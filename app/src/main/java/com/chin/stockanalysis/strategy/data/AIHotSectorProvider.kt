package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.ui.Message
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## AI 热门板块查询工具 v1.0
 *
 * 通过 DeepSeek/豆包 AI 直接查询当前年度、月度、周度、昨日热门板块，
 * 替代原来的 ETF 涨跌 + 东方财富 compositeScore 判断方式。
 *
 * AI 返回结构化 JSON，包含：
 * - annual_sectors: 年度热门板块（长期趋势）
 * - monthly_sectors: 月度热门板块（中期趋势）
 * - weekly_sectors: 周度热门板块（短期热点）
 * - yesterday_sectors: 上一个交易日热门板块（最新资金流向）
 *
 * 缓存策略：每 3 小时刷新一次（避免频繁调用 API）
 */
object AIHotSectorProvider {

    private const val TAG = "AIHotSectorProvider"
    private const val PREFS_NAME = "ai_hot_sectors_prefs"
    private const val KEY_CACHED_SECTORS = "cached_sectors_json"
    private const val KEY_LAST_FETCH_TIME = "last_fetch_time"
    private const val CACHE_DURATION_MS = 3 * 60 * 60 * 1000L  // 3 小时

    data class HotSectorResult(
        val annualSectors: List<String>,
        val monthlySectors: List<String>,
        val weeklySectors: List<String>,
        val yesterdaySectors: List<String>,
        val fetchTime: Long = System.currentTimeMillis()
    ) {
        /** 合并去重后的所有热门板块 */
        val allSectors: List<String> get() =
            (annualSectors + monthlySectors + weeklySectors + yesterdaySectors).distinct()
    }

    /**
     * 取得热门板块（优先从缓存，过期则 AI 查询）
     */
    suspend fun getHotSectors(context: Context): HotSectorResult = withContext(Dispatchers.IO) {
        // 1. 尝试缓存
        val cached = loadCache(context)
        if (cached != null) {
            Log.i(TAG, "📦 从缓存读取热门板块: ${cached.allSectors.size} 个")
            return@withContext cached
        }

        // 2. AI 查询
        Log.i(TAG, "🤖 开始 AI 查询热门板块...")
        val result = try {
            fetchFromAI(context)
        } catch (e: Exception) {
            Log.w(TAG, "AI 查询失败，使用备用列表: ${e.message}")
            getDefaultHotSectors(context)
        }

        // 3. 写入缓存
        saveCache(context, result)
        result
    }

