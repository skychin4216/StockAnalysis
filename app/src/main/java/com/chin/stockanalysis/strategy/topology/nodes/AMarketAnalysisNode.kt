package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.market.AMarketAnalysisEngine
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 大盤K線分析 Pipeline 節點
 *
 * 封裝 AMarketAnalysisEngine，作為 DAG Pipeline 節點使用。
 * 分析上證指數 K 線 → 輸出大盤環境評估（冷/熱/溫和 + 建議週期 + 倉位）。
 *
 * 輸出 [AMarketAnalysisEngine.MarketAnalysisResult]，
 * 存入 context.stageOutputs["n_a_market"]，供下游節點（如實倉評估）讀取。
 */
class AMarketAnalysisNode : BaseNode<Any, AMarketAnalysisEngine.MarketAnalysisResult>(
    "n_a_market", "A股大盤K線分析", NodeType.FACTOR_COMPUTE
) {
    companion object {
        private const val TAG = "AMarketAnalysisNode"
    }

    override suspend fun execute(
        context: PipelineContext,
        input: Any
    ): AMarketAnalysisEngine.MarketAnalysisResult {
        Log.i(TAG, "開始大盤K線分析...")
        val result = AMarketAnalysisEngine.analyze(context.androidContext)
        Log.i(TAG, "大盤分析完成: ${result.summary}")
        return result
    }
}
