package com.chin.stockanalysis.agent.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 错误记录
 */
data class AgentError(
    val type: String,       // TIMEOUT / EXCEPTION / LLM_ERROR / PARSE_ERROR
    val message: String,
    val fallback: String? = null  // 降级方案标识（如 "ATR_ALGORITHM"）
)

/**
 * Agent 结构化汇报（Announce 机制）
 *
 * Sub-Agent 完成后以结构化格式向 Orchestrator 汇报。
 * Orchestrator 用自己的风格重新组织（不是原始转发）。
 *
 * @property taskId 任务 ID
 * @property role 角色名（scout/analyst/guardian/executor）
 * @property status 完成状态：completed / degraded / failed / timed_out
 * @property result 结构化结果（key-value，由具体 Agent 定义）
 * @property errors 错误列表（degraded/failed 时非空）
 * @property tokenUsed LLM token 消耗（0 表示未调用 LLM）
 * @property durationMs 执行耗时
 */
data class AgentAnnounce(
    val taskId: String,
    val role: String,
    val status: String,
    val result: Map<String, Any?>,
    val errors: List<AgentError> = emptyList(),
    val tokenUsed: Int = 0,
    val durationMs: Long = 0
) {
    val isSuccess: Boolean get() = status == "completed"
    val isDegraded: Boolean get() = status == "degraded"
    val isFailed: Boolean get() = status == "failed" || status == "timed_out"

    /** 安全读取结果中的值 */
    @Suppress("UNCHECKED_CAST")
    fun <T> getResult(key: String): T? = result[key] as? T

    companion object {
        fun success(taskId: String, role: String, result: Map<String, Any?>, durationMs: Long = 0) =
            AgentAnnounce(taskId, role, "completed", result, durationMs = durationMs)

        fun degraded(taskId: String, role: String, result: Map<String, Any?>, errors: List<AgentError>, durationMs: Long = 0) =
            AgentAnnounce(taskId, role, "degraded", result, errors, durationMs = durationMs)

        fun failed(taskId: String, role: String, errors: List<AgentError>) =
            AgentAnnounce(taskId, role, "failed", emptyMap(), errors)

        fun timedOut(taskId: String, role: String, timeoutMs: Long) =
            AgentAnnounce(taskId, role, "timed_out", emptyMap(),
                listOf(AgentError("TIMEOUT", "超过 ${timeoutMs}ms 限制")), durationMs = timeoutMs)
    }
}

/**
 * Agent 会话记忆（Layer 2）
 *
 * 生命周期：一次分析任务。Orchestrator 独占写入，Sub-Agent 只读指定 slot。
 */
class AgentSession(val sessionId: String) {
    private val slots = ConcurrentHashMap<String, Any?>()

    /** Orchestrator 写入 slot */
    fun setSlot(name: String, value: Any?) { slots[name] = value }

    /** 读取指定 slot（Sub-Agent 只读） */
    @Suppress("UNCHECKED_CAST")
    fun <T> getSlot(name: String): T? = slots[name] as? T

    /** 所有 slot 名称（供 Orchestrator 查看） */
    fun slotNames(): Set<String> = slots.keys.toSet()
}

/**
 * Agent 上下文 — 三层记忆架构
 *
 * Layer 1: Global Memory — 全局只读（市场环境、用户偏好、策略配置）
 * Layer 2: Session Memory — Orchestrator 独占写入，Sub-Agent 只读指定 slot
 * Layer 3: Agent Memory — 独立短期记忆，完成后销毁
 *
 * @property role 当前 Agent 的角色定义
 * @property taskId 任务唯一标识
 * @property parentSession 父会话（null 表示顶层 Orchestrator）
 */
class AgentContext(
    val role: AgentRole,
    val taskId: String,
    val parentSession: AgentSession? = null
) {
    // ── Layer 3: Agent 短期记忆（独立，不共享） ──
    private val toolResults = ConcurrentHashMap<String, Any?>()
    private val reasoningLog = mutableListOf<String>()
    private val errors = mutableListOf<AgentError>()
    private val startTime = System.currentTimeMillis()

    // ── 权限检查 ──
    fun hasPermission(permission: AgentPermission): Boolean = role.hasPermission(permission)

    /** 断言权限，无权限时抛异常 */
    fun requirePermission(permission: AgentPermission) {
        if (!hasPermission(permission)) {
            throw SecurityException("${role.displayName}(${role.name}) 无权限: $permission")
        }
    }

    // ── Layer 2: Session 记忆读取（只读，需权限） ──
    @Suppress("UNCHECKED_CAST")
    fun <T> readFromSession(slotName: String): T? {
        if (parentSession == null) return null
        return parentSession.getSlot(slotName)
    }

    // ── Layer 3: Agent 短期记忆操作 ──
    fun recordToolResult(toolName: String, result: Any?) {
        // ConcurrentHashMap 不接受 null value，用 "N/A" 代替
        toolResults[toolName] = result ?: "N/A"
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getToolResult(toolName: String): T? = toolResults[toolName] as? T

    fun log(message: String) {
        synchronized(reasoningLog) { reasoningLog.add(message) }
    }

    fun recordError(type: String, message: String, fallback: String? = null) {
        synchronized(errors) { errors.add(AgentError(type, message, fallback)) }
    }

    fun getErrors(): List<AgentError> = synchronized(errors) { errors.toList() }

    // ── Announce 构建 ──
    fun buildAnnounce(): AgentAnnounce {
        val duration = System.currentTimeMillis() - startTime
        val status = when {
            errors.isEmpty() -> "completed"
            toolResults.isNotEmpty() -> "degraded"
            else -> "failed"
        }
        return AgentAnnounce(
            taskId = taskId,
            role = role.name,
            status = status,
            result = toolResults.toMap(),
            errors = errors.toList(),
            tokenUsed = 0,  // 由 LLM 调用方填充
            durationMs = duration
        )
    }
}
