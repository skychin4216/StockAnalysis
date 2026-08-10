package com.chin.stockanalysis.strategy.agent

/**
 * ## Agent 权限枚举
 *
 * 借鉴 OpenCode 的 allow/deny 工具权限模型。
 * 每个 Agent 角色拥有一组 permissions 和 denyPermissions，deny 永远优先。
 */
enum class AgentPermission {
    // 数据读取
    READ_MARKET_DATA,
    READ_PORTFOLIO,
    READ_NEWS,
    READ_SECTOR_DATA,

    // LLM 调用
    CALL_LLM_STRONG,
    CALL_LLM_FAST,

    // 写入操作
    WRITE_TRADE_ORDER,
    WRITE_WATCHLIST,
    PUBLISH_CROSSTAB,

    // 系统操作
    SPAWN_SUBAGENT,
    MODIFY_STRATEGY,
}

/**
 * ## LLM 模型层级
 */
enum class LlmTier(val label: String) {
    NONE("无 LLM"),
    FAST("快速模型"),
    STRONG("强模型"),
    ULTRA("最强模型")
}
