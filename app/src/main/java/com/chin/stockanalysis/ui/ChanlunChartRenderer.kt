package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Paint
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.ScatterData
import com.github.mikephil.charting.data.ScatterDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import org.json.JSONObject

/**
 * 缠论结构叠加渲染器
 *
 * 输入：PC 端 `_chanlun_engine.py` 输出的结构 JSON（klines + bi + zs + bsp）
 * 输出：CombinedChart（K线蜡烛 + 笔折线 + 中枢框 + 买卖点圆点）
 *
 * 对齐约定：klu_idx == klines 数组下标（chan.py 注入的是原始K线序列）
 */
object ChanlunChartRenderer {

    private val RED = Color.parseColor("#E53935")
    private val GREEN = Color.parseColor("#43A047")
    private val BLUE = Color.parseColor("#1976D2")
    private val ORANGE = Color.parseColor("#E65100")
    private val GRAY = Color.parseColor("#999999")
    private val PURPLE = Color.parseColor("#8E24AA")
    private val TEAL = Color.parseColor("#00897B")

    /** 渲染 APP 本地引擎结果（ChanlunEngine.Structure）。 */
    fun render(chart: CombinedChart, st: com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Structure,
               bars: Int = 250, showBi: Boolean = true, showZs: Boolean = true, showBsp: Boolean = true,
               showSP: Boolean = true, showFib: Boolean = false, showFx: Boolean = false) {
        val total = st.bars.size
        val offset = maxOf(0, total - bars)
        val n = total - offset
        if (n < 5) return
        chart.axisLeft.removeAllLimitLines()   // 防重渲染累积

        // ── K 线蜡烛 ──
        val entries = ArrayList<CandleEntry>(n)
        val labels = ArrayList<String>(n)
        for (i in 0 until n) {
            val k = st.bars[offset + i]
            labels.add(k.time)
            entries.add(CandleEntry(i.toFloat(), k.high.toFloat(), k.low.toFloat(),
                k.open.toFloat(), k.close.toFloat()))
        }
        val cds = CandleDataSet(entries, "K线").apply {
            color = GRAY; shadowColor = GRAY; shadowWidth = 0.8f
            increasingColor = RED; increasingPaintStyle = Paint.Style.FILL
            decreasingColor = GREEN; decreasingPaintStyle = Paint.Style.FILL
            neutralColor = GRAY; setDrawValues(false)
            setDrawHighlightIndicators(true)
            highLightColor = GRAY
        }

        // ── 笔折线：每根笔画 begin→end 线段，相邻笔端点重合自然连成折线 ──
        val lineData = LineData()
        if (showBi) for (j in st.bi.indices) {
            val b = st.bi[j]
            val x0 = b.beginIdx - offset; val x1 = b.endIdx - offset
            if (x1 < 0 || x0 >= n) continue
            val pts = arrayListOf(
                Entry(maxOf(0, x0).toFloat(), b.beginPrice.toFloat()),
                Entry(minOf(n - 1, x1).toFloat(), b.endPrice.toFloat()))
            val up = b.dir == com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.Dir.UP
            lineData.addDataSet(LineDataSet(pts, "bi$j").apply {
                color = if (up) RED else GREEN
                lineWidth = if (b.sure) 2f else 1f
                setDrawCircles(false); setDrawValues(false)
                isHighlightEnabled = false
                if (!b.sure) enableDashedLine(8f, 4f, 0f)
            })
        }

        // ── 中枢框 ──
        if (showZs) for (i in st.zs.indices) {
            val z = st.zs[i]
            val x0 = maxOf(0f, (z.beginIdx - offset).toFloat())
            val x1 = minOf((n - 1).toFloat(), (z.endIdx - offset).toFloat())
            if (x1 < 0 || x0 >= n || x1 <= x0) continue
            for ((v, tag) in listOf(z.low.toFloat() to "lo$i", z.high.toFloat() to "hi$i")) {
                lineData.addDataSet(LineDataSet(arrayListOf(Entry(x0, v), Entry(x1, v)), "zs$tag").apply {
                    color = BLUE; lineWidth = 1.2f
                    setDrawCircles(false); setDrawValues(false)
                    isHighlightEnabled = false
                    enableDashedLine(10f, 5f, 0f)
                })
            }
        }

        // ── 买卖点圆点 ──
        val scatterData = ScatterData()
        if (showBsp) {
            val buys = ArrayList<Entry>(); val sells = ArrayList<Entry>()
            for (p in st.bsp) {
                val x = p.idx - offset
                if (x < 0 || x >= n) continue
                if (p.isBuy) buys.add(Entry(x.toFloat(), (p.price * 0.995).toFloat()))
                else sells.add(Entry(x.toFloat(), (p.price * 1.005).toFloat()))
            }
            if (buys.isNotEmpty()) scatterData.addDataSet(ScatterDataSet(buys, "买点").apply {
                color = RED; scatterShapeSize = 28f; setDrawValues(false); isHighlightEnabled = false })
            if (sells.isNotEmpty()) scatterData.addDataSet(ScatterDataSet(sells, "卖点").apply {
                color = GREEN; scatterShapeSize = 28f; setDrawValues(false); isHighlightEnabled = false })
        }

        // ── 分型三角（顶=紫▽在上方，底=青△在下方；逐K回放时看生成过程） ──
        if (showFx && st.fx.isNotEmpty()) {
            val tops = ArrayList<Entry>(); val bots = ArrayList<Entry>()
            for (f in st.fx) {
                val mg = st.merged.getOrNull(f.mergedIdx) ?: continue
                val x = mg.endIdx - offset
                if (x < 0 || x >= n) continue
                if (f.type == com.chin.stockanalysis.strategy.chanlun.ChanlunEngine.FxType.TOP)
                    tops.add(Entry(x.toFloat(), (f.price * 1.004).toFloat()))
                else bots.add(Entry(x.toFloat(), (f.price * 0.996).toFloat()))
            }
            if (tops.isNotEmpty()) scatterData.addDataSet(ScatterDataSet(tops, "顶分型").apply {
                color = PURPLE; scatterShapeSize = 16f; setDrawValues(false); isHighlightEnabled = false
                setScatterShape(com.github.mikephil.charting.charts.ScatterChart.ScatterShape.CHEVRON_DOWN) })
            if (bots.isNotEmpty()) scatterData.addDataSet(ScatterDataSet(bots, "底分型").apply {
                color = TEAL; scatterShapeSize = 16f; setDrawValues(false); isHighlightEnabled = false
                setScatterShape(com.github.mikephil.charting.charts.ScatterChart.ScatterShape.CHEVRON_UP) })
        }

        applyToChart(chart, cds, lineData, scatterData, labels, n, bars)
        // 十字光标浮窗（本地引擎路径）
        chart.marker = ChanlunMarkerView(chart.context, st, offset)

        // ── 支撑/压力线（最近中枢 ZD/ZG，Y轴 LimitLine 带标签） ──
        if (showSP && st.zs.isNotEmpty()) {
            val z = st.zs.last()
            addLimitLine(chart, z.high.toFloat(), "压力 ${fmt2(z.high)}", RED)
            addLimitLine(chart, z.low.toFloat(), "支撑 ${fmt2(z.low)}", BLUE)
        }
        // ── 黄金分割回撤位（基于最后一根实笔的反向摆动） ──
        if (showFib && st.bi.size >= 2) {
            // 用最近两根笔构成摆动区间
            val b1 = st.bi[st.bi.size - 2]; val b2 = st.bi[st.bi.size - 1]
            val hi = maxOf(b1.beginPrice, b1.endPrice, b2.beginPrice, b2.endPrice)
            val lo = minOf(b1.beginPrice, b1.endPrice, b2.beginPrice, b2.endPrice)
            val range = hi - lo
            if (range > 0) {
                for (ratio in listOf(0.382, 0.5, 0.618)) {
                    val v = (hi - range * ratio).toFloat()
                    addLimitLine(chart, v, "fib${(ratio * 1000).toInt() / 10.0}% ${fmt2(v.toDouble())}", ORANGE, dashed = true)
                }
            }
        }
    }

