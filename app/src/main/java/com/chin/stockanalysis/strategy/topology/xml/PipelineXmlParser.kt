package com.chin.stockanalysis.strategy.topology.xml

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.topology.core.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * ## Pipeline XML 解析器
 *
 * 将 XML 文件解析为 Pipeline 对象。
 * XML 格式参考高通 Camera Pipeline 的拓扑描述方式。
 *
 * ### XML Schema
 * ```xml
 * <pipeline id="screening" name="量化选股" version="1">
 *   <stages>
 *     <stage name="数据准备" parallel="true">
 *       <linkList name="市场上下文">
 *         <link from="n1" fromPort="out" to="n2" toPort="in" />
 *       </linkList>
 *       <linkList name="股票池">
 *         <link from="n2" fromPort="out" to="n3" toPort="in" />
 *       </linkList>
 *     </stage>
 *     <stage name="策略筛选" parallel="true">
 *       <linkList name="均线金叉">
 *         <link from="n3" fromPort="out" to="n4" toPort="in" />
 *       </linkList>
 *       <linkList name="放量突破">
 *         <link from="n3" fromPort="out" to="n5" toPort="in" />
 *       </linkList>
 *     </stage>
 *   </stages>
 *   <nodes>
 *     <node id="n1" module="market_context" />
 *     <node id="n2" module="stock_pool" />
 *     <node id="n3" module="stock_pool" />
 *     <node id="n4" module="strategy:ma_golden_cross" />
 *     <node id="n5" module="strategy:volume_break" />
 *   </nodes>
 * </pipeline>
 * ```
 *
 * ### UseCase XML Schema（多 Pipeline，推荐）
 * ```xml
 * <usecase id="short_term" name="短线量化">
 *   <description>短线量化选股流程</description>
 *   <pipelines>
 *     <pipeline ref="usecases/data_prep_pipeline.xml" parallel="false" name="数据准备" />
 *     <pipeline ref="usecases/strategy_screening_pipeline.xml" parallel="true" name="策略筛选" />
 *     <pipeline ref="usecases/ai_trade_pipeline.xml" parallel="false" name="AI+交易" />
 *   </pipelines>
 *   <config>
 *     <param name="orderType" value="ShortTermQuant" />
 *     <param name="maxHoldings" value="3" />
 *   </config>
 * </usecase>
 * ```
 *
 * ### 向后兼容：单 Pipeline 格式
 * ```xml
 * <usecase id="short_term" name="短线量化">
 *   <description>短线量化选股流程</description>
 *   <pipeline ref="usecases/short_term_pipeline.xml" />
 *   <config>
 *     <param name="orderType" value="ShortTermQuant" />
 *     <param name="maxHoldings" value="3" />
 *   </config>
 * </usecase>
 * ```
 */
object PipelineXmlParser {

    private const val TAG = "PipelineXmlParser"

    // ════════════════════════════════════════════════════
    // Pipeline XML → Pipeline 对象
    // ════════════════════════════════════════════════════

