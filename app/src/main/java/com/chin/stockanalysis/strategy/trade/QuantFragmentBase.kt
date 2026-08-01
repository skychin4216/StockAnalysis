package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.DataExportImport
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 量化交易 Fragment 基類
 *
 * 為中線量化（MidTermQuant）和短線量化（ShortTermQuant）提供共用功能：
 * - 統一按鈕行：建倉 → 持倉 → 回溯 → 擬合 → 賣出 → 數據
 * - 賣出評估與執行（使用 AutoSellEngine）
 * - 數據管理與清除
 * - 持倉刷新與顯示
 */
abstract class QuantFragmentBase : Fragment() {

    // ═══════════════════════════════════════════════════
    // 子類可覆寫的配置
    // ═══════════════════════════════════════════════════

    /** 是否顯示多日價格列（子類可覆寫） */
    protected open val showMultiDayPrices: Boolean = true

    /** 持倉標題前綴（如 "短線量化" / "中線量化"） */
    protected open val positionTitlePrefix: String = ""

    // ═══════════════════════════════════════════════════
    // UI 組件
    // ═══════════════════════════════════════════════════

    /** 根佈局 */
    protected lateinit var rootLayout: LinearLayout

    /** 狀態文字 */
    protected lateinit var statusTv: TextView

    /** 進度條 */
    protected lateinit var progressBar: ProgressBar

    /** 持倉容器 */
    protected lateinit var positionContainer: LinearLayout

    /** 建倉按鈕 */
    protected lateinit var buildBtn: Button

    /** 清除按鈕 */
    protected lateinit var clearBtn: Button

    /** 做T按鈕（顯示待處理推薦數） */
    protected lateinit var tTradeBtn: Button

    // ═══════════════════════════════════════════════════
    // 引擎與數據
    // ═══════════════════════════════════════════════════

    protected var engine: StrategyEngine? = null
    protected var browsingDate: LocalDate = TradingDayPickerView.recentTradingDay()

    /** 賣出決策緩存 */
    protected var sellDecisionsCache: List<AutoSellEngine.SellDecision> = emptyList()

    /** 清除模式標記 */
    protected var clearMode: Boolean = false
    protected var selectedDateForClear: String? = null

    companion object {
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val TAG = "QuantFragmentBase"
    }

