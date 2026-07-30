package com.chin.stockanalysis.agent.core

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.v2.FinalDecision
import com.chin.stockanalysis.agent.v2.MarketEnvironment
import com.chin.stockanalysis.agent.v2.PositionWaterValve
import com.chin.stockanalysis.agent.v2.ProfitQualityAnalyzer
import com.chin.stockanalysis.agent.v2.V2DecisionMatrix
import com.chin.stockanalysis.stock.data.StockDataFacade
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import kotlinx.coroutines.*

/**
 * Orchestrator — 場景編排者
 *
 * 職責：
 * 1. 根據 UserIntent 確定 Agent 組合
 * 2. 並行派生 Scout / Analyst / Guardian
 * 3. 收集所有 Announce，彙總為最終報告
 * 4. 派生 Executor 執行交易動作
 * 5. 處理降級（degraded 結果降低權重）
 *
 * 使用方式：
 * ```kotlin
 * val orchestrator = AgentOrchestrator(context)
 * val result = orchestrator.execute(intent)
 * ```
 */
class AgentOrchestrator(internal val appContext: Context) {

    companion object {
        private const val TAG = "AgentOrchestrator"
    }

    internal val spawner = SubAgentSpawner()
    private val sessionManager = AgentSessionManager.instance
    private val intentRouter = IntentRouter()

    /**
     * 執行分析任務
     *
     * @param intent 用戶意圖（已路由）
     * @param stockCode 目標股票代碼（可選，null=全市場）
     * @param stockName 股票名稱
     * @param onProgress 進度回調（角色名 → 狀態描述）
     * @return 最終彙總結果
     */
    suspend fun execute(
        intent: UserIntent,
        stockCode: String? = null,
        stockName: String? = null,
        onProgress: ((String, String) -> Unit)? = null
    ): OrchestratorResult {
        val sessionId = "${stockCode ?: "market"}_${System.currentTimeMillis()}"
        val session = AgentSession(sessionId)
        val scope = sessionManager.createSession(sessionId)

        // 寫入 session 基礎信息
        session.setSlot("stockCode", stockCode)
        session.setSlot("stockName", stockName)
        session.setSlot("intent", intent)

        val clusterConfig = AgentClusterConfig.forPeriod(
            intent.period ?: com.chin.stockanalysis.strategy.HoldingPeriod.SHORT
        )

        Log.i(TAG, "▶ 開始編排: intent=${intent.type}, period=${intent.period}, " +
            "stock=$stockCode, session=$sessionId")

        return try {
            when (intent.type) {
                IntentType.QUICK_SCAN -> executeQuickScan(session, scope, onProgress)
                IntentType.RISK_CHECK -> executeRiskCheck(session, scope, onProgress)
                IntentType.FOLLOW_UP -> executeFollowUp(session, scope, stockCode, onProgress)
                IntentType.DEEP_ANALYSIS -> executeDeepAnalysis(
                    session, scope, clusterConfig, stockCode, stockName, onProgress
                )
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "會話被取消: $sessionId")
            OrchestratorResult(cancelled = true)
        } catch (e: Exception) {
            Log.e(TAG, "編排異常: ${e.message}", e)
            OrchestratorResult(error = e.message ?: "unknown")
        } finally {
            sessionManager.cancelSession(sessionId)
        }
    }

    // ── 快速掃描：僅 Scout，無 LLM ──
    private suspend fun executeQuickScan(
        session: AgentSession,
        scope: CoroutineScope,
        onProgress: ((String, String) -> Unit)?
    ): OrchestratorResult {
        onProgress?.invoke("scout", "市場環境掃描中...")

        val scoutAnnounce = spawner.spawn(
            role = AgentRoles.SCOUT,
            task = AgentTask { ctx -> ScoutTask(appContext).execute(ctx) },
            session = session,
            scope = scope
        ).await()

        return OrchestratorResult(
            announces = listOf(scoutAnnounce),
            summary = buildQuickScanSummary(scoutAnnounce)
        )
    }

