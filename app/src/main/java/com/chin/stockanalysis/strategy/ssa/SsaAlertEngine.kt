package com.chin.stockanalysis.strategy.ssa

import com.chin.stockanalysis.strategy.indicatorbt.IndicatorBacktestEngine
import kotlin.math.abs

/**
 * ## 短线技术指标预警引擎（纯 Kotlin，零 Android 依赖）
 *
 * 面向「⚠ 预警」Tab：对末 N 根 60 分钟 K 线（默认 120 根 ≈ 30 交易日）做短线技术指标分析，
 * 逐根判定买入 / 卖出信号并给出触发价格与原因文案。**纯展示 / 辅助预警，不接入任何损益回测。**
 *
 * 指标口径：
 * - **JAX = 济安线**：A 线 = `JAX`，J 线 = `济安线`，默认 N=5
 * - **BBI 多空指标**：`(MA(C,3)+MA(C,6)+MA(C,12)+MA(C,24))/4`，其均线 = `MA(BBI, bbiMaN)`；
 *   本引擎的「买入窗」与斩仓判据沿用此线（**BBI ≠ BB 布林极限**，布林极限见 [SsaBbiPivotEngine.bbSeries]）
 * - **OBV / MAOBV**、**KDJ(9,3,3)**、**WR(14)** 国内口径（0~100，越大越超卖）
 *
 * 铁律：所有指标与判据只使用 `≤ i` 的历史数据，**无未来函数**；信号在 i 根收盘确认、次日开盘执行。
 */
object SsaAlertEngine {

    /** 预警用 K 线（含 volume，OBV 依赖） */
    data class Bar(
        val date: String,
        val time: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long
    )

    data class AlertParams(
        val jaxN: Int = 5,        // 济安线 N
        val obvMaN: Int = 10,     // MAOBV 的 SMA 周期
        val kdjN: Int = 9,
        val wrN: Int = 14,
        val bbiMaN: Int = 3,      // BBI 的均线周期
        val devPct: Double = 2.0, // S1b：跌破 A 线的阈值（%）
        /** BBI 买入窗硬闸门：窗口关闭时买入信号评分置 0 并灰显（信号仍列出） */
        val requireBbiWindow: Boolean = true,
        /** 收益率对比是否扣费（默认毛收益） */
        val includeCost: Boolean = false,
        val commissionRate: Double = 0.00025,  // 单边佣金（万 2.5）
        val stampDutyRate: Double = 0.0005     // 卖出印花税（千 0.5）
    )

    /** 预警信号（触发价 = 信号根收盘，次日开盘执行） */
    data class Signal(
        val index: Int,
        val date: String,
        val time: String,
        val price: Double,
        val buy: Boolean,
        val cond: String,           // "B1".."B5" / "S1a".."S5" / "X1"
        val reason: String,
        val resonance: Boolean,     // 同根同向 ≥2 条
        val strong: Boolean = false, // B4 的 <25「动能最强」标注
        /** 买入信号触发时 BBI 买入窗是否开启；卖出信号恒 true */
        val windowOpen: Boolean = true,
        /** 买入 = 该根 barScore（0/20/…/100）；卖出 = 0 */
        val score: Int = 0
    )

    /** 区间内「信号配对」模拟 vs 买入持有的收益率对比 */
    data class AlertPerf(
        val fromIndex: Int,
        val toIndex: Int,
        val fromLabel: String,
        val toLabel: String,
        /** 策略收益率（%，窗口内按信号建/平仓，末根强平） */
        val strategyReturnPct: Double,
        /** 买入持有收益率（%） */
        val buyHoldReturnPct: Double,
        /** 超额 = 策略 - 买持（%） */
        val excessPct: Double,
        val trades: Int,
        val winRatePct: Double,
        val netOfCost: Boolean,
        /** 平均单笔持仓期最大浮亏 MAE（%，收盘口径盯市、相对建仓价，≤0；0=未统计） */
        val maePct: Double = 0.0,
        /** 平均单笔持仓期最大回吐 MPD（%，相对**持仓期最高收盘**回落的幅度，≥0；0=未统计） */
        val mpdPct: Double = 0.0,
        /** 窗口内权益曲线最大回撤 MDD（%，逐根收盘盯市；0=未统计） */
        val mddPct: Double = 0.0
    )

