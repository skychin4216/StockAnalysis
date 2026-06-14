package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.data.StrategyDataFeed
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.ui.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 单只股票分析服务
 *
 * 分析流程 (参考模拟交易 9 步流程):
 *   1. 获取最新数据 (如未缓存则拉取)
 *   2. 执行所有启用策略 → 收集命中结果
 *   3. 智能体分析 → 汇总技术指标
 *   4. AI 预测 → 最终评分 + 买入建议
 *
 * 每个步骤都通过 onProgress 回调实时推送进度到对话框。
 */
class StockAnalyzerService(private val context: Context) {

    companion object {
        private const val TAG = "StockAnalyzer"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    data class AnalysisResult(
        val stockCode: String,
        val stockName: String,
        val currentPrice: Double,
        val changePct: Double,
        val strategyHits: List<StrategyHit>,
        val agentAnalysis: String,
        val aiPicks: List<AIPredictionEngine.AIPick>,
        val finalRecommendation: String
    )

    data class StrategyHit(
        val strategyName: String,
        val strength: Int,
        val reason: String
    )

    suspend fun analyze(
        stockCode: String,
        stockNameHint: String = "",
        strategyEngine: StrategyEngine? = null,
        onProgress: suspend (Message) -> Unit
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val today = LocalDate.now().format(DATE_FMT)

        // ── Step 1: 获取最新数据 ──
        onProgress(Message(content = "📊 正在获取 $stockCode 最新行情数据...", isUser = false))
        val feed = StrategyDataFeed(context)
        val allStocks = feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = false))
        var targetStock = allStocks.find { it.code == stockCode }
        if (targetStock == null) {
            // 尝试只拉取这只股票的数据
            try {
                val db = StockDatabase.getInstance(context)
                val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(context)
                val todayDate = LocalDate.now()
                val (records, name) = fetcher.fetchOneStock(stockCode, todayDate.minusDays(5), todayDate)
                if (records.isNotEmpty()) {
                    db.dailySnapshotDao().insertAll(records)
                    val close = records.lastOrNull()?.close ?: 0.0
                    val open = records.lastOrNull()?.open ?: 0.0
                    val chg = records.lastOrNull()?.changePct ?: 0.0
                    val vol = records.lastOrNull()?.volume ?: 0L
                    targetStock = StockRealtime(
                        code = stockCode, name = name.ifBlank { stockNameHint },
                        price = close, open = open,
                        yestClose = if (chg != 0.0 && close != 0.0) close / (1.0 + chg / 100.0) else close,
                        high = records.lastOrNull()?.high ?: close,
                        low = records.lastOrNull()?.low ?: close,
                        volume = vol, amount = close * vol,
                        changePercent = chg, changeAmount = close * chg / 100,
                        timestamp = System.currentTimeMillis()
                    )
                }
            } catch (_: Exception) {}
        }
        if (targetStock == null) {
            throw Exception("未找到 $stockCode 的行情数据，请先导入历史数据")
        }
        val stock = targetStock
        val stockName = stock.name.takeIf { it.isNotBlank() } ?: stockNameHint
        onProgress(Message(content = "✅ 数据就绪: $stockName(${stockCode.takeLast(6)}) 现价 ¥${"%.2f".format(stock.price)} ${if(stock.changePercent>=0)"+" else ""}${"%.2f".format(stock.changePercent)}%", isUser = false))

        // ── Step 2: 记录到 userSearchHistory ──
        com.chin.stockanalysis.stock.database.StockDataCenter.recordUserSearch(stockCode, stockName, stock.price, stock.changePercent)

        // ── Step 3: 执行所有策略（每个策略单独显示） ──
        onProgress(Message(content = "📈 多策略分析 (${if(strategyEngine!=null) "自定义引擎" else "全部启用策略"}):", isUser = false))
        val eng = strategyEngine ?: StrategyEngineHolder.get()
        val singleStockList = listOf(stock)
        val hits = mutableListOf<StrategyHit>()
        val screeningList = mutableListOf<ScreeningResult>()
        for (strategy in eng.getStrategies()) {
            if (!eng.isEnabled(strategy.id) || strategy.id == "ai_prediction") continue
            try {
                val result = strategy.screenWithData(singleStockList)
                val screening = result.getOrNull()
                if (screening != null) {
                    screeningList.add(screening)
                    val sig = screening.signals.firstOrNull()
                    if (sig != null) {
                        hits.add(StrategyHit(strategy.name, sig.strength, sig.reason))
                        onProgress(Message(content = "  ${strategy.category.icon} ${strategy.name}: ✅ 命中 (强度${sig.strength}%) — ${sig.reason.take(50)}", isUser = false))
                    } else {
                        onProgress(Message(content = "  ${strategy.category.icon} ${strategy.name}: ❌ 未命中", isUser = false))
                    }
                }
            } catch (_: Exception) {
                onProgress(Message(content = "  ${strategy.category.icon} ${strategy.name}: ⚠️ 执行异常", isUser = false))
            }
        }
        onProgress(Message(content = "策略命中: ${hits.size}/${eng.getStrategies().count { eng.isEnabled(it.id) && it.id != "ai_prediction" }} 个策略", isUser = false))

