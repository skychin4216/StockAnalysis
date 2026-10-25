package com.chin.stockanalysis.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chin.stockanalysis.strategy.ssa.SsaAlertStore
import kotlin.math.abs

/**
 * ## 短线预警系统悬浮窗视图（纯代码构建，无 XML）
 *
 * 两形态：
 * - **小浮窗（默认）**：窄卡片，单行 marquee 滚动显示各持仓股「名称 评分 建议」；
 * - **展开面板**：点击小浮窗切换，表格列出所有持仓股「名称 / 现价 / 涨跌幅 / 评分 / 建议」，
 *   顶部含【🔄 更新】按钮（回调 [onUpdate]）与【收起】按钮。
 *
 * 可拖拽（位移超过 [SLOP_DP] 视为拖拽，否则视为点击）。
 */
@SuppressLint("ViewConstructor")
class SsaAlertOverlayView(
    private val ctx: Context,
    private val onUpdate: () -> Unit
) {

    companion object {
        private const val SLOP_DP = 8
        private const val RED = "#D32F2F"
        private const val GREEN = "#2E7D32"
        private const val TEAL = "#00796B"
        private const val GRAY = "#757575"
        private const val DARK = "#212121"
    }

    val root: View
    val params: WindowManager.LayoutParams

    private val wm: WindowManager? = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private val collapsedCard: LinearLayout
    private val expandedCard: LinearLayout
    private val marqueeTv: TextView
    private val updatedTv: TextView
    private val listBox: LinearLayout
    private val bodyScroll: ScrollView
    private val updateBtn: Button

    private var expanded = false

    // 拖拽状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    init {
        val slop = dp(SLOP_DP)

        // ── 小浮窗 ──
        marqueeTv = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        updatedTv = TextView(ctx).apply {
            textSize = 9f; setTextColor("#B2EBF2".let { Color.parseColor(it) })
            setPadding(dp(10), 0, dp(10), dp(6))
        }
        collapsedCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(TEAL, "#FFFFFF", 8)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(marqueeTv)
            addView(updatedTv)
            setOnClickListener { setExpanded(true) }
        }

        // ── 展开面板 ──
        updateBtn = Button(ctx).apply {
            text = "🔄 更新"; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0288D1"))
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onUpdate() }
        }
        val collapseBtn = Button(ctx).apply {
            text = "收起"; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(GRAY))
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(34))
            setOnClickListener { setExpanded(false) }
        }
        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
            addView(TextView(ctx).apply {
                text = "⚠ 持仓股短线预警"; textSize = 12f
                setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(4))
            addView(updateBtn); addView(collapseBtn)
        }
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bodyScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            addView(listBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        expandedCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#263238", "#FFFFFF", 8)
            addView(headRow); addView(btnRow); addView(bodyScroll)
            visibility = View.GONE
        }

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(collapsedCard)
            addView(expandedCard)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(120)
        }

        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm?.updateViewLayout(root, params)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = dragging
                    dragging = false
                    was
                }
                else -> false
            }
        }
    }

    // ── 生命周期 ────────────────────────────────────────

    fun attach() {
        runCatching { wm?.addView(root, params) }
    }

    fun detach() {
        runCatching { wm?.takeIf { root.parent != null }?.removeView(root) }
    }

    /** 主线程调用 */
    fun setUpdating(updating: Boolean) {
        updateBtn.isEnabled = !updating
        updateBtn.text = if (updating) "更新中…" else "🔄 更新"
    }

    private fun setExpanded(expand: Boolean) {
        expanded = expand
        collapsedCard.visibility = if (expand) View.GONE else View.VISIBLE
        expandedCard.visibility = if (expand) View.VISIBLE else View.GONE
        if (expand) {
            params.width = dp(304)
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        runCatching { wm?.updateViewLayout(root, params) }
    }

    // ── 渲染 ────────────────────────────────────────────

    /** 主线程调用 */
    fun render(list: List<SsaAlertStore.OverlayRow>) {
        val upd = list.firstOrNull()?.updated.orEmpty()
        if (list.isEmpty()) {
            marqueeTv.text = "⚠ 持仓池为空 · 无预警股"
            updatedTv.text = "更新时间 $upd"
            listBox.removeAllViews()
            return
        }
        marqueeTv.text = list.joinToString("     |     ") { "${it.name} ${it.advice}" } + "     |     "
        updatedTv.text = "${list.size} 只持仓 · ${if (upd.isNotEmpty()) "更新于 $upd" else ""}"

        listBox.removeAllViews()
        val widths = listOf(70, 52, 48, 30, 34)
        listBox.addView(tableRow(listOf("名称", "现价", "涨跌", "BBI", "建议"), widths, header = true))
        list.forEach { r ->
            val adviceColor = when {
                r.advice.startsWith("买入") -> RED
                r.advice.startsWith("关注") || r.advice == "金叉预警" -> TEAL
                r.advice.contains("卖出") -> GREEN
                r.advice.startsWith("持仓") -> "#1565C0"
                else -> GRAY
            }
            val cells = listOf(
                r.name.ifBlank { r.code },
                if (r.price > 0) "%.2f".format(r.price) else "—",
                "%+.2f%%".format(r.changePct),
                if (r.score > 0) r.score.toString() else "0",
                r.advice
            )
            listBox.addView(tableRow(cells, widths, header = false, adviceColor = adviceColor,
                changePct = r.changePct, windowClosed = !r.windowOpen))
        }
        listBox.addView(TextView(ctx).apply {
            text = "口径：60分钟K末200根 · BBI12冠军组合（STEADY+固定成本止损）· BBI列：100买入拐点/80ER闸未过/70金叉预警/60持仓/0观望 · 仅提示不构成投资建议"
            textSize = 8.5f; setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(dp(6), dp(4), dp(6), dp(6))
        })

        // 列表高度自适应：表头 + N 行 + 说明，最多 300dp
        val need = (list.size + 2) * dp(21) + dp(12)
        bodyScroll.layoutParams = (bodyScroll.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )).apply { height = need.coerceAtMost(dp(300)) }
        if (expanded) runCatching { wm?.updateViewLayout(root, params) }
    }

    private fun tableRow(
        cells: List<String>,
        widths: List<Int>,
        header: Boolean,
        adviceColor: String = "#ECEFF1",
        changePct: Double = 0.0,
        windowClosed: Boolean = false
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(3), dp(4), dp(3))
        if (header) setBackgroundColor(Color.parseColor("#37474F"))
        cells.forEachIndexed { i, txt ->
            addView(TextView(ctx).apply {
                text = txt
                textSize = if (header) 10f else 10f
                gravity = Gravity.CENTER_VERTICAL or
                    (if (i == 0) Gravity.START else Gravity.CENTER_HORIZONTAL)
                setTextColor(when {
                    header -> Color.WHITE
                    i == 0 -> Color.parseColor("#ECEFF1")
                    i == 2 -> if (changePct >= 0) Color.parseColor("#FF8A80") else Color.parseColor("#A5D6A7")
                    i == 4 -> Color.parseColor(adviceColor)
                    windowClosed -> Color.parseColor("#90A4AE")
                    else -> Color.parseColor("#CFD8DC")
                })
                if (header || i == 4) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), 0, dp(2), 0)
                layoutParams = LinearLayout.LayoutParams(dp(widths[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    // ── 工具 ────────────────────────────────────────────

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun roundedBg(fill: String, stroke: String, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.parseColor(fill))
        setStroke(dp(1), Color.parseColor(stroke))
        cornerRadius = dp(radiusDp).toFloat()
    }
}
