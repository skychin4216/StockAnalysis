package com.chin.stockanalysis.strategy.topology.core

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

// ============================================================================
// DagPipeline — 高通 Camera 风格 DAG 拓扑执行引擎
// ============================================================================

/**
 * ## DAG 边定义（高通 Links 等价）
 *
 * 源节点 → 目标节点的有向边，带端口标识。
 * 对应高通 XML 中的 `<Link><SourcePortId>...<TargetPortId>`。
 *
 * @property sourceNodeId 源节点 ID
 * @property sourcePortId 源端口 ID（高通兼容，预留）
 * @property targetNodeId 目标节点 ID
 * @property targetPortId 目标端口 ID（高通兼容，预留）
 */
data class DagEdge(
    val sourceNodeId: String,
    val sourcePortId: Int = 0,
    val targetNodeId: String,
    val targetPortId: Int = 0
)

/**
 * ## DAG 节点定义（高通 NodeList 等价）
 *
 * 封装一个 [PipelineNode] 及其 ID。
 * 对应高通 XML 中的 `<Node><nodeName>...<NodeId>`。
 */
data class DagNode(
    val nodeId: String,
    val nodeName: String,
    val node: PipelineNode<*, *>,
    /** 所属 Pipeline 分组 ID（用于 UI 著色，null = 未分组） */
    val pipelineGroup: String = ""
) {
    /** 该节点的所有入边的源节点 ID 集合 */
    var dependencies: Set<String> = emptySet()

    /** 该节点的所有出边的目标节点 ID 集合 */
    var dependents: Set<String> = emptySet()

    /** 是否为关键节点（失败会导致 Pipeline 失败） */
    val isCritical: Boolean get() = nodeId !in NON_CRITICAL_NODE_IDS &&
        !nodeId.startsWith("strategy_")

    companion object {
        /** 非关键节点：失败不影响 Pipeline 最终结果（仅记录警告） */
        val NON_CRITICAL_NODE_IDS = setOf(
            "n_bg",       // 后台暂停/恢复（辅助）
            "n_fit",      // 拟合计算（耗时，不影响建仓）
            "n_swap",     // 腾龙换鸟（辅助优化）
            "n_guard",    // 持仓风控（辅助，失败不阻断买入）
            "n_heat",     // 热度计算（辅助数据）
            "n_candle",   // K线形态侦测（辅助提醒）
            "n_news_str", // 新闻力度（辅助评分）
            "n_rot_pen",  // 轮动惩罚（辅助评分）
            "n_crossday", // 跨日聚合（辅助数据）
            "n_multihot"  // 多周期热门（辅助数据）
        )

        /**
         * 辅助节点：仅提供执行顺序保证（如先卖后买），其输出为空/失败时
         * 下游节点自动回退到辅助节点的上游输出，永远不阻断下游执行。
         */
        val AUXILIARY_NODES = setOf("n_swap")
    }
}

/**
 * ## DagPipeline — 高通 Camera 风格的 DAG 拓扑执行引擎
 *
 * 与 [Pipeline]（Stage → LinkList → Link）不同，DagPipeline 采用：
 * - **扁平 DAG 结构**：NodeList + Links，无嵌套
 * - **拓扑排序自动推导并行度**：Kahn 算法分层，同层节点自动并行
 * - **无自环 Link 问题**：源节点若无入边（根节点），自动用 `Unit` 作为初始输入
 *
 * ### 高通 XML 对应关系
 * ```
 * <NodeList>        → nodes: List<DagNode>
 * <Links>           → edges: List<DagEdge>
 * <PipelineName>    → name
 * <UsecaseName>      → (由 UseCase 层管理)
 * ```
 *
 * ### 执行流程
 * 1. 构建邻接表 + 入度表
 * 2. Kahn 拓扑排序，分层（同层可并行）
 * 3. 逐层执行：同层节点用 coroutineScope + async 并行
 * 4. 每个节点从 context.stageOutputs 读取依赖的输出，执行后存入 context.stageOutputs
 *
 * ### 使用示例
 * ```kotlin
 * val dag = DagPipeline(
 *     name = "MidTermPipeline",
 *     nodes = listOf(
 *         DagNode("n_ctx", "市场上下文", MarketContextNode()),
 *         DagNode("n_pool", "股票池", StockPoolNode()),
 *     ),
 *     edges = listOf(
 *         DagEdge("n_ctx", 0, "n_pool", 0)
 *     )
 * )
 * val result = dag.execute(context)
 * ```
 */
