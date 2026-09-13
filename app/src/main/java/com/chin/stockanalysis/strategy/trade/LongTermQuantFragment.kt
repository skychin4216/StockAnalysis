package com.chin.stockanalysis.strategy.trade

import android.util.Log
import android.view.View
import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * ## 长线量化 Tab — 持仓 6 月到 1 年+，价值投资
 *
 * 核心特点：
 * - 策略池：LONG 周期（低估值、基本面筛选、机构增持、行业龙头护城河）
 * - 持仓周期：6 个月 ~ 1 年+
 * - 最大持仓：5 只
 * - 卖出规则：基本面恶化 / 估值过高
 * - 数据频率：周K + 季报
 * - 分析重心：深度基本面
 *
 * onCreateView / initEngine / DAG Pipeline / 回溯测试 已由基类 QuantFragmentBase 统一提供。
 */
class LongTermQuantFragment : QuantFragmentBase() {

    companion object {
        private const val TAG = "LongTermQuant"
        private const val MAX_HOLDINGS = 5
        private const val OVERVALUED_PE = 80.0
        private const val OVERVALUED_PB = 10.0
    }

    override fun getQuantType() = "LongTermQuant"
    override val positionTitlePrefix = "长线"
    override fun getDefaultUseCaseId() = "long_term"

    override fun onBuildClick(saveAsAiOnly: Boolean) {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.LONG,
            useCaseId = pendingUseCaseId ?: "long_term",
            orderType = "long_term",
            importDays = 60,
            titlePrefix = "长线",
            saveAsAiOnly = saveAsAiOnly,
            seedStageOutputs = pendingSeedStageOutputs,
            parallelMode = pendingParallelMode
        )
    }

    override fun onFittingClick() {
        showFittingParamsReport(titlePrefix = "长线")
    }

    override fun onBacktrackClick() {
        runHistoricalBacktrack(
            holdingPeriod = HoldingPeriod.LONG,
            tradingDays = 60,
            titlePrefix = "长线",
            extraInfo = "持仓: 6月-1年+ | 卖出: 基本面恶化 / 估值过高"
        )
    }

    override fun onClearClick() { clearData() }

    // ── buildUI ──

    override fun buildUI() {
        addTitleRow(getString(com.chin.stockanalysis.R.string.title_long_system))
        rootLayout.addView(createExecLogPanel())
        rootLayout.addView(createButtonRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())

        refreshPositions()
    }

    override fun getPeriodTipText(): String = "💎 持仓6月-1年+ | 最多${MAX_HOLDINGS}只 | 深度基本面"
}
