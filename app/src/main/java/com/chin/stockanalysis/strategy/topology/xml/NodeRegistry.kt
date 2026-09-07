package com.chin.stockanalysis.strategy.topology.xml

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.topology.nodes.*
import com.chin.stockanalysis.strategy.topology.pipelines.*

/**
 * ## Node 注册表
 *
 * 将 XML 中的 module 字符串映射到具体的 Node 实例。
 * XML 中 `<node module="market_context" />` → `NodeRegistry.createNode("market_context", config)`
 *
 * 全局单例，App 启动时注册，XML 解析时查找。
 */
object NodeRegistry {

    private const val TAG = "NodeRegistry"

    /** Node 工厂函数：module type + config Map → PipelineNode */
    private val factories = java.util.concurrent.ConcurrentHashMap<String, (Context, Map<String, String>) -> PipelineNode<*, *>>()

    /** 策略列表（由外部注入，XML 中 module="strategy:ma_golden_cross" 时使用） */
    private val strategyMap = java.util.concurrent.ConcurrentHashMap<String, Strategy>()

    /**
     * 注册内置 Node 工厂。
     */
    fun init(appContext: Context) {
        // 数据源
        register("market_context") { ctx, _ -> MarketContextNode() }
        register("stock_pool") { _, _ -> StockPoolNode() }

        // 过滤
        register("main_board_filter") { ctx, _ -> MainBoardFilterNode() }
        register("pool_filter") { ctx, _ -> StockPoolFilterNode() }

        // 聚合
        register("signal_merge") { ctx, _ -> SignalMergeNode() }

        // 增强
        register("sector_boost") { ctx, _ -> SectorBoostNode() }
        // 2026-09-05 宏观事件驱动（事件库节点，读 assets/macro_events/event_library.json）
        register("macro_event_bias") { ctx, config ->
            val scale = config["boostScale"]?.toDoubleOrNull() ?: 1.0
            MacroEventBiasNode(boostScale = scale)
        }
        register("bounce_reversal") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.BounceReversalNode() }
        // 2026-09-07 量价因子（相对沪深300强度 + 缩量档位/无量扣分 + 低价龙头加分）
        // 参数与 pipeline XML config 严格一致（Python 端 usecase_pipeline 同构）
        register("volume_price_factor") { _, config ->
            com.chin.stockanalysis.strategy.topology.nodes.VolumePriceFactorNode(
                mode = config["mode"] ?: "auto",
                benchmark = config["benchmark"] ?: "sh000300",
                relWinDays = config["relWinDays"]?.toIntOrNull() ?: 10,
                rsHi = config["rsHi"]?.toDoubleOrNull() ?: 3.0,
                rsBonus = config["rsBonus"]?.toDoubleOrNull() ?: 4.0,
                rsLo = config["rsLo"]?.toDoubleOrNull() ?: -2.0,
                rsPenalty = config["rsPenalty"]?.toDoubleOrNull() ?: -3.0,
                thinVr = config["thinVr"]?.toDoubleOrNull() ?: 0.5,
                thinPenalty = config["thinPenalty"]?.toDoubleOrNull() ?: -4.0,
                shrinkVr = config["shrinkVr"]?.toDoubleOrNull() ?: 0.8,
                shrinkPenalty = config["shrinkPenalty"]?.toDoubleOrNull() ?: -2.0,
                lowPriceMax = config["lowPriceMax"]?.toDoubleOrNull() ?: 10.0,
                leaderTopK = config["leaderTopK"]?.toIntOrNull() ?: 5,
                leaderBonus = config["leaderBonus"]?.toDoubleOrNull() ?: 3.0
            )
        }
        register("ancestral_rules") { _, config ->
            val period = config["holdingPeriod"] ?: "SHORT"
            com.chin.stockanalysis.strategy.topology.nodes.AncestralRulesNode(holdingPeriod = period)
        }
        register("inst_tips") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.InstitutionalTipsNode() }
        register("ma_convergence") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.MaConvergenceNode() }
        register("market_ma_check") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.MarketMaConvergenceCheckNode() }
        register("market_ma_unified") { _, config ->
            val threshold = config["threshold"]?.toDoubleOrNull() ?: 0.02
            val mode = config["checkMode"] ?: "full"
            com.chin.stockanalysis.strategy.topology.nodes.MarketMaUnifiedNode(threshold = threshold, checkMode = mode)
        }
        register("strict_selection") { _, config ->
            com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationNode(
                convergenceThreshold = config["convergenceThreshold"]?.toDoubleOrNull() ?: 2.5,
                useMA60 = config["useMA60"]?.toBooleanStrictOrNull() ?: true,
                convergenceDurationDays = config["convergenceDurationDays"]?.toIntOrNull() ?: 15,
                convergenceDurationRatio = config["convergenceDurationRatio"]?.toDoubleOrNull() ?: 0.8,
                volumeBreakoutRatio = config["volumeBreakoutRatio"]?.toDoubleOrNull() ?: 1.5,
                minChangePct = config["minChangePct"]?.toDoubleOrNull() ?: 0.0,
                requireChangePct = config["requireChangePct"]?.toBooleanStrictOrNull() ?: false,
                minDrawdownPct = config["minDrawdownPct"]?.toDoubleOrNull() ?: 30.0,
                requireMA60Rising = config["requireMA60Rising"]?.toBooleanStrictOrNull() ?: true,
                maRisingDays = config["maRisingDays"]?.toIntOrNull() ?: 5,
                requireMA250Rising = config["requireMA250Rising"]?.toBooleanStrictOrNull() ?: false,
                useMA250InBullish = config["useMA250InBullish"]?.toBooleanStrictOrNull() ?: false,
                requireCloseAboveConvergenceTop = config["requireCloseAboveConvergenceTop"]?.toBooleanStrictOrNull() ?: false,
                requireOpenBelowMAs = config["requireOpenBelowMAs"]?.toBooleanStrictOrNull() ?: false,
                requireVolumeShrink = config["requireVolumeShrink"]?.toBooleanStrictOrNull() ?: false,
                requireAboveYearLine = config["requireAboveYearLine"]?.toBooleanStrictOrNull() ?: false,
                requireAboveAllMAs = config["requireAboveAllMAs"]?.toBooleanStrictOrNull() ?: true,
                moderateVolumeLower = config["moderateVolumeLower"]?.toDoubleOrNull() ?: 1.2,
                moderateVolumeUpper = config["moderateVolumeUpper"]?.toDoubleOrNull() ?: 1.8,
                lookbackDays = config["lookbackDays"]?.toIntOrNull() ?: 120,
                minPassCount = config["minPassCount"]?.toIntOrNull() ?: 7,
                allowLowAmbush = config["allowLowAmbush"]?.toBooleanStrictOrNull() ?: false,
                allowQuietRise = config["allowQuietRise"]?.toBooleanStrictOrNull() ?: false,
                quietVolumeRatio = config["quietVolumeRatio"]?.toDoubleOrNull() ?: 1.0,
                macroSectorKeywords = config["macroSectorKeywords"]
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                period = config["period"] ?: ""
            )
        }
        register("base_position_guard") { _, config ->
            val period = config["holdingPeriod"] ?: "MID"
            com.chin.stockanalysis.strategy.topology.nodes.BasePositionGuardNode(holdingPeriod = period)
        }

        // 过滤（主力资金）
        register("smart_money_filter") { ctx, config ->
            val minScore = config["minScore"]?.toIntOrNull() ?: 55
            SmartMoneyFilterNode(minScore)
        }

        // K线形态侦测（非关键，透传输入）
        register("candle_pattern") { _, _ ->
            CandlePatternNode()
        }

        // AI
        register("ai_predict") { _, _ -> AIPredictNode() }

        // ══════════ 财务健康筛选（豆包第五部分：排雷 + 优选） ══════════
        register("financial_health") { _, config ->
            val minScore = config["minScore"]?.toIntOrNull() ?: 60
            val maxDebt = config["maxDebtRatio"]?.toDoubleOrNull() ?: 70.0
            com.chin.stockanalysis.strategy.topology.nodes.FinancialHealthNode(
                minScore = minScore, maxDebtRatio = maxDebt
            )
        }

        // ══════════ 中线量化独有 Node ══════════

        // 中线数据源
        register("adaptive_params") { ctx, _ -> AdaptiveParamsNode() }
        register("multi_period_hot") { ctx, config ->
            val onlyMain = config["onlyMainBoard"]?.toBooleanStrictOrNull() ?: true
            MultiPeriodHotNode(onlyMainBoard = onlyMain)
        }

        // 中线聚合
        register("cross_day_aggregation") { ctx, config ->
            val window = config["windowDays"]?.toIntOrNull() ?: 5
            val topN = config["topN"]?.toIntOrNull() ?: 20
            CrossDayAggregationNode(windowDays = window, topN = topN)
        }

        // 行业相对估值（2026-09-04 新增：科技成长按科技同侪/行业 PE 中位比较，传统行业按行业中位；
        // 只做加权不剔除，供中/长线增强层使用）
        register("sector_relative_pe") { ctx, config ->
            val topRatio = config["topRatio"]?.toDoubleOrNull() ?: 0.75
            val highRatio = config["highRatio"]?.toDoubleOrNull() ?: 2.0
            com.chin.stockanalysis.strategy.topology.nodes.SectorRelativePeNode(
                topRatio = topRatio, highRatio = highRatio)
        }

        // 中线增强
        register("news_strength") { ctx, config ->
            val days = config["lookbackDays"]?.toIntOrNull() ?: 3
            NewsStrengthNode(lookbackDays = days)
        }
        register("rotation_penalty") { ctx, config ->
            val threshold = config["thresholdDays"]?.toIntOrNull() ?: 3
            val penalty = config["penaltyPerExcess"]?.toIntOrNull() ?: 10
            RotationPenaltyNode(
                thresholdDays = threshold, penaltyPerExcess = penalty)
        }

        // 中线过滤
        register("news_guard") { ctx, config ->
            val impact = config["impactThreshold"]?.toIntOrNull() ?: 75
            val sentiment = config["sentimentThreshold"]?.toIntOrNull() ?: -30
            NewsGuardNode(
                impactThreshold = impact, sentimentThreshold = sentiment)
        }

        // 中线交易动作
        register("swap_weak") { ctx, config ->
            val maxH = config["maxHoldings"]?.toIntOrNull() ?: 5
            SwapWeakNode(maxHoldings = maxH)
        }
        register("holding_guard") { ctx, _ -> HoldingGuardNode() }

        // ══════════ 中线补齐 Node ══════════

        register("heat_score") { ctx, _ -> HeatScoreNode() }
        register("generate_orders") { ctx, config ->
            val maxH = config["maxHoldings"]?.toIntOrNull() ?: 5
            val orderType = config["orderType"] ?: "MidTermQuant"
            // 豆包体系四周期差异化仓位（账户%）：超短15/3、短波25/6、中线40/10、长线50/15
            val totalRatio = config["totalCapRatio"]?.toDoubleOrNull() ?: 12.5
            val singleRatio = config["singlePositionRatio"]?.toDoubleOrNull() ?: 5.0
            GenerateOrdersNode(
                maxHoldings = maxH,
                orderType = orderType,
                totalCapRatio = totalRatio,
                singlePositionRatio = singleRatio
            )
        }
        register("position_merge") { ctx, _ -> PositionMergeNode() }
        register("bg_manager") { ctx, _ -> BackgroundManagerNode() }
        register("fitting_save") { ctx, _ -> FittingSaveNode() }

        // ══════════ Hardcode 补齐 Node（HardcodeCompatNodes.kt） ══════════

        // 候选池过滤（所有周期共用）
        register("candidate_pool") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.CandidatePoolNode() }

        // Zipline 因子预计算（短线专用）
        register("zipline_factor") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.ZiplineFactorNode() }

        // 板块精选池（中线专用）
        register("sector_stock_pool") { _, config ->
            val dims = (config["hotDims"] ?: "today")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            com.chin.stockanalysis.strategy.topology.nodes.SectorStockPoolNode(hotDims = dims)
        }

        // 防守高息（中线/长线，熊市/震荡时启动）
        register("defensive_dividend") { _, config ->
            val maxPb = config["maxPb"]?.toDoubleOrNull() ?: 1.5
            val maxDebt = config["maxDebt"]?.toDoubleOrNull() ?: 70.0
            val maxCand = config["maxCandidates"]?.toIntOrNull() ?: 5
            val maxPerSec = config["maxPerSector"]?.toIntOrNull() ?: 2
            com.chin.stockanalysis.strategy.topology.nodes.DefensiveDividendNode(
                maxPb = maxPb, maxDebt = maxDebt, maxCandidates = maxCand, maxPerSector = maxPerSec)
        }

        // 数据导入检查（所有周期，Layer 0 首节点）
        register("data_import") { _, config ->
            val days = config["days"]?.toIntOrNull() ?: 60
            val minSnaps = config["minSnapshots"]?.toIntOrNull() ?: 100
            com.chin.stockanalysis.strategy.topology.nodes.DataImportNode(days = days, minSnapshots = minSnaps)
        }

        // 盘中 K 线分析（交易时段自动获取分钟线，计算 VWAP/均线/量能指标）
        register("intraday_analysis") { _, config ->
            val interval = config["intervalMin"]?.toIntOrNull() ?: 5
            val minBars = config["minBars"]?.toIntOrNull() ?: 5
            com.chin.stockanalysis.strategy.topology.nodes.IntradayAnalysisNode(
                intervalMin = interval, minBars = minBars
            )
        }

        // T+1 自动卖出（超短线专用）
        register("t1_auto_sell") { _, config ->
            val stopLoss = config["stopLossPct"]?.toDoubleOrNull() ?: -2.0
            val takeProfit = config["takeProfitPct"]?.toDoubleOrNull() ?: 3.0
            com.chin.stockanalysis.strategy.topology.nodes.T1AutoSellNode(
                stopLossPct = stopLoss, takeProfitPct = takeProfit
            )
        }

        // 跨 Tab 发布（短线/中线共用）
        register("crosstab_publish") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.CrossTabPublishNode() }

        // ══════════ 做T Pipeline Node（TTradePipelineNodes.kt） ══════════

        register("t_trade_import") { _, _ -> TTradeImportNode() }
        register("t_holdings_load") { _, config ->
            val pt = config["periodType"] ?: ""
            THoldingsLoadNode(periodType = pt)
        }
        register("t_inst_intent") { _, _ -> TInstIntentNode() }
        register("t_signal_synthesize") { _, config ->
            val minConf = config["minConfidence"]?.toDoubleOrNull() ?: 0.3
            TSignalSynthesizeNode(minConfidence = minConf)
        }
        register("t_recommend_save") { _, _ -> TRecommendSaveNode() }

        // ══════════ v6：先判方向再定周期 + 行业季节日历 + 龙头跟踪 ══════════
        register("direction_label") { _, config ->
            val exclude = config["exclude"] ?: "DOWNTREND,OSCILLATION"
            val penalty = config["penalty"]?.toIntOrNull() ?: 25
            com.chin.stockanalysis.strategy.topology.nodes.DirectionLabelNode(
                exclude = exclude.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                penalty = penalty
            )
        }
        register("seasonality_boost") { _, config ->
            val mult = config["multiplier"]?.toDoubleOrNull() ?: 1.0
            com.chin.stockanalysis.strategy.topology.nodes.SeasonalityBoostNode(multiplier = mult)
        }
        register("leader_track") { _, config ->
            val bonus = config["bonus"]?.toIntOrNull() ?: 8
            val onlyMain = config["onlyMainline"]?.toBooleanStrictOrNull() ?: true
            com.chin.stockanalysis.strategy.topology.nodes.LeaderTrackNode(
                bonus = bonus, onlyMainline = onlyMain)
        }

        // ══════════ 实仓分析 Pipeline Node ══════════
        register("real_holding_eval") { _, _ -> RealHoldingAnalysisNode() }
        register("a_market_analysis") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.AMarketAnalysisNode() }

        // ══════════ 市场公共研判 Pipeline（大盘多周期研判→风格轮动→板块强弱） ══════════
        register("style_rotation") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.StyleRotationNode() }
        register("sector_strength") { _, _ ->
            com.chin.stockanalysis.strategy.topology.nodes.SectorStrengthNode()
        }
        register("market_direction") { _, _ ->
            com.chin.stockanalysis.strategy.topology.nodes.MarketDirectionNode()
        }
        register("stock_deep_analysis") { _, _ ->
            com.chin.stockanalysis.strategy.topology.nodes.StockDeepAnalysisNode()
        }
        register("t_trade_eval") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.TTradeEvalNode() }
        register("holding_diagnostic") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.HoldingDiagnosticNode() }
        register("holding_prediction") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.HoldingPredictionNode() }
        register("sector_leader_analysis") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.SectorLeaderAnalysisNode() }
        register("market_sector_leaders") { _, _ -> com.chin.stockanalysis.strategy.topology.nodes.MarketSectorLeadersNode() }

        // ══════════ ETF 低位低吸（etf_dip usecase · 与三周期同构的 XML 单一源） ══════════
        // 规则/参数在 assets/usecases/etf_dip_pipeline.xml，APK 与 AutoQuant Python 引擎共用同一 XML
        register("etf_gate") { _, config ->
            com.chin.stockanalysis.strategy.topology.nodes.EtfGateNode(
                indexCode = config["indexCode"] ?: "sh000300",
                maFast = config["maFast"]?.toIntOrNull() ?: 20,
                maSlow = config["maSlow"]?.toIntOrNull() ?: 60,
                minSnapshots = config["minSnapshots"]?.toIntOrNull() ?: 60
            )
        }
        register("etf_dip_signal") { _, config ->
            com.chin.stockanalysis.strategy.topology.nodes.EtfDipSignalNode(
                ddLo = config["ddLo"]?.toDoubleOrNull() ?: -25.0,
                ddHi = config["ddHi"]?.toDoubleOrNull() ?: -12.0,
                rsiMax = config["rsiMax"]?.toDoubleOrNull() ?: 30.0,
                requireAbove250 = config["above250"]?.toBooleanStrictOrNull() ?: true,
                upCloseOrRsiTurn = config["upCloseOrRsiTurn"]?.toBooleanStrictOrNull() ?: true,
                notNew5 = config["notNew5"]?.toBooleanStrictOrNull() ?: true,
                ddWin = config["ddWin"]?.toIntOrNull() ?: 60,
                maYear = config["maYear"]?.toIntOrNull() ?: 250,
                minSnapshots = config["minSnapshots"]?.toIntOrNull() ?: 900,
                watchDdMax = config["watchDdMax"]?.toDoubleOrNull() ?: -8.0,
                watchRsiMax = config["watchRsiMax"]?.toDoubleOrNull() ?: 45.0
            )
        }
        register("etf_exit_policy") { _, config ->
            com.chin.stockanalysis.strategy.topology.nodes.EtfExitPolicyNode(
                tp = config["tp"]?.toDoubleOrNull() ?: 2.0,
                sl = config["sl"]?.toDoubleOrNull() ?: -6.0,
                hold = config["hold"]?.toIntOrNull() ?: 30,
                topApproach = config["topApproach"]?.toIntOrNull() ?: 8,
                topWatch = config["topWatch"]?.toIntOrNull() ?: 12,
                strategyText = config["strategyText"] ?: ""
            )
        }

        Log.i(TAG, "Node 注册完成: ${factories.keys}")
    }

    /**
     * 注册自定义 Node 工厂。
     */
    fun register(moduleType: String, factory: (Context, Map<String, String>) -> PipelineNode<*, *>) {
        factories[moduleType] = factory
    }

    /**
     * 注入策略列表（供 strategy:xxx 类型的 Node 使用）。
     */
    fun registerStrategies(strategies: List<Strategy>) {
        for (s in strategies) {
            strategyMap[s.id] = s
        }
        Log.i(TAG, "策略注册完成: ${strategyMap.keys}")
    }

    /**
     * 创建 Node 实例。
     *
     * @param moduleType XML 中的 module 属性值
     * @param config XML 中的 <param> 配置
     * @param context Android Context
     * @return Node 实例，失败返回 null
     */
    fun createNode(moduleType: String, config: Map<String, String>, context: Context): PipelineNode<*, *>? {
        // 策略 Node: module="strategy:ma_golden_cross"
        if (moduleType.startsWith("strategy:")) {
            val strategyId = moduleType.removePrefix("strategy:")
            val strategy = strategyMap[strategyId]
            if (strategy != null) {
                return StrategyNode(strategy)
            } else {
                Log.e(TAG, "未找到策略: $strategyId (可用: ${strategyMap.keys})")
                return null
            }
        }

        // 内置 Node
        val factory = factories[moduleType]
        if (factory != null) {
            return try {
                factory.invoke(context, config)
            } catch (e: Exception) {
                Log.e(TAG, "创建 Node 失败: $moduleType — ${e.message}")
                null
            }
        }

        Log.e(TAG, "未知的 module 类型: $moduleType")
        return null
    }

    /**
     * 列出所有已注册的 module 类型。
     */
    fun listModules(): Set<String> = factories.keys + strategyMap.keys.map { "strategy:$it" }
}
