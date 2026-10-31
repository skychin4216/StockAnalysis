package com.chin.stockanalysis.strategy.rvg

import kotlin.math.*

/**
 * ## RVG（Regime-Adaptive Volatility Targeting，波动率状态自适应）
 *
 * 一句话：σ 决定仓位大小，趋势决定能不能开仓、做多还是做空，两者解耦。
 *
 * 执行顺序固定（不可调换）：
 * 1. L1 结构层：PELT 断点检测 → 当前阶段起点（每次新断点后冷却若干日禁止开新仓）
 * 2. L2 波动层：GJR-GARCH(1,1,1) + t 分布，只用「当前阶段」样本估计 → σ_f / σ_LR / R
 * 3. L3 仓位层：w* = clip(σ_target/σ_f, 0, L_max) · 1[d≠0] · s(R)
 * 4. 执行层：10% 调仓死区、T+1 开盘执行、万3 佣金 + 5bp 滑点
 *
 * 额外风控闸门：断点冷却期禁开新仓 / R>2.2 仓位×0.3 / 净值回撤>20% 停手 20 日 /
 * 重大事件次日仓位上限 ≤0.5 倍 / 单笔 ATR 止损（入场价 − 2.5×ATR14）。
 *
 * 设计要点（为什么先切段后估参数）：
 * 若先估波动率再切段，暴跌极端值会污染参数，导致后续仓位长期偏小；
 * 若先定仓位再切段，断点当天还拿着旧仓位，风险很高。
 *
 * 纯算法、无 Android 依赖。
 */
object RvgEngine {

    data class Params(
        /** 年化目标波动（0.10 = 10%） */
        val sigmaTarget: Double = 0.10,
        /** 杠杆上限 L_max */
        val maxLeverage: Double = 1.0,
        /** PELT 滚动窗口 */
        val peltWindow: Int = 500,
        /** pen = penK · ln(n) */
        val penK: Double = 3.0,
        /** PELT 最小段长 */
        val peltMinSize: Int = 20,
        /** 每 N 日重新检测断点 / 重估 GARCH */
        val recheckEvery: Int = 20,
        /** 新断点后冷却日数（禁止开新仓） */
        val cooldownDays: Int = 5,
        /** 波动预测步数（取平均） */
        val forecastSteps: Int = 20,
        /** 调仓死区：|新仓-旧仓| < deadband 不调 */
        val deadband: Double = 0.10,
        val commission: Double = 0.0003,
        val slippage: Double = 0.0005,
        /** 净值回撤熔断阈值 */
        val ddStop: Double = 0.20,
        /** 熔断停手天数 */
        val ddStopDays: Int = 20,
        val atrN: Int = 14,
        val atrK: Double = 2.5,
        val useAtrStop: Boolean = true,
        /** 是否允许做空（d=-1 时建负仓） */
        val allowShort: Boolean = true,
        /** 重大事件次日仓位上限（相对 L_max 的倍数） */
        val eventCap: Double = 0.5,
        /** 重大事件日期集合（yyyy-MM-dd），空集合=不启用 */
        val eventDates: Set<String> = emptySet()
    )

    data class Garch(
        val alpha: Double, val beta: Double, val gamma: Double,
        val omega: Double, val nu: Double,
        /** ξ = α + β + γ/2 持续性 */
        val xi: Double,
        /** 余震半衰期（天） */
        val halfLife: Double,
        val qlike: Double, val coverage: Double,
        val n: Int, val fitted: Boolean
    )

    data class Step(
        val idx: Int,
        val date: String,
        val close: Double,
        val phaseStartDate: String,
        val phaseBars: Int,
        val newBreak: Boolean,
        val cooling: Boolean,
        /** 年化预测波动 */
        val sigmaF: Double,
        /** 阶段长期波动（年化） */
        val sigmaLR: Double,
        /** 波动比 R = σ_f / σ_LR */
        val rRatio: Double,
        /** 波动闸门系数 s(R) */
        val scale: Double,
        /** 方向 d ∈ {+1,0,-1} */
        val dir: Int,
        /** 死区前的目标仓位 */
        val wTarget: Double,
        /** 实际持有仓位（含符号） */
        val w: Double,
        val equity: Double,
        val dd: Double,
        val halted: Boolean,
        val exitNote: String?
    )

