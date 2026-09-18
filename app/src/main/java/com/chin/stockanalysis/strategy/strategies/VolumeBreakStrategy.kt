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
 * ## 放量突破策略（修正版）
 *
 * 使用真实量比（今日成交量 / 近 10 日均量）代替绝对成交额。
 * 解决旧版系统性偏好大盘股的问题。
 *
 * 筛选：量比 >= 2.0 且涨幅 >= 2% 且价格突破开盘价
 * 评分：量比(40%) + 突破幅度(30%) + 涨幅(30%)
 */
class VolumeBreakStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "volume_break"
    override var name = "放量突破策略"
    override var description = "成交量放大2倍以上，价格突破近期高点，确认强势突破信号"
    override val category = StrategyCategory.VOLUME
    override val holdingPeriods = listOf(HoldingPeriod.SHORT, HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 120

    override val config = StrategyConfig.custom(
        params = mapOf("volume_ratio_min" to 2.0, "change_percent_min" to 2.0, "lookback_days" to 10),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("volume_ratio", "量比得分", 40, "今日成交量/近10日均量"),
        WeightFactor("break", "突破强度", 30, "价格超出开盘价幅度"),
        WeightFactor("change", "涨幅得分", 30, "当日涨跌幅")
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

    override suspend fun isAvailable(): Boolean = true

    private suspend fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        val marketDirection = try { screener.detectMarketDirection() } catch (_: Exception) { "OSCILLATION" }
        val isBearish = marketDirection == "BEARISH"
        val dynamicChangeMin = if (isBearish) 3.0 else 2.0
        val dynamicStrengthThreshold = if (isBearish) 50 else 35
        val volumeRatioMin = (config.params["volume_ratio_min"] as? Number)?.toDouble() ?: 2.0
        val lookbackDays = (config.params["lookback_days"] as? Number)?.toInt() ?: 10

        val db = StockDatabase.getInstance(screener.context)
        val dao = db.dailySnapshotDao()

        // 预过滤：当日有涨幅、价格突破开盘价、有基本流动性
        val candidates = pool.filter {
            it.changePercent >= dynamicChangeMin &&
            it.price > it.open &&
            it.amount > 30_000_000 &&
            it.price > 2.0
        }
        Log.i("VB_Strategy", "大盘: $marketDirection, 候选: ${candidates.size}/${pool.size}, 量比门槛: $volumeRatioMin")

        val signals = mutableListOf<StrategySignal>()

        for (stock in candidates) {
            try {
                // 从 DB 读取近 lookbackDays 天历史成交量
                val history = dao.getByCode(stock.code, lookbackDays)
                if (history.isEmpty()) continue

                val avgVolume = history.map { it.volume.toDouble() }.average()
                if (avgVolume <= 0) continue

                // 计算真实量比
                val volumeRatio = stock.volume.toDouble() / avgVolume
                if (volumeRatio < volumeRatioMin) continue

                // 突破幅度：价格超出开盘价的百分比
                val breakPercent = if (stock.open > 0) (stock.price - stock.open) / stock.open * 100 else 0.0

                // 评分
                val volumeScore = when {
                    volumeRatio > 5.0 -> 40
                    volumeRatio > 3.5 -> 35
                    volumeRatio > 2.5 -> 30
                    volumeRatio > 2.0 -> 25
                    else -> 15
                }
                val breakScore = when {
                    breakPercent > 5 -> 30
                    breakPercent > 3 -> 25
                    breakPercent > 2 -> 20
                    breakPercent > 1 -> 15
                    breakPercent > 0 -> 10
                    else -> 0
                }
                val changeScore = when {
                    stock.changePercent > 7 -> 30
                    stock.changePercent > 5 -> 25
                    stock.changePercent > 3 -> 20
                    stock.changePercent > 2 -> 15
                    else -> 10
                }

                val strength = (volumeScore + breakScore + changeScore).coerceIn(0, 100)
                if (strength < dynamicStrengthThreshold) continue

                signals.add(StrategySignal(
                    stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
                    strength = strength,
                    action = when { strength >= 75 -> SignalAction.BUY; strength >= 55 -> SignalAction.WATCH; else -> SignalAction.HOLD },
                    reason = "量比${"%.1f".format(volumeRatio)} 涨${"%.1f".format(stock.changePercent)}% 突破${"%.1f".format(breakPercent)}%",
                    currentPrice = stock.price, changePercent = stock.changePercent
                ))
            } catch (_: Exception) { continue }
        }

        val result = signals.sortedByDescending { it.strength }.take(config.maxResults)
        Log.i("VB_Strategy", "计算完成: ${candidates.size} 候选 → ${result.size} 信号")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = result, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }
}
