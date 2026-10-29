package com.chin.stockanalysis.strategy.analysis

import kotlin.math.*

/**
 * ## 相空间重构 + 相似态近邻预测（Takens 延迟嵌入 / Lorenz Method of Analogues）
 *
 * 纯算法、无 Android 依赖。双方法对比：
 *  - **方法A 价格**：直接对 log 收盘 z-score 后嵌入（含趋势，吸引子是"游走团块"）
 *  - **方法B cycle**：先 SSA(L=30) 提取 cycle 分量（去趋势去噪，振荡结构），对 cycle 嵌入；
 *    预测价格 = 趋势基底(末日趋势平推) + cycle 预测值；拟合价 = 当日实际趋势 + cycle 拟合值
 *
 * 流程（两法共用）：AMI 首局部极小 → τ；FNN<10% → m；
 * kNN 相似态加权投票 → 未来 h 步；末 60 根走查拟合 + 近 evalN 根滚动回测（相关系数/方向命中率）。
 */
object PhaseSpaceEngine {

    data class Pass(
        val label: String,
        val tau: Int,
        val m: Int,
        val ami: List<Double>,            // lag=1..maxLag 的 AMI
        val fnnPct: List<Double>,         // m=1..6 的假近邻率%
        val attractor: List<FloatArray>,  // [x(t), x(t-τ), recency 0旧→1新]
        val histDates: List<String>,      // 对比图窗口（末 60 根）
        val actual: List<Double>,         // 实际收盘
        val fitted: List<Double?>,        // 走查 1 步拟合价（对齐 actual）
        val fcPrices: List<Double>,       // 未来 H 根预测价
        val fcPct: List<Double>,          // 相对最新收盘的涨跌幅%
        val corrByH: List<Double>,        // h=1..H 走查相关系数（末 60 根，z 空间）
        val corrPctByH: List<Double>,     // h=1..H 涨跌幅口径相关系数（预测Δ% vs 实际Δ%）
        val pctScatter: List<Pair<Float, Float>>,  // h=1 散点：实际Δ% → 预测Δ%（幅度精度可视化）
        val rollCorr: List<Double>,       // 滚动回测相关系数（长窗口）
        val rollHit: List<Double>,        // 滚动回测方向命中率%
        val rollDates: List<String>,      // 滚动回测窗口日期
        val hitCurve: List<Double>,       // 1步方向命中率 20根滑动均值（%）
        val rollN: Int,                   // 滚动回测实际样本根数
        val walkHit: List<Boolean?>       // 走查窗口每根 1步方向命中（null=平/无预测）；B 用前日趋势平推口径，不含当日趋势泄漏
    )

    data class Result(
        val lastPrice: Double,
        val price: Pass,                  // 方法A：价格水平
        val cycle: Pass?,                 // 方法B：SSA cycle（分解失败时 null）
        val ret: Pass? = null,            // 方法C：对数收益率嵌入
        val doji: List<Boolean> = emptyList()  // 走查窗口对齐：十字星标记（实体/振幅≤10%），无 OHLC 时为空
    )

