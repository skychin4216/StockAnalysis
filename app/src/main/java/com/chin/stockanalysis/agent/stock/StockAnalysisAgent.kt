package com.chin.stockanalysis.agent.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 股票分析 Agent（Plan-and-Execute 模式）
 *
 * 對單只股票進行多維度深度分析，給出買賣建議。
 * 工具：
 * - TechnicalAnalysisTool: 技術面
 * - FundamentalAnalysisTool: 基本面
 * - FundFlowTool: 資金面
 */
class StockAnalysisAgent(context: Context) : AgentBase(
    id = "stock_analysis",
    name = "股票分析 Agent",
    description = "對單只股票進行多維度深度分析，給出買賣建議",
    context = context
) {
    companion object { private const val TAG = "StockAnalysisAgent" }

    init {
        registerTool(TechnicalAnalysisTool(context))
        registerTool(FundamentalAnalysisTool(context))
        registerTool(FundFlowTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位專業的 A 股股票分析師 Agent，擅長多維度深度分析單只股票。

        ## 分析框架
        1. 技術面：均線排列、支撐壓力、量能變化
        2. 基本面：主營業務、產業鏈地位
        3. 資金面：主力流向、換手率

        ## 輸出格式
        {
          "overall_score": 75,
          "recommendation": "BUY|HOLD|SELL|WATCH",
          "confidence": "HIGH|MEDIUM|LOW",
          "technical_score": 80,
          "fundamental_score": 85,
          "fund_flow_score": 70,
          "reasoning": "...",
          "risk_factors": ["..."],
          "target_price": "1650",
          "stop_loss": "1450"
        }
    """.trimIndent()

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

        return try {
            val json = org.json.JSONObject(result.output)
            StockAnalysisResult(
                success = result.success,
                stockCode = stockCode,
                overallScore = json.optInt("overall_score", 0),
                recommendation = json.optString("recommendation", "WATCH"),
                confidence = json.optString("confidence", "LOW"),
                technicalScore = json.optInt("technical_score", 0),
                fundamentalScore = json.optInt("fundamental_score", 0),
                fundFlowScore = json.optInt("fund_flow_score", 0),
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
                stockCode = stockCode,
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
    override val description = "分析股票技術面（均線、K線、成交量）"
    override val parameters = listOf("stock_code", "days")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val days = params["days"]?.toIntOrNull() ?: 30
                val db = StockDatabase.getInstance(c)
                val snapshots = db.dailySnapshotDao().getByCode(code, days)

                if (snapshots.isEmpty()) return@withContext "沒有歷史數據"
                if (snapshots.size < 5) return@withContext "數據不足（僅 ${snapshots.size} 條）"

                val latest = snapshots.first()
                val prices = snapshots.map { it.close }
                val volumes = snapshots.map { it.volume }

                val ma5 = prices.take(5).average()
                val ma10 = prices.take(10).average()
                val ma20 = prices.take(coerceAtMost(20, prices.size)).average()
                val avgVolume = volumes.average()
                val latestVolume = volumes.first()

                val trend = when {
                    prices.first() > prices.last() * 1.05 -> "上升趨勢"
                    prices.first() < prices.last() * 0.95 -> "下降趨勢"
                    else -> "震蕩整理"
                }

                buildString {
                    appendLine("【技術面分析】 $code")
                    appendLine("- 當前價: ${latest.close} " +
                        "(${if (latest.changePct >= 0) "+" else ""}" +
                        "${"%.2f".format(latest.changePct)}%)")
                    appendLine("- MA5: ${"%.2f".format(ma5)}, " +
                        "MA10: ${"%.2f".format(ma10)}, " +
                        "MA20: ${"%.2f".format(ma20)}")
                    appendLine("- 趨勢: $trend")
                    appendLine("- 量能: ${
                        if (latestVolume > avgVolume * 1.5) "放量"
                        else if (latestVolume < avgVolume * 0.7) "縮量"
                        else "正常"
                    } (${"%.0f".format(latestVolume / avgVolume * 100)}% 均量)")
                    appendLine("- 換手率: ${"%.2f".format(latest.turnoverRate)}%")
                    appendLine("- 主力淨流入: ${"%.2f".format(latest.mainNetInflow)}萬")
                }
            } catch (e: Exception) {
                "錯誤: 技術分析失敗: ${e.message}"
            }
        }
    }

    private fun coerceAtMost(max: Int, size: Int): Int = if (max > size) size else max
}

/** 基本面分析工具 */
class FundamentalAnalysisTool(private val ctx: Context) : AgentTool {
    override val name = "fundamental_analysis"
    override val description = "分析股票基本面（主營業務、產業鏈）"
    override val parameters = listOf("stock_code")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val db = StockDatabase.getInstance(c)
                val basic = db.stockBasicDao().getByCode(code)
                    ?: return@withContext "未找到股票基本信息"

                val sectorNames = db.sectorStockDao().getSectorNamesByStockCode(code)

                buildString {
                    appendLine("【基本面分析】 $code ${basic.name}")
                    appendLine("- 主營業務: ${basic.business}")
                    if (sectorNames.isNotEmpty()) {
                        appendLine("- 所屬板塊: ${sectorNames.joinToString(", ")}")
                    }
                    if (basic.chainRationale.isNotBlank()) {
                        appendLine("- 產業鏈邏輯: ${basic.chainRationale}")
                    }
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

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val days = params["days"]?.toIntOrNull() ?: 5
                val db = StockDatabase.getInstance(c)
                val snapshots = db.dailySnapshotDao().getByCode(code, days)

                if (snapshots.isEmpty()) return@withContext "沒有資金流向數據"

                val totalInflow = snapshots.sumOf { it.mainNetInflow }
                val avgTurnover = snapshots.map { it.turnoverRate }.average()

                buildString {
                    appendLine("【資金面分析】 $code（近 $days 日）")
                    appendLine("- 主力淨流入合計: ${"%.2f".format(totalInflow)}萬")
                    appendLine("- 平均換手率: ${"%.2f".format(avgTurnover)}%")
                    appendLine("- 資金趨勢: ${if (totalInflow > 0) "流入" else "流出"}")
                }
            } catch (e: Exception) {
                "錯誤: 資金分析失敗: ${e.message}"
            }
        }
    }
}