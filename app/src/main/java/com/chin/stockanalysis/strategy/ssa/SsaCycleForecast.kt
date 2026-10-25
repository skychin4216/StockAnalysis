package com.chin.stockanalysis.strategy.ssa

import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Bar
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ## 拟合周期函数 · 未来一天走势与目标价位预测（纯展示，不进信号/损益）
 *
 * 定位：**只做展示外推 + 命中率回测**，绝不参与选股/交易决策、不影响回测损益。
 * 每日随滚动窗口更新：每次只用「截止末根 K」的固定长度窗口重新拟合，无未来函数。
 *
 * 方法（经典「去趋势 + 单谐波最小二乘」）：
 *  1. 取末 window 根收盘价，一次线性最小二乘去趋势 → 残差 r[i]；
 *  2. 对 r 做自相关，取峰值滞后作为主周期 T（无显著周期则退化为纯线性外推）；
 *  3. 以 cos/sin(2πi/T) 为基，解 2×2 正规方程得谐波系数 (A,B)；
 *  4. 未来 h 根外推：pred = 趋势线延拓 + A·cos + B·sin；
 *     扇形 = pred ± σ·√step（σ=残差标准差，随步长加宽）。
 *
 * horizon 默认 4 根 1H K ≈ 1 个交易日（A 股 1H 序列：10:30/11:30/14:00/15:00）。
 */
object SsaCycleForecast {

    /** 默认拟合窗口（根） */
    const val DEFAULT_WINDOW = 120
    /** 默认预测步长（根 1H ≈ 一个交易日） */
    const val DEFAULT_HORIZON = 4
    /** 方向判定阈值（目标价相对末收盘变动绝对值超过该比例才算「上行/下行」） */
    private const val DIR_EPS = 0.0015

    data class Forecast(
        val ok: Boolean,
        val note: String,
        val window: Int,
        val period: Double,          // 主周期（根），<=0 表示无显著周期（退化为线性外推）
        val lastClose: Double,
        val nextLabel: String,       // 下一根 K 的时间标签
        val targetLabel: String,     // 目标K（末根 + horizon）的时间标签
        val predNext: Double,        // 下一根预测收盘价
        val target: Double,          // 目标价位 = 第 horizon 根预测价
        val up: Boolean,             // 目标 > 末收盘（含阈值）
        val horizon: Int,
        val predMid: DoubleArray,    // 未来 horizon 根预测中枢
        val predUpper: DoubleArray,  // +σ 扇形上沿
        val predLower: DoubleArray,  // -σ 扇形下沿
        val sigma: Double            // 残差标准差（价格尺度）
    )

    data class Eval(
        val name: String,
        val samples: Int,
        val hitDirPct: Double,       // 下一根方向命中率
        val mae: Double,             // 平均绝对误差（价格）
        val maePct: Double           // 平均绝对误差（%）
    )

    data class EvalSplit(val full: Eval, val train: Eval, val test: Eval)

    // ── 拟合基础函数 ──

