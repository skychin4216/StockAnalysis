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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ## 盘中分钟 K 线获取器
 *
 * 从 EastMoney API 获取盘中 5 分钟 K 线数据。
 * 使用 `push2his/stock/kline/get` 接口，`klt=5` 表示 5 分钟线。
 *
 * 返回 [IntradayKlineEntity] 列表，可直接存入 DB 或用于盘中分析。
 */
class IntradayKlineFetcher(private val context: Context) {

    companion object {
        private const val TAG = "IntradayKlineFetcher"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private const val CACHE_DIR = "kline_cache"
        private const val MEM_TTL_MS = 3 * 60 * 1000L   // 内存缓存 3 分钟（盘中连续点击不重复拉网）
        /** 增量拉取根数：覆盖周末/假期/盘中空档绰绰有余，远小于全量 1023 */
        private const val INCREMENTAL_BARS = 80
        /** 磁盘缓存达到此根数即可走增量，否则全量（各策略最多用 200 根） */
        private const val MIN_BARS_FOR_INCREMENTAL = 120
        private val memCache = ConcurrentHashMap<String, Pair<List<IntradayKlineEntity>, Long>>()
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
     * 获取指定股票当日的 5 分钟 K 线。
     *
     * @param code 股票代码（如 "sh600519"）
     * @param intervalMin K 线周期（1=1min, 5=5min, 15=15min, 30=30min, 60=60min）
     * @return 盘中 K 线列表，按时间升序
     */
    suspend fun fetchIntradayKline(
        code: String,
        intervalMin: Int = 5
    ): List<IntradayKlineEntity> = withContext(Dispatchers.IO) {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        fetchIntradayKlineInternal(code, today, intervalMin)
    }

    /**
     * 拉取当日日线 OHLC（新浪 scale=240，datalen=5），返回 (open, high, low, close) 或 null。
     * 收盘核对用——只需当日四个数，走 HTTP 一次搞定。
     */
    suspend fun fetchDailyOnce(code: String): DoubleArray? = withContext(Dispatchers.IO) {
        val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val prefix = when {
            code.startsWith("sh") || pureCode.startsWith("6") || pureCode.startsWith("5") || pureCode.startsWith("9") -> "sh"
            else -> "sz"
        }
        val url = "${DataConfig.sinaKline}?symbol=$prefix$pureCode&scale=240&ma=no&datalen=5"
        for (attempt in 0..1) {
            try {
                if (attempt > 0) kotlinx.coroutines.delay(400L * attempt)
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", "https://finance.sina.com.cn/")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) continue
                val body = resp.body?.string() ?: continue
                if (!body.trimStart().startsWith("[")) continue
                val arr = JSONArray(body)
                if (arr.length() == 0) continue
                val last = arr.getJSONObject(arr.length() - 1)
                val close = last.optDouble("close", 0.0)
                val open = last.optDouble("open", 0.0)
                val high = last.optDouble("high", 0.0)
                val low = last.optDouble("low", 0.0)
                val day = last.optString("day", "")
                // 必须是当日
                val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                if (day.startsWith(todayStr)) {
                    return@withContext doubleArrayOf(open, high, low, close)
                }
            } catch (e: Exception) {
                Log.d(TAG, "dailyOnce #$attempt $code: ${e.message?.take(40)}")
            }
        }
        null
    }

    /**
     * 历史小时 K（新浪 scale=60 优先，东财 push2his klt=60 兜底），用于「三层 SSA 做T」回测。
     * PC 端回测实测：东财 push2his 本机不可达，新浪 60 分钟接口稳定（datalen=1023 ≈ 1.7 年）；
     * 返回升序列表（含 date / datetime）。
     *
     * 2026-10-21 新增本地缓存 + 增量更新：
     * - 内存缓存 3 分钟（避免连续点击/回测反复拉网）；
     * - 磁盘缓存 `filesDir/kline_cache/hourly_<code>.json`，收盘后 / 周末 / 盘前直接复用；
     * - 缓存 ≥120 根时只请求最近 [INCREMENTAL_BARS] 根（新浪 datalen / 东财 beg=末根日期），
     *   与旧缓存按 datetime 合并；仅首次或缓存不足才全量拉 1023 根。
     */
    suspend fun fetchHourlyHistory(code: String, days: Int = 760): List<IntradayKlineEntity> =
        withContext(Dispatchers.IO) {
            val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
            // 1. 内存缓存（3 分钟 TTL，最快）
            memCache[pureCode]?.let { (bars, ts) ->
                if (System.currentTimeMillis() - ts < MEM_TTL_MS) {
                    Log.d(TAG, "hourly60 mem $code: ${bars.size} 根（缓存）")
                    return@withContext bars
                }
            }
            // 2. 磁盘缓存
            val disk = loadHourlyCache(pureCode)
            if (disk != null && isCacheFresh(disk, pureCode)) {
                Log.d(TAG, "hourly60 disk $code: ${disk.size} 根（缓存）")
                memCache[pureCode] = disk to System.currentTimeMillis()
                return@withContext disk
            }
            // 3. 网络拉取：缓存够深 → 只拉最近 80 根增量；首次/缓存不足 → 全量 1023 根
            val today = LocalDate.now()
            val canIncremental = disk != null && disk.size >= MIN_BARS_FOR_INCREMENTAL
            val fresh = if (canIncremental) {
                fetchHourlySina(code, INCREMENTAL_BARS)?.takeIf { it.isNotEmpty() }
                    ?: fetchHourlyEastmoney(code, disk!!.last().date, today.format(DATE_FMT))
                        .takeIf { it.isNotEmpty() }
                    ?: fetchHourlySina(code)   // 增量两路都失败 → 退回全量，避免返回过期数据
            } else {
                fetchHourlySina(code)?.takeIf { it.isNotEmpty() }
                    ?: fetchHourlyEastmoney(
                        code,
                        today.minusDays(days.toLong()).format(DATE_FMT),
                        today.format(DATE_FMT)
                    ).takeIf { it.isNotEmpty() }
            }
            val merged = if (disk != null && fresh != null) mergeBars(disk, fresh) else (fresh ?: disk ?: emptyList())
            if (merged.isNotEmpty()) {
                saveHourlyCache(pureCode, merged)
                memCache[pureCode] = merged to System.currentTimeMillis()
                Log.d(TAG, "hourly60 net $code: ${merged.size} 根（${if (canIncremental) "增量" else "全量"}，已存盘）")
            }
            merged
        }

    // ═══════════════════════ 1H K线本地缓存（内存+磁盘）═══════════════════════

    private fun cacheFile(code: String): File {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        return File(dir, "hourly_$code.json")
    }

    private fun saveHourlyCache(code: String, bars: List<IntradayKlineEntity>) {
        runCatching {
            cacheFile(code).writeText(toJsonArray(bars).toString())
        }.onFailure { Log.w(TAG, "缓存写入失败 $code: ${it.message}") }
    }

    private fun loadHourlyCache(code: String): List<IntradayKlineEntity>? {
        val f = cacheFile(code)
        if (!f.exists()) return null
        return runCatching {
            fromJsonArray(JSONArray(f.readText()))
        }.getOrNull().also {
            if (it == null) Log.w(TAG, "缓存读取失败 $code，将重新下载")
        }
    }

    /** 新旧合并：以 datetime 为键，新数据覆盖旧数据，返回升序列表 */
    private fun mergeBars(old: List<IntradayKlineEntity>, fresh: List<IntradayKlineEntity>): List<IntradayKlineEntity> {
        val map = old.associateBy { it.datetime }.toMutableMap()
        for (b in fresh) map[b.datetime] = b
        return map.values.sortedBy { it.datetime }
    }

    /**
     * 缓存是否可复用。
     * 关键：盘前/周末复用只能用于「已完整收盘」的缓存——
     * 若最后一根不是 15 点收盘K线（如盘中 11:30 抓的快照），必须刷新补齐，
     * 否则全天都会停在旧时刻（历史 bug：凌晨把 11:30 快照当盘前缓存复用）。
     */
    private fun isCacheFresh(bars: List<IntradayKlineEntity>, code: String): Boolean {
        if (bars.isEmpty()) return false
        val last = bars.last()
        val lastDate = try {
            LocalDate.parse(last.date, DATE_FMT)
        } catch (_: Exception) { return false }
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        if (lastDate.isAfter(today)) return false
        val closed = isDayCloseBar(last)
        if (lastDate == today) {
            // 今日缓存：收盘后且确含收盘K线才算新鲜
            return now.toLocalTime().isAfter(LocalTime.of(15, 5)) && closed
        }
        if (!closed) {
            // 残缺缓存：10 分钟内刚拉过就不再重复请求（防停牌股反复打网）
            val f = cacheFile(code)
            return f.exists() && System.currentTimeMillis() - f.lastModified() < 10 * 60_000L
        }
        val dow = today.dayOfWeek
        return dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY ||
            now.toLocalTime().isBefore(LocalTime.of(9, 35))
    }

    /** 是否为当日收盘K线（A股 15:00） */
    private fun isDayCloseBar(b: IntradayKlineEntity): Boolean {
        val dt = b.datetime
        val sp = dt.indexOf(' ')
        if (sp < 0 || dt.length < sp + 3) return false
        return dt.substring(sp + 1, sp + 3) == "15"
    }

    private fun toJsonArray(bars: List<IntradayKlineEntity>): JSONArray {
        val arr = JSONArray()
        for (b in bars) {
            arr.put(JSONObject().apply {
                put("code", b.code)
                put("name", b.name)
                put("datetime", b.datetime)
                put("date", b.date)
                put("open", b.open)
                put("close", b.close)
                put("high", b.high)
                put("low", b.low)
                put("volume", b.volume)
                put("amount", b.amount)
                put("intervalMin", b.intervalMin)
            })
        }
        return arr
    }

    private fun fromJsonArray(arr: JSONArray): List<IntradayKlineEntity> {
        val out = ArrayList<IntradayKlineEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(IntradayKlineEntity(
                code = o.optString("code"),
                name = o.optString("name"),
                datetime = o.optString("datetime"),
                date = o.optString("date"),
                open = o.optDouble("open", 0.0),
                close = o.optDouble("close", 0.0),
                high = o.optDouble("high", 0.0),
                low = o.optDouble("low", 0.0),
                volume = o.optLong("volume", 0L),
                amount = o.optDouble("amount", 0.0),
                intervalMin = o.optInt("intervalMin", 60)
            ))
        }
        return out
    }

