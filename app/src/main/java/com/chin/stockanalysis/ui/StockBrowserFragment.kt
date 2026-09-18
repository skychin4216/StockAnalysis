package com.chin.stockanalysis.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ## 股票浏览面板 - 参考同花顺设计
 *
 * 以 BottomSheetDialogFragment 形式弹出，
 * 显示 A股 / ETF / 热门 / 涨幅 / 跌幅 / 主线 六个分页。
 *
 * ### 使用方式（在 ChatActivity 中）
 * ```kotlin
 * val dialog = StockBrowserFragment.newInstance()
 * dialog.onStockSelected = { stock ->
 *     binding.etInput.setText("帮我分析 ${stock.name}（${stock.code.takeLast(6)}）")
 *     dialog.dismiss()
 * }
 * dialog.show(supportFragmentManager, "stock_browser")
 * ```
 *
 * ### 搜索（v2.0）
 * 通过 `StockDataCenter.searchStocks()` 在真实股票库中检索（代码/名称/拼音），
 * 并批量拉取实时行情展示。
 */
class StockBrowserFragment : BottomSheetDialogFragment() {

    /** 股票被选中时的回调（由 ChatActivity 设置）*/
    var onStockSelected: ((StockListFragment.StockItem) -> Unit)? = null

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var searchInput: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var searchContainer: ViewGroup
    private lateinit var searchResultsView: RecyclerView
    private lateinit var searchProgress: ProgressBar
    private lateinit var searchEmpty: TextView

    private val pages = mutableListOf<StockListFragment>()
    private val pageTitles = mutableListOf<String>()

