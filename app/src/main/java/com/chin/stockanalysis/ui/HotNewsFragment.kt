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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 热点新闻列表 (Fire)
 */
class HotNewsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingTv: TextView
    private lateinit var emptyTv: TextView
    private lateinit var hotSectorTv: TextView
    private lateinit var hotSectorDropBtn: Button
    private var selectedPreset = 0
    private val selectedSectors = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 12); setBackgroundColor(Color.WHITE) }
        header.addView(TextView(ctx).apply { text = "🔥 热点新闻"; textSize = 20f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD) })
        header.addView(TextView(ctx).apply { text = "AI 硬件优先 · 热门板块动态 · 关联 A 股"; textSize = 12f; setTextColor(Color.parseColor("#999999")); setPadding(0, 2, 0, 0) })

        val hotSectorRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 0) }
        hotSectorRow.addView(TextView(ctx).apply { text = "🏷"; textSize = 13f; setPadding(0, 0, 4, 0) })
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

        val refreshRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 6, 16, 6); setBackgroundColor(Color.WHITE) }
        val refreshBtn = Button(ctx).apply { text = "🔄 刷新新闻"; textSize = 13f; setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE); setPadding(16, 8, 16, 8); setMinWidth(0); setMinimumWidth(0); setOnClickListener { loadNews(forceRefresh = true) } }
        refreshRow.addView(refreshBtn)
        loadingTv = TextView(ctx).apply { text = ""; textSize = 12f; setTextColor(Color.parseColor("#888888")); layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 12 } }
        refreshRow.addView(loadingTv); root.addView(refreshRow)

        emptyTv = TextView(ctx).apply { text = "暂无热点新闻\n请先确保已导入历史数据，App 启动后会自动拉取"; textSize = 14f; setTextColor(Color.parseColor("#AAAAAA")); gravity = Gravity.CENTER; setPadding(32, 64, 32, 64); visibility = View.GONE }; root.addView(emptyTv)
        recyclerView = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f); adapter = NewsAdapter(emptyList(), ::onNewsClick, ::onRelatedStocks) }; root.addView(recyclerView)

        loadNews(); refreshHotSectors(); startHotSectorRefresh()
        return root
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    override fun onResume() { super.onResume(); refreshHotSectors() }

    private fun refreshHotSectors() {
        if (selectedSectors.isNotEmpty()) { hotSectorTv.text = selectedSectors.take(5).joinToString("  "); return }
        val sectors = EastMoneyHotSectorSource.conceptSectors
        if (sectors.isNotEmpty()) { hotSectorTv.text = sectors.take(5).joinToString("  ") { it.name }; return }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(requireContext())
                val news = db.newsFactorDao().getAllActive(50).map { it.sector }.filter { it.isNotBlank() }.distinct().take(5)
                if (news.isNotEmpty()) { withContext(Dispatchers.Main) { hotSectorTv.text = news.joinToString("  ") }; return@launch }
                val latest = db.sectorDailyRecordDao().getByDate(StrategyListFragment.recentTradingDay().toString())
                val names = if (latest.isNotEmpty()) latest.take(5).joinToString("  ") { it.sectorName } else "暂无热门板块"
                withContext(Dispatchers.Main) { hotSectorTv.text = names }
            } catch (_: Exception) { withContext(Dispatchers.Main) { hotSectorTv.text = "暂无热门板块" } }
        }
    }

    private fun startHotSectorRefresh() { lifecycleScope.launch(Dispatchers.IO) { while (isActive) { delay(3 * 60_000L); withContext(Dispatchers.Main) { refreshHotSectors() } } } }

    private fun loadNews(forceRefresh: Boolean = false) {
        loadingTv.text = "⏳ ${if (forceRefresh) "正在搜索最新新闻..." else "加载中..."}"; recyclerView.visibility = View.GONE; emptyTv.visibility = View.GONE
        lifecycleScope.launch {
            if (forceRefresh) withContext(Dispatchers.IO) { com.chin.stockanalysis.news.HotSectorNewsUpdater(requireContext()).updateIfNeeded(forceRefresh = true) }
            val news = withContext(Dispatchers.IO) { try { StockDatabase.getInstance(requireContext()).newsFactorDao().getAllActive(100) } catch (e: Exception) { emptyList() } }
            withContext(Dispatchers.Main) {
                loadingTv.text = if (news.isNotEmpty()) "共 ${news.size} 条" else "暂无新闻"; refreshHotSectors()
                if (news.isEmpty()) { emptyTv.visibility = View.VISIBLE; recyclerView.visibility = View.GONE } else { emptyTv.visibility = View.GONE; recyclerView.visibility = View.VISIBLE; (recyclerView.adapter as? NewsAdapter)?.update(news) }
            }
        }
    }

    // ── Combined dropdown ──
    private fun showSectorDropdown() {
        lifecycleScope.launch(Dispatchers.IO) {
            val presets = listOf("当日热门", "最近交易日", "近10日", "近100日")
            val allNames = mutableSetOf<String>()
            val live = EastMoneyHotSectorSource.conceptSectors
            if (live.isNotEmpty()) allNames.addAll(live.map { it.name })
            try { val db = StockDatabase.getInstance(requireContext()); allNames.addAll(db.newsFactorDao().getAllActive(100).map { it.sector }.filter { it.isNotBlank() }); allNames.addAll(db.sectorDailyRecordDao().getRecentDays(100).map { it.sectorName }.filter { it.isNotBlank() }) } catch (_: Exception) {}
            val sorted = allNames.filter { it.isNotEmpty() }.sorted()
            withContext(Dispatchers.Main) {
                val popup = buildCombinedPopup(presets, sorted)
                hotSectorDropBtn.tag = popup; popup.showAsDropDown(hotSectorDropBtn, 0, 4, Gravity.END)
            }
        }
    }

    private fun buildCombinedPopup(presets: List<String>, sorted: List<String>): PopupWindow {
        val ctx = requireContext(); val w = dp(170)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(TextView(ctx).apply { text = "🏷 选择板块"; textSize = 11f; setTextColor(Color.parseColor("#1565C0")); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(2), dp(4), dp(4)) })
        for ((i, p) in presets.withIndex()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(1), dp(4), dp(1)) }
            row.addView(TextView(ctx).apply { text = if (selectedPreset == i) "●" else "○"; textSize = 12f; setTextColor(Color.parseColor("#1565C0")); setPadding(0, 0, dp(6), 0) })
            row.addView(TextView(ctx).apply { text = p; textSize = 12f; setTextColor(Color.parseColor("#333333")); layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
            row.setOnClickListener { selectedPreset = i; selectedSectors.clear(); hotSectorDropBtn.text = "$p ▼"; refreshHotSectors(); try { (hotSectorDropBtn.tag as? PopupWindow)?.dismiss() } catch (_: Exception) {} }
            root.addView(row)
        }
        root.addView(View(ctx).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(4), 0, dp(4)) }; setBackgroundColor(Color.parseColor("#DDDDDD")) })
        val scroll = ScrollView(ctx).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(180)) }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (name in sorted) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(1), dp(2), dp(1)) }
            val cb = CheckBox(ctx).apply { isChecked = name in selectedSectors; setPadding(0, 0, dp(4), 0); setOnCheckedChangeListener { _, chk -> if (chk) selectedSectors.add(name) else selectedSectors.remove(name) } }
            row.addView(cb); row.addView(TextView(ctx).apply { text = name; textSize = 11f; setTextColor(Color.parseColor("#333333")); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
            list.addView(row)
        }
        scroll.addView(list); root.addView(scroll)
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        listOf("清除" to { selectedSectors.clear(); selectedPreset = 0; hotSectorDropBtn.text = "当日热门 ▼"; refreshHotSectors() },
               "确定" to { hotSectorDropBtn.text = if (selectedSectors.isEmpty()) "${presets[selectedPreset]} ▼" else "✓${selectedSectors.size} ▼"; refreshHotSectors() })
            .forEach { (lbl, act) -> btnRow.addView(TextView(ctx).apply { text = lbl; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1565C0")); setPadding(0, dp(6), 0, dp(6)); layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { act(); try { (hotSectorDropBtn.tag as? PopupWindow)?.dismiss() } catch (_: Exception) {} } }) }
        root.addView(btnRow)
        return PopupWindow(root, w, LayoutParams.WRAP_CONTENT, true).apply { setBackgroundDrawable(ColorDrawable(Color.WHITE)); elevation = 8f }
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