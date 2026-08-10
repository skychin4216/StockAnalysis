package com.chin.stockanalysis.strategy.topology.pipelines

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.FreezingPointChecker
import com.chin.stockanalysis.strategy.analysis.MaConvergenceAnalyzer
import com.chin.stockanalysis.strategy.analysis.PricePositionAnalyzer
import com.chin.stockanalysis.strategy.analysis.StabilityChecker
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlin.math.abs

/**
 * ## 个股分析 Pipeline — 可参数化的 6+1 项检查
 *
 * 将严选条件 + 大盘均线检查包装为独立可复用的 Pipeline，
 * 不同周期可传入不同参数（如 PE 阈值、离散率阈值等）。
 *
 * ### 检查项目
 * 1. 大盘均线粘合向上（MaConvergenceAnalyzer）
 * 2. 个股均线粘合向上：MA5 > MA10 > MA20，离散率 < threshold
 * 3. 三日不新低
 * 4. 历史低位 percentile
 * 5. PE < peThreshold
 * 6. 周期活跃度
 * 7. 冰点买入（换手率 + 量比）
 *
 * ### 使用方式
 * ```kotlin
 * val pipeline = StockCheckPipeline(
 *     peThreshold = 30.0,
 *     maDivergenceThreshold = 0.03
 * )
 * val result = pipeline.analyze(context, "000001")
 * ```
 */
