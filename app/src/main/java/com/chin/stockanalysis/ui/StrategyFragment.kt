package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import android.widget.AdapterView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.backtest.StrategySelfTuner
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.strategy.strategies.*
import com.chin.stockanalysis.strategy.ui.StrategyAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

class StrategyFragment : Fragment() {

    private lateinit var layout: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: StrategyAdapter
    private lateinit var statusTv: TextView
    private lateinit var scanBtn: Button
    private lateinit var tuneBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var scopeSpinner: Spinner
    private lateinit var tradingDaySpinner: Spinner

    private var lastExecutedDate: String = "--"
    private var todayStr: String = LocalDate.now().toString()

    private var engine: StrategyEngine? = null
    private var screener: StockScreener? = null
    private var currentScope: ScanScope = ScanScope.FULL_MARKET
    private var selectedTradingDay: TradingDayOption = TradingDayOption.LAST_1

    enum class TradingDayOption(val label: String, val offset: Int) {
        LAST_1("上一个交易日", 1), LAST_3("前三个交易日", 3), LAST_5("前五个交易日", 5),
        LAST_10("前十个交易日", 10), LAST_30("前三十个交易日", 30),
        LAST_50("前五十个交易日", 50), LAST_100("前一百个交易日", 100);

        fun actualTradingDay(): String {
            var d = LocalDate.now().minusDays(offset.toLong())
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) {
                d = d.minusDays(1)
            }
            return d.toString()
        }

