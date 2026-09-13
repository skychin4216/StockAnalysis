package com.chin.stockanalysis.strategy.topology.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser.EditableLink
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser.EditableLinkList
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser.EditableNode
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser.EditablePipeline
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser.EditableStage
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser

/**
 * ## TopologyEditorViewModel
 *
 * 管理 Pipeline 拓扑编辑器的状态，包括：
 * - 节点列表（增改删）
 * - 连线列表（增删）
 * - 选中节点
 * - 连线创建模式
 * - Pipeline 元数据（ID、名称、描述）
 *
 * ### 编辑器操作流程
 * 1. 从左侧调色板点击 module → 添加节点到画布
 * 2. 点击节点卡片 → 选中（右侧显示属性）
 * 3. 长按节点卡片 → 删除节点
 * 4. 点击「连线」按钮进入连线模式 → 依次点击两个节点建立连接
 * 5. 底部按钮：保存 XML、加载 XML、导出 Mermaid、执行 Pipeline
 *
 * ### 状态通知
 * 通过 [refreshTrigger] LiveData 通知 UI 刷新，UI 观察后调用各 getter 方法获取最新状态。
 */
class TopologyEditorViewModel : ViewModel() {

    /** 节点列表（画布上的所有节点） */
    private val _nodes = mutableListOf<EditableNode>()

    /** 连线列表（节点之间的有向边） */
    private val _links = mutableListOf<EditableLink>()

    /** 当前选中的节点 ID */
    private var _selectedNodeId: String? = null

    /** 连线模式下的源节点 ID（点击第一个节点后设置） */
    private var _linkSourceId: String? = null

    /** 节点 ID 计数器 */
    private var _nodeCounter = 0

    /** Pipeline 元数据 */
    var pipelineId: String = "new_pipeline"
    var pipelineName: String = "新建 Pipeline"
    var pipelineDescription: String = ""

    /** UI 刷新触发器（值变化时通知 UI 重绘） */
    private val _refreshTrigger = MutableLiveData(0)
    val refreshTrigger: LiveData<Int> = _refreshTrigger

    /** 状态消息（用于显示操作结果） */
    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    // ════════════════════════════════════════════════════
    // 节点操作
    // ════════════════════════════════════════════════════

    /**
     * 从调色板添加一个新节点到画布。
     *
     * @param module Node 的 module 类型（如 "market_context"、"stock_pool"）
     * @return 新创建的节点
     */
    fun addNode(module: String): EditableNode {
        _nodeCounter++
        val nodeId = "n$_nodeCounter"
        val node = EditableNode(id = nodeId, module = module)
        _nodes.add(node)
        _selectedNodeId = nodeId
        notifyRefresh()
        _statusMessage.value = "已添加节点: $nodeId ($module)"
        return node
    }

    /**
     * 删除指定节点及其所有相关连线。
     *
     * @param nodeId 要删除的节点 ID
     */
    fun removeNode(nodeId: String) {
        _nodes.removeAll { it.id == nodeId }
        _links.removeAll { it.fromId == nodeId || it.toId == nodeId }
        if (_selectedNodeId == nodeId) _selectedNodeId = null
        if (_linkSourceId == nodeId) _linkSourceId = null
        notifyRefresh()
        _statusMessage.value = "已删除节点: $nodeId"
    }

    /**
     * 选中节点（或取消选中）。
     * 如果处于连线模式，则完成连线创建。
     *
     * @param nodeId 要选中的节点 ID，null 表示取消选中
     */
    fun selectNode(nodeId: String?) {
        if (nodeId == null) {
            _selectedNodeId = null
            _linkSourceId = null
            notifyRefresh()
            return
        }

        // 如果在连线模式且点击了不同节点，创建连线
        if (_linkSourceId != null && _linkSourceId != nodeId) {
            val success = addLink(_linkSourceId!!, nodeId)
            _linkSourceId = null
            if (!success) {
                _statusMessage.value = "连线已存在或无效"
            }
            notifyRefresh()
            return
        }

        // 切换选中
        _selectedNodeId = if (_selectedNodeId == nodeId) null else nodeId
        _linkSourceId = null
        notifyRefresh()
    }

    /**
     * 进入/退出连线模式。
     * 第一次点击设置源节点，第二次点击完成连线。
     *
     * @paramNodeId 被点击的节点 ID
     */
    fun toggleLinkMode(nodeId: String) {
        if (_linkSourceId == null) {
            _linkSourceId = nodeId
            _statusMessage.value = "连线模式：请点击目标节点"
        } else if (_linkSourceId == nodeId) {
            _linkSourceId = null
            _statusMessage.value = "已取消连线模式"
        } else {
            addLink(_linkSourceId!!, nodeId)
            _linkSourceId = null
        }
        notifyRefresh()
    }

    /**
     * 获取当前选中的节点。
     */
    fun getSelectedNode(): EditableNode? {
        return _nodes.find { it.id == _selectedNodeId }
    }

