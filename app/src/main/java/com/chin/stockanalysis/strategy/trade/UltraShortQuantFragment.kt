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
 * ## 超短線量化 Tab — 持倉 1 天，T+1 賣出
 *
 * 核心特點：
 * - 策略池：ULTRA_SHORT 週期（尾盤低吸、早盤追漲）
 * - 持倉週期：1 天（T+1 自動賣出）
 * - 最大持倉：3 只
 * - 止損 -2% / 止盈 +3%
 * - 無 AI 精選（時效優先）
 * - 建倉時機：14:30 尾盤低吸 / 開盤 30 分鐘追漲
 *
 * 流程：
 *   1. 準備股票池數據（復用 StrategyDataFeed）
 *   2. 執行 ULTRA_SHORT 週期策略篩選
 *   3. 合併結果，按強度排序取 Top 3
 *   4. 自動建倉（orderType="UltraShortQuant"）
 *   5. T+1 自動賣出（次日開盤價賣出）
 */
class UltraShortQuantFragment : QuantFragmentBase() {

    companion object {
        private const val TAG = "UltraShortQuant"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 最大持倉數 */
        private const val MAX_HOLDINGS = 3

        /** 止損線 -2% */
        private const val STOP_LOSS_PCT = -2.0

        /** 止盈線 +3% */
        private const val TAKE_PROFIT_PCT = 3.0
    }

    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView

    private var todayStocks: List<com.chin.stockanalysis.stock.StockRealtime> = emptyList()
    private var lastTradeDate: String = ""

    override fun getQuantType() = "UltraShortQuant"

    override val positionTitlePrefix = "超短線"

    override val showMultiDayPrices = false

    override fun onBuildClick() { runBuildAndBuy() }
    override fun onFittingClick() { showFittingHint() }
    override fun onBacktrackClick() { runUltraShortBacktrack() }
    override fun onClearClick() { clearData() }

    override fun getDefaultUseCaseId() = "ultra_short"

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
            text = "⚡ 超短線量化系統 (T+1 賣出，止損-2%/止盈+3%)"
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
            text = "⚡ 持倉1天 | 最多${MAX_HOLDINGS}只 | 止損${STOP_LOSS_PCT}%/止盈+${TAKE_PROFIT_PCT}%"
            textSize = 10f
            setTextColor(Color.parseColor("#E65100"))
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
    // 建倉 — 超短線核心邏輯
    // ═══════════════════════════════════════

