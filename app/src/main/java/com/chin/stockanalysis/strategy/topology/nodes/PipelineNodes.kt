package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.data.SmartMoneyCache
import com.chin.stockanalysis.strategy.data.StrategyDataFeed
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.FilterResult
import com.chin.stockanalysis.strategy.topology.core.FilteredStockInfo
import com.chin.stockanalysis.strategy.topology.core.MergedSignalPool
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import com.chin.stockanalysis.strategy.topology.core.SignalPack
import com.chin.stockanalysis.strategy.topology.core.StockPool
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.sector.StrategyMarketContext

// ════════════════════════════════════════════════════════════════════════════
//  1. MarketContextNode (DATA_SOURCE)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 市場上下文構建節點
 *
 * 構建 [StrategyMarketContext]，作為 Pipeline 的第一個數據源節點。
 * 構建結果會同時存入 [PipelineContext.marketContext] 供後續節點使用。
 *
 * @property forceRefresh 是否強制刷新緩存（默認 false，使用 5 分鐘 TTL 緩存）
 */
class MarketContextNode(
    private val forceRefresh: Boolean = false
) : PipelineNode<Unit, StrategyMarketContext> {

    override val nodeId: String = "market_context"
    override val nodeName: String = "市場上下文構建"
    override val nodeType: NodeType = NodeType.DATA_SOURCE

    override suspend fun execute(context: PipelineContext, input: Unit): StrategyMarketContext {
        return try {
            val marketContext = StrategyMarketContext.build(
                context.androidContext,
                context.tradeDate,
                forceRefresh
            )
            // 注入到共享上下文供後續節點使用
            context.marketContext = marketContext
            context.log(nodeId, "市場上下文構建完成: ${marketContext.summary()}")
            marketContext
        } catch (e: Exception) {
            context.log(nodeId, "市場上下文構建失敗: ${e.message}")
            throw e
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  2. StockPoolNode (DATA_SOURCE)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 股票池構建節點
 *
 * 根據市場上下文中的板塊信息，從全市場股票中過濾出目標股票池。
 * 若市場上下文中定義了用戶關注板塊，優先匹配相關板塊的股票；
 * 否則返回全市場股票（可通過 [PipelineConfig.onlyMainBoard] 控制是否只保留主板）。
 */
class StockPoolNode : PipelineNode<StrategyMarketContext, StockPool> {

    override val nodeId: String = "stock_pool"
    override val nodeName: String = "股票池構建"
    override val nodeType: NodeType = NodeType.DATA_SOURCE

    override suspend fun execute(context: PipelineContext, input: StrategyMarketContext): StockPool {
        return try {
            val feed = StrategyDataFeed(context.androidContext)
            val allStocks = feed.prepareFromDb(
                date = context.tradeDate,
                config = StrategyDataFeed.DataFeedConfig(
                    onlyMainBoard = context.config.onlyMainBoard
                )
            )

            // 如果有用戶關注板塊或熱門板塊，嘗試按板塊名稱匹配過濾
            val sectorKeywords = input.userFocusSectors + input.todayHotSectors
            val filteredStocks = if (sectorKeywords.isNotEmpty()) {
                allStocks.filter { stock ->
                    sectorKeywords.any { keyword ->
                        stock.name.contains(keyword) || keyword.contains(stock.name.take(2))
                    }
                }
            } else {
                allStocks
            }

            // 如果板塊過濾後為空，回退到全市場
            val finalStocks = if (filteredStocks.isEmpty() && sectorKeywords.isNotEmpty()) {
                context.log(nodeId, "板塊匹配無結果，回退到全市場 (${allStocks.size} 只)")
                allStocks
            } else {
                filteredStocks
            }

            val pool = StockPool(
                stocks = finalStocks,
                source = if (sectorKeywords.isNotEmpty()) "sector_filtered" else "market_all",
                totalCount = allStocks.size,
                filterReason = if (finalStocks.size < allStocks.size) {
                    "按板塊關鍵詞 ${sectorKeywords.size} 個過濾"
                } else ""
            )

            context.setStageOutput(nodeId, pool)
            context.log(nodeId, "股票池構建完成: ${pool.size}/${pool.totalCount} 只, source=${pool.source}")
            pool
        } catch (e: Exception) {
            context.log(nodeId, "股票池構建失敗: ${e.message}")
            throw e
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  3. StrategyNode (STRATEGY)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 策略篩選節點
 *
 * 包裝現有 [Strategy] 接口，將其接入 Pipeline 數據流。
 * 接收 [StockPool]，調用策略的 `screenWithData()` 方法，將結果轉換為 [SignalPack]。
 *
 * @property strategy 被包裝的量化選股策略實例
 */
class StrategyNode(
    internal val strategy: Strategy
) : PipelineNode<StockPool, SignalPack> {

    override val nodeId: String = "strategy_${strategy.id}"
    override val nodeName: String = "策略: ${strategy.name}"
    override val nodeType: NodeType = NodeType.STRATEGY

    override suspend fun execute(context: PipelineContext, input: StockPool): SignalPack {
        return try {
            if (input.isEmpty) {
                context.log(nodeId, "股票池為空，跳過策略 ${strategy.name}")
                return SignalPack(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    signals = emptyList()
                )
            }

            val result = strategy.screenWithData(input.stocks)

            val signals = result.fold(
                onSuccess = { screeningResult ->
                    context.log(
                        nodeId,
                        "策略 ${strategy.name} 篩選完成: 掃描 ${screeningResult.totalScanned} 只, " +
                            "命中 ${screeningResult.hitCount} 只, 耗時 ${screeningResult.scanTimeMs}ms"
                    )
                    screeningResult.signals
                },
                onFailure = { e ->
                    context.log(nodeId, "策略 ${strategy.name} 執行失敗: ${e.message}")
                    emptyList()
                }
            )

            // 限制最大信號數
            val limitedSignals = signals.take(context.config.maxSignalsPerStrategy)

            SignalPack(
                strategyId = strategy.id,
                strategyName = strategy.name,
                signals = limitedSignals
            )
        } catch (e: Exception) {
            context.log(nodeId, "策略節點 ${strategy.name} 異常: ${e.message}")
            SignalPack(
                strategyId = strategy.id,
                strategyName = strategy.name,
                signals = emptyList()
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  4. SignalMergeNode (AGGREGATION)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 信號聚合節點
 *
 * 將多個策略的 [SignalPack] 合併為一個 [MergedSignalPool]。
 * 按股票代碼聚合，記錄每隻股票被哪些策略命中及其強度。
 */
class SignalMergeNode : PipelineNode<List<SignalPack>, MergedSignalPool> {

    override val nodeId: String = "signal_merge"
    override val nodeName: String = "多策略信號聚合"
    override val nodeType: NodeType = NodeType.AGGREGATION

    override suspend fun execute(context: PipelineContext, input: List<SignalPack>): MergedSignalPool {
        return try {
            val stockHits = mutableMapOf<String, MutableList<Pair<String, Int>>>()
            val stockNames = mutableMapOf<String, String>()
            val allSignals = mutableListOf<StrategySignal>()

            for (pack in input) {
                for (signal in pack.signals) {
                    // 聚合命中記錄
                    stockHits.getOrPut(signal.stockCode) { mutableListOf() }
                        .add(pack.strategyId to signal.strength)
                    // 記錄股票名稱
                    if (!stockNames.containsKey(signal.stockCode)) {
                        stockNames[signal.stockCode] = signal.stockName
                    }
                    // 收集所有信號
                    allSignals.add(signal)
                }
            }

            // 按強度降序排序
            val boostedSignals = allSignals.sortedByDescending { it.strength }

            val merged = MergedSignalPool(
                stockHits = stockHits,
                stockNames = stockNames,
                boostedSignals = boostedSignals
            )

            context.setStageOutput(nodeId, merged)
            context.log(
                nodeId,
                "信號聚合完成: ${input.size} 個策略, " +
                    "涉及 ${merged.totalStocks} 只股票, " +
                    "多策略命中 ${merged.multiHitStocks.size} 只"
            )
            merged
        } catch (e: Exception) {
            context.log(nodeId, "信號聚合異常: ${e.message}")
            throw e
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  5. SectorBoostNode (ENRICHMENT)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 板塊加權增強節點
 *
 * 從市場上下文中獲取板塊加權信息（用戶關注板塊、回彈板塊、熱門板塊），
 * 對匹配的股票信號進行加分處理。
 *
 * 加分規則：
 * - 用戶關注板塊匹配: +15 分
 * - 回彈板塊匹配: +1~5 分（回調天數）
 * - 今日熱門板塊匹配: +10 分
 */
class SectorBoostNode : PipelineNode<MergedSignalPool, MergedSignalPool> {

    override val nodeId: String = "sector_boost"
    override val nodeName: String = "板塊加權增強"
    override val nodeType: NodeType = NodeType.ENRICHMENT

    override suspend fun execute(context: PipelineContext, input: MergedSignalPool): MergedSignalPool {
        val marketContext = context.marketContext
            ?: run {
                context.log(nodeId, "市場上下文為空，跳過板塊加權")
                return input
            }

        return try {
            val enhancedSignals = input.boostedSignals.map { signal ->
                var boost = 0

                // 用戶關注板塊加分
                boost += marketContext.getFocusBoostForStock(signal.stockName)

                // 回彈板塊加分
                boost += marketContext.getBounceBoostForStock(signal.stockName)

                // 今日熱門板塊加分
                if (marketContext.getTodayHotRank(signal.stockName) >= 0) {
                    boost += 10
                }

                if (boost > 0) {
                    signal.copy(strength = (signal.strength + boost).coerceAtMost(100))
                } else {
                    signal
                }
            }

            val boosted = input.copy(boostedSignals = enhancedSignals)

            val boostedCount = boosted.boostedSignals.count { newSignal ->
                val original = input.boostedSignals.find { it.stockCode == newSignal.stockCode && it.strategyId == newSignal.strategyId }
                original != null && newSignal.strength != original.strength
            }

            context.log(nodeId, "板塊加權完成: ${boostedCount} 個信號被增強")
            boosted
        } catch (e: Exception) {
            context.log(nodeId, "板塊加權異常: ${e.message}")
            input // 出錯時返回原始數據
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  6. SmartMoneyFilterNode (FILTER)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 主力資金過濾節點
 *
 * 使用 [SmartMoneyCache] 的綜合評分過濾低分股票。
 * 評分低於 [minScore] 的股票信號將被淘汰。
 *
 * @property minScore 最低通過分數（默認 55）
 */
class SmartMoneyFilterNode(
    private val minScore: Int = 55
) : PipelineNode<MergedSignalPool, FilterResult> {

    override val nodeId: String = "smart_money_filter"
    override val nodeName: String = "主力資金過濾"
    override val nodeType: NodeType = NodeType.FILTER

    override suspend fun execute(context: PipelineContext, input: MergedSignalPool): FilterResult {
        return try {
            // 確保緩存已刷新
            val allCodes = input.stockHits.keys.toList()
            if (allCodes.isNotEmpty()) {
                SmartMoneyCache.refresh(context.androidContext, allCodes)
            }

            val passed = mutableListOf<StrategySignal>()
            val rejected = mutableListOf<FilteredStockInfo>()

            for (signal in input.boostedSignals) {
                val score = SmartMoneyCache.getScore(signal.stockCode).combined
                if (score >= minScore) {
                    passed.add(signal)
                } else {
                    rejected.add(
                        FilteredStockInfo(
                            code = signal.stockCode,
                            name = signal.stockName,
                            reason = "主力資金評分低於門檻: ${"%.1f".format(score)} < $minScore",
                            originalStrength = signal.strength
                        )
                    )
                }
            }

            val result = FilterResult(passed = passed, rejected = rejected)

            context.setStageOutput(nodeId, result)
            context.log(
                nodeId,
                "主力資金過濾完成: 通過 ${result.passCount} 只, " +
                    "淘汰 ${result.rejectCount} 只, 通過率 ${"%.1f".format(result.passRate * 100)}%"
            )
            result
        } catch (e: Exception) {
            context.log(nodeId, "主力資金過濾異常: ${e.message}")
            // 出錯時返回空過濾結果，保留所有信號
            FilterResult(
                passed = input.boostedSignals,
                rejected = emptyList()
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  7. AIPredictNode (AI_PREDICTION)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## AI 綜合預測節點
 *
 * 調用 [AIPredictionEngine] 對聚合信號進行 AI 綜合分析，
 * 輸出 Top 推薦股票及市場展望。
 *
 * @property useEnhancedAi 是否使用增強型 AI（默認 true）
 */
class AIPredictNode(
    private val useEnhancedAi: Boolean = true
) : PipelineNode<MergedSignalPool, AIPredictionEngine.AIPrediction> {

    override val nodeId: String = "ai_predict"
    override val nodeName: String = "AI 綜合預測"
    override val nodeType: NodeType = NodeType.AI_PREDICTION

    override suspend fun execute(
        context: PipelineContext,
        input: MergedSignalPool
    ): AIPredictionEngine.AIPrediction {
        val engine = AIPredictionEngine(context.androidContext)

        // 構建板塊上下文
        val sectorContext = context.marketContext?.toAiSectorContext()
            ?: AIPredictionEngine.SectorContext()

        // 構建市場大環境描述
        val marketDirection = context.getMarketDirection()
        val marketContextStr = context.marketContext?.indexSnapshot?.marketDesc() ?: ""

        // 將 MergedSignalPool 轉換為 ScreeningResult 列表供引擎使用
        val signalPacks = context.getStageOutput<List<SignalPack>>("signal_merge")
        val screeningResults = signalPacks?.map { pack ->
            com.chin.stockanalysis.strategy.models.ScreeningResult(
                strategyId = pack.strategyId,
                strategyName = pack.strategyName,
                category = com.chin.stockanalysis.strategy.StrategyCategory.MOMENTUM,
                signals = pack.signals,
                totalScanned = 0,
                scanTimeMs = 0L
            )
        } ?: emptyList()

        // ── AI 精選動態接入：讀取策略 requiresAIRefine 做條件執行 ──
        val allStrategies = context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
        val requiresAIRefine = allStrategies.any { it.requiresAIRefine }

        // 若沒有策略需要 AI 精選，跳過 AI 預測，直接返回按強度排序的原始信號
        if (!requiresAIRefine) {
            context.log(nodeId, "沒有策略需要 AI 精選（requiresAIRefine=false），跳過 AI 預測")

            val topPicks = screeningResults.flatMap { it.signals }
                .sortedByDescending { it.strength }
                .take(5)
                .mapIndexed { index, signal ->
                    AIPredictionEngine.AIPick(
                        stockCode = signal.stockCode,
                        stockName = signal.stockName,
                        rank = index + 1,
                        compositeScore = signal.strength,
                        upProbability = signal.strength,
                        reason = signal.reason,
                        actionSuggestion = signal.action.label
                    )
                }

            return AIPredictionEngine.AIPrediction(
                mode = "NO_AI",
                modeReason = "沒有策略需要 AI 精選，使用原始信號排序",
                topPicks = topPicks,
                marketOutlook = "未使用 AI 精選，信號已按強度排序",
                riskWarning = "",
                marketDirection = marketDirection
            )
        }

        context.log(nodeId, "${allStrategies.count { it.requiresAIRefine }} 個策略需要 AI 精選，啟動 AI 預測")

        return try {
            val prediction = engine.predict(
                strategyResults = screeningResults,
                selectedDate = context.tradeDate,
                useEnhancedAi = useEnhancedAi,
                marketContext = marketContextStr,
                sectorContext = sectorContext
            )

            prediction ?: run {
                context.log(nodeId, "AI 預測返回 null，使用默認結果")
                AIPredictionEngine.AIPrediction(
                    mode = "FALLBACK",
                    modeReason = "AI 預測引擎返回空結果",
                    topPicks = emptyList(),
                    marketOutlook = "數據不足，無法生成預測",
                    riskWarning = "AI 預測不可用，請依賴其他篩選結果",
                    marketDirection = marketDirection
                )
            }
        } catch (e: Exception) {
            context.log(nodeId, "AI 預測異常: ${e.message}")
            AIPredictionEngine.AIPrediction(
                mode = "ERROR",
                modeReason = "AI 預測引擎異常: ${e.message}",
                topPicks = emptyList(),
                marketOutlook = "預測失敗",
                riskWarning = "AI 預測不可用，請依賴其他篩選結果",
                marketDirection = marketDirection
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  8. MainBoardFilterNode (FILTER)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 主板過濾節點
 *
 * 從股票池中過濾出主板股票，排除 ETF、LOF、轉債等非主板品種。
 *
 * 排除規則（股票代碼前綴）：
 * - `sh51*` — 上證 ETF
 * - `sh56*` — 上證 ETF（跨境）
 * - `sz15*` — 深證 ETF / LOF
 * - `sz16*` — 深證 ETF / LOF
 * - `bj8*`  — 北交所股票
 */
class MainBoardFilterNode : PipelineNode<StockPool, StockPool> {

    override val nodeId: String = "main_board_filter"
    override val nodeName: String = "主板股票過濾"
    override val nodeType: NodeType = NodeType.FILTER

    companion object {
        /** 排除的股票代碼前綴集合 */
        private val EXCLUDED_PREFIXES = setOf("sh51", "sh56", "sz15", "sz16", "bj8")

        /**
         * 判斷股票代碼是否為主板股票
         * @param code 股票代碼（如 sh600519）
         * @return true 表示是主板股票（保留），false 表示需要排除
         */
        fun isMainBoardStock(code: String): Boolean {
            return EXCLUDED_PREFIXES.none { prefix -> code.startsWith(prefix) }
        }
    }

    override suspend fun execute(context: PipelineContext, input: StockPool): StockPool {
        return try {
            val originalSize = input.stocks.size
            val mainBoardStocks = input.stocks.filter { isMainBoardStock(it.code) }
            val excludedCount = originalSize - mainBoardStocks.size

            val pool = StockPool(
                stocks = mainBoardStocks,
                source = input.source,
                totalCount = input.totalCount,
                filterReason = buildString {
                    append(input.filterReason)
                    if (excludedCount > 0) {
                        if (isNotEmpty()) append("; ")
                        append("排除非主板 $excludedCount 只")
                    }
                }
            )

            context.log(
                nodeId,
                "主板過濾完成: $originalSize → ${pool.size} 只 (排除 $excludedCount 只)"
            )
            pool
        } catch (e: Exception) {
            context.log(nodeId, "主板過濾異常: ${e.message}")
            // 出錯時返回原始股票池
            input
        }
    }
}
