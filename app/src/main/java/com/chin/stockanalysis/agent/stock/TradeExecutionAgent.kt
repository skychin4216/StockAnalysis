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
 * ## 交易执行 Agent（Plan-and-Execute 模式）
 *
 * 替代现有的 SimulationTradeEngine 硬编码流程，实现智能交易决策：
 * 1. 规划交易步骤（选股 → 分析 → 下单 → 监控 → 卖出）
 * 2. 动态调整策略组合和仓位分配
 * 3. 集成风控检查
 */
class TradeExecutionAgent(context: Context) : AgentBase(
    id = "trade_execution",
    name = "交易执行 Agent",
    description = "根据市场环境和策略信号，执行智能买卖决策",
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
        你是一位专业的 A 股交易执行 Agent，负责将选股和分析结果转化为实际交易操作。

        ## 核心职责
        1. 评估当前持仓状态和现金仓位
        2. 根据选股结果和分析报告决定买入名单和仓位分配
        3. 执行风控检查（止损、止盈、最大回撤）
        4. 决定卖出时机和顺序
        5. 记录交易决策理由

        ## 交易规则
        - 最大持仓数: $MAX_HOLDINGS 只
        - 单只股票最大仓位: 30%
        - 新买入必须有明确的止损位（亏损 > 8% 强制止损）
        - 卖出优先级: 触发止损 > 达到止盈 > 板块走弱 > 持仓超期
        - 腾笼换鸟: 当持仓已满且有更优标的时，先卖出最差持仓再买入

        ## 输出格式
        {
          "action": "BUY|SELL|HOLD|MIXED",
          "buy_orders": [
            {"code": "600519", "name": "贵州茅台", "price": 1500.0, "quantity": 100, "reason": "...", "stop_loss": 1400}
          ],
          "sell_orders": [
            {"code": "000001", "name": "平安银行", "reason": "触发止损", "profit_pct": -8.5}
          ],
          "portfolio_after": {"cash_ratio": 30, "stock_ratio": 70, "holdings": 5},
          "reasoning": "..."
        }
    """.trimIndent()

    /**
     * 执行交易决策（买入 + 卖出）
     */
    suspend fun executeTrade(
        candidates: List<StockRecommendation>? = null,
        onProgress: ((String) -> Unit)? = null
    ): TradeExecutionResult {
        onProgress?.invoke("📈 交易 Agent 启动...")

        val ctx = AgentContext().apply {
            candidates?.let { put("candidates", it) }
            put("date", TradingDayPickerView.recentTradingDay().toString())
            put("max_holdings", MAX_HOLDINGS)
        }

        val result = planAndExecute(
            goal = "根据当前市场环境、持仓状态和候选股票，制定今日交易计划（买入+卖出）",
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
            Log.w(TAG, "解析交易结果失败: ${e.message}")
            TradeExecutionResult(
                success = result.success,
                rawOutput = result.output,
                steps = result.steps
            )
        }
    }
}

/** 交易执行结果 */
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
/** 持仓状态工具 */
class PortfolioStatusTool(private val ctx: Context) : AgentTool {
    override val name = "portfolio_status"
    override val description = "获取当前持仓状态、现金比例、盈亏情况"
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
                    appendLine("【持仓状态】")
                    appendLine("- 持仓数: ${orders.size}")
                    appendLine("- 总市值: ${"%.2f".format(totalValue)}")
                    appendLine("- 持仓列表:")
                    holdings.forEach { appendLine("  • $it") }
                }
            } catch (e: Exception) {
                "错误: 获取持仓失败: ${e.message}"
            }
        }
    }
}

/** 下单买入工具 */
class PlaceBuyOrderTool(private val ctx: Context) : AgentTool {
    override val name = "place_buy_order"
    override val description = "创建买入订单（写入数据库）"
    override val parameters = listOf("stock_code", "stock_name", "price", "quantity", "reason", "strategy_id")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "错误: 缺少股票代码"
                val name = params["stock_name"] ?: code
                val price = params["price"]?.toDoubleOrNull() ?: return@withContext "错误: 缺少价格"
                val qty = params["quantity"]?.toIntOrNull() ?: 100
                val reason = params["reason"] ?: "Agent 决策买入"
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
                        orderType = "Agent买入",
                        status = "BUYING",
                        reason = reason,
                        createdAt = System.currentTimeMillis(),
                        scoreAtBuy = 0,
                        buyTime = java.time.LocalTime.now().toString().take(8)
                    )
                )
                "✅ 已创建买入订单: $name($code) ${qty}股 @${price}"
            } catch (e: Exception) {
                "错误: 创建买入订单失败: ${e.message}"
            }
        }
    }
}

/** 下单卖出工具 */
class PlaceSellOrderTool(private val ctx: Context) : AgentTool {
    override val name = "place_sell_order"
    override val description = "标记持仓为卖出状态"
    override val parameters = listOf("stock_code", "reason")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "错误: 缺少股票代码"
                val reason = params["reason"] ?: "Agent 决策卖出"
                val db = StockDatabase.getInstance(c)

                val activeOrders = db.strategyTradeOrderDao().getRecent(200)
                    .filter { it.stockCode == code && it.status in listOf("BUYING", "PENDING") }
                if (activeOrders.isEmpty()) return@withContext "错误: 未找到股票 $code 的活跃持仓"
                val order = activeOrders.first()
                db.strategyTradeOrderDao().updateSellInfo(
                    order.id, "SOLD", order.buyPrice,
                    java.time.LocalDate.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " 15:00", 0.0)
                "✅ 已标记卖出: $code | 理由: $reason"
            } catch (e: Exception) {
                "错误: 标记卖出失败: ${e.message}"
            }
        }
    }
}

/** 风控检查工具 */
class RiskCheckTool(private val ctx: Context) : AgentTool {
    override val name = "risk_check"
    override val description = "检查持仓风险（止损、回撤、超期）"
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
                        pct < -8 -> risks.add("${o.stockCode}: 亏损 ${"%.1f".format(pct)}% > 8%，触发止损")
                        pct > 20 -> risks.add("${o.stockCode}: 盈利 ${"%.1f".format(pct)}% > 20%，建议止盈")
                    }
                }

                if (risks.isEmpty()) "✅ 所有持仓风险可控" else "⚠️ 风险警告:\n${risks.joinToString("\n")}"
            } catch (e: Exception) {
                "错误: 风控检查失败: ${e.message}"
            }
        }
    }
}

/** 择时工具 */
class MarketTimingTool(private val ctx: Context) : AgentTool {
    override val name = "market_timing"
    override val description = "评估当前市场时机是否适合交易"
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
                    avgChange > 2 && upRatio > 0.7 -> "强势市场，积极做多"
                    avgChange > 0 && upRatio > 0.5 -> "偏多市场，适度参与"
                    avgChange < -2 && upRatio < 0.3 -> "弱势市场，控制仓位"
                    else -> "震荡市场，精选个股"
                }

                buildString {
                    appendLine("【择时评估】 $today")
                    appendLine("- 平均涨跌幅: ${"%.2f".format(avgChange)}%")
                    appendLine("- 上涨比例: ${"%.1f".format(upRatio * 100)}%")
                    appendLine("- 建议: $timing")
                }
            } catch (e: Exception) {
                "错误: 择时评估失败: ${e.message}"
            }
        }
    }
}
