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
 * Orchestrator — 场景编排者
 *
 * 职责：
 * 1. 根据 UserIntent 确定 Agent 组合
 * 2. 并行派生 Scout / Analyst / Guardian
 * 3. 收集所有 Announce，汇总为最终报告
 * 4. 派生 Executor 执行交易动作
 * 5. 处理降级（degraded 结果降低权重）
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
    private val planningAgent = PlanningAgent(appContext)

    /**
     * 执行分析任务
     *
     * @param intent 用户意图（已路由）
     * @param stockCode 目标股票代码（可选，null=全市场）
     * @param stockName 股票名称
     * @param onProgress 进度回调（角色名 → 状态描述）
     * @return 最终汇总结果
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

        // 写入 session 基础信息
        session.setSlot("stockCode", stockCode)
        session.setSlot("stockName", stockName)
        session.setSlot("intent", intent)

        val clusterConfig = AgentClusterConfig.forPeriod(
            intent.period ?: com.chin.stockanalysis.strategy.HoldingPeriod.SHORT
        )

        Log.i(TAG, "▶ 开始编排: intent=${intent.type}, period=${intent.period}, " +
            "stock=$stockCode, session=$sessionId")

        return try {
            when (intent.type) {
                IntentType.QUICK_SCAN -> executeQuickScan(session, scope, onProgress)
                IntentType.RISK_CHECK -> executeRiskCheck(session, scope, onProgress)
                IntentType.FOLLOW_UP -> executeFollowUp(session, scope, stockCode, onProgress)
                IntentType.DEEP_ANALYSIS -> executeDeepAnalysis(
                    session, scope, clusterConfig, stockCode, stockName, onProgress
                )
                IntentType.GENERAL_CHAT -> executeGeneralChat(session, scope, intent.rawInput, onProgress)
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "会话被取消: $sessionId")
            OrchestratorResult(cancelled = true)
        } catch (e: Exception) {
            Log.e(TAG, "编排异常: ${e.message}", e)
            OrchestratorResult(error = e.message ?: "unknown")
        } finally {
            sessionManager.cancelSession(sessionId)
        }
    }

    // ── 通用问答：单次 LLM 调用，不走任何 pipeline ──
    private suspend fun executeGeneralChat(
        session: AgentSession,
        scope: CoroutineScope,
        userQuestion: String,
        onProgress: ((String, String) -> Unit)?
    ): OrchestratorResult {
        onProgress?.invoke("chat", "回答中...")

        val slot = com.chin.stockanalysis.ai.AiProviderPool.acquire(
            context = appContext,
            callerTag = "Orchestrator.GeneralChat",
            timeoutMs = 25_000L
        ) ?: return OrchestratorResult(error = "AI 服务未就绪")

        return try {
            val answer: String = kotlinx.coroutines.withTimeout(25_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    val sb = StringBuilder()
                    slot.provider.sendMessageStream(
                        messages = listOf(com.chin.stockanalysis.ui.Message(content = userQuestion, isUser = true)),
                        systemPrompt = "你是一个专业的股票投资助手。用户正在问你一个一般性问题。\n" +
                            "请用简洁、专业的方式回答。如果问题与股票投资无关，礼貌地引导回投资话题。\n" +
                            "回答要点：直接回答问题，不要长篇大论。如果涉及投资概念，给出具体例子。200字以内。",
                        onSuccess = { chunk: String -> sb.append(chunk) },
                        onComplete = { full: String -> cont.resume(full.ifEmpty { sb.toString() }, null) },
                        onError = { err: String -> cont.resumeWith(Result.failure(Exception(err))) }
                    )
                }
            }
            OrchestratorResult(summary = answer)
        } catch (e: Exception) {
            OrchestratorResult(error = "回答失败: ${e.message}")
        } finally {
            com.chin.stockanalysis.ai.AiProviderPool.releaseNonBlocking(slot)
        }
    }

    // ── 快速扫描：仅 Scout，无 LLM ──
    private suspend fun executeQuickScan(
        session: AgentSession,
        scope: CoroutineScope,
        onProgress: ((String, String) -> Unit)?
    ): OrchestratorResult {
        onProgress?.invoke("scout", "市场环境扫描中...")

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

    // ── 风控扫描：仅 Guardian ──
    private suspend fun executeRiskCheck(
        session: AgentSession,
        scope: CoroutineScope,
        onProgress: ((String, String) -> Unit)?
    ): OrchestratorResult {
        onProgress?.invoke("guardian", "持仓风险扫描中...")

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

    // ── 追问：仅 Analyst ──
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

    // ── 深度分析：PlanningAgent → Scout + DeepAnalyst + Guardian 并行 → 决策矩阵 → 汇总 ──
    private suspend fun executeDeepAnalysis(
        session: AgentSession,
        scope: CoroutineScope,
        config: AgentClusterConfig,
        stockCode: String?,
        stockName: String?,
        onProgress: ((String, String) -> Unit)?
    ): OrchestratorResult {
        val startTime = System.currentTimeMillis()

        // Phase 0: PlanningAgent 智能规划（失败不阻塞，15s 超时）
        val intent = session.getSlot<UserIntent>("intent")
        val analysisPlan = try {
            onProgress?.invoke("planner", "🧠 分析规划中...")
            val planInput = intent ?: UserIntent(IntentType.DEEP_ANALYSIS, rawInput = "")
            planningAgent.plan(planInput, stockCode, stockName)
                .also {
                    session.setSlot("analysisPlan", it)
                    onProgress?.invoke("planner", "🧠 规划完成: depth=${it.depth}, dims=${it.focusDimensions.size}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "PlanningAgent 失败（降级）: ${e.message}")
            PlanningAgent.AnalysisPlan(fallback = true)
        }

        // 根据规划调整分析参数
        val adjustedConfig = when (analysisPlan.depth) {
            PlanningAgent.AnalysisPlan.Depth.QUICK -> config.copy(
                analystSteps = 2,
                analystTimeout = 60_000
            )
            PlanningAgent.AnalysisPlan.Depth.STANDARD -> config
            PlanningAgent.AnalysisPlan.Depth.DEEP -> config.copy(
                analystSteps = (config.analystSteps + 2).coerceAtMost(10),
                analystTimeout = (config.analystTimeout * 1.3).toLong()
            )
        }

        // Phase 1: Scout 先行（提供市场环境给 Analyst）
        onProgress?.invoke("scout", "🔍 市场环境侦察...")
        val scoutDeferred = spawner.spawn(
            role = AgentRoles.SCOUT.copy(timeoutMs = config.orchestratorTimeout / 6),
            task = AgentTask { ctx -> ScoutTask(appContext).execute(ctx) },
            session = session,
            scope = scope
        )

        // Scout 完成后写入 session（Analyst 可读取）
        val scoutAnnounce = scoutDeferred.await()
        session.setSlot("scoutResult", scoutAnnounce.result)
        onProgress?.invoke("scout", "🔍 侦察完成: ${scoutAnnounce.status}")

        // Phase 2: DeepAnalyst（多子Agent深度分析） + Guardian 并行
        onProgress?.invoke("analyst", "📊 深度分析中（${adjustedConfig.analystSteps}子Agent）...")
        onProgress?.invoke("guardian", "🛡️ 风控评估中...")

        val analystRole = AgentRoles.ANALYST.copy(timeoutMs = adjustedConfig.analystTimeout)
        val guardianRole = AgentRoles.GUARDIAN.copy(timeoutMs = config.guardianTimeout)

        val analystDeferred = spawner.spawn(
            role = analystRole,
            task = AgentTask { ctx ->
                if (stockCode != null) {
                    DeepAnalystEngine(appContext, stockCode, stockName, adjustedConfig.analystSteps).execute(ctx)
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
        onProgress?.invoke("guardian", "🛡️ 风控完成: ${guardianAnnounce.status}")

        // Phase 2.5: V2 决策矩阵（市场环境 × 利润质量 × 估值）
        onProgress?.invoke("orchestrator", "🎯 决策矩阵计算中...")
        val decisionOutput = computeDecisionMatrix(stockCode, scoutAnnounce)
        decisionOutput?.let { session.setSlot("decisionMatrix", it.decision) }

        // Phase 3: 汇总（Orchestrator 用自己的风格重新组织）
        val allAnnounces = listOf(scoutAnnounce, analystAnnounce, guardianAnnounce)
        val summary = buildDeepAnalysisSummary(allAnnounces, adjustedConfig, decisionOutput, analysisPlan)

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

    /** 决策矩阵输出（含 V2 估值评估） */
    private data class DecisionMatrixOutput(
        val decision: FinalDecision,
        val peBand: String,              // 低估/合理/高估/无法评估
        val valuationWarning: String?    // PE 陷阱警示
    )

    /**
     * V2 决策矩阵计算（迁移自 V2AgentRunner）
     *
     * 融合三维度：市场环境（Scout）× 利润质量（ProfitQualityAnalyzer）× PE/PB 估值
     * 失败不阻塞主流程，返回 null。
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
                description = "来自 Scout 侦察",
                sellType = marketReport?.sellType?.sellType ?: "NONE"
            )

            val profitQuality = ProfitQualityAnalyzer.analyze(stockCode)

            val positionCap = marketReport?.let {
                PositionWaterValve.calculatePositionCap(it).capPercent
            } ?: 50

            // PE/PB：优先实时行情（多源合并），仍为 0 时回退本地快照（DB v12 已持久化）
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
                pe <= 0 -> "无法评估"
                pe < 20 -> "低估"
                pe < 40 -> "合理"
                else -> "高估"
            }
            // PE 陷阱警示（迁移自 V2AgentRunner）：一次性浮盈导致静态 PE 极低
            val valuationWarning = if (profitQuality.qualityLevel == com.chin.stockanalysis.agent.v2.ProfitQualityLevel.ONE_TIME_PROFIT && pe > 0 && pe < 20) {
                "⚠️ 市盈率陷阱：一次性浮盈导致静态PE极低，建议使用扣非PE重新估值"
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
            Log.w(TAG, "决策矩阵计算失败（不阻塞）: ${e.message}")
            null
        }
    }

    // ── 报告构建（Orchestrator 重新组织，非原始转发） ──

    private fun buildQuickScanSummary(scout: AgentAnnounce): String = buildString {
        appendLine("═══ 快速市场扫描 ═══")
        if (scout.isSuccess || scout.isDegraded) {
            scout.getResult<String>("marketDirection")?.let { appendLine("市场方向: $it") }
            scout.getResult<List<String>>("hotSectors")?.let {
                appendLine("热门板块: ${it.take(5).joinToString(", ")}")
            }
            scout.getResult<String>("sentiment")?.let { appendLine("市场情绪: $it") }
        } else {
            appendLine("⚠️ 扫描失败: ${scout.errors.firstOrNull()?.message}")
        }
    }

    private fun buildRiskCheckSummary(guardian: AgentAnnounce): String = buildString {
        appendLine("═══ 风控扫描报告 ═══")
        if (guardian.isSuccess || guardian.isDegraded) {
            guardian.getResult<String>("positionAdvice")?.let { appendLine("仓位建议: $it") }
            guardian.getResult<List<String>>("riskWarnings")?.let { warnings ->
                if (warnings.isNotEmpty()) {
                    appendLine("⚠️ 风险警示:")
                    warnings.forEach { appendLine("  • $it") }
                }
            }
            if (guardian.isDegraded) appendLine("（部分分析使用算法后备）")
        } else {
            appendLine("⚠️ 风控扫描失败: ${guardian.errors.firstOrNull()?.message}")
        }
    }

    private fun buildAnalystSummary(analyst: AgentAnnounce): String = buildString {
        appendLine("═══ 深度分析 ═══")
        if (analyst.isSuccess || analyst.isDegraded) {
            analyst.getResult<Int>("score")?.let { appendLine("综合评分: $it") }
            analyst.getResult<String>("recommendation")?.let { appendLine("建议: $it") }
            analyst.getResult<String>("summary")?.let { appendLine(it) }
            if (analyst.isDegraded) appendLine("（部分分析降级）")
        } else {
            appendLine("⚠️ 分析失败: ${analyst.errors.firstOrNull()?.message}")
        }
    }

    private fun buildDeepAnalysisSummary(
        announces: List<AgentAnnounce>,
        config: AgentClusterConfig,
        decisionOutput: DecisionMatrixOutput? = null,
        analysisPlan: PlanningAgent.AnalysisPlan? = null
    ): String = buildString {
        val scout = announces.firstOrNull { it.role == "scout" }
        val analyst = announces.firstOrNull { it.role == "analyst" }
        val guardian = announces.firstOrNull { it.role == "guardian" }

        appendLine("═══ ${config.period.name} 深度分析报告 ═══")

        // 分析规划信息
        if (analysisPlan != null && !analysisPlan.fallback) {
            appendLine("── 🧠 分析规划 ──")
            appendLine("分析深度: ${analysisPlan.depth}")
            if (analysisPlan.specialNotes.isNotEmpty()) {
                appendLine("注意事项: ${analysisPlan.specialNotes.joinToString("；")}")
            }
            appendLine()
        }

        // 市场环境（来自 Scout）
        if (scout != null && !scout.isFailed) {
            appendLine("── 🌐 市场环境 ──")
            scout.getResult<String>("marketDirection")?.let {
                val dirEmoji = when (it) { "BULLISH" -> "🔴"; "BEARISH" -> "🟢"; else -> "🟡" }
                appendLine("$dirEmoji 方向: $it")
            }
            scout.getResult<List<String>>("hotSectors")?.let {
                appendLine("热点板块: ${it.take(3).joinToString(", ")}")
            }
            appendLine()
        }

        // 分析结论（来自 DeepAnalyst）— 结构化输出
        if (analyst != null && !analyst.isFailed) {
            appendLine("── 📊 分析结论 ──")
            analyst.getResult<Int>("score")?.let { appendLine("综合评分: $it/100") }
            analyst.getResult<String>("recommendation")?.let { appendLine("建议: $it") }

            // 各维度结论
            analyst.getResult<Int>("chainScore")?.let { cs ->
                val verdict = if (cs >= 40) "✅ 通过" else "❌ 未达标"
                appendLine("产业链打分: $cs/100 $verdict")
            }
            analyst.getResult<String>("riskLevel")?.let { rl ->
                val emoji = when (rl) { "低" -> "🟢"; "中" -> "🟡"; else -> "🔴" }
                appendLine("风控等级: $emoji $rl")
            }
            analyst.getResult<String>("positionAdjust")?.let { appendLine("舆情仓位微调: $it") }

            @Suppress("UNCHECKED_CAST")
            (analyst.result["entryZones"] as? List<String>)?.let { zones ->
                if (zones.isNotEmpty()) appendLine("低吸区间: ${zones.joinToString(" / ")}")
            }
            @Suppress("UNCHECKED_CAST")
            (analyst.result["riskFactors"] as? List<String>)?.let { factors ->
                if (factors.isNotEmpty()) {
                    appendLine("风险因素:")
                    factors.take(3).forEach { appendLine("  ⚠️ $it") }
                }
            }

            // 分析摘要（已结构化，不再原始转发）
            analyst.getResult<String>("summary")?.let { summary ->
                if (summary.isNotBlank()) {
                    appendLine()
                    appendLine(summary)
                }
            }
            appendLine()
        }

        // 风控意见（来自 Guardian）
        if (guardian != null && !guardian.isFailed) {
            appendLine("── 🛡 风控意见 ──")
            guardian.getResult<String>("positionAdvice")?.let { appendLine("仓位建议: $it") }
            guardian.getResult<List<String>>("riskWarnings")?.let { warnings ->
                if (warnings.isNotEmpty()) {
                    warnings.take(3).forEach { appendLine("  ⚠️ $it") }
                } else {
                    appendLine("  ✅ 无重大风险警示")
                }
            }
            appendLine()
        }

        // 决策矩阵（V2 迁移：环境 × 利润质量 × 估值）
        decisionOutput?.let { output ->
            val decision = output.decision
            appendLine("── 🎯 决策矩阵 ──")
            appendLine("操作: ${decision.action} | 建议仓位: ${decision.positionPercent}%")
            appendLine("估值: PE ${output.peBand}")
            appendLine("策略: ${decision.strategy}")
            decision.tTradingAdvice?.let { appendLine("做T建议: $it") }
            appendLine("止损规则: ${decision.stopLossRule}")
            output.valuationWarning?.let { appendLine(it) }
            decision.riskWarning?.let { appendLine("⚠️ $it") }
            appendLine()
        }

        // 降级标记
        val degradedCount = announces.count { it.isDegraded }
        if (degradedCount > 0) {
            appendLine("── ⚠️ ${degradedCount}个环节降级（使用算法后备） ──")
        }
    }
}

/**
 * Orchestrator 最终输出
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

    /** 获取指定角色的 announce */
    fun announceOf(role: String): AgentAnnounce? = announces.firstOrNull { it.role == role }
}

