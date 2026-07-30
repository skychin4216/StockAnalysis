package com.chin.stockanalysis.agent.core

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.AgentManager
import com.chin.stockanalysis.agent.pipeline.QuarterlyComparisonProvider
import com.chin.stockanalysis.agent.pipeline.StructuredOutputParser
import com.chin.stockanalysis.ai.AiProviderSelector
import com.chin.stockanalysis.stock.data.StockDataFacade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ## 深度分析引擎 — V1.0 Pipeline 分析深度遷移
 *
 * 復用 skills_config.json 中 pipeline_agent_* 的專家 prompt，
 * 以「分階段並行」方式對單只股票執行與 V1.0 同深度的多維分析：
 *
 * Phase 1（並行）: 基本面拐點(A1) + 賽道熱度(A3) + 板塊輿情(D)
 * Phase 2（並行）: 產業鏈打分(A2) + 技術量價(A4)  ← 注入 Phase1 摘要
 * Phase 3（串行）: 風控終審(A5)                   ← 注入全部摘要
 *
 * 相比 V1.0 的 6 步串行，延遲從 6×LLM 降至 3×LLM，同時保留關鍵依賴。
 * 相比舊 AnalystTask（1 次 LLM），分析深度追平 V1.0。
 *
 * @param maxSteps 子 Agent 數量上限（來自 AgentClusterConfig.analystSteps）：
 *   ≤3 → 僅技術+輿情+風控（超短線快分析）
 *   ≤5 → +基本面+賽道（短線）
 *   ≥6 → 全量（中長線深度分析）
 */
