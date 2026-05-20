package com.chin.stockanalysis.stock.intent.handlers

import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent

/**
 * 股票名称处理器 - 识别常见股票名称 "茅台" / "五粮液" 等
 */
class StockNameHandler : IntentHandler {
    override var next: IntentHandler? = null

    companion object {
        // 常见 A 股股票名称映射表
        // 常见 A 股股票名称映射表（按代码排序）
        // 添加新的股票时，格式：\"名称\" to "sh/szXXXXXX"
        private val STOCK_NAME_MAP = mapOf(
            "茅台" to "sh600519",
            "五粮液" to "sz000858",
            "平安" to "sh601318",
            "宁德时代" to "sz300750",
            "比亚迪" to "sz002594",
            "工商银行" to "sh601398",
            "建设银行" to "sh601939",
            "农业银行" to "sh601288",
            "中国银行" to "sh601988",
            "贵州茅台" to "sh600519",
            "美的集团" to "sz000333",
            "格力电器" to "sz000651",
            "苹果" to "sh603160",
            "华勤技术" to "sh603296",
            "立讯精密" to "sz002475",
            "海康威视" to "sz002415",
            "中芯国际" to "sh688981",
            "长江电力" to "sh600900",
            "中兴通讯" to "sz000063",
            "迈瑞医疗" to "sz300760",
            "恒瑞医药" to "sh600276",
            "药明康德" to "sh603259",
            "科大讯飞" to "sz002230",
            "紫金矿业" to "sh601899",
            "万华化学" to "sh600309"
        )
    }

    override fun match(input: String): Boolean {
        return STOCK_NAME_MAP.keys.any { input.contains(it) }
    }

    override fun parse(input: String): IntentResult {
        val matchedNames = mutableListOf<String>()
        val matchedCodes = mutableListOf<String>()

        for ((name, code) in STOCK_NAME_MAP) {
            if (input.contains(name)) {
                matchedNames.add(name)
                matchedCodes.add(code)
            }
        }

        val distinctCodes = matchedCodes.distinct()
        val distinctNames = matchedNames.distinct()
        val upperInput = input.uppercase()
        val intent = when {
            distinctCodes.size >= 2 && (input.contains("对比") || input.contains("比较") || input.contains("哪个") || input.contains("哪只") || upperInput.contains("VS")) -> StockIntent.COMPARE_STOCKS
            input.contains("MACD", ignoreCase = true) || input.contains("K线") || input.contains("技术") || input.contains("指标") || input.contains("走势") || input.contains("分析") -> StockIntent.TECHNICAL_ANALYSIS
            else -> StockIntent.QUERY_PRICE
        }

        return IntentResult(
            intent = intent,
            stockCodes = distinctCodes,
            stockNames = distinctNames,
            confidence = 0.8f,
            rawQuery = input,
            parsedParams = mapOf("analysis_type" to detectAnalysisType(input))
        )
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

