package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlin.math.abs
import kotlin.math.min

/**
 * ## 技术假设摘要（双端同源 · 2026-09-10 抽取公共化）
 *
 * 原为 ETF 页的 private `EtfMath`（EtfDipNodes.kt）。三周期选股结果表与「大A祖训」节点
 * 也需要同一套「技术假设」语言（SAR / MACD / OBV / 跌后K形态 / 趋势图），故上提为公共
 * object，供下列三处共用：
 *   · EtfDipNodes（工作台·ETF 页 11 列表）
 *   · AncestralRulesNode（大A祖训：口诀加减分）
 *   · GenerateOrdersNode（短/中/长三周期选股结果表）
 *
 * 口径严格对齐 PC 端：
 *   · `smalltools/_technicals.py` 的 rich_tag（SAR/MACD/OBV/跌后K）
 *   · `assets/usecases/usecase_pipeline.py` 的 `_trend_match_3way`（趋势图三类）
 *
 * ### 双端一致性
 * `toCandles(...)` 把纯数值序列还原成 [DailySnapshotEntity]，使 ETF（读 JSON 缓存）也能
 * 走与 DB 路径完全相同的形态库 / 趋势打分，避免「APK 简版 vs PC 完整版」口径漂移。
 */
object TechTags {

    /** 数值守卫：JSON 缺字段 optDouble 会返回 NaN，先清成兜底值再进指标链。 */
    fun num(v: Double, fb: Double = 0.0): Double = if (v.isFinite()) v else fb

    /** 简单移动平均（返回与原序列等长）。 */
    fun sma(vals: List<Double>, n: Int): List<Double> {
        val out = ArrayList<Double>(vals.size)
        var acc = 0.0
        val q = ArrayDeque<Double>()
        for (v in vals) {
            acc += v
            q.addLast(v)
            if (q.size > n) acc -= q.removeFirst()
            out.add(acc / q.size)
        }
        return out
    }

    /** 指数移动平均（EMA 种子取首值）。 */
    fun emaSeries(vals: List<Double>, n: Int): List<Double> {
        if (vals.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1)
        val out = ArrayList<Double>(vals.size)
        var acc = vals[0]
        out.add(acc)
        for (i in 1 until vals.size) {
            acc = vals[i] * k + acc * (1 - k)
            out.add(acc)
        }
        return out
    }

    /** RSI（默认 6 日，简单平均口径，与 PC `_etf_rsi` 一致）。 */
    fun rsi(vals: List<Double>, n: Int = 6): List<Double> {
        val out = ArrayList<Double>(vals.size)
        out.add(50.0)
        val gains = ArrayDeque<Double>()
        val losses = ArrayDeque<Double>()
        for (i in 1 until vals.size) {
            val ch = vals[i] - vals[i - 1]
            gains.addLast(if (ch > 0) ch else 0.0)
            losses.addLast(if (ch < 0) -ch else 0.0)
            if (gains.size > n) { gains.removeFirst(); losses.removeFirst() }
            val ag = gains.sum() / gains.size
            val al = losses.sum() / losses.size
            out.add(if (al == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + ag / al))
        }
        return out
    }

    /** 量比 vr = 当日量 / 前5日均量；数据不足返回 0.0。 */
    fun volumeRatio(vols: List<Double>): Double {
        if (vols.size < 6) return 0.0
        val prev5 = vols.subList(vols.size - 6, vols.size - 1).sum()
        val avg = prev5 / 5.0
        return if (avg > 0) vols.last() / avg else 0.0
    }

