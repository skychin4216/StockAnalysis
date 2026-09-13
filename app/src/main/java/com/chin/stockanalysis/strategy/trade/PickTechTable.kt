package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.chin.stockanalysis.strategy.analysis.TechTags

/**
 * 一行「技术假设」选股结果（工作台·ETF 表与短/中/长三周期结果表共用）。
 *
 * 字段口径见 `strategy/analysis/TechTags.kt`（APK）与 `usecases/usecase_pipeline.py`
 * 的 `_etf_*` 技术函数（PC），双端同源。
 */
data class PickTechRow(
    val code: String,
    val name: String,
    val close: Double,
    val dd60: Double,      // 距 60 日高回撤 %
    val rsi6: Double,
    val sar: String,
    val macd: String,
    val obv: String,
    val kline: String,     // 跌后K形态
    val trend: String,     // 趋势图：↑上涨·形态 / →中性 / ↓下跌·形态
    val inst: String = "—",  // 机构股：A·机构加仓 / B·机构参与 / C·散户票 / —（无季报数据）
    val status: String     // 信号 / 观察 / 入选
)

/**
 * ## 选股结果技术表（2026-09-10 抽取公共组件）
 *
 * 把工作台·ETF 页的 11 列技术假设表（名称/代码/收盘/回撤60/RSI6/SAR/MACD/OBV/跌后K/趋势图/状态）
 * 抽成公共组件，供 **ETF 页** 与 **短/中/长三周期结果区** 共用 —— 一处样式、四处一致，
 * 避免「ETF 有表格而三周期只有文字」的割裂。
 *
 * 视觉与 ETF 页保持一致：深蓝表头白字 + 深底行 + 横向滚动 + 指标语义配色（红涨绿跌）。
 */
object PickTechTable {

    private val COL_HEAD = listOf(
        "名称", "代码", "收盘", "回撤60", "RSI6", "SAR", "MACD", "OBV", "跌后K", "趋势图", "机构股", "状态"
    )
    private val COL_W = intArrayOf(72, 74, 64, 54, 42, 64, 76, 64, 76, 104, 84, 44)

    private const val COLOR_UP = 0xFFFF5A5A.toInt()     // 红（涨 / 多头）
    private const val COLOR_DOWN = 0xFF34C759.toInt()   // 绿（跌 / 空头）
    private const val COLOR_FLAT = 0xFFC9CDD4.toInt()   // 灰（中性）