    data class Metrics(
        val annRet: Double,
        val annVol: Double,
        val mdd: Double,
        val turnover: Double,
        val avgAbsPos: Double,
        val investedPct: Double,
        val bars: Int,
        val trades: Int,
        val hitDays: Int
    )

    data class Result(
        val dates: List<String>,
        val closes: List<Double>,
        val steps: List<Step>,
        /** 断点位置（对应 dates 下标） */
        val breakIdx: List<Int>,
        /** 最近一次 GARCH 拟合结果 */
        val garch: Garch?,
        val metrics: Metrics,
        val lastSignal: Step?,
        /** 下一交易日的建议与预测（最后一根收盘后按同一套规则外推） */
        val nextDay: NextDay?,
        val warmup: Int
    )

    /**
     * 下一交易日建议：用最后一根收盘后的 GARCH 一步预测 σ 与同一套三层规则外推，
     * 不做执行模拟（次日开盘价未知），仅给出「方向 / 目标仓位 / 是否触达死区 / 建议动作」。
     */
    data class NextDay(
        val lastDate: String,
        val close: Double,
        /** 次日预测日波动（log 收益标准差） */
        val sigmaDaily: Double,
        /** 次日预测年化波动 σ_f(h=1) */
        val sigmaAnn: Double,
        /** 阶段长期波动（年化） */
        val sigmaLR: Double,
        val rRatio: Double,
        val scale: Double,
        val dir: Int,
        /** 死区前的目标仓位 */
        val wTarget: Double,
        /** 最后一根收盘后的实际持有仓位 */
        val wCur: Double,
        /** 死区后的建议仓位 */
        val wNext: Double,
        /** 建议变动 = wNext − wCur */
        val delta: Double,
        val action: String,
        val cooling: Boolean,
        val halted: Boolean,
        val forcedFlat: Boolean,
        /** 收盘价 ±1σ / ±2σ 价格带（正态近似） */
        val up1: Double, val dn1: Double,
        val up2: Double, val dn2: Double,
        /** 当前持仓的 ATR 止损价（无持仓/未启用为 null） */
        val stopPrice: Double?
    )

    // ═════════════════════════ 主流程 ═════════════════════════

    /**
     * 状态切换标签（确定性第 1 步，免 GARCH 拟合、可直接外供其它模块）：
     * |log 收益| 的短窗波动 / 长窗波动 > thr → 动荡态（true）。
     * 与主回测 σ_f/σ_LR 同源（meanSquare 滚动比），相空间等模块可用它做状态条件化过滤。
     * 前缀不足长窗时一律 false（平静）。
     */
    fun regimeLabels(closes: DoubleArray, shortN: Int = 20, longN: Int = 120,
                     thr: Double = 1.5): BooleanArray {
        val n = closes.size
        val out = BooleanArray(n)
        if (n < longN + 2) return out
        val r = DoubleArray(n) { i -> if (i == 0) 0.0 else ln(closes[i] / closes[i - 1]) }
        var sShort = 0.0
        var sLong = 0.0
        val thr2 = thr * thr
        for (i in 1 until n) {
            val v = r[i] * r[i]
            sShort += v; sLong += v
            if (i > shortN) sShort -= r[i - shortN] * r[i - shortN]
            if (i > longN) sLong -= r[i - longN] * r[i - longN]
            if (i >= longN) {
                val vl = sLong / longN
                out[i] = vl > 1e-12 && (sShort / shortN) / vl > thr2
            }
        }
        return out
    }

