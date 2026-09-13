package com.chin.stockanalysis.agent.router

import android.content.Context
import com.chin.stockanalysis.agent.risk.HoldingRisk
import com.chin.stockanalysis.agent.risk.RiskManagementAgent
import com.chin.stockanalysis.agent.risk.RiskScanResult
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.trade.AutoSellEngine

/**
 * ## 风控路由层
 *
 * Legacy: AutoSellEngine.evaluateAll() → 转换为 RiskScanResult
 * Agent: RiskManagementAgent.scanPortfolio()
 */
interface RiskManagementService {
    suspend fun scanPortfolio(context: Context): RiskScanResult
}

/** Legacy 实现 — 调用 AutoSellEngine.evaluateAll() */
class LegacyRiskManagementService : RiskManagementService {
    override suspend fun scanPortfolio(context: Context): RiskScanResult {
        val engine = StrategyEngineHolder.get()
        val strategies = engine.getStrategies()
        val sellEngine = AutoSellEngine(context)

        val decisions = sellEngine.evaluateAll(strategies)

        if (decisions.isEmpty()) {
            return RiskScanResult(
                success = true,
                portfolioRiskLevel = "LOW",
                rawOutput = "无活跃持仓，无需风控扫描"
            )
        }

        val holdings = decisions.map { d ->
            val riskStatus = when {
                d.shouldSell && d.urgency >= 8 -> "DANGER"
                d.shouldSell -> "WARNING"
                d.profitPct < -3.0 -> "CAUTION"
                else -> "SAFE"
            }
            HoldingRisk(
                code = d.order.stockCode,
                riskStatus = riskStatus,
                profitPct = d.profitPct,
                maxDrawdown = 0.0, // AutoSellEngine 不提供此字段
                daysHeld = 0,     // AutoSellEngine 不提供此字段
                recommendation = if (d.shouldSell) "建议卖出" else "继续持有",
                reason = d.reason
            )
        }

        val sellCount = decisions.count { it.shouldSell }
        val riskLevel = when {
            sellCount >= decisions.size / 2 -> "HIGH"
            sellCount > 0 -> "MEDIUM"
            else -> "LOW"
        }
        val urgentAlerts = decisions.filter { it.shouldSell && it.urgency >= 7 }
            .map { "[${it.order.stockCode}] ${it.reason}" }

        return RiskScanResult(
            success = true,
            portfolioRiskLevel = riskLevel,
            holdings = holdings,
            urgentAlerts = urgentAlerts,
            rawOutput = decisions.joinToString("\n") {
                "${it.order.stockCode}: ${it.reason} (profit: ${"%.2f".format(it.profitPct)}%)"
            }
        )
    }
}

/** Agent 实现 */
class AgentRiskManagementService : RiskManagementService {
    override suspend fun scanPortfolio(context: Context): RiskScanResult {
        val agent = RiskManagementAgent(context)
        return agent.scanPortfolio()
    }
}

object RiskRouter {
    fun getService(): RiskManagementService {
        return when (FeatureFlagManager.resolveRoute(FeatureFlagManager.riskManagementRoute)) {
            com.chin.stockanalysis.config.AgentRoute.AGENT_FRAMEWORK -> AgentRiskManagementService()
            else -> LegacyRiskManagementService()
        }
    }
}
