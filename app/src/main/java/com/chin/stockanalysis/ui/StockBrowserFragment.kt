package com.chin.stockanalysis.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * ## 股票浏览面板 - 参考同花顺设计
 *
 * 以 BottomSheetDialogFragment 形式弹出，
 * 显示 A股 / ETF / 热门 / 涨幅 / 跌幅 五个分页。
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
 */
class StockBrowserFragment : BottomSheetDialogFragment() {

    /** 股票被选中时的回调（由 ChatActivity 设置）*/
    var onStockSelected: ((StockListFragment.StockItem) -> Unit)? = null

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var searchInput: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var searchResultsView: RecyclerView

    private val pages = mutableListOf<StockListFragment>()
    private val pageTitles = mutableListOf<String>()

    // 全部股票合并（用于搜索）
    private val allStocks = mutableListOf<StockListFragment.StockItem>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = LinearLayout(requireContext()).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.82).toInt()
        )
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(context.getColor(android.R.color.white))

        // ── 顶部标题 + 关闭按钮 ──
        addView(createHeader())

        // ── 搜索栏 ──
        addView(createSearchBar())

        // ── 搜索结果 RecyclerView（默认隐藏）──
        searchResultsView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context)
            visibility = View.GONE
        }
        addView(searchResultsView)

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
            StockListFragment.Type.LOSE     to "跌幅"
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

    // ── 搜索过滤 ──

    private fun filterStocks(query: String) {
        if (query.isBlank()) {
            // 恢复 Tab 显示
            tabLayout.visibility = View.VISIBLE
            viewPager.visibility = View.VISIBLE
            searchResultsView.visibility = View.GONE
            return
        }

        // 隐藏 Tab，显示搜索结果
        tabLayout.visibility = View.GONE
        viewPager.visibility = View.GONE
        searchResultsView.visibility = View.VISIBLE

        // 收集所有示例数据（实际项目应从数据源查询）
        val allItems = buildAllStockList()
        val filtered = allItems.filter { stock ->
            stock.name.contains(query, ignoreCase = true) ||
                    stock.code.contains(query, ignoreCase = true) ||
                    stock.code.takeLast(6).contains(query, ignoreCase = true)
        }

        searchResultsView.adapter = StockListAdapter(filtered) { stock ->
            onStockSelected?.invoke(stock)
            dismiss()
        }
    }

    /** 聚合所有分页的示例数据供搜索使用 */
    private fun buildAllStockList(): List<StockListFragment.StockItem> = listOf(
        // A股主板
        StockListFragment.StockItem("sh600519", "贵州茅台", "1734.50", "+2.15%", "🟢"),
        StockListFragment.StockItem("sh600000", "浦发银行", "7.42", "+1.23%", "🟢"),
        StockListFragment.StockItem("sh601988", "中国银行", "4.15", "-0.58%", "🔴"),
        StockListFragment.StockItem("sz000858", "五粮液", "134.80", "+3.45%", "🟢"),
        StockListFragment.StockItem("sz000651", "格力电器", "40.25", "-2.34%", "🔴"),
        StockListFragment.StockItem("sh601318", "中国平安", "43.60", "+0.92%", "🟢"),
        StockListFragment.StockItem("sh600036", "招商银行", "35.80", "+1.10%", "🟢"),
        StockListFragment.StockItem("sz000333", "美的集团", "58.30", "+1.88%", "🟢"),
        StockListFragment.StockItem("sz002594", "比亚迪", "285.40", "+4.20%", "🟢"),
        StockListFragment.StockItem("sh600900", "长江电力", "26.80", "+0.75%", "🟢"),
        // ETF
        StockListFragment.StockItem("sz159915", "创业板ETF", "1.823", "+1.23%", "🟢"),
        StockListFragment.StockItem("sh510050", "50ETF", "3.128", "+0.34%", "🟢"),
        StockListFragment.StockItem("sh588000", "科创50", "0.924", "-1.23%", "🔴"),
        StockListFragment.StockItem("sh510300", "沪深300ETF", "4.012", "+0.88%", "🟢"),
        StockListFragment.StockItem("sz159919", "沪深300ETF", "4.015", "+0.90%", "🟢"),
        StockListFragment.StockItem("sh512010", "医疗ETF", "0.891", "-0.55%", "🔴"),
        StockListFragment.StockItem("sh512880", "证券ETF", "0.754", "+2.10%", "🟢"),
        StockListFragment.StockItem("sz159941", "纳指ETF", "1.245", "+0.40%", "🟢"),
    )

    // ── 工具 ──

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance() = StockBrowserFragment()
    }
}
