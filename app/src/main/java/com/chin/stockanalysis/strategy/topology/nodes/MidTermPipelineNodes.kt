package com.chin.stockanalysis.strategy.topology.nodes

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
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.trade.AutoSellEngine
import com.chin.stockanalysis.strategy.trade.SimulationTradeEngine
import com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity
import com.chin.stockanalysis.strategy.trade.StrategyTradeFittingParamEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ════════════════════════════════════════════════════════════════════════════
//  中線量化獨有 Node
//
//  相比短線/選股，中線獨有的 12 個步驟：
//  1. CrossDayAggregationNode    — 跨日聚合（回溯5天策略命中頻次）
//  2. MultiPeriodHotNode        — 多周期熱門股聚合（3/5/10/30/50/100天漲幅Top3）
//  3. NewsStrengthNode           — 新聞力度計算（近3天新聞影響力×情緒）
//  4. RotationPenaltyNode       — 板塊輪動懲罰（防止板塊過度集中）
//  5. NewsGuardNode              — 新聞攔截（買入前利空攔截）+ 技術過濾（4條規則）
//  6. AdaptiveParamsNode         — 大盤分析+自適應參數（動態閾值/數量限制）
//  7. SwapWeakNode               — 騰龍換鳥（倉位不足時自動換股）
//  8. HeatScoreNode              — 熱度計算（5維熱度分數 0-100）
//  9. GenerateOrdersNode         — 買入訂單生成（AI精選 + 自適應參數）
//  10. PositionMergeNode        — 持倉合併（追加加權平均 / 新增PENDING訂單）
//  11. BackgroundManagerNode     — 後臺暫停/恢復（AI分析前暫停，分析後恢復）
//  12. FittingSaveNode           — 擬合計算+保存（網格搜索擬合結果保存到DB）
// ════════════════════════════════════════════════════════════════════════════

private const val TAG = "MidTermNodes"
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

/**
 * 將 orderType 歸一化為持倉周期標識。
 *
 * 不同寫入路徑的 orderType 取值不一致（如 DAG 寫 "ShortTermQuant"、
 * Fragment 寫 "shortterm"），持倉統計必須按周期聚合而非跨周期累加，
 * 否則短線會把中線持倉也算進自己的倉位数。
 */
private fun orderTypePeriod(orderType: String): String = when {
    orderType.contains("UltraShort", ignoreCase = true) ||
        orderType.contains("ultra_short", ignoreCase = true) -> "ultra_short"
    orderType.contains("ShortTerm", ignoreCase = true) ||
        orderType.contains("short_term", ignoreCase = true) ||
        orderType.equals("shortterm", ignoreCase = true) -> "short"
    orderType.contains("MidTerm", ignoreCase = true) ||
        orderType.contains("mid_term", ignoreCase = true) -> "mid"
    orderType.contains("LongTerm", ignoreCase = true) ||
        orderType.contains("long_term", ignoreCase = true) -> "long"
    else -> "other"
}

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
 */
