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
import com.chin.stockanalysis.strategy.data.SmartMoneyCache
import com.chin.stockanalysis.ui.CrossTabBus
import com.chin.stockanalysis.agent.pipeline.AgentPipelineOrchestrator
import com.chin.stockanalysis.agent.pipeline.ui.PipelineProgressView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
class ShortTermQuantFragment : QuantFragmentBase() {

    private lateinit var pipelineProgressView: PipelineProgressView
    private lateinit var aiPipelineBtn: Button

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

    companion object {
        private const val TAG = "ShortTermQuant"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    override fun getQuantType() = "ShortTermQuant"

    override val positionTitlePrefix = "短線量化"

    override fun onBuildClick() { runBuildAndBuy() }
    override fun onFittingClick() = autoFit()
    override fun onBacktrackClick() {
        Toast.makeText(requireContext(), "短線量化暫無回調功能", Toast.LENGTH_SHORT).show()
    }
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
        val mainBoardSwitch = Switch(requireContext()).apply { text = "仅主板"; textSize = 11f; isChecked = true; setTextColor(Color.parseColor("#333333")) }
        configRow.addView(mainBoardSwitch)

        // Agent 分析按钮 — 放在仅主板之后
        aiPipelineBtn = Button(requireContext()).apply {
            text = "🧠 Agent分析"; textSize = 10f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6A1B9A")); setPadding(8, 2, 8, 2)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(24)).apply { marginStart = 6 }
            setOnClickListener { runAIPipeline() }
        }
        configRow.addView(aiPipelineBtn)
        rootLayout.addView(configRow)

        // ── 统一按钮行（基类提供：建倉/持倉/回溯/擬合/賣出/數據） ──
        rootLayout.addView(createButtonRow())

        val progressRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 4, 16, 4) }
        progressBar = ProgressBar(requireContext()).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } }
        progressRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply { text = "就绪"; textSize = 12f; setTextColor(Color.parseColor("#AAAAAA")) }
        progressRow.addView(statusTv); rootLayout.addView(progressRow)

        // ── AI 智能體流水線進度面板 ──
        pipelineProgressView = PipelineProgressView(requireContext()).apply { visibility = View.GONE }
        rootLayout.addView(pipelineProgressView)

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
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 建仓中"; progressBar.visibility = View.VISIBLE; statusTv.text = "🔄 初始化短綫量化..."
        lifecycleScope.launch(Dispatchers.IO) {
            val totalStart = System.currentTimeMillis()
            try {
                // 🔥 非阻塞啟動新聞因子拉取（與後續步驟並行）
                val newsJob = com.chin.stockanalysis.news.HotSectorNewsUpdater.ensureFreshGlobal(
                    scope = this, context = requireContext(), forceRefresh = true
                )

                // 检查是否需要先导入数据
                val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val prefs = requireContext().getSharedPreferences("data_import", android.content.Context.MODE_PRIVATE)
                val lastImport = prefs.getString("last_import_date", "") ?: ""
                val db = StockDatabase.getInstance(requireContext())
                val todaySnaps = db.dailySnapshotDao().getByDate(today)
                val needImport = todaySnaps.size < 100 || lastImport != today
                val importStart = System.currentTimeMillis()
                if (needImport) {
                    Log.i(TAG, "[ShortTerm] Step 1: importing data, todaySnaps=${todaySnaps.size}, lastImport=$lastImport")
                    withContext(Dispatchers.Main) { statusTv.text = "📥 導入歷史數據中..." }
                    val f = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                    f.fetchAllHistoricalData(60) { p ->
                        lifecycleScope.launch(Dispatchers.Main) { statusTv.text = "📥 導入數據: ${p.completedStocks}/${p.totalStocks}" }
                    }
                    prefs.edit().putString("last_import_date", today).apply()
                    withContext(Dispatchers.Main) { statusTv.text = "✅ 數據導入完成" }
                } else {
                    Log.i(TAG, "[ShortTerm] Step 1: skip import, data already up to date")
                }
                val importElapsed = System.currentTimeMillis() - importStart
                Log.i(TAG, "[ShortTerm] Step 1 done: import=${importElapsed}ms")

                // 建仓日使用最近的交易日（避免非交易日）
                val tradeDay = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                lastTradeDate = tradeDay
                val feed = StrategyDataFeed(requireContext())
                // 使用備選池而非全部A股，大幅提升速度
                val poolCodes = try { com.chin.stockanalysis.strategy.data.CandidatePool.getPoolCodes(requireContext()) } catch (_: Exception) { emptyList() }
                withContext(Dispatchers.Main) { statusTv.text = "🔄 準備股票池數據..." }
                val feedStart = System.currentTimeMillis()
                val stocks = if (poolCodes.isNotEmpty()) {
                    feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = true, stockCodes = poolCodes.toSet()))
                } else {
                    feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = true))
                }
                val feedElapsed = System.currentTimeMillis() - feedStart
                Log.i(TAG, "[ShortTerm] Step 2 done: prepareFromDb=${feedElapsed}ms, stocks=${stocks.size}")
                todayStocks = stocks
                if (stocks.isEmpty()) { withContext(Dispatchers.Main) { statusTv.text="⚠️ 无交易日数据"; buildBtn.isEnabled=true; buildBtn.text="▶ 建仓"; progressBar.visibility=View.GONE }; return@launch }

                withContext(Dispatchers.Main) { statusTv.text = "🔄 Zipline 因子計算中..." }
                val ziplineStart = System.currentTimeMillis()
                val pipeline = ZiplinePipeline(requireContext()); val factors = pipeline.computeAll(stocks, today, 30); pipelineFactors = factors
                val ziplineElapsed = System.currentTimeMillis() - ziplineStart
                Log.i(TAG, "[ShortTerm] Step 3 done: Zipline computeAll=${ziplineElapsed}ms, valid=${factors.ma5.size}")

                val codeToName = try { StockDatabase.getInstance(requireContext()).stockBasicDao().getAll().associate { it.code to it.name } } catch (_: Exception) { emptyMap() }
                val strategyStart = System.currentTimeMillis()
                val screenings = mutableMapOf<Strategy, ScreeningResult>(); val screeningList = mutableListOf<ScreeningResult>()
                var enabledCount = 0
                withContext(Dispatchers.Main) { statusTv.text = "🔄 執行策略篩選中..." }
                for (strategy in eng.getStrategies()) {
                    if (!eng.isEnabled(strategy.id) || strategy.id == "ai_prediction") continue
                    enabledCount++
                    val sName = strategy.name
                    withContext(Dispatchers.Main) { statusTv.text = "🔄 執行策略: $sName ($enabledCount/${eng.getStrategies().size - 1})" }
                    val sStart = System.currentTimeMillis()
                    try {
                        val r = strategy.screenWithData(stocks)
                        r.getOrNull()?.let { screenings[strategy]=it; screeningList.add(it) }
                    } catch (e: Exception) {
            Log.w("ShortTermQuant", "Agent routing failed: ${e.message}")
        }
                    val sElapsed = System.currentTimeMillis() - sStart
                    if (sElapsed > 1000) {
                        Log.d(TAG, "[ShortTerm] Strategy ${strategy.id} took ${sElapsed}ms")
                    }
                }
                val strategyElapsed = System.currentTimeMillis() - strategyStart
                Log.i(TAG, "[ShortTerm] Step 4 done: ${enabledCount} strategies=${strategyElapsed}ms, hits=${screeningList.sumOf { it.hitCount }}")
                allScreenings = screenings

                // 合并池
                withContext(Dispatchers.Main) { statusTv.text = "🔄 合併策略結果..." }
                val mergeStart = System.currentTimeMillis()
                val pool = mutableMapOf<String, MutableList<Pair<String, Int>>>()
                for ((s, sc) in screenings) for (sig in sc.signals.distinctBy { it.stockCode }) pool.getOrPut(sig.stockCode){ mutableListOf() }.add(s.name to sig.strength)
                mergedPool = pool.mapValues { it.value.sortedByDescending { p->p.second } }
                mergedStockNames = pool.keys.associateWith { c -> screenings.values.firstNotNullOfOrNull { sc->sc.signals.find{it.stockCode==c}?.stockName } ?: codeToName[c] ?: c }
                val mergeElapsed = System.currentTimeMillis() - mergeStart
                Log.i(TAG, "[ShortTerm] Step 5 done: merge pool=${mergeElapsed}ms, size=${mergedPool.size}")

                // AI 精选
                val aiStart = System.currentTimeMillis()
                if (screeningList.isNotEmpty()) {
                    // AI 前置：等待新聞因子完成（已在 Pipeline 開頭 async 啟動）
                    if (newsJob.isActive) {
                        val newsWaitStart = System.currentTimeMillis()
                        val newsTimerJob = coroutineScope {
                            launch {
                                while (isActive) {
                                    delay(1000)
                                    val elapsed = "%.0f".format((System.currentTimeMillis() - newsWaitStart) / 1000.0)
                                    withContext(Dispatchers.Main) { statusTv.text = "📰 等待新聞因子完成... ${elapsed}s" }
                                }
                            }
                        }
                        try { newsJob.await() } catch (e: Exception) {
                            Log.w("ShortTermQuant", "新聞因子等待失敗（不阻塞）: ${e.message}")
                        }
                        newsTimerJob.cancel()
                        val newsElapsed = "%.1f".format((System.currentTimeMillis() - newsWaitStart) / 1000.0)
                        withContext(Dispatchers.Main) { statusTv.text = "✅ 新聞因子完成 (${newsElapsed}s)" }
                    }
                    com.chin.stockanalysis.stock.database.AppBackgroundRunner.isQuantRunning = true

                    withContext(Dispatchers.Main) { statusTv.text = "🤖 AI 大模型分析中..." }
                    try {
                        val p = AIPredictionEngine(requireContext()).predict(screeningList, today)
                        if (p!=null && p.topPicks.isNotEmpty()) aiPicks = p.topPicks else aiPicks = emptyList()
                    } catch (_: Exception) { aiPicks = emptyList() }

                    // AI 結束，恢復後臺
                    com.chin.stockanalysis.stock.database.AppBackgroundRunner.isQuantRunning = false
                }
                val aiElapsed = System.currentTimeMillis() - aiStart
                Log.i(TAG, "[ShortTerm] Step 6 done: AI predict=${aiElapsed}ms, picks=${aiPicks.size}")

                // 发布到跨Tab总线
                CrossTabBus.postMergedPool(mergedPool)
                CrossTabBus.postAiTopPicks(aiPicks)
                CrossTabBus.postStrategyResults(screeningList)

                val totalElapsed = System.currentTimeMillis() - totalStart
                Log.i(TAG, "[ShortTerm] ====== TOTAL: ${totalElapsed}ms ======")
                Log.i(TAG, "[ShortTerm] Breakdown: import=${importElapsed}ms  feed=${feedElapsed}ms  zipline=${ziplineElapsed}ms  strategy=${strategyElapsed}ms  merge=${mergeElapsed}ms  ai=${aiElapsed}ms")

                // 引擎內部已恢復後臺，這裡額外觸發一次持倉監控
                try { com.chin.stockanalysis.stock.database.AppBackgroundRunner.monitorWatchlistDirect(requireContext()) } catch (_: Exception) {}

                withContext(Dispatchers.Main) {
                    showPipelineTable(screenings, factors, today); hasPipelineResult = true
                    statusTv.text = "✅ 选股完成 (${factors.ma5.size} 只有效, ${totalElapsed}ms)"; buildBtn.isEnabled = true; buildBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE
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
            } catch (e: Exception) {
                Log.e(TAG, "[ShortTerm] Pipeline failed: ${e.message}", e)
                com.chin.stockanalysis.stock.database.AppBackgroundRunner.isQuantRunning = false
                withContext(Dispatchers.Main) { statusTv.text="❌ 选股失败: ${e.message?.take(40)}"; buildBtn.isEnabled=true; buildBtn.text="▶ 建仓"; progressBar.visibility=View.GONE }
            }
        }
    }

    private fun buyAiPicks() {
        if (aiPicks.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val existingCodes = db.strategyTradeOrderDao().getRecent(200).filter { it.orderType == "ShortTermQuant" && (it.status == "BUYING" || it.status == "PENDING") }.map { it.stockCode }.toSet()
                val toInsert = mutableListOf<StrategyTradeOrderEntity>()
                val watchlistStocks = mutableListOf<Triple<String, String, Int>>()
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
                    watchlistStocks.add(Triple(pick.stockCode, pick.stockName, pick.compositeScore))
                }
                if (toInsert.isNotEmpty()) {
                    db.strategyTradeOrderDao().insertAll(toInsert)
                    com.chin.stockanalysis.stock.database.AppBackgroundRunner.addBatchToWatchlist(
                        requireContext(), watchlistStocks, source = "shortterm"
                    )
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

        // 確保主力資金緩存已刷新
        if (!SmartMoneyCache.isFresh()) {
            try {
                SmartMoneyCache.refresh(requireContext(), aiPicks.map { it.stockCode })
                Log.i(TAG, "短綫量化: 主力資金緩存已刷新")
            } catch (e: Exception) {
                Log.w(TAG, "短綫量化: 主力資金緩存刷新失敗: ${e.message}")
            }
        }

        val db = StockDatabase.getInstance(requireContext())
        val existingCodes = db.strategyTradeOrderDao().getRecent(200)
            .filter { it.orderType == "ShortTermQuant" && (it.status == "BUYING" || it.status == "PENDING") }
            .map { it.stockCode }.toSet()
        val toInsert = mutableListOf<StrategyTradeOrderEntity>()
        val aiSelectedEntities = mutableListOf<com.chin.stockanalysis.stock.database.AiSelectedStockEntity>()
        val watchlistStocks = mutableListOf<Triple<String, String, Int>>()
        val today = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        for (pick in aiPicks) {
            if (pick.stockCode in existingCodes) continue

            // 主力資金評分過濾
            val smScore = SmartMoneyCache.getScore(pick.stockCode)
            if (smScore.combined < 55) {
                Log.i(TAG, "🚫 短綫買入評分攔截: ${pick.stockName}(${pick.stockCode}) 主力資金評分=${String.format("%.0f", smScore.combined)} < 55")
                continue
            }

            val snap = todayStocks.find { it.code == pick.stockCode }
            val buyPrice = snap?.price ?: 0.0
            if (buyPrice <= 0) continue
            val finalScore = (pick.compositeScore + smScore.combined.toInt()) / 2
            toInsert.add(StrategyTradeOrderEntity(
                strategyId = "AI_Selected", stockCode = pick.stockCode, stockName = pick.stockName,
                tradeDate = lastTradeDate, buyPrice = buyPrice,
                buyTime = java.time.LocalTime.now().toString().take(8),
                quantity = 100, orderType = "ShortTermQuant", status = "BUYING",
                reason = "AI精选: ${pick.reason.take(60)} | 主力資金=${String.format("%.0f", smScore.combined)}",
                scoreAtBuy = finalScore,
                createdAt = System.currentTimeMillis()
            ))
            // 同時保存到 AI 精選表
            aiSelectedEntities.add(com.chin.stockanalysis.stock.database.AiSelectedStockEntity(
                stockCode = pick.stockCode,
                stockName = pick.stockName,
                source = "shortterm",
                selectedDate = today,
                score = pick.compositeScore,
                reason = pick.reason.take(120),
                buyPrice = buyPrice
            ))
            // 加到自選股
            watchlistStocks.add(Triple(pick.stockCode, pick.stockName, pick.compositeScore))
        }
        if (toInsert.isNotEmpty()) {
            db.strategyTradeOrderDao().insertAll(toInsert)
            Log.i("AutoQuant", "✅ 自動建倉 ${toInsert.size} 只")
        }
        // 保存 AI 精選到獨立表
        if (aiSelectedEntities.isNotEmpty()) {
            com.chin.stockanalysis.stock.database.AppBackgroundRunner.saveAiSelectedStocks(
                requireContext(), aiSelectedEntities
            )
        }
        // 同步加入我的自選
        if (watchlistStocks.isNotEmpty()) {
            com.chin.stockanalysis.stock.database.AppBackgroundRunner.addBatchToWatchlist(
                requireContext(), watchlistStocks, source = "shortterm"
            )
            Log.i("AutoQuant", "⭐ 加入自選: ${watchlistStocks.size} 只")
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
        sv.addView(container);
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("短线选股结果 ($today)").setView(sv).setPositiveButton("关闭", null).show()
    }

    // ═══════════════════════════════════════
    // 数据查看
    // ═══════════════════════════════════════

    protected override fun showDataMenu() {
        val options = arrayOf(
            "🧹 清空持仓",
            "🧹 清空报告",
            "📋 查看交易记录",
            "📊 短线量化报告 (历史)"
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("数据中心")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmAndClearPositions()
                    1 -> confirmAndClearReports()
                    2 -> showShortTermTradeHistory()
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

    /** 自动拟合 — 遍历最近交易日，验证预测准确率并更新策略权重 */
    private fun autoFit() {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 拟合中"; progressBar.visibility = View.VISIBLE; statusTv.text = "🔧 自动拟合中..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val te = SimulationTradeEngine(requireContext())
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val recentDates = StockDatabase.getInstance(requireContext()).dailySnapshotDao().getAvailableDates(30).sorted()
                if (recentDates.size < 2) {
                    withContext(Dispatchers.Main) { statusTv.text = "⚠️ 数据不足"; buildBtn.isEnabled = true; buildBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE }
                    return@launch
                }
                val results = te.autoFit(strategies, recentDates)
                withContext(Dispatchers.Main) {
                    statusTv.text = "✅ 拟合完成: ${results.size} 策略优化"; buildBtn.isEnabled = true; buildBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "拟合完成", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTv.text = "❌ 拟合失败: ${e.message?.take(30)}"; buildBtn.isEnabled = true; buildBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE }
            }
        }
    }

    /** 供外部调用的自动触发 Pipeline */
    fun autoRunPipeline() {
        if (buildBtn.isEnabled) runPipeline()
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
    // showDialog 已由基類 QuantFragmentBase 提供，無需重寫
}
