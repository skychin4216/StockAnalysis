package com.chin.stockanalysis.agent.core

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
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
     * 角色级并发闸门（Phase E 并发调度优化）。
     *
     * `AgentRole.maxConcurrent` 此前只是个从未被读取的声明值：Sub-Agent 想派生多少就派生多少，
     * 同一角色堆几十个并发协程会同时压 LLM 配额与内存。这里按角色建信号量把它兑现——
     * 同角色同时最多 `maxConcurrent` 个在跑，其余在闸门外挂起（协程挂起不占线程）。
     */
    private val gates = ConcurrentHashMap<String, Semaphore>()

    private fun gateOf(role: AgentRole): Semaphore =
        gates.getOrPut(role.name) { Semaphore(role.maxConcurrent.coerceAtLeast(1)) }

    /**
     * 角色调度优先级（数值小的先占闸门许可）。
     * 用 `AgentRoles.*.name` 作键而非字面量，避免角色改名后静默失配。
     */
    private fun priorityOf(role: AgentRole): Int = when (role.name) {
        AgentRoles.ORCHESTRATOR.name -> 0
        AgentRoles.SCOUT.name -> 10
        AgentRoles.ANALYST.name -> 20
        AgentRoles.GUARDIAN.name -> 30
        AgentRoles.EXECUTOR.name -> 40
        else -> 50
    }

    /** 各角色闸门空闲许可（诊断用） */
    fun gateStatus(): String =
        gates.entries.joinToString(", ") { (name, sem) -> "$name=${sem.availablePermits}空闲" }
            .ifEmpty { "尚无角色派生" }

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

            // 并发闸门：同角色最多 role.maxConcurrent 个同时执行（Phase E）
            val gate = gateOf(role)
            if (gate.availablePermits <= 0) {
                Log.i(TAG, "⏳ ${role.displayName} [$taskId] 排队等许可（${role.name} 上限 ${role.maxConcurrent}）")
            }
            // 许可在超时计时**之前**获取：排队等待不算进角色自己的 timeoutMs
            gate.acquire()
            try {
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
            } finally {
                gate.release()
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
        // 按角色优先级依次发起：许可先到先得，先发起的角色先占坑。
        // （仅影响许可竞争顺序，返回顺序仍与传入的 tasks 一致）
        val deferreds = tasks.indices
            .sortedBy { priorityOf(tasks[it].first) }
            .map { it to spawn(tasks[it].first, tasks[it].second, session, scope) }
        return deferreds.sortedBy { it.first }.map { it.second.await() }
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
