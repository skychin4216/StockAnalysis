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
 * ## 高开高走策略（动量类）
 *
 * 筛选条件:
 * 1. 高开 2% 以上 (price > yestClose * 1.02)
 * 2. 持续走强 (price > open * 1.01)
 * 3. 涨幅 > 3%
 */
class GapUpMomentumStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "gap_up_momentum"
    override var name = "高开高走策略"
    override var description = "高开2%以上且持续放量走强，捕捉动量突破机会"
    override val category = StrategyCategory.MOMENTUM
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("gap_min" to 2.0, "strength_min" to 1.0, "change_min" to 3.0),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("open_gap", "开盘强度", 40, "开盘涨幅评分"),
        WeightFactor("intra_day", "盘中动量", 40, "盘中持续走强评分"),
        WeightFactor("volume", "成交量", 20, "成交量活跃度")
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
        val step1 = pool.filter { it.yestClose > 0 && it.open > 0 && it.price > 0 }
        val step2 = step1.filter {
            val gapPct = (it.open - it.yestClose) / it.yestClose * 100
            val intraPct = (it.price - it.open) / it.open * 100
            gapPct >= 1.0 && intraPct >= 0.5 && it.changePercent >= 1.5
        }
        Log.i("GU_Strategy", "pool=${pool.size} → 基础过滤=${step1.size} → 高开高走(gap≥1% & intra≥0.5%)=${step2.size}")
        val step3 = step2.map { calculateSignal(it) }
        val step4 = step3.filter { it.strength >= 30 }
        Log.i("GU_Strategy", "打分后 strength>=30: ${step4.size}")
        val signals = step4.sortedByDescending { it.strength }.take(config.maxResults)
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    override suspend fun isAvailable(): Boolean = true

    private fun calculateSignal(stock: StockRealtime): StrategySignal {
        val w = weightFactors.associateBy { it.key }
        val gapPct = if (stock.yestClose > 0) ((stock.open - stock.yestClose) / stock.yestClose) * 100 else 0.0
        val intraPct = if (stock.open > 0) ((stock.price - stock.open) / stock.open) * 100 else 0.0
        val openScore = when { gapPct > 5 -> 40; gapPct > 3 -> 30; gapPct > 2 -> 20; gapPct > 1 -> 10; else -> 5 }
        val intraScore = when { intraPct > 5 -> 40; intraPct > 3 -> 30; intraPct > 2 -> 20; intraPct > 1 -> 10; else -> 5 }
        val volumeScore = if (stock.amount > 500_000_000) 20 else if (stock.amount > 100_000_000) 10 else 5
        val rawStrength = (openScore * (w["open_gap"]?.weight ?: 40) / 100.0).toInt() +
                (intraScore * (w["intra_day"]?.weight ?: 40) / 100.0).toInt() +
                (volumeScore * (w["volume"]?.weight ?: 20) / 100.0).toInt()
        val strength = minOf(rawStrength, 100)
        return StrategySignal(
            stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
            strength = strength,
            action = when { strength >= 80 -> SignalAction.BUY; strength >= 60 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = "高开${String.format("%.2f", gapPct)}%, 盘中涨${String.format("%.2f", intraPct)}%",
            currentPrice = stock.price, changePercent = stock.changePercent
        )
    }
}