    /** Wilder 抛物线 SAR（step=0.02 max=0.2）。返回与 closes 同长的可空数组。 */
    fun parabolicSar(highs: List<Double>, lows: List<Double>, closes: List<Double>): List<Double?> {
        val n = closes.size
        val sar = arrayOfNulls<Double>(n)
        if (n < 3) return sar.toList()
        var trendUp: Boolean
        var ep: Double
        var af = 0.02
        if (closes[1] >= closes[0]) { trendUp = true; ep = highs[1]; af = 0.02; sar[0] = lows[0] }
        else { trendUp = false; ep = lows[1]; af = 0.02; sar[0] = highs[0] }
        for (i in 1 until n) {
            val base = sar[i - 1] ?: (if (trendUp) lows[i - 1] else highs[i - 1])
            var s = base + af * (ep - base)
            if (trendUp) {
                if (lows[i - 1] < s) s = lows[i - 1]
                if (i >= 2 && lows[i - 2] < s) s = lows[i - 2]
            } else {
                if (highs[i - 1] > s) s = highs[i - 1]
                if (i >= 2 && highs[i - 2] > s) s = highs[i - 2]
            }
            if (trendUp && lows[i] < s) {
                sar[i] = ep; trendUp = false; ep = lows[i]; af = 0.02
            } else if (!trendUp && highs[i] > s) {
                sar[i] = ep; trendUp = true; ep = highs[i]; af = 0.02
            } else {
                sar[i] = s
                if (trendUp && highs[i] > ep) { ep = highs[i]; af = min(af + 0.02, 0.2) }
                else if (!trendUp && lows[i] < ep) { ep = lows[i]; af = min(af + 0.02, 0.2) }
            }
        }
        return sar.toList()
    }

    /** SAR 摘要文本：红↑N / 刚翻红 / 绿↓N / 刚翻绿N天；数据不足返回 ""。 */
    fun sarText(closes: List<Double>, highs: List<Double>, lows: List<Double>): String {
        val n = closes.size
        if (n < 3) return ""
        val sar = parabolicSar(highs, lows, closes)
        var i = n - 1
        while (i >= 0 && sar[i] == null) i--
        if (i < 0) return ""
        val lastUp = closes[i] >= sar[i]!!
        var bars = 0
        var flipDir: String? = null
        var j = i
        while (j >= 0) {
            val sv = sar[j] ?: break
            val up = closes[j] >= sv
            if (up == lastUp) { bars++; j-- } else { flipDir = if (up) "UP" else "DOWN"; break }
        }
        val ago = bars
        return when {
            lastUp && flipDir == "UP" && ago in 1..3 -> "刚翻红"
            lastUp -> "红↑$bars"
            !lastUp && flipDir == "DOWN" && ago in 1..3 -> "刚翻绿${ago}天"
            else -> "绿↓$bars"
        }
    }

    /** MACD(12,26,9) 摘要：金叉 / 死叉 / 红绿柱收窄-扩大；数据不足返回 ""。 */
    fun macdText(closes: List<Double>): String {
        if (closes.size < 26) return ""
        val dif = emaSeries(closes, 12)
        val dea = emaSeries(dif, 9)
        val hist = dif[dif.size - 1] - dea[dea.size - 1]
        val prev = if (dif.size > 1) dif[dif.size - 2] - dea[dea.size - 2] else hist
        return when {
            prev <= 0.0 && hist > 0.0 -> "金叉"
            prev >= 0.0 && hist < 0.0 -> "死叉"
            else -> {
                val color = if (hist >= 0.0) "红柱" else "绿柱"
                val trend = if (abs(hist) < abs(prev)) "收窄" else "扩大"
                "$color$trend"
            }
        }
    }

    /** OBV(20日均线)方向：OBV上行 / OBV下行；数据不足返回 null。 */
    fun obvText(closes: List<Double>, vols: List<Double>): String? {
        if (closes.size < 21) return null
        val obv = ArrayList<Double>(closes.size)
        obv.add(0.0)
        for (i in 1 until closes.size) {
            obv.add(obv.last() + when {
                closes[i] > closes[i - 1] -> vols[i]
                closes[i] < closes[i - 1] -> -vols[i]
                else -> 0.0
            })
        }
        val last = obv.last()
        if (!last.isFinite()) return null
        val obv20 = obv.takeLast(20).average()
        if (!obv20.isFinite()) return null
        return if (obv20 == 0.0) null else if (last >= obv20) "OBV上行" else "OBV下行"
    }