    fun analyze(
        dates: List<String>,
        closes: DoubleArray,
        opens: DoubleArray? = null,
        highs: DoubleArray? = null,
        lows: DoubleArray? = null,
        p: Params = Params()
    ): Result? {
        val n = closes.size
        if (n < 150 || dates.size != n) return null

        // 收益序列：r[i] 对应第 i 根（i>=1）的日对数收益，r[0]=0
        val r = DoubleArray(n) { if (it == 0) 0.0 else ln(closes[it].coerceAtLeast(1e-9) / closes[it - 1].coerceAtLeast(1e-9)) }
        val op = opens ?: closes
        val hi = highs ?: closes
        val lo = lows ?: closes

        val ma20 = movingAverage(closes, 20)
        val ma60 = movingAverage(closes, 60)
        val atr = atrSeries(hi, lo, closes, p.atrN)

        val warmup = maxOf(80, p.peltMinSize + 20)
        if (n - 2 <= warmup) return null

        val steps = ArrayList<Step>(n - warmup)
        val breakIdx = ArrayList<Int>()

        var phaseStart = warmup
        var lastDetect = -1
        var coolUntil = -1
        var newBreak = false
        var garch: Garch? = null
        var lastFit = -1
        var vCur = r[n - 1] * r[n - 1]
        var params: FitRes? = null

        var eq = 1.0
        var peak = 1.0
        var wHeld = 0.0
        var entryPrice = 0.0
        var entryAtr = 0.0
        var haltUntil = -1
        var forceFlatNext = false
        var trades = 0
        var turnoverAbs = 0.0
        var posAbsSum = 0.0
        var investedDays = 0
        val eqCurve = ArrayList<Double>(n)

        for (t in warmup until n - 1) {
            // ── L1 结构层：PELT 断点检测（每 recheckEvery 日一次） ──
            newBreak = false
            if (t - lastDetect >= p.recheckEvery) {
                lastDetect = t
                val w0 = (t - p.peltWindow + 1).coerceAtLeast(1)
                val seg = DoubleArray(t - w0 + 1) { i -> r[w0 + i] }
                val cps = pelt(zScore(seg), p.penK * ln(seg.size.toDouble()), p.peltMinSize)
                val last = cps.lastOrNull()
                if (last != null) {
                    val cand = w0 + last
                    if (cand > phaseStart) {
                        phaseStart = cand
                        breakIdx.add(cand)
                        newBreak = true
                        coolUntil = t + p.cooldownDays
                    }
                }
            }
            val cooling = t < coolUntil

            // ── L2 波动层：GJR-GARCH(1,1,1)+t（仅用当前阶段样本，每 recheckEvery 日重估） ──
            val from = phaseStart.coerceAtLeast(1)
            if (t - lastFit >= p.recheckEvery || params == null) {
                lastFit = t
                params = fitGjr(DoubleArray(t - from + 1) { i -> r[from + i] })
                // 重算阶段内滤波序列，取末端方差
                vCur = filterVariance(r, from, t, params)
                garch = toGarch(params, r, from, t)
            } else {
                vCur = nextVariance(vCur, r[t], params!!)
            }
            if (vCur < 1e-12) vCur = 1e-12

            val sgF = forecastSigma(vCur, params!!, p.forecastSteps)
            val sgLR = sqrt(meanSquare(r, from, t)) * sqrt(252.0)
            val ratio = (sgF / sgLR.coerceAtLeast(1e-9))
            val scale = when {
                ratio > 2.2 -> 0.3
                ratio > 1.5 -> 0.6
                else -> 1.0
            }

            // ── L3 方向层：MA20 + MA60 斜率，2 日确认 ──
            val dir = direction(ma20, ma60, t)

            // ── L3 仓位层 ──
            var wT = if (dir == 0) 0.0 else {
                val base = (p.sigmaTarget / sgF.coerceAtLeast(1e-6)).coerceIn(0.0, p.maxLeverage)
                val signed = base * scale * if (dir > 0) 1.0 else -1.0
                if (!p.allowShort && signed < 0) 0.0 else signed
            }
            // 断点冷却期：禁止开新仓（已有仓位维持，不加减）
            if (cooling && wT != 0.0 && wHeld == 0.0) wT = 0.0
            // 重大事件次日仓位上限
            val nextDate = dates[t + 1]
            if (p.eventDates.isNotEmpty() && nextDate in p.eventDates) {
                val cap = p.eventCap * p.maxLeverage
                wT = wT.coerceIn(-cap, cap)
            }
            // 回撤熔断
            if (t < haltUntil) wT = 0.0
            // ATR 止损后强制空仓一日
            if (forceFlatNext) { wT = 0.0; forceFlatNext = false }

            // ── 执行层：10% 死区 + T+1 开盘 ──
            var wNew = wT
            if (abs(wNew - wHeld) < p.deadband) wNew = wHeld
            val costRate = abs(wNew - wHeld) * (p.commission + p.slippage)
            turnoverAbs += abs(wNew - wHeld)
            if (abs(wNew) > 1e-9 && abs(wHeld) < 1e-9) {
                trades++
                entryPrice = op[t + 1]
                entryAtr = atr[t].coerceAtLeast(1e-9)
            }

            // 旧仓 close[t] → open[t+1]，新仓 open[t+1] → close[t+1]
            val gap = op[t + 1].coerceAtLeast(1e-9) / closes[t].coerceAtLeast(1e-9) - 1.0
            val intraday = closes[t + 1].coerceAtLeast(1e-9) / op[t + 1].coerceAtLeast(1e-9) - 1.0
            eq *= (1.0 + wHeld * gap)
            eq *= (1.0 + wNew * intraday)
            eq *= (1.0 - costRate)
            if (eq < 1e-9) eq = 1e-9
            wHeld = wNew
            peak = max(peak, eq)
            val dd = 1.0 - eq / peak

            // 熔断触发
            var exitNote: String? = null
            if (dd > p.ddStop && t >= haltUntil) {
                haltUntil = t + p.ddStopDays
                wHeld = 0.0
                peak = eq
                exitNote = "回撤熔断"
            }

            // ATR 止损（收盘触发，直接平仓）
            if (p.useAtrStop && abs(wHeld) > 1e-9 && entryPrice > 0) {
                val stop = if (wHeld > 0) entryPrice - p.atrK * entryAtr else entryPrice + p.atrK * entryAtr
                val hit = if (wHeld > 0) closes[t + 1] <= stop else closes[t + 1] >= stop
                if (hit) {
                    eq *= (1.0 - abs(wHeld) * (p.commission + p.slippage))
                    turnoverAbs += abs(wHeld)
                    wHeld = 0.0
                    forceFlatNext = true
                    exitNote = "ATR止损"
                }
            }

            posAbsSum += abs(wHeld)
            if (abs(wHeld) > 1e-9) investedDays++
            eqCurve.add(eq)

            steps.add(
                Step(
                    idx = t + 1,
                    date = nextDate,
                    close = closes[t + 1],
                    phaseStartDate = dates[phaseStart],
                    phaseBars = t - phaseStart + 1,
                    newBreak = newBreak,
                    cooling = cooling,
                    sigmaF = sgF,
                    sigmaLR = sgLR,
                    rRatio = ratio,
                    scale = scale,
                    dir = dir,
                    wTarget = wT,
                    w = wHeld,
                    equity = eq,
                    dd = dd,
                    halted = t < haltUntil,
                    exitNote = exitNote
                )
            )
        }

        if (steps.isEmpty()) return null

        // ── 下一交易日：最后一根收盘后用同一套规则外推（不做执行模拟） ──
        val nextDay: NextDay? = params?.let { pp ->
            // vCur 是 r[n-2] 的方差；先递推到 r[n-1]，再取 h=1 预测（即次日 r[n]）
            val vLast = nextVariance(vCur, r[n - 1], pp)
            val sigmaAnn = forecastSigma(vLast, pp, 1)
            val sigmaDaily = sigmaAnn / sqrt(252.0)
            val from = phaseStart.coerceAtLeast(1)
            val sigmaLR = sqrt(meanSquare(r, from, n - 1)) * sqrt(252.0)
            val ratio = sigmaAnn / sigmaLR.coerceAtLeast(1e-9)
            val scale = when {
                ratio > 2.2 -> 0.3
                ratio > 1.5 -> 0.6
                else -> 1.0
            }
            val dir = direction(ma20, ma60, n - 1)
            var wT = if (dir == 0) 0.0 else {
                val base = (p.sigmaTarget / sigmaAnn.coerceAtLeast(1e-6)).coerceIn(0.0, p.maxLeverage)
                val signed = base * scale * if (dir > 0) 1.0 else -1.0
                if (!p.allowShort && signed < 0) 0.0 else signed
            }
            val cooling = n - 1 < coolUntil
            if (cooling && wT != 0.0 && wHeld == 0.0) wT = 0.0
            val halted = n - 1 < haltUntil
            if (halted) wT = 0.0
            val forcedFlat = forceFlatNext
            if (forcedFlat) wT = 0.0
            var wNext = wT
            if (abs(wNext - wHeld) < p.deadband) wNext = wHeld
            val delta = wNext - wHeld
            val action = when {
                forcedFlat -> "空仓观望（ATR 止损后强制空仓）"
                halted -> "强制空仓（回撤熔断停手期内）"
                abs(delta) < 1e-9 && abs(wNext) < 1e-9 -> "空仓观望（方向不明或冷却中）"
                abs(delta) < 1e-9 -> "持有不动（变动未达死区）"
                abs(wHeld) < 1e-9 -> if (wNext > 0) "建仓买入" else "建仓做空"
                abs(wNext) < 1e-9 -> "清仓卖出"
                wNext * wHeld < 0 -> if (wNext > 0) "反手做多" else "反手做空"
                abs(wNext) > abs(wHeld) -> if (wNext > 0) "加仓" else "加空"
                else -> if (wNext > 0) "减仓" else "减空"
            }
            val px = closes[n - 1]
            val stop = if (p.useAtrStop && abs(wHeld) > 1e-9 && entryPrice > 0)
                (if (wHeld > 0) entryPrice - p.atrK * entryAtr else entryPrice + p.atrK * entryAtr)
            else null
            NextDay(
                lastDate = dates[n - 1], close = px,
                sigmaDaily = sigmaDaily, sigmaAnn = sigmaAnn,
                sigmaLR = sigmaLR, rRatio = ratio, scale = scale, dir = dir,
                wTarget = wT, wCur = wHeld, wNext = wNext, delta = delta, action = action,
                cooling = cooling, halted = halted, forcedFlat = forcedFlat,
                up1 = px * exp(sigmaDaily), dn1 = px * exp(-sigmaDaily),
                up2 = px * exp(2.0 * sigmaDaily), dn2 = px * exp(-2.0 * sigmaDaily),
                stopPrice = stop
            )
        }

        // 指标
        val eqRets = DoubleArray(eqCurve.size) { i ->
            if (i == 0) 0.0 else ln(eqCurve[i] / eqCurve[i - 1].coerceAtLeast(1e-12))
        }
        val years = steps.size / 252.0
        val annRet = (eq.pow(1.0 / years.coerceAtLeast(1e-9)) - 1.0)
        val annVol = stdev(eqRets) * sqrt(252.0)
        var mdd = 0.0; var pk = eqCurve.first()
        eqCurve.forEach { v -> pk = max(pk, v); mdd = max(mdd, 1.0 - v / pk) }
        val metrics = Metrics(
            annRet = annRet,
            annVol = annVol,
            mdd = mdd,
            turnover = turnoverAbs / years.coerceAtLeast(1e-9),
            avgAbsPos = if (steps.isEmpty()) 0.0 else posAbsSum / steps.size,
            investedPct = if (steps.isEmpty()) 0.0 else investedDays.toDouble() / steps.size,
            bars = steps.size,
            trades = trades,
            hitDays = steps.count { it.exitNote == "ATR止损" }
        )

        return Result(
            dates = dates,
            closes = closes.toList(),
            steps = steps,
            breakIdx = breakIdx,
            garch = garch,
            metrics = metrics,
            lastSignal = steps.last(),
            nextDay = nextDay,
            warmup = warmup
        )
    }

