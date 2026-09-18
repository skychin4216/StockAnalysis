package com.chin.stockanalysis.strategy.trade.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/**
 * ## 工作台统一 UI 构件（公共类 · 2026-09-10 抽取）
 *
 * 背景：短/中/长三周期页（浅色 #F5F6FA，见 QuantFragmentBase）与 ETF 页（深色 #0E1116，
 * 见 EtfDipFragment）此前各写一份「标题行 / 状态条 / 横滑表格 / 按钮行 / 空态提示」，
 * 同一件事两处实现 → 改一边忘一边（如门控横幅、技术表列宽、状态配色）。
 *
 * 本类把这类**区块构造**收敛为一处：三周期与 ETF、以及后续所有新视图（如 ETF 思路实验室）
 * 都从这里取构件，只传 [QuantPalette] 区分深浅色主题，不再各写一套。
 *
 * 分层（不要互相替代）：
 *   - QuantUiKit    = 布局/区块「构造器」（标题、按钮、表格骨架、卡片、空态、折叠区）
 *   - PickTechTable = 11 列「技术假设」行的**内容渲染**（口径统一，见该类注释）
 *   - 各业务 Fragment = 数据 → 调本类构件
 *
 * 用法示例：
 * ```
 * val p = if (isDarkPage) QuantUiKit.DARK else QuantUiKit.LIGHT
 * container.addView(QuantUiKit.titleRow(ctx, p, "📊 当日选股 · 技术假设", "（3 只）"))
 * val t = QuantUiKit.table(ctx, p, cols, widths, container)
 * t.addView(QuantUiKit.row(ctx, p, values, widths, ...))
 * ```
 */

/** 工作台配色：深色（ETF 页）/ 浅色（三周期页）各一套，业务侧只依赖它，不硬编码颜色。 */
class QuantPalette(
    val dark: Boolean,
    val bg: String,
    val card: String,
    val headerBg: String,
    val cellBg: String,
    val title: String,
    val text: String,
    val subText: String,
    val accent: String,
    val up: String,
    val down: String,
    val ok: String,
    val warn: String,
    val bad: String,
    val tip: String
)

object QuantUiKit {

    /** ETF 页：深色底（#0E1116），红涨绿跌沿用 A 股盘面习惯。 */
    val DARK = QuantPalette(
        dark = true,
        bg = "#0E1116", card = "#141A24", headerBg = "#3B5B92", cellBg = "#171E28",
        title = "#FF8A50", text = "#E0E0E0", subText = "#8A8F98", accent = "#64B5F6",
        up = "#FF8A80", down = "#64B5F6", ok = "#69F0AE", warn = "#FFD54F",
        bad = "#E57373", tip = "#6E7683"
    )

    /** 三周期页：浅色底（#F5F6FA）。 */
    val LIGHT = QuantPalette(
        dark = false,
        bg = "#F5F6FA", card = "#FFFFFF", headerBg = "#3B5B92", cellBg = "#FFFFFF",
        title = "#1A1A2E", text = "#37474F", subText = "#999999", accent = "#1976D2",
        up = "#D32F2F", down = "#1976D2", ok = "#2E7D32", warn = "#EF6C00",
        bad = "#C62828", tip = "#999999"
    )

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun color(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        Color.BLACK
    }

    // ══════════════════ 基础：卡片 / 分隔线 ══════════════════

