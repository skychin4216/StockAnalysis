package com.chin.stockanalysis.strategy.strategies

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VolumeBreakStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "volume_break"
    override var name = "放量突破策略"
    override var description = "成交量放大2倍以上，价格突破近期高点，确认强势突破信号"
    override val category = StrategyCategory.VOLUME
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("volume_ratio_min" to 2.0, "break_percent_min" to 1.0, "change_percent_min" to 2.0),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("volume", "量比得分", 40, "成交额规模评分"),
        WeightFactor("break", "突破强度", 30, "价格超出开盘价幅度评分"),
        WeightFactor("change", "涨幅得分", 30, "当日涨跌幅评分")
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            if (pool.isEmpty()) return@withContext Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
            ))
            val signals = pool
                .filter { it.changePercent >= 2.0 && it.price > it.open && it.amount > 10_000_000 }
                .map { calculateSignal(it) }
                .filter { it.strength >= 50 }
                .sortedByDescending { it.strength }
                .take(config.maxResults)
            Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun isAvailable(): Boolean = true

    private fun calculateSignal(stock: StockRealtime): StrategySignal {
        val w = weightFactors.associateBy { it.key }
        val volumeScore = when { stock.amount > 2_000_000_000 -> 40; stock.amount > 1_000_000_000 -> 30; stock.amount > 500_000_000 -> 20; stock.amount > 200_000_000 -> 10; else -> 5 }
        val breakPercent = if (stock.open > 0) ((stock.price - stock.open) / stock.open) * 100 else 0.0
        val breakScore = when { breakPercent > 5 -> 30; breakPercent > 3 -> 25; breakPercent > 2 -> 20; breakPercent > 1 -> 15; breakPercent > 0 -> 10; else -> 0 }
        val changeScore = when { stock.changePercent > 7 -> 30; stock.changePercent > 5 -> 25; stock.changePercent > 3 -> 20; stock.changePercent > 2 -> 15; else -> 10 }
        val rawStrength = (volumeScore * (w["volume"]?.weight ?: 40) / 100.0).toInt() +
                (breakScore * (w["break"]?.weight ?: 30) / 100.0).toInt() +
                (changeScore * (w["change"]?.weight ?: 30) / 100.0).toInt()
        val strength = minOf(rawStrength, 100)
        return StrategySignal(
            stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
            strength = strength,
            action = when { strength >= 80 -> SignalAction.BUY; strength >= 60 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = "放量突破：涨${String.format("%.2f", stock.changePercent)}%, 突破${String.format("%.2f", breakPercent)}%",
            currentPrice = stock.price, changePercent = stock.changePercent
        )
    }
}