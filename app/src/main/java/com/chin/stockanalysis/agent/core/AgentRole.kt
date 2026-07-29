package com.chin.stockanalysis.agent.core

/**
 * Agent 權限枚舉 — 最小權限原則
 *
 * 每個 Agent 角色只擁有完成其職責所需的最小權限集合。
 * deny 永遠優先於 allow。
 */
enum class AgentPermission {
    // 數據讀取
    READ_MARKET_DATA,      // 行情、K線、財報
    READ_PORTFOLIO,        // 持倉、交易記錄
    READ_NEWS,             // 新聞 API
    READ_SECTOR_DATA,      // 板塊數據

    // LLM 調用
    CALL_LLM_STRONG,       // 強模型（高 max_tokens）
    CALL_LLM_FAST,         // 快速模型（低成本）

    // 寫入操作
    WRITE_TRADE_ORDER,     // 生成/修改交易訂單
    WRITE_WATCHLIST,       // 修改自選股
    PUBLISH_CROSSTAB,      // 跨 Tab 發布

    // 系統操作
    SPAWN_SUBAGENT,        // 派生子 Agent
    MODIFY_STRATEGY,       // 修改策略參數
    EXECUTE_TRADE          // 執行交易（永遠拒絕 Agent 直接執行）
}

/**
 * LLM 模型層級 — 成本分層策略
 *
 * 主 Agent 用強模型，Sub-Agent 用快速模型，純量化節點不調 LLM。
 */
enum class LlmTier {
    NONE,       // 不調用 LLM (Scout, Executor)
    FAST,       // 快速模型 (Guardian) — 對應 AiProviderSelector 的 STOCK_PICKING
    STRONG,     // 強模型 (Analyst) — 對應 PIPELINE_EXPERT
    ULTRA       // 最強模型 (Orchestrator) — 對應 CHAT_LEGACY (reasoning)
}

/**
 * Agent 角色定義
 *
 * @property name 角色標識（小寫，用於日誌和路由）
 * @property displayName 中文顯示名
 * @property emoji UI 圖標
 * @property permissions 允許的權限集合
 * @property denyPermissions 拒絕的權限集合（永遠優先）
 * @property llmModel 使用的 LLM 層級
 * @property maxConcurrent 最大並發數
 * @property timeoutMs 超時時間（毫秒）
 * @property maxSpawnDepth 最大派生深度（0=不能派生, 1=可派生一層）
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
    /** 檢查是否擁有指定權限（deny 優先） */
    fun hasPermission(permission: AgentPermission): Boolean {
        if (permission in denyPermissions) return false
        return permission in permissions
    }
}

/**
 * 預定義角色 — 五種核心 Agent 角色
 */
object AgentRoles {

    val ORCHESTRATOR = AgentRole(
        name = "orchestrator",
        displayName = "編排者",
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
        displayName = "偵察兵",
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
        displayName = "分析師",
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
        displayName = "風控官",
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
        displayName = "執行器",
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

    /** 按名稱查找角色 */
    fun byName(name: String): AgentRole? = when (name) {
        "orchestrator" -> ORCHESTRATOR
        "scout" -> SCOUT
        "analyst" -> ANALYST
        "guardian" -> GUARDIAN
        "executor" -> EXECUTOR
        else -> null
    }
}
