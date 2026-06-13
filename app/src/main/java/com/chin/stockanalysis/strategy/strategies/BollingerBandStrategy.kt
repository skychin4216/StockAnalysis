package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
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
 * ## 布林带突破策略（参考 Backtrader/vnpy）
 */
class BollingerBandStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "bollinger_band"
    override var name = "布林带突破策略"
    override var description = "股价突破布林带上轨且成交量放大，趋势确认信号"
    override val category = StrategyCategory.TREND
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("period" to 20, "std_mult" to 2.0, "volume_min" to 1e8),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("breakout", "突破强度", 40, "距离上轨的偏移"),
        WeightFactor("volume", "量能确认", 35, "成交量放大倍数"),
        WeightFactor("bandwidth", "带宽位置", 25, "布林带宽度分位数")
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

    private fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))
        val period = (config.params["period"] as? Number)?.toInt() ?: 20
        val volMin = (config.params["volume_min"] as? Number)?.toDouble() ?: 1e8

        val signals = pool.filter { it.volume * it.price > volMin }.map { stock ->
            val bb = calculateBollinger(stock, pool, period)
            val breakout = if (bb.mid > 0) (stock.price - bb.upper) / bb.mid * 100 else 0.0
            val avgVol = pool.map { s: StockRealtime -> s.volume }.average().takeIf { it > 0 } ?: 1.0
            val volumeConfirm = if (stock.volume > 0) stock.volume / avgVol else 1.0
            val strength = ((kotlin.math.max(breakout + 5.0, 0.0) * weightFactors[0].weight +
                    kotlin.math.min(volumeConfirm, 3.0) * 10 * weightFactors[1].weight +
                    (50 - bb.bandWidth.coerceIn(0.0, 100.0)) * 0.3 * weightFactors[2].weight) / 100).toInt().coerceIn(0, 100)
            StrategySignal(strategyId = id, category = category, stockCode = stock.code, stockName = stock.name,
                strength = strength, reason = "布林带上轨${"%.2f".format(bb.upper)} 突破${if(breakout>0)"+" else ""}${"%.2f".format(breakout)}%",
                action = if (strength >= 40) SignalAction.BUY else SignalAction.WATCH,
                currentPrice = stock.price, changePercent = stock.changePercent)
        }.filter { it.strength >= 25 }.sortedByDescending { it.strength }.take(config.maxResults)
        Log.i("BB_Strategy", "pool=${pool.size} → 信号=${signals.size}")
        return Result.success(ScreeningResult(strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime))
    }

    data class BollingerResult(val mid: Double, val upper: Double, val lower: Double, val bandWidth: Double)

    private fun calculateBollinger(stock: StockRealtime, pool: List<StockRealtime>, period: Int): BollingerResult {
        val ref = pool.takeIf { pool.size >= period } ?: return BollingerResult(0.0, 0.0, 0.0, 0.0)
        val samples = ref.sortedByDescending { s: StockRealtime -> s.timestamp }.take(period).map { s: StockRealtime -> s.price }
        if (samples.size < period) return BollingerResult(0.0, 0.0, 0.0, 0.0)
        val ma = samples.average()
        val variance = samples.map { v: Double -> (v - ma).pow(2) }.average()
        val std = sqrt(variance)
        val upper = ma + 2.0 * std
        val lower = ma - 2.0 * std
        val bandWidth = if (ma > 0) (upper - lower) / ma * 100 else 0.0
        return BollingerResult(ma, upper, lower, bandWidth)
    }
}