    // ── 風控掃描：僅 Guardian ──
    private suspend fun executeRiskCheck(
        session: AgentSession,
        scope: CoroutineScope,
        onProgress: ((String, String) -> Unit)?
    ): OrchestratorResult {
        onProgress?.invoke("guardian", "持倉風險掃描中...")

        val guardianAnnounce = spawner.spawn(
            role = AgentRoles.GUARDIAN,
            task = AgentTask { ctx -> GuardianTask(appContext).execute(ctx) },
            session = session,
            scope = scope
        ).await()

        return OrchestratorResult(
            announces = listOf(guardianAnnounce),
            summary = buildRiskCheckSummary(guardianAnnounce)
        )
    }

    // ── 追問：僅 Analyst ──
    private suspend fun executeFollowUp(
        session: AgentSession,
        scope: CoroutineScope,
        stockCode: String?,
        onProgress: ((String, String) -> Unit)?
    ): OrchestratorResult {
        onProgress?.invoke("analyst", "深度分析中...")

        val analystAnnounce = spawner.spawn(
            role = AgentRoles.ANALYST,
            task = AgentTask { ctx -> AnalystTask(appContext, stockCode).execute(ctx) },
            session = session,
            scope = scope
        ).await()

        return OrchestratorResult(
            announces = listOf(analystAnnounce),
            summary = buildAnalystSummary(analystAnnounce)
        )
    }

    // ── 深度分析：Scout + DeepAnalyst + Guardian 並行 → 決策矩陣 → 彙總 ──
    private suspend fun executeDeepAnalysis(
        session: AgentSession,
        scope: CoroutineScope,
        config: AgentClusterConfig,
        stockCode: String?,
        stockName: String?,
        onProgress: ((String, String) -> Unit)?
    ): OrchestratorResult {
        val startTime = System.currentTimeMillis()

        // Phase 1: Scout 先行（提供市場環境給 Analyst）
        onProgress?.invoke("scout", "🔍 市場環境偵察...")
        val scoutDeferred = spawner.spawn(
            role = AgentRoles.SCOUT.copy(timeoutMs = config.orchestratorTimeout / 6),
            task = AgentTask { ctx -> ScoutTask(appContext).execute(ctx) },
            session = session,
            scope = scope
        )

        // Scout 完成後寫入 session（Analyst 可讀取）
        val scoutAnnounce = scoutDeferred.await()
        session.setSlot("scoutResult", scoutAnnounce.result)
        onProgress?.invoke("scout", "🔍 偵察完成: ${scoutAnnounce.status}")

        // Phase 2: DeepAnalyst（多子Agent深度分析） + Guardian 並行
        onProgress?.invoke("analyst", "📊 深度分析中（${config.analystSteps}子Agent）...")
        onProgress?.invoke("guardian", "🛡️ 風控評估中...")

        val analystRole = AgentRoles.ANALYST.copy(timeoutMs = config.analystTimeout)
        val guardianRole = AgentRoles.GUARDIAN.copy(timeoutMs = config.guardianTimeout)

        val analystDeferred = spawner.spawn(
            role = analystRole,
            task = AgentTask { ctx ->
                if (stockCode != null) {
                    DeepAnalystEngine(appContext, stockCode, stockName, config.analystSteps).execute(ctx)
                } else {
                    AnalystTask(appContext, null).execute(ctx)
                }
            },
            session = session,
            scope = scope
        )
        val guardianDeferred = spawner.spawn(
            role = guardianRole,
            task = AgentTask { ctx -> GuardianTask(appContext).execute(ctx) },
            session = session,
            scope = scope
        )

        val analystAnnounce = analystDeferred.await()
        val guardianAnnounce = guardianDeferred.await()

        session.setSlot("analystResult", analystAnnounce.result)
        session.setSlot("guardianResult", guardianAnnounce.result)

        onProgress?.invoke("analyst", "📊 分析完成: ${analystAnnounce.status}")
        onProgress?.invoke("guardian", "🛡️ 風控完成: ${guardianAnnounce.status}")

        // Phase 2.5: V2 決策矩陣（市場環境 × 利潤質量 × 估值）
        onProgress?.invoke("orchestrator", "🎯 決策矩陣計算中...")
        val decisionOutput = computeDecisionMatrix(stockCode, scoutAnnounce)
        decisionOutput?.let { session.setSlot("decisionMatrix", it.decision) }

        // Phase 3: 彙總（Orchestrator 用自己的風格重新組織）
        val allAnnounces = listOf(scoutAnnounce, analystAnnounce, guardianAnnounce)
        val summary = buildDeepAnalysisSummary(allAnnounces, config, decisionOutput)

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "◀ 深度分析完成: ${elapsed}ms, " +
            "scout=${scoutAnnounce.status}, analyst=${analystAnnounce.status}, " +
            "guardian=${guardianAnnounce.status}, decision=${decisionOutput?.decision?.action ?: "N/A"}")

