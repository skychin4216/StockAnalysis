package com.chin.stockanalysis.agent.router

import android.content.Context
import com.chin.stockanalysis.agent.stock.StockPickingAgent
import com.chin.stockanalysis.agent.stock.StockPickingResult
import com.chin.stockanalysis.agent.stock.StockRecommendation
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.trade.SimulationTradeEngine

/**
 * ## 選股路由層
 *
 * Legacy: SimulationTradeEngine.runTradeSession() → 轉換為 StockPickingResult
 * Agent: StockPickingAgent.pickStocks()
 */
interface StockPickingService {
    suspend fun pickStocks(
        context: Context,
        date: String? = null,
        onlyMainBoard: Boolean = true,
        maxResults: Int = 10,
        onProgress: ((String) -> Unit)? = null
    ): StockPickingResult
}

/** Legacy 實現 — 調用 SimulationTradeEngine.runTradeSession() */
class LegacyStockPickingService : StockPickingService {
    override suspend fun pickStocks(
        context: Context,
        date: String?,
        onlyMainBoard: Boolean,
        maxResults: Int,
        onProgress: ((String) -> Unit)?
    ): StockPickingResult {
        val engine = StrategyEngineHolder.get()
        val strategies = engine.getStrategies()
        if (strategies.isEmpty()) {
            return StockPickingResult(success = false, rawOutput = "無可用策略")
        }

        val te = SimulationTradeEngine(context)
        val config = SimulationTradeEngine.TradeSessionConfig(
            tradeDate = date ?: java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")),
            onlyMainBoard = onlyMainBoard
        )

        onProgress?.invoke("正在執行選股 Pipeline...")
        val report = te.runTradeSession(strategies, config)

        // 將 TradeSessionReport 轉換為 StockPickingResult
        val recommendations = report.aiTop3.map { pick ->
            StockRecommendation(
                code = pick.stockCode,
                name = pick.stockName,
                strategies = listOf("LegacyPipeline"),
                score = pick.compositeScore,
                reason = pick.reason
            )
        }.take(maxResults)

        return StockPickingResult(
            success = recommendations.isNotEmpty(),
            recommendations = recommendations,
            marketAssessment = report.summary,
            rawOutput = report.summary,
            steps = 9
        )
    }
}

/** Agent 實現 */
class AgentStockPickingService : StockPickingService {
    override suspend fun pickStocks(
        context: Context,
        date: String?,
        onlyMainBoard: Boolean,
        maxResults: Int,
        onProgress: ((String) -> Unit)?
    ): StockPickingResult {
        val agent = StockPickingAgent(context)
        return agent.pickStocks(date, onlyMainBoard, maxResults, onProgress)
    }
}

/** 路由工廠 */
object StockPickingRouter {
    fun getService(): StockPickingService {
        return when (FeatureFlagManager.resolveRoute(FeatureFlagManager.stockPickingRoute)) {
            com.chin.stockanalysis.config.AgentRoute.AGENT_FRAMEWORK -> AgentStockPickingService()
            else -> LegacyStockPickingService()
        }
    }
}
