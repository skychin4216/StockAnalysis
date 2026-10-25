package com.chin.stockanalysis.strategy.ssa

/**
 * ## 区间做T / 海龟突破 引擎（2026-10-22 新增海龟变体）
 *
 * 池级回测结论（171只 × 1023根1H，买入持有均值 -8.83%）：
 * - 区间反转 N14+ER0.45：-8.79% / -30.36% / 17.6笔
 * - 双周期反转 14/7：-7.28% / -25.13% / 27.3笔
 * - 海龟 S1 20/10：-2.74% / -26.71% / 21.1笔
 * - 海龟 S2 55/20：-3.04% / -22.71% / 8.5笔
 * → 海龟突破明显优于反转，故新增 S1/S2 变体。
 *
 * 执行口径：t 收盘出信号 → **t+1 开盘成交**（无未来函数）。
 * ⚠️ A 股 T+1（2026-10-22 修正）：卖出成交日必须晚于买入成交日，否则信号作废、顺延。
 */
object SsaRangeTEngine {

    /** Kaufman 效率系数窗口 */
    const val ER_W = 10
    /** 效率系数上限：反转模式过滤趋势段用 */
    const val ER_MAX = 0.45
    /** 现行默认唐奇安根数（兼容旧口径） */
    const val DON_N = 14

    enum class Mode { REVERSAL, BREAKOUT }
    enum class Kind { BUY, SELL }

    data class Variant(
        val key: String, val label: String,
        val nIn: Int, val nOut: Int,
        val erMax: Double?,       // null = 无 ER 过滤
        val mode: Mode,
        val confirm: Int = 1,     // 突破确认根数（>1 = 连续 confirm 根收盘站上前 nIn 高才买，过滤假突破）
        val erRise: Boolean = false, // 突破买入要求 ER10 正在上升（趋势能量跟随，2026-10-22 池级验证收益最优）
        val erFall: Boolean = false, // 反转买入要求 ER10 正在下降（趋势化消退）
        val pivotW: Int = 0,         // 反转谷底确认：触及下沿后等第一根止跌K才买，最多等 pivotW 根
        val greedyBuy: Double? = null, // 贪婪买点（2026-10-22 用户口径）：触下沿后不限时滚动跟踪最低收盘，反弹 greedyBuy（0=首根止跌K）确认才买；先冲回上沿则放弃。卖点贪婪经回测有害，不实现
        val srcHL: Boolean = false    // 双唐奇安（2026-10-23）：上轨用 K线最高价、下轨用最低价建通道（替代收盘价口径）
    ) {
        fun describeEntry(): String = when (mode) {
            Mode.REVERSAL -> "收盘 ≤ 前${nIn}根最低" + (if (srcHL) "价" else "收盘") +
                (if (erFall) "·ER降" else "") +
                (if (greedyBuy != null) "·贪底${if (greedyBuy > 0) "${"%.1f".format(greedyBuy * 100)}%" else "止跌"}" else "") +
                (if (pivotW > 0) "·等止跌" else "")
            Mode.BREAKOUT -> (if (confirm > 1) "连续${confirm}根收盘 > 前${nIn}根最高" else "收盘 > 前${nIn}根最高") +
                (if (srcHL) "价" else "收盘") + (if (erRise) "·ER升" else "")
        }
        fun describeExit(): String = when (mode) {
            Mode.REVERSAL -> "收盘 ≥ 前${nOut}根最高" + (if (srcHL) "价" else "收盘")
            Mode.BREAKOUT -> "收盘 < 前${nOut}根最低" + (if (srcHL) "价" else "收盘")
        }
    }

