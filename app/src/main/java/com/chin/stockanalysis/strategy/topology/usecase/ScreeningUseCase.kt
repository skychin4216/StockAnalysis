package com.chin.stockanalysis.strategy.topology.usecase

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.topology.nodes.*
import com.chin.stockanalysis.strategy.data.SmartMoneyCache

/**
 * ## 量化選股 UseCase
 *
 * 對應 StrategyListFragment.doExecute() 的完整流程。
 * Pipeline：
 *   Stage 1: 市場上下文 + 股票池加載（並行）
 *   Stage 2: 策略篩選（並行，每個策略一條 LinkList）
 *   Stage 3: 合併信號 + 板塊加權（串行）
 *
 * 特點：純篩選，無交易，AI 在 UI 層異步調用。
 */
class ScreeningUseCase(
    private val appContext: Context,
    private val strategies: List<Strategy>
) {

    companion object {
        private const val TAG = "ScreeningUseCase"
    }

    /**
     * 構建並返回 Pipeline 實例。
     */
    fun buildPipeline(): Pipeline {
        // ── Stage 1: 數據準備（市場上下文 + 股票池） ──
        val dataStage = Pipeline.Stage(
            name = "數據準備",
            parallel = true,
            linkLists = listOf(
                LinkList("市場上下文", "構建 StrategyMarketContext", links = listOf(
                    Link.root(MarketContextNode(), label = "init -> marketCtx")
                ))
            )
        )

        // ── Stage 2: 策略篩選（並行） ──
        val strategyLinkLists = strategies
            .filter { it.id != "ai_prediction" }
            .map { strategy ->
                LinkList(
                    name = "策略:${strategy.name}",
                    description = strategy.description,
                    links = listOf(
                        Link(
                            from = StockPoolNode(),
                            to = StrategyNode(strategy),
                            label = "stockPool → ${strategy.id}"
                        )
                    )
                )
            }

        val strategyStage = Pipeline.Stage(
            name = "策略篩選",
            parallel = true,
            linkLists = strategyLinkLists
        )

        // ── Stage 3: 合併 + 板塊加權 ──
        val mergeStage = Pipeline.Stage(
            name = "合併加權",
            parallel = false,
            linkLists = listOf(
                LinkList("合併+板塊加權", "多策略信號合併後板塊加權", links = listOf(
                    Link(
                        from = SignalMergeNode(),
                        to = SectorBoostNode(),
                        label = "signalPacks → mergedPool → boostedPool"
                    )
                ))
            )
        )

        return Pipeline(
            name = "量化選股Pipeline",
            description = "全市場掃描 → 多策略並行篩選 → 信號合併 → 板塊加權",
            stages = listOf(dataStage, strategyStage, mergeStage)
        )
    }

    /**
     * 執行量化選股。
     *
     * @param tradeDate 交易日（yyyy-MM-dd）
     * @return PipelineResult，finalOutput 為 MergedSignalPool
     */
    suspend fun run(tradeDate: String): PipelineResult {
        val context = PipelineContext(
            tradeDate = tradeDate,
            androidContext = appContext,
            smartMoneyCache = SmartMoneyCache
        )
        val pipeline = buildPipeline()
        return pipeline.execute(context)
    }
}

