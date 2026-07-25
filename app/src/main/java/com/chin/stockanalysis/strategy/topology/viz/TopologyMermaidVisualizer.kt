package com.chin.stockanalysis.strategy.topology.viz

import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.Pipeline
import com.chin.stockanalysis.strategy.topology.core.PipelineNode
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser.UseCaseConfig

/**
 * ## TopologyMermaidVisualizer
 *
 * 將 Pipeline / UseCase 拓撲結構轉換為 Mermaid 流程圖字符串，
 * 可直接粘貼到 Mermaid Live Editor 或 Markdown 中渲染。
 *
 * ### Node 形狀對應表
 * | NodeType        | Mermaid 形狀   | 語義       |
 * |-----------------|---------------|-----------|
 * | DATA_SOURCE     | 圓角矩形 `(...)` | 數據源     |
 * | STRATEGY        | 菱形 `{...}`    | 策略篩選   |
 * | FILTER          | 矩形 `[...]`    | 過濾       |
 * | AI_PREDICTION   | 六邊形 `{{...}}`| AI 預測   |
 * | AGGREGATION     | 矩形 `[...]`    | 聚合       |
 * | ENRICHMENT      | 矩形 `[...]`    | 數據增強   |
 * | TRADE_ACTION    | 圓形 `((...))`  | 交易動作   |
 * | 其他             | 矩形 `[...]`    | 通用       |
 *
 * ### 使用示例
 * ```kotlin
 * val mermaid = TopologyMermaidVisualizer.pipelineToMermaid(pipeline)
 * println(mermaid)  // 粘貼到 https://mermaid.live 渲染
 * ```
 */
object TopologyMermaidVisualizer {

    // ════════════════════════════════════════════════════
    // Pipeline → Mermaid
    // ════════════════════════════════════════════════════

    /**
     * 將單個 Pipeline 轉為 Mermaid 流程圖。
     *
     * - 每個 Stage 用虛線分隔區域註釋標記
     * - Node 按 NodeType 使用不同形狀
     * - Link 轉為有向邊 `-->`
     *
     * @param pipeline 要可視化的 Pipeline
     * @return Mermaid 流程圖字符串
     */
    fun pipelineToMermaid(pipeline: Pipeline): String {
        val sb = StringBuilder()
        sb.appendLine("graph LR")

        // 收集所有節點和邊
        val nodeMap = mutableMapOf<String, PipelineNode<*, *>>()
        val edges = mutableListOf<EdgeInfo>()

        for (stage in pipeline.stages) {
            // Stage 分隔註釋
            sb.appendLine("  %% Stage: ${stage.name}${if (stage.parallel) " (並行)" else ""}")

            for (linkList in stage.linkLists) {
                for (link in linkList.links) {
                    // 收集節點
                    if (!nodeMap.containsKey(link.from.nodeId)) {
                        nodeMap[link.from.nodeId] = link.from
                    }
                    if (!nodeMap.containsKey(link.to.nodeId)) {
                        nodeMap[link.to.nodeId] = link.to
                    }
                    // 收集邊
                    edges.add(EdgeInfo(link.from.nodeId, link.to.nodeId, linkList.name))
                }
            }
        }

        // 輸出節點聲明（按出現順序）
        sb.appendLine()
        for ((nodeId, node) in nodeMap) {
            val nodeDef = formatNodeDef(node.nodeId, node.nodeName, node.nodeType)
            sb.appendLine("  $nodeDef")
        }

        // 輸出邊
        sb.appendLine()
        for (edge in edges) {
            sb.appendLine("  ${edge.from} --> ${edge.to}")
        }

        // 輸出 CSS 樣式定義
        sb.appendLine()
        sb.append(mermaidStyles())

        return sb.toString()
    }

    // ════════════════════════════════════════════════════
    // UseCase（多 Pipeline）→ Mermaid
    // ════════════════════════════════════════════════════

