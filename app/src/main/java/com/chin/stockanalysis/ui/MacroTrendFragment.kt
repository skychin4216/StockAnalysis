package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import com.chin.stockanalysis.strategy.ssa.SsaEngine
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 🌊 大势分析 — 股债大类资产对比
 *
 * 第一期：沪深300 vs 二级债基（易方达稳健收益A 110007 为二级债基代表）的归一化走势对比。
 * 数据源：
 *  - 沪深300：东财/新浪日线（价格指数，未含分红再投）
 *  - 二级债基：天天基金单位净值（110007 易方达稳健收益债券A，2005年成立的老牌二级债基）
 *
 * 归一化口径：两序列取共同起始日，各自首日收盘/净值 = 100。
 */
class MacroTrendFragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val BLUE = Color.parseColor("#1976D2")
    private val GRAY = Color.parseColor("#999999")
    private val TEAL = Color.parseColor("#00897B")

    // 对比标的
    private data class Asset(val label: String, val code: String, val color: Int)
    private val assets = listOf(
        Asset("沪深300", "sh000300", RED),
        Asset("二级债基(易方达稳健收益A)", "110007", BLUE)
    )

    private lateinit var statusTv: TextView
    private lateinit var chart: LineChart
    private lateinit var statBox: LinearLayout
    /** 十字光标点选小卡片 */
    private lateinit var tipTv: TextView
    /** 当前图数据（供十字光标回调取日期/数值） */
    private var chartDates: List<String> = emptyList()
    private var chartValues: List<List<Double>> = emptyList()
    /** 最近一次加载失败的具体原因（分数据源记录） */
    private var lastError = ""

    // ── 期现对冲区块（近一年沪深300 vs IF主力 vs 对冲组合 + cycle 副图）──
    private lateinit var hedgeChart: LineChart
    private lateinit var cycleChart: LineChart
    private lateinit var hedgeStatusTv: TextView
    private lateinit var hedgeStatTv: TextView
    private lateinit var hedgeTipTv: TextView
    private var hedgeDates: List<String> = emptyList()
    /** [0]=现货归一化 [1]=IF主力归一化 [2]=对冲组合归一化 */
    private var hedgeValues: List<List<Double>> = emptyList()
    private var hedgeCycle: DoubleArray = DoubleArray(0)
    private var hedgeErr = ""

    // ── 宽基ETF 资金代理区块（主力 vs 散户小单 累计净流入；公开数据无机构/个人日频拆分，用四档资金流近似）──
    private lateinit var etfFlowChart: LineChart
    private lateinit var etfFlowStatusTv: TextView
    private lateinit var etfFlowStatTv: TextView
    private lateinit var etfFlowTipTv: TextView
    private var etfFlowDates: List<String> = emptyList()
    /** [0]=主力累计(亿元) [1]=散户(小单)累计(亿元) */
    private var etfFlowValues: List<List<Double>> = emptyList()
    /** 当日净额（主力, 小单），亿元 */
    private var etfFlowDaily: List<Pair<Double, Double>> = emptyList()

    /** 宽基ETF组合：上证50/沪深300/中证500/科创50/创业板——大资金对大势态度的代表篮子 */
    private val etfBasket = listOf(
        "1.510050" to "上证50ETF",
        "1.510300" to "沪深300ETF",
        "1.510500" to "中证500ETF",
        "1.588000" to "科创50ETF",
        "0.159915" to "创业板ETF"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }

        box.addView(tv("🌊 大势分析", 15f, bold = true))
        box.addView(tv("大类资产走势对比（归一化，首日=100），观察股债跷跷板与长期收益差", 10f, GRAY))

        // 刷新按钮 + 行业板块轮动入口
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btn = android.widget.Button(ctx).apply { text = "刷新数据"; textSize = 12f }
        btnRow.addView(btn)
        val btnRotation = android.widget.Button(ctx).apply {
            text = "🔄 行业板块轮动"; textSize = 12f
            setOnClickListener {
                val containerId = (view?.parent as? ViewGroup)?.id ?: return@setOnClickListener
                parentFragmentManager.beginTransaction()
                    .replace(containerId, SectorRotationFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
        btnRow.addView(btnRotation, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        box.addView(btnRow)

        statusTv = tv("点击「刷新数据」加载…", 11f, GRAY)
        box.addView(statusTv)

        chart = LineChart(ctx).apply {
            setBackgroundColor(Color.WHITE)
            description.isEnabled = false
            setTouchEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            axisRight.isEnabled = false
            xAxis.setDrawGridLines(false)
            axisLeft.setDrawGridLines(true)
            axisLeft.gridColor = Color.parseColor("#EEEEEE")
            legend.orientation = Legend.LegendOrientation.HORIZONTAL
            legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
            legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            legend.setDrawInside(false)
            legend.textSize = 11f
            setExtraOffsets(5f, 10f, 5f, 5f)
        }
        box.addView(chart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))

        // 十字光标点选小卡片（默认隐藏，点图出现）
        tipTv = TextView(ctx).apply {
            textSize = 12f; setTextColor(Color.parseColor("#333333"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        val tipCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#DDDDDD"))
            }
            setPadding(0, 0, 0, 0)
            visibility = View.GONE
            addView(tipTv)
        }
        tipTv.tag = tipCard
        box.addView(tipCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // 十字光标：3dp 深灰粗线 + 点选弹小卡片
        chart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: com.github.mikephil.charting.highlight.Highlight?) {
                val idx = e?.x?.toInt() ?: return
                if (idx !in chartDates.indices) return
                val sb = StringBuilder("📅 ${chartDates[idx]}")
                assets.forEachIndexed { i, a ->
                    val v = chartValues.getOrNull(i)?.getOrNull(idx) ?: return@forEachIndexed
                    val chg = v - 100.0
                    sb.append("\n${a.label}：${"%.1f".format(v)}（累计 ${"%+.1f".format(chg)}%）")
                }
                tipTv.text = sb.toString()
                (tipTv.tag as? View)?.visibility = View.VISIBLE
            }
            override fun onNothingSelected() {
                (tipTv.tag as? View)?.visibility = View.GONE
            }
        })

        statBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(statBox)

        // ── 🛡 期现对冲对比（近一年）+ cycle 副图 ──
        box.addView(tv("🛡 期现对冲对比（近一年）", 13f, bold = true))
        box.addView(tv("沪深300现货 vs IF主力连续 vs 对冲组合（多现货+空期货，归一化首日=100）；副图=现货 SSA cycle 分量", 10f, GRAY))
        hedgeStatusTv = tv("随「刷新数据」一并加载…", 10f, GRAY)
        box.addView(hedgeStatusTv)

        hedgeChart = LineChart(ctx).apply {
            setBackgroundColor(Color.WHITE)
            description.isEnabled = false
            setTouchEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            axisRight.isEnabled = false
            xAxis.setDrawGridLines(false)
            axisLeft.setDrawGridLines(true)
            axisLeft.gridColor = Color.parseColor("#EEEEEE")
            legend.orientation = Legend.LegendOrientation.HORIZONTAL
            legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
            legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            legend.setDrawInside(false)
            legend.textSize = 10f
        }
        box.addView(hedgeChart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(260)))

        // 点选小卡片（默认隐藏）
        hedgeTipTv = TextView(ctx).apply {
            textSize = 12f; setTextColor(Color.parseColor("#333333"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val hedgeTipCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#DDDDDD"))
            }
            visibility = View.GONE
            addView(hedgeTipTv)
        }
        hedgeTipTv.tag = hedgeTipCard
        box.addView(hedgeTipCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // cycle 副图（SSA 分解的周期分量，零轴；与主图 X 联动）
        box.addView(tv("cycle 分量（SSA，沪深300 收盘；边缘 L 根可信度低）", 10f, GRAY))
        cycleChart = LineChart(ctx).apply {
            setBackgroundColor(Color.WHITE)
            description.isEnabled = false
            setTouchEnabled(false)
            setDrawGridBackground(false)
            axisRight.isEnabled = false
            xAxis.setDrawGridLines(false)
            xAxis.setDrawLabels(false)
            axisLeft.setDrawGridLines(false)
            axisLeft.textSize = 8f
            axisLeft.textColor = GRAY
            legend.isEnabled = false
        }
        box.addView(cycleChart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))

        // 主副图联动：十字线延伸到副图 + 副图 X 跟随主图缩放平移（Y 各自独立）
        run {
            val aux = arrayOf<LineChart>(cycleChart)
            fun syncX() {
                val sv = FloatArray(9); hedgeChart.viewPortHandler.matrixTouch.getValues(sv)
                aux.forEach { dst ->
                    val dv = FloatArray(9); dst.viewPortHandler.matrixTouch.getValues(dv)
                    sv[android.graphics.Matrix.MSCALE_Y] = dv[android.graphics.Matrix.MSCALE_Y]
                    sv[android.graphics.Matrix.MTRANS_Y] = dv[android.graphics.Matrix.MTRANS_Y]
                    val m = android.graphics.Matrix(); m.setValues(sv)
                    dst.viewPortHandler.refresh(m, dst, true)
                }
            }
            hedgeChart.onChartGestureListener = object : com.github.mikephil.charting.listener.OnChartGestureListener {
                override fun onChartGestureStart(me: android.view.MotionEvent?, g: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?) {}
                override fun onChartGestureEnd(me: android.view.MotionEvent?, g: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?) {
                    // 点击/长按不算视口变化，不同步
                    if (g == com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture.SINGLE_TAP ||
                        g == com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture.LONG_PRESS) return
                    syncX()
                }
                override fun onChartLongPressed(me: android.view.MotionEvent?) {}
                override fun onChartDoubleTapped(me: android.view.MotionEvent?) = syncX()
                override fun onChartSingleTapped(me: android.view.MotionEvent?) {}
                override fun onChartFling(m1: android.view.MotionEvent?, m2: android.view.MotionEvent?, vx: Float, vy: Float) = syncX()
                override fun onChartScale(me: android.view.MotionEvent?, sx: Float, sy: Float) = syncX()
                override fun onChartTranslate(me: android.view.MotionEvent?, dx: Float, dy: Float) = syncX()
            }
            hedgeChart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: com.github.mikephil.charting.highlight.Highlight?) {
                    val ex = e ?: return
                    val idx = ex.x.toInt()
                    if (idx !in hedgeDates.indices) return
                    val sb = StringBuilder("📅 ${hedgeDates[idx]}")
                    val lbs = listOf("现货", "IF主力", "对冲组合")
                    hedgeValues.forEachIndexed { i, v ->
                        val x = v.getOrNull(idx) ?: return@forEachIndexed
                        sb.append("\n${lbs[i]}：${"%.1f".format(x)}（累计 ${"%+.1f".format(x - 100)}%）")
                    }
                    val cy = hedgeCycle.getOrNull(idx) ?: Double.NaN
                    if (!cy.isNaN()) sb.append("\ncycle：${"%+.1f".format(cy)}")
                    hedgeTipTv.text = sb.toString()
                    (hedgeTipTv.tag as? View)?.visibility = View.VISIBLE
                    cycleChart.highlightValue(ex.x, 0, false)
                }
                override fun onNothingSelected() {
                    (hedgeTipTv.tag as? View)?.visibility = View.GONE
                    cycleChart.highlightValue(null)
                }
            })
        }

        hedgeStatTv = tv("", 10f, GRAY)
        box.addView(hedgeStatTv)

        // ── 🏦 宽基ETF 资金代理：主力 vs 散户（小单）累计净流入 ──
        box.addView(tv("🏦 宽基ETF 资金代理（近250交易日）", 13f, bold = true))
        box.addView(tv("上证50/沪深300/中证500/科创50/创业板 5只ETF合计：主力(超大单+大单)累计净流入 vs 散户(小单)累计净流入。" +
            "注：机构/个人真实申购拆分仅基金半年报披露（半年2点），日频无公开数据，此处用四档资金流做代理口径。", 10f, GRAY))
        etfFlowStatusTv = tv("随「刷新数据」一并加载…", 10f, GRAY)
        box.addView(etfFlowStatusTv)

        etfFlowChart = LineChart(ctx).apply {
            setBackgroundColor(Color.WHITE)
            description.isEnabled = false
            setTouchEnabled(true); setPinchZoom(true); setDrawGridBackground(false)
            axisRight.isEnabled = false
            xAxis.setDrawGridLines(false)
            axisLeft.setDrawGridLines(true); axisLeft.gridColor = Color.parseColor("#EEEEEE")
            axisLeft.setDrawZeroLine(true); axisLeft.zeroLineColor = GRAY; axisLeft.zeroLineWidth = 1.2f
            legend.orientation = Legend.LegendOrientation.HORIZONTAL
            legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
            legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            legend.setDrawInside(false); legend.textSize = 10f
        }
        box.addView(etfFlowChart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))

        etfFlowTipTv = TextView(ctx).apply {
            textSize = 12f; setTextColor(Color.parseColor("#333333"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val etfFlowTipCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#DDDDDD"))
            }
            visibility = View.GONE
            addView(etfFlowTipTv)
        }
        etfFlowTipTv.tag = etfFlowTipCard
        box.addView(etfFlowTipCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        etfFlowChart.setOnChartValueSelectedListener(object : com.github.mikephil.charting.listener.OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: com.github.mikephil.charting.highlight.Highlight?) {
                val idx = e?.x?.toInt() ?: return
                if (idx !in etfFlowDates.indices) return
                val (dm, ds) = etfFlowDaily.getOrNull(idx) ?: return
                etfFlowTipTv.text = "📅 ${etfFlowDates[idx]}\n当日：主力 %+.1f 亿 / 散户 %+.1f 亿\n累计：主力 %+.1f 亿 / 散户 %+.1f 亿"
                    .format(dm, ds, etfFlowValues[0][idx], etfFlowValues[1][idx])
                (etfFlowTipTv.tag as? View)?.visibility = View.VISIBLE
            }
            override fun onNothingSelected() {
                (etfFlowTipTv.tag as? View)?.visibility = View.GONE
            }
        })

        etfFlowStatTv = tv("", 10f, GRAY)
        box.addView(etfFlowStatTv)

        box.addView(hint("说明：沪深300为价格指数（未含分红再投）；债券端以易方达稳健收益债券A（110007）作为二级债基代表。" +
            "归一化起点=100，数据仅为研究展示，不构成投资建议。"))

        scroll.addView(box)

        btn.setOnClickListener { refresh() }
        // 首次自动加载
        refresh()

        return scroll
    }

    private fun refresh() {
        statusTv.text = "⏳ 加载沪深300 + 二级债基数据…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { loadAll() }
            if (result == null) {
                statusTv.text = "⚠️ 数据加载失败：$lastError"
                return@launch
            }
            renderChart(result.first, result.second)
            statusTv.text = "✅ 已更新（${result.first.dates.first()} ~ ${result.first.dates.last()}，共 ${result.first.dates.size} 个交易日）"
        }
        // 期现对冲对比独立加载（失败不影响主对比）
        hedgeStatusTv.text = "⏳ 加载沪深300 + IF主力连续…"
        viewLifecycleOwner.lifecycleScope.launch {
            val h = withContext(Dispatchers.IO) { loadHedge() }
            if (h == null) {
                hedgeStatusTv.text = "⚠️ 对冲数据加载失败：$hedgeErr"
                return@launch
            }
            renderHedge(h)
            hedgeStatusTv.text = "✅ 对冲对比已更新（${h.dates.first()} ~ ${h.dates.last()}，共 ${h.dates.size} 个交易日）"
        }
        // 宽基ETF 资金代理独立加载（失败不影响其它区块）
        etfFlowStatusTv.text = "⏳ 加载宽基ETF 资金流（5只合计）…"
        viewLifecycleOwner.lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { loadEtfFlow() }
            if (f == null) {
                etfFlowStatusTv.text = "⚠️ ETF资金流加载失败：$etfFlowErr"
                return@launch
            }
            renderEtfFlow(f)
            etfFlowStatusTv.text = "✅ ETF资金代理已更新（${f.dates.first()} ~ ${f.dates.last()}，共 ${f.dates.size} 个交易日）"
        }
    }

    /** 拉取两个标的，按日期对齐后返回（对齐后的日期列表，以及各标的归一化值列表） */
    private suspend fun loadAll(): Pair<Aligned, List<List<Double>>>? {
        val errs = ArrayList<String>()
        val seriesList = assets.map { a ->
            val s = loadSeries(a)
            if (s == null) errs.add("${a.label}：$lastError")
            s
        }.filterNotNull()
        if (seriesList.size < 2) { lastError = errs.joinToString("；"); return null }

        // 按日期取交集
        val dateSets = seriesList.map { it.closes.keys }.toSet().reduce { a, b -> a.intersect(b) }
        if (dateSets.isEmpty()) { lastError = "两序列无共同交易日"; return null }
        val dates = dateSets.sorted()

        val alignedValues = seriesList.map { s ->
            val base = s.closes[dates.first()]!!
            dates.map { d -> s.closes[d]!! / base * 100.0 }
        }
        return Pair(Aligned(dates), alignedValues)
    }

    private data class Aligned(val dates: List<String>)

    private data class Series(val closes: Map<String, Double>) // date -> close/nav

    private suspend fun loadSeries(asset: Asset): Series? = when {
        asset.code.startsWith("sh") || asset.code.startsWith("sz") -> loadIndexDaily(asset.code)
        else -> loadFundNav(asset.code)
    }

    /** 指数/ETF 日线：复用 HistoricalDataFetcher 拉取并存库，再读 DailySnapshotEntity */
    private suspend fun loadIndexDaily(code: String): Series? {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        var local: List<DailySnapshotEntity> =
            runCatching { db.dailySnapshotDao().getByCode(code, 3000) }.getOrDefault(emptyList())
        if (local.size < 500) {
            // 首次/历史不足：全量拉一次
            val ok = runCatching { HistoricalDataFetcher(ctx).fetchStockLatest(code, 2000) }.getOrDefault(false)
            if (ok) local = runCatching { db.dailySnapshotDao().getByCode(code, 3000) }.getOrDefault(emptyList())
            if (!ok && local.size < 100) { lastError = "指数拉取失败（东财push2his不可达+新浪兜底失败）"; return null }
        } else {
            // 已有历史：只增量补最近 40 根（insertAll REPLACE 幂等合并）
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
            val maxDate = runCatching { db.dailySnapshotDao().getMaxDate(code) }.getOrNull()
            if (maxDate == null || maxDate < today) {
                runCatching { HistoricalDataFetcher(ctx).fetchStockLatest(code, 40) }
                local = runCatching { db.dailySnapshotDao().getByCode(code, 3000) }.getOrDefault(emptyList())
            }
        }
        if (local.size < 100) { lastError = "指数本地数据不足（${local.size}条）"; return null }
        val map = LinkedHashMap<String, Double>()
        for (e in local.sortedBy { it.date }) {
            if (e.close > 0) map[e.date] = e.close
        }
        return Series(map)
    }

    /**
     * 场外基金净值：天天基金 pingzhongdata 接口（韧性解析：定位字段后逐条扫 x/y 键值对）
     * ⚠️ 不能用 webScrapeClient：它的拦截器手动加了 Accept-Encoding: gzip，
     *    导致 OkHttp 透明解压失效，body 是 gzip 乱码。这里用裸客户端（自动解压）。
     * 本地优先：净值落库 daily_snapshot（code=FUND_xxx），3 天内不访问网络；
     * 过期才全量拉一次（接口不支持区间），只插入比本地新的点（REPLACE 幂等）。
     */
    private val fundClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private suspend fun loadFundNav(code: String): Series? {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        val key = "FUND_$code"
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

        // 1) 本地命中且新鲜（最新净值在 3 天内）→ 直接返回，不访问网络
        var local = runCatching { db.dailySnapshotDao().getByCode(key, 6000) }.getOrDefault(emptyList())
        val maxDate = runCatching { db.dailySnapshotDao().getMaxDate(key) }.getOrNull()
        val freshThreshold = sdf.format(Date(System.currentTimeMillis() - 3L * 86400000))
        if (local.size >= 100 && maxDate != null && maxDate >= freshThreshold) {
            val map = LinkedHashMap<String, Double>()
            for (e in local.sortedBy { it.date }) if (e.close > 0) map[e.date] = e.close
            return Series(map)
        }

        // 2) 过期或没有 → 全量拉一次，只插入比本地新的点
        val url = "http://fund.eastmoney.com/pingzhongdata/$code.js"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "http://fund.eastmoney.com/")
            .build()
        val txt = runCatching {
            fundClient.newCall(req).execute().use { it.body?.string() }
        }.getOrNull() ?: run {
            // 网络挂了但本地有旧数据 → 降级用旧数据
            if (local.size >= 100) {
                val map = LinkedHashMap<String, Double>()
                for (e in local.sortedBy { it.date }) if (e.close > 0) map[e.date] = e.close
                return Series(map)
            }
            lastError = "基金接口无响应（fund.eastmoney.com）"; return null
        }
        val start = txt.indexOf("Data_netWorthTrend")
        if (start < 0) { lastError = "基金返回缺少净值字段（响应${txt.length}字符）"; return null }
        val end = txt.indexOf("/*", start).takeIf { it > start } ?: txt.length
        val seg = txt.substring(start, end)
        val fetched = LinkedHashMap<String, Double>()
        for (m in Regex("\"x\":(\\d+),\"y\":([0-9.]+)").findAll(seg)) {
            val ts = m.groupValues[1].toLongOrNull() ?: continue
            val nav = m.groupValues[2].toDoubleOrNull() ?: continue
            if (nav > 0) fetched[sdf.format(Date(ts))] = nav
        }
        if (fetched.size < 100) { lastError = "基金净值数据不足（${fetched.size}条，响应${txt.length}字符）"; return null }

        // 增量入库：只插比本地 maxDate 新的点（首次 maxDate=null 全插）
        val newEntities = fetched.entries
            .filter { maxDate == null || it.key > maxDate }
            .map { (d, nav) ->
                DailySnapshotEntity(
                    code = key, name = code, date = d,
                    open = nav, close = nav, high = nav, low = nav,
                    volume = 0L, amount = 0.0, changePct = 0.0
                )
            }
        if (newEntities.isNotEmpty()) {
            runCatching { db.dailySnapshotDao().insertAll(newEntities) }
        }

        local = runCatching { db.dailySnapshotDao().getByCode(key, 6000) }.getOrDefault(emptyList())
        if (local.size < 100) { lastError = "基金本地数据不足（${local.size}条）"; return null }
        val map = LinkedHashMap<String, Double>()
        for (e in local.sortedBy { it.date }) if (e.close > 0) map[e.date] = e.close
        return Series(map)
    }

    /**
     * 股指期货 IF 主力连续日线：新浪 InnerFuturesNewService.getDailyKLine?symbol=IF0
     * （需 Referer 头，返回 2017 起全历史 JSON 数组 [{d,o,h,l,c,v,...}]）。
     * 本地优先：落库 daily_snapshot（code=IF_MAIN），最新点在 3 天内不访问网络；
     * 过期才全量拉一次，只插入比本地新的点（REPLACE 幂等）。
     */
    private suspend fun loadIfFuturesDaily(): Series? {
        val ctx = requireContext()
        val db = StockDatabase.getInstance(ctx)
        val key = "IF_MAIN"
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

        var local = runCatching { db.dailySnapshotDao().getByCode(key, 4000) }.getOrDefault(emptyList())
        val maxDate = runCatching { db.dailySnapshotDao().getMaxDate(key) }.getOrNull()
        val freshThreshold = sdf.format(Date(System.currentTimeMillis() - 3L * 86400000))
        if (local.size >= 200 && maxDate != null && maxDate >= freshThreshold) {
            val map = LinkedHashMap<String, Double>()
            for (e in local.sortedBy { it.date }) if (e.close > 0) map[e.date] = e.close
            return Series(map)
        }

        val url = "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_IF0=/InnerFuturesNewService.getDailyKLine?symbol=IF0"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://finance.sina.com.cn")
            .build()
        val txt = runCatching {
            fundClient.newCall(req).execute().use { it.body?.string() }
        }.getOrNull() ?: run {
            // 网络挂了但本地有旧数据 → 降级用旧数据
            if (local.size >= 200) {
                val map = LinkedHashMap<String, Double>()
                for (e in local.sortedBy { it.date }) if (e.close > 0) map[e.date] = e.close
                return Series(map)
            }
            lastError = "新浪期货接口无响应（stock2.finance.sina.com.cn）"; return null
        }

        val rx = Regex("\\{\"d\":\"(\\d{4}-\\d{2}-\\d{2})\",\"o\":\"([0-9.]+)\",\"h\":\"([0-9.]+)\",\"l\":\"([0-9.]+)\",\"c\":\"([0-9.]+)\",\"v\":\"(\\d+)\"")
        val fetched = LinkedHashMap<String, Double>()
        val rows = ArrayList<DailySnapshotEntity>()
        for (m in rx.findAll(txt)) {
            val d = m.groupValues[1]
            val c = m.groupValues[5].toDoubleOrNull() ?: continue
            if (c <= 0) continue
            fetched[d] = c
            rows.add(DailySnapshotEntity(
                code = key, name = "IF主力", date = d,
                open = m.groupValues[2].toDoubleOrNull() ?: c,
                close = c,
                high = m.groupValues[3].toDoubleOrNull() ?: c,
                low = m.groupValues[4].toDoubleOrNull() ?: c,
                volume = m.groupValues[6].toLongOrNull() ?: 0L,
                amount = 0.0, changePct = 0.0
            ))
        }
        if (fetched.size < 100) { lastError = "期货数据解析不足（${fetched.size}条，响应${txt.length}字符）"; return null }

        val newRows = rows.filter { maxDate == null || it.date > maxDate }
        if (newRows.isNotEmpty()) runCatching { db.dailySnapshotDao().insertAll(newRows) }

        local = runCatching { db.dailySnapshotDao().getByCode(key, 4000) }.getOrDefault(emptyList())
        val map = LinkedHashMap<String, Double>()
        for (e in local.sortedBy { it.date }) if (e.close > 0) map[e.date] = e.close
        if (map.size < 100) { lastError = "期货本地数据不足（${map.size}条）"; return null }
        return Series(map)
    }

    private data class HedgeData(
        val dates: List<String>,
        val spot: List<Double>,   // 现货归一化（首日=100）
        val fut: List<Double>,    // IF主力归一化
        val hedge: List<Double>,  // 对冲组合 = 多现货 + 空期货（理想化，不含保证金/手续费/基差成本）
        val cycle: DoubleArray,   // 现货收盘 SSA cycle 分量（原始点数，含 NaN 边缘）
        val ssaL: Int
    )

    /** 近一年：沪深300 与 IF主力 日期交集取最后 ~250 交易日，各自归一化 + 对冲组合 + cycle */
    private suspend fun loadHedge(): HedgeData? {
        val errs = ArrayList<String>()
        val spot = loadIndexDaily("sh000300")
        if (spot == null) errs.add("沪深300：$lastError")
        val fut = loadIfFuturesDaily()
        if (fut == null) errs.add("IF主力：$lastError")
        if (spot == null || fut == null) { hedgeErr = errs.joinToString("；"); return null }

        val common = spot.closes.keys.intersect(fut.closes.keys).sorted()
        if (common.size < 60) { hedgeErr = "共同交易日不足（${common.size}）"; return null }
        val dates = common.takeLast(250)   // 近一年 ≈ 250 个交易日

        val s0 = spot.closes[dates.first()]!!
        val f0 = fut.closes[dates.first()]!!
        val sN = dates.map { spot.closes[it]!! / s0 * 100.0 }
        val fN = dates.map { fut.closes[it]!! / f0 * 100.0 }
        val hN = sN.mapIndexed { i, s -> s - (fN[i] - 100.0) }

        // cycle：对窗口内现货原始收盘做 SSA 分解（窗口自适应，最少 L=10）
        val closes = dates.map { spot.closes[it]!! }.toDoubleArray()
        val L = minOf(30, dates.size / 3).coerceAtLeast(10)
        val cyc = runCatching { SsaEngine.decompose(closes, L).cycle }
            .getOrElse { DoubleArray(closes.size) { Double.NaN } }
        return HedgeData(dates, sN, fN, hN, cyc, L)
    }

    private fun renderHedge(h: HedgeData) {
        hedgeDates = h.dates
        hedgeValues = listOf(h.spot, h.fut, h.hedge)
        hedgeCycle = h.cycle

        // 主图：三线对比
        val labels = listOf("沪深300现货", "IF主力连续", "对冲组合(多现货+空IF)")
        val colors = listOf(RED, TEAL, BLUE)
        val sets = ArrayList<LineDataSet>()
        hedgeValues.forEachIndexed { i, v ->
            val entries = v.mapIndexed { idx, x -> Entry(idx.toFloat(), x.toFloat()) }
            sets.add(LineDataSet(entries, labels[i]).apply {
                color = colors[i]
                lineWidth = if (i == 2) 2.2f else 1.4f
                setDrawCircles(false)
                setDrawValues(false)
                setDrawHighlightIndicators(true)
                highLightColor = Color.parseColor("#555555")
                highlightLineWidth = 3f
            })
        }
        hedgeChart.data = LineData(sets.toList())
        hedgeChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt().coerceIn(0, h.dates.size - 1)
                return h.dates[idx].substring(0, 7) // YYYY-MM
            }
        }
        hedgeChart.xAxis.labelCount = 6
        // 与副图统一左边距，保证 X 像素对齐
        val offL = dp(40).toFloat()
        hedgeChart.setViewPortOffsets(offL, dp(4).toFloat(), dp(8).toFloat(), dp(4).toFloat())
        hedgeChart.invalidate()

        // cycle 副图：单线 + 零轴（原始点数）
        val cycE = ArrayList<Entry>()
        h.cycle.forEachIndexed { i, v -> if (!v.isNaN()) cycE.add(Entry(i.toFloat(), v.toFloat())) }
        val cycDs = LineDataSet(cycE, "cycle L=${h.ssaL}").apply {
            color = TEAL
            lineWidth = 1.4f
            setDrawCircles(false)
            setDrawValues(false)
            setDrawHighlightIndicators(true)
            highLightColor = Color.parseColor("#555555")
        }
        cycleChart.apply {
            data = LineData(listOf(cycDs))
            xAxis.axisMinimum = -0.5f
            xAxis.axisMaximum = h.dates.size - 0.5f
            axisLeft.removeAllLimitLines()
            axisLeft.addLimitLine(com.github.mikephil.charting.components.LimitLine(0f).apply {
                lineColor = GRAY; lineWidth = 0.8f
            })
            setViewPortOffsets(offL, dp(2).toFloat(), dp(8).toFloat(), dp(2).toFloat())
            invalidate()
        }

        // 统计：现货 vs 对冲组合（区间收益 / 最大回撤 / 年化波动）
        fun annVol(v: List<Double>): Double {
            val rets = (1 until v.size).map { v[it] / v[it - 1] - 1.0 }
            val mean = rets.average()
            val varr = rets.map { (it - mean) * (it - mean) }.average()
            return Math.sqrt(varr) * Math.sqrt(252.0)
        }
        val sRet = h.spot.last() / h.spot.first() - 1.0
        val hRet = h.hedge.last() / h.hedge.first() - 1.0
        hedgeStatTv.text = "现货：收益 ${pct(sRet)} 回撤 ${pct(maxDrawdown(h.spot))} 年化波动 ${pct(annVol(h.spot))}\n" +
            "对冲：收益 ${pct(hRet)} 回撤 ${pct(maxDrawdown(h.hedge))} 年化波动 ${pct(annVol(h.hedge))}（理想化，未含保证金/展期/费用）"
    }

    // ═══════════════ 宽基ETF 资金代理（主力 vs 散户小单） ═══════════════

    private var etfFlowErr = ""

    private data class EtfFlowData(
        val dates: List<String>,
        val cumMain: List<Double>,   // 主力累计净流入（亿元，首日=0）
        val cumSmall: List<Double>,  // 散户(小单)累计净流入（亿元，首日=0）
        val dailyMain: List<Double>,
        val dailySmall: List<Double>
    )

    /** 单只 ETF 资金流日线：date -> (主力净额元, 中小单净额元)；失败原因写 diag。主源东财 push2delay，兜底新浪 lscjfb */
    private fun fetchEtfFlowDaily(secid: String, diag: StringBuilder): Map<String, Pair<Double, Double>>? {
        fetchEtfFlowEast(secid, diag)?.let { return it }
        return fetchEtfFlowSina(secid, diag)
    }

    /** 东财 push2delay fflow/daykline：f52=主力净额（超大+大），f53=小单净额；f54=中单 → 中小单=f53+f54 */
    private fun fetchEtfFlowEast(secid: String, diag: StringBuilder): Map<String, Pair<Double, Double>>? {
        val base = com.chin.stockanalysis.strategy.data.DataSourceConfig.urlOr(
            "east_delay_fflow", "https://push2delay.eastmoney.com/api/qt/stock/fflow/daykline/get")
        val url = "$base?secid=$secid&klt=101&lmt=250&fields1=f1,f2,f3,f7" +
            "&fields2=f51,f52,f53,f54,f55,f56&ut=b2884a393a59ad64002292a3e90d46a5"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://quote.eastmoney.com/").build()
        val tag = secid.substringAfter('.')
        val resp = runCatching {
            fundClient.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        }.getOrNull() ?: run { diag.append("$tag:东财网络异常;"); return null }
        val txt = resp.second
        if (resp.first != 200 || txt.isBlank()) { diag.append("$tag:东财http${resp.first};"); return null }
        val klines = runCatching {
            org.json.JSONObject(txt).getJSONObject("data").getJSONArray("klines")
        }.getOrNull() ?: run {
            diag.append("$tag:东财解析失败[${txt.take(60).replace("\n", "")}];")
            return null
        }
        val map = LinkedHashMap<String, Pair<Double, Double>>()
        for (i in 0 until klines.length()) {
            val p = klines.getString(i).split(",")
            if (p.size < 4) continue
            val main = p[1].toDoubleOrNull() ?: continue          // f52 主力=超大+大
            val small = (p[2].toDoubleOrNull() ?: 0.0) +
                (p[3].toDoubleOrNull() ?: 0.0)                    // f53 小单 + f54 中单
            map[p[0]] = main to small
        }
        if (map.isEmpty()) { diag.append("$tag:东财0行;"); return null }
        return map
    }

    /** 新浪 MoneyFlow.ssl_qsfx_lscjfb（ETF 四档齐全）：主力=r0_net+r1_net（超大+大），中小单=r2_net+r3_net（中+小） */
    private fun fetchEtfFlowSina(secid: String, diag: StringBuilder): Map<String, Pair<Double, Double>>? {
        val tag = secid.substringAfter('.')
        val sina = (if (secid.startsWith("1.")) "sh" else "sz") + tag
        val url = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
            "MoneyFlow.ssl_qsfx_lscjfb?page=1&num=250&sort=opendate&asc=0&daima=$sina"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://finance.sina.com.cn/").build()
        val txt = runCatching { fundClient.newCall(req).execute().use { it.body?.string() } }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: run { diag.append("$tag:新浪网络异常;"); return null }
        val arr = runCatching { org.json.JSONArray(txt) }.getOrNull() ?: run {
            diag.append("$tag:新浪解析失败[${txt.take(60).replace("\n", "")}];"); return null
        }
        val map = LinkedHashMap<String, Pair<Double, Double>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = o.optString("opendate")
            if (d.isBlank()) continue
            val r0 = o.optDouble("r0_net", Double.NaN)   // 超大单净额（字符串自动转）
            val r1 = o.optDouble("r1_net", Double.NaN)   // 大单
            val r2 = o.optDouble("r2_net", Double.NaN)   // 中单
            val r3 = o.optDouble("r3_net", Double.NaN)   // 小单
            if (r0.isNaN() || r1.isNaN() || r2.isNaN() || r3.isNaN()) continue
            map[d] = (r0 + r1) to (r2 + r3)
        }
        if (map.isEmpty()) { diag.append("$tag:新浪0行;"); return null }
        return map
    }

    /** 5只宽基ETF资金流日线 → 按日期合计 → 累计曲线（亿元） */
    private suspend fun loadEtfFlow(): EtfFlowData? {
        val diag = StringBuilder()
        val perEtf = etfBasket.mapNotNull { (secid, _) -> fetchEtfFlowDaily(secid, diag) }
        if (perEtf.isEmpty()) { etfFlowErr = "全部拉取失败：$diag"; return null }
        // 日期并集：任一 ETF 当日有数据即纳入（缺数据的按 0 计，合计仍可比）
        val dates = perEtf.flatMap { it.keys }.toSortedSet().toList()
        if (dates.size < 20) { etfFlowErr = "共同交易日不足(${dates.size})：$diag"; return null }
        val dailyMain = dates.map { d -> perEtf.sumOf { it[d]?.first ?: 0.0 } / 1e8 }
        val dailySmall = dates.map { d -> perEtf.sumOf { it[d]?.second ?: 0.0 } / 1e8 }
        var acc = 0.0
        val cumMainRaw = dailyMain.map { acc += it; acc }
        acc = 0.0
        val cumSmallRaw = dailySmall.map { acc += it; acc }
        // 起点归零：首日=0，便于直接读窗口内累计净申赎
        val m0 = cumMainRaw.first(); val s0 = cumSmallRaw.first()
        return EtfFlowData(dates,
            cumMainRaw.map { it - m0 }, cumSmallRaw.map { it - s0 },
            dailyMain, dailySmall)
    }

    private fun renderEtfFlow(f: EtfFlowData) {
        etfFlowDates = f.dates
        etfFlowDaily = f.dailyMain.zip(f.dailySmall)
        etfFlowValues = listOf(f.cumMain, f.cumSmall)
        val mainDs = LineDataSet(f.cumMain.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }, "主力累计(亿元)").apply {
            color = RED; lineWidth = 2f; setDrawCircles(false); setDrawValues(false)
        }
        val smallDs = LineDataSet(f.cumSmall.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) }, "散户累计(亿元)").apply {
            color = BLUE; lineWidth = 2f; setDrawCircles(false); setDrawValues(false)
        }
        etfFlowChart.data = LineData(mainDs, smallDs)
        etfFlowChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt()
                return if (i in etfFlowDates.indices) etfFlowDates[i].substring(5) else ""
            }
        }
        etfFlowChart.invalidate()
        fun sumLast(n: Int, v: List<Double>) = v.takeLast(n).sum()
        etfFlowStatTv.text = ("近5日：主力 %+.1f 亿 / 散户 %+.1f 亿；近20日：主力 %+.1f 亿 / 散户 %+.1f 亿\n" +
            "全窗口合计：主力 %+.1f 亿 vs 散户 %+.1f 亿（主力持续流入+散户流出=大资金吸筹特征）").format(
            sumLast(5, f.dailyMain), sumLast(5, f.dailySmall),
            sumLast(20, f.dailyMain), sumLast(20, f.dailySmall),
            f.dailyMain.sum(), f.dailySmall.sum())
    }

    private fun renderChart(aligned: Aligned, values: List<List<Double>>) {
        chartDates = aligned.dates
        chartValues = values
        val sets = ArrayList<LineDataSet>()
        assets.forEachIndexed { i, a ->
            val entries = ArrayList<Entry>()
            values[i].forEachIndexed { idx, v -> entries.add(Entry(idx.toFloat(), v.toFloat())) }
            sets.add(LineDataSet(entries, a.label).apply {
                color = a.color
                lineWidth = 1.8f
                setDrawCircles(false)
                setDrawValues(false)
                setDrawHighlightIndicators(true)
                highLightColor = Color.parseColor("#555555")
                highlightLineWidth = 3f   // dp，内部自动转像素
                mode = LineDataSet.Mode.LINEAR
            })
        }
        chart.data = LineData(sets.toList())
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt().coerceIn(0, aligned.dates.size - 1)
                return aligned.dates[idx].substring(0, 7) // YYYY-MM
            }
        }
        chart.xAxis.labelCount = 8
        chart.axisLeft.axisMinimum = 50f  // 归一化后通常不会跌破50，留出空间
        chart.invalidate()

        renderStats(aligned, values)
    }

    private fun renderStats(aligned: Aligned, values: List<List<Double>>) {
        statBox.removeAllViews()
        val days = aligned.dates.size
        val years = days / 252.0
        val cal = Calendar.getInstance()
        val startDate = aligned.dates.first()
        val endDate = aligned.dates.last()

        assets.forEachIndexed { i, a ->
            val v = values[i]
            val totalRet = v.last() / v.first() - 1.0
            val annualRet = Math.pow(1.0 + totalRet, 1.0 / years) - 1.0
            val mdd = maxDrawdown(v)
            statBox.addView(card {
                it.addView(cardTitle("${a.label}（${a.code}）"))
                it.addView(statRow("区间", "$startDate ~ $endDate（${"%.1f".format(years)}年）"))
                it.addView(statRow("区间收益", pct(totalRet)))
                it.addView(statRow("年化收益", pct(annualRet)))
                it.addView(statRow("最大回撤", pct(mdd)))
            })
        }
    }

    private fun maxDrawdown(v: List<Double>): Double {
        var peak = v[0]
        var mdd = 0.0
        for (x in v) {
            if (x > peak) peak = x
            val dd = x / peak - 1.0
            if (dd < mdd) mdd = dd
        }
        return mdd
    }

    private fun pct(v: Double): String =
        "${if (v > 0) "+" else ""}${"%.2f".format(v * 100)}%"

    // ── UI 工具 ──
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, size: Float, color: Int = Color.parseColor("#222222"), bold: Boolean = false) = TextView(requireContext()).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }
    private fun hint(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 10f; setTextColor(GRAY)
        setPadding(0, dp(6), 0, dp(2))
    }
    private fun cardTitle(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.parseColor("#333333")); setPadding(0, 0, 0, dp(6))
    }
    private fun statRow(k: String, v: String) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(requireContext()).apply { text = k; textSize = 11f; setTextColor(GRAY) })
        addView(TextView(requireContext()).apply {
            text = v; textSize = 11f; setTextColor(Color.parseColor("#222222"))
            setPadding(dp(8), 0, 0, 0)
        })
    }
    private fun card(build: (LinearLayout) -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1), Color.parseColor("#E8E8E8"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        build(this)
    }
}
