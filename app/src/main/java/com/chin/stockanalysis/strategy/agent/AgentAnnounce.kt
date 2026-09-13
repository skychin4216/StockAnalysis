package com.chin.stockanalysis.strategy.agent

/**
 * ## Agent 结构化汇报
 *
 * 借鉴 OpenCode Sub-Agent 的 Announce 机制。
 * 子 Agent 完成后以结构化格式向 Orchestrator 汇报。
 */
data class AgentAnnounce(
    val taskId: String,
    val role: String,
    val status: Status,
    val result: Map<String, Any> = emptyMap(),
    val errors: List<AgentError> = emptyList(),
    val tokenUsed: Int = 0,
    val durationMs: Long = 0
) {
    enum class Status { COMPLETED, DEGRADED, FAILED, TIMED_OUT }

    val isSuccess: Boolean get() = status == Status.COMPLETED || status == Status.DEGRADED
}

data class AgentError(
    val type: String,
    val message: String,
    val fallback: String? = null
)
