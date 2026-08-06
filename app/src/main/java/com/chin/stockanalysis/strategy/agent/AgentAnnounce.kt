package com.chin.stockanalysis.strategy.agent

/**
 * ## Agent 結構化彙報
 *
 * 借鑒 OpenCode Sub-Agent 的 Announce 機制。
 * 子 Agent 完成後以結構化格式向 Orchestrator 彙報。
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
