package com.chin.stockanalysis.strategy.ssa

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ## ER分位 · App 端本机备选（2026-10-18 用户需求）
 *
 * PC 推送不可用、或推送 JSON 里缺 `er_pct_map` 时，App 本机用
 * **每日荐股核心池（[HistoricalDataFetcher.getCoreStockPool]，~171 只）+ Room 日线（W=60）**
 * 计算 Kaufman 效率比，并在**池内**算横截面分位，作为降级备选。
 *
 * - 与 PC 推送**同池同口径**：池 = `app/src/main/assets/core_stock_pool.json`，K 线 = 日线。
 * - 数据全部取自 Room `daily_snapshot`（本地读库、**不联网**）；需先跑过每日荐股全量构建
 *   （SsaRecoPipeline）把池内日线灌进本地库，否则池内有效样本会不足。
 * - 池内有效样本 <2（无法构成横截面）时返回空表；PC 恢复后 UI 自动改回 PC 口径。
 */
object SsaErLocal {

    private const val TAG = "SsaErLocal"

    /** 效率比窗口（根） */
    const val W = 60

    /** 单票日线读取根数（ER 只需 W+1 根，取 300 留足余量） */
    private const val DAILY_LIMIT = 300

    /** 末根 Kaufman 效率比 = |C_n − C_{n−W}| / Σ|ΔC|（数据不足/含非有限值返回 null） */
    fun erOf(closes: List<Double>, w: Int = W): Double? {
        val n = closes.size
        if (n < w + 1) return null
        var vol = 0.0
        for (k in (n - w) until n) {
            val a = closes[k]
            val b = closes[k - 1]
            if (!a.isFinite() || !b.isFinite()) return null
            vol += abs(a - b)
        }
        if (vol <= 0.0) return 0.0
        return abs(closes[n - 1] - closes[n - 1 - w]) / vol
    }

    /**
     * 每日荐股核心池内、按 Room 日线（W=60）的 ER 横截面分位（中位秩，0~100）。
     *
     * 2026-10-18 用户方案A：[extraCodes]（持仓票等池外票）**并入横截面** ——
     * universe = 每日荐股核心池 ∪ extraCodes，分位在扩展池内计算，池外票也永远有值。
     *
     * @return `{6位代码: 0~100}`；universe 有效样本 <2 时返回空表
     */
    suspend fun corePoolPercentiles(ctx: Context, extraCodes: Set<String> = emptySet()): Map<String, Double> =
        withContext(Dispatchers.IO) {
            val codes = runCatching { HistoricalDataFetcher.getCoreStockPool(ctx) }
                .getOrDefault(emptySet())
            val universe = codes + extraCodes
            if (universe.isEmpty()) return@withContext emptyMap()
            percentilesFor(ctx, universe)
        }

    /**
     * 任意 universe 的 ER 横截面分位：对 [universeRaw]（带/不带 sh/sz/bj 前缀均可）逐票读
     * Room 日线算末根 ER（W=60），再在**该 universe 内**算中位秩分位。
     *
     * 供「持仓票并入横截面补算」使用：universe 传入 `PC表keys ∪ 缺失持仓票`，
     * 只取缺失票的分位回填（池内票仍以 PC 值为准，保持 PC 优先口径）。
     *
     * @return `{6位代码: 0~100}`；universe 有效样本 <2 时返回空表
     */
    suspend fun percentilesFor(ctx: Context, universeRaw: Collection<String>): Map<String, Double> =
        withContext(Dispatchers.IO) {
            if (universeRaw.isEmpty()) return@withContext emptyMap()
            val dao = StockDatabase.getInstance(ctx).dailySnapshotDao()
            val ers = ConcurrentHashMap<String, Double>()
            // 2026-10-18：8 路并发读 Room（171 只串行要 2~8s，并行 <1s），失败的单票跳过
            coroutineScope {
                val sem = Semaphore(8)
                universeRaw.map { norm(it) }.toSet().forEach { raw ->
                    launch {
                        sem.withPermit {
                            val secid = secidOf(raw)
                            runCatching {
                                val closes = dao.getByCode(secid, DAILY_LIMIT) // DAO 按 date DESC 返回
                                    .filter { it.close > 0.0 }
                                    .sortedBy { it.date }                      // → 升序
                                    .map { it.close }
                                val er = erOf(closes)
                                if (er != null && er.isFinite()) ers[raw] = er
                            }.onFailure {
                                Log.w(TAG, "[$raw] 读日线失败: ${it.message}")
                            }
                        }
                    }
                }
            }
            if (ers.size < 2) return@withContext emptyMap()
            val vals = ers.values.sorted()
            val n = vals.size
            // 中位秩 = (严格小于数 + 0.5×相等数) / 总数 —— 同值不塌缩到同一点
            ers.mapValues { (_, e) ->
                100.0 * (lowerBound(vals, e) + 0.5 * (upperBound(vals, e) - lowerBound(vals, e))) / n
            }
        }

