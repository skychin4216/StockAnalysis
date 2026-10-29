package com.chin.stockanalysis.strategy.analysis

import kotlin.math.*

/**
 * ## ARMA 参数谱估计（Yule-Walker / 创新算法 / Hannan-Rissanen / Bairstow 求根）
 *
 * 流程：
 *  - 对数价格线性去趋势 → 残差 → 标准化
 *  - AR(p)：Burg 最大熵法（Marple 格滤波，|k|≤1 保证极点在圆内/上）→ 系数 c1..cp、白噪方差 σ²
 *  - ARMA(p,q)：Hannan-Rissanen 两步法（高阶 AR 预白化 → 回归得 ARMA 参数）
 *  - 极点 = H(z) 分母 z^p - c1 z^{p-1} - ... - cp = 0 的根；零点 = z^q + b1 z^{q-1} + ... + bq = 0 的根
 *  - 功率谱 PSD(ω) = σ² · |1 + Σ bj e^{-ijω}|² / |1 - Σ ci e^{-ikω}|²，ω∈[0,π] 即绕单位圆一圈
 *  - 预测：ARMA 多步递推（h>q 时 MA 创新项归零）；走查 1 步拟合 + 滚动方向命中率 + 相关系数
 *
 * 极点/零点在单位圆上/内 → 决定振荡频率与衰减；绕圆一圈的 PSD 峰谷即谱估计。
 */
object ArmaEngine {

    data class Complex(val re: Double, val im: Double) {
        val abs: Double get() = sqrt(re * re + im * im)
        val arg: Double get() = atan2(im, re)
    }

    data class Model(
        val p: Int, val q: Int,
        val ar: DoubleArray,    // a1..ap
        val ma: DoubleArray,    // b1..bq
        val sigma2: Double,
        val poles: List<Complex>,
        val zeros: List<Complex>
    )

    data class Pass(
        val label: String,
        val model: Model,
        val histDates: List<String>,
        val actual: List<Double>,
        val fitted: List<Double?>,     // 走查 1 步拟合价
        val fcPrices: List<Double>,    // 未来 H 根预测价
        val fcPct: List<Double>,       // 相对最新收盘涨跌幅%
        val corrByH: List<Double>,     // 走查相关系数
        val corrPctByH: List<Double>,  // 涨跌幅口径相关
        val rollHit: List<Double>,     // 滚动方向命中率%
        val rollDates: List<String>,
        val rollN: Int,
        val psdW: List<Double>,        // 角频率 0..π
        val psd: List<Double>,         // 功率谱
        val residual: List<Double>     // 走查残差（实际-拟合），用于诊断
    )

    data class Result(
        val lastPrice: Double,
        val ar: Pass,          // AR 通道
        val arma: Pass?        // ARMA 通道（q>0 时）
    )

    private const val MAX_P = 14
    private const val MAX_Q = 4

    fun analyze(dates: List<String>, closes: DoubleArray, horizon: Int = 5,
                pIn: Int = 0, qIn: Int = 2): Result? {
        val n = closes.size
        if (n < 120 || dates.size != n) return null
        val last = closes[n - 1]

        // 对数 + 线性去趋势
        val y = DoubleArray(n) { ln(closes[it].coerceAtLeast(1e-9)) }
        val xm = (n - 1) / 2.0
        val ym = y.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { val dx = i - xm; sxy += dx * (y[i] - ym); sxx += dx * dx }
        val b = if (sxx > 0) sxy / sxx else 0.0
        val a = ym - b * xm
        val detrend = DoubleArray(n) { y[it] - (a + b * it) }
        val mu = detrend.average()
        val sd = sqrt(detrend.sumOf { (it - mu) * (it - mu) } / n).coerceAtLeast(1e-9)
        val x = DoubleArray(n) { (detrend[it] - mu) / sd }

        // 阶数：p 自动 AIC，q 用入参
        val p = if (pIn > 0) pIn else selectP(x)
        val q = qIn.coerceIn(0, MAX_Q)

        // ── AR 通道 ──
        val arModel = fitAR(x, p)
        val arPass = buildPass("AR($p)", dates, closes, x, arModel, horizon, last,
            fcToPrice = { z -> exp((z * sd + mu) + (a + b * (n - 1))) },
            fitToPrice = { t, z -> exp((z * sd + mu) + (a + b * t)) })

        // ── ARMA 通道 ──
        val armaPass = if (q > 0) {
            val armaModel = runCatching { fitARMA(x, p, q) }.getOrNull()
            if (armaModel != null)
                buildPass("ARMA($p,$q)", dates, closes, x, armaModel, horizon, last,
                    fcToPrice = { z -> exp((z * sd + mu) + (a + b * (n - 1))) },
                    fitToPrice = { t, z -> exp((z * sd + mu) + (a + b * t)) })
            else null
        } else null

        return Result(last, arPass, armaPass)
    }

