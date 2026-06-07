package com.chin.stockanalysis.ai

import android.util.Log
import com.chin.stockanalysis.ApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ## 多 AI 编排器
 *
 * 并行调用多个 AI Provider 获取不同维度信息，汇总后供主 AI 综合分析。
 *
 * ### 工作流程
 * ```
 * 用户问题 "600519 怎么样？"
 *    ├─ async { stockProvider → 获取股票行情/技术指标分析 }
 *    ├─ async { newsProvider  → 获取近期新闻/利好利空因子 }
 *    └─ 汇总结果 → 追加到主 AI system prompt → 主 AI 综合回复
 * ```
 *
 * ### Provider 分配
 * | 角色 | Provider | 输出 |
 * |------|----------|------|
 * | 股票分析师 | stockProvider | JSON: 价格/涨跌/技术建议 |
 * | 新闻分析师 | newsProvider | JSON: 近期利好/利空/热度 |
 * | 综合顾问 | 主 AI (system prompt) | 汇总输出给用户 |
 */
class AiOrchestrator {

    companion object {
        private const val TAG = "AiOrchestrator"

        private const val STOCK_PROMPT = """你是股票行情分析师。仅输出 JSON：
{"price":当前价,"change":"+2.5%","volume":"放量/缩量/平量","trend":"上升/下降/震荡","action":"建议买入/持有观望/谨慎/建议卖出","ma5":"5日均线位置描述","reason":"一句话核心逻辑(15字内)"}
不输出其他内容。"""

        private const val NEWS_PROMPT = """你是财经新闻分析师。仅输出 JSON：
{"bullish":[{"title":"利好标题","impact":"强/中/弱","date":"YYYY-MM-DD"}],"bearish":[{"title":"利空标题","impact":"强/中/弱","date":"YYYY-MM-DD"}],"summary":"一句话总结(15字内)"}
不输出其他内容。"""

        private const val SYNTHESIS_MARKER = "\n\n【多维度AI分析报告】\n"
    }

    /**
     * 并行调用 stockProvider + newsProvider 获取多维度分析。
     *
     * @param primary 默认 Provider（单 AI 模式时复用）
     * @param stockProvider 股票分析用 Provider（null 则跳过）
     * @param newsProvider 新闻分析用 Provider（null 则跳过）
     * @param query 用户原始问题
     * @return 追加到 system prompt 的综合分析文本
     */
    suspend fun fetchMultiAnalysis(
        primary: ApiProvider,
        stockProvider: ApiProvider?,
        newsProvider: ApiProvider?,
        query: String
    ): String = coroutineScope {
        val stockDeferred = if (stockProvider != null) {
            async(Dispatchers.IO) { tryFetch(stockProvider, STOCK_PROMPT, query) }
        } else null

        val newsDeferred = if (newsProvider != null) {
            async(Dispatchers.IO) { tryFetch(newsProvider, NEWS_PROMPT, query) }
        } else null

        val stockResult = stockDeferred?.await()
        val newsResult = newsDeferred?.await()

        buildString {
            if (stockResult != null || newsResult != null) {
                append(SYNTHESIS_MARKER)
                if (stockResult != null) {
                    append("📊 股票行情: $stockResult\n")
                }
                if (newsResult != null) {
                    append("📰 新闻动向: $newsResult\n")
                }
                append("\n请综合以上多维度分析，给用户一个全面的回复。")
            }
        }
    }

    private suspend fun tryFetch(provider: ApiProvider, systemPrompt: String, query: String): String? {
        return try {
            val fullPrompt = "$systemPrompt\n\n用户问题: $query"
            suspendCancellableCoroutine { cont ->
                provider.sendMessageStream(
                    messages = emptyList(),
                    systemPrompt = fullPrompt,
                    onSuccess = {},
                    onComplete = { full -> cont.resume(full) },
                    onError = { err -> cont.resumeWithException(Exception(err)) }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "并行AI获取失败: ${e.message?.take(60)}")
            null
        }
    }
}