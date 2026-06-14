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
 * ## 模拟交易引擎 v2.0 — 9步精選流程 + 智能賣出
 */
class SimulationTradeEngine(private val context: Context) {

    companion object {
        private const val TAG = "SimTradeEngine"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val PERIOD_DAYS = listOf(1, 3, 10, 30, 50, 100)
        const val MAX_STOCKS_PER_STRATEGY = 15
        const val FINAL_TOP3 = 3
        const val CROSS_DAY_WINDOW = 5
        const val CROSS_DAY_TOP_N = 20
        const val ROTATION_THRESHOLD_DAYS = 3
        const val NEWS_WEIGHT_BOOST = 0.15
        const val ROTATION_PENALTY = 0.10
        const val MAX_HOLDINGS = 5  // 最大持仓数，超出时触发腾笼换鸟
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
        val crossDayRanking: List<Pair<String, Int>> = emptyList(),
        // ── 增强报告字段 ──
        val stepDetail: StepDetail = StepDetail(),
        val swapInfo: SwapInfo? = null
    )

    /** 每步詳細數據 */
    data class StepDetail(
        val basePoolSize: Int = 0,       // Step 1-4 底池
        val afterMainBoard: Int = 0,      // 主板過濾後
        val strategyHitCount: Int = 0,    // Step 5 命中策略數
        val crossDaySize: Int = 0,        // Step 6 跨日聚合
        val sectorAdded: Int = 0,         // Step 7 板塊新增
        val userAdded: Int = 0,           // Step 8 用戶+智能體新增
        val finalPoolSize: Int = 0,       // Step 9 最終池
        val buyFilterPassed: Int = 0,     // 買入過濾通過數
        val buyFilterRejects: List<String> = emptyList(), // 過濾拒絕明細
        val newBuyCodes: List<String> = emptyList(),  // 本次新買入
        val mergedCodes: List<String> = emptyList()   // 合併持倉
    )

    /** 騰籠換鳥信息 */
    data class SwapInfo(
        val beforeCount: Int,
        val soldCount: Int,
        val soldStocks: List<String>  // "股票名(原因)"
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

    data class FittingRoundParam(val round:Int,val paramJson:String,val accuracy:Float,val avgReturn:Double,val hitCount:Int,val totalSignals:Int)

    data class SellDecision(
        val order: StrategyTradeOrderEntity, val reason: String, val currentPrice: Double,
        val profitPct: Double, val shouldSell: Boolean, val strategy: String
    )

    // ══════════════════════════════════════════════════
    // 主入口：9步精選流程
    // ══════════════════════════════════════════════════

    suspend fun runTradeSession(
        strategies: List<Strategy>,
        config: TradeSessionConfig = TradeSessionConfig()
    ): TradeSessionReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "━━━ 9步精選模擬交易 ━━━")
        Log.i(TAG, "交易日: ${config.tradeDate}  主板: ${config.onlyMainBoard}  周期: ${config.periods}")
        Log.i(TAG, "策略数: ${strategies.size}  最大持仓: ${MAX_HOLDINGS}")

        val poolCodes = buildDailyStockPool(config.tradeDate)
        Log.i(TAG, "【Step 1-4】精選池: ${poolCodes.size} 隻")

        val allSnapshots = getTradingDayData(config.tradeDate)
        Log.i(TAG, "获取交易日数据: ${allSnapshots.size}只")
        if (allSnapshots.isEmpty()) {
            return@withContext TradeSessionReport(config, emptyList(), emptyList(), emptyList(),
                "交易日 ${config.tradeDate} 无可用数据")
        }
        // 统一数据层: 使用 StrategyDataFeed 构建 StockRealtime
        val dataFeed = com.chin.stockanalysis.strategy.data.StrategyDataFeed(context)
        val feedConfig = com.chin.stockanalysis.strategy.data.StrategyDataFeed.DataFeedConfig(
            onlyMainBoard = config.onlyMainBoard,
            stockCodes = poolCodes
        )
        var stockList = dataFeed.convertSnapshots(allSnapshots, feedConfig)
        Log.i(TAG, "統一數據層: ${allSnapshots.size}隻 → ${stockList.size}隻 (主板=${config.onlyMainBoard})")

