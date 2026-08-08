package com.chin.stockanalysis.strategy.topology.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * ## DagPipelineView — DAG Pipeline 可視化 View
 *
 * 顯示 Pipeline 的節點拓撲結構，支持：
 * - 雙指縮放 (Pinch-to-Zoom)
 * - 單指拖動平移
 * - 點擊節點顯示詳情
 *
 * 節點按層級排列，顏色表示執行狀態：
 * - 灰色: 未執行
 * - 藍色: 執行中
 * - 綠色: 成功
 * - 紅色: 失敗
 */
class DagPipelineView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ════════════════════════════════════════════════════
    // 數據模型
    // ════════════════════════════════════════════════════

    data class PipelineNode(
        val id: String,
        val name: String,
        val module: String,
        val layer: Int,
        var status: NodeStatus = NodeStatus.PENDING,
        val detail: String = ""
    )

    enum class NodeStatus {
        PENDING,    // 未執行
        RUNNING,    // 執行中
        SUCCESS,    // 成功
        FAILED      // 失敗
    }

    data class PipelineLink(
        val fromId: String,
        val toId: String
    )

    // ════════════════════════════════════════════════════
    // 畫布狀態
    // ════════════════════════════════════════════════════

    private val nodes = mutableListOf<PipelineNode>()
    private val links = mutableListOf<PipelineLink>()
    private val nodePositions = mutableMapOf<String, RectF>()

    private var currentScale = 1.0f
    private var translateX = 0f
    private var translateY = 0f

    private var selectedNodeId: String? = null
    private var onNodeClickListener: ((PipelineNode) -> Unit)? = null

    // ════════════════════════════════════════════════════
    // 手勢檢測
    // ════════════════════════════════════════════════════

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale = (currentScale * detector.scaleFactor).coerceIn(0.3f, 3.0f)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                translateX -= distanceX
                translateY -= distanceY
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val tapX = (e.x - translateX) / currentScale
                val tapY = (e.y - translateY) / currentScale
                handleNodeTap(tapX, tapY)
                return true
            }
        }
    )

    // ════════════════════════════════════════════════════
    // 繪畫工具
    // ════════════════════════════════════════════════════

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#FFC107")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val moduleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        color = Color.parseColor("#B0BEC5")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }

    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#546E7A")
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#546E7A")
    }

    // ════════════════════════════════════════════════════
    // 數據設置
    // ════════════════════════════════════════════════════

    fun setPipelineData(
        nodeList: List<PipelineNode>,
        linkList: List<PipelineLink>
    ) {
        nodes.clear()
        nodes.addAll(nodeList)
        links.clear()
        links.addAll(linkList)
        calculateLayout()
        invalidate()
    }

    fun updateNodeStatus(nodeId: String, status: NodeStatus, detail: String = "") {
        val node = nodes.find { it.id == nodeId }
        if (node != null) {
            node.status = status
            invalidate()
        }
    }

    fun setOnNodeClickListener(listener: (PipelineNode) -> Unit) {
        onNodeClickListener = listener
    }

    fun resetView() {
        currentScale = 1.0f
        translateX = 0f
        translateY = 0f
        selectedNodeId = null
        invalidate()
    }

    // ════════════════════════════════════════════════════
    // 佈局計算
    // ════════════════════════════════════════════════════

    private fun calculateLayout() {
        nodePositions.clear()
        if (nodes.isEmpty()) return

        val nodeWidth = 220f
        val nodeHeight = 90f
        val horizontalGap = 80f
        val verticalGap = 120f

        // 按層級分組
        val layers = nodes.groupBy { it.layer }

        for ((layer, layerNodes) in layers) {
            val layerWidth = layerNodes.size * nodeWidth + (layerNodes.size - 1) * horizontalGap
            val startX = -layerWidth / 2 + nodeWidth / 2
            val y = layer * (nodeHeight + verticalGap)

            layerNodes.forEachIndexed { index, node ->
                val x = startX + index * (nodeWidth + horizontalGap)
                nodePositions[node.id] = RectF(
                    x - nodeWidth / 2,
                    y - nodeHeight / 2,
                    x + nodeWidth / 2,
                    y + nodeHeight / 2
                )
            }
        }
    }

    // ════════════════════════════════════════════════════
    // 繪製
    // ════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.translate(translateX + width / 2f, translateY + 100f)
        canvas.scale(currentScale, currentScale)

        // 繪製連線
        drawLinks(canvas)

        // 繪製節點
        drawNodes(canvas)

        canvas.restore()
    }

    private fun drawLinks(canvas: Canvas) {
        for (link in links) {
            val fromRect = nodePositions[link.fromId] ?: continue
            val toRect = nodePositions[link.toId] ?: continue

            val startX = fromRect.centerX()
            val startY = fromRect.bottom
            val endX = toRect.centerX()
            val endY = toRect.top

            // 貝塞爾曲線
            val path = Path()
            path.moveTo(startX, startY)
            val controlY = (startY + endY) / 2
            path.cubicTo(startX, controlY, endX, controlY, endX, endY)
            canvas.drawPath(path, linkPaint)

            // 箭頭
            val arrowSize = 15f
            val arrowPath = Path()
            arrowPath.moveTo(endX, endY)
            arrowPath.lineTo(endX - arrowSize, endY - arrowSize)
            arrowPath.lineTo(endX + arrowSize, endY - arrowSize)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
        }
    }

    private fun drawNodes(canvas: Canvas) {
        for (node in nodes) {
            val rect = nodePositions[node.id] ?: continue

            // 節點顏色
            val color = when (node.status) {
                NodeStatus.PENDING -> Color.parseColor("#455A64")
                NodeStatus.RUNNING -> Color.parseColor("#1565C0")
                NodeStatus.SUCCESS -> Color.parseColor("#2E7D32")
                NodeStatus.FAILED -> Color.parseColor("#C62828")
            }

            // 繪製節點背景
            nodePaint.color = color
            canvas.drawRoundRect(rect, 16f, 16f, nodePaint)

            // 繪製邊框（選中時高亮）
            if (node.id == selectedNodeId) {
                canvas.drawRoundRect(rect, 16f, 16f, nodeBorderPaint)
            }

            // 繪製文字
            val textY = rect.centerY() - 10f
            canvas.drawText(node.name, rect.centerX(), textY, textPaint)

            // 繪製模組名稱
            canvas.drawText(node.module, rect.centerX(), textY + 30f, moduleTextPaint)
        }
    }

    // ════════════════════════════════════════════════════
    // 觸摸處理
    // ════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleNodeTap(x: Float, y: Float) {
        for ((id, rect) in nodePositions) {
            if (rect.contains(x, y)) {
                val node = nodes.find { it.id == id }
                if (node != null) {
                    selectedNodeId = id
                    onNodeClickListener?.invoke(node)
                    invalidate()
                    return
                }
            }
        }
        // 點擊空白處取消選中
        selectedNodeId = null
        invalidate()
    }
}
