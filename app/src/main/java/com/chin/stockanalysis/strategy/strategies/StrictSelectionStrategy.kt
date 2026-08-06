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
 * ## 六項嚴選策略（Pipeline-based）
 *
 * 復用 StockCheckPipeline 的 6 項嚴選邏輯，對全市場掃描：
 * 1. 均線粘合向上
 * 2. 三日不新低
 * 3. 歷史低位
 * 4. PE < 30
 * 5. 周期活躍度
 * 6. 冰點買入
 *
 * 通過 ≥4 項的股票作為選股信號輸出。
 */
class StrictSelectionStrategy(
    private val screener: StockScreener,
    private val appContext: android.content.Context? = null
) : Strategy {

    override val id = "strict_selection"
    override var name = "六項嚴選"
    override var description = "復用 Pipeline 六項嚴選：均線粘合+三日不新低+歷史低位+PE<30+周期活躍+冰點買入，≥4項通過方入選"
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
        WeightFactor("ma_converge", "均線粘合", 20, "MA5>MA10>MA20離散率<3%"),
        WeightFactor("no_new_low", "三日不新低", 15, "最近3日最低價均>前低"),
        WeightFactor("hist_low", "歷史低位", 15, "60日區間底部25%"),
        WeightFactor("low_pe", "低PE", 20, "PE<30無泡沫"),
        WeightFactor("cyclical_active", "周期活躍", 15, "N日內≥3天漲跌>3%"),
        WeightFactor("freezing", "冰點買入", 15, "低換手+低量比")
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

            // 取前 80 只掃描（控制耗時）
            val candidates = pool.take(80)
            for (stock in candidates) {
                try {
                    val result = pipeline.analyze(ctx, stock.code)
                    if (result.stockName == "數據不足" || result.stockName == "異常") continue
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
                            reason = "嚴選${result.passCount}/6: ${result.summary}",
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
