package com.chin.stockanalysis.stock.database

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## App 後台統一調度器
 *
 * 啟動時執行：
 *  1. 熱門板塊池定時刷新
 *  2. 股票資料中心初始化
 *  3. 持倉監控（每 5 分鐘檢查一次）
 */
object AppBackgroundRunner {

    private const val TAG = "AppBgRunner"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private var monitorJob: Job? = null
    private var _appScope: CoroutineScope? = null

    /** 量化選股運行時設為 true，後臺 AI 相關任務應暫停 */
    @Volatile
    var isQuantRunning = false

    /** 暫停後臺 AI 任務（量化選股開始時調用） */
    fun pauseForQuant() {
        isQuantRunning = true
        Log.i(TAG, "⏸️ 量化選股開始，暫停後臺 AI 任務")
    }

    /** 量化開始前，先確保新聞因子是最新的，然後再暫停後臺 */
    suspend fun ensureNewsFreshThenPause(context: Context) {
        try {
            val updater = com.chin.stockanalysis.news.HotSectorNewsUpdater(context.applicationContext)
            updater.updateIfNeeded(forceRefresh = true)
            Log.i(TAG, "📰 新聞因子已刷新，暫停後臺 AI 任務")
        } catch (e: Exception) {
            Log.w(TAG, "新聞刷新失敗（不阻塞量化）: ${e.message}")
        }
        isQuantRunning = true
    }

    /** 恢復後臺 AI 任務並立即觸發一次（量化選股結束時調用） */
    suspend fun resumeAfterQuant(context: Context) {
        isQuantRunning = false
        Log.i(TAG, "▶️ 量化選股結束，恢復後臺 AI 任務，立即觸發一次")
        monitorJob?.cancel()
        try { monitorWatchlist(context) } catch (_: Exception) {}
        _appScope?.let { startPositionMonitor(context.applicationContext, it) }
    }

    /** 觸發一次持倉監控（不重啟定時器，供量化結束後額外調用） */
    suspend fun monitorWatchlistDirect(context: Context) {
        try { monitorWatchlist(context) } catch (e: Exception) { Log.w(TAG, "額外監控失敗: ${e.message}") }
    }

