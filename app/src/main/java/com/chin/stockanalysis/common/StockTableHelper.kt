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
 * - 統一點擊/刪除回調
 */
object StockTableHelper {

    data class StockDisplayItem(
        val code: String,
        val name: String,
        val sector: String = "",           // 板塊大類
        val subSector: String = "",        // 具體子板塊
        val changePct: Double = 0.0,       // 漲幅%
        val price: Double = 0.0,           // 現價
        val peRatio: Double = 0.0,         // 市盈率(TTM)
        val turnoverRate: Double = 0.0,    // 換手率%
        val marketCap: Double = 0.0,       // 總市值(億)
        val changeAmount: Double = 0.0,    // 漲跌額（保留字段，暫不顯示）
        val hasSnapshot: Boolean = true,   // 是否有行情數據
        val source: String = "",           // 來源
        val score: Int = 0                 // 評分
    )

    /** 創建表頭行（8列） */
    fun createHeaderRow(context: Context): LinearLayout {
        val dp = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
        }.also { row ->
            val weights = floatArrayOf(2.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.6f)
            val titles = arrayOf("股票名稱", "板塊", "漲幅", "現價", "市盈", "換手率", "市值", "清空")
            val gravities = intArrayOf(Gravity.START, Gravity.START, Gravity.CENTER, Gravity.END, Gravity.END, Gravity.CENTER, Gravity.END, Gravity.CENTER)
            for (i in titles.indices) {
                row.addView(TextView(context).apply {
                    text = titles[i]
                    textSize = 10f
                    setTextColor(Color.parseColor("#888888"))
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    gravity = gravities[i]
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[i])
                })
            }
        }
    }

    /** 創建數據行（8列）
     * @param onItemClick 點擊行回調（查看詳情）
     * @param onDelete 點擊刪除按鈕回調
     */
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
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
        }

        val weights = floatArrayOf(2.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.6f)

        // 行情顏色（紅漲綠跌）
        val priceColor = when {
            !item.hasSnapshot -> Color.parseColor("#999999")
            item.changePct > 0 -> Color.parseColor("#E53935")
            item.changePct < 0 -> Color.parseColor("#43A047")
            else -> Color.parseColor("#333333")
        }

        // ── 1. 股票名稱 + 代碼（小字） ──
        val nameCell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[0])
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

        // ── 2. 板塊（有子板塊顯示子板塊，否則顯示大類） ──
        row.addView(TextView(context).apply {
            val sectorText = if (item.subSector.isNotBlank() && item.subSector != "其他") {
                item.subSector.take(6)
            } else if (item.sector.isNotBlank() && item.sector != "其他") {
                item.sector.take(6)
            } else ""
            text = sectorText
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[1])
        })

        // ── 3. 漲幅（帶背景色） ──
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
            gravity = Gravity.CENTER
            setPadding((4 * dp).toInt(), (1 * dp).toInt(), (4 * dp).toInt(), (1 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(changeBg)
                cornerRadius = 2f * dp
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[2])
        })

        // ── 4. 現價（紅漲綠跌） ──
        row.addView(TextView(context).apply {
            text = if (item.hasSnapshot) String.format("%.2f", item.price) else "--"
            textSize = 12f
            setTextColor(priceColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[3])
        })

        // ── 5. 市盈率 ──
        row.addView(TextView(context).apply {
            text = if (item.peRatio > 0) String.format("%.1f", item.peRatio) else "-"
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[4])
        })

        // ── 6. 換手率 ──
        row.addView(TextView(context).apply {
            text = if (item.turnoverRate > 0) String.format("%.2f%%", item.turnoverRate) else "-"
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[5])
        })

        // ── 7. 市值(億)（緊凑靠右） ──
        row.addView(TextView(context).apply {
            text = if (item.marketCap > 0) String.format("%.0f", item.marketCap) else "-"
            textSize = 10f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[6])
        })

        // ── 8. 清空（刪除按鈕，紅色小字） ──
        val deleteBtn = TextView(context).apply {
            text = " ✕ "
            textSize = 11f
            setTextColor(Color.parseColor("#E53935"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[7])
            setOnClickListener { onDelete?.invoke(item) }
        }
        row.addView(deleteBtn)

        wrapper.addView(row)

        // 分隔線
        if (!isLast) {
            wrapper.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply {
                    setMargins((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                }
                setBackgroundColor(Color.parseColor("#F0F0F0"))
            })
        }

        // 點擊查看詳情（回調方式）
        if (onItemClick != null) {
            wrapper.setOnClickListener { onItemClick(item) }
        }

        return wrapper
    }
}