package com.chin.stockanalysis.strategy.topology.core

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

// ============================================================================
// DagPipeline — 高通 Camera 風格 DAG 拓撲執行引擎
// ============================================================================

/**
 * ## DAG 邊定義（高通 Links 等價）
 *
 * 源節點 → 目標節點的有向邊，帶端口標識。
 * 對應高通 XML 中的 `<Link><SourcePortId>...<TargetPortId>`。
 *
 * @property sourceNodeId 源節點 ID
 * @property sourcePortId 源端口 ID（高通兼容，預留）
 * @property targetNodeId 目標節點 ID
 * @property targetPortId 目標端口 ID（高通兼容，預留）
 */
data class DagEdge(
    val sourceNodeId: String,
    val sourcePortId: Int = 0,
    val targetNodeId: String,
    val targetPortId: Int = 0
)

/**
 * ## DAG 節點定義（高通 NodeList 等價）
 *
 * 封裝一個 [PipelineNode] 及其 ID。
 * 對應高通 XML 中的 `<Node><nodeName>...<NodeId>`。
 */
data class DagNode(
    val nodeId: String,
    val nodeName: String,
    val node: PipelineNode<*, *>
) {
    /** 該節點的所有入邊的源節點 ID 集合 */
    var dependencies: Set<String> = emptySet()

    /** 該節點的所有出邊的目標節點 ID 集合 */
    var dependents: Set<String> = emptySet()

    /** 是否為關鍵節點（失敗會導致 Pipeline 失敗） */
    val isCritical: Boolean get() = nodeId !in NON_CRITICAL_NODE_IDS &&
        !nodeId.startsWith("strategy_")

    companion object {
        /** 非關鍵節點：失敗不影響 Pipeline 最終結果（僅記錄警告） */
        val NON_CRITICAL_NODE_IDS = setOf(
            "n_bg",       // 後臺暫停/恢復（輔助）
            "n_fit",      // 擬合計算（耗時，不影響建倉）
            "n_swap",     // 騰龍換鳥（輔助優化）
            "n_guard",    // 持倉風控（輔助，失敗不阻斷買入）
            "n_heat",     // 熱度計算（輔助數據）
            "n_candle",   // K線形態偵測（輔助提醒）
            "n_news_str", // 新聞力度（輔助評分）
            "n_rot_pen",  // 輪動懲罰（輔助評分）
            "n_crossday", // 跨日聚合（輔助數據）
            "n_multihot"  // 多周期熱門（輔助數據）
        )

        /**
         * 輔助節點：僅提供執行順序保證（如先賣後買），其輸出為空/失敗時
         * 下游節點自動回退到輔助節點的上游輸出，永遠不阻斷下游執行。
         */
        val AUXILIARY_NODES = setOf("n_swap")
    }
}

