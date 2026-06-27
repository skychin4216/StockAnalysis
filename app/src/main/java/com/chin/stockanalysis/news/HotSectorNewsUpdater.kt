package com.chin.stockanalysis.news

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.OpenAiCompatibleProvider
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.*
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
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** AI 硬件优先板块关键词 */
        val PRIORITY_SECTORS = listOf(
            "光通信", "光模块", "光芯片", "光材料", "CPO",
            "存储芯片", "HBM", "半导体设备", "半导体材料", "芯片国产替代",
            "AI服务器", "AI应用", "AIPC", "英伟达产业链",
            "绿色电力", "电网设备", "特高压", "液冷散热",
            "华为昇腾", "算力租赁", "PCB", "先进封装"
        )
    }

    private val db = StockDatabase.getInstance(context)

    /** 是否已执行过更新（本次 App 生命周期内只跑一次） */
    @Volatile
    private var hasRun = false

    /**
     * App 启动或用户手动刷新时调用。
     * @param forceRefresh true=忽略缓存强制重新拉取
     */
    suspend fun updateIfNeeded(forceRefresh: Boolean = false, ignoreQuantPause: Boolean = false) {
        if (!forceRefresh && hasRun) return
        hasRun = true

        try {
            val latestDate = db.newsFactorDao().getLatestNewsDate()
            val today = LocalDate.now().format(DATE_FMT)
            val existingCount = if (latestDate == today) db.newsFactorDao().countActive() else 0

            if (!forceRefresh && latestDate == today && existingCount > 5) {
                Log.i(TAG, "✅ 今日已有 $existingCount 条新闻，跳过更新")
                return
            }

            Log.i(TAG, "━━━ 开始后台拉取热门板块新闻 ━━━")

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

    /** 用豆包 AI 搜索指定板块的最新新闻 */
    private suspend fun searchSectorNews(sector: String, ignoreQuantPause: Boolean = false): List<NewsFactorEntity> {
        // 量化選股運行時暫停 AI 新聞搜索（除非是量化主動調用）
        if (!ignoreQuantPause && com.chin.stockanalysis.stock.database.AppBackgroundRunner.isQuantRunning) {
            Log.i(TAG, "⏸️ 量化選股運行中，跳過新聞搜索: $sector")
            return emptyList()
        }
        val slot = com.chin.stockanalysis.ai.AiProviderPool.acquire(context, callerTag = "HotSectorNewsUpdater.$sector")
        if (slot == null) {
            Log.w(TAG, "無可用 AI Provider，跳過新聞搜索: $sector")
            return emptyList()
        }
        try {
            val provider = slot.provider

            val today = LocalDate.now().format(DATE_FMT)
            val prompt = buildString {
                appendLine("你是一个A股财经新闻助手。请搜索最近一周关于【${sector}】板块的热门新闻（3-5条）。")
                appendLine("每一条新闻包含：标题、内容摘要、利好/利空判断、影响强度(0-100)、相关A股主板上市公司。")
                appendLine()
                appendLine("请按以下 JSON 格式输出（仅 JSON，不要其他文字）：")
                appendLine("```json")
                appendLine("{")
                appendLine("  \"sector\": \"$sector\",")
                appendLine("  \"news\": [")
                appendLine("    {")
                appendLine("      \"title\": \"新闻标题\",")
                appendLine("      \"content\": \"50字内摘要\",")
                appendLine("      \"sentiment\": 1,")
                appendLine("      \"strength\": 75,")
                appendLine("      \"company\": \"公司名称\",")
                appendLine("      \"stock_code\": \"sh600xxx\",")
                appendLine("      \"tags\": \"标签1,标签2\"")
                appendLine("    }")
                appendLine("  ]")
                appendLine("}")
                appendLine("```")
            }

            val response = withContext(Dispatchers.IO) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    provider.sendMessageStream(
                        messages = emptyList(),
                        systemPrompt = prompt,
                        onSuccess = {},
                        onComplete = { full -> cont.resumeWith(Result.success(full)) },
                        onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
                    )
                }
            }

            return parseNewsResponse(response, sector, today)
        } catch (e: Exception) {
            Log.w(TAG, "搜索板块[$sector]新闻失败: ${e.message}")
            return emptyList()
        } finally {
            com.chin.stockanalysis.ai.AiProviderPool.releaseNonBlocking(slot)
        }
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
}