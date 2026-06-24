package com.chin.stockanalysis.agent.pipeline

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.AgentManager
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.StrategySignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ## 智能體流水線編排器 v2.0（參考豆包思路）
 *
 * 支持三種分析模式：
 * 1. MODE_NORMAL_SIX — 六智體通用模式（消費、醫藥、周期等普通公司）
 * 2. MODE_SELLER_SEVEN — 七智體賣水人模式（光通信、半導體等上下游清晰賽道）
 * 3. MODE_SELLER_SIMPLE — 精簡版（5 主線 + 1 支線，當前 APP 默認）
 *
 * 全局統一規則：
 * - 綜合總分強制區間 [0, 100]
 * - 支線（Agent D）與主線第一步同步並行發起，不阻塞主線
 * - 串行下一級必須攜帶前面所有智能體完整輸出作為入參
 * - 板塊加分：0 ~ +8
 * - 新聞因子：-10 ~ +5（利空扣分幅度 > 利好加分）
 *
 * AI 動態選擇模式：用戶輸入標的後，AI 根據股票所屬賽道自動選擇模式
 */
class AgentPipelineOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "PipelineOrchestrator"

        // ════════════════════════════════════════
        // 三種分析模式
        // ════════════════════════════════════════

        enum class AnalysisMode(val label: String, val desc: String) {
            /** 六智體通用模式：適用消費、醫藥、周期、整車等普通公司 */
            MODE_NORMAL_SIX("六智體通用", "普通個股研判"),
            /** 七智體賣水人模式：適用光通信、半導體、鋰電、光伏等上下游清晰賽道 */
            MODE_SELLER_SEVEN("七智體賣水人", "產業鏈賣水人研判"),
            /** 精簡版：5 主線 + 1 支線，APP 默認 */
            MODE_SELLER_SIMPLE("精簡版", "5+1 常態化分析")
        }

        // ════════════════════════════════════════
        // 各模式的流水線步驟定義
        // ════════════════════════════════════════

        /** 六智體：A1→A2(賽道)→A3(技術)→A4(競爭格局)→A5(風控) + 並行D */
        private val STEPS_SIX: List<PipelineStep> = listOf(
            PipelineStep("pipeline_agent_1", "Agent 1: 基本面拐點價值選股", 0),
            PipelineStep("pipeline_agent_3", "Agent 2: 賽道熱度識別",       1),
            PipelineStep("pipeline_agent_4", "Agent 3: 技術量價拐點交易",     2),
            PipelineStep("pipeline_agent_competition", "Agent 4: 行業競爭格局", 3),
            PipelineStep("pipeline_agent_5", "Agent 5: 風控終審",           4, canHedge = true),
            PipelineStep("pipeline_agent_d", "Agent D: 板塊&輿情評分",       5, isAuxiliary = true)
        )

        /** 七智體：A1→A2(賣水人)→A3(賽道)→A4(競爭格局)→A5(技術)→A6(風控) + 並行D */
        private val STEPS_SEVEN: List<PipelineStep> = listOf(
            PipelineStep("pipeline_agent_1", "Agent 1: 基本面拐點價值選股", 0),
            PipelineStep("pipeline_agent_2", "Agent 2: 產業鏈賣水人選股",   1, isScorer = true, passThreshold = 40),
            PipelineStep("pipeline_agent_3", "Agent 3: 賽道熱度識別",       2),
            PipelineStep("pipeline_agent_competition", "Agent 4: 行業競爭格局", 3),
            PipelineStep("pipeline_agent_4", "Agent 5: 技術量價拐點交易",   4),
            PipelineStep("pipeline_agent_5", "Agent 6: 風控終審",           5, canHedge = true),
            PipelineStep("pipeline_agent_d", "Agent D: 板塊&輿情評分",     6, isAuxiliary = true)
        )

        /** 精簡版：A1→A2(賣水人)→A3(賽道)→A4(技術)→A5(風控) + 並行D */
        private val STEPS_SIMPLE: List<PipelineStep> = listOf(
            PipelineStep("pipeline_agent_1", "Agent 1: 基本面拐點價值選股", 0),
            PipelineStep("pipeline_agent_2", "Agent 2: 產業鏈賣水人選股",   1, isScorer = true, passThreshold = 40),
            PipelineStep("pipeline_agent_3", "Agent 3: 賽道熱度識別",       2),
            PipelineStep("pipeline_agent_4", "Agent 4: 技術量價拐點交易",   3),
            PipelineStep("pipeline_agent_5", "Agent 5: 風控終審",           4, canHedge = true),
            PipelineStep("pipeline_agent_d", "Agent D: 板塊&輿情評分",     5, isAuxiliary = true)
        )

        /** 根據模式獲取步驟列表 */
        fun getSteps(mode: AnalysisMode): List<PipelineStep> = when (mode) {
            AnalysisMode.MODE_NORMAL_SIX    -> STEPS_SIX
            AnalysisMode.MODE_SELLER_SEVEN  -> STEPS_SEVEN
            AnalysisMode.MODE_SELLER_SIMPLE -> STEPS_SIMPLE
        }

        /** 根據模式名稱獲取步驟列表（UI 使用） */
        fun getStepsByName(modeName: String): List<PipelineStep> = when {
            modeName.contains("六智體") -> STEPS_SIX
            modeName.contains("七智體") -> STEPS_SEVEN
            else -> STEPS_SIMPLE
        }

        /** 根據模式獲取權重公式描述 */
        fun getWeightFormula(mode: AnalysisMode): String = when (mode) {
            AnalysisMode.MODE_NORMAL_SIX -> "基礎分 = A1×0.2 + A2×0.2 + A4×0.2 + A3×0.3 + A5×0.1"
            AnalysisMode.MODE_SELLER_SEVEN -> "基礎分 = A1×0.2 + A2×0.2 + A3×0.2 + A5×0.3 + A6×0.1（A4僅定性）"
            AnalysisMode.MODE_SELLER_SIMPLE -> "基礎分 = A1×0.2 + A2×0.2 + A3×0.2 + A4×0.3 + A5×0.1"
        }

        /** 已知賣水人賽道關鍵詞（用於 AI 動態選擇模式的輔助判斷） */
        private val SELLER_SECTOR_KEYWORDS = listOf(
            "光通信", "光模塊", "PCB", "覆銅板", "CCL", "半導體", "鋰電", "光伏",
            "生益科技", "華工科技", "光迅科技", "潔美科技", "中際旭創", "新易盛",
            "天孚通信", "源傑科技", "銅陵有色", "諾德股份", "嘉元科技"
        )

        /** 判斷標的是否屬於賣水人賽道（快速本地判斷，不調 AI） */
        fun isLikelySellerSector(target: String): Boolean {
            return SELLER_SECTOR_KEYWORDS.any { target.contains(it) }
        }

        // ════════════════════════════════════════
        // 豆包風格輸出格式模板
        // ════════════════════════════════════════

        const val OUTPUT_FORMAT_TEMPLATE = """
請嚴格按照以下豆包風格格式輸出分析結果（Markdown 格式）：

## 📊 基礎行情總覽
（表格：標的、代碼、現價、當日漲跌、總市值、PE(TTM)、核心定位）

## 智能體 1：基本面分析（營收、利潤、壁壘、財務質量）
（每只標的的分析：主業、最新財報、壁壘、短板）
基本面排名：...

## 智能體 2：{agent2_name}
（根據模式不同：賣水人分析 或 賽道分析）

## 智能體 3：{agent3_name}
（根據模式不同：技術面 或 賽道熱度）

## 智能體 4：{agent4_name}
（根據模式不同：競爭格局 或 技術面）

## 智能體 5/6：風控終審
（風險逐條對比，整體風險等級）

## 智能體 D：板塊&輿情評分
（板塊加分、新聞因子）

## 綜合總評
（總結性結論、操作建議）

⚠️ 所有內容僅為數據復盤研究，不構成任何投資建議
"""
    }

    private val agentManager = AgentManager(context)
    private val dataFeeder = DataFeeder(context)
    private val parser = StructuredOutputParser

    /** 可選：量化策略信號注入 */
    var quantSignalsProvider: (suspend (stockCode: String) -> List<StrategySignal>)? = null

    /** 步驟完成回調 */
    var onStepComplete: ((stepIndex: Int, step: PipelineStep, context: PipelineContext) -> Unit)? = null
    /** 步驟開始回調 */
    var onStepStart: ((stepIndex: Int, step: PipelineStep) -> Unit)? = null
    /** 錯誤回調 */
    var onError: ((stepIndex: Int, error: String) -> Unit)? = null
    /** 模式選擇回調 */
    var onModeSelected: ((mode: AnalysisMode, reason: String) -> Unit)? = null

    /**
     * 執行完整流水線（自動選擇模式）
     *
     * @param target 用戶輸入的標的（如 "生益科技" 或 "光通信板塊"）
     * @param sector 可選的板塊指定（null 則自動推斷）
     * @param forceMode 強制指定模式（null 則 AI 動態選擇）
     */
    suspend fun execute(
        target: String,
        sector: String? = null,
        forceMode: AnalysisMode? = null
    ): PipelineResult {
        val ctx = PipelineContext(target = target)
        ctx.sector = sector ?: dataFeeder.inferSector(target)

        // AI 動態選擇模式
        val mode = forceMode ?: selectMode(target, ctx.sector)
        ctx.analysisModeName = mode.label
        val steps = getSteps(mode)
        onModeSelected?.invoke(mode, "已選擇 ${mode.label} 模式")

        Log.i(TAG, "🧠 啟動 ${mode.label} 模式分析: $target (${steps.size} 步)")

        try {
            // 啟動支線並行：Agent D（板塊&輿情）
            val sentimentJob = SupervisorJob()
            val sentimentScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + sentimentJob)
            val dStep = steps.find { it.isAuxiliary }!!
            val dIndex = steps.indexOf(dStep)
            onStepStart?.invoke(dIndex, dStep)
            val sentimentDeferred = sentimentScope.async {
                val sentimentText = callLLM(dStep, steps, ctx, mode)
                ctx.stepAnalyses[dIndex] = sentimentText
                ctx.sentimentResult = parser.parseSentimentResult(sentimentText)
                if (ctx.sentimentResult != null) {
                    ctx.positionAdjust = ctx.sentimentResult!!.positionAdjust
                }
                onStepComplete?.invoke(dIndex, dStep, ctx)
            }

            // 主線串行
            val mainSteps = steps.filter { !it.isAuxiliary }
            for ((index, step) in mainSteps.withIndex()) {
                onStepStart?.invoke(index, step)
                Log.d(TAG, "Step $index: ${step.name} — LLM 分析中...")

                // Agent 4 之前注入量化策略信號
                if (step.agentId == "pipeline_agent_4" && ctx.quantSignals == null) {
                    injectQuantSignals(ctx)
                }

                val response = callLLM(step, steps, ctx, mode)
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
                        break
                    }
                }

                onStepComplete?.invoke(index, step, ctx)
            }

            // 等待支線完成
            try { sentimentDeferred.await() } catch (_: Exception) {}

            // 構建最終結果（豆包格式）
            return buildFinalResult(ctx, mode, steps)

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
     * AI 動態選擇分析模式
     * 優先本地快速判斷，無法確定時使用 AI 判斷
     */
    private suspend fun selectMode(target: String, sector: String): AnalysisMode {
        // 1. 本地快速判斷：賣水人賽道關鍵詞
        if (isLikelySellerSector(target) || isLikelySellerSector(sector)) {
            Log.i(TAG, "🎯 本地判斷為賣水人賽道，使用精簡版")
            return AnalysisMode.MODE_SELLER_SIMPLE
        }

        // 2. AI 判斷（僅在本地無法確定時）
        return withContext(Dispatchers.IO) {
            try {
                val slot = AiProviderPool.acquire(context) ?: return@withContext AnalysisMode.MODE_SELLER_SIMPLE
                try {
                    suspendCancellableCoroutine { cont ->
                        slot.provider.sendMessageStream(
                            messages = emptyList(),
                            systemPrompt = """你是股票分析模式選擇器。根據用戶輸入的標的，判斷應該使用哪種分析模式。
標的：$target
賽道：$sector

三種模式：
1. MODE_NORMAL_SIX — 六智體通用：適用消費、醫藥、周期、整車等無明確上游賣水邏輯的普通公司
2. MODE_SELLER_SEVEN — 七智體賣水人：適用光通信、半導體、鋰電、光伏等上下游分層清晰賽道
3. MODE_SELLER_SIMPLE — 精簡版：5+1 常態化分析，適合大多數標的

請只回覆模式名稱（MODE_NORMAL_SIX 或 MODE_SELLER_SEVEN 或 MODE_SELLER_SIMPLE），不要其他內容。""",
                            onSuccess = {},
                            onComplete = { full ->
                                val mode = when {
                                    full.contains("MODE_SELLER_SEVEN") -> AnalysisMode.MODE_SELLER_SEVEN
                                    full.contains("MODE_NORMAL_SIX") -> AnalysisMode.MODE_NORMAL_SIX
                                    else -> AnalysisMode.MODE_SELLER_SIMPLE
                                }
                                cont.resume(mode)
                            },
                            onError = { cont.resume(AnalysisMode.MODE_SELLER_SIMPLE) }
                        )
                    }
                } finally {
                    AiProviderPool.releaseNonBlocking(slot)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI 模式選擇失敗，使用默認精簡版: ${e.message}")
                AnalysisMode.MODE_SELLER_SIMPLE
            }
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

    private fun applyHedgeMechanism(ctx: PipelineContext) {
        val chainScore = ctx.chainScore ?: return
        val riskResult = ctx.riskResult ?: return

        if (chainScore.overseasBonus > 0 && riskResult.overseasDeduction > 0) {
            Log.w(TAG, "對沖觸發：海外供應鏈加分 ${chainScore.overseasBonus} 被清零")
            val newTotal = (chainScore.totalScore - chainScore.overseasBonus - chainScore.foreignRatingBonus)
                .coerceAtLeast(0)
            ctx.chainScore = chainScore.copy(
                overseasBonus = 0, foreignRatingBonus = 0,
                totalScore = newTotal, passed = newTotal >= 40
            )
            ctx.riskResult = riskResult.copy(adjustedScore = newTotal)
        }
    }

    private suspend fun injectQuantSignals(ctx: PipelineContext) {
        val provider = quantSignalsProvider ?: return
        val stockCode = ctx.chainScore?.stockCode ?: extractStockCode(ctx.target)
        if (stockCode == ctx.target) return

        try {
            val signals = provider(stockCode)
            if (signals.isEmpty()) return
            val sb = StringBuilder("【量化策略信號（${signals.size} 條）】\n")
            for (sig in signals) {
                sb.append("- ${sig.emoji} [${sig.strategyId}] ${sig.stockName}: ${sig.reason} (強度:${sig.strength}%, 建議:${sig.action.label})\n")
            }
            ctx.quantSignals = sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "量化信號注入失敗: ${e.message?.take(60)}")
        }
    }

    /**
     * 構建最終結果（含豆包風格綜合摘要）
     */
    private fun buildFinalResult(ctx: PipelineContext, mode: AnalysisMode, steps: List<PipelineStep>): PipelineResult {
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
            totalSteps = steps.size,
            stocks = listOf(stockResult),
            analysisMode = mode.label,
            weightFormula = getWeightFormula(mode)
        )
    }

    /**
     * 調用 LLM（SSE 串流）
     */
    private suspend fun callLLM(step: PipelineStep, allSteps: List<PipelineStep>, ctx: PipelineContext, mode: AnalysisMode): String {
        return withContext(Dispatchers.IO) {
            val agent = agentManager.get(step.agentId)
            val systemPrompt = buildStepPrompt(step, allSteps, agent?.systemPrompt ?: "", ctx, mode)

            val slot = AiProviderPool.acquire(context)
                ?: throw IllegalStateException("無可用 AI Provider")

            try {
                suspendCancellableCoroutine { cont ->
                    slot.provider.sendMessageStream(
                        messages = emptyList(),
                        systemPrompt = systemPrompt,
                        onSuccess = { },
                        onComplete = { full ->
                            val sanitized = full.replace("null", "")
                            cont.resume(sanitized)
                        },
                        onError = { err -> cont.resumeWithException(Exception(err)) }
                    )
                }
            } finally {
                AiProviderPool.releaseNonBlocking(slot)
            }
        }
    }

    /**
     * 構建每步的 SystemPrompt（含前序步驟累積 + 豆包輸出格式）
     */
    private fun buildStepPrompt(step: PipelineStep, allSteps: List<PipelineStep>, basePrompt: String, ctx: PipelineContext, mode: AnalysisMode): String {
        val sb = StringBuilder()

        // 基礎 SystemPrompt
        sb.append(basePrompt)
        sb.append("\n\n")

        // 注入前序步驟摘要（串行傳參規則）
        val mainSteps = allSteps.filter { !it.isAuxiliary }
        val currentIndex = mainSteps.indexOf(step)
        if (currentIndex > 0) {
            sb.append("【前序智能體分析結果匯總】\n")
            for (i in 0 until currentIndex) {
                val prevStep = mainSteps[i]
                val prevAnalysis = ctx.stepAnalyses[i]
                if (prevAnalysis != null) {
                    sb.append("── ${prevStep.name} ──\n")
                    sb.append(prevAnalysis.take(800))  // 截取前 800 字避免過長
                    sb.append("\n\n")
                }
            }
        }

        // 注入 Agent F 情報（若有）
        ctx.intelligence?.let { intel ->
            sb.append("【最新產業情報】\n")
            sb.append("催化事件：${intel.events.joinToString("；")}\n")
            sb.append("外資評級：${intel.ratings.joinToString("；")}\n")
            sb.append("供需格局：${intel.supplyChain.joinToString("；")}\n\n")
        }

        // 分析標的
        sb.append("【分析標的】${ctx.target}\n")
        sb.append("【賽道範圍】${ctx.sector}\n")
        sb.append("【權重公式】${getWeightFormula(mode)}\n\n")

        // 豆包風格輸出格式
        sb.append(OUTPUT_FORMAT_TEMPLATE)

        return sb.toString()
    }

    private fun extractStockCode(input: String): String {
        val pattern = Regex("(sh|sz|bj)\\d{6}")
        return pattern.find(input)?.value ?: input
    }
}
