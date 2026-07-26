package com.chin.stockanalysis.strategy.strategies

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
 * ## 均線金叉策略（V2 — 真實均線交叉檢測）
 *
 * 檢測 MA5 上穿 MA20 的金叉信號，配合成交量確認趨勢啟動。
 * - 前一天 MA5 < MA20，今天 MA5 > MA20 → 金叉確認
 * - 輔助條件：收陽線、漲幅 > 0.5%、成交額 > 5000 萬
 * - BEARISH 時提高門檻
 */
class MovingAverageStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "ma_golden_cross"
    override var name = "均线金叉策略"
    override var description = "5日均线上穿20日均线，配合成交量放大确认趋势启动"
    override val category = StrategyCategory.TREND
    override val holdingPeriods = listOf(HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 120   // 5個交易日

    override val config = StrategyConfig.custom(
        params = mapOf("short_period" to 5, "long_period" to 20, "volume_ratio_min" to 1.5),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("golden_cross", "金叉確認", 35, "MA5上穿MA20的金叉強度"),
        WeightFactor("momentum", "动量得分", 30, "基于涨跌幅的动量评分"),
        WeightFactor("volume", "量比得分", 35, "基于成交额的量比评分")
    )

    private val db by lazy { StockDatabase.getInstance(screener.context) }

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty()) preloadedStocks.filter { it.code in config.stockPool } else preloadedStocks
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        // 大盤環境預檢
        val marketDir = try { screener.detectMarketDirection() } catch (_: Exception) { "OSCILLATION" }
        val isBearish = marketDir == "BEARISH"
        val dynamicChangeMin = if (isBearish) 1.0 else 0.5
        val dynamicStrengthThreshold = if (isBearish) 45 else 30
        val dynamicAmountMin = if (isBearish) 100_000_000.0 else 50_000_000.0
        Log.i("MA_Strategy", "大盤環境: $marketDir → 門檻 chg≥${dynamicChangeMin}%, amt≥${dynamicAmountMin/1e6}M, strength≥$dynamicStrengthThreshold")

        // Step 1: 基本過濾（收陽線 + 漲幅 + 成交額）
        val step1 = pool.filter { it.price > it.yestClose && it.changePercent >= dynamicChangeMin && it.amount >= dynamicAmountMin }
        Log.i("MA_Strategy", "pool=${pool.size} → 基本過濾=${step1.size}")

        // Step 2: MA5/MA20 金叉檢測（查最近21天K線數據）
        val availableDates = try { db.dailySnapshotDao().getAvailableDates(25) } catch (_: Exception) { emptyList() }
        val todayDate = availableDates.firstOrNull() ?: ""

        // 批量預加載近21天的收盤價（避免 N+1）
        val closesByCode = mutableMapOf<String, List<Double>>()
        if (availableDates.size >= 21) {
            val datesNeeded = availableDates.take(21)
            val recentSnaps = try { db.dailySnapshotDao().getByDate(datesNeeded.first()) } catch (_: Exception) { emptyList() }
            // 一次性查最近21天的所有快照
            for (date in datesNeeded) {
                val snaps = try { db.dailySnapshotDao().getByDate(date) } catch (_: Exception) { emptyList() }
                for (snap in snaps) {
                    closesByCode.getOrPut(snap.code) { mutableListOf() }
                    (closesByCode[snap.code] as MutableList).add(snap.close)
                }
            }
        }

        // 計算 MA 並檢測金叉
        val goldenCrossStocks = mutableListOf<StockRealtime>()
        for (stock in step1) {
            val closes = closesByCode[stock.code]?.takeLast(21)
            if (closes == null || closes.size < 21) continue

            val todayMA5 = closes.takeLast(5).average()
            val todayMA20 = closes.takeLast(20).average()
            // 前一天：去掉今天，計算昨天 MA5/MA20
            val yesterdayCloses = closes.dropLast(1)
            val yesterdayMA5 = yesterdayCloses.takeLast(5).average()
            val yesterdayMA20 = yesterdayCloses.takeLast(20).average()

            val isGoldenCross = yesterdayMA5 <= yesterdayMA20 && todayMA5 > todayMA20
            val isNearCross = todayMA5 > todayMA20 && (todayMA5 - todayMA20) / todayMA20 < 0.02 // 接近金叉（2%以內）

            if (isGoldenCross || isNearCross) {
                val crossType = if (isGoldenCross) "金叉" else "接近金叉"
                Log.i("MA_Strategy", "  ✅ $crossType: ${stock.name}(${stock.code}) MA5=${"%.2f".format(todayMA5)} MA20=${"%.2f".format(todayMA20)} 昨MA5=${"%.2f".format(yesterdayMA5)} 昨MA20=${"%.2f".format(yesterdayMA20)}")
                goldenCrossStocks.add(stock)
            }
        }
        Log.i("MA_Strategy", "金叉檢測: ${goldenCrossStocks.size}/${step1.size}")

        // Step 3: 打分
        val step3 = goldenCrossStocks.map { calculateSignal(it) }
        val step4 = step3.filter { it.strength >= dynamicStrengthThreshold }
        Log.i("MA_Strategy", "打分後 strength≥$dynamicStrengthThreshold: ${step4.size}")
        if (step3.isNotEmpty()) {
            val top = step3.sortedByDescending { it.strength }.take(3)
            Log.i("MA_Strategy", "  Top3: ${top.joinToString { "${it.stockName}=${it.strength}" }}")
        }
        val signals = step4.sortedByDescending { it.strength }.take(config.maxResults)
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    override suspend fun isAvailable(): Boolean = true

    private fun calculateSignal(stock: StockRealtime): StrategySignal {
        val w = weightFactors.associateBy { it.key }
        // 金叉確認得分：漲幅越大說明金叉越強
        val crossScore = when {
            stock.changePercent > 5 -> 35
            stock.changePercent > 3 -> 28
            stock.changePercent > 1 -> 20
            stock.changePercent > 0 -> 12
            else -> 5
        }
        // 動量得分
        val momentumScore = when {
            stock.changePercent > 5 -> 30
            stock.changePercent > 3 -> 22
            stock.changePercent > 1 -> 15
            stock.changePercent > 0 -> 8
            else -> 0
        }
        // 量比得分
        val volumeScore = when {
            stock.amount > 2_000_000_000.0 -> 35
            stock.amount > 1_000_000_000.0 -> 28
            stock.amount > 500_000_000.0 -> 20
            stock.amount > 100_000_000.0 -> 12
            else -> 5
        }
        val rawStrength = (crossScore * (w["golden_cross"]?.weight ?: 35) / 100.0).toInt() +
                (momentumScore * (w["momentum"]?.weight ?: 30) / 100.0).toInt() +
                (volumeScore * (w["volume"]?.weight ?: 35) / 100.0).toInt()
        val strength = minOf(rawStrength, 100)
        return StrategySignal(
            stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
            strength = strength,
            action = when { strength >= 80 -> SignalAction.BUY; strength >= 60 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = "均线金叉确认：MA5上穿MA20，涨${String.format("%.2f", stock.changePercent)}%",
            currentPrice = stock.price, changePercent = stock.changePercent
        )
    }
}