    /** 6 位代码归一：去掉 sh/sz/bj 前缀 */
    private fun norm(raw: String): String {
        val s = raw.trim()
        return if (s.length > 6 && (s.startsWith("sh") || s.startsWith("sz") || s.startsWith("bj")))
            s.substring(2) else s
    }

    // ── ER分位 × BBI12 上行拐点筛选（2026-10-19 用户需求）──────────────────
    // 口径与 PC 筛选脚本 smalltools/_er_bbi12_screen.py / 回测 _er_router_backtest.py 完全同源：
    // BBI=(MA3+MA6+MA12+MA24)/4，BBI12=SMA(BBI,12)；拐点：谷底 b<=a && c>b（信号根=确认根 i+1），
    // 峰顶 b>=a && c<b；「上行中」= 末个已确认拐点是谷底 且 末根 BBI12 >= 前一根（排除下行/回落）。

    /** 一只上行票的展示行 */
    data class UpRow(
        val c6: String,
        val pct: Double,        // ER 池内中位秩分位 0~100
        val stage: String,      // 阶段标签（ER 值演化口径），如 "趋势·前段(ER 0.42↑)"
        val barsAfter: Int,     // 谷底信号根之后经过的根数
        val troughDate: String, // 谷底信号根日期
        val close: Double,      // 末根收盘
        val gain: Double        // BBI12 末根相对谷底信号根的涨幅 %
    )

    /** 筛选结果：rows 已按 ER 分位降序；asof=数据截至（最新日线日期）；stages=池内全体票阶段标签 */
    data class UpScreen(
        val rows: List<UpRow>, val asof: String, val poolN: Int, val upN: Int,
        val stages: Map<String, String> = emptyMap()
    )

    // ── ER 阶段（2026-10-19 用户口径：市况按池内分位，进度按 ER 值变化）─────────
    // 语义对齐：ER 评分（分位）高 = 趋势段 —— 市况分界复用回测 P70 闸门口径，
    // 不用绝对 0.3（池内 ER 普遍偏低时会出现"分位 95 却标震荡"的矛盾）。
    // 进度 = 近 5 根 ΔER（>±0.03 显著）：抬升/持续/回落。
    //   趋势(≥70)：抬升=前段(启动) / 持续=中段(高位) / 回落=末段(衰竭)
    //   过渡(30~70)：抬升=前段(向趋势演化) / 持续=中段 / 回落=末段(向震荡演化)
    //   震荡(≤30)：抬升=末段(酝酿突破) / 持续=中段(盘整) / 回落=前段(刚入盘整)

    /** 趋势市况分界：ER 分位 ≥ 此值（与回测 P70 闸门同口径） */
    const val ER_TREND_PCT = 70.0

    /** 震荡市况分界：ER 分位 ≤ 此值 */
    const val ER_RANGE_PCT = 30.0

    /** ER 变化显著阈值：近 N 根 |ΔER| 超过它才判抬升/回落 */
    const val ER_SLOPE_THR = 0.03

    /** ER 变化观察窗（根） */
    const val ER_SLOPE_BARS = 5

    /** 滚动 ER 序列（末 tail 根，窗口 W，逐根滚动）：供阶段判定 */
    fun erSeries(closes: List<Double>, tail: Int = 40): DoubleArray {
        val n = closes.size
        if (n < W + 1) return DoubleArray(0)
        val from = maxOf(W, n - tail)
        val out = DoubleArray(n - from) { Double.NaN }
        for (i in from until n) {
            val e = erOf(closes.subList(i - W, i + 1))
            out[i - from] = e ?: Double.NaN
        }
        return out
    }

