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
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours as A股TradingHours
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.*

class MarketHotFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var indexRow: LinearLayout
    private lateinit var refreshTv: TextView
    private lateinit var marketStatusBar: LinearLayout
    private lateinit var marketStatusTv: TextView
    private var refreshJob: Job? = null
    private var cachedIndices: List<EastMoneyHotSectorSource.GlobalIndex> = emptyList()
    private var lastIndexFetchTime: Long = 0L   // 上次拉取时间戳

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6FA"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        tabLayout = TabLayout(requireContext()).apply {
            setSelectedTabIndicatorColor(Color.parseColor("#E65100"))
            setTabTextColors(Color.parseColor("#999999"), Color.parseColor("#E65100"))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(tabLayout)

        val indexContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL; setPadding(8, 4, 8, 4) }
        refreshTv = TextView(requireContext()).apply { text = "⏳ 加载中..."; textSize = 10f; setTextColor(Color.parseColor("#999999")); setPadding(0, 0, 4, 0) }
        indexContainer.addView(refreshTv)
        indexRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) }
        indexContainer.addView(indexRow)
        root.addView(HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false; addView(indexContainer) })

        // 📌 市场状态栏（上证指数和板块之间）（东方财富风格）
        marketStatusBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 3, 12, 3)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        marketStatusTv = TextView(requireContext()).apply {
            text = A股TradingHours.获取状态摘要()
            textSize = 10f; setTextColor(Color.parseColor("#E65100"))
            maxLines = 2
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        marketStatusBar.addView(marketStatusTv)
        root.addView(marketStatusBar)

        viewPager = ViewPager2(requireContext()).apply {
            adapter = SectorTabAdapter(this@MarketHotFragment)
            offscreenPageLimit = 2
        }
        root.addView(viewPager, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = when(pos) { 0 -> "🏭 行业" 1 -> "💡 概念" 2 -> "📈 指数" else -> "" }
        }.attach()

        startPoolScheduler(lifecycleScope)
        startAutoRefresh()
        return root
    }

    private class SectorTabAdapter(f: Fragment) : FragmentStateAdapter(f) {
        override fun getItemCount() = 3
        override fun createFragment(pos: Int) = when(pos) {
            0, 1, 2 -> SectorTabFragment.newInstance(when(pos) { 0->2 1->3 2->1 else->2 })
            else -> SectorTabFragment.newInstance(2)
        }
    }

    private fun startAutoRefresh() {
        refreshJob = lifecycleScope.launch {
            updateIndexRowFromCache()
            updateMarketStatusBar()
            while (isActive) {
                // A股交易中用标准间隔（约3s），其他情况1分钟刷新一次UI
                // (pool scheduler 已在后台每1分钟更新 globalIndices 缓存)
                val interval = if (A股TradingHours.a股是否交易中()) A股TradingHours.获取刷新间隔() else 60_000L
                delay(interval)
                if (isActive) {
                    updateIndexRowFromCache()
                    updateMarketStatusBar()
                }
            }
        }
    }

    /** 更新市场状态栏（只显示A股状态，不重复显示美股状态） */
    private fun updateMarketStatusBar() {
        lifecycleScope.launch(Dispatchers.Main) {
            marketStatusTv.text = A股TradingHours.获取状态摘要()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); refreshJob?.cancel() }

    private fun updateIndexRowFromCache() {
        val now = System.currentTimeMillis()
        val latest = globalIndices
        val useData = if (latest.isNotEmpty()) {
            cachedIndices = latest; lastIndexFetchTime = now; latest
        } else if (cachedIndices.isNotEmpty()) {
            cachedIndices
        } else {
            emptyList()
        }
        val aStatus = A股TradingHours.a股状态()
        val usStatus = A股TradingHours.美股状态()
        val lastDay = TradingDayPickerView.recentTradingDay()
        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        // 状态标签：只显示一个市场状态，避免重复
        val timeLabel = when {
            A股TradingHours.a股是否交易中() -> "🟢 A股交易中 $timeStr"
            usStatus == A股TradingHours.MarketStatus.交易中 -> "🟢 美股交易中 $timeStr"
            aStatus == A股TradingHours.MarketStatus.已收盘 -> "📌 A股 $lastDay 已收盘"
            aStatus == A股TradingHours.MarketStatus.未开盘 -> "⏳ 待开盘 · 显示 $lastDay 数据"
            else -> "📌 $lastDay 数据"
        }
        lifecycleScope.launch(Dispatchers.Main) {
            refreshTv.text = timeLabel
            updateIndexRow(useData)
        }
    }

    private fun updateIndexRow(indices: List<EastMoneyHotSectorSource.GlobalIndex>) {
        indexRow.removeAllViews()
        if (indices.isEmpty()) {
            indexRow.addView(TextView(requireContext()).apply{text="更新中...";textSize=10f;setTextColor(Color.parseColor("#999999"));setPadding(4,4,4,4)})
            return
        }
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
            // 所有指数卡片点击 → 可查看对应板块
            if (idx.code.isNotEmpty()) {
                card.setOnClickListener {
                    if (idx.name == "ETF") {
                        val d = SectorDetailFragment.newInstance("ETF", "")
                        activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit()
                    } else {
                        // 其他指数也跳转到对应板块
                        val d = SectorDetailFragment.newInstance(idx.name, idx.code)
                        activity?.supportFragmentManager?.beginTransaction()?.replace(android.R.id.content, d)?.addToBackStack(null)?.commit()
                    }
                }
            }
            indexRow.addView(card)
        }
    }
}