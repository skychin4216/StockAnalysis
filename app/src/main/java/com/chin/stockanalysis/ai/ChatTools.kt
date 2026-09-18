package com.chin.stockanalysis.ai

/**
 * 对话工具定义集
 *
 * 用于原生 Function Calling（OpenAI 兼容格式）。
 * LLM 根据这些定义自动决定调用哪个工具。
 */
object ChatTools {

    /**
     * 工具定义（OpenAI function calling 格式）
     */
    data class ToolDef(
        val type: String = "function",
        val function: FunctionDef
    )

    data class FunctionDef(
        val name: String,
        val description: String,
        val parameters: Map<String, Any>
    )

    /**
     * LLM 返回的 tool_call 结构
     */
    data class ToolCall(
        val id: String,
        val type: String,
        val function: FunctionCall
    )

    data class FunctionCall(
        val name: String,
        val arguments: String  // JSON 字符串
    )

    /** 股票查询工具 */
    val stockQuery = ToolDef(function = FunctionDef(
        name = "stock_query",
        description = "根据股票名称或代码查询实时行情、基本面数据。支援股票名称（如'兆易创新'）和代码（如'603986'或'sh603986'）。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "股票名称或代码"
                ),
                "include_fundamentals" to mapOf(
                    "type" to "boolean",
                    "description" to "是否包含基本面数据（PE/PB/ROE），默认 true"
                )
            ),
            "required" to listOf("query")
        )
    ))

    /** 板块查询工具 */
    val sectorQuery = ToolDef(function = FunctionDef(
        name = "sector_query",
        description = "查询板块/行业的热门程度、成分股、资金流向。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "sector_name" to mapOf(
                    "type" to "string",
                    "description" to "板块名称（如'光通信'、'半导体'、'华为概念'）"
                )
            ),
            "required" to listOf("sector_name")
        )
    ))

    /** 市场简报工具 */
    val marketBrief = ToolDef(function = FunctionDef(
        name = "market_brief",
        description = "获取 A 股市场总览（大盘指数、涨跌停数、热门板块、北向资金）。",
        parameters = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>()
        )
    ))

    /** 所有工具列表 */
    val allTools: List<ToolDef> = listOf(stockQuery, sectorQuery, marketBrief)

    /** 检查 Provider 是否支援 Function Calling */
    fun isSupportedByProvider(providerName: String): Boolean {
        return providerName in setOf(
            "doubao", "dashscope-qwen3", "deepseek-official"
        )
    }
}
