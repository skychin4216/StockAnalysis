package com.chin.stockanalysis.strategy.topology.xml

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## Node 註冊表
 *
 * 將 XML 中的 module 字符串映射到具體的 Node 實例。
 * XML 中 `<node module="market_context" />` → `NodeRegistry.createNode("market_context", config)`
 *
 * 全局單例，App 啟動時註冊，XML 解析時查找。
 */
object NodeRegistry {

    private const val TAG = "NodeRegistry"

    /** Node 工廠函數：module type + config Map → PipelineNode */
    private val factories = java.util.concurrent.ConcurrentHashMap<String, (Context, Map<String, String>) -> PipelineNode<*, *>>()

    /** 策略列表（由外部注入，XML 中 module="strategy:ma_golden_cross" 時使用） */
    private val strategyMap = java.util.concurrent.ConcurrentHashMap<String, Strategy>()

    /**
     * 註冊內置 Node 工廠。
     */
    fun init(appContext: Context) {
        // 數據源
        register("market_context") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.MarketContextNode() }
        register("stock_pool") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.StockPoolNode() }

        // 過濾
        register("main_board_filter") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.MainBoardFilterNode() }

        // 聚合
        register("signal_merge") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.SignalMergeNode() }

        // 增強
        register("sector_boost") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.SectorBoostNode() }
        register("bounce_reversal") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.BounceReversalNode() }
        register("ancestral_rules") { _, config ->
            val period = config["holdingPeriod"] ?: "SHORT"
            com.chin.stockanalysis.strategy.topology.nodes.AncestralRulesNode(holdingPeriod = period)
        }
        register("inst_tips") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.InstitutionalTipsNode() }
        register("ma_convergence") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.MaConvergenceNode() }
        register("base_position_guard") { _, config ->
            val period = config["holdingPeriod"] ?: "MID"
            com.chin.stockanalysis.strategy.topology.nodes.BasePositionGuardNode(holdingPeriod = period)
        }

        // 過濾（主力資金）
        register("smart_money_filter") { ctx, config ->
            val minScore = config["minScore"]?.toIntOrNull() ?: 55
            com.chin.stockanalysis.strategy.topology.nodes.SmartMoneyFilterNode(minScore)
        }

        // K線形態偵測（非關鍵，透傳輸入）
        register("candle_pattern") { _, _ ->
            com.chin.stockanalysis.strategy.topology.nodes.CandlePatternNode()
        }

        // AI
        register("ai_predict") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.AIPredictNode() }

        // ══════════ 中線量化獨有 Node ══════════

        // 中線數據源
        register("adaptive_params") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.AdaptiveParamsNode() }
        register("multi_period_hot") { ctx, config ->
            val onlyMain = config["onlyMainBoard"]?.toBooleanStrictOrNull() ?: true
            com.chin.stockanalysis.strategy.topology.nodes.MultiPeriodHotNode(onlyMainBoard = onlyMain)
        }

        // 中線聚合
        register("cross_day_aggregation") { ctx, config ->
            val window = config["windowDays"]?.toIntOrNull() ?: 5
            val topN = config["topN"]?.toIntOrNull() ?: 20
            com.chin.stockanalysis.strategy.topology.nodes.CrossDayAggregationNode(windowDays = window, topN = topN)
        }

        // 中線增強
        register("news_strength") { ctx, config ->
            val days = config["lookbackDays"]?.toIntOrNull() ?: 3
            com.chin.stockanalysis.strategy.topology.nodes.NewsStrengthNode(lookbackDays = days)
        }
        register("rotation_penalty") { ctx, config ->
            val threshold = config["thresholdDays"]?.toIntOrNull() ?: 3
            val penalty = config["penaltyPerExcess"]?.toIntOrNull() ?: 10
            com.chin.stockanalysis.strategy.topology.nodes.RotationPenaltyNode(
                thresholdDays = threshold, penaltyPerExcess = penalty)
        }

        // 中線過濾
        register("news_guard") { ctx, config ->
            val impact = config["impactThreshold"]?.toIntOrNull() ?: 75
            val sentiment = config["sentimentThreshold"]?.toIntOrNull() ?: -30
            com.chin.stockanalysis.strategy.topology.nodes.NewsGuardNode(
                impactThreshold = impact, sentimentThreshold = sentiment)
        }

        // 中線交易動作
        register("swap_weak") { ctx, config ->
            val maxH = config["maxHoldings"]?.toIntOrNull() ?: 5
            com.chin.stockanalysis.strategy.topology.nodes.SwapWeakNode(maxHoldings = maxH)
        }
        register("holding_guard") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.HoldingGuardNode() }

        // ══════════ 中線補齊 Node ══════════

        register("heat_score") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.HeatScoreNode() }
        register("generate_orders") { ctx, config ->
            val maxH = config["maxHoldings"]?.toIntOrNull() ?: 5
            val orderType = config["orderType"] ?: "MidTermQuant"
            com.chin.stockanalysis.strategy.topology.nodes.GenerateOrdersNode(maxHoldings = maxH, orderType = orderType)
        }
        register("position_merge") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.PositionMergeNode() }
        register("bg_manager") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.BackgroundManagerNode() }
        register("fitting_save") { ctx, _ -> com.chin.stockanalysis.strategy.topology.nodes.FittingSaveNode() }

        // ══════════ Hardcode 補齊 Node（HardcodeCompatNodes.kt） ══════════

        // 候選池過濾（所有周期共用）
        register("candidate_pool") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.CandidatePoolNode() }

        // Zipline 因子預計算（短線專用）
        register("zipline_factor") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.ZiplineFactorNode() }

        // 板塊精選池（中線專用）
        register("sector_stock_pool") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.SectorStockPoolNode() }

        // 防守高息（中線/長線，熊市/震盪時啟動）
        register("defensive_dividend") { _, config ->
            val maxPb = config["maxPb"]?.toDoubleOrNull() ?: 1.5
            val maxDebt = config["maxDebt"]?.toDoubleOrNull() ?: 70.0
            val maxCand = config["maxCandidates"]?.toIntOrNull() ?: 5
            com.chin.stockanalysis.strategy.topology.nodes.DefensiveDividendNode(
                maxPb = maxPb, maxDebt = maxDebt, maxCandidates = maxCand)
        }

        // 數據導入檢查（所有周期，Layer 0 首節點）
        register("data_import") { _, config ->
            val days = config["days"]?.toIntOrNull() ?: 60
            val minSnaps = config["minSnapshots"]?.toIntOrNull() ?: 100
            com.chin.stockanalysis.strategy.topology.nodes.DataImportNode(days = days, minSnapshots = minSnaps)
        }

        // T+1 自動賣出（超短線專用）
        register("t1_auto_sell") { _, config ->
            val stopLoss = config["stopLossPct"]?.toDoubleOrNull() ?: -2.0
            val takeProfit = config["takeProfitPct"]?.toDoubleOrNull() ?: 3.0
            com.chin.stockanalysis.strategy.topology.nodes.T1AutoSellNode(
                stopLossPct = stopLoss, takeProfitPct = takeProfit
            )
        }

        // 跨 Tab 發布（短線/中線共用）
        register("crosstab_publish") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.CrossTabPublishNode() }

        // ══════════ 做T Pipeline Node（TTradePipelineNodes.kt） ══════════

        register("t_trade_import") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.TTradeImportNode() }
        register("t_holdings_load") { _, config ->
            val pt = config["periodType"] ?: ""
            com.chin.stockanalysis.strategy.topology.nodes.THoldingsLoadNode(periodType = pt)
        }
        register("t_inst_intent") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.TInstIntentNode() }
        register("t_signal_synthesize") { _, config ->
            val minConf = config["minConfidence"]?.toDoubleOrNull() ?: 0.3
            com.chin.stockanalysis.strategy.topology.nodes.TSignalSynthesizeNode(minConfidence = minConf)
        }
        register("t_recommend_save") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.TRecommendSaveNode() }

        Log.i(TAG, "Node 註冊完成: ${factories.keys}")
    }

    /**
     * 註冊自定義 Node 工廠。
     */
    fun register(moduleType: String, factory: (Context, Map<String, String>) -> PipelineNode<*, *>) {
        factories[moduleType] = factory
    }

    /**
     * 注入策略列表（供 strategy:xxx 類型的 Node 使用）。
     */
    fun registerStrategies(strategies: List<Strategy>) {
        for (s in strategies) {
            strategyMap[s.id] = s
        }
        Log.i(TAG, "策略註冊完成: ${strategyMap.keys}")
    }

    /**
     * 創建 Node 實例。
     *
     * @param moduleType XML 中的 module 屬性值
     * @param config XML 中的 <param> 配置
     * @param context Android Context
     * @return Node 實例，失敗返回 null
     */
    fun createNode(moduleType: String, config: Map<String, String>, context: Context): PipelineNode<*, *>? {
        // 策略 Node: module="strategy:ma_golden_cross"
        if (moduleType.startsWith("strategy:")) {
            val strategyId = moduleType.removePrefix("strategy:")
            val strategy = strategyMap[strategyId]
            if (strategy != null) {
                return com.chin.stockanalysis.strategy.topology.nodes.StrategyNode(strategy)
            } else {
                Log.e(TAG, "未找到策略: $strategyId (可用: ${strategyMap.keys})")
                return null
            }
        }

        // 內置 Node
        val factory = factories[moduleType]
        if (factory != null) {
            return try {
                factory.invoke(context, config)
            } catch (e: Exception) {
                Log.e(TAG, "創建 Node 失敗: $moduleType — ${e.message}")
                null
            }
        }

        Log.e(TAG, "未知的 module 類型: $moduleType")
        return null
    }

    /**
     * 列出所有已註冊的 module 類型。
     */
    fun listModules(): Set<String> = factories.keys + strategyMap.keys.map { "strategy:$it" }
}