        val heatScores = mutableMapOf<String, Int>()
        stockList.forEach { stock -> heatScores[stock.code] = StockDataCenter.calculateStockHeatScore(stock.code, allSnapshots) }
        stockList = stockList.sortedByDescending { heatScores[it.code] ?: 0 }

        // Step 5: 策略信號生成
        val allTodayResults = mutableListOf<StrategyPeriodResult>()
        for (strategy in strategies) {
            if (strategy.id == "ai_prediction") continue
            Log.d(TAG, "【Step 5】执行策略: ${strategy.name} 输入${stockList.size}只")
            val rawSignals = executeStrategy(strategy, stockList, 1) ?: continue
            if (rawSignals.isEmpty()) { Log.d(TAG, "  ${strategy.name}: 无信号"); continue }
            Log.d(TAG, "  ${strategy.name}: 原始信號${rawSignals.size}個")
            if (rawSignals.isEmpty()) continue
            // 🔥 買入前過濾 (Step 5内部，在主板过濾之前)
            val afterBuyFilter = applySignalFilter(rawSignals, allSnapshots)
            Log.d(TAG, "  ${strategy.name}: 4条过滤后${afterBuyFilter.size}個")
            val newsScore = calculateNewsStrength(afterBuyFilter, config.tradeDate)
            val rotationPenaltyScore = calculateRotationPenalty(afterBuyFilter, config.tradeDate)
            val (filtered, filteredInfo) = filterByMainBoard(afterBuyFilter, config.onlyMainBoard)
            val top15 = filtered.sortedByDescending { it.strength }.take(MAX_STOCKS_PER_STRATEGY)
            allTodayResults.add(StrategyPeriodResult(
                strategyId = strategy.id, strategyName = strategy.name,
                periodDays = 1, tradeDate = config.tradeDate,
                rawStockSignals = rawSignals.take(MAX_STOCKS_PER_STRATEGY),
                newsStrengthScore = newsScore, rotationPenalty = rotationPenaltyScore,
                afterMainBoardFilter = filtered.take(MAX_STOCKS_PER_STRATEGY),
                filteredStocks = filteredInfo.take(20), finalTop15 = top15
            ))
            Log.i(TAG, "  ${strategy.name}: 输出Top15 — ${top15.take(5).joinToString { "${it.stockName}(${it.strength}%)" }}")
            savePeriodResultToDb(strategy.id, strategy.name, 1, config.tradeDate, rawSignals, newsScore, rotationPenaltyScore,
                filtered.map { it.stockCode }, filteredInfo, top15)
        }
        Log.i(TAG, "【Step 5】${allTodayResults.size} 個策略命中，输入${stockList.size}只 → 输出策略结果")

        // Step 6: 跨日聚合 + 多周期热门股
        val crossDayTop20 = aggregateCrossDayResults(config.tradeDate, strategies, poolCodes)
        Log.i(TAG, "【Step 6a】跨${CROSS_DAY_WINDOW}天聚合 → Top${crossDayTop20.size}: ${crossDayTop20.take(8).joinToString { "${it.first.takeLast(6)}(${it.second}天)" }}")
        val hotStocks = addMultiPeriodHotStocks(config.tradeDate, config.onlyMainBoard)
        Log.i(TAG, "【Step 6b】多周期热门股: +${hotStocks.size}隻 — ${hotStocks.take(5).joinToString()}")

        // Step 7: 板块精选
        val sectorPicked = if (config.onlyMainBoard) getHotSectorStockPool().filter { isMainBoard(it) }.toSet() else getHotSectorStockPool()
        Log.i(TAG, "【Step 7】板塊精選: +${sectorPicked.size} 隻")

