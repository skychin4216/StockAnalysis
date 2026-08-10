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
 * ## 中线均线趋势跟踪策略
 *
 * 填补中线缺少真正趋势策略的空白。
 *
 * 入场条件：
 * 1. 均线多头排列：MA20 > MA60 > MA120
 * 2. MACD 确认：DIF > DEA 且 DIF > 0（零轴上方）
 * 3. 回踩确认：近 3 日最低价触及 MA20 但未跌破 MA60
 * 4. 入场信号：回踩后首日收阳（close > open）
 *
 * 评分：均线排列强度(35%) + MACD 动能(30%) + 回踩精准度(20%) + 量能(15%)
 */
class TrendFollowingStrategy(
    private val context: Context,
    private val screener: StockScreener? = null
) : Strategy {

    override val id = "trend_following"
    override var name = "均线趋势跟踪"
    override var description = "MA20>MA60>MA120多头排列，MACD零轴上方，回踩MA20不破后收阳入场"
    override val category = StrategyCategory.TREND
    override val holdingPeriods = listOf(HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 120

    override val config = StrategyConfig.custom(
        params = mapOf("ma_short" to 20, "ma_mid" to 60, "ma_long" to 120),
        maxResults = 10
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("alignment", "均线排列", 35, "多头排列强度"),
        WeightFactor("macd", "MACD动能", 30, "DIF-DEA差值"),
        WeightFactor("pullback", "回踩精准", 20, "触及MA20但未破MA60"),
        WeightFactor("volume", "量能确认", 15, "收阳日放量")
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

        val marketDir = try { screener?.detectMarketDirection() } catch (_: Exception) { null } ?: "OSCILLATION"
        val isBearish = marketDir == "BEARISH"
        val strengthThreshold = if (isBearish) 55 else 40

        val db = StockDatabase.getInstance(context)
        val dao = db.dailySnapshotDao()

        // 预过滤：有基本流动性、当日收阳、非 ST
        val candidates = pool.filter {
            it.amount > 100_000_000 &&
            it.price > 5.0 &&
            it.price > it.open && // 当日收阳（入场信号）
            !it.name.contains("ST", ignoreCase = true) &&
            it.changePercent > -2.0 // 排除大跌股
        }
        Log.i("TF_Strategy", "大盘: $marketDir, 候选: ${candidates.size}/${pool.size}")

        val signals = mutableListOf<StrategySignal>()

        for (stock in candidates) {
            try {
                // 读取 120+5 天历史数据
                val history = dao.getByCode(stock.code, 125)
                if (history.size < 120) continue

                val sorted = history.sortedBy { it.date }
                val closes = sorted.map { it.close }
                val highs = sorted.map { it.high }
                val lows = sorted.map { it.low }
                val volumes = sorted.map { it.volume.toDouble() }

                // 计算均线
                val ma20 = closes.takeLast(20).average()
                val ma60 = closes.takeLast(60).average()
                val ma120 = closes.takeLast(120).average()

                // 条件 1：多头排列 MA20 > MA60 > MA120
                if (!(ma20 > ma60 && ma60 > ma120)) continue

                // 条件 2：MACD（DIF > DEA 且 DIF > 0）
                val ema12 = calculateEMA(closes, 12)
                val ema26 = calculateEMA(closes, 26)
                val dif = ema12 - ema26
                val difSeries = calculateDIFSeries(closes, 12, 26)
                val dea = calculateEMA(difSeries, 9)
                if (dif <= dea || dif <= 0) continue

                // 条件 3：回踩确认 — 近 3 日最低价触及 MA20 但未跌破 MA60
                val recent3Lows = lows.takeLast(3)
                val touchedMA20 = recent3Lows.any { it <= ma20 * 1.02 } // 2% 容差
                val heldMA60 = recent3Lows.all { it > ma60 * 0.98 } // 2% 容差
                if (!touchedMA20 || !heldMA60) continue

                // 条件 4：今日收阳（已在预过滤中确认）

                // ── 评分 ──
                // 均线排列强度 (0-35)
                val spread20_60 = if (ma60 > 0) (ma20 - ma60) / ma60 * 100 else 0.0
                val spread60_120 = if (ma120 > 0) (ma60 - ma120) / ma120 * 100 else 0.0
                val alignmentScore = when {
                    spread20_60 > 5 && spread60_120 > 5 -> 35
                    spread20_60 > 3 && spread60_120 > 3 -> 30
                    spread20_60 > 2 && spread60_120 > 2 -> 25
                    spread20_60 > 1 && spread60_120 > 1 -> 18
                    else -> 12
                }

                // MACD 动能 (0-30)
                val macdStrength = dif - dea
                val macdScore = when {
                    macdStrength > 1.0 -> 30
                    macdStrength > 0.5 -> 25
                    macdStrength > 0.2 -> 20
                    macdStrength > 0.05 -> 15
                    else -> 8
                }

                // 回踩精准度 (0-20)
                val todayLow = lows.last()
                val distToMA20 = if (ma20 > 0) (todayLow - ma20) / ma20 * 100 else 0.0
                val pullbackScore = when {
                    distToMA20 in -1.0..1.0 -> 20  // 精准触及
                    distToMA20 in -2.0..2.0 -> 16
                    distToMA20 in 1.0..5.0 -> 12   // 略高于 MA20
                    else -> 6
                }

                // 量能确认 (0-15)
                val avgVol10 = volumes.takeLast(11).dropLast(1).average()
                val volRatio = if (avgVol10 > 0) stock.volume.toDouble() / avgVol10 else 1.0
                val volumeScore = when {
                    volRatio > 2.0 -> 15
                    volRatio > 1.5 -> 12
                    volRatio > 1.2 -> 9
                    volRatio > 0.8 -> 6
                    else -> 3
                }

                val strength = (alignmentScore + macdScore + pullbackScore + volumeScore).coerceIn(0, 100)
                if (strength < strengthThreshold) continue

                val reason = buildString {
                    append("多头排列 MA20>${"%.1f".format(ma20)}>MA60>${"%.1f".format(ma60)}")
                    append(" MACD=${"%.2f".format(dif)}")
                    append(" 回踩MA20收阳")
                    if (volRatio > 1.5) append(" 放量${"%.1f".format(volRatio)}x")
                }

                signals.add(StrategySignal(
                    strategyId = id, category = category,
                    stockCode = stock.code, stockName = stock.name,
                    strength = strength, reason = reason,
                    action = if (strength >= 65) SignalAction.BUY else SignalAction.WATCH,
                    currentPrice = stock.price, changePercent = stock.changePercent
                ))
            } catch (_: Exception) { continue }
        }

        val result = signals.sortedByDescending { it.strength }.take(config.maxResults)
        Log.i("TF_Strategy", "计算完成: ${candidates.size} 候选 → ${result.size} 信号")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = result, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    private fun calculateEMA(data: List<Double>, period: Int): Double {
        if (data.isEmpty()) return 0.0
        val k = 2.0 / (period + 1)
        var ema = data.first()
        for (i in 1 until data.size) {
            ema = data[i] * k + ema * (1 - k)
        }
        return ema
    }

    private fun calculateDIFSeries(closes: List<Double>, fast: Int, slow: Int): List<Double> {
        if (closes.size < slow) return emptyList()
        val kFast = 2.0 / (fast + 1)
        val kSlow = 2.0 / (slow + 1)
        var emaFast = closes.first()
        var emaSlow = closes.first()
        val difList = mutableListOf<Double>()
        for (i in 1 until closes.size) {
            emaFast = closes[i] * kFast + emaFast * (1 - kFast)
            emaSlow = closes[i] * kSlow + emaSlow * (1 - kSlow)
            difList.add(emaFast - emaSlow)
        }
        return difList
    }
}
