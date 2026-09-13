package com.chin.stockanalysis.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * ## 股票栏目 — v12.0
 *
 * 顶部四 Tab 切换：
 * - Tab 0：精选股票（WatchlistUnifiedFragment — 自选 / AI精选 / 备选池 按钮切换）
 * - Tab 1：K线趋势（TrendChartTabFragment — 扫描股票池 / 趋势图谱 / 截图OCR / 输入跳转）
 * - Tab 2：热门行情（MarketHotFragment — 全球指数 + A 股热门板块）
 * - Tab 3：热点新闻（HotNewsFragment — 板块新闻分析）
 */
class StockTabFragment : Fragment() {

    private lateinit var viewPager: ViewPager2

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
        }

        // TabLayout
        val tabLayout = TabLayout(ctx).apply {
            setSelectedTabIndicatorColor(Color.parseColor("#E65100"))
            setTabTextColors(Color.parseColor("#999999"), Color.parseColor("#E65100"))
            setBackgroundColor(Color.WHITE)
            elevation = 2f
            tabMode = TabLayout.MODE_FIXED
            isTabIndicatorFullWidth = false
        }
        root.addView(tabLayout)

        // ViewPager2
        viewPager = ViewPager2(ctx).apply {
            adapter = StockTabAdapter(this@StockTabFragment)
            offscreenPageLimit = 1
        }
        root.addView(viewPager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(com.chin.stockanalysis.R.string.stock_tab_picks)
                1 -> getString(com.chin.stockanalysis.R.string.stock_tab_trend)
                2 -> getString(com.chin.stockanalysis.R.string.stock_tab_hot)
                3 -> getString(com.chin.stockanalysis.R.string.stock_tab_news)
                else -> ""
            }
        }.attach()

        return root
    }

    private class StockTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

        override fun getItemCount() = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> WatchlistUnifiedFragment()
                1 -> TrendChartTabFragment()
                2 -> MarketHotFragment()
                3 -> HotNewsFragment()
                else -> throw IllegalArgumentException("Unknown position: $position")
            }
        }
    }

    /** 切换到精选股票 Tab（含其内部 AI精选 子模式） */
    fun switchToWatchlist() {
        viewPager.setCurrentItem(0, false)
    }

    /** 切换到精选股票 → 机构推荐模式 */
    fun switchToInstitutional() {
        viewPager.setCurrentItem(0, false)
        viewPager.postDelayed({
            getWatchlistFragment()?.switchToInstitutionalMode()
        }, 300)
    }

    /** 切换到 K线趋势 Tab */
    fun switchToTrend() {
        viewPager.setCurrentItem(1, false)
    }

    /** 获取精选股票 Fragment（遍历 childFragmentManager，避免依赖 ViewPager2 内部 tag） */
    fun getWatchlistFragment(): WatchlistUnifiedFragment? {
        return childFragmentManager.fragments.firstOrNull { it is WatchlistUnifiedFragment } as? WatchlistUnifiedFragment
    }

    /** 获取 K线趋势 Fragment */
    fun getTrendFragment(): TrendChartTabFragment? {
        return childFragmentManager.fragments.firstOrNull { it is TrendChartTabFragment } as? TrendChartTabFragment
    }

    /** 切到「K线趋势」子页并把指定股票补数据/识别/注入/聚焦（详情页联动入口） */
    fun openTrendPattern(stockCode: String, stockName: String) {
        viewPager.setCurrentItem(1, false)
        viewPager.postDelayed({
            val tf = getTrendFragment()
            if (tf != null) {
                tf.focusStockOnTrend(stockCode, stockName)
            } else {
                // 子 Fragment 尚未重建完成，稍后再试一次
                viewPager.postDelayed({
                    getTrendFragment()?.focusStockOnTrend(stockCode, stockName)
                }, 400)
            }
        }, 300)
    }
}
