package com.chin.stockanalysis.strategy.topology.core

/**
 * ## Pipeline 抽象基類
 *
 * Pipeline = 多個 Node（或子 Pipeline）連接在一起，作為一個整體可復用。
 *
 * 核心設計：Pipeline 本身也是 [PipelineNode]，因此可以：
 * - 像 Node 一樣被放入更大的 Pipeline
 * - 像 Node 一樣在 XML DAG 中引用
 * - 嵌套任意層級
 *
 * ### 層級
 * ```
 * UseCase (完整流程)
 *   └─ Pipeline (可復用的節點圖)
 *        └─ Node (單一功能) 或子 Pipeline
 * ```
 *
 * ### 子類實現
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

    /** 子節點列表，由 [buildChildren] 懶初始化 */
    private val _children by lazy { buildChildren() }
    val children: List<PipelineNode<*, *>> get() = _children

    /**
     * 構建子節點列表。
     * 子類在此返回組成此 Pipeline 的所有 Node（或子 Pipeline）。
     */
    protected abstract fun buildChildren(): List<PipelineNode<*, *>>

    /**
     * 默認執行：按順序鏈式調用子節點，前一節點輸出作為下一節點輸入。
     * 子類可覆蓋以實現 DAG / 並行 / 條件分支等自定義執行邏輯。
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
        context.log(nodeId, "Pipeline [$nodeName] 開始 (${children.size} 個子節點)")
        val result = executePipeline(context, input)
        context.log(nodeId, "Pipeline [$nodeName] 完成")
        return result
    }
}
