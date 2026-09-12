package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity

/**
 * ## MACD 柱底背离分析工具（共用）
 *
 * 供单股 K 线趋势分析（StockDetailFragment）与后续 DAG 技术节点复用。
 * 输入：按日期升序排列的日线列表（建议 ≥ 35 条，MACD(12,26,9) 才收敛）。
 *
 * 判定口径 = 「价格创新低，但 MACD 动能柱的底部在抬高」（不必等三天不新低）：
 *
 * 1. **拐点谷对比（经典底背离）**：取最近两次 MACD 柱局部谷 v1(旧) / v2(新)，
 *    若 v2 所在段价格低点 < v1 所在段价格低点、且 bar(v2) > bar(v1)
 *    → 动能柱谷抬高 = 底背离雏形（低吸关注）。
 * 2. **动能衰减（短线杀跌末端）**：当日价格创近 10 日新低，但绿柱缩短 / 翻红
 *    → 杀跌动能衰竭，同样提示低吸观察。
 *
 * 结果里会带出两谷的日期、柱值、段最低价，供 UI 显示依据，方便人工复核。
 */
object MacdDivergenceAnalyzer {

    data class Result(
        /** v2 段最低价 < v1 段最低价（价格段创新低） */
        val priceNewLow: Boolean,
        /** bar(v2) > bar(v1)，或末端动能修复 */
        val barBottomRising: Boolean,
        /** 拐点谷对比成立 → 经典 MACD 柱底背离 */
        val bullDivergence: Boolean,
        /** 当日价格创近 10 日新低 且 绿柱缩短/翻红（动能衰竭） */
        val macdShrinking: Boolean,
        val valleyDate1: String?,
        val valleyDate2: String?,
        val valleyBar1: Double,
        val valleyBar2: Double,
        val segmentLow1: Double,
        val segmentLow2: Double,
        val lastBar: Double,
        /** 文字摘要（含依据，可直接上 UI） */
        val hint: String
    ) {
        companion object {
            fun empty(reason: String = "数据不足") = Result(
                priceNewLow = false, barBottomRising = false, bullDivergence = false,
                macdShrinking = false, valleyDate1 = null, valleyDate2 = null,
                valleyBar1 = 0.0, valleyBar2 = 0.0,
                segmentLow1 = 0.0, segmentLow2 = 0.0,
                lastBar = 0.0, hint = reason
            )
        }
    }

    /**
     * @param snaps 按日期升序排列的日线
     * @param lookback 柱谷扫描范围（交易日）
     */
    fun analyze(
        snaps: List<DailySnapshotEntity>,
        lookback: Int = 40,
        fast: Int = 12,
        slow: Int = 26,
        signal: Int = 9
    ): Result {
        val n = snaps.size
        if (n < slow + signal + 2) return Result.empty()
        val closes = snaps.map { it.close }
        val lows = snaps.map { it.low }
        val bars = macdBars(closes, fast, slow, signal)
        val scanStart = maxOf(1, n - lookback)

        // ── 1. 从右往左找 MACD 柱局部谷（最多取两个） ──
        val valleys = mutableListOf<Int>()
        var i = n - 2
        while (i > scanStart && valleys.size < 2) {
            if (bars[i] < bars[i - 1] && bars[i] <= bars[i + 1]) valleys.add(i)
            i--
        }
        val v1: Int
        val v2: Int
        when {
            valleys.size >= 2 -> { v2 = valleys[0]; v1 = valleys[1] }
            valleys.size == 1 -> { v2 = valleys[0]; v1 = maxOf(0, v2 - 5) }
            else -> {
                // 区间内无局部谷（单边下跌）：以柱最低点 + 更早参考段
                val minIdx = bars.indices.minByOrNull { bars[it] } ?: (n - 1)
                v2 = minIdx.coerceIn(1, n - 1)
                v1 = maxOf(0, v2 - 5)
            }
        }

        // ── 2. 段低点（谷前后 ±2 日窗口内最低价）与柱值对比 ──
        val seg1 = segmentLow(lows, v1)
        val seg2 = segmentLow(lows, v2)
        val priceDown = seg2 < seg1
        val barUp = bars[v2] > bars[v1]
        val bull = priceDown && barUp

        // ── 3. 动能衰减：当日价格创新低 + 柱回升（绿柱缩短/翻红） ──
        val recent10 = if (n > 10) lows.subList(n - 10, n - 1) else lows.subList(0, n - 1)
        val newLowToday = recent10.isNotEmpty() && lows.last() < (recent10.minOrNull() ?: Double.MAX_VALUE)
        val risingBarToday = bars[n - 1] > bars[n - 2]
        val shrinking = newLowToday && risingBarToday

        val hint = buildString {
            append("柱谷 ${snaps[v1].date}=${fmt(bars[v1])} → ${snaps[v2].date}=${fmt(bars[v2])}")
            append(" | 段低 ${fmt(seg1)} → ${fmt(seg2)}")
            when {
                bull -> append(" → 价格创新低但动能柱底抬高（低吸关注）")
                shrinking -> append(" → 当日创新低但绿柱缩短/翻红（杀跌动能衰竭）")
                bars[n - 1] >= 0 -> append(" → 动能柱已回零轴上")
                else -> append(" → 柱底未抬，等待动能修复")
            }
        }

        return Result(
            priceNewLow = priceDown,
            barBottomRising = barUp || shrinking,
            bullDivergence = bull,
            macdShrinking = shrinking,
            valleyDate1 = snaps[v1].date,
            valleyDate2 = snaps[v2].date,
            valleyBar1 = bars[v1],
            valleyBar2 = bars[v2],
            segmentLow1 = seg1,
            segmentLow2 = seg2,
            lastBar = bars[n - 1],
            hint = hint
        )
    }

    private fun segmentLow(lows: List<Double>, idx: Int): Double {
        val n = lows.size
        val from = maxOf(0, idx - 2)
        val to = minOf(n - 1, idx + 2)
        return lows.subList(from, to + 1).minOrNull() ?: Double.MAX_VALUE
    }

    /** MACD(12,26,9) 柱序列：bar = 2×(DIF−DEA)，红为正/绿为负 */
    private fun macdBars(
        closes: List<Double>,
        fast: Int,
        slow: Int,
        signal: Int
    ): List<Double> {
        val ef = emaSeries(closes, fast)
        val es = emaSeries(closes, slow)
        val dif = List(closes.size) { ef[it] - es[it] }
        val dea = emaSeries(dif, signal)
        return List(closes.size) { 2.0 * (dif[it] - dea[it]) }
    }

    private fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val out = ArrayList<Double>(values.size)
        var e = values[0]
        out.add(e)
        for (i in 1 until values.size) {
            e = values[i] * k + e * (1 - k)
            out.add(e)
        }
        return out
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)
}
