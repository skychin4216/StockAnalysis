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
 * - 动态选择已配置 apiKey 的 Provider，失败后自动切换下一个
 * - 不检查健康缓存，失败后自动切换
 * - 与 AiProviderPool 互斥（不同时占用同一 Provider）
 *
 * 目的：避免策略并发调用多 Provider 导致限流/混乱，
 *       保持策略的稳定性。
 */
object SimpleAiProvider {

    private const val TAG = "SimpleAiProvider"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val providerCache = mutableMapOf<String, ApiProvider>()
    private var configManager: ApiConfigManager? = null
    private var currentConfigId: String? = null
    private var currentSlot: AiProviderPool.Slot? = null
    /** 已失败的 provider ID，避免循环切换 */
    private val failedIds = mutableSetOf<String>()

    /**
     * 动态获取所有已配置 apiKey 的 Provider 列表（按 builtInProviders 顺序）
     */
    private fun getAvailableIds(): List<String> {
        val mgr = configManager ?: return emptyList()
        return mgr.getAllConfigs()
            .filter { it.apiKey.isNotBlank() }
            .map { it.id }
    }

    /**
     * 获取当前可用的 Provider，失败时自动切到下一个
     */
    suspend fun acquire(context: Context): AiProviderPool.Slot? {
        initIfNeeded(context)
        val mgr = configManager ?: return null

        mutex.withLock {
            val availableIds = getAvailableIds()
            if (availableIds.isEmpty()) {
                Log.e(TAG, "❌ 无可用 Provider（所有 Provider 均未配置 apiKey）")
                return null
            }

            // 先尝试上次成功的（未在失败列表中）
            currentConfigId?.let { id ->
                if (id !in failedIds) {
                    val config = mgr.getProviderConfig(id)
                    if (config != null && config.apiKey.isNotBlank()) {
                        val provider = getOrCreateProvider(config)
                        val slot = AiProviderPool.Slot(id, config.name, provider)
                        currentSlot = slot
                        Log.d(TAG, "✅ 使用: ${config.name}")
                        return slot
                    }
                }
            }

            // 找第一个可用的（跳过已失败的）
            for (id in availableIds) {
                if (id in failedIds) continue
                val config = mgr.getProviderConfig(id) ?: continue
                if (config.apiKey.isBlank()) continue
                val provider = getOrCreateProvider(config)
                val slot = AiProviderPool.Slot(id, config.name, provider)
                currentConfigId = id
                currentSlot = slot
                Log.i(TAG, "✅ acquire → ${config.name}")
                return slot
            }

            // 全部失败，不再重试
            Log.e(TAG, "❌ 所有 ${availableIds.size} 个 Provider 均已失败")
            return null
        }
    }

    /**
     * 切换到下一个 Provider（当前 Provider 失败时调用）
     */
    suspend fun switchToNext(context: Context): AiProviderPool.Slot? {
        val mgr = configManager ?: return null
        mutex.withLock {
            val availableIds = getAvailableIds()
            if (availableIds.isEmpty()) return null

            // 标记当前 provider 为已失败
            currentConfigId?.let { failedIds.add(it) }

            // 从当前位置的下一个开始，找第一个未失败的
            val currentIdx = availableIds.indexOf(currentConfigId)
            for (i in 1..availableIds.size) {
                val nextIdx = (currentIdx + i) % availableIds.size
                val nextId = availableIds[nextIdx]
                if (nextId in failedIds) continue
                val config = mgr.getProviderConfig(nextId)
                if (config != null && config.apiKey.isNotBlank()) {
                    val provider = getOrCreateProvider(config)
                    currentConfigId = nextId
                    currentSlot = AiProviderPool.Slot(nextId, config.name, provider)
                    Log.i(TAG, "🔄 切换 → ${config.name}")
                    return currentSlot
                }
            }

            // 全部失败，不再重试
            Log.e(TAG, "❌ 所有 ${availableIds.size} 个 Provider 均已失败")
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