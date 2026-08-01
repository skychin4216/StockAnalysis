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
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        /** 最大持倉數（後備默認，優先讀取策略 maxPositions） */
        private const val DEFAULT_MAX_HOLDINGS = 3

        /** 止損線 -2%（後備默認，優先讀取策略 defaultStopLoss） */
        private const val DEFAULT_STOP_LOSS_PCT = -2.0

        /** 止盈線 +3%（後備默認，優先讀取策略 defaultTakeProfit） */
        private const val DEFAULT_TAKE_PROFIT_PCT = 3.0
    }

    /** 從啟用的超短線策略讀取止損線(%)，取最保守值（最大），無則用默認 */
    private fun resolveStopLossPct(): Double {
        val vals = engine?.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
            ?.mapNotNull { it.defaultStopLoss } ?: emptyList()
        return vals.maxOrNull()?.toDouble()?.times(100) ?: DEFAULT_STOP_LOSS_PCT
    }

    /** 從啟用的超短線策略讀取止盈線(%)，取最小值，無則用默認 */
    private fun resolveTakeProfitPct(): Double {
        val vals = engine?.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
            ?.mapNotNull { it.defaultTakeProfit } ?: emptyList()
        return vals.minOrNull()?.toDouble()?.times(100) ?: DEFAULT_TAKE_PROFIT_PCT
    }

    /** 從啟用的超短線策略讀取最大持倉數，取最小值，無則用默認 */
    private fun resolveMaxHoldings(): Int {
        val vals = engine?.getEnabledStrategiesByPeriod(HoldingPeriod.ULTRA_SHORT)
            ?.map { it.maxPositions } ?: emptyList()
        return vals.minOrNull() ?: DEFAULT_MAX_HOLDINGS
    }

    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView
    private lateinit var mainBoardSwitch: Switch

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

        // 僅主板開關
        mainBoardSwitch = Switch(requireContext()).apply {
            text = "仅主板"; textSize = 11f; isChecked = false; setTextColor(Color.parseColor("#333333"))
        }
        configRow.addView(mainBoardSwitch)

        // 提示標籤
        val tipTv = TextView(requireContext()).apply {
            text = "⚡ 持倉1天 | 最多${resolveMaxHoldings()}只 | 止損${resolveStopLossPct()}%/止盈+${resolveTakeProfitPct()}%"
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
        engine ?: return
        buildBtn.isEnabled = false
        buildBtn.text = "⏳ 執行中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "⚡ 超短線選股中..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val today = TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val tradeDate = browsingDate.format(DATE_FMT)
                lastTradeDate = tradeDate
                executeViaDagPipeline(tradeDate, today)
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
                orderType = "ultra_short",
                importDays = 30,
                onNodeProgress = { pipelineName, nodeName ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        statusTv.text = "🔄 [DAG] ${pipelineName} ${nodeName} 執行中..."
                    }
                }
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
     * T+1 自動賣出：
     * - 建倉日早於今日（T+1 到期）→ 次日集合競價無論盈虧強制清倉
     * - 當日建倉 → 僅在觸發止損/止盈時賣出
     *
     * 價格來源：MultiSourceStockRepository 實時行情（非建倉時快照），避免過期價格。
     */
    private fun checkT1AutoSell() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == "UltraShortQuant" &&
                        (it.status == "BUYING" || it.status == "PENDING") }

                if (orders.isEmpty()) return@launch

                // 從策略風控字段解析止損/止盈線
                val stopLossPct = resolveStopLossPct()
                val takeProfitPct = resolveTakeProfitPct()

                // 實時價格（5源並發競速），取代 todayStocks 緩存快照
                val realtime = try {
                    com.chin.stockanalysis.stock.data.StockDataSourceFactory
                        .createDefaultRepository(requireContext().applicationContext)
                        .getRealtime(orders.map { it.stockCode })
                } catch (e: Exception) {
                    Log.w(TAG, "T+1 實時行情獲取失敗: ${e.message}"); emptyMap()
                }

                val today = TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                var sellCount = 0
                var forcedCount = 0
                for (order in orders) {
                    val currentPrice = realtime[order.stockCode]?.price ?: continue
                    if (currentPrice <= 0) continue
                    val pnlPct = (currentPrice - order.buyPrice) / order.buyPrice * 100

                    val isT1Due = order.tradeDate < today
                    val hitStop = pnlPct <= stopLossPct || pnlPct >= takeProfitPct

                    // T+1 到期 → 無論盈虧強制清倉；當日建倉 → 僅止損/止盈觸發
                    if (isT1Due || hitStop) {
                        db.strategyTradeOrderDao().updateSellInfo(
                            id = order.id, status = "SOLD",
                            sellPrice = currentPrice,
                            sellTime = today + " " + java.time.LocalTime.now().toString().take(8),
                            profitPct = pnlPct
                        )
                        sellCount++
                        if (isT1Due) forcedCount++
                        Log.i(TAG, "[UltraShort] T+1 賣出: ${order.stockName} " +
                            "盈虧=${"%.2f".format(pnlPct)}% ${if (isT1Due) "(次日強制清倉)" else "(止損/止盈)"}")
                    }
                }
                if (sellCount > 0) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚡ T+1 賣出: ${sellCount} 只 (強制清倉 ${forcedCount})"
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
                sb.appendLine("持倉: 1天 | 止損: ${resolveStopLossPct()}% | 止盈: +${resolveTakeProfitPct()}%")
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
            "• 止損: ${resolveStopLossPct()}%\n" +
            "• 止盈: +${resolveTakeProfitPct()}%\n" +
            "• 最大持倉: ${resolveMaxHoldings()} 只\n" +
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
