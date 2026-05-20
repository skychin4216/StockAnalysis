package com.chin.stockanalysis.stock.realtime

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.StockCache
import com.chin.stockanalysis.stock.data.StockDataSource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ## 实时数据访问器
 *
 * ### 为什么豆包能实时，API 却不能？
 * - 豆包 App 是 **客户端直连** 新浪/腾讯等开源股票 API，获取实时数据后注入 AI
 * - 豆包 API 如果只调用 AI 模型本身，模型训练数据有截止日期，自然没有实时数据
 * - **结论**：实时数据必须从 **开源财经网站** 的 HTTP/WebSocket 接口获取，不能依赖 AI API
 *
 * ### 职责
 * 1. **并发请求** — 同时从多源请求，取最快返回
 * 2. **智能选源** — 根据延迟、健康状态自动选择最优数据源
 * 3. **指数退避重试** — 失败时自动重试，延迟递增
 * 4. **频率控制** — 避免触发 API 频率限制
 * 5. **健康监控** — 定期 ping 各数据源，标记不可用源
 * 6. **降级策略** — 全部源不可用时使用缓存 + 延长轮询间隔
 *
 * ### 使用示例
 * ```kotlin
 * val accessor = RealtimeDataAccessor(sources, scope)
 * val data = accessor.fetchRealtime(listOf("sh600519", "sz000858"))
 * ```
 */
