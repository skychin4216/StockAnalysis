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
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.database.DataExportImport
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 中线量化面板 v3.0 — 继承 QuantFragmentBase
 *
 * 共用基类的：卖出评估/执行、数据管理、持仓刷新、交易历史、导出等功能。
 * 子类只需实现自己的建仓逻辑（executeTrade）和特有的UI配置。
 */
class MidTermQuantFragment : QuantFragmentBase() {

    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView
    private lateinit var periodCheckboxes: LinearLayout
    private lateinit var mainBoardSwitch: Switch
    private lateinit var executeBtn: Button
    private lateinit var fittingBtn: Button

    private var screener: StockScreener? = null
    private var selectedPeriods: Set<Int> = setOf(1)
    private var hasTradeReport: Boolean = false
    private var positionOrderDates: MutableMap<View, String> = mutableMapOf()

    companion object {
        private val PERIOD_LABELS = mapOf(1 to "当日", 3 to "近3日", 10 to "近10日",
            30 to "近30日", 50 to "近50日", 100 to "近100日")
    }

    // ── 抽象方法实现 ──

    override fun getQuantType() = "MidTermQuant"
    override fun onBuildClick() { executeTrade() }
    override fun onFittingClick() { showFittingParams() }
    override fun onBacktrackClick() { runNextDayBacktrack() }
    override fun onClearClick() { clearData() }

