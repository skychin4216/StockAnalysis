package com.chin.stockanalysis.stock.intent.handlers

import android.util.Log
import com.chin.stockanalysis.stock.analysis.AiStockAnalyzer
import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent

/**
 * ## AI 意图兜底处理器
 *
 * 职责链最后一关：当前面所有 Handler 都无法高置信度匹配时，由 AI 解析意图。
 *
 * ### 工作流程
 * 1. 总是匹配（兜底机制，match 返回 true）
 * 2. 调用 AiStockAnalyzer 发送 JSON prompt 给 LLM
 * 3. LLM 返回结构化 JSON → 解析为 IntentResult
 * 4. 返回结果给 IntentProcessorChain
 *
 * ### 使用场景
 * - "今天哪些股票涨停了？" → QUERY_HOT_STOCKS
 * - "白酒板块怎么样" → QUERY_SECTOR
 * - "分析茅台的MACD" → TECHNICAL_ANALYSIS（已有 StockNameHandler 匹配名称）
 * - "推荐几只潜力股" → UNKNOWN（AI 会解析但最终交给 LLM 自己回答）
 *
 * ### 线程安全
 * parse() 是挂起函数（suspend），需要在协程中调用。
 * 调用方（IntentProcessorChain）需要做适配。
 */
class AiIntentHandler(
    private val aiAnalyzer: AiStockAnalyzer? = null
) : IntentHandler {

    override var next: IntentHandler? = null
    private val tag = "AiIntentHandler"

    /**
     * 总是匹配（兜底处理器）
     *
     * 注意：只有当 confidence < 0.7 时才会被 IntentProcessorChain 调用到。
     * 纯名称/代码查询不会进入此处理器。
     */
    override fun match(input: String): Boolean {
        // 兜底策略：如果 AI 分析器可用，总是尝试
        if (aiAnalyzer == null) return false
        Log.d(tag, "match: AI analyzer available, will attempt intent parsing")
        return true
    }

    /**
     * 非挂起版本：如果 aiAnalyzer 不可用，返回 UNKNOWN
     * 调用方应优先使用 parseSuspend()
     */
    override fun parse(input: String): IntentResult {
        return IntentResult(
            intent = StockIntent.UNKNOWN,
            stockCodes = emptyList(),
            stockNames = emptyList(),
            confidence = 0.0f,
            rawQuery = input,
            parsedParams = emptyMap()
        )
    }

    /**
     * 挂起版本：调用 AI 分析器解析意图
     * 调用方需要确保在协程中使用此方法
     */
    suspend fun parseSuspend(input: String): IntentResult {
        if (aiAnalyzer == null) {
            Log.w(tag, "AI analyzer not available, returning UNKNOWN")
            return IntentResult(
                intent = StockIntent.UNKNOWN,
                stockCodes = emptyList(),
                stockNames = emptyList(),
                confidence = 0.0f,
                rawQuery = input
            )
        }

        Log.d(tag, "parseSuspend: delegating to AiStockAnalyzer...")
        return aiAnalyzer.analyzeIntent(input)
    }
}