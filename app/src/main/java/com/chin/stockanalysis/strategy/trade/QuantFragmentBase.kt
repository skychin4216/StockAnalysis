package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
 * - UI 組件（狀態、進度條、持倉容器、按鈕等）
 * - 賣出評估與執行（使用 AutoSellEngine）
 * - 數據管理與清除
 * - 持倉刷新與顯示
 */
abstract class QuantFragmentBase : Fragment() {

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

    /** 清除按鈕 */
    protected lateinit var clearBtn: Button

    // ═══════════════════════════════════════════════════
    // 引擎與數據
    // ═══════════════════════════════════════════════════

    protected var engine: StrategyEngine? = null
    protected var tradeEngine: SimulationTradeEngine? = null
    protected var browsingDate: LocalDate = TradingDayPickerView.recentTradingDay()

    /** 賣出決策緩存 */
    protected var sellDecisionsCache: List<AutoSellEngine.SellDecision> = emptyList()

    /** 清除模式標記 */
    protected var clearMode: Boolean = false
    protected var selectedDateForClear: String? = null

    companion object {
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
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
        tradeEngine = SimulationTradeEngine(ctx)
    }

    protected abstract fun buildUI()

    // ═══════════════════════════════════════════════════
    // 抽象方法（子類必須實現）
    // ═══════════════════════════════════════════════════

    /** 返回量化類型："ShortTermQuant" 或 "MidTermQuant" */
    abstract fun getQuantType(): String

    /** 擬合按鈕點擊 */
    abstract fun onFittingClick()

    /** 回調按鈕點擊 */
    abstract fun onBacktrackClick()

    /** 清除按鈕點擊 */
    abstract fun onClearClick()

    /** 加載持倉（子類可覆寫以自定義持倉顯示） */
    abstract fun loadPositions()

    // ═══════════════════════════════════════════════════
    // UI 輔助方法
    // ═══════════════════════════════════════════════════