    /**
     * 將 UseCase（多 Pipeline）轉為 Mermaid 流程圖。
     *
     * - 每個 Pipeline 用 `subgraph` 包裹
     * - `parallel=true` 的 Pipeline 標記為與前一個並行
     * - Pipeline 之間用虛線箭頭表示執行順序
     *
     * @param useCase UseCase 配置
     * @param pipelines 對應的 Pipeline 列表（順序與 useCase.steps 中的 pipeline 引用一致）
     * @return Mermaid 流程圖字符串
     */
    fun useCaseToMermaid(useCase: UseCaseConfig, pipelines: List<Pipeline>): String {
        val sb = StringBuilder()
        sb.appendLine("graph LR")

        // UseCase 標題註釋
        sb.appendLine("  %% UseCase: ${useCase.id} — ${useCase.name}")
        if (useCase.description.isNotBlank()) {
            sb.appendLine("  %% ${useCase.description}")
        }
        sb.appendLine()

        // 為每個 Pipeline 生成 subgraph
        val pipelineEndNodes = mutableListOf<String>()  // 每個 Pipeline 的最後節點 ID（用於連接 Pipeline 間的邊）
        val pipelineSteps = useCase.steps.filterIsInstance<com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser.StepRef.pipeline>()

        for ((index, pipeline) in pipelines.withIndex()) {
            val ref = pipelineSteps.getOrNull(index)
            val pipelineLabel = ref?.name?.ifBlank { pipeline.name } ?: pipeline.name
            val isParallel = ref?.parallel ?: false
            val subgraphId = "sub_${index}"

            sb.appendLine("  %% Pipeline ${index + 1}: $pipelineLabel${if (isParallel) " (與前一個並行)" else ""}")

            // subgraph 開始
            sb.appendLine("  subgraph $subgraphId [$pipelineLabel]")

            // 收集節點和邊（帶 pipeline 前綴避免 ID 衝突）
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

            // 輸出節點聲明
            for ((nodeId, node) in nodeMap) {
                val nodeDef = formatNodeDef(nodeId, node.nodeName, node.nodeType)
                sb.appendLine("    $nodeDef")
            }

            // 輸出邊
            for (edge in edges) {
                sb.appendLine("    ${edge.from} --> ${edge.to}")
            }

            // subgraph 結束
            sb.appendLine("  end")
            sb.appendLine()

            if (lastNodeId != null) {
                pipelineEndNodes.add(lastNodeId)
            }
        }

        // Pipeline 間連接（串行的用虛線箭頭）
        for (i in 1 until pipelineEndNodes.size) {
            val ref = pipelineSteps.getOrNull(i)
            val isParallel = ref?.parallel ?: false
            if (!isParallel && i > 0) {
                // 串行：前一個 Pipeline 的末尾 → 當前 Pipeline 的開頭
                val prevEnd = pipelineEndNodes[i - 1]
                sb.appendLine("  %% 串行連接: Pipeline $i → Pipeline ${i + 1}")
                sb.appendLine("  $prevEnd -.-> ${pipelineEndNodes[i]}")
            }
        }

        // CSS 樣式
        sb.appendLine()
        sb.append(mermaidStyles())

        return sb.toString()
    }

    // ════════════════════════════════════════════════════
    // Node 圖例
    // ════════════════════════════════════════════════════

