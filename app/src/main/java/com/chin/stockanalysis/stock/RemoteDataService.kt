package com.chin.stockanalysis.stock

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ## 云服务层 - 远程数据服务
 *
 * 与 Aliyun Function Compute 或自部署 Python 后端通信。
 * 将 CPU 密集型任务（产业链分析、行业热点等）转发到云端处理。
 *
 * ### API 定义
 * ```
 * GET  /health                    → 健康检查
 * POST /api/stock/realtime        → 并发获取实时行情（云端聚合）
 * POST /api/analysis/complex      → 复杂分析（产业链/行业热点等）
 * ```
 *
 * ### 使用示例
 * ```kotlin
 * val remote = RemoteDataService("https://your-aliyun-url.fc.aliyuncs.com")
 * val isOk = remote.healthCheck()
 * val analysis = remote.analyzeIndustryChain("人形机器人前10股票")
 * ```
 *
 * ### 当云端不可用时的降级策略
 * - healthCheck() 失败 → 所有云端功能降级到本地
 * - getRealtime() 失败 → 回退到本地的 MultiSourceStockRepository
 * - analyzeComplex() 失败 → 回退到本地的 AiStockAnalyzer
 */
class RemoteDataService(
    /** Aliyun FC 部署 URL，如 "https://xxx-cn-hangzhou.fc.aliyuncs.com" */
    private val baseUrl: String,
    /** 请求超时时间（毫秒），默认 10 秒 */
    private val timeoutMs: Long = 10_000L
) {
    private val tag = "RemoteDataService"

    /** 共享 OkHttp 客户端 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ======================== 公开 API ========================

    /**
     * 健康检查 - 检测云端服务是否可用
     *
     * @return true 表示服务可用
     */
    fun healthCheck(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            Log.d(tag, "healthCheck → $ok (HTTP ${response.code})")
            ok
        } catch (e: Exception) {
            Log.w(tag, "healthCheck failed: ${e.message}")
            false
        }
    }

    /**
     * 从云端获取实时行情（云端并发聚合多个数据源）
     *
     * @param codes 股票代码列表
     * @return JSON 响应字符串，失败时返回 null
     */
    fun getRealtime(codes: List<String>): String? {
        if (codes.isEmpty()) return null

        return try {
            val jsonBody = JSONObject().apply {
                put("codes", codes)
            }
            val body = jsonBody.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/api/stock/realtime")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(tag, "getRealtime HTTP ${response.code}")
                return null
            }

            val result = response.body?.string()
            Log.d(tag, "getRealtime(${codes.size} codes) → ${result?.length ?: 0} chars")
            result
        } catch (e: Exception) {
            Log.e(tag, "getRealtime failed: ${e.message}")
            null
        }
    }

    /**
     * 复杂分析 - 产业链分析、行业热点等 CPU 密集型任务
     *
     * @param query 用户查询文本
     * @return JSON 响应字符串，失败时返回 null
     */
    fun analyzeComplex(query: String): String? {
        if (query.isBlank()) return null

        return try {
            val jsonBody = JSONObject().apply {
                put("query", query)
            }
            val body = jsonBody.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/api/analysis/complex")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(tag, "analyzeComplex HTTP ${response.code}")
                return null
            }

            val result = response.body?.string()
            Log.d(tag, "analyzeComplex('${query.take(30)}...') → ${result?.length ?: 0} chars")
            result
        } catch (e: Exception) {
            Log.e(tag, "analyzeComplex failed: ${e.message}")
            null
        }
    }

    /**
     * 云端服务状态诊断 - 一次性检查所有 API 的可用性
     *
     * @return 诊断信息文本
     */
    fun getDiagnostics(): String {
        val healthOk = healthCheck()
        return buildString {
            appendLine("═══════════════════════════════════════")
            appendLine("☁️ RemoteDataService Diagnostics")
            appendLine("═══════════════════════════════════════")
            appendLine("Base URL: $baseUrl")
            appendLine("Timeout: ${timeoutMs}ms")
            appendLine("Health Check: ${if (healthOk) "✓" else "✗"}")
            appendLine("└─ 如果失败：确认 Aliyun FC 已部署且 URL 正确")
            if (!healthOk) {
                appendLine("⚠️ 云端服务不可用，将降级到本地处理")
            }
            appendLine("═══════════════════════════════════════")
        }
    }

    /**
     * 关闭客户端（释放资源）
     */
    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        Log.d(tag, "Shutdown complete")
    }
}