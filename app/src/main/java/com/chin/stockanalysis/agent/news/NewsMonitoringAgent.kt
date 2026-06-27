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
 * ## 新聞監控 Agent（ReAct 模式）
 *
 * 替代現有的 HotSectorNewsUpdater 定時任務，實現主動新聞監控：
 * 1. 監控熱門板塊新聞動態
 * 2. 評估新聞對股票的影響
 * 3. 發現重大利好/利空時主動提醒
 * 4. 持續跟蹤重要事件進展
 */
class NewsMonitoringAgent(context: Context) : AgentBase(
    id = "news_monitor",
    name = "新聞監控 Agent",
    description = "主動監控市場新聞，評估影響，發現重大事件時主動提醒",
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
        你是一位專業的市場新聞分析 Agent，負責監控和評估市場新聞對股票的影響。

        ## 核心能力
        1. 主動發現熱門板塊的最新新聞
        2. 評估單條新聞的利好/利空程度和影響範圍
        3. 識別重大催化事件（政策、業績、併購、技術突破等）
        4. 跟蹤重要事件的後續進展
        5. 給出投資建議（關注/規避/無影響）

        ## 評估維度
        - 情感: 利好 / 利空 / 中性
        - 強度: 1-100（100 為極重大）
        - 影響範圍: 個股 / 板塊 / 全市場
        - 持續性: 一次性 / 短期 / 中期 / 長期
        - 確定性: 傳聞 / 預期 / 確認

        ## 輸出格式
        {
          "news_items": [
            {
              "title": "...",
              "sentiment": "利好",
              "strength": 75,
              "scope": "板塊",
              "duration": "中期",
              "certainty": "確認",
              "affected_stocks": ["600519", "000858"],
              "recommendation": "關注"
            }
          ],
          "summary": "..."
        }
    """.trimIndent()

    /**
     * 監控指定板塊的新聞
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
            input = sectorName?.let { "監控 $it 板塊的最新新聞動態，評估對相關股票的影響" }
                ?: "監控當前熱門板塊的最新新聞動態",
            ctx = ctx,
            maxSteps = 5
        )

        return parseMonitorResult(result)
    }

    /**
     * 評估單條新聞對股票的影響
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
            input = "評估新聞『$newsTitle』對股票 ${stockCodes.joinToString()} 的影響",
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
                            scope = obj.optString("scope", "個股"),
                            duration = obj.optString("duration", "短期"),
                            certainty = obj.optString("certainty", "傳聞"),
                            affectedStocks = obj.optJSONArray("affected_stocks")?.let {
                                (0 until it.length()).map { idx -> it.getString(idx) }
                            } ?: emptyList(),
                            recommendation = obj.optString("recommendation", "觀察")
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

/** 新聞監控結果 */
data class NewsMonitorResult(
    val success: Boolean,
    val items: List<NewsAssessment> = emptyList(),
    val summary: String = "",
    val rawOutput: String = ""
)

/** 單條新聞評估 */
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

/** 新聞影響評估結果 */
data class NewsImpactResult(
    val success: Boolean,
    val assessment: String,
    val steps: Int = 0
)

/** ================================================================ */
/** 獲取板塊新聞工具 */
class FetchSectorNewsTool(private val ctx: Context) : AgentTool {
    override val name = "fetch_sector_news"
    override val description = "獲取指定板塊或熱門板塊的最新新聞"
    override val parameters = listOf("sector", "limit")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val sector = params["sector"]
                val limit = params["limit"]?.toIntOrNull() ?: 10
                val db = StockDatabase.getInstance(ctx)

                val news = if (sector != null) {
                    db.newsFactorDao().getActiveBySector(sector, limit)
                } else {
                    db.newsFactorDao().getLatestActive(limit)
                }

                if (news.isEmpty()) return@withContext "暫無相關新聞"

                buildString {
                    appendLine("【新聞列表】${sector ?: "熱門板塊"}（${news.size}條）")
                    news.forEachIndexed { i, n ->
                        appendLine("${i + 1}. [${n.sentiment}] ${n.title}（強度 ${n.strength}）")
                    }
                }
            } catch (e: Exception) {
                "錯誤: 獲取新聞失敗: ${e.message}"
            }
        }
    }
}

/** 新聞影響評估工具 */
class NewsImpactTool(private val ctx: Context) : AgentTool {
    override val name = "news_impact"
    override val description = "評估新聞對指定股票的影響程度"
    override val parameters = listOf("news_title", "stock_codes")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val title = params["news_title"] ?: return@withContext "錯誤: 缺少新聞標題"
                val codes = params["stock_codes"]?.split(",")?.map { it.trim() } ?: emptyList()
                val db = StockDatabase.getInstance(ctx)

                val impacts = mutableListOf<String>()
                for (code in codes) {
                    val basic = db.stockBasicDao().getByCode(code)
                    val related = basic?.mainBusiness?.let { business ->
                        title.contains(business) || business.contains(title.take(10))
                    } ?: false
                    impacts.add("$code(${basic?.name ?: "未知"}): ${if (related) "直接相關" else "間接相關"}")
                }

                buildString {
                    appendLine("【新聞影響評估】$title")
                    impacts.forEach { appendLine("- $it") }
                }
            } catch (e: Exception) {
                "錯誤: 評估失敗: ${e.message}"
            }
        }
    }
}

/** 事件跟蹤工具 */
class TrackEventTool(private val ctx: Context) : AgentTool {
    override val name = "track_event"
    override val description = "跟蹤重要事件的後續進展"
    override val parameters = listOf("event_keyword", "days")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val keyword = params["event_keyword"] ?: return@withContext "錯誤: 缺少事件關鍵詞"
                val days = params["days"]?.toIntOrNull() ?: 7
                val db = StockDatabase.getInstance(ctx)

                // 簡單實現：搜索標題包含關鍵詞的新聞
                val news = db.newsFactorDao().searchByKeyword(keyword, days)

                if (news.isEmpty()) return@withContext "未找到相關事件進展"

                buildString {
                    appendLine("【事件跟蹤】$keyword（近 $days 天）")
                    news.forEach { n ->
                        appendLine("- ${n.newsDate}: [${n.sentiment}] ${n.title}")
                    }
                }
            } catch (e: Exception) {
                "錯誤: 跟蹤失敗: ${e.message}"
            }
        }
    }
}
