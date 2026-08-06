package com.chin.stockanalysis.strategy.agent

import android.content.Context
import kotlinx.coroutines.*

/**
 * ## Sub-Agent 派生器
 *
 * 借鑒 OpenCode sessions_spawn 的非阻塞派生設計。
 * 每個 Sub-Agent 在獨立 coroutine 中執行，完成後產生 AgentAnnounce。
 */
class SubAgentSpawner(
    private val appContext: Context
) {
    /**
     * 派生子 Agent（非阻塞）
     * @param role Agent 角色
     * @param task 任務執行體
     * @param session Session 記憶
     * @return Deferred<AgentAnnounce>
     */
    fun spawn(
        role: AgentRole,
        task: AgentTask,
        session: AgentSessionMemory,
        scope: CoroutineScope
    ): Deferred<AgentAnnounce> {
        return scope.async(Dispatchers.Default) {
            val ctx = AgentContext(
                role = role,
                taskId = task.id,
                androidContext = appContext,
                sessionMemory = session
            )

            try {
                withTimeout(role.timeoutMs) {
                    task.execute(ctx)
                }
                ctx.buildAnnounce(durationMs = 0)
            } catch (e: TimeoutCancellationException) {
                AgentAnnounce(
                    taskId = task.id,
                    role = role.name,
                    status = AgentAnnounce.Status.TIMED_OUT,
                    errors = listOf(AgentError("TIMEOUT", "${role.timeoutMs}ms")),
                    durationMs = role.timeoutMs
                )
            } catch (e: Exception) {
                AgentAnnounce(
                    taskId = task.id,
                    role = role.name,
                    status = AgentAnnounce.Status.FAILED,
                    errors = listOf(AgentError("EXCEPTION", e.message ?: "unknown")),
                    durationMs = 0
                )
            }
        }
    }
}

/**
 * Agent 任務 — 由 SubAgentSpawner 執行
 */
interface AgentTask {
    val id: String
    val description: String
    suspend fun execute(context: AgentContext)
}

/**
 * 簡單任務實現 — 用 lambda 構造
 */
class SimpleAgentTask(
    override val id: String,
    override val description: String,
    private val block: suspend (AgentContext) -> Unit
) : AgentTask {
    override suspend fun execute(context: AgentContext) = block(context)
}