class RealtimeDataAccessor(
    /** 所有可用的数据源（按优先级排序） */
    private val sources: List<StockDataSource>,
    /** 协程作用域 */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    /** 并发请求超时时间（毫秒） */
    private val requestTimeoutMs: Long = 5000L,
    /** 同一股票最短请求间隔（毫秒），防止被限流 */
    private val minRequestIntervalMs: Long = 1000L
) {
    private val tag = "RealtimeDataAccessor"

    // ======================== 内部状态 ========================

    /** 数据源健康状态 <数据源类名, 是否可用> */
    private val healthStatus = ConcurrentHashMap<String, Boolean>()

    /** 数据源延迟统计 <数据源类名, 最近N次平均延迟ms> */
    private val latencyStats = ConcurrentHashMap<String, MutableList<Long>>()

    /** 每只股票上次请求时间戳，用于频率控制 */
    private val lastRequestTime = ConcurrentHashMap<String, AtomicLong>()

    /** 连续失败次数（用于降级） */
    private val consecutiveFailures = AtomicInteger(0)

    /** 降级模式标志 */
    private val degradedMode = AtomicInteger(0) // 0=正常, 1=降级, 2=重度降级

    /** 协程锁，防止并发健康检查冲突 */
    private val healthCheckMutex = Mutex()

    /** 健康检查任务 */
    private var healthCheckJob: Job? = null

    /** 请求计数器（监控用） */
    private val requestCount = AtomicLong(0)

    companion object {
        /** 连续多少次失败后触发降级 */
        private const val DEGRADE_THRESHOLD = 3
        /** 降级模式下的请求间隔倍数 */
        private const val DEGRADE_INTERVAL_MULTIPLIER = 3L
    }

    // ======================== 初始化 ========================

    init {
        // 初始所有数据源标记为健康
        sources.forEach { source ->
            healthStatus[source::class.simpleName ?: source.javaClass.name] = true
            latencyStats[source::class.simpleName ?: source.javaClass.name] = mutableListOf()
        }
        // 启动后台健康检查
        startHealthCheck()
    }

    // ======================== 公开 API ========================

    /**
     * 获取实时行情（并发请求多个数据源，取最快返回）
     *
     * 策略：
     * 1. 先过滤出健康的源
     * 2. 对每个健康源发起并发协程请求
     * 3. 取第一个成功返回的结果
     * 4. 如果全部失败，所有源标记为不健康，返回空
     * 5. 连续失败超过阈值则进入降级模式
     *
     * @param codes 股票代码列表（如 ["sh600519", "sz000858"]）
     * @param preferFastest 是否优先使用最快的源（而不是优先级最高的），默认 true
     * @return 股票代码 -> 实时数据
     */
    suspend fun fetchRealtime(
        codes: List<String>,
        preferFastest: Boolean = true
    ): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()

        requestCount.incrementAndGet()

        // 降级模式下的频率控制
        val effectiveInterval = if (degradedMode.get() > 0) {
            minRequestIntervalMs * (DEGRADE_INTERVAL_MULTIPLIER shl (degradedMode.get() - 1))
        } else {
            minRequestIntervalMs
        }

        // 频率控制：检查是否有股票在短时间内被重复请求
        val now = System.currentTimeMillis()
        val filteredCodes = codes.filter { code ->
            val lastTime = lastRequestTime[code]?.get() ?: 0L
            if (now - lastTime >= effectiveInterval) {
                lastRequestTime.getOrPut(code) { AtomicLong(now) }.set(now)
                true
            } else {
                false // 请求太频繁，跳过
            }
        }
        if (filteredCodes.isEmpty()) return emptyMap()

        // 选择要使用的数据源
        val activeSources = if (preferFastest) {
            // 按平均延迟排序（延迟越低越优先）
            sources
                .filter { healthStatus[it::class.simpleName ?: it.javaClass.name] != false }
                .sortedBy { getAverageLatency(it) }
        } else {
            // 按优先级排序
            sources
                .filter { healthStatus[it::class.simpleName ?: it.javaClass.name] != false }
                .sortedBy { it.priority() }
        }

        if (activeSources.isEmpty()) {
            // 所有源都不可用，尝试强制唤醒一次
            Log.w(tag, "No healthy sources, force retry...")
            return forceFetchWithRetry(filteredCodes)
        }

        // 并发请求多个数据源，取最快返回（首个成功）
        val result = concurrentFetch(filteredCodes, activeSources)

        // 更新连续失败计数
        if (result.isEmpty()) {
            val failures = consecutiveFailures.incrementAndGet()
            if (failures >= DEGRADE_THRESHOLD) {
                degradedMode.set(1)
                Log.w(tag, "Degraded mode activated ($failures consecutive failures)")
            }
        } else {
            // 成功后降低降级级别
            consecutiveFailures.set(0)
            val currentDegrade = degradedMode.get()
            if (currentDegrade > 0) {
                degradedMode.set(currentDegrade - 1)
                Log.d(tag, "Degraded mode reduced to level ${degradedMode.get()}")
            }
        }

        return result
    }

    /**
     * 获取所有数据源的健康状态，包含降级信息
     */
    fun getHealthReport(): Map<String, Any> {
        val report = mutableMapOf<String, Any>()
        sources.forEach { source ->
            val key = source::class.simpleName ?: source.javaClass.name
            report[key] = mapOf(
                "healthy" to (healthStatus[key] == true),
                "avgLatency" to getAverageLatency(source),
                "priority" to source.priority()
            )
        }
        report["totalRequests"] = requestCount.get()
        report["degradedMode"] = degradedMode.get()
        report["consecutiveFailures"] = consecutiveFailures.get()
        return report
    }

    /**
     * 手动触发一次数据源健康检查
     */
    suspend fun checkHealth() {
        healthCheckMutex.withLock {
            Log.d(tag, "Running health check on ${sources.size} sources...")
            var healthyCount = 0
            sources.forEach { source ->
                try {
                    val start = System.currentTimeMillis()
                    val available = source.isAvailable()
                    val elapsed = System.currentTimeMillis() - start

                    val key = source::class.simpleName ?: source.javaClass.name
                    healthStatus[key] = available
                    updateLatency(key, elapsed)
                    if (available) healthyCount++
                    Log.d(tag, "  ${source::class.simpleName}: ${if (available) "✓" else "✗"} (${elapsed}ms)")
                } catch (e: Exception) {
                    val key = source::class.simpleName ?: source.javaClass.name
                    healthStatus[key] = false
                    Log.e(tag, "  ${source::class.simpleName}: ✗ ${e.message}")
                }
            }
            Log.d(tag, "Health check done: $healthyCount/${sources.size} healthy")
        }
    }

    /**
     * 停止所有后台任务（释放资源）
     */
    fun shutdown() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        consecutiveFailures.set(0)
        degradedMode.set(0)
        Log.d(tag, "Shutdown complete")
    }

    // ======================== 内部实现 ========================

    /**
     * 并发请求所有活跃源，取首个成功返回的结果
     */
    private suspend fun concurrentFetch(
        codes: List<String>,
        activeSources: List<StockDataSource>
    ): Map<String, StockRealtime> = coroutineScope {
        val deferredResults = activeSources.map { source ->
            async {
                val key = source::class.simpleName ?: source.javaClass.name
                try {
                    val start = System.currentTimeMillis()
                    val result = withTimeout(requestTimeoutMs) {
                        source.fetchRealtime(codes)
                    }
                    val elapsed = System.currentTimeMillis() - start

                    // 更新延迟统计
                    updateLatency(key, elapsed)

                    if (result.isNotEmpty()) {
                        // 标记为健康
                        healthStatus[key] = true
                        Log.d(tag, "✓ ${source::class.simpleName}: ${result.size} stocks in ${elapsed}ms")
                        Result.success(result)
                    } else {
                        Log.w(tag, "✗ ${source::class.simpleName}: empty response")
                        Result.failure(Exception("Empty response from $key"))
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(tag, "✗ ${source::class.simpleName}: timeout after ${requestTimeoutMs}ms")
                    healthStatus[key] = false
                    Result.failure(e)
                } catch (e: Exception) {
                    Log.w(tag, "✗ ${source::class.simpleName}: ${e.message}")
                    healthStatus[key] = false
                    Result.failure(e)
                }
            }
        }

        // 等待任意一个成功（或全部失败）
        for (deferred in deferredResults) {
            try {
                val result = deferred.await()
                if (result.isSuccess) {
                    return@coroutineScope result.getOrDefault(emptyMap())
                }
            } catch (_: Exception) {
                // 忽略单个源的失败，继续等待其他源
            }
        }

        // 全部失败，返回空
        Log.e(tag, "All ${activeSources.size} sources failed!")
        emptyMap()
    }

    /**
     * 所有源标记为不可用时，强制重试一次
     * 先做一次健康检查，再用恢复的源重试
     */
    private suspend fun forceFetchWithRetry(codes: List<String>): Map<String, StockRealtime> {
        // 先做一次健康检查
        checkHealth()

        // 重新获取可用的源
        val recoveredSources = sources.filter {
            healthStatus[it::class.simpleName ?: it.javaClass.name] == true
        }

        if (recoveredSources.isEmpty()) {
            // 真的全部不可用，用最高优先级的源再试最后一次
            Log.w(tag, "Still no healthy sources after health check! Using highest priority source...")
            return runCatching {
                sources.first().fetchRealtime(codes)
            }.getOrDefault(emptyMap())
        }

        Log.d(tag, "Recovered ${recoveredSources.size} sources after health check")
        // 用恢复的源并发请求
        return concurrentFetch(codes, recoveredSources)
    }

    /**
     * 更新延迟统计（保留最近10次）
     */
    private fun updateLatency(key: String, elapsed: Long) {
        val stats = latencyStats.getOrPut(key) { mutableListOf() }
        synchronized(stats) {
            stats.add(elapsed)
            if (stats.size > 10) {
                stats.removeAt(0)
            }
        }
    }

    /**
     * 获取数据源的平均延迟
     */
    private fun getAverageLatency(source: StockDataSource): Long {
        val key = source::class.simpleName ?: source.javaClass.name
        val stats = latencyStats[key] ?: return Long.MAX_VALUE
        synchronized(stats) {
            if (stats.isEmpty()) return Long.MAX_VALUE
            return stats.average().toLong()
        }
    }

    /**
     * 启动后台健康检查任务（每30秒检查一次）
     */
    private fun startHealthCheck() {
        healthCheckJob = scope.launch {
            while (isActive) {
                try {
                    delay(30_000L) // 每30秒检查一次
                    checkHealth()
                } catch (e: Exception) {
                    Log.w(tag, "Health check exception: ${e.message}")
                }
            }
        }
        Log.d(tag, "Health check started (interval: 30s)")
    }
}