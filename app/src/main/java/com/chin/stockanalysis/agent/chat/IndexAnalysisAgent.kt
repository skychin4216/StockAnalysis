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
 * 指数分析 Agent
 *
 * 基于指数 K 线形态数据，调用 LLM 进行技术面预判。
 *
 * 用户输入如「上证指数怎么样」「大盘上周涨了4天，周五大跌，怎么看」时触发。
 */
object IndexAnalysisAgent {

    private const val TAG = "IndexAnalysisAgent"
    /** 推理模型较慢，给 60s */
    private const val LLM_TIMEOUT_MS = 60_000L

    data class IndexAnalysisResult(
        val indexName: String,
        val summary: String,        // 一句话总结
        val technicalView: String,  // 技术面观点
        val prediction: String,     // 短线预判
        val risks: List<String>,    // 风险提示
        val rawKlineData: String    // 原始 K 线数据（调试用）
    )

    /**
     * 分析指数并输出 AI 预判
     *
     * @param context Android Context
     * @param indexCode 指数代码（如 "sh000001"）
     * @param indexName 指数名称（如 "上证指数"）
     * @return IndexAnalysisResult，若无数据返回 null
     */
    suspend fun analyze(context: Context, indexCode: String, indexName: String): IndexAnalysisResult? {
        // Step 1: 获取 K 线形态分析
        val kline = IndexKlineAnalyzer.analyze(context, indexCode, indexName, days = 20)
            ?: return null

        // Step 2: 构建 prompt
        val prompt = buildIndexPrompt(kline)

        // Step 3: 调用 LLM（总超时 60s，20s 无活动检测）
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

                        // 无活动检测
                        val activityJob = launch {
                            while (isActive) {
                                delay(5_000)
                                val idle = System.currentTimeMillis() - lastTokenTime
                                if (idle > 20_000 && hasReceivedToken) {
                                    Log.w(TAG, "⏱ 20s 无新 token，取消")
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
                Log.e(TAG, "LLM 分析失败: ${e.message}")
                ""
            }
        }

        if (response.isBlank()) return null

        // Step 4: 解析 LLM 回复（简单分段解析）
        return parseResponse(indexName, response, kline)
    }

    private fun buildSystemPrompt(): String = """
你是一位资深的 A 股指数技术分析师，擅长从 K 线形态、量能变化、均线系统中提炼交易信号。

输出格式要求：
1. 一句话总结当前技术面状态
2. 技术面观点（200字内，基于均线/量能/形态）
3. 短线预判（下一周走势倾向，给出具体价位区间或涨跌倾向）
4. 风险提示（列出2-3个需要警惕的信号）

请用繁体中文回答，语气专业但易懂。
""".trimIndent()

    private fun buildIndexPrompt(kline: IndexKlineAnalyzer.KlineAnalysis): String {
        return """
请分析以下指数的技术面：

指数：${kline.indexName}（${kline.indexCode}）
最新收盘：${String.format("%.2f", kline.latestClose)}（${if (kline.latestChangePct >= 0) "+" else ""}${String.format("%.2f", kline.latestChangePct)}%）

K线形态（近${kline.totalUpDays + kline.totalDownDays}个交易日）：
- 连涨天数：${kline.consecutiveUpDays} 天
- 连跌天数：${kline.consecutiveDownDays} 天
- 上涨天数 / 下跌天数：${kline.totalUpDays} / ${kline.totalDownDays}

均线系统：
- MA5：${String.format("%.2f", kline.ma5)}  ${if (kline.aboveMa5) "✓ 价格在上方" else "✗ 价格在下方"}
- MA10：${String.format("%.2f", kline.ma10)} ${if (kline.aboveMa10) "✓ 价格在上方" else "✗ 价格在下方"}
- MA20：${String.format("%.2f", kline.ma20)} ${if (kline.aboveMa20) "✓ 价格在上方" else "✗ 价格在下方"}

量能：
- 最新量能 / 均量：${String.format("%.2f", kline.latestVolumeRatio)}x（${if (kline.latestVolumeRatio > 1.2) "放量" else if (kline.latestVolumeRatio < 0.8) "缩量" else "平量"}）

区间统计：
- 最高：${String.format("%.2f", kline.maxHighInPeriod)}
- 最低：${String.format("%.2f", kline.minLowInPeriod)}
- 波动率：${String.format("%.2f", kline.volatility)}%

趋势判定：${kline.trend}
关键事件：${kline.keyEvents.joinToString("；")}

请根据以上数据给出技术面分析和短线预判。
""".trimIndent()
    }

    private fun parseResponse(indexName: String, response: String, kline: IndexKlineAnalyzer.KlineAnalysis): IndexAnalysisResult {
        // 简单解析：按行分段
        val lines = response.lines().filter { it.isNotBlank() }
        val summary = lines.firstOrNull() ?: "技术面分析完成"
        val technicalView = lines.drop(1).take(3).joinToString("\n")
        val prediction = lines.find { it.contains("预判") || it.contains("预测") || it.contains("看法") } ?: "请关注均线支撑"
        val risks = lines.filter { it.contains("风险") || it.contains("警惕") || it.contains("注意") }
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
