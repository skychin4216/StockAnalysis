package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.data.StockDataSource
import okhttp3.Request
import java.nio.charset.Charset

/**
 * 新浪财经数据源 - 主数据源（优先级1）
 * 特点：免费、无需 token、支持批量查询
 *
 * ⚠️ 关键：
 * 1. 新浪 API 需要设置 Referer 头，否则返回空数据！
 * 2. 新浪返回的是 **GBK/GB2312 编码**，必须正确解码！
 * 3. 正确 URL 格式: https://hq.sinajs.cn/list=sh600519,sz000858
 *
 * ✅ 改进：
 * - 使用共享 OkHttp 连接池
 * - 支持 GBK 解码
 * - 指数退避重试（Retry with exponential backoff）
 * - 更清晰的异常日志
 */
class SinaStockSource : StockDataSource {

    private val client = HttpClientProvider.realtimeClient
    private val healthClient = HttpClientProvider.healthCheckClient
    private val tag = "SinaStockSource"

    companion object {
        private const val BASE_URL = "https://hq.sinajs.cn"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 500L
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

                // 带重试的请求
                val rawBody = executeWithRetry(url, batchIndex)
                if (rawBody == null) {
                    Log.w(tag, "  batch[$batchIndex] failed after $MAX_RETRIES retries")
                    continue
                }

                // 解析新浪 JS 格式的响应
                val parsed = parseResponse(rawBody, batch)
                Log.d(tag, "  batch[$batchIndex] parsed ${parsed.size} stocks")
                for ((code, stock) in parsed) {
                    result[code] = stock
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

    /**
     * 带指数退避的重试机制
     * - 最多重试 MAX_RETRIES 次
     * - 首次失败后等待 500ms，第二次等待 1000ms
     */
    private fun executeWithRetry(url: String, batchIndex: Int): String? {
        var lastError: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    val delay = RETRY_DELAY_MS * (1L shl (attempt - 1)) // 500, 1000
                    Log.d(tag, "  batch[$batchIndex] retry #$attempt after ${delay}ms...")
                    Thread.sleep(delay)
                }

                val request = Request.Builder()
                    .url(url)
                    // 🔴 关键修复：必须加 Referer 头，否则新浪返回空
                    .header("Referer", "https://finance.sina.com.cn")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                val responseCode = response.code

                if (!response.isSuccessful) {
                    Log.w(tag, "  batch[$batchIndex] attempt#$attempt HTTP $responseCode: ${response.message}")
                    lastError = Exception("HTTP $responseCode")
                    continue
                }

                // ⚠️ 重要：新浪返回的是 GBK/GB2312 编码！
                // 必须用 source() 读取原始字节流并用 GBK 解码
                val bodyBytes = response.body?.bytes()
                if (bodyBytes == null || bodyBytes.isEmpty()) {
                    Log.w(tag, "  batch[$batchIndex] attempt#$attempt: empty body")
                    lastError = Exception("Empty body")
                    continue
                }

                val body = try {
                    String(bodyBytes, Charset.forName("GBK"))
                } catch (e: Exception) {
                    // 如果 GBK 解码失败，回退到默认解码（可能乱码但能解析）
                    Log.w(tag, "  batch[$batchIndex] GBK decode failed, fallback to default: ${e.message}")
                    String(bodyBytes)
                }

                Log.d(tag, "  batch[$batchIndex] attempt#$attempt HTTP $responseCode, body length=${body.length}")
                return body

            } catch (e: Exception) {
                lastError = e
                Log.w(tag, "  batch[$batchIndex] attempt#$attempt exception: ${e.message}")
            }
        }

        Log.e(tag, "  batch[$batchIndex] all $MAX_RETRIES retries exhausted: ${lastError?.message}")
        return null
    }

    private fun parseResponse(body: String, batch: List<String>? = null): Map<String, StockRealtime> {
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

                        // 检查是否是空数据（新浪返回空字符串表示无数据）
                        if (priceStr.isBlank() || priceStr == "0.00") {
                            Log.v(tag, "  skip $code: empty/zero price")
                            continue
                        }

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
                        // 简略格式（如指数等）
                        Log.w(tag, "  partial data for $code: only ${data.size} fields (need 32)")

                        val name = data[0]
                        val priceStr = data[3]

                        if (priceStr.isBlank()) continue
                        val price = priceStr.toDoubleOrNull()
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

        // 如果解析结果为零，尝试用批量传入的 codes 做二次检查
        if (result.isEmpty() && batch != null && batch.isNotEmpty()) {
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
            val response = healthClient.newCall(request).execute()
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