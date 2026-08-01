package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.CandidatePool
import com.chin.stockanalysis.strategy.data.ZiplinePipeline
import com.chin.stockanalysis.strategy.sector.StrategyMarketContext
import com.chin.stockanalysis.strategy.trade.HotSectorStockPool
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.stock.database.StockDataCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ════════════════════════════════════════════════════════════════════════════
//  Hardcode 補齊節點群
//
//  將 Hardcode 路徑中存在但 DAG Pipeline 缺少的步驟，
//  封裝為獨立的 PipelineNode，使兩條路徑產出一致。
// ════════════════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════════════════
//  1. CandidatePoolNode (FILTER) — 補齊所有周期的候選池
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 候選池過濾節點
 *
 * 將全市場股票池按 CandidatePool 的核心龍頭股 + AI 熱門板塊龍頭進行過濾。
 * 同時補充用戶搜索歷史、自選股、智能體推薦的股票。
 *
 * 補齊 Hardcode 路徑中所有周期都使用的 CandidatePool 邏輯。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_cand" name="候選池過濾" module="candidate_pool" />
 * ```
 * 放在 stock_pool 之後、signal_merge 之前。
 */
class CandidatePoolNode : PipelineNode<Any, StockPool> {

    override val nodeId: String = "candidate_pool"
    override val nodeName: String = "候選池過濾"
    override val nodeType: NodeType = NodeType.FILTER

    override suspend fun execute(context: PipelineContext, input: Any): StockPool {
        // 從 input 或 context 中按需讀取 StockPool
        val pool: StockPool = when (input) {
            is StockPool -> input
            else -> context.getStageOutput<StockPool>("stock_pool")
                ?: context.getStageOutput<StockPool>("n_pool")
                ?: StockPool(emptyList(), "empty")
        }
        val inputCodes = pool.stocks.map { it.code }
        context.log(nodeId, "📥 輸入: ${pool.size} 只股票")

        return try {
            // 1. 獲取 CandidatePool 代碼
            val candidateCodes = CandidatePool.getPoolCodes(context.androidContext).toMutableSet()
            context.log(nodeId, "CandidatePool 代碼: ${candidateCodes.size} 只")

            // 2. 補充用戶搜索歷史 + 自選股
            val userCodes = getUserStockCodes(context)
            candidateCodes.addAll(userCodes)
            context.log(nodeId, "用戶搜索/自選股補充: ${userCodes.size} 只，合計 ${candidateCodes.size} 只")

            // 3. 補充板塊精選池（如果 stageOutputs 中有 sector_stock_codes）
            val sectorCodes = context.getStageOutput<Set<String>>("sector_stock_codes")
            if (sectorCodes != null && sectorCodes.isNotEmpty()) {
                candidateCodes.addAll(sectorCodes)
                context.log(nodeId, "板塊精選池補充: ${sectorCodes.size} 只，合計 ${candidateCodes.size} 只")
            }

            // 4. 過濾全市場股票池，只保留候選池中的股票
            val filteredStocks = pool.stocks.filter { it.code in candidateCodes }

            // 5. 對於候選池中但不在全市場快照中的股票，從 DB 補充
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
                filterReason = "CandidatePool(${candidateCodes.size}) 過濾 + 用戶/板塊補充"
            )

            context.setStageOutput(nodeId, result)
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.size, outputCount = finalStocks.size,
                filterCount = pool.size - filteredStocks.size,
                filterReason = "候選池過濾",
                inputCodes = inputCodes.take(5),
                outputCodes = finalStocks.map { it.code }.take(5)
            )
            context.log(nodeId, "📤 輸出: ${finalStocks.size} 只 (過濾 ${pool.size - filteredStocks.size}, 補充 ${extraStocks.size})")
            result
        } catch (e: Exception) {
            context.log(nodeId, "候選池過濾失敗，返回原池: ${e.message}")
            context.recordError(nodeId, "候選池過濾失敗: ${e.message}")
            pool  // 失敗時返回原池，不阻塞流程
        }
    }

    /** 讀取用戶搜索歷史 + 自選股 */
    private suspend fun getUserStockCodes(context: PipelineContext): Set<String> = withContext(Dispatchers.IO) {
        try {
            val db = StockDatabase.getInstance(context.androidContext)
            val codes = mutableSetOf<String>()

            // 自選股
            val watchlist = db.userWatchlistDao().getAll()
            codes.addAll(watchlist.map { it.stockCode })

            // 用戶搜索歷史（StockDataCenter 內存緩存，最近 50 條）
            val searchHistory = StockDataCenter.getRecentSearches(50)
            codes.addAll(searchHistory.map { it.first })

            codes
        } catch (e: Exception) {
            Log.w("CandidatePoolNode", "讀取用戶股票失敗: ${e.message}")
            emptySet()
        }
    }

    /** 從 DB 補充缺失股票的實時數據 */
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
            Log.w("CandidatePoolNode", "DB 補充股票失敗: ${e.message}")
            emptyList()
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  2. ZiplineFactorNode (FACTOR_COMPUTE) — 補齊短線因子計算
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## Zipline 因子預計算節點
 *
 * 預計算 RSI(14) / 布林帶 / ATR(14) / MA5 / MA20 / 動量5日 / 10日均量，
 * 存入 PipelineContext 供下游策略節點使用。
 *
 * 補齊短線 Hardcode 路徑中的 ZiplinePipeline.computeAll() 步驟。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_zipline" name="Zipline因子計算" module="zipline_factor" />
 * ```
 * 放在 stock_pool/candidate_pool 之後、signal_merge 之前。
 */
