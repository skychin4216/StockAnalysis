package com.chin.stockanalysis.stock.intent.handlers

import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent

/**
 * 股票代码处理器 - 识别纯数字代码 "600519" / "sh600519"
 */
class StockCodeHandler : IntentHandler {
    override var next: IntentHandler? = null

    companion object {
        // 匹配 6 位数字或以 sh/sz 开头的股票代码
        private val STOCK_CODE_REGEX = Regex("(sh|sz)?\\d{6}")
    }

    override fun match(input: String): Boolean {
        return STOCK_CODE_REGEX.containsMatchIn(input)
    }

    override fun parse(input: String): IntentResult {
        val codes = STOCK_CODE_REGEX.findAll(input)
            .map {
                val code = it.value
                // 自动添加 sh/sz 前缀
                if (code.startsWith("sh") || code.startsWith("sz")) {
                    code
                } else {
                    normalizeCode(code)
                }
            }
            .distinct()
            .toList()

        val upperInput = input.uppercase()
        val intent = when {
            codes.size >= 2 && (input.contains("对比") || input.contains("比较") || input.contains("哪个") || input.contains("哪只") || upperInput.contains("VS")) -> StockIntent.COMPARE_STOCKS
            input.contains("MACD", ignoreCase = true) || input.contains("K线") || input.contains("技术") || input.contains("指标") || input.contains("走势") || input.contains("分析") -> StockIntent.TECHNICAL_ANALYSIS
            else -> StockIntent.QUERY_PRICE
        }

        return IntentResult(
            intent = intent,
            stockCodes = codes,
            stockNames = emptyList(),
            confidence = if (codes.size == 1) 0.95f else 0.85f,
            rawQuery = input,
            parsedParams = mapOf("analysis_type" to detectAnalysisType(input))
        )
    }

    private fun normalizeCode(code: String): String {
        return when {
            code.startsWith("6") -> "sh$code"
            code.startsWith("0") || code.startsWith("3") -> "sz$code"
            code.startsWith("4") || code.startsWith("8") -> "bj$code"
            else -> "sh$code"
        }
    }

    private fun detectAnalysisType(input: String): String {
        return when {
            input.contains("MACD", ignoreCase = true) -> "macd"
            input.contains("K线") || input.contains("蜡烛图") -> "kline"
            input.contains("均线") || input.contains("MA") -> "ma"
            input.contains("成交量") || input.contains("量能") -> "volume"
            input.contains("走势") || input.contains("趋势") -> "trend"
            else -> "general"
        }
    }
}

