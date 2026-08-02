package com.chin.stockanalysis.strategy.trade

import android.util.Log
import android.view.View
import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * ## 長線量化 Tab — 持倉 6 月到 1 年+，價值投資
 *
 * 核心特點：
 * - 策略池：LONG 週期（低估值、基本面篩選、機構增持、行業龍頭護城河）
 * - 持倉週期：6 個月 ~ 1 年+
 * - 最大持倉：5 只
 * - 賣出規則：基本面惡化 / 估值過高
 * - 數據頻率：週K + 季報
 * - 分析重心：深度基本面
 *
 * onCreateView / initEngine / DAG Pipeline / 回溯測試 已由基類 QuantFragmentBase 統一提供。
 */
class LongTermQuantFragment : QuantFragmentBase() {

    companion object {
        private const val TAG = "LongTermQuant"
        private const val MAX_HOLDINGS = 5
        private const val OVERVALUED_PE = 80.0
        private const val OVERVALUED_PB = 10.0
    }

    override fun getQuantType() = "LongTermQuant"
    override val positionTitlePrefix = "長線"
    override fun getDefaultUseCaseId() = "long_term"

    override fun onBuildClick() {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.LONG,
            useCaseId = "long_term",
            orderType = "long_term",
            importDays = 60,
            titlePrefix = "長線"
        )
    }

    override fun onFittingClick() {
        showDialog("長線擬合提示",
            "長線策略（持倉6月-1年+）基於深度基本面分析，參數穩定。\n\n" +
            "核心策略：\n" +
            "• 低估值 — PE/PB 歷史分位篩選\n" +
            "• 基本面三層篩選 — ROE/負債率/現金流\n" +
            "• 機構增持 — 高ROE+低負債+穩健現金流\n" +
            "• 行業龍頭護城河 — 技術壁壘+龍頭地位+高毛利\n\n" +
            "賣出條件：\n" +
            "• 基本面惡化（ROE 連續下滑）\n" +
            "• 估值過高（PE > $OVERVALUED_PE 或 PB > $OVERVALUED_PB）\n" +
            "• 行業格局發生重大變化")
    }

    override fun onBacktrackClick() {
        runHistoricalBacktrack(
            holdingPeriod = HoldingPeriod.LONG,
            tradingDays = 60,
            titlePrefix = "長線",
            extraInfo = "持倉: 6月-1年+ | 賣出: 基本面惡化 / 估值過高"
        )
    }

    override fun onClearClick() { clearData() }

    // ── buildUI ──

    override fun buildUI() {
        addTitleRow("💎 長線量化系統 (價值投資，持倉6月-1年+)")

        val (configRow, _, _) = createDatePickerRow(
            tipText = "💎 持倉6月-1年+ | 最多${MAX_HOLDINGS}只 | 深度基本面",
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