    // ═════════════════════════ L1 PELT（L2 均值漂移代价） ═════════════════════════

    /**
     * PELT：F(t) = min_{s≤t-minSize} [F(s) + cost(s,t) + pen]，F(0) = -pen。
     * 输入需先标准化（否则 log 收益的代价量级远小于 pen=3·ln n，永远检不出断点）。
     */
    private fun pelt(x: DoubleArray, pen: Double, minSize: Int): List<Int> {
        val n = x.size
        if (n < 2 * minSize) return emptyList()
        val p1 = DoubleArray(n + 1)
        val p2 = DoubleArray(n + 1)
        for (i in 0 until n) {
            p1[i + 1] = p1[i] + x[i]
            p2[i + 1] = p2[i] + x[i] * x[i]
        }
        fun cost(s: Int, e: Int): Double {
            val k = (e - s).toDouble()
            if (k <= 0) return 0.0
            val sum = p1[e] - p1[s]
            return p2[e] - p2[s] - sum * sum / k
        }
        val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val lastIdx = IntArray(n + 1) { -1 }
        best[0] = -pen
        for (t in minSize..n) {
            var bv = Double.POSITIVE_INFINITY
            var bi = -1
            var s = 0
            while (s <= t - minSize) {
                if (best[s].isFinite()) {
                    val v = best[s] + cost(s, t) + pen
                    if (v < bv) { bv = v; bi = s }
                }
                s++
            }
            if (bi >= 0) { best[t] = bv; lastIdx[t] = bi }
        }
        val cps = ArrayList<Int>()
        var t = n
        while (t > 0) {
            val s = lastIdx[t]
            if (s <= 0) break
            cps.add(s)
            t = s
        }
        cps.reverse()
        return cps
    }

