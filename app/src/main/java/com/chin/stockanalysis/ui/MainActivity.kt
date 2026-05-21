package com.chin.stockanalysis.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.R
import com.chin.stockanalysis.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 主 Activity — 豆包风格四 Tab 布局
 *
 * 对话(0) | 股票(1) | 策略(2) | 我的(3)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView

    private val tabFragments: List<Fragment> by lazy {
        listOf(
            ChatTabFragment(),    // 0 - 对话
            StockTabFragment(),   // 1 - 股票
            StrategyFragment(),   // 2 - 策略
            SettingsFragment()    // 3 - 我的
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initGlobalServices()
        setupSystemBars()
        setupViewPager()
        setupBottomNavigation()
    }

    private fun initGlobalServices() {
        ApiConfigManager.getInstance(applicationContext)
    }

    private fun setupSystemBars() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }
    }

    private fun setupViewPager() {
        viewPager = binding.viewPager
        viewPager.adapter = MainTabAdapter(this, tabFragments)
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = tabFragments.size - 1
        viewPager.setCurrentItem(0, false)   // 默认「对话」tab
    }

    private fun setupBottomNavigation() {
        bottomNav = binding.bottomNav
        bottomNav.selectedItemId = R.id.nav_chat

        bottomNav.setOnItemSelectedListener { item ->
            val pageIndex = when (item.itemId) {
                R.id.nav_chat     -> 0
                R.id.nav_stock    -> 1
                R.id.nav_strategy -> 2
                R.id.nav_mine     -> 3
                else -> -1
            }
            if (pageIndex >= 0) {
                viewPager.setCurrentItem(pageIndex, false)
                true
            } else false
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val itemId = when (position) {
                    0 -> R.id.nav_chat
                    1 -> R.id.nav_stock
                    2 -> R.id.nav_strategy
                    3 -> R.id.nav_mine
                    else -> R.id.nav_chat
                }
                if (bottomNav.selectedItemId != itemId) {
                    bottomNav.selectedItemId = itemId
                }
            }
        })
    }

    private class MainTabAdapter(
        activity: AppCompatActivity,
        private val fragments: List<Fragment>
    ) : FragmentStateAdapter(activity) {
        override fun getItemCount() = fragments.size
        override fun createFragment(position: Int) = fragments[position]
    }
}
