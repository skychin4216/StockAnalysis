package com.chin.stockanalysis.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.R
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.SectorDailyRecordEntity
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 板塊走勢圖 — 展示近期熱門板塊的漲跌/資金/熱度趨勢
 *
 * 數據來源：sector_daily_record
 * 圖表：CombinedChart（折線 + 柱狀）
 */
class SectorTrendChartFragment : Fragment() {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd")

    private lateinit var chart: CombinedChart
    private lateinit var spinner: Spinner
    private lateinit var infoTv: TextView
    private lateinit var rangeRow: LinearLayout

    private var sectorRecords: List<SectorDailyRecordEntity> = emptyList()
    private var allTopSectors: List<Pair<String, String>> = emptyList() // code to name
    private var rangeDays = 90 // 預設 3 個月

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

        // 標題行
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val titleTv = TextView(ctx).apply {
            text = "板塊走勢"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
        }
        titleRow.addView(titleTv)
        root.addView(titleRow)

        // 板塊選擇器
        spinner = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < allTopSectors.size) {
                    loadSectorChart(allTopSectors[position].first)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        root.addView(spinner)

        // 時間範圍選擇按鈕行
        rangeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        data class RangeBtn(val label: String, val days: Int)
        val rangeBtns = listOf(
            RangeBtn("1月", 30),
            RangeBtn("3月", 90),
            RangeBtn("6月", 120),
            RangeBtn("1年", 250),
            RangeBtn("全部", 0)
        )
        for (rb in rangeBtns) {
            val isActive = rangeDays == rb.days
            val btn = TextView(ctx).apply {
                text = rb.label
                textSize = 10f
                setTextColor(if (isActive) Color.WHITE else Color.parseColor("#666666"))
                setBackgroundColor(if (isActive) Color.parseColor("#1976D2") else Color.parseColor("#EEEEEE"))
                setPadding(12, 4, 12, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 4 }
                setOnClickListener {
                    rangeDays = rb.days
                    renderChart()
                }
            }
            rangeRow.addView(btn)
        }
        root.addView(rangeRow)

        // 信息文字
        infoTv = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
        }
        root.addView(infoTv)

        // 圖表
        chart = CombinedChart(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ).apply { topMargin = 8 }
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            setDrawValueAboveBar(false)
            setHighlightFullBarEnabled(false)
            setPinchZoom(true)
            setScaleEnabled(true)
            isDoubleTapToZoomEnabled = true
        }
        root.addView(chart)

        // 載入數據
        loadTopSectors()

        return root
    }

    private fun loadTopSectors() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                // 取最近 30 天的 top 板塊
                val recentDays = db.sectorDailyRecordDao().getRecentDays(30)
                val sectorMap = mutableMapOf<String, String>() // code to name
                for (r in recentDays) {
                    if (r.rank <= 15) { // 只取每天前 15 名
                        sectorMap[r.sectorCode] = r.sectorName
                    }
                }
                allTopSectors = sectorMap.entries.map { it.key to it.value }.sortedBy { it.second }

                withContext(Dispatchers.Main) {
                    if (allTopSectors.isEmpty()) {
                        infoTv.text = "暫無板塊歷史數據，請等待每日板塊數據更新"
                        return@withContext
                    }
                    // 填充 Spinner
                    val names = allTopSectors.map { it.second }
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinner.adapter = adapter
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    infoTv.text = "載入失敗: ${e.message?.take(50)}"
                }
            }
        }
    }

    private fun loadSectorChart(sectorCode: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                sectorRecords = db.sectorDailyRecordDao()
                    .getBySectorCode(sectorCode, 250)
                    .sortedBy { it.date }

                withContext(Dispatchers.Main) {
                    renderChart()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    infoTv.text = "載入圖表失敗: ${e.message?.take(50)}"
                }
            }
        }
    }

    private fun renderChart() {
        if (sectorRecords.isEmpty()) {
            infoTv.text = "無歷史數據"
            return
        }

        // 按時間範圍篩選
        val filteredRecords = if (rangeDays == 0) {
            sectorRecords
        } else {
            sectorRecords.takeLast(rangeDays)
        }

        if (filteredRecords.isEmpty()) {
            infoTv.text = "此時間範圍內無數據"
            return
        }

        // 更新按鈕狀態
        updateRangeButtons()

        val sectorName = filteredRecords.first().sectorName
        val dates = filteredRecords.map {
            try { LocalDate.parse(it.date, DATE_FMT).format(SHORT_DATE) }
            catch (_: Exception) { it.date.takeLast(5) }
        }

        // ── 構建累計指數（基準=100）──
        val indexValues = mutableListOf<Double>()
        var cumulativeIndex = 100.0
        for (r in filteredRecords) {
            cumulativeIndex *= (1 + r.changePct / 100)
            indexValues.add(cumulativeIndex)
        }

        // ── 構建 OHLC 模擬（用於 K 線顯示）──
        // 由於板塊只有漲跌幅，我們用漲跌幅模擬 K 線形態
        val candleEntries = filteredRecords.mapIndexed { index, r ->
            val baseValue = if (index == 0) 100.0 else indexValues[index - 1]
            val closeValue = indexValues[index]
            val openValue = baseValue
            // 模擬高低點：用漲跌幅的絕對值作為波動範圍
            val volatility = kotlin.math.abs(r.changePct) * baseValue / 100
            val highValue = maxOf(openValue, closeValue) + volatility * 0.3
            val lowValue = minOf(openValue, closeValue) - volatility * 0.3
            CandleEntry(
                index.toFloat(),
                highValue.toFloat(),
                lowValue.toFloat(),
                openValue.toFloat(),
                closeValue.toFloat()
            )
        }

        // ── 均線計算 ──
        val ma5Entries = calcMA(indexValues, 5)
        val ma10Entries = calcMA(indexValues, 10)
        val ma20Entries = calcMA(indexValues, 20)

        // ── 成交量柱狀（資金流入）──
        val inflowMax = filteredRecords.map { kotlin.math.abs(it.mainNetInflow) }.maxOrNull() ?: 1.0
        val volumeEntries = filteredRecords.mapIndexed { index, r ->
            BarEntry(index.toFloat(), (r.mainNetInflow / inflowMax * 30).toFloat())
        }

        // ── 建立圖表 ──
        chart.apply {
            setBackgroundColor(Color.WHITE)
            description.isEnabled = false
            legend.isEnabled = true
            legend.textSize = 9f
            legend.textColor = Color.parseColor("#666666")
            setScaleEnabled(true)
            setPinchZoom(true)
            setDragEnabled(true)
            setDoubleTapToZoomEnabled(true)
            setHighlightPerTapEnabled(true)
            setHighlightPerDragEnabled(true)
            setVisibleXRangeMaximum(250f)
            setVisibleXRangeMinimum(10f)
            drawOrder = arrayOf(
                CombinedChart.DrawOrder.CANDLE,
                CombinedChart.DrawOrder.LINE,
                CombinedChart.DrawOrder.BAR
            )
            // 定位到最新數據
            if (filteredRecords.size > 60) {
                moveViewToX((filteredRecords.size - 60).toFloat())
            } else {
                moveViewToX(0f)
            }
        }

        // ── K 線數據 ──
        val candleDataSet = CandleDataSet(candleEntries, "板塊指數").apply {
            color = Color.parseColor("#333333")
            shadowColor = Color.parseColor("#999999")
            shadowWidth = 1f
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = Color.parseColor("#E53935")  // 漲紅
            decreasingColor = Color.parseColor("#43A047")  // 跌綠
            setDrawValues(false)
            isHighlightEnabled = true
            setDrawHighlightIndicators(true)
            setHighLightColor(Color.parseColor("#999999"))
            setHighlightLineWidth(1f)
            enableDashedHighlightLine(8f, 4f, 0f)
        }

        // ── 均線數據 ──
        val lineData = LineData()
        if (ma5Entries.isNotEmpty()) {
            lineData.addDataSet(LineDataSet(ma5Entries, "MA5").apply {
                color = Color.parseColor("#FF9800")
                lineWidth = 1.2f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }
        if (ma10Entries.isNotEmpty()) {
            lineData.addDataSet(LineDataSet(ma10Entries, "MA10").apply {
                color = Color.parseColor("#2196F3")
                lineWidth = 1.2f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }
        if (ma20Entries.isNotEmpty()) {
            lineData.addDataSet(LineDataSet(ma20Entries, "MA20").apply {
                color = Color.parseColor("#9C27B0")
                lineWidth = 1.2f
                setDrawCircles(false)
                setDrawValues(false)
                isHighlightEnabled = false
            })
        }

        // ── 成交量數據 ──
        val volumeDataSet = BarDataSet(volumeEntries, "資金流入").apply {
            // 根據漲跌著色
            val colors = filteredRecords.map { r ->
                if (r.changePct >= 0) Color.parseColor("#33E53935") else Color.parseColor("#3343A047")
            }
            setColors(colors)
            setDrawValues(false)
            barShadowColor = Color.TRANSPARENT
        }

        // ── 組合數據 ──
        val combinedData = CombinedData()
        combinedData.setData(CandleData(candleDataSet))
        combinedData.setData(lineData)
        combinedData.setData(BarData(volumeDataSet))
        chart.data = combinedData

        // ── X 軸 ──
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            labelCount = dates.size.coerceAtMost(8)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val idx = value.toInt()
                    return if (idx in dates.indices) dates[idx] else ""
                }
            }
        }

        // ── 左 Y 軸（指數）──
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EEEEEE")
            setLabelCount(6, true)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "${"%.1f".format(value)}"
            }
        }

        // ── 右 Y 軸（成交量）──
        chart.axisRight.apply {
            setDrawGridLines(false)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "${"%.0f".format(value)}"
            }
        }

        chart.invalidate()

        // ── 更新信息欄 ──
        val latest = filteredRecords.last()
        val latestIndex = indexValues.last()
        val avgChange = filteredRecords.takeLast(5).map { it.changePct }.average()
        val totalInflow = filteredRecords.takeLast(5).sumOf { it.mainNetInflow }
        val hotDays = filteredRecords.takeLast(10).count { it.isHot in listOf("S", "A") }
        infoTv.text = "$sectorName | 指數: ${"%.2f".format(latestIndex)} | " +
            "最新: ${"%.2f".format(latest.changePct)}% | " +
            "5日均漲: ${"%.2f".format(avgChange)}% | " +
            "5日資金: ${"%.0f".format(totalInflow)}億 | " +
            "10日熱天: $hotDays"
    }

    /** 計算移動平均線 */
    private fun calcMA(values: List<Double>, period: Int): List<Entry> {
        if (values.size < period) return emptyList()
        val result = mutableListOf<Entry>()
        for (i in (period - 1) until values.size) {
            val sum = values.subList(i - period + 1, i + 1).sum()
            result.add(Entry(i.toFloat(), (sum / period).toFloat()))
        }
        return result
    }

    private fun updateRangeButtons() {
        for (i in 0 until rangeRow.childCount) {
            val btn = rangeRow.getChildAt(i) as? TextView ?: continue
            val days = when (btn.text.toString()) {
                "1月" -> 30
                "3月" -> 90
                "6月" -> 120
                "1年" -> 250
                else -> 0
            }
            val isActive = rangeDays == days
            btn.setTextColor(if (isActive) Color.WHITE else Color.parseColor("#666666"))
            btn.setBackgroundColor(if (isActive) Color.parseColor("#1976D2") else Color.parseColor("#EEEEEE"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