    /**
     * 新浪 60 分钟 K：`CN_MarketData.getKLineData?symbol=sh600176&scale=60&ma=no&datalen=1023`。
     * 与 [HistoricalDataFetcher] 日 K（scale=240）同一接口，响应为纯 JSON 数组
     * `[{day:"2026-01-15 15:00:00",open,high,low,close,volume},...]`。
     * @return 成功返回列表；全失败返回 null（交由东财兜底）
     */
    private suspend fun fetchHourlySina(code: String, datalen: Int = 1023): List<IntradayKlineEntity>? {
        val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val prefix = when {
            code.startsWith("sh") -> "sh"
            code.startsWith("sz") -> "sz"
            code.startsWith("bj") -> "bj"
            pureCode.startsWith("6") || pureCode.startsWith("5") || pureCode.startsWith("9") -> "sh"
            else -> "sz"
        }
        val url = "${DataConfig.sinaKline}?symbol=$prefix$pureCode&scale=60&ma=no&datalen=$datalen"
        for (attempt in 0..1) {
            try {
                if (attempt > 0) kotlinx.coroutines.delay(400L * attempt)
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", "https://finance.sina.com.cn/")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) continue
                val body = resp.body?.string() ?: continue
                if (!body.trimStart().startsWith("[")) continue
                val arr = JSONArray(body)
                val out = ArrayList<IntradayKlineEntity>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val day = o.optString("day", "")
                    if (day.length < 16) continue
                    val datetime = day.substring(0, 16)          // "2026-01-15 15:00"
                    val barDate = datetime.substring(0, 10).replace("-", "")
                    out.add(IntradayKlineEntity(
                        code = code, name = "", datetime = datetime, date = barDate,
                        open = o.optDouble("open", 0.0),
                        close = o.optDouble("close", 0.0),
                        high = o.optDouble("high", 0.0),
                        low = o.optDouble("low", 0.0),
                        volume = o.optLong("volume", 0L),
                        amount = o.optDouble("amount", 0.0),
                        intervalMin = 60
                    ))
                }
                Log.d(TAG, "hourly60 sina $code: ${out.size} 根")
                return out
            } catch (e: Exception) {
                Log.d(TAG, "hourly60 sina #$attempt $code: ${e.message?.take(60)}")
            }
        }
        return null
    }

    /** 东财 push2his klt=60 兜底（本机常被重置，仅作备用）。 */
    private suspend fun fetchHourlyEastmoney(code: String, beg: String, end: String): List<IntradayKlineEntity> {
        val market = if (code.startsWith("sh")) 1 else if (code.startsWith("bj")) 1 else 0
        val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val url = "${DataConfig.eastmoneyPush2his}/stock/kline/get?" +
                "secid=$market.$pureCode&klt=60&fqt=1" +
                "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
                "&beg=$beg&end=$end&lmt=2000"
        for (attempt in 0..2) {
            try {
                if (attempt > 0) kotlinx.coroutines.delay(500L * (1 shl attempt))
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", DataConfig.eastmoneyQuote)
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) continue
                val body = resp.body?.string() ?: continue
                val data = JSONObject(body).optJSONObject("data") ?: continue
                val klines = data.optJSONArray("klines") ?: continue
                val stockName = cleanStockName(data.optString("name", ""))
                val out = ArrayList<IntradayKlineEntity>(klines.length())
                for (i in 0 until klines.length()) {
                    val p = klines.getString(i).split(",")
                    if (p.size < 7) continue
                    val datetime = p[0]
                    val barDate = datetime.split(" ").firstOrNull()?.replace("-", "") ?: continue
                    out.add(IntradayKlineEntity(
                        code = code, name = stockName, datetime = datetime, date = barDate,
                        open = p[1].toDoubleOrNull() ?: 0.0,
                        close = p[2].toDoubleOrNull() ?: 0.0,
                        high = p[3].toDoubleOrNull() ?: 0.0,
                        low = p[4].toDoubleOrNull() ?: 0.0,
                        volume = p[5].toLongOrNull() ?: 0L,
                        amount = p[6].toDoubleOrNull() ?: 0.0,
                        intervalMin = 60
                    ))
                }
                return out
            } catch (e: Exception) {
                Log.d(TAG, "hourly60 #$attempt $code: ${e.message?.take(60)}")
            }
        }
        return emptyList()
    }

    /**
     * 批量获取多只股票的盘中 K 线。
     *
     * @param codes 股票代码列表
     * @param intervalMin K 线周期
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
                // 避免请求过快被限流
                kotlinx.coroutines.delay(200)
            } catch (e: Exception) {
                Log.w(TAG, "获取 $code 盘中K线失败: ${e.message}")
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
                    // 盘中 K 线格式: "2025-01-15 10:30,open,close,high,low,volume,amount,..."
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
                Log.d(TAG, "获取 $code 盘中K线: ${results.size} 根 ${intervalMin}分钟线")
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
