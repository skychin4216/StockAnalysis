package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
            text = "🔧 Pipeline"
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
        val tTradeBtn = Button(requireContext()).apply {
            text = "🔄 做T"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#BF360C"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.8f).apply { marginEnd = 1 }
            setOnClickListener { showTTradeMenu() }
        }
        row.addView(tTradeBtn)

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

                if (holdingOrders.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(requireContext(), "無 $periodType 持倉，無法做T", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 為每只持倉生成做T信號
                val allSignals = mutableListOf<TTradeSignal>()
                for (order in holdingOrders) {
                    val signals = tEngine.generateSignals(order.stockCode, order.quantity, periodType)
                    allSignals.addAll(signals)
                }

                // 獲取做T統計
                val stats = tEngine.getTTradeStats(periodType)

                withContext(Dispatchers.Main) {
                    showTTradeDialog(allSignals, stats, periodType)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(requireContext(), "做T面板加載失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 顯示做T交易對話框 */
    private fun showTTradeDialog(
        signals: List<TTradeSignal>,
        stats: TTradeStats,
        periodType: String
    ) {
        val dialog = android.app.Dialog(requireContext())
        dialog.setTitle("🔄 做T面板 — $periodType")
        val scrollView = android.widget.ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 統計區
        val winRateStr = "%.1f%%".format(stats.winRate)
        val profitStr = "%.2f".format(stats.totalProfit)
        container.addView(TextView(requireContext()).apply {
            text = "📊 做T統計: 總盈虧 $profitStr | 勝率 $winRateStr | 已完成 ${stats.totalTrades} 筆 | 未平倉 ${stats.openCount} 筆"
            textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 8)
        })

        if (signals.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "暫無做T信號\n\n做T條件：\n• 股價接近支撐位 → 做T買入\n• 股價接近阻力位 → 反T賣出\n• 需有底倉才能做T"
                textSize = 11f; setTextColor(Color.parseColor("#666666"))
                setPadding(0, 16, 0, 16)
            })
        } else {
            // 信號列表
            container.addView(TextView(requireContext()).apply {
                text = "📋 做T信號 (${signals.size} 個):"
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
                setPadding(0, 8, 0, 4)
            })

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
                signalCard.addView(Button(requireContext()).apply {
                    text = "執行 ${signal.signalType.label}"
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(signalColor)
                    setPadding(4, 1, 4, 1)
                    setMinWidth(0); setMinimumWidth(0)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(22)).apply {
                        topMargin = 4
                    }
                    setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val tEngine = TTradeEngine(requireContext())
                                tEngine.executeTTrade(signal, periodType)
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(requireContext(), "✅ ${signal.signalType.label} 已執行", android.widget.Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                    showTTradeMenu() // 刷新
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(requireContext(), "執行失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                })
                container.addView(signalCard)
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

    // ═══════════════════════════════════════════════════
    // 數據管理
    // ═══════════════════════════════════════════════════

    /** 顯示數據菜單 */
    protected open fun showDataMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "📋 查看交易記錄")
        popup.menu.add(0, 2, 0, "📊 查看持倉詳情")
        popup.menu.add(0, 3, 0, "🔥 導出熱門板塊數據")
        popup.menu.add(0, 4, 0, "📋 導出策略報告")
        popup.menu.add(0, 5, 0, "🧠 市場記憶設置")
        popup.menu.add(0, 6, 1, "💰 持有收益歷史")
        popup.menu.add(0, 7, 1, "📅 月度熱點前瞻")
        popup.menu.add(0, 10, 4, "📈 回溯測試")
        popup.menu.add(0, 11, 4, "🔧 擬合調優")
        popup.menu.add(0, 12, 5, "🔄 全周期擬合")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showTradeHistory()
                2 -> loadPositions()
                3 -> exportHotSectors()
                4 -> exportStrategyReport()
                5 -> showMarketMemoryDialog()
                6 -> showHoldingProfitHistory()
                7 -> showMonthlyForecast()
                10 -> onBacktrackClick()
                11 -> onFittingClick()
                12 -> runCrossPeriodFitting()
            }
            true
        }
        popup.show()
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

    /** 刷新持倉 */
    open fun refreshPositions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter {
                        it.orderType == quantType &&
                        (it.status == "BUYING" || it.status == "PENDING")
                    }
                    .sortedByDescending { it.tradeDate }

                if (orders.isEmpty()) {
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

                withContext(Dispatchers.Main) {
                    renderPositions(orders, dates, priceMap)
                }
            } catch (_: Exception) {}
        }
    }

    /** 渲染持倉列表（多日表格視圖，可被子類覆寫） */
    protected open fun renderPositions(
        orders: List<StrategyTradeOrderEntity>,
        dates: List<String>,
        priceMap: Map<String, Map<String, Double>>
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

    /** 顯示市場記憶設置對話框 */
    protected fun showMarketMemoryDialog() {
        val memory = com.chin.stockanalysis.strategy.sector.UserMarketMemory(requireContext())
        val current = memory.focusSectors.joinToString(", ")
        val input = android.widget.EditText(requireContext()).apply {
            hint = "輸入關注板塊，用逗號分隔（如：科技,半導體,光通信）"
            setText(current)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("🧠 市場記憶設置")
            .setMessage("系統會持續追蹤這些板塊，連跌時提醒，選股時優先。\n\nAI 當前判斷：${memory.aiYearDetection}")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val sectors = input.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }
                memory.focusSectors = sectors
                com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                android.widget.Toast.makeText(requireContext(), "已保存 ${sectors.size} 個關注板塊，緩存已清空", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("AI 檢測板塊大年") { _, _ ->
                lifecycleScope.launch {
                    val result = memory.detectSectorYearByIndex()
                    memory.aiYearDetection = result
                    com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                    android.widget.Toast.makeText(requireContext(), "AI 檢測：$result", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    /** 導出交易數據（保留供子類調用） */
    protected fun exportTradeData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()
                val orders = db.strategyTradeOrderDao().getRecent(1000)
                    .filter { it.orderType == quantType }
                val sb = StringBuilder()
                sb.appendLine("$quantType 數據導出")
                sb.appendLine("導出時間: ${LocalDate.now()}"); sb.appendLine()
                sb.appendLine("=== 交易訂單 ===")
                sb.appendLine("日期,策略,股票代碼,股票名稱,買入價,數量,狀態,賣出價,收益%")
                for (order in orders) {
                    sb.appendLine("${order.tradeDate},${order.strategyId},${order.stockCode},${order.stockName},${order.buyPrice},${order.quantity},${order.status},${order.sellPrice},${order.profitPct}")
                }
                withContext(Dispatchers.Main) {
                    showDialog("導出數據", sb.toString())
                    Toast.makeText(requireContext(), "數據已生成（可複製）", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "導出失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