    private fun runBuildAndBuy() {
        val eng = engine ?: return
        buildBtn.isEnabled = false
        buildBtn.text = "⏳ 執行中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "⚡ 超短線選股中..."

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
                            .fetchAllHistoricalData(30)
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
                        onlyMainBoard = false, stockCodes = poolCodes.toSet()
                    ))
                } else {
                    feed.prepareFromDb(today, StrategyDataFeed.DataFeedConfig(onlyMainBoard = false))
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
                Log.i(TAG, "[UltraShort] 股票池: ${stocks.size} 只")

                // Step 3: 執行 ULTRA_SHORT 週期策略
                withContext(Dispatchers.Main) { statusTv.text = "⚡ 執行超短線策略篩選..." }
                val strategies = eng.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚠️ 沒有啟用的超短線策略（尾盤低吸/早盤追漲）"
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                val screenings = mutableMapOf<Strategy, com.chin.stockanalysis.strategy.models.ScreeningResult>()
                for ((index, strategy) in strategies.withIndex()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚡ 執行策略: ${strategy.name} (${index + 1}/${strategies.size})"
                    }
                    try {
                        val r = strategy.screenWithData(stocks)
                        r.getOrNull()?.let { screenings[strategy] = it }
                    } catch (e: Exception) {
                        Log.w(TAG, "策略 ${strategy.id} 執行失敗: ${e.message}")
                    }
                }
                Log.i(TAG, "[UltraShort] 策略執行完成: ${screenings.size}/${strategies.size} 成功")

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
                        statusTv.text = "⚠️ 超短線策略無命中信號"
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
                val existingCodes = db.strategyTradeOrderDao().getRecent(200)
                    .filter { it.orderType == "UltraShortQuant" &&
                        (it.status == "BUYING" || it.status == "PENDING") }
                    .map { it.stockCode }.toSet()

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
                        Log.w(TAG, "超短線選股 $code 無即時價格，跳過")
                        continue
                    }
                    val name = codeToName[code] ?: code
                    val maxStrength = hits.maxOf { it.second }
                    toInsert.add(StrategyTradeOrderEntity(
                        strategyId = hits.joinToString(",") { it.first },
                        stockCode = code, stockName = name,
                        tradeDate = tradeDate, buyPrice = buyPrice,
                        buyTime = java.time.LocalTime.now().toString().take(8),
                        quantity = 100, orderType = "UltraShortQuant", status = "BUYING",
                        reason = "超短線策略命中: ${hits.joinToString(",") { "${it.first}(${it.second}%)" }}",
                        scoreAtBuy = maxStrength,
                        createdAt = System.currentTimeMillis()
                    ))
                    watchlistStocks.add(Triple(code, name, maxStrength))
                }

                if (toInsert.isNotEmpty()) {
                    db.strategyTradeOrderDao().insertAll(toInsert)
                    try {
                        com.chin.stockanalysis.stock.database.AppBackgroundRunner.addBatchToWatchlist(
                            requireContext(), watchlistStocks, source = "ultra_short"
                        )
                    } catch (_: Exception) {}
                }

                val elapsed = System.currentTimeMillis() - totalStart
                val summary = buildString {
                    appendLine("⚡ 超短線選股完成 (${elapsed}ms)")
                    appendLine("策略: ${strategies.joinToString(",") { it.name }}")
                    appendLine("命中: ${mergedPool.size} 只 → 建倉: ${toInsert.size} 只")
                    if (toInsert.isNotEmpty()) {
                        appendLine("建倉股票:")
                        for (order in toInsert) {
                            appendLine("  • ${order.stockName}(${order.stockCode.takeLast(6)}) ¥${"%.2f".format(order.buyPrice)} 強度:${order.scoreAtBuy}%")
                        }
                    }
                    appendLine("止損: ${STOP_LOSS_PCT}% | 止盈: +${TAKE_PROFIT_PCT}% | T+1 賣出")
                }

                withContext(Dispatchers.Main) {
                    showDialog("超短線選股報告", summary)
                    statusTv.text = "✅ 超短線: 建倉 ${toInsert.size} 只 (${elapsed}ms)"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                    refreshPositions()
                }

                // 觸發 T+1 賣出檢查
                checkT1AutoSell()

            } catch (e: Exception) {
                Log.e(TAG, "[UltraShort] 建倉失敗", e)
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 超短線建倉失敗: ${e.message?.take(40)}"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    /**
     * ══════════ DAG Pipeline 執行（通用開關開啟時走此路徑） ══════════
     *
     * 使用 ultra_short_pipeline.xml 動態鏈接，節點關係由 XML 定義，可重新編排。
     * 節點內部會自行將訂單寫入 strategy_trade_order 表。
     */
    private suspend fun executeViaDagPipeline(tradeDate: String, today: String) {
        val eng = engine ?: return
        withContext(Dispatchers.Main) { statusTv.text = "🔄 [DAG] 超短線 Pipeline 執行中..." }
        try {
            val strategies = eng.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
            val r = com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor.execute(
                context = requireContext(),
                useCaseId = "ultra_short",
                tradeDate = tradeDate,
                today = today,
                strategies = strategies,
                orderType = "ultra_short_dag",
                importDays = 30
            )
            withContext(Dispatchers.Main) {
                showDialog(
                    "超短線 DAG Pipeline 報告",
                    com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
                        .buildReportText("超短線 DAG Pipeline", r)
                )
                statusTv.text = r.uiText
                buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                progressBar.visibility = View.GONE
                refreshPositions()
            }
            // 觸發 T+1 賣出檢查（超短線專有）
            checkT1AutoSell()
        } catch (e: Exception) {
            Log.e(TAG, "[DAG] 超短線執行異常", e)
            withContext(Dispatchers.Main) {
                statusTv.text = "❌ [DAG] ${e.message?.take(40)}"
                buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * T+1 自動賣出：檢查昨日建倉的超短線持倉，觸發止損/止盈
     */
    private fun checkT1AutoSell() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == "UltraShortQuant" &&
                        (it.status == "BUYING" || it.status == "PENDING") }

                if (orders.isEmpty()) return@launch

                val today = TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                var sellCount = 0
                for (order in orders) {
                    // T+1: 如果建倉日不是今天，則檢查是否需要賣出
                    if (order.tradeDate < today) {
                        val snap = todayStocks.find { it.code == order.stockCode }
                        val currentPrice = snap?.price ?: continue
                        val pnlPct = (currentPrice - order.buyPrice) / order.buyPrice * 100

                        // 止損或止盈觸發 → 自動賣出
                        if (pnlPct <= STOP_LOSS_PCT || pnlPct >= TAKE_PROFIT_PCT) {
                            db.strategyTradeOrderDao().updateSellInfo(
                                id = order.id, status = "SOLD",
                                sellPrice = currentPrice,
                                sellTime = today + " " + java.time.LocalTime.now().toString().take(8),
                                profitPct = pnlPct
                            )
                            sellCount++
                            Log.i(TAG, "[UltraShort] T+1 賣出: ${order.stockName} 盈虧=${"%.2f".format(pnlPct)}%")
                        }
                    }
                }
                if (sellCount > 0) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚡ T+1 自動賣出: ${sellCount} 只觸發止損/止盈"
                        refreshPositions()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "T+1 賣出檢查失敗: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════
    // 回溯測試
    // ═══════════════════════════════════════

    private fun runUltraShortBacktrack() {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 回溯中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "⚡ 超短線回溯測試中..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = eng.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚠️ 無超短線策略可回測"
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                val ctx = requireContext()
                val backtestEngine = com.chin.stockanalysis.strategy.backtest.HistoricalBacktestEngine(ctx)
                val report = backtestEngine.runHistoricalBacktest(strategies, tradingDays = 30)

                val sb = StringBuilder()
                sb.appendLine("⚡ 超短線回溯測試報告 (30交易日)")
                sb.appendLine("持倉: 1天 | 止損: ${STOP_LOSS_PCT}% | 止盈: +${TAKE_PROFIT_PCT}%")
                sb.appendLine("期間: ${report.dateRange}")
                sb.appendLine()
                for (r in report.strategyReports) {
                    sb.appendLine("⚡ ${r.strategyName}")
                    sb.appendLine("  交易日: ${r.totalDays} 天 | 買入信號: ${r.totalBuys} 次")
                    sb.appendLine("  買入準確率: ${"%.1f".format(r.buyAccuracy * 100)}% (${r.correctBuys}/${r.totalBuys})")
                    sb.appendLine("  平均淨收益: ${"%.2f".format(r.avgReturn)}%（已扣交易成本0.3%）")
                    sb.appendLine("  最大盈利: ${"%.2f".format(r.maxGain)}% | 最大虧損: ${"%.2f".format(r.maxLoss)}%")
                    sb.appendLine()
                }

                withContext(Dispatchers.Main) {
                    showDialog("超短線回溯報告", sb.toString())
                    statusTv.text = "✅ 超短線回測完成"
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
        showDialog("超短線擬合提示",
            "超短線策略（持倉1天）參數固定，無需擬合調優。\n\n" +
            "核心參數：\n" +
            "• 止損: ${STOP_LOSS_PCT}%\n" +
            "• 止盈: +${TAKE_PROFIT_PCT}%\n" +
            "• 最大持倉: $MAX_HOLDINGS 只\n" +
            "• 持倉週期: 1天 (T+1)\n\n" +
            "如需調整，請在回溯測試中驗證不同參數組合。")
    }

    // ── 覆寫賣出評估：使用超短線止損/止盈規則 ──

    override fun showSellMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "⚡ T+1 賣出檢查")
        popup.menu.add(0, 2, 0, "💰 賣出評估")
        popup.menu.add(0, 3, 0, "⚡ 執行賣出")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> checkT1AutoSell()
                2 -> runAutoSellEvaluation()
                3 -> executeAutoSell()
            }
            true
        }
        popup.show()
    }
}
