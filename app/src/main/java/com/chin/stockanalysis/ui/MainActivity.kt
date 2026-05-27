package com.chin.stockanalysis.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.R
import com.chin.stockanalysis.conversation.ConversationRepository
import com.chin.stockanalysis.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // 需求3：安装新版本时自动查询外部路径并导入旧对话数据
        migrateLegacyConversations()
    }

    private fun migrateLegacyConversations() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                ConversationRepository(applicationContext).migrateLegacyConversations()
            }
            if (count > 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ 已从旧版本导入 $count 条历史对话",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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
        // 东方财富风格：选中项橙红色
        bottomNav.itemIconTintList = resources.getColorStateList(com.chin.stockanalysis.R.color.nav_item_tint)
        bottomNav.itemTextColor = resources.getColorStateList(com.chin.stockanalysis.R.color.nav_item_tint)
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
