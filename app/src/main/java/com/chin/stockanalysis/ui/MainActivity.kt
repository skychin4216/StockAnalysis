package com.chin.stockanalysis.ui

import android.app.AlertDialog
import android.content.Intent
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
import com.chin.stockanalysis.news.HotSectorNewsUpdater
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.storage.BackupManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 Activity — 豆包风格五 Tab 布局
 *
 * 对话(0) | 智能体(1) | 股票(2) | 策略(3) | 我的(4)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView

    companion object {
        private const val REQUEST_BACKUP_FOLDER = 9001
    }

    private val tabFragments: List<Fragment> by lazy {
        listOf(
            ChatTabFragment(),    // 0 - 对话
            AgentTabFragment(),   // 1 - 智能体
            StockTabFragment(),   // 2 - 股票
            StrategyFragment(),   // 3 - 策略
            SettingsFragment()    // 4 - 我的
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
        // 数据备份初始化
        initBackupSystem()
        // 统一后台调度器
        com.chin.stockanalysis.stock.database.AppBackgroundRunner.start(applicationContext, lifecycleScope)
        migrateLegacyConversations()
        // 后台拉取热点板块新闻
        lifecycleScope.launch(Dispatchers.IO) {
            HotSectorNewsUpdater(applicationContext).updateIfNeeded()
        }
    }

    private fun initBackupSystem() {
        BackupManager.initialize(
            this,
            onNeedSetup = {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("💾 数据备份")
                        .setMessage("选择备份文件夹可保护你的数据（卸载重装后可恢复）。\n\n建议选择 /Documents/StockAnalysis")
                        .setPositiveButton("选择文件夹") { _, _ ->
                            BackupManager.openFolderPicker(this, REQUEST_BACKUP_FOLDER)
                        }
                        .setNegativeButton("稍后", null)
                        .show()
                }
            },
            onRestored = {
                runOnUiThread {
                    Toast.makeText(this, "✅ 数据已从备份恢复", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    /** 处理备份文件夹选择结果 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BACKUP_FOLDER && resultCode == RESULT_OK && data?.data != null) {
            BackupManager.onFolderSelected(this, data.data!!)
            // 立即做一次备份
            lifecycleScope.launch(Dispatchers.IO) {
                BackupManager.backupNow(this@MainActivity)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "✅ 备份文件夹已设置", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** App 进入后台时自动备份 */
    override fun onStop() {
        super.onStop()
        lifecycleScope.launch(Dispatchers.IO) {
            BackupManager.backupNow(this@MainActivity)
        }
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
        viewPager.isUserInputEnabled = true   // 允许左右滑动切换 Tab
        viewPager.offscreenPageLimit = tabFragments.size - 1
        viewPager.setCurrentItem(0, false)   // 默认「对话」tab
    }

    override fun onBackPressed() {
        // Bug fix: 在智能体对话页面按返回键应返回智能体列表，而不是退出app
        val agentChatFragment = supportFragmentManager.findFragmentByTag("agent_chat")
        if (agentChatFragment != null) {
            val agentTab = supportFragmentManager.fragments
                .firstOrNull { it is AgentTabFragment } as? AgentTabFragment
            if (agentTab != null) {
                agentTab.closeAgentChat()
                return
            }
        }
        super.onBackPressed()
    }

    private fun setupBottomNavigation() {
        bottomNav = binding.bottomNav
        bottomNav.itemIconTintList = resources.getColorStateList(com.chin.stockanalysis.R.color.nav_item_tint)
        bottomNav.itemTextColor = resources.getColorStateList(com.chin.stockanalysis.R.color.nav_item_tint)
        bottomNav.selectedItemId = R.id.nav_chat

        bottomNav.setOnItemSelectedListener { item ->
            val pageIndex = when (item.itemId) {
                R.id.nav_chat     -> 0
                R.id.nav_agent    -> 1
                R.id.nav_stock    -> 2
                R.id.nav_strategy -> 3
                R.id.nav_mine     -> 4
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
                    1 -> R.id.nav_agent
                    2 -> R.id.nav_stock
                    3 -> R.id.nav_strategy
                    4 -> R.id.nav_mine
                    else -> R.id.nav_chat
                }
                if (bottomNav.selectedItemId != itemId) {
                    bottomNav.selectedItemId = itemId
                }
            }
        })
    }

    fun switchToStrategyTab() {
        viewPager.setCurrentItem(3, false)
        bottomNav.selectedItemId = R.id.nav_strategy
    }

    fun switchToChatAndSend(message: String) {
        viewPager.setCurrentItem(0, false)
        bottomNav.selectedItemId = R.id.nav_chat
        viewPager.postDelayed({
            val chatFragment = supportFragmentManager.fragments
                .firstOrNull { it is ChatTabFragment } as? ChatTabFragment
            if (chatFragment != null) {
                chatFragment.sendMessageFromExternal(message)
            }
        }, 300)
    }

    private class MainTabAdapter(
        activity: AppCompatActivity,
        private val fragments: List<Fragment>
    ) : FragmentStateAdapter(activity) {
        override fun getItemCount() = fragments.size
        override fun createFragment(position: Int) = fragments[position]
    }
}