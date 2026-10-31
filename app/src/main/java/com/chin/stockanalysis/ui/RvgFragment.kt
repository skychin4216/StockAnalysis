package com.chin.stockanalysis.ui

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
import com.chin.stockanalysis.strategy.data.IntradayKlineFetcher
import com.chin.stockanalysis.strategy.rvg.RvgEngine
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 🌡 RVG Tab — 波动率状态自适应
 *
 * 决策链路（T 日收盘算，T+1 开盘执行）：
 * 1. PELT 断点检测 → 阶段起点（新断点后冷却禁开新仓）
 * 2. GJR-GARCH(1,1,1)+t 只用当前阶段样本 → σ_f / σ_LR / R
 * 3. 仓位 = clip(σ_target/σ_f, 0, L_max) · 1[d≠0] · s(R)
 * 4. 执行：10% 死区、T+1 开盘、万3+5bp
 *
 * σ 只管仓位大小；趋势只管能不能开仓、做多做空。二者解耦。
 */
class RvgFragment : Fragment() {

    private val TEAL = Color.parseColor("#26A69A")
    private val RED = Color.parseColor("#EF5350")
    private val GREEN = Color.parseColor("#43A047")
    private val ORANGE = Color.parseColor("#FF9800")
    private val BLUE = Color.parseColor("#1E88E5")
    private val GRAY = Color.parseColor("#757575")
    private val DARK = Color.parseColor("#212121")

    private val fetcher by lazy { IntradayKlineFetcher(requireContext()) }

    // 参数（默认值遵循策略说明书）
    private var sigmaTarget = 10.0   // %
    private var maxLev = 1.0
    private var penK = 3.0
    private var deadband = 10.0      // %
    private var cooldown = 5
    private var allowShort = true
    private var useAtrStop = true

