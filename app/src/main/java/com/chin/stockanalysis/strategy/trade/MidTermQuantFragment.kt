package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * ## 中线量化面板 v3.0 — 继承 QuantFragmentBase
 *
 * 共用基类的：卖出评估/执行、数据管理、持仓刷新、交易历史、导出等功能。
 * 建仓逻辑走标准 runDagPipeline() 路径（与超短线/短线/长线一致）。
 */
class MidTermQuantFragment : QuantFragmentBase() {

    private var screener: StockScreener? = null

    companion object {
        private const val TAG = "MidTermQuant"
        /** 共享状态中中线周期选择的 key */
        private const val STATE_KEY = "mid"
        private val PERIOD_LABELS = mapOf(1 to "当日", 3 to "近3日", 10 to "近10日",
            30 to "近30日", 50 to "近50日", 100 to "近100日")
    }

    // ── 抽象方法实现 ──

    override fun getQuantType() = "MidTermQuant"
    override val positionTitlePrefix = "中线"
    override fun onBuildClick(saveAsAiOnly: Boolean) {
        runDagPipeline(HoldingPeriod.MID, "mid_term", "midterm", 60, "中线", saveAsAiOnly = saveAsAiOnly)
    }
    override fun onFittingClick() { showFittingParamsReport(titlePrefix = "中线") }
    override fun onBacktrackClick() { runNextDayBacktrack() }
    override fun onClearClick() { clearData() }

    // ── 生命周期 ──

    override fun initEngine() {
        super.initEngine()
        val ctx = requireContext().applicationContext
        val repo = StockDataSourceFactory.createDefaultRepository(ctx)
        screener = StockScreener(repo, ctx)
    }

    override fun buildUI() {
        addTitleRow(getString(com.chin.stockanalysis.R.string.title_mid_system), textSize = 18f)
        rootLayout.addView(createProgressRow())
        rootLayout.addView(createButtonRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())
        refreshPositions()
    }

    override fun getPeriodTipText(): String = "📈 持仓1-6月 | 最多5只 | 基本面+技术面"

    override fun getPeriodRowLabel(): String = "📊 数据周期:"

    override fun getPeriodOptions(): List<Pair<Int, String>> = PERIOD_LABELS.toList()

    override fun getSelectedPeriod(): Int {
        val p = QuantWorkbenchState.selectedPeriodFor(STATE_KEY, 1)
        return if (p in PERIOD_LABELS.keys) p else 1
    }

    override fun applySelectedPeriod(period: Int) {
        if (period in PERIOD_LABELS.keys) QuantWorkbenchState.setSelectedPeriod(STATE_KEY, period)
    }

    override fun applyMainBoardOnly(checked: Boolean) {
        QuantWorkbenchState.mainBoardOnly = checked
    }

    /** 供外部调用的自动执行中线量化 */
    fun autoExecuteTrade() {
        if (buildBtn.isEnabled) onBuildClick()
    }

    // ═══════════════════════════════════════
    // 回溯
    // ═══════════════════════════════════════

    private fun runNextDayBacktrack() {
        val eng = engine ?: return
        val te = StrategyFittingEngine(requireContext())
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 回溯中..."
        progressBar.visibility = View.VISIBLE; statusTv.text = "正在执行回溯复盘..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val buyingOrders = db.strategyTradeOrderDao().getRecent(100).filter { it.status == "BUYING" }
                val boughtStocks = buyingOrders.map { it.stockCode }.toSet()
                for (order in buyingOrders) {
                    val nextDate = getNextTradingDayFromDB(order.tradeDate) ?: continue
                    val nextDaySnaps = db.dailySnapshotDao().getByDate(nextDate)
                    val nextDayStock = nextDaySnaps.find { it.code == order.stockCode } ?: continue
                    // 使用次日开盘价作为模拟卖出价（修正未来函数）
                    val sellPrice = nextDayStock.open
                    // 扣除卖出成本（佣金+印花税+滑点 ≈ 0.15%）
                    val netSellPrice = sellPrice * (1.0 - 0.0015)
                    val profitPct = (netSellPrice - order.buyPrice) / order.buyPrice * 100
                    db.strategyTradeOrderDao().updateSellInfo(
                        id = order.id, status = "SOLD", sellPrice = sellPrice,
                        sellTime = "$nextDate 15:00", profitPct = profitPct
                    )
                }
                val tradeDates = buyingOrders.map { it.tradeDate }.distinct()
                val allReports = mutableListOf<StrategyFittingEngine.BacktrackReport>()
                for (tradeDate in tradeDates) {
                    val config = StrategyFittingEngine.TradeSessionConfig(
                        tradeDate = tradeDate,
                        periods = listOf(getSelectedPeriod().takeIf { it > 0 } ?: 1),
                        onlyMainBoard = QuantWorkbenchState.mainBoardOnly, maxFitRounds = 100
                    )
                    allReports.add(te.backtrackAndOptimize(strategies, config, emptyList(), boughtStocks))
                }
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("📈 回溯复盘优化报告")
                    sb.appendLine("回溯日期: ${LocalDate.now()}")
                    sb.appendLine("处理交易日: ${tradeDates.joinToString(", ")}"); sb.appendLine()
                    for (r in allReports) { sb.appendLine(r.summary); sb.appendLine() }
                    showDialog("回溯复盘", sb.toString())
                    buildBtn.isEnabled = true; buildBtn.text = "📈建仓"; progressBar.visibility = View.GONE
                    statusTv.text = "✅ 回溯完成: ${allReports.sumOf { it.buyOrdersAnalyzed.size }}笔订单, ${allReports.sumOf { it.missedOpportunities.size }}个遗漏机会"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    buildBtn.isEnabled = true; buildBtn.text = "📈建仓"; progressBar.visibility = View.GONE
                    statusTv.text = "❌ 回溯失败: ${e.message?.take(50)}"
                    Toast.makeText(requireContext(), "回溯失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun getNextTradingDayFromDB(date: String): String? =
        com.chin.stockanalysis.ui.TradingDayPickerView.getNextTradingDayFromDb(date) { requireContext() }

    // showFittingParams 已由基类 showFittingParamsReport 统一提供
    // showFinalPool / confirmAndClearPositions / confirmAndClearReports
    // 已由基类 QuantFragmentBase 统一提供，所有周期共用相同实现
}
