package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.data.sources.SectorSubDivision
import com.chin.stockanalysis.stock.database.SectorStockEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 板块详情页（v3.1 网络优先版）
 *
 * 有 sectorCode → 网络拉取龙头股表格（名称/现价/涨跌/换手/主力流入/市值/标签）
 * 无 sectorCode → 展示静态精选股票库
 */
class SectorDetailFragment : Fragment() {

    companion object {
        private const val ARG_SECTOR_NAME = "sector_name"
        private const val ARG_SECTOR_CODE = "sector_code"
        fun newInstance(sectorName: String, sectorCode: String = ""): SectorDetailFragment {
            return SectorDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTOR_NAME, sectorName)
                    putString(ARG_SECTOR_CODE, sectorCode)
                }
            }
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var container: LinearLayout
    private lateinit var loadingTv: TextView
    private val hotSource = EastMoneyHotSectorSource()

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val sectorName = arguments?.getString(ARG_SECTOR_NAME) ?: "热门板块"
        val sectorCode = arguments?.getString(ARG_SECTOR_CODE) ?: ""
        buildHeader(sectorName)
        container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 80)
        }
        loadingTv = TextView(requireContext()).apply {
            text = "⏳ 加载中..."; textSize = 14f; setTextColor(Color.GRAY); setPadding(24, 24, 24, 24)
        }
        root.addView(container)
        root.addView(loadingTv)
        loadData(sectorName, sectorCode)
        return root
    }

    private fun buildHeader(name: String) {
        val h = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 40, 24, 12); setBackgroundColor(Color.WHITE)
        }
        h.addView(TextView(requireContext()).apply {
            text = "📊 $name"
            textSize = 22f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(h)
    }

    private fun loadData(sectorName: String, sectorCode: String) {
        // 有 BK 代码 → 网络拉取实时龙头表格（含现价/涨跌/换手/主力流入/市值）
        if (sectorCode.isNotEmpty()) {
            lifecycleScope.launch {
                val leaders = withContext(Dispatchers.IO) { hotSource.fetchSectorLeaders(sectorCode, 20) }
                loadingTv.visibility = View.GONE
                if (leaders.isNotEmpty()) {
                    // 保存板块→股票映射到数据库（反向查询用）
                    lifecycleScope.launch(Dispatchers.IO) {
                        saveSectorStockMapping(sectorName, sectorCode, leaders)
                    }
                    container.addView(createLeaderCard(sectorName, leaders))
                } else {
                    showStaticData(sectorName)
                }
            }
            return
        }
        // 无 BK 代码 → 直接展示静态精选数据库
        loadingTv.visibility = View.GONE
        showStaticData(sectorName)
    }

    private fun showStaticData(sectorName: String) {
        val subSectors = SectorSubDivision.getSubSectors(sectorName)
        if (subSectors.isNotEmpty()) {
            for (ss in subSectors.take(5)) {
                container.addView(createSubSectorCard(ss))
            }
        } else {
            container.addView(TextView(requireContext()).apply {
                text = "暂无数据"; textSize = 14f; setTextColor(Color.GRAY); setPadding(24, 24, 24, 24)
            })
        }
    }

    // ======================== 实时龙头股表格 ========================

    private fun createLeaderCard(sectorName: String, leaders: List<EastMoneyHotSectorSource.LeaderStock>): LinearLayout {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(16, 12, 16, 12)
            (layoutParams as? LayoutParams)?.setMargins(0, 0, 0, 12); elevation = 3f
        }
        card.addView(TextView(ctx).apply {
            text = "📌 $sectorName · 龙头股 (Top ${leaders.size})"
            textSize = 16f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        card.addView(TextView(ctx).apply {
            text = "按市值排序 | 实时数据来源：东方财富行情 API"
            textSize = 11f; setTextColor(Color.parseColor("#999999")); setPadding(0, 0, 0, 10)
        })
        // 表头
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#E3F2FD")); setPadding(4, 6, 4, 6)
        }
        val hLabels = listOf("名称/代码", "现价", "涨跌%", "换手", "主力流入", "市值", "标签")
        val hWeights = listOf(2.5f, 1.0f, 1.0f, 1.0f, 1.2f, 1.0f, 1.3f)
        for ((i, label) in hLabels.withIndex()) {
            header.addView(TextView(ctx).apply {
                text = label; textSize = 10f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1565C0")); gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, hWeights[i])
            })
        }
        card.addView(header)
        val sectorNameFinal = sectorName
        for ((idx, s) in leaders.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(4, 6, 4, 6)
                if (idx % 2 == 1) setBackgroundColor(Color.parseColor("#F5F5F5"))
                // 点击股票 → 跳转到股票详情页（而非板块页）
                setOnClickListener {
                    val detail = StockDetailFragment.newInstance(
                        stockCode = s.code.takeIf { it.isNotBlank() } ?: "",
                        stockName = s.name,
                        price = s.price,
                        changePct = s.changePercent,
                        sectorName = sectorNameFinal
                    )
                    activity?.supportFragmentManager
                        ?.beginTransaction()
                        ?.replace(android.R.id.content, detail)
                        ?.addToBackStack(null)
                        ?.commit()
                }
            }
            row.addView(TextView(ctx).apply {
                text = "${idx + 1}. ${s.name}\n${s.code.takeLast(6)}"
                textSize = 11f; setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, hWeights[0])
            })
            row.addView(TextView(ctx).apply {
                text = if (s.price > 0) "${"%.2f".format(s.price)}" else "—"
                textSize = 11f; setTextColor(Color.parseColor("#E65100")); gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, hWeights[1])
            })
            row.addView(TextView(ctx).apply {
                val sign = if (s.changePercent >= 0) "+" else ""
                text = if (s.changePercent != 0.0) "$sign${"%.2f".format(s.changePercent)}%" else "—"
                textSize = 11f; gravity = Gravity.CENTER
                setTextColor(if (s.changePercent >= 0) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, hWeights[2])
            })
            row.addView(TextView(ctx).apply {
                text = if (s.turnoverRate > 0) "${"%.1f".format(s.turnoverRate)}%" else "—"
                textSize = 11f; setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, hWeights[3])
            })
            row.addView(TextView(ctx).apply {
                val inflow = s.mainNetInflow
                text = if (inflow != 0.0) "${if (inflow > 0) "+" else ""}${"%.1f".format(inflow)}亿" else "—"
                textSize = 11f; gravity = Gravity.CENTER
                setTextColor(if (inflow >= 0) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, hWeights[4])
            })
            row.addView(TextView(ctx).apply {
                text = if (s.marketCap > 0) "${"%.0f".format(s.marketCap)}亿" else "—"
                textSize = 10f; setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, hWeights[5])
            })
            row.addView(TextView(ctx).apply {
                val tags = mutableListOf<String>()
                if (s.isBoard) tags.add("🔴涨停")
                if (s.limitDays > 0) tags.add("连${s.limitDays}板")
                if (s.threeDayInflow > 0.5) tags.add("3日流入${"%.1f".format(s.threeDayInflow)}亿")
                text = tags.joinToString(" ")
                textSize = 9f; setTextColor(Color.parseColor("#E65100")); gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, hWeights[6])
            })
            card.addView(row)
        }
        return card
    }

    // ======================== 静态精选子板块 ========================

    // ======================== 板块→股票映射持久化 ========================

    /** 将东方财富返回的龙头股存入 sector_stocks 表，实现反向查询 */
    private suspend fun saveSectorStockMapping(
        sectorName: String, sectorCode: String, leaders: List<EastMoneyHotSectorSource.LeaderStock>
    ) {
        try {
            val db = StockDatabase.getInstance(requireContext())
            // 生成 sector_key：用 sector_name 做 key（与 ThemeStockLibrary 一致）
            val sectorKey = sectorName
            val entities = leaders.mapNotNull { leader ->
                val code = leader.code
                if (code.isBlank()) return@mapNotNull null
                // 补全交易所前缀（东方财富 f12 字段只返回数字代码）
                val fullCode = when {
                    code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj") -> code
                    code.startsWith("6") || code.startsWith("9") -> "sh$code"
                    code.startsWith("4") || code.startsWith("8") -> "bj$code"
                    else -> "sz$code"
                }
                // 补全0开头的代码为6位
                val fullCode6 = if (fullCode.take(2) in setOf("sh","sz","bj") && fullCode.length == 8) fullCode
                    else if (fullCode.take(2) == "sh" && fullCode.length < 8) "sh${code.padStart(6, '0')}"
                    else fullCode

                SectorStockEntity(sectorKey = sectorKey, sectorName = sectorName, stockCode = fullCode6)
            }
            if (entities.isNotEmpty()) {
                db.sectorStockDao().insertAll(entities)
                android.util.Log.i("SectorDetail", "保存板块映射: $sectorName → ${entities.size} 只股票")
            }
        } catch (e: Exception) {
            android.util.Log.w("SectorDetail", "保存板块映射失败: ${e.message}")
        }
    }

    // ======================== 静态精选子板块 ========================

    private fun createSubSectorCard(ss: SectorSubDivision.SubSector): LinearLayout {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(16, 12, 16, 12)
            (layoutParams as? LayoutParams)?.setMargins(0, 0, 0, 12); elevation = 3f
        }
        card.addView(TextView(ctx).apply {
            text = "📌 ${ss.name}"; textSize = 16f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
        })
        card.addView(TextView(ctx).apply {
            text = ss.description; textSize = 12f; setTextColor(Color.parseColor("#888888")); setPadding(0, 4, 0, 10)
        })
        val mainList = ss.mainBoardStocks
        if (mainList.isNotEmpty()) {
            card.addView(TextView(ctx).apply {
                text = "▸ 主板"; textSize = 13f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD)
                setPadding(0, 8, 0, 6)
            })
            for (s in mainList) card.addView(createStockRow(ctx, s))
        }
        val gemList = ss.gemKcbStocks
        if (gemList.isNotEmpty()) {
            card.addView(TextView(ctx).apply {
                text = "▸ 创业板/科创板"; textSize = 13f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD)
                setPadding(0, 8, 0, 6)
            })
            for (s in gemList) card.addView(createStockRow(ctx, s))
        }
        return card
    }

    private fun createStockRow(ctx: android.content.Context, stock: SectorSubDivision.EnrichedStock): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(8, 6, 0, 4)
            (layoutParams as? LayoutParams)?.setMargins(0, 0, 0, 6)
            setBackgroundColor(Color.parseColor("#F8F9FC"))
            // 点击静态股票 → 跳转到股票详情页（而非板块页）
            setOnClickListener {
                val detail = StockDetailFragment.newInstance(
                    stockCode = stock.code,
                    stockName = stock.name,
                    sectorName = "" // 从子板块推断
                )
                activity?.supportFragmentManager
                    ?.beginTransaction()
                    ?.replace(android.R.id.content, detail)
                    ?.addToBackStack(null)
                    ?.commit()
            }
        }
        val boardTag = if (stock.isMainBoard) "" else " [${stock.boardType}]"
        row.addView(TextView(ctx).apply {
            text = "${stock.name}$boardTag (${stock.code.takeLast(6)}) · ${stock.business}"
            textSize = 12f; setTextColor(Color.parseColor("#333333"))
        })
        if (stock.orders.isNotEmpty()) row.addView(TextView(ctx).apply {
            text = "订单: ${stock.orders}"; textSize = 10f; setTextColor(Color.parseColor("#1565C0"))
        })
        if (stock.recentNews.isNotEmpty()) row.addView(TextView(ctx).apply {
            text = "${if (stock.bullishNews) "📈" else "📉"} ${stock.recentNews}"
            textSize = 10f
            setTextColor(if (stock.bullishNews) Color.parseColor("#E53935") else Color.parseColor("#43A047"))
        })
        return row
    }
}