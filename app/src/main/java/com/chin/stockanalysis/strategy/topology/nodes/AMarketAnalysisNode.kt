package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.market.AMarketAnalysisEngine
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 大盘K线分析 Pipeline 节点
 *
 * 封装 AMarketAnalysisEngine，作为 DAG Pipeline 节点使用。
 * 分析上证指数 K 线 → 输出大盘环境评估（冷/热/温和 + 建议周期 + 仓位）。
 *
 * 输出 [AMarketAnalysisEngine.MarketAnalysisResult]，
 * 存入 context.stageOutputs["n_a_market"]，供下游节点（如实仓评估）读取。
 */
class AMarketAnalysisNode : BaseNode<Any, AMarketAnalysisEngine.MarketAnalysisResult>(
    "n_a_market", "A股大盘K线分析", NodeType.FACTOR_COMPUTE
) {
    companion object {
        private const val TAG = "AMarketAnalysisNode"
    }

    override suspend fun execute(
        context: PipelineContext,
        input: Any
    ): AMarketAnalysisEngine.MarketAnalysisResult {
        Log.i(TAG, "开始大盘K线分析...")
        val result = AMarketAnalysisEngine.analyze(context.androidContext)
        Log.i(TAG, "大盘分析完成: ${result.summary}")
        return result
    }
}
