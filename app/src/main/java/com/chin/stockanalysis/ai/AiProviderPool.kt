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
 * ## AI Provider 共享池（v2）
 *
 * 跨窗口分配 AI Provider：
 * - 优先级：豆包 > Qwen > DeepSeek > 硅基流动
 * - 已被占用的跳过，找下一个可用（需通过连通性检测）
 * - 全部不可用时等待释放
 */
object AiProviderPool {

    private const val TAG = "AiProviderPool"

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
        val allocatedAt: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val occupied = mutableMapOf<String, Slot>()
    private val providerCache = mutableMapOf<String, ApiProvider>()
    private val healthyCache = mutableMapOf<String, Boolean>()
    private var configManager: ApiConfigManager? = null

    /**
     * 获取最优可用 AI Provider
     */
    suspend fun acquire(context: Context): Slot? {
        initIfNeeded(context)
        val mgr = configManager ?: return null

        // 尝试获取，全部忙时等待重试
        repeat(30) { attempt ->
            mutex.withLock {
                for (preferredId in PRIORITY_ORDER) {
                    val config = mgr.getProviderConfig(preferredId) ?: continue
                    if (config.apiKey.isBlank()) continue // 无 key 跳过
                    if (occupied.containsKey(preferredId)) continue // 已被占用

                    // 连通性检测（缓存 60s）
                    if (!isHealthy(config.id, config)) continue

                    val provider = getOrCreateProvider(config)
                    val slot = Slot(preferredId, config.name, provider)
                    occupied[preferredId] = slot
                    Log.i(TAG, "✅ acquire → ${config.name}")
                    return slot
                }

                // 全部不可用 → 尝试共享第一个健康的
                for (preferredId in PRIORITY_ORDER) {
                    val config = mgr.getProviderConfig(preferredId) ?: continue
                    if (config.apiKey.isBlank()) continue
                    if (!isHealthy(config.id, config)) continue

                    val provider = getOrCreateProvider(config)
                    val slot = Slot(preferredId, "${config.name}(共享)", provider)
                    Log.w(TAG, "⚠️ 全部忙，共享: ${config.name}")
                    return slot
                }
            }

            Log.d(TAG, "⏳ 第${attempt+1}次等待...")
            delay(2000L)
        }

        Log.e(TAG, "❌ 30次重试后仍无可用 AI")
        return null
    }

    /**
     * 释放 Slot
     */
    suspend fun release(slot: Slot?) {
        if (slot == null) return
        mutex.withLock {
            occupied.remove(slot.configId)
            Log.i(TAG, "🔓 release: ${slot.configName}")
        }
    }

    fun releaseNonBlocking(slot: Slot?) {
        if (slot == null) return
        scope.launch { release(slot) }
    }

    /**
     * 快速连通性检测（缓存 60 秒）
     */
    private suspend fun isHealthy(configId: String, config: ApiProviderConfig): Boolean {
        healthyCache[configId]?.let { return it }

        return try {
            val provider = getOrCreateProvider(config)
            val deferred = CompletableDeferred<Boolean>()
            withTimeout(3000L) {
                provider.sendMessageStream(
                    messages = emptyList(),
                    systemPrompt = "ping",
                    onSuccess = {},
                    onComplete = {
                        healthyCache[configId] = true
                        deferred.complete(true)
                    },
                    onError = {
                        healthyCache[configId] = false
                        Log.w(TAG, "❌ ${config.name} 不可用: $it")
                        deferred.complete(false)
                    }
                )
                deferred.await()
            }
        } catch (e: Exception) {
            healthyCache[configId] = false
            Log.w(TAG, "❌ ${config.name} 探测失败: ${e.message}")
            false
        }
    }

    /** 清除连通性缓存（网络恢复时调用） */
    fun invalidateHealthCache() {
        healthyCache.clear()
    }

    private fun initIfNeeded(context: Context) {
        if (configManager == null) configManager = ApiConfigManager.getInstance(context)
    }

    private fun getOrCreateProvider(config: ApiProviderConfig): ApiProvider {
        return providerCache.getOrPut(config.id) { OpenAiCompatibleProvider(config) }
    }
}