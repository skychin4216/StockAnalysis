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
 * 將 XML 文件解析為 Pipeline 對象。
 * XML 格式參考高通 Camera Pipeline 的拓撲描述方式。
 *
 * ### XML Schema
 * ```xml
 * <pipeline id="screening" name="量化選股" version="1">
 *   <stages>
 *     <stage name="數據準備" parallel="true">
 *       <linkList name="市場上下文">
 *         <link from="n1" fromPort="out" to="n2" toPort="in" />
 *       </linkList>
 *       <linkList name="股票池">
 *         <link from="n2" fromPort="out" to="n3" toPort="in" />
 *       </linkList>
 *     </stage>
 *     <stage name="策略篩選" parallel="true">
 *       <linkList name="均線金叉">
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
 * ### UseCase XML Schema（多 Pipeline，推薦）
 * ```xml
 * <usecase id="short_term" name="短線量化">
 *   <description>短線量化選股流程</description>
 *   <pipelines>
 *     <pipeline ref="usecases/data_prep_pipeline.xml" parallel="false" name="數據準備" />
 *     <pipeline ref="usecases/strategy_screening_pipeline.xml" parallel="true" name="策略篩選" />
 *     <pipeline ref="usecases/ai_trade_pipeline.xml" parallel="false" name="AI+交易" />
 *   </pipelines>
 *   <config>
 *     <param name="orderType" value="ShortTermQuant" />
 *     <param name="maxHoldings" value="3" />
 *   </config>
 * </usecase>
 * ```
 *
 * ### 向後兼容：單 Pipeline 格式
 * ```xml
 * <usecase id="short_term" name="短線量化">
 *   <description>短線量化選股流程</description>
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
    // Pipeline XML → Pipeline 對象
    // ════════════════════════════════════════════════════

    /**
     * 解析 Pipeline XML 字符串為 Pipeline 對象。
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
            Log.e(TAG, "Pipeline XML 解析失敗: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 <nodes> 區塊，填充 nodeId → Node 映射。
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
                    Log.e(TAG, "Node 創建失敗: id=$nodeId, module=$module")
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * 解析 <stages> 區塊，生成 Stage 列表。
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
     * 解析 <linkList> 區塊，生成 LinkList 列表。
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
     * 解析 <link> 元素，生成 Link 對象。
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
     * 解析 <config> 區塊中的 <param> 元素。
     */
    private fun parseConfigParams(parser: XmlPullParser): Map<String, String> {
        val config = mutableMapOf<String, String>()
        // 自閉合標籤（如 <Node ... />）無子元素，直接返回
        if (parser.isEmptyElementTag) return config

        val depth = parser.depth
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            // 退出：回到同層或更外層的 END_TAG（不消費越界事件）
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
    // UseCase XML → UseCaseConfig 對象
    // ════════════════════════════════════════════════════

    /**
     * UseCase 配置（從 XML 解析），支持 Pipeline 引用和直接 Node 定義。
     *
     * UseCase 的執行步驟分為「有序步驟列表」，每個步驟可以是：
     * - [StepRef.pipeline]：引用一個 Pipeline XML 文件
     * - [StepRef.node]：直接定義一個單獨 Node（module + config）
     *
     * 步驟之間默認串行；當 [parallel] = true 時，與前一個步驟並行執行。
     */
    data class UseCaseConfig(
        val id: String,
        val name: String,
        val description: String = "",
        val steps: List<StepRef>,
        val config: Map<String, String> = emptyMap()
    )

    /**
     * UseCase 中的一個執行步驟。
     */
    sealed class StepRef {
        /** 引用一個 Pipeline XML 文件 */
        data class pipeline(
            val ref: String,
            val parallel: Boolean = false,
            val name: String = ""
        ) : StepRef()

        /** 直接定義一個單獨 Node（不需要獨立 Pipeline XML） */
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
     * 支持三種格式：
     * 1. 新格式（推薦）：`<pipelines>` 內混合 `<pipeline ref="...">` 和 `<node id="..." module="...">`
     * 2. 舊格式兼容：`<pipeline ref="..." />` 不在 `<pipelines>` 包裹
     * 3. 直接 node：`<node id="..." module="..." />` 不在 `<pipelines>` 包裹
     *
     * ```xml
     * <usecase id="short_term" name="短線量化">
     *   <steps>
     *     <pipeline ref="data_prep.xml" parallel="false" name="數據準備" />
     *     <pipeline ref="strategy_screening.xml" parallel="true" name="策略篩選" />
     *     <pipeline ref="merge_boost.xml" parallel="false" name="合併加權" />
     *     <pipeline ref="ai_filter.xml" parallel="false" name="AI+主力過濾" />
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
                            // 解析 <steps> 或 <pipelines> 內的步驟
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
                        // 向後兼容：頂級的 <pipeline ref="..."> 或 <node id="..." module="...">
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
            Log.e(TAG, "UseCase XML 解析失敗: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════════
    // V2: 高通風格 DAG Pipeline 解析（NodeList + Links）
    // ════════════════════════════════════════════════════

    /**
     * 檢測 XML 是否為高通風格 V2 格式（含 <NodeList> 和 <Links> 標籤）。
     */
    fun isDagPipelineXml(xml: String): Boolean {
        return xml.contains("<NodeList>") || xml.contains("<nodeList>")
    }

    /**
     * 解析高通風格 V2 DAG Pipeline XML 字符串。
     *
     * XML 格式（對應高通 Camera Pipeline XML）：
     * ```xml
     * <DagPipeline id="mid_term" name="中線量化" description="...">
     *   <PipelineName>MidTermPipeline</PipelineName>
     *   <NodeList>
     *     <Node>
     *       <nodeName>市場上下文</nodeName>
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

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "DagPipeline", "dagPipeline", "Pipeline" -> {
                            pipelineId = parser.getAttributeValue(null, "id") ?: ""
                            pipelineName = parser.getAttributeValue(null, "name") ?: ""
                            pipelineDesc = parser.getAttributeValue(null, "description") ?: ""
                        }
                        "PipelineName" -> {
                            if (pipelineName.isBlank()) pipelineName = parser.nextText().trim()
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
                Log.e(TAG, "DAG Pipeline XML 無任何 Node")
                return null
            }

            Log.i(TAG, "DAG Pipeline 解析: $pipelineId ($pipelineName) — ${dagNodes.size} nodes, ${dagEdges.size} edges")

            DagPipeline(
                id = pipelineId,
                name = pipelineName,
                description = pipelineDesc,
                nodes = dagNodes,
                edges = dagEdges
            )
        } catch (e: Exception) {
            Log.e(TAG, "DAG Pipeline XML 解析失敗: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 <NodeList> 區塊。
     *
     * 支持兩種格式：
     * 1. 高通風格（嵌套標籤）：
     *    <Node><nodeName>市場上下文</nodeName><NodeId>n_ctx</NodeId><module>market_context</module></Node>
     * 2. 屬性風格（簡寫）：
     *    <Node id="n_ctx" name="市場上下文" module="market_context" />
     */
    private fun parseDagNodes(parser: XmlPullParser, context: Context, dagNodes: MutableList<DagNode>) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth &&
            (parser.name == "NodeList" || parser.name == "nodeList")) &&
            eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && (parser.name == "Node" || parser.name == "node")) {
                // 先嘗試屬性風格
                val attrId = parser.getAttributeValue(null, "id")
                val attrName = parser.getAttributeValue(null, "name")
                val attrModule = parser.getAttributeValue(null, "module")
                val attrConfig = parseConfigParams(parser)

                if (!attrId.isNullOrBlank() && !attrModule.isNullOrBlank()) {
                    // 屬性風格
                    val node = NodeRegistry.createNode(attrModule, attrConfig, context)
                    if (node != null) {
                        dagNodes.add(DagNode(attrId, attrName ?: attrModule, node))
                    } else {
                        Log.e(TAG, "DAG Node 創建失敗: id=$attrId, module=$attrModule")
                    }
                } else {
                    // 高通風格（嵌套標籤）
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

                    if (nodeId.isNotBlank() && module.isNotBlank()) {
                        val node = NodeRegistry.createNode(module, nodeConfig, context)
                        if (node != null) {
                            dagNodes.add(DagNode(nodeId, nodeName.ifBlank { module }, node))
                        } else {
                            Log.e(TAG, "DAG Node 創建失敗: id=$nodeId, module=$module")
                        }
                    }
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * 解析 <Links> 區塊。
     *
     * 支持兩種格式：
     * 1. 高通風格（嵌套標籤）：
     *    <Link><SourceNodeId>n_ctx</SourceNodeId><TargetNodeId>n_pool</TargetNodeId></Link>
     * 2. 屬性風格（簡寫）：
     *    <Link source="n_ctx" target="n_pool" />
     */
    private fun parseDagEdges(parser: XmlPullParser, dagEdges: MutableList<DagEdge>) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth &&
            (parser.name == "Links" || parser.name == "links")) &&
            eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && (parser.name == "Link" || parser.name == "link")) {
                // 先嘗試屬性風格
                val attrSource = parser.getAttributeValue(null, "source") ?: parser.getAttributeValue(null, "from")
                val attrTarget = parser.getAttributeValue(null, "target") ?: parser.getAttributeValue(null, "to")
                val attrSourcePort = parser.getAttributeValue(null, "sourcePortId")?.toIntOrNull() ?: 0
                val attrTargetPort = parser.getAttributeValue(null, "targetPortId")?.toIntOrNull() ?: 0

                if (!attrSource.isNullOrBlank() && !attrTarget.isNullOrBlank()) {
                    dagEdges.add(DagEdge(attrSource, attrSourcePort, attrTarget, attrTargetPort))
                } else {
                    // 高通風格（嵌套標籤）
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
     * 從 assets 加載 DAG Pipeline XML，自動檢測 V1/V2 格式。
     */
    fun loadDagPipelineFromAssets(context: Context, assetPath: String): DagPipeline? {
        return try {
            val xml = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            if (isDagPipelineXml(xml)) {
                parseDagPipeline(xml, context)
            } else {
                // V1 格式轉為 V1 Pipeline，不適用於 DagPipeline
                Log.w(TAG, "$assetPath 不是 DAG 格式，跳過")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "加載 DAG Pipeline 失敗: $assetPath — ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════════
    // 編輯器模型（用於 UI 編輯器的增改刪操作）
    // ════════════════════════════════════════════════════

    /** 編輯器中的 Node 表示（不依賴具體 Node 實例） */
    data class EditableNode(
        val id: String,
        val module: String,
        val name: String = "",
        val config: Map<String, String> = emptyMap()
    )

    /** 編輯器中的 Link 表示 */
    data class EditableLink(
        val fromId: String,
        val toId: String
    )

    /** 編輯器中的 LinkList 表示 */
    data class EditableLinkList(
        val name: String,
        val description: String = "",
        val links: List<EditableLink>
    )

    /** 編輯器中的 Stage 表示 */
    data class EditableStage(
        val name: String,
        val parallel: Boolean = false,
        val linkLists: List<EditableLinkList>
    )

    /** 編輯器中的 Pipeline 表示 */
    data class EditablePipeline(
        val id: String,
        val name: String,
        val description: String = "",
        val version: Int = 1,
        val nodes: List<EditableNode>,
        val stages: List<EditableStage>
    )

    // ════════════════════════════════════════════════════
    // 反向序列化：對象 → XML（供編輯器保存使用）
    // ════════════════════════════════════════════════════

    /**
     * 將 EditablePipeline 序列化為 Pipeline XML 字符串。
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
     * 將 UseCaseConfig 序列化為 UseCase XML 字符串。
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
     * 將 Pipeline 對象轉為 EditablePipeline（供編輯器加載使用）。
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
     * 從 PipelineNode 推斷 module 字符串。
     */
    private fun inferModule(node: PipelineNode<*, *>): String {
        return when (node.nodeType) {
            NodeType.STRATEGY -> {
                // StrategyNode 的 nodeId 格式為 "strategy_xxx"
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
            Log.e(TAG, "加載 Pipeline 失敗: $assetPath — ${e.message}")
            null
        }
    }

    fun loadUseCaseFromAssets(context: Context, assetPath: String): UseCaseConfig? {
        return try {
            val xml = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            parseUseCase(xml)
        } catch (e: Exception) {
            Log.e(TAG, "加載 UseCase 失敗: $assetPath — ${e.message}")
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
