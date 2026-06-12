package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.strategy.models.StrategySignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ## 模拟交易引擎 v2.0 — 9步精選流程
 *
 * ### 9步流程
 * Step 1: App啟動時構建熱門板塊選股池（緩存）
 * Step 2: TOP_STOCKS 174隻做底倉
 * Step 3: 當日漲幅Top50 + 成交量Top50 補入活躍股
 * Step 4: 合併去重 → ~200-250隻/天
 * Step 5: 策略對這200-250隻篩選 → 各取Top 15
 * Step 6: 跨5個交易日去重按命中天數排序 → 取前20
 * Step 7: 加入Step 1獲取的板塊精選股
 * Step 8: 加入用戶搜索 + 智能體推薦的個股
 * Step 9: AI預測最終 ~30-50隻 → Top 3買入建議
 */
class SimulationTradeEngine(private val context: Context) {

    companion object {
        private const val TAG = "SimTradeEngine"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val PERIOD_DAYS = listOf(1, 3, 10, 30, 50, 100)
        const val MAX_STOCKS_PER_STRATEGY = 15  // Step 5: 每個策略取 Top15
        const val FINAL_TOP3 = 3
        const val CROSS_DAY_WINDOW = 5  // Step 6: 跨5天聚合
        const val CROSS_DAY_TOP_N = 20  // Step 6: 取前20

        /** 板块连续上涨阈值（超过此天数考虑轮动） */
        const val ROTATION_THRESHOLD_DAYS = 3
        /** 新闻热点加权幅度 */
        const val NEWS_WEIGHT_BOOST = 0.15
        /** 板块轮动惩罚幅度 */
        const val ROTATION_PENALTY = 0.10
    }

    private val db = StockDatabase.getInstance(context)

