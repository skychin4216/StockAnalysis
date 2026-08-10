package com.chin.stockanalysis.strategy.strategies

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 情绪周期策略
 *
 * 基于市场情绪指标择时，在情绪冰点日买入超跌反弹股。
 *
 * 情绪指标：
 * - 涨停数 / 跌停数比
 * - 连板高度（最高连续涨停天数）
 * - 炸板率（盘中触及涨停但未封住的比例，简化为涨停后回落）
 *
 * 冰点信号：跌停 > 涨停 × 2 且市场普跌 → 次日反弹概率大
 * 高潮信号：涨停 > 50 → 风险警示，不买入
 *
 * 冰点日选股：近 3 日跌幅 > 10% 但今日企稳（跌幅收窄或收阳）
 * 评分：情绪位置(40%) + 超跌程度(30%) + 企稳信号(30%)
 */
class MarketSentimentStrategy(
    private val context: Context,
    private val screener: StockScreener? = null
) : Strategy {

    override val id = "market_sentiment"
    override var name = "情绪周期策略"
    override var description = "情绪冰点日买入超跌反弹股，高潮日回避"
    override val category = StrategyCategory.CUSTOM
    override val holdingPeriods = listOf(HoldingPeriod.ULTRA_SHORT)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 4
    override val defaultStopLoss = -0.03f
    override val defaultTakeProfit = 0.05f
    override val maxPositions = 3

    override val config = StrategyConfig.custom(
        params = mapOf("freeze_ratio" to 2.0, "oversold_drop" to -10.0, "limit_up_threshold" to 50),
        maxResults = 8
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("sentiment", "情绪位置", 40, "冰点=满分，高潮=0"),
        WeightFactor("oversold", "超跌程度", 30, "近3日跌幅越大越好"),
        WeightFactor("stabilize", "企稳信号", 30, "今日跌幅收窄或收阳")
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = screener?.scanFullMarket() ?: emptyList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty()) {
                preloadedStocks.filter { it.code in config.stockPool }
            } else {
                preloadedStocks
            }
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun isAvailable(): Boolean = true

    private suspend fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        val db = StockDatabase.getInstance(context)
        val dao = db.dailySnapshotDao()
        val limitUpThreshold = (config.params["limit_up_threshold"] as? Number)?.toInt() ?: 50
        val freezeRatio = (config.params["freeze_ratio"] as? Number)?.toDouble() ?: 2.0
        val oversoldDrop = (config.params["oversold_drop"] as? Number)?.toDouble() ?: -10.0

        // ── 计算市场情绪指标 ──
        val limitUpCount = pool.count { it.changePercent >= 9.5 }
        val limitDownCount = pool.count { it.changePercent <= -9.5 }
        val upCount = pool.count { it.changePercent > 0 }
        val downCount = pool.count { it.changePercent < 0 }
        val avgChange = pool.map { it.changePercent }.average()

        // 连板高度（简化：从 DB 查近 5 日每日涨停股，找连续出现的）
        val consecutiveHeight = calculateConsecutiveLimitUp(db, pool)

        // 情绪判定
        val sentimentPhase = when {
            limitUpCount >= limitUpThreshold && consecutiveHeight >= 5 -> "CLIMAX"
            limitDownCount > limitUpCount * freezeRatio && avgChange < -1.5 -> "FREEZE"
            limitDownCount > limitUpCount && avgChange < -0.5 -> "COOLING"
            limitUpCount > limitDownCount * 2 && avgChange > 1.0 -> "WARMING"
            else -> "NEUTRAL"
        }

        Log.i("MS_Strategy", "情绪: $sentimentPhase, 涨停=$limitUpCount 跌停=$limitDownCount " +
            "连板=$consecutiveHeight 均涨=${"%.2f".format(avgChange)}%")

        // 高潮期不买入
        if (sentimentPhase == "CLIMAX") {
            Log.i("MS_Strategy", "情绪高潮，回避买入")
            return Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
            ))
        }

        // 只在冰点/降温期选股
        if (sentimentPhase != "FREEZE" && sentimentPhase != "COOLING") {
            Log.i("MS_Strategy", "情绪非冰点($sentimentPhase)，不触发超跌反弹")
            return Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
            ))
        }

        // ── 冰点选股：超跌 + 企稳 ──
        // 读取近 3 天数据计算累计跌幅
        val dates = dao.getAvailableDates(5).sorted().takeLast(4)
        if (dates.size < 4) {
            return Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
            ))
        }

        val signals = mutableListOf<StrategySignal>()

        // 预过滤：今日企稳（跌幅 < 昨日 或 收阳）、有流动性
        val candidates = pool.filter {
            it.amount > 50_000_000 &&
            it.price > 3.0 &&
            !it.name.contains("ST", ignoreCase = true) &&
            it.changePercent > -5.0 && // 今日没有继续暴跌
            (it.changePercent > -1.0 || it.price > it.open) // 跌幅收窄或收阳
        }

        for (stock in candidates) {
            try {
                val history = dao.getByCode(stock.code, 5)
                if (history.size < 3) continue

                val sorted = history.sortedBy { it.date }
                // 近 3 日累计跌幅
                val recent3Change = sorted.takeLast(3).sumOf { it.changePct }
                if (recent3Change > oversoldDrop) continue // 跌幅不够深

                // 企稳信号
                val todayStabilized = stock.changePercent > sorted.dropLast(1).last().changePct || // 今日跌幅 < 昨日
                    stock.price > stock.open // 或收阳

                // 情绪位置评分 (0-40)
                val sentimentScore = when (sentimentPhase) {
                    "FREEZE" -> 40
                    "COOLING" -> 28
                    else -> 15
                }

                // 超跌程度评分 (0-30)
                val oversoldScore = when {
                    recent3Change < -20 -> 30
                    recent3Change < -15 -> 26
                    recent3Change < -12 -> 22
                    recent3Change < oversoldDrop -> 18
                    else -> 10
                }

                // 企稳信号评分 (0-30)
                val stabilizeScore = when {
                    stock.changePercent > 0 && stock.price > stock.open -> 30 // 收阳且涨
                    stock.price > stock.open -> 24 // 收阳
                    stock.changePercent > -0.5 -> 18 // 基本平盘
                    todayStabilized -> 14
                    else -> 5
                }

                val strength = (sentimentScore + oversoldScore + stabilizeScore).coerceIn(0, 100)
                if (strength < 50) continue

                val reason = buildString {
                    append("情绪${if (sentimentPhase == "FREEZE") "冰点" else "降温"}")
                    append(" 3日跌${"%.1f".format(recent3Change)}%")
                    append(" 今日${"%.1f".format(stock.changePercent)}%")
                    if (stock.price > stock.open) append(" 收阳企稳")
                }

                signals.add(StrategySignal(
                    strategyId = id, category = category,
                    stockCode = stock.code, stockName = stock.name,
                    strength = strength, reason = reason,
                    action = if (strength >= 70) SignalAction.BUY else SignalAction.WATCH,
                    currentPrice = stock.price, changePercent = stock.changePercent
                ))
            } catch (_: Exception) { continue }
        }

        val result = signals.sortedByDescending { it.strength }.take(config.maxResults)
        Log.i("MS_Strategy", "计算完成: ${candidates.size} 候选 → ${result.size} 信号")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = result, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    /**
     * 简化连板高度计算：统计今日涨停股中，昨日也涨停的数量
     */
    private suspend fun calculateConsecutiveLimitUp(
        db: StockDatabase,
        todayPool: List<StockRealtime>
    ): Int {
        return try {
            val dao = db.dailySnapshotDao()
            val dates = dao.getAvailableDates(7).sorted()
            if (dates.size < 2) return 0

            val todayLimitUp = todayPool.filter { it.changePercent >= 9.5 }.map { it.code }.toSet()
            if (todayLimitUp.isEmpty()) return 0

            // 向前回溯，找最长连续涨停
            var maxHeight = 1
            var currentCodes = todayLimitUp

            for (i in dates.size - 2 downTo maxOf(0, dates.size - 6)) {
                val prevSnaps = dao.getByDate(dates[i])
                val prevLimitUp = prevSnaps.filter { it.changePct >= 9.5 }.map { it.code }.toSet()
                val consecutive = currentCodes.intersect(prevLimitUp)
                if (consecutive.isEmpty()) break
                maxHeight++
                currentCodes = consecutive
            }

            maxHeight
        } catch (_: Exception) { 0 }
    }
}
