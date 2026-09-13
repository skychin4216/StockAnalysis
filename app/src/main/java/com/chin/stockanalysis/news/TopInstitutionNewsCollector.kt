package com.chin.stockanalysis.news

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * ## 全球 Top10 机构研报收集器（新闻因子）
 *
 * 抓取东方财富研报中心发布的全球 Top10 投资银行研报，
 * 解析为结构化新闻因子写入 `news_factors` 表（source = "top10_institution"），
 * 供策略把机构研报当作新闻因子参与分析。
 *
 * 数据流：
 * 1. 调用东方财富研报中心 API 拉取最近 N 天研报（真实数据）
 * 2. 按机构名过滤出 Top10 机构（高盛/摩根士丹利/摩根大通等）发布的研报
 * 3. 先用评级规则解析（确定性高），再对前 15 条用 AI 补充摘要与情感校准
 * 4. 写入 `news_factors` 表，同日同源先清后写，避免重复
 *
 * 缓存策略：每天最多更新一次（`forceRefresh` 可强制）。
 */
class TopInstitutionNewsCollector(private val context: Context) {

    companion object {
        private const val TAG = "TopInstNews"
        private const val SOURCE = "top10_institution"
        private const val AI_BATCH_SIZE = 15

        /** 全球 Top10 投资银行（东方财富研报中心 orgSName 匹配关键字） */
        private val TOP_INSTITUTIONS = listOf(
            "高盛", "摩根士丹利", "摩根大通", "美银", "花旗",
            "瑞银", "瑞信", "德意志银行", "巴克莱", "汇丰"
        )

        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 每日缓存：记录最近一次成功更新的日期 */
        private val lastUpdateDay = AtomicReference("")

        private val mutex = kotlinx.coroutines.sync.Mutex()

        /** 全局共享 job：避免多个调用方重复拉取 */
        private var pendingJob: Deferred<*>? = null

        @Synchronized
        fun ensureFreshGlobal(scope: kotlinx.coroutines.CoroutineScope, context: Context, forceRefresh: Boolean = false): Deferred<*> {
            if (pendingJob != null && pendingJob!!.isActive) {
                Log.i(TAG, "⏭️ 机构研报收集已在进行中，共享同一个 job")
                return pendingJob!!
            }
            val job = scope.async(Dispatchers.IO) {
                TopInstitutionNewsCollector(context).updateIfNeeded(forceRefresh)
            }
            pendingJob = job
            job.invokeOnCompletion { pendingJob = null }
            return job
        }

        /** 今日是否已更新过 */
        fun isUpdatedToday(): Boolean = lastUpdateDay.get() == LocalDate.now().format(DATE_FMT)
    }

    private val db = StockDatabase.getInstance(context)

    /**
     * 更新机构研报新闻因子。
     *
     * @param forceRefresh true=忽略每日缓存强制刷新
     * @return 本次写入的新闻因子列表（未更新时返回空）
     */
    suspend fun updateIfNeeded(forceRefresh: Boolean = false): List<NewsFactorEntity> {
        val today = LocalDate.now().format(DATE_FMT)
        if (!forceRefresh && lastUpdateDay.get() == today) {
            Log.i(TAG, "⏭️ 今日机构研报已更新过，跳过")
            return emptyList()
        }

        mutex.lock()
        try {
            // 双重检查：获得锁后可能已被其他协程更新
            if (!forceRefresh && lastUpdateDay.get() == today) return emptyList()

            Log.i(TAG, "━━━ 开始收集 Top10 机构研报 ━━━")

            // 1. 拉取研报
            val reports = fetchInstitutionReports()
            if (reports.isEmpty()) {
                Log.w(TAG, "⚠️ 未拉取到 Top10 机构研报")
                return emptyList()
            }
            Log.i(TAG, "📊 拉取到 Top10 机构研报 ${reports.size} 条")

            // 2. 评级规则解析（保底）
            var entities = reports.map { it.toNewsFactor(today) }

            // 3. AI 补充摘要 + 情感校准（失败不影响已有结果）
            val aiParsed = parseWithAi(entities)
            if (aiParsed.isNotEmpty()) entities = aiParsed

            // 4. 写入数据库（同日同源先清后写，避免重复）
            if (entities.isNotEmpty()) {
                db.newsFactorDao().deleteBySourceAndDate(SOURCE, today)
                db.newsFactorDao().insertAll(entities)
                lastUpdateDay.set(today)
                Log.i(TAG, "✅ 已保存 ${entities.size} 条 Top10 机构研报新闻因子")
            }
            return entities
        } catch (e: Exception) {
            Log.w(TAG, "机构研报收集失败: ${e.message}")
            return emptyList()
        } finally {
            mutex.unlock()
        }
    }

