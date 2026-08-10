package com.chin.stockanalysis.strategy.topology.ui

import com.chin.stockanalysis.strategy.topology.core.NodeType

/**
 * ## NodeGraphData — 可视化数据模型
 *
 * 定义画布上节点与连线的纯数据表示，不依赖 Android UI 组件，
 * 供 [NodeCanvasView] 渲染、[NodeAutoLayout] 布局、[TopologyEditorViewModel] 同步使用。
 *
 * ### 数据类
 * - [VisualNode]：画布节点，含世界座标 (x, y) 和语义类型 [NodeType]
 * - [VisualLink]：节点间有向连线
 * - [NodeCanvasState]：画布状态容器，持有节点/连线列表及视图变换参数
 */

/**
 * 画布上的可视化节点。
 *
 * @property id 节点唯一标识（与 EditableNode.id 对应）
 * @property module Node 的 module 类型（如 "market_context"）
 * @property name 人类可读名称（显示在节点卡片上）
 * @property nodeType 节点语义类型（决定著色）
 * @property x 世界座标 X（画布像素）
 * @property y 世界座标 Y（画布像素）
 * @property config 节点配置键值对
 * @property width 节点卡片宽度（像素）
 * @property height 节点卡片高度（像素）
 */
data class VisualNode(
    val id: String,
    val module: String,
    val name: String,
    val nodeType: NodeType,
    var x: Float,
    var y: Float,
    val config: Map<String, String> = emptyMap(),
    val width: Float = 240f,
    val height: Float = 84f,
    /** 所属 Pipeline 分组 ID（用于著色，空 = 按 NodeType 著色） */
    val pipelineGroupId: String = ""
)

/**
 * 画布上的可视化连线（有向边）。
 *
 * @property fromId 源节点 ID
 * @property toId 目标节点 ID
 * @property label 连线标签（可选，显示在连线中点）
 */
data class VisualLink(
    val fromId: String,
    val toId: String,
    val label: String = ""
)

/**
 * 画布状态容器。
 *
 * 持有当前画布上的节点列表、连线列表以及视图变换参数（缩放/平移）。
 * [NodeCanvasView] 直接读写此状态进行渲染。
 */
class NodeCanvasState {
    /** 画布上的所有节点 */
    val nodes: MutableList<VisualNode> = mutableListOf()

    /** 画布上的所有连线 */
    val links: MutableList<VisualLink> = mutableListOf()

    /** 当前缩放比例（1f = 原始大小） */
    var scale: Float = 1f

    /** X 轴平移量（像素） */
    var translateX: Float = 0f

    /** Y 轴平移量（像素） */
    var translateY: Float = 0f

    /** 当前选中的节点 ID（null 表示未选中） */
    var selectedNodeId: String? = null
}
