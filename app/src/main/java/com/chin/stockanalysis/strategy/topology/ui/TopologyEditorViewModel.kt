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
 * 管理 Pipeline 拓撲編輯器的狀態，包括：
 * - 節點列表（增改刪）
 * - 連線列表（增刪）
 * - 選中節點
 * - 連線創建模式
 * - Pipeline 元數據（ID、名稱、描述）
 *
 * ### 編輯器操作流程
 * 1. 從左側調色板點擊 module → 添加節點到畫布
 * 2. 點擊節點卡片 → 選中（右側顯示屬性）
 * 3. 長按節點卡片 → 刪除節點
 * 4. 點擊「連線」按鈕進入連線模式 → 依次點擊兩個節點建立連接
 * 5. 底部按鈕：保存 XML、加載 XML、導出 Mermaid、執行 Pipeline
 *
 * ### 狀態通知
 * 通過 [refreshTrigger] LiveData 通知 UI 刷新，UI 觀察後調用各 getter 方法獲取最新狀態。
 */
class TopologyEditorViewModel : ViewModel() {

    /** 節點列表（畫布上的所有節點） */
    private val _nodes = mutableListOf<EditableNode>()

    /** 連線列表（節點之間的有向邊） */
    private val _links = mutableListOf<EditableLink>()

    /** 當前選中的節點 ID */
    private var _selectedNodeId: String? = null

    /** 連線模式下的源節點 ID（點擊第一個節點後設置） */
    private var _linkSourceId: String? = null

    /** 節點 ID 計數器 */
    private var _nodeCounter = 0

    /** Pipeline 元數據 */
    var pipelineId: String = "new_pipeline"
    var pipelineName: String = "新建 Pipeline"
    var pipelineDescription: String = ""

    /** UI 刷新觸發器（值變化時通知 UI 重繪） */
    private val _refreshTrigger = MutableLiveData(0)
    val refreshTrigger: LiveData<Int> = _refreshTrigger

    /** 狀態消息（用於顯示操作結果） */
    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    // ════════════════════════════════════════════════════
    // 節點操作
    // ════════════════════════════════════════════════════

    /**
     * 從調色板添加一個新節點到畫布。
     *
     * @param module Node 的 module 類型（如 "market_context"、"stock_pool"）
     * @return 新創建的節點
     */
    fun addNode(module: String): EditableNode {
        _nodeCounter++
        val nodeId = "n$_nodeCounter"
        val node = EditableNode(id = nodeId, module = module)
        _nodes.add(node)
        _selectedNodeId = nodeId
        notifyRefresh()
        _statusMessage.value = "已添加節點: $nodeId ($module)"
        return node
    }

    /**
     * 刪除指定節點及其所有相關連線。
     *
     * @param nodeId 要刪除的節點 ID
     */
    fun removeNode(nodeId: String) {
        _nodes.removeAll { it.id == nodeId }
        _links.removeAll { it.fromId == nodeId || it.toId == nodeId }
        if (_selectedNodeId == nodeId) _selectedNodeId = null
        if (_linkSourceId == nodeId) _linkSourceId = null
        notifyRefresh()
        _statusMessage.value = "已刪除節點: $nodeId"
    }

    /**
     * 選中節點（或取消選中）。
     * 如果處於連線模式，則完成連線創建。
     *
     * @param nodeId 要選中的節點 ID，null 表示取消選中
     */
    fun selectNode(nodeId: String?) {
        if (nodeId == null) {
            _selectedNodeId = null
            _linkSourceId = null
            notifyRefresh()
            return
        }

        // 如果在連線模式且點擊了不同節點，創建連線
        if (_linkSourceId != null && _linkSourceId != nodeId) {
            val success = addLink(_linkSourceId!!, nodeId)
            _linkSourceId = null
            if (!success) {
                _statusMessage.value = "連線已存在或無效"
            }
            notifyRefresh()
            return
        }

        // 切換選中
        _selectedNodeId = if (_selectedNodeId == nodeId) null else nodeId
        _linkSourceId = null
        notifyRefresh()
    }

    /**
     * 進入/退出連線模式。
     * 第一次點擊設置源節點，第二次點擊完成連線。
     *
     * @paramNodeId 被點擊的節點 ID
     */
    fun toggleLinkMode(nodeId: String) {
        if (_linkSourceId == null) {
            _linkSourceId = nodeId
            _statusMessage.value = "連線模式：請點擊目標節點"
        } else if (_linkSourceId == nodeId) {
            _linkSourceId = null
            _statusMessage.value = "已取消連線模式"
        } else {
            addLink(_linkSourceId!!, nodeId)
            _linkSourceId = null
        }
        notifyRefresh()
    }

