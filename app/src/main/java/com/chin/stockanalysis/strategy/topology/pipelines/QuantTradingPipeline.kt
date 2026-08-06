package com.chin.stockanalysis.strategy.topology.pipelines

import android.content.Context
import com.chin.stockanalysis.agent.v2.ProfitQualityLevel
import com.chin.stockanalysis.agent.v2.ProfitQualityAnalyzer
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.AppBackgroundRunner
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
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
import kotlin.math.abs
import kotlin.math.roundToInt

// ════════════════════════════════════════════════════════════════════════════
//  量化交易 Pipeline（QuantTradingPipeline）
//
//  涵蓋完整量化交易流程的節點與數據類型：
//  ── 市場分析 ──
//  1. AdaptiveParamsNode         — 大盤分析+自適應參數（動態閾值/數量限制）
//  2. MultiPeriodHotNode         — 多周期熱門股聚合（3/5/10/30/50/100天漲幅Top3）
//  3. CrossDayAggregationNode    — 跨日聚合（回溯N天策略命中頻次）
//  ── 信號增強 ──
//  4. HeatScoreNode              — 熱度計算（5維熱度分數 0-100）
//  5. NewsStrengthNode           — 新聞力度計算（近3天新聞影響力×情緒）
//  6. RotationPenaltyNode        — 板塊輪動懲罰（防止板塊過度集中）
//  7. NewsGuardNode              — 新聞攔截（買入前利空攔截）+ 技術過濾（4條規則）
//  ── 交易執行 ──
//  8. GenerateOrdersNode         — 買入訂單生成（AI精選 + 自適應參數）
//  9. PositionMergeNode          — 持倉合併（追加加權平均 / 新增PENDING訂單）
//  10. SwapWeakNode              — 騰龍換鳥（倉位不足時自動換股）
//  11. HoldingGuardNode          — 持倉風控（止損/止盈/策略退出）
//  ── 基礎設施 ──
//  12. BackgroundManagerNode     — 後臺暫停/恢復（AI分析前暫停，分析後恢復）
//  13. FittingSaveNode           — 擬合計算+保存（網格搜索擬合結果保存到DB）
// ════════════════════════════════════════════════════════════════════════════

private const val TAG = "QuantTrading"
private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

// ──────────────────────────────────────────────────────────────────────────────
//  輔助函數
// ──────────────────────────────────────────────────────────────────────────────

/** 格式化 top N 代碼列表用於日誌輸出 */
private fun formatTopCodes(codes: Collection<String>, limit: Int = 5): String =
    codes.take(limit).joinToString(prefix = "[", separator = ", ", postfix = "]")

/** 格式化代碼+名稱對用於日誌輸出 */
private fun formatTopCodeNames(pairs: Collection<Pair<String, String>>, limit: Int = 5): String =
    pairs.take(limit).joinToString(prefix = "[", separator = ", ", postfix = "]") { "${it.first}(${it.second})" }

// ──────────────────────────────────────────────────────────────────────────────
//  數據類
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 跨日聚合結果：股票代碼 → 被命中的天數
 */
data class CrossDayResult(
    val rankings: List<Pair<String, Int>>,  // (stockCode, hitDays) 按命中天數降序
    val windowDays: Int,                    // 實際回溯天數
    val baseDate: String                    // 基準日期
)

/**
 * 多周期熱門股結果：去重後的熱門股票代碼集合
 */
data class MultiPeriodHotResult(
    val hotStocks: Set<String>,            // 各周期 Top3 去重合集
    val periodCount: Int,                   // 成功計算的周期數
    val periods: List<Int>                  // 計算的周期列表
)

/**
 * 新聞攔截結果
 */
data class NewsGuardResult(
    val blockedCodes: Set<String>,          // 被攔截的股票代碼
    val blockedReasons: Map<String, String>, // code → 攔截原因
    val technicalFilteredCodes: Set<String>, // 技術規則過濾的代碼
    val passedCodes: Set<String>            // 最終通過的代碼
)

/**
 * 騰龍換鳥結果
 *
 * orders 透傳上游 GenerateOrdersNode 的買入訂單，無論是否換股都不丟棄。
 * 下游 PositionMergeNode 從 orders 中提取訂單執行持倉入庫。
 */
data class SwapWeakResult(
    val swappedCount: Int,                  // 實際換股數量
    val soldStocks: List<String>,          // 被賣出的股票（名稱）
    val beforeCount: Int,                   // 換股前持倉數
    val afterCount: Int,                    // 換股後持倉數
    val orders: List<TradeOrder> = emptyList()  // 透傳買入訂單
)

/**
 * 持倉風控結果
 */
data class HoldingGuardResult(
    val soldCount: Int,                     // 風控賣出數量（止損/止盈/策略退出）
    val soldStocks: List<String>,          // 被賣出的股票（名稱+原因）
    val remainingCount: Int,               // 賣出後剩餘持倉數
    val evaluatedCount: Int                 // 評估的持倉總數
)

/**
 * 買入訂單生成結果
 */
data class OrderGenerationResult(
    val orders: List<TradeOrder>,
    val filteredCount: Int,
    val emptyTriggered: Boolean
)

/**
 * 持倉合併結果
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
 * ## 跨日聚合節點
 *
 * 回溯最近 [windowDays] 個交易日，對股票池中的股票逐一重跑策略，
 * 統計每隻股票被命中的天數，取 Top [topN] 隻。
 *
 * 中線獨有：短線只用當日數據，中線需要連續性驗證。
 *
 * @property windowDays 回溯天數（默認 5）
 * @property topN 取 Top N（默認 20）
 * @property strategies 策略列表（外部注入，避免從 context 讀取）
 */
