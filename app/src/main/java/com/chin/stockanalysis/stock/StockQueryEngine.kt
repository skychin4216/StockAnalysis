package com.chin.stockanalysis.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.MultiSourceStockRepository
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.prefetch.StockPrefetchScheduler
import com.chin.stockanalysis.stock.prefetch.UserQueryHistory
import com.chin.stockanalysis.stock.theme.ThemeStockService
import com.chin.stockanalysis.stock.theme.UserPreferenceManager
import kotlinx.coroutines.CoroutineScope

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
        // 阶段 1：主题/板块查询（优先级最高）
        //   识别"商业航天"、"有色金属"、"AI算力"等
        // ══════════════════════════════════════════════════
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
}