    /**
     * 强制刷新（不读缓存）
     */
    suspend fun forceRefresh(context: Context): HotSectorResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔄 强制刷新 AI 热门板块...")
        val result = try {
            fetchFromAI(context)
        } catch (e: Exception) {
            Log.w(TAG, "AI 查询失败，使用备用列表: ${e.message}")
            getDefaultHotSectors(context)
        }
        saveCache(context, result)
        result
    }

    /** 检查缓存是否过期 */
    fun isCacheExpired(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetchTime = prefs.getLong(KEY_LAST_FETCH_TIME, 0L)
        return System.currentTimeMillis() - lastFetchTime > CACHE_DURATION_MS
    }

    // ════════════════════════════════════════
    // AI 查询逻辑
    // ════════════════════════════════════════

    /**
     * 并行调用多个 AI Provider 查询热门板块
     *
     * 将 4 个时间维度拆分到最多 4 个 Provider 并行查询：
     *   - Provider 0 → 年度
     *   - Provider 1 → 月度
     *   - Provider 2 → 周度
     *   - Provider 3 → 昨日
     * 如果可用 Provider < 4，则用可用的 Provider 平分维度。
     */
    /** Task 辅助 data class */
    private data class Task(val dimension: String, val userMessage: String, val provider: ApiProvider, val providerName: String)

    private suspend fun fetchFromAI(context: Context): HotSectorResult = coroutineScope {
        val slots = AiProviderPool.acquireAllHealthy(context)
        if (slots.isEmpty()) throw IllegalStateException("无可用 AI Provider")

        val dimensions = listOf("annual", "monthly", "weekly", "yesterday")
        Log.i(TAG, "🚀 并行 AI 查询: ${slots.size} 个 Provider × ${dimensions.size} 个维度")

        // 为每个维度建立任务，轮流分配给可用 Provider
        val tasks = dimensions.mapIndexed { i, dim ->
            val slot = slots[i % slots.size]
            Task(
                dimension = dim,
                userMessage = buildString {
                    appendLine("请列出当前A股市场在以下时间周期的热门板块（最多15个）：")
                    appendLine("- ${when(dim) {
                        "annual" -> "年度：持续受政策/资金关注的长期热门板块"
                        "monthly" -> "月度：近一个月资金流入明显的中期热门板块"
                        "weekly" -> "周度：本周最活跃的短期热点板块"
                        else -> "昨日：上一个交易日表现最好的板块"
                    }}")
                    appendLine()
                    appendLine("输出严格JSON格式（不要markdown代码块）：")
                    appendLine("""{"${dim}_sectors":[]}""")
                },
                provider = slot.provider,
                providerName = slot.configName
            )
        }

        // 并行执行所有任务
        val results = tasks.map { task ->
            async(Dispatchers.IO) { querySingleProvider(task) }
        }.awaitAll()

        // 合并结果
        val annualSectors = mutableListOf<String>()
        val monthlySectors = mutableListOf<String>()
        val weeklySectors = mutableListOf<String>()
        val yesterdaySectors = mutableListOf<String>()

        results.forEachIndexed { i, sectors ->
            when (tasks[i].dimension) {
                "annual" -> annualSectors.addAll(sectors)
                "monthly" -> monthlySectors.addAll(sectors)
                "weekly" -> weeklySectors.addAll(sectors)
                "yesterday" -> yesterdaySectors.addAll(sectors)
            }
        }

        Log.i(TAG, "✅ 并行查询完成: 年度${annualSectors.size} 月度${monthlySectors.size} 周度${weeklySectors.size} 昨日${yesterdaySectors.size}")
        HotSectorResult(
            annualSectors = annualSectors.distinct(),
            monthlySectors = monthlySectors.distinct(),
            weeklySectors = weeklySectors.distinct(),
            yesterdaySectors = yesterdaySectors.distinct()
        )
    }

    /** 对单个 Provider 执行查询，返回板块列表 */
    private suspend fun querySingleProvider(task: Task): List<String> {
        val (dimension, userMessage, provider, providerName) = task
        val deferred = CompletableDeferred<String>()

        provider.sendMessageStream(
            messages = listOf(Message(content = userMessage, isUser = true)),
            systemPrompt = "你是一位专业的A股市场分析师，精通中国A股市场的板块轮动和资金流向规律。你的任务是根据你的知识库，列出指定的热门A股板块。",
            onSuccess = { /* 串流 chunk 不处理，等待 onComplete 的完整内容 */ },
            onComplete = { fullContent ->
                deferred.complete(fullContent)
            },
            onError = { errorMsg ->
                Log.e(TAG, "❌ $providerName 查询[$dimension]失败: $errorMsg")
                deferred.complete("")
            }
        )

        val rawResponse = withTimeoutOrNull(30_000) { deferred.await() } ?: ""
        if (rawResponse.isBlank()) {
            Log.w(TAG, "⏰ $providerName 查询[$dimension]超时或返回为空")
            return emptyList()
        }

        Log.i(TAG, "  ✅ $providerName → $dimension: ${rawResponse.length} 字")
        return parseSingleDimensionResponse(rawResponse, "${dimension}_sectors")
    }

    /** 解析单个维度的回应 */
    private fun parseSingleDimensionResponse(raw: String, key: String): List<String> {
        var json = raw.trim()
        val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = jsonBlockRegex.find(json)
        if (match != null) json = match.groupValues[1].trim()
        val braceStart = json.indexOf('{')
        val braceEnd = json.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) json = json.substring(braceStart, braceEnd + 1)

        return try {
            val obj = JSONObject(json)
            obj.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        } catch (e: Exception) {
            // 正则 fallback
            val regex = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
            val m = regex.find(json) ?: return emptyList()
            Regex("\"([^\"]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
        }
    }

    // ════════════════════════════════════════
    // JSON 解析
    // ════════════════════════════════════════

    private fun parseAIResponse(raw: String): HotSectorResult {
        var json = raw.trim()

        // 尝试提取 JSON（处理 Markdown 代码块包裹的情况）
        val jsonBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = jsonBlockRegex.find(json)
        if (match != null) {
            json = match.groupValues[1].trim()
        }

        // 尝试匹配最外层的 {...}
        val braceStart = json.indexOf('{')
        val braceEnd = json.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) {
            json = json.substring(braceStart, braceEnd + 1)
        }

        try {
            val obj = JSONObject(json)
            return HotSectorResult(
                annualSectors = obj.optJSONArray("annual_sectors")?.toStringList() ?: emptyList(),
                monthlySectors = obj.optJSONArray("monthly_sectors")?.toStringList() ?: emptyList(),
                weeklySectors = obj.optJSONArray("weekly_sectors")?.toStringList() ?: emptyList(),
                yesterdaySectors = obj.optJSONArray("yesterday_sectors")?.toStringList() ?: emptyList()
            )
        } catch (e: Exception) {
            Log.w(TAG, "JSON 解析失败: ${e.message}，原始: ${json.take(200)}")
            // 备用：尝试正则提取
            return regexFallbackParse(json)
        }
    }

    private fun regexFallbackParse(text: String): HotSectorResult {
        fun extractSectors(key: String): List<String> {
            val regex = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
            val m = regex.find(text) ?: return emptyList()
            val content = m.groupValues[1]
            return Regex("\"([^\"]+)\"").findAll(content).map { it.groupValues[1] }.toList()
        }
        return HotSectorResult(
            annualSectors = extractSectors("annual_sectors"),
            monthlySectors = extractSectors("monthly_sectors"),
            weeklySectors = extractSectors("weekly_sectors"),
            yesterdaySectors = extractSectors("yesterday_sectors")
        )
    }

    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).map { getString(it) }
    }

    // ════════════════════════════════════════
    // 缓存
    // ════════════════════════════════════════

    private fun loadCache(context: Context): HotSectorResult? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CACHED_SECTORS, null) ?: return null
        val lastFetchTime = prefs.getLong(KEY_LAST_FETCH_TIME, 0L)

        if (System.currentTimeMillis() - lastFetchTime > CACHE_DURATION_MS) {
            return null
        }

        return try {
            val obj = JSONObject(json)
            HotSectorResult(
                annualSectors = obj.optJSONArray("annual_sectors")?.toStringList() ?: emptyList(),
                monthlySectors = obj.optJSONArray("monthly_sectors")?.toStringList() ?: emptyList(),
                weeklySectors = obj.optJSONArray("weekly_sectors")?.toStringList() ?: emptyList(),
                yesterdaySectors = obj.optJSONArray("yesterday_sectors")?.toStringList() ?: emptyList(),
                fetchTime = lastFetchTime
            )
        } catch (e: Exception) {
            Log.w(TAG, "缓存解析失败: ${e.message}")
            null
        }
    }

    private fun saveCache(context: Context, result: HotSectorResult) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("annual_sectors", JSONArray(result.annualSectors))
            put("monthly_sectors", JSONArray(result.monthlySectors))
            put("weekly_sectors", JSONArray(result.weeklySectors))
            put("yesterday_sectors", JSONArray(result.yesterdaySectors))
        }.toString()
        prefs.edit().apply {
            putString(KEY_CACHED_SECTORS, json)
            putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
            apply()
        }
        Log.i(TAG, "💾 热门板块快取已保存: ${result.allSectors.size} 个")
    }

    // ════════════════════════════════════════
    // 备用列表（AI 查询失败时使用）
    // ════════════════════════════════════════

    /**
     * 备用列表（AI 查询失败时使用）
     * 动态从数据库 sector_daily_record 表获取最近热门板块，不使用硬编码列表
     */
    suspend fun getDefaultHotSectors(context: Context): HotSectorResult {
        return try {
            // 从数据库获取最近记录的板块数据
            val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(context)
            val recentRecords = db.sectorDailyRecordDao().getRecentDays(7)

            if (recentRecords.isEmpty()) {
                // DB 也没有数据时，从东方财富实时数据获取
                getFromEastMoneyRealtime()
            } else {
                // 按涨跌幅排序，分组为不同周期
                val sorted = recentRecords.sortedByDescending { it.changePct }
                val today = sorted.filter { it.date == sorted.first().date }
                    .take(10).map { it.sectorName }
                val weekly = sorted.distinctBy { it.sectorName }
                    .take(10).map { it.sectorName }
                val monthly = sorted.distinctBy { it.sectorName }
                    .takeLast(10).reversed().map { it.sectorName }

                HotSectorResult(
                    annualSectors = monthly,   // 年度用月度数据替代
                    monthlySectors = monthly,
                    weeklySectors = weekly,
                    yesterdaySectors = today
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "动态获取 fallback 板块失败，使用东方财富实时数据: ${e.message}")
            getFromEastMoneyRealtime()
        }
    }

    /** 从东方财富实时板块数据获取 */
    private fun getFromEastMoneyRealtime(): HotSectorResult {
        val conceptSectors = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.conceptSectors
        val industrySectors = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.industrySectors
        val all = conceptSectors + industrySectors
        val sorted = all.sortedByDescending { it.changePercent }

        val top10 = sorted.take(10).map { it.name }
        val next10 = sorted.drop(10).take(10).map { it.name }

        return HotSectorResult(
            annualSectors = next10,
            monthlySectors = next10,
            weeklySectors = top10,
            yesterdaySectors = top10
        )
    }
}