    // ─────────────────────── AR：Durbin-Levinson ───────────────────────

    /** Yule-Walker 自相关 */
    private fun autocorr(x: DoubleArray, maxLag: Int): DoubleArray {
        val n = x.size
        val m = x.average()
        val r = DoubleArray(maxLag + 1)
        var s0 = 0.0
        for (v in x) s0 += (v - m) * (v - m)
        if (s0 < 1e-12) return r
        for (k in 0..maxLag) {
            var s = 0.0
            for (i in 0 until n - k) s += (x[i] - m) * (x[i + k] - m)
            r[k] = s / s0
        }
        return r
    }

    /**
     * Burg 法（最大熵谱估计）——反射系数 |k|≤1，极点必在单位圆内/上。
     * 标准 Marple 格滤波递推：前向/后向残差用独立数组同步更新（原地更新会污染）。
     * 返回预测系数 c（模型 x[t] = Σ cᵢ x[t-i] + ε）与 σ²；c = -（AR 多项式系数）。
     */
    private fun burg(x: DoubleArray, p: Int): Pair<DoubleArray, Double> {
        val n = x.size
        var f = x.copyOf()
        var b = x.copyOf()
        var sigma2 = x.sumOf { it * it } / n
        // AR 多项式系数 a[0..m]（a₀=1），模型 Σ aᵢ x[t-i] = ε
        var a = doubleArrayOf(1.0)
        for (m in 1..p) {
            var num = 0.0; var den = 0.0
            for (t in m until n) {
                num += f[t] * b[t - m]
                den += f[t] * f[t] + b[t - m] * b[t - m]
            }
            val k = (if (den > 1e-12) -2.0 * num / den else 0.0).coerceIn(-0.999, 0.999)
            val aOld = a
            a = DoubleArray(m + 1)
            a[0] = 1.0
            for (i in 1 until m) a[i] = aOld[i] + k * aOld[m - i]
            a[m] = k
            sigma2 *= (1.0 - k * k)
            val nf = f.copyOf(); val nb = b.copyOf()
            for (t in m until n) {
                nf[t] = f[t] + k * b[t - m]
                nb[t] = b[t - m] + k * f[t]
            }
            f = nf; b = nb
        }
        val c = DoubleArray(p) { -a[it + 1] }
        return c to maxOf(sigma2, 1e-12)
    }

    private fun fitAR(x: DoubleArray, p: Int): Model {
        val (ar, sigma2) = burg(x, p)
        // 极点 = H(z) 分母 z^p - c1 z^{p-1} - ... - cp = 0 的根（polyRoots 用升幂系数）
        val poles = runCatching { polyRoots(DoubleArray(p + 1) { if (it == p) 1.0 else -ar[p - 1 - it] }) }
            .getOrDefault(emptyList())
        return Model(p, 0, ar, DoubleArray(0), sigma2, poles, emptyList())
    }

    /** AIC 选 AR 阶数 1..MAX_P */
    private fun selectP(x: DoubleArray): Int {
        var best = 1; var bestAic = Double.POSITIVE_INFINITY
        for (p in 1..minOf(MAX_P, x.size / 4)) {
            val m = fitAR(x, p)
            val aic = x.size * ln(maxOf(m.sigma2, 1e-12)) + 2.0 * (p + 1)
            if (aic < bestAic) { bestAic = aic; best = p }
        }
        return best
    }

    // ─────────────────────── MA：创新算法 ───────────────────────

    /**
     * 创新算法（innovation algorithm）估计 MA(q) 参数。
     * 返回 (b1..bq, sigma2)。
     */
    private fun innovationMA(r: DoubleArray, q: Int): Pair<DoubleArray, Double> {
        val maxK = q
        val theta = Array(maxK + 1) { DoubleArray(maxK + 1) }   // theta[k][i]
        val v = DoubleArray(maxK + 1)
        v[0] = r[0]
        for (k in 1..maxK) {
            for (i in 1..k) {
                var s = r[k - i]
                for (j in 1 until i) s -= theta[i - 1][j - 1] * theta[k - 1][k - j - 1] * v[j - 1]
                if (abs(v[i - 1]) > 1e-12) theta[k][i - 1] = s / v[i - 1]
            }
            var s = r[0]
            for (j in 1..k) s -= theta[k][j - 1] * theta[k][j - 1] * v[j - 1]
            v[k] = maxOf(s, 1e-12)
        }
        val ma = DoubleArray(q) { theta[q][it] }
        return ma to v[q]
    }