    private fun addLimitLine(chart: CombinedChart, y: Float, label: String, color: Int, dashed: Boolean = false) {
        val ll = com.github.mikephil.charting.components.LimitLine(y, label).apply {
            lineColor = color; lineWidth = 1f
            textColor = color; textSize = 9f
            labelPosition = com.github.mikephil.charting.components.LimitLine.LimitLabelPosition.RIGHT_TOP
            if (dashed) enableDashedLine(10f, 5f, 0f)
        }
        chart.axisLeft.addLimitLine(ll)
    }

    private fun fmt2(v: Double) = String.format("%.2f", v)

    /** 渲染 PC 端结构 JSON（assets 预生成版，离线兜底）。 */
    fun render(chart: CombinedChart, st: JSONObject, bars: Int = 250) {
        val klines = st.optJSONArray("klines") ?: return
        val bi = st.optJSONArray("bi")
        val zs = st.optJSONArray("zs")
        val bsp = st.optJSONArray("bsp")

        val total = klines.length()
        val offset = maxOf(0, total - bars)   // 只画最近 bars 根
        val n = total - offset
        if (n < 5) return

        // ── K 线蜡烛 ──
        val entries = ArrayList<CandleEntry>(n)
        val labels = ArrayList<String>(n)
        for (i in 0 until n) {
            val k = klines.getJSONObject(offset + i)
            labels.add(k.optString("time"))
            entries.add(CandleEntry(i.toFloat(),
                k.optDouble("high").toFloat(), k.optDouble("low").toFloat(),
                k.optDouble("open").toFloat(), k.optDouble("close").toFloat()))
        }
        val cds = CandleDataSet(entries, "K线").apply {
            color = GRAY
            shadowColor = GRAY; shadowWidth = 0.8f
            increasingColor = RED; increasingPaintStyle = Paint.Style.FILL
            decreasingColor = GREEN; decreasingPaintStyle = Paint.Style.FILL
            neutralColor = GRAY
            setDrawValues(false)
        }
        val candleData = CandleData(cds)

        // ── 笔折线（每根笔 begin→end，红涨绿跌） ──
        val lineData = LineData()
        if (bi != null) {
            for (j in 0 until bi.length()) {
                val b = bi.getJSONObject(j)
                val x0 = b.optInt("begin_klu_idx") - offset
                val x1 = b.optInt("end_klu_idx") - offset
                if (x1 < 0 || x0 >= n) continue
                val y0 = b.optDouble("begin_val").toFloat()
                val y1 = b.optDouble("end_val").toFloat()
                val pts = arrayListOf(
                    Entry(maxOf(0, x0).toFloat(), y0),
                    Entry(minOf(n - 1, x1).toFloat(), y1))
                val up = b.optString("dir") == "up"
                val sure = b.optBoolean("is_sure", false)
                lineData.addDataSet(LineDataSet(pts, "bi$j").apply {
                    color = if (up) RED else GREEN
                    lineWidth = if (sure) 2f else 1f
                    setDrawCircles(false); setDrawValues(false)
                    if (!sure) enableDashedLine(8f, 4f, 0f)
                })
            }
        }

        // ── 中枢框（蓝色上下沿虚线） ──
        if (zs != null) {
            for (i in 0 until zs.length()) {
                val z = zs.getJSONObject(i)
                val x0 = (z.optInt("begin_klu_idx") - offset).toFloat()
                val x1 = (z.optInt("end_klu_idx") - offset).toFloat()
                if (x1 < 0 || x0 >= n) continue
                val lo = z.optDouble("low").toFloat()
                val hi = z.optDouble("high").toFloat()
                val cx0 = maxOf(0f, x0); val cx1 = minOf((n - 1).toFloat(), x1)
                for ((v, tag) in listOf(lo to "lo$i", hi to "hi$i")) {
                    lineData.addDataSet(LineDataSet(arrayListOf(Entry(cx0, v), Entry(cx1, v)), "zs$tag").apply {
                        color = BLUE; lineWidth = 1.2f
                        setDrawCircles(false); setDrawValues(false)
                        isHighlightEnabled = false
                        enableDashedLine(10f, 5f, 0f)
                    })
                }
            }
        }

        // ── 买卖点圆点（买=红上三角▲，卖=绿下三角▼） ──
        val scatterData = ScatterData()
        if (bsp != null) {
            val buys = ArrayList<Entry>()
            val sells = ArrayList<Entry>()
            for (i in 0 until bsp.length()) {
                val p = bsp.getJSONObject(i)
                val x = p.optInt("klu_idx") - offset
                if (x < 0 || x >= n) continue
                val price = p.optDouble("price").toFloat()
                if (p.optBoolean("is_buy")) buys.add(Entry(x.toFloat(), price * 0.995f))
                else sells.add(Entry(x.toFloat(), price * 1.005f))
            }
            if (buys.isNotEmpty()) {
                scatterData.addDataSet(ScatterDataSet(buys, "买点").apply {
                    color = RED; scatterShapeSize = 28f; setDrawValues(false); isHighlightEnabled = false
                })
            }
            if (sells.isNotEmpty()) {
                scatterData.addDataSet(ScatterDataSet(sells, "卖点").apply {
                    color = GREEN; scatterShapeSize = 28f; setDrawValues(false); isHighlightEnabled = false
                })
            }
        }

        // ── 组装 ──
        applyToChart(chart, cds, lineData, scatterData, labels, n, bars)
    }

