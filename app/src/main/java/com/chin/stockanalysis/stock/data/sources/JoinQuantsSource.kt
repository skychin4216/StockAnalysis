package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.data.StockDataSource
import okhttp3.Request
import org.json.JSONObject

/**
 * 聚宽 (JoinQuants) 数据源 - 专业级数据源（优先级2）
 *
 * 特点：
 * - 需要 Token（通过 SharedPreferences 配置）
 * - 数据精准度高
 * - 支持 K 线、财务数据、信号回测
 *
 * ✅ 改进：
 * - 使用共享 Http 连接池
 * - 指数退避重试
 * - 错误处理优化
 *
 * 使用示例：
 * ```kotlin
 * StockDataSourceFactory.saveJoinQuantsToken(context, "your-token")
 * val source = JoinQuantsSource(token)
 * ```
 */
class JoinQuantsSource(private val token: String = "") : StockDataSource {

    private val client = HttpClientProvider.realtimeClient
    private val healthClient = HttpClientProvider.healthCheckClient
    private val tag = "JoinQuantsSource"

    companion object {
        private const val BASE_URL = "https://api.joinquants.com/api"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 500L
    }

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty() || token.isBlank()) return emptyMap()

        Log.d(tag, "Fetching ${codes.size} codes")

        val result = mutableMapOf<String, StockRealtime>()

        // 聚宽接口可能有限制，按5个一分批
        codes.chunked(5).forEach { batch ->
            val batchResult = executeWithRetry(batch)
            result.putAll(batchResult)
        }

        Log.d(tag, "Got ${result.size}/${codes.size}")
        return result
    }

    /**
     * 带指数退避重试的批量查询
     */
    private fun executeWithRetry(batch: List<String>): Map<String, StockRealtime> {
        val jqCodes = batch.joinToString(",") { normalizeToJQFormat(it) }
        val url = "${BASE_URL}/query_quote?security=${jqCodes}&token=$token"

        for (attempt in 0..MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    val delay = RETRY_DELAY_MS * (1L shl (attempt - 1))
                    Thread.sleep(delay)
                }

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(tag, "attempt#$attempt HTTP ${response.code}")
                    continue
                }

                val body = response.body?.string() ?: continue
                return parseResponse(body)

            } catch (e: Exception) {
                Log.w(tag, "attempt#$attempt: ${e.message}")
            }
        }

        return emptyMap()
    }

    private fun parseResponse(body: String): Map<String, StockRealtime> {
        val result = mutableMapOf<String, StockRealtime>()

        return try {
            val jsonResponse = JSONObject(body)

            if (jsonResponse.optInt("error_code") != 0) {
                Log.w(tag, "API error: ${jsonResponse.optString("error_msg")}")
                return result
            }

            val dataObj = jsonResponse.optJSONObject("data") ?: return result
            val iterator = dataObj.keys()

            while (iterator.hasNext()) {
                val jqCode = iterator.next()
                val item = dataObj.optJSONObject(jqCode) ?: continue

                val androidCode = reverseNormalizeFromJQFormat(jqCode)
                val price = item.optDouble("current", 0.0)
                if (price <= 0) continue

                val yestClose = item.optDouble("last_close", price)

                val stock = StockRealtime(
                    code = androidCode,
                    name = item.optString("name", ""),
                    price = price,
                    open = item.optDouble("open", 0.0),
                    yestClose = yestClose,
                    high = item.optDouble("high", 0.0),
                    low = item.optDouble("low", 0.0),
                    volume = item.optLong("volume", 0L),
                    amount = item.optDouble("money", 0.0),
                    changePercent = if (yestClose == 0.0) 0.0 else ((price - yestClose) / yestClose) * 100,
                    changeAmount = price - yestClose,
                    timestamp = System.currentTimeMillis()
                )
                result[androidCode] = stock
            }

            result
        } catch (e: Exception) {
            Log.e(tag, "Parse error: ${e.message}")
            emptyMap()
        }
    }

    /** sh600519 -> 600519.XSHG */
    private fun normalizeToJQFormat(androidCode: String): String {
        val code = androidCode.takeLast(6)
        return when {
            androidCode.startsWith("sh") -> "$code.XSHG"
            androidCode.startsWith("sz") -> "$code.XSHE"
            androidCode.startsWith("bj") -> "$code.XBJS"
            else -> "$code.XSHG"
        }
    }

    /** 600519.XSHG -> sh600519 */
    private fun reverseNormalizeFromJQFormat(jqCode: String): String {
        val code = jqCode.substringBefore(".")
        val market = jqCode.substringAfter(".")
        return when (market) {
            "XSHG" -> "sh$code"
            "XSHE" -> "sz$code"
            "XBJS" -> "bj$code"
            else -> "sh$code"
        }
    }

    override fun isAvailable(): Boolean {
        if (token.isBlank()) return false

        return try {
            val url = "${BASE_URL}/query_quote?security=000001.XSHE&token=$token"
            val request = Request.Builder().url(url).build()
            healthClient.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override fun priority(): Int = 1
}