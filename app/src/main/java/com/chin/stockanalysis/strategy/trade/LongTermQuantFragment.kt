package com.chin.stockanalysis.strategy.trade

import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // ── 覆寫賣出評估：長線使用基本面/估值規則 ──

    override fun showSellMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "💎 基本面檢查")
        popup.menu.add(0, 2, 0, "💰 賣出評估")
        popup.menu.add(0, 3, 0, "⚡ 執行賣出")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> checkFundamentalHealth()
                2 -> runAutoSellEvaluation()
                3 -> executeAutoSell()
            }
            true
        }
        popup.show()
    }

    /**
     * 基本面健康檢查：檢查長線持倉的基本面是否惡化
     */
    private fun checkFundamentalHealth() {
        progressBar.visibility = View.VISIBLE
        statusTv.text = "💎 正在檢查基本面..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == "LongTermQuant" &&
                        (it.status == "BUYING" || it.status == "PENDING") }

                if (orders.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "💎 無長線持倉"
                        showDialog("基本面檢查", "暫無長線持倉，無需檢查。")
                    }
                    return@launch
                }

                // 獲取實時行情（含基本面數據）
                val realtimeMap = try {
                    com.chin.stockanalysis.stock.data.StockDataSourceFactory
                        .createDefaultRepository(requireContext().applicationContext)
                        .getRealtime(orders.map { it.stockCode })
                } catch (e: Exception) {
                    Log.w(TAG, "實時行情獲取失敗: ${e.message}"); emptyMap()
                }

                val sb = StringBuilder()
                sb.appendLine("💎 長線持倉基本面檢查報告")
                sb.appendLine("持倉數: ${orders.size} 只")
                sb.appendLine()

                for (order in orders) {
                    sb.appendLine("📊 ${order.stockName} (${order.stockCode.takeLast(6)})")
                    sb.appendLine("  建倉日: ${order.tradeDate} | 成本: ¥${"%.2f".format(order.buyPrice)}")

                    val snap = realtimeMap[order.stockCode]
                    val currentPrice = snap?.price ?: order.buyPrice
                    val pnlPct = if (order.buyPrice > 0) {
                        (currentPrice - order.buyPrice) / order.buyPrice * 100
                    } else 0.0
                    val pnlStr = if (pnlPct >= 0) "+${"%.2f".format(pnlPct)}%" else "${"%.2f".format(pnlPct)}%"
                    sb.appendLine("  當前價: ¥${"%.2f".format(currentPrice)} | 盈虧: $pnlStr")

                    if (snap != null) {
                        sb.appendLine("  ── 基本面 ──")
                        if (snap.pe > 0) {
                            val peWarning = if (snap.pe > OVERVALUED_PE) " ⚠️ 估值過高" else ""
                            sb.appendLine("  PE(TTM): ${"%.1f".format(snap.pe)}$peWarning")
                        }
                        if (snap.pb > 0) {
                            val pbWarning = if (snap.pb > OVERVALUED_PB) " ⚠️ 估值過高" else ""
                            sb.appendLine("  PB: ${"%.2f".format(snap.pb)}$pbWarning")
                        }
                        if (snap.roeTTM > 0) {
                            val roeWarning = if (snap.roeTTM < 8.0) " ⚠️ ROE偏低" else " ✅"
                            sb.appendLine("  ROE: ${"%.1f".format(snap.roeTTM)}%$roeWarning")
                        }
                        if (snap.debtToAsset > 0) {
                            val debtWarning = if (snap.debtToAsset > 60.0) " ⚠️ 負債率偏高" else " ✅"
                            sb.appendLine("  負債率: ${"%.1f".format(snap.debtToAsset)}%$debtWarning")
                        }
                        if (snap.grossMarginTTM > 0) {
                            sb.appendLine("  毛利率: ${"%.1f".format(snap.grossMarginTTM)}%")
                        }
                        if (snap.pe <= 0 && snap.pb <= 0 && snap.roeTTM <= 0) {
                            sb.appendLine("  ── 基本面數據缺失 ──")
                        }
                    } else {
                        sb.appendLine("  ── 基本面數據缺失 ──")
                    }
                    sb.appendLine()
                }

                sb.appendLine("💡 賣出信號：")
                sb.appendLine("  • PE > $OVERVALUED_PE 或 PB > $OVERVALUED_PB → 估值過高")
                sb.appendLine("  • ROE < 8% 或連續下滑 → 基本面惡化")
                sb.appendLine("  • 負債率 > 60% → 財務風險增加")

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 基本面檢查完成 (${orders.size} 只)"
                    showDialog("基本面檢查報告", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 基本面檢查失敗: ${e.message?.take(40)}"
                }
            }
        }
    }
}