    // ═══════════════════════════════════════════════════
    // 生命週期
    // ═══════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
        refreshPositions()
        return rootLayout
    }

    protected open fun initEngine() {
        val ctx = requireContext().applicationContext
        com.chin.stockanalysis.strategy.StrategyEngineHolder.init(ctx)
        engine = com.chin.stockanalysis.strategy.StrategyEngineHolder.get()
    }

    protected abstract fun buildUI()

    // ═══════════════════════════════════════════════════
    // 抽象方法（子類必須實現）
    // ═══════════════════════════════════════════════════

    /** 返回量化類型："ShortTermQuant" 或 "MidTermQuant" */
    abstract fun getQuantType(): String

    /** 建倉按鈕點擊 — 各子類實現自己的選股邏輯 */
    abstract fun onBuildClick()

    /** 擬合按鈕點擊 */
    abstract fun onFittingClick()

    /** 回溯按鈕點擊 */
    abstract fun onBacktrackClick()

    /** 清除按鈕點擊 */
    abstract fun onClearClick()

    /** 加載持倉（默認調用 refreshPositions，子類可覆寫） */
    open fun loadPositions() = refreshPositions()

    // ═══════════════════════════════════════════════════
    // UI 輔助方法
    // ═══════════════════════════════════════════════════

    /**
     * 統一按鈕行：建倉 | Pipeline | 持倉 | 賣出 ▾ | 數據 ▾
     */
    protected fun createButtonRow(): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 1, 4, 1)
        }

        // ── 1. 建倉 ──
        buildBtn = Button(requireContext()).apply {
            text = "▶ 建倉"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E65100"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1.0f).apply { marginEnd = 1 }
            setOnClickListener { onBuildClick() }
        }
        row.addView(buildBtn)

        // ── 2. Pipeline（啟動拓撲編輯器） ──
        val pipelineBtn = Button(requireContext()).apply {
            text = "Pipeline"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6A1B9A"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }
            setOnClickListener { openPipelineEditor() }
        }
        row.addView(pipelineBtn)

        // ── 3. 持倉 ──
        val posBtn = Button(requireContext()).apply {
            text = "📊 持倉"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }
            setOnClickListener { refreshPositions() }
        }
        row.addView(posBtn)

        // ── 3.5 做T（T+0 日內交易） ──
        tTradeBtn = Button(requireContext()).apply {
            text = "做T"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#BF360C"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.8f).apply { marginEnd = 1 }
            setOnClickListener { showTTradeMenu() }
        }
        row.addView(tTradeBtn)

        // ── 3.6 真實持倉（手動導入券商持倉，做T和規劃） ──
        val realPosBtn = Button(requireContext()).apply {
            text = "👤 真倉"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6A1B9A"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.8f).apply { marginEnd = 1 }
            setOnClickListener { showRealPositionMenu() }
        }
        row.addView(realPosBtn)

        // ── 4. 賣出（帶下拉菜單） ──
        val sellBtn = Button(requireContext()).apply {
            text = "💰 賣出 ▾"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00897B"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1f).apply { marginEnd = 1 }
            setOnClickListener { showSellMenu(it) }
        }
        row.addView(sellBtn)

        // ── 5. 數據 ▾（含回溯/擬合/數據管理） ──
        val dataBtn = Button(requireContext()).apply {
            text = "🗄️ 數據 ▾"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#455A64"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1.0f)
            setOnClickListener { showDataMenu(it) }
        }
        row.addView(dataBtn)

        return row
    }

    /** 啟動 Pipeline 拓撲編輯器 */
    protected open fun openPipelineEditor() {
        try {
            val intent = android.content.Intent(
                requireContext(),
                com.chin.stockanalysis.strategy.topology.ui.TopologyEditorActivity::class.java
            )
            intent.putExtra("usecase_id", getDefaultUseCaseId())
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Pipeline 編輯器跳轉失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 子類覆蓋以指定預加載的 UseCase ID */
    protected open fun getDefaultUseCaseId(): String = "mid_term"

    /**
     * 創建進度條行
     */
    protected fun createProgressRow(): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 4, 16, 4)
        }

        progressBar = ProgressBar(requireContext()).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
        }
        row.addView(progressBar)

        statusTv = TextView(requireContext()).apply {
            text = "就緒"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        row.addView(statusTv)

        return row
    }

    /**
     * 創建標準單元格
     */
    protected fun createCell(
        text: String,
        widthDp: Int,
        colorHex: String,
        fontSize: Float,
        bold: Boolean = false
    ): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = fontSize
        setTextColor(Color.parseColor(colorHex))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dpToPx(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
        setPadding(2, 4, 2, 4)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    /**
     * dp 轉 px
     */
    protected fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ═══════════════════════════════════════════════════
    // 通用模板方法（子類可直接調用，消除重複代碼）
    // ═══════════════════════════════════════════════════

    /**
     * 創建日期選擇器行（日期標籤 + 交易日選擇器 + 僅主板開關 + 提示文字）
     * @param tipText 提示標籤文字（如 "⚡ 持倉1天 | 最多3只"）
     * @param tipColor 提示文字顏色（默認橙色）
     * @param mainBoardDefault 僅主板開關默認值
     * @return Triple(行Layout, TradingDayPickerView, Switch)
     */
    protected fun createDatePickerRow(
        tipText: String,
        tipColor: String = "#E65100",
        mainBoardDefault: Boolean = true
    ): Triple<LinearLayout, TradingDayPickerView, Switch> {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6); setBackgroundColor(Color.WHITE)
        }
        val dateLabelTv = TextView(ctx).apply {
            text = "📅 交易日:"; textSize = 12f
            setTextColor(Color.parseColor("#333333")); setTypeface(null, Typeface.BOLD)
        }
        row.addView(dateLabelTv)
        val datePicker = TradingDayPickerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 4; marginEnd = 6 }
            onDateChanged = { d ->
                browsingDate = d
                val isNonTrading = d.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                    d.dayOfWeek == java.time.DayOfWeek.SUNDAY ||
                    d in TradingDayPickerView.CHINESE_HOLIDAYS
                dateLabelTv.text = if (isNonTrading) "📅 非交易日:" else "📅 交易日:"
            }
        }
        row.addView(datePicker)
        val mainBoardSwitch = Switch(ctx).apply {
            text = "仅主板"; textSize = 11f; isChecked = mainBoardDefault
            setTextColor(Color.parseColor("#333333"))
        }
        row.addView(mainBoardSwitch)
        if (tipText.isNotBlank()) {
            row.addView(TextView(ctx).apply {
                text = tipText; textSize = 10f
                setTextColor(Color.parseColor(tipColor)); setPadding(8, 0, 0, 0)
            })
        }
        return Triple(row, datePicker, mainBoardSwitch)
    }

    /** 創建持倉顯示區（ScrollView + positionContainer） */
    protected fun createContentScrollArea(): ScrollView {
        positionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(8, 4, 8, 4)
        }
        return ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(positionContainer)
        }
    }

    /** 添加分隔線 */
    protected fun addSeparator(topMargin: Int = 8) {
        rootLayout.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .apply { this.topMargin = topMargin }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })
    }

    /** 添加標題行 */
    protected fun addTitleRow(text: String, textSize: Float = 14f, textColor: String = "#1A1A2E") {
        rootLayout.addView(TextView(requireContext()).apply {
            this.text = text; this.textSize = textSize
            setTextColor(Color.parseColor(textColor)); setTypeface(null, Typeface.BOLD)
            setPadding(16, 12, 16, 6)
        })
    }

    /**
     * ═══ 通用 DAG Pipeline 執行模板 ═══
     *
     * 超短線/短線/長線共用此方法，只需傳入不同參數。
     *
     * @param holdingPeriod 持倉週期
     * @param useCaseId DAG useCase ID (如 "ultra_short", "short_term", "long_term")
     * @param orderType 訂單類型 (如 "ultra_short", "shortterm", "long_term")
     * @param importDays 導入天數
     * @param titlePrefix 標題前綴 (如 "超短線", "短線", "長線")
     * @param onComplete 完成後回調（如超短線的 T+1 檢查）
     */
    protected fun runDagPipeline(
        holdingPeriod: HoldingPeriod,
        useCaseId: String,
        orderType: String,
        importDays: Int,
        titlePrefix: String,
        onComplete: (() -> Unit)? = null
    ) {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 執行中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "🔄 [DAG] ${titlePrefix} Pipeline 執行中..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val today = TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val tradeDate = browsingDate.format(DATE_FMT)
                val strategies = eng.getEnabledStrategiesByPeriod(holdingPeriod)
                val r = com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor.execute(
                    context = requireContext(),
                    useCaseId = useCaseId,
                    tradeDate = tradeDate,
                    today = today,
                    strategies = strategies,
                    orderType = orderType,
                    importDays = importDays,
                    onNodeProgress = { pipelineName, nodeName ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            statusTv.text = "🔄 [DAG] ${pipelineName} ${nodeName} 執行中..."
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    showDialog(
                        "${titlePrefix} DAG Pipeline 報告",
                        com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
                            .buildReportText("${titlePrefix} DAG Pipeline", r)
                    )
                    statusTv.text = r.uiText
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                    refreshPositions()
                }
                onComplete?.invoke()
            } catch (e: Exception) {
                Log.e("QuantFragmentBase", "[DAG] ${titlePrefix} 執行異常", e)
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ [DAG] ${e.message?.take(40)}"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    /**
     * ═══ 通用歷史回溯測試模板 ═══
     *
     * 超短線/短線/長線共用此方法。
     *
     * @param holdingPeriod 持倉週期（null 表示使用所有啟用策略）
     * @param tradingDays 回測天數
     * @param titlePrefix 標題前綴
     * @param extraInfo 額外信息行（如 "持倉: 1天 | 止損: -2%"）
     */
    protected fun runHistoricalBacktrack(
        holdingPeriod: HoldingPeriod?,
        tradingDays: Int,
        titlePrefix: String,
        extraInfo: String = ""
    ) {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 回溯中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "${titlePrefix}回溯測試中..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = if (holdingPeriod != null) {
                    eng.getEnabledStrategiesByPeriod(holdingPeriod)
                } else {
                    eng.getStrategies().filter { eng.isEnabled(it.id) }
                }
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚠️ 無${titlePrefix}策略可回測"
                        buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                val backtestEngine = com.chin.stockanalysis.strategy.backtest.HistoricalBacktestEngine(requireContext())
                val report = backtestEngine.runHistoricalBacktest(strategies, tradingDays = tradingDays)

                val sb = StringBuilder()
                sb.appendLine("${titlePrefix}回溯測試報告 (${tradingDays}交易日)")
                if (extraInfo.isNotBlank()) sb.appendLine(extraInfo)
                sb.appendLine("期間: ${report.dateRange}")
                sb.appendLine()
                for (r in report.strategyReports) {
                    sb.appendLine("📋 ${r.strategyName}")
                    sb.appendLine("  交易日: ${r.totalDays} 天 | 買入信號: ${r.totalBuys} 次")
                    sb.appendLine("  買入準確率: ${"%.1f".format(r.buyAccuracy * 100)}% (${r.correctBuys}/${r.totalBuys})")
                    sb.appendLine("  平均淨收益: ${"%.2f".format(r.avgReturn)}%（已扣交易成本0.3%）")
                    sb.appendLine("  最大盈利: ${"%.2f".format(r.maxGain)}% | 最大虧損: ${"%.2f".format(r.maxLoss)}%")
                    sb.appendLine()
                }

                withContext(Dispatchers.Main) {
                    showDialog("${titlePrefix}回溯報告", sb.toString())
                    statusTv.text = "✅ ${titlePrefix}回測完成"
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

    /**
     * ═══ 通用擬合參數報告模板 ═══
     *
     * 短線/中線共用此方法展示擬合參數報告。
     *
     * @param titlePrefix 標題前綴 (如 "短線", "中線")
     * @param periodLabel 週期標籤 (如 "3日", 可選)
     */
    protected fun showFittingParamsReport(
        titlePrefix: String,
        periodLabel: String = ""
    ) {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 擬合中"
        progressBar.visibility = View.VISIBLE
        statusTv.text = "🔧 ${titlePrefix}擬合調優中${if (periodLabel.isNotBlank()) "（週期: $periodLabel）" else ""}..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val te = StrategyFittingEngine(requireContext())
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val recentDates = db.dailySnapshotDao().getAvailableDates(30).sorted()

                if (strategies.isNotEmpty() && recentDates.size >= 2) {
                    try {
                        te.autoFit(strategies, recentDates)
                    } catch (e: Exception) {
                        Log.w("QuantFragmentBase", "${titlePrefix}擬合失敗（仍顯示已有數據）: ${e.message}")
                    }
                }

                val sb = StringBuilder()
                sb.appendLine("🔧 ${titlePrefix}擬合參數${if (periodLabel.isNotBlank()) "（當前週期: $periodLabel）" else ""}")
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
                    showDialog("${titlePrefix}擬合參數", sb.toString())
                    statusTv.text = "✅ 擬合完成: ${strategies.size} 個策略"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 擬合失敗: ${e.message?.take(30)}"
                    buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 賣出功能
    // ═══════════════════════════════════════════════════

    /** 顯示賣出下拉菜單 */
    protected open fun showSellMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "💰 賣出評估")
        popup.menu.add(0, 2, 0, "📊 賣出績效")
        popup.menu.add(0, 3, 0, "⚡ 執行賣出")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> runAutoSellEvaluation()
                2 -> showSellPerformance()
                3 -> executeAutoSell()
            }
            true
        }
        popup.show()
    }

    /** 執行賣出評估（僅評估，不執行） */
    protected fun runAutoSellEvaluation() {
        val eng = engine ?: return
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在評估賣出信號..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val appCtx = requireContext().applicationContext
                val sellEngine = AutoSellEngine(appCtx)
                val decisions = sellEngine.evaluateAll(strategies, AutoSellEngine.AutoSellConfig(tradeDate = browsingDate.format(DATE_FMT)))
                    .filter { it.order.orderType == getQuantType() }
                sellDecisionsCache = decisions
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    val shouldSell = decisions.count { it.shouldSell }
                    val holding = decisions.size
                    statusTv.text = "💰 賣出評估: $holding 持倉, $shouldSell 觸發賣出"
                    showSellDecisionsDialog(decisions)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 賣出評估失敗: ${e.message?.take(40)}"
                }
            }
        }
    }

    /** 執行賣出（使用緩存的決策） */
    protected fun executeAutoSell() {
        if (sellDecisionsCache.isEmpty()) {
            runAutoSellEvaluation()
            Toast.makeText(requireContext(), "請等待評估完成後再次點擊「執行賣出」", Toast.LENGTH_SHORT).show()
            return
        }
        val shouldSell = sellDecisionsCache.filter { it.shouldSell }
        if (shouldSell.isEmpty()) {
            Toast.makeText(requireContext(), "當前沒有需要賣出的持倉", Toast.LENGTH_SHORT).show()
            return
        }
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在執行賣出..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val appCtx = requireContext().applicationContext
                val sellEngine = AutoSellEngine(appCtx)
                val strategies = engine?.getStrategies()?.filter { engine!!.isEnabled(it.id) } ?: emptyList()
                val decisions = sellEngine.evaluateAll(strategies, AutoSellEngine.AutoSellConfig(tradeDate = browsingDate.format(DATE_FMT)))
                sellEngine.executeSells(decisions, browsingDate.format(DATE_FMT))
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 已執行 ${shouldSell.size} 筆賣出"
                    refreshPositions()
                    sellDecisionsCache = emptyList()
                    Toast.makeText(requireContext(), "已賣出 ${shouldSell.size} 筆訂單", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 賣出執行失敗: ${e.message?.take(40)}"
                }
            }
        }
    }

    /** 顯示賣出決策對話框 */
    protected fun showSellDecisionsDialog(decisions: List<AutoSellEngine.SellDecision>) {
        val sb = StringBuilder()
        sb.appendLine("💰 智能賣出評估報告")
        sb.appendLine("交易日: ${browsingDate.format(DATE_FMT)}")
        sb.appendLine("共 ${decisions.size} 個持倉評估")
        sb.appendLine()

        val sellList = decisions.filter { it.shouldSell }.sortedByDescending { it.urgency }
        val holdList = decisions.filter { !it.shouldSell }

        if (sellList.isNotEmpty()) {
            sb.appendLine("🔴 賣出信號 (${sellList.size}個):")
            for (d in sellList) {
                val emoji = when {
                    d.urgency >= 9 -> "🚨"
                    d.urgency >= 7 -> "⚠️"
                    d.urgency >= 5 -> "📢"
                    else -> "🔔"
                }
                sb.appendLine("  $emoji ${d.order.stockName}(${d.order.stockCode.takeLast(6)})")
                sb.appendLine("    觸發: ${d.strategy} | 盈虧: ${"%.2f".format(d.profitPct)}% | 賣出比例: ${(d.sellRatio * 100).toInt()}%")
                sb.appendLine("    原因: ${d.reason.take(80)}")
                if (d.technicalDetails.isNotEmpty())
                    d.technicalDetails.forEach { (k, v) -> sb.appendLine("    $k=$v") }
                sb.appendLine()
            }
        }

        if (holdList.isNotEmpty()) {
            sb.appendLine("🟢 繼續持有 (${holdList.size}個):")
            for (d in holdList) {
                sb.appendLine("  ✅ ${d.order.stockName}(${d.order.stockCode.takeLast(6)}) 盈虧: ${"%.2f".format(d.profitPct)}%")
                if (d.technicalDetails.isNotEmpty()) {
                    val tech = d.technicalDetails
                    sb.append("    MA5:${tech["ma5"] ?: "N/A"} MA20:${tech["ma20"] ?: "N/A"} RSI:${tech["rsi"] ?: "N/A"} ATR:${tech["atr"] ?: "N/A"}")
                    sb.appendLine()
                }
            }
        }

        showDialog("賣出評估報告", sb.toString())
    }

    /** 查看賣出策略歷史績效 */
    protected fun showSellPerformance() {
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在統計賣出績效..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val appCtx = requireContext().applicationContext
                val sellEngine = AutoSellEngine(appCtx)
                val stats = sellEngine.getSellPerformance(90)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 賣出績效已加載"
                    if (stats.isEmpty()) {
                        showDialog("賣出績效", "暫無賣出記錄，無法統計績效")
                        return@withContext
                    }
                    val sb = StringBuilder()
                    sb.appendLine("📊 賣出策略績效統計 (最近90天)")
                    sb.appendLine()
                    sb.appendLine("策略            賣出數  均收益   勝率    最大贏   最大虧   均持倉")
                    sb.appendLine("──────────────────────────────────────────────────────")
                    for (s in stats) {
                        val name = when (s.strategyName) {
                            "HardStop" -> "硬止損"; "MaxDrawdown" -> "最大回撤"
                            "TimeForceClose" -> "時間強平"; "TimeNoProgress" -> "時間無進展"
                            "TieredTP" -> "階梯止盈"; "ChandelierExit" -> "吊燈止損"
                            "TrailProfit" -> "移動止盈"; "MADeathCross" -> "MA死叉"
                            "VolumeClimax" -> "放量滯漲"; "RSIOverbought" -> "RSI超買"
                            "SectorWeakness" -> "板塊弱勢"; "TakeProfit" -> "目標止盈"
                            "ATRStop" -> "ATR止損"; "MomentumDecay" -> "動量衰竭"
                            "Other" -> "其他"; else -> s.strategyName.take(6)
                        }
                        sb.appendLine(
                            "${name.padEnd(10)} ${s.totalSells.toString().padEnd(6)} " +
                            "${"%.2f".format(s.avgProfitPct).padStart(7)}% " +
                            "${"%.0f".format(s.winRate * 100).padStart(5)}% " +
                            "${"%.2f".format(s.maxProfitPct).padStart(7)}% " +
                            "${"%.2f".format(s.maxLossPct).padStart(7)}% " +
                            "${"%.1f".format(s.avgDaysHeld).padStart(4)}天"
                        )
                    }
                    sb.appendLine()
                    sb.appendLine("💡 綜合表現排名基於: 勝率 × 平均收益")
                    showDialog("賣出績效", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 績效統計失敗: ${e.message?.take(40)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 真實持倉（手動導入券商持倉）
    // ═══════════════════════════════════════════════════

    /** 顯示真實持倉管理菜單 */
    protected open fun showRealPositionMenu() {
        val items = arrayOf(
            "📋 查看真實持倉",
            "➕ 添加真實持倉",
            "✏️ 編輯持倉",
            "💰 賣出/減倉",
            "🔄 對真實持倉做T",
            "📊 真實持倉做T統計"
        )
        val builder = android.app.AlertDialog.Builder(requireContext())
            .setTitle("👤 真實持倉管理")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRealPositionList()
                    1 -> showAddRealPositionDialog()
                    2 -> showEditRealPositionDialog()
                    3 -> showSellRealPositionDialog()
                    4 -> showRealPositionTSignals()
                    5 -> showRealPositionTStats()
                }
            }
        builder.show()
    }

    /** 顯示真實持倉列表 */
    private fun showRealPositionList() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val positions = db.realPositionDao().getAllActive()
                withContext(Dispatchers.Main) {
                    if (positions.isEmpty()) {
                        showDialog("真實持倉", "暫無真實持倉記錄\n\n點擊「添加真實持倉」錄入您的券商持倉")
                        return@withContext
                    }

                    val sb = StringBuilder()
                    sb.appendLine("📊 真實持倉 (${positions.size} 只):")
                    sb.appendLine("─".repeat(50))
                    var totalCost = 0.0
                    for (p in positions) {
                        val cost = p.avgBuyPrice * p.quantity
                        totalCost += cost
                        sb.appendLine("▸ ${p.stockName}(${p.stockCode})")
                        sb.appendLine("  數量: ${p.quantity}股 | 均價: ${"%.2f".format(p.avgBuyPrice)} | 成本: ${"%.0f".format(cost)}")
                        if (p.periodType.isNotEmpty()) {
                            sb.appendLine("  分類: ${p.periodType}")
                        }
                        if (p.notes.isNotEmpty()) {
                            sb.appendLine("  備註: ${p.notes}")
                        }
                        sb.appendLine()
                    }
                    sb.appendLine("─".repeat(50))
                    sb.appendLine("💰 總成本: ${"%.0f".format(totalCost)}")
                    sb.appendLine()
                    sb.appendLine("💡 在券商APP查看持倉後，點擊「添加真實持倉」錄入")
                    sb.appendLine("💡 錄入後可使用「對真實持倉做T」生成做T建議")
                    showDialog("真實持倉列表", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("真實持倉", "❌ 加載失敗: ${e.message}")
                }
            }
        }
    }

    /** 添加真實持倉對話框 */
    private fun showAddRealPositionDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
        }

        val codeEt = android.widget.EditText(ctx).apply {
            hint = "股票代碼 (如 sh600519)"
            setSingleLine()
        }
        val nameEt = android.widget.EditText(ctx).apply {
            hint = "股票名稱 (如 貴州茅臺)"
            setSingleLine()
        }
        val qtyEt = android.widget.EditText(ctx).apply {
            hint = "持有數量 (股)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val priceEt = android.widget.EditText(ctx).apply {
            hint = "買入均價"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        val dateEt = android.widget.EditText(ctx).apply {
            hint = "買入日期 (yyyy-MM-dd)"
            setSingleLine()
            val today = java.time.LocalDate.now().toString()
            setText(today)
        }

        val periodSpinner = android.widget.ArrayAdapter<String>(
            ctx, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("未分類", "短線 ShortTermQuant", "中線 MidTermQuant", "長線 LongTermQuant")
        )
        val periodSpinnerView = android.widget.Spinner(ctx).apply {
            adapter = periodSpinner
        }

        val notesEt = android.widget.EditText(ctx).apply {
            hint = "備註 (可選)"
            setSingleLine()
        }

        container.addView(codeEt)
        container.addView(nameEt)
        container.addView(qtyEt)
        container.addView(priceEt)
        container.addView(dateEt)
        container.addView(android.widget.TextView(ctx).apply {
            text = "持倉分類:"; textSize = 12f; setPadding(0, 8, 0, 4)
        })
        container.addView(periodSpinnerView)
        container.addView(notesEt)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("➕ 添加真實持倉")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val code = codeEt.text.toString().trim()
                val name = nameEt.text.toString().trim()
                val qty = qtyEt.text.toString().trim().toIntOrNull() ?: 0
                val price = priceEt.text.toString().trim().toDoubleOrNull() ?: 0.0
                val date = dateEt.text.toString().trim()
                val periodIdx = periodSpinnerView.selectedItemPosition
                val periodType = when (periodIdx) {
                    1 -> "ShortTermQuant"
                    2 -> "MidTermQuant"
                    3 -> "LongTermQuant"
                    else -> ""
                }
                val notes = notesEt.text.toString().trim()

                if (code.isEmpty()) {
                    android.widget.Toast.makeText(ctx, "請輸入股票代碼", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(ctx.applicationContext)
                        val rowId = db.realPositionDao().insert(
                            RealPositionEntity(
                                stockCode = code,
                                stockName = name,
                                quantity = qty,
                                avgBuyPrice = price,
                                buyDate = date,
                                periodType = periodType,
                                notes = notes
                            )
                        )
                        // 名稱為空時，異步用 StockNameResolver 補全
                        if (name.isEmpty()) {
                            try {
                                val resolved = com.chin.stockanalysis.stock.database.StockNameResolver
                                    .resolve(ctx.applicationContext, code)
                                if (resolved.isNotBlank() && resolved != code) {
                                    db.realPositionDao().updateStockName(rowId, resolved)
                                    Log.i(TAG, "✅ 真實持倉名稱已補全: $code → $resolved")
                                }
                            } catch (_: Exception) {}
                        }
                        withContext(Dispatchers.Main) {
                            val label = if (name.isNotEmpty()) name else code
                            android.widget.Toast.makeText(ctx, "✅ 已添加 $label", android.widget.Toast.LENGTH_SHORT).show()
                            refreshPositions()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "❌ 添加失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 編輯真實持倉（先列出活躍真倉供選擇，再彈出編輯表單） */
    private fun showEditRealPositionDialog() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val positions = db.realPositionDao().getAllActive()
                withContext(Dispatchers.Main) {
                    if (positions.isEmpty()) {
                        showDialog("編輯持倉", "暫無真實持倉可編輯\n\n請先添加真實持倉")
                        return@withContext
                    }

                    val labels = positions.mapIndexed { i, p ->
                        val nameLabel = p.stockName.ifEmpty { "⚠️待完善" }
                        "${i + 1}. $nameLabel(${p.stockCode}) ${p.quantity}股 @ ${"%.2f".format(p.avgBuyPrice)}"
                    }.toTypedArray()

                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("✏️ 選擇要編輯的持倉")
                        .setItems(labels) { _, which ->
                            showEditRealPositionForm(positions[which])
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("編輯持倉", "❌ 加載失敗: ${e.message}")
                }
            }
        }
    }

    /** 編輯真實持倉表單（預填現有數據） */
    private fun showEditRealPositionForm(position: RealPositionEntity) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
        }

        val codeEt = android.widget.EditText(ctx).apply {
            setText(position.stockCode); setSingleLine()
            hint = "股票代碼"
            isEnabled = false // 代碼不可編輯（updatePosition 不更新代碼）
        }
        val nameEt = android.widget.EditText(ctx).apply {
            setText(position.stockName); setSingleLine()
            hint = "股票名稱（可留空，自動補全）"
        }
        val qtyEt = android.widget.EditText(ctx).apply {
            setText(position.quantity.toString()); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "持有數量 (股)"
        }
        val priceEt = android.widget.EditText(ctx).apply {
            setText(if (position.avgBuyPrice > 0) "%.2f".format(position.avgBuyPrice) else "")
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "買入均價"
        }
        val sectorEt = android.widget.EditText(ctx).apply {
            setText(position.sector); setSingleLine()
            hint = "板塊 (可選)"
        }
        val notesEt = android.widget.EditText(ctx).apply {
            setText(position.notes); setSingleLine()
            hint = "備註 (可選)"
        }

        fun addLabel(text: String) {
            container.addView(android.widget.TextView(ctx).apply {
                this.text = text; textSize = 12f; setPadding(0, 8, 0, 2)
            })
        }
        addLabel("股票代碼:"); container.addView(codeEt)
        addLabel("股票名稱:"); container.addView(nameEt)
        addLabel("數量:"); container.addView(qtyEt)
        addLabel("均價:"); container.addView(priceEt)
        addLabel("板塊:"); container.addView(sectorEt)
        addLabel("備註:"); container.addView(notesEt)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("✏️ 編輯持倉")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = nameEt.text.toString().trim()
                val qty = qtyEt.text.toString().trim().toIntOrNull() ?: 0
                val price = priceEt.text.toString().trim().toDoubleOrNull() ?: 0.0
                val sector = sectorEt.text.toString().trim()
                val notes = notesEt.text.toString().trim()
                // 代碼不可變更，沿用原值
                val code = position.stockCode

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(ctx.applicationContext)
                        db.realPositionDao().updatePosition(
                            id = position.id,
                            quantity = qty,
                            avgBuyPrice = price,
                            name = name,
                            sector = sector,
                            notes = notes
                        )
                        // 若原名稱為空、現在仍未填，則異步補全；若已補全也一併寫入
                        if (name.isEmpty()) {
                            try {
                                val resolved = com.chin.stockanalysis.stock.database.StockNameResolver
                                    .resolve(ctx.applicationContext, code)
                                if (resolved.isNotBlank() && resolved != code) {
                                    db.realPositionDao().updateStockName(position.id, resolved)
                                    Log.i(TAG, "✅ 編輯後名稱已補全: $code → $resolved")
                                }
                            } catch (_: Exception) {}
                        }
                        withContext(Dispatchers.Main) {
                            val label = if (name.isNotEmpty()) name else code
                            android.widget.Toast.makeText(ctx, "✅ 已更新 $label", android.widget.Toast.LENGTH_SHORT).show()
                            refreshPositions()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "❌ 更新失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 賣出/減倉真實持倉（先列出活躍真倉供選擇，再彈出賣出表單） */
    private fun showSellRealPositionDialog() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val positions = db.realPositionDao().getAllActive()
                withContext(Dispatchers.Main) {
                    if (positions.isEmpty()) {
                        showDialog("賣出/減倉", "暫無真實持倉可賣出\n\n請先添加真實持倉")
                        return@withContext
                    }

                    val labels = positions.mapIndexed { i, p ->
                        val nameLabel = p.stockName.ifEmpty { "⚠️待完善" }
                        "${i + 1}. $nameLabel(${p.stockCode}) ${p.quantity}股 @ ${"%.2f".format(p.avgBuyPrice)}"
                    }.toTypedArray()

                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("💰 選擇要賣出/減倉的持倉")
                        .setItems(labels) { _, which ->
                            showSellRealPositionForm(positions[which])
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("賣出/減倉", "❌ 加載失敗: ${e.message}")
                }
            }
        }
    }

    /** 賣出/減倉表單（輸入賣出數量和賣出價格） */
    private fun showSellRealPositionForm(position: RealPositionEntity) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
        }

        val nameLabel = position.stockName.ifEmpty { "⚠️待完善" }
        container.addView(android.widget.TextView(ctx).apply {
            text = "當前: $nameLabel(${position.stockCode})\n持有 ${position.quantity}股 均價 ${"%.2f".format(position.avgBuyPrice)}"
            textSize = 11f; setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, 8)
        })

        val qtyEt = android.widget.EditText(ctx).apply {
            hint = "賣出數量 (股，最多 ${position.quantity})"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val priceEt = android.widget.EditText(ctx).apply {
            hint = "賣出價格"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        container.addView(qtyEt)
        container.addView(priceEt)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("💰 賣出/減倉")
            .setView(container)
            .setPositiveButton("確認賣出") { _, _ ->
                val sellQty = qtyEt.text.toString().trim().toIntOrNull() ?: 0
                val sellPrice = priceEt.text.toString().trim().toDoubleOrNull() ?: 0.0

                if (sellQty <= 0) {
                    android.widget.Toast.makeText(ctx, "請輸入有效的賣出數量", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (sellQty > position.quantity) {
                    android.widget.Toast.makeText(ctx, "賣出數量不能超過持有數量(${position.quantity})", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(ctx.applicationContext)
                        val periodType = getQuantType()
                        val tradeDate = java.time.LocalDate.now().toString()

                        if (sellQty >= position.quantity) {
                            // 全部賣出 → 標記清倉
                            db.realPositionDao().markInactive(position.id)
                        } else {
                            // 部分賣出 → 更新剩餘數量（均價沿用原成本基準）
                            val remainQty = position.quantity - sellQty
                            db.realPositionDao().updateQuantity(
                                id = position.id,
                                quantity = remainQty,
                                avgBuyPrice = position.avgBuyPrice
                            )
                        }

                        // 記錄賣出信息到 TTradeRecordEntity（復用現有表，tradeType="REAL_SELL"）
                        val profit = (sellPrice - position.avgBuyPrice) * sellQty
                        val profitPct = if (position.avgBuyPrice > 0)
                            (sellPrice - position.avgBuyPrice) / position.avgBuyPrice * 100 else 0.0
                        db.tTradeRecordDao().insert(
                            TTradeRecordEntity(
                                stockCode = position.stockCode,
                                stockName = position.stockName,
                                tradeDate = tradeDate,
                                tradeType = "REAL_SELL",
                                quantity = sellQty,
                                price = sellPrice,
                                pairedPrice = position.avgBuyPrice,
                                profit = profit,
                                profitPct = profitPct,
                                status = "CLOSED",
                                periodType = periodType,
                                basePositionQty = position.quantity
                            )
                        )
                        Log.i(TAG, "💰 真倉賣出記錄: ${position.stockCode} ${sellQty}股 @${sellPrice} 盈虧${"%.2f".format(profit)}")

                        withContext(Dispatchers.Main) {
                            val msg = if (sellQty >= position.quantity)
                                "✅ 已清倉 ${position.stockName.ifEmpty { position.stockCode }}"
                            else
                                "✅ 已減倉 ${position.stockName.ifEmpty { position.stockCode }} 剩餘${position.quantity - sellQty}股"
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                            statusTv.text = msg
                            refreshPositions()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "❌ 賣出失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 對真實持倉生成做T信號 */
    private fun showRealPositionTSignals() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val positions = db.realPositionDao().getAllActive()

                if (positions.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showDialog("真實持倉做T", "暫無真實持倉\n\n請先添加真實持倉")
                    }
                    return@launch
                }

                val tEngine = TTradeEngine(requireContext().applicationContext)
                val allSignals = mutableListOf<TTradeSignal>()

                for (pos in positions) {
                    try {
                        val signals = tEngine.generateSignals(
                            pos.stockCode,
                            pos.quantity,
                            "RealPosition"
                        )
                        allSignals.addAll(signals)
                    } catch (_: Exception) {}
                }

                // 保存推薦記錄（自動去重）
                if (allSignals.isNotEmpty()) {
                    tEngine.saveRecommendations(allSignals, "REAL")
                }

                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("真實持倉做T建議")
                    sb.appendLine("持倉 ${positions.size} 只 | 信號 ${allSignals.size} 個")
                    sb.appendLine("─".repeat(50))

                    if (allSignals.isEmpty()) {
                        sb.appendLine()
                        sb.appendLine("暫無做T信號")
                        sb.appendLine()
                        sb.appendLine("做T條件：")
                        sb.appendLine("• 股價接近支撐位 → 做T買入")
                        sb.appendLine("• 股價接近阻力位 → 反T賣出")
                        sb.appendLine("• 需有底倉才能做T")
                        sb.appendLine()
                        sb.appendLine("💡 提示：做T信號基於技術分析，")
                        sb.appendLine("請在券商APP手動執行後記錄結果")
                    } else {
                        for (sig in allSignals) {
                            val typeLabel = when (sig.signalType) {
                                TTradeType.T_BUY -> "🟢 做T買入"
                                TTradeType.T_SELL -> "🔴 做T賣出(配對)"
                                TTradeType.RT_SELL -> "🟡 反T賣出"
                                TTradeType.RT_BUY -> "🔵 反T買回(配對)"
                            }
                            sb.appendLine("▸ $typeLabel ${sig.stockName}(${sig.stockCode})")
                            sb.appendLine("  數量: ${sig.quantity}股 | 建議價: ${"%.2f".format(sig.suggestedPrice)} → 目標: ${"%.2f".format(sig.targetPrice)}")
                            sb.appendLine("  原因: ${sig.reason}")
                            sb.appendLine()
                        }
                        sb.appendLine("─".repeat(50))
                        sb.appendLine("💡 請在券商APP手動執行以上建議")
                        sb.appendLine("💡 執行後可通過「做T統計」記錄結果")
                    }

                    showDialog("真實持倉做T建議", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("真實持倉做T", "❌ 加載失敗: ${e.message}")
                }
            }
        }
    }

    /** 真實持倉做T統計 */
    private fun showRealPositionTStats() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val periodType = getQuantType()
                val totalProfit = db.tTradeRecordDao().getTotalProfit(periodType) ?: 0.0
                val winCount = db.tTradeRecordDao().getWinCount(periodType)
                val totalClosed = db.tTradeRecordDao().getTotalClosedCount(periodType)
                val openCount = db.tTradeRecordDao().getOpenCount(periodType)
                val winRate = if (totalClosed > 0) winCount * 100.0 / totalClosed else 0.0

                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("📊 做T統計 ($periodType)")
                    sb.appendLine("─".repeat(40))
                    sb.appendLine("💰 總盈虧: ${"%.2f".format(totalProfit)}")
                    sb.appendLine("📈 勝率: ${"%.1f".format(winRate)}% ($winCount/$totalClosed)")
                    sb.appendLine("✅ 已完成: $totalClosed 筆")
                    sb.appendLine("⏳ 未平倉: $openCount 筆")
                    sb.appendLine()
                    sb.appendLine("💡 做T統計包含模擬持倉和真實持倉的做T記錄")
                    showDialog("做T統計", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("做T統計", "❌ 加載失敗: ${e.message}")
                }
            }
        }
    }

    /** 自動檢測真實持倉的做T機會並在狀態欄通知（refreshPositions 渲染完成後調用） */
    private fun checkRealPositionTSignals() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val positions = db.realPositionDao().getAllActive()
                if (positions.isEmpty()) return@launch

                val tEngine = TTradeEngine(requireContext().applicationContext)
                val allSignals = mutableListOf<TTradeSignal>()
                val details = mutableListOf<String>()

                for (pos in positions) {
                    try {
                        val signals = tEngine.generateSignals(
                            pos.stockCode, pos.quantity, "RealPosition"
                        )
                        if (signals.isNotEmpty()) {
                            allSignals.addAll(signals)
                            for (sig in signals) {
                                val typeLabel = when (sig.signalType) {
                                    TTradeType.T_BUY -> "做T買入"
                                    TTradeType.T_SELL -> "做T賣出"
                                    TTradeType.RT_SELL -> "反T賣出"
                                    TTradeType.RT_BUY -> "反T買回"
                                }
                                details.add(
                                    "${sig.stockName}(${sig.stockCode}) $typeLabel " +
                                    "建議價${"%.2f".format(sig.suggestedPrice)} → 目標${"%.2f".format(sig.targetPrice)}"
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 保存推薦記錄（自動去重）
                if (allSignals.isNotEmpty()) {
                    tEngine.saveRecommendations(allSignals, "REAL")
                }

                // 獲取今日待處理推薦數
                val pendingCount = tEngine.getRecommendationStats().todayPending

                val totalSignals = allSignals.size
                withContext(Dispatchers.Main) {
                    // 更新做T按鈕顯示待處理推薦數
                    tTradeBtn.text = if (pendingCount > 0) "做T($pendingCount)" else "做T"

                    if (totalSignals > 0) {
                        Log.i(TAG, "真倉做T信號: 共 $totalSignals 個\n${details.joinToString("\n")}")
                        statusTv.text = "真倉有 $totalSignals 個做T信號"
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════
    // 做 T（T+0 日內交易）
    // ═══════════════════════════════════════════════════

    /** 顯示做 T 面板 */
    protected open fun showTTradeMenu() {
        val periodType = getQuantType()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val tEngine = TTradeEngine(requireContext())

                // 獲取當前週期持倉
                val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
                    .filter { (it.status == "BUYING" || it.status == "PENDING") && it.orderType == periodType }

                // 為模擬持倉生成做T信號
                val allSignals = mutableListOf<TTradeSignal>()
                for (order in holdingOrders) {
                    val signals = tEngine.generateSignals(order.stockCode, order.quantity, periodType)
                    allSignals.addAll(signals)
                }

                // 同時檢查真實持倉的做T信號
                val realPositions = db.realPositionDao().getAllActive()
                for (pos in realPositions) {
                    if (pos.quantity <= 0) continue
                    val realSignals = tEngine.generateSignals(pos.stockCode, pos.quantity, "RealPosition")
                    allSignals.addAll(realSignals)
                }

                // 獲取做T統計
                val stats = tEngine.getTTradeStats(periodType)

                // 獲取今日推薦（後台監控生成的）
                val todayRecommendations = tEngine.getTodayRecommendations()

                // 獲取推薦歷史（近7天）
                val history = tEngine.getRecommendationHistory(7)

                // 獲取推薦統計
                val recStats = tEngine.getRecommendationStats()

                // 獲取今日做T統計摘要（含虛擬成功率）
                val today = java.time.LocalDate.now().toString()
                val dailySummary = tEngine.getDailySummary(today, periodType)

                withContext(Dispatchers.Main) {
                    // 更新做T按鈕顯示待處理推薦數
                    tTradeBtn.text = if (recStats.todayPending > 0) "做T(${recStats.todayPending})" else "做T"
                    showTTradeDialog(allSignals, stats, periodType, todayRecommendations, history, recStats, dailySummary)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(requireContext(), "做T面板加載失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 顯示做T交易對話框（含推薦歷史和每日統計） */
    private fun showTTradeDialog(
        signals: List<TTradeSignal>,
        stats: TTradeStats,
        periodType: String,
        todayRecommendations: List<TTradeRecommendationEntity>,
        history: List<TTradeRecommendationEntity>,
        recStats: RecommendationStats,
        dailySummary: DailyTSummary
    ) {
        val dialog = android.app.Dialog(requireContext())
        dialog.setTitle("做T面板 — $periodType")
        val scrollView = android.widget.ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // ── 做T統計區 ──
        val winRateStr = "%.1f%%".format(stats.winRate)
        val profitStr = "%.2f".format(stats.totalProfit)
        container.addView(TextView(requireContext()).apply {
            text = "做T統計: 總盈虧 $profitStr | 勝率 $winRateStr | 已完成 ${stats.totalTrades} 筆 | 未平倉 ${stats.openCount} 筆"
            textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 4)
        })

        // 推薦統計
        container.addView(TextView(requireContext()).apply {
            text = "推薦統計: 今日待處理 ${recStats.todayPending} 條 | 近7天已執行 ${recStats.weekExecuted} 條"
            textSize = 10f; setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, 8)
        })

        // ── 今日做T統計摘要 ──
        if (dailySummary.totalRecommendations > 0) {
            container.addView(TextView(requireContext()).apply {
                text = "今日做T統計: 推薦 ${dailySummary.totalRecommendations} 條 | " +
                       "目標觸及 ${dailySummary.targetHitCount} 條 | " +
                       "虛擬成功率 ${"%.1f".format(dailySummary.virtualSuccessRate)}% | " +
                       "已執行 ${dailySummary.executedCount} 條"
                textSize = 10f
                setTextColor(Color.parseColor(if (dailySummary.virtualSuccessRate >= 50) "#2E7D32" else "#C62828"))
                setPadding(0, 0, 0, 8)
            })
        }

        // ── 當前做T信號（即時生成的） ──
        container.addView(TextView(requireContext()).apply {
            text = if (signals.isEmpty()) "即時做T信號: 無" else "即時做T信號 (${signals.size} 個):"
            textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 8, 0, 4)
        })

        if (signals.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "暫無做T信號\n做T條件：股價接近支撐位→做T買入 / 接近阻力位→反T賣出"
                textSize = 10f; setTextColor(Color.parseColor("#999999"))
                setPadding(0, 4, 0, 8)
            })
        } else {
            for (signal in signals) {
                val signalColor = when (signal.signalType) {
                    TTradeType.T_BUY, TTradeType.T_SELL -> Color.parseColor("#E53935")
                    TTradeType.RT_SELL, TTradeType.RT_BUY -> Color.parseColor("#43A047")
                }
                val signalCard = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 8, 12, 8)
                    setBackgroundColor(Color.parseColor("#F5F5F5"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = 8
                    }
                }
                signalCard.addView(TextView(requireContext()).apply {
                    text = "${signal.stockName} (${signal.stockCode})\n" +
                           "操作: ${signal.signalType.label} ${signal.signalType.desc}\n" +
                           "價格: ${"%.2f".format(signal.suggestedPrice)} → 目標 ${"%.2f".format(signal.targetPrice)}\n" +
                           "數量: ${signal.quantity}股 | 預期收益: ${"%.2f%%".format(signal.expectedProfitPct)}\n" +
                           "原因: ${signal.reason}"
                    textSize = 10f; setTextColor(Color.parseColor("#444444"))
                    setLineSpacing(2f, 1f)
                })
                // 執行按鈕
                val btnRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 0)
                }
                btnRow.addView(Button(requireContext()).apply {
                    text = "執行 ${signal.signalType.label}"
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(signalColor)
                    setPadding(4, 1, 4, 1)
                    setMinWidth(0); setMinimumWidth(0)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(22)).apply {
                        marginEnd = 8
                    }
                    setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val tEngine = TTradeEngine(requireContext())
                                tEngine.executeTTrade(signal, periodType)
                                // 同時在推薦表中查找匹配的推薦並標記為已執行
                                val today = java.time.LocalDate.now().toString()
                                val signalTypeStr = when (signal.signalType) {
                                    TTradeType.T_BUY -> "T_BUY"
                                    TTradeType.T_SELL -> "T_SELL"
                                    TTradeType.RT_SELL -> "RT_SELL"
                                    TTradeType.RT_BUY -> "RT_BUY"
                                }
                                val matching = todayRecommendations.find {
                                    it.stockCode == signal.stockCode && it.signalType == signalTypeStr && it.status == "PENDING"
                                }
                                if (matching != null) {
                                    tEngine.markRecommendationExecuted(matching.id, signal.suggestedPrice)
                                }
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(requireContext(), "${signal.signalType.label} 已執行", android.widget.Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                    showTTradeMenu()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(requireContext(), "執行失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                })
                signalCard.addView(btnRow)
                container.addView(signalCard)
            }
        }

        // ── 今日推薦（後台監控生成的） ──
        val pendingRecs = todayRecommendations.filter { it.status == "PENDING" }
        if (pendingRecs.isNotEmpty()) {
            // 分隔線
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
                setBackgroundColor(Color.parseColor("#DDDDDD"))
                setPadding(0, 8, 0, 8)
            })

            container.addView(TextView(requireContext()).apply {
                text = "後台推薦 (${pendingRecs.size} 條待處理):"
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#1565C0"))
                setPadding(0, 4, 0, 4)
            })

            for (rec in pendingRecs) {
                val recCard = createRecommendationCard(rec, dialog)
                container.addView(recCard)
            }
        }

        // ── 推薦歷史（近7天，含已處理） ──
        val processedHistory = history.filter { it.status != "PENDING" }.take(20)
        if (processedHistory.isNotEmpty()) {
            // 分隔線
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
                setBackgroundColor(Color.parseColor("#DDDDDD"))
                setPadding(0, 8, 0, 8)
            })

            container.addView(TextView(requireContext()).apply {
                text = "推薦歷史 (近7天 ${processedHistory.size} 條):"
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#666666"))
                setPadding(0, 4, 0, 4)
            })

            for (rec in processedHistory) {
                val statusLabel = when (rec.status) {
                    "EXECUTED" -> "已執行"
                    "IGNORED" -> "已忽略"
                    "EXPIRED" -> "已過期"
                    "TARGET_HIT" -> "目標觸及"
                    "TARGET_MISSED" -> "目標未達"
                    else -> rec.status
                }
                val statusColor = when (rec.status) {
                    "EXECUTED" -> "#43A047"
                    "TARGET_HIT" -> "#2E7D32"
                    "IGNORED" -> "#9E9E9E"
                    "EXPIRED" -> "#FF9800"
                    "TARGET_MISSED" -> "#C62828"
                    else -> "#666666"
                }
                val signalLabel = when (rec.signalType) {
                    "T_BUY" -> "做T買入"
                    "T_SELL" -> "做T賣出"
                    "RT_SELL" -> "反T賣出"
                    "RT_BUY" -> "反T買回"
                    else -> rec.signalType
                }

                val histRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 4, 8, 4)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                histRow.addView(TextView(requireContext()).apply {
                    text = "${rec.tradeDate.takeLast(5)} ${rec.stockName.take(6)} $signalLabel"
                    textSize = 9f; setTextColor(Color.parseColor("#555555"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                histRow.addView(TextView(requireContext()).apply {
                    text = statusLabel
                    textSize = 9f; setTextColor(Color.parseColor(statusColor))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                container.addView(histRow)
            }
        }

        scrollView.addView(container)
        dialog.setContentView(scrollView)
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    /** 創建推薦卡片（含執行/忽略按鈕） */
    private fun createRecommendationCard(
        rec: TTradeRecommendationEntity,
        dialog: android.app.Dialog
    ): LinearLayout {
        val signalLabel = when (rec.signalType) {
            "T_BUY" -> "做T買入"
            "T_SELL" -> "做T賣出"
            "RT_SELL" -> "反T賣出"
            "RT_BUY" -> "反T買回"
            else -> rec.signalType
        }
        val signalColor = when (rec.signalType) {
            "T_BUY", "T_SELL" -> Color.parseColor("#E53935")
            else -> Color.parseColor("#43A047")
        }

        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 6
            }
        }

        card.addView(TextView(requireContext()).apply {
            text = "${rec.stockName} (${rec.stockCode.takeLast(6)})\n" +
                   "操作: $signalLabel\n" +
                   "推薦價: ${"%.2f".format(rec.suggestedPrice)} → 目標 ${"%.2f".format(rec.targetPrice)}\n" +
                   "數量: ${rec.quantity}股 | 預期: ${"%.2f%%".format(rec.expectedProfitPct)}"
            textSize = 10f; setTextColor(Color.parseColor("#333333"))
            setLineSpacing(2f, 1f)
        })

        // 按鈕行
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }

        // 執行按鈕
        btnRow.addView(Button(requireContext()).apply {
            text = "已執行"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(signalColor)
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(22)).apply {
                marginEnd = 8
            }
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val tEngine = TTradeEngine(requireContext())
                        tEngine.markRecommendationExecuted(rec.id, rec.suggestedPrice)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(requireContext(), "已標記為執行", android.widget.Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            showTTradeMenu()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(requireContext(), "操作失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })

        // 忽略按鈕
        btnRow.addView(Button(requireContext()).apply {
            text = "忽略"
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(22))
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val tEngine = TTradeEngine(requireContext())
                        tEngine.markRecommendationIgnored(rec.id)
                        withContext(Dispatchers.Main) {
                            dialog.dismiss()
                            showTTradeMenu()
                        }
                    } catch (_: Exception) {}
                }
            }
        })

        card.addView(btnRow)
        return card
    }

    // ═══════════════════════════════════════════════════
    // 數據管理
    // ═══════════════════════════════════════════════════

    /** 顯示數據菜單（統一版，所有週期共用） */
    protected open fun showDataMenu(anchor: View) {
        val exporter = DataExportImport(requireContext())
        val periodLabel = getQuantType()
        val options = arrayOf(
            "📋 查看交易記錄",
            "📊 查看量化報告",
            "📊 查看精選池",
            "💰 查看持倉詳情",
            "🧠 市場記憶設置",
            "💰 持有收益歷史",
            "📅 月度熱點前瞻",
            "🔥 查看熱門板塊報告",
            "📋 查看策略報告",
            "─ 導出 ─",
            "📤 導出 JSON (全部數據)",
            "📂 查看導出文件列表",
            "📊 數據庫統計信息",
            "─ 清空 ─",
            "🧹 清空持倉",
            "🧹 清空報告",
            "─ 策略優化 ─",
            "📈 回溯測試",
            "🔧 擬合調優",
            "🔄 全周期擬合"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("🗄️ 數據中心 — $periodLabel")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showTradeHistory()
                    1 -> showTradeReportsHistory()
                    2 -> showFinalPool()
                    3 -> loadPositions()
                    4 -> showMarketMemoryDialog()
                    5 -> showHoldingProfitHistory()
                    6 -> showMonthlyForecast()
                    7 -> exportHotSectors()
                    8 -> exportStrategyReport()
                    // 9 = 分隔線
                    10 -> exportToJson(exporter)
                    11 -> showExportFiles(exporter)
                    12 -> showDbStats(exporter)
                    // 13 = 分隔線
                    14 -> confirmAndClearPositions()
                    15 -> confirmAndClearReports()
                    // 16 = 分隔線
                    17 -> onBacktrackClick()
                    18 -> onFittingClick()
                    19 -> runCrossPeriodFitting()
                }
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    // ═══════════════════════════════════════
    // 數據導出/導入（從中線提升到基類，所有週期共用）
    // ═══════════════════════════════════════

    /** 查看量化報告歷史 */
    protected open fun showTradeReportsHistory() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val entities = db.dailyPeriodResultDao().getRecent(100)
                    .filter { it.strategyId != "FINAL_POOL" && it.strategyId != "BACKTRACK" }
                if (entities.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "暫無量化報告記錄", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val codeToName = try { db.stockBasicDao().getAll().associate { it.code to it.name } } catch (_: Exception) { emptyMap() }
                val grouped = entities.groupBy { it.tradeDate }
                val sb = StringBuilder()
                sb.appendLine("📊 量化報告歷史 (共 ${entities.size} 條)")
                sb.appendLine()
                for ((date, items) in grouped.toSortedMap().entries.reversed().take(10)) {
                    sb.appendLine("━━━ $date ━━━")
                    for (item in items) {
                        val top3Json = try { org.json.JSONArray(item.finalTop3Json) } catch (_: Exception) { org.json.JSONArray() }
                        val mainBoardLabel = if (item.mainBoardFilter) " 主板" else ""
                        sb.appendLine("  ${item.strategyName}[${item.periodDays}日]$mainBoardLabel: ${item.stockCount}只信號")
                        if (item.newsStrengthScore > 0) sb.appendLine("    新聞力度:${item.newsStrengthScore} 輪動懲罰:${item.rotationPenalty}")
                        try {
                            val reasonJson = org.json.JSONArray(item.filteredReasonJson)
                            if (reasonJson.length() > 0) {
                                val sampleReason = reasonJson.optJSONObject(0)
                                if (sampleReason != null) sb.appendLine("    ⚠️ 過濾: ${sampleReason.optString("name")}(${sampleReason.optString("reason")}) 等${reasonJson.length()}只")
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
                withContext(Dispatchers.Main) { showDialog("量化報告歷史", sb.toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加載失敗: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** 查看精選池 */
    protected open fun showFinalPool() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val entities = db.dailyPeriodResultDao().getRecent(100)
                    .filter { it.strategyId == "FINAL_POOL" }
                    .sortedByDescending { it.tradeDate }
                if (entities.isEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "暫無精選池記錄", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val latest = entities.first()
                val codes = try { org.json.JSONArray(latest.stockCodesJson) } catch (_: Exception) { org.json.JSONArray() }
                val crossDayJson = try { org.json.JSONArray(latest.finalTop3Json) } catch (_: Exception) { org.json.JSONArray() }
                val sb = StringBuilder()
                sb.appendLine("📋 精選最終池")
                sb.appendLine("交易日: ${latest.tradeDate}")
                sb.appendLine("共 ${latest.stockCount} 只股票輸入AI")
                sb.appendLine()
                sb.appendLine("🔥 跨日聚合命中 Top10:")
                for (i in 0 until crossDayJson.length()) {
                    val obj = crossDayJson.getJSONObject(i)
                    sb.appendLine("  ${obj.optString("code").takeLast(6)}: ${obj.optInt("days")}天命中")
                }
                sb.appendLine()
                sb.appendLine("📊 完整精選池 (${codes.length()} 只):")
                for (i in 0 until minOf(codes.length(), 60)) {
                    sb.appendLine("  ${i+1}. ${codes.optString(i)}")
                }
                if (codes.length() > 60) sb.appendLine("  ... 共 ${codes.length()} 只，僅顯示前60")
                withContext(Dispatchers.Main) { showDialog("精選池_${latest.tradeDate}", sb.toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "載入精選池失敗: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    protected open fun exportToJson(exporter: DataExportImport) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val path = exporter.exportAllToJson()
                withContext(Dispatchers.Main) { showDialog("導出成功", "文件已保存到:\n$path") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "導出失敗: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    protected open fun showExportFiles(exporter: DataExportImport) {
        val files = exporter.getExportFiles()
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), "暫無導出文件", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.appendLine("📂 已導出的文件:")
        sb.appendLine()
        for (f in files) {
            sb.appendLine("${f.name} (${f.length()/1024}KB)")
            sb.appendLine("  修改時間: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))}")
            sb.appendLine()
        }
        showDialog("導出文件列表", sb.toString())
    }

    protected open fun showDbStats(exporter: DataExportImport) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val stats = exporter.getDatabaseStats()
            withContext(Dispatchers.Main) { showDialog("數據庫統計", stats) }
        }
    }

    /** 清空持倉（帶二次確認） */
    protected open fun confirmAndClearPositions() {
        val periodType = getQuantType()
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空持倉")
            .setMessage("確定要清空所有 $periodType 持倉記錄嗎？此操作不可撤銷。")
            .setPositiveButton("確定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val orders = db.strategyTradeOrderDao().getRecent(500)
                            .filter { it.orderType == periodType }
                        for (order in orders) {
                            db.strategyTradeOrderDao().deleteByDate(order.tradeDate)
                        }
                        withContext(Dispatchers.Main) {
                            refreshPositions()
                            statusTv.text = "✅ 已清空 $periodType 持倉"
                            Toast.makeText(requireContext(), "持倉已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失敗: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 清空報告（帶二次確認） */
    protected open fun confirmAndClearReports() {
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空報告")
            .setMessage("確定要清空所有量化報告記錄嗎？此操作不可撤銷。")
            .setPositiveButton("確定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val entities = db.dailyPeriodResultDao().getRecent(1000)
                        for (e in entities) {
                            try { db.dailyPeriodResultDao().deleteByDate(e.tradeDate) } catch (_: Exception) {}
                        }
                        withContext(Dispatchers.Main) {
                            statusTv.text = "✅ 已清空報告"
                            Toast.makeText(requireContext(), "報告已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失敗: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 全周期擬合：依序執行 4 個周期 DAG Pipeline，
     * 合併結果找出被 ≥2 個周期同時選中的股票（高置信度）。
     */
    protected fun runCrossPeriodFitting() {
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 全周期..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "🔄 全周期擬合：啟動 4 個 Pipeline..."

        lifecycleScope.launch(Dispatchers.IO) {
            val tradeDate = try {
                com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().toString()
            } catch (_: Exception) { java.time.LocalDate.now().toString() }
            val today = java.time.LocalDate.now().toString()

            val periods = listOf(
                Triple("ultra_short", "超短線", 30),
                Triple("short_term", "短線", 60),
                Triple("mid_term", "中線", 60),
                Triple("long_term", "長線", 60)
            )

            val periodEnumMap = mapOf(
                "ultra_short" to HoldingPeriod.ULTRA_SHORT,
                "short_term" to HoldingPeriod.SHORT,
                "mid_term" to HoldingPeriod.MID,
                "long_term" to HoldingPeriod.LONG
            )

            val results = mutableMapOf<String, String>()  // period → summary
            var allSuccess = true

            for ((useCaseId, label, importDays) in periods) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "🔄 全周期擬合：$label Pipeline 執行中..."
                }
                try {
                    val strategies = engine?.getEnabledStrategiesByPeriod(
                        periodEnumMap[useCaseId] ?: HoldingPeriod.SHORT
                    ) ?: emptyList()

                    val r = com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor.execute(
                        context = requireContext(),
                        useCaseId = useCaseId,
                        tradeDate = tradeDate,
                        today = today,
                        strategies = strategies,
                        orderType = useCaseId,
                        importDays = importDays,
                        onNodeProgress = { _, nodeName ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                statusTv.text = "🔄 $label: $nodeName..."
                            }
                        }
                    )
                    results[label] = if (r.success) "✅ ${r.ordersCount}筆訂單" else "❌ 失敗"
                    if (!r.success) allSuccess = false
                } catch (e: Exception) {
                    results[label] = "❌ ${e.message?.take(30)}"
                    allSuccess = false
                }
            }

            // 合併：查詢今日所有 BUYING 訂單，按股票分組
            val db = StockDatabase.getInstance(requireContext())
            val todayOrders = db.strategyTradeOrderDao().getByDate(tradeDate)
                .filter { it.status == "BUYING" || it.status == "HOLDING" }

            // 按股票代碼分組，統計被幾個周期選中
            val stockPeriods = todayOrders.groupBy { it.stockCode }
                .mapValues { (_, orders) -> orders.map { it.orderType }.distinct() }

            val multiHit = stockPeriods.filter { it.value.size >= 2 }
                .entries.sortedByDescending { it.value.size }

            // 生成報告
            val report = buildString {
                appendLine("═══ 全周期擬合報告 ═══")
                appendLine("交易日: $tradeDate")
                appendLine()
                appendLine("── 各周期執行結果 ──")
                results.forEach { (period, summary) -> appendLine("$period: $summary") }
                appendLine()

                if (multiHit.isNotEmpty()) {
                    appendLine("── 🎯 多周期共振（≥2 周期選中）──")
                    multiHit.forEach { (code, periods) ->
                        val name = todayOrders.firstOrNull { it.stockCode == code }?.stockName ?: code
                        appendLine("★ $name ($code) ← ${periods.joinToString("+")}")
                    }
                    appendLine()
                    appendLine("💡 多周期共振股票置信度更高，建議優先關注")
                } else {
                    appendLine("── 無多周期共振股票 ──")
                    appendLine("各周期選股無交集，市場分歧較大")
                }

                appendLine()
                appendLine("── 各周期選股明細 ──")
                stockPeriods.entries.sortedByDescending { it.value.size }.forEach { (code, periods) ->
                    val name = todayOrders.firstOrNull { it.stockCode == code }?.stockName ?: code
                    val score = todayOrders.firstOrNull { it.stockCode == code }?.scoreAtBuy ?: 0
                    appendLine("$name ($code) | 分數:$score | ${periods.joinToString("+")}")
                }
            }

            withContext(Dispatchers.Main) {
                showDialog("全周期擬合報告", report)
                statusTv.text = if (allSuccess) "✅ 全周期擬合完成" else "⚠ 部分周期失敗"
                buildBtn.isEnabled = true; buildBtn.text = "▶ 建倉"
                progressBar.visibility = View.GONE
                refreshPositions()
            }
        }
    }

    /** 清除數據 */
    protected fun clearData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()
                val orders = db.strategyTradeOrderDao().getRecent(500)
                    .filter { it.orderType == quantType }
                val dates = orders.map { it.tradeDate }.distinct()
                for (date in dates) {
                    db.strategyTradeOrderDao().deleteByDate(date)
                }
                withContext(Dispatchers.Main) {
                    refreshPositions()
                    statusTv.text = "✅ 已清除 ${orders.size} 條 $quantType 數據"
                    Toast.makeText(requireContext(), "已清除 ${orders.size} 條記錄", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 清除失敗: ${e.message?.take(40)}"
                }
            }
        }
    }

    /** 按日期清除數據 */
    protected fun clearDataByDate(date: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                StockDatabase.getInstance(requireContext()).strategyTradeOrderDao().deleteByDate(date)
                withContext(Dispatchers.Main) {
                    refreshPositions()
                    statusTv.text = "✅ 已清除 $date 的 ${getQuantType()} 數據"
                    Toast.makeText(requireContext(), "已清除 $date 的數據", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 清除失敗: ${e.message?.take(40)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 持倉管理（共用）
    // ═══════════════════════════════════════════════════

    /**
     * 動態補全持倉訂單中缺失的股票名稱
     *
     * 任何週期（超短/短/中/長線）選到的股票，如果訂單中 stockName 為空，
     * 通過 StockNameResolver 統一補全（stock_basics → daily_snapshot → 新浪API）。
     * 補全後持久化到 strategy_trade_orders 表，避免重複查詢。
     *
     * @return 補全名稱後的訂單列表（與輸入列表同序，但 stockName 可能已更新）
     */
    private suspend fun ensureStockNames(
        db: StockDatabase,
        orders: List<StrategyTradeOrderEntity>
    ): List<StrategyTradeOrderEntity> {
        val missingNameOrders = orders.filter { it.stockName.isBlank() }
        if (missingNameOrders.isEmpty()) return orders

        val missingCodes = missingNameOrders.map { it.stockCode }.distinct()
        Log.i("QuantFragmentBase", "🔧 ensureStockNames: ${missingCodes.size} 只股票缺少名稱，開始補全")

        // 統一調用 StockNameResolver 批量解析
        val nameMap = com.chin.stockanalysis.stock.database.StockNameResolver
            .resolveBatch(requireContext(), missingCodes)
        Log.i("QuantFragmentBase", "  StockNameResolver 解析命中: ${nameMap.size}/${missingCodes.size}")

        // 持久化補全結果到數據庫
        var fixedCount = 0
        for (order in missingNameOrders) {
            val name = nameMap[order.stockCode]
            if (!name.isNullOrBlank()) {
                db.strategyTradeOrderDao().updateStockName(order.id, name)
                fixedCount++
            }
        }
        Log.i("QuantFragmentBase", "  ✅ ensureStockNames 完成: 補全 $fixedCount/${missingNameOrders.size} 筆訂單名稱")

        // 返回更新後的列表（用 nameMap 覆蓋空名稱）
        return if (fixedCount > 0) {
            orders.map { order ->
                if (order.stockName.isBlank() && nameMap.containsKey(order.stockCode)) {
                    order.copy(stockName = nameMap[order.stockCode]!!)
                } else {
                    order
                }
            }
        } else {
            orders
        }
    }

    /** 刷新持倉 */
    open fun refreshPositions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()
                val rawOrders = db.strategyTradeOrderDao().getRecent(100)
                    .filter {
                        it.orderType == quantType &&
                        (it.status == "BUYING" || it.status == "PENDING")
                    }
                    .sortedByDescending { it.tradeDate }

                if (rawOrders.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        positionContainer.removeAllViews()
                        positionContainer.addView(TextView(requireContext()).apply {
                            text = "📌 暫無持倉記錄"
                            textSize = 11f
                            setTextColor(Color.parseColor("#999999"))
                            setPadding(0, 8, 0, 8)
                        })
                    }
                    return@launch
                }

                // 動態補全缺失的股票名稱（任何週期通用）
                val orders = ensureStockNames(db, rawOrders)

                val minTradeDate = orders.minByOrNull { it.tradeDate }?.tradeDate ?: browsingDate.format(DATE_FMT)
                val allDates = db.dailySnapshotDao().getAvailableDates(20)
                val dates = allDates
                    .filter { it >= minTradeDate && it <= browsingDate.format(DATE_FMT) }
                    .filter { dateStr ->
                        // 自動排除周六日和節假日
                        try {
                            val d = java.time.LocalDate.parse(dateStr)
                            com.chin.stockanalysis.ui.TradingDayPickerView.isTradingDay(d)
                        } catch (_: Exception) { false }
                    }
                    .sorted().takeLast(10)

                val priceMap = mutableMapOf<String, MutableMap<String, Double>>()
                for (date in dates) {
                    val snaps = db.dailySnapshotDao().getByDate(date)
                    for (snap in snaps) {
                        priceMap.getOrPut(snap.code) { mutableMapOf() }[date] = snap.close
                    }
                }

                // 預加載真實持倉數據（IO 線程），傳入 renderPositions 一併渲染
                val realPositions = db.realPositionDao().getAllActive()
                withContext(Dispatchers.Main) {
                    renderPositions(orders, dates, priceMap, realPositions)
                }
                // UI 渲染完成後，異步檢查真倉做T信號並通知
                checkRealPositionTSignals()
            } catch (_: Exception) {}
        }
    }

    /** 渲染持倉列表（多日表格視圖，可被子類覆寫） */
    protected open fun renderPositions(
        orders: List<StrategyTradeOrderEntity>,
        dates: List<String>,
        priceMap: Map<String, Map<String, Double>>,
        realPositions: List<RealPositionEntity> = emptyList()
    ) {
        positionContainer.removeAllViews()

        if (orders.isEmpty()) {
            positionContainer.addView(TextView(requireContext()).apply {
                text = "📌 暫無持倉記錄"
                textSize = 11f; setTextColor(Color.parseColor("#999999")); setPadding(0, 8, 0, 8)
            })
            return
        }

        // 計算總盈虧
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

        // 固化各週期持有收益到數據庫（異步，不阻塞 UI）
        // 每個週期獨立保存：UltraShortQuant / ShortTermQuant / MidTermQuant / LongTermQuant
        val quantType = getQuantType()
        val tradeDateStr = browsingDate.format(DATE_FMT)
        val stockCodes = orders.joinToString(",") { it.stockCode }
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entity = com.chin.stockanalysis.strategy.trade.PeriodHoldingProfitEntity(
                    periodType = quantType,
                    tradeDate = tradeDateStr,
                    holdingCount = orders.size,
                    totalCost = totalCost,
                    totalValue = totalValue,
                    totalPnl = totalPnl,
                    totalPnlPct = totalPnlPct,
                    stockCodes = stockCodes
                )
                StockDatabase.getInstance(appCtx)
                    .periodHoldingProfitDao().insert(entity)
                android.util.Log.i("QuantFragmentBase", "✅ $quantType 持有收益已固化: $tradeDateStr ${orders.size}只 盈虧¥${"%.0f".format(totalPnl)}")
            } catch (e: Exception) {
                android.util.Log.w("QuantFragmentBase", "固化持有收益失敗: ${e.message}")
            }
        }

        // 標題行
        val titlePrefix = if (positionTitlePrefix.isNotEmpty()) positionTitlePrefix else quantType
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 2, 0, 2)
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = "📌 ${titlePrefix}持倉"
            textSize = 12f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = "總持倉 ${orders.size} 只  |  "
            textSize = 10f; setTextColor(Color.parseColor("#666666")); gravity = Gravity.END
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = "總盈虧 $pnlStr"
            textSize = 10f; setTextColor(Color.parseColor(pnlColor)); gravity = Gravity.END
        })
        positionContainer.addView(titleRow)

        // 多日漲跌表格
        val scroll = HorizontalScrollView(requireContext())
        val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 4)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        for (header in listOf("股票", "建倉日", "成本"))
            headerRow.addView(createCell(header, 60, "#666666", 10f, bold = true))
        headerRow.addView(createCell("持倉", 45, "#666666", 9f, bold = true))
        if (showMultiDayPrices) {
            for (date in dates) {
                val label = date.takeLast(5)
                headerRow.addView(createCell(label, 72, "#666666", 10f, bold = true))
            }
        }
        headerRow.addView(createCell("賣出", 50, "#666666", 9f, bold = true))
        table.addView(headerRow)

        for (order in orders) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 2)
            }
            // 股票名/代碼
            val nameCell = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dpToPx(60), LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
            }
            nameCell.addView(TextView(requireContext()).apply {
                text = order.stockName.take(6); textSize = 11f
                setTextColor(Color.parseColor("#222222")); gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })
            nameCell.addView(TextView(requireContext()).apply {
                text = order.stockCode.takeLast(6); textSize = 8f
                setTextColor(Color.parseColor("#AAAAAA")); gravity = Gravity.CENTER
            })
            // 點擊股票名稱直接跳轉到詳情頁
            nameCell.setOnClickListener {
                com.chin.stockanalysis.ui.StockDetailNavigator.navigateFromFragment(
                    this@QuantFragmentBase,
                    order.stockCode,
                    order.stockName,
                    price = order.buyPrice
                )
            }
            nameCell.isClickable = true
            nameCell.foreground = android.graphics.drawable.GradientDrawable().apply {
                setColor(0)
                setCornerRadius(8f)
            }
            row.addView(nameCell)

            // 建倉日
            row.addView(createCell(order.tradeDate.takeLast(5), 60, "#333333", 10f))
            // 成本
            row.addView(createCell("¥${"%.2f".format(order.buyPrice)}", 60, "#333333", 10f))
            // 持倉量
            row.addView(createCell("${order.quantity}", 45, "#1565C0", 9f))

            // 多日價格列
            if (showMultiDayPrices) {
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
                    row.addView(TextView(requireContext()).apply {
                        text = cellText; textSize = 9f
                        setTextColor(Color.parseColor(cellColor)); gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dpToPx(72), LinearLayout.LayoutParams.WRAP_CONTENT)
                        setPadding(2, 4, 2, 4); setLineSpacing(2f, 1f)
                    })
                }
            }

            // 賣出按鈕
            val lastPrice = priceMap[order.stockCode]?.get(dates.lastOrNull())
            val sellBtn = Button(requireContext()).apply {
                text = "賣"; textSize = 9f; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#C62828"))
                layoutParams = LinearLayout.LayoutParams(dpToPx(50), dpToPx(28)); setPadding(2, 0, 2, 0)
                isEnabled = lastPrice != null
                setOnClickListener { showSellConfirmDialog(order, lastPrice ?: order.buyPrice) }
            }
            row.addView(sellBtn)
            table.addView(row)
        }
        scroll.addView(table)
        val verticalScroll = ScrollView(requireContext())
        verticalScroll.addView(scroll)
        positionContainer.addView(verticalScroll)

        // ════════ 真實持倉顯示區域（模擬持倉表格之後） ════════
        if (realPositions.isNotEmpty()) {
            // 分隔線
            positionContainer.addView(View(requireContext()).apply {
                setBackgroundColor(Color.parseColor("#D0D0D0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
                ).apply { setMargins(0, 10, 0, 4) }
            })

            // 計算真實持倉總成本
            var realTotalCost = 0.0
            for (rp in realPositions) {
                realTotalCost += rp.avgBuyPrice * rp.quantity
            }

            // 真實持倉標題行：「👤 真實持倉 (N只)」+ 總成本
            val realTitleRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 2, 0, 2)
            }
            realTitleRow.addView(TextView(requireContext()).apply {
                text = "👤 真實持倉 (${realPositions.size} 只)"
                textSize = 12f; setTextColor(Color.parseColor("#1A1A2E"))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            realTitleRow.addView(TextView(requireContext()).apply {
                text = "總成本 ¥${"%.0f".format(realTotalCost)}"
                textSize = 10f; setTextColor(Color.parseColor("#1565C0")); gravity = Gravity.END
            })
            positionContainer.addView(realTitleRow)

            // 真實持倉表格
            val realTable = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            val realHeader = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 4)
                setBackgroundColor(Color.parseColor("#EEEEEE"))
            }
            for (h in listOf("股票", "數量", "均價", "成本")) {
                realHeader.addView(createCell(h, 60, "#666666", 10f, bold = true))
            }
            realTable.addView(realHeader)

            for (rp in realPositions) {
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 2)
                }
                val needsComplete = rp.quantity <= 0 || rp.avgBuyPrice <= 0.0

                // 股票名/代碼（點擊可跳轉詳情頁）
                val nameCell = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(dpToPx(60), LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER
                }
                nameCell.addView(TextView(requireContext()).apply {
                    text = rp.stockName.take(6).ifEmpty { rp.stockCode.takeLast(6) }
                    textSize = 11f; setTextColor(Color.parseColor("#222222")); gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                })
                nameCell.addView(TextView(requireContext()).apply {
                    text = rp.stockCode.takeLast(6); textSize = 8f
                    setTextColor(Color.parseColor("#AAAAAA")); gravity = Gravity.CENTER
                })
                nameCell.setOnClickListener {
                    com.chin.stockanalysis.ui.StockDetailNavigator.navigateFromFragment(
                        this@QuantFragmentBase,
                        rp.stockCode,
                        rp.stockName.ifEmpty { rp.stockCode },
                        price = rp.avgBuyPrice
                    )
                }
                nameCell.isClickable = true
                nameCell.foreground = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0); setCornerRadius(8f)
                }
                row.addView(nameCell)

                // 數量
                row.addView(createCell(
                    if (rp.quantity <= 0) "⚠️待完善" else "${rp.quantity}",
                    60, if (needsComplete) "#E65100" else "#1565C0", 10f
                ))
                // 均價
                row.addView(createCell(
                    if (rp.avgBuyPrice <= 0.0) "⚠️待完善" else "¥${"%.2f".format(rp.avgBuyPrice)}",
                    60, if (needsComplete) "#E65100" else "#333333", 10f
                ))
                // 成本
                val cost = rp.avgBuyPrice * rp.quantity
                row.addView(createCell(
                    if (needsComplete) "—" else "¥${"%.0f".format(cost)}",
                    60, "#333333", 10f
                ))
                realTable.addView(row)
            }
            positionContainer.addView(realTable)
        }
    }

    /** 顯示單筆賣出確認對話框 */
    protected fun showSellConfirmDialog(order: StrategyTradeOrderEntity, currentPrice: Double) {
        val profitPct = if (order.buyPrice > 0) (currentPrice - order.buyPrice) / order.buyPrice * 100 else 0.0
        val profitStr = if (profitPct >= 0) "+${"%.2f".format(profitPct)}%" else "${"%.2f".format(profitPct)}%"

        val message = """
            股票: ${order.stockName} (${order.stockCode})
            買入價: ¥${"%.2f".format(order.buyPrice)}
            當前價: ¥${"%.2f".format(currentPrice)}
            盈虧: $profitStr
            數量: ${order.quantity} 股
            
            確認賣出？
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("💰 確認賣出")
            .setMessage(message)
            .setPositiveButton("確認賣出") { _, _ -> executeSingleSell(order, currentPrice) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 執行單筆賣出 */
    protected fun executeSingleSell(order: StrategyTradeOrderEntity, sellPrice: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val profitPct = (sellPrice - order.buyPrice) / order.buyPrice * 100
                db.strategyTradeOrderDao().updateSellInfo(
                    id = order.id, status = "SOLD", sellPrice = sellPrice,
                    sellTime = LocalDate.now().toString() + " " + java.time.LocalTime.now().toString().take(8),
                    profitPct = profitPct
                )
                withContext(Dispatchers.Main) {
                    statusTv.text = "✅ 已賣出 ${order.stockName} @ ¥${"%.2f".format(sellPrice)} (${"%.2f".format(profitPct)}%)"
                    Toast.makeText(requireContext(), "賣出成功: ${order.stockName}", Toast.LENGTH_SHORT).show()
                    refreshPositions()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 賣出失敗: ${e.message?.take(40)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 通用對話框與導出
    // ═══════════════════════════════════════════════════

    /** 顯示通用對話框 */
    protected fun showDialog(title: String, content: String) {
        val sv = ScrollView(requireContext())
        sv.addView(TextView(requireContext()).apply {
            text = content; textSize = 10f; setTextColor(Color.parseColor("#333333"))
            setPadding(16, 12, 16, 12); setLineSpacing(2f, 1.1f); setTypeface(Typeface.MONOSPACE)
        })
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("關閉", null)
            .create()
            .apply {
                show()
                window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            }
    }

    /** 顯示各週期持有收益歷史 */
    protected fun showHoldingProfitHistory() {
        val appCtx = requireContext().applicationContext
        val currentPeriod = getQuantType()
        val periodNames = mapOf(
            "UltraShortQuant" to "超短線",
            "ShortTermQuant" to "短線",
            "MidTermQuant" to "中線",
            "LongTermQuant" to "長線"
        )
        val currentName = periodNames[currentPeriod] ?: currentPeriod

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(appCtx)
                // 查詢當前週期最近 30 天的收益記錄
                val records = db.periodHoldingProfitDao().getByPeriodType(currentPeriod, 30)
                // 同時查詢所有週期的最新記錄
                val latestAll = db.periodHoldingProfitDao().getLatestAllPeriods()
                // 查詢所有週期的歷史記錄（用於計算日變化）
                val allPeriodLatest = mutableMapOf<String, List<PeriodHoldingProfitEntity>>()
                for (p in periodNames.keys) {
                    allPeriodLatest[p] = db.periodHoldingProfitDao().getByPeriodType(p, 2)
                }

                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("💰 持有收益歷史 — $currentName")
                    sb.appendLine("━".repeat(40))
                    sb.appendLine()

                    // 各週期最新收益概覽（含日變化）
                    sb.appendLine("📊 各週期最新持倉收益：")
                    sb.appendLine()
                    var grandPnl = 0.0
                    var grandCost = 0.0
                    for (entity in latestAll.sortedBy { it.periodType }) {
                        val name = periodNames[entity.periodType] ?: entity.periodType
                        val pnlStr = if (entity.totalPnl >= 0) "+" else ""
                        val color = if (entity.totalPnl >= 0) "🔴" else "🟢"
                        // 計算與前一交易日的變化
                        val prevRecords = allPeriodLatest[entity.periodType] ?: emptyList()
                        val changeStr = if (prevRecords.size >= 2) {
                            val prev = prevRecords[1]
                            val diff = entity.totalPnl - prev.totalPnl
                            val arrow = if (diff >= 0) "↑" else "↓"
                            " | 日變化$arrow${"%.0f".format(kotlin.math.abs(diff))}"
                        } else ""
                        sb.appendLine("  $color $name: ${pnlStr}¥${"%.0f".format(entity.totalPnl)} (${"%.2f".format(entity.totalPnlPct)}%) | ${entity.holdingCount}只 | ${entity.tradeDate}$changeStr")
                        grandPnl += entity.totalPnl
                        grandCost += entity.totalCost
                    }
                    if (latestAll.isNotEmpty()) {
                        val grandPct = if (grandCost > 0) grandPnl / grandCost * 100 else 0.0
                        val gStr = if (grandPnl >= 0) "+" else ""
                        sb.appendLine("  ────────────────────────────────")
                        sb.appendLine("  📊 合計: ${gStr}¥${"%.0f".format(grandPnl)} (${"%.2f".format(grandPct)}%)")
                    }
                    sb.appendLine()

                    // 當前週期歷史走勢
                    sb.appendLine("📈 $currentName 最近收益走勢：")
                    sb.appendLine()
                    if (records.isEmpty()) {
                        sb.appendLine("  暫無歷史記錄")
                    } else {
                        sb.appendLine("  日期         盈虧金額       盈虧%    持倉數  日變化")
                        sb.appendLine("  " + "─".repeat(46))
                        for ((idx, r) in records.withIndex()) {
                            val pnlStr = if (r.totalPnl >= 0) "+" else ""
                            // 計算與前一條記錄的變化
                            val dayChange = if (idx < records.size - 1) {
                                val prev = records[idx + 1]
                                val diff = r.totalPnl - prev.totalPnl
                                val arrow = if (diff >= 0) "↑" else "↓"
                                "$arrow${"%.0f".format(kotlin.math.abs(diff))}"
                            } else "—"
                            sb.appendLine("  ${r.tradeDate}  ${pnlStr}¥${"%8.0f".format(r.totalPnl)}  ${pnlStr}${"%6.2f".format(r.totalPnlPct)}%  ${r.holdingCount}只   $dayChange")
                        }
                    }

                    showDialog("持有收益歷史", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "加載收益歷史失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 顯示月度熱點前瞻報告 */
    protected fun showMonthlyForecast() {
        statusTv.text = "📅 正在生成月度熱點前瞻..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val forecaster = com.chin.stockanalysis.strategy.market.SectorTrendForecaster(
                    requireContext().applicationContext
                )
                val report = forecaster.forecast()

                withContext(Dispatchers.Main) {
                    statusTv.text = "✅ ${report.targetMonth} 月度前瞻已生成（${report.sectors.size} 個板塊）"
                    showDialog("月度熱點前瞻", report.narrative)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 月度前瞻失敗: ${e.message}"
                    Toast.makeText(requireContext(), "月度前瞻失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 顯示交易歷史 */
    protected fun showTradeHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == quantType }
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("📋 交易記錄 (最近100條)"); sb.appendLine()
                    if (orders.isEmpty()) sb.appendLine("暫無交易記錄")
                    else for (order in orders) {
                        val statusEmoji = when (order.status) {
                            "SOLD" -> "✅"; "BUYING" -> "🟢"; "FAILED" -> "❌"; else -> "⏳"
                        }
                        sb.appendLine("$statusEmoji ${order.stockName}(${order.stockCode.takeLast(6)})")
                        sb.appendLine("   買入: ${order.tradeDate} ¥${"%.2f".format(order.buyPrice)} x${order.quantity}")
                        if (order.status == "SOLD") {
                            val profitStr = if (order.profitPct >= 0) "+${"%.2f".format(order.profitPct)}%"
                            else "${"%.2f".format(order.profitPct)}%"
                            sb.appendLine("   賣出: ¥${"%.2f".format(order.sellPrice)} 收益: $profitStr")
                        } else sb.appendLine("   狀態: ${order.status}")
                        sb.appendLine()
                    }
                    showDialog("交易記錄", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "加載失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 導出熱門板塊（從 sector_daily_record 表讀取已保存的數據） */
    protected fun exportHotSectors() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val recentDays = db.sectorDailyRecordDao().getRecentDays(30)
                if (recentDays.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "無熱門板塊數據（請先導入或運行量化）", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val sb = StringBuilder()
                sb.appendLine("🔥 熱門板塊報告（最近 30 個交易日）")
                sb.appendLine("導出時間: ${LocalDate.now()}"); sb.appendLine()

                // 按日期分組
                val grouped = recentDays.groupBy { it.date }.toSortedMap()
                for ((date, sectors) in grouped) {
                    val hotCount = sectors.count { it.isHot == "Y" || it.isHot == "true" }
                    sb.appendLine("📅 $date（${sectors.size} 板塊，${hotCount} 熱門）")
                    for (s in sectors.sortedByDescending { it.hotScore }.take(10)) {
                        val tag = when (s.rank) { in 1..3 -> "🔥"; in 4..10 -> "⭐"; else -> "  " }
                        sb.appendLine("  $tag ${s.sectorName} 漲幅:${"%.2f".format(s.changePct)}% 主力:${"%.0f".format(s.mainNetInflow)}萬 評分:${"%.1f".format(s.hotScore)} 連板:${s.consecutiveHotDays}天 $s.isHot")
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) {
                    showDialog("熱門板塊報告", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "導出失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 導出策略報告（各策略的權重、擬合結果、回測表現） */
    protected fun exportStrategyReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val eng = com.chin.stockanalysis.strategy.StrategyEngineHolder.get()
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }

                val sb = StringBuilder()
                sb.appendLine("📋 策略配置報告")
                sb.appendLine("導出時間: ${LocalDate.now()}")
                sb.appendLine("量化類型: ${getQuantType()}"); sb.appendLine()

                for (strategy in strategies) {
                    sb.appendLine("━━ ${strategy.name} (${strategy.id}) ━━")
                    sb.appendLine("  類別: ${strategy.category.label}")
                    sb.appendLine("  權重因子:")
                    for (f in strategy.weightFactors) {
                        sb.appendLine("    - ${f.label}: ${f.weight}%")
                    }
                    // 讀取最近的擬合結果
                    val snapshots = try {
                        db.strategyWeightSnapshotDao().getByStrategy(strategy.id)
                    } catch (_: Exception) { emptyList() }
                    if (snapshots.isNotEmpty()) {
                        val latest = snapshots.first()
                        sb.appendLine("  最近擬合: ${latest.date} | 命中: ${latest.hitCount}")
                    }
                    sb.appendLine()
                }

                // 市場環境
                try {
                    val marketReport = com.chin.stockanalysis.strategy.market.MarketAnalyzer.analyze(requireContext(), emptyList())
                    sb.appendLine("━━ 當前市場環境 ━━")
                    sb.appendLine("  趨勢: ${marketReport.trend.direction} (強度:${marketReport.trend.strength})")
                    sb.appendLine("  ADX: ${marketReport.trend.adx?.let { "%.1f".format(it) } ?: "N/A"}")
                    sb.appendLine("  賣出類型: ${marketReport.sellType.sellType}")
                    sb.appendLine()
                    sb.append(marketReport.summary)
                } catch (_: Exception) {
                    sb.appendLine("（市場環境分析失敗）")
                }

                withContext(Dispatchers.Main) {
                    showDialog("策略報告", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "導出失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 顯示市場記憶設置對話框（AI檢測 + 人為設置並行顯示） */
    protected fun showMarketMemoryDialog() {
        val memory = com.chin.stockanalysis.strategy.sector.UserMarketMemory(requireContext())
        val ctx = requireContext()

        // 先顯示載入中的對話框，異步獲取數據後更新內容
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }
        val loadingTv = android.widget.TextView(ctx).apply {
            text = "⏳ 正在載入市場記憶數據..."
            textSize = 13f
            setPadding(16, 24, 16, 24)
            gravity = android.view.Gravity.CENTER
        }
        container.addView(loadingTv)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🧠 市場記憶設置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val input = container.findViewWithTag<android.widget.EditText>("sector_input")
                if (input != null) {
                    val sectors = input.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }
                    memory.focusSectors = sectors
                    com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                    android.widget.Toast.makeText(ctx, "已保存 ${sectors.size} 個關注板塊，緩存已清空", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()

        // 異步載入 AI 檢測 + 近期熱門板塊 + 新聞板塊
        lifecycleScope.launch(Dispatchers.IO) {
            val aiDetection = memory.aiYearDetection
            val hotSectors = memory.getRecentHotSectors()
            val newsSectors = memory.getRecentNewsSectors()
            val currentFocus = memory.focusSectors

            // 合併所有建議板塊（去重）
            val allSuggestions = (hotSectors.map { it.sectorName } + newsSectors + currentFocus)
                .distinct().filter { it.isNotBlank() }

            withContext(Dispatchers.Main) {
                if (!isAdded) { dialog.dismiss(); return@withContext }
                container.removeAllViews()

                // ════ AI 檢測區 ════
                val aiSection = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#F0F7FF"))
                    setPadding(16, 12, 16, 12)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.bottomMargin = 8
                    layoutParams = lp
                }

                aiSection.addView(android.widget.TextView(ctx).apply {
                    text = "🤖 AI 市場檢測"
                    textSize = 13f
                    setTextColor(Color.parseColor("#1565C0"))
                    setTypeface(null, Typeface.BOLD)
                })

                aiSection.addView(android.widget.TextView(ctx).apply {
                    text = "📊 風格判斷：$aiDetection"
                    textSize = 11f
                    setTextColor(Color.parseColor("#333333"))
                    setPadding(0, 4, 0, 8)
                })

                if (hotSectors.isNotEmpty()) {
                    aiSection.addView(android.widget.TextView(ctx).apply {
                        text = "🔥 近5日熱門板塊（按熱度排序）："
                        textSize = 11f
                        setTextColor(Color.parseColor("#555555"))
                        setTypeface(null, Typeface.BOLD)
                    })
                    val hotSb = StringBuilder()
                    for (s in hotSectors.take(6)) {
                        val trendIcon = if (s.totalChangePct > 0) "📈" else if (s.totalChangePct < 0) "📉" else "➡️"
                        hotSb.appendLine("  $trendIcon ${s.sectorName} 熱度${"%.0f".format(s.avgHotScore)} 累計${"%+.1f".format(s.totalChangePct)}% ${s.hotDays}天熱門")
                    }
                    aiSection.addView(android.widget.TextView(ctx).apply {
                        text = hotSb.toString().trim()
                        textSize = 10f
                        setTextColor(Color.parseColor("#666666"))
                        setPadding(0, 2, 0, 8)
                    })
                }

                if (newsSectors.isNotEmpty()) {
                    aiSection.addView(android.widget.TextView(ctx).apply {
                        text = "📰 新聞高頻板塊：${newsSectors.joinToString(" · ")}"
                        textSize = 10f
                        setTextColor(Color.parseColor("#666666"))
                        setPadding(0, 2, 0, 4)
                    })
                }

                // AI 刷新按鈕
                aiSection.addView(android.widget.Button(ctx).apply {
                    text = "🔄 重新檢測市場風格"
                    textSize = 11f
                    setOnClickListener {
                        text = "⏳ 檢測中..."
                        isEnabled = false
                        lifecycleScope.launch {
                            val result = memory.detectSectorYearByIndex()
                            memory.aiYearDetection = result
                            com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                            withContext(Dispatchers.Main) {
                                text = "✅ $result"
                                isEnabled = true
                                // 刷新整個對話框
                                dialog.dismiss()
                                showMarketMemoryDialog()
                            }
                        }
                    }
                })

                container.addView(aiSection)

                // ════ 人為設置區 ════
                val manualSection = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#FAFAFA"))
                    setPadding(16, 12, 16, 12)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = 4
                    layoutParams = lp
                }

                manualSection.addView(android.widget.TextView(ctx).apply {
                    text = "✍️ 人為設置關注板塊"
                    textSize = 13f
                    setTextColor(Color.parseColor("#E65100"))
                    setTypeface(null, Typeface.BOLD)
                })

                manualSection.addView(android.widget.TextView(ctx).apply {
                    text = "系統會持續追蹤這些板塊，連跌時提醒，選股時優先。"
                    textSize = 10f
                    setTextColor(Color.parseColor("#888888"))
                    setPadding(0, 4, 0, 8)
                })

                val input = android.widget.EditText(ctx).apply {
                    hint = "輸入關注板塊，用逗號分隔"
                    setText(currentFocus.joinToString(", "))
                    textSize = 12f
                    tag = "sector_input"
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    layoutParams = lp
                }
                manualSection.addView(input)

                // 快速添加板塊標籤
                if (allSuggestions.isNotEmpty()) {
                    manualSection.addView(android.widget.TextView(ctx).apply {
                        text = "點擊下方標籤快速添加/移除："
                        textSize = 10f
                        setTextColor(Color.parseColor("#999999"))
                        setPadding(0, 8, 0, 4)
                    })

                    val chipsFlow = com.chin.stockanalysis.ui.FlowLayout(ctx).apply {
                        setPadding(0, 4, 0, 4)
                    }

                    for (sector in allSuggestions.take(12)) {
                        val isAdded = currentFocus.any { it == sector }
                        val chip = android.widget.TextView(ctx).apply {
                            text = if (isAdded) "✓ $sector" else "+ $sector"
                            textSize = 11f
                            setPadding(20, 8, 20, 8)
                            setTextColor(if (isAdded) Color.WHITE else Color.parseColor("#555555"))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = 16f
                                setColor(if (isAdded) Color.parseColor("#4CAF50") else Color.parseColor("#EEEEEE"))
                            }
                            setOnClickListener {
                                val currentText = input.text.toString()
                                val currentList = currentText.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
                                if (currentList.contains(sector)) {
                                    currentList.remove(sector)
                                    text = "+ $sector"
                                    setTextColor(Color.parseColor("#555555"))
                                    background = android.graphics.drawable.GradientDrawable().apply {
                                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                        cornerRadius = 16f
                                        setColor(Color.parseColor("#EEEEEE"))
                                    }
                                } else {
                                    currentList.add(sector)
                                    text = "✓ $sector"
                                    setTextColor(Color.WHITE)
                                    background = android.graphics.drawable.GradientDrawable().apply {
                                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                        cornerRadius = 16f
                                        setColor(Color.parseColor("#4CAF50"))
                                    }
                                }
                                input.setText(currentList.joinToString(", "))
                            }
                        }
                        val chipLp = com.chin.stockanalysis.ui.FlowLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        chipLp.setMargins(0, 0, 8, 8)
                        chip.layoutParams = chipLp
                        chipsFlow.addView(chip)
                    }
                    manualSection.addView(chipsFlow)
                }

                container.addView(manualSection)
            }
        }
    }

}
