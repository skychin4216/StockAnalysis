package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 🔄 行业板块轮动 — 近 12 个月 × 月度涨幅 Top10
 *
 * 数据口径：
 *  - 板块池：东财行业板块 `fs=m:90+t:2`（约 40 只），按综合分排序取头部
 *  - K 线：`push2his stock/kline/get, secid=90.BKxxxx, lmt=250`（约 1 年日 K）
 *  - 月涨幅 = 当月最后一个交易日收盘 / 当月第一个交易日收盘 - 1
 *  - 仅保留涨幅 > 0 的板块（"上升趋势"过滤），每月按涨幅降序取前 10
 *  - 月份按时间升序（早→晚）；当前月标记「进行中」
 *
 * 交互：点月份下的板块 chip → 顶部主图切换为该板块近 1 年走势（归一化，首收=100）。
 */
class SectorRotationFragment : Fragment() {

    private val RED = Color.parseColor("#E53935")
    private val BLUE = Color.parseColor("#1976D2")
    private val GRAY = Color.parseColor("#999999")
    private val DARK = Color.parseColor("#333333")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private lateinit var statusTv: TextView
    private lateinit var chart: LineChart
    private lateinit var chartTitleTv: TextView
    private lateinit var monthBox: LinearLayout
    private lateinit var progressBar: ProgressBar

