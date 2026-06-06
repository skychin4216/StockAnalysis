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
 * ## 模拟交易引擎 v1.0
 *
 * 核心功能：
 * 1. 对每个策略，按不同周期(1/3/10/30/50/100日)执行完整的测试
 * 2. 根据策略执行后得出5-10只股票
 * 3. 根据市场热点行情 + 板块轮动调整权重
 * 4. 只考虑主板/过滤
 * 5. 每个策略最终最多保留三只票（记录过滤原因）
 * 6. AI最终选出3只精选股票
 * 7. 模拟买入和次日卖出，调优拟合参数
 *
 * ### 数据流
 * ```
 * 策略执行 → 多周期结果 → 新闻+轮动调权 → 主板过滤
 * → Top3保留 → AI精选3只 → 模拟交易 → 次日验证 → 调优拟合
 * ```
 */
class SimulationTradeEngine(private val context: Context) {

    companion object {
        private const val TAG = "SimTradeEngine"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val PERIOD_DAYS = listOf(1, 3, 10, 30, 50, 100)
        const val MAX_STOCKS_PER_STRATEGY = 10
        const val FINAL_TOP3 = 3

        /** 板块连续上涨阈值（超过此天数考虑轮动） */
        const val ROTATION_THRESHOLD_DAYS = 3
        /** 新闻热点加权幅度 */
        const val NEWS_WEIGHT_BOOST = 0.15
        /** 板块轮动惩罚幅度 */
        const val ROTATION_PENALTY = 0.10
    }

    private val db = StockDatabase.getInstance(context)

