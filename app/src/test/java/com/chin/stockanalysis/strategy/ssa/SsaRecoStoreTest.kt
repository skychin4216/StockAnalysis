package com.chin.stockanalysis.strategy.ssa

import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.Params
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.RecoIncremental
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 每日荐股快照存储层测试：
 * 1. 状态 JSON round-trip 逐位一致（含 bars/信号链/票性聚合/账户快照）
 * 2. restore→step 与不中断连续 step 逐位一致
 * 3. seedMissing 幂等续跑、数据不足跳过
 * 4. dailyUpdate 增量追加、重叠K去重、无新K幂等、label 迁移→参数变化→全史重 seed
 */
class SsaRecoStoreTest {

    private fun tempStore(): Pair<SsaRecoStore, File> {
        val dir = Files.createTempDirectory("ssa_reco_test").toFile()
        return SsaRecoStore(dir) to dir
    }

    /** 与 SsaRecoIncrementalTest 同款合成K线：漂移+双周期+随机噪声 */
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

    @Test
    fun stateRoundTrip_bitwiseEqual() {
        val (store, dir) = tempStore()
        try {
            val bars = synthBars(300, 42)
            val inc = RecoIncremental.seed(bars.take(260), Params(5, 10, 60))
            // 步进 2 根，让信号链/票性聚合/pendingZ 状态更丰满
            inc.step(bars.subList(260, 262))
            val state = inc.exportState()
            val label = SsaHybridEngine.classifyOnly(bars.take(262)).label
            store.saveState("RT", state, label)
            val loaded = store.loadState("RT")
            assertNotNull(loaded)
            assertEquals("JSON round-trip 应逐位一致", state, loaded)

            // restore→step 与连续 step 逐位一致
            val fromRestore = RecoIncremental.restore(loaded!!)
            fromRestore.step(bars.subList(262, 265))
            inc.step(bars.subList(262, 265))
            assertEquals("adviceHistory", inc.adviceHistoryCopy(), fromRestore.adviceHistoryCopy())
            val a = fromRestore.snapshot()
            val b = inc.snapshot()
            assertEquals("profile", b.profile, a.profile)
            assertEquals("recentAdvices", b.recentAdvices, a.recentAdvices)
            assertEquals("buyStat", b.buyStat, a.buyStat)
            assertEquals("sellStat", b.sellStat, a.sellStat)
            assertEquals("holdPosStat", b.holdPosStat, a.holdPosStat)
            assertEquals("holdEmptyStat", b.holdEmptyStat, a.holdEmptyStat)
            assertEquals("advice", b.advice, a.advice)
            assertEquals("close", b.close, a.close, 0.0)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun seedMissing_idempotentAndSkipsInsufficient() {
        val (store, dir) = tempStore()
        try {
            val good = synthBars(300, 42)
            val barsByCode: Map<String, List<Bar>?> =
                mapOf("A" to good, "B" to good.take(50), "C" to null)
            val first = store.seedMissing(listOf("A", "B", "C"), barsProvider = { c -> barsByCode[c] })
            assertEquals("仅 A 完成构建", 1, first)
            val idx = store.loadIndex()
            assertTrue("A 已入索引", idx.containsKey("A"))
            assertEquals(300, idx["A"]!!.barCount)
            assertEquals(good.last().date, idx["A"]!!.lastDate)
            assertFalse("B 数据不足不入索引", idx.containsKey("B"))
            // 幂等：再跑一遍跳过已有
            val second = store.seedMissing(listOf("A", "B", "C"), barsProvider = { c -> barsByCode[c] })
            assertEquals("第二次应跳过已有状态", 0, second)
            assertEquals("seedMissing 不写卡片缓存", 0, store.loadLatestCards().size)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun dailyUpdate_stepAndMigration() {
        val (store, dir) = tempStore()
        try {
            val bars = synthBars(300, 42)
            val n0 = 290
            // 预探测 step 后票性 → 其推荐参数 → 强制存一个不同参数档以触发迁移
            val probe = RecoIncremental.seed(bars.take(n0), Params())
            val newLabel = probe.step(bars.subList(n0, bars.size)).profile.label
            val rec = SsaHybridEngine.recommendedParams(newLabel)
            val forced = if (rec == Params(5, 10, 60)) Params(30, 60, 60) else Params(5, 10, 60)
            assertTrue("强制参数须与推荐参数不同", forced != rec)

            val seeded = RecoIncremental.seed(bars.take(n0), forced)
            store.saveState("M", seeded.exportState(), SsaHybridEngine.classifyOnly(bars.take(n0)).label)
            assertEquals(n0, store.loadIndex()["M"]!!.barCount)

            // 每日更新：10 根新K → step → label 迁移 → 全史重 seed
            val cards = store.dailyUpdate(mapOf("M" to bars))
            val entry = store.loadIndex()["M"]!!
            assertEquals("迁移后根数=全史", bars.size, entry.barCount)
            assertEquals("迁移后参数=推荐参数", rec, store.loadState("M")!!.params)
            assertEquals(bars.last().date, entry.lastDate)
            val card = cards.firstOrNull { it.code == "M" }
            assertNotNull("更新后应有卡片", card)
            assertEquals(bars.last().date, card!!.snap.date)
            // 迁移重 seed 与直接全量 seed 结果一致
            val direct = RecoIncremental.seed(bars, rec).snapshot()
            assertEquals("迁移重seed后建议史与直接seed一致", direct.recentAdvices, card.snap.recentAdvices)
            assertEquals(direct.profile, card.snap.profile)
            assertEquals(direct.advice, card.snap.advice)

            // 幂等：同样的K再喂一遍 → 无新K → 状态与卡片不变
            val cards2 = store.dailyUpdate(mapOf("M" to bars))
            assertEquals(bars.size, store.loadIndex()["M"]!!.barCount)
            assertEquals(1, cards2.size)
            assertEquals(card.snap.date, cards2.first { it.code == "M" }.snap.date)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun dailyUpdate_dedupesOverlapAndContinuesAcrossRestart() {
        val (store, dir) = tempStore()
        try {
            val bars = synthBars(320, 99)
            store.seedMissing(listOf("K"), barsProvider = { bars.take(300) })
            // 模拟 App 重启：新 store 实例（同目录），分两段增量，第二段包含第一段全部重叠K
            val store2 = SsaRecoStore(dir)
            store2.dailyUpdate(mapOf("K" to bars.take(310)))
            assertEquals(310, store2.loadIndex()["K"]!!.barCount)
            val cards = store2.dailyUpdate(mapOf("K" to bars))   // 前 310 根重复，仅 10 根新
            assertEquals("重叠K去重后=全史", 320, store2.loadIndex()["K"]!!.barCount)
            assertEquals(bars.last().date, cards.first { it.code == "K" }.snap.date)

            // 核心等价：跨重启分段增量到达的建议史 == 不间断全量 run 的建议史（逐位）
            val st = store2.loadState("K")!!
            val full = SsaHybridEngine.run(bars, st.params)
            assertEquals(full.advice.toList(), st.adviceHistory)
        } finally { dir.deleteRecursively() }
    }
}
