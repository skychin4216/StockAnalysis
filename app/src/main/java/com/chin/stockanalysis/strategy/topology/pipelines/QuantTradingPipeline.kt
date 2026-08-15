package com.chin.stockanalysis.strategy.topology.pipelines

import android.content.Context
import com.chin.stockanalysis.agent.v2.ProfitQualityLevel
import com.chin.stockanalysis.agent.v2.ProfitQualityAnalyzer
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.AppBackgroundRunner
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.backtest.FullCycleBacktestEngine
import com.chin.stockanalysis.strategy.backtest.StrategyOptimizer
import com.chin.stockanalysis.strategy.data.SmartMoneyCache
import com.chin.stockanalysis.strategy.market.MarketAdaptiveStrategy
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.topology.nodes.MainBoardFilterNode
import com.chin.stockanalysis.strategy.trade.AutoSellEngine
import com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity
import com.chin.stockanalysis.strategy.trade.StrategyTradeFittingParamEntity
import com.chin.stockanalysis.strategy.trade.TradeOrder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

// ════════════════════════════════════════════════════════════════════════════
//  量化交易 Pipeline（QuantTradingPipeline）
//
//  涵盖完整量化交易流程的节点与数据类型：
//  ── 市场分析 ──
//  1. AdaptiveParamsNode         — 大盘分析+自适应参数（动态阈值/数量限制）
//  2. MultiPeriodHotNode         — 多周期热门股聚合（3/5/10/30/50/100天涨幅Top3）
//  3. CrossDayAggregationNode    — 跨日聚合（回溯N天策略命中频次）
//  ── 信号增强 ──
//  4. HeatScoreNode              — 热度计算（5维热度分数 0-100）
//  5. NewsStrengthNode           — 新闻力度计算（近3天新闻影响力×情绪）
//  6. RotationPenaltyNode        — 板块轮动惩罚（防止板块过度集中）
//  7. NewsGuardNode              — 新闻拦截（买入前利空拦截）+ 技术过滤（4条规则）
//  ── 交易执行 ──
//  8. GenerateOrdersNode         — 买入订单生成（AI精选 + 自适应参数）
//  9. PositionMergeNode          — 持仓合并（追加加权平均 / 新增PENDING订单）
//  10. SwapWeakNode              — 腾龙换鸟（仓位不足时自动换股）
//  11. HoldingGuardNode          — 持仓风控（止损/止盈/策略退出）
//  ── 基础设施 ──
//  12. BackgroundManagerNode     — 后台暂停/恢复（AI分析前暂停，分析后恢复）
//  13. FittingSaveNode           — 拟合计算+保存（网格搜索拟合结果保存到DB）
// ════════════════════════════════════════════════════════════════════════════

private const val TAG = "QuantTrading"
private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

// ──────────────────────────────────────────────────────────────────────────────
//  辅助函数
// ──────────────────────────────────────────────────────────────────────────────

/** 格式化 top N 代码列表用于日志输出 */
private fun formatTopCodes(codes: Collection<String>, limit: Int = 5): String =
    codes.take(limit).joinToString(prefix = "[", separator = ", ", postfix = "]")

/** 格式化代码+名称对用于日志输出 */
private fun formatTopCodeNames(pairs: Collection<Pair<String, String>>, limit: Int = 5): String =
    pairs.take(limit).joinToString(prefix = "[", separator = ", ", postfix = "]") { "${it.first}(${it.second})" }

// ──────────────────────────────────────────────────────────────────────────────
//  数据类
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 跨日聚合结果：股票代码 → 被命中的天数
 */
data class CrossDayResult(
    val rankings: List<Pair<String, Int>>,  // (stockCode, hitDays) 按命中天数降序
    val windowDays: Int,                    // 实际回溯天数
    val baseDate: String                    // 基准日期
)

/**
 * 多周期热门股结果：去重后的热门股票代码集合
 */
data class MultiPeriodHotResult(
    val hotStocks: Set<String>,            // 各周期 Top3 去重合集
    val periodCount: Int,                   // 成功计算的周期数
    val periods: List<Int>                  // 计算的周期列表
)

/**
 * 新闻拦截结果
 */
data class NewsGuardResult(
    val blockedCodes: Set<String>,          // 被拦截的股票代码
    val blockedReasons: Map<String, String>, // code → 拦截原因
    val technicalFilteredCodes: Set<String>, // 技术规则过滤的代码
    val passedCodes: Set<String>            // 最终通过的代码
)

/**
 * 腾龙换鸟结果
 *
 * orders 透传上游 GenerateOrdersNode 的买入订单，无论是否换股都不丢弃。
 * 下游 PositionMergeNode 从 orders 中提取订单执行持仓入库。
 */
data class SwapWeakResult(
    val swappedCount: Int,                  // 实际换股数量
    val soldStocks: List<String>,          // 被卖出的股票（名称）
    val beforeCount: Int,                   // 换股前持仓数
    val afterCount: Int,                    // 换股后持仓数
    val orders: List<TradeOrder> = emptyList()  // 透传买入订单
)

/**
 * 持仓风控结果
 */
data class HoldingGuardResult(
    val soldCount: Int,                     // 风控卖出数量（止损/止盈/策略退出）
    val soldStocks: List<String>,          // 被卖出的股票（名称+原因）
    val remainingCount: Int,               // 卖出后剩余持仓数
    val evaluatedCount: Int                 // 评估的持仓总数
)

/**
 * 买入订单生成结果
 */
data class OrderGenerationResult(
    val orders: List<TradeOrder>,
    val filteredCount: Int,
    val emptyTriggered: Boolean
)

/**
 * 持仓合并结果
 */
data class PositionMergeResult(
    val newCount: Int,
    val updatedCodes: List<String>,
    val totalHoldings: Int
)

// ════════════════════════════════════════════════════════════════════════════
//  1. CrossDayAggregationNode (AGGREGATION)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 跨日聚合节点
 *
 * 回溯最近 [windowDays] 个交易日，对股票池中的股票逐一重跑策略，
 * 统计每只股票被命中的天数，取 Top [topN] 只。
 *
 * 中线独有：短线只用当日数据，中线需要连续性验证。
 *
 * @property windowDays 回溯天数（默认 5）
 * @property topN 取 Top N（默认 20）
 * @property strategies 策略列表（外部注入，避免从 context 读取）
 */
