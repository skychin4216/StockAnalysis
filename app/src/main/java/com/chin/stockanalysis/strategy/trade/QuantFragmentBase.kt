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
import com.chin.stockanalysis.strategy.topology.core.orderTypePeriod
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 量化交易 Fragment 基类
 *
 * 为中线量化（MidTermQuant）和短线量化（ShortTermQuant）提供共用功能：
 * - 统一按钮行：建仓 → 持仓 → 回溯 → 拟合 → 卖出 → 数据
 * - 卖出评估与执行（使用 AutoSellEngine）
 * - 数据管理与清除
 * - 持仓刷新与显示
 */
abstract class QuantFragmentBase : Fragment() {

    // ═══════════════════════════════════════════════════
    // 子类可覆写的配置
    // ═══════════════════════════════════════════════════

    /** 是否显示多日价格列（子类可覆写） */
    protected open val showMultiDayPrices: Boolean = true

    /** 持仓标题前缀（如 "短线量化" / "中线量化"） */
    protected open val positionTitlePrefix: String = ""

    // ═══════════════════════════════════════════════════
    // UI 组件
    // ═══════════════════════════════════════════════════

    /** 根布局 */
    protected lateinit var rootLayout: LinearLayout

    /** 状态文字 */
    protected lateinit var statusTv: TextView

    /** 进度条 */
    protected lateinit var progressBar: ProgressBar

    /** 持仓容器 */
    protected lateinit var positionContainer: LinearLayout

    /** 建仓按钮 */
    protected lateinit var buildBtn: Button

    /** 清除按钮 */
    protected lateinit var clearBtn: Button

    /** 做T按钮（显示待处理推荐数） */
    protected lateinit var tTradeBtn: Button

    // ═══════════════════════════════════════════════════
    // 引擎与数据
    // ═══════════════════════════════════════════════════

    protected var engine: StrategyEngine? = null
    protected var browsingDate: LocalDate = TradingDayPickerView.recentTradingDay()

    /** 卖出决策缓存 */
    protected var sellDecisionsCache: List<AutoSellEngine.SellDecision> = emptyList()

    /** 清除模式标记 */
    protected var clearMode: Boolean = false
    protected var selectedDateForClear: String? = null

    /** 最近一次 Pipeline 选股结果（用于非交易时间显示「选股」区块） */
    protected var lastPickStocks: List<Triple<String, String, Int>> = emptyList()
    /** 选股中的股票代码集合（从持仓区排除，避免重叠） */
    protected var pickStockCodes: Set<String> = emptySet()

    /** 上次刷新时的数据哈希，用于判断是否需要重新渲染 */
    protected var lastRefreshHash: Int = 0

    companion object {
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val TAG = "QuantFragmentBase"
    }

    // ═══════════════════════════════════════════════════
    // 生命周期
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
        updateBuildButtonText()
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
    // 抽象方法（子类必须实现）
    // ═══════════════════════════════════════════════════

    /** 返回量化类型："ShortTermQuant" 或 "MidTermQuant" */
    abstract fun getQuantType(): String

    /** 返回 watchlist 中的 source 值（与 runDagPipeline 的 orderType 参数一致） */
    protected fun getWatchlistSource(): String = when (getQuantType()) {
        "UltraShortQuant" -> "ultra_short"
        "ShortTermQuant" -> "shortterm"
        "MidTermQuant" -> "midterm"
        "LongTermQuant" -> "long_term"
        else -> getQuantType()
    }

    /** 建仓按钮点击 — 各子类实现自己的选股逻辑 */
    abstract fun onBuildClick()

    /** 拟合按钮点击 */
    abstract fun onFittingClick()

    /** 回溯按钮点击 */
    abstract fun onBacktrackClick()

    /** 清除按钮点击 */
    abstract fun onClearClick()

    /** 加载持仓（默认调用 refreshPositions，子类可覆写） */
    open fun loadPositions() = refreshPositions()

    // ═══════════════════════════════════════════════════
    // UI 辅助方法
    // ═══════════════════════════════════════════════════