data class SwapWeakResult(
    val swappedCount: Int,                  // 實際換股數量
    val soldStocks: List<String>,          // 被賣出的股票（名稱）
    val beforeCount: Int,                   // 換股前持倉數
    val afterCount: Int                     // 換股後持倉數
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
    val orders: List<SimulationTradeEngine.TradeOrder>,
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
) : PipelineNode<Any, CrossDayResult> {

    override val nodeId: String = "cross_day_aggregation"
    override val nodeName: String = "跨日聚合"
    override val nodeType: NodeType = NodeType.AGGREGATION

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
) : PipelineNode<Unit, MultiPeriodHotResult> {

    override val nodeId: String = "multi_period_hot"
    override val nodeName: String = "多周期熱門股聚合"
    override val nodeType: NodeType = NodeType.DATA_SOURCE

    override suspend fun execute(context: PipelineContext, input: Unit): MultiPeriodHotResult {
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
) : PipelineNode<MergedSignalPool, Int> {

    override val nodeId: String = "news_strength"
    override val nodeName: String = "新聞力度計算"
    override val nodeType: NodeType = NodeType.ENRICHMENT

    override suspend fun execute(context: PipelineContext, input: MergedSignalPool): Int {
        val db = StockDatabase.getInstance(context.androidContext)

        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: ${input.totalStocks} 只股票 ${formatTopCodes(input.stockHits.keys)}")

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
                    inputCount = input.totalStocks, outputCount = input.totalStocks,
                    filterCount = 0, filterReason = "",
                    inputCodes = input.stockHits.keys.toList().take(5),
                    outputCodes = input.stockHits.keys.toList().take(5)
                )
                return 50
            }

            val signalCodes = input.stockHits.keys
            val related = newsList.filter { it.stockCode in signalCodes || it.sector.isNotEmpty() }

            if (related.isEmpty()) {
                context.log(nodeId, "新聞力度: ${newsList.size} 條新聞中無相關, 默認 40 分")
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: score=40 (無相關新聞)")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = input.totalStocks, outputCount = input.totalStocks,
                    filterCount = 0, filterReason = "",
                    inputCodes = input.stockHits.keys.toList().take(5),
                    outputCodes = input.stockHits.keys.toList().take(5)
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
                inputCount = input.totalStocks, outputCount = input.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = input.stockHits.keys.toList().take(5),
                outputCodes = input.stockHits.keys.toList().take(5)
            )

            context.log(nodeId, "新聞力度: ${related.size} 條相關新聞, " +
                "avgImpact=${"%.1f".format(avgStr)}, avgSentiment=${"%.1f".format(avgSent)}, " +
                "score=$score")

            score
        } catch (e: Exception) {
            context.log(nodeId, "新聞力度計算失敗: ${e.message}, 默認 50 分")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = input.totalStocks, outputCount = input.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = input.stockHits.keys.toList().take(5),
                outputCodes = input.stockHits.keys.toList().take(5)
            )
            50
        }
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
) : PipelineNode<MergedSignalPool, Int> {

    override val nodeId: String = "rotation_penalty"
    override val nodeName: String = "板塊輪動懲罰"
    override val nodeType: NodeType = NodeType.ENRICHMENT

    override suspend fun execute(context: PipelineContext, input: MergedSignalPool): Int {
        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: ${input.totalStocks} 只股票 ${formatTopCodes(input.stockHits.keys)}")

        return try {
            val sectorCounts = mutableMapOf<String, Int>()

            for (code in input.stockHits.keys) {
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
                inputCount = input.totalStocks, outputCount = input.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = input.stockHits.keys.toList().take(5),
                outputCodes = input.stockHits.keys.toList().take(5)
            )

            finalPenalty
        } catch (e: Exception) {
            context.log(nodeId, "輪動懲罰計算失敗: ${e.message}, 默認 0")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = input.totalStocks, outputCount = input.totalStocks,
                filterCount = 0, filterReason = "",
                inputCodes = input.stockHits.keys.toList().take(5),
                outputCodes = input.stockHits.keys.toList().take(5)
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
) : PipelineNode<Any, NewsGuardResult> {

    override val nodeId: String = "news_guard"
    override val nodeName: String = "新聞攔截+技術過濾"
    override val nodeType: NodeType = NodeType.FILTER

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
 * - scoreThreshold: 動態評分閾值（BEARISH=55, OSCILLATION=50, BULLISH=45）
 * - maxStockCount: 最大選股數量
 * - shouldGoEmpty: 是否觸發空倉
 *
 * 中線獨有：中線使用自適應參數調整閾值和持倉限制，短線不使用。
 *
 * @property holdingCodes 持倉股票代碼（用於大盤分析）
 */
class AdaptiveParamsNode(
    private val holdingCodes: List<String> = emptyList()
) : PipelineNode<Unit, MarketAdaptiveStrategy.AdaptiveParams?> {

    override val nodeId: String = "adaptive_params"
    override val nodeName: String = "大盤分析+自適應參數"
    override val nodeType: NodeType = NodeType.DATA_SOURCE

    override suspend fun execute(context: PipelineContext, input: Unit): MarketAdaptiveStrategy.AdaptiveParams? {
        // 📥 輸入日誌
        context.log(nodeId, "📥 $nodeName 輸入: ${holdingCodes.size} 只持倉 ${formatTopCodes(holdingCodes)}")

        return try {
            // 觸發延遲加載（首次調用時計算，後續直接返回緩存值）
            val report = context.getMarketReport(holdingCodes)
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
) : PipelineNode<OrderGenerationResult, SwapWeakResult> {

    override val nodeId: String = "swap_weak"
    override val nodeName: String = "騰龍換鳥"
    override val nodeType: NodeType = NodeType.TRADE_ACTION

    override suspend fun execute(context: PipelineContext, input: OrderGenerationResult): SwapWeakResult {
        // 從上游 GenerateOrdersNode 輸出中動態獲取 newBuyCount
        val newBuyCount = input.orders.size

        // 📥 輸入日誌
        val orderCodes = input.orders.map { "${it.stockCode}(${it.stockName})" }
        context.log(nodeId, "📥 $nodeName 輸入: ${input.orders.size} 個訂單 ${formatTopCodes(orderCodes)}")

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
            val currentCount = holdingOrders.size

            // ── 騰龍換鳥只在「持倉+新買超出上限」時觸發 ──
            // 持倉+新買 ≤ 上限時倉位足夠，新票直接買入即可，無需賣出現有持倉騰位置。
            if (currentCount + newBuyCount <= maxHoldings) {
                context.log(nodeId, "騰龍換鳥: 持倉 $currentCount + 新買 $newBuyCount ≤ $maxHoldings, 倉位足夠, 無需換股")
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: 0 只換股，持倉不變 $currentCount")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = input.orders.size, outputCount = 0,
                    filterCount = input.orders.size,
                    filterReason = "持倉未滿無需換股",
                    inputCodes = orderCodes.take(5), outputCodes = emptyList()
                )
                return SwapWeakResult(0, emptyList(), currentCount, currentCount)
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
                return SwapWeakResult(0, emptyList(), currentCount, currentCount)
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
            // 若最佳新票都打不過最弱的待賣持倉，說明這次換股不是「升級」，不執行。
            val bestNewScore = input.orders.maxOfOrNull { it.scoreAtBuy } ?: 0
            val marginalHoldingScore = rankedForSale.lastOrNull()?.order?.scoreAtBuy ?: Int.MAX_VALUE
            if (bestNewScore <= marginalHoldingScore) {
                context.log(nodeId, "騰龍換鳥: 新票最高分 $bestNewScore ≤ 持倉分 $marginalHoldingScore, " +
                    "新票不夠優, 不換股")
                context.log(nodeId, "📤 $nodeName 輸出: 0 只換股（新票不優於持倉）")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = input.orders.size, outputCount = 0,
                    filterCount = input.orders.size,
                    filterReason = "新票不優於持倉",
                    inputCodes = orderCodes.take(5), outputCodes = emptyList()
                )
                return SwapWeakResult(0, emptyList(), currentCount, currentCount)
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
                    inputCount = input.orders.size, outputCount = rankedForSale.size,
                    filterCount = if (rankedForSale.isEmpty()) input.orders.size else 0,
                    filterReason = if (rankedForSale.isEmpty()) "無需換股" else "",
                    inputCodes = orderCodes.take(5), outputCodes = rankedForSale.take(5).map { it.order.stockCode }
                )

                SwapWeakResult(
                    swappedCount = rankedForSale.size,
                    soldStocks = soldNames,
                    beforeCount = currentCount,
                    afterCount = afterCount
                )
            } else {
                // 📤 輸出日誌
                context.log(nodeId, "📤 $nodeName 輸出: 0 只換股，無需賣出")
                context.recordStockFlow(
                    nodeId = nodeId, nodeName = nodeName,
                    inputCount = input.orders.size, outputCount = 0,
                    filterCount = input.orders.size,
                    filterReason = "無需換股",
                    inputCodes = orderCodes.take(5), outputCodes = emptyList()
                )
                SwapWeakResult(0, emptyList(), currentCount, currentCount)
            }
        } catch (e: Exception) {
            context.log(nodeId, "騰龍換鳥失敗: ${e.message}")
            context.recordError(nodeId, "騰龍換鳥失敗: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = input.orders.size, outputCount = 0,
                filterCount = input.orders.size,
                filterReason = "執行失敗: ${e.message}",
                inputCodes = orderCodes.take(5), outputCodes = emptyList()
            )
            SwapWeakResult(0, emptyList(), 0, 0)
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
class HeatScoreNode : PipelineNode<StockPool, Map<String, Int>> {

    override val nodeId: String = "heat_score"
    override val nodeName: String = "熱度計算"
    override val nodeType: NodeType = NodeType.DATA_TRANSFORM

    override suspend fun execute(context: PipelineContext, input: StockPool): Map<String, Int> {
        // 📥 輸入日誌
        val inputCodes = input.stocks.map { it.code }
        context.log(nodeId, "📥 $nodeName 輸入: ${input.stocks.size} 只股票 ${formatTopCodes(inputCodes)}")

        if (input.isEmpty) {
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

            for (stock in input.stocks) {
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
                inputCount = input.stocks.size, outputCount = heatScores.size,
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
                inputCount = input.stocks.size, outputCount = 0,
                filterCount = input.stocks.size,
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
 * @property maxHoldings 最大持倉數（默認 5）
 * @property orderType 訂單類型（默認 "MidTermQuant"）
 */
class GenerateOrdersNode(
    private val maxHoldings: Int = 5,
    private val orderType: String = "MidTermQuant"
) : PipelineNode<Any, OrderGenerationResult> {

    override val nodeId: String = "generate_orders"
    override val nodeName: String = "買入訂單生成"
    override val nodeType: NodeType = NodeType.TRADE_ACTION

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

            // 獲取當前持倉（按周期統計：只算與本 Pipeline 同周期的持倉，不跨周期累加）
            val period = orderTypePeriod(orderType)
            val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
                .filter { (it.status == "BUYING" || it.status == "PENDING") && orderTypePeriod(it.orderType) == period }
            val holdingCodes = holdingOrders.map { it.stockCode }.toSet()
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

                // 2. 已持有過濾
                if (pick.stockCode in holdingCodes) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: 已持有")
                    continue
                }

                // 3. ETF 過濾（排除 ETF）
                if (pick.stockCode.startsWith("sh51") || pick.stockCode.startsWith("sz15")) {
                    filteredCount++
                    filteredReasons.add("${pick.stockCode}: ETF排除")
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

            // ── 大盤防守：空倉防禦 ──
            // 與 Hardcode 路徑（SimulationTradeEngine.shouldForceEmpty）一致：
            // 大盤明確空頭且高分候選不足 2 只時，一股不買。
            // 僅中線/長線生效：順勢交易、持倉週期長，熊市空頭應空倉保本。
            // 超短/短線屬均值回歸，賺的是超跌反彈/V型反包/情緒脈衝——恰恰出現在殺最凶時，
            // 不擋空倉，改靠 maxStockCount 限倉 + 嚴止損 + T+1 紀律控風險。
            val marketReport = context.getMarketReport()
            if ((period == "mid" || period == "long") && marketReport != null &&
                MarketAdaptiveStrategy.shouldForceEmpty(marketReport, candidates.size)) {
                context.log(nodeId, "🛡 $nodeName 大盤防守: BEARISH(強度${marketReport.trend.strength}) + " +
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

            // 數量限制（自適應最大買入數；實際入庫由 position_merge 按可用倉位裁切）
            val buyCap = maxStockCount
            val finalCandidates = candidates
                .sortedByDescending { it.compositeScore }
                .take(buyCap)

            // 生成訂單
            val snapshots = try {
                db.dailySnapshotDao().getByDate(context.tradeDate)
            } catch (_: Exception) { emptyList() }
            val snapMap = snapshots.associateBy { it.code }

            val orders = finalCandidates.mapIndexed { index, pick ->
                val snap = snapMap[pick.stockCode]
                val buyPrice = snap?.close ?: 0.0
                SimulationTradeEngine.TradeOrder(
                    stockCode = pick.stockCode,
                    stockName = pick.stockName,
                    strategyId = "ai_midterm_${context.tradeDate}",
                    tradeDate = context.tradeDate,
                    buyPrice = buyPrice,
                    quantity = 100, // 默認一手
                    reason = "AI精選 rank=${pick.rank} score=${pick.compositeScore} ${pick.reason}",
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
                    if (filteredCount > 0) append("已持倉或價格異常${filteredCount}只")
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
class PositionMergeNode : PipelineNode<Any, PositionMergeResult> {

    override val nodeId: String = "position_merge"
    override val nodeName: String = "持倉合併"
    override val nodeType: NodeType = NodeType.TRADE_ACTION

    override suspend fun execute(context: PipelineContext, rawInput: Any): PositionMergeResult {
        // 騰龍換鳥(n_swap)是輔助節點，其輸出 SwapWeakResult 僅用於保證執行順序（先賣後買），
        // 實際訂單數據統一從 generate_orders(n_orders) 的輸出獲取
        val input: OrderGenerationResult = when (rawInput) {
            is OrderGenerationResult -> rawInput
            else -> context.getStageOutput<OrderGenerationResult>("n_orders")
                ?: OrderGenerationResult(emptyList(), 0, false)
        }
        // 📥 輸入日誌
        val orderCodes = input.orders.map { "${it.stockCode}(${it.stockName})" }
        context.log(nodeId, "📥 $nodeName 輸入: ${input.orders.size} 個訂單 ${formatTopCodes(orderCodes)}")

        if (input.orders.isEmpty()) {
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

            for (order in input.orders) {
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
                inputCount = input.orders.size, outputCount = newCount,
                filterCount = input.orders.size - newCount,
                filterReason = if (input.orders.size - newCount > 0) "重複持倉合併" else "",
                inputCodes = orderCodes.take(5), outputCodes = updatedCodes.take(5)
            )

            context.log(nodeId, "持倉合併: ${input.orders.size} 個訂單 → 新增 $newCount 只, " +
                "更新 ${updatedCodes.size} 只, 總持倉 $totalHoldings")

            PositionMergeResult(newCount, updatedCodes, totalHoldings)
        } catch (e: Exception) {
            context.log(nodeId, "持倉合併失敗: ${e.message}")
            context.recordError(nodeId, "持倉合併失敗: ${e.message}")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = input.orders.size, outputCount = 0,
                filterCount = input.orders.size,
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
class BackgroundManagerNode : PipelineNode<Unit, Unit> {

    override val nodeId: String = "bg_manager"
    override val nodeName: String = "後臺暫停/恢復"
    override val nodeType: NodeType = NodeType.DATA_SOURCE

    override suspend fun execute(context: PipelineContext, input: Unit): Unit {
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
class FittingSaveNode : PipelineNode<Any, Unit> {

    override val nodeId: String = "fitting_save"
    override val nodeName: String = "擬合計算+保存"
    override val nodeType: NodeType = NodeType.DATA_TRANSFORM

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
) : PipelineNode<Any, HoldingGuardResult> {

    override val nodeId: String = "holding_guard"
    override val nodeName: String = "持倉風控"
    override val nodeType: NodeType = NodeType.TRADE_ACTION

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
                    // 自適應止損：空頭市場收緊硬止損
                    hardStopLossPct = context.getAdaptiveParams()?.stopLossRate?.times(100)
                        ?: AutoSellEngine.HARD_STOP_LOSS_PCT
                )
            ).filter { orderTypePeriod(it.order.orderType) == period }

            val mustSell = decisions.filter { it.shouldSell }

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