    /** 圆角卡片背景（深色页用半透明淡色、浅色页用白底+描边感）。 */
    fun cardBackground(p: QuantPalette, radiusDp: Int = 10, fill: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill ?: color(p.card))
            cornerRadius = radiusDp.toFloat()
        }

    /** 淡色圆角卡片（用于强调块，如策略卡/提示卡）。 */
    fun tintedBackground(colorHex: String, radiusDp: Int = 8): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(colorHex))
            cornerRadius = radiusDp.toFloat()
        }

    fun divider(ctx: Context, p: QuantPalette, topMarginDp: Int = 6): View = View(ctx).apply {
        setBackgroundColor(color(if (p.dark) "#232B36" else "#DDDDDD"))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)
        ).apply { topMargin = dp(ctx, topMarginDp) }
    }

    // ══════════════════ 区块标题行 ══════════════════

    /**
     * 区块标题行：`📊 标题  副标题 ............ 尾部动作`。
     * @param trailing 右侧小动作（如「🔄」清理、「展开」），点击回调可用 [onTrailing]
     */
    fun titleRow(
        ctx: Context,
        p: QuantPalette,
        title: String,
        subtitle: String? = null,
        trailing: String? = null,
        trailingColor: String? = null,
        titleColorHex: String? = null,
        textSize: Float = 13f,
        topPaddingDp: Int = 6,
        onTrailing: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, topPaddingDp), 0, dp(ctx, 2))
        }
        row.addView(TextView(ctx).apply {
            text = title
            this.textSize = textSize
            setTextColor(color(titleColorHex ?: p.title))
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!subtitle.isNullOrBlank()) {
            row.addView(TextView(ctx).apply {
                text = subtitle
                this.textSize = 10f
                setTextColor(color(p.subText))
                setPadding(dp(ctx, 6), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        } else {
            row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        }
        if (!trailing.isNullOrBlank()) {
            row.addView(TextView(ctx).apply {
                text = trailing
                this.textSize = 12f
                setTextColor(color(trailingColor ?: p.accent))
                setPadding(dp(ctx, 8), 0, dp(ctx, 2), 0)
                if (onTrailing != null) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onTrailing() }
                }
            })
        }
        return row
    }

    // ══════════════════ 文字 / 指标 ══════════════════

    /** 普通说明文字（可等宽，用于日志/JSON 片段）。 */
    fun note(
        ctx: Context,
        p: QuantPalette,
        text: String,
        mono: Boolean = false,
        colorHex: String? = null,
        textSize: Float = 11f,
        topPaddingDp: Int = 2
    ): TextView = TextView(ctx).apply {
        this.text = text
        this.textSize = textSize
        setTextColor(color(colorHex ?: p.subText))
        if (mono) setTypeface(Typeface.MONOSPACE)
        setLineSpacing(2f, 1.0f)
        setPadding(0, dp(ctx, topPaddingDp), 0, dp(ctx, 2))
    }

    /** 键值行：`标签 ............. 值`（值可着色）。 */
    fun kvRow(
        ctx: Context,
        p: QuantPalette,
        label: String,
        value: String,
        valueColor: String? = null,
        labelSize: Float = 11.5f,
        valueSize: Float = 11.5f
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 1), 0, dp(ctx, 1))
        addView(TextView(ctx).apply {
            text = label
            textSize = labelSize
            setTextColor(color(p.subText))
        })
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(TextView(ctx).apply {
            text = value
            textSize = valueSize
            setTextColor(color(valueColor ?: p.text))
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    /** 状态徽标：ok=true 绿 / false 红（深色底用亮色、浅色底用深色，由调色板决定）。 */
    fun chip(ctx: Context, p: QuantPalette, text: String, ok: Boolean): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(color(if (p.dark) { if (ok) p.ok else p.bad } else p.text))
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
        background = if (ok) tintedBackground(if (p.dark) "#1D3A2A" else "#E8F5E9", 6)
        else tintedBackground(if (p.dark) "#3A1D1D" else "#FDECEA", 6)
    }

    // ══════════════════ 按钮 ══════════════════

    /** 小尺寸按钮（ETF 页与三周期按钮行同规格：高 22dp、字号 10、白字彩底）。 */
    fun button(
        ctx: Context,
        text: String,
        colorHex: String,
        weight: Float = 1f,
        textSize: Float = 10f,
        onClick: () -> Unit
    ): Button = Button(ctx).apply {
        this.text = text
        this.textSize = textSize
        setTextColor(Color.WHITE)
        setBackgroundColor(color(colorHex))
        setPadding(dp(ctx, 4), dp(ctx, 1), dp(ctx, 4), dp(ctx, 1))
        setMinWidth(0)
        setMinimumWidth(0)
        layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 22), weight).apply {
            marginEnd = dp(ctx, 1)
        }
        setOnClickListener { onClick() }
    }

    /** 空按钮行容器（业务侧逐个 addView(button(...))）。 */
    fun buttonRow(ctx: Context, p: QuantPalette): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(color(p.bg))
        setPadding(dp(ctx, 4), dp(ctx, 1), dp(ctx, 4), dp(ctx, 1))
    }

    // ══════════════════ 横滑表格 ══════════════════

    /**
     * 建一张「横滑 + 表头」的空表，返回 TableLayout 供追加 [row]。
     * 列宽语义与 ETF 页一致：minimumWidth=dp(width)，内容自适应并支持左右滑动。
     */
    fun table(
        ctx: Context,
        p: QuantPalette,
        cols: List<String>,
        widths: IntArray,
        host: LinearLayout,
        tip: String? = null,
        withHeader: Boolean = true
    ): TableLayout {
        if (!tip.isNullOrBlank()) {
            host.addView(note(ctx, p, tip, textSize = 10.5f, colorHex = p.tip))
        }
        val hsv = HorizontalScrollView(ctx).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = true
            setPadding(0, 0, 0, dp(ctx, 8))
        }
        val table = TableLayout(ctx).apply {
            setBackgroundColor(color(p.card))
            setStretchAllColumns(false)
            setPadding(dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
        }
        hsv.addView(
            table,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        host.addView(
            hsv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        if (withHeader) table.addView(headerRow(ctx, p, cols, widths))
        return table
    }

    /** 表头行：深蓝底白字（与推送选股图 / ETF 页一致）。 */
    fun headerRow(
        ctx: Context,
        p: QuantPalette,
        cols: List<String>,
        widths: IntArray
    ): TableRow = TableRow(ctx).apply {
        for ((idx, c) in cols.withIndex()) {
            addView(TextView(ctx).apply {
                text = c
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundColor(color(p.headerBg))
                setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                minimumWidth = dp(ctx, widths.getOrElse(idx) { 60 })
            })
        }
    }

    /**
     * 数据行。
     * @param aligns 每格对齐（Gravity.*），缺省居中；数值列通常 Gravity.END
     * @param colorOf (列下标, 值) -> 颜色 hex，返回 null 用默认正文色
     * @param boldFirst 首列加粗（标的名）
     */
    fun row(
        ctx: Context,
        p: QuantPalette,
        values: List<String>,
        widths: IntArray,
        aligns: IntArray? = null,
        colorOf: ((Int, String) -> Int?)? = null,
        boldFirst: Boolean = true,
        bgHex: String? = null,
        onClick: (() -> Unit)? = null
    ): TableRow = TableRow(ctx).apply {
        for ((idx, v) in values.withIndex()) {
            val tv = TextView(ctx).apply {
                text = v
                textSize = 11.5f
                gravity = aligns?.getOrNull(idx) ?: Gravity.CENTER
                setTextColor(colorOf?.invoke(idx, v) ?: color(p.text))
                if (idx == 0 && boldFirst) setTypeface(typeface, Typeface.BOLD)
                setBackgroundColor(color(bgHex ?: p.cellBg))
                setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                minimumWidth = dp(ctx, widths.getOrElse(idx) { 60 })
            }
            val lp = TableRow.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 1)
            addView(tv, lp)
        }
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** 表格内空态行（跨列提示）。 */
    fun emptyHint(ctx: Context, p: QuantPalette, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(p.subText))
        setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
    }

    // ══════════════════ 折叠区块 ══════════════════

    /**
     * 可折叠区块：点标题行展开/收起。
     * ```
     * val c = QuantUiKit.collapsible(ctx, p, "⚙️ 拟合（网格搜索）", expanded = false)
     * host.addView(c.view)
     * c.body.addView(...)
     * ```
     */
    class Collapsible(val view: LinearLayout, val body: LinearLayout, val titleView: TextView) {
        var expanded = false
            private set

        fun toggle() = setExpanded(!expanded)

        fun setExpanded(e: Boolean) {
            expanded = e
            body.visibility = if (e) View.VISIBLE else View.GONE
            titleView.text = titleView.text.toString().let { t ->
                val core = t.removePrefix("▾ ").removePrefix("▸ ")
                if (e) "▾ $core" else "▸ $core"
            }
        }
    }

    fun collapsible(
        ctx: Context,
        p: QuantPalette,
        title: String,
        expanded: Boolean = false,
        onToggle: ((Boolean) -> Unit)? = null
    ): Collapsible {
        val header = titleRow(ctx, p, title, textSize = 12f, topPaddingDp = 8)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 2), 0, dp(ctx, 2), dp(ctx, 4))
            visibility = View.GONE
        }
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(body)
        }
        val titleView = header.getChildAt(0) as TextView
        val c = Collapsible(holder, body, titleView)
        header.isClickable = true
        header.isFocusable = true
        header.setOnClickListener {
            c.toggle()
            onToggle?.invoke(c.expanded)
        }
        c.setExpanded(expanded)
        return c
    }
}
