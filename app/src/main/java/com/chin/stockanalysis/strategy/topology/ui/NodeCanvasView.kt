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
 * ## NodeCanvasView — 2D 拓撲畫布自定義 View
 *
 * 負責渲染節點（圓角矩形卡片）與連線（貝塞爾曲線 + 箭頭），
 * 並處理觸摸手勢：點擊選中、長按彈出菜單、從節點拖拽到另一節點建立連線、點擊連線。
 *
 * ### 手勢
 * | 手勢 | 動作 |
 * |------|------|
 * | 點擊節點 | 選中節點 → [NodeCanvasListener.onNodeSelected] |
 * | 長按節點 | 彈出節點菜單 → [NodeCanvasListener.onNodeLongPressed] |
 * | 從節點 A 拖拽到節點 B | 建立連線 → [NodeCanvasListener.onLinkCreated] |
 * | 點擊連線 | 選中連線 → [NodeCanvasListener.onLinkTapped] |
 * | 點擊空白 | 取消選中 → [NodeCanvasListener.onNodeSelected](null) |
 *
 * 畫布尺寸根據節點座標自動計算（[onMeasure]），適配外層 ScrollView 滾動。
 */
class NodeCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 畫布狀態（節點、連線、選中態） */
    val canvasState = NodeCanvasState()

    /** 交互回調 */
    var listener: NodeCanvasListener? = null

    // ════════════════════════════════════════════════════
    // 監聽器介面
    // ════════════════════════════════════════════════════

    interface NodeCanvasListener {
        /** 節點被選中（node=null 表示取消選中） */
        fun onNodeSelected(node: VisualNode?)

        /** 節點被長按 */
        fun onNodeLongPressed(node: VisualNode)

        /** 從 fromId 節點拖拽到 toId 節點，請求建立連線 */
        fun onLinkCreated(fromId: String, toId: String)

        /** 連線被點擊 */
        fun onLinkTapped(link: VisualLink)
    }

    // ════════════════════════════════════════════════════
    // 數據設置
    // ════════════════════════════════════════════════════

    /** 替換畫布上的所有節點 */
    fun setNodes(nodes: List<VisualNode>) {
        canvasState.nodes.clear()
        canvasState.nodes.addAll(nodes)
        requestLayout()
        invalidate()
    }

    /** 替換畫布上的所有連線 */
    fun setLinks(links: List<VisualLink>) {
        canvasState.links.clear()
        canvasState.links.addAll(links)
        invalidate()
    }

    /** 縮放/平移以適配所有節點（當前實現為重新計算尺寸並重繪） */
    fun zoomToFit() {
        requestLayout()
        invalidate()
    }

    /**
     * 平移畫布使指定節點居中顯示。
     */
    fun panToNode(node: VisualNode) {
        canvasState.translateX = width / 2f - (node.x + node.width / 2f) * canvasState.scale
        canvasState.translateY = height / 2f - (node.y + node.height / 2f) * canvasState.scale
        canvasState.selectedNodeId = node.id
        invalidate()
    }

    // ════════════════════════════════════════════════════
    // 測量
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
    // 繪製
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
            canvas.drawText("畫布為空，從左側側邊欄添加節點", width / 2f, height / 2f, hint)
            return
        }

        val nodeMap = canvasState.nodes.associateBy { it.id }

        // 1. 先繪製節點（底層）
        for (node in canvasState.nodes) {
            drawNode(canvas, node, node.id == canvasState.selectedNodeId)
        }

        // 2. 再繪製連線（上層，確保不被節點覆蓋）
        for (link in canvasState.links) {
            val from = nodeMap[link.fromId] ?: continue
            val to = nodeMap[link.toId] ?: continue
            drawLink(canvas, from, to, link.label)
        }

        // 3. 繪製拖拽預覽連線
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

        // 選中邊框
        if (selected) {
            nodeBorderPaint.color = Color.parseColor("#0F172A")
            canvas.drawRoundRect(rectBuffer, 16f, 16f, nodeBorderPaint)
        }

        // 只顯示中文名稱（居中）
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
        // 起點：from 節點右邊緣中點；終點：to 節點左邊緣中點
        val startX = from.x + from.width
        val startY = from.y + from.height / 2f
        val endX = to.x
        val endY = to.y + to.height / 2f

        // 貝塞爾曲線控制點：水平距離的一半，保持曲線圓滑
        val dx = Math.abs(endX - startX)
        val ctrlOffset = Math.max(dx * 0.5f, 60f)

        pathBuffer.reset()
        pathBuffer.moveTo(startX, startY)
        pathBuffer.cubicTo(
            startX + ctrlOffset, startY,   // 控制點1：向右延伸
            endX - ctrlOffset, endY,       // 控制點2：從左側進入
            endX, endY
        )
        canvas.drawPath(pathBuffer, linkPaint)

        // 箭頭（三角形填充，更醒目）
        val angle = Math.atan2((endY - (endY)).toDouble(), (endX - (endX - ctrlOffset)).toDouble())
        // 簡化：直接根據進入方向畫箭頭
        val arrowLen = 20f
        val arrowAngle = 0.5 // 弧度
        // 箭頭方向：從控制點2指向終點
        val dirX = endX - (endX - ctrlOffset)
        val dirY = endY - endY
        val dirLen = Math.hypot(dirX.toDouble(), dirY.toDouble()).toFloat()
        val nx = if (dirLen > 0) dirX / dirLen else 1f
        val ny = if (dirLen > 0) dirY / dirLen else 0f

        // 箭頭三個點
        val tipX = endX
        val tipY = endY
        val baseX = endX - nx * arrowLen
        val baseY = endY - ny * arrowLen
        // 垂直於方向的偏移
        val perpX = -ny * arrowLen * 0.5f
        val perpY = nx * arrowLen * 0.5f

        val arrowPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseX + perpX, baseY + perpY)
            lineTo(baseX - perpX, baseY - perpY)
            close()
        }
        canvas.drawPath(arrowPath, arrowPaint)

        // 標籤
        if (label.isNotBlank()) {
            val midX = (startX + endX) / 2f
            val midY = (startY + endY) / 2f
            // 繪製標籤背景
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
    // 觸摸處理
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

    /** 雙指縮放偵測器 */
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
        // 雙指縮放：禁止父 ScrollView 攔截
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

    /** 命中測試：返回包含觸點的節點 */
    private fun hitTestNode(x: Float, y: Float): VisualNode? {
        // 從後往前測（後繪製的在上層）
        for (i in canvasState.nodes.indices.reversed()) {
            val n = canvasState.nodes[i]
            if (x >= n.x && x <= n.x + n.width && y >= n.y && y <= n.y + n.height) {
                return n
            }
        }
        return null
    }

    /** 命中測試：返回距離觸點足夠近的連線 */
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

    /** Pipeline 分組著色色板 — 高辨識度、深色背景友好 */
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

    /** 根據 group ID 確定性分配顏色（同 group 同色） */
    private fun colorForPipelineGroup(groupId: String): Int {
        val idx = kotlin.math.abs(groupId.hashCode()) % groupColorPalette.size
        return groupColorPalette[idx]
    }

    private fun truncate(s: String, max: Int): String {
        return if (s.length > max) s.substring(0, max) + "…" else s
    }
}
