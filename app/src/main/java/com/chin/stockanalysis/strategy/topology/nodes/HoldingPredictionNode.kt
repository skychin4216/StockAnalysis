package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.market.AMarketAnalysisEngine
import com.chin.stockanalysis.strategy.topology.core.*
import com.chin.stockanalysis.strategy.trade.RealPositionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * ## 持仓预测节点
 *
 * 综合大盘环境、新闻因子、诊断结果，对每只持仓进行短期走势预测。
 *
 * ### 预测维度
 * 1. **短期趋势** (1-5日): 看涨/看平/看跌 + 置信度
 * 2. **关键价位**: 支撑位 / 阻力位
 * 3. **场景分析**: 乐观/中性/悲观场景
 *
 * ### 输入
 * - n_a_market: MarketAnalysisResult
 * - n_news: Int
 * - n_holding_diag: HoldingDiagnosticResult
 *
 * ### 输出
 * [HoldingPredictionResult]
 */
data class HoldingPredictionResult(
    val predictions: List<StockPrediction> = emptyList(),
    val actionPlan: String = "",
    val marketOutlook: String = "",
    val summary: String = ""
)

data class StockPrediction(
    val stockCode: String,
    val stockName: String,
    val currentPrice: Double,
    val trend: String,          // "看涨"/"看平"/"看跌"
    val confidence: Int,        // 0-100
    val supportPrice: Double,   // 支撑位
    val resistancePrice: Double,// 阻力位
    val bestCase: String,       // 乐观场景
    val worstCase: String,      // 悲观场景
    val action: String,         // 操作建议
    val factors: List<String>   // 影响因素
)

