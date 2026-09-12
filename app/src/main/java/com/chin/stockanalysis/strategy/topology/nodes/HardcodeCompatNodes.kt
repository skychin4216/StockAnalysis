package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.strategy.topology.pipelines.PositionMergeResult
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.CandidatePool
import com.chin.stockanalysis.strategy.data.ZiplinePipeline
import com.chin.stockanalysis.strategy.sector.StrategyMarketContext
import com.chin.stockanalysis.strategy.trade.HotSectorStockPool
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.stock.database.StockDataCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ════════════════════════════════════════════════════════════════════════════
//  Hardcode 补齐节点群
//
//  将 Hardcode 路径中存在但 DAG Pipeline 缺少的步骤，
//  封装为独立的 PipelineNode，使两条路径产出一致。
// ════════════════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════════════════
//  1. CandidatePoolNode (FILTER) — 补齐所有周期的候选池
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 候选池过滤节点
 *
 * 将全市场股票池按 CandidatePool 的核心龙头股 + AI 热门板块龙头进行过滤。
 * 同时补充用户搜索历史、自选股、智能体推荐的股票。
 *
 * 补齐 Hardcode 路径中所有周期都使用的 CandidatePool 逻辑。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_cand" name="候选池过滤" module="candidate_pool" />
 * ```
 * 放在 stock_pool 之后、signal_merge 之前。
 */
class CandidatePoolNode : BaseNode<Any, StockPool>("candidate_pool", "候选池过滤", NodeType.FILTER) {

    override suspend fun execute(context: PipelineContext, input: Any): StockPool {
        // 从 input 或 context 中按需读取 StockPool
        val pool: StockPool = when (input) {
            is StockPool -> input
            else -> context.getStageOutput<StockPool>("stock_pool")
                ?: context.getStageOutput<StockPool>("n_pool")
                ?: StockPool(emptyList(), "empty")
        }
        val inputCodes = pool.stocks.map { it.code }
        context.log(nodeId, "📥 输入: ${pool.size} 只股票")

        return try {
            // 1. 获取 CandidatePool 代码
            val candidateCodes = CandidatePool.getPoolCodes(context.androidContext).toMutableSet()
            context.log(nodeId, "CandidatePool 代码: ${candidateCodes.size} 只")

            // 2. 补充用户搜索历史 + 自选股
            val userCodes = getUserStockCodes(context)
            candidateCodes.addAll(userCodes)
            context.log(nodeId, "用户搜索/自选股补充: ${userCodes.size} 只，合计 ${candidateCodes.size} 只")

            // 3. 补充板块精选池（如果 stageOutputs 中有 sector_stock_codes）
            val sectorCodes = context.getStageOutput<Set<String>>("sector_stock_codes")
            if (sectorCodes != null && sectorCodes.isNotEmpty()) {
                candidateCodes.addAll(sectorCodes)
                context.log(nodeId, "板块精选池补充: ${sectorCodes.size} 只，合计 ${candidateCodes.size} 只")
            }

            // 4. 补充 AI 精选（ai_selected_stock 近 5 天）
            val aiCodes = getAiSelectionCodes(context)
            if (aiCodes.isNotEmpty()) {
                candidateCodes.addAll(aiCodes)
                context.log(nodeId, "AI 精选补充: ${aiCodes.size} 只，合计 ${candidateCodes.size} 只")
            }

            // 5. 过滤全市场股票池，只保留候选池中的股票
            val filteredStocks = pool.stocks.filter { it.code in candidateCodes }

            // 6. 对于候选池中但不在全市场快照中的股票，从 DB 补充
            val existingCodes = filteredStocks.map { it.code }.toSet()
            val missingCodes = candidateCodes - existingCodes
            val extraStocks = if (missingCodes.isNotEmpty()) {
                fetchStocksFromDb(context, missingCodes.toList())
            } else emptyList()

            val finalStocks = (filteredStocks + extraStocks)
                .distinctBy { it.code }
                .filterNot { it.code.startsWith("sh000") || it.code.startsWith("sz399") }
            val result = StockPool(
                stocks = finalStocks,
                source = "candidate_pool_filtered",
                totalCount = pool.size,
                filterReason = "CandidatePool(${candidateCodes.size}) 过滤 + 用户/板块补充"
            )

            context.setStageOutput(nodeId, result)
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.size, outputCount = finalStocks.size,
                filterCount = pool.size - filteredStocks.size,
                filterReason = "候选池过滤",
                inputCodes = inputCodes.take(6),
                outputCodes = finalStocks.map { it.code }.take(6)
            )
            context.log(nodeId, "📤 输出: ${finalStocks.size} 只 (过滤 ${pool.size - filteredStocks.size}, 补充 ${extraStocks.size})")
            result
        } catch (e: Exception) {
            context.log(nodeId, "候选池过滤失败，返回原池: ${e.message}")
            context.recordError(nodeId, "候选池过滤失败: ${e.message}")
            pool  // 失败时返回原池，不阻塞流程
        }
    }

    /** 读取用户搜索历史 + 自选股 */
    private suspend fun getUserStockCodes(context: PipelineContext): Set<String> = withContext(Dispatchers.IO) {
        try {
            val db = StockDatabase.getInstance(context.androidContext)
            val codes = mutableSetOf<String>()

            // 自选股
            val watchlist = db.userWatchlistDao().getAll()
            codes.addAll(watchlist.map { it.stockCode })

            // 用户搜索历史（StockDataCenter 内存缓存，最近 50 条）
            val searchHistory = StockDataCenter.getRecentSearches(50)
            codes.addAll(searchHistory.map { it.first })

            codes
        } catch (e: Exception) {
            Log.w("CandidatePoolNode", "读取用户股票失败: ${e.message}")
            emptySet()
        }
    }

    /** 读取 AI 精选（ai_selected_stock 近 5 天） */
    private suspend fun getAiSelectionCodes(context: PipelineContext): Set<String> = withContext(Dispatchers.IO) {
        try {
            val db = StockDatabase.getInstance(context.androidContext)
            val minDate = java.time.LocalDate.now().minusDays(5)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            db.aiSelectedStockDao().getRecentDays(minDate).map { it.stockCode }.toSet()
        } catch (e: Exception) {
            Log.w("CandidatePoolNode", "读取 AI 精选失败: ${e.message}")
            emptySet()
        }
    }

    /** 从 DB 补充缺失股票的实时数据 */
    private suspend fun fetchStocksFromDb(context: PipelineContext, codes: List<String>): List<StockRealtime> = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext emptyList()
        try {
            val db = StockDatabase.getInstance(context.androidContext)
            val today = context.tradeDate
            val snaps = db.dailySnapshotDao().getByDate(today)
            val snapMap = snaps.associateBy { it.code }
            codes.mapNotNull { code ->
                val snap = snapMap[code] ?: return@mapNotNull null
                StockRealtime(
                    code = snap.code, name = snap.name, price = snap.close,
                    open = snap.open, yestClose = snap.close, high = snap.high, low = snap.low,
                    volume = snap.volume, amount = snap.amount,
                    changePercent = snap.changePct, changeAmount = 0.0,
                    timestamp = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.w("CandidatePoolNode", "DB 补充股票失败: ${e.message}")
            emptyList()
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  2. ZiplineFactorNode (FACTOR_COMPUTE) — 补齐短线因子计算
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## Zipline 因子预计算节点
 *
 * 预计算 RSI(14) / 布林带 / ATR(14) / MA5 / MA20 / 动量5日 / 10日均量，
 * 存入 PipelineContext 供下游策略节点使用。
 *
 * 补齐短线 Hardcode 路径中的 ZiplinePipeline.computeAll() 步骤。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_zipline" name="Zipline因子计算" module="zipline_factor" />
 * ```
 * 放在 stock_pool/candidate_pool 之后、signal_merge 之前。
 */
