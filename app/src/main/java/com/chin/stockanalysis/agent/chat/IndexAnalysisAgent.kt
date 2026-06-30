package com.chin.stockanalysis.agent.chat

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ai.AiProviderSelector
import com.chin.stockanalysis.stock.analysis.IndexKlineAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

/**
 * 指數分析 Agent
 *
 * 基於指數 K 線形態數據，調用 LLM 進行技術面預判。
 *
 * 用戶輸入如「上證指數怎麼樣」「大盤上周漲了4天，周五大跌，怎麼看」時觸發。
 */
object IndexAnalysisAgent {

    private const val TAG = "IndexAnalysisAgent"
    /** 推理模型較慢，給 60s */
    private const val LLM_TIMEOUT_MS = 60_000L

    data class IndexAnalysisResult(
        val indexName: String,
        val summary: String,        // 一句話總結
        val technicalView: String,  // 技術面觀點
        val prediction: String,     // 短線預判
        val risks: List<String>,    // 風險提示
        val rawKlineData: String    // 原始 K 線數據（調試用）
    )

    /**
     * 分析指數並輸出 AI 預判
     *
     * @param context Android Context
     * @param indexCode 指數代碼（如 "sh000001"）
     * @param indexName 指數名稱（如 "上證指數"）
     * @return IndexAnalysisResult，若無數據返回 null
     */
    suspend fun analyze(context: Context, indexCode: String, indexName: String): IndexAnalysisResult? {
        // Step 1: 獲取 K 線形態分析
        val kline = IndexKlineAnalyzer.analyze(context, indexCode, indexName, days = 20)
            ?: return null

        // Step 2: 構建 prompt
        val prompt = buildIndexPrompt(kline)

        // Step 3: 調用 LLM（總超時 60s，20s 無活動檢測）
        val response = withContext(Dispatchers.IO) {
            val provider = AiProviderSelector.getProvider(
                context = context,
                scenario = AiProviderSelector.AiScenario.CHAT_AGENT
            ) ?: return@withContext ""

            val startTime = System.currentTimeMillis()
            var lastTokenTime = startTime
            var hasReceivedToken = false

            try {
                kotlinx.coroutines.withTimeout(LLM_TIMEOUT_MS) {
                    coroutineScope {
                        var resumed = false

                        // 無活動檢測
                        val activityJob = launch {
                            while (isActive) {
                                delay(5_000)
                                val idle = System.currentTimeMillis() - lastTokenTime
                                if (idle > 20_000 && hasReceivedToken) {
                                    Log.w(TAG, "⏱ 20s 無新 token，取消")
                                    resumed = true
                                    break
                                }
                            }
                        }

                        val result = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                            cont.invokeOnCancellation {
                                provider.cancel()
                            }

                            provider.sendMessageStream(
                                messages = listOf(com.chin.stockanalysis.ui.Message(content = prompt, isUser = true)),
                                systemPrompt = buildSystemPrompt(),
                                onSuccess = { _ ->
                                    lastTokenTime = System.currentTimeMillis()
                                    hasReceivedToken = true
                                },
                                onComplete = { full ->
                                    if (!resumed) { cont.resume(full) {} }
                                },
                                onError = { err ->
                                    if (!resumed) { cont.resumeWith(Result.failure(Exception(err))) }
                                }
                            )
                        }

                        activityJob.cancel()
                        result
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM 分析失敗: ${e.message}")
                ""
            }
        }

        if (response.isBlank()) return null

        // Step 4: 解析 LLM 回覆（簡單分段解析）
        return parseResponse(indexName, response, kline)
    }

    private fun buildSystemPrompt(): String = """
你是一位資深的 A 股指數技術分析師，擅長從 K 線形態、量能變化、均線系統中提煉交易信號。

輸出格式要求：
1. 一句話總結當前技術面狀態
2. 技術面觀點（200字內，基於均線/量能/形態）
3. 短線預判（下一週走勢傾向，給出具體價位區間或漲跌傾向）
4. 風險提示（列出2-3個需要警惕的信號）

請用繁體中文回答，語氣專業但易懂。
""".trimIndent()

    private fun buildIndexPrompt(kline: IndexKlineAnalyzer.KlineAnalysis): String {
        return """
請分析以下指數的技術面：

指數：${kline.indexName}（${kline.indexCode}）
最新收盤：${String.format("%.2f", kline.latestClose)}（${if (kline.latestChangePct >= 0) "+" else ""}${String.format("%.2f", kline.latestChangePct)}%）

K線形態（近${kline.totalUpDays + kline.totalDownDays}個交易日）：
- 連漲天數：${kline.consecutiveUpDays} 天
- 連跌天數：${kline.consecutiveDownDays} 天
- 上漲天數 / 下跌天數：${kline.totalUpDays} / ${kline.totalDownDays}

均線系統：
- MA5：${String.format("%.2f", kline.ma5)}  ${if (kline.aboveMa5) "✓ 價格在上方" else "✗ 價格在下方"}
- MA10：${String.format("%.2f", kline.ma10)} ${if (kline.aboveMa10) "✓ 價格在上方" else "✗ 價格在下方"}
- MA20：${String.format("%.2f", kline.ma20)} ${if (kline.aboveMa20) "✓ 價格在上方" else "✗ 價格在下方"}

量能：
- 最新量能 / 均量：${String.format("%.2f", kline.latestVolumeRatio)}x（${if (kline.latestVolumeRatio > 1.2) "放量" else if (kline.latestVolumeRatio < 0.8) "縮量" else "平量"}）

區間統計：
- 最高：${String.format("%.2f", kline.maxHighInPeriod)}
- 最低：${String.format("%.2f", kline.minLowInPeriod)}
- 波動率：${String.format("%.2f", kline.volatility)}%

趨勢判定：${kline.trend}
關鍵事件：${kline.keyEvents.joinToString("；")}

請根據以上數據給出技術面分析和短線預判。
""".trimIndent()
    }

    private fun parseResponse(indexName: String, response: String, kline: IndexKlineAnalyzer.KlineAnalysis): IndexAnalysisResult {
        // 簡單解析：按行分段
        val lines = response.lines().filter { it.isNotBlank() }
        val summary = lines.firstOrNull() ?: "技術面分析完成"
        val technicalView = lines.drop(1).take(3).joinToString("\n")
        val prediction = lines.find { it.contains("預判") || it.contains("預測") || it.contains("看法") } ?: "請關注均線支撐"
        val risks = lines.filter { it.contains("風險") || it.contains("警惕") || it.contains("注意") }
        return IndexAnalysisResult(
            indexName = indexName,
            summary = summary,
            technicalView = technicalView,
            prediction = prediction,
            risks = risks,
            rawKlineData = kline.toString()
        )
    }
}
