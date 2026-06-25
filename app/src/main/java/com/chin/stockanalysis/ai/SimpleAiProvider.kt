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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ## 单一 AI Provider（策略使用）
 *
 * - 仅使用一个 API，按优先级：豆包 > Qwen
 * - 不检查健康缓存，失败后自动切换
 * - 与 AiProviderPool 互斥（不同时占用同一 Provider）
 *
 * 目的：避免策略并发调用多 Provider 导致限流/混乱，
 *       保持策略的稳定性。
 */
object SimpleAiProvider {

    private const val TAG = "SimpleAiProvider"

    /** 策略专用优先级 */
    private val PRIORITY = listOf("doubao", "dashscope-qwen3")

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val providerCache = mutableMapOf<String, ApiProvider>()
    private var configManager: ApiConfigManager? = null
    private var currentConfigId: String? = null
    private var currentSlot: AiProviderPool.Slot? = null

    /**
     * 获取当前可用的 Provider，失败时自动切到下一个
     */
    suspend fun acquire(context: Context): AiProviderPool.Slot? {
        initIfNeeded(context)
        val mgr = configManager ?: return null

        mutex.withLock {
            // 先尝试上次成功的
            currentConfigId?.let { id ->
                val config = mgr.getProviderConfig(id)
                if (config != null && config.apiKey.isNotBlank()) {
                    val provider = getOrCreateProvider(config)
                    val slot = AiProviderPool.Slot(id, config.name, provider)
                    currentSlot = slot
                    Log.d(TAG, "✅ 使用: ${config.name}")
                    return slot
                }
            }
            // 按优先级找第一个可用的
            for (id in PRIORITY) {
                val config = mgr.getProviderConfig(id) ?: continue
                if (config.apiKey.isBlank()) continue
                val provider = getOrCreateProvider(config)
                val slot = AiProviderPool.Slot(id, config.name, provider)
                currentConfigId = id
                currentSlot = slot
                Log.i(TAG, "✅ acquire → ${config.name}")
                return slot
            }
            Log.e(TAG, "❌ 无可用 Provider")
            return null
        }
    }

    /**
     * 切换到下一个 Provider（当前 Provider 失败时调用）
     */
    suspend fun switchToNext(context: Context): AiProviderPool.Slot? {
        val mgr = configManager ?: return null
        mutex.withLock {
            val currentIdx = PRIORITY.indexOf(currentConfigId)
            val nextIdx = if (currentIdx >= 0 && currentIdx + 1 < PRIORITY.size) currentIdx + 1 else 0
            val nextId = PRIORITY[nextIdx]
            val config = mgr.getProviderConfig(nextId)
            if (config != null && config.apiKey.isNotBlank()) {
                val provider = getOrCreateProvider(config)
                currentConfigId = nextId
                currentSlot = AiProviderPool.Slot(nextId, config.name, provider)
                Log.i(TAG, "🔄 切换 → ${config.name}")
                return currentSlot
            }
            return null
        }
    }

    fun release() {
        scope.launch {
            mutex.withLock {
                currentSlot = null
            }
        }
    }

    private fun initIfNeeded(context: Context) {
        if (configManager == null) configManager = ApiConfigManager.getInstance(context)
    }

    private fun getOrCreateProvider(config: ApiProviderConfig): ApiProvider {
        return providerCache.getOrPut(config.id) { OpenAiCompatibleProvider(config) }
    }
}