class ZiplineFactorNode : BaseNode<Any, StockPool>("zipline_factor", "Zipline 因子计算", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): StockPool {
        // 从 input 或 context 中按需读取 StockPool
        val pool: StockPool = when (input) {
            is StockPool -> input
            else -> context.getStageOutput<StockPool>("stock_pool")
                ?: context.getStageOutput<StockPool>("n_pool")
                ?: context.getStageOutput<StockPool>("candidate_pool")
                ?: context.getStageOutput<StockPool>("n_cand")
                ?: StockPool(emptyList(), "empty")
        }
        context.log(nodeId, "📥 输入: ${pool.size} 只股票")

        return try {
            val zipline = ZiplinePipeline(context.androidContext)
            val factorSet = zipline.computeAll(pool.stocks, context.tradeDate, 30)

            // 存入 PipelineContext 供策略节点读取
            context.setStageOutput("zipline_factors", factorSet)

            context.log(nodeId, "📤 因子计算完成: MA5=${factorSet.ma5.size}, RSI=${factorSet.rsi14.size}, BB=${factorSet.bbUpper.size}, ATR=${factorSet.atr14.size}")
            pool  // 透传股票池，不修改
        } catch (e: Exception) {
            context.log(nodeId, "Zipline 因子计算失败，跳过: ${e.message}")
            context.recordError(nodeId, "Zipline 因子计算失败: ${e.message}")
            pool  // 失败时透传，不阻塞
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  3. SectorStockPoolNode (DATA_SOURCE) — 补齐中线板块精选池
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 板块精选池节点
 *
 * 调用 StrategyMarketContext.getHotSectorStockPool() 获取板块精选股票，
 * 输出股票代码集合存入 PipelineContext，供 CandidatePoolNode 合并。
 *
 * 补齐中线 Hardcode 路径中的 getHotSectorStockPool() 步骤。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_sector_pool" name="板块精选池" module="sector_stock_pool" />
 * ```
 * 放在 market_context 之后、stock_pool/candidate_pool 之前（并行）。
 */
/**
 * ## 板块精选池节点
 *
 * 按配置的周期维度聚合板块（今日/周/月/季度/轮动预测），
 * 使用 HotSectorStockPool.build 获取这些板块的龙头股票。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_sector_pool" name="板块精选池" module="sector_stock_pool">
 *   <config>
 *     <!-- 逗号分隔：today,weekly,monthly,quarterly,rotation -->
 *     <param name="hotDims" value="today,weekly,rotation" />
 *   </config>
 * </Node>
 * ```
 */
class SectorStockPoolNode(
    private val hotDims: List<String> = listOf("today")
) : BaseNode<Any, StrategyMarketContext>("sector_stock_pool", "板块精选池", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): StrategyMarketContext {
        // 从 input 或 context 中按需读取市场上下文
        val marketCtx: StrategyMarketContext = when (input) {
            is StrategyMarketContext -> input
            else -> context.marketContext
                ?: context.getStageOutput<StrategyMarketContext>("market_context")
                ?: context.getStageOutput<StrategyMarketContext>("n_ctx")
                ?: StrategyMarketContext.build(context.androidContext, context.tradeDate, false)
        }
        context.log(nodeId, "📥 输入: ${marketCtx.todayHotSectors.size} 个热门板块，维度: ${hotDims.joinToString(",")}")

        return try {
            // 按配置维度聚合板块名
            val sectorNames = buildSectorNames(marketCtx)

            // 获取板块精选股票池（使用 HotSectorStockPool.build）
            val sectorCodes = HotSectorStockPool.build(
                context.androidContext,
                sectorNames
            )

            // 存入 PipelineContext，供 CandidatePoolNode 读取
            context.setStageOutput("sector_stock_codes", sectorCodes)

            context.log(nodeId, "📤 板块精选池: ${sectorCodes.size} 只股票，覆盖板块: ${sectorNames.take(8).joinToString(",")}")
            marketCtx  // 透传市场上下文
        } catch (e: Exception) {
            context.log(nodeId, "板块精选池获取失败，跳过: ${e.message}")
            context.recordError(nodeId, "板块精选池失败: ${e.message}")
            marketCtx
        }
    }

    /** 按配置维度聚合板块名（去重） */
    private fun buildSectorNames(marketCtx: StrategyMarketContext): Set<String> {
        val names = mutableSetOf<String>()
        if ("today" in hotDims) names += marketCtx.todayHotSectors
        if ("weekly" in hotDims) names += marketCtx.weeklyHotSectors
        if ("monthly" in hotDims) names += marketCtx.monthlyHotSectors
        if ("quarterly" in hotDims) names += marketCtx.quarterlyHotSectors
        if ("rotation" in hotDims) names += marketCtx.rotationPredictedSectors
        if (names.isEmpty()) names += marketCtx.todayHotSectors  // 兜底
        return names
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  4. T1AutoSellNode (TRADE_ACTION) — 补齐超短线 T+1 自动卖出
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## T+1 自动卖出节点
 *
 * 对超短线持仓执行 T+1 强制清仓逻辑：
 * - T+1 到期的持仓 → 无论盈亏强制卖出
 * - 当日建仓 → 仅止损/止盈触发
 *
 * 补齐超短线 Hardcode 路径中的 checkT1AutoSell() 步骤。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_t1sell" name="T+1自动卖出" module="t1_auto_sell" />
 * ```
 * 放在 position_merge 之后。
 */
class T1AutoSellNode(
    private val stopLossPct: Double = -2.0,
    private val takeProfitPct: Double = 3.0
) : BaseNode<Any, T1AutoSellResult>("t1_auto_sell", "T+1 自动卖出", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: Any): T1AutoSellResult {
        // 兼容上游 PositionMergeResult 或空输入（上游失败时仍执行 T+1 卖出）
        val totalHoldings = when (input) {
            is PositionMergeResult -> input.totalHoldings
            else -> -1  // 未知持仓数，从 DB 实际读取
        }
        context.log(nodeId, "📥 输入: 总持仓 ${if (totalHoldings >= 0) totalHoldings else "未知"} 只")

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val today = context.tradeDate

            // 获取所有超短线活跃持仓
            val orders = db.strategyTradeOrderDao().getRecent(100)
                .filter { orderTypePeriod(it.orderType) == "ultra_short" &&
                    (it.status == "BUYING" || it.status == "PENDING") }

            if (orders.isEmpty()) {
                context.log(nodeId, "无超短线持仓，跳过 T+1 卖出")
                return T1AutoSellResult(0, 0, 0.0)
            }

            // 获取实时价格
            val repo = StockDataSourceFactory.createDefaultRepository(context.androidContext)
            val realtime = try {
                repo.getRealtime(orders.map { it.stockCode })
            } catch (e: Exception) {
                context.log(nodeId, "实时行情获取失败: ${e.message}")
                emptyMap()
            }

            var sellCount = 0
            var forcedCount = 0
            var totalPnl = 0.0

            for (order in orders) {
                val currentPrice = realtime[order.stockCode]?.price ?: continue
                if (currentPrice <= 0) continue
                // 成本价缺失/为 0 时无法计算真实盈亏，跳过以免出现 Infinity 误判止盈
                if (order.buyPrice <= 0) continue
                val pnlPct = (currentPrice - order.buyPrice) / order.buyPrice * 100

                // ── 口诀卖出侧（2026-09-10，与 n_ancestral 口诀同源）──
                //   急跌无量是洗盘 → 豁免止损（勿被洗出）
                //   缓跌放量立马撤 / 卖在人声鼎沸 / 连续大涨要离场 → 主动卖出（不等 T+1）
                val snaps = try {
                    db.dailySnapshotDao().getByCode(order.stockCode, 30).sortedBy { it.date }
                } catch (_: Exception) { emptyList() }
                val idiom = sellIdiom(snaps)

                val isT1Due = order.tradeDate < today
                val hitStop = pnlPct <= stopLossPct || pnlPct >= takeProfitPct
                val exemptStop = idiom.washout && pnlPct < 0          // 洗盘豁免（仅亏损侧）
                val idiomExit = idiom.flee || idiom.euphoria || idiom.bigGain

                if (isT1Due || (hitStop && !exemptStop) || idiomExit) {
                    db.strategyTradeOrderDao().updateSellInfo(
                        id = order.id, status = "SOLD",
                        sellPrice = currentPrice,
                        sellTime = today + " " + java.time.LocalTime.now().toString().take(8),
                        profitPct = pnlPct
                    )
                    sellCount++
                    totalPnl += pnlPct
                    if (isT1Due) forcedCount++

                    val why = when {
                        isT1Due -> "(次日强制清仓)"
                        idiomExit -> "(口诀: ${idiom.reason})"
                        else -> "(止损/止盈)"
                    }
                    context.log(nodeId, "卖出: ${order.stockName} 盈亏=${"%.2f".format(pnlPct)}% $why")
                } else if (exemptStop) {
                    context.log(nodeId, "🛡 ${order.stockName} 触止损(${"%.2f".format(pnlPct)}%)但急跌无量判定为洗盘 → 豁免不卖")
                }
            }

            val result = T1AutoSellResult(sellCount, forcedCount, totalPnl / maxOf(sellCount, 1))
            context.setStageOutput(nodeId, result)
            context.log(nodeId, "📤 T+1 卖出完成: ${sellCount} 只 (${forcedCount} 只强制), 平均盈亏=${"%.2f".format(result.avgPnl)}%")
            result
        } catch (e: Exception) {
            context.log(nodeId, "T+1 卖出失败: ${e.message}")
            context.recordError(nodeId, "T+1 卖出失败: ${e.message}")
            T1AutoSellResult(0, 0, 0.0)
        }
    }

    /** 口诀卖出侧信号（2026-09-10）。 */
    private data class SellIdiom(
        val washout: Boolean,   // 急跌无量是洗盘 → 豁免止损
        val flee: Boolean,      // 缓跌放量立马撤 → 主动卖出
        val euphoria: Boolean,  // 卖在人声鼎沸时 → 主动止盈
        val bigGain: Boolean,   // 连续大涨要离场 → 主动止盈
        val reason: String
    )

    /** 判定口诀卖出侧信号（与 n_ancestral 口诀同口径）。 */
    private fun sellIdiom(
        candles: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>
    ): SellIdiom {
        if (candles.size < 20) return SellIdiom(false, false, false, false, "")
        val n = candles.size
        val t0 = candles[n - 1]
        fun cg(i: Int): Double {
            if (i < 1) return 0.0
            val b = candles[i - 1].close
            return if (b > 0) (candles[i].close / b - 1) * 100 else 0.0
        }
        val c1 = cg(n - 1); val c2 = cg(n - 2); val c3 = cg(n - 3)
        val cum3 = ((1 + c1 / 100) * (1 + c2 / 100) * (1 + c3 / 100) - 1) * 100
        val avgV = candles.takeLast(20).map { it.volume.toDouble() }.average()
        val vr = if (avgV > 0) t0.volume.toDouble() / avgV else 1.0
        val closes = candles.map { it.close }
        val hi = closes.maxOrNull() ?: t0.close
        val lo = closes.minOrNull() ?: t0.close
        val pos = if (hi > lo) (t0.close - lo) / (hi - lo) else 0.5
        val washout = c1 <= -3.0 && vr < 0.8
        val flee = c1 in -3.0..-0.1 && c2 in -3.0..-0.1 && vr > 1.5
        val euphoria = pos > 0.95 && vr > 2.0 && Math.abs(c1) < 1.0
        val bigGain = cum3 >= 12.0 || listOf(c1, c2, c3).count { it >= 6.0 } >= 2
        val reason = when {
            flee -> "缓跌放量撤(量比${"%.2f".format(vr)})"
            bigGain -> "连续大涨要离场(3日${"%.1f".format(cum3)}%)"
            euphoria -> "卖在人声鼎沸(高位放量滞涨)"
            else -> ""
        }
        return SellIdiom(washout, flee, euphoria, bigGain, reason)
    }
}

/** T+1 自动卖出结果 */
data class T1AutoSellResult(
    val sellCount: Int,
    val forcedCount: Int,
    val avgPnl: Double
)

// ════════════════════════════════════════════════════════════════════════════
//  5. CrossTabPublishNode (TRADE_ACTION) — 补齐短线/中线跨 Tab 发布
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 跨 Tab 发布节点
 *
 * 将 DAG Pipeline 的选股结果发布到 CrossTabBus，
 * 让对话 Tab 可以接收量化选股结果作为 AI 上下文。
 *
 * 补齐短线/中线 Hardcode 路径中的 CrossTabBus.postStrategyResults() 等步骤。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_crosstab" name="跨Tab发布" module="crosstab_publish" />
 * ```
 * 放在 position_merge 之后（并行于 fitting_save）。
 */
class CrossTabPublishNode : BaseNode<Any, PositionMergeResult>("crosstab_publish", "跨 Tab 发布", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: Any): PositionMergeResult {
        // 兼容上游 PositionMergeResult 或空输入
        val mergeResult = input as? PositionMergeResult
            ?: PositionMergeResult(0, emptyList(), 0)
        context.log(nodeId, "📥 输入: 新增 ${mergeResult.newCount} 只, 总持仓 ${mergeResult.totalHoldings}")

        return try {
            // 1. 从 stageOutputs 读取信号合并结果（SignalMergeNode 的 nodeId = "signal_merge"）
            val mergedPool = context.getStageOutput<MergedSignalPool>("signal_merge")
            if (mergedPool != null) {
                val poolMap = mergedPool.stockHits
                com.chin.stockanalysis.ui.CrossTabBus.postMergedPool(poolMap)
                context.log(nodeId, "发布合并池: ${poolMap.size} 只股票")
            }

            // 2. 从 stageOutputs 读取 AI 精选结果（AIPredictNode 的 nodeId = "ai_predict"）
            val aiResult = context.getStageOutput<Any>("ai_predict")
            if (aiResult != null) {
                context.log(nodeId, "AI 精选结果已获取")
            }

            // 3. 发布持仓上下文
            val db = StockDatabase.getInstance(context.androidContext)
            val holdings = db.strategyTradeOrderDao().getRecent(50)
                .filter { it.status == "BUYING" || it.status == "PENDING" }
            if (holdings.isNotEmpty()) {
                val ctxMap = holdings.associate { it.stockCode to it.stockName }
                com.chin.stockanalysis.ui.CrossTabBus.postStockContext(ctxMap)
                context.log(nodeId, "发布持仓上下文: ${ctxMap.size} 只")
            }

            context.setStageOutput(nodeId, mergeResult)
            context.log(nodeId, "📤 跨 Tab 发布完成")
            mergeResult  // 透传，不影响后续流程
        } catch (e: Exception) {
            context.log(nodeId, "跨 Tab 发布失败: ${e.message}")
            context.recordError(nodeId, "跨 Tab 发布失败: ${e.message}")
            mergeResult
        }
    }
}
