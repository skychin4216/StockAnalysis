package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.CandlePatternDetector
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.topology.core.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ## K 线口诀 · 加减分 / 否决节点（2026-09-12 新增）
 *
 * 与 Python 端 `usecase_pipeline.kline_idiom`（assets/usecases）同口径，双端共用同一
 * pipeline XML config。规则出处：用户《常见K线图.txt》前 25 行口诀。
 *
 * ### 口诀五条（原文 → 判定）
 * 1. **三阳不过阴撤退**（看跌 3）：前三根连阳，仍未吃掉第 4 根阴线实体（收 < 阴线开盘）
 * 2. **三阴不过阳进场**（看涨 3）：前三根连阴，仍未跌破第 4 根阳线实体（收 > 阳线开盘）
 * 3. **一阳吞三线撤离**（看跌 2）：放量阳线实体吞没前三根实体；高位(60日 pos≥0.55)
 *    → 诱多撤离；低位 → 视为反转看涨 2
 * 4. **两阳夹一阴会涨**（看涨 4 = 多方炮）：阳-缩量阴-放量阳且第三阳创新高
 * 5. **两阴夹一阳离场**（看跌 4 = 空方炮）：阴-缩量阳-放量阴且第三阴创新低
 *
 * ### 经典 / 大型形态
 * 一阳穿三线(出水芙蓉) 3 · 连续下跌T线见 2 · 前进红三兵 2 · 平底镊子线 2 ·
 * 断头铡刀 4 · 倒V型/倒锤线 2 · 剧涨并排红 2 · 头肩顶 3 · 圆弧顶 3 · 圆底/圆弧底 2 ·
 * 底部直角三角形 2；另并入 `CandlePatternDetector` 强度 ≥3 的形态（近端经典形态）。
 *
 * ### 打分与否决
 * - 看涨：`strength += bullBoost × 看涨强度`（上限 maxBull）
 * - 看跌：`strength -= bearPenalty × 看跌强度`（上限 maxBear）
 * - 否决：任一看跌形态强度 ≥ vetoStrength 且 veto=true → 直接从候选池剔除
 *   （断头铡刀 4 / 空方炮 4 默认否决；可通过 XML config 关闭）
 *
 * ### 位置
 * 各周期环境 XML：n_ancestral → **n_idiom** → n_event/n_smart/n_ai
 */
