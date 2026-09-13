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
 * ## Link — 连接两个 Node 的有向边
 *
 * 负责在上游节点输出与下游节点输入之间进行类型转换与条件过滤。
 * - [transformer] 可选的类型转换函数，若为 null 则直接 cast
 * - [condition] 可选的条件判断，返回 false 时跳过下游节点的执行
 */
class Link<UPSTREAM_OUT, DOWNSTREAM_IN>(
    val from: PipelineNode<*, UPSTREAM_OUT>,
    val to: PipelineNode<DOWNSTREAM_IN, *>,
    val transformer: ((UPSTREAM_OUT) -> DOWNSTREAM_IN)? = null,
    val condition: ((UPSTREAM_OUT, PipelineContext) -> Boolean)? = null,
    val label: String = "${from.nodeId} -> ${to.nodeId}"
) {
    companion object {
        /** 虚拟源节点，用于 root Link 的 from。输出 Unit，表示没有上游依赖。 */
        private val SourceNode: PipelineNode<Unit, Unit> = object : PipelineNode<Unit, Unit> {
            override val nodeId = "__source__"
            override val nodeName = "Source"
            override val nodeType = NodeType.DATA_SOURCE
            override suspend fun execute(context: PipelineContext, input: Unit) = Unit
        }

        /**
         * 创建一个从 root 节点（接受 Unit 输入）开始的 Link。
         *
         * 用于 LinkList 的第一个节点没有上游依赖的情况。
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
     * 将上游输出传播到下游节点。
     *
     * 1. 先检查 [condition]，若条件为 false 则返回 null（跳过下游）
     * 2. 若存在 [transformer] 则使用其转换数据，否则直接 cast
     *
     * @param upstreamOutput 上游节点的输出结果
     * @param context 共享的流水线上下文
     * @return 转换后的下游输入，若条件不满足或转换失败则返回 null
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun propagate(upstreamOutput: UPSTREAM_OUT, context: PipelineContext): DOWNSTREAM_IN? {
        // 检查条件：若定义了 condition 且返回 false，则跳过下游
        if (condition != null) {
            val shouldPass = try {
                condition.invoke(upstreamOutput, context)
            } catch (e: Exception) {
                context.recordError(label, "Link condition evaluation failed: ${e.message}")
                return null
            }
            if (!shouldPass) return null
        }

        // 执行转换
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
 * ## LinkList — 有序链路列表
 *
 * 一组串行的 [Link] 组成一条处理链，可作为可复用的处理模板。
 * - [links] 按顺序依次执行
 * - [skipOnEmpty] 为 true 时，若中间某步产生 null 则跳过后续步骤
 */
class LinkList(
    val name: String,
    val description: String = "",
    val links: List<Link<*, *>>,
    val skipOnEmpty: Boolean = true
) {
    /**
     * 返回链路中的第一个节点（头节点）
     */
    fun headNode(): PipelineNode<*, *> {
        check(links.isNotEmpty()) { "LinkList '$name' has no links" }
        return links.first().from
    }

    /**
     * 返回链路中的最后一个节点（尾节点）
     */
    fun tailNode(): PipelineNode<*, *> {
        check(links.isNotEmpty()) { "LinkList '$name' has no links" }
        return links.last().to
    }

    /**
     * 返回链路中所有不重复的节点（按出现顺序）
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
     * 串行执行所有 Link。
     *
     * 对每个 Link：
     * 1. 调用 [Link.propagate] 将上游输出转换为下游输入
     * 2. 调用下游节点的 [PipelineNode.execute] 进行处理
     * 3. 记录每步耗时
     *
     * 错误不中断整条链路，而是记录到上下文，下游收到 null。
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun execute(context: PipelineContext): LinkListResult {
        val stepTimings = mutableListOf<Pair<String, Long>>()
        var success = true
        var currentOutput: Any? = null

        // 若链路为空，直接返回
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

            // 若上一步输出为 null 且设置了 skipOnEmpty，则跳过
            if (currentOutput == null && skipOnEmpty) {
                stepTimings.add(nodeId to 0L)
                continue
            }

            // 将上游输出传播为下游输入（null 时用 Unit 作为占位）
            val downstreamInput = (link as Link<Any, Any>).propagate(currentOutput ?: Unit, context)

            // 若传播结果为 null（条件不满足或转换失败）
            if (downstreamInput == null) {
                stepTimings.add(nodeId to 0L)
                currentOutput = null
                success = false
                continue
            }

            // 执行下游节点
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

            // 若节点执行失败，下游收到 null
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
 * LinkList 的执行结果
 */
data class LinkListResult(
    /** 链路名称 */
    val linkListName: String,
    /** 是否所有步骤均成功 */
    val success: Boolean,
    /** 最终输出（最后一个节点的结果） */
    val output: Any?,
    /** 每步耗时，(nodeId, 耗时ms) */
    val stepTimings: List<Pair<String, Long>>
)

// ============================================================================
// Pipeline & PipelineResult
// ============================================================================

/**
 * ## Pipeline — 编排多个 LinkList 的执行引擎
 *
 * 以 [Stage] 为单位组织 [LinkList]，支持：
 * - Stage 间串行执行
 * - 同一 Stage 内多个 LinkList 可并行执行（[Stage.parallel] = true）
 * - 每个 Stage 可设置 [Stage.condition] 决定是否执行
 * - Stage 执行结果注入 [PipelineContext.stageOutputs]
 */
data class Pipeline(
    val id: String = "",
    val name: String,
    val description: String = "",
    val version: Int = 1,
    val stages: List<Stage>,
) {
    /**
     * Pipeline 的一个执行阶段
     */
    data class Stage(
        /** 阶段名称 */
        val name: String,
        /** 该阶段包含的 LinkList 列表 */
        val linkLists: List<LinkList>,
        /** 同一阶段内的 LinkList 是否并行执行，默认串行 */
        val parallel: Boolean = false,
        /** 条件函数：返回 false 时跳过整个 Stage，null 表示始终执行 */
        val condition: ((PipelineContext) -> Boolean)? = null,
    )

    private val contextMutex = Mutex()

    /**
     * 执行整个 Pipeline。
     *
     * 按 Stage 顺序依次执行：
     * - 检查 Stage 的 [Stage.condition]，不满足则跳过
     * - 若 [Stage.parallel] 为 true，使用 coroutineScope + async 并行执行 LinkList
     * - 否则串行执行 LinkList
     * - 每个 Stage 的结果注入 [PipelineContext.stageOutputs]
     */
    suspend fun execute(context: PipelineContext): PipelineResult {
        val stageResults = mutableMapOf<String, LinkListResult>()
        val timings = mutableMapOf<String, Long>()
        val errors = mutableMapOf<String, String>()
        var finalOutput: Any? = null
        var allSuccess = true

        val totalElapsed = measureTimeMillis {
            for (stage in stages) {
                // 检查 Stage 条件
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

                    // 收集结果并注入上下文
                    for ((linkListName, result) in results) {
                        stageResults["${stage.name}:$linkListName"] = result
                        if (!result.success) {
                            allSuccess = false
                        }
                        // 记录最后一个非空的输出作为 finalOutput
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

        // 收集上下文中的错误
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
     * 串行执行一个 Stage 内的所有 LinkList
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
     * 并行执行一个 Stage 内的所有 LinkList（使用 coroutineScope + async）
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
 * Pipeline 的执行结果
 */
data class PipelineResult(
    /** Pipeline 名称 */
    val pipelineName: String,
    /** 是否所有 Stage 均成功完成 */
    val success: Boolean,
    /** 各 Stage 中 LinkList 的执行结果，key 格式为 "stageName:linkListName" */
    val stageResults: Map<String, LinkListResult>,
    /** 各 Stage 的耗时，key 为 stageName */
    val timings: Map<String, Long>,
    /** 总耗时（毫秒） */
    val totalElapsedMs: Long,
    /** 最终输出（最后一个非空输出） */
    val finalOutput: Any? = null,
    /** 错误信息，key 为出错的 stage:linkList 或 nodeId */
    val errors: Map<String, String> = emptyMap(),
    /** 各节点股票流动记录（nodeId → StockFlowRecord），DAG Pipeline 执行后填充 */
    val stockFlowLogs: Map<String, StockFlowRecord> = emptyMap()
)