    /** 线性最小二乘（x=0..n-1）→ (斜率, 截距)。与 np.polyfit(deg=1) 同解。 */
    private fun linFit(vals: DoubleArray): DoubleArray {
        val n = vals.size
        if (n < 2) return doubleArrayOf(0.0, vals.firstOrNull() ?: 0.0)
        val xm = (n - 1) / 2.0
        val ym = vals.sum() / n
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (i - xm) * (vals[i] - ym); den += (i - xm) * (i - xm) }
        val slope = if (den != 0.0) num / den else 0.0
        val intercept = ym - slope * xm
        return doubleArrayOf(slope, intercept)
    }

    /**
     * 自相关求主周期（根，含小数）：
     *  1) 取全局峰值滞后 → 抛物线插值细化到小数；
     *  2) 若半周期处自相关仍接近峰值，则折半（消除整数倍谐波，取基频）。
     * 无显著周期返回 0。
     */
    private fun dominantPeriod(res: DoubleArray): Double {
        val n = res.size
        if (n < 16) return 0.0
        val mean = res.sum() / n
        val x = DoubleArray(n) { res[it] - mean }
        var var0 = 0.0
        for (v in x) var0 += v * v
        if (var0 <= 1e-12) return 0.0
        val minLag = 4
        val maxLag = n / 2
        val ac = DoubleArray(maxLag + 1)
        var peak = 0.0; var peakLag = 0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in 0 until n - lag) s += x[i] * x[i + lag]
            s /= (n - lag)
            ac[lag] = s
            if (s > peak) { peak = s; peakLag = lag }
        }
        // 峰值不够显著（< 方差的 8%）视为无周期
        if (peakLag <= 0 || peak < 0.08 * (var0 / n)) return 0.0
        var t = peakLag.toDouble()
        if (peakLag in (minLag + 1) until maxLag) {
            val y1 = ac[peakLag - 1]; val y2 = ac[peakLag]; val y3 = ac[peakLag + 1]
            val den = y1 - 2.0 * y2 + y3
            if (abs(den) > 1e-12) {
                val d = 0.5 * (y1 - y3) / den
                if (abs(d) <= 1.0) t = peakLag + d
            }
        }
        // 折半消谐：半周期自相关仍 ≈ 峰值 → 真周期为半
        while (true) {
            val half = t / 2.0
            if (half < minLag) break
            val lag = Math.round(half).toInt()
            if (lag in minLag..maxLag && ac[lag] >= 0.9 * peak) t = half else break
        }
        return t
    }

    /** 单谐波最小二乘：解 r[i] ≈ A·cos(2πi/T) + B·sin(2πi/T) 的正规方程。 */
    private fun harmonicFit(res: DoubleArray, period: Double): DoubleArray {
        val n = res.size
        if (period <= 0.0 || n < 4) return doubleArrayOf(0.0, 0.0)
        var scc = 0.0; var sss = 0.0; var scs = 0.0; var rc = 0.0; var rs = 0.0
        for (i in 0 until n) {
            val a = 2.0 * PI * i / period
            val c = cos(a); val s = sin(a)
            scc += c * c; sss += s * s; scs += c * s
            rc += res[i] * c; rs += res[i] * s
        }
        val det = scc * sss - scs * scs
        if (abs(det) < 1e-12) return doubleArrayOf(0.0, 0.0)
        val a = (rc * sss - rs * scs) / det
        val b = (rs * scc - rc * scs) / det
        return doubleArrayOf(a, b)
    }

    private fun stdOf(res: DoubleArray): Double {
        val n = res.size
        if (n < 2) return 0.0
        val m = res.sum() / n
        var s = 0.0
        for (v in res) s += (v - m) * (v - m)
        return sqrt(s / n)
    }

    // ── 标签工具 ──

    private fun parseLabel(b: Bar): LocalDateTime? = try {
        val d = b.date
        val t = if (b.time.isNotEmpty()) b.time else "15:00"
        LocalDateTime.of(
            d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt(),
            t.substring(0, 2).toInt(), t.substring(3, 5).toInt()
        )
    } catch (e: Exception) { null }

    private fun fmt(dt: LocalDateTime, isHour: Boolean): String {
        fun two(v: Int) = if (v < 10) "0$v" else "$v"
        return if (isHour) "${two(dt.monthValue)}-${two(dt.dayOfMonth)} ${two(dt.hour)}:${two(dt.minute)}"
        else "${two(dt.monthValue)}-${two(dt.dayOfMonth)}"
    }

    private fun labelAt(last: Bar, step: Int): String {
        val isHour = last.time.isNotEmpty()
        val dt = parseLabel(last) ?: return last.date + (if (isHour) " " + last.time else "")
        val next = if (isHour) dt.plusHours(step.toLong()) else dt.plusDays(step.toLong())
        return fmt(next, isHour)
    }

    // ── 主预测 ──

    /**
     * 对 [bars] 末根做一次拟合外推。[bars] 必须按时间升序且只含已收盘 K。
     * ok=false 时仅 note 有效（数据不足）。
     */
    fun forecast(bars: List<Bar>, horizon: Int = DEFAULT_HORIZON, window: Int = DEFAULT_WINDOW): Forecast {
        val n = bars.size
        val h = maxOf(1, horizon)
        if (n < 40) return Forecast(false, "数据不足（<$n<40 根）", 0, 0.0, 0.0, "", "", 0.0, 0.0, false, h,
            DoubleArray(0), DoubleArray(0), DoubleArray(0), 0.0)
        val w = min(window, n)
        val sub = DoubleArray(w) { bars[n - w + it].close }
        val lastClose = sub[w - 1]
        val lf = linFit(sub)
        val slope = lf[0]; val intercept = lf[1]
        val res = DoubleArray(w) { sub[it] - (intercept + slope * it) }
        val period = dominantPeriod(res)
        val ab = harmonicFit(res, period)
        val a = ab[0]; val b = ab[1]
        val sigma = stdOf(res)

        val mid = DoubleArray(h); val upA = DoubleArray(h); val loA = DoubleArray(h)
        for (s in 1..h) {
            val x = (w - 1 + s).toDouble()
            val trendV = intercept + slope * x
            val cycV = if (period > 0.0) a * cos(2.0 * PI * x / period) + b * sin(2.0 * PI * x / period) else 0.0
            val pred = trendV + cycV
            val wd = sigma * sqrt(s.toDouble())
            mid[s - 1] = pred; upA[s - 1] = pred + wd; loA[s - 1] = pred - wd
        }
        val predNext = mid[0]
        val target = mid[h - 1]
        val up = target - lastClose > DIR_EPS * lastClose
        val note = if (period > 0.0)
            "单谐波拟合(周期≈%.0f根) + 线性趋势；残差σ=%.3f".format(period, sigma)
        else
            "无显著周期，退化为线性趋势外推；残差σ=%.3f".format(sigma)
        return Forecast(true, note, w, period, lastClose,
            labelAt(bars[n - 1], 1), labelAt(bars[n - 1], h),
            predNext, target, up, h, mid, upA, loA, sigma)
    }

    // ── 命中率回测（滚动、逐根外推，无未来函数）──

    private fun evalRange(bars: List<Bar>, from: Int, to: Int, horizon: Int, window: Int, name: String): Eval {
        val n = bars.size
        var hit = 0; var cnt = 0; var sumAbs = 0.0; var sumAbsPct = 0.0
        for (t in from..to) {
            val f = forecast(bars.subList(0, t + 1), horizon, window)
            if (!f.ok) continue
            val actualNext = bars[t + 1].close
            val predDir = if (f.up) 1 else -1
            val actDir = if (actualNext - bars[t].close > 0) 1 else -1
            if (predDir == actDir) hit++
            val err = abs(f.target - actualNext)
            sumAbs += err
            sumAbsPct += if (actualNext > 0) err / actualNext * 100.0 else 0.0
            cnt++
            if (t + 1 >= n - 1) break
        }
        return Eval(name, cnt,
            if (cnt > 0) hit * 100.0 / cnt else 0.0,
            if (cnt > 0) sumAbs / cnt else 0.0,
            if (cnt > 0) sumAbsPct / cnt else 0.0)
    }

    /** 全样本 + 训练(前 trainRatio) + 样本外 三段命中率回测。 */
    fun evaluateSplit(
        bars: List<Bar>, horizon: Int = DEFAULT_HORIZON, window: Int = DEFAULT_WINDOW,
        trainRatio: Double = 0.70
    ): EvalSplit {
        val n = bars.size
        val start = maxOf(40, window)
        val last = n - 2
        val full = evalRange(bars, start, last, horizon, window, "全样本")
        val splitIdx = (n * trainRatio).toInt().coerceIn(start, last)
        val train = evalRange(bars, start, splitIdx, horizon, window, "训练集")
        val test = evalRange(bars, maxOf(start, splitIdx + 1), last, horizon, window, "样本外")
        return EvalSplit(full, train, test)
    }
}
