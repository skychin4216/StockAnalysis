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
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ## 布林帶突破策略（修正版）
 *
 * 從 DB 讀取單股連續 N 天收盤價計算標準布林帶（MA20 ± 2σ）。
 * 突破判定：當日收盤價 > upper band 且成交量 > 1.5× 均量。
 * 新增 squeeze breakout 加分：帶寬收窄至近期低位後突破。
 */
class BollingerBandStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "bollinger_band"
    override var name = "布林带突破策略"
    override var description = "股价突破布林带上轨且成交量放大，趋势确认信号"
    override val category = StrategyCategory.TREND
    override val holdingPeriods = listOf(HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 120

    override val config = StrategyConfig.custom(
        params = mapOf("period" to 20, "std_mult" to 2.0, "volume_confirm_ratio" to 1.5),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("breakout", "突破强度", 40, "距离上轨的偏移百分比"),
        WeightFactor("volume", "量能确认", 35, "成交量/均量比值"),
        WeightFactor("squeeze", "带寬收窄", 25, "突破前帶寬是否處於低位")
    )

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
            val pool = if (config.stockPool.isNotEmpty()) {
                preloadedStocks.filter { it.code in config.stockPool }
            } else preloadedStocks
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun isAvailable(): Boolean = true

    private suspend fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        val marketDir = try { screener.detectMarketDirection() } catch (_: Exception) { "OSCILLATION" }
        val isBearish = marketDir == "BEARISH"
        val strengthThreshold = if (isBearish) 45 else 30
        val period = (config.params["period"] as? Number)?.toInt() ?: 20
        val stdMult = (config.params["std_mult"] as? Number)?.toDouble() ?: 2.0
        val volConfirmRatio = (config.params["volume_confirm_ratio"] as? Number)?.toDouble() ?: 1.5

        val db = StockDatabase.getInstance(screener.context)
        val dao = db.dailySnapshotDao()

        // 預過濾：只對有基本成交量的股票計算布林帶（減少 DB 查詢）
        val candidates = pool.filter { it.amount > 50_000_000 && it.price > 2.0 }
        Log.i("BB_Strategy", "大盤: $marketDir, 候選: ${candidates.size}/${pool.size}")

        val signals = mutableListOf<StrategySignal>()

        for (stock in candidates) {
            try {
                // 從 DB 讀取該股近 period+5 天歷史數據（多取 5 天用於計算均量）
                val history = dao.getByCode(stock.code, period + 5)
                if (history.size < period) continue

                // 按日期升序排列（DB 返回 DESC）
                val sorted = history.sortedBy { it.date }
                val closes = sorted.map { it.close }
                val volumes = sorted.map { it.volume.toDouble() }

                // 計算布林帶（取最近 period 天）
                val recentCloses = closes.takeLast(period)
                val ma = recentCloses.average()
                val variance = recentCloses.map { (it - ma).pow(2) }.average()
                val std = sqrt(variance)
                val upper = ma + stdMult * std
                val lower = ma - stdMult * std
                val bandWidth = if (ma > 0) (upper - lower) / ma * 100 else 0.0

                val currentPrice = stock.price

                // 突破判定：價格突破上軌
                val breakoutPct = if (upper > 0) (currentPrice - upper) / upper * 100 else 0.0
                if (breakoutPct < -1.0) continue // 允許 1% 容差（接近上軌也算）

                // 量能確認：今日成交量 > volConfirmRatio × 均量
                val avgVolume = volumes.takeLast(period).average()
                val todayVolume = stock.volume.toDouble()
                val volumeRatio = if (avgVolume > 0) todayVolume / avgVolume else 1.0
                if (volumeRatio < volConfirmRatio && breakoutPct > 0) continue // 突破時必須放量

                // Squeeze 檢測：當前帶寬是否處於近期低位
                val historicalBandWidths = mutableListOf<Double>()
                if (closes.size >= period + 5) {
                    for (offset in 0 until 5) {
                        val slice = closes.subList(offset, offset + period)
                        val sliceMa = slice.average()
                        val sliceVar = slice.map { (it - sliceMa).pow(2) }.average()
                        val sliceStd = sqrt(sliceVar)
                        val bw = if (sliceMa > 0) (sliceStd * 2 * stdMult) / sliceMa * 100 else 0.0
                        historicalBandWidths.add(bw)
                    }
                }
                val avgBandWidth = historicalBandWidths.average().takeIf { it > 0 } ?: bandWidth
                val isSqueeze = bandWidth < avgBandWidth * 0.7 // 帶寬收窄至均值 70% 以下

                // 評分
                val breakoutScore = when {
                    breakoutPct > 3.0 -> 40
                    breakoutPct > 1.5 -> 35
                    breakoutPct > 0.5 -> 30
                    breakoutPct > 0.0 -> 25
                    else -> 15 // 接近上軌但未突破
                }
                val volumeScore = when {
                    volumeRatio > 3.0 -> 35
                    volumeRatio > 2.0 -> 30
                    volumeRatio > 1.5 -> 25
                    volumeRatio > 1.2 -> 15
                    else -> 5
                }
                val squeezeScore = if (isSqueeze) 25 else when {
                    bandWidth < avgBandWidth * 0.85 -> 18
                    bandWidth < avgBandWidth -> 10
                    else -> 5
                }

                val strength = (breakoutScore + volumeScore + squeezeScore).coerceIn(0, 100)
                if (strength < strengthThreshold) continue

                val reason = buildString {
                    append("BB上軌${"%.2f".format(upper)}")
                    if (breakoutPct > 0) append(" 突破+${"%.1f".format(breakoutPct)}%")
                    else append(" 觸及")
                    append(" 量比${"%.1f".format(volumeRatio)}")
                    if (isSqueeze) append(" 帶寬收窄突破")
                }

                signals.add(StrategySignal(
                    strategyId = id, category = category,
                    stockCode = stock.code, stockName = stock.name,
                    strength = strength, reason = reason,
                    action = if (strength >= 60) SignalAction.BUY else SignalAction.WATCH,
                    currentPrice = currentPrice, changePercent = stock.changePercent
                ))
            } catch (_: Exception) { continue }
        }

        val result = signals.sortedByDescending { it.strength }.take(config.maxResults)
        Log.i("BB_Strategy", "計算完成: ${candidates.size} 候選 → ${result.size} 信號")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = result, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }
}
