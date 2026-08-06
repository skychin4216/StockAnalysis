package com.chin.stockanalysis.strategy.agent

import android.content.Context
import com.chin.stockanalysis.strategy.HoldingPeriod
import kotlinx.coroutines.*

/**
 * ## Agent 編排器
 *
 * 根據 UserIntent 路由到對應的 Agent 組合，
 * 派生 Scout / Analyst / Guardian 並行執行，
 * 收集所有 Announce 後彙總生成最終報告。
 *
 * ### 路由規則
 * - QUICK_SCAN → Scout only
 * - RISK_CHECK → Guardian only
 * - FOLLOW_UP → Analyst only
 * - DEEP_ANALYSIS → Scout + Analyst + Guardian + Executor（按週期配超時）
 */
class AgentOrchestrator(
    private val appContext: Context
) {
    private val spawner = SubAgentSpawner(appContext)
    private val sessionManager = AgentSessionManager()

    /**
     * 執行意圖 → 返回最終報告
     */
    suspend fun execute(intent: UserIntent): OrchestratorResult {
        return when (intent.type) {
            IntentType.QUICK_SCAN -> executeQuickScan(intent)
            IntentType.RISK_CHECK -> executeRiskCheck(intent)
            IntentType.FOLLOW_UP -> executeFollowUp(intent)
            IntentType.DEEP_ANALYSIS -> executeDeepAnalysis(intent)
        }
    }

    /**
     * 取消指定會話
     */
    fun cancel(sessionId: String) {
        sessionManager.cancelSession(sessionId)
    }

    /**
     * 取消所有
     */
    fun cancelAll() {
        sessionManager.cancelAll()
    }

    // ─── 快速掃描：Scout only ───
    private suspend fun executeQuickScan(intent: UserIntent): OrchestratorResult {
        val sessionId = "quick_${System.currentTimeMillis()}"
        val session = AgentSessionMemory()

        return coroutineScope {
            val scoutTask = SimpleAgentTask("scout_market", "市場環境掃描") { ctx ->
                scanMarketEnvironment(ctx)
            }
            val scoutDeferred = spawner.spawn(AgentRoles.SCOUT, scoutTask, session, this)

            sessionManager.registerSession(sessionId, scoutDeferred)
            val scoutAnnounce = scoutDeferred.await()

            OrchestratorResult(
                sessionId = sessionId,
                intent = intent,
                announces = listOf(scoutAnnounce),
                summary = buildQuickSummary(scoutAnnounce)
            )
        }
    }

    // ─── 風控掃描：Guardian only ───
    private suspend fun executeRiskCheck(intent: UserIntent): OrchestratorResult {
        val sessionId = "risk_${System.currentTimeMillis()}"
        val session = AgentSessionMemory()

        return coroutineScope {
            val guardianTask = SimpleAgentTask("guardian_risk", "持倉風險掃描") { ctx ->
                scanPortfolioRisk(ctx, intent.target)
            }
            val guardianDeferred = spawner.spawn(AgentRoles.GUARDIAN, guardianTask, session, this)

            sessionManager.registerSession(sessionId, guardianDeferred)
            val guardianAnnounce = guardianDeferred.await()

            OrchestratorResult(
                sessionId = sessionId,
                intent = intent,
                announces = listOf(guardianAnnounce),
                summary = buildRiskSummary(guardianAnnounce)
            )
        }
    }

    // ─── 追問：Analyst only ───
    private suspend fun executeFollowUp(intent: UserIntent): OrchestratorResult {
        val sessionId = "followup_${System.currentTimeMillis()}"
        val session = AgentSessionMemory()

        return coroutineScope {
            val analystTask = SimpleAgentTask("analyst_followup", "追問分析: ${intent.target}") { ctx ->
                analyzeStock(ctx, intent.target ?: "")
            }
            val analystDeferred = spawner.spawn(AgentRoles.ANALYST, analystTask, session, this)

            sessionManager.registerSession(sessionId, analystDeferred)
            val analystAnnounce = analystDeferred.await()

            OrchestratorResult(
                sessionId = sessionId,
                intent = intent,
                announces = listOf(analystAnnounce),
                summary = buildFollowUpSummary(analystAnnounce)
            )
        }
    }

    // ─── 深度分析：Scout + Analyst + Guardian 並行 ───
    private suspend fun executeDeepAnalysis(intent: UserIntent): OrchestratorResult {
        val sessionId = "deep_${System.currentTimeMillis()}"
        val session = AgentSessionMemory()
        val timeout = getTimeoutForPeriod(intent.period)

        return coroutineScope {
            // Phase 1: Scout 先行（市場環境）
            val scoutTask = SimpleAgentTask("scout_env", "市場環境感知") { ctx ->
                scanMarketEnvironment(ctx)
            }
            val scoutDeferred = spawner.spawn(AgentRoles.SCOUT, scoutTask, session, this)
            val scoutAnnounce = scoutDeferred.await()

            // 將 Scout 結果存入 Session 供其他 Agent 讀取
            session.putSlot("market_context", scoutAnnounce.result)

            // Phase 2: Analyst + Guardian 並行
            val analystTask = SimpleAgentTask("analyst_deep", "深度分析: ${intent.target ?: "全市場"}") { ctx ->
                analyzeStock(ctx, intent.target ?: "")
            }
            val guardianTask = SimpleAgentTask("guardian_risk", "風控評估") { ctx ->
                scanPortfolioRisk(ctx, intent.target)
            }

            val analystDeferred = spawner.spawn(AgentRoles.ANALYST, analystTask, session, this)
            val guardianDeferred = spawner.spawn(AgentRoles.GUARDIAN, guardianTask, session, this)

            // 註冊一個父 Job 用於級聯取消
            val parentJob = launch {
                listOf(analystDeferred, guardianDeferred).awaitAll()
            }
            sessionManager.registerSession(sessionId, parentJob)

            val analystAnnounce = withTimeout(timeout) { analystDeferred.await() }
            val guardianAnnounce = withTimeout(timeout) { guardianDeferred.await() }

            // Phase 3: 彙總
            val announces = listOf(scoutAnnounce, analystAnnounce, guardianAnnounce)
            OrchestratorResult(
                sessionId = sessionId,
                intent = intent,
                announces = announces,
                summary = buildDeepSummary(announces, intent.period)
            )
        }
    }

    private fun getTimeoutForPeriod(period: HoldingPeriod?): Long {
        return when (period) {
            HoldingPeriod.ULTRA_SHORT -> 30_000L
            HoldingPeriod.SHORT -> 60_000L
            HoldingPeriod.MID -> 90_000L
            HoldingPeriod.LONG -> 120_000L
            null -> 60_000L
        }
    }

    // ─── 任務實現（佔位，後續接入實際分析邏輯）───

    private suspend fun scanMarketEnvironment(ctx: AgentContext) {
        ctx.log("掃描市場環境...")
        // TODO: 接入 MarketAnalyzer, SectorRotationEngine, Level2DataProvider
        ctx.recordToolResult("marketDirection", "OSCILLATION")
        ctx.recordToolResult("hotSectors", listOf<String>())
        ctx.recordToolResult("sentiment", mapOf("limitUp" to 0, "limitDown" to 0))
        ctx.log("市場環境掃描完成")
    }

    private suspend fun analyzeStock(ctx: AgentContext, stockCode: String) {
        ctx.log("分析股票: $stockCode")
        // TODO: 接入 DAG Pipeline 分析鏈
        ctx.recordToolResult("score", 0)
        ctx.recordToolResult("recommendation", "WATCH")
        ctx.log("分析完成")
    }

    private suspend fun scanPortfolioRisk(ctx: AgentContext, targetStock: String?) {
        ctx.log("掃描持倉風險...")
        // TODO: 接入 ATR 止損, 倉位控制
        ctx.recordToolResult("riskLevel", "LOW")
        ctx.recordToolResult("stopLoss", emptyMap<String, Double>())
        ctx.log("風險掃描完成")
    }

    // ─── 報告生成 ───

    private fun buildQuickSummary(scout: AgentAnnounce): String {
        return buildString {
            appendLine("📊 快速市場掃描")
            appendLine("狀態: ${scout.status}")
            if (scout.result.isNotEmpty()) {
                scout.result.forEach { (k, v) -> appendLine("  $k: $v") }
            }
        }
    }

    private fun buildRiskSummary(guardian: AgentAnnounce): String {
        return buildString {
            appendLine("🛡️ 持倉風險報告")
            appendLine("狀態: ${guardian.status}")
            if (guardian.result.isNotEmpty()) {
                guardian.result.forEach { (k, v) -> appendLine("  $k: $v") }
            }
        }
    }

    private fun buildFollowUpSummary(analyst: AgentAnnounce): String {
        return buildString {
            appendLine("📊 追問分析結果")
            appendLine("狀態: ${analyst.status}")
            if (analyst.result.isNotEmpty()) {
                analyst.result.forEach { (k, v) -> appendLine("  $k: $v") }
            }
        }
    }

    private fun buildDeepSummary(announces: List<AgentAnnounce>, period: HoldingPeriod?): String {
        return buildString {
            appendLine("📈 深度分析報告 (${period?.label ?: "短線"})")
            appendLine()
            for (announce in announces) {
                val emoji = when (announce.role) {
                    "scout" -> "🔍"
                    "analyst" -> "📊"
                    "guardian" -> "🛡️"
                    else -> "📋"
                }
                appendLine("$emoji ${announce.role} [${announce.status}]")
                announce.result.forEach { (k, v) -> appendLine("  $k: $v") }
                if (announce.errors.isNotEmpty()) {
                    appendLine("  ⚠️ ${announce.errors.joinToString { "${it.type}: ${it.message}" }}")
                }
                appendLine()
            }
        }
    }

}

/**
 * 編排器執行結果
 */
data class OrchestratorResult(
    val sessionId: String,
    val intent: UserIntent,
    val announces: List<AgentAnnounce>,
    val summary: String
) {
    val allSuccess: Boolean get() = announces.all { it.isSuccess }
    val anyDegraded: Boolean get() = announces.any { it.status == AgentAnnounce.Status.DEGRADED }
}