        return OrchestratorResult(
            announces = allAnnounces,
            summary = summary,
            totalElapsedMs = elapsed,
            degraded = allAnnounces.any { it.isDegraded }
        )
    }

    /** 決策矩陣輸出（含 V2 估值評估） */
    private data class DecisionMatrixOutput(
        val decision: FinalDecision,
        val peBand: String,              // 低估/合理/高估/無法評估
        val valuationWarning: String?    // PE 陷阱警示
    )

    /**
     * V2 決策矩陣計算（遷移自 V2AgentRunner）
     *
     * 融合三維度：市場環境（Scout）× 利潤質量（ProfitQualityAnalyzer）× PE/PB 估值
     * 失敗不阻塞主流程，返回 null。
     */
    private suspend fun computeDecisionMatrix(
        stockCode: String?,
        scoutAnnounce: AgentAnnounce
    ): DecisionMatrixOutput? {
        if (stockCode == null) return null
        return try {
            val marketReport = scoutAnnounce.getResult<MarketAnalyzer.MarketReport>("marketReport")
            val direction = scoutAnnounce.getResult<String>("marketDirection") ?: "OSCILLATION"
            val strength = scoutAnnounce.getResult<Int>("trendStrength") ?: 30

            val marketEnv = MarketEnvironment(
                direction = direction,
                strength = strength,
                description = "來自 Scout 偵察",
                sellType = marketReport?.sellType?.sellType ?: "NONE"
            )

            val profitQuality = ProfitQualityAnalyzer.analyze(stockCode)

            val positionCap = marketReport?.let {
                PositionWaterValve.calculatePositionCap(it).capPercent
            } ?: 50

            // PE/PB：優先實時行情（多源合併），仍為 0 時回退本地快照（DB v12 已持久化）
            var pe = 0.0
            var pb = 0.0
            try {
                val quote = StockDataFacade.getInstance(appContext)
                    .getAnalysisData(stockCode).quote
                pe = quote?.pe ?: 0.0
                pb = quote?.pb ?: 0.0
            } catch (_: Exception) { }
            if (pe <= 0.0 || pb <= 0.0) {
                try {
                    val snap = com.chin.stockanalysis.stock.database.StockDatabase
                        .getInstance(appContext).dailySnapshotDao().getByCode(stockCode, 5)
                        .firstOrNull { it.pe > 0 || it.pb > 0 }
                    if (snap != null) {
                        if (pe <= 0.0 && snap.pe > 0) pe = snap.pe
                        if (pb <= 0.0 && snap.pb > 0) pb = snap.pb
                    }
                } catch (_: Exception) { }
            }

            val peBand = when {
                pe <= 0 -> "無法評估"
                pe < 20 -> "低估"
                pe < 40 -> "合理"
                else -> "高估"
            }
            // PE 陷阱警示（遷移自 V2AgentRunner）：一次性浮盈導致靜態 PE 極低
            val valuationWarning = if (profitQuality.qualityLevel == com.chin.stockanalysis.agent.v2.ProfitQualityLevel.ONE_TIME_PROFIT && pe > 0 && pe < 20) {
                "⚠️ 市盈率陷阱：一次性浮盈導致靜態PE極低，建議使用扣非PE重新估值"
            } else null

            val decision = V2DecisionMatrix.evaluate(
                V2DecisionMatrix.DecisionInput(
                    environment = marketEnv,
                    positionCap = positionCap,
                    profitQuality = profitQuality,
                    pe = pe,
                    pb = pb
                )
            )
            DecisionMatrixOutput(decision, peBand, valuationWarning)
        } catch (e: Exception) {
            Log.w(TAG, "決策矩陣計算失敗（不阻塞）: ${e.message}")
            null
        }
    }

    // ── 報告構建（Orchestrator 重新組織，非原始轉發） ──

    private fun buildQuickScanSummary(scout: AgentAnnounce): String = buildString {
        appendLine("═══ 快速市場掃描 ═══")
        if (scout.isSuccess || scout.isDegraded) {
            scout.getResult<String>("marketDirection")?.let { appendLine("市場方向: $it") }
            scout.getResult<List<String>>("hotSectors")?.let {
                appendLine("熱門板塊: ${it.take(5).joinToString(", ")}")
            }
            scout.getResult<String>("sentiment")?.let { appendLine("市場情緒: $it") }
        } else {
            appendLine("⚠️ 掃描失敗: ${scout.errors.firstOrNull()?.message}")
        }
    }

    private fun buildRiskCheckSummary(guardian: AgentAnnounce): String = buildString {
        appendLine("═══ 風控掃描報告 ═══")
        if (guardian.isSuccess || guardian.isDegraded) {
            guardian.getResult<String>("positionAdvice")?.let { appendLine("倉位建議: $it") }
            guardian.getResult<List<String>>("riskWarnings")?.let { warnings ->
                if (warnings.isNotEmpty()) {
                    appendLine("⚠️ 風險警示:")
                    warnings.forEach { appendLine("  • $it") }
                }
            }
            if (guardian.isDegraded) appendLine("（部分分析使用算法後備）")
        } else {
            appendLine("⚠️ 風控掃描失敗: ${guardian.errors.firstOrNull()?.message}")
        }
    }

    private fun buildAnalystSummary(analyst: AgentAnnounce): String = buildString {
        appendLine("═══ 深度分析 ═══")
        if (analyst.isSuccess || analyst.isDegraded) {
            analyst.getResult<Int>("score")?.let { appendLine("綜合評分: $it") }
            analyst.getResult<String>("recommendation")?.let { appendLine("建議: $it") }
            analyst.getResult<String>("summary")?.let { appendLine(it) }
            if (analyst.isDegraded) appendLine("（部分分析降級）")
        } else {
            appendLine("⚠️ 分析失敗: ${analyst.errors.firstOrNull()?.message}")
        }
    }

    private fun buildDeepAnalysisSummary(
        announces: List<AgentAnnounce>,
        config: AgentClusterConfig,
        decisionOutput: DecisionMatrixOutput? = null
    ): String = buildString {
        val scout = announces.firstOrNull { it.role == "scout" }
        val analyst = announces.firstOrNull { it.role == "analyst" }
        val guardian = announces.firstOrNull { it.role == "guardian" }

        appendLine("═══ ${config.period.name} 深度分析報告 ═══")

        // 市場環境（來自 Scout）
        if (scout != null && !scout.isFailed) {
            appendLine("── 市場環境 ──")
            scout.getResult<String>("marketDirection")?.let { appendLine("方向: $it") }
            scout.getResult<List<String>>("hotSectors")?.let {
                appendLine("熱點: ${it.take(3).joinToString(", ")}")
            }
        }

        // 分析結論（來自 DeepAnalyst）
        if (analyst != null && !analyst.isFailed) {
            appendLine("── 分析結論 ──")
            analyst.getResult<Int>("score")?.let { appendLine("評分: $it/100") }
            analyst.getResult<String>("recommendation")?.let { appendLine("建議: $it") }
            analyst.getResult<Int>("chainScore")?.let { appendLine("產業鏈打分: $it") }
            analyst.getResult<String>("riskLevel")?.let { appendLine("風控等級: $it") }
            analyst.getResult<String>("positionAdjust")?.let { appendLine("輿情倉位微調: $it") }
            @Suppress("UNCHECKED_CAST")
            (analyst.result["entryZones"] as? List<String>)?.let { zones ->
                if (zones.isNotEmpty()) appendLine("低吸區間: ${zones.joinToString(" / ")}")
            }
            @Suppress("UNCHECKED_CAST")
            (analyst.result["riskFactors"] as? List<String>)?.let { factors ->
                factors.take(3).forEach { appendLine("⚠️ $it") }
            }
            analyst.getResult<String>("summary")?.let { appendLine(it) }
        }

        // 風控意見（來自 Guardian）
        if (guardian != null && !guardian.isFailed) {
            appendLine("── 風控意見 ──")
            guardian.getResult<String>("positionAdvice")?.let { appendLine("倉位: $it") }
            guardian.getResult<List<String>>("riskWarnings")?.let { warnings ->
                warnings.take(3).forEach { appendLine("⚠️ $it") }
            }
        }

        // 決策矩陣（V2 遷移：環境 × 利潤質量 × 估值）
        decisionOutput?.let { output ->
            val decision = output.decision
            appendLine("── 🎯 決策矩陣 ──")
            appendLine("操作: ${decision.action} | 建議倉位: ${decision.positionPercent}%")
            appendLine("估值: PE ${output.peBand}")
            appendLine("策略: ${decision.strategy}")
            decision.tTradingAdvice?.let { appendLine("做T建議: $it") }
            appendLine("止損規則: ${decision.stopLossRule}")
            output.valuationWarning?.let { appendLine(it) }
            decision.riskWarning?.let { appendLine("⚠️ $it") }
        }

        // 降級標記
        val degradedCount = announces.count { it.isDegraded }
        if (degradedCount > 0) {
            appendLine("── ⚠️ ${degradedCount}個環節降級（使用算法後備） ──")
        }
    }
}

