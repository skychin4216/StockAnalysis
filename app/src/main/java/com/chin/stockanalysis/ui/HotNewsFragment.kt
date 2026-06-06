
package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.news.NewsFactorEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.database.StockDataCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 热点新闻列表 v3.0 — 下拉框：当日/近10日/近100日热门 + 今日Top5板块（按热度排序）
 * 选择后自动更新标题/副标题并刷新新闻
 */
class HotNewsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingTv: TextView
    private lateinit var emptyTv: TextView
    private lateinit var hotSectorTv: TextView
    private lateinit var hotSectorDropBtn: Button
    private lateinit var titleTv: TextView
    private lateinit var subtitleTv: TextView

    /** 当前选中项: 0=当日热门, 1=近10日, 2=近100日, 3..=具体板块 */
    private var selectedLabel = "当日热门"
    private var selectedSectorFilter: String? = null   // null=全部热门, "MLCC"=指定板块

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // ─── Header ───
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 12); setBackgroundColor(Color.WHITE) }
        titleTv = TextView(ctx).apply {
            text = "今日热门板块 · 关联A股"; textSize = 18f
            setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 4)
        }; header.addView(titleTv)
        subtitleTv = TextView(ctx).apply {
            text = "最新财经资讯 · 利好利空因子 · 策略参考数据"; textSize = 11f
            setTextColor(Color.parseColor("#999999")); setPadding(0, 0, 0, 0)
        }; header.addView(subtitleTv)

        val hotSectorRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 0) }
        hotSectorRow.addView(TextView(ctx).apply { text = "🔥"; textSize = 13f; setPadding(0, 0, 4, 0) })
        hotSectorTv = TextView(ctx).apply { text = "加载中..."; textSize = 12f; setTextColor(Color.parseColor("#E65100")); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) }
        hotSectorRow.addView(hotSectorTv)
        hotSectorDropBtn = Button(ctx).apply {
            text = "当日热门 ▼"; textSize = 12f; setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            setPadding(dp(6), 0, dp(6), 0); setMinWidth(0); setMinimumWidth(0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { showSectorDropdown() }
        }
        hotSectorRow.addView(hotSectorDropBtn)
        header.addView(hotSectorRow); root.addView(header)

        // ─── Refresh Row ───
        val refreshRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 6, 16, 6); setBackgroundColor(Color.WHITE) }
        val refreshBtn = Button(ctx).apply { text = "🔄 刷新新闻"; textSize = 13f; setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE); setPadding(16, 8, 16, 8); setMinWidth(0); setMinimumWidth(0); setOnClickListener { loadNews(forceRefresh = true) } }
        refreshRow.addView(refreshBtn)
        loadingTv = TextView(ctx).apply { text = ""; textSize = 12f; setTextColor(Color.parseColor("#888888")); layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 12 } }
        refreshRow.addView(loadingTv); root.addView(refreshRow)

        emptyTv = TextView(ctx).apply { text = "暂无热点新闻\n请先确保已导入历史数据，App 启动后会自动拉取"; textSize = 14f; setTextColor(Color.parseColor("#AAAAAA")); gravity = Gravity.CENTER; setPadding(32, 64, 32, 64); visibility = View.GONE }; root.addView(emptyTv)
        recyclerView = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f); adapter = NewsAdapter(emptyList(), ::onNewsClick, ::onRelatedStocks) }; root.addView(recyclerView)

        loadNews(); loadAndPopulateDropdown(); startHotSectorRefresh()
        return root
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    override fun onResume() { super.onResume(); loadAndPopulateDropdown() }

    // ─── 加载并填充下拉框显示的热门板块 ───
    private fun loadAndPopulateDropdown() {
        lifecycleScope.launch(Dispatchers.IO) {
            val top5 = getTop5HotSectors()
            withContext(Dispatchers.Main) {
                hotSectorTv.text = if (top5.isNotEmpty()) top5.joinToString("  ") else "暂无热门板块"
            }
        }
    }

    /** 获取今日Top5热门板块（按热度分数排序） */
    private suspend fun getTop5HotSectors(): List<String> {
        try {
            // 优先新闻因子
            val db = StockDatabase.getInstance(requireContext())
            val newsSectors = db.newsFactorDao().getAllActive(50)
                .map { it.sector }.filter { it.isNotBlank() }.distinct().take(5)
            if (newsSectors.size >= 3) return newsSectors
        } catch (_: Exception) {}
        // 实时板块热度排序
        val live = (EastMoneyHotSectorSource.conceptSectors + EastMoneyHotSectorSource.industrySectors)
            .sortedByDescending { it.compositeScore }.map { it.name }.distinct().take(5)
        if (live.isNotEmpty()) return live
        // Fallback
        return StockDataCenter.getHotSectorsByPeriod(1).take(5)
    }

    private fun startHotSectorRefresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) { delay(3 * 60_000L); val top5 = getTop5HotSectors(); withContext(Dispatchers.Main) { hotSectorTv.text = if (top5.isNotEmpty()) top5.joinToString("  ") else "暂无热门板块" } }
        }
    }

    // ─── 下拉弹窗：当日热门/近10日/近100日 + 今日Top5板块 ───
    private fun showSectorDropdown() {
        lifecycleScope.launch(Dispatchers.IO) {
            val top5 = getTop5HotSectors()
            withContext(Dispatchers.Main) {
                val ctx = requireContext()
                val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(4), dp(4), dp(4), dp(4)) }
                root.addView(TextView(ctx).apply { text = "🏷 选择新闻范围"; textSize = 11f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(2), dp(4), dp(6)) })

                // 时间范围选项
                val timeItems = listOf("当日热门", "近10日热门", "近100日热门")
                for (t in timeItems) {
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2)); setBackgroundColor(if (selectedLabel == t && selectedSectorFilter == null) Color.parseColor("#FFF3E0") else Color.TRANSPARENT) }
                    row.addView(TextView(ctx).apply { text = if (selectedLabel == t && selectedSectorFilter == null) "●" else "○"; textSize = 13f; setTextColor(Color.parseColor("#1565C0")); setPadding(0, 0, dp(8), 0) })
                    row.addView(TextView(ctx).apply { text = t; textSize = 13f; setTextColor(Color.parseColor("#222222")) })
                    root.addView(row)
                }

                // 分隔线 + 今日热门板块标题
                root.addView(View(ctx).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(6), 0, dp(4)) }; setBackgroundColor(Color.parseColor("#DDDDDD")) })
                root.addView(TextView(ctx).apply { text = "今日热门板块 (Top 5)"; textSize = 11f; setTextColor(Color.parseColor("#888888")); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(2), dp(4), dp(4)) })

                // Top5 板块选项
                if (top5.isEmpty()) {
                    root.addView(TextView(ctx).apply { text = "  暂无板块数据"; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(dp(4), dp(2), dp(4), dp(2)) })
                } else {
                    for ((idx, sectorName) in top5.withIndex()) {
                        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2)); setBackgroundColor(if (selectedSectorFilter == sectorName) Color.parseColor("#FFF3E0") else Color.TRANSPARENT) }
                        row.addView(TextView(ctx).apply { text = if (selectedSectorFilter == sectorName) "●" else "○"; textSize = 13f; setTextColor(Color.parseColor("#E65100")); setPadding(0, 0, dp(8), 0) })
                        row.addView(TextView(ctx).apply { text = "#${idx + 1} $sectorName"; textSize = 13f; setTextColor(Color.parseColor("#222222")) })
                        root.addView(row)
                    }
                }

                val popup = PopupWindow(root, dp(200), LayoutParams.WRAP_CONTENT, true).apply { setBackgroundDrawable(ColorDrawable(Color.WHITE)); elevation = 8f }

                // 点击逻辑：绑定每个 row
                var idx2 = 0
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i)
                    val tag = when {
                        i in 1..3 -> timeItems[i - 1] to (null as String?)
                        i >= 6 && i < 6 + top5.size -> top5[i - 6] to top5[i - 6]
                        else -> continue
                    }
                    val (lbl, sectorFilter) = tag
                    child.setOnClickListener {
                        selectedLabel = lbl; selectedSectorFilter = sectorFilter
                        hotSectorDropBtn.text = "$lbl ▼"
                        updateTitleBySelection()
                        popup.dismiss()
                        loadNews(forceRefresh = true)
                    }
                }
                popup.showAsDropDown(hotSectorDropBtn, 0, 4, Gravity.END)
            }
        }
    }

    /** 根据当前选项更新标题和副标题 */
    private fun updateTitleBySelection() {
        if (!isAdded) return
        when {
            selectedSectorFilter != null -> {
                titleTv.text = "${selectedSectorFilter} 板块动态 · 关联A股"
                subtitleTv.text = "聚焦「${selectedSectorFilter}」板块最新资讯与利好利空因子"
            }
            selectedLabel == "近10日热门" -> {
                titleTv.text = "近10日热门板块 · 关联A股"
                subtitleTv.text = "近10个交易日热度最高板块 · 利好利空因子 · 策略参考"
            }
            selectedLabel == "近100日热门" -> {
                titleTv.text = "近100日热门板块 · 关联A股"
                subtitleTv.text = "近100个交易日热度统计 · 中长期板块趋势 · 策略参考"
            }
            else -> {
                titleTv.text = "今日热门板块 · 关联A股"
                subtitleTv.text = "最新财经资讯 · 利好利空因子 · 策略参考数据"
            }
        }
    }

    private fun loadNews(forceRefresh: Boolean = false) {
        loadingTv.text = "⏳ ${if (forceRefresh) "正在搜索最新新闻..." else "加载中..."}"; recyclerView.visibility = View.GONE; emptyTv.visibility = View.GONE
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                com.chin.stockanalysis.news.HotSectorNewsUpdater(requireContext()).updateIfNeeded(
                    forceRefresh = true
                )
            }
            var news = withContext(Dispatchers.IO) { try { StockDatabase.getInstance(requireContext()).newsFactorDao().getAllActive(100) } catch (_: Exception) { emptyList() } }
            // 若选了具体板块，过滤板块相关新闻
            if (selectedSectorFilter != null) { news = news.filter { it.sector.contains(selectedSectorFilter!!, ignoreCase = true) || it.title.contains(selectedSectorFilter!!, ignoreCase = true) || it.content.contains(selectedSectorFilter!!, ignoreCase = true) } }
            withContext(Dispatchers.Main) {
                loadingTv.text = if (news.isNotEmpty()) "共 ${news.size} 条" else "暂无新闻"
                if (news.isEmpty()) { emptyTv.visibility = View.VISIBLE; recyclerView.visibility = View.GONE } else { emptyTv.visibility = View.GONE; recyclerView.visibility = View.VISIBLE; (recyclerView.adapter as? NewsAdapter)?.update(news) }
            }
        }
    }

    private fun onNewsClick(news: NewsFactorEntity) { HotNewsDetailFragment().apply { this.newsItem = news }.show(parentFragmentManager, "hot_news_detail") }
    private fun onRelatedStocks(news: NewsFactorEntity) { HotNewsDetailFragment().apply { this.newsItem = news; this.openStockTab = true }.show(parentFragmentManager, "hot_news_stocks") }

    inner class NewsAdapter(private var items: List<NewsFactorEntity>, private val onItemClick: (NewsFactorEntity) -> Unit, private val onRelatedStocks: (NewsFactorEntity) -> Unit) : RecyclerView.Adapter<NewsAdapter.VH>() {
        fun update(newItems: List<NewsFactorEntity>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH = VH(createCard(parent))
        override fun onBindViewHolder(h: VH, i: Int) = h.bind(items[i], onItemClick, onRelatedStocks)
        override fun getItemCount() = items.size
        private fun createCard(parent: ViewGroup) = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(20, 14, 20, 14); elevation = 2f; layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(12, 6, 12, 0) } }
        inner class VH(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
            fun bind(news: NewsFactorEntity, onClick: (NewsFactorEntity) -> Unit, onRelated: (NewsFactorEntity) -> Unit) {
                card.removeAllViews(); val ctx = card.context
                val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                titleRow.addView(TextView(ctx).apply { text = if (news.sentiment > 0) "📈" else if (news.sentiment < 0) "📉" else "➖"; textSize = 18f; layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 } })
                titleRow.addView(TextView(ctx).apply { text = news.title; textSize = 15f; setTextColor(Color.parseColor("#222222")); setTypeface(null, Typeface.BOLD); maxLines = 2; layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
                card.addView(titleRow)
                if (news.content.isNotBlank()) card.addView(TextView(ctx).apply { text = news.content; textSize = 12f; setTextColor(Color.parseColor("#666666")); maxLines = 2; setPadding(26, 4, 0, 4) })
                val metaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(26, 4, 0, 0) }
                metaRow.addView(TextView(ctx).apply { text = "🕐 ${news.newsDate}"; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA")) })
                if (news.sector.isNotBlank()) metaRow.addView(TextView(ctx).apply { text = "  🏷 ${news.sector}"; textSize = 11f; setTextColor(Color.parseColor("#1565C0")) })
                metaRow.addView(View(ctx).apply { layoutParams = LayoutParams(0, 1, 1f) })
                metaRow.addView(TextView(ctx).apply { text = "相关股票 →"; textSize = 12f; setTextColor(Color.parseColor("#E65100")); setTypeface(null, Typeface.BOLD); setPadding(8, 4, 8, 4); setBackgroundColor(Color.parseColor("#FFF3E0")); setOnClickListener { onRelated(news) } })
                card.addView(metaRow); card.setOnClickListener { onClick(news) }
            }
        }
    }
}
