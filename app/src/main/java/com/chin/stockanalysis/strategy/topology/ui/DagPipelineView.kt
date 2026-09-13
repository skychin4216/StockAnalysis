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
 * ## DagPipelineView — DAG Pipeline 可视化 View
 *
 * 显示 Pipeline 的节点拓扑结构，支持：
 * - 双指缩放 (Pinch-to-Zoom)
 * - 单指拖动平移
 * - 点击节点显示详情
 *
 * 节点按层级排列，颜色表示执行状态：
 * - 灰色: 未执行
 * - 蓝色: 执行中
 * - 绿色: 成功
 * - 红色: 失败
 */
class DagPipelineView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ════════════════════════════════════════════════════
    // 数据模型
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
        PENDING,    // 未执行
        RUNNING,    // 执行中
        SUCCESS,    // 成功
        FAILED      // 失败
    }

    data class PipelineLink(
        val fromId: String,
        val toId: String
    )

    // ════════════════════════════════════════════════════
    // 画布状态
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
    // 手势检测
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
    // 绘画工具
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
    // 数据设置
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
    // 布局计算
    // ════════════════════════════════════════════════════

    private fun calculateLayout() {
        nodePositions.clear()
        if (nodes.isEmpty()) return

        val nodeWidth = 220f
        val nodeHeight = 90f
        val horizontalGap = 80f
        val verticalGap = 120f

        // 按层级分组
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
    // 绘制
    // ════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.translate(translateX + width / 2f, translateY + 100f)
        canvas.scale(currentScale, currentScale)

        // 绘制连线
        drawLinks(canvas)

        // 绘制节点
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

            // 贝塞尔曲线
            val path = Path()
            path.moveTo(startX, startY)
            val controlY = (startY + endY) / 2
            path.cubicTo(startX, controlY, endX, controlY, endX, endY)
            canvas.drawPath(path, linkPaint)

            // 箭头
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

            // 节点颜色
            val color = when (node.status) {
                NodeStatus.PENDING -> Color.parseColor("#455A64")
                NodeStatus.RUNNING -> Color.parseColor("#1565C0")
                NodeStatus.SUCCESS -> Color.parseColor("#2E7D32")
                NodeStatus.FAILED -> Color.parseColor("#C62828")
            }

            // 绘制节点背景
            nodePaint.color = color
            canvas.drawRoundRect(rect, 16f, 16f, nodePaint)

            // 绘制边框（选中时高亮）
            if (node.id == selectedNodeId) {
                canvas.drawRoundRect(rect, 16f, 16f, nodeBorderPaint)
            }

            // 绘制文字
            val textY = rect.centerY() - 10f
            canvas.drawText(node.name, rect.centerX(), textY, textPaint)

            // 绘制模组名称
            canvas.drawText(node.module, rect.centerX(), textY + 30f, moduleTextPaint)
        }
    }

    // ════════════════════════════════════════════════════
    // 触摸处理
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
        // 点击空白处取消选中
        selectedNodeId = null
        invalidate()
    }
}
