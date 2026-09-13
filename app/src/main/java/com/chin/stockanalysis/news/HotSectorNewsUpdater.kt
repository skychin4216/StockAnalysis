package com.chin.stockanalysis.news

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.ai.AiProviderPool
import java.net.URLEncoder
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 热门板块新闻自动更新引擎
 *
 * App 启动时在后台运行一次：
 * 1. 从 sector_period_summary 或 sector_daily_record 动态获取 Top 5 热门板块
 * 2. 用豆包 AI 搜索相关板块最新新闻
 * 3. 提取结构化新闻 → 写入 news_factors 表
 */
class HotSectorNewsUpdater(private val context: Context) {

    companion object {
        private const val TAG = "HotSectorNewsUpdater"
        private const val PREFS_NAME = "hot_sector_news_cache"
        private const val CACHE_TTL_MINUTES = 60  // 新闻缓存有效期（分钟）
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 动态板块优先列表（从 DB 读取，无数据时为空） */
        private var dynamicPriorityCache: List<String>? = null
        private var dynamicPriorityTimestamp: Long = 0
        private const val DYNAMIC_CACHE_TTL = 4 * 3600 * 1000L  // 4小时刷新

        /**
         * 全局 Mutex：多个 Fragment/Engine 同时调用时，
         * 只有第一个执行实际拉取，其余挂起等待，完成后共享结果。
         */
        private val globalMutex = kotlinx.coroutines.sync.Mutex()

        /**
         * 便捷静态方法：全局只启动一次 async，后续调用返回同一个 Deferred。
         */
        private var pendingJob: Deferred<Unit>? = null

        @Synchronized
        fun ensureFreshGlobal(scope: CoroutineScope, context: Context, forceRefresh: Boolean = true): Deferred<Unit> {
            Log.i(TAG, "ensureFreshGlobal called, forceRefresh=$forceRefresh, pendingJob=${pendingJob}, isActive=${pendingJob?.isActive}")
            // 如果已有正在执行或已完成的 job，直接返回
            if (pendingJob != null && pendingJob!!.isActive) {
                Log.i(TAG, "⏭️ 新闻因子拉取已在进行中，共享同一个 job")
                return pendingJob!!
            }
            val job = HotSectorNewsUpdater(context).ensureFreshAsync(scope, forceRefresh, ignoreQuantPause = true)
            pendingJob = job
            Log.i(TAG, "🆕 创建新闻因子拉取 job=${job}")
            // 完成后清理引用
            job.invokeOnCompletion {
                Log.i(TAG, "✅ 新闻因子 job 完成，异常=${it?.message}")
                pendingJob = null
            }
            return job
        }
    }

    private val db = StockDatabase.getInstance(context)

    /** 是否已执行过更新（本次 App 生命周期内只跑一次） */
    @Volatile
    private var hasRun = false

