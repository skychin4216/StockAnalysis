package com.chin.stockanalysis.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.HotSector
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 板块走势对比 — 热门板块历史累计涨跌叠加（v2）
 *
 * 2026-09-06 改造（v2）：
 * - 旧版数据依赖本地 sector_daily_record 逐日积累，新装/断更即空白 →「不好用」。
 * - v2 直接实时拉东财板块日K（push2his stock/kline/get, secid=90.BKxxxx），
 *   与 App 个股日K同接口，无需本地积累，打开即有数据。
 * - 打开默认自动对比「当前最热 TOP6」（行业+概念按综合分），一键可用；
 *   保留 chip 点选增删 + 1月/3月/6月/1年/全部 时间范围。
 */
class SectorTrendChartFragment : Fragment() {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd")

    // 板块颜色盘（最多 8 种）
    private val SECTOR_COLORS = intArrayOf(
        0xFF1976D2.toInt(), // 蓝
        0xFFE53935.toInt(), // 红
        0xFF43A047.toInt(), // 绿
        0xFFFF9800.toInt(), // 橙
        0xFF9C27B0.toInt(), // 紫
        0xFF00ACC1.toInt(), // 青
        0xFFF4511E.toInt(), // 深橙
        0xFF6D4C41.toInt(), // 棕
    )

    private lateinit var chart: LineChart
    private lateinit var infoTv: TextView
    private lateinit var rangeRow: LinearLayout
    private lateinit var chipContainer: LinearLayout
    private val http = OkHttpClient()

