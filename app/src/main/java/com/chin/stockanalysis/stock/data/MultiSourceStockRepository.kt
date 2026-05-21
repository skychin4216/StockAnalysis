package com.chin.stockanalysis.stock.data

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * ## 多源股票数据仓储 - 并发请求、自动选源、健康检查
 *
 * 与 StockRepository 的区别：
 * - StockRepository 按优先级**顺序**降级（主源→备源1→备源2）
 * - MultiSourceStockRepository **并发**请求所有健康源，取**最快**返回
 *
 * ### 工作流程
 * ```
 * getRealtime(["sh600519"])
 *    │
 *    ├─ 1. 查缓存（智能 TTL）
 *    ├─ 2. 并发请求所有健康源
 *    │   ├─ async { SinaSource }
 *    │   ├─ async { TencentSource }
 *    │   └─ async { EastMoneySource }
 *    ├─ 3. 取第一个有数据的结果（按响应时间）
 *    ├─ 4. 写入缓存
 *    └─ 5. 返回
 * ```
 *
 * 健康检查每30秒在 ChatActivity 中触发。
 */
class MultiSourceStockRepository(
    private val sources: List<StockDataSource>,
    private val cache: SmartStockCache = SmartStockCache()
) {
    private val tag = "MultiSourceRepository"
    private val sourceHealth = mutableMapOf<StockDataSource, Boolean>()
    private val requestTimeouts = mutableMapOf<StockDataSource, Long>()

    init {
        for (source in sources) {
            sourceHealth[source] = true
            requestTimeouts[source] = 0L
        }
        Log.d(tag, "Init with ${sources.size} sources")
    }

    /**
     * 非挂起版本的 getRealtime - 与 StockService 兼容
     * 内部使用 runBlocking 包装协程
     */
    fun getRealtime(codes: List<String>): Map<String, StockRealtime> {
        return runBlocking {
            getRealtimeSuspend(codes)
        }
    }

    /**
     * 将已拉取的数据以**扩展 TTL** 写入缓存（供后台预取调度器使用）
     *
     * 正常的 getRealtime → cache.put()（智能 TTL，交易中仅 1s）
     * 预取场景 → cache.putWithExtendedTtl()（默认 10 分钟）
     * 这样当用户发问时，缓存已预热，立即秒返回无需实时请求。
     *
     * @param data 已获取的股票数据（通常来自 getRealtime 的结果）
     * @param ttlMs 缓存保留时长，默认 10 分钟
     */
    fun putToCache(data: Map<String, StockRealtime>, ttlMs: Long = SmartStockCache.PREFETCH_TTL_MS) {
        cache.putWithExtendedTtl(data, ttlMs)
    }

    /**
     * 获取实时数据 - 并发请求所有健康源，取最快返回（挂起版本）
     */
    suspend fun getRealtimeSuspend(codes: List<String>): Map<String, StockRealtime> =
        withContext(Dispatchers.IO) {
            if (codes.isEmpty()) return@withContext emptyMap()

            // 1. 查缓存
            val cached = cache.get(codes)
            val uncached = codes - cached.keys

            if (uncached.isEmpty()) {
                Log.d(tag, "All from cache")
                return@withContext cached
            }

            // 2. 并发请求所有健康源
            val healthySources = sources.filter { sourceHealth[it] != false }
            if (healthySources.isEmpty()) {
                Log.w(tag, "No healthy sources")
                return@withContext cached
            }

            Log.d(tag, "Concurrent request from ${healthySources.size} sources")

            val tasks = healthySources.map { source ->
                async {
                    try {
                        val startTime = System.currentTimeMillis()
                        val result = source.fetchRealtime(uncached)
                        val elapsed = System.currentTimeMillis() - startTime

                        // 更新平均响应时间
                        val prevTimeout = requestTimeouts[source] ?: 0L
                        requestTimeouts[source] = if (prevTimeout == 0L) elapsed else (prevTimeout + elapsed) / 2

                        Log.d(tag, "✓ ${source::class.simpleName}: ${result.size}/${uncached.size} (${elapsed}ms)")

                        // 恢复健康状态
                        if (sourceHealth[source] != true) sourceHealth[source] = true

                        result to source
                    } catch (e: Exception) {
                        Log.w(tag, "✗ ${source::class.simpleName}: ${e.message}")
                        sourceHealth[source] = false
                        emptyMap<String, StockRealtime>() to source
                    }
                }
            }

            // 3. 等待所有完成，取最快有数据的
            val results = tasks.awaitAll()
            val freshData = results
                .filter { (data, _) -> data.isNotEmpty() }
                .sortedBy { (_, source) -> requestTimeouts[source] ?: Long.MAX_VALUE }
                .firstOrNull()?.first ?: emptyMap()

            // 4. 写入缓存
            if (freshData.isNotEmpty()) {
                cache.put(freshData)
            }

            Log.d(tag, "Complete: ${(cached + freshData).size}/${codes.size}")
            cached + freshData
        }

    /**
     * 健康检查 - 检测所有数据源是否可用
     */
    suspend fun healthCheck() = withContext(Dispatchers.IO) {
        Log.d(tag, "Health check...")
        sources.map { source ->
            async {
                try {
                    val isAvailable = source.isAvailable()
                    sourceHealth[source] = isAvailable
                    Log.d(tag, "  ${source::class.simpleName}: ${if (isAvailable) "✓" else "✗"}")
                } catch (e: Exception) {
                    sourceHealth[source] = false
                }
            }
        }.awaitAll()
    }

    fun clearCache() = cache.clear()

    /**
     * 获取诊断信息
     */
    fun getDiagnostics(): String = buildString {
        appendLine("═══════════════════════════════════════")
        appendLine("Repository Diagnostics")
        appendLine("═══════════════════════════════════════")
        appendLine("Sources: ${sourceHealth.count { it.value }}/${sources.size} healthy")
        for ((source, isHealthy) in sourceHealth) {
            val status = if (isHealthy) "✓" else "✗"
            val timeout = requestTimeouts[source] ?: 0L
            appendLine("$status ${source::class.simpleName} (avg=${timeout}ms, p=${source.priority()})")
        }
        appendLine("═══════════════════════════════════════")
        appendLine(cache.getStats())
        appendLine("═══════════════════════════════════════")
    }
}