    /**
     * 創建按鈕行
     * 包含：擬合、回調、數據、賣出、清除
     */
    protected fun createButtonRow(): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 1, 4, 1)
        }

        // 擬合按鈕
        val fitBtn = Button(requireContext()).apply {
            text = "🔧 擬合(90%)"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#EF6C00"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0)
            setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }
            setOnClickListener { onFittingClick() }
        }
        row.addView(fitBtn)

        // 回調按鈕
        val backtrackBtn = Button(requireContext()).apply {
            text = "📈 回調"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7B1FA2"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0)
            setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }
            setOnClickListener { onBacktrackClick() }
        }
        row.addView(backtrackBtn)

        // 數據按鈕
        val dataBtn = Button(requireContext()).apply {
            text = "🗄️ 數據"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#455A64"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0)
            setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }
            setOnClickListener { showDataMenu() }
        }
        row.addView(dataBtn)

        // 賣出按鈕（帶下拉菜單）
        val sellBtn = Button(requireContext()).apply {
            text = "💰 賣出 ▾"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00897B"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0)
            setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1f).apply { marginEnd = 1 }
            setOnClickListener { showSellMenu(it) }
        }
        row.addView(sellBtn)

        // 清除按鈕
        clearBtn = Button(requireContext()).apply {
            text = "🗑️ 清除"
            textSize = 9f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0)
            setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.8f)
            setOnClickListener { onClearClick() }
        }
        row.addView(clearBtn)

        return row
    }

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

    /**
     * 顯示賣出下拉菜單
     */
    protected fun showSellMenu(anchor: View) {
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

    /**
     * 執行賣出評估（僅評估，不執行）
     */
    protected fun runAutoSellEvaluation() {
        val eng = engine ?: return
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在評估賣出信號..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val te = tradeEngine ?: return@launch

                // 過濾當前量化類型的訂單進行評估
                val decisions = te.runAutoSellEvaluate(
                    strategies,
                    browsingDate.format(DATE_FMT)
                ).filter { it.order.orderType == getQuantType() }

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

    /**
     * 執行賣出（使用緩存的決策）
     */
    protected fun executeAutoSell() {
        if (sellDecisionsCache.isEmpty()) {
            // 沒有緩存，先評估
            runAutoSellEvaluation()
            Toast.makeText(
                requireContext(),
                "請等待評估完成後再次點擊「執行賣出」",
                Toast.LENGTH_SHORT
            ).show()
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
                val te = tradeEngine ?: return@launch
                te.runAutoSell(
                    engine?.getStrategies()?.filter { engine!!.isEnabled(it.id) } ?: emptyList(),
                    browsingDate.format(DATE_FMT)
                )
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 已執行 ${shouldSell.size} 筆賣出"
                    refreshPositions()
                    sellDecisionsCache = emptyList()
                    Toast.makeText(
                        requireContext(),
                        "已賣出 ${shouldSell.size} 筆訂單",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 賣出執行失敗: ${e.message?.take(40)}"
                }
            }
        }
    }

    /**
     * 顯示賣出決策對話框
     */
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
                if (d.technicalDetails.isNotEmpty()) {
                    d.technicalDetails.forEach { (k, v) -> sb.appendLine("    $k=$v") }
                }
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

    /**
     * 查看賣出策略歷史績效
     */
    protected fun showSellPerformance() {
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在統計賣出績效..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val te = tradeEngine ?: return@launch
                val stats = te.getSellPerformance(90)

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
                            "HardStop" -> "硬止損"
                            "MaxDrawdown" -> "最大回撤"
                            "TimeForceClose" -> "時間強平"
                            "TimeNoProgress" -> "時間無進展"
                            "TieredTP" -> "階梯止盈"
                            "ChandelierExit" -> "吊燈止損"
                            "TrailProfit" -> "移動止盈"
                            "MADeathCross" -> "MA死叉"
                            "VolumeClimax" -> "放量滯漲"
                            "RSIOverbought" -> "RSI超買"
                            "SectorWeakness" -> "板塊弱勢"
                            "TakeProfit" -> "目標止盈"
                            "ATRStop" -> "ATR止損"
                            "MomentumDecay" -> "動量衰竭"
                            "Other" -> "其他"
                            else -> s.strategyName.take(6)
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
    // 數據管理
    // ═══════════════════════════════════════════════════

    /**
     * 顯示數據菜單
     */
    protected open fun showDataMenu() {
        val options = arrayOf(
            "📋 查看交易記錄",
            "📊 查看持倉詳情",
            "📤 導出交易數據"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("數據中心")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showTradeHistory()
                    1 -> loadPositions()
                    2 -> exportTradeData()
                }
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    /**
     * 清除數據
     */
    protected fun clearData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()

                // 獲取該類型的所有訂單
                val orders = db.strategyTradeOrderDao().getRecent(500)
                    .filter { it.orderType == quantType }

                // 刪除該類型的訂單（按日期分組刪除）
                val dates = orders.map { it.tradeDate }.distinct()
                for (date in dates) {
                    db.strategyTradeOrderDao().deleteByDate(date)
                }

                withContext(Dispatchers.Main) {
                    refreshPositions()
                    statusTv.text = "✅ 已清除 ${orders.size} 條 $quantType 數據"
                    Toast.makeText(
                        requireContext(),
                        "已清除 ${orders.size} 條記錄",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 清除失敗: ${e.message?.take(40)}"
                }
            }
        }
    }

    /**
     * 按日期清除數據
     */
    protected fun clearDataByDate(date: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()

                // 刪除該日期和類型的訂單
                db.strategyTradeOrderDao().deleteByDate(date)

                withContext(Dispatchers.Main) {
                    refreshPositions()
                    statusTv.text = "✅ 已清除 $date 的 $quantType 數據"
                    Toast.makeText(
                        requireContext(),
                        "已清除 $date 的數據",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 清除失敗: ${e.message?.take(40)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 持倉管理
    // ═══════════════════════════════════════════════════

    /**
     * 刷新持倉
     */
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

                val dates = db.dailySnapshotDao().getAvailableDates(15)
                    .filter { it >= orders.minOfOrNull { it.tradeDate } ?: it }
                    .sorted()
                    .takeLast(10)

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
            } catch (e: Exception) {
                // 忽略錯誤
            }
        }
    }

    /**
     * 渲染持倉列表（可被子類覆寫）
     */
    protected open fun renderPositions(
        orders: List<StrategyTradeOrderEntity>,
        dates: List<String>,
        priceMap: Map<String, Map<String, Double>>
    ) {
        positionContainer.removeAllViews()

        if (orders.isEmpty()) {
            positionContainer.addView(TextView(requireContext()).apply {
                text = "📌 暫無持倉記錄"
                textSize = 11f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, 4, 0, 4)
            })
            return
        }

        // 計算總盈虧
        val lastDate = dates.lastOrNull() ?: browsingDate.format(DATE_FMT)
        var totalCost = 0.0
        var totalValue = 0.0

        for (order in orders) {
            totalCost += order.buyPrice * order.quantity
            val lastPrice = priceMap[order.stockCode]?.get(lastDate) ?: order.buyPrice
            totalValue += lastPrice * order.quantity
        }

        val totalPnl = totalValue - totalCost
        val totalPnlPct = if (totalCost > 0) (totalPnl / totalCost * 100) else 0.0
        val pnlColor = if (totalPnl >= 0) "#D32F2F" else "#2E7D32"
        val pnlStr = "${if (totalPnl >= 0) "+" else ""}¥${"%.0f".format(totalPnl)} (${"%.2f".format(totalPnlPct)}%)"

        // 標題行
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 2)
        }

        titleRow.addView(TextView(requireContext()).apply {
            text = "📌 ${getQuantType()}持倉"
            textSize = 12f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        titleRow.addView(TextView(requireContext()).apply {
            text = "總持倉 ${orders.size} 只  |  "
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.END
        })

        titleRow.addView(TextView(requireContext()).apply {
            text = "總盈虧 $pnlStr"
            textSize = 10f
            setTextColor(Color.parseColor(pnlColor))
            gravity = Gravity.END
        })

        positionContainer.addView(titleRow)

        // 持倉列表
        val scroll = ScrollView(requireContext())
        val listLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (order in orders) {
            val row = createPositionRow(order, dates, priceMap)
            listLayout.addView(row)
        }

        scroll.addView(listLayout)
        positionContainer.addView(scroll)
    }

    /**
     * 創建持倉行（可被子類覆寫）
     */
    protected open fun createPositionRow(
        order: StrategyTradeOrderEntity,
        dates: List<String>,
        priceMap: Map<String, Map<String, Double>>
    ): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.WHITE)
        }

        // 股票名稱和代碼
        val nameLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }

        nameLayout.addView(TextView(requireContext()).apply {
            text = order.stockName
            textSize = 13f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
        })

        nameLayout.addView(TextView(requireContext()).apply {
            text = order.stockCode
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
        })

        row.addView(nameLayout)

        // 成本價
        row.addView(TextView(requireContext()).apply {
            text = "¥${"%.2f".format(order.buyPrice)}"
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        })

        // 當前價和盈虧
        val lastPrice = priceMap[order.stockCode]?.get(dates.lastOrNull())
        if (lastPrice != null) {
            val pnl = if (order.buyPrice > 0) (lastPrice - order.buyPrice) / order.buyPrice * 100 else 0.0
            val pnlColor = if (pnl >= 0) "#D32F2F" else "#2E7D32"

            val priceLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
                gravity = Gravity.CENTER
            }

            priceLayout.addView(TextView(requireContext()).apply {
                text = "¥${"%.2f".format(lastPrice)}"
                textSize = 12f
                setTextColor(Color.parseColor("#333333"))
                gravity = Gravity.CENTER
            })

            priceLayout.addView(TextView(requireContext()).apply {
                text = "${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)}%"
                textSize = 11f
                setTextColor(Color.parseColor(pnlColor))
                gravity = Gravity.CENTER
            })

            row.addView(priceLayout)
        }

        // 賣出按鈕
        val sellBtn = Button(requireContext()).apply {
            text = "賣"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(32))
            isEnabled = lastPrice != null
            setOnClickListener {
                showSellConfirmDialog(order, lastPrice ?: order.buyPrice)
            }
        }
        row.addView(sellBtn)

        return row
    }

    /**
     * 顯示單筆賣出確認對話框
     */
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
            .setPositiveButton("確認賣出") { _, _ ->
                executeSingleSell(order, currentPrice)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 執行單筆賣出
     */
    protected fun executeSingleSell(order: StrategyTradeOrderEntity, sellPrice: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val dao = db.strategyTradeOrderDao()
                val profitPct = (sellPrice - order.buyPrice) / order.buyPrice * 100

                dao.updateSellInfo(
                    id = order.id,
                    status = "SOLD",
                    sellPrice = sellPrice,
                    sellTime = LocalDate.now().toString() + " " +
                              java.time.LocalTime.now().toString().take(8),
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

    /**
     * 顯示通用對話框
     */
    protected fun showDialog(title: String, content: String) {
        val sv = ScrollView(requireContext())
        sv.addView(TextView(requireContext()).apply {
            text = content
            textSize = 10f
            setTextColor(Color.parseColor("#333333"))
            setPadding(16, 12, 16, 12)
            setLineSpacing(2f, 1.1f)
            setTypeface(Typeface.MONOSPACE)
        })

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("關閉", null)
            .create()
            .apply {
                show()
                window?.setLayout(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
    }

    /**
     * 顯示交易歷史
     */
    protected fun showTradeHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == quantType }

                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("📋 交易記錄 (最近100條)")
                    sb.appendLine()

                    if (orders.isEmpty()) {
                        sb.appendLine("暫無交易記錄")
                    } else {
                        for (order in orders) {
                            val statusEmoji = when (order.status) {
                                "SOLD" -> "✅"
                                "BUYING" -> "🟢"
                                "FAILED" -> "❌"
                                else -> "⏳"
                            }
                            sb.appendLine("$statusEmoji ${order.stockName}(${order.stockCode.takeLast(6)})")
                            sb.appendLine("   買入: ${order.tradeDate} ¥${"%.2f".format(order.buyPrice)} x${order.quantity}")
                            if (order.status == "SOLD") {
                                val profitStr = if (order.profitPct >= 0)
                                    "+${"%.2f".format(order.profitPct)}%"
                                else
                                    "${"%.2f".format(order.profitPct)}%"
                                sb.appendLine("   賣出: ¥${"%.2f".format(order.sellPrice)} 收益: $profitStr")
                            } else {
                                sb.appendLine("   狀態: ${order.status}")
                            }
                            sb.appendLine()
                        }
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

    /**
     * 導出交易數據
     */
    protected fun exportTradeData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()
                val orders = db.strategyTradeOrderDao().getRecent(1000)
                    .filter { it.orderType == quantType }

                val sb = StringBuilder()
                sb.appendLine("$quantType 數據導出")
                sb.appendLine("導出時間: ${LocalDate.now()}")
                sb.appendLine()
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
