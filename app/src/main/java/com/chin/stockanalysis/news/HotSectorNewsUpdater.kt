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
 * 1. 读取 sector_daily_record 近 100 交易日数据
 * 2. 聚合 Top 5 热门板块（AI 硬件优先）
 * 3. 用豆包 AI 搜索相关板块最新新闻
 * 4. 提取结构化新闻 → 写入 news_factors 表
 *
 * ### AI 硬件优先板块
 * 光通信、光模块、光芯片、光材料、存储、半导体芯片、国产替代、AI 应用、绿色电力、电网设备
 */
class HotSectorNewsUpdater(private val context: Context) {

    companion object {
        private const val TAG = "HotSectorNewsUpdater"
        private const val PREFS_NAME = "hot_sector_news_cache"
        private const val CACHE_TTL_MINUTES = 60  // 新聞緩存有效期（分鐘）
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** AI 硬件优先板块关键词 */
        val PRIORITY_SECTORS = listOf(
            "光通信", "光模块", "光芯片", "光材料", "CPO",
            "存储芯片", "HBM", "半导体设备", "半导体材料", "芯片国产替代",
            "AI服务器", "AI应用", "AIPC", "英伟达产业链",
            "绿色电力", "电网设备", "特高压", "液冷散热",
            "华为昇腾", "算力租赁", "PCB", "先进封装"
        )

        /**
         * 全局 Mutex：多個 Fragment/Engine 同時調用時，
         * 只有第一個執行實際拉取，其餘掛起等待，完成後共享結果。
         */
        private val globalMutex = kotlinx.coroutines.sync.Mutex()

        /**
         * 便捷靜態方法：全局只啟動一次 async，後續調用返回同一個 Deferred。
         */
        private var pendingJob: Deferred<Unit>? = null

        @Synchronized
        fun ensureFreshGlobal(scope: CoroutineScope, context: Context, forceRefresh: Boolean = true): Deferred<Unit> {
            // 如果已有正在執行或已完成的 job，直接返回
            if (pendingJob != null && pendingJob!!.isActive) {
                Log.i(TAG, "⏭️ 新聞因子拉取已在進行中，共享同一個 job")
                return pendingJob!!
            }
            val job = HotSectorNewsUpdater(context).ensureFreshAsync(scope, forceRefresh, ignoreQuantPause = true)
            pendingJob = job
            // 完成後清理引用
            job.invokeOnCompletion { pendingJob = null }
            return job
        }
    }

    private val db = StockDatabase.getInstance(context)

    /** 是否已执行过更新（本次 App 生命周期内只跑一次） */
    @Volatile
    private var hasRun = false