    private fun zScore(x: DoubleArray): DoubleArray {
        val m = x.average()
        val sd = sqrt(x.sumOf { (it - m) * (it - m) } / x.size).coerceAtLeast(1e-12)
        return DoubleArray(x.size) { (x[it] - m) / sd }
    }

    // ═════════════════════════ L2 GJR-GARCH(1,1,1) + t ═════════════════════════

    private class FitRes(
        val alpha: Double, val beta: Double, val gamma: Double,
        val omega: Double, val nu: Double, val sampleVar: Double, val fitted: Boolean
    ) {
        val xi get() = alpha + beta + gamma / 2.0
    }

    /** 方差目标化：ω = sampleVar·(1 − ξ)；参数用 Nelder-Mead 最大化 t 似然 */
    private fun fitGjr(r: DoubleArray): FitRes {
        val n = r.size
        val s2 = meanSquare(r, 0, n - 1).coerceAtLeast(1e-10)
        if (n < 60) return FitRes(0.05, 0.90, 0.05, s2 * 0.05, 6.0, s2, false)

        fun loglik(a: Double, b: Double, g: Double, nu: Double): Double {
            val xi = a + b + g / 2.0
            if (xi >= 0.999 || a < 0.0 || b < 0.0 || g < 0.0) return -1e12
            val om = s2 * (1.0 - xi)
            var v = s2
            var ll = 0.0
            val cst = lgamma((nu + 1) / 2.0) - lgamma(nu / 2.0) - 0.5 * ln(nu * PI)
            for (i in 1 until n) {
                val rr = r[i - 1]
                v = om + (a + if (rr < 0) g else 0.0) * rr * rr + b * v
                if (v < 1e-12) v = 1e-12
                ll += cst - 0.5 * (nu + 1.0) * ln(1.0 + r[i] * r[i] / (nu * v)) - 0.5 * ln(v)
            }
            return ll
        }

        // 参数化：u = [a, b, g, nu]，带边界裁剪
        fun clamp(u: DoubleArray): DoubleArray = doubleArrayOf(
            u[0].coerceIn(1e-4, 0.5),
            u[1].coerceIn(1e-4, 0.995),
            u[2].coerceIn(0.0, 0.5),
            u[3].coerceIn(3.0, 20.0)
        )
        var u = clamp(doubleArrayOf(0.05, 0.90, 0.05, 6.0))
        var f = loglik(u[0], u[1], u[2], u[3])

        // Nelder-Mead（4 维）
        val dim = 4
        val simplex = Array(dim + 1) { i ->
            val p = u.copyOf()
            if (i < dim) {
                p[i] = if (abs(p[i]) < 1e-6) 1e-3 else p[i] * 1.08
            }
            clamp(p)
        }
        val vals = DoubleArray(dim + 1) { i -> loglik(simplex[i][0], simplex[i][1], simplex[i][2], simplex[i][3]) }
        repeat(140) {
            val order = (0..dim).sortedBy { vals[it] }
            val s0 = order[0]
            val sN = order[dim]
            var centroid = DoubleArray(dim)
            for (i in 0 until dim) {
                val idx = order[i]
                for (d in 0 until dim) centroid[d] += simplex[idx][d] / dim.toDouble()
            }
            // 反射
            val xr = clamp(DoubleArray(dim) { d -> centroid[d] + (centroid[d] - simplex[sN][d]) })
            val fr = loglik(xr[0], xr[1], xr[2], xr[3])
            val sortedVals = order.map { vals[it] }
            when {
                fr < sortedVals[0] -> {
                    val xe = clamp(DoubleArray(dim) { d -> centroid[d] + 2.0 * (centroid[d] - simplex[sN][d]) })
                    val fe = loglik(xe[0], xe[1], xe[2], xe[3])
                    if (fe < fr) { simplex[sN] = xe; vals[sN] = fe } else { simplex[sN] = xr; vals[sN] = fr }
                }
                fr < sortedVals[dim - 1] -> { simplex[sN] = xr; vals[sN] = fr }
                else -> {
                    val xc = clamp(DoubleArray(dim) { d -> centroid[d] + 0.5 * (simplex[sN][d] - centroid[d]) })
                    val fc = loglik(xc[0], xc[1], xc[2], xc[3])
                    if (fc < vals[sN]) { simplex[sN] = xc; vals[sN] = fc }
                    else {
                        for (i in 1..dim) {
                            val idx = order[i]
                            simplex[idx] = clamp(DoubleArray(dim) { d -> 0.5 * (simplex[s0][d] + simplex[idx][d]) })
                            vals[idx] = loglik(simplex[idx][0], simplex[idx][1], simplex[idx][2], simplex[idx][3])
                        }
                    }
                }
            }
        }
        val bi = (0..dim).minByOrNull { vals[it] } ?: 0
        val bp = simplex[bi]
        if (!vals[bi].isFinite() || vals[bi] < -1e11) {
            return FitRes(0.05, 0.90, 0.05, s2 * 0.05, 6.0, s2, false)
        }
        val xi = bp[0] + bp[1] + bp[2] / 2.0
        return FitRes(bp[0], bp[1], bp[2], s2 * (1.0 - xi), bp[3], s2, true)
    }

