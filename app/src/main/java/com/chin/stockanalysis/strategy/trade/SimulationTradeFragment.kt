package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.database.DataExportImport
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.strategies.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.chin.stockanalysis.ui.TradingDayPickerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.*

/**
 * ## 中线量化面板 v2.0
 *
 * 新增:
 * - 自动卖出评估（10维智能卖出引擎）
 * - 卖出策略绩效统计
 * - 一键执行卖出
 */
class SimulationTradeFragment : Fragment() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var executeBtn: Button
    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView
    private lateinit var fittingBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var periodCheckboxes: LinearLayout
    private lateinit var mainBoardSwitch: Switch
    private lateinit var resultsContainer: LinearLayout
    private lateinit var positionContainer: LinearLayout
    private lateinit var reportDivider: View

    private var engine: StrategyEngine? = null
    private var screener: StockScreener? = null
    private var browsingDate: LocalDate = TradingDayPickerView.recentTradingDay()
    private var selectedPeriods: Set<Int> = setOf(1)
    private var tradeEngine: SimulationTradeEngine? = null
    private var db: StockDatabase? = null
    private var clearMode: Boolean = false
    private var selectedDateForClear: String? = null
    private var positionOrderDates: MutableMap<View, String> = mutableMapOf()
    private var hasTradeReport: Boolean = false

    // 卖出决策缓存
    private var sellDecisionsCache: List<AutoSellEngine.SellDecision> = emptyList()

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val PERIOD_LABELS = mapOf(1 to "当日", 3 to "近3日", 10 to "近10日",
            30 to "近30日", 50 to "近50日", 100 to "近100日")
    }

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

    private fun initEngine() {
        val ctx = requireContext().applicationContext
        com.chin.stockanalysis.strategy.StrategyEngineHolder.init(ctx)
        engine = com.chin.stockanalysis.strategy.StrategyEngineHolder.get()
        tradeEngine = SimulationTradeEngine(ctx)
    }

    private fun buildUI() {
        // 标题
        rootLayout.addView(TextView(requireContext()).apply {
            text = "🤖 中线量化系统 v2.0 (含智能卖出)"
            textSize = 18f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setPadding(16, 16, 16, 8)
        })

        // 参数配置区
        rootLayout.addView(createConfigSection())

        // 进度
        val progressRow = LinearLayout(requireContext()).apply {
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
        progressRow.addView(progressBar)
        statusTv = TextView(requireContext()).apply {
            text = "就绪"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        progressRow.addView(statusTv)
        rootLayout.addView(progressRow)

        // 操作按钮区（中线量化 + 智能卖出 合并一行）
        rootLayout.addView(createActionButtons())

        // 分割线
        rootLayout.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 8 }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        // 持仓视图
        positionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            setPadding(8, 4, 8, 4)
        }
        rootLayout.addView(positionContainer)

        // 分割线（初始隐藏，有报告时显示）
        reportDivider = View(requireContext()).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 4 }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        }
        rootLayout.addView(reportDivider)

        // 结果区（初始隐藏，有报告时显示）
        resultsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 4, 8, 4)
        }
        rootLayout.addView(resultsContainer)

        refreshPositions()
    }

    private fun updateViewSplit() {
        if (hasTradeReport) {
            positionContainer.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            resultsContainer.visibility = View.VISIBLE
            resultsContainer.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            reportDivider.visibility = View.VISIBLE
        } else {
            positionContainer.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            resultsContainer.visibility = View.GONE
            reportDivider.visibility = View.GONE
        }
        rootLayout.requestLayout()
    }

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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dateLabelTv = TextView(requireContext()).apply {
            text = "📅 交易日:"
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
        }
        row1.addView(dateLabelTv)
        datePicker = TradingDayPickerView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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
            text = "仅主板"
            textSize = 12f
            isChecked = true
            setTextColor(Color.parseColor("#333333"))
        }
        row1.addView(mainBoardSwitch)
        container.addView(row1)

        container.addView(TextView(requireContext()).apply {
            text = "📊 数据周期:"
            textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 4, 0, 2)
        })

        periodCheckboxes = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0)
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

    private fun createActionButtons(): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4, 1, 4, 1)
        }

        executeBtn = Button(requireContext()).apply {
            text = "▶ 建仓"; textSize = 10f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E65100"))
            setPadding(4, 1, 4, 1); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1f).apply { marginEnd = 1 }
            setOnClickListener { executeTrade() }
        }; row.addView(executeBtn)

        fittingBtn = Button(requireContext()).apply {
            text = "🔧 拟合"; textSize = 10f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#EF6C00"))
            setPadding(4, 1, 4, 1); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }
            setOnClickListener { showFittingParams() }
        }; row.addView(fittingBtn)

        val backtrackBtn = Button(requireContext()).apply {
            text = "📈 回溯"; textSize = 10f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7B1FA2"))
            setPadding(4, 1, 4, 1); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }
            setOnClickListener { runNextDayBacktrack() }
        }; row.addView(backtrackBtn)

        val dataBtn = Button(requireContext()).apply {
            text = "🗄️ 数据"; textSize = 10f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#455A64"))
            setPadding(4, 1, 4, 1); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 0.9f).apply { marginEnd = 1 }
            setOnClickListener { showDataMenu() }
        }; row.addView(dataBtn)

        val sellMenuBtn = Button(requireContext()).apply {
            text = "💰 卖出 ▾"; textSize = 10f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00897B"))
            setPadding(4, 1, 4, 1); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1.5f).apply { marginEnd = 1 }
            setOnClickListener { showSellMenu(it) }
        }; row.addView(sellMenuBtn)

        clearBtn = Button(requireContext()).apply {
            text = "🗑️ 清除"; textSize = 9f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            setPadding(4, 1, 4, 1); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 1.2f)
            setOnClickListener { handleClearClick() }
        }; row.addView(clearBtn)

        return row
    }

    /** 供外部调用的自动执行中线量化 */
    fun autoExecuteTrade() {
        if (executeBtn.isEnabled) executeTrade()
    }

    // ═══════════════════════════════════════
    // 核心功能 - 中线量化
    // ═══════════════════════════════════════

    private fun executeTrade() {
        val eng = engine ?: return
        if (selectedPeriods.isEmpty()) {
            Toast.makeText(requireContext(), "请至少选择一个周期", Toast.LENGTH_SHORT).show()
            return
        }

        executeBtn.isEnabled = false; executeBtn.text = "⏳ 执行中..."
        progressBar.visibility = View.VISIBLE; statusTv.text = "正在执行中线量化..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val config = SimulationTradeEngine.TradeSessionConfig(
                    tradeDate = browsingDate.format(DATE_FMT),
                    periods = selectedPeriods.toList().sorted(),
                    onlyMainBoard = mainBoardSwitch.isChecked,
                    maxFitRounds = 20
                )
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "没有启用的策略"; executeBtn.isEnabled = true
                        executeBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE
                    }; return@launch
                }
                if (tradeEngine == null) tradeEngine = SimulationTradeEngine(requireContext())
                val report = tradeEngine!!.runTradeSession(strategies, config)
                // 腾笼换鸟：买入前检查持仓数量，超过上限自动卖出最弱持股
                val swappedCount = tradeEngine!!.swapWeakHoldings(strategies, report.buyOrders.size, browsingDate.format(DATE_FMT))
                // 合并报告：把换股信息注入 summary
                val finalReport = if (swappedCount > 0) {
                    val holdingOrders = db?.strategyTradeOrderDao()?.getRecent(200)?.filter { it.status == "BUYING" }
                    val soldStocks = db?.strategyTradeOrderDao()?.getRecent(300)?.filter { it.status == "SOLD" && it.sellTime.take(10) == browsingDate.format(DATE_FMT) }
                    val swapInfo = SimulationTradeEngine.SwapInfo(
                        beforeCount = (holdingOrders?.size ?: 0) + swappedCount,
                        soldCount = swappedCount,
                        soldStocks = soldStocks?.map { "${it.stockName}(${it.stockCode.takeLast(6)})${"%.2f".format(it.profitPct)}%" } ?: emptyList()
                    )
                    val newSummary = tradeEngine!!.buildEnhancedSummary(report.stepDetail, report.aiTop3, report.crossDayRanking, swapInfo)
                    report.copy(summary = newSummary)
                } else report
                withContext(Dispatchers.Main) {
                    try {
                        saveBuyOrdersToDb(finalReport)
                        showTradeReport(finalReport)
                        refreshPositions()
                        statusTv.text = "✅ 完成: ${report.summary.lines().firstOrNull()?.take(60) ?: "交易完成"}"
                    } catch (uiEx: Exception) {
                        Log.e("TradeUI", "UI update failed", uiEx)
                        statusTv.text = "✅ 交易完成，但显示报告时出错"
                    } finally {
                        executeBtn.isEnabled = true; executeBtn.text = "▶ 建仓"
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 执行失败: ${e.message?.take(50)}"
                    executeBtn.isEnabled = true; executeBtn.text = "▶ 建仓"
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "执行失败: ${e.message?.take(100)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTradeReport(report: SimulationTradeEngine.TradeSessionReport) {
        resultsContainer.removeAllViews()
        hasTradeReport = true
        updateViewSplit()

        val sv = ScrollView(requireContext())
        val c = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8) }

        c.addView(TextView(requireContext()).apply {
            text = "📊 中线量化报告  ${report.config.tradeDate}"
            textSize = 15f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        })

        val table = TableLayout(requireContext()).apply { isStretchAllColumns = true }
        val hr = TableRow(requireContext())
        for (h in listOf("策略", "周期", "精选3只", "买入价", "卖出价", "收益")) {
            hr.addView(TextView(requireContext()).apply {
                text = h; textSize = 9f; setTextColor(Color.parseColor("#999999"))
                setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(2, 4, 2, 4)
            })
        }
        table.addView(hr)

        val resultMap = report.periodResults.groupBy { it.strategyId to it.periodDays }
        val enabledStrategies = engine?.getStrategies()?.filter { engine!!.isEnabled(it.id) } ?: emptyList()
        for (strategy in enabledStrategies) {
            for (period in report.config.periods) {
                val pr = resultMap[strategy.id to period]?.firstOrNull()
                val name = strategy.name.take(8)
                val periodLabel = PERIOD_LABELS[period] ?: "${period}日"
                val hasResults = pr != null && pr.finalTop15.isNotEmpty()
                val top3Text = if (hasResults) {
                    pr!!.finalTop15.joinToString("\n") { "${it.stockName}(${it.stockCode.takeLast(6)}) ${it.strength}%" }
                } else "⚠ 无信号"
                val row = TableRow(requireContext())
                if (hasResults && pr != null) { row.setOnClickListener { showDetailDialog(report, pr!!) } }
                row.addView(TextView(requireContext()).apply {
                    text = name; textSize = 10f; setTextColor(Color.parseColor("#222222"))
                    setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL; setPadding(1, 4, 1, 4)
                })
                row.addView(TextView(requireContext()).apply {
                    text = periodLabel; textSize = 10f; setTextColor(Color.parseColor("#333333"))
                    gravity = Gravity.CENTER; setPadding(1, 4, 1, 4)
                })
                row.addView(TextView(requireContext()).apply {
                    text = top3Text; textSize = 8f; setTextColor(Color.parseColor("#666666"))
                    setLineSpacing(1.5f, 1f); setPadding(1, 4, 1, 4)
                })
                row.addView(TextView(requireContext()).apply {
                    text = "—"; textSize = 10f; setTextColor(Color.parseColor("#999999"))
                    gravity = Gravity.CENTER; setPadding(1, 4, 1, 4)
                })
                row.addView(TextView(requireContext()).apply {
                    text = "—"; textSize = 10f; setTextColor(Color.parseColor("#999999"))
                    gravity = Gravity.CENTER; setPadding(1, 4, 1, 4)
                })
                row.addView(TextView(requireContext()).apply {
                    text = "回溯后"; textSize = 9f; setTextColor(Color.parseColor("#1565C0"))
                    gravity = Gravity.CENTER; setPadding(1, 4, 1, 4)
                })
                table.addView(row)
            }
        }
        c.addView(table)

        if (report.aiTop3.isNotEmpty()) {
            c.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 8; bottomMargin = 4 }
                setBackgroundColor(Color.parseColor("#DDDDDD"))
            })
            c.addView(TextView(requireContext()).apply {
                text = "🤖 AI精选 Top3"; textSize = 14f; setTextColor(Color.parseColor("#1565C0"))
                setTypeface(null, Typeface.BOLD); setPadding(0, 8, 0, 6)
            })
            for (pick in report.aiTop3) {
                val scoreColor = when { pick.compositeScore >= 75 -> "#E65100"; pick.compositeScore >= 60 -> "#2E7D32"; else -> "#666666" }
                c.addView(TextView(requireContext()).apply {
                    text = "#${pick.rank} ${pick.stockName}(${pick.stockCode.takeLast(6)}) 评分:${pick.compositeScore} 概率:${pick.upProbability}% ${pick.actionSuggestion}"
                    textSize = 11f; setTextColor(Color.parseColor(scoreColor)); setPadding(0, 2, 0, 2)
                })
                c.addView(TextView(requireContext()).apply {
                    text = "  ${pick.reason.take(100)}"; textSize = 9f
                    setTextColor(Color.parseColor("#999999")); setPadding(8, 0, 0, 4)
                })
            }
        }

        sv.addView(c)
        resultsContainer.addView(sv)
    }

    private fun showDetailDialog(report: SimulationTradeEngine.TradeSessionReport, pr: SimulationTradeEngine.StrategyPeriodResult) {
        val s = StringBuilder()
        s.appendLine("【${pr.strategyName}】${report.config.tradeDate}")
        s.appendLine("周期: ${pr.periodDays}日  新闻力度: ${pr.newsStrengthScore}  轮动惩罚: ${pr.rotationPenalty}")
        s.appendLine()
        if (pr.finalTop15.isNotEmpty()) {
            s.appendLine("🔥 精选Top15:")
            for ((i, sig) in pr.finalTop15.withIndex()) {
                s.appendLine("  ${i+1}. ${sig.stockName}(${sig.stockCode.takeLast(6)}) 强度:${sig.strength}%")
                s.appendLine("     ${sig.reason.take(80)}")
            }
        }
        if (pr.filteredStocks.isNotEmpty()) {
            s.appendLine(); s.appendLine("🚫 被过滤(${pr.filteredStocks.size}只):")
            for (f in pr.filteredStocks.take(5)) s.appendLine("  ${f.stockName}: ${f.reason}")
        }
        showDialog(pr.strategyName, s.toString())
    }

    // ═══════════════════════════════════════
    // 自动卖出
    // ═══════════════════════════════════════

    /** 卖出下拉菜单 */
    private fun showSellMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "💰 卖出评估")
        popup.menu.add(0, 2, 0, "📊 卖出绩效")
        popup.menu.add(0, 3, 0, "⚡ 执行卖出")
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

    /** 仅评估卖出信号（不执行） */
    private fun runAutoSellEvaluation() {
        val eng = engine ?: return
        progressBar.visibility = View.VISIBLE; statusTv.text = "正在评估卖出信号..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val te = tradeEngine ?: return@launch
                val decisions = te.runAutoSellEvaluate(strategies, browsingDate.format(DATE_FMT))
                sellDecisionsCache = decisions
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    val shouldSell = decisions.count { it.shouldSell }
                    val holding = decisions.size
                    statusTv.text = "💰 卖出评估: $holding 持仓, $shouldSell 触发卖出"
                    showSellDecisionsDialog(decisions)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 卖出评估失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    /** 执行卖出（使用缓存的决策） */
    private fun executeAutoSell() {
        if (sellDecisionsCache.isEmpty()) {
            // 没有缓存，先评估
            runAutoSellEvaluation()
            // 简单提示用户等待评估完成后再点击执行
            Toast.makeText(requireContext(), "请等待评估完成后再次点击「执行卖出」", Toast.LENGTH_SHORT).show()
            return
        }
        val shouldSell = sellDecisionsCache.filter { it.shouldSell }
        if (shouldSell.isEmpty()) {
            Toast.makeText(requireContext(), "当前没有需要卖出的持仓", Toast.LENGTH_SHORT).show()
            return
        }
        progressBar.visibility = View.VISIBLE; statusTv.text = "正在执行卖出..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val te = tradeEngine ?: return@launch
                te.runAutoSell(engine?.getStrategies()?.filter { engine!!.isEnabled(it.id) } ?: emptyList(),
                    browsingDate.format(DATE_FMT))
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 已执行 ${shouldSell.size} 笔卖出"
                    refreshPositions()
                    sellDecisionsCache = emptyList()
                    Toast.makeText(requireContext(), "已卖出 ${shouldSell.size} 笔订单", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 卖出执行失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    private fun showSellDecisionsDialog(decisions: List<AutoSellEngine.SellDecision>) {
        val sb = StringBuilder()
        sb.appendLine("💰 智能卖出评估报告"); sb.appendLine("交易日: ${browsingDate.format(DATE_FMT)}")
        sb.appendLine("共 ${decisions.size} 个持仓评估"); sb.appendLine()

        val sellList = decisions.filter { it.shouldSell }.sortedByDescending { it.urgency }
        val holdList = decisions.filter { !it.shouldSell }

        if (sellList.isNotEmpty()) {
            sb.appendLine("🔴 卖出信号 (${sellList.size}个):")
            for (d in sellList) {
                val emoji = when {
                    d.urgency >= 9 -> "🚨"
                    d.urgency >= 7 -> "⚠️"
                    d.urgency >= 5 -> "📢"
                    else -> "🔔"
                }
                sb.appendLine("  $emoji ${d.order.stockName}(${d.order.stockCode.takeLast(6)})")
                sb.appendLine("    触发: ${d.strategy} | 盈亏: ${"%.2f".format(d.profitPct)}% | 卖出比例: ${(d.sellRatio * 100).toInt()}%")
                sb.appendLine("    原因: ${d.reason.take(80)}")
                if (d.technicalDetails.isNotEmpty()) {
                    d.technicalDetails.forEach { (k, v) -> sb.appendLine("    $k=$v") }
                }
                sb.appendLine()
            }
        }

        if (holdList.isNotEmpty()) {
            sb.appendLine("🟢 继续持有 (${holdList.size}个):")
            for (d in holdList) {
                sb.appendLine("  ✅ ${d.order.stockName}(${d.order.stockCode.takeLast(6)}) 盈亏: ${"%.2f".format(d.profitPct)}%")
                if (d.technicalDetails.isNotEmpty()) {
                    val tech = d.technicalDetails
                    sb.append("    MA5:${tech["ma5"] ?: "N/A"} MA20:${tech["ma20"] ?: "N/A"} RSI:${tech["rsi"] ?: "N/A"} ATR:${tech["atr"] ?: "N/A"}")
                    sb.appendLine()
                }
            }
        }

        showDialog("卖出评估报告", sb.toString())
    }

    /** 查看卖出策略历史绩效 */
    private fun showSellPerformance() {
        progressBar.visibility = View.VISIBLE; statusTv.text = "正在统计卖出绩效..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val te = tradeEngine ?: return@launch
                val stats = te.getSellPerformance(90)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE; statusTv.text = "✅ 卖出绩效已加载"
                    if (stats.isEmpty()) {
                        showDialog("卖出绩效", "暂无卖出记录，无法统计绩效")
                        return@withContext
                    }
                    val sb = StringBuilder()
                    sb.appendLine("📊 卖出策略绩效统计 (最近90天)"); sb.appendLine()
                    sb.appendLine("策略            卖出数  均收益   胜率    最大赢   最大亏   均持仓")
                    sb.appendLine("──────────────────────────────────────────────────────")
                    for (s in stats) {
                        val name = when (s.strategyName) {
                            "HardStop" -> "硬止损"
                            "MaxDrawdown" -> "最大回撤"
                            "TimeForceClose" -> "时间强平"
                            "TimeNoProgress" -> "时间无进展"
                            "TieredTP" -> "阶梯止盈"
                            "ChandelierExit" -> "吊灯止损"
                            "TrailProfit" -> "移动止盈"
                            "MADeathCross" -> "MA死叉"
                            "VolumeClimax" -> "放量滞涨"
                            "RSIOverbought" -> "RSI超买"
                            "SectorWeakness" -> "板块弱势"
                            "TakeProfit" -> "目标止盈"
                            "ATRStop" -> "ATR止损"
                            "MomentumDecay" -> "动量衰竭"
                            "Other" -> "其他"
                            else -> s.strategyName.take(6)
                        }
                        sb.appendLine("${name.padEnd(10)} ${s.totalSells.toString().padEnd(6)} ${"%.2f".format(s.avgProfitPct).padStart(7)}% ${"%.0f".format(s.winRate * 100).padStart(5)}% ${"%.2f".format(s.maxProfitPct).padStart(7)}% ${"%.2f".format(s.maxLossPct).padStart(7)}% ${"%.1f".format(s.avgDaysHeld).padStart(4)}天")
                    }
                    sb.appendLine(); sb.appendLine("💡 综合表现排名基于: 胜率 × 平均收益")
                    showDialog("卖出绩效", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 绩效统计失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 查看历史
    // ═══════════════════════════════════════

    private fun showTradeHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(100)
                val periodResults = db.dailyPeriodResultDao().getAvailableDates(30)
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("📋 交易记录 (最近100条)"); sb.appendLine()
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
                    sb.appendLine("📊 策略周期结果 (最近30天)")
                    sb.appendLine("可查看的交易日: ${periodResults.joinToString(", ").take(100)}...")
                    showDialog("交易记录", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ═══════════════════════════════════════
    // 拟合参数查看
    // ═══════════════════════════════════════

    private fun showFittingParams() {
        val eng = engine ?: return
        val strategies = eng.getStrategies()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
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

    private fun showDataMenu() {
        val exporter = DataExportImport(requireContext())
        val options = arrayOf(
            "📋 查看交易记录",
            "📊 中线量化报告 (历史)",
            "📊 查看精選池",
            "📤 导出交易数据 (CSV文本)",
            "📤 导出 JSON (全部数据)",
            "📤 导出 CSV (分表)",
            "📂 查看导出文件列表",
            "📥 导入 JSON 数据",
            "📊 数据库统计信息"
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("数据中心")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showTradeHistory()
                    1 -> showTradeReportsHistory()
                    2 -> showFinalPool()
                    3 -> exportTradeData()
                    4 -> exportToJson(exporter)
                    5 -> exportToCsv(exporter)
                    6 -> showExportFiles(exporter)
                    7 -> showImportDialog(exporter)
                    8 -> showDbStats(exporter)
                }
            }
            .setNegativeButton("关闭", null).show()
    }

    /** 查看历史中线量化报告 */
    private fun showTradeReportsHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val entities = db.dailyPeriodResultDao().getRecent(100)
                    .filter { it.strategyId != "FINAL_POOL" && it.strategyId != "BACKTRACK" }
                if (entities.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "暂无中线量化报告记录", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val grouped = entities.groupBy { it.tradeDate }
                val sb = StringBuilder()
                sb.appendLine("📊 中线量化报告历史 (共 ${entities.size} 条)")
                sb.appendLine()
                val codeToName = try { db.stockBasicDao().getAll().associate { it.code to it.name } } catch (_: Exception) { emptyMap() }
                for ((date, items) in grouped.toSortedMap().entries.reversed().take(10)) {
                    sb.appendLine("━━━ ${date} ━━━")
                    for (item in items) {
                        val top3Json = try {
                            JSONArray(item.finalTop3Json)
                        } catch (_: Exception) { JSONArray() }
                        val mainBoardLabel = if (item.mainBoardFilter) " 主板" else ""
                        sb.appendLine("  ${item.strategyName}[${item.periodDays}日]$mainBoardLabel: ${item.stockCount}只信号")
                        if (item.newsStrengthScore > 0) sb.appendLine("    新闻力度:${item.newsStrengthScore} 轮动惩罚:${item.rotationPenalty}")
                        // 显示失败原因（过滤原因）
                        try {
                            val reasonJson = JSONArray(item.filteredReasonJson)
                            if (reasonJson.length() > 0) {
                                val sampleReason = reasonJson.optJSONObject(0)
                                if (sampleReason != null) {
                                    sb.appendLine("    ⚠️ 过滤: ${sampleReason.optString("name")}(${sampleReason.optString("reason")}) 等${reasonJson.length()}只")
                                }
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

    private fun exportTradeData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(1000)
                val periodResults = db.dailyPeriodResultDao().getRecent(200)
                val sb = StringBuilder()
                sb.appendLine("中线量化数据导出"); sb.appendLine("导出时间: ${LocalDate.now()}"); sb.appendLine()
                sb.appendLine("=== 交易订单 ===")
                sb.appendLine("日期,策略,股票代码,股票名称,买入价,数量,状态,卖出价,收益%")
                for (order in orders) sb.appendLine("${order.tradeDate},${order.strategyId},${order.stockCode},${order.stockName},${order.buyPrice},${order.quantity},${order.status},${order.sellPrice},${order.profitPct}")
                sb.appendLine(); sb.appendLine("=== 策略周期结果 ===")
                for (result in periodResults) {
                    val top3 = try { val arr = JSONArray(result.finalTop3Json); (0 until arr.length()).joinToString(", ") { arr.optString(it, "") } } catch (_: Exception) { "" }
                    sb.appendLine("${result.tradeDate},${result.strategyName}[${result.periodDays}日],${result.stockCount}只,${result.newsStrengthScore},${result.rotationPenalty},${top3.take(100)}")
                }
                sb.appendLine(); sb.appendLine("=== 9步精選最終池 ===")
                val finalPoolEntities = db.dailyPeriodResultDao().getRecent(50).filter { it.strategyId == "FINAL_POOL" }
                for (entity in finalPoolEntities) {
                    val codes = try { JSONArray(entity.stockCodesJson).length() } catch (_: Exception) { 0 }
                    val crossDayJson = try { JSONArray(entity.finalTop3Json) } catch (_: Exception) { JSONArray() }
                    val crossDayStr = (0 until crossDayJson.length()).joinToString("; ") { i -> val obj = crossDayJson.getJSONObject(i); "${obj.optString("code").takeLast(6)}:${obj.optInt("days")}天" }
                    sb.appendLine("${entity.tradeDate},FINAL_POOL,${codes}隻,${crossDayStr}")
                }
                withContext(Dispatchers.Main) { showDialog("导出数据", sb.toString()); Toast.makeText(requireContext(), "数据已生成（可复制）", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun runNextDayBacktrack() {
        val eng = engine ?: return; val te = tradeEngine ?: return
        executeBtn.isEnabled = false; executeBtn.text = "⏳ 回溯中..."
        progressBar.visibility = View.VISIBLE; statusTv.text = "正在执行回溯复盘..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val buyingOrders = getBuyingOrders(db)
                val boughtStocks = buyingOrders.map { it.stockCode }.toSet()
                for (order in buyingOrders) {
                    val nextDate = getNextTradingDayFromDB(order.tradeDate) ?: continue
                    val nextDaySnaps = db.dailySnapshotDao().getByDate(nextDate)
                    val nextDayStock = nextDaySnaps.find { it.code == order.stockCode }
                    if (nextDayStock != null) {
                        val sellPrice = nextDayStock.close
                        val profitPct = (sellPrice - order.buyPrice) / order.buyPrice * 100
                        db.strategyTradeOrderDao().updateSellInfo(id = order.id, status = "SOLD", sellPrice = sellPrice, sellTime = "$nextDate 15:00", profitPct = profitPct)
                    }
                }
                val oldSessionResults = buyingOrders.map { it.tradeDate }.distinct().flatMap { db.dailyPeriodResultDao().getByDate(it) }
                val oldResults = oldSessionResults.mapNotNull { entity ->
                    if (entity.strategyId == "BACKTRACK") return@mapNotNull null
                    SimulationTradeEngine.StrategyPeriodResult(strategyId = entity.strategyId, strategyName = entity.strategyName, periodDays = entity.periodDays, tradeDate = entity.tradeDate, rawStockSignals = emptyList(), newsStrengthScore = entity.newsStrengthScore, rotationPenalty = entity.rotationPenalty, afterMainBoardFilter = emptyList(), filteredStocks = emptyList(), finalTop15 = try { val arr = JSONArray(entity.finalTop3Json); (0 until arr.length()).map { i -> val obj = arr.getJSONObject(i); com.chin.stockanalysis.strategy.models.StrategySignal(strategyId = entity.strategyId, category = com.chin.stockanalysis.strategy.StrategyCategory.CUSTOM, stockCode = obj.optString("code"), stockName = obj.optString("name"), strength = obj.optInt("score"), reason = obj.optString("reason"), action = com.chin.stockanalysis.strategy.models.SignalAction.BUY) } } catch (_: Exception) { emptyList() })
                }
                val tradeDates = buyingOrders.map { it.tradeDate }.distinct()
                val allReports = mutableListOf<SimulationTradeEngine.BacktrackReport>()
                for (tradeDate in tradeDates) {
                    val config = SimulationTradeEngine.TradeSessionConfig(tradeDate = tradeDate, periods = selectedPeriods.toList().sorted().ifEmpty { listOf(1) }, onlyMainBoard = mainBoardSwitch.isChecked, maxFitRounds = 100)
                    allReports.add(te.backtrackAndOptimize(strategies = strategies, config = config, oldSessionResults = oldResults.filter { it.tradeDate == tradeDate }, boughtStocks = boughtStocks))
                }
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("📈 回溯复盘优化报告"); sb.appendLine("回溯日期: ${LocalDate.now().format(DATE_FMT)}")
                    sb.appendLine("处理交易日: ${tradeDates.joinToString(", ")}"); sb.appendLine()
                    for (report in allReports) { sb.appendLine(report.summary); sb.appendLine() }
                    showDialog("回溯复盘", sb.toString())
                    executeBtn.isEnabled = true; executeBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE
                    statusTv.text = "✅ 回溯完成: ${allReports.sumOf { it.buyOrdersAnalyzed.size }}笔订单, ${allReports.sumOf { it.missedOpportunities.size }}个遗漏机会"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { executeBtn.isEnabled = true; executeBtn.text = "▶ 建仓"; progressBar.visibility = View.GONE; statusTv.text = "❌ 回溯失败: ${e.message?.take(50)}"; Toast.makeText(requireContext(), "回溯失败: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private suspend fun getBuyingOrders(db: StockDatabase): List<StrategyTradeOrderEntity> = db.strategyTradeOrderDao().getRecent(100).filter { it.status == "BUYING" }

    private suspend fun getNextTradingDayFromDB(date: String): String? = try {
        val db = StockDatabase.getInstance(requireContext()); val dates = db.dailySnapshotDao().getAvailableDates(10).sorted(); val idx = dates.indexOf(date); if (idx >= 0 && idx + 1 < dates.size) dates[idx + 1] else null
    } catch (_: Exception) { null }

    private fun exportToJson(exporter: DataExportImport) {
        lifecycleScope.launch(Dispatchers.IO) {
            try { val path = exporter.exportAllToJson(); withContext(Dispatchers.Main) { showDialog("导出成功", "文件已保存到:\n$path\n\n可以通过文件管理器复制到电脑/其他手机使用") } }
            catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }
    private fun exportToCsv(exporter: DataExportImport) {
        lifecycleScope.launch(Dispatchers.IO) {
            try { val files = exporter.exportAllToCsv(); val sb = StringBuilder(); sb.appendLine("CSV 文件已保存:"); for (f in files) sb.appendLine("- $f"); sb.appendLine("\n每个文件可以用 Excel 打开"); withContext(Dispatchers.Main) { showDialog("CSV导出成功", sb.toString()) } }
            catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }
    private fun showExportFiles(exporter: DataExportImport) {
        val files = exporter.getExportFiles()
        if (files.isEmpty()) { Toast.makeText(requireContext(), "暂无导出文件", Toast.LENGTH_SHORT).show(); return }
        val sb = StringBuilder(); sb.appendLine("📂 已导出的文件:"); sb.appendLine()
        for (f in files) { val sizeKb = f.length() / 1024; sb.appendLine("${f.name} (${sizeKb}KB)"); sb.appendLine("  修改时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))}"); sb.appendLine("  路径: ${f.absolutePath}"); sb.appendLine() }
        showDialog("导出文件列表", sb.toString())
    }
    private fun showImportDialog(exporter: DataExportImport) {
        val input = EditText(requireContext()).apply { hint = "输入JSON文件完整路径"; setSingleLine(); val exportDir = File(requireContext().getExternalFilesDir(null), "StockAnalysis_exports"); if (exportDir.exists()) { val files = exportDir.listFiles()?.filter { it.name.endsWith(".json") }; if (files?.isNotEmpty() == true) setText(files.first().absolutePath) } }
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("导入数据").setMessage("从JSON文件导入数据到数据库").setView(input).setPositiveButton("导入") { _, _ -> val path = input.text.toString().trim(); if (path.isNotEmpty()) doImport(exporter, path) else Toast.makeText(requireContext(), "请指定文件路径", Toast.LENGTH_SHORT).show() }.setNegativeButton("取消", null).show()
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

    private suspend fun saveBuyOrdersToDb(report: SimulationTradeEngine.TradeSessionReport) {
        if (report.buyOrders.isEmpty()) return
        val db = StockDatabase.getInstance(requireContext())
        try {
            // 同花顺模式：重复买入同一股票时，合并持仓（加权平均成本）
            val existingOrders = db.strategyTradeOrderDao().getRecent(200).filter { it.status == "BUYING" }
            val toInsert = mutableListOf<StrategyTradeOrderEntity>()
            var merged = 0
            for (order in report.buyOrders) {
                val existing = existingOrders.firstOrNull { it.stockCode == order.stockCode }
                if (existing != null) {
                    val totalQty = existing.quantity + order.quantity
                    val weightedAvgPrice = (existing.buyPrice * existing.quantity + order.buyPrice * order.quantity) / totalQty
                    db.strategyTradeOrderDao().updateBuyPriceAndQty(existing.id, weightedAvgPrice, totalQty, order.tradeDate)
                    Log.i("SimTradeFragment", "🔄 合并持仓: ${order.stockName} $${"%.2f".format(existing.buyPrice)}×${existing.quantity} → $${"%.2f".format(weightedAvgPrice)}×${totalQty}")
                    merged++
                } else {
                    toInsert.add(StrategyTradeOrderEntity(
                        strategyId = order.strategyId, stockCode = order.stockCode, stockName = order.stockName,
                        tradeDate = order.tradeDate, buyPrice = order.buyPrice, buyTime = order.buyTime,
                        quantity = order.quantity, orderType = order.orderType, status = "BUYING",
                        reason = order.reason, scoreAtBuy = order.scoreAtBuy, createdAt = System.currentTimeMillis()))
                }
            }
            if (toInsert.isNotEmpty()) db.strategyTradeOrderDao().insertAll(toInsert)
            Log.i("SimTradeFragment", "💾 新增${toInsert.size}笔, 合并${merged}笔")
        } catch (e: Exception) { Log.w("SimTradeFragment", "保存订单失败: ${e.message}") }
    }

    private fun refreshPositions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(50)
                    .filter { it.status == "BUYING" || it.status == "PENDING" }
                    .sortedByDescending { it.tradeDate }
                if (orders.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        positionContainer.removeAllViews()
                        positionContainer.addView(TextView(requireContext()).apply { text = "📌 暂无持仓记录"; textSize = 11f; setTextColor(Color.parseColor("#999999")); setPadding(0, 4, 0, 4) })
                    }; return@launch
                }
                val browsingDateStr = browsingDate.format(DATE_FMT)
                val dates = db.dailySnapshotDao().getAvailableDates(15)
                    .filter { it >= orders.minOf { it.tradeDate } && it <= browsingDateStr }
                    .sorted().takeLast(10)
                val priceMap = mutableMapOf<String, MutableMap<String, Double>>()
                for (date in dates) { val snaps = db.dailySnapshotDao().getByDate(date); for (snap in snaps) priceMap.getOrPut(snap.code) { mutableMapOf() }[date] = snap.close }
                withContext(Dispatchers.Main) { renderPositionTable(orders, dates, priceMap) }
            } catch (e: Exception) { Log.e("SimTradePos", "加载持仓失败: ${e.message}", e) }
        }
    }

    private fun renderPositionTable(orders: List<StrategyTradeOrderEntity>, dates: List<String>, priceMap: Map<String, Map<String, Double>>) {
        positionContainer.removeAllViews()

        // ── 持倉標題行（含匯總） ──
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
            text = "📌 持仓明细 (启动资金 ¥1,000,000)"; textSize = 12f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = "总持仓 ${orders.size} 只  |  "; textSize = 10f
            setTextColor(Color.parseColor("#666666")); gravity = Gravity.END
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = "总盈亏 $pnlStr"; textSize = 10f; setTextColor(Color.parseColor(pnlColor))
            gravity = Gravity.END
        })
        positionContainer.addView(titleRow)
        val scroll = HorizontalScrollView(requireContext())
        val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val headerRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 4); setBackgroundColor(Color.parseColor("#EEEEEE")) }
        for (header in listOf("股票", "建仓日", "成本")) headerRow.addView(createCell(header, 60, "#666666", 10f, bold = true))
        headerRow.addView(createCell("持仓/可用", 55, "#666666", 9f, bold = true))
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
            row.addView(createCell("${order.quantity}/100", 55, "#1565C0", 9f))
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
            // 賣出按鈕
            val lastPrice = priceMap[order.stockCode]?.get(dates.lastOrNull())
            val sellBtn = Button(requireContext()).apply {
                text = "賣"
                textSize = 9f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#C62828"))
                layoutParams = LinearLayout.LayoutParams(dpToPx(50), dpToPx(28))
                setPadding(2, 0, 2, 0)
                isEnabled = !clearMode && lastPrice != null
                setOnClickListener {
                    showSellConfirmDialog(order, lastPrice ?: order.buyPrice)
                }
            }
            row.addView(sellBtn)

            if (clearMode) {
                row.setBackgroundColor(Color.parseColor("#FFEBEE")); val orderDate = order.tradeDate
                row.setOnClickListener { selectedDateForClear = orderDate; statusTv.text = "✅ 已选中交易日: $orderDate — 再次点击「确认清除」按钮执行删除"; Toast.makeText(requireContext(), "已选中: $orderDate", Toast.LENGTH_SHORT).show(); refreshPositions() }
            } else {
                row.setBackgroundColor(Color.TRANSPARENT)
                // 長按顯示擬合報告（替代原來的單擊）
                row.setOnLongClickListener {
                    statusTv.text = "⏳ 正在拟合 ${order.stockName}..."
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val eng = engine ?: return@launch; val te = tradeEngine ?: return@launch
                            val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                            val config = SimulationTradeEngine.TradeSessionConfig(tradeDate = order.tradeDate, periods = selectedPeriods.toList().sorted().ifEmpty { listOf(1) }, onlyMainBoard = true, maxFitRounds = 50)
                            val report = te.backtrackAndOptimize(strategies = strategies, config = config, oldSessionResults = emptyList(), boughtStocks = setOf(order.stockCode))
                            withContext(Dispatchers.Main) { showDialog("${order.stockName} 拟合报告", report.summary); statusTv.text = "✅ 拟合完成"; refreshPositions() }
                        } catch (e: Exception) { withContext(Dispatchers.Main) { statusTv.text = "❌ 拟合失败: ${e.message?.take(40)}" } }
                    }
                    true
                }
            }
            table.addView(row)
        }
        scroll.addView(table)
        val verticalScroll = ScrollView(requireContext()); verticalScroll.addView(scroll); positionContainer.addView(verticalScroll)
    }

    private fun createCell(text: String, widthDp: Int, colorHex: String, fontSize: Float, bold: Boolean = false): TextView =
        TextView(requireContext()).apply { this.text = text; textSize = fontSize; setTextColor(Color.parseColor(colorHex)); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dpToPx(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT); setPadding(2, 4, 2, 4); if (bold) setTypeface(null, Typeface.BOLD) }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 顯示單筆賣出確認對話框
     */
    private fun showSellConfirmDialog(order: StrategyTradeOrderEntity, currentPrice: Double) {
        val profitPct = if (order.buyPrice > 0) (currentPrice - order.buyPrice) / order.buyPrice * 100 else 0.0
        val profitStr = if (profitPct >= 0) "+${"%.2f".format(profitPct)}%" else "${"%.2f".format(profitPct)}%"
        val profitColor = if (profitPct >= 0) "#D32F2F" else "#2E7D32"

        val message = """
            股票: ${order.stockName} (${order.stockCode})
            買入價: ¥${"%.2f".format(order.buyPrice)}
            當前價: ¥${"%.2f".format(currentPrice)}
            盈虧: $profitStr
            數量: ${order.quantity} 股
            
            確認賣出？
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
    private fun executeSingleSell(order: StrategyTradeOrderEntity, sellPrice: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val dao = db.strategyTradeOrderDao()
                val profitPct = (sellPrice - order.buyPrice) / order.buyPrice * 100

                dao.updateSellInfo(
                    id = order.id,
                    status = "SOLD",
                    sellPrice = sellPrice,
                    sellTime = java.time.LocalDate.now().toString() + " " + java.time.LocalTime.now().toString().take(8),
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
                    Toast.makeText(requireContext(), "賣出失敗", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleClearClick() { if (clearMode && selectedDateForClear != null) confirmAndClear() else enterClearMode() }

    private fun enterClearMode() {
        clearMode = true; selectedDateForClear = null; clearBtn.text = "🗑️ 确认清除"
        clearBtn.setBackgroundColor(Color.parseColor("#B71C1C")); statusTv.text = "📌 请点击持仓行选中要清除的交易日"
        Toast.makeText(requireContext(), "请点击持仓中的建仓日以选中交易日", Toast.LENGTH_SHORT).show(); refreshPositions()
    }

    private fun confirmAndClear() {
        val date = selectedDateForClear ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getByDate(date); val orderCount = orders.size
                val periodResults = db.dailyPeriodResultDao().getByDate(date); val resultCount = periodResults.size
                val snaps = db.dailySnapshotDao().getByDate(date); val snapCount = snaps.size
                db.strategyTradeOrderDao().deleteByDate(date); db.dailyPeriodResultDao().deleteByDate(date)
                if (snapCount > 0) { val nextDay = java.time.LocalDate.parse(date).plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")); db.dailySnapshotDao().deleteOlderThan(nextDay) }
                val afterSnaps = db.dailySnapshotDao().getByDate(date); val deletedSnapCount = snapCount - afterSnaps.size
                val deletedCount = orderCount + resultCount + deletedSnapCount
                Log.i("SimTradeFragment", "🗑️ 已清除 $date 的数据: 订单=${orderCount}, 周期结果=${resultCount}, 快照=${deletedSnapCount}")
                withContext(Dispatchers.Main) { exitClearMode(); refreshPositions(); statusTv.text = "✅ 已清除 $date 数据 ($deletedCount 条)"; Toast.makeText(requireContext(), "已清除 $date ($deletedCount 条记录)", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { exitClearMode(); statusTv.text = "❌ 清除失败: ${e.message?.take(40)}"; Toast.makeText(requireContext(), "清除失败: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }

    private fun exitClearMode() { clearMode = false; selectedDateForClear = null; clearBtn.text = "🗑️ 清除"; clearBtn.setBackgroundColor(Color.parseColor("#C62828")) }

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

    private fun showDialog(title: String, content: String) {
        val sv = ScrollView(requireContext())
        sv.addView(TextView(requireContext()).apply { text = content; textSize = 10f; setTextColor(Color.parseColor("#333333")); setPadding(16, 12, 16, 12); setLineSpacing(2f, 1.1f); setTypeface(Typeface.MONOSPACE) })
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(title).setView(sv).setPositiveButton("关闭", null).create().apply { show(); window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
    }
}