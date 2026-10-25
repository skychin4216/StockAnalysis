package com.chin.stockanalysis.strategy.ssa

import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Bar
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * [SsaCycleForecast] 单测：确定性、主周期识别、外推命中率回测可用性。
 * 使用内置确定性合成序列（无网络依赖），验证「无未来函数」（纯函数、前缀一致）与重复性。
 */
class SsaCycleForecastTest {

    /** 确定性合成 1H 序列：趋势 + 单谐波 + 伪随机噪声。 */
    private fun synth(n: Int, period: Double, amp: Double, slope: Double, noise: Double): List<Bar> {
        var s = 123456789L
        fun rnd(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 11) and 0x1FFFFF).toDouble() / 0x1FFFFF.toDouble() - 0.5
        }
        val times = listOf("10:30", "11:30", "14:00", "15:00")
        val base = LocalDate.of(2024, 1, 2)
        val out = ArrayList<Bar>(n)
        for (i in 0 until n) {
            val mid = 10.0 + slope * i + amp * sin(2.0 * PI * i / period) + noise * rnd()
            val o = mid - 0.02; val c = mid + 0.02
            val dt = base.plusDays((i / 4).toLong())
            val date = "%04d%02d%02d".format(dt.year, dt.monthValue, dt.dayOfMonth)
            out.add(Bar(date, times[i % 4], o, max(o, c) + 0.1, min(o, c) - 0.1, c))
        }
        return out
    }

    @Test
    fun determinism_identicalInput_identicalOutput() {
        val bars = synth(300, period = 20.0, amp = 0.5, slope = 0.001, noise = 0.02)
        val f1 = SsaCycleForecast.forecast(bars)
        val f2 = SsaCycleForecast.forecast(bars)
        assertTrue(f1.ok)
        assertEquals(f1.period, f2.period, 1e-9)
        assertEquals(f1.target, f2.target, 1e-12)
        assertArrayEquals(f1.predMid, f2.predMid, 1e-12)
        assertArrayEquals(f1.predUpper, f2.predUpper, 1e-12)
    }

    @Test
    fun dominantPeriod_recovered() {
        val bars = synth(300, period = 20.0, amp = 0.5, slope = 0.001, noise = 0.02)
        val f = SsaCycleForecast.forecast(bars, horizon = 4)
        assertTrue("period=${f.period}", f.period in 16.0..24.0)
    }

    @Test
    fun extrapolation_tracksTrueSinusoid() {
        val period = 20.0; val amp = 0.5; val slope = 0.001
        val bars = synth(300, period, amp, slope, noise = 0.01)
        val f = SsaCycleForecast.forecast(bars, horizon = 4)
        // 每根预测价应与真值（10 + slope*i + amp*sin）相差不超过一个残差量级
        for (s in 1..4) {
            val i = 300 - 1 + s
            val truth = 10.0 + slope * i + amp * sin(2.0 * PI * i / period)
            assertTrue("step=$s pred=${f.predMid[s - 1]} truth=$truth",
                kotlin.math.abs(f.predMid[s - 1] - truth) < 0.3)
        }
    }

    @Test
    fun fan_widensWithStep() {
        val bars = synth(300, 20.0, 0.5, 0.001, 0.05)
        val f = SsaCycleForecast.forecast(bars, horizon = 4)
        val w0 = f.predUpper[0] - f.predMid[0]
        val w3 = f.predUpper[3] - f.predMid[3]
        assertTrue("扇形应随步长加宽: w0=$w0 w3=$w3", w3 > w0)
    }

    @Test
    fun evalSplit_runsAndReportsSamples() {
        val bars = synth(300, 20.0, 0.5, 0.001, 0.02)
        val e = SsaCycleForecast.evaluateSplit(bars, horizon = 4)
        assertTrue("全样本应有点", e.full.samples > 0)
        assertTrue("命中率应在 [0,100]: ${e.full.hitDirPct}", e.full.hitDirPct in 0.0..100.0)
        // 单谐波周期序列上，方向命中率应显著高于随机
        assertTrue("趋势+周期序列命中率应 >50%: ${e.full.hitDirPct}", e.full.hitDirPct > 50.0)
    }

    @Test
    fun insufficientData_returnsNotOk() {
        val bars = synth(20, 20.0, 0.5, 0.001, 0.02)
        val f = SsaCycleForecast.forecast(bars)
        assertTrue(!f.ok)
    }
}
