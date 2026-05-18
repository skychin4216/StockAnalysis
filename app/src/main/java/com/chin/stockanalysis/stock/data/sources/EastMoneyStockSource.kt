package com.chin.stockanalysis.stock.data.sources

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.StockDataSource
import okhttp3.OkHttpClient
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
 */
class EastMoneyStockSource : StockDataSource {

    private val client = OkHttpClient()

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, StockRealtime>()
        codes.chunked(50).forEach { batch ->
            val secids = batch.joinToString(",") { toSecId(it) }
            val url = "https://push2.eastmoney.com/api/qt/ulist.np/get" +
                "?fltt=2&invt=2&secids=$secids&fields=f12,f14,f2,f3,f4,f5,f6,f15,f16,f17,f18"
            val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string().orEmpty()
            parseBody(body).forEach { stock -> result[stock.code] = stock }
        }
        return result
    }

    private fun parseBody(body: String): List<StockRealtime> {
        return runCatching {
            val diff = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONArray("diff")
                ?: return emptyList()

            buildList {
                for (i in 0 until diff.length()) {
                    val item = diff.optJSONObject(i) ?: continue
                    val rawCode = item.optString("f12")
                    val code = normalizeBack(rawCode)
                    val price = item.optDoubleSafe("f2")
                    val yestClose = item.optDoubleSafe("f18")
                    add(
                        StockRealtime(
                            code = code,
                            name = item.optString("f14"),
                            price = price,
                            open = item.optDoubleSafe("f17"),
                            yestClose = yestClose,
                            high = item.optDoubleSafe("f15"),
                            low = item.optDoubleSafe("f16"),
                            volume = item.optDoubleSafe("f5").toLong(),
                            amount = item.optDoubleSafe("f6"),
                            changePercent = item.optDoubleSafe("f3"),
                            changeAmount = item.optDoubleSafe("f4").ifNaN(price - yestClose),
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

    private fun Double.ifNaN(fallback: Double): Double = if (isNaN()) fallback else this

    override fun isAvailable(): Boolean = runCatching {
        val request = Request.Builder()
            .url("https://push2.eastmoney.com/api/qt/ulist.np/get?secids=1.000001&fields=f12,f14,f2")
            .build()
        client.newCall(request).execute().isSuccessful
    }.getOrDefault(false)

    override fun priority(): Int = 3
}