    data class TradeSessionConfig(
        val tradeDate: String = LocalDate.now().format(DATE_FMT),
        val periods: List<Int> = PERIOD_DAYS,
        val onlyMainBoard: Boolean = true,
        val maxFitRounds: Int = 1000,
        val targetAccuracy: Float = 0.55f
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

    data class TradeSessionReport(
        val config: TradeSessionConfig,
        val periodResults: List<StrategyPeriodResult>,
        val aiTop3: List<AIPick>,
        val buyOrders: List<TradeOrder>,
        val summary: String,
        val finalPoolCodes: List<String> = emptyList(),
        val crossDayRanking: List<Pair<String, Int>> = emptyList()
    )

    data class AIPick(
        val rank: Int, val stockCode: String, val stockName: String,
        val compositeScore: Int, val upProbability: Int,
        val reason: String, val actionSuggestion: String
    )

    data class TradeOrder(
        val stockCode: String, val stockName: String, val strategyId: String,
        val tradeDate: String, val buyPrice: Double, val quantity: Int,
        val buyTime: String = LocalTime.now().toString().take(8),
        val reason: String = "", val scoreAtBuy: Int = 0,
        val orderType: String = "模拟买入", var status: String = "PENDING"
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

    // ══════════════════════════════════════════════════
    // 主入口：9步精選流程
    // ══════════════════════════════════════════════════

    suspend fun runTradeSession(
        strategies: List<Strategy>,
        config: TradeSessionConfig = TradeSessionConfig()
    ): TradeSessionReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "━━━ 9步精選模擬交易 ━━━")
        Log.i(TAG, "交易日: ${config.tradeDate}")

        // ═══ Step 1-4: 構建當日精選股票池 ═══
        val poolCodes = buildDailyStockPool(config.tradeDate)
        Log.i(TAG, "【Step 1-4】精選池: ${poolCodes.size} 隻")

        // ═══ 獲取當日快照（只取精選池中股票） ═══
        val allSnapshots = getTradingDayData(config.tradeDate)
        if (allSnapshots.isEmpty()) {
            return@withContext TradeSessionReport(config, emptyList(), emptyList(), emptyList(),
                "交易日 ${config.tradeDate} 无可用数据")
        }
        val snapshots = allSnapshots.filter { it.code in poolCodes }
        Log.i(TAG, "全市場${allSnapshots.size}隻 → 精選池${snapshots.size}隻")

        // 轉換為 StockRealtime
        var stockList = snapshots.map { snap ->
            StockRealtime(
                code = snap.code, name = snap.name, price = snap.close,
                open = snap.open, yestClose = if (snap.changePct != 0.0 && snap.close != 0.0) snap.close / (1.0 + snap.changePct / 100.0) else snap.close,
                high = snap.high, low = snap.low, volume = snap.volume,
                amount = snap.amount, changePercent = snap.changePct,
                changeAmount = snap.close * snap.changePct / 100, timestamp = System.currentTimeMillis()
            )
        }

        // 主板過濾
        if (config.onlyMainBoard) {
            stockList = stockList.filter { isMainBoard(it.code) }
            Log.i(TAG, "主板過濾後: ${stockList.size} 隻")
        }

        // 熱度排序
        val heatScores = mutableMapOf<String, Int>()
        stockList.forEach { stock -> heatScores[stock.code] = StockDataCenter.calculateStockHeatScore(stock.code, allSnapshots) }
        stockList = stockList.sortedByDescending { heatScores[it.code] ?: 0 }

        // ═══ Step 5: 每個策略對精選池評分，取 Top 15 ═══
        val allTodayResults = mutableListOf<StrategyPeriodResult>()
        for (strategy in strategies) {
            if (strategy.id == "ai_prediction") continue
            val rawSignals = executeStrategy(strategy, stockList, 1) ?: continue
            if (rawSignals.isEmpty()) continue
            val newsScore = calculateNewsStrength(rawSignals, config.tradeDate)
            val rotationPenaltyScore = calculateRotationPenalty(rawSignals, config.tradeDate)
            val (filtered, filteredInfo) = filterByMainBoard(rawSignals, config.onlyMainBoard)
            val top15 = filtered.sortedByDescending { it.strength }.take(MAX_STOCKS_PER_STRATEGY)
            allTodayResults.add(StrategyPeriodResult(
                strategyId = strategy.id, strategyName = strategy.name,
                periodDays = 1, tradeDate = config.tradeDate,
                rawStockSignals = rawSignals.take(MAX_STOCKS_PER_STRATEGY),
                newsStrengthScore = newsScore, rotationPenalty = rotationPenaltyScore,
                afterMainBoardFilter = filtered.take(MAX_STOCKS_PER_STRATEGY),
                filteredStocks = filteredInfo.take(20), finalTop15 = top15
            ))
            Log.d(TAG, "  ${strategy.name}: Top15 = ${top15.take(5).joinToString { it.stockName }}...")
            savePeriodResultToDb(strategy.id, strategy.name, 1, config.tradeDate, rawSignals, newsScore, rotationPenaltyScore,
                filtered.map { it.stockCode }, filteredInfo, top15)
        }
        Log.i(TAG, "【Step 5】${allTodayResults.size} 個策略各輸出 Top15")

        // ═══ Step 6: 跨5交易日聚合，按命中天數排序取前20 ═══
        val crossDayTop20 = aggregateCrossDayResults(config.tradeDate, strategies, poolCodes)
        Log.i(TAG, "【Step 6】跨${CROSS_DAY_WINDOW}天聚合 → Top${crossDayTop20.size}: ${
            crossDayTop20.take(8).joinToString { "${it.first.takeLast(6)}(${it.second}天)" }}")

        // ═══ Step 7: 加入板塊精選股 ═══
        val sectorPicked = getHotSectorStockPool()
        Log.i(TAG, "【Step 7】板塊精選: ${sectorPicked.size} 隻加入")

        // ═══ Step 8: 加入用戶搜索 + 智能體推薦股 ═══
        val userStockCodes = StockDataCenter.getUserSearchStockCodes()
        val skillPickCodes = StockDataCenter.getSkillPickStockCodes()
        Log.i(TAG, "【Step 8】用戶搜索${userStockCodes.size}隻 + 智能體${skillPickCodes.size}隻")

        // ═══ Step 9: 合併最終池 → AI 預測 ═══
        val finalPool = (crossDayTop20.map { it.first }.toSet() + sectorPicked + userStockCodes + skillPickCodes).toSet()
        Log.i(TAG, "【Step 9】最終AI輸入池: ${finalPool.size} 隻")

        // 過濾出這 finalPool 的快照 → 轉 StockRealtime → 調 AI 策略
        val finalSnapshots = allSnapshots.filter { it.code in finalPool }
        val finalStockList = finalSnapshots.map { snap ->
            StockRealtime(
                code = snap.code, name = snap.name, price = snap.close,
                open = snap.open, yestClose = if (snap.changePct != 0.0 && snap.close != 0.0) snap.close / (1.0 + snap.changePct / 100.0) else snap.close,
                high = snap.high, low = snap.low, volume = snap.volume,
                amount = snap.amount, changePercent = snap.changePct,
                changeAmount = snap.close * snap.changePct / 100, timestamp = System.currentTimeMillis()
            )
        }

        // 用 AI 策略做最終精選
        val aiStrategy = strategies.find { it.id == "ai_prediction" }
        val aiPicks = if (aiStrategy != null && aiStrategy is com.chin.stockanalysis.strategy.strategies.AIPredictionStrategy) {
            // 先跑其他策略給 AI 策略提供 ScreeningResult
            val screeningResults = mutableListOf<com.chin.stockanalysis.strategy.models.ScreeningResult>()
            for (strategy in strategies) {
                if (strategy.id == "ai_prediction") continue
                executeStrategy(strategy, finalStockList, 1)?.let { signals ->
                    screeningResults.add(com.chin.stockanalysis.strategy.models.ScreeningResult(
                        strategyId = strategy.id, strategyName = strategy.name,
                        category = strategy.category, signals = signals,
                        totalScanned = signals.size, scanTimeMs = 0
                    ))
                }
            }
            aiStrategy.strategyResults = screeningResults
            aiStrategy.targetDate = config.tradeDate
            val aiSignals = executeStrategy(aiStrategy, finalStockList, 1) ?: emptyList()
            aiSignals.sortedByDescending { it.strength }.take(FINAL_TOP3).mapIndexed { i, s ->
                AIPick(rank = i+1, stockCode = s.stockCode, stockName = s.stockName,
                    compositeScore = s.strength, upProbability = ((s.strength * 0.8 + 20).toInt()).coerceIn(0,100),
                    reason = s.reason, actionSuggestion = if (s.strength>=75) "推荐买入" else if (s.strength>=60) "关注" else "观望")
            }
        } else emptyList()

        val buyOrders = generateBuyOrders(aiPicks, config.tradeDate, allSnapshots)
        saveDailyNewsHotPicks(config.tradeDate)
        runFitting(strategies, allTodayResults, config)

        val summary = buildEnhancedSummary(config, poolCodes.size, crossDayTop20.size, finalPool.size, aiPicks, crossDayTop20)
        // 保存最終精選池到 DB
        saveFinalPoolToDb(config.tradeDate, finalPool.toList(), crossDayTop20)
        TradeSessionReport(config, allTodayResults, aiPicks, buyOrders, summary, finalPool.toList(), crossDayTop20)
    }