class DagPipeline(
    val id: String = "",
    val name: String,
    val description: String = "",
    val nodes: List<DagNode>,
    val edges: List<DagEdge>,
    val pipelineGroups: List<PipelineGroup> = emptyList()
) {
    companion object {
        private const val TAG = "DagPipeline"

        /**
         * 主流节点：输出 0 代表「无股票可处理」，后续节点无意义 → 提前终止。
         * 使用 internal nodeId（node.nodeId）。
         * 不含：swap_weak（0=无需换鸟，merge 仍需跑）、adaptive_params/market_context（非股票输出）、
         *       sector_boost/ai_predict（enrichment，不直接决定有无候选）。
         */
        val FLOW_CRITICAL_NODES = setOf(
            "stock_pool", "candidate_pool", "signal_merge",
            // strict_selection 已移除：粘合严选输出 0 时不应终止 Pipeline，
            // 候选原样传递给下游趋势策略（双通道架构：通道A均值回归 + 通道B趋势跟踪）
            "smart_money_filter", "news_guard"
            // generate_orders 不在其中：0 订单是正常结果（如非交易时段/无候选通过），不算失败
        )
    }

    // 邻接表
    private val nodeMap: Map<String, DagNode> = nodes.associateBy { it.nodeId }
    private val adjacency: Map<String, List<DagEdge>> = edges.groupBy { it.sourceNodeId }
    private val inDegree: Map<String, Int> = run {
        val degrees = nodes.associate { it.nodeId to 0 }.toMutableMap()
        for (edge in edges) {
            if (edge.targetNodeId in degrees) {
                degrees[edge.targetNodeId] = (degrees[edge.targetNodeId] ?: 0) + 1
            }
        }
        degrees
    }

    private val contextMutex = Mutex()

    /**
     * 执行 DAG Pipeline。
     *
     * 使用 Kahn 算法进行拓扑排序，分层并行执行。
     * 每个节点的输出存入 `context.stageOutputs[nodeId]`，
     * 下游节点从 `context.stageOutputs[sourceNodeId]` 读取上游输出。
     */
    suspend fun execute(context: PipelineContext): DagPipelineResult {
        val nodeResults = mutableMapOf<String, DagNodeResult>()
        val errors = mutableMapOf<String, String>()
        var allSuccess = true
        var finalOutput: Any? = null

        Log.i(TAG, "▶ DAG Pipeline 开始执行: $name (${nodes.size} nodes, ${edges.size} edges)")

        val totalElapsed = measureTimeMillis {
            // 1. Kahn 拓扑排序 → 分层
            val layers = topologicalSort()

            if (layers.isEmpty()) {
                errors["topology"] = "拓扑排序失败：可能存在环"
                allSuccess = false
            } else {
                Log.i(TAG, "  拓扑分层: ${layers.size} 层" +
                    layers.mapIndexed { i, layer -> "\n    Layer $i: [${layer.joinToString { it.nodeId }}]" }
                        .joinToString())

                // 2. 逐层执行
                for ((layerIndex, layer) in layers.withIndex()) {
                    val layerElapsed = measureTimeMillis {
                        if (layer.size == 1) {
                            // 单节点串行
                            val dagNode = layer[0]
                            val result = executeNode(dagNode, context)
                            nodeResults[dagNode.nodeId] = result
                            if (!result.success && dagNode.isCritical) allSuccess = false
                            if (result.output != null) finalOutput = result.output
                            if (result.error != null && dagNode.isCritical) errors[dagNode.nodeId] = result.error
                        } else {
                            // 多节点并行
                            Log.i(TAG, "  Layer $layerIndex: 并行执行 ${layer.size} 个节点")
                            coroutineScope {
                                val deferred = layer.map { dagNode ->
                                    async {
                                        dagNode to executeNode(dagNode, context)
                                    }
                                }
                                for (d in deferred) {
                                    val (dagNode, result) = d.await()
                                    nodeResults[dagNode.nodeId] = result
                                    if (!result.success && dagNode.isCritical) allSuccess = false
                                    if (result.output != null) finalOutput = result.output
                                    if (result.error != null && dagNode.isCritical) errors[dagNode.nodeId] = result.error
                                }
                            }
                        }
                    }
                    Log.i(TAG, "  Layer $layerIndex 完成: ${layerElapsed}ms")

                    // ── Fail-fast：主流节点输出 0 → 无股票可处理，提前终止 ──
                    val emptyCritical = layer.firstOrNull { dagNode ->
                        dagNode.node.nodeId in FLOW_CRITICAL_NODES &&
                            nodeResults[dagNode.nodeId]?.let { r ->
                                r.success && (r.stockFlow?.outputCount ?: -1) == 0
                            } == true
                    }
                    if (emptyCritical != null) {
                        val nodeName = emptyCritical.nodeName
                        Log.w(TAG, "⛔ $nodeName 输出 0，无股票可处理，提前终止 Pipeline")
                        context.log(emptyCritical.nodeId,
                            "⛔ $nodeName 输出 0 → Pipeline 提前终止，后续节点不执行")
                        errors[emptyCritical.nodeId] = "输出为0，提前终止"
                        allSuccess = false
                        break
                    }
                }
            }
        }

        // 仅合并关键节点的 context 错误
        // 注意：节点内部用 internal nodeId（如 "heat_score"）记录错误，
        // 而 NON_CRITICAL_NODE_IDS 使用 XML nodeId（如 "n_heat"），两者都需过滤
        val nonCriticalInternalIds = nodes.filter { !it.isCritical }.map { it.node.nodeId }.toSet()
        context.errors.filterKeys { key ->
            !DagNode.NON_CRITICAL_NODE_IDS.contains(key) &&
                !nonCriticalInternalIds.contains(key) &&
                !key.startsWith("strategy_")
        }.let { errors.putAll(it) }

        Log.i(TAG, "◀ DAG Pipeline 完成: ${if (allSuccess) "成功" else "失败"}, 耗时 ${totalElapsed}ms" +
            (if (errors.isNotEmpty()) ", 错误: ${errors.keys}" else ""))

        // 收集所有节点的股票流动记录
        val stockFlowMap = nodeResults.mapNotNull { (id, result) ->
            result.stockFlow?.let { id to it }
        }.toMap()

        return DagPipelineResult(
            pipelineName = name,
            success = allSuccess && errors.isEmpty(),
            nodeResults = nodeResults,
            totalElapsedMs = totalElapsed,
            finalOutput = finalOutput,
            errors = errors,
            stockFlowLogs = stockFlowMap,
            pipelineGroups = pipelineGroups.associateBy { it.id }
        )
    }

    // ════════════════════════════════════════════════════
    //  拓扑排序（Kahn 算法）
    // ════════════════════════════════════════════════════

    /**
     * Kahn 拓扑排序，返回分层列表。
     * 每层内的节点无依赖关系，可以并行执行。
     *
     * @return 分层列表，外层为层级，内层为该层的节点
     */
    private fun topologicalSort(): List<List<DagNode>> {
        val remainingInDegree = inDegree.toMutableMap()
        val remaining = nodes.map { it.nodeId }.toMutableSet()
        val layers = mutableListOf<List<DagNode>>()

        while (remaining.isNotEmpty()) {
            // 找出所有入度为 0 的节点
            val ready = remaining.filter { (remainingInDegree[it] ?: 0) == 0 }
            if (ready.isEmpty()) {
                // 存在环
                Log.e(TAG, "拓扑排序失败: 剩余 ${remaining.size} 个节点存在环依赖")
                return layers.takeIf { remaining.isEmpty() } ?: emptyList()
            }

            val layerNodes = ready.mapNotNull { nodeMap[it] }
            layers.add(layerNodes)

            // 移除已处理的节点，更新入度
            for (nodeId in ready) {
                remaining.remove(nodeId)
                val outEdges = adjacency[nodeId] ?: emptyList()
                for (edge in outEdges) {
                    if (edge.targetNodeId in remainingInDegree) {
                        remainingInDegree[edge.targetNodeId] =
                            (remainingInDegree[edge.targetNodeId] ?: 0) - 1
                    }
                }
            }
        }

        return layers
    }

    // ════════════════════════════════════════════════════
    //  节点执行
    // ════════════════════════════════════════════════════

    /**
     * 执行单个 DAG 节点。
     *
     * 1. 从 context.stageOutputs 收集所有依赖节点的输出
     * 2. 如果有单一依赖，用其输出作为本节点的输入
     * 3. 如果有多个依赖，用第一个依赖的输出作为输入（其余通过 context 传递）
     * 4. 如果无依赖（根节点），用 `Unit` 作为输入
     * 5. 执行后将输出存入 context.stageOutputs[nodeId]
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun executeNode(dagNode: DagNode, context: PipelineContext): DagNodeResult {
        val nodeId = dagNode.nodeId
        val node = dagNode.node

        // 收集依赖输入
        val depEdges = edges.filter { it.targetNodeId == nodeId }
        var input: Any? = when {
            depEdges.isEmpty() -> Unit  // 根节点：无依赖，输入 Unit
            depEdges.size == 1 -> {
                // 单一依赖：直接用上游输出
                val sourceId = depEdges[0].sourceNodeId
                context.stageOutputs[sourceId]
            }
            node.nodeType == NodeType.AGGREGATION -> {
                // 聚合节点：收集所有上游输出为 List
                // 若某上游失败/无输出（null），记录告警，不静默丢弃——保证 D 的"综合分析"不被残缺数据欺骗
                val outputs = depEdges.map { it.sourceNodeId to context.stageOutputs[it.sourceNodeId] }
                val nullSources = outputs.filter { it.second == null }.map { it.first }
                if (nullSources.isNotEmpty()) {
                    context.log(nodeId, "⚠ ${dagNode.nodeName} 上游输出为空（${nullSources.joinToString()}），聚合输入不完整")
                }
                outputs.map { it.second }.filterNotNull()
            }
            else -> {
                // 非聚合多依赖：取第一个"有效"上游输出作为主输入，其余通过 context.stageOutputs 兜底读取。
                // 避免"首个依赖失败输出 null 时整节点被跳过、其余上游白算"的浪费
                depEdges.mapNotNull { context.stageOutputs[it.sourceNodeId] }.firstOrNull()
            }
        }

        // ── 辅助节点容错 ──
        // 如果所有上游都是辅助节点（如 n_swap）且输出为空（失败/超时），
        // 回退到辅助节点的上游输出（如 n_orders 的 OrderGenerationResult），
        // 确保辅助节点永远不阻断下游执行
        if (input == null && depEdges.isNotEmpty() &&
            depEdges.all { it.sourceNodeId in DagNode.AUXILIARY_NODES }) {
            input = depEdges
                .flatMap { e -> edges.filter { it.targetNodeId == e.sourceNodeId } }
                .firstNotNullOfOrNull { context.stageOutputs[it.sourceNodeId] }
            if (input != null) {
                context.log(nodeId, "⚠ 辅助节点上游无输出，回退使用间接上游: ${dagNode.nodeName}")
            }
        }

        // 跳过空输入（非根节点且上游失败）
        if (input == null && depEdges.isNotEmpty()) {
            context.log(nodeId, "⚠ 上游输出为空，跳过: ${dagNode.nodeName}")
            return DagNodeResult(
                nodeId = nodeId,
                nodeName = dagNode.nodeName,
                success = false,
                output = null,
                elapsedMs = 0,
                error = "上游输出为空"
            )
        }

        // 执行节点
        var output: Any? = null
        var error: String? = null
        var success = true

        val elapsed = measureTimeMillis {
            try {
                // 通知 UI 层当前正在执行的节点（pipeline 名 + node 名）
                context.onNodeProgress?.invoke(name, dagNode.nodeName)
                context.log(nodeId, "▶ 开始: ${dagNode.nodeName}")
                val nodeTimeout = when (nodeId) {
                    "n_fit" -> 180_000L   // 拟合计算耗时较长（已并行化，保留余量）
                    "n_bg" -> 10_000L     // 后台管理不需太久
                    else -> 60_000L       // AI精选/策略等需要较长超时
                }
                output = kotlinx.coroutines.withTimeout(nodeTimeout) {
                    (node as PipelineNode<Any, Any>).execute(context, input ?: Unit)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val msg = "Node 超时: ${dagNode.nodeName}"
                error = msg
                context.recordError(nodeId, msg)
                success = false
            } catch (e: Exception) {
                val msg = "Node 执行失败: ${e.message}"
                error = msg
                context.recordError(nodeId, msg)
                success = false
            }
        }

        if (success) {
            context.log(nodeId, "✓ 完成: ${dagNode.nodeName} (${elapsed}ms)")
        }

        // 提取该节点的股票流动记录（节点执行过程中调用 recordStockFlow 写入）
        // 注意：节点内部用 internal nodeId（如 "generate_orders"）记录，
        // 而 DAG 用 XML nodeId（如 "n_orders"），两者都需匹配，否则提取不到
        val internalNodeId = node.nodeId
        val nodeStockFlow = synchronized(context.stockFlowLogs) {
            context.stockFlowLogs.lastOrNull { it.nodeId == nodeId || it.nodeId == internalNodeId }
        }

        if (success) {
            context.onNodeDone?.invoke(name, dagNode.nodeName, output, nodeStockFlow)
        } else {
            context.log(nodeId, "✗ 失败: ${dagNode.nodeName} (${elapsed}ms) — $error")
            context.onNodeDone?.invoke(name, dagNode.nodeName, null, nodeStockFlow)
        }

        // 存入上下文
        contextMutex.withLock {
            context.stageOutputs[nodeId] = output
        }

        return DagNodeResult(
            nodeId = nodeId,
            nodeName = dagNode.nodeName,
            success = success,
            output = output,
            elapsedMs = elapsed,
            error = error,
            stockFlow = nodeStockFlow
        )
    }
}

// ============================================================================
// DagPipelineResult
// ============================================================================

/**
 * Pipeline 分组定义 — 一组逻辑上相关的节点集合。
 * 用于 UI 著色（同组节点染同色）和模板复用。
 */
data class PipelineGroup(
    val id: String,
    val name: String,
    val nodeIds: Set<String>,
    val params: Map<String, String> = emptyMap()
)

/**
 * DAG Pipeline 的执行结果
 */
data class DagPipelineResult(
    /** Pipeline 名称 */
    val pipelineName: String,
    /** 是否所有节点均成功 */
    val success: Boolean,
    /** 各节点的执行结果 */
    val nodeResults: Map<String, DagNodeResult>,
    /** 总耗时（毫秒） */
    val totalElapsedMs: Long,
    /** 最后一个非空输出 */
    val finalOutput: Any? = null,
    /** 错误信息 */
    val errors: Map<String, String> = emptyMap(),
    /** 各节点股票流动记录（nodeId → StockFlowRecord） */
    val stockFlowLogs: Map<String, StockFlowRecord> = emptyMap(),
    /** Pipeline 分组信息（groupId → PipelineGroup） */
    val pipelineGroups: Map<String, PipelineGroup> = emptyMap()
)

/**
 * 单个 DAG 节点的执行结果
 */
data class DagNodeResult(
    /** 节点 ID */
    val nodeId: String,
    /** 节点名称 */
    val nodeName: String,
    /** 是否成功 */
    val success: Boolean,
    /** 节点输出 */
    val output: Any?,
    /** 耗时（毫秒） */
    val elapsedMs: Long,
    /** 错误信息（成功时为 null） */
    val error: String? = null,
    /** 该节点的股票流动记录 */
    val stockFlow: StockFlowRecord? = null
)
