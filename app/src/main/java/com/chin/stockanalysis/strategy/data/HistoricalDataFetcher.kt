package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.database.StockBasicEntity
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * History data fetcher v2.
 * Improvements:
 * - Always re-fetch on manual import (no stale skip)
 * - Core stock pool backed by JSON assets + SharedPreferences (live)
 * - Detailed logging for diagnostics
 */
class HistoricalDataFetcher(private val context: Context) {

    companion object {
        private const val TAG = "HDFetcher"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val STORE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val PREFS_KEY_CORE_POOL = "core_stock_pool_json"
        private const val ASSET_FILE = "core_stock_pool.json"

        // Pool management: JSON assets (factory) + SharedPreferences (live)
        fun getTopStocks(context: Context): List<String> =
            (LeaderStockPool.ALL_LEADER_CODES + getPool(context, "core_stock_pool_json", "core_stock_pool.json")).toList()

        fun getCoreStockPool(context: Context) = getPool(context, PREFS_KEY_CORE_POOL, ASSET_FILE)
        fun getHeaderStockPool(context: Context) = getPool(context, "header_stock_pool_json", "header_stock_pool.json")
        fun getUserSearchPool(context: Context) = getPool(context, "user_search_pool_json", "user_search_pool.json")
        fun getSkillPickPool(context: Context) = getPool(context, "skill_pick_pool_json", "skill_pick_pool.json")

        fun saveCoreStockPool(context: Context, pool: Set<String>) = savePool(context, PREFS_KEY_CORE_POOL, pool)
        fun saveHeaderStockPool(context: Context, pool: Set<String>) = savePool(context, "header_stock_pool_json", pool)
        fun saveUserSearchPool(context: Context, pool: Set<String>) = savePool(context, "user_search_pool_json", pool)
        fun saveSkillPickPool(context: Context, pool: Set<String>) = savePool(context, "skill_pick_pool_json", pool)

        private fun getPool(context: Context, prefsKey: String, assetFile: String): Set<String> {
            val prefs = context.getSharedPreferences("stock_pool_mgr", Context.MODE_PRIVATE)
            val live = prefs.getStringSet(prefsKey, null)
            if (!live.isNullOrEmpty()) {
                Log.d(TAG, "[POOL] loaded $prefsKey from prefs: ${live.size} stocks")
                return live
            }
            val fromAsset = loadPoolFromAssets(context, assetFile)
            if (fromAsset.isNotEmpty()) {
                Log.i(TAG, "[POOL] loaded $assetFile from assets: ${fromAsset.size} stocks, saving to prefs")
                prefs.edit().putStringSet(prefsKey, fromAsset).apply()
            } else {
                Log.w(TAG, "[POOL] $assetFile is empty or not found in assets")
            }
            return fromAsset
        }

        private fun savePool(context: Context, prefsKey: String, pool: Set<String>) {
            context.getSharedPreferences("stock_pool_mgr", Context.MODE_PRIVATE)
                .edit().putStringSet(prefsKey, pool).apply()
            Log.i(TAG, "[POOL] saved $prefsKey: ${pool.size} stocks")
        }

        private fun loadPoolFromAssets(context: Context, assetFile: String): Set<String> {
            return try {
                val input = context.assets.open(assetFile)
                val reader = BufferedReader(InputStreamReader(input, "UTF-8"))
                val json = reader.use { it.readText() }
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("stocks") ?: JSONArray()
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    set.add(arr.getString(i))
                }
                Log.d(TAG, "[POOL] assets/$assetFile parsed: ${set.size} stocks")
                set
            } catch (e: Exception) {
                Log.w(TAG, "[POOL] failed to load assets/$assetFile: ${e.message}")
                emptySet()
            }
        }
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
    private val db = StockDatabase.getInstance(context)

    data class FetchProgress(
        val totalStocks: Int,
        val completedStocks: Int,
        val totalRecords: Int,
        val currentStock: String = ""
    )

