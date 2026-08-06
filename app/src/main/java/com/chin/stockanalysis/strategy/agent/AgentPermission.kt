package com.chin.stockanalysis.strategy.agent

/**
 * ## Agent 權限枚舉
 *
 * 借鑒 OpenCode 的 allow/deny 工具權限模型。
 * 每個 Agent 角色擁有一組 permissions 和 denyPermissions，deny 永遠優先。
 */
enum class AgentPermission {
    // 數據讀取
    READ_MARKET_DATA,
    READ_PORTFOLIO,
    READ_NEWS,
    READ_SECTOR_DATA,

    // LLM 調用
    CALL_LLM_STRONG,
    CALL_LLM_FAST,

    // 寫入操作
    WRITE_TRADE_ORDER,
    WRITE_WATCHLIST,
    PUBLISH_CROSSTAB,

    // 系統操作
    SPAWN_SUBAGENT,
    MODIFY_STRATEGY,
}

/**
 * ## LLM 模型層級
 */
enum class LlmTier(val label: String) {
    NONE("無 LLM"),
    FAST("快速模型"),
    STRONG("強模型"),
    ULTRA("最強模型")
}