    data class Result(
        val bars: List<Bar>,
        val jax: DoubleArray, val jix: DoubleArray,   // A 线 / J 线(济安线)
        val obv: DoubleArray, val maobv: DoubleArray,
        val k: DoubleArray, val d: DoubleArray, val j: DoubleArray,
        val wr: DoubleArray,
        val bbi: DoubleArray, val bbiMa: DoubleArray,
        val signals: List<Signal>,
        /** 逐根 BBI 买入窗（BB均线上行 且 BB 在其均线上方） */
        val buyWindowOpen: BooleanArray,
        /** 逐根买入评分 0/20/…/100（硬闸门生效时为 0） */
        val barScore: IntArray,
        /** 末根评分 = barScore[n-1] */
        val lastScore: Int,
        /** 区间内收益率对比 */
        val perf: AlertPerf,
        /** 收益率/展示窗口起点 = max(0, n-120) */
        val windowFrom: Int
    )

    // ───────────────────────────── 主入口 ─────────────────────────────

    fun analyze(bars: List<Bar>, p: AlertParams = AlertParams()): Result {
        require(bars.size >= 30) { "K 线不足 30 根，无法做短线预警分析" }
        val n = bars.size
        val closes = DoubleArray(n) { bars[it].close }

        // 转 IndicatorBacktestEngine.Bar 以复用 KDJ / WR（含 volume，保持同构）
        val ib = bars.map { IndicatorBacktestEngine.Bar(it.date, it.open, it.high, it.low, it.close, it.volume) }

        // ── JAX 济安线 ──
        val (jax, jix) = jaxSeries(bars, closes, p.jaxN)

        // ── BB = BBI ──
        val (bbi, bbiMa) = bbiSeries(closes, p.bbiMaN)

        // ── BBI 买入窗（前提闸门） ──
        val buyWindowOpen = buyWindowSeries(bbi, bbiMa)

        // ── OBV / MAOBV ──
        val obv = obvSeries(bars)
        val maobv = IndicatorBacktestEngine.emaSeries(IndicatorBacktestEngine.smaSeries(obv, p.obvMaN), 3)

        // ── KDJ / WR ──
        val (k, d, j) = IndicatorBacktestEngine.kdjSeries(ib, p.kdjN)
        val wr = IndicatorBacktestEngine.wrSeries(ib, p.wrN)

        val (signals, barScore) = scanSignals(bars, jax, jix, obv, maobv, k, d, wr, bbi, bbiMa, buyWindowOpen, p)

        val windowFrom = (n - 120).coerceAtLeast(0)
        val perf = computePerf(bars, signals, buyWindowOpen, windowFrom, n - 1, p)

        return Result(
            bars, jax, jix, obv, maobv, k, d, j, wr, bbi, bbiMa,
            signals, buyWindowOpen, barScore, barScore[n - 1], perf, windowFrom
        )
    }

    // ───────────────────────────── 指标实现 ─────────────────────────────

    private fun v(a: DoubleArray, i: Int) = i in a.indices && !a[i].isNaN()

    /**
     * NaN-safe SMA：跳过前导 NaN，从首个有效值起做 n 窗口均值，之前保持 NaN。
     * （[IndicatorBacktestEngine.smaSeries] 会把前导 NaN 累加进 sum 致整条序列 NaN，不可用于含暖机段的输入。）
     */
    internal fun sma(src: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        var first = -1
        for (i in src.indices) if (!src[i].isNaN()) { first = i; break }
        if (first < 0) return out
        var sum = 0.0
        for (i in first until src.size) {
            sum += src[i]
            if (i - first >= n) sum -= src[i - n]
            if (i - first >= n - 1) out[i] = sum / n
        }
        return out
    }