    suspend fun fetchAllHistoricalData(
        days: Int = 60,
        force: Boolean = false,
        startDateOverride: LocalDate? = null,
        onProgress: ((FetchProgress) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val endDate = LocalDate.now()
        var startDate = startDateOverride
            ?: (if (force && days < 500) LocalDate.of(2024, 1, 1) else endDate.minusDays((days * 1.5).toLong()))

        Log.i(TAG, "========== FETCH START ==========")
        Log.i(TAG, "  Date range: ${startDate.format(STORE_FMT)} ~ ${endDate.format(STORE_FMT)}")
        Log.i(TAG, "  force=$force  days=$days")

        val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay()
        val todayStr = today.format(STORE_FMT)
        val isNonTrading = !com.chin.stockanalysis.ui.TradingDayPickerView.isTradingDay(today)
        Log.i(TAG, "  today=$todayStr  nonTrading=$isNonTrading")

        val prefs = context.getSharedPreferences("data_import", Context.MODE_PRIVATE)
        val leaderFetched = prefs.getBoolean("leader_stocks_fetched", false)
        Log.i(TAG, "  leaderFetched=$leaderFetched")

        if (!leaderFetched) {
            Log.i(TAG, "  First fetch: setting startDate to 2024-01-01")
            startDate = LocalDate.of(2024, 1, 1)
        } else if (isNonTrading && !force) {
            Log.i(TAG, "  Non-trading day + not forced, skipping import")
            return@withContext 0
        }

        val stocks = getTopStocks(context)
        Log.i(TAG, "  Stocks to fetch: ${stocks.size} (LeaderPool + CorePool)")
        if (stocks.size <= 10) {
            Log.w(TAG, "  WARNING: Only ${stocks.size} stocks! Core pool may be empty.")
        }

        // Step 1: Realtime API for today
        val step1Start = System.currentTimeMillis()
        Log.i(TAG, "--- Step 1: Realtime API ---")
        var totalRecords = 0
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        var realtimeCount = 0
        try {
            val realtimeUrl = "https://push2.eastmoney.com/api/qt/clist/get?" +
                    "pn=1&pz=200&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23" +
                    "&fields=f2,f3,f4,f5,f6,f8,f12,f14,f15,f16,f17,f18"
            val req = Request.Builder().url(realtimeUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://quote.eastmoney.com/")
                .build()
            Log.d(TAG, "  Realtime API request...")
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val data = json.optJSONObject("data")
                val diffList = data?.optJSONArray("diff")
                if (diffList != null && diffList.length() > 0) {
                    val entities = mutableListOf<DailySnapshotEntity>()
                    val names = mutableListOf<StockBasicEntity>()
                    for (i in 0 until diffList.length()) {
                        val item = diffList.getJSONObject(i)
                        val code = item.optString("f12", "")
                        val prefix = when {
                            code.startsWith("6") || code.startsWith("9") -> "sh"
                            code.startsWith("4") || code.startsWith("8") -> "bj"
                            else -> "sz"
                        }
                        val fullCode = "$prefix$code"
                        if (fullCode !in stocks) continue
                        val name = item.optString("f14", "").let { raw ->
                            if (raw.startsWith("XD") || raw.startsWith("XR") || raw.startsWith("DR"))
                                raw.removePrefix("XD").removePrefix("XR").removePrefix("DR").trim()
                            else raw
                        }
                        entities.add(DailySnapshotEntity(
                            code = fullCode, name = name, date = todayStr,
                            open = item.optDouble("f17", 0.0),
                            close = item.optDouble("f2", 0.0),
                            high = item.optDouble("f15", 0.0),
                            low = item.optDouble("f16", 0.0),
                            volume = item.optLong("f5", 0L) * 100,
                            amount = item.optDouble("f6", 0.0) * 10000,
                            changePct = item.optDouble("f3", 0.0),
                            turnoverRate = item.optDouble("f8", 0.0),
                            mainNetInflow = 0.0
                        ))
                        if (name.isNotBlank()) {
                            names.add(StockBasicEntity(code = fullCode, name = name, business = ""))
                        }
                    }
                    if (entities.isNotEmpty()) {
                        db.dailySnapshotDao().insertAll(entities)
                        totalRecords += entities.size
                        realtimeCount = entities.size
                        for (nb in names) {
                            try { db.stockBasicDao().insert(nb) } catch (_: Exception) { }
                        }
                        Log.i(TAG, "  Realtime API: filled ${entities.size} stocks for $todayStr")
                    } else {
                        Log.w(TAG, "  Realtime API: no matching stocks found (market=${diffList.length()} stocks, filtered=0)")
                    }
                } else {
                    Log.w(TAG, "  Realtime API: empty diff list")
                }
            } else {
                Log.w(TAG, "  Realtime API: HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "  Realtime API failed: ${e.message}")
        }
        val step1Elapsed = System.currentTimeMillis() - step1Start
        Log.i(TAG, "  Step 1 done: ${step1Elapsed}ms, records=$realtimeCount")

        // Step 2: K-line API for full coverage
        val step2Start = System.currentTimeMillis()
        val concurrency = 10 // 降低併發，避免被限流
        Log.i(TAG, "--- Step 2: K-line API (${stocks.size} stocks, concurrency=$concurrency) ---")
        var failedStocks = 0
        var successStocks = 0
        if (stocks.isNotEmpty()) {
            stocks.chunked(concurrency).forEachIndexed { chunkIdx, batch ->
                val chunkStart = System.currentTimeMillis()
                val jobs = batch.map { code -> async { fetchOneStock(code, startDate, endDate) } }
                for (job in jobs) {
                    val (records, name) = job.await()
                    if (records.isNotEmpty()) {
                        db.dailySnapshotDao().insertAll(records)
                        totalRecords += records.size
                        successStocks++
                        if (name.isNotBlank()) {
                            try {
                                db.stockBasicDao().insert(StockBasicEntity(code = records.first().code, name = name, business = ""))
                            } catch (_: Exception) { }
                        }
                    } else {
                        failedStocks++
                    }
                    val done = doneCount.addAndGet(1)
                    onProgress?.invoke(FetchProgress(totalStocks = stocks.size, completedStocks = done, totalRecords = totalRecords))
                }
                val chunkElapsed = System.currentTimeMillis() - chunkStart
                if (chunkIdx % 5 == 0) {
                    Log.d(TAG, "  K-line chunk $chunkIdx: ${doneCount.get()}/${stocks.size} done, $totalRecords records, ${chunkElapsed}ms")
                }
            }
        }
        val step2Elapsed = System.currentTimeMillis() - step2Start
        Log.i(TAG, "  Step 2 done: ${step2Elapsed}ms, success=$successStocks, failed=$failedStocks")

        // Step 3: fill missing names (only for stocks that actually need it)
        val step3Start = System.currentTimeMillis()
        val filledCount = fillMissingNames()
        if (filledCount > 0) Log.i(TAG, "  Names fixed: $filledCount stocks")
        val step3Elapsed = System.currentTimeMillis() - step3Start
        Log.i(TAG, "  Step 3 done: ${step3Elapsed}ms")

        prefs.edit().putBoolean("leader_stocks_fetched", true).putString("last_fetched_date", todayStr).apply()

        val elapsedMs = System.currentTimeMillis() - startMs
        Log.i(TAG, "========== FETCH COMPLETE ==========")
        Log.i(TAG, "  Records: $totalRecords  Stocks: ${stocks.size}  Time: ${elapsedMs}ms")
        Log.i(TAG, "  Breakdown: Step1=${step1Elapsed}ms  Step2=${step2Elapsed}ms  Step3=${step3Elapsed}ms")
        totalRecords
    }

    private suspend fun fillMissingNames(): Int {
        try {
            val recentDates = db.dailySnapshotDao().getAvailableDates(10)
            if (recentDates.isEmpty()) return 0
            // 只獲取缺少名稱的股票，而非全部
            val missingNameCodes = mutableSetOf<String>()
            for (date in recentDates) {
                val shots = db.dailySnapshotDao().getByDate(date)
                for (s in shots) {
                    if (s.name.isBlank()) missingNameCodes.add(s.code)
                }
            }
            if (missingNameCodes.isEmpty()) {
                Log.d(TAG, "  fillMissingNames: all stocks have names, skip")
                return 0
            }
            Log.i(TAG, "  fillMissingNames: ${missingNameCodes.size} stocks need names")
            var corrected = 0
            var failed = 0
            val fillStart = System.currentTimeMillis()
            for (code in missingNameCodes) {
                try {
                    val (_, name) = fetchOneStock(code, startDate = LocalDate.now().minusDays(3), endDate = LocalDate.now())
                    if (name.isNotBlank()) {
                        db.stockBasicDao().insert(StockBasicEntity(code = code, name = name, business = ""))
                        db.dailySnapshotDao().updateName(code, name)
                        corrected++
                    } else {
                        failed++
                    }
                } catch (_: Exception) { failed++ }
            }
            val fillElapsed = System.currentTimeMillis() - fillStart
            Log.i(TAG, "  fillMissingNames: $corrected/${missingNameCodes.size} fixed, $failed failed, ${fillElapsed}ms")
            return corrected
        } catch (e: Exception) {
            Log.w(TAG, "  fillMissingNames failed: ${e.message}")
            return 0
        }
    }

    private suspend fun retryHttp(url: String, maxRetries: Int, delayMs: Long): String? {
        for (i in 0..maxRetries) {
            try {
                if (i > 0) kotlinx.coroutines.delay(delayMs * (i.toLong() + 1))
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) { val body = resp.body?.string() ?: continue; return body }
                Log.d(TAG, "  retryHttp #$i: HTTP ${resp.code}")
            } catch (e: Exception) {
                Log.d(TAG, "  retryHttp #$i: ${e.message?.take(40)}")
            }
        }
        return null
    }

    private suspend fun fetchFromSina(code: String, startDate: LocalDate, endDate: LocalDate): List<DailySnapshotEntity> {
        val prefix = if (code.startsWith("sh")) "sh" else "sz"
        val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?" +
                "symbol=${prefix}$pureCode&scale=240&ma=no&datalen=300"
        val body = retryHttp(url, maxRetries = 2, delayMs = 500) ?: return emptyList()
        try {
            val arr = org.json.JSONArray(body)
            val results = mutableListOf<DailySnapshotEntity>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val dateStr = obj.optString("day", "").replace("-", "")
                if (dateStr.isEmpty()) continue
                val date = try { LocalDate.parse(dateStr, DATE_FMT).format(STORE_FMT) } catch (_: Exception) { continue }
                if (date < startDate.format(STORE_FMT) || date > endDate.format(STORE_FMT)) continue
                val close = obj.optDouble("close", 0.0)
                val open = obj.optDouble("open", 0.0)
                val volume = obj.optLong("volume", 0)
                val amount = if (close > 0 && volume > 0) close * volume else 0.0
                val changePct = if (open > 0 && close > 0) (close - open) / open * 100 else 0.0
                results.add(DailySnapshotEntity(code = code, name = "", date = date, open = open, close = close,
                    high = obj.optDouble("high", 0.0), low = obj.optDouble("low", 0.0),
                    volume = volume, amount = amount, changePct = changePct, turnoverRate = 0.0, mainNetInflow = 0.0))
            }
            return results
        } catch (e: Exception) {
            Log.w(TAG, "  Sina fetch $code: ${e.message}")
            return emptyList()
        }
    }

    internal suspend fun fetchOneStock(code: String, startDate: LocalDate, endDate: LocalDate): Pair<List<DailySnapshotEntity>, String> {
        val emResults = fetchFromEastMoney(code, startDate, endDate)
        if (emResults != null) return emResults
        val sinaResults = fetchFromSina(code, startDate, endDate)
        if (sinaResults.isNotEmpty()) {
            val name = sinaResults.firstOrNull()?.name ?: ""
            return Pair(sinaResults, name)
        }
        Log.w(TAG, "  fetchOneStock $code: both sources failed")
        return Pair(emptyList(), "")
    }

    private suspend fun fetchFromEastMoney(code: String, startDate: LocalDate, endDate: LocalDate): Pair<List<DailySnapshotEntity>, String>? {
        val market = if (code.startsWith("sh")) 1 else if (code.startsWith("bj")) 1 else 0
        val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val beg = startDate.format(DATE_FMT)
        val end = endDate.format(DATE_FMT)
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?" +
                "secid=$market.$pureCode&klt=101&fqt=1" +
                "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f61" +
                "&beg=$beg&end=$end&lmt=300"
        val maxRetries = 2
        for (attempt in 0..maxRetries) {
            try {
                if (attempt > 0) {
                    val delayMs = 500L * (1 shl attempt) // 指數退避: 1000ms, 2000ms
                    Log.d(TAG, "  EastMoney retry $attempt for $code after ${delayMs}ms")
                    kotlinx.coroutines.delay(delayMs)
                }
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", "https://quote.eastmoney.com/")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) { Log.d(TAG, "  EastMoney #$attempt HTTP ${resp.code} for $code"); continue }
                val body = resp.body?.string() ?: continue
                val data = JSONObject(body).optJSONObject("data") ?: continue
                val klines = data.optJSONArray("klines") ?: continue
                val rawName = data.optString("name", "").trim()
                val stockName = if (rawName.startsWith("XD") || rawName.startsWith("XR") || rawName.startsWith("DR")) {
                    rawName.removePrefix("XD").removePrefix("XR").removePrefix("DR").trim()
                } else rawName.takeIf { it.isNotBlank() && it.length < 20 } ?: ""
                val results = mutableListOf<DailySnapshotEntity>()
                for (i in 0 until klines.length()) {
                    val line = klines.getString(i)
                    val parts = line.split(",")
                    if (parts.size < 9) continue
                    val dateStr = parts[0].replace("-", "")
                    val date = try { LocalDate.parse(dateStr, DATE_FMT).format(STORE_FMT) } catch (_: Exception) { continue }
                    results.add(DailySnapshotEntity(code = code, name = stockName, date = date,
                        open = parts[1].toDoubleOrNull() ?: 0.0, close = parts[2].toDoubleOrNull() ?: 0.0,
                        high = parts[3].toDoubleOrNull() ?: 0.0, low = parts[4].toDoubleOrNull() ?: 0.0,
                        volume = parts[5].toLongOrNull() ?: 0L, amount = parts[6].toDoubleOrNull() ?: 0.0,
                        changePct = parts[8].toDoubleOrNull() ?: 0.0, turnoverRate = 0.0, mainNetInflow = 0.0))
                }
                if (attempt > 0) {
                    Log.d(TAG, "  EastMoney retry success for $code after $attempt attempts")
                }
                return Pair(results, stockName)
            } catch (e: Exception) {
                val err = e.message?.take(60) ?: "unknown"
                Log.d(TAG, "  EastMoney #$attempt for $code: $err")
                if (attempt == maxRetries) {
                    Log.w(TAG, "  EastMoney gave up on $code after $maxRetries retries")
                }
            }
        }
        return null
    }
}