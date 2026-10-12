package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.indicatorbt.IndicatorBacktestEngine
import com.chin.stockanalysis.strategy.indicatorbt.IndicatorBacktestEngine.Bar
import com.chin.stockanalysis.strategy.indicatorbt.IndicatorBacktestEngine.BtParams
import com.chin.stockanalysis.strategy.indicatorbt.IndicatorBacktestEngine.Result
import com.chin.stockanalysis.strategy.indicatorbt.IndicatorBacktestEngine.StrategyType
import com.chin.stockanalysis.strategy.indicatorbt.SavedScanPool
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine as Ssa
import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine.Plot
import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine.Sig
import com.chin.stockanalysis.stock.database.StockDatabase
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 股票栏目 —「🧪 指标回测」页
 *
 * 输入股票代号/名称 → 选择指标策略与参数 → 拉取日K（本地优先，缺失走东财/新浪补数）
 * → 单指标历史回测 → 输出：
 *   1. 收益报告卡（总收益/年化/最大回撤/胜率/盈亏比/基准/超额等）
 *   2. 蜡烛图 + 指标线 + ▲买/▼卖标注（可拖动/缩放）
 *   3. MACD/RSI/KDJ 副图
 *   4. 策略资金曲线 vs 买入持有基准
 *   5. 逐笔交易明细表
 */
class IndicatorBacktestFragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val GREEN = Color.parseColor("#43A047")
    private val ORANGE = Color.parseColor("#E65100")
    private val BLUE = Color.parseColor("#1976D2")
    private val PURPLE = Color.parseColor("#9C27B0")
    private val GRAY = Color.parseColor("#999999")

    private lateinit var codeInput: EditText
    private lateinit var runBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var batchCb: CheckBox

    private var strategy = StrategyType.MA_CROSS
    private var windowBars = 250

    /** 最近一次批量回测结果（点击汇总表行可展开单只完整报告） */
    private var batchResults: List<Triple<String, String, Result?>> = emptyList()
    /** 最近一次批量汇总的成功/失败数（用于从单只报告返回时重建汇总表） */
    private var lastBatchOk = 0
    private var lastBatchFail = 0

    private val strategyChips = HashMap<StrategyType, TextView>()
    private val windowChips = HashMap<Int, TextView>()
    private val paramInputs = ArrayList<EditText>()
    /** MULTI 策略下 6 个技术信号勾选框（key = TechSignalEngine.Sig.key） */
    private val multiSigChecks = LinkedHashMap<String, CheckBox>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }

        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
        }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))

        // ── 标题 ──
        box.addView(sectionTitle("🧪 单指标历史回测"))
        box.addView(hint("输入股票代号（600519 / sh600519）或名称，选择指标策略，输出买卖点回测与收益报告。信号次日开盘成交，佣金万2.5、卖出印花税万5。"))

        // ── 代码输入 ──
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        codeInput = EditText(ctx).apply {
            hint = "股票代号 / 名称，如 600519 或 贵州茅台"
            textSize = 13f
            isSingleLine = true
            setTextColor(Color.parseColor("#333333"))
            setHintTextColor(Color.parseColor("#AAAAAA"))
            background = roundedBg("#F5F5F5", "#DDDDDD", 6)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        inputRow.addView(codeInput)
        runBtn = Button(ctx).apply {
            text = "开始回测"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(ORANGE)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
            setOnClickListener { onRun() }
        }
        inputRow.addView(runBtn)
        box.addView(inputRow)

        // ── 批量回测已保存筛选池 ──
        batchCb = CheckBox(ctx).apply {
            text = "📦 批量回测已保存的筛选股票池（${SavedScanPool.count(ctx)} 只）"
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
            setPadding(dp(2), dp(4), dp(2), dp(2))
            setOnCheckedChangeListener { _, checked ->
                codeInput.isEnabled = !checked
                codeInput.alpha = if (checked) 0.5f else 1f
                runBtn.text = if (checked) "批量回测" else "开始回测"
            }
        }
        box.addView(batchCb)

        // ── 区间选择 ──
        box.addView(label("回测区间"))
        run {
            val scroll = chipScroll(listOf(
                120 to "近6个月", 250 to "近1年", 500 to "近2年", 100000 to "全部"
            )) { key ->
                windowBars = key
                windowChips.forEach { (k, tv) -> selectChip(tv, k == key) }
            }
            box.addView(scroll)
            val row = scroll.getChildAt(0) as LinearLayout
            listOf(120, 250, 500, 100000).forEachIndexed { i, k ->
                windowChips[k] = row.getChildAt(i) as TextView
            }
            selectChip(windowChips[250]!!, true)
        }

        // ── 策略选择 ──
        box.addView(label("指标策略"))
        run {
            val scroll = chipScroll(
                StrategyType.values().map { it.ordinal to it.label }
            ) { key ->
                strategy = StrategyType.values()[key]
                strategyChips.forEach { (t, tv) -> selectChip(tv, t == strategy) }
                rebuildParams(box)
            }
            box.addView(scroll)
            val row = scroll.getChildAt(0) as LinearLayout
            StrategyType.values().forEachIndexed { i, t ->
                strategyChips[t] = row.getChildAt(i) as TextView
                selectChip(strategyChips[t]!!, t == strategy)
            }
        }

        // ── 参数区（动态） ──
        box.addView(label("策略参数（可改）").apply { tag = "PARAM_LABEL" })
        val paramRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            id = View.generateViewId()
            tag = "PARAM_ROW"
        }
        box.addView(paramRow)
        rebuildParams(box)

        // ── 状态 ──
        statusTv = TextView(ctx).apply {
            textSize = 11f
            setTextColor(GRAY)
            setPadding(dp(2), dp(6), dp(2), dp(2))
        }
        box.addView(statusTv)

        // ── 结果容器 ──
        resultBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        box.addView(resultBox)

        box.addView(hint("⚠️ 回测结果基于历史数据与固定指标规则，不代表未来表现，不构成投资建议。"))

        return root
    }

    // ═══════════════════════ 参数区 ═══════════════════════

    private fun paramDefs(type: StrategyType): List<Triple<String, String, Int>> = when (type) {
        StrategyType.MA_CROSS -> listOf(
            Triple("短周期", "5", 0), Triple("长周期", "20", 0)
        )
        StrategyType.MACD -> listOf(
            Triple("快线", "12", 0), Triple("慢线", "26", 0), Triple("信号", "9", 0)
        )
        StrategyType.RSI -> listOf(
            Triple("周期", "14", 0), Triple("超卖", "30", 1), Triple("超买", "70", 1)
        )
        StrategyType.KDJ -> listOf(Triple("周期", "9", 0))
        StrategyType.BOLL -> listOf(
            Triple("周期", "20", 0), Triple("倍数", "2", 2)
        )
        StrategyType.WR -> listOf(
            Triple("周期", "14", 0), Triple("超买", "20", 1), Triple("超卖", "80", 1)
        )
        StrategyType.MULTI -> emptyList()
    }
    // 类型：0=Int，1=Int(阈值但按整数读)，2=Double

    private fun rebuildParams(box: LinearLayout) {
        val ctx = requireContext()
        val row = box.findViewWithTag<LinearLayout>("PARAM_ROW") ?: return
        box.findViewWithTag<TextView>("PARAM_LABEL")?.text =
            if (strategy == StrategyType.MULTI) "勾选参与回测的技术信号（任一买入→买，任一卖出→卖）"
            else "策略参数（可改）"
        row.removeAllViews()
        paramInputs.clear()
        multiSigChecks.clear()

        // MULTI：横向排列 6 个信号勾选框
        if (strategy == StrategyType.MULTI) {
            val hsv = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val sigRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val sigLabels = linkedMapOf(
                "macd" to "MACD(6,13,9)", "wr" to "WR1/WR2", "rsi" to "RSI6",
                "obv" to "OBV/MAOBV", "skdj" to "SKDJ(9,3)", "bias" to "BIAS-QL"
            )
            sigLabels.forEach { (key, txt) ->
                val cb = CheckBox(ctx).apply {
                    text = txt
                    textSize = 11f
                    isChecked = true
                    setTextColor(Color.parseColor("#444444"))
                    setPadding(dp(2), 0, dp(6), 0)
                }
                multiSigChecks[key] = cb
                sigRow.addView(cb)
            }
            hsv.addView(sigRow)
            row.addView(hsv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            return
        }

        for ((caption, default, kind) in paramDefs(strategy)) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(8) }
            }
            cell.addView(TextView(ctx).apply {
                text = caption
                textSize = 10f
                setTextColor(GRAY)
            })
            val et = EditText(ctx).apply {
                setText(default)
                textSize = 13f
                isSingleLine = true
                setTextColor(Color.parseColor("#333333"))
                background = roundedBg("#FFFFFF", "#CCCCCC", 6)
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(36)
                )
                tag = kind
            }
            paramInputs.add(et)
            cell.addView(et)
            row.addView(cell)
        }
    }

    // ═══════════════════════ 执行回测 ═══════════════════════

    private fun onRun() {
        if (batchCb.isChecked) { onRunBatch(); return }
        val raw = codeInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            status("请输入股票代号或名称"); return
        }
        val params = readParams() ?: return
        params.isValid(strategy)?.let { status("⚠️ $it"); return }

        runBtn.isEnabled = false
        resultBox.removeAllViews()
        lifecycleScope.launch {
            try {
                status("⏳ 正在解析股票并检查本地日K…")
                val (code, name) = withContext(Dispatchers.IO) { resolveCode(raw) }
                if (code == null) {
                    status("⚠️ 未找到「$raw」对应的股票，请输入 6 位代码或准确名称"); return@launch
                }

                val bars = withContext(Dispatchers.IO) { loadBars(code) }
                if (bars == null) {
                    status("⚠️ $name($code) 日K数据获取失败，请稍后重试"); return@launch
                }

                val window = bars.takeLast(windowBars)
                if (window.size < 40) {
                    status("⚠️ $name($code) 有效K线仅 ${window.size} 根（需≥40），请换大区间"); return@launch
                }

                status("⏳ 正在回测 $name($code) · ${strategy.label} · ${window.size} 根K线…")
                val result = withContext(Dispatchers.Default) {
                    runCatching { IndicatorBacktestEngine.run(strategy, window, params) }
                }
                result.onSuccess { r ->
                    renderResult(name ?: code, code, r)
                    status("✅ 回测完成：${name ?: code}($code)，区间 ${r.stats.startDate} ~ ${r.stats.endDate}")
                }.onFailure { e ->
                    status("⚠️ 回测失败：${e.message}")
                }
            } finally {
                runBtn.isEnabled = true
            }
        }
    }

    // ═══════════════════════ 批量回测（已保存筛选池） ═══════════════════════

    /** 结果区外层 ScrollView 滚动回顶部（切换汇总/单只报告时定位） */
    private fun scrollResultToTop() {
        var v: View? = resultBox.parent as? View
        while (v != null && v !is ScrollView) v = v.parent as? View
        (v as? ScrollView)?.post { v.fullScroll(View.FOCUS_UP) }
    }

    private fun onRunBatch() {
        val pool = SavedScanPool.load(requireContext())
        if (pool.isEmpty()) {
            status("⚠️ 筛选池为空：请先到「K线趋势 → 扫描股票池」扫描并保存"); return
        }
        val params = readParams() ?: return
        params.isValid(strategy)?.let { status("⚠️ $it"); return }

        runBtn.isEnabled = false
        resultBox.removeAllViews()
        lifecycleScope.launch {
            val rows = ArrayList<Triple<String, String, Result?>>()
            var okN = 0; var failN = 0
            try {
                pool.forEachIndexed { i, item ->
                    status("⏳ 批量回测 ${i + 1}/${pool.size}：${item.name.ifBlank { item.code }}")
                    val bars = withContext(Dispatchers.IO) { loadBars(item.code) }
                    if (bars == null) { failN++; rows.add(Triple(item.name, item.code, null)); return@forEachIndexed }
                    val window = bars.takeLast(windowBars)
                    if (window.size < 40) { failN++; rows.add(Triple(item.name, item.code, null)); return@forEachIndexed }
                    val r = withContext(Dispatchers.Default) {
                        runCatching { IndicatorBacktestEngine.run(strategy, window, params) }
                    }.getOrNull()
                    if (r == null) failN++ else okN++
                    rows.add(Triple(item.name.ifBlank { item.code }, item.code, r))
                }
                batchResults = rows
                lastBatchOk = okN; lastBatchFail = failN
                renderBatchSummary(okN, failN)
                status("✅ 批量回测完成：成功 $okN / 失败 $failN（点击行可看该股票完整图表与买卖点）")
            } finally {
                runBtn.isEnabled = true
            }
        }
    }

    /** 批量结果汇总表（按总收益率降序；点击行展开单只完整回测报告） */
    private fun renderBatchSummary(okN: Int, failN: Int) {
        val ctx = requireContext()
        resultBox.removeAllViews()
        val valid = batchResults.filter { it.third != null }
            .sortedByDescending { it.third!!.stats.totalReturnPct }
        val avg = if (valid.isNotEmpty()) valid.map { it.third!!.stats.totalReturnPct }.average() else 0.0
        val win = valid.count { it.third!!.stats.totalReturnPct > 0 }

        resultBox.addView(card {
            it.addView(cardTitle("📊 批量回测汇总 · ${strategy.label}"))
            it.addView(TextView(ctx).apply {
                text = "共 ${batchResults.size} 只 · 成功 $okN · 失败 $failN · " +
                    "盈利 $win 只 · 平均收益 ${pct(avg)}（点击下方任意行查看完整图表/买卖点）"
                textSize = 11f
                setTextColor(Color.parseColor("#444444"))
                setPadding(0, 0, 0, dp(6))
            })
            val scroll = HorizontalScrollView(ctx)
            val table = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val headers = listOf("#", "名称/代码", "总收益", "年化", "最大回撤", "胜率", "交易", "基准", "超额")
            val widths = listOf(28, 120, 72, 70, 70, 64, 50, 72, 70)
            table.addView(batchRow(headers, widths, header = true))
            valid.forEachIndexed { i, (name, code, r) ->
                val s = r!!.stats
                val cells = listOf(
                    "${i + 1}",
                    "${name.ifBlank { code }}",
                    pct(s.totalReturnPct),
                    pct(s.annualizedPct),
                    pct(s.maxDrawdownPct),
                    "%.0f%%".format(s.winRatePct),
                    "${s.tradeCount}",
                    pct(s.benchmarkReturnPct),
                    pct(s.excessReturnPct)
                )
                val row = batchRow(cells, widths, header = false)
                (row.getChildAt(2) as TextView).apply {
                    setTextColor(if (s.totalReturnPct > 0) RED else GREEN)
                    setTypeface(typeface, Typeface.BOLD)
                }
                row.setOnClickListener {
                    renderResult(name.ifBlank { code }, code, r) {
                        renderBatchSummary(lastBatchOk, lastBatchFail)
                        scrollResultToTop()
                    }
                    scrollResultToTop()
                }
                table.addView(row)
            }
            scroll.addView(table)
            it.addView(scroll)
        })
    }

    private fun batchRow(cells: List<String>, widthsDp: List<Int>, header: Boolean): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (header) Color.parseColor("#F0F0F0") else Color.WHITE)
            isClickable = !header
            if (!header) {
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply { setColor(Color.parseColor("#FFF3E0")) })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(Color.WHITE) })
                }
            }
            cells.forEachIndexed { i, txt ->
                addView(TextView(ctx).apply {
                    text = txt
                    textSize = 10f
                    gravity = Gravity.CENTER_VERTICAL or (if (i == 1) Gravity.START else Gravity.CENTER_HORIZONTAL)
                    setTextColor(if (header) Color.parseColor("#666666") else Color.parseColor("#333333"))
                    if (header) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(5), dp(6), dp(5), dp(6))
                    layoutParams = LinearLayout.LayoutParams(dp(widthsDp[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::batchCb.isInitialized) {
            batchCb.text = "📦 批量回测已保存的筛选股票池（${SavedScanPool.count(requireContext())} 只）"
        }
    }

    private fun readParams(): BtParams? {
        val p = paramDefs(strategy)
        fun intAt(i: Int, name: String): Int? {
            val t = paramInputs[i].text?.toString()?.trim()?.toIntOrNull()
            if (t == null) status("⚠️ 参数「$name」需为整数")
            return t
        }
        fun dblAt(i: Int, name: String): Double? {
            val t = paramInputs[i].text?.toString()?.trim()?.toDoubleOrNull()
            if (t == null) status("⚠️ 参数「$name」需为数字")
            return t
        }
        return try {
            when (strategy) {
                StrategyType.MULTI -> {
                    val keys = multiSigChecks.filterValues { it.isChecked }.keys
                    if (keys.isEmpty()) { status("⚠️ 请至少勾选一个技术信号"); return null }
                    BtParams(multiSignals = keys)
                }
                StrategyType.MA_CROSS -> {
                    val s = intAt(0, "短周期") ?: return null
                    val l = intAt(1, "长周期") ?: return null
                    BtParams(maShort = s, maLong = l)
                }
                StrategyType.MACD -> {
                    val f = intAt(0, "快线") ?: return null
                    val s = intAt(1, "慢线") ?: return null
                    val g = intAt(2, "信号") ?: return null
                    BtParams(macdFast = f, macdSlow = s, macdSignal = g)
                }
                StrategyType.RSI -> {
                    val n = intAt(0, "周期") ?: return null
                    val lo = intAt(1, "超卖")?.toDouble() ?: return null
                    val hi = intAt(2, "超买")?.toDouble() ?: return null
                    BtParams(rsiPeriod = n, rsiOversold = lo, rsiOverbought = hi)
                }
                StrategyType.KDJ -> {
                    val n = intAt(0, "周期") ?: return null
                    BtParams(kdjN = n)
                }
                StrategyType.BOLL -> {
                    val n = intAt(0, "周期") ?: return null
                    val k = dblAt(1, "倍数") ?: return null
                    BtParams(bollN = n, bollK = k)
                }
                StrategyType.WR -> {
                    val n = intAt(0, "周期") ?: return null
                    val ob = intAt(1, "超买")?.toDouble() ?: return null
                    val os = intAt(2, "超卖")?.toDouble() ?: return null
                    BtParams(wrPeriod = n, wrOverbought = ob, wrOversold = os)
                }
            }
        } catch (_: Exception) { null }
    }

    /** 名称/代码 → 规范化 code（sh600519） */
    private suspend fun resolveCode(raw: String): Pair<String?, String?> {
        val digits = raw.filter { it.isDigit() }
        val normalized = StockAnalysisAgent.normalizeStockCode(raw)
        if (normalized.startsWith("sh") || normalized.startsWith("sz") || normalized.startsWith("bj")) {
            val db = StockDatabase.getInstance(requireContext())
            val name = runCatching { db.stockBasicDao().getByCode(normalized)?.name }.getOrNull()
            return normalized to (name?.ifBlank { normalized })
        }
        // 按名称在本地股票库查
        val db = StockDatabase.getInstance(requireContext())
        val hits = runCatching { db.stockBasicDao().searchByName(raw) }.getOrDefault(emptyList())
        val pick = hits.firstOrNull { it.name == raw }
            ?: hits.firstOrNull { it.name.contains(raw) || raw.contains(it.name) }
            ?: hits.firstOrNull()
        return pick?.code to pick?.name
    }

    /** 本地日K优先；不足或过期时走网络补数（约2年），返回升序 bar 列表 */
    private suspend fun loadBars(code: String): List<Bar>? {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        fun toBars(list: List<DailySnapshotEntity>) = list
            .filter { it.close > 0 && it.high > 0 && it.low > 0 }
            .sortedBy { it.date }
            .map { Bar(it.date, it.open.ifZero(it.close), it.high, it.low, it.close, it.volume) }

        var local = runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
        if (local.size < 400) {
            val ok = HistoricalDataFetcher(ctx).fetchStockLatest(code, 850)
            if (ok) {
                local = runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
            }
        }
        val bars = toBars(local)
        return if (bars.size < 40) null else bars
    }

    private fun Double.ifZero(fallback: Double) = if (this == 0.0) fallback else this

    // ═══════════════════════ 结果渲染 ═══════════════════════

    private fun renderResult(name: String, code: String, r: Result, onBack: (() -> Unit)? = null) {
        val ctx = requireContext()
        resultBox.removeAllViews()

        // 批量回测下进入单只报告：顶部提供返回汇总表按钮
        if (onBack != null) {
            resultBox.addView(TextView(ctx).apply {
                text = "← 返回批量汇总"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setBackgroundColor(BLUE)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    onBack()
                    status("✅ 已返回批量汇总，可继续点击其他股票查看报告")
                }
            })
        }

        // ── ① 次日操作建议 ──
        resultBox.addView(card {
            it.addView(cardTitle("🧭 次日操作建议（截至 ${r.latestDate} 收盘 ${fmt2(r.latestClose)}）"))
            val (word, color, desc) = when (r.latestAdvice) {
                IndicatorBacktestEngine.BUY -> Triple("买 入", RED,
                    "${r.type.label} 出现买入信号 → 次日开盘买入。理由：${r.latestAdviceWhy ?: ""}")
                IndicatorBacktestEngine.SELL -> Triple("卖 出", GREEN,
                    "${r.type.label} 出现卖出信号 → 次日开盘卖出。理由：${r.latestAdviceWhy ?: ""}")
                IndicatorBacktestEngine.HOLD_POS -> Triple("持 有", BLUE,
                    "当前持仓中，指标未触发卖出条件，继续持有。")
                else -> Triple("观 望", GRAY, "当前空仓，指标未触发买入条件，观望等待。")
            }
            it.addView(TextView(ctx).apply {
                text = word; textSize = 30f; setTextColor(color)
                setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(4))
            })
            it.addView(TextView(ctx).apply {
                text = "$name($code)　$desc"; textSize = 11.5f; setTextColor(Color.parseColor("#333333"))
                gravity = Gravity.CENTER; setPadding(dp(6), dp(2), dp(6), dp(8))
            })
        })

        // ── ② 票性识别（复用 SSA 混合策略 L30+EMA2 固定口径，与单票分析同结论）──
        val profBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        resultBox.addView(card {
            it.addView(cardTitle("🏷️ 票性识别（L30+EMA2 固定口径 · 滞后5日无未来函数）"))
            profBox.addView(TextView(ctx).apply {
                text = "⏳ 票性识别计算中…"; textSize = 11f; setTextColor(GRAY)
                setPadding(dp(2), dp(8), dp(2), dp(8))
            })
            it.addView(profBox)
        })
        if (r.bars.size >= 60) {
            lifecycleScope.launch {
                val pf = withContext(Dispatchers.Default) {
                    runCatching {
                        Ssa.classifyOnly(r.bars.map { b -> Ssa.Bar(b.date, b.open, b.high, b.low, b.close) })
                    }.getOrNull()
                }
                if (!isAdded) return@launch
                profBox.removeAllViews()
                if (pf == null) {
                    profBox.addView(TextView(ctx).apply {
                        text = "⚠️ 票性识别计算失败"; textSize = 11f; setTextColor(GRAY)
                        setPadding(dp(2), dp(8), dp(2), dp(8))
                    })
                    return@launch
                }
                val (word, color, tip) = when (pf.label) {
                    "TREND" -> Triple("趋势票", RED,
                        "顺势类指标（双均线/MACD）在主升段更占优，摆动类超买信号容易卖飞；" +
                            "买入信号可较高信任，不要逆势频繁操作。")
                    "MIXED" -> Triple("震荡混合票", ORANGE,
                        "信号反复偏多，建议结合指标位置轻仓试错、严格止损；" +
                            "卖出信号的避跌价值通常高于买入信号。")
                    "OSCILLATION" -> Triple("窄幅震荡票", GRAY,
                        "均线/趋势类指标易反复金叉死叉磨损，RSI/KDJ/WR 摆动类指标更适配，宜降低操作频率。")
                    else -> Triple("慢牛票", BLUE,
                        "买入持有往往优于频繁波段；如做波段建议轻仓、低频，避免卖飞。")
                }
                profBox.addView(TextView(ctx).apply {
                    text = word; textSize = 22f; setTextColor(color)
                    setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(4))
                })
                profBox.addView(statGrid(listOf(
                    "高噪后3日均" to pct(pf.hi3),
                    "高噪后5日均" to pct(pf.hi5),
                    "超跌后5日均" to pct(pf.lo5),
                    "周期振幅比" to "%.1f%%".format(pf.cycAmp * 100)
                )))
                profBox.addView(TextView(ctx).apply {
                    text = "口径：hi5>0.6 且振幅>4% → 趋势；hi5<-0.2 且振幅<3% → 震荡；" +
                        "-0.2≤hi5<0.4 且振幅<2.5% → 慢牛；其余混合。" +
                        "高噪样本 ${pf.hiCount} 个 / 超跌样本 ${pf.loCount} 个。$tip"
                    textSize = 10.5f; setTextColor(GRAY); setPadding(dp(4), dp(6), dp(4), dp(2))
                })
            }
        } else {
            profBox.removeAllViews()
            profBox.addView(TextView(ctx).apply {
                text = "K线不足 60 根，票性识别需 ≥60 根历史数据，请换更大区间。"
                textSize = 11f; setTextColor(GRAY); setPadding(dp(2), dp(8), dp(2), dp(8))
            })
        }

        // ── ③ 建议滚动命中率 ──
        resultBox.addView(card {
            it.addView(cardTitle("🎯 建议滚动命中率（次日开盘成交）"))
            val widths = listOf(64, 60, 72, 72, 92)
            val table = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            table.addView(tradeRow(listOf("建议", "次数", "次日对", "3日对", "5日均收益"), widths, header = true))
            fun addHitRow(label: String, s: IndicatorBacktestEngine.AdviceStat) {
                val row = tradeRow(
                    listOf(label, "${s.count}", "%.0f%%".format(s.win1Pct),
                        "%.0f%%".format(s.win3Pct), pct(s.avgRet5Pct)),
                    widths, header = false
                )
                (row.getChildAt(4) as TextView).setTextColor(
                    if (s.avgRet5Pct > 0) RED else if (s.avgRet5Pct < 0) GREEN else Color.parseColor("#333333")
                )
                table.addView(row)
            }
            addHitRow("买入", r.buyStat)
            addHitRow("卖出", r.sellStat)
            addHitRow("持有", r.holdPosStat)
            addHitRow("观望", r.holdEmptyStat)
            it.addView(table)
        })

        // ── ④ 报告卡（回测绩效） ──
        resultBox.addView(card {
            it.addView(TextView(ctx).apply {
                text = "$name($code)"
                textSize = 15f
                setTextColor(Color.parseColor("#222222"))
                setTypeface(typeface, Typeface.BOLD)
            })
            it.addView(TextView(ctx).apply {
                text = "${r.type.label} · ${r.stats.startDate} ~ ${r.stats.endDate} · ${r.stats.barCount} 根日K"
                textSize = 11f
                setTextColor(GRAY)
                setPadding(0, dp(2), 0, dp(8))
            })
            val s = r.stats
            val cells = listOf(
                "总收益率" to pct(s.totalReturnPct),
                "年化收益" to pct(s.annualizedPct),
                "最大回撤" to pct(s.maxDrawdownPct),
                "胜率" to "${"%.1f".format(s.winRatePct)}% (${s.winCount}/${s.tradeCount})",
                "交易次数" to "${s.tradeCount} 笔",
                "盈亏比" to if (s.profitFactor.isInfinite()) "∞" else "%.2f".format(s.profitFactor),
                "平均盈利" to pct(s.avgWinPct),
                "平均亏损" to pct(s.avgLossPct),
                "平均持仓" to "${s.avgHoldBars} 根K线",
                "基准(买持)" to pct(s.benchmarkReturnPct),
                "超额收益" to pct(s.excessReturnPct),
                "期末权益" to "¥${"%,.0f".format(IndicatorBacktestEngine.INITIAL_CAPITAL * (1 + s.totalReturnPct / 100))}"
            )
            it.addView(statGrid(cells))
        })

        // ── ⑤ 图表（主图/全部副图/资金曲线：捏合缩放视口同步 + 十字光标竖线跨图联动） ──
        val linked = ArrayList<BarLineChartBase<*>>()

        resultBox.addView(card {
            it.addView(cardTitle("📈 K线 · 买卖点（▲买入 ▼卖出；主副图双指同步缩放，拖动出十字光标）"))
            val c = buildCandleChart(r, 320)
            linked.add(c)
            it.addView(c)
        })

        if (!r.subTitle.isNullOrBlank()) {
            resultBox.addView(card {
                it.addView(cardTitle("📊 ${r.subTitle}"))
                val c: BarLineChartBase<*> = when (r.type) {
                    StrategyType.MACD -> buildMacdChart(r, 130)
                    else -> buildOscillatorChart(r, 130)
                }
                linked.add(c)
                it.addView(c)
            })
        }

        r.multiPlots?.forEach { (sig, plot) ->
            val enabled = r.params.multiSignals.contains(sig.key)
            resultBox.addView(card {
                it.addView(cardTitle((if (enabled) "📊 " else "⚪ 未启用 · ") + sig.label))
                val v = buildMultiPlotChart(r.bars, plot, sig == Sig.MACD, 140)
                if (v is BarLineChartBase<*>) linked.add(v)
                it.addView(v)
            })
        }

        resultBox.addView(card {
            it.addView(cardTitle("💰 资金曲线：策略 vs 买入持有"))
            val c = buildEquityChart(r, 160)
            linked.add(c)
            it.addView(c)
        })

        linkCharts(linked)

        // ── ⑥ 交易明细（含每笔买/卖的指标理由，供复盘） ──
        resultBox.addView(card {
            it.addView(cardTitle("📋 逐笔交易明细（${r.trades.size} 笔 · 含指标理由）"))
            if (r.trades.isEmpty()) {
                it.addView(TextView(ctx).apply {
                    text = "该区间内策略未产生完整交易（无卖出信号）。可换更大区间或其它指标。"
                    textSize = 11f
                    setTextColor(GRAY)
                    setPadding(0, dp(4), 0, dp(4))
                })
            } else {
                it.addView(buildTradeTable(r))
            }
        })

        // ── ⑦ 近30日建议验证（建议 vs 后续实际涨跌） ──
        val rec = r.recentAdvices.filter { it.r1 != null }
        if (rec.isNotEmpty()) {
            val buyH = rec.filter { it.advice == IndicatorBacktestEngine.BUY && it.r3 != null }
            val sellH = rec.filter { it.advice == IndicatorBacktestEngine.SELL && it.r3 != null }
            resultBox.addView(card {
                it.addView(cardTitle("📜 近30日建议验证（t 收盘 → t+k 实际涨跌）"))
                val widths = listOf(82, 52, 60, 64, 64, 64, 52)
                val table = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                table.addView(tradeRow(listOf("日期", "建议", "收盘", "次日%", "3日%", "5日%", "判定"), widths, header = true))
                rec.reversed().forEach { a ->
                    val (w, wc) = advWord(a.advice)
                    val r3 = a.r3
                    val judge = when {
                        r3 == null -> "…"
                        a.advice == IndicatorBacktestEngine.BUY -> if (r3 > 0) "✅对" else "❌错"
                        a.advice == IndicatorBacktestEngine.SELL -> if (r3 < 0) "✅对" else "❌错"
                        a.advice == IndicatorBacktestEngine.HOLD_POS -> if (r3 > 0) "✅对" else "❌错"
                        else -> "—"
                    }
                    val row = tradeRow(listOf(
                        a.date, w, fmt2(a.close),
                        a.r1?.let { "%+.2f".format(it) } ?: "-",
                        a.r3?.let { "%+.2f".format(it) } ?: "-",
                        a.r5?.let { "%+.2f".format(it) } ?: "-", judge
                    ), widths, header = false)
                    (row.getChildAt(1) as TextView).setTextColor(wc)
                    listOf(3, 4, 5).forEach { ci ->
                        val v = when (ci) { 3 -> a.r1; 4 -> a.r3; else -> a.r5 }
                        if (v != null) (row.getChildAt(ci) as TextView).setTextColor(
                            if (v > 0) RED else if (v < 0) GREEN else Color.parseColor("#333333")
                        )
                    }
                    table.addView(row)
                }
                it.addView(table)
                it.addView(TextView(ctx).apply {
                    text = "3日口径：买入命中 ${buyH.count { (it.r3 ?: 0.0) > 0 }}/${buyH.size}" +
                        " · 卖出命中 ${sellH.count { (it.r3 ?: 0.0) < 0 }}/${sellH.size}" +
                        "（确定性算法+锁定参数，历史建议与当日信号一致）"
                    textSize = 10f; setTextColor(GRAY); setPadding(dp(4), dp(6), dp(4), 0)
                })
            })
        }
    }

    private fun advWord(a: Int): Pair<String, Int> = when (a) {
        IndicatorBacktestEngine.BUY -> "买入" to RED
        IndicatorBacktestEngine.SELL -> "卖出" to GREEN
        IndicatorBacktestEngine.HOLD_POS -> "持有" to BLUE
        else -> "观望" to GRAY
    }

    private fun fmt2(v: Double) = "%.2f".format(v)

    private fun pct(v: Double): String {
        val sign = if (v > 0) "+" else ""
        return "${sign}%.2f%%".format(v)
    }

    private fun statGrid(cells: List<Pair<String, String>>): View {
        val ctx = requireContext()
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cells.chunked(3).forEach { rowCells ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowCells.forEach { (k, v) ->
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    background = roundedBg("#FAFAFA", "#EEEEEE", 6)
                }
                val upTone = v.startsWith("+")
                val downTone = v.startsWith("-")
                cell.addView(TextView(ctx).apply {
                    text = k; textSize = 9f; setTextColor(GRAY)
                })
                cell.addView(TextView(ctx).apply {
                    text = v; textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(when { upTone -> RED; downTone -> GREEN; else -> Color.parseColor("#333333") })
                })
                row.addView(cell)
                (cell.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6)
            }
            // 补齐最后一行空位，保持列宽
            repeat(3 - rowCells.size) {
                val ph = View(ctx)
                ph.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                row.addView(ph)
            }
            grid.addView(row)
            (row.layoutParams as? LinearLayout.LayoutParams)?.let { it.bottomMargin = dp(6) }
        }
        return grid
    }

    // ─────────── 主图 ───────────

    private val linePalette = intArrayOf(ORANGE, BLUE, PURPLE, GRAY, RED, GREEN)

    private fun buildCandleChart(r: Result, heightDp: Int): CombinedChart {
        val ctx = requireContext()
        val bars = r.bars
        val chart = CombinedChart(ctx)
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
        )
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.textSize = 9f
        chart.legend.textColor = GRAY
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setAutoScaleMinMaxEnabled(true)
        chart.setDragEnabled(true)
        chart.setDoubleTapToZoomEnabled(true)
        chart.setHighlightPerTapEnabled(true)
        chart.setHighlightPerDragEnabled(true)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat())
        chart.setVisibleXRangeMinimum(40f)
        chart.moveViewToX((bars.size - 1).toFloat())
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.CANDLE, CombinedChart.DrawOrder.LINE)

        val candleEntries = ArrayList<CandleEntry>(bars.size)
        for (i in bars.indices) {
            val b = bars[i]
            candleEntries.add(CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat()))
        }
        val cds = CandleDataSet(candleEntries, "").apply {
            shadowColor = Color.parseColor("#999999")
            shadowWidth = 1f
            increasingPaintStyle = Paint.Style.FILL
            decreasingPaintStyle = Paint.Style.FILL
            increasingColor = RED
            decreasingColor = GREEN
            // 十字光标：只画跨图竖线（粗虚线），横线关闭
            isHighlightEnabled = true
            setDrawHorizontalHighlightIndicator(false)
            setHighlightLineWidth(1.6f)
            enableDashedHighlightLine(8f, 4f, 0f)
            highLightColor = Color.parseColor("#888888")
            setDrawValues(false)
        }

        val lineData = LineData()
        for ((li, line) in r.mainLines.withIndex()) {
            val entries = ArrayList<Entry>()
            for (i in line.values.indices) {
                val v = line.values[i]
                if (!v.isNaN()) entries.add(Entry(i.toFloat(), v.toFloat()))
            }
            if (entries.isEmpty()) continue
            lineData.addDataSet(LineDataSet(entries, line.name).apply {
                color = linePalette[line.colorHint % linePalette.size]
                lineWidth = 1.1f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }

        // 买卖点：用 LineDataSet 的点标签画 ▲/▼
        val buys = r.markers.filter { it.buy }.map {
            Entry(it.index.toFloat(), (r.bars[it.index].low * 0.985).toFloat())
        }
        val sells = r.markers.filter { !it.buy }.map {
            Entry(it.index.toFloat(), (r.bars[it.index].high * 1.015).toFloat())
        }
        lineData.addDataSet(arrowSet(buys, "买入", true, RED))
        lineData.addDataSet(arrowSet(sells, "卖出", false, GREEN))

        val combined = CombinedData()
        combined.setData(CandleData(cds))
        combined.setData(lineData)
        chart.data = combined
        styleCommonAxes(chart, bars)
        chart.post { chart.invalidate() }
        return chart
    }

    private fun arrowSet(entries: List<Entry>, label: String, up: Boolean, color: Int): LineDataSet {
        return LineDataSet(entries, label).apply {
            this.color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(true)
            valueTextSize = 12f
            valueTextColor = color
            isHighlightEnabled = false
            setDrawHorizontalHighlightIndicator(false)
            valueFormatter = object : ValueFormatter() {
                override fun getPointLabel(entry: Entry?): String = if (up) "▲" else "▼"
            }
        }
    }

    // ─────────── MACD 副图 ───────────

    private fun buildMacdChart(r: Result, heightDp: Int): CombinedChart {
        val ctx = requireContext()
        val bars = r.bars
        val chart = CombinedChart(ctx)
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
        )
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.textSize = 8f
        chart.legend.textColor = GRAY
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setAutoScaleMinMaxEnabled(true)
        chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(true)
        chart.setHighlightPerDragEnabled(true)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat())
        chart.setVisibleXRangeMinimum(40f)
        chart.moveViewToX((bars.size - 1).toFloat())
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.LINE)

        val hist = r.subHist
        val barEntries = ArrayList<BarEntry>()
        val colors = ArrayList<Int>()
        if (hist != null) {
            for (i in hist.indices) {
                if (!hist[i].isNaN()) {
                    barEntries.add(BarEntry(i.toFloat(), hist[i].toFloat()))
                    colors.add(if (hist[i] >= 0) RED else GREEN)
                }
            }
        }
        val barDs = BarDataSet(barEntries, "MACD柱").apply {
            setColors(colors)
            setDrawValues(false)
            isHighlightEnabled = true
            highLightColor = Color.parseColor("#888888")
            barBorderWidth = 0f
        }
        val barData = BarData(barDs).apply { barWidth = 0.6f }

        val lineData = LineData()
        r.subLines.forEach { line ->
            val es = ArrayList<Entry>()
            for (i in line.values.indices) {
                if (!line.values[i].isNaN()) es.add(Entry(i.toFloat(), line.values[i].toFloat()))
            }
            lineData.addDataSet(LineDataSet(es, line.name).apply {
                color = linePalette[line.colorHint % linePalette.size]
                lineWidth = 1f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }
        // 0 轴
        chart.axisLeft.addLimitLine(LimitLine(0f, "").apply {
            lineColor = Color.parseColor("#CCCCCC"); lineWidth = 1f
        })

        val combined = CombinedData()
        combined.setData(barData)
        combined.setData(lineData)
        chart.data = combined
        styleCommonAxes(chart, bars, priceFormat = false)
        chart.post { chart.invalidate() }
        return chart
    }

    // ─────────── RSI/KDJ 副图 ───────────

    private fun buildOscillatorChart(r: Result, heightDp: Int): LineChart {
        val ctx = requireContext()
        val bars = r.bars
        val chart = LineChart(ctx)
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
        )
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.textSize = 8f
        chart.legend.textColor = GRAY
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setAutoScaleMinMaxEnabled(true)
        chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(true)
        chart.setHighlightPerDragEnabled(true)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat())
        chart.setVisibleXRangeMinimum(40f)
        chart.moveViewToX((bars.size - 1).toFloat())

        val data = LineData()
        r.subLines.forEach { line ->
            val es = ArrayList<Entry>()
            for (i in line.values.indices) {
                if (!line.values[i].isNaN()) es.add(Entry(i.toFloat(), line.values[i].toFloat()))
            }
            data.addDataSet(LineDataSet(es, line.name).apply {
                color = linePalette[line.colorHint % linePalette.size]
                lineWidth = 1f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }
        // 十字光标竖线挂在第一条指标线上（其余线不参与高亮）
        (data.getDataSetByIndex(0) as? LineDataSet)?.apply {
            isHighlightEnabled = true
            setDrawHorizontalHighlightIndicator(false)
            setHighlightLineWidth(1.6f)
            enableDashedHighlightLine(8f, 4f, 0f)
            highLightColor = Color.parseColor("#888888")
        }
        if (r.type == StrategyType.RSI) {
            val lo = r.params.rsiOversold.toFloat()
            val hi = r.params.rsiOverbought.toFloat()
            chart.axisLeft.addLimitLine(LimitLine(lo, "超卖${lo.toInt()}").apply {
                lineColor = GREEN; lineWidth = 1f; textSize = 8f; textColor = GRAY
            })
            chart.axisLeft.addLimitLine(LimitLine(hi, "超买${hi.toInt()}").apply {
                lineColor = RED; lineWidth = 1f; textSize = 8f; textColor = GRAY
            })
            chart.axisLeft.axisMinimum = 0f
            chart.axisLeft.axisMaximum = 100f
        }
        if (r.type == StrategyType.WR) {
            // 国内口径：WR 越大越超卖（高位 80 = 超卖，低位 20 = 超买）
            val ob = r.params.wrOverbought.toFloat()
            val os = r.params.wrOversold.toFloat()
            chart.axisLeft.addLimitLine(LimitLine(os, "超卖${os.toInt()}").apply {
                lineColor = GREEN; lineWidth = 1f; textSize = 8f; textColor = GRAY
            })
            chart.axisLeft.addLimitLine(LimitLine(ob, "超买${ob.toInt()}").apply {
                lineColor = RED; lineWidth = 1f; textSize = 8f; textColor = GRAY
            })
            chart.axisLeft.axisMinimum = 0f
            chart.axisLeft.axisMaximum = 100f
        }
        chart.data = data
        styleLineChartAxes(chart, bars)
        chart.post { chart.invalidate() }
        return chart
    }

    // ─────────── MULTI 多信号副图（柱+线/纯线，参考线/买卖箭头/WR倒置） ───────────

    private val multiLinePalette = intArrayOf(ORANGE, BLUE, PURPLE, GRAY, RED, GREEN)

    private fun buildMultiPlotChart(bars: List<Bar>, plot: Plot, combined: Boolean, heightDp: Int): View {
        val n = bars.size
        // 值范围（含柱），用于放置买卖箭头
        var yMin = Double.MAX_VALUE; var yMax = -Double.MAX_VALUE
        fun consider(v: Double) { if (!v.isNaN()) { if (v < yMin) yMin = v; if (v > yMax) yMax = v } }
        plot.lines.forEach { ln -> ln.second.forEach { consider(it) } }
        plot.bars?.forEach { consider(it) }
        if (yMin == Double.MAX_VALUE) { yMin = 0.0; yMax = 100.0 }
        val pad = (yMax - yMin).coerceAtLeast(1.0) * 0.08

        val lineData = LineData()
        plot.lines.forEachIndexed { li, (name, arr) ->
            val es = ArrayList<Entry>()
            arr.forEachIndexed { i, v -> if (!v.isNaN()) es.add(Entry(i.toFloat(), v.toFloat())) }
            if (es.isNotEmpty()) lineData.addDataSet(LineDataSet(es, name).apply {
                color = multiLinePalette[li % multiLinePalette.size]
                lineWidth = 1.2f
                setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            })
        }
        lineData.addDataSet(arrowSet(plot.buys.map { Entry(it.toFloat(), (yMin - pad).toFloat()) }, "买入", true, RED))
        lineData.addDataSet(arrowSet(plot.sells.map { Entry(it.toFloat(), (yMax + pad).toFloat()) }, "卖出", false, GREEN))

        fun styleAxis(axis: com.github.mikephil.charting.components.YAxis) {
            axis.textSize = 8f; axis.textColor = GRAY
            axis.gridColor = Color.parseColor("#EEEEEE")
            plot.refs.forEach { (v, lbl) ->
                axis.addLimitLine(LimitLine(v.toFloat(), lbl).apply {
                    lineColor = Color.parseColor("#CCCCCC"); lineWidth = 1f; textSize = 7f; textColor = GRAY
                })
            }
            if (plot.inverted) { axis.axisMinimum = 0f; axis.axisMaximum = 100f; axis.isInverted = true }
        }

        if (combined && plot.bars != null) {
            val chart = CombinedChart(requireContext())
            chart.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
            )
            chart.setBackgroundColor(Color.WHITE)
            chart.description.isEnabled = false
            chart.legend.textSize = 8f; chart.legend.textColor = GRAY
            chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
            chart.setAutoScaleMinMaxEnabled(true)
            chart.setHighlightPerTapEnabled(true); chart.setHighlightPerDragEnabled(true)
            chart.setVisibleXRangeMaximum((n + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
            chart.moveViewToX((n - 1).toFloat())
            chart.drawOrder = arrayOf(CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.LINE)
            val be = ArrayList<BarEntry>(); val cs = ArrayList<Int>()
            plot.bars.forEachIndexed { i, v ->
                if (!v.isNaN()) { be.add(BarEntry(i.toFloat(), v.toFloat())); cs.add(if (v >= 0) RED else GREEN) }
            }
            val bds = BarDataSet(be, "MACD柱").apply {
                setColors(cs); setDrawValues(false); isHighlightEnabled = true
                highLightColor = Color.parseColor("#888888")
            }
            val cd = CombinedData()
            cd.setData(BarData(bds).apply { barWidth = 0.6f })
            cd.setData(lineData)
            chart.data = cd
            styleCommonAxes(chart, bars, priceFormat = false)
            styleAxis(chart.axisLeft)
            chart.post { chart.invalidate() }
            return chart
        } else {
            val chart = LineChart(requireContext())
            chart.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
            )
            chart.setBackgroundColor(Color.WHITE)
            chart.description.isEnabled = false
            chart.legend.textSize = 8f; chart.legend.textColor = GRAY
            chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
            chart.setAutoScaleMinMaxEnabled(true)
            chart.setHighlightPerTapEnabled(true); chart.setHighlightPerDragEnabled(true)
            chart.setVisibleXRangeMaximum((n + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
            chart.moveViewToX((n - 1).toFloat())
            // 十字光标竖线挂在第一条指标线上
            (lineData.getDataSetByIndex(0) as? LineDataSet)?.apply {
                isHighlightEnabled = true
                setDrawHorizontalHighlightIndicator(false)
                setHighlightLineWidth(1.6f)
                enableDashedHighlightLine(8f, 4f, 0f)
                highLightColor = Color.parseColor("#888888")
            }
            chart.data = lineData
            styleLineChartAxes(chart, bars)
            styleAxis(chart.axisLeft)
            chart.post { chart.invalidate() }
            return chart
        }
    }

    // ─────────── 资金曲线 ───────────

    private fun buildEquityChart(r: Result, heightDp: Int): LineChart {
        val ctx = requireContext()
        val bars = r.bars
        val chart = LineChart(ctx)
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
        )
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.textSize = 9f
        chart.legend.textColor = GRAY
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setAutoScaleMinMaxEnabled(true)
        chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(true)
        chart.setHighlightPerDragEnabled(true)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat())
        chart.setVisibleXRangeMinimum(40f)
        chart.moveViewToX((bars.size - 1).toFloat())

        val strat = ArrayList<Entry>()
        val bench = ArrayList<Entry>()
        for (i in bars.indices) {
            strat.add(Entry(i.toFloat(), r.equityCurve[i].toFloat()))
            bench.add(Entry(i.toFloat(), r.benchmarkCurve[i].toFloat()))
        }
        val data = LineData()
        data.addDataSet(LineDataSet(strat, "策略权益").apply {
            color = ORANGE; lineWidth = 1.6f
            setDrawCircles(false); setDrawValues(false)
            // 十字光标竖线挂在策略权益线上
            isHighlightEnabled = true
            setDrawHorizontalHighlightIndicator(false)
            setHighlightLineWidth(1.6f)
            enableDashedHighlightLine(8f, 4f, 0f)
            highLightColor = Color.parseColor("#888888")
        })
        data.addDataSet(LineDataSet(bench, "买入持有").apply {
            color = GRAY; lineWidth = 1.2f
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            enableDashedLine(6f, 4f, 0f)
        })
        chart.data = data
        styleLineChartAxes(chart, bars, money = true)
        chart.post { chart.invalidate() }
        return chart
    }

    // ─────────── 主图/副图联动 ───────────

    /**
     * ## 主图/全部副图/资金曲线联动
     * - 双指捏合缩放 / 平移：任意图的 X 视口（scaleX/transX）同步到其余图（Y 轴各自适应不复制）
     * - 十字光标：轻点或按住拖动出现，竖线为高亮指示线；任意图高亮变化时把同一 x 索引同步到其余图，
     *   竖线视觉上同时穿过主图与全部副图（各图挂在第一个允许高亮的数据集上）
     */
    private fun linkCharts(charts: List<BarLineChartBase<*>>) {
        if (charts.size <= 1) return
        fun syncFrom(src: BarLineChartBase<*>) {
            val sv = FloatArray(9)
            src.viewPortHandler.matrixTouch.getValues(sv)
            charts.forEach { dst ->
                if (dst !== src) {
                    val dm = Matrix(dst.viewPortHandler.matrixTouch)
                    val dv = FloatArray(9)
                    dm.getValues(dv)
                    dv[Matrix.MSCALE_X] = sv[Matrix.MSCALE_X]
                    dv[Matrix.MTRANS_X] = sv[Matrix.MTRANS_X]
                    dm.setValues(dv)
                    dst.viewPortHandler.refresh(dm, dst, true)
                }
            }
        }
        var linkingHighlight = false
        charts.forEach { c ->
            c.setHighlightPerDragEnabled(true)   // 按住拖动 = 十字扫描
            c.onChartGestureListener = object : OnChartGestureListener {
                override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartGesture?) {}
                override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartGesture?) { syncFrom(c) }
                override fun onChartLongPressed(me: MotionEvent?) {}
                override fun onChartDoubleTapped(me: MotionEvent?) {}
                override fun onChartSingleTapped(me: MotionEvent?) {}
                override fun onChartFling(me: MotionEvent?, me2: MotionEvent?, vx: Float, vy: Float) {}
                override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) { syncFrom(c) }
                override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) { syncFrom(c) }
            }
            c.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry, h: Highlight) {
                    if (linkingHighlight) return
                    linkingHighlight = true
                    charts.forEach { o -> if (o !== c) syncHighlight(o, h.x) }
                    linkingHighlight = false
                }
                override fun onNothingSelected() {
                    if (linkingHighlight) return
                    linkingHighlight = true
                    charts.forEach { o -> if (o !== c) o.highlightValue(null, false) }
                    linkingHighlight = false
                }
            })
        }
    }

    /**
     * 把 X 位置的十字竖线同步到目标图。
     *
     * ⚠️ CombinedChart（主图/MACD 副图/MULTI 柱线图）必须显式给出 dataIndex：
     * [com.github.mikephil.charting.charts.Chart.highlightValue] 的三参重载内部构造的 Highlight
     * 其 dataIndex 恒为 -1，而 CombinedData.getEntryForHighlight 会执行 getDataByIndex(dataIndex)
     * → List.get(-1) → IndexOutOfBoundsException。故此处为 CombinedChart 手工构造带 dataIndex 的 Highlight。
     */
    private fun syncHighlight(chart: BarLineChartBase<*>, x: Float) {
        val combined = chart.data as? CombinedData
        if (combined != null) {
            val all = combined.allData      // 子数据（CandleData/BarData/LineData…）的插入顺序即 dataIndex
            for (i in all.indices) {
                val dsi = all[i].dataSets.indexOfFirst { it.isHighlightEnabled }
                if (dsi >= 0) {
                    val hl = Highlight(x, Float.NaN, dsi)
                    hl.dataIndex = i
                    chart.highlightValue(hl, false)
                    return
                }
            }
            return
        }
        val dsi = chart.data?.dataSets?.indexOfFirst { it.isHighlightEnabled } ?: -1
        if (dsi >= 0) chart.highlightValue(x, dsi, false)
    }

    // ─────────── 交易明细表 ───────────

    private fun buildTradeTable(r: Result): View {
        val ctx = requireContext()
        val scroll = HorizontalScrollView(ctx)
        val table = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val headers = listOf("#", "买入日期", "买价", "买入指标理由", "卖出日期", "卖价", "卖出指标理由", "收益率", "持仓")
        val widths = listOf(28, 82, 58, 175, 82, 58, 175, 68, 44)
        table.addView(tradeCellRow(headers, widths, header = true))
        r.trades.forEachIndexed { i, t ->
            val cells = listOf(
                "${i + 1}",
                t.buyDate, "%.2f".format(t.buyPrice),
                t.buyWhy.ifBlank { "—" },
                t.sellDate, "%.2f".format(t.sellPrice),
                t.sellWhy.ifBlank { "—" },
                "${if (t.returnPct > 0) "+" else ""}%.2f%%".format(t.returnPct),
                "${t.barsHeld}根"
            )
            val row = tradeCellRow(cells, widths, header = false)
            (row.getChildAt(7) as TextView).apply {
                setTextColor(if (t.returnPct > 0) RED else GREEN)
                setTypeface(typeface, Typeface.BOLD)
            }
            table.addView(row)
        }
        scroll.addView(table)
        return scroll
    }

    /** 交易明细专用行：指标理由列左对齐自动换行，其余居中 */
    private fun tradeCellRow(cells: List<String>, widthsDp: List<Int>, header: Boolean): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (header) Color.parseColor("#F0F0F0") else Color.WHITE)
            cells.forEachIndexed { i, txt ->
                val reasonCol = i == 3 || i == 6
                addView(TextView(ctx).apply {
                    text = txt
                    textSize = if (header) 10f else 9.5f
                    gravity = if (reasonCol) Gravity.START or Gravity.CENTER_VERTICAL
                    else Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
                    setTextColor(if (header) Color.parseColor("#666666") else Color.parseColor("#333333"))
                    if (header) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    layoutParams = LinearLayout.LayoutParams(dp(widthsDp[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }

    private fun tradeRow(cells: List<String>, widthsDp: List<Int>, header: Boolean): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (header) Color.parseColor("#F0F0F0") else Color.WHITE)
            cells.forEachIndexed { i, txt ->
                addView(TextView(ctx).apply {
                    text = txt
                    textSize = if (header) 10f else 10f
                    gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
                    setTextColor(if (header) Color.parseColor("#666666") else Color.parseColor("#333333"))
                    if (header) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    layoutParams = LinearLayout.LayoutParams(dp(widthsDp[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }

    // ─────────── 坐标轴公共样式 ───────────

    private fun dateFormatter(bars: List<Bar>) = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val ix = value.toInt()
            return if (ix in bars.indices) bars[ix].date.takeLast(5) else ""
        }
    }

    private fun styleCommonAxes(chart: CombinedChart, bars: List<Bar>, priceFormat: Boolean = true) {
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (bars.size / 6).coerceAtLeast(1).toFloat()
            textSize = 8f
            textColor = GRAY
            labelCount = 6
            setDrawGridLines(false)
            setAvoidFirstLastClipping(true)
            valueFormatter = dateFormatter(bars)
        }
        chart.axisLeft.apply {
            textSize = 8f
            textColor = GRAY
            gridColor = Color.parseColor("#EEEEEE")
            if (priceFormat) valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = "%.2f".format(value)
            }
        }
        chart.axisRight.isEnabled = false
    }

    private fun styleLineChartAxes(chart: LineChart, bars: List<Bar>, money: Boolean = false) {
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (bars.size / 6).coerceAtLeast(1).toFloat()
            textSize = 8f
            textColor = GRAY
            labelCount = 6
            setDrawGridLines(false)
            setAvoidFirstLastClipping(true)
            valueFormatter = dateFormatter(bars)
        }
        chart.axisLeft.apply {
            textSize = 8f
            textColor = GRAY
            gridColor = Color.parseColor("#EEEEEE")
            if (money) valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = "%,.0f".format(value)
            }
        }
        chart.axisRight.isEnabled = false
    }

    // ═══════════════════════ 通用 UI ═══════════════════════

    private fun status(s: String) {
        if (!isAdded) return
        statusTv.text = s
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(fill: String, stroke: String, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fill))
            setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun sectionTitle(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.parseColor("#222222"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun label(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.parseColor("#666666"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2), dp(8), 0, dp(3))
    }

    private fun hint(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 10f
        setTextColor(GRAY)
        setPadding(dp(2), dp(3), dp(2), dp(3))
    }

    private fun cardTitle(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#333333"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun card(build: (LinearLayout) -> Unit): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBg("#FFFFFF", "#E8E8E8", 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            build(this)
        }
    }

    /** 横向可滚动的选择 chip 组；getChildAt(0) 为内部 LinearLayout */
    private fun chipScroll(items: List<Pair<Int, String>>, onSelect: (Int) -> Unit): HorizontalScrollView {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        items.forEach { (key, txt) ->
            val tv = TextView(ctx).apply {
                text = txt
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener { onSelect(key) }
            }
            selectChip(tv, false)
            row.addView(tv)
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun selectChip(tv: TextView, selected: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (selected) {
                setColor(Color.parseColor("#FFF3E0"))
                setStroke(dp(1), ORANGE)
            } else {
                setColor(Color.parseColor("#F2F2F2"))
                setStroke(dp(1), Color.parseColor("#DDDDDD"))
            }
        }
        tv.background = bg
        tv.setTextColor(if (selected) ORANGE else Color.parseColor("#666666"))
        tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }
}