    /** K线形态摘要：多日下跌后是否出现 小阳/小阴/大阳/大阴（±1.5% 分界，与 PC 同口径）。
     *  例：今日阴跌延续 → "连跌4·小阴"；今日企稳收阳 → "跌3后小阳"。 */
    fun klineDesc(closes: List<Double>, i: Int): String {
        val chg = if (i >= 1 && closes[i - 1] > 0) (closes[i] / closes[i - 1] - 1) * 100.0 else 0.0
        val size = when {
            chg >= 1.5 -> "大阳"
            chg > 0.0 -> "小阳"
            chg >= -1.5 -> "小阴"
            else -> "大阴"
        }
        var down = 0
        var j = i
        while (j > 0 && closes[j] < closes[j - 1]) { down++; j-- }
        if (down >= 1) return "连跌$down·$size"
        var downEx = 0
        var k = i - 1
        while (k > 0 && closes[k] < closes[k - 1]) { downEx++; k-- }
        return if (downEx >= 1) "跌${downEx}后$size" else "今日$size"
    }

    /** 末根/末两根的经典形态（十字星/吞没/锤子线），与 PC 引擎形态命名对齐。 */
    fun lastPatternLabel(closes: List<Double>, opens: List<Double>,
                         highs: List<Double>, lows: List<Double>): String {
        val n = closes.size
        if (n < 4) return ""
        val c1 = closes[n - 1]; val o1 = opens[n - 1]
        val h1 = highs[n - 1]; val l1 = lows[n - 1]
        val c2 = closes[n - 2]; val o2 = opens[n - 2]
        val body1 = abs(c1 - o1)
        val rng1 = (h1 - l1).coerceAtLeast(1e-9)
        val bull1 = c1 > o1
        val bull2 = c2 > o2
        return when {
            body1 / rng1 < 0.1 -> "十字星"
            bull1 && !bull2 && c1 >= o2 && o1 <= c2 -> "看涨吞没"
            !bull1 && bull2 && c1 <= o2 && o1 >= c2 -> "看跌吞没"
            (min(o1, c1) - l1) > 2 * body1 && (h1 - maxOf(o1, c1)) < body1 -> "锤子线"
            else -> ""
        }
    }

    /**
     * 纯数值序列 → 形态库可用的 K 线实体。
     *
     * 目的：让读 JSON 缓存（无 DB 实体）的 ETF 页，也能复用与 DB 完全相同的
     * [CandlePatternDetector] / 趋势打分，杜绝双端口径漂移。
     * 只填形态判定与趋势打分用得到的字段（open/close/high/low/volume），其余保留默认。
     */
    fun toCandles(
        code: String,
        closes: List<Double>,
        opens: List<Double> = emptyList(),
        highs: List<Double> = emptyList(),
        lows: List<Double> = emptyList(),
        vols: List<Double> = emptyList()
    ): List<DailySnapshotEntity> {
        val n = closes.size
        return (0 until n).map { i ->
            val c = closes[i]
            val o = opens.getOrElse(i) { c }
            val h = maxOf(highs.getOrElse(i) { c }, o, c)
            val l = minOf(lows.getOrElse(i) { c }, o, c)
            val prev = if (i > 0) closes[i - 1] else c
            DailySnapshotEntity(
                code = code,
                name = "",
                date = String.format(java.util.Locale.US, "%05d", i),
                open = o,
                close = c,
                high = h,
                low = l,
                volume = vols.getOrElse(i) { 0.0 }.toLong(),
                amount = 0.0,
                changePct = if (prev > 0) (c / prev - 1.0) * 100.0 else 0.0
            )
        }
    }
}