class StockCheckPipeline(
    /** PE 上限阈值 */
    val peThreshold: Double = 30.0,
    /** 个股均线离散率阈值 */
    val maDivergenceThreshold: Double = 0.03,
    /** 历史低位百分位（0.25 = 底部 25%） */
    val historicalLowPercentile: Double = 0.25,
    /** 活跃度要求：最少几天涨跌幅 > activeChangeThreshold */
    val activeDaysThreshold: Int = 3,
    /** 活跃度阈值：涨跌幅 > 此值算活跃（百分比，如 3.0 = 3%） */
    val activeChangeThreshold: Double = 3.0,
    /** 冰点换手率上限（%） */
    val turnoverThreshold: Double = 2.0,
    /** 冰点量比上限 */
    val volumeRatioThreshold: Double = 1.0,
    /** 回溯天数 */
    val lookbackDays: Int = 60,
    /** 大盘均线粘合阈值（MaConvergenceAnalyzer） */
    val marketMaThreshold: Double = 0.02,
    /** 通过所需最少项数（满 7 项） */
    val minPassCount: Int = 4
) {

    companion object {
        private const val TAG = "StockCheckPipeline"

        /** 超短线参数：更严格的 PE、更宽松的活跃度 */
        fun ultraShortParams() = StockCheckPipeline(
            peThreshold = 50.0,
            maDivergenceThreshold = 0.02,
            activeDaysThreshold = 5,
            activeChangeThreshold = 3.0,
            turnoverThreshold = 3.0,
            lookbackDays = 30,
            marketMaThreshold = 0.015,
            minPassCount = 4
        )

        /** 短线参数 */
        fun shortTermParams() = StockCheckPipeline(
            peThreshold = 40.0,
            maDivergenceThreshold = 0.025,
            activeDaysThreshold = 3,
            activeChangeThreshold = 3.0,
            turnoverThreshold = 2.5,
            lookbackDays = 40,
            marketMaThreshold = 0.02,
            minPassCount = 4
        )

        /** 中线参数（默认） */
        fun midTermParams() = StockCheckPipeline(
            peThreshold = 30.0,
            maDivergenceThreshold = 0.03,
            historicalLowPercentile = 0.25,
            activeDaysThreshold = 3,
            lookbackDays = 60,
            marketMaThreshold = 0.02,
            minPassCount = 4
        )

        /** 长线参数：更严格的 PE 和低位要求 */
        fun longTermParams() = StockCheckPipeline(
            peThreshold = 25.0,
            maDivergenceThreshold = 0.035,
            historicalLowPercentile = 0.20,
            activeDaysThreshold = 2,
            activeChangeThreshold = 2.0,
            lookbackDays = 120,
            marketMaThreshold = 0.025,
            minPassCount = 5
        )
    }

    /**
     * 个股分析结果
     */
    data class StockCheckResult(
        val stockCode: String,
        val stockName: String,
        /** 大盘均线是否粘合向上 */
        val marketMaConverged: Boolean = false,
        val marketMaDescription: String = "",
        /** 6 项个股检查详情 */
        val maConvergedUp: Boolean = false,
        val threeDayNoNewLow: Boolean = false,
        val historicalLow: Boolean = false,
        val peOk: Boolean = false,
        val cyclicalActive: Boolean = false,
        val freezingPoint: Boolean = false,
        val volumeRatioOk: Boolean = false,
        /** 通过项数（满 7 项：大盘 + 6 个股） */
        val passCount: Int = 0,
        /** 是否通过（passCount >= minPassCount） */
        val passed: Boolean = false,
        /** 当前价格 */
        val currentPrice: Double = 0.0,
        /** PE 值 */
        val pe: Double = 0.0,
        /** 换手率 */
        val turnoverRate: Double = 0.0,
        /** 量比 */
        val volumeRatio: Double = 0.0,
        /** 文字摘要 */
        val summary: String = ""
    )

    /**
     * 分析单只股票（Context 版本）
     */
    suspend fun analyze(context: Context, stockCode: String): StockCheckResult {
        val db = StockDatabase.getInstance(context)
        return analyze(db, stockCode)
    }

    /**
     * 分析单只股票（StockDatabase 版本，供 StrictSelectionChecker 等调用）
     */
    suspend fun analyze(db: StockDatabase, stockCode: String): StockCheckResult {
        return try {
            val snaps = db.dailySnapshotDao().getByCode(stockCode, lookbackDays + 10)
                .sortedBy { it.date }
            if (snaps.size < 20) {
                return StockCheckResult(stockCode, "数据不足", summary = "K线数据不足(${snaps.size}条)")
            }

            // 大盘均线检查
            val indexSnaps = db.dailySnapshotDao().getByCode("sh000001", 35)
            val marketMaResult = if (indexSnaps.size >= 20) {
                MaConvergenceAnalyzer.analyze(indexSnaps.sortedBy { it.date }, marketMaThreshold)
            } else {
                MaConvergenceAnalyzer.Result.empty()
            }

            analyzeSnaps(snaps, marketMaResult)
        } catch (e: Exception) {
            Log.e(TAG, "个股分析异常: $stockCode - ${e.message}", e)
            StockCheckResult(stockCode, "异常", summary = "分析异常: ${e.message}")
        }
    }

    /**
     * 用预载入的快照分析（不回查 DB，供 PipelineBacktestEngine 回溯用）
     * 不含大盘均线检查（回溯时大盘数据未必对齐）
     */
    fun analyzeSnaps(
        snaps: List<DailySnapshotEntity>,
        marketMaResult: MaConvergenceAnalyzer.Result = MaConvergenceAnalyzer.Result.empty()
    ): StockCheckResult {
        if (snaps.size < 20) {
            return StockCheckResult("", "数据不足", summary = "K线数据不足(${snaps.size}条)")
        }

        val latest = snaps.last()
        val closes = snaps.map { it.close }
        val stockCode = latest.code
        val name = latest.name

        // ═══ 1. 个股均线粘合向上 ═══
        val (_, maConvergedUp) = MaConvergenceAnalyzer.bullishConvergence(closes, maDivergenceThreshold)

        // ═══ 2. 三日不新低 ═══
        val threeDayNoNewLow = StabilityChecker.check(snaps, StabilityChecker.Mode.ASCENDING)

        // ═══ 3. 历史低位 ═══
        val positionInRange = PricePositionAnalyzer.fromCloses(closes, latest.close)
        val historicalLow = positionInRange <= historicalLowPercentile

        // ═══ 4. PE ═══
        val peOk = latest.pe > 0 && latest.pe < peThreshold

        // ═══ 5. 周期活跃度 ═══
        val activeDays = snaps.takeLast(lookbackDays).count {
            abs(it.changePct) > activeChangeThreshold
        }
        val cyclicalActive = activeDays >= activeDaysThreshold

        // ═══ 6. 冰点买入 ═══
        val fp = FreezingPointChecker.check(latest, snaps, turnoverThreshold, volumeRatioThreshold)

        // ═══ 统计通过项数 ═══
        var passCount = 0
        if (marketMaResult.convergedAndUp) passCount++
        if (maConvergedUp) passCount++
        if (threeDayNoNewLow) passCount++
        if (historicalLow) passCount++
        if (peOk) passCount++
        if (cyclicalActive) passCount++
        if (fp.isFreezing) passCount++

        val passed = passCount >= minPassCount

        return StockCheckResult(
            stockCode = stockCode,
            stockName = name,
            marketMaConverged = marketMaResult.convergedAndUp,
            marketMaDescription = marketMaResult.hint,
            maConvergedUp = maConvergedUp,
            threeDayNoNewLow = threeDayNoNewLow,
            historicalLow = historicalLow,
            peOk = peOk,
            cyclicalActive = cyclicalActive,
            freezingPoint = fp.turnoverOk,
            volumeRatioOk = fp.volumeRatioOk,
            passCount = passCount,
            passed = passed,
            currentPrice = latest.close,
            pe = latest.pe,
            turnoverRate = latest.turnoverRate,
            volumeRatio = fp.volumeRatio,
            summary = "$name(${stockCode.takeLast(4)}) 通过:$passCount/7"
        )
    }

    /**
     * 批量分析多只股票
     */
    suspend fun analyzeBatch(context: Context, stockCodes: List<String>): List<StockCheckResult> {
        return stockCodes.map { analyze(context, it) }
    }

    /**
     * 格式化结果为可读文字
     */
    fun formatResult(result: StockCheckResult): String {
        return buildString {
            appendLine("═══ ${result.stockName}(${result.stockCode}) ═══")
            appendLine("价格: ${result.currentPrice}  PE: ${result.pe}  换手率: ${result.turnoverRate}%")
            appendLine("量比: ${"%.2f".format(result.volumeRatio)}")
            appendLine()
            appendLine("大盘均线: ${if (result.marketMaConverged) "✅" else "⚠"} ${result.marketMaDescription}")
            appendLine("① 均线粘合向上: ${if (result.maConvergedUp) "✅" else "❌"}")
            appendLine("② 三日不新低:   ${if (result.threeDayNoNewLow) "✅" else "❌"}")
            appendLine("③ 历史低位25%:  ${if (result.historicalLow) "✅" else "❌"}")
            appendLine("④ PE<${peThreshold}:     ${if (result.peOk) "✅" else "❌"}")
            appendLine("⑤ 周期活跃度:   ${if (result.cyclicalActive) "✅" else "❌"}")
            appendLine("⑥ 冰点买入:     ${if (result.freezingPoint && result.volumeRatioOk) "✅" else "❌"} (换手${if (result.freezingPoint) "✓" else "✗"} 量比${if (result.volumeRatioOk) "✓" else "✗"})")
            appendLine()
            appendLine("通过: ${result.passCount}/7 ${if (result.passed) "→ ✅ 符合买入条件" else "→ ⚠ 未达标准"}")
        }
    }
}
