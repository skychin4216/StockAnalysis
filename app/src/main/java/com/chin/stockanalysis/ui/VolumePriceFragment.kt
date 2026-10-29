package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.ssa.SsaEngine
import com.github.mikephil.charting.charts.BarChart
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
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.ViewPortHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 📈 量价策略 — 波段量价效率分析（Effort vs Result / Weis Wave 思路）
 *
 * 以价位为主轴把日K切成「一起一落」的波段链（zigzag），对每段回答三个问题：
 *  1. 这次价格运动用了多大的成交量？→ 段总量 / 单位涨幅成交量（万股每 1%）
 *  2. 价格到关键区域后卖盘/买盘怎么变？→ 回落段量能 vs 上升段量能
 *  3. 冲击结束后能不能维持？→ 回撤比 + 量比 → 接受度评级（缩量被托住 / 放量回原地）
 * 相邻同向段做单位量对比：第二次过同一压力区量更小 → 更容易过去。
 * 副图 OBV：段内 OBV 增减 + 顶/底背离检测。
 */
class VolumePriceFragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val GREEN = Color.parseColor("#43A047")
    private val BLUE = Color.parseColor("#1976D2")
    private val GRAY = Color.parseColor("#999999")
    private val DARK = Color.parseColor("#333333")
    private val TEAL = Color.parseColor("#00897B")
    private val GOLD = Color.parseColor("#F9A825")

    private data class KBar(val date: String, val open: Double, val high: Double,
                            val low: Double, val close: Double, val vol: Long)

    private data class Pivot(val idx: Int, val price: Double, val isHigh: Boolean, val confirmed: Boolean = true)

    private data class Leg(
        val no: Int, val up: Boolean,
        val fromIdx: Int, val toIdx: Int,
        val fromDate: String, val toDate: String,
        val fromPrice: Double, val toPrice: Double,
        val chgPct: Double, val bars: Int,
        val volSum: Double,   // 万股
        val volAvg: Double,   // 万股/日
        val unitVol: Double,  // 万股 / 1%
        val obvDelta: Double, // 万股
        val endConfirmed: Boolean
    )

    private lateinit var codeInput: EditText
    private lateinit var thInput: EditText
    private lateinit var runBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var resultBox: LinearLayout
    private var windowBars = 250
    /** 数据级别：false=日线，true=1H 小时线 */
    private var hourlyMode = false
    private var recentFocus = false   // 🔎 近10天：只收窄显示视口，分析仍基于完整窗口
    private var windowChipHost: LinearLayout? = null
    /** SSA cycle 嵌入窗口 L（20/30/60 可选） */
    private var cyclePeriod = 30

    /** 上次成功分析的完整结果，用于切换 SSA 周期 / 逐K回放 时不重拉数据直接重渲染 */
    private data class LastRun(val name: String, val code: String, val bars: List<KBar>,
                               val pivots: List<Pivot>, val legs: List<Leg>, val obv: DoubleArray, val th: Double,
                               val cycle: DoubleArray?,
                               val vcp: com.chin.stockanalysis.strategy.analysis.VcpDetector.Result? = null,
                               val vcpAll: List<com.chin.stockanalysis.strategy.analysis.VcpDetector.Result> = emptyList())
    private var lastRun: LastRun? = null

    // 四图十字光标联动
    private var priceChart: CombinedChart? = null
    private var rateChart: CombinedChart? = null
    private var obvChart: LineChart? = null
    private var ssaChart: LineChart? = null

    // 逐K回放（缠论页同款：K线逐根走出，四图同步增长）
    private var replayJob: kotlinx.coroutines.Job? = null
    private var replayPaused = false
    private var replaySpeedMs = 400L
    private var btnReplayPlay: Button? = null
    private var btnReplayPause: Button? = null
    private var btnReplaySpeed: Button? = null
    private var replayProgTv: TextView? = null
    /** 回放面板（放在图表卡片下方，render() 时挂到 resultBox） */
    private var replayRowPanel: LinearLayout? = null
    /** 四图容器（回放每帧只重建这里，卡片其余部分不动） */
    private var chartHost: LinearLayout? = null
    private var chartDetailBox: LinearLayout? = null

    /** 回放时临时隐藏的顶部视图（输入/chips/提示），停止回放后恢复 */
    private val replayHideViews = ArrayList<View>()

    /** 在 from 之外的图上同步一条竖线（用 X 轴 LimitLine 画，跨图表类型稳定，不走 Highlight） */
    private fun syncCrosshair(from: Int, x: Float) {
        syncVLine(from, 0, priceChart, x)
        syncVLine(from, 1, rateChart, x)
        syncVLine(from, 2, obvChart, x)
        syncVLine(from, 3, ssaChart, x)
    }

    private fun syncVLine(from: Int, idx: Int, chart: com.github.mikephil.charting.charts.Chart<*>?, x: Float) {
        val c = chart ?: return
        c.xAxis.removeAllLimitLines()
        if (from != idx) {
            val ll = LimitLine(x).apply {
                lineColor = Color.parseColor("#555555")
                lineWidth = 3f
            }
            c.xAxis.addLimitLine(ll)
        }
        c.invalidate()
    }

    /** 四图视口联动：任一图缩放/平移，其余图应用同一矩阵（视口偏移一致，直接复用矩阵） */
    private fun attachViewportSync() {
        val charts = listOfNotNull(priceChart, rateChart, obvChart, ssaChart)
        if (charts.size < 2) return
        charts.forEach { src ->
            src.onChartGestureListener = object : OnChartGestureListener {
                private fun propagate() {
                    charts.filter { it !== src }.forEach { dst ->
                        val m = android.graphics.Matrix(src.viewPortHandler.matrixTouch)
                        @Suppress("UNCHECKED_CAST")
                        (dst.viewPortHandler as ViewPortHandler).refresh(m, dst, true)
                    }
                }
                override fun onChartGestureStart(me: android.view.MotionEvent?, g: ChartTouchListener.ChartGesture?) {
                    // 暂停只由「⏸ 暂停」按钮控制；图表上的任何手势（含带位移的点击被识别成 DRAG）都不打断回放
                }
                override fun onChartGestureEnd(me: android.view.MotionEvent?, g: ChartTouchListener.ChartGesture?) {
                    // 点击/长按不算视口变化：否则点击一张未缩放的图会把它的完整矩阵套到已放大图上，图瞬间缩回去
                    if (g == ChartTouchListener.ChartGesture.SINGLE_TAP ||
                        g == ChartTouchListener.ChartGesture.LONG_PRESS) return
                    propagate()
                }
                override fun onChartLongPressed(me: android.view.MotionEvent?) {}
                override fun onChartDoubleTapped(me: android.view.MotionEvent?) = propagate()
                override fun onChartSingleTapped(me: android.view.MotionEvent?) {}
                override fun onChartFling(m1: android.view.MotionEvent?, m2: android.view.MotionEvent?, vx: Float, vy: Float) = propagate()
                override fun onChartScale(me: android.view.MotionEvent?, sx: Float, sy: Float) = propagate()
                override fun onChartTranslate(me: android.view.MotionEvent?, dx: Float, dy: Float) = propagate()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }
        scroll.addView(box)

        val titleV = title("📈 量价策略 · 波段量价效率")
        box.addView(titleV)
        val hintV = hint("以价位为主轴切成「一起一落」波段链：上升段统计单位涨幅消耗多少成交量（万股/1%），" +
            "回落段看量能判断市场接受程度（缩量=下方有人接、被托住；放量=买单一停就回去）。" +
            "突破前高另起新段，相邻同向段对比单位量：第二次过同一位置量更小 → 更容易过去。附 OBV 背离检测。")
        box.addView(hintV)
        replayHideViews += titleV; replayHideViews += hintV

        // 输入行
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        codeInput = EditText(ctx).apply {
            hint = "股票代号 / 名称，如 600519 或 贵州茅台"
            textSize = 13f; isSingleLine = true
            setTextColor(DARK); setHintTextColor(Color.parseColor("#AAAAAA"))
            background = roundedBg("#F5F5F5", "#DDDDDD", 6)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        inputRow.addView(codeInput)
        runBtn = Button(ctx).apply {
            text = "开始分析"; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
            setOnClickListener { onRun() }
        }
        inputRow.addView(runBtn)
        box.addView(inputRow)
        replayHideViews += inputRow

        // 参数行：波段阈值
        val prow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        prow.addView(TextView(ctx).apply { text = "波段阈值%"; textSize = 11f; setTextColor(GRAY) })
        thInput = EditText(ctx).apply {
            setText("5"); textSize = 12f; isSingleLine = true
            setTextColor(DARK)
            background = roundedBg("#FFFFFF", "#CCCCCC", 6)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(34)).apply {
                marginStart = dp(6); marginEnd = dp(10)
            }
        }
        prow.addView(thInput)
        prow.addView(TextView(ctx).apply {
            text = "反转超过该幅度才确认拐点（小=波段密，大=波段疏）"
            textSize = 10f; setTextColor(GRAY)
        })
        box.addView(prow)
        replayHideViews += prow

        // 数据级别：日线 / 1H 小时线（1H 首次全量下载后落盘，之后只拉增量）
        val lvRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        val rg = android.widget.RadioGroup(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val rbDay = android.widget.RadioButton(ctx).apply {
            text = "日线"; textSize = 12f; isChecked = true; id = 3001
        }
        val rbHour = android.widget.RadioButton(ctx).apply {
            text = "1H 小时线"; textSize = 12f; id = 3002
        }
        rg.addView(rbDay); rg.addView(rbHour)
        lvRow.addView(rg)
        android.widget.CheckBox(ctx).apply {
            text = "🔎 近10天"; textSize = 12f
            setOnCheckedChangeListener { _, c ->
                recentFocus = c
                // 取消时先复位四图视口，再按当前模式重填
                if (!c) listOfNotNull(priceChart, rateChart, obvChart, ssaChart).forEach { it.fitScreen() }
                lastRun?.let { lr -> chartHost?.let { h -> fillCharts(h, lr, lr.bars.size - 1) } }
            }
        }.also { lvRow.addView(it) }
        box.addView(lvRow)
        replayHideViews += lvRow
        rg.setOnCheckedChangeListener { _, id ->
            hourlyMode = (id == 3002)
            rebuildWindowChips()
        }

        // 区间（日线/1H 标签不同，切级别时重建）
        windowChipHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(windowChipHost)
        replayHideViews += windowChipHost!!
        rebuildWindowChips()

        statusTv = TextView(ctx).apply { textSize = 11f; setTextColor(GRAY); setPadding(dp(2), dp(6), dp(2), dp(2)) }
        box.addView(statusTv)
        replayHideViews += statusTv

        // ── ▶ 逐K回放面板（缠论页同款）：K线逐根走出，四图同步增长，看量价结构的生成过程 ──
        val replayRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        btnReplayPlay = Button(ctx).apply { text = "▶ 逐K回放"; textSize = 11f; setOnClickListener { onReplayPlay() } }
        btnReplayPause = Button(ctx).apply { text = "⏸ 暂停"; textSize = 11f; setOnClickListener { onReplayPause() } }
        btnReplaySpeed = Button(ctx).apply {
            text = "速度:中"; textSize = 11f
            setOnClickListener {
                replaySpeedMs = when (replaySpeedMs) { 1000L -> 400L; 400L -> 120L; else -> 1000L }
                text = "速度:" + when (replaySpeedMs) { 1000L -> "慢"; 400L -> "中"; else -> "快" }
            }
        }
        val btnReplayReset = Button(ctx).apply {
            text = "⏮ 重置"; textSize = 11f
            setOnClickListener {
                val wasPlaying = replayJob != null
                stopReplay(restore = true)
                replayProgTv?.text = "已重置到起点"
                if (wasPlaying) startReplay()
            }
        }
        replayProgTv = TextView(ctx).apply {
            text = "先点「开始分析」加载K线"; textSize = 10f; setTextColor(GRAY); setPadding(dp(6), 0, 0, 0)
        }
        replayRow.addView(btnReplayPlay); replayRow.addView(btnReplayPause)
        replayRow.addView(btnReplaySpeed); replayRow.addView(btnReplayReset)
        replayRow.addView(replayProgTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // 不在顶部 addView：面板挂到 render() 里图表卡片的下方
        replayRowPanel = replayRow

        resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(resultBox)
        val tailHintV = hint("⚠️ 波段由收盘价 zigzag 切分，末端最后一段可能尚未确认（随行情变化）；量能单位=万股；结果不构成投资建议。")
        box.addView(tailHintV)
        replayHideViews += tailHintV
        return scroll
    }

    // ═══════════════════════ 执行 ═══════════════════════

    /** 区间 chips：日线按交易日，1H 按每交易日 4 根估算 */
    private fun rebuildWindowChips() {
        val host = windowChipHost ?: return
        host.removeAllViews()
        val items = if (hourlyMode)
            listOf(120 to "近1.5月", 250 to "近3月", 500 to "近6月")
        else
            listOf(60 to "近3个月", 120 to "近6个月", 250 to "近1年")
        if (items.none { it.first == windowBars }) windowBars = 250
        host.addView(chipScroll(items, windowBars) { windowBars = it })
    }

    /** X 轴标签：日线 MM-DD；1H 显示 MM-DD HH */
    private fun xLabel(bars: List<KBar>, i: Int): String {
        val d = bars[i].date
        return if (d.contains(" ") && d.length >= 13) d.substring(5, 13) else d.substring(5)
    }

    private fun onRun() {
        val raw = codeInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) { status("请输入股票代号或名称"); return }
        val th = thInput.text?.toString()?.trim()?.toDoubleOrNull()
        if (th == null || th < 1.0 || th > 30.0) { status("⚠️ 波段阈值需为 1~30 的数字"); return }
        runBtn.isEnabled = false
        stopReplay(restore = false)
        resultBox.removeAllViews()
        lifecycleScope.launch {
            try {
                status("⏳ 解析股票并加载${if (hourlyMode) "1H" else "日"}K…")
                val (code, name) = withContext(Dispatchers.IO) { resolveCode(raw) }
                if (code == null) { status("⚠️ 未找到「$raw」"); return@launch }
                val barsAll = withContext(Dispatchers.IO) { loadBars(code) }
                if (barsAll == null) { status("⚠️ ${name ?: code} ${if (hourlyMode) "1H" else "日"}K获取失败"); return@launch }
                val bars = barsAll.takeLast(windowBars)
                if (bars.size < 60) { status("⚠️ 有效K线仅 ${bars.size} 根（需≥60）"); return@launch }
                status("⏳ 波段切分 + 量价效率计算中（${bars.size} 根，阈值 $th%）…")
                val pivots = zigzag(bars.map { it.close }, th)
                if (pivots.size < 3) { status("⚠️ 波段数不足（${pivots.size - 1} 段），请调小阈值"); return@launch }
                val obv = calcObv(bars)
                val legs = buildLegs(bars, pivots, obv)
                status("⏳ SSA cycle 分量计算中（L=$cyclePeriod）…")
                val cycle = computeCycle(bars, cyclePeriod)
                // VCP 波动收缩检测（多锚点扫描：大 base 内嵌套的小 VCP 一并检出）
                val vcpAll = com.chin.stockanalysis.strategy.analysis.VcpDetector.detectAll(
                    bars.map { com.chin.stockanalysis.strategy.analysis.VcpDetector.Bar(it.date, it.close, it.vol) },
                    pivots.map { com.chin.stockanalysis.strategy.analysis.VcpDetector.Pivot(it.idx, it.price, it.isHigh, it.confirmed) },
                    legs.map { com.chin.stockanalysis.strategy.analysis.VcpDetector.Leg(it.up, it.chgPct, it.volAvg) }
                )
                // 无成立形态时仍跑单锚点版拿失败原因，卡片照常显示 ❌ 说明
                val vcpPrimary = vcpAll.firstOrNull() ?: com.chin.stockanalysis.strategy.analysis.VcpDetector.detect(
                    bars.map { com.chin.stockanalysis.strategy.analysis.VcpDetector.Bar(it.date, it.close, it.vol) },
                    pivots.map { com.chin.stockanalysis.strategy.analysis.VcpDetector.Pivot(it.idx, it.price, it.isHigh, it.confirmed) },
                    legs.map { com.chin.stockanalysis.strategy.analysis.VcpDetector.Leg(it.up, it.chgPct, it.volAvg) }
                )
                val nm = name ?: code
                lastRun = LastRun(nm, code, bars, pivots, legs, obv, th, cycle, vcpPrimary, vcpAll)
                render(nm, code, bars, pivots, legs, obv, th, cycle, vcpPrimary, vcpAll)
                status("✅ 分析完成：$nm($code) · ${legs.size} 段")
            } finally {
                runBtn.isEnabled = true
            }
        }
    }

    // ═══════════════════════ 波段切分 / 统计 ═══════════════════════

    /** 收盘价 zigzag：反向波动超过 th% 确认拐点；末端极值标记未确认 */
    private fun zigzag(closes: List<Double>, thPct: Double): List<Pivot> {
        val n = closes.size
        if (n < 3) return emptyList()
        val th = thPct / 100.0
        var loIdx = 0; var lo = closes[0]
        var hiIdx = 0; var hi = closes[0]
        var dir = 0; var start = 0
        for (i in 1 until n) {
            val p = closes[i]
            if (p < lo) { lo = p; loIdx = i }
            if (p > hi) { hi = p; hiIdx = i }
            if (hi / lo - 1.0 >= th) {
                dir = if (loIdx < hiIdx) 1 else -1
                start = i
                break
            }
        }
        if (dir == 0) return listOf(Pivot(0, closes[0], false, false), Pivot(n - 1, closes[n - 1], true, false))
        val pivots = ArrayList<Pivot>()
        var extIdx: Int; var extPrice: Double
        if (dir == 1) { pivots.add(Pivot(loIdx, lo, false)); extIdx = hiIdx; extPrice = hi }
        else { pivots.add(Pivot(hiIdx, hi, true)); extIdx = loIdx; extPrice = lo }
        for (i in (start + 1) until n) {
            val p = closes[i]
            if (dir == 1) {
                if (p > extPrice) { extPrice = p; extIdx = i }
                else if (extPrice / p - 1.0 >= th) {
                    pivots.add(Pivot(extIdx, extPrice, true))
                    dir = -1; extIdx = i; extPrice = p
                }
            } else {
                if (p < extPrice) { extPrice = p; extIdx = i }
                else if (p / extPrice - 1.0 >= th) {
                    pivots.add(Pivot(extIdx, extPrice, false))
                    dir = 1; extIdx = i; extPrice = p
                }
            }
        }
        pivots.add(Pivot(extIdx, extPrice, dir == 1, confirmed = false))
        return pivots
    }

    private fun calcObv(bars: List<KBar>): DoubleArray {
        val obv = DoubleArray(bars.size)
        for (i in 1 until bars.size) {
            obv[i] = obv[i - 1] + when {
                bars[i].close > bars[i - 1].close -> bars[i].vol.toDouble()
                bars[i].close < bars[i - 1].close -> -bars[i].vol.toDouble()
                else -> 0.0
            }
        }
        return obv
    }

    /** SSA cycle 分量：整窗一次分解（直接调 internal decompose，不走 run() 的逐K滚动，O(n) 快），绕 0 震荡，仅可视化 */
    private suspend fun computeCycle(bars: List<KBar>, L: Int): DoubleArray? =
        withContext(Dispatchers.Default) {
            if (bars.size < L + 20) return@withContext null
            runCatching {
                val close = DoubleArray(bars.size) { bars[it].close }
                SsaEngine.decompose(close, L).cycle
            }.getOrNull()
        }

    /** 切换 SSA 周期：用 lastRun 缓存重算 cycle 并重绘四图，不重拉数据 */
    private fun rerunSsa() {
        val lr = lastRun ?: return
        stopReplay(restore = false)
        lifecycleScope.launch {
            status("⏳ SSA cycle 分量计算中（L=$cyclePeriod）…")
            val cycle = computeCycle(lr.bars, cyclePeriod)
            if (!isAdded) return@launch
            lastRun = lr.copy(cycle = cycle)
            render(lr.name, lr.code, lr.bars, lr.pivots, lr.legs, lr.obv, lr.th, cycle, lr.vcp, lr.vcpAll)
            status("✅ 已按 L=$cyclePeriod 重绘 SSA cycle")
        }
    }

    // ═══════════════════════ 逐K回放（缠论页同款） ═══════════════════════

    private fun onReplayPlay() {
        val lr = lastRun
        if (replayJob != null) { stopReplay(restore = true); status("已停止回放（恢复完整图）"); return }
        if (lr == null || lr.bars.size < 60) { status("⚠️ 先点「开始分析」加载K线（需≥60根）"); return }
        startReplay()
    }

    private fun onReplayPause() {
        if (replayJob == null) return   // 未在回放时无效
        replayPaused = !replayPaused
        btnReplayPause?.text = if (replayPaused) "▶ 继续" else "⏸ 暂停"
        if (replayPaused) replayProgTv?.text = "已暂停（点「▶ 继续」接着走）"
    }

    /** 回放可见窗口宽度（根 K） */
    private val REPLAY_VIS_BARS = 60f

    /** 回放跟随：四图统一固定宽度窗口，向右滚动露出最新一根；数据不足一窗时显示全部 */
    private fun followReplayViewport(idx: Int) {
        listOfNotNull(priceChart, rateChart, obvChart, ssaChart).forEach { c ->
            c.setVisibleXRangeMaximum(REPLAY_VIS_BARS)
            c.setVisibleXRangeMinimum(0f)
            c.moveViewToX((idx - REPLAY_VIS_BARS + 1).coerceAtLeast(-0.5f))
            c.invalidate()
        }
    }

    /** 退出回放恢复自由缩放/拖动 */
    private fun releaseReplayViewport() {
        listOfNotNull(priceChart, rateChart, obvChart, ssaChart).forEach { c ->
            c.setVisibleXRangeMaximum(Float.MAX_VALUE)
            c.fitScreen()
            c.invalidate()
        }
    }

    /** 回放主循环：从第 40 根暖机位开始，每帧只显示 [0..idx] 的K线/波段/速率/OBV/cycle，四图同步增长 */
    private fun startReplay() {
        val lr = lastRun ?: return
        val host = chartHost ?: return
        btnReplayPlay?.text = "⏹ 停止"
        replayPaused = false; btnReplayPause?.text = "⏸ 暂停"
        // 回放时隐藏顶部输入/chip 区，把屏幕让给图表；停止回放后恢复
        replayHideViews.forEach { it.visibility = View.GONE }
        var idx = 40.coerceAtMost(lr.bars.size - 1)   // 回放起点：前 40 根暖机
        replayJob = lifecycleScope.launch {
            while (isActive && idx < lr.bars.size) {
                while (isActive && replayPaused) kotlinx.coroutines.delay(120)  // 暂停挂起
                if (!isActive) break
                fillCharts(host, lr, idx)
                followReplayViewport(idx)
                val bar = lr.bars[idx]
                replayProgTv?.text = "${idx + 1}/${lr.bars.size}  ${bar.date}  收${bar.close}"
                idx++
                kotlinx.coroutines.delay(replaySpeedMs)
            }
            if (idx >= lr.bars.size) { stopReplay(restore = true); replayProgTv?.text = "回放完成 ${lr.bars.size}/${lr.bars.size}" }
        }
    }

    /** 停止回放；restore=true 时恢复完整四图 */
    private fun stopReplay(restore: Boolean) {
        replayJob?.cancel(); replayJob = null
        replayPaused = false
        btnReplayPause?.text = "⏸ 暂停"
        btnReplayPlay?.text = "▶ 逐K回放"
        replayHideViews.forEach { it.visibility = View.VISIBLE }
        if (restore) {
            val lr = lastRun; val host = chartHost
            if (lr != null && host != null) { fillCharts(host, lr, lr.bars.size - 1); releaseReplayViewport() }
        }
    }

    override fun onDestroyView() {
        replayJob?.cancel(); replayJob = null   // 切页时停掉逐K回放
        super.onDestroyView()
    }

    private fun buildLegs(bars: List<KBar>, pivots: List<Pivot>, obv: DoubleArray): List<Leg> {
        val legs = ArrayList<Leg>()
        for (k in 0 until pivots.size - 1) {
            val a = pivots[k]; val b = pivots[k + 1]
            val up = b.isHigh
            var volSum = 0.0
            for (i in (a.idx + 1)..b.idx) volSum += bars[i].vol.toDouble()
            volSum /= 10000.0 // 股 → 万股
            val chgPct = (b.price - a.price) / a.price * 100.0
            val nBars = b.idx - a.idx
            legs.add(Leg(
                no = k, up = up,
                fromIdx = a.idx, toIdx = b.idx,
                fromDate = bars[a.idx].date, toDate = bars[b.idx].date,
                fromPrice = a.price, toPrice = b.price,
                chgPct = chgPct, bars = nBars,
                volSum = volSum,
                volAvg = if (nBars > 0) volSum / nBars else 0.0,
                unitVol = if (abs(chgPct) > 0.01) volSum / abs(chgPct) else 0.0,
                obvDelta = (obv[b.idx] - obv[a.idx]) / 10000.0,
                endConfirmed = b.confirmed
            ))
        }
        return legs
    }

    // ═══════════════════════ 渲染 ═══════════════════════

    private fun render(name: String, code: String, bars: List<KBar>, pivots: List<Pivot>,
                       legs: List<Leg>, obv: DoubleArray, th: Double, cycle: DoubleArray?,
                       vcp: com.chin.stockanalysis.strategy.analysis.VcpDetector.Result? = null,
                       vcpAll: List<com.chin.stockanalysis.strategy.analysis.VcpDetector.Result> = emptyList()) {
        resultBox.removeAllViews()
        // 新一轮分析会重建 chartHost，旧 chart 仍挂在旧 host 上 → 必须清缓存，否则 addView 报 "already has a parent" 闪退
        priceChart = null; rateChart = null; obvChart = null; ssaChart = null
        renderSummary(name, code, bars, pivots, legs, obv, th)
        vcp?.let { renderVcpCard(it, vcpAll.drop(1)) }
        val detailBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        detailBox.addView(hint("👆 点击主图 K 线或拐点圆点，这里显示该段量价明细"))
        chartDetailBox = detailBox
        // 四图同卡紧邻：说明文字全部在上/下方，图与图之间无文字；图区单独放 chartHost，回放每帧只重建图区
        // 图表卡片用窄 padding（左右 2dp），让 K 线图尽量占满手机宽度
        resultBox.addView(cardSlim {
            it.addView(cardTitle("K线波段 ＋ OBV ＋ SSA cycle ＋ 量价速率（四图逐K同位置，缩放/竖线联动）"))
            it.addView(hint("圆点：红=波段底（买点位）绿=波段顶（卖点位）灰=未确认；上升段标单位涨幅成交量，回落段标相对上一上升段的量比。点击K线在底部看该段明细。"))
            val host = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            chartHost = host
            it.addView(host)
            // SSA 周期切换：不重拉数据，用缓存只重算 cycle 分量重绘
            it.addView(TextView(requireContext()).apply {
                text = "SSA 嵌入窗口 L（L 小=短周期敏感、振荡密；L 大=抓长周期、曲线平滑）"
                textSize = 10f; setTextColor(GRAY); setPadding(0, dp(4), 0, 0)
            })
            it.addView(chipScroll(listOf(20 to "L=20", 30 to "L=30", 60 to "L=60"), cyclePeriod) { sel ->
                if (sel != cyclePeriod) { cyclePeriod = sel; rerunSsa() }
            })
            it.addView(hint("②OBV 能量潮（亿股）+ 唐奇安通道（20日：上轨/下轨/灰色中位线）：OBV 贴上轨运行=资金持续流入、趋势偏强；贴下轨=持续流出；在中位线附近来回=震荡，对应价格也震荡。价格新高 OBV 不新高 = 顶背离；价格新低 OBV 不新低 = 底背离。"))
            it.addView(hint("③SSA cycle（青实线）＝剔除趋势噪声后的主周期振荡；橙虚线＝速度（一阶差分，峰领先 cycle 约 T/4）；紫点线＝加速度（二阶差分，再领先 T/4）：加速度下穿零轴是最早的周期衰竭预警，价格/周期/资金三维速度共振转向最可靠。三线各自归一化、共享零轴；半透明尾段＝边缘效应区（末端 L~2L 根）可信度低；回放时为整窗分解截断显示，仅用于观察节奏。"))
            it.addView(hint("④速率图（对数轴，标签为原始量级）：柱=当日每推动 1% 价格消耗的成交量（红涨绿跌）。紫线=近5K 成交量合计÷涨跌幅合计（万股/1%）：走高=推同样的幅度越来越费量（浮筹多/阻力大），走低=越来越省量（浮筹少/抛压轻）。"))
            it.addView(detailBox)
        })
        // 回放面板放图表卡片正下方；resultBox.removeAllViews() 已自动 detach，这里先防御性摘父再挂
        replayRowPanel?.let { panel ->
            (panel.parent as? android.view.ViewGroup)?.removeView(panel)
            resultBox.addView(panel)
        }
        lastRun?.let { lr -> chartHost?.let { host -> fillCharts(host, lr, bars.size - 1) } }
    }

    /** 填充四图到 host：只画 [0..upto] 的K线/波段/速率/OBV/cycle（upto=bars.size-1 即完整图；回放帧=截断显示） */
    private fun fillCharts(host: LinearLayout, lr: LastRun, upto: Int) {
        val n = (upto + 1).coerceAtMost(lr.bars.size)
        val barsSub = lr.bars.subList(0, n)
        val pivotsSub = lr.pivots.filter { it.idx < n }
        val legsSub = lr.legs.filter { it.toIdx < n }
        val obvSub = lr.obv.copyOfRange(0, n)
        val cycleSub = lr.cycle?.copyOfRange(0, n)
        val detail = chartDetailBox ?: LinearLayout(requireContext())

        // 首帧：建图 + 挂树 + 同步监听；后续帧：只换数据（避免每帧重建视图导致抖动）
        val price = renderPriceChart(priceChart, barsSub, pivotsSub, legsSub, detail)
        val obvV = renderObvChart(obvChart, barsSub, pivotsSub, obvSub)
        val ssaV = renderSsaChart(ssaChart, barsSub, cycleSub)
        val rate = renderVolumeChart(rateChart, barsSub, pivotsSub, legsSub)
        if (firstFrame(priceChart, host)) {
            host.removeAllViews()
            // 顺序：K线 → OBV → SSA cycle → 量价速率（速率在最下面，带日期标签）
            host.addView(price, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(380)))
            host.addView(obvV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140)).apply { topMargin = dp(4) })
            host.addView(ssaV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)))
            host.addView(rate, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))
            attachViewportSync()
        }
        // 近10天聚焦：四图视口统一收窄到窗口末端（回放时随 upto 逐帧前移）
        if (recentFocus) {
            val cnt = if (hourlyMode) 40 else 10
            if (n > cnt) listOfNotNull(priceChart, rateChart, obvChart, ssaChart).forEach { c ->
                c.setVisibleXRangeMaximum(cnt.toFloat())
                c.moveViewToX((n - cnt).toFloat())
            }
        }
    }

    private fun firstFrame(priceChart: CombinedChart?, host: LinearLayout): Boolean {
        // host 已经有 4 个子图且首图就是缓存的 priceChart → 不是首帧
        return !(host.childCount == 4 && host.getChildAt(0) === priceChart)
    }

    /** 顶部结论卡：当前段状态 + 最近起落对比 + OBV 背离 */
    private fun renderSummary(name: String, code: String, bars: List<KBar>, pivots: List<Pivot>,
                              legs: List<Leg>, obv: DoubleArray, th: Double) {
        val ctx = requireContext()
        resultBox.addView(card {
            it.addView(TextView(ctx).apply {
                text = "$name($code)"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#222222"))
            })
            it.addView(TextView(ctx).apply {
                text = "${bars.first().date} ~ ${bars.last().date} · ${bars.size} 根日K · 阈值 $th% · ${legs.size} 段"
                textSize = 10f; setTextColor(GRAY); setPadding(0, dp(2), 0, dp(6))
            })

            val last = legs.last()
            val lastDir = if (last.up) "↑ 上升" else "↓ 回落"
            val lastState = if (last.endConfirmed) "已确认拐点" else "进行中（未确认）"
            it.addView(TextView(ctx).apply {
                text = "📌 当前：段${cn(last.no)} $lastDir $lastState\n" +
                    "${last.fromDate} %.2f → ${last.toDate} %.2f（%s%.1f%%）".format(last.fromPrice, last.toPrice, if (last.up) "+" else "", last.chgPct)
                textSize = 12f; setTextColor(if (last.up) RED else GREEN); setPadding(0, 0, 0, dp(4))
            })

            // 最近同向段单位量对比
            val prevSame = legs.lastOrNull { l -> l.up == last.up && l.no < last.no }
            if (prevSame != null && prevSame.unitVol > 0) {
                val r = last.unitVol / prevSame.unitVol
                val verdict = when {
                    !last.up -> when {
                        r < 0.8 -> "回落缩量，抛压减轻"
                        r > 1.25 -> "回落放量，抛压加重"
                        else -> "回落量能持平"
                    }
                    else -> when {
                        r < 0.8 -> "推动更省量 → 过同一区域更容易"
                        r > 1.25 -> "推动更费量 → 上方分歧加大"
                        else -> "单位量与上段持平"
                    }
                }
                it.addView(TextView(ctx).apply {
                    text = "🔁 同向段对比：段${cn(prevSame.no)} 单位量 %.2f → 段${cn(last.no)} %.2f 万股/1%%（%.2fx）→ $verdict"
                        .format(prevSame.unitVol, last.unitVol, r)
                    textSize = 11f; setTextColor(DARK); setPadding(0, 0, 0, dp(4))
                })
            }

            // 最近回落段维持度
            val lastDown = legs.lastOrNull { l -> !l.up }
            if (lastDown != null && lastDown.no >= 1) {
                it.addView(TextView(ctx).apply {
                    text = "🧱 最近回落（段${cn(lastDown.no)}）维持度：" + acceptanceText(legs, lastDown)
                    textSize = 11f; setTextColor(DARK); setPadding(0, 0, 0, dp(4))
                })
            }

            // OBV 背离
            it.addView(TextView(ctx).apply {
                text = "📊 OBV：" + obvDivergenceText(pivots, obv)
                textSize = 11f; setTextColor(DARK)
            })
        })
    }

    /** 🌀 VCP 波动收缩卡片：结论 + 收缩序列 + 压力/突破信息 + 评分；others = 多锚点扫出的其余成立形态 */
    private fun renderVcpCard(v: com.chin.stockanalysis.strategy.analysis.VcpDetector.Result,
                              others: List<com.chin.stockanalysis.strategy.analysis.VcpDetector.Result> = emptyList()) {
        val ctx = requireContext()
        resultBox.addView(card {
            it.addView(cardTitle("🌀 VCP 波动收缩（高点走平·低点递升·振幅收缩·量能萎缩→放量突破）"))
            val (icon, color) = when (v.status) {
                com.chin.stockanalysis.strategy.analysis.VcpDetector.Status.BREAKOUT -> "✅" to RED
                com.chin.stockanalysis.strategy.analysis.VcpDetector.Status.FORMING -> "🔄" to BLUE
                com.chin.stockanalysis.strategy.analysis.VcpDetector.Status.INVALID -> "❌" to GRAY
            }
            it.addView(TextView(ctx).apply {
                text = "$icon ${v.reason}" + if (v.status != com.chin.stockanalysis.strategy.analysis.VcpDetector.Status.INVALID) "（评分 ${v.score}/100）" else ""
                textSize = 12f; setTextColor(color); setPadding(0, 0, 0, dp(4))
            })
            if (v.status == com.chin.stockanalysis.strategy.analysis.VcpDetector.Status.INVALID) return@card
            // 收缩序列：-22.1% → -13.4% → -7.2%（每次带回落段日均量）
            val seq = v.contractions.joinToString(" → ") { c -> "-%.1f%%".format(c.depthPct) }
            it.addView(TextView(ctx).apply {
                text = "📉 收缩序列（${v.anchorDate} 起）：$seq"
                textSize = 11f; setTextColor(DARK); setPadding(0, 0, 0, dp(2))
            })
            it.addView(TextView(ctx).apply {
                text = "🔇 回落段日均量：" + v.contractions.joinToString(" → ") { c -> "%.2f".format(c.downVolAvg) } +
                    " 万股（末/首 = %.0f%%）".format(v.contractions.last().downVolAvg / v.contractions.first().downVolAvg * 100)
                textSize = 11f; setTextColor(DARK); setPadding(0, 0, 0, dp(2))
            })
            it.addView(TextView(ctx).apply {
                text = "🎯 压力 %.2f（主图青色虚线）· 金色折线=收缩轮廓（H0→各低点/反弹高点）\n现价距突破 %.1f%% · 最新量 %.2f 万股（%.2f×base均量 %.2f）"
                    .format(v.resistance, v.distToResistPct, v.lastVol, v.volMult, v.baseVolAvg)
                textSize = 11f; setTextColor(DARK)
            })
            if (v.tailUnconfirmed) it.addView(hint("注：末端拐点尚未确认，形态可能继续演化。"))
            if (others.isNotEmpty()) it.addView(hint(
                "🔎 多锚点另检出 ${others.size} 个成立形态（主图金色细轮廓）：" +
                    others.joinToString("；") { o -> "${o.anchorDate} 起，压力 %.2f，评分 %d".format(o.resistance, o.score) }))
        })
    }

    private fun obvDivergenceText(pivots: List<Pivot>, obv: DoubleArray): String {
        val highs = pivots.filter { it.isHigh }
        val lows = pivots.filter { !it.isHigh }
        val sb = StringBuilder()
        if (highs.size >= 2) {
            val a = highs[highs.size - 2]; val b = highs.last()
            if (b.price > a.price * 1.001 && obv[b.idx] < obv[a.idx])
                sb.append("⚠️ 顶背离：价格新高 %.2f>%.2f 但 OBV 走低；".format(b.price, a.price))
        }
        if (lows.size >= 2) {
            val a = lows[lows.size - 2]; val b = lows.last()
            if (b.price < a.price * 0.999 && obv[b.idx] > obv[a.idx])
                sb.append("✅ 底背离：价格新低 %.2f<%.2f 但 OBV 抬高；".format(b.price, a.price))
        }
        return if (sb.isEmpty()) "量价同步，无明显背离" else sb.toString().removeSuffix("；")
    }

    /** 回落段接受度：量比（日均量/上段上升日均量）+ 回撤比（回落幅度/上涨幅度） */
    private fun acceptanceText(legs: List<Leg>, down: Leg): String {
        val up = legs.getOrNull(down.no - 1) ?: return "—"
        if (!up.up || up.volAvg <= 0) return "—"
        val volR = down.volAvg / up.volAvg
        val retrace = if (up.chgPct > 0.01) abs(down.chgPct) / up.chgPct else 0.0
        val verdict = when {
            volR < 0.8 && retrace < 0.5 -> "✅ 缩量小回 → 下方有人接，回落被托住"
            volR > 1.2 || retrace > 0.8 -> "⚠️ 放量深回 → 上涨依赖主动买单，买单一停就回去"
            else -> "➖ 中性（量/回撤一强一弱）"
        }
        return "日均量 %.2fx、回撤比 %.2f → %s".format(volR, retrace, verdict)
    }

    /** 图① K线 + 波段折线 + 拐点量价标注（写在该段终点价位旁）。
     *  existing != null → 复用现有图，只更新数据（避免回放每帧重建视图导致抖动） */
    private fun renderPriceChart(existing: CombinedChart?, bars: List<KBar>, pivots: List<Pivot>, legs: List<Leg>, detailBox: LinearLayout): View {
        val ctx = requireContext()
        val n = bars.size

        val ces = ArrayList<CandleEntry>(n)
        for (i in 0 until n) {
            val b = bars[i]
            ces.add(CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat()))
        }
        val cds = CandleDataSet(ces, "K线").apply {
            color = GRAY; shadowColor = GRAY; shadowWidth = 0.8f
            increasingColor = RED; increasingPaintStyle = Paint.Style.FILL
            decreasingColor = GREEN; decreasingPaintStyle = Paint.Style.FILL
            neutralColor = GRAY; setDrawValues(false)
            setDrawHighlightIndicators(true)
            highLightColor = Color.parseColor("#555555"); setHighlightLineWidth(3f)
        }

        // 拐点标注：每段终点写「涨/跌幅% + 单位量(上升) 或 量比(回落)」
        val pivotLabels = HashMap<Int, String>()
        if (pivots.isNotEmpty()) pivotLabels[pivots[0].idx] = xLabel(bars, pivots[0].idx)   // 回放早期 pivots 可能极少，防空
        for (k in 1 until pivots.size) {
            val leg = legs[k - 1]
            val main = "%s%.1f%%".format(if (leg.up) "+" else "－", abs(leg.chgPct))
            val sub = if (leg.up) "%.2f万股/%%".format(leg.unitVol)
            else {
                val upPrev = legs.getOrNull(leg.no - 1)
                if (upPrev != null && upPrev.up && upPrev.volAvg > 0) "量%.2fx".format(leg.volAvg / upPrev.volAvg)
                else "%.2f万股/%%".format(leg.unitVol)
            }
            pivotLabels[pivots[k].idx] = "$main\n$sub" + if (!pivots[k].confirmed) "…" else ""
        }

        val zigEntries = pivots.map { Entry(it.idx.toFloat(), it.price.toFloat()) }
        val zigDs = LineDataSet(zigEntries, "波段").apply {
            color = Color.parseColor("#37474F"); lineWidth = 1.6f
            setDrawCircles(false)
            setDrawValues(true); valueTextSize = 8f; valueTextColor = DARK
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float, entry: Entry?, dataSetIndex: Int, viewPortHandler: ViewPortHandler?): String {
                    val x = entry?.x?.toInt() ?: return ""
                    return pivotLabels[x] ?: ""
                }
            }
            isHighlightEnabled = false
            mode = LineDataSet.Mode.LINEAR
        }

        // 拐点圆点：按与相邻拐点的距离自适应大小。挨得近的点画小，避免两个圆圈叠成一坨显得特别大。
        val bigSel = ArrayList<Int>(); val smallSel = ArrayList<Int>()
        pivots.forEachIndexed { i, p ->
            val leftGap = if (i > 0) p.idx - pivots[i - 1].idx else 9999
            val rightGap = if (i < pivots.size - 1) pivots[i + 1].idx - p.idx else 9999
            if (minOf(leftGap, rightGap) <= 6) smallSel.add(i) else bigSel.add(i)
        }
        fun dotDs(sel: List<Int>, radiusDp: Float) = LineDataSet(
            sel.map { Entry(pivots[it].idx.toFloat(), pivots[it].price.toFloat()) }, ""
        ).apply {
            color = Color.TRANSPARENT; lineWidth = 0f   // 只用来画圆点，不画连线
            setDrawCircles(true); circleRadius = radiusDp; setDrawCircleHole(false)
            setCircleColors(sel.map { if (!pivots[it].confirmed) GRAY else if (pivots[it].isHigh) GREEN else RED })
            setDrawValues(false); isHighlightEnabled = false
        }
        val dotSets = ArrayList<LineDataSet>()
        if (bigSel.isNotEmpty()) dotSets.add(dotDs(bigSel, 7f))
        if (smallSel.isNotEmpty()) dotSets.add(dotDs(smallSel, 4.2f))

        // VCP 收缩楔形金色轮廓（多锚点：主形态粗线，嵌套/早期形态细线；仅成立/构建中画出）
        val vcps = lastRun?.vcpAll?.filter {
            it.status != com.chin.stockanalysis.strategy.analysis.VcpDetector.Status.INVALID && it.wedge.size >= 2
        } ?: emptyList()
        val vcpDsList = vcps.mapIndexed { i, v ->
            LineDataSet(v.wedge.map { Entry(it.idx.toFloat(), it.price.toFloat()) }, "VCP轮廓$i").apply {
                color = GOLD; lineWidth = if (i == 0) 2.2f else 1.3f
                setDrawCircles(true); circleRadius = if (i == 0) 4f else 3f; setDrawCircleHole(false)
                setCircleColors(v.wedge.map { GOLD })
                setDrawValues(false); isHighlightEnabled = false
                mode = LineDataSet.Mode.LINEAR
            }
        }

        val chart = existing ?: CombinedChart(ctx).apply {
            description.isEnabled = false
            setBackgroundColor(Color.WHITE)
            setTouchEnabled(true); setPinchZoom(true); setDrawGridBackground(false)
            axisRight.isEnabled = false
            xAxis.setDrawLabels(false); xAxis.setDrawGridLines(false)
            axisLeft.setDrawGridLines(true); axisLeft.gridColor = Color.parseColor("#EEEEEE")
            axisLeft.spaceTop = 25f // 给拐点标注留位
            legend.isEnabled = false
            drawOrder = arrayOf(CombinedChart.DrawOrder.CANDLE, CombinedChart.DrawOrder.LINE)
            // Y 轴随可见窗口自适应：回放/缩放时不再被历史最高价压出大片空白
            isAutoScaleMinMaxEnabled = true
        }
        // 数据 + X 轴范围每帧刷新
        chart.xAxis.axisMinimum = -0.5f; chart.xAxis.axisMaximum = n - 0.5f
        val data = CombinedData()
        data.setData(CandleData(cds))
        data.setData(LineData(listOf(zigDs) + dotSets + vcpDsList))
        chart.data = data
        // VCP 压力位虚线（回放每帧重建前先清，避免重复叠加）；主形态青色，其余金色
        chart.axisLeft.removeAllLimitLines()
        vcps.forEachIndexed { i, v ->
            chart.axisLeft.addLimitLine(LimitLine(v.resistance.toFloat(), "VCP压力 %.2f".format(v.resistance)).apply {
                lineColor = if (i == 0) TEAL else GOLD; lineWidth = 1.2f; enableDashedLine(10f, 6f, 0f)
                textSize = 9f; textColor = if (i == 0) TEAL else GOLD
                labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
            })
        }
        // 点击K线/拐点 → 明细小卡片（listener 闭包引用 legs/pivots，每帧必须重建）
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                val idx = e?.x?.toInt() ?: return
                showLegDetail(detailBox, legs, pivots, idx)
                syncCrosshair(0, e.x)
            }
            override fun onNothingSelected() {}
        })
        // 视口偏移放最后 + 每帧重设：MPAndroidChart v3.1.0 的 setViewPortOffsets 是 post(Runnable) 异步生效且不重绘，
        // 首帧会用 0 偏移画出图。改用 setExtraOffsets（同步生效，叠加在 auto-calc 之上）保证首帧就有正确左边距。
        // 左右 extra 压到最小，让绘图区尽量占满手机宽度
        chart.setExtraOffsets(dp(2).toFloat(), dp(8).toFloat(), dp(4).toFloat(), dp(4).toFloat())
        chart.notifyDataSetChanged()
        chart.invalidate()
        priceChart = chart
        return chart
    }

    /** 图② 量价速率：逐K「单位涨幅成交量」(万股/1%) 柱 + 5K 平滑速率线；与主图逐K同位置 */
    private fun renderVolumeChart(existing: CombinedChart?, bars: List<KBar>, pivots: List<Pivot>, legs: List<Leg>): View {
        val ctx = requireContext()
        val n = bars.size
        // 逐K单位量 = 当日成交量(万股) ÷ 当日|涨跌幅%|；涨跌幅<0.05%按0.05计
        val rate = DoubleArray(n)
        for (i in 1 until n) {
            val dpct = abs((bars[i].close - bars[i - 1].close) / bars[i - 1].close * 100.0)
            rate[i] = (bars[i].vol / 10000.0) / maxOf(dpct, 0.05)
        }
        if (n > 1) rate[0] = rate[1]
        // ⚠️ 对数坐标：值域跨 4 个数量级（0.1~2000+），线性轴必然把主体压成一条线，cap 封顶也没用（95分位本身就落在极端区）
        // 显示值 = log10(v)，下限 0.1 防 log(0)；Y 轴标签反变换回原始量级（万/亿缩写）
        fun lg(v: Double) = kotlin.math.log10(maxOf(v, 0.1)).toFloat()
        // ⚠️ 纯 log 仍被压扁：十字星日 ÷0.05% 把 rate 打爆到 ~1e8（log10=8），主体 3~30 只占 log 值域顶部 1/8
        // 解决：log 后按 2%~98% 分位截断（极值日顶到边界即可），并保证最小跨度，主体才能撑满整个图
        val srt = rate.map { lg(it).toDouble() }.sorted()
        var loL = srt[(n * 0.02).toInt().coerceIn(0, n - 1)]
        var hiL = srt[(n * 0.98).toInt().coerceIn(0, n - 1)]
        if (hiL - loL < 1.5) { val mid = (hiL + loL) / 2; loL = mid - 0.75; hiL = mid + 0.75 }
        fun lgc(v: Double) = lg(v).coerceIn(loL.toFloat(), hiL.toFloat())

        val entries = ArrayList<BarEntry>(n)
        val colors = IntArray(n)
        for (i in 0 until n) {
            entries.add(BarEntry(i.toFloat(), lgc(rate[i])))
            colors[i] = when {
                i == 0 -> Color.parseColor("#CCCCCC")
                bars[i].close >= bars[i - 1].close -> Color.parseColor("#EF9A9A")
                else -> Color.parseColor("#A5D6A7")
            }
        }
        val bds = BarDataSet(entries, "单位量 万股/1%(log)").apply {
            setColors(colors.toList())
            setDrawValues(false)
            isHighlightEnabled = false // 柱不出高亮，点选落到速率线上出十字线
        }
        // 单一速率线：近5K 成交量合计 ÷ 近5K 涨跌幅绝对值合计（万股/1%），同样走对数
        // 用区间汇总而不是逐K平均，避免十字星把数值打爆；一条线看整体趋势即可
        val rateEs = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val from = maxOf(0, i - 4)
            var sv = 0.0; var sc = 0.0
            for (j in from..i) {
                sv += bars[j].vol / 10000.0
                if (j > 0) sc += abs((bars[j].close - bars[j - 1].close) / bars[j - 1].close * 100.0)
            }
            rateEs.add(Entry(i.toFloat(), lgc(sv / maxOf(sc, 1.0))))
        }
        val rateDs = LineDataSet(rateEs, "量价速率(5K)").apply {
            color = Color.parseColor("#7B1FA2"); lineWidth = 2f
            setDrawCircles(false); setDrawValues(false)
            setDrawHighlightIndicators(true)
            highLightColor = Color.parseColor("#555555"); setHighlightLineWidth(3f)
            mode = LineDataSet.Mode.LINEAR
        }

        val chart = existing ?: CombinedChart(ctx).apply {
            description.isEnabled = false
            setBackgroundColor(Color.WHITE)
            setTouchEnabled(true); setPinchZoom(true); setDrawGridBackground(false)
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.labelCount = 6   // 速率图在最下面，带日期标签
            axisLeft.setDrawGridLines(true); axisLeft.gridColor = Color.parseColor("#EEEEEE")
            axisLeft.setLabelCount(5, false) // 对数轴 label 反变换后是不均匀量级，label 多了会挤成一团
            // 对数轴：内部值 = log10(万股/1%)，标签反变换回原始量级并缩写 万/亿
            axisLeft.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val v = Math.pow(10.0, value.toDouble())
                    return when {
                        v >= 1e8 -> "%.0f亿".format(v / 1e8)
                        v >= 1e4 -> "%.0f万".format(v / 1e4)
                        v >= 100 -> "%.0f".format(v)
                        else -> "%.1f".format(v)
                    }
                }
            }
            legend.isEnabled = true; legend.textSize = 10f
            drawOrder = arrayOf(CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.LINE)
            isAutoScaleMinMaxEnabled = true
        }
        // 数据 + X 轴范围 + 日期 formatter 每帧刷新（n 会随回放增长）
        chart.xAxis.axisMinimum = -0.5f; chart.xAxis.axisMaximum = n - 0.5f
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt().coerceIn(0, n - 1)
                return xLabel(bars, i)
            }
        }
        val data = CombinedData()
        data.setData(BarData(bds))
        data.setData(LineData(listOf(rateDs)))
        chart.data = data
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                e?.let { syncCrosshair(1, it.x) }
            }
            override fun onNothingSelected() {}
        })
        chart.setExtraOffsets(dp(2).toFloat(), dp(14).toFloat(), dp(4).toFloat(), dp(20).toFloat())
        chart.notifyDataSetChanged()
        chart.invalidate()
        rateChart = chart
        return chart
    }

    /** 图③ OBV 曲线 + 唐奇安通道（20日）+ 波段拐点 */
    private fun renderObvChart(existing: LineChart?, bars: List<KBar>, pivots: List<Pivot>, obv: DoubleArray): View {
        val ctx = requireContext()
        val n = bars.size
        val es = ArrayList<Entry>(n)
        for (i in 0 until n) es.add(Entry(i.toFloat(), (obv[i] / 1e8).toFloat()))
        val ds = LineDataSet(es, "OBV(亿股)").apply {
            color = BLUE; lineWidth = 1.5f
            setDrawCircles(false); setDrawValues(false)
            setDrawHighlightIndicators(true)
            highLightColor = Color.parseColor("#555555"); setHighlightLineWidth(3f)
            mode = LineDataSet.Mode.LINEAR
        }

        // 唐奇安通道：N 日 OBV 最高/最低/中值。OBV 贴上轨=资金持续流入（强势），贴下轨=持续流出
        val N = 20
        val upEs = ArrayList<Entry>(n); val loEs = ArrayList<Entry>(n); val midEs = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val from = maxOf(0, i - N + 1)
            var hi = -Double.MAX_VALUE; var lo = Double.MAX_VALUE
            for (j in from..i) {
                val v = obv[j] / 1e8
                if (v > hi) hi = v
                if (v < lo) lo = v
            }
            upEs.add(Entry(i.toFloat(), hi.toFloat()))
            loEs.add(Entry(i.toFloat(), lo.toFloat()))
            midEs.add(Entry(i.toFloat(), ((hi + lo) / 2.0).toFloat()))
        }
        val upDs = LineDataSet(upEs, "上轨${N}").apply {
            color = Color.parseColor("#EF9A9A"); lineWidth = 1f
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            mode = LineDataSet.Mode.LINEAR
        }
        val loDs = LineDataSet(loEs, "下轨${N}").apply {
            color = Color.parseColor("#A5D6A7"); lineWidth = 1f
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            mode = LineDataSet.Mode.LINEAR
        }
        val midDs = LineDataSet(midEs, "中位线${N}").apply {
            color = Color.parseColor("#616161"); lineWidth = 1.2f
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            mode = LineDataSet.Mode.LINEAR
        }

        val pivEs = pivots.map { Entry(it.idx.toFloat(), (obv[it.idx] / 1e8).toFloat()) }
        val pivDs = LineDataSet(pivEs, "拐点").apply {
            color = Color.TRANSPARENT; setDrawCircles(true); circleRadius = 3.2f; setDrawCircleHole(false)
            setCircleColors(pivots.map { if (!it.confirmed) GRAY else if (it.isHigh) GREEN else RED })
            setDrawValues(false); isHighlightEnabled = false
        }
        val chart = existing ?: LineChart(ctx).apply {
            description.isEnabled = false
            setBackgroundColor(Color.WHITE)
            setTouchEnabled(true); setPinchZoom(true); setDrawGridBackground(false)
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.setDrawLabels(false)   // 日期标签挪到最底下的 SSA cycle 图
            xAxis.labelCount = 6
            axisLeft.setDrawGridLines(true); axisLeft.gridColor = Color.parseColor("#EEEEEE")
            legend.isEnabled = false
            isAutoScaleMinMaxEnabled = true
        }
        chart.xAxis.axisMinimum = -0.5f; chart.xAxis.axisMaximum = n - 0.5f
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt().coerceIn(0, n - 1)
                return xLabel(bars, i)
            }
        }
        chart.data = LineData(listOf(upDs, loDs, midDs, ds, pivDs))
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                e?.let { syncCrosshair(2, it.x) }
            }
            override fun onNothingSelected() {}
        })
        chart.setExtraOffsets(dp(2).toFloat(), dp(6).toFloat(), dp(4).toFloat(), dp(4).toFloat())
        chart.notifyDataSetChanged()
        chart.invalidate()
        obvChart = chart
        return chart
    }

    /** 图③ SSA cycle 分量 + 速度（一阶差分）+ 加速度（二阶差分）：三线各自归一化，末端 L~2L 根半透明=边缘效应区 */
    private fun renderSsaChart(existing: LineChart?, bars: List<KBar>, cycle: DoubleArray?): View {
        val ctx = requireContext()
        val n = bars.size
        if (cycle == null || cycle.size != n) {
            ssaChart = null
            return TextView(ctx).apply {
                text = "SSA cycle：K线数不足（需 ≥ L+20 根）或计算失败，请加大区间或减小 L"
                textSize = 11f; setTextColor(GRAY); setPadding(dp(6), dp(10), dp(6), dp(10))
            }
        }
        // 第一个非 NaN（L 根暖机段）
        var first = 0
        while (first < n && cycle[first].isNaN()) first++
        if (n - first < 10) {
            ssaChart = null
            return TextView(ctx).apply {
                text = "SSA cycle：有效段太短（暖机后不足 10 根），请加大区间或减小 L"
                textSize = 11f; setTextColor(GRAY); setPadding(dp(6), dp(10), dp(6), dp(10))
            }
        }
        val L = cyclePeriod
        // 速度 = cycle 一阶差分（领先 cycle 峰约 T/4）；加速度 = 二阶差分（再领先 T/4）
        val vel = DoubleArray(n) { Double.NaN }
        val acc = DoubleArray(n) { Double.NaN }
        for (i in first + 1 until n) vel[i] = cycle[i] - cycle[i - 1]
        for (i in first + 2 until n) acc[i] = vel[i] - vel[i - 1]
        // 归一化分母：主段（剔除两端边缘效应区）的最大绝对值，三线同图可比、零轴共享
        fun normOf(a: DoubleArray, from: Int, tailN: Int): Double {
            val end = (n - tailN).coerceAtLeast(from + 5)
            var m = 0.0
            for (i in from until end) { val v = abs(a[i]); if (!v.isNaN() && v > m) m = v }
            return if (m > 1e-12) m else 1.0
        }
        val sets = ArrayList<LineDataSet>()
        sets.addAll(twoSegSets(TEAL, cycle, first, L, normOf(cycle, first, L), 1.8f, null))
        sets.addAll(twoSegSets(Color.parseColor("#F57C00"), vel, first + 1, L, normOf(vel, first + 1, L), 1.4f, floatArrayOf(10f, 6f)))
        sets.addAll(twoSegSets(Color.parseColor("#8E24AA"), acc, first + 2, 2 * L, normOf(acc, first + 2, 2 * L), 1.2f, floatArrayOf(3f, 5f)))

        val chart = existing ?: LineChart(ctx).apply {
            description.isEnabled = false
            setBackgroundColor(Color.WHITE)
            setTouchEnabled(true); setPinchZoom(true); setDrawGridBackground(false)
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.setDrawLabels(false)   // 日期标签在最底下的速率图上
            xAxis.labelCount = 6
            axisLeft.setDrawGridLines(true); axisLeft.gridColor = Color.parseColor("#EEEEEE")
            // 零轴参考线：上穿=走强，下穿=走弱
            axisLeft.addLimitLine(LimitLine(0f).apply {
                lineColor = Color.parseColor("#9E9E9E"); lineWidth = 1f
            })
            legend.isEnabled = false   // 颜色说明写在图下 hint③
        }
        chart.xAxis.axisMinimum = -0.5f; chart.xAxis.axisMaximum = n - 0.5f
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt().coerceIn(0, n - 1)
                return xLabel(bars, i)
            }
        }
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                e?.let { syncCrosshair(3, it.x) }
            }
            override fun onNothingSelected() {}
        })
        chart.setExtraOffsets(dp(2).toFloat(), dp(6).toFloat(), dp(4).toFloat(), dp(4).toFloat())
        chart.notifyDataSetChanged()
        chart.invalidate()
        ssaChart = chart
        return chart
    }

    /** 一条线拆成「主体实色 + 末端 tailN 根半透明（边缘效应区提示）」两个 DataSet；vals 前段 NaN 跳过，from=首个有效下标 */
    private fun twoSegSets(baseColor: Int, vals: DoubleArray, from: Int, tailN: Int,
                           norm: Double, lw: Float, dash: FloatArray?): List<LineDataSet> {
        val n = vals.size
        val split = (n - tailN).coerceIn(from + 1, n)   // 主体 [from, split)，尾段 [split-1, n)（重叠一根保持连续）
        fun mk(lo: Int, hi: Int, c: Int): LineDataSet? {
            if (hi - lo < 2) return null
            val es = ArrayList<Entry>(hi - lo)
            for (i in lo until hi) {
                val v = vals[i]
                if (!v.isNaN()) es.add(Entry(i.toFloat(), (v / norm).toFloat()))
            }
            if (es.size < 2) return null
            return LineDataSet(es, "").apply {
                this.color = c
                this.lineWidth = lw
                setDrawCircles(false); setDrawValues(false)
                setDrawHighlightIndicators(true)
                highLightColor = Color.parseColor("#555555"); setHighlightLineWidth(3f)
                mode = LineDataSet.Mode.LINEAR
                if (dash != null) enableDashedLine(dash[0], dash[1], 0f)
            }
        }
        val tailColor = (baseColor and 0x00FFFFFF) or 0x55000000
        return listOfNotNull(mk(from, split, baseColor), mk((split - 1).coerceAtLeast(from), n, tailColor))
    }

    /** 点击主图点位 → 在图下方小卡片显示该段量价明细 */
    private fun showLegDetail(box: LinearLayout, legs: List<Leg>, pivots: List<Pivot>, barIdx: Int) {
        val ctx = requireContext()
        val leg = legs.firstOrNull { barIdx in (it.fromIdx + 1)..it.toIdx }
            ?: legs.firstOrNull { it.fromIdx == barIdx }
            ?: legs.minByOrNull { minOf(abs(barIdx - it.fromIdx), abs(barIdx - it.toIdx)) }
            ?: return
        box.removeAllViews()
        val dirColor = if (leg.up) RED else GREEN
        box.addView(TextView(ctx).apply {
            text = "📍 段${cn(leg.no)} ${if (leg.up) "↑ 上升" else "↓ 回落"}　" +
                "${leg.fromDate.substring(5)} %.2f → ${leg.toDate.substring(5)} %.2f".format(leg.fromPrice, leg.toPrice) +
                if (!leg.endConfirmed) "（进行中）" else ""
            textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(dirColor)
        })
        box.addView(TextView(ctx).apply {
            val unit = if (hourlyMode) "根" else "天"
            text = "幅度 %s%.1f%%｜%d $unit｜总量 %.0f万股｜均量 %.1f万股｜单位涨幅 %.2f万股/1%%"
                .format(if (leg.up) "+" else "－", abs(leg.chgPct), leg.bars, leg.volSum, leg.volAvg, leg.unitVol)
            textSize = 11f; setTextColor(DARK)
        })
        box.addView(TextView(ctx).apply {
            text = "OBV 段内变化：%s%.0f 万股".format(if (leg.obvDelta >= 0) "+" else "", leg.obvDelta)
            textSize = 11f; setTextColor(DARK)
        })
        if (leg.up) {
            val prevHigh = pivots.filter { p -> p.isHigh && p.idx < leg.fromIdx }.lastOrNull()
            if (prevHigh != null && leg.toPrice > prevHigh.price * 1.001) {
                box.addView(TextView(ctx).apply {
                    text = "🔺 突破前高 %.2f → 另起新段".format(prevHigh.price)
                    textSize = 11f; setTextColor(RED)
                })
            }
            val prev = legs.lastOrNull { l -> l.up && l.no < leg.no }
            if (prev != null && prev.unitVol > 0) {
                val r = leg.unitVol / prev.unitVol
                val verdict = when {
                    r < 0.8 -> "推动更省量，同区域复测更容易过"
                    r > 1.25 -> "推动更费量，上方分歧加大"
                    else -> "与上段持平"
                }
                box.addView(TextView(ctx).apply {
                    text = "🔁 vs 段${cn(prev.no)}（上一上升）：单位量 %.2f → %.2f（%.2fx）→ $verdict"
                        .format(prev.unitVol, leg.unitVol, r)
                    textSize = 11f; setTextColor(BLUE)
                })
            }
        } else {
            box.addView(TextView(ctx).apply {
                text = "🧱 接受度：" + acceptanceText(legs, leg)
                textSize = 11f; setTextColor(BLUE)
            })
        }
    }

    // ═══════════════════════ 数据加载 ═══════════════════════

    private suspend fun resolveCode(raw: String): Pair<String?, String?> {
        val normalized = StockAnalysisAgent.normalizeStockCode(raw)
        val db = StockDatabase.getInstance(requireContext())
        if (normalized.startsWith("sh") || normalized.startsWith("sz") || normalized.startsWith("bj")) {
            val nm = runCatching { db.stockBasicDao().getByCode(normalized)?.name }.getOrNull()
            return normalized to (nm?.ifBlank { normalized })
        }
        val hits = runCatching { db.stockBasicDao().searchByName(raw) }.getOrDefault(emptyList())
        val pick = hits.firstOrNull { it.name == raw }
            ?: hits.firstOrNull { it.name.contains(raw) || raw.contains(it.name) }
            ?: hits.firstOrNull()
        return pick?.code to pick?.name
    }

    private suspend fun loadBars(code: String): List<KBar>? =
        if (hourlyMode) loadHourlyBars(code) else loadDailyBars(code)

    /** 1H：首次全量 1023 根落盘，之后只拉最近 80 根增量（IntradayKlineFetcher 内部已做磁盘缓存+合并） */
    private suspend fun loadHourlyBars(code: String): List<KBar>? {
        val full = if (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj")) code
        else (if (code[0] in "659") "sh" else "sz") + code
        val ents = runCatching {
            com.chin.stockanalysis.strategy.data.IntradayKlineFetcher(requireContext())
                .fetchHourlyHistory(full)
        }.getOrDefault(emptyList())
        val bars = ents.filter { it.close > 0 && it.high > 0 && it.low > 0 }
            .sortedBy { it.datetime }
            .map { KBar(it.datetime, it.open, it.high, it.low, it.close, it.volume) }
        return if (bars.size < 60) null else bars
    }

    /** 日线：本地库优先，首次不足拉 850 根；已有历史时只在最新日期落后 2 天以上时补最近 60 根 */
    private suspend fun loadDailyBars(code: String): List<KBar>? {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        fun conv(list: List<DailySnapshotEntity>) = list
            .filter { it.close > 0 && it.high > 0 && it.low > 0 }
            .sortedBy { it.date }
            .map { KBar(it.date, if (it.open == 0.0) it.close else it.open, it.high, it.low, it.close, it.volume) }
        var local = runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
        val staleBefore = java.time.LocalDate.now().minusDays(2).toString()
        val needFull = local.size < 400
        val needIncrement = local.isNotEmpty() && (local.maxOf { it.date }) < staleBefore
        if (needFull || needIncrement) {
            runCatching { HistoricalDataFetcher(ctx).fetchStockLatest(code, if (needFull) 850 else 60) }
            local = runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
        }
        val bars = conv(local)
        return if (bars.size < 60) null else bars
    }

    // ═══════════════════════ UI 工具 ═══════════════════════

    private fun cn(no: Int): String = arrayOf("①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩")
        .getOrElse(no) { "(${no + 1})" }

    private fun status(s: String) { if (isAdded) statusTv.text = s }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun roundedBg(fill: String, stroke: String, r: Int) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(Color.parseColor(fill)); setStroke(dp(1), Color.parseColor(stroke))
    }
    private fun title(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#222222")); setPadding(0, dp(4), 0, dp(2))
    }
    private fun hint(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 10f; setTextColor(GRAY); setPadding(0, dp(2), 0, dp(4))
    }
    private fun cardTitle(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#444444")); setPadding(0, 0, 0, dp(4))
    }
    private fun card(build: (LinearLayout) -> Unit): View {
        val ctx = requireContext()
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        build(inner)
        return LinearLayout(ctx).apply {
            background = roundedBg("#FFFFFF", "#E0E0E0", 8)
            setPadding(0, 0, 0, 0)
            addView(inner)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }
    /** 窄边距卡片：左右 padding 仅 2dp，给图表卡片用，让图尽量占满手机宽度 */
    private fun cardSlim(build: (LinearLayout) -> Unit): View {
        val ctx = requireContext()
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        build(inner)
        return LinearLayout(ctx).apply {
            background = roundedBg("#FFFFFF", "#E0E0E0", 8)
            addView(inner)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }
    private fun chipScroll(items: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit): HorizontalScrollView {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tvs = ArrayList<TextView>()
        items.forEachIndexed { idx, (key, label) ->
            val tv = TextView(ctx).apply {
                text = label; textSize = 11f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    tvs.forEachIndexed { j, o ->
                        val on = j == idx
                        o.background = roundedBg(if (on) "#E0F2F1" else "#F2F2F2", "#DDDDDD", 14)
                        o.setTextColor(if (on) TEAL else Color.parseColor("#666666"))
                    }
                    onSelect(key)
                }
                background = roundedBg(if (key == selected) "#E0F2F1" else "#F2F2F2", "#DDDDDD", 14)
                setTextColor(if (key == selected) TEAL else Color.parseColor("#666666"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            tvs.add(tv); row.addView(tv)
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; addView(row)
        }
    }
}
