package com.chin.stockanalysis.agent.pipeline.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.chin.stockanalysis.agent.pipeline.AgentPipelineOrchestrator
import com.chin.stockanalysis.agent.pipeline.PipelineContext
import com.chin.stockanalysis.agent.pipeline.PipelineResult
import com.chin.stockanalysis.agent.pipeline.PipelineStep

/**
 * 7 步流水線進度面板
 *
 * 展示 F → 3 → 1 → 2 → 5 → D → 4 的執行進度，
 * 每步顯示狀態圖標、名稱、分析摘要。
 * Agent 2 完成後顯示打分徽章，Agent 5 顯示風控徽章，Agent D 顯示倉位微調。
 */
class PipelineProgressView(context: Context) : LinearLayout(context) {

    companion object {
        private const val COLOR_PENDING = "#E0E0E0"
        private const val COLOR_RUNNING = "#1565C0"
        private const val COLOR_DONE = "#2E7D32"
        private const val COLOR_ERROR = "#C62828"
        private const val COLOR_SCORE_HIGH = "#E65100"
        private const val COLOR_SCORE_MID = "#F9A825"
        private const val COLOR_SCORE_LOW = "#757575"
        private const val COLOR_RISK_LOW = "#2E7D32"
        private const val COLOR_RISK_MID = "#F9A825"
        private const val COLOR_RISK_HIGH = "#C62828"
    }

    private val stepViews = mutableMapOf<Int, StepCard>()
    private val resultContainer: LinearLayout

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(8, 8, 8, 8)
        setBackgroundColor(Color.parseColor("#FAFAFA"))

        // 標題
        addView(TextView(context).apply {
            text = "🧠 AI 智能體流水線 (F→3→1→2→5→D→4)"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        // 步驟卡片容器
        val stepsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (step in AgentPipelineOrchestrator.STEPS) {
            val card = StepCard(context, step)
            stepViews[step.order] = card
            stepsContainer.addView(card)
        }
        addView(stepsContainer)

        // 分割線
        addView(View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { topMargin = 8 }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        // 最終結果區
        resultContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
        }
        addView(resultContainer)
    }

    /**
     * 標記步驟開始
     */
    fun markStepStart(_stepIndex: Int, step: PipelineStep) {
        stepViews[step.order]?.markRunning()
    }

    /**
     * 標記步驟完成，更新摘要和徽章
     */
    fun markStepComplete(stepIndex: Int, step: PipelineStep, ctx: PipelineContext) {
        val card = stepViews[step.order] ?: return
        val summary = ctx.stepAnalyses[stepIndex] ?: ""

        card.markDone(summary)

        // Agent 2 打分徽章
        if (step.isScorer && ctx.chainScore != null) {
            card.setScoreBadge(ctx.chainScore!!.totalScore, ctx.chainScore!!.barrierLevel)
        }

        // Agent 5 風控徽章
        if (step.canHedge && ctx.riskResult != null) {
            card.setRiskBadge(ctx.riskResult!!.riskLevel, ctx.riskResult!!.deductions)
        }

        // Agent D 倉位徽章
        if (step.isAuxiliary && ctx.sentimentResult != null) {
            card.setPositionBadge(ctx.sentimentResult!!.positionAdjust)
        }
    }

    /**
     * 標記步驟錯誤
     */
    fun markStepError(stepIndex: Int, error: String) {
        val order = AgentPipelineOrchestrator.STEPS.getOrNull(stepIndex)?.order ?: return
        stepViews[order]?.markError(error)
    }

    /**
     * 顯示最終結果
     */
    fun showResult(result: PipelineResult) {
        resultContainer.removeAllViews()
        resultContainer.visibility = View.VISIBLE

        if (result.errorMessage != null) {
            resultContainer.addView(TextView(context).apply {
                text = "❌ 流水線錯誤: ${result.errorMessage}"
                textSize = 12f
                setTextColor(Color.parseColor(COLOR_ERROR))
            })
            return
        }

        val stock = result.stocks.firstOrNull()
        if (stock == null) {
            resultContainer.addView(TextView(context).apply {
                text = "⚠️ 無結果"
                textSize = 12f
                setTextColor(Color.parseColor("#757575"))
            })
            return
        }

        // 最終判定
        val passedText = if (stock.passed) "✅ 通過全部流水線" else "❌ 未通過流水線"
        val passedColor = if (stock.passed) COLOR_DONE else COLOR_ERROR

        resultContainer.addView(TextView(context).apply {
            text = passedText
            textSize = 13f
            setTextColor(Color.parseColor(passedColor))
            setTypeface(null, Typeface.BOLD)
        })

        // 綜合摘要
        val sb = StringBuilder()
        stock.chainScore?.let { sb.append("打分: ${it.totalScore}/100 | ") }
        stock.riskResult?.let { sb.append("風控: ${it.riskLevel} | ") }
        stock.sentimentResult?.let { sb.append("倉位: ${it.positionAdjust} | ") }
        stock.tradePlan?.let { sb.append("止損: ${it.stopLoss}") }
        if (sb.isNotEmpty()) {
            resultContainer.addView(TextView(context).apply {
                text = sb.toString()
                textSize = 11f
                setTextColor(Color.parseColor("#666666"))
                setPadding(0, 4, 0, 0)
            })
        }
    }

    /**
     * 重置所有步驟狀態
     */
    fun reset() {
        stepViews.values.forEach { it.reset() }
        resultContainer.removeAllViews()
        resultContainer.visibility = View.GONE
    }

    // ═══════════════════════════════════════
    // 單步卡片
    // ═══════════════════════════════════════

    private class StepCard(context: Context, private val step: PipelineStep) : LinearLayout(context) {

        private val iconTv: TextView
        private val nameTv: TextView
        private val summaryTv: TextView
        private val badgeTv: TextView

        init {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 2
                bottomMargin = 2
            }

            iconTv = TextView(context).apply {
                text = "⬜"
                textSize = 14f
                setPadding(0, 0, 8, 0)
            }
            addView(iconTv)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }

            nameTv = TextView(context).apply {
                text = step.name
                textSize = 12f
                setTextColor(Color.parseColor("#333333"))
                setTypeface(null, Typeface.BOLD)
            }
            textCol.addView(nameTv)

            summaryTv = TextView(context).apply {
                text = "等待中..."
                textSize = 10f
                setTextColor(Color.parseColor("#AAAAAA"))
                maxLines = 2
                setPadding(0, 2, 0, 0)
            }
            textCol.addView(summaryTv)
            addView(textCol)

            badgeTv = TextView(context).apply {
                text = ""
                textSize = 10f
                setPadding(8, 2, 0, 2)
                visibility = View.GONE
            }
            addView(badgeTv)
        }