        // Step 8: 用户搜索/智能体
        val userStockCodes = if (config.onlyMainBoard) StockDataCenter.getUserSearchStockCodes().filter { isMainBoard(it) }.toSet() else StockDataCenter.getUserSearchStockCodes()
        val skillPickCodes = if (config.onlyMainBoard) StockDataCenter.getSkillPickStockCodes().filter { isMainBoard(it) }.toSet() else StockDataCenter.getSkillPickStockCodes()
        Log.i(TAG, "【Step 8】用戶搜索${userStockCodes.size}隻 + 智能體${skillPickCodes.size}隻")

        // Step 9: 组装+过滤
        val rawPool = (crossDayTop20.map { it.first }.toSet() + hotStocks + sectorPicked + userStockCodes + skillPickCodes).toSet()
        Log.i(TAG, "【Step 9a】rawPool: ${rawPool.size}隻")
        val finalPool = filterPoolCodes(rawPool, allSnapshots)
        val filteredOut = rawPool.size - finalPool.size
        if (filteredOut > 0) Log.i(TAG, "【Step 9b】買入過濾: 移除${filteredOut}隻不合格股 → 最終池 ${finalPool.size} 隻")
        else Log.i(TAG, "【Step 9b】最終AI輸入池: ${finalPool.size} 隻")

        // Step 9c: 新聞檢查
        val newsBlocked = checkNewsBeforeBuy(config.tradeDate, allSnapshots)
        if (newsBlocked.isNotEmpty()) Log.i(TAG, "【Step 9c】新聞攔截: ${newsBlocked.size}隻有重大利空 — ${newsBlocked.joinToString { "${it.stockName}(${it.reason})" }}")
        val safePool = finalPool.filter { code -> newsBlocked.none { it.code == code } }
        if (safePool.size < finalPool.size) Log.i(TAG, "【Step 9c】安全池: ${safePool.size}隻")

        val finalPoolSafe = safePool.toSet()
        val finalSnapshots = allSnapshots.filter { it.code in finalPoolSafe }
        val finalStockList = dataFeed.convertSnapshots(finalSnapshots, com.chin.stockanalysis.strategy.data.StrategyDataFeed.DataFeedConfig(onlyMainBoard = false))

