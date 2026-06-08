package com.chin.stockanalysis.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.news.NewsFactorManager
import com.chin.stockanalysis.skill.SkillOrchestrator
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
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
    private val repository: MultiSourceStockRepository,
    private val skillOrchestrator: SkillOrchestrator? = null
) {

    companion object {
        private const val TAG = "StockQueryEngine"

        /**
         * 工厂方法（推荐使用）：自动创建多源仓储并初始化引擎
         */
        fun create(context: Context, skillOrchestrator: SkillOrchestrator? = null): StockQueryEngine {
            val repo = StockDataSourceFactory.createDefaultRepository(context.applicationContext)
            return StockQueryEngine(context.applicationContext, repo, skillOrchestrator)
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

    /** v6.0 新增：新闻因子管理器（利好/利空新闻）*/
    val newsManager: NewsFactorManager = NewsFactorManager(context)

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
    suspend fun buildSystemPrompt(
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

                // 记录查询，供下次预取使用
                prefetchScheduler.recordQuery(ctx.intent.stockCodes)

                // v10.1: 記錄用戶搜索的股票到 StockDataCenter（含價格）
                ctx.intent.stockCodes.forEach { code ->
                    val name = ctx.promptPrefix.lines().firstOrNull { it.contains("名称") }
                        ?.substringAfter("名称:")?.trim() ?: code
                    val rt = ctx.realtimeData[code]
                    val price = rt?.price ?: -1.0
                    val changePct = rt?.changePercent ?: 0.0
                    com.chin.stockanalysis.stock.database.StockDataCenter.recordUserSearch(
                        stockCode = code,
                        stockName = name,
                        price = price,
                        changePct = changePct
                    )
                }

                // v10.1: 將即時行情持久化到 daily_snapshot 表（供模擬交易使用）
                saveRealtimeToSnapshot(ctx)

                // ========== v6.0 新增：个股 + 板块 + 新闻 综合分析 ==========
                val enrichedPrompt = buildEnrichedStockPrompt(ctx)
                // ========== v10.0: Skill 選股分析 ==========
                val skillPrompt = buildSkillPrompt(ctx, userText)
                Log.d(TAG, "═══════════════════════════════════════")
                "$baseSystemPrompt$prefPrompt$enrichedPrompt$skillPrompt"
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
        // 规则1：sh/sz + 6位数字的完整代码格式
        if (Regex("""[sS][hHzZ]\d{6}""").containsMatchIn(userText)) {
            Log.d(TAG, "hasStockNameOrCode: 匹配到股票代码（sh/sz格式） → skip theme query")
            return true
        }

        // 规则2：6位纯数字代码（可能是股票代码）
        if (Regex("""\b\d{6}\b""").containsMatchIn(userText)) {
            Log.d(TAG, "hasStockNameOrCode: 匹配到6位数字代码 → skip theme query")
            return true
        }

        // 规则3：任何纯中文词（2-8字）都可能是股票名称！
        // 这是一个股票分析软件！默认用户在问股票！
        val chineseCandidates = Regex("""[\u4e00-\u9fff]{2,8}""").findAll(userText)
            .map { it.value }
            .filter { 
                // 只排除极少数明显不是股票的词
                it !in listOf(
                    "今天", "今日", "最新", "现在", "行情", "价格", "股价", "走势", "分析", 
                    "多少钱", "实时", "最新价格", "怎么样", "如何", "什么", "为什么",
                    "可以", "能够", "应该", "可能", "大概", "也许", "或者", "还是",
                    "和", "与", "跟", "同", "及", "以及", "还有", "但是", "不过",
                    "因为", "所以", "因此", "于是", "那么", "这样", "那样", "这个",
                    "那个", "哪个", "哪些", "怎么", "如何"
                )
            }
            .toList()
        
        if (chineseCandidates.isNotEmpty()) {
            Log.d(TAG, "hasStockNameOrCode: 检测到中文候选词 ${chineseCandidates} → 尝试个股查询（这是股票软件！）")
            return true
        }

        return false
    }

    /**
     * v6.0 新增：构建个股 + 板块 + 新闻 综合分析的 prompt
     *
     * 当查询个股时，自动获取：
     * 1. 个股实时行情数据
     * 2. 该股票所属板块信息
     * 3. 板块实时热度数据（涨跌幅、换手率、主力资金流入等）
     * 4. 相关利好/利空新闻
     *
     * 然后注入到 AI prompt 中，让 AI 自动综合分析。
     */
    private suspend fun buildEnrichedStockPrompt(ctx: StockContext): String {
        val promptBuilder = StringBuilder()

        // ========== 1. 个股实时行情数据 ==========
        promptBuilder.append("\n\n【个股实时行情数据】\n")
        promptBuilder.append(ctx.promptPrefix)

        val firstStockCode = ctx.intent.stockCodes.firstOrNull()
        if (firstStockCode == null) {
            return promptBuilder.toString()
        }

        try {

            // ========== 2. 获取该股票所属板块 ==========
            val sectors = dbManager.db.sectorStockDao().getSectorNamesByStockCode(firstStockCode)
            if (sectors.isNotEmpty()) {
                promptBuilder.append("\n【所属板块】\n")
                sectors.forEachIndexed { i, sector ->
                    promptBuilder.append("${i + 1}. ${sector}\n")
                }

                // ========== 3. 获取板块实时热度数据 ==========
                val hotSectors = mutableListOf<EastMoneyHotSectorSource.HotSector>()
                // 从 EastMoneyHotSectorSource 中查找匹配的板块
                val industrySectors = EastMoneyHotSectorSource.industrySectors
                val conceptSectors = EastMoneyHotSectorSource.conceptSectors

                sectors.forEach { sectorName ->
                    // 模糊匹配板块名称
                    val matched = (industrySectors + conceptSectors)
                        .firstOrNull { hs ->
                            hs.name.contains(sectorName) || sectorName.contains(hs.name)
                        }
                    if (matched != null) {
                        hotSectors.add(matched)
                    }
                }

                if (hotSectors.isNotEmpty()) {
                    promptBuilder.append("\n【板块实时热度】\n")
                    hotSectors.take(3).forEachIndexed { i, hs ->
                        val typeLabel = if (hs.sectorType == 2) "行业" else "概念"
                        promptBuilder.append("${i + 1}. [${typeLabel}] ${hs.name}\n")
                        promptBuilder.append("   涨跌幅: ${String.format("%.2f", hs.changePercent)}% | 换手率: ${String.format("%.2f", hs.turnoverRate)}%\n")
                        promptBuilder.append("   主力资金净流入: ${String.format("%.2f", hs.mainNetInflow)}亿元 | 热度评分: ${String.format("%.2f", hs.hotScore)}\n")
                        if (hs.top1StockName.isNotEmpty()) {
                            promptBuilder.append("   领涨股: ${hs.top1StockName} (${hs.top1StockCode}) ${String.format("%.2f", hs.top1ChangePercent)}%\n")
                        }
                    }
                }
            }

            // ========== 4. 获取即時公告、新聞、資金流向（從權威來源） ==========
            promptBuilder.append("\n【实时市场数据】(以下数据为系统实时从权威来源获取，非训练数据)")
            val stockName = ctx.promptPrefix.lines().firstOrNull { it.contains("名称") }
                ?.substringAfter("名称:")?.trim() ?: firstStockCode
            try {
                val newsFetcher = com.chin.stockanalysis.stock.data.sources.StockNewsFetcher()
                val newsContext = newsFetcher.fetchStockContext(firstStockCode, stockName)
                promptBuilder.append(newsContext.toPromptInjection())
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ StockNewsFetcher 失败: ${e.message}")
                // Fallback: 使用本地新聞數據庫
                try {
                    val relatedNews = mutableListOf<String>()
                    val newsByCode = newsManager.searchByStockCode(firstStockCode, limit = 5)
                    val newsByName = if (stockName.isNotEmpty()) {
                        newsManager.searchByCompany(stockName, limit = 5)
                    } else emptyList()
                    val allNews = (newsByCode + newsByName).distinctBy { it.title }.take(8)
                    if (allNews.isNotEmpty()) {
                        promptBuilder.append("\n【相关利好/利空新闻】(本地缓存)\n")
                        allNews.forEach { news ->
                            val emoji = when { news.sentiment > 0 -> "📈利好"; news.sentiment < 0 -> "📉利空"; else -> "📰中性" }
                            promptBuilder.append("$emoji【${news.title}】${news.content.take(50)}... (${news.newsDate})\n")
                        }
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "⚠️ 本地新闻也失败: ${e2.message}")
                }
            }

            // ========== 5. 添加 AI 分析指令 ==========
            promptBuilder.append("\n【综合分析指令】")
            promptBuilder.append("\n请基于以上全部实时数据进行综合分析。所有价格、走势、公告、新闻均已由系统实时获取。")
            promptBuilder.append("\n禁止使用训练数据中的旧信息。如果某项数据未获取到，直接说明「该数据暂未获取到」。")
            promptBuilder.append("\n1. 个股当前走势分析（技术面：换手率、量价关系、主力资金动向）")
            promptBuilder.append("\n2. 板块前景分析（当前是低位/高位？整体行情如何？）")
            promptBuilder.append("\n3. 板块溢价情况和活跃度评估")
            promptBuilder.append("\n4. 业绩预期和上下游供应链分析")
            promptBuilder.append("\n5. 相关新闻和公告对股价的潜在影响")
            promptBuilder.append("\n6. 最后给出一个综合的投资参考评估")
            promptBuilder.append("\n\n请将以上分析以清晰的标题「综合分析」开头输出。")
            promptBuilder.append("\n⚠️ 注意：不要给出具体的买卖建议，只提供分析参考。投资有风险，入市需谨慎。\n")

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 构建综合分析 prompt 失败: ${e.message}")
        }

        return promptBuilder.toString()
    }

    /**
     * v10.0: 构建 Skill 选股分析 prompt
     *
     * 当查询个股且有 SkillOrchestrator 时，执行所有匹配的 Skill，
     * 并将结果注入到 AI prompt 中。
     */
    private suspend fun buildSkillPrompt(ctx: StockContext, userInput: String = ""): String {
        val orchestrator = skillOrchestrator ?: run {
            Log.d(TAG, "🔧 SkillOrchestrator 未初始化，跳過 Skill 分析")
            return ""
        }
        val firstStockCode = ctx.intent.stockCodes.firstOrNull() ?: run {
            Log.d(TAG, "🔧 buildSkillPrompt: 無股票代碼，跳過")
            return ""
        }
        val stockName = ctx.promptPrefix.lines().firstOrNull { it.contains("名称") }
            ?.substringAfter("名称:")?.trim() ?: firstStockCode

        Log.i(TAG, "🎯 buildSkillPrompt: 開始 Skill 分析 for $stockName ($firstStockCode)")

        // 獲取板塊信息
        val sectors = try {
            dbManager.db.sectorStockDao().getSectorNamesByStockCode(firstStockCode)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 獲取板塊信息失敗: ${e.message}")
            emptyList()
        }
        Log.d(TAG, "   板塊: ${sectors.ifEmpty { listOf("無") }.joinToString(", ")}")

        return try {
            val results = orchestrator.runSkills(
                stockCode = firstStockCode,
                stockName = stockName,
                userInput = userInput,
                sectors = sectors
            )
            if (results.isEmpty()) {
                Log.d(TAG, "📭 Skill 執行結果為空")
                ""
            } else {
                val prompt = orchestrator.formatForPrompt(results)
                Log.i(TAG, "📝 Skill prompt 已生成: ${prompt.length}字, ${results.size}個 Skill")
                prompt
            }
        } catch (e: Exception) {
            Log.w(TAG, "❌ Skill 分析失败: ${e.message}")
            ""
        }
    }

    /**
     * v10.1: 將即時行情數據持久化到 daily_snapshot 表
     *
     * 用戶在 AI 對話中查詢個股時，StockService 從網路即時拉取了行情數據，
     * 這些數據在此寫入 daily_snapshot 表，供模擬交易引擎使用。
     *
     * 如果當日數據已存在則跳過（避免重複寫入）。
     */
    private suspend fun saveRealtimeToSnapshot(ctx: StockContext) {
        if (ctx.realtimeData.isEmpty()) return
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        try {
            val db = dbManager.db
            val existingCodes = try {
                db.dailySnapshotDao().getByDate(today).map { it.code }.toSet()
            } catch (_: Exception) { emptySet() }

            val entities = ctx.realtimeData.values
                .filter { it.code !in existingCodes }
                .map { stock ->
                    com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity(
                        code = stock.code,
                        name = stock.name,
                        date = today,
                        open = stock.open,
                        close = stock.price,
                        high = stock.high,
                        low = stock.low,
                        volume = stock.volume,
                        amount = stock.amount,
                        changePct = stock.changePercent,
                        turnoverRate = 0.0,
                        mainNetInflow = 0.0
                    )
                }
            if (entities.isNotEmpty()) {
                db.dailySnapshotDao().insertAll(entities)
                Log.i(TAG, "💾 已寫入 ${entities.size} 條即時行情到 daily_snapshot ($today)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 寫入 daily_snapshot 失敗: ${e.message}")
        }
    }
}
