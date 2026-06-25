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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * ## 策略栏目 — v9.0 改版
 *
 * 顶部三 Tab：
 * - Tab 0：量化选股 (StrategyListFragment)
 * - Tab 1：中线量化 (MidTermQuantFragment)
 * - Tab 2：短线量化 (ShortTermQuantFragment)
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

        // 监听跨Tab指令
        observeCommands()

        // 绑定
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "🎯 量化选股"
                1 -> "💹 中线量化"
                2 -> "🤖 短线量化"
                else -> ""
            }
        }.attach()

        return root
    }

    private fun observeCommands() {
        lifecycleScope.launch(Dispatchers.IO) {
            com.chin.stockanalysis.ui.CrossTabBus.command.collect { cmd ->
                if (cmd != null) {
                    Log.i("StrategyFragment", "📢 收到指令: ${cmd.action}")
                    when (cmd.action) {
                        "EXECUTE_SIMULATE_TRADE" -> {
                            // 切换到中线量化Tab并自动触发买入
                            withContext(Dispatchers.Main) {
                                viewPager.setCurrentItem(1, true)  // Tab 1 = 中线量化
                                viewPager.postDelayed({
                                    val frag = childFragmentManager.fragments
                                        .firstOrNull { it is com.chin.stockanalysis.strategy.trade.MidTermQuantFragment }
                                        as? com.chin.stockanalysis.strategy.trade.MidTermQuantFragment
                                    frag?.autoExecuteTrade()
                                }, 500)
                            }
                        }
                        "RUN_PIPELINE" -> {
                            withContext(Dispatchers.Main) {
                                viewPager.setCurrentItem(2, true)  // Tab 2 = 短线量化
                                viewPager.postDelayed({
                                    val frag = childFragmentManager.fragments
                                        .firstOrNull { it is com.chin.stockanalysis.strategy.trade.ShortTermQuantFragment }
                                        as? com.chin.stockanalysis.strategy.trade.ShortTermQuantFragment
                                    frag?.autoRunPipeline()
                                }, 500)
                            }
                        }
                        "SWITCH_TO_STRATEGY_TAB" -> {
                            withContext(Dispatchers.Main) {
                                (activity as? MainActivity)?.switchToStrategyTab()
                            }
                        }
                    }
                    com.chin.stockanalysis.ui.CrossTabBus.consumeCommand()
                }
            }
        }
    }

    private class StrategyTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> StrategyListFragment()
                1 -> com.chin.stockanalysis.strategy.trade.MidTermQuantFragment()
                2 -> com.chin.stockanalysis.strategy.trade.ShortTermQuantFragment()
                else -> StrategyListFragment()
            }
        }
    }
}