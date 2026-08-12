package com.chin.stockanalysis.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedList

/**
 * 量化任务调度器 — 串行化 pipeline 执行，防止并发过载
 *
 * 解决的问题：
 * 1. 4 个周期同时跑 → API 并发过高 → 东方财富断流
 * 2. UseCaseLoader.init() 共享状态被并发覆盖
 * 3. 后台 monitor 和 UI 触发同时跑 → 资源竞争
 *
 * 设计：
 * - 单例，全局唯一
 * - 内部单线程 Dispatcher，pipeline 排队串行执行
 * - 前台服务自动管理（第一个任务 start，最后一个任务 stop）
 * - 支持取消排队中的任务
 * - 180s 全局超时保护
 */
@OptIn(ExperimentalCoroutinesApi::class)
object QuantTaskScheduler {

    private const val TAG = "QuantScheduler"
    private const val GLOBAL_TIMEOUT_MS = 180_000L  // 3 分钟超时

    /** 单线程调度器 — 保证 pipeline 串行执行 */
    private val schedulerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    /** 运行计数 — 用于管理前台服务生命周期 */
    @Volatile private var runningCount = 0
    private val countMutex = Mutex()

    /** 任务队列（用于 UI 显示） */
    private val pendingTasks = LinkedList<String>()

    /** 当前正在执行的任务名 */
    @Volatile var currentTask: String = ""
        private set

    /**
     * 提交量化任务（串行执行）
     *
     * @param context 上下文
     * @param taskName 任务名（如 "超短线"、"中线"）
     * @param block 实际执行的 suspend 函数
     * @return 任务的 Deferred，可 await 获取结果
     */
    fun submit(
        context: Context,
        taskName: String,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        val ctx = context.applicationContext
        Log.i(TAG, "提交任务: $taskName (队列中 ${pendingTasks.size} 个)")

        val job = schedulerScope.launch {
            // 等待前台服务启动
            countMutex.withLock {
                runningCount++
                if (runningCount == 1) {
                    QuantForegroundService.start(ctx, taskName)
                }
            }

            try {
                currentTask = taskName
                pendingTasks.remove(taskName)
                Log.i(TAG, "开始执行: $taskName (剩余 ${pendingTasks.size} 个)")

                // 带超时的执行
                withTimeout(GLOBAL_TIMEOUT_MS) {
                    block()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "任务超时: $taskName (${GLOBAL_TIMEOUT_MS / 1000}s)")
            } catch (e: CancellationException) {
                Log.i(TAG, "任务取消: $taskName")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "任务异常: $taskName — ${e.message}", e)
            } finally {
                countMutex.withLock {
                    runningCount--
                    if (runningCount <= 0) {
                        runningCount = 0
                        currentTask = ""
                        QuantForegroundService.stop(ctx)
                    }
                }
            }
        }

        pendingTasks.add(taskName)
        return job
    }

    /**
     * 批量提交（如 4 个周期），按顺序排队
     */
    fun submitBatch(
        context: Context,
        tasks: List<Pair<String, suspend CoroutineScope.() -> Unit>>
    ): List<Job> {
        return tasks.map { (name, block) -> submit(context, name, block) }
    }

    /** 取消所有排队中和正在执行的任务 */
    fun cancelAll() {
        Log.i(TAG, "取消所有任务")
        pendingTasks.clear()
        schedulerScope.coroutineContext.cancelChildren()
    }

    /** 是否正在执行 */
    val isRunning: Boolean get() = runningCount > 0

    /** 排队任务数 */
    val pendingCount: Int get() = pendingTasks.size

    /**
     * 供 AppBackgroundRunner 使用的兼容接口
     * 检查是否可以执行（如果没有正在运行的 UI pipeline，则可以直接执行）
     */
    fun tryExecuteDirect(
        context: Context,
        taskName: String,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return submit(context, taskName, block)
    }
}
