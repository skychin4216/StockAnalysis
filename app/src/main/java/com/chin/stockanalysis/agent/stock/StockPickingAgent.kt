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
 * ## 选股 Agent（ReAct 模式）
 *
 * 根据市场环境动态选择策略组合，执行智能选股。
 * 工具注册：
 * - MarketSnapshotTool: 大盘快照
 * - StrategyScanTool: 策略扫描
 */
class StockPickingAgent(context: Context) : AgentBase(
    id = "stock_picking",
    name = "选股 Agent",
    description = "根据市场环境动态选择策略组合，执行智能选股",
    context = context
) {
    companion object { private const val TAG = "StockPickingAgent" }

    init {
        registerTool(MarketSnapshotTool(context))
        registerTool(StrategyScanTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位专业的 A 股选股 Agent，擅长根据市场环境动态调整选股策略。

        ## 核心能力
        1. 分析大盘环境
        2. 从策略库中动态选择最合适的组合
        3. 执行策略扫描
        4. 给出 Top 10 推荐股票及理由

        ## 输出格式
        {
          "recommendations": [
            {"code": "600519", "name": "贵州茅台", "strategies": ["MA突破"], "score": 85, "reason": "..."}
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

        onProgress?.invoke("🤖 选股 Agent 启动...")

        val ctx = AgentContext().apply {
            put("date", tradingDay)
            put("only_main_board", onlyMainBoard)
            put("max_results", maxResults)
        }

        val result = react(
            input = "请为 $tradingDay 选出 $maxResults 只最值得关注的 A 股股票。" +
                    if (onlyMainBoard) "仅考虑主板股票。" else "",
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
            Log.w(TAG, "解析选股结果失败: ${e.message}")
            StockPickingResult(
                success = result.success,
                rawOutput = result.output,
                steps = result.steps
            )
        }
    }
}

/** 选股结果 */
data class StockPickingResult(
    val success: Boolean,
    val recommendations: List<StockRecommendation> = emptyList(),
    val marketAssessment: String = "",
    val riskWarning: String = "",
    val rawOutput: String = "",
    val steps: Int = 0
)

/** 单只股票推荐 */
data class StockRecommendation(
    val code: String,
    val name: String,
    val strategies: List<String>,
    val score: Int,
    val reason: String
)

/** ================================================================ */
/** 工具 1: 市场快照 */
class MarketSnapshotTool(private val ctx: Context) : AgentTool {
    override val name = "market_snapshot"
    override val description = "获取当日大盘快照数据"
    override val parameters = listOf("date")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val date = params["date"]
                    ?: TradingDayPickerView.recentTradingDay().toString()
                val db = StockDatabase.getInstance(c)
                val marketData = db.dailySnapshotDao().getByDate(date)

                if (marketData.isEmpty()) return@withContext "暂无市场数据"

                val avgChange = marketData.map { it.changePct }.average()
                val upCount = marketData.count { it.changePct > 0 }
                val downCount = marketData.count { it.changePct < 0 }

                buildString {
                    appendLine("【大盘环境】 $date")
                    appendLine("- 平均涨跌幅: ${"%.2f".format(avgChange)}%")
                    appendLine("- 上涨家数: $upCount, 下跌家数: $downCount")
                    appendLine("- 市场情绪: ${
                        if (avgChange > 1) "强势"
                        else if (avgChange > 0) "偏多"
                        else if (avgChange > -1) "偏弱"
                        else "弱势"
                    }")
                }
            } catch (e: Exception) {
                "错误: 获取市场快照失败: ${e.message}"
            }
        }
    }
}

/** 工具 2: 策略扫描 */
class StrategyScanTool(private val ctx: Context) : AgentTool {
    override val name = "strategy_scan"
    override val description = "执行指定策略扫描全市场或指定股票池"
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
                "错误: 策略扫描失败: ${e.message}"
            }
        }
    }
}