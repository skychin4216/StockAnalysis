package com.chin.stockanalysis.stock.formatter

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent
import java.text.SimpleDateFormat
import java.util.*

/**
 * 股票数据格式化器 - 将数据转为 AI 友好的文本
 */
class StockDataFormatter {

    /**
     * 根据意图和数据生成适合注入 prompt 的文本
     */
    fun format(intent: IntentResult, data: Map<String, StockRealtime>): String {
        return when (intent.intent) {
            StockIntent.QUERY_PRICE -> formatRealtime(intent, data)
            StockIntent.QUERY_INDEX -> formatIndex(data)
            StockIntent.TECHNICAL_ANALYSIS -> formatTechnicalAnalysis(intent, data)
            StockIntent.COMPARE_STOCKS -> formatComparison(intent, data)
            else -> ""
        }
    }

    /**
     * 格式化实时行情
     */
    private fun formatRealtime(intent: IntentResult, data: Map<String, StockRealtime>): String {
        if (data.isEmpty()) return "（无相关股票数据）"

        val sb = StringBuilder()
        sb.appendLine("【实时行情数据】")
        sb.appendLine("（以下是用户查询的股票实时行情，请基于这些数据回答）")
        sb.appendLine()

        for ((code, stock) in data) {
            val arrow = when {
                stock.changeAmount > 0 -> "📈"
                stock.changeAmount < 0 -> "📉"
                else -> "➡️"
            }

            sb.appendLine("$arrow ${stock.name} ($code)")
            sb.appendLine("   当前价: ${String.format("%.2f", stock.price)} 元")
            sb.appendLine("   涨跌: ${if (stock.changeAmount > 0) "+" else ""}${String.format("%.2f", stock.changeAmount)} (${String.format("%.2f", stock.changePercent)}%)")
            sb.appendLine("   最高: ${String.format("%.2f", stock.high)}  最低: ${String.format("%.2f", stock.low)}")
            sb.appendLine("   成交量: ${stock.volume / 10000} 万手  成交额: ${String.format("%.2f", stock.amount / 100000000)} 亿元")
            sb.appendLine("   昨收: ${String.format("%.2f", stock.yestClose)}  开盘: ${String.format("%.2f", stock.open)}")
            sb.appendLine("   更新时间: ${formatTimestamp(stock.timestamp)}")
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * 格式化指数
     */
    private fun formatIndex(data: Map<String, StockRealtime>): String {
        if (data.isEmpty()) return "（无相关指数数据）"

        val sb = StringBuilder()
        sb.appendLine("【大盘指数】")
        sb.appendLine()

        for ((code, stock) in data) {
            val arrow = when {
                stock.changeAmount > 0 -> "📈"
                stock.changeAmount < 0 -> "📉"
                else -> "➡️"
            }

            sb.appendLine("$arrow ${stock.name}")
            sb.appendLine("   当前: ${String.format("%.2f", stock.price)}")
            sb.appendLine("   涨跌: ${if (stock.changeAmount > 0) "+" else ""}${String.format("%.4f", stock.changeAmount)} (${String.format("%.2f", stock.changePercent)}%)")
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * 技术分析 Prompt 数据：当前版本注入实时行情 + 明确要求 AI 结合 K线/指标框架分析。
     * 历史 K 线可由 StockTabFragment 展示，后续可扩展 HistorySource 注入更多 OHLC 数据。
     */
    private fun formatTechnicalAnalysis(intent: IntentResult, data: Map<String, StockRealtime>): String {
        if (data.isEmpty()) return "（识别到技术分析意图，但未获取到实时行情数据）"
        val analysisType = intent.parsedParams["analysis_type"]?.toString().orEmpty().ifBlank { "general" }
        val sb = StringBuilder()
        sb.appendLine("【股票技术分析上下文】")
        sb.appendLine("用户原问题：${intent.rawQuery}")
        sb.appendLine("分析类型：$analysisType")
        sb.appendLine("请基于以下实时行情，并结合常见技术分析框架（趋势、均线、MACD、量价关系、支撑/压力）给出专业但不构成投资建议的分析。")
        sb.appendLine()
        sb.append(formatRealtime(intent, data))
        sb.appendLine("输出要求：")
        sb.appendLine("1. 先总结当前价格和涨跌幅；")
        sb.appendLine("2. 再分析趋势/量价/风险；")
        sb.appendLine("3. 最后给出风险提示，不要直接喊买卖。")
        return sb.toString()
    }

    /** 多股票对比分析 Prompt 数据。 */
    private fun formatComparison(intent: IntentResult, data: Map<String, StockRealtime>): String {
        if (data.isEmpty()) return "（识别到股票对比意图，但未获取到实时行情数据）"
        val sb = StringBuilder()
        sb.appendLine("【股票对比分析上下文】")
        sb.appendLine("用户原问题：${intent.rawQuery}")
        sb.appendLine("请基于以下多只股票实时行情，从涨跌幅、成交活跃度、价格位置、短线风险等维度进行横向对比。")
        sb.appendLine()
        sb.append(formatRealtime(intent, data))
        sb.appendLine("输出要求：")
        sb.appendLine("1. 用表格或分点对比；")
        sb.appendLine("2. 强调数据时效性和风险；")
        sb.appendLine("3. 不给具体买卖指令，仅提供分析参考。")
        return sb.toString()
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