    /** 保存最終精選池到 daily_period_result 表（strategyId = "FINAL_POOL"） */
    private suspend fun saveFinalPoolToDb(tradeDate: String, codes: List<String>, crossDay: List<Pair<String, Int>>) {
        try {
            val top3Json = JSONArray(crossDay.take(10).map { (code, days) ->
                JSONObject().apply { put("code", code); put("days", days) }
            }).toString()
            db.dailyPeriodResultDao().insert(DailyPeriodResultEntity(
                strategyId = "FINAL_POOL", strategyName = "9步精選最終池",
                tradeDate = tradeDate, periodDays = 9,
                stockCodesJson = JSONArray(codes).toString(),
                stockCount = codes.size,
                newsStrengthScore = 0, rotationPenalty = 0,
                mainBoardFilter = true,
                filteredCodesJson = "[]", filteredReasonJson = "[]",
                finalTop3Json = top3Json,
                aiSelectionReason = "共${codes.size}隻精選股輸入AI",
                createdAt = System.currentTimeMillis()
            ))
            Log.i(TAG, "💾 最終精選池已保存: ${codes.size} 隻")
        } catch (e: Exception) { Log.w(TAG, "保存最終池失敗: ${e.message}") }
    }

    // ══════════════════════════════════════════════════
    // Step 6: 跨交易日聚合
    // ══════════════════════════════════════════════════

    private suspend fun aggregateCrossDayResults(
        baseDate: String,
        strategies: List<Strategy>,
        poolCodes: Set<String>
    ): List<Pair<String, Int>> {
        val allDates = try {
            db.dailySnapshotDao().getAvailableDates(CROSS_DAY_WINDOW + 5)
                .filter { it <= baseDate }.take(CROSS_DAY_WINDOW)
        } catch (_: Exception) { emptyList() }

        if (allDates.size < 2) {
            Log.w(TAG, "跨日聚合: 僅${allDates.size}天可用數據，跳過")
            return emptyList()
        }

        val hitCounts = mutableMapOf<String, Int>()
        val stockNames = mutableMapOf<String, String>()

        for (date in allDates) {
            try {
                val snaps = db.dailySnapshotDao().getByDate(date).filter { it.code in poolCodes }
                if (snaps.isEmpty()) continue
                val stockList = snaps.map { snap ->
                    StockRealtime(code=snap.code, name=snap.name, price=snap.close, open=snap.open,
                        yestClose=if(snap.changePct!=0.0&&snap.close!=0.0)snap.close/(1.0+snap.changePct/100.0) else snap.close,
                        high=snap.high, low=snap.low, volume=snap.volume, amount=snap.amount,
                        changePercent=snap.changePct, changeAmount=snap.close*snap.changePct/100,
                        timestamp=System.currentTimeMillis())
                }
                val seenToday = mutableSetOf<String>()
                for (strategy in strategies) {
                    if (strategy.id == "ai_prediction") continue
                    executeStrategy(strategy, stockList, 1)?.filter { it.stockCode !in seenToday }?.forEach { signal ->
                        hitCounts[signal.stockCode] = (hitCounts[signal.stockCode]?:0) + 1
                        seenToday.add(signal.stockCode)
                        stockNames[signal.stockCode] = signal.stockName
                    }
                }
            } catch (_: Exception) { /* skip this date */ }
        }

        return hitCounts.entries.sortedByDescending { it.value }.take(CROSS_DAY_TOP_N)
            .map { (code, days) -> code to days }
    }

    // ══════════════════════════════════════════════════
    // 回溯复盘（保持不變）
    // ══════════════════════════════════════════════════

