package com.chin.stockanalysis.agent.router

import android.content.Context
import com.chin.stockanalysis.agent.stock.StockPickingAgent
import com.chin.stockanalysis.agent.stock.StockPickingResult
import com.chin.stockanalysis.agent.stock.StockRecommendation
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.topology.xml.DagTradeExecutor
import com.chin.stockanalysis.stock.database.StockDatabase

/**
 * ## 選股路由層
 *
 * Legacy: DagTradeExecutor (mid_term pipeline) → 從 DB 讀取訂單轉為推薦
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

/** Legacy 實現 — 調用 DAG Pipeline (mid_term) */
class LegacyStockPickingService : StockPickingService {
    override suspend fun pickStocks(
        context: Context,
        date: String?,
        onlyMainBoard: Boolean,
        maxResults: Int,
        onProgress: ((String) -> Unit)?
    ): StockPickingResult {
        val engine = StrategyEngineHolder.get()
        val strategies = engine.getEnabledStrategiesByPeriod(HoldingPeriod.MID)
        if (strategies.isEmpty()) {
            return StockPickingResult(success = false, rawOutput = "無可用策略")
        }

        val tradeDate = date ?: java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        onProgress?.invoke("正在執行 DAG 選股 Pipeline...")
        val result = DagTradeExecutor.execute(
            context = context,
            useCaseId = "mid_term",
            tradeDate = tradeDate,
            today = today,
            strategies = strategies,
            orderType = "MidTermQuant"
        ) { _, nodeName ->
            onProgress?.invoke("🔄 $nodeName 執行中...")
        }

        // 從 DB 讀取今日訂單轉為推薦列表
        val db = StockDatabase.getInstance(context)
        val orders = try { db.strategyTradeOrderDao().getByDate(tradeDate) } catch (_: Exception) { emptyList() }
        val recommendations = orders
            .filter { it.status == "BUYING" || it.status == "PENDING" }
            .sortedByDescending { it.scoreAtBuy }
            .take(maxResults)
            .map { order ->
                StockRecommendation(
                    code = order.stockCode,
                    name = order.stockName,
                    strategies = listOf("DAG_Pipeline"),
                    score = order.scoreAtBuy,
                    reason = order.reason
                )
            }

        return StockPickingResult(
            success = result.success && recommendations.isNotEmpty(),
            recommendations = recommendations,
            marketAssessment = result.uiText,
            rawOutput = result.uiText,
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