// ════════════════════════════════════════════════════════════════
//  具体 Task 实现（包装现有组件）
// ════════════════════════════════════════════════════════════════

/**
 * Scout 任务 — 包装现有 MarketAnalyzer + StockDataFacade
 * 纯量化，无 LLM。
 */
class ScoutTask(private val appContext: Context) {
    suspend fun execute(ctx: AgentContext): Map<String, Any?> {
        ctx.requirePermission(AgentPermission.READ_MARKET_DATA)
        ctx.log("开始市场环境侦察")

        return try {
            // 调用现有 MarketAnalyzer（holdingCodes 传空 = 不评估持仓）
            val marketReport = com.chin.stockanalysis.strategy.market.MarketAnalyzer
                .analyze(appContext, emptyList())

            val result = mutableMapOf<String, Any?>(
                "marketDirection" to marketReport.trend.direction,
                "trendStrength" to marketReport.trend.strength,
                "summary" to marketReport.summary,
                "marketReport" to marketReport  // 供决策矩阵使用（PositionWaterValve）
            )

            // 板块轮动建议
            result["sectorAdvice"] = marketReport.sectorAdvice.toString()

            // 外围市场
            if (marketReport.overseas.direction != "UNKNOWN") {
                result["overseasDirection"] = marketReport.overseas.direction
                result["overseasStrength"] = marketReport.overseas.strength
                result["overseasHint"] = marketReport.overseas.impactHint
            }

            ctx.log("侦察完成: direction=${marketReport.trend.direction}, " +
                "strength=${marketReport.trend.strength}, overseas=${marketReport.overseas.direction}")
            result

        } catch (e: Exception) {
            ctx.recordError("SCOUT_ERROR", e.message ?: "市场侦察失败")
            mapOf("marketDirection" to "UNKNOWN", "error" to e.message)
        }
    }
}

