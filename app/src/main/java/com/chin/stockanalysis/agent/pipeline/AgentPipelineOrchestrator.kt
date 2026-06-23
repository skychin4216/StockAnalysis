package com.chin.stockanalysis.agent.pipeline

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.AgentManager
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.StrategySignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 7 智能體流水線編排器
 *
 * 流水線順序：F → 3 → 1 → 2 → 5 → D → 4
 *
 * - Agent F：數據底座（不調 LLM，直接採集）
 * - Agent 3：賽道熱度識別
 * - Agent 1：基本面硬性篩選
 * - Agent 2：產業鏈賣水人打分（唯一打分核心，≥40 分通過）
 * - Agent 5：風控終審（紅隊證偽 + 海外替代對沖）
 * - Agent D：輿情微調（僅倉位，不影響淘汰）
 * - Agent 4：交易執行方案
 */
class AgentPipelineOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "PipelineOrchestrator"

        /** 7 步流水線定義 */
        val STEPS: List<PipelineStep> = listOf(
            PipelineStep("pipeline_agent_f",         "Agent F: 數據底座",     0, isDataFeeder = true),
            PipelineStep("pipeline_agent_3",         "Agent 3: 賽道識別",     1),
            PipelineStep("pipeline_agent_1",         "Agent 1: 基本面初篩",   2),
            PipelineStep("pipeline_agent_2",         "Agent 2: 產業鏈打分",   3, isScorer = true, passThreshold = 40),
            PipelineStep("pipeline_agent_5",         "Agent 5: 風控終審",     4, canHedge = true),
            PipelineStep("pipeline_agent_d",         "Agent D: 輿情微調",     5, isAuxiliary = true),
            PipelineStep("pipeline_agent_4",         "Agent 4: 交易執行",     6)
        )
    }

    private val agentManager = AgentManager(context)
    private val dataFeeder = DataFeeder(context)
    private val parser = StructuredOutputParser

    /** 可選：量化策略信號注入（Phase 2） */
    var quantSignalsProvider: (suspend (stockCode: String) -> List<StrategySignal>)? = null

    /** 步驟完成回調（UI 更新用） */
    var onStepComplete: ((stepIndex: Int, step: PipelineStep, context: PipelineContext) -> Unit)? = null
    /** 步驟開始回調 */
    var onStepStart: ((stepIndex: Int, step: PipelineStep) -> Unit)? = null
    /** 錯誤回調 */
    var onError: ((stepIndex: Int, error: String) -> Unit)? = null

    /**
     * 執行完整流水線
     *
     * @param target 用戶輸入的標的（如 "生益科技" 或 "光通信板塊"）
     * @param sector 可選的板塊指定（null 則自動推斷）
     * @return PipelineResult 最終結果
     */
    suspend fun execute(target: String, sector: String? = null): PipelineResult {
        val ctx = PipelineContext(target = target)
        ctx.sector = sector ?: dataFeeder.inferSector(target)

        try {
            for ((index, step) in STEPS.withIndex()) {
                onStepStart?.invoke(index, step)

                when {
                    // ── Agent F：數據底座 ──
                    step.isDataFeeder -> {
                        Log.d(TAG, "Step $index: ${step.name} — 數據採集中...")
                        ctx.intelligence = dataFeeder.fetch(target, ctx.sector)
                        ctx.stepAnalyses[index] = "數據採集完成：${ctx.intelligence?.events?.size ?: 0} 條催化事件"
                        onStepComplete?.invoke(index, step, ctx)
                    }

                    // ── Agent D：輿情微調（輔助，不影響淘汰）──
                    step.isAuxiliary -> {
                        Log.d(TAG, "Step $index: ${step.name} — 輿情評分...")
                        val sentimentText = callLLM(step, ctx)
                        ctx.stepAnalyses[index] = sentimentText
                        ctx.sentimentResult = parser.parseSentimentResult(sentimentText)
                        if (ctx.sentimentResult != null) {
                            ctx.positionAdjust = ctx.sentimentResult!!.positionAdjust
                        }
                        onStepComplete?.invoke(index, step, ctx)
                    }

                    // ── 其他步驟：正常 LLM 調用 ──
                    else -> {
                        Log.d(TAG, "Step $index: ${step.name} — LLM 分析中...")

                        // Agent 4 之前注入量化策略信號
                        if (step.agentId == "pipeline_agent_4" && ctx.quantSignals == null) {
                            injectQuantSignals(ctx)
                        }

                        val response = callLLM(step, ctx)
                        ctx.stepAnalyses[index] = response

                        // 解析結構化輸出
                        processStepResult(step, response, ctx)

                        // Agent 5 對沖機制
                        if (step.canHedge) {
                            applyHedgeMechanism(ctx)
                        }

                        // Agent 2 打分後檢查通過閾值
                        if (step.isScorer && ctx.chainScore != null) {
                            if (!ctx.chainScore!!.passed) {
                                Log.d(TAG, "Agent 2 打分 ${ctx.chainScore!!.totalScore} < 40，標的淘汰")
                                onStepComplete?.invoke(index, step, ctx)
                                break // 不再執行後續步驟
                            }
                        }

                        onStepComplete?.invoke(index, step, ctx)
                    }
                }
            }

            // 構建最終結果
            return buildFinalResult(ctx)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "流水線執行異常", e)
            val failedStepIdx = ctx.stepAnalyses.size
            onError?.invoke(failedStepIdx, e.message ?: "未知錯誤")
            return PipelineResult(
                target = target, sector = ctx.sector,
                stepsCompleted = ctx.stepAnalyses.size,
                errorMessage = e.message
            )
        }
    }

    /**
     * 處理每步的結構化輸出解析
     */
    private fun processStepResult(step: PipelineStep, response: String, ctx: PipelineContext) {
        when (step.agentId) {
            "pipeline_agent_3" -> {
                ctx.sectorHeatLevel = parser.parseSectorHeat(response)
            }
            "pipeline_agent_1" -> {
                ctx.filteredPool = parser.parseFilteredPool(response)
            }
            "pipeline_agent_2" -> {
                // 從初選池中取第一只（或用 target 推斷）進行打分
                val stockCode = ctx.filteredPool?.firstOrNull()?.stockCode ?: extractStockCode(ctx.target)
                val stockName = ctx.filteredPool?.firstOrNull()?.stockName ?: ctx.target
                ctx.chainScore = parser.parseChainScore(stockCode, stockName, response)
            }
            "pipeline_agent_5" -> {
                val stockCode = ctx.chainScore?.stockCode ?: extractStockCode(ctx.target)
                ctx.riskResult = parser.parseRiskResult(stockCode, response)
            }
            "pipeline_agent_4" -> {
                val stockCode = ctx.chainScore?.stockCode ?: extractStockCode(ctx.target)
                val stockName = ctx.chainScore?.stockName ?: ctx.target
                ctx.tradePlan = parser.parseTradePlan(stockCode, stockName, response)
            }
        }
    }

    /**
     * Agent 5 對沖機制：
     * 若 Agent2 獲得海外供應鏈加分（overseasBonus > 0），
     * Agent5 需核查海外客戶自研替代/二供導入/砍單風險。
     * 存在風險 → 清零海外加分，重算產業鏈總分。
     */
    private fun applyHedgeMechanism(ctx: PipelineContext) {
        val chainScore = ctx.chainScore ?: return
        val riskResult = ctx.riskResult ?: return

        if (chainScore.overseasBonus > 0 && riskResult.overseasDeduction > 0) {
            Log.w(TAG, "對沖觸發：海外供應鏈加分 ${chainScore.overseasBonus} 被清零")
            val newTotal = (chainScore.totalScore - chainScore.overseasBonus - chainScore.foreignRatingBonus)
                .coerceAtLeast(0)
            ctx.chainScore = chainScore.copy(
                overseasBonus = 0,
                foreignRatingBonus = 0,
                totalScore = newTotal,
                passed = newTotal >= 40
            )
            ctx.riskResult = riskResult.copy(adjustedScore = newTotal)
        }
    }

    /**
     * 注入量化策略信號（Phase 2）
     * 在 Agent 4 交易執行之前，運行量化策略掃描，
     * 將結果格式化為文本注入 Agent 4 的 prompt。
     */
    private suspend fun injectQuantSignals(ctx: PipelineContext) {
        val provider = quantSignalsProvider ?: return
        val stockCode = ctx.chainScore?.stockCode ?: extractStockCode(ctx.target)
        if (stockCode == ctx.target) return // 無有效股票代碼

        try {
            val signals = provider(stockCode)
            if (signals.isEmpty()) return

            val sb = StringBuilder("【量化策略信號（${signals.size} 條）】\n")
            for (sig in signals) {
                sb.append("- ${sig.emoji} [${sig.strategyId}] ${sig.stockName}: ${sig.reason} (強度:${sig.strength}%, 建議:${sig.action.label})\n")
            }
            ctx.quantSignals = sb.toString()
            Log.d(TAG, "注入 ${signals.size} 條量化信號")
        } catch (e: Exception) {
            Log.w(TAG, "量化信號注入失敗: ${e.message?.take(60)}")
        }
    }

    /**
     * 構建最終結果
     */
    private fun buildFinalResult(ctx: PipelineContext): PipelineResult {
        val stockCode = ctx.chainScore?.stockCode ?: extractStockCode(ctx.target)
        val stockName = ctx.chainScore?.stockName ?: ctx.target

        val stockResult = PipelineStockResult(
            stockCode = stockCode,
            stockName = stockName,
            chainScore = ctx.chainScore,
            riskResult = ctx.riskResult,
            sentimentResult = ctx.sentimentResult,
            tradePlan = ctx.tradePlan,
            finalPosition = ctx.positionAdjust ?: "30%",
            passed = ctx.chainScore?.passed == true && ctx.riskResult?.passed == true
        )

        return PipelineResult(
            target = ctx.target,
            sector = ctx.sector,
            stepsCompleted = ctx.stepAnalyses.size,
            totalSteps = STEPS.size,
            stocks = listOf(stockResult)
        )
    }

    /**
     * 調用 LLM（SSE 串流）
     */
    private suspend fun callLLM(step: PipelineStep, ctx: PipelineContext): String {
        return withContext(Dispatchers.IO) {
            val agent = agentManager.get(step.agentId)
            val systemPrompt = buildStepPrompt(step, agent?.systemPrompt ?: "", ctx)

            val slot = AiProviderPool.acquire(context)
                ?: throw IllegalStateException("無可用 AI Provider")

            try {
                suspendCancellableCoroutine { cont ->
                    slot.provider.sendMessageStream(
                        messages = emptyList(),
                        systemPrompt = systemPrompt,
                        onSuccess = { /* 串流 chunk 不處理 */ },
                        onComplete = { full ->
                            val sanitized = full.replace("null", "")
                            cont.resume(sanitized)
                        },
                        onError = { err ->
                            cont.resumeWithException(Exception(err))
                        }
                    )
                }
            } finally {
                AiProviderPool.releaseNonBlocking(slot)
            }
        }
    }

    /**
     * 構建每步的 SystemPrompt
     * 累積前序步驟的結構化摘要 + Agent F 情報注入
     */
    private fun buildStepPrompt(step: PipelineStep, basePrompt: String, ctx: PipelineContext): String {
        val sb = StringBuilder()

        // 基礎 SystemPrompt
        sb.append(basePrompt)
        sb.append("\n\n")

        // 注入 Agent F 情報（若有）
        ctx.intelligence?.let { intel ->
            sb.append("【最新產業情報（Agent F 採集）】\n")
            sb.append("催化事件：${intel.events.joinToString("；")}\n")
            sb.append("外資評級：${intel.ratings.joinToString("；")}\n")
            sb.append("供需格局：${intel.supplyChain.joinToString("；")}\n\n")
        }

        // 注入前序步驟摘要
        when (step.agentId) {
            "pipeline_agent_3" -> {
                sb.append("【分析標的】${ctx.target}\n")
                sb.append("【賽道範圍】${ctx.sector}\n")
            }
            "pipeline_agent_1" -> {
                ctx.sectorHeatLevel?.let { sb.append("【賽道熱度】$it\n") }
                sb.append("【分析標的】${ctx.target}\n")
            }
            "pipeline_agent_2" -> {
                ctx.sectorHeatLevel?.let { sb.append("【賽道熱度】$it\n") }
                ctx.filteredPool?.let { pool ->
                    sb.append("【初選池】${pool.size} 只：${pool.joinToString("、") { it.stockName }}\n")
                }
            }
            "pipeline_agent_5" -> {
                ctx.chainScore?.let { score ->
                    sb.append("【產業鏈打分】${score.totalScore}/100（壁壘等級：${score.barrierLevel}）\n")
                    if (score.overseasBonus > 0) sb.append("【注意】該標的獲得海外供應鏈加分 +${score.overseasBonus}，需核查替代風險\n")
                }
            }
            "pipeline_agent_d" -> {
                ctx.chainScore?.let { sb.append("【產業鏈打分】${it.totalScore}/100\n") }
                ctx.riskResult?.let { sb.append("【風控等級】${it.riskLevel}\n") }
            }
            "pipeline_agent_4" -> {
                ctx.chainScore?.let { sb.append("【產業鏈打分】${it.totalScore}/100\n") }
                ctx.riskResult?.let { sb.append("【風控等級】${it.riskLevel}\n") }
                ctx.sentimentResult?.let { sb.append("【倉位微調】${it.positionAdjust}（${it.reason}）\n") }
                ctx.quantSignals?.let { sb.append("【量化策略信號】\n$it\n") }
            }
        }

        // 輸出格式要求
        sb.append("\n請嚴格按 JSON 格式輸出結果，JSON 前後用 ```json ``` 包裹。\n")

        return sb.toString()
    }

    /**
     * 從用戶輸入中提取股票代碼
     */
    private fun extractStockCode(input: String): String {
        val pattern = Regex("(sh|sz|bj)\\d{6}")
        return pattern.find(input)?.value ?: input
    }
}