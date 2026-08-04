package com.chin.stockanalysis.strategy.trade

import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 超短線量化 Tab — 持倉 1 天，T+1 賣出
 *
 * 核心特點：
 * - 策略池：ULTRA_SHORT 週期（尾盤低吸、早盤追漲）
 * - 持倉週期：1 天（T+1 自動賣出）
 * - 最大持倉：3 只
 * - 止損 -2% / 止盈 +3%
 * - 無 AI 精選（時效優先）
 * - 建倉時機：14:30 尾盤低吸 / 開盤 30 分鐘追漲
 *
 * onCreateView / initEngine / DAG Pipeline / 回溯測試 已由基類 QuantFragmentBase 統一提供。
 */
class UltraShortQuantFragment : QuantFragmentBase() {

    companion object {
        private const val TAG = "UltraShortQuant"
        private const val DEFAULT_MAX_HOLDINGS = 3
        private const val DEFAULT_STOP_LOSS_PCT = -2.0
        private const val DEFAULT_TAKE_PROFIT_PCT = 3.0
    }

    private fun resolveStopLossPct(): Double {
        val vals = engine?.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
            ?.mapNotNull { it.defaultStopLoss } ?: emptyList()
        return vals.maxOrNull()?.toDouble()?.times(100) ?: DEFAULT_STOP_LOSS_PCT
    }

    private fun resolveTakeProfitPct(): Double {
        val vals = engine?.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
            ?.mapNotNull { it.defaultTakeProfit } ?: emptyList()
        return vals.minOrNull()?.toDouble()?.times(100) ?: DEFAULT_TAKE_PROFIT_PCT
    }

    private fun resolveMaxHoldings(): Int {
        val vals = engine?.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
            ?.map { it.maxPositions } ?: emptyList()
        return vals.minOrNull() ?: DEFAULT_MAX_HOLDINGS
    }

    override fun getQuantType() = "UltraShortQuant"
    override val positionTitlePrefix = "超短線"
    override val showMultiDayPrices = false
    override fun getDefaultUseCaseId() = "ultra_short"

    override fun onBuildClick() {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.ULTRA_SHORT,
            useCaseId = "ultra_short",
            orderType = "ultra_short",
            importDays = 30,
            titlePrefix = "超短線",
            onComplete = { checkT1AutoSell() }
        )
    }

    override fun onFittingClick() {
        showDialog("超短線擬合提示",
            "超短線策略（持倉1天）參數固定，無需擬合調優。\n\n" +
            "核心參數：\n" +
            "• 止損: ${resolveStopLossPct()}%\n" +
            "• 止盈: +${resolveTakeProfitPct()}%\n" +
            "• 最大持倉: ${resolveMaxHoldings()} 只\n" +
            "• 持倉週期: 1天 (T+1)\n\n" +
            "如需調整，請在回溯測試中驗證不同參數組合。")
    }

    override fun onBacktrackClick() {
        runHistoricalBacktrack(
            holdingPeriod = HoldingPeriod.ULTRA_SHORT,
            tradingDays = 30,
            titlePrefix = "超短線",
            extraInfo = "持倉: 1天 | 止損: ${resolveStopLossPct()}% | 止盈: +${resolveTakeProfitPct()}%"
        )
    }

    override fun onClearClick() { clearData() }

    // ── buildUI ──

    override fun buildUI() {
        addTitleRow("⚡ 超短線量化系統 (T+1 賣出，止損-2%/止盈+3%)")

        val (configRow, _, _) = createDatePickerRow(
            tipText = "⚡ 持倉1天 | 最多${resolveMaxHoldings()}只 | 止損${resolveStopLossPct()}%/止盈+${resolveTakeProfitPct()}%",
            mainBoardDefault = true
        )
        rootLayout.addView(configRow)
        rootLayout.addView(createProgressRow())
        rootLayout.addView(createButtonRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())

        refreshPositions()
    }

    // ═══════════════════════════════════════
    // T+1 自動賣出（超短線專有）
    // ═══════════════════════════════════════

    /**
     * T+1 自動賣出：
     * - 建倉日早於今日（T+1 到期）→ 次日集合競價無論盈虧強制清倉
     * - 當日建倉 → 僅在觸發止損/止盈時賣出
     */
    private fun checkT1AutoSell() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == "UltraShortQuant" &&
                        (it.status == "BUYING" || it.status == "PENDING") }

                if (orders.isEmpty()) return@launch

                val stopLossPct = resolveStopLossPct()
                val takeProfitPct = resolveTakeProfitPct()

                val realtime = try {
                    com.chin.stockanalysis.stock.data.StockDataSourceFactory
                        .createDefaultRepository(requireContext().applicationContext)
                        .getRealtime(orders.map { it.stockCode })
                } catch (e: Exception) {
                    Log.w(TAG, "T+1 實時行情獲取失敗: ${e.message}"); emptyMap()
                }

                val today = TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                var sellCount = 0
                var forcedCount = 0
                for (order in orders) {
                    val currentPrice = realtime[order.stockCode]?.price ?: continue
                    if (currentPrice <= 0) continue
                    val pnlPct = (currentPrice - order.buyPrice) / order.buyPrice * 100

                    val isT1Due = order.tradeDate < today
                    val hitStop = pnlPct <= stopLossPct || pnlPct >= takeProfitPct

                    if (isT1Due || hitStop) {
                        db.strategyTradeOrderDao().updateSellInfo(
                            id = order.id, status = "SOLD",
                            sellPrice = currentPrice,
                            sellTime = today + " " + java.time.LocalTime.now().toString().take(8),
                            profitPct = pnlPct
                        )
                        sellCount++
                        if (isT1Due) forcedCount++
                        Log.i(TAG, "[UltraShort] T+1 賣出: ${order.stockName} " +
                            "盈虧=${"%.2f".format(pnlPct)}% ${if (isT1Due) "(次日強制清倉)" else "(止損/止盈)"}")
                    }
                }
                if (sellCount > 0) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚡ T+1 賣出: ${sellCount} 只 (強制清倉 ${forcedCount})"
                        refreshPositions()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "T+1 賣出檢查失敗: ${e.message}")
            }
        }
    }

    // ── 覆寫賣出評估：使用超短線止損/止盈規則 ──

    override fun showTradeEvaluationMenu(anchor: View) {
        val items = arrayOf(
            "🔄 做T信號",
            "💰 賣出評估",
            "📈 買入評估",
            "💎 基本面檢查",
            "⚡ T+1 賣出檢查",
            "📊 賣出績效",
            "⚡ 執行賣出"
        )
        val titleView = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 16)
            addView(android.widget.TextView(requireContext()).apply {
                text = "💰 買賣評估（超短線）"; textSize = 18f
                setTextColor(android.graphics.Color.parseColor("#222222"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(android.widget.TextView(requireContext()).apply {
                text = "🔄"; textSize = 20f
                setPadding(16, 0, 0, 0)
                isClickable = true; isFocusable = true
                setOnClickListener { runAllEvaluations() }
            })
        }
        AlertDialog.Builder(requireContext())
            .setCustomTitle(titleView)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTTradeMenu()
                    1 -> runAutoSellEvaluation()
                    2 -> showBuyEvaluation()
                    3 -> checkFundamentalHealth()
                    4 -> checkT1AutoSell()
                    5 -> showSellPerformance()
                    6 -> executeAutoSell()
                }
            }
            .setNegativeButton("關閉", null)
            .show()
    }
}
