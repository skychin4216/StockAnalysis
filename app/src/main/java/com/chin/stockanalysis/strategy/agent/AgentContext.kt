package com.chin.stockanalysis.strategy.agent

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * ## Agent 上下文（三層記憶）
 *
 * Layer 1: Global Memory — App 進程級，所有 Agent 共享只讀
 * Layer 2: Session Memory — 一次分析任務，Orchestrator 獨佔讀寫
 * Layer 3: Agent Memory — Sub-Agent 執行期間，完成後銷毀
 */
class AgentContext(
    val role: AgentRole,
    val taskId: String,
    val androidContext: Context,
    val sessionMemory: AgentSessionMemory? = null
) {
    // Layer 3: Agent 短期記憶
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

    // 從 Session Memory 讀取（需權限）
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
 * Session 級記憶 — Orchestrator 管理，Sub-Agent 只讀指定 slot
 */
class AgentSessionMemory {
    private val slots = ConcurrentHashMap<String, Any>()

    fun putSlot(name: String, value: Any) {
        slots[name] = value
    }

    fun getSlot(name: String): Any? = slots[name]

    fun getAllSlots(): Map<String, Any> = slots.toMap()
}
