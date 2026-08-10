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
 * 板块轮动图 — 热力图展示不同时间周期各板块的表现
 *
 * 设计概念：
 * - X 轴：时间周期（最近 4 周 / 3 个月 / 每季度）
 * - Y 轴：热门板块
 * - 颜色：涨跌幅（红=涨, 绿=跌, 深浅=幅度）
 *
 * 用途：一眼看出板块轮动规律，哪个板块在哪个时间段表现好
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

        // 标题
        root.addView(TextView(ctx).apply {
            text = "🔄 板块轮动热力图"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 8)
        })

        // 说明
        root.addView(TextView(ctx).apply {
            text = "颜色越红表示涨幅越大，越绿表示跌幅越大"
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 12)
        })

        // 信息栏
        infoTv = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, 8)
        }
        root.addView(infoTv)

        // 热力图容器（可滚动）
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

        // 载入数据
        loadRotationData()

        return root
    }

    private fun loadRotationData() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val source = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource()
                // 从 API 直接获取行业+概念板块（含多周期涨跌幅）
                val industry = source.fetchSectorsByTypeDirect(2, 30)
                val concept = source.fetchSectorsByTypeDirect(3, 30)
                val allSectors = (industry + concept).distinctBy { it.code }

                if (allSectors.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        infoTv.text = "暂无板块数据，请稍后重试"
                    }
                    return@launch
                }

                val periodNames = listOf("今日", "5日", "10日", "20日")

                // 按综合得分排序，取 top 20
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
                    infoTv.text = "载入失败: ${e.message?.take(50)}"
                }
            }
        }
    }

    private fun renderHeatmap(data: List<RotationRow>, periodNames: List<String>) {
        heatmapContainer.removeAllViews()
        val ctx = requireContext()

        // 表头
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        headerRow.addView(TextView(ctx).apply {
            text = "板块"
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

        // 分隔线
        heatmapContainer.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        })

        // 数据行
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
                    // 颜色：红涨绿跌
                    if (value != null) {
                        val intensity = (kotlin.math.abs(value) / 5 * 255).toInt().coerceIn(50, 200)
                        val color = if (value >= 0) {
                            Color.rgb(255, 255 - intensity, 255 - intensity) // 红色系
                        } else {
                            Color.rgb(255 - intensity, 255, 255 - intensity) // 绿色系
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

        // 图例
        heatmapContainer.addView(TextView(ctx).apply {
            text = "\n图例：深红=大涨 | 浅红=小涨 | 浅绿=小跌 | 深绿=大跌"
            textSize = 10f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 12, 0, 0)
        })

        infoTv.text = "共 ${data.size} 个板块 | 数据来源：东方财富即时行情"
    }

    private data class RotationRow(
        val sectorName: String,
        val periods: MutableMap<String, Double> = mutableMapOf()
    )
}
