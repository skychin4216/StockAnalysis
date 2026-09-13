package com.chin.stockanalysis.strategy.agent

import android.content.Context
import kotlinx.coroutines.*

/**
 * ## Sub-Agent 派生器
 *
 * 借鉴 OpenCode sessions_spawn 的非阻塞派生设计。
 * 每个 Sub-Agent 在独立 coroutine 中执行，完成后产生 AgentAnnounce。
 */
class SubAgentSpawner(
    private val appContext: Context
) {
    /**
     * 派生子 Agent（非阻塞）
     * @param role Agent 角色
     * @param task 任务执行体
     * @param session Session 记忆
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
 * Agent 任务 — 由 SubAgentSpawner 执行
 */
interface AgentTask {
    val id: String
    val description: String
    suspend fun execute(context: AgentContext)
}

/**
 * 简单任务实现 — 用 lambda 构造
 */
class SimpleAgentTask(
    override val id: String,
    override val description: String,
    private val block: suspend (AgentContext) -> Unit
) : AgentTask {
    override suspend fun execute(context: AgentContext) = block(context)
}
