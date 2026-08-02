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
 * PlanningAgent — 智能分析規劃層
 *
 * 在 IntentRouter 確定性路由之後，Orchestrator 執行之前，
 * 用一次輕量 LLM 調用決定：
 * - 分析深度（快速/標準/深度）
 * - 需要關注的分析維度（技術面/基本面/資金面/輿情/產業鏈）
 * - 是否需要對比分析（同板塊競品）
 * - 特殊注意事項（財報窗口、除權除息等）
 *
 * 設計原則：
 * - 單次 LLM 調用，15s 超時
 * - 失敗時降級為默認計劃，不阻塞主流程
 * - 輸出結構化 AnalysisPlan，Orchestrator 據此調整參數
 */
class PlanningAgent(private val appContext: Context) {

    companion object {
        private const val TAG = "PlanningAgent"
        private const val TIMEOUT_MS = 15_000L
    }

    /**
     * 分析計劃 — Orchestrator 據此調整執行策略
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
     * 生成分析計劃
     *
     * @param userIntent 已路由的用戶意圖
     * @param stockCode 目標股票代碼
     * @param stockName 股票名稱
     * @return AnalysisPlan（失敗時返回默認計劃）
     */
    suspend fun plan(
        userIntent: UserIntent,
        stockCode: String?,
        stockName: String?
    ): AnalysisPlan {
        // 非 DEEP_ANALYSIS 不走規劃
        if (userIntent.type != IntentType.DEEP_ANALYSIS) {
            return AnalysisPlan()
        }

        // 無目標股票（全市場掃描）不需要規劃
        if (stockCode == null) {
            return AnalysisPlan()
        }

        return try {
            val planJson = callLLMForPlan(userIntent, stockCode, stockName)
            parsePlan(planJson)
        } catch (e: Exception) {
            Log.w(TAG, "規劃失敗，使用默認計劃: ${e.message}")
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
                        你是股票分析規劃師。根據用戶意圖和股票信息，決定分析策略。
                        輸出嚴格 JSON，不要其他文字。

                        格式：
                        {
                          "depth": "QUICK|STANDARD|DEEP",
                          "focus": ["TECHNICAL","FUNDAMENTAL","FUND_FLOW","SENTIMENT","INDUSTRY_CHAIN"],
                          "compare_peers": false,
                          "peer_sector": null,
                          "notes": []
                        }

                        規則：
                        - depth=QUICK：超短線/日內交易，只需技術面+資金面
                        - depth=STANDARD：短線/中線，技術+基本面+資金面
                        - depth=DEEP：長線/價值投資，全維度+產業鏈+同業對比
                        - focus：只選需要的維度，不要全選
                        - compare_peers：長線/中線時可考慮同業對比
                        - notes：特殊注意事項（如"注意財報窗口期"）
                    """.trimIndent()

                    val userPrompt = buildString {
                        appendLine("股票: ${stockName ?: "未知"}($stockCode)")
                        appendLine("週期: ${intent.period?.name ?: "SHORT"}")
                        appendLine("用戶問題: ${intent.rawInput.take(200)}")
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
            // 如果 LLM 沒指定，用默認全維度
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
            Log.w(TAG, "解析計劃失敗: ${e.message}")
            AnalysisPlan(fallback = true)
        }
    }
}
