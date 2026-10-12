package com.chin.stockanalysis.strategy.ssa

import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.Params
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.RecoIncremental
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 每日荐股增量引擎等价性守护：
 * seed(全量一次) + step(每日一根) 的结果必须与对同一根K的全量 run() **逐位一致**。
 * 原理：SSA 逐K只用 [0..t] 历史（无未来函数）⇒ 新增K后历史分解值与建议序列永不漂移。
 */
class SsaRecoIncrementalTest {

    /** 合成K线：漂移 + 双周期 + 随机噪声，保证出现足够多的金叉/死叉/谷底/峰顶信号 */
    private fun synthBars(n: Int, seed: Long): List<Bar> {
        val rnd = Random(seed)
        var px = 20.0
        val out = ArrayList<Bar>(n)
        for (i in 0 until n) {
            val cycle = 0.02 * sin(i / 9.0) + 0.008 * sin(i / 23.0)
            px *= (1 + 0.0004 + cycle + rnd.nextDouble(-0.028, 0.028))
            val o = px * (1 + rnd.nextDouble(-0.006, 0.006))
            val h = max(o, px) * (1 + rnd.nextDouble(0.0, 0.012))
            val l = min(o, px) * (1 - rnd.nextDouble(0.0, 0.012))
            out.add(Bar(String.format("D%05d", i), o, h, l, px))
        }
        return out
    }

    private fun assertSameResult(
        full: SsaHybridEngine.Result,
        snap: SsaHybridEngine.RecoSnapshot,
        tag: String
    ) {
        assertEquals("$tag latestAdvice", full.latestAdvice, snap.advice)
        assertEquals("$tag latestDate", full.latestDate, snap.date)
        assertEquals("$tag latestClose", full.latestClose, snap.close, 0.0)
        assertEquals("$tag profile", full.profile, snap.profile)
        assertEquals("$tag recentAdvices", full.recentAdvices, snap.recentAdvices)
        assertEquals("$tag buyStat", full.buyStat, snap.buyStat)
        assertEquals("$tag sellStat", full.sellStat, snap.sellStat)
        assertEquals("$tag holdPosStat", full.holdPosStat, snap.holdPosStat)
        assertEquals("$tag holdEmptyStat", full.holdEmptyStat, snap.holdEmptyStat)
    }

    @Test
    fun seedOnlyEqualsFullRun() {
        val bars = synthBars(300, 42)
        val params = Params(5, 10, 60)
        val snap = RecoIncremental.seed(bars, params).snapshot()
        val full = SsaHybridEngine.run(bars, params)
        assertSameResult(full, snap, "seedOnly")
    }

    @Test
    fun stepOneByOneEqualsFullRun_allParamSets() {
        val bars = synthBars(320, 7)
        val seedN = 260
        val paramSets = listOf(
            Params(),
            Params(5, 10, 60),
            Params(30, 60, 60),
            Params(30, 60, 60, baseFraction = 0.3)
        )
        for (params in paramSets) {
            val inc = RecoIncremental.seed(bars.take(seedN), params)
            // 中途校验：增量状态必须等于对前 290 根的全新全量 run（历史永不漂移）
            for (i in seedN until 290) inc.step(listOf(bars[i]))
            val runMid = SsaHybridEngine.run(bars.take(290), params)
            assertEquals("$params mid adviceHistory", runMid.advice.toList(), inc.adviceHistoryCopy())
            assertSameResult(runMid, inc.snapshot(), "$params mid")
            // 逐根步进到终点
            for (i in 290 until bars.size) inc.step(listOf(bars[i]))
            val full = SsaHybridEngine.run(bars, params)
            assertEquals("$params final adviceHistory", full.advice.toList(), inc.adviceHistoryCopy())
            assertSameResult(full, inc.snapshot(), "$params final")
        }
    }

    @Test
    fun chunkStepEqualsFullRun() {
        val bars = synthBars(320, 99)
        val params = Params(30, 60, 60)
        val inc = RecoIncremental.seed(bars.take(260), params)
        inc.step(bars.subList(260, bars.size))
        val full = SsaHybridEngine.run(bars, params)
        assertEquals("chunk adviceHistory", full.advice.toList(), inc.adviceHistoryCopy())
        assertSameResult(full, inc.snapshot(), "chunk")
    }

    @Test
    fun stepRejectsNonIncreasingDates() {
        val bars = synthBars(120, 3)
        val inc = RecoIncremental.seed(bars, Params())
        try {
            inc.step(listOf(Bar(bars.last().date, 10.0, 11.0, 9.0, 10.5)))
            fail("同日期新K应被拒绝")
        } catch (_: IllegalArgumentException) {
        }
    }
}
