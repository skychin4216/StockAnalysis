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
 * 热点新闻列表 v4.0 — Tab 切换：今日热门板块 / 新闻列表
 * 顶部 Tab 切换 + 刷新按钮，下方共用内容区
 */
class HotNewsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingTv: TextView
    private lateinit var emptyTv: TextView
    private lateinit var hotSectorTv: TextView
    private lateinit var hotSectorDropBtn: Button
    private lateinit var subtitleTv: TextView
    private lateinit var tabSectorTv: TextView
    private lateinit var tabNewsTv: TextView
    private lateinit var refreshBtn: Button
    private lateinit var sectorContainer: LinearLayout
    private var showingSectors = true

    /** 当前选中项 */
    private var selectedLabel = "当日热门"
    private var selectedSectorFilter: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        root.addView(TextView(ctx).apply { text = "init" }) // placeholder below

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = view.context
        val root = view as ScrollView

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        root.removeAllViews()
        root.addView(inner)

        // ─── Header：Tab 切換 + 刷新按鈕 ───
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 12, 12, 12); setBackgroundColor(Color.WHITE)
        }

        tabSectorTv = TextView(ctx).apply {
            text = "今日热门板块"; textSize = 14f
            setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            setOnClickListener { switchToSectors() }
        }; header.addView(tabSectorTv)

        tabNewsTv = TextView(ctx).apply {
            text = "新闻列表"; textSize = 14f
            setTextColor(Color.parseColor("#999999")); setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { switchToNews() }
        }; header.addView(tabNewsTv)

        header.addView(View(ctx).apply { layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })

        hotSectorDropBtn = Button(ctx).apply {
            text = "当日热门 ▼"; textSize = 11f; setTextColor(Color.parseColor("#1565C0"))
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            setPadding(dp(8), dp(2), dp(8), dp(2)); setMinWidth(0); setMinimumWidth(0)
            setOnClickListener { showSectorDropdown() }
        }; header.addView(hotSectorDropBtn)

        refreshBtn = Button(ctx).apply {
            text = "🔄 刷新"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(dp(10), dp(4), dp(10), dp(4)); setMinWidth(0); setMinimumWidth(0)
            setOnClickListener {
                if (showingSectors) loadAndPopulateDropdown() else loadNews(forceRefresh = true)
            }
        }; header.addView(refreshBtn)
        inner.addView(header)

        // ─── 內容共用區域 (FrameLayout: 熱門板塊 / 新聞列表) ───
        val contentFrame = FrameLayout(ctx).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.WHITE)
        }

        // ── 熱門板塊 ──
        sectorContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            visibility = View.VISIBLE
        }
        subtitleTv = TextView(ctx).apply {
            text = "最新财经资讯 · 利好利空因子 · 策略参考数据"; textSize = 11f
            setTextColor(Color.parseColor("#999999"))
        }
        sectorContainer.addView(subtitleTv)
        hotSectorTv = TextView(ctx).apply {
            text = "加载中..."; textSize = 12f; setTextColor(Color.parseColor("#333333"))
            setLineSpacing(0f, 1.3f); setPadding(0, dp(8), 0, 0)
        }
        sectorContainer.addView(hotSectorTv)
        contentFrame.addView(sectorContainer)

        // ── 新聞列表加載中提示 ──
        loadingTv = TextView(ctx).apply {
            text = "加载中..."; textSize = 12f; setTextColor(Color.parseColor("#888888"))
            setPadding(16, 8, 16, 4); visibility = View.GONE
        }
        contentFrame.addView(loadingTv)

        // ── 新聞空狀態 ──
        emptyTv = TextView(ctx).apply {
            text = "暂无热点新闻\n请先确保已导入历史数据，App 启动后会自动拉取"
            textSize = 14f; setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER; setPadding(32, 64, 32, 64); visibility = View.GONE
        }
        contentFrame.addView(emptyTv)

        // ── 新聞 RecyclerView ──
        recyclerView = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = NewsAdapter(emptyList(), ::onNewsClick, ::onRelatedStocks)
            visibility = View.GONE
        }
        contentFrame.addView(recyclerView)

        inner.addView(contentFrame)

        updateTabUI()
        loadNews(); loadAndPopulateDropdown(); startHotSectorRefresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    override fun onResume() { super.onResume(); loadAndPopulateDropdown() }

    // ─── Tab 切換 ───

    private fun switchToSectors() {
        showingSectors = true
        updateTabUI()
        loadAndPopulateDropdown()
    }

    private fun switchToNews() {
        showingSectors = false
        updateTabUI()
        loadNews()
    }

    private fun updateTabUI() {
        if (!isAdded) return
        if (showingSectors) {
            tabSectorTv.setTextColor(Color.parseColor("#1565C0"))
            tabSectorTv.setBackgroundColor(Color.parseColor("#E3F2FD"))
            tabNewsTv.setTextColor(Color.parseColor("#999999"))
            tabNewsTv.setBackgroundColor(Color.TRANSPARENT)
            sectorContainer.visibility = View.VISIBLE
            loadingTv.visibility = View.GONE
            emptyTv.visibility = View.GONE
            recyclerView.visibility = View.GONE
        } else {
            tabNewsTv.setTextColor(Color.parseColor("#1565C0"))
            tabNewsTv.setBackgroundColor(Color.parseColor("#E3F2FD"))
            tabSectorTv.setTextColor(Color.parseColor("#999999"))
            tabSectorTv.setBackgroundColor(Color.TRANSPARENT)
            sectorContainer.visibility = View.GONE
        }
    }

    // ─── 加载并填充热门板块 ───
    private fun loadAndPopulateDropdown() {
        lifecycleScope.launch(Dispatchers.IO) {
            val display = getHotSectorsDisplay()
            withContext(Dispatchers.Main) {
                hotSectorTv.text = if (display.isNotEmpty()) display else "暂无热门板块"
            }
        }
    }

    /** 获取今日Top5热门板块（按热度分数排序，过滤子板块） */
    private suspend fun getTop5HotSectors(): List<String> {
        try {
            val db = StockDatabase.getInstance(requireContext())
            val newsSectors = db.newsFactorDao().getAllActive(50)
                .map { it.sector }.filter { it.isNotBlank() }
                .filter { !com.chin.stockanalysis.stock.data.sources.SectorSubDivision.isSubSectorName(it) }
                .distinct().take(5)
            if (newsSectors.size >= 3) return newsSectors
        } catch (_: Exception) {}
        val allNames = (EastMoneyHotSectorSource.conceptSectors + EastMoneyHotSectorSource.industrySectors)
            .sortedByDescending { it.compositeScore }.map { it.name }.distinct()
        val live = allNames.filter { name ->
            !com.chin.stockanalysis.stock.data.sources.SectorSubDivision.isSubSectorName(name)
        }.take(5)
        if (live.isNotEmpty()) return live
        return StockDataCenter.getHotSectorsByPeriod(1).take(5)
    }

    /** 获取今日Top5热门板块，直接从东财API获取成分股涨跌幅 */
    private suspend fun getHotSectorsDisplay(): String {
        // 第一步：获取涨幅最高的5个概念板块
        var hotSectors = EastMoneyHotSectorSource.conceptSectors
            .sortedByDescending { it.changePercent }.take(10)
        if (hotSectors.isEmpty()) {
            try {
                val source = EastMoneyHotSectorSource()
                hotSectors = source.fetchSectorsByTypeDirect(type = 3, topN = 10)
                    .sortedByDescending { it.changePercent }.take(10)
            } catch (_: Exception) {}
        }

        if (hotSectors.isEmpty()) return "暂无热门板块数据"

        // 第二步：对每个板块调用 fetchSectorLeaders 获取涨幅前5成分股
        val source = EastMoneyHotSectorSource()
        val lines = mutableListOf<String>()
        val allLeaders = mutableListOf<Pair<String, EastMoneyHotSectorSource.LeaderStock>>()

        for (s in hotSectors) {
            val emoji = if (s.changePercent > 0) "📈" else "📉"
            val sign = if (s.changePercent > 0) "+" else ""

            val leaders = try {
                source.fetchSectorLeaders(s.code, 5)
            } catch (_: Exception) { emptyList() }

            val stocksDisplay = leaders.joinToString(" ") { stock ->
                val cpSign = if (stock.changePercent > 0) "+" else ""
                "${stock.name} $cpSign${"%.2f".format(stock.changePercent)}%"
            }

            lines.add("$emoji ${s.name} $sign${"%.2f".format(s.changePercent)}%")
            if (stocksDisplay.isNotBlank()) {
                lines.add("├─ $stocksDisplay")
            }

            for (stock in leaders) {
                allLeaders.add(s.name to stock)
            }
        }

        // 第三步：涨幅Top5个股汇总
        if (allLeaders.isNotEmpty()) {
            val top5 = allLeaders.sortedByDescending { it.second.changePercent }.take(5)
            lines.add("")
            lines.add("🔥 涨幅Top5个股:")
            for ((sectorName, stock) in top5) {
                val sign = if (stock.changePercent > 0) "+" else ""
                lines.add("📈 ${stock.name}(${stock.code}) ${sign}${"%.2f".format(stock.changePercent)}% [$sectorName]")
            }
        }

        return if (lines.isNotEmpty()) lines.joinToString("\n") else "暂无热门板块"
    }

    private fun startHotSectorRefresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3 * 60_000L)
                val display = getHotSectorsDisplay()
                withContext(Dispatchers.Main) {
                    hotSectorTv.text = if (display.isNotEmpty()) display else "暂无热门板块"
                }
            }
        }
    }

    // ─── 下拉弹窗 ───
    private fun showSectorDropdown() {
        lifecycleScope.launch(Dispatchers.IO) {
            val top5 = getTop5HotSectors()
            withContext(Dispatchers.Main) {
                val ctx = requireContext()
                val popupRoot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(4), dp(4), dp(4), dp(4)) }
                popupRoot.addView(TextView(ctx).apply { text = "🏷 选择新闻范围"; textSize = 11f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(2), dp(4), dp(6)) })

                val timeItems = listOf("当日热门", "近10日热门", "近100日热门")
                for (t in timeItems) {
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2)); setBackgroundColor(if (selectedLabel == t && selectedSectorFilter == null) Color.parseColor("#FFF3E0") else Color.TRANSPARENT) }
                    row.addView(TextView(ctx).apply { text = if (selectedLabel == t && selectedSectorFilter == null) "●" else "○"; textSize = 13f; setTextColor(Color.parseColor("#1565C0")); setPadding(0, 0, dp(8), 0) })
                    row.addView(TextView(ctx).apply { text = t; textSize = 13f; setTextColor(Color.parseColor("#222222")) })
                    popupRoot.addView(row)
                }

                popupRoot.addView(View(ctx).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(6), 0, dp(4)) }; setBackgroundColor(Color.parseColor("#DDDDDD")) })
                popupRoot.addView(TextView(ctx).apply { text = "今日热门板块 (Top 5)"; textSize = 11f; setTextColor(Color.parseColor("#888888")); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(2), dp(4), dp(4)) })

                val popup = PopupWindow(popupRoot, dp(240), LayoutParams.WRAP_CONTENT, true).apply { setBackgroundDrawable(ColorDrawable(Color.WHITE)); elevation = 8f }

                if (top5.isEmpty()) {
                    popupRoot.addView(TextView(ctx).apply { text = "  暂无板块数据"; textSize = 11f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(dp(4), dp(2), dp(4), dp(2)) })
                } else {
                    for ((idx, sectorName) in top5.withIndex()) {
                        val parentRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(if (selectedSectorFilter == sectorName) Color.parseColor("#FFF3E0") else Color.TRANSPARENT) }
                        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2)) }
                        row.addView(TextView(ctx).apply { text = if (selectedSectorFilter == sectorName) "●" else "○"; textSize = 13f; setTextColor(Color.parseColor("#E65100")); setPadding(0, 0, dp(8), 0) })

                        val subSectors = try { com.chin.stockanalysis.stock.data.sources.SectorSubDivision.getSubSectors(sectorName) } catch (_: Exception) { emptyList() }
                        val hasSubs = subSectors.isNotEmpty()
                        row.addView(TextView(ctx).apply {
                            text = "#${idx + 1} $sectorName ${if (hasSubs) "▶" else ""}"
                            textSize = 13f; setTextColor(Color.parseColor("#222222"))
                        })
                        parentRow.addView(row)

                        val subContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), 0, 0, 0); visibility = View.GONE }
                        if (hasSubs) {
                            for (sub in subSectors.take(5)) {
                                val subRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(2), dp(4), dp(2)); setBackgroundColor(if (selectedSectorFilter == "${sectorName}·${sub.name}") Color.parseColor("#FFF3E0") else Color.TRANSPARENT) }
                                val subFilter = "${sectorName}·${sub.name}"
                                subRow.addView(TextView(ctx).apply { text = if (selectedSectorFilter == subFilter) "●" else "○"; textSize = 11f; setTextColor(Color.parseColor("#888888")); setPadding(0, 0, dp(6), 0) })
                                subRow.addView(TextView(ctx).apply { text = "  ${sub.name}"; textSize = 12f; setTextColor(Color.parseColor("#555555")) })
                                subRow.setOnClickListener {
                                    selectedLabel = "${sectorName}·${sub.name}"; selectedSectorFilter = subFilter
                                    hotSectorDropBtn.text = "${sub.name} ▼"
                                    popup.dismiss()
                                    switchToNews()
                                    loadNews(forceRefresh = true)
                                }
                                subContainer.addView(subRow)
                            }
                        }
                        parentRow.addView(subContainer)
                        popupRoot.addView(parentRow)

                        row.setOnClickListener {
                            if (hasSubs) {
                                subContainer.visibility = if (subContainer.visibility == View.GONE) View.VISIBLE else View.GONE
                            } else {
                                selectedLabel = sectorName; selectedSectorFilter = sectorName
                                hotSectorDropBtn.text = "$sectorName ▼"
                                popup.dismiss()
                                switchToNews()
                                loadNews(forceRefresh = true)
                            }
                        }
                    }
                }

                for (i in 1..3) {
                    val child = popupRoot.getChildAt(i - 1)
                    val t = timeItems[i - 1]
                    child.setOnClickListener {
                        selectedLabel = t; selectedSectorFilter = null
                        hotSectorDropBtn.text = "$t ▼"
                        popup.dismiss()
                        switchToNews()
                        loadNews(forceRefresh = true)
                    }
                }
                popup.showAsDropDown(hotSectorDropBtn, 0, 4, Gravity.END)
            }
        }
    }

    // ─── 加载新闻 ───
    private fun loadNews(forceRefresh: Boolean = false) {
        loadingTv.text = "⏳ ${if (forceRefresh) "正在搜索最新新闻..." else "加载中..."}"
        loadingTv.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE; emptyTv.visibility = View.GONE
        lifecycleScope.launch {
            val hasLocalData = withContext(Dispatchers.IO) {
                try { StockDatabase.getInstance(requireContext()).newsFactorDao().getAllActive(1).isNotEmpty() } catch (_: Exception) { false }
            }
            if (forceRefresh || !hasLocalData) {
                withContext(Dispatchers.IO) {
                    com.chin.stockanalysis.news.HotSectorNewsUpdater(requireContext()).updateIfNeeded(forceRefresh = forceRefresh)
                }
            }
            var news = withContext(Dispatchers.IO) { try { StockDatabase.getInstance(requireContext()).newsFactorDao().getAllActive(200) } catch (_: Exception) { emptyList() } }
            if (selectedSectorFilter != null) {
                news = news.filter { it.sector.contains(selectedSectorFilter!!, ignoreCase = true) || it.title.contains(selectedSectorFilter!!, ignoreCase = true) || it.content.contains(selectedSectorFilter!!, ignoreCase = true) }
            } else {
                news = news.groupBy { it.sector.takeIf { s -> s.isNotBlank() } ?: "其他" }
                    .flatMap { (_, items) -> items.sortedByDescending { it.newsDate }.take(8) }
                    .sortedByDescending { it.newsDate }
            }
            withContext(Dispatchers.Main) {
                loadingTv.visibility = View.GONE
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