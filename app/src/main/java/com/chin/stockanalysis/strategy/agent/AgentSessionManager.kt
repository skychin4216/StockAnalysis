package com.chin.stockanalysis.strategy.agent

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * ## Agent 会话管理器
 *
 * 管理所有活跃的分析会话，支持级联停止。
 */
class AgentSessionManager {
    private val activeSessions = ConcurrentHashMap<String, Job>()

    fun registerSession(sessionId: String, job: Job) {
        activeSessions[sessionId] = job
    }

    fun cancelSession(sessionId: String) {
        activeSessions[sessionId]?.cancel()
        activeSessions.remove(sessionId)
    }

    fun cancelAll() {
        activeSessions.values.forEach { it.cancel() }
        activeSessions.clear()
    }

    fun activeCount(): Int = activeSessions.size
}
