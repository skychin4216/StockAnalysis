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
 * 分析深度模式（統一入口使用）
 *
 * 對應 UI 的三檔分析深度，由 [AgentOrchestrator.analyzeStock] 驅動：
 * - [QUICK]   快速：StockAnalysisAgent + RiskManagementAgent 並行（葉子模塊，無深度編排）
 * - [DEEP]    深度：DeepAnalystEngine 多子 Agent（V1.0 流水線等價）+ 決策矩陣
 * - [EXPERT]  專家：同 DEEP 深度，週期參數按中長線（更長超時、更多子 Agent）
 */
enum class AnalysisMode {
    QUICK, DEEP, EXPERT
}

/**
 * 統一分析結果 — 所有調用方（詳情頁 / 對話框 / 策略列表）共用
 *
 * 取代舊 [com.chin.stockanalysis.agent.framework.UnifiedAgentRunner.Result]。
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
    /** 組合後可直接展示的文字報告 */
    val summaryText: String = "",
    val errorMessage: String? = null,
    val elapsedMs: Long = 0,
    /** 是否走完整 Agent 編排（false = 輕量直連路徑） */
    val degraded: Boolean = false,
    /** 產業鏈打分（DEEP/EXPERT） */
    val chainScore: Int? = null,
    /** 是否通過流水線篩選（DEEP/EXPERT） */
    val passed: Boolean? = null,
    /** 壁壘等級（DEEP/EXPERT，供策略列表 CrossTabBus） */
    val barrierLevel: String? = null,
    /** 建議倉位（決策矩陣輸出） */
    val positionPercent: Int? = null
)

/**
 * 分析步驟描述（供進度 UI，如 PipelineProgressView）
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
 * 步驟進度監聽器 — DeepAnalystEngine 逐子 Agent 回調
 */
interface AnalysisStepListener {
    /** 子 Agent 分析完成（summary 為可讀摘要，非原始 JSON） */
    fun onStepComplete(step: AnalysisStep, summary: String, result: Map<String, Any?>) {}
    /** 子 Agent 失敗 */
    fun onStepError(step: AnalysisStep, error: String) {}
}

