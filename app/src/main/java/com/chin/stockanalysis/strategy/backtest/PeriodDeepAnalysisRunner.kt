package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline
import kotlin.math.roundToInt

/**
 * ## 四周期深度分析（详情页「深度分析」入口）
 *
 * 参考一键建仓的四周期框架，但**只分析、不下单**（与 AI 对话框「板块多周期全面深度分析」一致）：
 * 输入个股 → 分别用 超短/短线/中线/长线 四套模板打分 →
 * 输出各周期适配度 + 周期推荐 + 综合建议。
 *
 * 打分口径：
 * - 周期适配分 = 通过（passed）：70 + 30×通过比例；未通过：50×通过比例（0~100）
 * - 综合分 = 四周期最高分
 * - 推荐周期 = 通过周期中分数最高者；四周期均未通过 → 不推荐买入（观望）
 * - 建议三档：综合分 ≥85 符合买入 / 有周期通过但偏弱 → 可小仓试仓 / 无通过 → 不建议买入
 */
class PeriodDeepAnalysisRunner {

    data class PeriodItem(
        val period: String,
        val icon: String,
        val passed: Boolean,
        val passCount: Int,
        val totalChecks: Int,
        val score: Int,
        val result: StockCheckPipeline.StockCheckResult
    )

    data class Result(
        val items: List<PeriodItem>,
        /** 推荐周期（通过周期中分数最高），四周期均未通过为 null */
        val bestPeriod: String?,
        val overallScore: Int,
        /** BUY / WATCH / HOLD */
        val recommendation: String,
        val report: String,
        val elapsedMs: Long
    )

    suspend fun analyze(context: Context, stockCode: String, stockName: String): Result {
        val t0 = System.currentTimeMillis()
        val items = PERIOD_TEMPLATES.map { (period, icon, factory) ->
            val pipeline = factory(null)
            val r = pipeline.analyze(context, stockCode)
            val score = if (r.passed) {
                70 + (30.0 * r.passCount / r.totalChecks).roundToInt()
            } else {
                (50.0 * r.passCount / r.totalChecks).roundToInt()
            }
            PeriodItem(period, icon, r.passed, r.passCount, r.totalChecks, score, r)
        }

        val best = items.filter { it.passed }.maxByOrNull { it.score }
        val overallScore = items.maxOf { it.score }
        val recommendation = when {
            best != null && overallScore >= 85 -> "BUY"
            best != null -> "WATCH"
            else -> "HOLD"
        }
        val report = buildReport(stockCode, stockName, items, best, overallScore)
        return Result(items, best?.period, overallScore, recommendation, report, System.currentTimeMillis() - t0)
    }

    private fun buildReport(
        stockCode: String,
        stockName: String,
        items: List<PeriodItem>,
        best: PeriodItem?,
        overallScore: Int
    ): String = buildString {
        appendLine("🧭 四周期深度分析 · $stockName（${stockCode.takeLast(4)}）")
        appendLine()
        for (item in items) {
            val r = item.result
            appendLine("${item.icon} [${item.period}] ${if (item.passed) "✅ 适配" else "❌ 未达标"}（${item.passCount}/${item.totalChecks}项 · ${item.score}分）")
            appendLine("   ${briefOf(r)}")
            appendLine()
        }
        appendLine("📊 综合评分：$overallScore/100")
        if (best != null) {
            appendLine("🏆 周期推荐：${best.period}（四周期中适配度最高）")
            appendLine(
                if (overallScore >= 85) "✅ 建议：符合买入条件，可关注${best.period}机会"
                else "👀 建议：${best.period}信号偏弱，可小仓位试仓或继续观察"
            )
        } else {
            appendLine("🏆 周期推荐：暂无（四周期均未达标）")
            appendLine("⛔ 建议：当前不满足买入条件（评分 $overallScore/100），不建议买入，观望为主")
        }
        appendLine("⚠️ 本次仅为分析，未生成订单，不构成投资建议")
    }

    private fun briefOf(r: StockCheckPipeline.StockCheckResult): String = buildString {
        if (r.convergenceDegree in 0.0..999.0) {
            append("粘合${"%.1f".format(r.convergenceDegree)}% ")
        }
        append("多头${if (r.bullishAligned) "✓" else "✗"} ")
        if (r.volumeRatio > 0) append("量比${"%.2f".format(r.volumeRatio)} ")
        if (r.drawdownPct > 0) append("距高-${"%.1f".format(r.drawdownPct)}% ")
        if (r.aboveYearLine) append("站上年线 ")
        if (r.threeDayNoNewLow) append("三日不新低✓ ")
        if (r.changePct != 0.0) {
            append("当日${if (r.changePct > 0) "+" else ""}${"%.2f".format(r.changePct)}% ")
        }
        if (r.direction != "OSCILLATION") append("方向:${r.direction}")
    }.trim()

    companion object {
        private data class Template(
            val period: String,
            val icon: String,
            val factory: (String?) -> StockCheckPipeline
        )

        private val PERIOD_TEMPLATES = listOf(
            Template("超短线", "⚡", { StockCheckPipeline.ultraShortParams(it) }),
            Template("短线", "🚀", { StockCheckPipeline.shortTermParams(it) }),
            Template("中线", "📈", { StockCheckPipeline.midTermParams(it) }),
            Template("长线", "💎", { StockCheckPipeline.longTermParams(it) })
        )
    }
}
