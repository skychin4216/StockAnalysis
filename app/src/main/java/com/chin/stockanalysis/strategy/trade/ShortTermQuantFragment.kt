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

    companion object {
        private const val TAG = "ShortTermQuant"
        /** 共享状态中短线周期选择的 key */
        private const val STATE_KEY = "short"
        private val PERIOD_LABELS = mapOf(
            1 to "1日", 3 to "3日", 5 to "5日",
            7 to "7日", 10 to "10日", 14 to "14日"
        )
    }

    override fun getQuantType() = "ShortTermQuant"
    override val positionTitlePrefix = "短线量化"
    override fun getDefaultUseCaseId() = "short_term"

    override fun onBuildClick(saveAsAiOnly: Boolean) {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.SHORT,
            useCaseId = pendingUseCaseId ?: "short_term",
            orderType = "shortterm",
            importDays = 60,
            titlePrefix = "短线",
            saveAsAiOnly = saveAsAiOnly,
            seedStageOutputs = pendingSeedStageOutputs,
            parallelMode = pendingParallelMode
        )
    }

    override fun onFittingClick() {
        showFittingParamsReport(
            titlePrefix = "短线",
            periodLabel = "${getSelectedPeriod().takeIf { it > 0 } ?: 3}日"
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
        rootLayout.addView(createButtonRow())
        rootLayout.addView(createExecLogPanel())
        addSeparator()
        rootLayout.addView(createContentScrollArea())
    }

    override fun getPeriodTipText(): String = "📊 持仓1-14天 | 最多5只 | 技术+资金"

    override fun getPeriodOptions(): List<Pair<Int, String>> = PERIOD_LABELS.toList()

    override fun getSelectedPeriod(): Int {
        val p = QuantWorkbenchState.selectedPeriodFor(STATE_KEY, 3)
        return if (p in PERIOD_LABELS.keys) p else 3
    }

    override fun applySelectedPeriod(period: Int) {
        if (period in PERIOD_LABELS.keys) QuantWorkbenchState.setSelectedPeriod(STATE_KEY, period)
    }

    /** 供外部调用的自动触发 Pipeline */
    override fun autoRunPipeline(saveAsAiOnly: Boolean) {
        if (buildBtn.isEnabled) onBuildClick(saveAsAiOnly)
    }
}
