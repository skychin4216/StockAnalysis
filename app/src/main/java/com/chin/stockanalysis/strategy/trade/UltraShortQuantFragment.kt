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
 * ## 超短线量化 Tab — 持仓 1 天，T+1 卖出
 *
 * 核心特点：
 * - 策略池：ULTRA_SHORT 周期（尾盘低吸、早盘追涨）
 * - 持仓周期：1 天（T+1 自动卖出）
 * - 最大持仓：3 只
 * - 止损 -2% / 止盈 +3%
 * - 无 AI 精选（时效优先）
 * - 建仓时机：14:30 尾盘低吸 / 开盘 30 分钟追涨
 *
 * onCreateView / initEngine / DAG Pipeline / 回溯测试 已由基类 QuantFragmentBase 统一提供。
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
    override val positionTitlePrefix = "超短线"
    override val showMultiDayPrices = false
    override fun getDefaultUseCaseId() = "ultra_short"

    override fun onBuildClick(saveAsAiOnly: Boolean) {
        runDagPipeline(
            holdingPeriod = HoldingPeriod.ULTRA_SHORT,
            useCaseId = pendingUseCaseId ?: "ultra_short",
            orderType = "ultra_short",
            importDays = 30,
            titlePrefix = "超短线",
            onComplete = { checkT1AutoSell() },
            saveAsAiOnly = saveAsAiOnly,
            seedStageOutputs = pendingSeedStageOutputs,
            parallelMode = pendingParallelMode
        )
    }

    override fun onFittingClick() {
        showFittingParamsReport(titlePrefix = "超短线")
    }

    override fun onBacktrackClick() {
        runHistoricalBacktrack(
            holdingPeriod = HoldingPeriod.ULTRA_SHORT,
            tradingDays = 30,
            titlePrefix = "超短线",
            extraInfo = "持仓: 1天 | 止损: ${resolveStopLossPct()}% | 止盈: +${resolveTakeProfitPct()}%"
        )
    }

    override fun onClearClick() { clearData() }

    // ── buildUI ──

    override fun buildUI() {
        addTitleRow(getString(com.chin.stockanalysis.R.string.title_ultra_short_system))
        rootLayout.addView(createExecLogPanel())
        rootLayout.addView(createButtonRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())

        refreshPositions()
    }

    override fun getPeriodTipText(): String =
        "⚡ 持仓1天 | 最多${resolveMaxHoldings()}只 | 止损${resolveStopLossPct()}%/止盈+${resolveTakeProfitPct()}%"

    // ═══════════════════════════════════════
    // T+1 自动卖出（超短线专有）
    // ═══════════════════════════════════════

    /**
     * T+1 自动卖出：
     * - 建仓日早于今日（T+1 到期）→ 次日集合竞价无论盈亏强制清仓
     * - 当日建仓 → 仅在触发止损/止盈时卖出
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
                    Log.w(TAG, "T+1 实时行情获取失败: ${e.message}"); emptyMap()
                }

                val today = TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                var sellCount = 0
                var forcedCount = 0
                for (order in orders) {
                    val currentPrice = realtime[order.stockCode]?.price ?: continue
                    if (currentPrice <= 0) continue
                    // 成本价缺失/为 0 时无法计算真实盈亏，跳过以免出现 Infinity 误判止盈
                    if (order.buyPrice <= 0) continue
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
                        Log.i(TAG, "[UltraShort] T+1 卖出: ${order.stockName} " +
                            "盈亏=${"%.2f".format(pnlPct)}% ${if (isT1Due) "(次日强制清仓)" else "(止损/止盈)"}")
                    }
                }
                if (sellCount > 0) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚡ T+1 卖出: ${sellCount} 只 (强制清仓 ${forcedCount})"
                        refreshPositions()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "T+1 卖出检查失败: ${e.message}")
            }
        }
    }

    // ── 覆写卖出评估：使用超短线止损/止盈规则 ──

    /** 超短线额外菜单项：T+1 卖出检查 */
    override fun getExtraEvalMenuItems(): List<Pair<String, () -> Unit>> = listOf(
        "⚡ T+1 卖出检查" to { checkT1AutoSell() }
    )
}