    fun start(context: Context, scope: CoroutineScope) {
        Log.i(TAG, "🚀 啟動後台任務")
        EastMoneyHotSectorSource.startPoolScheduler(scope)
        StockDataCenter.init(context.applicationContext, scope)
        _appScope = scope

        // 啟動時執行一次：遷移超過 5 天的 AI 精選到自選股
        scope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(context.applicationContext)
                val today = LocalDate.now().format(DATE_FMT)
                migrateOldAiPicksToWatchlist(context.applicationContext, db, today)
            } catch (_: Exception) {}
        }

        // 啟動時增量同步 daily_snapshot（拉取缺失的交易日數據）
        scope.launch(Dispatchers.IO) {
            syncMissingTradingDays(context.applicationContext)
        }

        startPositionMonitor(context.applicationContext, scope)
    }

    /**
     * 增量同步 daily_snapshot：
     * 查詢本地最後一條數據日期，拉取從那天到今天之間所有缺失的交易日數據。
     *
     * - 首次安裝：拉取最近 5 個交易日
     * - 正常使用：只拉取缺失的天數（通常 0-1 天）
     * - 長期未使用：拉取缺失的所有交易日（最多 30 天）
     */
    private suspend fun syncMissingTradingDays(context: Context) {
        try {
            val db = StockDatabase.getInstance(context)
            val today = LocalDate.now()

            // 查詢本地最新數據日期
            val existingDates = try {
                db.dailySnapshotDao().getAvailableDates(30)
            } catch (_: Exception) { emptyList() }

            val latestDate = existingDates
                .filter { it <= today.format(DATE_FMT) }
                .maxOrNull()

            if (latestDate != null && latestDate >= today.format(DATE_FMT)) {
                Log.i(TAG, "📅 daily_snapshot 已是最新（$latestDate），跳過增量同步")
                return
            }

            // 計算需要拉取的天數
            val startLocalDate = if (latestDate != null) {
                LocalDate.parse(latestDate, DATE_FMT).plusDays(1)
            } else {
                today.minusDays(5)  // 首次安裝，拉取最近 5 天
            }
            val daysToFetch = java.time.temporal.ChronoUnit.DAYS.between(startLocalDate, today).toInt().coerceIn(0, 30)

            if (daysToFetch <= 0) {
                Log.i(TAG, "📅 無需增量同步")
                return
            }

            Log.i(TAG, "📅 增量同步 daily_snapshot：從 ${startLocalDate.format(DATE_FMT)} 到 ${today.format(DATE_FMT)}（約 $daysToFetch 天）")

            val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(context)
            val count = fetcher.fetchAllHistoricalData(
                days = daysToFetch + 2,  // 多拉 2 天保險
                startDateOverride = startLocalDate,
                onProgress = { progress ->
                    Log.d(TAG, "📅 增量同步: ${progress.completedStocks}/${progress.totalStocks} (${progress.totalRecords} 條)")
                }
            )
            Log.i(TAG, "📅 增量同步完成：寫入 $count 條記錄")
        } catch (e: Exception) {
            Log.w(TAG, "📅 增量同步失敗（不阻塞啟動）: ${e.message}")
        }
    }

    private fun startPositionMonitor(context: Context, scope: CoroutineScope) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { monitorWatchlist(context) }
                catch (e: Exception) { Log.w(TAG, "監控異常: ${e.message}") }
                delay(5 * 60 * 1000L)
            }
        }
    }

    private suspend fun monitorWatchlist(context: Context) {
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)

        // ── 1. 監控自選股買賣點 ──
        val watchlist = db.userWatchlistDao().getAll()
        if (watchlist.isNotEmpty()) {
            val codes = watchlist.map { it.stockCode }

            val snapshots = try { db.dailySnapshotDao().getByDate(today) } catch (_: Exception) { emptyList() }
            val snapMap = snapshots.associateBy { s -> s.code }

            var buySignals = 0; var sellSignals = 0
            for (item in watchlist) {
                if (item.status == "SOLD") continue
                val snap = snapMap[item.stockCode] ?: continue
                val changePct = snap.changePct

                when {
                    item.status == "WATCHING" && changePct > 2.0 -> {
                        db.userWatchlistDao().update(item.copy(status = "BOUGHT", buyPrice = snap.close, buyDate = today))
                        buySignals++
                        Log.i(TAG, "🟢 自動買入: ${item.stockName}(${item.stockCode})")
                    }
                    item.status == "BOUGHT" && (changePct > 5.0 || changePct < -3.0) -> {
                        db.userWatchlistDao().update(item.copy(status = "SOLD", sellPrice = snap.close, sellDate = today))
                        sellSignals++
                        Log.i(TAG, "🟡 自動賣出: ${item.stockName}(${item.stockCode})")
                    }
                }
            }
            if (buySignals > 0 || sellSignals > 0)
                Log.i(TAG, "📊 監控(自選): ${watchlist.size}只 買入${buySignals} 賣出${sellSignals}")
        }

        // ── 2. 監控 AI 精選股買賣點 ──
        monitorAiSelectedStocks(context, db, today)
    }

    /**
     * 保留最近 5 天的 AI 精選記錄，超出的遷移到自選股後刪除
     */
    private suspend fun migrateOldAiPicksToWatchlist(context: Context, db: StockDatabase, today: String) {
        val aiDao = db.aiSelectedStockDao()
        val minDate = java.time.LocalDate.now().minusDays(5).format(DATE_FMT)
        val allAiStocks = aiDao.getAll()
        val oldStocks = allAiStocks.filter { it.selectedDate < minDate }
        if (oldStocks.isEmpty()) return

        Log.i(TAG, "🔄 遷移 ${oldStocks.size} 只超 5 天 AI 精選股到自選股...")
        val watchlistDao = db.userWatchlistDao()
        for (stock in oldStocks) {
            val existing = watchlistDao.getByCode(stock.stockCode)
            if (existing == null) {
                watchlistDao.insert(UserWatchlistEntity(
                    stockCode = stock.stockCode,
                    stockName = stock.stockName,
                    source = "ai_${stock.source}",
                    addedDate = today,
                    status = "WATCHING",
                    scoreAtAdd = stock.score
                ))
                Log.i(TAG, "  ➕ ${stock.stockName}(${stock.stockCode}) → 自選股")
            }
        }
        // 刪除超過 5 天的記錄
        aiDao.deleteBeforeDate(minDate)
        Log.i(TAG, "✅ AI 精選遷移完成，保留近 5 天數據")
    }

    /**
     * 監控 AI 精選股（當天）
     */
    private suspend fun monitorAiSelectedStocks(context: Context, db: StockDatabase, today: String) {
        val aiDao = db.aiSelectedStockDao()
        val aiStocks = aiDao.getByDate(today)
        if (aiStocks.isEmpty()) return

        val snapshots = try { db.dailySnapshotDao().getByDate(today) } catch (_: Exception) { emptyList() }
        val snapMap = snapshots.associateBy { s -> s.code }

        var buySignals = 0
        for (stock in aiStocks) {
            val snap = snapMap[stock.stockCode] ?: continue
            val changePct = snap.changePct

            // AI 精選當天漲幅 > 2% → 自動加入自選股並標記為 BOUGHT
            if (changePct > 2.0) {
                val watchlistDao = db.userWatchlistDao()
                val existing = watchlistDao.getByCode(stock.stockCode)
                if (existing == null) {
                    watchlistDao.insert(UserWatchlistEntity(
                        stockCode = stock.stockCode,
                        stockName = stock.stockName,
                        source = "ai_${stock.source}",
                        addedDate = today,
                        status = "BOUGHT",
                        buyPrice = snap.close,
                        buyDate = today,
                        scoreAtAdd = stock.score
                    ))
                    buySignals++
                    Log.i(TAG, "🤖 AI精選自動買入: ${stock.stockName}(${stock.stockCode})")
                }
            }
        }
        if (buySignals > 0)
            Log.i(TAG, "📊 AI監控: ${aiStocks.size}只精選 買入${buySignals}")
    }

    /** 將策略精選股添加到自選股 */
    suspend fun addToWatchlist(context: Context, stockCode: String, stockName: String, source: String, score: Int = 0) {
        val db = StockDatabase.getInstance(context)
        if (db.userWatchlistDao().getByCode(stockCode) == null) {
            db.userWatchlistDao().insert(UserWatchlistEntity(
                stockCode = stockCode, stockName = stockName, source = source,
                addedDate = LocalDate.now().format(DATE_FMT), scoreAtAdd = score
            ))
        }
    }

    suspend fun addBatchToWatchlist(context: Context, stocks: List<Triple<String, String, Int>>, source: String) {
        StockDatabase.getInstance(context).userWatchlistDao().insertAll(
            stocks.map { (c, n, s) -> UserWatchlistEntity(stockCode = c, stockName = n, source = source, addedDate = LocalDate.now().format(DATE_FMT), scoreAtAdd = s) }
        )
    }

    /** 保存當天 AI 精選股（保留 5 天記錄，清除超過 5 天的舊數據） */
    suspend fun saveAiSelectedStocks(context: Context, stocks: List<AiSelectedStockEntity>) {
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)
        val minDate = LocalDate.now().minusDays(5).format(DATE_FMT)
        // 刪除超過 5 天的記錄，保留近 5 天
        db.aiSelectedStockDao().deleteBeforeDate(minDate)
        db.aiSelectedStockDao().insertAll(stocks)
        Log.i(TAG, "🤖 AI精選保存: ${stocks.size} 只 → ai_selected_stock（保留近 5 天）")
    }

    /** 清除當天 AI 精選（策略重新運行前調用） */
    suspend fun clearTodayAiSelected(context: Context) {
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)
        db.aiSelectedStockDao().deleteByDate(today)
    }
}
