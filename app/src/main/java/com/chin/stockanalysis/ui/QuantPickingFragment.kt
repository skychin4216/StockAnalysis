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
 * ## 量化选股 — 内部 4 Tab
 *
 * 挂载在「量化」外层 Tab 下：
 * - Tab 0：策略 (StrategyListFragment) — 策略沙盒
 * - Tab 1：数据 (StrategyImportFragment) — 数据管理 & 导入
 * - Tab 2：我的工作台 (QuantWorkbenchFragment) — 量化工作台
 * - Tab 3：AI 分析 (AIAnalysisFragment) — 个股 / 板块 Agent 分析
 */
class QuantPickingFragment : Fragment() {

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
            adapter = PickingTabAdapter(this@QuantPickingFragment)
            offscreenPageLimit = 3
        }
        root.addView(viewPager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        // 绑定
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(com.chin.stockanalysis.R.string.tab_strategy)
                1 -> getString(com.chin.stockanalysis.R.string.tab_data)
                2 -> getString(com.chin.stockanalysis.R.string.tab_my_workbench)
                3 -> getString(com.chin.stockanalysis.R.string.tab_ai_analysis)
                else -> ""
            }
        }.attach()

        return root
    }

    private class PickingTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> StrategyListFragment()
                1 -> StrategyImportFragment()
                2 -> com.chin.stockanalysis.strategy.trade.QuantWorkbenchFragment()
                3 -> AIAnalysisFragment()
                else -> throw IllegalStateException("Unknown position: $position")
            }
        }
    }
}
