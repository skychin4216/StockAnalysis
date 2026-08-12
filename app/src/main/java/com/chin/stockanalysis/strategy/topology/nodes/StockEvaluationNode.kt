package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline

/**
 * ## 个股评估检查节点（均线多头粘合选股）
 *
 * 对 MergedSignalPool 中的每只股票运行 StockCheckPipeline，
 * 基于粘合度为核心的多项检查，不同周期通过 XML 配不同参数。
 */
data class StockEvaluationResult(
    val passedStocks: Map<String, StockEvaluationDetail> = emptyMap(),
    val totalCount: Int = 0,
    val passedCount: Int = 0
)

data class StockEvaluationDetail(
    val code: String,
    val name: String,
    /** 粘合度（%） */
    val convergenceDegree: Double = 999.0,
    /** 粘合度达标 */
    val convergenceOk: Boolean = false,
    /** 多头排列 */
    val bullishAligned: Boolean = false,
    /** 粘合持续天数达标 */
    val convergenceDurationOk: Boolean = false,
    /** 量能条件达标 */
    val volumeConditionOk: Boolean = false,
    /** 距高点跌幅达标 */
    val drawdownOk: Boolean = false,
    /** MA60 上升 */
    val ma60Rising: Boolean = false,
    /** 站稳年线 */
    val aboveYearLine: Boolean = false,
    /** 涨幅达标 */
    val changePctOk: Boolean = true,
    /** 站上所有均线 */
    val aboveAllMAs: Boolean = true,
    /** 通过项数 */
    val passCount: Int = 0,
    /** 本周期适用检查总数 */
    val totalChecks: Int = 7,
    /** 是否通过 */
    val allPassed: Boolean = false
)

class StockEvaluationNode(
    val convergenceThreshold: Double = 2.5,
    val useMA60: Boolean = true,
    val convergenceDurationDays: Int = 15,
    val volumeBreakoutRatio: Double = 1.5,
    val minChangePct: Double = 0.0,
    val requireChangePct: Boolean = false,
    val minDrawdownPct: Double = 30.0,
    val requireMA60Rising: Boolean = true,
    val requireVolumeShrink: Boolean = false,
    val requireAboveYearLine: Boolean = false,
    val requireAboveAllMAs: Boolean = true,
    val moderateVolumeLower: Double = 1.2,
    val moderateVolumeUpper: Double = 1.8,
    val lookbackDays: Int = 120,
    val minPassCount: Int = 7
) : BaseNode<MergedSignalPool, MergedSignalPool>("strict_selection", "均线粘合严选", NodeType.FILTER) {

    companion object {
        private const val TAG = "StockEvaluation"
    }

    private val pipeline = StockCheckPipeline(
        convergenceThreshold = convergenceThreshold,
        useMA60 = useMA60,
        convergenceDurationDays = convergenceDurationDays,
        volumeBreakoutRatio = volumeBreakoutRatio,
        minChangePct = minChangePct,
        requireChangePct = requireChangePct,
        minDrawdownPct = minDrawdownPct,
        requireMA60Rising = requireMA60Rising,
        requireVolumeShrink = requireVolumeShrink,
        requireAboveYearLine = requireAboveYearLine,
        requireAboveAllMAs = requireAboveAllMAs,
        moderateVolumeLower = moderateVolumeLower,
        moderateVolumeUpper = moderateVolumeUpper,
        lookbackDays = lookbackDays,
        minPassCount = minPassCount
    )

    override suspend fun execute(context: PipelineContext, input: MergedSignalPool): MergedSignalPool {
        val passedMap = mutableMapOf<String, StockEvaluationDetail>()

        for ((code, _) in input.stockHits) {
            try {
                val result = pipeline.analyze(context.androidContext, code)
                if (result.stockName == "数据不足" || result.stockName == "异常") continue

                val name = input.stockNames[code] ?: result.stockName

                val detail = StockEvaluationDetail(
                    code = code,
                    name = name,
                    convergenceDegree = result.convergenceDegree,
                    convergenceOk = result.convergenceOk,
                    bullishAligned = result.bullishAligned,
                    convergenceDurationOk = result.convergenceDurationOk,
                    volumeConditionOk = result.volumeConditionOk,
                    drawdownOk = result.drawdownOk,
                    ma60Rising = result.ma60Rising,
                    aboveYearLine = result.aboveYearLine,
                    changePctOk = result.changePctOk,
                    aboveAllMAs = result.aboveAllMAs,
                    passCount = result.passCount,
                    totalChecks = result.totalChecks,
                    allPassed = result.passed
                )

                if (result.passed) {
                    passedMap[code] = detail
                }
            } catch (_: Exception) {}
        }

        val evalResult = StockEvaluationResult(
            passedStocks = passedMap,
            totalCount = input.stockHits.size,
            passedCount = passedMap.count { it.value.allPassed }
        )
        context.setStageOutput(nodeId + "_eval", evalResult)

        context.log(nodeId, "$nodeName: ${input.stockHits.size} 只候选 → " +
            "${passedMap.size} 只通过(≥${minPassCount}项)，${evalResult.passedCount} 只全部通过")
        if (passedMap.isNotEmpty()) {
            val top3 = passedMap.values.sortedByDescending { it.passCount }.take(3)
            context.log(nodeId, "$nodeName TOP3: ${top3.joinToString { "${it.name}(${it.passCount}/${it.totalChecks})" }}")
        }

        val passedCodes = passedMap.keys
        val filteredSignals = input.boostedSignals.filter { it.stockCode in passedCodes }
        val filteredHits = input.stockHits.filterKeys { it in passedCodes }
        val filteredNames = input.stockNames.filterKeys { it in passedCodes }

        return MergedSignalPool(filteredHits, filteredNames, filteredSignals)
    }
}

