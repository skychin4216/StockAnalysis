package com.chin.stockanalysis.agent.router

import android.content.Context
import com.chin.stockanalysis.agent.stock.BuyOrder
import com.chin.stockanalysis.agent.stock.TradeExecutionAgent
import com.chin.stockanalysis.agent.stock.TradeExecutionResult
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.trade.SimulationTradeEngine
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 交易執行路由層
 *
 * Legacy: SimulationTradeEngine.runTradeSession() → 轉換為 TradeExecutionResult
 * Agent: TradeExecutionAgent.executeTrade()
 */
interface TradeExecutionService {
    suspend fun executeTrade(context: Context): TradeExecutionResult
}

/** Legacy 實現 — 調用 SimulationTradeEngine.runTradeSession() */
class LegacyTradeExecutionService : TradeExecutionService {
    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    override suspend fun executeTrade(context: Context): TradeExecutionResult {
        val engine = StrategyEngineHolder.get()
        val strategies = engine.getStrategies()
        if (strategies.isEmpty()) {
            return TradeExecutionResult(success = false, reasoning = "無可用策略")
        }

        val te = SimulationTradeEngine(context)
        val config = SimulationTradeEngine.TradeSessionConfig(
            tradeDate = LocalDate.now().format(DATE_FMT),
            onlyMainBoard = true
        )

        val report = te.runTradeSession(strategies, config)

        // 將 TradeSessionReport 轉換為 TradeExecutionResult
        val buyOrders = report.buyOrders.map { order ->
            BuyOrder(
                code = order.stockCode,
                name = order.stockName,
                price = order.buyPrice,
                quantity = order.quantity,
                reason = order.reason,
                stopLoss = 0.0 // Legacy 不提供止損價
            )
        }

        return TradeExecutionResult(
            success = true,
            action = if (buyOrders.isNotEmpty()) "BUY" else "HOLD",
            buyOrders = buyOrders,
            reasoning = report.summary,
            rawOutput = report.summary,
            steps = 9
        )
    }
}

/** Agent 實現 */
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
