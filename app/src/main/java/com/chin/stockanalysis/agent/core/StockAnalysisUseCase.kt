package com.chin.stockanalysis.agent.core

import android.util.Log
import com.chin.stockanalysis.agent.risk.RiskManagementAgent
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.agent.v2.MarketEnvironment
import com.chin.stockanalysis.agent.v2.PositionWaterValve
import com.chin.stockanalysis.agent.v2.ProfitQualityAnalyzer
import com.chin.stockanalysis.agent.v2.V2DecisionMatrix
import com.chin.stockanalysis.stock.data.StockDataFacade
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 分析深度模式（统一入口使用）
 *
 * 对应 UI 的三档分析深度，由 [AgentOrchestrator.analyzeStock] 驱动：
 * - [QUICK]   快速：StockAnalysisAgent + RiskManagementAgent 并行（叶子模块，无深度编排）
 * - [DEEP]    深度：DeepAnalystEngine 多子 Agent（V1.0 流水线等价）+ 决策矩阵
 * - [EXPERT]  专家：同 DEEP 深度，周期参数按中长线（更长超时、更多子 Agent）
 */
enum class AnalysisMode {
    QUICK, DEEP, EXPERT
}

/**
 * 统一分析结果 — 所有调用方（详情页 / 对话框 / 策略列表）共用
 *
 * 取代旧 [com.chin.stockanalysis.agent.framework.UnifiedAgentRunner.Result]。
 */
data class AnalysisResult(
    val stockCode: String,
    val stockName: String,
    val mode: AnalysisMode,
    val success: Boolean,
    val overallScore: Int = 0,
    val recommendation: String? = null,
    val confidence: String? = null,
    val riskLevel: String? = null,
    val targetPrice: String? = null,
    val stopLoss: String? = null,
    /** 组合后可直接展示的文字报告 */
    val summaryText: String = "",
    val errorMessage: String? = null,
    val elapsedMs: Long = 0,
    /** 是否走完整 Agent 编排（false = 轻量直连路径） */
    val degraded: Boolean = false,
    /** 产业链打分（DEEP/EXPERT） */
    val chainScore: Int? = null,
    /** 是否通过流水线筛选（DEEP/EXPERT） */
    val passed: Boolean? = null,
    /** 壁垒等级（DEEP/EXPERT，供策略列表 CrossTabBus） */
    val barrierLevel: String? = null,
    /** 建议仓位（决策矩阵输出） */
    val positionPercent: Int? = null,
    /** 是否通过六项严选检查（null = 未检查，如 recommendation 非 BUY） */
    val strictSelectionPassed: Boolean? = null,
    /** 严选检查详情 */
    val strictSelectionDetail: com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationDetail? = null
)

/**
 * 分析步骤描述（供进度 UI，如 PipelineProgressView）
 */
data class AnalysisStep(
    val agentId: String,
    val name: String,
    val order: Int,
    val isScorer: Boolean = false,
    val canHedge: Boolean = false,
    val isAuxiliary: Boolean = false
)

/**
 * 步骤进度监听器 — DeepAnalystEngine 逐子 Agent 回调
 */
interface AnalysisStepListener {
    /** 子 Agent 分析完成（summary 为可读摘要，非原始 JSON） */
    fun onStepComplete(step: AnalysisStep, summary: String, result: Map<String, Any?>) {}
    /** 子 Agent 失败 */
    fun onStepError(step: AnalysisStep, error: String) {}
}

/**
 * 统一股票分析入口 — 单一入口，flag 控制编排深度
 *
 * 设计理念：Agent 与非 Agent 同时支持，都走 [AgentOrchestrator]。
 * - useAgentFramework = true  → 完整角色编排（Scout + Analyst + Guardian 并行，降级兜底）
 * - useAgentFramework = false → 轻量直连（DeepAnalystEngine + 决策矩阵，无 session/spawner 开销）
 *
 * 两种路径产出相同 [AnalysisResult] 结构，调用方无感知。
 */
private const val TAG = "AgentOrchestrator"