    /**
     * 阶段标签：市况由池内分位定（≥70 趋势 / ≤30 震荡 / 其间 过渡），
     * 进度由 ER 序列近 5 根变化定（对称语义见上）。
     * 返回如 "趋势·前段(ER 0.42↑)" / "震荡·末段(ER 0.21↑)"；数据不足返回 null。
     */
    fun stageLabel(pct: Double, erSeq: DoubleArray): String? {
        if (erSeq.isEmpty()) return null
        val now = erSeq.last()
        if (!now.isFinite()) return null
        val pi = erSeq.size - 1 - ER_SLOPE_BARS
        if (pi < 0) return null
        val prev = erSeq[pi]
        if (!prev.isFinite()) return null
        val d = now - prev
        val arrow = if (d > 0) "↑" else if (d < 0) "↓" else "→"
        val market = when {
            pct >= ER_TREND_PCT -> "趋势"
            pct <= ER_RANGE_PCT -> "震荡"
            else -> "过渡"
        }
        val rising = d > ER_SLOPE_THR
        val falling = d < -ER_SLOPE_THR
        val seg = when {
            rising && market != "过渡" -> if (market == "趋势") "前段" else "末段"
            falling && market != "过渡" -> if (market == "趋势") "末段" else "前段"
            rising -> "前段"
            falling -> "末段"
            else -> "中段"
        }
        return "$market·$seg(ER ${"%.2f".format(now)}$arrow)"
    }

