package com.chin.stockanalysis.agent.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.ai.StockAnalyzerService
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.data.StrategyDataFeed
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 股票分析 Agent（Plan-and-Execute 模式）
 *
 * 替代現有的 StockAnalyzerService，實現動態單股深度分析：
 * 1. 規劃分析步驟（技術面 → 基本面 → 資金面 → 新聞面 → 綜合評估）
 * 2. 每步可調用不同工具或子 Agent
 * 3. 最後匯總給出買賣建議
 */
class StockAnalysisAgent(context: Context) : AgentBase(
    id = "stock_analysis",
    name = "股票分析 Agent",
    description = "對單只股票進行多維度深度分析，給出買賣建議",
    context = context
) {
    companion object {
        private const val TAG = "StockAnalysisAgent"
    }

    init {
        registerTool(TechnicalAnalysisTool(context))
        registerTool(FundamentalAnalysisTool(context))
        registerTool(FundFlowTool(context))
        registerTool(NewsAnalysisTool(context))
        registerTool(SectorPositionTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位專業的 A 股股票分析師 Agent，擅長多維度深度分析單只股票。

        ## 分析框架
        1. 技術面：均線排列、支撐壓力、量能變化、形態識別
        2. 基本面：主營業務、產業鏈地位、財務健康度
        3. 資金面：主力流向、換手率、大單動向
        4. 新聞面：近期利好利空、催化事件、輿情強度
        5. 板塊面：所屬板塊熱度、龍頭地位、輪動節奏

        ## 輸出格式
        {
          "overall_score": 75,
          "recommendation": "BUY|HOLD|SELL|WATCH",
          "confidence": "HIGH|MEDIUM|LOW",
          "technical": {"trend": "UP", "support": "1500", "resistance": "1600", "score": 80},
          "fundamental": {"business": "白酒龍頭", "moat": "強", "score": 85},
          "fund_flow": {"main_force": "流入", "turnover": "2.1%", "score": 70},
          "news": {"sentiment": "偏多", "catalyst": "提價預期", "risk": "消費疲軟", "score": 75},
          "sector": {"heat": "中", "position": "龍頭", "score": 80},
          "reasoning": "...",
          "risk_factors": ["..."],
          "target_price": "1650",
          "stop_loss": "1450"
        }
    """.trimIndent()

    /**
     * 分析單只股票
     */
    suspend fun analyze(
        stockCode: String,
        onProgress: ((String) -> Unit)? = null
    ): StockAnalysisResult {
        onProgress?.invoke("🔍 啟動分析 Agent...")

        val ctx = AgentContext().apply {
            put("stock_code", stockCode)
            put("date", TradingDayPickerView.recentTradingDay().toString())
        }

        val result = planAndExecute(
            goal = "對股票 $stockCode 進行全面深度分析，給出買賣建議和目標價/止損位",
            ctx = ctx,
            maxSteps = 8
        )

        return parseAnalysisResult(stockCode, result)
    }

    private fun parseAnalysisResult(code: String, result: AgentResult): StockAnalysisResult {
        return try {
            val json = org.json.JSONObject(result.output)
            StockAnalysisResult(
                success = result.success,
                stockCode = code,
                overallScore = json.optInt("overall_score", 0),
                recommendation = json.optString("recommendation", "WATCH"),
                confidence = json.optString("confidence", "LOW"),
                technicalScore = json.optJSONObject("technical")?.optInt("score", 0) ?: 0,
                fundamentalScore = json.optJSONObject("fundamental")?.optInt("score", 0) ?: 0,
                fundFlowScore = json.optJSONObject("fund_flow")?.optInt("score", 0) ?: 0,
                newsScore = json.optJSONObject("news")?.optInt("score", 0) ?: 0,
                sectorScore = json.optJSONObject("sector")?.optInt("score", 0) ?: 0,
                reasoning = json.optString("reasoning", ""),
                riskFactors = json.optJSONArray("risk_factors")?.let {
                    (0 until it.length()).map { i -> it.getString(i) }
                } ?: emptyList(),
                targetPrice = json.optString("target_price", ""),
                stopLoss = json.optString("stop_loss", ""),
                rawOutput = result.output,
                steps = result.steps
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析分析結果失敗: ${e.message}")
            StockAnalysisResult(
                success = result.success,
                stockCode = code,
                rawOutput = result.output,
                steps = result.steps
            )
        }
    }
}

/** 分析結果 */
data class StockAnalysisResult(
    val success: Boolean,
    val stockCode: String,
    val overallScore: Int = 0,
    val recommendation: String = "WATCH",
    val confidence: String = "LOW",
    val technicalScore: Int = 0,
    val fundamentalScore: Int = 0,
    val fundFlowScore: Int = 0,
    val newsScore: Int = 0,
    val sectorScore: Int = 0,
    val reasoning: String = "",
    val riskFactors: List<String> = emptyList(),
    val targetPrice: String = "",
    val stopLoss: String = "",
    val rawOutput: String = "",
    val steps: Int = 0
)

/** ================================================================ */
/** 技術分析工具 */
class TechnicalAnalysisTool(private val ctx: Context) : AgentTool {
    override val name = "technical_analysis"
    override val description = "分析股票技術面（均線、K線、成交量、技術指標）"
    override val parameters = listOf("stock_code", "days")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val days = params["days"]?.toIntOrNull() ?: 30
                val db = StockDatabase.getInstance(ctx)
                val snapshots = db.dailySnapshotDao().getRecentByCode(code, days)

                if (snapshots.size < 5) return@withContext "數據不足（僅 ${snapshots.size} 天）"

                val latest = snapshots.first()
                val prices = snapshots.map { it.close }
                val volumes = snapshots.map { it.volume }

                // 簡單技術指標計算
                val ma5 = prices.take(5).average()
                val ma10 = prices.take(10).average()
                val ma20 = prices.take(20).average()
                val avgVolume = volumes.average()
                val latestVolume = volumes.first()

                val trend = when {
                    prices.first() > prices.last() * 1.05 -> "上升趨勢"
                    prices.first() < prices.last() * 0.95 -> "下降趨勢"
                    else -> "震蕩整理"
                }

                buildString {
                    appendLine("【技術面分析】 $code")
                    appendLine("- 當前價: ${latest.close} (${if(latest.changePct>=0)"+" else ""}${"%.2f".format(latest.changePct)}%)")
                    appendLine("- MA5: ${"%.2f".format(ma5)}, MA10: ${"%.2f".format(ma10)}, MA20: ${"%.2f".format(ma20)}")
                    appendLine("- 趨勢: $trend")
                    appendLine("- 量能: ${if(latestVolume > avgVolume * 1.5) "放量" else if(latestVolume < avgVolume * 0.7) "縮量" else "正常"} (${"%.0f".format(latestVolume/avgVolume*100)}% 均量)")
                    appendLine("- 換手率: ${"%.2f".format(latest.turnover)}%")
                    appendLine("- 主力淨流入: ${"%.2f".format(latest.mainForceNetInflow)}萬")
                }
            } catch (e: Exception) {
                "錯誤: 技術分析失敗: ${e.message}"
            }
        }
    }
}

/** 基本面分析工具 */
class FundamentalAnalysisTool(private val ctx: Context) : AgentTool {
    override val name = "fundamental_analysis"
    override val description = "分析股票基本面（主營業務、產業鏈、財務）"
    override val parameters = listOf("stock_code")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val db = StockDatabase.getInstance(ctx)
                val basic = db.stockBasicDao().getByCode(code)

                if (basic == null) return@withContext "未找到股票基本信息"

                val sectors = db.sectorStockDao().getSectorsByStockCode(code)

                buildString {
                    appendLine("【基本面分析】 $code ${basic.name}")
                    appendLine("- 主營業務: ${basic.mainBusiness}")
                    appendLine("- 所屬板塊: ${sectors.joinToString { it.sectorName }}")
                    appendLine("- 產業鏈邏輯: ${basic.chainLogic}")
                }
            } catch (e: Exception) {
                "錯誤: 基本面分析失敗: ${e.message}"
            }
        }
    }
}

/** 資金流向工具 */
class FundFlowTool(private val ctx: Context) : AgentTool {
    override val name = "fund_flow"
    override val description = "分析資金流向和主力動向"
    override val parameters = listOf("stock_code", "days")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val days = params["days"]?.toIntOrNull() ?: 5
                val db = StockDatabase.getInstance(ctx)
                val snapshots = db.dailySnapshotDao().getRecentByCode(code, days)

                val totalInflow = snapshots.sumOf { it.mainForceNetInflow }
                val avgTurnover = snapshots.map { it.turnover }.average()

                buildString {
                    appendLine("【資金面分析】 $code（近 $days 日）")
                    appendLine("- 主力淨流入合計: ${"%.2f".format(totalInflow)}萬")
                    appendLine("- 平均換手率: ${"%.2f".format(avgTurnover)}%")
                    appendLine("- 資金趨勢: ${if(totalInflow > 0) "流入" else "流出"}")
                }
            } catch (e: Exception) {
                "錯誤: 資金分析失敗: ${e.message}"
            }
        }
    }
}

/** 新聞分析工具 */
class NewsAnalysisTool(private val ctx: Context) : AgentTool {
    override val name = "news_analysis"
    override val description = "分析股票相關新聞的利好利空"
    override val parameters = listOf("stock_code", "limit")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val limit = params["limit"]?.toIntOrNull() ?: 10
                val db = StockDatabase.getInstance(ctx)
                val news = db.newsFactorDao().getActiveByStockCode(code, limit)

                if (news.isEmpty()) return@withContext "暫無相關新聞"

                val good = news.filter { it.sentiment == "利好" }
                val bad = news.filter { it.sentiment == "利空" }

                buildString {
                    appendLine("【新聞面分析】 $code（近 ${news.size} 條）")
                    appendLine("- 利好: ${good.size} 條, 利空: ${bad.size} 條")
                    if (good.isNotEmpty()) {
                        appendLine("- 最新利好: ${good.first().title}（強度 ${good.first().strength}）")
                    }
                    if (bad.isNotEmpty()) {
                        appendLine("- 最新利空: ${bad.first().title}（強度 ${bad.first().strength}）")
                    }
                }
            } catch (e: Exception) {
                "錯誤: 新聞分析失敗: ${e.message}"
            }
        }
    }
}

/** 板塊地位工具 */
class SectorPositionTool(private val ctx: Context) : AgentTool {
    override val name = "sector_position"
    override val description = "分析股票在所屬板塊中的地位和板塊熱度"
    override val parameters = listOf("stock_code")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val db = StockDatabase.getInstance(ctx)
                val sectors = db.sectorStockDao().getSectorsByStockCode(code)

                if (sectors.isEmpty()) return@withContext "未找到所屬板塊"

                val today = TradingDayPickerView.recentTradingDay().toString()
                val sectorInfo = sectors.first()
                val sectorRecord = db.sectorDailyRecordDao().getByDateAndSector(today, sectorInfo.sectorName)

                buildString {
                    appendLine("【板塊分析】 $code → ${sectorInfo.sectorName}")
                    appendLine("- 板塊今日漲幅: ${sectorRecord?.let { "${"%.2f".format(it.avgChange)}%" } ?: "無數據"}")
                    appendLine("- 板塊股票數: ${sectorInfo.stockCount}")
                    appendLine("- 板塊內排名: 待計算")
                }
            } catch (e: Exception) {
                "錯誤: 板塊分析失敗: ${e.message}"
            }
        }
    }
}
