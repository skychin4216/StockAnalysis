package com.chin.stockanalysis.agent.core

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent 任務接口
 *
 * 每個 Sub-Agent 的具體邏輯實現此接口。
 * 在 [execute] 中完成工作，通過 context.recordToolResult() 記錄結果，
 * 最終由框架調用 context.buildAnnounce() 生成彙報。
 */
fun interface AgentTask {
    /**
     * 執行任務。
     *
     * @param context Agent 上下文（含權限、記憶、session 讀取）
     * @return 結構化結果 map（存入 announce.result）
     */
    suspend fun execute(context: AgentContext): Map<String, Any?>
}

/**
 * Sub-Agent 派生器
 *
 * 負責：
 * 1. 創建 AgentContext（注入角色、session）
 * 2. 在 coroutine 中執行 AgentTask
 * 3. 處理超時和異常
 * 4. 返回結構化 AgentAnnounce
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
     * 派生子 Agent 並返回 Deferred（非阻塞，可並行派生多個）
     *
     * @param role Agent 角色（決定權限、超時、LLM 層級）
     * @param task 具體任務邏輯
     * @param session 父會話記憶（Sub-Agent 可讀取指定 slot）
     * @param scope 協程作用域（用於級聯取消）
     * @param inputSlots 需要注入的 session slot 名稱列表（文檔用途，實際讀取由 task 自行決定）
     * @return Deferred<AgentAnnounce> 結構化彙報
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
                // 將 execute 返回的 result 也記錄到 context
                result.forEach { (k, v) -> context.recordToolResult(k, v) }

                val announce = context.buildAnnounce()
                Log.i(TAG, "✓ ${role.displayName} [$taskId] 完成: ${announce.status} (${announce.durationMs}ms)")
                announce

            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "⏱ ${role.displayName} [$taskId] 超時: ${role.timeoutMs}ms")
                AgentAnnounce.timedOut(taskId, role.name, role.timeoutMs)

            } catch (e: CancellationException) {
                Log.w(TAG, "⊘ ${role.displayName} [$taskId] 被取消")
                AgentAnnounce.failed(taskId, role.name,
                    listOf(AgentError("CANCELLED", "任務被取消")))

            } catch (e: Exception) {
                Log.e(TAG, "✗ ${role.displayName} [$taskId] 異常: ${e.message}", e)
                context.recordError("EXCEPTION", e.message ?: "unknown")
                AgentAnnounce.failed(taskId, role.name, context.getErrors())
            }
        }
    }

    /**
     * 並行派生多個 Sub-Agent，等待全部完成
     *
     * @param tasks 角色→任務的映射
     * @param session 共享會話
     * @param scope 協程作用域
     * @return 所有 announce 結果（按傳入順序）
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
 * Agent 會話管理器
 *
 * 管理所有活躍的分析會話，支持：
 * - 級聯停止（取消 Orchestrator 自動取消所有 Sub-Agent）
 * - Fragment 銷毀時清理
 * - 會話狀態查詢
 */
class AgentSessionManager {
    companion object {
        private const val TAG = "AgentSessionManager"

        /** 全局單例 */
        val instance: AgentSessionManager by lazy { AgentSessionManager() }
    }

    private val activeSessions = ConcurrentHashMap<String, Job>()
    private val sessionScopes = ConcurrentHashMap<String, CoroutineScope>()

    /**
     * 創建新的分析會話
     *
     * @param sessionId 會話 ID（通常用股票代碼+時間戳）
     * @return 會話的 CoroutineScope（用於 spawn Sub-Agent）
     */
    fun createSession(sessionId: String): CoroutineScope {
        // 如果已存在同名會話，先取消
        cancelSession(sessionId)

        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + job)
        activeSessions[sessionId] = job
        sessionScopes[sessionId] = scope

        Log.i(TAG, "創建會話: $sessionId")
        return scope
    }

    /**
     * 獲取會話的 CoroutineScope
     */
    fun getScope(sessionId: String): CoroutineScope? = sessionScopes[sessionId]

    /**
     * 取消指定會話的所有 Agent（級聯停止）
     *
     * SupervisorJob.cancel() 會自動級聯到所有子 coroutine。
     */
    fun cancelSession(sessionId: String) {
        val job = activeSessions.remove(sessionId)
        sessionScopes.remove(sessionId)
        if (job != null && job.isActive) {
            job.cancel(CancellationException("Session $sessionId cancelled"))
            Log.i(TAG, "取消會話: $sessionId")
        }
    }

    /**
     * 取消所有活躍會話（Fragment 銷毀時調用）
     */
    fun cancelAll() {
        val count = activeSessions.size
        activeSessions.values.forEach { it.cancel() }
        activeSessions.clear()
        sessionScopes.clear()
        if (count > 0) Log.i(TAG, "取消所有會話: $count 個")
    }

    /**
     * 查詢會話是否活躍
     */
    fun isActive(sessionId: String): Boolean =
        activeSessions[sessionId]?.isActive == true

    /**
     * 當前活躍會話數
     */
    fun activeCount(): Int = activeSessions.count { it.value.isActive }
}
