package com.chin.stockanalysis.agent.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * ## 交易執行 Agent（Plan-and-Execute 模式）
 *
 * 替代現有的 SimulationTradeEngine 硬編碼流程，實現智能交易決策：
 * 1. 規劃交易步驟（選股 → 分析 → 下單 → 監控 → 賣出）
 * 2. 動態調整策略組合和倉位分配
 * 3. 集成風控檢查
 */
class TradeExecutionAgent(context: Context) : AgentBase(
    id = "trade_execution",
    name = "交易執行 Agent",
    description = "根據市場環境和策略信號，執行智能買賣決策",
    context = context
) {
    companion object {
        private const val TAG = "TradeExecutionAgent"
        private const val MAX_HOLDINGS = 5
    }

    init {
        registerTool(PortfolioStatusTool(context))
        registerTool(PlaceBuyOrderTool(context))
        registerTool(PlaceSellOrderTool(context))
        registerTool(RiskCheckTool(context))
        registerTool(MarketTimingTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位專業的 A 股交易執行 Agent，負責將選股和分析結果轉化為實際交易操作。

        ## 核心職責
        1. 評估當前持倉狀態和現金倉位
        2. 根據選股結果和分析報告決定買入名單和倉位分配
        3. 執行風控檢查（止損、止盈、最大回撤）
        4. 決定賣出時機和順序
        5. 記錄交易決策理由

        ## 交易規則
        - 最大持倉數: $MAX_HOLDINGS 只
        - 單只股票最大倉位: 30%
        - 新買入必須有明確的止損位（虧損 > 8% 強制止損）
        - 賣出優先級: 觸發止損 > 達到止盈 > 板塊走弱 > 持倉超期
        - 騰籠換鳥: 當持倉已滿且有更優標的時，先賣出最差持倉再買入

        ## 輸出格式
        {
          "action": "BUY|SELL|HOLD|MIXED",
          "buy_orders": [
            {"code": "600519", "name": "貴州茅台", "price": 1500.0, "quantity": 100, "reason": "...", "stop_loss": 1400}
          ],
          "sell_orders": [
            {"code": "000001", "name": "平安銀行", "reason": "觸發止損", "profit_pct": -8.5}
          ],
          "portfolio_after": {"cash_ratio": 30, "stock_ratio": 70, "holdings": 5},
          "reasoning": "..."
        }
    """.trimIndent()

    /**
     * 執行交易決策（買入 + 賣出）
     */
    suspend fun executeTrade(
        candidates: List<StockRecommendation>? = null,
        onProgress: ((String) -> Unit)? = null
    ): TradeExecutionResult {
        onProgress?.invoke("📈 交易 Agent 啟動...")

        val ctx = AgentContext().apply {
            candidates?.let { put("candidates", it) }
            put("date", TradingDayPickerView.recentTradingDay().toString())
            put("max_holdings", MAX_HOLDINGS)
        }

        val result = planAndExecute(
            goal = "根據當前市場環境、持倉狀態和候選股票，制定今日交易計劃（買入+賣出）",
            ctx = ctx,
            maxSteps = 10
        )

        return parseExecutionResult(result)
    }

    private fun parseExecutionResult(result: AgentResult): TradeExecutionResult {
        return try {
            val json = org.json.JSONObject(result.output)
            val buys = mutableListOf<BuyOrder>()
            val sells = mutableListOf<SellOrder>()

            json.optJSONArray("buy_orders")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    buys.add(BuyOrder(
                        code = obj.getString("code"),
                        name = obj.getString("name"),
                        price = obj.getDouble("price"),
                        quantity = obj.getInt("quantity"),
                        reason = obj.optString("reason", ""),
                        stopLoss = obj.optDouble("stop_loss", 0.0)
                    ))
                }
            }

            json.optJSONArray("sell_orders")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    sells.add(SellOrder(
                        code = obj.getString("code"),
                        name = obj.getString("name"),
                        reason = obj.optString("reason", ""),
                        profitPct = obj.optDouble("profit_pct", 0.0)
                    ))
                }
            }

            TradeExecutionResult(
                success = result.success,
                action = json.optString("action", "HOLD"),
                buyOrders = buys,
                sellOrders = sells,
                reasoning = json.optString("reasoning", ""),
                rawOutput = result.output,
                steps = result.steps
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析交易結果失敗: ${e.message}")
            TradeExecutionResult(
                success = result.success,
                rawOutput = result.output,
                steps = result.steps
            )
        }
    }
}

/** 交易執行結果 */
data class TradeExecutionResult(
    val success: Boolean,
    val action: String = "HOLD",
    val buyOrders: List<BuyOrder> = emptyList(),
    val sellOrders: List<SellOrder> = emptyList(),
    val reasoning: String = "",
    val rawOutput: String = "",
    val steps: Int = 0
)

data class BuyOrder(
    val code: String,
    val name: String,
    val price: Double,
    val quantity: Int,
    val reason: String,
    val stopLoss: Double
)

data class SellOrder(
    val code: String,
    val name: String,
    val reason: String,
    val profitPct: Double
)

/** ================================================================ */
/** 持倉狀態工具 */
class PortfolioStatusTool(private val ctx: Context) : AgentTool {
    override val name = "portfolio_status"
    override val description = "獲取當前持倉狀態、現金比例、盈虧情況"
    override val parameters = listOf<String>()

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(c)
                val orders = db.strategyTradeOrderDao().getRecent(200).filter { it.status in listOf("BUYING", "PENDING") }

                val totalValue = orders.sumOf { it.buyPrice * it.quantity }
                val holdings = orders.map { "${it.stockCode}(${it.stockName}) x${it.quantity} @${it.buyPrice}" }

                buildString {
                    appendLine("【持倉狀態】")
                    appendLine("- 持倉數: ${orders.size}")
                    appendLine("- 總市值: ${"%.2f".format(totalValue)}")
                    appendLine("- 持倉列表:")
                    holdings.forEach { appendLine("  • $it") }
                }
            } catch (e: Exception) {
                "錯誤: 獲取持倉失敗: ${e.message}"
            }
        }
    }
}

/** 下單買入工具 */
class PlaceBuyOrderTool(private val ctx: Context) : AgentTool {
    override val name = "place_buy_order"
    override val description = "創建買入訂單（寫入數據庫）"
    override val parameters = listOf("stock_code", "stock_name", "price", "quantity", "reason", "strategy_id")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 缺少股票代碼"
                val name = params["stock_name"] ?: code
                val price = params["price"]?.toDoubleOrNull() ?: return@withContext "錯誤: 缺少價格"
                val qty = params["quantity"]?.toIntOrNull() ?: 100
                val reason = params["reason"] ?: "Agent 決策買入"
                val strategyId = params["strategy_id"] ?: "AgentTrade"
                val today = TradingDayPickerView.recentTradingDay()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

                val db = StockDatabase.getInstance(c)
                db.strategyTradeOrderDao().insert(
                    StrategyTradeOrderEntity(
                        strategyId = strategyId,
                        stockCode = code,
                        stockName = name,
                        tradeDate = today,
                        buyPrice = price,
                        quantity = qty,
                        orderType = "Agent買入",
                        status = "BUYING",
                        reason = reason,
                        createdAt = System.currentTimeMillis(),
                        scoreAtBuy = 0,
                        buyTime = java.time.LocalTime.now().toString().take(8)
                    )
                )
                "✅ 已創建買入訂單: $name($code) ${qty}股 @${price}"
            } catch (e: Exception) {
                "錯誤: 創建買入訂單失敗: ${e.message}"
            }
        }
    }
}

/** 下單賣出工具 */
class PlaceSellOrderTool(private val ctx: Context) : AgentTool {
    override val name = "place_sell_order"
    override val description = "標記持倉為賣出狀態"
    override val parameters = listOf("stock_code", "reason")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 缺少股票代碼"
                val reason = params["reason"] ?: "Agent 決策賣出"
                val db = StockDatabase.getInstance(c)

                val activeOrders = db.strategyTradeOrderDao().getRecent(200)
                    .filter { it.stockCode == code && it.status in listOf("BUYING", "PENDING") }
                if (activeOrders.isEmpty()) return@withContext "錯誤: 未找到股票 $code 的活躍持倉"
                val order = activeOrders.first()
                db.strategyTradeOrderDao().updateSellInfo(
                    order.id, "SOLD", order.buyPrice,
                    java.time.LocalDate.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " 15:00", 0.0)
                "✅ 已標記賣出: $code | 理由: $reason"
            } catch (e: Exception) {
                "錯誤: 標記賣出失敗: ${e.message}"
            }
        }
    }
}

/** 風控檢查工具 */
class RiskCheckTool(private val ctx: Context) : AgentTool {
    override val name = "risk_check"
    override val description = "檢查持倉風險（止損、回撤、超期）"
    override val parameters = listOf("stock_code")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"]
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()

                val allActiveOrders = db.strategyTradeOrderDao().getRecent(200)
                    .filter { it.status in listOf("BUYING", "PENDING") }
                val orders = if (code != null) {
                    allActiveOrders.filter { it.stockCode == code }
                } else {
                    allActiveOrders
                }

                val risks = mutableListOf<String>()
                for (o in orders) {
                    val snap = db.dailySnapshotDao().getByDateAndCode(today, o.stockCode)
                    if (snap == null) continue

                    val pct = (snap.close - o.buyPrice) / o.buyPrice * 100

                    when {
                        pct < -8 -> risks.add("${o.stockCode}: 虧損 ${"%.1f".format(pct)}% > 8%，觸發止損")
                        pct > 20 -> risks.add("${o.stockCode}: 盈利 ${"%.1f".format(pct)}% > 20%，建議止盈")
                    }
                }

                if (risks.isEmpty()) "✅ 所有持倉風險可控" else "⚠️ 風險警告:\n${risks.joinToString("\n")}"
            } catch (e: Exception) {
                "錯誤: 風控檢查失敗: ${e.message}"
            }
        }
    }
}

/** 擇時工具 */
class MarketTimingTool(private val ctx: Context) : AgentTool {
    override val name = "market_timing"
    override val description = "評估當前市場時機是否適合交易"
    override val parameters = listOf<String>()

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(c)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val data = db.dailySnapshotDao().getByDate(today)

                val avgChange = data.map { it.changePct }.average()
                val upRatio = if (data.isNotEmpty()) data.count { it.changePct > 0 }.toDouble() / data.size else 0.0

                val timing = when {
                    avgChange > 2 && upRatio > 0.7 -> "強勢市場，積極做多"
                    avgChange > 0 && upRatio > 0.5 -> "偏多市場，適度參與"
                    avgChange < -2 && upRatio < 0.3 -> "弱勢市場，控制倉位"
                    else -> "震蕩市場，精選個股"
                }

                buildString {
                    appendLine("【擇時評估】 $today")
                    appendLine("- 平均漲跌幅: ${"%.2f".format(avgChange)}%")
                    appendLine("- 上漲比例: ${"%.1f".format(upRatio * 100)}%")
                    appendLine("- 建議: $timing")
                }
            } catch (e: Exception) {
                "錯誤: 擇時評估失敗: ${e.message}"
            }
        }
    }
}
