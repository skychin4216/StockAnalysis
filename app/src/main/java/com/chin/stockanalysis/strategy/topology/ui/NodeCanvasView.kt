package com.chin.stockanalysis.strategy.topology.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.chin.stockanalysis.strategy.topology.core.NodeType

/**
 * ## NodeCanvasView — 2D 拓扑画布自定义 View
 *
 * 负责渲染节点（圆角矩形卡片）与连线（贝塞尔曲线 + 箭头），
 * 并处理触摸手势：点击选中、长按弹出菜单、从节点拖拽到另一节点建立连线、点击连线。
 *
 * ### 手势
 * | 手势 | 动作 |
 * |------|------|
 * | 点击节点 | 选中节点 → [NodeCanvasListener.onNodeSelected] |
 * | 长按节点 | 弹出节点菜单 → [NodeCanvasListener.onNodeLongPressed] |
 * | 从节点 A 拖拽到节点 B | 建立连线 → [NodeCanvasListener.onLinkCreated] |
 * | 点击连线 | 选中连线 → [NodeCanvasListener.onLinkTapped] |
 * | 点击空白 | 取消选中 → [NodeCanvasListener.onNodeSelected](null) |
 *
 * 画布尺寸根据节点座标自动计算（[onMeasure]），适配外层 ScrollView 滚动。
 */
class NodeCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 画布状态（节点、连线、选中态） */
    val canvasState = NodeCanvasState()

    /** 交互回调 */
    var listener: NodeCanvasListener? = null

    // ════════════════════════════════════════════════════
    // 监听器介面
    // ════════════════════════════════════════════════════

    interface NodeCanvasListener {
        /** 节点被选中（node=null 表示取消选中） */
        fun onNodeSelected(node: VisualNode?)

        /** 节点被长按 */
        fun onNodeLongPressed(node: VisualNode)

        /** 从 fromId 节点拖拽到 toId 节点，请求建立连线 */
        fun onLinkCreated(fromId: String, toId: String)

        /** 连线被点击 */
        fun onLinkTapped(link: VisualLink)
    }

    // ════════════════════════════════════════════════════
    // 数据设置
    // ════════════════════════════════════════════════════

    /** 替换画布上的所有节点 */
    fun setNodes(nodes: List<VisualNode>) {
        canvasState.nodes.clear()
        canvasState.nodes.addAll(nodes)
        requestLayout()
        invalidate()
    }

    /** 替换画布上的所有连线 */
    fun setLinks(links: List<VisualLink>) {
        canvasState.links.clear()
        canvasState.links.addAll(links)
        invalidate()
    }

    /** 缩放/平移以适配所有节点（当前实现为重新计算尺寸并重绘） */
    fun zoomToFit() {
        requestLayout()
        invalidate()
    }

    /**
     * 平移画布使指定节点居中显示。
     */
    fun panToNode(node: VisualNode) {
        canvasState.translateX = width / 2f - (node.x + node.width / 2f) * canvasState.scale
        canvasState.translateY = height / 2f - (node.y + node.height / 2f) * canvasState.scale
        canvasState.selectedNodeId = node.id
        invalidate()
    }

    // ════════════════════════════════════════════════════
    // 测量
    // ════════════════════════════════════════════════════

    private val padding = 48f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var contentW = 0
        var contentH = 0
        for (n in canvasState.nodes) {
            contentW = maxOf(contentW, (n.x + n.width + padding).toInt())
            contentH = maxOf(contentH, (n.y + n.height + padding).toInt())
        }
        if (contentW == 0) contentW = 600
        if (contentH == 0) contentH = 400

        val w = resolveSize(contentW, widthMeasureSpec)
        val h = resolveSize(contentH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ════════════════════════════════════════════════════
    // 绘制
    // ════════════════════════════════════════════════════

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6"); strokeWidth = 8f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C3AED"); strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6"); strokeWidth = 8f; style = Paint.Style.FILL_AND_STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E40AF"); textSize = 22f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private val rectBuffer = RectF()
    private val pathBuffer = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (canvasState.nodes.isEmpty()) {
            val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#CBD5E1"); textSize = 32f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("画布为空，从左侧侧边栏添加节点", width / 2f, height / 2f, hint)
            return
        }

        val nodeMap = canvasState.nodes.associateBy { it.id }

        // 1. 先绘制节点（底层）
        for (node in canvasState.nodes) {
            drawNode(canvas, node, node.id == canvasState.selectedNodeId)
        }

        // 2. 再绘制连线（上层，确保不被节点覆盖）
        for (link in canvasState.links) {
            val from = nodeMap[link.fromId] ?: continue
            val to = nodeMap[link.toId] ?: continue
            drawLink(canvas, from, to, link.label)
        }

        // 3. 绘制拖拽预览连线
        if (linkDragging && dragFromNode != null) {
            val from = dragFromNode!!
            val startX = from.x + from.width
            val startY = from.y + from.height / 2f
            val endX = currentDragX
            val endY = currentDragY
            pathBuffer.reset()
            pathBuffer.moveTo(startX, startY)
            val midX = (startX + endX) / 2f
            pathBuffer.cubicTo(midX, startY, midX, endY, endX, endY)
            canvas.drawPath(pathBuffer, previewPaint)
        }
    }

    private fun drawNode(canvas: Canvas, node: VisualNode, selected: Boolean) {
        rectBuffer.set(node.x, node.y, node.x + node.width, node.y + node.height)
        val bgColor = if (node.pipelineGroupId.isNotBlank()) {
            colorForPipelineGroup(node.pipelineGroupId)
        } else {
            colorForType(node.nodeType)
        }
        nodePaint.color = bgColor
        canvas.drawRoundRect(rectBuffer, 16f, 16f, nodePaint)

        // 选中边框
        if (selected) {
            nodeBorderPaint.color = Color.parseColor("#0F172A")
            canvas.drawRoundRect(rectBuffer, 16f, 16f, nodeBorderPaint)
        }

        // 只显示中文名称（居中）
        val displayName = node.name.ifBlank { node.module }
        val textY = node.y + node.height / 2f - (titlePaint.ascent() + titlePaint.descent()) / 2f
        canvas.drawText(
            truncate(displayName, 14),
            node.x + node.width / 2f,
            textY,
            titlePaint
        )
    }

    private fun drawLink(canvas: Canvas, from: VisualNode, to: VisualNode, label: String) {
        // 起点：from 节点右边缘中点；终点：to 节点左边缘中点
        val startX = from.x + from.width
        val startY = from.y + from.height / 2f
        val endX = to.x
        val endY = to.y + to.height / 2f

        // 贝塞尔曲线控制点：水平距离的一半，保持曲线圆滑
        val dx = Math.abs(endX - startX)
        val ctrlOffset = Math.max(dx * 0.5f, 60f)

        pathBuffer.reset()
        pathBuffer.moveTo(startX, startY)
        pathBuffer.cubicTo(
            startX + ctrlOffset, startY,   // 控制点1：向右延伸
            endX - ctrlOffset, endY,       // 控制点2：从左侧进入
            endX, endY
        )
        canvas.drawPath(pathBuffer, linkPaint)

        // 箭头（三角形填充，更醒目）
        val angle = Math.atan2((endY - (endY)).toDouble(), (endX - (endX - ctrlOffset)).toDouble())
        // 简化：直接根据进入方向画箭头
        val arrowLen = 20f
        val arrowAngle = 0.5 // 弧度
        // 箭头方向：从控制点2指向终点
        val dirX = endX - (endX - ctrlOffset)
        val dirY = endY - endY
        val dirLen = Math.hypot(dirX.toDouble(), dirY.toDouble()).toFloat()
        val nx = if (dirLen > 0) dirX / dirLen else 1f
        val ny = if (dirLen > 0) dirY / dirLen else 0f

        // 箭头三个点
        val tipX = endX
        val tipY = endY
        val baseX = endX - nx * arrowLen
        val baseY = endY - ny * arrowLen
        // 垂直于方向的偏移
        val perpX = -ny * arrowLen * 0.5f
        val perpY = nx * arrowLen * 0.5f

        val arrowPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseX + perpX, baseY + perpY)
            lineTo(baseX - perpX, baseY - perpY)
            close()
        }
        canvas.drawPath(arrowPath, arrowPaint)

        // 标签
        if (label.isNotBlank()) {
            val midX = (startX + endX) / 2f
            val midY = (startY + endY) / 2f
            // 绘制标签背景
            val labelW = labelPaint.measureText(label) + 16f
            val labelH = 32f
            val labelRect = RectF(midX - labelW / 2f, midY - labelH, midX + labelW / 2f, midY)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFFFF"); style = Paint.Style.FILL
            }
            canvas.drawRoundRect(labelRect, 8f, 8f, bgPaint)
            canvas.drawText(label, midX, midY - 10f, labelPaint)
        }
    }

    // ════════════════════════════════════════════════════
    // 触摸处理
    // ════════════════════════════════════════════════════

    private val touchSlop = 16f
    private var downX = 0f
    private var downY = 0f
    private var downNode: VisualNode? = null
    private var downLink: VisualLink? = null
    private var linkDragging = false
    private var dragFromNode: VisualNode? = null
    private var currentDragX = 0f
    private var currentDragY = 0f
    private var hasMoved = false

    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        if (!hasMoved && downNode != null && !linkDragging) {
            longPressFired = true
            canvasState.selectedNodeId = downNode!!.id
            invalidate()
            listener?.onNodeLongPressed(downNode!!)
        }
    }

    /** 双指缩放侦测器 */
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (canvasState.scale * detector.scaleFactor).coerceIn(0.3f, 3.0f)
                canvasState.scale = newScale
                requestLayout()
                invalidate()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 双指缩放：禁止父 ScrollView 拦截
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount >= 2) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                hasMoved = false
                longPressFired = false
                downNode = hitTestNode(event.x, event.y)
                downLink = if (downNode == null) hitTestLink(event.x, event.y) else null
                linkDragging = false
                dragFromNode = null
                if (downNode != null) {
                    handler.postDelayed(longPressRunnable, 500)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (!hasMoved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    hasMoved = true
                    handler.removeCallbacks(longPressRunnable)
                    if (downNode != null) {
                        linkDragging = true
                        dragFromNode = downNode
                        currentDragX = event.x
                        currentDragY = event.y
                    }
                }
                if (linkDragging) {
                    currentDragX = event.x
                    currentDragY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (longPressFired) {
                    longPressFired = false
                    return true
                }
                if (linkDragging && dragFromNode != null) {
                    val target = hitTestNode(event.x, event.y)
                    if (target != null && target.id != dragFromNode!!.id) {
                        listener?.onLinkCreated(dragFromNode!!.id, target.id)
                    }
                    linkDragging = false
                    dragFromNode = null
                    invalidate()
                    return true
                }
                if (!hasMoved) {
                    val tapped = hitTestNode(event.x, event.y)
                    if (tapped != null) {
                        canvasState.selectedNodeId = tapped.id
                        listener?.onNodeSelected(tapped)
                    } else {
                        val tappedLink = hitTestLink(event.x, event.y)
                        if (tappedLink != null) {
                            listener?.onLinkTapped(tappedLink)
                        } else {
                            canvasState.selectedNodeId = null
                            listener?.onNodeSelected(null)
                        }
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                linkDragging = false
                dragFromNode = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 命中测试：返回包含触点的节点 */
    private fun hitTestNode(x: Float, y: Float): VisualNode? {
        // 从后往前测（后绘制的在上层）
        for (i in canvasState.nodes.indices.reversed()) {
            val n = canvasState.nodes[i]
            if (x >= n.x && x <= n.x + n.width && y >= n.y && y <= n.y + n.height) {
                return n
            }
        }
        return null
    }

    /** 命中测试：返回距离触点足够近的连线 */
    private fun hitTestLink(x: Float, y: Float): VisualLink? {
        val nodeMap = canvasState.nodes.associateBy { it.id }
        val threshold = 18f
        for (link in canvasState.links) {
            val from = nodeMap[link.fromId] ?: continue
            val to = nodeMap[link.toId] ?: continue
            val sx = from.x + from.width
            val sy = from.y + from.height / 2f
            val ex = to.x
            val ey = to.y + to.height / 2f
            if (distToSegment(x, y, sx, sy, ex, ey) < threshold) {
                return link
            }
        }
        return null
    }

    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return Math.hypot((px - x1).toDouble(), (py - y1).toDouble()).toFloat()
        var t = ((px - x1) * dx + (py - y1) * dy) / len2
        t = t.coerceIn(0f, 1f)
        val cx = x1 + t * dx
        val cy = y1 + t * dy
        return Math.hypot((px - cx).toDouble(), (py - cy).toDouble()).toFloat()
    }

    // ════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════

    private fun colorForType(type: NodeType): Int {
        return when (type) {
            NodeType.DATA_SOURCE -> Color.parseColor("#059669")
            NodeType.STRATEGY -> Color.parseColor("#7C3AED")
            NodeType.FILTER -> Color.parseColor("#DC2626")
            NodeType.AI_PREDICTION -> Color.parseColor("#2563EB")
            NodeType.ENRICHMENT -> Color.parseColor("#0891B2")
            NodeType.AGGREGATION -> Color.parseColor("#4F46E5")
            NodeType.TRADE_ACTION -> Color.parseColor("#D97706")
            NodeType.FACTOR_COMPUTE -> Color.parseColor("#0D9488")
            NodeType.DATA_TRANSFORM -> Color.parseColor("#6B7280")
        }
    }

    /** Pipeline 分组著色色板 — 高辨识度、深色背景友好 */
    private val groupColorPalette = intArrayOf(
        0xFF6366F1.toInt(), // Indigo
        0xFFEC4899.toInt(), // Pink
        0xFFF59E0B.toInt(), // Amber
        0xFF10B981.toInt(), // Emerald
        0xFF8B5CF6.toInt(), // Violet
        0xFFEF4444.toInt(), // Red
        0xFF06B6D4.toInt(), // Cyan
        0xFF84CC16.toInt(), // Lime
        0xFFF97316.toInt(), // Orange
        0xFF14B8A6.toInt()  // Teal
    )

    /** 根据 group ID 确定性分配颜色（同 group 同色） */
    private fun colorForPipelineGroup(groupId: String): Int {
        val idx = kotlin.math.abs(groupId.hashCode()) % groupColorPalette.size
        return groupColorPalette[idx]
    }

    private fun truncate(s: String, max: Int): String {
        return if (s.length > max) s.substring(0, max) + "…" else s
    }
}
