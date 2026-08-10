package com.chin.stockanalysis.agent.core

/**
 * Agent 权限枚举 — 最小权限原则
 *
 * 每个 Agent 角色只拥有完成其职责所需的最小权限集合。
 * deny 永远优先于 allow。
 */
enum class AgentPermission {
    // 数据读取
    READ_MARKET_DATA,      // 行情、K线、财报
    READ_PORTFOLIO,        // 持仓、交易记录
    READ_NEWS,             // 新闻 API
    READ_SECTOR_DATA,      // 板块数据

    // LLM 调用
    CALL_LLM_STRONG,       // 强模型（高 max_tokens）
    CALL_LLM_FAST,         // 快速模型（低成本）

    // 写入操作
    WRITE_TRADE_ORDER,     // 生成/修改交易订单
    WRITE_WATCHLIST,       // 修改自选股
    PUBLISH_CROSSTAB,      // 跨 Tab 发布

    // 系统操作
    SPAWN_SUBAGENT,        // 派生子 Agent
    MODIFY_STRATEGY,       // 修改策略参数
    EXECUTE_TRADE          // 执行交易（永远拒绝 Agent 直接执行）
}

/**
 * LLM 模型层级 — 成本分层策略
 *
 * 主 Agent 用强模型，Sub-Agent 用快速模型，纯量化节点不调 LLM。
 */
enum class LlmTier {
    NONE,       // 不调用 LLM (Scout, Executor)
    FAST,       // 快速模型 (Guardian) — 对应 AiProviderSelector 的 STOCK_PICKING
    STRONG,     // 强模型 (Analyst) — 对应 PIPELINE_EXPERT
    ULTRA       // 最强模型 (Orchestrator) — 对应 CHAT_LEGACY (reasoning)
}

/**
 * Agent 角色定义
 *
 * @property name 角色标识（小写，用于日志和路由）
 * @property displayName 中文显示名
 * @property emoji UI 图标
 * @property permissions 允许的权限集合
 * @property denyPermissions 拒绝的权限集合（永远优先）
 * @property llmModel 使用的 LLM 层级
 * @property maxConcurrent 最大并发数
 * @property timeoutMs 超时时间（毫秒）
 * @property maxSpawnDepth 最大派生深度（0=不能派生, 1=可派生一层）
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
    /** 检查是否拥有指定权限（deny 优先） */
    fun hasPermission(permission: AgentPermission): Boolean {
        if (permission in denyPermissions) return false
        return permission in permissions
    }
}

/**
 * 预定义角色 — 五种核心 Agent 角色
 */
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
        denyPermissions = setOf(AgentPermission.WRITE_TRADE_ORDER, AgentPermission.EXECUTE_TRADE),
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
            AgentPermission.SPAWN_SUBAGENT, AgentPermission.WRITE_TRADE_ORDER
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
        denyPermissions = setOf(
            AgentPermission.SPAWN_SUBAGENT, AgentPermission.WRITE_TRADE_ORDER,
            AgentPermission.EXECUTE_TRADE
        ),
        llmModel = LlmTier.STRONG,
        maxConcurrent = 5,
        timeoutMs = 60_000,
        maxSpawnDepth = 0
    )

    val GUARDIAN = AgentRole(
        name = "guardian",
        displayName = "风控官",
        emoji = "🛡️",
        permissions = setOf(
            AgentPermission.READ_PORTFOLIO, AgentPermission.CALL_LLM_FAST
        ),
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

    /** 按名称查找角色 */
    fun byName(name: String): AgentRole? = when (name) {
        "orchestrator" -> ORCHESTRATOR
        "scout" -> SCOUT
        "analyst" -> ANALYST
        "guardian" -> GUARDIAN
        "executor" -> EXECUTOR
        else -> null
    }
}