        val aiStrategy = strategies.find { it.id == "ai_prediction" }
        val aiPicks = if (aiStrategy != null && aiStrategy is com.chin.stockanalysis.strategy.strategies.AIPredictionStrategy) {
            val screeningResults = mutableListOf<com.chin.stockanalysis.strategy.models.ScreeningResult>()
            for (strategy in strategies) {
                if (strategy.id == "ai_prediction") continue
                executeStrategy(strategy, finalStockList, 1)?.let { signals ->
                    screeningResults.add(com.chin.stockanalysis.strategy.models.ScreeningResult(
                        strategyId = strategy.id, strategyName = strategy.name,
                        category = strategy.category, signals = signals,
                        totalScanned = signals.size, scanTimeMs = 0))
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
        Log.i(TAG, "生成买入订单: ${buyOrders.size}只")
        saveDailyNewsHotPicks(config.tradeDate)
        runFitting(strategies, allTodayResults, config)
        saveFinalPoolToDb(config.tradeDate, finalPoolSafe.toList(), crossDayTop20)
        Log.i(TAG, "━━━ 9步精選完成 ━━━")

        val stepDetail = StepDetail(
            basePoolSize = poolCodes.size,
            afterMainBoard = stockList.size,
            strategyHitCount = allTodayResults.size,
            crossDaySize = crossDayTop20.size,
            sectorAdded = sectorPicked.size,
            userAdded = userStockCodes.size + skillPickCodes.size,
            finalPoolSize = finalPool.size,
            buyFilterPassed = 0,
            buyFilterRejects = emptyList(),
            newBuyCodes = buyOrders.map { "${it.stockName}(${it.stockCode.takeLast(6)})" },
            mergedCodes = emptyList()
        )
        val summary = buildEnhancedSummary(stepDetail, aiPicks, crossDayTop20, null)
        TradeSessionReport(config, allTodayResults, aiPicks, buyOrders, summary, finalPool.toList(), crossDayTop20, stepDetail, null)
    }

    private suspend fun saveFinalPoolToDb(tradeDate: String, codes: List<String>, crossDay: List<Pair<String, Int>>) {
        try {
            val top3Json = JSONArray(crossDay.take(10).map { (code, days) ->
                JSONObject().apply { put("code", code); put("days", days) }
            }).toString()
            db.dailyPeriodResultDao().insert(DailyPeriodResultEntity(
                strategyId = "FINAL_POOL", strategyName = "9步精選最終池",
                tradeDate = tradeDate, periodDays = 9,
                stockCodesJson = JSONArray(codes).toString(), stockCount = codes.size,
                newsStrengthScore = 0, rotationPenalty = 0, mainBoardFilter = true,
                filteredCodesJson = "[]", filteredReasonJson = "[]",
                finalTop3Json = top3Json, aiSelectionReason = "共${codes.size}隻精選股輸入AI",
                createdAt = System.currentTimeMillis()))
            Log.i(TAG, "💾 最終精選池已保存: ${codes.size} 隻")
        } catch (e: Exception) { Log.w(TAG, "保存最終池失敗: ${e.message}") }
    }

    private suspend fun aggregateCrossDayResults(baseDate: String, strategies: List<Strategy>, poolCodes: Set<String>): List<Pair<String, Int>> {
        val allDates = try {
            db.dailySnapshotDao().getAvailableDates(CROSS_DAY_WINDOW + 5).filter { it <= baseDate }.take(CROSS_DAY_WINDOW)
        } catch (_: Exception) { emptyList() }
        if (allDates.size < 2) { Log.w(TAG, "跨日聚合: 僅${allDates.size}天"); return emptyList() }
        val hitCounts = mutableMapOf<String, Int>()
        for (date in allDates) {
            try {
                val snaps = db.dailySnapshotDao().getByDate(date).filter { it.code in poolCodes }
                if (snaps.isEmpty()) continue
                val stockList = snaps.map { snap ->
                    StockRealtime(code=snap.code, name=snap.name, price=snap.close, open=snap.open,
                        yestClose=if(snap.changePct!=0.0&&snap.close!=0.0)snap.close/(1.0+snap.changePct/100.0) else snap.close,
                        high=snap.high, low=snap.low, volume=snap.volume, amount=snap.amount,
                        changePercent=snap.changePct, changeAmount=snap.close*snap.changePct/100, timestamp=System.currentTimeMillis())
                }
                val seenToday = mutableSetOf<String>()
                for (strategy in strategies) {
                    if (strategy.id == "ai_prediction") continue
                    executeStrategy(strategy, stockList, 1)?.filter { it.stockCode !in seenToday }?.forEach { signal ->
                        hitCounts[signal.stockCode] = (hitCounts[signal.stockCode]?:0) + 1; seenToday.add(signal.stockCode)
                    }
                }
            } catch (_: Exception) {}
        }
        return hitCounts.entries.sortedByDescending { it.value }.take(CROSS_DAY_TOP_N).map { (code, days) -> code to days }
    }

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
        val nextDayPriceMap = nextDayData.associate { it.code to it.close }
        val allPeriodResults = mutableListOf<StrategyPeriodResult>()
        for (strategy in strategies) for (period in config.periods) {
            val rawSignals = executeStrategy(strategy, stockList, period) ?: continue
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
            val nextPrice = nextDayPriceMap[code] ?: continue
            orderAnalysisList.add(BacktrackOrderAnalysis(code, snap.name, snap.close, nextPrice, (nextPrice-snap.close)/snap.close*100, (nextPrice-snap.close)/snap.close*100 > 0))
        }
        val optimizedList = mutableListOf<BacktrackOptimizedStrategy>()
        for (strategy in strategies) for (period in config.periods) {
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

    private suspend fun getTradingDayData(date: String): List<DailySnapshotEntity> {
        val local = try { db.dailySnapshotDao().getByDate(date) } catch (e: Exception) { emptyList() }
        if (local.size >= 100) return local
        val today = LocalDate.now().format(DATE_FMT)
        // 强制导入：如果已有数据太少，直接拉取全部历史数据
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
            } catch (_: Exception) { /* fallback to original logic */ }
        }
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
            val bestRound = fp.maxByOrNull{ it.accuracy } ?: continue
            val oldBest = getOldBestAccuracy(strategy.id, tradeDate, period) ?: 0f
            if (bestRound.accuracy > oldBest && bestRound.accuracy >= config.targetAccuracy) {
                try {
                    val bestParams = JSONObject(bestRound.paramJson)
                    for (factor in strategy.weightFactors) {
                        val newWeight = bestParams.optInt(factor.key, factor.weight)
                        strategy.weightFactors = strategy.weightFactors.map { f ->
                            if (f.key == factor.key) f.copy(weight = newWeight) else f
                        }
                    }
                    val today = LocalDate.now().format(DATE_FMT)
                    val weightJson = strategy.weightFactors.joinToString(";") { "${it.key}:${it.label}:${it.weight}" }
                    db.strategyWeightSnapshotDao().insert(
                        com.chin.stockanalysis.strategy.backtest.StrategyWeightSnapshotEntity(
                            strategyId = strategy.id, date = today, weightJson = weightJson, totalScore = 0, hitCount = bestRound.hitCount))
                    Log.i(TAG, "🔧 统一调优: ${strategy.name} ${"%.1f".format(oldBest*100)}% → ${"%.1f".format(bestRound.accuracy*100)}%")
                } catch (e: Exception) { Log.w(TAG, "更新共享权重失败: ${e.message}") }
            }
        }
    }

