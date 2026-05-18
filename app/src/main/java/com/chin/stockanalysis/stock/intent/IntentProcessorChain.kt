package com.chin.stockanalysis.stock.intent

import com.chin.stockanalysis.stock.intent.handlers.*

/**
 * 意图处理器链 - 使用职责链模式处理用户输入
 */
class IntentProcessorChain {

    private val handlers: List<IntentHandler> = listOf(
        StockCodeHandler(),      // 优先：纯数字代码
        IndexHandler(),          // 指数查询
        StockNameHandler(),      // 股票名称
        // HotStockHandler(),     // 热门股票（可选）
        // AiIntentHandler()      // AI 方案处理（需要 LLM）
    )

    /**
     * 处理用户输入，返回意图识别结果
     */
    fun process(input: String): IntentResult {
        for (handler in handlers) {
            if (handler.match(input)) {
                val result = handler.parse(input)
                // 如果置信度足够高，直接返回
                if (result.confidence >= 0.7f) {
                    return result
                }
            }
        }

        // 未能识别，返回 UNKNOWN
        return IntentResult(
            intent = StockIntent.UNKNOWN,
            stockCodes = emptyList(),
            stockNames = emptyList(),
            confidence = 0.0f,
            rawQuery = input
        )
    }
}

