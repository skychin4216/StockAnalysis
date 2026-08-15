package com.chin.stockanalysis.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 策略栏目 — v12.0 量化选股 改版
 *
 * 顶部六 Tab：
 * - Tab 0：超短线 (UltraShortQuantFragment) — 持仓1天，T+1卖出
 * - Tab 1：短线量化 (ShortTermQuantFragment) — 持仓1天~2周
 * - Tab 2：中线量化 (MidTermQuantFragment) — 持仓1~6个月
 * - Tab 3：长线量化 (LongTermQuantFragment) — 持仓6月~1年
 * - Tab 4：实仓 (RealHoldingQuantFragment) — 真实持仓管理
 * - Tab 5：量化选股 (QuantPickingFragment) — 内部 4 Tab：策略 / 数据 / 我的工作台 / AI 分析
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
            // 固定模式：按屏幕宽度平均分布所有 tab（每个 tab = 屏幕宽度 / tab 数量）
            tabMode = TabLayout.MODE_FIXED
        }
        root.addView(tabLayout)

        // ViewPager2
        viewPager = ViewPager2(ctx).apply {
            adapter = StrategyTabAdapter(this@StrategyFragment)
            offscreenPageLimit = 2
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
        0 -> getString(com.chin.stockanalysis.R.string.tab_ultra_short)
        1 -> getString(com.chin.stockanalysis.R.string.tab_short)
        2 -> getString(com.chin.stockanalysis.R.string.tab_mid)
        3 -> getString(com.chin.stockanalysis.R.string.tab_long)
        4 -> getString(com.chin.stockanalysis.R.string.tab_real)
        5 -> getString(com.chin.stockanalysis.R.string.tab_stock_picking)
        else -> ""
        }
        }.attach()

        // MODE_FIXED 下每个 tab 平均分配屏幕宽度；清除默认最小宽度与内边距，避免挤压不均
        for (i in 0 until tabLayout.tabCount) {
            tabLayout.getTabAt(i)?.view?.let { tabView ->
                tabView.minimumWidth = 0
                tabView.setPadding(0, 0, 0, 0)
                (tabView.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
            }
        }

        // 切换周期 tab 时自动刷新持仓
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                childFragmentManager.executePendingTransactions()
                val frag = childFragmentManager.findFragmentByTag("f$position")
                if (frag is com.chin.stockanalysis.strategy.trade.QuantFragmentBase) {
                    frag.refreshPositions()
                }
            }
        })

        return root
    }

    private fun observeCommands() {
        lifecycleScope.launch(Dispatchers.IO) {
            com.chin.stockanalysis.ui.CrossTabBus.commandFlow.collect { cmd ->
                Log.i("StrategyFragment", "📢 收到指令: ${cmd.action}")
                when (cmd.action) {
                    "EXECUTE_SIMULATE_TRADE" -> {
                        withContext(Dispatchers.Main) {
                            viewPager.setCurrentItem(2, true)  // Tab 2 = 中线量化
                            childFragmentManager.executePendingTransactions()
                            val frag = childFragmentManager.findFragmentByTag("f2")
                                as? com.chin.stockanalysis.strategy.trade.MidTermQuantFragment
                            if (frag != null) {
                                frag.autoExecuteTrade()
                            } else {
                                Log.w("StrategyFragment", "MidTermQuantFragment not found")
                                Toast.makeText(requireContext(), "中线量化模块未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "RUN_PIPELINE" -> {
                        withContext(Dispatchers.Main) {
                            viewPager.setCurrentItem(1, true)  // Tab 1 = 短线量化
                            childFragmentManager.executePendingTransactions()
                            val frag = childFragmentManager.findFragmentByTag("f1")
                                as? com.chin.stockanalysis.strategy.trade.ShortTermQuantFragment
                            if (frag != null) {
                                frag.autoRunPipeline()
                            } else {
                                Log.w("StrategyFragment", "ShortTermQuantFragment not found")
                                Toast.makeText(requireContext(), "短线量化模块未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "SWITCH_TO_STRATEGY_TAB" -> {
                        withContext(Dispatchers.Main) {
                            (activity as? MainActivity)?.switchToStrategyTab()
                        }
                    }
                }
            }
        }
    }

    private class StrategyTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 6

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> com.chin.stockanalysis.strategy.trade.UltraShortQuantFragment()
                1 -> com.chin.stockanalysis.strategy.trade.ShortTermQuantFragment()
                2 -> com.chin.stockanalysis.strategy.trade.MidTermQuantFragment()
                3 -> com.chin.stockanalysis.strategy.trade.LongTermQuantFragment()
                4 -> com.chin.stockanalysis.strategy.trade.RealHoldingQuantFragment()
                5 -> QuantPickingFragment()
                else -> throw IllegalStateException("Unknown position: $position")
            }
        }
    }
}
