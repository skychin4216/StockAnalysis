package com.chin.stockanalysis.strategy.trade

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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.StockPoolAggregator
import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine
import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine.Eval
import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine.Outcome
import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine.Plot
import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine.Sig
import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine.TechBar
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 量化选股 →「技术指标」Tab（与 短线/中线/长线 平级）
 *
 * 6 个技术信号（MACD6139 / WR / RSI / OBV / SKDJ / BIAS-QL）各自可开关，
 * 每个买入确认计 20 分；扫描本地股票池，结果含每信号评分；点击股票查看 K线 + 6 个信号副图。
 */
class TechIndicatorQuantFragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val GREEN = Color.parseColor("#43A047")
    private val ORANGE = Color.parseColor("#E65100")
    private val BLUE = Color.parseColor("#1976D2")
    private val GRAY = Color.parseColor("#999999")

    private data class StockResult(
        val code: String,
        val name: String,
        val bars: List<TechBar>,   // 绘图用（最近60根≈3个月，升序）
        val outcome: Outcome,
        val close: Double,
        val changePct: Double
    )

    private lateinit var statusTv: TextView
    private lateinit var bodyBox: LinearLayout
    private lateinit var scanBtn: Button
    private val sigChecks = LinkedHashMap<Sig, CheckBox>()
    private var lastResults: List<StockResult> = emptyList()
    private var scanSerial = 0

    private val signalColors = intArrayOf(
        Color.parseColor("#E65100"), Color.parseColor("#1976D2"),
        Color.parseColor("#9C27B0"), Color.parseColor("#00897B"),
        Color.parseColor("#6A1B9A"), Color.parseColor("#5D4037")
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
        }

        // ── 固定配置区 ──
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8)
            )
            addView(TextView(ctx).apply {
                text = "📡 技术指标多信号选股"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#222222"))
            })
            addView(TextView(ctx).apply {
                text = "勾选参与的信号，每个买入确认计 20 分；扫描本地股票池，按总分排序，点击查看信号副图。"
                textSize = 10f
                setTextColor(GRAY)
                setPadding(0, dp(2), 0, dp(4))
            })
            // 6 个信号勾选项（两列）
            val sigs = Sig.values()
            for (rowIdx in sigs.indices step 2) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in 0 until 2) {
                    val idx = rowIdx + c
                    val cell = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    if (idx < sigs.size) {
                        val sig = sigs[idx]
                        val cb = CheckBox(ctx).apply {
                            text = sig.label
                            textSize = 12f
                            isChecked = true
                            setTextColor(Color.parseColor("#444444"))
                        }
                        sigChecks[sig] = cb
                        cell.addView(cb)
                    }
                    row.addView(cell)
                }
                addView(row)
            }
            // 操作行
            val op = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            op.addView(TextView(ctx).apply {
                text = "全选"; textSize = 11f; setTextColor(BLUE)
                setPadding(dp(4), dp(4), dp(12), dp(4))
                isClickable = true
                setOnClickListener { sigChecks.values.forEach { it.isChecked = true } }
            })
            op.addView(TextView(ctx).apply {
                text = "全不选"; textSize = 11f; setTextColor(GRAY)
                setPadding(0, dp(4), dp(12), dp(4))
                isClickable = true
                setOnClickListener { sigChecks.values.forEach { it.isChecked = false } }
            })
            scanBtn = Button(ctx).apply {
                text = "🔍 开始扫描"
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(ORANGE)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                setOnClickListener { startScan() }
            }
            op.addView(scanBtn)
            addView(op)
            statusTv = TextView(ctx).apply {
                textSize = 10f; setTextColor(GRAY)
                setPadding(dp(2), dp(4), dp(2), 0)
            }
            addView(statusTv)
        })

        // ── 结果滚动区 ──
        val scroll = ScrollView(ctx)
        bodyBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
        }
        scroll.addView(bodyBox)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        renderIdleHint()
        return root
    }

    /** 工作台切回本页时回调（结果已在内存，无需重扫） */
    fun refresh() { /* no-op */ }

    private fun status(s: String) {
        if (!isAdded) return
        activity?.runOnUiThread { if (isAdded && ::statusTv.isInitialized) statusTv.text = s }
    }

    private fun renderIdleHint() {
        bodyBox.removeAllViews()
        bodyBox.addView(TextView(requireContext()).apply {
            text = "勾选上方信号后点击「开始扫描」。\n数据来自本地日K（建议先在 PC/守护同步行情）。"
            textSize = 11f
            setTextColor(GRAY)
            setPadding(0, dp(12), 0, 0)
        })
    }

    // ═══════════════════════ 扫描 ═══════════════════════

    private fun startScan() {
        val enabled = sigChecks.filterValues { it.isChecked }.keys
        if (enabled.isEmpty()) {
            Toast.makeText(requireContext(), "请至少勾选一个信号", Toast.LENGTH_SHORT).show(); return
        }
        val serial = ++scanSerial
        scanBtn.isEnabled = false
        bodyBox.removeAllViews()
        lifecycleScope.launch {
            val ctx = requireContext()
            try {
                status("📡 正在整合股票池…")
                val pool = withContext(Dispatchers.IO) { StockPoolAggregator.collect(ctx) }
                if (pool.codes.isEmpty()) {
                    status("⚠️ 各股票池均为空"); renderIdleHint(); return@launch
                }
                val db = StockDatabase.getInstance(ctx)
                val nameMap = HashMap<String, String>()
                runCatching { db.stockBasicDao().getByCodes(pool.codes).forEach { if (it.name.isNotBlank()) nameMap[it.code] = it.name } }
                runCatching {
                    val missing = pool.codes.filter { nameMap[it].isNullOrBlank() }
                    if (missing.isNotEmpty()) db.dailySnapshotDao().getLastNames(missing)
                        .forEach { if (nameMap[it.code].isNullOrBlank() && it.name.isNotBlank()) nameMap[it.code] = it.name }
                }
                val targets = pool.codes.filterNot { isRiskName(nameMap[it] ?: "") }
                status("📡 池内 ${targets.size} 只，开始计算信号…")

                val results = ArrayList<StockResult>()
                var scanned = 0; var skipped = 0
                for (code in targets) {
                    if (serial != scanSerial) return@launch
                    val nm = nameMap[code] ?: code
                    scanned++
                    if (scanned % 20 == 0) status("⏳ $scanned/${targets.size} · 命中 ${results.size}")
                    val candles = withContext(Dispatchers.IO) {
                        runCatching { db.dailySnapshotDao().getByCode(code, 250) }.getOrDefault(emptyList())
                    }
                    if (candles.size < 60) { skipped++; continue }
                    val asc = candles.sortedBy { it.date }
                    val bars = asc.map { TechBar(it.date, it.open.ifZero(it.close), it.high, it.low, it.close, it.volume.toDouble()) }
                    val outcome = withContext(Dispatchers.Default) {
                        runCatching { TechSignalEngine.evaluate(bars, enabled) }.getOrNull()
                    } ?: continue
                    if (outcome.total <= 0) continue
                    val last = asc.last(); val prev = asc.getOrNull(asc.size - 2)
                    val chg = if (last.changePct != 0.0) last.changePct
                    else if (prev != null && prev.close > 0) (last.close - prev.close) / prev.close * 100 else 0.0
                    val r = StockResult(code, nm, bars.takeLast(60), outcome, last.close, chg)
                    results.add(r)
                    // 实时插入（按分排序位）
                    withContext(Dispatchers.Main) {
                        if (serial != scanSerial || !isAdded) return@withContext
                        insertResultRow(r)
                    }
                }
                if (serial != scanSerial) return@launch
                val sorted = results.sortedWith(
                    compareByDescending<StockResult> { it.outcome.total }
                        .thenByDescending { it.outcome.hitCount }
                        .thenBy { it.code }
                )
                // 名额规则：默认取评分前 10；第 11 名起若为满分则继续纳入，最多 20
                val fullMark = enabled.size * TechSignalEngine.SCORE_PER_SIGNAL
                val capped = if (sorted.size <= 10) sorted
                else sorted.take(10) + sorted.drop(10).filter { it.outcome.total >= fullMark }.take(10)
                lastResults = capped
                rerenderResultRows()
                val fullNote = if (capped.size > 10) "（含满分追加至 ${capped.size} 只，上限20）" else ""
                status("✅ 扫描 ${targets.size} 只（K线不足跳过 $skipped）→ 命中 ${results.size} 只，按分取前 ${capped.size} 只$fullNote")
            } catch (e: Exception) {
                status("⚠️ 扫描失败：${e.message}")
            } finally {
                if (serial == scanSerial) scanBtn.isEnabled = true
            }
        }
    }

    private fun isRiskName(name: String) =
        name.contains("ST", ignoreCase = true) || name.contains("退")

    private fun Double.ifZero(f: Double) = if (this == 0.0) f else this

    // ═══════════════════════ 结果列表 ═══════════════════════

    private fun insertResultRow(r: StockResult) {
        // 简化：逐条追加到末尾，扫描结束后统一按分重排（rerenderResultRows）
        bodyBox.addView(buildResultRow(r))
    }

    private fun rerenderResultRows() {
        bodyBox.removeAllViews()
        bodyBox.addView(summaryHeader())
        lastResults.forEach { bodyBox.addView(buildResultRow(it)) }
    }

    private fun summaryHeader(): TextView = TextView(requireContext()).apply {
        val en = lastResults.firstOrNull()?.outcome?.enabledCount ?: 0
        text = "入选 ${lastResults.size} 只（前10；第11名起满分可追加，最多20）· 满分 ${en * 20}（$en 个信号×20），按总分降序"
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ORANGE)
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun buildResultRow(r: StockResult): View {
        val ctx = requireContext()
        val o = r.outcome
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#EEEEEE"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            // 顶部行：名称代码 + 总分 + 涨跌
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(ctx).apply {
                text = "${r.name} ${r.code.takeLast(6)}"
                textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#222222"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(ctx).apply {
                text = "${o.total}分"
                textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(RED)
                setPadding(dp(6), 0, dp(6), 0)
            })
            top.addView(TextView(ctx).apply {
                text = "${if (r.changePct >= 0) "+" else ""}${"%.2f".format(r.changePct)}%"
                textSize = 12f
                setTextColor(if (r.changePct >= 0) RED else GREEN)
            })
            addView(top)
            // 每信号评分 chips
            val chips = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            o.evals.forEach { ev ->
                chips.addView(TextView(ctx).apply {
                    val enTxt = if (ev.enabled) "${shortName(ev.sig)} ${ev.score}" else "${shortName(ev.sig)} ·"
                    text = enTxt
                    textSize = 9f
                    val (bg, fg) = when {
                        !ev.enabled -> Color.parseColor("#F5F5F5") to Color.parseColor("#BBBBBB")
                        ev.bullish -> Color.parseColor("#FDECEA") to RED
                        else -> Color.parseColor("#F0F0F0") to Color.parseColor("#888888")
                    }
                    setBackgroundColor(bg)
                    setTextColor(fg)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(5) }
                })
            }
            addView(chips)
            setOnClickListener { showDetail(r) }
        }
    }

    private fun shortName(s: Sig) = when (s) {
        Sig.MACD -> "MACD"; Sig.WR -> "WR"; Sig.RSI -> "RSI"
        Sig.OBV -> "OBV"; Sig.SKDJ -> "SKDJ"; Sig.BIAS -> "BIAS"
    }

    // ═══════════════════════ 详情（K线 + 6 信号副图） ═══════════════════════

    private fun showDetail(r: StockResult) {
        val ctx = requireContext()
        bodyBox.removeAllViews()
        val o = r.outcome

        // 返回按钮
        bodyBox.addView(TextView(ctx).apply {
            text = "← 返回扫描结果"
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            setBackgroundColor(BLUE); gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { rerenderResultRows(); scrollTop() }
        })
        // 标题 + 总分
        bodyBox.addView(card { c ->
            c.addView(TextView(ctx).apply {
                text = "${r.name} ${r.code.takeLast(6)}"
                textSize = 16f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#222222"))
            })
            c.addView(TextView(ctx).apply {
                text = "技术指标总分 ${o.total} / ${o.enabledCount * 20} · 命中 ${o.hitCount}/${o.enabledCount} 信号" +
                    " · ${r.bars.first().date} ~ ${r.bars.last().date}"
                textSize = 11f; setTextColor(GRAY); setPadding(0, dp(2), 0, dp(6))
            })
            // 每信号评分 + 状态
            o.evals.forEach { ev -> c.addView(evalLine(ev)) }
        })
        // K线
        bodyBox.addView(card { c ->
            c.addView(cardTitle("📈 K线（最近 ${r.bars.size} 根）"))
            c.addView(buildCandle(r.bars, 220))
        })
        // 6 个信号副图（按引擎顺序，含未勾选的也展示以便观察；评分仅对勾选生效）
        for (sig in Sig.values()) {
            val plot = o.plots[sig] ?: continue
            val ev = o.evals.first { it.sig == sig }
            bodyBox.addView(card { c ->
                c.addView(cardTitle(chartTitle(sig, ev)))
                c.addView(buildPlot(r.bars, plot, sig == Sig.MACD || plot.bars != null, 150))
            })
        }
        scrollTop()
    }

    private fun chartTitle(sig: Sig, ev: Eval): String {
        val scoreTxt = if (ev.enabled) "${ev.score}分" else "未启用"
        val flag = if (ev.enabled && ev.bullish) "🔴 买入确认" else if (!ev.enabled) "⚪" else "⚫"
        return "$flag ${sig.label} · $scoreTxt · ${ev.state}"
    }

    private fun evalLine(ev: Eval): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(ctx).apply {
                text = if (ev.enabled && ev.bullish) "🔴" else if (ev.enabled) "⚫" else "⚪"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(ctx).apply {
                text = ev.sig.label
                textSize = 12f; setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(ctx).apply {
                text = if (ev.enabled) "${ev.score} 分" else "—"
                textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (ev.enabled && ev.bullish) RED else Color.parseColor("#666666"))
                layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(ctx).apply {
                text = ev.state
                textSize = 11f; setTextColor(GRAY)
            })
        }
    }

    private fun scrollTop() {
        var v: View? = bodyBox.parent as? View
        while (v != null && v !is ScrollView) v = v.parent as? View
        (v as? ScrollView)?.post { v.fullScroll(View.FOCUS_UP) }
    }

    // ═══════════════════════ 图表 ═══════════════════════

    private fun buildCandle(bars: List<TechBar>, h: Int): CombinedChart {
        val chart = CombinedChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat())
        chart.moveViewToX((bars.size - 1).toFloat())
        val es = ArrayList<CandleEntry>(bars.size)
        bars.forEachIndexed { i, b -> es.add(CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat())) }
        val ds = CandleDataSet(es, "").apply {
            shadowColor = Color.parseColor("#999999"); shadowWidth = 1f
            increasingPaintStyle = Paint.Style.FILL; decreasingPaintStyle = Paint.Style.FILL
            increasingColor = RED; decreasingColor = GREEN
            isHighlightEnabled = false; setDrawValues(false)
        }
        chart.data = CombinedData().apply { setData(CandleData(ds)) }
        styleX(chart, bars)
        chart.axisLeft.textSize = 8f; chart.axisLeft.textColor = GRAY
        chart.axisLeft.gridColor = Color.parseColor("#EEEEEE")
        chart.axisRight.isEnabled = false
        chart.post { chart.invalidate() }
        return chart
    }

    /** 通用信号副图：bars!=null 用柱+线组合（MACD），否则纯多线；带参考线/买卖箭头 */
    private fun buildPlot(bars: List<TechBar>, plot: Plot, combined: Boolean, h: Int): View {
        val n = bars.size
        // 全量值的上下界，用于放置买卖箭头
        var yMin = Double.MAX_VALUE; var yMax = -Double.MAX_VALUE
        fun consider(v: Double) { if (!v.isNaN()) { if (v < yMin) yMin = v; if (v > yMax) yMax = v } }
        plot.lines.forEach { ln -> ln.second.forEach { consider(it) } }
        plot.bars?.forEach { consider(it) }
        if (yMin == Double.MAX_VALUE) { yMin = 0.0; yMax = 100.0 }
        val pad = (yMax - yMin).coerceAtLeast(1.0) * 0.06

        val dates = bars.map { it.date }
        fun xAxisFormatter() = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt(); return if (i in dates.indices) dates[i].takeLast(5) else ""
            }
        }

        val lineData = LineData()
        plot.lines.forEachIndexed { li, (name, arr) ->
            val es = ArrayList<Entry>()
            arr.forEachIndexed { i, v -> if (!v.isNaN()) es.add(Entry(i.toFloat(), v.toFloat())) }
            lineData.addDataSet(LineDataSet(es, name).apply {
                color = signalColors[li % signalColors.size]; lineWidth = 1.2f
                setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            })
        }
        // 买卖箭头
        lineData.addDataSet(arrowSet(plot.buys.map { Entry(it.toFloat(), (yMin - pad).toFloat()) }, true, RED))
        lineData.addDataSet(arrowSet(plot.sells.map { Entry(it.toFloat(), (yMax + pad).toFloat()) }, false, GREEN))

        val refs = plot.refs

        if (combined) {
            val chart = CombinedChart(requireContext())
            chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
            chart.setBackgroundColor(Color.WHITE)
            chart.description.isEnabled = false
            chart.legend.textSize = 8f; chart.legend.textColor = GRAY
            chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
            chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
            chart.setVisibleXRangeMaximum((n + 2).toFloat())
            chart.moveViewToX((n - 1).toFloat())
            chart.drawOrder = arrayOf(CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.LINE)
            if (plot.bars != null) {
                val be = ArrayList<BarEntry>(); val cs = ArrayList<Int>()
                plot.bars.forEachIndexed { i, v ->
                    if (!v.isNaN()) {
                        be.add(BarEntry(i.toFloat(), v.toFloat()))
                        cs.add(if (v >= 0) RED else GREEN)
                    }
                }
                val bds = BarDataSet(be, "MACD柱").apply { setColors(cs); setDrawValues(false); isHighlightEnabled = false }
                val bd = BarData(bds).apply { barWidth = 0.6f }
                val cd = CombinedData()
                cd.setData(bd); cd.setData(lineData)
                chart.data = cd
            } else {
                chart.data = CombinedData().apply { setData(lineData) }
            }
            styleX(chart, bars)
            applyAxis(chart.axisLeft, refs, plot.inverted)
            chart.axisRight.isEnabled = false
            chart.post { chart.invalidate() }
            return chart
        } else {
            val chart = LineChart(requireContext())
            chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
            chart.setBackgroundColor(Color.WHITE)
            chart.description.isEnabled = false
            chart.legend.textSize = 8f; chart.legend.textColor = GRAY
            chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
            chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
            chart.setVisibleXRangeMaximum((n + 2).toFloat())
            chart.moveViewToX((n - 1).toFloat())
            chart.data = lineData
            styleX(chart, bars)
            applyAxis(chart.axisLeft, refs, plot.inverted)
            chart.axisRight.isEnabled = false
            chart.post { chart.invalidate() }
            return chart
        }
    }

    private fun arrowSet(es: List<Entry>, up: Boolean, color: Int): LineDataSet =
        LineDataSet(es, if (up) "买" else "卖").apply {
            this.color = Color.TRANSPARENT
            setDrawCircles(false); setDrawValues(true)
            valueTextSize = 11f; valueTextColor = color; isHighlightEnabled = false
            valueFormatter = object : ValueFormatter() {
                override fun getPointLabel(entry: Entry?): String = if (up) "▲" else "▼"
            }
        }

    private fun styleX(chart: com.github.mikephil.charting.charts.BarLineChartBase<*>, bars: List<TechBar>) {
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (bars.size / 5).coerceAtLeast(1).toFloat()
            labelCount = 5; textSize = 8f; textColor = GRAY
            setDrawGridLines(false); setAvoidFirstLastClipping(true)
            val dates = bars.map { it.date }
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val i = value.toInt(); return if (i in dates.indices) dates[i].takeLast(5) else ""
                }
            }
        }
    }

    private fun applyAxis(axis: com.github.mikephil.charting.components.YAxis, refs: List<Pair<Double, String>>, inverted: Boolean) {
        axis.textSize = 8f; axis.textColor = GRAY
        axis.gridColor = Color.parseColor("#EEEEEE")
        axis.removeAllLimitLines()
        refs.forEach { (v, lbl) ->
            axis.addLimitLine(LimitLine(v.toFloat(), lbl).apply {
                lineColor = Color.parseColor("#CCCCCC"); lineWidth = 1f
                textSize = 7f; textColor = GRAY
            })
        }
        if (inverted) { axis.axisMinimum = 0f; axis.axisMaximum = 100f; axis.isInverted = true }
    }

    // ═══════════════════════ 通用 UI ═══════════════════════

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun cardTitle(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#333333")); setPadding(0, 0, 0, dp(6))
    }

    private fun card(build: (LinearLayout) -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.parseColor("#E8E8E8"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        build(this)
    }
}
