package com.chin.stockanalysis.agent.core

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.strategy.HoldingPeriod
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * PlanningAgent — 智能分析规划层
 *
 * 在 IntentRouter 确定性路由之后，Orchestrator 执行之前，
 * 用一次轻量 LLM 调用决定：
 * - 分析深度（快速/标准/深度）
 * - 需要关注的分析维度（技术面/基本面/资金面/舆情/产业链）
 * - 是否需要对比分析（同板块竞品）
 * - 特殊注意事项（财报窗口、除权除息等）
 *
 * 设计原则：
 * - 单次 LLM 调用，15s 超时
 * - 失败时降级为默认计划，不阻塞主流程
 * - 输出结构化 AnalysisPlan，Orchestrator 据此调整参数
 */
class PlanningAgent(private val appContext: Context) {

    companion object {
        private const val TAG = "PlanningAgent"
        private const val TIMEOUT_MS = 15_000L
    }

    /**
     * 分析计划 — Orchestrator 据此调整执行策略
     */
    data class AnalysisPlan(
        val depth: Depth = Depth.STANDARD,
        val focusDimensions: Set<Dimension> = Dimension.entries.toSet(),
        val compareWithPeers: Boolean = false,
        val peerSector: String? = null,
        val specialNotes: List<String> = emptyList(),
        val estimatedToolCalls: Int = 0,
        val fallback: Boolean = false
    ) {
        enum class Depth { QUICK, STANDARD, DEEP }
        enum class Dimension { TECHNICAL, FUNDAMENTAL, FUND_FLOW, SENTIMENT, INDUSTRY_CHAIN }
    }

    /**
     * 生成分析计划
     *
     * @param userIntent 已路由的用户意图
     * @param stockCode 目标股票代码
     * @param stockName 股票名称
     * @return AnalysisPlan（失败时返回默认计划）
     */
    suspend fun plan(
        userIntent: UserIntent,
        stockCode: String?,
        stockName: String?
    ): AnalysisPlan {
        // 非 DEEP_ANALYSIS 不走规划
        if (userIntent.type != IntentType.DEEP_ANALYSIS) {
            return AnalysisPlan()
        }

        // 无目标股票（全市场扫描）不需要规划
        if (stockCode == null) {
            return AnalysisPlan()
        }

        return try {
            val planJson = callLLMForPlan(userIntent, stockCode, stockName)
            parsePlan(planJson)
        } catch (e: Exception) {
            Log.w(TAG, "规划失败，使用默认计划: ${e.message}")
            AnalysisPlan(fallback = true)
        }
    }

    private suspend fun callLLMForPlan(
        intent: UserIntent,
        stockCode: String,
        stockName: String?
    ): String {
        val slot = AiProviderPool.acquire(
            context = appContext,
            callerTag = "PlanningAgent",
            timeoutMs = TIMEOUT_MS
        ) ?: return "{}"

        return try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    var resumed = false
                    val sb = StringBuilder()

                    val systemPrompt = """
                        你是股票分析规划师。根据用户意图和股票信息，决定分析策略。
                        输出严格 JSON，不要其他文字。

                        格式：
                        {
                          "depth": "QUICK|STANDARD|DEEP",
                          "focus": ["TECHNICAL","FUNDAMENTAL","FUND_FLOW","SENTIMENT","INDUSTRY_CHAIN"],
                          "compare_peers": false,
                          "peer_sector": null,
                          "notes": []
                        }

                        规则：
                        - depth=QUICK：超短线/日内交易，只需技术面+资金面
                        - depth=STANDARD：短线/中线，技术+基本面+资金面
                        - depth=DEEP：长线/价值投资，全维度+产业链+同业对比
                        - focus：只选需要的维度，不要全选
                        - compare_peers：长线/中线时可考虑同业对比
                        - notes：特殊注意事项（如"注意财报窗口期"）
                    """.trimIndent()

                    val userPrompt = buildString {
                        appendLine("股票: ${stockName ?: "未知"}($stockCode)")
                        appendLine("周期: ${intent.period?.name ?: "SHORT"}")
                        appendLine("用户问题: ${intent.rawInput.take(200)}")
                    }

                    slot.provider.sendMessageStream(
                        messages = listOf(com.chin.stockanalysis.ui.Message(content = userPrompt, isUser = true)),
                        systemPrompt = systemPrompt,
                        onSuccess = { chunk -> sb.append(chunk) },
                        onComplete = { full ->
                            if (!resumed) { resumed = true; cont.resume(full, null) }
                        },
                        onError = { err ->
                            if (!resumed) { resumed = true; cont.resume("{}", null) }
                        }
                    )
                }
            }
        } finally {
            AiProviderPool.releaseNonBlocking(slot)
        }
    }

    private fun parsePlan(jsonStr: String): AnalysisPlan {
        return try {
            val cleaned = jsonStr.substringAfter("```json").substringBefore("```").trim()
                .ifEmpty { jsonStr.trim() }
            val json = JSONObject(cleaned)

            val depth = when (json.optString("depth", "STANDARD").uppercase()) {
                "QUICK" -> AnalysisPlan.Depth.QUICK
                "DEEP" -> AnalysisPlan.Depth.DEEP
                else -> AnalysisPlan.Depth.STANDARD
            }

            val focusDimensions = mutableSetOf<AnalysisPlan.Dimension>()
            json.optJSONArray("focus")?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (arr.getString(i).uppercase()) {
                        "TECHNICAL" -> focusDimensions.add(AnalysisPlan.Dimension.TECHNICAL)
                        "FUNDAMENTAL" -> focusDimensions.add(AnalysisPlan.Dimension.FUNDAMENTAL)
                        "FUND_FLOW" -> focusDimensions.add(AnalysisPlan.Dimension.FUND_FLOW)
                        "SENTIMENT" -> focusDimensions.add(AnalysisPlan.Dimension.SENTIMENT)
                        "INDUSTRY_CHAIN" -> focusDimensions.add(AnalysisPlan.Dimension.INDUSTRY_CHAIN)
                    }
                }
            }
            // 如果 LLM 没指定，用默认全维度
            if (focusDimensions.isEmpty()) {
                focusDimensions.addAll(AnalysisPlan.Dimension.entries)
            }

            val notes = mutableListOf<String>()
            json.optJSONArray("notes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    notes.add(arr.getString(i))
                }
            }

            AnalysisPlan(
                depth = depth,
                focusDimensions = focusDimensions,
                compareWithPeers = json.optBoolean("compare_peers", false),
                peerSector = json.optString("peer_sector", null),
                specialNotes = notes,
                estimatedToolCalls = when (depth) {
                    AnalysisPlan.Depth.QUICK -> 1
                    AnalysisPlan.Depth.STANDARD -> 3
                    AnalysisPlan.Depth.DEEP -> 5
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析计划失败: ${e.message}")
            AnalysisPlan(fallback = true)
        }
    }
}
