package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.data.StockDataSource
import okhttp3.Request
import org.json.JSONObject

/**
 * 东方财富实时行情备用数据源。
 *
 * 接口示例：
 * https://push2.eastmoney.com/api/qt/ulist.np/get?secids=1.600519,0.000858&fields=f12,f14,f2,f3,f4,f5,f6,f15,f16,f17,f18
 *
 * 常见字段：
 * f12 代码、f14 名称、f2 当前价、f3 涨跌幅、f4 涨跌额、f5 成交量、f6 成交额、
 * f15 最高、f16 最低、f17 开盘、f18 昨收。
 *
 * ✅ 改进：
 * - 使用共享 OkHttp 连接池
 * - 指数退避重试
 * - 添加 User-Agent 头（反爬）
 * - 更完善的 null 安全处理
 */
class EastMoneyStockSource : StockDataSource {

    private val client = HttpClientProvider.realtimeClient
    private val healthClient = HttpClientProvider.healthCheckClient
    private val tag = "EastMoneySource"

    companion object {
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 300L
    }

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, StockRealtime>()

        codes.chunked(50).forEach { batch ->
            val secids = batch.joinToString(",") { toSecId(it) }
            val url = "https://push2.eastmoney.com/api/qt/ulist.np/get" +
                "?fltt=2&invt=2&secids=$secids&fields=f12,f14,f2,f3,f4,f5,f6,f15,f16,f17,f18"

            val body = executeWithRetry(url)
            if (body != null) {
                parseBody(body).forEach { stock -> result[stock.code] = stock }
            }
        }
        return result
    }

    /**
     * 带指数退避的重试机制
     */
    private fun executeWithRetry(url: String): String? {
        for (attempt in 0..MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    val delay = RETRY_DELAY_MS * (1L shl (attempt - 1))
                    Thread.sleep(delay)
                }

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://quote.eastmoney.com/")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(tag, "  attempt#$attempt HTTP ${response.code}")
                    continue
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Log.w(tag, "  attempt#$attempt empty body")
                    continue
                }

                return body
            } catch (e: Exception) {
                Log.w(tag, "  attempt#$attempt: ${e.message}")
            }
        }

        Log.e(tag, "  all retries exhausted for: ${url.take(100)}")
        return null
    }

    private fun parseBody(body: String): List<StockRealtime> {
        return runCatching {
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return emptyList()
            val diff = data.optJSONArray("diff") ?: return emptyList()

            buildList {
                for (i in 0 until diff.length()) {
                    val item = diff.optJSONObject(i) ?: continue
                    val rawCode = item.optString("f12")
                    val code = normalizeBack(rawCode)

                    // 检查核心字段是否有效
                    val price = item.optDoubleSafe("f2")
                    if (price <= 0) continue

                    val yestClose = item.optDoubleSafe("f18")
                    val changePercent = item.optDoubleSafe("f3")
                    val changeAmount = item.optDoubleSafe("f4")

                    add(
                        StockRealtime(
                            code = code,
                            name = item.optString("f14"),
                            price = price,
                            open = item.optDoubleSafe("f17"),
                            yestClose = yestClose,
                            high = item.optDoubleSafe("f15"),
                            low = item.optDoubleSafe("f16"),
                            volume = (item.optDoubleSafe("f5") * 100).toLong(), // 东方财富单位是手->股
                            amount = item.optDoubleSafe("f6") * 10000, // 万元->元
                            changePercent = changePercent,
                            changeAmount = if (changeAmount.isNaN() || changeAmount == 0.0) price - yestClose else changeAmount,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** sh600519 -> 1.600519, sz000858 -> 0.000858, bj83xxxx -> 0.83xxxx */
    private fun toSecId(code: String): String {
        val raw = code.takeLast(6)
        val market = when {
            code.startsWith("sh") || raw.startsWith("6") || raw.startsWith("9") -> "1"
            else -> "0"
        }
        return "$market.$raw"
    }

    private fun normalizeBack(rawCode: String): String {
        return when {
            rawCode.startsWith("6") || rawCode.startsWith("9") -> "sh$rawCode"
            rawCode.startsWith("4") || rawCode.startsWith("8") -> "bj$rawCode"
            else -> "sz$rawCode"
        }
    }

    private fun JSONObject.optDoubleSafe(key: String): Double {
        val value = opt(key) ?: return 0.0
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    override fun isAvailable(): Boolean = runCatching {
        val request = Request.Builder()
            .url("https://push2.eastmoney.com/api/qt/ulist.np/get?secids=1.000001&fields=f12,f14,f2")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        healthClient.newCall(request).execute().isSuccessful
    }.getOrDefault(false)

    override fun priority(): Int = 3
}