        fun markRunning() {
            iconTv.text = "🔄"
            summaryTv.text = "執行中..."
            summaryTv.setTextColor(Color.parseColor(COLOR_RUNNING))
        }

        fun markDone(summary: String) {
            iconTv.text = "✅"
            summaryTv.text = summary.take(80)
            summaryTv.setTextColor(Color.parseColor(COLOR_DONE))
        }

        fun markError(error: String) {
            iconTv.text = "❌"
            summaryTv.text = error.take(60)
            summaryTv.setTextColor(Color.parseColor(COLOR_ERROR))
        }

        fun setScoreBadge(score: Int, _barrierLevel: String) {
            badgeTv.visibility = View.VISIBLE
            val color = when {
                score >= 70 -> COLOR_SCORE_HIGH
                score >= 40 -> COLOR_SCORE_MID
                else -> COLOR_SCORE_LOW
            }
            badgeTv.text = "${score}分"
            badgeTv.setTextColor(Color.parseColor(color))
            badgeTv.setTypeface(null, Typeface.BOLD)
            badgeTv.textSize = 16f
        }

        fun setRiskBadge(riskLevel: String, _deductions: List<com.chin.stockanalysis.agent.pipeline.RiskDeduction>) {
            badgeTv.visibility = View.VISIBLE
            val color = when (riskLevel) {
                "低" -> COLOR_RISK_LOW
                "中" -> COLOR_RISK_MID
                else -> COLOR_RISK_HIGH
            }
            val icon = when (riskLevel) {
                "低" -> "🟢"
                "中" -> "🟡"
                else -> "🔴"
            }
            badgeTv.text = "$icon $riskLevel"
            badgeTv.setTextColor(Color.parseColor(color))
            badgeTv.setTypeface(null, Typeface.BOLD)
            badgeTv.textSize = 12f
        }

        fun setPositionBadge(position: String) {
            badgeTv.visibility = View.VISIBLE
            badgeTv.text = "📊 $position"
            badgeTv.setTextColor(Color.parseColor("#1565C0"))
            badgeTv.textSize = 11f
        }

        fun reset() {
            iconTv.text = "⬜"
            summaryTv.text = "等待中..."
            summaryTv.setTextColor(Color.parseColor("#AAAAAA"))
            badgeTv.visibility = View.GONE
            badgeTv.text = ""
        }
    }
}
