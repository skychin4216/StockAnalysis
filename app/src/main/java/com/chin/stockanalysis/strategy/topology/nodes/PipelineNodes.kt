package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.stock.database.StockDataCenter
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
import com.chin.stockanalysis.strategy.topology.pipelines.RotationPenaltyResult
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.sector.StrategyMarketContext

// ════════════════════════════════════════════════════════════════════════════
//  1. MarketContextNode (DATA_SOURCE)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 市场上下文构建节点
 *
 * 构建 [StrategyMarketContext]，作为 Pipeline 的第一个数据源节点。
 * 构建结果会同时存入 [PipelineContext.marketContext] 供后续节点使用。
 *
 * @property forceRefresh 是否强制刷新缓存（默认 false，使用 5 分钟 TTL 缓存）
 */
class MarketContextNode(
    private val forceRefresh: Boolean = false
) : BaseNode<Any, StrategyMarketContext>("market_context", "市场上下文构建", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): StrategyMarketContext {
        return try {
            val marketContext = StrategyMarketContext.build(
                context.androidContext,
                context.tradeDate,
                forceRefresh
            )
            // 注入到共享上下文供后续节点使用
            context.marketContext = marketContext
            context.log(nodeId, "市场上下文构建完成: ${marketContext.summary()}")
            marketContext
        } catch (e: Exception) {
            context.log(nodeId, "市场上下文构建失败: ${e.message}")
            throw e
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  2. StockPoolNode (DATA_SOURCE)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 股票池构建节点
 *
 * 根据市场上下文中的板块信息，从全市场股票中过滤出目标股票池。
 * 若市场上下文中定义了用户关注板块，优先匹配相关板块的股票；
 * 否则返回全市场股票（可通过 [PipelineConfig.onlyMainBoard] 控制是否只保留主板）。
 *
 * **设计说明**：接受 `Any` 输入是因为此节点在 DAG 中可能有多个上游边
 * （如 `n_import` 输出 `Int`、`n_ctx` 输出 `StrategyMarketContext`），
 * DAG 框架取第一条边的输出作为 input。市场上下文统一从
 * `context.marketContext` 读取（由 `MarketContextNode` 写入），不依赖 input 参数。
 */
class StockPoolNode : BaseNode<Any, StockPool>("stock_pool", "股票池构建", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): StockPool {
        return try {
            // 从 context 读取市场上下文（由 MarketContextNode 写入）
            val marketContext = context.marketContext
            if (marketContext == null) {
                context.log(nodeId, "⚠ 市场上下文为空，使用空板块列表构建股票池")
            }

            val feed = StrategyDataFeed(context.androidContext)
            val allStocks = feed.prepareFromDb(
                date = context.tradeDate,
                config = StrategyDataFeed.DataFeedConfig(
                    onlyMainBoard = context.config.onlyMainBoard,
                    enrichFundamentals = false  // 候选池不需要基本面，避免阻塞在网络API
                )
            )

            // 如果有用户关注板块或热门板块，尝试按板块名称匹配过滤
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

            // 如果板块过滤后为空，回退到全市场
            val finalStocks = if (filteredStocks.isEmpty() && sectorKeywords.isNotEmpty()) {
                context.log(nodeId, "板块匹配无结果，回退到全市场 (${allStocks.size} 只)")
                allStocks
            } else {
                filteredStocks
            }

            // 过滤掉大盘指数（sh000xxx / sz399xxx 不可交易，如上证指数、深证成指等）
            val tradeableStocks = finalStocks.filterNot { stock ->
                stock.code.startsWith("sh000") || stock.code.startsWith("sz399")
            }
            if (tradeableStocks.size < finalStocks.size) {
                context.log(nodeId, "过滤大盘指数: ${finalStocks.size - tradeableStocks.size} 只（sh000/sz399）")
            }

            val pool = StockPool(
                stocks = tradeableStocks,
                source = if (sectorKeywords.isNotEmpty()) "sector_filtered" else "market_all",
                totalCount = allStocks.size,
                filterReason = if (finalStocks.size < allStocks.size) {
                    "按板块关键词 ${sectorKeywords.size} 个过滤"
                } else ""
            )

            context.setStageOutput(nodeId, pool)
            context.log(nodeId, "股票池构建完成: ${pool.size}/${pool.totalCount} 只, source=${pool.source}")
            pool
        } catch (e: Exception) {
            context.log(nodeId, "股票池构建失败: ${e.message}")
            throw e
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  3. StrategyNode (STRATEGY)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 策略筛选节点
 *
 * 包装现有 [Strategy] 接口，将其接入 Pipeline 数据流。
 * 从 context 中按需读取 [StockPool]，调用策略的 `screenWithData()` 方法，将结果转换为 [SignalPack]。
 *
 * @property strategy 被包装的量化选股策略实例
 */
class StrategyNode(
    internal val strategy: Strategy
) : BaseNode<Any, SignalPack>("strategy_${strategy.id}", "策略: ${strategy.name}", NodeType.STRATEGY) {

    override suspend fun execute(context: PipelineContext, input: Any): SignalPack {
        return try {
            // 从 input 或 context 中按需读取 StockPool
            val pool: StockPool = when (input) {
                is StockPool -> input
                else -> context.getStageOutput<StockPool>("stock_pool")
                    ?: context.getStageOutput<StockPool>("n_pool")
                    ?: context.getStageOutput<StockPool>("candidate_pool")
                    ?: context.getStageOutput<StockPool>("n_cand")
                    ?: StockPool(emptyList(), "empty")
            }

            if (pool.isEmpty) {
                context.log(nodeId, "股票池为空，跳过策略 ${strategy.name}")
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
                        "策略 ${strategy.name} 筛选完成: 扫描 ${screeningResult.totalScanned} 只, " +
                            "命中 ${screeningResult.hitCount} 只, 耗时 ${screeningResult.scanTimeMs}ms"
                    )
                    screeningResult.signals
                },
                onFailure = { e ->
                    context.log(nodeId, "策略 ${strategy.name} 执行失败: ${e.message}")
                    emptyList()
                }
            )

            // 限制最大信号数
            val limitedSignals = signals.take(context.config.maxSignalsPerStrategy)

            SignalPack(
                strategyId = strategy.id,
                strategyName = strategy.name,
                signals = limitedSignals
            )
        } catch (e: Exception) {
            context.log(nodeId, "策略节点 ${strategy.name} 异常: ${e.message}")
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
 * ## 信号聚合节点
 *
 * 将多个策略的 [SignalPack] 合并为一个 [MergedSignalPool]。
 * 按股票代码聚合，记录每只股票被哪些策略命中及其强度。
 */
class SignalMergeNode : BaseNode<Any, MergedSignalPool>("signal_merge", "多策略信号聚合", NodeType.AGGREGATION) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        return try {
            // 从输入中提取 SignalPack（过滤掉非 SignalPack 项目如 AdaptiveParams）
            // 同时兼容 DefensiveDividendNode 输出的 List<StrategySignal>
            val packs: List<SignalPack> = when (input) {
                is List<*> -> {
                    val signalPacks = input.filterIsInstance<SignalPack>().toMutableList()
                    // 将 List<StrategySignal>（如 DefensiveDividendNode 输出）转为 SignalPack
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
                    context.log(nodeId, "⚠ 未知输入类型: ${input::class.simpleName}，无信号可聚合")
                    emptyList()
                }
            }

            val stockHits = mutableMapOf<String, MutableList<Pair<String, Int>>>()
            val stockNames = mutableMapOf<String, String>()
            val allSignals = mutableListOf<StrategySignal>()

            for (pack in packs) {
                for (signal in pack.signals) {
                    // 聚合命中记录
                    stockHits.getOrPut(signal.stockCode) { mutableListOf() }
                        .add(pack.strategyId to signal.strength)
                    // 记录股票名称
                    if (!stockNames.containsKey(signal.stockCode)) {
                        stockNames[signal.stockCode] = signal.stockName
                    }
                    // 收集所有信号
                    allSignals.add(signal)
                }
            }

            // 按强度降序排序 + 去重（同一股票只保留最强信号）
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
                "信号聚合完成: ${packs.size} 个策略, " +
                    "涉及 ${merged.totalStocks} 只股票, " +
                    "多策略命中 ${merged.multiHitStocks.size} 只"
            )
            merged
        } catch (e: Exception) {
            context.log(nodeId, "信号聚合异常: ${e.message}")
            throw e
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  5. SectorBoostNode (ENRICHMENT)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 板块加权增强节点
 *
 * 从市场上下文中获取板块加权信息（用户关注板块、回弹板块、热门板块），
 * 对匹配的股票信号进行加分处理。
 *
 * 加分规则：
 * - 用户关注板块匹配: +15 分
 * - 回弹板块匹配: +1~5 分（回调天数）
 * - 今日热门板块匹配: +10 分
 */
class SectorBoostNode : BaseNode<Any, MergedSignalPool>("sector_boost", "板块加权增强", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        // 兼容多依赖：中线 n_boost 有 n_merge + n_heat 两条入边，
        // 主输入应为 MergedSignalPool，若收到其他类型则从 context 读取
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
                context.log(nodeId, "市场上下文为空，跳过板块加权")
                return signalPool
            }

        // 读取热度评分（n_heat 输出 Map<String, Int>）
        val heatScores = context.getStageOutput<Map<String, Int>>("heat_score")
            ?: context.getStageOutput<Map<String, Int>>("n_heat")
            ?: emptyMap()

        // 读取大盘均线收敛结果（n_ma_unified 输出）
        val maResult = context.getStageOutput<MaConvergenceResult>("n_ma_conv")

        return try {
            val enhancedSignals = signalPool.boostedSignals.map { signal ->
                var boost = 0

                // 用户关注板块加分
                boost += marketContext.getFocusBoostForStock(signal.stockName)

                // 回弹板块加分
                boost += marketContext.getBounceBoostForStock(signal.stockName)

                // 今日热门板块加分
                if (marketContext.getTodayHotRank(signal.stockName) >= 0) {
                    boost += 10
                }

                // 轮动预测板块加分（动量延续+资金流向预测的明日热门，前3 +12 / 其余 +8）
                boost += marketContext.getRotationBoostForStock(signal.stockName)

                // 热度评分加分（5 维热度，最高 100 分 → 映射到 0~15 加分）
                val heat = heatScores[signal.stockCode] ?: 0
                if (heat > 0) {
                    boost += (heat * 15 / 100).coerceIn(0, 15)
                }

                // 大盘均线粘合向上加分（市场整体做多氛围）
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

            val heatInfo = if (heatScores.isNotEmpty()) "热度=${heatScores.size}只" else ""
            val maInfo = if (maResult?.maConvergedAndUp == true) "均线粘合向上✓" else ""
            context.log(nodeId, "板块加权完成: ${boostedCount} 个信号被增强 $heatInfo $maInfo")
            boosted
        } catch (e: Exception) {
            context.log(nodeId, "板块加权异常: ${e.message}")
            signalPool // 出错时返回原始数据
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  6. SmartMoneyFilterNode (FILTER)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 主力资金过滤节点
 *
 * 使用 [SmartMoneyCache] 的综合评分过滤低分股票。
 * 评分低于 [minScore] 的股票信号将被淘汰。
 *
 * @property minScore 最低通过分数（默认 55）
 */
class SmartMoneyFilterNode(
    private val minScore: Int = 55
) : BaseNode<Any, MergedSignalPool>("smart_money_filter", "主力资金过滤", NodeType.FILTER) {

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        // 根据上游类型提取信号池
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            is AIPredictionEngine.AIPrediction -> {
                // 将 AIPick 转换为 StrategySignal 构建信号池
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
                context.log(nodeId, "⚠ 未知输入类型: ${input::class.simpleName}，跳过过滤")
                return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }

        return try {
            // 确保缓存已刷新
            val allCodes = pool.stockHits.keys.toList()
            if (allCodes.isNotEmpty()) {
                SmartMoneyCache.refresh(context.androidContext, allCodes)
            }

            val passed = mutableListOf<StrategySignal>()
            var rejectCount = 0

            // 读取板块轮动惩罚结果（v2）：命中受罚板块的股票在主力资金评分上降分，
            // 使轮动惩罚真正影响选股（闭环），而非仅写入报表字段
            val rotationResult = context.getStageOutput<RotationPenaltyResult>("n_rot_pen")
                ?: context.getStageOutput<RotationPenaltyResult>("rotation_penalty")

            for (signal in pool.boostedSignals) {
                var score = SmartMoneyCache.getScore(signal.stockCode).combined
                // 轮动惩罚降分：股票所属任一板块受罚则扣分（取最重惩罚）
                if (rotationResult != null && rotationResult.sectorPenalties.isNotEmpty()) {
                    val sectors = StockDataCenter.getSectorsByStock(signal.stockCode)
                    val sectorPenalty = sectors
                        .mapNotNull { rotationResult.sectorPenalties[it] }
                        .minOrNull() ?: 0
                    if (sectorPenalty < 0) {
                        score += sectorPenalty
                    }
                }
                // 2026-09-04 用户决策：不再对防守高息股(银行/电力等)故意降阈放行——
                // 资金面差的票同样影响防御表现，防守候选也按正常 minScore 过滤
                if (score >= minScore) {
                    passed.add(signal)
                } else {
                    rejectCount++
                }
            }

            // 过滤 stockHits / stockNames，只保留通过的股票
            val passedCodes = passed.map { it.stockCode }.toSet()
            val filteredHits = pool.stockHits.filterKeys { it in passedCodes }
            val filteredNames = pool.stockNames.filterKeys { it in passedCodes }

            // 兜底：若全部被淘汰（通过率 0%），保留原始信号避免 Pipeline 中断
            if (passed.isEmpty() && pool.boostedSignals.isNotEmpty()) {
                context.log(nodeId, "⚠ 主力资金过滤全部淘汰，保留原始信号（不阻塞 Pipeline）")
                context.setStageOutput(nodeId, pool)
                context.log(
                    nodeId,
                    "主力资金过滤完成: 通过 0 只, " +
                        "淘汰 $rejectCount 只, 通过率 0.0%（兜底透传）"
                )
                return pool
            }

            val result = MergedSignalPool(
                stockHits = filteredHits,
                stockNames = filteredNames,
                boostedSignals = passed
            )

            context.setStageOutput(nodeId, result)
            val rotationInfo = if (rotationResult?.sectorPenalties?.isNotEmpty() == true) {
                ", 轮动惩罚板块=${rotationResult.sectorPenalties.size}个"
            } else {
                ""
            }
            context.log(
                nodeId,
                "主力资金过滤完成: 通过 ${passed.size} 只, " +
                    "淘汰 $rejectCount 只, 通过率 ${"%.1f".format(if (pool.boostedSignals.isEmpty()) 100.0 else passed.size * 100.0 / pool.boostedSignals.size)}%" +
                    rotationInfo
            )
            result
        } catch (e: Exception) {
            context.log(nodeId, "主力资金过滤异常: ${e.message}")
            // 出错时保留所有信号
            pool
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  6.5 CandlePatternNode (K 线形态侦测)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## K 线形态侦测节点
 *
 * 扫描候选股的最近 K 线，侦测上升三法、下降三法、早晨之星、黄昏之星、
 * 红三兵、三乌鸦等经典形态。结果存入 context（供报告重点提醒），
 * 输出透传输入（不影响主流水线）。
 *
 * 非关键节点：失败不影响 Pipeline。
 */
class CandlePatternNode : BaseNode<Any, Any>("candle_pattern", "K线形态侦测", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): Any {
        // 提取候选股代码
        val codes: List<String> = when (input) {
            is MergedSignalPool -> input.boostedSignals.map { it.stockCode }.distinct()
            is Set<*> -> input.filterIsInstance<String>()
            is List<*> -> input.filterIsInstance<String>()
            else -> emptyList()
        }

        if (codes.isEmpty()) {
            context.log(nodeId, "📥 $nodeName: 无候选股，跳过")
            return input
        }

        context.log(nodeId, "📥 $nodeName 输入: ${codes.size} 只候选股")

        return try {
            val dao = StockDatabase.getInstance(context.androidContext).dailySnapshotDao()
            val alerts = mutableMapOf<String, List<CandlePatternDetector.PatternMatch>>()
            val names = mutableMapOf<String, String>()

            for (code in codes) {
                val raw = dao.getByCode(code, 12)  // 最近 12 根 K 线
                if (raw.size < 5) continue
                val candles = raw.reversed()  // DESC → ASC
                val patterns = CandlePatternDetector.detect(candles)
                if (patterns.isNotEmpty()) {
                    alerts[code] = patterns
                    names[code] = candles.last().name
                }
            }

            // 存入 context 供报告使用
            context.setStageOutput(nodeId, alerts)

            if (alerts.isEmpty()) {
                context.log(nodeId, "📤 $nodeName: 未侦测到经典形态")
            } else {
                val summary = alerts.entries.joinToString { (code, pats) ->
                    val name = names[code] ?: code
                    val signals = pats.joinToString("/") { "${it.patternName}(${it.direction.signal})" }
                    "$name($code): $signals"
                }
                context.log(nodeId, "🚨 $nodeName 侦测到形态: $summary")
            }

            // 透传输入（不影响下游）
            input
        } catch (e: Exception) {
            context.log(nodeId, "K线形态侦测异常: ${e.message}，跳过")
            input
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  7. AIPredictNode (AI_PREDICTION)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## AI 综合预测节点
 *
 * 调用 [AIPredictionEngine] 对聚合信号进行 AI 综合分析，
 * 输出 Top 推荐股票及市场展望。
 *
 * @property useEnhancedAi 是否使用增强型 AI（默认 true）
 */
class AIPredictNode(
    private val useEnhancedAi: Boolean = true
) : BaseNode<Any, AIPredictionEngine.AIPrediction>("ai_predict", "AI 综合预测", NodeType.AI_PREDICTION) {

    override suspend fun execute(
        context: PipelineContext,
        input: Any
    ): AIPredictionEngine.AIPrediction {
        // AI 精选是最终选股步骤（在主力过滤 + 新闻拦截之后），负责从已过滤候选中挑出 top 5
        // 兼容多种上游：
        // - MergedSignalPool（超短线，无 n_newsguard）：直接使用
        // - NewsGuardResult（短/中/长线）：从 context 读取 smart_money_filter 输出，按 passedCodes 过滤
        val signalPool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            is NewsGuardResult -> {
                // 从 context 读取主力资金过滤后的信号池，按新闻拦截通过的代码过滤
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
                context.log(nodeId, "⚠ 输入类型=${input::class.simpleName}，从 context 读取 smart_money_filter 输出")
                context.getStageOutput<MergedSignalPool>("smart_money_filter")
                    ?: context.getStageOutput<MergedSignalPool>("n_smart")
                    ?: context.getStageOutput<MergedSignalPool>("sector_boost")
                    ?: context.getStageOutput<MergedSignalPool>("n_boost")
                    ?: MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }

        val engine = AIPredictionEngine(context.androidContext)

        // 构建板块上下文
        val sectorContext = context.marketContext?.toAiSectorContext()
            ?: AIPredictionEngine.SectorContext()

        // 构建市场大环境描述
        val marketDirection = context.getMarketDirection()
        val marketContextStr = context.marketContext?.indexSnapshot?.marketDesc() ?: ""

        // 直接使用上游传入的 MergedSignalPool 构建 ScreeningResult
        val screeningResults = listOf(
            com.chin.stockanalysis.strategy.models.ScreeningResult(
                strategyId = "merged",
                strategyName = "合并信号池",
                category = com.chin.stockanalysis.strategy.StrategyCategory.MOMENTUM,
                signals = signalPool.boostedSignals,
                totalScanned = signalPool.totalStocks,
                scanTimeMs = 0L
            )
        )

        // ── AI 精选动态接入：读取策略 requiresAIRefine 做条件执行 ──
        val allStrategies = context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
        val requiresAIRefine = allStrategies.any { it.requiresAIRefine }

        // AI 精选是最终裁切步骤：从已过滤的候选中按强度取 top 5
        if (!requiresAIRefine) {
            context.log(nodeId, "没有策略需要 AI 精选（requiresAIRefine=false），按强度取 top 5")

            val topPicks = rankedPicks(screeningResults.flatMap { it.signals })

            return AIPredictionEngine.AIPrediction(
                mode = "NO_AI",
                modeReason = "没有策略需要 AI 精选，使用原始信号排序",
                topPicks = topPicks,
                marketOutlook = "未使用 AI 精选，信号已按强度排序",
                riskWarning = "",
                marketDirection = marketDirection
            )
        }

        context.log(nodeId, "${allStrategies.count { it.requiresAIRefine }} 个策略需要 AI 精选，启动 AI 预测")

        return try {
            val prediction = engine.predict(
                strategyResults = screeningResults,
                selectedDate = context.tradeDate,
                useEnhancedAi = useEnhancedAi,
                marketContext = marketContextStr,
                sectorContext = sectorContext
            )

            prediction ?: run {
                context.log(nodeId, "AI 预测返回 null，回退为规则排序候选（保留候选供最终挑选）")
                AIPredictionEngine.AIPrediction(
                    mode = "FALLBACK",
                    modeReason = "AI 预测引擎返回空结果，候选按强度排序保留（未做 AI 精选）",
                    topPicks = rankedPicks(screeningResults.flatMap { it.signals }),
                    marketOutlook = "AI 不可用：以下候选已按信号强度排序，供你最终挑选",
                    riskWarning = "AI 预测不可用，候选保留但未经 AI 精选",
                    marketDirection = marketDirection
                )
            }
        } catch (e: Exception) {
            context.log(nodeId, "AI 预测异常: ${e.message}，回退为规则排序候选")
            AIPredictionEngine.AIPrediction(
                mode = "ERROR",
                modeReason = "AI 预测引擎异常: ${e.message}，候选按强度排序保留",
                topPicks = rankedPicks(screeningResults.flatMap { it.signals }),
                marketOutlook = "AI 不可用：以下候选已按信号强度排序，供你最终挑选",
                riskWarning = "AI 预测不可用，候选保留但未经 AI 精选",
                marketDirection = marketDirection
            )
        }
    }

    /**
     * AI 不可用时的兜底裁切：按信号强度排序保留 top 候选，
     * 保证「最终面对多只候选不知如何下手」时仍有清晰排序清单可人工挑选。
     */
    private fun rankedPicks(
        signals: List<StrategySignal>,
        takeN: Int = 5
    ): List<AIPredictionEngine.AIPick> =
        signals.sortedByDescending { it.strength }
            .take(takeN)
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
}

// ════════════════════════════════════════════════════════════════════════════
//  8. MainBoardFilterNode (FILTER)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 主板过滤节点
 *
 * 从股票池中过滤出沪深主板股票，排除 ETF、LOF、转债、科创板、创业板、北交所等非主板品种。
 *
 * 排除规则（股票代码前缀）：
 * - `sh51*` — 上证 ETF
 * - `sh56*` — 上证 ETF（跨境）
 * - `sz15*` — 深证 ETF / LOF
 * - `sz16*` — 深证 ETF / LOF
 * - `bj8*`  — 北交所股票
 * - `sh688*` / `sh689*` — 科创板股票
 * - `sz300*` / `sz301*` — 创业板股票
 */
class MainBoardFilterNode : BaseNode<Any, StockPool>("main_board_filter", "主板股票过滤", NodeType.FILTER) {

    companion object {
        /** 排除的股票代码前缀集合（与 StrategyDataFeed.isMainBoard 语义保持一致） */
        private val EXCLUDED_PREFIXES = setOf("sh51", "sh56", "sz15", "sz16", "bj8", "sh688", "sh689", "sz300", "sz301")

        /**
         * 判断股票代码是否为主板股票
         * @param code 股票代码（如 sh600519）
         * @return true 表示是主板股票（保留），false 表示需要排除
         */
        fun isMainBoardStock(code: String): Boolean {
            return EXCLUDED_PREFIXES.none { prefix -> code.startsWith(prefix) }
        }
    }

    override suspend fun execute(context: PipelineContext, input: Any): StockPool {
        return try {
            // 从 input 或 context 中按需读取 StockPool
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
                "主板过滤完成: $originalSize → ${result.size} 只 (排除 $excludedCount 只)"
            )
            result
        } catch (e: Exception) {
            context.log(nodeId, "主板过滤异常: ${e.message}")
            // 出错时尝试返回原始股票池
            (input as? StockPool) ?: StockPool(emptyList(), "empty")
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  9. StockPoolFilterNode (FILTER) — 股票池清洗（ST / 主板开关）
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 股票池过滤节点（ST / 主板开关）
 *
 * 在公共选股管线 `stock_picking_common_pipeline` 中紧跟在 `n_pool` 之后执行，
 * 对股票池做两级硬过滤，从源头减小喂给各周期策略的输入规模：
 *
 * 1. **ST 过滤（无条件）**：名称含 `ST`/`*ST` 的退市风险股一律剔除；
 * 2. **主板开关过滤（条件）**：当 [PipelineConfig.onlyMainBoard] = true（用户开启主板偏好）时，
 *    剔除科创板（sh688/sh689）、创业板（sz300/sz301）及北交所/ETF 等非主板品种
 *    （复用 [MainBoardFilterNode.isMainBoardStock] 规则）。
 *
 * 过滤结果会**覆盖写回 `n_pool`**，使下游策略节点通过 `getStageOutput("n_pool")`
 * 兜底读取时拿到的是清洗后的股票池，避免 ST/非主板风险股流入选股链路。
 */
class StockPoolFilterNode : BaseNode<Any, StockPool>("pool_filter", "股票池过滤(ST/科创/创业)", NodeType.FILTER) {

    companion object {
        /** 判断是否为 ST / *ST 退市风险股 */
        fun isStStock(name: String): Boolean = name.contains("ST", ignoreCase = true)
    }

    override suspend fun execute(context: PipelineContext, input: Any): StockPool {
        return try {
            // 从 input 或 context 中读取股票池（优先本节点写回的 n_pool）
            val pool: StockPool = when (input) {
                is StockPool -> input
                else -> context.getStageOutput<StockPool>("n_pool")
                    ?: context.getStageOutput<StockPool>("stock_pool")
                    ?: return StockPool(emptyList(), "empty")
            }

            val onlyMainBoard = context.config.onlyMainBoard
            var stExcluded = 0
            var boardExcluded = 0
            val kept = pool.stocks.filter { stock ->
                when {
                    isStStock(stock.name) -> { stExcluded++; false }
                    onlyMainBoard && !MainBoardFilterNode.isMainBoardStock(stock.code) -> { boardExcluded++; false }
                    else -> true
                }
            }
            val excluded = stExcluded + boardExcluded

            val result = StockPool(
                stocks = kept,
                source = pool.source,
                totalCount = pool.totalCount,
                filterReason = buildString {
                    append(pool.filterReason)
                    if (stExcluded > 0) { if (isNotEmpty()) append("; "); append("排除ST $stExcluded 只") }
                    if (boardExcluded > 0) { if (isNotEmpty()) append("; "); append("排除非主板 $boardExcluded 只") }
                }
            )

            // 覆盖写回 n_pool，让下游策略节点拿到清洗后的股票池
            context.setStageOutput("n_pool", result)
            context.setStageOutput(nodeId, result)
            context.recordStockFlow(
                nodeId = nodeId,
                nodeName = nodeName,
                inputCount = pool.stocks.size,
                outputCount = kept.size,
                filterCount = excluded,
                filterReason = "ST $stExcluded / 主板开关 onlyMainBoard=$onlyMainBoard 排除 $boardExcluded"
            )
            context.log(
                nodeId,
                "股票池过滤: ${pool.stocks.size} → ${kept.size} 只（ST $stExcluded, 非主板 $boardExcluded, onlyMainBoard=$onlyMainBoard）"
            )
            result
        } catch (e: Exception) {
            context.log(nodeId, "股票池过滤异常: ${e.message}")
            (input as? StockPool) ?: context.getStageOutput<StockPool>("n_pool")
                ?: StockPool(emptyList(), "empty")
        }
    }
}