    /** 阶段内递推滤波，返回末端条件方差 */
    private fun filterVariance(r: DoubleArray, from: Int, to: Int, p: FitRes): Double {
        var v = p.sampleVar
        for (i in (from + 1)..to) {
            v = nextVariance(v, r[i - 1], p)
        }
        return v
    }

    private fun nextVariance(vPrev: Double, rPrev: Double, p: FitRes): Double {
        val v = p.omega + (p.alpha + if (rPrev < 0) p.gamma else 0.0) * rPrev * rPrev + p.beta * vPrev
        return v.coerceAtLeast(1e-12)
    }

    /** 多步预测方差均值 → 年化 σ_f */
    private fun forecastSigma(vLast: Double, p: FitRes, steps: Int): Double {
        val keep = p.alpha + p.gamma / 2.0 + p.beta   // E[1(r<0)] = 0.5
        var v = vLast
        var sum = 0.0
        for (h in 1..steps) {
            v = p.omega + keep * v
            sum += v.coerceAtLeast(1e-12)
        }
        return sqrt(sum / steps) * sqrt(252.0)
    }

    private fun toGarch(p: FitRes, r: DoubleArray, from: Int, to: Int): Garch {
        // QLIKE 与 ±1.96σ 覆盖率（样本内一步预测）
        var v = p.sampleVar
        var q = 0.0
        var cover = 0
        var cnt = 0
        for (i in (from + 1)..to) {
            val rr = r[i - 1]
            v = nextVariance(v, rr, p)
            val r2 = r[i] * r[i]
            q += r2 / v + ln(v)
            if (abs(r[i]) <= 1.96 * sqrt(v)) cover++
            cnt++
        }
        val xi = p.xi
        val hl = if (xi in 1e-6..0.9999) ln(0.5) / ln(xi) else Double.NaN
        return Garch(
            alpha = p.alpha, beta = p.beta, gamma = p.gamma, omega = p.omega, nu = p.nu,
            xi = xi, halfLife = hl,
            qlike = if (cnt > 0) q / cnt else Double.NaN,
            coverage = if (cnt > 0) cover.toDouble() / cnt else Double.NaN,
            n = cnt, fitted = p.fitted
        )
    }