/**
 * Analyst 任务 — 包装现有 AgentPipelineOrchestrator / StockAnalysisAgent
 * 调用 LLM（强模型）。
 */
class AnalystTask(
    private val appContext: Context,
    private val stockCode: String?
) {
    suspend fun execute(ctx: AgentContext): Map<String, Any?> {
        ctx.requirePermission(AgentPermission.CALL_LLM_STRONG)

        if (stockCode == null) {
            return mapOf("error" to "无目标股票", "recommendation" to "WATCH")
        }

        ctx.log("开始深度分析: $stockCode")

        return try {
            // 调用现有 StockAnalysisAgent
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
            ctx.recordError("ANALYST_ERROR", e.message ?: "分析失败", "ALGORITHM")
            mapOf(
                "score" to 50,
                "recommendation" to "WATCH",
                "summary" to "LLM 不可用，使用默认评分",
                "fallback" to "ALGORITHM"
            )
        }
    }
}

/**
 * Guardian 任务 — 包装现有 RiskManagementAgent
 * 调用 LLM（快速模型）或纯算法后备。
 */
class GuardianTask(private val appContext: Context) {
    suspend fun execute(ctx: AgentContext): Map<String, Any?> {
        ctx.requirePermission(AgentPermission.READ_PORTFOLIO)
        ctx.log("开始风控扫描")

        return try {
            val agent = com.chin.stockanalysis.agent.risk.RiskManagementAgent(appContext)
            val result = agent.scanPortfolio(null)

            ctx.log("风控完成: ${result.urgentAlerts.size} 个警示")
            mapOf(
                "riskLevel" to result.portfolioRiskLevel,
                "positionAdvice" to result.positionAdvice,
                "riskWarnings" to result.urgentAlerts,
                "marketRisk" to result.marketRisk,
                "holdings" to result.holdings.map { "${it.code}: ${it.recommendation}" }
            )
        } catch (e: Exception) {
            ctx.recordError("GUARDIAN_ERROR", e.message ?: "风控失败", "ATR_ALGORITHM")
            mapOf(
                "riskLevel" to "UNKNOWN",
                "positionAdvice" to "LLM不可用，建议保守仓位(≤30%)",
                "riskWarnings" to listOf("风控Agent异常: ${e.message}"),
                "fallback" to "ATR_ALGORITHM"
            )
        }
    }
}
