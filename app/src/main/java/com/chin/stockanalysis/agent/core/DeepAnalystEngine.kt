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
 * ## 深度分析引擎 — V1.0 Pipeline 分析深度迁移
 *
 * 复用 skills_config.json 中 pipeline_agent_* 的专家 prompt，
 * 以「分阶段并行」方式对单只股票执行与 V1.0 同深度的多维分析：
 *
 * Phase 1（并行）: 基本面拐点(A1) + 赛道热度(A3) + 板块舆情(D)
 * Phase 2（并行）: 产业链打分(A2) + 技术量价(A4)  ← 注入 Phase1 摘要
 * Phase 3（串行）: 风控终审(A5)                   ← 注入全部摘要
 *
 * 相比 V1.0 的 6 步串行，延迟从 6×LLM 降至 3×LLM，同时保留关键依赖。
 * 相比旧 AnalystTask（1 次 LLM），分析深度追平 V1.0。
 *
 * @param maxSteps 子 Agent 数量上限（来自 AgentClusterConfig.analystSteps）：
 *   ≤3 → 仅技术+舆情+风控（超短线快分析）
 *   ≤5 → +基本面+赛道（短线）
 *   ≥6 → 全量（中长线深度分析）
 */
class DeepAnalystEngine(
    private val appContext: Context,
    private val stockCode: String,
    private val stockName: String?,
    private val maxSteps: Int = 6
) {

    companion object {
        private const val TAG = "DeepAnalystEngine"
        /** 单个子 Agent LLM 调用超时 */
        private const val STEP_TIMEOUT_MS = 90_000L
        /** LLM 无活动检测间隔 */
        private const val IDLE_CHECK_INTERVAL_MS = 5_000L
        /** LLM 无新 token 判定卡死的时长 */
        private const val IDLE_STALL_MS = 30_000L

        // ── 子 Agent 定义 ──
        private data class SubAgentDef(
            val agentId: String,
            val displayName: String,
            val phase: Int,
            val maxTokens: Int = 4096
        )

        private val allSubAgents = listOf(
            SubAgentDef("pipeline_agent_1", "基本面拐点分析", phase = 1),
            SubAgentDef("pipeline_agent_3", "赛道热度识别", phase = 1),
            SubAgentDef("pipeline_agent_d", "板块&舆情评分", phase = 1),
            SubAgentDef("pipeline_agent_2", "产业链打分", phase = 2),
            SubAgentDef("pipeline_agent_4", "技术量价分析", phase = 2, maxTokens = 6144),
            SubAgentDef("pipeline_agent_competition", "行业竞争格局", phase = 2),
            SubAgentDef("pipeline_agent_5", "风控终审", phase = 3, maxTokens = 6144)
        )

        /** 根据 maxSteps 选取子 Agent 集合（风控永远保留） */
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

        /** 给定分析深度（子 Agent 数上限），返回有序步骤列表（供进度 UI 预建卡片） */
        fun stepsFor(maxSteps: Int): List<AnalysisStep> =
            selectSubAgents(maxSteps).mapIndexed { i, def -> def.toAnalysisStep(i) }
    }

    /** 根据 maxSteps 选取子 Agent 集合（风控永远保留） */
    private fun selectSubAgents(): List<SubAgentDef> = Companion.selectSubAgents(maxSteps)

    /**
     * 执行深度分析
     *
     * @param ctx Agent 上下文
     * @param stepListener 逐步进度回调（供 StrategyListFragment 进度面板，可空）
     * @param quantSignalsProvider 量化策略信号提供者（注入到各子 Agent prompt，可空）
     * @return 结构化结果 map（score/recommendation/summary/riskFactors/tradePlan 等）
     */
    suspend fun execute(
        ctx: AgentContext,
        stepListener: AnalysisStepListener? = null,
        quantSignalsProvider: (suspend (String) -> List<com.chin.stockanalysis.strategy.models.StrategySignal>)? = null
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val agents = selectSubAgents()
        val target = stockName?.let { "$it($stockCode)" } ?: stockCode
        ctx.log("深度分析引擎启动: $target, 子Agent=${agents.size}")

        // ══ 1. 数据预取（一次获取，所有子 Agent 共享） ══
        val stockData = try {
            StockDataFacade.getInstance(appContext).getAnalysisData(stockCode)
        } catch (e: Exception) {
            Log.w(TAG, "StockDataFacade 获取失败: ${e.message}")
            null
        }
        val quarterlyText = try {
            val result = QuarterlyComparisonProvider.fetch(stockCode)
            if (result.hasData) QuarterlyComparisonProvider.formatForAgentInjection(result) else null
        } catch (e: Exception) {
            Log.w(TAG, "季度环比数据获取失败: ${e.message}")
            null
        }
        val hotSectors = fetchTodayHotSectors()
        val resolvedName = stockName ?: stockData?.quote?.name ?: stockCode

        // 量化策略信号（一次获取，注入各子 Agent）
        val quantSignalsText = fetchQuantSignals(quantSignalsProvider)

        // ══ 2. 分阶段执行 ══
        val analyses = mutableMapOf<String, String>()   // agentId → LLM 原始输出
        val phase1Agents = agents.filter { it.phase == 1 }
        val phase2Agents = agents.filter { it.phase == 2 }
        val phase3Agents = agents.filter { it.phase == 3 }

        coroutineScope {
            // Phase 1: 并行
            val p1Deferred = phase1Agents.map { def ->
                def to async {
                    runSubAgent(ctx, def, target, resolvedName, stockData, quarterlyText, hotSectors, emptyMap(), quantSignalsText, agents, stepListener)
                }
            }
            for ((def, deferred) in p1Deferred) {
                deferred.await()?.let { analyses[def.agentId] = it }
            }
            ctx.log("Phase 1 完成: ${analyses.size}/${phase1Agents.size}")

            // Phase 2: 并行（注入 Phase1 摘要）
            val p2Deferred = phase2Agents.map { def ->
                def to async {
                    runSubAgent(ctx, def, target, resolvedName, stockData, quarterlyText, hotSectors, analyses, quantSignalsText, agents, stepListener)
                }
            }
            for ((def, deferred) in p2Deferred) {
                deferred.await()?.let { analyses[def.agentId] = it }
            }
            ctx.log("Phase 2 完成: ${analyses.size}/${agents.size - phase3Agents.size}")

            // 提前淘汰（对齐 V1 passThreshold）：产业链打分 < 40 且非数据不足 → 跳过风控终审
            val earlyScore = analyses["pipeline_agent_2"]?.let {
                StructuredOutputParser.parseChainScore(stockCode, resolvedName, it)
            }
            val eliminated = earlyScore != null && !earlyScore.passed && earlyScore.totalScore > 0
            if (eliminated) {
                ctx.log("提前淘汰: 产业链打分 ${earlyScore?.totalScore} < 40, 跳过 Phase 3")
            }

            // Phase 3: 串行（注入全部摘要；淘汰时跳过）
            if (!eliminated) {
                for (def in phase3Agents) {
                    runSubAgent(ctx, def, target, resolvedName, stockData, quarterlyText, hotSectors, analyses, quantSignalsText, agents, stepListener)
                        ?.let { analyses[def.agentId] = it }
                }
            }
            ctx.log("Phase 3 完成: ${analyses.size}/${agents.size}")
        }

        // ══ 3. 解析结构化输出 ══
        var chainScore = analyses["pipeline_agent_2"]?.let {
            StructuredOutputParser.parseChainScore(stockCode, resolvedName, it)
        }
        var riskResult = analyses["pipeline_agent_5"]?.let {
            StructuredOutputParser.parseRiskResult(stockCode, it)
        }

        // 对冲机制（对齐 V1 applyHedgeMechanism）：风控海外扣分清零海外供应链加分
        if (chainScore != null && riskResult != null &&
            chainScore.overseasBonus > 0 && riskResult.overseasDeduction > 0) {
            val newTotal = (chainScore.totalScore - chainScore.overseasBonus - chainScore.foreignRatingBonus)
                .coerceAtLeast(0)
            ctx.log("对冲触发: 海外加分 ${chainScore.overseasBonus}+${chainScore.foreignRatingBonus} 清零 → $newTotal")
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

        // ══ 4. 融合评分与建议 ══
        val baseScore = chainScore?.totalScore
            ?: analyses.values.size * 15  // 无打分时按完成度估算
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

        // ══ 5. 组装报告（豆包风格 Markdown） ══
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
    //  子 Agent 执行
    // ════════════════════════════════════════════════════

    /**
     * 执行单个子 Agent：加载 prompt → 注入数据 → LLM 调用
     *
     * @return LLM 原始输出文本（失败返回 null，不阻塞其他子 Agent）
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
                Log.w(TAG, "${def.displayName} prompt 为空，跳过")
                stepListener?.onStepError(step, "prompt 为空")
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
            Log.w(TAG, "${def.displayName} 失败: ${e.message}")
            ctx.recordError("SUB_AGENT_${def.agentId}", "${def.displayName}: ${e.message}")
            stepListener?.onStepError(step, e.message ?: "分析失败")
            null
        }
    }

    /** 获取量化策略信号文本（注入各子 Agent prompt） */
    private suspend fun fetchQuantSignals(
        provider: (suspend (String) -> List<com.chin.stockanalysis.strategy.models.StrategySignal>)?
    ): String? {
        provider ?: return null
        return try {
            val signals = provider(stockCode)
            if (signals.isEmpty()) return null
            buildString {
                appendLine("【量化策略信号（${signals.size} 条）】")
                for (sig in signals) {
                    appendLine("- ${sig.emoji} [${sig.strategyId}] ${sig.stockName}: ${sig.reason} (强度:${sig.strength}%, 建议:${sig.action.label})")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "量化信号注入失败: ${e.message?.take(60)}")
            null
        }
    }

    /**
     * LLM 调用（SSE 串流 + 无活动检测 + 硬超时兜底）
     */
    private suspend fun callLLM(def: SubAgentDef, systemPrompt: String): String {
        val provider = AiProviderSelector.getProvider(
            context = appContext,
            scenario = AiProviderSelector.AiScenario.PIPELINE_EXPERT
        ) ?: throw IllegalStateException("无可用 AI Provider")

        var lastTokenTime = System.currentTimeMillis()
        var hasReceivedToken = false

        return withTimeout(STEP_TIMEOUT_MS) {
            coroutineScope {
                // 无活动检测：30s 无新 token → 取消 LLM
                val activityJob = launch {
                    while (isActive) {
                        delay(IDLE_CHECK_INTERVAL_MS)
                        val idle = System.currentTimeMillis() - lastTokenTime
                        if (idle > IDLE_STALL_MS && hasReceivedToken) {
                            Log.w(TAG, "⏱ ${def.displayName} ${IDLE_STALL_MS / 1000}s 无新 token，取消")
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
    //  Prompt 构建（复用 V1 buildStepPrompt 的数据注入逻辑）
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

        // 前序子 Agent 分析摘要（Phase 2/3 注入，越靠后越精简）
        if (priorAnalyses.isNotEmpty()) {
            append("【前序智能体分析结果汇总】\n")
            val maxChars = if (def.phase >= 3) 300 else 500
            for ((agentId, analysis) in priorAnalyses) {
                val name = allSubAgents.firstOrNull { it.agentId == agentId }?.displayName ?: agentId
                append("── $name ──\n")
                append(analysis.take(maxChars))
                append("\n\n")
            }
        }

        // 股票实时数据
        stockData?.let { data ->
            append("【股票实时数据】\n")
            data.quote?.let { q ->
                append("当前价: ${q.price} (${if (q.changePercent >= 0) "+" else ""}${"%.2f".format(q.changePercent)}%)")
                append(" | 最高: ${q.high} | 最低: ${q.low}")
                append(" | 成交量: ${q.volume} | 换手率: ${"%.2f".format(q.turnoverRate)}%\n")
                if (q.pe > 0) append("PE(TTM): ${"%.2f".format(q.pe)}\n")
            }
            val f = data.fundamental
            append("股票名称: ${f.name}")
            if (f.business.isNotBlank()) append(" | 主营业务: ${f.business}")
            if (f.sectorNames.isNotEmpty()) append(" | 板块: ${f.sectorNames.joinToString(", ")}")
            if (f.chainRationale.isNotBlank()) append(" | 产业链: ${f.chainRationale}")
            append("（${f.source}）\n")
            val h = data.history
            if (h.snapshots.size >= 5) {
                val prices = h.snapshots.map { it.close }
                val ma5 = prices.take(5).average()
                val ma10 = prices.take(minOf(10, prices.size)).average()
                val ma20 = prices.take(minOf(20, prices.size)).average()
                append("MA5: ${"%.2f".format(ma5)} | MA10: ${"%.2f".format(ma10)} | MA20: ${"%.2f".format(ma20)}")
                if (!h.isFresh) append("（历史截至 ${h.latestDate}）")
                append("\n")
            }
            val ff = data.fundFlow
            if (!ff.isEmpty) {
                append("主力净流入: ${"%.2f".format(ff.totalNetInflow)}万 | 平均换手率: ${"%.2f".format(ff.avgTurnoverRate)}%")
                if (!ff.isFresh) append("（截至 ${ff.latestDate}）")
                append("\n")
            }
            append("\n")
        }

        // 季度环比数据
        quarterlyText?.let {
            append(it)
            append("\n\n")
        }

        // 量化策略信号（来自策略列表的实时筛选信号）
        quantSignalsText?.let {
            append(it)
            append("\n")
        }

        // 分析标的
        append("【分析标的】$target\n")
        append("【股票代码】$stockCode\n")
        append("【股票名称】$resolvedName\n")
    }

    /** 动态获取今日热门赛道（suspend 版本，避免 runBlocking） */
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
        if (allSectors.isEmpty()) "今日暂无明确热门赛道，请根据标的自身基本面动态分析"
        else allSectors.take(10).joinToString("、")
    } catch (_: Exception) {
        "今日暂无明确热门赛道，请根据标的自身基本面动态分析"
    }

    // ════════════════════════════════════════════════════
    //  报告组装
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
        appendLine("## 🧠 深度分析报告：$target")
        appendLine("综合评分: $finalScore/100 | 建议: $recommendation")
        appendLine()

        // 基础行情
        stockData?.quote?.let { q ->
            appendLine("### 📊 基础行情")
            appendLine("现价 ${q.price} (${if (q.changePercent >= 0) "+" else ""}${"%.2f".format(q.changePercent)}%)")
            if (q.pe > 0) append("PE(TTM): ${"%.2f".format(q.pe)}")
            if (q.pb > 0) append(" | PB: ${"%.2f".format(q.pb)}")
            appendLine()
        }

        // ── 各子 Agent 结构化结论（使用 formatReadable 提取清晰结论，非原始 JSON） ──
        val sectionConclusions = mutableListOf<String>()
        for (def in agents) {
            val rawAnalysis = analyses[def.agentId] ?: continue
            appendLine()
            appendLine("### ${def.displayName}")
            // 使用 StructuredOutputParser.formatReadable 产生结构化结论
            val readable = StructuredOutputParser.formatReadable(def.agentId, rawAnalysis)
            appendLine(readable)
            // 收集关键结论供综合摘要使用
            val conclusionLine = extractConclusion(def.agentId, readable)
            if (conclusionLine.isNotBlank()) sectionConclusions.add("${def.displayName}: $conclusionLine")
        }

        // ── 综合摘要（各环节一句话结论） ──
        if (sectionConclusions.isNotEmpty()) {
            appendLine()
            appendLine("### 📋 各环节结论摘要")
            sectionConclusions.forEach { appendLine("• $it") }
        }

        // 产业链打分摘要
        chainScore?.let { cs ->
            appendLine()
            val verdict = if (cs.passed) "✅ 通过" else "❌ 未达标"
            appendLine("### 📈 产业链打分: ${cs.totalScore}/100（壁垒: ${cs.barrierLevel}）$verdict")
            if (cs.overseasBonus > 0) appendLine("海外供应链加分: +${cs.overseasBonus}")
            if (cs.foreignRatingBonus > 0) appendLine("外资评级加分: +${cs.foreignRatingBonus}")
        }

        // 风控终审摘要
        riskResult?.let { rr ->
            appendLine()
            val riskEmoji = when (rr.riskLevel) { "低" -> "🟢"; "中" -> "🟡"; else -> "🔴" }
            appendLine("### 🛡 风控终审: $riskEmoji ${rr.riskLevel}风险")
            if (rr.deductions.isNotEmpty()) {
                rr.deductions.forEach { appendLine("  • ${it.item}: ${it.description} (-${it.score})") }
            } else {
                appendLine("  • 无重大风险扣分项")
            }
            if (rr.adjustedScore > 0) appendLine("对冲后分数: ${rr.adjustedScore}")
        }

        // 交易方案
        tradePlan?.let { tp ->
            appendLine()
            appendLine("### 🎯 交易方案")
            if (tp.entryZones.isNotEmpty()) appendLine("低吸区间: ${tp.entryZones.joinToString(" / ")}")
            appendLine("止损: ${tp.stopLoss} | 目标: ${tp.targets.joinToString(" / ")}")
            appendLine("仓位: ${tp.maxPosition}（${tp.splitRatio}）")
        }

        // 舆情微调
        sentiment?.let { st ->
            appendLine()
            appendLine("### 📰 舆情微调: ${st.positionAdjust}（${st.reason}）")
        }

        appendLine()
        appendLine("⚠️ 所有内容仅为数据复盘研究，不构成任何投资建议")
    }

    /**
     * 从子 Agent 的可读输出中提取一句话结论
     */
    private fun extractConclusion(agentId: String, readable: String): String {
        // 取第一行非空内容作为结论摘要
        val firstLine = readable.lines().firstOrNull { it.isNotBlank() } ?: return ""
        // 截断过长内容
        return if (firstLine.length > 80) firstLine.take(77) + "..." else firstLine
    }
}