    /**
     * 池级回测后定型。
     * 2026-10-23 双唐奇安（上轨=最高价、下轨=最低价，171×1023根1H 同源对比）：
     * - 高低价20/10+2根+ER升：-3.77% / -15.86% / 4.5笔 → 收益与回撤均优于收盘价口径
     * - 高低价10/10+2根+ER升：-4.65% / -19.51% / 6.9笔 / 胜率36.2% / 盈利票54 → 胜率与盈利面最优
     * - 高低价20/5+2根+ER升：-3.92% / -13.45% / 4.9笔 → 回撤最低
     * - 对照（收盘价20/10+2根+ER升）：-5.92% / -22.25% / 11.8笔
     * → 高低价通道全面占优（收益+2.2pp、回撤改善6.4pp、无效交易降62%），故设为默认。
     * 2026-10-22 收盘价口径结论保留：
     * - 海龟S1+2根+ER升：-1.28% / -21.52% / 13.2笔（当时样本段）
     * - 反转ER降+谷底等3：-3.09% / -18.62% / 10.0笔 / 盈利票66
     */
    val VARIANTS = listOf(
        Variant("hl20_10_c2_er", "双轨高低20/10+ER升", 20, 10, null, Mode.BREAKOUT, confirm = 2, erRise = true, srcHL = true),
        Variant("hl10_10_c2_er", "双轨高低10/10+ER升", 10, 10, null, Mode.BREAKOUT, confirm = 2, erRise = true, srcHL = true),
        Variant("hl20_5_c2_er", "双轨高低20/5+ER升", 20, 5, null, Mode.BREAKOUT, confirm = 2, erRise = true, srcHL = true),
        Variant("t20_10_c2_er", "海龟S1+2根+ER升", 20, 10, null, Mode.BREAKOUT, confirm = 2, erRise = true),
        Variant("rev14_dyn", "反转ER降+贪底", 14, 14, ER_MAX, Mode.REVERSAL, erFall = true, greedyBuy = 0.008),
        Variant("t20_10_c2", "海龟S1+2根", 20, 10, null, Mode.BREAKOUT, confirm = 2),
        Variant("t55_20_c2", "海龟S2+2根", 55, 20, null, Mode.BREAKOUT, confirm = 2),
        Variant("rev14", "区间反转N14+ER", 14, 14, ER_MAX, Mode.REVERSAL),
        Variant("turtle20_10", "海龟S1 20/10", 20, 10, null, Mode.BREAKOUT),
        Variant("turtle55_20", "海龟S2 55/20", 55, 20, null, Mode.BREAKOUT)
    )
    fun variantByKey(key: String?): Variant = VARIANTS.firstOrNull { it.key == key } ?: VARIANTS[0]

    data class Sig(
        val index: Int,
        val date: String,
        val price: Double,
        val kind: Kind,
        val reason: String
    )

    data class State(
        val variant: Variant,
        val holding: Boolean,
        val buyPrice: Double,   // 最近一次买入价（未持仓=NaN）
        val buyDate: String,
        val lastPrice: Double,
        val lastDate: String,
        val pnlPct: Double,     // 现价相对买入价 %（未持仓=NaN）
        val lower: Double,      // 末根下沿
        val upper: Double,      // 末根上沿
        val er: Double,         // 末根 ER10（NaN=数据不足）
        val toLowerPct: Double, // 现价距下沿 %
        val toUpperPct: Double, // 现价距上沿 %
        val advice: String,     // 买入 / 卖出 / 持仓 / 观望
        val reason: String,
        val signals: List<Sig>
    )

    fun run(closes: DoubleArray, dates: List<String>): State = run(closes, dates, VARIANTS[0])