    suspend fun backtrackAndOptimize(
        strategies: List<Strategy>, config: TradeSessionConfig,
        oldSessionResults: List<StrategyPeriodResult>, boughtStocks: Set<String>
    ): BacktrackReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "━━━ 回溯复盘 ━━━")
        val snapshots = getTradingDayData(config.tradeDate)
        if (snapshots.isEmpty()) return@withContext BacktrackReport(config.tradeDate, emptyList(), emptyList(), emptyList(), "无数据")
        val stockList = snapshots.map { snap ->
            StockRealtime(code=snap.code, name=snap.name, price=snap.close, open=snap.open,
                yestClose=if(snap.changePct!=0.0&&snap.close!=0.0)snap.close/(1.0+snap.changePct/100.0) else snap.close,
                high=snap.high, low=snap.low, volume=snap.volume, amount=snap.amount,
                changePercent=snap.changePct, changeAmount=snap.close*snap.changePct/100,
                timestamp=System.currentTimeMillis())
        }
        val nextDate = getNextTradingDay(config.tradeDate)
        val nextDayData = if (nextDate != null) try { db.dailySnapshotDao().getByDate(nextDate) } catch (_: Exception) { emptyList() } else emptyList()
        val nextDayPriceMap = nextDayData.associate { it.code to it.close }
        val allPeriodResults = mutableListOf<StrategyPeriodResult>()
        for (strategy in strategies) {
            for (period in config.periods) {
                val rawSignals = executeStrategy(strategy, stockList, period) ?: continue
                if (rawSignals.isEmpty()) continue
                val newsScore = calculateNewsStrength(rawSignals, config.tradeDate)
                val rotationPenaltyScore = calculateRotationPenalty(rawSignals, config.tradeDate)
                val (filtered, filteredInfo) = filterByMainBoard(rawSignals, config.onlyMainBoard)
                val top15 = filtered.sortedByDescending { it.strength }.take(MAX_STOCKS_PER_STRATEGY)
                allPeriodResults.add(StrategyPeriodResult(strategyId= strategy.id, strategyName= strategy.name,
                    periodDays= period, tradeDate= config.tradeDate, rawStockSignals= rawSignals.take(MAX_STOCKS_PER_STRATEGY),
                    newsStrengthScore= newsScore, rotationPenalty= rotationPenaltyScore,
                    afterMainBoardFilter= filtered.take(MAX_STOCKS_PER_STRATEGY),
                    filteredStocks= filteredInfo.take(20), finalTop15= top15))
                savePeriodResultToDb(strategy.id, strategy.name, period, config.tradeDate, rawSignals, newsScore, rotationPenaltyScore,
                    filtered.map{it.stockCode}, filteredInfo, top15)
            }
        }
        val orderAnalysisList = mutableListOf<BacktrackOrderAnalysis>()
        for (code in boughtStocks) {
            val snap = snapshots.find { it.code == code } ?: continue
            val nextPrice = nextDayPriceMap[code] ?: continue
            orderAnalysisList.add(BacktrackOrderAnalysis(code, snap.name, snap.close, nextPrice, (nextPrice-snap.close)/snap.close*100, (nextPrice-snap.close)/snap.close*100 > 0))
        }
        val missedOpportunities = mutableListOf<BacktrackMissedStock>()
        val optimizedList = mutableListOf<BacktrackOptimizedStrategy>()
        for (strategy in strategies) {
            for (period in config.periods) {
                val prs = allPeriodResults.filter { it.strategyId == strategy.id && it.periodDays == period }
                if (prs.isEmpty() || nextDayData.isEmpty()) continue
                val tradeDate = prs.first().tradeDate
                val fittingParams = mutableListOf<FittingRoundParam>()
                for (round in 1..config.maxFitRounds.coerceAtMost(500)) fittingParams.add(validateParams(prs.first(), nextDayData, round))
                saveFittingParams(strategy.id, tradeDate, period, fittingParams)
                val bestNew = fittingParams.maxByOrNull{it.accuracy}
                val oldBest = getOldBestAccuracy(strategy.id, tradeDate, period)
                optimizedList.add(BacktrackOptimizedStrategy(strategy.id, strategy.name, oldBest?:0f, bestNew?.accuracy?:0f,
                    listOf("重新拟合:${fittingParams.size}轮, ${"%.1f".format((oldBest?:0f)*100)}%→${"%.1f".format((bestNew?.accuracy?:0f)*100)}%")))
            }
        }
        val sb = StringBuilder()
        sb.appendLine("📈 回溯复盘").appendLine("交易日: ${config.tradeDate}")
        sb.appendLine("📊 买入分析: ${orderAnalysisList.size}只, ${orderAnalysisList.count{it.wasGood}}盈利")
        for (o in orderAnalysisList.filter{!it.wasGood}) sb.appendLine("  ❌ ${o.stockName}: ${"%.2f".format(o.profitPct)}%")
        sb.appendLine("🔧 参数优化: ${optimizedList.size}策略")
        for (o in optimizedList) sb.appendLine("  ✅ ${o.strategyName}: ${o.paramChanges.joinToString()}")
        saveBacktrackResult(config.tradeDate, sb.toString())
        Log.i(TAG, "━━━ 回溯复盘完成 ━━━")
        BacktrackReport(config.tradeDate, orderAnalysisList, missedOpportunities, optimizedList, sb.toString())
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

    // ══════════════════════════════════════════════════
    // 數據獲取
    // ══════════════════════════════════════════════════

    private suspend fun getTradingDayData(date: String): List<DailySnapshotEntity> {
        val local = try { db.dailySnapshotDao().getByDate(date) } catch (e: Exception) { emptyList() }
        if (local.size >= 100) return local
        val today = LocalDate.now().format(DATE_FMT)
        if (date == today) {
            try {
                val screener = com.chin.stockanalysis.strategy.data.StockScreener(
                    com.chin.stockanalysis.stock.data.StockDataSourceFactory.createDefaultRepository(context.applicationContext))
                val realtimeStocks = screener.scanFullMarket()
                if (realtimeStocks.isEmpty()) return local
                val entities = realtimeStocks.map { DailySnapshotEntity(code=it.code, name=it.name, date=date,
                    open=it.open, close=it.price, high=it.high, low=it.low, volume=it.volume,
                    amount=it.amount, changePct=it.changePercent, turnoverRate=it.turnoverRate, mainNetInflow=0.0) }
                db.dailySnapshotDao().insertAll(entities)
                return local + entities.filter { it.code !in local.map{l->l.code}.toSet() }
            } catch (e: Exception) { return local }
        } else {
            val topStocks = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher.TOP_STOCKS
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

    private suspend fun getPeriodData(periodDays: Int, baseDate: String): List<String> = try {
        db.dailySnapshotDao().getAvailableDates(periodDays+5).filter{it<=baseDate}.take(periodDays) } catch (_: Exception) { emptyList() }

    // ══════════════════════════════════════════════════
    // 擴展股票池 (Step 1-4)
    // ══════════════════════════════════════════════════

    private var hotSectorPoolCache: Set<String>? = null

    private suspend fun getHotSectorStockPool(): Set<String> {
        if (hotSectorPoolCache != null) return hotSectorPoolCache!!
        hotSectorPoolCache = HotSectorStockPool.build(context)
        return hotSectorPoolCache!!
    }

    suspend fun buildDailyStockPool(baseDate: String): Set<String> = withContext(Dispatchers.IO) {
        val pool = mutableSetOf<String>()
        pool.addAll(com.chin.stockanalysis.strategy.data.HistoricalDataFetcher.TOP_STOCKS)
        Log.d(TAG, "底倉: ${pool.size}隻")
        val sectorPool = getHotSectorStockPool(); pool.addAll(sectorPool)
        Log.d(TAG, "+板塊: ${sectorPool.size}隻 → ${pool.size}隻")
        try {
            val snaps = db.dailySnapshotDao().getByDate(baseDate)
            if(snaps.isNotEmpty()) {
                val gainers = snaps.sortedByDescending{it.changePct}.take(50).map{it.code}
                val volumeLeaders = snaps.sortedByDescending{it.volume}.take(50).map{it.code}
                pool.addAll(gainers); pool.addAll(volumeLeaders)
                Log.d(TAG, "+活躍: +${gainers.size}+${volumeLeaders.size} → ${pool.size}隻")
            }
        } catch (_: Exception) {}
        Log.i(TAG, "📊 ${baseDate} 擴展池: ${pool.size}隻")
        pool
    }

    // ══════════════════════════════════════════════════
    // 數據補齊
    // ══════════════════════════════════════════════════

    private suspend fun ensurePeriodDataAvailable(periods: List<Int>, baseDate: String) {
        val maxPeriod = periods.maxOrNull() ?: return
        val allDates = try { db.dailySnapshotDao().getAvailableDates(maxPeriod+5).filter{it<=baseDate}.take(maxPeriod) } catch (_: Exception) { emptyList() }
        val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(context)
        val topStocks = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher.TOP_STOCKS
        var fetchedCount = 0
        for (date in allDates) {
            try {
                if (db.dailySnapshotDao().getByDate(date).size >= 120) continue
                val parsedDate = LocalDate.parse(date); val rangeStart = parsedDate.minusDays(5); val rangeEnd = parsedDate.plusDays(1)
                var fetched = 0
                for (code in topStocks) {
                    try { val (records,_)=fetcher.fetchOneStock(code,rangeStart,rangeEnd)
                        val dr = records.filter{it.date==date}
                        if(dr.isNotEmpty()){db.dailySnapshotDao().insertAll(dr);fetched+=dr.size}
                    } catch (_: Exception) {}
                }
                if(fetched>0){fetchedCount+=fetched;Log.i(TAG,"📅 $date: +${fetched}隻")}
            } catch (_: Exception) {}
        }
        if(fetchedCount>0) Log.i(TAG,"📊 共補${fetchedCount}條記錄") else Log.i(TAG,"📊 數據充足")
    }

    // ══════════════════════════════════════════════════
    // 策略執行
    // ══════════════════════════════════════════════════

    private suspend fun executeStrategy(strategy: Strategy, stockList: List<StockRealtime>, periodDays: Int): List<StrategySignal>? = try {
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

    // ══════════════════════════════════════════════════
    // AI精選 + 買入 + 擬合
    // ══════════════════════════════════════════════════

    private suspend fun generateBuyOrders(aiPicks: List<AIPick>, tradeDate: String, snapshots: List<DailySnapshotEntity>): List<TradeOrder> =
        aiPicks.mapNotNull{pick->snapshots.find{it.code==pick.stockCode}?.let{ TradeOrder(pick.stockCode,pick.stockName,"AI_SELECTED",tradeDate,it.close,100,reason=pick.reason,scoreAtBuy=pick.compositeScore,orderType="AI精選") }}

    private suspend fun runFitting(strategies: List<Strategy>, results: List<StrategyPeriodResult>, config: TradeSessionConfig) {
        for (strategy in strategies) for (period in config.periods) {
            val prs = results.filter{it.strategyId==strategy.id && it.periodDays==period}; if(prs.isEmpty()) continue
            val tradeDate = prs.first().tradeDate
            val nextDate = getNextTradingDay(tradeDate)?:try{LocalDate.parse(tradeDate).plusDays(1).format(DATE_FMT)}catch(_:Exception){null}?:continue
            val nextDayData = getTradingDayData(nextDate); if(nextDayData.isEmpty()) continue
            val fp = mutableListOf<FittingRoundParam>()
            for(round in 1..config.maxFitRounds.coerceAtMost(1000)) fp.add(validateParams(prs.first(),nextDayData,round))
            saveFittingParams(strategy.id, tradeDate, period, fp)

            // 統一提煉：找到最佳輪次
            val bestRound = fp.maxByOrNull{ it.accuracy } ?: continue
            val oldBest = getOldBestAccuracy(strategy.id, tradeDate, period) ?: 0f

            // 如果擬合發現了更好的參數，更新共享 weightFactors 並持久化
            if (bestRound.accuracy > oldBest && bestRound.accuracy >= config.targetAccuracy) {
                try {
                    val bestParams = JSONObject(bestRound.paramJson)
                    for (factor in strategy.weightFactors) {
                        val newWeight = bestParams.optInt(factor.key, factor.weight)
                        strategy.weightFactors = strategy.weightFactors.map { f ->
                            if (f.key == factor.key) f.copy(weight = newWeight) else f
                        }
                    }
                    // 調用 StrategySelfTuner 的保存邏輯（持久化到 strategy_weight_snapshot）
                    val today = LocalDate.now().format(DATE_FMT)
                    val weightJson = strategy.weightFactors.joinToString(";") { "${it.key}:${it.label}:${it.weight}" }
                    db.strategyWeightSnapshotDao().insert(
                        com.chin.stockanalysis.strategy.backtest.StrategyWeightSnapshotEntity(
                            strategyId = strategy.id, date = today,
                            weightJson = weightJson, totalScore = 0, hitCount = bestRound.hitCount
                        )
                    )
                    Log.i(TAG, "🔧 [统一调优] ${strategy.name} 权重建模更新: 准确率 ${"%.1f".format(oldBest*100)}% → ${"%.1f".format(bestRound.accuracy*100)}%, 已持久化到 strategy_weight_snapshot")
                } catch (e: Exception) {
                    Log.w(TAG, "更新共享权重失败: ${e.message}")
                }
            } else {
                Log.d(TAG, "🔧 [统一调优] ${strategy.name} 未提升 (最佳${"%.1f".format(bestRound.accuracy*100)}% ≤ 历史${"%.1f".format(oldBest*100)}%或未达阈值${"%.0f".format(config.targetAccuracy*100)}%)")
            }
        }
    }

    data class FittingRoundParam(val round:Int,val paramJson:String,val accuracy:Float,val avgReturn:Double,val hitCount:Int,val totalSignals:Int)

    private fun generatePerturbedParams(strategy: Strategy, round: Int): String {
        val params=JSONObject()
        for(f in strategy.weightFactors) params.put(f.key,(f.weight+(Math.random()*20-10).roundToInt()).coerceIn(1,90))
        params.put("round",round); params.put("total_weight",strategy.weightFactors.sumOf{it.weight}); return params.toString()
    }

    private suspend fun validateParams(result: StrategyPeriodResult, nextDayData: List<DailySnapshotEntity>, round: Int): FittingRoundParam {
        var hit=0; var totalRet=0.0
        for(signal in result.finalTop15){ val nd=nextDayData.find{it.code==signal.stockCode}; if(nd!=null){ if(nd.changePct>0)hit++; totalRet+=nd.changePct } }
        return FittingRoundParam(round,"{\"round\":$round}",if(result.finalTop15.size>0)hit.toFloat()/result.finalTop15.size else 0f, if(result.finalTop15.size>0)totalRet/result.finalTop15.size else 0.0, hit, result.finalTop15.size)
    }

    // ══════════════════════════════════════════════════
    // 持久化
    // ══════════════════════════════════════════════════

    private suspend fun saveDailyNewsHotPicks(tradeDate: String) {
        try {
            val fromDate=LocalDate.parse(tradeDate).minusDays(1).format(DATE_FMT)
            val newsList=db.newsFactorDao().getActiveByDateRange(fromDate,tradeDate); if(newsList.isEmpty())return
            val ss=mutableMapOf<String,MutableList<Int>>(); val sn=mutableMapOf<String,MutableList<String>>(); val sst=mutableMapOf<String,MutableSet<String>>()
            for(n in newsList){val sec=n.sector.ifBlank{"综合"}; ss.getOrPut(sec){mutableListOf()}.add(n.impactStrength); sn.getOrPut(sec){mutableListOf()}.add(n.title); if(n.stockCode.isNotBlank())sst.getOrPut(sec){mutableSetOf()}.add(n.stockCode)}
            val top3=ss.entries.map{(sec,scores)->Triple(sec,scores.average().roundToInt(),sst[sec]?.joinToString(",")?:"")}.sortedByDescending{it.second}.take(3)
            var rank=1; for(item in top3){ db.dailyNewsHotPickDao().insert(DailyNewsHotPickEntity(newsDate=tradeDate,rank=rank,sectorName=item.first,subSectorName="",hotScore=item.second,newsTitle=sn[item.first]?.firstOrNull()?:"",relatedStockCodes=item.third)); rank++ }
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

    private suspend fun getNextTradingDay(date: String): String? = try {
        val dates=db.dailySnapshotDao().getAvailableDates(10).sorted(); val idx=dates.indexOf(date); if(idx>=0&&idx+1<dates.size)dates[idx+1] else null
    } catch (_: Exception) { null }

    private fun buildEnhancedSummary(config: TradeSessionConfig, poolSize: Int, crossDaySize: Int, finalPoolSize: Int, aiPicks: List<AIPick>, crossDay: List<Pair<String,Int>>): String {
        val sb = StringBuilder()
        sb.appendLine("📊 9步精選模擬交易報告")
        sb.appendLine("交易日: ${config.tradeDate}")
        sb.appendLine()
        sb.appendLine("📋 流程統計:")
        sb.appendLine("  Step 1-4: 精選池 $poolSize 隻")
        sb.appendLine("  Step 5:   各策略取 Top${MAX_STOCKS_PER_STRATEGY}")
        sb.appendLine("  Step 6:   跨${CROSS_DAY_WINDOW}天聚合 → 前${crossDaySize}隻")
        sb.appendLine("  Step 7-8: +板塊精選+用戶/智能體 → 最終池 $finalPoolSize 隻")
        sb.appendLine("  Step 9:   AI預測")
        sb.appendLine()
        if (crossDay.isNotEmpty()) {
            sb.appendLine("🔥 跨日聚合命中Top${minOf(10,crossDay.size)}:")
            for ((code, days) in crossDay.take(10)) sb.appendLine("  ${code.takeLast(6)}: ${days}天命中")
        }
        sb.appendLine()
        sb.appendLine("🤖 AI最終推薦:")
        for (pick in aiPicks) sb.appendLine("  #${pick.rank} ${pick.stockName}(${pick.stockCode.takeLast(6)}) 評分:${pick.compositeScore} ${pick.actionSuggestion}")
        return sb.toString()
    }
}

// ══════════════════════════════════════════════════
// Room Entity 定义
// ══════════════════════════════════════════════════

@androidx.room.Entity(
    tableName = "daily_period_result",
    indices = [androidx.room.Index(value = ["strategy_id", "trade_date", "period_days"], unique = true)]
)
data class DailyPeriodResultEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "strategy_id") val strategyId: String,
    @androidx.room.ColumnInfo(name = "strategy_name") val strategyName: String,
    @androidx.room.ColumnInfo(name = "trade_date") val tradeDate: String,
    @androidx.room.ColumnInfo(name = "period_days") val periodDays: Int,
    @androidx.room.ColumnInfo(name = "stock_codes_json") val stockCodesJson: String,
    @androidx.room.ColumnInfo(name = "stock_count") val stockCount: Int,
    @androidx.room.ColumnInfo(name = "news_strength_score") val newsStrengthScore: Int,
    @androidx.room.ColumnInfo(name = "rotation_penalty") val rotationPenalty: Int,
    @androidx.room.ColumnInfo(name = "main_board_filter") val mainBoardFilter: Boolean,
    @androidx.room.ColumnInfo(name = "filtered_codes_json") val filteredCodesJson: String,
    @androidx.room.ColumnInfo(name = "filtered_reason_json") val filteredReasonJson: String,
    @androidx.room.ColumnInfo(name = "final_top3_json") val finalTop3Json: String,
    @androidx.room.ColumnInfo(name = "ai_selection_reason") val aiSelectionReason: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long
)

@androidx.room.Entity(
    tableName = "strategy_trade_fitting_params",
    indices = [androidx.room.Index(value = ["strategy_id", "trade_date", "period_days", "fitting_round"])]
)
data class StrategyTradeFittingParamEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "strategy_id") val strategyId: String,
    @androidx.room.ColumnInfo(name = "trade_date") val tradeDate: String,
    @androidx.room.ColumnInfo(name = "period_days") val periodDays: Int,
    @androidx.room.ColumnInfo(name = "param_json") val paramJson: String,
    @androidx.room.ColumnInfo(name = "fitting_round") val fittingRound: Int,
    @androidx.room.ColumnInfo(name = "accuracy") val accuracy: Double,
    @androidx.room.ColumnInfo(name = "avg_return") val avgReturn: Double,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long
)