class KlineIdiomNode(
    private val bullBoost: Double = 0.6,
    private val bearPenalty: Double = 1.2,
    private val veto: Boolean = true,
    private val vetoStrength: Int = 4,
    private val maxBull: Double = 6.0,
    private val maxBear: Double = 6.0,
    private val minBars: Int = 30
) : BaseNode<Any, MergedSignalPool>("kline_idiom", "K线口诀", NodeType.ENRICHMENT) {

    companion object {
        private const val TAG = "KlineIdiomNode"
        private const val LOOKBACK_DAYS = 130   // 覆盖头肩顶(40)/圆弧(30)/MA30 等最长窗口
    }

    /** 命中明细：名 → 强度（正=看涨，负=看跌）。 */
    data class IdiomResult(
        val bull: MutableList<Pair<String, Int>> = mutableListOf(),
        val bear: MutableList<Pair<String, Int>> = mutableListOf()
    ) {
        val bullStrength: Int get() = bull.sumOf { it.second }
        val bearStrength: Int get() = bear.sumOf { it.second }
        val summary: String
            get() = (bull.map { "↑" + it.first } + bear.map { "↓" + it.first }).joinToString(" ")
    }

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> {
                context.log(nodeId, "$nodeName: 输入非 MergedSignalPool，跳过")
                return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }
        if (pool.boostedSignals.isEmpty()) return pool

        return try {
            val dao = StockDatabase.getInstance(context.androidContext).dailySnapshotDao()
            var nBull = 0
            var nBear = 0
            var nVeto = 0
            val kept = mutableListOf<com.chin.stockanalysis.strategy.models.StrategySignal>()

            for (signal in pool.boostedSignals) {
                val snaps = dao.getByCode(signal.stockCode, LOOKBACK_DAYS)
                if (snaps.size < minBars) {
                    kept.add(signal)
                    continue
                }
                val sorted = snaps.sortedBy { it.date }
                val r = evaluateIdiom(sorted)
                val hard = r.bear.filter { it.second >= vetoStrength }.map { it.first }
                if (veto && hard.isNotEmpty()) {
                    nVeto++
                    context.log(nodeId, "🚫 $nodeName 否决 ${signal.stockCode}: ${hard.joinToString("/")}")
                    continue
                }
                var delta = 0.0
                if (r.bull.isNotEmpty()) {
                    nBull++
                    delta += min(maxBull, bullBoost * r.bullStrength)
                }
                if (r.bear.isNotEmpty()) {
                    nBear++
                    delta -= min(maxBear, bearPenalty * r.bearStrength)
                }
                kept.add(
                    if (delta == 0.0) signal
                    else signal.copy(
                        strength = (signal.strength + delta).toInt().coerceIn(0, 100),
                        details = signal.details + ("kline_idiom" to r.summary)
                    )
                )
            }

            val sorted = kept.sortedByDescending { it.strength }
            context.log(
                nodeId,
                "🕯 K线口诀: 看涨$nBull 看跌$nBear 否决$nVeto / ${pool.boostedSignals.size} 只"
            )
            MergedSignalPool(pool.stockHits, pool.stockNames, sorted)
        } catch (e: Exception) {
            Log.e(TAG, "K线口诀评估异常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 异常(${e.message})，原样通过")
            pool
        }
    }

    /** 逐条判定口诀/形态（snaps 升序，≥minBars）。 */
    private fun evaluateIdiom(snaps: List<DailySnapshotEntity>): IdiomResult {
        val r = IdiomResult()
        val n = snaps.size
        if (n < 6) return r
        val o = snaps.map { it.open }
        val c = snaps.map { it.close }
        val hi = snaps.map { it.high }
        val lo = snaps.map { it.low }
        val vol = snaps.map { it.volume.toDouble() }
        if (c.last() <= 0) return r

        fun ib(i: Int): Double = c[n + i] - o[n + i]      // i<0 表示从末尾往前索引
        fun body(i: Int): Double = abs(ib(i))

        val recentIdx = (max(0, n - 10) until n)
        val mb = recentIdx.map { body(it - n) }.average().let { if (it > 0) it else c.last() * 0.005 }
        val win = if (n >= 60) c.subList(n - 60, n) else c
        val hi60 = win.max()
        val lo60 = win.min()
        val pos = if (hi60 > lo60) (c.last() - lo60) / (hi60 - lo60) else 0.5
        val volWin = if (n >= 20) vol.subList(n - 20, n) else vol
        val avgVol = volWin.average()

        // ① 三阳不过阴撤退
        if (n >= 4 && ib(-4) < 0 && (1..3).all { ib(-it) > 0 } &&
            listOf(c[n - 3], c[n - 2], c[n - 1]).max() < o[n - 4] * 0.998
        ) r.bear.add("三阳不过阴" to 3)

        // ② 三阴不过阳进场
        if (n >= 4 && ib(-4) > 0 && (1..3).all { ib(-it) < 0 } &&
            listOf(c[n - 3], c[n - 2], c[n - 1]).min() > o[n - 4] * 1.002
        ) r.bull.add("三阴不过阳" to 3)

        // ③ 一阳吞三线（高位诱多 / 低位反转）
        if (n >= 4 && ib(-1) > 0 && body(-1) >= mb * 1.5) {
            val top3 = (2..4).maxOf { max(o[n - it], c[n - it]) }
            val bot3 = (2..4).minOf { min(o[n - it], c[n - it]) }
            if (o[n - 1] <= bot3 && c[n - 1] >= top3) {
                if (pos >= 0.55) r.bear.add("一阳吞三线(高位诱多)" to 2)
                else r.bull.add("一阳吞三线(低位反转)" to 2)
            }
        }

        // ④ 两阳夹一阴（多方炮）
        if (n >= 3 && ib(-3) > 0 && ib(-2) < 0 && ib(-1) > 0 &&
            c[n - 1] > c[n - 3] && vol[n - 2] <= vol[n - 3] && vol[n - 1] >= vol[n - 2]
        ) r.bull.add("两阳夹一阴(多方炮)" to 4)

        // ⑤ 两阴夹一阳（空方炮）
        if (n >= 3 && ib(-3) < 0 && ib(-2) > 0 && ib(-1) < 0 &&
            c[n - 1] < c[n - 3] && vol[n - 2] <= vol[n - 3] && vol[n - 1] >= vol[n - 2]
        ) r.bear.add("两阴夹一阳(空方炮)" to 4)

        // ⑥ 一阳穿三线 / 出水芙蓉（放量上穿 MA5/10/30）
        if (n >= 31) {
            val p = listOfNotNull(ma(c, 5, n - 2), ma(c, 10, n - 2), ma(c, 30, n - 2))
            val q = listOfNotNull(ma(c, 5, n - 1), ma(c, 10, n - 1), ma(c, 30, n - 1))
            if (p.size == 3 && q.size == 3 && ib(-1) > 0 &&
                c[n - 2] < (p.minOrNull() ?: Double.MAX_VALUE) &&
                c[n - 1] > (q.maxOrNull() ?: Double.MIN_VALUE) &&
                avgVol > 0 && vol[n - 1] >= avgVol * 1.5
            ) r.bull.add("一阳穿三线(出水芙蓉)" to 3)
        }

        // ⑦ 连续下跌 T 线见
        if (n >= 5 && (n - 4 until n).all { c[it] < c[it - 1] }) {
            val lowShadow = min(o[n - 1], c[n - 1]) - lo[n - 1]
            val upShadow = hi[n - 1] - max(o[n - 1], c[n - 1])
            if (body(-1) <= mb * 0.8 &&
                lowShadow >= max(body(-1), c[n - 1] * 0.01) * 2 &&
                upShadow <= max(body(-1), c[n - 1] * 0.003)
            ) r.bull.add("连续下跌T线见" to 2)
        }

        // ⑧ 前进红三兵（低位/盘整后三连小阳）
        if (n >= 22 && (1..3).all { ib(-it) > 0 && body(-it) <= mb * 1.2 } &&
            c[n - 2] > c[n - 3] && c[n - 1] > c[n - 2] && pos <= 0.4
        ) r.bull.add("前进红三兵" to 2)

        // ⑨ 平底镊子线
        if (n >= 9 && abs(lo[n - 1] - lo[n - 2]) <= max(c[n - 1] * 0.005, 1e-9) &&
            min(lo[n - 1], lo[n - 2]) <= lo.subList(n - 9, n - 2).min() * 1.002
        ) r.bull.add("平底镊子线" to 2)

        // ⑩ 断头铡刀（均线取前一根位置，避免自证）
        if (n >= 21 && ib(-1) < 0 && c[n - 2] > 0 && (c[n - 1] / c[n - 2] - 1) <= -0.03) {
            val m = listOf(ma(c, 5, n - 2), ma(c, 10, n - 2), ma(c, 20, n - 2)).filterNotNull()
            if (m.size == 3 && c[n - 2] > m.maxOrNull()!! && c[n - 1] < m.minOrNull()!!) {
                r.bear.add("断头铡刀" to 4)
            }
        }

        // ⑪ 倒 V 型 / 倒锤线（上涨 8%+ 后高位长上影）
        if (n >= 6 && body(-1) > 0 && c[n - 6] > 0 &&
            (hi[n - 1] - max(o[n - 1], c[n - 1])) >= body(-1) * 2 &&
            (c[n - 1] / c[n - 6] - 1) >= 0.08 && pos >= 0.7
        ) r.bear.add("倒V型/倒锤线" to 2)

        // ⑫ 剧涨并排红
        if (n >= 4 && c[n - 4] > 0 && (c[n - 1] / c[n - 4] - 1) >= 0.15 &&
            ib(-2) > 0 && ib(-1) > 0 &&
            abs(c[n - 1] - c[n - 2]) <= c[n - 1] * 0.005 &&
            abs(o[n - 1] - o[n - 2]) <= c[n - 1] * 0.005
        ) r.bear.add("剧涨并排红" to 2)

        // ⑬ 头肩顶（40 根窗口 左肩-头-右肩 + 跌破颈线）
        if (n >= 40) {
            val lw = c.subList(n - 40, n - 25)
            val hw = c.subList(n - 25, n - 13)
            val rw = c.subList(n - 13, n)
            val ls = lw.max()
            val hd = hw.max()
            val rs = rw.max()
            if (hd > ls && hd > rs && abs(ls - rs) <= max(ls, rs) * 0.04 &&
                c[n - 1] < min(lw.min(), rw.min())
            ) r.bear.add("头肩顶" to 3)
        }

        // ⑭ 圆弧顶 / 圆底（30 根三段均值）
        if (n >= 30) {
            val a1 = c.subList(n - 30, n - 20).average()
            val a2 = c.subList(n - 20, n - 10).average()
            val a3 = c.subList(n - 10, n).average()
            when {
                a2 < a1 && a2 < a3 -> r.bull.add("圆底/圆弧底" to 2)
                a2 > a1 && a2 > a3 -> r.bear.add("圆弧顶" to 3)
            }
        }

        // ⑮ 底部直角三角形（三次探底相近 + 放量突破 30 日上边线）
        if (n >= 31) {
            val b1 = lo.subList(n - 30, n - 20).min()
            val b2 = lo.subList(n - 20, n - 10).min()
            val b3 = lo.subList(n - 10, n).min()
            if (abs(b1 - b2) <= max(b1, b2) * 0.02 && abs(b2 - b3) <= max(b2, b3) * 0.02 &&
                c[n - 1] > hi.subList(n - 31, n - 1).max() && avgVol > 0 && vol[n - 1] >= avgVol * 1.3
            ) r.bull.add("底部直角三角形" to 2)
        }

        // ⑯ 并入 CandlePatternDetector 的近端经典形态（强度 ≥3）
        runCatching { CandlePatternDetector.detect(snaps) }.getOrDefault(emptyList())
            .filter { it.strength >= 3 }
            .forEach { m ->
                if (m.direction == CandlePatternDetector.Direction.BULLISH) {
                    r.bull.add(m.patternName to m.strength)
                } else {
                    r.bear.add(m.patternName to m.strength)
                }
            }
        return r
    }

    /** 以 index 为末位的 period 日均线（样本不足 null）。 */
    private fun ma(vals: List<Double>, period: Int, index: Int): Double? {
        if (index < 0 || index + 1 < period) return null
        return vals.subList(index - period + 1, index + 1).average()
    }
}
