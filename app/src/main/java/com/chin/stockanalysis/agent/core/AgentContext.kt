package com.chin.stockanalysis.agent.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 錯誤記錄
 */
data class AgentError(
    val type: String,       // TIMEOUT / EXCEPTION / LLM_ERROR / PARSE_ERROR
    val message: String,
    val fallback: String? = null  // 降級方案標識（如 "ATR_ALGORITHM"）
)

/**
 * Agent 結構化彙報（Announce 機制）
 *
 * Sub-Agent 完成後以結構化格式向 Orchestrator 彙報。
 * Orchestrator 用自己的風格重新組織（不是原始轉發）。
 *
 * @property taskId 任務 ID
 * @property role 角色名（scout/analyst/guardian/executor）
 * @property status 完成狀態：completed / degraded / failed / timed_out
 * @property result 結構化結果（key-value，由具體 Agent 定義）
 * @property errors 錯誤列表（degraded/failed 時非空）
 * @property tokenUsed LLM token 消耗（0 表示未調用 LLM）
 * @property durationMs 執行耗時
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

    /** 安全讀取結果中的值 */
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
                listOf(AgentError("TIMEOUT", "超過 ${timeoutMs}ms 限制")), durationMs = timeoutMs)
    }
}

/**
 * Agent 會話記憶（Layer 2）
 *
 * 生命週期：一次分析任務。Orchestrator 獨佔寫入，Sub-Agent 只讀指定 slot。
 */
class AgentSession(val sessionId: String) {
    private val slots = ConcurrentHashMap<String, Any?>()

    /** Orchestrator 寫入 slot */
    fun setSlot(name: String, value: Any?) { slots[name] = value }

    /** 讀取指定 slot（Sub-Agent 只讀） */
    @Suppress("UNCHECKED_CAST")
    fun <T> getSlot(name: String): T? = slots[name] as? T

    /** 所有 slot 名稱（供 Orchestrator 查看） */
    fun slotNames(): Set<String> = slots.keys.toSet()
}

/**
 * Agent 上下文 — 三層記憶架構
 *
 * Layer 1: Global Memory — 全局只讀（市場環境、用戶偏好、策略配置）
 * Layer 2: Session Memory — Orchestrator 獨佔寫入，Sub-Agent 只讀指定 slot
 * Layer 3: Agent Memory — 獨立短期記憶，完成後銷毀
 *
 * @property role 當前 Agent 的角色定義
 * @property taskId 任務唯一標識
 * @property parentSession 父會話（null 表示頂層 Orchestrator）
 */
class AgentContext(
    val role: AgentRole,
    val taskId: String,
    val parentSession: AgentSession? = null
) {
    // ── Layer 3: Agent 短期記憶（獨立，不共享） ──
    private val toolResults = ConcurrentHashMap<String, Any?>()
    private val reasoningLog = mutableListOf<String>()
    private val errors = mutableListOf<AgentError>()
    private val startTime = System.currentTimeMillis()

    // ── 權限檢查 ──
    fun hasPermission(permission: AgentPermission): Boolean = role.hasPermission(permission)

    /** 斷言權限，無權限時拋異常 */
    fun requirePermission(permission: AgentPermission) {
        if (!hasPermission(permission)) {
            throw SecurityException("${role.displayName}(${role.name}) 無權限: $permission")
        }
    }

    // ── Layer 2: Session 記憶讀取（只讀，需權限） ──
    @Suppress("UNCHECKED_CAST")
    fun <T> readFromSession(slotName: String): T? {
        if (parentSession == null) return null
        return parentSession.getSlot(slotName)
    }

    // ── Layer 3: Agent 短期記憶操作 ──
    fun recordToolResult(toolName: String, result: Any?) {
        toolResults[toolName] = result
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

    // ── Announce 構建 ──
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
            tokenUsed = 0,  // 由 LLM 調用方填充
            durationMs = duration
        )
    }
}