    // ── 生命周期 ──

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        initEngine()
        buildUI()
        return rootLayout
    }

    override fun initEngine() {
        super.initEngine()
        val ctx = requireContext().applicationContext
        val repo = StockDataSourceFactory.createDefaultRepository(ctx)
        screener = StockScreener(repo)
    }

    override fun buildUI() {
        rootLayout.addView(TextView(requireContext()).apply {
            text = "🤖 中线量化系统 v3.0 (含智能卖出)"
            textSize = 18f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setPadding(16, 16, 16, 8)
        })

        rootLayout.addView(createConfigSection())
        rootLayout.addView(createProgressRow())

        // 统一按钮行（来自基类：建倉/持倉/回溯/擬合/賣出/數據）
        rootLayout.addView(createButtonRow())

        rootLayout.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 8 }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        val contentScroll = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        positionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(8, 4, 8, 4)
        }
        contentScroll.addView(positionContainer)
        rootLayout.addView(contentScroll)

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

        val row1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        dateLabelTv = TextView(requireContext()).apply {
            text = "📅 交易日:"; textSize = 13f
            setTextColor(Color.parseColor("#333333")); setTypeface(null, Typeface.BOLD)
        }
        row1.addView(dateLabelTv)
        datePicker = TradingDayPickerView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 4; marginEnd = 12 }
            onDateChanged = { d ->
                browsingDate = d
                val isNonTrading = d.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                        d.dayOfWeek == java.time.DayOfWeek.SUNDAY ||
                        d in TradingDayPickerView.CHINESE_HOLIDAYS
                dateLabelTv.text = if (isNonTrading) "📅 非交易日:" else "📅 交易日:"
            }
        }
        row1.addView(datePicker)

        mainBoardSwitch = Switch(requireContext()).apply {
            text = "仅主板"; textSize = 12f; isChecked = true; setTextColor(Color.parseColor("#333333"))
        }
        row1.addView(mainBoardSwitch)
        container.addView(row1)

        container.addView(TextView(requireContext()).apply {
            text = "📊 数据周期:"; textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 4, 0, 2)
        })

        periodCheckboxes = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
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
        periodCheckboxes.addView(radioGroup)
        container.addView(periodCheckboxes)
        return container
    }

    /** 供外部调用的自动执行中线量化 */
    fun autoExecuteTrade() {
        if (buildBtn.isEnabled) executeTrade()
    }

    // ═══════════════════════════════════════
    // 建仓 — 中线量化核心逻辑
    // ═══════════════════════════════════════

    private fun executeTrade() {
        val eng = engine ?: return
        if (selectedPeriods.isEmpty()) {
            Toast.makeText(requireContext(), "请至少选择一个周期", Toast.LENGTH_SHORT).show()
            return
        }

        buildBtn.isEnabled = false; buildBtn.text = "⏳ 执行中..."
        progressBar.visibility = View.VISIBLE; statusTv.text = "正在执行中线量化..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val importPrefs = requireContext().getSharedPreferences("data_import", android.content.Context.MODE_PRIVATE)
                val lastImport = importPrefs.getString("last_import_date", "") ?: ""
                val config = SimulationTradeEngine.TradeSessionConfig(
                    tradeDate = browsingDate.format(DATE_FMT),
                    periods = selectedPeriods.toList().sorted(),
                    onlyMainBoard = mainBoardSwitch.isChecked,
                    maxFitRounds = 20
                )
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "没有启用的策略"; buildBtn.isEnabled = true
                        buildBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE
                    }; return@launch
                }
                if (tradeEngine == null) tradeEngine = SimulationTradeEngine(requireContext())
                val te = tradeEngine!!
                val db = StockDatabase.getInstance(requireContext())
                // 检查是否需要先导入数据（与短线量化一致）
                val todaySnaps = db.dailySnapshotDao().getByDate(today)
                val needImport = todaySnaps.size < 100 || lastImport != today
                if (needImport) {
                    withContext(Dispatchers.Main) { statusTv.text = "📥 数据不足，自动导入中（请耐心等待）..." }
                    val f = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                    f.fetchAllHistoricalData(60) { p ->
                        lifecycleScope.launch(Dispatchers.Main) { statusTv.text = "📥 导入: ${p.completedStocks}/${p.totalStocks} 只" }
                    }
                    importPrefs.edit().putString("last_import_date", today).apply()
                    withContext(Dispatchers.Main) { statusTv.text = "✅ 数据导入完成，开始中线量化..." }
                }
                val report = te.runTradeSession(strategies, config)
                val swappedCount = te.swapWeakHoldings(strategies, report.buyOrders.size, browsingDate.format(DATE_FMT))
                val finalReport = if (swappedCount > 0) {
                    val holdingOrders = db.strategyTradeOrderDao().getRecent(200).filter { it.status == "BUYING" }
                    val soldStocks = db.strategyTradeOrderDao().getRecent(300).filter {
                        it.status == "SOLD" && it.sellTime.take(10) == browsingDate.format(DATE_FMT)
                    }
                    val swapInfo = SimulationTradeEngine.SwapInfo(
                        beforeCount = (holdingOrders.size) + swappedCount,
                        soldCount = swappedCount,
                        soldStocks = soldStocks.map {
                            "${it.stockName}(${it.stockCode.takeLast(6)})${"%.2f".format(it.profitPct)}%"
                        }
                    )
                    report.copy(summary = te.buildEnhancedSummary(report.stepDetail, report.aiTop3, report.crossDayRanking, swapInfo))
                } else report
                withContext(Dispatchers.Main) {
                    try {
                        saveBuyOrdersToDb(finalReport)
                        showTradeReport(finalReport)
                        refreshPositions()
                        statusTv.text = "✅ 完成: ${report.summary.lines().firstOrNull()?.take(60) ?: "交易完成"}"
                    } catch (uiEx: Exception) {
                        Log.e("TradeUI", "UI update failed", uiEx)
                        statusTv.text = "✅ 交易完成，但显示报告时出错"
                    } finally {
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建仓"
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 执行失败: ${e.message?.take(50)}"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建仓"
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "执行失败: ${e.message?.take(100)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 报告显示
    // ═══════════════════════════════════════

    private fun showTradeReport(report: SimulationTradeEngine.TradeSessionReport) {
        val sv = ScrollView(requireContext())
        val c = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8) }

        c.addView(TextView(requireContext()).apply {
            text = "📊 中线量化报告  ${report.config.tradeDate}"
            textSize = 15f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        })

        val table = TableLayout(requireContext()).apply { isStretchAllColumns = true }
        val hr = TableRow(requireContext())
        for (h in listOf("策略", "周期", "精选3只", "买入价", "卖出价", "收益")) {
            hr.addView(TextView(requireContext()).apply {
                text = h; textSize = 9f; setTextColor(Color.parseColor("#999999"))
                setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(2, 4, 2, 4)
            })
        }
        table.addView(hr)

        val resultMap = report.periodResults.groupBy { it.strategyId to it.periodDays }
        val enabledStrategies = engine?.getStrategies()?.filter { engine!!.isEnabled(it.id) } ?: emptyList()
        for (strategy in enabledStrategies) {
            for (period in report.config.periods) {
                val pr = resultMap[strategy.id to period]?.firstOrNull()
                val name = strategy.name.take(8)
                val periodLabel = PERIOD_LABELS[period] ?: "${period}日"
                val hasResults = pr != null && pr.finalTop15.isNotEmpty()
                val top3Text = if (hasResults) {
                    pr!!.finalTop15.joinToString("\n") { "${it.stockName}(${it.stockCode.takeLast(6)}) ${it.strength}%" }
                } else "⚠ 无信号"
                val row = TableRow(requireContext())
                if (hasResults && pr != null) { row.setOnClickListener { showDetailDialog(report, pr) } }
                row.addView(TextView(requireContext()).apply {
                    text = name; textSize = 10f; setTextColor(Color.parseColor("#222222"))
                    setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL; setPadding(1, 4, 1, 4)
                })
                row.addView(TextView(requireContext()).apply {
                    text = periodLabel; textSize = 10f; setTextColor(Color.parseColor("#333333"))
                    gravity = Gravity.CENTER; setPadding(1, 4, 1, 4)
                })
                row.addView(TextView(requireContext()).apply {
                    text = top3Text; textSize = 8f; setTextColor(Color.parseColor("#666666"))
                    setLineSpacing(1.5f, 1f); setPadding(1, 4, 1, 4)
                })
                for (j in 1..3) row.addView(TextView(requireContext()).apply {
                    text = "—"; textSize = 10f; setTextColor(Color.parseColor("#999999"))
                    gravity = Gravity.CENTER; setPadding(1, 4, 1, 4)
                })
                table.addView(row)
            }
        }
        c.addView(table)

        if (report.aiTop3.isNotEmpty()) {
            c.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 8; bottomMargin = 4 }
                setBackgroundColor(Color.parseColor("#DDDDDD"))
            })
            c.addView(TextView(requireContext()).apply {
                text = "🤖 AI精选 Top3"; textSize = 14f; setTextColor(Color.parseColor("#1565C0"))
                setTypeface(null, Typeface.BOLD); setPadding(0, 8, 0, 6)
            })
            for (pick in report.aiTop3) {
                val scoreColor = when { pick.compositeScore >= 75 -> "#E65100"; pick.compositeScore >= 60 -> "#2E7D32"; else -> "#666666" }
                c.addView(TextView(requireContext()).apply {
                    text = "#${pick.rank} ${pick.stockName}(${pick.stockCode.takeLast(6)}) 评分:${pick.compositeScore} 概率:${pick.upProbability}% ${pick.actionSuggestion}"
                    textSize = 11f; setTextColor(Color.parseColor(scoreColor)); setPadding(0, 2, 0, 2)
                })
                c.addView(TextView(requireContext()).apply {
                    text = "  ${pick.reason.take(100)}"; textSize = 9f
                    setTextColor(Color.parseColor("#999999")); setPadding(8, 0, 0, 4)
                })
            }
        }

        sv.addView(c)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("中线量化报告").setView(sv).setPositiveButton("关闭", null).show()
    }

    private fun showDetailDialog(report: SimulationTradeEngine.TradeSessionReport, pr: SimulationTradeEngine.StrategyPeriodResult) {
        val s = StringBuilder()
        s.appendLine("【${pr.strategyName}】${report.config.tradeDate}")
        s.appendLine("周期: ${pr.periodDays}日  新闻力度: ${pr.newsStrengthScore}  轮动惩罚: ${pr.rotationPenalty}")
        s.appendLine()
        if (pr.finalTop15.isNotEmpty()) {
            s.appendLine("🔥 精选Top15:")
            for ((i, sig) in pr.finalTop15.withIndex()) {
                s.appendLine("  ${i+1}. ${sig.stockName}(${sig.stockCode.takeLast(6)}) 强度:${sig.strength}%")
                s.appendLine("     ${sig.reason.take(80)}")
            }
        }
        if (pr.filteredStocks.isNotEmpty()) {
            s.appendLine(); s.appendLine("🚫 被过滤(${pr.filteredStocks.size}只):")
            for (f in pr.filteredStocks.take(5)) s.appendLine("  ${f.stockName}: ${f.reason}")
        }
        showDialog(pr.strategyName, s.toString())
    }

    // ═══════════════════════════════════════
    // 回溯
    // ═══════════════════════════════════════

    private fun runNextDayBacktrack() {
        val eng = engine ?: return; val te = tradeEngine ?: return
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
                    val sellPrice = nextDayStock.close
                    val profitPct = (sellPrice - order.buyPrice) / order.buyPrice * 100
                    db.strategyTradeOrderDao().updateSellInfo(
                        id = order.id, status = "SOLD", sellPrice = sellPrice,
                        sellTime = "$nextDate 15:00", profitPct = profitPct
                    )
                }
                val tradeDates = buyingOrders.map { it.tradeDate }.distinct()
                val allReports = mutableListOf<SimulationTradeEngine.BacktrackReport>()
                for (tradeDate in tradeDates) {
                    val config = SimulationTradeEngine.TradeSessionConfig(
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
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE
                    statusTv.text = "✅ 回溯完成: ${allReports.sumOf { it.buyOrdersAnalyzed.size }}笔订单, ${allReports.sumOf { it.missedOpportunities.size }}个遗漏机会"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE
                    statusTv.text = "❌ 回溯失败: ${e.message?.take(50)}"
                    Toast.makeText(requireContext(), "回溯失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun getNextTradingDayFromDB(date: String): String? = try {
        val db = StockDatabase.getInstance(requireContext())
        val dates = db.dailySnapshotDao().getAvailableDates(10).sorted()
        val idx = dates.indexOf(date); if (idx >= 0 && idx + 1 < dates.size) dates[idx + 1] else null
    } catch (_: Exception) { null }

    // ═══════════════════════════════════════
    // 拟合
    // ═══════════════════════════════════════

    private fun showFittingParams() {
        val eng = engine ?: return
        val strategies = eng.getStrategies()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val sb = StringBuilder()
                sb.appendLine("🔧 调优拟合参数"); sb.appendLine()
                for (strategy in strategies) {
                    sb.appendLine("【${strategy.name}】")
                    val params = db.strategyTradeFittingParamDao().getRecentByStrategy(strategy.id, 50)
                    if (params.isEmpty()) sb.appendLine("  暂无拟合数据")
                    else {
                        val byPeriod = params.groupBy { it.periodDays }
                        for ((period, items) in byPeriod) {
                            val best = items.maxByOrNull { it.accuracy }; val worst = items.minByOrNull { it.accuracy }
                            sb.appendLine("  [${period}日] ${items.size}条")
                            if (best != null) sb.appendLine("    最佳: 准确率${"%.2f".format(best.accuracy * 100)}% 平均收益${"%.2f".format(best.avgReturn)}%")
                            if (worst != null) sb.appendLine("    最差: 准确率${"%.2f".format(worst.accuracy * 100)}% 平均收益${"%.2f".format(worst.avgReturn)}%")
                        }
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) { showDialog("拟合参数", sb.toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ═══════════════════════════════════════
    // 数据菜单（覆写基类，增加中线特有选项）
    // ═══════════════════════════════════════

    override fun showDataMenu() {
        val exporter = DataExportImport(requireContext())
        val options = arrayOf(
            "🧹 清空持仓", "🧹 清空报告",
            "📋 查看交易记录", "📊 中线量化报告 (历史)",
            "📊 查看精選池",
            "📤 导出交易数据 (CSV文本)", "📤 导出 JSON (全部数据)", "📤 导出 CSV (分表)",
            "📂 查看导出文件列表", "📥 导入 JSON 数据", "📊 数据库统计信息"
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("数据中心")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmAndClearPositions()
                    1 -> confirmAndClearReports()
                    2 -> showTradeHistory()
                    3 -> showTradeReportsHistory()
                    4 -> showFinalPool()
                    5 -> exportTradeData()
                    6 -> exportToJson(exporter)
                    7 -> exportToCsv(exporter)
                    8 -> showExportFiles(exporter)
                    9 -> showImportDialog(exporter)
                    10 -> showDbStats(exporter)
                }
            }
            .setNegativeButton("关闭", null).show()
    }

    private fun showTradeReportsHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val entities = db.dailyPeriodResultDao().getRecent(100)
                    .filter { it.strategyId != "FINAL_POOL" && it.strategyId != "BACKTRACK" }
                if (entities.isEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "暂无中线量化报告记录", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val codeToName = try { db.stockBasicDao().getAll().associate { it.code to it.name } } catch (_: Exception) { emptyMap() }
                val grouped = entities.groupBy { it.tradeDate }
                val sb = StringBuilder()
                sb.appendLine("📊 中线量化报告历史 (共 ${entities.size} 条)"); sb.appendLine()
                for ((date, items) in grouped.toSortedMap().entries.reversed().take(10)) {
                    sb.appendLine("━━━ ${date} ━━━")
                    for (item in items) {
                        val top3Json = try { JSONArray(item.finalTop3Json) } catch (_: Exception) { JSONArray() }
                        val mainBoardLabel = if (item.mainBoardFilter) " 主板" else ""
                        sb.appendLine("  ${item.strategyName}[${item.periodDays}日]$mainBoardLabel: ${item.stockCount}只信号")
                        if (item.newsStrengthScore > 0) sb.appendLine("    新闻力度:${item.newsStrengthScore} 轮动惩罚:${item.rotationPenalty}")
                        try {
                            val reasonJson = JSONArray(item.filteredReasonJson)
                            if (reasonJson.length() > 0) {
                                val sampleReason = reasonJson.optJSONObject(0)
                                if (sampleReason != null) sb.appendLine("    ⚠️ 过滤: ${sampleReason.optString("name")}(${sampleReason.optString("reason")}) 等${reasonJson.length()}只")
                            }
                        } catch (_: Exception) {}
                        if (top3Json.length() > 0) {
                            for (i in 0 until minOf(top3Json.length(), 3)) {
                                val obj = top3Json.optJSONObject(i) ?: continue
                                val code = obj.optString("code")
                                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: codeToName[code] ?: code.takeLast(6)
                                val sector = try { com.chin.stockanalysis.stock.database.StockDataCenter.getSectorsByStock(code).firstOrNull() ?: "" } catch (_: Exception) { "" }
                                val sectorStr = if (sector.isNotBlank()) " [$sector]" else ""
                                sb.appendLine("    Top${i+1}: $name(${code.takeLast(6)})$sectorStr 得分:${obj.optInt("score")}")
                            }
                        }
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) { showDialog("中线量化报告历史", sb.toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ═══════════════════════════════════════
    // 导出/导入（中线特有）
    // ═══════════════════════════════════════

    private fun exportToJson(exporter: DataExportImport) {
        lifecycleScope.launch(Dispatchers.IO) {
            try { val path = exporter.exportAllToJson(); withContext(Dispatchers.Main) { showDialog("导出成功", "文件已保存到:\n$path") } }
            catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }
    private fun exportToCsv(exporter: DataExportImport) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val files = exporter.exportAllToCsv()
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder().apply { appendLine("CSV 文件已保存:"); files.forEach { appendLine("- $it") }; appendLine("\n每个文件可以用 Excel 打开") }
                    showDialog("CSV导出成功", sb.toString())
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }
    private fun showExportFiles(exporter: DataExportImport) {
        val files = exporter.getExportFiles()
        if (files.isEmpty()) { Toast.makeText(requireContext(), "暂无导出文件", Toast.LENGTH_SHORT).show(); return }
        val sb = StringBuilder()
        sb.appendLine("📂 已导出的文件:"); sb.appendLine()
        for (f in files) {
            sb.appendLine("${f.name} (${f.length()/1024}KB)")
            sb.appendLine("  修改时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))}")
            sb.appendLine()
        }
        showDialog("导出文件列表", sb.toString())
    }
    private fun showImportDialog(exporter: DataExportImport) {
        val input = EditText(requireContext()).apply {
            hint = "输入JSON文件完整路径"; setSingleLine()
            val exportDir = File(requireContext().getExternalFilesDir(null), "StockAnalysis_exports")
            if (exportDir.exists()) {
                val files = exportDir.listFiles()?.filter { it.name.endsWith(".json") }
                if (files?.isNotEmpty() == true) setText(files.first().absolutePath)
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("导入数据").setMessage("从JSON文件导入数据到数据库").setView(input)
            .setPositiveButton("导入") { _, _ -> val path = input.text.toString().trim(); if (path.isNotEmpty()) doImport(exporter, path) else Toast.makeText(requireContext(), "请指定文件路径", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("取消", null).show()
    }
    private fun doImport(exporter: DataExportImport, path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try { val report = exporter.importFromJson(path); withContext(Dispatchers.Main) { if (report.success) showDialog("导入成功", report.message) else Toast.makeText(requireContext(), "导入失败: ${report.message}", Toast.LENGTH_LONG).show() } }
            catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "导入异常: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }
    private fun showDbStats(exporter: DataExportImport) {
        lifecycleScope.launch(Dispatchers.IO) { val stats = exporter.getDatabaseStats(); withContext(Dispatchers.Main) { showDialog("数据库统计", stats) } }
    }

    private fun showFinalPool() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val entities = db.dailyPeriodResultDao().getRecent(100).filter { it.strategyId == "FINAL_POOL" }.sortedByDescending { it.tradeDate }
                if (entities.isEmpty()) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "暂无精选池记录", Toast.LENGTH_SHORT).show() }; return@launch }
                val latest = entities.first()
                val codes = try { JSONArray(latest.stockCodesJson) } catch (_: Exception) { JSONArray() }
                val crossDayJson = try { JSONArray(latest.finalTop3Json) } catch (_: Exception) { JSONArray() }
                val sb = StringBuilder()
                sb.appendLine("📋 9步精选最终池"); sb.appendLine("交易日: ${latest.tradeDate}"); sb.appendLine("共 ${latest.stockCount} 只股票输入AI"); sb.appendLine()
                sb.appendLine("🔥 跨日聚合命中 Top10:")
                for (i in 0 until crossDayJson.length()) { val obj = crossDayJson.getJSONObject(i); sb.appendLine("  ${obj.optString("code").takeLast(6)}: ${obj.optInt("days")}天命中") }
                sb.appendLine(); sb.appendLine("📊 完整精选池 (${codes.length()} 只):")
                for (i in 0 until minOf(codes.length(), 60)) sb.appendLine("  ${i+1}. ${codes.optString(i)}")
                if (codes.length() > 60) sb.appendLine("  ... 共 ${codes.length()} 只，仅显示前60")
                withContext(Dispatchers.Main) { showDialog("FinalPool_${latest.tradeDate}", sb.toString()) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "载入精选池失败: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }
    }

    // ═══════════════════════════════════════
    // 确认清除
    // ═══════════════════════════════════════

    private fun confirmAndClearPositions() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空持仓")
            .setMessage("确定要清空所有中线量化持仓记录吗？此操作不可撤销。")
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val orders = db.strategyTradeOrderDao().getRecent(500)
                        for (order in orders) db.strategyTradeOrderDao().deleteByDate(order.tradeDate)
                        withContext(Dispatchers.Main) { refreshPositions(); statusTv.text = "✅ 已清空持仓"; Toast.makeText(requireContext(), "持仓已清空", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" } }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun confirmAndClearReports() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空报告")
            .setMessage("确定要清空所有中线量化报告记录吗？此操作不可撤销。")
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

    // ═══════════════════════════════════════
    // 保存
    // ═══════════════════════════════════════

    private suspend fun saveBuyOrdersToDb(report: SimulationTradeEngine.TradeSessionReport) {
        try {
            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            // 保存買入訂單到自選股 + AI 精選表
            if (report.buyOrders.isNotEmpty()) {
                com.chin.stockanalysis.stock.database.AppBackgroundRunner.addBatchToWatchlist(
                    requireContext(),
                    report.buyOrders.map { Triple(it.stockCode, it.stockName, it.scoreAtBuy ?: 0) },
                    source = "midterm"
                )
                val aiEntities = report.buyOrders.map { order ->
                    com.chin.stockanalysis.stock.database.AiSelectedStockEntity(
                        stockCode = order.stockCode, stockName = order.stockName,
                        source = "midterm", selectedDate = today,
                        score = order.scoreAtBuy ?: 0, reason = order.reason.take(120), buyPrice = order.buyPrice
                    )
                }
                com.chin.stockanalysis.stock.database.AppBackgroundRunner.saveAiSelectedStocks(requireContext(), aiEntities)
                Log.i("MidTermQuant", "⭐ 加入自選+AI精選: ${report.buyOrders.size}只")
            }
            // 保存 AI Top3 精選（即使沒有 buyOrders 也要保存 AI 精選結果）
            if (report.aiTop3.isNotEmpty()) {
                val aiTopEntities = report.aiTop3.map { pick ->
                    com.chin.stockanalysis.stock.database.AiSelectedStockEntity(
                        stockCode = pick.stockCode, stockName = pick.stockName,
                        source = "midterm_ai", selectedDate = today,
                        score = pick.compositeScore, reason = pick.reason.take(120), buyPrice = 0.0
                    )
                }
                // 去重（避免與 buyOrders 重複）
                val existingCodes = report.buyOrders.map { it.stockCode }.toSet()
                val uniqueAiTop = aiTopEntities.filter { it.stockCode !in existingCodes }
                if (uniqueAiTop.isNotEmpty()) {
                    com.chin.stockanalysis.stock.database.AppBackgroundRunner.saveAiSelectedStocks(requireContext(), uniqueAiTop)
                    Log.i("MidTermQuant", "🤖 AI精選保存: ${uniqueAiTop.size}只")
                }
            }
        } catch (e: Exception) { Log.w("MidTermQuant", "保存失敗: ${e.message}") }
    }
}