    // 可用板块池（实时 API：行业+概念 top40，按综合分排序）
    private var pool: List<HotSector> = emptyList()
    // 板块名映射 code -> name（用于 K 线接口异常时仍能显示名）
    private val nameMap = LinkedHashMap<String, String>()
    // 当前选中的板块 codes（默认 = 池内 TOP6）
    private val selectedSectors = mutableSetOf<String>()
    // 已拉取的板块日K缓存 code -> (date, close) 升序
    private val klineMap = mutableMapOf<String, List<Pair<String, Double>>>()
    private var rangeDays = 90
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(8, 8, 8, 8)
        }

        // ── 板块 Chip 行（可横向滚动）──
        val chipScroll = HorizontalScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
        }
        chipContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 6)
        }
        chipScroll.addView(chipContainer)
        root.addView(chipScroll)

        // ── 时间范围按钮行 ──
        rangeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 4)
        }
        val rangeBtns = listOf(
            "1月" to 30, "3月" to 90, "6月" to 120, "1年" to 250, "全部" to 0
        )
        for ((label, days) in rangeBtns) {
            val isActive = rangeDays == days
            val btn = TextView(ctx).apply {
                text = label
                textSize = 10f
                tag = days
                setTextColor(if (isActive) Color.WHITE else Color.parseColor("#666666"))
                setBackgroundColor(if (isActive) Color.parseColor("#1976D2") else Color.parseColor("#EEEEEE"))
                setPadding(12, 4, 12, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 4 }
                setOnClickListener {
                    rangeDays = days
                    updateRangeButtons()
                    renderChart()
                }
            }
            rangeRow.addView(btn)
        }
        root.addView(rangeRow)

        // ── 信息栏 ──
        infoTv = TextView(ctx).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, 4)
        }
        root.addView(infoTv)

        // ── 折线图 ──
        chart = LineChart(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            description.isEnabled = false
            setDrawGridBackground(false)
            setPinchZoom(true)
            setScaleEnabled(true)
            isDoubleTapToZoomEnabled = true
            legend.isEnabled = true
            legend.textSize = 9f
            legend.textColor = Color.parseColor("#666666")
            legend.setDrawInside(false)
        }
        root.addView(chart)

        loadSectorsAndKlines()
        return root
    }

    // ══════════════════════════════════════
    // 数据载入（v2：实时板块 + 实时板块日K）
    // ══════════════════════════════════════

    private fun loadSectorsAndKlines() {
        if (loading) return
        loading = true
        infoTv.text = "🔄 正在获取热门板块实时走势..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val source = EastMoneyHotSectorSource()
                val industry = source.fetchSectorsByTypeDirect(2, 20)
                val concept = source.fetchSectorsByTypeDirect(3, 20)
                val all = (industry + concept).distinctBy { it.code }
                if (all.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        infoTv.text = "暂无板块数据（网络受限？），请稍后重试"
                        loading = false
                    }
                    return@launch
                }
                // 按综合分排序 → 默认 TOP6
                pool = all.sortedByDescending { it.compositeScore }
                nameMap.clear()
                pool.forEach { nameMap[it.code] = it.name }
                selectedSectors.clear()
                pool.take(6).forEach { selectedSectors.add(it.code) }

                ensureKlines(selectedSectors.toList())

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    loading = false
                    buildChips()
                    renderChart()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    infoTv.text = "载入失败: ${e.message?.take(50)}"
                    loading = false
                }
            }
        }
    }

    /** 补拉缺失板块的日K（code 列表） */
    private fun ensureKlines(codes: List<String>) {
        val miss = codes.filter { it !in klineMap }
        if (miss.isEmpty()) return
        // 最多并发 4
        miss.forEachIndexed { idx, code ->
            if (idx % 4 == 0) Thread.sleep(80)
            try {
                val kl = fetchBoardKline(code)
                if (kl.isNotEmpty()) klineMap[code] = kl
            } catch (_: Exception) { /* 单板块失败跳过 */ }
        }
    }

    /** 拉某板块（90.BKxxxx）最近 250 根日K，返回升序 (date, close) */
    private fun fetchBoardKline(code: String): List<Pair<String, Double>> {
        val url = "${DataConfig.eastmoneyPush2his}/stock/kline/get?" +
                "secid=90.$code&klt=101&fqt=1" +
                "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f61" +
                "&lmt=250"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) return emptyList()
        val body = resp.body?.string() ?: return emptyList()
        val data = JSONObject(body).optJSONObject("data") ?: return emptyList()
        val klines = data.optJSONArray("klines") ?: return emptyList()
        val list = mutableListOf<Pair<String, Double>>()
        for (i in 0 until klines.length()) {
            val line = klines.getString(i).split(",")
            if (line.size < 3) continue
            val close = line[2].toDoubleOrNull() ?: continue
            list.add(line[0] to close)
        }
        return list
    }

    // ══════════════════════════════════════
    // Chip 构建
    // ══════════════════════════════════════

    private fun buildChips() {
        chipContainer.removeAllViews()
        val ctx = requireContext()
        for (s in pool.take(40)) {
            val isSelected = s.code in selectedSectors
            val chip = TextView(ctx).apply {
                text = s.name
                textSize = 10f
                setPadding(10, 4, 10, 4)
                tag = s.code
                if (isSelected) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1976D2"))
                } else {
                    setTextColor(Color.parseColor("#666666"))
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 4 }
                setOnClickListener {
                    if (loading) return@setOnClickListener
                    if (s.code in selectedSectors) {
                        if (selectedSectors.size > 1) selectedSectors.remove(s.code)
                    } else {
                        if (selectedSectors.size >= 8) {
                            selectedSectors.remove(selectedSectors.first())
                        }
                        selectedSectors.add(s.code)
                    }
                    buildChips()
                    infoTv.text = "🔄 拉取 ${s.name} 走势中..."
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        ensureKlines(selectedSectors.toList())
                        withContext(Dispatchers.Main) {
                            if (isAdded) renderChart()
                        }
                    }
                }
            }
            chipContainer.addView(chip)
        }
    }

    // ══════════════════════════════════════
    // 图表渲染
    // ══════════════════════════════════════

    private fun renderChart() {
        if (selectedSectors.isEmpty() || klineMap.isEmpty()) return

        // 日期并集（X 轴）
        val allDates = sortedSetOf<String>()
        val series = mutableListOf<Pair<String, List<Pair<String, Double>>>>()
        for (code in selectedSectors) {
            val records = klineMap[code] ?: continue
            val filtered = if (rangeDays <= 0) records else records.takeLast(rangeDays)
            if (filtered.isEmpty()) continue
            filtered.forEach { allDates.add(it.first) }
            series.add(code to filtered)
        }
        if (allDates.isEmpty() || series.isEmpty()) {
            infoTv.text = "所选板块无K线数据"
            return
        }
        val dateList = allDates.toList()
        val dateIndexMap = dateList.withIndex().associate { (i, d) -> d to i }

        val lineDataSets = mutableListOf<LineDataSet>()
        val infoParts = mutableListOf<String>()

        for ((idx, pair) in series.withIndex()) {
            val code = pair.first
            val filtered = pair.second
            val name = nameMap[code] ?: code
            val color = SECTOR_COLORS[idx % SECTOR_COLORS.size]

            // 归一化累计走势（基准 100 = 起点收盘）
            val entries = mutableListOf<Entry>()
            val baseClose = filtered.first().second
            if (baseClose <= 0.0) continue
            for ((date, close) in filtered) {
                val xIdx = dateIndexMap[date] ?: continue
                entries.add(Entry(xIdx.toFloat(), (close / baseClose * 100).toFloat()))
            }
            if (entries.isEmpty()) continue
            lineDataSets.add(LineDataSet(entries, name).apply {
                this.color = color
                lineWidth = 1.8f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = true
                setHighlightLineWidth(1f)
                mode = LineDataSet.Mode.LINEAR
            })
            val latestIdx = filtered.last().second
            val totalChange = (latestIdx / baseClose - 1) * 100
            infoParts.add("$name: ${if (totalChange >= 0) "+" else ""}${"%.1f".format(totalChange)}%")
        }

        if (lineDataSets.isEmpty()) {
            infoTv.text = "所选板块K线获取失败"
            return
        }

        chart.data = LineData(lineDataSets as List<ILineDataSet>)

        var yMin = Float.MAX_VALUE
        var yMax = Float.MIN_VALUE
        for (ds in lineDataSets) {
            for (e in ds.values) {
                if (e.y < yMin) yMin = e.y
                if (e.y > yMax) yMax = e.y
            }
        }

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            labelCount = dateList.size.coerceAtMost(8)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val i = value.toInt()
                    return if (i in dateList.indices) {
                        try { LocalDate.parse(dateList[i], DATE_FMT).format(SHORT_DATE) }
                        catch (_: Exception) { dateList[i].takeLast(5) }
                    } else ""
                }
            }
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EEEEEE")
            setLabelCount(6, true)
            axisMinimum = yMin - (yMax - yMin) * 0.15f
            axisMaximum = yMax + (yMax - yMin) * 0.15f
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "${"%.1f".format(value)}"
            }
        }
        chart.axisRight.setDrawGridLines(false)

        chart.setVisibleXRangeMaximum(60f)
        chart.setVisibleXRangeMinimum(5f)
        if (dateList.size > 60) {
            chart.moveViewToX((dateList.size - 60).toFloat())
        } else {
            chart.moveViewToX(0f)
        }
        chart.invalidate()

        val periodName = when (rangeDays) {
            30 -> "近1月"; 90 -> "近3月"; 120 -> "近6月"; 250 -> "近1年"; else -> "全部"
        }
        infoTv.text = "默认对比当日最热 TOP6 | 可点板块chip增删（≤8条） | $periodName 归一化走势\n" +
                infoParts.joinToString("   ")
        updateRangeButtons()
    }

    // ══════════════════════════════════════
    // 工具
    // ══════════════════════════════════════

    private fun updateRangeButtons() {
        for (i in 0 until rangeRow.childCount) {
            val btn = rangeRow.getChildAt(i) as? TextView ?: continue
            val days = btn.tag as? Int ?: continue
            val isActive = rangeDays == days
            btn.setTextColor(if (isActive) Color.WHITE else Color.parseColor("#666666"))
            btn.setBackgroundColor(if (isActive) Color.parseColor("#1976D2") else Color.parseColor("#EEEEEE"))
        }
    }
}
