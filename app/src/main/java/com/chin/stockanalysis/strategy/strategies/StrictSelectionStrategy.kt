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
 * ## 六项严选策略（Pipeline-based）
 *
 * 复用 StockCheckPipeline 的 6 项严选逻辑，对全市场扫描：
 * 1. 均线粘合向上
 * 2. 三日不新低
 * 3. 历史低位
 * 4. PE < 30
 * 5. 周期活跃度
 * 6. 冰点买入
 *
 * 通过 ≥4 项的股票作为选股信号输出。
 */
class StrictSelectionStrategy(
    private val screener: StockScreener,
    private val appContext: android.content.Context? = null
) : Strategy {

    override val id = "strict_selection"
    override var name = "六项严选"
    override var description = "复用 Pipeline 六项严选：均线粘合+三日不新低+历史低位+PE<30+周期活跃+冰点买入，≥4项通过方入选"
    override val category = StrategyCategory.VALUE
    override val holdingPeriods = listOf(HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("min_pass_count" to 4, "lookback_days" to 60, "pe_threshold" to 30.0)
    )

    override val defaultStopLoss = -0.05f
    override val defaultTakeProfit = 0.10f
    override val maxPositions = 5

    override var weightFactors = listOf(
        WeightFactor("ma_converge", "均线粘合", 20, "MA5>MA10>MA20离散率<3%"),
        WeightFactor("no_new_low", "三日不新低", 15, "最近3日最低价均>前低"),
        WeightFactor("hist_low", "历史低位", 15, "60日区间底部25%"),
        WeightFactor("low_pe", "低PE", 20, "PE<30无泡沫"),
        WeightFactor("cyclical_active", "周期活跃", 15, "N日内≥3天涨跌>3%"),
        WeightFactor("freezing", "冰点买入", 15, "低换手+低量比")
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

            // 取前 80 只扫描（控制耗时）
            val candidates = pool.take(80)
            for (stock in candidates) {
                try {
                    val result = pipeline.analyze(ctx, stock.code)
                    if (result.stockName == "数据不足" || result.stockName == "异常") continue
                    if (result.passCount < 4) continue

                    val strength = (result.passCount * 15).coerceAtMost(100)
                    val details = buildMap {
                        put("passCount", "${result.passCount}/6")
                        put("ma", if (result.maConvergedUp) "✓" else "✗")
                        put("noNewLow", if (result.threeDayNoNewLow) "✓" else "✗")
                        put("histLow", if (result.historicalLow) "✓" else "✗")
                        put("pe", "${"%.1f".format(result.pe)}")
                        put("turnover", "${"%.1f".format(result.turnoverRate)}%")
                    }

                    signals.add(
                        StrategySignal(
                            stockCode = stock.code,
                            stockName = result.stockName,
                            strategyId = id,
                            category = category,
                            strength = strength,
                            action = if (result.passCount >= 5) SignalAction.BUY else SignalAction.WATCH,
                            reason = "严选${result.passCount}/6: ${result.summary}",
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
