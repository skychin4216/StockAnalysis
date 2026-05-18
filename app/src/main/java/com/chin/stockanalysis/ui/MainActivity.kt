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
 * StockAnalysis v2.0 — 主 Tab Activity（UI 层）
 *
 * 对齐《项目架构说明.md》：
 * - Multi-Fragment + BottomNavigationView + ViewPager2
 * - 📊 StockTabFragment：实时行情 / K线 / 技术指标
 * - 💬 ChatTabFragment：AI 智能对话入口
 * - ⚙️ SettingsFragment：API Provider / Model / API Key 配置
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView

    private val tabFragments: List<Fragment> by lazy {
        listOf(
            StockTabFragment(),
            ChatTabFragment(),
            SettingsFragment()
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
        // 预热配置管理器，保证设置页和聊天页读取同一份 Provider/Model/API Key 配置。
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
        // 默认显示聊天 Tab（index=1），而非股票 Tab（index=0）
        viewPager.setCurrentItem(1, false)
    }

    private fun setupBottomNavigation() {
        bottomNav = binding.bottomNav

        // 初始同步底部导航高亮为"聊天"（与 setupViewPager 中默认选中 index=1 保持一致）
        bottomNav.selectedItemId = R.id.nav_chat

        bottomNav.setOnItemSelectedListener { item ->
            val pageIndex = when (item.itemId) {
                R.id.nav_stock -> 0
                R.id.nav_chat -> 1
                R.id.nav_settings -> 2
                else -> -1
            }
            if (pageIndex >= 0) {
                viewPager.setCurrentItem(pageIndex, false)
                true
            } else {
                false
            }
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val itemId = when (position) {
                    0 -> R.id.nav_stock
                    1 -> R.id.nav_chat
                    2 -> R.id.nav_settings
                    else -> R.id.nav_stock
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
        override fun getItemCount(): Int = fragments.size
        override fun createFragment(position: Int): Fragment = fragments[position]
    }
}