/**
 * ## DagPipeline — 高通 Camera 風格的 DAG 拓撲執行引擎
 *
 * 與 [Pipeline]（Stage → LinkList → Link）不同，DagPipeline 採用：
 * - **扁平 DAG 結構**：NodeList + Links，無嵌套
 * - **拓撲排序自動推導並行度**：Kahn 算法分層，同層節點自動並行
 * - **無自環 Link 問題**：源節點若無入邊（根節點），自動用 `Unit` 作為初始輸入
 *
 * ### 高通 XML 對應關係
 * ```
 * <NodeList>        → nodes: List<DagNode>
 * <Links>           → edges: List<DagEdge>
 * <PipelineName>    → name
 * <UsecaseName>      → (由 UseCase 層管理)
 * ```
 *
 * ### 執行流程
 * 1. 構建鄰接表 + 入度表
 * 2. Kahn 拓撲排序，分層（同層可並行）
 * 3. 逐層執行：同層節點用 coroutineScope + async 並行
 * 4. 每個節點從 context.stageOutputs 讀取依賴的輸出，執行後存入 context.stageOutputs
 *
 * ### 使用示例
 * ```kotlin
 * val dag = DagPipeline(
 *     name = "MidTermPipeline",
 *     nodes = listOf(
 *         DagNode("n_ctx", "市場上下文", MarketContextNode()),
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
    val edges: List<DagEdge>
) {
    companion object {
        private const val TAG = "DagPipeline"

        /**
         * 主流節點：輸出 0 代表「無股票可處理」，後續節點無意義 → 提前終止。
         * 使用 internal nodeId（node.nodeId）。
         * 不含：swap_weak（0=無需換鳥，merge 仍需跑）、adaptive_params/market_context（非股票輸出）、
         *       sector_boost/ai_predict（enrichment，不直接決定有無候選）。
         */
        val FLOW_CRITICAL_NODES = setOf(
            "stock_pool", "candidate_pool", "signal_merge",
            "smart_money_filter", "news_guard", "generate_orders"
        )
    }

    // 鄰接表
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
     * 執行 DAG Pipeline。
     *
     * 使用 Kahn 算法進行拓撲排序，分層並行執行。
     * 每個節點的輸出存入 `context.stageOutputs[nodeId]`，
     * 下游節點從 `context.stageOutputs[sourceNodeId]` 讀取上游輸出。
     */
    suspend fun execute(context: PipelineContext): DagPipelineResult {
        val nodeResults = mutableMapOf<String, DagNodeResult>()
        val errors = mutableMapOf<String, String>()
        var allSuccess = true
        var finalOutput: Any? = null

        Log.i(TAG, "▶ DAG Pipeline 開始執行: $name (${nodes.size} nodes, ${edges.size} edges)")

        val totalElapsed = measureTimeMillis {
            // 1. Kahn 拓撲排序 → 分層
            val layers = topologicalSort()

            if (layers.isEmpty()) {
                errors["topology"] = "拓撲排序失敗：可能存在環"
                allSuccess = false
            } else {
                Log.i(TAG, "  拓撲分層: ${layers.size} 層" +
                    layers.mapIndexed { i, layer -> "\n    Layer $i: [${layer.joinToString { it.nodeId }}]" }
                        .joinToString())

                // 2. 逐層執行
                for ((layerIndex, layer) in layers.withIndex()) {
                    val layerElapsed = measureTimeMillis {
                        if (layer.size == 1) {
                            // 單節點串行
                            val dagNode = layer[0]
                            val result = executeNode(dagNode, context)
                            nodeResults[dagNode.nodeId] = result
                            if (!result.success && dagNode.isCritical) allSuccess = false
                            if (result.output != null) finalOutput = result.output
                            if (result.error != null && dagNode.isCritical) errors[dagNode.nodeId] = result.error
                        } else {
                            // 多節點並行
                            Log.i(TAG, "  Layer $layerIndex: 並行執行 ${layer.size} 個節點")
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

                    // ── Fail-fast：主流節點輸出 0 → 無股票可處理，提前終止 ──
                    val emptyCritical = layer.firstOrNull { dagNode ->
                        dagNode.node.nodeId in FLOW_CRITICAL_NODES &&
                            nodeResults[dagNode.nodeId]?.let { r ->
                                r.success && (r.stockFlow?.outputCount ?: -1) == 0
                            } == true
                    }
                    if (emptyCritical != null) {
                        val nodeName = emptyCritical.nodeName
                        Log.w(TAG, "⛔ $nodeName 輸出 0，無股票可處理，提前終止 Pipeline")
                        context.log(emptyCritical.nodeId,
                            "⛔ $nodeName 輸出 0 → Pipeline 提前終止，後續節點不執行")
                        errors[emptyCritical.nodeId] = "輸出為0，提前終止"
                        allSuccess = false
                        break
                    }
                }
            }
        }

        // 僅合併關鍵節點的 context 錯誤
        // 注意：節點內部用 internal nodeId（如 "heat_score"）記錄錯誤，
        // 而 NON_CRITICAL_NODE_IDS 使用 XML nodeId（如 "n_heat"），兩者都需過濾
        val nonCriticalInternalIds = nodes.filter { !it.isCritical }.map { it.node.nodeId }.toSet()
        context.errors.filterKeys { key ->
            !DagNode.NON_CRITICAL_NODE_IDS.contains(key) &&
                !nonCriticalInternalIds.contains(key) &&
                !key.startsWith("strategy_")
        }.let { errors.putAll(it) }

        Log.i(TAG, "◀ DAG Pipeline 完成: ${if (allSuccess) "成功" else "失敗"}, 耗時 ${totalElapsed}ms" +
            (if (errors.isNotEmpty()) ", 錯誤: ${errors.keys}" else ""))

        // 收集所有節點的股票流動記錄
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
            stockFlowLogs = stockFlowMap
        )
    }

    // ════════════════════════════════════════════════════
    //  拓撲排序（Kahn 算法）
    // ════════════════════════════════════════════════════

    /**
     * Kahn 拓撲排序，返回分層列表。
     * 每層內的節點無依賴關係，可以並行執行。
     *
     * @return 分層列表，外層為層級，內層為該層的節點
     */
    private fun topologicalSort(): List<List<DagNode>> {
        val remainingInDegree = inDegree.toMutableMap()
        val remaining = nodes.map { it.nodeId }.toMutableSet()
        val layers = mutableListOf<List<DagNode>>()

        while (remaining.isNotEmpty()) {
            // 找出所有入度為 0 的節點
            val ready = remaining.filter { (remainingInDegree[it] ?: 0) == 0 }
            if (ready.isEmpty()) {
                // 存在環
                Log.e(TAG, "拓撲排序失敗: 剩餘 ${remaining.size} 個節點存在環依賴")
                return layers.takeIf { remaining.isEmpty() } ?: emptyList()
            }

            val layerNodes = ready.mapNotNull { nodeMap[it] }
            layers.add(layerNodes)

            // 移除已處理的節點，更新入度
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
    //  節點執行
    // ════════════════════════════════════════════════════

    /**
     * 執行單個 DAG 節點。
     *
     * 1. 從 context.stageOutputs 收集所有依賴節點的輸出
     * 2. 如果有單一依賴，用其輸出作為本節點的輸入
     * 3. 如果有多個依賴，用第一個依賴的輸出作為輸入（其餘通過 context 傳遞）
     * 4. 如果無依賴（根節點），用 `Unit` 作為輸入
     * 5. 執行後將輸出存入 context.stageOutputs[nodeId]
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun executeNode(dagNode: DagNode, context: PipelineContext): DagNodeResult {
        val nodeId = dagNode.nodeId
        val node = dagNode.node

        // 收集依賴輸入
        val depEdges = edges.filter { it.targetNodeId == nodeId }
        var input: Any? = when {
            depEdges.isEmpty() -> Unit  // 根節點：無依賴，輸入 Unit
            depEdges.size == 1 -> {
                // 單一依賴：直接用上游輸出
                val sourceId = depEdges[0].sourceNodeId
                context.stageOutputs[sourceId]
            }
            node.nodeType == NodeType.AGGREGATION -> {
                // 聚合節點：收集所有上游輸出為 List
                depEdges.mapNotNull { context.stageOutputs[it.sourceNodeId] }
            }
            else -> {
                // 非聚合多依賴：用首個上游輸出作為主輸入，其餘通過 context.stageOutputs 讀取
                val sourceId = depEdges[0].sourceNodeId
                context.stageOutputs[sourceId]
            }
        }

        // ── 輔助節點容錯 ──
        // 如果所有上游都是輔助節點（如 n_swap）且輸出為空（失敗/超時），
        // 回退到輔助節點的上游輸出（如 n_orders 的 OrderGenerationResult），
        // 確保輔助節點永遠不阻斷下游執行
        if (input == null && depEdges.isNotEmpty() &&
            depEdges.all { it.sourceNodeId in DagNode.AUXILIARY_NODES }) {
            input = depEdges
                .flatMap { e -> edges.filter { it.targetNodeId == e.sourceNodeId } }
                .firstNotNullOfOrNull { context.stageOutputs[it.sourceNodeId] }
            if (input != null) {
                context.log(nodeId, "⚠ 輔助節點上游無輸出，回退使用間接上游: ${dagNode.nodeName}")
            }
        }

        // 跳過空輸入（非根節點且上游失敗）
        if (input == null && depEdges.isNotEmpty()) {
            context.log(nodeId, "⚠ 上游輸出為空，跳過: ${dagNode.nodeName}")
            return DagNodeResult(
                nodeId = nodeId,
                nodeName = dagNode.nodeName,
                success = false,
                output = null,
                elapsedMs = 0,
                error = "上游輸出為空"
            )
        }

        // 執行節點
        var output: Any? = null
        var error: String? = null
        var success = true

        val elapsed = measureTimeMillis {
            try {
                // 通知 UI 層當前正在執行的節點（pipeline 名 + node 名）
                context.onNodeProgress?.invoke(name, dagNode.nodeName)
                context.log(nodeId, "▶ 開始: ${dagNode.nodeName}")
                val nodeTimeout = when (nodeId) {
                    "n_fit" -> 120_000L   // 擬合計算耗時較長
                    "n_bg" -> 10_000L     // 後臺管理不需太久
                    else -> 60_000L       // AI精選/策略等需要較長超時
                }
                output = kotlinx.coroutines.withTimeout(nodeTimeout) {
                    (node as PipelineNode<Any, Any>).execute(context, input ?: Unit)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val msg = "Node 超時: ${dagNode.nodeName}"
                error = msg
                context.recordError(nodeId, msg)
                success = false
            } catch (e: Exception) {
                val msg = "Node 執行失敗: ${e.message}"
                error = msg
                context.recordError(nodeId, msg)
                success = false
            }
        }

        if (success) {
            context.log(nodeId, "✓ 完成: ${dagNode.nodeName} (${elapsed}ms)")
        } else {
            context.log(nodeId, "✗ 失敗: ${dagNode.nodeName} (${elapsed}ms) — $error")
        }

        // 存入上下文
        contextMutex.withLock {
            context.stageOutputs[nodeId] = output
        }

        // 提取該節點的股票流動記錄（節點執行過程中調用 recordStockFlow 寫入）
        // 注意：節點內部用 internal nodeId（如 "generate_orders"）記錄，
        // 而 DAG 用 XML nodeId（如 "n_orders"），兩者都需匹配，否則提取不到
        val internalNodeId = node.nodeId
        val nodeStockFlow = synchronized(context.stockFlowLogs) {
            context.stockFlowLogs.lastOrNull { it.nodeId == nodeId || it.nodeId == internalNodeId }
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
 * DAG Pipeline 的執行結果
 */
data class DagPipelineResult(
    /** Pipeline 名稱 */
    val pipelineName: String,
    /** 是否所有節點均成功 */
    val success: Boolean,
    /** 各節點的執行結果 */
    val nodeResults: Map<String, DagNodeResult>,
    /** 總耗時（毫秒） */
    val totalElapsedMs: Long,
    /** 最後一個非空輸出 */
    val finalOutput: Any? = null,
    /** 錯誤信息 */
    val errors: Map<String, String> = emptyMap(),
    /** 各節點股票流動記錄（nodeId → StockFlowRecord） */
    val stockFlowLogs: Map<String, StockFlowRecord> = emptyMap()
)

/**
 * 單個 DAG 節點的執行結果
 */
data class DagNodeResult(
    /** 節點 ID */
    val nodeId: String,
    /** 節點名稱 */
    val nodeName: String,
    /** 是否成功 */
    val success: Boolean,
    /** 節點輸出 */
    val output: Any?,
    /** 耗時（毫秒） */
    val elapsedMs: Long,
    /** 錯誤信息（成功時為 null） */
    val error: String? = null,
    /** 該節點的股票流動記錄 */
    val stockFlow: StockFlowRecord? = null
)
