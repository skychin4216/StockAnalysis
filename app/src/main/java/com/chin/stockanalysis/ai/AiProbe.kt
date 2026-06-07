package com.chin.stockanalysis.ai

import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProviderConfig
import com.chin.stockanalysis.OpenAiCompatibleProvider
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.io.IOException

/**
 * ## AI 探针（App 启动时后台测速）
 *
 * 启动时并发 Ping 所有已配置 API Key 的 Provider，选出最快的作为前台，
 * 最轻量的作为后台 fallback。
 *
 * ### 优先级逻辑
 * 1. 如果有 ≥2 个可用 Provider → 最快做前台(主对话)，第二快做后台(记忆提取/追问)
 * 2. 如果只有 1 个可用 → 全部用这个（原始行为，不竞争）
 * 3. 全部不可用 → null（等用户配置 Key）
 *
 * ### 使用方式
 * ```kotlin
 * AiProbe.runProbe(context) { result ->
 *     when (result) {
 *         is AiProbe.Result.Ready -> {
 *             // result.primary → 前台 Provider
 *             // result.secondary → 后台 Provider (null 表示单 AI 模式)
 *         }
 *         is AiProbe.Result.None -> { /* 无可用 AI */ }
 *     }
 * }
 * ```
 */
object AiProbe {

    private const val TAG = "AiProbe"
    private const val PROBE_TIMEOUT_SECONDS = 8L
    private const val MIN_RESPONSE_TIME_MS = 50L  // 低于此值视为探针失败(返回太快的错误)

    sealed class Result {
        /** 至少一个 Provider 可用 */
        data class Ready(
            val primary: ProviderWithLatency,           // 最快(前台)
            val secondary: ProviderWithLatency? = null  // 第二快(后台)，null 表示单AI模式
        ) : Result()

        /** 无可用 Provider */
        object None : Result()
    }

    data class ProviderWithLatency(
        val config: ApiProviderConfig,
        val latencyMs: Long,
        val provider: OpenAiCompatibleProvider
    )

    /**
     * 并发探测所有已配置 Key 的 Provider 是否可用及其延迟。
     *
     * @param context Android Context
     * @param onResult 探测完成回调（主线程）
     * @param onProgress 可选进度回调
     */
    fun runProbe(
        configManager: ApiConfigManager,
        onResult: (Result) -> Unit
    ) {
        val candidates = configManager.getAllConfigs()
            .filter { it.apiKey.isNotBlank() }
            .distinctBy { it.baseUrl } // 同 baseUrl 只测一次

        if (candidates.isEmpty()) {
            Log.w(TAG, "无可用 Provider（所有 Key 为空）")
            onResult(Result.None)
            return
        }

        Log.i(TAG, "🔍 开始探测 ${candidates.size} 个 Provider...")

        // 并发探测所有候选
        val results = mutableListOf<ProviderWithLatency>()
        var completed = 0
        val total = candidates.size

        for (config in candidates) {
            val provider = OpenAiCompatibleProvider(config)
            val client = OkHttpClient.Builder()
                .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val url = config.baseUrl.trimEnd('/') + "/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()

            val startMs = System.currentTimeMillis()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    synchronized(results) {
                        completed++
                        Log.d(TAG, "❌ ${config.name}: ${e.message?.take(60)}")
                        checkDone()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val latency = System.currentTimeMillis() - startMs
                    response.close()
                    synchronized(results) {
                        completed++
                        if (response.isSuccessful && latency > MIN_RESPONSE_TIME_MS) {
                            results.add(ProviderWithLatency(config, latency, provider))
                            Log.d(TAG, "✅ ${config.name}: ${latency}ms")
                        } else {
                            Log.d(TAG, "❌ ${config.name}: HTTP ${response.code} (${latency}ms)")
                        }
                        checkDone()
                    }
                }

                fun checkDone() {
                    if (completed >= total) {
                        // 按延迟排序
                        results.sortBy { it.latencyMs }
                        val finalResult = when {
                            results.isEmpty() -> {
                                Log.w(TAG, "所有 Provider 探测失败")
                                Result.None
                            }
                            results.size == 1 -> {
                                val p = results[0]
                                Log.i(TAG, "🎯 单 AI 模式: ${p.config.name} (${p.latencyMs}ms)")
                                Result.Ready(primary = p, secondary = null)
                            }
                            else -> {
                                val primary = results[0]
                                val secondary = results[1]
                                Log.i(TAG, "🎯 双 AI 模式: 前台=${primary.config.name}(${primary.latencyMs}ms) 后台=${secondary.config.name}(${secondary.latencyMs}ms)")
                                Result.Ready(primary, secondary)
                            }
                        }
                        onResult(finalResult)
                    }
                }
            })
        }
    }
}