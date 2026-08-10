package com.chin.stockanalysis.strategy.topology.core

/**
 * ## Pipeline 抽象基类
 *
 * Pipeline = 多个 Node（或子 Pipeline）连接在一起，作为一个整体可复用。
 *
 * 核心设计：Pipeline 本身也是 [PipelineNode]，因此可以：
 * - 像 Node 一样被放入更大的 Pipeline
 * - 像 Node 一样在 XML DAG 中引用
 * - 嵌套任意层级
 *
 * ### 层级
 * ```
 * UseCase (完整流程)
 *   └─ Pipeline (可复用的节点图)
 *        └─ Node (单一功能) 或子 Pipeline
 * ```
 *
 * ### 子类实现
 * ```kotlin
 * class MyPipeline : BasePipeline("my_pipe", "我的流程") {
 *     override fun buildChildren(): List<PipelineNode<*, *>> = listOf(
 *         NodeA(), NodeB(), NodeC()
 *     )
 * }
 * ```
 */
abstract class BasePipeline(
    override val nodeId: String,
    override val nodeName: String
) : PipelineNode<Any, Any> {

    override val nodeType: NodeType = NodeType.AGGREGATION

    /** 子节点列表，由 [buildChildren] 懒初始化 */
    private val _children by lazy { buildChildren() }
    val children: List<PipelineNode<*, *>> get() = _children

    /**
     * 构建子节点列表。
     * 子类在此返回组成此 Pipeline 的所有 Node（或子 Pipeline）。
     */
    protected abstract fun buildChildren(): List<PipelineNode<*, *>>

    /**
     * 默认执行：按顺序链式调用子节点，前一节点输出作为下一节点输入。
     * 子类可覆盖以实现 DAG / 并行 / 条件分支等自定义执行逻辑。
     */
    protected open suspend fun executePipeline(
        context: PipelineContext,
        input: Any
    ): Any {
        var result: Any = input
        for (child in children) {
            @Suppress("UNCHECKED_CAST")
            val node = child as PipelineNode<Any, Any>
            result = node.execute(context, result)
        }
        return result
    }

    final override suspend fun execute(context: PipelineContext, input: Any): Any {
        context.log(nodeId, "Pipeline [$nodeName] 开始 (${children.size} 个子节点)")
        val result = executePipeline(context, input)
        context.log(nodeId, "Pipeline [$nodeName] 完成")
        return result
    }
}
