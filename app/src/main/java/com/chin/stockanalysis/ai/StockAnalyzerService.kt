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
        Log.i(TAG, "🚀 analyze() started: code=$stockCode name=$stockNameHint")
        val today = LocalDate.now().format(DATE_FMT)

        // ── Step 1: 获取最新数据 (合并到一个泡泡) ──
        val initMsg = StringBuilder("🔍 分析 ${stockNameHint.ifBlank { stockCode.takeLast(6) }}\n")
        val feed = StrategyDataFeed(context)
        val allStocks = feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = false))
        Log.i(TAG, "  全市场数据: ${allStocks.size}只, 目标代码=$stockCode")
        var targetStock = allStocks.find { it.code == stockCode }
        if (targetStock == null) {
            Log.i(TAG, "  ⚠️ 全市场数据中未找到 $stockCode, 尝试单独拉取...")
            // 尝试只拉取这只股票的数据
            try {
                val db = StockDatabase.getInstance(context)
                val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(context)
                val todayDate = LocalDate.now()
                val (records, name) = fetcher.fetchOneStock(stockCode, todayDate.minusDays(5), todayDate)
                Log.i(TAG, "  单独拉取结果: ${records.size}条, 名称=$name")
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
            } catch (e: Exception) { Log.w(TAG, "  单独拉取异常: ${e.message}") }
        }
        if (targetStock == null) {
            throw Exception("未找到 $stockCode 的行情数据，请先导入历史数据")
        }
        val stock = targetStock
        val stockName = stock.name.takeIf { it.isNotBlank() } ?: stockNameHint
        Log.i(TAG, "  ✅ 数据就绪: $stockName price=${stock.price} chg=${stock.changePercent}")
        initMsg.append("✅ $stockName · ¥${"%.2f".format(stock.price)} · 涨${if(stock.changePercent>=0)"+" else ""}${"%.2f".format(stock.changePercent)}%\n")

        // ── Step 2: 记录到 userSearchHistory ──
        com.chin.stockanalysis.stock.database.StockDataCenter.recordUserSearch(stockCode, stockName, stock.price, stock.changePercent)

        // ── Step 3: 执行所有策略 (合并到一个泡泡) ──
        val eng = strategyEngine ?: StrategyEngineHolder.get()
        val enabledCount = eng.getStrategies().count { eng.isEnabled(it.id) && it.id != "ai_prediction" }
        Log.i(TAG, "  📈 开始执行 $enabledCount 个策略...")
        initMsg.append("📈 多策略分析 (${enabledCount}个策略):\n")
        val singleStockList = listOf(stock)
        val hits = mutableListOf<StrategyHit>()
        val screeningList = mutableListOf<ScreeningResult>()
        var hitIdx = 1
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
                        initMsg.append("  $hitIdx. ${strategy.category.icon} ${strategy.name}: ✅ 命中 (${sig.strength}%)\n")
                    } else {
                        initMsg.append("  $hitIdx. ${strategy.category.icon} ${strategy.name}: ❌ 未命中\n")
                    }
                }
            } catch (_: Exception) {
                initMsg.append("  $hitIdx. ${strategy.category.icon} ${strategy.name}: ⚠️ 异常\n")
            }
            hitIdx++
        }
        initMsg.append("\n策略命中: ${hits.size}/$enabledCount 个策略")
        onProgress(Message(content = initMsg.toString(), isUser = false))

        // ── Step 4: 智能体技术分析 (独立泡泡) ──
        onProgress(Message(content = buildString {
            appendLine("🤖 智能体技术分析")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("现价: ¥${"%.2f".format(stock.price)} | 涨跌: ${if(stock.changePercent>=0)"+" else ""}${"%.2f".format(stock.changePercent)}%")
            appendLine("成交量: ${formatVolume(stock.volume)} | 开盘: ¥${"%.2f".format(stock.open)} | 昨收: ¥${"%.2f".format(stock.yestClose)}")
            if (hits.isNotEmpty()) {
                appendLine("策略命中 (${hits.size}个):")
                for (h in hits) {
                    appendLine("  · ${h.strategyName}: ${h.strength}%")
                }
            } else {
                appendLine("⚠️ 无策略命中")
            }
            append("━━━━━━━━━━━━━━━━━━")
        }, isUser = false))

        // ── Step 5: AI 预测 (独立泡泡) ──
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
                        appendLine("🧠 AI 综合评估报告")
                        appendLine("━━━━━━━━━━━━━━━━━━")
                        appendLine("股票: $stockName (${stockCode.takeLast(6)})")
                        appendLine("价格: ¥${"%.2f".format(stock.price)} (${if(stock.changePercent>=0)"+" else ""}${"%.2f".format(stock.changePercent)}%)")
                        appendLine("综合评分: ${topPick.compositeScore}分")
                        appendLine("上涨概率: ${topPick.upProbability}%")
                        appendLine("建议: $actionEmoji")
                        appendLine("理由: ${topPick.reason}")
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