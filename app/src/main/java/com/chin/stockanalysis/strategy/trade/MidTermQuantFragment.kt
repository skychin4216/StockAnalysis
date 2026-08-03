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
 * 子类只需实现自己的建仓逻辑（executeTrade）和特有的UI配置。
 */
class MidTermQuantFragment : QuantFragmentBase() {

    private lateinit var mainBoardSwitch: Switch

    private var screener: StockScreener? = null
    private var selectedPeriods: Set<Int> = setOf(1)
    private var hasTradeReport: Boolean = false

    companion object {
        private const val TAG = "MidTermQuant"
        private val PERIOD_LABELS = mapOf(1 to "当日", 3 to "近3日", 10 to "近10日",
            30 to "近30日", 50 to "近50日", 100 to "近100日")
    }

    // ── 抽象方法实现 ──

    override fun getQuantType() = "MidTermQuant"
    override fun onBuildClick() { executeTrade() }
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
        addTitleRow("🤖 中线量化系统 v3.0 (含智能卖出)", textSize = 18f)
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
                    buildBtn.text = "📈建倉"; progressBar.visibility = View.GONE
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
                buildBtn.text = "📈建倉"
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
                buildBtn.text = "📈建倉"
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

        buildBtn.isEnabled = false; buildBtn.text = "⏳ 執行中..."
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
                    buildBtn.isEnabled = true; buildBtn.text = "📈建倉"
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

    /**
     * 保存 DAG Pipeline 執行報告到數據庫（含節點股票流動記錄）。
     * 數據菜單、導出/導入、清空持倉/報告等功能已由基類 QuantFragmentBase 統一提供。
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

    // showFinalPool / confirmAndClearPositions / confirmAndClearReports
    // 已由基類 QuantFragmentBase 統一提供，所有週期共用相同實現

}
