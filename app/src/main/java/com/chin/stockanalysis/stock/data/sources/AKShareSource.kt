package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.data.StockDataSource
import okhttp3.Request
import org.json.JSONObject

/**
 * AKShare 数据源 - 免费补充数据源（优先级4）
 *
 * 接口示例：http://api.akshare.tech/stock_zh_a_spot
 * 返回全市场实时行情 JSON。
 *
 * ✅ 改进：
 * - 使用共享 Http 连接池
 * - 指数退避重试
 * - 支持批量代码过滤
 */
class AKShareSource : StockDataSource {

    private val client = HttpClientProvider.realtimeClient
    private val healthClient = HttpClientProvider.healthCheckClient
    private val tag = "AKShareSource"

    companion object {
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 500L
    }

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()

        return try {
            val allData = fetchAllSpotData()
            val result = allData.filterKeys { it in codes }
            Log.d(tag, "Got ${result.size}/${codes.size}")
            result
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 获取全市场行情数据，带重试
     */
    private fun fetchAllSpotData(): Map<String, StockRealtime> {
        val result = mutableMapOf<String, StockRealtime>()

        for (attempt in 0..MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    val delay = RETRY_DELAY_MS * (1L shl (attempt - 1))
                    Thread.sleep(delay)
                }

                val request = Request.Builder()
                    .url("http://api.akshare.tech/stock_zh_a_spot")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(tag, "attempt#$attempt HTTP ${response.code}")
                    continue
                }

                val body = response.body?.string() ?: continue
                val jsonResponse = JSONObject(body)
                val dataArray = jsonResponse.optJSONArray("data") ?: continue

                for (i in 0 until dataArray.length()) {
                    val item = dataArray.optJSONObject(i) ?: continue
                    parseStockItem(item)?.let { result[it.code] = it }
                }

                Log.d(tag, "Fetched ${result.size} total stocks (attempt#$attempt)")
                return result // 成功后返回

            } catch (e: Exception) {
                Log.w(tag, "attempt#$attempt exception: ${e.message}")
            }
        }

        return result
    }

    private fun parseStockItem(item: JSONObject): StockRealtime? {
        return try {
            val code = item.optString("code").ifBlank {
                val symbol = item.optString("symbol")
                when {
                    symbol.startsWith("6") || symbol.startsWith("9") -> "sh$symbol"
                    symbol.startsWith("4") || symbol.startsWith("8") -> "bj$symbol"
                    else -> "sz$symbol"
                }
            }
            if (code.isBlank()) return null

            val price = item.optDouble("price", 0.0)
            if (price <= 0) return null

            val yestClose = item.optDouble("last_close", price)

            StockRealtime(
                code = code,
                name = item.optString("name"),
                price = price,
                open = item.optDouble("open", 0.0),
                yestClose = yestClose,
                high = item.optDouble("high", 0.0),
                low = item.optDouble("low", 0.0),
                volume = item.optLong("volume", 0L),
                amount = item.optDouble("amount", 0.0),
                changePercent = if (yestClose == 0.0) 0.0 else ((price - yestClose) / yestClose) * 100,
                changeAmount = price - yestClose,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.v(tag, "Parse error: ${e.message}")
            null
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://api.akshare.tech/stock_zh_a_spot")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            healthClient.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override fun priority(): Int = 4
}