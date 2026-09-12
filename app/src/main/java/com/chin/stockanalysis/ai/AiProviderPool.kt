package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiKeysLoader
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.ApiProviderConfig
import com.chin.stockanalysis.OpenAiCompatibleProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ## AI Provider 共享池（v3 - 防冻结超时版）
 *
 * 核心改进：
 * 1. 超时自动释放：acquire 超过 90 秒未 release 的占用自动清理
 * 2. 前后台切换清理：App 回到前台时强制清扫过期占用
 * 3. 进程解冻检测：系统冻结后恢复时自动重置状态
 * 4. 降级模式：所有 Provider 被锁时创建临时 Provider
 * 5. 详细日志：记录占用者调用栈，方便排查
 */
object AiProviderPool {

    private const val TAG = "AiProviderPool"

        /** 占用超时时间（秒）：30 秒自动释放，避免任何锁死 */
    private const val OCCUPY_TIMEOUT_MS = 30_000L

    /** 健康检测超时（秒）：豆包等火山引擎 API 需更长时间 */
    private const val HEALTH_PROBE_TIMEOUT_MS = 5_000L

    /** AI 请求超时（秒） */
    private const val AI_REQUEST_TIMEOUT_MS = 30_000L

    /** 优先级顺序 */
    private val PRIORITY_ORDER = listOf(
        "doubao",
        "dashscope-qwen3",
        "deepseek-official",
        "siliconflow-v3-flash"
    )

