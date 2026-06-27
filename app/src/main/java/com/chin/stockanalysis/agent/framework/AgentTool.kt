package com.chin.stockanalysis.agent.framework

/**
 * ## Agent 工具接口
 *
 * 每個工具是一個可復用的功能單元，Agent 可以通過 LLM 調用。
 * 工具應該是純函數（無副作用）或明確記錄副作用。
 */
interface AgentTool {
    /** 工具唯一標識 */
    val name: String

    /** 工具描述（給 LLM 看） */
    val description: String

    /** 工具參數列表（給 LLM 看） */
    val parameters: List<String>

    /**
     * 執行工具
     *
     * @param params 參數鍵值對
     * @param ctx Agent 上下文（可讀取共享數據）
     * @return 工具執行結果（字符串，將作為觀察返回給 LLM）
     */
    suspend fun execute(params: Map<String, String>, ctx: AgentContext): String
}