class HoldingPredictionNode : BaseNode<Any, HoldingPredictionResult>(
    "n_holding_predict", "持仓走势预测", NodeType.AI_PREDICTION
) {
    companion object {
        private const val TAG = "HoldingPredict"
    }

    override suspend fun execute(context: PipelineContext, input: Any): HoldingPredictionResult {
        Log.i(TAG, "开始持仓走势预测...")

        val db = StockDatabase.getInstance(context.androidContext)

        // 1. 读取上游结果
        val marketResult = context.getStageOutput<AMarketAnalysisEngine.MarketAnalysisResult>("n_a_market")
        val newsScore = context.getStageOutput<Int>("n_news") ?: 50
        val diagnostic = context.getStageOutput<HoldingDiagnosticResult>("n_holding_diag")

        // 2. 大盘展望
        val marketOutlook = buildMarketOutlook(marketResult, newsScore)

        // 3. 读取持仓
        val positions = withContext(Dispatchers.IO) {
            try { db.realPositionDao().getAllActive() } catch (_: Exception) { emptyList<RealPositionEntity>() }
        }

        if (positions.isEmpty()) {
            return HoldingPredictionResult(marketOutlook = marketOutlook, summary = "暂无持仓")
        }

        // 4. 逐股预测
        val predictions = positions.map { pos ->
            val diag = diagnostic?.holdings?.find { it.stockCode == pos.stockCode }
            predictStock(db, pos, marketResult, newsScore, diag)
        }

        // 5. 生成操作计划
        val actionPlan = buildActionPlan(predictions, diagnostic)

        // 6. 汇总
        val bullish = predictions.count { it.trend == "看涨" }
        val bearish = predictions.count { it.trend == "看跌" }
        val neutral = predictions.size - bullish - bearish
        val summary = "预测: ${bullish}只看涨, ${neutral}只震荡, ${bearish}只看跌 | $marketOutlook"

        Log.i(TAG, "预测完成: $summary")
        return HoldingPredictionResult(predictions, actionPlan, marketOutlook, summary)
    }

    private suspend fun predictStock(
        db: StockDatabase,
        pos: RealPositionEntity,
        marketResult: AMarketAnalysisEngine.MarketAnalysisResult?,
        newsScore: Int,
        diag: HoldingDiagnosis?
    ): StockPrediction = withContext(Dispatchers.IO) {
        val code = pos.stockCode
        val snaps = try {
            db.dailySnapshotDao().getByCode(code, 60).sortedBy { it.date }
        } catch (_: Exception) { emptyList() }

        if (snaps.size < 10) {
            return@withContext StockPrediction(
                code, pos.stockName, pos.avgBuyPrice,
                "看平", 30, pos.avgBuyPrice * 0.95, pos.avgBuyPrice * 1.05,
                "数据不足，无法预测", "数据不足，无法预测",
                "观望等待", listOf("K线数据不足")
            )
        }

        val closes = snaps.map { it.close }
        val volumes = snaps.map { it.volume.toDouble() }
        val highs = snaps.map { it.high }
        val lows = snaps.map { it.low }
        val currentPrice = closes.last()

        // ── 支撑位 / 阻力位 ──
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val ma20 = closes.takeLast(20).average()

        val recentLow = lows.takeLast(20).min()
        val recentHigh = highs.takeLast(20).max()

        // 支撑 = max(近期低点, MA5×0.98, MA10×0.97)
        val support = maxOf(recentLow, ma5 * 0.98, ma10 * 0.97)
        // 阻力 = min(近期高点, MA5×1.02, MA10×1.03)
        val resistance = minOf(recentHigh, ma5 * 1.02, ma10 * 1.03)

        // ── 趋势预测 ──
        val factors = mutableListOf<String>()
        var trendScore = 0  // 正=看涨, 负=看跌

        // 因子1: MA排列
        val maBullish = ma5 > ma10 && ma10 > ma20
        val maBearish = ma5 < ma10 && ma10 < ma20
        when {
            maBullish -> { trendScore += 2; factors.add("均线多头排列") }
            maBearish -> { trendScore -= 2; factors.add("均线空头排列") }
            ma5 > ma10 -> { trendScore += 1; factors.add("短期均线偏多") }
            ma5 < ma10 -> { trendScore -= 1; factors.add("短期均线偏空") }
        }

        // 因子2: 动量
        if (closes.size >= 6) {
            val mom5 = (closes.last() - closes[closes.size - 6]) / closes[closes.size - 6] * 100
            when {
                mom5 > 5 -> { trendScore += 2; factors.add("5日动量强(+${String.format("%.1f", mom5)}%)") }
                mom5 > 2 -> { trendScore += 1; factors.add("5日温和上涨") }
                mom5 < -5 -> { trendScore -= 2; factors.add("5日动量弱(${String.format("%.1f", mom5)}%)") }
                mom5 < -2 -> { trendScore -= 1; factors.add("5日温和下跌") }
            }
        }

        // 因子3: 量能
        if (volumes.size >= 10) {
            val vol5 = volumes.takeLast(5).average()
            val volPrev5 = volumes.dropLast(5).takeLast(5).average()
            val volRatio = if (volPrev5 > 0) vol5 / volPrev5 else 1.0
            val priceChange5 = if (closes.size >= 6) (closes.last() - closes[closes.size - 6]) / closes[closes.size - 6] * 100 else 0.0
            when {
                volRatio > 1.3 && priceChange5 > 0 -> { trendScore += 1; factors.add("放量上涨") }
                volRatio > 1.3 && priceChange5 < 0 -> { trendScore -= 1; factors.add("放量下跌") }
                volRatio < 0.7 && priceChange5 < 0 -> { trendScore += 1; factors.add("缩量下跌(可能洗盘)") }
            }
        }

        // 因子4: 诊断结果
        if (diag != null) {
            when {
                diag.technicalScore >= 70 -> { trendScore += 1; factors.add("技术面健康(${diag.technicalScore}分)") }
                diag.technicalScore < 30 -> { trendScore -= 1; factors.add("技术面恶化(${diag.technicalScore}分)") }
            }
            if (diag.capitalScore >= 70) { trendScore += 1; factors.add("资金面积极(${diag.capitalScore}分)") }
            if (diag.capitalScore < 30) { trendScore -= 1; factors.add("资金面消极(${diag.capitalScore}分)") }
        }

        // 因子5: 大盘环境
        if (marketResult != null) {
            when {
                marketResult.isTrendUp -> { trendScore += 1; factors.add("大盘多头") }
                marketResult.isTopDanger -> { trendScore -= 2; factors.add("大盘逃顶信号") }
                marketResult.isBottomConfirmed -> { trendScore += 1; factors.add("大盘底部确认") }
            }
        }

        // 因子6: 新闻
        when {
            newsScore >= 70 -> { trendScore += 1; factors.add("新闻面偏多") }
            newsScore <= 30 -> { trendScore -= 1; factors.add("新闻面偏空") }
        }

        // ── 趋势判定 ──
        val trend = when {
            trendScore >= 3 -> "看涨"
            trendScore >= 1 -> "偏多震荡"
            trendScore <= -3 -> "看跌"
            trendScore <= -1 -> "偏空震荡"
            else -> "震荡"
        }

        val confidence = minOf(90, 40 + abs(trendScore) * 10)

        // ── 场景分析 ──
        val bestCase = when {
            trendScore >= 3 -> "突破阻力${String.format("%.2f", resistance)}，目标涨${String.format("%.1f", (resistance / currentPrice - 1) * 100 + 3)}%"
            trendScore >= 1 -> "反弹至MA5(${String.format("%.2f", ma5)})附近，涨${String.format("%.1f", (ma5 / currentPrice - 1) * 100)}%"
            else -> "震荡企稳，等待方向选择"
        }

        val worstCase = when {
            trendScore <= -3 -> "跌破支撑${String.format("%.2f", support)}，可能跌${String.format("%.1f", (1 - support / currentPrice) * 100 + 3)}%"
            trendScore <= -1 -> "回踩支撑${String.format("%.2f", support)}，跌${String.format("%.1f", (1 - support / currentPrice) * 100)}%"
            else -> "继续震荡，注意止损位${String.format("%.2f", pos.avgBuyPrice * 0.9)}"
        }

        // ── 操作建议 ──
        val pnlPct = if (pos.avgBuyPrice > 0) (currentPrice - pos.avgBuyPrice) / pos.avgBuyPrice * 100 else 0.0
        val action = when {
            trendScore >= 3 && pnlPct > 0 -> "持有，突破阻力可加仓"
            trendScore >= 3 && pnlPct <= 0 -> "持有等待解套，突破阻力可加仓"
            trendScore >= 1 -> "持有，可做T降成本"
            trendScore <= -3 && pnlPct < -5 -> "止损减仓"
            trendScore <= -3 -> "减仓或反T"
            trendScore <= -1 -> "观望为主，反弹减仓"
            else -> "持有观望，高抛低吸"
        }

        StockPrediction(
            code, pos.stockName, currentPrice,
            trend, confidence,
            support, resistance,
            bestCase, worstCase, action,
            factors
        )
    }

    private fun buildMarketOutlook(
        marketResult: AMarketAnalysisEngine.MarketAnalysisResult?,
        newsScore: Int
    ): String {
        if (marketResult == null) return "大盘数据不可用"

        val marketHint = when {
            marketResult.isTopDanger -> "大盘逃顶信号，注意风险"
            marketResult.isTrendUp -> "大盘多头，利于持仓"
            marketResult.isBottomConfirmed -> "大盘底部确认，可逐步加仓"
            else -> "大盘震荡，灵活应对"
        }

        val newsHint = when {
            newsScore >= 70 -> "，新闻面偏多"
            newsScore <= 30 -> "，新闻面偏空"
            else -> ""
        }

        return "$marketHint$newsHint | 建议仓位${marketResult.suggestedPositionPct}%"
    }

    private fun buildActionPlan(
        predictions: List<StockPrediction>,
        diagnostic: HoldingDiagnosticResult?
    ): String {
        val sb = StringBuilder()

        // 优先处理
        val urgent = predictions.filter { it.trend == "看跌" }
        if (urgent.isNotEmpty()) {
            sb.appendLine("⚠ 优先处理:")
            for (p in urgent) {
                sb.appendLine("  ${p.stockName}: ${p.action}")
            }
        }

        // 可加仓
        val bullish = predictions.filter { it.trend == "看涨" && it.confidence >= 60 }
        if (bullish.isNotEmpty()) {
            sb.appendLine("📈 可加仓:")
            for (p in bullish) {
                sb.appendLine("  ${p.stockName}: ${p.action}")
            }
        }

        // 中性持仓
        val neutral = predictions.filter { it !in urgent && it !in bullish }
        if (neutral.isNotEmpty()) {
            sb.appendLine("📊 持有观察:")
            for (p in neutral) {
                sb.appendLine("  ${p.stockName}: ${p.action}")
            }
        }

        // 组合建议
        if (diagnostic != null) {
            sb.appendLine()
            for (s in diagnostic.portfolioHealth.suggestions) {
                sb.appendLine("💡 $s")
            }
        }

        return sb.toString().trimEnd()
    }
}