    private suspend fun validateParams(result: StrategyPeriodResult, nextDayData: List<DailySnapshotEntity>, round: Int): FittingRoundParam {
        var hit=0; var totalRet=0.0
        for(signal in result.finalTop15){ val nd=nextDayData.find{it.code==signal.stockCode}; if(nd!=null){ if(nd.changePct>0)hit++; totalRet+=nd.changePct } }
        return FittingRoundParam(round,"{\"round\":$round}",if(result.finalTop15.size>0)hit.toFloat()/result.finalTop15.size else 0f, if(result.finalTop15.size>0)totalRet/result.finalTop15.size else 0.0, hit, result.finalTop15.size)
    }

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

    // ══════════════════════════════════════════════════
    // 智能賣出 (委托给 AutoSellEngine)
    // ══════════════════════════════════════════════════

    suspend fun runAutoSellEvaluate(strategies: List<Strategy>, today: String = LocalDate.now().format(DATE_FMT)): List<AutoSellEngine.SellDecision> {
        val sellEngine = AutoSellEngine(context)
        return sellEngine.evaluateAll(strategies, AutoSellEngine.AutoSellConfig(tradeDate = today))
    }

    suspend fun runAutoSell(strategies: List<Strategy>, today: String = LocalDate.now().format(DATE_FMT)): List<AutoSellEngine.SellDecision> {
        val sellEngine = AutoSellEngine(context)
        val decisions = sellEngine.evaluateAll(strategies, AutoSellEngine.AutoSellConfig(tradeDate = today))
        sellEngine.executeSells(decisions, today)
        return decisions
    }

    suspend fun getSellPerformance(days: Int = 90): List<AutoSellEngine.SellPerformanceStats> {
        val sellEngine = AutoSellEngine(context)
        return sellEngine.getSellPerformance(days)
    }

