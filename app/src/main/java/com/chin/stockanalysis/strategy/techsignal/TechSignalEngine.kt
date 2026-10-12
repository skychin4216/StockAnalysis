package com.chin.stockanalysis.strategy.techsignal

import kotlin.math.abs

/**
 * ## 技术指标多信号选股引擎（量化选股 → 技术指标策略）
 *
 * 与短线/中线 DAG 平级的独立打分引擎：6 个技术信号各自可开关，
 * 每个信号买入确认计 20 分，选股结果包含每个信号的评分。
 *
 * 信号定义（均以最新一根日K“买入确认”为准，交叉类信号允许在最近 [RECENT_BARS] 根内确认）：
 *  1. MACD(6,13,9)：绿色（负）柱绝对值见顶后开始收缩=买；红色（正）柱见顶、下一根缩短=卖。
 *  2. WR：WR1/WR2 均处 >65 后回落、任一跌破 65=买；<20 后回升、均站上 20=卖。
 *  3. RSI：RSI6 > 50=多，< 50=空。
 *  4. OBV：OBV 在 MAOBV(30) 上方=多；回踩 MAOBV 附近=加仓；跌破=卖。
 *  5. SKDJ(9,3)：K 上穿 D 金叉=买，下穿死叉=卖。
 *  6. BIAS-QL：BIAS(6) 上穿 BIASMA(6) 金叉=买；金叉后 BIAS 自高点回落=卖。
 *
 * 纯 Kotlin，无 Android 依赖，bars 为旧→新升序。
 */
object TechSignalEngine {

    const val SCORE_PER_SIGNAL = 20
    private const val RECENT_BARS = 3   // 交叉类信号“最近确认”的容忍根数

    enum class Sig(val key: String, val label: String) {
        MACD("macd", "MACD(6,13,9)"),
        WR("wr", "WR1/WR2"),
        RSI("rsi", "RSI6"),
        OBV("obv", "OBV/MAOBV"),
        SKDJ("skdj", "SKDJ(9,3)"),
        BIAS("bias", "BIAS-QL")
    }

    data class TechBar(
        val date: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double
    )

    /** 单信号评估结果（结果列表每信号的评分明细） */
    data class Eval(
        val sig: Sig,
        val enabled: Boolean,
        val bullish: Boolean,   // 最新买入是否确认
        val score: Int,         // 0 或 20
        val state: String       // 人类可读状态
    )

    /** 可绘制序列：line 名称→值（NaN 为暖机期）；bars 为柱状（如 MACD）；refs 为参考水平线 */
    data class Plot(
        val lines: List<Pair<String, DoubleArray>>,
        val bars: DoubleArray? = null,
        val barColorsUp: Boolean = true,  // 柱正值是否红色（国内配色）
        val refs: List<Pair<Double, String>> = emptyList(),
        val buys: List<Int> = emptyList(),
        val sells: List<Int> = emptyList(),
        val inverted: Boolean = false
    )

    data class Outcome(
        val evals: List<Eval>,
        val total: Int,
        val hitCount: Int,
        val enabledCount: Int,
        val plots: Map<Sig, Plot>
    ) {
        fun eval(sig: Sig): Eval? = evals.firstOrNull { it.sig == sig }
    }

    // ═══════════════════════ 入口 ═══════════════════════

