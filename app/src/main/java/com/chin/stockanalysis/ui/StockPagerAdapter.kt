package com.chin.stockanalysis.ui

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 Adapter - 用于显示不同标签页的股票列表
 */
class StockPagerAdapter(
    fragment: Fragment,
    private val pages: List<Fragment>,
    private val titles: List<String>
) : FragmentStateAdapter(fragment) {

    override fun getItemCount() = pages.size

    override fun createFragment(position: Int) = pages[position]

    fun getPageTitle(position: Int) = titles[position]
}

