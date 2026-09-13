package com.chin.stockanalysis.agent.core

import android.util.Log
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import kotlinx.coroutines.*

/**
 * 降级策略枚举
 */
enum class FallbackStrategy {
    SKIP,            // 跳过，使用 null 输出（非关键节点）
    CACHE,           // 使用上次成功的缓存结果
    DEFAULT_VALUE,   // 使用预设值
    RETRY_ONCE,      // 重试一次
    DEGRADE_LLM,     // 降级到更小/更快的模型
    ALGORITHM_BACKUP // 使用纯算法后备（不调 LLM）
}

/**
 * 节点容错配置
 *
 * @property isCritical 是否关键（失败导致 Pipeline 失败）
 * @property fallback 降级策略
 * @property retryCount 重试次数
 * @property algorithmBackup 算法后备函数（可选）
 */
data class NodeResilience(
    val isCritical: Boolean = false,
    val fallback: FallbackStrategy = FallbackStrategy.SKIP,
    val retryCount: Int = 0,
    val algorithmBackup: (suspend (PipelineContext) -> Any?)? = null
)

/**
 * AgentNode — 将 Agent 包装为 DAG 节点
 *
 * 使 LLM 推理与量化计算在同一张 DAG 图中流动。
 * Agent 的分析结果通过此包装器流入 DAG 的下游节点。
 *
 * 使用方式：
 * ```kotlin
 * // 在 NodeRegistry 中注册
 * register("agent_analyst") { ctx, config ->
 *     AgentNode(
 *         role = AgentRoles.ANALYST,
 *         taskFactory = { context -> AnalystTask(context.androidContext, stockCode) },
 *         outputKey = "ai_analysis"
 *     )
 * }
 * ```
 *
 * @property role Agent 角色（决定权限、超时）
 * @property taskFactory 任务工厂（从 PipelineContext 创建 AgentTask）
 * @property outputKey 输出存入 PipelineContext 的 key
 * @property resilience 容错配置
 */
class AgentNode(
    private val role: AgentRole,
    private val taskFactory: (PipelineContext) -> AgentTask,
    private val outputKey: String = "agent_${role.name}",
    private val resilience: NodeResilience = NodeResilience()
) : PipelineNode<Any, Any?> {

    companion object {
        private const val TAG = "AgentNode"
    }

    override val nodeId: String = "agent_${role.name}"
    override val nodeName: String = "${role.emoji} ${role.displayName}"
    override val nodeType: NodeType = NodeType.AI_PREDICTION

    override suspend fun execute(context: PipelineContext, input: Any): Any? {
        context.log(nodeId, "▶ $nodeName 开始 (timeout=${role.timeoutMs}ms)")

        val session = AgentSession("dag_${context.tradeDate}_${role.name}")
        val spawner = SubAgentSpawner()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        var lastError: Exception? = null
        val maxAttempts = 1 + resilience.retryCount

        for (attempt in 1..maxAttempts) {
            try {
                val task = taskFactory(context)
                val announce = spawner.spawn(
                    role = role,
                    task = task,
                    session = session,
                    scope = scope
                ).await()

                scope.cancel()

                // 处理结果
                return when {
                    announce.isSuccess || announce.isDegraded -> {
                        if (announce.isDegraded) {
                            context.log(nodeId, "⚠️ $nodeName 降级: ${announce.errors}")
                        }
                        context.log(nodeId, "✓ $nodeName 完成 (${announce.durationMs}ms)")
                        context.setStageOutput(outputKey, announce.result)
                        announce.result
                    }
                    else -> {
                        lastError = RuntimeException("Agent failed: ${announce.errors}")
                        if (attempt < maxAttempts) {
                            context.log(nodeId, "⟳ $nodeName 重试 ($attempt/$maxAttempts)")
                            continue
                        }
                        null
                    }
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts) {
                    context.log(nodeId, "⟳ $nodeName 异常重试 ($attempt/$maxAttempts): ${e.message}")
                    continue
                }
            }
        }

        scope.cancel()

        // 所有重试失败 → 执行降级策略
        context.log(nodeId, "✗ $nodeName 失败: ${lastError?.message}")
        return applyFallback(context, lastError)
    }

    /**
     * 应用降级策略
     */
    private suspend fun applyFallback(context: PipelineContext, error: Exception?): Any? {
        return when (resilience.fallback) {
            FallbackStrategy.SKIP -> {
                context.log(nodeId, "⊘ $nodeName 降级: SKIP（跳过）")
                null
            }
            FallbackStrategy.DEFAULT_VALUE -> {
                context.log(nodeId, "⊘ $nodeName 降级: DEFAULT_VALUE")
                emptyMap<String, Any>()
            }
            FallbackStrategy.ALGORITHM_BACKUP -> {
                context.log(nodeId, "⊘ $nodeName 降级: ALGORITHM_BACKUP")
                resilience.algorithmBackup?.invoke(context)
            }
            else -> {
                context.log(nodeId, "⊘ $nodeName 降级: ${resilience.fallback}")
                null
            }
        }
    }
}

/**
 * LLM 响应缓存 — 避免重复调用
 *
 * 相同股票同一天内的相同分析类型，直接返回缓存。
 * key: stockCode_analysisType_tradeDate_tier
 */
class AnalysisCache(
    private val ttlMinutes: Int = 30,
    private val maxSize: Int = 100
) {
    data class CacheEntry(
        val response: Map<String, Any?>,
        val timestamp: Long,
        val modelUsed: String = ""
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(key: String): CacheEntry? {
        val entry = cache[key] ?: return null
        val ageMinutes = (System.currentTimeMillis() - entry.timestamp) / 60_000
        if (ageMinutes > ttlMinutes) {
            cache.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: String, response: Map<String, Any?>, model: String = "") {
        cache[key] = CacheEntry(response, System.currentTimeMillis(), model)
    }

    @Synchronized
    fun invalidate(key: String) { cache.remove(key) }

    @Synchronized
    fun clear() { cache.clear() }

    fun makeKey(stockCode: String, analysisType: String, tradeDate: String, tier: LlmTier): String {
        return "${stockCode}_${analysisType}_${tradeDate}_${tier.name}"
    }

    companion object {
        /** 全局共享缓存实例 */
        val global = AnalysisCache(ttlMinutes = 30, maxSize = 200)
    }
}
