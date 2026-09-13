package com.chin.stockanalysis.ui

import android.app.AlertDialog
import android.content.Context
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
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader
import com.chin.stockanalysis.config.LanguageManager
import com.chin.stockanalysis.R
import com.chin.stockanalysis.conversation.ConversationRepository
import com.chin.stockanalysis.databinding.ActivityMainBinding
import com.chin.stockanalysis.news.HotSectorNewsUpdater
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.storage.BackupManager
import com.chin.stockanalysis.update.AppUpdateManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 Activity — 豆包风格五 Tab 布局
 *
 * 对话(0) | 智能体(1) | 股票(2) | 量化选股(3) | 我的(4)
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initGlobalServices()
        // 加载三年 walk-forward 固化参数（assets/backtest_params.json），失败时静默降级到代码默认
        BacktestParamsLoader.load(applicationContext)
        setupSystemBars()
        setupViewPager()
        setupBottomNavigation()
        // 处理启动时的分享意图
        handleShareIntent(intent)
        // 返回键：智能体对话页返回时应回到智能体列表，而非退出 App
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val agentChatFragment = supportFragmentManager.findFragmentByTag("agent_chat")
                if (agentChatFragment != null) {
                    val agentTab = supportFragmentManager.fragments
                        .firstOrNull { it is AgentTabFragment } as? AgentTabFragment
                    if (agentTab != null) {
                        agentTab.closeAgentChat()
                        return
                    }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * 处理其他应用分享的图片/PDF/文字 → 路由到 AI 对话框
     * AI 分析后询问是否保存到机构推荐
     */
    private fun handleShareIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != android.content.Intent.ACTION_SEND) return

        @Suppress("DEPRECATION")
        val sharedUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
        val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)

        // 切换到 AI 对话 Tab
        viewPager.postDelayed({
            viewPager.setCurrentItem(0, false) // Chat tab
            bottomNav.selectedItemId = R.id.nav_chat

            viewPager.postDelayed({
                val chatFragment = supportFragmentManager.fragments
                    .firstOrNull { it is ChatTabFragment } as? ChatTabFragment
                chatFragment?.handleSharedContent(sharedUri, sharedText)
            }, 400)
        }, 200)
    }

    private fun initGlobalServices() {
        FeatureFlagManager.init(applicationContext)
        ApiConfigManager.getInstance(applicationContext)
        // 统一数据源配置
        com.chin.stockanalysis.config.DataConfig.load(applicationContext)
        // 数据备份初始化
        initBackupSystem()
        // 统一后台调度器
        com.chin.stockanalysis.stock.database.AppBackgroundRunner.start(applicationContext, lifecycleScope)
        // OS 级兜底：每交易日 11:35/15:05 自动补齐实仓日K（WorkManager，App 后台/被杀也会到点执行）
        com.chin.stockanalysis.sync.RealPositionDailySyncScheduler.schedule(applicationContext)
        // logcat 自动落盘（供「上传今日数据」同步日志到 COS）
        com.chin.stockanalysis.util.FileLogger.start(applicationContext)
        migrateLegacyConversations()
        // 后台拉取热点板块新闻
        lifecycleScope.launch(Dispatchers.IO) {
            HotSectorNewsUpdater(applicationContext).updateIfNeeded()
        }
        // 构建股票名称 Trie 词典（供意图解析使用）
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!com.chin.stockanalysis.stock.data.StockNameTrie.isBuilt) {
                    com.chin.stockanalysis.stock.data.StockNameTrie.build(applicationContext)
                    android.util.Log.i("MainActivity", "StockNameTrie 构建完成")
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "StockNameTrie 构建失败: ${e.message}")
            }
        }
        // 后台预热 sector_stocks：自动拉取热门板块成分股写入本地数据库
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                prefetchSectorStocks()
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "sector_stocks 预热失败: ${e.message}")
            }
        }
        // 后台检查 App 更新（延迟执行，避免与启动流程竞争）
        lifecycleScope.launch(Dispatchers.IO) {
            delay(8000)
            AppUpdateManager.checkForUpdate(applicationContext) { info ->
                if (info != null) {
                    runOnUiThread { AppUpdateManager.showUpdateDialog(this@MainActivity, info) }
                }
            }
        }
    }

    private fun initBackupSystem() {
        // 仅首次启动弹备份引导框，避免每次启动都打扰用户
        val prefs = getSharedPreferences("backup_setup", MODE_PRIVATE)
        if (!prefs.getBoolean("setup_prompted", false)) {
            BackupManager.initialize(
                this,
                onNeedSetup = {
                    runOnUiThread {
                        prefs.edit().putBoolean("setup_prompted", true).apply()
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
    }

    /**
     * 后台预热 sector_stocks 表：启动时自动拉取热门板块成分股。
     * 仅拉取热门板块，不影响 App 主流程。
     * 如果 24 小时内已拉取过，则跳过。
     */
    private fun prefetchSectorStocks() {
        val sectorSource = com.chin.stockanalysis.stock.data.sources.EastMoneySectorSource()
        val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(applicationContext)
        val prefs = applicationContext.getSharedPreferences("sector_stock_prefetch", 0)
        val lastFetch = prefs.getLong("last_prefetch_time", 0)
        // 24小时内不重复拉取
        if (System.currentTimeMillis() - lastFetch < 24 * 3600 * 1000L) {
            android.util.Log.i("MainActivity", "sector_stocks 预热跳过: 24小时内已拉取")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            android.util.Log.i("MainActivity", "sector_stocks 预热开始...")
            // 别名逐个遍历；板块代码与官方板块名均从东财板块清单运行时解析。
            // （2026-09 事故：旧静态 BK 表把 BK1045(房地产服务)整批写成"稀土/碳中和"，
            //   详见 EastMoneySectorSource 注释；故不再按别名硬编码落库。）
            val keywords = com.chin.stockanalysis.stock.data.sources.EastMoneySectorSource.SECTOR_KEYWORDS
            val writtenKeys = LinkedHashSet<String>()
            var successCount = 0
            for (kw in keywords) {
                try {
                    val result = sectorSource.fetchByName(kw, topN = 30, excludeKcb = false, excludeCyb = false)
                    if (result == null || result.second.isEmpty()) continue
                    val (officialName, stocks) = result
                    if (!writtenKeys.add(officialName)) continue   // 同一官方板块多别名只拉一次
                    val codes = stocks.map { it.code }
                    // 差集清理：移除该官方板块下已不在成分列表中的旧行（含历史脏数据）
                    db.sectorStockDao().pruneSectorKeyNotIn(officialName, codes)
                    db.sectorStockDao().insertAll(stocks.map {
                        com.chin.stockanalysis.stock.database.SectorStockEntity(
                            sectorKey = officialName,
                            sectorName = officialName,
                            stockCode = it.code
                        )
                    })
                    successCount++
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "预热板块[$kw]失败: ${e.message}")
                }
            }
            // 一次性清理历史"别名键"脏数据（旧版把板块别名当 key 落库，
            // BK 代码漂移时污染了 稀土/钢铁/AI 等键；官方名入库后别名键已无意义）
            if (successCount > 0 && !prefs.getBoolean("alias_keys_v2_cleaned", false)) {
                val legacyKeys = (keywords - writtenKeys).toList()
                if (legacyKeys.isNotEmpty()) {
                    db.sectorStockDao().deleteSectorKeys(legacyKeys)
                    android.util.Log.i("MainActivity", "已清除 ${legacyKeys.size} 个历史别名板块键")
                }
                prefs.edit().putBoolean("alias_keys_v2_cleaned", true).apply()
            }
            prefs.edit().putLong("last_prefetch_time", System.currentTimeMillis()).apply()
            android.util.Log.i("MainActivity", "sector_stocks 预热完成: $successCount/${writtenKeys.size} 个官方板块")
        }
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
    override fun onStart() {
        super.onStart()
        // App 回到前台时清扫过期 AI Provider 占用（应对系统冻结导致的释放遗漏）
        com.chin.stockanalysis.ai.AiProviderPool.sweepExpired()
    }

    override fun onResume() {
        super.onResume()
        // 进程解冻后强制清理 Provider 锁（Samsung Nandswap）
        com.chin.stockanalysis.ai.AiProviderPool.emergencyReset("onResume")
        // 用户从"安装未知应用"设置页返回后，继续未完成的 APK 安装
        AppUpdateManager.retryPendingInstall(this)
    }

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

    fun switchToStockTab() {
        viewPager.setCurrentItem(2, false)
        bottomNav.selectedItemId = R.id.nav_stock
    }

    /** 导航到精选股票 → 机构推荐 Tab */
    fun navigateToInstitutional() {
        switchToStockTab()
        viewPager.postDelayed({
            val stockTab = supportFragmentManager.fragments
                .firstOrNull { it is StockTabFragment } as? StockTabFragment
            stockTab?.switchToInstitutional()
        }, 300)
    }

    /** 导航到「股票 → K线趋势」页并聚焦某只股票的形态图谱（个股详情页等联动入口） */
    fun navigateToTrendPattern(stockCode: String, stockName: String) {
        switchToStockTab()
        viewPager.postDelayed({
            val stockTab = supportFragmentManager.fragments
                .firstOrNull { it is StockTabFragment } as? StockTabFragment
            stockTab?.openTrendPattern(stockCode, stockName)
        }, 350)
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