    /**
     * JAX = 济安线：
     * ```
     * VAR1 = (2C+H+L)/4 ; AA = |VAR1 - MA(C,N)| / MA(C,N)
     * JIX  = DMA(VAR1, AA)        // 济安线：以 AA 为权重的动态平滑（播种于首个 MA 有效点）
     * CC   = C / JIX ; MA1 = MA(CC*VAR1, 3)
     * MAAA = ((MA1 - JIX)/JIX)/3 ; JAX = MA1 - MAAA*MA1
     * ```
     * 防 NaN 污染：`AA`/`JIX` 为 0 或 NaN 时该点输出 NaN 且不参与递推；`[0, N+2)` 全 NaN。
     */
    private fun jaxSeries(bars: List<Bar>, closes: DoubleArray, N: Int): Pair<DoubleArray, DoubleArray> {
        val n = bars.size
        val var1 = DoubleArray(n) { i -> (2 * bars[i].close + bars[i].high + bars[i].low) / 4.0 }
        val maC = IndicatorBacktestEngine.smaSeries(closes, N)

        val jix = DoubleArray(n) { Double.NaN }
        var prev = Double.NaN
        for (i in 0 until n) {
            if (!v(maC, i) || maC[i] == 0.0) continue          // 暖机：MA 未有效
            val aa = abs(var1[i] - maC[i]) / maC[i]
            val w = aa.coerceIn(0.0, 1.0)                       // 防权重过冲致 DMA 振荡
            prev = if (prev.isNaN()) var1[i] else w * var1[i] + (1 - w) * prev
            jix[i] = prev
        }

        val ccVar1 = DoubleArray(n) { i ->
            if (v(jix, i) && jix[i] != 0.0) closes[i] / jix[i] * var1[i] else Double.NaN
        }
        val ma1 = sma(ccVar1, 3)
        val jax = DoubleArray(n) { i ->
            if (v(ma1, i) && v(jix, i) && jix[i] != 0.0) {
                val maaa = ((ma1[i] - jix[i]) / jix[i]) / 3.0
                ma1[i] - maaa * ma1[i]
            } else Double.NaN
        }
        return jax to jix
    }

    /** BB = BBI：(MA3+MA6+MA12+MA24)/4；其均线 = MA(BBI, bbiMaN) */
    internal fun bbiSeries(closes: DoubleArray, bbiMaN: Int): Pair<DoubleArray, DoubleArray> {
        val m3 = IndicatorBacktestEngine.smaSeries(closes, 3)
        val m6 = IndicatorBacktestEngine.smaSeries(closes, 6)
        val m12 = IndicatorBacktestEngine.smaSeries(closes, 12)
        val m24 = IndicatorBacktestEngine.smaSeries(closes, 24)
        val bbi = DoubleArray(closes.size) { i ->
            if (v(m3, i) && v(m6, i) && v(m12, i) && v(m24, i)) (m3[i] + m6[i] + m12[i] + m24[i]) / 4.0 else Double.NaN
        }
        val bbiMa = sma(bbi, bbiMaN)
        return bbi to bbiMa
    }

    /**
     * BBI 买入窗（买入前提）：
     * `BB 均线趋势向上 且 BB 在其均线上方` → 买入空间开启。`[0]` 恒 false（无前值）。
     */
    internal fun buyWindowSeries(bbi: DoubleArray, bbiMa: DoubleArray): BooleanArray {
        val n = bbi.size
        val out = BooleanArray(n)
        for (i in 1 until n) {
            out[i] = v(bbi, i) && v(bbiMa, i) && v(bbiMa, i - 1) &&
                bbiMa[i] > bbiMa[i - 1] && bbi[i] > bbiMa[i]
        }
        return out
    }

    /** OBV：窗口首根 = 0，收盘涨 +vol / 跌 -vol / 平 0 */
    private fun obvSeries(bars: List<Bar>): DoubleArray {
        val n = bars.size
        val out = DoubleArray(n)
        out[0] = 0.0
        for (i in 1 until n) {
            val d = bars[i].close - bars[i - 1].close
            out[i] = out[i - 1] + (if (d > 0) bars[i].volume.toDouble() else if (d < 0) -bars[i].volume.toDouble() else 0.0)
        }
        return out
    }

    // ───────────────────────────── 信号扫描 ─────────────────────────────

