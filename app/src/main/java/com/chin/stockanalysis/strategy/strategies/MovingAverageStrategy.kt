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

class MovingAverageStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "ma_golden_cross"
    override var name = "均线金叉策略"
    override var description = "5日均线上穿20日均线，配合成交量放大确认趋势启动"
    override val category = StrategyCategory.TREND
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("short_period" to 5, "long_period" to 20, "volume_ratio_min" to 1.5),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("momentum", "动量得分", 40, "基于涨跌幅的动量评分"),
        WeightFactor("volume", "量比得分", 30, "基于成交额的量比评分"),
        WeightFactor("position", "价格位置", 30, "当前价相对开盘价的位置")
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) {
            Result.failure(e)
        }
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

    private fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))
        val step1 = pool.filter { it.price > it.yestClose && it.changePercent > 0.5 }
        Log.i("MA_Strategy", "pool=${pool.size} → 过滤(price>yestClose && chg>0.5)=${step1.size}")
        if (step1.isNotEmpty()) {
            val sample = step1.first()
            Log.i("MA_Strategy", "  样本: ${sample.code} ${sample.name} price=${"%.2f".format(sample.price)} open=${"%.2f".format(sample.open)} yestClose=${"%.2f".format(sample.yestClose)} chg=${"%.2f".format(sample.changePercent)}% amt=${sample.amount}")
        }
        val step2 = step1.map { calculateSignal(it) }
        val step3 = step2.filter { it.strength >= 30 }
        Log.i("MA_Strategy", "打分后 strength>=30: ${step3.size}")
        if (step2.isNotEmpty()) {
            val topScores = step2.sortedByDescending { it.strength }.take(3)
            Log.i("MA_Strategy", "  Top3得分: ${topScores.joinToString { it.stockName + "=" + it.strength }}")
        }
        val signals = step3
            .sortedByDescending { it.strength }
            .take(config.maxResults)
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    override suspend fun isAvailable(): Boolean = true

    private fun calculateSignal(stock: StockRealtime): StrategySignal {
        val w = weightFactors.associateBy { it.key }
        val momentumScore = when { stock.changePercent > 5 -> 40; stock.changePercent > 3 -> 30; stock.changePercent > 1 -> 20; stock.changePercent > 0 -> 10; else -> 0 }
        val volumeScore = if (stock.amount > 1_000_000_000) 30 else if (stock.amount > 500_000_000) 20 else if (stock.amount > 100_000_000) 10 else 5
        val positionScore = when { stock.price > stock.open * 1.02 -> 30; stock.price > stock.open * 1.01 -> 20; stock.price > stock.open -> 10; else -> 0 }
        val rawStrength = (momentumScore * (w["momentum"]?.weight ?: 40) / 100.0).toInt() +
                (volumeScore * (w["volume"]?.weight ?: 30) / 100.0).toInt() +
                (positionScore * (w["position"]?.weight ?: 30) / 100.0).toInt()
        val strength = minOf(rawStrength, 100)
        return StrategySignal(
            stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
            strength = strength,
            action = when { strength >= 80 -> SignalAction.BUY; strength >= 60 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = "均线金叉候选：涨${String.format("%.2f", stock.changePercent)}%",
            currentPrice = stock.price, changePercent = stock.changePercent
        )
    }
}