    fun evaluate(bars: List<TechBar>, enabled: Set<Sig>): Outcome? {
        if (bars.size < 35) return null
        val n = bars.size
        val close = DoubleArray(n) { bars[it].close }
        val high = DoubleArray(n) { bars[it].high }
        val low = DoubleArray(n) { bars[it].low }
        val vol = DoubleArray(n) { bars[it].volume }

        val evals = ArrayList<Eval>(6)
        val plots = LinkedHashMap<Sig, Plot>()

        // ── 1. MACD(6,13,9) ──
        run {
            val dif = emaSeries(close, 6).zip(emaSeries(close, 13)) { a, b -> if (a.isNaN() || b.isNaN()) Double.NaN else a - b }
                .toDoubleArray()
            val dea = emaSeries(dif, 9)
            val macdBar = DoubleArray(n) { i -> if (dif[i].isNaN() || dea[i].isNaN()) Double.NaN else (dif[i] - dea[i]) * 2.0 }
            val buys = ArrayList<Int>(); val sells = ArrayList<Int>()
            for (t in 2 until n) {
                if (macdBar[t].isNaN() || macdBar[t - 1].isNaN() || macdBar[t - 2].isNaN()) continue
                // 绿色（负）柱：前一根还在放大（更负），本根首次收缩
                if (macdBar[t - 1] < 0 && macdBar[t] > macdBar[t - 1] && macdBar[t - 1] <= macdBar[t - 2]) buys.add(t)
                // 红色（正）柱：前一根见顶（更大），本根首次缩短
                if (macdBar[t - 1] > 0 && macdBar[t] < macdBar[t - 1] && macdBar[t - 1] >= macdBar[t - 2]) sells.add(t)
            }
            val bull = recentConfirmed(buys, n)
            val state = when {
                bull -> "绿柱收缩，买入确认"
                macdBar.lastOrNull()?.let { !it.isNaN() && it > 0 } == true -> "红柱多头"
                else -> "绿柱空头"
            }
            plots[Sig.MACD] = Plot(
                lines = listOf("DIF" to dif, "DEA" to dea),
                bars = macdBar,
                refs = listOf(0.0 to ""),
                buys = buys, sells = sells
            )
            evals += Eval(Sig.MACD, Sig.MACD in enabled, bull, if (bull && Sig.MACD in enabled) SCORE_PER_SIGNAL else 0, state)
        }

        // ── 2. WR1 / WR2 ──
        run {
            val wr1 = wrSeries(high, low, close, 1)
            val wr2 = wrSeries(high, low, close, 2)
            val buys = ArrayList<Int>(); val sells = ArrayList<Int>()
            for (t in 1 until n) {
                if (wr1[t].isNaN() || wr2[t].isNaN() || wr1[t - 1].isNaN() || wr2[t - 1].isNaN()) continue
                // >65 区回落，任一跌破 65
                if (wr1[t - 1] > 65 && wr2[t - 1] > 65 && (minOf(wr1[t], wr2[t]) < 65)) buys.add(t)
                // <20 区回升，均站上 20
                if (minOf(wr1[t - 1], wr2[t - 1]) < 20 && minOf(wr1[t], wr2[t]) > 20) sells.add(t)
            }
            val bull = recentConfirmed(buys, n)
            val cur1 = wr1.lastOrNull { !it.isNaN() }
            val cur2 = wr2.lastOrNull { !it.isNaN() }
            val state = "WR1=${cur1?.let { "%.0f".format(it) } ?: "-"} WR2=${cur2?.let { "%.0f".format(it) } ?: "-"}" +
                if (bull) " · 高位回落买" else ""
            plots[Sig.WR] = Plot(
                lines = listOf("WR1" to wr1, "WR2" to wr2),
                refs = listOf(65.0 to "65", 20.0 to "20"),
                buys = buys, sells = sells,
                inverted = true
            )
            evals += Eval(Sig.WR, Sig.WR in enabled, bull, if (bull && Sig.WR in enabled) SCORE_PER_SIGNAL else 0, state)
        }

        // ── 3. RSI6 ──
        run {
            val rsi = rsiSeries(close, 6)
            val buys = ArrayList<Int>(); val sells = ArrayList<Int>()
            for (t in 1 until n) {
                if (rsi[t].isNaN() || rsi[t - 1].isNaN()) continue
                if (rsi[t - 1] <= 50 && rsi[t] > 50) buys.add(t)
                if (rsi[t - 1] >= 50 && rsi[t] < 50) sells.add(t)
            }
            val cur = rsi.lastOrNull { !it.isNaN() } ?: 50.0
            val bull = cur > 50
            plots[Sig.RSI] = Plot(
                lines = listOf("RSI6" to rsi),
                refs = listOf(50.0 to "50", 80.0 to "80", 20.0 to "20"),
                buys = buys, sells = sells
            )
            evals += Eval(Sig.RSI, Sig.RSI in enabled, bull, if (bull && Sig.RSI in enabled) SCORE_PER_SIGNAL else 0,
                "RSI6=${"%.1f".format(cur)} " + if (bull) "多头(>50)" else "空头(<50)")
        }

        // ── 4. OBV + MAOBV(30) ──
        run {
            val obv = DoubleArray(n)
            for (t in 1 until n) {
                obv[t] = obv[t - 1] + when {
                    close[t] > close[t - 1] -> vol[t]
                    close[t] < close[t - 1] -> -vol[t]
                    else -> 0.0
                }
            }
            val maobv = smaSeries(obv, 30)
            val buys = ArrayList<Int>(); val sells = ArrayList<Int>()
            var addHint = ""
            for (t in 1 until n) {
                if (maobv[t].isNaN() || maobv[t - 1].isNaN()) continue
                if (obv[t - 1] <= maobv[t - 1] && obv[t] > maobv[t]) buys.add(t)
                if (obv[t - 1] >= maobv[t - 1] && obv[t] < maobv[t]) sells.add(t)
            }
            val bull = !maobv[n - 1].isNaN() && obv[n - 1] > maobv[n - 1]
            // 回踩 MAOBV 附近（±3%）且仍在上方 → 加仓
            if (bull && abs(maobv[n - 1]) > 0 && abs(obv[n - 1] - maobv[n - 1]) / abs(maobv[n - 1]) <= 0.03) {
                addHint = " · 回踩均线可加仓"
            }
            plots[Sig.OBV] = Plot(
                lines = listOf("OBV" to obv, "MAOBV30" to maobv),
                buys = buys, sells = sells
            )
            evals += Eval(Sig.OBV, Sig.OBV in enabled, bull, if (bull && Sig.OBV in enabled) SCORE_PER_SIGNAL else 0,
                if (bull) "OBV 在均线上方$addHint" else "OBV 跌破均线")
        }

        // ── 5. SKDJ(9,3) ──
        run {
            val rsv = DoubleArray(n) { Double.NaN }
            for (t in 8 until n) {
                var hh = Double.MIN_VALUE; var ll = Double.MAX_VALUE
                for (j in t - 8..t) { if (high[j] > hh) hh = high[j]; if (low[j] < ll) ll = low[j] }
                rsv[t] = if (hh > ll) (close[t] - ll) / (hh - ll) * 100 else 50.0
            }
            val k = DoubleArray(n) { Double.NaN }; val d = DoubleArray(n) { Double.NaN }
            var pk = 50.0; var pd = 50.0
            for (t in 0 until n) {
                if (!rsv[t].isNaN()) {
                    pk = (2 * pk + rsv[t]) / 3
                    pd = (2 * pd + pk) / 3
                    k[t] = pk; d[t] = pd
                }
            }
            val buys = ArrayList<Int>(); val sells = ArrayList<Int>()
            for (t in 1 until n) {
                if (k[t].isNaN() || k[t - 1].isNaN()) continue
                if (k[t - 1] <= d[t - 1] && k[t] > d[t]) buys.add(t)
                if (k[t - 1] >= d[t - 1] && k[t] < d[t]) sells.add(t)
            }
            val bull = (!k[n - 1].isNaN() && k[n - 1] > d[n - 1]) && recentConfirmed(buys, n)
            plots[Sig.SKDJ] = Plot(
                lines = listOf("K" to k, "D" to d),
                refs = listOf(80.0 to "80", 20.0 to "20"),
                buys = buys, sells = sells
            )
            evals += Eval(Sig.SKDJ, Sig.SKDJ in enabled, bull, if (bull && Sig.SKDJ in enabled) SCORE_PER_SIGNAL else 0,
                if (bull) "K上穿D 金叉" else "无金叉")
        }

        // ── 6. BIAS-QL：BIAS(6) 与 BIASMA(6) ──
        run {
            val ma6 = smaSeries(close, 6)
            val bias = DoubleArray(n) { i -> if (ma6[i].isNaN() || ma6[i] == 0.0) Double.NaN else (close[i] - ma6[i]) / ma6[i] * 100 }
            val biasMa = smaSeries(bias, 6)
            val buys = ArrayList<Int>(); val sells = ArrayList<Int>()
            for (t in 1 until n) {
                if (bias[t].isNaN() || bias[t - 1].isNaN() || biasMa[t].isNaN() || biasMa[t - 1].isNaN()) continue
                if (bias[t - 1] <= biasMa[t - 1] && bias[t] > biasMa[t]) buys.add(t)
                // 金叉后 BIAS 自前高回落
                if (t >= 2 && !bias[t - 2].isNaN() && bias[t] > biasMa[t] &&
                    bias[t - 1] >= bias[t - 2] && bias[t] < bias[t - 1]) sells.add(t)
            }
            val bull = (!bias[n - 1].isNaN() && !biasMa[n - 1].isNaN() && bias[n - 1] > biasMa[n - 1]) &&
                recentConfirmed(buys, n)
            plots[Sig.BIAS] = Plot(
                lines = listOf("BIAS" to bias, "BIASMA" to biasMa),
                refs = listOf(0.0 to "0"),
                buys = buys, sells = sells
            )
            evals += Eval(Sig.BIAS, Sig.BIAS in enabled, bull, if (bull && Sig.BIAS in enabled) SCORE_PER_SIGNAL else 0,
                if (bull) "BIAS 金叉多头" else "无金叉")
        }

        val enabledEvals = evals.filter { it.enabled }
        val total = enabledEvals.sumOf { it.score }
        val hit = enabledEvals.count { it.bullish }
        return Outcome(evals, total, hit, enabledEvals.size, plots)
    }

