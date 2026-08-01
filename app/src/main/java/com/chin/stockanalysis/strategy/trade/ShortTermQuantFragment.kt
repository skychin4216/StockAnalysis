package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * ## 短线量化 Tab — Zipline Pipeline + 定时选股 + 独立持仓
 *
 * 流程:
 *   1. Pipeline 因子计算 → 各策略独立打分
 *   2. 合并池 (多策略交集)
 *   3. AI 精选 Top3-5 → 最终推荐
 *   4. 独立持仓 (orderType="ShortTermQuant")  顶部显示
 */
class ShortTermQuantFragment : QuantFragmentBase() {

    private lateinit var mainBoardSwitch: Switch

    /** 短線週期選擇（持倉 1 天 ~ 2 週） */
    private var selectedPeriods: Set<Int> = setOf(3)

    companion object {
        private const val TAG = "ShortTermQuant"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val PERIOD_LABELS = mapOf(
            1 to "1日", 3 to "3日", 5 to "5日",
            7 to "7日", 10 to "10日", 14 to "14日"
        )
    }

    override fun getQuantType() = "ShortTermQuant"

    override val positionTitlePrefix = "短線量化"

    override fun getDefaultUseCaseId() = "short_term"

    override fun onBuildClick() { runBuildAndBuy() }
    override fun onFittingClick() = showFittingParams()
    override fun onBacktrackClick() { runShortTermBacktrack() }
    override fun onClearClick() = clearData()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        initEngine(); buildUI(); refreshPositions(); return rootLayout
    }

    override fun initEngine() {
        val ctx = requireContext().applicationContext; StrategyEngineHolder.init(ctx); engine = StrategyEngineHolder.get()
    }

    override fun buildUI() {
        rootLayout.addView(TextView(requireContext()).apply {
            text = "🤖 短线量化系统 (Zipline Pipeline + AI精选)"
            textSize = 16f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD); setPadding(16, 16, 16, 8)
        })
        val configRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 6, 8, 6); setBackgroundColor(Color.WHITE) }
        val dateLabelTv = TextView(requireContext()).apply { text = "📅 交易日:"; textSize = 12f; setTextColor(Color.parseColor("#333333")); setTypeface(null, Typeface.BOLD) }
        configRow.addView(dateLabelTv)
        val datePicker = com.chin.stockanalysis.ui.TradingDayPickerView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 4; marginEnd = 6 }
            onDateChanged = { d ->
                browsingDate = d
                val isNonTrading = d.dayOfWeek == java.time.DayOfWeek.SATURDAY || d.dayOfWeek == java.time.DayOfWeek.SUNDAY || d in com.chin.stockanalysis.ui.TradingDayPickerView.CHINESE_HOLIDAYS
                dateLabelTv.text = if (isNonTrading) "📅 非交易日:" else "📅 交易日:"
            }
        }
        configRow.addView(datePicker)
        mainBoardSwitch = Switch(requireContext()).apply { text = "仅主板"; textSize = 11f; isChecked = true; setTextColor(Color.parseColor("#333333")) }
        configRow.addView(mainBoardSwitch)

        // 持倉信息提示
        val tipTv = TextView(requireContext()).apply {
            text = "📊 持倉1-14天 | 最多5只 | 技術+資金"
            textSize = 10f; setTextColor(Color.parseColor("#1565C0")); setPadding(8, 0, 0, 0)
        }
        configRow.addView(tipTv)
        rootLayout.addView(configRow)

        // ── 週期選擇行（短線持倉 1 日 ~ 2 週） ──
        val periodRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4); setBackgroundColor(Color.WHITE)
        }
        periodRow.addView(TextView(requireContext()).apply {
            text = "📊 週期:"; textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 4, 0)
        })
        val periodRadioGroup = android.widget.RadioGroup(requireContext()).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        for ((period, label) in PERIOD_LABELS) {
            val rb = android.widget.RadioButton(requireContext()).apply {
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

        // ── 统一按钮行（基类提供：建倉/持倉/回溯/擬合/賣出/數據） ──
        rootLayout.addView(createButtonRow())

        val progressRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 4, 16, 4) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } }
        progressRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "就绪"; textSize = 12f; setTextColor(Color.parseColor("#AAAAAA")) }
        progressRow.addView(statusTv); rootLayout.addView(progressRow)

        rootLayout.addView(View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1); setBackgroundColor(Color.parseColor("#DDDDDD")) })

        // 持倉顯示區
        val contentScroll = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        positionContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        contentScroll.addView(positionContainer)
        rootLayout.addView(contentScroll)
    }


    // ═══════════════════════════════════════
    // 持仓（直接使用基類 refreshPositions + renderPositions）
    // ═══════════════════════════════════════

    // createCell, dpToPx, showSellConfirmDialog, executeSingleSell 已由基類 QuantFragmentBase 提供

    // ═══════════════════════════════════════
    // 建倉（智能分流：Agent結果 vs zipline）
    // ═══════════════════════════════════════

    /**
     * 短線量化回測：復用 HistoricalBacktestEngine 做逐日回測
     * 持仓周期 = 3 天，賣出使用 AutoSellEngine 規則（硬止損 -8% / 時間平倉 10 天）
     */
    private fun runShortTermBacktrack() {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 回溯中..."
        progressBar.visibility = View.VISIBLE; statusTv.text = "正在執行短線回溯..."
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"; progressBar.visibility = View.GONE
                        statusTv.text = "❌ 無啟用策略"
                    }
                    return@launch
                }
                val backtestEngine = com.chin.stockanalysis.strategy.backtest.HistoricalBacktestEngine(ctx)
                val report = backtestEngine.runHistoricalBacktest(strategies, tradingDays = 30)
                val sb = StringBuilder()
                sb.appendLine("📈 短線回測報告（30 個交易日）")
                sb.appendLine("期間: ${report.dateRange}"); sb.appendLine()
                for (r in report.strategyReports) {
                    sb.appendLine("📋 ${r.strategyName}")
                    sb.appendLine("  交易日: ${r.totalDays} 天 | 買入信號: ${r.totalBuys} 次")
                    sb.appendLine("  買入準確率: ${"%.1f".format(r.buyAccuracy * 100)}% (${r.correctBuys}/${r.totalBuys})")
                    sb.appendLine("  平均淨收益: ${"%.2f".format(r.avgReturn)}%（已扣交易成本0.3%）")
                    sb.appendLine("  最大盈利: ${"%.2f".format(r.maxGain)}% | 最大虧損: ${"%.2f".format(r.maxLoss)}%")
                    sb.appendLine()
                }
                Log.i("ShortTermQuant", sb.toString())
                withContext(Dispatchers.Main) {
                    showDialog("短線回測報告", sb.toString())
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"; progressBar.visibility = View.GONE
                    statusTv.text = "✅ 回測完成: ${report.strategyReports.size} 個策略"
                }
            } catch (e: Exception) {
                Log.e("ShortTermQuant", "回測失敗: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"; progressBar.visibility = View.GONE
                    statusTv.text = "❌ 回測失敗: ${e.message?.take(50)}"
                    Toast.makeText(ctx, "回測失敗: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 建倉按鈕點擊：走 DAG Pipeline
     */
    private fun runBuildAndBuy() {
        executeViaDagPipeline()
    }

    /**
     * ══════════ DAG Pipeline 執行（通用開關開啟時走此路徑） ══════════
     *
     * 使用 short_term_pipeline.xml 動態鏈接，節點關係由 XML 定義，可重新編排。
     * 含熱度計算、新聞攔截、騰龍換鳥等短線專有節點。
     */
    private fun executeViaDagPipeline() {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ [DAG] 執行中"
        progressBar.visibility = View.VISIBLE
        statusTv.text = "🔄 [DAG] 短線 Pipeline 執行中..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val tradeDate = browsingDate.format(DATE_FMT)
                val strategies = eng.getEnabledStrategiesByPeriod(HoldingPeriod.SHORT)
                val r = com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor.execute(
                    context = requireContext(),
                    useCaseId = "short_term",
                    tradeDate = tradeDate,
                    today = today,
                    strategies = strategies,
                    orderType = "shortterm",
                    importDays = 60,
                    onNodeProgress = { pipelineName, nodeName ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            statusTv.text = "🔄 [DAG] ${pipelineName} ${nodeName} 執行中..."
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    showDialog(
                        "短線 DAG Pipeline 報告",
                        com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
                            .buildReportText("短線 DAG Pipeline", r)
                    )
                    statusTv.text = r.uiText
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                    refreshPositions()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[DAG] 短線執行異常", e)
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ [DAG] ${e.message?.take(40)}"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }





    // ═══════════════════════════════════════
    // 数据查看
    // ═══════════════════════════════════════

    override fun showDataMenu(anchor: View) {
        val options = arrayOf(
            "🧹 清空持仓",
            "🧹 清空报告",
            "📋 查看交易记录",
            "📊 短线量化报告 (历史)",
            "📈 回溯測試",
            "🔧 擬合調優"
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("数据中心")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmAndClearPositions()
                    1 -> confirmAndClearReports()
                    2 -> showShortTermTradeHistory()
                    3 -> { /* 短线报告历史 - 待实现 */ }
                    4 -> onBacktrackClick()
                    5 -> onFittingClick()
                }
            }
            .setNegativeButton("关闭", null).show()
    }

    private fun confirmAndClearPositions() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空持仓")
            .setMessage("确定要清空所有短线量化持仓记录吗？此操作不可撤销。")
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val orders = db.strategyTradeOrderDao().getRecent(500)
                            .filter { it.orderType == "ShortTermQuant" }
                        for (order in orders) db.strategyTradeOrderDao().deleteByDate(order.tradeDate)
                        withContext(Dispatchers.Main) { refreshPositions(); statusTv.text = "✅ 已清空持仓"; Toast.makeText(requireContext(), "持仓已清空", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" } }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun confirmAndClearReports() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空报告")
            .setMessage("确定要清空所有短线量化报告记录吗？此操作不可撤销。")
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val entities = db.dailyPeriodResultDao().getRecent(1000)
                        for (e in entities) {
                            try { db.dailyPeriodResultDao().deleteByDate(e.tradeDate) } catch (_: Exception) {}
                        }
                        withContext(Dispatchers.Main) { statusTv.text = "✅ 已清空报告"; Toast.makeText(requireContext(), "报告已清空", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" } }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun showShortTermTradeHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(100)
                val periodResults = db.dailyPeriodResultDao().getAvailableDates(30)
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("📋 短线量化交易记录 (最近100条)"); sb.appendLine()
                    if (orders.isEmpty()) sb.appendLine("暂无交易记录")
                    else for (order in orders) {
                        val statusEmoji = when (order.status) { "SOLD"->"✅"; "BUYING"->"🟢"; "FAILED"->"❌"; else->"⏳" }
                        sb.appendLine("$statusEmoji ${order.stockName}(${order.stockCode.takeLast(6)})")
                        sb.appendLine("   买入: ${order.tradeDate} ¥${"%.2f".format(order.buyPrice)} x${order.quantity}")
                        if (order.status == "SOLD") {
                            val profitStr = if (order.profitPct >= 0) "+${"%.2f".format(order.profitPct)}%" else "${"%.2f".format(order.profitPct)}%"
                            sb.appendLine("   卖出: ¥${"%.2f".format(order.sellPrice)} 收益: $profitStr")
                        } else sb.appendLine("   状态: ${order.status}")
                        sb.appendLine()
                    }
                    showDialog("交易记录", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ═══════════════════════════════════════
    // 操作
    // ═══════════════════════════════════════

    private fun importData() {
        buildBtn.isEnabled=false; buildBtn.text="⏳"; progressBar.visibility=View.VISIBLE; statusTv.text="正在从东方财富拉取历史K线..."
        lifecycleScope.launch(Dispatchers.IO) { try { val f=com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext()); val total=f.fetchAllHistoricalData(60){p-> lifecycleScope.launch(Dispatchers.Main){statusTv.text="进度: ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条"} }; withContext(Dispatchers.Main){buildBtn.isEnabled=true; buildBtn.text="▶ 建仓"; progressBar.visibility=View.GONE; statusTv.text="✅ 导入完成 · $total 条历史记录"} } catch (e:Exception){withContext(Dispatchers.Main){buildBtn.isEnabled=true; buildBtn.text="▶ 建仓"; progressBar.visibility=View.GONE; statusTv.text="导入失败: ${e.message}"} } }
    }

    // 賣出功能已由基類 QuantFragmentBase 提供（showSellMenu / runAutoSellEvaluation / executeAutoSell）
    // 短線量化使用基類的完整賣出評估和執行功能

    /** 擬合調優 — 執行 autoFit 並展示各策略各週期擬合參數報告（對齊中線邏輯） */
    private fun showFittingParams() {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 擬合中"; progressBar.visibility = View.VISIBLE; statusTv.text = "🔧 擬合調優中（週期: ${selectedPeriods.joinToString(",")}日）..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val te = StrategyFittingEngine(requireContext())
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val recentDates = db.dailySnapshotDao().getAvailableDates(30).sorted()

                // 先執行擬合
                if (strategies.isNotEmpty() && recentDates.size >= 2) {
                    try {
                        te.autoFit(strategies, recentDates)
                        Log.i(TAG, "短線擬合完成，更新 strategy_trade_fitting_params 表")
                    } catch (e: Exception) {
                        Log.w(TAG, "短線擬合失敗（仍顯示已有數據）: ${e.message}")
                    }
                }

                // 展示擬合參數報告
                val sb = StringBuilder()
                sb.appendLine("🔧 短線擬合參數（當前週期: ${selectedPeriods.joinToString(",")}日）")
                sb.appendLine()
                for (strategy in strategies) {
                    sb.appendLine("【${strategy.name}】")
                    val params = db.strategyTradeFittingParamDao().getRecentByStrategy(strategy.id, 50)
                    if (params.isEmpty()) {
                        sb.appendLine("  暫無擬合數據")
                    } else {
                        val byPeriod = params.groupBy { it.periodDays }
                        for ((period, items) in byPeriod) {
                            val best = items.maxByOrNull { it.accuracy }
                            val worst = items.minByOrNull { it.accuracy }
                            sb.appendLine("  [${period}日] ${items.size}條")
                            if (best != null) sb.appendLine("    最佳: 準確率${"%.2f".format(best.accuracy * 100)}% 平均收益${"%.2f".format(best.avgReturn)}%")
                            if (worst != null) sb.appendLine("    最差: 準確率${"%.2f".format(worst.accuracy * 100)}% 平均收益${"%.2f".format(worst.avgReturn)}%")
                        }
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) {
                    showDialog("短線擬合參數", sb.toString())
                    statusTv.text = "✅ 擬合完成: ${strategies.size} 個策略"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"; progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 擬合失敗: ${e.message?.take(30)}"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"; progressBar.visibility = View.GONE
                }
            }
        }
    }

    /** 供外部调用的自动触发 Pipeline */
    fun autoRunPipeline() {
        if (buildBtn.isEnabled) runBuildAndBuy()
    }

    // 清除功能已由基類 QuantFragmentBase 提供（clearData / clearDataByDate）
    // showDialog 已由基類 QuantFragmentBase 提供，無需重寫
}
