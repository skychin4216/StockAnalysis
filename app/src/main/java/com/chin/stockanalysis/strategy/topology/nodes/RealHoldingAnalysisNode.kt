package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.market.AMarketAnalysisEngine
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.trade.RealPositionEntity
import com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * ## 实仓持仓评估节点 v2
 *
 * 读取 AMarketAnalysisEngine 的大盘结论，
 * 对每只持仓逐股分析 K 线，生成操作建议：
 * - 加仓 / 持有 / 减仓 / 清仓
 * - 做T / 反T / 观望
 *
 * ### 输入
 * - n_a_market: AMarketAnalysisEngine.MarketAnalysisResult
 *
 * ### 输出
 * [RealHoldingAnalysisResult]
 */
data class RealHoldingAnalysisResult(
    val marketSummary: String = "",
    val suggestedPeriod: String = "",
    val suggestedPositionPct: Int = 50,
    val holdingCount: Int = 0,
    val holdingDetails: String = "",
    val overallAdvice: String = ""
)

class RealHoldingAnalysisNode : BaseNode<Any, RealHoldingAnalysisResult>(
    "n_rh_eval", "实仓持仓评估", NodeType.TRADE_ACTION
) {
    companion object {
        private const val TAG = "RealHoldingEval"
    }

    override suspend fun execute(context: PipelineContext, input: Any): RealHoldingAnalysisResult {
        Log.i(TAG, "开始实仓持仓评估...")

        // 1. 读取大盘分析结论
        val marketResult = context.getStageOutput<AMarketAnalysisEngine.MarketAnalysisResult>("n_a_market")
        val marketSummary = marketResult?.summary ?: "大盘数据不可用"
        val marketCondition = when {
            marketResult == null -> "未知"
            marketResult.isTopDanger -> "危险"
            marketResult.isTrendUp -> "多头"
            marketResult.isBottomConfirmed -> "触底"
            else -> "震荡"
        }

        // 1.5 读取新闻力度（如果有的话）
        val newsScore = context.getStageOutput<Int>("n_news")
        if (newsScore != null) {
            context.log("n_rh_eval", "新闻力度: $newsScore")
        }

        // 2. 读取持仓数据
        val db = StockDatabase.getInstance(context.androidContext)
        val realPositions = withContext(Dispatchers.IO) {
            try { db.realPositionDao().getAllActive() } catch (_: Exception) { emptyList<RealPositionEntity>() }
        }
        val orders = withContext(Dispatchers.IO) {
            try {
                db.strategyTradeOrderDao().getRecent(500)
                    .filter { it.status == "BUYING" || it.status == "PENDING" || it.status == "HOLDING" }
            } catch (_: Exception) { emptyList<StrategyTradeOrderEntity>() }
        }

        val today = LocalDate.now()

        // 3. 逐持仓分析
        val details = buildString {
            // 真实持仓
            if (realPositions.isNotEmpty()) {
                appendLine("── 真实持仓 (${realPositions.size} 只) ──")
                for (p in realPositions) {
                    val analysis = analyzeHolding(context, p.stockCode, p.avgBuyPrice, today)
                    val buyDate = try { LocalDate.parse(p.buyDate) } catch (_: Exception) { today }
                    val daysHeld = ChronoUnit.DAYS.between(buyDate, today).toInt().coerceAtLeast(0)
                    appendLine("  ${p.stockName}(${p.stockCode}) ${daysHeld}天 ¥${"%.2f".format(p.avgBuyPrice)}")
                    appendLine("    → ${analysis}")
                }
            }
            // 策略持仓
            if (orders.isNotEmpty()) {
                appendLine("── 策略持仓 (${orders.size} 笔) ──")
                for (o in orders.take(15)) {
                    val analysis = analyzeHolding(context, o.stockCode, o.buyPrice, today)
                    val buyDate = try { LocalDate.parse(o.tradeDate) } catch (_: Exception) { today }
                    val daysHeld = ChronoUnit.DAYS.between(buyDate, today).toInt().coerceAtLeast(0)
                    val pnl = o.profitPct
                    val pnlStr = if (pnl >= 0) "+${"%.2f".format(pnl)}%" else "${"%.2f".format(pnl)}%"
                    appendLine("  ${o.stockName}(${o.stockCode}) ${daysHeld}天 $pnlStr")
                    appendLine("    → ${analysis}")
                }
                if (orders.size > 15) appendLine("  ... 共 ${orders.size} 笔")
            }
            if (realPositions.isEmpty() && orders.isEmpty()) {
                appendLine("暂无持仓")
            }
        }

        // 4. 整体建议
        val advice = buildOverallAdvice(marketCondition, marketResult, realPositions.size + orders.size, newsScore)

        val result = RealHoldingAnalysisResult(
            marketSummary = marketSummary,
            suggestedPeriod = marketResult?.suggestedPeriod?.label ?: "短线",
            suggestedPositionPct = marketResult?.suggestedPositionPct ?: 40,
            holdingCount = realPositions.size + orders.size,
            holdingDetails = details.trimEnd(),
            overallAdvice = advice
        )

        Log.i(TAG, "实仓评估完成: 大盘=$marketCondition, 持仓=${result.holdingCount}只")
        return result
    }

    /**
     * 分析单只持仓：读取 K 线 → 判断趋势 → 生成操作建议
     */
    private suspend fun analyzeHolding(
        context: PipelineContext,
        stockCode: String,
        buyPrice: Double,
        today: LocalDate
    ): String = withContext(Dispatchers.IO) {
        try {
            val db = StockDatabase.getInstance(context.androidContext)
            val snapshots = db.dailySnapshotDao().getByCode(stockCode, 30).sortedBy { it.date }
            if (snapshots.size < 5) return@withContext "数据不足，建议观望"

            val latest = snapshots.last()
            val currentPrice = latest.close
            val pnlPct = if (buyPrice > 0) (currentPrice - buyPrice) / buyPrice * 100 else 0.0

            val closes = snapshots.map { it.close }
            val ma5 = closes.takeLast(5).average()
            val ma10 = closes.takeLast(10).average()
            val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else ma10

            val aboveMa5 = currentPrice > ma5
            val aboveMa10 = currentPrice > ma10
            val maBullish = ma5 > ma10 && ma10 > ma20
            val maBearish = ma5 < ma10 && ma10 < ma20

            val todayVol = latest.volume.toDouble()
            val avgVol5 = snapshots.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
            val volRatio = if (avgVol5 > 0) todayVol / avgVol5 else 1.0

            val recent3Change = if (closes.size >= 4) {
                (closes.last() - closes[closes.size - 4]) / closes[closes.size - 4] * 100
            } else 0.0

            // ── 交易纪律侦测 ──
            val prevClose = if (closes.size >= 2) closes[closes.size - 2] else currentPrice
            val gapUpPct = if (prevClose > 0) (latest.open - prevClose) / prevClose * 100 else 0.0
            val isHighOpenChase = gapUpPct > 5.0  // 纪律一：高开>5%不追
            val nearMa5 = ma5 > 0 && Math.abs(currentPrice - ma5) / ma5 * 100 < 2.0  // 纪律一：MA5附近低吸
            val bodyPct = if (latest.open > 0) (latest.close - latest.open) / latest.open * 100 else 0.0
            val isBigYin = bodyPct < -3.0  // 大阴线
            val upperShadow = latest.high - maxOf(latest.open, latest.close)
            val bodySize = Math.abs(latest.close - latest.open)
            val isLongUpperShadow = bodySize > 0 && upperShadow > bodySize * 2.0  // 长上影线
            val isLimitUp = latest.close >= prevClose * 1.095  // 涨停
            val isLimitUpOpen = isLimitUp && latest.close < latest.high  // 开板
            val isAtHigh = currentPrice > ma20 * 1.15  // 高位（远离MA20）
            val isBigYinAtHigh = isAtHigh && isBigYin  // 高位大阴

            val sb = StringBuilder()

            // 操作建议（融入交易纪律）
            val advice = when {
                // 纪律四：止损-10%
                pnlPct <= -10 -> "🔴 止损清仓(${String.format("%.1f", pnlPct)}%)(纪律四)"
                // 纪律三：高位大阴必卖
                isBigYinAtHigh -> "🔴 高位大阴，建议减仓(纪律三)"
                // 纪律三：长上影线必卖
                isLongUpperShadow && isAtHigh -> "🔴 高位长上影，建议减仓(纪律三)"
                // 纪律三：涨停开板必卖
                isLimitUpOpen -> "🟡 涨停开板，注意风险(纪律三)"
                // 纪律三：赚钱趋势减弱→止盈
                pnlPct >= 15 && !maBullish -> "🟢 止盈减仓(${String.format("%.1f", pnlPct)}%，趋势减弱)(纪律三)"
                // 纪律一：高开>5%不追
                isHighOpenChase -> "⚠ 高开${String.format("%.1f", gapUpPct)}%，不追高(纪律一)"
                // 纪律一：MA5附近低吸信号
                nearMa5 && maBullish -> "📈 MA5附近低吸机会，持有/加仓(纪律一)"
                // 原趋势判断
                maBearish && !aboveMa10 && volRatio > 1.5 && recent3Change < -5 -> "📉 反T机会(超跌放量)"
                maBearish && !aboveMa10 -> "⚠ 空头排列，建议减仓或反T"
                maBullish && aboveMa5 && volRatio > 1.3 -> "📈 多头放量，持有/加仓"
                maBullish && aboveMa5 -> "📈 多头持有，可做T降成本"
                aboveMa5 && !aboveMa10 -> "🔄 短线反弹，做T为主"
                !aboveMa5 && aboveMa10 -> "🔄 回调MA10附近，可做T或观望"
                else -> "⏳ 震荡观望"
            }

            sb.append(advice)
            sb.append(" | 现价${String.format("%.2f", currentPrice)} 盈亏${String.format("%.1f", pnlPct)}%")
            sb.append(" | MA5${String.format("%.0f", ma5)}${if (aboveMa5) "↑" else "↓"}")
            if (volRatio > 1.3) {
                sb.append(" | 放量${String.format("%.1f", volRatio)}x")
            }
            sb.toString()
        } catch (e: Exception) {
            "分析失败: ${e.message}"
        }
    }

    private fun buildOverallAdvice(
        marketCondition: String,
        marketResult: AMarketAnalysisEngine.MarketAnalysisResult?,
        holdingCount: Int,
        newsScore: Int? = null
    ): String {
        if (holdingCount == 0) return "暂无持仓，等待建仓机会"

        val newsHint = when {
            newsScore == null -> ""
            newsScore >= 70 -> " | 新闻面偏多📰+"
            newsScore <= 30 -> " | 新闻面偏空📰-"
            else -> " | 新闻面中性"
        }

        return when (marketCondition) {
            "危险" -> "⚠️ 大盘逃顶信号，建议收紧止损，仓位降至 ${marketResult?.suggestedPositionPct ?: 10}% 以下$newsHint"
            "多头" -> "✅ 大盘多头排列，持仓可继续持有，仓位可达 ${marketResult?.suggestedPositionPct ?: 70}%$newsHint"
            "触底" -> "🔄 大盘底部确认，可逐步建仓，仓位 ${marketResult?.suggestedPositionPct ?: 50}%$newsHint"
            else -> "⏳ 大盘震荡，高抛低吸为主，仓位 ${marketResult?.suggestedPositionPct ?: 40}%$newsHint"
        }
    }
}