        fun displayLabel(): String {
            val dateStr = actualTradingDay()
            return "$label ($dateStr)"
        }
    }
    enum class ScanScope(val label: String) { FULL_MARKET("全市场"), SECTOR("板块"), WATCHLIST("自选股") }

    private val SECTORS = listOf("全市场", "化工", "有色金属", "半导体", "医药", "新能源", "军工", "消费", "金融", "AI算力", "商业航天")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        initEngine()
        buildUI()
        return layout
    }

    private fun initEngine() {
        val ctx = requireContext().applicationContext
        val repo = StockDataSourceFactory.createDefaultRepository(ctx)
        screener = StockScreener(repo)
        engine = StrategyEngine(ctx, screener!!).apply {
            registerStrategy(MovingAverageStrategy(screener!!))
            registerStrategy(VolumeBreakStrategy(screener!!))
            registerStrategy(LowValuationStrategy(screener!!))
            registerStrategy(GapUpMomentumStrategy(screener!!))
            registerStrategy(TurnoverFilterStrategy(screener!!))
            registerStrategy(EarlyMorningChaseStrategy(screener!!))
            registerStrategy(TailLowPickStrategy(screener!!))
        }
    }

    private fun buildUI() {
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 32, 16, 8); setBackgroundColor(Color.WHITE); elevation = 2f
        }
        header.addView(TextView(requireContext()).apply {
            text = "🎯 量化选股"; textSize = 22f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
        })
        header.addView(TextView(requireContext()).apply {
            text = "7 种策略 · 全市场/板块/自选股 · 多维度综合打分"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(0, 2, 0, 0)
        })
        layout.addView(header)

        val infoRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(16, 4, 16, 4); setBackgroundColor(Color.parseColor("#FFF8E1"))
        }
        val lastExecTv = TextView(requireContext()).apply {
            text = "已完成: $lastExecutedDate"; textSize = 11f; setTextColor(Color.parseColor("#E65100")); tag = "last_exec"
        }
        infoRow.addView(lastExecTv)
        infoRow.addView(TextView(requireContext()).apply {
            text = "  |  "; textSize = 11f; setTextColor(Color.parseColor("#CCCCCC"))
        })
        val recentTradingDayTv = TextView(requireContext()).apply {
            val recentDay = TradingDayOption.LAST_1.actualTradingDay()
            text = "最近交易日: $recentDay"; textSize = 11f; setTextColor(Color.parseColor("#2E7D32")); tag = "recent_trading_day"
        }
        infoRow.addView(recentTradingDayTv)
        layout.addView(infoRow)

        // 第一行：范围 + 交易日
        val row1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 2); setBackgroundColor(Color.WHITE)
        }
        row1.addView(TextView(requireContext()).apply {
            text = "范围:"; textSize = 11f; setTextColor(Color.parseColor("#888888"))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 2 }
        })
        scopeSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, SECTORS).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(0); setBackgroundColor(Color.parseColor("#E3F2FD")); setPadding(2, 1, 2, 1)
            layoutParams = LayoutParams(0, 42, 1.0f).apply { marginEnd = 8 }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentScope = if (SECTORS[position] == "全市场") ScanScope.FULL_MARKET else ScanScope.SECTOR
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        row1.addView(scopeSpinner)
        row1.addView(TextView(requireContext()).apply {
            text = "交易日:"; textSize = 11f; setTextColor(Color.parseColor("#888888"))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 2 }
        })
        tradingDaySpinner = Spinner(requireContext()).apply {
            val options = TradingDayOption.values().map { it.displayLabel() }
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(0); setBackgroundColor(Color.parseColor("#FFF3E0")); setPadding(2, 1, 2, 1)
            layoutParams = LayoutParams(0, 42, 1.5f)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedTradingDay = TradingDayOption.values()[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        row1.addView(tradingDaySpinner)
        layout.addView(row1)

        // 第二行：执行 + 调优 + 导入 + 自定义
        val row2 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 2, 8, 6); setBackgroundColor(Color.WHITE)
        }
        scanBtn = Button(requireContext()).apply {
            text = "执行"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#E65100"))
            setPadding(6, 6, 6, 6); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LayoutParams(0, 48, 1.0f).apply { marginEnd = 3 }
            setOnClickListener { runSelectedStrategies() }
        }
        row2.addView(scanBtn)
        tuneBtn = Button(requireContext()).apply {
            text = "调优"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#EF6C00"))
            setPadding(6, 6, 6, 6); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LayoutParams(0, 48, 1.0f).apply { marginEnd = 3 }
            setOnClickListener { runSelfTune() }
        }
        row2.addView(tuneBtn)
        val importBtn = Button(requireContext()).apply {
            text = "导入"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2E7D32"))
            setPadding(6, 6, 6, 6); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LayoutParams(0, 48, 1.0f).apply { marginEnd = 3 }
            setOnClickListener { importHistoricalData() }
        }
        row2.addView(importBtn)
        val addCustomBtn = Button(requireContext()).apply {
            text = "+自定义"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(6, 6, 6, 6); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LayoutParams(0, 48, 1.0f)
            setOnClickListener { showAddDialog() }
        }
        row2.addView(addCustomBtn)
        layout.addView(row2)

        val statusRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 2, 16, 4); setBackgroundColor(Color.WHITE)
        }
        progressBar = ProgressBar(requireContext()).apply {
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 }
        }
        statusRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply {
            text = "7 个策略已就绪"; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA"))
        }
        statusRow.addView(statusTv)
        layout.addView(statusRow)

        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, 4, 0, 4); clipToPadding = false
        }
        layout.addView(recyclerView)
        refreshList()
    }

    private fun runSelectedStrategies() {
        val eng = engine ?: return
        scanBtn.isEnabled = false; scanBtn.text = "⏳"; progressBar.visibility = View.VISIBLE

        val selectedDate = selectedTradingDay.actualTradingDay()
        statusTv.text = "  正在执行 ${selectedTradingDay.label} ($selectedDate)..."

        if (selectedTradingDay.offset > 1) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
                    val dates = db.dailySnapshotDao().getAvailableDates(selectedTradingDay.offset + 1)
                    if (dates.size < 2) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(),
                                "历史数据不足(${dates.size}天)，自动切换实时模式。\n请先用\"上一个交易日\"执行几次积累数据。",
                                Toast.LENGTH_LONG).show()
                            scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE
                            statusTv.text = "  数据不足，请先导入历史数据"
                        }
                        return@launch
                    }
                    val be = com.chin.stockanalysis.strategy.backtest.HistoricalBacktestEngine(requireContext())
                    val report = be.runHistoricalBacktest(
                        eng.getStrategies(),
                        selectedTradingDay.offset,
                        targetDate = selectedDate
                    )
                    withContext(Dispatchers.Main) {
                        scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE
                        lastExecutedDate = selectedDate
                        updateDateInfoRow()
                        statusTv.text = "  已完成 · ${selectedTradingDay.label} ($selectedDate)"
                        showBacktestReport(report)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE
                        statusTv.text = "  回测失败: ${e.message}"
                    }
                }
            }
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
                    val snapshots = db.dailySnapshotDao().getByDate(selectedDate)

                    if (snapshots.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(),
                                "$selectedDate 无历史数据，将使用实时行情执行。\n建议先导入历史数据。",
                                Toast.LENGTH_LONG).show()
                            executeRealTime(eng, selectedDate)
                        }
                        return@launch
                    }

                    val stockList = snapshots.map { snap ->
                        com.chin.stockanalysis.stock.StockRealtime(
                            code = snap.code, name = snap.name,
                            price = snap.close, open = snap.open,
                            yestClose = snap.open * 0.99,
                            high = snap.high, low = snap.low,
                            volume = snap.volume, amount = snap.amount,
                            changePercent = snap.changePct,
                            changeAmount = snap.close * snap.changePct / 100,
                            timestamp = System.currentTimeMillis()
                        )
                    }

                    val results = mutableListOf<ScreeningResult>()
                    for (strategy in eng.getStrategies()) {
                        if (!eng.isEnabled(strategy.id)) continue
                        try {
                            val result = strategy.screenWithData(stockList).getOrNull()
                            if (result != null) results.add(result)
                        } catch (e: Exception) {
                            android.util.Log.w("StrategyFragment", "策略 ${strategy.id} 执行异常: ${e.message}")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE
                        lastExecutedDate = selectedDate
                        updateDateInfoRow()
                        statusTv.text = "  已完成 · $selectedDate"
                        val totalHits = results.sumOf { it.hitCount }
                        if (totalHits > 0) showResultsDialog(results) else showResultsDialogSimple(results)
                        saveBacktestData(results)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE
                        statusTv.text = "  执行失败: ${e.message}"
                    }
                }
            }
        }
    }

    // ── 自测调优 ──
    private fun runSelfTune() {
        val eng = engine ?: return
        tuneBtn.isEnabled = false; tuneBtn.text = "⏳"; progressBar.visibility = View.VISIBLE
        statusTv.text = "  🔧 正在自测调优（回测→评估→调优）..."

        lifecycleScope.launch {
            try {
                val tuner = StrategySelfTuner(requireContext())
                val report = tuner.selfTune(
                    strategies = eng.getEnabledStrategies(),
                    backtestDays = 30,
                    targetAccuracy = 0.55f
                )
                withContext(Dispatchers.Main) {
                    tuneBtn.isEnabled = true; tuneBtn.text = "调优"; progressBar.visibility = View.GONE
                    statusTv.text = "  ✅ 调优完成"
                    AlertDialog.Builder(requireContext())
                        .setTitle("🔧 策略自测调优报告")
                        .setMessage(report.summary)
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("查看详细结果") { _, _ ->
                            // 弹出更详细的对话框
                            AlertDialog.Builder(requireContext())
                                .setTitle("📊 调优详情")
                                .setMessage(report.strategyTuneDetails.joinToString("\n\n") { d ->
                                    buildString {
                                        val icon = if (d.afterAccuracy > d.beforeAccuracy + 0.02f) "📈" else "➖"
                                        appendLine("$icon ${d.strategyName}")
                                        appendLine("   准确率: ${"%.1f".format(d.beforeAccuracy * 100)}% → ${"%.1f".format(d.afterAccuracy * 100)}%")
                                        appendLine("   平均收益: ${"%.2f".format(d.beforeAvgReturn)}% → ${"%.2f".format(d.afterAvgReturn)}%")
                                        if (d.wasTuned) appendLine("   调优: ${d.weightChanges.joinToString("; ")}")
                                    }
                                })
                                .setPositiveButton("关闭", null)
                                .show()
                        }
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tuneBtn.isEnabled = true; tuneBtn.text = "调优"; progressBar.visibility = View.GONE
                    statusTv.text = "  调优失败: ${e.message?.take(30)}"
                    Toast.makeText(requireContext(), "调优失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun executeRealTime(eng: StrategyEngine, selectedDate: String) {
        eng.runAll(lifecycleScope, onComplete = { results ->
            lifecycleScope.launch(Dispatchers.Main) {
                scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE
                lastExecutedDate = selectedDate
                updateDateInfoRow()
                statusTv.text = "  已完成 · $selectedDate (实时数据)"
                val totalHits = results.sumOf { it.hitCount }
                if (totalHits > 0) showResultsDialog(results) else showResultsDialogSimple(results)
                saveBacktestData(results)
                saveDailySnapshot()
            }
        })
    }

    private fun updateDateInfoRow() {
        view?.findViewWithTag<TextView>("last_exec")?.text = "已完成: $lastExecutedDate"
    }

    private fun saveDailySnapshot() {
        lifecycleScope.launch {
            try {
                val stocks = screener?.scanFullMarket() ?: return@launch
                if (stocks.isNotEmpty()) {
                    com.chin.stockanalysis.strategy.backtest.BacktestEngine(requireContext()).saveDailySnapshot(stocks)
                    statusTv.text = "  已完成 · 已保存${stocks.size}条行情快照"
                }
            } catch (e: Exception) { android.util.Log.w("StrategyFragment", "保存快照失败: ${e.message}") }
        }
    }

    private fun refreshList() {
        engine?.let {
            adapter = StrategyAdapter(it.getStrategies(), ::onStrategyClick, ::onStrategyToggle)
            recyclerView.adapter = adapter
            statusTv.text = "  ${it.getStrategies().size} 个策略已就绪"
        }
    }

    private fun onStrategyClick(strategy: Strategy) {
        StrategyDetailFragment().apply {
            this.strategy = strategy
            onSave = { updated ->
                engine?.apply {
                    removeStrategy(updated.id)
                    registerStrategy(updated)
                    refreshList()
                }
            }
        }.show(parentFragmentManager, "strategy_detail")
    }

    private fun onStrategyToggle(strategy: Strategy) {
        engine?.setEnabled(strategy.id, !engine!!.isEnabled(strategy.id))
    }

    private fun openResultDialog(result: ScreeningResult) {
        StrategyResultDialogFragment().apply {
            this.result = result
            onAskQuestion = { question ->
                val ctx = buildString {
                    appendLine("基于以下策略扫描结果，请回答用户问题：")
                    appendLine("策略: ${result.strategyName} | 扫描: ${result.totalScanned}只 | 命中: ${result.hitCount}只")
                    for ((i, s) in result.signals.take(10).withIndex())
                        appendLine("| ${i + 1} | ${s.stockName} | ${s.stockCode.takeLast(6)} | ${s.strength}% | ${"%.2f".format(s.currentPrice)} | ${"%.2f".format(s.changePercent)}% |")
                    appendLine()
                    appendLine("用户问题: $question")
                }
                if (activity is MainActivity) (activity as MainActivity).switchToChatAndSend(ctx)
                else Toast.makeText(requireContext(), "提问已记录: $question", Toast.LENGTH_SHORT).show()
            }
        }.show(parentFragmentManager, "strategy_result")
    }

    private fun showResultsDialogSimple(results: List<ScreeningResult>) {
        AlertDialog.Builder(requireContext()).setTitle("📊 扫描结果").setMessage(results.joinToString("\n") { r ->
            "${r.category.icon} ${r.strategyName}: 扫描${r.totalScanned}只, 命中${r.hitCount}只\n" +
                    r.signals.take(5).joinToString("\n") { "  ${it.emoji} ${it.stockName}(${it.stockCode}) 强度:${it.strength}%" }
        }).setPositiveButton("关闭", null).show()
    }

    private fun showResultsDialog(results: List<ScreeningResult>) {
        val scrollView = ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
        }
        for (r in results) {
            val title = TextView(requireContext()).apply {
                text = "${r.category.icon} ${r.strategyName}  (${r.hitCount}只 / ${r.scanTimeMs}ms)"
                textSize = 15f; setTextColor(Color.parseColor("#333333"))
                setTypeface(null, Typeface.BOLD); setPadding(0, 16, 0, 8)
            }
            title.setOnClickListener { openResultDialog(r) }; container.addView(title)
            val table = TableLayout(requireContext()).apply { isStretchAllColumns = true }
            val hr = TableRow(requireContext())
            for (h in listOf("名称", "代码", "强度", "价格", "涨幅"))
                hr.addView(TextView(requireContext()).apply {
                    text = h; textSize = 11f; setTextColor(Color.parseColor("#999999"))
                    setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(4, 4, 4, 4)
                })
            table.addView(hr)
            for (s in r.signals.take(10)) {
                val row = TableRow(requireContext())
                val c = when {
                    s.strength >= 80 -> Color.parseColor("#E65100")
                    s.strength >= 60 -> Color.parseColor("#2E7D32")
                    else -> Color.parseColor("#666666")
                }
                row.setOnClickListener { openResultDialog(r) }
                for (cell in listOf(
                    s.stockName, s.stockCode, "${s.strength}%",
                    String.format("%.2f", s.currentPrice),
                    "${String.format("%+.2f", s.changePercent)}%"
                ))
                    row.addView(TextView(requireContext()).apply {
                        text = cell; textSize = 11f; setTextColor(c)
                        gravity = Gravity.CENTER; setPadding(2, 4, 2, 4)
                    })
                table.addView(row)
            }
            container.addView(table)
        }

        container.addView(View(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2).apply { topMargin = 16; bottomMargin = 8 }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        val aiHeader = TextView(requireContext()).apply {
            text = "🤖 AI 综合预测（多策略+新闻因子+周期轮动）"
            textSize = 16f; setTextColor(Color.parseColor("#1565C0"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 8, 0, 8)
        }
        container.addView(aiHeader)

        val aiLoading = TextView(requireContext()).apply {
            text = "  ⏳ AI 正在分析中，请稍候..."
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
        }
        container.addView(aiLoading)

        scrollView.addView(container)

        AlertDialog.Builder(requireContext()).setTitle("📊 扫描结果 · 点击策略名查看详情")
            .setView(scrollView).setPositiveButton("关闭", null).show()

        val selectedDate = selectedTradingDay.actualTradingDay()
        lifecycleScope.launch {
            try {
                val aiEngine = AIPredictionEngine(requireContext())
                val prediction = aiEngine.predict(results, selectedDate)
                if (prediction != null && prediction.topPicks.isNotEmpty()) {
                    requireActivity().runOnUiThread {
                        aiLoading.text = ""
                        container.addView(TextView(requireContext()).apply {
                            text = "  📋 使用方案${prediction.mode}: ${prediction.modeReason}"
                            textSize = 11f; setTextColor(Color.parseColor("#E65100"))
                            setPadding(0, 4, 0, 8)
                        })
                        container.addView(TextView(requireContext()).apply {
                            text = "  📊 市场判断: ${prediction.marketOutlook}"
                            textSize = 11f; setTextColor(Color.parseColor("#666666"))
                            setPadding(0, 0, 0, 4)
                        })
                        val aiTable = TableLayout(requireContext()).apply {
                            isStretchAllColumns = true
                            setPadding(0, 8, 0, 8)
                        }
                        val aiHr = TableRow(requireContext())
                        for (h in listOf("排名", "股票", "得分", "涨概率", "建议"))
                            aiHr.addView(TextView(requireContext()).apply {
                                text = h; textSize = 11f; setTextColor(Color.parseColor("#1565C0"))
                                setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(2, 4, 2, 4)
                            })
                        aiTable.addView(aiHr)
                        for (pick in prediction.topPicks) {
                            val row = TableRow(requireContext())
                            row.setBackgroundColor(Color.parseColor("#E3F2FD"))
                            row.addView(TextView(requireContext()).apply {
                                text = "#${pick.rank}"; textSize = 11f; gravity = Gravity.CENTER
                                setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD)
                            })
                            row.addView(TextView(requireContext()).apply {
                                text = "${pick.stockName}\n${pick.stockCode.takeLast(6)}"
                                textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#333333"))
                            })
                            row.addView(TextView(requireContext()).apply {
                                text = "${pick.compositeScore}"; textSize = 12f; gravity = Gravity.CENTER
                                setTextColor(Color.parseColor("#E65100")); setTypeface(null, Typeface.BOLD)
                            })
                            row.addView(TextView(requireContext()).apply {
                                text = "${pick.upProbability}%"; textSize = 11f; gravity = Gravity.CENTER
                                setTextColor(Color.parseColor("#2E7D32"))
                            })
                            row.addView(TextView(requireContext()).apply {
                                text = pick.actionSuggestion; textSize = 10f; gravity = Gravity.CENTER
                                setTextColor(Color.parseColor("#666666"))
                            })
                            aiTable.addView(row)
                            val reasonRow = TableRow(requireContext())
                            reasonRow.addView(TextView(requireContext()).apply {
                                text = "  💡 ${pick.reason}"
                                textSize = 10f; setTextColor(Color.parseColor("#888888"))
                                setPadding(8, 2, 4, 6)
                            })
                            aiTable.addView(reasonRow)
                        }
                        container.addView(aiTable)
                        container.addView(TextView(requireContext()).apply {
                            text = "  ⚠️ ${prediction.riskWarning}"
                            textSize = 10f; setTextColor(Color.parseColor("#FF5722"))
                            setPadding(0, 8, 0, 4)
                        })
                    }
                } else {
                    requireActivity().runOnUiThread {
                        aiLoading.text = "  ⚠️ AI 预测暂不可用（请检查API配置）"
                    }
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    aiLoading.text = "  ⚠️ AI 预测失败: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun showAddDialog() {
        val name = EditText(requireContext()).apply { hint = "策略名称"; setSingleLine() }
        val desc = EditText(requireContext()).apply { hint = "策略描述"; setSingleLine() }
        AlertDialog.Builder(requireContext()).setTitle("添加自定义策略").setView(
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 8)
                addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })
                addView(desc, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }).setPositiveButton("创建") { _, _ ->
            val n = name.text.toString().trim()
            if (n.isNotBlank()) {
                val id = "custom_${System.currentTimeMillis()}"
                engine?.registerStrategy(object : Strategy {
                    override val id = id
                    override var name = n
                    override var description = desc.text.toString().trim().ifEmpty { "自定义策略" }
                    override val category = StrategyCategory.CUSTOM
                    override val config = StrategyConfig.fullMarket(20)
                    override var weightFactors = listOf(WeightFactor("default", "综合评分", 100, "默认权重"))
                    override val source = StrategySource.USER_CUSTOM
                    override suspend fun screen() = Result.success(
                        ScreeningResult(
                            strategyId = id, strategyName = n, category = StrategyCategory.CUSTOM,
                            signals = emptyList(), totalScanned = 0, scanTimeMs = 0
                        )
                    )
                    override suspend fun isAvailable() = false
                }); refreshList()
            }
        }.setNegativeButton("取消", null).show()
    }

    private fun importHistoricalData() {
        scanBtn.isEnabled = false; scanBtn.text = "⏳"; progressBar.visibility = View.VISIBLE
        statusTv.text = "  正在从东方财富拉取历史K线..."
        lifecycleScope.launch {
            try {
                val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                val total = fetcher.fetchAllHistoricalData(60) { p ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        statusTv.text = "  进度: ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条"
                    }
                }
                withContext(Dispatchers.Main) {
                    scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE
                    statusTv.text = "  ✅ 导入完成 · $total 条历史记录"
                    Toast.makeText(requireContext(), "导入成功！共 $total 条数据，可立即回测", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    scanBtn.isEnabled = true; scanBtn.text = "执行"; progressBar.visibility = View.GONE
                    statusTv.text = "  导入失败: ${e.message}"
                }
            }
        }
    }

    private fun showBacktestReport(report: com.chin.stockanalysis.strategy.backtest.HistoricalBacktestReport) {
        if (report.strategyReports.isEmpty()) {
            Toast.makeText(requireContext(), "暂无足够数据", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext()).setTitle("📊 策略历史回测报告")
            .setMessage(report.summary()).setPositiveButton("关闭", null).show()
    }

    private fun saveBacktestData(results: List<ScreeningResult>) {
        lifecycleScope.launch {
            try {
                val be = com.chin.stockanalysis.strategy.backtest.BacktestEngine(requireContext())
                for (r in results) be.savePredictions(r.strategyId, r.strategyName, r)
            } catch (e: Exception) {
                android.util.Log.w("StrategyFragment", "保存预测失败: ${e.message}")
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); engine?.cancelScan() }
}