package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.analysis.CandlePatternDetector
import com.chin.stockanalysis.strategy.data.SmartMoneyCache
import com.chin.stockanalysis.strategy.data.StrategyDataFeed
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.MergedSignalPool
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import com.chin.stockanalysis.strategy.topology.core.SignalPack
import com.chin.stockanalysis.strategy.topology.core.StockPool
import com.chin.stockanalysis.strategy.topology.pipelines.NewsGuardResult
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
) : BaseNode<Any, StrategyMarketContext>("market_context", "市場上下文構建", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): StrategyMarketContext {
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
 *
 * **設計說明**：接受 `Any` 輸入是因為此節點在 DAG 中可能有多個上游邊
 * （如 `n_import` 輸出 `Int`、`n_ctx` 輸出 `StrategyMarketContext`），
 * DAG 框架取第一條邊的輸出作為 input。市場上下文統一從
 * `context.marketContext` 讀取（由 `MarketContextNode` 寫入），不依賴 input 參數。
 */
class StockPoolNode : BaseNode<Any, StockPool>("stock_pool", "股票池構建", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): StockPool {
        return try {
            // 從 context 讀取市場上下文（由 MarketContextNode 寫入）
            val marketContext = context.marketContext
            if (marketContext == null) {
                context.log(nodeId, "⚠ 市場上下文為空，使用空板塊列表構建股票池")
            }

            val feed = StrategyDataFeed(context.androidContext)
            val allStocks = feed.prepareFromDb(
                date = context.tradeDate,
                config = StrategyDataFeed.DataFeedConfig(
                    onlyMainBoard = context.config.onlyMainBoard
                )
            )

            // 如果有用戶關注板塊或熱門板塊，嘗試按板塊名稱匹配過濾
            val sectorKeywords = marketContext?.let {
                it.userFocusSectors + it.todayHotSectors
            } ?: emptySet()
            val filteredStocks = if (sectorKeywords.isNotEmpty()) {
                allStocks.filter { stock ->
                    sectorKeywords.any { keyword ->
                        stock.name.contains(keyword)
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

            // 過濾掉大盤指數（sh000xxx / sz399xxx 不可交易，如上證指數、深證成指等）
            val tradeableStocks = finalStocks.filterNot { stock ->
                stock.code.startsWith("sh000") || stock.code.startsWith("sz399")
            }
            if (tradeableStocks.size < finalStocks.size) {
                context.log(nodeId, "過濾大盤指數: ${finalStocks.size - tradeableStocks.size} 只（sh000/sz399）")
            }

            val pool = StockPool(
                stocks = tradeableStocks,
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
 * 從 context 中按需讀取 [StockPool]，調用策略的 `screenWithData()` 方法，將結果轉換為 [SignalPack]。
 *
 * @property strategy 被包裝的量化選股策略實例
 */
class StrategyNode(
    internal val strategy: Strategy
) : BaseNode<Any, SignalPack>("strategy_${strategy.id}", "策略: ${strategy.name}", NodeType.STRATEGY) {

    override suspend fun execute(context: PipelineContext, input: Any): SignalPack {
        return try {
            // 從 input 或 context 中按需讀取 StockPool
            val pool: StockPool = when (input) {
                is StockPool -> input
                else -> context.getStageOutput<StockPool>("stock_pool")
                    ?: context.getStageOutput<StockPool>("n_pool")
                    ?: context.getStageOutput<StockPool>("candidate_pool")
                    ?: context.getStageOutput<StockPool>("n_cand")
                    ?: StockPool(emptyList(), "empty")
            }

            if (pool.isEmpty) {
                context.log(nodeId, "股票池為空，跳過策略 ${strategy.name}")
                return SignalPack(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    signals = emptyList()
                )
            }

            val result = strategy.screenWithData(pool.stocks)

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
class SignalMergeNode : BaseNode<Any, MergedSignalPool>("signal_merge", "多策略信號聚合", NodeType.AGGREGATION) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        return try {
            // 從輸入中提取 SignalPack（過濾掉非 SignalPack 項目如 AdaptiveParams）
            // 同時兼容 DefensiveDividendNode 輸出的 List<StrategySignal>
            val packs: List<SignalPack> = when (input) {
                is List<*> -> {
                    val signalPacks = input.filterIsInstance<SignalPack>().toMutableList()
                    // 將 List<StrategySignal>（如 DefensiveDividendNode 輸出）轉為 SignalPack
                    val looseSignals = input.filterIsInstance<List<*>>()
                        .filter { it.isNotEmpty() && it[0] is StrategySignal }
                        .flatten()
                        .filterIsInstance<StrategySignal>()
                    if (looseSignals.isNotEmpty()) {
                        signalPacks.add(SignalPack(
                            strategyId = "defensive_dividend",
                            strategyName = "防守高息",
                            signals = looseSignals
                        ))
                    }
                    signalPacks
                }
                is SignalPack -> listOf(input)
                else -> {
                    context.log(nodeId, "⚠ 未知輸入類型: ${input::class.simpleName}，無信號可聚合")
                    emptyList()
                }
            }

            val stockHits = mutableMapOf<String, MutableList<Pair<String, Int>>>()
            val stockNames = mutableMapOf<String, String>()
            val allSignals = mutableListOf<StrategySignal>()

            for (pack in packs) {
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

            // 按強度降序排序 + 去重（同一股票只保留最強信號）
            val boostedSignals = allSignals
                .sortedByDescending { it.strength }
                .distinctBy { it.stockCode }

            val merged = MergedSignalPool(
                stockHits = stockHits,
                stockNames = stockNames,
                boostedSignals = boostedSignals
            )

            context.setStageOutput(nodeId, merged)
            context.log(
                nodeId,
                "信號聚合完成: ${packs.size} 個策略, " +
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
class SectorBoostNode : BaseNode<Any, MergedSignalPool>("sector_boost", "板塊加權增強", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        // 兼容多依賴：中線 n_boost 有 n_merge + n_heat 兩條入邊，
        // 主輸入應為 MergedSignalPool，若收到其他類型則從 context 讀取
        val signalPool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> {
                context.getStageOutput<MergedSignalPool>("signal_merge")
                    ?: context.getStageOutput<MergedSignalPool>("n_merge")
                    ?: return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }

        val marketContext = context.marketContext
            ?: run {
                context.log(nodeId, "市場上下文為空，跳過板塊加權")
                return signalPool
            }

        // 讀取熱度評分（n_heat 輸出 Map<String, Int>）
        val heatScores = context.getStageOutput<Map<String, Int>>("heat_score")
            ?: context.getStageOutput<Map<String, Int>>("n_heat")
            ?: emptyMap()

        // 讀取大盤均線收斂結果（n_ma_unified 輸出）
        val maResult = context.getStageOutput<MaConvergenceResult>("n_ma_conv")

        return try {
            val enhancedSignals = signalPool.boostedSignals.map { signal ->
                var boost = 0

                // 用戶關注板塊加分
                boost += marketContext.getFocusBoostForStock(signal.stockName)

                // 回彈板塊加分
                boost += marketContext.getBounceBoostForStock(signal.stockName)

                // 今日熱門板塊加分
                if (marketContext.getTodayHotRank(signal.stockName) >= 0) {
                    boost += 10
                }

                // 熱度評分加分（5 維熱度，最高 100 分 → 映射到 0~15 加分）
                val heat = heatScores[signal.stockCode] ?: 0
                if (heat > 0) {
                    boost += (heat * 15 / 100).coerceIn(0, 15)
                }

                // 大盤均線粘合向上加分（市場整體做多氛圍）
                if (maResult != null && maResult.maConvergedAndUp) {
                    boost += 8
                }

                if (boost > 0) {
                    signal.copy(strength = (signal.strength + boost).coerceAtMost(100))
                } else {
                    signal
                }
            }

            val boosted = signalPool.copy(boostedSignals = enhancedSignals)

            val boostedCount = boosted.boostedSignals.count { newSignal ->
                val original = signalPool.boostedSignals.find { it.stockCode == newSignal.stockCode && it.strategyId == newSignal.strategyId }
                original != null && newSignal.strength != original.strength
            }

            val heatInfo = if (heatScores.isNotEmpty()) "熱度=${heatScores.size}只" else ""
            val maInfo = if (maResult?.maConvergedAndUp == true) "均線粘合向上✓" else ""
            context.log(nodeId, "板塊加權完成: ${boostedCount} 個信號被增強 $heatInfo $maInfo")
            boosted
        } catch (e: Exception) {
            context.log(nodeId, "板塊加權異常: ${e.message}")
            signalPool // 出錯時返回原始數據
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
) : BaseNode<Any, MergedSignalPool>("smart_money_filter", "主力資金過濾", NodeType.FILTER) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        // 根據上游類型提取信號池
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            is AIPredictionEngine.AIPrediction -> {
                // 將 AIPick 轉換為 StrategySignal 構建信號池
                val signals = input.topPicks.map { pick ->
                    StrategySignal(
                        stockCode = pick.stockCode,
                        stockName = pick.stockName,
                        strategyId = "ai_predict",
                        category = com.chin.stockanalysis.strategy.StrategyCategory.MOMENTUM,
                        strength = pick.compositeScore,
                        action = com.chin.stockanalysis.strategy.models.SignalAction.BUY,
                        reason = pick.reason
                    )
                }
                MergedSignalPool(
                    stockHits = signals.groupBy { it.stockCode }.mapValues { (_, sigs) ->
                        sigs.map { "ai_predict" to it.strength }
                    },
                    stockNames = signals.associate { it.stockCode to it.stockName },
                    boostedSignals = signals
                )
            }
            else -> {
                context.log(nodeId, "⚠ 未知輸入類型: ${input::class.simpleName}，跳過過濾")
                return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }

        return try {
            // 確保緩存已刷新
            val allCodes = pool.stockHits.keys.toList()
            if (allCodes.isNotEmpty()) {
                SmartMoneyCache.refresh(context.androidContext, allCodes)
            }

            val passed = mutableListOf<StrategySignal>()
            var rejectCount = 0

            for (signal in pool.boostedSignals) {
                val score = SmartMoneyCache.getScore(signal.stockCode).combined
                // 防守高息股（銀行/電力等）主力資金分天然低，降閾到 20
                val effectiveMin = if (signal.strategyId == "defensive_dividend") 20 else minScore
                if (score >= effectiveMin) {
                    passed.add(signal)
                } else {
                    rejectCount++
                }
            }

            // 過濾 stockHits / stockNames，只保留通過的股票
            val passedCodes = passed.map { it.stockCode }.toSet()
            val filteredHits = pool.stockHits.filterKeys { it in passedCodes }
            val filteredNames = pool.stockNames.filterKeys { it in passedCodes }

            // 兜底：若全部被淘汰（通過率 0%），保留原始信號避免 Pipeline 中斷
            if (passed.isEmpty() && pool.boostedSignals.isNotEmpty()) {
                context.log(nodeId, "⚠ 主力資金過濾全部淘汰，保留原始信號（不阻塞 Pipeline）")
                context.setStageOutput(nodeId, pool)
                context.log(
                    nodeId,
                    "主力資金過濾完成: 通過 0 只, " +
                        "淘汰 $rejectCount 只, 通過率 0.0%（兜底透傳）"
                )
                return pool
            }

            val result = MergedSignalPool(
                stockHits = filteredHits,
                stockNames = filteredNames,
                boostedSignals = passed
            )

            context.setStageOutput(nodeId, result)
            context.log(
                nodeId,
                "主力資金過濾完成: 通過 ${passed.size} 只, " +
                    "淘汰 $rejectCount 只, 通過率 ${"%.1f".format(if (pool.boostedSignals.isEmpty()) 100.0 else passed.size * 100.0 / pool.boostedSignals.size)}%"
            )
            result
        } catch (e: Exception) {
            context.log(nodeId, "主力資金過濾異常: ${e.message}")
            // 出錯時保留所有信號
            pool
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  6.5 CandlePatternNode (K 線形態偵測)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## K 線形態偵測節點
 *
 * 掃描候選股的最近 K 線，偵測上升三法、下降三法、早晨之星、黃昏之星、
 * 紅三兵、三烏鴉等經典形態。結果存入 context（供報告重點提醒），
 * 輸出透傳輸入（不影響主流水線）。
 *
 * 非關鍵節點：失敗不影響 Pipeline。
 */
class CandlePatternNode : BaseNode<Any, Any>("candle_pattern", "K線形態偵測", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): Any {
        // 提取候選股代碼
        val codes: List<String> = when (input) {
            is MergedSignalPool -> input.boostedSignals.map { it.stockCode }.distinct()
            is Set<*> -> input.filterIsInstance<String>()
            is List<*> -> input.filterIsInstance<String>()
            else -> emptyList()
        }

        if (codes.isEmpty()) {
            context.log(nodeId, "📥 $nodeName: 無候選股，跳過")
            return input
        }

        context.log(nodeId, "📥 $nodeName 輸入: ${codes.size} 只候選股")

        return try {
            val dao = StockDatabase.getInstance(context.androidContext).dailySnapshotDao()
            val alerts = mutableMapOf<String, List<CandlePatternDetector.PatternMatch>>()
            val names = mutableMapOf<String, String>()

            for (code in codes) {
                val raw = dao.getByCode(code, 12)  // 最近 12 根 K 線
                if (raw.size < 5) continue
                val candles = raw.reversed()  // DESC → ASC
                val patterns = CandlePatternDetector.detect(candles)
                if (patterns.isNotEmpty()) {
                    alerts[code] = patterns
                    names[code] = candles.last().name
                }
            }

            // 存入 context 供報告使用
            context.setStageOutput(nodeId, alerts)

            if (alerts.isEmpty()) {
                context.log(nodeId, "📤 $nodeName: 未偵測到經典形態")
            } else {
                val summary = alerts.entries.joinToString { (code, pats) ->
                    val name = names[code] ?: code
                    val signals = pats.joinToString("/") { "${it.patternName}(${it.direction.signal})" }
                    "$name($code): $signals"
                }
                context.log(nodeId, "🚨 $nodeName 偵測到形態: $summary")
            }

            // 透傳輸入（不影響下游）
            input
        } catch (e: Exception) {
            context.log(nodeId, "K線形態偵測異常: ${e.message}，跳過")
            input
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
) : BaseNode<Any, AIPredictionEngine.AIPrediction>("ai_predict", "AI 綜合預測", NodeType.AI_PREDICTION) {

    override suspend fun execute(
        context: PipelineContext,
        input: Any
    ): AIPredictionEngine.AIPrediction {
        // AI 精選是最終選股步驟（在主力過濾 + 新聞攔截之後），負責從已過濾候選中挑出 top 5
        // 兼容多種上游：
        // - MergedSignalPool（超短線，無 n_newsguard）：直接使用
        // - NewsGuardResult（短/中/長線）：從 context 讀取 smart_money_filter 輸出，按 passedCodes 過濾
        val signalPool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            is NewsGuardResult -> {
                // 從 context 讀取主力資金過濾後的信號池，按新聞攔截通過的代碼過濾
                val pool = context.getStageOutput<MergedSignalPool>("smart_money_filter")
                    ?: context.getStageOutput<MergedSignalPool>("n_smart")
                    ?: MergedSignalPool(emptyMap(), emptyMap(), emptyList())
                if (input.passedCodes.isEmpty()) {
                    pool
                } else {
                    MergedSignalPool(
                        stockHits = pool.stockHits.filterKeys { it in input.passedCodes },
                        stockNames = pool.stockNames.filterKeys { it in input.passedCodes },
                        boostedSignals = pool.boostedSignals.filter { it.stockCode in input.passedCodes }
                    )
                }
            }
            else -> {
                context.log(nodeId, "⚠ 輸入類型=${input::class.simpleName}，從 context 讀取 smart_money_filter 輸出")
                context.getStageOutput<MergedSignalPool>("smart_money_filter")
                    ?: context.getStageOutput<MergedSignalPool>("n_smart")
                    ?: context.getStageOutput<MergedSignalPool>("sector_boost")
                    ?: context.getStageOutput<MergedSignalPool>("n_boost")
                    ?: MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }

        val engine = AIPredictionEngine(context.androidContext)

        // 構建板塊上下文
        val sectorContext = context.marketContext?.toAiSectorContext()
            ?: AIPredictionEngine.SectorContext()

        // 構建市場大環境描述
        val marketDirection = context.getMarketDirection()
        val marketContextStr = context.marketContext?.indexSnapshot?.marketDesc() ?: ""

        // 直接使用上游傳入的 MergedSignalPool 構建 ScreeningResult
        val screeningResults = listOf(
            com.chin.stockanalysis.strategy.models.ScreeningResult(
                strategyId = "merged",
                strategyName = "合併信號池",
                category = com.chin.stockanalysis.strategy.StrategyCategory.MOMENTUM,
                signals = signalPool.boostedSignals,
                totalScanned = signalPool.totalStocks,
                scanTimeMs = 0L
            )
        )

        // ── AI 精選動態接入：讀取策略 requiresAIRefine 做條件執行 ──
        val allStrategies = context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
        val requiresAIRefine = allStrategies.any { it.requiresAIRefine }

        // AI 精選是最終裁切步驟：從已過濾的候選中按強度取 top 5
        if (!requiresAIRefine) {
            context.log(nodeId, "沒有策略需要 AI 精選（requiresAIRefine=false），按強度取 top 5")

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
class MainBoardFilterNode : BaseNode<Any, StockPool>("main_board_filter", "主板股票過濾", NodeType.FILTER) {

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

    override suspend fun execute(context: PipelineContext, input: Any): StockPool {
        return try {
            // 從 input 或 context 中按需讀取 StockPool
            val pool: StockPool = when (input) {
                is StockPool -> input
                else -> context.getStageOutput<StockPool>("stock_pool")
                    ?: context.getStageOutput<StockPool>("n_pool")
                    ?: return StockPool(emptyList(), "empty")
            }

            val originalSize = pool.stocks.size
            val mainBoardStocks = pool.stocks.filter { isMainBoardStock(it.code) }
            val excludedCount = originalSize - mainBoardStocks.size

            val result = StockPool(
                stocks = mainBoardStocks,
                source = pool.source,
                totalCount = pool.totalCount,
                filterReason = buildString {
                    append(pool.filterReason)
                    if (excludedCount > 0) {
                        if (isNotEmpty()) append("; ")
                        append("排除非主板 $excludedCount 只")
                    }
                }
            )

            context.log(
                nodeId,
                "主板過濾完成: $originalSize → ${result.size} 只 (排除 $excludedCount 只)"
            )
            result
        } catch (e: Exception) {
            context.log(nodeId, "主板過濾異常: ${e.message}")
            // 出錯時嘗試返回原始股票池
            (input as? StockPool) ?: StockPool(emptyList(), "empty")
        }
    }
}
