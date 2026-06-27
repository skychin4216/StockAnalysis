package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.ApiProviderConfig
import com.chin.stockanalysis.OpenAiCompatibleProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ## AI Provider 共享池（v3 - 防凍結超時版）
 *
 * 核心改進：
 * 1. 超時自動釋放：acquire 超過 90 秒未 release 的佔用自動清理
 * 2. 前後台切換清理：App 回到前臺時強制清掃過期佔用
 * 3. 進程解凍檢測：系統凍結後恢復時自動重置狀態
 * 4. 降級模式：所有 Provider 被鎖時創建臨時 Provider
 * 5. 詳細日誌：記錄佔用者調用棧，方便排查
 */
object AiProviderPool {

    private const val TAG = "AiProviderPool"

        /** 佔用超時時間（秒）：30 秒自動釋放，避免任何鎖死 */
    private const val OCCUPY_TIMEOUT_MS = 30_000L

    /** 健康檢測超時（秒） */
    private const val HEALTH_PROBE_TIMEOUT_MS = 3_000L

    /** AI 請求超時（秒） */
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
     * 獲取最優可用 AI Provider
     *
     * @param callerTag 調用方標識（用於日誌排查）
     * @param timeoutMs 等待超時（默認 60 秒）
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
                // 1. 清掃過期佔用
                sweepExpiredLocked()

                // 2. 嘗試獲取空閒 Provider
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

                // 3. 全部被佔用 → 嘗試共享第一個有 key 的（不論健康狀態）
                for (preferredId in PRIORITY_ORDER) {
                    val config = mgr.getProviderConfig(preferredId) ?: continue
                    if (config.apiKey.isBlank()) continue

                    val provider = getOrCreateProvider(config)
                    val slot = Slot(preferredId, "${config.name}(共享)", provider, allocatedBy = callerTag)
                    Log.w(TAG, "⚠️ 全部忙，共享: ${config.name} [by: $callerTag]")
                    return slot
                }
            }

            // 4. 等待 1 秒後重試（最多 5 秒）
            Log.d(TAG, "⏳ 等待釋放... [by: $callerTag]")
            delay(1_000L)
        }

        Log.e(TAG, "❌ ${timeoutMs}ms 內無可用 AI [by: $callerTag]")
        return null
    }

    /**
     * 取得所有健康的 AI Provider（不佔用，用於並行任務）
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
        Log.i(TAG, "📊 acquireAllHealthy → ${result.size} 個可用: ${result.joinToString { it.configName }}")
        return result
    }

    /**
     * 釋放 Slot（務必在 try-finally 中調用）
     */
    suspend fun release(slot: Slot?) {
        if (slot == null) return
        mutex.withLock {
            val removed = occupied.remove(slot.configId)
            if (removed != null) {
                Log.i(TAG, "🔓 release: ${slot.configName} [held: ${System.currentTimeMillis() - slot.allocatedAt}ms, by: ${slot.allocatedBy}]")
            } else {
                Log.w(TAG, "🔓 release: ${slot.configName} 已不在 occupied 中（可能超時自動釋放了）")
            }
        }
    }

    fun releaseNonBlocking(slot: Slot?) {
        if (slot == null) return
        scope.launch { release(slot) }
    }

    /**
     * 強制清掃所有過期佔用（App 回到前臺或進程解凍時調用）
     */
    fun sweepExpired() {
        val now = System.currentTimeMillis()
        if (now - lastSweepTime < 10_000L) return // 10 秒內不重複清掃
        lastSweepTime = now
        scope.launch {
            mutex.withLock { sweepExpiredLocked() }
        }
    }

    /** 內部：清掃超時佔用 */
    private fun sweepExpiredLocked() {
        val now = System.currentTimeMillis()
        val expired = occupied.filterValues { now - it.allocatedAt > OCCUPY_TIMEOUT_MS }
        if (expired.isNotEmpty()) {
            Log.w(TAG, "🧹 自動清掃 ${expired.size} 個超時佔用:")
            for ((id, slot) in expired) {
                occupied.remove(id)
                Log.w(TAG, "   • ${slot.configName} 超時 ${(now - slot.allocatedAt) / 1000}秒 [by: ${slot.allocatedBy}]")
            }
        }
    }

    /** 強制重置所有狀態（進程被凍結後恢復時調用） */
    fun emergencyReset(reason: String) {
        scope.launch {
            mutex.withLock {
                val count = occupied.size
                if (count > 0) {
                    Log.e(TAG, "🚨 緊急重置: $reason，清理 $count 個佔用")
                    for ((id, slot) in occupied) {
                        Log.e(TAG, "   • $id: ${slot.configName} 已持有 ${(System.currentTimeMillis() - slot.allocatedAt) / 1000}秒 [by: ${slot.allocatedBy}]")
                    }
                    occupied.clear()
                }
            }
            healthyCache.clear()
            Log.i(TAG, "🚨 緊急重置完成: $reason")
        }
    }

    /**
     * 快速连通性检测（缓存 60 秒，超時 5 秒）
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

    /** 獲取當前佔用狀態（用於調試） */
    fun dumpStatus(): String {
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("=== AiProviderPool 狀態 ===")
            appendLine("佔用數: ${occupied.size}")
            for ((id, slot) in occupied) {
                appendLine("  $id: ${slot.configName} 已持有 ${(now - slot.allocatedAt) / 1000}秒 [by: ${slot.allocatedBy}]")
            }
            appendLine("健康緩存: ${healthyCache.entries.joinToString()}")
        }
    }

    private fun initIfNeeded(context: Context) {
        if (configManager == null) configManager = ApiConfigManager.getInstance(context)
    }

    private fun getOrCreateProvider(config: ApiProviderConfig): ApiProvider {
        return providerCache.getOrPut(config.id) { OpenAiCompatibleProvider(config) }
    }
}
