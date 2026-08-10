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
 * ## 换手率活跃策略（修正版）
 *
 * 直接使用 StockRealtime.turnoverRate 字段（来源于 DailySnapshotEntity.turnover_rate）。
 * 废除旧版用成交额代替换手率的错误逻辑。
 *
 * 筛选：换手率 3%-15%（过低不活跃，过高可能是出货）且涨幅 >= 1%
 * 评分：换手率适中度(40%) + 涨幅(30%) + 连续放量(30%)
 * 新增：连续 3 日换手率递增加分
 */
class TurnoverFilterStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "turnover_active"
    override var name = "换手率活跃策略"
    override var description = "换手率处于活跃区间且涨幅显著，连续放量确认资金持续关注"
    override val category = StrategyCategory.VOLUME
    override val holdingPeriods = listOf(HoldingPeriod.SHORT)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 24

    override val config = StrategyConfig.custom(
        params = mapOf("turnover_min" to 3.0, "turnover_max" to 15.0, "change_min" to 1.0),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("turnover", "换手率适中度", 40, "3-10%最佳区间"),
        WeightFactor("change", "涨幅得分", 30, "当日涨跌幅"),
        WeightFactor("continuity", "连续放量", 30, "近3日换手率递增")
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

        val marketDir = try { screener.detectMarketDirection() } catch (_: Exception) { "OSCILLATION" }
        val isBearish = marketDir == "BEARISH"
        val strengthThreshold = if (isBearish) 50 else 35
        val turnoverMin = (config.params["turnover_min"] as? Number)?.toDouble() ?: 3.0
        val turnoverMax = (config.params["turnover_max"] as? Number)?.toDouble() ?: 15.0
        val changeMin = (config.params["change_min"] as? Number)?.toDouble() ?: 1.0

        val db = StockDatabase.getInstance(screener.context)
        val dao = db.dailySnapshotDao()

        // 使用真实换手率过滤
        val candidates = pool.filter {
            it.turnoverRate >= turnoverMin &&
            it.turnoverRate <= turnoverMax &&
            it.changePercent >= changeMin &&
            it.price > 2.0 &&
            it.amount > 30_000_000
        }
        Log.i("TO_Strategy", "大盘: $marketDir, 候选: ${candidates.size}/${pool.size} (换手率${turnoverMin}-${turnoverMax}%)")

        val signals = mutableListOf<StrategySignal>()

        for (stock in candidates) {
            try {
                // 读取近 3 天历史换手率，检测连续放量
                val history = dao.getByCode(stock.code, 3)
                val histTurnovers = history.sortedBy { it.date }.map { it.turnoverRate }
                val isIncreasing = histTurnovers.size >= 3 &&
                    histTurnovers[2] > histTurnovers[1] && histTurnovers[1] > histTurnovers[0]

                // 换手率适中度评分 (0-40)：5-10% 最佳
                val turnoverScore = when {
                    stock.turnoverRate in 5.0..10.0 -> 40
                    stock.turnoverRate in 4.0..12.0 -> 32
                    stock.turnoverRate in 3.0..15.0 -> 24
                    else -> 15
                }

                // 涨幅评分 (0-30)
                val changeScore = when {
                    stock.changePercent > 7 -> 30
                    stock.changePercent > 5 -> 25
                    stock.changePercent > 3 -> 20
                    stock.changePercent > 2 -> 15
                    stock.changePercent > 1 -> 10
                    else -> 5
                }

                // 连续放量评分 (0-30)
                val continuityScore = when {
                    isIncreasing && stock.turnoverRate > (histTurnovers.firstOrNull() ?: 0.0) * 1.3 -> 30
                    isIncreasing -> 24
                    histTurnovers.size >= 2 && stock.turnoverRate > histTurnovers.last() -> 16
                    else -> 8
                }

                val strength = (turnoverScore + changeScore + continuityScore).coerceIn(0, 100)
                if (strength < strengthThreshold) continue

                val reason = buildString {
                    append("换手${"%.1f".format(stock.turnoverRate)}%")
                    append(" 涨${"%.1f".format(stock.changePercent)}%")
                    if (isIncreasing) append(" 连续放量")
                }

                signals.add(StrategySignal(
                    stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
                    strength = strength,
                    action = when { strength >= 75 -> SignalAction.BUY; strength >= 55 -> SignalAction.WATCH; else -> SignalAction.HOLD },
                    reason = reason,
                    currentPrice = stock.price, changePercent = stock.changePercent
                ))
            } catch (_: Exception) { continue }
        }

        val result = signals.sortedByDescending { it.strength }.take(config.maxResults)
        Log.i("TO_Strategy", "计算完成: ${candidates.size} 候选 → ${result.size} 信号")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = result, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }
}