    private fun scanSignals(
        bars: List<Bar>, jax: DoubleArray, jix: DoubleArray,
        obv: DoubleArray, maobv: DoubleArray,
        k: DoubleArray, d: DoubleArray, wr: DoubleArray,
        bbi: DoubleArray, bbiMa: DoubleArray,
        buyWindowOpen: BooleanArray, p: AlertParams
    ): Pair<List<Signal>, IntArray> {
        val n = bars.size
        val raw = ArrayList<Signal>(32)

        fun add(i: Int, buy: Boolean, cond: String, reason: String, strong: Boolean = false) {
            raw.add(Signal(i, bars[i].date, bars[i].time, bars[i].close, buy, cond, reason, false, strong))
        }

        // S1b 事件型判定：A 线在 J 线上且价格跌破 A 线超阈值（仅首次触发）
        fun s1bActive(i: Int): Boolean {
            if (!v(jax, i) || !v(jix, i)) return false
            return jax[i] > jix[i] && bars[i].close < jax[i] * (1 - p.devPct / 100.0)
        }

        for (i in 1 until n) {
            // ── 买入 ──
            if (v(jax, i) && v(jix, i) && v(jax, i - 1) && v(jix, i - 1) &&
                jax[i - 1] <= jix[i - 1] && jax[i] > jix[i]
            ) add(i, true, "B1", "JAX: A线(%.3f)上穿J线(%.3f)".format(jax[i], jix[i]))

            if (v(obv, i) && v(maobv, i) && v(obv, i - 1) && v(maobv, i - 1) &&
                obv[i - 1] <= maobv[i - 1] && obv[i] > maobv[i]
            ) add(i, true, "B2", "OBV 上穿 MAOBV")

            if (v(k, i) && v(d, i) && v(k, i - 1) && v(d, i - 1) &&
                k[i - 1] <= d[i - 1] && k[i] > d[i]
            ) add(i, true, "B3", "KDJ 金叉(K=%.1f,D=%.1f)".format(k[i], d[i]))

            if (v(wr, i) && v(wr, i - 1) && wr[i - 1] > 65 && wr[i] <= 65) {
                val strong = wr[i] < 25
                add(i, true, "B4", "WR 自 %.1f 降至 %.1f(<65)".format(wr[i - 1], wr[i]) +
                    if (strong) " · 动能最强(<25)" else "", strong)
            }

            val b5 = v(bbi, i) && v(bbiMa, i) && v(bbi, i - 1) && v(bbiMa, i - 1) &&
                bbi[i - 1] <= bbiMa[i - 1] && bbi[i] > bbiMa[i]
            if (b5) add(i, true, "B5", "BB 上穿其均线")

            // ── 卖出 ──
            if (v(jax, i) && v(jix, i) && v(jax, i - 1) && v(jix, i - 1) &&
                jax[i - 1] >= jix[i - 1] && jax[i] < jix[i]
            ) add(i, false, "S1a", "JAX: A线跌落J线")

            if (s1bActive(i) && !s1bActive(i - 1)) {
                val dev = (jax[i] - bars[i].close) / jax[i] * 100.0
                add(i, false, "S1b", "价格低于A线 %.2f%%(超阈值 %.1f%%)卖出".format(dev, p.devPct))
            }

            if (v(obv, i) && v(maobv, i) && v(obv, i - 1) && v(maobv, i - 1) &&
                obv[i - 1] >= maobv[i - 1] && obv[i] < maobv[i] && maobv[i] < maobv[i - 1]
            ) add(i, false, "S2", "OBV 下穿 MAOBV 且 MAOBV 向下")

            if (v(k, i) && v(d, i) && v(k, i - 1) && v(d, i - 1) &&
                k[i - 1] >= d[i - 1] && k[i] < d[i]
            ) add(i, false, "S3", "KDJ 死叉(K=%.1f,D=%.1f)".format(k[i], d[i]))

            if (v(wr, i) && v(wr, i - 1) && wr[i - 1] < 25 && wr[i] >= 25)
                add(i, false, "S4", "WR 自 %.1f 升至 %.1f(≥25)".format(wr[i - 1], wr[i]))

            if (v(bbi, i) && v(bbiMa, i) && v(bbi, i - 1) && v(bbiMa, i - 1) &&
                bbi[i - 1] >= bbiMa[i - 1] && bbi[i] < bbiMa[i]
            ) add(i, false, "S5", "BB 下穿其均线")

            // ── 特殊情况 X1：BB 多头结构 + A 线在 J 线下，逢低买 ──
            if (i >= 1 && v(bbi, i) && v(bbiMa, i) && v(bbiMa, i - 1) && v(jax, i) && v(jix, i) &&
                bbiMa[i] > bbiMa[i - 1] && bbi[i] > bbiMa[i] && jax[i] < jix[i]
            ) {
                if (bars[i].close <= jax[i])
                    add(i, true, "X1", "BB多头+价格触及A线支撑逢低买")
                else if (b5)
                    add(i, true, "X1", "BB多头+BB上穿均线逢低买")
            }
        }

        // ── 逐根买入评分：B1..B5 各 +20（X1 不计分，避免与 B5 的"BB上穿"分支重复计数）──
        val scoreCnt = IntArray(n)
        raw.forEach { s -> if (s.buy && s.cond != "X1") scoreCnt[s.index] = scoreCnt[s.index] + 1 }
        val barScore = IntArray(n) { i ->
            if (p.requireBbiWindow && !buyWindowOpen[i]) 0 else 20 * scoreCnt[i]
        }

        // ── 共振高亮：同根同向 ≥2 条 ──
        val buyCnt = HashMap<Int, Int>()
        val sellCnt = HashMap<Int, Int>()
        raw.forEach { s -> if (s.buy) buyCnt[s.index] = (buyCnt[s.index] ?: 0) + 1 else sellCnt[s.index] = (sellCnt[s.index] ?: 0) + 1 }
        val filled = raw.map { s ->
            val res = if (s.buy) (buyCnt[s.index] ?: 0) >= 2 else (sellCnt[s.index] ?: 0) >= 2
            if (s.buy) s.copy(resonance = res, windowOpen = buyWindowOpen[s.index], score = barScore[s.index])
            else s.copy(resonance = res)
        }.sortedBy { it.index }
        return filled to barScore
    }

