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

/**
 * ## RSI背离策略（参考 vnpy CtaStrategy）
 */
class RSIDivergenceStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "rsi_divergence"
    override var name = "RSI背离策略"
    override var description = "RSI超卖区间反弹，配合成交量确认底部反转"
    override val category = StrategyCategory.MOMENTUM
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("rsi_period" to 14, "oversold" to 30, "min_price" to 5.0),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("rsi", "RSI得分", 45, "RSI值越低越好"),
        WeightFactor("volume", "量能确认", 30, "成交量放大"),
        WeightFactor("momentum", "反转动量", 25, "价格反弹强度")
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
        val period = (config.params["rsi_period"] as? Number)?.toInt() ?: 14
        val minPrice = (config.params["min_price"] as? Number)?.toDouble() ?: 5.0
        val avgVolume = pool.map { s: StockRealtime -> s.volume }.average().takeIf { it > 0 } ?: 1.0

        val signals = pool.filter { it.price >= minPrice && it.volume > 0 }.map { stock ->
            val rsi = calculateRSI(stock, pool, period)
            val volumeRatio = stock.volume / avgVolume
            val momentum = if (stock.yestClose > 0) (stock.price - stock.yestClose) / stock.yestClose * 100 else 0.0
            val rsiScore = if (rsi in 1.0..30.0) 100.0 else if (rsi in 30.0..40.0) 80.0 else if (rsi in 40.0..50.0) 50.0 else 10.0
            val strength = ((rsiScore * weightFactors[0].weight +
                    kotlin.math.min(volumeRatio, 3.0) * 15 * weightFactors[1].weight +
                    kotlin.math.max(momentum, 0.0) * 1.5 * weightFactors[2].weight) / 100).toInt().coerceIn(0, 100)
            StrategySignal(strategyId = id, category = category, stockCode = stock.code, stockName = stock.name,
                strength = strength, reason = "RSI${"%.0f".format(rsi)} 量比${"%.1f".format(volumeRatio)} 动量${"%.1f".format(momentum)}%",
                action = if (strength >= 50) SignalAction.BUY else SignalAction.WATCH,
                currentPrice = stock.price, changePercent = stock.changePercent)
        }.filter { it.strength >= 30 }.sortedByDescending { it.strength }.take(config.maxResults)
        Log.i("RSI_Strategy", "pool=${pool.size} → 信号=${signals.size}")
        return Result.success(ScreeningResult(strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime))
    }

    private fun calculateRSI(stock: StockRealtime, pool: List<StockRealtime>, period: Int): Double {
        val ref = pool.takeIf { pool.size >= period } ?: return 50.0
        val samples: List<Double> = ref.sortedByDescending { s: StockRealtime -> s.timestamp }.take(period + 1).map { s: StockRealtime -> s.price }
        if (samples.size < period + 1) return 50.0
        var gain = 0.0; var loss = 0.0
        for (i in 1 until samples.size) {
            val diff = samples[i] - samples[i - 1]
            if (diff > 0) gain += diff else loss += (-diff)
        }
        if (gain + loss == 0.0) return 50.0
        val avgGain = gain / period; val avgLoss = loss / period
        val rs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }
}