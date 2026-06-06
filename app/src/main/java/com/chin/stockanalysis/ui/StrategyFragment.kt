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
 * ## 策略栏目 — v8.0 改版
 *
 * 顶部三 Tab：
 * - Tab 0：量化选股 (StrategyListFragment)
 * - Tab 1：热点新闻 (HotNewsFragment)
 * - Tab 2：模拟交易 (SimulationTradeFragment)
 */
class StrategyFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
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
            tabMode = TabLayout.MODE_FIXED
        }
        root.addView(tabLayout)

        // ViewPager2
        viewPager = ViewPager2(ctx).apply {
            adapter = StrategyTabAdapter(this@StrategyFragment)
            offscreenPageLimit = 1
        }
        root.addView(viewPager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        // 绑定
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "🎯 量化选股"
                1 -> "🔥 热点新闻"
                2 -> "💹 模拟交易"
                else -> ""
            }
        }.attach()

        return root
    }

    private class StrategyTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> StrategyListFragment()
                1 -> HotNewsFragment()
                2 -> com.chin.stockanalysis.strategy.trade.SimulationTradeFragment()
                else -> StrategyListFragment()
            }
        }
    }
}