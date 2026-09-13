package com.chin.stockanalysis.agent.core

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent 任务接口
 *
 * 每个 Sub-Agent 的具体逻辑实现此接口。
 * 在 [execute] 中完成工作，通过 context.recordToolResult() 记录结果，
 * 最终由框架调用 context.buildAnnounce() 生成汇报。
 */
fun interface AgentTask {
    /**
     * 执行任务。
     *
     * @param context Agent 上下文（含权限、记忆、session 读取）
     * @return 结构化结果 map（存入 announce.result）
     */
    suspend fun execute(context: AgentContext): Map<String, Any?>
}

/**
 * Sub-Agent 派生器
 *
 * 负责：
 * 1. 创建 AgentContext（注入角色、session）
 * 2. 在 coroutine 中执行 AgentTask
 * 3. 处理超时和异常
 * 4. 返回结构化 AgentAnnounce
 *
 * 使用方式：
 * ```kotlin
 * val spawner = SubAgentSpawner()
 * val scoutDeferred = spawner.spawn(AgentRoles.SCOUT, scoutTask, session)
 * val analystDeferred = spawner.spawn(AgentRoles.ANALYST, analystTask, session)
 * val scoutResult = scoutDeferred.await()
 * val analystResult = analystDeferred.await()
 * ```
 */
class SubAgentSpawner(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    companion object {
        private const val TAG = "SubAgentSpawner"
    }

    private val taskCounter = AtomicInteger(0)

    /**
     * 派生子 Agent 并返回 Deferred（非阻塞，可并行派生多个）
     *
     * @param role Agent 角色（决定权限、超时、LLM 层级）
     * @param task 具体任务逻辑
     * @param session 父会话记忆（Sub-Agent 可读取指定 slot）
     * @param scope 协程作用域（用于级联取消）
     * @param inputSlots 需要注入的 session slot 名称列表（文档用途，实际读取由 task 自行决定）
     * @return Deferred<AgentAnnounce> 结构化汇报
     */
    fun spawn(
        role: AgentRole,
        task: AgentTask,
        session: AgentSession,
        scope: CoroutineScope,
        inputSlots: List<String> = emptyList()
    ): Deferred<AgentAnnounce> {
        val taskId = "${role.name}_${taskCounter.incrementAndGet()}"

        return scope.async(dispatcher) {
            val context = AgentContext(
                role = role,
                taskId = taskId,
                parentSession = session
            )

            Log.i(TAG, "▶ spawn ${role.emoji} ${role.displayName} [$taskId] timeout=${role.timeoutMs}ms")

            try {
                val result = withTimeout(role.timeoutMs) {
                    task.execute(context)
                }
                // 将 execute 返回的 result 也记录到 context
                result.forEach { (k, v) -> context.recordToolResult(k, v) }

                val announce = context.buildAnnounce()
                Log.i(TAG, "✓ ${role.displayName} [$taskId] 完成: ${announce.status} (${announce.durationMs}ms)")
                announce

            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "⏱ ${role.displayName} [$taskId] 超时: ${role.timeoutMs}ms")
                AgentAnnounce.timedOut(taskId, role.name, role.timeoutMs)

            } catch (e: CancellationException) {
                Log.w(TAG, "⊘ ${role.displayName} [$taskId] 被取消")
                AgentAnnounce.failed(taskId, role.name,
                    listOf(AgentError("CANCELLED", "任务被取消")))

            } catch (e: Exception) {
                Log.e(TAG, "✗ ${role.displayName} [$taskId] 异常: ${e.message}", e)
                context.recordError("EXCEPTION", e.message ?: "unknown")
                AgentAnnounce.failed(taskId, role.name, context.getErrors())
            }
        }
    }

    /**
     * 并行派生多个 Sub-Agent，等待全部完成
     *
     * @param tasks 角色→任务的映射
     * @param session 共享会话
     * @param scope 协程作用域
     * @return 所有 announce 结果（按传入顺序）
     */
    suspend fun spawnAll(
        tasks: List<Pair<AgentRole, AgentTask>>,
        session: AgentSession,
        scope: CoroutineScope
    ): List<AgentAnnounce> {
        val deferreds = tasks.map { (role, task) ->
            spawn(role, task, session, scope)
        }
        return deferreds.awaitAll()
    }
}

/**
 * Agent 会话管理器
 *
 * 管理所有活跃的分析会话，支持：
 * - 级联停止（取消 Orchestrator 自动取消所有 Sub-Agent）
 * - Fragment 销毁时清理
 * - 会话状态查询
 */
class AgentSessionManager {
    companion object {
        private const val TAG = "AgentSessionManager"

        /** 全局单例 */
        val instance: AgentSessionManager by lazy { AgentSessionManager() }
    }

    private val activeSessions = ConcurrentHashMap<String, Job>()
    private val sessionScopes = ConcurrentHashMap<String, CoroutineScope>()

    /**
     * 创建新的分析会话
     *
     * @param sessionId 会话 ID（通常用股票代码+时间戳）
     * @return 会话的 CoroutineScope（用于 spawn Sub-Agent）
     */
    fun createSession(sessionId: String): CoroutineScope {
        // 如果已存在同名会话，先取消
        cancelSession(sessionId)

        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + job)
        activeSessions[sessionId] = job
        sessionScopes[sessionId] = scope

        Log.i(TAG, "创建会话: $sessionId")
        return scope
    }

    /**
     * 获取会话的 CoroutineScope
     */
    fun getScope(sessionId: String): CoroutineScope? = sessionScopes[sessionId]

    /**
     * 取消指定会话的所有 Agent（级联停止）
     *
     * SupervisorJob.cancel() 会自动级联到所有子 coroutine。
     */
    fun cancelSession(sessionId: String) {
        val job = activeSessions.remove(sessionId)
        sessionScopes.remove(sessionId)
        if (job != null && job.isActive) {
            job.cancel(CancellationException("Session $sessionId cancelled"))
            Log.i(TAG, "取消会话: $sessionId")
        }
    }

    /**
     * 取消所有活跃会话（Fragment 销毁时调用）
     */
    fun cancelAll() {
        val count = activeSessions.size
        activeSessions.values.forEach { it.cancel() }
        activeSessions.clear()
        sessionScopes.clear()
        if (count > 0) Log.i(TAG, "取消所有会话: $count 个")
    }

    /**
     * 查询会话是否活跃
     */
    fun isActive(sessionId: String): Boolean =
        activeSessions[sessionId]?.isActive == true

    /**
     * 当前活跃会话数
     */
    fun activeCount(): Int = activeSessions.count { it.value.isActive }
}
