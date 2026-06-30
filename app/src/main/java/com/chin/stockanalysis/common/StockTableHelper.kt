package com.chin.stockanalysis.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ## 股票表格公共輔助類
 *
 * 備選池/自選/AI精選 共用：
 * - 創建標準表頭（動態列，默認8列）
 * - 創建數據行
 * - 支持水平滾動，列數可動態配置
 * - 向後兼容：createHeaderRow / createDataRow 簽名不變
 */
object StockTableHelper {

    // ═══════════════════════════════════════════════════════════════
    //  ColumnDef — 動態列定義
    // ═══════════════════════════════════════════════════════════════
    data class ColumnDef(
        val key: String,           // 唯一標識（如 "name", "sector", "change_pct"）
        val title: String,         // 表頭顯示文字
        val widthDp: Int,          // 列寬
        val gravity: Int = Gravity.CENTER,
        val fontSize: Float = 10f,
        val bold: Boolean = false,
        val colorHex: String = "#666666"
    )

    // ═══════════════════════════════════════════════════════════════
    //  StockDisplayItem — 擴展字段
    // ═══════════════════════════════════════════════════════════════
    data class StockDisplayItem(
        val code: String,
        val name: String,
        val sector: String = "",
        val subSector: String = "",
        val changePct: Double = 0.0,
        val price: Double = 0.0,
        val peRatio: Double = 0.0,
        val turnoverRate: Double = 0.0,
        val marketCap: Double = 0.0,
        val changeAmount: Double = 0.0,
        val hasSnapshot: Boolean = true,
        val source: String = "",
        val score: Int = 0,
        // 擴展字段
        val high: Double = 0.0,
        val low: Double = 0.0,
        val volume: Long = 0,
        val amount: Double = 0.0,
        val pb: Double = 0.0
    )

    // ═══════════════════════════════════════════════════════════════
    //  預設列配置
    // ═══════════════════════════════════════════════════════════════

    /** 預設列配置（與現有8列完全一致） */
    fun defaultColumns(): List<ColumnDef> = listOf(
        ColumnDef("name", "股票名稱", 72, Gravity.START, 13f, true, "#1A1A2E"),
        ColumnDef("sector", "板塊", 44, Gravity.START, 10f, false, "#666666"),
        ColumnDef("change_pct", "漲幅", 50, Gravity.CENTER, 11f, true),
        ColumnDef("price", "現價", 44, Gravity.CENTER, 12f, true, "#333333"),
        ColumnDef("pe", "市盈", 36, Gravity.CENTER, 10f),
        ColumnDef("turnover", "換手率", 44, Gravity.CENTER, 10f),
        ColumnDef("market_cap", "市值", 44, Gravity.CENTER, 10f),
        ColumnDef("delete", "清空", 26, Gravity.CENTER, 14f, true, "#E53935")
    )

    /** 擴展列配置（新增更多東方財富字段） */
    fun extendedColumns(): List<ColumnDef> = listOf(
        ColumnDef("name", "股票名稱", 72, Gravity.START, 13f, true, "#1A1A2E"),
        ColumnDef("sector", "板塊", 44, Gravity.START, 10f),
        ColumnDef("change_pct", "漲幅", 50, Gravity.CENTER, 11f, true),
        ColumnDef("price", "現價", 44, Gravity.CENTER, 12f, true, "#333333"),
        ColumnDef("change_amount", "漲跌", 40, Gravity.CENTER, 10f),
        ColumnDef("high", "最高", 44, Gravity.CENTER, 10f),
        ColumnDef("low", "最低", 44, Gravity.CENTER, 10f),
        ColumnDef("volume", "成交量", 50, Gravity.CENTER, 10f),
        ColumnDef("amount", "成交額", 50, Gravity.CENTER, 10f),
        ColumnDef("turnover", "換手率", 44, Gravity.CENTER, 10f),
        ColumnDef("pe", "市盈", 36, Gravity.CENTER, 10f),
        ColumnDef("pb", "市淨", 36, Gravity.CENTER, 10f),
        ColumnDef("market_cap", "市值", 50, Gravity.CENTER, 10f),
        ColumnDef("delete", "清空", 26, Gravity.CENTER, 14f, true, "#E53935")
    )

