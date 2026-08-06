package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline

/**
 * ## 個股評估檢查節點（6 項嚴選）
 *
 * 對 MergedSignalPool 中的每只股票評估 6 項條件：
 * 1. 均線粘合向上：MA5 > MA10 > MA20，離散率 < threshold
 * 2. 三日不新低：最近 3 日最低價均 > 前低
 * 3. 歷史低位：當前價在 N 日區間底部 percentile
 * 4. PE < peThreshold：無泡沫
 * 5. 周期活躍度：N 日內 ≥activeDays 天漲跌幅 > activeChange%
 * 6. 冰點買入：換手率 < turnoverThreshold 且 量比 < volumeRatioThreshold
 *
 * ### XML 配置
 * 所有參數均可通過 XML `<config><param>` 傳入，不同周期配不同參數：
 * ```xml
 * <Node id="n_eval" module="stock_evaluation">
 *   <config>
 *     <param name="peThreshold" value="30.0" />
 *     <param name="maDivergenceThreshold" value="0.03" />
 *     <param name="lookbackDays" value="60" />
 *   </config>
 * </Node>
 * ```
 *
 * ### 輸出
 * [StockEvaluationResult] 存入 context.setStageOutput(nodeId, result)
 */
data class StockEvaluationResult(
    val passedStocks: Map<String, StockEvaluationDetail> = emptyMap(),
    val totalCount: Int = 0,
    val passedCount: Int = 0
)

data class StockEvaluationDetail(
    val code: String,
    val name: String,
    val maConvergedUp: Boolean = false,
    val threeDayNoNewLow: Boolean = false,
    val historicalLow25: Boolean = false,
    val peLow: Boolean = false,
    val cyclicalActive: Boolean = false,
    val freezingPoint: Boolean = false,
    val passCount: Int = 0,  // 通過幾項（滿 6 項）
    val allPassed: Boolean = false
)

class StockEvaluationNode(
    val peThreshold: Double = 30.0,
    val maDivergenceThreshold: Double = 0.03,
    val historicalLowPercentile: Double = 0.25,
    val activeDaysThreshold: Int = 3,
    val activeChangeThreshold: Double = 3.0,
    val turnoverThreshold: Double = 2.0,
    val volumeRatioThreshold: Double = 1.0,
    val lookbackDays: Int = 60,
    val marketMaThreshold: Double = 0.02,
    val minPassCount: Int = 4
) : BaseNode<MergedSignalPool, MergedSignalPool>("strict_selection", "六項嚴選檢查", NodeType.FILTER) {

    companion object {
        private const val TAG = "StockEvaluation"
    }

    /** 內部使用的 StockCheckPipeline（由構造參數構建） */
    private val pipeline = StockCheckPipeline(
        peThreshold = peThreshold,
        maDivergenceThreshold = maDivergenceThreshold,
        historicalLowPercentile = historicalLowPercentile,
        activeDaysThreshold = activeDaysThreshold,
        activeChangeThreshold = activeChangeThreshold,
        turnoverThreshold = turnoverThreshold,
        volumeRatioThreshold = volumeRatioThreshold,
        lookbackDays = lookbackDays,
        marketMaThreshold = marketMaThreshold,
        minPassCount = minPassCount
    )

    override suspend fun execute(context: PipelineContext, input: MergedSignalPool): MergedSignalPool {
        val passedMap = mutableMapOf<String, StockEvaluationDetail>()

        // 遍歷所有候選股票
        for ((code, _) in input.stockHits) {
            try {
                val result = pipeline.analyze(context.androidContext, code)
                if (result.stockName == "數據不足" || result.stockName == "異常") continue

                val name = input.stockNames[code] ?: result.stockName

                // 轉換為 StockEvaluationDetail（6 項，不含大盤 MA）
                val passCount = listOf(
                    result.maConvergedUp, result.threeDayNoNewLow, result.historicalLow,
                    result.peOk, result.cyclicalActive,
                    result.freezingPoint && result.volumeRatioOk
                ).count { it }

                val detail = StockEvaluationDetail(
                    code = code,
                    name = name,
                    maConvergedUp = result.maConvergedUp,
                    threeDayNoNewLow = result.threeDayNoNewLow,
                    historicalLow25 = result.historicalLow,
                    peLow = result.peOk,
                    cyclicalActive = result.cyclicalActive,
                    freezingPoint = result.freezingPoint && result.volumeRatioOk,
                    passCount = passCount,
                    allPassed = passCount == 6
                )

                if (passCount >= minPassCount) {
                    passedMap[code] = detail
                }

            } catch (_: Exception) {}
        }

        // 評估結果另存，供報告使用
        val evalResult = StockEvaluationResult(
            passedStocks = passedMap,
            totalCount = input.stockHits.size,
            passedCount = passedMap.count { it.value.allPassed }
        )
        context.setStageOutput(nodeId + "_eval", evalResult)

        context.log(nodeId, "$nodeName: ${input.stockHits.size} 只候選 → " +
            "${passedMap.size} 只通過≥${minPassCount}項，${evalResult.passedCount} 只全部通過")
        if (passedMap.isNotEmpty()) {
            val top3 = passedMap.values.sortedByDescending { it.passCount }.take(3)
            context.log(nodeId, "$nodeName TOP3: ${top3.joinToString { "${it.name}(${it.passCount}/6)" }}")
        }

        // 過濾信號池，只保留通過嚴選的股票
        val passedCodes = passedMap.keys
        val filteredSignals = input.boostedSignals.filter { it.stockCode in passedCodes }
        val filteredHits = input.stockHits.filterKeys { it in passedCodes }
        val filteredNames = input.stockNames.filterKeys { it in passedCodes }

        return MergedSignalPool(filteredHits, filteredNames, filteredSignals)
    }
}