/**
 * Orchestrator 最終輸出
 */
data class OrchestratorResult(
    val announces: List<AgentAnnounce> = emptyList(),
    val summary: String = "",
    val totalElapsedMs: Long = 0,
    val degraded: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null && !cancelled && announces.any { it.isSuccess || it.isDegraded }

    /** 獲取指定角色的 announce */
    fun announceOf(role: String): AgentAnnounce? = announces.firstOrNull { it.role == role }
}

// ════════════════════════════════════════════════════════════════
//  具體 Task 實現（包裝現有組件）
// ════════════════════════════════════════════════════════════════

/**
 * Scout 任務 — 包裝現有 MarketAnalyzer + StockDataFacade
 * 純量化，無 LLM。
 */
class ScoutTask(private val appContext: Context) {
    suspend fun execute(ctx: AgentContext): Map<String, Any?> {
        ctx.requirePermission(AgentPermission.READ_MARKET_DATA)
        ctx.log("開始市場環境偵察")

        return try {
            // 調用現有 MarketAnalyzer（holdingCodes 傳空 = 不評估持倉）
            val marketReport = com.chin.stockanalysis.strategy.market.MarketAnalyzer
                .analyze(appContext, emptyList())

            val result = mutableMapOf<String, Any?>(
                "marketDirection" to marketReport.trend.direction,
                "trendStrength" to marketReport.trend.strength,
                "summary" to marketReport.summary,
                "marketReport" to marketReport  // 供決策矩陣使用（PositionWaterValve）
            )

            // 板塊輪動建議
            result["sectorAdvice"] = marketReport.sectorAdvice.toString()

            // 外圍市場
            if (marketReport.overseas.direction != "UNKNOWN") {
                result["overseasDirection"] = marketReport.overseas.direction
                result["overseasStrength"] = marketReport.overseas.strength
                result["overseasHint"] = marketReport.overseas.impactHint
            }

            ctx.log("偵察完成: direction=${marketReport.trend.direction}, " +
                "strength=${marketReport.trend.strength}, overseas=${marketReport.overseas.direction}")
            result

        } catch (e: Exception) {
            ctx.recordError("SCOUT_ERROR", e.message ?: "市場偵察失敗")
            mapOf("marketDirection" to "UNKNOWN", "error" to e.message)
        }
    }
}

