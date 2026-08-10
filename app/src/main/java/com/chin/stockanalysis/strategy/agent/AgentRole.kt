package com.chin.stockanalysis.strategy.agent

/**
 * ## Agent 角色定义
 *
 * 预定义 5 种角色：Orchestrator / Scout / Analyst / Guardian / Executor
 */
data class AgentRole(
    val name: String,
    val displayName: String,
    val emoji: String,
    val permissions: Set<AgentPermission>,
    val denyPermissions: Set<AgentPermission> = emptySet(),
    val llmModel: LlmTier = LlmTier.NONE,
    val maxConcurrent: Int = 1,
    val timeoutMs: Long = 30_000,
    val maxSpawnDepth: Int = 0
) {
    fun hasPermission(perm: AgentPermission): Boolean {
        if (perm in denyPermissions) return false
        return perm in permissions
    }
}

object AgentRoles {

    val ORCHESTRATOR = AgentRole(
        name = "orchestrator",
        displayName = "编排者",
        emoji = "🎯",
        permissions = setOf(
            AgentPermission.READ_MARKET_DATA, AgentPermission.READ_PORTFOLIO,
            AgentPermission.CALL_LLM_STRONG, AgentPermission.CALL_LLM_FAST,
            AgentPermission.WRITE_WATCHLIST, AgentPermission.SPAWN_SUBAGENT
        ),
        llmModel = LlmTier.ULTRA,
        maxConcurrent = 1,
        timeoutMs = 120_000,
        maxSpawnDepth = 1
    )

    val SCOUT = AgentRole(
        name = "scout",
        displayName = "侦察兵",
        emoji = "🔍",
        permissions = setOf(
            AgentPermission.READ_MARKET_DATA, AgentPermission.READ_SECTOR_DATA,
            AgentPermission.READ_NEWS
        ),
        denyPermissions = setOf(
            AgentPermission.CALL_LLM_STRONG, AgentPermission.CALL_LLM_FAST,
            AgentPermission.SPAWN_SUBAGENT
        ),
        llmModel = LlmTier.NONE,
        maxConcurrent = 3,
        timeoutMs = 15_000,
        maxSpawnDepth = 0
    )

    val ANALYST = AgentRole(
        name = "analyst",
        displayName = "分析师",
        emoji = "📊",
        permissions = setOf(
            AgentPermission.READ_MARKET_DATA, AgentPermission.READ_PORTFOLIO,
            AgentPermission.READ_NEWS, AgentPermission.READ_SECTOR_DATA,
            AgentPermission.CALL_LLM_STRONG
        ),
        denyPermissions = setOf(AgentPermission.SPAWN_SUBAGENT, AgentPermission.WRITE_TRADE_ORDER),
        llmModel = LlmTier.STRONG,
        maxConcurrent = 5,
        timeoutMs = 60_000,
        maxSpawnDepth = 0
    )

    val GUARDIAN = AgentRole(
        name = "guardian",
        displayName = "风控官",
        emoji = "🛡️",
        permissions = setOf(AgentPermission.READ_PORTFOLIO, AgentPermission.CALL_LLM_FAST),
        denyPermissions = setOf(
            AgentPermission.READ_MARKET_DATA, AgentPermission.READ_NEWS,
            AgentPermission.READ_SECTOR_DATA, AgentPermission.SPAWN_SUBAGENT,
            AgentPermission.WRITE_TRADE_ORDER
        ),
        llmModel = LlmTier.FAST,
        maxConcurrent = 2,
        timeoutMs = 30_000,
        maxSpawnDepth = 0
    )

    val EXECUTOR = AgentRole(
        name = "executor",
        displayName = "执行器",
        emoji = "⚡",
        permissions = setOf(
            AgentPermission.READ_MARKET_DATA, AgentPermission.READ_PORTFOLIO,
            AgentPermission.WRITE_TRADE_ORDER, AgentPermission.WRITE_WATCHLIST,
            AgentPermission.PUBLISH_CROSSTAB
        ),
        denyPermissions = setOf(
            AgentPermission.CALL_LLM_STRONG, AgentPermission.CALL_LLM_FAST,
            AgentPermission.SPAWN_SUBAGENT
        ),
        llmModel = LlmTier.NONE,
        maxConcurrent = 1,
        timeoutMs = 10_000,
        maxSpawnDepth = 0
    )
}
