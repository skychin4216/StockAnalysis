package com.chin.stockanalysis.strategy.topology.viz

import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.Pipeline
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser.UseCaseConfig

/**
 * ## TopologyMermaidVisualizer
 *
 * 将 Pipeline / UseCase 拓扑结构转换为 Mermaid 流程图字符串，
 * 可直接粘贴到 Mermaid Live Editor 或 Markdown 中渲染。
 *
 * ### Node 形状对应表
 * | NodeType        | Mermaid 形状   | 语义       |
 * |-----------------|---------------|-----------|
 * | DATA_SOURCE     | 圆角矩形 `(...)` | 数据源     |
 * | STRATEGY        | 菱形 `{...}`    | 策略筛选   |
 * | FILTER          | 矩形 `[...]`    | 过滤       |
 * | AI_PREDICTION   | 六边形 `{{...}}`| AI 预测   |
 * | AGGREGATION     | 矩形 `[...]`    | 聚合       |
 * | ENRICHMENT      | 矩形 `[...]`    | 数据增强   |
 * | TRADE_ACTION    | 圆形 `((...))`  | 交易动作   |
 * | 其他             | 矩形 `[...]`    | 通用       |
 *
 * ### 使用示例
 * ```kotlin
 * val mermaid = TopologyMermaidVisualizer.pipelineToMermaid(pipeline)
 * println(mermaid)  // 粘贴到 https://mermaid.live 渲染
 * ```
 */
object TopologyMermaidVisualizer {

    // ════════════════════════════════════════════════════
    // Pipeline → Mermaid
    // ════════════════════════════════════════════════════

    /**
     * 将单个 Pipeline 转为 Mermaid 流程图。
     *
     * - 每个 Stage 用虚线分隔区域注释标记
     * - Node 按 NodeType 使用不同形状
     * - Link 转为有向边 `-->`
     *
     * @param pipeline 要可视化的 Pipeline
     * @return Mermaid 流程图字符串
     */
    fun pipelineToMermaid(pipeline: Pipeline): String {
        val sb = StringBuilder()
        sb.appendLine("graph LR")

        // 收集所有节点和边
        val nodeMap = mutableMapOf<String, PipelineNode<*, *>>()
        val edges = mutableListOf<EdgeInfo>()

        for (stage in pipeline.stages) {
            // Stage 分隔注释
            sb.appendLine("  %% Stage: ${stage.name}${if (stage.parallel) " (并行)" else ""}")

            for (linkList in stage.linkLists) {
                for (link in linkList.links) {
                    // 收集节点
                    if (!nodeMap.containsKey(link.from.nodeId)) {
                        nodeMap[link.from.nodeId] = link.from
                    }
                    if (!nodeMap.containsKey(link.to.nodeId)) {
                        nodeMap[link.to.nodeId] = link.to
                    }
                    // 收集边
                    edges.add(EdgeInfo(link.from.nodeId, link.to.nodeId, linkList.name))
                }
            }
        }

        // 输出节点声明（按出现顺序）
        sb.appendLine()
        for ((nodeId, node) in nodeMap) {
            val nodeDef = formatNodeDef(node.nodeId, node.nodeName, node.nodeType)
            sb.appendLine("  $nodeDef")
        }

        // 输出边
        sb.appendLine()
        for (edge in edges) {
            sb.appendLine("  ${edge.from} --> ${edge.to}")
        }

        // 输出 CSS 样式定义
        sb.appendLine()
        sb.append(mermaidStyles())

        return sb.toString()
    }

    // ════════════════════════════════════════════════════
    // UseCase（多 Pipeline）→ Mermaid
    // ════════════════════════════════════════════════════

