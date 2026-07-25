package com.chin.stockanalysis.strategy.topology.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

// ============================================================================
// Link<UPSTREAM_OUT, DOWNSTREAM_IN>
// ============================================================================

/**
 * ## Link — 連接兩個 Node 的有向邊
 *
 * 負責在上游節點輸出與下游節點輸入之間進行類型轉換與條件過濾。
 * - [transformer] 可選的類型轉換函數，若為 null 則直接 cast
 * - [condition] 可選的條件判斷，返回 false 時跳過下游節點的執行
 */
class Link<UPSTREAM_OUT, DOWNSTREAM_IN>(
    val from: PipelineNode<*, UPSTREAM_OUT>,
    val to: PipelineNode<DOWNSTREAM_IN, *>,
    val transformer: ((UPSTREAM_OUT) -> DOWNSTREAM_IN)? = null,
    val condition: ((UPSTREAM_OUT, PipelineContext) -> Boolean)? = null,
    val label: String = "${from.nodeId} -> ${to.nodeId}"
) {
    companion object {
        /** 虛擬源節點，用於 root Link 的 from。輸出 Unit，表示沒有上游依賴。 */
        private val SourceNode: PipelineNode<Unit, Unit> = object : PipelineNode<Unit, Unit> {
            override val nodeId = "__source__"
            override val nodeName = "Source"
            override val nodeType = NodeType.DATA_SOURCE
            override suspend fun execute(context: PipelineContext, input: Unit) = Unit
        }

        /**
         * 創建一個從 root 節點（接受 Unit 輸入）開始的 Link。
         *
         * 用於 LinkList 的第一個節點沒有上游依賴的情況。
         */
        fun <DOWNSTREAM_IN> root(
            target: PipelineNode<DOWNSTREAM_IN, *>,
            label: String = "root -> ${target.nodeId}"
        ): Link<Unit, DOWNSTREAM_IN> = Link(
            from = SourceNode,
            to = target,
            label = label
        )
    }
    /**
     * 將上游輸出傳播到下游節點。
     *
     * 1. 先檢查 [condition]，若條件為 false 則返回 null（跳過下游）
     * 2. 若存在 [transformer] 則使用其轉換數據，否則直接 cast
     *
     * @param upstreamOutput 上游節點的輸出結果
     * @param context 共享的流水線上下文
     * @return 轉換後的下游輸入，若條件不滿足或轉換失敗則返回 null
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun propagate(upstreamOutput: UPSTREAM_OUT, context: PipelineContext): DOWNSTREAM_IN? {
        // 檢查條件：若定義了 condition 且返回 false，則跳過下游
        if (condition != null) {
            val shouldPass = try {
                condition.invoke(upstreamOutput, context)
            } catch (e: Exception) {
                context.recordError(label, "Link condition evaluation failed: ${e.message}")
                return null
            }
            if (!shouldPass) return null
        }

        // 執行轉換
        return try {
            if (transformer != null) {
                transformer.invoke(upstreamOutput)
            } else {
                upstreamOutput as DOWNSTREAM_IN
            }
        } catch (e: Exception) {
            context.recordError(label, "Link transform/cast failed: ${e.message}")
            null
        }
    }
}

// ============================================================================
// LinkList & LinkListResult
// ============================================================================

/**
 * ## LinkList — 有序鏈路列表
 *
 * 一組串行的 [Link] 組成一條處理鏈，可作為可復用的處理模板。
 * - [links] 按順序依次執行
 * - [skipOnEmpty] 為 true 時，若中間某步產生 null 則跳過後續步驟
 */
class LinkList(
    val name: String,
    val description: String = "",
    val links: List<Link<*, *>>,
    val skipOnEmpty: Boolean = true
) {
    /**
     * 返回鏈路中的第一個節點（頭節點）
     */
    fun headNode(): PipelineNode<*, *> {
        check(links.isNotEmpty()) { "LinkList '$name' has no links" }
        return links.first().from
    }

    /**
     * 返回鏈路中的最後一個節點（尾節點）
     */
    fun tailNode(): PipelineNode<*, *> {
        check(links.isNotEmpty()) { "LinkList '$name' has no links" }
        return links.last().to
    }

    /**
     * 返回鏈路中所有不重複的節點（按出現順序）
     */
    fun allNodes(): List<PipelineNode<*, *>> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<PipelineNode<*, *>>()
        for (link in links) {
            if (visited.add(link.from.nodeId)) result.add(link.from)
            if (visited.add(link.to.nodeId)) result.add(link.to)
        }
        return result
    }

    /**
     * 串行執行所有 Link。
     *
     * 對每個 Link：
     * 1. 調用 [Link.propagate] 將上游輸出轉換為下游輸入
     * 2. 調用下游節點的 [PipelineNode.execute] 進行處理
     * 3. 記錄每步耗時
     *
     * 錯誤不中斷整條鏈路，而是記錄到上下文，下游收到 null。
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun execute(context: PipelineContext): LinkListResult {
        val stepTimings = mutableListOf<Pair<String, Long>>()
        var success = true
        var currentOutput: Any? = null

        // 若鏈路為空，直接返回
        if (links.isEmpty()) {
            return LinkListResult(
                linkListName = name,
                success = true,
                output = null,
                stepTimings = emptyList()
            )
        }

        for ((index, link) in links.withIndex()) {
            val nodeId = link.to.nodeId

            // 若上一步輸出為 null 且設置了 skipOnEmpty，則跳過
            if (currentOutput == null && skipOnEmpty) {
                stepTimings.add(nodeId to 0L)
                continue
            }

            // 將上游輸出傳播為下游輸入（null 時用 Unit 作為佔位）
            val downstreamInput = (link as Link<Any, Any>).propagate(currentOutput ?: Unit, context)

            // 若傳播結果為 null（條件不滿足或轉換失敗）
            if (downstreamInput == null) {
                stepTimings.add(nodeId to 0L)
                currentOutput = null
                success = false
                continue
            }

            // 執行下游節點
            var nodeOutput: Any? = null
            val elapsed = measureTimeMillis {
                try {
                    nodeOutput = (link.to as PipelineNode<Any, Any>).execute(context, downstreamInput)
                } catch (e: Exception) {
                    context.recordError(
                        nodeId,
                        "Node execution failed in LinkList '$name' at step ${index + 1}: ${e.message}"
                    )
                    success = false
                }
            }
            stepTimings.add(nodeId to elapsed)

            // 若節點執行失敗，下游收到 null
            currentOutput = nodeOutput
            if (nodeOutput == null) {
                success = false
            }
        }

        return LinkListResult(
            linkListName = name,
            success = success,
            output = currentOutput,
            stepTimings = stepTimings
        )
    }
}

/**
 * LinkList 的執行結果
 */
data class LinkListResult(
    /** 鏈路名稱 */
    val linkListName: String,
    /** 是否所有步驟均成功 */
    val success: Boolean,
    /** 最終輸出（最後一個節點的結果） */
    val output: Any?,
    /** 每步耗時，(nodeId, 耗時ms) */
    val stepTimings: List<Pair<String, Long>>
)

// ============================================================================
// Pipeline & PipelineResult
// ============================================================================

/**
 * ## Pipeline — 編排多個 LinkList 的執行引擎
 *
 * 以 [Stage] 為單位組織 [LinkList]，支持：
 * - Stage 間串行執行
 * - 同一 Stage 內多個 LinkList 可並行執行（[Stage.parallel] = true）
 * - 每個 Stage 可設置 [Stage.condition] 決定是否執行
 * - Stage 執行結果注入 [PipelineContext.stageOutputs]
 */
data class Pipeline(
    val id: String = "",
    val name: String,
    val description: String = "",
    val version: Int = 1,
    val stages: List<Stage>,
) {
    /**
     * Pipeline 的一個執行階段
     */
    data class Stage(
        /** 階段名稱 */
        val name: String,
        /** 該階段包含的 LinkList 列表 */
        val linkLists: List<LinkList>,
        /** 同一階段內的 LinkList 是否並行執行，默認串行 */
        val parallel: Boolean = false,
        /** 條件函數：返回 false 時跳過整個 Stage，null 表示始終執行 */
        val condition: ((PipelineContext) -> Boolean)? = null,
    )

    private val contextMutex = Mutex()

    /**
     * 執行整個 Pipeline。
     *
     * 按 Stage 順序依次執行：
     * - 檢查 Stage 的 [Stage.condition]，不滿足則跳過
     * - 若 [Stage.parallel] 為 true，使用 coroutineScope + async 並行執行 LinkList
     * - 否則串行執行 LinkList
     * - 每個 Stage 的結果注入 [PipelineContext.stageOutputs]
     */
    suspend fun execute(context: PipelineContext): PipelineResult {
        val stageResults = mutableMapOf<String, LinkListResult>()
        val timings = mutableMapOf<String, Long>()
        val errors = mutableMapOf<String, String>()
        var finalOutput: Any? = null
        var allSuccess = true

        val totalElapsed = measureTimeMillis {
            for (stage in stages) {
                // 檢查 Stage 條件
                if (stage.condition != null) {
                    val shouldRun = try {
                        stage.condition.invoke(context)
                    } catch (e: Exception) {
                        errors[stage.name] = "Stage condition failed: ${e.message}"
                        allSuccess = false
                        continue
                    }
                    if (!shouldRun) continue
                }

                val stageElapsed = measureTimeMillis {
                    val results = if (stage.parallel) {
                        executeStageParallel(stage, context, errors)
                    } else {
                        executeStageSequential(stage, context, errors)
                    }

                    // 收集結果並注入上下文
                    for ((linkListName, result) in results) {
                        stageResults["${stage.name}:$linkListName"] = result
                        if (!result.success) {
                            allSuccess = false
                        }
                        // 記錄最後一個非空的輸出作為 finalOutput
                        if (result.output != null) {
                            finalOutput = result.output
                        }
                        // 注入到上下文
                        contextMutex.withLock {
                            context.stageOutputs["${stage.name}:$linkListName"] = result.output
                        }
                    }
                }
                timings[stage.name] = stageElapsed
            }
        }

        // 收集上下文中的錯誤
        errors.putAll(context.errors)

        return PipelineResult(
            pipelineName = name,
            success = allSuccess && errors.isEmpty(),
            stageResults = stageResults,
            timings = timings,
            totalElapsedMs = totalElapsed,
            finalOutput = finalOutput,
            errors = errors
        )
    }

    /**
     * 串行執行一個 Stage 內的所有 LinkList
     */
    private suspend fun executeStageSequential(
        stage: Stage,
        context: PipelineContext,
        errors: MutableMap<String, String>
    ): Map<String, LinkListResult> {
        val results = mutableMapOf<String, LinkListResult>()
        for (linkList in stage.linkLists) {
            val result = try {
                linkList.execute(context)
            } catch (e: Exception) {
                errors["${stage.name}:${linkList.name}"] = "LinkList execution failed: ${e.message}"
                LinkListResult(
                    linkListName = linkList.name,
                    success = false,
                    output = null,
                    stepTimings = emptyList()
                )
            }
            results[linkList.name] = result
        }
        return results
    }

    /**
     * 並行執行一個 Stage 內的所有 LinkList（使用 coroutineScope + async）
     */
    private suspend fun executeStageParallel(
        stage: Stage,
        context: PipelineContext,
        errors: MutableMap<String, String>
    ): Map<String, LinkListResult> {
        return coroutineScope {
            val deferredResults = stage.linkLists.map { linkList ->
                async {
                    linkList.name to try {
                        linkList.execute(context)
                    } catch (e: Exception) {
                        errors["${stage.name}:${linkList.name}"] =
                            "LinkList execution failed: ${e.message}"
                        LinkListResult(
                            linkListName = linkList.name,
                            success = false,
                            output = null,
                            stepTimings = emptyList()
                        )
                    }
                }
            }
            deferredResults.awaitAll().toMap()
        }
    }
}

// ============================================================================
// PipelineResult
// ============================================================================

/**
 * Pipeline 的執行結果
 */
data class PipelineResult(
    /** Pipeline 名稱 */
    val pipelineName: String,
    /** 是否所有 Stage 均成功完成 */
    val success: Boolean,
    /** 各 Stage 中 LinkList 的執行結果，key 格式為 "stageName:linkListName" */
    val stageResults: Map<String, LinkListResult>,
    /** 各 Stage 的耗時，key 為 stageName */
    val timings: Map<String, Long>,
    /** 總耗時（毫秒） */
    val totalElapsedMs: Long,
    /** 最終輸出（最後一個非空輸出） */
    val finalOutput: Any? = null,
    /** 錯誤信息，key 為出錯的 stage:linkList 或 nodeId */
    val errors: Map<String, String> = emptyMap(),
    /** 各節點股票流動記錄（nodeId → StockFlowRecord），DAG Pipeline 執行後填充 */
    val stockFlowLogs: Map<String, StockFlowRecord> = emptyMap()
)