@androidx.room.Entity(
    tableName = "daily_news_hot_picks",
    indices = [androidx.room.Index(value = ["news_date", "rank"], unique = true)]
)
data class DailyNewsHotPickEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "news_date") val newsDate: String,
    @androidx.room.ColumnInfo(name = "rank") val rank: Int,
    @androidx.room.ColumnInfo(name = "sector_name") val sectorName: String,
    @androidx.room.ColumnInfo(name = "sub_sector_name") val subSectorName: String,
    @androidx.room.ColumnInfo(name = "hot_score") val hotScore: Int,
    @androidx.room.ColumnInfo(name = "news_title") val newsTitle: String,
    @androidx.room.ColumnInfo(name = "related_stock_codes") val relatedStockCodes: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@androidx.room.Entity(
    tableName = "strategy_trade_orders",
    indices = [
        androidx.room.Index(value = ["strategy_id", "trade_date"]),
        androidx.room.Index(value = ["stock_code", "trade_date"])
    ]
)
data class StrategyTradeOrderEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "strategy_id") val strategyId: String,
    @androidx.room.ColumnInfo(name = "stock_code") val stockCode: String,
    @androidx.room.ColumnInfo(name = "stock_name") val stockName: String,
    @androidx.room.ColumnInfo(name = "trade_date") val tradeDate: String,
    @androidx.room.ColumnInfo(name = "buy_price") val buyPrice: Double,
    @androidx.room.ColumnInfo(name = "buy_time") val buyTime: String,
    @androidx.room.ColumnInfo(name = "quantity") val quantity: Int,
    @androidx.room.ColumnInfo(name = "order_type") val orderType: String,
    @androidx.room.ColumnInfo(name = "sell_price") val sellPrice: Double = 0.0,
    @androidx.room.ColumnInfo(name = "sell_time") val sellTime: String = "",
    @androidx.room.ColumnInfo(name = "profit_pct") val profitPct: Double = 0.0,
    @androidx.room.ColumnInfo(name = "status") val status: String,
    @androidx.room.ColumnInfo(name = "reason") val reason: String,
    @androidx.room.ColumnInfo(name = "score_at_buy") val scoreAtBuy: Int,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

