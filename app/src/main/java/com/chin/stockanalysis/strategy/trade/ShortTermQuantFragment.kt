package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * ## 短线量化 Tab — Zipline Pipeline + 定时选股 + 独立持仓
 *
 * 流程:
 *   1. Pipeline 因子计算 → 各策略独立打分
 *   2. 合并池 (多策略交集)
 *   3. AI 精选 Top3-5 → 最终推荐
 *   4. 独立持仓 (orderType="ShortTermQuant")  顶部显示
 *
 * onCreateView / initEngine / DAG Pipeline / 回溯测试 / 拟合报告 已由基类 QuantFragmentBase 统一提供。
 */
class ShortTermQuantFragment : QuantFragmentBase() {

    /** 短线周期选择（持仓 1 天 ~ 2 周） */
    private var selectedPeriods: Set<Int> = setOf(3)

    companion object {
        private const val TAG = "ShortTermQuant"
        private val PERIOD_LABELS = mapOf(
            1 to "1日", 3 to "3日", 5 to "5日",
            7 to "7日", 10 to "10日", 14 to "14日"
        )
    }

    override fun getQuantType() = "ShortTermQuant"
    override val positionTitlePrefix = "短线量化"
    override fun getDefaultUseCaseId() = "short_term"

    override fun onBuildClick() {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.SHORT,
            useCaseId = "short_term",
            orderType = "shortterm",
            importDays = 60,
            titlePrefix = "短线"
        )
    }

    override fun onFittingClick() {
        showFittingParamsReport(
            titlePrefix = "短线",
            periodLabel = selectedPeriods.joinToString(",") + "日"
        )
    }

    override fun onBacktrackClick() {
        runHistoricalBacktrack(
            holdingPeriod = null,
            tradingDays = 30,
            titlePrefix = "短线"
        )
    }

    override fun onClearClick() = clearData()

    // ── buildUI ──

    override fun buildUI() {
        addTitleRow(getString(com.chin.stockanalysis.R.string.title_short_system), textSize = 16f)

        val (configRow, _, _) = createDatePickerRow(
            tipText = "📊 持仓1-14天 | 最多5只 | 技术+资金",
            tipColor = "#1565C0"
        )
        rootLayout.addView(configRow)

        // ── 周期选择行（短线持仓 1 日 ~ 2 周） ──
        val periodRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4); setBackgroundColor(Color.WHITE)
        }
        periodRow.addView(android.widget.TextView(requireContext()).apply {
            text = "📊 周期:"; textSize = 11f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 4, 0)
        })
        val periodRadioGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        for ((period, label) in PERIOD_LABELS) {
            val rb = RadioButton(requireContext()).apply {
                text = label; textSize = 11f; id = period
                isChecked = period == selectedPeriods.firstOrNull()
                setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedPeriods = setOf(period) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = -4; marginStart = -4 }
            }
            periodRadioGroup.addView(rb)
        }
        periodRow.addView(periodRadioGroup)
        rootLayout.addView(periodRow)

        // ── 进度行 + 按钮行 + 分隔线 + 持仓区 ──
        rootLayout.addView(createButtonRow())
        rootLayout.addView(createProgressRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())
    }

    /** 供外部调用的自动触发 Pipeline */
    fun autoRunPipeline() {
        if (buildBtn.isEnabled) onBuildClick()
    }
}