    // ═══════════════════════════════════════════════════════════════
    //  DynamicStockTable — 動態列表格構建器
    // ═══════════════════════════════════════════════════════════════

    /**
     * 動態列定義的表格構建器。
     * 支持 addColumn / removeColumn / setColumns 動態調整列，
     * createHeaderRow / createDataRow / createBulkRows 生成帶水平滾動的表格行。
     */
    class DynamicStockTable(private val context: Context) {

        private val columns = mutableListOf<ColumnDef>()

        fun addColumn(col: ColumnDef): DynamicStockTable {
            columns.add(col)
            return this
        }

        fun removeColumn(key: String): DynamicStockTable {
            columns.removeAll { it.key == key }
            return this
        }

        fun setColumns(cols: List<ColumnDef>): DynamicStockTable {
            columns.clear()
            columns.addAll(cols)
            return this
        }

        fun getColumns(): List<ColumnDef> = columns.toList()

        /** 總列寬（dp） */
        fun totalWidthDp(): Int = columns.sumOf { it.widthDp }

        // ── 表頭 ──
        fun createHeaderRow(onClearAll: (() -> Unit)? = null): LinearLayout {
            val dp = context.resources.displayMetrics.density
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            }
            for (col in columns) {
                val tv = TextView(context).apply {
                    text = col.title
                    textSize = 10f
                    setTextColor(Color.parseColor("#888888"))
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    gravity = col.gravity
                    layoutParams = LinearLayout.LayoutParams(
                        (col.widthDp * dp).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                if (col.key == "delete" && onClearAll != null) {
                    tv.setTextColor(Color.parseColor("#E53935"))
                    tv.setOnClickListener { onClearAll() }
                }
                row.addView(tv)
            }
            return row
        }

        // ── 單行數據 ──
        fun createDataRow(
            item: StockDisplayItem,
            isLast: Boolean,
            onItemClick: ((StockDisplayItem) -> Unit)? = null,
            onDelete: ((StockDisplayItem) -> Unit)? = null
        ): View {
            val dp = context.resources.displayMetrics.density

            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            }

            val priceColor = when {
                !item.hasSnapshot -> Color.parseColor("#999999")
                item.changePct > 0 -> Color.parseColor("#E53935")
                item.changePct < 0 -> Color.parseColor("#43A047")
                else -> Color.parseColor("#333333")
            }

            for (col in columns) {
                val cell = buildCell(col, item, priceColor, dp, onDelete)
                row.addView(cell)
            }

            wrapper.addView(row)

            if (!isLast) {
                wrapper.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        setMargins((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                    }
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                })
            }

            if (onItemClick != null) {
                wrapper.setOnClickListener { onItemClick(item) }
            }

            return wrapper
        }

        // ── 批量行 ──
        fun createBulkRows(
            items: List<StockDisplayItem>,
            onItemClick: ((StockDisplayItem) -> Unit)? = null,
            onDelete: ((StockDisplayItem) -> Unit)? = null
        ): LinearLayout {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            for ((index, item) in items.withIndex()) {
                container.addView(createDataRow(item, isLast = index == items.lastIndex, onItemClick, onDelete))
            }
            return container
        }

