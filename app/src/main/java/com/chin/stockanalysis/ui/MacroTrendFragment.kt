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
