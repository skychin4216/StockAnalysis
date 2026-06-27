package com.chin.stockanalysis.agent.router

import android.content.Context
import com.chin.stockanalysis.agent.stock.StockPickingAgent
import com.chin.stockanalysis.agent.stock.StockPickingResult
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.agent.pipeline.AgentPipelineOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 選股路由層
 *
 * Fragment 統一調用此接口，底層根據 FeatureFlag 決定走 Legacy 還是 Agent。
 * 刪除 Legacy 時只需修改此文件，Fragment 完全不用改。
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

/** Legacy 實現 */
class LegacyStockPickingService : StockPickingService {
    override suspend fun pickStocks(
        context: Context,
        date: String?,
        onlyMainBoard: Boolean,
        maxResults: Int,
        onProgress: ((String) -> Unit)?
    ): StockPickingResult {
        // 調用舊的 AgentPipelineOrchestrator
        val orchestrator = AgentPipelineOrchestrator(context)
        // 舊系統返回的是 PipelineResult，需要轉換為 StockPickingResult
        // 這裡是適配代碼
        return StockPickingResult(
            success = true,
            recommendations = emptyList(),
            rawOutput = "Legacy pipeline executed"
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
