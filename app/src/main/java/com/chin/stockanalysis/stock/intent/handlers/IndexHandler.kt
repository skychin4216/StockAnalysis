package com.chin.stockanalysis.stock.intent.handlers

import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent

/**
 * 指数处理器 - 识别指数查询 "上证指数" / "创业板指" 等
 */
class IndexHandler : IntentHandler {
    override var next: IntentHandler? = null

    companion object {
        private val INDEX_MAP = mapOf(
            "上证指数" to "sh000001",
            "深证指数" to "sz399106",
            "创业板指" to "sz399006",
            "中小板指" to "sz399005",
            "沪深300" to "sh000300",
            "上证50" to "sh000016",
            "中证100" to "sh000903"
        )
    }

    override fun match(input: String): Boolean {
        return INDEX_MAP.keys.any { input.contains(it) }
    }

    override fun parse(input: String): IntentResult {
        val matchedNames = mutableListOf<String>()
        val matchedCodes = mutableListOf<String>()

        for ((name, code) in INDEX_MAP) {
            if (input.contains(name)) {
                matchedNames.add(name)
                matchedCodes.add(code)
            }
        }

        return IntentResult(
            intent = StockIntent.QUERY_INDEX,
            stockCodes = matchedCodes,
            stockNames = matchedNames,
            confidence = 0.9f,
            rawQuery = input
        )
    }
}