    /** 公共收尾：组装 CombinedData + 坐标轴配置。 */
    private fun applyToChart(chart: CombinedChart, cds: CandleDataSet, lineData: LineData,
                             scatterData: ScatterData, labels: List<String>, n: Int, bars: Int) {
        val cd = CombinedData()
        cd.setData(CandleData(cds))
        if (lineData.dataSetCount > 0) cd.setData(lineData)
        if (scatterData.dataSetCount > 0) cd.setData(scatterData)
        chart.data = cd

        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(true)
            setScaleEnabled(true)
            isDragEnabled = true
            setPinchZoom(true)
            setDrawGridBackground(false)
            setMaxVisibleValueCount(60)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textColor = GRAY; textSize = 8f
                // 右侧留白：让最右一根K线可以拖进画面中部，不贴边界
                axisMinimum = -0.5f
                axisMaximum = (n - 1) + 8.5f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val idx = value.toInt()
                        if (idx < 0 || idx >= n) return ""
                        val t = labels[idx]
                        return if (idx % maxOf(1, n / 6) == 0) t.substring(5, minOf(10, t.length)) else ""
                    }
                }
            }
            axisLeft.apply {
                textColor = GRAY; textSize = 9f
                setDrawGridLines(true); gridColor = Color.parseColor("#EEEEEE")
            }
            axisRight.isEnabled = false
            extraRightOffset = 28f
            setVisibleXRangeMaximum(bars.toFloat())
            moveViewToX(0f)

            // 双击清除高亮 → 浮窗卡片消失；单击/拖动自动重新高亮 → 卡片出现
            setOnChartGestureListener(object : com.github.mikephil.charting.listener.OnChartGestureListener {
                override fun onChartGestureStart(me: android.view.MotionEvent?, lastPerformedGesture: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?) {}
                override fun onChartGestureEnd(me: android.view.MotionEvent?, lastPerformedGesture: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?) {}
                override fun onChartLongPressed(me: android.view.MotionEvent?) {}
                override fun onChartDoubleTapped(me: android.view.MotionEvent?) {
                    this@apply.highlightValue(null, true)
                    this@apply.invalidate()
                }
                override fun onChartSingleTapped(me: android.view.MotionEvent?) {}
                override fun onChartFling(me1: android.view.MotionEvent?, me2: android.view.MotionEvent?, velocityX: Float, velocityY: Float) {}
                override fun onChartScale(me: android.view.MotionEvent?, scaleX: Float, scaleY: Float) {}
                override fun onChartTranslate(me: android.view.MotionEvent?, dX: Float, dY: Float) {}
            })
            invalidate()
        }
    }
}