    /**
     * 獲取當前選中的節點。
     */
    fun getSelectedNode(): EditableNode? {
        return _nodes.find { it.id == _selectedNodeId }
    }

    /**
     * 更新選中節點的配置。
     *
     * @param nodeId 節點 ID
     * @param config 新的配置鍵值對
     */
    fun updateNodeConfig(nodeId: String, config: Map<String, String>) {
        val index = _nodes.indexOfFirst { it.id == nodeId }
        if (index >= 0) {
            _nodes[index] = _nodes[index].copy(config = config)
            notifyRefresh()
            _statusMessage.value = "已更新節點配置: $nodeId"
        }
    }

    /**
     * 更新選中節點的 module 類型。
     */
    fun updateNodeModule(nodeId: String, module: String) {
        val index = _nodes.indexOfFirst { it.id == nodeId }
        if (index >= 0) {
            _nodes[index] = _nodes[index].copy(module = module)
            notifyRefresh()
        }
    }

    // ════════════════════════════════════════════════════
    // 連線操作
    // ════════════════════════════════════════════════════

    /**
     * 添加一條連線。
     *
     * @param fromId 源節點 ID
     * @param toId 目標節點 ID
     * @return true 如果添加成功，false 如果連線已存在或無效
     */
    fun addLink(fromId: String, toId: String): Boolean {
        if (fromId == toId) {
            _statusMessage.value = "不能連接到自身"
            return false
        }
        if (_links.any { it.fromId == fromId && it.toId == toId }) {
            _statusMessage.value = "連線已存在: $fromId → $toId"
            return false
        }
        _links.add(EditableLink(fromId, toId))
        _statusMessage.value = "已添加連線: $fromId → $toId"
        return true
    }

    /**
     * 刪除一條連線。
     */
    fun removeLink(fromId: String, toId: String) {
        _links.removeAll { it.fromId == fromId && it.toId == toId }
        notifyRefresh()
        _statusMessage.value = "已刪除連線: $fromId → $toId"
    }

    // ════════════════════════════════════════════════════
    // 序列化 / 反序列化
    // ════════════════════════════════════════════════════

    /**
     * 將當前編輯器狀態序列化為 Pipeline XML 字符串。
     *
     * 所有連線放入一個默認 Stage 的單個 LinkList 中。
     */
    fun toXml(): String {
        val stage = EditableStage(
            name = "編輯器Stage",
            parallel = false,
            linkLists = listOf(
                EditableLinkList(
                    name = "主鏈路",
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
     * 從 EditablePipeline 加載到編輯器狀態。
     */
    fun loadFromEditable(editable: EditablePipeline) {
        _nodes.clear()
        _nodes.addAll(editable.nodes)
        _links.clear()
        // 從所有 Stage 的所有 LinkList 中提取連線
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
        // 更新計數器以避免 ID 衝突
        _nodeCounter = _nodes.maxOfOrNull { extractNumber(it.id) } ?: 0
        notifyRefresh()
        _statusMessage.value = "已加載 Pipeline: ${editable.name} (${_nodes.size} 節點, ${_links.size} 連線)"
    }

    /**
     * 清空編輯器。
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
        _statusMessage.value = "已清空編輯器"
    }

    // ════════════════════════════════════════════════════
    // 狀態查詢（供 UI 讀取）
    // ════════════════════════════════════════════════════

    fun getNodes(): List<EditableNode> = _nodes.toList()

    fun getLinks(): List<EditableLink> = _links.toList()

    fun getSelectedNodeId(): String? = _selectedNodeId

    fun getLinkSourceId(): String? = _linkSourceId

    fun isLinkMode(): Boolean = _linkSourceId != null

    /**
     * 獲取指定節點的所有出邊目標。
     */
    fun getDownstreamNodeIds(nodeId: String): List<String> {
        return _links.filter { it.fromId == nodeId }.map { it.toId }
    }

    /**
     * 獲取指定節點的所有入邊源頭。
     */
    fun getUpstreamNodeIds(nodeId: String): List<String> {
        return _links.filter { it.toId == nodeId }.map { it.fromId }
    }

    // ════════════════════════════════════════════════════
    // 內部工具
    // ════════════════════════════════════════════════════

    private fun notifyRefresh() {
        _refreshTrigger.value = (_refreshTrigger.value ?: 0) + 1
    }

    private fun extractNumber(id: String): Int {
        return id.removePrefix("n").toIntOrNull() ?: 0
    }
}
