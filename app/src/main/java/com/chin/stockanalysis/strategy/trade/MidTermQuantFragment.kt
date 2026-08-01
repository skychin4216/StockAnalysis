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
import com.chin.stockanalysis.strategy.HoldingPeriod
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
        private const val TAG = "MidTermQuant"
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
        screener = StockScreener(repo, ctx)
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

        // 持倉信息提示
        val tipTv = TextView(requireContext()).apply {
            text = "📈 持倉1-6月 | 最多5只 | 基本面+技術面"
            textSize = 10f; setTextColor(Color.parseColor("#1565C0")); setPadding(8, 0, 0, 0)
        }
        row1.addView(tipTv)
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

    /**
     * ══════════ 臨時方法：通過 DAG Pipeline 執行中線量化 ══════════
     *
     * 使用高通風格 DagPipeline 替代 SimulationTradeEngine.runTradeSession()。
     * 後期 DAG Pipeline 功能完整後，將此方法邏輯合併回 executeTrade() 並刪除。
     */
    private suspend fun executeTradeViaDagPipeline(tradeDate: String, today: String, totalStart: Long) {
        val appCtx = requireContext().applicationContext
        withContext(Dispatchers.Main) {
            statusTv.text = "🔄 [DAG] 初始化 Pipeline..."
        }

        try {
            // 確保 UseCaseLoader 已初始化
            val eng = engine ?: return
            val strategies = eng.getEnabledStrategiesByPeriod(HoldingPeriod.MID)
            if (strategies.isEmpty()) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "没有启用的策略"; buildBtn.isEnabled = true
                    buildBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE
                }
                return
            }

            com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader.init(
                appCtx, strategies
            )

            // 數據導入檢查（與原始流程一致）
            val db = StockDatabase.getInstance(appCtx)
            val todaySnaps = db.dailySnapshotDao().getByDate(today)
            if (todaySnaps.size < 100) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "🔄 [DAG] 數據不足(${todaySnaps.size}<100)，先導入數據..."
                }
                com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(appCtx)
                    .fetchAllHistoricalData(days = 60)
            }

            // 構建市場上下文（與原始流程一致）
            val mktCtx = com.chin.stockanalysis.strategy.sector.StrategyMarketContext
                .build(appCtx, today)

            withContext(Dispatchers.Main) {
                statusTv.text = "🔄 [DAG] 執行中線 Pipeline..."
            }

            // 執行 DAG Pipeline
            val result = com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
                .run("mid_term", tradeDate) { pipelineName, nodeName ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        statusTv.text = "🔄 [DAG] ${pipelineName} ${nodeName} 執行中..."
                    }
                }

            val elapsed = System.currentTimeMillis() - totalStart

            // ══════════ 後處理：從 nodeResults 提取訂單/持倉/換股信息 ══════════
            var ordersCount = 0
            var swapSummary = ""
            var mergeSummary = ""
            var savedWatchlist = false

            try {
                // UseCaseLoader.run() 返回 MultiPipelineResult，
                // 其中 pipelineResults 的值類型為 PipelineResult（DagPipelineResult 已在內部轉換）。
                // DAG 執行後，PipelineResult.stageResults 的 key 是 nodeId，value 是 LinkListResult。
                for ((_, pipelineResult) in result.pipelineResults) {
                    val nodeResults = pipelineResult.stageResults

                    // 提取訂單生成結果
                    val ordersLinkResult = nodeResults["n_orders"]
                    val ordersOutput = ordersLinkResult?.output
                    if (ordersOutput is com.chin.stockanalysis.strategy.topology.nodes.OrderGenerationResult) {
                        ordersCount = ordersOutput.orders.size
                        Log.i(TAG, "[DAG] 訂單生成: $ordersCount 筆")
                    }

                    // 提取持倉合併結果
                    val mergeOutput = nodeResults["n_merge_pos"]?.output
                    if (mergeOutput is com.chin.stockanalysis.strategy.topology.nodes.PositionMergeResult) {
                        mergeSummary = buildString {
                            appendLine("持倉合併: 新增${mergeOutput.newCount}筆, 總持倉${mergeOutput.totalHoldings}筆")
                            if (mergeOutput.updatedCodes.isNotEmpty()) {
                                appendLine("  追加: ${mergeOutput.updatedCodes.take(5).joinToString(", ")}")
                            }
                        }
                        Log.i(TAG, "[DAG] $mergeSummary")
                    }

                    // 提取騰龍換鳥結果
                    val swapOutput = nodeResults["n_swap"]?.output
                    if (swapOutput is com.chin.stockanalysis.strategy.topology.nodes.SwapWeakResult) {
                        swapSummary = buildString {
                            appendLine("騰龍換鳥: 換${swapOutput.swappedCount}筆")
                            appendLine("  換股前: ${swapOutput.beforeCount}筆 → 換股後: ${swapOutput.afterCount}筆")
                            if (swapOutput.soldStocks.isNotEmpty()) {
                                appendLine("  賣出: ${swapOutput.soldStocks.joinToString(", ")}")
                            }
                        }
                        Log.i(TAG, "[DAG] $swapSummary")
                    }

                    // 提取訂單並保存到自選股
                    if (ordersOutput is com.chin.stockanalysis.strategy.topology.nodes.OrderGenerationResult
                        && ordersOutput.orders.isNotEmpty()) {
                        try {
                            val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                            val watchlistItems = ordersOutput.orders.map { order ->
                                Triple(order.stockCode, order.stockName, order.scoreAtBuy)
                            }
                            com.chin.stockanalysis.stock.database.AppBackgroundRunner.addBatchToWatchlist(
                                requireContext(), watchlistItems, source = "midterm"
                            )
                            savedWatchlist = true
                            Log.i(TAG, "[DAG] 已保存 ${watchlistItems.size} 只到自選股")
                        } catch (e: Exception) {
                            Log.w(TAG, "[DAG] 保存自選股失敗: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[DAG] 後處理異常: ${e.message}")
            }

            // ══════════ 輸出結果摘要 ══════════
            val summary = buildString {
                appendLine("═══ DAG Pipeline 執行報告 ═══")
                appendLine("成功: ${result.success}")
                appendLine("總耗時: ${elapsed}ms (Pipeline: ${result.totalElapsedMs}ms)")
                appendLine("Pipeline 數: ${result.pipelineResults.size}")
                for ((name, pr) in result.pipelineResults) {
                    appendLine("  $name: ${if (pr.success) "✓" else "✗"} (${pr.totalElapsedMs}ms)")
                }
                if (ordersCount > 0) appendLine("生成訂單: ${ordersCount}筆")
                if (swapSummary.isNotBlank()) appendLine(swapSummary.trimEnd())
                if (mergeSummary.isNotBlank()) appendLine(mergeSummary.trimEnd())
                if (savedWatchlist) appendLine("已保存到自選股")
                if (result.errors.isNotEmpty()) {
                    appendLine("錯誤:")
                    for ((key, msg) in result.errors) {
                        appendLine("  [$key] $msg")
                    }
                }
                appendLine("═══════════════════════════")
            }

            Log.i(TAG, summary)

            // 收集各 Pipeline 節點股票流動摘要
            val stockFlowLines = mutableListOf<String>()
            var totalFlowNodes = 0
            for ((pipeName, pr) in result.pipelineResults) {
                if (pr.stockFlowLogs.isNotEmpty()) {
                    stockFlowLines.add("📊 $pipeName:")
                    totalFlowNodes += pr.stockFlowLogs.size
                    for ((nodeId, flow) in pr.stockFlowLogs) {
                        val line = buildString {
                            append("  ${flow.nodeName}: ${flow.inputCount}→${flow.outputCount}")
                            if (flow.filterCount > 0) append(" (過濾${flow.filterCount}: ${flow.filterReason})")
                        }
                        stockFlowLines.add(line)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                // 報告彈窗（對齊超短線/短線/長線，中線此前缺失）
                val reportText = buildString {
                    appendLine("═══ 中線 DAG Pipeline 報告 ═══")
                    appendLine("成功: ${result.success} | 耗時: ${elapsed}ms")
                    appendLine("Pipeline: ${result.pipelineResults.keys.joinToString(", ")}")
                    if (ordersCount > 0) appendLine("生成訂單: ${ordersCount}筆")
                    if (mergeSummary.isNotBlank()) appendLine(mergeSummary.trimEnd())
                    if (swapSummary.isNotBlank()) appendLine(swapSummary.trimEnd())
                    if (savedWatchlist) appendLine("已保存到自選股")
                    if (stockFlowLines.isNotEmpty()) {
                        appendLine("── 節點股票流動 ──")
                        for (line in stockFlowLines) appendLine(line)
                    }
                    if (result.errors.isNotEmpty()) {
                        appendLine("── 錯誤 ──")
                        for ((key, msg) in result.errors) appendLine("  [$key] $msg")
                    }
                    appendLine("═══════════════════════════")
                }
                showDialog("中線 DAG Pipeline 報告", reportText)

                val detailLines = mutableListOf<String>()
                if (ordersCount > 0) detailLines.add("訂單${ordersCount}筆")
                if (swapSummary.isNotBlank()) detailLines.add("換${swapSummary.lines().first().filter { it.isDigit() }}筆")
                if (savedWatchlist) detailLines.add("已保存自選")

                // 構建 UI 顯示文本（pipeline 名稱 + 節點流動 + 摘要）
                val uiText = buildString {
                    if (result.success) {
                        appendLine("✅ [DAG] ${result.pipelineResults.keys.firstOrNull() ?: "Pipeline"} 完成 (${elapsed}ms)")
                    } else {
                        appendLine("❌ [DAG] 失敗: ${result.errors.keys.joinToString(", ")}")
                    }
                    // 節點股票流動摘要（最多顯示 5 行，避免過長）
                    if (stockFlowLines.isNotEmpty()) {
                        for (line in stockFlowLines.take(6)) {
                            appendLine(line)
                        }
                        if (stockFlowLines.size > 6) {
                            appendLine("  ... 共 $totalFlowNodes 個節點")
                        }
                    }
                    // 業務摘要
                    if (detailLines.isNotEmpty()) {
                        append(detailLines.joinToString(" | "))
                    }
                }

                statusTv.text = uiText.trimEnd()
                buildBtn.isEnabled = true
                buildBtn.text = "▶ 建仓"
                progressBar.visibility = View.GONE
            }

            // 保存量化報告（含 pipeline 名稱 + 股票流動 JSON）
            try {
                savePipelineReportToDb(result, tradeDate, stockFlowLines)
            } catch (e: Exception) {
                Log.w(TAG, "[DAG] 保存報告失敗: ${e.message}")
            }

            // 後處理：刷新持倉（與原始流程一致的後續步驟）
            try { com.chin.stockanalysis.stock.database.AppBackgroundRunner.monitorWatchlistDirect(appCtx) } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                refreshPositions()
            }

        } catch (e: Exception) {
            Log.e(TAG, "[DAG] 執行異常", e)
            withContext(Dispatchers.Main) {
                statusTv.text = "❌ [DAG] 異常: ${e.message}"
                buildBtn.isEnabled = true
                buildBtn.text = "▶ 建仓"
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun executeTrade() {
        engine ?: return
        if (selectedPeriods.isEmpty()) {
            Toast.makeText(requireContext(), "请至少选择一个周期", Toast.LENGTH_SHORT).show()
            return
        }

        buildBtn.isEnabled = false; buildBtn.text = "⏳ 执行中..."
        progressBar.visibility = View.VISIBLE; statusTv.text = "🔄 初始化中綫量化..."

        lifecycleScope.launch(Dispatchers.IO) {
            val totalStart = System.currentTimeMillis()
            try {
                val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val tradeDate = browsingDate.format(DATE_FMT)
                executeTradeViaDagPipeline(tradeDate, today, totalStart)
            } catch (e: Exception) {
                Log.e(TAG, "[MidTerm] executeTrade failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 执行失败: ${e.message?.take(50)}"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建仓"
                    progressBar.visibility = View.GONE
                }
            }
        }
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

    private suspend fun getNextTradingDayFromDB(date: String): String? =
        com.chin.stockanalysis.ui.TradingDayPickerView.getNextTradingDayFromDb(date) { requireContext() }

    // ═══════════════════════════════════════
    // 拟合
    // ═══════════════════════════════════════

    private fun showFittingParams() {
        val eng = engine ?: return
        val strategies = eng.getStrategies()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())

                // 先執行一次快速擬合（用 autoFit 而非 gridSearch）
                val te = StrategyFittingEngine(requireContext())
                val enabledStrategies = strategies.filter { eng.isEnabled(it.id) }
                val recentDates = db.dailySnapshotDao().getAvailableDates(30).sorted()
                if (enabledStrategies.isNotEmpty() && recentDates.size >= 2) {
                    try {
                        te.autoFit(enabledStrategies, recentDates)
                        Log.i(TAG, "擬合完成，更新 strategy_trade_fitting_params 表")
                    } catch (e: Exception) {
                        Log.w(TAG, "快速擬合失敗（仍顯示已有數據）: ${e.message}")
                    }
                }

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

    override fun showDataMenu(anchor: View) {
        val exporter = DataExportImport(requireContext())
        val options = arrayOf(
            "🧹 清空持仓", "🧹 清空报告",
            "📋 查看交易记录", "📊 中线量化报告 (历史)",
            "📊 查看精選池",
            "📤 导出交易数据 (CSV文本)", "📤 导出 JSON (全部数据)", "📤 导出 CSV (分表)",
            "📂 查看导出文件列表", "📥 导入 JSON 数据", "📊 数据库统计信息",
            "📈 回溯測試", "🔧 擬合調優"
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
                    11 -> onBacktrackClick()
                    12 -> onFittingClick()
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

    /**
     * 保存 DAG Pipeline 執行報告到數據庫（含節點股票流動記錄）。
     */
    private suspend fun savePipelineReportToDb(
        result: com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader.MultiPipelineResult,
        tradeDate: String,
        stockFlowLines: List<String>
    ) {
        val db = StockDatabase.getInstance(requireContext())

        // 收集最終輸出的股票代碼 + 逐策略 Top3 + 新聞力度/輪動懲罰
        val finalCodes = mutableListOf<String>()
        var newsStrengthScore = 0
        var rotationPenalty = 0
        val perStrategyTop3 = org.json.JSONArray()

        for ((_, pr) in result.pipelineResults) {
            // 最終訂單股票代碼
            val orders = pr.stageResults["n_orders"]?.output
            if (orders is com.chin.stockanalysis.strategy.topology.nodes.OrderGenerationResult) {
                finalCodes.addAll(orders.orders.map { it.stockCode })
            }

            // 新聞力度（Int 輸出）
            (pr.stageResults["n_news_str"]?.output as? Int)?.let {
                newsStrengthScore = it
            }

            // 板塊輪動懲罰（Int 輸出）
            (pr.stageResults["n_rot_pen"]?.output as? Int)?.let {
                rotationPenalty = it
            }

            // 逐策略 Top3：從信號合併節點提取 MergedSignalPool，按 strategyId 分組取 Top3
            val mergedPool = pr.stageResults["n_merge"]?.output
            if (mergedPool is com.chin.stockanalysis.strategy.topology.core.MergedSignalPool) {
                val strategyNames = mutableMapOf<String, String>()
                for ((_, linkResult) in pr.stageResults) {
                    val sp = linkResult.output
                    if (sp is com.chin.stockanalysis.strategy.topology.core.SignalPack) {
                        strategyNames[sp.strategyId] = sp.strategyName
                    }
                }

                val byStrategy = mergedPool.boostedSignals.groupBy { it.strategyId }
                for ((sid, signals) in byStrategy) {
                    val top3 = signals.sortedByDescending { it.strength }.take(3)
                    val picksArr = org.json.JSONArray()
                    for ((rank, sig) in top3.withIndex()) {
                        picksArr.put(org.json.JSONObject().apply {
                            put("rank", rank + 1)
                            put("code", sig.stockCode)
                            put("name", sig.stockName)
                            put("strength", sig.strength)
                            put("reason", sig.reason.take(100))
                        })
                    }
                    perStrategyTop3.put(org.json.JSONObject().apply {
                        put("strategyId", sid)
                        put("strategyName", strategyNames[sid] ?: sid)
                        put("picks", picksArr)
                    })
                }
            }
        }

        // 構建 pipeline flow JSON
        val flowJson = org.json.JSONObject().apply {
            put("useCaseId", result.useCaseId)
            put("success", result.success)
            put("totalElapsedMs", result.totalElapsedMs)
            val pipes = org.json.JSONObject()
            for ((pipeName, pr) in result.pipelineResults) {
                val pipeObj = org.json.JSONObject()
                pipeObj.put("pipelineName", pr.pipelineName)
                pipeObj.put("success", pr.success)
                val flows = org.json.JSONArray()
                for ((nodeId, flow) in pr.stockFlowLogs) {
                    flows.put(org.json.JSONObject().apply {
                        put("nodeId", nodeId)
                        put("nodeName", flow.nodeName)
                        put("inputCount", flow.inputCount)
                        put("outputCount", flow.outputCount)
                        put("filterCount", flow.filterCount)
                        put("filterReason", flow.filterReason)
                    })
                }
                pipeObj.put("stockFlows", flows)
                pipes.put(pipeName, pipeObj)
            }
            put("pipelines", pipes)
        }

        val entity = com.chin.stockanalysis.strategy.trade.DailyPeriodResultEntity(
            strategyId = "DAG_MIDTERM",
            strategyName = result.pipelineResults.keys.firstOrNull() ?: "中線DAG",
            tradeDate = tradeDate,
            periodDays = 5,
            stockCodesJson = org.json.JSONArray(finalCodes).toString(),
            stockCount = finalCodes.size,
            newsStrengthScore = newsStrengthScore,
            rotationPenalty = rotationPenalty,
            mainBoardFilter = true,
            filteredCodesJson = "[]",
            filteredReasonJson = stockFlowLines.joinToString("\n"),
            finalTop3Json = perStrategyTop3.toString(),
            aiSelectionReason = "DAG Pipeline 執行",
            pipelineFlowJson = flowJson.toString(),
            createdAt = System.currentTimeMillis()
        )
        db.dailyPeriodResultDao().insert(entity)
        Log.i(TAG, "[DAG] 報告已保存: ${entity.strategyName} ${tradeDate}, 最終股票 ${finalCodes.size} 只, 逐策略Top3 ${perStrategyTop3.length()} 組, 節點流動 ${result.pipelineResults.values.sumOf { it.stockFlowLogs.size }} 個")
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

}
