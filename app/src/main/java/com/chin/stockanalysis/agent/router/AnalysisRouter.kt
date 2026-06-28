package com.chin.stockanalysis.agent.router

import android.content.Context
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.agent.stock.StockAnalysisResult
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.ai.StockAnalyzerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 股票分析路由層
 */
interface StockAnalysisService {
    suspend fun analyze(context: Context, stockCode: String): StockAnalysisResult
}

/** Legacy 實現：包装旧 StockAnalyzerService */
class LegacyStockAnalysisService : StockAnalysisService {
    override suspend fun analyze(context: Context, stockCode: String): StockAnalysisResult {
        return withContext(Dispatchers.IO) {
            val service = StockAnalyzerService(context)
            val result = service.analyze(stockCode, onProgress = { _: com.chin.stockanalysis.ui.Message -> })
            val maxStrength = result.strategyHits.maxOfOrNull { it.strength } ?: 0
            StockAnalysisResult(
                success = true,
                stockCode = stockCode,
                overallScore = maxStrength,
                recommendation = result.finalRecommendation.ifBlank { "WATCH" },
                reasoning = result.agentAnalysis,
                rawOutput = "策略命中: ${result.strategyHits.joinToString { "${it.strategyName}(${it.strength})" }}"
            )
        }
    }
}

/** Agent 實現 */
class AgentStockAnalysisService : StockAnalysisService {
    override suspend fun analyze(context: Context, stockCode: String): StockAnalysisResult {
        val agent = StockAnalysisAgent(context)
        return agent.analyze(stockCode)
    }
}

object AnalysisRouter {
    fun getService(): StockAnalysisService {
        return when (FeatureFlagManager.resolveRoute(FeatureFlagManager.stockAnalysisRoute)) {
            com.chin.stockanalysis.config.AgentRoute.AGENT_FRAMEWORK -> AgentStockAnalysisService()
            else -> LegacyStockAnalysisService()
        }
    }
}
