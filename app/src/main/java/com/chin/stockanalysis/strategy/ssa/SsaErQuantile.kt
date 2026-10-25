package com.chin.stockanalysis.strategy.ssa

/**
 * ## ER 自身时序分位（erq）· Kotlin 版（2026-10-20 回测采纳）
 *
 * 移植自 `smalltools/_ssa_t0_engine.py --erq` 预计算段，口径逐行对齐：
 * - ER（Kaufman 效率比，W=60）：`er[t] = |C[t] − C[t−60]| / Σ|ΔC|`（窗口含 t 共 61 点），
 *   与 PC `_bbi_er_vs_ssa.er_series` / 本机 [SsaErLocal.erOf] 同口径。
 * - 自身时序滚动分位：`erPct[t]` = 过去 [WIN]=250 根（**不含 t**）中 ER < er[t] 的占比×100，
 *   有效样本 <60 → NaN。无未来函数（只用 t 之前的历史）。
 *
 * 与横截面口径（SsaErLocal.percentilesFor）的区别：不依赖全池数据，单票自洽——
 * 这是选它做 T0 做T闸的决定性工程优势（APK 无需等全池横截面）。
 *
 * 全池回测（171只 1H，rsi+adx 之上）：横截面闸 -4.28%/-22.06%/59票 →
 * 自身时序闸 -4.09%/-22.05%/60票，统计同级、93/171 票更优。
 */
object SsaErQuantile {

    /** ER 窗口（根），与 PC er_series 的 w=60 一致 */
    const val W = 60

    /** 自身时序分位滚动窗（根，不含当根） */
    const val WIN = 250

    /** 分位有效所需的最小历史样本数 */
    const val MIN_SAMPLES = 60

    /**
     * 全序列 ER：out[t] = |C[t]−C[t−W]| / Σ|ΔC|（t<W 为 NaN）。
     * 与 Python er_series 同口径（窗口 = closes[t-W..t] 共 W+1 点）。
     */
    fun erSeries(closes: DoubleArray, w: Int = W): DoubleArray {
        val n = closes.size
        val out = DoubleArray(n) { Double.NaN }
        for (t in w until n) {
            var path = 0.0
            var ok = true
            for (k in (t - w + 1)..t) {
                val a = closes[k]; val b = closes[k - 1]
                if (!a.isFinite() || !b.isFinite()) { ok = false; break }
                path += kotlin.math.abs(a - b)
            }
            if (!ok) continue
            val net = kotlin.math.abs(closes[t] - closes[t - w])
            out[t] = if (path > 0.0) net / path else 0.0
        }
        return out
    }

    /**
     * 自身时序滚动分位：erPct[t] = 过去 [win] 根（不含 t）中 ER < er[t] 的占比×100。
     * 历史不足 [MIN_SAMPLES] 或 er[t] 非有限 → NaN。与 Python `--erq` 预计算逐行对齐。
     */
    fun rollingPct(er: DoubleArray, win: Int = WIN): DoubleArray {
        val n = er.size
        val out = DoubleArray(n) { Double.NaN }
        for (t in 1 until n) {
            val e = er[t]
            if (!e.isFinite()) continue
            val from = maxOf(0, t - win)
            var cnt = 0; var less = 0
            for (k in from until t) {
                val v = er[k]
                if (v.isFinite()) { cnt++; if (v < e) less++ }
            }
            if (cnt < MIN_SAMPLES) continue
            out[t] = less * 100.0 / cnt
        }
        return out
    }

    /** 一步取两序列：(erPct, er)，供 SsaT0Engine.Params.erPctArr/erArr 注入 */
    fun erqArrays(closes: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val er = erSeries(closes)
        return rollingPct(er) to er
    }
}
