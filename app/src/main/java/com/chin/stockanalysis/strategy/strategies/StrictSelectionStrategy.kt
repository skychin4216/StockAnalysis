package com.chin.stockanalysis.strategy.strategies

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 均线多头粘合选股策略（Pipeline-based）
 *
 * 复用 StockCheckPipeline 的均线粘合框架，对全市场扫描：
 * 1. 粘合度 ≤ 阈值
 * 2. 多头排列
 * 3. 粘合持续天数
 * 4. 量能条件
 * 5. 距高点跌幅
 * 6. MA60 上升
 * 7. 站稳年线
 *
 * 通过 ≥ minPassCount 项的股票作为选股信号输出。
 */
class StrictSelectionStrategy(
    private val screener: StockScreener,
    private val appContext: android.content.Context? = null
) : Strategy {

    override val id = "strict_selection"
    override var name = "均线粘合严选"
    override var description = "均线粘合选股：粘合度+多头排列+粘合持续+量能+跌幅+MA60+年线，≥7项通过方入选"
    override val category = StrategyCategory.VALUE
    override val holdingPeriods = listOf(HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("min_pass_count" to 7, "lookback_days" to 120, "convergence_threshold" to 2.5)
    )

    override val defaultStopLoss = -0.05f
    override val defaultTakeProfit = 0.10f
    override val maxPositions = 5

    override var weightFactors = listOf(
        WeightFactor("convergence", "粘合度", 25, "MA均线粘合度≤2.5%"),
        WeightFactor("bullish", "多头排列", 20, "MA5>MA10>MA20>MA60"),
        WeightFactor("duration", "粘合持续", 15, "粘合持续≥15天"),
        WeightFactor("volume", "量能条件", 15, "温和放量1.2-1.8倍"),
        WeightFactor("drawdown", "跌幅达标", 10, "距高点跌幅≥30%"),
        WeightFactor("ma60", "MA60上升", 10, "60日均线走平或上翘"),
        WeightFactor("year_line", "站稳年线", 5, "股价在250日均线上方")
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("需要 Context"))

        val pool = screener.scanFullMarket()
        doScreen(ctx, pool, startTime)
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        val ctx = appContext ?: return Result.failure(IllegalStateException("需要 Context"))
        return doScreen(ctx, preloadedStocks, startTime)
    }

    override suspend fun isAvailable() = true

    private suspend fun doScreen(
        ctx: android.content.Context,
        pool: List<StockRealtime>,
        startTime: Long
    ): Result<ScreeningResult> {
        return try {
            val pipeline = StockCheckPipeline.midTermParams()
            val signals = mutableListOf<StrategySignal>()

            val candidates = pool.take(80)
            for (stock in candidates) {
                try {
                    val result = pipeline.analyze(ctx, stock.code)
                    if (result.stockName == "数据不足" || result.stockName == "异常") continue
                    if (!result.passed) continue

                    val strength = if (result.totalChecks > 0)
                        (result.passCount * 100 / result.totalChecks).coerceAtMost(100) else 0
                    val details = buildMap {
                        put("passCount", "${result.passCount}/${result.totalChecks}")
                        put("convergence", "${"%.1f".format(result.convergenceDegree)}%")
                        put("bullish", if (result.bullishAligned) "✓" else "✗")
                        put("duration", "${result.convergenceDays}天")
                        put("volumeRatio", "${"%.1f".format(result.volumeRatio)}")
                        put("drawdown", "${"%.1f".format(result.drawdownPct)}%")
                    }

                    signals.add(
                        StrategySignal(
                            stockCode = stock.code,
                            stockName = result.stockName,
                            strategyId = id,
                            category = category,
                            strength = strength,
                            action = if (result.passed) SignalAction.BUY else SignalAction.WATCH,
                            reason = "粘合${"%.1f".format(result.convergenceDegree)}% ${result.passCount}/${result.totalChecks}: ${result.summary}",
                            details = details,
                            currentPrice = stock.price,
                            changePercent = stock.changePercent
                        )
                    )
                } catch (_: Exception) {}
            }

            Result.success(
                ScreeningResult(
                    strategyId = id,
                    strategyName = name,
                    category = category,
                    signals = signals.sortedByDescending { it.strength },
                    totalScanned = candidates.size,
                    scanTimeMs = System.currentTimeMillis() - startTime
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