    /** 板块代码 -> 名称 */
    private val nameMap = LinkedHashMap<String, String>()
    /** 板块代码 -> 日K 升序 (date, close) */
    private val klineMap = LinkedHashMap<String, List<Pair<String, Double>>>()
    /** 月份 -> 该月涨幅榜（涨幅降序，仅正涨幅，最多 10） */
    private val monthLeaders = LinkedHashMap<String, List<Pair<String, Double>>>()  // month -> [(code, chgPct)]
    /** 当前选中要画走势的板块 */
    private var selectedCode: String? = null
    /** 当前选中 chip 的月份（用于重新点亮） */
    private var selectedMonth: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }
        scroll.addView(box)

        // ── 顶行：返回 + 标题 ──
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = TextView(ctx).apply {
            text = "← 返回大势"; textSize = 12f; setTextColor(BLUE)
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { parentFragmentManager.popBackStack() }
        }
        topRow.addView(backBtn)
        topRow.addView(tv("🔄 行业板块轮动 · 近12个月", 15f, DARK, bold = true))
        box.addView(topRow)
        box.addView(tv("每月该行业板块涨幅榜 Top10（仅保留上涨），点板块看其近 1 年走势", 10f, GRAY))

        // ── 进度 + 状态 ──
        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        box.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
        ).apply { topMargin = dp(6) })
        statusTv = tv("⏳ 初始化…", 11f, GRAY)
        box.addView(statusTv)

        // ── 走势图 ──
        chartTitleTv = tv("（选板块后显示）", 12f, DARK, bold = true)
        box.addView(chartTitleTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        chart = LineChart(ctx).apply {
            setBackgroundColor(Color.WHITE)
            description.isEnabled = false
            setTouchEnabled(true); setPinchZoom(true)
            setDrawGridBackground(false)
            axisRight.isEnabled = false
            xAxis.setDrawGridLines(false)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisLeft.setDrawGridLines(true)
            axisLeft.gridColor = Color.parseColor("#EEEEEE")
            legend.isEnabled = false
            setExtraOffsets(5f, 6f, 5f, 5f)
        }
        box.addView(chart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))

        // ── 月份榜 ──
        monthBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(monthBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        loadAll()
        return scroll
    }

    // ═══════════════════════════════ 数据加载 ═══════════════════════════════

    private fun loadAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                setStatus("⏳ 拉取行业板块池…", 5)
                val sectors = withContext(Dispatchers.IO) {
                    EastMoneyHotSectorSource().fetchSectorsByTypeDirect(2, 40)
                }
                if (sectors.isEmpty()) {
                    setStatus("⚠️ 行业板块池为空（网络受限？）", 0); return@launch
                }
                nameMap.clear()
                sectors.forEach { nameMap[it.code] = it.name }
                setStatus("⏳ 拉取 ${sectors.size} 只板块近 1 年日K…", 10)

                // 并发拉 K 线（6 路并发）
                val fetched = withContext(Dispatchers.IO) {
                    fetchAllKlines(sectors.map { it.code }) { done, total ->
                        launch(Dispatchers.Main) {
                            setStatus("⏳ 板块日K $done/$total", 10 + done * 70 / total)
                        }
                    }
                }
                klineMap.clear()
                klineMap.putAll(fetched)
                if (klineMap.isEmpty()) {
                    setStatus("⚠️ 全部板块日K拉取失败", 0); return@launch
                }

                setStatus("⏳ 按月聚合涨幅榜…", 85)
                computeMonthLeaders()

                setStatus("⏳ 渲染…", 95)
                renderMonths()
                // 默认选最近月涨幅第一的板块
                val lastMonth = monthLeaders.keys.lastOrNull()
                val top = lastMonth?.let { monthLeaders[it]?.firstOrNull() }
                if (top != null) selectSector(top.first, lastMonth)

                setStatus("✅ 已加载 ${klineMap.size} 只板块 × ${monthLeaders.size} 个月", 100)
            } catch (e: Exception) {
                setStatus("⚠️ 加载失败：${e.message?.take(60)}", 0)
            }
        }
    }

    private suspend fun fetchAllKlines(
        codes: List<String>,
        onProgress: (Int, Int) -> Unit
    ): Map<String, List<Pair<String, Double>>> = coroutineScope {
        val result = LinkedHashMap<String, List<Pair<String, Double>>>()
        val total = codes.size
        var done = 0
        // 分片并发，每片 6 个
        codes.chunked(6).forEach { chunk ->
            val deferreds = chunk.map { code ->
                async(Dispatchers.IO) {
                    code to runCatching { fetchBoardKline(code) }.getOrDefault(emptyList())
                }
            }
            deferreds.awaitAll().forEach { (code, list) ->
                if (list.isNotEmpty()) result[code] = list
                done++
                onProgress(done, total)
            }
        }
        result
    }

    /** 拉某板块（90.BKxxxx）日K：本地优先（daily_snapshot, key=SECTOR_xxx），仅增量补最新日期之后的K线。
     *  返回升序 (date, close)，本地为空时全量拉 250 根。 */
    private suspend fun fetchBoardKline(code: String): List<Pair<String, Double>> {
        val ctx = context ?: return emptyList()
        val db = StockDatabase.getInstance(ctx)
        val key = "SECTOR_$code"
        val local = runCatching { db.dailySnapshotDao().getByCode(key, 1000) }
            .getOrDefault(emptyList())
            .sortedBy { it.date }
            .map { it.date to it.close }
            .filter { it.second > 0 }
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())
        val maxDate = local.lastOrNull()?.first
        // 本地已覆盖到今天（或今天之后，例如周末已拉到周五）→ 直接用本地
        if (maxDate != null && maxDate >= today) return local.takeLast(250)
        // 增量：本地最后一根日期起（含当日，REPLACE 幂等）
        val beg = maxDate?.replace("-", "") ?: "0"
        val url = "${DataConfig.eastmoneyPush2his}/stock/kline/get?" +
                "secid=90.$code&klt=101&fqt=1&beg=$beg&end=20500101" +
                "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f61" +
                "&lmt=500"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val body = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return local.takeLast(250)
            r.body?.string() ?: return local.takeLast(250)
        }
        val data = JSONObject(body).optJSONObject("data") ?: return local.takeLast(250)
        val klines = data.optJSONArray("klines") ?: return local.takeLast(250)
        val newBars = ArrayList<Pair<String, Double>>(klines.length())
        for (i in 0 until klines.length()) {
            val line = klines.getString(i).split(",")
            if (line.size < 3) continue
            val close = line[2].toDoubleOrNull() ?: continue
            newBars.add(line[0] to close)
        }
        if (newBars.isEmpty()) return local.takeLast(250)
        // 写库（REPLACE 幂等）
        val entities = newBars.map { (d, c) ->
            DailySnapshotEntity(
                code = key, name = nameMap[code] ?: code, date = d,
                open = c, close = c, high = c, low = c,
                volume = 0L, amount = 0.0, changePct = 0.0
            )
        }
        runCatching { db.dailySnapshotDao().insertAll(entities) }
        // 合并：local 去掉与新数据起点重叠的，拼接 newBars
        val cutoff = newBars.first().first
        return (local.filter { it.first < cutoff } + newBars).takeLast(250)
    }

    /** 按月聚合：每只板块在每个自然月的涨跌幅，每月取涨幅>0 的 Top10 */
    private fun computeMonthLeaders() {
        monthLeaders.clear()
        // 收集出现过的所有月份（升序）
        val allMonths = sortedSetOf<String>()
        klineMap.values.forEach { list ->
            list.forEach { (d, _) -> if (d.length >= 7) allMonths.add(d.substring(0, 7)) }
        }
        // 只保留最近 12 个月
        val months = allMonths.toList().takeLast(12)
        if (months.isEmpty()) return
        val currentMonth = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.CHINA)
            .format(java.util.Date())

        for (m in months) {
            val ranks = ArrayList<Pair<String, Double>>()
            for ((code, list) in klineMap) {
                val inMonth = list.filter { it.first.startsWith(m) }
                if (inMonth.size < 2) continue
                val first = inMonth.first().second
                val last = inMonth.last().second
                if (first <= 0) continue
                val chg = last / first - 1.0
                if (chg > 0) ranks.add(code to chg)
            }
            ranks.sortByDescending { it.second }
            val label = if (m == currentMonth) "$m（进行中）" else m
            monthLeaders[label] = ranks.take(10)
        }
    }

    // ═══════════════════════════════ 渲染 ═══════════════════════════════

    private fun renderMonths() {
        val ctx = requireContext()
        monthBox.removeAllViews()
        for ((monthLabel, leaders) in monthLeaders) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#E8E8E8"))
                }
            }
            // 月份标题行
            val monthKey = monthLabel.substring(0, 7)
            card.addView(tv("📅 $monthLabel  ·  ${leaders.size} 只上涨", 12f, DARK, bold = true))
            // 板块 chips（横向滚动）
            val chipScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
            }
            val chipRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            leaders.forEachIndexed { idx, (code, chg) ->
                chipRow.addView(buildChip(code, monthKey, idx + 1, chg))
            }
            chipScroll.addView(chipRow)
            card.addView(chipScroll)

            monthBox.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
    }

    private fun buildChip(code: String, monthKey: String, rank: Int, chgPct: Double): View {
        val ctx = requireContext()
        val name = nameMap[code] ?: code
        val selected = (code == selectedCode && monthKey == selectedMonth)
        return TextView(ctx).apply {
            text = "$rank.$name ${pct(chgPct)}"
            textSize = 11f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(if (selected) Color.WHITE else DARK)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                if (selected) {
                    setColor(BLUE)
                } else {
                    setColor(Color.parseColor("#F0F0F0"))
                    setStroke(dp(1), Color.parseColor("#DDDDDD"))
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { selectSector(code, monthKey) }
        }
    }

    private fun selectSector(code: String, monthKey: String) {
        selectedCode = code
        selectedMonth = monthKey
        renderChart()
        renderMonths()  // 重绘 chips 让选中态生效
    }

    private fun renderChart() {
        val code = selectedCode ?: return
        val list = klineMap[code] ?: return
        val name = nameMap[code] ?: code
        if (list.size < 2) return
        val base = list.first().second
        if (base <= 0) return

        val entries = ArrayList<Entry>(list.size)
        list.forEachIndexed { i, p -> entries.add(Entry(i.toFloat(), (p.second / base * 100.0).toFloat())) }
        val ds = LineDataSet(entries, name).apply {
            color = BLUE
            lineWidth = 1.8f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }
        chart.data = LineData(ds)
        chart.xAxis.apply {
            labelCount = 6
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val i = value.toInt().coerceIn(0, list.size - 1)
                    return list[i].first.substring(0, 7)
                }
            }
        }
        chart.axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = "%.0f".format(value)
        }
        chart.invalidate()

        val totalChg = list.last().second / base - 1.0
        val firstDate = list.first().first
        val lastDate = list.last().first
        chartTitleTv.text = "📈 $name  ·  $firstDate ~ $lastDate  ·  累计 ${pct(totalChg)}"
    }

    // ═══════════════════════════════ 工具 ═══════════════════════════════

    private fun setStatus(s: String, prog: Int) {
        if (!isAdded) return
        statusTv.text = s
        progressBar.progress = prog
        progressBar.visibility = if (prog in 1 until 100) View.VISIBLE else View.GONE
    }

    private fun pct(v: Double): String =
        "${if (v > 0) "+" else ""}${"%.1f".format(v * 100)}%"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float, color: Int = Color.parseColor("#222222"), bold: Boolean = false) =
        TextView(requireContext()).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }
}