class CrossDayAggregationNode(
    private val windowDays: Int = 5,
    private val topN: Int = 20,
    private val strategies: List<Strategy> = emptyList()
) : BaseNode<Any, CrossDayResult>("cross_day_aggregation", "跨日聚合", NodeType.AGGREGATION) {

    override suspend fun execute(context: PipelineContext, input: Any): CrossDayResult {
        // 从不同上游类型中提取股票代码集合
        val poolCodes: Set<String> = when (input) {
            is Set<*> -> input.filterIsInstance<String>().toSet()
            is StockPool -> input.stocks.map { it.code }.toSet()
            is MultiPeriodHotResult -> input.hotStocks
            is List<*> -> {
                // 聚合节点收到多个上游输出，合并所有股票代码
                val codes = mutableSetOf<String>()
                for (item in input) {
                    when (item) {
                        is Set<*> -> codes.addAll(item.filterIsInstance<String>())
                        is StockPool -> codes.addAll(item.stocks.map { it.code })
                        is MultiPeriodHotResult -> codes.addAll(item.hotStocks)
                    }
                }
                codes
            }
            else -> {
                context.log(nodeId, "⚠ 未知输入类型: ${input::class.simpleName}，尝试从 context 读取股票池")
                context.getStageOutput<StockPool>("stock_pool")?.stocks?.map { it.code }?.toSet() ?: emptySet()
            }
        }

        // 📥 输入日志
        context.log(nodeId, "📥 $nodeName 输入: ${poolCodes.size} 只股票 ${formatTopCodes(poolCodes)}")

        if (poolCodes.isEmpty()) {
            context.log(nodeId, "股票池为空，跳过跨日聚合")
            return CrossDayResult(emptyList(), 0, context.tradeDate)
        }

        val effectiveStrategies = strategies.ifEmpty {
            context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
        }
        if (effectiveStrategies.isEmpty()) {
            context.log(nodeId, "无可用策略，跳过跨日聚合")
            return CrossDayResult(emptyList(), 0, context.tradeDate)
        }

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            val allDates = db.dailySnapshotDao().getAvailableDates(windowDays + 5)
                .filter { it <= context.tradeDate }
                .take(windowDays)

            if (allDates.size < 2) {
                context.log(nodeId, "跨日聚合: 仅 ${allDates.size} 天可用，数据不足")
                return CrossDayResult(emptyList(), allDates.size, context.tradeDate)
            }

            val hitCounts = mutableMapOf<String, Int>()

            for (date in allDates) {
                try {
                    val snaps = db.dailySnapshotDao().getByDate(date).filter { it.code in poolCodes }
                    if (snaps.isEmpty()) continue

                    val stockList = snaps.map { snap ->
                        val yestClose = if (snap.changePct != 0.0 && snap.close != 0.0) {
                            snap.close / (1.0 + snap.changePct / 100.0)
                        } else snap.close
                        StockRealtime(
                            code = snap.code, name = snap.name, price = snap.close,
                            open = snap.open, yestClose = yestClose,
                            high = snap.high, low = snap.low,
                            volume = snap.volume, amount = snap.amount,
                            changePercent = snap.changePct,
                            changeAmount = snap.close * snap.changePct / 100,
                            timestamp = System.currentTimeMillis()
                        )
                    }

                    val seenToday = mutableSetOf<String>()
                    for (strategy in effectiveStrategies) {
                        if (strategy.id == "ai_prediction") continue
                        try {
                            val result = strategy.screenWithData(stockList)
                            result.getOrNull()?.signals
                                ?.filter { it.stockCode !in seenToday }
                                ?.forEach { signal ->
                                    hitCounts[signal.stockCode] = (hitCounts[signal.stockCode] ?: 0) + 1
                                    seenToday.add(signal.stockCode)
                                }
                        } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }

            val rankings = hitCounts.entries
                .sortedByDescending { it.value }
                .take(topN)
                .map { it.key to it.value }

            context.setStageOutput(nodeId, rankings)

            // 📤 输出日志
            val outputCodes = rankings.map { it.first }
            context.log(nodeId, "📤 $nodeName 输出: ${rankings.size} 只股票 ${formatTopCodes(outputCodes)}")

            // 🚫 过滤日志
            val filteredCount = poolCodes.size - rankings.size
            if (filteredCount > 0) {
                context.log(nodeId, "🚫 $nodeName 过滤掉: $filteredCount 只（未入围 Top$topN）")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = poolCodes.size, outputCount = rankings.size,
                filterCount = filteredCount,
                filterReason = if (filteredCount > 0) "未入围 Top$topN" else "",
                inputCodes = poolCodes.take(5), outputCodes = outputCodes.take(5)
            )

            context.log(nodeId, "跨日聚合完成: ${allDates.size} 天回溯, " +
                "${poolCodes.size} 只股票, ${rankings.size} 只入围" +
                (rankings.take(3).joinToString(prefix = " Top3=", separator = ",") { "${it.first}(${it.second}天)" }))

            CrossDayResult(rankings, allDates.size, context.tradeDate)
        } catch (e: Exception) {
            context.log(nodeId, "跨日聚合失败: ${e.message}")
            context.recordError(nodeId, "跨日聚合失败: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = poolCodes.size, outputCount = 0,
                filterCount = poolCodes.size,
                filterReason = "执行失败: ${e.message}",
                inputCodes = poolCodes.take(5), outputCodes = emptyList()
            )
            CrossDayResult(emptyList(), 0, context.tradeDate)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  2. MultiPeriodHotNode (DATA_SOURCE)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 多周期热门股聚合节点
 *
 * 对多个时间周期（3/5/10/30/50/100天），分别计算累计涨幅 Top3 股票，
 * 去重后合并为一个热门股集合，扩大中线精选池。
 *
 * 中线独有：短线只看当日热门，中线需要多周期验证趋势延续性。
 *
 * @property periods 计算的周期天数列表（默认 [3, 5, 10, 30, 50, 100]）
 * @property topNPerPeriod 每个周期取 Top N（默认 3）
 * @property onlyMainBoard 是否只看主板（默认 true）
 */
class MultiPeriodHotNode(
    private val periods: List<Int> = listOf(3, 5, 10, 30, 50, 100),
    private val topNPerPeriod: Int = 3,
    private val onlyMainBoard: Boolean = true
) : BaseNode<Any, MultiPeriodHotResult>("multi_period_hot", "多周期热门股聚合", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): MultiPeriodHotResult {
        val db = StockDatabase.getInstance(context.androidContext)

        // 📥 输入日志（独立数据源，无直接输入）
        context.log(nodeId, "📥 $nodeName 输入: 独立数据源, 从DB加载 ${periods.size} 个周期数据")

        return try {
            val allDates = db.dailySnapshotDao().getAvailableDates(120).sorted()
            val hotSet = mutableSetOf<String>()
            var successCount = 0

            for (days in periods) {
                val startIdx = allDates.indexOf(context.tradeDate) - days
                if (startIdx < 0) continue

                val slices = allDates.subList(startIdx.coerceAtLeast(0), allDates.size)
                val sliceMap = mutableMapOf<String, Double>()

                for (date in slices) {
                    try {
                        val snaps = db.dailySnapshotDao().getByDate(date)
                        for (snap in snaps) {
                            if (onlyMainBoard && !MainBoardFilterNode.isMainBoardStock(snap.code)) continue
                            sliceMap[snap.code] = (sliceMap[snap.code] ?: 0.0) + snap.changePct
                        }
                    } catch (_: Exception) { continue }
                }

                sliceMap.entries
                    .sortedByDescending { it.value }
                    .take(topNPerPeriod)
                    .mapTo(hotSet) { it.key }
                successCount++
            }

            context.setStageOutput(nodeId, hotSet)

            // 📤 输出日志
            context.log(nodeId, "📤 $nodeName 输出: ${hotSet.size} 只股票 ${formatTopCodes(hotSet)}")

            // 🚫 过滤日志
            if (onlyMainBoard) {
                context.log(nodeId, "🚫 $nodeName 过滤: 已排除非主板股票（前缀 sh51/sh56/sz15/sz16/bj8）")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = periods.size, outputCount = hotSet.size,
                filterCount = if (hotSet.isEmpty()) periods.size else 0,
                filterReason = if (hotSet.isEmpty()) "无热门股票" else "",
                inputCodes = emptyList(), outputCodes = hotSet.toList().take(5)
            )

            context.log(nodeId, "多周期热门股: ${successCount}/${periods.size} 周期成功, " +
                "${hotSet.size} 只热门股去重")

            MultiPeriodHotResult(hotSet, successCount, periods)
        } catch (e: Exception) {
            context.log(nodeId, "多周期热门股失败: ${e.message}")
            context.recordError(nodeId, "多周期热门股失败: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = periods.size, outputCount = 0,
                filterCount = periods.size,
                filterReason = "执行失败: ${e.message}",
                inputCodes = emptyList(), outputCodes = emptyList()
            )
            MultiPeriodHotResult(emptySet(), 0, periods)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  3. NewsStrengthNode (ENRICHMENT)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 新闻力度计算节点
 *
 * 查询近 [lookbackDays] 天的活跃新闻因子，计算与信号股票相关新闻的
 * 平均影响力 × 情绪分，生成 0-100 的新闻力度分数。
 *
 * 计算公式：score = avgStrength × (0.5 + avgSentiment × 0.3)
 * 无相关新闻默认 50 分。
 *
 * 中线独有：短线不计算新闻力度，中线需要新闻面支撑。
 *
 * @property lookbackDays 回溯天数（默认 3）
 */
class NewsStrengthNode(
    private val lookbackDays: Int = 3
) : BaseNode<Any, Int>("news_strength", "新闻力度计算", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): Int {
        // 从 input 或 context 中按需读取 MergedSignalPool
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> context.getStageOutput<MergedSignalPool>("sector_boost")
                ?: context.getStageOutput<MergedSignalPool>("n_boost")
                ?: context.getStageOutput<MergedSignalPool>("signal_merge")
                ?: context.getStageOutput<MergedSignalPool>("n_merge")
                ?: MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        }
        val db = StockDatabase.getInstance(context.androidContext)

        // 📥 输入日志
        context.log(nodeId, "📥 $nodeName 输入: ${pool.totalStocks} 只股票 ${formatTopCodes(pool.stockHits.keys)}")

        return try {
            // 新闻查询用实际日期（周日执行也能吃到周末新闻），而非 tradeDate
            val newsToDate = LocalDate.now().format(DATE_FMT)
            val fromDate = LocalDate.now()
                .minusDays(lookbackDays.toLong())
                .format(DATE_FMT)

            val newsList = db.newsFactorDao().getActiveByDateRange(fromDate, newsToDate)

            if (newsList.isEmpty()) {
                context.log(nodeId, "新闻力度: 近 ${lookbackDays} 天无活跃新闻, 默认 50 分")
                // 📤 输出日志
                context.log(nodeId, "📤 $nodeName 输出: score=50 (无新闻数据)")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = pool.totalStocks, outputCount = pool.totalStocks,
                    filterCount = 0, filterReason = "",
                    inputCodes = pool.stockHits.keys.toList().take(5),
                    outputCodes = pool.stockHits.keys.toList().take(5)
                )
                return 50
            }

            val signalCodes = pool.stockHits.keys
            val related = newsList.filter { it.stockCode in signalCodes || it.sector.isNotEmpty() }

            if (related.isEmpty()) {
                context.log(nodeId, "新闻力度: ${newsList.size} 条新闻中无相关, 默认 40 分")
                // 📤 输出日志
                context.log(nodeId, "📤 $nodeName 输出: score=40 (无相关新闻)")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = pool.totalStocks, outputCount = pool.totalStocks,
                    filterCount = 0, filterReason = "",
                    inputCodes = pool.stockHits.keys.toList().take(5),
                    outputCodes = pool.stockHits.keys.toList().take(5)
                )
                return 40
            }

            val avgStr = related.map { it.impactStrength.toDouble() }.average()
            val avgSent = related.map { it.sentiment.toDouble() }.average()
            val score = (avgStr * (0.5 + avgSent * 0.3)).roundToInt().coerceIn(0, 100)

            context.setStageOutput(nodeId, score)

            // 📤 输出日志
            context.log(nodeId, "📤 $nodeName 输出: score=$score")

            // 🚫 过滤日志
            val unrelatedNewsCount = newsList.size - related.size
            if (unrelatedNewsCount > 0) {
                context.log(nodeId, "🚫 $nodeName 过滤掉: $unrelatedNewsCount 条无关新闻")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.totalStocks, outputCount = pool.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = pool.stockHits.keys.toList().take(5),
                outputCodes = pool.stockHits.keys.toList().take(5)
            )

            // ═══ 补齐 Hardcode：固化每日新闻热点 Top3 到 daily_news_hot_picks 表 ═══
            try {
                saveNewsHotPicks(context, related, context.tradeDate)
            } catch (e2: Exception) {
                context.log(nodeId, "新闻热点固化失败（不阻塞）: ${e2.message}")
            }

            context.log(nodeId, "新闻力度: ${related.size} 条相关新闻, " +
                "avgImpact=${"%.1f".format(avgStr)}, avgSentiment=${"%.1f".format(avgSent)}, " +
                "score=$score")

            score
        } catch (e: Exception) {
            context.log(nodeId, "新闻力度计算失败: ${e.message}, 默认 50 分")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.totalStocks, outputCount = pool.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = pool.stockHits.keys.toList().take(5),
                outputCodes = pool.stockHits.keys.toList().take(5)
            )
            50
        }
    }

    /**
     * 补齐 Hardcode：按板块聚合新闻热点，取 Top3 固化到 daily_news_hot_picks 表。
     *
     * 聚合规则：
     * - 按新闻的 sector 栏位分组
     * - 每个板块的 hotScore = 该板块新闻的平均 impactStrength
     * - 按热度降序取 Top3
     * - relatedStockCodes = 该板块新闻涉及的股票代码集合
     */
    private suspend fun saveNewsHotPicks(
        context: PipelineContext,
        relatedNews: List<com.chin.stockanalysis.news.NewsFactorEntity>,
        tradeDate: String
    ) {
        if (relatedNews.isEmpty()) return

        // 按板块聚合
        val sectorGroups = relatedNews
            .filter { it.sector.isNotEmpty() }
            .groupBy { it.sector }

        if (sectorGroups.isEmpty()) return

        // 计算每个板块的热度分数
        val sectorScores = sectorGroups.map { (sector, newsList) ->
            val avgImpact = newsList.map { it.impactStrength }.average().toInt()
            val relatedCodes = newsList.map { it.stockCode }.filter { it.isNotEmpty() }.distinct()
            val topNews = newsList.maxByOrNull { it.impactStrength }
            Triple(sector, avgImpact, relatedCodes to (topNews?.title ?: ""))
        }.sortedByDescending { it.second }

        val db = StockDatabase.getInstance(context.androidContext)
        val hotPickDao = db.dailyNewsHotPickDao()

        // 取 Top3 写入
        for ((rank, item) in sectorScores.take(3).withIndex()) {
            val (sector, hotScore, pair) = item
            val (relatedCodes, newsTitle) = pair
            val entity = com.chin.stockanalysis.strategy.trade.DailyNewsHotPickEntity(
                newsDate = tradeDate,
                rank = rank + 1,
                sectorName = sector,
                subSectorName = "",  // 子板块由 StockDataCenter 动态查询，此处留空
                hotScore = hotScore,
                newsTitle = newsTitle.take(200),
                relatedStockCodes = relatedCodes.joinToString(",")
            )
            hotPickDao.insert(entity)
        }

        context.log(nodeId, "📰 新闻热点固化: ${minOf(3, sectorScores.size)} 个板块写入 daily_news_hot_picks")
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  4. RotationPenaltyNode (ENRICHMENT)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 板块轮动惩罚结果（v2）
 *
 * 不再只输出单一总惩罚（死值），而是携带**板块级惩罚映射**，
 * 供下游 [SmartMoneyFilterNode] 对受罚板块中的股票逐股降分，真正影响选股。
 *
 * @property rotationPenalty 总惩罚分（≤0，供报表 rotationPenalty 字段 / 历史记录）
 * @property sectorPenalties 板块名 → 惩罚分（负值，命中受罚板块的股票在主力资金过滤中降分）
 * @property rotationSpeedFactor 跨日轮动因子（1.3 快速轮动加重 / 0.7 持续性强放宽 / 1.0 中性）
 * @property overlap 今日与昨日 top10 板块重合度（0~1）
 */
data class RotationPenaltyResult(
    val rotationPenalty: Int,
    val sectorPenalties: Map<String, Int>,
    val rotationSpeedFactor: Double = 1.0,
    val overlap: Double = 0.5
)

/**
 * ## 板块轮动惩罚节点 v2
 *
 * 三维度惩罚机制：
 * 1. **集中度惩罚**（对数衰减）：某板块信号数超过阈值后施加惩罚，但用 log2 衰减而非线性
 * 2. **板块生命周期**：结合 consecutiveHotDays 判断板块所处阶段
 *    - 刚启动(≤2天) → 不惩罚
 *    - 高潮期(3-4天) → 轻度惩罚
 *    - 退潮期(≥5天) → 重度惩罚
 * 3. **跨日轮动检测**：比较今天和昨天的选股板块分布
 *    - 重合度低(<30%) → 轮动快，分散持仓惩罚加重
 *    - 重合度高(>70%) → 板块持续性强，集中度惩罚放宽
 *
 * 输出为 [RotationPenaltyResult]，其中 `sectorPenalties` 供下游
 * 主力资金过滤（smart_money_filter）对受罚板块个股降分，实现惩罚闭环。
 *
 * @property thresholdDays 板块集中度阈值（默认 3）
 * @property penaltyPerExcess 基础惩罚分数（默认 10）
 */
class RotationPenaltyNode(
    private val thresholdDays: Int = 3,
    private val penaltyPerExcess: Int = 10
) : BaseNode<Any, RotationPenaltyResult>("rotation_penalty", "板块轮动惩罚", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): RotationPenaltyResult {
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> context.getStageOutput<MergedSignalPool>("sector_boost")
                ?: context.getStageOutput<MergedSignalPool>("n_boost")
                ?: context.getStageOutput<MergedSignalPool>("signal_merge")
                ?: context.getStageOutput<MergedSignalPool>("n_merge")
                ?: MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        }
        context.log(nodeId, "📥 $nodeName 输入: ${pool.totalStocks} 只股票 ${formatTopCodes(pool.stockHits.keys)}")

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val today = LocalDate.now().toString()
            val yesterday = try {
                val tradingDays = db.dailySnapshotDao().getByCode("sh000001", 5).sortedBy { it.date }
                if (tradingDays.size >= 2) tradingDays[tradingDays.size - 2].date else today
            } catch (_: Exception) { today }

            // ── 1. 板块集中度统计 ──
            val sectorCounts = mutableMapOf<String, Int>()
            for (code in pool.stockHits.keys) {
                val sectors = StockDataCenter.getSectorsByStock(code)
                for (sector in sectors) {
                    sectorCounts[sector] = (sectorCounts[sector] ?: 0) + 1
                }
            }

            // ── 2. 板块连续热门天数（生命周期） ──
            val sectorHotDays = mutableMapOf<String, Int>()
            try {
                val todayRecords = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    db.sectorDailyRecordDao().getByDate(today)
                }
                for (record in todayRecords) {
                    sectorHotDays[record.sectorName] = record.consecutiveHotDays
                }
            } catch (_: Exception) { /* 数据可能不存在 */ }

            // ── 3. 跨日轮动检测 ──
            val yesterdaySectors = try {
                val yesterdayRecords = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    db.sectorDailyRecordDao().getByDate(yesterday)
                }
                yesterdayRecords.filter { it.rank <= 10 }.map { it.sectorName }.toSet()
            } catch (_: Exception) { emptySet() }

            val todayTopSectors = sectorCounts.keys.filter { (sectorCounts[it] ?: 0) >= 2 }.toSet()
            val overlap = if (todayTopSectors.isNotEmpty() && yesterdaySectors.isNotEmpty()) {
                todayTopSectors.intersect(yesterdaySectors).size.toDouble() / todayTopSectors.size
            } else 0.5  // 无数据时默认中等轮动
            val rotationSpeedFactor = when {
                overlap < 0.3 -> 1.3   // 快速轮动 → 惩罚加重
                overlap > 0.7 -> 0.7   // 板块持续性强 → 惩罚放宽
                else -> 1.0
            }

            // ── 4. 综合惩罚计算 ──
            var penalty = 0
            val sectorPenalties = mutableMapOf<String, Int>()  // 板块 → 惩罚分（负值，供下游逐股降分）
            val penalizedSectors = mutableListOf<String>()
            for ((sector, count) in sectorCounts) {
                if (count < thresholdDays) continue

                // 4a. 集中度惩罚（对数衰减）
                val excess = count - thresholdDays + 1
                val basePenalty = (log2(excess + 1.0) * penaltyPerExcess).toInt()

                // 4b. 板块生命周期系数
                val hotDays = sectorHotDays[sector] ?: 0
                val lifecycleFactor = when {
                    hotDays <= 2 -> 0.5   // 刚启动，惩罚减半
                    hotDays <= 4 -> 1.0   // 高潮期，正常惩罚
                    else -> 1.5            // 退潮期，惩罚加重50%
                }

                val sectorPenalty = (basePenalty * lifecycleFactor).toInt()
                penalty -= sectorPenalty
                sectorPenalties[sector] = -sectorPenalty

                val lifecycleLabel = when {
                    hotDays <= 2 -> "启动${hotDays}d"
                    hotDays <= 4 -> "高潮${hotDays}d"
                    else -> "退潮${hotDays}d"
                }
                penalizedSectors.add("$sector(${count}次/$lifecycleLabel/-${sectorPenalty})")
            }

            // 4c. 跨日轮动因子（总惩罚与板块级惩罚同步缩放，保持口径一致）
            penalty = (penalty * rotationSpeedFactor).toInt()
            val finalPenalty = penalty.coerceIn(-100, 0)
            val scaledSectorPenalties = sectorPenalties.mapValues { (_, p) ->
                (p * rotationSpeedFactor).toInt().coerceIn(-100, 0)
            }
            val result = RotationPenaltyResult(
                rotationPenalty = finalPenalty,
                sectorPenalties = scaledSectorPenalties,
                rotationSpeedFactor = rotationSpeedFactor,
                overlap = overlap
            )

            context.setStageOutput(nodeId, result)

            context.log(nodeId, "📤 $nodeName 输出: penalty=$finalPenalty (轮动因子=${"%.1f".format(rotationSpeedFactor)})")

            if (finalPenalty < 0) {
                context.log(nodeId, "板块轮动惩罚v2: $finalPenalty 分, " +
                    "惩罚板块: ${penalizedSectors.joinToString()}, " +
                    "跨日重合度=${"%.0f".format(overlap * 100)}%")
            } else {
                context.log(nodeId, "板块轮动惩罚v2: 无惩罚（板块分散度正常）")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.totalStocks, outputCount = pool.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = pool.stockHits.keys.toList().take(5),
                outputCodes = pool.stockHits.keys.toList().take(5)
            )

            result
        } catch (e: Exception) {
            context.log(nodeId, "轮动惩罚v2计算失败: ${e.message}, 默认 0")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.totalStocks, outputCount = pool.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = pool.stockHits.keys.toList().take(5),
                outputCodes = pool.stockHits.keys.toList().take(5)
            )
            RotationPenaltyResult(0, emptyMap())
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  5. NewsGuardNode (FILTER)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 新闻拦截 + 技术过滤节点
 *
 * 两层防护：
 * 1. **新闻拦截**：买入前检查近 [lookbackDays] 天是否有重大利空新闻
 *    （影响力 >= [impactThreshold] 且情绪 < [sentimentThreshold]）
 * 2. **技术过滤**（4 条规则）：
 *    - 大阴线不抄（跌幅 >= 7%）
 *    - 一字板不跳（涨停一字板）
 *    - 均线空头不搞（近5日收盘价前3日低于5日均线）
 *    - 顶背离不追（小幅涨但上影线长且连续3日以上阳线）
 *
 * @property lookbackDays 新闻回溯天数（默认 3）
 * @property impactThreshold 影响力阈值（默认 75）
 * @property sentimentThreshold 情绪阈值（默认 -30）
 */
