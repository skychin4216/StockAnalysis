package com.chin.stockanalysis.news

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 新闻利好利空因子管理器
 *
 * 核心功能：
 * 1. 从 AI 对话中提取新闻因子（用户输入后确认添加）
 * 2. 定时搜索行业巨头动态（每天1次）
 * 3. 为策略分析提供活跃因子数据
 * 4. 自动清理过期数据（>3个月标记非活跃，>1年删除）
 *
 * ### 使用方式
 * ```kotlin
 * val manager = NewsFactorManager(context)
 * // AI 对话中检测到新闻信息
 * val extracted = manager.extractNewsFactorFromAI(userMessage, aiResponse)
 * // 询问用户是否保存
 * if (extracted != null) {
 *     // show dialog to user
 *     manager.insertFactor(extracted)
 * }
 * ```
 */
class NewsFactorManager(private val context: Context) {

    companion object {
        private const val TAG = "NewsFactorManager"
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val db = StockDatabase.getInstance(context)
    private val dao = db.newsFactorDao()

    // ════════════════════════════════════════
    // 1. AI 提取新闻因子
    // ════════════════════════════════════════

    /**
     * 从用户输入中检测是否包含公司/股票相关新闻信息，
     * 如果是，用AI提取关键信息并返回结构化数据。
     *
     * @return NewsFactorEntity 如果检测到新闻并成功提取，否则 null
     */
    suspend fun tryExtractFromUserMessage(userMessage: String): NewsFactorEntity? {
        if (!isNewsRelated(userMessage)) return null

        val provider = ApiConfigManager.getInstance(context).createCurrentProvider() ?: return null

        val prompt = """你是一个财经新闻提取助手。从以下用户消息中提取利好/利空新闻因子。

仅输出 JSON 对象（不是数组）：
{
  "company_name": "公司/股票名称",
  "title": "新闻标题(15字以内)",
  "content": "新闻摘要(50-100字)",
  "news_date": "新闻日期(YYYY-MM-DD，默认今天)",
  "sentiment": 1或-1或0 (利好/利空/中性),
  "impact_strength": 1-100 (影响强度),
  "tags": "关联标签(逗号分隔)",
  "sector": "行业/板块",
  "stock_code": "股票代码(如sh600519，若无则空)"
}

如果用户消息不包含有价值新闻，输出：{"skip":true}

用户消息：
$userMessage"""

        return try {
            val result = withContext(Dispatchers.IO) {
                sendSyncRequest(provider, prompt)
            }
            parseSingleFactor(result)
        } catch (e: Exception) {
            Log.e(TAG, "AI 提取新闻因子失败: ${e.message}")
            null
        }
    }

    /**
     * 更完整的提取：从用户消息+AI回复中提取
     */
    suspend fun extractFromConversation(userMessage: String, aiResponse: String): List<NewsFactorEntity> {
        val provider = ApiConfigManager.getInstance(context).createCurrentProvider() ?: return emptyList()

        val prompt = """你是一个财经新闻提取助手。从以下对话中提取所有利好/利空新闻信息。

仅输出 JSON 数组：
[{
  "company_name": "公司/股票名称",
  "title": "新闻标题(15字以内)",
  "content": "新闻摘要(50-100字)",
  "news_date": "新闻日期(YYYY-MM-DD，默认今天)",
  "sentiment": 1或-1或0 (利好/利空/中性),
  "impact_strength": 1-100 (影响强度),
  "tags": "关联标签(逗号分隔)",
  "sector": "行业/板块",
  "stock_code": "股票代码(如sh600519，若无则空)"
}]

如果没有有价值的新闻信息，输出：[]

用户: $userMessage
AI回复: ${aiResponse.take(500)}"""

        return try {
            val result = withContext(Dispatchers.IO) {
                sendSyncRequest(provider, prompt)
            }
            parseFactorList(result)
        } catch (e: Exception) {
            Log.e(TAG, "AI 提取新闻因子失败: ${e.message}")
            emptyList()
        }
    }

    // ════════════════════════════════════════
    // 2. 定时搜索行业巨头动态
    // ════════════════════════════════════════

    /**
     * 每日搜索一次重要行业巨头的新闻动态
     * 关注的巨头列表可在内部配置
     */
    suspend fun dailySearchIndustryGiants(): List<NewsFactorEntity> {
        val giants = listOf(
            "英伟达 黄仁勋 近期动态",
            "特斯拉 马斯克 最新动向",
            "苹果 库克 供应链消息",
            "华为 任正非 芯片进展",
            "台积电 先进制程 最新消息",
            "微软 谷歌 AI布局",
            "比亚迪 新能源汽车 最新动态",
            "宁德时代 电池技术 进展"
        )

        val collected = mutableListOf<NewsFactorEntity>()
        val provider = ApiConfigManager.getInstance(context).createCurrentProvider() ?: return collected

        for (keyword in giants) {
            try {
                val result = extractNewsForKeyword(provider, keyword)
                if (result.isNotEmpty()) {
                    collected.addAll(result)
                    // 避免过快请求
                    kotlinx.coroutines.delay(2000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "搜索 '$keyword' 失败: ${e.message}")
            }
        }

        // 批量保存
        if (collected.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (factor in collected) {
                    dao.insert(factor)
                }
            }
            Log.i(TAG, "每日搜索完成，新增 ${collected.size} 条新闻因子")
        }
        return collected
    }

    private suspend fun extractNewsForKeyword(provider: ApiProvider, keyword: String): List<NewsFactorEntity> {
        val today = LocalDate.now().format(DATE_FMT)
        val prompt = """你是一个金融新闻搜索助手。请根据以下关键词搜索并整理近期的相关新闻动态。

关键词：$keyword

请输出 JSON 数组，每条新闻包含：
[{
  "company_name": "涉及的公司名称",
  "title": "新闻标题(15字以内)",
  "content": "新闻摘要(50-100字，基于你的训练数据)",
  "sentiment": 1或-1或0 (利好/利空/中性),
  "impact_strength": 1-100 (对A股相关板块的影响强度),
  "tags": "相关标签(逗号分隔)",
  "sector": "相关A股板块"
}]

最多返回3条最重要的新闻。没有重要新闻则输出[]。"""

        val result = sendSyncRequest(provider, prompt)
        val factors = parseFactorList(result)
        return factors.map { it.copy(
            newsDate = today,
            source = "ai_search",
            createdAt = System.currentTimeMillis()
        ) }
    }

    // ════════════════════════════════════════
    // 3. 数据管理和查询
    // ════════════════════════════════════════

    /** 插入新闻因子 */
    suspend fun insertFactor(entity: NewsFactorEntity): Long {
        return withContext(Dispatchers.IO) { dao.insert(entity) }
    }

    /** 批量插入 */
    suspend fun insertFactors(entities: List<NewsFactorEntity>) {
        withContext(Dispatchers.IO) { dao.insertAll(entities) }
    }

    /** 获取所有活跃因子（供策略分析使用） */
    suspend fun getActiveFactors(limit: Int = 100): List<NewsFactorEntity> {
        return withContext(Dispatchers.IO) { dao.getAllActive(limit) }
    }

    /** 获取利好因子 */
    suspend fun getBullishFactors(limit: Int = 50): List<NewsFactorEntity> {
        return withContext(Dispatchers.IO) { dao.getActiveBullish(limit) }
    }

    /** 获取利空因子 */
    suspend fun getBearishFactors(limit: Int = 50): List<NewsFactorEntity> {
        return withContext(Dispatchers.IO) { dao.getActiveBearish(limit) }
    }

    /** 按公司名称搜索 */
    suspend fun searchByCompany(keyword: String, limit: Int = 20): List<NewsFactorEntity> {
        return withContext(Dispatchers.IO) { dao.searchActiveByCompany(keyword, limit) }
    }

    /** 按行业搜索 */
    suspend fun searchBySector(sector: String, limit: Int = 20): List<NewsFactorEntity> {
        return withContext(Dispatchers.IO) { dao.getActiveBySector(sector, limit) }
    }

    /** 按股票代码搜索 */
    suspend fun searchByStockCode(code: String, limit: Int = 20): List<NewsFactorEntity> {
        return withContext(Dispatchers.IO) { dao.getActiveByStock(code, limit) }
    }

    /** 获取统计数据 */
    suspend fun getStats(): NewsFactorStats {
        return withContext(Dispatchers.IO) {
            NewsFactorStats(
                totalCount = dao.count(),
                activeCount = dao.countActive(),
                bullishCount = dao.getActiveBullish(1000).size,
                bearishCount = dao.getActiveBearish(1000).size,
                latestNewsDate = dao.getLatestNewsDate()
            )
        }
    }

    /** 定时清理：标记超3个月为非活跃，删除超1年 */
    suspend fun maintenance() {
        withContext(Dispatchers.IO) {
            val threeMonthsAgo = LocalDate.now().minusMonths(3).format(DATE_FMT)
            val oneYearAgo = LocalDate.now().minusYears(1).format(DATE_FMT)

            val deactivated = dao.deactivateOlderThan(threeMonthsAgo)
            val deleted = dao.deleteOlderThan(oneYearAgo)

            Log.i(TAG, "维护完成: 停用${deactivated}条, 删除${deleted}条")
        }
    }

    /** 构建策略分析用的因子描述文本 */
    suspend fun buildFactorPrompt(): String {
        val factors = withContext(Dispatchers.IO) { dao.getAllActive(50) }
        if (factors.isEmpty()) return ""

        return buildString {
            appendLine()
            appendLine("【近期利好利空新闻因子（近3个月）】")
            appendLine()

            val bullish = factors.filter { it.sentiment > 0 }
            if (bullish.isNotEmpty()) {
                appendLine("📈 利好因子：")
                for ((i, f) in bullish.take(15).withIndex()) {
                    appendLine("${i + 1}. [${f.companyName}] ${f.title} (强度:${f.impactStrength}) - ${f.newsDate}")
                }
                appendLine()
            }

            val bearish = factors.filter { it.sentiment < 0 }
            if (bearish.isNotEmpty()) {
                appendLine("📉 利空因子：")
                for ((i, f) in bearish.take(15).withIndex()) {
                    appendLine("${i + 1}. [${f.companyName}] ${f.title} (强度:${f.impactStrength}) - ${f.newsDate}")
                }
                appendLine()
            }
        }
    }

    /** 按ID删除 */
    suspend fun deleteById(id: Long) {
        withContext(Dispatchers.IO) { dao.deleteById(id) }
    }

    // ════════════════════════════════════════
    // 内部方法
    // ════════════════════════════════════════

    private suspend fun sendSyncRequest(provider: ApiProvider, prompt: String): String {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            provider.sendMessageStream(
                messages = emptyList(), systemPrompt = prompt,
                onSuccess = {},
                onComplete = { full -> cont.resumeWith(Result.success(full)) },
                onError = { err -> cont.resumeWith(Result.failure(Exception(err))) }
            )
        }
    }