    /**
     * 列出所有可用 Node 類型的 Mermaid 圖例。
     *
     * 生成一個包含所有 NodeType 形狀示例的 Mermaid 圖，
     * 方便用戶了解不同節點類型的視覺表示。
     *
     * @return Mermaid 圖例字符串
     */
    fun nodeLegend(): String {
        val sb = StringBuilder()
        sb.appendLine("graph LR")
        sb.appendLine("  %% Node 類型圖例")
        sb.appendLine()

        val legends = listOf(
            Triple("legend_source", NodeType.DATA_SOURCE, "數據源 (DATA_SOURCE)"),
            Triple("legend_transform", NodeType.DATA_TRANSFORM, "數據轉換 (DATA_TRANSFORM)"),
            Triple("legend_factor", NodeType.FACTOR_COMPUTE, "因子計算 (FACTOR_COMPUTE)"),
            Triple("legend_strategy", NodeType.STRATEGY, "策略篩選 (STRATEGY)"),
            Triple("legend_enrichment", NodeType.ENRICHMENT, "數據增強 (ENRICHMENT)"),
            Triple("legend_filter", NodeType.FILTER, "過濾 (FILTER)"),
            Triple("legend_ai", NodeType.AI_PREDICTION, "AI 預測 (AI_PREDICTION)"),
            Triple("legend_aggregation", NodeType.AGGREGATION, "聚合 (AGGREGATION)"),
            Triple("legend_trade", NodeType.TRADE_ACTION, "交易動作 (TRADE_ACTION)")
        )

        // 創建臨時節點用於形狀生成
        for ((id, type, label) in legends) {
            val nodeDef = formatNodeDef(id, label, type)
            sb.appendLine("  $nodeDef")
        }

        // 串聯展示
        sb.appendLine()
        for (i in 0 until legends.size - 1) {
            sb.appendLine("  ${legends[i].first} --> ${legends[i + 1].first}")
        }

        sb.appendLine()
        sb.append(mermaidStyles())

        return sb.toString()
    }

    // ════════════════════════════════════════════════════
    // 內部工具
    // ════════════════════════════════════════════════════

    /** 邊信息 */
    private data class EdgeInfo(
        val from: String,
        val to: String,
        val linkListName: String
    )

    /**
     * 根據 NodeType 和節點信息，生成完整的 Mermaid 節點定義行。
     *
     * 形狀對應：
     * - DATA_SOURCE → 圓角矩形 `("text")`
     * - STRATEGY → 菱形 `{"text"}`
     * - FILTER → 矩形 `["text"]`
     * - AI_PREDICTION → 六邊形 `{{"text"}}`
     * - TRADE_ACTION → 圓形 `(("text"))`
     * - 其他 → 矩形 `["text"]`
     *
     * @param nodeId Mermaid 節點 ID
     * @param nodeName 節點顯示名稱
     * @param type 節點類型
     * @return 完整的節點定義行，如 `n1("市場上下文"):::source`
     */
    private fun formatNodeDef(nodeId: String, nodeName: String, type: NodeType): String {
        val escapedName = escapeMermaidText(nodeName)
        val cssClass = typeToCssClass(type)
        val shapeContent = when (type) {
            NodeType.DATA_SOURCE -> "(\"$escapedName\")"        // 圓角矩形
            NodeType.STRATEGY -> "{\"$escapedName\"}"           // 菱形
            NodeType.FILTER -> "[\"$escapedName\"]"             // 矩形
            NodeType.AI_PREDICTION -> "{{\"$escapedName\"}}"    // 六邊形
            NodeType.AGGREGATION -> "[\"$escapedName\"]"        // 矩形
            NodeType.ENRICHMENT -> "[\"$escapedName\"]"         // 矩形
            NodeType.DATA_TRANSFORM -> "[\"$escapedName\"]"     // 矩形
            NodeType.FACTOR_COMPUTE -> "[\"$escapedName\"]"     // 矩形
            NodeType.TRADE_ACTION -> "((\"$escapedName\"))"     // 圓形（sink）
        }
        return "$nodeId$shapeContent:::$cssClass"
    }

    /**
     * 轉義 Mermaid 文本中的特殊字符。
     */
    private fun escapeMermaidText(text: String): String {
        return text.replace("\"", "#quot;")
            .replace("[", "#91;")
            .replace("]", "#93;")
            .replace("{", "#123;")
            .replace("}", "#125;")
    }

    /**
     * 根據 NodeType 返回 CSS class 名稱。
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
     * 生成 Mermaid CSS 樣式定義（classDef）。
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
