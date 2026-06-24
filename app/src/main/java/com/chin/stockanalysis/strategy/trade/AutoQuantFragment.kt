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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.data.StrategyDataFeed
import com.chin.stockanalysis.strategy.data.ZiplinePipeline
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import com.chin.stockanalysis.ui.CrossTabBus
import com.chin.stockanalysis.agent.pipeline.AgentPipelineOrchestrator
import com.chin.stockanalysis.agent.pipeline.ui.PipelineProgressView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
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
class AutoQuantFragment : QuantFragmentBase() {

    private lateinit var runPipelineBtn: Button
    private lateinit var pipelineProgressView: PipelineProgressView
    private lateinit var aiPipelineBtn: Button
    private lateinit var reportDivider: View
    private lateinit var resultsContainer: LinearLayout

    // 短线量化配置 (持仓周期3天)
    private val shortTermConfig = SimulationTradeEngine.TradeSessionConfig(
        tradeDate = browsingDate.format(DATE_FMT),
        periods = listOf(1, 3),
        onlyMainBoard = true,
        maxFitRounds = 500,
        targetAccuracy = 0.55f,
        holdingPeriod = 3
    )

    private var pipelineFactors: ZiplinePipeline.FactorSet? = null
    private var allScreenings: Map<Strategy, ScreeningResult> = emptyMap()
    private var todayStocks: List<com.chin.stockanalysis.stock.StockRealtime> = emptyList()
    private var lastTradeDate: String = ""
    private var hasPipelineResult: Boolean = false

    private var aiPicks: List<AIPredictionEngine.AIPick> = emptyList()
    private var mergedPool: Map<String, List<Pair<String, Int>>> = emptyMap()
    private var mergedStockNames: Map<String, String> = emptyMap()