    // ═════════════════════════ 方向层 ═════════════════════════

    /** d = +1：MA20 与 MA60 同时上行且连续 2 日；-1：同时下行连续 2 日；否则 0 */
    private fun direction(ma20: DoubleArray, ma60: DoubleArray, t: Int): Int {
        if (t < 2) return 0
        fun slope(ma: DoubleArray, i: Int): Double {
            if (i < 1 || ma[i].isNaN() || ma[i - 1].isNaN()) return Double.NaN
            return ma[i] - ma[i - 1]
        }
        val s20 = slope(ma20, t); val s20p = slope(ma20, t - 1)
        val s60 = slope(ma60, t); val s60p = slope(ma60, t - 1)
        if (s20.isNaN() || s60.isNaN() || s20p.isNaN() || s60p.isNaN()) return 0
        val up = s20 > 0.0 && s60 > 0.0 && s20p > 0.0 && s60p > 0.0
        val dn = s20 < 0.0 && s60 < 0.0 && s20p < 0.0 && s60p < 0.0
        return if (up) 1 else if (dn) -1 else 0
    }

    // ═════════════════════════ 小工具 ═════════════════════════

    private fun movingAverage(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i]
            if (i >= n) sum -= x[i - n]
            if (i >= n - 1) out[i] = sum / n
        }
        return out
    }

    private fun atrSeries(hi: DoubleArray, lo: DoubleArray, cls: DoubleArray, n: Int): DoubleArray {
        val tr = DoubleArray(cls.size) { i ->
            if (i == 0) hi[i] - lo[i]
            else maxOf(hi[i] - lo[i], abs(hi[i] - cls[i - 1]), abs(lo[i] - cls[i - 1]))
        }
        val out = DoubleArray(cls.size) { Double.NaN }
        var sum = 0.0
        for (i in tr.indices) {
            sum += tr[i]
            if (i >= n) sum -= tr[i - n]
            if (i >= n - 1) out[i] = sum / n
        }
        return out
    }

    private fun meanSquare(x: DoubleArray, from: Int, to: Int): Double {
        if (to < from) return 0.0
        var s = 0.0
        for (i in from..to) s += x[i] * x[i]
        return s / (to - from + 1)
    }

    private fun stdev(x: DoubleArray): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    /** Lanczos 近似的 lnΓ(x) */
    private fun lgamma(x: Double): Double {
        if (x < 0.5) return ln(PI / sin(PI * x)) - lgamma(1.0 - x)
        val g = 7.0
        val c = doubleArrayOf(
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7
        )
        val z = x - 1.0
        var a = c[0]
        val t = z + g + 0.5
        for (i in 1..8) a += c[i] / (z + i)
        return 0.5 * ln(2.0 * PI) + (z + 0.5) * ln(t) - t + ln(a)
    }
}