    /** 单票阶段查询（池外持仓票补算用）：Room 日线 → 滚动 ER 序列 → 标签；数据不足返回 null */
    suspend fun stageFor(ctx: Context, c6: String, erPct: Double): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val closes = StockDatabase.getInstance(ctx).dailySnapshotDao()
                    .getByCode(secidOf(c6), DAILY_LIMIT)
                    .filter { it.close > 0.0 }
                    .sortedBy { it.date }
                    .map { it.close }
                stageLabel(erPct, erSeries(closes))
            }.getOrNull()
        }

    /** SMA（窗口含非有限值 → NaN，与 PC sma_series 同口径） */
    private fun smaSeries(a: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(a.size) { Double.NaN }
        for (i in (n - 1) until a.size) {
            var s = 0.0
            var ok = true
            for (k in (i - n + 1)..i) {
                val v = a[k]
                if (!v.isFinite()) { ok = false; break }
                s += v
            }
            if (ok) out[i] = s / n
        }
        return out
    }

    /** BBI12 = SMA(BBI,12)，BBI=(MA3+MA6+MA12+MA24)/4（与 PC bbi12_line 同口径） */
    fun bbi12Line(closes: List<Double>): DoubleArray {
        val a = closes.toDoubleArray()
        val m3 = smaSeries(a, 3); val m6 = smaSeries(a, 6)
        val m12 = smaSeries(a, 12); val m24 = smaSeries(a, 24)
        val bbi = DoubleArray(a.size) { Double.NaN }
        for (i in a.indices) {
            if (!m3[i].isFinite() || !m6[i].isFinite() || !m12[i].isFinite() || !m24[i].isFinite()) continue
            bbi[i] = (m3[i] + m6[i] + m12[i] + m24[i]) / 4.0
        }
        return smaSeries(bbi, 12)
    }

    /** BBI12 线拐点：(确认根下标, 是否谷底)；与 PC bbi12_pivots 同口径 */
    fun bbi12Pivots(line: DoubleArray): List<Pair<Int, Boolean>> {
        val out = ArrayList<Pair<Int, Boolean>>()
        for (i in 1 until line.size - 1) {
            val a = line[i - 1]; val b = line[i]; val c = line[i + 1]
            if (!a.isFinite() || !b.isFinite() || !c.isFinite()) continue
            if (b <= a && c > b) out.add((i + 1) to true)
            else if (b >= a && c < b) out.add((i + 1) to false)
        }
        return out
    }

    /**
     * ER分位 × BBI12 上行拐点 · Top N（App 端本机版，2026-10-19 用户需求）。
     * 池 = 每日荐股核心池；日线取 Room daily_snapshot（8 路并发读，不联网）。
     * 返回 rows 按 ER 分位降序；池为空/有效样本 <2 时 rows 为空（asof/poolN 仍回填）。
     */
    suspend fun upscreen(ctx: Context, topN: Int = 20): UpScreen = withContext(Dispatchers.IO) {
        val codes = runCatching { HistoricalDataFetcher.getCoreStockPool(ctx) }
            .getOrDefault(emptySet())
            .map { norm(it) }.toSet()
        if (codes.isEmpty()) return@withContext UpScreen(emptyList(), "", 0, 0)
        val dao = StockDatabase.getInstance(ctx).dailySnapshotDao()
        // c6 → (closes 升序, dates 升序)
        val data = ConcurrentHashMap<String, Pair<List<Double>, List<String>>>()
        coroutineScope {
            val sem = Semaphore(8)
            codes.forEach { raw ->
                launch {
                    sem.withPermit {
                        runCatching {
                            val sn = dao.getByCode(secidOf(raw), DAILY_LIMIT)
                                .filter { it.close > 0.0 }
                                .sortedBy { it.date }
                            if (sn.isNotEmpty()) {
                                data[raw] = sn.map { it.close } to sn.map { it.date }
                            }
                        }
                    }
                }
            }
        }
        if (data.size < 2) return@withContext UpScreen(emptyList(), "", codes.size, 0)
        val ers = HashMap<String, Double>()
        val ups = HashSet<String>()
        val lineCache = HashMap<String, DoubleArray>()
        var asof = ""
        data.forEach { (c, pair) ->
            val (closes, dates) = pair
            val d = dates.last()
            if (d > asof) asof = d
            val e = erOf(closes)
            if (e != null && e.isFinite()) ers[c] = e
            val line = bbi12Line(closes)
            lineCache[c] = line
            if (line.size >= 2 && line.last().isFinite() && line[line.size - 2].isFinite()) {
                val piv = bbi12Pivots(line)
                if (piv.isNotEmpty() && piv.last().second && line.last() >= line[line.size - 2]) {
                    ups.add(c)
                }
            }
        }
        if (ers.size < 2) return@withContext UpScreen(emptyList(), asof, codes.size, ups.size)
        val vals = ers.values.sorted()
        val n = vals.size
        fun pctOf(e: Double) =
            100.0 * (lowerBound(vals, e) + 0.5 * (upperBound(vals, e) - lowerBound(vals, e))) / n
        // 池内全体票的阶段标签（Bottom10 / 候选票 / 持仓票卡片直接查这张表）
        val stages = HashMap<String, String>()
        ers.forEach { (c, e) ->
            data[c]?.let { (closes, _) ->
                stageLabel(pctOf(e), erSeries(closes))?.let { s -> stages[c] = s }
            }
        }
        val rows = ups.filter { ers.containsKey(it) }
            .sortedByDescending { pctOf(ers[it]!!) }
            .take(topN)
            .map { c ->
                val (closes, dates) = data[c]!!
                val line = lineCache[c]!!
                val piv = bbi12Pivots(line)
                val ti = if (piv.isNotEmpty()) piv.last().first else closes.size - 1
                val tiC = ti.coerceIn(0, closes.size - 1)
                val base = line.getOrNull(tiC) ?: Double.NaN
                val gain = if (base.isFinite() && base > 0) (line.last() / base - 1.0) * 100.0
                           else Double.NaN
                UpRow(c, pctOf(ers[c]!!), stages[c] ?: "",
                    closes.size - 1 - tiC,
                    dates.getOrNull(tiC)?.takeLast(5) ?: "—", closes.last(), gain)
            }
        UpScreen(rows, asof, codes.size, ups.size, stages)
    }

    /** 补市场前缀（6/5/9 → sh；其余 → sz），与 Room `daily_snapshot.code` 口径一致 */
    private fun secidOf(raw: String): String {
        val s = raw.trim()
        return when {
            s.length > 6 && (s.startsWith("sh") || s.startsWith("sz") || s.startsWith("bj")) -> s
            s.startsWith("6") || s.startsWith("5") || s.startsWith("9") -> "sh$s"
            else -> "sz$s"
        }
    }

    private fun lowerBound(a: List<Double>, v: Double): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) {
            val m = (lo + hi) / 2
            if (a[m] < v) lo = m + 1 else hi = m
        }
        return lo
    }

    private fun upperBound(a: List<Double>, v: Double): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) {
            val m = (lo + hi) / 2
            if (a[m] <= v) lo = m + 1 else hi = m
        }
        return lo
    }
}
