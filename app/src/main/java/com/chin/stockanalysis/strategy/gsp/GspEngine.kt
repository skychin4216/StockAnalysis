package com.chin.stockanalysis.strategy.gsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * ## GSP 图信号处理引擎（纯 Kotlin，无 Android 依赖）
 *
 * 把板块成分股建模成图：
 *  - 节点 = 板块等权/市值加权综合指数（节点 0）+ 各成分股
 *  - 边权 = 去 β 残差收益率的滚动 Pearson 相关（截断负相关），w_ij = max(corr, 0)
 *  - 图拉普拉斯 L = I - D^{-1/2} W D^{-1/2}
 *  - 切比雪夫多项式逼近 g(L)（避开 O(N³) 特征分解，阶数 4~6 足够）
 *  - 热核扩散 f(t) = e^{-tL}·e_s，从锚节点/龙头打脉冲，看能量如何外扩
 *
 * 口径：对数收益率；去 β 用沪深300 线性回归残差；全部为确定性计算。
 */
object GspEngine {

    /** 扩散类型（方案二） */
    enum class DiffusionType(val label: String) {
        BENIGN("良性扩散"),     // 能量平滑外扩，后续节点依次激活
        MALIGNANT("恶性孤龙"),  // 能量卡在龙头自身，其余无响应
        CHAOTIC("混乱分歧")     // 能量此起彼伏、无顺序
    }

    // ─────────────────────── 预处理：收益率 + 去β残差 ───────────────────────

    fun logReturns(close: DoubleArray): DoubleArray {
        if (close.size < 2) return DoubleArray(0)
        val r = DoubleArray(close.size - 1)
        for (i in 1 until close.size) {
            val a = close[i - 1].coerceAtLeast(1e-9)
            val b = close[i].coerceAtLeast(1e-9)
            r[i - 1] = ln(b / a)
        }
        return r
    }

    /** 残差 = r_stock - (α + β·r_market)，剥离大盘 β 后用残差算联动 */
    fun residualReturns(stock: DoubleArray, market: DoubleArray): DoubleArray {
        val n = min(stock.size, market.size)
        if (n < 5) return DoubleArray(n)
        val so = stock.size - n
        val mo = market.size - n
        var ms = 0.0; var mm = 0.0
        for (i in 0 until n) { ms += stock[so + i]; mm += market[mo + i] }
        ms /= n; mm /= n
        var cov = 0.0; var varM = 0.0
        for (i in 0 until n) {
            val ds = stock[so + i] - ms
            val dm = market[mo + i] - mm
            cov += ds * dm
            varM += dm * dm
        }
        val beta = if (varM > 1e-12) cov / varM else 1.0
        val alpha = ms - beta * mm
        return DoubleArray(n) { i -> stock[so + i] - (alpha + beta * market[mo + i]) }
    }

    /** 窗口末尾 Pearson 相关（只用最近 window 根） */
    fun pearson(a: DoubleArray, b: DoubleArray, window: Int): Double {
        val n = min(a.size, b.size)
        val w = min(window, n)
        if (w < 5) return 0.0
        val ao = n - w; val bo = b.size - w
        var ma = 0.0; var mb = 0.0
        for (i in 0 until w) { ma += a[ao + i]; mb += b[bo + i] }
        ma /= w; mb /= w
        var cov = 0.0; var va = 0.0; var vb = 0.0
        for (i in 0 until w) {
            val da = a[ao + i] - ma
            val db = b[bo + i] - mb
            cov += da * db; va += da * da; vb += db * db
        }
        if (va < 1e-12 || vb < 1e-12) return 0.0
        return (cov / sqrt(va * vb)).coerceIn(-1.0, 1.0)
    }

    // ─────────────────────── 图：邻接矩阵 + 归一化拉普拉斯 ───────────────────────