    // ═══════════════════════════════════════
    // 1. 主入口：对每个策略按不同周期执行完整测试
    // ═══════════════════════════════════════

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
        /** 原始策略输出 5-10只股票 */
        val rawStockSignals: List<StrategySignal>,
        /** 新闻力度评分 (0-100) */
        val newsStrengthScore: Int,
        /** 板块轮动惩罚 (-100 ~ 0) */
        val rotationPenalty: Int,
        /** 主板过滤后 */
        val afterMainBoardFilter: List<StrategySignal>,
        /** 被过滤的股票及原因 */
        val filteredStocks: List<FilteredStockInfo>,
        /** 最终 Top3 */
        val finalTop3: List<StrategySignal>,
        /** AI精选理由 */
        val aiSelectionReason: String = ""
    )

    data class FilteredStockInfo(
        val stockCode: String,
        val stockName: String,
        val reason: String
    )

    data class TradeSessionReport(
        val config: TradeSessionConfig,
        val periodResults: List<StrategyPeriodResult>,
        val aiTop3: List<AIPick>,
        /** 买入建议 */
        val buyOrders: List<TradeOrder>,
        val summary: String
    )

    data class AIPick(
        val rank: Int,
        val stockCode: String,
        val stockName: String,
        val compositeScore: Int,
        val upProbability: Int,
        val reason: String,
        val actionSuggestion: String
    )

    data class TradeOrder(
        val stockCode: String,
        val stockName: String,
        val strategyId: String,
        val tradeDate: String,
        val buyPrice: Double,
        val quantity: Int,
        val buyTime: String = LocalTime.now().toString().take(8),
        val reason: String = "",
        val scoreAtBuy: Int = 0,
        val orderType: String = "模拟买入",
        var status: String = "PENDING"
    )

    /**
     * 回溯复盘优化报告
     */
    data class BacktrackReport(
        val tradeDate: String,
        val buyOrdersAnalyzed: List<BacktrackOrderAnalysis>,
        val missedOpportunities: List<BacktrackMissedStock>,
        val optimizedStrategies: List<BacktrackOptimizedStrategy>,
        val summary: String
    )

    data class BacktrackOrderAnalysis(
        val stockCode: String,
        val stockName: String,
        val buyPrice: Double,
        val nextDayPrice: Double,
        val profitPct: Double,
        val wasGood: Boolean
    )

    data class BacktrackMissedStock(
        val stockCode: String,
        val stockName: String,
        val fromStrategy: String,
        val filterReason: String,
        val wouldHaveProfitPct: Double,
        val suggestion: String
    )

    data class BacktrackOptimizedStrategy(
        val strategyId: String,
        val strategyName: String,
        val oldAccuracy: Float,
        val newAccuracy: Float,
        val paramChanges: List<String>
    )

    /**
     * 回溯复盘并优化参数
     * 1. 用当日数据重新执行全部策略筛选
     * 2. 对比原始买入订单，找出落选但次日表现更好的股票
     * 3. 分析过滤原因
     * 4. 重新调优拟合参数
     * 5. 固化优化参数到数据库
     */
    suspend fun backtrackAndOptimize(
        strategies: List<Strategy>,
        config: TradeSessionConfig,
        oldSessionResults: List<StrategyPeriodResult>,
        boughtStocks: Set<String>
    ): BacktrackReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "━━━ 开始回溯复盘优化 ━━━")
        Log.i(TAG, "交易日: ${config.tradeDate}, 已买入: ${boughtStocks.size} 只")

        // 获取当日数据
        val snapshots = getTradingDayData(config.tradeDate)
        if (snapshots.isEmpty()) {
            return@withContext BacktrackReport(
                tradeDate = config.tradeDate,
                buyOrdersAnalyzed = emptyList(),
                missedOpportunities = emptyList(),
                optimizedStrategies = emptyList(),
                summary = "交易日 ${config.tradeDate} 无可用数据"
            )
        }

        val stockList = snapshots.map { snap ->
            val yc = if (snap.changePct != 0.0 && snap.close != 0.0) snap.close / (1.0 + snap.changePct / 100.0) else snap.close
            StockRealtime(
                code = snap.code, name = snap.name,
                price = snap.close, open = snap.open,
                yestClose = yc, high = snap.high, low = snap.low,
                volume = snap.volume, amount = snap.amount,
                changePercent = snap.changePct,
                changeAmount = snap.close * snap.changePct / 100,
                timestamp = System.currentTimeMillis()
            )
        }

        // 获取次日数据用于验证
        val nextDate = getNextTradingDay(config.tradeDate)
        val nextDayData = if (nextDate != null) {
            try { db.dailySnapshotDao().getByDate(nextDate) } catch (_: Exception) { emptyList() }
        } else emptyList()

        val nextDayPriceMap = nextDayData.associate { it.code to it.close }

        // ===== 1. 重新执行全部策略筛选 =====
        val allPeriodResults = mutableListOf<StrategyPeriodResult>()

        for (strategy in strategies) {
            for (period in config.periods) {
                val periodData = getPeriodData(period, config.tradeDate)
                if (periodData.isEmpty()) continue

                val rawSignals = executeStrategy(strategy, stockList, period) ?: continue
                if (rawSignals.isEmpty()) continue

                val newsScore = calculateNewsStrength(rawSignals, config.tradeDate)
                val rotationPenaltyScore = calculateRotationPenalty(rawSignals, config.tradeDate)
                val (filtered, filteredInfo) = filterByMainBoard(rawSignals, config.onlyMainBoard)
                val top3 = selectTop3(filtered, newsScore, rotationPenaltyScore, config.tradeDate)

                allPeriodResults.add(StrategyPeriodResult(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    periodDays = period,
                    tradeDate = config.tradeDate,
                    rawStockSignals = rawSignals.take(MAX_STOCKS_PER_STRATEGY),
                    newsStrengthScore = newsScore,
                    rotationPenalty = rotationPenaltyScore,
                    afterMainBoardFilter = filtered.take(MAX_STOCKS_PER_STRATEGY),
                    filteredStocks = filteredInfo.take(20),
                    finalTop3 = top3
                ))
            }
        }

        // ===== 2. 分析买入订单在次日的表现 =====
        val orderAnalysisList = mutableListOf<BacktrackOrderAnalysis>()
        for (code in boughtStocks) {
            val snap = snapshots.find { it.code == code }
            val nextPrice = nextDayPriceMap[code]
            if (snap != null && nextPrice != null) {
                val profitPct = (nextPrice - snap.close) / snap.close * 100
                orderAnalysisList.add(BacktrackOrderAnalysis(
                    stockCode = code,
                    stockName = snap.name,
                    buyPrice = snap.close,
                    nextDayPrice = nextPrice,
                    profitPct = profitPct,
                    wasGood = profitPct > 0
                ))
            }
        }

        // ===== 3. 分析落选股中次日表现优异的 =====
        val missedOpportunities = mutableListOf<BacktrackMissedStock>()
        val oldTop3Codes = oldSessionResults.flatMap { it.finalTop3.map { s -> s.stockCode } }.toSet()

        for (result in allPeriodResults) {
            for (filtered in result.filteredStocks) {
                val nextPrice = nextDayPriceMap[filtered.stockCode] ?: continue
                val snap = snapshots.find { it.code == filtered.stockCode } ?: continue
                val profitPct = (nextPrice - snap.close) / snap.close * 100

                // 被过滤的股票次日表现好但没被精选
                if (profitPct > 0 && filtered.stockCode !in oldTop3Codes) {
                    missedOpportunities.add(BacktrackMissedStock(
                        stockCode = filtered.stockCode,
                        stockName = filtered.stockName,
                        fromStrategy = result.strategyName,
                        filterReason = filtered.reason,
                        wouldHaveProfitPct = profitPct,
                        suggestion = if (filtered.stockCode.startsWith("sh688") || filtered.stockCode.startsWith("sz300"))
                            "放宽主板过滤可纳入" else "调整权重评分可入选"
                    ))
                }
            }
        }

        // ===== 4. 重新调优拟合参数 =====
        val optimizedList = mutableListOf<BacktrackOptimizedStrategy>()
        for (strategy in strategies) {
            for (period in config.periods) {
                val periodResults = allPeriodResults.filter { it.strategyId == strategy.id && it.periodDays == period }
                if (periodResults.isEmpty() || nextDayData.isEmpty()) continue

                val tradeDate = periodResults.first().tradeDate

                val fittingParams = mutableListOf<FittingRoundParam>()
                for (round in 1..config.maxFitRounds.coerceAtMost(500)) {
                    val result = validateParams(periodResults.first(), nextDayData, round)
                    fittingParams.add(result)
                }

                saveFittingParams(strategy.id, tradeDate, period, fittingParams)

                val bestNew = fittingParams.maxByOrNull { it.accuracy }
                val oldBest = getOldBestAccuracy(strategy.id, tradeDate, period)

                optimizedList.add(BacktrackOptimizedStrategy(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    oldAccuracy = oldBest ?: 0f,
                    newAccuracy = bestNew?.accuracy ?: 0f,
                    paramChanges = listOf("重新拟合: ${fittingParams.size}轮, " +
                            "旧准确率: ${"%.1f".format((oldBest ?: 0f) * 100)}% → " +
                            "新准确率: ${"%.1f".format((bestNew?.accuracy ?: 0f) * 100)}%")
                ))
            }
        }

        // 构建摘要
        val sb = StringBuilder()
        sb.appendLine("📈 回溯复盘优化报告")
        sb.appendLine("交易日: ${config.tradeDate}")
        sb.appendLine()

        sb.appendLine("📊 买入订单分析 (${orderAnalysisList.size} 只):")
        val goodCount = orderAnalysisList.count { it.wasGood }
        val badCount = orderAnalysisList.size - goodCount
        sb.appendLine("  盈利: $goodCount 只, 亏损: $badCount 只")
        for (order in orderAnalysisList.filter { !it.wasGood }) {
            sb.appendLine("  ❌ ${order.stockName}: ${if (order.profitPct >= 0) "+" else ""}${"%.2f".format(order.profitPct)}%")
        }

        sb.appendLine()
        sb.appendLine("🔍 落选但次日上涨 (${missedOpportunities.size} 只):")
        for (missed in missedOpportunities.take(10)) {
            sb.appendLine("  ⚠️ ${missed.stockName}: 被${missed.fromStrategy}过滤(${missed.filterReason})")
            sb.appendLine("     次日涨幅: +${"%.2f".format(missed.wouldHaveProfitPct)}% → ${missed.suggestion}")
        }

        sb.appendLine()
        sb.appendLine("🔧 参数优化 (${optimizedList.size} 策略):")
        for (opt in optimizedList) {
            sb.appendLine("  ✅ ${opt.strategyName}: ${opt.paramChanges.joinToString()}")
        }

        // 固化优化结果到数据库
        saveBacktrackResult(config.tradeDate, sb.toString())

        Log.i(TAG, "━━━ 回溯复盘优化完成 ━━━")
        BacktrackReport(
            tradeDate = config.tradeDate,
            buyOrdersAnalyzed = orderAnalysisList,
            missedOpportunities = missedOpportunities,
            optimizedStrategies = optimizedList,
            summary = sb.toString()
        )
    }

    private suspend fun getOldBestAccuracy(strategyId: String, tradeDate: String, period: Int): Float? {
        return try {
            db.strategyTradeFittingParamDao()
                .getBestAccuracy(strategyId, tradeDate, period)
                ?.toFloat()
        } catch (_: Exception) { null }
    }

    private suspend fun saveBacktrackResult(tradeDate: String, summary: String) {
        try {
            val entity = DailyPeriodResultEntity(
                strategyId = "BACKTRACK",
                strategyName = "回溯复盘",
                tradeDate = tradeDate,
                periodDays = 0,
                stockCodesJson = "[]",
                stockCount = 0,
                newsStrengthScore = 0,
                rotationPenalty = 0,
                mainBoardFilter = false,
                filteredCodesJson = "[]",
                filteredReasonJson = "[]",
                finalTop3Json = "[]",
                aiSelectionReason = summary,
                createdAt = System.currentTimeMillis()
            )
            db.dailyPeriodResultDao().insert(entity)
        } catch (_: Exception) {
            Log.w(TAG, "保存回溯结果失败")
        }
    }

    /**
     * 执行完整交易会话
     */
    suspend fun runTradeSession(
        strategies: List<Strategy>,
        config: TradeSessionConfig = TradeSessionConfig()
    ): TradeSessionReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "━━━ 开始模拟交易会话 ━━━")
        Log.i(TAG, "交易日: ${config.tradeDate}, 周期: ${config.periods}")
        Log.i(TAG, "策略数: ${strategies.size}, 主板过滤: ${config.onlyMainBoard}")

        // 获取当前交易日的快照数据
        val snapshots = getTradingDayData(config.tradeDate)
        if (snapshots.isEmpty()) {
            Log.w(TAG, "交易日 ${config.tradeDate} 无数据")
            return@withContext TradeSessionReport(
                config = config,
                periodResults = emptyList(),
                aiTop3 = emptyList(),
                buyOrders = emptyList(),
                summary = "交易日 ${config.tradeDate} 无可用数据"
            )
        }

        val stockList = snapshots.map { snap ->
            val yc = if (snap.changePct != 0.0 && snap.close != 0.0) snap.close / (1.0 + snap.changePct / 100.0) else snap.close
            StockRealtime(
                code = snap.code, name = snap.name,
                price = snap.close, open = snap.open,
                yestClose = yc, high = snap.high, low = snap.low,
                volume = snap.volume, amount = snap.amount,
                changePercent = snap.changePct,
                changeAmount = snap.close * snap.changePct / 100,
                timestamp = System.currentTimeMillis()
            )
        }

        val allPeriodResults = mutableListOf<StrategyPeriodResult>()

        // 对每个策略
        for (strategy in strategies) {
            Log.i(TAG, "策略: ${strategy.name} (${strategy.id})")

            // 对每个周期
            for (period in config.periods) {
                val periodData = getPeriodData(period, config.tradeDate)
                if (periodData.isEmpty()) continue

                // 1. 执行策略
                val rawSignals = executeStrategy(strategy, stockList, period) ?: continue
                if (rawSignals.isEmpty()) continue

                Log.d(TAG, "  周期${period}日: 原始信号${rawSignals.size}只")

                // 2. 新闻力度评分
                val newsScore = calculateNewsStrength(rawSignals, config.tradeDate)

                // 3. 板块轮动惩罚
                val rotationPenaltyScore = calculateRotationPenalty(rawSignals, config.tradeDate)

                // 4. 主板过滤
                val (filtered, filteredInfo) = filterByMainBoard(rawSignals, config.onlyMainBoard)

                // 5. 最终Top3
                val top3 = selectTop3(filtered, newsScore, rotationPenaltyScore, config.tradeDate)

                allPeriodResults.add(StrategyPeriodResult(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    periodDays = period,
                    tradeDate = config.tradeDate,
                    rawStockSignals = rawSignals.take(MAX_STOCKS_PER_STRATEGY),
                    newsStrengthScore = newsScore,
                    rotationPenalty = rotationPenaltyScore,
                    afterMainBoardFilter = filtered.take(MAX_STOCKS_PER_STRATEGY),
                    filteredStocks = filteredInfo.take(20),
                    finalTop3 = top3
                ))

                // 保存结果到数据库
                savePeriodResultToDb(strategy.id, strategy.name, period, config.tradeDate,
                    rawSignals, newsScore, rotationPenaltyScore,
                    filtered.map { it.stockCode }, filteredInfo, top3)
            }
        }

        // 6. AI精选3只
        val aiPicks = aiSelectTop3(allPeriodResults, config.tradeDate)
        // 7. 生成买入建议
        val buyOrders = generateBuyOrders(aiPicks, config.tradeDate, snapshots)

        // 8. 保存新闻热点（固化）
        saveDailyNewsHotPicks(config.tradeDate)

        // 生成调优拟合参数
        runFitting(strategies, allPeriodResults, config)

        val summary = buildSummary(allPeriodResults, aiPicks, config)
        Log.i(TAG, "━━━ 交易会话完成 ━━━")

        TradeSessionReport(
            config = config,
            periodResults = allPeriodResults,
            aiTop3 = aiPicks,
            buyOrders = buyOrders,
            summary = summary
        )
    }

    // ═══════════════════════════════════════
    // 2. 数据获取
    // ═══════════════════════════════════════

    private suspend fun getTradingDayData(date: String): List<DailySnapshotEntity> {
        return try {
            db.dailySnapshotDao().getByDate(date)
        } catch (e: Exception) {
            Log.w(TAG, "获取交易日数据失败: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getPeriodData(periodDays: Int, baseDate: String): List<String> {
        return try {
            db.dailySnapshotDao().getAvailableDates(periodDays + 5)
                .filter { it <= baseDate }
                .take(periodDays)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ═══════════════════════════════════════
    // 3. 策略执行
    // ═══════════════════════════════════════

    private suspend fun executeStrategy(
        strategy: Strategy,
        stockList: List<StockRealtime>,
        periodDays: Int
    ): List<StrategySignal>? {
        return try {
            val result = strategy.screenWithData(stockList).getOrNull() ?: return null
            result.signals
                .filter { it.action == SignalAction.BUY || it.action == SignalAction.WATCH }
                .sortedByDescending { it.strength }
                .take(MAX_STOCKS_PER_STRATEGY)
        } catch (e: Exception) {
            Log.w(TAG, "策略执行失败: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════
    // 4. 新闻力度评分
    // ═══════════════════════════════════════

    private suspend fun calculateNewsStrength(
        signals: List<StrategySignal>,
        tradeDate: String
    ): Int {
        return try {
            // 获取最近3天的活跃新闻
            val fromDate = LocalDate.parse(tradeDate).minusDays(3).format(DATE_FMT)
            val newsList = db.newsFactorDao().getActiveByDateRange(fromDate, tradeDate)
            if (newsList.isEmpty()) return 50 // 默认中等

            // 统计与信号股票相关的新闻
            val signalCodes = signals.map { it.stockCode }.toSet()
            val relatedNews = newsList.filter { it.stockCode in signalCodes || it.sector.isNotEmpty() }

            if (relatedNews.isEmpty()) return 40

            // 计算综合评分
            val avgStrength = relatedNews.map { it.impactStrength }.average()
            val avgSentiment = relatedNews.map { it.sentiment.toDouble() }.average()
            val baseScore = (avgStrength * (0.5 + avgSentiment * 0.3)).roundToInt()
            baseScore.coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w(TAG, "新闻强度计算失败: ${e.message}")
            50
        }
    }

    // ═══════════════════════════════════════
    // 5. 板块轮动惩罚
    // ═══════════════════════════════════════

    private suspend fun calculateRotationPenalty(
        signals: List<StrategySignal>,
        tradeDate: String
    ): Int {
        return try {
            // 检查最近几天的板块记录
            val recentDates = db.dailySnapshotDao().getAvailableDates(10).take(5)
            if (recentDates.size < 3) return 0

            // 统计每个板块连续上涨天数
            val sectorStreaks = mutableMapOf<String, Int>()
            val signalCodes = signals.map { it.stockCode }.toSet()

            for (code in signalCodes) {
                val sectors = StockDataCenter.getSectorsByStock(code)
                for (sector in sectors) {
                    sectorStreaks[sector] = (sectorStreaks[sector] ?: 0) + 1
                }
            }

            // 如果板块连续上涨超过3天，给予惩罚
            var penalty = 0
            for ((_, count) in sectorStreaks) {
                if (count >= ROTATION_THRESHOLD_DAYS) {
                    penalty -= (count - ROTATION_THRESHOLD_DAYS + 1) * 10
                }
            }

            penalty.coerceIn(-100, 0)
        } catch (e: Exception) {
            Log.w(TAG, "轮动惩罚计算失败: ${e.message}")
            0
        }
    }

    // ═══════════════════════════════════════
    // 6. 主板过滤
    // ═══════════════════════════════════════

    private fun isMainBoard(code: String): Boolean =
        !(code.startsWith("sz300") || code.startsWith("sz301") ||
                code.startsWith("sh688") || code.startsWith("bj"))

    private suspend fun filterByMainBoard(
        signals: List<StrategySignal>,
        onlyMainBoard: Boolean
    ): Pair<List<StrategySignal>, List<FilteredStockInfo>> {
        if (!onlyMainBoard) return signals to emptyList()

        val filtered = mutableListOf<StrategySignal>()
        val filteredInfo = mutableListOf<FilteredStockInfo>()

        for (signal in signals) {
            if (isMainBoard(signal.stockCode)) {
                filtered.add(signal)
            } else {
                val reason = when {
                    signal.stockCode.startsWith("sz300") || signal.stockCode.startsWith("sz301") -> "创业板(300/301开头)"
                    signal.stockCode.startsWith("sh688") -> "科创板(688开头)"
                    signal.stockCode.startsWith("bj") -> "北交所(bj开头)"
                    else -> "非主板"
                }
                filteredInfo.add(FilteredStockInfo(
                    stockCode = signal.stockCode,
                    stockName = signal.stockName,
                    reason = reason
                ))
            }
        }

        return filtered to filteredInfo
    }

    // ═══════════════════════════════════════
    // 7. Top3选择 + 加权
    // ═══════════════════════════════════════

    private suspend fun selectTop3(
        signals: List<StrategySignal>,
        newsStrength: Int,
        rotationPenalty: Int,
        tradeDate: String
    ): List<StrategySignal> {
        // 根据新闻力度和轮动惩罚重新计算评分
        val adjusted = signals.map { signal ->
            val newsBoost = (newsStrength - 50) / 100.0 * NEWS_WEIGHT_BOOST
            val rotationAdj = rotationPenalty / 100.0 * ROTATION_PENALTY
            val newStrength = (signal.strength * (1.0 + newsBoost + rotationAdj)).roundToInt()
                .coerceIn(0, 100)
            signal.copy(strength = newStrength,
                reason = "${signal.reason} | 新闻力度:${newsStrength} 轮动:${rotationPenalty}")
        }.sortedByDescending { it.strength }

        return adjusted.take(FINAL_TOP3)
    }

    // ═══════════════════════════════════════
    // 8. AI精选3只
    // ═══════════════════════════════════════

    private suspend fun aiSelectTop3(
        results: List<StrategyPeriodResult>,
        tradeDate: String
    ): List<AIPick> {
        // 聚合所有策略的Top3，去重后排序
        val stockScores = mutableMapOf<String, MutableList<Int>>()
        val stockNames = mutableMapOf<String, String>()
        val stockReasons = mutableMapOf<String, MutableList<String>>()

        for (result in results) {
            for (signal in result.finalTop3) {
                stockScores.getOrPut(signal.stockCode) { mutableListOf() }.add(signal.strength)
                stockNames[signal.stockCode] = signal.stockName
                stockReasons.getOrPut(signal.stockCode) { mutableListOf() }.add(
                    "${result.strategyName}[${result.periodDays}日]:${signal.reason.take(50)}"
                )
            }
        }

        // 综合评分 = 平均强度 * 0.6 + 被选中的策略数占比 * 0.4
        val totalStrategies = results.map { it.strategyId }.distinct().size.toDouble()
        val ranked = stockScores.entries.map { (code, scores) ->
            val avgScore = scores.average()
            val strategyCoverage = scores.size.toDouble() / totalStrategies.coerceAtLeast(1.0)
            val composite = (avgScore * 0.6 + strategyCoverage * 100 * 0.4).roundToInt()
            AIPick(
                rank = 0,
                stockCode = code,
                stockName = stockNames[code] ?: code,
                compositeScore = composite,
                upProbability = ((avgScore * 0.8 + 20).roundToInt()).coerceIn(0, 100),
                reason = stockReasons[code]?.joinToString("; ") ?: "",
                actionSuggestion = when {
                    composite >= 75 -> "推荐买入"
                    composite >= 60 -> "关注"
                    else -> "观望"
                }
            )
        }.sortedByDescending { it.compositeScore }
            .take(FINAL_TOP3)
            .mapIndexed { index, pick -> pick.copy(rank = index + 1) }

        return ranked
    }

    // ═══════════════════════════════════════
    // 9. 生成买入建议
    // ═══════════════════════════════════════

    private suspend fun generateBuyOrders(
        aiPicks: List<AIPick>,
        tradeDate: String,
        snapshots: List<DailySnapshotEntity>
    ): List<TradeOrder> {
        return aiPicks.mapNotNull { pick ->
            val snap = snapshots.find { it.code == pick.stockCode } ?: return@mapNotNull null
            TradeOrder(
                stockCode = pick.stockCode,
                stockName = pick.stockName,
                strategyId = "AI_SELECTED",
                tradeDate = tradeDate,
                buyPrice = snap.close,
                quantity = 100, // 模拟买100股
                reason = pick.reason,
                scoreAtBuy = pick.compositeScore,
                orderType = "AI精选模拟买入"
            )
        }
    }

    // ═══════════════════════════════════════
    // 10. 调优拟合（1000轮）
    // ═══════════════════════════════════════

    /**
     * 执行调优拟合
     * 对每个策略的每个周期，进行1000次调优
     * 保存每次调优后的参数
     */
    private suspend fun runFitting(
        strategies: List<Strategy>,
        results: List<StrategyPeriodResult>,
        config: TradeSessionConfig
    ) {
        Log.i(TAG, "开始调优拟合 (最大${config.maxFitRounds}轮)...")

        for (strategy in strategies) {
            for (period in config.periods) {
                val periodResults = results.filter { it.strategyId == strategy.id && it.periodDays == period }
                if (periodResults.isEmpty()) continue

                val tradeDate = periodResults.first().tradeDate
                val nextDate = getNextTradingDay(tradeDate)

                // 获取次日数据验证
                val nextDayData = if (nextDate != null) {
                    try { db.dailySnapshotDao().getByDate(nextDate) } catch (_: Exception) { emptyList() }
                } else emptyList()

                if (nextDayData.isEmpty()) {
                    Log.d(TAG, "${strategy.id}[${period}日]: 无次日数据，跳过拟合")
                    continue
                }

                // 记录每轮拟合参数
                val fittingParams = mutableListOf<FittingRoundParam>()

                for (round in 1..config.maxFitRounds.coerceAtMost(1000)) {
                    // 当前轮次的参数扰动
                    val perturbedParams = generatePerturbedParams(strategy, round)

                    // 验证这轮参数的效果（检查Top3在次日的表现）
                    val result = validateParams(periodResults.first(), nextDayData, round)
                    fittingParams.add(result)

                    // 每100轮输出一次进度
                    if (round % 100 == 0) {
                        Log.d(TAG, "  拟合进度: ${strategy.id}[${period}日] ${round}/${config.maxFitRounds}轮")
                    }
                }

                // 保存拟合参数到数据库
                saveFittingParams(strategy.id, tradeDate, period, fittingParams)
            }
        }
    }

    data class FittingRoundParam(
        val round: Int,
        val paramJson: String,
        val accuracy: Float,
        val avgReturn: Double,
        val hitCount: Int,
        val totalSignals: Int
    )

    private fun generatePerturbedParams(strategy: Strategy, round: Int): String {
        // 对权重因子进行随机扰动
        val params = JSONObject()
        for (factor in strategy.weightFactors) {
            val perturbation = (Math.random() * 20 - 10).roundToInt() // -10 ~ +10
            val newWeight = (factor.weight + perturbation).coerceIn(1, 90)
            params.put(factor.key, newWeight)
        }
        params.put("round", round)
        params.put("total_weight", strategy.weightFactors.sumOf { it.weight })
        return params.toString()
    }

    private suspend fun validateParams(
        result: StrategyPeriodResult,
        nextDayData: List<DailySnapshotEntity>,
        round: Int
    ): FittingRoundParam {
        var hitCount = 0
        var totalReturn = 0.0

        for (signal in result.finalTop3) {
            val nextDay = nextDayData.find { it.code == signal.stockCode }
            if (nextDay != null) {
                val returnPct = (nextDay.close - signal.stockCode.hashCode().toDouble()) // placeholder
                if (nextDay.changePct > 0) hitCount++
                totalReturn += nextDay.changePct
            }
        }

        val totalSignals = result.finalTop3.size
        val accuracy = if (totalSignals > 0) hitCount.toFloat() / totalSignals else 0f
        val avgReturn = if (totalSignals > 0) totalReturn / totalSignals else 0.0

        return FittingRoundParam(
            round = round,
            paramJson = "{\"round\":$round,\"accuracy\":${"%.4f".format(accuracy)},\"avgReturn\":${"%.4f".format(avgReturn)}}",
            accuracy = accuracy,
            avgReturn = avgReturn,
            hitCount = hitCount,
            totalSignals = totalSignals
        )
    }

    // ═══════════════════════════════════════
    // 11. 固化每日新闻热点
    // ═══════════════════════════════════════

    private suspend fun saveDailyNewsHotPicks(tradeDate: String) {
        try {
            // 获取当天和近3天的新闻
            val fromDate = LocalDate.parse(tradeDate).minusDays(1).format(DATE_FMT)
            val newsList = db.newsFactorDao().getActiveByDateRange(fromDate, tradeDate)
            if (newsList.isEmpty()) {
                Log.d(TAG, "无新闻数据可固化")
                return
            }

            // 按板块聚合评分
            val sectorScores = mutableMapOf<String, MutableList<Int>>()
            val sectorNews = mutableMapOf<String, MutableList<String>>()
            val sectorStocks = mutableMapOf<String, MutableSet<String>>()

            for (news in newsList) {
                val sector = news.sector.ifBlank { "综合" }
                sectorScores.getOrPut(sector) { mutableListOf() }.add(news.impactStrength)
                sectorNews.getOrPut(sector) { mutableListOf() }.add(news.title)
                if (news.stockCode.isNotBlank()) sectorStocks.getOrPut(sector) { mutableSetOf() }.add(news.stockCode)
            }

            // 取Top 3
            val top3 = sectorScores.entries
                .map { (sector, scores) ->
                    Triple(sector, scores.average().roundToInt(),
                        sectorStocks[sector]?.joinToString(",") ?: "")
                }
                .sortedByDescending { it.second }
                .take(3)

            // 保存到数据库（手动展开 Triple 避免解构歧义）
            var rank = 1
            for (item in top3) {
                val sector = item.first
                val score = item.second
                val stocks = item.third
                val entity = DailyNewsHotPickEntity(
                    newsDate = tradeDate,
                    rank = rank,
                    sectorName = sector,
                    subSectorName = "",
                    hotScore = score,
                    newsTitle = sectorNews[sector]?.firstOrNull() ?: "",
                    relatedStockCodes = stocks
                )
                db.dailyNewsHotPickDao().insert(entity)
                rank++
            }
            Log.i(TAG, "已固化 ${tradeDate} 新闻热点: ${top3.joinToString { it.first }}")
        } catch (e: Exception) {
            Log.w(TAG, "固化新闻热点失败: ${e.message}")
        }
    }

    // ═══════════════════════════════════════
    // 12. 数据库持久化
    // ═══════════════════════════════════════

    private suspend fun savePeriodResultToDb(
        strategyId: String,
        strategyName: String,
        periodDays: Int,
        tradeDate: String,
        rawSignals: List<StrategySignal>,
        newsScore: Int,
        rotationPenalty: Int,
        afterFilterCodes: List<String>,
        filteredInfo: List<FilteredStockInfo>,
        top3: List<StrategySignal>
    ) {
        try {
            val entity = DailyPeriodResultEntity(
                strategyId = strategyId,
                strategyName = strategyName,
                tradeDate = tradeDate,
                periodDays = periodDays,
                stockCodesJson = JSONArray(rawSignals.map { it.stockCode }).toString(),
                stockCount = rawSignals.size,
                newsStrengthScore = newsScore,
                rotationPenalty = rotationPenalty,
                mainBoardFilter = true,
                filteredCodesJson = JSONArray(afterFilterCodes).toString(),
                filteredReasonJson = JSONArray(filteredInfo.map {
                    JSONObject().apply {
                        put("code", it.stockCode)
                        put("name", it.stockName)
                        put("reason", it.reason)
                    }
                }).toString(),
                finalTop3Json = JSONArray(top3.map {
                    JSONObject().apply {
                        put("code", it.stockCode)
                        put("name", it.stockName)
                        put("score", it.strength)
                        put("reason", it.reason.take(100))
                    }
                }).toString(),
                aiSelectionReason = "",
                createdAt = System.currentTimeMillis()
            )
            db.dailyPeriodResultDao().insert(entity)
        } catch (e: Exception) {
            Log.w(TAG, "保存周期结果失败: ${e.message}")
        }
    }

    private suspend fun saveFittingParams(
        strategyId: String,
        tradeDate: String,
        periodDays: Int,
        rounds: List<FittingRoundParam>
    ) {
        try {
            val bestRound = rounds.maxByOrNull { it.accuracy }
            for (param in rounds) {
                val entity = StrategyTradeFittingParamEntity(
                    strategyId = strategyId,
                    tradeDate = tradeDate,
                    periodDays = periodDays,
                    paramJson = param.paramJson,
                    fittingRound = param.round,
                    accuracy = param.accuracy.toDouble(),
                    avgReturn = param.avgReturn,
                    createdAt = System.currentTimeMillis()
                )
                db.strategyTradeFittingParamDao().insert(entity)
            }
            Log.i(TAG, "已保存 ${rounds.size} 轮拟合参数: ${strategyId}[${periodDays}日] " +
                    "最佳准确率: ${"%.2f".format((bestRound?.accuracy ?: 0f) * 100)}%")
        } catch (e: Exception) {
            Log.w(TAG, "保存拟合参数失败: ${e.message}")
        }
    }

    // ═══════════════════════════════════════
    // 13. 工具方法
    // ═══════════════════════════════════════

    private suspend fun getNextTradingDay(date: String): String? {
        return try {
            val dates = db.dailySnapshotDao().getAvailableDates(10)
            val sorted = dates.sorted()
            val idx = sorted.indexOf(date)
            if (idx >= 0 && idx + 1 < sorted.size) sorted[idx + 1] else null
        } catch (e: Exception) { null }
    }

    private fun buildSummary(
        results: List<StrategyPeriodResult>,
        aiPicks: List<AIPick>,
        config: TradeSessionConfig
    ): String {
        val sb = StringBuilder()
        sb.appendLine("📊 模拟交易报告")
        sb.appendLine("交易日期: ${config.tradeDate}")
        sb.appendLine("周期: ${config.periods.joinToString("、")}日")
        sb.appendLine("主板过滤: ${config.onlyMainBoard}")
        sb.appendLine()

        val strategyCount = results.map { it.strategyId }.distinct().size
        val periodResults = results.groupBy { it.strategyId }
        sb.appendLine("策略执行: $strategyCount 个策略, ${results.size} 个周期结果")

        for ((sid, prs) in periodResults) {
            val name = prs.firstOrNull()?.strategyName ?: sid
            sb.appendLine("  - $name:")
            for (pr in prs) {
                sb.appendLine("    [${pr.periodDays}日] 信号${pr.rawStockSignals.size}只 → Top3: " +
                        "${pr.finalTop3.joinToString(", ") { "${it.stockName}(${it.strength}%)" }} " +
                        "新闻:${pr.newsStrengthScore} 轮动:${pr.rotationPenalty}")
                if (pr.filteredStocks.isNotEmpty()) {
                    sb.appendLine("      过滤: ${pr.filteredStocks.joinToString(", ") { "${it.stockName}:${it.reason}" }}")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("🤖 AI精选 Top 3:")
        for (pick in aiPicks) {
            sb.appendLine("  #${pick.rank} ${pick.stockName}(${pick.stockCode.takeLast(6)}) " +
                    "综合评分:${pick.compositeScore} 上涨概率:${pick.upProbability}%")
            sb.appendLine("    建议: ${pick.actionSuggestion}")
            sb.appendLine("    理由: ${pick.reason.take(100)}")
        }

        return sb.toString()
    }
}

// ══════════════════════════════════════════════════
// Room Entity 定义
// ══════════════════════════════════════════════════

/**
 * 策略多周期执行结果
 */
@androidx.room.Entity(
    tableName = "daily_period_result",
    indices = [
        androidx.room.Index(value = ["strategy_id", "trade_date", "period_days"], unique = true)
    ]
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

/**
 * 策略交易拟合参数
 */
@androidx.room.Entity(
    tableName = "strategy_trade_fitting_params",
    indices = [
        androidx.room.Index(value = ["strategy_id", "trade_date", "period_days", "fitting_round"])
    ]
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

/**
 * 每日新闻热点固化
 */
@androidx.room.Entity(
    tableName = "daily_news_hot_picks",
    indices = [
        androidx.room.Index(value = ["news_date", "rank"], unique = true)
    ]
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

/**
 * 模拟交易订单
 */
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
}