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
 * ## 情緒週期策略
 *
 * 基於市場情緒指標擇時，在情緒冰點日買入超跌反彈股。
 *
 * 情緒指標：
 * - 漲停數 / 跌停數比
 * - 連板高度（最高連續漲停天數）
 * - 炸板率（盤中觸及漲停但未封住的比例，簡化為漲停後回落）
 *
 * 冰點信號：跌停 > 漲停 × 2 且市場普跌 → 次日反彈概率大
 * 高潮信號：漲停 > 50 → 風險警示，不買入
 *
 * 冰點日選股：近 3 日跌幅 > 10% 但今日企穩（跌幅收窄或收陽）
 * 評分：情緒位置(40%) + 超跌程度(30%) + 企穩信號(30%)
 */
class MarketSentimentStrategy(
    private val context: Context,
    private val screener: StockScreener? = null
) : Strategy {

    override val id = "market_sentiment"
    override var name = "情緒週期策略"
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
        WeightFactor("sentiment", "情緒位置", 40, "冰點=滿分，高潮=0"),
        WeightFactor("oversold", "超跌程度", 30, "近3日跌幅越大越好"),
        WeightFactor("stabilize", "企穩信號", 30, "今日跌幅收窄或收陽")
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

        // ── 計算市場情緒指標 ──
        val limitUpCount = pool.count { it.changePercent >= 9.5 }
        val limitDownCount = pool.count { it.changePercent <= -9.5 }
        val upCount = pool.count { it.changePercent > 0 }
        val downCount = pool.count { it.changePercent < 0 }
        val avgChange = pool.map { it.changePercent }.average()

        // 連板高度（簡化：從 DB 查近 5 日每日漲停股，找連續出現的）
        val consecutiveHeight = calculateConsecutiveLimitUp(db, pool)

        // 情緒判定
        val sentimentPhase = when {
            limitUpCount >= limitUpThreshold && consecutiveHeight >= 5 -> "CLIMAX"
            limitDownCount > limitUpCount * freezeRatio && avgChange < -1.5 -> "FREEZE"
            limitDownCount > limitUpCount && avgChange < -0.5 -> "COOLING"
            limitUpCount > limitDownCount * 2 && avgChange > 1.0 -> "WARMING"
            else -> "NEUTRAL"
        }

        Log.i("MS_Strategy", "情緒: $sentimentPhase, 漲停=$limitUpCount 跌停=$limitDownCount " +
            "連板=$consecutiveHeight 均漲=${"%.2f".format(avgChange)}%")

        // 高潮期不買入
        if (sentimentPhase == "CLIMAX") {
            Log.i("MS_Strategy", "情緒高潮，迴避買入")
            return Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
            ))
        }

        // 只在冰點/降溫期選股
        if (sentimentPhase != "FREEZE" && sentimentPhase != "COOLING") {
            Log.i("MS_Strategy", "情緒非冰點($sentimentPhase)，不觸發超跌反彈")
            return Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
            ))
        }

        // ── 冰點選股：超跌 + 企穩 ──
        // 讀取近 3 天數據計算累計跌幅
        val dates = dao.getAvailableDates(5).sorted().takeLast(4)
        if (dates.size < 4) {
            return Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
            ))
        }

        val signals = mutableListOf<StrategySignal>()

        // 預過濾：今日企穩（跌幅 < 昨日 或 收陽）、有流動性
        val candidates = pool.filter {
            it.amount > 50_000_000 &&
            it.price > 3.0 &&
            !it.name.contains("ST", ignoreCase = true) &&
            it.changePercent > -5.0 && // 今日沒有繼續暴跌
            (it.changePercent > -1.0 || it.price > it.open) // 跌幅收窄或收陽
        }

        for (stock in candidates) {
            try {
                val history = dao.getByCode(stock.code, 5)
                if (history.size < 3) continue

                val sorted = history.sortedBy { it.date }
                // 近 3 日累計跌幅
                val recent3Change = sorted.takeLast(3).sumOf { it.changePct }
                if (recent3Change > oversoldDrop) continue // 跌幅不夠深

                // 企穩信號
                val todayStabilized = stock.changePercent > sorted.dropLast(1).last().changePct || // 今日跌幅 < 昨日
                    stock.price > stock.open // 或收陽

                // 情緒位置評分 (0-40)
                val sentimentScore = when (sentimentPhase) {
                    "FREEZE" -> 40
                    "COOLING" -> 28
                    else -> 15
                }

                // 超跌程度評分 (0-30)
                val oversoldScore = when {
                    recent3Change < -20 -> 30
                    recent3Change < -15 -> 26
                    recent3Change < -12 -> 22
                    recent3Change < oversoldDrop -> 18
                    else -> 10
                }

                // 企穩信號評分 (0-30)
                val stabilizeScore = when {
                    stock.changePercent > 0 && stock.price > stock.open -> 30 // 收陽且漲
                    stock.price > stock.open -> 24 // 收陽
                    stock.changePercent > -0.5 -> 18 // 基本平盤
                    todayStabilized -> 14
                    else -> 5
                }

                val strength = (sentimentScore + oversoldScore + stabilizeScore).coerceIn(0, 100)
                if (strength < 50) continue

                val reason = buildString {
                    append("情緒${if (sentimentPhase == "FREEZE") "冰點" else "降溫"}")
                    append(" 3日跌${"%.1f".format(recent3Change)}%")
                    append(" 今日${"%.1f".format(stock.changePercent)}%")
                    if (stock.price > stock.open) append(" 收陽企穩")
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
        Log.i("MS_Strategy", "計算完成: ${candidates.size} 候選 → ${result.size} 信號")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = result, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    /**
     * 簡化連板高度計算：統計今日漲停股中，昨日也漲停的數量
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

            // 向前回溯，找最長連續漲停
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
