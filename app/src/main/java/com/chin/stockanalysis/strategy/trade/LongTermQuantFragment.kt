package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.data.StrategyDataFeed
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 長線量化 Tab — 持倉 1 年以上，價值投資
 *
 * 核心特點：
 * - 策略池：LONG 週期（低估值、基本面篩選、機構增持、行業龍頭護城河）
 * - 持倉週期：1 年以上
 * - 最大持倉：5 只
 * - 賣出規則：基本面惡化 / 估值過高
 * - 數據頻率：週K + 季報
 * - 分析重心：深度基本面
 *
 * 流程：
 *   1. 準備股票池數據（復用 StrategyDataFeed）
 *   2. 執行 LONG 週期策略篩選
 *   3. 合併結果，按強度排序取 Top 5
 *   4. 自動建倉（orderType="LongTermQuant"）
 *   5. 定期檢查基本面是否惡化
 */
class LongTermQuantFragment : QuantFragmentBase() {

    companion object {
        private const val TAG = "LongTermQuant"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 最大持倉數 */
        private const val MAX_HOLDINGS = 5

        /** 估值過高賣出閾值（PE > 80 或 PB > 10） */
        private const val OVERVALUED_PE = 80.0
        private const val OVERVALUED_PB = 10.0
    }

    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView

    private var todayStocks: List<com.chin.stockanalysis.stock.StockRealtime> = emptyList()
    private var lastTradeDate: String = ""

    override fun getQuantType() = "LongTermQuant"

    override val positionTitlePrefix = "長線"

    override fun onBuildClick() { runBuildAndBuy() }
    override fun onFittingClick() { showFittingHint() }
    override fun onBacktrackClick() { runLongTermBacktrack() }
    override fun onClearClick() { clearData() }

    override fun getDefaultUseCaseId() = "long_term"