    /**
     * 腾笼换鸟：持仓超限时，评估所有持仓，自动卖出最弱的，为新买入腾出仓位。
     * @param strategies 当前启用的策略列表
     * @param newBuyCount 本次要新买入的股票数量
     * @param today 当前交易日
     * @return 被卖出的股票数量
     */
    suspend fun swapWeakHoldings(strategies: List<Strategy>, newBuyCount: Int, today: String = LocalDate.now().format(DATE_FMT)): Int = withContext(Dispatchers.IO) {
        val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
            .filter { it.status == "BUYING" || it.status == "PENDING" }
        val currentCount = holdingOrders.size
        val maxAllowed = MAX_HOLDINGS - newBuyCount
        if (currentCount <= maxAllowed) return@withContext 0

        val needToSell = currentCount - maxAllowed
        Log.i(TAG, "🔄 腾笼换鸟: 当前${currentCount}只持仓, 需要为新买入腾出${needToSell}个位置")

        // 评估所有持仓
        val sellEngine = AutoSellEngine(context)
        val allDecisions = sellEngine.evaluateAll(strategies, AutoSellEngine.AutoSellConfig(tradeDate = today))

        // 优先卖出：1) 已触发卖出的优先，2) 按紧急度降序，3) 同紧急度选亏损最多的
        val rankedForSale = allDecisions
            .sortedWith(compareByDescending<AutoSellEngine.SellDecision> { it.shouldSell }
                .thenByDescending { it.urgency }
                .thenBy { it.profitPct })
            .take(needToSell)

        if (rankedForSale.isNotEmpty()) {
            Log.i(TAG, "🔴 自动换股卖出: ${rankedForSale.joinToString { "${it.order.stockName}(${it.strategy})" }}")
            sellEngine.executeSells(rankedForSale, today)
        }
        rankedForSale.size
    }

    /**
     * 買入前過濾 — 4條規則
     * 1. 大阴线不抄: 当日/昨收跌幅 ≥ 7% → 拒绝
     * 2. 均线空头不搞: MA5 < MA20 → 拒绝
     * 3. 一字开板莫跳: 开盘价 = 涨停价(10%) → 拒绝
     * 4. 顶背离不追: 价格新高但RSI不新高 → 拒绝
     * @return Pair(通过列表, 拒绝原因列表)
     */
    /**
     * Step 5 内部过滤 — 工作在策略信号上, 而非AI推荐结果
     * 过滤掉明显垃圾信号, 提高后续步骤质量
     */
    private fun applySignalFilter(signals: List<StrategySignal>, allSnapshots: List<DailySnapshotEntity>): List<StrategySignal> {
        val snapMap = allSnapshots.associateBy { it.code }
        var removed = 0

        val result = signals.filter { signal ->
            val snap = snapMap[signal.stockCode] ?: return@filter true
            var keep = true

            // ① 大阴线不抄: 当日跌幅 ≥ 7%
            if (snap.changePct <= -7.0) keep = false

            // ② 一字开板莫跳: 涨停板一字板
            if (snap.changePct >= 9.5 && snap.open >= snap.close * 0.99) keep = false

            // ③ 均线空头不搞
            if (snap.close < snap.open && snap.changePct < -2.0) {
                val recent = allSnapshots.filter { it.code == snap.code }
                    .sortedByDescending { it.date }.take(5).map { it.close }
                if (recent.size >= 5 && recent.take(3).average() < recent.average()) keep = false
            }

            // ④ 顶背离不去追
            if (snap.changePct in 0.0..1.5 && snap.high > snap.open * 1.02) {
                val recentDates = allSnapshots.filter { it.code == snap.code }
                    .sortedByDescending { it.date }
                if (recentDates.size >= 4 && recentDates.take(4).count { it.changePct > 0 } >= 3) keep = false
            }

            if (!keep) removed++
            keep
        }

        if (removed > 0) Log.i(TAG, "🔥 Step5内部过滤: 移除${removed}个信号")
        return result
    }

    /**
     * Step 9 最终池过滤 — 对代码集合应用4条规则
     */
    private fun filterPoolCodes(codes: Set<String>, allSnapshots: List<DailySnapshotEntity>): Set<String> {
        val snapMap = allSnapshots.associateBy { it.code }
        return codes.filter { code ->
            val snap = snapMap[code] ?: return@filter true // 无数据不拦截
            // ① 大阴线不抄
            if (snap.changePct <= -7.0) return@filter false
            // ② 一字开板莫跳
            if (snap.changePct >= 9.5 && snap.open >= snap.close * 0.99) return@filter false
            // ③ 均线空头
            if (snap.close < snap.open && snap.changePct < -2.0) {
                val recent = allSnapshots.filter { it.code == code }
                    .sortedByDescending { it.date }.take(5).map { it.close }
                if (recent.size >= 5 && recent.take(3).average() < recent.average()) return@filter false
            }
            // ④ 顶背离
            if (snap.changePct in 0.0..1.5 && snap.high > snap.open * 1.02) {
                val recentDates = allSnapshots.filter { it.code == code }
                    .sortedByDescending { it.date }
                if (recentDates.size >= 4 && recentDates.take(4).count { it.changePct > 0 } >= 3) return@filter false
            }
            true
        }.toSet()
    }