class CrossDayAggregationNode(
    private val windowDays: Int = 5,
    private val topN: Int = 20,
    private val strategies: List<Strategy> = emptyList()
) : BaseNode<Any, CrossDayResult>("cross_day_aggregation", "跨日聚合", NodeType.AGGREGATION) {

    override suspend fun execute(context: PipelineContext, input: Any): CrossDayResult {
        // 從不同上游類型中提取股票代碼集合
        val poolCodes: Set<String> = when (input) {
            is Set<*> -> input.filterIsInstance<String>().toSet()
            is StockPool -> input.stocks.map { it.code }.toSet()
            is MultiPeriodHotResult -> input.hotStocks
            is List<*> -> {
                // 聚合節點收到多個上游輸出，合併所有股票代碼
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
                context.log(nodeId, "⚠ 未知輸入類型: ${input::class.simpleName}，嘗試從 context 讀取股票池")
                context.getStageOutput<StockPool>("stock_pool")?.stocks?.map { it.code }?.toSet() ?: emptySet()
            }
        }

        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: ${poolCodes.size} 只股票 ${formatTopCodes(poolCodes)}")

        if (poolCodes.isEmpty()) {
            context.log(nodeId, "股票池為空，跳過跨日聚合")
            return CrossDayResult(emptyList(), 0, context.tradeDate)
        }

        val effectiveStrategies = strategies.ifEmpty {
            context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
        }
        if (effectiveStrategies.isEmpty()) {
            context.log(nodeId, "無可用策略，跳過跨日聚合")
            return CrossDayResult(emptyList(), 0, context.tradeDate)
        }

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            val allDates = db.dailySnapshotDao().getAvailableDates(windowDays + 5)
                .filter { it <= context.tradeDate }
                .take(windowDays)

            if (allDates.size < 2) {
                context.log(nodeId, "跨日聚合: 僅 ${allDates.size} 天可用，數據不足")
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

            // 📤 輸出日誌
            val outputCodes = rankings.map { it.first }
            context.log(nodeId, "📤 $nodeName 輸出: ${rankings.size} 只股票 ${formatTopCodes(outputCodes)}")

            // 🚫 過濾日誌
            val filteredCount = poolCodes.size - rankings.size
            if (filteredCount > 0) {
                context.log(nodeId, "🚫 $nodeName 過濾掉: $filteredCount 只（未入圍 Top$topN）")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = poolCodes.size, outputCount = rankings.size,
                filterCount = filteredCount,
                filterReason = if (filteredCount > 0) "未入圍 Top$topN" else "",
                inputCodes = poolCodes.take(5), outputCodes = outputCodes.take(5)
            )

            context.log(nodeId, "跨日聚合完成: ${allDates.size} 天回溯, " +
                "${poolCodes.size} 只股票, ${rankings.size} 隻入圍" +
                (rankings.take(3).joinToString(prefix = " Top3=", separator = ",") { "${it.first}(${it.second}天)" }))

            CrossDayResult(rankings, allDates.size, context.tradeDate)
        } catch (e: Exception) {
            context.log(nodeId, "跨日聚合失敗: ${e.message}")
            context.recordError(nodeId, "跨日聚合失敗: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = poolCodes.size, outputCount = 0,
                filterCount = poolCodes.size,
                filterReason = "執行失敗: ${e.message}",
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
 * ## 多周期熱門股聚合節點
 *
 * 對多個時間周期（3/5/10/30/50/100天），分別計算累計漲幅 Top3 股票，
 * 去重後合併為一個熱門股集合，擴大中線精選池。
 *
 * 中線獨有：短線只看當日熱門，中線需要多周期驗證趨勢延續性。
 *
 * @property periods 計算的周期天數列表（默認 [3, 5, 10, 30, 50, 100]）
 * @property topNPerPeriod 每個周期取 Top N（默認 3）
 * @property onlyMainBoard 是否只看主板（默認 true）
 */
class MultiPeriodHotNode(
    private val periods: List<Int> = listOf(3, 5, 10, 30, 50, 100),
    private val topNPerPeriod: Int = 3,
    private val onlyMainBoard: Boolean = true
) : BaseNode<Any, MultiPeriodHotResult>("multi_period_hot", "多周期熱門股聚合", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): MultiPeriodHotResult {
        val db = StockDatabase.getInstance(context.androidContext)

        // 📥 輸入日誌（獨立數據源，無直接輸入）
        context.log(nodeId, "📥 $nodeName 輸入: 獨立數據源, 從DB加載 ${periods.size} 個周期數據")

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

            // 📤 輸出日誌
            context.log(nodeId, "📤 $nodeName 輸出: ${hotSet.size} 只股票 ${formatTopCodes(hotSet)}")

            // 🚫 過濾日誌
            if (onlyMainBoard) {
                context.log(nodeId, "🚫 $nodeName 過濾: 已排除非主板股票（前綴 sh51/sh56/sz15/sz16/bj8）")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = periods.size, outputCount = hotSet.size,
                filterCount = if (hotSet.isEmpty()) periods.size else 0,
                filterReason = if (hotSet.isEmpty()) "無熱門股票" else "",
                inputCodes = emptyList(), outputCodes = hotSet.toList().take(5)
            )

            context.log(nodeId, "多周期熱門股: ${successCount}/${periods.size} 周期成功, " +
                "${hotSet.size} 隻熱門股去重")

            MultiPeriodHotResult(hotSet, successCount, periods)
        } catch (e: Exception) {
            context.log(nodeId, "多周期熱門股失敗: ${e.message}")
            context.recordError(nodeId, "多周期熱門股失敗: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = periods.size, outputCount = 0,
                filterCount = periods.size,
                filterReason = "執行失敗: ${e.message}",
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
 * ## 新聞力度計算節點
 *
 * 查詢近 [lookbackDays] 天的活躍新聞因子，計算與信號股票相關新聞的
 * 平均影響力 × 情緒分，生成 0-100 的新聞力度分數。
 *
 * 計算公式：score = avgStrength × (0.5 + avgSentiment × 0.3)
 * 無相關新聞默認 50 分。
 *
 * 中線獨有：短線不計算新聞力度，中線需要新聞面支撐。
 *
 * @property lookbackDays 回溯天數（默認 3）
 */
class NewsStrengthNode(
    private val lookbackDays: Int = 3
) : BaseNode<Any, Int>("news_strength", "新聞力度計算", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): Int {
        // 從 input 或 context 中按需讀取 MergedSignalPool
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> context.getStageOutput<MergedSignalPool>("sector_boost")
                ?: context.getStageOutput<MergedSignalPool>("n_boost")
                ?: context.getStageOutput<MergedSignalPool>("signal_merge")
                ?: context.getStageOutput<MergedSignalPool>("n_merge")
                ?: MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        }
        val db = StockDatabase.getInstance(context.androidContext)

        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: ${pool.totalStocks} 只股票 ${formatTopCodes(pool.stockHits.keys)}")

        return try {
            val fromDate = LocalDate.parse(context.tradeDate)
                .minusDays(lookbackDays.toLong())
                .format(DATE_FMT)

            val newsList = db.newsFactorDao().getActiveByDateRange(fromDate, context.tradeDate)

            if (newsList.isEmpty()) {
                context.log(nodeId, "新聞力度: 近 ${lookbackDays} 天無活躍新聞, 默認 50 分")
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: score=50 (無新聞數據)")
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
                context.log(nodeId, "新聞力度: ${newsList.size} 條新聞中無相關, 默認 40 分")
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: score=40 (無相關新聞)")
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

            // 📤 輸出日誌
            context.log(nodeId, "📤 $nodeName 輸出: score=$score")

            // 🚫 過濾日誌
            val unrelatedNewsCount = newsList.size - related.size
            if (unrelatedNewsCount > 0) {
                context.log(nodeId, "🚫 $nodeName 過濾掉: $unrelatedNewsCount 條無關新聞")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.totalStocks, outputCount = pool.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = pool.stockHits.keys.toList().take(5),
                outputCodes = pool.stockHits.keys.toList().take(5)
            )

            // ═══ 補齊 Hardcode：固化每日新聞熱點 Top3 到 daily_news_hot_picks 表 ═══
            try {
                saveNewsHotPicks(context, related, context.tradeDate)
            } catch (e2: Exception) {
                context.log(nodeId, "新聞熱點固化失敗（不阻塞）: ${e2.message}")
            }

            context.log(nodeId, "新聞力度: ${related.size} 條相關新聞, " +
                "avgImpact=${"%.1f".format(avgStr)}, avgSentiment=${"%.1f".format(avgSent)}, " +
                "score=$score")

            score
        } catch (e: Exception) {
            context.log(nodeId, "新聞力度計算失敗: ${e.message}, 默認 50 分")
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
     * 補齊 Hardcode：按板塊聚合新聞熱點，取 Top3 固化到 daily_news_hot_picks 表。
     *
     * 聚合規則：
     * - 按新聞的 sector 欄位分組
     * - 每個板塊的 hotScore = 該板塊新聞的平均 impactStrength
     * - 按熱度降序取 Top3
     * - relatedStockCodes = 該板塊新聞涉及的股票代碼集合
     */
    private suspend fun saveNewsHotPicks(
        context: PipelineContext,
        relatedNews: List<com.chin.stockanalysis.news.NewsFactorEntity>,
        tradeDate: String
    ) {
        if (relatedNews.isEmpty()) return

        // 按板塊聚合
        val sectorGroups = relatedNews
            .filter { it.sector.isNotEmpty() }
            .groupBy { it.sector }

        if (sectorGroups.isEmpty()) return

        // 計算每個板塊的熱度分數
        val sectorScores = sectorGroups.map { (sector, newsList) ->
            val avgImpact = newsList.map { it.impactStrength }.average().toInt()
            val relatedCodes = newsList.map { it.stockCode }.filter { it.isNotEmpty() }.distinct()
            val topNews = newsList.maxByOrNull { it.impactStrength }
            Triple(sector, avgImpact, relatedCodes to (topNews?.title ?: ""))
        }.sortedByDescending { it.second }

        val db = StockDatabase.getInstance(context.androidContext)
        val hotPickDao = db.dailyNewsHotPickDao()

        // 取 Top3 寫入
        for ((rank, item) in sectorScores.take(3).withIndex()) {
            val (sector, hotScore, pair) = item
            val (relatedCodes, newsTitle) = pair
            val entity = com.chin.stockanalysis.strategy.trade.DailyNewsHotPickEntity(
                newsDate = tradeDate,
                rank = rank + 1,
                sectorName = sector,
                subSectorName = "",  // 子板塊由 StockDataCenter 動態查詢，此處留空
                hotScore = hotScore,
                newsTitle = newsTitle.take(200),
                relatedStockCodes = relatedCodes.joinToString(",")
            )
            hotPickDao.insert(entity)
        }

        context.log(nodeId, "📰 新聞熱點固化: ${minOf(3, sectorScores.size)} 個板塊寫入 daily_news_hot_picks")
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  4. RotationPenaltyNode (ENRICHMENT)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 板塊輪動懲罰節點
 *
 * 統計信號股票所屬板塊的集中度，如果某板塊被超過閾值天數的股票命中，
 * 則施加懲罰分數，防止板塊輪動過快。
 *
 * 規則：某板塊被 >= [thresholdDays] 個信號命中 → 每多一個懲罰 10 分
 * 範圍：0 ~ -100
 *
 * @property thresholdDays 板塊集中度閾值（默認 3）
 * @property penaltyPerExcess 每超出一個的懲罰分數（默認 10）
 */
class RotationPenaltyNode(
    private val thresholdDays: Int = 3,
    private val penaltyPerExcess: Int = 10
) : BaseNode<Any, Int>("rotation_penalty", "板塊輪動懲罰", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: Any): Int {
        // 從 input 或 context 中按需讀取 MergedSignalPool
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> context.getStageOutput<MergedSignalPool>("sector_boost")
                ?: context.getStageOutput<MergedSignalPool>("n_boost")
                ?: context.getStageOutput<MergedSignalPool>("signal_merge")
                ?: context.getStageOutput<MergedSignalPool>("n_merge")
                ?: MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        }
        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: ${pool.totalStocks} 只股票 ${formatTopCodes(pool.stockHits.keys)}")

        return try {
            val sectorCounts = mutableMapOf<String, Int>()

            for (code in pool.stockHits.keys) {
                val sectors = StockDataCenter.getSectorsByStock(code)
                for (sector in sectors) {
                    sectorCounts[sector] = (sectorCounts[sector] ?: 0) + 1
                }
            }

            var penalty = 0
            val penalizedSectors = mutableListOf<String>()
            for ((sector, count) in sectorCounts) {
                if (count >= thresholdDays) {
                    val excess = count - thresholdDays + 1
                    penalty -= excess * penaltyPerExcess
                    penalizedSectors.add("$sector(${count}次)")
                }
            }

            val finalPenalty = penalty.coerceIn(-100, 0)

            context.setStageOutput(nodeId, finalPenalty)

            // 📤 輸出日誌
            context.log(nodeId, "📤 $nodeName 輸出: penalty=$finalPenalty")

            if (finalPenalty < 0) {
                context.log(nodeId, "板塊輪動懲罰: $finalPenalty 分, " +
                    "懲罰板塊: ${penalizedSectors.joinToString()}")
                // 🚫 過濾日誌
                context.log(nodeId, "🚫 $nodeName 懲罰板塊: ${penalizedSectors.size} 個板塊超過閾值($thresholdDays)")
            } else {
                context.log(nodeId, "板塊輪動懲罰: 無懲罰（板塊分散度正常）")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.totalStocks, outputCount = pool.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = pool.stockHits.keys.toList().take(5),
                outputCodes = pool.stockHits.keys.toList().take(5)
            )

            finalPenalty
        } catch (e: Exception) {
            context.log(nodeId, "輪動懲罰計算失敗: ${e.message}, 默認 0")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.totalStocks, outputCount = pool.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = pool.stockHits.keys.toList().take(5),
                outputCodes = pool.stockHits.keys.toList().take(5)
            )
            0
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  5. NewsGuardNode (FILTER)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 新聞攔截 + 技術過濾節點
 *
 * 兩層防護：
 * 1. **新聞攔截**：買入前檢查近 [lookbackDays] 天是否有重大利空新聞
 *    （影響力 >= [impactThreshold] 且情緒 < [sentimentThreshold]）
 * 2. **技術過濾**（4 條規則）：
 *    - 大陰線不抄（跌幅 >= 7%）
 *    - 一字板不跳（漲停一字板）
 *    - 均線空頭不搞（近5日收盤價前3日低於5日均線）
 *    - 頂背離不追（小幅漲但上影線長且連續3日以上陽線）
 *
 * @property lookbackDays 新聞回溯天數（默認 3）
 * @property impactThreshold 影響力閾值（默認 75）
 * @property sentimentThreshold 情緒閾值（默認 -30）
 */
class NewsGuardNode(
    private val lookbackDays: Int = 3,
    private val impactThreshold: Int = 75,
    private val sentimentThreshold: Int = -30
) : BaseNode<Any, NewsGuardResult>("news_guard", "新聞攔截+技術過濾", NodeType.FILTER) {

    override suspend fun execute(context: PipelineContext, input: Any): NewsGuardResult {
        // 從不同上游類型中提取候選股票代碼
        val candidateCodes: Set<String> = when (input) {
            is Set<*> -> input.filterIsInstance<String>().toSet()
            is MergedSignalPool -> input.stockHits.keys
            is List<*> -> input.filterIsInstance<String>().toSet()
            else -> {
                context.log(nodeId, "⚠ 未知輸入類型: ${input::class.simpleName}，嘗試從 context 讀取")
                context.getStageOutput<MergedSignalPool>("smart_money_filter")?.stockHits?.keys ?: emptySet()
            }
        }

        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: ${candidateCodes.size} 只股票 ${formatTopCodes(candidateCodes)}")

        if (candidateCodes.isEmpty()) {
            return NewsGuardResult(emptySet(), emptyMap(), emptySet(), emptySet())
        }

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 1. 新聞攔截
            val fromDate = LocalDate.parse(context.tradeDate)
                .minusDays(lookbackDays.toLong())
                .format(DATE_FMT)

            val newsList = db.newsFactorDao().getActiveByDateRange(fromDate, context.tradeDate)
            val blockedCodes = mutableSetOf<String>()
            val blockedReasons = mutableMapOf<String, String>()

            for (news in newsList) {
                if (news.stockCode.isBlank()) continue
                if (news.stockCode in candidateCodes &&
                    news.impactStrength >= impactThreshold &&
                    news.sentiment < sentimentThreshold) {
                    blockedCodes.add(news.stockCode)
                    blockedReasons[news.stockCode] = "利空攔截: ${news.title.take(40)} " +
                        "(影響力=${news.impactStrength}, 情緒=${news.sentiment})"
                }
            }

            // 2. 技術過濾（需要當日快照數據）
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

            // 📤 輸出日誌
            context.log(nodeId, "📤 $nodeName 輸出: ${passedCodes.size} 只股票 ${formatTopCodes(passedCodes)}")

            // 🚫 過濾日誌
            if (blockedCodes.isNotEmpty()) {
                context.log(nodeId, "🚫 $nodeName 過濾掉: ${blockedCodes.size} 只利空攔截 ${formatTopCodes(blockedCodes)}")
                blockedCodes.forEach { code ->
                    context.log(nodeId, "  攔截: $code → ${blockedReasons[code]}")
                }
            }
            if (technicalFilteredCodes.isNotEmpty()) {
                context.log(nodeId, "🚫 $nodeName 過濾掉: ${technicalFilteredCodes.size} 只技術規則 ${formatTopCodes(technicalFilteredCodes)}")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = candidateCodes.size, outputCount = passedCodes.size,
                filterCount = candidateCodes.size - passedCodes.size,
                filterReason = buildString {
                    if (blockedCodes.isNotEmpty()) append("利空攔截${blockedCodes.size}只 ")
                    if (technicalFilteredCodes.isNotEmpty()) append("技術規則${technicalFilteredCodes.size}只")
                }.trim(),
                inputCodes = candidateCodes.take(5), outputCodes = passedCodes.take(5)
            )

            context.log(nodeId, "新聞攔截: ${blockedCodes.size} 只被攔截, " +
                "技術過濾: ${technicalFilteredCodes.size} 只被過濾, " +
                "最終通過: ${passedCodes.size} 只")

            NewsGuardResult(blockedCodes, blockedReasons, technicalFilteredCodes, passedCodes)
        } catch (e: Exception) {
            context.log(nodeId, "新聞攔截失敗: ${e.message}, 全部通過")
            context.recordError(nodeId, "新聞攔截失敗: ${e.message}")
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
     * 4 條技術過濾規則
     */
    private fun passesTechnicalFilter(
        code: String,
        snapMap: Map<String, DailySnapshotEntity>,
        allSnapshots: List<DailySnapshotEntity>
    ): Boolean {
        val snap = snapMap[code] ?: return true

        // 規則1: 大陰線不抄（跌幅 >= 7%）
        if (snap.changePct <= -7.0) return false

        // 規則2: 一字板不跳（漲停一字板）
        if (snap.changePct >= 9.5 && snap.open >= snap.close * 0.99) return false

        // 規則3: 均線空頭不搞（陰線且跌幅>2%，且上影線明顯 → 拋壓重）
        if (snap.close < snap.open && snap.changePct < -2.0) {
            val upperShadow = snap.high - maxOf(snap.open, snap.close)
            val body = abs(snap.open - snap.close)
            if (body > 0 && upperShadow > body * 0.5) return false
        }

        // 規則4: 頂背離不追（小幅漲但上影線長且連續3日以上陽線）
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
 * ## 自適應參數節點
 *
 * 觸發大盤分析報告 + 市場自適應參數的延遲加載，
 * 並將結果存入 PipelineContext 供後續節點使用。
 *
 * 自適應參數包含：
 * - scoreThreshold: 動態評分閾值（BEARISH強=55/BEARISH弱=60, OSCILLATION=55, BULLISH=50）
 * - maxStockCount: 最大選股數量
 * - shouldGoEmpty: 是否觸發空倉
 *
 * 中線獨有：中線使用自適應參數調整閾值和持倉限制，短線不使用。
 *
 * @property holdingCodes 持倉股票代碼（用於大盤分析）
 */
class AdaptiveParamsNode(
    private val holdingCodes: List<String> = emptyList()
) : BaseNode<Any, MarketAdaptiveStrategy.AdaptiveParams?>("adaptive_params", "大盤分析+自適應參數", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): MarketAdaptiveStrategy.AdaptiveParams? {
        // 從數據庫讀取持倉（若構造函數未傳入）
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
                context.log(nodeId, "讀取持倉失敗: ${e.message}")
                emptyList()
            }
        }

        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: ${effectiveHoldingCodes.size} 只持倉 ${formatTopCodes(effectiveHoldingCodes)}")

        return try {
            // 觸發延遲加載（首次調用時計算，後續直接返回緩存值）
            val report = context.getMarketReport(effectiveHoldingCodes)
            val params = context.getAdaptiveParams()

            if (report != null) {
                context.log(nodeId, "大盤分析: direction=${report.trend.direction}, " +
                    "trend=${report.trend.description}")
            }

            if (params != null) {
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: scoreThreshold=${params.scoreThreshold}, " +
                    "maxCount=${params.maxStockCount}, forceEmpty=${params.forceEmpty}")
                context.log(nodeId, "自適應參數: scoreThreshold=${params.scoreThreshold}, " +
                    "maxCount=${params.maxStockCount}, forceEmpty=${params.forceEmpty}")
            } else {
                context.log(nodeId, "自適應參數計算失敗，使用默認值")
                context.recordError(nodeId, "自適應參數返回 null")
            }

            params
        } catch (e: Exception) {
            context.log(nodeId, "大盤分析失敗: ${e.message}")
            context.recordError(nodeId, "大盤分析失敗: ${e.message}")
            null
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  7. SwapWeakNode (TRADE_ACTION) — 已修改：從 OrderGenerationResult 獲取 newBuyCount
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 騰龍換鳥節點
 *
 * 當持倉數 + 新買入數超過 [maxHoldings] 時，用 AutoSellEngine 評估所有持倉，
 * 優先賣出已觸發止損 / 最虧損的股票騰出倉位。
 *
 * 賣出排序優先級：shouldSell > urgency > profitPct（升序，優先虧損最多）
 *
 * 中線獨有：中線持倉週期長，需要動態換股優化持倉組合。
 *
 * **已修改**：newBuyCount 從上游 GenerateOrdersNode 的輸出中動態獲取，
 * 不再從 XML config 固定讀取。
 *
 * @property maxHoldings 最大持倉數（默認 5）
 * @property strategies 策略列表（用於 AutoSellEngine 評估）
 */
class SwapWeakNode(
    private val maxHoldings: Int = 5,
    private val strategies: List<Strategy> = emptyList()
) : BaseNode<Any, SwapWeakResult>("swap_weak", "騰龍換鳥", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: Any): SwapWeakResult {
        // 從 input 或 context 中按需讀取 OrderGenerationResult
        val orderResult: OrderGenerationResult = when (input) {
            is OrderGenerationResult -> input
            else -> context.getStageOutput<OrderGenerationResult>("generate_orders")
                ?: context.getStageOutput<OrderGenerationResult>("n_orders")
                ?: return SwapWeakResult(0, emptyList(), 0, 0, emptyList())
        }
        // 從上游 GenerateOrdersNode 輸出中動態獲取 newBuyCount
        val newBuyCount = orderResult.orders.size

        // ── 快速跳過：無新買訂單時無需任何 DB 查詢，直接返回 ──
        if (newBuyCount == 0) {
            context.log(nodeId, "$nodeName: 無新買訂單，跳過")
            return SwapWeakResult(0, emptyList(), 0, 0, orderResult.orders)
        }

        // 📥 輸入日誌
        val orderCodes = orderResult.orders.map { "${it.stockCode}(${it.stockName})" }
        context.log(nodeId, "📥 $nodeName 輸入: ${orderResult.orders.size} 個訂單 ${formatTopCodes(orderCodes)}")

        val effectiveStrategies = strategies.ifEmpty {
            context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
        }

        val db = StockDatabase.getInstance(context.androidContext)
        val sellEngine = AutoSellEngine(context.androidContext)

        return try {
            // 按周期統計持倉：只算與本 Pipeline 同周期的持倉，不跨周期累加
            val period = orderTypePeriod(context.config.orderType)
            val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
                .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            var currentCount = holdingOrders.size

            // ── 趨勢轉換檢測：即使持倉未滿，趨勢轉空的持倉也主動賣出換股 ──
            // 讀取每只持倉最近20天K線，判斷是否空頭排列(MA5<MA10<MA20)或近3日創20日新低
            // 長線更保守：僅在極端趨勢反轉（空頭排列+創新低同時滿足）才換股
            val trendReversedCodes = mutableListOf<String>()
            for (order in holdingOrders) {
                try {
                    val snaps = db.dailySnapshotDao().getByCode(order.stockCode, 20)
                    if (snaps.size < 20) continue  // 數據不足，跳過趨勢判斷
                    val klines = snaps.sortedBy { it.date }  // 按 date ASC 排序，takeLast = 最新
                    val closes = klines.map { it.close }
                    val ma5 = closes.takeLast(5).average()
                    val ma10 = closes.takeLast(10).average()
                    val ma20 = closes.takeLast(20).average()
                    val recent3Low = klines.takeLast(3).minOf { it.low }
                    val prior17Low = klines.dropLast(3).minOf { it.low }
                    val bearishAlignment = ma5 < ma10 && ma10 < ma20
                    val newLow = recent3Low < prior17Low

                    // 長線：需要空頭排列 AND 創新低才換股（更保守）
                    // 中線：空頭排列 OR 創新低即換股（原有邏輯）
                    val shouldSwitch = if (period == "long") {
                        bearishAlignment && newLow
                    } else {
                        bearishAlignment || newLow
                    }

                    if (shouldSwitch) {
                        trendReversedCodes.add(order.stockCode)
                        val reason = if (bearishAlignment && newLow) "空頭排列+創新低"
                            else if (bearishAlignment) "空頭排列" else "近3日創新低"
                        context.log(nodeId, "${period}線趨勢轉換: ${order.stockName}($reason)，主動賣出換股")
                    } else if (period == "long" && (bearishAlignment || newLow)) {
                        // 長線單一信號不換股，但記錄建議做T
                        val signal = if (bearishAlignment) "空頭排列" else "近3日創新低"
                        context.log(nodeId, "💡 長線持倉 ${order.stockName} 出現${signal}但未達雙重確認，建議做T降成本而非換股")
                    }
                } catch (_: Exception) {
                    // 單股查詢失敗不影響整體流程
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
                    val soldNames = trendSellDecisions.map { "${it.order.stockName}(趨勢轉空)" }
                    context.log(nodeId, "騰龍換鳥: 趨勢轉換賣出 ${soldNames.joinToString()}")
                    sellEngine.executeSells(trendSellDecisions, context.tradeDate, force = true)
                }
                // 賣出後重新計算持倉數
                currentCount = db.strategyTradeOrderDao().getRecent(500)
                    .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            }

            // ── 騰龍換鳥只在「持倉+新買超出上限」時觸發 ──
            // 持倉+新買 ≤ 上限時倉位足夠，新票直接買入即可，無需賣出現有持倉騰位置。
            if (currentCount + newBuyCount <= maxHoldings) {
                context.log(nodeId, "騰龍換鳥: 持倉 $currentCount + 新買 $newBuyCount ≤ $maxHoldings, 倉位足夠, 無需換股")
                // 💡 中長線建議：已有持倉優先做T，不輕易加新倉
                if (period == "mid" || period == "long") {
                    context.log(nodeId, "💡 $period 持倉建議: 優先對現有持倉做T降低成本，而非頻繁換股")
                }
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: 0 只換股，持倉不變 $currentCount")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = orderResult.orders.size, outputCount = 0,
                    filterCount = orderResult.orders.size,
                    filterReason = "持倉未滿無需換股",
                    inputCodes = orderCodes.take(5), outputCodes = emptyList()
                )
                return SwapWeakResult(0, emptyList(), currentCount, currentCount, orderResult.orders)
            }

            // 沒有新買訂單 → 沒有換股候選，不換
            if (newBuyCount == 0) {
                context.log(nodeId, "騰龍換鳥: 持倉已滿 $currentCount/$maxHoldings 但無新買訂單, 無需換股")
                context.log(nodeId, "📤 $nodeName 輸出: 0 只換股，持倉不變 $currentCount")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = 0, outputCount = 0,
                    filterCount = 0, filterReason = "無新買訂單",
                    inputCodes = emptyList(), outputCodes = emptyList()
                )
                return SwapWeakResult(0, emptyList(), currentCount, currentCount, orderResult.orders)
            }

            // 持倉已滿且有新候選 → 需騰出 newBuyCount 個位置（持倉超滿時一併修剪到上限）
            val needToSell = currentCount + newBuyCount - maxHoldings
            context.log(nodeId, "騰龍換鳥: 持倉已滿 $currentCount/$maxHoldings + 新買 $newBuyCount, " +
                "需騰出 $needToSell 個位置")

            val allDecisions = sellEngine.evaluateAll(
                effectiveStrategies,
                AutoSellEngine.AutoSellConfig(
                    tradeDate = context.tradeDate,
                    // 自適應止損：空頭市場收緊硬止損（如 -8% → -3%），讓虧損票在換股排序中優先被賣
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

            // ── 只換「更值得買」的票：新票最高評分須高於待賣邊界持倉的買入評分 ──
            // 中長線換股門檻更高：除非新票明顯更優（中線+20%，長線+50%），否則優先做T不換股
            // 短線維持原始邏輯（只要新票分數 > 持倉分數即可換）
            val bestNewScore = orderResult.orders.maxOfOrNull { it.scoreAtBuy } ?: 0
            val marginalHoldingScore = rankedForSale.lastOrNull()?.order?.scoreAtBuy ?: Int.MAX_VALUE
            val swapThreshold = when (period) {
                "long" -> (marginalHoldingScore * 1.5).toInt()   // 長線：新票需比持倉高50%才換
                "mid" -> (marginalHoldingScore * 1.2).toInt()    // 中線：新票需比持倉高20%才換
                else -> marginalHoldingScore                      // 短線：只要更高即可
            }
            if (bestNewScore <= swapThreshold) {
                context.log(nodeId, "騰龍換鳥: 新票最高分 $bestNewScore ≤ ${period}換股門檻 $swapThreshold " +
                    "(持倉分 $marginalHoldingScore, 門檻倍數 ${if(period=="long") "1.5" else if(period=="mid") "1.2" else "1.0"}), " +
                    "新票不夠優, 不換股 → 建議對現有持倉做T")
                context.log(nodeId, "📤 $nodeName 輸出: 0 只換股（新票不優於持倉，建議做T）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = orderResult.orders.size, outputCount = 0,
                    filterCount = orderResult.orders.size,
                    filterReason = "新票不夠優(${period}門檻未達)，建議做T",
                    inputCodes = orderCodes.take(5), outputCodes = emptyList()
                )
                return SwapWeakResult(0, emptyList(), currentCount, currentCount, orderResult.orders)
            }

            if (rankedForSale.isNotEmpty()) {
                val soldNames = rankedForSale.map { "${it.order.stockName}(${it.reason})" }
                context.log(nodeId, "騰龍換鳥: 賣出 ${soldNames.joinToString()}")

                // 🚫 過濾日誌
                context.log(nodeId, "🚫 $nodeName 賣出: ${rankedForSale.size} 只 ${formatTopCodes(soldNames)}")

                // 騰龍換鳥為強制換倉：被選中的股票不論 shouldSell 為何都必須賣出
                sellEngine.executeSells(rankedForSale, context.tradeDate, force = true)

                val afterCount = db.strategyTradeOrderDao().getRecent(500)
                    .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }

                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: ${rankedForSale.size} 只換股，持倉 $currentCount → $afterCount")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = orderResult.orders.size, outputCount = rankedForSale.size,
                    filterCount = if (rankedForSale.isEmpty()) orderResult.orders.size else 0,
                    filterReason = if (rankedForSale.isEmpty()) "無需換股" else "",
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
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: 0 只換股，無需賣出")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = orderResult.orders.size, outputCount = 0,
                    filterCount = orderResult.orders.size,
                    filterReason = "無需換股",
                    inputCodes = orderCodes.take(5), outputCodes = emptyList()
                )
                SwapWeakResult(0, emptyList(), currentCount, currentCount, orderResult.orders)
            }
        } catch (e: Exception) {
            context.log(nodeId, "騰龍換鳥失敗: ${e.message}")
            context.recordError(nodeId, "騰龍換鳥失敗: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = orderResult.orders.size, outputCount = 0,
                filterCount = orderResult.orders.size,
                filterReason = "執行失敗: ${e.message}",
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
 * ## 熱度計算節點
 *
 * 對股票池中的每隻股票計算 5 維熱度分數（0-100）。
 * 維度：交易量能(30) + 資金動向(20) + 板塊歷史熱度(20) + 價格位置(15) + 概念壁壘(15)
 *
 * 原始流程對應：SimulationTradeEngine.calculateHeatScoresBatch()
 *
 * 計算邏輯：
 * 1. 從 DB 獲取當日快照，計算交易量能得分（量比、換手率）
 * 2. 從 SmartMoneyCache 獲取主力資金評分
 * 3. 從 SectorDailyRecord 獲取板塊歷史熱度
 * 4. 從快照計算價格位置得分（距均線距離、漲幅位置）
 * 5. 返回 Map<String, Int>（code → score）
 */
class HeatScoreNode : BaseNode<Any, Map<String, Int>>("heat_score", "熱度計算", NodeType.DATA_TRANSFORM) {

    override suspend fun execute(context: PipelineContext, input: Any): Map<String, Int> {
        // 從 input 或 context 中按需讀取 StockPool
        val pool: StockPool = when (input) {
            is StockPool -> input
            else -> context.getStageOutput<StockPool>("stock_pool")
                ?: context.getStageOutput<StockPool>("n_pool")
                ?: context.getStageOutput<StockPool>("candidate_pool")
                ?: context.getStageOutput<StockPool>("n_cand")
                ?: StockPool(emptyList(), "empty")
        }
        // 📥 輸入日誌
        val inputCodes = pool.stocks.map { it.code }
        context.log(nodeId, "📥 $nodeName 輸入: ${pool.stocks.size} 只股票 ${formatTopCodes(inputCodes)}")

        if (pool.isEmpty) {
            context.log(nodeId, "股票池為空，跳過熱度計算")
            return emptyMap()
        }

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 1. 獲取當日快照用於量能計算
            val snapshots = try {
                db.dailySnapshotDao().getByDate(context.tradeDate)
            } catch (_: Exception) { emptyList() }
            val snapMap = snapshots.associateBy { it.code }

            // 2. 獲取板塊歷史熱度
            val sectorDao = db.sectorDailyRecordDao()
            val recentHotSectors = try {
                sectorDao.getTopHotSectors(10)
            } catch (_: Exception) { emptyList() }
            val hotSectorCodes = recentHotSectors.map { it.sector_code }.toSet()

            // 3. 計算每隻股票的熱度分數
            val heatScores = mutableMapOf<String, Int>()

            for (stock in pool.stocks) {
                val code = stock.code
                val snap = snapMap[code]
                if (snap == null) {
                    heatScores[code] = 30 // 無快照數據，給低分
                    continue
                }

                // 維度1: 交易量能（30分）— 基於量比和漲跌幅
                val volumeScore = calculateVolumeScore(snap)

                // 維度2: 資金動向（20分）— 基於 SmartMoneyCache
                val moneyScore = SmartMoneyCache.getScore(code).combined.roundToInt().coerceIn(0, 100)

                // 維度3: 板塊歷史熱度（20分）— 所屬板塊是否在熱門板塊中
                val stockSectors = StockDataCenter.getSectorsByStock(code)
                val sectorHeatScore = if (stockSectors.any { s ->
                    hotSectorCodes.any { h -> s.contains(h) || h.contains(s) }
                }) 20 else 5

                // 維度4: 價格位置（15分）— 距5日均線位置
                val recentSnaps = snapshots.filter { it.code == code }
                    .sortedByDescending { it.date }
                    .take(5)
                val priceScore = calculatePricePositionScore(snap, recentSnaps)

                // 維度5: 概念壁壘（15分）— 板塊數量越多說明概念越豐富
                val conceptScore = (stockSectors.size * 3).coerceAtMost(15)

                val totalScore = (volumeScore * 0.30 + moneyScore * 0.20 +
                    sectorHeatScore * 0.20 + priceScore * 0.15 + conceptScore * 0.15)
                    .roundToInt().coerceIn(0, 100)

                heatScores[code] = totalScore
            }

            // 按分數降序排列的 Top10
            val top10 = heatScores.entries
                .sortedByDescending { it.value }
                .take(10)
                .map { "${it.key}=${it.value}" }

            context.setStageOutput(nodeId, heatScores)

            // 📤 輸出日誌
            val outputCodes = heatScores.keys
            context.log(nodeId, "📤 $nodeName 輸出: ${heatScores.size} 只股票熱度分數 Top10: [${top10.joinToString(", ")}]")

            // 🚫 過濾日誌
            val lowScoreCount = heatScores.count { it.value < 40 }
            if (lowScoreCount > 0) {
                context.log(nodeId, "🚫 $nodeName 低熱度(<40分): $lowScoreCount 只")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.stocks.size, outputCount = heatScores.size,
                filterCount = lowScoreCount,
                filterReason = if (lowScoreCount > 0) "低熱度(<40分)" else "",
                inputCodes = inputCodes.take(5), outputCodes = heatScores.keys.toList().take(5)
            )

            context.log(nodeId, "熱度計算完成: ${heatScores.size} 只股票, Top10: ${top10.joinToString(", ")}")

            heatScores
        } catch (e: Exception) {
            context.log(nodeId, "熱度計算失敗: ${e.message}")
            context.recordError(nodeId, "熱度計算失敗: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.stocks.size, outputCount = 0,
                filterCount = pool.stocks.size,
                filterReason = "執行失敗: ${e.message}",
                inputCodes = inputCodes.take(5), outputCodes = emptyList()
            )
            emptyMap()
        }
    }

    /**
     * 計算交易量能得分（0-100）
     * 基於漲跌幅和量比的組合
     */
    private fun calculateVolumeScore(snap: DailySnapshotEntity): Int {
        var score = 50
        // 漲幅貢獻
        when {
            snap.changePct >= 5.0 -> score += 30
            snap.changePct >= 3.0 -> score += 20
            snap.changePct >= 1.0 -> score += 10
            snap.changePct <= -5.0 -> score -= 25
            snap.changePct <= -3.0 -> score -= 15
        }
        // 量能貢獻（量大說明關注度高）
        if (snap.amount > 5e8) score += 10
        if (snap.amount > 1e9) score += 10
        return score.coerceIn(0, 100)
    }

    /**
     * 計算價格位置得分（0-100）
     * 基於收盤價與5日均線的距離
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
            deviation > 10 -> 30  // 遠高於均線，可能過熱
            deviation > 3 -> 70   // 略高於均線，強勢
            deviation > -3 -> 90  // 接近均線，位置適中
            deviation > -10 -> 40 // 低於均線，偏弱
            else -> 20            // 遠低於均線
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  9. GenerateOrdersNode (TRADE_ACTION)
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 買入訂單生成節點
 *
 * 根據 AI 精選結果 + 自適應參數生成買入訂單。
 * 過濾鏈：主力資金評分 → 空倉觸發 → 數量限制 → ETF 過濾 → 排序截取
 *
 * 原始流程對應：SimulationTradeEngine.generateBuyOrders()
 *
 * @property maxHoldings 最大持倉數（默認 6）
 * @property orderType 訂單類型（默認 "MidTermQuant"）
 */
class GenerateOrdersNode(
    private val maxHoldings: Int = 6,
    private val orderType: String = "MidTermQuant"
) : BaseNode<Any, OrderGenerationResult>("generate_orders", "買入訂單生成", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: Any): OrderGenerationResult {
        // 兼容多種上游：AIPrediction / MergedSignalPool / NewsGuardResult
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
                // 從 context 讀取主力過濾後的信號池，按 passedCodes 過濾
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
                context.log(nodeId, "⚠ 未知輸入類型: ${input::class.simpleName}，無法生成訂單")
                return OrderGenerationResult(emptyList(), 0, false)
            }
        }

        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: ${topPicks.size} 只候選 ${formatTopCodes(topPicks.map { "${it.stockCode}(${it.stockName})" })}")

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 獲取自適應參數
            val params = context.getAdaptiveParams()
            val scoreThreshold = params?.scoreThreshold ?: 50
            val maxStockCount = params?.maxStockCount ?: maxHoldings
            val forceEmpty = params?.forceEmpty ?: false

            // 空倉觸發
            if (forceEmpty) {
                context.log(nodeId, "自適應參數觸發空倉，不生成訂單")
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: 0 個訂單（空倉觸發）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = topPicks.size, outputCount = 0,
                    filterCount = topPicks.size,
                    filterReason = "空倉觸發",
                    inputCodes = topPicks.map { it.stockCode }.take(5),
                    outputCodes = emptyList()
                )
                return OrderGenerationResult(emptyList(), topPicks.size, true)
            }

            // 交易時段檢查：非交易時段仍記錄信號，但標記為預信號（下一交易日可執行）
            val now = java.time.LocalDateTime.now()
            val hourMin = now.hour * 100 + now.minute
            val isTradingHours = (hourMin in 930..1130) || (hourMin in 1300..1500)
            val isTradingDay = now.dayOfWeek in java.time.DayOfWeek.MONDAY..java.time.DayOfWeek.FRIDAY
            val isPreSignal = !isTradingHours || !isTradingDay
            if (isPreSignal) {
                context.log(nodeId, "📡 非交易時段(${now.hour}:${"%02d".format(now.minute)})，記錄預信號供下一交易日參考")
            }

            // 獲取當前持倉（按周期統計：只算與本 Pipeline 同周期的持倉，不跨周期累加）
            val period = orderTypePeriod(orderType)
            val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
                .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            val holdingCodes = holdingOrders.map { it.stockCode }.toSet()
            // 僅「同日買入」的持倉才過濾（避免同日重複下單）；
            // 非同日持倉允許通過 → 下游 PositionMergeNode 執行加倉（追加加權平均）
            val todayHoldingCodes = holdingOrders
                .filter { it.tradeDate == context.tradeDate }
                .map { it.stockCode }.toSet()
            val availableSlots = maxHoldings - holdingCodes.size

            if (availableSlots <= 0) {
                context.log(nodeId, "持倉已滿 ${holdingCodes.size}/$maxHoldings（$period 周期），仍輸出候選供騰龍換鳥評估")
            }

            // 過濾鏈
            var filteredCount = 0
            val filteredReasons = mutableListOf<String>()
            val candidates = mutableListOf<AIPredictionEngine.AIPick>()

            for (pick in topPicks) {
                // 1. 評分閾值過濾
                if (pick.compositeScore < scoreThreshold) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 評分${pick.compositeScore}<$scoreThreshold")
                    continue
                }

                // 2. 同日已持有過濾（僅過濾同日重複，非同日允許加倉）
                if (pick.stockCode in todayHoldingCodes) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 今日已買入")
                    continue
                }

                // 3. ETF 過濾（排除 ETF）
                if (pick.stockCode.startsWith("sh51") || pick.stockCode.startsWith("sz15")) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: ETF排除")
                    continue
                }

                // 3.5 指數過濾（排除大盤指數：sh000xxx / sz399xxx 不可交易）
                if (pick.stockCode.startsWith("sh000") || pick.stockCode.startsWith("sz399")) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 指數排除")
                    continue
                }

                // 4. 主力資金評分過濾（低於 30 分的過濾）
                val moneyScore = SmartMoneyCache.getScore(pick.stockCode).combined
                if (moneyScore < 30) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 主力資金${moneyScore.roundToInt()}<30")
                    continue
                }

                candidates.add(pick)
            }

            // ── 寧缺勿濫：不放鬆選股條件 ──
            // 如果所有候選都被過濾，直接空倉，不做 Fallback
            // 避免買入不符合條件的「魚尾」股票
            if (candidates.isEmpty()) {
                context.log(nodeId, "⚠ $nodeName 無候選通過嚴格條件，寧缺勿濫，空倉觀望")
                if (filteredReasons.isNotEmpty()) {
                    context.log(nodeId, "📋 過濾詳情: ${filteredReasons.joinToString(" | ")}")
                }
                context.log(nodeId, "📤 $nodeName 輸出: 0 個訂單（無候選通過）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = topPicks.size, outputCount = 0,
                    filterCount = topPicks.size,
                    filterReason = "無候選通過嚴格條件",
                    inputCodes = topPicks.map { it.stockCode }.take(5),
                    outputCodes = emptyList()
                )
                return OrderGenerationResult(emptyList(), topPicks.size, true)
            }

            // ── 大盤防守：空倉防禦 ──
            // 與 Hardcode 路徑（SimulationTradeEngine.shouldForceEmpty）一致：
            // 大盤明確空頭且高分候選不足 2 只時，一股不買。
            // 僅中線/長線生效：順勢交易、持倉週期長，熊市空頭應空倉保本。
            // 超短/短線屬均值回歸，賺的是超跌反彈/V型反包/情緒脈衝——恰恰出現在殺最凶時，
            // 不擋空倉，改靠 maxStockCount 限倉 + 嚴止損 + T+1 紀律控風險。
            val marketReport = context.getMarketReport()
            val isBearishEmpty = (period == "mid" || period == "long") && marketReport != null &&
                MarketAdaptiveStrategy.shouldForceEmpty(marketReport, candidates.size)

            // ── 長線嚴選例外：熊市不輕易空倉，滿足嚴格條件仍可買入 ──
            // 條件：均線粘合向上 + 三日不新低 + 歷史低位 + PE低 + 周期活躍度高 + 買在冰點
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

                        // 1. 均線粘合向上：MA5 > MA10 > MA20，且 MA5/MA10 差距 < 3%
                        val maConvergedUp = ma5 > ma10 && ma10 > ma20 &&
                            (ma5 - ma10) / ma10 < 0.03
                        if (!maConvergedUp) continue

                        // 2. 三日不新低：近3日 low 均 >= 第4日 low
                        val recent3Lows = snaps.takeLast(3).map { it.low }
                        val priorLow = snaps.dropLast(3).minOf { it.low }
                        val threeDayNoNewLow = recent3Lows.all { it >= priorLow }
                        if (!threeDayNoNewLow) continue

                        // 3. 歷史低位：close 在 60日區間的下20%
                        val positionPct = if (range60 > 0) (latest.close - low60) / range60 else 0.5
                        val isHistoricalLow = positionPct < 0.25
                        if (!isHistoricalLow) continue

                        // 4. PE低（無泡沫）：PE > 0 且 < 30，或 PE < 行業平均
                        val pe = latest.pe
                        val peOk = pe > 0 && pe < 30.0
                        if (!peOk) continue

                        // 5. 歷史活躍度（周期性漲高落底）：60日內有至少3次 >3% 的漲跌
                        val volatileDays = snaps.takeLast(60).count { Math.abs(it.close - it.open) / it.open > 0.03 }
                        val isCyclicallyActive = volatileDays >= 3
                        if (!isCyclicallyActive) continue

                        // 6. 買在冰點（不要太多利好）：低換手率 + 低成交量
                        val avgVol = snaps.takeLast(20).map { it.volume.toDouble() }.average()
                        val volRatio = if (avgVol > 0) latest.volume.toDouble() / avgVol else 1.0
                        val lowAttention = latest.turnoverRate < 2.0 && volRatio < 1.0

                        // 綜合評分：信號強度 + 低位加分 + 冰點加分
                        var totalScore = pick.compositeScore.toDouble()
                        totalScore += (1.0 - positionPct) * 20  // 越低位分越高
                        if (lowAttention) totalScore += 10  // 冰點加分
                        if (pe < 15) totalScore += 5  // 超低PE加分

                        strictPicks.add(StrictLongPick(pick, totalScore))
                    } catch (_: Exception) {}
                }

                strictPicks.sortByDescending { it.score }
                val longTermOrders = strictPicks.take(2).map { sp ->
                    val snap = strictSnapMap[sp.pick.stockCode]
                    val buyPrice = snap?.close ?: 0.0
                    context.log(nodeId, "🎯 長線嚴選: ${sp.pick.stockName}(${sp.pick.stockCode}) " +
                        "PE=${"%.1f".format(snap?.pe ?: 0.0)} 評分=${"%.0f".format(sp.score)} " +
                        "均線粘合✓ 三日不新低✓ 歷史低位✓ 低PE✓ 冰點埋伏")
                    TradeOrder(
                        stockCode = sp.pick.stockCode,
                        stockName = sp.pick.stockName,
                        strategyId = "long_strict_${context.tradeDate}",
                        tradeDate = context.tradeDate,
                        buyPrice = buyPrice,
                        quantity = 100,
                        reason = "長線嚴選: 均線粘合向上+三日不新低+歷史低位+低PE+冰點 " +
                            "score=${"%.0f".format(sp.score)} ${sp.pick.reason}",
                        scoreAtBuy = sp.pick.compositeScore,
                        orderType = orderType
                    )
                }

                if (longTermOrders.isNotEmpty()) {
                    context.log(nodeId, "🎯 $nodeName 大盤雖空頭，但發現 ${longTermOrders.size} 只" +
                        "符合長線嚴選條件 → 允許買入（熊市逆勢嚴選）")
                    context.setStageOutput(nodeId, longTermOrders)
                    context.recordStockFlow(
                        nodeId = nodeId, nodeName = nodeName,
                        inputCount = topPicks.size, outputCount = longTermOrders.size,
                        filterCount = topPicks.size - longTermOrders.size,
                        filterReason = "長線嚴選(熊市逆勢)",
                        inputCodes = topPicks.map { it.stockCode }.take(5),
                        outputCodes = longTermOrders.map { it.stockCode }
                    )
                    return OrderGenerationResult(longTermOrders, topPicks.size - longTermOrders.size, false)
                }
                // 若無符合長線嚴選的候選，繼續走下面的普通熊市打底倉邏輯
            }

            if (isBearishEmpty) {

                // ── 例外：防守個股歷史低位企穩 → 允許打底倉（2手=200股）──
                // 熊市中如果防守板塊個股（銀行/保險/電力/高速公路等）處於歷史低位，
                // 且出現三日不新低/企穩/均線趨勢向上信號，可以小倉位打底倉
                // 條件4：高股息優先 — 低PB + 防禦板塊 + 大市值 → 排序靠前
                data class BasePickCandidate(
                    val pick: AIPredictionEngine.AIPick,
                    val buyPrice: Double,
                    val stabilizingSignal: String,
                    val isDefensive: Boolean,
                    val dividendScore: Double  // 高股息代理評分
                )
                val baseCandidates = mutableListOf<BasePickCandidate>()
                val defensiveSectors = setOf("銀行", "保險", "電力", "高速公路", "煤炭", "石油",
                    "電信", "水務", "燃氣", "鐵路", "港口", "機場", "證券")
                val bearSnapshots = try {
                    db.dailySnapshotDao().getByDate(context.tradeDate)
                } catch (_: Exception) { emptyList() }
                val bearSnapMap = bearSnapshots.associateBy { it.code }

                for (pick in topPicks.take(10)) {  // 只看前10名候選
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

                        // 條件1：歷史低位（當前價在60日高點的70%以下，或低於MA20的5%以下）
                        val isHistoricalLow = latest.close < high60 * 0.75 || latest.close < ma20 * 0.95
                        if (!isHistoricalLow) continue

                        // 條件2：企穩信號（滿足任一）
                        // a) 三日不新低：近3日最低點 ≥ 前17日最低點
                        val threeDayNoNewLow = recent3Lows.minOrNull()!! >= priorLow
                        // b) 均線趨勢向上：MA5 > MA10（短期均線拐頭）
                        val maTurningUp = ma5 > ma10
                        // c) 收盤站上MA5（企穩跡象）
                        val aboveMa5 = latest.close > ma5
                        val stabilizing = threeDayNoNewLow || maTurningUp || aboveMa5
                        if (!stabilizing) continue

                        // 條件3：防守板塊（從個股名稱或候選信息判斷）
                        val stockName = pick.stockName
                        val isDefensive = defensiveSectors.any { s -> stockName.contains(s) }
                        // 非嚴格限制：歷史低位企穩的個股也允許，不僅限防守板塊
                        if (!isDefensive && !isHistoricalLow) continue

                        val stabilizingSignal = when {
                            threeDayNoNewLow -> "三日不新低"
                            maTurningUp -> "均線向上"
                            else -> "站上MA5"
                        }

                        // 條件4：高股息代理評分（無真實股息率，用低PB+防禦板塊+大市值近似）
                        // PB越低分越高（PB<1.0=滿分40，PB<1.5=30，否則0）
                        val pb = latest.pb
                        val pbScore = when {
                            pb > 0 && pb < 1.0 -> 40.0
                            pb > 0 && pb < 1.5 -> 30.0
                            else -> 0.0
                        }
                        // 防禦板塊加25分（銀行/保險/電力等傳統高息板塊）
                        val sectorScore = if (isDefensive) 25.0 else 0.0
                        // 大市值加20分（市值>500億，大盤股通常派息穩定）
                        val capScore = if (latest.marketCap > 500_0000_0000.0) 20.0 else 0.0
                        // ROE加15分（盈利能力強才有能力派息）
                        val roeScore = if (latest.roeTTM > 10.0) 15.0 else 0.0
                        val dividendScore = pbScore + sectorScore + capScore + roeScore

                        val buyPrice = bearSnapMap[pick.stockCode]?.close ?: latest.close
                        baseCandidates.add(BasePickCandidate(pick, buyPrice, stabilizingSignal, isDefensive, dividendScore))

                    } catch (_: Exception) {}
                }

                // 按高股息評分降序排序，高股息優先買入
                baseCandidates.sortByDescending { it.dividendScore }
                val basePositionOrders = baseCandidates.take(2).mapIndexed { _, c ->
                    val isHighDiv = c.dividendScore >= 50
                    val dividendTag = if (isHighDiv) "🔶高股息" else ""
                    context.log(nodeId, "🛡💪 熊市打底倉: ${c.pick.stockName}(${c.pick.stockCode}) " +
                        "價=${"%.2f".format(c.buyPrice)} 歷史低位+企穩(${c.stabilizingSignal})" +
                        (if (isHighDiv) " $dividendTag(評分${c.dividendScore})" else ""))

                    TradeOrder(
                        stockCode = c.pick.stockCode,
                        stockName = c.pick.stockName,
                        strategyId = "ai_${period}term_${context.tradeDate}",
                        tradeDate = context.tradeDate,
                        buyPrice = c.buyPrice,
                        quantity = 200, // 打底倉2手
                        reason = "熊市防守打底倉: 歷史低位+企穩(${c.stabilizingSignal})" +
                            (if (isHighDiv) " +高股息(評分${c.dividendScore})" else "") +
                            " score=${c.pick.compositeScore} ${c.pick.reason}",
                        scoreAtBuy = c.pick.compositeScore,
                        orderType = orderType
                    )
                }

                if (basePositionOrders.isNotEmpty()) {
                    context.log(nodeId, "🛡 $nodeName 大盤防守空倉，但發現 ${basePositionOrders.size} 只" +
                        "歷史低位企穩個股 → 打底倉（2手/只）")
                    context.setStageOutput(nodeId, basePositionOrders)
                    context.recordStockFlow(
                        nodeId = nodeId, nodeName = nodeName,
                        inputCount = topPicks.size, outputCount = basePositionOrders.size,
                        filterCount = topPicks.size - basePositionOrders.size,
                        filterReason = "大盤防守空倉(打底倉例外)",
                        inputCodes = topPicks.map { it.stockCode }.take(5),
                        outputCodes = basePositionOrders.map { it.stockCode }
                    )
                    return OrderGenerationResult(basePositionOrders, topPicks.size - basePositionOrders.size, false)
                } else {
                    context.log(nodeId, "🛡 $nodeName 大盤防守: BEARISH(強度${marketReport?.trend?.strength}) + " +
                        "高分候選僅 ${candidates.size} 只(<2) → 空倉觀望")
                    context.log(nodeId, "📤 $nodeName 輸出: 0 個訂單（空倉防禦）")
                    context.recordStockFlow(
                        nodeId = nodeId, nodeName = nodeName,
                        inputCount = topPicks.size, outputCount = 0,
                        filterCount = topPicks.size,
                        filterReason = "大盤防守空倉",
                        inputCodes = topPicks.map { it.stockCode }.take(5),
                        outputCodes = emptyList()
                    )
                    return OrderGenerationResult(emptyList(), topPicks.size, true)
                }
            }

            // 數量限制（自適應最大買入數；實際入庫由 position_merge 按可用倉位裁切）
            // 限倉規則：持倉 >= 3 時，每次最多新增 1 只（騰籠換鳥由下游 SwapWeakNode 處理）
            val holdingCount = holdingCodes.size
            val effectiveCap = if (holdingCount >= 3) {
                context.log(nodeId, "持倉 $holdingCount 只(>=3)，本次最多新增 1 只")
                1
            } else {
                maxStockCount
            }
            val buyCap = minOf(effectiveCap, maxOf(availableSlots, if (holdingCount >= 3) 1 else 0))
            val finalCandidates = candidates
                .sortedByDescending { it.compositeScore }
                .take(buyCap)

            // 生成訂單
            val snapshots = try {
                db.dailySnapshotDao().getByDate(context.tradeDate)
            } catch (_: Exception) { emptyList() }
            val snapMap = snapshots.associateBy { it.code }

            // 預先補全候選股中缺失的名稱（統一調用 StockNameResolver）
            val blankNameCodes = finalCandidates.filter { it.stockName.isBlank() }.map { it.stockCode }.distinct()
            val resolvedNames = if (blankNameCodes.isNotEmpty()) {
                com.chin.stockanalysis.stock.database.StockNameResolver.resolveBatch(context.androidContext, blankNameCodes)
            } else emptyMap()

            val orders = finalCandidates.mapIndexed { index, pick ->
                val snap = snapMap[pick.stockCode]
                val buyPrice = snap?.close ?: 0.0
                val resolvedName = if (pick.stockName.isNotBlank()) pick.stockName
                    else resolvedNames[pick.stockCode] ?: pick.stockName
                val preSignalTag = if (isPreSignal) " [預信號]" else ""
                TradeOrder(
                    stockCode = pick.stockCode,
                    stockName = resolvedName,
                    strategyId = "ai_midterm_${context.tradeDate}",
                    tradeDate = context.tradeDate,
                    buyPrice = buyPrice,
                    quantity = 100, // 默認一手
                    reason = "AI精選 rank=${pick.rank} score=${pick.compositeScore} ${pick.reason}$preSignalTag",
                    scoreAtBuy = pick.compositeScore,
                    orderType = orderType
                )
            }

            context.setStageOutput(nodeId, orders)

            // 📤 輸出日誌
            val orderCodes = orders.map { "${it.stockCode}(${it.stockName})" }
            context.log(nodeId, "📤 $nodeName 輸出: ${orders.size} 個訂單 ${formatTopCodes(orderCodes)}")

            // 🚫 過濾日誌
            if (filteredCount > 0) {
                val filteredCodeList = filteredReasons.take(5)
                context.log(nodeId, "🚫 $nodeName 過濾掉: $filteredCount 只 ${filteredCodeList.joinToString(", ")}")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = topPicks.size, outputCount = orders.size,
                filterCount = topPicks.size - orders.size,
                filterReason = buildString {
                    if (filteredCount > 0) append("同日重複/價格異常${filteredCount}只")
                }.trim(),
                inputCodes = topPicks.map { it.stockCode }.take(5),
                outputCodes = orders.map { it.stockCode }.take(5)
            )

            context.log(nodeId, "買入訂單生成: ${topPicks.size} 只AI精選 → 過濾 $filteredCount → 最終 ${orders.size} 個訂單")

            OrderGenerationResult(orders, filteredCount, false)
        } catch (e: Exception) {
            context.log(nodeId, "買入訂單生成失敗: ${e.message}")
            context.recordError(nodeId, "買入訂單生成失敗: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = topPicks.size, outputCount = 0,
                filterCount = topPicks.size,
                filterReason = "執行失敗: ${e.message}",
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
 * ## 持倉合併節點
 *
 * 將新買入訂單與現有持倉合併：已持有則追加加權平均，新持倉則插入 PENDING 訂單。
 *
 * 原始流程對應：SimulationTradeEngine.runTradeSession() Step 11
 */
class PositionMergeNode : BaseNode<Any, PositionMergeResult>("position_merge", "持倉合併", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, rawInput: Any): PositionMergeResult {
        // 兼容兩種上游輸入：
        // - SwapWeakResult（經騰龍換鳥 n_swap → n_merge_pos）：直接從 orders 字段提取
        // - OrderGenerationResult（直連 n_orders → n_merge_pos）：直接使用
        // - 其他類型（如輔助節點容錯回退）：從 context 獲取 n_orders 的輸出
        val orders: List<TradeOrder> = when (rawInput) {
            is SwapWeakResult -> rawInput.orders
            is OrderGenerationResult -> rawInput.orders
            else -> context.getStageOutput<OrderGenerationResult>("n_orders")?.orders
                ?: emptyList()
        }
        // 📥 輸入日誌
        val orderCodes = orders.map { "${it.stockCode}(${it.stockName})" }
        context.log(nodeId, "📥 $nodeName 輸入: ${orders.size} 個訂單 ${formatTopCodes(orderCodes)}")

        if (orders.isEmpty()) {
            val db = StockDatabase.getInstance(context.androidContext)
            val period = orderTypePeriod(context.config.orderType)
            val currentTotal = db.strategyTradeOrderDao().getRecent(500)
                .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            // 📤 輸出日誌
            context.log(nodeId, "📤 $nodeName 輸出: 0 新增, $currentTotal 總持倉（$period 周期）")
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
            // 獲取當前持倉（按周期統計：只算與本 Pipeline 同周期的持倉，不跨周期累加）
            val period = orderTypePeriod(context.config.orderType)
            val existingOrders = db.strategyTradeOrderDao().getRecent(500)
                .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            val existingMap = existingOrders.associateBy { it.stockCode }

            // 容量安全網：新持倉插入不得超過可用倉位（swap_weak 已騰位，此為兜底）
            val maxHoldings = context.config.maxHoldings
            val availableSlots = maxHoldings - existingOrders.size

            var newCount = 0
            val updatedCodes = mutableListOf<String>()
            val entitiesToInsert = mutableListOf<StrategyTradeOrderEntity>()

            for (order in orders) {
                val existing = existingMap[order.stockCode]
                if (existing != null) {
                    // 已持有：追加加權平均
                    val totalQuantity = existing.quantity + order.quantity
                    val avgPrice = (existing.buyPrice * existing.quantity + order.buyPrice * order.quantity) /
                        totalQuantity.toDouble()
                    db.strategyTradeOrderDao().updateQuantityAndPrice(existing.id, totalQuantity, avgPrice)
                    updatedCodes.add("${order.stockCode}(追加${order.quantity}股,均價${"%.2f".format(avgPrice)})")
                } else if (newCount < availableSlots) {
                    // 新持倉：插入 BUYING 訂單（與 Hardcode 路徑一致）
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
                    // 超出可用倉位，跳過（安全網）
                    context.log(nodeId, "⚠ ${order.stockCode} 跳過：持倉已達上限 $maxHoldings（$period 周期）")
                }
            }

            // 批量插入新訂單
            if (entitiesToInsert.isNotEmpty()) {
                db.strategyTradeOrderDao().insertAll(entitiesToInsert)
            }

            val totalHoldings = db.strategyTradeOrderDao().getRecent(500)
                .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }

            context.setStageOutput(nodeId, PositionMergeResult(newCount, updatedCodes, totalHoldings))

            // 📤 輸出日誌
            context.log(nodeId, "📤 $nodeName 輸出: 新增 $newCount 只，更新 ${updatedCodes.size} 只，總持倉 $totalHoldings")

            // 🚫 過濾日誌
            if (updatedCodes.isNotEmpty()) {
                context.log(nodeId, "  追加更新: ${updatedCodes.joinToString(", ")}")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = orders.size, outputCount = newCount,
                filterCount = orders.size - newCount,
                filterReason = if (orders.size - newCount > 0) "重複持倉合併" else "",
                inputCodes = orderCodes.take(5), outputCodes = updatedCodes.take(5)
            )

            context.log(nodeId, "持倉合併: ${orders.size} 個訂單 → 新增 $newCount 只, " +
                "更新 ${updatedCodes.size} 只, 總持倉 $totalHoldings")

            PositionMergeResult(newCount, updatedCodes, totalHoldings)
        } catch (e: Exception) {
            context.log(nodeId, "持倉合併失敗: ${e.message}")
            context.recordError(nodeId, "持倉合併失敗: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = orders.size, outputCount = 0,
                filterCount = orders.size,
                filterReason = "執行失敗: ${e.message}",
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
 * ## 後臺管理節點
 *
 * 在 AI 分析前暫停後臺任務，分析後恢復。
 * 確保量化選股期間不會與後臺定時任務（如新聞更新、持倉監控）衝突。
 *
 * 原始流程對應：AppBackgroundRunner.isQuantRunning
 */
class BackgroundManagerNode : BaseNode<Any, Unit>("bg_manager", "後臺暫停/恢復", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): Unit {
        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: 準備暫停後臺任務（isQuantRunning=${AppBackgroundRunner.isQuantRunning}）")

        return try {
            // 立即暫停後臺任務（不阻塞）
            AppBackgroundRunner.pauseForQuant()

            // 異步刷新新聞因子（不阻塞 pipeline，失敗也無所謂）
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val updater = com.chin.stockanalysis.news.HotSectorNewsUpdater(context.androidContext.applicationContext)
                    updater.updateIfNeeded(forceRefresh = true)
                } catch (_: Exception) { /* 新聞刷新失敗不影響 pipeline */ }
            }

            context.log(nodeId, "⏸️ 後臺 AI 任務已暫停（isQuantRunning=${AppBackgroundRunner.isQuantRunning}）")

            // 記錄暫停狀態到 context，由 pipeline 執行器在完成後恢復
            context.setStageOutput(nodeId, "paused")

            // 📤 輸出日誌
            context.log(nodeId, "📤 $nodeName 輸出: 後臺已暫停，pipeline 完成後需調用 AppBackgroundRunner.resumeAfterQuant()")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = 0, outputCount = 0,
                filterCount = 0, filterReason = "",
                inputCodes = emptyList(), outputCodes = emptyList()
            )
        } catch (e: Exception) {
            context.log(nodeId, "後臺管理失敗: ${e.message}")
            context.recordError(nodeId, "後臺管理失敗: ${e.message}")
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
 * ## 擬合保存節點
 *
 * 對策略進行參數擬合（網格搜索），保存擬合結果到 DB。
 * 使用 Walk-Forward 分割：訓練集 80%，測試集 20%。
 *
 * 原始流程對應：SimulationTradeEngine.runFitting()
 */
class FittingSaveNode : BaseNode<Any, Unit>("fitting_save", "擬合計算+保存", NodeType.DATA_TRANSFORM) {

    override suspend fun execute(context: PipelineContext, input: Any): Unit {
        // 兼容不同上游類型
        val totalHoldings: Int = when (input) {
            is PositionMergeResult -> input.totalHoldings
            is OrderGenerationResult -> {
                val db0 = StockDatabase.getInstance(context.androidContext)
                db0.strategyTradeOrderDao().getRecent(500)
                    .count { it.status == "BUYING" || it.status == "PENDING" }
            }
            else -> 0
        }

        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: 總持倉 $totalHoldings, 上游類型=${input::class.simpleName}")

        val db = StockDatabase.getInstance(context.androidContext)

        return try {
            // 獲取策略列表
            val strategies = context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
            if (strategies.isEmpty()) {
                context.log(nodeId, "無可用策略，跳過擬合")
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: 0 策略擬合（無策略）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = totalHoldings, outputCount = 0,
                    filterCount = 0,
                    filterReason = "無策略",
                    inputCodes = emptyList(), outputCodes = emptyList()
                )
                return
            }

            // 獲取可用日期
            val availableDates = db.dailySnapshotDao().getAvailableDates(120)
                .sorted()
                .filter { it <= context.tradeDate }

            if (availableDates.size < 20) {
                context.log(nodeId, "歷史數據不足（${availableDates.size} 天），跳過擬合")
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: 0 策略擬合（數據不足）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = totalHoldings, outputCount = 0,
                    filterCount = 0,
                    filterReason = "數據不足",
                    inputCodes = emptyList(), outputCodes = emptyList()
                )
                return
            }

            val optimizer = StrategyOptimizer(context.androidContext)
            var successCount = 0
            val fittingResults = mutableListOf<StrategyTradeFittingParamEntity>()

            for (strategy in strategies) {
                try {
                    // 跳過 AI 預測策略（不適合網格搜索擬合）
                    if (strategy.id == "ai_prediction") continue

                    val result = optimizer.gridSearch(strategy, availableDates)

                    // 保存擬合結果到 DB
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
                    fittingResults.add(entity)
                    successCount++

                    context.log(nodeId, "  擬合策略 ${strategy.id}: " +
                        "accuracy=${"%.1f".format(result.bestAccuracy)}%, " +
                        "avgReturn=${"%.2f".format(result.bestAvgReturn)}%, " +
                        "combinations=${result.totalCombinations}")
                } catch (e: Exception) {
                    context.log(nodeId, "  擬合策略 ${strategy.id} 失敗: ${e.message}")
                }
            }

            // 批量保存
            if (fittingResults.isNotEmpty()) {
                db.strategyTradeFittingParamDao().insertAll(fittingResults)
            }

            // 📤 輸出日誌
            context.log(nodeId, "📤 $nodeName 輸出: ${strategies.size} 策略，擬合成功 $successCount/${strategies.size}")

            // 🚫 過濾日誌
            val failedCount = strategies.size - successCount
            if (failedCount > 0) {
                context.log(nodeId, "🚫 $nodeName 擬合失敗: $failedCount 個策略")
            }
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = totalHoldings, outputCount = successCount,
                filterCount = failedCount,
                filterReason = if (failedCount > 0) "擬合失敗${failedCount}個策略" else "",
                inputCodes = emptyList(), outputCodes = emptyList()
            )

            context.log(nodeId, "擬合計算完成: ${strategies.size} 策略, " +
                "成功 $successCount, 日期範圍 ${availableDates.firstOrNull()} ~ ${availableDates.lastOrNull()}")

            context.setStageOutput(nodeId, successCount)
        } catch (e: Exception) {
            context.log(nodeId, "擬合保存失敗: ${e.message}")
            context.recordError(nodeId, "擬合保存失敗: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = totalHoldings, outputCount = 0,
                filterCount = 0,
                filterReason = "執行失敗: ${e.message}",
                inputCodes = emptyList(), outputCodes = emptyList()
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  HoldingGuardNode (TRADE_ACTION) — 持倉風控
// ════════════════════════════════════════════════════════════════════════════

/**
 * ## 持倉風控節點
 *
 * 獨立於篩選鏈，每次 Pipeline 執行時**必定運行**（放 Layer 1，依賴 n_ctx）：
 * - 評估本周期全部持倉（AutoSellEngine：止損/止盈/策略退出/技術面惡化）
 * - shouldSell=true 的持倉直接執行賣出
 *
 * 與 SwapWeakNode 的分工：
 * - HoldingGuardNode：「治病」— 持倉本身出問題（止損/止盈觸發），無論有無新候選都賣
 * - SwapWeakNode：「換血」— 持倉健康但倉位滿，賣最弱騰位給更優的新票
 *
 * 非關鍵節點：失敗不阻斷 Pipeline。
 *
 * XML 用法：
 * ```xml
 * <Node id="n_guard" name="持倉風控" module="holding_guard" />
 * <Link><SourceNodeId>n_ctx</SourceNodeId><TargetNodeId>n_guard</TargetNodeId></Link>
 * ```
 */
class HoldingGuardNode(
    private val strategies: List<Strategy> = emptyList()
) : BaseNode<Any, HoldingGuardResult>("holding_guard", "持倉風控", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: Any): HoldingGuardResult {
        val effectiveStrategies = strategies.ifEmpty {
            context.getStageOutput<List<Strategy>>("_strategies") ?: emptyList()
        }

        val db = StockDatabase.getInstance(context.androidContext)
        val period = orderTypePeriod(context.config.orderType)
        val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
            .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }

        if (holdingOrders.isEmpty()) {
            context.log(nodeId, "📥 $nodeName: 無 $period 周期持倉，跳過評估")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = 0, outputCount = 0,
                filterCount = 0, filterReason = "",
                inputCodes = emptyList(), outputCodes = emptyList()
            )
            return HoldingGuardResult(0, emptyList(), 0, 0)
        }

        context.log(nodeId, "📥 $nodeName 輸入: ${holdingOrders.size} 只 $period 持倉")

        return try {
            val sellEngine = AutoSellEngine(context.androidContext)
            val decisions = sellEngine.evaluateAll(
                effectiveStrategies,
                AutoSellEngine.AutoSellConfig(
                    tradeDate = context.tradeDate,
                    // 自適應止損：空頭市場收緊硬止損；長期持倉放寬到 -25%
                    hardStopLossPct = if (period == "long") -25.0
                        else context.getAdaptiveParams()?.stopLossRate?.times(100)
                            ?: AutoSellEngine.HARD_STOP_LOSS_PCT
                )
            ).filter { orderTypePeriod(it.order.orderType) == period }

            var mustSell = decisions.filter { it.shouldSell }

            // ── 長期持倉特殊保護：只賣突發利空，不賣常規止盈止損 ──
            // 長期容忍更大波動，止盈不觸發，只有突發利空（單日跌幅>7%或3日累計跌幅>15%）才賣出
            if (period == "long" && mustSell.isNotEmpty()) {
                val filteredSell = mustSell.filter { decision ->
                    try {
                        val recentSnaps = db.dailySnapshotDao()
                            .getByCode(decision.order.stockCode, 5)
                            .sortedBy { it.date }  // 按 date ASC，last() = 最新
                        if (recentSnaps.size >= 3) {
                            val singleDayDrop = recentSnaps.last().changePct
                            val threeDayDrop = recentSnaps.takeLast(3).sumOf { it.changePct }
                            // 突發利空：單日跌幅>7% 或 3日累計跌幅>15%
                            singleDayDrop < -7.0 || threeDayDrop < -15.0
                        } else false
                    } catch (_: Exception) {
                        false  // 查詢失敗時不賣（保守策略，避免誤殺長期持倉）
                    }
                }
                val filteredOut = mustSell.size - filteredSell.size
                if (filteredOut > 0) {
                    context.log(nodeId, "長期持倉保護: 過濾掉 $filteredOut 只常規止盈止損信號，" +
                        "僅保留 ${filteredSell.size} 只突發利空信號")
                }
                mustSell = filteredSell
            }

            if (mustSell.isEmpty()) {
                context.log(nodeId, "📤 $nodeName: ${holdingOrders.size} 只持倉全部健康，無賣出信號")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = holdingOrders.size, outputCount = 0,
                    filterCount = 0, filterReason = "",
                    inputCodes = holdingOrders.map { it.stockCode }.take(5), outputCodes = emptyList()
                )
                return HoldingGuardResult(0, emptyList(), holdingOrders.size, holdingOrders.size)
            }

            // 執行賣出（shouldSell 已過濾，force=false 即可）
            val soldNames = mustSell.map { "${it.order.stockName}(${it.reason})" }
            context.log(nodeId, "🚫 $nodeName 賣出: ${soldNames.joinToString(", ")}")
            sellEngine.executeSells(mustSell, context.tradeDate)

            val remainingCount = db.strategyTradeOrderDao().getRecent(500)
                .count { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }

            context.log(nodeId, "📤 $nodeName 輸出: 賣出 ${mustSell.size} 只，剩餘 $remainingCount 只")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = holdingOrders.size, outputCount = mustSell.size,
                filterCount = 0, filterReason = "風控賣出",
                inputCodes = holdingOrders.map { it.stockCode }.take(5),
                outputCodes = mustSell.map { it.order.stockCode }
            )

            HoldingGuardResult(mustSell.size, soldNames, remainingCount, holdingOrders.size)
        } catch (e: Exception) {
            context.log(nodeId, "$nodeName 異常: ${e.message}（不阻斷 Pipeline）")
            context.recordError(nodeId, "持倉風控異常: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = holdingOrders.size, outputCount = 0,
                filterCount = 0, filterReason = "執行異常: ${e.message}",
                inputCodes = holdingOrders.map { it.stockCode }.take(5), outputCodes = emptyList()
            )
            HoldingGuardResult(0, emptyList(), holdingOrders.size, holdingOrders.size)
        }
    }
}