/**
 * Analyst 任務 — 包裝現有 AgentPipelineOrchestrator / StockAnalysisAgent
 * 調用 LLM（強模型）。
 */
class AnalystTask(
    private val appContext: Context,
    private val stockCode: String?
) {
    suspend fun execute(ctx: AgentContext): Map<String, Any?> {
        ctx.requirePermission(AgentPermission.CALL_LLM_STRONG)

        if (stockCode == null) {
            return mapOf("error" to "無目標股票", "recommendation" to "WATCH")
        }

        ctx.log("開始深度分析: $stockCode")

        return try {
            // 調用現有 StockAnalysisAgent
            val agent = com.chin.stockanalysis.agent.stock.StockAnalysisAgent(appContext)
            val result = agent.analyze(stockCode, null, null)

            ctx.log("分析完成: score=${result.overallScore}")
            mapOf(
                "score" to result.overallScore,
                "recommendation" to result.recommendation,
                "confidence" to result.confidence,
                "targetPrice" to result.targetPrice,
                "stopLoss" to result.stopLoss,
                "summary" to result.reasoning,
                "riskFactors" to result.riskFactors
            )
        } catch (e: Exception) {
            ctx.recordError("ANALYST_ERROR", e.message ?: "分析失敗", "ALGORITHM")
            mapOf(
                "score" to 50,
                "recommendation" to "WATCH",
                "summary" to "LLM 不可用，使用默認評分",
                "fallback" to "ALGORITHM"
            )
        }
    }
}

