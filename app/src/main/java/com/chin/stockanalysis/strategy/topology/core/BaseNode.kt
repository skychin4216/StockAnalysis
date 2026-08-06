package com.chin.stockanalysis.strategy.topology.core

/**
 * ## Node 抽象基類
 *
 * Node = 單一功能、可復用的最小執行單元。
 *
 * 提供 nodeId / nodeName / nodeType 的標準實現與 log 便捷方法，
 * 子類只需實現 [execute]。
 *
 * ### 遷移
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

    /** 便捷日誌：自動帶 nodeId 前綴 */
    protected fun PipelineContext.log(message: String) = log(nodeId, message)
}