    /**
     * 将 UseCase（多 Pipeline）转为 Mermaid 流程图。
     *
     * - 每个 Pipeline 用 `subgraph` 包裹
     * - `parallel=true` 的 Pipeline 标记为与前一个并行
     * - Pipeline 之间用虚线箭头表示执行顺序
     *
     * @param useCase UseCase 配置
     * @param pipelines 对应的 Pipeline 列表（顺序与 useCase.steps 中的 pipeline 引用一致）
     * @return Mermaid 流程图字符串
     */
    fun useCaseToMermaid(useCase: UseCaseConfig, pipelines: List<Pipeline>): String {
        val sb = StringBuilder()
        sb.appendLine("graph LR")

        // UseCase 标题注释
        sb.appendLine("  %% UseCase: ${useCase.id} — ${useCase.name}")
        if (useCase.description.isNotBlank()) {
            sb.appendLine("  %% ${useCase.description}")
        }
        sb.appendLine()

        // 为每个 Pipeline 生成 subgraph
        val pipelineEndNodes = mutableListOf<String>()  // 每个 Pipeline 的最后节点 ID（用于连接 Pipeline 间的边）
        val pipelineSteps = useCase.steps.filterIsInstance<com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser.StepRef.pipeline>()

        for ((index, pipeline) in pipelines.withIndex()) {
            val ref = pipelineSteps.getOrNull(index)
            val pipelineLabel = ref?.name?.ifBlank { pipeline.name } ?: pipeline.name
            val isParallel = ref?.parallel ?: false
            val subgraphId = "sub_${index}"

            sb.appendLine("  %% Pipeline ${index + 1}: $pipelineLabel${if (isParallel) " (与前一个并行)" else ""}")

            // subgraph 开始
            sb.appendLine("  subgraph $subgraphId [$pipelineLabel]")

            // 收集节点和边（带 pipeline 前缀避免 ID 冲突）
            val prefix = "p${index}_"
            val nodeMap = mutableMapOf<String, PipelineNode<*, *>>()
            val edges = mutableListOf<EdgeInfo>()
            var lastNodeId: String? = null

            for (stage in pipeline.stages) {
                for (linkList in stage.linkLists) {
                    for (link in linkList.links) {
                        val fromId = prefix + link.from.nodeId
                        val toId = prefix + link.to.nodeId
                        if (!nodeMap.containsKey(fromId)) {
                            nodeMap[fromId] = link.from
                        }
                        if (!nodeMap.containsKey(toId)) {
                            nodeMap[toId] = link.to
                        }
                        edges.add(EdgeInfo(fromId, toId, linkList.name))
                        lastNodeId = toId
                    }
                }
            }

            // 输出节点声明
            for ((nodeId, node) in nodeMap) {
                val nodeDef = formatNodeDef(nodeId, node.nodeName, node.nodeType)
                sb.appendLine("    $nodeDef")
            }

            // 输出边
            for (edge in edges) {
                sb.appendLine("    ${edge.from} --> ${edge.to}")
            }

            // subgraph 结束
            sb.appendLine("  end")
            sb.appendLine()

            if (lastNodeId != null) {
                pipelineEndNodes.add(lastNodeId)
            }
        }

        // Pipeline 间连接（串行的用虚线箭头）
        for (i in 1 until pipelineEndNodes.size) {
            val ref = pipelineSteps.getOrNull(i)
            val isParallel = ref?.parallel ?: false
            if (!isParallel && i > 0) {
                // 串行：前一个 Pipeline 的末尾 → 当前 Pipeline 的开头
                val prevEnd = pipelineEndNodes[i - 1]
                sb.appendLine("  %% 串行连接: Pipeline $i → Pipeline ${i + 1}")
                sb.appendLine("  $prevEnd -.-> ${pipelineEndNodes[i]}")
            }
        }

        // CSS 样式
        sb.appendLine()
        sb.append(mermaidStyles())

        return sb.toString()
    }

    // ════════════════════════════════════════════════════
    // Node 图例
    // ════════════════════════════════════════════════════

    /**
     * 列出所有可用 Node 类型的 Mermaid 图例。
     *
     * 生成一个包含所有 NodeType 形状示例的 Mermaid 图，
     * 方便用户了解不同节点类型的视觉表示。
     *
     * @return Mermaid 图例字符串
     */
    fun nodeLegend(): String {
        val sb = StringBuilder()
        sb.appendLine("graph LR")
        sb.appendLine("  %% Node 类型图例")
        sb.appendLine()

        val legends = listOf(
            Triple("legend_source", NodeType.DATA_SOURCE, "数据源 (DATA_SOURCE)"),
            Triple("legend_transform", NodeType.DATA_TRANSFORM, "数据转换 (DATA_TRANSFORM)"),
            Triple("legend_factor", NodeType.FACTOR_COMPUTE, "因子计算 (FACTOR_COMPUTE)"),
            Triple("legend_strategy", NodeType.STRATEGY, "策略筛选 (STRATEGY)"),
            Triple("legend_enrichment", NodeType.ENRICHMENT, "数据增强 (ENRICHMENT)"),
            Triple("legend_filter", NodeType.FILTER, "过滤 (FILTER)"),
            Triple("legend_ai", NodeType.AI_PREDICTION, "AI 预测 (AI_PREDICTION)"),
            Triple("legend_aggregation", NodeType.AGGREGATION, "聚合 (AGGREGATION)"),
            Triple("legend_trade", NodeType.TRADE_ACTION, "交易动作 (TRADE_ACTION)")
        )

        // 创建临时节点用于形状生成
        for ((id, type, label) in legends) {
            val nodeDef = formatNodeDef(id, label, type)
            sb.appendLine("  $nodeDef")
        }

        // 串联展示
        sb.appendLine()
        for (i in 0 until legends.size - 1) {
            sb.appendLine("  ${legends[i].first} --> ${legends[i + 1].first}")
        }

        sb.appendLine()
        sb.append(mermaidStyles())

        return sb.toString()
    }

