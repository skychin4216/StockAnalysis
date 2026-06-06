package com.chin.stockanalysis.strategy.trade

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
 * ## 模拟交易面板
 *
 * 在策略页面新增"模拟交易"Tab
 * 提供：
 * 1. 选择交易日
 * 2. 勾选使用的策略
 * 3. 选择数据周期（当日/近3/10/30/50/100日）
 * 4. 主板过滤开关
 * 5. AI精选 Top3
 * 6. 执行模拟买入/卖出
 * 7. 查看历史交易记录
 * 8. 调优拟合参数查看
 * 9. 交易记录导出
 */
class SimulationTradeFragment : Fragment() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var executeBtn: Button
    private lateinit var dateLabelTv: TextView
    private lateinit var datePicker: TradingDayPickerView
    private lateinit var fittingBtn: Button
    private lateinit var periodCheckboxes: LinearLayout
    private lateinit var mainBoardSwitch: Switch
    private lateinit var resultsContainer: LinearLayout

    private var engine: StrategyEngine? = null
    private var screener: StockScreener? = null
    private var browsingDate: LocalDate = TradingDayPickerView.recentTradingDay()
    private var selectedPeriods: Set<Int> = setOf(1)
    private var tradeEngine: SimulationTradeEngine? = null

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
        val repo = StockDataSourceFactory.createDefaultRepository(ctx)
        screener = StockScreener(repo)
        engine = StrategyEngine(ctx, screener!!).apply {
            registerStrategy(MovingAverageStrategy(screener!!))
            registerStrategy(VolumeBreakStrategy(screener!!))
            registerStrategy(LowValuationStrategy(screener!!))
            registerStrategy(GapUpMomentumStrategy(screener!!))
            registerStrategy(TurnoverFilterStrategy(screener!!))
            registerStrategy(com.chin.stockanalysis.strategy.strategies.EarlyMorningChaseStrategy(screener!!))
            registerStrategy(TailLowPickStrategy(screener!!))
        }
        tradeEngine = SimulationTradeEngine(ctx)
    }

    private fun buildUI() {
        // 标题
        rootLayout.addView(TextView(requireContext()).apply {
            text = "🤖 模拟交易系统 v1.0"
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

        // 操作按钮区
        rootLayout.addView(createActionButtons())

        // 分割线
        rootLayout.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 8 }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        // 结果区
        resultsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            setPadding(8, 4, 8, 4)
        }
        rootLayout.addView(resultsContainer)
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

        // 第一行：交易日 + 主板过滤
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

        // 第二行：周期选择标签
        container.addView(TextView(requireContext()).apply {
            text = "📊 数据周期:"
            textSize = 11f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 4, 0, 2)
        })

        // 第三行：周期选项
        periodCheckboxes = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        for ((period, label) in PERIOD_LABELS) {
            val cb = CheckBox(requireContext()).apply {
                text = label
                textSize = 11f
                isChecked = period in selectedPeriods
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedPeriods = selectedPeriods + period
                    else selectedPeriods = selectedPeriods - period
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = -4; marginStart = -4 }
            }
            periodCheckboxes.addView(cb)
        }
        container.addView(periodCheckboxes)

        return container
    }

    private fun createActionButtons(): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6)
        }

        executeBtn = Button(requireContext()).apply {
            text = "▶ 模拟交易"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E65100"))
            setPadding(10, 12, 10, 12)
            setMinWidth(0)
            setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f).apply { marginEnd = 4 }
            setOnClickListener { executeTrade() }
        }
        row.addView(executeBtn)

        fittingBtn = Button(requireContext()).apply {
            text = "🔧 拟合"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#EF6C00"))
            setPadding(8, 12, 8, 12)
            setMinWidth(0)
            setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 }
            setOnClickListener { showFittingParams() }
        }
        row.addView(fittingBtn)

        // "回溯"按钮
        val backtrackBtn = Button(requireContext()).apply {
            text = "📈 回溯"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7B1FA2"))
            setPadding(8, 12, 8, 12)
            setMinWidth(0)
            setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 }
            setOnClickListener { runNextDayBacktrack() }
        }
        row.addView(backtrackBtn)

        // "数据"按钮（记录/导出/导入/统计）
        val dataBtn = Button(requireContext()).apply {
            text = "🗄️ 数据"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#455A64"))
            setPadding(8, 12, 8, 12)
            setMinWidth(0)
            setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showDataMenu() }
        }
        row.addView(dataBtn)

        return row
    }

    // ═══════════════════════════════════════
    // 核心功能
    // ═══════════════════════════════════════

    private fun executeTrade() {
        val eng = engine ?: return
        if (selectedPeriods.isEmpty()) {
            Toast.makeText(requireContext(), "请至少选择一个周期", Toast.LENGTH_SHORT).show()
            return
        }

        executeBtn.isEnabled = false
        executeBtn.text = "⏳ 执行中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在执行模拟交易..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val config = SimulationTradeEngine.TradeSessionConfig(
                    tradeDate = browsingDate.format(DATE_FMT),
                    periods = selectedPeriods.toList().sorted(),
                    onlyMainBoard = mainBoardSwitch.isChecked,
                    maxFitRounds = 200 // 为了效率先设为200轮
                )
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }

                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                    statusTv.text = "没有启用的策略"
                        executeBtn.isEnabled = true
                        executeBtn.text = "▶ 模拟交易"
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                val report = tradeEngine!!.runTradeSession(strategies, config)

                withContext(Dispatchers.Main) {
                    showTradeReport(report)
                    executeBtn.isEnabled = true
                    executeBtn.text = "▶ 模拟交易"
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 完成: ${report.summary.lines().firstOrNull()?.take(60) ?: "交易完成"}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 执行失败: ${e.message?.take(50)}"
                    executeBtn.isEnabled = true
                    executeBtn.text = "▶ 模拟交易"
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "执行失败: ${e.message?.take(100)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTradeReport(report: SimulationTradeEngine.TradeSessionReport) {
        resultsContainer.removeAllViews()

        // 添加报告内容（可滚动）
        val scrollView = ScrollView(requireContext())
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        // 摘要
        content.addView(TextView(requireContext()).apply {
            text = report.summary
            textSize = 10f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(2f, 1.1f)
            setTypeface(Typeface.MONOSPACE)
        })

        // AI精选
        if (report.aiTop3.isNotEmpty()) {
            content.addView(TextView(requireContext()).apply {
                text = "\n🤖 AI精选 Top3"
                textSize = 15f
                setTextColor(Color.parseColor("#1565C0"))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 12, 0, 8)
            })

            for (pick in report.aiTop3) {
                val pickCard = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 8, 12, 8)
                    setBackgroundColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 6 }
                    setOnClickListener {
                        // 跳转到股票详情
                        val detail = com.chin.stockanalysis.ui.StockDetailFragment.newInstance(
                            pick.stockCode, pick.stockName, 0.0, 0.0, ""
                        )
                        activity?.supportFragmentManager?.beginTransaction()
                            ?.replace(android.R.id.content, detail)
                            ?.addToBackStack(null)
                            ?.commit()
                    }
                }

                pickCard.addView(TextView(requireContext()).apply {
                    text = "#${pick.rank} ${pick.stockName} (${pick.stockCode.takeLast(6)})"
                    textSize = 14f
                    setTextColor(Color.parseColor("#1A1A2E"))
                    setTypeface(null, Typeface.BOLD)
                })

                val scoreColor = when {
                    pick.compositeScore >= 75 -> Color.parseColor("#E65100")
                    pick.compositeScore >= 60 -> Color.parseColor("#2E7D32")
                    else -> Color.parseColor("#666666")
                }

                pickCard.addView(TextView(requireContext()).apply {
                    text = "综合评分: ${pick.compositeScore}% | 上涨概率: ${pick.upProbability}% | 建议: ${pick.actionSuggestion}"
                    textSize = 12f
                    setTextColor(scoreColor)
                    setPadding(0, 4, 0, 2)
                })

                pickCard.addView(TextView(requireContext()).apply {
                    text = "理由: ${pick.reason.take(150)}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#666666"))
                })

                content.addView(pickCard)
            }
        }

        // 买入建议
        if (report.buyOrders.isNotEmpty()) {
            content.addView(TextView(requireContext()).apply {
                text = "\n📝 买入建议"
                textSize = 15f
                setTextColor(Color.parseColor("#E65100"))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 12, 0, 8)
            })

            for (order in report.buyOrders) {
                val orderCard = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12, 8, 12, 8)
                    setBackgroundColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4 }
                }

                orderCard.addView(TextView(requireContext()).apply {
                    text = order.stockName
                    textSize = 13f
                    setTextColor(Color.parseColor("#1A1A2E"))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                orderCard.addView(TextView(requireContext()).apply {
                    text = "¥${"%.2f".format(order.buyPrice)}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#E53935"))
                    setTypeface(null, Typeface.BOLD)
                    setPadding(8, 0, 8, 0)
                })

                orderCard.addView(TextView(requireContext()).apply {
                    text = "x${order.quantity}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#666666"))
                })

                content.addView(orderCard)

                // 保存交易订单到数据库
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val entity = StrategyTradeOrderEntity(
                            strategyId = order.strategyId,
                            stockCode = order.stockCode,
                            stockName = order.stockName,
                            tradeDate = order.tradeDate,
                            buyPrice = order.buyPrice,
                            buyTime = order.buyTime,
                            quantity = order.quantity,
                            orderType = order.orderType,
                            status = "BUYING",
                            reason = order.reason,
                            scoreAtBuy = order.scoreAtBuy
                        )
                        db.strategyTradeOrderDao().insert(entity)
                    } catch (_: Exception) {}
                }
            }
        }

        scrollView.addView(content)
        resultsContainer.addView(scrollView)
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
                    sb.appendLine("📋 交易记录 (最近100条)")
                    sb.appendLine()

                    if (orders.isEmpty()) {
                        sb.appendLine("暂无交易记录")
                    } else {
                        for (order in orders) {
                            val statusEmoji = when (order.status) {
                                "SOLD" -> "✅"
                                "BUYING" -> "🟢"
                                "FAILED" -> "❌"
                                else -> "⏳"
                            }
                            sb.appendLine("$statusEmoji ${order.stockName}(${order.stockCode.takeLast(6)})")
                            sb.appendLine("   买入: ${order.tradeDate} ¥${"%.2f".format(order.buyPrice)} x${order.quantity}")
                            if (order.status == "SOLD") {
                                val profitStr = if (order.profitPct >= 0) "+${"%.2f".format(order.profitPct)}%" else "${"%.2f".format(order.profitPct)}%"
                                sb.appendLine("   卖出: ¥${"%.2f".format(order.sellPrice)} 收益: $profitStr")
                            } else {
                                sb.appendLine("   状态: ${order.status}")
                            }
                            sb.appendLine()
                        }
                    }

                    sb.appendLine("📊 策略周期结果 (最近30天)")
                    sb.appendLine("可查看的交易日: ${periodResults.joinToString(", ").take(100)}...")

                    showDialog("交易记录", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
                sb.appendLine("🔧 调优拟合参数")
                sb.appendLine()

                for (strategy in strategies) {
                    sb.appendLine("【${strategy.name}】")
                    val params = db.strategyTradeFittingParamDao()
                        .getRecentByStrategy(strategy.id, 50)
                    if (params.isEmpty()) {
                        sb.appendLine("  暂无拟合数据")
                    } else {
                        // 按周期分组
                        val byPeriod = params.groupBy { it.periodDays }
                        for ((period, items) in byPeriod) {
                            val best = items.maxByOrNull { it.accuracy }
                            val worst = items.minByOrNull { it.accuracy }
                            sb.appendLine("  [${period}日] ${items.size}条")
                            if (best != null) {
                                sb.appendLine("    最佳: 准确率${"%.2f".format(best.accuracy * 100)}% 平均收益${"%.2f".format(best.avgReturn)}%")
                            }
                            if (worst != null) {
                                sb.appendLine("    最差: 准确率${"%.2f".format(worst.accuracy * 100)}% 平均收益${"%.2f".format(worst.avgReturn)}%")
                            }
                        }
                    }
                    sb.appendLine()
                }

                withContext(Dispatchers.Main) {
                    showDialog("拟合参数", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 导出数据
    // ═══════════════════════════════════════

    private fun exportTradeData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val orders = db.strategyTradeOrderDao().getRecent(1000)
                val periodResults = db.dailyPeriodResultDao().getRecent(200)

                val sb = StringBuilder()
                sb.appendLine("模拟交易数据导出")
                sb.appendLine("导出时间: ${LocalDate.now()}")
                sb.appendLine()

                sb.appendLine("=== 交易订单 ===")
                sb.appendLine("日期,策略,股票代码,股票名称,买入价,数量,状态,卖出价,收益%")
                for (order in orders) {
                    sb.appendLine("${order.tradeDate},${order.strategyId},${order.stockCode},${order.stockName},${order.buyPrice},${order.quantity},${order.status},${order.sellPrice},${order.profitPct}")
                }

                sb.appendLine()
                sb.appendLine("=== 策略周期结果 ===")
                for (result in periodResults) {
                    val top3 = try {
                        val arr = JSONArray(result.finalTop3Json)
                        (0 until arr.length()).joinToString(", ") { arr.optString(it, "") }
                    } catch (_: Exception) { "" }
                    sb.appendLine("${result.tradeDate},${result.strategyName}[${result.periodDays}日],${result.stockCount}只,${result.newsStrengthScore},${result.rotationPenalty},${top3.take(100)}")
                }

                // 显示导出内容
                withContext(Dispatchers.Main) {
                    showDialog("导出数据", sb.toString())
                    Toast.makeText(requireContext(), "数据已生成（可复制）", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 次日回溯 - 检查昨日买入股票的涨跌
    // ═══════════════════════════════════════

    /**
     * 运行次日回溯复盘
     * 
     * 增强功能：
     * 1. 检查所有 BUYING 订单在次日的涨跌，自动更新卖出状态
     * 2. 对亏损订单：重新执行全部策略筛选，分析落选股中次日表现优异的
     * 3. 分析过滤原因并重新调优拟合参数
     * 4. 将优化参数和数据固化到数据库
     */
    private fun runNextDayBacktrack() {
        val eng = engine ?: return
        val te = tradeEngine ?: return

        executeBtn.isEnabled = false
        executeBtn.text = "⏳ 回溯中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在执行回溯复盘..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }

                // 获取所有 BUYING 状态的订单
                val buyingOrders = getBuyingOrders(db)
                val boughtStocks = buyingOrders.map { it.stockCode }.toSet()

                // 更新每个订单的卖出状态
                for (order in buyingOrders) {
                    val nextDate = getNextTradingDayFromDB(order.tradeDate)
                    if (nextDate == null) continue
                    val nextDaySnaps = db.dailySnapshotDao().getByDate(nextDate)
                    val nextDayStock = nextDaySnaps.find { it.code == order.stockCode }
                    if (nextDayStock != null) {
                        val sellPrice = nextDayStock.close
                        val profitPct = (sellPrice - order.buyPrice) / order.buyPrice * 100
                        db.strategyTradeOrderDao().updateSellInfo(
                            id = order.id,
                            status = "SOLD",
                            sellPrice = sellPrice,
                            sellTime = "$nextDate 15:00",
                            profitPct = profitPct
                        )
                    }
                }

                // 获取原始交易会话的结果（用于对比落选股）
                val oldSessionResults = buyingOrders
                    .map { it.tradeDate }
                    .distinct()
                    .flatMap { db.dailyPeriodResultDao().getByDate(it) }

                // 构建旧结果对象
                val oldResults = oldSessionResults.mapNotNull { entity ->
                    if (entity.strategyId == "BACKTRACK") return@mapNotNull null
                    SimulationTradeEngine.StrategyPeriodResult(
                        strategyId = entity.strategyId,
                        strategyName = entity.strategyName,
                        periodDays = entity.periodDays,
                        tradeDate = entity.tradeDate,
                        rawStockSignals = emptyList(),
                        newsStrengthScore = entity.newsStrengthScore,
                        rotationPenalty = entity.rotationPenalty,
                        afterMainBoardFilter = emptyList(),
                        filteredStocks = emptyList(),
                        finalTop3 = try {
                            val arr = JSONArray(entity.finalTop3Json)
                            (0 until arr.length()).map { i ->
                                val obj = arr.getJSONObject(i)
                                com.chin.stockanalysis.strategy.models.StrategySignal(
                                    strategyId = entity.strategyId,
                                    category = com.chin.stockanalysis.strategy.StrategyCategory.CUSTOM,
                                    stockCode = obj.optString("code"),
                                    stockName = obj.optString("name"),
                                    strength = obj.optInt("score"),
                                    reason = obj.optString("reason"),
                                    action = com.chin.stockanalysis.strategy.models.SignalAction.BUY
                                )
                            }
                        } catch (_: Exception) { emptyList() }
                    )
                }

                // 按交易日分组执行回溯
                val tradeDates = buyingOrders.map { it.tradeDate }.distinct()
                val allReports = mutableListOf<SimulationTradeEngine.BacktrackReport>()

                for (tradeDate in tradeDates) {
                    val config = SimulationTradeEngine.TradeSessionConfig(
                        tradeDate = tradeDate,
                        periods = selectedPeriods.toList().sorted().ifEmpty { listOf(1) },
                        onlyMainBoard = mainBoardSwitch.isChecked,
                        maxFitRounds = 100
                    )
                    val report = te.backtrackAndOptimize(
                        strategies = strategies,
                        config = config,
                        oldSessionResults = oldResults.filter { it.tradeDate == tradeDate },
                        boughtStocks = boughtStocks
                    )
                    allReports.add(report)
                }

                // 汇总显示
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("📈 回溯复盘优化报告")
                    sb.appendLine("回溯日期: ${LocalDate.now().format(DATE_FMT)}")
                    sb.appendLine("处理交易日: ${tradeDates.joinToString(", ")}")
                    sb.appendLine()

                    for (report in allReports) {
                        sb.appendLine(report.summary)
                        sb.appendLine()
                    }

                    showDialog("回溯复盘", sb.toString())
                    executeBtn.isEnabled = true
                    executeBtn.text = "▶ 模拟交易"
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 回溯完成: ${allReports.sumOf { it.buyOrdersAnalyzed.size }}笔订单, " +
                            "${allReports.sumOf { it.missedOpportunities.size }}个遗漏机会"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    executeBtn.isEnabled = true
                    executeBtn.text = "▶ 模拟交易"
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 回溯失败: ${e.message?.take(50)}"
                    Toast.makeText(requireContext(), "回溯失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun getBuyingOrders(db: StockDatabase): List<StrategyTradeOrderEntity> {
        val all = db.strategyTradeOrderDao().getRecent(100)
        return all.filter { it.status == "BUYING" }
    }

    private suspend fun getNextTradingDayFromDB(date: String): String? {
        return try {
            val db = StockDatabase.getInstance(requireContext())
            val dates = db.dailySnapshotDao().getAvailableDates(10)
            val sorted = dates.sorted()
            val idx = sorted.indexOf(date)
            if (idx >= 0 && idx + 1 < sorted.size) sorted[idx + 1] else null
        } catch (_: Exception) { null }
    }

    // ═══════════════════════════════════════
    // 导入/导出
    // ═══════════════════════════════════════

    private fun showDataMenu() {
        val exporter = DataExportImport(requireContext())

        val options = arrayOf(
            "📋 查看交易记录",
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
                    1 -> exportTradeData()
                    2 -> exportToJson(exporter)
                    3 -> exportToCsv(exporter)
                    4 -> showExportFiles(exporter)
                    5 -> showImportDialog(exporter)
                    6 -> showDbStats(exporter)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun exportToJson(exporter: DataExportImport) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val path = exporter.exportAllToJson()
                withContext(Dispatchers.Main) {
                    showDialog("导出成功", "文件已保存到:\n$path\n\n可以通过文件管理器复制到电脑/其他手机使用")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun exportToCsv(exporter: DataExportImport) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val files = exporter.exportAllToCsv()
                val sb = StringBuilder()
                sb.appendLine("CSV 文件已保存:")
                for (f in files) {
                    sb.appendLine("- $f")
                }
                sb.appendLine("\n每个文件可以用 Excel 打开")
                withContext(Dispatchers.Main) {
                    showDialog("CSV导出成功", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showExportFiles(exporter: DataExportImport) {
        val files = exporter.getExportFiles()
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), "暂无导出文件", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.appendLine("📂 已导出的文件:")
        sb.appendLine()
        for (f in files) {
            val sizeKb = f.length() / 1024
            sb.appendLine("${f.name} (${sizeKb}KB)")
            sb.appendLine("  修改时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))}")
            sb.appendLine("  路径: ${f.absolutePath}")
            sb.appendLine()
        }
        showDialog("导出文件列表", sb.toString())
    }

    private fun showImportDialog(exporter: DataExportImport) {
        val input = EditText(requireContext()).apply {
            hint = "输入JSON文件完整路径"
            setSingleLine()
            // 默认显示导出目录路径
            val exportDir = File(requireContext().getExternalFilesDir(null), "StockAnalysis_exports")
            if (exportDir.exists()) {
                val files = exportDir.listFiles()?.filter { it.name.endsWith(".json") }
                if (files?.isNotEmpty() == true) {
                    setText(files.first().absolutePath)
                }
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("导入数据")
            .setMessage("从JSON文件导入数据到数据库")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    doImport(exporter, path)
                } else {
                    Toast.makeText(requireContext(), "请指定文件路径", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doImport(exporter: DataExportImport, path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val report = exporter.importFromJson(path)
                withContext(Dispatchers.Main) {
                    if (report.success) {
                        showDialog("导入成功", report.message)
                    } else {
                        Toast.makeText(requireContext(), "导入失败: ${report.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "导入异常: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showDbStats(exporter: DataExportImport) {
        lifecycleScope.launch(Dispatchers.IO) {
            val stats = exporter.getDatabaseStats()
            withContext(Dispatchers.Main) {
                showDialog("数据库统计", stats)
            }
        }
    }

    // ═══════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════

    private fun showDialog(title: String, content: String) {
        val sv = ScrollView(requireContext())
        sv.addView(TextView(requireContext()).apply {
            text = content
            textSize = 10f
            setTextColor(Color.parseColor("#333333"))
            setPadding(16, 12, 16, 12)
            setLineSpacing(2f, 1.1f)
            setTypeface(Typeface.MONOSPACE)
        })
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .create().apply {
                show()
                window?.setLayout(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
    }
}