    private lateinit var codeInput: EditText
    private lateinit var statusTv: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var runBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, c: Int, s: Float, bold: Boolean = false) = TextView(requireContext()).apply {
        text = t; textSize = s; setTextColor(c)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }
        scroll.addView(root)

        root.addView(tv("🌡 RVG 波动率状态自适应", DARK, 15f, true))
        root.addView(tv("PELT 先切市场阶段 → 只用当前阶段样本估 GJR-GARCH(1,1,1)+t → " +
            "用 σ_target/σ_f 定仓位大小；方向由 MA20+MA60 斜率（2 日确认）决定；" +
            "波动闸门 s(R) 防震荡频繁交易。σ 与方向解耦，T 日收盘算、T+1 开盘执行。", GRAY, 10f))

        // 代码输入
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        codeInput = EditText(ctx).apply {
            hint = "股票代码，如 600519 / sh600519"
            textSize = 13f; isSingleLine = true
            setTextColor(Color.parseColor("#333333")); setHintTextColor(Color.parseColor("#AAAAAA"))
            background = rounded("#F5F5F5", "#DDDDDD")
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        }
        inputRow.addView(codeInput)
        runBtn = Button(ctx).apply {
            text = "运行回测"; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(TEAL)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
            setOnClickListener { onRun() }
        }
        inputRow.addView(runBtn)
        root.addView(inputRow)

        // 参数格
        root.addView(label("核心参数（年化目标波动 / 杠杆上限 / PELT 惩罚系数 / 调仓死区）"))
        val prow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        prow.addView(paramCell("目标波动%", sigmaTarget.toString()) { sigmaTarget = it.coerceIn(2.0, 50.0) })
        prow.addView(paramCell("杠杆上限", maxLev.toString()) { maxLev = it.coerceIn(0.2, 2.0) })
        prow.addView(paramCell("pen系数", penK.toString()) { penK = it.coerceIn(0.5, 10.0) })
        prow.addView(paramCell("死区%", deadband.toString()) { deadband = it.coerceIn(0.0, 50.0) })
        root.addView(prow)

        // 开关行
        val swRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(2))
        }
        val swShort = switchChip("允许做空") { allowShort = it }
        val swAtr = switchChip("ATR 止损") { useAtrStop = it }
        val swCool = switchChip("断点冷却", initial = true) { cooldown = if (it) 5 else 0 }
        listOf(swShort, swAtr, swCool).forEachIndexed { i, v ->
            swRow.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (i < 2) marginEnd = dp(6) })
        }
        root.addView(swRow)

        statusTv = tv("输入代码后点「运行回测」", GRAY, 11f).apply { setPadding(dp(2), dp(6), dp(2), dp(2)) }
        root.addView(statusTv)

        resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultBox)

        root.addView(tv("⚠️ 回测为规则模拟（T+1 开盘、万3佣金+5bp滑点）；" +
            "GJR-GARCH 为方差目标化 + t 分布 QML 估计；合成数据验证不代表实盘收益。", GRAY, 9f))

        return scroll
    }

    // ═════════════════════════ 运行 ═════════════════════════

    private fun onRun() {
        val raw = codeInput.text.toString().trim()
        if (raw.isEmpty()) { statusTv.text = "⚠️ 请输入股票代码"; return }
        val full = fullCode(raw)
        runBtn.isEnabled = false
        statusTv.text = "⏳ 拉取日线…"
        resultBox.removeAllViews()
        lifecycleScope.launch {
            try {
                val bars = withContext(Dispatchers.IO) { fetcher.fetchDailyHistory(full, datalen = 750) }
                if (bars.size < 150) {
                    statusTv.text = "❌ 日线不足（${bars.size} 根，至少需 150 根）"; runBtn.isEnabled = true; return@launch
                }
                statusTv.text = "⏳ 计算 PELT 断点 + GJR-GARCH（${bars.size} 根）…"
                val dates = bars.map { it.datetime.substring(0, 10) }
                val closes = bars.map { it.close }.toDoubleArray()
                val opens = bars.map { it.open }.toDoubleArray()
                val highs = bars.map { it.high }.toDoubleArray()
                val lows = bars.map { it.low }.toDoubleArray()
                val p = RvgEngine.Params(
                    sigmaTarget = sigmaTarget / 100.0,
                    maxLeverage = maxLev,
                    penK = penK,
                    deadband = deadband / 100.0,
                    cooldownDays = cooldown,
                    allowShort = allowShort,
                    useAtrStop = useAtrStop
                )
                val res = withContext(Dispatchers.Default) {
                    RvgEngine.analyze(dates, closes, opens, highs, lows, p)
                }
                if (res == null) {
                    statusTv.text = "❌ 数据不足或计算失败"; runBtn.isEnabled = true; return@launch
                }
                statusTv.text = "✅ $full 完成：${res.metrics.bars} 个交易日、${res.breakIdx.size} 个断点"
                render(res, full)
            } catch (e: Exception) {
                statusTv.text = "❌ ${e.javaClass.simpleName}: ${e.message?.take(50)}"
            } finally {
                runBtn.isEnabled = true
            }
        }
    }

    // ═════════════════════════ 渲染 ═════════════════════════

    private fun render(r: RvgEngine.Result, code: String) {
        val ctx = requireContext()
        resultBox.removeAllViews()
        val m = r.metrics
        val g = r.garch
        val last = r.lastSignal

        // 指标卡
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded("#FFFFFF", "#DDDDDD")
        }
        card.addView(tv("📊 回测结果  $code", DARK, 12f, true))
        card.addView(kv("年化收益", pct(m.annRet), if (m.annRet >= 0) RED else GREEN))
        card.addView(kv("年化波动", pct(m.annVol), GRAY))
        card.addView(kv("最大回撤", pct(m.mdd), RED))
        card.addView(kv("年化换手", "%.1f 倍".format(m.turnover), GRAY))
        card.addView(kv("平均仓位 / 持仓占比", "%.0f%% / %.0f%%".format(m.avgAbsPos * 100, m.investedPct * 100), BLUE))
        card.addView(kv("交易笔数 / ATR止损", "${m.trades} / ${m.hitDays}", GRAY))
        if (g != null) {
            card.addView(tv("—— GJR-GARCH(1,1,1) + t（最近一次拟合，样本 ${g.n}）", DARK, 11f, true)
                .apply { setPadding(0, dp(6), 0, dp(2)) })
            card.addView(kv("α / β / γ / ν",
                "%.3f / %.3f / %.3f / %.1f".format(g.alpha, g.beta, g.gamma, g.nu), DARK))
            card.addView(kv("ξ=α+β+γ/2", "%.3f".format(g.xi), DARK))
            card.addView(kv("余震半衰期", if (g.halfLife.isNaN()) "—" else "%.1f 天".format(g.halfLife), ORANGE))
            card.addView(kv("QLIKE / ±1.96σ覆盖率",
                "%.2f / %.1f%%".format(g.qlike, g.coverage * 100), GRAY))
        }
        if (last != null) {
            card.addView(tv("—— 最新信号 ${last.date}", DARK, 11f, true).apply { setPadding(0, dp(6), 0, dp(2)) })
            card.addView(kv("σ_f / σ_LR / R",
                "%.1f%% / %.1f%% / %.2f".format(last.sigmaF * 100, last.sigmaLR * 100, last.rRatio), DARK))
            card.addView(kv("方向 d / 闸门 s(R) / 仓位",
                "${dirLabel(last.dir)} / ${"%.1f".format(last.scale)} / ${"%+.0f%%".format(last.w * 100)}", BLUE))
            card.addView(kv("阶段起点", "${last.phaseStartDate}（已 ${last.phaseBars} 日）", GRAY))
        }

        // —— 次日建议与预测 ——
        r.nextDay?.let { nd ->
            card.addView(tv("—— 次日建议与预测（基于 ${nd.lastDate} 收盘）", DARK, 11f, true)
                .apply { setPadding(0, dp(8), 0, dp(2)) })
            card.addView(kv("次日预测波动", "日 σ %.2f%% / 年化 σ_f %.1f%%（σ_LR %.1f%%，R=%.2f）"
                .format(nd.sigmaDaily * 100, nd.sigmaAnn * 100, nd.sigmaLR * 100, nd.rRatio), DARK))
            card.addView(kv("±1σ 价格带（≈68%）", "%.2f ~ %.2f".format(nd.dn1, nd.up1), GRAY))
            card.addView(kv("±2σ 价格带（≈95%）", "%.2f ~ %.2f".format(nd.dn2, nd.up2), GRAY))
            card.addView(kv("方向 d / 闸门 s(R) / 目标仓位",
                "${dirLabel(nd.dir)} / ${"%.1f".format(nd.scale)} / ${"%+.0f%%".format(nd.wTarget * 100)}", BLUE))
            val actColor = when {
                nd.delta > 1e-9 -> RED
                nd.delta < -1e-9 -> GREEN
                else -> GRAY
            }
            card.addView(kv("建议动作", "%s  %+.0f%% → %+.0f%%（%+.0f%%）"
                .format(nd.action, nd.wCur * 100, nd.wNext * 100, nd.delta * 100), actColor))
            nd.stopPrice?.let {
                card.addView(kv("ATR 止损位（收盘触发）", "%.2f（距现价 %+.1f%%）"
                    .format(it, (it / nd.close - 1.0) * 100), ORANGE))
            }
            val notes = buildList {
                if (nd.cooling) add("断点冷却期禁开新仓")
                if (nd.halted) add("回撤熔断停手中")
                if (nd.forcedFlat) add("ATR 止损后强制空仓")
            }
            if (notes.isNotEmpty()) card.addView(kv("限制", notes.joinToString(" / "), ORANGE))
            card.addView(tv("σ 为 GJR-GARCH(1,1,1)+t 的一步预测；价格带为正态近似，只刻画波动区间，不含方向预测。",
                GRAY, 8f).apply { setPadding(0, dp(4), 0, 0) })
        }
        resultBox.addView(card)

        // ① 价格 + 断点
        resultBox.addView(section("① 价格与 PELT 断点（红线=阶段切换）"))
        val priceChart = mkChart()
        resultBox.addView(priceChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
        drawPrice(priceChart, r)

        // ② σ_f vs σ_LR
        resultBox.addView(section("② 预测波动 σ_f vs 阶段波动 σ_LR（年化 %）"))
        val volChart = mkChart()
        resultBox.addView(volChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
        drawVol(volChart, r)

        // ③ 仓位 + 净值
        resultBox.addView(section("③ 仓位（左轴 %）与净值（右轴）"))
        val posChart = mkChart(right = true)
        resultBox.addView(posChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)))
        drawPosEq(posChart, r)

        // ④ 信号表
        resultBox.addView(section("④ 近 25 日信号表"))
        val rows = r.steps.takeLast(25).reversed()
        val head = tv("日期       σf%  R     s    d   仓位    净值   备注", GRAY, 9f)
        head.setTypeface(Typeface.MONOSPACE)
        resultBox.addView(head)
        rows.forEach { s ->
            val note = when {
                s.exitNote != null -> s.exitNote!!
                s.newBreak -> "断点"
                s.cooling -> "冷却"
                s.halted -> "熔断"
                else -> ""
            }
            val color = when {
                s.w > 0.001 -> RED
                s.w < -0.001 -> GREEN
                else -> GRAY
            }
            resultBox.addView(tv(
                "%-10s %4.0f %4.2f %4.1f %4s %5.0f%% %6.3f %s".format(
                    s.date.takeLast(10), s.sigmaF * 100, s.rRatio, s.scale,
                    dirLabel(s.dir), s.w * 100, s.equity, note
                ), color, 9f
            ).apply { setTypeface(Typeface.MONOSPACE) })
        }
    }

    private fun drawPrice(chart: LineChart, r: RvgEngine.Result) {
        val entries = r.steps.mapIndexed { i, s -> Entry(i.toFloat(), s.close.toFloat()) }
        val ds = LineDataSet(entries, "收盘").apply {
            color = DARK; lineWidth = 1.2f; setDrawCircles(false); setDrawValues(false)
        }
        chart.data = LineData(ds)
        chart.xAxis.valueFormatter = dateFmt(r.steps.map { it.date })
        r.breakIdx.forEach { bi ->
            val pos = r.steps.indexOfFirst { it.idx >= bi }
            if (pos >= 0) {
                chart.xAxis.addLimitLine(LimitLine(pos.toFloat(), "").apply {
                    lineColor = RED; lineWidth = 1.0f
                    enableDashedLine(6f, 4f, 0f)
                })
            }
        }
        chart.invalidate()
    }

    private fun drawVol(chart: LineChart, r: RvgEngine.Result) {
        val f = r.steps.mapIndexed { i, s -> Entry(i.toFloat(), (s.sigmaF * 100).toFloat()) }
        val lr = r.steps.mapIndexed { i, s -> Entry(i.toFloat(), (s.sigmaLR * 100).toFloat()) }
        val d1 = LineDataSet(f, "σ_f").apply {
            color = ORANGE; lineWidth = 1.5f; setDrawCircles(false); setDrawValues(false)
        }
        val d2 = LineDataSet(lr, "σ_LR").apply {
            color = BLUE; lineWidth = 1.2f; setDrawCircles(false); setDrawValues(false)
            enableDashedLine(6f, 4f, 0f)
        }
        chart.data = LineData(d1, d2)
        chart.xAxis.valueFormatter = dateFmt(r.steps.map { it.date })
        chart.invalidate()
    }

    private fun drawPosEq(chart: LineChart, r: RvgEngine.Result) {
        val w = r.steps.mapIndexed { i, s -> Entry(i.toFloat(), (s.w * 100).toFloat()) }
        val eq = r.steps.mapIndexed { i, s -> Entry(i.toFloat(), s.equity.toFloat()) }
        val d1 = LineDataSet(w, "仓位%").apply {
            color = TEAL; lineWidth = 1.2f; setDrawCircles(false); setDrawValues(false)
            setDrawFilled(true); fillAlpha = 60; fillColor = TEAL
        }
        val d2 = LineDataSet(eq, "净值").apply {
            color = RED; lineWidth = 1.5f; setDrawCircles(false); setDrawValues(false)
        }
        d2.axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT
        chart.data = LineData(d1, d2)
        chart.xAxis.valueFormatter = dateFmt(r.steps.map { it.date })
        chart.invalidate()
    }

    // ═════════════════════════ UI 小件 ═════════════════════════

    private fun mkChart(right: Boolean = false) = LineChart(requireContext()).apply {
        description.isEnabled = false
        setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true)
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        axisRight.isEnabled = right
        axisLeft.setDrawGridLines(true)
        legend.isEnabled = true; legend.textSize = 9f
        setNoDataText("")
        setPadding(4, 4, 4, 4)
    }

    private fun dateFmt(dates: List<String>) = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val i = value.toInt()
            return if (i in dates.indices) dates[i].takeLast(5) else ""
        }
    }

    private fun dirLabel(d: Int) = when (d) { 1 -> "多"; -1 -> "空"; else -> "0" }

    private fun pct(v: Double) = "%.1f%%".format(v * 100)

    private fun section(t: String) = tv(t, DARK, 11f, true).apply { setPadding(0, dp(8), 0, dp(2)) }

    private fun label(t: String) = tv(t, GRAY, 9f).apply { setPadding(0, dp(4), 0, dp(2)) }

    private fun kv(k: String, v: String, vc: Int): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2))
        }
        row.addView(tv(k, GRAY, 10f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        row.addView(tv(v, vc, 10f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun paramCell(cap: String, dft: String, onChange: (Double) -> Unit): View {
        val ctx = requireContext()
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
        }
        cell.addView(tv(cap, GRAY, 9f))
        cell.addView(EditText(ctx).apply {
            setText(dft); textSize = 12f; isSingleLine = true
            setTextColor(Color.parseColor("#333333"))
            background = rounded("#FFFFFF", "#CCCCCC")
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    s?.toString()?.toDoubleOrNull()?.let(onChange)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        return cell
    }

    private fun switchChip(t: String, initial: Boolean = true, onToggle: (Boolean) -> Unit): TextView {
        var on = initial
        return TextView(requireContext()).apply {
            text = if (on) "$t ✓" else t
            textSize = 11f; gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            paint(on)
            setOnClickListener {
                on = !on
                text = if (on) "$t ✓" else t
                paint(on)
                onToggle(on)
            }
        }
    }

    private fun TextView.paint(on: Boolean) {
        setTextColor(if (on) Color.WHITE else DARK)
        background = rounded(if (on) "#26A69A" else "#E0E0E0", if (on) "#26A69A" else "#CCCCCC")
    }

    private fun rounded(bg: String, stroke: String) = android.graphics.drawable.GradientDrawable().apply {
        setColor(Color.parseColor(bg))
        cornerRadius = dp(6).toFloat()
        setStroke(1, Color.parseColor(stroke))
    }

    private fun fullCode(code: String): String {
        val pure = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        if (pure.isEmpty()) return code
        return if (pure[0] in "659") "sh$pure" else "sz$pure"
    }
}
