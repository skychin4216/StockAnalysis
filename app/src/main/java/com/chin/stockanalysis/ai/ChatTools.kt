package com.chin.stockanalysis.ai

/**
 * 對話工具定義集
 *
 * 用於原生 Function Calling（OpenAI 兼容格式）。
 * LLM 根據這些定義自動決定調用哪個工具。
 */
object ChatTools {

    /**
     * 工具定義（OpenAI function calling 格式）
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
     * LLM 返回的 tool_call 結構
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

    /** 股票查詢工具 */
    val stockQuery = ToolDef(function = FunctionDef(
        name = "stock_query",
        description = "根據股票名稱或代碼查詢實時行情、基本面數據。支援股票名稱（如'兆易創新'）和代碼（如'603986'或'sh603986'）。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "股票名稱或代碼"
                ),
                "include_fundamentals" to mapOf(
                    "type" to "boolean",
                    "description" to "是否包含基本面數據（PE/PB/ROE），默認 true"
                )
            ),
            "required" to listOf("query")
        )
    ))

    /** 板塊查詢工具 */
    val sectorQuery = ToolDef(function = FunctionDef(
        name = "sector_query",
        description = "查詢板塊/行業的熱門程度、成分股、資金流向。",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "sector_name" to mapOf(
                    "type" to "string",
                    "description" to "板塊名稱（如'光通信'、'半導體'、'華為概念'）"
                )
            ),
            "required" to listOf("sector_name")
        )
    ))

    /** 市場簡報工具 */
    val marketBrief = ToolDef(function = FunctionDef(
        name = "market_brief",
        description = "獲取 A 股市場總覽（大盤指數、漲跌停數、熱門板塊、北向資金）。",
        parameters = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>()
        )
    ))

    /** 所有工具列表 */
    val allTools: List<ToolDef> = listOf(stockQuery, sectorQuery, marketBrief)

    /** 檢查 Provider 是否支援 Function Calling */
    fun isSupportedByProvider(providerName: String): Boolean {
        return providerName in setOf(
            "doubao", "dashscope-qwen3", "deepseek-official"
        )
    }
}
