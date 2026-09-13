package com.chin.stockanalysis.agent.news

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.news.HotSectorNewsUpdater
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 新闻监控 Agent（ReAct 模式）
 *
 * 替代现有的 HotSectorNewsUpdater 定时任务，实现主动新闻监控：
 * 1. 监控热门板块新闻动态
 * 2. 评估新闻对股票的影响
 * 3. 发现重大利好/利空时主动提醒
 * 4. 持续跟踪重要事件进展
 */
class NewsMonitoringAgent(context: Context) : AgentBase(
    id = "news_monitor",
    name = "新闻监控 Agent",
    description = "主动监控市场新闻，评估影响，发现重大事件时主动提醒",
    context = context
) {
    companion object {
        private const val TAG = "NewsMonitoringAgent"
    }

    init {
        registerTool(FetchSectorNewsTool(context))
        registerTool(NewsImpactTool(context))
        registerTool(TrackEventTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位专业的市场新闻分析 Agent，负责监控和评估市场新闻对股票的影响。

        ## 核心能力
        1. 主动发现热门板块的最新新闻
        2. 评估单条新闻的利好/利空程度和影响范围
        3. 识别重大催化事件（政策、业绩、并购、技术突破等）
        4. 跟踪重要事件的后续进展
        5. 给出投资建议（关注/规避/无影响）

        ## 评估维度
        - 情感: 利好 / 利空 / 中性
        - 强度: 1-100（100 为极重大）
        - 影响范围: 个股 / 板块 / 全市场
        - 持续性: 一次性 / 短期 / 中期 / 长期
        - 确定性: 传闻 / 预期 / 确认

        ## 输出格式
        {
          "news_items": [
            {
              "title": "...",
              "sentiment": "利好",
              "strength": 75,
              "scope": "板块",
              "duration": "中期",
              "certainty": "确认",
              "affected_stocks": ["600519", "000858"],
              "recommendation": "关注"
            }
          ],
          "summary": "..."
        }
    """.trimIndent()

    /**
     * 监控指定板块的新闻
     */
    suspend fun monitorSector(
        sectorName: String? = null,
        onAlert: ((String) -> Unit)? = null
    ): NewsMonitorResult {
        val ctx = AgentContext().apply {
            sectorName?.let { put("sector", it) }
            put("date", TradingDayPickerView.recentTradingDay().toString())
        }

        val result = react(
            input = sectorName?.let { "监控 $it 板块的最新新闻动态，评估对相关股票的影响" }
                ?: "监控当前热门板块的最新新闻动态",
            ctx = ctx,
            maxSteps = 5
        )

        return parseMonitorResult(result)
    }

    /**
     * 评估单条新闻对股票的影响
     */
    suspend fun assessNewsImpact(
        newsTitle: String,
        stockCodes: List<String>
    ): NewsImpactResult {
        val ctx = AgentContext().apply {
            put("news_title", newsTitle)
            put("stock_codes", stockCodes)
        }

        val result = react(
            input = "评估新闻『$newsTitle』对股票 ${stockCodes.joinToString()} 的影响",
            ctx = ctx,
            maxSteps = 3
        )

        return NewsImpactResult(
            success = result.success,
            assessment = result.output,
            steps = result.steps
        )
    }

    private fun parseMonitorResult(result: AgentResult): NewsMonitorResult {
        return try {
            val json = org.json.JSONObject(result.output)
            val items = mutableListOf<NewsAssessment>()
            json.optJSONArray("news_items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    items.add(
                        NewsAssessment(
                            title = obj.getString("title"),
                            sentiment = obj.optString("sentiment", "中性"),
                            strength = obj.optInt("strength", 50),
                            scope = obj.optString("scope", "个股"),
                            duration = obj.optString("duration", "短期"),
                            certainty = obj.optString("certainty", "传闻"),
                            affectedStocks = obj.optJSONArray("affected_stocks")?.let {
                                (0 until it.length()).map { idx -> it.getString(idx) }
                            } ?: emptyList(),
                            recommendation = obj.optString("recommendation", "观察")
                        )
                    )
                }
            }
            NewsMonitorResult(
                success = result.success,
                items = items,
                summary = json.optString("summary", ""),
                rawOutput = result.output
            )
        } catch (e: Exception) {
            NewsMonitorResult(success = result.success, rawOutput = result.output)
        }
    }
}

/** 新闻监控结果 */
data class NewsMonitorResult(
    val success: Boolean,
    val items: List<NewsAssessment> = emptyList(),
    val summary: String = "",
    val rawOutput: String = ""
)

/** 单条新闻评估 */
data class NewsAssessment(
    val title: String,
    val sentiment: String,
    val strength: Int,
    val scope: String,
    val duration: String,
    val certainty: String,
    val affectedStocks: List<String>,
    val recommendation: String
)

/** 新闻影响评估结果 */
data class NewsImpactResult(
    val success: Boolean,
    val assessment: String,
    val steps: Int = 0
)

/** ================================================================ */
/** 获取板块新闻工具 */
class FetchSectorNewsTool(private val ctx: Context) : AgentTool {
    override val name = "fetch_sector_news"
    override val description = "获取指定板块或热门板块的最新新闻"
    override val parameters = listOf("sector", "limit")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val sector = params["sector"]
                val limit = params["limit"]?.toIntOrNull() ?: 10
                val db = StockDatabase.getInstance(c)

                val news = if (sector != null) {
                    db.newsFactorDao().getActiveBySector(sector, limit)
                } else {
                    db.newsFactorDao().getActiveBySector("", limit)
                }

                if (news.isEmpty()) return@withContext "暂无相关新闻"

                buildString {
                    appendLine("【新闻列表】${sector ?: "热门板块"}（${news.size}条）")
                    news.forEachIndexed { i, n ->
                        val sentimentLabel = when {
                            n.sentiment > 0 -> "利好"
                            n.sentiment < 0 -> "利空"
                            else -> "中性"
                        }
                        appendLine("${i + 1}. [$sentimentLabel] ${n.title}")
                    }
                }
            } catch (e: Exception) {
                "错误: 获取新闻失败: ${e.message}"
            }
        }
    }
}

/** 新闻影响评估工具 */
class NewsImpactTool(private val ctx: Context) : AgentTool {
    override val name = "news_impact"
    override val description = "评估新闻对指定股票的影响程度"
    override val parameters = listOf("news_title", "stock_codes")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val title = params["news_title"] ?: return@withContext "错误: 缺少新闻标题"
                val codes = params["stock_codes"]?.split(",")?.map { it.trim() } ?: emptyList()
                val db = StockDatabase.getInstance(c)

                val impacts = mutableListOf<String>()
                for (code in codes) {
                    val basic = db.stockBasicDao().getByCode(code)
                    val related = basic?.business?.let { business ->
                        title.contains(business) || business.contains(title.take(10))
                    } ?: false
                    impacts.add("$code(${basic?.name ?: "未知"}): ${if (related) "直接相关" else "间接相关"}")
                }

                buildString {
                    appendLine("【新闻影响评估】$title")
                    impacts.forEach { appendLine("- $it") }
                }
            } catch (e: Exception) {
                "错误: 评估失败: ${e.message}"
            }
        }
    }
}

/** 事件跟踪工具 */
class TrackEventTool(private val ctx: Context) : AgentTool {
    override val name = "track_event"
    override val description = "跟踪重要事件的后续进展"
    override val parameters = listOf("event_keyword", "days")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val keyword = params["event_keyword"] ?: return@withContext "错误: 缺少事件关键词"
                val days = params["days"]?.toIntOrNull() ?: 7
                val db = StockDatabase.getInstance(c)

                // 取最近 50 条新闻，然后手动过滤关键词
                val recentNews = db.newsFactorDao().getActiveBySector("", 50)
                val filtered = recentNews.filter { it.title.contains(keyword, ignoreCase = true) }

                if (filtered.isEmpty()) return@withContext "未找到相关事件进展"

                buildString {
                    appendLine("【事件跟踪】$keyword（近 $days 天）")
                    filtered.forEach { n ->
                        val sentimentLabel = when {
                            n.sentiment > 0 -> "利好"
                            n.sentiment < 0 -> "利空"
                            else -> "中性"
                        }
                        appendLine("- ${n.newsDate}: [$sentimentLabel] ${n.title}")
                    }
                }
            } catch (e: Exception) {
                "错误: 跟踪失败: ${e.message}"
            }
        }
    }
}