    private val repository by lazy {
        StockDataSourceFactory.createDefaultRepository(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = LinearLayout(requireContext()).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(context.getColor(android.R.color.white))

        // ── 顶部标题 + 关闭按钮 ──
        addView(createHeader())

        // ── 搜索栏 ──
        addView(createSearchBar())

        // ── 搜索结果区（默认隐藏）──
        searchContainer = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        searchProgress = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        searchContainer.addView(searchProgress)
        searchResultsView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context)
        }
        searchContainer.addView(searchResultsView)
        searchEmpty = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            text = "未找到相关股票"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
        }
        searchContainer.addView(searchEmpty)
        addView(searchContainer)

        // ── TabLayout ──
        tabLayout = TabLayout(context).apply {
            setSelectedTabIndicatorColor(0xFFE53935.toInt())  // 红色指示器
            setTabTextColors(0xFF888888.toInt(), 0xFF111111.toInt())
            tabMode = TabLayout.MODE_SCROLLABLE
        }
        addView(tabLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── ViewPager2 ──
        viewPager = ViewPager2(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }
        addView(viewPager)

        setupViewPager()
    }

    override fun onStart() {
        super.onStart()
        // 展开到接近全屏（自适应横屏/分屏，避免硬编码高度溢出）
        try {
            dialog?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.skipCollapsed = true
                behavior.maxHeight = (resources.displayMetrics.heightPixels * 0.82).toInt()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        } catch (e: Exception) {
            Log.w(TAG, "BottomSheet 高度设置失败: ${e.message}")
        }
    }

    // ── 顶部标题行 ──

    private fun createHeader(): View {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 0, 8.dpToPx(), 0)
            setBackgroundColor(context.getColor(android.R.color.white))

            // 标题
            addView(android.widget.TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "📈 选择股票"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF111111.toInt())
            })

            // 关闭按钮
            addView(ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { dismiss() }
            })
        }
    }

    // ── 搜索栏 ──

    private fun createSearchBar(): View {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            setBackgroundColor(0xFFF5F5F5.toInt())

            // 搜索框
            searchInput = EditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 40.dpToPx(), 1f)
                hint = "搜索股票代码或名称"
                setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                textSize = 14f
                setBackgroundResource(android.R.drawable.edit_text)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        filterStocks(s?.toString() ?: "")
                    }
                })
            }
            addView(searchInput)

            // 搜索按钮
            btnSearch = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()).apply {
                    marginStart = 8.dpToPx()
                }
                setImageResource(android.R.drawable.ic_menu_search)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    filterStocks(searchInput.text.toString())
                }
            }
            addView(btnSearch)
        }
    }

    // ── ViewPager2 设置 ──

    private fun setupViewPager() {
        val tabDefs = listOf(
            StockListFragment.Type.A_SHARE to "A股",
            StockListFragment.Type.ETF      to "ETF",
            StockListFragment.Type.HOT      to "热门",
            StockListFragment.Type.GAIN     to "涨幅",
            StockListFragment.Type.LOSE     to "跌幅",
            StockListFragment.Type.MAINLINE to "主线"
        )

        tabDefs.forEach { (type, title) ->
            val fragment = StockListFragment.newInstance(type).also { f ->
                // 将选中回调透传
                f.onStockSelected = { stock ->
                    onStockSelected?.invoke(stock)
                    dismiss()
                }
            }
            pages.add(fragment)
            pageTitles.add(title)
        }

        viewPager.adapter = StockPagerAdapter(this, pages, pageTitles)

        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = pageTitles[pos]
        }.attach()
    }

    // ── 搜索过滤（真实数据源）──

    private fun filterStocks(query: String) {
        if (query.isBlank()) {
            // 恢复 Tab 显示
            tabLayout.visibility = View.VISIBLE
            viewPager.visibility = View.VISIBLE
            searchContainer.visibility = View.GONE
            return
        }

        // 隐藏 Tab，显示搜索结果 + loading
        tabLayout.visibility = View.GONE
        viewPager.visibility = View.GONE
        showSearchLoading()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { realSearch(query) }
                if (view == null) return@launch
                if (items.isEmpty()) showSearchEmpty() else showSearchResults(items)
            } catch (e: Exception) {
                Log.w(TAG, "搜索失败: ${e.message}", e)
                if (view == null) return@launch
                showSearchEmpty()
            }
        }
    }

    /** 真实搜索：StockDataCenter 检索 + 批量实时行情 */
    private suspend fun realSearch(query: String): List<StockListFragment.StockItem> {
        val pairs = StockDataCenter.searchStocks(query)
        if (pairs.isEmpty()) return emptyList()

        val codes = pairs.map { it.first }
        val nameMap = pairs.toMap()
        val rtMap = try { repository.getRealtimeSuspend(codes) } catch (e: Exception) {
            Log.w(TAG, "搜索行情拉取失败: ${e.message}")
            emptyMap()
        }
        return codes.mapNotNull { code ->
            val rt = rtMap[code] ?: return@mapNotNull null
            StockListFragment.StockItem(
                code = code,
                name = rt.name.ifBlank { nameMap[code] ?: code },
                price = formatPrice(rt.price),
                change = formatPercent(rt.changePercent),
                arrow = when {
                    rt.changePercent > 0 -> "🟢"
                    rt.changePercent < 0 -> "🔴"
                    else -> "⚪"
                }
            )
        }
    }

    private fun hideSearchArea() {
        tabLayout.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE
        searchContainer.visibility = View.GONE
        searchProgress.visibility = View.GONE
        searchResultsView.visibility = View.GONE
        searchEmpty.visibility = View.GONE
    }

    private fun showSearchLoading() {
        searchContainer.visibility = View.VISIBLE
        searchProgress.visibility = View.VISIBLE
        searchResultsView.visibility = View.GONE
        searchEmpty.visibility = View.GONE
    }

    private fun showSearchResults(items: List<StockListFragment.StockItem>) {
        searchContainer.visibility = View.VISIBLE
        searchProgress.visibility = View.GONE
        searchResultsView.visibility = View.VISIBLE
        searchEmpty.visibility = View.GONE
        searchResultsView.adapter = StockListAdapter(items) { stock ->
            onStockSelected?.invoke(stock)
            dismiss()
        }
    }

    private fun showSearchEmpty() {
        searchContainer.visibility = View.VISIBLE
        searchProgress.visibility = View.GONE
        searchResultsView.visibility = View.GONE
        searchEmpty.visibility = View.VISIBLE
    }

    // ── 工具 ──

    private fun formatPrice(p: Double): String =
        if (p <= 0) "--" else String.format(Locale.US, "%.2f", p)

    private fun formatPercent(p: Double): String =
        if (p == 0.0) "0.00%" else String.format(Locale.US, "%+.2f%%", p)

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "StockBrowserFragment"
        fun newInstance() = StockBrowserFragment()
    }
}