    /**
     * App 启动或用户手动刷新时调用。
     * 全局 Mutex 保证多个调用方共享同一次拉取：
     * - 第一个调用：执行实际拉取
     * - 后续调用：挂起等待，完成后直接返回（不重复拉取）
     *
     * @param forceRefresh true=忽略缓存强制重新拉取
     */
    suspend fun updateIfNeeded(forceRefresh: Boolean = false, ignoreQuantPause: Boolean = false) {
        // 快速路径：非强制且已完成，直接返回
        if (!forceRefresh && hasRun) {
            Log.i(TAG, "⏭️ 新闻因子已更新过，跳过")
            return
        }

        Log.i(TAG, "🔒 请求 globalMutex...")
        globalMutex.lock()
        Log.i(TAG, "🔒 获得 globalMutex")
        try {
            // 双重检查：获得锁后再判断（可能已被其他线程完成）
                if (!forceRefresh && hasRun) {
                    Log.i(TAG, "⏭️ 新闻因子已被其他调用更新，共享结果")
                    return
                }
                hasRun = true

                val latestDate = db.newsFactorDao().getLatestNewsDate()
                val today = LocalDate.now().format(DATE_FMT)
                val existingCount = if (latestDate == today) db.newsFactorDao().countActive() else 0

                if (!forceRefresh && latestDate == today && existingCount > 5) {
                    Log.i(TAG, "✅ 今日已有 $existingCount 条新闻，跳过更新")
                    return
                }

                Log.i(TAG, "━━━ 开始检查热门板块新闻（${CACHE_TTL_MINUTES}分钟内有缓存则跳过） ━━━")

                // 1. 获取 Top 5 热门板块
                val topSectors = getTopHotSectors()
                Log.i(TAG, "Top 5 热门板块: ${topSectors.joinToString()}")

                // 2. 选择动态热门板块
                val targetSectors = selectPrioritySectors(topSectors)
                Log.i(TAG, "🎯 目标板块: ${targetSectors.joinToString()}")

                // 3. 并行搜索所有板块新闻
                val allNews = mutableListOf<NewsFactorEntity>()
                coroutineScope {
                    val sectorJobs = targetSectors.map { sector ->
                        async(Dispatchers.IO) {
                            Log.i(TAG, "🔍 [$sector] 并行开始搜索...")
                            val sectorStart = System.currentTimeMillis()
                            val news = searchSectorNews(sector, ignoreQuantPause = ignoreQuantPause)
                            Log.i(TAG, "🔍 [$sector] 搜索完成，耗时=${System.currentTimeMillis()-sectorStart}ms, 获取=${news.size}条")
                            news
                        }
                    }
                    sectorJobs.awaitAll().forEach { allNews.addAll(it) }
                }

                // 4. 写入数据库
                if (allNews.isNotEmpty()) {
                    db.newsFactorDao().insertAll(allNews)
                    Log.i(TAG, "✅ 已保存 ${allNews.size} 条热点新闻")
                } else {
                    Log.i(TAG, "⚠️ 未拉取到新新闻")
                }
        } catch (e: Exception) {
            Log.w(TAG, "后台新闻更新失败: ${e.message}")
        } finally {
            globalMutex.unlock()
            Log.i(TAG, "🔓 释放 globalMutex")
        }
    }

    /**
     * 非阻塞版本：返回一个 Deferred，调用方可：
     * 1. 先做其他任务
     * 2. 到需要新闻因子时 `.await()`
     *
     * 典型用法：
     * ```
     * val newsJob = HotSectorNewsUpdater.ensureFreshAsync(context)
     * doOtherWork()  // 并行执行不需要新闻的任务
     * newsJob.await() // 到需要新闻因子时才等待
     * ```
     */
    fun ensureFreshAsync(scope: CoroutineScope, forceRefresh: Boolean = true, ignoreQuantPause: Boolean = false): Deferred<Unit> {
        return scope.async(Dispatchers.IO) {
            updateIfNeeded(forceRefresh, ignoreQuantPause)
        }
    }

