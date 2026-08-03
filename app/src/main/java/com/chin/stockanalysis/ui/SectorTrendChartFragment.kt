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
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.SectorDailyRecordEntity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 板塊走勢對比 — 多板塊累計漲跌疊加顯示
 *
 * 設計概念：
 * - 多條折線疊加，每條代表一個板塊的累計指數（基準=100）
 * - 板塊通過 chip 切換/多選，最多同時顯示 5 條
 * - 時間範圍可選：1月/3月/6月/1年/全部
 */
class SectorTrendChartFragment : Fragment() {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd")

    // 板塊顏色盤（最多 8 種）
    private val SECTOR_COLORS = intArrayOf(
        0xFF1976D2.toInt(), // 藍
        0xFFE53935.toInt(), // 紅
        0xFF43A047.toInt(), // 綠
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

    // 所有可用板塊
    private var allTopSectors: List<Pair<String, String>> = emptyList()
    // 所有板塊的完整記錄（code -> records）
    private var sectorRecordMap: Map<String, List<SectorDailyRecordEntity>> = emptyMap()
    // 當前選中的板塊 codes
    private val selectedSectors = mutableSetOf<String>()
    // 熱門板塊 codes（近30天有 S/A 評級）
    private var hotSectorCodes: Set<String> = emptySet()
    private var rangeDays = 90

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

        // ── 板塊 Chip 行（可橫向滾動）──
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

        // ── 時間範圍按鈕行 ──
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

        // ── 信息欄 ──
        infoTv = TextView(ctx).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, 4)
        }
        root.addView(infoTv)

        // ── 折線圖 ──
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

        loadAllData()
        return root
    }

    // ══════════════════════════════════════
    // 數據載入
    // ══════════════════════════════════════

    private fun loadAllData() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                var recentDays = db.sectorDailyRecordDao().getRecentDays(30)

                if (recentDays.isEmpty()) {
                    try {
                        val engine = com.chin.stockanalysis.strategy.backtest.SectorRotationEngine(requireContext())
                        engine.saveDailySectorData()
                        recentDays = db.sectorDailyRecordDao().getRecentDays(30)
                    } catch (_: Exception) {}
                }

                // 找所有板塊（不限 rank），統計熱門天數
                val sectorMap = mutableMapOf<String, String>()
                val sectorHotDays = mutableMapOf<String, Int>()
                for (r in recentDays) {
                    sectorMap[r.sectorCode] = r.sectorName
                    if (r.isHot in listOf("S", "A")) {
                        sectorHotDays[r.sectorCode] = (sectorHotDays[r.sectorCode] ?: 0) + 1
                    }
                }
                // 排序：熱門優先（按熱門天數降序），同級按名稱
                allTopSectors = sectorMap.entries
                    .map { it.key to it.value }
                    .sortedWith(
                        compareByDescending<Pair<String, String>> { sectorHotDays[it.first] ?: 0 }
                            .thenBy { it.second }
                    )
                hotSectorCodes = sectorHotDays.keys

                // 預載每個板塊的完整記錄
                val recordMap = mutableMapOf<String, List<SectorDailyRecordEntity>>()
                for ((code, _) in allTopSectors) {
                    recordMap[code] = db.sectorDailyRecordDao()
                        .getBySectorCode(code, 250).sortedBy { it.date }
                }
                sectorRecordMap = recordMap

                withContext(Dispatchers.Main) {
                    if (allTopSectors.isEmpty()) {
                        infoTv.text = "暫無板塊數據，請等待更新"
                        return@withContext
                    }
                    // 預設選中前 3 個板塊
                    selectedSectors.clear()
                    allTopSectors.take(3).forEach { selectedSectors.add(it.first) }
                    buildChips()
                    renderChart()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    infoTv.text = "載入失敗: ${e.message?.take(50)}"
                }
            }
        }
    }

    // ══════════════════════════════════════
    // Chip 構建
    // ══════════════════════════════════════

    private fun buildChips() {
        chipContainer.removeAllViews()
        val ctx = requireContext()
        for ((code, name) in allTopSectors) {
            val isSelected = code in selectedSectors
            val chip = TextView(ctx).apply {
                text = name
                textSize = 10f
                setPadding(10, 4, 10, 4)
                tag = code
                if (isSelected) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1976D2"))
                } else if (code in hotSectorCodes) {
                    // 熱門板塊：紅色底
                    setTextColor(Color.parseColor("#C62828"))
                    setBackgroundColor(Color.parseColor("#FFEBEE"))
                } else {
                    setTextColor(Color.parseColor("#666666"))
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 4 }
                setOnClickListener {
                    if (code in selectedSectors) {
                        if (selectedSectors.size > 1) selectedSectors.remove(code)
                    } else {
                        if (selectedSectors.size >= 5) {
                            // 最多 5 條，移除最早的
                            selectedSectors.remove(selectedSectors.first())
                        }
                        selectedSectors.add(code)
                    }
                    buildChips()
                    renderChart()
                }
            }
            chipContainer.addView(chip)
        }
    }

    // ══════════════════════════════════════
    // 圖表渲染
    // ══════════════════════════════════════

    private fun renderChart() {
        if (selectedSectors.isEmpty() || sectorRecordMap.isEmpty()) return

        // 找到所有選中板塊的日期並集（用於 X 軸）
        val allDates = sortedSetOf<String>()
        val sectorLineData = mutableListOf<Pair<String, List<SectorDailyRecordEntity>>>()

        for (code in selectedSectors) {
            val records = sectorRecordMap[code] ?: continue
            val filtered = if (rangeDays <= 0) records else records.takeLast(rangeDays)
            if (filtered.isEmpty()) continue
            filtered.forEach { allDates.add(it.date) }
            sectorLineData.add(code to filtered)
        }

        if (allDates.isEmpty() || sectorLineData.isEmpty()) {
            infoTv.text = "所選範圍無數據"
            return
        }

        val dateList = allDates.toList()
        val dateIndexMap = dateList.withIndex().associate { (i, d) -> d to i }

        // 為每個板塊構建累計指數折線
        val lineDataSets = mutableListOf<LineDataSet>()
        val infoParts = mutableListOf<String>()

        for ((idx, code) in selectedSectors.withIndex()) {
            val records = sectorRecordMap[code] ?: continue
            val filtered = if (rangeDays <= 0) records else records.takeLast(rangeDays)
            if (filtered.isEmpty()) continue

            val name = records.first().sectorName
            val color = SECTOR_COLORS[idx % SECTOR_COLORS.size]

            // 構建累計指數
            val entries = mutableListOf<Entry>()
            var cumIndex = 100.0
            for (r in filtered) {
                cumIndex *= (1 + r.changePct / 100)
                val xIdx = dateIndexMap[r.date] ?: continue
                entries.add(Entry(xIdx.toFloat(), cumIndex.toFloat()))
            }

            if (entries.isNotEmpty()) {
                lineDataSets.add(LineDataSet(entries, name).apply {
                    this.color = color
                    lineWidth = 1.8f
                    setDrawCircles(false)
                    setDrawValues(false)
                    isHighlightEnabled = true
                    setHighlightLineWidth(1f)
                    mode = LineDataSet.Mode.LINEAR
                })

                // 信息欄
                val latestIndex = cumIndex
                val totalChange = (cumIndex - 100.0) / 100.0 * 100
                val latest5 = filtered.takeLast(5)
                val avg5 = latest5.map { it.changePct }.average()
                infoParts.add("$name: ${"%.1f".format(latestIndex)}(${if (totalChange >= 0) "+" else ""}${"%.1f".format(totalChange)}%)")
            }
        }

        // 更新圖表
        chart.data = LineData(lineDataSets as List<ILineDataSet>)

        // X 軸
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

        // Y 軸
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EEEEEE")
            setLabelCount(6, true)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "${"%.1f".format(value)}"
            }
        }
        chart.axisRight.setDrawGridLines(false)

        // 定位到最新
        if (dateList.size > 60) {
            chart.moveViewToX((dateList.size - 60).toFloat())
        } else {
            chart.moveViewToX(0f)
        }
        chart.invalidate()

        // 信息欄
        infoTv.text = infoParts.joinToString("  |  ")
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

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