    data class Slot(
        val configId: String,
        val configName: String,
        val provider: ApiProvider,
        val allocatedAt: Long = System.currentTimeMillis(),
        val allocatedBy: String = Thread.currentThread().stackTrace
            .filter { it.className.contains("stockanalysis") && !it.className.contains("AiProviderPool") }
            .take(3)
            .joinToString(" → ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val occupied = mutableMapOf<String, Slot>()
    private val providerCache = mutableMapOf<String, ApiProvider>()
    private val healthyCache = mutableMapOf<String, Boolean>()
    private val healthyCacheTime = mutableMapOf<String, Long>()
    private var configManager: ApiConfigManager? = null
    private var lastSweepTime = 0L

    /**
     * 获取最优可用 AI Provider
     *
     * @param callerTag 调用方标识（用于日志排查）
     * @param timeoutMs 等待超时（默认 60 秒）
     */
    suspend fun acquire(
        context: Context,
        callerTag: String = "unknown",
        timeoutMs: Long = 60_000L
    ): Slot? {
        initIfNeeded(context)
        val mgr = configManager ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            mutex.withLock {
                // 1. 清扫过期占用
                sweepExpiredLocked()

                // 2. 尝试获取空闲 Provider
                for (preferredId in PRIORITY_ORDER) {
                    val config = mgr.getProviderConfig(preferredId) ?: continue
                    if (config.apiKey.isBlank()) continue
                    if (occupied.containsKey(preferredId)) continue

                    if (!isHealthy(config.id, config)) continue

                    val provider = getOrCreateProvider(config)
                    val slot = Slot(preferredId, config.name, provider, allocatedBy = callerTag)
                    occupied[preferredId] = slot
                    Log.i(TAG, "✅ acquire → ${config.name} [by: $callerTag]")
                    return slot
                }

                // 3. 全部被占用 → 尝试共享第一个有 key 的（不论健康状态）
                var anyKeyed = false
                for (preferredId in PRIORITY_ORDER) {
                    val config = mgr.getProviderConfig(preferredId) ?: continue
                    if (config.apiKey.isBlank()) continue
                    anyKeyed = true

                    val provider = getOrCreateProvider(config)
                    val slot = Slot(preferredId, "${config.name}(共享)", provider, allocatedBy = callerTag)
                    Log.w(TAG, "⚠️ 全部忙，共享: ${config.name} [by: $callerTag]")
                    return slot
                }
                // 3.5 一个 key 都没配：立即失败，不必空转等待满 timeout（否则调用方白等 60s 才报错）
                if (!anyKeyed) {
                    Log.e(TAG, "❌ 未配置任何 AI Provider 的 apiKey，立即失败 [by: $callerTag]")
                    // Key 来源诊断：区分「properties 没读到」「app_config.json 没写 api_key」「真没配」
                    Log.e(TAG, "   Key 诊断: " + ApiKeysLoader.describeKeySources(PRIORITY_ORDER))
                    return null
                }
            }

            // 4. 等待 1 秒后重试（最多 5 秒）
            Log.d(TAG, "⏳ 等待释放... [by: $callerTag]")
            delay(1_000L)
        }

        Log.e(TAG, "❌ ${timeoutMs}ms 内无可用 AI [by: $callerTag]")
        return null
    }

    /**
     * 取得所有健康的 AI Provider（不占用，用于并行任务）
     */
    suspend fun acquireAllHealthy(context: Context): List<Slot> {
        initIfNeeded(context); val result = mutableListOf<Slot>()
        val mgr = configManager ?: return result
        mutex.withLock {
            sweepExpiredLocked()
            for (preferredId in PRIORITY_ORDER) {
                val config = mgr.getProviderConfig(preferredId) ?: continue
                if (config.apiKey.isBlank()) continue
                if (!isHealthy(config.id, config)) continue
                val provider = getOrCreateProvider(config)
                result.add(Slot(config.id, config.name, provider))
            }
        }
        Log.i(TAG, "📊 acquireAllHealthy → ${result.size} 个可用: ${result.joinToString { it.configName }}")
        return result
    }

    /**
     * 释放 Slot（务必在 try-finally 中调用）
     */
    suspend fun release(slot: Slot?) {
        if (slot == null) return
        mutex.withLock {
            val removed = occupied.remove(slot.configId)
            if (removed != null) {
                Log.i(TAG, "🔓 release: ${slot.configName} [held: ${System.currentTimeMillis() - slot.allocatedAt}ms, by: ${slot.allocatedBy}]")
            } else {
                Log.w(TAG, "🔓 release: ${slot.configName} 已不在 occupied 中（可能超时自动释放了）")
            }
        }
    }

    fun releaseNonBlocking(slot: Slot?) {
        if (slot == null) return
        scope.launch { release(slot) }
    }

    /**
     * 强制清扫所有过期占用（App 回到前台或进程解冻时调用）
     */
    fun sweepExpired() {
        val now = System.currentTimeMillis()
        if (now - lastSweepTime < 10_000L) return // 10 秒内不重复清扫
        lastSweepTime = now
        scope.launch {
            mutex.withLock { sweepExpiredLocked() }
        }
    }

    /** 内部：清扫超时占用 */
    private fun sweepExpiredLocked() {
        val now = System.currentTimeMillis()
        val expired = occupied.filterValues { now - it.allocatedAt > OCCUPY_TIMEOUT_MS }
        if (expired.isNotEmpty()) {
            Log.w(TAG, "🧹 自动清扫 ${expired.size} 个超时占用:")
            for ((id, slot) in expired) {
                occupied.remove(id)
                Log.w(TAG, "   • ${slot.configName} 超时 ${(now - slot.allocatedAt) / 1000}秒 [by: ${slot.allocatedBy}]")
            }
        }
    }

    /** 强制重置所有状态（进程被冻结后恢复时调用） */
    fun emergencyReset(reason: String) {
        scope.launch {
            mutex.withLock {
                val count = occupied.size
                if (count > 0) {
                    Log.e(TAG, "🚨 紧急重置: $reason，清理 $count 个占用")
                    for ((id, slot) in occupied) {
                        Log.e(TAG, "   • $id: ${slot.configName} 已持有 ${(System.currentTimeMillis() - slot.allocatedAt) / 1000}秒 [by: ${slot.allocatedBy}]")
                    }
                    occupied.clear()
                }
            }
            healthyCache.clear()
            Log.i(TAG, "🚨 紧急重置完成: $reason")
        }
    }

    /**
     * 快速连通性检测（缓存 60 秒，超时 5 秒）
     */
    private suspend fun isHealthy(configId: String, config: ApiProviderConfig): Boolean {
        // 缓存 10 秒有效（避免进程冻结局导致缓存永不更新）
        healthyCache[configId]?.let { healthy ->
            val cacheTime = healthyCacheTime[configId] ?: 0L
            if (System.currentTimeMillis() - cacheTime < 10_000L) return healthy
            // 过期，清除缓存重新探测
            healthyCache.remove(configId)
            healthyCacheTime.remove(configId)
        }

        return try {
            val provider = getOrCreateProvider(config)
            val deferred = CompletableDeferred<Boolean>()
            withTimeout(HEALTH_PROBE_TIMEOUT_MS) {
                provider.sendMessageStream(
                    messages = emptyList(),
                    systemPrompt = "ping",
                    onSuccess = {},
                    onComplete = {
                        healthyCache[configId] = true; healthyCacheTime[configId] = System.currentTimeMillis()
                        deferred.complete(true)
                    },
                    onError = {
                        healthyCache[configId] = false; healthyCacheTime[configId] = System.currentTimeMillis()
                        Log.w(TAG, "❌ ${config.name} 不可用: $it")
                        deferred.complete(false)
                    }
                )
                deferred.await()
            }
        } catch (e: Exception) {
            healthyCache[configId] = false; healthyCacheTime[configId] = System.currentTimeMillis()
            Log.w(TAG, "❌ ${config.name} 探测失败: ${e.message}")
            false
        }
    }

    /** 清除连通性缓存（网络恢复时调用） */
    fun invalidateHealthCache() {
        healthyCache.clear()
        healthyCacheTime.clear()
    }

    /** 获取当前占用状态（用于调试） */
    fun dumpStatus(): String {
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("=== AiProviderPool 状态 ===")
            appendLine("占用数: ${occupied.size}")
            for ((id, slot) in occupied) {
                appendLine("  $id: ${slot.configName} 已持有 ${(now - slot.allocatedAt) / 1000}秒 [by: ${slot.allocatedBy}]")
            }
            appendLine("健康缓存: ${healthyCache.entries.joinToString()}")
        }
    }

    private fun initIfNeeded(context: Context) {
        if (configManager == null) configManager = ApiConfigManager.getInstance(context)
    }

    private fun getOrCreateProvider(config: ApiProviderConfig): ApiProvider {
        return providerCache.getOrPut(config.id) { OpenAiCompatibleProvider(config) }
    }
}
