package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.database.StockBasicEntity
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.ui.TradingDayPickerView
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
            (getDefaultIndexCodes() + LeaderStockPool.getMainlineCodes(context) + getPool(context, "core_stock_pool_json", "core_stock_pool.json")).toList()

        /**
         * 默认指数/ETF 列表，确保每次数据同步都会更新
         * 包含：A 股主要指数、热门 ETF
         * 注意：美股/韩国需要不同 API 格式，暂不加入
         */
        fun getDefaultIndexCodes(): Set<String> = setOf(
            // A 股主要指数
            "sh000001", // 上证指数
            "sz399001", // 深证成指
            "sz399006", // 创业板指
            "sh000688", // 科创50
            "sz399303", // 国证2000
            // A 股热门 ETF
            "sh510300", // 沪深300ETF
            "sh510500", // 中证500ETF
            "sz159915", // 创业板ETF
            "sh588000", // 科创50ETF
            "sz159949", // 创业板50ETF
            "sh512100"  // 中证1000ETF
        )

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
        incremental: Boolean = false,
        startDateOverride: LocalDate? = null,
        onProgress: ((FetchProgress) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val endDate = LocalDate.now()
        var startDate = startDateOverride
            // force=true 一律回填到 2024-01-01：工作台「回溯+拟合」需要一年窗口 + MA250 回看
            ?: (if (force) LocalDate.of(2024, 1, 1) else endDate.minusDays((days * 1.5).toLong()))

        Log.i(TAG, "========== FETCH START ==========")
        Log.i(TAG, "  Date range: ${startDate.format(STORE_FMT)} ~ ${endDate.format(STORE_FMT)}")
        Log.i(TAG, "  force=$force  incremental=$incremental  days=$days")

        val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay()
        val todayStr = today.format(STORE_FMT)
        val isNonTrading = !com.chin.stockanalysis.ui.TradingDayPickerView.isTradingDay(today)
        Log.i(TAG, "  today=$todayStr  nonTrading=$isNonTrading")

        val prefs = context.getSharedPreferences("data_import", Context.MODE_PRIVATE)
        val leaderFetched = prefs.getBoolean("leader_stocks_fetched", false)
        Log.i(TAG, "  leaderFetched=$leaderFetched")

        // 增量拉取标记：每只股票已同步到的最大日期（daily_snapshot 实表 + prefs 双保险）
        // 判断规则：已有 maxDate >= 最近交易日 → 跳过；有数据但日期旧 → 只补 maxDate+1 ~ today；
        // 从未拉过 → 全量 startDate 起。标记写 prefs 的 sync_upto_<code>，供界面展示/快速判断。
        val maxDates: Map<String, String> = if (incremental) {
            try {
                db.dailySnapshotDao().getMaxDateByCode().associate { it.code to it.maxDate }
            } catch (e: Exception) {
                Log.w(TAG, "  增量标记查询失败，回退全量拉取: ${e.message}")
                emptyMap()
            }
        } else emptyMap()
        Log.i(TAG, "  已同步标记: ${maxDates.size} 只股票有历史数据")

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
            val realtimeUrl = "${DataConfig.eastmoneyPush2}/clist/get?" +
                    "pn=1&pz=200&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23" +
                    "&fields=f2,f3,f4,f5,f6,f8,f9,f12,f14,f15,f16,f17,f18,f20,f23"
            val req = Request.Builder().url(realtimeUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", DataConfig.eastmoneyQuote)
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
                            mainNetInflow = 0.0,
                            pe = item.optDouble("f9", 0.0),
                            pb = item.optDouble("f23", 0.0),
                            marketCap = item.optDouble("f20", 0.0)
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
        val concurrency = 10 // 降低并发，避免被限流
        Log.i(TAG, "--- Step 2: K-line API (${stocks.size} stocks, concurrency=$concurrency) ---")
        var failedStocks = 0
        var successStocks = 0
        var skippedStocks = 0
        if (stocks.isNotEmpty()) {
            stocks.chunked(concurrency).forEachIndexed { chunkIdx, batch ->
                val chunkStart = System.currentTimeMillis()
                val jobs = batch.map { code -> async {
                    if (incremental) {
                        val have = maxDates[code]
                        when {
                            // 已同步到最近交易日 → 跳过（不重复拉取）
                            have != null && have >= todayStr -> Triple(emptyList(), "", true)
                            // 从未拉过 → 全量
                            have == null -> {
                                val (records, name) = fetchOneStock(code, startDate, endDate)
                                Triple(records, name, false)
                            }
                            // 有旧数据 → 只补 maxDate+1 ~ today（缺口增量）
                            else -> {
                                val effStart = runCatching { LocalDate.parse(have, STORE_FMT).plusDays(1) }.getOrNull()
                                    ?.let { if (it.isAfter(startDate)) it else startDate } ?: startDate
                                val (records, name) = fetchOneStock(code, effStart, endDate)
                                Triple(records, name, false)
                            }
                        }
                    } else {
                        val (records, name) = fetchOneStock(code, startDate, endDate)
                        Triple(records, name, false)
                    }
                } }
                for (job in jobs) {
                    val (records, name, skipped) = job.await()
                    val done = doneCount.addAndGet(1)
                    if (skipped) {
                        skippedStocks++
                        onProgress?.invoke(FetchProgress(totalStocks = stocks.size, completedStocks = done, totalRecords = totalRecords))
                        continue
                    }
                    if (records.isNotEmpty()) {
                        db.dailySnapshotDao().insertAll(records)
                        totalRecords += records.size
                        successStocks++
                        // 同步标记：该股票已拉取到最新交易日（增量判断依据）
                        prefs.edit().putString("sync_upto_${records.first().code}", todayStr).apply()
                        if (name.isNotBlank()) {
                            try {
                                db.stockBasicDao().insert(StockBasicEntity(code = records.first().code, name = name, business = ""))
                            } catch (_: Exception) { }
                        }
                    } else {
                        failedStocks++
                    }
                    onProgress?.invoke(FetchProgress(totalStocks = stocks.size, completedStocks = done, totalRecords = totalRecords))
                }
                val chunkElapsed = System.currentTimeMillis() - chunkStart
                if (chunkIdx % 5 == 0) {
                    Log.d(TAG, "  K-line chunk $chunkIdx: ${doneCount.get()}/${stocks.size} done, $totalRecords records, ${chunkElapsed}ms")
                }
            }
        }
        val step2Elapsed = System.currentTimeMillis() - step2Start
        Log.i(TAG, "  Step 2 done: ${step2Elapsed}ms, success=$successStocks, failed=$failedStocks, skipped=$skippedStocks")

        // Step 2.5: 基本面充实（K线写入会覆盖当日行的 PE/PB/市值，须在其后回写）
        val step25Start = System.currentTimeMillis()
        Log.i(TAG, "--- Step 2.5: Fundamentals enrichment (${stocks.size} stocks) ---")
        val enrichedCount = try {
            enrichFundamentals(todayStr, stocks)
        } catch (e: Exception) {
            Log.w(TAG, "  Fundamentals enrichment failed: ${e.message}"); 0
        }
        val step25Elapsed = System.currentTimeMillis() - step25Start
        Log.i(TAG, "  Step 2.5 done: ${step25Elapsed}ms, enriched=$enrichedCount")

        // Step 2.6: 历史基本面回填（将季报数据映射到历史交易日）
        val step26Start = System.currentTimeMillis()
        Log.i(TAG, "--- Step 2.6: Historical fundamentals backfill ---")
        val backfilledCount = try {
            backfillHistoricalFundamentals(stocks)
        } catch (e: Exception) {
            Log.w(TAG, "  Historical backfill failed: ${e.message}"); 0
        }
        val step26Elapsed = System.currentTimeMillis() - step26Start
        Log.i(TAG, "  Step 2.6 done: ${step26Elapsed}ms, backfilled=$backfilledCount")

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
        Log.i(TAG, "  Breakdown: Step1=${step1Elapsed}ms  Step2=${step2Elapsed}ms  Step2.5=${step25Elapsed}ms  Step2.6=${step26Elapsed}ms  Step3=${step3Elapsed}ms")
        totalRecords
    }

    /**
     * 轻量刷新今日实时行情：仅对指定股票更新当日快照的行情字段（OHLCV/涨跌幅/换手/PE/PB/市值），
     * 保留基本面字段（ROE/毛利率/负债/现金流）。用于"一键选股/一键建仓"前，确保使用的是
     * 当前时点的价格，而不是上次同步时（如 13:00）缓存的旧价。
     *
     * @return 成功刷新的股票数
     */
    suspend fun refreshTodayRealtime(codes: List<String>): Int = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext 0
        val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay()
        val todayStr = today.format(STORE_FMT)
        var updated = 0
        try {
            val quotes = com.chin.stockanalysis.stock.data.sources.EastMoneyStockSource().fetchRealtime(codes)
            // 同步真实持仓最新价：实仓表只存买入时快照，每个交易日盘中刷新时用最新行情回写
            try {
                val positions = db.realPositionDao().getAllActive()
                for (p in positions) {
                    val q = quotes[p.stockCode] ?: continue
                    db.realPositionDao().updateMarketData(
                        id = p.id,
                        currentPrice = q.price,
                        pe = q.pe,
                        turnoverRate = q.turnoverRate
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshTodayRealtime: 实仓同步失败 ${e.message}")
            }
            for (q in quotes.values) {
                val affected = db.dailySnapshotDao().updateQuote(
                    code = q.code, date = todayStr,
                    open = q.open, close = q.price, high = q.high, low = q.low,
                    volume = q.volume, amount = q.amount,
                    changePct = q.changePercent, turnoverRate = q.turnoverRate,
                    pe = q.pe, pb = q.pb, marketCap = q.marketCap
                )
                if (affected == 0) {
                    // 当日行不存在（如首次盘中刷新），则插入完整行
                    db.dailySnapshotDao().insertAll(listOf(
                        DailySnapshotEntity(
                            code = q.code, name = q.name, date = todayStr,
                            open = q.open, close = q.price, high = q.high, low = q.low,
                            volume = q.volume, amount = q.amount,
                            changePct = q.changePercent, turnoverRate = q.turnoverRate,
                            mainNetInflow = 0.0, pe = q.pe, pb = q.pb, marketCap = q.marketCap
                        )
                    ))
                }
                updated++
            }
            Log.i(TAG, "refreshTodayRealtime: 更新 $updated 只股票今日行情 ($todayStr)")
        } catch (e: Exception) {
            Log.w(TAG, "refreshTodayRealtime failed: ${e.message}")
        }
        updated
    }

    /**
     * 基本面充实：把 PE/PB/市值（push2 行情批量）与 ROE/毛利率/负债率/现金流
     * （datacenter 财务批量）回写到当日快照行。
     *
     * 必要性：K 线 API 只有 OHLCV，Step 2 的 REPLACE 写入会把 Step 1 已带的
     * PE/PB/市值清零；基本面策略（机构增持/护城河/低估值/周期低位）的硬性
     * 过滤全部依赖这些字段。
     *
     * @return 实际更新的行数
     */
    private suspend fun enrichFundamentals(date: String, stocks: List<String>): Int {
        // 1. 行情批量：PE/PB/市值/换手率（复用 EastMoneyStockSource，内部按 50 分批）
        val quoteMap = try {
            com.chin.stockanalysis.stock.data.sources.EastMoneyStockSource().fetchRealtime(stocks)
        } catch (e: Exception) {
            Log.w(TAG, "  quote batch failed: ${e.message}"); emptyMap()
        }

        // 2. 财务批量：ROE/毛利率/负债率/现金流（按报告期倒序，每股取最新一期）
        val financeMap = FundamentalsProvider.fetchBulkFinance(neededCodes = stocks)

        if (quoteMap.isEmpty() && financeMap.isEmpty()) return 0

        var updated = 0
        for (code in stocks) {
            val q = quoteMap[code]
            val f = financeMap[code]
            if (q == null && f == null) continue
            // 至少有一个有效值才回写（市值>0 或 PE!=0 或 ROE!=0）
            val hasValue = (q != null && (q.marketCap > 0 || q.pe != 0.0)) ||
                    (f != null && (f.roe != 0.0 || f.grossMargin != 0.0))
            if (!hasValue) continue
            try {
                updated += db.dailySnapshotDao().updateFundamentals(
                    code = code, date = date,
                    pe = q?.pe ?: 0.0,
                    pb = q?.pb ?: 0.0,
                    marketCap = q?.marketCap ?: 0.0,
                    roeTTM = f?.roe ?: 0.0,
                    grossMarginTTM = f?.grossMargin ?: 0.0,
                    debtToAsset = f?.debtToAsset ?: 0.0,
                    operatingCashFlow = f?.operatingCashFlow ?: 0.0,
                    turnoverRate = q?.turnoverRate ?: 0.0
                )
            } catch (_: Exception) { /* 单只失败不中断 */ }
        }
        return updated
    }

    /**
     * 历史基本面回填：将季报财务数据按报告期映射到历史交易日。
     *
     * A 股财报披露截止日规则：
     * - Q1（报告期 03-31）→ 5/1 起可用
     * - H1（报告期 06-30）→ 9/1 起可用
     * - Q3（报告期 09-30）→ 11/1 起可用
     * - 年报（报告期 12-31）→ 次年 5/1 起可用
     *
     * 对每只股票，将其各报告期财务数据回填到对应的历史日期区间，
     * 仅覆盖 roe_ttm = 0 且 gross_margin_ttm = 0 的行。
     *
     * @return 实际更新的行数
     */
    private suspend fun backfillHistoricalFundamentals(stocks: List<String>): Int {
        // 1. 拉取多报告期财务数据
        val financeHistory = FundamentalsProvider.fetchBulkFinanceHistory(maxPages = 20, maxPeriodsPerStock = 8)
        if (financeHistory.isEmpty()) {
            Log.w(TAG, "  历史财务数据为空，跳过回填")
            return 0
        }

        // 2. 获取所有可用交易日
        val allDates = db.dailySnapshotDao().getAvailableDates(500).sorted()
        if (allDates.isEmpty()) return 0

        // 3. 找出需要回填的日期（基本面为 0 的日期）
        // 简化：检查最近日期，如果最新日期已有基本面数据则跳过最新日期
        val latestDate = allDates.last()
        val datesToBackfill = allDates.filter { it < latestDate }
        if (datesToBackfill.isEmpty()) return 0

        // 4. 按报告期分组日期
        val datesByPeriod = datesToBackfill.groupBy { reportPeriodForDate(it) }
        Log.i(TAG, "  需回填 ${datesToBackfill.size} 个交易日, 分 ${datesByPeriod.size} 个报告期")

        // 5. 逐股票逐报告期回填
        var totalUpdated = 0
        val stockSet = stocks.toSet()

        for ((period, dates) in datesByPeriod) {
            val fromDate = dates.first()
            val toDate = dates.last()

            for ((code, periods) in financeHistory) {
                if (code !in stockSet) continue
                // 找到该报告期对应的财务数据
                val finData = periods.find { it.reportDate == period } ?: continue
                // 至少有一个有效值才回填
                if (finData.roe == 0.0 && finData.grossMargin == 0.0) continue

                try {
                    totalUpdated += db.dailySnapshotDao().updateFundamentalsForDateRange(
                        code = code, fromDate = fromDate, toDate = toDate,
                        roeTTM = finData.roe,
                        grossMarginTTM = finData.grossMargin,
                        debtToAsset = finData.debtToAsset,
                        operatingCashFlow = finData.operatingCashFlow
                    )
                } catch (_: Exception) { /* 单只/单期失败不中断 */ }
            }
        }

        Log.i(TAG, "  历史基本面回填完成: $totalUpdated 行 (${financeHistory.size} 只股票, ${datesByPeriod.size} 个报告期)")
        return totalUpdated
    }

    /**
     * 根据交易日期推算适用的报告期。
     *
     * A 股财报披露截止日：
     * - 1/1~4/30  → 上年年报 (YYYY-12-31)
     * - 5/1~8/31  → 当年 Q1 (YYYY-03-31)
     * - 9/1~10/31 → 当年 H1 (YYYY-06-30)
     * - 11/1~12/31 → 当年 Q3 (YYYY-09-30)
     */
    private fun reportPeriodForDate(dateStr: String): String {
        val parts = dateStr.split("-")
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        return when {
            month in 1..4 -> "${year - 1}-12-31"
            month in 5..8 -> "$year-03-31"
            month in 9..10 -> "$year-06-30"
            else -> "$year-09-30"
        }
    }

    private suspend fun fillMissingNames(): Int {
        try {
            val recentDates = db.dailySnapshotDao().getAvailableDates(10)
            if (recentDates.isEmpty()) return 0
            // 只获取缺少名称的股票，而非全部
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
            val fillStart = System.currentTimeMillis()
            var corrected = 0

            // 1. 先从 stock_basic 表查找已有名称
            val existingBasics = db.stockBasicDao().getAll().associate { it.code to it.name }
            for (code in missingNameCodes.toList()) {
                val existingName = existingBasics[code]
                if (!existingName.isNullOrBlank()) {
                    db.dailySnapshotDao().updateName(code, existingName)
                    corrected++
                }
            }
            val remainingCodes = missingNameCodes.filter { code ->
                db.dailySnapshotDao().getByDate(recentDates.first()).find { it.code == code }?.name.isNullOrBlank()
            }
            Log.i(TAG, "  fillMissingNames: $corrected fixed from stock_basic, ${remainingCodes.size} remaining")

            // 2. 用新浪批量 API 轻量获取剩余名称
            if (remainingCodes.isNotEmpty()) {
                val sinaFixed = fetchNamesFromSinaBatch(remainingCodes)
                corrected += sinaFixed
            }

            val fillElapsed = System.currentTimeMillis() - fillStart
            Log.i(TAG, "  fillMissingNames: $corrected/${missingNameCodes.size} fixed, ${missingNameCodes.size - corrected} remaining, ${fillElapsed}ms")
            return corrected
        } catch (e: Exception) {
            Log.w(TAG, "  fillMissingNames failed: ${e.message}")
            return 0
        }
    }

    /** 新浪批量 API 轻量获取名称：一个请求最多 60 个股票 */
    private suspend fun fetchNamesFromSinaBatch(codes: List<String>): Int {
        var fixed = 0
        val batches = codes.chunked(60)
        for (batch in batches) {
            try {
                val url = "${DataConfig.sinaHq}/list=${batch.joinToString(",")}"
                val req = Request.Builder().url(url)
                    .header("Referer", DataConfig.sinaFinance)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                if (!resp.isSuccessful) continue
                // 新浪返回 GBK 编码，必须用原始字节解码
                val bodyBytes = resp.body?.bytes() ?: continue
                val body = try {
                    String(bodyBytes, java.nio.charset.Charset.forName("GBK"))
                } catch (_: Exception) { String(bodyBytes) }
                // 解析: var hq_str_sh600000="浦发银行,10.50,...";
                val regex = Regex("var hq_str_(sh\\d+|sz\\d+|bj\\d+)=\"([^,]*)")
                val matches = regex.findAll(body)
                for (match in matches) {
                    val code = match.groupValues[1]
                    val name = match.groupValues[2].trim()
                    if (name.isNotBlank()) {
                        db.stockBasicDao().insert(StockBasicEntity(code = code, name = name, business = ""))
                        db.dailySnapshotDao().updateName(code, name)
                        fixed++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "  fetchNamesFromSinaBatch failed: ${e.message}")
            }
        }
        Log.i(TAG, "  fetchNamesFromSinaBatch: $fixed/${codes.size} fixed")
        return fixed
    }

    /**
     * 批量补全指定股票的显示名称（供首次导入 / 扫描前调用）。
     * 先查本地 stock_basics，其余走新浪批量行情（每批最多 60 只）；补到的名称同步写入 basics 与日K。
     * @return 本次新增/修正的数量
     */
    suspend fun fillNamesForCodes(codes: List<String>): Int {
        if (codes.isEmpty()) return 0
        try {
            val existing = try {
                db.stockBasicDao().getByCodes(codes).associate { it.code to it.name }
            } catch (_: Exception) { emptyMap() }
            val remaining = codes.filter { existing[it].isNullOrBlank() }
            if (remaining.isEmpty()) return 0
            return fetchNamesFromSinaBatch(remaining)
        } catch (e: Exception) {
            Log.w(TAG, "fillNamesForCodes failed: ${e.message}")
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
        val url = "${DataConfig.sinaKline}?" +
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

    /**
     * 公开单股增量更新：拉取最近 [days] 根 K 线并落库。
     * 供趋势图扫描、AI 对话框等"按需更新单只股票"场景使用；
     * 调用方应通过公共内存池（TrendScanMemoryPool）自行去重，避免重复拉取。
     */
    suspend fun fetchStockLatest(code: String, days: Int = 120): Boolean = withContext(Dispatchers.IO) {
        try {
            val end = LocalDate.now()
            val start = end.minusDays((days * 1.5).toLong())
            val (records, name) = fetchOneStock(code, start, end)
            if (records.isEmpty()) return@withContext false
            db.dailySnapshotDao().insertAll(records)
            val prefs = context.getSharedPreferences("data_import", Context.MODE_PRIVATE)
            prefs.edit().putString(
                "sync_upto_${records.first().code}",
                TradingDayPickerView.recentTradingDay().format(STORE_FMT)
            ).apply()
            if (name.isNotBlank()) {
                try {
                    db.stockBasicDao().insert(StockBasicEntity(code = records.first().code, name = name, business = ""))
                } catch (_: Exception) { }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "fetchStockLatest 失败 $code: ${e.message}")
            false
        }
    }

    internal suspend fun fetchOneStock(code: String, startDate: LocalDate, endDate: LocalDate): Pair<List<DailySnapshotEntity>, String> {
        val normalizedCode = StockAnalysisAgent.normalizeStockCode(code)
        val emResults = fetchFromEastMoney(normalizedCode, startDate, endDate)
        if (emResults != null) return emResults
        val sinaResults = fetchFromSina(normalizedCode, startDate, endDate)
        if (sinaResults.isNotEmpty()) {
            val name = sinaResults.firstOrNull()?.name ?: ""
            return Pair(sinaResults, name)
        }
        Log.w(TAG, "  fetchOneStock $normalizedCode: both sources failed")
        return Pair(emptyList(), "")
    }

    /**
     * 东财日 K 抓取（分段回填）。
     * 单次 lmt=300 上限约 1.2 年交易日；工作台「回溯+拟合」需 2 年回看，
     * 因此从 endDate 向前分段抓取直到覆盖 startDate，再按日期去重合并。
     */
    private suspend fun fetchFromEastMoney(code: String, startDate: LocalDate, endDate: LocalDate): Pair<List<DailySnapshotEntity>, String>? {
        val market = if (code.startsWith("sh")) 1 else if (code.startsWith("bj")) 1 else 0
        val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val all = mutableListOf<DailySnapshotEntity>()
        var stockName = ""
        var curEnd = endDate
        var guard = 0
        while (guard < 10 && !curEnd.isBefore(startDate)) {
            val chunk = fetchEastMoneyChunk(code, market, pureCode, startDate, curEnd)
            if (chunk == null) break
            if (chunk.first.isEmpty()) break
            all.addAll(chunk.first)
            if (stockName.isBlank()) stockName = chunk.second
            val earliest = chunk.first.minByOrNull { it.date } ?: break
            val earliestDate = try { LocalDate.parse(earliest.date, STORE_FMT) } catch (_: Exception) { break }
            if (!earliestDate.isAfter(startDate)) break
            curEnd = earliestDate.minusDays(1)
            guard++
        }
        if (all.isEmpty()) return null
        val deduped = all.distinctBy { it.date }.sortedBy { it.date }
        return Pair(deduped, stockName)
    }

    /** 单次东财 K 线请求（lmt=300），带指数退避重试 */
    private suspend fun fetchEastMoneyChunk(code: String, market: Int, pureCode: String, startDate: LocalDate, endDate: LocalDate): Pair<List<DailySnapshotEntity>, String>? {
        val beg = startDate.format(DATE_FMT)
        val end = endDate.format(DATE_FMT)
        val url = "${DataConfig.eastmoneyPush2his}/stock/kline/get?" +
                "secid=$market.$pureCode&klt=101&fqt=1" +
                "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f61" +
                "&beg=$beg&end=$end&lmt=300"
        val maxRetries = 2
        for (attempt in 0..maxRetries) {
            try {
                if (attempt > 0) {
                    val delayMs = 500L * (1 shl attempt) // 指数退避: 1000ms, 2000ms
                    Log.d(TAG, "  EastMoney retry $attempt for $pureCode after ${delayMs}ms")
                    kotlinx.coroutines.delay(delayMs)
                }
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", DataConfig.eastmoneyQuote)
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) { Log.d(TAG, "  EastMoney #$attempt HTTP ${resp.code} for $pureCode"); continue }
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
                    Log.d(TAG, "  EastMoney retry success for $pureCode after $attempt attempts")
                }
                return Pair(results, stockName)
            } catch (e: Exception) {
                val err = e.message?.take(60) ?: "unknown"
                Log.d(TAG, "  EastMoney #$attempt for $pureCode: $err")
                if (attempt == maxRetries) {
                    Log.w(TAG, "  EastMoney gave up on $pureCode after $maxRetries retries")
                }
            }
        }
        return null
    }
}