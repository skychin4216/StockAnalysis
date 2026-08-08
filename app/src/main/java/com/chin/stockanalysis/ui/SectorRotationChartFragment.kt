package com.chin.stockanalysis.ui

import android.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 板塊輪動圖 — 熱力圖展示不同時間週期各板塊的表現
 *
 * 設計概念：
 * - X 軸：時間週期（最近 4 週 / 3 個月 / 每季度）
 * - Y 軸：熱門板塊
 * - 顏色：漲跌幅（紅=漲, 綠=跌, 深淺=幅度）
 *
 * 用途：一眼看出板塊輪動規律，哪個板塊在哪个时间段表現好
 */
class SectorRotationChartFragment : Fragment() {

    private lateinit var heatmapContainer: LinearLayout
    private lateinit var infoTv: TextView

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
            setPadding(12, 12, 12, 12)
        }

        // 標題
        root.addView(TextView(ctx).apply {
            text = "🔄 板塊輪動熱力圖"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 8)
        })

        // 說明
        root.addView(TextView(ctx).apply {
            text = "顏色越紅表示漲幅越大，越綠表示跌幅越大"
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 12)
        })

        // 信息欄
        infoTv = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, 8)
        }
        root.addView(infoTv)

        // 熱力圖容器（可滾動）
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        heatmapContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(heatmapContainer)
        root.addView(scrollView)

        // 載入數據
        loadRotationData()

        return root
    }

    private fun loadRotationData() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val source = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource()
                // 從 API 直接獲取行業+概念板塊（含多周期漲跌幅）
                val industry = source.fetchSectorsByTypeDirect(2, 30)
                val concept = source.fetchSectorsByTypeDirect(3, 30)
                val allSectors = (industry + concept).distinctBy { it.code }

                if (allSectors.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        infoTv.text = "暫無板塊數據，請稍後重試"
                    }
                    return@launch
                }

                val periodNames = listOf("今日", "5日", "10日", "20日")

                // 按綜合得分排序，取 top 20
                val topSectors = allSectors.sortedByDescending { it.compositeScore }.take(20)

                val rotationData = mutableListOf<RotationRow>()
                for (s in topSectors) {
                    val row = RotationRow(s.name)
                    row.periods["今日"] = s.changePercent
                    if (s.change5d != 0.0) row.periods["5日"] = s.change5d
                    if (s.change10d != 0.0) row.periods["10日"] = s.change10d
                    if (s.change20d != 0.0) row.periods["20日"] = s.change20d
                    rotationData.add(row)
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    renderHeatmap(rotationData, periodNames)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    infoTv.text = "載入失敗: ${e.message?.take(50)}"
                }
            }
        }
    }

    private fun renderHeatmap(data: List<RotationRow>, periodNames: List<String>) {
        heatmapContainer.removeAllViews()
        val ctx = requireContext()

        // 表頭
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        headerRow.addView(TextView(ctx).apply {
            text = "板塊"
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        })
        for (name in periodNames) {
            headerRow.addView(TextView(ctx).apply {
                text = name
                textSize = 10f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        heatmapContainer.addView(headerRow)

        // 分隔線
        heatmapContainer.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        })

        // 數據行
        for (row in data) {
            val dataRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 2, 0, 2)
            }
            dataRow.addView(TextView(ctx).apply {
                text = row.sectorName
                textSize = 11f
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
                setPadding(0, 4, 4, 4)
            })
            for (periodName in periodNames) {
                val value = row.periods[periodName]
                dataRow.addView(TextView(ctx).apply {
                    text = if (value != null) "${"%.1f".format(value)}%" else "-"
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding(2, 4, 2, 4)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    // 顏色：紅漲綠跌
                    if (value != null) {
                        val intensity = (kotlin.math.abs(value) / 5 * 255).toInt().coerceIn(50, 200)
                        val color = if (value >= 0) {
                            Color.rgb(255, 255 - intensity, 255 - intensity) // 紅色系
                        } else {
                            Color.rgb(255 - intensity, 255, 255 - intensity) // 綠色系
                        }
                        setBackgroundColor(color)
                        setTextColor(if (intensity > 150) Color.WHITE else Color.parseColor("#333333"))
                    } else {
                        setTextColor(Color.parseColor("#AAAAAA"))
                    }
                })
            }
            heatmapContainer.addView(dataRow)
        }

        // 圖例
        heatmapContainer.addView(TextView(ctx).apply {
            text = "\n圖例：深紅=大漲 | 淺紅=小漲 | 淺綠=小跌 | 深綠=大跌"
            textSize = 10f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 12, 0, 0)
        })

        infoTv.text = "共 ${data.size} 個板塊 | 數據來源：東方財富即時行情"
    }

    private data class RotationRow(
        val sectorName: String,
        val periods: MutableMap<String, Double> = mutableMapOf()
    )
}
