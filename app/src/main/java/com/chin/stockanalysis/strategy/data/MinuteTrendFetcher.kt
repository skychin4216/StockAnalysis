package com.chin.stockanalysis.strategy.data

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ## 当日分时（分钟趋势）获取器
 *
 * 双源容错：
 * 1. **腾讯** `web.ifzq.gtimg.cn/appstock/app/minute/query?code=sh600519`（优先，个股/指数均支持）
 * 2. **东方财富** `push2his/.../stock/trends2/get?secid=1.600519`（备选）
 *
 * 返回逐分钟的价格/均价/成交量（手），供详情页绘制分时图。
 */
class MinuteTrendFetcher {

    companion object {
        private const val TAG = "MinuteTrendFetcher"
    }

    /** 单点分时数据 */
    data class MinutePoint(
        val time: String,        // "09:30"
        val price: Double,
        val avg: Double,         // 均价（腾讯由累计额/累计量折算，东财直接返回）
        val volumeMin: Long,     // 该分钟成交量（手，增量）
        val volumeCum: Long      // 当日累计成交量（手）
    )

    data class MinuteResult(
        val code: String,
        val date: String,                // "20260904" / "2026-09-04"
        val prevClose: Double,           // 昨收（可能为 0 表示无法解析）
        val points: List<MinutePoint>,
        val source: String               // "腾讯" / "东财"
    )

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
     * 获取当日分时：先腾讯，失败/无数据再试东财。
     *
     * @param code 带交易所前缀代码，如 "sh600519" / "sz000001" / "sh000001"
     */
    suspend fun fetchMinuteTrend(code: String): MinuteResult? = withContext(Dispatchers.IO) {
        if (code.isBlank()) return@withContext null

        val errors = mutableListOf<String>()
        try {
            val t = fetchFromTencent(code)
            if (t != null && t.points.isNotEmpty()) {
                Log.d(TAG, "腾讯分时成功: $code ${t.points.size}点")
                return@withContext t
            }
            errors.add("腾讯无数据")
        } catch (e: Exception) {
            errors.add("腾讯异常:${e.message}")
            Log.w(TAG, "腾讯分时失败 $code: ${e.message}")
        }

        // 避免连续请求过快
        delay(200)

        try {
            val em = fetchFromEastMoney(code)
            if (em != null && em.points.isNotEmpty()) {
                Log.d(TAG, "东财分时成功: $code ${em.points.size}点")
                return@withContext em
            }
            errors.add("东财无数据")
        } catch (e: Exception) {
            errors.add("东财异常:${e.message}")
            Log.w(TAG, "东财分时失败 $code: ${e.message}")
        }

        Log.w(TAG, "分时双源均失败 $code: ${errors.joinToString(";")}")
        null
    }

    private fun newRequest(url: String, referer: String): Request =
        Request.Builder().url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .addHeader("Referer", referer)
            .build()

    // ───────────────────────── 腾讯 minute/query ─────────────────────────
    private fun fetchFromTencent(code: String): MinuteResult? {
        val url = "${DataConfig.tencentMinute}?code=$code"
        val resp = client.newCall(newRequest(url, "https://gu.qq.com/")).execute()
        val body = resp.body?.string() ?: return null
        val root = JSONObject(body)
        if (root.optString("code") != "0") return null

        val host = root.optJSONObject("data")?.optJSONObject(code) ?: return null
        val dd = host.optJSONObject("data") ?: return null
        val rows = dd.optJSONArray("data") ?: return null
        if (rows.length() < 2) return null

        val date = dd.optString("date", "")
        // qt.<code> 数组：[.., 名称, 代码, 现价, 昨收, 今开, ...]（索引3/4/5）
        var prevClose = 0.0
        val qt = host.optJSONObject("qt")
        val qtArr = qt?.optJSONArray(code)
        if (qtArr != null && qtArr.length() > 4) {
            prevClose = qtArr.optDouble(4, 0.0)
        }

        val points = mutableListOf<MinutePoint>()
        var cum = 0L
        for (i in 0 until rows.length()) {
            val line = rows.getString(i)
            val p = line.split(" ")
            if (p.size < 4) continue
            val timeRaw = p[0].trim()                          // "0930"
            val price = p[1].toDoubleOrNull() ?: continue
            val cumVol = p[2].toLongOrNull() ?: 0L             // 累计成交量(手)
            val cumAmt = p[3].toDoubleOrNull() ?: 0.0          // 累计成交额(元)
            val volMin = if (i == 0) cumVol else (cumVol - cum).coerceAtLeast(0L)
            val avg = if (cumVol > 0) cumAmt / (cumVol * 100.0) else price
            points.add(
                MinutePoint(
                    time = formatTime(timeRaw),
                    price = price,
                    avg = avg,
                    volumeMin = volMin,
                    volumeCum = cumVol
                )
            )
            cum = cumVol
        }
        if (points.size < 2) return null
        if (prevClose <= 0.0) {
            prevClose = points.firstOrNull()?.let { p0 ->
                // 用均价/现价估测昨收不可靠，置 0 让 UI 退化为相对首点
                p0.avg
            } ?: 0.0
        }
        return MinuteResult(code, date, prevClose, points, "腾讯")
    }

    // ───────────────────────── 东财 trends2 ─────────────────────────
    private fun fetchFromEastMoney(code: String): MinuteResult? {
        val pure = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val market = if (code.startsWith("sh")) 1 else 0
        val url = "${DataConfig.eastmoneyPush2his}/stock/trends2/get?" +
                "secid=$market.$pure" +
                "&fields1=f1,f2,f3,f7,f8" +
                "&fields2=f51,f53,f56,f58" +
                "&ndays=1&iscr=0"
        val resp = client.newCall(newRequest(url, DataConfig.eastmoneyQuote)).execute()
        val body = resp.body?.string() ?: return null
        val data = JSONObject(body).optJSONObject("data") ?: return null
        val trends = data.optJSONArray("trends") ?: return null
        if (trends.length() < 2) return null

        val prevClose = data.optDouble("prePrice", 0.0)
        val date = data.optString("date", "")

        // 每行: "2026-09-04 09:30,price,volume(手),avgPrice"
        val points = mutableListOf<MinutePoint>()
        var cum = 0L
        for (i in 0 until trends.length()) {
            val parts = trends.getString(i).split(",")
            if (parts.size < 4) continue
            val price = parts[1].toDoubleOrNull() ?: continue
            val volMin = parts[2].toLongOrNull() ?: 0L
            val avg = parts[3].toDoubleOrNull() ?: price
            cum += volMin
            points.add(
                MinutePoint(
                    time = parts[0].substringAfter(' ', parts[0]).trim(),
                    price = price,
                    avg = avg,
                    volumeMin = volMin,
                    volumeCum = cum
                )
            )
        }
        if (points.size < 2) return null
        return MinuteResult(code, date, prevClose, points, "东财")
    }

    /** "0930" → "09:30" */
    private fun formatTime(raw: String): String =
        if (raw.length == 4) "${raw.substring(0, 2)}:${raw.substring(2)}" else raw
}