    /**
     * 解析 Pipeline XML 字符串为 Pipeline 对象。
     */
    fun parsePipeline(xml: String, context: Context): Pipeline? {
        return try {
            val parser = newParser(xml)

            var pipelineId = ""
            var pipelineName = ""
            var pipelineDesc = ""
            var version = 1

            val nodes = mutableMapOf<String, PipelineNode<*, *>>()  // nodeId → Node
            val stages = mutableListOf<Pipeline.Stage>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "pipeline" -> {
                            pipelineId = parser.getAttributeValue(null, "id") ?: ""
                            pipelineName = parser.getAttributeValue(null, "name") ?: ""
                            pipelineDesc = parser.getAttributeValue(null, "description") ?: ""
                            version = parser.getAttributeValue(null, "version")?.toIntOrNull() ?: 1
                        }
                        "nodes" -> parseNodes(parser, context, nodes)
                        "stages" -> parseStages(parser, nodes, stages)
                    }
                }
                eventType = parser.next()
            }

            if (pipelineId.isBlank()) {
                Log.e(TAG, "Pipeline XML 缺少 id")
                return null
            }

            Pipeline(id = pipelineId, name = pipelineName, description = pipelineDesc,
                version = version, stages = stages)
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline XML 解析失败: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 <nodes> 区块，填充 nodeId → Node 映射。
     */
    private fun parseNodes(parser: XmlPullParser, context: Context, nodes: MutableMap<String, PipelineNode<*, *>>) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "nodes")) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "node") {
                val nodeId = parser.getAttributeValue(null, "id") ?: continue
                val module = parser.getAttributeValue(null, "module") ?: continue
                val config = parseConfigParams(parser)

                val node = NodeRegistry.createNode(module, config, context)
                if (node != null) {
                    nodes[nodeId] = node
                } else {
                    Log.e(TAG, "Node 创建失败: id=$nodeId, module=$module")
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * 解析 <stages> 区块，生成 Stage 列表。
     */
    private fun parseStages(parser: XmlPullParser, nodes: Map<String, PipelineNode<*, *>>, stages: MutableList<Pipeline.Stage>) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "stages")) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "stage") {
                val stageName = parser.getAttributeValue(null, "name") ?: continue
                val parallel = parser.getAttributeValue(null, "parallel")?.toBooleanStrictOrNull() ?: false

                val linkLists = mutableListOf<LinkList>()
                parseLinkLists(parser, nodes, linkLists)

                stages.add(Pipeline.Stage(
                    name = stageName,
                    linkLists = linkLists,
                    parallel = parallel
                ))
            }
            eventType = parser.next()
        }
    }

    /**
     * 解析 <linkList> 区块，生成 LinkList 列表。
     */
    private fun parseLinkLists(parser: XmlPullParser, nodes: Map<String, PipelineNode<*, *>>, linkLists: MutableList<LinkList>) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "stage")) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "linkList") {
                val llName = parser.getAttributeValue(null, "name") ?: ""
                val llDesc = parser.getAttributeValue(null, "description") ?: ""

                val links = mutableListOf<Link<*, *>>()
                parseLinks(parser, nodes, links)

                linkLists.add(LinkList(name = llName, description = llDesc, links = links))
            }
            eventType = parser.next()
        }
    }

    /**
     * 解析 <link> 元素，生成 Link 对象。
     */
    private fun parseLinks(parser: XmlPullParser, nodes: Map<String, PipelineNode<*, *>>, links: MutableList<Link<*, *>>) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "linkList")) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "link") {
                val fromId = parser.getAttributeValue(null, "from") ?: continue
                val toId = parser.getAttributeValue(null, "to") ?: continue

                val fromNode = nodes[fromId]
                val toNode = nodes[toId]
                if (fromNode == null || toNode == null) {
                    Log.e(TAG, "Link 引用了不存在的 Node: from=$fromId(${fromNode != null}), to=$toId(${toNode != null})")
                    eventType = parser.next()
                    continue
                }

                links.add(Link(
                    from = fromNode,
                    to = toNode,
                    label = "$fromId → $toId"
                ))
            }
            eventType = parser.next()
        }
    }

    /**
     * 解析 <config> 区块中的 <param> 元素。
     */
    private fun parseConfigParams(parser: XmlPullParser): Map<String, String> {
        val config = mutableMapOf<String, String>()
        // 自闭合标签（如 <Node ... />）无子元素，直接返回
        if (parser.isEmptyElementTag) return config

        val depth = parser.depth
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            // 退出：回到同层或更外层的 END_TAG（不消费越界事件）
            if (eventType == XmlPullParser.END_TAG && parser.depth <= depth) break
            if (eventType == XmlPullParser.START_TAG && parser.name == "param") {
                val name = parser.getAttributeValue(null, "name") ?: ""
                val value = parser.getAttributeValue(null, "value") ?: ""
                if (name.isNotBlank()) config[name] = value
            }
            eventType = parser.next()
        }
        return config
    }

    // ════════════════════════════════════════════════════
    // UseCase XML → UseCaseConfig 对象
    // ════════════════════════════════════════════════════

    /**
     * UseCase 配置（从 XML 解析），支持 Pipeline 引用和直接 Node 定义。
     *
     * UseCase 的执行步骤分为「有序步骤列表」，每个步骤可以是：
     * - [StepRef.pipeline]：引用一个 Pipeline XML 文件
     * - [StepRef.node]：直接定义一个单独 Node（module + config）
     *
     * 步骤之间默认串行；当 [parallel] = true 时，与前一个步骤并行执行。
     */
    data class UseCaseConfig(
        val id: String,
        val name: String,
        val description: String = "",
        val steps: List<StepRef>,
        val config: Map<String, String> = emptyMap()
    )

    /**
     * UseCase 中的一个执行步骤。
     */
    sealed class StepRef {
        /** 引用一个 Pipeline XML 文件 */
        data class pipeline(
            val ref: String,
            val parallel: Boolean = false,
            val name: String = ""
        ) : StepRef()

        /** 直接定义一个单独 Node（不需要独立 Pipeline XML） */
        data class node(
            val id: String,
            val module: String,
            val config: Map<String, String> = emptyMap(),
            val parallel: Boolean = false
        ) : StepRef()
    }

    /**
     * 解析 UseCase XML 字符串。
     *
     * 支持三种格式：
     * 1. 新格式（推荐）：`<pipelines>` 内混合 `<pipeline ref="...">` 和 `<node id="..." module="...">`
     * 2. 旧格式兼容：`<pipeline ref="..." />` 不在 `<pipelines>` 包裹
     * 3. 直接 node：`<node id="..." module="..." />` 不在 `<pipelines>` 包裹
     *
     * ```xml
     * <usecase id="short_term" name="短线量化">
     *   <steps>
     *     <pipeline ref="data_prep.xml" parallel="false" name="数据准备" />
     *     <pipeline ref="strategy_screening.xml" parallel="true" name="策略筛选" />
     *     <pipeline ref="merge_boost.xml" parallel="false" name="合并加权" />
     *     <pipeline ref="ai_filter.xml" parallel="false" name="AI+主力过滤" />
     *   </steps>
     *   <config><param name="maxHoldings" value="3" /></config>
     * </usecase>
     * ```
     */
    fun parseUseCase(xml: String): UseCaseConfig? {
        return try {
            val parser = newParser(xml)

            var id = ""
            var name = ""
            var desc = ""
            val steps = mutableListOf<StepRef>()
            val config = mutableMapOf<String, String>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "usecase" -> {
                            id = parser.getAttributeValue(null, "id") ?: ""
                            name = parser.getAttributeValue(null, "name") ?: ""
                        }
                        "description" -> {
                            desc = parser.nextText().trim()
                        }
                        "steps", "pipelines" -> {
                            // 解析 <steps> 或 <pipelines> 内的步骤
                            val stepsDepth = parser.depth
                            var pe = parser.next()
                            while (!(pe == XmlPullParser.END_TAG && parser.depth == stepsDepth &&
                                (parser.name == "steps" || parser.name == "pipelines"))) {
                                if (pe == XmlPullParser.START_TAG) {
                                    when (parser.name) {
                                        "pipeline" -> {
                                            val ref = parser.getAttributeValue(null, "ref") ?: ""
                                            val parallel = parser.getAttributeValue(null, "parallel")?.toBooleanStrictOrNull() ?: false
                                            val pName = parser.getAttributeValue(null, "name") ?: ""
                                            if (ref.isNotBlank()) steps.add(StepRef.pipeline(ref, parallel, pName))
                                        }
                                        "node" -> {
                                            val nodeId = parser.getAttributeValue(null, "id") ?: ""
                                            val module = parser.getAttributeValue(null, "module") ?: ""
                                            val parallel = parser.getAttributeValue(null, "parallel")?.toBooleanStrictOrNull() ?: false
                                            val nodeConfig = parseConfigParams(parser)
                                            if (nodeId.isNotBlank() && module.isNotBlank()) {
                                                steps.add(StepRef.node(nodeId, module, nodeConfig, parallel))
                                            }
                                        }
                                    }
                                }
                                pe = parser.next()
                            }
                        }
                        // 向后兼容：顶级的 <pipeline ref="..."> 或 <node id="..." module="...">
                        "pipeline" -> {
                            val ref = parser.getAttributeValue(null, "ref") ?: ""
                            val parallel = parser.getAttributeValue(null, "parallel")?.toBooleanStrictOrNull() ?: false
                            val pName = parser.getAttributeValue(null, "name") ?: ""
                            if (ref.isNotBlank()) steps.add(StepRef.pipeline(ref, parallel, pName))
                        }
                        "node" -> {
                            val nodeId = parser.getAttributeValue(null, "id") ?: ""
                            val module = parser.getAttributeValue(null, "module") ?: ""
                            val parallel = parser.getAttributeValue(null, "parallel")?.toBooleanStrictOrNull() ?: false
                            val nodeConfig = parseConfigParams(parser)
                            if (nodeId.isNotBlank() && module.isNotBlank()) {
                                steps.add(StepRef.node(nodeId, module, nodeConfig, parallel))
                            }
                        }
                        "config" -> {
                            val depth = parser.depth
                            var ce = parser.next()
                            while (!(ce == XmlPullParser.END_TAG && parser.depth <= depth)) {
                                if (ce == XmlPullParser.START_TAG && parser.name == "param") {
                                    val pName = parser.getAttributeValue(null, "name") ?: ""
                                    val pValue = parser.getAttributeValue(null, "value") ?: ""
                                    if (pName.isNotBlank()) config[pName] = pValue
                                }
                                ce = parser.next()
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            if (id.isBlank()) {
                Log.e(TAG, "UseCase XML 缺少 id")
                return null
            }

            UseCaseConfig(id = id, name = name, description = desc, steps = steps, config = config)
        } catch (e: Exception) {
            Log.e(TAG, "UseCase XML 解析失败: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════════
    // V2: 高通风格 DAG Pipeline 解析（NodeList + Links）
    // ════════════════════════════════════════════════════

    /**
     * 检测 XML 是否为高通风格 V2 格式（含 <NodeList> 和 <Links> 标签）。
     */
    fun isDagPipelineXml(xml: String): Boolean {
        return xml.contains("<NodeList>") || xml.contains("<nodeList>")
    }

    /**
     * 解析高通风格 V2 DAG Pipeline XML 字符串。
     *
     * XML 格式（对应高通 Camera Pipeline XML）：
     * ```xml
     * <DagPipeline id="mid_term" name="中线量化" description="...">
     *   <PipelineName>MidTermPipeline</PipelineName>
     *   <NodeList>
     *     <Node>
     *       <nodeName>市场上下文</nodeName>
     *       <NodeId>n_ctx</NodeId>
     *       <module>market_context</module>
     *     </Node>
     *   </NodeList>
     *   <Links>
     *     <Link>
     *       <SourceNodeId>n_ctx</SourceNodeId>
     *       <SourcePortId>0</SourcePortId>
     *       <TargetNodeId>n_pool</TargetNodeId>
     *       <TargetPortId>0</TargetPortId>
     *     </Link>
     *   </Links>
     * </DagPipeline>
     * ```
     */
    fun parseDagPipeline(xml: String, context: Context): DagPipeline? {
        return try {
            val parser = newParser(xml)

            var pipelineId = ""
            var pipelineName = ""
            var pipelineDesc = ""

            val dagNodes = mutableListOf<DagNode>()
            val dagEdges = mutableListOf<DagEdge>()
            val pipelineGroups = mutableListOf<PipelineGroup>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "DagPipeline", "dagPipeline" -> {
                            pipelineId = parser.getAttributeValue(null, "id") ?: ""
                            pipelineName = parser.getAttributeValue(null, "name") ?: ""
                            pipelineDesc = parser.getAttributeValue(null, "description") ?: ""
                        }
                        "PipelineName" -> {
                            if (pipelineName.isBlank()) pipelineName = parser.nextText().trim()
                        }
                        // ── 子 Pipeline 分组块 ──
                        "Pipeline" -> {
                            val groupId = parser.getAttributeValue(null, "id") ?: ""
                            val groupName = parser.getAttributeValue(null, "name") ?: ""
                            val groupParams = parseConfigParams(parser)
                            if (groupId.isNotBlank()) {
                                val groupNodes = mutableListOf<DagNode>()
                                val groupEdges = mutableListOf<DagEdge>()
                                parsePipelineGroup(parser, context, groupId, groupName, groupParams, groupNodes, groupEdges)
                                dagNodes.addAll(groupNodes)
                                dagEdges.addAll(groupEdges)
                                pipelineGroups.add(PipelineGroup(
                                    id = groupId,
                                    name = groupName,
                                    nodeIds = groupNodes.map { it.nodeId }.toSet(),
                                    params = groupParams
                                ))
                            }
                        }
                        "NodeList", "nodeList" -> {
                            parseDagNodes(parser, context, dagNodes)
                        }
                        "Links", "links" -> {
                            parseDagEdges(parser, dagEdges)
                        }
                    }
                }
                eventType = parser.next()
            }

            if (pipelineId.isBlank() && dagNodes.isNotEmpty()) {
                pipelineId = "dag_${System.currentTimeMillis()}"
            }

            if (dagNodes.isEmpty()) {
                Log.e(TAG, "DAG Pipeline XML 无任何 Node")
                return null
            }

            Log.i(TAG, "DAG Pipeline 解析: $pipelineId ($pipelineName) — ${dagNodes.size} nodes, ${dagEdges.size} edges" +
                if (pipelineGroups.isNotEmpty()) ", ${pipelineGroups.size} groups" else "")

            DagPipeline(
                id = pipelineId,
                name = pipelineName,
                description = pipelineDesc,
                nodes = dagNodes,
                edges = dagEdges,
                pipelineGroups = pipelineGroups
            )
        } catch (e: Exception) {
            Log.e(TAG, "DAG Pipeline XML 解析失败: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 <Pipeline id="..." name="..."> 分组块。
     *
     * 内部包含 <NodeList> 和 <Links>，节点自动标记 pipelineGroup。
     * 支持 ${param} 模板变量替换。
     */
    private fun parsePipelineGroup(
        parser: XmlPullParser, context: Context,
        groupId: String, groupName: String, params: Map<String, String>,
        groupNodes: MutableList<DagNode>, groupEdges: MutableList<DagEdge>
    ) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth &&
            parser.name == "Pipeline") && eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "NodeList", "nodeList" -> {
                        parseDagNodes(parser, context, groupNodes, groupId, params)
                    }
                    "Links", "links" -> {
                        parseDagEdges(parser, groupEdges)
                    }
                }
            }
            eventType = parser.next()
        }
        // 标记 pipelineGroup
        for (i in groupNodes.indices) {
            val n = groupNodes[i]
            groupNodes[i] = n.copy(pipelineGroup = groupId)
        }
    }

    /**
     * 解析 <NodeList> 区块。
     *
     * 支持两种格式：
     * 1. 高通风格（嵌套标签）：
     *    <Node><nodeName>市场上下文</nodeName><NodeId>n_ctx</NodeId><module>market_context</module></Node>
     * 2. 属性风格（简写）：
     *    <Node id="n_ctx" name="市场上下文" module="market_context" />
     *
     * @param groupId 所属 Pipeline 分组 ID（空 = 未分组）
     * @param params 模板变量替换（${key} → value）
     */
    private fun parseDagNodes(
        parser: XmlPullParser, context: Context,
        dagNodes: MutableList<DagNode>,
        groupId: String = "",
        params: Map<String, String> = emptyMap()
    ) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth &&
            (parser.name == "NodeList" || parser.name == "nodeList")) &&
            eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && (parser.name == "Node" || parser.name == "node")) {
                // 先尝试属性风格
                val attrId = parser.getAttributeValue(null, "id")
                val attrName = parser.getAttributeValue(null, "name")
                val attrModule = parser.getAttributeValue(null, "module")
                val rawConfig = parseConfigParams(parser)
                // 模板变量替换
                val attrConfig = if (params.isNotEmpty()) {
                    rawConfig.mapValues { (_, v) -> resolveTemplateVars(v, params) }
                } else rawConfig

                if (!attrId.isNullOrBlank() && !attrModule.isNullOrBlank()) {
                    // 属性风格
                    val node = NodeRegistry.createNode(attrModule, attrConfig, context)
                    if (node != null) {
                        dagNodes.add(DagNode(attrId, attrName ?: attrModule, node, groupId))
                    } else {
                        Log.e(TAG, "DAG Node 创建失败: id=$attrId, module=$attrModule")
                    }
                } else {
                    // 高通风格（嵌套标签）
                    var nodeName = ""
                    var nodeId = ""
                    var module = ""
                    var nodeConfig = mutableMapOf<String, String>()

                    val nodeDepth = parser.depth
                    var ne = parser.next()
                    while (!(ne == XmlPullParser.END_TAG && parser.depth == nodeDepth &&
                        (parser.name == "Node" || parser.name == "node"))) {
                        if (ne == XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "nodeName", "name" -> nodeName = parser.nextText().trim()
                                "NodeId", "nodeId", "id" -> nodeId = parser.nextText().trim()
                                "module" -> module = parser.nextText().trim()
                                "config" -> {
                                    nodeConfig = parseConfigParams(parser).toMutableMap()
                                }
                            }
                        }
                        ne = parser.next()
                    }

                    // 模板变量替换
                    if (params.isNotEmpty()) {
                        nodeConfig = nodeConfig.mapValues { (_, v) -> resolveTemplateVars(v, params) }.toMutableMap()
                    }

                    if (nodeId.isNotBlank() && module.isNotBlank()) {
                        val node = NodeRegistry.createNode(module, nodeConfig, context)
                        if (node != null) {
                            dagNodes.add(DagNode(nodeId, nodeName.ifBlank { module }, node, groupId))
                        } else {
                            Log.e(TAG, "DAG Node 创建失败: id=$nodeId, module=$module")
                        }
                    }
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * 解析 config value 中的 ${param} 模板变量。
     * 例：resolveTemplateVars("${threshold}", mapOf("threshold" to "0.015")) → "0.015"
     */
    private fun resolveTemplateVars(value: String, params: Map<String, String>): String {
        if (!value.contains("\${")) return value
        var result = value
        for ((k, v) in params) {
            result = result.replace("\${$k}", v)
        }
        return result
    }

    /**
     * 解析 <Links> 区块。
     *
     * 支持两种格式：
     * 1. 高通风格（嵌套标签）：
     *    <Link><SourceNodeId>n_ctx</SourceNodeId><TargetNodeId>n_pool</TargetNodeId></Link>
     * 2. 属性风格（简写）：
     *    <Link source="n_ctx" target="n_pool" />
     */
    private fun parseDagEdges(parser: XmlPullParser, dagEdges: MutableList<DagEdge>) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth &&
            (parser.name == "Links" || parser.name == "links")) &&
            eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && (parser.name == "Link" || parser.name == "link")) {
                // 先尝试属性风格
                val attrSource = parser.getAttributeValue(null, "source") ?: parser.getAttributeValue(null, "from")
                val attrTarget = parser.getAttributeValue(null, "target") ?: parser.getAttributeValue(null, "to")
                val attrSourcePort = parser.getAttributeValue(null, "sourcePortId")?.toIntOrNull() ?: 0
                val attrTargetPort = parser.getAttributeValue(null, "targetPortId")?.toIntOrNull() ?: 0

                if (!attrSource.isNullOrBlank() && !attrTarget.isNullOrBlank()) {
                    dagEdges.add(DagEdge(attrSource, attrSourcePort, attrTarget, attrTargetPort))
                } else {
                    // 高通风格（嵌套标签）
                    var sourceId = ""
                    var targetId = ""
                    var sourcePort = 0
                    var targetPort = 0

                    val linkDepth = parser.depth
                    var le = parser.next()
                    while (!(le == XmlPullParser.END_TAG && parser.depth == linkDepth &&
                        (parser.name == "Link" || parser.name == "link"))) {
                        if (le == XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "SourceNodeId", "sourceNodeId", "source" -> sourceId = parser.nextText().trim()
                                "SourcePortId", "sourcePortId" -> sourcePort = parser.nextText().trim().toIntOrNull() ?: 0
                                "TargetNodeId", "targetNodeId", "target" -> targetId = parser.nextText().trim()
                                "TargetPortId", "targetPortId" -> targetPort = parser.nextText().trim().toIntOrNull() ?: 0
                            }
                        }
                        le = parser.next()
                    }

                    if (sourceId.isNotBlank() && targetId.isNotBlank()) {
                        dagEdges.add(DagEdge(sourceId, sourcePort, targetId, targetPort))
                    }
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * 从 assets 加载 DAG Pipeline XML，自动检测 V1/V2 格式。
     */
    fun loadDagPipelineFromAssets(context: Context, assetPath: String): DagPipeline? {
        return try {
            val xml = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            if (isDagPipelineXml(xml)) {
                parseDagPipeline(xml, context)
            } else {
                // V1 格式转为 V1 Pipeline，不适用于 DagPipeline
                Log.w(TAG, "$assetPath 不是 DAG 格式，跳过")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载 DAG Pipeline 失败: $assetPath — ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════════
    // 编辑器模型（用于 UI 编辑器的增改删操作）
    // ════════════════════════════════════════════════════

    /** 编辑器中的 Node 表示（不依赖具体 Node 实例） */
    data class EditableNode(
        val id: String,
        val module: String,
        val name: String = "",
        val config: Map<String, String> = emptyMap(),
        val pipelineGroup: String = ""
    )

    /** 编辑器中的 Link 表示 */
    data class EditableLink(
        val fromId: String,
        val toId: String
    )

    /** 编辑器中的 LinkList 表示 */
    data class EditableLinkList(
        val name: String,
        val description: String = "",
        val links: List<EditableLink>
    )

    /** 编辑器中的 Stage 表示 */
    data class EditableStage(
        val name: String,
        val parallel: Boolean = false,
        val linkLists: List<EditableLinkList>
    )

    /** 编辑器中的 Pipeline 表示 */
    data class EditablePipeline(
        val id: String,
        val name: String,
        val description: String = "",
        val version: Int = 1,
        val nodes: List<EditableNode>,
        val stages: List<EditableStage>
    )

    // ════════════════════════════════════════════════════
    // 反向序列化：对象 → XML（供编辑器保存使用）
    // ════════════════════════════════════════════════════

    /**
     * 将 EditablePipeline 序列化为 Pipeline XML 字符串。
     */
    fun pipelineToXml(p: EditablePipeline): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.appendLine("<pipeline id=\"${escapeXml(p.id)}\" name=\"${escapeXml(p.name)}\"" +
            (if (p.description.isNotBlank()) " description=\"${escapeXml(p.description)}\"" else "") +
            " version=\"${p.version}\">")
        sb.appendLine()

        // nodes
        sb.appendLine("  <nodes>")
        for (node in p.nodes) {
            if (node.config.isEmpty()) {
                sb.appendLine("    <node id=\"${escapeXml(node.id)}\" module=\"${escapeXml(node.module)}\" />")
            } else {
                sb.appendLine("    <node id=\"${escapeXml(node.id)}\" module=\"${escapeXml(node.module)}\">")
                sb.appendLine("      <config>")
                for ((k, v) in node.config) {
                    sb.appendLine("        <param name=\"${escapeXml(k)}\" value=\"${escapeXml(v)}\" />")
                }
                sb.appendLine("      </config>")
                sb.appendLine("    </node>")
            }
        }
        sb.appendLine("  </nodes>")
        sb.appendLine()

        // stages
        sb.appendLine("  <stages>")
        for (stage in p.stages) {
            sb.appendLine("    <stage name=\"${escapeXml(stage.name)}\" parallel=\"${stage.parallel}\">")
            for (ll in stage.linkLists) {
                val descAttr = if (ll.description.isNotBlank()) " description=\"${escapeXml(ll.description)}\"" else ""
                sb.appendLine("      <linkList name=\"${escapeXml(ll.name)}\"$descAttr>")
                for (link in ll.links) {
                    sb.appendLine("        <link from=\"${escapeXml(link.fromId)}\" to=\"${escapeXml(link.toId)}\" />")
                }
                sb.appendLine("      </linkList>")
            }
            sb.appendLine("    </stage>")
        }
        sb.appendLine("  </stages>")
        sb.appendLine("</pipeline>")
        return sb.toString()
    }

    /**
     * 将 UseCaseConfig 序列化为 UseCase XML 字符串。
     */
    fun useCaseToXml(config: UseCaseConfig): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.appendLine("<usecase id=\"${escapeXml(config.id)}\" name=\"${escapeXml(config.name)}\">")
        if (config.description.isNotBlank()) {
            sb.appendLine("  <description>${escapeXml(config.description)}</description>")
        }
        sb.appendLine("  <steps>")
        for (step in config.steps) {
            if (step is StepRef.pipeline) {
                val parallelAttr = " parallel=\"${step.parallel}\""
                val nameAttr = if (step.name.isNotBlank()) " name=\"${escapeXml(step.name)}\"" else ""
                sb.appendLine("    <pipeline ref=\"${escapeXml(step.ref)}\"$parallelAttr$nameAttr />")
            } else if (step is StepRef.node) {
                val nameAttr = " name=\"${escapeXml(step.id)}\""
                val moduleAttr = " module=\"${escapeXml(step.module)}\""
                val parallelAttr = " parallel=\"${step.parallel}\""
                sb.appendLine("    <node id=\"${escapeXml(step.id)}\"$moduleAttr$parallelAttr />")
            }
        }
        sb.appendLine("  </steps>")
        if (config.config.isNotEmpty()) {
            sb.appendLine("  <config>")
            for ((k, v) in config.config) {
                sb.appendLine("    <param name=\"${escapeXml(k)}\" value=\"${escapeXml(v)}\" />")
            }
            sb.appendLine("  </config>")
        }
        sb.appendLine("</usecase>")
        return sb.toString()
    }

    /**
     * 将 Pipeline 对象转为 EditablePipeline（供编辑器加载使用）。
     */
    fun pipelineToEditable(pipeline: Pipeline): EditablePipeline {
        val nodes = mutableMapOf<String, EditableNode>()
        val stages = pipeline.stages.map { stage ->
            EditableStage(
                name = stage.name,
                parallel = stage.parallel,
                linkLists = stage.linkLists.map { ll ->
                    EditableLinkList(
                        name = ll.name,
                        description = ll.description,
                        links = ll.links.map { link ->
                            // 收集 node 信息
                            if (!nodes.containsKey(link.from.nodeId)) {
                                nodes[link.from.nodeId] = EditableNode(
                                    id = link.from.nodeId,
                                    module = inferModule(link.from),
                                    name = link.from.nodeName,
                                    config = emptyMap()
                                )
                            }
                            if (!nodes.containsKey(link.to.nodeId)) {
                                nodes[link.to.nodeId] = EditableNode(
                                    id = link.to.nodeId,
                                    module = inferModule(link.to),
                                    name = link.to.nodeName,
                                    config = emptyMap()
                                )
                            }
                            EditableLink(link.from.nodeId, link.to.nodeId)
                        }
                    )
                }
            )
        }
        return EditablePipeline(
            id = pipeline.id,
            name = pipeline.name,
            description = pipeline.description,
            version = pipeline.version,
            nodes = nodes.values.toList(),
            stages = stages
        )
    }

    /**
     * 从 PipelineNode 推断 module 字符串。
     */
    private fun inferModule(node: PipelineNode<*, *>): String {
        return when (node.nodeType) {
            NodeType.STRATEGY -> {
                // StrategyNode 的 nodeId 格式为 "strategy_xxx"
                val strategyId = node.nodeId.removePrefix("strategy_")
                "strategy:$strategyId"
            }
            NodeType.DATA_SOURCE -> when {
                node.nodeId.contains("market") -> "market_context"
                node.nodeId.contains("pool") -> "stock_pool"
                else -> node.nodeId
            }
            NodeType.AGGREGATION -> "signal_merge"
            NodeType.ENRICHMENT -> "sector_boost"
            NodeType.FILTER -> when {
                node.nodeId.contains("smart") -> "smart_money_filter"
                node.nodeId.contains("main_board") -> "main_board_filter"
                else -> node.nodeId
            }
            NodeType.AI_PREDICTION -> "ai_predict"
            else -> node.nodeId
        }
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    // ════════════════════════════════════════════════════
    // 文件 I/O
    // ════════════════════════════════════════════════════

    fun loadPipelineFromAssets(context: Context, assetPath: String): Pipeline? {
        return try {
            val xml = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            parsePipeline(xml, context)
        } catch (e: Exception) {
            Log.e(TAG, "加载 Pipeline 失败: $assetPath — ${e.message}")
            null
        }
    }

    fun loadUseCaseFromAssets(context: Context, assetPath: String): UseCaseConfig? {
        return try {
            val xml = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            parseUseCase(xml)
        } catch (e: Exception) {
            Log.e(TAG, "加载 UseCase 失败: $assetPath — ${e.message}")
            null
        }
    }

    fun listAssets(context: Context, dir: String): List<String> {
        return try { context.assets.list(dir)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    // ════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════

    private fun newParser(xml: String): XmlPullParser {
        return XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser().also {
            it.setInput(StringReader(xml))
        }
    }
}
