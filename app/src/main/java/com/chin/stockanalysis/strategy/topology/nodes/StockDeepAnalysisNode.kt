package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.strategy.backtest.PeriodDeepAnalysisRunner
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext

/**
 * ## 四周期深度分析节点（stock_deep_analysis / n_periods）
 *
 * 豆包体系「个股深度分析」useCase 的核心节点：
 * 读取 stageOutputs["n_target"]（目标个股代码，由调用方 seed 注入），
 * 对当前个股执行超短/短/中/长 四周期深度打分（纯本地 [PeriodDeepAnalysisRunner]），
 * 并将公共研判层产出的大盘方向（n_adaptive）与风格环境（n_style_rotation）拼接进报告头部。
 *
 * XML 用法：`<node module="stock_deep_analysis" />`
 * 依赖 seed：n_target（个股代码字符串）。
 * 输出 [PeriodDeepAnalysisRunner.Result]，存入 stageOutputs["n_periods"]。
 */
class StockDeepAnalysisNode : BaseNode<Any, PeriodDeepAnalysisRunner.Result>(
    "n_periods", "四周期深度分析", NodeType.FACTOR_COMPUTE
) {

    override suspend fun execute(context: PipelineContext, input: Any): PeriodDeepAnalysisRunner.Result {
        val code = context.getStageOutput<String>("n_target")?.trim().orEmpty()
        if (code.isEmpty()) {
            val msg = "❌ 四周期深度分析：未指定目标个股（stageOutputs.n_target 为空）"
            context.log(nodeId, msg)
            return PeriodDeepAnalysisRunner.Result(emptyList(), null, 0, "HOLD", msg, 0)
        }

        val name = StockDataCenter.getStockName(code)
        val result = PeriodDeepAnalysisRunner().analyze(context.androidContext, code, name)

        // 拼接豆包体系公共研判层产出（大盘方向 / 风格环境）
        val direction = context.getStageOutput<MarketDirectionResult>("n_adaptive")?.direction
        val style = context.getStageOutput<StyleRotationResult>("n_style_rotation")?.styleLabel
        val report = buildString {
            if (direction != null || style != null) {
                appendLine("🧭 豆包体系环境研判")
                if (direction != null) appendLine("  📡 大盘方向：${directionText(direction)}")
                if (style != null) appendLine("  🧭 风格环境：$style")
                appendLine()
            }
            append(result.report)
        }

        context.recordStockFlow(
            nodeId = nodeId, nodeName = nodeName,
            inputCount = 1, outputCount = 1,
            filterCount = 0,
            filterReason = "四周期打分",
            inputCodes = emptyList(), outputCodes = listOf(code)
        )
        return result.copy(report = report)
    }

    private fun directionText(d: String) = when (d) {
        "BULLISH" -> "牛市（进攻）"
        "BEARISH" -> "熊市（防御）"
        else -> "震荡（均衡）"
    }
}