suspend fun AgentOrchestrator.analyzeStock(
    stockCode: String,
    stockName: String?,
    mode: AnalysisMode,
    useAgentFramework: Boolean,
    stepListener: AnalysisStepListener? = null,
    quantSignalsProvider: (suspend (String) -> List<com.chin.stockanalysis.strategy.models.StrategySignal>)? = null
): AnalysisResult {
    val startTime = System.currentTimeMillis()
    val resolvedName = stockName ?: stockCode
    Log.i(TAG, "▶ 统一分析 [$mode/${if (useAgentFramework) "Agent" else "轻量"}]: $resolvedName($stockCode)")

    return try {
        when (mode) {
            AnalysisMode.QUICK -> runQuickAnalysis(stockCode, resolvedName, startTime)
            AnalysisMode.DEEP, AnalysisMode.EXPERT -> runDeepAnalysis(
                stockCode, resolvedName, mode, useAgentFramework, startTime, stepListener, quantSignalsProvider
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        AnalysisResult(stockCode, resolvedName, mode, false,
            errorMessage = "分析已取消", elapsedMs = System.currentTimeMillis() - startTime)
    } catch (e: Exception) {
        Log.e(TAG, "统一分析异常: ${e.message}", e)
        AnalysisResult(stockCode, resolvedName, mode, false,
            errorMessage = e.message, summaryText = "❌ 分析失败: ${e.message}",
            elapsedMs = System.currentTimeMillis() - startTime)
    }
}

// ════════════════════════════════════════════════════════════
//  QUICK：叶子模块并行（StockAnalysisAgent + RiskManagementAgent + MarketAnalyzer）
// ════════════════════════════════════════════════════════════

private suspend fun AgentOrchestrator.runQuickAnalysis(
    stockCode: String, stockName: String, startTime: Long
): AnalysisResult = withContext(Dispatchers.IO) {
    val (analysis, risk, marketReport) = coroutineScope {
        val a = async { runCatching { StockAnalysisAgent(appContext).analyze(stockCode, stockName) }.getOrNull() }
        val r = async { runCatching { RiskManagementAgent(appContext).assessStockRiskDirect(stockCode) }.getOrNull() }
        val m = async { runCatching { MarketAnalyzer.analyze(appContext, emptyList()) }.getOrNull() }
        Triple(a.await(), r.await(), m.await())
    }

    val sb = StringBuilder()
    val marketWarnings = buildMarketWarnings(marketReport)

    if (analysis != null && analysis.success) {
        sb.appendLine("## 📊 AI 综合分析")
        sb.appendLine("综合评分: ${analysis.overallScore}/100 | 建议: ${analysis.recommendation} | 置信度: ${analysis.confidence}")
        sb.appendLine()

        // 各维度结构化结论
        sb.appendLine("### 📈 技术面: ${analysis.technicalScore}/100")
        val techVerdict = when {
            analysis.technicalScore >= 70 -> "技术面偏多，形态较好"
            analysis.technicalScore >= 50 -> "技术面中性，观望为主"
            else -> "技术面偏空，谨慎操作"
        }
        sb.appendLine("结论: $techVerdict")
        sb.appendLine()

        sb.appendLine("### 💼 基本面: ${analysis.fundamentalScore}/100")
        val fundVerdict = when {
            analysis.fundamentalScore >= 70 -> "基本面扎实，估值合理"
            analysis.fundamentalScore >= 50 -> "基本面一般，需关注业绩变化"
            else -> "基本面较弱，注意风险"
        }
        sb.appendLine("结论: $fundVerdict")
        sb.appendLine()

        sb.appendLine("### 💰 资金面: ${analysis.fundFlowScore}/100")
        val flowVerdict = when {
            analysis.fundFlowScore >= 70 -> "资金持续流入，主力看好"
            analysis.fundFlowScore >= 50 -> "资金流向中性"
            else -> "资金流出，注意主力动向"
        }
        sb.appendLine("结论: $flowVerdict")
        sb.appendLine()

        if (analysis.targetPrice.isNotBlank()) sb.appendLine("### 🎯 目标价: ${analysis.targetPrice}")
        if (analysis.stopLoss.isNotBlank()) sb.appendLine("### 🛑 止损位: ${analysis.stopLoss}")
        if (analysis.riskFactors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### ⚠️ 风险因素")
            analysis.riskFactors.forEach { sb.appendLine("  • $it") }
        }
        if (analysis.reasoning.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("### 📝 详细分析")
            sb.appendLine(analysis.reasoning)
        }
        sb.appendLine()
    } else {
        sb.appendLine("⚠️ AI 综合分析：失败或超时")
    }
    sb.appendLine("## 🛡 风控评估")
    if (risk != null && risk.success) {
        sb.appendLine(cleanRiskAssessment(risk.assessment))
    } else {
        sb.appendLine("风控评估：失败或超时")
    }
    if (marketWarnings.isNotEmpty()) {
        sb.appendLine(); sb.appendLine("## 🌐 大盘环境")
        marketWarnings.forEach { sb.appendLine(it) }
        if (analysis?.recommendation == "BUY" && marketReport?.trend?.direction == "BEARISH") {
            sb.appendLine(); sb.appendLine("🔴 **注意**: 大盘下行环境下，买入建议需谨慎！")
        }
    }

    // 严选检查：BUY 建议时作为最终关卡
    var strictPassed: Boolean? = null
    var strictDetail: com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationDetail? = null
    if (analysis?.recommendation == "BUY") {
        try {
            val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(appContext)
            strictDetail = com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationChecker
                .evaluate(stockCode, db)
            strictPassed = strictDetail.allPassed
            sb.appendLine()
            sb.append(com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationChecker.formatResult(strictDetail))
        } catch (_: Exception) {}
    }

    AnalysisResult(
        stockCode = stockCode, stockName = stockName, mode = AnalysisMode.QUICK,
        success = analysis?.success == true,
        overallScore = analysis?.overallScore ?: 0,
        recommendation = analysis?.recommendation,
        confidence = analysis?.confidence,
        targetPrice = analysis?.targetPrice?.takeIf { it.isNotBlank() },
        stopLoss = analysis?.stopLoss?.takeIf { it.isNotBlank() },
        summaryText = sb.toString(),
        errorMessage = if (analysis?.success != true) "AI 分析失败" else null,
        elapsedMs = System.currentTimeMillis() - startTime,
        strictSelectionPassed = strictPassed,
        strictSelectionDetail = strictDetail
    )
}

/** 清理风控 Agent 原始输出，提取结论部分 */
private fun cleanRiskAssessment(raw: String): String {
    if (raw.isBlank()) return "暂无评估结果"
    // 如果已经很简短（<300字），直接返回
    if (raw.length < 300 && !raw.contains("<thinking") && !raw.contains("```")) return raw.trim()

    var cleaned = raw
        // 移除 <thinking> 推理标签
        .replace(Regex("<thinking>[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE), "")
        // 移除 JSON/程式码块
        .replace(Regex("```[\\s\\S]*?```"), "")
        // 移除工具调用描述行
        .replace(Regex("^.*(?:调用|呼叫|calling|tool_call).*$", RegexOption.MULTILINE), "")
        // 移除纯 JSON 结构行
        .replace(Regex("^\\s*[{}\\[\\],:]\\s*$", RegexOption.MULTILINE), "")
        // 移除 JSON key-value 行（如 "risk_level": "MEDIUM"）
        .replace(Regex("^\\s*\"[^\"]+\"\\s*:\\s*.+$", RegexOption.MULTILINE), "")

    // 按行过滤：移除空白行和纯数字行
    cleaned = cleaned.lines()
        .filter { line ->
            val t = line.trim()
            t.isNotBlank() && !t.matches(Regex("^-?\\d+(\\.\\d+)?$"))
        }
        .joinToString("\n")
        .trim()

    // 如果清理后为空，返回摘要提示
    if (cleaned.isBlank()) return "风险评估完成，但未得出明确结论。建议结合大盘环境综合判断。"
    return cleaned
}

private fun buildMarketWarnings(marketReport: MarketAnalyzer.MarketReport?): List<String> {
    val warnings = mutableListOf<String>()
    if (marketReport != null) {
        val trend = marketReport.trend
        val sellType = marketReport.sellType
        when (trend.direction) {
            "BEARISH" -> {
                warnings.add("⚠️ 大盘下行（强度${trend.strength}/100）" +
                    if (sellType.sellType == "INSTITUTIONAL_EXIT") " | 主力撤资中"
                    else if (sellType.sellType == "QUANT_CRASH") " | 量化砸盘" else "")
                warnings.add("建议降低仓位，关注防御板块")
            }
            "OSCILLATION" -> {
                if (trend.strength > 40)
                    warnings.add("⚡ 大盘震荡加剧（强度${trend.strength}）— 控制仓位")
                else
                    warnings.add("📊 大盘震荡（强度${trend.strength}）— 轻仓操作")
            }
        }
    }
    return warnings
}

// ════════════════════════════════════════════════════════════
//  DEEP / EXPERT：DeepAnalystEngine + 决策矩阵（flag 控制是否加 Scout/Guardian 编排）
// ════════════════════════════════════════════════════════════

private suspend fun AgentOrchestrator.runDeepAnalysis(
    stockCode: String,
    stockName: String,
    mode: AnalysisMode,
    useAgentFramework: Boolean,
    startTime: Long,
    stepListener: AnalysisStepListener?,
    quantSignalsProvider: (suspend (String) -> List<com.chin.stockanalysis.strategy.models.StrategySignal>)?
): AnalysisResult = withContext(Dispatchers.IO) {
    // EXPERT 按中长线给更多子 Agent / 更长超时；DEEP 按短线
    val period = if (mode == AnalysisMode.EXPERT) HoldingPeriod.MID else HoldingPeriod.SHORT
    val config = AgentClusterConfig.forPeriod(period)

    val analystResult: Map<String, Any?>
    var decision: AgentOrchestratorDecision? = null
    var guardianAnnounce: AgentAnnounce? = null
    var scoutAnnounce: AgentAnnounce? = null
    var degraded = false

    if (useAgentFramework) {
        // ── 完整编排：Scout → (DeepAnalyst ∥ Guardian) → 决策矩阵 ──
        val sessionId = "${stockCode}_${System.currentTimeMillis()}"
        val session = AgentSession(sessionId)
        val scope = AgentSessionManager.instance.createSession(sessionId)
        try {
            scoutAnnounce = spawner.spawn(
                role = AgentRoles.SCOUT.copy(timeoutMs = config.orchestratorTimeout / 6),
                task = AgentTask { ctx -> ScoutTask(appContext).execute(ctx) },
                session = session, scope = scope
            ).await()

            val analystDeferred = spawner.spawn(
                role = AgentRoles.ANALYST.copy(timeoutMs = config.analystTimeout),
                task = AgentTask { ctx ->
                    DeepAnalystEngine(appContext, stockCode, stockName, config.analystSteps)
                        .execute(ctx, stepListener, quantSignalsProvider)
                },
                session = session, scope = scope
            )
            val guardianDeferred = spawner.spawn(
                role = AgentRoles.GUARDIAN.copy(timeoutMs = config.guardianTimeout),
                task = AgentTask { ctx -> GuardianTask(appContext).execute(ctx) },
                session = session, scope = scope
            )
            analystResult = analystDeferred.await().result
            guardianAnnounce = guardianDeferred.await()
            degraded = guardianAnnounce?.isDegraded == true

            decision = computeDecision(
                stockCode,
                scoutAnnounce?.getResult<String>("marketDirection") ?: "OSCILLATION",
                scoutAnnounce?.getResult<Int>("trendStrength") ?: 30,
                scoutAnnounce?.getResult<MarketAnalyzer.MarketReport>("marketReport")
            )
        } finally {
            AgentSessionManager.instance.cancelSession(sessionId)
        }
    } else {
        // ── 轻量直连：DeepAnalystEngine + 内联市场环境 → 决策矩阵 ──
        val ctx = AgentContext(role = AgentRoles.ANALYST, taskId = "lightweight_$stockCode")
        analystResult = DeepAnalystEngine(appContext, stockCode, stockName, config.analystSteps)
            .execute(ctx, stepListener, quantSignalsProvider)

        val marketReport = runCatching { MarketAnalyzer.analyze(appContext, emptyList()) }.getOrNull()
        decision = computeDecision(
            stockCode,
            marketReport?.trend?.direction ?: "OSCILLATION",
            marketReport?.trend?.strength ?: 30,
            marketReport
        )
    }

    val score = (analystResult["score"] as? Int) ?: 0
    val recommendation = analystResult["recommendation"] as? String
    val chainScore = analystResult["chainScore"] as? Int
    val riskLevel = (analystResult["riskLevel"] as? String) ?: guardianAnnounce?.getResult<String>("riskLevel")
    val report = analystResult["summary"] as? String ?: ""
    @Suppress("UNCHECKED_CAST")
    val entryZones = analystResult["entryZones"] as? List<String>
    @Suppress("UNCHECKED_CAST")
    val riskFactors = analystResult["riskFactors"] as? List<String>
    val passed = (chainScore ?: 0) >= 40 && riskLevel != "高"

    // 严选检查：BUY 建议时作为最终关卡
    var strictPassed: Boolean? = null
    var strictDetail: com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationDetail? = null
    if (recommendation == "BUY") {
        try {
            val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(appContext)
            val strictPipeline = when (period) {
                HoldingPeriod.SHORT -> com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline.shortTermParams()
                HoldingPeriod.MID -> com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline.midTermParams()
                HoldingPeriod.LONG -> com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline.longTermParams()
                else -> com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline.midTermParams()
            }
            strictDetail = com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationChecker
                .evaluate(stockCode, db, strictPipeline)
            strictPassed = strictDetail.allPassed
        } catch (_: Exception) {}
    }

    val summary = buildUnifiedSummary(
        stockName, stockCode, mode, score, recommendation, report,
        decision, guardianAnnounce, scoutAnnounce, entryZones, riskFactors,
        strictDetail
    )

    AnalysisResult(
        stockCode = stockCode, stockName = stockName, mode = mode,
        success = report.isNotBlank() || score > 0,
        overallScore = score,
        recommendation = recommendation,
        confidence = analystResult["confidence"] as? String,
        riskLevel = riskLevel,
        targetPrice = (analystResult["targetPrice"] as? String)?.takeIf { it.isNotBlank() },
        stopLoss = (analystResult["stopLoss"] as? String)?.takeIf { it.isNotBlank() },
        summaryText = summary,
        elapsedMs = System.currentTimeMillis() - startTime,
        degraded = degraded,
        chainScore = chainScore,
        passed = passed,
        barrierLevel = null,
        positionPercent = decision?.decision?.positionPercent,
        strictSelectionPassed = strictPassed,
        strictSelectionDetail = strictDetail
    )
}

/** 决策矩阵输出（供统一入口使用） */
internal data class AgentOrchestratorDecision(
    val decision: com.chin.stockanalysis.agent.v2.FinalDecision,
    val peBand: String,
    val valuationWarning: String?
)

/**
 * 决策矩阵计算（市场环境 × 利润质量 × 估值）— 统一入口共用
 *
 * 失败不阻塞，返回 null。
 */
internal suspend fun AgentOrchestrator.computeDecision(
    stockCode: String,
    direction: String,
    strength: Int,
    marketReport: MarketAnalyzer.MarketReport?
): AgentOrchestratorDecision? = try {
    val marketEnv = MarketEnvironment(
        direction = direction, strength = strength,
        description = "市场环境侦察",
        sellType = marketReport?.sellType?.sellType ?: "NONE"
    )
    val profitQuality = ProfitQualityAnalyzer.analyze(stockCode)
    val positionCap = marketReport?.let { PositionWaterValve.calculatePositionCap(it).capPercent } ?: 50

    var pe = 0.0; var pb = 0.0
    runCatching {
        val quote = StockDataFacade.getInstance(appContext).getAnalysisData(stockCode).quote
        pe = quote?.pe ?: 0.0; pb = quote?.pb ?: 0.0
    }
    if (pe <= 0.0 || pb <= 0.0) {
        runCatching {
            val snap = com.chin.stockanalysis.stock.database.StockDatabase
                .getInstance(appContext).dailySnapshotDao().getByCode(stockCode, 5)
                .firstOrNull { it.pe > 0 || it.pb > 0 }
            if (snap != null) {
                if (pe <= 0.0 && snap.pe > 0) pe = snap.pe
                if (pb <= 0.0 && snap.pb > 0) pb = snap.pb
            }
        }
    }
    val peBand = when { pe <= 0 -> "无法评估"; pe < 20 -> "低估"; pe < 40 -> "合理"; else -> "高估" }
    val valuationWarning = if (profitQuality.qualityLevel == com.chin.stockanalysis.agent.v2.ProfitQualityLevel.ONE_TIME_PROFIT && pe > 0 && pe < 20)
        "⚠️ 市盈率陷阱：一次性浮盈导致静态PE极低，建议使用扣非PE重新估值" else null

    val decision = V2DecisionMatrix.evaluate(
        V2DecisionMatrix.DecisionInput(
            environment = marketEnv, positionCap = positionCap,
            profitQuality = profitQuality, pe = pe, pb = pb
        )
    )
    AgentOrchestratorDecision(decision, peBand, valuationWarning)
} catch (e: Exception) {
    Log.w(TAG, "决策矩阵计算失败（不阻塞）: ${e.message}")
    null
}

private fun buildUnifiedSummary(
    stockName: String, stockCode: String, mode: AnalysisMode,
    score: Int, recommendation: String?, report: String,
    decision: AgentOrchestratorDecision?, guardian: AgentAnnounce?, scout: AgentAnnounce?,
    entryZones: List<String>?, riskFactors: List<String>?,
    strictDetail: com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationDetail? = null
): String = buildString {
    val modeLabel = when (mode) { AnalysisMode.QUICK -> "快速"; AnalysisMode.DEEP -> "深度"; AnalysisMode.EXPERT -> "专家" }
    appendLine("## 🧠 $modeLabel 分析报告：$stockName($stockCode)")
    appendLine("综合评分: $score/100 | 建议: ${recommendation ?: "WATCH"}")
    appendLine()

    // 市场环境（Scout 或内联 MarketAnalyzer）
    val direction = scout?.getResult<String>("marketDirection")
    if (direction != null) {
        val dirEmoji = when (direction) { "BULLISH" -> "🔴"; "BEARISH" -> "🟢"; else -> "🟡" }
        appendLine("### 🌐 大盘环境")
        appendLine("$dirEmoji 方向: $direction")
        scout.getResult<List<String>>("hotSectors")?.let { appendLine("热点板块: ${it.take(3).joinToString(", ")}") }
        appendLine()
    }

    // DeepAnalyst 报告（已由 DeepAnalystEngine.buildReport 结构化，含各环节结论）
    if (report.isNotBlank()) { appendLine(report); appendLine() }

    // 决策矩阵
    decision?.let { d ->
        appendLine("### 🎯 决策矩阵")
        appendLine("操作: ${d.decision.action} | 建议仓位: ${d.decision.positionPercent}%")
        appendLine("估值: PE ${d.peBand} | 策略: ${d.decision.strategy}")
        d.decision.tTradingAdvice?.let { appendLine("做T建议: $it") }
        appendLine("止损规则: ${d.decision.stopLossRule}")
        d.valuationWarning?.let { appendLine(it) }
        d.decision.riskWarning?.let { appendLine("⚠️ $it") }
        appendLine()
    }

    // 风控意见（Guardian，仅完整编排）
    if (guardian != null && !guardian.isFailed) {
        appendLine("### 🛡 风控意见")
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

    // 交易区间与风险
    if (entryZones != null && entryZones.isNotEmpty()) {
        appendLine("### 💰 低吸区间: ${entryZones.joinToString(" / ")}")
    }
    if (riskFactors != null && riskFactors.isNotEmpty()) {
        appendLine("### ⚠️ 风险因素")
        riskFactors.take(3).forEach { appendLine("  • $it") }
    }

    // 严选检查结果（仅 BUY 建议时显示）
    if (strictDetail != null) {
        appendLine()
        append(com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationChecker.formatResult(strictDetail))
    }
}
