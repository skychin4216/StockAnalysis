package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.strategy.backtest.IntradayKlineEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * ## 盤中分鐘 K 線獲取器
 *
 * 從 EastMoney API 獲取盤中 5 分鐘 K 線數據。
 * 使用 `push2his/stock/kline/get` 接口，`klt=5` 表示 5 分鐘線。
 *
 * 返回 [IntradayKlineEntity] 列表，可直接存入 DB 或用於盤中分析。
 */
class IntradayKlineFetcher(private val context: Context) {

    companion object {
        private const val TAG = "IntradayKlineFetcher"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(10, 120, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 獲取指定股票當日的 5 分鐘 K 線。
     *
     * @param code 股票代碼（如 "sh600519"）
     * @param intervalMin K 線週期（1=1min, 5=5min, 15=15min, 30=30min, 60=60min）
     * @return 盤中 K 線列表，按時間升序
     */
    suspend fun fetchIntradayKline(
        code: String,
        intervalMin: Int = 5
    ): List<IntradayKlineEntity> = withContext(Dispatchers.IO) {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        fetchIntradayKlineInternal(code, today, intervalMin)
    }

    /**
     * 批量獲取多只股票的盤中 K 線。
     *
     * @param codes 股票代碼列表
     * @param intervalMin K 線週期
     * @return code → klines 映射
     */
    suspend fun fetchBatchIntraday(
        codes: List<String>,
        intervalMin: Int = 5
    ): Map<String, List<IntradayKlineEntity>> = withContext(Dispatchers.IO) {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val result = mutableMapOf<String, List<IntradayKlineEntity>>()
        for (code in codes) {
            try {
                val klines = fetchIntradayKlineInternal(code, today, intervalMin)
                if (klines.isNotEmpty()) {
                    result[code] = klines
                }
                // 避免請求過快被限流
                kotlinx.coroutines.delay(200)
            } catch (e: Exception) {
                Log.w(TAG, "獲取 $code 盤中K線失敗: ${e.message}")
            }
        }
        result
    }

    private suspend fun fetchIntradayKlineInternal(
        code: String,
        date: String,
        intervalMin: Int
    ): List<IntradayKlineEntity> {
        val market = if (code.startsWith("sh")) 1 else if (code.startsWith("bj")) 1 else 0
        val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val dateCompact = date.replace("-", "")

        // klt: 1=1min, 5=5min, 15=15min, 30=30min, 60=60min, 101=daily
        val url = "${DataConfig.eastmoneyPush2his}/stock/kline/get?" +
                "secid=$market.$pureCode&klt=$intervalMin&fqt=1" +
                "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                "&beg=$dateCompact&end=$dateCompact&lmt=240"

        val maxRetries = 2
        for (attempt in 0..maxRetries) {
            try {
                if (attempt > 0) {
                    kotlinx.coroutines.delay(500L * (1 shl attempt))
                }
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", DataConfig.eastmoneyQuote)
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    Log.d(TAG, "HTTP ${resp.code} for $code attempt=$attempt")
                    continue
                }
                val body = resp.body?.string() ?: continue
                val data = JSONObject(body).optJSONObject("data") ?: continue
                val klines = data.optJSONArray("klines") ?: continue
                val rawName = data.optString("name", "").trim()
                val stockName = cleanStockName(rawName)

                val results = mutableListOf<IntradayKlineEntity>()
                for (i in 0 until klines.length()) {
                    val line = klines.getString(i)
                    val parts = line.split(",")
                    // 盤中 K 線格式: "2025-01-15 10:30,open,close,high,low,volume,amount,..."
                    if (parts.size < 7) continue
                    val datetime = parts[0]  // "2025-01-15 10:30"
                    val barDate = datetime.split(" ").firstOrNull() ?: date

                    results.add(IntradayKlineEntity(
                        code = code,
                        name = stockName,
                        datetime = datetime,
                        date = barDate,
                        open = parts[1].toDoubleOrNull() ?: 0.0,
                        close = parts[2].toDoubleOrNull() ?: 0.0,
                        high = parts[3].toDoubleOrNull() ?: 0.0,
                        low = parts[4].toDoubleOrNull() ?: 0.0,
                        volume = parts[5].toLongOrNull() ?: 0L,
                        amount = parts[6].toDoubleOrNull() ?: 0.0,
                        intervalMin = intervalMin
                    ))
                }
                Log.d(TAG, "獲取 $code 盤中K線: ${results.size} 根 ${intervalMin}分鐘線")
                return results
            } catch (e: Exception) {
                Log.d(TAG, "EastMoney intraday #$attempt for $code: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun cleanStockName(rawName: String): String {
        val name = rawName.trim()
        if (name.isBlank() || name.length >= 20) return ""
        return when {
            name.startsWith("XD") || name.startsWith("XR") || name.startsWith("DR") ->
                name.removePrefix("XD").removePrefix("XR").removePrefix("DR").trim()
            else -> name
        }
    }
}