/**
 * 統一股票分析入口 — 單一入口，flag 控制編排深度
 *
 * 設計理念：Agent 與非 Agent 同時支持，都走 [AgentOrchestrator]。
 * - useAgentFramework = true  → 完整角色編排（Scout + Analyst + Guardian 並行，降級兜底）
 * - useAgentFramework = false → 輕量直連（DeepAnalystEngine + 決策矩陣，無 session/spawner 開銷）
 *
 * 兩種路徑產出相同 [AnalysisResult] 結構，調用方無感知。
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
    Log.i(TAG, "▶ 統一分析 [$mode/${if (useAgentFramework) "Agent" else "輕量"}]: $resolvedName($stockCode)")

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
        Log.e(TAG, "統一分析異常: ${e.message}", e)
        AnalysisResult(stockCode, resolvedName, mode, false,
            errorMessage = e.message, summaryText = "❌ 分析失敗: ${e.message}",
            elapsedMs = System.currentTimeMillis() - startTime)
    }
}

// ════════════════════════════════════════════════════════════
//  QUICK：葉子模塊並行（StockAnalysisAgent + RiskManagementAgent + MarketAnalyzer）
// ════════════════════════════════════════════════════════════

private suspend fun AgentOrchestrator.runQuickAnalysis(
    stockCode: String, stockName: String, startTime: Long
): AnalysisResult = withContext(Dispatchers.IO) {
    val (analysis, risk, marketReport) = coroutineScope {
        val a = async { runCatching { StockAnalysisAgent(appContext).analyze(stockCode, stockName) }.getOrNull() }
        val r = async { runCatching { RiskManagementAgent(appContext).assessStockRisk(stockCode) }.getOrNull() }
        val m = async { runCatching { MarketAnalyzer.analyze(appContext, emptyList()) }.getOrNull() }
        Triple(a.await(), r.await(), m.await())
    }

    val sb = StringBuilder()
    val marketWarnings = buildMarketWarnings(marketReport)

    if (analysis != null && analysis.success) {
        sb.appendLine("## 📊 AI 綜合分析")
        sb.appendLine("評分: ${analysis.overallScore}/100 | 建議: ${analysis.recommendation} | 置信度: ${analysis.confidence}")
        sb.appendLine("技術面: ${analysis.technicalScore}/100 | 基本面: ${analysis.fundamentalScore}/100 | 資金面: ${analysis.fundFlowScore}/100")
        if (analysis.targetPrice.isNotBlank()) sb.appendLine("目標價: ${analysis.targetPrice}")
        if (analysis.stopLoss.isNotBlank()) sb.appendLine("止損位: ${analysis.stopLoss}")
        if (analysis.riskFactors.isNotEmpty()) sb.appendLine("風險: ${analysis.riskFactors.joinToString("、")}")
        if (analysis.reasoning.isNotBlank()) sb.appendLine("\n${analysis.reasoning}")
        sb.appendLine()
    } else {
        sb.appendLine("AI 綜合分析：失敗或超時")
    }
    sb.appendLine("## 🛡 風控評估")
    sb.appendLine(if (risk != null && risk.success) risk.assessment else "風控評估：失敗或超時")
    if (marketWarnings.isNotEmpty()) {
        sb.appendLine(); sb.appendLine("## 🌐 大盤環境")
        marketWarnings.forEach { sb.appendLine(it) }
        if (analysis?.recommendation == "BUY" && marketReport?.trend?.direction == "BEARISH") {
            sb.appendLine(); sb.appendLine("🔴 **注意**: 大盤下行環境下，買入建議需謹慎！")
        }
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
        errorMessage = if (analysis?.success != true) "AI 分析失敗" else null,
        elapsedMs = System.currentTimeMillis() - startTime
    )
}

private fun buildMarketWarnings(marketReport: MarketAnalyzer.MarketReport?): List<String> {
    val warnings = mutableListOf<String>()
    if (marketReport != null) {
        val trend = marketReport.trend
        val sellType = marketReport.sellType
        when (trend.direction) {
            "BEARISH" -> {
                warnings.add("⚠️ 大盤下行（強度${trend.strength}/100）" +
                    if (sellType.sellType == "INSTITUTIONAL_EXIT") " | 主力撤資中"
                    else if (sellType.sellType == "QUANT_CRASH") " | 量化砸盤" else "")
                warnings.add("建議降低倉位，關注防禦板塊")
            }
            "OSCILLATION" -> {
                if (trend.strength > 40)
                    warnings.add("⚡ 大盤震蕩加劇（強度${trend.strength}）— 控制倉位")
                else
                    warnings.add("📊 大盤震蕩（強度${trend.strength}）— 輕倉操作")
            }
        }
    }
    return warnings
}

// ════════════════════════════════════════════════════════════
//  DEEP / EXPERT：DeepAnalystEngine + 決策矩陣（flag 控制是否加 Scout/Guardian 編排）
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
    // EXPERT 按中長線給更多子 Agent / 更長超時；DEEP 按短線
    val period = if (mode == AnalysisMode.EXPERT) HoldingPeriod.MID else HoldingPeriod.SHORT
    val config = AgentClusterConfig.forPeriod(period)

    val analystResult: Map<String, Any?>
    var decision: AgentOrchestratorDecision? = null
    var guardianAnnounce: AgentAnnounce? = null
    var scoutAnnounce: AgentAnnounce? = null
    var degraded = false

    if (useAgentFramework) {
        // ── 完整編排：Scout → (DeepAnalyst ∥ Guardian) → 決策矩陣 ──
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
        // ── 輕量直連：DeepAnalystEngine + 內聯市場環境 → 決策矩陣 ──
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

    val summary = buildUnifiedSummary(
        stockName, stockCode, mode, score, recommendation, report,
        decision, guardianAnnounce, scoutAnnounce, entryZones, riskFactors
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
        positionPercent = decision?.decision?.positionPercent
    )
}

/** 決策矩陣輸出（供統一入口使用） */
internal data class AgentOrchestratorDecision(
    val decision: com.chin.stockanalysis.agent.v2.FinalDecision,
    val peBand: String,
    val valuationWarning: String?
)