    fun analyze(dates: List<String>, closes: DoubleArray, horizon: Int = 5,
                evalN: Int = 250, cycleL: Int = 0,
                opens: DoubleArray? = null, highs: DoubleArray? = null, lows: DoubleArray? = null): Result? {
        val n = closes.size
        if (n < 120 || dates.size != n) return null
        val last = closes[n - 1]

        // ── 方法A：对数价格水平 ──
        val lg = DoubleArray(n) { ln(closes[it].coerceAtLeast(1e-9)) }
        val mu = lg.average()
        val sd = sqrt(lg.sumOf { (it - mu) * (it - mu) } / n).coerceAtLeast(1e-9)
        val xPrice = DoubleArray(n) { (lg[it] - mu) / sd }
        val pricePass = runPass("价格", dates, closes, xPrice, horizon, evalN, last,
            fcToPrice = { z -> exp(z * sd + mu) },
            fitToPrice = { _, z -> exp(z * sd + mu) })

        // ── 方法B：因果 SSA cycle（L 自动寻优：ACF 主导周期 P → L≈P）──
        val autoL = if (cycleL > 0) cycleL else selectCycleL(closes)
        val cycFull = runCatching { causalCycle(closes, autoL) }.getOrNull()
        val validFrom = if (cycFull != null)
            (0 until n).firstOrNull { !cycFull[it].isNaN() } ?: n else n
        val cyclePass = if (cycFull != null && n - validFrom >= 120) {
            // 裁掉因果分解不可用前缀，之后的序列完全无前视
            val dts = dates.subList(validFrom, n)
            val cls = closes.copyOfRange(validFrom, n)
            val cyc = cycFull.copyOfRange(validFrom, n)
            val nn = cls.size
            val trend = DoubleArray(nn) { cls[it] - cyc[it] }   // 趋势基底 = 收盘 - cycle（因果）
            val cmu = cyc.average()
            val csd = sqrt(cyc.sumOf { (it - cmu) * (it - cmu) } / nn).coerceAtLeast(1e-9)
            val xCyc = DoubleArray(nn) { (cyc[it] - cmu) / csd }
            runPass("cycle因果 L=$autoL", dts, cls, xCyc, horizon, evalN, last,
                fcToPrice = { z -> trend[nn - 1] + z * csd },  // 预测：末日趋势平推 + cycle
                fitToPrice = { t, z -> trend[t] + z * csd })   // 拟合：当日实际趋势 + cycle
        } else null

        // ── 方法C：对数收益率嵌入（去水平只留变动节奏；预测价=逐步收益率累乘还原）──
        val lr = DoubleArray(n) { i -> if (i == 0) 0.0 else ln(closes[i] / closes[i - 1]) }
        val rmu = lr.average()
        val rsd = sqrt(lr.sumOf { (it - rmu) * (it - rmu) } / n).coerceAtLeast(1e-9)
        val xRet = DoubleArray(n) { (lr[it] - rmu) / rsd }
        val retPass0 = runPass("收益率", dates, closes, xRet, horizon, evalN, last,
            fcToPrice = { it },  // 占位，下面按累计收益覆写
            fitToPrice = { t, z -> if (t >= 1) closes[t - 1] * exp(z * rsd + rmu) else closes[0] },
            hitZeroBased = true)
        var cum = 0.0
        val fcP = retPass0.fcPrices.map { z -> cum += z * rsd + rmu; last * exp(cum) }
        val retPass = retPass0.copy(fcPrices = fcP, fcPct = fcP.map { (it / last - 1) * 100.0 })

        // ── 十字星标记（走查窗口对齐）：实体/振幅 ≤10% ──
        val doji = if (opens != null && highs != null && lows != null &&
            opens.size == n && highs.size == n && lows.size == n)
            ((n - 60).coerceAtLeast(0) until n).map { t ->
                val rng = highs[t] - lows[t]
                rng > 0 && abs(closes[t] - opens[t]) / rng <= 0.1
            } else emptyList()

        return Result(last, pricePass, cyclePass, retPass, doji)
    }

    /**
     * 因果 SSA cycle 序列：对每个 t，仅截 closes[0..t] 做标准 SSA 分解并取**当次分解的末端值**。
     * 消除全序列一次性分解的前视偏差（Hankel 矩阵两端纠缠，cycle[t] 隐含 t 之后的数据）。
     * 前缀 t < 2L 数据不足以分解，置 NaN（由 analyze 裁掉）。计算量 O(n) 次分解，手机端数秒。
     */
    private fun causalCycle(closes: DoubleArray, L: Int): DoubleArray {
        val n = closes.size
        val out = DoubleArray(n) { Double.NaN }
        val minT = (2 * L).coerceAtMost(n - 1)
        for (t in minT until n) {
            val c = runCatching {
                com.chin.stockanalysis.strategy.ssa.SsaEngine.decompose(closes.copyOfRange(0, t + 1), L).cycle
            }.getOrNull() ?: continue
            out[t] = c[t]   // 只取末端值——该值只用了 ≤t 的数据，严格因果
        }
        return out
    }

