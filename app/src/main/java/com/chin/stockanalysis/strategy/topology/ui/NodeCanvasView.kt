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
 * ## NodeCanvasView — 全屏 2D 拓扑画布自定义 View
 *
 * 渲染节点（圆角矩形卡片）与连线（贝塞尔曲线 + 箭头），支持世界坐标系缩放平移：
 * 屏幕坐标 = 世界坐标 × [canvasState.scale] + [canvasState.translate]。
 *
 * ### 手势
 * | 手势 | 动作 |
 * |------|------|
 * | 点击节点 | 选中节点 → [NodeCanvasListener.onNodeSelected] |
 * | 长按节点 | 弹出节点菜单 → [NodeCanvasListener.onNodeLongPressed] |
 * | 从节点 A 拖拽到节点 B | 建立连线 → [NodeCanvasListener.onLinkCreated] |
 * | 点击连线 | 选中连线 → [NodeCanvasListener.onLinkTapped] |
 * | 点击空白 | 取消选中 → [NodeCanvasListener.onNodeSelected](null) |
 * | 空白处拖拽 | 平移画布 |
 * | 双指缩放 | 缩放画布（以手势焦点为中心） |
 *
 * 画布自身占满父容器（全屏），不再依赖外层 ScrollView。
 */
class NodeCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 画布状态（节点、连线、缩放、平移、选中态） */
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

    /** 缩放/平移以适配所有节点，并居中显示 */
    fun zoomToFit() {
        if (width <= 0 || height <= 0) {
            post { zoomToFit() }
            return
        }
        if (canvasState.nodes.isEmpty()) {
            canvasState.scale = 1f
            canvasState.translateX = 0f
            canvasState.translateY = 0f
            invalidate()
            return
        }
        val minX = canvasState.nodes.minOf { it.x }
        val maxX = canvasState.nodes.maxOf { it.x + it.width }
        val minY = canvasState.nodes.minOf { it.y }
        val maxY = canvasState.nodes.maxOf { it.y + it.height }
        val bw = maxX - minX
        val bh = maxY - minY
        if (bw <= 0f || bh <= 0f) return

        val pad = dp(120f)
        val availW = (width - pad * 2f).coerceAtLeast(1f)
        val availH = (height - pad * 2f).coerceAtLeast(1f)
        val s = minOf(availW / bw, availH / bh, 1.4f).coerceIn(0.25f, 3f)

        canvasState.scale = s
        canvasState.translateX = width / 2f - (minX + bw / 2f) * s
        canvasState.translateY = height / 2f - (minY + bh / 2f) * s
        invalidate()
    }

    /** 重置为原始比例并回到原点 */
    fun resetView() {
        canvasState.scale = 1f
        canvasState.translateX = 0f
        canvasState.translateY = 0f
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

    /** 将屏幕坐标转换为世界坐标 */
    private fun screenToWorldX(sx: Float): Float = (sx - canvasState.translateX) / canvasState.scale
    private fun screenToWorldY(sy: Float): Float = (sy - canvasState.translateY) / canvasState.scale

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    // ════════════════════════════════════════════════════
    // 测量 — 全屏填满父容器
    // ════════════════════════════════════════════════════

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(2000)
            else -> 2000
        }
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> MeasureSpec.getSize(heightMeasureSpec).coerceAtMost(1400)
            else -> 1400
        }
        setMeasuredDimension(w, h)
    }

    // ════════════════════════════════════════════════════
    // 绘制
    // ════════════════════════════════════════════════════

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(11f); isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6"); strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A78BFA"); strokeWidth = dp(2f)
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6"); style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E40AF"); textSize = dp(8f)
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#16203A"); strokeWidth = dp(0.5f)
    }
    private val accentGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B"); strokeWidth = dp(0.5f)
    }
    private val emptyHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B"); textSize = dp(13f); textAlign = Paint.Align.CENTER
    }
    private val emptyHintSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569"); textSize = dp(10f); textAlign = Paint.Align.CENTER
    }

    private val rectBuffer = RectF()
    private val pathBuffer = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0B1220"))
        drawGrid(canvas)

        if (canvasState.nodes.isEmpty()) {
            val cy = height / 2f - dp(24f)
            canvas.drawText("当前 Pipeline 为空", width / 2f, cy, emptyHintPaint)
            canvas.drawText("点击右上角 📦 打开节点模板面板，拖入节点开始搭建", width / 2f, cy + dp(28f), emptyHintSubPaint)
            return
        }

        val nodeMap = canvasState.nodes.associateBy { it.id }

        canvas.save()
        canvas.translate(canvasState.translateX, canvasState.translateY)
        canvas.scale(canvasState.scale, canvasState.scale)

        // 1. 先绘制连线（底层），避免节点遮挡
        for (link in canvasState.links) {
            val from = nodeMap[link.fromId] ?: continue
            val to = nodeMap[link.toId] ?: continue
            drawLink(canvas, from, to, link.label)
        }

        // 2. 再绘制节点
        for (node in canvasState.nodes) {
            drawNode(canvas, node, node.id == canvasState.selectedNodeId)
        }

        // 3. 绘制拖拽预览连线
        if (linkDragging && dragFromNode != null) {
            val from = dragFromNode!!
            val startX = from.x + from.width
            val startY = from.y + from.height / 2f
            val endX = currentDragWorldX
            val endY = currentDragWorldY
            pathBuffer.reset()
            pathBuffer.moveTo(startX, startY)
            val midX = (startX + endX) / 2f
            pathBuffer.cubicTo(midX, startY, midX, endY, endX, endY)
            canvas.drawPath(pathBuffer, previewPaint)
        }

        canvas.restore()
    }

    /** 绘制深色网格背景（屏幕空间，固定间距） */
    private fun drawGrid(canvas: Canvas) {
        val step = dp(56f)
        val majorStep = step * 5
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), accentGridPaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, accentGridPaint)
            y += step
        }
        x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += majorStep
        }
        y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += majorStep
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
        canvas.drawRoundRect(rectBuffer, dp(6f), dp(6f), nodePaint)

        // 选中边框
        if (selected) {
            nodeBorderPaint.color = Color.parseColor("#FFFFFF")
            nodeBorderPaint.strokeWidth = dp(2f)
            canvas.drawRoundRect(
                rectBuffer.left - dp(3f), rectBuffer.top - dp(3f),
                rectBuffer.right + dp(3f), rectBuffer.bottom + dp(3f),
                dp(8f), dp(8f), nodeBorderPaint
            )
            nodeBorderPaint.strokeWidth = dp(1.5f)
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
        val ctrlOffset = Math.max(dx * 0.5f, dp(24f))

        pathBuffer.reset()
        pathBuffer.moveTo(startX, startY)
        pathBuffer.cubicTo(
            startX + ctrlOffset, startY,
            endX - ctrlOffset, endY,
            endX, endY
        )
        canvas.drawPath(pathBuffer, linkPaint)

        // 箭头：从控制点2指向终点（水平向右进入）
        val arrowLen = dp(8f)
        val nx = 1f
        val ny = 0f

        val tipX = endX
        val tipY = endY
        val baseX = endX - nx * arrowLen
        val baseY = endY - ny * arrowLen
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
            val labelW = labelPaint.measureText(label) + dp(8f)
            val labelH = dp(14f)
            val labelRect = RectF(midX - labelW / 2f, midY - labelH, midX + labelW / 2f, midY)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFFFF"); style = Paint.Style.FILL
            }
            canvas.drawRoundRect(labelRect, dp(4f), dp(4f), bgPaint)
            canvas.drawText(label, midX, midY - dp(3f), labelPaint)
        }
    }

    // ════════════════════════════════════════════════════
    // 触摸处理
    // ════════════════════════════════════════════════════

    private val touchSlop = dp(12f)
    private var downX = 0f
    private var downY = 0f
    private var downNode: VisualNode? = null
    private var downLink: VisualLink? = null
    private var linkDragging = false
    private var dragFromNode: VisualNode? = null
    private var currentDragWorldX = 0f
    private var currentDragWorldY = 0f
    private var hasMoved = false

    /** 空白拖拽平移画布 */
    private var panning = false
    private var lastPanX = 0f
    private var lastPanY = 0f

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

    /** 双指缩放侦测器（以手势焦点为中心） */
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (canvasState.scale * detector.scaleFactor).coerceIn(0.25f, 3.0f)
                val fx = detector.focusX
                val fy = detector.focusY
                // 保持焦点下的世界坐标不变
                val wx = (fx - canvasState.translateX) / canvasState.scale
                val wy = (fy - canvasState.translateY) / canvasState.scale
                canvasState.scale = newScale
                canvasState.translateX = fx - wx * newScale
                canvasState.translateY = fy - wy * newScale
                invalidate()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
                linkDragging = false
                dragFromNode = null
                panning = false

                val wx = screenToWorldX(event.x)
                val wy = screenToWorldY(event.y)
                downNode = hitTestNode(wx, wy)
                downLink = if (downNode == null) hitTestLink(wx, wy) else null
                if (downNode != null) {
                    handler.postDelayed(longPressRunnable, 500)
                }
                lastPanX = event.x
                lastPanY = event.y
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
                        // 从节点拖拽 = 建立连线
                        linkDragging = true
                        dragFromNode = downNode
                        currentDragWorldX = screenToWorldX(event.x)
                        currentDragWorldY = screenToWorldY(event.y)
                    } else {
                        // 空白拖拽 = 平移画布
                        panning = true
                    }
                }
                if (linkDragging) {
                    currentDragWorldX = screenToWorldX(event.x)
                    currentDragWorldY = screenToWorldY(event.y)
                    invalidate()
                } else if (panning) {
                    canvasState.translateX += event.x - lastPanX
                    canvasState.translateY += event.y - lastPanY
                    lastPanX = event.x
                    lastPanY = event.y
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
                    val wx = screenToWorldX(event.x)
                    val wy = screenToWorldY(event.y)
                    val target = hitTestNode(wx, wy)
                    if (target != null && target.id != dragFromNode!!.id) {
                        listener?.onLinkCreated(dragFromNode!!.id, target.id)
                    }
                    linkDragging = false
                    dragFromNode = null
                    invalidate()
                    return true
                }
                if (!hasMoved) {
                    val wx = screenToWorldX(event.x)
                    val wy = screenToWorldY(event.y)
                    val tapped = hitTestNode(wx, wy)
                    if (tapped != null) {
                        canvasState.selectedNodeId = tapped.id
                        listener?.onNodeSelected(tapped)
                    } else {
                        val tappedLink = hitTestLink(wx, wy)
                        if (tappedLink != null) {
                            listener?.onLinkTapped(tappedLink)
                        } else {
                            canvasState.selectedNodeId = null
                            listener?.onNodeSelected(null)
                        }
                    }
                    invalidate()
                }
                panning = false
                linkDragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                linkDragging = false
                dragFromNode = null
                panning = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 命中测试（世界坐标）：返回包含触点的节点 */
    private fun hitTestNode(x: Float, y: Float): VisualNode? {
        for (i in canvasState.nodes.indices.reversed()) {
            val n = canvasState.nodes[i]
            if (x >= n.x && x <= n.x + n.width && y >= n.y && y <= n.y + n.height) {
                return n
            }
        }
        return null
    }

    /** 命中测试（世界坐标）：返回距离触点足够近的连线 */
    private fun hitTestLink(x: Float, y: Float): VisualLink? {
        val nodeMap = canvasState.nodes.associateBy { it.id }
        val threshold = dp(7f)
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
