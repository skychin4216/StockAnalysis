package com.chin.stockanalysis.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * ## 股票栏目 — v6.0 改版
 *
 * 顶部双 Tab 切换：
 * - Tab 0：热门行情（MarketHotFragment — 全球指数 + A 股热门板块，每 60s 刷新）
 * - Tab 1：我的自选（WatchlistGroupFragment — 自选组管理）
 */
class StockTabFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

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
        tabLayout = TabLayout(ctx).apply {
            setSelectedTabIndicatorColor(Color.parseColor("#E65100"))
            setTabTextColors(Color.parseColor("#999999"), Color.parseColor("#E65100"))
            setBackgroundColor(Color.WHITE)
            elevation = 2f
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

        // 绑定 TabLayout 和 ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "🔥 热门行情"
                1 -> "⭐ 我的自选"
                else -> ""
            }
        }.attach()

        return root
    }

    private class StockTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

        override fun getItemCount() = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> MarketHotFragment()
                1 -> WatchlistGroupFragment()
                else -> MarketHotFragment()
            }
        }
    }
}