    // ── 生命週期 ──

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
        val ctx = requireContext().applicationContext
        StrategyEngineHolder.init(ctx)
        engine = StrategyEngineHolder.get()
        tradeEngine = SimulationTradeEngine(ctx)
    }

    override fun buildUI() {
        rootLayout.addView(TextView(requireContext()).apply {
            text = "💎 長線量化系統 (價值投資，持倉1年+)"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setPadding(16, 12, 16, 6)
        })

        // 配置行
        val configRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(Color.WHITE)
        }
        dateLabelTv = TextView(requireContext()).apply {
            text = "📅 交易日:"
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
        }
        configRow.addView(dateLabelTv)
        datePicker = TradingDayPickerView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 4 }
            onDateChanged = { d ->
                browsingDate = d
                val isNonTrading = d.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                    d.dayOfWeek == java.time.DayOfWeek.SUNDAY ||
                    d in TradingDayPickerView.CHINESE_HOLIDAYS
                dateLabelTv.text = if (isNonTrading) "📅 非交易日:" else "📅 交易日:"
            }
        }
        configRow.addView(datePicker)

        // 提示標籤
        val tipTv = TextView(requireContext()).apply {
            text = "💎 持倉1年+ | 最多${MAX_HOLDINGS}只 | 深度基本面"
            textSize = 10f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(8, 0, 0, 0)
        }
        configRow.addView(tipTv)
        rootLayout.addView(configRow)

        // 進度行
        rootLayout.addView(createProgressRow())

        // 統一按鈕行
        rootLayout.addView(createButtonRow())

        rootLayout.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 8 }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        // 持倉顯示區
        val contentScroll = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        positionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }
        contentScroll.addView(positionContainer)
        rootLayout.addView(contentScroll)

        refreshPositions()
    }

    // ═══════════════════════════════════════
    // 建倉 — 長線核心邏輯
    // ═══════════════════════════════════════

    private fun runBuildAndBuy() {
        val eng = engine ?: return
        buildBtn.isEnabled = false
        buildBtn.text = "⏳ 執行中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "💎 長線選股中..."

        lifecycleScope.launch(Dispatchers.IO) {
            val totalStart = System.currentTimeMillis()
            try {
                val today = TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val tradeDate = browsingDate.format(DATE_FMT)
                lastTradeDate = tradeDate

                // ══════════ DAG Pipeline 分支（通用開關） ══════════
                if (com.chin.stockanalysis.config.FeatureFlagManager.useDagPipeline) {
                    executeViaDagPipeline(tradeDate, today)
                    return@launch
                }

                // Step 1: 確保數據導入
                val db = StockDatabase.getInstance(requireContext())
                val todaySnaps = db.dailySnapshotDao().getByDate(today)
                if (todaySnaps.size < 100) {
                    withContext(Dispatchers.Main) { statusTv.text = "📥 導入歷史數據中..." }
                    try {
                        com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                            .fetchAllHistoricalData(60)
                    } catch (e: Exception) {
                        Log.w(TAG, "數據導入失敗（不阻塞）: ${e.message}")
                    }
                }

                // Step 2: 準備股票池
                withContext(Dispatchers.Main) { statusTv.text = "🔄 準備股票池數據..." }
                val feed = StrategyDataFeed(requireContext())
                val poolCodes = try {
                    com.chin.stockanalysis.strategy.data.CandidatePool.getPoolCodes(requireContext())
                } catch (_: Exception) { emptyList() }

                val stocks = if (poolCodes.isNotEmpty()) {
                    feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(
                        onlyMainBoard = true, stockCodes = poolCodes.toSet()
                    ))
                } else {
                    feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = true))
                }
                todayStocks = stocks
                if (stocks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚠️ 無交易日數據"
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }
                Log.i(TAG, "[LongTerm] 股票池: ${stocks.size} 只")

                // Step 3: 執行 LONG 週期策略
                withContext(Dispatchers.Main) { statusTv.text = "💎 執行長線策略篩選..." }
                val strategies = eng.getEnabledStrategiesByPeriod(HoldingPeriod.LONG)
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚠️ 沒有啟用的長線策略（低估值/基本面/機構增持/護城河）"
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                val screenings = mutableMapOf<Strategy, com.chin.stockanalysis.strategy.models.ScreeningResult>()
                for ((index, strategy) in strategies.withIndex()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "💎 執行策略: ${strategy.name} (${index + 1}/${strategies.size})"
                    }
                    try {
                        val r = strategy.screenWithData(stocks)
                        r.getOrNull()?.let { screenings[strategy] = it }
                    } catch (e: Exception) {
                        Log.w(TAG, "策略 ${strategy.id} 執行失敗: ${e.message}")
                    }
                }
                Log.i(TAG, "[LongTerm] 策略執行完成: ${screenings.size}/${strategies.size} 成功")

                // Step 4: 合併結果，按強度排序
                val mergedPool = mutableMapOf<String, MutableList<Pair<String, Int>>>()
                val codeToName = mutableMapOf<String, String>()
                for ((s, sc) in screenings) {
                    for (sig in sc.signals.distinctBy { it.stockCode }) {
                        mergedPool.getOrPut(sig.stockCode) { mutableListOf() }
                            .add(s.name to sig.strength)
                        codeToName[sig.stockCode] = sig.stockName
                    }
                }

                if (mergedPool.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚠️ 長線策略無命中信號"
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                // 按最大強度排序，取 Top N
                val sortedStocks = mergedPool.entries
                    .sortedByDescending { it.value.maxOf { p -> p.second } }
                    .take(MAX_HOLDINGS)

                // Step 5: 檢查持倉限制並建倉
                val existingOrders = db.strategyTradeOrderDao().getRecent(200)
                    .filter { it.orderType == "LongTermQuant" &&
                        (it.status == "BUYING" || it.status == "PENDING") }
                val existingCodes = existingOrders.map { it.stockCode }.toSet()

                val availableSlots = MAX_HOLDINGS - existingCodes.size
                if (availableSlots <= 0) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚠️ 已達最大持倉限制 ($MAX_HOLDINGS 只)"
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                        progressBar.visibility = View.GONE
                        refreshPositions()
                    }
                    return@launch
                }

                val toInsert = mutableListOf<StrategyTradeOrderEntity>()
                val watchlistStocks = mutableListOf<Triple<String, String, Int>>()
                for ((code, hits) in sortedStocks.take(availableSlots)) {
                    if (code in existingCodes) continue
                    val snap = todayStocks.find { it.code == code }
                    val buyPrice = snap?.price ?: 0.0
                    if (buyPrice <= 0) {
                        Log.w(TAG, "長線選股 $code 無即時價格，跳過")
                        continue
                    }
                    val name = codeToName[code] ?: code
                    val maxStrength = hits.maxOf { it.second }
                    toInsert.add(StrategyTradeOrderEntity(
                        strategyId = hits.joinToString(",") { it.first },
                        stockCode = code, stockName = name,
                        tradeDate = tradeDate, buyPrice = buyPrice,
                        buyTime = java.time.LocalTime.now().toString().take(8),
                        quantity = 100, orderType = "LongTermQuant", status = "BUYING",
                        reason = "長線策略命中: ${hits.joinToString(",") { "${it.first}(${it.second}%)" }}",
                        scoreAtBuy = maxStrength,
                        createdAt = System.currentTimeMillis()
                    ))
                    watchlistStocks.add(Triple(code, name, maxStrength))
                }

                if (toInsert.isNotEmpty()) {
                    db.strategyTradeOrderDao().insertAll(toInsert)
                    try {
                        com.chin.stockanalysis.stock.database.AppBackgroundRunner.addBatchToWatchlist(
                            requireContext(), watchlistStocks, source = "long_term"
                        )
                    } catch (_: Exception) {}
                }

                val elapsed = System.currentTimeMillis() - totalStart
                val summary = buildString {
                    appendLine("💎 長線選股完成 (${elapsed}ms)")
                    appendLine("策略: ${strategies.joinToString(",") { it.name }}")
                    appendLine("命中: ${mergedPool.size} 只 → 建倉: ${toInsert.size} 只")
                    if (toInsert.isNotEmpty()) {
                        appendLine("建倉股票:")
                        for (order in toInsert) {
                            appendLine("  • ${order.stockName}(${order.stockCode.takeLast(6)}) ¥${"%.2f".format(order.buyPrice)} 強度:${order.scoreAtBuy}%")
                        }
                    }
                    appendLine("持倉週期: 1年+ | 賣出條件: 基本面惡化 / 估值過高")
                }

                withContext(Dispatchers.Main) {
                    showDialog("長線選股報告", summary)
                    statusTv.text = "✅ 長線: 建倉 ${toInsert.size} 只 (${elapsed}ms)"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                    refreshPositions()
                }

            } catch (e: Exception) {
                Log.e(TAG, "[LongTerm] 建倉失敗", e)
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 長線建倉失敗: ${e.message?.take(40)}"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    /**
     * ══════════ DAG Pipeline 執行（通用開關開啟時走此路徑） ══════════
     *
     * 使用 long_term_pipeline.xml 動態鏈接，節點關係由 XML 定義，可重新編排。
     * 含 news_guard 防黑天鵝，不含騰龍換鳥（長線重倉持有）。
     */
    private suspend fun executeViaDagPipeline(tradeDate: String, today: String) {
        val eng = engine ?: return
        withContext(Dispatchers.Main) { statusTv.text = "🔄 [DAG] 長線 Pipeline 執行中..." }
        try {
            val strategies = eng.getEnabledStrategiesByPeriod(HoldingPeriod.LONG)
            val r = com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor.execute(
                context = requireContext(),
                useCaseId = "long_term",
                tradeDate = tradeDate,
                today = today,
                strategies = strategies,
                orderType = "long_term_dag",
                importDays = 60
            )
            withContext(Dispatchers.Main) {
                showDialog(
                    "長線 DAG Pipeline 報告",
                    com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
                        .buildReportText("長線 DAG Pipeline", r)
                )
                statusTv.text = r.uiText
                buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                progressBar.visibility = View.GONE
                refreshPositions()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DAG] 長線執行異常", e)
            withContext(Dispatchers.Main) {
                statusTv.text = "❌ [DAG] ${e.message?.take(40)}"
                buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                progressBar.visibility = View.GONE
            }
        }
    }

    // ═══════════════════════════════════════
    // 回溯測試
    // ═══════════════════════════════════════

    private fun runLongTermBacktrack() {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 回溯中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "💎 長線回溯測試中..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = eng.getEnabledStrategiesByPeriod(HoldingPeriod.LONG)
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚠️ 無長線策略可回測"
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                val ctx = requireContext()
                val backtestEngine = com.chin.stockanalysis.strategy.backtest.HistoricalBacktestEngine(ctx)
                val report = backtestEngine.runHistoricalBacktest(strategies, tradingDays = 60)

                val sb = StringBuilder()
                sb.appendLine("💎 長線回溯測試報告 (60交易日)")
                sb.appendLine("持倉: 1年+ | 賣出: 基本面惡化 / 估值過高")
                sb.appendLine("期間: ${report.dateRange}")
                sb.appendLine()
                for (r in report.strategyReports) {
                    sb.appendLine("💎 ${r.strategyName}")
                    sb.appendLine("  交易日: ${r.totalDays} 天 | 買入信號: ${r.totalBuys} 次")
                    sb.appendLine("  買入準確率: ${"%.1f".format(r.buyAccuracy * 100)}% (${r.correctBuys}/${r.totalBuys})")
                    sb.appendLine("  平均淨收益: ${"%.2f".format(r.avgReturn)}%（已扣交易成本0.3%）")
                    sb.appendLine("  最大盈利: ${"%.2f".format(r.maxGain)}% | 最大虧損: ${"%.2f".format(r.maxLoss)}%")
                    sb.appendLine()
                }

                withContext(Dispatchers.Main) {
                    showDialog("長線回溯報告", sb.toString())
                    statusTv.text = "✅ 長線回測完成"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 回測失敗: ${e.message?.take(40)}"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun showFittingHint() {
        showDialog("長線擬合提示",
            "長線策略（持倉1年+）基於深度基本面分析，參數穩定。\n\n" +
            "核心策略：\n" +
            "• 低估值 — PE/PB 歷史分位篩選\n" +
            "• 基本面三層篩選 — ROE/負債率/現金流\n" +
            "• 機構增持 — 高ROE+低負債+穩健現金流\n" +
            "• 行業龍頭護城河 — 技術壁壘+龍頭地位+高毛利\n\n" +
            "賣出條件：\n" +
            "• 基本面惡化（ROE 連續下滑）\n" +
            "• 估值過高（PE > $OVERVALUED_PE 或 PB > $OVERVALUED_PB）\n" +
            "• 行業格局發生重大變化")
    }

    // ── 覆寫賣出評估：長線使用基本面/估值規則 ──

    override fun showSellMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "💎 基本面檢查")
        popup.menu.add(0, 2, 0, "💰 賣出評估")
        popup.menu.add(0, 3, 0, "⚡ 執行賣出")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> checkFundamentalHealth()
                2 -> runAutoSellEvaluation()
                3 -> executeAutoSell()
            }
            true
        }
        popup.show()
    }

    /**
     * 基本面健康檢查：檢查長線持倉的基本面是否惡化
     */
    private fun checkFundamentalHealth() {
        progressBar.visibility = View.VISIBLE
        statusTv.text = "💎 正在檢查基本面..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == "LongTermQuant" &&
                        (it.status == "BUYING" || it.status == "PENDING") }

                if (orders.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "💎 無長線持倉"
                        showDialog("基本面檢查", "暫無長線持倉，無需檢查。")
                    }
                    return@launch
                }

                val sb = StringBuilder()
                sb.appendLine("💎 長線持倉基本面檢查報告")
                sb.appendLine("持倉數: ${orders.size} 只")
                sb.appendLine()

                for (order in orders) {
                    sb.appendLine("📊 ${order.stockName} (${order.stockCode.takeLast(6)})")
                    sb.appendLine("  建倉日: ${order.tradeDate} | 成本: ¥${"%.2f".format(order.buyPrice)}")

                    // 獲取當前價格
                    val snap = todayStocks.find { it.code == order.stockCode }
                    val currentPrice = snap?.price ?: order.buyPrice
                    val pnlPct = if (order.buyPrice > 0) {
                        (currentPrice - order.buyPrice) / order.buyPrice * 100
                    } else 0.0
                    val pnlStr = if (pnlPct >= 0) "+${"%.2f".format(pnlPct)}%" else "${"%.2f".format(pnlPct)}%"
                    sb.appendLine("  當前價: ¥${"%.2f".format(currentPrice)} | 盈虧: $pnlStr")

                    // 獲取基本面數據（財務指標在 StockRealtime 上）
                    val fundamental = snap
                    if (fundamental != null) {
                        sb.appendLine("  ── 基本面 ──")
                        if (fundamental.pe > 0) {
                            val peWarning = if (fundamental.pe > OVERVALUED_PE) " ⚠️ 估值過高" else ""
                            sb.appendLine("  PE(TTM): ${"%.1f".format(fundamental.pe)}$peWarning")
                        }
                        if (fundamental.pb > 0) {
                            val pbWarning = if (fundamental.pb > OVERVALUED_PB) " ⚠️ 估值過高" else ""
                            sb.appendLine("  PB: ${"%.2f".format(fundamental.pb)}$pbWarning")
                        }
                        if (fundamental.roeTTM > 0) {
                            val roeWarning = if (fundamental.roeTTM < 8.0) " ⚠️ ROE偏低" else " ✅"
                            sb.appendLine("  ROE: ${"%.1f".format(fundamental.roeTTM)}%$roeWarning")
                        }
                        if (fundamental.debtToAsset > 0) {
                            val debtWarning = if (fundamental.debtToAsset > 60.0) " ⚠️ 負債率偏高" else " ✅"
                            sb.appendLine("  負債率: ${"%.1f".format(fundamental.debtToAsset)}%$debtWarning")
                        }
                        if (fundamental.grossMarginTTM > 0) {
                            sb.appendLine("  毛利率: ${"%.1f".format(fundamental.grossMarginTTM)}%")
                        }
                        if (fundamental.pe <= 0 && fundamental.pb <= 0 && fundamental.roeTTM <= 0) {
                            sb.appendLine("  ── 基本面數據缺失 ──")
                        }
                    } else {
                        sb.appendLine("  ── 基本面數據缺失 ──")
                    }
                    sb.appendLine()
                }

                sb.appendLine("💡 賣出信號：")
                sb.appendLine("  • PE > $OVERVALUED_PE 或 PB > $OVERVALUED_PB → 估值過高")
                sb.appendLine("  • ROE < 8% 或連續下滑 → 基本面惡化")
                sb.appendLine("  • 負債率 > 60% → 財務風險增加")

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 基本面檢查完成 (${orders.size} 只)"
                    showDialog("基本面檢查報告", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 基本面檢查失敗: ${e.message?.take(40)}"
                }
            }
        }
    }
}
