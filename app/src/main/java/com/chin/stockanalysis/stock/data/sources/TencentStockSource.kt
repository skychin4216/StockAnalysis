package com.chin.stockanalysis.stock.data.sources

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.StockDataSource
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 腾讯财经实时行情备用数据源。
 *
 * 接口示例：https://qt.gtimg.cn/q=sh600519,sz000858
 * 返回格式较长，以 ~ 分隔，常用字段：
 * 1 名称、3 当前价、4 昨收、5 今开、30 时间、31 涨跌、32 涨跌幅、33 最高、34 最低、36 成交量、37 成交额。
 */
class TencentStockSource : StockDataSource {

    private val client = OkHttpClient()

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, StockRealtime>()
        codes.chunked(50).forEach { batch ->
            val request = Request.Builder()
                .url("https://qt.gtimg.cn/q=${batch.joinToString(",")}")
                .build()
            val body = client.newCall(request).execute().body?.string().orEmpty()
            body.lines().forEach { line ->
                parseLine(line)?.let { result[it.code] = it }
            }
        }
        return result
    }

    private fun parseLine(line: String): StockRealtime? {
        if (!line.contains("=\"")) return null
        val code = line.substringAfter("v_").substringBefore("=").trim()
        val fields = line.substringAfter("\"").substringBeforeLast("\"").split("~")
        if (fields.size < 38) return null
        val price = doubleAt(fields, 3) ?: return null
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
        client.newCall(Request.Builder().url("https://qt.gtimg.cn/q=sh000001").build()).execute().isSuccessful
    }.getOrDefault(false)

    override fun priority(): Int = 2
}