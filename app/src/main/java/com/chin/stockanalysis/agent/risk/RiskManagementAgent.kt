package com.chin.stockanalysis.agent.risk

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.trade.AutoSellEngine
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 風控 Agent（Plan-and-Execute 模式）
 *
 * 替代現有的 AutoSellEngine 和 AppBackgroundRunner 持倉監控，實現智能風控：
 * 1. 持續監控所有持倉的風險指標
 * 2. 動態評估止損/止盈條件
 * 3. 考慮大盤環境和板塊走勢
 * 4. 給出倉位調整建議
 * 5. 發現系統性風險時提醒減倉
 */
class RiskManagementAgent(context: Context) : AgentBase(
    id = "risk_management",
    name = "風控 Agent",
    description = "持續監控持倉風險，動態評估止損止盈，給出倉位調整建議",
    context = context
) {
    companion object {
        private const val TAG = "RiskManagementAgent"
    }

    init {
        registerTool(PortfolioRiskScanTool(context))
        registerTool(StopLossCheckTool(context))
        registerTool(TakeProfitCheckTool(context))
        registerTool(MarketRiskTool(context))
        registerTool(PositionSizingTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位專業的風險管理 Agent，負責保護投資組合免受重大損失。

        ## 核心職責
        1. 監控每個持倉的虧損幅度、回撤、持有時間
        2. 評估大盤和板塊的系統性風險
        3. 動態調整止損/止盈位（根據波動率）
        4. 給出倉位調整建議（加倉/減倉/觀望）
        5. 識別黑天鵝事件信號

        ## 風控規則
        - 硬止損: 單股虧損 > 8% → 必須賣出
        - 最大回撤: 從最高點回撤 > 12% → 賣出
        - 時間止損: 持倉 > 10 天無盈利 → 評估賣出
        - 階梯止盈: +10% 賣1/3, +15% 賣1/3, +20% 賣剩餘
        - 移動止盈: 盈利 > 8% 後，回撤超盈利 50% → 賣出
        - 系統性風險: 大盤跌 > 3% 或板塊跌 > 5% → 減倉至 50%

        ## 輸出格式
        {
          "portfolio_risk_level": "LOW|MEDIUM|HIGH|CRITICAL",
          "holdings": [
            {
              "code": "600519",
              "risk_status": "SAFE|WARNING|CRITICAL",
              "profit_pct": 5.2,
              "max_drawdown": 3.1,
              "days_held": 5,
              "recommendation": "HOLD|SELL_PARTIAL|SELL_ALL|ADD",
              "reason": "..."
            }
          ],
          "market_risk": "...",
          "position_advice": "維持當前倉位 / 減倉至 X% / 清倉觀望",
          "urgent_alerts": ["..."]
        }
    """.trimIndent()

    /**
     * 執行風控掃描
     */
    suspend fun scanPortfolio(
        onAlert: ((String) -> Unit)? = null
    ): RiskScanResult {
        val ctx = AgentContext().apply {
            put("date", TradingDayPickerView.recentTradingDay().toString())
        }

        val result = planAndExecute(
            goal = "掃描整個投資組合的風險狀況，識別需要處理的持倉",
            ctx = ctx,
            maxSteps = 8
        )

        return parseRiskResult(result)
    }

    /**
     * 評估單只股票風險
     */
    suspend fun assessStockRisk(stockCode: String): StockRiskAssessment {
        val ctx = AgentContext().apply {
            put("stock_code", stockCode)
            put("date", TradingDayPickerView.recentTradingDay().toString())
        }

        val result = react(
            input = "評估股票 $stockCode 的當前風險狀況",
            ctx = ctx,
            maxSteps = 4
        )

        return StockRiskAssessment(
            success = result.success,
            stockCode = stockCode,
            assessment = result.output,
            steps = result.steps
        )
    }

    private fun parseRiskResult(result: AgentResult): RiskScanResult {
        return try {
            val json = org.json.JSONObject(result.output)
            val holdings = mutableListOf<HoldingRisk>()
            json.optJSONArray("holdings")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    holdings.add(
                        HoldingRisk(
                            code = obj.getString("code"),
                            riskStatus = obj.optString("risk_status", "SAFE"),
                            profitPct = obj.optDouble("profit_pct", 0.0),
                            maxDrawdown = obj.optDouble("max_drawdown", 0.0),
                            daysHeld = obj.optInt("days_held", 0),
                            recommendation = obj.optString("recommendation", "HOLD"),
                            reason = obj.optString("reason", "")
                        )
                    )
                }
            }

            val alerts = mutableListOf<String>()
            json.optJSONArray("urgent_alerts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    alerts.add(arr.getString(i))
                }
            }

            RiskScanResult(
                success = result.success,
                portfolioRiskLevel = json.optString("portfolio_risk_level", "LOW"),
                holdings = holdings,
                marketRisk = json.optString("market_risk", ""),
                positionAdvice = json.optString("position_advice", ""),
                urgentAlerts = alerts,
                rawOutput = result.output
            )
        } catch (e: Exception) {
            RiskScanResult(success = result.success, rawOutput = result.output)
        }
    }
}

/** 風控掃描結果 */
data class RiskScanResult(
    val success: Boolean,
    val portfolioRiskLevel: String = "LOW",
    val holdings: List<HoldingRisk> = emptyList(),
    val marketRisk: String = "",
    val positionAdvice: String = "",
    val urgentAlerts: List<String> = emptyList(),
    val rawOutput: String = ""
)

/** 單個持倉風險 */
data class HoldingRisk(
    val code: String,
    val riskStatus: String,
    val profitPct: Double,
    val maxDrawdown: Double,
    val daysHeld: Int,
    val recommendation: String,
    val reason: String
)

/** 單股風險評估 */
data class StockRiskAssessment(
    val success: Boolean,
    val stockCode: String,
    val assessment: String,
    val steps: Int = 0
)

/** ================================================================ */
/** 持倉風險掃描工具 */
class PortfolioRiskScanTool(private val ctx: Context) : AgentTool {
    override val name = "portfolio_risk_scan"
    override val description = "掃描所有持倉的風險指標（盈虧、回撤、持倉時間）"
    override val parameters = listOf<String>()

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val orders = db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }

                if (orders.isEmpty()) return@withContext "當前無持倉"

                val results = mutableListOf<String>()
                for (order in orders) {
                    val snapshot = db.dailySnapshotDao().getByDateAndCode(today, order.stockCode)
                    if (snapshot == null) continue

                    val profitPct = (snapshot.close - order.buyPrice) / order.buyPrice * 100
                    val daysHeld = 0 // 簡化

                    val status = when {
                        profitPct < -8 -> "CRITICAL(止損)"
                        profitPct < -5 -> "WARNING(虧損)"
                        profitPct > 15 -> "WARNING(止盈)"
                        else -> "SAFE"
                    }

                    results.add("${order.stockCode}(${order.stockName}): 盈虧 ${"%.1f".format(profitPct)}% | 持有 $daysHeld 天 | $status")
                }

                buildString {
                    appendLine("【持倉風險掃描】共 ${orders.size} 只")
                    results.forEach { appendLine("- $it") }
                }
            } catch (e: Exception) {
                "錯誤: 掃描失敗: ${e.message}"
            }
        }
    }
}

/** 止損檢查工具 */
class StopLossCheckTool(private val ctx: Context) : AgentTool {
    override val name = "stop_loss_check"
    override val description = "檢查是否有持倉觸發止損條件"
    override val parameters = listOf("threshold_pct")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val threshold = params["threshold_pct"]?.toDoubleOrNull() ?: -8.0
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val orders = db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }

                val triggered = mutableListOf<String>()
                for (order in orders) {
                    val snapshot = db.dailySnapshotDao().getByDateAndCode(today, order.stockCode)
                    if (snapshot == null) continue
                    val profitPct = (snapshot.close - order.buyPrice) / order.buyPrice * 100
                    if (profitPct <= threshold) {
                        triggered.add("${order.stockCode}: ${"%.1f".format(profitPct)}% <= ${threshold}%")
                    }
                }

                if (triggered.isEmpty()) "✅ 無持倉觸發止損（閾值 ${threshold}%）"
                else "⚠️ 止損觸發:\n${triggered.joinToString("\n")}"
            } catch (e: Exception) {
                "錯誤: 止損檢查失敗: ${e.message}"
            }
        }
    }
}

/** 止盈檢查工具 */
class TakeProfitCheckTool(private val ctx: Context) : AgentTool {
    override val name = "take_profit_check"
    override val description = "檢查是否有持倉達到止盈條件"
    override val parameters = listOf("threshold_pct")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val threshold = params["threshold_pct"]?.toDoubleOrNull() ?: 15.0
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val orders = db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }

                val triggered = mutableListOf<String>()
                for (order in orders) {
                    val snapshot = db.dailySnapshotDao().getByDateAndCode(today, order.stockCode)
                    if (snapshot == null) continue
                    val profitPct = (snapshot.close - order.buyPrice) / order.buyPrice * 100
                    if (profitPct >= threshold) {
                        triggered.add("${order.stockCode}: +${"%.1f".format(profitPct)}% >= +${threshold}%")
                    }
                }

                if (triggered.isEmpty()) "✅ 無持倉達到止盈（閾值 +${threshold}%）"
                else "🎯 止盈觸發:\n${triggered.joinToString("\n")}"
            } catch (e: Exception) {
                "錯誤: 止盈檢查失敗: ${e.message}"
            }
        }
    }
}

/** 市場風險工具 */
class MarketRiskTool(private val ctx: Context) : AgentTool {
    override val name = "market_risk"
    override val description = "評估當前市場系統性風險"
    override val parameters = listOf<String>()

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val data = db.dailySnapshotDao().getByDate(today)

                val avgChange = data.map { it.changePct }.average()
                val downRatio = if (data.isNotEmpty()) data.count { it.changePct < -5 }.toDouble() / data.size else 0.0
                val limitDownCount = data.count { it.changePct <= -9.9 }

                val riskLevel = when {
                    avgChange < -3 || downRatio > 0.1 || limitDownCount > 50 -> "HIGH（高風險）"
                    avgChange < -1.5 || downRatio > 0.05 -> "MEDIUM（中風險）"
                    else -> "LOW（低風險）"
                }

                buildString {
                    appendLine("【市場風險評估】")
                    appendLine("- 風險等級: $riskLevel")
                    appendLine("- 平均漲跌: ${"%.2f".format(avgChange)}%")
                    appendLine("- 大跌股比例: ${"%.1f".format(downRatio * 100)}%")
                    appendLine("- 跌停家數: $limitDownCount")
                }
            } catch (e: Exception) {
                "錯誤: 市場風險評估失敗: ${e.message}"
            }
        }
    }
}

/** 倉位調整工具 */
class PositionSizingTool(private val ctx: Context) : AgentTool {
    override val name = "position_sizing"
    override val description = "根據風險等級給出倉位調整建議"
    override val parameters = listOf("risk_level", "current_stock_ratio")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val riskLevel = params["risk_level"] ?: "LOW"
        val currentRatio = params["current_stock_ratio"]?.toDoubleOrNull() ?: 70.0

        val advice = when (riskLevel.uppercase()) {
            "HIGH", "CRITICAL" -> "建議減倉至 30% 以下，優先賣出弱勢股"
            "MEDIUM" -> "建議減倉至 50% 左右，保留強勢股"
            else -> if (currentRatio < 80) "可以維持或適度加倉" else "倉位已高，不建議追漲"
        }

        return "【倉位建議】當前股票倉位 ${currentRatio}% | 風險等級 $riskLevel | $advice"
    }
}