    /**
     * L 自动寻优：对数价格线性去趋势 → 残差 ACF → 第一个显著局部极大 lag = 主导周期 P → L≈P。
     * SSA 经验法则：窗口 L 约等于要解析的周期才能分离出完整振荡对。
     * 无显著周期（纯趋势/随机票）回退默认 30。范围 clamp [20, 80] 且 ≤ n/4。
     */
    private fun selectCycleL(closes: DoubleArray): Int {
        val n = closes.size
        // 对数 + 线性去趋势
        val y = DoubleArray(n) { ln(closes[it].coerceAtLeast(1e-9)) }
        val xm = (n - 1) / 2.0
        val ym = y.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { val dx = i - xm; sxy += dx * (y[i] - ym); sxx += dx * dx }
        val b = if (sxx > 0) sxy / sxx else 0.0
        val a = ym - b * xm
        val r = DoubleArray(n) { y[it] - (a + b * it) }
        // ACF，lag 5..min(n/3, 120)
        var s0 = 0.0; for (v in r) s0 += v * v
        if (s0 < 1e-12) return 30
        val maxLag = minOf(n / 3, 120)
        fun acf(k: Int): Double {
            var s = 0.0; for (i in 0 until n - k) s += r[i] * r[i + k]
            return s / s0
        }
        // 第一个显著局部极大（acf>0.2）即主导周期
        for (k in 8 until maxLag - 1) {
            val v = acf(k)
            if (v > 0.2 && v > acf(k - 1) && v >= acf(k + 1)) {
                return k.coerceIn(20, 80).coerceAtMost(n / 4)
            }
        }
        return 30
    }
    private fun runPass(label: String, dates: List<String>, closes: DoubleArray, x: DoubleArray,
                        horizon: Int, evalN: Int, last: Double,
                        fcToPrice: (Double) -> Double,
                        fitToPrice: (Int, Double) -> Double,
                        hitZeroBased: Boolean = false): Pass {
        val n = x.size

        // ── τ：AMI 第一局部极小 ──
        val maxLag = minOf(40, n / 5)
        val ami = amiSeries(x, maxLag)
        var tau = 1
        for (l in 2 until maxLag - 1) {
            if (ami[l - 1] < ami[l - 2] && ami[l - 1] < ami[l]) { tau = l; break }
        }
        if (tau == 1) { // 无局部极小 → 跌至首值 1/e
            val th = ami.first() / E
            for (l in ami.indices) if (ami[l] < th) { tau = l + 1; break }
        }

        // ── m：假近邻 ──
        val maxM = 6
        val fnn = (1..maxM).map { fnnPct(x, it, tau) }
        var m = fnn.indexOfFirst { it < 10.0 } + 1
        if (m <= 0) m = fnn.indices.minByOrNull { fnn[it] }!! + 1
        m = m.coerceIn(2, maxM)

        // ── 嵌入向量 ──
        val off = (m - 1) * tau
        val cnt = n - off
        fun vec(t: Int) = DoubleArray(m) { k -> x[t - k * tau] }
        fun dist2(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0; for (k in a.indices) { val d = a[k] - b[k]; s += d * d }; return s
        }
        val theiler = off + 1

        /** 邻点时刻必须 < tIdx-theiler 且 +outH 不越界，返回 (时刻, 距离²) 升序 */
        fun neighbors(tIdx: Int, outH: Int): List<Pair<Int, Double>> {
            val q = vec(tIdx)
            val lim = tIdx - theiler
            val list = ArrayList<Pair<Int, Double>>()
            var j = off
            while (j <= lim - outH) {
                list.add(j to dist2(q, vec(j)))
                j++
            }
            list.sortBy { it.second }
            return list
        }

        /** kNN 加权预测：tIdx 时刻状态 → h 步后的 z 值；邻点不足 3 返回 null */
        fun predict(tIdx: Int, h: Int): Double? {
            val nb = neighbors(tIdx, h)
            if (nb.size < 3) return null
            val k = minOf(maxOf(8, m + 2), nb.size)
            val top = nb.subList(0, k)
            val med = top[k / 2].second.coerceAtLeast(1e-12)
            var sw = 0.0; var sv = 0.0
            for ((j, d2) in top) {
                val w = exp(-d2 / (2 * med))
                sw += w; sv += w * x[j + h]
            }
            return if (sw > 0) sv / sw else null
        }

        // ── 走查：末 60 根（对比图 + 相关系数）──
        val backN = 60
        val startT = n - backN
        val actual = ArrayList<Double>(backN)
        val fitted = ArrayList<Double?>(backN)
        val walkHit = ArrayList<Boolean?>(backN)
        val errs = Array(horizon) { ArrayList<Pair<Double, Double>>() }
        val errsPct = Array(horizon) { ArrayList<Pair<Double, Double>>() }
        for (t in startT until n) {
            actual.add(closes[t])
            val p1 = predict(t - 1, 1)
            if (p1 == null) { fitted.add(null); walkHit.add(null) }
            else {
                fitted.add(fitToPrice(t, p1))
                // 方向命中：B 用 t-1 前日趋势平推口径（无前视）；收益率通道用 z 符号口径；|实际变动|<0.3% 视为平
                val da = closes[t] - closes[t - 1]
                walkHit.add(if (abs(da / closes[t - 1]) < 0.003) null
                    else if (hitZeroBased) (p1 > 0) == (x[t] > 0)
                    else (fitToPrice(t - 1, p1) > closes[t - 1]) == (da > 0))
            }
            for (h in 1..horizon) {
                if (t + h > n - 1) break
                predict(t, h)?.let { p ->
                    errs[h - 1].add(p to x[t + h])
                    if (closes[t] > 0) errsPct[h - 1].add(
                        ((fitToPrice(t, p) - closes[t]) / closes[t]) to ((closes[t + h] - closes[t]) / closes[t]))
                }
            }
        }
        val corrByH = errs.map { pearson(it) }
        val corrPctByH = errsPct.map { pearson(it) }
        val pctScatter = errsPct[0].map { (it.second * 100).toFloat() to (it.first * 100).toFloat() }

        // ── 滚动回测：近 evalN 根（相关系数 + 方向命中率 + 1步命中滑动曲线）──
        val evalStart = maxOf(off + theiler + 10, n - evalN)
        val evalErrs = Array(horizon) { ArrayList<Pair<Double, Double>>() }
        val hitCnt = IntArray(horizon); val hitTot = IntArray(horizon)
        val rollDates = ArrayList<String>()
        val hitFlags = ArrayList<Int>()
        for (t in evalStart until n) {
            rollDates.add(dates[t])
            var f1 = 0
            for (h in 1..horizon) {
                if (t + h > n - 1) break
                val p = predict(t, h) ?: continue
                evalErrs[h - 1].add(p to x[t + h])
                hitTot[h - 1]++
                if ((p > x[t]) == (x[t + h] > x[t])) {
                    hitCnt[h - 1]++
                    if (h == 1) f1 = 1
                }
            }
            hitFlags.add(f1)
        }
        val rollCorr = evalErrs.map { pearson(it) }
        val rollHit = (0 until horizon).map {
            if (hitTot[it] > 0) hitCnt[it] * 100.0 / hitTot[it] else 0.0
        }
        val win = 20
        val hitCurve = hitFlags.indices.map { i ->
            val s = maxOf(0, i - win + 1)
            hitFlags.subList(s, i + 1).average() * 100.0
        }

        // ── 当前状态 → 未来 H 根 ──
        val fcX = (1..horizon).mapNotNull { h -> predict(n - 1, h) }
        val fcPrices = fcX.map { fcToPrice(it) }
        val fcPct = fcPrices.map { (it / last - 1) * 100.0 }

        // ── 吸引子投影（末 250 个向量，x(t) vs x(t-τ)）──
        val attN = minOf(250, cnt)
        val attractor = (cnt - attN until cnt).map { i ->
            val t = off + i
            floatArrayOf(x[t].toFloat(), x[t - tau].toFloat(), i.toFloat() / (attN - 1).coerceAtLeast(1))
        }

        return Pass(
            label = label, tau = tau, m = m, ami = ami, fnnPct = fnn,
            attractor = attractor,
            histDates = dates.subList(startT, n),
            actual = actual, fitted = fitted,
            fcPrices = fcPrices, fcPct = fcPct, corrByH = corrByH,
            corrPctByH = corrPctByH, pctScatter = pctScatter,
            rollCorr = rollCorr, rollHit = rollHit,
            rollDates = rollDates, hitCurve = hitCurve,
            rollN = hitTot[0], walkHit = walkHit
        )
    }