class NewsGuardNode(
    private val lookbackDays: Int = 3,
    private val impactThreshold: Int = 75,
    private val sentimentThreshold: Int = -30
) : BaseNode<Any, NewsGuardResult>("news_guard", "新闻拦截+技术过滤", NodeType.FILTER) {

    override suspend fun execute(context: PipelineContext, input: Any): NewsGuardResult {
        // 从不同上游类型中提取候选股票代码
        val candidateCodes: Set<String> = when (input) {
            is Set<*> -> input.filterIsInstance<String>().toSet()
            is MergedSignalPool -> input.stockHits.keys
            is List<*> -> input.filterIsInstance<String>().toSet()
            else -> {
                context.log(nodeId, "⚠ 未知输入类型: ${input::class.simpleName}，尝试从 context 读取")
                context.getStageOutput<MergedSignalPool>("smart_money_filter")?.stockHits?.keys ?: emptySet()
            }
        }

        // 📥 输入日志
        context.log(nodeId, "📥 $nodeName 输入: ${candidateCodes.size} 只股票 ${formatTopCodes(candidateCodes)}")

        if (candidateCodes.isEmpty()) {
            return NewsGuardResult(emptySet(), emptyMap(), emptySet(), emptySet())
        }

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 1. 新闻拦截（用实际日期，周日执行也能吃到周末新闻）
            val newsToDate = LocalDate.now().format(DATE_FMT)
            val fromDate = LocalDate.now()
                .minusDays(lookbackDays.toLong())
                .format(DATE_FMT)

            val newsList = db.newsFactorDao().getActiveByDateRange(fromDate, newsToDate)
            val blockedCodes = mutableSetOf<String>()
            val blockedReasons = mutableMapOf<String, String>()

            for (news in newsList) {
                if (news.stockCode.isBlank()) continue
                if (news.stockCode in candidateCodes &&
                    news.impactStrength >= impactThreshold &&
                    news.sentiment < sentimentThreshold) {
                    blockedCodes.add(news.stockCode)
                    blockedReasons[news.stockCode] = "利空拦截: ${news.title.take(40)} " +
                        "(影响力=${news.impactStrength}, 情绪=${news.sentiment})"
                }
            }

            // 2. 技术过滤（需要当日快照数据）
            val afterNewsBlock = candidateCodes - blockedCodes
            val allSnapshots = try {
                db.dailySnapshotDao().getByDate(context.tradeDate)
            } catch (_: Exception) { emptyList() }
            val snapMap = allSnapshots.associateBy { it.code }

            val technicalFilteredCodes = mutableSetOf<String>()
            for (code in afterNewsBlock) {
                if (!passesTechnicalFilter(code, snapMap, allSnapshots)) {
                    technicalFilteredCodes.add(code)
                }
            }

            val passedCodes = afterNewsBlock - technicalFilteredCodes

            // 📤 输出日志
            context.log(nodeId, "📤 $nodeName 输出: ${passedCodes.size} 只股票 ${formatTopCodes(passedCodes)}")

            // 🚫 过滤日志
            if (blockedCodes.isNotEmpty()) {
                context.log(nodeId, "🚫 $nodeName 过滤掉: ${blockedCodes.size} 只利空拦截 ${formatTopCodes(blockedCodes)}")
                blockedCodes.forEach { code ->
                    context.log(nodeId, "  拦截: $code → ${blockedReasons[code]}")
                }
            }
            if (technicalFilteredCodes.isNotEmpty()) {
                context.log(nodeId, "🚫 $nodeName 过滤掉: ${technicalFilteredCodes.size} 只技术规则 ${formatTopCodes(technicalFilteredCodes)}")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = candidateCodes.size, outputCount = passedCodes.size,
                filterCount = candidateCodes.size - passedCodes.size,
                filterReason = buildString {
                    if (blockedCodes.isNotEmpty()) append("利空拦截${blockedCodes.size}只 ")
                    if (technicalFilteredCodes.isNotEmpty()) append("技术规则${technicalFilteredCodes.size}只")
                }.trim(),
                inputCodes = candidateCodes.take(5), outputCodes = passedCodes.take(5)
            )

            context.log(nodeId, "新闻拦截: ${blockedCodes.size} 只被拦截, " +
                "技术过滤: ${technicalFilteredCodes.size} 只被过滤, " +
                "最终通过: ${passedCodes.size} 只")

            NewsGuardResult(blockedCodes, blockedReasons, technicalFilteredCodes, passedCodes)
        } catch (e: Exception) {
            context.log(nodeId, "新闻拦截失败: ${e.message}, 全部通过")
            context.recordError(nodeId, "新闻拦截失败: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = candidateCodes.size, outputCount = candidateCodes.size,
                filterCount = 0, filterReason = "",
                inputCodes = candidateCodes.take(5), outputCodes = candidateCodes.take(5)
            )
            NewsGuardResult(emptySet(), emptyMap(), emptySet(), candidateCodes)
        }
    }

    /**
     * 4 条技术过滤规则
     */
    private fun passesTechnicalFilter(
        code: String,
        snapMap: Map<String, DailySnapshotEntity>,
        allSnapshots: List<DailySnapshotEntity>
    ): Boolean {
        val snap = snapMap[code] ?: return true

        // 规则1: 大阴线不抄（跌幅 >= 7%）
        if (snap.changePct <= -7.0) return false

        // 规则2: 一字板不跳（涨停一字板）
        if (snap.changePct >= 9.5 && snap.open >= snap.close * 0.99) return false

        // 规则3: 均线空头不搞（阴线且跌幅>2%，且上影线明显 → 抛压重）
        if (snap.close < snap.open && snap.changePct < -2.0) {
            val upperShadow = snap.high - maxOf(snap.open, snap.close)
            val body = abs(snap.open - snap.close)
            if (body > 0 && upperShadow > body * 0.5) return false
        }

        // 规则4: 顶背离不追（小幅涨但上影线长且连续3日以上阳线）
        if (snap.changePct in 0.0..1.5 && snap.high > snap.open * 1.02) {
            val recent = allSnapshots.filter { it.code == code }
                .sortedByDescending { it.date }
                .take(4)
            if (recent.size >= 4 && recent.count { it.changePct > 0 } >= 3) return false
        }

        return true
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  6. AdaptiveParamsNode (DATA_SOURCE)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 自适应参数节点
 *
 * 触发大盘分析报告 + 市场自适应参数的延迟加载，
 * 并将结果存入 PipelineContext 供后续节点使用。
 *
 * 自适应参数包含：
 * - scoreThreshold: 动态评分阈值（BEARISH强=55/BEARISH弱=60, OSCILLATION=55, BULLISH=50）
 * - maxStockCount: 最大选股数量
 * - shouldGoEmpty: 是否触发空仓
 *
 * 中线独有：中线使用自适应参数调整阈值和持仓限制，短线不使用。
 *
 * @property holdingCodes 持仓股票代码（用于大盘分析）
 */
class AdaptiveParamsNode(
    private val holdingCodes: List<String> = emptyList()
) : BaseNode<Any, MarketAdaptiveStrategy.AdaptiveParams?>("adaptive_params", "大盘分析+自适应参数", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): MarketAdaptiveStrategy.AdaptiveParams? {
        // 从数据库读取持仓（若构造函数未传入）
        val effectiveHoldingCodes = if (holdingCodes.isNotEmpty()) {
            holdingCodes
        } else {
            try {
                val db = StockDatabase.getInstance(context.androidContext)
                val period = orderTypePeriod(context.config.orderType)
                db.strategyTradeOrderDao().getRecent(500)
                    .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
                    .map { it.stockCode }
            } catch (e: Exception) {
                context.log(nodeId, "读取持仓失败: ${e.message}")
                emptyList()
            }
        }

        // 📥 输入日志
        context.log(nodeId, "📥 $nodeName 输入: ${effectiveHoldingCodes.size} 只持仓 ${formatTopCodes(effectiveHoldingCodes)}")

        return try {
            // 触发延迟加载（首次调用时计算，后续直接返回缓存值）
            val report = context.getMarketReport(effectiveHoldingCodes)
            val params = context.getAdaptiveParams()

            if (report != null) {
                context.log(nodeId, "大盘分析: direction=${report.trend.direction}, " +
                    "trend=${report.trend.description}")
            }

            if (params != null) {
                // 📤 输出日志
                context.log(nodeId, "📤 $nodeName 输出: scoreThreshold=${params.scoreThreshold}, " +
                    "maxCount=${params.maxStockCount}, forceEmpty=${params.forceEmpty}")
                context.log(nodeId, "自适应参数: scoreThreshold=${params.scoreThreshold}, " +
                    "maxCount=${params.maxStockCount}, forceEmpty=${params.forceEmpty}")
            } else {
                context.log(nodeId, "自适应参数计算失败，使用默认值")
                context.recordError(nodeId, "自适应参数返回 null")
            }

            params
        } catch (e: Exception) {
            context.log(nodeId, "大盘分析失败: ${e.message}")
            context.recordError(nodeId, "大盘分析失败: ${e.message}")
            null
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  7. SwapWeakNode (TRADE_ACTION) — 已修改：从 OrderGenerationResult 获取 newBuyCount
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 腾龙换鸟节点
 *
 * 当持仓数 + 新买入数超过 [maxHoldings] 时，用 AutoSellEngine 评估所有持仓，
 * 优先卖出已触发止损 / 最亏损的股票腾出仓位。
 *
 * 卖出排序优先级：shouldSell > urgency > profitPct（升序，优先亏损最多）
 *
 * 中线独有：中线持仓周期长，需要动态换股优化持仓组合。
 *
 * **已修改**：newBuyCount 从上游 GenerateOrdersNode 的输出中动态获取，
 * 不再从 XML config 固定读取。
 *
 * @property maxHoldings 最大持仓数（默认 5）
 * @property strategies 策略列表（用于 AutoSellEngine 评估）
 */
class SwapWeakNode(
    private val maxHoldings: Int = 5,
    private val strategies: List<Strategy> = emptyList()
) : BaseNode<Any, SwapWeakResult>("swap_weak", "腾龙换鸟", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: Any): SwapWeakResult {
        // 非交易时段不执行腾笼换鸟（无法获取实时价格，卖出无意义）
        if (!ChinaMarketTradingHours.a股是否交易中()) {
            context.log(nodeId, "⏸️ 非交易时段，跳过$nodeName")
            return SwapWeakResult(0, emptyList(), 0, 0, emptyList())
        }

        // 从 input 或 context 中按需读取 OrderGenerationResult
        val orderResult: OrderGenerationResult = when (input) {
            is OrderGenerationResult -> input
            else -> context.getStageOutput<OrderGenerationResult>("generate_orders")
                ?: context.getStageOutput<OrderGenerationResult>("n_orders")
                ?: return SwapWeakResult(0, emptyList(), 0, 0, emptyList())
        }
        // 从上游 GenerateOrdersNode 输出中动态获取 newBuyCount
        val newBuyCount = orderResult.orders.size

        // ── 快速跳过：无新买订单时无需任何 DB 查询，直接返回 ──
        if (newBuyCount == 0) {
            context.log(nodeId, "$nodeName: 无新买订单，跳过")
            return SwapWeakResult(0, emptyList(), 0, 0, orderResult.orders)
        }

        // 📥 输入日志
        val orderCodes = orderResult.orders.map { "${it.stockCode}(${it.stockName})" }
        context.log(nodeId, "📥 $nodeName 输入: ${orderResult.orders.size} 个订单 ${formatTopCodes(orderCodes)}")

        val effectiveStrategies = strategies.ifEmpty {
            context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
        }

        val db = StockDatabase.getInstance(context.androidContext)
        val sellEngine = AutoSellEngine(context.androidContext)

        return try {
            // 按周期统计持仓：只算与本 Pipeline 同周期的持仓，不跨周期累加
            val period = orderTypePeriod(context.config.orderType)
            val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
                .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            var currentCount = holdingOrders.size

            // 选股数量（独立计数，仅供日志参考，不影响腾龙换鸟判断）
            val pickCount = try {
                db.userWatchlistDao().getBySourceAndDate(context.config.orderType, context.tradeDate).size
            } catch (_: Exception) { 0 }
            if (pickCount > 0) {
                context.log(nodeId, "持仓 $currentCount | 选股 $pickCount（独立，不影响换鸟）")
            }

            // ── 趋势转换检测：即使持仓未满，趋势转空的持仓也主动卖出换股 ──
            // 读取每只持仓最近20天K线，判断是否空头排列(MA5<MA10<MA20)或近3日创20日新低
            // 长线更保守：仅在极端趋势反转（空头排列+创新低同时满足）才换股
            val trendReversedCodes = mutableListOf<String>()
            for (order in holdingOrders) {
                try {
                    val snaps = db.dailySnapshotDao().getByCode(order.stockCode, 20)
                    if (snaps.size < 20) continue  // 数据不足，跳过趋势判断
                    val klines = snaps.sortedBy { it.date }  // 按 date ASC 排序，takeLast = 最新
                    val closes = klines.map { it.close }
                    val ma5 = closes.takeLast(5).average()
                    val ma10 = closes.takeLast(10).average()
                    val ma20 = closes.takeLast(20).average()
                    val recent3Low = klines.takeLast(3).minOf { it.low }
                    val prior17Low = klines.dropLast(3).minOf { it.low }
                    val bearishAlignment = ma5 < ma10 && ma10 < ma20
                    val newLow = recent3Low < prior17Low

                    // 长线：需要空头排列 AND 创新低才换股（更保守）
                    // 中线：空头排列 OR 创新低即换股（原有逻辑）
                    val shouldSwitch = if (period == "long") {
                        bearishAlignment && newLow
                    } else {
                        bearishAlignment || newLow
                    }

                    if (shouldSwitch) {
                        trendReversedCodes.add(order.stockCode)
                        val reason = if (bearishAlignment && newLow) "空头排列+创新低"
                            else if (bearishAlignment) "空头排列" else "近3日创新低"
                        context.log(nodeId, "${period}线趋势转换: ${order.stockName}($reason)，主动卖出换股")
                    } else if (period == "long" && (bearishAlignment || newLow)) {
                        // 长线单一信号不换股，但记录建议做T
                        val signal = if (bearishAlignment) "空头排列" else "近3日创新低"
                        context.log(nodeId, "💡 长线持仓 ${order.stockName} 出现${signal}但未达双重确认，建议做T降成本而非换股")
                    }
                } catch (_: Exception) {
                    // 单股查询失败不影响整体流程
                }
            }

            if (trendReversedCodes.isNotEmpty()) {
                val allDecisions = sellEngine.evaluateAll(
                    effectiveStrategies,
                    AutoSellEngine.AutoSellConfig(
                        tradeDate = context.tradeDate,
                        hardStopLossPct = context.getAdaptiveParams()?.stopLossRate?.times(100)
                            ?: AutoSellEngine.HARD_STOP_LOSS_PCT
                    )
                ).filter { orderTypePeriod(it.order.orderType) == period }
                val trendSellDecisions = allDecisions.filter { it.order.stockCode in trendReversedCodes }
                if (trendSellDecisions.isNotEmpty()) {
                    val soldNames = trendSellDecisions.map { "${it.order.stockName}(趋势转空)" }
                    context.log(nodeId, "腾龙换鸟: 趋势转换卖出 ${soldNames.joinToString()}")
                    sellEngine.executeSells(trendSellDecisions, context.tradeDate, force = true)
                }
                // 卖出后重新计算持仓数
                currentCount = db.strategyTradeOrderDao().getRecent(500)
                    .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            }

            // ── 腾龙换鸟只在「持仓+新买超出上限」时触发 ──
            // 持仓+新买 ≤ 上限时仓位足够，新票直接买入即可，无需卖出现有持仓腾位置。
            if (currentCount + newBuyCount <= maxHoldings) {
                context.log(nodeId, "腾龙换鸟: 持仓 $currentCount + 新买 $newBuyCount ≤ $maxHoldings, 仓位足够, 无需换股")
                // 💡 中长线建议：已有持仓优先做T，不轻易加新仓
                if (period == "mid" || period == "long") {
                    context.log(nodeId, "💡 $period 持仓建议: 优先对现有持仓做T降低成本，而非频繁换股")
                }
                // 📤 输出日志
                context.log(nodeId, "📤 $nodeName 输出: 0 只换股，持仓不变 $currentCount")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = orderResult.orders.size, outputCount = 0,
                    filterCount = orderResult.orders.size,
                    filterReason = "持仓未满无需换股",
                    inputCodes = orderCodes.take(5), outputCodes = emptyList()
                )
                return SwapWeakResult(0, emptyList(), currentCount, currentCount, orderResult.orders)
            }

            // 没有新买订单 → 没有换股候选，不换
            if (newBuyCount == 0) {
                context.log(nodeId, "腾龙换鸟: 持仓已满 $currentCount/$maxHoldings 但无新买订单, 无需换股")
                context.log(nodeId, "📤 $nodeName 输出: 0 只换股，持仓不变 $currentCount")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = 0, outputCount = 0,
                    filterCount = 0, filterReason = "无新买订单",
                    inputCodes = emptyList(), outputCodes = emptyList()
                )
                return SwapWeakResult(0, emptyList(), currentCount, currentCount, orderResult.orders)
            }

            // 持仓已满且有新候选 → 需腾出 newBuyCount 个位置（持仓超满时一并修剪到上限）
            val needToSell = currentCount + newBuyCount - maxHoldings
            context.log(nodeId, "腾龙换鸟: 持仓已满 $currentCount/$maxHoldings + 新买 $newBuyCount, " +
                "需腾出 $needToSell 个位置")

            val allDecisions = sellEngine.evaluateAll(
                effectiveStrategies,
                AutoSellEngine.AutoSellConfig(
                    tradeDate = context.tradeDate,
                    // 自适应止损：空头市场收紧硬止损（如 -8% → -3%），让亏损票在换股排序中优先被卖
                    hardStopLossPct = context.getAdaptiveParams()?.stopLossRate?.times(100)
                        ?: AutoSellEngine.HARD_STOP_LOSS_PCT
                )
            ).filter { orderTypePeriod(it.order.orderType) == period }

            val rankedForSale = allDecisions
                .sortedWith(
                    compareByDescending<AutoSellEngine.SellDecision> { it.shouldSell }
                        .thenByDescending { it.urgency }
                        .thenBy { it.profitPct }
                )
                .take(needToSell)

            // ── 只换「更值得买」的票：新票最高评分须高于待卖边界持仓的买入评分 ──
            // 中长线换股门槛更高：除非新票明显更优（中线+20%，长线+50%），否则优先做T不换股
            // 短线维持原始逻辑（只要新票分数 > 持仓分数即可换）
            val bestNewScore = orderResult.orders.maxOfOrNull { it.scoreAtBuy } ?: 0
            val marginalHoldingScore = rankedForSale.lastOrNull()?.order?.scoreAtBuy ?: Int.MAX_VALUE
            val swapThreshold = when (period) {
                "long" -> (marginalHoldingScore * 1.5).toInt()   // 长线：新票需比持仓高50%才换
                "mid" -> (marginalHoldingScore * 1.2).toInt()    // 中线：新票需比持仓高20%才换
                else -> marginalHoldingScore                      // 短线：只要更高即可
            }
            if (bestNewScore <= swapThreshold) {
                context.log(nodeId, "腾龙换鸟: 新票最高分 $bestNewScore ≤ ${period}换股门槛 $swapThreshold " +
                    "(持仓分 $marginalHoldingScore, 门槛倍数 ${if(period=="long") "1.5" else if(period=="mid") "1.2" else "1.0"}), " +
                    "新票不够优, 不换股 → 建议对现有持仓做T")
                context.log(nodeId, "📤 $nodeName 输出: 0 只换股（新票不优于持仓，建议做T）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = orderResult.orders.size, outputCount = 0,
                    filterCount = orderResult.orders.size,
                    filterReason = "新票不够优(${period}门槛未达)，建议做T",
                    inputCodes = orderCodes.take(5), outputCodes = emptyList()
                )
                return SwapWeakResult(0, emptyList(), currentCount, currentCount, orderResult.orders)
            }

            if (rankedForSale.isNotEmpty()) {
                val soldNames = rankedForSale.map { "${it.order.stockName}(${it.reason})" }
                context.log(nodeId, "腾龙换鸟: 卖出 ${soldNames.joinToString()}")

                // 🚫 过滤日志
                context.log(nodeId, "🚫 $nodeName 卖出: ${rankedForSale.size} 只 ${formatTopCodes(soldNames)}")

                // 腾龙换鸟为强制换仓：被选中的股票不论 shouldSell 为何都必须卖出
                sellEngine.executeSells(rankedForSale, context.tradeDate, force = true)

                val afterCount = db.strategyTradeOrderDao().getRecent(500)
                    .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }

                // 📤 输出日志
                context.log(nodeId, "📤 $nodeName 输出: ${rankedForSale.size} 只换股，持仓 $currentCount → $afterCount")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = orderResult.orders.size, outputCount = rankedForSale.size,
                    filterCount = if (rankedForSale.isEmpty()) orderResult.orders.size else 0,
                    filterReason = if (rankedForSale.isEmpty()) "无需换股" else "",
                    inputCodes = orderCodes.take(5), outputCodes = rankedForSale.take(5).map { it.order.stockCode }
                )

                SwapWeakResult(
                    swappedCount = rankedForSale.size,
                    soldStocks = soldNames,
                    beforeCount = currentCount,
                    afterCount = afterCount,
                    orders = orderResult.orders
                )
            } else {
                // 📤 输出日志
                context.log(nodeId, "📤 $nodeName 输出: 0 只换股，无需卖出")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = orderResult.orders.size, outputCount = 0,
                    filterCount = orderResult.orders.size,
                    filterReason = "无需换股",
                    inputCodes = orderCodes.take(5), outputCodes = emptyList()
                )
                SwapWeakResult(0, emptyList(), currentCount, currentCount, orderResult.orders)
            }
        } catch (e: Exception) {
            context.log(nodeId, "腾龙换鸟失败: ${e.message}")
            context.recordError(nodeId, "腾龙换鸟失败: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = orderResult.orders.size, outputCount = 0,
                filterCount = orderResult.orders.size,
                filterReason = "执行失败: ${e.message}",
                inputCodes = orderCodes.take(5), outputCodes = emptyList()
            )
            SwapWeakResult(0, emptyList(), 0, 0, orderResult.orders)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  8. HeatScoreNode (DATA_TRANSFORM)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 热度计算节点
 *
 * 对股票池中的每只股票计算 5 维热度分数（0-100）。
 * 维度：交易量能(30) + 资金动向(20) + 板块历史热度(20) + 价格位置(15) + 概念壁垒(15)
 *
 * 原始流程对应：SimulationTradeEngine.calculateHeatScoresBatch()
 *
 * 计算逻辑：
 * 1. 从 DB 获取当日快照，计算交易量能得分（量比、换手率）
 * 2. 从 SmartMoneyCache 获取主力资金评分
 * 3. 从 SectorDailyRecord 获取板块历史热度
 * 4. 从快照计算价格位置得分（距均线距离、涨幅位置）
 * 5. 返回 Map<String, Int>（code → score）
 */
class HeatScoreNode : BaseNode<Any, Map<String, Int>>("heat_score", "热度计算", NodeType.DATA_TRANSFORM) {

    override suspend fun execute(context: PipelineContext, input: Any): Map<String, Int> {
        // 从 input 或 context 中按需读取 StockPool
        val pool: StockPool = when (input) {
            is StockPool -> input
            else -> context.getStageOutput<StockPool>("stock_pool")
                ?: context.getStageOutput<StockPool>("n_pool")
                ?: context.getStageOutput<StockPool>("candidate_pool")
                ?: context.getStageOutput<StockPool>("n_cand")
                ?: StockPool(emptyList(), "empty")
        }
        // 📥 输入日志
        val inputCodes = pool.stocks.map { it.code }
        context.log(nodeId, "📥 $nodeName 输入: ${pool.stocks.size} 只股票 ${formatTopCodes(inputCodes)}")

        if (pool.isEmpty) {
            context.log(nodeId, "股票池为空，跳过热度计算")
            return emptyMap()
        }

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 1. 获取当日快照用于量能计算
            val snapshots = try {
                db.dailySnapshotDao().getByDate(context.tradeDate)
            } catch (_: Exception) { emptyList() }
            val snapMap = snapshots.associateBy { it.code }

            // 2. 获取板块历史热度
            val sectorDao = db.sectorDailyRecordDao()
            val recentHotSectors = try {
                sectorDao.getTopHotSectors(10)
            } catch (_: Exception) { emptyList() }
            val hotSectorCodes = recentHotSectors.map { it.sector_code }.toSet()

            // 3. 计算每只股票的热度分数
            val heatScores = mutableMapOf<String, Int>()

            for (stock in pool.stocks) {
                val code = stock.code
                val snap = snapMap[code]
                if (snap == null) {
                    heatScores[code] = 30 // 无快照数据，给低分
                    continue
                }

                // 维度1: 交易量能（30分）— 基于量比和涨跌幅
                val volumeScore = calculateVolumeScore(snap)

                // 维度2: 资金动向（20分）— 基于 SmartMoneyCache
                val moneyScore = SmartMoneyCache.getScore(code).combined.roundToInt().coerceIn(0, 100)

                // 维度3: 板块历史热度（20分）— 所属板块是否在热门板块中
                val stockSectors = StockDataCenter.getSectorsByStock(code)
                val sectorHeatScore = if (stockSectors.any { s ->
                    hotSectorCodes.any { h -> s.contains(h) || h.contains(s) }
                }) 20 else 5

                // 维度4: 价格位置（15分）— 距5日均线位置
                val recentSnaps = snapshots.filter { it.code == code }
                    .sortedByDescending { it.date }
                    .take(5)
                val priceScore = calculatePricePositionScore(snap, recentSnaps)

                // 维度5: 概念壁垒（15分）— 板块数量越多说明概念越丰富
                val conceptScore = (stockSectors.size * 3).coerceAtMost(15)

                val totalScore = (volumeScore * 0.30 + moneyScore * 0.20 +
                    sectorHeatScore * 0.20 + priceScore * 0.15 + conceptScore * 0.15)
                    .roundToInt().coerceIn(0, 100)

                heatScores[code] = totalScore
            }

            // 按分数降序排列的 Top10
            val top10 = heatScores.entries
                .sortedByDescending { it.value }
                .take(10)
                .map { "${it.key}=${it.value}" }

            context.setStageOutput(nodeId, heatScores)

            // 📤 输出日志
            val outputCodes = heatScores.keys
            context.log(nodeId, "📤 $nodeName 输出: ${heatScores.size} 只股票热度分数 Top10: [${top10.joinToString(", ")}]")

            // 🚫 过滤日志
            val lowScoreCount = heatScores.count { it.value < 40 }
            if (lowScoreCount > 0) {
                context.log(nodeId, "🚫 $nodeName 低热度(<40分): $lowScoreCount 只")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.stocks.size, outputCount = heatScores.size,
                filterCount = lowScoreCount,
                filterReason = if (lowScoreCount > 0) "低热度(<40分)" else "",
                inputCodes = inputCodes.take(5), outputCodes = heatScores.keys.toList().take(5)
            )

            context.log(nodeId, "热度计算完成: ${heatScores.size} 只股票, Top10: ${top10.joinToString(", ")}")

            heatScores
        } catch (e: Exception) {
            context.log(nodeId, "热度计算失败: ${e.message}")
            context.recordError(nodeId, "热度计算失败: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.stocks.size, outputCount = 0,
                filterCount = pool.stocks.size,
                filterReason = "执行失败: ${e.message}",
                inputCodes = inputCodes.take(5), outputCodes = emptyList()
            )
            emptyMap()
        }
    }

    /**
     * 计算交易量能得分（0-100）
     * 基于涨跌幅和量比的组合
     */
    private fun calculateVolumeScore(snap: DailySnapshotEntity): Int {
        var score = 50
        // 涨幅贡献
        when {
            snap.changePct >= 5.0 -> score += 30
            snap.changePct >= 3.0 -> score += 20
            snap.changePct >= 1.0 -> score += 10
            snap.changePct <= -5.0 -> score -= 25
            snap.changePct <= -3.0 -> score -= 15
        }
        // 量能贡献（量大说明关注度高）
        if (snap.amount > 5e8) score += 10
        if (snap.amount > 1e9) score += 10
        return score.coerceIn(0, 100)
    }

    /**
     * 计算价格位置得分（0-100）
     * 基于收盘价与5日均线的距离
     */
    private fun calculatePricePositionScore(
        current: DailySnapshotEntity,
        recentSnaps: List<DailySnapshotEntity>
    ): Int {
        if (recentSnaps.size < 3) return 50
        val avg5 = recentSnaps.map { it.close }.average()
        if (avg5 == 0.0) return 50
        val deviation = ((current.close - avg5) / avg5 * 100)
        return when {
            deviation > 10 -> 30  // 远高于均线，可能过热
            deviation > 3 -> 70   // 略高于均线，强势
            deviation > -3 -> 90  // 接近均线，位置适中
            deviation > -10 -> 40 // 低于均线，偏弱
            else -> 20            // 远低于均线
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  9. GenerateOrdersNode (TRADE_ACTION)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 买入订单生成节点
 *
 * 根据 AI 精选结果 + 自适应参数生成买入订单。
 * 过滤链：主力资金评分 → 空仓触发 → 数量限制 → ETF 过滤 → 排序截取
 *
 * 原始流程对应：SimulationTradeEngine.generateBuyOrders()
 *
 * @property maxHoldings 最大持仓数（默认 6）
 * @property orderType 订单类型（默认 "MidTermQuant"）
 */
class GenerateOrdersNode(
    private val maxHoldings: Int = 6,
    private val orderType: String = "MidTermQuant"
) : BaseNode<Any, OrderGenerationResult>("generate_orders", "买入订单生成", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: Any): OrderGenerationResult {
        // 兼容多种上游：AIPrediction / MergedSignalPool / NewsGuardResult
        val topPicks: List<AIPredictionEngine.AIPick> = when (input) {
            is AIPredictionEngine.AIPrediction -> input.topPicks
            is com.chin.stockanalysis.strategy.topology.core.MergedSignalPool ->
                input.boostedSignals.mapIndexed { index, signal ->
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
            is NewsGuardResult -> {
                // 从 context 读取主力过滤后的信号池，按 passedCodes 过滤
                val pool = context.getStageOutput<com.chin.stockanalysis.strategy.topology.core.MergedSignalPool>("smart_money_filter")
                val passedSignals = pool?.boostedSignals?.filter { it.stockCode in input.passedCodes } ?: emptyList()
                passedSignals.mapIndexed { index, signal ->
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
            else -> {
                context.log(nodeId, "⚠ 未知输入类型: ${input::class.simpleName}，无法生成订单")
                return OrderGenerationResult(emptyList(), 0, false)
            }
        }

        // 📥 输入日志
        context.log(nodeId, "📥 $nodeName 输入: ${topPicks.size} 只候选 ${formatTopCodes(topPicks.map { "${it.stockCode}(${it.stockName})" })}")

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 获取自适应参数
            val params = context.getAdaptiveParams()
            val scoreThreshold = params?.scoreThreshold ?: 50
            val maxStockCount = params?.maxStockCount ?: maxHoldings
            val forceEmpty = params?.forceEmpty ?: false

            // 空仓触发
            if (forceEmpty) {
                context.log(nodeId, "自适应参数触发空仓，不生成订单")
                // 📤 输出日志
                context.log(nodeId, "📤 $nodeName 输出: 0 个订单（空仓触发）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = topPicks.size, outputCount = 0,
                    filterCount = topPicks.size,
                    filterReason = "空仓触发",
                    inputCodes = topPicks.map { it.stockCode }.take(5),
                    outputCodes = emptyList()
                )
                return OrderGenerationResult(emptyList(), topPicks.size, true)
            }

            // 交易时段检查：非交易时段仍记录信号，但标记为预信号（下一交易日可执行）
            val now = java.time.LocalDateTime.now()
            val hourMin = now.hour * 100 + now.minute
            val isTradingHours = (hourMin in 930..1130) || (hourMin in 1300..1500)
            val isTradingDay = now.dayOfWeek in java.time.DayOfWeek.MONDAY..java.time.DayOfWeek.FRIDAY
            val isPreSignal = !isTradingHours || !isTradingDay
            if (isPreSignal) {
                context.log(nodeId, "📡 非交易时段(${now.hour}:${"%02d".format(now.minute)})，记录预信号供下一交易日参考")
            }

            // 获取当前持仓（按周期统计：只算与本 Pipeline 同周期的持仓，不跨周期累加）
            val period = orderTypePeriod(orderType)
            val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
                .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            val holdingCodes = holdingOrders.map { it.stockCode }.toSet()
            // 仅「同日买入」的持仓才过滤（避免同日重复下单）；
            // 非同日持仓允许通过 → 下游 PositionMergeNode 执行加仓（追加加权平均）
            val todayHoldingCodes = holdingOrders
                .filter { it.tradeDate == context.tradeDate }
                .map { it.stockCode }.toSet()
            val availableSlots = maxHoldings - holdingCodes.size

            // 选股数量（独立计数，仅供日志参考，不影响持仓上限）
            val pickCount = try {
                db.userWatchlistDao().getBySourceAndDate(orderType, context.tradeDate).size
            } catch (_: Exception) { 0 }
            if (pickCount > 0) {
                context.log(nodeId, "持仓 ${holdingCodes.size}/$maxHoldings | 选股 $pickCount/6（独立）")
            }
            if (availableSlots <= 0) {
                context.log(nodeId, "持仓已满 ${holdingCodes.size}/$maxHoldings（$period 周期），仍输出候选供腾龙换鸟评估")
            }

            // 过滤链
            var filteredCount = 0
            val filteredReasons = mutableListOf<String>()
            val candidates = mutableListOf<AIPredictionEngine.AIPick>()

            for (pick in topPicks) {
                // 1. 评分阈值过滤
                if (pick.compositeScore < scoreThreshold) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 评分${pick.compositeScore}<$scoreThreshold")
                    continue
                }

                // 2. 同日已持有过滤（仅过滤同日重复，非同日允许加仓）
                if (pick.stockCode in todayHoldingCodes) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 今日已买入")
                    continue
                }

                // 2.5 风控卖出过滤（持仓风控已卖出的股票不再买回）
                @Suppress("UNCHECKED_CAST")
                val guardSoldCodes = context.stageOutputs["guard_sold_codes"] as? Set<String> ?: emptySet()
                if (pick.stockCode in guardSoldCodes) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 风控已卖出")
                    continue
                }

                // 3. ETF 过滤（排除 ETF）
                if (pick.stockCode.startsWith("sh51") || pick.stockCode.startsWith("sz15")) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: ETF排除")
                    continue
                }

                // 3.5 指数过滤（排除大盘指数：sh000xxx / sz399xxx 不可交易）
                if (pick.stockCode.startsWith("sh000") || pick.stockCode.startsWith("sz399")) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 指数排除")
                    continue
                }

                // 4. 主力资金评分过滤（低于 30 分的过滤）
                val moneyScore = SmartMoneyCache.getScore(pick.stockCode).combined
                if (moneyScore < 30) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 主力资金${moneyScore.roundToInt()}<30")
                    continue
                }

                candidates.add(pick)
            }

            // ── 宁缺勿滥：不放松选股条件 ──
            // 如果所有候选都被过滤，直接空仓，不做 Fallback
            // 避免买入不符合条件的「鱼尾」股票
            if (candidates.isEmpty()) {
                context.log(nodeId, "⚠ $nodeName 无候选通过严格条件，宁缺勿滥，空仓观望")
                if (filteredReasons.isNotEmpty()) {
                    context.log(nodeId, "📋 过滤详情: ${filteredReasons.joinToString(" | ")}")
                }
                context.log(nodeId, "📤 $nodeName 输出: 0 个订单（无候选通过）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = topPicks.size, outputCount = 0,
                    filterCount = topPicks.size,
                    filterReason = "无候选通过严格条件",
                    inputCodes = topPicks.map { it.stockCode }.take(5),
                    outputCodes = emptyList()
                )
                return OrderGenerationResult(emptyList(), topPicks.size, true)
            }

            // ── 大盘防守：空仓防御 ──
            // 与 Hardcode 路径（SimulationTradeEngine.shouldForceEmpty）一致：
            // 大盘明确空头且高分候选不足 2 只时，一股不买。
            // 仅中线/长线生效：顺势交易、持仓周期长，熊市空头应空仓保本。
            // 超短/短线属均值回归，赚的是超跌反弹/V型反包/情绪脉冲——恰恰出现在杀最凶时，
            // 不挡空仓，改靠 maxStockCount 限仓 + 严止损 + T+1 纪律控风险。
            val marketReport = context.getMarketReport()
            val isBearishEmpty = (period == "mid" || period == "long") && marketReport != null &&
                MarketAdaptiveStrategy.shouldForceEmpty(marketReport, candidates.size)

            // ── 长线严选例外：熊市不轻易空仓，满足严格条件仍可买入 ──
            // 条件：均线粘合向上 + 三日不新低 + 历史低位 + PE低 + 周期活跃度高 + 买在冰点
            if (isBearishEmpty && period == "long" && topPicks.isNotEmpty()) {
                data class StrictLongPick(
                    val pick: AIPredictionEngine.AIPick,
                    val score: Double
                )
                val strictPicks = mutableListOf<StrictLongPick>()
                val strictSnapshots = try {
                    db.dailySnapshotDao().getByDate(context.tradeDate)
                } catch (_: Exception) { emptyList() }
                val strictSnapMap = strictSnapshots.associateBy { it.code }

                for (pick in topPicks.take(15)) {
                    try {
                        val snaps = db.dailySnapshotDao().getByCode(pick.stockCode, 65)
                            .sortedBy { it.date }
                        if (snaps.size < 30) continue
                        val latest = snaps.last()
                        val closes = snaps.map { it.close }
                        val lows = snaps.map { it.low }
                        val highs = snaps.map { it.high }
                        val ma5 = closes.takeLast(5).average()
                        val ma10 = closes.takeLast(10).average()
                        val ma20 = closes.takeLast(20).average()
                        val ma30 = closes.takeLast(30).average()
                        val high60 = highs.maxOrNull() ?: latest.close
                        val low60 = lows.minOrNull() ?: latest.close
                        val range60 = high60 - low60

                        // 1. 均线粘合向上：MA5 > MA10 > MA20，且 MA5/MA10 差距 < 3%
                        val maConvergedUp = ma5 > ma10 && ma10 > ma20 &&
                            (ma5 - ma10) / ma10 < 0.03
                        if (!maConvergedUp) continue

                        // 2. 三日不新低：近3日 low 均 >= 第4日 low
                        val recent3Lows = snaps.takeLast(3).map { it.low }
                        val priorLow = snaps.dropLast(3).minOf { it.low }
                        val threeDayNoNewLow = recent3Lows.all { it >= priorLow }
                        if (!threeDayNoNewLow) continue

                        // 3. 历史低位：close 在 60日区间的下20%
                        val positionPct = if (range60 > 0) (latest.close - low60) / range60 else 0.5
                        val isHistoricalLow = positionPct < 0.25
                        if (!isHistoricalLow) continue

                        // 4. PE低（无泡沫）：PE > 0 且 < 30，或 PE < 行业平均
                        val pe = latest.pe
                        val peOk = pe > 0 && pe < 30.0
                        if (!peOk) continue

                        // 5. 历史活跃度（周期性涨高落底）：60日内有至少3次 >3% 的涨跌
                        val volatileDays = snaps.takeLast(60).count { Math.abs(it.close - it.open) / it.open > 0.03 }
                        val isCyclicallyActive = volatileDays >= 3
                        if (!isCyclicallyActive) continue

                        // 6. 买在冰点（不要太多利好）：低换手率 + 低成交量
                        val avgVol = snaps.takeLast(20).map { it.volume.toDouble() }.average()
                        val volRatio = if (avgVol > 0) latest.volume.toDouble() / avgVol else 1.0
                        val lowAttention = latest.turnoverRate < 2.0 && volRatio < 1.0

                        // 综合评分：信号强度 + 低位加分 + 冰点加分
                        var totalScore = pick.compositeScore.toDouble()
                        totalScore += (1.0 - positionPct) * 20  // 越低位分越高
                        if (lowAttention) totalScore += 10  // 冰点加分
                        if (pe < 15) totalScore += 5  // 超低PE加分

                        strictPicks.add(StrictLongPick(pick, totalScore))
                    } catch (_: Exception) {}
                }

                strictPicks.sortByDescending { it.score }
                val longTermOrders = strictPicks.take(2).mapNotNull { sp ->
                    val snap = strictSnapMap[sp.pick.stockCode]
                    val buyPrice = snap?.close ?: 0.0
                    // B9: 非交易日/缺快照时买价无效则跳过，避免 buyPrice=0 入库
                    if (buyPrice <= 0) return@mapNotNull null
                    context.log(nodeId, "🎯 长线严选: ${sp.pick.stockName}(${sp.pick.stockCode}) " +
                        "PE=${"%.1f".format(snap?.pe ?: 0.0)} 评分=${"%.0f".format(sp.score)} " +
                        "均线粘合✓ 三日不新低✓ 历史低位✓ 低PE✓ 冰点埋伏")
                    TradeOrder(
                        stockCode = sp.pick.stockCode,
                        stockName = sp.pick.stockName,
                        strategyId = "long_strict_${context.tradeDate}",
                        tradeDate = context.tradeDate,
                        buyPrice = buyPrice,
                        quantity = 100,
                        reason = "长线严选: 均线粘合向上+三日不新低+历史低位+低PE+冰点 " +
                            "score=${"%.0f".format(sp.score)} ${sp.pick.reason}",
                        scoreAtBuy = sp.pick.compositeScore,
                        orderType = orderType
                    )
                }

                if (longTermOrders.isNotEmpty()) {
                    context.log(nodeId, "🎯 $nodeName 大盘虽空头，但发现 ${longTermOrders.size} 只" +
                        "符合长线严选条件 → 允许买入（熊市逆势严选）")
                    context.setStageOutput(nodeId, longTermOrders)
                    context.recordStockFlow(
                        nodeId = nodeId, nodeName = nodeName,
                        inputCount = topPicks.size, outputCount = longTermOrders.size,
                        filterCount = topPicks.size - longTermOrders.size,
                        filterReason = "长线严选(熊市逆势)",
                        inputCodes = topPicks.map { it.stockCode }.take(5),
                        outputCodes = longTermOrders.map { it.stockCode }
                    )
                    return OrderGenerationResult(longTermOrders, topPicks.size - longTermOrders.size, false)
                }
                // 若无符合长线严选的候选，继续走下面的普通熊市打底仓逻辑
            }

            if (isBearishEmpty) {

                // ── 例外：防守个股历史低位企稳 → 允许打底仓（2手=200股）──
                // 熊市中如果防守板块个股（银行/保险/电力/高速公路等）处于历史低位，
                // 且出现三日不新低/企稳/均线趋势向上信号，可以小仓位打底仓
                // 条件4：高股息优先 — 低PB + 防御板块 + 大市值 → 排序靠前
                data class BasePickCandidate(
                    val pick: AIPredictionEngine.AIPick,
                    val buyPrice: Double,
                    val stabilizingSignal: String,
                    val isDefensive: Boolean,
                    val dividendScore: Double  // 高股息代理评分
                )
                val baseCandidates = mutableListOf<BasePickCandidate>()
                val defensiveSectors = setOf("银行", "保险", "电力", "高速公路", "煤炭", "石油",
                    "电信", "水务", "燃气", "铁路", "港口", "机场", "证券")
                val bearSnapshots = try {
                    db.dailySnapshotDao().getByDate(context.tradeDate)
                } catch (_: Exception) { emptyList() }
                val bearSnapMap = bearSnapshots.associateBy { it.code }

                for (pick in topPicks.take(10)) {  // 只看前10名候选
                    try {
                        val snaps = db.dailySnapshotDao().getByCode(pick.stockCode, 60)
                            .sortedBy { it.date }
                        if (snaps.size < 20) continue

                        val latest = snaps.last()
                        val closes = snaps.map { it.close }
                        val ma5 = closes.takeLast(5).average()
                        val ma10 = closes.takeLast(10).average()
                        val ma20 = closes.takeLast(20).average()
                        val recent3Lows = snaps.takeLast(3).map { it.low }
                        val priorLow = snaps.dropLast(3).minOf { it.low }
                        val high60 = snaps.map { it.high }.maxOrNull() ?: latest.close

                        // 条件1：历史低位（当前价在60日高点的70%以下，或低于MA20的5%以下）
                        val isHistoricalLow = latest.close < high60 * 0.75 || latest.close < ma20 * 0.95
                        if (!isHistoricalLow) continue

                        // 条件2：企稳信号（满足任一）
                        // a) 三日不新低：近3日最低点 ≥ 前17日最低点
                        val threeDayNoNewLow = recent3Lows.minOrNull()!! >= priorLow
                        // b) 均线趋势向上：MA5 > MA10（短期均线拐头）
                        val maTurningUp = ma5 > ma10
                        // c) 收盘站上MA5（企稳迹象）
                        val aboveMa5 = latest.close > ma5
                        val stabilizing = threeDayNoNewLow || maTurningUp || aboveMa5
                        if (!stabilizing) continue

                        // 条件3：防守板块（从个股名称或候选信息判断）
                        val stockName = pick.stockName
                        val isDefensive = defensiveSectors.any { s -> stockName.contains(s) }
                        // 非严格限制：历史低位企稳的个股也允许，不仅限防守板块
                        if (!isDefensive && !isHistoricalLow) continue

                        val stabilizingSignal = when {
                            threeDayNoNewLow -> "三日不新低"
                            maTurningUp -> "均线向上"
                            else -> "站上MA5"
                        }

                        // 条件4：高股息代理评分（无真实股息率，用低PB+防御板块+大市值近似）
                        // PB越低分越高（PB<1.0=满分40，PB<1.5=30，否则0）
                        val pb = latest.pb
                        val pbScore = when {
                            pb > 0 && pb < 1.0 -> 40.0
                            pb > 0 && pb < 1.5 -> 30.0
                            else -> 0.0
                        }
                        // 防御板块加25分（银行/保险/电力等传统高息板块）
                        val sectorScore = if (isDefensive) 25.0 else 0.0
                        // 大市值加20分（市值>500亿，大盘股通常派息稳定）
                        val capScore = if (latest.marketCap > 500_0000_0000.0) 20.0 else 0.0
                        // ROE加15分（盈利能力强才有能力派息）
                        val roeScore = if (latest.roeTTM > 10.0) 15.0 else 0.0
                        val dividendScore = pbScore + sectorScore + capScore + roeScore

                        val buyPrice = bearSnapMap[pick.stockCode]?.close ?: latest.close
                        // B9: 买价无效则跳过，避免 buyPrice=0 入库
                        if (buyPrice <= 0) continue
                        baseCandidates.add(BasePickCandidate(pick, buyPrice, stabilizingSignal, isDefensive, dividendScore))

                    } catch (_: Exception) {}
                }

                // 按高股息评分降序排序，高股息优先买入
                baseCandidates.sortByDescending { it.dividendScore }
                val basePositionOrders = baseCandidates.take(2).mapIndexed { _, c ->
                    val isHighDiv = c.dividendScore >= 50
                    val dividendTag = if (isHighDiv) "🔶高股息" else ""
                    context.log(nodeId, "🛡💪 熊市打底仓: ${c.pick.stockName}(${c.pick.stockCode}) " +
                        "价=${"%.2f".format(c.buyPrice)} 历史低位+企稳(${c.stabilizingSignal})" +
                        (if (isHighDiv) " $dividendTag(评分${c.dividendScore})" else ""))

                    TradeOrder(
                        stockCode = c.pick.stockCode,
                        stockName = c.pick.stockName,
                        strategyId = "ai_${period}term_${context.tradeDate}",
                        tradeDate = context.tradeDate,
                        buyPrice = c.buyPrice,
                        quantity = 200, // 打底仓2手
                        reason = "熊市防守打底仓: 历史低位+企稳(${c.stabilizingSignal})" +
                            (if (isHighDiv) " +高股息(评分${c.dividendScore})" else "") +
                            " score=${c.pick.compositeScore} ${c.pick.reason}",
                        scoreAtBuy = c.pick.compositeScore,
                        orderType = orderType
                    )
                }

                if (basePositionOrders.isNotEmpty()) {
                    context.log(nodeId, "🛡 $nodeName 大盘防守空仓，但发现 ${basePositionOrders.size} 只" +
                        "历史低位企稳个股 → 打底仓（2手/只）")
                    context.setStageOutput(nodeId, basePositionOrders)
                    context.recordStockFlow(
                        nodeId = nodeId, nodeName = nodeName,
                        inputCount = topPicks.size, outputCount = basePositionOrders.size,
                        filterCount = topPicks.size - basePositionOrders.size,
                        filterReason = "大盘防守空仓(打底仓例外)",
                        inputCodes = topPicks.map { it.stockCode }.take(5),
                        outputCodes = basePositionOrders.map { it.stockCode }
                    )
                    return OrderGenerationResult(basePositionOrders, topPicks.size - basePositionOrders.size, false)
                } else {
                    context.log(nodeId, "🛡 $nodeName 大盘防守: BEARISH(强度${marketReport?.trend?.strength}) + " +
                        "高分候选仅 ${candidates.size} 只(<2) → 空仓观望")
                    context.log(nodeId, "📤 $nodeName 输出: 0 个订单（空仓防御）")
                    context.recordStockFlow(
                        nodeId = nodeId, nodeName = nodeName,
                        inputCount = topPicks.size, outputCount = 0,
                        filterCount = topPicks.size,
                        filterReason = "大盘防守空仓",
                        inputCodes = topPicks.map { it.stockCode }.take(5),
                        outputCodes = emptyList()
                    )
                    return OrderGenerationResult(emptyList(), topPicks.size, true)
                }
            }

            // 数量限制（自适应最大买入数；实际入库由 position_merge 按可用仓位裁切）
            // 限仓规则：持仓 >= 3 时，每次最多新增 1 只（腾笼换鸟由下游 SwapWeakNode 处理）
            val holdingCount = holdingCodes.size
            val effectiveCap = if (holdingCount >= 3) {
                context.log(nodeId, "持仓 $holdingCount 只(>=3)，本次最多新增 1 只")
                1
            } else {
                maxStockCount
            }
            val buyCap = minOf(effectiveCap, maxOf(availableSlots, if (holdingCount >= 3) 1 else 0))
            val finalCandidates = candidates
                .sortedByDescending { it.compositeScore }
                .take(buyCap)

            // 生成订单
            val snapshots = try {
                db.dailySnapshotDao().getByDate(context.tradeDate)
            } catch (_: Exception) { emptyList() }
            val snapMap = snapshots.associateBy { it.code }

            // 预先补全候选股中缺失的名称（统一调用 StockNameResolver）
            val blankNameCodes = finalCandidates.filter { it.stockName.isBlank() }.map { it.stockCode }.distinct()
            val resolvedNames = if (blankNameCodes.isNotEmpty()) {
                com.chin.stockanalysis.stock.database.StockNameResolver.resolveBatch(context.androidContext, blankNameCodes)
            } else emptyMap()

            val orders = finalCandidates.mapIndexedNotNull { index, pick ->
                val snap = snapMap[pick.stockCode]
                val buyPrice = snap?.close ?: 0.0
                // B9: 非交易日/缺快照时买价无效则跳过，避免 buyPrice=0 入库
                if (buyPrice <= 0) return@mapIndexedNotNull null
                val resolvedName = if (pick.stockName.isNotBlank()) pick.stockName
                    else resolvedNames[pick.stockCode] ?: pick.stockName
                val preSignalTag = if (isPreSignal) " [预信号]" else ""
                TradeOrder(
                    stockCode = pick.stockCode,
                    stockName = resolvedName,
                    strategyId = "ai_midterm_${context.tradeDate}",
                    tradeDate = context.tradeDate,
                    buyPrice = buyPrice,
                    quantity = 100, // 默认一手
                    reason = "AI精选 rank=${pick.rank} score=${pick.compositeScore} ${pick.reason}$preSignalTag",
                    scoreAtBuy = pick.compositeScore,
                    orderType = orderType
                )
            }

            context.setStageOutput(nodeId, orders)

            // 📤 输出日志
            val orderCodes = orders.map { "${it.stockCode}(${it.stockName})" }
            context.log(nodeId, "📤 $nodeName 输出: ${orders.size} 个订单 ${formatTopCodes(orderCodes)}")

            // 🚫 过滤日志
            if (filteredCount > 0) {
                val filteredCodeList = filteredReasons.take(5)
                context.log(nodeId, "🚫 $nodeName 过滤掉: $filteredCount 只 ${filteredCodeList.joinToString(", ")}")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = topPicks.size, outputCount = orders.size,
                filterCount = topPicks.size - orders.size,
                filterReason = buildString {
                    if (filteredCount > 0) append("同日重复/价格异常${filteredCount}只")
                }.trim(),
                inputCodes = topPicks.map { it.stockCode }.take(5),
                outputCodes = orders.map { it.stockCode }.take(5)
            )

            context.log(nodeId, "买入订单生成: ${topPicks.size} 只AI精选 → 过滤 $filteredCount → 最终 ${orders.size} 个订单")

            OrderGenerationResult(orders, filteredCount, false)
        } catch (e: Exception) {
            context.log(nodeId, "买入订单生成失败: ${e.message}")
            context.recordError(nodeId, "买入订单生成失败: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = topPicks.size, outputCount = 0,
                filterCount = topPicks.size,
                filterReason = "执行失败: ${e.message}",
                inputCodes = topPicks.map { it.stockCode }.take(5),
                outputCodes = emptyList()
            )
            OrderGenerationResult(emptyList(), 0, false)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  10. PositionMergeNode (TRADE_ACTION)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 持仓合并节点
 *
 * 将新买入订单与现有持仓合并：已持有则追加加权平均，新持仓则插入 PENDING 订单。
 *
 * 原始流程对应：SimulationTradeEngine.runTradeSession() Step 11
 */
class PositionMergeNode : BaseNode<Any, PositionMergeResult>("position_merge", "持仓合并", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, rawInput: Any): PositionMergeResult {
        // 兼容两种上游输入：
        // - SwapWeakResult（经腾龙换鸟 n_swap → n_merge_pos）：直接从 orders 字段提取
        // - OrderGenerationResult（直连 n_orders → n_merge_pos）：直接使用
        // - 其他类型（如辅助节点容错回退）：从 context 获取 n_orders 的输出
        val orders: List<TradeOrder> = when (rawInput) {
            is SwapWeakResult -> rawInput.orders
            is OrderGenerationResult -> rawInput.orders
            else -> context.getStageOutput<OrderGenerationResult>("n_orders")?.orders
                ?: emptyList()
        }
        // 📥 输入日志
        val orderCodes = orders.map { "${it.stockCode}(${it.stockName})" }
        context.log(nodeId, "📥 $nodeName 输入: ${orders.size} 个订单 ${formatTopCodes(orderCodes)}")

        if (orders.isEmpty()) {
            val db = StockDatabase.getInstance(context.androidContext)
            val period = orderTypePeriod(context.config.orderType)
            val currentTotal = db.strategyTradeOrderDao().getRecent(500)
                .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            // 📤 输出日志
            context.log(nodeId, "📤 $nodeName 输出: 0 新增, $currentTotal 总持仓（$period 周期）")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = 0, outputCount = 0,
                filterCount = 0,
                filterReason = "",
                inputCodes = emptyList(), outputCodes = emptyList()
            )
            return PositionMergeResult(0, emptyList(), currentTotal)
        }

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 获取当前持仓（按周期统计：只算与本 Pipeline 同周期的持仓，不跨周期累加）
            val period = orderTypePeriod(context.config.orderType)
            val existingOrders = db.strategyTradeOrderDao().getRecent(500)
                .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            val existingMap = existingOrders.associateBy { it.stockCode }

            // 容量安全网：新持仓插入不得超过可用仓位（swap_weak 已腾位，此为兜底）
            val maxHoldings = context.config.maxHoldings
            val availableSlots = maxHoldings - existingOrders.size

            var newCount = 0
            val updatedCodes = mutableListOf<String>()
            val entitiesToInsert = mutableListOf<StrategyTradeOrderEntity>()

            for (order in orders) {
                val existing = existingMap[order.stockCode]
                if (existing != null) {
                    // 已持有：追加加权平均
                    val totalQuantity = existing.quantity + order.quantity
                    val avgPrice = (existing.buyPrice * existing.quantity + order.buyPrice * order.quantity) /
                        totalQuantity.toDouble()
                    db.strategyTradeOrderDao().updateQuantityAndPrice(existing.id, totalQuantity, avgPrice)
                    updatedCodes.add("${order.stockCode}(追加${order.quantity}股,均价${"%.2f".format(avgPrice)})")
                } else if (newCount < availableSlots) {
                    // 新持仓：插入 BUYING 订单（与 Hardcode 路径一致）
                    val entity = StrategyTradeOrderEntity(
                        strategyId = order.strategyId,
                        stockCode = order.stockCode,
                        stockName = order.stockName,
                        tradeDate = order.tradeDate,
                        buyPrice = order.buyPrice,
                        buyTime = order.buyTime,
                        quantity = order.quantity,
                        orderType = order.orderType,
                        status = "BUYING",
                        reason = order.reason,
                        scoreAtBuy = order.scoreAtBuy
                    )
                    entitiesToInsert.add(entity)
                    newCount++
                } else {
                    // 超出可用仓位，跳过（安全网）
                    context.log(nodeId, "⚠ ${order.stockCode} 跳过：持仓已达上限 $maxHoldings（$period 周期）")
                }
            }

            // 批量插入新订单
            if (entitiesToInsert.isNotEmpty()) {
                db.strategyTradeOrderDao().insertAll(entitiesToInsert)
            }

            val totalHoldings = db.strategyTradeOrderDao().getRecent(500)
                .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }

            context.setStageOutput(nodeId, PositionMergeResult(newCount, updatedCodes, totalHoldings))

            // 📤 输出日志
            context.log(nodeId, "📤 $nodeName 输出: 新增 $newCount 只，更新 ${updatedCodes.size} 只，总持仓 $totalHoldings")

            // 🚫 过滤日志
            if (updatedCodes.isNotEmpty()) {
                context.log(nodeId, "  追加更新: ${updatedCodes.joinToString(", ")}")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = orders.size, outputCount = newCount,
                filterCount = orders.size - newCount,
                filterReason = if (orders.size - newCount > 0) "重复持仓合并" else "",
                inputCodes = orderCodes.take(5), outputCodes = updatedCodes.take(5)
            )

            context.log(nodeId, "持仓合并: ${orders.size} 个订单 → 新增 $newCount 只, " +
                "更新 ${updatedCodes.size} 只, 总持仓 $totalHoldings")

            PositionMergeResult(newCount, updatedCodes, totalHoldings)
        } catch (e: Exception) {
            context.log(nodeId, "持仓合并失败: ${e.message}")
            context.recordError(nodeId, "持仓合并失败: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = orders.size, outputCount = 0,
                filterCount = orders.size,
                filterReason = "执行失败: ${e.message}",
                inputCodes = orderCodes.take(5), outputCodes = emptyList()
            )
            PositionMergeResult(0, emptyList(), 0)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  11. BackgroundManagerNode (DATA_SOURCE)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 后台管理节点
 *
 * 在 AI 分析前暂停后台任务，分析后恢复。
 * 确保量化选股期间不会与后台定时任务（如新闻更新、持仓监控）冲突。
 *
 * 原始流程对应：AppBackgroundRunner.isQuantRunning
 */
class BackgroundManagerNode : BaseNode<Any, Unit>("bg_manager", "后台暂停/恢复", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): Unit {
        // 📥 输入日志
        context.log(nodeId, "📥 $nodeName 输入: 准备暂停后台任务（isQuantRunning=${AppBackgroundRunner.isQuantRunning}）")

        return try {
            // 立即暂停后台任务（不阻塞）
            AppBackgroundRunner.pauseForQuant()

            // 异步刷新新闻因子（不阻塞 pipeline，失败也无所谓）
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val updater = com.chin.stockanalysis.news.HotSectorNewsUpdater(context.androidContext.applicationContext)
                    updater.updateIfNeeded(forceRefresh = true)
                } catch (_: Exception) { /* 新闻刷新失败不影响 pipeline */ }
            }

            context.log(nodeId, "⏸️ 后台 AI 任务已暂停（isQuantRunning=${AppBackgroundRunner.isQuantRunning}）")

            // 记录暂停状态到 context，由 pipeline 执行器在完成后恢复
            context.setStageOutput(nodeId, "paused")

            // 📤 输出日志
            context.log(nodeId, "📤 $nodeName 输出: 后台已暂停，pipeline 完成后需调用 AppBackgroundRunner.resumeAfterQuant()")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = 0, outputCount = 0,
                filterCount = 0, filterReason = "",
                inputCodes = emptyList(), outputCodes = emptyList()
            )
        } catch (e: Exception) {
            context.log(nodeId, "后台管理失败: ${e.message}")
            context.recordError(nodeId, "后台管理失败: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = 0, outputCount = 0,
                filterCount = 0, filterReason = "",
                inputCodes = emptyList(), outputCodes = emptyList()
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  12. FittingSaveNode (DATA_TRANSFORM)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 拟合保存节点
 *
 * 对策略进行参数拟合（网格搜索），保存拟合结果到 DB。
 * 使用 Walk-Forward 分割：训练集 80%，测试集 20%。
 *
 * 原始流程对应：SimulationTradeEngine.runFitting()
 */
class FittingSaveNode : BaseNode<Any, Unit>("fitting_save", "拟合计算+保存", NodeType.DATA_TRANSFORM) {

    override suspend fun execute(context: PipelineContext, input: Any): Unit {
        // 兼容不同上游类型
        val totalHoldings: Int = when (input) {
            is PositionMergeResult -> input.totalHoldings
            is OrderGenerationResult -> {
                val db0 = StockDatabase.getInstance(context.androidContext)
                db0.strategyTradeOrderDao().getRecent(500)
                    .count { it.status == "BUYING" || it.status == "PENDING" }
            }
            else -> 0
        }

        // 📥 输入日志
        context.log(nodeId, "📥 $nodeName 输入: 总持仓 $totalHoldings, 上游类型=${input::class.simpleName}")

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 获取策略列表
            val strategies = context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
            if (strategies.isEmpty()) {
                context.log(nodeId, "无可用策略，跳过拟合")
                // 📤 输出日志
                context.log(nodeId, "📤 $nodeName 输出: 0 策略拟合（无策略）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = totalHoldings, outputCount = 0,
                    filterCount = 0,
                    filterReason = "无策略",
                    inputCodes = emptyList(), outputCodes = emptyList()
                )
                return
            }

            // 获取可用日期
            val availableDates = db.dailySnapshotDao().getAvailableDates(120)
                .sorted()
                .filter { it <= context.tradeDate }

            if (availableDates.size < 20) {
                context.log(nodeId, "历史数据不足（${availableDates.size} 天），跳过拟合")
                // 📤 输出日志
                context.log(nodeId, "📤 $nodeName 输出: 0 策略拟合（数据不足）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = totalHoldings, outputCount = 0,
                    filterCount = 0,
                    filterReason = "数据不足",
                    inputCodes = emptyList(), outputCodes = emptyList()
                )
                return
            }

            val optimizer = StrategyOptimizer(context.androidContext)
            var successCount = 0
            val fittingResults = mutableListOf<StrategyTradeFittingParamEntity>()

            // 并行拟合所有策略（避免串行超时 120s）
            val fitJobs = strategies.filter { it.id != "ai_prediction" }
            coroutineScope {
                val deferreds = fitJobs.map { strategy ->
                    async(Dispatchers.IO) {
                        try {
                            val result = optimizer.gridSearch(strategy, availableDates)
                            val weightsJson = result.bestWeights.joinToString(",") { "${it.key}=${it.weight}" }
                            val entity = StrategyTradeFittingParamEntity(
                                strategyId = strategy.id,
                                tradeDate = context.tradeDate,
                                periodDays = 20,
                                paramJson = weightsJson,
                                fittingRound = 1,
                                accuracy = result.bestAccuracy.toDouble(),
                                avgReturn = result.bestAvgReturn,
                                createdAt = System.currentTimeMillis()
                            )
                            context.log(nodeId, "  拟合策略 ${strategy.id}: " +
                                "accuracy=${"%.1f".format(result.bestAccuracy)}%, " +
                                "avgReturn=${"%.2f".format(result.bestAvgReturn)}%, " +
                                "combinations=${result.totalCombinations}")
                            entity
                        } catch (e: Exception) {
                            context.log(nodeId, "  拟合策略 ${strategy.id} 失败: ${e.message}")
                            null
                        }
                    }
                }

                // 等待所有拟合完成，收集成功结果
                val results = deferreds.map { it.await() }
                results.filterNotNull().forEach { fittingResults.add(it) }
                successCount = fittingResults.size
            }

            // 批量保存
            if (fittingResults.isNotEmpty()) {
                db.strategyTradeFittingParamDao().insertAll(fittingResults)
            }

            // 📤 输出日志
            context.log(nodeId, "📤 $nodeName 输出: ${strategies.size} 策略，拟合成功 $successCount/${strategies.size}")

            // 🚫 过滤日志
            val failedCount = strategies.size - successCount
            if (failedCount > 0) {
                context.log(nodeId, "🚫 $nodeName 拟合失败: $failedCount 个策略")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = totalHoldings, outputCount = successCount,
                filterCount = failedCount,
                filterReason = if (failedCount > 0) "拟合失败${failedCount}个策略" else "",
                inputCodes = emptyList(), outputCodes = emptyList()
            )

            context.log(nodeId, "拟合计算完成: ${strategies.size} 策略, " +
                "成功 $successCount, 日期范围 ${availableDates.firstOrNull()} ~ ${availableDates.lastOrNull()}")

            context.setStageOutput(nodeId, successCount)
        } catch (e: Exception) {
            context.log(nodeId, "拟合保存失败: ${e.message}")
            context.recordError(nodeId, "拟合保存失败: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = totalHoldings, outputCount = 0,
                filterCount = 0,
                filterReason = "执行失败: ${e.message}",
                inputCodes = emptyList(), outputCodes = emptyList()
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  HoldingGuardNode (TRADE_ACTION) — 持仓风控
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 持仓风控节点
 *
 * 独立于筛选链，每次 Pipeline 执行时**必定运行**（放 Layer 1，依赖 n_ctx）：
 * - 评估本周期全部持仓（AutoSellEngine：止损/止盈/策略退出/技术面恶化）
 * - shouldSell=true 的持仓直接执行卖出
 *
 * 与 SwapWeakNode 的分工：
 * - HoldingGuardNode：「治病」— 持仓本身出问题（止损/止盈触发），无论有无新候选都卖
 * - SwapWeakNode：「换血」— 持仓健康但仓位满，卖最弱腾位给更优的新票
 *
 * 非关键节点：失败不阻断 Pipeline。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_guard" name="持仓风控" module="holding_guard" />
 * <Link><SourceNodeId>n_ctx</SourceNodeId><TargetNodeId>n_guard</TargetNodeId></Link>
 * ```
 */
class HoldingGuardNode(
    private val strategies: List<Strategy> = emptyList()
) : BaseNode<Any, HoldingGuardResult>("holding_guard", "持仓风控", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: Any): HoldingGuardResult {
        val effectiveStrategies = strategies.ifEmpty {
            context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
        }

        val db = StockDatabase.getInstance(context.androidContext)
        val period = orderTypePeriod(context.config.orderType)
        val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
            .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }

        if (holdingOrders.isEmpty()) {
            context.log(nodeId, "📥 $nodeName: 无 $period 周期持仓，跳过评估")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = 0, outputCount = 0,
                filterCount = 0, filterReason = "",
                inputCodes = emptyList(), outputCodes = emptyList()
            )
            return HoldingGuardResult(0, emptyList(), 0, 0)
        }

        context.log(nodeId, "📥 $nodeName 输入: ${holdingOrders.size} 只 $period 持仓")

        return try {
            val sellEngine = AutoSellEngine(context.androidContext)
            // ── 大盘状态参数矩阵（工作台「回溯+拟合」落库） ──
            // 中线/长线按当前大盘状态读取拟合参数；CRASH（暴跌期）强制 1 天内迅速离场
            val currentState = FullCycleBacktestEngine.detectCurrentState(context.androidContext)
            val crashMode = currentState == FullCycleBacktestEngine.MarketState.CRASH
            val periodKey = when (period) {
                "mid" -> "中线"
                "long" -> "长线"
                else -> period
            }
            val fitted = if (!crashMode)
                FullCycleBacktestEngine.loadFitMatrix(context.androidContext, periodKey)[currentState]
                else null
            if (crashMode) {
                context.log(nodeId, "🚨 $nodeName: 检测到暴跌期(CRASH)，$period 持仓强制 1 天离场")
            } else if (fitted != null) {
                context.log(nodeId, "$nodeName: 应用${currentState.label}拟合参数 → 持有${fitted.maxHoldDays}天 / 止盈+${fitted.takeProfitPct}% / 止损${fitted.stopLossPct}%（样本${fitted.sampleCount}，平均${"%.2f".format(fitted.avgRet)}%）")
            }
            val decisions = sellEngine.evaluateAll(
                effectiveStrategies,
                AutoSellEngine.AutoSellConfig(
                    tradeDate = context.tradeDate,
                    hardStopLossPct = when {
                        // 暴跌期：无论中线长线，止损收紧到 -3% 快速离场
                        crashMode -> if (period == "long") -5.0 else -3.0
                        // 状态拟合矩阵优先
                        fitted != null -> fitted.stopLossPct
                        // 自适应止损：空头市场收紧硬止损；长期持仓放宽到 -25%
                        period == "long" -> -25.0
                        period == "mid" -> context.getAdaptiveParams()?.stopLossRate?.times(100) ?: -10.0
                        else -> context.getAdaptiveParams()?.stopLossRate?.times(100)
                            ?: AutoSellEngine.HARD_STOP_LOSS_PCT
                    },
                    timeForceCloseDays = when {
                        // 暴跌期：次日即走
                        crashMode -> 1
                        // 状态拟合矩阵：持有天数
                        fitted != null -> fitted.maxHoldDays
                        // 2026-08-15 一年回溯拟合最优参数（smalltools/out_year.txt）：
                        // 中线：持有 15 天到期卖 / +20% 单档止盈 / -10% 止损
                        period == "mid" -> 15
                        else -> AutoSellEngine.TIME_FORCE_CLOSE_DAYS
                    },
                    tpTiers = when {
                        crashMode -> null
                        fitted != null -> listOf(AutoSellEngine.TakeProfitTier(fitted.takeProfitPct, 1.0))
                        period == "mid" -> listOf(AutoSellEngine.TakeProfitTier(20.0, 1.0))
                        else -> null
                    }
                )
            ).filter { orderTypePeriod(it.order.orderType) == period }

            var mustSell = decisions.filter { it.shouldSell }

            // ── 长期持仓特殊保护：只卖突发利空，不卖常规止盈止损 ──
            // 长期容忍更大波动，止盈不触发，只有突发利空（单日跌幅>7%或3日累计跌幅>15%）才卖出
            // 暴跌期(CRASH)跳过该保护：即使常规卖出信号也放行，确保快速离场
            if (period == "long" && mustSell.isNotEmpty() && !crashMode) {
                val filteredSell = mustSell.filter { decision ->
                    try {
                        val recentSnaps = db.dailySnapshotDao()
                            .getByCode(decision.order.stockCode, 5)
                            .sortedBy { it.date }  // 按 date ASC，last() = 最新
                        if (recentSnaps.size >= 3) {
                            val singleDayDrop = recentSnaps.last().changePct
                            val threeDayDrop = recentSnaps.takeLast(3).sumOf { it.changePct }
                            // 突发利空：单日跌幅>7% 或 3日累计跌幅>15%
                            singleDayDrop < -7.0 || threeDayDrop < -15.0
                        } else false
                    } catch (_: Exception) {
                        false  // 查询失败时不卖（保守策略，避免误杀长期持仓）
                    }
                }
                val filteredOut = mustSell.size - filteredSell.size
                if (filteredOut > 0) {
                    context.log(nodeId, "长期持仓保护: 过滤掉 $filteredOut 只常规止盈止损信号，" +
                        "仅保留 ${filteredSell.size} 只突发利空信号")
                }
                mustSell = filteredSell
            }

            if (mustSell.isEmpty()) {
                context.log(nodeId, "📤 $nodeName: ${holdingOrders.size} 只持仓全部健康，无卖出信号")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = holdingOrders.size, outputCount = 0,
                    filterCount = 0, filterReason = "",
                    inputCodes = holdingOrders.map { it.stockCode }.take(5), outputCodes = emptyList()
                )
                return HoldingGuardResult(0, emptyList(), holdingOrders.size, holdingOrders.size)
            }

            // 执行卖出（shouldSell 已过滤，force=false 即可）
            val soldNames = mustSell.map { "${it.order.stockName}(${it.reason})" }
            context.log(nodeId, "🚫 $nodeName 卖出: ${soldNames.joinToString(", ")}")
            sellEngine.executeSells(mustSell, context.tradeDate)

            // 将风控卖出的股票代码存入 context，供下游订单生成节点过滤
            val soldCodes = mustSell.map { it.order.stockCode }.toSet()
            context.setStageOutput("guard_sold_codes", soldCodes)

            val remainingCount = db.strategyTradeOrderDao().getRecent(500)
                .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }

            context.log(nodeId, "📤 $nodeName 输出: 卖出 ${mustSell.size} 只，剩余 $remainingCount 只")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = holdingOrders.size, outputCount = mustSell.size,
                filterCount = 0, filterReason = "风控卖出",
                inputCodes = holdingOrders.map { it.stockCode }.take(5),
                outputCodes = mustSell.map { it.order.stockCode }
            )

            HoldingGuardResult(mustSell.size, soldNames, remainingCount, holdingOrders.size)
        } catch (e: Exception) {
            context.log(nodeId, "$nodeName 异常: ${e.message}（不阻断 Pipeline）")
            context.recordError(nodeId, "持仓风控异常: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = holdingOrders.size, outputCount = 0,
                filterCount = 0, filterReason = "执行异常: ${e.message}",
                inputCodes = holdingOrders.map { it.stockCode }.take(5), outputCodes = emptyList()
            )
            HoldingGuardResult(0, emptyList(), holdingOrders.size, holdingOrders.size)
        }
    }
}