/**
 * ## 個股評估單股檢查（供 Agent 分析流程調用）
 *
 * 從 [StockEvaluationNode] 提取核心邏輯，不依賴 PipelineContext。
 * Agent 完成分析後，作為最終買入關卡調用。
 */
object StockEvaluationChecker {

    suspend fun evaluate(
        stockCode: String,
        db: StockDatabase,
        pipeline: StockCheckPipeline = StockCheckPipeline.midTermParams()
    ): StockEvaluationDetail {
        val result = pipeline.analyze(db, stockCode)
        if (result.stockName == "數據不足" || result.stockName == "異常") {
            return StockEvaluationDetail(code = stockCode, name = result.stockName, passCount = 0)
        }

        val passCount = listOf(
            result.maConvergedUp, result.threeDayNoNewLow, result.historicalLow,
            result.peOk, result.cyclicalActive,
            result.freezingPoint && result.volumeRatioOk
        ).count { it }

        return StockEvaluationDetail(
            code = stockCode, name = result.stockName,
            maConvergedUp = result.maConvergedUp,
            threeDayNoNewLow = result.threeDayNoNewLow,
            historicalLow25 = result.historicalLow,
            peLow = result.peOk,
            cyclicalActive = result.cyclicalActive,
            freezingPoint = result.freezingPoint && result.volumeRatioOk,
            passCount = passCount,
            allPassed = passCount == 6
        )
    }

    /** 格式化成可讀摘要，供報告附加 */
    fun formatResult(detail: StockEvaluationDetail): String = buildString {
        val passed = detail.passCount >= 4
        append(if (passed) "✅" else "⚠️")
        append(" 嚴選檢查: ${detail.passCount}/6")
        if (detail.name.isNotBlank()) append(" (${detail.name})")
        appendLine()
        append("  ${if (detail.maConvergedUp) "✓" else "✗"}均線粘合向上")
        append("  ${if (detail.threeDayNoNewLow) "✓" else "✗"}三日不新低")
        append("  ${if (detail.historicalLow25) "✓" else "✗"}歷史低位")
        appendLine()
        append("  ${if (detail.peLow) "✓" else "✗"}PE<30")
        append("  ${if (detail.cyclicalActive) "✓" else "✗"}周期活躍")
        append("  ${if (detail.freezingPoint) "✓" else "✗"}冰點買入")
        if (!passed) appendLine("\n  ⚠️ 未達≥4項門檻，建議觀望")
    }
}