    /** 平均互信息：16 bin 直方图，lag=1..maxLag */
    private fun amiSeries(x: DoubleArray, maxLag: Int): List<Double> {
        val n = x.size
        val bins = 16
        val lo = x.minOrNull()!!; val hi = x.maxOrNull()!!
        val range = (hi - lo).coerceAtLeast(1e-9)
        fun bin(v: Double) = ((v - lo) / range * (bins - 1)).toInt().coerceIn(0, bins - 1)
        val out = ArrayList<Double>(maxLag)
        for (lag in 1..maxLag) {
            val px = DoubleArray(bins); val py = DoubleArray(bins)
            val pxy = Array(bins) { DoubleArray(bins) }
            val c = n - lag
            for (i in lag until n) {
                val a = bin(x[i]); val b = bin(x[i - lag])
                px[a]++; py[b]++; pxy[a][b]++
            }
            var s = 0.0
            for (a in 0 until bins) for (b in 0 until bins) {
                val p = pxy[a][b]
                if (p > 0) s += p / c * ln(p * c / (px[a] * py[b]))
            }
            out.add(s)
        }
        return out
    }

    /** 假近邻率%：维数 m → m+1 时距离放大倍数 > 15 视为假近邻 */
    private fun fnnPct(x: DoubleArray, m: Int, tau: Int): Double {
        val n = x.size
        val off = m * tau
        val cnt = n - off
        if (cnt < 30) return 100.0
        var falseCnt = 0
        val rt = 15.0
        for (i in off until n) {
            var bestJ = -1; var bestD = Double.MAX_VALUE
            for (j in off until n) {
                if (abs(i - j) <= tau) continue
                var s = 0.0
                for (k in 0 until m) { val d = x[i - k * tau] - x[j - k * tau]; s += d * d }
                if (s < bestD) { bestD = s; bestJ = j }
            }
            if (bestJ < 0 || bestD < 1e-12) continue
            val dNext = abs(x[i] - x[bestJ])
            if (dNext / sqrt(bestD) > rt) falseCnt++
        }
        return falseCnt * 100.0 / cnt
    }

    private fun pearson(pairs: List<Pair<Double, Double>>): Double {
        if (pairs.size < 5) return 0.0
        val a = pairs.map { it.first }; val b = pairs.map { it.second }
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) {
            val da = a[i] - ma; val db = b[i] - mb
            sab += da * db; saa += da * da; sbb += db * db
        }
        return if (saa > 0 && sbb > 0) sab / sqrt(saa * sbb) else 0.0
    }
}
