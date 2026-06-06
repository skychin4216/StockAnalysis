package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.chin.stockanalysis.news.NewsFactorEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.stock.data.sources.SectorSubDivision
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope

/**
 * ## 热点新闻详情 (BottomSheet) v2.0 — 可点击交互版
 *
 * - 板块标签可点击 → 板块详情页
 * - 个股可点击 → StockDetailFragment（AI分析+类似个股）
 * - 相似板块 + 关联股票一键导航
 * - 标签可点击 → StockDataCenter 拼音搜索
 */
class HotNewsDetailFragment : BottomSheetDialogFragment() {

    var newsItem: NewsFactorEntity? = null
    var openStockTab: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 48)
            setBackgroundColor(Color.WHITE)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val n = newsItem ?: return root

        // 标题行
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 16) }
        titleRow.addView(TextView(ctx).apply {
            text = if (n.sentiment > 0) "📈" else if (n.sentiment < 0) "📉" else "➖"
            textSize = 24f; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 }
        })
        titleRow.addView(TextView(ctx).apply {
            text = n.title; textSize = 18f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(titleRow)

        // 日期 + 可点击板块
        val metaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }
        metaRow.addView(TextView(ctx).apply { text = "🕐 ${n.newsDate}"; textSize = 12f; setTextColor(Color.parseColor("#888888")) })
        if (n.sector.isNotBlank()) {
            val chip = TextView(ctx).apply {
                text = "  🏷 ${n.sector}"; textSize = 12f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD); setPadding(4, 2, 4, 2)
                setOnClickListener {
                    val d = SectorDetailFragment.newInstance(n.sector, "")
                    activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit(); dismiss()
                }
            }; metaRow.addView(chip)
        }
        val sc = if (n.sentiment > 0) Color.parseColor("#2E7D32") else Color.parseColor("#E53935")
        metaRow.addView(TextView(ctx).apply { text = "  ${if (n.sentiment > 0) "利好" else "利空"} (${n.impactStrength})"; textSize = 12f; setTextColor(sc); setTypeface(null, Typeface.BOLD) })
        root.addView(metaRow)

        root.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#EEEEEE")); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2).apply { bottomMargin = 12 } })

        // 内容
        root.addView(TextView(ctx).apply { text = "📰 详细内容:"; textSize = 14f; setTextColor(Color.parseColor("#333333")); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 4) })
        root.addView(TextView(ctx).apply { text = n.content.ifBlank { "暂无详细内容" }; textSize = 14f; setTextColor(Color.parseColor("#444444")); setPadding(0, 0, 0, 12) })

        // 可点击标签行
        if (n.tags.isNotBlank()) {
            root.addView(TextView(ctx).apply { text = "🏷 标签: "; textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(0, 0, 0, 4) })
            val tagsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }
            for (tag in n.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(8)) {
                val tc = TextView(ctx).apply {
                    text = tag; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0")); setPadding(8, 3, 8, 3)
                    (layoutParams as? LayoutParams)?.setMargins(0, 0, 6, 0)
                    setOnClickListener {
                        lifecycleScope.launch {
                            val stocks = StockDataCenter.searchStocks(tag)
                            if (stocks.isNotEmpty()) {
                                val (code, name) = stocks.first()
                                val d = StockDetailFragment.newInstance(code, name, sectorName = tag)
                                activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit(); dismiss()
                            } else {
                                val sectors = StockDataCenter.searchSectors(tag)
                                if (sectors.isNotEmpty()) {
                                    val d = SectorDetailFragment.newInstance(sectors.first(), "")
                                    activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit(); dismiss()
                                } else Toast.makeText(ctx, "未找到匹配: $tag", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }; tagsRow.addView(tc)
            }; root.addView(tagsRow)
        }

        root.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#EEEEEE")); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 2).apply { topMargin = 4; bottomMargin = 12 } })

        // 关联股票标题
        root.addView(TextView(ctx).apply { text = "📊 关联股票 + 相似板块（点击查看详情+AI分析）"; textSize = 15f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8) })

        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 8) }
        val loadingTv = TextView(ctx).apply { text = "  ⏳ 正在匹配..."; textSize = 12f; setTextColor(Color.parseColor("#999999")) }; container.addView(loadingTv)
        root.addView(container)

        lifecycleScope.launch {
            val stocks = findRelatedStocks(n)
            withContext(Dispatchers.Main) {
                container.removeView(loadingTv)
                if (stocks.isNotEmpty()) {
                    addSimilarSectors(ctx, n, container)
                    for (s in stocks) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4, 8, 4, 8)
                            setBackgroundColor(Color.parseColor("#F5F6FA")); (layoutParams as? LayoutParams)?.setMargins(0, 4, 0, 0)
                            setOnClickListener {
                                val d = StockDetailFragment.newInstance(s.code, s.name)
                                activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit(); dismiss()
                            }
                        }
                        row.addView(TextView(ctx).apply { text = "${s.name} (${s.code.takeLast(6)})\n${s.business.take(20)}"; textSize = 13f; setTextColor(Color.parseColor("#333333")); layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
                        val aiBtn = TextView(ctx).apply {
                            text = "🤖 AI分析"; textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#E65100")); setPadding(8, 4, 8, 4)
                            setOnClickListener {
                                val sector = n.sector.ifBlank { "相关板块" }
                                val q = """请对${s.name}(${s.code.takeLast(6)})进行全面分析：
1. 结合新闻「${n.title}」，判断${if (n.sentiment > 0) "利好" else "利空"}影响程度
2. 当前买点/卖点判断（技术面+基本面）
3. 所在「$sector」板块趋势行情（是否轮动启动）
4. 公司主营业务简介，核心竞争力
5. 是否存在暴雷风险（资金、业绩、债务）
6. 结论：建议持有/买入/观望/规避，并给出理由"""
                                if (activity is MainActivity) { (activity as MainActivity).switchToChatAndSend(q); dismiss() }
                            }
                        }; row.addView(aiBtn); container.addView(row)
                    }
                } else container.addView(TextView(ctx).apply { text = "  ⚠️ 未找到关联股票"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(0, 4, 0, 0) })
            }
        }
        return root
    }

    private fun addSimilarSectors(ctx: android.content.Context, n: NewsFactorEntity, container: LinearLayout) {
        if (n.sector.isBlank()) return
        val sub = SectorSubDivision.getSubSectors(n.sector)
        if (sub.isEmpty()) return
        container.addView(TextView(ctx).apply { text = "🔗 相似板块"; textSize = 13f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD); setPadding(0, 8, 0, 4) })
        for (ss in sub.take(3)) {
            val sr = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4, 6, 4, 6)
                setOnClickListener {
                    val d = SectorDetailFragment.newInstance(ss.name, "")
                    activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit(); dismiss()
                }
            }
            sr.addView(TextView(ctx).apply { text = "📌 ${ss.name}"; textSize = 12f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD); layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
            sr.addView(TextView(ctx).apply { text = "${ss.mainBoardStocks.size + ss.gemKcbStocks.size}只 →"; textSize = 11f; setTextColor(Color.parseColor("#E65100")) })
            container.addView(sr)
            for (st in ss.mainBoardStocks.take(3)) {
                val str = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(20, 2, 4, 2)
                    setOnClickListener {
                        val d = StockDetailFragment.newInstance(st.code, st.name)
                        activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit(); dismiss()
                    }
                }
                str.addView(TextView(ctx).apply { text = "  ${st.name} (${st.code.takeLast(6)})"; textSize = 11f; setTextColor(Color.parseColor("#555555")) })
                container.addView(str)
            }
        }
    }

    data class RelatedStock(val name: String, val code: String, val business: String)

    /** 判断是否主板（排除创业板300/301、科创板688、北交所bj） */
    private fun isMainBoard(code: String) = !(code.startsWith("sz300") || code.startsWith("sz301") || code.startsWith("sh688") || code.startsWith("bj"))

    private suspend fun findRelatedStocks(news: NewsFactorEntity): List<RelatedStock> = withContext(Dispatchers.IO) {
        try {
            val db = StockDatabase.getInstance(requireContext())
            val results = mutableListOf<RelatedStock>()
            // 1. 精确个股
            if (news.stockCode.isNotBlank()) {
                db.stockBasicDao().getByCode(news.stockCode)?.let { results.add(RelatedStock(it.name, it.code, it.business)) }
            }
            // 2. 标签搜索
            for (tag in news.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(3)) {
                for (m in db.stockBasicDao().searchByName(tag)) {
                    if (results.none { it.code == m.code }) results.add(RelatedStock(m.name, m.code, m.business))
                }
            }
            // 3. 板块关联 — 主板优先
            if (news.sector.isNotBlank()) {
                try {
                    val codes = db.sectorStockDao().getStockCodesBySector(news.sector)
                    val all = db.stockBasicDao().getByCodes(codes.take(20))
                    val mainBoard = all.filter { isMainBoard(it.code) }
                    val others = all.filter { !isMainBoard(it.code) }
                    for (b in (mainBoard + others).take(10)) {
                        if (results.none { it.code == b.code } && results.size < 10) results.add(RelatedStock(b.name, b.code, b.business))
                    }
                } catch (_: Exception) {}
            }
            // 主板优先排序
            results.sortedByDescending { isMainBoard(it.code) }.take(10)
        } catch (e: Exception) { emptyList() }
    }
}