/**
 * ## 个股评估单股检查（供 Agent 分析流程调用）
 */
object StockEvaluationChecker {

    suspend fun evaluate(
        stockCode: String,
        db: StockDatabase,
        pipeline: StockCheckPipeline = StockCheckPipeline.midTermParams()
    ): StockEvaluationDetail {
        val result = pipeline.analyze(db, stockCode)
        if (result.stockName == "数据不足" || result.stockName == "异常") {
            return StockEvaluationDetail(code = stockCode, name = result.stockName, passCount = 0)
        }

        return StockEvaluationDetail(
            code = stockCode,
            name = result.stockName,
            convergenceDegree = result.convergenceDegree,
            convergenceOk = result.convergenceOk,
            bullishAligned = result.bullishAligned,
            convergenceDurationOk = result.convergenceDurationOk,
            volumeConditionOk = result.volumeConditionOk,
            drawdownOk = result.drawdownOk,
            ma60Rising = result.ma60Rising,
            aboveYearLine = result.aboveYearLine,
            changePctOk = result.changePctOk,
            aboveAllMAs = result.aboveAllMAs,
            passCount = result.passCount,
            totalChecks = result.totalChecks,
            allPassed = result.passed
        )
    }

    /** 格式化成可读摘要，供报告附加 */
    fun formatResult(detail: StockEvaluationDetail): String = buildString {
        val passed = detail.allPassed
        append(if (passed) "✅" else "⚠️")
        append(" 粘合选股: ${detail.passCount}/${detail.totalChecks}")
        if (detail.name.isNotBlank()) append(" (${detail.name})")
        appendLine()
        append("  ${if (detail.convergenceOk) "✓" else "✗"}粘合度${"%.1f".format(detail.convergenceDegree)}%")
        append("  ${if (detail.bullishAligned) "✓" else "✗"}多头排列")
        append("  ${if (detail.convergenceDurationOk) "✓" else "✗"}粘合持续")
        appendLine()
        append("  ${if (detail.volumeConditionOk) "✓" else "✗"}量能条件")
        append("  ${if (detail.drawdownOk) "✓" else "✗"}跌幅达标")
        append("  ${if (detail.ma60Rising) "✓" else "✗"}MA60上升")
        append("  ${if (detail.aboveYearLine) "✓" else "✗"}站稳年线")
        append("  ${if (detail.changePctOk) "✓" else "✗"}涨幅达标")
        append("  ${if (detail.aboveAllMAs) "✓" else "✗"}站上均线")
        if (!passed) appendLine("\n  ⚠️ 未达通过标准，建议观望")
    }
}
