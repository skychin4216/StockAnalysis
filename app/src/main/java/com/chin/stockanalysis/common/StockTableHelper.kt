package com.chin.stockanalysis.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ## 股票表格公共輔助類
 *
 * 備選池/自選/AI精選 共用：
 * - 創建標準表頭（8列）
 * - 創建數據行
 * - 表頭和數據行使用相同 WEIGHTS，保證列對齊
 */
object StockTableHelper {

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
        val score: Int = 0
    )

    // 列寬權重（表頭和數據行共用，保證對齊）
    private val WEIGHTS = floatArrayOf(1.8f, 0.5f, 1.0f, 0.9f, 0.8f, 0.9f, 0.8f, 0.5f)

    private val TITLES = arrayOf("股票名稱", "板塊", "漲幅", "現價", "市盈", "換手率", "市值", "清空")
    private val GRAVITIES = intArrayOf(
        Gravity.START, Gravity.START, Gravity.CENTER, Gravity.CENTER,
        Gravity.CENTER, Gravity.CENTER, Gravity.CENTER, Gravity.CENTER
    )

    fun createHeaderRow(context: Context, onClearAll: (() -> Unit)? = null): LinearLayout {
        val dp = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }.also { row ->
            for (i in TITLES.indices) {
                val tv = TextView(context).apply {
                    text = TITLES[i]
                    textSize = 10f
                    setTextColor(Color.parseColor("#888888"))
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    gravity = GRAVITIES[i]
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[i])
                }
                if (i == 7 && onClearAll != null) {
                    tv.setTextColor(Color.parseColor("#E53935"))
                    tv.setOnClickListener { onClearAll() }
                }
                row.addView(tv)
            }
        }
    }

    fun createDataRow(
        context: Context,
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

        // ── 1. 股票名稱 + 代碼 ──
        val nameCell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[0])
        }
        nameCell.addView(TextView(context).apply {
            text = item.name.take(8)
            textSize = 13f
            setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        nameCell.addView(TextView(context).apply {
            text = item.code.takeLast(6)
            textSize = 9f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, (1 * dp).toInt(), 0, 0)
        })
        row.addView(nameCell)

        // ── 2. 板塊 ──
        row.addView(TextView(context).apply {
            text = when {
                item.subSector.isNotBlank() && item.subSector != "其他" -> item.subSector.take(6)
                item.sector.isNotBlank() && item.sector != "其他" -> item.sector.take(6)
                else -> ""
            }
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = GRAVITIES[1]
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[1])
        })

        // ── 3. 漲幅 ──
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
        row.addView(TextView(context).apply {
            text = changeText
            textSize = 11f
            setTextColor(priceColor)
            setTypeface(null, Typeface.BOLD)
            gravity = GRAVITIES[2]
            setPadding((4 * dp).toInt(), (1 * dp).toInt(), (4 * dp).toInt(), (1 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(changeBg)
                cornerRadius = 2f * dp
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[2])
        })

        // ── 4. 現價 ──
        row.addView(TextView(context).apply {
            text = if (item.hasSnapshot) String.format("%.2f", item.price) else "--"
            textSize = 12f
            setTextColor(priceColor)
            setTypeface(null, Typeface.BOLD)
            gravity = GRAVITIES[3]
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[3])
        })

        // ── 5. 市盈率 ──
        row.addView(TextView(context).apply {
            text = if (item.peRatio > 0) String.format("%.1f", item.peRatio) else "-"
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            gravity = GRAVITIES[4]
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[4])
        })

        // ── 6. 換手率 ──
        row.addView(TextView(context).apply {
            text = if (item.turnoverRate > 0) String.format("%.2f%%", item.turnoverRate) else "-"
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            gravity = GRAVITIES[5]
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[5])
        })

        // ── 7. 市值(億) ──
        row.addView(TextView(context).apply {
            text = if (item.marketCap > 0) String.format("%.0f", item.marketCap) else "-"
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            gravity = GRAVITIES[6]
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[6])
        })

        // ── 8. 清空（刪除按鈕） ──
        row.addView(TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#E53935"))
            setTypeface(null, Typeface.BOLD)
            gravity = GRAVITIES[7]
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[7])
            setOnClickListener { onDelete?.invoke(item) }
        })

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
}
