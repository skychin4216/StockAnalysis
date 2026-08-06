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
 * ## 個股分析 Pipeline — 可參數化的 6+1 項檢查
 *
 * 將嚴選條件 + 大盤均線檢查包裝為獨立可復用的 Pipeline，
 * 不同周期可傳入不同參數（如 PE 閾值、離散率閾值等）。
 *
 * ### 檢查項目
 * 1. 大盤均線粘合向上（MaConvergenceAnalyzer）
 * 2. 個股均線粘合向上：MA5 > MA10 > MA20，離散率 < threshold
 * 3. 三日不新低
 * 4. 歷史低位 percentile
 * 5. PE < peThreshold
 * 6. 周期活躍度
 * 7. 冰點買入（換手率 + 量比）
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
    /** PE 上限閾值 */
    val peThreshold: Double = 30.0,
    /** 個股均線離散率閾值 */
    val maDivergenceThreshold: Double = 0.03,
    /** 歷史低位百分位（0.25 = 底部 25%） */
    val historicalLowPercentile: Double = 0.25,
    /** 活躍度要求：最少幾天漲跌幅 > activeChangeThreshold */
    val activeDaysThreshold: Int = 3,
    /** 活躍度閾值：漲跌幅 > 此值算活躍（百分比，如 3.0 = 3%） */
    val activeChangeThreshold: Double = 3.0,
    /** 冰點換手率上限（%） */
    val turnoverThreshold: Double = 2.0,
    /** 冰點量比上限 */
    val volumeRatioThreshold: Double = 1.0,
    /** 回溯天數 */
    val lookbackDays: Int = 60,
    /** 大盤均線粘合閾值（MaConvergenceAnalyzer） */
    val marketMaThreshold: Double = 0.02,
    /** 通過所需最少項數（滿 7 項） */
    val minPassCount: Int = 4
) {

    companion object {
        private const val TAG = "StockCheckPipeline"

        /** 超短線參數：更嚴格的 PE、更寬鬆的活躍度 */
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

        /** 短線參數 */
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

        /** 中線參數（默認） */
        fun midTermParams() = StockCheckPipeline(
            peThreshold = 30.0,
            maDivergenceThreshold = 0.03,
            historicalLowPercentile = 0.25,
            activeDaysThreshold = 3,
            lookbackDays = 60,
            marketMaThreshold = 0.02,
            minPassCount = 4
        )

        /** 長線參數：更嚴格的 PE 和低位要求 */
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
     * 個股分析結果
     */
    data class StockCheckResult(
        val stockCode: String,
        val stockName: String,
        /** 大盤均線是否粘合向上 */
        val marketMaConverged: Boolean = false,
        val marketMaDescription: String = "",
        /** 6 項個股檢查詳情 */
        val maConvergedUp: Boolean = false,
        val threeDayNoNewLow: Boolean = false,
        val historicalLow: Boolean = false,
        val peOk: Boolean = false,
        val cyclicalActive: Boolean = false,
        val freezingPoint: Boolean = false,
        val volumeRatioOk: Boolean = false,
        /** 通過項數（滿 7 項：大盤 + 6 個股） */
        val passCount: Int = 0,
        /** 是否通過（passCount >= minPassCount） */
        val passed: Boolean = false,
        /** 當前價格 */
        val currentPrice: Double = 0.0,
        /** PE 值 */
        val pe: Double = 0.0,
        /** 換手率 */
        val turnoverRate: Double = 0.0,
        /** 量比 */
        val volumeRatio: Double = 0.0,
        /** 文字摘要 */
        val summary: String = ""
    )

    /**
     * 分析單只股票（Context 版本）
     */
    suspend fun analyze(context: Context, stockCode: String): StockCheckResult {
        val db = StockDatabase.getInstance(context)
        return analyze(db, stockCode)
    }

    /**
     * 分析單只股票（StockDatabase 版本，供 StrictSelectionChecker 等調用）
     */
    suspend fun analyze(db: StockDatabase, stockCode: String): StockCheckResult {
        return try {
            val snaps = db.dailySnapshotDao().getByCode(stockCode, lookbackDays + 10)
                .sortedBy { it.date }
            if (snaps.size < 20) {
                return StockCheckResult(stockCode, "數據不足", summary = "K線數據不足(${snaps.size}條)")
            }

            // 大盤均線檢查
            val indexSnaps = db.dailySnapshotDao().getByCode("sh000001", 35)
            val marketMaResult = if (indexSnaps.size >= 20) {
                MaConvergenceAnalyzer.analyze(indexSnaps.sortedBy { it.date }, marketMaThreshold)
            } else {
                MaConvergenceAnalyzer.Result.empty()
            }

            analyzeSnaps(snaps, marketMaResult)
        } catch (e: Exception) {
            Log.e(TAG, "個股分析異常: $stockCode - ${e.message}", e)
            StockCheckResult(stockCode, "異常", summary = "分析異常: ${e.message}")
        }
    }

    /**
     * 用預載入的快照分析（不回查 DB，供 PipelineBacktestEngine 回溯用）
     * 不含大盤均線檢查（回溯時大盤數據未必對齊）
     */
    fun analyzeSnaps(
        snaps: List<DailySnapshotEntity>,
        marketMaResult: MaConvergenceAnalyzer.Result = MaConvergenceAnalyzer.Result.empty()
    ): StockCheckResult {
        if (snaps.size < 20) {
            return StockCheckResult("", "數據不足", summary = "K線數據不足(${snaps.size}條)")
        }

        val latest = snaps.last()
        val closes = snaps.map { it.close }
        val stockCode = latest.code
        val name = latest.name

        // ═══ 1. 個股均線粘合向上 ═══
        val (_, maConvergedUp) = MaConvergenceAnalyzer.bullishConvergence(closes, maDivergenceThreshold)

        // ═══ 2. 三日不新低 ═══
        val threeDayNoNewLow = StabilityChecker.check(snaps, StabilityChecker.Mode.ASCENDING)

        // ═══ 3. 歷史低位 ═══
        val positionInRange = PricePositionAnalyzer.fromCloses(closes, latest.close)
        val historicalLow = positionInRange <= historicalLowPercentile

        // ═══ 4. PE ═══
        val peOk = latest.pe > 0 && latest.pe < peThreshold

        // ═══ 5. 周期活躍度 ═══
        val activeDays = snaps.takeLast(lookbackDays).count {
            abs(it.changePct) > activeChangeThreshold
        }
        val cyclicalActive = activeDays >= activeDaysThreshold

        // ═══ 6. 冰點買入 ═══
        val fp = FreezingPointChecker.check(latest, snaps, turnoverThreshold, volumeRatioThreshold)

        // ═══ 統計通過項數 ═══
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
            summary = "$name(${stockCode.takeLast(4)}) 通過:$passCount/7"
        )
    }

    /**
     * 批量分析多只股票
     */
    suspend fun analyzeBatch(context: Context, stockCodes: List<String>): List<StockCheckResult> {
        return stockCodes.map { analyze(context, it) }
    }

    /**
     * 格式化結果為可讀文字
     */
    fun formatResult(result: StockCheckResult): String {
        return buildString {
            appendLine("═══ ${result.stockName}(${result.stockCode}) ═══")
            appendLine("價格: ${result.currentPrice}  PE: ${result.pe}  換手率: ${result.turnoverRate}%")
            appendLine("量比: ${"%.2f".format(result.volumeRatio)}")
            appendLine()
            appendLine("大盤均線: ${if (result.marketMaConverged) "✅" else "⚠"} ${result.marketMaDescription}")
            appendLine("① 均線粘合向上: ${if (result.maConvergedUp) "✅" else "❌"}")
            appendLine("② 三日不新低:   ${if (result.threeDayNoNewLow) "✅" else "❌"}")
            appendLine("③ 歷史低位25%:  ${if (result.historicalLow) "✅" else "❌"}")
            appendLine("④ PE<${peThreshold}:     ${if (result.peOk) "✅" else "❌"}")
            appendLine("⑤ 周期活躍度:   ${if (result.cyclicalActive) "✅" else "❌"}")
            appendLine("⑥ 冰點買入:     ${if (result.freezingPoint && result.volumeRatioOk) "✅" else "❌"} (換手${if (result.freezingPoint) "✓" else "✗"} 量比${if (result.volumeRatioOk) "✓" else "✗"})")
            appendLine()
            appendLine("通過: ${result.passCount}/7 ${if (result.passed) "→ ✅ 符合買入條件" else "→ ⚠ 未達標準"}")
        }
    }
}
