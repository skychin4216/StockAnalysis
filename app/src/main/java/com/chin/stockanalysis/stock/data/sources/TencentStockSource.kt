package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.data.StockDataSource
import okhttp3.Request

/**
 * 腾讯财经实时行情备用数据源。
 *
 * 接口示例：https://qt.gtimg.cn/q=sh600519,sz000858
 * 返回格式较长，以 ~ 分隔，常用字段：
 * 1 名称、3 当前价、4 昨收、5 今开、30 时间、31 涨跌、32 涨跌幅、33 最高、34 最低、36 成交量、37 成交额。
 *
 * ✅ 改进：
 * - 使用共享 OkHttp 连接池
 * - 指数退避重试
 * - 添加 User-Agent 头
 */
class TencentStockSource : StockDataSource {

    private val client = HttpClientProvider.realtimeClient
    private val healthClient = HttpClientProvider.healthCheckClient
    private val tag = "TencentSource"

    companion object {
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 300L
    }

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, StockRealtime>()

        codes.chunked(50).forEach { batch ->
            val body = executeWithRetry(batch)
            if (body != null) {
                body.lines().forEach { line ->
                    parseLine(line)?.let { result[it.code] = it }
                }
            }
        }
        return result
    }

    /**
     * 带指数退避的重试机制
     */
    private fun executeWithRetry(batch: List<String>): String? {
        val url = "https://qt.gtimg.cn/q=${batch.joinToString(",")}"

        for (attempt in 0..MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    val delay = RETRY_DELAY_MS * (1L shl (attempt - 1))
                    Thread.sleep(delay)
                }

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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

    private fun parseLine(line: String): StockRealtime? {
        if (!line.contains("=\"")) return null
        val code = line.substringAfter("v_").substringBefore("=").trim()
        val fields = line.substringAfter("\"").substringBeforeLast("\"").split("~")
        if (fields.size < 38) return null

        val price = doubleAt(fields, 3)
        if (price == null || price <= 0) return null

        val yestClose = doubleAt(fields, 4) ?: 0.0
        val open = doubleAt(fields, 5) ?: 0.0
        val changeAmount = doubleAt(fields, 31) ?: (price - yestClose)
        val changePercent = doubleAt(fields, 32) ?: calcPercent(price, yestClose)
        val high = doubleAt(fields, 33) ?: 0.0
        val low = doubleAt(fields, 34) ?: 0.0
        val volume = ((doubleAt(fields, 36) ?: 0.0) * 100).toLong()
        val amount = (doubleAt(fields, 37) ?: 0.0) * 10000

        return StockRealtime(
            code = code,
            name = textAt(fields, 1),
            price = price,
            open = open,
            yestClose = yestClose,
            high = high,
            low = low,
            volume = volume,
            amount = amount,
            changePercent = changePercent,
            changeAmount = changeAmount,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun doubleAt(fields: List<String>, index: Int): Double? {
        val value = fields.getOrNull(index) ?: return null
        return value.toDoubleOrNull()
    }

    private fun textAt(fields: List<String>, index: Int): String {
        return fields.getOrNull(index) ?: ""
    }

    private fun calcPercent(price: Double, previous: Double): Double {
        return if (previous == 0.0) 0.0 else (price - previous) / previous * 100
    }

    override fun isAvailable(): Boolean = runCatching {
        val request = Request.Builder()
            .url("https://qt.gtimg.cn/q=sh000001")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        healthClient.newCall(request).execute().isSuccessful
    }.getOrDefault(false)

    override fun priority(): Int = 2
}