package com.chin.stockanalysis.stock.intent.handlers

import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent

/**
 * ## 通用股票查询处理器
 *
 * 识别没有具体股票代码/名称的泛化股市查询，如：
 * - "分析今天股市" → 给 AI 传递"无具体股票数据"提示
 * - "今天大盘怎么样" → 同上
 * - "股市行情" → 同上
 *
 * 这些查询没有具体股票代码，因此无法实时获取数据，
 * Handler 标记为 GENERAL_CHAT + stockCodes=空 + 低置信度，
 * 由 IntentProcessorChain 决定是否走 AI 兜底或直接让 AI 自己回答。
 */
class GeneralStockHandler : IntentHandler {
    override var next: IntentHandler? = null

    companion object {
        // 匹配通用股市词汇（不含具体股票名称/代码）
        private val GENERAL_PATTERNS = listOf(
            Regex("股市|大盘|行情|涨停|跌停|市场|A股|股票|指数"),
            Regex("分析.*(今天|今日|最近|近期).*(股市|大盘|市场|行情)"),
            Regex("(今天|今日|最近|近期).*(股市|大盘|市场|行情)"),
            Regex("推荐.*(股票|个股|板块)"),
            Regex("板块.*(行情|走势|分析)")
        )
    }

    override fun match(input: String): Boolean {
        return GENERAL_PATTERNS.any { it.containsMatchIn(input) }
    }

    override fun parse(input: String): IntentResult {
        return IntentResult(
            intent = StockIntent.UNKNOWN, // 无法精确定位到具体股票
            stockCodes = emptyList(),
            stockNames = emptyList(),
            confidence = 0.3f, // 低置信度，由后续 AI 兜底或直接让 AI 回答
            rawQuery = input
        )
    }
}