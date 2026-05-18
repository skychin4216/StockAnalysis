package com.chin.stockanalysis.stock.data.sources

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.StockDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 新浪财经数据源 - 主数据源（优先级1）
 * 特点：免费、无需 token、支持批量查询
 */
class SinaStockSource : StockDataSource {

    private val client = OkHttpClient()

    companion object {
        private const val BASE_URL = "https://hq.sinajs.cn"
        private const val PRICE_URL = "https://quotes.sina.com.cn/hs?list="
    }

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()

        try {
            val result = mutableMapOf<String, StockRealtime>()

            // 新浪接口支持批量查询，但为了稳定性，我们按50个分批
            codes.chunked(50).forEach { batch ->
                val url = "${BASE_URL}?list=${batch.joinToString(",")}"
                val request = Request.Builder().url(url).build()

                try {
                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: return@forEach

                    // 解析新浪 JS 格式的响应
                    parseResponse(body).forEach { (code, stock) ->
                        result[code] = stock
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return result
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyMap()
        }
    }

    private fun parseResponse(body: String): Map<String, StockRealtime> {
        val result = mutableMapOf<String, StockRealtime>()

        // 新浪返回格式: var hq_str_sh600519="贵州茅台,1688.00,1687.00,1690.00,1680.00,";
        val lines = body.split("\n")
        for (line in lines) {
            if (line.contains("hq_str_")) {
                try {
                    val code = line.substringAfter("hq_str_").substringBefore("=")
                    val data = line.substringAfter("\"").substringBefore("\"").split(",")

                    if (data.size >= 5) {
                        val stock = StockRealtime(
                            code = code,
                            name = data[0],
                            price = data[3].toDoubleOrNull() ?: 0.0,
                            open = data[1].toDoubleOrNull() ?: 0.0,
                            yestClose = data[2].toDoubleOrNull() ?: 0.0,
                            high = data[4].toDoubleOrNull() ?: 0.0,
                            low = if (data.size > 5) data[5].toDoubleOrNull() ?: 0.0 else 0.0,
                            volume = if (data.size > 8) data[8].toLongOrNull() ?: 0L else 0L,
                            amount = if (data.size > 9) data[9].toDoubleOrNull() ?: 0.0 else 0.0,
                            changePercent = calculateChangePercent(
                                data[3].toDoubleOrNull() ?: 0.0,
                                data[2].toDoubleOrNull() ?: 0.0
                            ),
                            changeAmount = (data[3].toDoubleOrNull() ?: 0.0) - (data[2].toDoubleOrNull() ?: 0.0),
                            timestamp = System.currentTimeMillis()
                        )
                        result[code] = stock
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return result
    }

    private fun calculateChangePercent(current: Double, previous: Double): Double {
        if (previous == 0.0) return 0.0
        return ((current - previous) / previous) * 100
    }

    override fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder().url("${BASE_URL}?list=sh000001").build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override fun priority(): Int = 1
}