        // ── Step 4: 智能体分析 ──
        onProgress(Message(content = "🤖 智能体技术分析:", isUser = false))
        onProgress(Message(content = "━━ 技术指标 ━━\n现价: ¥${"%.2f".format(stock.price)} | 涨跌: ${if(stock.changePercent>=0)"+" else ""}${"%.2f".format(stock.changePercent)}%\n成交量: ${formatVolume(stock.volume)} | 开盘: ¥${"%.2f".format(stock.open)} | 昨收: ¥${"%.2f".format(stock.yestClose)}", isUser = false))
        if (hits.isNotEmpty()) {
            onProgress(Message(content = "命中策略 (${hits.size}个):", isUser = false))
            for (h in hits) {
                onProgress(Message(content = "  · ${h.strategyName}: ${h.strength}%", isUser = false))
            }
        }

        // ── Step 5: AI 预测 ──
        onProgress(Message(content = "🧠 AI 综合评估 (AIPredictionEngine)...", isUser = false))
        val aiPicks = mutableListOf<AIPredictionEngine.AIPick>()
        var finalRecommendation: String
        if (screeningList.isNotEmpty()) {
            try {
                val aiEngine = AIPredictionEngine(context)
                val prediction = aiEngine.predict(screeningList, today)
                if (prediction != null && prediction.topPicks.isNotEmpty()) {
                    aiPicks.addAll(prediction.topPicks)
                    val topPick = prediction.topPicks.first()
                    val actionEmoji = when {
                        topPick.compositeScore >= 75 -> "🟢 推荐买入"
                        topPick.compositeScore >= 60 -> "🟡 可关注"
                        else -> "🔴 建议观望"
                    }
                    finalRecommendation = buildString {
                        appendLine("━━━━━━━━━━━━━━━━━━")
                        appendLine("🧠 AI 综合评估报告")
                        appendLine("股票: $stockName (${stockCode.takeLast(6)})")
                        appendLine("价格: ¥${"%.2f".format(stock.price)} (${if(stock.changePercent>=0)"+" else ""}${"%.2f".format(stock.changePercent)}%)")
                        appendLine("综合评分: ${topPick.compositeScore}分")
                        appendLine("上涨概率: ${topPick.upProbability}%")
                        appendLine("建议: $actionEmoji")
                        appendLine("理由: ${topPick.reason}")
                        appendLine("策略命中: ${hits.joinToString { "${it.strategyName.take(4)}(${it.strength})" }}")
                        if (hits.isEmpty()) appendLine("⚠️ 无策略命中，请谨慎操作")
                        appendLine("━━━━━━━━━━━━━━━━━━")
                    }
                } else {
                    finalRecommendation = buildString {
                        appendLine("━━━━━━━━━━━━━━━━━━")
                        appendLine("⚠️ AI 预测暂不可用")
                        appendLine("策略命中数: ${hits.size}")
                        if (hits.isNotEmpty()) appendLine("命中策略: ${hits.joinToString { "${it.strategyName}(${it.strength}%)" }}")
                        appendLine("━━━━━━━━━━━━━━━━━━")
                    }
                }
            } catch (_: Exception) {
                finalRecommendation = "AI 预测执行异常，请稍后重试"
            }
        } else {
            finalRecommendation = buildString {
                appendLine("━━━━━━━━━━━━━━━━━━")
                appendLine("⚠️ 所有策略均未命中")
                appendLine("可能原因: 该股票当前不符合任何策略的买入条件")
                appendLine("━━━━━━━━━━━━━━━━━━")
            }
        }
        onProgress(Message(content = finalRecommendation, isUser = false))

        AnalysisResult(stockCode, stockName, stock.price, stock.changePercent, hits, "智能体分析已完成", aiPicks, finalRecommendation)
    }

    private fun buildAgentAnalysis(stock: StockRealtime, hits: List<StrategyHit>): String = buildString {
        appendLine("━━ 智能体技术分析 ━━")
        appendLine("现价: ¥${"%.2f".format(stock.price)} | 开盘: ¥${"%.2f".format(stock.open)} | 昨收: ¥${"%.2f".format(stock.yestClose)}")
        appendLine("涨幅: ${if(stock.changePercent>=0)"+" else ""}${"%.2f".format(stock.changePercent)}% | 成交量: ${formatVolume(stock.volume)}")
        if (hits.isNotEmpty()) {
            appendLine("策略命中 (${hits.size}/10):")
            for (h in hits) appendLine("  · ${h.strategyName}: ${h.strength}% — ${h.reason.take(60)}")
        } else {
            appendLine("⚠️ 所有策略均未命中该股票")
        }
    }

    private fun formatVolume(vol: Long): String = when {
        vol >= 1_0000_0000 -> "${"%.2f".format(vol/1e8)}亿"
        vol >= 10000 -> "${"%.2f".format(vol/1e4)}万"
        else -> "$vol"
    }
}