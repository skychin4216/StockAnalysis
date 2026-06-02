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
 * ## 换手率活跃策略（量价类）
 *
 * 筛选条件:
 * 1. 成交额 > 2亿（代理换手率高）
 * 2. 涨幅 > 1.5%
 * 3. 价格适中（10-200元）
 */
class TurnoverFilterStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "turnover_active"
    override var name = "换手率活跃策略"
    override var description = "成交活跃度高且涨幅显著的股票，适合短线关注"
    override val category = StrategyCategory.VOLUME
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("amount_min" to 200_000_000.0, "change_min" to 1.5, "price_max" to 200.0),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("turnover", "成交活跃", 50, "以成交额代理换手率评分"),
        WeightFactor("change", "涨幅得分", 30, "当日涨跌幅评分"),
        WeightFactor("price", "价格区间", 20, "价格适中程度评分")
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
        val step1 = pool.filter { it.amount > 50_000_000 && it.changePercent >= 1.0 && it.price > 2.0 && it.price < 300.0 }
        Log.i("TO_Strategy", "pool=${pool.size} → 过滤(amt>50M & chg>=1% & price2-300)=${step1.size}")
        val step2 = step1.map { calculateSignal(it) }
        val step3 = step2.filter { it.strength >= 30 }
        Log.i("TO_Strategy", "打分后 strength>=30: ${step3.size}")
        val signals = step3.sortedByDescending { it.strength }.take(config.maxResults)
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    override suspend fun isAvailable(): Boolean = true

    private fun calculateSignal(stock: StockRealtime): StrategySignal {
        val w = weightFactors.associateBy { it.key }
        val turnoverScore = when { stock.amount > 2_000_000_000 -> 50; stock.amount > 1_000_000_000 -> 40; stock.amount > 500_000_000 -> 30; stock.amount > 200_000_000 -> 20; stock.amount > 50_000_000 -> 10; else -> 5 }
        val changeScore = when { stock.changePercent > 7 -> 30; stock.changePercent > 5 -> 25; stock.changePercent > 3 -> 20; stock.changePercent > 1.5 -> 15; stock.changePercent > 1 -> 10; else -> 5 }
        val priceScore = when { stock.price in 10.0..100.0 -> 20; stock.price in 100.0..200.0 -> 15; stock.price in 2.0..300.0 -> 10; else -> 5 }
        val rawStrength = (turnoverScore * (w["turnover"]?.weight ?: 50) / 100.0).toInt() +
                (changeScore * (w["change"]?.weight ?: 30) / 100.0).toInt() +
                (priceScore * (w["price"]?.weight ?: 20) / 100.0).toInt()
        val strength = minOf(rawStrength, 100)
        return StrategySignal(
            stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
            strength = strength,
            action = when { strength >= 80 -> SignalAction.BUY; strength >= 60 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = "活跃股：涨${String.format("%.2f", stock.changePercent)}%, 成交${String.format("%.1f", stock.amount / 100_000_000)}亿",
            currentPrice = stock.price, changePercent = stock.changePercent
        )
    }
}