    // ─────────────────────── ARMA：Hannan-Rissanen ───────────────────────

    private fun fitARMA(x: DoubleArray, p: Int, q: Int): Model {
        val n = x.size
        // 1. 高阶 AR(L) 预白化得到残差 ê
        val L = minOf(p + q + 5, n / 6)
        val (arL, _) = burg(x, L)
        val resid = DoubleArray(n)
        for (t in L until n) {
            var s = 0.0
            for (i in 1..L) s += arL[i - 1] * x[t - i]
            resid[t] = x[t] - s
        }
        // 2. 回归 x[t] = Σ ai x[t-i] + Σ bj resid[t-j]
        val K = maxOf(p, q)
        val rows = ArrayList<DoubleArray>()
        val yv = ArrayList<Double>()
        for (t in K until n) {
            val row = DoubleArray(p + q)
            for (i in 1..p) row[i - 1] = x[t - i]
            for (j in 1..q) row[p + j - 1] = resid[t - j]
            rows.add(row)
            yv.add(x[t])
        }
        val coef = lstsq(rows, yv)
        val ar = DoubleArray(p) { coef[it] }
        val ma = DoubleArray(q) { coef[p + it] }
        // σ²
        var sse = 0.0
        for (t in K until n) {
            var s = 0.0
            for (i in 1..p) s += ar[i - 1] * x[t - i]
            for (j in 1..q) s += ma[j - 1] * resid[t - j]
            val e = x[t] - s
            sse += e * e
        }
        val sigma2 = sse / maxOf(n - K - p - q, 1)
        val poles = runCatching { polyRoots(DoubleArray(p + 1) { if (it == p) 1.0 else -ar[p - 1 - it] }) }
            .getOrDefault(emptyList())
        // 零点 = z^q + b1 z^{q-1} + ... + bq = 0 的根
        val zeros = if (q > 0)
            runCatching { polyRoots(DoubleArray(q + 1) { if (it == q) 1.0 else ma[q - 1 - it] }) }
                .getOrDefault(emptyList())
        else emptyList()
        return Model(p, q, ar, ma, maxOf(sigma2, 1e-12), poles, zeros)
    }

    /** 最小二乘：A x = y（行优先） */
    private fun lstsq(A: List<DoubleArray>, y: List<Double>): DoubleArray {
        val m = A.size; val k = A[0].size
        if (m < k) return DoubleArray(k)
        // 正规方程 A^T A x = A^T y
        val ata = Array(k) { DoubleArray(k) }
        val aty = DoubleArray(k)
        for (i in 0 until m) {
            val row = A[i]
            for (a in 0 until k) {
                aty[a] += row[a] * y[i]
                for (b in 0 until k) ata[a][b] += row[a] * row[b]
            }
        }
        // 对角线加小阻尼防奇异
        for (i in 0 until k) ata[i][i] += 1e-9
        return solveSym(ata, aty)
    }

