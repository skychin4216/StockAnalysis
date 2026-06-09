package com.chin.stockanalysis.agent

/**
 * ## AI 智能体 (Agent)
 *
 * 每个智能体是一个独立的选股分析角色，拥有：
 * - 名称、描述
 * - 独立的 system prompt（输入指令）
 * - 触发关键词
 * - 独立对话上下文
 *
 * 智能体对话中 AI 选出的股票保存到 [AgentPick] 列表，
 * 供策略和模拟交易优先使用。
 */
data class Agent(
    val id: String,
    val name: String,
    val icon: String = "🤖",
    /** 设定描述 — 智能体的角色说明 */
    val description: String = "",
    /** 输入指令描述 — quickPrompt：用戶自由編寫的簡單命令觸發詞 */
    val quickPrompt: String = "",
    /** 全自动执行规则描述 — systemPrompt：AI 根据設定描述自动生成并填充 */
    val systemPrompt: String = "",
    /** 触发关键词（备用，用于 AI 对话中的关键词匹配） */
    val triggerKeywords: List<String> = emptyList(),
    val enabled: Boolean = true,
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val source: AgentSource = AgentSource.USER_CREATED
)

enum class AgentSource { USER_CREATED, FROM_SKILL, AUTO_GENERATED }

/**
 * 智能体选股结果 — 对应 StockDataCenter.SkillPickEntry
 */
data class AgentPick(
    val rank: Int,
    val stockCode: String,
    val stockName: String,
    val reason: String,
    val confidence: Float = 0.5f,
    val sourceAgentId: String,
    val createdAt: Long = System.currentTimeMillis()
)