    /** 从 sector_period_summary 获取近期主要板块，数据不足时用 sector_daily_record 降级 */
    private suspend fun getTopHotSectors(): List<String> {
        return try {
            // 优先用 sector_period_summary（周/月聚合）
            val tracker = com.chin.stockanalysis.strategy.backtest.SectorPeriodTracker(context)
            val weeklySectors = tracker.getCurrentWeekTopSectors(15)
            if (weeklySectors.isNotEmpty()) {
                weeklySectors
            } else {
                // 降级到 sector_daily_record
                Log.i(TAG, "sector_period_summary 为空，降级到 sector_daily_record")
                val hotStats = db.sectorDailyRecordDao().getTopHotSectors(15)
                if (hotStats.isNotEmpty()) {
                    hotStats.map { it.sector_code }
                } else {
                    Log.i(TAG, "板块数据为空，无默认板块")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取板块记录失败: ${e.message}")
            emptyList()
        }
    }

    /** 直接使用动态 hot 板块，不再硬编码优先级过滤 */
    private fun selectPrioritySectors(hotSectors: List<String>): List<String> {
        Log.i(TAG, "动态板块: ${hotSectors.joinToString()}")
        return hotSectors.take(5)
    }

    /** 三级优先级搜索指定板块最新新闻（搜索与解析分离） */
    private suspend fun searchSectorNews(sector: String, ignoreQuantPause: Boolean = false): List<NewsFactorEntity> {
        // 量化选股运行时暂停新闻搜索（除非是量化主动调用）
        if (!ignoreQuantPause && com.chin.stockanalysis.stock.database.AppBackgroundRunner.isQuantRunning) {
            Log.i(TAG, "⏸️ 量化选股运行中，跳过新闻搜索: $sector")
            return emptyList()
        }

        // 缓存检查
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetchKey = "last_fetch_$sector"
        val lastFetch = prefs.getLong(lastFetchKey, 0L)
        if (System.currentTimeMillis() - lastFetch < 30 * 60 * 1000) {
            val cached = loadCachedNews(sector)
            if (cached.isNotEmpty()) return cached
        }

        val today = LocalDate.now().format(DATE_FMT)

        // === 第一步：搜索新闻 ===
        val rawNews = fetchNewsFromEastMoney(sector, today)
            .ifEmpty { fetchNewsFromDuckDuckGo(sector, today) }
            .ifEmpty { fetchNewsWithTavily(sector, today) }

        // === 第二步：AI 解析 ===
        if (rawNews.isNotEmpty()) {
            val parsed = parseNewsWithAi(rawNews, sector, today)
            if (parsed.isNotEmpty()) {
                prefs.edit().putLong(lastFetchKey, System.currentTimeMillis()).apply()
                return parsed
            }
            // AI 解析失败，继续到 fallback
        }

        // === Fallback 1：AI provider 直接搜索+分析 ===
        Log.w(TAG, "⚠️ [$sector] 分离搜索解析失败，fallback 到 AI provider")
        val aiNews = searchWithAiProvider(sector, today)
        if (aiNews.isNotEmpty()) {
            prefs.edit().putLong(lastFetchKey, System.currentTimeMillis()).apply()
            return aiNews
        }

        // === Fallback 2：关键词匹配 ===
        if (rawNews.isNotEmpty()) {
            Log.w(TAG, "⚠️ [$sector] AI provider 也失败，fallback 到关键词匹配")
            val keywordParsed = parseWithKeywords(rawNews, sector, today)
            if (keywordParsed.isNotEmpty()) {
                prefs.edit().putLong(lastFetchKey, System.currentTimeMillis()).apply()
                return keywordParsed
            }
        }

        Log.w(TAG, "⚠️ [$sector] 所有新闻源均失败")
        return emptyList()
    }

    private fun parseNewsResponse(response: String, sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val start = response.indexOf('{')
            val end = response.lastIndexOf('}')
            if (start == -1 || end == -1) return emptyList()
            val obj = JSONObject(response.substring(start, end + 1))
            val arr = obj.optJSONArray("news") ?: return emptyList()

            val results = mutableListOf<NewsFactorEntity>()
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                results.add(NewsFactorEntity(
                    stockCode = n.optString("stock_code", ""),
                    companyName = n.optString("company", ""),
                    title = n.optString("title", ""),
                    content = n.optString("content", ""),
                    newsDate = today,
                    sentiment = n.optInt("sentiment", 1),
                    impactStrength = n.optInt("strength", 50).coerceIn(0, 100),
                    source = "ai_search",
                    sourceUrl = "",
                    tags = "${n.optString("tags", "")},$sector",
                    sector = sector,
                    createdAt = System.currentTimeMillis(),
                    isActive = true
                ))
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "解析新闻失败: ${e.message}")
            emptyList()
        }
    }