    // ══════════════════════════════════════
    // 数据拉取
    // ══════════════════════════════════════

    /** 从东方财富研报中心拉取最近 7 天研报，并按 Top10 机构过滤 */
    private suspend fun fetchInstitutionReports(): List<ReportInfo> {
        return try {
            val endDate = LocalDate.now()
            val beginDate = endDate.minusDays(6)
            val url = "${DataConfig.eastmoneyReport}?industryCode=*&pageSize=100" +
                "&industry=*&rating=*&ratingChange=*" +
                "&beginTime=${beginDate.format(DATE_FMT)}&endTime=${endDate.format(DATE_FMT)}" +
                "&pageNo=1&fields=&qType=0&orgCode="
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .addHeader("Referer", DataConfig.eastmoneyReportData)
                .build()

            val response = withTimeoutOrNull(6000) {
                withContext(Dispatchers.IO) { HttpClientProvider.realtimeClient.newCall(request).execute() }
            }
            if (response == null || !response.isSuccessful) {
                Log.w(TAG, "研报接口失败: ${response?.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("data") ?: return emptyList()

            val reports = mutableListOf<ReportInfo>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val org = item.optString("orgSName", "")
                // 仅保留 Top10 机构研报
                if (TOP_INSTITUTIONS.none { org.contains(it) }) continue
                val title = item.optString("title", "")
                if (title.isBlank()) continue
                reports.add(
                    ReportInfo(
                        org = org,
                        title = title,
                        stockName = item.optString("stockName", ""),
                        stockCode = item.optString("stockCode", "").trim(),
                        industry = item.optString("industryName", ""),
                        rating = item.optString("ratingName", ""),
                        publishDate = item.optString("publishDate", "").take(10),
                        researcher = item.optString("researcher", "")
                    )
                )
            }
            Log.i(TAG, "📑 Top10 机构研报 ${reports.size} 条 / 接口返回 ${arr.length()} 条")
            reports
        } catch (e: Exception) {
            Log.w(TAG, "研报拉取失败: ${e.message}")
            emptyList()
        }
    }

    // ══════════════════════════════════════
    // AI 解析
    // ══════════════════════════════════════

    /**
     * 用 AI 对前 [AI_BATCH_SIZE] 条研报做摘要与情感校准。
     * 按标题匹配覆盖 sentiment / impactStrength / content；失败返回空，不影响规则解析结果。
     */
    private suspend fun parseWithAi(entities: List<NewsFactorEntity>): List<NewsFactorEntity> {
        if (entities.isEmpty()) return emptyList()
        val batch = entities.take(AI_BATCH_SIZE)
        val lines = batch.joinToString("\n") { e ->
            "- 机构=${e.tags.substringAfter("机构研报,").substringBefore(",")} | 标题=${e.title} | 公司=${e.companyName} | 评级=${e.content.substringAfter("评级：", "").substringBefore("，")}"
        }
        val prompt = """
你是一位资深 A 股策略分析师。以下是全球 Top10 投资银行近期发布的研报列表，请逐条判断其对 A 股相关个股的影响，返回 JSON：

研报列表：
$lines

返回格式：
{
  "reports": [
    {
      "title": "研报标题（原样返回）",
      "sentiment": 1,
      "strength": 70,
      "summary": "一句话摘要（含评级/目标价/核心逻辑，20-40字）"
    }
  ]
}
规则：
- sentiment: 1=利好(买入/增持/强烈推荐/超配), -1=利空(卖出/减持/回避/低配), 0=中性(持有/中性/标配)
- strength: 0-100，体现研报对股价的影响力度
- 只返回 JSON，不要任何其他文字。
""".trimIndent()

        val slot = AiProviderPool.acquire(
            context = context,
            callerTag = "TopInstitutionNewsCollector.parse",
            timeoutMs = 8_000L
        ) ?: return emptyList()

        return try {
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
                Log.w(TAG, "⏱️ AI 解析研报超时")
                return emptyList()
            }
            applyAiResult(entities, response)
        } catch (e: Exception) {
            Log.w(TAG, "AI 解析研报失败: ${e.message}")
            emptyList()
        } finally {
            AiProviderPool.release(slot)
        }
    }