// ══════════════════════════════════════════════════
// DAO 定义
// ══════════════════════════════════════════════════

@androidx.room.Dao
interface DailyPeriodResultDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyPeriodResultEntity): Long

    @androidx.room.Query("SELECT * FROM daily_period_result WHERE trade_date = :date ORDER BY period_days")
    suspend fun getByDate(date: String): List<DailyPeriodResultEntity>

    @androidx.room.Query("SELECT * FROM daily_period_result WHERE strategy_id = :sid AND trade_date = :date")
    suspend fun getByStrategyAndDate(sid: String, date: String): List<DailyPeriodResultEntity>

    @androidx.room.Query("SELECT * FROM daily_period_result ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<DailyPeriodResultEntity>

    @androidx.room.Query("SELECT DISTINCT trade_date FROM daily_period_result ORDER BY trade_date DESC LIMIT :limit")
    suspend fun getAvailableDates(limit: Int = 30): List<String>

    @androidx.room.Query("DELETE FROM daily_period_result WHERE trade_date = :date")
    suspend fun deleteByDate(date: String)
}

@androidx.room.Dao
interface StrategyTradeFittingParamDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StrategyTradeFittingParamEntity): Long

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<StrategyTradeFittingParamEntity>)

    @androidx.room.Query("SELECT * FROM strategy_trade_fitting_params WHERE strategy_id = :sid AND trade_date = :date AND period_days = :period ORDER BY fitting_round ASC")
    suspend fun getByStrategyAndDate(sid: String, date: String, period: Int): List<StrategyTradeFittingParamEntity>

    @androidx.room.Query("SELECT * FROM strategy_trade_fitting_params WHERE strategy_id = :sid ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentByStrategy(sid: String, limit: Int = 100): List<StrategyTradeFittingParamEntity>

    @androidx.room.Query("SELECT MAX(accuracy) FROM strategy_trade_fitting_params WHERE strategy_id = :sid AND trade_date = :date AND period_days = :period")
    suspend fun getBestAccuracy(sid: String, date: String, period: Int): Double?

    @androidx.room.Query("DELETE FROM strategy_trade_fitting_params WHERE strategy_id = :sid AND trade_date = :date")
    suspend fun deleteByStrategyAndDate(sid: String, date: String)
}