    /**
     * 统一按钮行（v15）：📈建仓 | 🔀Pipeline | 💰买卖评估 ▾ | 📦持仓 | 📊报告
     * 宽度规则：1汉字=2单位，1英文=1单位，▾=1单位。emoji 不计入宽度。
     * 原「数据」多级菜单拆分为「持仓」和「报告」两个直达按钮。
     */
    protected fun createButtonRow(): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 1, 4, 1)
        }

        // ── 1. 📈建仓（2汉字=4单位，权重4） ──
        buildBtn = Button(requireContext()).apply {
            text = getString(com.chin.stockanalysis.R.string.btn_build_position)
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E65100"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 4.0f).apply { marginEnd = 1 }
            setOnClickListener { onBuildClick() }
        }
        row.addView(buildBtn)

        // ── 2. 🔀Pipeline（8英文=8单位，视觉偏长故降至5） ──
        val pipelineBtn = Button(requireContext()).apply {
            text = "🔀Pipeline"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6A1B9A"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 5.0f).apply { marginEnd = 1 }
            setOnClickListener { openPipelineEditor() }
        }
        row.addView(pipelineBtn)

        // ── 3. 💰买卖评估 ▾（4汉字+▾=9单位，权重5） ──
        tTradeBtn = Button(requireContext()).apply {
            text = getString(com.chin.stockanalysis.R.string.btn_trade_eval)
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#BF360C"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 5.0f).apply { marginEnd = 1 }
            setOnClickListener { showTradeEvaluationMenu(it) }
        }
        row.addView(tTradeBtn)

        // ── 4. 📦持仓（2汉字=4单位，权重4） ──
        val holdingBtn = Button(requireContext()).apply {
            text = getString(com.chin.stockanalysis.R.string.btn_holdings_label)
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 4.0f).apply { marginEnd = 1 }
            setOnClickListener { showHoldingMenu() }
        }
        row.addView(holdingBtn)

        // ── 5. 📊报告（2汉字=4单位，权重4） ──
        val reportBtn = Button(requireContext()).apply {
            text = getString(com.chin.stockanalysis.R.string.btn_report_label)
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#455A64"))
            setPadding(4, 1, 4, 1)
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(22), 4.0f)
            setOnClickListener { showReportMenu() }
        }
        row.addView(reportBtn)

        return row
    }

    /** 根据 A 股交易时段更新建仓按钮文字（交易时间=建仓，非交易时间=选股） */
    protected fun updateBuildButtonText() {
        val isTrading = com.chin.stockanalysis.stock.database.ChinaMarketTradingHours.a股是否交易中()
        buildBtn?.text = if (isTrading)
            getString(com.chin.stockanalysis.R.string.btn_build_position)
        else
            getString(com.chin.stockanalysis.R.string.btn_stock_picking)
    }

    override fun onResume() {
        super.onResume()
        updateBuildButtonText()
    }

    /** 持仓菜单（合并原「持仓与交易」+「设置与维护」） */
    protected open fun showHoldingMenu() {
        val items = arrayOf(
            "✏️ 编辑持仓",
            "💰 卖出/减仓",
            "📋 查看交易记录",
            "💰 查看持仓详情",
            "🧠 市场记忆设置",
            "🧹 清空持仓"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("📦 持仓管理 — ${getQuantType()}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEditRealPositionDialog()
                    1 -> showSellRealPositionDialog()
                    2 -> showTradeHistory()
                    3 -> loadPositions()
                    4 -> showMarketMemoryDialog()
                    5 -> confirmAndClearPositions()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 报告菜单（原「报告与分析」全部条目） */
    protected open fun showReportMenu() {
        val periodLabel = getQuantType()
        val items = arrayOf(
            "📊 全周期报告",
            "📊 ${periodLabel}量化报告",
            "📊 查看精选池",
            "💰 持有收益历史",
            "📅 月度热点前瞻",
            "🔥 查看热门板块报告",
            "📋 查看策略报告",
            "📈 回溯测试",
            "🔧 拟合调优",
            "🔄 全周期拟合",
            "🧹 清空报告"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("📊 报告中心 — $periodLabel")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTradeReportsHistory()
                    1 -> showPeriodReportHistory()
                    2 -> showFinalPool()
                    3 -> showHoldingProfitHistory()
                    4 -> showMonthlyForecast()
                    5 -> exportHotSectors()
                    6 -> exportStrategyReport()
                    7 -> onBacktrackClick()
                    8 -> onFittingClick()
                    9 -> runCrossPeriodFitting()
                    10 -> confirmAndClearReports()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 启动 Pipeline 拓扑编辑器 */
    protected open fun openPipelineEditor() {
        val intent = android.content.Intent(requireContext(), com.chin.stockanalysis.strategy.topology.ui.TopologyEditorActivity::class.java)
        intent.putExtra("usecase_id", getDefaultUseCaseId())
        startActivity(intent)
    }

    /** 子类覆盖以指定预加载的 UseCase ID */
    protected open fun getDefaultUseCaseId(): String = "mid_term"

    /**
     * 创建进度条行
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
            text = "就绪"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        row.addView(statusTv)

        return row
    }

    /**
     * 创建标准单元格
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
     * dp 转 px
     */
    protected fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ═══════════════════════════════════════════════════
    // 通用模板方法（子类可直接调用，消除重复代码）
    // ═══════════════════════════════════════════════════

    /**
     * 创建日期选择器行（日期标签 + 交易日选择器 + 仅主板开关 + 提示文字）
     * @param tipText 提示标签文字（如 "⚡ 持仓1天 | 最多3只"）
     * @param tipColor 提示文字颜色（默认橙色）
     * @param mainBoardDefault 仅主板开关默认值
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

    /** 创建持仓显示区（ScrollView + positionContainer） */
    protected fun createContentScrollArea(): ScrollView {
        positionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(8, 4, 8, 4)
        }
        return ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(positionContainer)
        }
    }

    /** 添加分隔线 */
    protected fun addSeparator(topMargin: Int = 8) {
        rootLayout.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .apply { this.topMargin = topMargin }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })
    }

    /** 添加标题行 */
    protected fun addTitleRow(text: String, textSize: Float = 14f, textColor: String = "#1A1A2E") {
        rootLayout.addView(TextView(requireContext()).apply {
            this.text = text; this.textSize = textSize
            setTextColor(Color.parseColor(textColor)); setTypeface(null, Typeface.BOLD)
            setPadding(16, 12, 16, 6)
        })
    }

    /**
     * ═══ 通用 DAG Pipeline 执行模板 ═══
     *
     * 超短线/短线/长线共用此方法，只需传入不同参数。
     *
     * @param holdingPeriod 持仓周期
     * @param useCaseId DAG useCase ID (如 "ultra_short", "short_term", "long_term")
     * @param orderType 订单类型 (如 "ultra_short", "shortterm", "long_term")
     * @param importDays 导入天数
     * @param titlePrefix 标题前缀 (如 "超短线", "短线", "长线")
     * @param onComplete 完成后回调（如超短线的 T+1 检查）
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
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 排队中..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "🔄 [DAG] ${titlePrefix} 等待执行..."

        val ctx = requireContext()
        com.chin.stockanalysis.service.QuantTaskScheduler.submit(ctx, titlePrefix) {
            try {
                val today = TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                val effectiveTradeDate = TradingDayPickerView.recentTradingDay(browsingDate).format(DATE_FMT)
                val strategies = eng.getEnabledStrategiesByPeriod(holdingPeriod)

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        statusTv.text = "🔄 [DAG] ${titlePrefix} Pipeline 执行中..."
                        buildBtn.text = "⏳ 执行中..."
                    }
                }

                val r = com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor.execute(
                    context = ctx,
                    useCaseId = useCaseId,
                    tradeDate = effectiveTradeDate,
                    today = today,
                    strategies = strategies,
                    orderType = orderType,
                    importDays = importDays,
                    onNodeProgress = { pipelineName, nodeName ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (isAdded) statusTv.text = "🔄 [DAG] ${pipelineName} ${nodeName} 执行中..."
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    android.util.Log.i("QuantFragmentBase", "Pipeline 完成: selectedStocks=${r.selectedStocks.size} 只")
                    lastPickStocks = r.selectedStocks
                    pickStockCodes = if (r.selectedStocks.isNotEmpty())
                        r.selectedStocks.map { it.first }.toSet() else emptySet()
                    android.util.Log.i("QuantFragmentBase", "lastPickStocks=${lastPickStocks.size}, pickStockCodes=$pickStockCodes")

                    showPipelineNodeDetails("${titlePrefix} DAG Pipeline", r)

                    statusTv.text = r.uiText
                    buildBtn.isEnabled = true; updateBuildButtonText()
                    progressBar.visibility = View.GONE
                    refreshPositions()
                }
                onComplete?.invoke()
            } catch (e: Exception) {
                Log.e("QuantFragmentBase", "[DAG] ${titlePrefix} 执行异常", e)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    val errorDetail = buildString {
                        appendLine("❌ Pipeline 执行异常")
                        appendLine("异常类型: ${e.javaClass.simpleName}")
                        appendLine("异常信息: ${e.message}")
                        val cause = e.cause
                        if (cause != null) {
                            appendLine("根因: ${cause.javaClass.simpleName}: ${cause.message}")
                        }
                        appendLine()
                        appendLine("可能原因：")
                        appendLine("  1. 数据不完整或格式异常")
                        appendLine("  2. 策略配置错误")
                        appendLine("  3. 内存不足")
                        appendLine()
                        appendLine("详细堆栈：")
                        appendLine(e.stackTraceToString().take(500))
                    }
                    showDialog("${titlePrefix} Pipeline 异常", errorDetail)
                    statusTv.text = "❌ [DAG] ${e.message?.take(40)}"
                    buildBtn.isEnabled = true; updateBuildButtonText()
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    /**
     * ═══ 通用历史回溯测试模板 ═══
     *
     * 超短线/短线/长线共用此方法。
     *
     * @param holdingPeriod 持仓周期（null 表示使用所有启用策略）
     * @param tradingDays 回测天数
     * @param titlePrefix 标题前缀
     * @param extraInfo 额外信息行（如 "持仓: 1天 | 止损: -2%"）
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
        statusTv.text = "${titlePrefix}回溯测试中..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = if (holdingPeriod != null) {
                    eng.getEnabledStrategiesByPeriod(holdingPeriod)
                } else {
                    eng.getStrategies().filter { eng.isEnabled(it.id) }
                }
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusTv.text = "⚠️ 无${titlePrefix}策略可回测"
                        buildBtn.isEnabled = true; updateBuildButtonText()
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                val backtestEngine = com.chin.stockanalysis.strategy.backtest.HistoricalBacktestEngine(requireContext())
                val report = backtestEngine.runHistoricalBacktest(strategies, tradingDays = tradingDays)

                val sb = StringBuilder()
                sb.appendLine("${titlePrefix}回溯测试报告 (${tradingDays}交易日)")
                if (extraInfo.isNotBlank()) sb.appendLine(extraInfo)
                sb.appendLine("期间: ${report.dateRange}")
                sb.appendLine()
                for (r in report.strategyReports) {
                    sb.appendLine("📋 ${r.strategyName}")
                    sb.appendLine("  交易日: ${r.totalDays} 天 | 买入信号: ${r.totalBuys} 次")
                    sb.appendLine("  买入准确率: ${"%.1f".format(r.buyAccuracy * 100)}% (${r.correctBuys}/${r.totalBuys})")
                    sb.appendLine("  平均净收益: ${"%.2f".format(r.avgReturn)}%（已扣交易成本0.3%）")
                    sb.appendLine("  最大盈利: ${"%.2f".format(r.maxGain)}% | 最大亏损: ${"%.2f".format(r.maxLoss)}%")
                    sb.appendLine()
                }

                withContext(Dispatchers.Main) {
                    showDialog("${titlePrefix}回溯报告", sb.toString())
                    statusTv.text = "✅ ${titlePrefix}回测完成"
                    buildBtn.isEnabled = true; updateBuildButtonText()
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 回测失败: ${e.message?.take(40)}"
                    buildBtn.isEnabled = true; updateBuildButtonText()
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    /**
     * ═══ 通用拟合参数报告模板 ═══
     *
     * 短线/中线共用此方法展示拟合参数报告。
     *
     * @param titlePrefix 标题前缀 (如 "短线", "中线")
     * @param periodLabel 周期标签 (如 "3日", 可选)
     */
    protected fun showFittingParamsReport(
        titlePrefix: String,
        periodLabel: String = ""
    ) {
        val eng = engine ?: return
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 拟合中"
        progressBar.visibility = View.VISIBLE
        statusTv.text = "🔧 ${titlePrefix}拟合调优中${if (periodLabel.isNotBlank()) "（周期: $periodLabel）" else ""}..."

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
                        Log.w("QuantFragmentBase", "${titlePrefix}拟合失败（仍显示已有数据）: ${e.message}")
                    }
                }

                val sb = StringBuilder()
                sb.appendLine("🔧 ${titlePrefix}拟合参数${if (periodLabel.isNotBlank()) "（当前周期: $periodLabel）" else ""}")
                sb.appendLine()
                for (strategy in strategies) {
                    sb.appendLine("【${strategy.name}】")
                    val params = db.strategyTradeFittingParamDao().getRecentByStrategy(strategy.id, 50)
                    if (params.isEmpty()) {
                        sb.appendLine("  暂无拟合数据")
                    } else {
                        val byPeriod = params.groupBy { it.periodDays }
                        for ((period, items) in byPeriod) {
                            val best = items.maxByOrNull { it.accuracy }
                            val worst = items.minByOrNull { it.accuracy }
                            sb.appendLine("  [${period}日] ${items.size}条")
                            if (best != null) sb.appendLine("    最佳: 准确率${"%.2f".format(best.accuracy * 100)}% 平均收益${"%.2f".format(best.avgReturn)}%")
                            if (worst != null) sb.appendLine("    最差: 准确率${"%.2f".format(worst.accuracy * 100)}% 平均收益${"%.2f".format(worst.avgReturn)}%")
                        }
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) {
                    showDialog("${titlePrefix}拟合参数", sb.toString())
                    statusTv.text = "✅ 拟合完成: ${strategies.size} 个策略"
                    buildBtn.isEnabled = true; updateBuildButtonText()
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 拟合失败: ${e.message?.take(30)}"
                    buildBtn.isEnabled = true; updateBuildButtonText()
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════
    // 买卖评估（整合做T + 卖出）
    // ═══════════════════════════════════════════════════

    /** 买卖评估菜单：统一选项 + 基本面检查（居中 Dialog） */
    protected open fun showTradeEvaluationMenu(anchor: View) {
        val items = arrayOf(
            "🔄 做T信号",
            "💰 卖出评估",
            "📈 买入评估",
            "💎 基本面检查",
            "📊 卖出绩效",
            "⚡ 执行卖出"
        )
        val titleView = buildEvalTitleView("💰 买卖评估")
        AlertDialog.Builder(requireContext())
            .setCustomTitle(titleView)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTTradeMenu()
                    1 -> runAutoSellEvaluation()
                    2 -> showBuyEvaluation()
                    3 -> checkFundamentalHealth()
                    4 -> showSellPerformance()
                    5 -> executeAutoSell()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 构建标题视图：标题文字 + 🔄 刷新按钮 */
    private fun buildEvalTitleView(title: String): android.view.View {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 16)
        }
        layout.addView(android.widget.TextView(requireContext()).apply {
            text = title; textSize = 18f
            setTextColor(Color.parseColor("#222222"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        layout.addView(android.widget.TextView(requireContext()).apply {
            text = "🔄"; textSize = 20f
            setPadding(16, 0, 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { runAllEvaluations() }
        })
        return layout
    }

    /** 一键执行所有评估功能 */
    protected fun runAllEvaluations() {
        progressBar.visibility = View.VISIBLE
        statusTv.text = "🚀 一键执行全部评估..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 做T信号
                withContext(Dispatchers.Main) { statusTv.text = "🔄 正在生成做T信号..." }
                try { showTTradeMenu() } catch (_: Exception) {}

                // 2. 卖出评估
                withContext(Dispatchers.Main) { statusTv.text = "💰 正在卖出评估..." }
                delay(500)
                try { runAutoSellEvaluation() } catch (_: Exception) {}

                // 3. 买入评估
                withContext(Dispatchers.Main) { statusTv.text = "📈 正在买入评估..." }
                delay(500)
                try { showBuyEvaluation() } catch (_: Exception) {}

                // 4. 基本面检查
                withContext(Dispatchers.Main) { statusTv.text = "💎 正在基本面检查..." }
                delay(500)
                try { checkFundamentalHealth() } catch (_: Exception) {}

                withContext(Dispatchers.Main) {
                    statusTv.text = "✅ 全部评估完成"
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 评估失败: ${e.message?.take(30)}"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    /** 综合评估对话框：同时显示做T信号和卖出评估 */
    protected fun showTradeEvaluationDialog() {
        val periodType = getQuantType()
        val eng = engine ?: return
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在载入综合评估..."

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val tEngine = TTradeEngine(requireContext())
                val appCtx = requireContext().applicationContext

                // ═══ 并行载入做T信号和卖出评估 ═══
                val tTradeDeferred = async {
                    try {
                        val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
                            .filter { (it.status == "BUYING" || it.status == "PENDING") && it.orderType == periodType }
                        val allSignals = mutableListOf<TTradeSignal>()
                        for (order in holdingOrders) {
                            allSignals.addAll(tEngine.generateSignals(order.stockCode, order.quantity, periodType))
                        }
                        val realPositions = db.realPositionDao().getAllActive()
                        for (pos in realPositions) {
                            if (pos.quantity <= 0) continue
                            allSignals.addAll(tEngine.generateSignals(pos.stockCode, pos.quantity, "RealPosition"))
                        }
                        val stats = tEngine.getTTradeStats(periodType)
                        val recStats = tEngine.getRecommendationStats()
                        Triple(allSignals, stats, recStats)
                    } catch (e: Exception) {
                        Log.w("QuantBase", "做T数据载入失败: ${e.message}")
                        null
                    }
                }

                val sellDeferred = async {
                    try {
                        val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                        val sellEngine = AutoSellEngine(appCtx)
                        sellEngine.evaluateAll(strategies, AutoSellEngine.AutoSellConfig(tradeDate = browsingDate.format(DATE_FMT)))
                            .filter { it.order.orderType == getQuantType() }
                    } catch (e: Exception) {
                        Log.w("QuantBase", "卖出评估载入失败: ${e.message}")
                        null
                    }
                }

                val tTradeData = tTradeDeferred.await()
                val sellDecisions = sellDeferred.await()
                sellDecisionsCache = sellDecisions ?: emptyList()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "📈 评估载入完成"
                    buildEvaluationTabbedDialog(tTradeData, sellDecisions)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 评估载入失败: ${e.message?.take(40)}"
                    android.widget.Toast.makeText(requireContext(), "评估载入失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 构建综合评估 Tabbed 对话框 */
    private fun buildEvaluationTabbedDialog(
        tTradeData: Triple<List<TTradeSignal>, TTradeStats, RecommendationStats>?,
        sellDecisions: List<AutoSellEngine.SellDecision>?
    ) {
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("📈 买卖综合评估")
            .create()

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }

        // ── Tab 按钮行 ──
        val tabRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val contentFrame = android.widget.FrameLayout(requireContext())

        val tTabBtn = android.widget.Button(requireContext()).apply {
            text = "🔄 做T信号"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#BF360C"))
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, dpToPx(30), 1f).apply { marginEnd = 4 }
        }
        val sTabBtn = android.widget.Button(requireContext()).apply {
            text = "💰 卖出评估"
            textSize = 12f
            setTextColor(Color.parseColor("#BF360C"))
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            setMinWidth(0); setMinimumWidth(0)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, dpToPx(30), 1f)
        }
        tabRow.addView(tTabBtn)
        tabRow.addView(sTabBtn)
        container.addView(tabRow)
        container.addView(contentFrame)

        // ── 做T Tab 内容 ──
        val tScroll = android.widget.ScrollView(requireContext())
        val tContent = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        tScroll.addView(tContent)

        if (tTradeData != null) {
            val (signals, stats, recStats) = tTradeData
            // 统计摘要
            tContent.addView(makeInfoCard("做T统计", buildString {
                appendLine("总盈亏: ${"%.2f".format(stats.totalProfit)}  |  胜率: ${"%.1f".format(stats.winRate * 100)}%")
                appendLine("完成交易: ${stats.totalTrades}  |  待处理: ${recStats.todayPending}")
            }))
            // 信号列表
            if (signals.isEmpty()) {
                tContent.addView(makeInfoCard("提示", "暂无做T信号。系统会根据支撑/阻力位+RSI+量能+K线形态自动生成建议。"))
            } else {
                // 趋势预警
                val rising = signals.filter { it.trendDirection.contains("上升") || it.trendDirection.contains("准备上升") }
                val falling = signals.filter { it.trendDirection.contains("下跌") || it.trendDirection.contains("准备下跌") }
                if (rising.isNotEmpty() || falling.isNotEmpty()) {
                    val alertText = buildString {
                        if (rising.isNotEmpty()) appendLine("⚡ 准备上升: ${rising.joinToString { it.stockCode }}")
                        if (falling.isNotEmpty()) appendLine("⚡ 准备下跌: ${falling.joinToString { it.stockCode }}")
                    }
                    tContent.addView(makeInfoCard("趋势预警", alertText.trim()))
                }
                for (signal in signals.take(10)) {
                    val trendIcon = when {
                        signal.trendDirection.contains("上升") -> "📈"
                        signal.trendDirection.contains("下跌") -> "📉"
                        else -> "➡️"
                    }
                    val trendInfo = if (signal.trendDirection.isNotEmpty())
                        "$trendIcon ${signal.trendDirection} | 置信度${signal.confidence}%\n" else ""
                    val rsiInfo = if (signal.rsi != 50.0 || signal.volumeRatio != 1.0)
                        "RSI:${"%.0f".format(signal.rsi)} 量比:${"%.1f".format(signal.volumeRatio)}" +
                        if (signal.patternName.isNotEmpty()) " 形态:${signal.patternName}" else ""
                        else ""
                    val rsiFull = if (rsiInfo.isNotEmpty()) "$rsiInfo\n" else ""

                    tContent.addView(makeInfoCard(
                        "${signal.signalType.label} ${signal.stockCode}",
                        "${trendInfo}${rsiFull}建议价: ${"%.2f".format(signal.suggestedPrice)} → 目标: ${"%.2f".format(signal.targetPrice)}\n" +
                        "数量: ${signal.quantity}股  |  预期收益: ${"%.2f".format(signal.expectedProfitPct)}%\n${signal.reason}"
                    ))
                }
            }
        } else {
            tContent.addView(makeInfoCard("错误", "做T数据载入失败"))
        }

        // ── 卖出 Tab 内容 ──
        val sScroll = android.widget.ScrollView(requireContext())
        val sContent = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        sScroll.addView(sContent)

        if (sellDecisions != null) {
            val shouldSell = sellDecisions.filter { it.shouldSell }
            val holding = sellDecisions.filter { !it.shouldSell }
            sContent.addView(makeInfoCard("卖出摘要", "持仓: ${sellDecisions.size}  |  触发卖出: ${shouldSell.size}  |  继续持有: ${holding.size}"))

            if (shouldSell.isEmpty()) {
                sContent.addView(makeInfoCard("✅ 好消息", "所有持仓暂无卖出信号，建议继续持有。"))
            } else {
                for (d in shouldSell.sortedByDescending { it.urgency }) {
                    val emoji = if (d.urgency >= 9) "🚨" else if (d.urgency >= 7) "⚠️" else "📢"
                    sContent.addView(makeInfoCard(
                        "$emoji ${d.order.stockCode} — ${d.strategy}",
                        "盈亏: ${"%.2f".format(d.profitPct)}%  |  卖出比例: ${"%.0f".format(d.sellRatio * 100)}%\n" +
                        "紧急度: ${d.urgency}/10  |  ${d.reason}"
                    ))
                }
            }
            for (d in holding.take(5)) {
                sContent.addView(makeInfoCard(
                    "✅ 持有 ${d.order.stockCode}",
                    "盈亏: ${"%.2f".format(d.profitPct)}%  |  ${d.reason}"
                ))
            }
        } else {
            sContent.addView(makeInfoCard("错误", "卖出评估数据载入失败"))
        }

        // ── Tab 切换逻辑 ──
        fun showTTab() {
            tTabBtn.setTextColor(Color.WHITE); tTabBtn.setBackgroundColor(Color.parseColor("#BF360C"))
            sTabBtn.setTextColor(Color.parseColor("#BF360C")); sTabBtn.setBackgroundColor(Color.parseColor("#E0E0E0"))
            contentFrame.removeAllViews()
            contentFrame.addView(tScroll)
        }
        fun showSTab() {
            sTabBtn.setTextColor(Color.WHITE); sTabBtn.setBackgroundColor(Color.parseColor("#00897B"))
            tTabBtn.setTextColor(Color.parseColor("#00897B")); tTabBtn.setBackgroundColor(Color.parseColor("#E0E0E0"))
            contentFrame.removeAllViews()
            contentFrame.addView(sScroll)
        }
        tTabBtn.setOnClickListener { showTTab() }
        sTabBtn.setOnClickListener { showSTab() }
        showTTab() // 默认显示做T

        dialog.setView(container)
        dialog.setButton(android.app.AlertDialog.BUTTON_NEUTRAL, "关闭") { _, _ -> }
        dialog.show()
    }

    /** 构建简单的信息卡片 */
    private fun makeInfoCard(title: String, content: String): android.widget.LinearLayout {
        return android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(12, 8, 12, 8)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(4) }
            addView(android.widget.TextView(requireContext()).apply {
                text = title; textSize = 13f; setTextColor(Color.parseColor("#333333"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(android.widget.TextView(requireContext()).apply {
                text = content; textSize = 11f; setTextColor(Color.parseColor("#666666"))
                setPadding(0, 4, 0, 0)
            })
        }
    }

    // ═══════════════════════════════════════════════════
    // 卖出功能（保留原有独立方法，供菜单调用）
    // ═══════════════════════════════════════════════════

    /** 显示卖出下拉菜单 */
    protected open fun showSellMenu(anchor: View) {
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

    /** 执行卖出评估（仅评估，不执行） */
    protected fun runAutoSellEvaluation() {
        val eng = engine ?: return
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在评估卖出信号..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }
                val appCtx = requireContext().applicationContext
                val sellEngine = AutoSellEngine(appCtx)

                // 1. 评估持仓 (strategy_trade_order)
                val holdingDecisions = sellEngine.evaluateAll(strategies, AutoSellEngine.AutoSellConfig(tradeDate = browsingDate.format(DATE_FMT)))
                    .filter { it.order.orderType == getQuantType() }
                    .toMutableList()

                // 2. 评估选股 (user_watchlist)
                val db = StockDatabase.getInstance(requireContext())
                val periodType = getQuantType()
                // 选股保存时用的是 getWatchlistSource()（小写），这里必须一致才能查得到
                val watchlistItems = db.userWatchlistDao().getBySource(getWatchlistSource())
                    .filter { it.status == "WATCHING" }

                val watchlistDecisions = mutableListOf<AutoSellEngine.SellDecision>()
                if (watchlistItems.isNotEmpty()) {
                    // 获取实时行情
                    val realtimeMap = try {
                        com.chin.stockanalysis.stock.data.StockDataSourceFactory
                            .createDefaultRepository(appCtx)
                            .getRealtime(watchlistItems.map { it.stockCode })
                    } catch (e: Exception) {
                        Log.w(TAG, "选股行情获取失败: ${e.message}"); emptyMap()
                    }

                    for (watch in watchlistItems) {
                        val snap = realtimeMap[watch.stockCode]
                        val currentPrice = snap?.price ?: watch.buyPrice
                        val pnlPct = if (watch.buyPrice > 0) {
                            (currentPrice - watch.buyPrice) / watch.buyPrice * 100
                        } else 0.0

                        // 简化评估：亏损超过5%或基本面恶化建议移除
                        var shouldRemove = false
                        var reason = ""
                        var urgency = 0

                        if (pnlPct <= -5.0) {
                            shouldRemove = true
                            reason = "亏损超过5%，建议移除观察"
                            urgency = 6
                        } else if (snap != null) {
                            // 检查基本面
                            if (snap.pe > 100 || snap.pb > 12) {
                                shouldRemove = true
                                reason = "估值过高 (PE=${"%.1f".format(snap.pe)}, PB=${"%.2f".format(snap.pb)})"
                                urgency = 5
                            } else if (snap.roeTTM > 0 && snap.roeTTM < 5.0) {
                                shouldRemove = true
                                reason = "ROE过低 (${ "%.1f".format(snap.roeTTM)}%)"
                                urgency = 4
                            }
                        }

                        // 转换为 SellDecision 格式
                        val order = StrategyTradeOrderEntity(
                            id = 0,
                            strategyId = "watchlist",
                            stockCode = watch.stockCode,
                            stockName = watch.stockName,
                            tradeDate = watch.addedDate,
                            buyPrice = watch.buyPrice,
                            buyTime = "09:30:00",
                            quantity = 100,
                            orderType = watch.source,
                            status = "WATCHING",
                            reason = "选股观察",
                            scoreAtBuy = watch.scoreAtAdd
                        )

                        watchlistDecisions.add(
                            AutoSellEngine.SellDecision(
                                order = order,
                                reason = if (reason.isNotEmpty()) reason else "观察中",
                                currentPrice = currentPrice,
                                profitPct = pnlPct,
                                shouldSell = shouldRemove,
                                strategy = "选股评估",
                                sellRatio = 1.0,
                                urgency = urgency,
                                technicalDetails = if (snap != null) mapOf(
                                    "pe" to "%.1f".format(snap.pe),
                                    "pb" to "%.2f".format(snap.pb),
                                    "roe" to "%.1f".format(snap.roeTTM)
                                ) else emptyMap()
                            )
                        )
                    }
                }

                // 3. 合并两个列表
                val allDecisions = holdingDecisions + watchlistDecisions
                sellDecisionsCache = allDecisions

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    val holdingCount = holdingDecisions.size
                    val watchCount = watchlistDecisions.size
                    val shouldSellCount = allDecisions.count { it.shouldSell }
                    statusTv.text = "💰 卖出评估: $holdingCount 持仓 + $watchCount 选股, $shouldSellCount 触发卖出"
                    showSellDecisionsDialog(allDecisions)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 卖出评估失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    /** 买入评估：运行当前周期策略，显示买入候选 */
    protected fun showBuyEvaluation() {
        val eng = engine ?: return
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在评估买入信号..."

        val periodType = getQuantType()
        val periodEnumMap = mapOf(
            "UltraShortQuant" to HoldingPeriod.ULTRA_SHORT,
            "ShortTermQuant" to HoldingPeriod.SHORT,
            "MidTermQuant" to HoldingPeriod.MID,
            "LongTermQuant" to HoldingPeriod.LONG
        )
        val holdingPeriod = periodEnumMap[periodType] ?: HoldingPeriod.SHORT

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val strategies = eng.getEnabledStrategiesByPeriod(holdingPeriod)
                if (strategies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "❌ 当前周期无启用的策略"
                    }
                    return@launch
                }

                // 先检查快取结果
                var allSignals = eng.getAllLastResults()
                    .flatMap { it.signals }
                    .filter { it.action == com.chin.stockanalysis.strategy.models.SignalAction.BUY }

                // 快取为空时即时运行策略
                if (allSignals.isEmpty()) {
                    val results = mutableListOf<com.chin.stockanalysis.strategy.models.ScreeningResult>()
                    val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                    eng.runAll(
                        scope = lifecycleScope,
                        onProgress = { result -> results.add(result) },
                        onComplete = { deferred.complete(Unit) }
                    )
                    withTimeoutOrNull(60_000L) { deferred.await() }
                    allSignals = results.flatMap { it.signals }
                        .filter { it.action == com.chin.stockanalysis.strategy.models.SignalAction.BUY }
                }

                val sorted = allSignals.sortedByDescending { it.strength }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "📈 买入评估: ${sorted.size} 个候选"
                    showBuyCandidatesDialog(sorted, periodType, strategies.size)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 买入评估失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    private fun showBuyCandidatesDialog(
        signals: List<com.chin.stockanalysis.strategy.models.StrategySignal>,
        periodType: String,
        strategyCount: Int
    ) {
        val sb = StringBuilder()
        sb.appendLine("📈 买入候选评估报告")
        sb.appendLine("周期: $periodType | 策略数: $strategyCount")
        sb.appendLine("交易日: ${browsingDate.format(DATE_FMT)}")
        sb.appendLine()

        if (signals.isEmpty()) {
            sb.appendLine("当前无买入信号，建议观望。")
        } else {
            sb.appendLine("共 ${signals.size} 个买入候选（按强度排序）:")
            sb.appendLine()
            for ((i, sig) in signals.withIndex()) {
                val emoji = when {
                    sig.strength >= 80 -> "🔥"
                    sig.strength >= 65 -> "⭐"
                    else -> "👀"
                }
                sb.appendLine("  ${i + 1}. $emoji ${sig.stockName}(${sig.stockCode.takeLast(6)})")
                sb.appendLine("    强度: ${sig.strength} | 现价: ¥${"%.2f".format(sig.currentPrice)} | 涨幅: ${"%.2f".format(sig.changePercent)}%")
                sb.appendLine("    原因: ${sig.reason.take(100)}")
                if (sig.details.isNotEmpty()) {
                    val topDetails = sig.details.entries.take(4).joinToString(" ") { "${it.key}=${it.value}" }
                    sb.appendLine("    指标: $topDetails")
                }
                sb.appendLine()
            }
        }

        showDialog("买入评估报告", sb.toString())
    }

    /** 基本面健康检查：检查当前周期持仓的基本面是否恶化 */
    protected fun checkFundamentalHealth() {
        val periodType = getQuantType()
        val periodLabel = when (periodType) {
            "ultra_short" -> "超短线"
            "short_term" -> "短线"
            "mid_term" -> "中线"
            "long_term" -> "长线"
            else -> periodType
        }
        progressBar.visibility = View.VISIBLE
        statusTv.text = "💎 正在检查基本面..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())

                // 1. 查询持仓 (strategy_trade_order)
                val holdingOrders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == periodType &&
                        (it.status == "BUYING" || it.status == "PENDING") }
                    .map { it to "持仓" }

                // 2. 查询选股 (user_watchlist) — source 用小写（getWatchlistSource），与保存时一致
                val watchlistOrders = db.userWatchlistDao().getBySource(getWatchlistSource())
                    .filter { it.status == "WATCHING" }
                    .map { watch ->
                        // 转换为 StrategyTradeOrderEntity 格式
                        val order = StrategyTradeOrderEntity(
                            id = 0,
                            strategyId = "watchlist",
                            stockCode = watch.stockCode,
                            stockName = watch.stockName,
                            tradeDate = watch.addedDate,
                            buyPrice = watch.buyPrice,
                            buyTime = "09:30:00",
                            quantity = 100,
                            orderType = watch.source,
                            status = "WATCHING",
                            reason = "选股观察",
                            scoreAtBuy = watch.scoreAtAdd
                        )
                        order to "选股"
                    }

                // 3. 合并两个列表
                val allOrders = holdingOrders + watchlistOrders

                if (allOrders.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "💎 无${periodLabel}持仓/选股"
                        showDialog("基本面检查", "暂无${periodLabel}持仓或选股，无需检查。")
                    }
                    return@launch
                }

                val allCodes = allOrders.map { it.first.stockCode }
                val realtimeMap = try {
                    com.chin.stockanalysis.stock.data.StockDataSourceFactory
                        .createDefaultRepository(requireContext().applicationContext)
                        .getRealtime(allCodes)
                } catch (e: Exception) {
                    Log.w(TAG, "实时行情获取失败: ${e.message}"); emptyMap()
                }

                val sb = StringBuilder()
                val holdingCount = holdingOrders.size
                val watchCount = watchlistOrders.size
                sb.appendLine("💎 ${periodLabel}基本面检查报告")
                sb.appendLine("持仓: $holdingCount 只 | 选股: $watchCount 只")
                sb.appendLine()

                // 4. 显示持仓部分
                if (holdingOrders.isNotEmpty()) {
                    sb.appendLine("══════ 持仓 ($holdingCount) ══════")
                    for ((order, _) in holdingOrders) {
                        appendStockFundamental(sb, order, realtimeMap, "[持仓]")
                    }
                }

                // 5. 显示选股部分
                if (watchlistOrders.isNotEmpty()) {
                    sb.appendLine("══════ 选股 ($watchCount) ══════")
                    for ((order, _) in watchlistOrders) {
                        appendStockFundamental(sb, order, realtimeMap, "[选股]")
                    }
                }

                sb.appendLine("💡 卖出信号：")
                sb.appendLine("  • PE > 80 或 PB > 10 → 估值过高")
                sb.appendLine("  • ROE < 8% 或连续下滑 → 基本面恶化")
                sb.appendLine("  • 负债率 > 60% → 财务风险增加")

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 基本面检查完成 (${holdingCount}持仓 + ${watchCount}选股)"
                    showDialog("基本面检查报告", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 基本面检查失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    /** 辅助函数：输出单个股票的基本面信息 */
    private fun appendStockFundamental(
        sb: StringBuilder,
        order: StrategyTradeOrderEntity,
        realtimeMap: Map<String, com.chin.stockanalysis.stock.StockRealtime>,
        tag: String
    ) {
        sb.appendLine("$tag ${order.stockName} (${order.stockCode.takeLast(6)})")
        sb.appendLine("  建仓日: ${order.tradeDate} | 成本: ¥${"%.2f".format(order.buyPrice)}")

        val snap = realtimeMap[order.stockCode]
        val currentPrice = snap?.price ?: order.buyPrice
        val pnlPct = if (order.buyPrice > 0) {
            (currentPrice - order.buyPrice) / order.buyPrice * 100
        } else 0.0
        val pnlStr = if (pnlPct >= 0) "+${"%.2f".format(pnlPct)}%" else "${"%.2f".format(pnlPct)}%"
        sb.appendLine("  当前价: ¥${"%.2f".format(currentPrice)} | 盈亏: $pnlStr")

        if (snap != null) {
            sb.appendLine("  ── 基本面 ──")
            if (snap.pe > 0) {
                val peWarning = if (snap.pe > 80) " ⚠️ 估值过高" else ""
                sb.appendLine("  PE(TTM): ${"%.1f".format(snap.pe)}$peWarning")
            }
            if (snap.pb > 0) {
                val pbWarning = if (snap.pb > 10) " ⚠️ 估值过高" else ""
                sb.appendLine("  PB: ${"%.2f".format(snap.pb)}$pbWarning")
            }
            if (snap.roeTTM > 0) {
                val roeWarning = if (snap.roeTTM < 8.0) " ⚠️ ROE偏低" else " ✅"
                sb.appendLine("  ROE: ${"%.1f".format(snap.roeTTM)}%$roeWarning")
            }
            if (snap.debtToAsset > 0) {
                val debtWarning = if (snap.debtToAsset > 60.0) " ⚠️ 负债率偏高" else " ✅"
                sb.appendLine("  负债率: ${"%.1f".format(snap.debtToAsset)}%$debtWarning")
            }
            if (snap.grossMarginTTM > 0) {
                sb.appendLine("  毛利率: ${"%.1f".format(snap.grossMarginTTM)}%")
            }
            if (snap.pe <= 0 && snap.pb <= 0 && snap.roeTTM <= 0) {
                sb.appendLine("  ── 基本面数据缺失 ──")
            }
        } else {
            sb.appendLine("  ── 基本面数据缺失 ──")
        }
        sb.appendLine()
    }

    /** 执行卖出（使用缓存的决策） */
    protected fun executeAutoSell() {
        if (sellDecisionsCache.isEmpty()) {
            runAutoSellEvaluation()
            Toast.makeText(requireContext(), "请等待评估完成后再次点击「执行卖出」", Toast.LENGTH_SHORT).show()
            return
        }
        val shouldSell = sellDecisionsCache.filter { it.shouldSell }
        if (shouldSell.isEmpty()) {
            Toast.makeText(requireContext(), "当前没有需要卖出的持仓或选股", Toast.LENGTH_SHORT).show()
            return
        }

        // 分离持仓和选股
        val holdingToSell = shouldSell.filter { it.order.status != "WATCHING" }
        val watchlistToRemove = shouldSell.filter { it.order.status == "WATCHING" }

        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在执行卖出..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val appCtx = requireContext().applicationContext
                var holdingSoldCount = 0
                var watchlistRemovedCount = 0

                // 1. 执行持仓卖出（只卖本周期已评估命中的持仓，避免跨周期误卖）
                if (holdingToSell.isNotEmpty()) {
                    val sellEngine = AutoSellEngine(appCtx)
                    sellEngine.executeSells(holdingToSell, browsingDate.format(DATE_FMT))
                    holdingSoldCount = holdingToSell.size
                }

                // 2. 移除选股 (从 user_watchlist 删除)
                if (watchlistToRemove.isNotEmpty()) {
                    val db = StockDatabase.getInstance(requireContext())
                    for (decision in watchlistToRemove) {
                        db.userWatchlistDao().deleteByCode(decision.order.stockCode)
                        watchlistRemovedCount++
                    }
                }

                val totalCount = holdingSoldCount + watchlistRemovedCount
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 已执行 $totalCount 笔操作"
                    refreshPositions()
                    sellDecisionsCache = emptyList()

                    val msg = buildString {
                        if (holdingSoldCount > 0) append("卖出 $holdingSoldCount 笔持仓")
                        if (watchlistRemovedCount > 0) {
                            if (isNotEmpty()) append("，")
                            append("移除 $watchlistRemovedCount 只选股")
                        }
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ 卖出执行失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    /** 显示卖出决策对话框 */
    protected fun showSellDecisionsDialog(decisions: List<AutoSellEngine.SellDecision>) {
        val sb = StringBuilder()
        sb.appendLine("💰 智能卖出评估报告")
        sb.appendLine("交易日: ${browsingDate.format(DATE_FMT)}")
        sb.appendLine("共 ${decisions.size} 个持仓评估")
        sb.appendLine()

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
                if (d.technicalDetails.isNotEmpty())
                    d.technicalDetails.forEach { (k, v) -> sb.appendLine("    $k=$v") }
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
    protected fun showSellPerformance() {
        progressBar.visibility = View.VISIBLE
        statusTv.text = "正在统计卖出绩效..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val appCtx = requireContext().applicationContext
                val sellEngine = AutoSellEngine(appCtx)
                val stats = sellEngine.getSellPerformance(90)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "✅ 卖出绩效已加载"
                    if (stats.isEmpty()) {
                        showDialog("卖出绩效", "暂无卖出记录，无法统计绩效")
                        return@withContext
                    }
                    val sb = StringBuilder()
                    sb.appendLine("📊 卖出策略绩效统计 (最近90天)")
                    sb.appendLine()
                    sb.appendLine("策略            卖出数  均收益   胜率    最大赢   最大亏   均持仓")
                    sb.appendLine("──────────────────────────────────────────────────────")
                    for (s in stats) {
                        val name = when (s.strategyName) {
                            "HardStop" -> "硬止损"; "MaxDrawdown" -> "最大回撤"
                            "TimeForceClose" -> "时间强平"; "TimeNoProgress" -> "时间无进展"
                            "TieredTP" -> "阶梯止盈"; "ChandelierExit" -> "吊灯止损"
                            "TrailProfit" -> "移动止盈"; "MADeathCross" -> "MA死叉"
                            "VolumeClimax" -> "放量滞涨"; "RSIOverbought" -> "RSI超买"
                            "SectorWeakness" -> "板块弱势"; "TakeProfit" -> "目标止盈"
                            "ATRStop" -> "ATR止损"; "MomentumDecay" -> "动量衰竭"
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
                    sb.appendLine("💡 综合表现排名基于: 胜率 × 平均收益")
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

    // ═══════════════════════════════════════════════════
    // 真实持仓（手动导入券商持仓）
    // ═══════════════════════════════════════════════════

    /** 显示真实持仓管理菜单 */
    protected open fun showRealPositionMenu() {
        val items = arrayOf(
            "📋 查看真实持仓",
            "➕ 添加真实持仓",
            "✏️ 编辑持仓",
            "💰 卖出/减仓",
            "🔄 对真实持仓做T",
            "📊 真实持仓做T统计"
        )
        val builder = android.app.AlertDialog.Builder(requireContext())
            .setTitle("👤 真实持仓管理")
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

    /** 显示真实持仓列表 */
    private fun showRealPositionList() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val positions = db.realPositionDao().getAllActive()
                withContext(Dispatchers.Main) {
                    if (positions.isEmpty()) {
                        showDialog("真实持仓", "暂无真实持仓记录\n\n点击「添加真实持仓」录入您的券商持仓")
                        return@withContext
                    }

                    val sb = StringBuilder()
                    sb.appendLine("📊 真实持仓 (${positions.size} 只):")
                    sb.appendLine("─".repeat(50))
                    var totalCost = 0.0
                    for (p in positions) {
                        val cost = p.avgBuyPrice * p.quantity
                        totalCost += cost
                        sb.appendLine("▸ ${p.stockName}(${p.stockCode})")
                        sb.appendLine("  数量: ${p.quantity}股 | 均价: ${"%.2f".format(p.avgBuyPrice)} | 成本: ${"%.0f".format(cost)}")
                        if (p.periodType.isNotEmpty()) {
                            sb.appendLine("  分类: ${p.periodType}")
                        }
                        if (p.notes.isNotEmpty()) {
                            sb.appendLine("  备注: ${p.notes}")
                        }
                        sb.appendLine()
                    }
                    sb.appendLine("─".repeat(50))
                    sb.appendLine("💰 总成本: ${"%.0f".format(totalCost)}")
                    sb.appendLine()
                    sb.appendLine("💡 在券商APP查看持仓后，点击「添加真实持仓」录入")
                    sb.appendLine("💡 录入后可使用「对真实持仓做T」生成做T建议")
                    showDialog("真实持仓列表", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("真实持仓", "❌ 加载失败: ${e.message}")
                }
            }
        }
    }

    /** 添加真实持仓对话框 */
    private fun showAddRealPositionDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
        }

        val codeEt = android.widget.EditText(ctx).apply {
            hint = "股票代码 (如 sh600519)"
            setSingleLine()
        }
        val nameEt = android.widget.EditText(ctx).apply {
            hint = "股票名称 (如 贵州茅台)"
            setSingleLine()
        }
        val qtyEt = android.widget.EditText(ctx).apply {
            hint = "持有数量 (股)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val priceEt = android.widget.EditText(ctx).apply {
            hint = "买入均价"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        val dateEt = android.widget.EditText(ctx).apply {
            hint = "买入日期 (yyyy-MM-dd)"
            setSingleLine()
            val today = java.time.LocalDate.now().toString()
            setText(today)
        }

        val periodSpinner = android.widget.ArrayAdapter<String>(
            ctx, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("未分类", "短线 ShortTermQuant", "中线 MidTermQuant", "长线 LongTermQuant")
        )
        val periodSpinnerView = android.widget.Spinner(ctx).apply {
            adapter = periodSpinner
        }

        val notesEt = android.widget.EditText(ctx).apply {
            hint = "备注 (可选)"
            setSingleLine()
        }

        container.addView(codeEt)
        container.addView(nameEt)
        container.addView(qtyEt)
        container.addView(priceEt)
        container.addView(dateEt)
        container.addView(android.widget.TextView(ctx).apply {
            text = "持仓分类:"; textSize = 12f; setPadding(0, 8, 0, 4)
        })
        container.addView(periodSpinnerView)
        container.addView(notesEt)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("➕ 添加真实持仓")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val rawInput = codeEt.text.toString().trim()
                // 解析中文名称 → 代码（如 "兆易创新" → "603986"）
                val resolvedInput = com.chin.stockanalysis.ai.StockEntityExtractor.resolveSync(rawInput) ?: rawInput
                val code = com.chin.stockanalysis.agent.stock.StockAnalysisAgent.normalizeStockCode(resolvedInput)
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
                    android.widget.Toast.makeText(ctx, "请输入股票代码", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(ctx.applicationContext)
                        val nextId = db.realPositionDao().getMaxId() + 1
                        val rowId = db.realPositionDao().insert(
                            RealPositionEntity(
                                id = nextId,
                                stockCode = code,
                                stockName = name,
                                quantity = qty,
                                avgBuyPrice = price,
                                buyDate = date,
                                periodType = periodType,
                                notes = notes
                            )
                        )
                        // 名称为空时，异步用 StockNameResolver 补全
                        if (name.isEmpty()) {
                            try {
                                val resolved = com.chin.stockanalysis.stock.database.StockNameResolver
                                    .resolve(ctx.applicationContext, code)
                                if (resolved.isNotBlank() && resolved != code) {
                                    db.realPositionDao().updateStockName(rowId, resolved)
                                    Log.i(TAG, "✅ 真实持仓名称已补全: $code → $resolved")
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
                            android.widget.Toast.makeText(ctx, "❌ 添加失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 编辑真实持仓（先列出活跃真仓供选择，再弹出编辑表单） */
    protected fun showEditRealPositionDialog() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val positions = db.realPositionDao().getAllActive()
                withContext(Dispatchers.Main) {
                    if (positions.isEmpty()) {
                        showDialog("编辑持仓", "暂无真实持仓可编辑\n\n请先添加真实持仓")
                        return@withContext
                    }

                    val labels = positions.mapIndexed { i, p ->
                        val nameLabel = p.stockName.ifEmpty { "⚠️待完善" }
                        "${i + 1}. $nameLabel(${p.stockCode}) ${p.quantity}股 @ ${"%.2f".format(p.avgBuyPrice)}"
                    }.toTypedArray()

                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("✏️ 选择要编辑的持仓")
                        .setItems(labels) { _, which ->
                            showEditRealPositionForm(positions[which])
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("编辑持仓", "❌ 加载失败: ${e.message}")
                }
            }
        }
    }

    /** 编辑真实持仓表单（预填现有数据） */
    private fun showEditRealPositionForm(position: RealPositionEntity) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
        }

        val codeEt = android.widget.EditText(ctx).apply {
            setText(position.stockCode); setSingleLine()
            hint = "股票代码"
            isEnabled = false // 代码不可编辑（updatePosition 不更新代码）
        }
        val nameEt = android.widget.EditText(ctx).apply {
            setText(position.stockName); setSingleLine()
            hint = "股票名称（可留空，自动补全）"
        }
        val qtyEt = android.widget.EditText(ctx).apply {
            setText(position.quantity.toString()); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "持有数量 (股)"
        }
        val priceEt = android.widget.EditText(ctx).apply {
            setText(if (position.avgBuyPrice > 0) "%.2f".format(position.avgBuyPrice) else "")
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "买入均价"
        }
        val sectorEt = android.widget.EditText(ctx).apply {
            setText(position.sector); setSingleLine()
            hint = "板块 (可选)"
        }
        val notesEt = android.widget.EditText(ctx).apply {
            setText(position.notes); setSingleLine()
            hint = "备注 (可选)"
        }

        fun addLabel(text: String) {
            container.addView(android.widget.TextView(ctx).apply {
                this.text = text; textSize = 12f; setPadding(0, 8, 0, 2)
            })
        }
        addLabel("股票代码:"); container.addView(codeEt)
        addLabel("股票名称:"); container.addView(nameEt)
        addLabel("数量:"); container.addView(qtyEt)
        addLabel("均价:"); container.addView(priceEt)
        addLabel("板块:"); container.addView(sectorEt)
        addLabel("备注:"); container.addView(notesEt)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("✏️ 编辑持仓")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = nameEt.text.toString().trim()
                val qty = qtyEt.text.toString().trim().toIntOrNull() ?: 0
                val price = priceEt.text.toString().trim().toDoubleOrNull() ?: 0.0
                val sector = sectorEt.text.toString().trim()
                val notes = notesEt.text.toString().trim()
                // 代码不可变更，沿用原值
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
                        // 若原名称为空、现在仍未填，则异步补全；若已补全也一并写入
                        if (name.isEmpty()) {
                            try {
                                val resolved = com.chin.stockanalysis.stock.database.StockNameResolver
                                    .resolve(ctx.applicationContext, code)
                                if (resolved.isNotBlank() && resolved != code) {
                                    db.realPositionDao().updateStockName(position.id, resolved)
                                    Log.i(TAG, "✅ 编辑后名称已补全: $code → $resolved")
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
                            android.widget.Toast.makeText(ctx, "❌ 更新失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 卖出/减仓真实持仓（先列出活跃真仓供选择，再弹出卖出表单） */
    protected fun showSellRealPositionDialog() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val positions = db.realPositionDao().getAllActive()
                withContext(Dispatchers.Main) {
                    if (positions.isEmpty()) {
                        showDialog("卖出/减仓", "暂无真实持仓可卖出\n\n请先添加真实持仓")
                        return@withContext
                    }

                    val labels = positions.mapIndexed { i, p ->
                        val nameLabel = p.stockName.ifEmpty { "⚠️待完善" }
                        "${i + 1}. $nameLabel(${p.stockCode}) ${p.quantity}股 @ ${"%.2f".format(p.avgBuyPrice)}"
                    }.toTypedArray()

                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("💰 选择要卖出/减仓的持仓")
                        .setItems(labels) { _, which ->
                            showSellRealPositionForm(positions[which])
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("卖出/减仓", "❌ 加载失败: ${e.message}")
                }
            }
        }
    }

    /** 卖出/减仓表单（输入卖出数量和卖出价格） */
    private fun showSellRealPositionForm(position: RealPositionEntity) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
        }

        val nameLabel = position.stockName.ifEmpty { "⚠️待完善" }
        container.addView(android.widget.TextView(ctx).apply {
            text = "当前: $nameLabel(${position.stockCode})\n持有 ${position.quantity}股 均价 ${"%.2f".format(position.avgBuyPrice)}"
            textSize = 11f; setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, 8)
        })

        val qtyEt = android.widget.EditText(ctx).apply {
            hint = "卖出数量 (股，最多 ${position.quantity})"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val priceEt = android.widget.EditText(ctx).apply {
            hint = "卖出价格"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        container.addView(qtyEt)
        container.addView(priceEt)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("💰 卖出/减仓")
            .setView(container)
            .setPositiveButton("确认卖出") { _, _ ->
                val sellQty = qtyEt.text.toString().trim().toIntOrNull() ?: 0
                val sellPrice = priceEt.text.toString().trim().toDoubleOrNull() ?: 0.0

                if (sellQty <= 0) {
                    android.widget.Toast.makeText(ctx, "请输入有效的卖出数量", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (sellQty > position.quantity) {
                    android.widget.Toast.makeText(ctx, "卖出数量不能超过持有数量(${position.quantity})", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(ctx.applicationContext)
                        val periodType = getQuantType()
                        val tradeDate = java.time.LocalDate.now().toString()

                        if (sellQty >= position.quantity) {
                            // 全部卖出 → 标记清仓
                            db.realPositionDao().markInactive(position.id)
                        } else {
                            // 部分卖出 → 更新剩余数量（均价沿用原成本基准）
                            val remainQty = position.quantity - sellQty
                            db.realPositionDao().updateQuantity(
                                id = position.id,
                                quantity = remainQty,
                                avgBuyPrice = position.avgBuyPrice
                            )
                        }

                        // 记录卖出信息到 TTradeRecordEntity（复用现有表，tradeType="REAL_SELL"）
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
                        Log.i(TAG, "💰 真仓卖出记录: ${position.stockCode} ${sellQty}股 @${sellPrice} 盈亏${"%.2f".format(profit)}")

                        withContext(Dispatchers.Main) {
                            val msg = if (sellQty >= position.quantity)
                                "✅ 已清仓 ${position.stockName.ifEmpty { position.stockCode }}"
                            else
                                "✅ 已减仓 ${position.stockName.ifEmpty { position.stockCode }} 剩余${position.quantity - sellQty}股"
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                            statusTv.text = msg
                            refreshPositions()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "❌ 卖出失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 对真实持仓生成做T信号 */
    private fun showRealPositionTSignals() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext().applicationContext)
                val positions = db.realPositionDao().getAllActive()

                if (positions.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showDialog("真实持仓做T", "暂无真实持仓\n\n请先添加真实持仓")
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

                // 保存推荐记录（自动去重）
                if (allSignals.isNotEmpty()) {
                    tEngine.saveRecommendations(allSignals, "REAL")
                }

                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("真实持仓做T建议")
                    sb.appendLine("持仓 ${positions.size} 只 | 信号 ${allSignals.size} 个")
                    sb.appendLine("─".repeat(50))

                    if (allSignals.isEmpty()) {
                        sb.appendLine()
                        sb.appendLine("暂无做T信号")
                        sb.appendLine()
                        sb.appendLine("做T条件：")
                        sb.appendLine("• 股价接近支撑位 + RSI超卖 → 做T买入")
                        sb.appendLine("• 股价接近阻力位 + RSI超买 → 反T卖出")
                        sb.appendLine("• 需有底仓才能做T")
                        sb.appendLine()
                        sb.appendLine("💡 提示：做T信号基于技术分析（RSI+量能+K线形态+趋势），")
                        sb.appendLine("请在券商APP手动执行后记录结果")
                    } else {
                        // 趋势预警（最优先显示）
                        val risingStocks = allSignals.filter {
                            it.trendDirection.contains("上升") || it.trendDirection.contains("准备上升")
                        }
                        val fallingStocks = allSignals.filter {
                            it.trendDirection.contains("下跌") || it.trendDirection.contains("准备下跌")
                        }
                        if (risingStocks.isNotEmpty()) {
                            sb.appendLine("⚡ 准备上升: ${risingStocks.joinToString { it.stockName }}")
                        }
                        if (fallingStocks.isNotEmpty()) {
                            sb.appendLine("⚡ 准备下跌: ${fallingStocks.joinToString { it.stockName }}")
                        }
                        if (risingStocks.isNotEmpty() || fallingStocks.isNotEmpty()) {
                            sb.appendLine("─".repeat(50))
                        }

                        for (sig in allSignals) {
                            val typeLabel = when (sig.signalType) {
                                TTradeType.T_BUY -> "🟢 做T买入"
                                TTradeType.T_SELL -> "🔴 做T卖出(配对)"
                                TTradeType.RT_SELL -> "🟡 反T卖出"
                                TTradeType.RT_BUY -> "🔵 反T买回(配对)"
                            }
                            val trendIcon = when {
                                sig.trendDirection.contains("上升") -> "📈"
                                sig.trendDirection.contains("下跌") -> "📉"
                                else -> "➡️"
                            }
                            sb.appendLine("▸ $typeLabel ${sig.stockName}(${sig.stockCode})")
                            sb.appendLine("  $trendIcon 趋势: ${sig.trendDirection} | 置信度: ${sig.confidence}%")
                            sb.appendLine("  RSI: ${"%.0f".format(sig.rsi)} | 量比: ${"%.1f".format(sig.volumeRatio)}" +
                                if (sig.patternName.isNotEmpty()) " | 形态: ${sig.patternName}" else "")
                            sb.appendLine("  数量: ${sig.quantity}股 | 建议价: ${"%.2f".format(sig.suggestedPrice)} → 目标: ${"%.2f".format(sig.targetPrice)}")
                            sb.appendLine("  原因: ${sig.reason}")
                            sb.appendLine()
                        }
                        sb.appendLine("─".repeat(50))
                        sb.appendLine("💡 请在券商APP手动执行以上建议")
                        sb.appendLine("💡 执行后可通过「做T统计」记录结果")
                    }

                    showDialog("真实持仓做T建议", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("真实持仓做T", "❌ 加载失败: ${e.message}")
                }
            }
        }
    }

    /** 真实持仓做T统计 */
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
                    sb.appendLine("📊 做T统计 ($periodType)")
                    sb.appendLine("─".repeat(40))
                    sb.appendLine("💰 总盈亏: ${"%.2f".format(totalProfit)}")
                    sb.appendLine("📈 胜率: ${"%.1f".format(winRate)}% ($winCount/$totalClosed)")
                    sb.appendLine("✅ 已完成: $totalClosed 笔")
                    sb.appendLine("⏳ 未平仓: $openCount 笔")
                    sb.appendLine()
                    sb.appendLine("💡 做T统计包含模拟持仓和真实持仓的做T记录")
                    showDialog("做T统计", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDialog("做T统计", "❌ 加载失败: ${e.message}")
                }
            }
        }
    }

    /** 自动检测真实持仓的做T机会并在状态栏通知（refreshPositions 渲染完成后调用） */
    protected fun checkRealPositionTSignals() {
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
                                    TTradeType.T_BUY -> "做T买入"
                                    TTradeType.T_SELL -> "做T卖出"
                                    TTradeType.RT_SELL -> "反T卖出"
                                    TTradeType.RT_BUY -> "反T买回"
                                }
                                details.add(
                                    "${sig.stockName}(${sig.stockCode}) $typeLabel " +
                                    "建议价${"%.2f".format(sig.suggestedPrice)} → 目标${"%.2f".format(sig.targetPrice)}"
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 保存推荐记录（自动去重）
                if (allSignals.isNotEmpty()) {
                    tEngine.saveRecommendations(allSignals, "REAL")
                }

                // 获取今日待处理推荐数
                val pendingCount = tEngine.getRecommendationStats().todayPending

                val totalSignals = allSignals.size
                withContext(Dispatchers.Main) {
                    // 更新买卖评估按钮显示待处理推荐数
                    tTradeBtn.text = if (pendingCount > 0) "💰买卖评估($pendingCount) ▾" else "💰买卖评估 ▾"

                    if (totalSignals > 0) {
                        Log.i(TAG, "真仓做T信号: 共 $totalSignals 个\n${details.joinToString("\n")}")
                        statusTv.text = "真仓有 $totalSignals 个做T信号"
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════
    // 做 T（T+0 日内交易）
    // ═══════════════════════════════════════════════════

    /** 显示做 T 面板 */
    protected open fun showTTradeMenu() {
        val periodType = getQuantType()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val tEngine = TTradeEngine(requireContext())

                // 获取当前周期持仓
                val holdingOrders = db.strategyTradeOrderDao().getRecent(500)
                    .filter { (it.status == "BUYING" || it.status == "PENDING") && it.orderType == periodType }

                // 为模拟持仓生成做T信号
                val allSignals = mutableListOf<TTradeSignal>()
                for (order in holdingOrders) {
                    val signals = tEngine.generateSignals(order.stockCode, order.quantity, periodType)
                    allSignals.addAll(signals)
                }

                // 同时检查真实持仓的做T信号
                val realPositions = db.realPositionDao().getAllActive()
                for (pos in realPositions) {
                    if (pos.quantity <= 0) continue
                    val realSignals = tEngine.generateSignals(pos.stockCode, pos.quantity, "RealPosition")
                    allSignals.addAll(realSignals)
                }

                // 获取做T统计
                val stats = tEngine.getTTradeStats(periodType)

                // 获取今日推荐（后台监控生成的）
                val todayRecommendations = tEngine.getTodayRecommendations()

                // 获取推荐历史（近7天）
                val history = tEngine.getRecommendationHistory(7)

                // 获取推荐统计
                val recStats = tEngine.getRecommendationStats()

                // 获取今日做T统计摘要（含虚拟成功率）
                val today = java.time.LocalDate.now().toString()
                val dailySummary = tEngine.getDailySummary(today, periodType)

                // 获取做T成功率（7天 + 30天）
                val successRate7d = tEngine.getSuccessRate(7)
                val successRate30d = tEngine.getSuccessRate(30)

                // 在 IO 线程获取市场报告（suspend function）
                val marketReport = try {
                    com.chin.stockanalysis.strategy.market.MarketAnalyzer.analyze(requireContext(), emptyList())
                } catch (_: Exception) { null }

                withContext(Dispatchers.Main) {
                    // 更新买卖评估按钮显示待处理推荐数
                    tTradeBtn.text = if (recStats.todayPending > 0) "💰买卖评估(${recStats.todayPending}) ▾" else "💰买卖评估 ▾"
                    showTTradeDialog(allSignals, stats, periodType, todayRecommendations, history, recStats, dailySummary, marketReport, successRate7d, successRate30d)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(requireContext(), "做T面板加载失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 显示做T交易对话框（含推荐历史和每日统计） */
    private fun showTTradeDialog(
        signals: List<TTradeSignal>,
        stats: TTradeStats,
        periodType: String,
        todayRecommendations: List<TTradeRecommendationEntity>,
        history: List<TTradeRecommendationEntity>,
        recStats: RecommendationStats,
        dailySummary: DailyTSummary,
        marketReport: com.chin.stockanalysis.strategy.market.MarketAnalyzer.MarketReport? = null,
        successRate7d: TTradeSuccessRate? = null,
        successRate30d: TTradeSuccessRate? = null
    ) {
        val dialog = android.app.Dialog(requireContext())
        dialog.setTitle("做T面板 — $periodType")
        val scrollView = android.widget.ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // ── 市场环境区（外盘 + 大盘方向） ──
        try {
            if (marketReport != null) {
            val trend = marketReport.trend
            val overseas = marketReport.overseas
            val trendLabel = when (trend.direction) {
                "BULLISH" -> "多头 ↑"
                "BEARISH" -> "空头 ↓"
                else -> "震荡 ↔"
            }
            val trendColor = when (trend.direction) {
                "BULLISH" -> "#2E7D32"
                "BEARISH" -> "#C62828"
                else -> "#FF9800"
            }
            val overseasLabel = when (overseas?.direction) {
                "BULLISH" -> "偏多 ↑"
                "BEARISH" -> "偏空 ↓"
                else -> "中性"
            }
            val overseasColor = when (overseas?.direction) {
                "BULLISH" -> "#2E7D32"
                "BEARISH" -> "#C62828"
                else -> "#666666"
            }
            val weightedChange = overseas?.weightedChange?.let { "%.2f%%".format(it) } ?: "-"
            container.addView(TextView(requireContext()).apply {
                text = "大盘: $trendLabel (强度${trend.strength}) | 外盘: $overseasLabel ($weightedChange)"
                textSize = 11f
                setTextColor(Color.parseColor(trendColor))
                setPadding(0, 0, 0, 2)
            })
            if (!overseas?.impactHint.isNullOrEmpty()) {
                container.addView(TextView(requireContext()).apply {
                    text = "外盘影响: ${overseas.impactHint}"
                    textSize = 10f; setTextColor(Color.parseColor(overseasColor))
                    setPadding(0, 0, 0, 4)
                })
            }
            }
        } catch (_: Exception) {
            // 市场数据加载失败时不阻塞 UI
        }

        // ── 做T统计区 ──
        val winRateStr = "%.1f%%".format(stats.winRate)
        val profitStr = "%.2f".format(stats.totalProfit)
        container.addView(TextView(requireContext()).apply {
            text = "做T统计: 总盈亏 $profitStr | 胜率 $winRateStr | 已完成 ${stats.totalTrades} 笔 | 未平仓 ${stats.openCount} 笔"
            textSize = 11f; setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 4)
        })

        // 推荐统计
        container.addView(TextView(requireContext()).apply {
            text = "推荐统计: 今日待处理 ${recStats.todayPending} 条 | 近7天已执行 ${recStats.weekExecuted} 条"
            textSize = 10f; setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, 8)
        })

        // ── 今日做T统计摘要 ──
        if (dailySummary.totalRecommendations > 0) {
            container.addView(TextView(requireContext()).apply {
                text = "今日做T统计: 推荐 ${dailySummary.totalRecommendations} 条 | " +
                       "目标触及 ${dailySummary.targetHitCount} 条 | " +
                       "虚拟成功率 ${"%.1f".format(dailySummary.virtualSuccessRate)}% | " +
                       "已执行 ${dailySummary.executedCount} 条"
                textSize = 10f
                setTextColor(Color.parseColor(if (dailySummary.virtualSuccessRate >= 50) "#2E7D32" else "#C62828"))
                setPadding(0, 0, 0, 8)
            })
        }

        // ── 做T成功率统计（7天 / 30天） ──
        val sr7 = successRate7d
        val sr30 = successRate30d
        if ((sr7 != null && sr7.total > 0) || (sr30 != null && sr30.total > 0)) {
            // 分隔线
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
                setBackgroundColor(Color.parseColor("#E0E0E0"))
                setPadding(0, 4, 0, 4)
            })

            container.addView(TextView(requireContext()).apply {
                text = "做T成功率:"
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#1565C0"))
                setPadding(0, 4, 0, 4)
            })

            // 7天成功率
            if (sr7 != null && sr7.total > 0) {
                val rateColor = if (sr7.overallSuccessRate >= 50) "#2E7D32" else "#C62828"
                container.addView(TextView(requireContext()).apply {
                    text = "近7天: 共${sr7.total}条 | 触及目标 ${sr7.hitCount}条 (${ "%.1f%%".format(sr7.overallSuccessRate)}) | " +
                           "盈利 ${sr7.profitableCount}条 (${ "%.1f%%".format(sr7.profitRate)}) | " +
                           "做T ${sr7.tCount}条(${ "%.0f%%".format(sr7.tSuccessRate)}) 反T ${sr7.rtCount}条(${ "%.0f%%".format(sr7.rtSuccessRate)}) | " +
                           "均盈 ${"%.2f%%".format(sr7.avgVirtualProfitPct)}"
                    textSize = 10f; setTextColor(Color.parseColor(rateColor))
                    setPadding(0, 0, 0, 4)
                })
            }

            // 30天成功率
            if (sr30 != null && sr30.total > 0) {
                val rateColor30 = if (sr30.overallSuccessRate >= 50) "#2E7D32" else "#C62828"
                container.addView(TextView(requireContext()).apply {
                    text = "近30天: 共${sr30.total}条 | 触及目标 ${sr30.hitCount}条 (${ "%.1f%%".format(sr30.overallSuccessRate)}) | " +
                           "盈利 ${sr30.profitableCount}条 (${ "%.1f%%".format(sr30.profitRate)}) | " +
                           "做T ${sr30.tCount}条(${ "%.0f%%".format(sr30.tSuccessRate)}) 反T ${sr30.rtCount}条(${ "%.0f%%".format(sr30.rtSuccessRate)}) | " +
                           "均盈 ${"%.2f%%".format(sr30.avgVirtualProfitPct)}"
                    textSize = 10f; setTextColor(Color.parseColor(rateColor30))
                    setPadding(0, 0, 0, 8)
                })
            }
        }

        // ── 当前做T信号（即时生成的） ──
        container.addView(TextView(requireContext()).apply {
            text = if (signals.isEmpty()) "即时做T信号: 无" else "即时做T信号 (${signals.size} 个):"
            textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 8, 0, 4)
        })

        if (signals.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "暂无做T信号\n做T条件：股价接近支撑位→做T买入 / 接近阻力位→反T卖出"
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
                    val trendIcon = when {
                        signal.trendDirection.contains("上升") -> "📈"
                        signal.trendDirection.contains("下跌") -> "📉"
                        else -> "➡️"
                    }
                    val trendLine = if (signal.trendDirection.isNotEmpty())
                        "$trendIcon 趋势: ${signal.trendDirection} | 置信度: ${signal.confidence}%\n" else ""
                    val rsiLine = if (signal.rsi != 50.0 || signal.volumeRatio != 1.0)
                        "RSI: ${"%.0f".format(signal.rsi)} | 量比: ${"%.1f".format(signal.volumeRatio)}" +
                        if (signal.patternName.isNotEmpty()) " | 形态: ${signal.patternName}" else ""
                        else ""
                    val rsiLineFull = if (rsiLine.isNotEmpty()) "$rsiLine\n" else ""

                    text = "${signal.stockName} (${signal.stockCode})\n" +
                           "操作: ${signal.signalType.label} ${signal.signalType.desc}\n" +
                           trendLine +
                           rsiLineFull +
                           "价格: ${"%.2f".format(signal.suggestedPrice)} → 目标 ${"%.2f".format(signal.targetPrice)}\n" +
                           "数量: ${signal.quantity}股 | 预期收益: ${"%.2f%%".format(signal.expectedProfitPct)}\n" +
                           "原因: ${signal.reason}"
                    textSize = 10f; setTextColor(Color.parseColor("#444444"))
                    setLineSpacing(2f, 1f)
                })
                // 执行按钮
                val btnRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 0)
                }
                btnRow.addView(Button(requireContext()).apply {
                    text = "执行 ${signal.signalType.label}"
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
                                // 同时在推荐表中查找匹配的推荐并标记为已执行
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
                                    android.widget.Toast.makeText(requireContext(), "${signal.signalType.label} 已执行", android.widget.Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                    showTTradeMenu()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(requireContext(), "执行失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                })
                signalCard.addView(btnRow)
                container.addView(signalCard)
            }
        }

        // ── 今日推荐（后台监控生成的） ──
        val pendingRecs = todayRecommendations.filter { it.status == "PENDING" }
        if (pendingRecs.isNotEmpty()) {
            // 分隔线
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
                setBackgroundColor(Color.parseColor("#DDDDDD"))
                setPadding(0, 8, 0, 8)
            })

            container.addView(TextView(requireContext()).apply {
                text = "后台推荐 (${pendingRecs.size} 条待处理):"
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#1565C0"))
                setPadding(0, 4, 0, 4)
            })

            for (rec in pendingRecs) {
                val recCard = createRecommendationCard(rec, dialog)
                container.addView(recCard)
            }
        }

        // ── 推荐历史（近7天，含已处理） ──
        val processedHistory = history.filter { it.status != "PENDING" }.take(20)
        if (processedHistory.isNotEmpty()) {
            // 分隔线
            container.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
                setBackgroundColor(Color.parseColor("#DDDDDD"))
                setPadding(0, 8, 0, 8)
            })

            container.addView(TextView(requireContext()).apply {
                text = "推荐历史 (近7天 ${processedHistory.size} 条):"
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#666666"))
                setPadding(0, 4, 0, 4)
            })

            for (rec in processedHistory) {
                val statusLabel = when (rec.status) {
                    "EXECUTED" -> "已执行"
                    "IGNORED" -> "已忽略"
                    "EXPIRED" -> "已过期"
                    "TARGET_HIT" -> "✅目标触及"
                    "TARGET_MISSED" -> "❌目标未达"
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
                    "T_BUY" -> "做T买入"
                    "T_SELL" -> "做T卖出"
                    "RT_SELL" -> "反T卖出"
                    "RT_BUY" -> "反T买回"
                    else -> rec.signalType
                }
                // 判断时间（从 createdAt 时间戳）
                val judgeTime = if (rec.createdAt > 0) {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(rec.createdAt))
                } else ""
                // 虚拟盈亏
                val profitStr = if (rec.virtualProfitPct != 0.0) {
                    "${if (rec.virtualProfitPct > 0) "+" else ""}${"%.2f%%".format(rec.virtualProfitPct)}"
                } else ""
                val profitColor = if (rec.virtualProfitPct > 0) "#2E7D32" else if (rec.virtualProfitPct < 0) "#C62828" else "#999999"

                val histCard = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8, 4, 8, 4)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = 2
                    }
                }
                // 第一行：日期 股票 信号 状态
                val row1 = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                row1.addView(TextView(requireContext()).apply {
                    text = "${rec.tradeDate.takeLast(5)} $judgeTime ${rec.stockName.take(6)} $signalLabel"
                    textSize = 9f; setTextColor(Color.parseColor("#555555"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row1.addView(TextView(requireContext()).apply {
                    text = statusLabel
                    textSize = 9f; setTextColor(Color.parseColor(statusColor))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                histCard.addView(row1)
                // 第二行：推荐价 目标价 虚拟盈亏
                val detailLine = "推荐 ${"%.2f".format(rec.suggestedPrice)} → 目标 ${"%.2f".format(rec.targetPrice)}" +
                        if (profitStr.isNotEmpty()) " | 盈亏 $profitStr" else ""
                histCard.addView(TextView(requireContext()).apply {
                    text = detailLine
                    textSize = 8f; setTextColor(Color.parseColor(profitColor))
                    setPadding(0, 1, 0, 0)
                })
                container.addView(histCard)
            }
        }

        scrollView.addView(container)
        // 底部关闭按钮
        container.addView(Button(requireContext()).apply {
            text = "关闭"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#757575"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(36)).apply {
                topMargin = dpToPx(12)
            }
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(scrollView)
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    /** 创建推荐卡片（含执行/忽略按钮） */
    private fun createRecommendationCard(
        rec: TTradeRecommendationEntity,
        dialog: android.app.Dialog
    ): LinearLayout {
        val signalLabel = when (rec.signalType) {
            "T_BUY" -> "做T买入"
            "T_SELL" -> "做T卖出"
            "RT_SELL" -> "反T卖出"
            "RT_BUY" -> "反T买回"
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
                   "推荐价: ${"%.2f".format(rec.suggestedPrice)} → 目标 ${"%.2f".format(rec.targetPrice)}\n" +
                   "数量: ${rec.quantity}股 | 预期: ${"%.2f%%".format(rec.expectedProfitPct)}"
            textSize = 10f; setTextColor(Color.parseColor("#333333"))
            setLineSpacing(2f, 1f)
        })

        // 按钮行
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }

        // 执行按钮
        btnRow.addView(Button(requireContext()).apply {
            text = "已执行"
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
                            android.widget.Toast.makeText(requireContext(), "已标记为执行", android.widget.Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            showTTradeMenu()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(requireContext(), "操作失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })

        // 忽略按钮
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
    // 数据管理
    // ═══════════════════════════════════════════════════

    /** 显示数据菜单（两级分类版，所有周期共用） */
    protected open fun showDataMenu(anchor: View) {
        val periodLabel = getQuantType()
        val categories = arrayOf(
            "📋 持仓与交易",
            "📊 报告与分析",
            "⚙️ 设置与维护"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("🗄️ 数据中心 — $periodLabel")
            .setItems(categories) { _, which ->
                when (which) {
                    0 -> showDataCategory_Trades()
                    1 -> showDataCategory_Reports()
                    2 -> showDataCategory_Maintenance()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 数据分类 1：持仓与交易 */
    private fun showDataCategory_Trades() {
        val items = arrayOf(
            "📋 查看交易记录",
            "💰 查看持仓详情"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("📋 持仓与交易")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTradeHistory()
                    1 -> loadPositions()
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    /** 数据分类 2：报告与分析 */
    private fun showDataCategory_Reports() {
        val periodLabel = getQuantType()
        val items = arrayOf(
            "📊 全周期报告",
            "📊 ${periodLabel}量化报告",
            "📊 查看精选池",
            "💰 持有收益历史",
            "📅 月度热点前瞻",
            "🔥 查看热门板块报告",
            "📋 查看策略报告",
            "📈 回溯测试",
            "🔧 拟合调优",
            "🔄 全周期拟合"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("📊 报告与分析")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTradeReportsHistory()
                    1 -> showPeriodReportHistory()
                    2 -> showFinalPool()
                    3 -> showHoldingProfitHistory()
                    4 -> showMonthlyForecast()
                    5 -> exportHotSectors()
                    6 -> exportStrategyReport()
                    7 -> onBacktrackClick()
                    8 -> onFittingClick()
                    9 -> runCrossPeriodFitting()
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    /** 数据分类 3：设置与维护 */
    private fun showDataCategory_Maintenance() {
        val items = arrayOf(
            "🧠 市场记忆设置",
            "🧹 清空持仓",
            "🧹 清空报告"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("⚙️ 设置与维护")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showMarketMemoryDialog()
                    1 -> confirmAndClearPositions()
                    2 -> confirmAndClearReports()
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    // ═══════════════════════════════════════
    // 数据导出/导入（从中线提升到基类，所有周期共用）
    // ═══════════════════════════════════════

    /** 查看全周期量化报告历史（所有周期汇总，不含大盘/严选详情） */
    protected open fun showTradeReportsHistory() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val entities = db.dailyPeriodResultDao().getRecent(100)
                    .filter { it.strategyId != "FINAL_POOL" && it.strategyId != "BACKTRACK" }
                if (entities.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "暂无量化报告记录", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val codeToName = try { db.stockBasicDao().getAll().associate { it.code to it.name } } catch (_: Exception) { emptyMap() }
                val grouped = entities.groupBy { it.tradeDate }
                val sb = StringBuilder()
                val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                sb.appendLine("📊 全周期报告 (共 ${entities.size} 条)")
                sb.appendLine("总纲：逃顶要快，抄底要慢")
                sb.appendLine()
                for ((date, items) in grouped.toSortedMap().entries.reversed().take(10)) {
                    sb.appendLine("━━━ $date ━━━")
                    for (item in items) {
                        val mainBoardLabel = if (item.mainBoardFilter) " 主板" else ""
                        val timeStr = timeFmt.format(java.util.Date(item.createdAt))
                        sb.appendLine("  [$timeStr] ${item.strategyName}[${item.periodDays}日]$mainBoardLabel: ${item.stockCount}只信号")
                        if (item.newsStrengthScore > 0) sb.appendLine("    新闻力度:${item.newsStrengthScore} 轮动惩罚:${item.rotationPenalty}")
                        // 解析 finalTop3Json（兼容 flat 和 nested 两种格式）
                        val top3Json = try { org.json.JSONArray(item.finalTop3Json) } catch (_: Exception) { org.json.JSONArray() }
                        if (top3Json.length() > 0) {
                            val picks = extractTopPicks(top3Json, codeToName)
                            for ((idx, pick) in picks.withIndex()) {
                                if (idx >= 3) break
                                val sector = try { com.chin.stockanalysis.stock.database.StockDataCenter.getSectorsByStock(pick.code).firstOrNull() ?: "" } catch (_: Exception) { "" }
                                val sectorStr = if (sector.isNotBlank()) " [$sector]" else ""
                                sb.appendLine("    Top${idx+1}: ${pick.name}(${pick.code.takeLast(6)})$sectorStr 得分:${pick.score}")
                            }
                        }
                        // 从 stockCodesJson 显示实际选到的股票
                        try {
                            val codesJson = org.json.JSONArray(item.stockCodesJson)
                            if (codesJson.length() > 0) {
                                val codes = (0 until codesJson.length()).map { codesJson.optString(it) }.filter { it.isNotBlank() }
                                if (codes.isNotEmpty()) {
                                    val names = codes.take(5).map { c ->
                                        val n = codeToName[c] ?: c.takeLast(6)
                                        n
                                    }
                                    sb.appendLine("    选股: ${names.joinToString("、")}")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) { showDialog("全周期报告", sb.toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** 从 finalTop3Json 提取 top picks（兼容 flat 和 nested DAG 格式） */
    private data class TopPick(val code: String, val name: String, val score: Int)

    private fun extractTopPicks(top3Json: org.json.JSONArray, codeToName: Map<String, String>): List<TopPick> {
        val picks = mutableListOf<TopPick>()
        for (i in 0 until top3Json.length()) {
            val obj = top3Json.optJSONObject(i) ?: continue
            // 格式A: flat {code, name, score/reason}
            val flatCode = obj.optString("code", "")
            if (flatCode.isNotBlank()) {
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: codeToName[flatCode] ?: flatCode.takeLast(6)
                val score = if (obj.has("score")) obj.optInt("score") else obj.optInt("strength")
                picks.add(TopPick(flatCode, name, score))
                continue
            }
            // 格式B: nested {strategyId, strategyName, picks: [{code, name, strength}]}
            val innerPicks = obj.optJSONArray("picks")
            if (innerPicks != null) {
                for (j in 0 until innerPicks.length()) {
                    val inner = innerPicks.optJSONObject(j) ?: continue
                    val code = inner.optString("code", "")
                    if (code.isNotBlank()) {
                        val name = inner.optString("name").takeIf { it.isNotBlank() } ?: codeToName[code] ?: code.takeLast(6)
                        val score = if (inner.has("score")) inner.optInt("score") else inner.optInt("strength")
                        picks.add(TopPick(code, name, score))
                    }
                }
            }
        }
        return picks.sortedByDescending { it.score }
    }

    /** 查看当前周期独立量化报告（含大盘均线 + 6项严选详情） */
    protected open fun showPeriodReportHistory() {
        val quantType = getQuantType()
        val periodStrategyId = when (quantType) {
            "UltraShortQuant" -> "DAG_ULTRA_SHORT"
            "ShortTermQuant" -> "DAG_SHORT"
            "MidTermQuant" -> "DAG_MID"
            "LongTermQuant" -> "DAG_LONG"
            else -> "DAG_${quantType.uppercase()}"
        }
        val periodLabel = when (quantType) {
            "UltraShortQuant" -> "超短线"
            "ShortTermQuant" -> "短线"
            "MidTermQuant" -> "中线"
            "LongTermQuant" -> "长线"
            else -> quantType
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val entities = db.dailyPeriodResultDao().getRecent(200)
                    .filter { it.strategyId == periodStrategyId }
                if (entities.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "暂无${periodLabel}量化报告", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val codeToName = try { db.stockBasicDao().getAll().associate { it.code to it.name } } catch (_: Exception) { emptyMap() }
                val grouped = entities.groupBy { it.tradeDate }
                val sb = StringBuilder()
                val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                sb.appendLine("📊 $periodLabel 量化报告 (共 ${entities.size} 条)")
                sb.appendLine("总纲：逃顶要快，抄底要慢")
                sb.appendLine()
                for ((date, items) in grouped.toSortedMap().entries.reversed().take(10)) {
                    sb.appendLine("━━━ $date ━━━")
                    for (item in items) {
                        val timeStr = timeFmt.format(java.util.Date(item.createdAt))
                        sb.appendLine("  [$timeStr] ${item.strategyName}[${item.periodDays}日]: ${item.stockCount}只信号")
                        if (item.newsStrengthScore > 0) sb.appendLine("    新闻力度:${item.newsStrengthScore} 轮动惩罚:${item.rotationPenalty}")
                        // 从 pipelineFlowJson 提取大盘均线 + 严选详情
                        try {
                            val flowJson = org.json.JSONObject(item.pipelineFlowJson)
                            val pipelines = flowJson.optJSONObject("pipelines")
                            if (pipelines != null) {
                                val keys = pipelines.keys()
                                while (keys.hasNext()) {
                                    val pipeObj = pipelines.optJSONObject(keys.next()) ?: continue
                                    // 大盘均线检查
                                    val marketMa = pipeObj.optJSONObject("n_market_ma_check")
                                    if (marketMa != null) {
                                        val converged = marketMa.optBoolean("isConvergedUpward", false)
                                        val desc = marketMa.optString("description", "")
                                        sb.appendLine("    大盘均线: ${if (converged) "✅" else "⚠️"} $desc")
                                    }
                                    // 严选检查
                                    val strict = pipeObj.optJSONObject("n_strict_selection")
                                    if (strict != null) {
                                        val passed = strict.optInt("passedCount", 0)
                                        val total = strict.optInt("totalCount", 0)
                                        sb.appendLine("    严选检查: $passed/$total 只全部通过")
                                        val passedStocks = strict.optJSONObject("passedStocks")
                                        if (passedStocks != null && passedStocks.length() > 0) {
                                            val stockKeys = passedStocks.keys()
                                            while (stockKeys.hasNext()) {
                                                val k = stockKeys.next()
                                                val d = passedStocks.optJSONObject(k) ?: continue
                                                val n = d.optString("name", k.takeLast(6))
                                                val cnt = d.optInt("passCount", 0)
                                                val total = d.optInt("totalChecks", 7)
                                                val conv = if (d.optBoolean("convergenceOk")) "✓" else "✗"
                                                val bull = if (d.optBoolean("bullishAligned")) "✓" else "✗"
                                                val dur = if (d.optBoolean("convergenceDurationOk")) "✓" else "✗"
                                                val vol = if (d.optBoolean("volumeConditionOk")) "✓" else "✗"
                                                val dd = if (d.optBoolean("drawdownOk")) "✓" else "✗"
                                                val ma60 = if (d.optBoolean("ma60Rising")) "✓" else "✗"
                                                val year = if (d.optBoolean("aboveYearLine")) "✓" else "✗"
                                                val chg = if (d.optBoolean("changePctOk")) "✓" else "✗"
                                                val above = if (d.optBoolean("aboveAllMAs")) "✓" else "✗"
                                                sb.appendLine("    $n($cnt/$total): 粘合$conv 多头$bull 持续$dur 量能$vol 跌幅$dd MA60$ma60 年线$year 涨幅$chg 站上$above")
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                        // 选到的股票 + 评分
                        val top3Json = try { org.json.JSONArray(item.finalTop3Json) } catch (_: Exception) { org.json.JSONArray() }
                        if (top3Json.length() > 0) {
                            val picks = extractTopPicks(top3Json, codeToName)
                            for ((idx, pick) in picks.withIndex()) {
                                if (idx >= 5) break
                                sb.appendLine("    Top${idx+1}: ${pick.name}(${pick.code.takeLast(6)}) 得分:${pick.score}")
                            }
                        }
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) { showDialog("$periodLabel 量化报告", sb.toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** 查看精选池 */
    protected open fun showFinalPool() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val entities = db.dailyPeriodResultDao().getRecent(100)
                    .filter { it.strategyId == "FINAL_POOL" }
                    .sortedByDescending { it.tradeDate }
                if (entities.isEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "暂无精选池记录", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val latest = entities.first()
                val codes = try { org.json.JSONArray(latest.stockCodesJson) } catch (_: Exception) { org.json.JSONArray() }
                val crossDayJson = try { org.json.JSONArray(latest.finalTop3Json) } catch (_: Exception) { org.json.JSONArray() }
                val sb = StringBuilder()
                sb.appendLine("📋 精选最终池")
                sb.appendLine("交易日: ${latest.tradeDate}")
                sb.appendLine("共 ${latest.stockCount} 只股票输入AI")
                sb.appendLine()
                sb.appendLine("🔥 跨日聚合命中 Top10:")
                for (i in 0 until crossDayJson.length()) {
                    val obj = crossDayJson.getJSONObject(i)
                    sb.appendLine("  ${obj.optString("code").takeLast(6)}: ${obj.optInt("days")}天命中")
                }
                sb.appendLine()
                sb.appendLine("📊 完整精选池 (${codes.length()} 只):")
                for (i in 0 until minOf(codes.length(), 60)) {
                    sb.appendLine("  ${i+1}. ${codes.optString(i)}")
                }
                if (codes.length() > 60) sb.appendLine("  ... 共 ${codes.length()} 只，仅显示前60")
                withContext(Dispatchers.Main) { showDialog("精选池_${latest.tradeDate}", sb.toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "载入精选池失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    protected open fun exportToJson(exporter: DataExportImport) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val path = exporter.exportAllToJson()
                withContext(Dispatchers.Main) { showDialog("导出成功", "文件已保存到:\n$path") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    protected open fun showExportFiles(exporter: DataExportImport) {
        val files = exporter.getExportFiles()
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), "暂无导出文件", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.appendLine("📂 已导出的文件:")
        sb.appendLine()
        for (f in files) {
            sb.appendLine("${f.name} (${f.length()/1024}KB)")
            sb.appendLine("  修改时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))}")
            sb.appendLine()
        }
        showDialog("导出文件列表", sb.toString())
    }

    protected open fun showDbStats(exporter: DataExportImport) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val stats = exporter.getDatabaseStats()
            withContext(Dispatchers.Main) { showDialog("数据库统计", stats) }
        }
    }

    /** 清空持仓（带二次确认） */
    protected open fun confirmAndClearPositions() {
        val periodType = getQuantType()
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空持仓")
            .setMessage("确定要清空所有 $periodType 持仓记录吗？此操作不可撤销。")
            .setPositiveButton("确定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val orders = db.strategyTradeOrderDao().getRecent(500)
                            .filter { it.orderType == periodType }
                        for (order in orders) {
                            db.strategyTradeOrderDao().deleteById(order.id)
                        }
                        withContext(Dispatchers.Main) {
                            refreshPositions()
                            statusTv.text = "✅ 已清空 $periodType 持仓"
                            Toast.makeText(requireContext(), "持仓已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 清空选股（带二次确认） */
    protected open fun confirmAndClearPicks() {
        val periodType = getQuantType()
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空选股")
            .setMessage("确定要清空所有 $periodType 选股记录吗？")
            .setPositiveButton("确定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val source = getWatchlistSource()
                        val today = java.time.LocalDate.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                        )
                        db.userWatchlistDao().deleteBySourceAndDate(source, today)
                        withContext(Dispatchers.Main) {
                            lastPickStocks = emptyList()
                            pickStockCodes = emptySet()
                            refreshPositions()
                            statusTv.text = "✅ 已清空 $periodType 选股"
                            Toast.makeText(requireContext(), "选股已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空选股失败: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 清空报告（带二次确认） */
    protected open fun confirmAndClearReports() {
        AlertDialog.Builder(requireContext())
            .setTitle("🧹 清空报告")
            .setMessage("确定要清空所有量化报告记录吗？此操作不可撤销。")
            .setPositiveButton("确定") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val entities = db.dailyPeriodResultDao().getRecent(1000)
                        for (e in entities) {
                            try { db.dailyPeriodResultDao().deleteByDate(e.tradeDate) } catch (_: Exception) {}
                        }
                        withContext(Dispatchers.Main) {
                            statusTv.text = "✅ 已清空报告"
                            Toast.makeText(requireContext(), "报告已清空", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { statusTv.text = "❌ 清空失败: ${e.message?.take(40)}" }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 全周期拟合：依序执行 4 个周期 DAG Pipeline，
     * 合并结果找出被 ≥2 个周期同时选中的股票（高置信度）。
     */
    protected fun runCrossPeriodFitting() {
        buildBtn.isEnabled = false; buildBtn.text = "⏳ 全周期..."
        progressBar.visibility = View.VISIBLE
        statusTv.text = "🔄 全周期拟合：启动 4 个 Pipeline..."

        lifecycleScope.launch(Dispatchers.IO) {
            val tradeDate = try {
                com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().toString()
            } catch (_: Exception) { java.time.LocalDate.now().toString() }
            val today = java.time.LocalDate.now().toString()

            val periods = listOf(
                Triple("ultra_short", "超短线", 30),
                Triple("short_term", "短线", 60),
                Triple("mid_term", "中线", 60),
                Triple("long_term", "长线", 60)
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
                    statusTv.text = "🔄 全周期拟合：$label Pipeline 执行中..."
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
                    results[label] = if (r.success) "✅ ${r.ordersCount}笔订单" else "❌ 失败"
                    if (!r.success) allSuccess = false
                } catch (e: Exception) {
                    results[label] = "❌ ${e.message?.take(30)}"
                    allSuccess = false
                }
            }

            // 合并：查询今日所有 BUYING 订单，按股票分组
            val db = StockDatabase.getInstance(requireContext())
            val todayOrders = db.strategyTradeOrderDao().getByDate(tradeDate)
                .filter { it.status == "BUYING" || it.status == "HOLDING" }

            // 按股票代码分组，统计被几个周期选中
            val stockPeriods = todayOrders.groupBy { it.stockCode }
                .mapValues { (_, orders) -> orders.map { it.orderType }.distinct() }

            val multiHit = stockPeriods.filter { it.value.size >= 2 }
                .entries.sortedByDescending { it.value.size }

            // 生成报告
            val report = buildString {
                appendLine("═══ 全周期拟合报告 ═══")
                appendLine("交易日: $tradeDate")
                appendLine()
                appendLine("── 各周期执行结果 ──")
                results.forEach { (period, summary) -> appendLine("$period: $summary") }
                appendLine()

                if (multiHit.isNotEmpty()) {
                    appendLine("── 🎯 多周期共振（≥2 周期选中）──")
                    multiHit.forEach { (code, periods) ->
                        val name = todayOrders.firstOrNull { it.stockCode == code }?.stockName ?: code
                        appendLine("★ $name ($code) ← ${periods.joinToString("+")}")
                    }
                    appendLine()
                    appendLine("💡 多周期共振股票置信度更高，建议优先关注")
                } else {
                    appendLine("── 无多周期共振股票 ──")
                    appendLine("各周期选股无交集，市场分歧较大")
                }

                appendLine()
                appendLine("── 各周期选股明细 ──")
                stockPeriods.entries.sortedByDescending { it.value.size }.forEach { (code, periods) ->
                    val name = todayOrders.firstOrNull { it.stockCode == code }?.stockName ?: code
                    val score = todayOrders.firstOrNull { it.stockCode == code }?.scoreAtBuy ?: 0
                    appendLine("$name ($code) | 分数:$score | ${periods.joinToString("+")}")
                }
            }

            withContext(Dispatchers.Main) {
                showDialog("全周期拟合报告", report)
                statusTv.text = if (allSuccess) "✅ 全周期拟合完成" else "⚠ 部分周期失败"
                buildBtn.isEnabled = true; updateBuildButtonText()
                progressBar.visibility = View.GONE
                refreshPositions()
            }
        }
    }

    /** 清除数据 */
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
                    statusTv.text = "✅ 已清除 ${orders.size} 条 $quantType 数据"
                    Toast.makeText(requireContext(), "已清除 ${orders.size} 条记录", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 清除失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    /** 按日期清除数据 */
    protected fun clearDataByDate(date: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                StockDatabase.getInstance(requireContext()).strategyTradeOrderDao().deleteByDate(date)
                withContext(Dispatchers.Main) {
                    refreshPositions()
                    statusTv.text = "✅ 已清除 $date 的 ${getQuantType()} 数据"
                    Toast.makeText(requireContext(), "已清除 $date 的数据", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 清除失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 持仓管理（共用）
    // ═══════════════════════════════════════════════════

    /**
     * 动态补全持仓订单中缺失的股票名称
     *
     * 任何周期（超短/短/中/长线）选到的股票，如果订单中 stockName 为空，
     * 通过 StockNameResolver 统一补全（stock_basics → daily_snapshot → 新浪API）。
     * 补全后持久化到 strategy_trade_orders 表，避免重复查询。
     *
     * @return 补全名称后的订单列表（与输入列表同序，但 stockName 可能已更新）
     */
    private suspend fun ensureStockNames(
        db: StockDatabase,
        orders: List<StrategyTradeOrderEntity>
    ): List<StrategyTradeOrderEntity> {
        val missingNameOrders = orders.filter { it.stockName.isBlank() }
        if (missingNameOrders.isEmpty()) return orders

        val missingCodes = missingNameOrders.map { it.stockCode }.distinct()
        Log.i("QuantFragmentBase", "🔧 ensureStockNames: ${missingCodes.size} 只股票缺少名称，开始补全")

        // 统一调用 StockNameResolver 批量解析
        val nameMap = com.chin.stockanalysis.stock.database.StockNameResolver
            .resolveBatch(requireContext(), missingCodes)
        Log.i("QuantFragmentBase", "  StockNameResolver 解析命中: ${nameMap.size}/${missingCodes.size}")

        // 持久化补全结果到数据库
        var fixedCount = 0
        for (order in missingNameOrders) {
            val name = nameMap[order.stockCode]
            if (!name.isNullOrBlank()) {
                db.strategyTradeOrderDao().updateStockName(order.id, name)
                fixedCount++
            }
        }
        Log.i("QuantFragmentBase", "  ✅ ensureStockNames 完成: 补全 $fixedCount/${missingNameOrders.size} 笔订单名称")

        // 返回更新后的列表（用 nameMap 覆盖空名称）
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

    /**
     * 计算数据哈希，用于判断是否需要重新渲染。
     * 基于订单的 id/status/tradeDate/stockCode 和选股列表内容。
     */
    protected fun computeDataHash(
        orders: List<StrategyTradeOrderEntity>,
        picks: List<Triple<String, String, Int>>
    ): Int {
        var h = 17
        for (o in orders) {
            h = h * 31 + o.id.hashCode()
            h = h * 31 + (o.status ?: "").hashCode()
            h = h * 31 + (o.tradeDate ?: "").hashCode()
            h = h * 31 + (o.stockCode ?: "").hashCode()
        }
        for (p in picks) {
            h = h * 31 + p.hashCode()
        }
        return h
    }

    /** 刷新持仓 */
    open fun refreshPositions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()
                val period = orderTypePeriod(quantType)
                val rawOrders = db.strategyTradeOrderDao().getRecent(500)
                    .filter {
                        orderTypePeriod(it.orderType) == period &&
                        (it.status == "BUYING" || it.status == "PENDING")
                    }
                    .sortedByDescending { it.tradeDate }

                // 从 DB 恢复选股区（lastPickStocks 为空时从 user_watchlist 读取）
                if (lastPickStocks.isEmpty()) {
                    val source = getWatchlistSource()
                    val today = TradingDayPickerView.recentTradingDay(browsingDate).format(DATE_FMT)
                    val picks = db.userWatchlistDao().getBySourceAndDate(source, today)
                    if (picks.isNotEmpty()) {
                        lastPickStocks = picks.map { Triple(it.stockCode, it.stockName, it.scoreAtAdd) }
                        pickStockCodes = lastPickStocks.map { it.first }.toSet()
                    }
                }

                // ── 数据哈希比对：未变化则跳过重新渲染 ──
                val currentHash = computeDataHash(rawOrders, lastPickStocks)
                if (currentHash == lastRefreshHash) {
                    Log.d(TAG, "⏭️ refreshPositions: 数据未变化，跳过重新渲染 (hash=$currentHash)")
                    return@launch
                }
                lastRefreshHash = currentHash
                Log.d(TAG, "🔄 refreshPositions: 数据已变化，重新渲染 (hash=$currentHash)")

                if (rawOrders.isEmpty() && lastPickStocks.isEmpty()) {
                    // 仍然渲染持仓/选股 title（即使为空）
                    withContext(Dispatchers.Main) {
                        renderPositions(emptyList(), emptyList(), emptyMap())
                    }
                    return@launch
                }

                // 动态补全缺失的股票名称（任何周期通用）
                val orders = ensureStockNames(db, rawOrders)

                val minTradeDate = orders.minByOrNull { it.tradeDate }?.tradeDate ?: browsingDate.format(DATE_FMT)
                val allDates = db.dailySnapshotDao().getAvailableDates(20)
                var dates = allDates
                    .filter { it >= minTradeDate && it <= browsingDate.format(DATE_FMT) }
                    .filter { dateStr ->
                        // 自动排除周六日和节假日
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

                // 实时行情补充：获取今日实时价格，确保盘中也能看到最新价和盈亏
                val todayStr = browsingDate.format(DATE_FMT)
                val realtimeMap = try {
                    com.chin.stockanalysis.stock.data.StockDataSourceFactory
                        .createDefaultRepository(requireContext().applicationContext)
                        .getRealtime(orders.map { it.stockCode })
                } catch (_: Exception) { emptyMap() }
                if (realtimeMap.isNotEmpty()) {
                    for ((code, rt) in realtimeMap) {
                        if (rt.price > 0) {
                            priceMap.getOrPut(code) { mutableMapOf() }[todayStr] = rt.price
                        }
                    }
                    // 确保今日日期在 dates 列表中（盘中快照可能尚未入库）
                    if (todayStr !in dates) {
                        dates.toMutableList().also {
                            it.add(todayStr); it.sort()
                        }.let { dates = it }
                    }
                }

                withContext(Dispatchers.Main) {
                    renderPositions(orders, dates, priceMap)
                }
            } catch (_: Exception) {}
        }
    }

    /** 渲染持仓列表（多日表格视图，可被子类覆写） */
    protected open fun renderPositions(
        orders: List<StrategyTradeOrderEntity>,
        dates: List<String>,
        priceMap: Map<String, Map<String, Double>>
    ) {
        positionContainer.removeAllViews()

        // ── 持仓区（始终显示标题，即使为空） ──
        renderOrderTable(orders, dates, priceMap, "持仓")

        // ── 选股区（始终显示标题，即使为空） ──
        if (lastPickStocks.isNotEmpty()) {
            // 先显示占位
            renderEmptySection("选股", titleColor = "#6A1B9A")

            // 异步查询 user_watchlist 完整数据
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val db = StockDatabase.getInstance(requireContext())
                    val source = getWatchlistSource()
                    val today = TradingDayPickerView.recentTradingDay(browsingDate).format(DATE_FMT)
                    val picks = db.userWatchlistDao().getBySourceAndDate(source, today)

                    // 获取实时价格
                    val realtimeMap = try {
                        com.chin.stockanalysis.stock.data.StockDataSourceFactory
                            .createDefaultRepository(requireContext().applicationContext)
                            .getRealtime(picks.map { it.stockCode })
                    } catch (_: Exception) { emptyMap() }

                    // 转换为 StrategyTradeOrderEntity
                    val pickOrders = picks.map { pick ->
                        val price = if (pick.buyPrice > 0) pick.buyPrice
                            else realtimeMap[pick.stockCode]?.price ?: 0.0
                        StrategyTradeOrderEntity(
                            strategyId = getQuantType(),
                            stockCode = pick.stockCode,
                            stockName = pick.stockName,
                            tradeDate = pick.addedDate,
                            buyPrice = price,
                            buyTime = pick.addedDate,
                            quantity = 100,
                            orderType = getQuantType(),
                            status = "WATCHING",
                            reason = "选股",
                            scoreAtBuy = pick.scoreAtAdd
                        )
                    }

                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        // 移除占位，重新渲染完整表格
                        positionContainer.removeAllViews()
                        // 重新渲染持仓区
                        if (orders.isNotEmpty()) {
                            renderOrderTable(orders, dates, priceMap, "持仓")
                        } else {
                            renderEmptySection("持仓")
                        }
                        // 渲染选股区完整表格
                        if (pickOrders.isNotEmpty()) {
                            renderOrderTable(pickOrders, dates, priceMap, "选股", titleColor = "#6A1B9A")
                        } else if (lastPickStocks.isNotEmpty()) {
                            // DB 查无数据（日期不一致），用内存中的 lastPickStocks 渲染
                            renderPickListSection()
                        } else {
                            renderEmptySection("选股", titleColor = "#6A1B9A")
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        // 查询失败，用 lastPickStocks 简单渲染
                        positionContainer.removeAllViews()
                        if (orders.isNotEmpty()) {
                            renderOrderTable(orders, dates, priceMap, "持仓")
                        } else {
                            renderEmptySection("持仓")
                        }
                        renderPickListSection()
                    }
                }
            }
        } else {
            renderEmptySection("选股", titleColor = "#6A1B9A")
        }
    }

    /** 渲染空白区块占位（保持持仓/选股两区块标题始终可见） */
    private fun renderEmptySection(sectionTitle: String, titleColor: String = "#1A1A2E") {
        val quantType = getQuantType()
        val titlePrefix = if (positionTitlePrefix.isNotEmpty()) positionTitlePrefix else quantType
        val fullTitle = if (titlePrefix.contains("量化")) "${titlePrefix}${sectionTitle}" else "${titlePrefix}量化${sectionTitle}"
        val isPickSection = sectionTitle == "选股"

        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, if (isPickSection) 12 else 2, 0, 2)
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = "📌 $fullTitle"
            textSize = 12f; setTextColor(Color.parseColor(titleColor))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = "暂无${sectionTitle}"; textSize = 10f
            setTextColor(Color.parseColor("#999999")); gravity = Gravity.END
        })
        titleRow.addView(TextView(requireContext()).apply {
            text = " 🔄"; textSize = 14f
            setTextColor(Color.parseColor("#1976D2")); setPadding(8, 0, 0, 0)
            isClickable = true; setOnClickListener { refreshPositions() }
        })
        positionContainer.addView(titleRow)
    }

    /**
     * 渲染订单表格区块（持仓或选股共用）
     * @param sectionTitle "持仓" 或 "选股"
     * @param titleColor 标题颜色
     */
    protected fun renderOrderTable(
        orders: List<StrategyTradeOrderEntity>,
        dates: List<String>,
        priceMap: Map<String, Map<String, Double>>,
        sectionTitle: String,
        titleColor: String = "#1A1A2E"
    ) {
        val quantType = getQuantType()
        val titlePrefix = if (positionTitlePrefix.isNotEmpty()) positionTitlePrefix else quantType
        // 选股区标题简化：xxx量化选股
        val displayTitle = if (sectionTitle == "选股") "选股" else sectionTitle
        val fullTitle = if (titlePrefix.contains("量化")) "${titlePrefix}${displayTitle}" else "${titlePrefix}量化${displayTitle}"

        // 计算总盈亏
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

        // 固化持有收益（仅持仓区）
        if (sectionTitle == "持仓") {
            val tradeDateStr = browsingDate.format(DATE_FMT)
            val stockCodes = orders.joinToString(",") { it.stockCode }
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val entity = com.chin.stockanalysis.strategy.trade.PeriodHoldingProfitEntity(
                        periodType = quantType, tradeDate = tradeDateStr,
                        holdingCount = orders.size, totalCost = totalCost, totalValue = totalValue,
                        totalPnl = totalPnl, totalPnlPct = totalPnlPct, stockCodes = stockCodes
                    )
                    StockDatabase.getInstance(appCtx).periodHoldingProfitDao().insert(entity)
                } catch (_: Exception) {}
            }
        }

        // 标题行
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, if (sectionTitle == "选股") 12 else 2, 0, 2)
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = "📌 $fullTitle"
            textSize = 12f; setTextColor(Color.parseColor(titleColor))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 总持仓/总选股 X 只
        val countLabel = if (sectionTitle == "选股") "总选股" else "总持仓"
        titleRow.addView(TextView(requireContext()).apply {
            text = "$countLabel ${orders.size} 只"
            textSize = 10f; setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 6, 0)
        })
        // 清空按钮
        titleRow.addView(TextView(requireContext()).apply {
            text = "清空"
            textSize = 10f; setTextColor(Color.parseColor("#C62828"))
            setPadding(0, 0, 6, 0)
            isClickable = true
            setOnClickListener {
                if (sectionTitle == "选股") confirmAndClearPicks()
                else confirmAndClearPositions()
            }
        })
        // 总盈亏
        titleRow.addView(TextView(requireContext()).apply {
            text = "总盈亏 $pnlStr"
            textSize = 10f; setTextColor(Color.parseColor(pnlColor))
            setPadding(0, 0, 4, 0)
        })
        // 刷新
        titleRow.addView(TextView(requireContext()).apply {
            text = "🔄"; textSize = 14f
            setTextColor(Color.parseColor("#1976D2"))
            isClickable = true; setOnClickListener { refreshPositions() }
        })
        positionContainer.addView(titleRow)

        // 多日涨跌表格
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 4)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        for (header in listOf("股票", "建仓日", "成本"))
            headerRow.addView(createCell(header, 60, "#666666", 10f, bold = true))
        headerRow.addView(createCell(sectionTitle, 45, "#666666", 9f, bold = true))
        if (showMultiDayPrices) {
            for (date in dates) {
                headerRow.addView(createCell(date.takeLast(5), 72, "#666666", 10f, bold = true))
            }
        } else if (dates.isNotEmpty()) {
            headerRow.addView(createCell("今日", 72, "#666666", 10f, bold = true))
        }
        headerRow.addView(createCell("卖出", 50, "#666666", 9f, bold = true))
        table.addView(headerRow)

        for (order in orders) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 2)
            }
            // 股票名/代码
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
            nameCell.setOnClickListener {
                com.chin.stockanalysis.ui.StockDetailNavigator.navigateFromFragment(
                    this@QuantFragmentBase, order.stockCode, order.stockName, price = order.buyPrice
                )
            }
            nameCell.isClickable = true
            nameCell.foreground = android.graphics.drawable.GradientDrawable().apply { setColor(0); setCornerRadius(8f) }
            row.addView(nameCell)

            row.addView(createCell(order.tradeDate.takeLast(5), 60, "#333333", 10f))
            row.addView(createCell("¥${"%.2f".format(order.buyPrice)}", 60, "#333333", 10f))
            row.addView(createCell("${order.quantity}", 45, "#1565C0", 9f))

            // 多日价格列
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
            } else if (dates.isNotEmpty()) {
                val todayPrice = priceMap[order.stockCode]?.get(dates.last())
                val cellText: String; val cellColor: String
                if (todayPrice == null) { cellText = "—"; cellColor = "#999999" }
                else {
                    val pnl = if (order.buyPrice > 0) (todayPrice - order.buyPrice) / order.buyPrice * 100 else 0.0
                    cellText = "¥${"%.2f".format(todayPrice)}\n${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)}%"
                    cellColor = if (pnl >= 0) "#D32F2F" else "#2E7D32"
                }
                row.addView(TextView(requireContext()).apply {
                    text = cellText; textSize = 9f
                    setTextColor(Color.parseColor(cellColor)); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dpToPx(72), LinearLayout.LayoutParams.WRAP_CONTENT)
                    setPadding(2, 4, 2, 4); setLineSpacing(2f, 1f)
                })
            }

            // 卖出按钮（持仓和选股都可以卖出）
            val lastPrice = priceMap[order.stockCode]?.get(dates.lastOrNull())
            val sellBtn = Button(requireContext()).apply {
                text = "卖"; textSize = 9f; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#C62828"))
                layoutParams = LinearLayout.LayoutParams(dpToPx(50), dpToPx(28)); setPadding(2, 0, 2, 0)
                isEnabled = lastPrice != null
                setOnClickListener { showSellConfirmDialog(order, lastPrice ?: order.buyPrice) }
            }
            row.addView(sellBtn)
            table.addView(row)
        }
        scroll.addView(table)
        positionContainer.addView(scroll)
    }

    /** 渲染选股简单列表（当选股订单不在当前 orders 列表中时使用） */
    private fun renderPickListSection() {
        val pickTitle = if (positionTitlePrefix.isNotEmpty()) positionTitlePrefix else getQuantType()
        val pickRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 2)
        }
        pickRow.addView(TextView(requireContext()).apply {
            text = "📌 ${pickTitle}量化选股（${lastPickStocks.size} 只）"
            textSize = 12f; setTextColor(Color.parseColor("#6A1B9A"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        pickRow.addView(TextView(requireContext()).apply {
            text = " 🔄"; textSize = 14f
            setTextColor(Color.parseColor("#1976D2")); setPadding(8, 0, 0, 0)
            isClickable = true; setOnClickListener { refreshPositions() }
        })
        positionContainer.addView(pickRow)

        for ((code, name, score) in lastPickStocks) {
            val itemRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(8, 3, 0, 3)
            }
            itemRow.addView(TextView(requireContext()).apply {
                text = "${name.take(4)}($code)"
                textSize = 11f; setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })
            itemRow.addView(TextView(requireContext()).apply {
                text = "评分 $score"
                textSize = 10f; setTextColor(Color.parseColor("#1976D2"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            positionContainer.addView(itemRow)
        }
    }

    /** 一键建仓：将选股区的股票写入 DB 作为正式持仓 */
    protected fun convertPicksToPositions() {
        if (lastPickStocks.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("确认建仓")
            .setMessage("将 ${lastPickStocks.size} 只选股建仓？\n\n${lastPickStocks.joinToString("\n") { "  ${it.second}(${it.first}) 评分${it.third}" }}")
            .setPositiveButton("确认建仓") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = StockDatabase.getInstance(requireContext())
                        val codes = lastPickStocks.map { it.first }
                        // 获取实时价格
                        val realtime = try {
                            com.chin.stockanalysis.stock.data.StockDataSourceFactory
                                .createDefaultRepository(requireContext().applicationContext)
                                .getRealtime(codes)
                        } catch (_: Exception) { emptyMap() }
                        val today = TradingDayPickerView.recentTradingDay().format(DATE_FMT)
                        val now = java.time.LocalTime.now().toString().take(8)
                        val quantType = getQuantType()
                        val entities = lastPickStocks.map { (code, name, score) ->
                            val price = realtime[code]?.price ?: 0.0
                            StrategyTradeOrderEntity(
                                strategyId = quantType,
                                stockCode = code, stockName = name,
                                tradeDate = today, buyPrice = price,
                                buyTime = "$today $now", quantity = 100,
                                orderType = quantType, status = "BUYING",
                                reason = "一键建仓", scoreAtBuy = score
                            )
                        }
                        db.strategyTradeOrderDao().insertAll(entities)
                        withContext(Dispatchers.Main) {
                            val count = entities.size
                            pickStockCodes = emptySet()
                            lastPickStocks = emptyList()
                            refreshPositions()
                            statusTv.text = "✅ 已建仓 $count 只"
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            statusTv.text = "建仓失败: ${e.message}"
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 显示单笔卖出确认对话框 */
    protected fun showSellConfirmDialog(order: StrategyTradeOrderEntity, currentPrice: Double) {
        val profitPct = if (order.buyPrice > 0) (currentPrice - order.buyPrice) / order.buyPrice * 100 else 0.0
        val profitStr = if (profitPct >= 0) "+${"%.2f".format(profitPct)}%" else "${"%.2f".format(profitPct)}%"

        val message = """
            股票: ${order.stockName} (${order.stockCode})
            买入价: ¥${"%.2f".format(order.buyPrice)}
            当前价: ¥${"%.2f".format(currentPrice)}
            盈亏: $profitStr
            数量: ${order.quantity} 股
            
            确认卖出？
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("💰 确认卖出")
            .setMessage(message)
            .setPositiveButton("确认卖出") { _, _ -> executeSingleSell(order, currentPrice) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 执行单笔卖出 */
    protected fun executeSingleSell(order: StrategyTradeOrderEntity, sellPrice: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val profitPct = if (order.buyPrice > 0) (sellPrice - order.buyPrice) / order.buyPrice * 100 else 0.0

                if (order.status == "WATCHING") {
                    // 选股区：从 user_watchlist 删除
                    db.userWatchlistDao().deleteByCode(order.stockCode)
                    withContext(Dispatchers.Main) {
                        lastPickStocks = lastPickStocks.filter { it.first != order.stockCode }
                        pickStockCodes = lastPickStocks.map { it.first }.toSet()
                        statusTv.text = "✅ 已卖出 ${order.stockName} @ ¥${"%.2f".format(sellPrice)} (${"%.2f".format(profitPct)}%)"
                        Toast.makeText(requireContext(), "卖出成功: ${order.stockName}", Toast.LENGTH_SHORT).show()
                        refreshPositions()
                    }
                } else {
                    // 持仓区：更新 strategy_trade_orders
                    db.strategyTradeOrderDao().updateSellInfo(
                        id = order.id, status = "SOLD", sellPrice = sellPrice,
                        sellTime = LocalDate.now().toString() + " " + java.time.LocalTime.now().toString().take(8),
                        profitPct = profitPct
                    )
                    withContext(Dispatchers.Main) {
                        statusTv.text = "✅ 已卖出 ${order.stockName} @ ¥${"%.2f".format(sellPrice)} (${"%.2f".format(profitPct)}%)"
                        Toast.makeText(requireContext(), "卖出成功: ${order.stockName}", Toast.LENGTH_SHORT).show()
                        refreshPositions()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 卖出失败: ${e.message?.take(40)}"
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 通用对话框与导出
    // ═══════════════════════════════════════════════════

    /** 显示通用对话框 */
    protected fun showDialog(title: String, content: String) {
        val sv = ScrollView(requireContext())
        sv.addView(TextView(requireContext()).apply {
            text = content; textSize = 10f; setTextColor(Color.parseColor("#333333"))
            setPadding(16, 12, 16, 12); setLineSpacing(2f, 1.1f); setTypeface(Typeface.MONOSPACE)
        })
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .create()
            .apply {
                show()
                window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            }
    }

    /**
     * 在内容区顶部插入 Pipeline 节点执行详情（可折叠）。
     * 每个 Node 显示：名称、状态、输入/输出数量、过滤原因、实际股票代码。
     */
    protected fun showPipelineNodeDetails(
        title: String,
        result: com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor.DagExecResult
    ) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val details = result.nodeFlowDetails
        if (details.isEmpty()) return

        // ── 详情容器（先声明，供标题列点击引用） ──
        val detailContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(8, 4, 8, 4)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val arrowTv = TextView(ctx).apply {
            text = "▶"
            textSize = 12f
            setTextColor(Color.parseColor("#3F51B5"))
            setPadding(0, 0, (8 * density).toInt(), 0)
        }

        // ── 可折叠标题列 ──
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, (8 * density).toInt(), 12, (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#E8EAF6"))
            setOnClickListener {
                detailContainer.visibility =
                    if (detailContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                arrowTv.text = if (detailContainer.visibility == View.VISIBLE) "▼" else "▶"
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerRow.addView(arrowTv)
        val titleTv = TextView(ctx).apply {
            text = "$title  (${details.size} 个节点, ${result.totalElapsedMs}ms)"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#283593"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleTv)
        val statusBadge = TextView(ctx).apply {
            text = if (result.success) "✅" else "❌"
            textSize = 14f
        }
        headerRow.addView(statusBadge)

        for (node in details) {
            val nodeCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 6, 8, 6)
                setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * density).toInt() }
            }

            // 第一行：节点名 + 状态 + 耗时
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val statusIcon = if (node.errorMsg.isNotEmpty()) "❌" else if (node.success) "✅" else "⚠️"
            val nameTv = TextView(ctx).apply {
                text = "$statusIcon ${node.nodeName}"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#1A237E"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(nameTv)
            if (node.elapsedMs > 0) {
                val timeTv = TextView(ctx).apply {
                    text = "${node.elapsedMs}ms"
                    textSize = 10f
                    setTextColor(Color.parseColor("#9E9E9E"))
                }
                row1.addView(timeTv)
            }
            nodeCard.addView(row1)

            // 第二行：输入 → 输出
            val flowTv = TextView(ctx).apply {
                val flowText = buildString {
                    append("输入 ${node.inputCount} → 输出 ${node.outputCount}")
                    if (node.filterCount > 0) append(" | 过滤 ${node.filterCount}: ${node.filterReason}")
                }
                text = flowText
                textSize = 11f
                setTextColor(Color.parseColor("#424242"))
                setPadding(0, 2, 0, 2)
            }
            nodeCard.addView(flowTv)

            // 第三行：输入股票代码
            if (node.inputCodes.isNotEmpty()) {
                val inTv = TextView(ctx).apply {
                    text = "⬅ 入: ${node.inputCodes.joinToString(", ")}"
                    textSize = 10f
                    setTextColor(Color.parseColor("#1565C0"))
                    setPadding(0, 1, 0, 1)
                }
                nodeCard.addView(inTv)
            }

            // 第四行：输出股票代码
            if (node.outputCodes.isNotEmpty()) {
                val outTv = TextView(ctx).apply {
                    text = "➡ 出: ${node.outputCodes.joinToString(", ")}"
                    textSize = 10f
                    setTextColor(Color.parseColor("#2E7D32"))
                    setPadding(0, 1, 0, 1)
                }
                nodeCard.addView(outTv)
            }

            // 错误信息
            if (node.errorMsg.isNotEmpty()) {
                val errTv = TextView(ctx).apply {
                    text = "❌ ${node.errorMsg}"
                    textSize = 10f
                    setTextColor(Color.parseColor("#C62828"))
                    setPadding(0, 2, 0, 0)
                }
                nodeCard.addView(errTv)
            }

            detailContainer.addView(nodeCard)
        }

        // 插入到 positionContainer 顶部
        positionContainer.addView(headerRow, 0)
        positionContainer.addView(detailContainer, 1)
    }

    /** 显示各周期持有收益历史 */
    protected fun showHoldingProfitHistory() {
        val appCtx = requireContext().applicationContext
        val currentPeriod = getQuantType()
        val periodNames = mapOf(
            "UltraShortQuant" to "超短线",
            "ShortTermQuant" to "短线",
            "MidTermQuant" to "中线",
            "LongTermQuant" to "长线"
        )
        val currentName = periodNames[currentPeriod] ?: currentPeriod

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(appCtx)
                // 查询当前周期最近 30 天的收益记录
                val records = db.periodHoldingProfitDao().getByPeriodType(currentPeriod, 30)
                // 同时查询所有周期的最新记录
                val latestAll = db.periodHoldingProfitDao().getLatestAllPeriods()
                // 查询所有周期的历史记录（用于计算日变化）
                val allPeriodLatest = mutableMapOf<String, List<PeriodHoldingProfitEntity>>()
                for (p in periodNames.keys) {
                    allPeriodLatest[p] = db.periodHoldingProfitDao().getByPeriodType(p, 2)
                }

                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("💰 持有收益历史 — $currentName")
                    sb.appendLine("━".repeat(40))
                    sb.appendLine()

                    // 各周期最新收益概览（含日变化）
                    sb.appendLine("📊 各周期最新持仓收益：")
                    sb.appendLine()
                    var grandPnl = 0.0
                    var grandCost = 0.0
                    for (entity in latestAll.sortedBy { it.periodType }) {
                        val name = periodNames[entity.periodType] ?: entity.periodType
                        val pnlStr = if (entity.totalPnl >= 0) "+" else ""
                        val color = if (entity.totalPnl >= 0) "🔴" else "🟢"
                        // 计算与前一交易日的变化
                        val prevRecords = allPeriodLatest[entity.periodType] ?: emptyList()
                        val changeStr = if (prevRecords.size >= 2) {
                            val prev = prevRecords[1]
                            val diff = entity.totalPnl - prev.totalPnl
                            val arrow = if (diff >= 0) "↑" else "↓"
                            " | 日变化$arrow${"%.0f".format(kotlin.math.abs(diff))}"
                        } else ""
                        sb.appendLine("  $color $name: ${pnlStr}¥${"%.0f".format(entity.totalPnl)} (${"%.2f".format(entity.totalPnlPct)}%) | ${entity.holdingCount}只 | ${entity.tradeDate}$changeStr")
                        grandPnl += entity.totalPnl
                        grandCost += entity.totalCost
                    }
                    if (latestAll.isNotEmpty()) {
                        val grandPct = if (grandCost > 0) grandPnl / grandCost * 100 else 0.0
                        val gStr = if (grandPnl >= 0) "+" else ""
                        sb.appendLine("  ────────────────────────────────")
                        sb.appendLine("  📊 合计: ${gStr}¥${"%.0f".format(grandPnl)} (${"%.2f".format(grandPct)}%)")
                    }
                    sb.appendLine()

                    // 当前周期历史走势
                    sb.appendLine("📈 $currentName 最近收益走势：")
                    sb.appendLine()
                    if (records.isEmpty()) {
                        sb.appendLine("  暂无历史记录")
                    } else {
                        sb.appendLine("  日期         盈亏金额       盈亏%    持仓数  日变化")
                        sb.appendLine("  " + "─".repeat(46))
                        for ((idx, r) in records.withIndex()) {
                            val pnlStr = if (r.totalPnl >= 0) "+" else ""
                            // 计算与前一条记录的变化
                            val dayChange = if (idx < records.size - 1) {
                                val prev = records[idx + 1]
                                val diff = r.totalPnl - prev.totalPnl
                                val arrow = if (diff >= 0) "↑" else "↓"
                                "$arrow${"%.0f".format(kotlin.math.abs(diff))}"
                            } else "—"
                            sb.appendLine("  ${r.tradeDate}  ${pnlStr}¥${"%8.0f".format(r.totalPnl)}  ${pnlStr}${"%6.2f".format(r.totalPnlPct)}%  ${r.holdingCount}只   $dayChange")
                        }
                    }

                    showDialog("持有收益历史", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "加载收益历史失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 显示月度热点前瞻报告 */
    protected fun showMonthlyForecast() {
        statusTv.text = "📅 正在生成月度热点前瞻..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val forecaster = com.chin.stockanalysis.strategy.market.SectorTrendForecaster(
                    requireContext().applicationContext
                )
                val report = forecaster.forecast()

                withContext(Dispatchers.Main) {
                    statusTv.text = "✅ ${report.targetMonth} 月度前瞻已生成（${report.sectors.size} 个板块）"
                    showDialog("月度热点前瞻", report.narrative)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTv.text = "❌ 月度前瞻失败: ${e.message}"
                    Toast.makeText(requireContext(), "月度前瞻失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 显示交易历史 */
    protected fun showTradeHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val quantType = getQuantType()
                val orders = db.strategyTradeOrderDao().getRecent(100)
                    .filter { it.orderType == quantType }
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.appendLine("📋 交易记录 (最近100条)"); sb.appendLine()
                    if (orders.isEmpty()) sb.appendLine("暂无交易记录")
                    else for (order in orders) {
                        val statusEmoji = when (order.status) {
                            "SOLD" -> "✅"; "BUYING" -> "🟢"; "FAILED" -> "❌"; else -> "⏳"
                        }
                        sb.appendLine("$statusEmoji ${order.stockName}(${order.stockCode.takeLast(6)})")
                        sb.appendLine("   买入: ${order.tradeDate} ¥${"%.2f".format(order.buyPrice)} x${order.quantity}")
                        if (order.status == "SOLD") {
                            val profitStr = if (order.profitPct >= 0) "+${"%.2f".format(order.profitPct)}%"
                            else "${"%.2f".format(order.profitPct)}%"
                            sb.appendLine("   卖出: ¥${"%.2f".format(order.sellPrice)} 收益: $profitStr")
                        } else sb.appendLine("   状态: ${order.status}")
                        sb.appendLine()
                    }
                    showDialog("交易记录", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 导出热门板块（从 sector_daily_record 表读取已保存的数据） */
    protected fun exportHotSectors() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val recentDays = db.sectorDailyRecordDao().getRecentDays(30)
                if (recentDays.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "无热门板块数据（请先导入或运行量化）", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val sb = StringBuilder()
                sb.appendLine("🔥 热门板块报告（最近 30 个交易日）")
                sb.appendLine("导出时间: ${LocalDate.now()}"); sb.appendLine()

                // 按日期分组
                val grouped = recentDays.groupBy { it.date }.toSortedMap()
                for ((date, sectors) in grouped) {
                    val hotCount = sectors.count { it.isHot in listOf("S", "Y", "true", "A") }
                    sb.appendLine("📅 $date（${sectors.size} 板块，${hotCount} 热门）")
                    for (s in sectors.sortedByDescending { it.hotScore }.take(10)) {
                        val tag = when (s.rank) { in 1..3 -> "🔥"; in 4..10 -> "⭐"; else -> "  " }
                        val hotLabel = when (s.isHot) {
                            "S", "Y", "true" -> "🔥热门"
                            "A" -> "⭐关注"
                            else -> ""
                        }
                        val hotSuffix = if (hotLabel.isNotEmpty()) " $hotLabel" else ""
                        val consecLabel = if (s.consecutiveHotDays > 0) " 连板${s.consecutiveHotDays}天" else ""
                        sb.appendLine("  $tag ${s.sectorName} 涨幅:${"%.2f".format(s.changePct)}% 主力:${"%.0f".format(s.mainNetInflow)}万 评分:${"%.1f".format(s.hotScore)}$consecLabel$hotSuffix")
                    }
                    sb.appendLine()
                }
                withContext(Dispatchers.Main) {
                    showDialog("热门板块报告", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 导出策略报告（各策略的权重、拟合结果、回测表现） */
    protected fun exportStrategyReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val eng = com.chin.stockanalysis.strategy.StrategyEngineHolder.get()
                val strategies = eng.getStrategies().filter { eng.isEnabled(it.id) }

                val sb = StringBuilder()
                sb.appendLine("📋 策略配置报告")
                sb.appendLine("导出时间: ${LocalDate.now()}")
                sb.appendLine("量化类型: ${getQuantType()}"); sb.appendLine()

                for (strategy in strategies) {
                    sb.appendLine("━━ ${strategy.name} (${strategy.id}) ━━")
                    sb.appendLine("  类别: ${strategy.category.label}")
                    sb.appendLine("  权重因子:")
                    for (f in strategy.weightFactors) {
                        sb.appendLine("    - ${f.label}: ${f.weight}%")
                    }
                    // 读取最近的拟合结果
                    val snapshots = try {
                        db.strategyWeightSnapshotDao().getByStrategy(strategy.id)
                    } catch (_: Exception) { emptyList() }
                    if (snapshots.isNotEmpty()) {
                        val latest = snapshots.first()
                        sb.appendLine("  最近拟合: ${latest.date} | 命中: ${latest.hitCount}")
                    }
                    sb.appendLine()
                }

                // 市场环境
                try {
                    val marketReport = com.chin.stockanalysis.strategy.market.MarketAnalyzer.analyze(requireContext(), emptyList())
                    sb.appendLine("━━ 当前市场环境 ━━")
                    sb.appendLine("  趋势: ${marketReport.trend.direction} (强度:${marketReport.trend.strength})")
                    sb.appendLine("  ADX: ${marketReport.trend.adx?.let { "%.1f".format(it) } ?: "N/A"}")
                    sb.appendLine("  卖出类型: ${marketReport.sellType.sellType}")
                    sb.appendLine()
                    sb.append(marketReport.summary)
                } catch (_: Exception) {
                    sb.appendLine("（市场环境分析失败）")
                }

                withContext(Dispatchers.Main) {
                    showDialog("策略报告", sb.toString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 显示市场记忆设置对话框（AI检测 + 多选板块 + 新增） */
    protected fun showMarketMemoryDialog() {
        val memory = com.chin.stockanalysis.strategy.sector.UserMarketMemory(requireContext())
        val ctx = requireContext()

        // 先显示载入中的对话框，异步获取数据后更新内容
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }
        val loadingTv = android.widget.TextView(ctx).apply {
            text = "⏳ 正在载入市场记忆数据..."
            textSize = 13f
            setPadding(16, 24, 16, 24)
            gravity = android.view.Gravity.CENTER
        }
        container.addView(loadingTv)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🧠 市场记忆设置")
            .setView(container)
            .setNegativeButton("关闭", null)
            .show()

        // 异步载入 AI 检测 + 近期热门板块 + 新闻板块 + 所有已记录板块
        lifecycleScope.launch(Dispatchers.IO) {
            val aiDetection = memory.aiYearDetection
            val hotSectors = memory.getRecentHotSectors()
            val newsSectors = memory.getRecentNewsSectors()
            val allSectors = memory.getAllFocusSectors()
            val activeNames = allSectors.filter { it.isActive }.map { it.sectorName }

            // 建议板块 = 热门+新闻 中尚未记录的
            val suggestedNames = (hotSectors.map { it.sectorName } + newsSectors)
                .distinct().filter { name -> allSectors.none { it.sectorName == name } }.take(8)

            withContext(Dispatchers.Main) {
                if (!isAdded) { dialog.dismiss(); return@withContext }
                container.removeAllViews()

                // ════ AI 检测区 ════
                val aiSection = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#F0F7FF"))
                    setPadding(16, 12, 16, 12)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.bottomMargin = 8
                    layoutParams = lp
                }

                aiSection.addView(android.widget.TextView(ctx).apply {
                    text = "🤖 AI 市场检测"
                    textSize = 13f
                    setTextColor(Color.parseColor("#1565C0"))
                    setTypeface(null, Typeface.BOLD)
                })

                aiSection.addView(android.widget.TextView(ctx).apply {
                    text = "📊 风格判断：$aiDetection"
                    textSize = 11f
                    setTextColor(Color.parseColor("#333333"))
                    setPadding(0, 4, 0, 8)
                })

                if (hotSectors.isNotEmpty()) {
                    aiSection.addView(android.widget.TextView(ctx).apply {
                        text = "🔥 近5日热门板块（按热度排序）："
                        textSize = 11f
                        setTextColor(Color.parseColor("#555555"))
                        setTypeface(null, Typeface.BOLD)
                    })
                    val hotSb = StringBuilder()
                    for (s in hotSectors.take(6)) {
                        val trendIcon = if (s.totalChangePct > 0) "📈" else if (s.totalChangePct < 0) "📉" else "➡️"
                        hotSb.appendLine("  $trendIcon ${s.sectorName} 热度${"%.0f".format(s.avgHotScore)} 累计${"%+.1f".format(s.totalChangePct)}% ${s.hotDays}天热门")
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
                        text = "📰 新闻高频板块：${newsSectors.joinToString(" · ")}"
                        textSize = 10f
                        setTextColor(Color.parseColor("#666666"))
                        setPadding(0, 2, 0, 4)
                    })
                }

                // AI 刷新按钮
                aiSection.addView(android.widget.Button(ctx).apply {
                    text = "🔄 重新检测市场风格"
                    textSize = 11f
                    setOnClickListener {
                        text = "⏳ 检测中..."
                        isEnabled = false
                        lifecycleScope.launch {
                            val result = memory.detectSectorYearByIndex()
                            memory.aiYearDetection = result
                            com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                            withContext(Dispatchers.Main) {
                                text = "✅ $result"
                                isEnabled = true
                                dialog.dismiss()
                                showMarketMemoryDialog()
                            }
                        }
                    }
                })

                container.addView(aiSection)

                // ════ 已记录板块区（多选 chips） ════
                val manualSection = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#FAFAFA"))
                    setPadding(16, 12, 16, 12)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = 4
                    layoutParams = lp
                }

                manualSection.addView(android.widget.TextView(ctx).apply {
                    text = "📋 已记录的主力板块（点击切换启用/停用）"
                    textSize = 13f
                    setTextColor(Color.parseColor("#E65100"))
                    setTypeface(null, Typeface.BOLD)
                })

                if (allSectors.isNotEmpty()) {
                    val chipsFlow = com.chin.stockanalysis.ui.FlowLayout(ctx).apply {
                        setPadding(0, 8, 0, 8)
                    }
                    for (sector in allSectors) {
                        val isActive = sector.isActive
                        val chip = android.widget.TextView(ctx).apply {
                            text = if (isActive) "✓ ${sector.sectorName}" else "☐ ${sector.sectorName}"
                            textSize = 11f
                            setPadding(20, 8, 20, 8)
                            setTextColor(if (isActive) Color.WHITE else Color.parseColor("#999999"))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = 16f
                                setColor(if (isActive) Color.parseColor("#4CAF50") else Color.parseColor("#EEEEEE"))
                            }
                            setOnClickListener {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    memory.toggleSector(sector.sectorName, !isActive)
                                    com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                                    withContext(Dispatchers.Main) {
                                        dialog.dismiss()
                                        showMarketMemoryDialog()
                                    }
                                }
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
                } else {
                    manualSection.addView(android.widget.TextView(ctx).apply {
                        text = "（尚无记录，请在下方新增）"
                        textSize = 11f
                        setTextColor(Color.parseColor("#999999"))
                        setPadding(0, 4, 0, 8)
                    })
                }

                // ════ 新增板块输入 ════
                manualSection.addView(android.widget.TextView(ctx).apply {
                    text = "➕ 新增关注板块"
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                    setPadding(0, 8, 0, 4)
                })

                val input = android.widget.EditText(ctx).apply {
                    hint = "输入板块名称"
                    textSize = 12f
                    tag = "sector_input"
                }
                manualSection.addView(input)

                manualSection.addView(android.widget.Button(ctx).apply {
                    text = "✅ 新增"
                    textSize = 11f
                    setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) {
                            android.widget.Toast.makeText(ctx, "请输入板块名称", android.widget.Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            memory.addOrActivateSector(name)
                            com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(ctx, "已新增「$name」", android.widget.Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                showMarketMemoryDialog()
                            }
                        }
                    }
                })

                // ════ 建议板块 chips ════
                if (suggestedNames.isNotEmpty()) {
                    manualSection.addView(android.widget.TextView(ctx).apply {
                        text = "💡 近期热门但未记录（点击新增）："
                        textSize = 10f
                        setTextColor(Color.parseColor("#999999"))
                        setPadding(0, 8, 0, 4)
                    })
                    val suggestFlow = com.chin.stockanalysis.ui.FlowLayout(ctx)
                    for (name in suggestedNames) {
                        val chip = android.widget.TextView(ctx).apply {
                            text = "+ $name"
                            textSize = 11f
                            setPadding(20, 8, 20, 8)
                            setTextColor(Color.parseColor("#555555"))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = 16f
                                setColor(Color.parseColor("#FFF3E0"))
                            }
                            setOnClickListener {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    memory.addOrActivateSector(name)
                                    com.chin.stockanalysis.strategy.sector.StrategyMarketContext.invalidateCache()
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(ctx, "已新增「$name」", android.widget.Toast.LENGTH_SHORT).show()
                                        dialog.dismiss()
                                        showMarketMemoryDialog()
                                    }
                                }
                            }
                        }
                        val lp = com.chin.stockanalysis.ui.FlowLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        lp.setMargins(0, 0, 8, 8)
                        chip.layoutParams = lp
                        suggestFlow.addView(chip)
                    }
                    manualSection.addView(suggestFlow)
                }

                container.addView(manualSection)
            }
        }
    }

}
