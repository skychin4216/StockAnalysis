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
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.data.IntradayKlineFetcher
import com.chin.stockanalysis.strategy.ssa.SsaEngine
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Layer
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.LayerCfg
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Params
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Result
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.SegStats
import com.chin.stockanalysis.stock.database.StockDatabase
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
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
 * ## 三层 SSA 稳态做T（周线→日线→1H）
 *
 * 固定长度滚动窗口 SSA；1H 连续2根 cycle 拐点做正T/反T；周线 C、日线 B/C 层级风控；
 * 价格越日线蓝轨硬止损；含佣金/印花税/滑点；70% 训练 / 30% 样本外。
 */
class SsaT0Fragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val GREEN = Color.parseColor("#43A047")
    private val ORANGE = Color.parseColor("#E65100")
    private val BLUE = Color.parseColor("#1976D2")
    private val PURPLE = Color.parseColor("#9C27B0")
    private val TEAL = Color.parseColor("#00897B")
    private val GRAY = Color.parseColor("#999999")
    private val A_C = Color.parseColor("#90A4AE")

    private lateinit var codeInput: EditText
    private lateinit var runBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var resultBox: LinearLayout
    private val cfgInputs = ArrayList<EditText>()   // 顺序：周L M N 日L M N 时L M N
    private lateinit var capUpEt: EditText
    private lateinit var capDownEt: EditText
    private lateinit var slipEt: EditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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

        box.addView(sectionTitle("🔄 三层 SSA 稳态做T"))
        box.addView(hint("周线定环境 → 日线过滤+硬止损 → 1H 用连续2根 cycle 确认拐点做正T/反T。" +
            "固定滚动窗口、无未来函数；周线C/日线B·C 禁止开新T；含佣金+印花税+滑点；70%训练/30%样本外。"))

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        codeInput = EditText(ctx).apply {
            hint = "股票代号 / 名称"
            textSize = 13f; isSingleLine = true
            setTextColor(Color.parseColor("#333333")); setHintTextColor(Color.parseColor("#AAAAAA"))
            background = roundedBg("#F5F5F5", "#DDDDDD", 6)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        inputRow.addView(codeInput)
        runBtn = Button(ctx).apply {
            text = "开始做T回测"; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
            setOnClickListener { onRun() }
        }
        inputRow.addView(runBtn)
        box.addView(inputRow)

        // 三层窗口参数（可横向滑动）
        box.addView(label("三层固定窗口（周 / 日 / 时：L嵌入, M滚动样本, N回看）"))
        val hsv = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val prow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf(
            "周L" to "20", "周M" to "80", "周N" to "12",
            "日L" to "30", "日M" to "120", "日N" to "20",
            "时L" to "30", "时M" to "120", "时N" to "20"
        )
        defs.forEach { (cap, dft) ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) }
            }
            cell.addView(TextView(ctx).apply { text = cap; textSize = 9f; setTextColor(GRAY) })
            cell.addView(EditText(ctx).apply {
                setText(dft); textSize = 12f; isSingleLine = true
                setTextColor(Color.parseColor("#333333"))
                background = roundedBg("#FFFFFF", "#CCCCCC", 6)
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            }.also { cfgInputs.add(it) })
            prow.addView(cell)
        }
        hsv.addView(prow); box.addView(hsv)

        // 仓位/成本
        box.addView(label("仓位上限与成本（周线上/周线下，滑点）"))
        val crow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(cap: String, dft: String): EditText {
            val c = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
            }
            c.addView(TextView(ctx).apply { text = cap; textSize = 9f; setTextColor(GRAY) })
            val et = EditText(ctx).apply {
                setText(dft); textSize = 12f; isSingleLine = true
                setTextColor(Color.parseColor("#333333"))
                background = roundedBg("#FFFFFF", "#CCCCCC", 6)
                setPadding(dp(6), 0, dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
            }
            c.addView(et); crow.addView(c); return et
        }
        capUpEt = cell("周线上 仓位%", "40")
        capDownEt = cell("周线下 仓位%", "25")
        slipEt = cell("滑点(‱→小数0.0005)", "0.0005")
        box.addView(crow)

        statusTv = TextView(ctx).apply { textSize = 11f; setTextColor(GRAY); setPadding(dp(2), dp(6), dp(2), dp(2)) }
        box.addView(statusTv)
        resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(resultBox)
        box.addView(hint("⚠️ 三层 SSA 均为固定长度滚动窗口逐K重算；信号次日1H开盘成交；" +
            "A/B/C 为已收盘K状态；末尾窗口存在边缘效应；T单快速了结，不做长波段。结果不构成投资建议。"))
        return root
    }

    // ═══════════════════════ 执行 ═══════════════════════

    private fun onRun() {
        val raw = codeInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) { status("请输入股票代号或名称"); return }
        val p = readParams() ?: return
        runBtn.isEnabled = false
        resultBox.removeAllViews()
        lifecycleScope.launch {
            try {
                status("⏳ 解析股票 / 加载日K…")
                val (code, name) = withContext(Dispatchers.IO) { resolveCode(raw) }
                if (code == null) { status("⚠️ 未找到「$raw」"); return@launch }
                val days = withContext(Dispatchers.IO) { loadDaily(code) }
                if (days == null) { status("⚠️ $name 日K不足（需约2年）"); return@launch }
                status("⏳ 在线拉取 1H K线（东财 klt=60，约2年）…")
                val hourlyEnt = withContext(Dispatchers.IO) {
                    IntradayKlineFetcher(requireContext()).fetchHourlyHistory(code, 800)
                }
                if (hourlyEnt.size < p.hour.windowM + 60) {
                    status("⚠️ 1H K线仅 ${hourlyEnt.size} 根（需≥${p.hour.windowM + 60}），网络数据不足"); return@launch
                }
                val hBars = hourlyEnt
                    .filter { it.close > 0 && it.high > 0 && it.low > 0 }
                    .sortedBy { it.datetime }
                    .map { Bar(it.date, it.datetime.substringAfter(' ', ""), it.open, it.high, it.low, it.close) }
                status("⏳ 三层滚动 SSA + 做T回测（${hBars.size} 根1H / ${days.size} 根日K）…")
                val r = withContext(Dispatchers.Default) { runCatching { SsaT0Engine.run(hBars, days, p) } }
                r.onSuccess { render(name ?: code, code, it) }
                    .onFailure { status("⚠️ 回测失败：${it.message}") }
                if (r.isSuccess) status("✅ 完成：${name ?: code}($code)")
            } finally {
                runBtn.isEnabled = true
            }
        }
    }

    private fun readParams(): Params? {
        fun iv(i: Int, n: String): Int? {
            val v = cfgInputs[i].text?.toString()?.trim()?.toIntOrNull()
            if (v == null) status("⚠️ 「$n」需为整数")
            return v
        }
        val wl = iv(0, "周L") ?: return null; val wm = iv(1, "周M") ?: return null; val wn = iv(2, "周N") ?: return null
        val dl = iv(3, "日L") ?: return null; val dm = iv(4, "日M") ?: return null; val dn = iv(5, "日N") ?: return null
        val hl = iv(6, "时L") ?: return null; val hm = iv(7, "时M") ?: return null; val hn = iv(8, "时N") ?: return null
        val cu = capUpEt.text?.toString()?.trim()?.toDoubleOrNull(); if (cu == null) { status("⚠️ 周线上仓位%无效"); return null }
        val cd = capDownEt.text?.toString()?.trim()?.toDoubleOrNull(); if (cd == null) { status("⚠️ 周线下仓位%无效"); return null }
        val sl = slipEt.text?.toString()?.trim()?.toDoubleOrNull(); if (sl == null) { status("⚠️ 滑点无效"); return null }
        return Params(LayerCfg(wl, wm, wn), LayerCfg(dl, dm, dn), LayerCfg(hl, hm, hn), cu, cd, slippageRate = sl)
    }

    // ═══════════════════════ 渲染 ═══════════════════════

    private fun render(name: String, code: String, r: Result) {
        resultBox.removeAllViews()
        // 分段指标
        resultBox.addView(card {
            it.addView(cardTitle("📊 分段绩效（参数固定，样本外不回调）"))
            it.addView(segTable(listOf(r.train, r.test, r.full)))
        })
        // 周线下跌环境
        val wd = r.weekDown
        resultBox.addView(card {
            it.addView(cardTitle("🔻 周线下跌环境下的独立做T绩效"))
            it.addView(statGrid(listOf(
                "下跌环境T单数" to "${wd.trades} 笔",
                "胜率" to "%.1f%%".format(wd.winRatePct),
                "平均每笔" to pct(wd.avgPnlPct),
                "T单累计" to pct(wd.totalPnlPct)
            )))
        })
        // 主图：1H K线 + 蓝/红轨道同轴 + ABC + 买卖点 + 训练分界
        resultBox.addView(card {
            it.addView(cardTitle("① 1H 主图：K线 + 蓝正常轨 + 红极限轨（同轴） + A/B/C + 做T买卖点"))
            it.addView(buildHourMain(r, 340))
            it.addView(legend("蓝线=正常区间  红虚线=极限区间  灰/橙/红圆点=A/B/C  ▲买 ▼卖  竖虚线=训练/样本外分界"))
        })
        // 1H 趋势/cycle
        resultBox.addView(card {
            it.addView(cardTitle("② 1H SSA 趋势中枢 / cycle 振荡（拐点确认用）"))
            it.addView(buildTrendCycleMini(r.h, 110, timeFmt(r.h.bars)))
        })
        // 日线层
        resultBox.addView(card {
            it.addView(cardTitle("③ 日线层：收盘 + 双轨道 + A/B/C（过滤器 / 硬止损基准）"))
            it.addView(buildBandLayer(r.d, 200, dateFmt(r.d.bars)))
        })
        // 周线层
        resultBox.addView(card {
            it.addView(cardTitle("④ 周线层：收盘 + 双轨道 + 趋势（环境总闸）"))
            it.addView(buildBandLayer(r.w, 170, dateFmt(r.w.bars)))
        })
        // 净值
        resultBox.addView(card {
            it.addView(cardTitle("⑤ 总权益（含底仓）vs 买入持有 + 回撤"))
            it.addView(buildEquity(r, 180))
        })
        // 风控日志
        resultBox.addView(card {
            it.addView(cardTitle("🛡️ 风控触发（${r.riskEvents.size} 次，最多显示40条）"))
            val txt = if (r.riskEvents.isEmpty()) "无风控触发。"
            else r.riskEvents.takeLast(40).joinToString("\n") { e -> "${e.time}  [${e.level}] ${e.reason}" }
            it.addView(TextView(requireContext()).apply {
                text = txt; textSize = 10f; setTextColor(Color.parseColor("#555555"))
            })
        })
        // 交易明细
        resultBox.addView(card {
            it.addView(cardTitle("📋 T单明细（${r.trades.size} 笔，最多显示60笔）"))
            val scroll2 = HorizontalScrollView(requireContext())
            val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            val headers = listOf("#", "方向", "开仓", "开价", "平仓", "平价", "收益", "1H根数", "周线", "离场")
            val widths = listOf(24, 38, 92, 56, 92, 56, 62, 56, 40, 120)
            table.addView(row(headers, widths, true))
            r.trades.takeLast(60).forEachIndexed { i, t ->
                val rr = tableRow(listOf(
                    "${i + 1}", t.direction, t.openTime.takeLast(11), "%.2f".format(t.openPrice),
                    t.closeTime.takeLast(11), "%.2f".format(t.closePrice),
                    "${if (t.returnPct > 0) "+" else ""}%.2f%%".format(t.returnPct),
                    "${t.barsHeld}", if (t.weeklyUpAtOpen) "↑" else "↓", t.exitReason
                ), widths)
                (rr.getChildAt(6) as TextView).apply {
                    setTextColor(if (t.returnPct > 0) RED else GREEN); setTypeface(typeface, Typeface.BOLD)
                }
                (rr.getChildAt(1) as TextView).setTextColor(if (t.direction == "正T") BLUE else PURPLE)
                (rr.getChildAt(8) as TextView).setTextColor(if (t.weeklyUpAtOpen) RED else GREEN)
                table.addView(rr)
            }
            scroll2.addView(table); it.addView(scroll2)
        })
    }

    private fun pct(v: Double) = "${if (v > 0) "+" else ""}%.2f%%".format(v)

    private fun segTable(segs: List<SegStats>): View {
        val scroll = HorizontalScrollView(requireContext())
        val table = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val headers = listOf("分段", "总收益", "年化", "最大回撤", "T单数", "胜率", "笔均盈亏",
            "正T/反T", "平均持仓", "每周笔数", "买持基准", "超额")
        val widths = listOf(96, 70, 66, 70, 52, 58, 66, 64, 64, 66, 72, 66)
        table.addView(row(headers, widths, true))
        segs.forEach { s ->
            val rr = tableRow(listOf(
                s.name, pct(s.totalReturnPct), pct(s.annualizedPct), pct(s.maxDrawdownPct),
                "${s.tradeCount}", "%.1f%%".format(s.winRatePct), pct(s.avgPnlPct),
                "${s.longT}/${s.shortT}", "${s.avgBarsHeld}根",
                "%.2f".format(s.tradesPerWeek), pct(s.benchReturnPct), pct(s.excessPct)
            ), widths)
            (rr.getChildAt(1) as TextView).apply {
                setTextColor(if (s.totalReturnPct > 0) RED else GREEN); setTypeface(typeface, Typeface.BOLD)
            }
            // 每周笔数 <1 标橙提示
            (rr.getChildAt(9) as TextView).setTextColor(if (s.tradesPerWeek < 1.0) ORANGE else Color.parseColor("#333333"))
            table.addView(rr)
        }
        scroll.addView(table)
        return scroll
    }

    private fun statGrid(cells: List<Pair<String, String>>): View {
        val grid = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        cells.chunked(4).forEach { rc ->
            val r2 = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rc.forEach { (k, v) ->
                val c = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = roundedBg("#FAFAFA", "#EEEEEE", 6)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(6); bottomMargin = dp(6) }
                }
                c.addView(TextView(requireContext()).apply { text = k; textSize = 9f; setTextColor(GRAY) })
                c.addView(TextView(requireContext()).apply {
                    text = v; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(when { v.startsWith("+") -> RED; v.startsWith("-") -> GREEN
                        else -> Color.parseColor("#333333") })
                })
                r2.addView(c)
            }
            repeat(4 - rc.size) { r2.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }) }
            grid.addView(r2)
        }
        return grid
    }

    // ═══════════════════════ 图表 ═══════════════════════

    private fun dateFmt(bars: List<Bar>) = object : ValueFormatter() {
        override fun getFormattedValue(v: Float): String {
            val i = v.toInt()
            if (i !in bars.indices) return ""
            val b = bars[i]
            return "${b.date.substring(4, 6)}/${b.date.substring(6, 8)}"
        }
    }
    private fun timeFmt(bars: List<Bar>) = object : ValueFormatter() {
        override fun getFormattedValue(v: Float): String {
            val i = v.toInt()
            if (i !in bars.indices) return ""
            val b = bars[i]
            return "${b.date.substring(4, 6)}/${b.date.substring(6, 8)} ${b.time.takeLast(5)}"
        }
    }

    private fun styleX(chart: com.github.mikephil.charting.charts.BarLineChartBase<*>, bars: List<Bar>, time: Boolean) {
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (bars.size / 7).coerceAtLeast(1).toFloat()
            labelCount = 7; textSize = 7f; textColor = GRAY
            setDrawGridLines(false); setAvoidFirstLastClipping(true)
            valueFormatter = if (time) timeFmt(bars) else dateFmt(bars)
        }
        chart.axisLeft.apply { textSize = 8f; textColor = GRAY; gridColor = Color.parseColor("#EEEEEE") }
        chart.axisRight.isEnabled = false
    }

    private fun splitLine(x: Float): LimitLine = LimitLine(x, "训练/样本外").apply {
        lineColor = Color.parseColor("#7B1FA2"); lineWidth = 1.2f
        enableDashedLine(8f, 6f, 0f); textSize = 7f; textColor = PURPLE
    }

    private fun bandSet(arr: DoubleArray, label: String, color: Int, dashed: Boolean, width: Float = 1f) =
        LineDataSet(entries(arr), label).apply {
            this.color = color; lineWidth = width
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            if (dashed) enableDashedLine(7f, 5f, 0f)
        }

    private fun entries(a: DoubleArray) = ArrayList<Entry>().also { es ->
        a.forEachIndexed { i, v -> if (!v.isNaN()) es.add(Entry(i.toFloat(), v.toFloat())) }
    }

    private fun abcScatter(layer: Layer, scn: Int, color: Int, label: String): LineDataSet {
        val es = ArrayList<Entry>()
        layer.bars.forEachIndexed { i, b -> if (layer.scenario[i] == scn) es.add(Entry(i.toFloat(), b.close.toFloat())) }
        return LineDataSet(es, label).apply {
            this.color = color; setDrawCircles(true); circleRadius = 2f; setCircleColor(color)
            lineWidth = 0f; setDrawValues(false); isHighlightEnabled = false
        }
    }

    private fun arrows(markers: List<com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Marker>,
                       bars: List<Bar>, buy: Boolean): LineDataSet {
        val es = markers.filter { it.buy == buy }.map {
            val m = if (buy) bars[it.index].low * 0.99 else bars[it.index].high * 1.01
            Entry(it.index.toFloat(), m.toFloat())
        }
        return LineDataSet(es, if (buy) "买入" else "卖出").apply {
            color = Color.TRANSPARENT; setDrawCircles(false); setDrawValues(true)
            valueTextSize = 10f; valueTextColor = if (buy) RED else GREEN; isHighlightEnabled = false
            valueFormatter = object : ValueFormatter() {
                override fun getPointLabel(entry: Entry?) = if (buy) "▲" else "▼"
            }
        }
    }

    /** ① 1H 主图（K线+四轨道同轴+ABC散点+箭头+分界） */
    private fun buildHourMain(r: Result, h: Int): CombinedChart {
        val layer = r.h; val bars = layer.bars
        val chart = CombinedChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum(240f); chart.setVisibleXRangeMinimum(60f)
        chart.moveViewToX((bars.size - 1).toFloat())
        chart.drawOrder = arrayOf(CombinedChart.DrawOrder.CANDLE, CombinedChart.DrawOrder.LINE)

        val ce = ArrayList<CandleEntry>()
        bars.forEachIndexed { i, b ->
            ce.add(CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat()))
        }
        val cds = CandleDataSet(ce, "1H").apply {
            shadowColor = Color.parseColor("#BBBBBB"); shadowWidth = 1f
            increasingPaintStyle = Paint.Style.FILL; decreasingPaintStyle = Paint.Style.FILL
            increasingColor = RED; decreasingColor = GREEN
            isHighlightEnabled = false; setDrawValues(false)
        }
        val ld = LineData(
            bandSet(layer.eDn, "极限下轨", RED, true, 0.9f),
            bandSet(layer.nDn, "正常下轨", BLUE, false, 1.1f),
            bandSet(layer.nUp, "正常上轨", BLUE, false, 1.1f),
            bandSet(layer.eUp, "极限上轨", RED, true, 0.9f),
            abcScatter(layer, SsaEngine.SCN_A, A_C, "A"),
            abcScatter(layer, SsaEngine.SCN_B, ORANGE, "B"),
            abcScatter(layer, SsaEngine.SCN_C, RED, "C"),
            arrows(r.markers, bars, true),
            arrows(r.markers, bars, false)
        )
        chart.data = CombinedData().apply { setData(CandleData(cds)); setData(ld) }
        styleX(chart, bars, time = true)
        chart.xAxis.addLimitLine(splitLine(r.splitIndex.toFloat()))
        chart.post { chart.invalidate() }
        return chart
    }

    /** ② 趋势+cycle 双区迷你图 */
    private fun buildTrendCycleMini(layer: Layer, h: Int, fmt: ValueFormatter): View {
        val wrap = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(miniLine(listOf(
            line(layer.trend, "趋势中枢", ORANGE, 1.5f)
        ), layer.bars, h, fmt, false))
        wrap.addView(miniLine(listOf(
            line(layer.cycle, "cycle", PURPLE, 1.2f)
        ), layer.bars, 90, fmt, true))
        return wrap
    }

    private fun line(a: DoubleArray, nm: String, c: Int, w: Float): LineDataSet =
        LineDataSet(entries(a), nm).apply {
            color = c; lineWidth = w; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
        }

    private fun miniLine(sets: List<LineDataSet>, bars: List<Bar>, h: Int,
                         fmt: ValueFormatter, zeroLine: Boolean): LineChart {
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(40f)
        chart.moveViewToX((bars.size - 1).toFloat())
        chart.data = LineData(sets)
        if (zeroLine) chart.axisLeft.addLimitLine(LimitLine(0f, "").apply {
            lineColor = Color.parseColor("#CCCCCC"); lineWidth = 1f
        })
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (bars.size / 7).coerceAtLeast(1).toFloat(); labelCount = 7
            textSize = 7f; textColor = GRAY; setDrawGridLines(false); valueFormatter = fmt
        }
        chart.axisLeft.textSize = 7f; chart.axisLeft.textColor = GRAY
        chart.axisRight.isEnabled = false
        chart.post { chart.invalidate() }
        return chart
    }

    /** ③④ 日/周层：收盘+四轨道+ABC（全同轴） */
    private fun buildBandLayer(layer: Layer, h: Int, fmt: ValueFormatter): LineChart {
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 7f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((layer.bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(30f)
        chart.moveViewToX((layer.bars.size - 1).toFloat())
        val closeSet = LineDataSet(entries(DoubleArray(layer.bars.size) { i -> layer.bars[i].close }), "收盘").apply {
            color = Color.parseColor("#222222"); lineWidth = 1.3f
            setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
        }
        chart.data = LineData(
            bandSet(layer.eDn, "极限下轨", RED, true, 0.9f),
            bandSet(layer.nDn, "正常下轨", BLUE, false),
            closeSet,
            bandSet(layer.nUp, "正常上轨", BLUE, false),
            bandSet(layer.eUp, "极限上轨", RED, true, 0.9f),
            abcScatter(layer, SsaEngine.SCN_A, A_C, "A"),
            abcScatter(layer, SsaEngine.SCN_B, ORANGE, "B"),
            abcScatter(layer, SsaEngine.SCN_C, RED, "C")
        )
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = (layer.bars.size / 7).coerceAtLeast(1).toFloat(); labelCount = 7
            textSize = 7f; textColor = GRAY; setDrawGridLines(false); valueFormatter = fmt
        }
        chart.axisLeft.textSize = 8f; chart.axisLeft.textColor = GRAY
        chart.axisLeft.gridColor = Color.parseColor("#EEEEEE")
        chart.axisRight.isEnabled = false
        chart.post { chart.invalidate() }
        return chart
    }

    /** ⑤ 权益/基准/回撤 */
    private fun buildEquity(r: Result, h: Int): LineChart {
        val bars = r.h.bars
        val chart = LineChart(requireContext())
        chart.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        chart.setBackgroundColor(Color.WHITE); chart.description.isEnabled = false
        chart.legend.textSize = 8f; chart.legend.textColor = GRAY
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.setDragEnabled(true)
        chart.setHighlightPerTapEnabled(false); chart.setHighlightPerDragEnabled(false)
        chart.setVisibleXRangeMaximum((bars.size + 2).toFloat()); chart.setVisibleXRangeMinimum(60f)
        chart.moveViewToX((bars.size - 1).toFloat())
        val es = ArrayList<Entry>(); val bs = ArrayList<Entry>(); val ds = ArrayList<Entry>()
        bars.indices.forEach { i ->
            es.add(Entry(i.toFloat(), r.equity[i].toFloat()))
            bs.add(Entry(i.toFloat(), r.benchmark[i].toFloat()))
        }
        val data = LineData(
            LineDataSet(es, "总权益(含底仓)").apply {
                color = TEAL; lineWidth = 1.6f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
            },
            LineDataSet(bs, "买入持有").apply {
                color = GRAY; lineWidth = 1.1f; setDrawCircles(false); setDrawValues(false); isHighlightEnabled = false
                enableDashedLine(6f, 4f, 0f)
            }
        )
        chart.data = data
        styleX(chart, bars, time = true)
        chart.xAxis.addLimitLine(splitLine(r.splitIndex.toFloat()))
        chart.axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(v: Float) = "%,.0f".format(v)
        }
        chart.post { chart.invalidate() }
        return chart
    }

    // ═══════════════════════ 数据 ═══════════════════════

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

    private suspend fun loadDaily(code: String): List<Bar>? {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        var local: List<DailySnapshotEntity> =
            runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
        if (local.size < 500) {
            val ok = HistoricalDataFetcher(ctx).fetchStockLatest(code, 900)
            if (ok) local = runCatching { db.dailySnapshotDao().getByCode(code, 2000) }.getOrDefault(emptyList())
        }
        val bars = local.filter { it.close > 0 && it.high > 0 && it.low > 0 }
            .sortedBy { it.date }
            .map { Bar(it.date, "", it.open.ifZero(it.close), it.high, it.low, it.close) }
        return if (bars.size < 150) null else bars
    }

    private fun Double.ifZero(f: Double) = if (this == 0.0) f else this

    // ═══════════════════════ UI 工具 ═══════════════════════

    private fun status(s: String) { if (isAdded) statusTv.text = s }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun roundedBg(fill: String, stroke: String, r: Int) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(Color.parseColor(fill)); setStroke(dp(1), Color.parseColor(stroke))
    }
    private fun sectionTitle(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#222222")); setPadding(0, dp(4), 0, dp(2))
    }
    private fun label(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 11f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#666666")); setPadding(dp(2), dp(8), 0, dp(3))
    }
    private fun hint(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 10f; setTextColor(GRAY); setPadding(dp(2), dp(3), dp(2), dp(3))
    }
    private fun legend(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 9f; setTextColor(GRAY); setPadding(0, dp(4), 0, 0)
    }
    private fun cardTitle(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#333333")); setPadding(0, 0, 0, dp(6))
    }
    private fun card(build: (LinearLayout) -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
        background = roundedBg("#FFFFFF", "#E8E8E8", 8)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        build(this)
    }
    private fun row(cells: List<String>, widthsDp: List<Int>, header: Boolean): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (header) Color.parseColor("#F0F0F0") else Color.WHITE)
            cells.forEachIndexed { i, txt ->
                addView(TextView(requireContext()).apply {
                    text = txt; textSize = 9f
                    gravity = Gravity.CENTER_VERTICAL or
                        (if (i == 0 || i == cells.lastIndex) Gravity.START else Gravity.CENTER_HORIZONTAL)
                    setTextColor(if (header) Color.parseColor("#666666") else Color.parseColor("#333333"))
                    if (header) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(5), dp(4), dp(5))
                    layoutParams = LinearLayout.LayoutParams(dp(widthsDp[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }
    private fun tableRow(cells: List<String>, widthsDp: List<Int>) = row(cells, widthsDp, false)
}
