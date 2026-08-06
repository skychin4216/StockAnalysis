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
 * 建倉邏輯走標準 runDagPipeline() 路徑（與超短線/短線/長線一致）。
 */
class MidTermQuantFragment : QuantFragmentBase() {

    private lateinit var mainBoardSwitch: Switch

    private var screener: StockScreener? = null
    private var selectedPeriods: Set<Int> = setOf(1)

    companion object {
        private const val TAG = "MidTermQuant"
        private val PERIOD_LABELS = mapOf(1 to "当日", 3 to "近3日", 10 to "近10日",
            30 to "近30日", 50 to "近50日", 100 to "近100日")
    }

    // ── 抽象方法实现 ──

    override fun getQuantType() = "MidTermQuant"
    override fun onBuildClick() {
        runDagPipeline(HoldingPeriod.MID, "mid_term", "midterm", 60, "中線")
    }
    override fun onFittingClick() { showFittingParamsReport(titlePrefix = "中線") }
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
        addTitleRow("🤖 中线量化系统(价值投资，持仓 1-6 个月)", textSize = 18f)
        rootLayout.addView(createConfigSection())
        rootLayout.addView(createProgressRow())
        rootLayout.addView(createButtonRow())
        addSeparator()
        rootLayout.addView(createContentScrollArea())
        refreshPositions()
    }

    // ── 配置区 ──

    private fun createConfigSection(): View {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 日期選擇行（復用基類 createDatePickerRow）
        val (dateRow, _, switch) = createDatePickerRow(
            tipText = "📈 持倉1-6月 | 最多5只 | 基本面+技術面",
            tipColor = "#1565C0"
        )
        mainBoardSwitch = switch
        container.addView(dateRow)

        // 週期選擇
        container.addView(android.widget.TextView(requireContext()).apply {
            text = "📊 数据周期:"; textSize = 11f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 4, 0, 2)
        })
        val radioGroup = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        for ((period, label) in PERIOD_LABELS) {
            val rb = RadioButton(requireContext()).apply {
                text = label; textSize = 11f; id = period
                isChecked = period == selectedPeriods.firstOrNull()
                setOnCheckedChangeListener { _, isChecked -> if (isChecked) selectedPeriods = setOf(period) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = -4; marginStart = -4 }
            }
            radioGroup.addView(rb)
        }
        container.addView(radioGroup)
        return container
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
                    // 使用次日開盤價作為模擬賣出價（修正未來函數）
                    val sellPrice = nextDayStock.open
                    // 扣除賣出成本（佣金+印花稅+滑點 ≈ 0.15%）
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
                        periods = selectedPeriods.toList().sorted().ifEmpty { listOf(1) },
                        onlyMainBoard = mainBoardSwitch.isChecked, maxFitRounds = 100
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
                    buildBtn.isEnabled = true; buildBtn.text = "📈建倉"; progressBar.visibility = View.GONE
                    statusTv.text = "✅ 回溯完成: ${allReports.sumOf { it.buyOrdersAnalyzed.size }}笔订单, ${allReports.sumOf { it.missedOpportunities.size }}个遗漏机会"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    buildBtn.isEnabled = true; buildBtn.text = "📈建倉"; progressBar.visibility = View.GONE
                    statusTv.text = "❌ 回溯失败: ${e.message?.take(50)}"
                    Toast.makeText(requireContext(), "回溯失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun getNextTradingDayFromDB(date: String): String? =
        com.chin.stockanalysis.ui.TradingDayPickerView.getNextTradingDayFromDb(date) { requireContext() }

    // showFittingParams 已由基類 showFittingParamsReport 統一提供
    // showFinalPool / confirmAndClearPositions / confirmAndClearReports
    // 已由基類 QuantFragmentBase 統一提供，所有週期共用相同實現
}
