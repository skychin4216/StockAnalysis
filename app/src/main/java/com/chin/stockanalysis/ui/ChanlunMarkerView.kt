package com.chin.stockanalysis.ui

import android.content.Context
import android.widget.TextView
import com.chin.stockanalysis.R
import com.chin.stockanalysis.strategy.chanlun.ChanlunEngine
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

/**
 * 缠论图表十字光标浮窗卡片。
 * 显示：时间 / 价格 / 所在笔方向 / 中枢位置 / 最近买卖点。
 */
class ChanlunMarkerView(
    ctx: Context,
    private val st: ChanlunEngine.Structure,
    private val offset: Int   // 显示区域相对原始 bars 的偏移
) : MarkerView(ctx, R.layout.chanlun_marker) {

    private val tvTime: TextView = findViewById(R.id.tvTime)
    private val tvPrice: TextView = findViewById(R.id.tvPrice)
    private val tvBi: TextView = findViewById(R.id.tvBi)
    private val tvZs: TextView = findViewById(R.id.tvZs)
    private val tvBsp: TextView = findViewById(R.id.tvBsp)

    override fun refreshContent(e: Entry, highlight: Highlight) {
        val idx = offset + e.x.toInt()
        val bar = st.bars.getOrNull(idx)
        if (bar != null) {
            tvTime.text = bar.time
            tvPrice.text = String.format("价 %.2f  开%.2f 高%.2f 低%.2f",
                bar.close, bar.open, bar.high, bar.low)
        }
        // 笔
        val bi = st.bi.find { it.beginIdx <= idx && it.endIdx >= idx }
        tvBi.text = if (bi == null) "" else {
            val dir = if (bi.dir == ChanlunEngine.Dir.UP) "↑上涨笔" else "↓下跌笔"
            val sure = if (bi.sure) "" else "（虚笔）"
            "$dir$sure  #${st.bi.indexOf(bi) + 1}/${st.bi.size}"
        }
        // 中枢位置
        val z = st.zs.find { it.beginIdx <= idx && it.endIdx >= idx }
        tvZs.text = if (z == null) "" else {
            val pos = when {
                bi == null -> ""
                bi.endPrice > z.high -> "上方"
                bi.endPrice < z.low -> "下方"
                else -> "内"
            }
            "中枢#${st.zs.indexOf(z) + 1} $pos [${fmt(z.low)},${fmt(z.high)}]"
        }
        // 最近买卖点（±3根）
        val near = st.bsp.minByOrNull { kotlin.math.abs(it.idx - idx) }
        tvBsp.text = if (near == null || kotlin.math.abs(near.idx - idx) > 3) "" else {
            val side = if (near.isBuy) "买" else "卖"
            val c = if (near.isBuy) "🔴" else "🟢"
            "最近: $c${near.type}$side@${fmt(near.price)}"
        }
        super.refreshContent(e, highlight)
    }

    /** 卡片左上角对齐十字交叉点，右下偏移 16dp */
    override fun getOffset(): MPPointF = MPPointF(-(width / 2).toFloat(), -height - dp16)

    private val dp16 = (16 * context.resources.displayMetrics.density)

    private fun fmt(v: Double): String = String.format("%.2f", v)
}
