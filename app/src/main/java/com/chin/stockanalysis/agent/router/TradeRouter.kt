package com.chin.stockanalysis.agent.router

import android.content.Context
import com.chin.stockanalysis.agent.stock.BuyOrder
import com.chin.stockanalysis.agent.stock.TradeExecutionAgent
import com.chin.stockanalysis.agent.stock.TradeExecutionResult
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
import com.chin.stockanalysis.stock.database.StockDatabase
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 交易执行路由层
 *
 * Legacy: DagTradeExecutor (mid_term pipeline)
 * Agent: TradeExecutionAgent.executeTrade()
 */
interface TradeExecutionService {
    suspend fun executeTrade(context: Context): TradeExecutionResult
}

/** Legacy 实现 — 调用 DAG Pipeline (mid_term) */
class LegacyTradeExecutionService : TradeExecutionService {
    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    override suspend fun executeTrade(context: Context): TradeExecutionResult {
        val engine = StrategyEngineHolder.get()
        val strategies = engine.getEnabledStrategiesByPeriod(HoldingPeriod.MID)
        if (strategies.isEmpty()) {
            return TradeExecutionResult(success = false, reasoning = "无可用策略")
        }

        val today = LocalDate.now().format(DATE_FMT)
        val result = DagTradeExecutor.execute(
            context = context,
            useCaseId = "mid_term",
            tradeDate = today,
            today = today,
            strategies = strategies,
            orderType = "MidTermQuant"
        )

        // 从 DB 读取今日订单构建 BuyOrder 列表
        val db = StockDatabase.getInstance(context)
        val orders = try { db.strategyTradeOrderDao().getByDate(today) } catch (_: Exception) { emptyList() }
        val buyOrders = orders.filter { it.status == "BUYING" || it.status == "PENDING" }.map { order ->
            BuyOrder(
                code = order.stockCode,
                name = order.stockName,
                price = order.buyPrice,
                quantity = order.quantity,
                reason = order.reason,
                stopLoss = 0.0
            )
        }

        return TradeExecutionResult(
            success = result.success,
            action = if (buyOrders.isNotEmpty()) "BUY" else "HOLD",
            buyOrders = buyOrders,
            reasoning = result.uiText,
            rawOutput = result.uiText,
            steps = 9
        )
    }
}

/** Agent 实现 */
class AgentTradeExecutionService : TradeExecutionService {
    override suspend fun executeTrade(context: Context): TradeExecutionResult {
        val agent = TradeExecutionAgent(context)
        return agent.executeTrade()
    }
}

object TradeRouter {
    fun getService(): TradeExecutionService {
        return when (FeatureFlagManager.resolveRoute(FeatureFlagManager.tradeExecutionRoute)) {
            com.chin.stockanalysis.config.AgentRoute.AGENT_FRAMEWORK -> AgentTradeExecutionService()
            else -> LegacyTradeExecutionService()
        }
    }
}