class DeepAnalystEngine(
    private val appContext: Context,
    private val stockCode: String,
    private val stockName: String?,
    private val maxSteps: Int = 6
) {

    companion object {
        private const val TAG = "DeepAnalystEngine"
        /** 單個子 Agent LLM 調用超時 */
        private const val STEP_TIMEOUT_MS = 90_000L
        /** LLM 無活動檢測間隔 */
        private const val IDLE_CHECK_INTERVAL_MS = 5_000L
        /** LLM 無新 token 判定卡死的時長 */
        private const val IDLE_STALL_MS = 30_000L

        // ── 子 Agent 定義 ──
        private data class SubAgentDef(
            val agentId: String,
            val displayName: String,
            val phase: Int,
            val maxTokens: Int = 4096
        )

        private val allSubAgents = listOf(
            SubAgentDef("pipeline_agent_1", "基本面拐點分析", phase = 1),
            SubAgentDef("pipeline_agent_3", "賽道熱度識別", phase = 1),
            SubAgentDef("pipeline_agent_d", "板塊&輿情評分", phase = 1),
            SubAgentDef("pipeline_agent_2", "產業鏈打分", phase = 2),
            SubAgentDef("pipeline_agent_4", "技術量價分析", phase = 2, maxTokens = 6144),
            SubAgentDef("pipeline_agent_competition", "行業競爭格局", phase = 2),
            SubAgentDef("pipeline_agent_5", "風控終審", phase = 3, maxTokens = 6144)
        )

        /** 根據 maxSteps 選取子 Agent 集合（風控永遠保留） */
        private fun selectSubAgents(maxSteps: Int): List<SubAgentDef> = when {
            maxSteps <= 3 -> allSubAgents.filter {
                it.agentId in setOf("pipeline_agent_4", "pipeline_agent_d", "pipeline_agent_5")
            }
            maxSteps <= 5 -> allSubAgents.filter { it.agentId != "pipeline_agent_2" }
            else -> allSubAgents
        }

        private fun SubAgentDef.toAnalysisStep(order: Int) = AnalysisStep(
            agentId = agentId,
            name = displayName,
            order = order,
            isScorer = agentId == "pipeline_agent_2",
            canHedge = agentId == "pipeline_agent_5",
            isAuxiliary = agentId == "pipeline_agent_d"
        )

        /** 给定分析深度（子 Agent 數上限），返回有序步驟列表（供進度 UI 預建卡片） */
        fun stepsFor(maxSteps: Int): List<AnalysisStep> =
            selectSubAgents(maxSteps).mapIndexed { i, def -> def.toAnalysisStep(i) }
    }

    /** 根據 maxSteps 選取子 Agent 集合（風控永遠保留） */
    private fun selectSubAgents(): List<SubAgentDef> = Companion.selectSubAgents(maxSteps)

    /**
     * 執行深度分析
     *
     * @param ctx Agent 上下文
     * @param stepListener 逐步進度回調（供 StrategyListFragment 進度面板，可空）
     * @param quantSignalsProvider 量化策略信號提供者（注入到各子 Agent prompt，可空）
     * @return 結構化結果 map（score/recommendation/summary/riskFactors/tradePlan 等）
     */
    suspend fun execute(
        ctx: AgentContext,
        stepListener: AnalysisStepListener? = null,
        quantSignalsProvider: (suspend (String) -> List<com.chin.stockanalysis.strategy.models.StrategySignal>)? = null
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val agents = selectSubAgents()
        val target = stockName?.let { "$it($stockCode)" } ?: stockCode
        ctx.log("深度分析引擎啟動: $target, 子Agent=${agents.size}")

        // ══ 1. 數據預取（一次獲取，所有子 Agent 共享） ══
        val stockData = try {
            StockDataFacade.getInstance(appContext).getAnalysisData(stockCode)
        } catch (e: Exception) {
            Log.w(TAG, "StockDataFacade 獲取失敗: ${e.message}")
            null
        }
        val quarterlyText = try {
            val result = QuarterlyComparisonProvider.fetch(stockCode)
            if (result.hasData) QuarterlyComparisonProvider.formatForAgentInjection(result) else null
        } catch (e: Exception) {
            Log.w(TAG, "季度環比數據獲取失敗: ${e.message}")
            null
        }
        val hotSectors = fetchTodayHotSectors()
        val resolvedName = stockName ?: stockData?.quote?.name ?: stockCode

        // 量化策略信號（一次獲取，注入各子 Agent）
        val quantSignalsText = fetchQuantSignals(quantSignalsProvider)

        // ══ 2. 分階段執行 ══
        val analyses = mutableMapOf<String, String>()   // agentId → LLM 原始輸出
        val phase1Agents = agents.filter { it.phase == 1 }
        val phase2Agents = agents.filter { it.phase == 2 }
        val phase3Agents = agents.filter { it.phase == 3 }

        coroutineScope {
            // Phase 1: 並行
            val p1Deferred = phase1Agents.map { def ->
                def to async {
                    runSubAgent(ctx, def, target, resolvedName, stockData, quarterlyText, hotSectors, emptyMap(), quantSignalsText, agents, stepListener)
                }
            }
            for ((def, deferred) in p1Deferred) {
                deferred.await()?.let { analyses[def.agentId] = it }
            }
            ctx.log("Phase 1 完成: ${analyses.size}/${phase1Agents.size}")

            // Phase 2: 並行（注入 Phase1 摘要）
            val p2Deferred = phase2Agents.map { def ->
                def to async {
                    runSubAgent(ctx, def, target, resolvedName, stockData, quarterlyText, hotSectors, analyses, quantSignalsText, agents, stepListener)
                }
            }
            for ((def, deferred) in p2Deferred) {
                deferred.await()?.let { analyses[def.agentId] = it }
            }
            ctx.log("Phase 2 完成: ${analyses.size}/${agents.size - phase3Agents.size}")

            // 提前淘汰（對齊 V1 passThreshold）：產業鏈打分 < 40 且非數據不足 → 跳過風控終審
            val earlyScore = analyses["pipeline_agent_2"]?.let {
                StructuredOutputParser.parseChainScore(stockCode, resolvedName, it)
            }
            val eliminated = earlyScore != null && !earlyScore.passed && earlyScore.totalScore > 0
            if (eliminated) {
                ctx.log("提前淘汰: 產業鏈打分 ${earlyScore?.totalScore} < 40, 跳過 Phase 3")
            }

            // Phase 3: 串行（注入全部摘要；淘汰時跳過）
            if (!eliminated) {
                for (def in phase3Agents) {
                    runSubAgent(ctx, def, target, resolvedName, stockData, quarterlyText, hotSectors, analyses, quantSignalsText, agents, stepListener)
                        ?.let { analyses[def.agentId] = it }
                }
            }
            ctx.log("Phase 3 完成: ${analyses.size}/${agents.size}")
        }

        // ══ 3. 解析結構化輸出 ══
        var chainScore = analyses["pipeline_agent_2"]?.let {
            StructuredOutputParser.parseChainScore(stockCode, resolvedName, it)
        }
        var riskResult = analyses["pipeline_agent_5"]?.let {
            StructuredOutputParser.parseRiskResult(stockCode, it)
        }

        // 對沖機制（對齊 V1 applyHedgeMechanism）：風控海外扣分清零海外供應鏈加分
        if (chainScore != null && riskResult != null &&
            chainScore.overseasBonus > 0 && riskResult.overseasDeduction > 0) {
            val newTotal = (chainScore.totalScore - chainScore.overseasBonus - chainScore.foreignRatingBonus)
                .coerceAtLeast(0)
            ctx.log("對沖觸發: 海外加分 ${chainScore.overseasBonus}+${chainScore.foreignRatingBonus} 清零 → $newTotal")
            chainScore = chainScore.copy(
                overseasBonus = 0, foreignRatingBonus = 0,
                totalScore = newTotal, passed = newTotal >= 40
            )
            riskResult = riskResult.copy(adjustedScore = newTotal)
        }
        val sentiment = analyses["pipeline_agent_d"]?.let {
            StructuredOutputParser.parseSentimentResult(it)
        }
        val tradePlan = analyses["pipeline_agent_4"]?.let {
            StructuredOutputParser.parseTradePlan(stockCode, resolvedName, it)
        }

        // ══ 4. 融合評分與建議 ══
        val baseScore = chainScore?.totalScore
            ?: analyses.values.size * 15  // 無打分時按完成度估算
        val finalScore = when {
            riskResult != null && riskResult.adjustedScore > 0 -> riskResult.adjustedScore
            else -> baseScore
        }.coerceIn(0, 100)

        val recommendation = when {
            riskResult != null && !riskResult.passed -> "SELL"
            chainScore != null && !chainScore.passed -> "WATCH"
            finalScore >= 65 -> "BUY"
            finalScore >= 45 -> "WATCH"
            else -> "HOLD"
        }

        val riskFactors = riskResult?.deductions?.map { "${it.item}: ${it.description} (-${it.score})" }
            ?: emptyList()

        // ══ 5. 組裝報告（豆包風格 Markdown） ══
        val summary = buildReport(target, resolvedName, stockData, analyses, agents,
            chainScore, riskResult, sentiment, tradePlan, finalScore, recommendation)

        ctx.log("深度分析完成: score=$finalScore, recommendation=$recommendation")

        mapOf(
            "score" to finalScore,
            "recommendation" to recommendation,
            "confidence" to if (analyses.size >= agents.size - 1) "HIGH" else "MEDIUM",
            "summary" to summary,
            "riskFactors" to riskFactors,
            "targetPrice" to tradePlan?.targets?.firstOrNull(),
            "stopLoss" to tradePlan?.stopLoss,
            "chainScore" to chainScore?.totalScore,
            "riskLevel" to riskResult?.riskLevel,
            "positionAdjust" to sentiment?.positionAdjust,
            "entryZones" to tradePlan?.entryZones,
            "agentCount" to analyses.size,
            "totalAgents" to agents.size
        )
    }

    // ════════════════════════════════════════════════════
    //  子 Agent 執行
    // ════════════════════════════════════════════════════

    /**
     * 執行單個子 Agent：加載 prompt → 注入數據 → LLM 調用
     *
     * @return LLM 原始輸出文本（失敗返回 null，不阻塞其他子 Agent）
     */
    private suspend fun runSubAgent(
        ctx: AgentContext,
        def: SubAgentDef,
        target: String,
        resolvedName: String,
        stockData: StockDataFacade.StockAnalysisData?,
        quarterlyText: String?,
        hotSectors: String,
        priorAnalyses: Map<String, String>,
        quantSignalsText: String?,
        agents: List<SubAgentDef>,
        stepListener: AnalysisStepListener?
    ): String? {
        val step = with(DeepAnalystEngine.Companion) { def.toAnalysisStep(agents.indexOf(def).coerceAtLeast(0)) }
        return try {
            val basePrompt = AgentManager(appContext).get(def.agentId)?.systemPrompt ?: ""
            if (basePrompt.isBlank()) {
                Log.w(TAG, "${def.displayName} prompt 為空，跳過")
                stepListener?.onStepError(step, "prompt 為空")
                null
            } else {
                val prompt = buildSubAgentPrompt(
                    basePrompt.replace("{today_hot_sectors}", hotSectors),
                    def, target, resolvedName, stockData, quarterlyText, priorAnalyses, quantSignalsText
                )
                val output = callLLM(def, prompt)
                ctx.log("${def.displayName} 完成 (${output.length} 字)")
                stepListener?.onStepComplete(step, StructuredOutputParser.formatReadable(def.agentId, output), emptyMap())
                output
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "${def.displayName} 失敗: ${e.message}")
            ctx.recordError("SUB_AGENT_${def.agentId}", "${def.displayName}: ${e.message}")
            stepListener?.onStepError(step, e.message ?: "分析失敗")
            null
        }
    }

    /** 獲取量化策略信號文本（注入各子 Agent prompt） */
    private suspend fun fetchQuantSignals(
        provider: (suspend (String) -> List<com.chin.stockanalysis.strategy.models.StrategySignal>)?
    ): String? {
        provider ?: return null
        return try {
            val signals = provider(stockCode)
            if (signals.isEmpty()) return null
            buildString {
                appendLine("【量化策略信號（${signals.size} 條）】")
                for (sig in signals) {
                    appendLine("- ${sig.emoji} [${sig.strategyId}] ${sig.stockName}: ${sig.reason} (強度:${sig.strength}%, 建議:${sig.action.label})")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "量化信號注入失敗: ${e.message?.take(60)}")
            null
        }
    }

    /**
     * LLM 調用（SSE 串流 + 無活動檢測 + 硬超時兜底）
     */
    private suspend fun callLLM(def: SubAgentDef, systemPrompt: String): String {
        val provider = AiProviderSelector.getProvider(
            context = appContext,
            scenario = AiProviderSelector.AiScenario.PIPELINE_EXPERT
        ) ?: throw IllegalStateException("無可用 AI Provider")

        var lastTokenTime = System.currentTimeMillis()
        var hasReceivedToken = false

        return withTimeout(STEP_TIMEOUT_MS) {
            coroutineScope {
                // 無活動檢測：30s 無新 token → 取消 LLM
                val activityJob = launch {
                    while (isActive) {
                        delay(IDLE_CHECK_INTERVAL_MS)
                        val idle = System.currentTimeMillis() - lastTokenTime
                        if (idle > IDLE_STALL_MS && hasReceivedToken) {
                            Log.w(TAG, "⏱ ${def.displayName} ${IDLE_STALL_MS / 1000}s 無新 token，取消")
                            provider.cancel()
                            break
                        }
                    }
                }

                val result = suspendCancellableCoroutine<String> { cont ->
                    cont.invokeOnCancellation { provider.cancel() }
                    provider.sendMessageStreamJson(
                        messages = emptyList(),
                        systemPrompt = systemPrompt,
                        onSuccess = {
                            lastTokenTime = System.currentTimeMillis()
                            hasReceivedToken = true
                        },
                        onComplete = { full ->
                            val sanitized = full.replace(Regex(":\\s*null\\s*([,}\\]])"), ": \"\"$1")
                            if (!cont.isCompleted) cont.resume(sanitized) {}
                        },
                        onError = { err ->
                            if (!cont.isCompleted) cont.resumeWithException(Exception(err))
                        },
                        maxTokens = def.maxTokens
                    )
                }

                activityJob.cancel()
                result
            }
        }
    }

    // ════════════════════════════════════════════════════
    //  Prompt 構建（復用 V1 buildStepPrompt 的數據注入邏輯）
    // ════════════════════════════════════════════════════

    private fun buildSubAgentPrompt(
        basePrompt: String,
        def: SubAgentDef,
        target: String,
        resolvedName: String,
        stockData: StockDataFacade.StockAnalysisData?,
        quarterlyText: String?,
        priorAnalyses: Map<String, String>,
        quantSignalsText: String?
    ): String = buildString {
        append(basePrompt)
        append("\n\n")

        // 前序子 Agent 分析摘要（Phase 2/3 注入，越靠後越精簡）
        if (priorAnalyses.isNotEmpty()) {
            append("【前序智能體分析結果匯總】\n")
            val maxChars = if (def.phase >= 3) 300 else 500
            for ((agentId, analysis) in priorAnalyses) {
                val name = allSubAgents.firstOrNull { it.agentId == agentId }?.displayName ?: agentId
                append("── $name ──\n")
                append(analysis.take(maxChars))
                append("\n\n")
            }
        }

        // 股票實時數據
        stockData?.let { data ->
            append("【股票實時數據】\n")
            data.quote?.let { q ->
                append("當前價: ${q.price} (${if (q.changePercent >= 0) "+" else ""}${"%.2f".format(q.changePercent)}%)")
                append(" | 最高: ${q.high} | 最低: ${q.low}")
                append(" | 成交量: ${q.volume} | 換手率: ${"%.2f".format(q.turnoverRate)}%\n")
                if (q.pe > 0) append("PE(TTM): ${"%.2f".format(q.pe)}\n")
            }
            val f = data.fundamental
            append("股票名稱: ${f.name}")
            if (f.business.isNotBlank()) append(" | 主營業務: ${f.business}")
            if (f.sectorNames.isNotEmpty()) append(" | 板塊: ${f.sectorNames.joinToString(", ")}")
            if (f.chainRationale.isNotBlank()) append(" | 產業鏈: ${f.chainRationale}")
            append("（${f.source}）\n")
            val h = data.history
            if (h.snapshots.size >= 5) {
                val prices = h.snapshots.map { it.close }
                val ma5 = prices.take(5).average()
                val ma10 = prices.take(minOf(10, prices.size)).average()
                val ma20 = prices.take(minOf(20, prices.size)).average()
                append("MA5: ${"%.2f".format(ma5)} | MA10: ${"%.2f".format(ma10)} | MA20: ${"%.2f".format(ma20)}")
                if (!h.isFresh) append("（歷史截至 ${h.latestDate}）")
                append("\n")
            }
            val ff = data.fundFlow
            if (!ff.isEmpty) {
                append("主力淨流入: ${"%.2f".format(ff.totalNetInflow)}萬 | 平均換手率: ${"%.2f".format(ff.avgTurnoverRate)}%")
                if (!ff.isFresh) append("（截至 ${ff.latestDate}）")
                append("\n")
            }
            append("\n")
        }

        // 季度環比數據
        quarterlyText?.let {
            append(it)
            append("\n\n")
        }

        // 量化策略信號（來自策略列表的實時篩選信號）
        quantSignalsText?.let {
            append(it)
            append("\n")
        }

        // 分析標的
        append("【分析標的】$target\n")
        append("【股票代碼】$stockCode\n")
        append("【股票名稱】$resolvedName\n")
    }

    /** 動態獲取今日熱門賽道（suspend 版本，避免 runBlocking） */
    private suspend fun fetchTodayHotSectors(): String = try {
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val mktCtx = com.chin.stockanalysis.strategy.sector.StrategyMarketContext.build(appContext, today)
        val allSectors = mutableSetOf<String>()
        allSectors.addAll(mktCtx.todayHotSectors)
        allSectors.addAll(mktCtx.userFocusSectors)
        try {
            val hot = com.chin.stockanalysis.strategy.data.AIHotSectorProvider.getHotSectors(appContext)
            allSectors.addAll(hot.weeklySectors)
            allSectors.addAll(hot.monthlySectors)
        } catch (_: Exception) { }
        if (allSectors.isEmpty()) "今日暫無明確熱門賽道，請根據標的自身基本面動態分析"
        else allSectors.take(10).joinToString("、")
    } catch (_: Exception) {
        "今日暫無明確熱門賽道，請根據標的自身基本面動態分析"
    }

    // ════════════════════════════════════════════════════
    //  報告組裝
    // ════════════════════════════════════════════════════

    private fun buildReport(
        target: String,
        resolvedName: String,
        stockData: StockDataFacade.StockAnalysisData?,
        analyses: Map<String, String>,
        agents: List<SubAgentDef>,
        chainScore: com.chin.stockanalysis.agent.pipeline.ChainScoreResult?,
        riskResult: com.chin.stockanalysis.agent.pipeline.RiskValidationResult?,
        sentiment: com.chin.stockanalysis.agent.pipeline.SentimentAdjustResult?,
        tradePlan: com.chin.stockanalysis.agent.pipeline.TradeExecutionPlan?,
        finalScore: Int,
        recommendation: String
    ): String = buildString {
        appendLine("## 🧠 深度分析報告")
        appendLine("標的: $target | 綜合評分: $finalScore/100 | 建議: $recommendation")
        appendLine()

        // 基礎行情
        stockData?.quote?.let { q ->
            appendLine("### 📊 基礎行情")
            appendLine("現價 ${q.price} (${if (q.changePercent >= 0) "+" else ""}${"%.2f".format(q.changePercent)}%)")
            if (q.pe > 0) append("PE(TTM): ${"%.2f".format(q.pe)}")
            if (q.pb > 0) append(" | PB: ${"%.2f".format(q.pb)}")
            appendLine()
        }

        // 各子 Agent 分析（按定義順序輸出）
        for (def in agents) {
            val analysis = analyses[def.agentId] ?: continue
            appendLine()
            appendLine("### ${def.displayName}")
            // 截取有效內容（去掉 JSON 區塊，保留分析文本）
            val textPart = analysis.replace(Regex("```json[\\s\\S]*?```"), "").trim()
            appendLine(textPart.take(800))
        }

        // 產業鏈打分摘要
        chainScore?.let { cs ->
            appendLine()
            appendLine("### 📈 產業鏈打分: ${cs.totalScore}/100（壁壘: ${cs.barrierLevel}）")
            if (cs.overseasBonus > 0) appendLine("海外供應鏈加分: +${cs.overseasBonus}")
            if (cs.foreignRatingBonus > 0) appendLine("外資評級加分: +${cs.foreignRatingBonus}")
        }

        // 風控終審摘要
        riskResult?.let { rr ->
            appendLine()
            appendLine("### 🛡 風控終審: ${rr.riskLevel}風險")
            rr.deductions.forEach { appendLine("- ${it.item}: ${it.description} (-${it.score})") }
            if (rr.adjustedScore > 0) appendLine("對沖後分數: ${rr.adjustedScore}")
        }

        // 交易方案
        tradePlan?.let { tp ->
            appendLine()
            appendLine("### 🎯 交易方案")
            if (tp.entryZones.isNotEmpty()) appendLine("低吸區間: ${tp.entryZones.joinToString(" / ")}")
            appendLine("止損: ${tp.stopLoss} | 目標: ${tp.targets.joinToString(" / ")}")
            appendLine("倉位: ${tp.maxPosition}（${tp.splitRatio}）")
        }

        // 輿情微調
        sentiment?.let { st ->
            appendLine()
            appendLine("### 📰 輿情微調: ${st.positionAdjust}（${st.reason}）")
        }

        appendLine()
        appendLine("⚠️ 所有內容僅為數據復盤研究，不構成任何投資建議")
    }
}
