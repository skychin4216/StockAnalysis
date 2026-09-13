package com.chin.stockanalysis.strategy.agent

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * ## Agent 上下文（三层记忆）
 *
 * Layer 1: Global Memory — App 进程级，所有 Agent 共享只读
 * Layer 2: Session Memory — 一次分析任务，Orchestrator 独占读写
 * Layer 3: Agent Memory — Sub-Agent 执行期间，完成后销毁
 */
class AgentContext(
    val role: AgentRole,
    val taskId: String,
    val androidContext: Context,
    val sessionMemory: AgentSessionMemory? = null
) {
    // Layer 3: Agent 短期记忆
    private val toolResults = ConcurrentHashMap<String, Any>()
    private val logs = mutableListOf<String>()
    private val errors = mutableListOf<AgentError>()

    fun recordToolResult(toolName: String, result: Any) {
        toolResults[toolName] = result
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getToolResult(toolName: String): T? = toolResults[toolName] as? T

    fun log(msg: String) {
        logs.add(msg)
    }

    fun addError(error: AgentError) {
        errors.add(error)
    }

    // 从 Session Memory 读取（需权限）
    @Suppress("UNCHECKED_CAST")
    fun <T> readSession(slot: String): T? {
        if (sessionMemory == null) return null
        if (!role.hasPermission(AgentPermission.READ_PORTFOLIO) &&
            !role.hasPermission(AgentPermission.READ_MARKET_DATA)) return null
        return sessionMemory.getSlot(slot) as? T
    }

    fun buildAnnounce(durationMs: Long): AgentAnnounce {
        return AgentAnnounce(
            taskId = taskId,
            role = role.name,
            status = if (errors.isEmpty()) AgentAnnounce.Status.COMPLETED
                     else AgentAnnounce.Status.DEGRADED,
            result = toolResults,
            errors = errors.toList(),
            tokenUsed = logs.size,
            durationMs = durationMs
        )
    }
}

/**
 * Session 级记忆 — Orchestrator 管理，Sub-Agent 只读指定 slot
 */
class AgentSessionMemory {
    private val slots = ConcurrentHashMap<String, Any>()

    fun putSlot(name: String, value: Any) {
        slots[name] = value
    }

    fun getSlot(name: String): Any? = slots[name]

    fun getAllSlots(): Map<String, Any> = slots.toMap()
}
