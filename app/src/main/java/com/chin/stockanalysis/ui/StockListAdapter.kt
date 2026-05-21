package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.R

/**
 * 股票列表 Adapter - 同花顺/东方财富风格
 *
 * 每行显示：
 * - 左侧：股票名称（粗体）+ 代码（灰色）
 * - 右侧：现价（粗体）+ 涨跌幅徽标（A股：涨=红色，跌=绿色）
 */
class StockListAdapter(
    private val stocks: List<StockListFragment.StockItem>,
    private val onItemClick: (StockListFragment.StockItem) -> Unit
) : RecyclerView.Adapter<StockListAdapter.ViewHolder>() {

    /** A股颜色规范：涨=红，跌=绿（与西方相反） */
    private val colorUp = Color.parseColor("#E53935")     // 上涨红
    private val colorDown = Color.parseColor("#00897B")   // 下跌绿
    private val colorFlat = Color.parseColor("#888888")   // 平盘灰

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stock, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(stocks[position])
        // 相隔行背景色（斑马纹，提升可读性）
        holder.itemView.setBackgroundColor(
            if (position % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE
        )
    }

    override fun getItemCount() = stocks.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvStockName)
        private val tvCode: TextView = itemView.findViewById(R.id.tvStockCode)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvPrice)
        private val tvChange: TextView = itemView.findViewById(R.id.tvChange)

        fun bind(stock: StockListFragment.StockItem) {
            // ── 左侧：名称 + 代码 ──
            tvName.text = stock.name
            tvCode.text = stock.code.takeLast(6)   // 只显示6位代码（去掉 sh/sz 前缀）

            // ── 右侧：价格 ──
            tvPrice.text = stock.price

            // ── 右侧：涨跌幅徽标 ──
            val changeText = stock.change
            val isUp = changeText.startsWith("+") || (!changeText.startsWith("-") && changeText != "0.00%")
            val isDown = changeText.startsWith("-")

            tvChange.text = changeText
            val badgeColor = when {
                isUp -> colorUp
                isDown -> colorDown
                else -> colorFlat
            }
            // 设置带圆角的背景徽标
            tvChange.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f.dpToPx(itemView)
                setColor(badgeColor)
            }

            // 价格颜色跟随涨跌
            tvPrice.setTextColor(badgeColor)

            // 点击事件
            itemView.setOnClickListener { onItemClick(stock) }
        }

        private fun Float.dpToPx(view: View): Float {
            return this * view.resources.displayMetrics.density
        }
    }
}