    // ════════════════════════════════════════════════════
    // 内部工具
    // ════════════════════════════════════════════════════

    /** 边信息 */
    private data class EdgeInfo(
        val from: String,
        val to: String,
        val linkListName: String
    )

    /**
     * 根据 NodeType 和节点信息，生成完整的 Mermaid 节点定义行。
     *
     * 形状对应：
     * - DATA_SOURCE → 圆角矩形 `("text")`
     * - STRATEGY → 菱形 `{"text"}`
     * - FILTER → 矩形 `["text"]`
     * - AI_PREDICTION → 六边形 `{{"text"}}`
     * - TRADE_ACTION → 圆形 `(("text"))`
     * - 其他 → 矩形 `["text"]`
     *
     * @param nodeId Mermaid 节点 ID
     * @param nodeName 节点显示名称
     * @param type 节点类型
     * @return 完整的节点定义行，如 `n1("市场上下文"):::source`
     */
    private fun formatNodeDef(nodeId: String, nodeName: String, type: NodeType): String {
        val escapedName = escapeMermaidText(nodeName)
        val cssClass = typeToCssClass(type)
        val shapeContent = when (type) {
            NodeType.DATA_SOURCE -> "(\"$escapedName\")"        // 圆角矩形
            NodeType.STRATEGY -> "{\"$escapedName\"}"           // 菱形
            NodeType.FILTER -> "[\"$escapedName\"]"             // 矩形
            NodeType.AI_PREDICTION -> "{{\"$escapedName\"}}"    // 六边形
            NodeType.AGGREGATION -> "[\"$escapedName\"]"        // 矩形
            NodeType.ENRICHMENT -> "[\"$escapedName\"]"         // 矩形
            NodeType.DATA_TRANSFORM -> "[\"$escapedName\"]"     // 矩形
            NodeType.FACTOR_COMPUTE -> "[\"$escapedName\"]"     // 矩形
            NodeType.TRADE_ACTION -> "((\"$escapedName\"))"     // 圆形（sink）
        }
        return "$nodeId$shapeContent:::$cssClass"
    }

    /**
     * 转义 Mermaid 文本中的特殊字符。
     */
    private fun escapeMermaidText(text: String): String {
        return text.replace("\"", "#quot;")
            .replace("[", "#91;")
            .replace("]", "#93;")
            .replace("{", "#123;")
            .replace("}", "#125;")
    }

    /**
     * 根据 NodeType 返回 CSS class 名称。
     */
    private fun typeToCssClass(type: NodeType): String {
        return when (type) {
            NodeType.DATA_SOURCE -> "source"
            NodeType.STRATEGY -> "strategy"
            NodeType.FILTER -> "filter"
            NodeType.AI_PREDICTION -> "ai"
            NodeType.AGGREGATION -> "aggregation"
            NodeType.ENRICHMENT -> "enrichment"
            NodeType.DATA_TRANSFORM -> "transform"
            NodeType.FACTOR_COMPUTE -> "factor"
            NodeType.TRADE_ACTION -> "sink"
        }
    }

    /**
     * 生成 Mermaid CSS 样式定义（classDef）。
     */
    private fun mermaidStyles(): String {
        return """
            |  classDef source fill:#4CAF50,stroke:#2E7D32,color:#fff,rx:20px,ry:20px
            |  classDef strategy fill:#FF9800,stroke:#E65100,color:#fff
            |  classDef filter fill:#F44336,stroke:#B71C1C,color:#fff
            |  classDef ai fill:#9C27B0,stroke:#4A148C,color:#fff
            |  classDef aggregation fill:#2196F3,stroke:#0D47A1,color:#fff
            |  classDef enrichment fill:#00BCD4,stroke:#006064,color:#fff
            |  classDef transform fill:#607D8B,stroke:#263238,color:#fff
            |  classDef factor fill:#795548,stroke:#3E2723,color:#fff
            |  classDef sink fill:#E91E63,stroke:#880E4F,color:#fff
        """.trimMargin()
    }
}