    /** Step 6b: 多周期热门股 — 近3/5/10/30/50/100天各Top3涨幅股去重 */
    private suspend fun addMultiPeriodHotStocks(tradeDate: String, onlyMainBoard: Boolean = true): Set<String> {
        val periods = listOf(3, 5, 10, 30, 50, 100)
        val allDates = try { db.dailySnapshotDao().getAvailableDates(120).sorted() } catch (_: Exception) { emptyList() }
        val hotSet = mutableSetOf<String>()
        for (days in periods) {
            val startIdx = allDates.indexOf(tradeDate) - days
            if (startIdx < 0) continue
            val slices = allDates.subList(startIdx.coerceAtLeast(0), allDates.size)
            val sliceMap = mutableMapOf<String, Double>()
            for (date in slices) {
                try {
                    val snaps = db.dailySnapshotDao().getByDate(date)
                    for (snap in snaps) {
                        if (onlyMainBoard && !isMainBoard(snap.code)) continue
                        sliceMap[snap.code] = (sliceMap[snap.code] ?: 0.0) + snap.changePct
                    }
                } catch (_: Exception) { continue }
            }
            sliceMap.entries.sortedByDescending { it.value }.take(3).mapTo(hotSet) { it.key }
        }
        return hotSet
    }

    /** Step 9c: 买入前新闻拦截 — 高位负面消息拒绝买入 */
    data class NewsBlockedStock(val code: String, val stockName: String, val reason: String)
    private suspend fun checkNewsBeforeBuy(tradeDate: String, allSnapshots: List<DailySnapshotEntity>): List<NewsBlockedStock> {
        val fromDate = try { LocalDate.parse(tradeDate).minusDays(3).format(DATE_FMT) } catch (_: Exception) { tradeDate }
        val newsList = try { db.newsFactorDao().getActiveByDateRange(fromDate, tradeDate) } catch (_: Exception) { emptyList() }
        if (newsList.isEmpty()) return emptyList()
        val snapMap = allSnapshots.associateBy { it.code }
        val blocked = mutableListOf<NewsBlockedStock>()
        for (news in newsList) {
            if (news.stockCode.isBlank()) continue
            if (news.impactStrength >= 75 && news.sentiment < -30) {
                val snap = snapMap[news.stockCode] ?: continue
                blocked.add(NewsBlockedStock(news.stockCode, snap.name, "${news.title.take(40)}"))
            }
        }
        return blocked
    }

    /** 自动拟合 — 遍历最近 N 个交易日，验证每个策略的预测准确率并优化权重 */
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
                val rawSignals = executeStrategy(strategy, stockList, 1) ?: continue
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