class ZiplineFactorNode : PipelineNode<Any, StockPool> {

    override val nodeId: String = "zipline_factor"
    override val nodeName: String = "Zipline 因子計算"
    override val nodeType: NodeType = NodeType.FACTOR_COMPUTE

    override suspend fun execute(context: PipelineContext, input: Any): StockPool {
        // 從 input 或 context 中按需讀取 StockPool
        val pool: StockPool = when (input) {
            is StockPool -> input
            else -> context.getStageOutput<StockPool>("stock_pool")
                ?: context.getStageOutput<StockPool>("n_pool")
                ?: context.getStageOutput<StockPool>("candidate_pool")
                ?: context.getStageOutput<StockPool>("n_cand")
                ?: StockPool(emptyList(), "empty")
        }
        context.log(nodeId, "📥 輸入: ${pool.size} 只股票")

        return try {
            val zipline = ZiplinePipeline(context.androidContext)
            val factorSet = zipline.computeAll(pool.stocks, context.tradeDate, 30)

            // 存入 PipelineContext 供策略節點讀取
            context.setStageOutput("zipline_factors", factorSet)

            context.log(nodeId, "📤 因子計算完成: MA5=${factorSet.ma5.size}, RSI=${factorSet.rsi14.size}, BB=${factorSet.bbUpper.size}, ATR=${factorSet.atr14.size}")
            pool  // 透傳股票池，不修改
        } catch (e: Exception) {
            context.log(nodeId, "Zipline 因子計算失敗，跳過: ${e.message}")
            context.recordError(nodeId, "Zipline 因子計算失敗: ${e.message}")
            pool  // 失敗時透傳，不阻塞
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  3. SectorStockPoolNode (DATA_SOURCE) — 補齊中線板塊精選池
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 板塊精選池節點
 *
 * 調用 StrategyMarketContext.getHotSectorStockPool() 獲取板塊精選股票，
 * 輸出股票代碼集合存入 PipelineContext，供 CandidatePoolNode 合併。
 *
 * 補齊中線 Hardcode 路徑中的 getHotSectorStockPool() 步驟。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_sector_pool" name="板塊精選池" module="sector_stock_pool" />
 * ```
 * 放在 market_context 之後、stock_pool/candidate_pool 之前（並行）。
 */
class SectorStockPoolNode : PipelineNode<Any, StrategyMarketContext> {

    override val nodeId: String = "sector_stock_pool"
    override val nodeName: String = "板塊精選池"
    override val nodeType: NodeType = NodeType.DATA_SOURCE

    override suspend fun execute(context: PipelineContext, input: Any): StrategyMarketContext {
        // 從 input 或 context 中按需讀取市場上下文
        val marketCtx: StrategyMarketContext = when (input) {
            is StrategyMarketContext -> input
            else -> context.marketContext
                ?: context.getStageOutput<StrategyMarketContext>("market_context")
                ?: context.getStageOutput<StrategyMarketContext>("n_ctx")
                ?: StrategyMarketContext.build(context.androidContext, context.tradeDate, false)
        }
        context.log(nodeId, "📥 輸入: ${marketCtx.todayHotSectors.size} 個熱門板塊")

        return try {
            // 獲取板塊精選股票池（使用 HotSectorStockPool.build，傳入今日熱門板塊）
            val sectorCodes = HotSectorStockPool.build(
                context.androidContext,
                marketCtx.todayHotSectors.toSet()
            )

            // 存入 PipelineContext，供 CandidatePoolNode 讀取
            context.setStageOutput("sector_stock_codes", sectorCodes)

            context.log(nodeId, "📤 板塊精選池: ${sectorCodes.size} 只股票")
            marketCtx  // 透傳市場上下文
        } catch (e: Exception) {
            context.log(nodeId, "板塊精選池獲取失敗，跳過: ${e.message}")
            context.recordError(nodeId, "板塊精選池失敗: ${e.message}")
            marketCtx
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  4. T1AutoSellNode (TRADE_ACTION) — 補齊超短線 T+1 自動賣出
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## T+1 自動賣出節點
 *
 * 對超短線持倉執行 T+1 強制清倉邏輯：
 * - T+1 到期的持倉 → 無論盈虧強制賣出
 * - 當日建倉 → 僅止損/止盈觸發
 *
 * 補齊超短線 Hardcode 路徑中的 checkT1AutoSell() 步驟。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_t1sell" name="T+1自動賣出" module="t1_auto_sell" />
 * ```
 * 放在 position_merge 之後。
 */
class T1AutoSellNode(
    private val stopLossPct: Double = -2.0,
    private val takeProfitPct: Double = 3.0
) : PipelineNode<Any, T1AutoSellResult> {

    override val nodeId: String = "t1_auto_sell"
    override val nodeName: String = "T+1 自動賣出"
    override val nodeType: NodeType = NodeType.TRADE_ACTION

    override suspend fun execute(context: PipelineContext, input: Any): T1AutoSellResult {
        // 兼容上游 PositionMergeResult 或空輸入（上游失敗時仍執行 T+1 賣出）
        val totalHoldings = when (input) {
            is PositionMergeResult -> input.totalHoldings
            else -> -1  // 未知持倉數，從 DB 實際讀取
        }
        context.log(nodeId, "📥 輸入: 總持倉 ${if (totalHoldings >= 0) totalHoldings else "未知"} 只")

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val today = context.tradeDate

            // 獲取所有超短線活躍持倉
            val orders = db.strategyTradeOrderDao().getRecent(100)
                .filter { it.orderType == "UltraShortQuant" &&
                    (it.status == "BUYING" || it.status == "PENDING") }

            if (orders.isEmpty()) {
                context.log(nodeId, "無超短線持倉，跳過 T+1 賣出")
                return T1AutoSellResult(0, 0, 0.0)
            }

            // 獲取實時價格
            val repo = StockDataSourceFactory.createDefaultRepository(context.androidContext)
            val realtime = try {
                repo.getRealtime(orders.map { it.stockCode })
            } catch (e: Exception) {
                context.log(nodeId, "實時行情獲取失敗: ${e.message}")
                emptyMap()
            }

            var sellCount = 0
            var forcedCount = 0
            var totalPnl = 0.0

            for (order in orders) {
                val currentPrice = realtime[order.stockCode]?.price ?: continue
                if (currentPrice <= 0) continue
                val pnlPct = (currentPrice - order.buyPrice) / order.buyPrice * 100

                val isT1Due = order.tradeDate < today
                val hitStop = pnlPct <= stopLossPct || pnlPct >= takeProfitPct

                if (isT1Due || hitStop) {
                    db.strategyTradeOrderDao().updateSellInfo(
                        id = order.id, status = "SOLD",
                        sellPrice = currentPrice,
                        sellTime = today + " " + java.time.LocalTime.now().toString().take(8),
                        profitPct = pnlPct
                    )
                    sellCount++
                    totalPnl += pnlPct
                    if (isT1Due) forcedCount++

                    context.log(nodeId, "賣出: ${order.stockName} 盈虧=${"%.2f".format(pnlPct)}% " +
                        if (isT1Due) "(次日強制清倉)" else "(止損/止盈)")
                }
            }

            val result = T1AutoSellResult(sellCount, forcedCount, totalPnl / maxOf(sellCount, 1))
            context.setStageOutput(nodeId, result)
            context.log(nodeId, "📤 T+1 賣出完成: ${sellCount} 只 (${forcedCount} 只強制), 平均盈虧=${"%.2f".format(result.avgPnl)}%")
            result
        } catch (e: Exception) {
            context.log(nodeId, "T+1 賣出失敗: ${e.message}")
            context.recordError(nodeId, "T+1 賣出失敗: ${e.message}")
            T1AutoSellResult(0, 0, 0.0)
        }
    }
}

/** T+1 自動賣出結果 */
data class T1AutoSellResult(
    val sellCount: Int,
    val forcedCount: Int,
    val avgPnl: Double
)

// ════════════════════════════════════════════════════════════════════════════
//  5. CrossTabPublishNode (TRADE_ACTION) — 補齊短線/中線跨 Tab 發布
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 跨 Tab 發布節點
 *
 * 將 DAG Pipeline 的選股結果發布到 CrossTabBus，
 * 讓對話 Tab 可以接收量化選股結果作為 AI 上下文。
 *
 * 補齊短線/中線 Hardcode 路徑中的 CrossTabBus.postStrategyResults() 等步驟。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_crosstab" name="跨Tab發布" module="crosstab_publish" />
 * ```
 * 放在 position_merge 之後（並行於 fitting_save）。
 */
class CrossTabPublishNode : PipelineNode<Any, PositionMergeResult> {

    override val nodeId: String = "crosstab_publish"
    override val nodeName: String = "跨 Tab 發布"
    override val nodeType: NodeType = NodeType.TRADE_ACTION

    override suspend fun execute(context: PipelineContext, input: Any): PositionMergeResult {
        // 兼容上游 PositionMergeResult 或空輸入
        val mergeResult = input as? PositionMergeResult
            ?: PositionMergeResult(0, emptyList(), 0)
        context.log(nodeId, "📥 輸入: 新增 ${mergeResult.newCount} 只, 總持倉 ${mergeResult.totalHoldings}")

        return try {
            // 1. 從 stageOutputs 讀取信號合併結果（SignalMergeNode 的 nodeId = "signal_merge"）
            val mergedPool = context.getStageOutput<MergedSignalPool>("signal_merge")
            if (mergedPool != null) {
                val poolMap = mergedPool.stockHits
                com.chin.stockanalysis.ui.CrossTabBus.postMergedPool(poolMap)
                context.log(nodeId, "發布合併池: ${poolMap.size} 只股票")
            }

            // 2. 從 stageOutputs 讀取 AI 精選結果（AIPredictNode 的 nodeId = "ai_predict"）
            val aiResult = context.getStageOutput<Any>("ai_predict")
            if (aiResult != null) {
                context.log(nodeId, "AI 精選結果已獲取")
            }

            // 3. 發布持倉上下文
            val db = StockDatabase.getInstance(context.androidContext)
            val holdings = db.strategyTradeOrderDao().getRecent(50)
                .filter { it.status == "BUYING" || it.status == "PENDING" }
            if (holdings.isNotEmpty()) {
                val ctxMap = holdings.associate { it.stockCode to it.stockName }
                com.chin.stockanalysis.ui.CrossTabBus.postStockContext(ctxMap)
                context.log(nodeId, "發布持倉上下文: ${ctxMap.size} 只")
            }

            context.setStageOutput(nodeId, mergeResult)
            context.log(nodeId, "📤 跨 Tab 發布完成")
            mergeResult  // 透傳，不影響後續流程
        } catch (e: Exception) {
            context.log(nodeId, "跨 Tab 發布失敗: ${e.message}")
            context.recordError(nodeId, "跨 Tab 發布失敗: ${e.message}")
            mergeResult
        }
    }
}
