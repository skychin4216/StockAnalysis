package com.chin.stockanalysis.agent.core

import android.util.Log
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import kotlinx.coroutines.*

/**
 * 降級策略枚舉
 */
enum class FallbackStrategy {
    SKIP,            // 跳過，使用 null 輸出（非關鍵節點）
    CACHE,           // 使用上次成功的緩存結果
    DEFAULT_VALUE,   // 使用預設值
    RETRY_ONCE,      // 重試一次
    DEGRADE_LLM,     // 降級到更小/更快的模型
    ALGORITHM_BACKUP // 使用純算法後備（不調 LLM）
}

/**
 * 節點容錯配置
 *
 * @property isCritical 是否關鍵（失敗導致 Pipeline 失敗）
 * @property fallback 降級策略
 * @property retryCount 重試次數
 * @property algorithmBackup 算法後備函數（可選）
 */
data class NodeResilience(
    val isCritical: Boolean = false,
    val fallback: FallbackStrategy = FallbackStrategy.SKIP,
    val retryCount: Int = 0,
    val algorithmBackup: (suspend (PipelineContext) -> Any?)? = null
)

/**
 * AgentNode — 將 Agent 包裝為 DAG 節點
 *
 * 使 LLM 推理與量化計算在同一張 DAG 圖中流動。
 * Agent 的分析結果通過此包裝器流入 DAG 的下游節點。
 *
 * 使用方式：
 * ```kotlin
 * // 在 NodeRegistry 中註冊
 * register("agent_analyst") { ctx, config ->
 *     AgentNode(
 *         role = AgentRoles.ANALYST,
 *         taskFactory = { context -> AnalystTask(context.androidContext, stockCode) },
 *         outputKey = "ai_analysis"
 *     )
 * }
 * ```
 *
 * @property role Agent 角色（決定權限、超時）
 * @property taskFactory 任務工廠（從 PipelineContext 創建 AgentTask）
 * @property outputKey 輸出存入 PipelineContext 的 key
 * @property resilience 容錯配置
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
        context.log(nodeId, "▶ $nodeName 開始 (timeout=${role.timeoutMs}ms)")

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

                // 處理結果
                return when {
                    announce.isSuccess || announce.isDegraded -> {
                        if (announce.isDegraded) {
                            context.log(nodeId, "⚠️ $nodeName 降級: ${announce.errors}")
                        }
                        context.log(nodeId, "✓ $nodeName 完成 (${announce.durationMs}ms)")
                        context.setStageOutput(outputKey, announce.result)
                        announce.result
                    }
                    else -> {
                        lastError = RuntimeException("Agent failed: ${announce.errors}")
                        if (attempt < maxAttempts) {
                            context.log(nodeId, "⟳ $nodeName 重試 ($attempt/$maxAttempts)")
                            continue
                        }
                        null
                    }
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts) {
                    context.log(nodeId, "⟳ $nodeName 異常重試 ($attempt/$maxAttempts): ${e.message}")
                    continue
                }
            }
        }

        scope.cancel()

        // 所有重試失敗 → 執行降級策略
        context.log(nodeId, "✗ $nodeName 失敗: ${lastError?.message}")
        return applyFallback(context, lastError)
    }

    /**
     * 應用降級策略
     */
    private suspend fun applyFallback(context: PipelineContext, error: Exception?): Any? {
        return when (resilience.fallback) {
            FallbackStrategy.SKIP -> {
                context.log(nodeId, "⊘ $nodeName 降級: SKIP（跳過）")
                null
            }
            FallbackStrategy.DEFAULT_VALUE -> {
                context.log(nodeId, "⊘ $nodeName 降級: DEFAULT_VALUE")
                emptyMap<String, Any>()
            }
            FallbackStrategy.ALGORITHM_BACKUP -> {
                context.log(nodeId, "⊘ $nodeName 降級: ALGORITHM_BACKUP")
                resilience.algorithmBackup?.invoke(context)
            }
            else -> {
                context.log(nodeId, "⊘ $nodeName 降級: ${resilience.fallback}")
                null
            }
        }
    }
}

/**
 * LLM 響應緩存 — 避免重複調用
 *
 * 相同股票同一天內的相同分析類型，直接返回緩存。
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
        /** 全局共享緩存實例 */
        val global = AnalysisCache(ttlMinutes = 30, maxSize = 200)
    }
}