    fun buildEnhancedSummary(detail: StepDetail, aiPicks: List<AIPick>, crossDay: List<Pair<String,Int>>, swapInfo: SimulationTradeEngine.SwapInfo?): String = buildString {
        appendLine("📊 9步精選模擬交易報告")
        appendLine(); appendLine("📋 流程統計:")
        appendLine("  Step 1-4: 精選池 ${detail.basePoolSize} 隻 → 主板過濾後 ${detail.afterMainBoard} 隻")
        appendLine("  Step 5:   ${detail.strategyHitCount} 個策略命中, 各輸出 Top${MAX_STOCKS_PER_STRATEGY}")
        appendLine("  Step 6:   跨${CROSS_DAY_WINDOW}天聚合 → 前${detail.crossDaySize}隻")
        appendLine("  Step 7:   板塊精選 +${detail.sectorAdded} 隻")
        appendLine("  Step 8:   用戶搜索/智能體 +${detail.userAdded} 隻 → 最終池 ${detail.finalPoolSize} 隻")
        appendLine("  Step 9:   AI預測 Top${FINAL_TOP3}"); appendLine()

        if (detail.newBuyCodes.isNotEmpty()) {
            appendLine("🟢 本次買入 ${detail.newBuyCodes.size} 隻:")
            detail.newBuyCodes.forEach { appendLine("  ✚ $it") }
            appendLine()
        }
        if (detail.mergedCodes.isNotEmpty()) {
            appendLine("🔄 合併持倉 ${detail.mergedCodes.size} 筆:")
            detail.mergedCodes.forEach { appendLine("  → $it") }
            appendLine()
        }
        if (swapInfo != null && swapInfo.soldCount > 0) {
            appendLine("🔴 騰籠換鳥: 持倉 ${swapInfo.beforeCount}隻 → 賣出 ${swapInfo.soldCount}隻")
            swapInfo.soldStocks.forEach { appendLine("  ✕ $it") }
            appendLine()
        }

        appendLine("🤖 AI最終推薦 Top3:")
        for (pick in aiPicks) appendLine("  #${pick.rank} ${pick.stockName}(${pick.stockCode.takeLast(6)}) 評分:${pick.compositeScore} ${pick.actionSuggestion}")
    }
}

// ══════════════════════════════════════════════════
// Room Entity
// ══════════════════════════════════════════════════

@androidx.room.Entity(tableName = "daily_period_result", indices = [androidx.room.Index(value = ["strategy_id", "trade_date", "period_days"], unique = true)])
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

@androidx.room.Entity(tableName = "strategy_trade_fitting_params", indices = [androidx.room.Index(value = ["strategy_id", "trade_date", "period_days", "fitting_round"])])
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

@androidx.room.Entity(tableName = "daily_news_hot_picks", indices = [androidx.room.Index(value = ["news_date", "rank"], unique = true)])
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

@androidx.room.Entity(tableName = "strategy_trade_orders", indices = [androidx.room.Index(value = ["strategy_id", "trade_date"]), androidx.room.Index(value = ["stock_code", "trade_date"])])
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
// DAO
// ══════════════════════════════════════════════════

@androidx.room.Dao
interface DailyPeriodResultDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyPeriodResultEntity): Long
    @androidx.room.Query("SELECT * FROM daily_period_result WHERE trade_date = :date ORDER BY period_days")
    suspend fun getByDate(date: String): List<DailyPeriodResultEntity>
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
    @androidx.room.Query("SELECT * FROM strategy_trade_fitting_params WHERE strategy_id = :sid ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentByStrategy(sid: String, limit: Int = 100): List<StrategyTradeFittingParamEntity>
    @androidx.room.Query("SELECT MAX(accuracy) FROM strategy_trade_fitting_params WHERE strategy_id = :sid AND trade_date = :date AND period_days = :period")
    suspend fun getBestAccuracy(sid: String, date: String, period: Int): Double?
}

@androidx.room.Dao
interface DailyNewsHotPickDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyNewsHotPickEntity): Long
    @androidx.room.Query("SELECT * FROM daily_news_hot_picks WHERE news_date = :date ORDER BY rank ASC")
    suspend fun getByDate(date: String): List<DailyNewsHotPickEntity>
    @androidx.room.Query("SELECT DISTINCT news_date FROM daily_news_hot_picks ORDER BY news_date DESC LIMIT 100")
    suspend fun getAvailableDates(): List<String>
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
    @androidx.room.Query("UPDATE strategy_trade_orders SET quantity = :quantity WHERE id = :id")
    suspend fun updateQuantity(id: Long, quantity: Int)
    @androidx.room.Query("UPDATE strategy_trade_orders SET buy_price = :price, quantity = :qty, trade_date = :date WHERE id = :id")
    suspend fun updateBuyPriceAndQty(id: Long, price: Double, qty: Int, date: String)
}
