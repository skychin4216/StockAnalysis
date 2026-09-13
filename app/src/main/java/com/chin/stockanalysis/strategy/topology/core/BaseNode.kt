package com.chin.stockanalysis.strategy.topology.core

/**
 * ## Node 抽象基类
 *
 * Node = 单一功能、可复用的最小执行单元。
 *
 * 提供 nodeId / nodeName / nodeType 的标准实现与 log 便捷方法，
 * 子类只需实现 [execute]。
 *
 * ### 迁移
 * ```kotlin
 * // Before
 * class FooNode : PipelineNode<Any, FooResult> {
 *     override val nodeId = "foo"
 *     override val nodeName = "Foo"
 *     override val nodeType = NodeType.FILTER
 *     override suspend fun execute(ctx, input) = ...
 * }
 * // After
 * class FooNode : BaseNode<Any, FooResult>("foo", "Foo", NodeType.FILTER) {
 *     override suspend fun execute(ctx, input) = ...
 * }
 * ```
 */
abstract class BaseNode<IN, OUT>(
    override val nodeId: String,
    override val nodeName: String,
    override val nodeType: NodeType
) : PipelineNode<IN, OUT> {

    override fun equals(other: Any?): Boolean =
        other is PipelineNode<*, *> && other.nodeId == nodeId

    override fun hashCode(): Int = nodeId.hashCode()

    override fun toString(): String = "$nodeName($nodeId)"

    /** 便捷日志：自动带 nodeId 前缀 */
    protected fun PipelineContext.log(message: String) = log(nodeId, message)
}
