package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.backtest.StrategyOptimizer
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.strategy.models.StrategySignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 策略擬合 + 回溯復盤引擎
 *
 * 從 SimulationTradeEngine 提取的獨立模塊，
 * 保留 autoFit / backtrackAndOptimize 功能，供 Fragment 數據菜單使用。
 */
class StrategyFittingEngine(private val context: Context) {

    companion object {
        private const val TAG = "StrategyFitting"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        const val MAX_STOCKS_PER_STRATEGY = 15
        const val ROTATION_THRESHOLD_DAYS = 3
    }

    private val db = StockDatabase.getInstance(context)

    // ══════════════════════════════════════════════════
    // 數據類型
    // ══════════════════════════════════════════════════

    data class TradeSessionConfig(
        val tradeDate: String = LocalDate.now().format(DATE_FMT),
        val periods: List<Int> = listOf(1, 3, 10, 30, 50, 100),
        val onlyMainBoard: Boolean = true,
        val maxFitRounds: Int = 1000,
        val targetAccuracy: Float = 0.55f,
        val holdingPeriod: Int = 10,
        val orderType: String = "AI精選"
    )

    data class StrategyPeriodResult(
        val strategyId: String,
        val strategyName: String,
        val periodDays: Int,
        val tradeDate: String,
        val rawStockSignals: List<StrategySignal>,
        val newsStrengthScore: Int,
        val rotationPenalty: Int,
        val afterMainBoardFilter: List<StrategySignal>,
        val filteredStocks: List<FilteredStockInfo>,
        val finalTop15: List<StrategySignal>,
        val aiSelectionReason: String = ""
    )

    data class FilteredStockInfo(
        val stockCode: String, val stockName: String, val reason: String
    )

    data class BacktrackReport(
        val tradeDate: String, val buyOrdersAnalyzed: List<BacktrackOrderAnalysis>,
        val missedOpportunities: List<BacktrackMissedStock>,
        val optimizedStrategies: List<BacktrackOptimizedStrategy>, val summary: String
    )

    data class BacktrackOrderAnalysis(
        val stockCode: String, val stockName: String,
        val buyPrice: Double, val nextDayPrice: Double,
        val profitPct: Double, val wasGood: Boolean
    )

    data class BacktrackMissedStock(
        val stockCode: String, val stockName: String,
        val fromStrategy: String, val filterReason: String,
        val wouldHaveProfitPct: Double, val suggestion: String
    )

    data class BacktrackOptimizedStrategy(
        val strategyId: String, val strategyName: String,
        val oldAccuracy: Float, val newAccuracy: Float,
        val paramChanges: List<String>
    )

    data class FittingRoundParam(
        val round: Int, val paramJson: String, val accuracy: Float,
        val avgReturn: Double, val hitCount: Int, val totalSignals: Int
    )

    // ══════════════════════════════════════════════════
    // autoFit — 快速擬合
    // ══════════════════════════════════════════════════

    suspend fun autoFit(strategies: List<Strategy>, recentDates: List<String>): List<String> = withContext(Dispatchers.IO) {
        val optimizedList = mutableListOf<String>()
        for (i in 0 until recentDates.size - 1) {
            val date = recentDates[i]
            val snaps = getTradingDayData(date)
            if (snaps.isEmpty()) continue
            val nextDate = recentDates[i + 1]
            val nextSnaps = getTradingDayData(nextDate)
            if (nextSnaps.isEmpty()) continue
            val stockList = snaps.map { snap ->
                StockRealtime(code=snap.code, name=snap.name, price=snap.close, open=snap.open,
                    yestClose=if(snap.changePct!=0.0&&snap.close!=0.0)snap.close/(1.0+snap.changePct/100.0) else snap.close,
                    high=snap.high, low=snap.low, volume=snap.volume, amount=snap.amount,
                    changePercent=snap.changePct, changeAmount=snap.close*snap.changePct/100, timestamp=System.currentTimeMillis())
            }
            for (strategy in strategies) {
                if (strategy.id == "ai_prediction") continue
                val rawSignals = executeStrategy(strategy, stockList) ?: continue
                if (rawSignals.isEmpty()) continue
                val top15 = rawSignals.sortedByDescending { it.strength }.take(MAX_STOCKS_PER_STRATEGY)
                var hit = 0; var totalRet = 0.0
                for (signal in top15) {
                    val nd = nextSnaps.find { it.code == signal.stockCode }
                    if (nd != null) { if (nd.changePct > 0) hit++; totalRet += nd.changePct }
                }
                val accuracy = if (top15.size > 0) hit.toFloat() / top15.size else 0f
                val avgRet = if (top15.size > 0) totalRet / top15.size else 0.0
                try {
                    db.strategyTradeFittingParamDao().insert(StrategyTradeFittingParamEntity(
                        strategyId = strategy.id, tradeDate = date, periodDays = 1,
                        paramJson = "{\"round\":\"auto\"}", fittingRound = 0,
                        accuracy = accuracy.toDouble(), avgReturn = avgRet, createdAt = System.currentTimeMillis()))
                } catch (_: Exception) {}
                val oldBest = try { db.strategyTradeFittingParamDao().getBestAccuracy(strategy.id, date, 1) ?: 0.0 } catch (_: Exception) { 0.0 }
                if (accuracy > oldBest) {
                    optimizedList.add("${strategy.name}: ${"%.1f".format(oldBest*100)}% → ${"%.1f".format(accuracy*100)}%")
                    Log.i(TAG, "🔧 autoFit: ${strategy.name} $date ${"%.1f".format(accuracy*100)}%")
                }
            }
        }
        return@withContext optimizedList
    }

