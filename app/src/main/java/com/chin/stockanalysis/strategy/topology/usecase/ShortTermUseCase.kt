package com.chin.stockanalysis.strategy.topology.usecase

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.topology.nodes.*
import com.chin.stockanalysis.strategy.data.SmartMoneyCache

/**
 * ## 短線量化 UseCase
 *
 * 對應 ShortTermQuantFragment.runPipeline() 的完整流程。
 * Pipeline：
 *   Stage 1: 數據準備（股票池 + 新聞因子並行）
 *   Stage 2: 策略篩選（並行）
 *   Stage 3: 合併 + 板塊加權（串行）
 *   Stage 4: AI 精選 + 主力資金過濾（串行）
 *
 * 特點：因子計算 → 策略 → 合併 → AI 精選 → 自動建倉。
 * 短線一定要熱點熱點熱點。
 */
class ShortTermUseCase(
    private val appContext: Context,
    private val strategies: List<Strategy>
) {

    companion object {
        private const val TAG = "ShortTermUseCase"
    }

    fun buildPipeline(): Pipeline {
        // ── Stage 1: 數據準備 ──
        val dataStage = Pipeline.Stage(
            name = "數據準備",
            parallel = true,
            linkLists = listOf(
                LinkList("市場上下文", links = listOf(
                    Link.root(MarketContextNode(), label = "init -> marketCtx")
                ))
            )
        )

        // ── Stage 2: 策略篩選（並行） ──
        val strategyLinkLists = strategies
            .filter { it.id != "ai_prediction" }
            .map { strategy ->
                LinkList("策略:${strategy.name}", links = listOf(
                    Link(StockPoolNode(), StrategyNode(strategy),
                        label = "stockPool → ${strategy.id}")
                ))
            }

        val strategyStage = Pipeline.Stage(
            name = "策略篩選",
            parallel = true,
            linkLists = strategyLinkLists
        )

        // ── Stage 3: 合併 + 板塊加權 ──
        val mergeStage = Pipeline.Stage(
            name = "合併加權",
            linkLists = listOf(
                LinkList("合併+板塊加權", links = listOf(
                    Link(SignalMergeNode(), SectorBoostNode(),
                        label = "signals → merged → boosted")
                ))
            )
        )

        // ── Stage 4: AI 精選 + 主力資金過濾 ──
        val tradeStage = Pipeline.Stage(
            name = "AI+主力過濾",
            linkLists = listOf(
                LinkList("AI精選", links = listOf(
                    Link(SectorBoostNode(), AIPredictNode(),
                        label = "boosted → aiPicks")
                )),
                LinkList("主力資金過濾", links = listOf(
                    Link(AIPredictNode(), SmartMoneyFilterNode(),
                        label = "aiPicks → filtered")
                ))
            )
        )

        return Pipeline(
            name = "短線量化Pipeline",
            description = "股票池 → 策略並行篩選 → 合併加權 → AI精選 → 主力資金過濾",
            stages = listOf(dataStage, strategyStage, mergeStage, tradeStage)
        )
    }

    suspend fun run(tradeDate: String): PipelineResult {
        val context = PipelineContext(
            tradeDate = tradeDate,
            androidContext = appContext,
            smartMoneyCache = SmartMoneyCache,
            config = PipelineConfig(
                orderType = "ShortTermQuant",
                maxHoldings = 3,
                maxSignalsPerStrategy = 15
            )
        )
        return buildPipeline().execute(context)
    }
}

