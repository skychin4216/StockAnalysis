package com.chin.stockanalysis.strategy.ssa

import kotlin.math.abs

/**
 * ## BBI12 拐点 + 移动止盈 8% 双模式引擎（2026-10-20 回测定型）
 *
 * 与 PC 回测（smalltools，171只池 1H 全历史，费 0.13%/边）同口径：
 * - **买入**：BBI12（SMA(BBI,12)，BBI=(MA3+MA6+MA12+MA24)/4）谷底拐点确认
 *   （i 根拐点须 i+1 根收盘确认，信号根=确认根，无未来函数）
 * - **卖出（先到先卖）**：BBI12 峰顶拐点确认 或 移动止盈——从持仓最高价回撤 ≥ [TRAIL_PCT]%
 * - **模式**：
 *   - [Mode.AGGRESSIVE] 进取：拐点直接买 → 600176 +235.65%/回撤-18.31%；池均-3.02%
 *   - [Mode.STEADY] 稳健：Δ5ER（ER 与前 5 根之差，W=60）> [D5ER_MIN] 才买，
 *     过滤「ER 急降中的下跌中继拐点」→ 600176 +172.72%/回撤-14.53%；池均-0.18%
 * - **Δ5ER 金叉预警**：空仓且最近 3 根内 Δ5ER 由 ≤0 转 >0 → advice 提示
 *   「金叉预警」（领先谷底拐点平均约 6 根，仅作盯盘提醒，不直接买入）
 *
 * 废弃旧口径（ER≥70 横截面闸门 + 跌破买入价 0% 止损，**无 Δ5ER**）：池级 -1.67%/回撤-6.86%
 * 看似稳健，但 600176 这类强趋势票 +214.55% 被打成 -7.96%，闸门误杀最大两笔
 * 盈利（买入时 ER 分位仅 28/36），0% 止损被小时级噪音反复触发。
 *
 * 2026-10-20 四变体全池回测（171只）后**重新启用**为「冠军组合」可选口径：
 * ER70闸 + Δ5ER + 固定成本止损（无移动止盈）= 池级 **-0.88%/-5.90%**，全场最优；
 * 移动止盈 8% 版 -0.73%/-10.32%（回撤劣于固定止损）。通过 [run] 的
 * gateEr/erPctArr/fixedStop 参数启用（默认全关，保持上方既有口径不变）。
 *
 * 完整策略文档：docs/bbi12_trailing_strategy.md
 */
object SsaBbiErEngine {

    /** 策略模式：稳健=加 Δ5ER 过滤；进取=不过滤 */
    enum class Mode { STEADY, AGGRESSIVE }

    /** 移动止盈：从持仓最高价回撤此百分比即卖 */
    const val TRAIL_PCT = 8.0

    /** Δ5ER 过滤下限（仅 STEADY）：Δ5ER ≤ 此值判定「急降」，跳过该拐点 */
    const val D5ER_MIN = -0.05

    /** ER 窗口（根，Kaufman 效率比） */
    const val ER_W = 60

    /** ΔER 步长（根） */
    const val D5_LAG = 5

    /** Δ5ER 金叉预警回看（根）：空仓时最近 N 根内出现金叉 → 提示 */
    const val GC_ALERT_BARS = 3

    enum class Kind { BUY, SELL_PEAK, SELL_TRAIL, SELL_STOP, SKIP }

    /** 单条信号（触发价 = 信号根收盘） */
    data class Sig(
        val index: Int,
        val date: String,
        val price: Double,
        val kind: Kind,
        val reason: String
    )

    /** 引擎输出：当前状态 + 全部信号 */
    data class State(
        val holding: Boolean,
        val buyPrice: Double,       // 最近一次买入价（未持仓=NaN）
        val buyDate: String,
        val peakPrice: Double,      // 持仓期间最高价（未持仓=NaN）
        val lastPrice: Double,
        val lastDate: String,
        val pnlPct: Double,         // 现价相对买入价 %（未持仓=NaN）
        val fromPeakPct: Double,    // 现价距持仓最高价 %（未持仓=NaN）
        val trailLine: Double,      // 止盈线 = peak×(1-8%)（未持仓=NaN）
        val d5er: Double,           // 末根 Δ5ER（NaN=数据不足）
        val gcAlert: Boolean,       // 空仓且最近 GC_ALERT_BARS 根内有 Δ5ER 金叉
        val advice: String,         // 持仓/止盈卖出/峰顶卖出/过滤观望/金叉预警/空仓观望
        val reason: String,
        val signals: List<Sig>
    )

