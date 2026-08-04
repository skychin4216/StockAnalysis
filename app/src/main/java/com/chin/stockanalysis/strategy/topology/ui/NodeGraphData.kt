package com.chin.stockanalysis.strategy.topology.ui

import com.chin.stockanalysis.strategy.topology.core.NodeType

/**
 * ## NodeGraphData — 可視化數據模型
 *
 * 定義畫布上節點與連線的純數據表示，不依賴 Android UI 組件，
 * 供 [NodeCanvasView] 渲染、[NodeAutoLayout] 佈局、[TopologyEditorViewModel] 同步使用。
 *
 * ### 數據類
 * - [VisualNode]：畫布節點，含世界座標 (x, y) 和語義類型 [NodeType]
 * - [VisualLink]：節點間有向連線
 * - [NodeCanvasState]：畫布狀態容器，持有節點/連線列表及視圖變換參數
 */

/**
 * 畫布上的可視化節點。
 *
 * @property id 節點唯一標識（與 EditableNode.id 對應）
 * @property module Node 的 module 類型（如 "market_context"）
 * @property name 人類可讀名稱（顯示在節點卡片上）
 * @property nodeType 節點語義類型（決定著色）
 * @property x 世界座標 X（畫布像素）
 * @property y 世界座標 Y（畫布像素）
 * @property config 節點配置鍵值對
 * @property width 節點卡片寬度（像素）
 * @property height 節點卡片高度（像素）
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
    /** 所屬 Pipeline 分組 ID（用於著色，空 = 按 NodeType 著色） */
    val pipelineGroupId: String = ""
)

/**
 * 畫布上的可視化連線（有向邊）。
 *
 * @property fromId 源節點 ID
 * @property toId 目標節點 ID
 * @property label 連線標籤（可選，顯示在連線中點）
 */
data class VisualLink(
    val fromId: String,
    val toId: String,
    val label: String = ""
)

/**
 * 畫布狀態容器。
 *
 * 持有當前畫布上的節點列表、連線列表以及視圖變換參數（縮放/平移）。
 * [NodeCanvasView] 直接讀寫此狀態進行渲染。
 */
class NodeCanvasState {
    /** 畫布上的所有節點 */
    val nodes: MutableList<VisualNode> = mutableListOf()

    /** 畫布上的所有連線 */
    val links: MutableList<VisualLink> = mutableListOf()

    /** 當前縮放比例（1f = 原始大小） */
    var scale: Float = 1f

    /** X 軸平移量（像素） */
    var translateX: Float = 0f

    /** Y 軸平移量（像素） */
    var translateY: Float = 0f

    /** 當前選中的節點 ID（null 表示未選中） */
    var selectedNodeId: String? = null
}
