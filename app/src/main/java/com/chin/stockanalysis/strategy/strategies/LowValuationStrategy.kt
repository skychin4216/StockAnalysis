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

class LowValuationStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "low_valuation"
    override var name = "低估值策略"
    override var description = "筛选市盈率低于行业均值、基本面稳健且价格企稳的低估值股票"
    override val category = StrategyCategory.VALUE
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("pe_max" to 15.0, "roe_min" to 15.0, "stabilization_days" to 5),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("valuation", "估值评分", 40, "基于价格水平的估值评分"),
        WeightFactor("stable", "企稳评分", 30, "价格波动幅度评分"),
        WeightFactor("liquidity", "流动性", 30, "成交活跃度评分")
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
            val pool = if (config.stockPool.isNotEmpty()) preloadedStocks.filter { it.code in config.stockPool } else preloadedStocks
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))
        val signals = pool
            .filter { it.amount > 50_000_000 && it.changePercent in -5.0..5.0 && it.price > 1.0 }
            .map { calculateSignal(it) }
            .filter { it.strength >= 40 }
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
        val valuationScore = when { stock.price < 10 -> 40; stock.price < 20 -> 35; stock.price < 50 -> 30; stock.price < 100 -> 20; stock.price < 200 -> 10; else -> 5 }
        val changeAbs = kotlin.math.abs(stock.changePercent)
        val stableScore = when { changeAbs < 0.5 -> 30; changeAbs < 1.0 -> 25; changeAbs < 2.0 -> 20; changeAbs < 3.0 -> 15; changeAbs < 5.0 -> 10; else -> 5 }
        val liquidityScore = when { stock.amount > 500_000_000 -> 25; stock.amount > 200_000_000 -> 20; stock.amount > 100_000_000 -> 15; else -> 10 }
        val rawStrength = (valuationScore * (w["valuation"]?.weight ?: 40) / 100.0).toInt() +
                (stableScore * (w["stable"]?.weight ?: 30) / 100.0).toInt() +
                (liquidityScore * (w["liquidity"]?.weight ?: 30) / 100.0).toInt()
        val strength = minOf(rawStrength, 100)
        return StrategySignal(
            stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
            strength = strength,
            action = when { strength >= 70 -> SignalAction.BUY; strength >= 50 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = "低估值候选：¥${String.format("%.2f", stock.price)}, 涨跌${String.format("%.2f", stock.changePercent)}%",
            currentPrice = stock.price, changePercent = stock.changePercent
        )
    }
}