    companion object { private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd") }

    override fun getQuantType() = "ShortTermQuant"

    override fun onFittingClick() = autoFit()
    override fun onBacktrackClick() {
        // 短線量化暫無回調功能，顯示提示
        Toast.makeText(requireContext(), "短線量化暫無回調功能", Toast.LENGTH_SHORT).show()
    }
    override fun onClearClick() = clearData()
    override fun loadPositions() = refreshPositions()

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
        val mainBoardSwitch = Switch(requireContext()).apply { text = "仅主板"; textSize = 11f; isChecked = true; setTextColor(Color.parseColor("#333333")) }
        configRow.addView(mainBoardSwitch)
        rootLayout.addView(configRow)

        // ── 按钮行（一行 6 個按鈕） ──
        val btnRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4, 1, 4, 1) }
        aiPipelineBtn = Button(requireContext()).apply { text = "🧠 Agent分析"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#6A1B9A")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1.2f).apply { marginEnd = 1 }; setOnClickListener { runAIPipeline() } }; btnRow.addView(aiPipelineBtn)
        runPipelineBtn = Button(requireContext()).apply { text = "▶ 建仓"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1f).apply { marginEnd = 1 }; setOnClickListener { runBuildAndBuy() } }; btnRow.addView(runPipelineBtn)
        val fitBtn = Button(requireContext()).apply { text = "🔧 拟合"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#EF6C00")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }; setOnClickListener { autoFit() } }; btnRow.addView(fitBtn)
        val sellBtn = Button(requireContext()).apply { text = "💰 卖出 ▾"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#00897B")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1f).apply { marginEnd = 1 }; setOnClickListener { showSellMenu(it) } }; btnRow.addView(sellBtn)
        val dataBtn = Button(requireContext()).apply { text = "🗄️ 数据"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#455A64")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }; setOnClickListener { showDataMenuLocal() } }; btnRow.addView(dataBtn)
        val clearBtnLocal = Button(requireContext()).apply { text = "🗑️ 清除"; textSize = 9f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#C62828")); setPadding(4,1,4,1); setMinWidth(0); setMinimumWidth(0); layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.7f); setOnClickListener { clearData() } }; btnRow.addView(clearBtnLocal)
        rootLayout.addView(btnRow)

        val progressRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 4, 16, 4) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } }
        progressRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "就绪"; textSize = 12f; setTextColor(Color.parseColor("#AAAAAA")) }
        progressRow.addView(statusTv); rootLayout.addView(progressRow)

        // ── AI 智能體流水線進度面板 ──
        pipelineProgressView = PipelineProgressView(requireContext()).apply { visibility = View.GONE }
        rootLayout.addView(pipelineProgressView)

        rootLayout.addView(View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1); setBackgroundColor(Color.parseColor("#DDDDDD")) })

        // ── 持仓区 (顶部) ──
        positionContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 4, 8, 4) }
        rootLayout.addView(positionContainer)

        // ── 分割线 (有结果时显示) ──
        reportDivider = View(requireContext()).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 4 }; setBackgroundColor(Color.parseColor("#DDDDDD")) }
        rootLayout.addView(reportDivider)

        // ── 结果区 (底部，参考中线量化 UI) ──
        resultsContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(8, 4, 8, 4) }
        rootLayout.addView(resultsContainer)
    }

    private fun updateViewSplit() {
        if (hasPipelineResult) {
            resultsContainer.visibility = View.VISIBLE; reportDivider.visibility = View.VISIBLE
        } else { resultsContainer.visibility = View.GONE; reportDivider.visibility = View.GONE }
        rootLayout.requestLayout()
    }

    // ═══════════════════════════════════════
    // 持仓
    // ═══════════════════════════════════════

    override fun refreshPositions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == "ShortTermQuant" && (it.status == "BUYING" || it.status == "PENDING") }
                    .sortedByDescending { it.tradeDate }
                val dates = db.dailySnapshotDao().getAvailableDates(15)
                    .filter { it >= orders.minOf { it.tradeDate } && it <= browsingDate.format(DATE_FMT) }
                    .sorted().takeLast(10)
                val priceMap = mutableMapOf<String, MutableMap<String, Double>>()
                for (date in dates) { val snaps = db.dailySnapshotDao().getByDate(date); for (snap in snaps) priceMap.getOrPut(snap.code) { mutableMapOf() }[date] = snap.close }
                withContext(Dispatchers.Main) { renderPositionTable(orders, dates, priceMap) }
            } catch (_: Exception) {}
        }
    }

    private fun renderPositionTable(orders: List<StrategyTradeOrderEntity>, dates: List<String> = emptyList(), priceMap: Map<String, Map<String, Double>> = emptyMap()) {
        positionContainer.removeAllViews()
        if (orders.isEmpty()) { positionContainer.addView(TextView(requireContext()).apply { text = "📌 暂无持仓记录"; textSize = 11f; setTextColor(Color.parseColor("#999999")); setPadding(0, 4, 0, 4) }); return }

        // 匯總
        val lastDate = dates.lastOrNull() ?: browsingDate.format(DATE_FMT)
        var totalCost = 0.0; var totalValue = 0.0
        for (order in orders) {
            totalCost += order.buyPrice * order.quantity
            val lastPrice = priceMap[order.stockCode]?.get(lastDate) ?: order.buyPrice
            totalValue += lastPrice * order.quantity
        }
        val totalPnl = totalValue - totalCost
        val totalPnlPct = if (totalCost > 0) (totalPnl / totalCost * 100) else 0.0
        val pnlColor = if (totalPnl >= 0) "#D32F2F" else "#2E7D32"
        val pnlStr = "${if (totalPnl >= 0) "+" else ""}¥${"%.0f".format(totalPnl)} (${"%.2f".format(totalPnlPct)}%)"

        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 2, 0, 2)
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = "📌 短线量化持仓 (启动资金 ¥1,000,000)"; textSize = 12f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = "总持仓 ${orders.size} 只  |  "; textSize = 10f; setTextColor(Color.parseColor("#666666")); gravity = Gravity.END
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = "总盈亏 $pnlStr"; textSize = 10f; setTextColor(Color.parseColor(pnlColor)); gravity = Gravity.END
        })
        positionContainer.addView(titleRow)

        // 多日漲跌表格（模擬交易風格）
        val scroll = HorizontalScrollView(requireContext())
        val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val headerRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 4); setBackgroundColor(Color.parseColor("#EEEEEE")) }
        for (header in listOf("股票", "建仓日", "成本")) headerRow.addView(createCell(header, 60, "#666666", 10f, bold = true))
        headerRow.addView(createCell("持仓", 45, "#666666", 9f, bold = true))
        for (date in dates) { val label = date.takeLast(5); headerRow.addView(createCell(label, 72, "#666666", 10f, bold = true)) }
        headerRow.addView(createCell("💰 卖出", 50, "#666666", 9f, bold = true))
        table.addView(headerRow)

        for (order in orders) {
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 2) }
            val nameCell = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(dpToPx(60), LinearLayout.LayoutParams.WRAP_CONTENT); gravity = Gravity.CENTER }
            nameCell.addView(TextView(requireContext()).apply { text = order.stockName.take(6); textSize = 11f; setTextColor(Color.parseColor("#222222")); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) })
            nameCell.addView(TextView(requireContext()).apply { text = order.stockCode.takeLast(6); textSize = 8f; setTextColor(Color.parseColor("#AAAAAA")); gravity = Gravity.CENTER })
            row.addView(nameCell)
            row.addView(createCell(order.tradeDate.takeLast(5), 60, "#333333", 10f))
            row.addView(createCell("¥${"%.2f".format(order.buyPrice)}", 60, "#333333", 10f))
            row.addView(createCell("${order.quantity}", 45, "#1565C0", 9f))
            for (date in dates) {
                val price = priceMap[order.stockCode]?.get(date)
                val cellText: String; val cellColor: String
                if (price == null) { cellText = "—"; cellColor = "#999999" }
                else if (date < order.tradeDate) { cellText = "¥${"%.2f".format(price)}"; cellColor = "#000000" }
                else if (date == order.tradeDate) { cellText = "¥${"%.2f".format(price)}\n0.00%"; cellColor = "#999999" }
                else {
                    val pnl = if (order.buyPrice > 0) (price - order.buyPrice) / order.buyPrice * 100 else 0.0
                    cellText = "¥${"%.2f".format(price)}\n${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)}%"
                    cellColor = if (pnl >= 0) "#D32F2F" else "#2E7D32"
                }
                row.addView(TextView(requireContext()).apply { text = cellText; textSize = 9f; setTextColor(Color.parseColor(cellColor)); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dpToPx(72), LinearLayout.LayoutParams.WRAP_CONTENT); setPadding(2, 4, 2, 4); setLineSpacing(2f, 1f) })
            }
            val lastPrice = priceMap[order.stockCode]?.get(dates.lastOrNull())
            val sellBtn = Button(requireContext()).apply {
                text = "賣"; textSize = 9f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#C62828"))
                layoutParams = LinearLayout.LayoutParams(dpToPx(50), dpToPx(28)); setPadding(2, 0, 2, 0)
                isEnabled = lastPrice != null
                setOnClickListener { showSellConfirmDialog(order, lastPrice ?: order.buyPrice) }
            }
            row.addView(sellBtn)
            table.addView(row)
        }
        scroll.addView(table)
        val verticalScroll = ScrollView(requireContext()); verticalScroll.addView(scroll); positionContainer.addView(verticalScroll)
    }

    // createCell, dpToPx, showSellConfirmDialog, executeSingleSell 已由基類 QuantFragmentBase 提供

    // ═══════════════════════════════════════
    // 建倉（智能分流：Agent結果 vs zipline）
    // ═══════════════════════════════════════

    /**
     * 建倉按鈕點擊：
     * - 如果有 Agent 分析結果 → 從結果中買入合適買點的股票
     * - 否則 → 執行 zipline 然後尋找可買入且符合騰籠換鳥的股票
     */
    private fun runBuildAndBuy() {
        if (hasAgentResult && aiPicks.isNotEmpty()) {
            // 有 Agent 分析結果，直接從結果中建倉
            statusTv.text = "🔄 從 Agent 分析結果中建倉..."
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    buyAiPicksInternal()
                    analyzeSwapCandidates()
                    withContext(Dispatchers.Main) {
                        statusTv.text = "✅ 建倉完成（${aiPicks.size} 只 Agent 精選股）"
                        refreshPositions()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "❌ 建倉失敗: ${e.message?.take(30)}"
                    }
                }
            }
        } else {
            // 沒有 Agent 結果，執行 zipline 選股 + 建倉 + 騰籠換鳥
            runPipeline()
        }
    }

    /** 標記是否有 Agent 分析結果 */
    private var hasAgentResult = false

    // ═══════════════════════════════════════
    // 短线选股（zipline 全流程）
    // ═══════════════════════════════════════

    private fun runPipeline() {
        val eng = engine ?: return
        runPipelineBtn.isEnabled = false; runPipelineBtn.text = "⏳ 短线选股"; progressBar.visibility = View.VISIBLE; statusTv.text = "检查数据..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 检查是否需要先导入数据
                val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val prefs = requireContext().getSharedPreferences("data_import", android.content.Context.MODE_PRIVATE)
                val lastImport = prefs.getString("last_import_date", "") ?: ""
                val db = StockDatabase.getInstance(requireContext())
                val todaySnaps = db.dailySnapshotDao().getByDate(today)
                val needImport = todaySnaps.size < 100 || lastImport != today
                if (needImport) {
                    withContext(Dispatchers.Main) { statusTv.text = "📥 数据不足，自动导入..." }
                    val f = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                    f.fetchAllHistoricalData(60) { p ->
                        lifecycleScope.launch(Dispatchers.Main) { statusTv.text = "📥 导入: ${p.completedStocks}/${p.totalStocks}" }
                    }
                    prefs.edit().putString("last_import_date", today).apply()
                    withContext(Dispatchers.Main) { statusTv.text = "✅ 导入完成，开始 Pipeline" }
                }
                // 建仓日使用最近的交易日（避免非交易日）
                val tradeDay = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                lastTradeDate = tradeDay
                val feed = StrategyDataFeed(requireContext())
                // 使用備選池而非全部A股，大幅提升速度
                val poolCodes = try { com.chin.stockanalysis.strategy.data.CandidatePool.getPoolCodes(requireContext()) } catch (_: Exception) { emptyList() }
                val stocks = if (poolCodes.isNotEmpty()) {
                    feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = true, stockCodes = poolCodes.toSet()))
                } else {
                    feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = true))
                }
                todayStocks = stocks
                if (stocks.isEmpty()) { withContext(Dispatchers.Main) { statusTv.text="⚠️ 无交易日数据"; runPipelineBtn.isEnabled=true; runPipelineBtn.text="▶ 短线选股"; progressBar.visibility=View.GONE }; return@launch }
                val pipeline = ZiplinePipeline(requireContext()); val factors = pipeline.computeAll(stocks, today, 30); pipelineFactors = factors
                val codeToName = try { StockDatabase.getInstance(requireContext()).stockBasicDao().getAll().associate { it.code to it.name } } catch (_: Exception) { emptyMap() }
                val screenings = mutableMapOf<Strategy, ScreeningResult>(); val screeningList = mutableListOf<ScreeningResult>()
                for (strategy in eng.getStrategies()) { if (!eng.isEnabled(strategy.id) || strategy.id == "ai_prediction") continue; try { val r = strategy.screenWithData(stocks); r.getOrNull()?.let { screenings[strategy]=it; screeningList.add(it) } } catch (_: Exception) {} }
                allScreenings = screenings
                // 合并池
                val pool = mutableMapOf<String, MutableList<Pair<String, Int>>>()
                for ((s, sc) in screenings) for (sig in sc.signals.distinctBy { it.stockCode }) pool.getOrPut(sig.stockCode){ mutableListOf() }.add(s.name to sig.strength)
                mergedPool = pool.mapValues { it.value.sortedByDescending { p->p.second } }
                mergedStockNames = pool.keys.associateWith { c -> screenings.values.firstNotNullOfOrNull { sc->sc.signals.find{it.stockCode==c}?.stockName } ?: codeToName[c] ?: c }
                // AI 精选
                if (screeningList.isNotEmpty()) { withContext(Dispatchers.Main) { statusTv.text = "🤖 AI 正在精选..." }; try { val p = AIPredictionEngine(requireContext()).predict(screeningList, today); if (p!=null && p.topPicks.isNotEmpty()) aiPicks = p.topPicks else aiPicks = emptyList() } catch (_: Exception) { aiPicks = emptyList() } }
                // 发布到跨Tab总线
                CrossTabBus.postMergedPool(mergedPool)
                CrossTabBus.postAiTopPicks(aiPicks)
                CrossTabBus.postStrategyResults(screeningList)
                withContext(Dispatchers.Main) {
                    showPipelineTable(screenings, factors, today); hasPipelineResult = true; updateViewSplit()
                    statusTv.text = "✅ 短线选股完成 (${factors.ma5.size} 只有效)"; runPipelineBtn.isEnabled = true; runPipelineBtn.text = "▶ 短线选股"; progressBar.visibility = View.GONE
                    // 自動觸發建倉 + 騰龍換鳥分析
                    if (aiPicks.isNotEmpty()) {
                        statusTv.text = "🔄 正在自動建倉 + 騰龍換鳥分析..."
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                buyAiPicksInternal()
                                analyzeSwapCandidates()
                                withContext(Dispatchers.Main) {
                                    statusTv.text = "✅ 短线选股 + 建仓 + 騰龍換鳥分析完成"
                                    refreshPositions()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    statusTv.text = "✅ 短线选股完成 (建倉分析失敗: ${e.message?.take(30)})"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text="❌ 短线选股失败: ${e.message?.take(40)}"; runPipelineBtn.isEnabled=true; runPipelineBtn.text="▶ 短线选股"; progressBar.visibility=View.GONE } }
        }
    }

    private fun buyAiPicks() {
        if (aiPicks.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val existingCodes = db.strategyTradeOrderDao().getRecent(200).filter { it.orderType == "ShortTermQuant" && (it.status == "BUYING" || it.status == "PENDING") }.map { it.stockCode }.toSet()
                val toInsert = mutableListOf<StrategyTradeOrderEntity>()
                for (pick in aiPicks) {
                    if (pick.stockCode in existingCodes) continue
                    // 從即時行情獲取買入價格（避免 buyPrice=0）
                    val snap = todayStocks.find { it.code == pick.stockCode }
                    val buyPrice = snap?.price ?: 0.0
                    if (buyPrice <= 0) {
                        Log.w("AutoQuant", "AI精選 ${pick.stockName} 無即時價格，跳過買入")
                        continue
                    }
                    toInsert.add(StrategyTradeOrderEntity(
                        strategyId = "AI_Selected", stockCode = pick.stockCode, stockName = pick.stockName,
                        tradeDate = lastTradeDate, buyPrice = buyPrice,
                        buyTime = java.time.LocalTime.now().toString().take(8),
                        quantity = 100, orderType = "ShortTermQuant", status = "BUYING",
                        reason = "AI精选: ${pick.reason.take(60)}", scoreAtBuy = pick.compositeScore,
                        createdAt = System.currentTimeMillis()
                    ))
                }
                if (toInsert.isNotEmpty()) {
                    db.strategyTradeOrderDao().insertAll(toInsert)
                    withContext(Dispatchers.Main) {
                        statusTv.text = "✅ 已买入 ${toInsert.size} 只AI精选股"
                        refreshPositions()
                        Toast.makeText(requireContext(), "已买入 ${toInsert.size} 只", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) { statusTv.text = "⚠️ AI精选股已全部持仓或無即時價格" }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text = "❌ 买入失败: ${e.message?.take(30)}" } }
        }
    }

    // ═══════════════════════════════════════
    // 內部建倉 + 騰龍換鳥（不顯示 Toast，用於自動流程）
    // ═══════════════════════════════════════

    private suspend fun buyAiPicksInternal() {
        if (aiPicks.isEmpty()) return
        val db = StockDatabase.getInstance(requireContext())
        val existingCodes = db.strategyTradeOrderDao().getRecent(200)
            .filter { it.orderType == "ShortTermQuant" && (it.status == "BUYING" || it.status == "PENDING") }
            .map { it.stockCode }.toSet()
        val toInsert = mutableListOf<StrategyTradeOrderEntity>()
        for (pick in aiPicks) {
            if (pick.stockCode in existingCodes) continue
            val snap = todayStocks.find { it.code == pick.stockCode }
            val buyPrice = snap?.price ?: 0.0
            if (buyPrice <= 0) continue
            toInsert.add(StrategyTradeOrderEntity(
                strategyId = "AI_Selected", stockCode = pick.stockCode, stockName = pick.stockName,
                tradeDate = lastTradeDate, buyPrice = buyPrice,
                buyTime = java.time.LocalTime.now().toString().take(8),
                quantity = 100, orderType = "ShortTermQuant", status = "BUYING",
                reason = "AI精选: ${pick.reason.take(60)}", scoreAtBuy = pick.compositeScore,
                createdAt = System.currentTimeMillis()
            ))
        }
        if (toInsert.isNotEmpty()) {
            db.strategyTradeOrderDao().insertAll(toInsert)
            Log.i("AutoQuant", "✅ 自動建倉 ${toInsert.size} 只")
        }
    }

    /**
     * 騰龍換鳥分析：比較現有持倉和新選股的強度，
     * 如果新選股強度明顯高於現有持倉，建議換股
     */
    private suspend fun analyzeSwapCandidates() {
        if (aiPicks.isEmpty()) return
        val db = StockDatabase.getInstance(requireContext())
        val existing = db.strategyTradeOrderDao().getRecent(200)
            .filter { it.orderType == "ShortTermQuant" && it.status == "BUYING" }
        if (existing.isEmpty()) return

        // 找出現有持倉中強度最低的股票
        val weakest = existing.minByOrNull { it.scoreAtBuy }
        // 找出新選股中強度最高的股票
        val strongest = aiPicks.maxByOrNull { it.compositeScore }
        if (weakest != null && strongest != null && strongest.compositeScore > weakest.scoreAtBuy * 1.3f) {
            Log.i("AutoQuant", "🔄 騰龍換鳥建議: 賣出 ${weakest.stockName}(${weakest.scoreAtBuy}) → 買入 ${strongest.stockName}(${strongest.compositeScore})")
        }
    }

    // ═══════════════════════════════════════
    // UI 辅助
    // ═══════════════════════════════════════

    private fun getSector(code: String): String = try { kotlinx.coroutines.runBlocking { com.chin.stockanalysis.stock.database.StockDataCenter.getSectorsByStock(code).firstOrNull() ?: "" } } catch (_: Exception) { "" }

    private fun tableHeaderRow(): TableRow { val hr = TableRow(requireContext()); for (h in listOf("名称","板块","代码","强度","价格","涨幅")) hr.addView(TextView(requireContext()).apply { text=h; textSize=10f; setTextColor(Color.parseColor("#999999")); setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER; setPadding(2,4,2,4) }); return hr }

    private fun tableDataRow(name: String, code: String, strength: Int, price: Double, changePct: Double): TableRow {
        val row = TableRow(requireContext()); val sc = when { strength>=80 -> Color.parseColor("#E65100"); strength>=60 -> Color.parseColor("#2E7D32"); else -> Color.parseColor("#666666") }
        row.addView(TextView(requireContext()).apply { text=name.take(6); textSize=10f; setTextColor(Color.parseColor("#222222")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text=getSector(code).take(6).ifEmpty{"-"}; textSize=9f; setTextColor(Color.parseColor("#1565C0")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text=code.takeLast(6); textSize=10f; setTextColor(sc); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text="${strength}%"; textSize=10f; setTextColor(sc); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text="%.2f".format(price); textSize=10f; setTextColor(Color.parseColor("#E53935")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        row.addView(TextView(requireContext()).apply { text="${if(changePct>=0)"+" else ""}${"%.2f".format(changePct)}%"; textSize=10f; setTextColor(if(changePct>=0) Color.parseColor("#E53935") else Color.parseColor("#43A047")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        return row
    }

    private fun showPipelineTable(allScreenings: Map<Strategy, ScreeningResult>, factors: ZiplinePipeline.FactorSet, today: String) {
        resultsContainer.removeAllViews()
        val sv = ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8) }
        container.addView(TextView(requireContext()).apply { text="📊 短线选股因子计算结果 ($today)"; textSize=14f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null,Typeface.BOLD); setPadding(0,8,0,8) })
        container.addView(TextView(requireContext()).apply { text="有效股票: ${factors.ma5.size}只 | 合并池: ${mergedPool.size}只 | AI精选: ${aiPicks.size}只"; textSize=11f; setTextColor(Color.parseColor("#666666")); setPadding(0,0,0,8) })

        // ① AI 精选 Top3-5
        if (aiPicks.isNotEmpty()) {
            container.addView(TextView(requireContext()).apply { text="🤖 AI 精选 Top3-5"; textSize=15f; setTextColor(Color.parseColor("#E65100")); setTypeface(null,Typeface.BOLD); setPadding(0,12,0,6) })
            val t1 = TableLayout(requireContext()).apply { isStretchAllColumns=true }; t1.addView(tableHeaderRow())
            for (p in aiPicks) { val snap = todayStocks.find { it.code==p.stockCode }; t1.addView(tableDataRow(p.stockName, p.stockCode, p.compositeScore, snap?.price?:0.0, snap?.changePercent?:0.0)) }
            container.addView(t1)
        }

        // ② 合并池
        container.addView(View(requireContext()).apply { layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1).apply{topMargin=12}; setBackgroundColor(Color.parseColor("#DDDDDD")) })
        container.addView(TextView(requireContext()).apply { text="📋 策略合并池 (${mergedPool.size}只)"; textSize=13f; setTextColor(Color.parseColor("#333333")); setTypeface(null,Typeface.BOLD); setPadding(0,8,0,6) })
        val t2 = TableLayout(requireContext()).apply { isStretchAllColumns=true }
        val hr2 = TableRow(requireContext()); for (h in listOf("名称","板块","代码","命中","策略","热度")) hr2.addView(TextView(requireContext()).apply { text=h; textSize=10f; setTextColor(Color.parseColor("#999999")); setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
        t2.addView(hr2)
        for ((code, hits) in mergedPool.entries.sortedByDescending { it.value.size*50+(it.value.firstOrNull()?.second?:0) }.take(20)) {
            val name = mergedStockNames[code] ?: code.takeLast(6); val maxStr = hits.firstOrNull()?.second ?: 0
            val row = TableRow(requireContext())
            row.addView(TextView(requireContext()).apply { text=name.take(6); textSize=10f; setTextColor(Color.parseColor("#222222")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text=getSector(code).take(6).ifEmpty{"-"}; textSize=9f; setTextColor(Color.parseColor("#1565C0")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text=code.takeLast(6); textSize=10f; setTextColor(Color.parseColor("#333333")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text="${hits.size}次"; textSize=9f; setTextColor(Color.parseColor("#E65100")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text=hits.take(3).joinToString { "${it.first.take(3)}${it.second}" }; textSize=8f; setTextColor(Color.parseColor("#666666")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            row.addView(TextView(requireContext()).apply { text="${maxStr}%"; textSize=10f; setTextColor(Color.parseColor(if(maxStr>=70)"#E65100" else "#333333")); gravity=Gravity.CENTER; setPadding(2,4,2,4) })
            t2.addView(row)
        }; container.addView(t2)

        // ③ 各策略明细
        container.addView(View(requireContext()).apply { layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1).apply{topMargin=12}; setBackgroundColor(Color.parseColor("#DDDDDD")) })
        for ((strategy, screening) in allScreenings) {
            container.addView(TextView(requireContext()).apply { text="${strategy.category.icon} ${strategy.name}  (${screening.hitCount}只 / ${screening.scanTimeMs}ms)"; textSize=13f; setTextColor(Color.parseColor("#333333")); setTypeface(null,Typeface.BOLD); setPadding(0,12,0,6) })
            if (screening.signals.isEmpty()) { container.addView(TextView(requireContext()).apply { text="  ⚠️ 无命中信号"; textSize=11f; setTextColor(Color.parseColor("#999999")); setPadding(0,0,0,8) }); continue }
            val table = TableLayout(requireContext()).apply { isStretchAllColumns=true }; table.addView(tableHeaderRow())
            for (sig in screening.signals.distinctBy{it.stockCode}.take(15)) table.addView(tableDataRow(sig.stockName.take(6), sig.stockCode, sig.strength, sig.currentPrice, sig.changePercent))
            container.addView(table)
        }
        sv.addView(container); resultsContainer.addView(sv)
    }

    // ═══════════════════════════════════════
    // 数据查看
    // ═══════════════════════════════════════

    // 數據菜單已由基類 QuantFragmentBase 提供（showDataMenu）
    // 短線量化使用基類的數據菜單功能
    private fun showDataMenuLocal() {
        if (allScreenings.isEmpty() && mergedPool.isEmpty()) { Toast.makeText(requireContext(), "请先运行短线选股", Toast.LENGTH_SHORT).show(); return }
        val options = arrayOf("📊 策略明细 (${allScreenings.size}个)", "📋 合并池 (${mergedPool.size}只)", "🤖 AI精选 (${aiPicks.size}只)")
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("短线选股数据查看").setItems(options) { _, which ->
            when (which) { 0->showPerStrategyDetail(); 1->showMergedPoolDetail(); 2->showAiPicksDetail() }
        }.setNegativeButton("关闭", null).show()
    }

    private fun showPerStrategyDetail() {
        val sb = StringBuilder(); sb.appendLine("📊 策略明细 (合并前)")
        for ((strategy, screening) in allScreenings) {
            sb.appendLine(); sb.appendLine("${strategy.category.icon} ${strategy.name} (${screening.hitCount}只 / ${screening.scanTimeMs}ms)")
            if (screening.signals.isEmpty()) { sb.appendLine("  ⚠️ 无命中信号"); continue }
            for (sig in screening.signals.distinctBy{it.stockCode}.take(10)) { val s = getSector(sig.stockCode).take(6).ifEmpty{"-"}; sb.appendLine("  ${sig.stockName.take(6)}(${sig.stockCode.takeLast(6)}) [$s] ${sig.strength}% ¥${"%.2f".format(sig.currentPrice)} ${if(sig.changePercent>=0)"+" else ""}${"%.2f".format(sig.changePercent)}%") }
        }; showDialog("策略明细", sb.toString())
    }

    private fun showMergedPoolDetail() {
        val sb = StringBuilder(); sb.appendLine("📋 合并池 (${mergedPool.size}只)")
        for ((code, hits) in mergedPool.entries.sortedByDescending { it.value.size*50+(it.value.firstOrNull()?.second?:0) }.take(30)) {
            val name = mergedStockNames[code] ?: code.takeLast(6); val s = getSector(code).take(6).ifEmpty{"-"}; val maxStr = hits.firstOrNull()?.second ?: 0
            sb.appendLine("${name.take(6)}(${code.takeLast(6)}) [$s] ${hits.size}次命中 最高${maxStr}%")
            sb.appendLine("  策略: ${hits.joinToString { "${it.first.take(4)}(${it.second})" }}")
        }; showDialog("合并池", sb.toString())
    }

    private fun showAiPicksDetail() {
        val sb = StringBuilder(); sb.appendLine("🤖 AI 精选 Top3-5"); if (aiPicks.isEmpty()) sb.appendLine("(暂无结果)"); else for (p in aiPicks) { val snap = todayStocks.find{it.code==p.stockCode}; val s = getSector(p.stockCode).take(6).ifEmpty{"-"}; val price = snap?.price ?:0.0; val chg = snap?.changePercent ?:0.0; sb.appendLine("#${p.rank} ${p.stockName}(${p.stockCode.takeLast(6)}) [$s] 评分:${p.compositeScore} 概率:${p.upProbability}%"); sb.appendLine("  ¥${"%.2f".format(price)} ${if(chg>=0)"+" else ""}${"%.2f".format(chg)}% ${p.actionSuggestion}"); sb.appendLine("  ${p.reason}"); sb.appendLine() }; showDialog("AI精选", sb.toString())
    }

    // ═══════════════════════════════════════
    // 操作
    // ═══════════════════════════════════════

    private fun importData() {
        runPipelineBtn.isEnabled=false; runPipelineBtn.text="⏳"; progressBar.visibility=View.VISIBLE; statusTv.text="正在从东方财富拉取历史K线..."
        lifecycleScope.launch(Dispatchers.IO) { try { val f=com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext()); val total=f.fetchAllHistoricalData(60){p-> lifecycleScope.launch(Dispatchers.Main){statusTv.text="进度: ${p.completedStocks}/${p.totalStocks} 只 · ${p.totalRecords} 条"} }; withContext(Dispatchers.Main){runPipelineBtn.isEnabled=true; runPipelineBtn.text="▶ 短线选股"; progressBar.visibility=View.GONE; statusTv.text="✅ 导入完成 · $total 条历史记录"} } catch (e:Exception){withContext(Dispatchers.Main){runPipelineBtn.isEnabled=true; runPipelineBtn.text="▶ 短线选股"; progressBar.visibility=View.GONE; statusTv.text="导入失败: ${e.message}"} } }
    }

    // 賣出功能已由基類 QuantFragmentBase 提供（showSellMenu / runAutoSellEvaluation / executeAutoSell）
    // 短線量化使用基類的完整賣出評估和執行功能

    /** 自动拟合 — 遍历最近交易日，验证预测准确率并更新策略权重 */
    private fun autoFit() {
        val eng = engine ?: return
        runPipelineBtn.isEnabled = false; runPipelineBtn.text = "⏳ 拟合中"; progressBar.visibility = View.VISIBLE; statusTv.text = "🔧 自动拟合中..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val te = SimulationTradeEngine(requireContext())
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val recentDates = StockDatabase.getInstance(requireContext()).dailySnapshotDao().getAvailableDates(30).sorted()
                if (recentDates.size < 2) {
                    withContext(Dispatchers.Main) { statusTv.text = "⚠️ 数据不足"; runPipelineBtn.isEnabled = true; runPipelineBtn.text = "▶ 短线选股"; progressBar.visibility = View.GONE }
                    return@launch
                }
                val results = te.autoFit(strategies, recentDates)
                withContext(Dispatchers.Main) {
                    statusTv.text = "✅ 拟合完成: ${results.size} 策略优化"; runPipelineBtn.isEnabled = true; runPipelineBtn.text = "▶ 短线选股"; progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "拟合完成", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "❌ 拟合失败: ${e.message?.take(30)}"; runPipelineBtn.isEnabled = true; runPipelineBtn.text = "▶ 短线选股"; progressBar.visibility = View.GONE }
            }
        }
    }

    /** 供外部调用的自动触发 Pipeline */
    fun autoRunPipeline() {
        if (runPipelineBtn.isEnabled) runPipeline()
    }

    /**
     * 🧠 Agent 分析（AI 動態選擇模式：六智體/七智體/精簡版）
     */
    private fun runAIPipeline() {
        val inputEt = EditText(requireContext()).apply {
            hint = "輸入標的（如：生益科技、光通信板塊、半導體）"
            textSize = 13f
            setPadding(16, 12, 16, 12)
            setSingleLine(true)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("🧠 Agent 分析")
            .setMessage("輸入要分析的標的或板塊，AI 將根據賽道自動選擇分析模式：\n• 六智體通用（消費/醫藥/周期）\n• 七智體賣水人（光通信/半導體）\n• 精簡版 5+1（默認）")
            .setView(inputEt)
            .setPositiveButton("開始分析") { _, _ ->
                val target = inputEt.text.toString().trim()
                if (target.isBlank()) {
                    Toast.makeText(requireContext(), "請輸入標的", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                executeAIPipeline(target)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeAIPipeline(target: String) {
        aiPipelineBtn.isEnabled = false
        aiPipelineBtn.text = "⏳ 分析中..."
        pipelineProgressView.visibility = View.VISIBLE
        pipelineProgressView.reset()
        statusTv.text = "🧠 正在獲取股票數據..."

        val orchestrator = AgentPipelineOrchestrator(requireContext())

        // 注入量化信號提供者（可選）
        orchestrator.quantSignalsProvider = lambda@{ stockCode ->
            val eng = engine ?: return@lambda emptyList()
            try {
                val feed = StrategyDataFeed(requireContext())
                val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val stocks = feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = true))
                val allSignals = mutableListOf<StrategySignal>()
                for (strategy in eng.getStrategies()) {
                    if (!eng.isEnabled(strategy.id) || strategy.id == "ai_prediction") continue
                    try {
                        val result = strategy.screenWithData(stocks).getOrNull() ?: continue
                        allSignals.addAll(result.signals.filter { it.stockCode == stockCode })
                    } catch (_: Exception) { }
                }
                allSignals
            } catch (_: Exception) { emptyList() }
        }

        // 豆包風格動態進度文字（F→3→1→2→5→D→4）
        val stepProgressTexts = listOf(
            "🧠 正在啟動 Agent F（市場情緒）...",
            "🧠 正在啟動 Agent 3（資金面）...",
            "🧠 正在啟動 Agent 1（基本面拐點）...",
            "🧠 正在啟動 Agent 2（技術面）...",
            "🧠 正在啟動 Agent 5（風控終審）...",
            "🧠 正在啟動 Agent D（風險排雷）...",
            "🧠 正在啟動 Agent 4（技術交易執行）..."
        )

        // 設置回調
        orchestrator.onStepStart = { index, step ->
            lifecycleScope.launch(Dispatchers.Main) {
                pipelineProgressView.markStepStart(index, step)
                statusTv.text = stepProgressTexts.getOrElse(index) { "🧠 正在啟動 ${step.name}..." }
            }
        }

        orchestrator.onStepComplete = { index, step, ctx ->
            lifecycleScope.launch(Dispatchers.Main) {
                pipelineProgressView.markStepComplete(index, step, ctx)
            }
        }

        orchestrator.onError = { index, error ->
            lifecycleScope.launch(Dispatchers.Main) {
                pipelineProgressView.markStepError(index, error)
                statusTv.text = "❌ 步驟 $index 錯誤: ${error.take(40)}"
            }
        }

        // AI 動態選擇模式回調
        orchestrator.onModeSelected = { mode, reason ->
            lifecycleScope.launch(Dispatchers.Main) {
                statusTv.text = "🧠 $reason"
                pipelineProgressView.updateSteps(AgentPipelineOrchestrator.getStepsByName(mode.label))
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { statusTv.text = "🧠 正在綜合評估..." }
                val result = orchestrator.execute(target)

                withContext(Dispatchers.Main) {
                    pipelineProgressView.showResult(result)
                    statusTv.text = if (result.errorMessage != null) {
                        "❌ 分析失敗"
                    } else {
                        val passed = result.stocks.count { it.passed }
                        "✅ Agent 分析完成 [${result.analysisMode}] (${result.stepsCompleted}/${result.totalSteps} 步, ${passed} 只通過)"
                    }
                    aiPipelineBtn.isEnabled = true
                    aiPipelineBtn.text = "🧠 Agent分析"
                    hasAgentResult = true

                    // 發布到跨 Tab 總線
                    if (result.stocks.isNotEmpty()) {
                        CrossTabBus.postAiTopPicks(result.stocks.mapNotNull { stock ->
                            if (stock.chainScore != null) {
                                val cs = stock.chainScore
                                AIPredictionEngine.AIPick(
                                    stockCode = stock.stockCode,
                                    stockName = stock.stockName,
                                    compositeScore = cs.totalScore,
                                    upProbability = if (cs.totalScore >= 60) 75 else 50,
                                    rank = 1,
                                    reason = "AI智能体分析筛选: ${cs.barrierLevel}壁壘",
                                    actionSuggestion = if (stock.passed) "建議關注" else "風控不通過"
                                )
                            } else null
                        })
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ AI 智能体分析筛选異常: ${e.message?.take(40)}"
                    aiPipelineBtn.isEnabled = true
                    aiPipelineBtn.text = "🧠 Agent分析"
                }
            }
        }
    }

    // 清除功能已由基類 QuantFragmentBase 提供（clearData / clearDataByDate）
    // 短線量化使用基類的清除功能

    override fun showDialog(title: String, content: String) {
        val sv = ScrollView(requireContext()); sv.addView(TextView(requireContext()).apply { text=content; textSize=10f; setTextColor(Color.parseColor("#333333")); setPadding(16,12,16,12); setLineSpacing(2f,1.1f); setTypeface(Typeface.MONOSPACE) })
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(title).setView(sv).setPositiveButton("关闭",null).create().apply { show(); window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
    }
}