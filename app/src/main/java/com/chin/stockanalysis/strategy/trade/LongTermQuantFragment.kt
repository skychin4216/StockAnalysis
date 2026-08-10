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

    override fun onBuildClick() {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.LONG,
            useCaseId = "long_term",
            orderType = "long_term",
            importDays = 60,
            titlePrefix = "长线"
        )
    }

    override fun onFittingClick() {
        showDialog("长线拟合提示",
            "长线策略（持仓6月-1年+）基于深度基本面分析，参数稳定。\n\n" +
            "核心策略：\n" +
            "• 低估值 — PE/PB 历史分位筛选\n" +
            "• 基本面三层筛选 — ROE/负债率/现金流\n" +
            "• 机构增持 — 高ROE+低负债+稳健现金流\n" +
            "• 行业龙头护城河 — 技术壁垒+龙头地位+高毛利\n\n" +
            "卖出条件：\n" +
            "• 基本面恶化（ROE 连续下滑）\n" +
            "• 估值过高（PE > $OVERVALUED_PE 或 PB > $OVERVALUED_PB）\n" +
            "• 行业格局发生重大变化")
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

        val (configRow, _, _) = createDatePickerRow(
            tipText = "💎 持仓6月-1年+ | 最多${MAX_HOLDINGS}只 | 深度基本面",
            tipColor = "#1565C0"
        )
        rootLayout.addView(configRow)
        rootLayout.addView(createProgressRow())
        rootLayout.addView(createButtonRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())

        refreshPositions()
    }
}
