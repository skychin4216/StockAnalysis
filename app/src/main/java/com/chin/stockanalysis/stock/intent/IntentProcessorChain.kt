package com.chin.stockanalysis.stock.intent

import android.util.Log
import com.chin.stockanalysis.stock.analysis.AiStockAnalyzer
import com.chin.stockanalysis.stock.intent.handlers.*

/**
 * 意图处理器链 - 使用职责链模式处理用户输入
 *
 * 处理流程：
 * 1. StockCodeHandler - 优先：纯数字代码（如"600519"）
 * 2. IndexHandler - 指数查询（如"上证指数"）
 * 3. StockNameHandler - 股票名称（如"茅台"）
 * 4. AiIntentHandler - AI 兜底解析（需要 LLM，可选）
 *
 * 如果 AI 分析器可用且前面 Handler 置信度 < 0.7，自动使用 AI 兜底。
 */
class IntentProcessorChain(
    /** AI 分析器（可选，用于复杂意图的 AI 兜底解析） */
    private val aiAnalyzer: AiStockAnalyzer? = null
) {
    private val tag = "IntentProcessorChain"
    /** 置信度阈值：低于此值则尝试 AI 兜底 */
    private val AI_FALLBACK_THRESHOLD = 0.7f

    private val handlers: List<IntentHandler> = buildList {
        add(StockCodeHandler())      // 优先：纯数字代码
        add(IndexHandler())          // 指数查询
        add(StockNameHandler())      // 股票名称
        // AI 兜底（如果可用）
        if (aiAnalyzer != null) {
            add(AiIntentHandler(aiAnalyzer))
        }
    }

    /**
     * 同步处理用户输入，返回意图识别结果
     *
     * 适用于：纯代码/名称匹配，不需要 AI 兜底的场景
     */
    fun process(input: String): IntentResult {
        for (handler in handlers) {
            if (handler.match(input)) {
                val result = handler.parse(input)
                if (result.confidence >= AI_FALLBACK_THRESHOLD) {
                    Log.d(tag, "✓ ${handler::class.simpleName}: intent=${result.intent}, confidence=${result.confidence}")
                    return result
                }
                Log.d(tag, "  ${handler::class.simpleName}: confidence=${result.confidence} < $AI_FALLBACK_THRESHOLD, trying next...")
            }
        }

        // 未能识别，返回 UNKNOWN
        Log.d(tag, "✗ No handler matched, returning UNKNOWN")
        return IntentResult(
            intent = StockIntent.UNKNOWN,
            stockCodes = emptyList(),
            stockNames = emptyList(),
            confidence = 0.0f,
            rawQuery = input
        )
    }

    /**
     * 挂起版本的处理方法 - 支持 AI 兜底意图解析
     *
     * 适用于：需要 AI 解析复杂意图的场景
     * 1. 先让普通 Handler 匹配
     * 2. 如果匹配且置信度 >= 阈值，直接返回
     * 3. 如果所有 Handler 都不匹配或置信度低，使用 AI 兜底
     *
     * @param input 用户输入
     * @return IntentResult
     */
    suspend fun processSuspend(input: String): IntentResult {
        if (input.isBlank()) {
            return IntentResult(
                intent = StockIntent.UNKNOWN,
                stockCodes = emptyList(),
                stockNames = emptyList(),
                confidence = 0.0f,
                rawQuery = input
            )
        }

        // 1. 先让普通 Handler 匹配
        for (handler in handlers) {
            if (handler.match(input)) {
                val result = handler.parse(input)
                if (result.confidence >= AI_FALLBACK_THRESHOLD) {
                    Log.d(tag, "✓ ${handler::class.simpleName}: intent=${result.intent}, confidence=${result.confidence}")
                    return result
                }
                // 置信度低，如果这是最后一个非AI Handler，继续尝试其他
                if (handler !is AiIntentHandler) {
                    Log.d(tag, "  ${handler::class.simpleName}: confidence=${result.confidence}, trying AI...")
                }
            }
        }

        // 2. 尝试 AI 兜底解析
        if (aiAnalyzer != null) {
            Log.d(tag, "🤖 Falling back to AI intent analysis...")
            val aiResult = aiAnalyzer.analyzeIntent(input)
            if (aiResult.confidence >= AI_FALLBACK_THRESHOLD) {
                Log.d(tag, "✓ AI: intent=${aiResult.intent}, confidence=${aiResult.confidence}")
                return aiResult
            }
            Log.d(tag, "✗ AI: confidence=${aiResult.confidence}, still low")
        }

        // 3. 真的无法识别
        Log.d(tag, "✗ All handlers + AI failed, returning UNKNOWN")
        return IntentResult(
            intent = StockIntent.UNKNOWN,
            stockCodes = emptyList(),
            stockNames = emptyList(),
            confidence = 0.0f,
            rawQuery = input
        )
    }
}