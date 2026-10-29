package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.strategy.analysis.ArmaEngine
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ## 📡 ARMA 参数谱估计 Tab
 *
 * AR(p) 极点（×）+ MA(q) 零点（○）画在单位圆上；绕圆一圈得功率谱 PSD（峰谷）；
 * 用 ARMA 模型递归预测未来 h 根。流程对齐相空间：走查拟合 / 滚动方向命中率 / 相关系数。
 */
class ArmaFragment : Fragment() {

    private val TEAL = Color.parseColor("#26A69A")
    private val RED = Color.parseColor("#EF5350")
    private val GREEN = Color.parseColor("#43A047")
    private val ORANGE = Color.parseColor("#FF9800")
    private val GRAY = Color.parseColor("#757575")
    private val BLUE = Color.parseColor("#1E88E5")
    private val PURPLE = Color.parseColor("#8E24AA")
    private val DARK = Color.parseColor("#212121")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, c: Int, s: Float) = TextView(requireContext()).apply {
        text = t; textSize = s; setTextColor(c)
    }
    private fun tv(t: String, s: Float, b: Boolean) = TextView(requireContext()).apply {
        text = t; textSize = s; setTextColor(DARK)
        if (b) setTypeface(null, android.graphics.Typeface.BOLD)
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

        root.addView(tv("📡 ARMA 参数谱估计（极点× / 零点○ / 绕圆 PSD）", 15f, true))

        // 模式（单标的/价差套利）+ 标的类型（个股/指数/ETF）
        val selRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val modeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val singleRb = RadioButton(ctx).apply { text = "单标的"; isChecked = true; textSize = 12f }
        val pairRb = RadioButton(ctx).apply { text = "价差套利"; textSize = 12f }
        modeGroup.addView(singleRb); modeGroup.addView(pairRb)
        val typeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val stockRb = RadioButton(ctx).apply { text = "个股"; isChecked = true; textSize = 12f }
        val indexRb = RadioButton(ctx).apply { text = "指数"; textSize = 12f }
        val etfRb = RadioButton(ctx).apply { text = "ETF"; textSize = 12f }
        typeGroup.addView(stockRb); typeGroup.addView(indexRb); typeGroup.addView(etfRb)
        selRow.addView(modeGroup); selRow.addView(typeGroup)
        root.addView(selRow)

        // 输入行
        val codeEt = EditText(ctx).apply { hint = "代码/A腿，如 600188"; textSize = 13f }
        val dailyBtn = RadioButton(ctx).apply { text = "日线"; isChecked = true }
        val hourlyBtn = RadioButton(ctx).apply { text = "1H" }
        val pEt = EditText(ctx).apply { hint = "p(0=自动)"; textSize = 11f; setWidth(dp(60)) }
        val qEt = EditText(ctx).apply { hint = "q"; textSize = 11f; setWidth(dp(50)); setText("2") }
        val runBtn = Button(ctx).apply { text = "重构+预测"; textSize = 13f }
        val inputRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inputRow.addView(codeEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        inputRow.addView(dailyBtn)
        inputRow.addView(hourlyBtn)
        inputRow.addView(pEt)
        inputRow.addView(qEt)
        inputRow.addView(runBtn)
        root.addView(inputRow)
        // 套利 B 腿行（默认隐藏）
        val pairRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
        }
        val codeBEt = EditText(ctx).apply { hint = "B 腿代码，如 000905"; textSize = 13f }
        pairRow.addView(codeBEt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pairRow.addView(tv("  价差 = A ÷ B（ln 后即标准价差序列）", GRAY, 10f))
        root.addView(pairRow)
        pairRb.setOnCheckedChangeListener { _, on -> pairRow.visibility = if (on) View.VISIBLE else View.GONE }
        val st = tv("输入代码点「重构+预测」", GRAY, 11f)
        root.addView(st)

        val paramTv = tv("", TEAL, 11f)
        root.addView(paramTv)
        val pairTv = tv("", PURPLE, 12f)
        root.addView(pairTv)

        // ① 极点零点复平面图
        root.addView(tv("① 极点（×红=AR）/ 零点（○绿=MA）在单位圆上", 11f, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val poleChart = LineChart(ctx).apply {
            description.isEnabled = false; setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true)
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM)
            axisRight.isEnabled = false
        }
        root.addView(poleChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)))

        // ② PSD 绕圆谱图（整圆 0..2π，上下半圆镜像对称）
        root.addView(tv("② 功率谱 PSD（整圆 ω=0..2π；实数信号上下半圆镜像，峰=主振荡频率）", 11f, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val psdTv = tv("", DARK, 11f)
        root.addView(psdTv)
        val psdChart = LineChart(ctx).apply {
            description.isEnabled = false; setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true)
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM); axisRight.isEnabled = false
        }
        root.addView(psdChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))

        // ③ 预测对比
        root.addView(tv("③ 预测对比（蓝=实际；橙细=AR拟合；紫细=ARMA拟合；橙/紫虚线=未来预测）", 11f, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val fcTv = tv("", DARK, 12f)
        root.addView(fcTv)
        val fcChart = LineChart(ctx).apply {
            description.isEnabled = false; setTouchEnabled(true); isDragEnabled = true; setScaleEnabled(true)
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM); axisRight.isEnabled = false
        }
        root.addView(fcChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))

        // ④ 滚动命中率
        root.addView(tv("④ 滚动回测方向命中率%（橙=AR，紫=ARMA，红虚线=50% 随机基准）", 11f, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val hitChart = LineChart(ctx).apply {
            description.isEnabled = false; xAxis.setPosition(XAxis.XAxisPosition.BOTTOM); axisRight.isEnabled = false
        }
        root.addView(hitChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160)))

        runBtn.setOnClickListener {
            val code = codeEt.text?.toString()?.trim().orEmpty()
            if (code.length != 6) { st.text = "请输入 6 位代码"; return@setOnClickListener }
            val pairMode = pairRb.isChecked
            val codeB = codeBEt.text?.toString()?.trim().orEmpty()
            if (pairMode && codeB.length != 6) { st.text = "价差套利需输入 B 腿 6 位代码"; return@setOnClickListener }
            val type = when { indexRb.isChecked -> 1; etfRb.isChecked -> 2; else -> 0 }
            val p = pEt.text?.toString()?.toIntOrNull() ?: 0
            val q = qEt.text?.toString()?.toIntOrNull() ?: 2
            runBtn.isEnabled = false
            pairTv.text = ""
            lifecycleScope.launch {
                try {
                    st.text = "⏳ 拉取数据…"
                    val hourly = hourlyBtn.isChecked
                    val fetcher = com.chin.stockanalysis.strategy.data.IntradayKlineFetcher(ctx)
                    suspend fun fetch(c: String) = withContext(Dispatchers.IO) {
                        val f = fullCode(c, type)
                        if (hourly) fetcher.fetchHourlyHistory(f, preferCache = true)
                        else fetcher.fetchDailyHistory(f, preferCache = true)
                    }
                    val barsA = fetch(code)
                    var dates = barsA.map { if (hourly) it.datetime else it.datetime.substring(0, 10) }
                    var closes = barsA.map { it.close }.toDoubleArray()
                    if (pairMode) {
                        val barsB = fetch(codeB)
                        val key: (String) -> String = { if (hourly) it else it.substring(0, 10) }
                        val mapB = barsB.associate { key(it.datetime) to it.close }
                        val pairs = barsA.mapNotNull { a -> mapB[key(a.datetime)]?.let { key(a.datetime) to (a.close / it) } }
                        if (pairs.size < 120) {
                            st.text = "⚠️ 价差对齐后数据不足（${pairs.size} 根，需≥120）"
                            return@launch
                        }
                        dates = pairs.map { it.first }
                        closes = pairs.map { it.second }.toDoubleArray()   // 比值序列，引擎内 ln = 价差
                    } else if (barsA.size < 120) {
                        st.text = "⚠️ 数据不足（${barsA.size} 根，需≥120）——单票页先跑一次可建缓存"
                        return@launch
                    }
                    st.text = "⏳ ARMA 谱估计中…"
                    val r = withContext(Dispatchers.Default) {
                        ArmaEngine.analyze(dates, closes, horizon = 5, pIn = p, qIn = q)
                    }
                    if (r == null) { st.text = "⚠️ 分析失败"; return@launch }
                    st.text = "✅ 完成（${closes.size} 根）"
                    renderParam(paramTv, r)
                    renderPoles(poleChart, r)
                    renderPsd(psdChart, psdTv, r)
                    renderForecast(fcChart, fcTv, r)
                    renderHit(hitChart, r)
                    if (pairMode) renderPair(pairTv, r, closes, code, codeB) 
                } catch (e: Exception) {
                    st.text = "❌ ${e.message}"
                } finally {
                    runBtn.isEnabled = true
                }
            }
        }
        return scroll
    }

    /** 0=个股（6/5/9→sh） 1=指数（399→sz 其余→sh） 2=ETF（5→sh 其余→sz） */
    private fun fullCode(code: String, type: Int): String {
        val pure = code.removePrefix("sh").removePrefix("sz")
        return when (type) {
            1 -> if (pure.startsWith("399")) "sz$pure" else "sh$pure"
            2 -> if (pure[0] == '5') "sh$pure" else "sz$pure"
            else -> if (pure[0] in "659") "sh$pure" else "sz$pure"
        }
    }

    /** 价差套利结论：价差 z-score + 回归周期（PSD 主峰）+ 预测方向 → 腿间操作建议 */
    private fun renderPair(tv: TextView, r: ArmaEngine.Result, ratio: DoubleArray, codeA: String, codeB: String) {
        val win = ratio.takeLast(60)
        val mu = win.average()
        val sd = kotlin.math.sqrt(win.sumOf { (it - mu) * (it - mu) } / win.size).coerceAtLeast(1e-12)
        val z = (ratio.last() - mu) / sd
        val peakPass = r.arma ?: r.ar
        val peakIdx = (1 until peakPass.psd.size).maxByOrNull { peakPass.psd[it] } ?: 1
        val peak = peakPass.psdW[peakIdx]
        val period = if (peak > 1e-6) 2 * PI / peak else Double.POSITIVE_INFINITY
        val f1 = r.ar.fcPct.getOrNull(0) ?: 0.0
        val dir = when {
            f1 > 0.15 -> "多A空B（A强）"
            f1 < -0.15 -> "多B空A（B强）"
            else -> "价差走平，不动"
        }
        val zNote = when {
            z > 2.0 -> "价差处历史极端高位，回归压力大→提前布局反向"
            z < -2.0 -> "价差处历史极端低位，回归压力大→提前布局反向"
            else -> "价差在正常带内"
        }
        tv.text = "🧮 价差 A/B = %.4f（z=%+.2f，%s）\n".format(ratio.last(), z, zNote) +
            "回归周期 ≈ %.1f 根（PSD 主峰）；预测 T+1 价差 %+.2f%% → %s".format(period, f1, dir) +
            "\n（$codeA ÷ $codeB；价差预测涨=A 相对强，跌=B 相对强）"
    }

    private fun renderParam(tv: TextView, r: ArmaEngine.Result) {
        val sb = StringBuilder()
        fun fmt(m: ArmaEngine.Pass) {
            sb.append("【${m.label}】 σ²=%.4f 极点%d 零点%d\n".format(m.model.sigma2, m.model.poles.size, m.model.zeros.size))
            sb.append("  AR: ").append(m.model.ar.joinToString(" ") { "%.3f".format(it) }).append("\n")
            if (m.model.ma.isNotEmpty()) sb.append("  MA: ").append(m.model.ma.joinToString(" ") { "%.3f".format(it) }).append("\n")
            sb.append("  命中%: ").append(m.rollHit.joinToString(" ") { "%.0f".format(it) })
            sb.append("  涨幅相关: ").append(m.corrPctByH.joinToString(" ") { "%.2f".format(it) }).append("\n")
        }
        fmt(r.ar)
        r.arma?.let { fmt(it) }
        tv.text = sb.toString()
    }

    private fun renderPoles(chart: LineChart, r: ArmaEngine.Result) {
        val sets = ArrayList<LineDataSet>()
        // 单位圆拆上/下半弧，各自按 x 升序（MPAndroidChart 按 x 二分查找区间，乱序会丢段）
        val upper = (0..40).map {
            val a = PI * it / 40
            Entry(cos(a).toFloat(), sin(a).toFloat())
        }.sortedBy { it.x }
        val lower = (0..40).map {
            val a = PI + PI * it / 40
            Entry(cos(a).toFloat(), sin(a).toFloat())
        }.sortedBy { it.x }
        listOf(upper, lower).forEachIndexed { i, arc ->
            sets.add(LineDataSet(arc, if (i == 0) "单位圆" else "").apply {
                color = GRAY; lineWidth = 1f; setDrawCircles(false); setDrawValues(false)
            })
        }
        // 极点（×红，含共轭对，按 x 排序）
        val poles = ArrayList<Entry>()
        r.arma?.model?.poles?.forEach { poles.add(Entry(it.re.toFloat(), it.im.toFloat())) }
        r.ar.model.poles.forEach { poles.add(Entry(it.re.toFloat(), it.im.toFloat())) }
        poles.sortBy { it.x }
        if (poles.isNotEmpty()) {
            sets.add(LineDataSet(poles, "极点").apply {
                color = RED; lineWidth = 0f
                setDrawCircles(true); circleRadius = 5f; setCircleColors(listOf(RED))
                setDrawCircleHole(true); circleHoleRadius = 2.5f
                setDrawValues(false)
            })
        }
        // 零点（○绿）
        val zeros = ArrayList<Entry>()
        r.arma?.model?.zeros?.forEach { zeros.add(Entry(it.re.toFloat(), it.im.toFloat())) }
        zeros.sortBy { it.x }
        if (zeros.isNotEmpty()) {
            sets.add(LineDataSet(zeros, "零点").apply {
                color = GREEN; lineWidth = 0f
                setDrawCircles(true); circleRadius = 5f; setCircleColors(listOf(GREEN))
                setDrawCircleHole(true); circleHoleColor = Color.WHITE; circleHoleRadius = 2.5f
                setDrawValues(false)
            })
        }
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        // 固定对称坐标区间，圆才不会被屏幕宽高拉成椭圆
        chart.xAxis.axisMinimum = -1.3f; chart.xAxis.axisMaximum = 1.3f
        chart.axisLeft.axisMinimum = -1.3f; chart.axisLeft.axisMaximum = 1.3f
        chart.invalidate()
    }

    private fun renderPsd(chart: LineChart, tv: TextView, r: ArmaEngine.Result) {
        // 整圆显示：实数信号 PSD(ω)=PSD(2π-ω)，把 0..π 镜像到 π..2π
        // 合并后必须按 x 升序——MPAndroidChart 按 x 二分查找可见区间，乱序会丢镜像半段
        fun fullCircle(w: List<Double>, p: List<Double>): List<Entry> {
            val e = w.zip(p).mapTo(ArrayList()) { Entry(it.first.toFloat(), it.second.toFloat()) }
            for (i in w.size - 2 downTo 1) e.add(Entry((2 * PI - w[i]).toFloat(), p[i].toFloat()))
            return e.sortedBy { it.x }
        }
        val sets = ArrayList<LineDataSet>()
        val arSet = LineDataSet(fullCircle(r.ar.psdW, r.ar.psd), "AR PSD").apply {
            color = ORANGE; lineWidth = 2f; setDrawCircles(false); setDrawValues(false)
        }
        sets.add(arSet)
        r.arma?.let { a ->
            sets.add(LineDataSet(fullCircle(a.psdW, a.psd), "ARMA PSD").apply {
                color = PURPLE; lineWidth = 2f; setDrawCircles(false); setDrawValues(false)
            })
        }
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = when {
                value < 0.5f -> "0"; value < PI + 0.5 && value > PI - 0.5 -> "π"
                value > 2 * PI - 0.5 -> "2π"; else -> ""
            }
        }
        // 主峰：跳过 ω=0 的 DC 点（DC=趋势项，永远最大，周期 Infinity 无意义），报非 DC 次峰
        val pass = r.arma ?: r.ar
        val peakIdx = (1 until pass.psd.size).maxByOrNull { pass.psd[it] } ?: 0
        val w = pass.psdW[peakIdx]
        val periodBars = if (w > 1e-6) 2 * PI / w else Double.POSITIVE_INFINITY
        val dcNote = if ((pass.psd.getOrNull(0) ?: 0.0) > pass.psd[peakIdx]) "DC 主导（趋势项）；非DC " else ""
        tv.text = dcNote + "主峰 ω=%.3f rad/bar → 周期 %.1f 根 bar（π 右侧为镜像）".format(w, periodBars)
        chart.invalidate()
    }

    private fun renderForecast(chart: LineChart, tv: TextView, r: ArmaEngine.Result) {
        val p = r.ar
        val n = p.actual.size
        val sets = ArrayList<LineDataSet>()
        sets.add(LineDataSet(p.actual.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }, "实际").apply {
            color = BLUE; lineWidth = 2f; setDrawCircles(false); setDrawValues(false)
        })
        sets.add(LineDataSet(p.fitted.mapIndexedNotNull { i, v -> v?.let { Entry(i.toFloat(), it.toFloat()) } }, "AR拟合").apply {
            color = ORANGE; lineWidth = 1.3f; setDrawCircles(false); setDrawValues(false)
        })
        r.arma?.let { a ->
            sets.add(LineDataSet(a.fitted.mapIndexedNotNull { i, v -> v?.let { Entry(i.toFloat(), it.toFloat()) } }, "ARMA拟合").apply {
                color = PURPLE; lineWidth = 1.3f; setDrawCircles(false); setDrawValues(false)
            })
        }
        // 未来预测
        val eA = ArrayList<Entry>()
        eA.add(Entry((n - 1).toFloat(), r.lastPrice.toFloat()))
        p.fcPrices.forEachIndexed { i, v -> eA.add(Entry((n + i).toFloat(), v.toFloat())) }
        sets.add(LineDataSet(eA, "AR预测").apply {
            color = ORANGE; lineWidth = 2.2f; enableDashedLine(10f, 6f, 0f)
            setDrawCircles(true); circleRadius = 3.5f; setCircleColors(listOf(ORANGE))
            setDrawCircleHole(false); setDrawValues(true); valueTextSize = 9f; valueTextColor = ORANGE
        })
        r.arma?.let { a ->
            val eM = ArrayList<Entry>()
            eM.add(Entry((n - 1).toFloat(), r.lastPrice.toFloat()))
            a.fcPrices.forEachIndexed { i, v -> eM.add(Entry((n + i).toFloat(), v.toFloat())) }
            sets.add(LineDataSet(eM, "ARMA预测").apply {
                color = PURPLE; lineWidth = 2.2f; enableDashedLine(10f, 6f, 0f)
                setDrawCircles(true); circleRadius = 3.5f; setCircleColors(listOf(PURPLE))
                setDrawCircleHole(false); setDrawValues(true); valueTextSize = 9f; valueTextColor = PURPLE
            })
        }
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt()
                if (i in p.histDates.indices) return p.histDates[i].take(5)
                return "T+" + (i - n + 1)
            }
        }
        // 预测文本
        val baseDt = p.histDates.lastOrNull().orEmpty()
        val sb = StringBuilder("现价 %.2f".format(r.lastPrice))
        if (baseDt.isNotBlank()) sb.append("（$baseDt）")
        sb.append(" → 未来 ${p.fcPrices.size} 根预测价：\n")
        p.fcPrices.indices.forEach { i ->
            sb.append("  T+%d  AR: %.2f（%+.2f%%）".format(i + 1, p.fcPrices[i], p.fcPct[i]))
            r.arma?.let { a ->
                if (i < a.fcPrices.size) sb.append("  ARMA: %.2f（%+.2f%%）".format(a.fcPrices[i], a.fcPct[i]))
            }
            sb.append("\n")
        }
        tv.text = sb.toString().trimEnd()
        chart.invalidate()
    }

    private fun renderHit(chart: LineChart, r: ArmaEngine.Result) {
        val sets = ArrayList<LineDataSet>()
        sets.add(LineDataSet(r.ar.rollHit.indices.map { Entry(it.toFloat(), r.ar.rollHit[it].toFloat()) }, "AR命中").apply {
            color = ORANGE; lineWidth = 2f; setDrawCircles(true); circleRadius = 3f; setCircleColors(listOf(ORANGE)); setDrawValues(false)
        })
        r.arma?.let { a ->
            sets.add(LineDataSet(a.rollHit.indices.map { Entry(it.toFloat(), a.rollHit[it].toFloat()) }, "ARMA命中").apply {
                color = PURPLE; lineWidth = 2f; setDrawCircles(true); circleRadius = 3f; setCircleColors(listOf(PURPLE)); setDrawValues(false)
            })
        }
        sets.add(LineDataSet(listOf(Entry(0f, 50f), Entry(r.ar.rollHit.size.toFloat() - 1, 50f)), "50%").apply {
            color = RED; lineWidth = 1f; enableDashedLine(8f, 6f, 0f); setDrawCircles(false); setDrawValues(false)
        })
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.invalidate()
    }
}