    /** 交叉类买入是否在最近 RECENT_BARS 根内确认（含最新根） */
    private fun recentConfirmed(buys: List<Int>, n: Int): Boolean {
        val last = buys.lastOrNull() ?: return false
        return (n - 1 - last) in 0..RECENT_BARS
    }

    // ═══════════════════════ 基础序列 ═══════════════════════

    private fun emaSeries(v: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (v.isEmpty()) return out
        val k = 2.0 / (period + 1)
        var ema = Double.NaN
        for (i in v.indices) {
            if (v[i].isNaN()) continue
            ema = if (ema.isNaN()) v[i] else v[i] * k + ema * (1 - k)
            // 暖机：攒够 period 个有效值后才输出
            if (i >= period - 1) out[i] = ema
        }
        return out
    }

    private fun smaSeries(v: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        var sum = 0.0
        for (i in v.indices) {
            if (v[i].isNaN()) continue
            sum += v[i]
            if (i >= period) {
                val old = v[i - period]
                if (!old.isNaN()) sum -= old
            }
            if (i >= period - 1) {
                // 确保窗口内均为有效值
                val ok = (i - period + 1..i).all { !v[it].isNaN() }
                if (ok) out[i] = sum / period
            }
        }
        return out
    }

    /** 威廉 WR（国内口径）：(N日最高-收盘)/(N日最高-N日最低)×100，0~100 */
    private fun wrSeries(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(close.size) { Double.NaN }
        for (t in period - 1 until close.size) {
            var hh = Double.MIN_VALUE; var ll = Double.MAX_VALUE
            for (j in t - period + 1..t) { if (high[j] > hh) hh = high[j]; if (low[j] < ll) ll = low[j] }
            out[t] = if (hh > ll) (hh - close[t]) / (hh - ll) * 100 else 50.0
        }
        return out
    }

    /** RSI（Wilder 平滑）完整序列 */
    private fun rsiSeries(close: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(close.size) { Double.NaN }
        if (close.size <= period) return out
        var avgGain = 0.0; var avgLoss = 0.0
        for (i in 1..period) {
            val diff = close[i] - close[i - 1]
            if (diff >= 0) avgGain += diff else avgLoss -= diff
        }
        avgGain /= period; avgLoss /= period
        out[period] = if (avgLoss == 0.0) 100.0 else 100 - 100 / (1 + avgGain / avgLoss)
        for (i in period + 1 until close.size) {
            val diff = close[i] - close[i - 1]
            val g = if (diff > 0) diff else 0.0
            val l = if (diff < 0) -diff else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
            out[i] = if (avgLoss == 0.0) 100.0 else 100 - 100 / (1 + avgGain / avgLoss)
        }
        return out
    }
}