    /** 渲染整张表（含横向滚动）到容器。 */
    fun render(
        ctx: Context,
        container: LinearLayout,
        rows: List<PickTechRow>,
        onRowClick: ((PickTechRow) -> Unit)? = null
    ) {
        container.removeAllViews()
        val table = TableLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        addHeader(ctx, table)
        if (rows.isEmpty()) {
            table.addView(TextView(ctx).apply {
                text = "  （暂无入选标的）"
                textSize = 12f
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
            })
        } else {
            rows.forEach { fillRow(ctx, table, it, onRowClick) }
        }
        container.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = true
            addView(table)
        })
    }

    /**
     * 由 DB 日K快照构建一行「技术假设」（口径与工作台·ETF 页 / 选股 Pipeline 完全一致）。
     *
     * 2026-09-10 收敛：该逻辑原先只存在于 QuantTradingPipeline.GenerateOrdersNode 的私有方法里，
     * 于是三周期结果区在「重启 App / 换页后从 user_watchlist 恢复选股」时拿不到行数据 → 表格空白。
     * 现抽成公共构建器，Pipeline 与三周期 UI 共用同一实现，杜绝双份口径漂移。
     *
     * @return 数据不足 30 根（新股 / 长期停牌）返回 null，调用方跳过该行而非展示异常值
     */
    suspend fun buildRow(
        ctx: Context,
        code: String,
        name: String,
        status: String
    ): PickTechRow? = buildRow(
        com.chin.stockanalysis.stock.database.StockDatabase.getInstance(ctx), code, name, status, ctx
    )

    /** 同上（已持有 db 实例时用，避免重复 getInstance）。 */
    suspend fun buildRow(
        db: com.chin.stockanalysis.stock.database.StockDatabase,
        code: String,
        name: String,
        status: String,
        ctx: Context? = null
    ): PickTechRow? {
        val candles = try {
            db.dailySnapshotDao().getByCode(code, 60).sortedBy { it.date }
        } catch (_: Exception) {
            emptyList()
        }
        if (candles.size < 30) return null
        val closes = candles.map { it.close }
        val opens = candles.map { it.open }
        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val vols = candles.map { it.volume.toDouble() }
        val close = closes.last()
        val hi60 = highs.maxOrNull() ?: close
        val dd60 = if (hi60 > 0) (hi60 - close) / hi60 * 100 else 0.0
        val r6 = TechTags.rsi(closes, 6).lastOrNull() ?: 50.0
        val ma5 = TechTags.sma(closes, 5).lastOrNull() ?: close
        val ma10 = TechTags.sma(closes, 10).lastOrNull() ?: close
        val ma20 = TechTags.sma(closes, 20).lastOrNull() ?: close
        val dir = when {
            ma5 > ma10 && ma10 > ma20 -> "↑上涨"
            ma5 < ma10 && ma10 < ma20 -> "↓下跌"
            else -> "→中性"
        }
        val pat = TechTags.lastPatternLabel(closes, opens, highs, lows)
        return PickTechRow(
            code = code,
            name = name,
            close = close,
            dd60 = dd60,
            rsi6 = r6,
            sar = TechTags.sarText(closes, highs, lows).ifBlank { "-" },
            macd = TechTags.macdText(closes).ifBlank { "-" },
            obv = TechTags.obvText(closes, vols) ?: "-",
            kline = TechTags.klineDesc(closes, closes.size - 1),
            trend = if (pat.isBlank()) dir else "$dir·$pat",
            // 机构股列：读 data/_inst_holdings.json（与 PC 推送表 _inst_cell 同口径）；
            // 无 ctx（旧调用）或无季报数据 → "—"，不影响其余技术列。
            inst = ctx?.let {
                com.chin.stockanalysis.strategy.topology.nodes.InstHoldingsStore.gradeLabel(it, code)
            } ?: "—",
            status = status
        )
    }

    /** 表头：深蓝底白字（与推送选股图 / ETF 页一致）。 */
    private fun addHeader(ctx: Context, t: TableLayout) {
        val row = TableRow(ctx)
        COL_HEAD.forEachIndexed { idx, c ->
            row.addView(TextView(ctx).apply {
                text = c
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#3B5B92"))
                setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                minimumWidth = dp(ctx, COL_W.getOrElse(idx) { 60 })
            })
        }
        t.addView(row)
    }

    private fun fillRow(ctx: Context, t: TableLayout, r: PickTechRow, onRowClick: ((PickTechRow) -> Unit)?) {
        val vals = listOf(
            r.name, r.code,
            String.format("%.3f", r.close),
            String.format("%.1f", r.dd60),
            String.format("%.0f", r.rsi6),
            r.sar.ifBlank { "-" }, r.macd.ifBlank { "-" }, r.obv.ifBlank { "-" },
            r.kline.ifBlank { "-" }, r.trend.ifBlank { "—" },
            r.inst.ifBlank { "—" }, r.status
        )
        val row = TableRow(ctx)
        vals.forEachIndexed { idx, v ->
            val tv = TextView(ctx).apply {
                text = v
                textSize = 11.5f
                gravity = if (idx in 2..4) Gravity.END else Gravity.CENTER
                setTextColor(cellColor(idx, v))
                if (idx == 0) setTypeface(typeface, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#171E28"))
                setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                minimumWidth = dp(ctx, COL_W.getOrElse(idx) { 60 })
            }
            row.addView(tv, TableRow.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 1) })
        }
        onRowClick?.let { cb -> row.setOnClickListener { cb(r) } }
        t.addView(row)
    }

    /** 指标语义配色（红涨绿跌）：回撤/RSI/SAR/MACD/OBV/K形态/趋势图。 */
    private fun cellColor(idx: Int, v: String): Int = when (idx) {
        3 -> if (v.startsWith("-")) COLOR_DOWN else COLOR_FLAT          // 回撤60
        4 -> when {
            v.toDoubleOrNull()?.let { it < 30 } == true -> COLOR_DOWN
            v.toDoubleOrNull()?.let { it > 70 } == true -> COLOR_UP
            else -> COLOR_FLAT
        }
        5 -> if (v.contains("红")) COLOR_UP else if (v.contains("绿")) COLOR_DOWN else COLOR_FLAT
        6 -> if (v.contains("金叉") || v.contains("红柱")) COLOR_UP
        else if (v.contains("死叉") || v.contains("绿柱")) COLOR_DOWN else COLOR_FLAT
        7 -> if (v.contains("上行")) COLOR_UP else if (v.contains("下行")) COLOR_DOWN else COLOR_FLAT
        8 -> if (v.contains("阳")) COLOR_UP else if (v.contains("阴")) COLOR_DOWN else COLOR_FLAT
        9 -> when {
            v.contains("↑") || v.contains("上涨") -> COLOR_UP
            v.contains("↓") || v.contains("下跌") -> COLOR_DOWN
            else -> COLOR_FLAT
        }
        10 -> when {                                              // 机构股（季报定性）
            v.startsWith("A") -> COLOR_UP                          // A·机构加仓 = 红
            v.startsWith("C") -> COLOR_DOWN                        // C·散户票 = 绿
            else -> COLOR_FLAT
        }
        11 -> if (v.contains("信号") || v.contains("入选")) COLOR_UP else COLOR_FLAT
        else -> Color.parseColor("#E6E8EC")
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