    fun buildAdjacency(residuals: Array<DoubleArray>, window: Int = 40, minCorr: Double = 0.0): Array<DoubleArray> {
        val n = residuals.size
        val w = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val c = pearson(residuals[i], residuals[j], window)
            val e = if (c > minCorr) c else 0.0
            w[i][j] = e; w[j][i] = e
        }
        return w
    }

    fun degrees(w: Array<DoubleArray>): DoubleArray =
        DoubleArray(w.size) { i -> w[i].sum() }

    /** L = I - D^{-1/2} W D^{-1/2} */
    fun normalizedLaplacian(w: Array<DoubleArray>): Array<DoubleArray> {
        val n = w.size
        val deg = degrees(w)
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                l[i][j] = if (i == j) 1.0
                else if (deg[i] > 1e-12 && deg[j] > 1e-12) -w[i][j] / sqrt(deg[i] * deg[j])
                else 0.0
            }
        }
        return l
    }

    // ─────────────────────── 切比雪夫图滤波（O(N²·order)） ───────────────────────

    /** 幂迭代近似最大特征值，避免特征分解 */
    fun lambdaMax(l: Array<DoubleArray>, iterations: Int = 60): Double {
        val n = l.size
        if (n == 0) return 1.0
        var v = DoubleArray(n) { 1.0 / sqrt(n.toDouble()) }
        var lambda = 1.0
        repeat(iterations) {
            val w = matVec(l, v)
            lambda = (0 until n).sumOf { i -> v[i] * w[i] }
            var norm = 0.0
            for (i in 0 until n) norm += w[i] * w[i]
            norm = sqrt(norm)
            if (norm < 1e-12) return@repeat
            v = DoubleArray(n) { w[it] / norm }
        }
        return abs(lambda).coerceAtLeast(1e-9)
    }

    /**
     * 用切比雪夫多项式逼近 g(L)·x。
     * 先把 L 线性映射到 [-1,1]：A = (2/λmax)L − I，再递推 T_{k}=2A·T_{k-1}−T_{k-2}。
     */
    fun chebApply(l: Array<DoubleArray>, x: DoubleArray, order: Int, g: (Double) -> Double): DoubleArray {
        val n = l.size
        if (n == 0) return DoubleArray(0)
        val lmax = lambdaMax(l)
        val coef = chebCoeffs(order, lmax, g)
        val t0 = x.copyOf()
        var tPrev = DoubleArray(n)          // T_{-1} = 0
        var tCur = t0                       // T_0 = x
        val out = DoubleArray(n) { coef[0] / 2.0 * x[it] }
        for (k in 1..order) {
            val lx = matVec(l, tCur)
            val next = DoubleArray(n)
            if (k == 1) {
                for (i in 0 until n) next[i] = 2.0 / lmax * lx[i] - tCur[i]
            } else {
                for (i in 0 until n) next[i] = 2.0 * (2.0 / lmax * lx[i] - tCur[i]) - tPrev[i]
            }
            for (i in 0 until n) out[i] += coef[k] * next[i]
            tPrev = tCur; tCur = next
        }
        return out
    }

    /** c_k = (2/m)·Σ g(λ(θ_j))·cos(kθ_j)，θ_j=π(j+0.5)/m（切比雪夫-高斯节点，保证正交） */
    private fun chebCoeffs(order: Int, lmax: Double, g: (Double) -> Double): DoubleArray {
        val m = 256
        val c = DoubleArray(order + 1)
        for (k in 0..order) {
            var s = 0.0
            for (j in 0 until m) {
                val th = PI * (j + 0.5) / m
                val lam = lmax / 2.0 * (cos(th) + 1.0)
                s += g(lam) * cos(k * th)
            }
            c[k] = 2.0 * s / m
        }
        return c
    }

    // ─────────────────────── 热核扩散 ───────────────────────

    /** f(t) = e^{-tL}·e_s，返回每个节点接收到的能量 */
    fun diffuse(l: Array<DoubleArray>, s: Int, t: Double, order: Int = 6): DoubleArray {
        val n = l.size
        val delta = DoubleArray(n) { if (it == s) 1.0 else 0.0 }
        return chebApply(l, delta, order) { lambda -> exp(-t * lambda) }
    }

    /**
     * 扩散衰减时间常数 τ：对多个 t 算能量 E(t)=‖f(t)‖²，lnE 对 t 回归，
     * τ = -1/斜率。τ 越大 → 扰动传得远、衰减慢 → 板块共振强。
     */
    fun decayTau(l: Array<DoubleArray>, s: Int, order: Int = 6): Double {
        val ts = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
        val ys = DoubleArray(ts.size)
        for (i in ts.indices) {
            val f = diffuse(l, s, ts[i], order)
            val e = f.sumOf { it * it }.coerceAtLeast(1e-12)
            ys[i] = ln(e)
        }
        val mt = ts.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in ts.indices) {
            val dx = ts[i] - mt
            sxy += dx * (ys[i] - my); sxx += dx * dx
        }
        val slope = if (sxx > 1e-12) sxy / sxx else -1.0
        return if (slope < -1e-4) (-1.0 / slope).coerceIn(0.01, 99.0) else 99.0
    }

    // ─────────────────────── 信号分离：共模 / 个股专属 ───────────────────────

    fun split(l: Array<DoubleArray>, x: DoubleArray, order: Int = 5): Pair<DoubleArray, DoubleArray> {
        val common = chebApply(l, x, order) { lambda -> exp(-lambda) }
        val specific = DoubleArray(x.size) { x[it] - common[it] }
        return common to specific
    }

    // ─────────────────────── 龙头识别 + 扩散分类（方案二） ───────────────────────

    /** 龙头得分 = 0.7·度中心性(归一) + 0.3·底部拐点命中 */
    fun leaderScores(deg: DoubleArray, turning: BooleanArray): DoubleArray {
        val mx = deg.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
        return DoubleArray(deg.size) { i ->
            0.7 * (deg[i] / mx) + if (turning[i]) 0.3 else 0.0
        }
    }

    fun classifyDiffusion(f: DoubleArray, leader: Int): DiffusionType {
        val total = f.sumOf { abs(it) }.coerceAtLeast(1e-9)
        val leadShare = abs(f.getOrElse(leader) { 0.0 }) / total
        if (leadShare > 0.45) return DiffusionType.MALIGNANT
        val others = f.filterIndexed { i, _ -> i != leader }.map { abs(it) }
        val mx = others.maxOrNull() ?: 0.0
        if (mx < 1e-12) return DiffusionType.MALIGNANT
        val active = others.count { it > 0.2 * mx }
        val need = (others.size * 0.35).toInt().coerceAtLeast(2)
        return if (active >= need) DiffusionType.BENIGN else DiffusionType.CHAOTIC
    }

    // ─────────────────────── 基础矩阵运算 ───────────────────────

    private fun matVec(a: Array<DoubleArray>, x: DoubleArray): DoubleArray {
        val n = a.size
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            val row = a[i]
            for (j in 0 until n) s += row[j] * x[j]
            out[i] = s
        }
        return out
    }
}