    /**
     * App 启动或用户手动刷新时调用。
     * 全局 Mutex 保證多個調用方共享同一次拉取：
     * - 第一個調用：執行實際拉取
     * - 後續調用：掛起等待，完成後直接返回（不重複拉取）
     *
     * @param forceRefresh true=忽略緩存強制重新拉取
     */
    suspend fun updateIfNeeded(forceRefresh: Boolean = false, ignoreQuantPause: Boolean = false) {
        // 快速路徑：非強制且已完成，直接返回
        if (!forceRefresh && hasRun) {
            Log.i(TAG, "⏭️ 新聞因子已更新過，跳過")
            return
        }

        globalMutex.lock()
        try {
            // 雙重檢查：獲得鎖後再判斷（可能已被其他線程完成）
            if (!forceRefresh && hasRun) {
                Log.i(TAG, "⏭️ 新聞因子已被其他調用更新，共享結果")
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

            Log.i(TAG, "━━━ 開始檢查熱門板塊新聞（${CACHE_TTL_MINUTES}分鐘內有緩存則跳過） ━━━")

            // 1. 获取 Top 5 热门板块
            val topSectors = getTopHotSectors()
            Log.i(TAG, "Top 5 热门板块: ${topSectors.joinToString()}")

            // 2. 优先选择 AI 硬件相关板块
            val targetSectors = selectPrioritySectors(topSectors)

            // 3. 用 AI 搜索新闻
            val allNews = mutableListOf<NewsFactorEntity>()
            for ((i, sector) in targetSectors.withIndex()) {
                val news = searchSectorNews(sector, ignoreQuantPause = ignoreQuantPause)
                allNews.addAll(news)
                if (i < targetSectors.size - 1) delay(800)
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
        }
    }

    /**
     * 非阻塞版本：返回一個 Deferred，調用方可：
     * 1. 先做其他任務
     * 2. 到需要新聞因子時 `.await()`
     *
     * 典型用法：
     * ```
     * val newsJob = HotSectorNewsUpdater.ensureFreshAsync(context)
     * doOtherWork()  // 並行執行不需要新聞的任務
     * newsJob.await() // 到需要新聞因子時才等待
     * ```
     */
    fun ensureFreshAsync(scope: CoroutineScope, forceRefresh: Boolean = true, ignoreQuantPause: Boolean = false): Deferred<Unit> {
        return scope.async(Dispatchers.IO) {
            updateIfNeeded(forceRefresh, ignoreQuantPause)
        }
    }

    /** 从 sector_daily_record 获取近 100 日最热门板块，数据不足时用默认热门关键词 */
    private suspend fun getTopHotSectors(): List<String> {
        return try {
            val hotStats = db.sectorDailyRecordDao().getTopHotSectors(15)
            if (hotStats.isNotEmpty()) {
                hotStats.map { it.sector_code }
            } else {
                Log.i(TAG, "sector_daily_record 为空，使用默认热门板块关键词")
                PRIORITY_SECTORS.take(10)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取板块记录失败: ${e.message}")
            PRIORITY_SECTORS.take(10)
        }
    }

    /** 优先选择 AI 硬件相关板块，不足时用热门板块/默认关键词补充 */
    private fun selectPrioritySectors(hotSectors: List<String>): List<String> {
        // 先找到 AI 硬件匹配的
        val priority = hotSectors.filter { sector ->
            PRIORITY_SECTORS.any { p -> sector.contains(p, ignoreCase = true) || p.contains(sector, ignoreCase = true) }
        }
        val others = hotSectors.filter { it !in priority }
        Log.i(TAG, "AI 硬件优先板块: ${priority.joinToString()}")
        // 确保始终有 5 个板块可搜索
        val result = (priority + others).take(5)
        return if (result.size < 5) {
            // 不足 5 个时用默认热门关键词补全
            val fill = PRIORITY_SECTORS.filter { it !in result }.take(5 - result.size)
            result + fill
        } else result
    }

    /** 三級優先級搜索指定板塊最新新聞（搜索與解析分離） */
    private suspend fun searchSectorNews(sector: String, ignoreQuantPause: Boolean = false): List<NewsFactorEntity> {
        // 量化選股運行時暫停新聞搜索（除非是量化主動調用）
        if (!ignoreQuantPause && com.chin.stockanalysis.stock.database.AppBackgroundRunner.isQuantRunning) {
            Log.i(TAG, "⏸️ 量化選股運行中，跳過新聞搜索: $sector")
            return emptyList()
        }

        // 緩存檢查
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetchKey = "last_fetch_$sector"
        val lastFetch = prefs.getLong(lastFetchKey, 0L)
        if (System.currentTimeMillis() - lastFetch < 30 * 60 * 1000) {
            val cached = loadCachedNews(sector)
            if (cached.isNotEmpty()) return cached
        }

        val today = LocalDate.now().format(DATE_FMT)

        // === 第一步：搜索新聞 ===
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
            // AI 解析失敗，繼續到 fallback
        }

        // === Fallback 1：AI provider 直接搜索+分析 ===
        Log.w(TAG, "⚠️ [$sector] 分離搜索解析失敗，fallback 到 AI provider")
        val aiNews = searchWithAiProvider(sector, today)
        if (aiNews.isNotEmpty()) {
            prefs.edit().putLong(lastFetchKey, System.currentTimeMillis()).apply()
            return aiNews
        }

        // === Fallback 2：關鍵詞匹配 ===
        if (rawNews.isNotEmpty()) {
            Log.w(TAG, "⚠️ [$sector] AI provider 也失敗，fallback 到關鍵詞匹配")
            val keywordParsed = parseWithKeywords(rawNews, sector, today)
            if (keywordParsed.isNotEmpty()) {
                prefs.edit().putLong(lastFetchKey, System.currentTimeMillis()).apply()
                return keywordParsed
            }
        }

        Log.w(TAG, "⚠️ [$sector] 所有新聞源均失敗")
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

    /** 從數據庫加載今日該板塊的緩存新聞 */
    private suspend fun loadCachedNews(sector: String): List<NewsFactorEntity> {
        return try {
            val today = LocalDate.now().format(DATE_FMT)
            db.newsFactorDao().getActiveBySector(sector, limit = 20)
                .filter { it.newsDate == today }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 使用 AI 解析新聞列表，返回結構化結果 */
    private suspend fun parseNewsWithAi(newsList: List<NewsFactorEntity>, sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val titles = newsList.joinToString("\n") { "- ${it.title}" }
            val prompt = """
你是一位 A 股財經分析師。請分析以下關於「$sector」板塊的新聞，返回 JSON 格式：

新聞列表：
$titles

請返回：
{
  "news": [
    {
      "stock_code": "",
      "company": "",
      "title": "新聞標題",
      "content": "摘要",
      "sentiment": 1,
      "strength": 60,
      "tags": "$sector"
    }
  ]
}
"sentiment": 1=利好, -1=利空, 0=中性
"strength": 0-100 影響力度
只返回 JSON，不要其他文字。
""".trimIndent()

            val slot = com.chin.stockanalysis.ai.AiProviderPool.acquire(
                context = context,
                callerTag = "HotSectorNewsUpdater.parseNews",
                timeoutMs = 10_000L
            ) ?: return emptyList()

            try {
                val response = withTimeoutOrNull(10000) {
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
                    Log.w(TAG, "⏱️ [$sector] AI 解析新聞超時")
                    return emptyList()
                }
                parseNewsResponse(response, sector, today)
            } finally {
                com.chin.stockanalysis.ai.AiProviderPool.release(slot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI 解析新聞失敗 [$sector]: ${e.message}")
            emptyList()
        }
    }

    /** 關鍵詞匹配解析（AI 解析失敗時的 fallback） */
    private fun parseWithKeywords(newsList: List<NewsFactorEntity>, sector: String, today: String): List<NewsFactorEntity> {
        val positive = setOf("漲", "漲停", "利好", "訂單", "增長", "突破", "超預期", "業績", "盈利", "創新高", "爆發", "強勢")
        val negative = setOf("跌", "跌停", "利空", "虧損", "下滑", "減持", "召回", "監管", "調查", "暴跌", "疲軟")
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

    /** AI provider 直接搜索+分析（最終 fallback） */
    private suspend fun searchWithAiProvider(sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val prompt = """
你是一位 A 股財經分析師。請搜索並分析關於「$sector」板塊的最新新聞，返回 JSON 格式：

請返回：
{
  "news": [
    {
      "stock_code": "",
      "company": "",
      "title": "新聞標題",
      "content": "摘要",
      "sentiment": 1,
      "strength": 60,
      "tags": "$sector"
    }
  ]
}
"sentiment": 1=利好, -1=利空, 0=中性
"strength": 0-100 影響力度
請根據你的知識庫提供該板塊的最新動態，只返回 JSON，不要其他文字。
""".trimIndent()

            val slot = com.chin.stockanalysis.ai.AiProviderPool.acquire(
                context = context,
                callerTag = "HotSectorNewsUpdater.aiSearch",
                timeoutMs = 15_000L
            ) ?: return emptyList()

            try {
                val response = withTimeoutOrNull(15000) {
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
                    Log.w(TAG, "⏱️ [$sector] AI provider 搜索超時")
                    return emptyList()
                }
                parseNewsResponse(response, sector, today)
            } finally {
                com.chin.stockanalysis.ai.AiProviderPool.release(slot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI provider 搜索失敗 [$sector]: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchNewsFromEastMoney(sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val url = "https://searchapi.eastmoney.com/api/suggest/get?input=${URLEncoder.encode(sector, "UTF-8")}&type=14&count=5"
            val request = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").build()
            val response = withTimeoutOrNull(5000) {
                withContext(Dispatchers.IO) { com.chin.stockanalysis.stock.data.HttpClientProvider.realtimeClient.newCall(request).execute() }
            }
            if (response == null || !response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            // 東方財富返回的是 JSON 數組
            val arr = JSONArray(body)
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
            Log.w(TAG, "東方財富新聞搜索失敗 [$sector]: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchNewsFromDuckDuckGo(sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val query = URLEncoder.encode("$sector A股 新闻", "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$query"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Accept", "text/html")
                .build()
            val response = withTimeoutOrNull(8000) {
                withContext(Dispatchers.IO) { com.chin.stockanalysis.stock.data.HttpClientProvider.realtimeClient.newCall(request).execute() }
            }
            if (response == null || !response.isSuccessful) return emptyList()

            val html = response.body?.string() ?: return emptyList()
            // 簡單解析：提取 result__a 和 result__snippet
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
            Log.w(TAG, "DuckDuckGo 搜索失敗 [$sector]: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchNewsWithTavily(sector: String, today: String): List<NewsFactorEntity> {
        return try {
            val url = "https://api.tavily.com/search"
            val jsonBody = JSONObject().apply {
                put("api_key", "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")
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
            val response = withTimeoutOrNull(8000) {
                withContext(Dispatchers.IO) { client.newCall(request).execute() }
            }
            if (response == null || !response.isSuccessful) {
                Log.w(TAG, "Tavily 搜索失敗: ${response?.code}")
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
            Log.i(TAG, "Tavily 搜索 [$sector] 獲取 ${news.size} 條新聞")
            news
        } catch (e: Exception) {
            Log.w(TAG, "Tavily 搜索 [$sector] 失敗: ${e.message}")
            emptyList()
        }
    }
}