    private fun solveSym(A: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val a = A.map { it.copyOf() }.toTypedArray()
        val bb = b.copyOf()
        for (i in 0 until n) {
            var piv = i
            for (r in i + 1 until n) if (abs(a[r][i]) > abs(a[piv][i])) piv = r
            if (piv != i) { a[i] = a[piv].also { a[piv] = a[i] }; bb[i] = bb[piv].also { bb[piv] = bb[i] } }
            if (abs(a[i][i]) < 1e-15) continue
            for (r in i + 1 until n) {
                val f = a[r][i] / a[i][i]
                for (c in i until n) a[r][c] -= f * a[i][c]
                bb[r] -= f * bb[i]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = bb[i]
            for (j in i + 1 until n) s -= a[i][j] * x[j]
            x[i] = if (abs(a[i][i]) > 1e-15) s / a[i][i] else 0.0
        }
        return x
    }

    // ─────────────────────── Bairstow 多项式求根 ───────────────────────

    /**
     * Bairstow 法求实系数多项式根（升幂系数 c[0]+c[1]x+...+c[n]x^n=0）。
     * 返回所有根（实根 im=0，复根共轭成对）。
     */
    private fun polyRoots(coeff: DoubleArray): List<Complex> {
        // 归一化：首项系数=1
        val n = coeff.size - 1
        if (n <= 0) return emptyList()
        val lead = coeff[n]
        if (abs(lead) < 1e-15) {
            // 去掉末尾零
            var deg = n
            while (deg > 0 && abs(coeff[deg]) < 1e-15) deg--
            return polyRoots(coeff.copyOf(deg + 1))
        }
        val c = DoubleArray(n + 1) { coeff[it] / lead }
        val roots = ArrayList<Complex>()
        var deg = n
        var poly = c.copyOf(deg + 1)
        // 一次根直解
        while (deg >= 1) {
            if (deg == 1) {
                roots.add(Complex(-poly[0] / poly[1], 0.0))
                break
            }
            // Bairstow 找二次因子 x² + ux + v
            var u = poly[deg - 1] / poly[deg]
            var v = poly[deg - 2] / poly[deg]
            var ok = false
            repeat(80) {
                val b = DoubleArray(deg + 1)
                b[deg] = poly[deg]
                b[deg - 1] = poly[deg - 1] + u * b[deg]
                for (i in deg - 2 downTo 0) b[i] = poly[i] + u * b[i + 1] + v * b[i + 2]
                val cc = DoubleArray(deg + 1)
                cc[deg] = b[deg]
                cc[deg - 1] = b[deg - 1] + u * cc[deg]
                for (i in deg - 2 downTo 0) cc[i] = b[i] + u * cc[i + 1] + v * cc[i + 2]
                val c1 = cc[1]; val c2 = cc[2]; val c3 = if (deg >= 3) cc[3] else 0.0
                val det = c1 * c3 - c2 * c2
                if (abs(det) < 1e-15) return@repeat
                val du = (-b[0] * c3 + b[1] * c2) / det
                val dv = (b[0] * c2 - b[1] * c1) / det
                u += du; v += dv
                if (abs(b[0]) < 1e-9 && abs(b[1]) < 1e-9) ok = true
            }
            if (!ok) {
                // 退化：强制用 b 系数降阶
                val b = DoubleArray(deg + 1)
                b[deg] = poly[deg]
                b[deg - 1] = poly[deg - 1] + u * b[deg]
                for (i in deg - 2 downTo 0) b[i] = poly[i] + u * b[i + 1] + v * b[i + 2]
                poly = b.copyOfRange(2, deg + 1)
                deg -= 2
                continue
            }
            // 二次根
            val disc = u * u - 4 * v
            if (disc >= 0) {
                val sq = sqrt(disc)
                roots.add(Complex((-u + sq) / 2, 0.0))
                roots.add(Complex((-u - sq) / 2, 0.0))
            } else {
                val sq = sqrt(-disc)
                roots.add(Complex(-u / 2, sq / 2))
                roots.add(Complex(-u / 2, -sq / 2))
            }
            // 降阶：用商式 b（去掉 b0,b1）
            val b = DoubleArray(deg + 1)
            b[deg] = poly[deg]
            b[deg - 1] = poly[deg - 1] + u * b[deg]
            for (i in deg - 2 downTo 0) b[i] = poly[i] + u * b[i + 1] + v * b[i + 2]
            poly = b.copyOfRange(2, deg + 1)
            deg -= 2
        }
        return roots
    }

    // ─────────────────────── 预测 + 走查 + PSD ───────────────────────

    private fun buildPass(label: String, dates: List<String>, closes: DoubleArray, x: DoubleArray,
                          model: Model, horizon: Int, last: Double,
                          fcToPrice: (Double) -> Double,
                          fitToPrice: (Int, Double) -> Double): Pass {
        val n = x.size
        val ar = model.ar; val ma = model.ma; val p = model.p; val q = model.q

        /** h 步预测（从时刻 t，x[0..t] 已知）：z 空间 */
        fun predict(t: Int, h: Int): Double? {
            if (t < maxOf(p, q)) return null
            // 用 ARMA 递推；历史创新 ε 用最近 q 个 1 步残差近似
            val hist = DoubleArray(maxOf(h, q + 1))
            for (i in 0 until h) hist[i] = x[t - (h - 1 - i)] // 占位，下面用递推
            // 简化：1 步预测用完整 ARMA；多步递推时 ε[t+h-j] 对未来=0
            // 先算最近 q 个残差 ê[t-j] = x[t-j] - AR预测(x[..t-j-1])
            val eps = DoubleArray(q + 1)
            for (j in 1..q) {
                val tj = t - j
                if (tj < p) continue
                var s = 0.0
                for (i in 1..p) if (tj - i >= 0) s += ar[i - 1] * x[tj - i]
                eps[j] = x[tj] - s
            }
            // 递推预测
            val xx = DoubleArray(maxOf(t + h + 1, p + h + 1))
            for (i in 0..t) xx[i] = x[i]
            for (hh in 1..h) {
                var s = 0.0
                for (i in 1..p) s += ar[i - 1] * xx[t + hh - i]
                for (j in 1..q) {
                    val lag = hh - j
                    s += ma[j - 1] * if (lag <= 0) eps[-lag] else 0.0
                }
                xx[t + hh] = s
            }
            return xx[t + h]
        }

        // 走查：末 60 根
        val backN = 60
        val startT = n - backN
        val actual = ArrayList<Double>(backN)
        val fitted = ArrayList<Double?>(backN)
        val residual = ArrayList<Double>(backN)
        val errs = Array(horizon) { ArrayList<Pair<Double, Double>>() }
        val errsPct = Array(horizon) { ArrayList<Pair<Double, Double>>() }
        for (t in startT until n) {
            actual.add(closes[t])
            val p1 = predict(t - 1, 1)
            if (p1 == null) { fitted.add(null); residual.add(0.0) }
            else {
                val fp = fitToPrice(t, p1)
                fitted.add(fp)
                residual.add(closes[t] - fp)
            }
            for (h in 1..horizon) {
                if (t + h > n - 1) break
                predict(t, h)?.let { pr ->
                    errs[h - 1].add(pr to x[t + h])
                    if (closes[t] > 0) errsPct[h - 1].add(
                        ((fitToPrice(t, pr) - closes[t]) / closes[t]) to ((closes[t + h] - closes[t]) / closes[t]))
                }
            }
        }
        val corrByH = errs.map { pearson(it) }
        val corrPctByH = errsPct.map { pearson(it) }

        // 滚动方向命中率
        val evalN = 250
        val evalStart = maxOf(maxOf(p, q) + 1, n - evalN)
        val hitCnt = IntArray(horizon); val hitTot = IntArray(horizon)
        val rollDates = ArrayList<String>()
        for (t in evalStart until n) {
            rollDates.add(dates[t])
            for (h in 1..horizon) {
                if (t + h > n - 1) break
                val pr = predict(t, h) ?: continue
                // 价格方向口径：fitToPrice(t, pr) vs closes[t]
                val fp = fitToPrice(t, pr)
                val predDir = fp > closes[t]
                val actDir = closes[t + h] > closes[t]
                val flat = abs((closes[t + h] - closes[t]) / closes[t]) < 0.003
                if (!flat) { hitTot[h - 1]++; if (predDir == actDir) hitCnt[h - 1]++ }
            }
        }
        val rollHit = hitCnt.indices.map { if (hitTot[it] > 0) hitCnt[it] * 100.0 / hitTot[it] else 0.0 }

        // 未来 H 根预测
        val fcX = (1..horizon).mapNotNull { predict(n - 1, it) }
        val fcPrices = fcX.map { fcToPrice(it) }
        val fcPct = fcPrices.map { (it / last - 1) * 100.0 }

        // PSD：绕单位圆一圈 ω∈[0,π]
        val nFreq = 256
        val psdW = ArrayList<Double>(nFreq)
        val psd = ArrayList<Double>(nFreq)
        for (k in 0 until nFreq) {
            val w = PI * k / (nFreq - 1)
            // 分母 |1 - Σ ai e^{-ikw}|²
            var arRe = 1.0; var arIm = 0.0
            for (i in 1..p) { arRe -= ar[i - 1] * cos(i * w); arIm += ar[i - 1] * sin(i * w) }
            val den = arRe * arRe + arIm * arIm
            // 分子 |1 + Σ bj e^{-ijw}|²
            var maRe = 1.0; var maIm = 0.0
            for (j in 1..q) { maRe += ma[j - 1] * cos(j * w); maIm -= ma[j - 1] * sin(j * w) }
            val num = maRe * maRe + maIm * maIm
            psdW.add(w)
            psd.add(if (den > 1e-12) model.sigma2 * num / den else 0.0)
        }

        return Pass(label, model, dates.subList(startT, n), actual, fitted, fcPrices, fcPct,
            corrByH, corrPctByH, rollHit, rollDates, rollDates.size, psdW, psd, residual)
    }

    private fun pearson(pairs: List<Pair<Double, Double>>): Double {
        if (pairs.size < 2) return 0.0
        val mx = pairs.map { it.first }.average()
        val my = pairs.map { it.second }.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for ((x, y) in pairs) {
            val dx = x - mx; val dy = y - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        val d = sqrt(sxx * syy)
        return if (d > 1e-12) sxy / d else 0.0
    }
}