/**
 * 決策矩陣計算（市場環境 × 利潤質量 × 估值）— 統一入口共用
 *
 * 失敗不阻塞，返回 null。
 */
internal suspend fun AgentOrchestrator.computeDecision(
    stockCode: String,
    direction: String,
    strength: Int,
    marketReport: MarketAnalyzer.MarketReport?
): AgentOrchestratorDecision? = try {
    val marketEnv = MarketEnvironment(
        direction = direction, strength = strength,
        description = "市場環境偵察",
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
    val peBand = when { pe <= 0 -> "無法評估"; pe < 20 -> "低估"; pe < 40 -> "合理"; else -> "高估" }
    val valuationWarning = if (profitQuality.qualityLevel == com.chin.stockanalysis.agent.v2.ProfitQualityLevel.ONE_TIME_PROFIT && pe > 0 && pe < 20)
        "⚠️ 市盈率陷阱：一次性浮盈導致靜態PE極低，建議使用扣非PE重新估值" else null

    val decision = V2DecisionMatrix.evaluate(
        V2DecisionMatrix.DecisionInput(
            environment = marketEnv, positionCap = positionCap,
            profitQuality = profitQuality, pe = pe, pb = pb
        )
    )
    AgentOrchestratorDecision(decision, peBand, valuationWarning)
} catch (e: Exception) {
    Log.w(TAG, "決策矩陣計算失敗（不阻塞）: ${e.message}")
    null
}

private fun buildUnifiedSummary(
    stockName: String, stockCode: String, mode: AnalysisMode,
    score: Int, recommendation: String?, report: String,
    decision: AgentOrchestratorDecision?, guardian: AgentAnnounce?, scout: AgentAnnounce?,
    entryZones: List<String>?, riskFactors: List<String>?
): String = buildString {
    val modeLabel = when (mode) { AnalysisMode.QUICK -> "快速"; AnalysisMode.DEEP -> "深度"; AnalysisMode.EXPERT -> "專家" }
    appendLine("## 🧠 $modeLabel 分析報告：$stockName($stockCode)")
    appendLine("綜合評分: $score/100 | 建議: ${recommendation ?: "WATCH"}")
    appendLine()

    // 市場環境（Scout 或內聯 MarketAnalyzer）
    val direction = scout?.getResult<String>("marketDirection")
    if (direction != null) {
        appendLine("── 市場環境 ──")
        appendLine("方向: $direction")
        scout.getResult<List<String>>("hotSectors")?.let { appendLine("熱點: ${it.take(3).joinToString(", ")}") }
        appendLine()
    }

    // DeepAnalyst 報告（已含各子 Agent 可讀分析）
    if (report.isNotBlank()) { appendLine(report); appendLine() }

    // 決策矩陣
    decision?.let { d ->
        appendLine("── 🎯 決策矩陣 ──")
        appendLine("操作: ${d.decision.action} | 建議倉位: ${d.decision.positionPercent}%")
        appendLine("估值: PE ${d.peBand} | 策略: ${d.decision.strategy}")
        d.decision.tTradingAdvice?.let { appendLine("做T建議: $it") }
        appendLine("止損規則: ${d.decision.stopLossRule}")
        d.valuationWarning?.let { appendLine(it) }
        d.decision.riskWarning?.let { appendLine("⚠️ $it") }
        appendLine()
    }

    // 風控意見（Guardian，僅完整編排）
    if (guardian != null && !guardian.isFailed) {
        appendLine("── 風控意見 ──")
        guardian.getResult<String>("positionAdvice")?.let { appendLine("倉位: $it") }
        guardian.getResult<List<String>>("riskWarnings")?.let { it.take(3).forEach { w -> appendLine("⚠️ $w") } }
        appendLine()
    }

    entryZones?.takeIf { it.isNotEmpty() }?.let { appendLine("低吸區間: ${it.joinToString(" / ")}") }
    riskFactors?.takeIf { it.isNotEmpty() }?.let { it.take(3).forEach { f -> appendLine("⚠️ $f") } }
}