    // ══════════════════════════════════════════════════
    // backtrackAndOptimize — 回溯復盤 + 網格搜索優化
    // ══════════════════════════════════════════════════

    suspend fun backtrackAndOptimize(strategies: List<Strategy>, config: TradeSessionConfig,
        oldSessionResults: List<StrategyPeriodResult>, boughtStocks: Set<String>): BacktrackReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "━━━ 回溯复盘 ━━━")
        val snapshots = getTradingDayData(config.tradeDate)
        if (snapshots.isEmpty()) return@withContext BacktrackReport(config.tradeDate, emptyList(), emptyList(), emptyList(), "无数据")
        val stockList = snapshots.map { snap ->
            StockRealtime(code=snap.code, name=snap.name, price=snap.close, open=snap.open,
                yestClose=if(snap.changePct!=0.0&&snap.close!=0.0)snap.close/(1.0+snap.changePct/100.0) else snap.close,
                high=snap.high, low=snap.low, volume=snap.volume, amount=snap.amount,
                changePercent=snap.changePct, changeAmount=snap.close*snap.changePct/100, timestamp=System.currentTimeMillis())
        }
        val nextDate = getNextTradingDay(config.tradeDate)
        val nextDayData = if (nextDate != null) try { db.dailySnapshotDao().getByDate(nextDate) } catch (_: Exception) { emptyList() } else emptyList()
        val nextDayPriceMap = nextDayData.associate { it.code to it.open }
        val allPeriodResults = mutableListOf<StrategyPeriodResult>()
        for (strategy in strategies) for (period in config.periods) {
            val rawSignals = executeStrategy(strategy, stockList) ?: continue
            if (rawSignals.isEmpty()) continue
            val newsScore = calculateNewsStrength(rawSignals, config.tradeDate)
            val rotationPenaltyScore = calculateRotationPenalty(rawSignals, config.tradeDate)
            val (filtered, filteredInfo) = filterByMainBoard(rawSignals, config.onlyMainBoard)
            val top15 = filtered.sortedByDescending { it.strength }.take(MAX_STOCKS_PER_STRATEGY)
            allPeriodResults.add(StrategyPeriodResult(strategyId=strategy.id, strategyName=strategy.name, periodDays=period,
                tradeDate=config.tradeDate, rawStockSignals=rawSignals.take(MAX_STOCKS_PER_STRATEGY),
                newsStrengthScore=newsScore, rotationPenalty=rotationPenaltyScore,
                afterMainBoardFilter=filtered.take(MAX_STOCKS_PER_STRATEGY), filteredStocks=filteredInfo.take(20), finalTop15=top15))
            savePeriodResultToDb(strategy.id, strategy.name, period, config.tradeDate, rawSignals, newsScore, rotationPenaltyScore,
                filtered.map{it.stockCode}, filteredInfo, top15)
        }
        val orderAnalysisList = mutableListOf<BacktrackOrderAnalysis>()
        for (code in boughtStocks) {
            val snap = snapshots.find { it.code == code } ?: continue
            val nextOpenPrice = nextDayPriceMap[code] ?: continue
            val netBuyPrice = snap.close * (1.0 + 0.0015)
            val netSellPrice = nextOpenPrice * (1.0 - 0.0015)
            val profitPct = (netSellPrice - netBuyPrice) / netBuyPrice * 100
            orderAnalysisList.add(BacktrackOrderAnalysis(code, snap.name, snap.close, nextOpenPrice, profitPct, profitPct > 0))
        }
        val optimizedList = mutableListOf<BacktrackOptimizedStrategy>()
        val btAvailableDates = try { db.dailySnapshotDao().getAvailableDates(31).reversed() } catch (_: Exception) { emptyList() }
        val strategiesForGrid = if (strategies.size > 3) {
            Log.i(TAG, "backtrackAndOptimize: 策略數=${strategies.size}，僅對前3個執行 gridSearch")
            strategies.take(3)
        } else strategies
        val gridSearchStart = System.currentTimeMillis()
        for (strategy in strategiesForGrid) for (period in config.periods) {
            val prs = allPeriodResults.filter { it.strategyId == strategy.id && it.periodDays == period }
            if (prs.isEmpty() || nextDayData.isEmpty()) continue
            if (strategy.weightFactors.isEmpty()) continue
            val tradeDate = prs.first().tradeDate
            try {
                val optimizer = StrategyOptimizer(context)
                val gridResult = optimizer.gridSearch(strategy, btAvailableDates)
                val fp = FittingRoundParam(
                    round = gridResult.totalCombinations,
                    paramJson = strategy.weightFactors.joinToString(",") { "${it.key}:${it.weight}" },
                    accuracy = gridResult.bestAccuracy,
                    avgReturn = gridResult.bestAvgReturn,
                    hitCount = (gridResult.bestAccuracy * 15).toInt(),
                    totalSignals = 15
                )
                saveFittingParams(strategy.id, tradeDate, period, listOf(fp))
                val oldBest = getOldBestAccuracy(strategy.id, tradeDate, period) ?: 0f
                optimizedList.add(BacktrackOptimizedStrategy(strategy.id, strategy.name, oldBest, gridResult.bestAccuracy,
                    listOf("網格搜索${gridResult.totalCombinations}組合: ${"%.1f".format(oldBest*100)}%→${"%.1f".format(gridResult.bestAccuracy*100)}%")))
            } catch (e: Exception) {
                Log.w(TAG, "backtrack gridSearch 失敗: ${strategy.name} ${e.message}")
            }
        }
        val gridSearchElapsed = System.currentTimeMillis() - gridSearchStart
        Log.i(TAG, "backtrackAndOptimize gridSearch 總耗時: ${gridSearchElapsed}ms, 策略數=${strategiesForGrid.size}/${strategies.size}")
        val sb = StringBuilder()
        sb.appendLine("📈 回溯复盘").appendLine("交易日: ${config.tradeDate}")
        sb.appendLine("📊 买入分析: ${orderAnalysisList.size}只, ${orderAnalysisList.count{it.wasGood}}盈利")
        for (o in orderAnalysisList.filter{!it.wasGood}) sb.appendLine("  ❌ ${o.stockName}: ${"%.2f".format(o.profitPct)}%")
        sb.appendLine("🔧 参数优化: ${optimizedList.size}策略")
        for (o in optimizedList) sb.appendLine("  ✅ ${o.strategyName}: ${o.paramChanges.joinToString()}")
        saveBacktrackResult(config.tradeDate, sb.toString())
        Log.i(TAG, "━━━ 回溯复盘完成 ━━━")
        BacktrackReport(config.tradeDate, orderAnalysisList, emptyList(), optimizedList, sb.toString())
    }

    // ══════════════════════════════════════════════════
    // 私有工具方法
    // ══════════════════════════════════════════════════

    private suspend fun getTradingDayData(date: String): List<DailySnapshotEntity> {
        val local = try { db.dailySnapshotDao().getByDate(date) } catch (e: Exception) { emptyList() }
        if (local.size >= 100) return local
        val today = LocalDate.now().format(DATE_FMT)
        if (local.size < 50) {
            try {
                val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(context)
                val todayStr = LocalDate.now().format(DATE_FMT)
                val fetchEnd = if (date > todayStr) todayStr else date
                val parsedEnd = LocalDate.parse(fetchEnd)
                val parsedStart = parsedEnd.minusDays(90)
                val daysDelta = java.time.temporal.ChronoUnit.DAYS.between(parsedStart, parsedEnd).toInt().coerceIn(5, 120)
                fetcher.fetchAllHistoricalData(days = daysDelta)
                val afterFetch = try { db.dailySnapshotDao().getByDate(date) } catch (_: Exception) { emptyList() }
                if (afterFetch.size > local.size) {
                    Log.i(TAG, "强制自动导入完成: ${afterFetch.size}条 (之前${local.size}条)")
                    return afterFetch
                }
            } catch (_: Exception) {}
        }
        if (date == today) {
            try {
                val screener = com.chin.stockanalysis.strategy.data.StockScreener(
                    com.chin.stockanalysis.stock.data.StockDataSourceFactory.createDefaultRepository(context.applicationContext),
                    context.applicationContext)
                val realtimeStocks = screener.scanFullMarket()
                if (realtimeStocks.isEmpty()) return local
                val entities = realtimeStocks.map { DailySnapshotEntity(code=it.code, name=it.name, date=date,
                    open=it.open, close=it.price, high=it.high, low=it.low, volume=it.volume,
                    amount=it.amount, changePct=it.changePercent, turnoverRate=it.turnoverRate, mainNetInflow=0.0,
                    pe=it.pe, pb=it.pb, marketCap=it.marketCap, roeTTM=it.roeTTM,
                    grossMarginTTM=it.grossMarginTTM, debtToAsset=it.debtToAsset, operatingCashFlow=it.operatingCashFlow) }
                db.dailySnapshotDao().insertAll(entities)
                return local + entities.filter { it.code !in local.map{l->l.code}.toSet() }
            } catch (e: Exception) { return local }
        } else {
            val topStocks = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher.getTopStocks(context)
            try {
                val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(context)
                val parsedDate = LocalDate.parse(date)
                val rangeStart = parsedDate.minusDays(5); val rangeEnd = parsedDate.plusDays(1)
                var fetched = 0
                for (code in topStocks) {
                    try {
                        val (records, _) = fetcher.fetchOneStock(code, rangeStart, rangeEnd)
                        val dateRecords = records.filter{it.date==date}
                        if(dateRecords.isNotEmpty()) { db.dailySnapshotDao().insertAll(dateRecords); fetched+=dateRecords.size }
                    } catch (_: Exception) {}
                }
                if(fetched==0) { val daysAgo=java.time.temporal.ChronoUnit.DAYS.between(parsedDate,LocalDate.now()).toInt()
                    fetcher.fetchAllHistoricalData(days=(daysAgo+3).coerceAtLeast(5)) }
                return db.dailySnapshotDao().getByDate(date).ifEmpty { local }
            } catch (e: Exception) { return local }
        }
    }

    private suspend fun executeStrategy(strategy: Strategy, stockList: List<StockRealtime>): List<StrategySignal>? = try {
        strategy.screenWithData(stockList).getOrNull()?.signals?.filter {
            it.action == SignalAction.BUY || it.action == SignalAction.WATCH }?.sortedByDescending{it.strength}
    } catch (e: Exception) { Log.w(TAG,"策略失敗:${e.message}"); null }

    private suspend fun calculateNewsStrength(signals: List<StrategySignal>, tradeDate: String): Int = try {
        val fromDate = LocalDate.parse(tradeDate).minusDays(3).format(DATE_FMT)
        val newsList = db.newsFactorDao().getActiveByDateRange(fromDate, tradeDate)
        if(newsList.isEmpty()) 50 else {
            val signalCodes = signals.map{it.stockCode}.toSet()
            val related = newsList.filter{it.stockCode in signalCodes || it.sector.isNotEmpty()}
            if(related.isEmpty()) 40 else {
                val avgStr = related.map{it.impactStrength}.average()
                val avgSent = related.map{it.sentiment.toDouble()}.average()
                (avgStr*(0.5+avgSent*0.3)).roundToInt().coerceIn(0,100)
            }
        }
    } catch (_: Exception) { 50 }

    private suspend fun calculateRotationPenalty(signals: List<StrategySignal>, tradeDate: String): Int = try {
        val recentDates = db.dailySnapshotDao().getAvailableDates(10).take(5)
        if(recentDates.size<3) 0 else {
            val streaks = mutableMapOf<String,Int>()
            for(code in signals.map{it.stockCode}.toSet())
                for(sector in StockDataCenter.getSectorsByStock(code))
                    streaks[sector]=(streaks[sector]?:0)+1
            var penalty = 0
            for((_,cnt) in streaks) if(cnt>=ROTATION_THRESHOLD_DAYS) penalty-=(cnt-ROTATION_THRESHOLD_DAYS+1)*10
            penalty.coerceIn(-100,0)
        }
    } catch (_: Exception) { 0 }

    private fun isMainBoard(code: String): Boolean = !(code.startsWith("sz300")||code.startsWith("sz301")||code.startsWith("sh688")||code.startsWith("bj"))

    private suspend fun filterByMainBoard(signals: List<StrategySignal>, onlyMainBoard: Boolean): Pair<List<StrategySignal>,List<FilteredStockInfo>> {
        if(!onlyMainBoard) return signals to emptyList()
        val filtered = mutableListOf<StrategySignal>(); val info = mutableListOf<FilteredStockInfo>()
        for(s in signals) if(isMainBoard(s.stockCode)) filtered.add(s) else info.add(FilteredStockInfo(s.stockCode,s.stockName,
            when{s.stockCode.startsWith("sz300")||s.stockCode.startsWith("sz301")->"创业板";s.stockCode.startsWith("sh688")->"科创板";s.stockCode.startsWith("bj")->"北交所";else->"非主板"}))
        return filtered to info
    }

    private suspend fun getOldBestAccuracy(strategyId: String, tradeDate: String, period: Int): Float? =
        try { db.strategyTradeFittingParamDao().getBestAccuracy(strategyId, tradeDate, period)?.toFloat() } catch (_: Exception) { null }

    private suspend fun saveBacktrackResult(tradeDate: String, summary: String) {
        try {
            db.dailyPeriodResultDao().insert(DailyPeriodResultEntity(strategyId="BACKTRACK", strategyName="回溯复盘",
                tradeDate=tradeDate, periodDays=0, stockCodesJson="[]", stockCount=0, newsStrengthScore=0,
                rotationPenalty=0, mainBoardFilter=false, filteredCodesJson="[]", filteredReasonJson="[]",
                finalTop3Json="[]", aiSelectionReason=summary, createdAt=System.currentTimeMillis()))
        } catch (_: Exception) {}
    }

    private suspend fun savePeriodResultToDb(sid:String,sname:String,period:Int,tradeDate:String,rawSignals:List<StrategySignal>,news:Int,rotation:Int,codes:List<String>,info:List<FilteredStockInfo>,top15:List<StrategySignal>) {
        try { db.dailyPeriodResultDao().insert(DailyPeriodResultEntity(strategyId=sid,strategyName=sname,tradeDate=tradeDate,periodDays=period,
            stockCodesJson=JSONArray(rawSignals.map{it.stockCode}).toString(),stockCount=rawSignals.size,newsStrengthScore=news,rotationPenalty=rotation,
            mainBoardFilter=true,filteredCodesJson=JSONArray(codes).toString(),
            filteredReasonJson=JSONArray(info.map{JSONObject().apply{put("code",it.stockCode);put("name",it.stockName);put("reason",it.reason)}}).toString(),
            finalTop3Json=JSONArray(top15.map{JSONObject().apply{put("code",it.stockCode);put("name",it.stockName);put("score",it.strength);put("reason",it.reason.take(100))}}).toString(),
            aiSelectionReason="",createdAt=System.currentTimeMillis()))
        } catch (e: Exception) { Log.w(TAG,"保存失敗:${e.message}") }
    }

    private suspend fun saveFittingParams(sid:String,date:String,period:Int,rounds:List<FittingRoundParam>) {
        try { for(p in rounds) db.strategyTradeFittingParamDao().insert(StrategyTradeFittingParamEntity(strategyId=sid,tradeDate=date,periodDays=period,paramJson=p.paramJson,fittingRound=p.round,accuracy=p.accuracy.toDouble(),avgReturn=p.avgReturn,createdAt=System.currentTimeMillis())) }
        catch (_: Exception) {}
    }

    private suspend fun getNextTradingDay(date: String): String? =
        com.chin.stockanalysis.ui.TradingDayPickerView.getNextTradingDayFromDb(date) { context }
}
