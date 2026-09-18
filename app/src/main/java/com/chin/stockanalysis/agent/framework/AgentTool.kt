package com.chin.stockanalysis.agent.framework

/**
 * ## Agent 工具接口
 *
 * 每个工具是一个可复用的功能单元，Agent 可以通过 LLM 调用。
 * 工具应该是纯函数（无副作用）或明确记录副作用。
 */
interface AgentTool {
    /** 工具唯一标识 */
    val name: String

    /** 工具描述（给 LLM 看） */
    val description: String

    /** 工具参数列表（给 LLM 看） */
    val parameters: List<String>

    /**
     * 执行工具
     *
     * @param params 参数键值对
     * @param ctx Agent 上下文（可读取共享数据）
     * @return 工具执行结果（字符串，将作为观察返回给 LLM）
     */
    suspend fun execute(params: Map<String, String>, ctx: AgentContext): String
}