    /** ER 序列（W=[ER_W]，前 ER_W 根 NaN；与 PC er_series 同口径） */
    private fun erSeries(c: DoubleArray): DoubleArray {
        val n = c.size
        val out = DoubleArray(n) { Double.NaN }
        for (t in ER_W until n) {
            var vol = 0.0
            var ok = true
            for (k in (t - ER_W + 1)..t) {
                val d = abs(c[k] - c[k - 1])
                if (!d.isFinite()) { ok = false; break }
                vol += d
            }
            if (ok) out[t] = if (vol <= 0.0) 0.0 else abs(c[t] - c[t - ER_W]) / vol
        }
        return out
    }

    /**
     * @param closes 升序收盘价（1H 或日线均可，建议 ≥120 根）
     * @param dates  与 closes 等长的日期标签（展示用）
     * @param mode   [Mode.STEADY] 稳健 / [Mode.AGGRESSIVE] 进取
     * @param gateEr 冠军组合：ER 横截面分位闸（0=关闭，建议 70）。开启时须注入 [erPctArr]；
     *               未注入则闸自动失效（安全降级，单票自洽优先）
     * @param erPctArr 各根 bar 的全池横截面 ER 分位（下标对齐 closes），由调用方预计算注入
     * @param fixedStop 冠军组合：固定成本止损——现价跌破买入价即离场（优先级：峰顶 > 固定止损 > 移动止盈）；
     *                  false=既有移动止盈 8% 口径
     */
    fun run(closes: DoubleArray, dates: List<String>, mode: Mode,
            gateEr: Double = 0.0, erPctArr: DoubleArray? = null, fixedStop: Boolean = false): State {
        val n = closes.size
        require(n >= ER_W + D5_LAG + 10) { "K线不足 ${ER_W + D5_LAG + 10} 根" }
        val line = SsaErLocal.bbi12Line(closes.toList())
        val pivots = SsaErLocal.bbi12Pivots(line)   // (确认根下标, 是否谷底)
        val er = erSeries(closes)
        val d5 = DoubleArray(n) { i ->
            if (i >= D5_LAG && er[i].isFinite() && er[i - D5_LAG].isFinite())
                er[i] - er[i - D5_LAG] else Double.NaN
        }

        val sigs = ArrayList<Sig>()
        var holding = false
        var buyPrice = Double.NaN
        var buyDate = ""
        var peakPrice = Double.NaN
        var pi = 0
        for (t in 1 until n) {
            if (holding && closes[t] > peakPrice) peakPrice = closes[t]
            while (pi < pivots.size && pivots[pi].first < t) pi++
            // 卖出判定（先到先卖：峰顶 > 固定止损 > 移动止盈）
            if (holding) {
                val isPeak = pi < pivots.size && pivots[pi].first == t && !pivots[pi].second
                val trailHit = !fixedStop && peakPrice.isFinite() && peakPrice > 0 &&
                        (peakPrice - closes[t]) / peakPrice * 100 >= TRAIL_PCT
                val fixedHit = fixedStop && buyPrice.isFinite() && buyPrice > 0 && closes[t] < buyPrice
                when {
                    isPeak -> {
                        sigs.add(Sig(t, dates.getOrElse(t) { "" }, closes[t], Kind.SELL_PEAK,
                            "BBI12 峰顶拐点确认"))
                        holding = false
                    }
                    fixedHit -> {
                        sigs.add(Sig(t, dates.getOrElse(t) { "" }, closes[t], Kind.SELL_STOP,
                            "固定止损：现价跌破买入价 ${fmt(buyPrice)}"))
                        holding = false
                    }
                    trailHit -> {
                        sigs.add(Sig(t, dates.getOrElse(t) { "" }, closes[t], Kind.SELL_TRAIL,
                            "移动止盈：现价较高点 ${"%.2f".format(peakPrice)} 回撤 ≥${TRAIL_PCT.toInt()}%"))
                        holding = false
                    }
                }
            }
            // 买入判定（谷底拐点确认根）
            while (pi < pivots.size && pivots[pi].first == t) {
                val isBottom = pivots[pi].second
                if (isBottom && !holding) {
                    val e = d5[t]
                    val erPct = if (erPctArr != null && t < erPctArr.size) erPctArr[t] else Double.NaN
                    val gateOn = gateEr > 0.0 && erPct.isFinite()
                    val gatePass = gateEr <= 0.0 || (erPct.isFinite() && erPct >= gateEr)
                    val pass = (mode == Mode.AGGRESSIVE || (e.isFinite() && e > D5ER_MIN)) && gatePass
                    if (pass) {
                        holding = true; buyPrice = closes[t]; buyDate = dates.getOrElse(t) { "" }
                        peakPrice = closes[t]
                        sigs.add(Sig(t, buyDate, closes[t], Kind.BUY,
                            "BBI12 谷底拐点确认" +
                                (if (mode == Mode.STEADY) "（Δ5ER ${fmt(e)} > $D5ER_MIN）" else "") +
                                (if (gateOn) "（ER分位 ${fmt(erPct)} ≥ $gateEr）" else "")))
                    } else {
                        sigs.add(Sig(t, dates.getOrElse(t) { "" }, closes[t], Kind.SKIP,
                            when {
                                gateEr > 0.0 && erPct.isFinite() && erPct < gateEr ->
                                    "谷底拐点被过滤：ER分位 ${fmt(erPct)} < $gateEr（非趋势段）"
                                else -> "谷底拐点被过滤：Δ5ER ${fmt(e)} ≤ $D5ER_MIN（急降中继）"
                            }))
                    }
                }
                pi++
            }
        }

        val lastPrice = closes[n - 1]
        val lastDate = dates.getOrElse(n - 1) { "" }
        val pnl = if (holding && buyPrice.isFinite() && buyPrice > 0)
            (lastPrice / buyPrice - 1) * 100 else Double.NaN
        val fromPeak = if (holding && peakPrice.isFinite() && peakPrice > 0)
            (peakPrice - lastPrice) / peakPrice * 100 else Double.NaN
        val trailLine = if (holding && peakPrice.isFinite())
            peakPrice * (1 - TRAIL_PCT / 100) else Double.NaN
        val d5Last = d5[n - 1]

        // 空仓时 Δ5ER 金叉预警（最近 GC_ALERT_BARS 根内由 ≤0 转 >0）
        var gc = false
        if (!holding) {
            var k = n - 1
            while (k >= 1 && k >= n - GC_ALERT_BARS) {
                if (d5[k].isFinite() && d5[k - 1].isFinite() && d5[k - 1] <= 0 && d5[k] > 0) {
                    gc = true; break
                }
                k--
            }
        }

        val lastSig = sigs.lastOrNull()
        val recentTrail = lastSig?.kind == Kind.SELL_TRAIL && lastSig.index >= n - 4
        val recentPeak = lastSig?.kind == Kind.SELL_PEAK && lastSig.index >= n - 4
        val recentStop = lastSig?.kind == Kind.SELL_STOP && lastSig.index >= n - 4
        val recentSkip = lastSig?.kind == Kind.SKIP && lastSig.index >= n - 4
        val advice: String
        val reason: String
        when {
            holding -> {
                advice = "持仓"
                reason = "买入价 ${fmt(buyPrice)}（$buyDate）· 浮盈 ${fmt(pnl)}% · " +
                    if (fixedStop) "止损线 ${fmt(buyPrice)}（固定成本止损）"
                    else "止盈线 ${fmt(trailLine)}（距现价 ${fmt(fromPeak)}%）"
            }
            recentTrail -> { advice = "止盈卖出"; reason = lastSig!!.reason }
            recentPeak -> { advice = "峰顶卖出"; reason = lastSig!!.reason }
            recentStop -> { advice = "止损卖出"; reason = lastSig!!.reason }
            recentSkip -> { advice = "过滤观望"; reason = lastSig!!.reason }
            gc -> {
                advice = "金叉预警"
                reason = "Δ5ER 金叉（当前 ${fmt(d5Last)}），历史上领先谷底拐点约 6 根，盯盘等拐点确认"
            }
            else -> {
                advice = "空仓观望"
                reason = "等 BBI12 谷底拐点（Δ5ER 当前 ${fmt(d5Last)}" +
                    (if (mode == Mode.STEADY) "，须 > $D5ER_MIN 才买" else "") + "）"
            }
        }
        return State(holding, buyPrice, buyDate, peakPrice, lastPrice, lastDate,
            pnl, fromPeak, trailLine, d5Last, gc, advice, reason, sigs)
    }

    private fun fmt(v: Double): String = if (v.isFinite()) "%.2f".format(v) else "—"
}
