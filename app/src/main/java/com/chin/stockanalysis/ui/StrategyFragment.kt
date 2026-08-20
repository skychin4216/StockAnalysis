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
 * ## 策略栏目 — 量化选股（我的工作台 / 实仓 / 量化）
 *
 * 顶部三 Tab：
 * - Tab 0：我的工作台 (QuantWorkbenchFragment) — 内嵌超短/短/中/长 四周期页 + 公共操作
 * - Tab 1：实仓 (RealHoldingQuantFragment) — 真实持仓管理
 * - Tab 2：量化 (QuantPickingFragment) — 内部 3 Tab：策略 / 数据 / AI 分析
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
        0 -> getString(com.chin.stockanalysis.R.string.tab_my_workbench)
        1 -> getString(com.chin.stockanalysis.R.string.tab_real)
        2 -> getString(com.chin.stockanalysis.R.string.tab_stock_picking)
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

        // 切换 tab 时自动刷新持仓/结果
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                childFragmentManager.executePendingTransactions()
                val frag = childFragmentManager.findFragmentByTag("f$position")
                when {
                    frag is com.chin.stockanalysis.strategy.trade.QuantFragmentBase -> frag.refreshPositions()
                    frag is com.chin.stockanalysis.strategy.trade.QuantWorkbenchFragment -> frag.refreshAll()
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
                    // 周期操作已集成到「我的工作台」内部：切到 Tab 0 并转发给工作台
                    "EXECUTE_SIMULATE_TRADE" -> {
                        withContext(Dispatchers.Main) {
                            viewPager.setCurrentItem(0, true)  // Tab 0 = 我的工作台
                            runOnWorkbench("EXECUTE_SIMULATE_TRADE", null, null)
                        }
                    }
                    "RUN_PIPELINE" -> {
                        withContext(Dispatchers.Main) {
                            viewPager.setCurrentItem(0, true)  // Tab 0 = 我的工作台
                            runOnWorkbench("RUN_PIPELINE", null, null)
                        }
                    }
                    "SWITCH_TO_STRATEGY_TAB" -> {
                        withContext(Dispatchers.Main) {
                            (activity as? MainActivity)?.switchToStrategyTab()
                        }
                    }
                    "SWITCH_PERIOD_TAB" -> {
                        withContext(Dispatchers.Main) {
                            val period = cmd.extraParams["period"]?.toIntOrNull()
                            if (period == null || period !in 0..3) {
                                Log.w("StrategyFragment", "无效周期指令: ${cmd.extraParams}")
                                return@withContext
                            }
                            viewPager.setCurrentItem(0, true)  // Tab 0 = 我的工作台
                            runOnWorkbench("SWITCH_PERIOD_TAB", period, cmd.extraParams["op"] ?: "refresh")
                        }
                    }
                }
            }
        }
    }

    /**
     * 切换到「我的工作台」并等待其就绪后转发指令。
     * ViewPager2 的 setCurrentItem(smoothScroll=true) 是异步的，目标 fragment 在滚动过程中才创建，
     * 仅靠一次 post/executePendingTransactions 常找不到 fragment，因此用延迟重试
     * （每 150ms，最长约 1.8s）确保工作台模块就绪后再转发周期操作。
     */
    private fun runOnWorkbench(action: String, period: Int?, op: String?, attempt: Int = 0) {
        childFragmentManager.executePendingTransactions()
        val frag = childFragmentManager.findFragmentByTag("f0")
            as? com.chin.stockanalysis.strategy.trade.QuantWorkbenchFragment
        if (frag != null) {
            frag.handleExternalCommand(action, period, op)
            return
        }
        if (attempt >= 12) {
            Log.w("StrategyFragment", "工作台模块未就绪($action)")
            Toast.makeText(requireContext(), "工作台模块未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        viewPager.postDelayed({ runOnWorkbench(action, period, op, attempt + 1) }, 150L)
    }

    private class StrategyTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> com.chin.stockanalysis.strategy.trade.QuantWorkbenchFragment()
                1 -> com.chin.stockanalysis.strategy.trade.RealHoldingQuantFragment()
                2 -> QuantPickingFragment()
                else -> throw IllegalStateException("Unknown position: $position")
            }
        }
    }
}