        // ── 單元格渲染 ──
        private fun buildCell(
            col: ColumnDef,
            item: StockDisplayItem,
            priceColor: Int,
            dp: Float,
            onDelete: ((StockDisplayItem) -> Unit)?
        ): View {
            val widthPx = (col.widthDp * dp).toInt()

            return when (col.key) {
                // ── 股票名稱（兩行：名稱 + 代碼） ──
                "name" -> {
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.START
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }.also { cell ->
                        cell.addView(TextView(context).apply {
                            text = item.name.take(8)
                            textSize = col.fontSize
                            setTextColor(Color.parseColor(col.colorHex))
                            setTypeface(null, Typeface.BOLD)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        cell.addView(TextView(context).apply {
                            text = item.code.takeLast(6)
                            textSize = 9f
                            setTextColor(Color.parseColor("#AAAAAA"))
                            setPadding(0, (1 * dp).toInt(), 0, 0)
                        })
                    }
                }

                // ── 板塊 ──
                "sector" -> {
                    TextView(context).apply {
                        text = when {
                            item.subSector.isNotBlank() && item.subSector != "其他" -> item.subSector.take(6)
                            item.sector.isNotBlank() && item.sector != "其他" -> item.sector.take(6)
                            else -> ""
                        }
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 漲幅（帶顏色背景） ──
                "change_pct" -> {
                    val changeText = when {
                        !item.hasSnapshot -> "--"
                        item.changePct > 0 -> "+${String.format("%.2f", item.changePct)}%"
                        else -> "${String.format("%.2f", item.changePct)}%"
                    }
                    val changeBg = when {
                        !item.hasSnapshot -> Color.TRANSPARENT
                        item.changePct > 0 -> Color.parseColor("#FFF0F0")
                        item.changePct < 0 -> Color.parseColor("#F0FFF0")
                        else -> Color.parseColor("#F5F5F5")
                    }
                    TextView(context).apply {
                        text = changeText
                        textSize = col.fontSize
                        setTextColor(priceColor)
                        setTypeface(null, Typeface.BOLD)
                        gravity = col.gravity
                        setPadding((4 * dp).toInt(), (1 * dp).toInt(), (4 * dp).toInt(), (1 * dp).toInt())
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setColor(changeBg)
                            cornerRadius = 2f * dp
                        }
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 現價 ──
                "price" -> {
                    TextView(context).apply {
                        text = if (item.hasSnapshot) String.format("%.2f", item.price) else "--"
                        textSize = col.fontSize
                        setTextColor(priceColor)
                        setTypeface(null, Typeface.BOLD)
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 漲跌 ──
                "change_amount" -> {
                    val amountText = when {
                        !item.hasSnapshot -> "--"
                        item.changeAmount > 0 -> "+${String.format("%.2f", item.changeAmount)}"
                        else -> String.format("%.2f", item.changeAmount)
                    }
                    TextView(context).apply {
                        text = amountText
                        textSize = col.fontSize
                        setTextColor(priceColor)
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 最高 ──
                "high" -> {
                    TextView(context).apply {
                        text = if (item.high > 0) String.format("%.2f", item.high) else "-"
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 最低 ──
                "low" -> {
                    TextView(context).apply {
                        text = if (item.low > 0) String.format("%.2f", item.low) else "-"
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 成交量（格式化為萬/億） ──
                "volume" -> {
                    TextView(context).apply {
                        text = formatVolume(item.volume)
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 成交額（格式化為萬/億） ──
                "amount" -> {
                    TextView(context).apply {
                        text = formatAmount(item.amount)
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 換手率 ──
                "turnover" -> {
                    TextView(context).apply {
                        text = if (item.turnoverRate > 0) String.format("%.2f%%", item.turnoverRate) else "-"
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 市盈率 ──
                "pe" -> {
                    TextView(context).apply {
                        text = if (item.peRatio > 0) String.format("%.1f", item.peRatio) else "-"
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 市淨率 ──
                "pb" -> {
                    TextView(context).apply {
                        text = if (item.pb > 0) String.format("%.2f", item.pb) else "-"
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 市值（億） ──
                "market_cap" -> {
                    TextView(context).apply {
                        text = if (item.marketCap > 0) String.format("%.0f", item.marketCap) else "-"
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }

                // ── 刪除按鈕 ──
                "delete" -> {
                    TextView(context).apply {
                        text = "\u2715"  // ✕
                        textSize = col.fontSize
                        setTextColor(Color.parseColor("#E53935"))
                        setTypeface(null, Typeface.BOLD)
                        gravity = col.gravity
                        setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                        setOnClickListener { onDelete?.invoke(item) }
                    }
                }

                // ── 未知 key，顯示 "- " ──
                else -> {
                    TextView(context).apply {
                        text = "-"
                        textSize = col.fontSize
                        setTextColor(Color.parseColor(col.colorHex))
                        gravity = col.gravity
                        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                }
            }
        }

        // ── 數值格式化工具 ──
        private fun formatVolume(volume: Long): String {
            if (volume <= 0) return "-"
            return when {
                volume >= 100_000_000 -> String.format("%.2f億", volume / 100_000_000.0)
                volume >= 10_000 -> String.format("%.1f萬", volume / 10_000.0)
                else -> volume.toString()
            }
        }

        private fun formatAmount(amount: Double): String {
            if (amount <= 0) return "-"
            return when {
                amount >= 100_000_000.0 -> String.format("%.2f億", amount / 100_000_000.0)
                amount >= 10_000.0 -> String.format("%.1f萬", amount / 10_000.0)
                else -> String.format("%.0f", amount)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  公開 API — 向後兼容（簽名不變）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 創建表頭行（兼容現有簽名，內部使用 DynamicStockTable + defaultColumns）。
     * 返回 HorizontalScrollView 包裹的表頭，當列總寬超過屏幕時可水平滾動。
     */
    fun createHeaderRow(context: Context, onClearAll: (() -> Unit)? = null): View {
        return createDynamicHeaderRow(context, defaultColumns(), onClearAll)
    }

    /**
     * 創建數據行（兼容現有簽名，內部使用 DynamicStockTable + defaultColumns）。
     * 返回 HorizontalScrollView 包裹的數據行。
     */
    fun createDataRow(
        context: Context,
        item: StockDisplayItem,
        isLast: Boolean,
        onItemClick: ((StockDisplayItem) -> Unit)? = null,
        onDelete: ((StockDisplayItem) -> Unit)? = null
    ): View {
        return createDynamicDataRow(context, defaultColumns(), item, isLast, onItemClick, onDelete)
    }

    // ═══════════════════════════════════════════════════════════════
    //  公開 API — 動態列版本（新增）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 使用自定義列配置創建表頭行。
     * 返回 HorizontalScrollView，當列總寬超過屏幕時可水平滾動。
     */
    fun createDynamicHeaderRow(
        context: Context,
        columns: List<ColumnDef>,
        onClearAll: (() -> Unit)? = null
    ): HorizontalScrollView {
        val table = DynamicStockTable(context).apply { setColumns(columns) }
        val headerRow = table.createHeaderRow(onClearAll)
        return wrapInScrollView(context, headerRow)
    }

    /**
     * 使用自定義列配置創建數據行。
     * 返回 HorizontalScrollView，當列總寬超過屏幕時可水平滾動。
     */
    fun createDynamicDataRow(
        context: Context,
        columns: List<ColumnDef>,
        item: StockDisplayItem,
        isLast: Boolean,
        onItemClick: ((StockDisplayItem) -> Unit)? = null,
        onDelete: ((StockDisplayItem) -> Unit)? = null
    ): View {
        val table = DynamicStockTable(context).apply { setColumns(columns) }
        val row = table.createDataRow(item, isLast, onItemClick, onDelete)
        return wrapInScrollView(context, row)
    }

    /**
     * 使用自定義列配置批量創建數據行（共用一個 HorizontalScrollView）。
     */
    fun createDynamicBulkRows(
        context: Context,
        columns: List<ColumnDef>,
        items: List<StockDisplayItem>,
        onItemClick: ((StockDisplayItem) -> Unit)? = null,
        onDelete: ((StockDisplayItem) -> Unit)? = null
    ): View {
        val table = DynamicStockTable(context).apply { setColumns(columns) }
        val container = table.createBulkRows(items, onItemClick, onDelete)
        return wrapInScrollView(context, container)
    }

    /**
     * 使用自定義列配置創建完整表格（表頭 + 數據行，共用一個 HorizontalScrollView）。
     */
    fun createDynamicTable(
        context: Context,
        columns: List<ColumnDef>,
        items: List<StockDisplayItem>,
        onClearAll: (() -> Unit)? = null,
        onItemClick: ((StockDisplayItem) -> Unit)? = null,
        onDelete: ((StockDisplayItem) -> Unit)? = null
    ): HorizontalScrollView {
        val table = DynamicStockTable(context).apply { setColumns(columns) }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(table.createHeaderRow(onClearAll))
        for ((index, item) in items.withIndex()) {
            container.addView(table.createDataRow(item, isLast = index == items.lastIndex, onItemClick, onDelete))
        }
        return wrapInScrollView(context, container)
    }

    // ═══════════════════════════════════════════════════════════════
    //  內部工具
    // ═══════════════════════════════════════════════════════════════

    /**
     * 將內容包裹在 HorizontalScrollView 中。
     * 如果內容寬度 <= 屏幕寬度，則不添加滾動條（設置 horizontalScrollBarEnabled = false）。
     */
    private fun wrapInScrollView(context: Context, content: View): HorizontalScrollView {
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(content)
        }
    }
}
