package com.chin.stockanalysis.agent.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.data.StrategyDataFeed
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * ## 選股 Agent（ReAct 模式）
 *
 * 根據市場環境動態選擇策略組合，執行智能選股。
 * 工具註冊：
 * - MarketSnapshotTool: 大盤快照
 * - StrategyScanTool: 策略掃描
 */
class StockPickingAgent(context: Context) : AgentBase(
    id = "stock_picking",
    name = "選股 Agent",
    description = "根據市場環境動態選擇策略組合，執行智能選股",
    context = context
) {
    companion object { private const val TAG = "StockPickingAgent" }

    init {
        registerTool(MarketSnapshotTool(context))
        registerTool(StrategyScanTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位專業的 A 股選股 Agent，擅長根據市場環境動態調整選股策略。

        ## 核心能力
        1. 分析大盤環境
        2. 從策略庫中動態選擇最合適的組合
        3. 執行策略掃描
        4. 給出 Top 10 推薦股票及理由

        ## 輸出格式
        {
          "recommendations": [
            {"code": "600519", "name": "貴州茅台", "strategies": ["MA突破"], "score": 85, "reason": "..."}
          ],
          "market_assessment": "...",
          "risk_warning": "..."
        }
    """.trimIndent()

    suspend fun pickStocks(
        date: String? = null,
        onlyMainBoard: Boolean = true,
        maxResults: Int = 10,
        onProgress: ((String) -> Unit)? = null
    ): StockPickingResult {
        val tradingDay = date ?: TradingDayPickerView.recentTradingDay()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        onProgress?.invoke("🤖 選股 Agent 啟動...")

        val ctx = AgentContext().apply {
            put("date", tradingDay)
            put("only_main_board", onlyMainBoard)
            put("max_results", maxResults)
        }

        val result = react(
            input = "請為 $tradingDay 選出 $maxResults 只最值得關注的 A 股股票。" +
                    if (onlyMainBoard) "僅考慮主板股票。" else "",
            ctx = ctx,
            maxSteps = 6
        )

        return try {
            val json = org.json.JSONObject(result.output)
            val recs = mutableListOf<StockRecommendation>()
            val arr = json.optJSONArray("recommendations")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    recs.add(StockRecommendation(
                        code = obj.getString("code"),
                        name = obj.getString("name"),
                        strategies = obj.optJSONArray("strategies")?.let {
                            (0 until it.length()).map { idx -> it.getString(idx) }
                        } ?: emptyList(),
                        score = obj.optInt("score", 0),
                        reason = obj.optString("reason", "")
                    ))
                }
            }
            StockPickingResult(
                success = result.success,
                recommendations = recs,
                marketAssessment = json.optString("market_assessment", ""),
                riskWarning = json.optString("risk_warning", ""),
                rawOutput = result.output,
                steps = result.steps
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析選股結果失敗: ${e.message}")
            StockPickingResult(
                success = result.success,
                rawOutput = result.output,
                steps = result.steps
            )
        }
    }
}

/** 選股結果 */
data class StockPickingResult(
    val success: Boolean,
    val recommendations: List<StockRecommendation> = emptyList(),
    val marketAssessment: String = "",
    val riskWarning: String = "",
    val rawOutput: String = "",
    val steps: Int = 0
)

/** 單只股票推薦 */
data class StockRecommendation(
    val code: String,
    val name: String,
    val strategies: List<String>,
    val score: Int,
    val reason: String
)

/** ================================================================ */
/** 工具 1: 市場快照 */
class MarketSnapshotTool(private val ctx: Context) : AgentTool {
    override val name = "market_snapshot"
    override val description = "獲取當日大盤快照數據"
    override val parameters = listOf("date")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val date = params["date"]
                    ?: TradingDayPickerView.recentTradingDay().toString()
                val db = StockDatabase.getInstance(c)
                val marketData = db.dailySnapshotDao().getByDate(date)

                if (marketData.isEmpty()) return@withContext "暫無市場數據"

                val avgChange = marketData.map { it.changePct }.average()
                val upCount = marketData.count { it.changePct > 0 }
                val downCount = marketData.count { it.changePct < 0 }

                buildString {
                    appendLine("【大盤環境】 $date")
                    appendLine("- 平均漲跌幅: ${"%.2f".format(avgChange)}%")
                    appendLine("- 上漲家數: $upCount, 下跌家數: $downCount")
                    appendLine("- 市場情緒: ${
                        if (avgChange > 1) "強勢"
                        else if (avgChange > 0) "偏多"
                        else if (avgChange > -1) "偏弱"
                        else "弱勢"
                    }")
                }
            } catch (e: Exception) {
                "錯誤: 獲取市場快照失敗: ${e.message}"
            }
        }
    }
}

/** 工具 2: 策略掃描 */
class StrategyScanTool(private val ctx: Context) : AgentTool {
    override val name = "strategy_scan"
    override val description = "執行指定策略掃描全市場或指定股票池"
    override val parameters = listOf("strategy_ids", "date", "stock_codes")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val date = params["date"]
                    ?: TradingDayPickerView.recentTradingDay().toString()
                val strategyIds = params["strategy_ids"]?.split(",")?.map { it.trim() }
                    ?: StrategyEngineHolder.get().getStrategies().map { it.id }
                val stockCodes = params["stock_codes"]?.split(",")?.map { it.trim() }?.toSet()

                val feed = StrategyDataFeed(c)
                val config = StrategyDataFeed.DataFeedConfig(
                    onlyMainBoard = true,
                    stockCodes = stockCodes
                )
                val stocks = feed.prepareFromDb(date, config)

                val engine = StrategyEngineHolder.get()
                val results = mutableListOf<String>()

                for (strategyId in strategyIds) {
                    val strategy = engine.getStrategies().find { it.id == strategyId } ?: continue
                    if (!engine.isEnabled(strategy.id)) continue

                    val result = strategy.screenWithData(stocks)
                    result.getOrNull()?.signals?.let { signals ->
                        val top = signals.sortedByDescending { it.strength }.take(5)
                        results.add(
                            "${strategy.name}: ${signals.size}只命中, " +
                            "Top5: ${top.joinToString { "${it.stockCode}(${it.strength}分)" }}"
                        )
                    }
                }

                results.joinToString("\n")
            } catch (e: Exception) {
                "錯誤: 策略掃描失敗: ${e.message}"
            }
        }
    }
}