package com.chin.stockanalysis.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabaseManager
import com.chin.stockanalysis.stock.data.MultiSourceStockRepository
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.prefetch.StockPrefetchScheduler
import com.chin.stockanalysis.stock.prefetch.UserQueryHistory
import com.chin.stockanalysis.stock.theme.ThemeStockService
import com.chin.stockanalysis.stock.theme.UserPreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ## 股票查询引擎（共享核心逻辑 + 后台预取）
 *
 * 将 `buildSystemPromptWithStockData` 提取为独立类，
 * 供 `ChatTabFragment`、`ChatActivity` 等多个入口复用。
 *
 * ### v4.0 新增：后台预取（Prefetch）
 *
 * 为了像豆包一样快速响应，引入了**预测性缓存预热**机制：
 * 1. `UserQueryHistory` 记录用户查询的高频股票/主题，持久化到 SharedPreferences
 * 2. `StockPrefetchScheduler` 在后台周期性（活跃时段5分钟/非活跃30分钟）拉取高频数据
 * 3. 预取数据以 10 分钟 TTL 写入缓存（而非交易中的 1s TTL）
 * 4. 用户发问时，10分钟内的缓存直接秒返回，无需实时请求
 *
 * ```
 * 无预取（首次查询）：
 *   用户问 → 建立TCP连接 → 请求数据 → 等待响应 → AI生成 ≈ 2-5秒
 *
 * 有预取（后台已预热）：
 *   用户问 → 缓存命中（<1ms）→ AI直接生成 ≈ 0.5-1秒
 * ```
 *
 * ### 完整调用链
 * ```
 * ChatTabFragment / ChatActivity
 *   └─ StockQueryEngine.buildSystemPrompt()
 *        │
 *        ├─ [阶段0] UserPreferenceManager.learnFromMessage()
 *        │    └─ SharedPreferences（持久化：市值/板块/价格偏好）
 *        │
 *        ├─ [阶段1] ThemeStockService.processThemeQuery()
 *        │    │    识别"商业航天"/"有色金属"/"AI算力"等
 *        │    ├─ 方案A: ThemeStockLibrary（内置主题库）
 *        │    │    └─ MultiSourceStockRepository.getRealtime() ← 预取缓存命中则秒返回
 *        │    ├─ 方案B: EastMoneySectorSource.fetchByName()
 *        │    │    └─ 东方财富板块成分股 API → getRealtime()
 *        │    └─ EastMoneyBidAskSource.fetchBidAsk()（含"买手/卖手"时触发）
 *        │
 *        ├─ [阶段2] StockService.processUserInput()
 *        │    │    识别股票代码（600519）或名称（贵州茅台）
 *        │    └─ MultiSourceStockRepository.getRealtime() ← 预取缓存命中则秒返回
 *        │
 *        └─ [阶段3] 通用回答
 *               └─ AI 基于训练知识回答，无实时数据注入
 * ```
 *
 * ### 使用方式（在 Fragment/Activity 中）
 * ```kotlin
 * private val queryEngine by lazy { StockQueryEngine.create(requireContext()) }
 *
 * // 启动预取调度器（在 onViewCreated 或 onCreate 时调用）
 * queryEngine.startPrefetch(lifecycleScope)
 *
 * // 在 IO 线程调用
 * val systemPrompt = queryEngine.buildSystemPrompt(
 *     userText = userText,
 *     baseSystemPrompt = BASE_SYSTEM_PROMPT,
 *     onPreferenceLeaned = { runOnUiThread { Toast.show(...) } }
 * )
 * ```
 */