    /** 解析 AI 返回的 JSON，并按标题匹配覆盖原实体 */
    private fun applyAiResult(entities: List<NewsFactorEntity>, raw: String): List<NewsFactorEntity> {
        return try {
            val json = extractJson(raw) ?: return emptyList()
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("reports") ?: return emptyList()
            val parsedByTitle = mutableMapOf<String, Triple<Int, Int, String>>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val title = r.optString("title", "")
                if (title.isBlank()) continue
                parsedByTitle[title.trim()] = Triple(
                    r.optInt("sentiment", 0).coerceIn(-1, 1),
                    r.optInt("strength", 50).coerceIn(0, 100),
                    r.optString("summary", "").take(100)
                )
            }
            if (parsedByTitle.isEmpty()) return emptyList()

            entities.map { e ->
                val parsed = parsedByTitle[e.title.trim()]
                if (parsed != null) {
                    e.copy(
                        sentiment = parsed.first,
                        impactStrength = parsed.second,
                        content = if (parsed.third.isNotBlank()) parsed.third else e.content
                    )
                } else e
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI 研报 JSON 解析失败: ${e.message}")
            emptyList()
        }
    }

    /** 从 AI 输出中提取第一个完整的 JSON 对象 */
    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    // ══════════════════════════════════════
    // 实体转换
    // ══════════════════════════════════════

    /** 研报原始信息 */
    private data class ReportInfo(
        val org: String,
        val title: String,
        val stockName: String,
        val stockCode: String,
        val industry: String,
        val rating: String,
        val publishDate: String,
        val researcher: String
    )

    /** 按评级规则转换为新闻因子（确定性保底） */
    private fun ReportInfo.toNewsFactor(today: String): NewsFactorEntity {
        val bullish = listOf("买入", "强烈推荐", "推荐", "增持", "超配", "优于大市", "跑赢行业")
        val bearish = listOf("卖出", "减持", "回避", "低配", "弱于大市", "跑输行业")
        val sentiment = when {
            bullish.any { rating.contains(it) } -> 1
            bearish.any { rating.contains(it) } -> -1
            else -> 0
        }
        val strength = when {
            sentiment == 1 && (rating.contains("买入") || rating.contains("强烈") || rating.contains("超配")) -> 80
            sentiment == 1 -> 65
            sentiment == -1 -> 75
            else -> 45
        }
        val code = normalizeStockCode(stockCode)
        val ratingText = rating.ifBlank { "未评级" }
        val industryText = industry.ifBlank { "" }
        val summary = buildString {
            append("【$org】对 ${stockName.ifBlank { "相关个股" }} 发布研报，评级：$ratingText")
            if (industryText.isNotBlank()) append("，行业：$industryText")
            if (researcher.isNotBlank()) append("，分析师：$researcher")
        }
        return NewsFactorEntity(
            stockCode = code,
            companyName = stockName,
            title = title,
            content = summary,
            newsDate = publishDate.ifBlank { today },
            sentiment = sentiment,
            impactStrength = strength,
            source = SOURCE,
            sourceUrl = DataConfig.eastmoneyReportData,
            tags = listOf("机构研报", org, rating).filter { it.isNotBlank() }.joinToString(","),
            sector = industry,
            createdAt = System.currentTimeMillis(),
            isActive = true
        )
    }

    /** 6 位数字代码 → 带交易所前缀（sh/sz/bj），已有前缀原样返回 */
    private fun normalizeStockCode(code: String): String {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length > 6 &&
            (trimmed.startsWith("sh") || trimmed.startsWith("sz") || trimmed.startsWith("bj"))
        ) {
            return trimmed
        }
        return when {
            trimmed.startsWith("6") -> "sh$trimmed"
            trimmed.startsWith("0") || trimmed.startsWith("3") -> "sz$trimmed"
            trimmed.startsWith("4") || trimmed.startsWith("8") -> "bj$trimmed"
            else -> trimmed
        }
    }
}