    /** 从数据库加载今日该板块的缓存新闻 */
    private suspend fun loadCachedNews(sector: String): List<NewsFactorEntity> {
        return try {
            val today = LocalDate.now().format(DATE_FMT)
            db.newsFactorDao().getActiveBySector(sector, limit = 20)
                .filter { it.newsDate == today }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 使用 AI 解析新闻列表，返回结构化结果 */
    private suspend fun parseNewsWithAi(newsList: List<NewsFactorEntity>, sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val titles = newsList.joinToString("\n") { "- ${it.title}" }
            val prompt = """
你是一位 A 股财经分析师。请分析以下关于「$sector」板块的新闻，返回 JSON 格式：

新闻列表：
$titles

请返回：
{
  "news": [
    {
      "stock_code": "",
      "company": "",
      "title": "新闻标题",
      "content": "摘要",
      "sentiment": 1,
      "strength": 60,
      "tags": "$sector"
    }
  ]
}
"sentiment": 1=利好, -1=利空, 0=中性
"strength": 0-100 影响力度
只返回 JSON，不要其他文字。
""".trimIndent()

            val slot = com.chin.stockanalysis.ai.AiProviderPool.acquire(
                context = context,
                callerTag = "HotSectorNewsUpdater.parseNews",
                timeoutMs = 5_000L
            ) ?: return emptyList()

            try {
                val response = withTimeoutOrNull(5000) {
                    withContext(Dispatchers.IO) {
                        suspendCancellableCoroutine<String> { cont ->
                            slot.provider.sendMessageStream(
                                messages = emptyList(),
                                systemPrompt = prompt,
                                onSuccess = {},
                                onComplete = { full -> cont.resumeWith(Result.success(full)) },
                                onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
                            )
                        }
                    }
                }

                if (response == null) {
                    Log.w(TAG, "⏱️ [$sector] AI 解析新闻超时")
                    return emptyList()
                }
                parseNewsResponse(response, sector, today)
            } finally {
                com.chin.stockanalysis.ai.AiProviderPool.release(slot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI 解析新闻失败 [$sector]: ${e.message}")
            emptyList()
        }
    }

    /** 关键词匹配解析（AI 解析失败时的 fallback） */
    private fun parseWithKeywords(newsList: List<NewsFactorEntity>, sector: String, today: String): List<NewsFactorEntity> {
        val positive = setOf("涨", "涨停", "利好", "订单", "增长", "突破", "超预期", "业绩", "盈利", "创新高", "爆发", "强势")
        val negative = setOf("跌", "跌停", "利空", "亏损", "下滑", "减持", "召回", "监管", "调查", "暴跌", "疲软")
        return newsList.map { news ->
            val titleLower = news.title
            val posCount = positive.count { titleLower.contains(it) }
            val negCount = negative.count { titleLower.contains(it) }
            val sentiment = when {
                posCount > negCount -> 1
                negCount > posCount -> -1
                else -> 0
            }
            val strength = (50 + (posCount - negCount) * 15).coerceIn(0, 100)
            news.copy(
                sentiment = sentiment,
                impactStrength = strength,
                source = "${news.source}_keyword",
                tags = sector
            )
        }
    }

    /** AI provider 直接搜索+分析（最终 fallback） */
    private suspend fun searchWithAiProvider(sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val prompt = """
你是一位 A 股财经分析师。请搜索并分析关于「$sector」板块的最新新闻，返回 JSON 格式：

请返回：
{
  "news": [
    {
      "stock_code": "",
      "company": "",
      "title": "新闻标题",
      "content": "摘要",
      "sentiment": 1,
      "strength": 60,
      "tags": "$sector"
    }
  ]
}
"sentiment": 1=利好, -1=利空, 0=中性
"strength": 0-100 影响力度
请根据你的知识库提供该板块的最新动态，只返回 JSON，不要其他文字。
""".trimIndent()

            val slot = com.chin.stockanalysis.ai.AiProviderPool.acquire(
                context = context,
                callerTag = "HotSectorNewsUpdater.aiSearch",
                timeoutMs = 8_000L
            ) ?: return emptyList()

            try {
                val response = withTimeoutOrNull(8000) {
                    withContext(Dispatchers.IO) {
                        suspendCancellableCoroutine<String> { cont ->
                            slot.provider.sendMessageStream(
                                messages = emptyList(),
                                systemPrompt = prompt,
                                onSuccess = {},
                                onComplete = { full -> cont.resumeWith(Result.success(full)) },
                                onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
                            )
                        }
                    }
                }

                if (response == null) {
                    Log.w(TAG, "⏱️ [$sector] AI provider 搜索超时")
                    return emptyList()
                }
                parseNewsResponse(response, sector, today)
            } finally {
                com.chin.stockanalysis.ai.AiProviderPool.release(slot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI provider 搜索失败 [$sector]: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchNewsFromEastMoney(sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val url = com.chin.stockanalysis.config.DataConfig.eastmoneySearchUrl(sector, "14", 5)
            val request = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").build()
            val response = withTimeoutOrNull(3000) {
                withContext(Dispatchers.IO) { com.chin.stockanalysis.stock.data.HttpClientProvider.realtimeClient.newCall(request).execute() }
            }
            if (response == null || !response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            // 东方财富返回 {"QuotationCodeTable":{"Data":[...]}} 格式
            val arr = try {
                val root = JSONObject(body)
                if (root.has("QuotationCodeTable")) {
                    root.getJSONObject("QuotationCodeTable").getJSONArray("Data")
                } else if (root.has("Data")) {
                    root.getJSONArray("Data")
                } else {
                    null
                }
            } catch (_: Exception) {
                try { JSONArray(body) } catch (_: Exception) { null }
            }
            if (arr == null) return emptyList()
            val news = mutableListOf<NewsFactorEntity>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                news.add(NewsFactorEntity(
                    stockCode = "",
                    companyName = item.optString("Name", ""),
                    title = item.optString("Name", ""),
                    content = "",
                    newsDate = today,
                    sentiment = 1,
                    impactStrength = 50,
                    source = "eastmoney",
                    sourceUrl = item.optString("Url", ""),
                    tags = sector,
                    sector = sector,
                    createdAt = System.currentTimeMillis(),
                    isActive = true
                ))
            }
            news
        } catch (e: Exception) {
            Log.w(TAG, "东方财富新闻搜索失败 [$sector]: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchNewsFromDuckDuckGo(sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val query = "$sector A股 新闻"
            val url = com.chin.stockanalysis.config.DataConfig.duckduckgoUrl(query)
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Accept", "text/html")
                .build()
            val response = withTimeoutOrNull(5000) {
                withContext(Dispatchers.IO) { com.chin.stockanalysis.stock.data.HttpClientProvider.realtimeClient.newCall(request).execute() }
            }
            if (response == null || !response.isSuccessful) return emptyList()

            val html = response.body?.string() ?: return emptyList()
            // 简单解析：提取 result__a 和 result__snippet
            val news = mutableListOf<NewsFactorEntity>()
            val titleRegex = Regex("<a[^>]+class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val snippetRegex = Regex("<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val titles = titleRegex.findAll(html).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()
            val snippets = snippetRegex.findAll(html).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()

            for (i in titles.indices.take(5)) {
                news.add(NewsFactorEntity(
                    stockCode = "",
                    companyName = "",
                    title = titles.getOrNull(i) ?: "",
                    content = snippets.getOrNull(i)?.take(200) ?: "",
                    newsDate = today,
                    sentiment = 1,
                    impactStrength = 50,
                    source = "duckduckgo",
                    sourceUrl = "",
                    tags = sector,
                    sector = sector,
                    createdAt = System.currentTimeMillis(),
                    isActive = true
                ))
            }
            news
        } catch (e: Exception) {
            Log.w(TAG, "DuckDuckGo 搜索失败 [$sector]: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchNewsWithTavily(sector: String, today: String): List<NewsFactorEntity> {
        val apiKey = com.chin.stockanalysis.config.DataConfig.searchTavilyApiKey
        if (apiKey.isEmpty()) {
            Log.i(TAG, "Tavily API Key 未配置，跳过 Tavily 搜索 [$sector]")
            return emptyList()
        }
        return try {
            val url = com.chin.stockanalysis.config.DataConfig.searchTavily
            val jsonBody = JSONObject().apply {
                put("api_key", apiKey)
                put("query", "$sector A股 新闻")
                put("search_depth", "basic")
                put("max_results", 5)
                put("include_answer", false)
            }
            val mediaType = "application/json".toMediaType()
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody(mediaType))
                .addHeader("Content-Type", "application/json")
                .build()
            val client = com.chin.stockanalysis.stock.data.HttpClientProvider.realtimeClient
            val response = withTimeoutOrNull(5000) {
                withContext(Dispatchers.IO) { client.newCall(request).execute() }
            }
            if (response == null || !response.isSuccessful) {
                Log.w(TAG, "Tavily 搜索失败: ${response?.code}")
                return emptyList()
            }
            val bodyStr = response.body?.string() ?: return emptyList()
            val obj = JSONObject(bodyStr)
            val results = obj.optJSONArray("results") ?: return emptyList()
            val news = mutableListOf<NewsFactorEntity>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                news.add(NewsFactorEntity(
                    stockCode = "",
                    companyName = "",
                    title = item.optString("title", ""),
                    content = item.optString("content", "").take(200),
                    newsDate = today,
                    sentiment = 1,
                    impactStrength = 50,
                    source = "tavily",
                    sourceUrl = item.optString("url", ""),
                    tags = sector,
                    sector = sector,
                    createdAt = System.currentTimeMillis(),
                    isActive = true
                ))
            }
            Log.i(TAG, "Tavily 搜索 [$sector] 获取 ${news.size} 条新闻")
            news
        } catch (e: Exception) {
            Log.w(TAG, "Tavily 搜索 [$sector] 失败: ${e.message}")
            emptyList()
        }
    }
}