package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.Companion.globalIndices
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.Companion.startPoolScheduler
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.*

class MarketHotFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var indexRow: LinearLayout
    private lateinit var refreshTv: TextView
    private var refreshJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        // 标题
        val hdr = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 40, 24, 12); setBackgroundColor(Color.WHITE)
        }
        hdr.addView(TextView(requireContext()).apply {
            text = "🔥 热门行情"; textSize = 22f; setTextColor(Color.parseColor("#1A1A2E")); setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        refreshTv = TextView(requireContext()).apply { text = "⏳ 加载中..."; textSize = 11f; setTextColor(Color.parseColor("#999999")) }
        hdr.addView(refreshTv)
        root.addView(hdr)
        // 全球指数行
        indexRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(12, 6, 12, 6); setBackgroundColor(Color.WHITE) }
        root.addView(HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false; addView(indexRow) })
        // TabLayout
        tabLayout = TabLayout(requireContext()).apply {
            setSelectedTabIndicatorColor(Color.parseColor("#E65100"))
            setTabTextColors(Color.parseColor("#999999"), Color.parseColor("#E65100"))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(tabLayout)
        // ViewPager2
        viewPager = ViewPager2(requireContext()).apply {
            adapter = SectorTabAdapter(this@MarketHotFragment)
            offscreenPageLimit = 2
        }
        root.addView(viewPager, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = when(pos) { 0 -> "🏭 行业" 1 -> "💡 概念" 2 -> "📈 指数" else -> "" }
        }.attach()

        // 启动全局数据池调度器（App 生命周期级别，重复调用安全）
        startPoolScheduler(lifecycleScope)

        startAutoRefresh()
        return root
    }

    private class SectorTabAdapter(f: Fragment) : FragmentStateAdapter(f) {
        override fun getItemCount() = 3
        override fun createFragment(pos: Int) = SectorTabFragment.newInstance(when(pos) { 0->2 1->3 2->1 else->2 })
    }

    private fun startAutoRefresh() {
        refreshJob = lifecycleScope.launch {
            updateIndexRowFromCache()
            while (isActive) {
                delay(60_000L)
                if (isActive) updateIndexRowFromCache()
            }
        }
    }
    override fun onDestroyView() { super.onDestroyView(); refreshJob?.cancel() }

    private fun updateIndexRowFromCache() {
        refreshTv.text = "🕐 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
        updateIndexRow(globalIndices)
    }

    private fun updateIndexRow(indices: List<EastMoneyHotSectorSource.GlobalIndex>) {
        indexRow.removeAllViews()
        if (indices.isEmpty()) { indexRow.addView(TextView(requireContext()).apply{text="暂无";textSize=12f;setTextColor(Color.GRAY)}); return }
        for (idx in indices) {
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(12, 4, 12, 4)
                setBackgroundColor(Color.parseColor("#F8F9FC")); (layoutParams as? LayoutParams)?.setMargins(3, 0, 3, 0)
            }
            card.addView(TextView(requireContext()).apply{text=idx.name;textSize=10f;setTextColor(Color.parseColor("#666666"))})
            card.addView(TextView(requireContext()).apply{text=String.format("%.0f",idx.price);textSize=13f;setTextColor(Color.parseColor("#1A1A2E"));setTypeface(null,Typeface.BOLD)})
            card.addView(TextView(requireContext()).apply{
                val sign=if(idx.changePercent>=0) "+" else ""
                text="$sign${"%.2f".format(idx.changePercent)}%";textSize=10f
                setTextColor(if(idx.changePercent>=0)Color.parseColor("#E53935") else Color.parseColor("#43A047"))
            })
            if (idx.name == "ETF") {
                card.setOnClickListener {
                    val d = SectorDetailFragment.newInstance("ETF", "")
                    activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit()
                }
            }
            indexRow.addView(card)
        }
    }
}