class StockQueryEngine private constructor(
    context: Context,
    private val repository: MultiSourceStockRepository
) {

    companion object {
        private const val TAG = "StockQueryEngine"

        /**
         * 工厂方法（推荐使用）：自动创建多源仓储并初始化引擎
         */
        fun create(context: Context): StockQueryEngine {
            val repo = StockDataSourceFactory.createDefaultRepository(context.applicationContext)
            return StockQueryEngine(context.applicationContext, repo)
        }

        /**
         * 工厂方法（高级用法）：复用已有的多源仓储（避免重复初始化）
         */
        fun create(context: Context, repository: MultiSourceStockRepository): StockQueryEngine {
            return StockQueryEngine(context.applicationContext, repository)
        }
    }

    /** 核心：旧版具体股票处理（StockService）*/
    val stockService: StockService = StockService(repository = repository)

    /** 核心：主题/板块 + 盘口处理（ThemeStockService）*/
    private val themeStockService: ThemeStockService = ThemeStockService(repository = repository)

    /** 用户偏好记忆（跨会话持久化）*/
    val userPrefManager: UserPreferenceManager = UserPreferenceManager.getInstance(context)

    /** 用户查询历史（学习高频股票/主题，用于预取决策）*/
    val queryHistory: UserQueryHistory = UserQueryHistory.getInstance(context)

    /** v5.0 新增：本地数据库管理器（股票基本信息 + 板块映射）*/
    val dbManager: StockDatabaseManager = StockDatabaseManager.getInstance(context)

    /** 后台预取调度器（预热缓存，加速响应）*/
    val prefetchScheduler: StockPrefetchScheduler = StockPrefetchScheduler(
        repository = repository,
        queryHistory = queryHistory
    )

    // ─────────────────────────────────────────────
    // 公开接口
    // ─────────────────────────────────────────────

    /**
     * 启动后台预取调度器
     *
     * 建议在 Fragment.onViewCreated / Activity.onCreate 中调用一次。
     * scope 销毁时调度器自动停止，无内存泄漏。
     *
     * @param scope 生命周期绑定的协程作用域（lifecycleScope）
     */
    fun startPrefetch(scope: CoroutineScope) {
        prefetchScheduler.start(scope)
        Log.i(TAG, "预取调度器已启动 | 历史: ${queryHistory.getSummary()}")

        // v5.0：异步初始化本地数据库（首次启动时从 ThemeStockLibrary 迁移数据）
        scope.launch {
            dbManager.ensureInitialized()
        }
    }

    /**
     * ## 核心方法：构建注入 AI 的 system prompt
     *
     * ⚠️ **必须在 IO 线程（Dispatchers.IO）中调用**，内部有网络请求。
     *
     * @param userText 用户原始输入
     * @param baseSystemPrompt 基础系统提示词（由调用方传入，Fragment/Activity 各自维护）
     * @param onPreferenceLeaned 学到新偏好时的回调（在 IO 线程执行，UI 更新请 post 到主线程）
     * @return 完整的 system prompt 字符串（已注入实时数据 + 用户偏好）
     */
    fun buildSystemPrompt(
        userText: String,
        baseSystemPrompt: String,
        onPreferenceLeaned: ((summary: String) -> Unit)? = null
    ): String {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "🔍 开始处理: '${userText.take(60)}'")

        // ══════════════════════════════════════════════════
        // 阶段 0：学习用户偏好
        // ══════════════════════════════════════════════════
        val learnedPref = userPrefManager.learnFromMessage(userText)
        if (learnedPref) {
            Log.i(TAG, "📌 学到新偏好: ${userPrefManager.getPreferenceSummary().take(100)}")
            onPreferenceLeaned?.invoke(userPrefManager.getPreferenceSummary())
        }
        val prefPrompt = userPrefManager.buildPreferencePrompt()

        // ══════════════════════════════════════════════════
        // 阶段 1：主题/板块查询
        //   识别"商业航天"、"有色金属"、"AI算力"等行业/板块关键词
        //   ⚠️ 但如果用户输入中包含明确的**个股**名称或代码，
        //      则跳过板块查询，走阶段2的精确股票查询
        // ══════════════════════════════════════════════════
        val hasIndividualStock = hasStockNameOrCode(userText)
        if (!hasIndividualStock) {
            try {
                val themeResult = themeStockService.processThemeQuery(
                    userInput = userText,
                    topN = 20,
                    withBidAsk = false,  // 含"买手/卖手/低吸"时自动开启
                    prefManager = userPrefManager
                )
                if (themeResult != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val cacheHint = if (elapsed < 100) "⚡缓存命中" else "🌐实时获取"
                    Log.w(TAG, "✅ [主题/板块] ${themeResult.themeName} | ${themeResult.stockCount}只 | ${elapsed}ms $cacheHint")
                    Log.d(TAG, "═══════════════════════════════════════")

                    // 异步触发：下次用户再问相同主题时数据已在缓存
                    // （不等待，不阻塞当前请求）
                    prefetchScheduler.recordQuery(themeResult.stockCodes, themeResult.themeName)

                    return "$baseSystemPrompt$prefPrompt\n\n${themeResult.promptInjection}"
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ ThemeStockService 异常（跳过）: ${e.message}")
            }
        } else {
            Log.d(TAG, "🔍 检测到个股名称/代码，跳过板块查询，走精确查询")
        }

        // ══════════════════════════════════════════════════
        // 阶段 2：具体股票查询（原有 StockService）
        //   识别股票代码（600519）或股票名称（贵州茅台）
        // ══════════════════════════════════════════════════
        return try {
            val ctx = stockService.processUserInput(userText)
            val elapsed = System.currentTimeMillis() - startTime

            if (ctx.hasStockData && ctx.promptPrefix.isNotBlank()) {
                val cacheHint = if (elapsed < 50) "⚡缓存命中" else "🌐实时获取"
                Log.w(TAG, "✅ [具体股票] ${ctx.intent.stockCodes} | ${elapsed}ms $cacheHint")
                Log.d(TAG, "═══════════════════════════════════════")

                // 记录查询，供下次预取使用
                prefetchScheduler.recordQuery(ctx.intent.stockCodes)

                "$baseSystemPrompt$prefPrompt\n\n【实时行情数据】\n${ctx.promptPrefix}"
            } else {
                // ══════════════════════════════════════════════════
                // 阶段 3：通用问答（无实时数据，AI 基于训练知识回答）
                // ══════════════════════════════════════════════════
                Log.d(TAG, "ℹ️ [通用回答] 无实时数据 | ${elapsed}ms")
                Log.d(TAG, "═══════════════════════════════════════")
                "$baseSystemPrompt$prefPrompt"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 构建 prompt 失败: ${e.message}", e)
            Log.d(TAG, "═══════════════════════════════════════")
            "$baseSystemPrompt$prefPrompt"
        }
    }

    /**
     * 获取多源仓储（供外部需要直接操作数据时使用，如诊断、健康检查）
     */
    fun getRepository(): MultiSourceStockRepository = repository

    // ════════════════════════════════════════
    // 私有辅助方法
    // ════════════════════════════════════════

    /**
     * 检测用户输入是否包含已知个股名称或股票代码
     *
     * 当检测到个股时，阶段1的板块/主题查询应被跳过，
     * 直接进入阶段2的精确股票价格查询。
     *
     * 检测规则：
     * 1. 匹配 StockNameHandler 中的已知股票名称（茅台/宁德时代/比亚迪等）
     * 2. 匹配 sh/sz + 6位数字 格式的完整股票代码（sh600519 / sz000858 等）
     * 3. 匹配 6位纯数字 的股票代码（600519 / 000858 等）
     */
    private fun hasStockNameOrCode(userText: String): Boolean {
        // 规则1：常见 A 股名称关键词（与 StockNameHandler 内置表对齐）
        val commonNames = listOf(
            "茅台", "五粮液", "宁德时代", "比亚迪", "工商银行", "建设银行",
            "农业银行", "中国银行", "贵州茅台", "美的集团", "格力电器",
            "立讯精密", "海康威视", "中芯国际", "长江电力", "中兴通讯",
            "迈瑞医疗", "恒瑞医药", "药明康德", "科大讯飞", "紫金矿业",
            "万华化学", "生益科技", "沪电股份", "韦尔股份", "深信服",
            "广联达", "用友网络", "恒生电子", "赣锋锂业", "北方稀土",
            "中科三环", "山东黄金", "中国铝业", "华勤技术", "汇顶科技",
            "平安", "苹果"
        )
        if (commonNames.any { userText.contains(it) }) {
            Log.d(TAG, "hasStockNameOrCode: 匹配到个股名称 → skip theme query")
            return true
        }

        // 规则2：sh/sz + 6位数字的完整代码格式
        if (Regex("""[sS][hHzZ]\d{6}""").containsMatchIn(userText)) {
            Log.d(TAG, "hasStockNameOrCode: 匹配到股票代码（sh/sz格式） → skip theme query")
            return true
        }

        // 规则3：6位纯数字代码（可能是股票代码）
        if (Regex("""\b\d{6}\b""").containsMatchIn(userText)) {
            Log.d(TAG, "hasStockNameOrCode: 匹配到6位数字代码 → skip theme query")
            return true
        }

        return false
    }
}
