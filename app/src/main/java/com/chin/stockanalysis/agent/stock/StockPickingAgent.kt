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
 * 替代現有的硬編碼 StrategyEngine 流程，實現動態選股：
 * 1. 觀察當前市場環境（大盤、板塊熱度）
 * 2. 思考應該使用哪些策略組合
 * 3. 執行策略掃描
 * 4. 觀察結果，決定是否調整策略
 * 5. 給出最終選股建議
 *
 * 工具註冊：
 * - MarketSnapshotTool: 獲取大盤/板塊快照
 * - StrategyScanTool: 執行策略掃描
 * - StockFilterTool: 過濾股票（主板、市值、ST）
 * - NewsCheckTool: 檢查新聞利空
 */
class StockPickingAgent(context: Context) : AgentBase(
    id = "stock_picking",
    name = "選股 Agent",
    description = "根據市場環境動態選擇策略組合，執行智能選股",
    context = context
) {
    companion object {
        private const val TAG = "StockPickingAgent"
    }

    init {
        // 註冊工具
        registerTool(MarketSnapshotTool(context))
        registerTool(StrategyScanTool(context))
        registerTool(StockFilterTool(context))
        registerTool(NewsCheckTool(context))
        registerTool(SectorHeatTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位專業的 A 股選股 Agent，擅長根據市場環境動態調整選股策略。

        ## 核心能力
        1. 分析大盤環境和板塊熱度
        2. 從 11 種策略中動態選擇最合適的組合
        3. 執行策略掃描並過濾結果
        4. 檢查新聞利空，排除風險股
        5. 給出 Top 10 推薦股票及理由

        ## 策略庫
        - MA突破: 均線突破策略，適合趨勢行情
        - 放量突破: 成交量放大+價格突破，適合啟動點
        - 低估值: PE/PB 低位，適合價值投資
        - 跳空動量: 跳空上漲+動量延續，適合強勢股
        - 換手率過濾: 高換手活躍股
        - 布林帶: 價格觸及布林帶上下軌，適合震蕩行情
        - RSI背離: RSI 與價格背離，適合反轉點
        - 基本面過濾: 財務指標篩選
        - 早盤追漲: 開盤強勢股
        - 尾盤低吸: 尾盤異動股
        - AI預測: 大模型綜合預測

        ## 輸出格式
        最終答案請以 JSON 格式輸出：
        {
          "recommendations": [
            {"code": "600519", "name": "貴州茅台", "strategies": ["MA突破", "低估值"], "score": 85, "reason": "..."}
          ],
          "market_assessment": "...",
          "strategy_combination": ["..."],
          "risk_warning": "..."
        }
    """.trimIndent()

    /**
     * 快捷方法：執行選股（封裝 ReAct 流程）
     */
    suspend fun pickStocks(
        date: String? = null,
        onlyMainBoard: Boolean = true,
        maxResults: Int = 10,
        onProgress: ((String) -> Unit)? = null
    ): StockPickingResult {
        val tradingDay = date ?: TradingDayPickerView.recentTradingDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        onProgress?.invoke("🤖 選股 Agent 啟動...")

        val ctx = AgentContext().apply {
            put("date", tradingDay)
            put("only_main_board", onlyMainBoard)
            put("max_results", maxResults)
        }

        val result = react(
            input = "請為 $tradingDay 選出 $maxResults 只最值得關注的 A 股股票。${if (onlyMainBoard) "僅考慮主板股票。" else ""}",
            ctx = ctx,
            maxSteps = 6
        )

        return parsePickingResult(result)
    }

    private fun parsePickingResult(result: AgentResult): StockPickingResult {
        return try {
            val json = org.json.JSONObject(result.output)
            val recs = mutableListOf<StockRecommendation>()
            val arr = json.optJSONArray("recommendations")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    recs.add(
                        StockRecommendation(
                            code = obj.getString("code"),
                            name = obj.getString("name"),
                            strategies = (0 until obj.optJSONArray("strategies")?.length().orDefault(0))
                                .map { obj.optJSONArray("strategies")?.getString(it) ?: "" },
                            score = obj.optInt("score", 0),
                            reason = obj.optString("reason", "")
                        )
                    )
                }
            }
            StockPickingResult(
                success = result.success,
                recommendations = recs,
                marketAssessment = json.optString("market_assessment", ""),
                strategyCombination = json.optJSONArray("strategy_combination")?.let {
                    (0 until it.length()).map { idx -> it.getString(idx) }
                } ?: emptyList(),
                riskWarning = json.optString("risk_warning", ""),
                rawOutput = result.output,
                steps = result.steps
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析選股結果失敗: ${e.message}")
            StockPickingResult(
                success = result.success,
                recommendations = emptyList(),
                rawOutput = result.output,
                steps = result.steps
            )
        }
    }

    private fun Int?.orDefault(default: Int): Int = this ?: default
}

/** 選股結果 */
data class StockPickingResult(
    val success: Boolean,
    val recommendations: List<StockRecommendation>,
    val marketAssessment: String = "",
    val strategyCombination: List<String> = emptyList(),
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
    override val description = "獲取當日大盤和熱門板塊的快照數據"
    override val parameters = listOf("date")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val date = params["date"] ?: TradingDayPickerView.recentTradingDay().toString()
                val db = StockDatabase.getInstance(ctx)

                // 獲取大盤數據（以上證指數為代表）
                val marketData = db.dailySnapshotDao().getByDate(date)
                val avgChange = marketData.map { it.changePct }.average()
                val upCount = marketData.count { it.changePct > 0 }
                val downCount = marketData.count { it.changePct < 0 }

                // 獲取熱門板塊
                val sectors = db.sectorDailyRecordDao().getTopSectorsByDate(date, 5)

                buildString {
                    appendLine("【大盤環境】 $date")
                    appendLine("- 平均漲跌幅: ${"%.2f".format(avgChange)}%")
                    appendLine("- 上漲家數: $upCount, 下跌家數: $downCount")
                    appendLine("- 市場情緒: ${if (avgChange > 1) "強勢" else if (avgChange > 0) "偏多" else if (avgChange > -1) "偏弱" else "弱勢"}")
                    appendLine("【熱門板塊 TOP 5】")
                    sectors.forEach { s ->
                        appendLine("- ${s.sectorName}: ${"%.2f".format(s.avgChange)}% (${s.stockCount}只)")
                    }
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

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val date = params["date"] ?: TradingDayPickerView.recentTradingDay().toString()
                val strategyIds = params["strategy_ids"]?.split(",")?.map { it.trim() }
                    ?: StrategyEngineHolder.get().getStrategies().map { it.id }
                val stockCodes = params["stock_codes"]?.split(",")?.map { it.trim() }?.toSet()

                val feed = StrategyDataFeed(ctx)
                val config = StrategyDataFeed.DataFeedConfig(
                    onlyMainBoard = ctx.getString("only_main_board")?.toBoolean() ?: true,
                    stockCodes = stockCodes ?: emptySet()
                )
                val stocks = feed.prepareFromDb(date, config)

                val engine = StrategyEngineHolder.get()
                val results = mutableListOf<String>()

                for (strategyId in strategyIds) {
                    val strategy = engine.getStrategies().find { it.id == strategyId }
                        ?: continue
                    if (!engine.isEnabled(strategy.id)) continue

                    val result = strategy.screenWithData(stocks)
                    result.getOrNull()?.signals?.let { signals ->
                        val top = signals.sortedByDescending { it.strength }.take(5)
                        results.add("${strategy.name}: ${signals.size}只命中, Top5: ${top.joinToString { "${it.stockCode}(${it.strength}分)" }}")
                    }
                }

                results.joinToString("\n")
            } catch (e: Exception) {
                "錯誤: 策略掃描失敗: ${e.message}"
            }
        }
    }
}

/** 工具 3: 股票過濾 */
class StockFilterTool(private val ctx: Context) : AgentTool {
    override val name = "stock_filter"
    override val description = "過濾股票（主板、市值、ST、換手率）"
    override val parameters = listOf("stock_codes", "min_market_cap", "exclude_st", "min_turnover")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val codes = params["stock_codes"]?.split(",")?.map { it.trim() } ?: return@withContext "錯誤: 未提供股票代碼"
                val minCap = params["min_market_cap"]?.toDoubleOrNull() ?: 0.0
                val excludeSt = params["exclude_st"]?.toBoolean() ?: true
                val minTurnover = params["min_turnover"]?.toDoubleOrNull() ?: 0.0

                val db = StockDatabase.getInstance(ctx)
                val filtered = codes.filter { code ->
                    val basic = db.stockBasicDao().getByCode(code) ?: return@filter false
                    val snapshot = db.dailySnapshotDao().getLatestByCode(code)

                    if (excludeSt && (basic.name.contains("ST") || basic.name.contains("*ST"))) return@filter false
                    if (snapshot != null && snapshot.turnover < minTurnover) return@filter false
                    true
                }

                "過濾結果: ${filtered.size}/${codes.size} 只通過\n通過: ${filtered.joinToString(", ")}"
            } catch (e: Exception) {
                "錯誤: 過濾失敗: ${e.message}"
            }
        }
    }
}

/** 工具 4: 新聞檢查 */
class NewsCheckTool(private val ctx: Context) : AgentTool {
    override val name = "news_check"
    override val description = "檢查指定股票是否有重大利空新聞"
    override val parameters = listOf("stock_codes")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val codes = params["stock_codes"]?.split(",")?.map { it.trim() } ?: return@withContext "錯誤: 未提供股票代碼"
                val db = StockDatabase.getInstance(ctx)

                val risks = mutableListOf<String>()
                for (code in codes) {
                    val news = db.newsFactorDao().getActiveByStockCode(code, limit = 5)
                    val badNews = news.filter { it.sentiment == "利空" && it.strength > 60 }
                    if (badNews.isNotEmpty()) {
                        risks.add("$code: ${badNews.joinToString { it.title }}")
                    }
                }

                if (risks.isEmpty()) {
                    "✅ 所有股票無重大利空新聞"
                } else {
                    "⚠️ 發現利空新聞:\n${risks.joinToString("\n")}"
                }
            } catch (e: Exception) {
                "錯誤: 新聞檢查失敗: ${e.message}"
            }
        }
    }
}

/** 工具 5: 板塊熱度 */
class SectorHeatTool(private val ctx: Context) : AgentTool {
    override val name = "sector_heat"
    override val description = "獲取當前最熱門的板塊及其龍頭股"
    override val parameters = listOf("date", "top_n")

    override suspend fun execute(params: Map<String, String>, ctx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val date = params["date"] ?: TradingDayPickerView.recentTradingDay().toString()
                val topN = params["top_n"]?.toIntOrNull() ?: 5
                val db = StockDatabase.getInstance(ctx)

                val sectors = db.sectorDailyRecordDao().getTopSectorsByDate(date, topN)
                buildString {
                    sectors.forEach { s ->
                        appendLine("【${s.sectorName}】漲幅 ${"%.2f".format(s.avgChange)}%")
                        val stocks = db.sectorStockDao().getStocksBySector(s.sectorName)
                        val leaders = stocks.take(3)
                        appendLine("  龍頭: ${leaders.joinToString { "${it.stockCode}" }}")
                    }
                }
            } catch (e: Exception) {
                "錯誤: 獲取板塊熱度失敗: ${e.message}"
            }
        }
    }
}