    /**
     * @param highs/lows K线最高价/最低价；[Variant.srcHL] 为 true 时用于建双唐奇安通道，
     *        缺省(null/长度不符)时自动回退收盘价口径，避免调用方未升级导致崩溃。
     */
    fun run(
        closes: DoubleArray, dates: List<String>, v: Variant,
        highs: DoubleArray? = null, lows: DoubleArray? = null
    ): State {
        val n = closes.size
        val nMax = kotlin.math.max(v.nIn, v.nOut)
        require(n >= nMax + ER_W + 5) { "K线不足 ${nMax + ER_W + 5} 根（当前 $n）" }
        val er = SsaErQuantile.erSeries(closes, ER_W)

        // 双唐奇安：上轨取最高价序列的最高，下轨取最低价序列的最低
        val useHL = v.srcHL && highs != null && lows != null &&
            highs.size == n && lows.size == n
        val hiSrc = if (useHL) highs!! else closes
        val loSrc = if (useHL) lows!! else closes

        // 通道数组（确认根数>1 时要用到历史根的通道值，统一预算）
        val hiInA = DoubleArray(n) { Double.NaN }
        val loInA = DoubleArray(n) { Double.NaN }
        val hiOutA = DoubleArray(n) { Double.NaN }
        val loOutA = DoubleArray(n) { Double.NaN }
        for (t in v.nIn until n) {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (k in (t - v.nIn) until t) {
                if (loSrc[k] < lo) lo = loSrc[k]
                if (hiSrc[k] > hi) hi = hiSrc[k]
            }
            loInA[t] = lo; hiInA[t] = hi
        }
        for (t in v.nOut until n) {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (k in (t - v.nOut) until t) {
                if (loSrc[k] < lo) lo = loSrc[k]
                if (hiSrc[k] > hi) hi = hiSrc[k]
            }
            loOutA[t] = lo; hiOutA[t] = hi
        }

        val sigs = ArrayList<Sig>()
        var holding = false
        var buyPrice = Double.NaN
        var buyDate = ""
        /** 买入成交日（信号根 t 的下一根开盘）——T+1 判定用 */
        var buyExecDay = ""
        /** 反转谷底确认：武装等待倒计时 */
        var arm = 0
        /** 贪婪买点：武装后滚动跟踪的最低收盘 */
        var armLo = Double.NaN

        for (t in nMax until n) {
            if (v.erMax != null && (!er[t].isFinite() || er[t] > v.erMax)) continue
            val loIn = loInA[t]; val hiIn = hiInA[t]
            val loOut = loOutA[t]; val hiOut = hiOutA[t]
            val c = closes[t]
            val d = dates.getOrElse(t) { "" }
            when (v.mode) {
                Mode.REVERSAL -> {
                    if (holding) {
                        if (c >= hiOut) {
                            val execDay = dates.getOrElse(t + 1) { "" }.trim().substringBefore(' ')
                            if (buyExecDay.isNotEmpty() && execDay.isNotEmpty() && execDay <= buyExecDay) continue
                            sigs.add(Sig(t, d, c, Kind.SELL,
                                "收盘 ${fmt(c)} ≥ 前${v.nOut}根最高 ${fmt(hiOut)}，ER10 ${fmt(er[t])} ≤ ${v.erMax} → 次日开盘卖出"))
                            holding = false; buyPrice = Double.NaN; buyDate = ""; buyExecDay = ""
                        }
                    } else {
                        // 动态 ER：erFall 要求信号根 ER10 正在下降（趋势化消退，2026-10-22 池级验证有效）
                        val erDynOk = !v.erFall ||
                            (er[t].isFinite() && er[t - 1].isFinite() && er[t] < er[t - 1])
                        if (v.greedyBuy != null) {
                            // 贪婪买点：触及下沿（且 ER 下降）后不限时滚动跟踪最低收盘，
                            // 反弹确认（首根止跌K / 反弹超阈值）才买；价格先冲回上沿则放弃
                            if (c <= loIn && erDynOk) {
                                arm = 1; armLo = c
                            } else if (arm > 0) {
                                if (c < armLo) armLo = c
                                val okB = if (v.greedyBuy == 0.0) c > closes[t - 1]
                                    else c >= armLo * (1 + v.greedyBuy) && c > closes[t - 1]
                                if (okB) {
                                    sigs.add(Sig(t, d, c, Kind.BUY,
                                        "贪底确认：触及下沿后滚至最低 ${fmt(armLo)}，反弹${if (v.greedyBuy > 0) "${"%.1f".format(v.greedyBuy * 100)}%" else "首根止跌K"}确认（ER10 下降中）→ 次日开盘买入"))
                                    holding = true; buyPrice = c; buyDate = d
                                    buyExecDay = dates.getOrElse(t + 1) { "" }.trim().substringBefore(' ')
                                    arm = 0
                                } else if (c >= hiOut) {
                                    arm = 0  // 没等到确认就冲回上沿 → 放弃本次
                                }
                            }
                        } else if (v.pivotW > 0) {
                            // 谷底确认：触及下沿（且 ER 下降）后武装等待，首根"收盘>前收"止跌K才买
                            if (c <= loIn && erDynOk) {
                                arm = v.pivotW
                            } else if (arm > 0) {
                                if (c > closes[t - 1]) {
                                    sigs.add(Sig(t, d, c, Kind.BUY,
                                        "触及下沿 ${fmt(loIn)} 后首根止跌K（收盘 ${fmt(c)} > 前收 ${fmt(closes[t - 1])}）·ER10 下降中 → 次日开盘买入"))
                                    holding = true; buyPrice = c; buyDate = d
                                    buyExecDay = dates.getOrElse(t + 1) { "" }.trim().substringBefore(' ')
                                    arm = 0
                                } else arm--
                            }
                        } else if (c <= loIn && erDynOk) {
                            sigs.add(Sig(t, d, c, Kind.BUY,
                                "收盘 ${fmt(c)} ≤ 前${v.nIn}根最低 ${fmt(loIn)}，ER10 ${fmt(er[t])} ≤ ${v.erMax} → 次日开盘买入"))
                            holding = true; buyPrice = c; buyDate = d
                            buyExecDay = dates.getOrElse(t + 1) { "" }.trim().substringBefore(' ')
                        }
                    }
                }
                Mode.BREAKOUT -> {
                    if (holding) {
                        if (c < loOut) {
                            val execDay = dates.getOrElse(t + 1) { "" }.trim().substringBefore(' ')
                            if (buyExecDay.isNotEmpty() && execDay.isNotEmpty() && execDay <= buyExecDay) continue
                            sigs.add(Sig(t, d, c, Kind.SELL,
                                "收盘 ${fmt(c)} < 前${v.nOut}根最低 ${fmt(loOut)} → 次日开盘卖出（海龟止损/止盈）"))
                            holding = false; buyPrice = Double.NaN; buyDate = ""; buyExecDay = ""
                        }
                    } else {
                        // 突破确认：连续 confirm 根收盘站上通道（2根确认是池级回测最有效的假突破过滤）
                        var ok = true
                        for (k in 0 until v.confirm) {
                            val tt = t - k
                            if (tt < v.nIn || !(closes[tt] > hiInA[tt])) { ok = false; break }
                        }
                        // 动态 ER：erRise 要求信号根 ER10 正在上升（突破有趋势能量跟随，治"买在最高点"）
                        if (ok && v.erRise &&
                            !(er[t].isFinite() && er[t - 1].isFinite() && er[t] > er[t - 1])) ok = false
                        if (ok) {
                            val confTxt = if (v.confirm > 1) "连续${v.confirm}根收盘站上，" else ""
                            val erTxt = if (v.erRise) "ER10 上升中，" else ""
                            sigs.add(Sig(t, d, c, Kind.BUY,
                                "收盘 ${fmt(c)} > 前${v.nIn}根最高 ${fmt(hiIn)}（${confTxt}${erTxt}假突破过滤）→ 次日开盘买入"))
                            holding = true; buyPrice = c; buyDate = d
                            buyExecDay = dates.getOrElse(t + 1) { "" }.trim().substringBefore(' ')
                        }
                    }
                }
            }
        }

        val lastPrice = closes[n - 1]
        val lastDate = dates.getOrElse(n - 1) { "" }
        val erLast = er[n - 1]
        var lower = Double.NaN; var upper = Double.NaN
        var toLower = Double.NaN; var toUpper = Double.NaN
        if (n > nMax) {
            var loF = Double.MAX_VALUE; var hiF = -Double.MAX_VALUE
            for (k in (n - v.nIn) until n) { if (hiSrc[k] > hiF) hiF = hiSrc[k] }
            for (k in (n - v.nOut) until n) { if (loSrc[k] < loF) loF = loSrc[k] }
            lower = loF; upper = hiF
            if (lower > 0) toLower = (lastPrice - lower) / lower * 100
            if (upper > 0) toUpper = (upper - lastPrice) / upper * 100
        }
        val pnl = if (holding && buyPrice.isFinite() && buyPrice > 0)
            (lastPrice / buyPrice - 1) * 100 else Double.NaN

        val lastSig = sigs.lastOrNull()
        val pending = lastSig?.takeIf { it.index == n - 1 }
        val advice = when {
            pending?.kind == Kind.BUY -> "买入"
            pending?.kind == Kind.SELL -> "卖出"
            holding -> "持仓"
            else -> "观望"
        }
        val reason = when {
            pending != null -> pending.reason
            holding -> when (v.mode) {
                Mode.REVERSAL -> "买入价 ${fmt(buyPrice)}（$buyDate）· 浮盈 ${fmt(pnl)}% · " +
                    "下次卖出阈值 = 前${v.nOut}根最高 ${fmt(upper)}（现价距上沿 ${fmt(toUpper)}%）"
                Mode.BREAKOUT -> "持仓中 · 买入价 ${fmt(buyPrice)}（$buyDate）· 浮盈 ${fmt(pnl)}% · " +
                    "下次卖出/止损 = 前${v.nOut}根最低 ${fmt(lower)}（现价距下沿 ${fmt(toLower)}%）"
            }
            !erLast.isFinite() -> "ER10 数据不足，等待更多 K 线"
            v.mode == Mode.REVERSAL && erLast > ER_MAX -> "ER10 ${fmt(erLast)} > ${ER_MAX}：当前波动趋势化，区间策略观望（避免逆势）"
            else -> when (v.mode) {
                Mode.REVERSAL -> "空仓等低吸：下沿 ${fmt(lower)}（现价距下沿 ${fmt(toLower)}%）· " +
                    "ER10 ${fmt(erLast)} ≤ ${ER_MAX}，触及下沿即出买入信号"
                Mode.BREAKOUT -> "空仓等突破：上沿 ${fmt(upper)}（现价距上沿 ${fmt(toUpper)}%）· " +
                    "收盘突破即买入"
            }
        }

        return State(v, holding, buyPrice, buyDate, lastPrice, lastDate, pnl,
            lower, upper, erLast, toLower, toUpper, advice, reason, sigs)
    }

    private fun fmt(v: Double): String = if (v.isFinite()) "%.2f".format(v) else "—"
}