@androidx.room.Dao
interface DailyNewsHotPickDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyNewsHotPickEntity): Long

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DailyNewsHotPickEntity>)

    @androidx.room.Query("SELECT * FROM daily_news_hot_picks WHERE news_date = :date ORDER BY rank ASC")
    suspend fun getByDate(date: String): List<DailyNewsHotPickEntity>

    @androidx.room.Query("SELECT * FROM daily_news_hot_picks WHERE news_date BETWEEN :fromDate AND :toDate ORDER BY news_date DESC, rank ASC")
    suspend fun getByDateRange(fromDate: String, toDate: String): List<DailyNewsHotPickEntity>

    @androidx.room.Query("SELECT DISTINCT news_date FROM daily_news_hot_picks ORDER BY news_date DESC LIMIT :limit")
    suspend fun getAvailableDates(limit: Int = 30): List<String>

    @androidx.room.Query("DELETE FROM daily_news_hot_picks WHERE news_date < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: String)
}

@androidx.room.Dao
interface StrategyTradeOrderDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StrategyTradeOrderEntity): Long

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<StrategyTradeOrderEntity>)

    @androidx.room.Query("SELECT * FROM strategy_trade_orders WHERE trade_date = :date ORDER BY score_at_buy DESC")
    suspend fun getByDate(date: String): List<StrategyTradeOrderEntity>

    @androidx.room.Query("SELECT * FROM strategy_trade_orders ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<StrategyTradeOrderEntity>

    @androidx.room.Query("SELECT * FROM strategy_trade_orders WHERE strategy_id = :sid ORDER BY created_at DESC LIMIT :limit")
    suspend fun getByStrategy(sid: String, limit: Int = 50): List<StrategyTradeOrderEntity>

    @androidx.room.Query("UPDATE strategy_trade_orders SET status = :status, sell_price = :sellPrice, sell_time = :sellTime, profit_pct = :profitPct WHERE id = :id")
    suspend fun updateSellInfo(id: Long, status: String, sellPrice: Double, sellTime: String, profitPct: Double)

    @androidx.room.Query("SELECT SUM(profit_pct) FROM strategy_trade_orders WHERE strategy_id = :sid AND status = 'SOLD'")
    suspend fun getTotalProfit(sid: String): Double?

    @androidx.room.Query("SELECT COUNT(*) FROM strategy_trade_orders WHERE strategy_id = :sid AND status = 'SOLD' AND profit_pct > 0")
    suspend fun getWinCount(sid: String): Int

    @androidx.room.Query("SELECT COUNT(*) FROM strategy_trade_orders WHERE strategy_id = :sid AND status = 'SOLD'")
    suspend fun getTotalSoldCount(sid: String): Int

    @androidx.room.Query("DELETE FROM strategy_trade_orders WHERE trade_date = :date")
    suspend fun deleteByDate(date: String)
}