    /**
     * 更新选中节点的配置。
     *
     * @param nodeId 节点 ID
     * @param config 新的配置键值对
     */
    fun updateNodeConfig(nodeId: String, config: Map<String, String>) {
        val index = _nodes.indexOfFirst { it.id == nodeId }
        if (index >= 0) {
            _nodes[index] = _nodes[index].copy(config = config)
            notifyRefresh()
            _statusMessage.value = "已更新节点配置: $nodeId"
        }
    }

    /**
     * 更新选中节点的 module 类型。
     */
    fun updateNodeModule(nodeId: String, module: String) {
        val index = _nodes.indexOfFirst { it.id == nodeId }
        if (index >= 0) {
            _nodes[index] = _nodes[index].copy(module = module)
            notifyRefresh()
        }
    }

    // ════════════════════════════════════════════════════
    // 连线操作
    // ════════════════════════════════════════════════════

    /**
     * 添加一条连线。
     *
     * @param fromId 源节点 ID
     * @param toId 目标节点 ID
     * @return true 如果添加成功，false 如果连线已存在或无效
     */
    fun addLink(fromId: String, toId: String): Boolean {
        if (fromId == toId) {
            _statusMessage.value = "不能连接到自身"
            return false
        }
        if (_links.any { it.fromId == fromId && it.toId == toId }) {
            _statusMessage.value = "连线已存在: $fromId → $toId"
            return false
        }
        _links.add(EditableLink(fromId, toId))
        _statusMessage.value = "已添加连线: $fromId → $toId"
        return true
    }

    /**
     * 删除一条连线。
     */
    fun removeLink(fromId: String, toId: String) {
        _links.removeAll { it.fromId == fromId && it.toId == toId }
        notifyRefresh()
        _statusMessage.value = "已删除连线: $fromId → $toId"
    }

    // ════════════════════════════════════════════════════
    // 序列化 / 反序列化
    // ════════════════════════════════════════════════════

    /**
     * 将当前编辑器状态序列化为 Pipeline XML 字符串。
     *
     * 所有连线放入一个默认 Stage 的单个 LinkList 中。
     */
    fun toXml(): String {
        val stage = EditableStage(
            name = "编辑器Stage",
            parallel = false,
            linkLists = listOf(
                EditableLinkList(
                    name = "主链路",
                    links = _links.toList()
                )
            )
        )
        val pipeline = EditablePipeline(
            id = pipelineId.ifBlank { "new_pipeline" },
            name = pipelineName.ifBlank { "新建 Pipeline" },
            description = pipelineDescription,
            nodes = _nodes.toList(),
            stages = listOf(stage)
        )
        return PipelineXmlParser.pipelineToXml(pipeline)
    }

    /**
     * 从 EditablePipeline 加载到编辑器状态。
     */
    fun loadFromEditable(editable: EditablePipeline) {
        _nodes.clear()
        _nodes.addAll(editable.nodes)
        _links.clear()
        // 从所有 Stage 的所有 LinkList 中提取连线
        for (stage in editable.stages) {
            for (ll in stage.linkLists) {
                _links.addAll(ll.links)
            }
        }
        pipelineId = editable.id
        pipelineName = editable.name
        pipelineDescription = editable.description
        _selectedNodeId = null
        _linkSourceId = null
        // 更新计数器以避免 ID 冲突
        _nodeCounter = _nodes.maxOfOrNull { extractNumber(it.id) } ?: 0
        notifyRefresh()
        _statusMessage.value = "已加载 Pipeline: ${editable.name} (${_nodes.size} 节点, ${_links.size} 连线)"
    }

    /**
     * 清空编辑器。
     */
    fun clear() {
        _nodes.clear()
        _links.clear()
        _selectedNodeId = null
        _linkSourceId = null
        _nodeCounter = 0
        pipelineId = "new_pipeline"
        pipelineName = "新建 Pipeline"
        pipelineDescription = ""
        notifyRefresh()
        _statusMessage.value = "已清空编辑器"
    }

    // ════════════════════════════════════════════════════
    // 状态查询（供 UI 读取）
    // ════════════════════════════════════════════════════

    fun getNodes(): List<EditableNode> = _nodes.toList()

    fun getLinks(): List<EditableLink> = _links.toList()

    fun getSelectedNodeId(): String? = _selectedNodeId

    fun getLinkSourceId(): String? = _linkSourceId

    fun isLinkMode(): Boolean = _linkSourceId != null

    /**
     * 获取指定节点的所有出边目标。
     */
    fun getDownstreamNodeIds(nodeId: String): List<String> {
        return _links.filter { it.fromId == nodeId }.map { it.toId }
    }

    /**
     * 获取指定节点的所有入边源头。
     */
    fun getUpstreamNodeIds(nodeId: String): List<String> {
        return _links.filter { it.toId == nodeId }.map { it.fromId }
    }

    // ════════════════════════════════════════════════════
    // 内部工具
    // ════════════════════════════════════════════════════

    private fun notifyRefresh() {
        _refreshTrigger.value = (_refreshTrigger.value ?: 0) + 1
    }

    private fun extractNumber(id: String): Int {
        return id.removePrefix("n").toIntOrNull() ?: 0
    }
}