    /**
     * 区间内「信号配对」模拟 vs 买入持有（单边做多，最多一仓；全部用收盘价）：
     * 空仓遇「买入窗开启」的买入信号建仓 → 持仓遇卖出信号平仓 → 末根强平。
     */
    internal fun computePerf(
        bars: List<Bar>, signals: List<Signal>, buyWindowOpen: BooleanArray,
        from: Int, to: Int, p: AlertParams
    ): AlertPerf {
        val buyAt = HashSet<Int>()
        val sellAt = HashSet<Int>()
        signals.forEach { s ->
            if (s.index in from..to) { if (s.buy) buyAt.add(s.index) else sellAt.add(s.index) }
        }
        val feeMul = (1.0 - p.commissionRate) * (1.0 - p.commissionRate - p.stampDutyRate)

        var holding = false
        var entry = 0.0
        var cum = 1.0
        var trades = 0
        var wins = 0
        fun closeTrade(exitPrice: Double) {
            val mult = (exitPrice / entry) * (if (p.includeCost) feeMul else 1.0)
            cum *= mult
            if (mult > 1.0) wins++
            trades++
        }
        for (i in from..to) {
            if (!holding && buyAt.contains(i) && buyWindowOpen[i]) {
                entry = bars[i].close; holding = true
            } else if (holding && sellAt.contains(i)) {
                closeTrade(bars[i].close); holding = false
            }
        }
        if (holding) closeTrade(bars[to].close)

        val strategy = (cum - 1.0) * 100.0
        val bh = if (bars[from].close > 0.0) (bars[to].close / bars[from].close - 1.0) * 100.0 else 0.0
        return AlertPerf(
            fromIndex = from, toIndex = to,
            fromLabel = "${bars[from].date} ${bars[from].time}",
            toLabel = "${bars[to].date} ${bars[to].time}",
            strategyReturnPct = strategy,
            buyHoldReturnPct = bh,
            excessPct = strategy - bh,
            trades = trades,
            winRatePct = if (trades > 0) wins * 100.0 / trades else 0.0,
            netOfCost = p.includeCost
        )
    }
}