    private fun parseSingleFactor(jsonStr: String): NewsFactorEntity? {
        try {
            val start = jsonStr.indexOf('{'); val end = jsonStr.lastIndexOf('}')
            if (start == -1 || end == -1) return null
            val obj = JSONObject(jsonStr.substring(start, end + 1))
            if (obj.optBoolean("skip", false)) return null

            val companyName = obj.optString("company_name", "").trim()
            if (companyName.isEmpty()) return null

            val today = LocalDate.now().format(DATE_FMT)
            return NewsFactorEntity(
                stockCode = obj.optString("stock_code", "").trim(),
                companyName = companyName,
                title = obj.optString("title", "").trim().ifEmpty { companyName + "相关动态" },
                content = obj.optString("content", "").trim(),
                newsDate = obj.optString("news_date", today).trim().ifEmpty { today },
                sentiment = obj.optInt("sentiment", 0),
                impactStrength = obj.optInt("impact_strength", 50).coerceIn(0, 100),
                source = "ai_extract",
                tags = obj.optString("tags", "").trim(),
                sector = obj.optString("sector", "").trim()
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析单条因子失败: ${e.message}")
            return null
        }
    }

    private fun parseFactorList(jsonStr: String): List<NewsFactorEntity> {
        val result = mutableListOf<NewsFactorEntity>()
        try {
            val start = jsonStr.indexOf('['); val end = jsonStr.lastIndexOf(']')
            if (start == -1 || end == -1) return result
            val arr = JSONArray(jsonStr.substring(start, end + 1))
            val today = LocalDate.now().format(DATE_FMT)

            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val companyName = obj.optString("company_name", "").trim()
                    if (companyName.isEmpty()) continue

                    result.add(NewsFactorEntity(
                        stockCode = obj.optString("stock_code", "").trim(),
                        companyName = companyName,
                        title = obj.optString("title", "").trim().ifEmpty { companyName + "相关动态" },
                        content = obj.optString("content", "").trim(),
                        newsDate = obj.optString("news_date", today).trim().ifEmpty { today },
                        sentiment = obj.optInt("sentiment", 0),
                        impactStrength = obj.optInt("impact_strength", 50).coerceIn(0, 100),
                        source = "ai_extract",
                        tags = obj.optString("tags", "").trim(),
                        sector = obj.optString("sector", "").trim()
                    ))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析因子列表失败: ${e.message}")
        }
        return result
    }

    /** 简单判断用户消息是否可能包含新闻信息 */
    private fun isNewsRelated(message: String): Boolean {
        val newsKeywords = listOf(
            "股价", "涨", "跌", "利好", "利空", "公告", "财报", "季报", "年报",
            "黄仁勋", "马斯克", "任正非", "CEO", "董事长", "创始人",
            "发布会", "大会", "展会", "GTC", "COMPUTEX", "收购", "合并",
            "供应链", "订单", "合作", "签约", "融资", "上市申请",
            "芯片", "AI", "人工智能", "自动驾驶", "新能源",
            "英伟达", "特斯拉", "华为", "台积电", "宁德时代", "比亚迪"
        )
        return newsKeywords.any { message.contains(it) }
    }
}

/** 统计信息 */
data class NewsFactorStats(
    val totalCount: Int,
    val activeCount: Int,
    val bullishCount: Int,
    val bearishCount: Int,
    val latestNewsDate: String?
)