/**
 * Guardian 任務 — 包裝現有 RiskManagementAgent
 * 調用 LLM（快速模型）或純算法後備。
 */
class GuardianTask(private val appContext: Context) {
    suspend fun execute(ctx: AgentContext): Map<String, Any?> {
        ctx.requirePermission(AgentPermission.READ_PORTFOLIO)
        ctx.log("開始風控掃描")

        return try {
            val agent = com.chin.stockanalysis.agent.risk.RiskManagementAgent(appContext)
            val result = agent.scanPortfolio(null)

            ctx.log("風控完成: ${result.urgentAlerts.size} 個警示")
            mapOf(
                "riskLevel" to result.portfolioRiskLevel,
                "positionAdvice" to result.positionAdvice,
                "riskWarnings" to result.urgentAlerts,
                "marketRisk" to result.marketRisk,
                "holdings" to result.holdings.map { "${it.code}: ${it.recommendation}" }
            )
        } catch (e: Exception) {
            ctx.recordError("GUARDIAN_ERROR", e.message ?: "風控失敗", "ATR_ALGORITHM")
            mapOf(
                "riskLevel" to "UNKNOWN",
                "positionAdvice" to "LLM不可用，建議保守倉位(≤30%)",
                "riskWarnings" to listOf("風控Agent異常: ${e.message}"),
                "fallback" to "ATR_ALGORITHM"
            )
        }
    }
}
