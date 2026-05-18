package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.StockDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 新浪财经数据源 - 主数据源（优先级1）
 * 特点：免费、无需 token、支持批量查询
 *
 * ⚠️ 关键：新浪 API 需要设置 Referer 头，否则返回空数据！
 *   正确 URL 格式: https://hq.sinajs.cn/list=sh600519,sz000858
 *   错误 URL 格式: https://hq.sinajs.cn?list=sh600519   (无数据返回)
 */
class SinaStockSource : StockDataSource {

    private val client = OkHttpClient()
    private val tag = "SinaStockSource"

    companion object {
        private const val BASE_URL = "https://hq.sinajs.cn"
    }

    override fun fetchRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) {
            Log.w(tag, "fetchRealtime: codes is empty, returning empty map")
            return emptyMap()
        }

        Log.d(tag, "fetchRealtime: requesting ${codes.size} codes: ${codes.take(3)}...")
        val startTime = System.currentTimeMillis()

        try {
            val result = mutableMapOf<String, StockRealtime>()

            // 新浪接口支持批量查询，但为了稳定性，我们按20个分批
            val batches = codes.chunked(20)
            for (batchIndex in batches.indices) {
                val batch = batches[batchIndex]
                val url = "${BASE_URL}/list=${batch.joinToString(",")}"
                Log.d(tag, "  batch[$batchIndex]: URL=$url")

                val request = Request.Builder()
                    .url(url)
                    // 🔴 关键修复：必须加 Referer 头，否则新浪返回空
                    .header("Referer", "https://finance.sina.com.cn")
                    .build()

                try {
                    val response = client.newCall(request).execute()
                    val responseCode = response.code
                    val body = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        Log.w(tag, "  batch[$batchIndex] HTTP $responseCode: ${response.message}")
                        continue
                    }

                    Log.d(tag, "  batch[$batchIndex] HTTP $responseCode, body length=${body.length}")

                    // 解析新浪 JS 格式的响应
                    val parsed = parseResponse(body)
                    Log.d(tag, "  batch[$batchIndex] parsed ${parsed.size} stocks")
                    for ((code, stock) in parsed) {
                        result[code] = stock
                    }
                } catch (e: Exception) {
                    Log.e(tag, "  batch[$batchIndex] exception: ${e.message}", e)
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(tag, "fetchRealtime: returning ${result.size}/${codes.size} stocks in ${elapsed}ms")
            if (result.isEmpty()) {
                Log.w(tag, "fetchRealtime: ALL DATA SOURCES RETURNED EMPTY! " +
                    "Check network, Referer header, and stock code format.")
            }
            return result
        } catch (e: Exception) {
            Log.e(tag, "fetchRealtime: top-level exception: ${e.message}", e)
            return emptyMap()
        }
    }

    private fun parseResponse(body: String): Map<String, StockRealtime> {
        val result = mutableMapOf<String, StockRealtime>()

        if (body.isBlank()) {
            Log.w(tag, "parseResponse: body is blank")
            return result
        }

        // 新浪返回格式: var hq_str_sh600519="贵州茅台,1688.00,1687.00,1690.00,1680.00,...";
        val lines = body.split("\n")
        Log.d(tag, "parseResponse: ${lines.size} lines")

        for (line in lines) {
            if (line.contains("hq_str_")) {
                try {
                    val code = line.substringAfter("hq_str_").substringBefore("=")
                    val data = line.substringAfter("\"").substringBefore("\"").split(",")

                    Log.v(tag, "  raw line: code=$code, fields=${data.size}")

                    if (data.size >= 32) {
                        // 股票完整格式（32+字段）
                        val name = data[0]
                        val openStr = data[1]
                        val yestStr = data[2]
                        val priceStr = data[3]
                        val highStr = data[4]
                        val lowStr = data[5]
                        val volumeStr = data[8]     // 成交量（手）
                        val amountStr = data[9]      // 成交额

                        val price = priceStr.toDoubleOrNull()
                        val yestClose = yestStr.toDoubleOrNull()

                        if (price == null || price <= 0) {
                            Log.w(tag, "  skip $code: invalid price=$priceStr, name=$name")
                            continue
                        }

                        val stock = StockRealtime(
                            code = code,
                            name = name,
                            price = price,
                            open = openStr.toDoubleOrNull() ?: 0.0,
                            yestClose = yestClose ?: 0.0,
                            high = highStr.toDoubleOrNull() ?: 0.0,
                            low = lowStr.toDoubleOrNull() ?: 0.0,
                            volume = volumeStr.toLongOrNull() ?: 0L,
                            amount = amountStr.toDoubleOrNull() ?: 0.0,
                            changePercent = calculateChangePercent(price, yestClose ?: 0.0),
                            changeAmount = price - (yestClose ?: 0.0),
                            timestamp = System.currentTimeMillis()
                        )
                        result[code] = stock
                        Log.v(tag, "  ✓ $name($code): price=$price, volume=$volumeStr")
                    } else if (data.size >= 5) {
                        // 简略格式
                        Log.w(tag, "  partial data for $code: only ${data.size} fields (need 32)")
                        val name = data[0]
                        val price = data[3].toDoubleOrNull()
                        val yestClose = data[2].toDoubleOrNull()

                        if (price == null || price <= 0) continue

                        val stock = StockRealtime(
                            code = code,
                            name = name,
                            price = price,
                            open = data[1].toDoubleOrNull() ?: 0.0,
                            yestClose = yestClose ?: 0.0,
                            high = data[4].toDoubleOrNull() ?: 0.0,
                            low = if (data.size > 5) data[5].toDoubleOrNull() ?: 0.0 else 0.0,
                            volume = if (data.size > 8) data[8].toLongOrNull() ?: 0L else 0L,
                            amount = if (data.size > 9) data[9].toDoubleOrNull() ?: 0.0 else 0.0,
                            changePercent = calculateChangePercent(price, yestClose ?: 0.0),
                            changeAmount = price - (yestClose ?: 0.0),
                            timestamp = System.currentTimeMillis()
                        )
                        result[code] = stock
                    } else {
                        Log.w(tag, "  skip $code: only ${data.size} fields (need >=5)")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "  parse error: ${e.message}", e)
                }
            }
        }

        if (result.isEmpty()) {
            Log.w(tag, "parseResponse: parsed 0 stocks from ${body.length} chars body")
            Log.d(tag, "  first 200 chars of body: ${body.take(200)}")
        }
        return result
    }

    private fun calculateChangePercent(current: Double, previous: Double): Double {
        if (previous == 0.0) return 0.0
        return ((current - previous) / previous) * 100
    }

    override fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("${BASE_URL}/list=sh000001")
                .header("Referer", "https://finance.sina.com.cn")
                .build()
            val response = client.newCall(request).execute()
            val available = response.isSuccessful
            Log.d(tag, "isAvailable: $available (HTTP ${response.code})")
            available
        } catch (e: Exception) {
            Log.e(tag, "isAvailable: false, exception: ${e.message}", e)
            false
        }
    }

    override fun priority(): Int = 1
}

