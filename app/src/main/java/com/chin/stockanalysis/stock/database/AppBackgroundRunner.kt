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

    fun start(context: Context, scope: CoroutineScope) {
        Log.i(TAG, "🚀 啟動後台任務")
        EastMoneyHotSectorSource.startPoolScheduler(scope)
        StockDataCenter.init(context.applicationContext, scope)
        startPositionMonitor(context.applicationContext, scope)
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

        // ── 1. 遷移前一天的 AI 精選股到自選股 ──
        migrateOldAiPicksToWatchlist(context, db, today)

        // ── 2. 監控自選股買賣點 ──
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

        // ── 3. 監控 AI 精選股買賣點 ──
        monitorAiSelectedStocks(context, db, today)
    }

    /**
     * 將非當天的 AI 精選股遷移到自選股，然後清除舊 AI 精選記錄
     */
    private suspend fun migrateOldAiPicksToWatchlist(context: Context, db: StockDatabase, today: String) {
        val aiDao = db.aiSelectedStockDao()
        val allAiStocks = aiDao.getAll()
        val oldStocks = allAiStocks.filter { it.selectedDate != today }
        if (oldStocks.isEmpty()) return

        Log.i(TAG, "🔄 遷移 ${oldStocks.size} 只舊 AI 精選股到自選股...")
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
        // 只保留當天的 AI 精選
        aiDao.keepOnlyToday(today)
        Log.i(TAG, "✅ AI 精選遷移完成，保留今日數據")
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

    /** 保存當天 AI 精選股（先清除舊數據，再寫入最新） */
    suspend fun saveAiSelectedStocks(context: Context, stocks: List<AiSelectedStockEntity>) {
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)
        // 只保留當天的，舊的自動遷移
        db.aiSelectedStockDao().keepOnlyToday(today)
        db.aiSelectedStockDao().insertAll(stocks)
        Log.i(TAG, "🤖 AI精選保存: ${stocks.size} 只 → ai_selected_stock")
    }

    /** 清除當天 AI 精選（策略重新運行前調用） */
    suspend fun clearTodayAiSelected(context: Context) {
        val db = StockDatabase.getInstance(context)
        val today = LocalDate.now().format(DATE_FMT)
        db.aiSelectedStockDao().deleteByDate(today)
    }
}
