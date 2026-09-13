package com.chin.stockanalysis.agent.pipeline.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.chin.stockanalysis.agent.core.AnalysisResult
import com.chin.stockanalysis.agent.core.AnalysisStep

/**
 * 智能体流水线进度面板
 *
 * 已解除对 AgentPipelineOrchestrator 的耦合，改用统一 [AnalysisStep] / [AnalysisResult]。
 * 步骤卡片由 [updateSteps] 动态传入（来自 DeepAnalystEngine.stepsFor）。
 */
class PipelineProgressView(context: Context) : LinearLayout(context) {
    companion object {
        private const val COLOR_RUNNING = "#1565C0"
        private const val COLOR_DONE = "#2E7D32"
        private const val COLOR_ERROR = "#C62828"
    }

    private val stepViews = mutableMapOf<Int, StepCard>()
    private val resultContainer: LinearLayout
    private val stepsContainer: LinearLayout
    private val titleTv: TextView

    init {
        orientation = VERTICAL
        setPadding(8, 8, 8, 8)
        setBackgroundColor(Color.parseColor("#FAFAFA"))
        // 标题
        titleTv = TextView(context).apply {
            text = "Agent 深度分析"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        addView(titleTv)
        // 步骤卡片容器
        stepsContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(stepsContainer)
        // 分割线
        addView(View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { topMargin = 8 }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })
        // 最终结果区
        resultContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
        }
        addView(resultContainer)
    }

    /** 动态更新步骤列表（执行前由 DeepAnalystEngine.stepsFor 提供） */
    fun updateSteps(steps: List<AnalysisStep>) {
        stepViews.clear()
        stepsContainer.removeAllViews()
        for (step in steps) {
            val card = StepCard(context, step)
            stepViews[step.order] = card
            stepsContainer.addView(card)
        }
        titleTv.text = "Agent 深度分析（${steps.size} 步）"
    }

    /** 标记步骤完成（summary 为可读摘要） */
    fun markStepComplete(step: AnalysisStep, summary: String) {
        val card = stepViews[step.order] ?: run {
            // 卡片未预建时动态补建
            val c = StepCard(context, step)
            stepViews[step.order] = c
            stepsContainer.addView(c)
            c
        }
        card.markDone(summary)
    }

    /** 标记步骤错误 */
    fun markStepError(step: AnalysisStep, error: String) {
        stepViews[step.order]?.markError(error)
    }

    /** 显示最终结果 */
    fun showResult(result: AnalysisResult) {
        resultContainer.removeAllViews()
        resultContainer.visibility = View.VISIBLE

        if (!result.success && result.errorMessage != null) {
            resultContainer.addView(TextView(context).apply {
                text = "分析失败: ${result.errorMessage}"
                textSize = 12f
                setTextColor(Color.parseColor(COLOR_ERROR))
            })
            return
        }

        // 最终判定
        val passed = result.passed ?: (result.overallScore >= 40)
        resultContainer.addView(TextView(context).apply {
            text = if (passed) "通过分析筛选" else "未通过筛选"
            textSize = 13f
            setTextColor(Color.parseColor(if (passed) COLOR_DONE else COLOR_ERROR))
            setTypeface(null, Typeface.BOLD)
        })
        // 综合摘要
        val sb = StringBuilder()
        if (result.overallScore > 0) sb.append("评分: ${result.overallScore}/100 | ")
        result.recommendation?.let { sb.append("建议: $it | ") }
        result.riskLevel?.let { sb.append("风控: $it | ") }
        result.positionPercent?.let { sb.append("仓位: $it% | ") }
        result.stopLoss?.let { sb.append("止损: $it") }
        if (sb.isNotEmpty()) {
            resultContainer.addView(TextView(context).apply {
                text = sb.toString()
                textSize = 11f
                setTextColor(Color.parseColor("#666666"))
                setPadding(0, 4, 0, 0)
            })
        }
    }

    /** 重置所有步骤状态 */
    fun reset() {
        stepViews.values.forEach { it.reset() }
        resultContainer.removeAllViews()
        resultContainer.visibility = View.GONE
    }

    // ----------------------------------------
    //  单步卡片
    // ----------------------------------------
    private class StepCard(context: Context, step: AnalysisStep) : LinearLayout(context) {
        private val iconTv: TextView
        private val nameTv: TextView
        private val summaryTv: TextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 2; bottomMargin = 2
            }
            iconTv = TextView(context).apply {
                text = "[ ]"; textSize = 14f; setPadding(0, 0, 8, 0)
            }
            addView(iconTv)
            val textCol = LinearLayout(context).apply {
                orientation = VERTICAL
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
        }

        fun markDone(summary: String) {
            iconTv.text = "[v]"
            summaryTv.text = summary.take(80)
            summaryTv.setTextColor(Color.parseColor(COLOR_DONE))
        }

        fun markError(error: String) {
            iconTv.text = "[x]"
            summaryTv.text = error.take(60)
            summaryTv.setTextColor(Color.parseColor(COLOR_ERROR))
        }

        fun reset() {
            iconTv.text = "[ ]"
            summaryTv.text = "等待中..."
            summaryTv.setTextColor(Color.parseColor("#AAAAAA"))
        }
    }
}