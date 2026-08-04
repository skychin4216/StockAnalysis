package com.chin.stockanalysis.strategy.topology.core

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.MaConvergenceAnalyzer
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
     * 分析單只股票
     */
    suspend fun analyze(context: Context, stockCode: String): StockCheckResult {
        val db = StockDatabase.getInstance(context)
        return try {
            val snaps = db.dailySnapshotDao().getByCode(stockCode, lookbackDays + 10)
                .sortedBy { it.date }
            if (snaps.size < 20) {
                return StockCheckResult(stockCode, "數據不足", summary = "K線數據不足(${snaps.size}條)")
            }

            val latest = snaps.last()
            val closes = snaps.map { it.close }
            val name = latest.name

            // ═══ 1. 大盤均線檢查 ═══
            val indexSnaps = db.dailySnapshotDao().getByCode("sh000001", 35)
            val marketMaResult = if (indexSnaps.size >= 20) {
                MaConvergenceAnalyzer.analyze(indexSnaps.sortedBy { it.date }, marketMaThreshold)
            } else {
                MaConvergenceAnalyzer.Result.empty()
            }

            // ═══ 2. 個股均線粘合向上 ═══
            val ma5 = closes.takeLast(5).average()
            val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else ma5
            val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else ma5
            val divergence = if (ma20 > 0) (ma5 - ma20) / ma20 else 1.0
            val maConvergedUp = ma5 > ma10 && ma10 > ma20 && abs(divergence) < maDivergenceThreshold

            // ═══ 3. 三日不新低 ═══
            val recent3 = snaps.takeLast(3)
            val threeDayNoNewLow = if (recent3.size >= 3) {
                val lows = recent3.map { it.low }
                lows[0] <= lows[1] && lows[1] <= lows[2]
            } else false

            // ═══ 4. 歷史低位 ═══
            val highN = closes.maxOrNull() ?: latest.close
            val lowN = closes.minOrNull() ?: latest.close
            val range = highN - lowN
            val positionInRange = if (range > 0) (latest.close - lowN) / range else 0.5
            val historicalLow = positionInRange <= historicalLowPercentile

            // ═══ 5. PE ═══
            val peOk = latest.pe > 0 && latest.pe < peThreshold

            // ═══ 6. 周期活躍度 ═══
            val activeDays = snaps.takeLast(lookbackDays).count {
                abs(it.changePct) > activeChangeThreshold
            }
            val cyclicalActive = activeDays >= activeDaysThreshold

            // ═══ 7. 冰點買入 ═══
            val turnoverOk = latest.turnoverRate < turnoverThreshold && latest.turnoverRate > 0
            val avgVolume5 = if (snaps.size >= 6) {
                snaps.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
            } else latest.volume.toDouble()
            val volRatio = if (avgVolume5 > 0) latest.volume / avgVolume5 else 1.0
            val volumeRatioOk = volRatio < volumeRatioThreshold

            // ═══ 統計通過項數 ═══
            var passCount = 0
            if (marketMaResult.convergedAndUp) passCount++
            if (maConvergedUp) passCount++
            if (threeDayNoNewLow) passCount++
            if (historicalLow) passCount++
            if (peOk) passCount++
            if (cyclicalActive) passCount++
            if (turnoverOk && volumeRatioOk) passCount++

            val passed = passCount >= minPassCount

            val summary = buildString {
                append("$name(${stockCode.takeLast(4)}) ")
                append("價格:${"%.2f".format(latest.close)} PE:${"%.1f".format(latest.pe)} ")
                append("通過:$passCount/7 ")
                if (passed) append("✅ 符合買入") else append("⚠ 未達標")
            }

            StockCheckResult(
                stockCode = stockCode,
                stockName = name,
                marketMaConverged = marketMaResult.convergedAndUp,
                marketMaDescription = marketMaResult.hint,
                maConvergedUp = maConvergedUp,
                threeDayNoNewLow = threeDayNoNewLow,
                historicalLow = historicalLow,
                peOk = peOk,
                cyclicalActive = cyclicalActive,
                freezingPoint = turnoverOk,
                volumeRatioOk = volumeRatioOk,
                passCount = passCount,
                passed = passed,
                currentPrice = latest.close,
                pe = latest.pe,
                turnoverRate = latest.turnoverRate,
                volumeRatio = volRatio,
                summary = summary
            )
        } catch (e: Exception) {
            Log.e(TAG, "個股分析異常: $stockCode - ${e.message}", e)
            StockCheckResult(stockCode, "異常", summary = "分析異常: ${e.message}")
        }
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
