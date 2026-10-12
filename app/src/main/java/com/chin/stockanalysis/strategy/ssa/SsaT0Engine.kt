package com.chin.stockanalysis.strategy.ssa

import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * ## 三层周期 SSA 稳态做T回测引擎（纯 Kotlin）
 *
 * 周线（环境总闸）→ 日线（过滤器+硬止损）→ 1小时（cycle 拐点做T）。
 *
 * ⚠️ 铁律：
 * 1. 三层全部**固定长度滚动窗口**逐K重算 SSA，禁止一次性全量分解（全量=未来函数）。
 * 2. 信号在 t 收盘确认、t+1 开盘成交；1H 决策时能看到的日线/周线状态只可能是
 *    「已经收盘」的那一根（当日15:00前不用当日日K，当周未结束不用当周周K）。
 * 3. SSA 右端点会随新K重构，因此**只用连续2根确认的 cycle 拐点**，不预测未来高低点。
 * 4. A/B/C 是已收盘K的状态分类，用于当下的风控开关，不引用未来K。
 * 5. 反T必须先有底仓；B/C 禁止一切新开T；硬止损独立于 cycle 信号。
 */
object SsaT0Engine {

    const val INITIAL_CAPITAL = 100_000.0

    data class Bar(
        val date: String,          // 日/周/小时：yyyyMMdd
        val time: String = "",     // 小时线 HH:mm，日/周为空
        val open: Double, val high: Double, val low: Double, val close: Double
    ) {
        val isHour: Boolean get() = time.isNotEmpty()
    }

    /** 每层固定参数（窗口长度固定，不自动自适应） */
    data class LayerCfg(
        val embedL: Int,        // SSA 嵌入窗口 L
        val windowM: Int,       // 每次分解使用的固定滚动样本长度
        val lookbackN: Int,     // 波动标准差回看
        val cycleMult: Double = 1.0,
        val noiseMult: Double = 1.0
    )

    data class Params(
        val week: LayerCfg = LayerCfg(20, 80, 12),
        val day: LayerCfg = LayerCfg(30, 120, 20),
        val hour: LayerCfg = LayerCfg(30, 120, 20),
        val capUpPct: Double = 40.0,       // 周线趋势向上时单T仓位上限（%资金）
        val capDownPct: Double = 25.0,     // 周线趋势向下时
        val commissionRate: Double = 0.00025,
        val stampDutyRate: Double = 0.0005,
        val slippageRate: Double = 0.0005, // 滑点（双边各计）
        val trainRatio: Double = 0.70
    )

    /** 一层滚动 SSA 全序列结果（数组下标对齐该层 bars） */
    data class Layer(
        val bars: List<Bar>,
        val trend: DoubleArray,      // 每根K收盘时的趋势末端
        val cycle: DoubleArray,      // 每根K收盘时的周期末端
        val mid: DoubleArray,        // 供当根使用的中枢（t-1推断）
        val nUp: DoubleArray, val nDn: DoubleArray,
        val eUp: DoubleArray, val eDn: DoubleArray,
        val scenario: IntArray       // 当根事后 A/B/C（暖机=0）
    )

    data class Marker(val index: Int, val price: Double, val buy: Boolean, val reason: String)

    data class Trade(
        val direction: String,      // 正T / 反T
        val openIndex: Int, val openTime: String, val openPrice: Double,
        val closeIndex: Int, val closeTime: String, val closePrice: Double,
        val returnPct: Double,
        val barsHeld: Int,
        val weeklyUpAtOpen: Boolean,
        val exitReason: String
    )

    data class RiskEvent(val index: Int, val time: String, val level: String, val reason: String)

    data class SegStats(
        val name: String,
        val bars: Int, val weeks: Double,
        val totalReturnPct: Double, val annualizedPct: Double,
        val maxDrawdownPct: Double,
        val tradeCount: Int, val winCount: Int, val winRatePct: Double,
        val avgPnlPct: Double, val avgBarsHeld: Int,
        val tradesPerWeek: Double,
        val longT: Int, val shortT: Int,
        val benchReturnPct: Double, val excessPct: Double
    )

    data class Result(
        val params: Params,
        val h: Layer, val d: Layer, val w: Layer,
        val markers: List<Marker>,
        val trades: List<Trade>,
        val riskEvents: List<RiskEvent>,
        val equity: DoubleArray, val benchmark: DoubleArray, val drawdown: DoubleArray,
        val splitIndex: Int,
        val full: SegStats, val train: SegStats, val test: SegStats,
        val weekDown: WeekDownReport
    )

    data class WeekDownReport(
        val trades: Int, val winRatePct: Double, val avgPnlPct: Double,
        val totalPnlPct: Double, val weeks: Int
    )

    // ═══════════════════════════════ 主入口 ═══════════════════════════════

    fun run(hourBars: List<Bar>, dayBars: List<Bar>, params: Params = Params()): Result {
        require(hourBars.size >= params.hour.windowM + 60) {
            "小时K不足：需≥${params.hour.windowM + 60} 根（当前 ${hourBars.size}）"
        }
        require(dayBars.size >= params.day.windowM + 30) {
            "日K不足：需≥${params.day.windowM + 30} 根（当前 ${dayBars.size}）"
        }
        val weekBars = aggregateWeekly(dayBars)
        require(weekBars.size >= params.week.windowM + 10) {
            "周K不足：需≥${params.week.windowM + 10} 根（当前 ${weekBars.size}）"
        }

        val w = buildLayer(weekBars, params.week)
        val d = buildLayer(dayBars, params.day)
        val h = buildLayer(hourBars, params.hour)

        val sim = simulate(h, d, w, params)
        val split = (h.bars.size * params.trainRatio).toInt()

        val full = segmentStats("全样本", h, sim.equity, sim.benchmark, sim.trades, 0, h.bars.size, params)
        val train = segmentStats("训练集(前70%)", h, sim.equity, sim.benchmark, sim.trades, 0, split, params)
        val test = segmentStats("样本外(后30%)", h, sim.equity, sim.benchmark, sim.trades, split, h.bars.size, params)

        // 周线下跌环境独立绩效：开仓时周线趋势向下的 T 单
        val down = sim.trades.filter { !it.weeklyUpAtOpen }
        val weekDown = WeekDownReport(
            trades = down.size,
            winRatePct = if (down.isEmpty()) 0.0 else down.count { it.returnPct > 0 }.toDouble() / down.size * 100,
            avgPnlPct = if (down.isEmpty()) 0.0 else down.map { it.returnPct }.average(),
            totalPnlPct = down.sumOf { it.returnPct },
            weeks = countWeeks(h.bars, 0, h.bars.size)
        )

        return Result(params, h, d, w, sim.markers, sim.trades, sim.riskEvents,
            sim.equity, sim.benchmark, sim.drawdown, split,
            full, train, test, weekDown)
    }

    // ═══════════════════════════════ 周K聚合 ═══════════════════════════════

    /** 日K（升序，yyyyMMdd）→ ISO 周聚合：周一开盘~周五收盘，周K日期=该周最后交易日 */
    fun aggregateWeekly(days: List<Bar>): List<Bar> {
        val out = ArrayList<Bar>()
        var cur = -1L
        var o = 0.0; var hi = 0.0; var lo = 0.0; var c = 0.0; var lastDate = ""
        for (b in days) {
            val ld = LocalDate.parse(b.date, FMT)
            val key = ld.getLong(IsoFields.WEEK_BASED_YEAR) * 100 +
                ld.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            if (key != cur) {
                if (cur >= 0) out.add(Bar(lastDate, "", o, hi, lo, c))
                cur = key; o = b.open; hi = b.high; lo = b.low; c = b.close; lastDate = b.date
            } else {
                if (b.high > hi) hi = b.high
                if (b.low < lo) lo = b.low
                c = b.close; lastDate = b.date
            }
        }
        if (cur >= 0) out.add(Bar(lastDate, "", o, hi, lo, c))
        return out
    }

    // ═══════════════════════════════ 单层滚动 SSA ═══════════════════════════════

    private fun buildLayer(bars: List<Bar>, cfg: LayerCfg): Layer {
        val n = bars.size
        val M = cfg.windowM; val L = cfg.embedL; val N = cfg.lookbackN
        val close = DoubleArray(n) { bars[it].close }
        val trend = DoubleArray(n) { Double.NaN }
        val cycle = DoubleArray(n) { Double.NaN }
        val mid = DoubleArray(n) { Double.NaN }
        val nUp = DoubleArray(n) { Double.NaN }; val nDn = DoubleArray(n) { Double.NaN }
        val eUp = DoubleArray(n) { Double.NaN }; val eDn = DoubleArray(n) { Double.NaN }
        val scn = IntArray(n)

        for (t in M - 1 until n) {
            // 固定长度滚动窗口：只取 [t-M+1, t] 历史，绝不引用 t 之后
            val seg = close.copyOfRange(t - M + 1, t + 1)
            val dec = SsaEngine.decompose(seg, L)
            val mm = seg.size
            val trEnd = dec.trend[mm - 1]; val cyEnd = dec.cycle[mm - 1]
            trend[t] = trEnd; cycle[t] = cyEnd
            val cs = tailStd(dec.cycle, N)
            val ns = tailStd(dec.noise, N)
            // 供 t+1 使用的轨道（在 t 收盘生成）
            if (t + 1 < n) {
                mid[t + 1] = trEnd
                nUp[t + 1] = trEnd + cfg.cycleMult * cs
                nDn[t + 1] = trEnd - cfg.cycleMult * cs
                eUp[t + 1] = trEnd + cfg.cycleMult * cs + cfg.noiseMult * ns
                eDn[t + 1] = trEnd - cfg.cycleMult * cs - cfg.noiseMult * ns
            }
            // 当根事后情景（轨道由 t-1 生成，无未来）
            if (!nUp[t].isNaN()) {
                scn[t] = when {
                    close[t] in nDn[t]..nUp[t] -> SsaEngine.SCN_A
                    close[t] in eDn[t]..eUp[t] -> SsaEngine.SCN_B
                    else -> SsaEngine.SCN_C
                }
            }
        }
        return Layer(bars, trend, cycle, mid, nUp, nDn, eUp, eDn, scn)
    }

    private fun tailStd(v: DoubleArray, n: Int): Double {
        var cnt = 0; var sum = 0.0
        val from = max(0, v.size - n)
        for (i in from until v.size) if (!v[i].isNaN()) { sum += v[i]; cnt++ }
        if (cnt < 2) return 0.0
        val mean = sum / cnt
        var ss = 0.0
        for (i in from until v.size) if (!v[i].isNaN()) ss += (v[i] - mean).pow(2)
        return kotlin.math.sqrt(ss / (cnt - 1))
    }

    // ═══════════════════════════════ 做T状态机 ═══════════════════════════════

    // 持仓状态：1=底仓正常(1单位) 2=正T加仓(2单位) 0=反T卖出/风控清空
    private data class Leg(
        val direction: String,        // 正T / 反T
        val openIndex: Int, val openPrice: Double, val shares: Long,
        val weeklyUp: Boolean
    )

    private data class SimOut(
        val equity: DoubleArray, val benchmark: DoubleArray, val drawdown: DoubleArray,
        val markers: List<Marker>, val trades: List<Trade>, val riskEvents: List<RiskEvent>,
        val baseShares: Long, val basePrice: Double, val startIndex: Int
    )

    private fun simulate(h: Layer, d: Layer, w: Layer, p: Params): SimOut {
        val n = h.bars.size
        val closes = DoubleArray(n) { h.bars[it].close }

        // 暖机：等待三层轨道均有效后开始
        var start = 0
        for (i in h.bars.indices) {
            if (!h.nUp[i].isNaN()) { start = i; break }
        }
        // 基准底仓在 start 开盘建立（虚拟底仓）
        val basePrice = h.bars[start].open.takeIf { it > 0 } ?: h.bars[start].close
        val baseShares = lots(INITIAL_CAPITAL * p.capUpPct / 100.0, basePrice)
        var cash = INITIAL_CAPITAL - baseShares * basePrice * (1 + p.commissionRate) * (1 + p.slippageRate)
        var state = 1
        var leg: Leg? = null
        val trades = ArrayList<Trade>()
        val markers = ArrayList<Marker>()
        val events = ArrayList<RiskEvent>()

        markers.add(Marker(start, basePrice, true, "底仓"))

        fun dayIdxFor(t: Int): Int {
            // 1H 决策时可见的最新已收盘日K
            val hd = h.bars[t].date
            val tm = h.bars[t].time
            var di = -1
            for (i in d.bars.indices) {
                if (d.bars[i].date < hd || (d.bars[i].date == hd && tm >= "15:00")) di = i else break
            }
            return di
        }
        fun weekIdxFor(t: Int): Int {
            val hd = h.bars[t].date
            val tm = h.bars[t].time
            var wi = -1
            for (i in w.bars.indices) {
                if (w.bars[i].date < hd || (w.bars[i].date == hd && tm >= "15:00")) wi = i else break
            }
            return wi
        }

        val equity = DoubleArray(n) { INITIAL_CAPITAL }
        val bench = DoubleArray(n) { INITIAL_CAPITAL }

        for (t in start until n) {
            val di = dayIdxFor(t); val wi = weekIdxFor(t)
            val dayScn = if (di >= 0) d.scenario[di] else 0
            val weekScn = if (wi >= 0) w.scenario[wi] else 0
            val weekUp = if (wi >= 1) w.trend[wi] >= w.trend[wi - 1] else true
            val capPct = if (weekUp) p.capUpPct else p.capDownPct

            // 收盘价相对日线蓝色正常区间（硬止损判定，用已收盘日K轨道）
            val hardStop = di >= 0 && !d.nUp[di].isNaN() &&
                (closes[t] > d.nUp[di] || closes[t] < d.nDn[di])

            // ── cycle 拐点（连续2根确认，规避端点重构问题；先于风控计算） ──
            var valley = false; var peak = false
            if (t >= 2 && !h.cycle[t].isNaN() && !h.cycle[t - 1].isNaN() && !h.cycle[t - 2].isNaN()) {
                valley = h.cycle[t - 2] > h.cycle[t - 1] && h.cycle[t - 1] < h.cycle[t]
                peak = h.cycle[t - 2] < h.cycle[t - 1] && h.cycle[t - 1] > h.cycle[t]
            }

            // ── 层级风控（优先级最高，t 收盘决策，t+1 开盘成交） ──
            // B 情景：T腿立即平；仅剩底仓时遇 cycle 峰顶也离场（整单位近似"减半尽快离场"）
            var force: String? = null
            when {
                weekScn == SsaEngine.SCN_C -> force = "周线C·全平停手"
                dayScn == SsaEngine.SCN_C -> force = "日线C·全平停手"
                hardStop -> force = "价格越日线蓝轨·硬止损"
                dayScn == SsaEngine.SCN_B && leg != null -> force = "日线B·减半离场"
                dayScn == SsaEngine.SCN_B && state == 1 && peak -> force = "日线B·顶背离场"
            }

            if (force != null && t + 1 < n && state > 0) {
                val fill = h.bars[t + 1].open
                // 平掉 T 腿
                if (leg != null) {
                    closeLeg(leg, t + 1, fill, h, trades, markers, force, p, isLong = leg.direction == "正T")
                    if (leg.direction == "正T") { cash += sellProceeds(leg.shares, fill, p); state = 1 }
                    else { cash -= buyCost(leg.shares, fill, p); state = 1 }
                    leg = null
                }
                // C/硬止损：底仓也清；B：只减半（已无加仓腿则不动底仓）
                if (state == 1 && (weekScn == SsaEngine.SCN_C || dayScn == SsaEngine.SCN_C || hardStop)) {
                    cash += sellProceeds(baseShares, fill, p)
                    markers.add(Marker(t + 1, fill, false, force))
                    state = 0
                }
                // B 情景底仓顶背离场：整单位离场（模型最小粒度）
                if (state == 1 && dayScn == SsaEngine.SCN_B) {
                    cash += sellProceeds(baseShares, fill, p)
                    markers.add(Marker(t + 1, fill, false, force))
                    state = 0
                }
                events.add(RiskEvent(t + 1, labelOf(h.bars[t + 1]),
                    if (weekScn == SsaEngine.SCN_C) "周线" else "日线", force))
            }

            // 开新T许可：日线A 且 周线非C，且不是刚被风控清空的状态
            val allowOpen = dayScn == SsaEngine.SCN_A && weekScn != SsaEngine.SCN_C

            if (t + 1 < n && force == null) {
                val fill = h.bars[t + 1].open
                if (fill > 0) when {
                    // 反T接回（最高优先，结束反T）
                    leg?.direction == "反T" && valley -> {
                        cash -= buyCost(leg.shares, fill, p)
                        closeLeg(leg, t + 1, fill, h, trades, markers, "cycle谷底·接回", p, isLong = false)
                        leg = null; state = 1
                    }
                    // 正T止盈（结束正T）
                    leg?.direction == "正T" && peak -> {
                        cash += sellProceeds(leg.shares, fill, p)
                        closeLeg(leg, t + 1, fill, h, trades, markers, "cycle峰顶·止盈", p, isLong = true)
                        leg = null; state = 1
                    }
                    // 正T低吸（无T腿时；state=0 视为重建底仓式开新正T）
                    leg == null && valley && allowOpen -> {
                        // 单T仓位 = 总资金 × (周线向上40% / 向下25%)；整手
                        val sh = lots(INITIAL_CAPITAL * capPct / 100.0, fill)
                        if (sh > 0 && cash >= buyCost(sh, fill, p)) {
                            cash -= buyCost(sh, fill, p)
                            leg = Leg("正T", t + 1, fill, sh, weekUp)
                            state += 1
                            markers.add(Marker(t + 1, fill, true, "正T低吸"))
                        }
                    }
                    // 反T高抛（必须有底仓：state==1 且无T腿）
                    leg == null && state == 1 && peak && allowOpen -> {
                        val sh = lots(INITIAL_CAPITAL * capPct / 100.0, fill)
                            .coerceAtMost(baseShares)
                        if (sh > 0) {
                            cash += sellProceeds(sh, fill, p)
                            leg = Leg("反T", t + 1, fill, sh, weekUp)
                            state = 0
                            markers.add(Marker(t + 1, fill, false, "反T高抛"))
                        }
                    }
                }
            }

            // 持仓估值：state 0/1/2 → 0/1/2 个单位（T腿份额按其自身价估）
            var posVal = if (state >= 1) baseShares * closes[t] else 0.0
            if (leg != null && leg.direction == "正T") posVal += leg.shares * closes[t]
            equity[t] = cash + posVal
            bench[t] = INITIAL_CAPITAL * (closes[t] / basePrice)
        }

        // 期末：未平T腿按最后收盘平仓结算（统计用，不改变 state 语义）
        val last = n - 1
        if (leg != null) {
            val fill = closes[last]
            closeLeg(leg, last, fill, h, trades, markers, "期末平仓", p, isLong = leg.direction == "正T")
            if (leg.direction == "正T") cash += sellProceeds(leg.shares, fill, p) else cash -= buyCost(leg.shares, fill, p)
            leg = null; state = 1
        }
        var posVal = if (state >= 1) baseShares * closes[last] else 0.0
        equity[last] = cash + posVal

        var peak = INITIAL_CAPITAL
        val dd = DoubleArray(n)
        for (i in 0 until n) {
            if (equity[i] > peak) peak = equity[i]
            dd[i] = (equity[i] - peak) / peak * 100
        }
        return SimOut(equity, bench, dd, markers, trades, events, baseShares, basePrice, start)
    }

    private fun labelOf(b: Bar) = "${b.date.take(4)}-${b.date.substring(4, 6)}-${b.date.substring(6, 8)} ${b.time}"

    private fun lots(capital: Double, price: Double): Long {
        if (price <= 0) return 0
        return (floor(capital / price / 100.0) * 100).toLong().coerceAtLeast(0)
    }

    private fun buyCost(shares: Long, price: Double, p: Params): Double =
        shares * price * (1 + p.slippageRate) * (1 + p.commissionRate)

    private fun sellProceeds(shares: Long, price: Double, p: Params): Double =
        shares * price * (1 - p.slippageRate) * (1 - p.commissionRate - p.stampDutyRate)

    private fun closeLeg(
        leg: Leg, closeIndex: Int, fill: Double, h: Layer,
        trades: MutableList<Trade>, markers: MutableList<Marker>,
        reason: String, p: Params, isLong: Boolean
    ) {
        val ret = if (isLong)
            (sellProceeds(leg.shares, fill, p) - buyCost(leg.shares, leg.openPrice, p)) /
                buyCost(leg.shares, leg.openPrice, p) * 100
        else
            (sellProceeds(leg.shares, leg.openPrice, p) - buyCost(leg.shares, fill, p)) /
                sellProceeds(leg.shares, leg.openPrice, p) * 100
        trades.add(Trade(
            direction = leg.direction,
            openIndex = leg.openIndex, openTime = labelOf(h.bars[leg.openIndex]),
            openPrice = leg.openPrice,
            closeIndex = closeIndex, closeTime = labelOf(h.bars[closeIndex]),
            closePrice = fill, returnPct = ret, barsHeld = closeIndex - leg.openIndex,
            weeklyUpAtOpen = leg.weeklyUp, exitReason = reason
        ))
        markers.add(Marker(closeIndex, fill, isLong, reason))
    }

    // ═══════════════════════════════ 分段统计 ═══════════════════════════════

    private fun countWeeks(bars: List<Bar>, from: Int, to: Int): Int {
        val set = HashSet<Long>()
        for (i in from until to) {
            val ld = LocalDate.parse(bars[i].date, FMT)
            set.add(ld.getLong(IsoFields.WEEK_BASED_YEAR) * 100 +
                ld.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        }
        return set.size.coerceAtLeast(1)
    }

    private fun segmentStats(
        name: String, h: Layer, equity: DoubleArray, bench: DoubleArray,
        trades: List<Trade>, from: Int, to: Int, p: Params
    ): SegStats {
        val e0 = equity[from]; val e1 = equity[to - 1]
        val total = (e1 / e0 - 1) * 100
        val d0 = LocalDate.parse(h.bars[from].date, FMT)
        val d1 = LocalDate.parse(h.bars[to - 1].date, FMT)
        val years = java.time.temporal.ChronoUnit.DAYS.between(d0, d1).coerceAtLeast(1) / 365.0
        val annual = ((e1 / e0).pow(1.0 / years) - 1) * 100
        var peak = e0; var mdd = 0.0
        for (i in from until to) {
            if (equity[i] > peak) peak = equity[i]
            val dd = (equity[i] - peak) / peak
            if (dd < mdd) mdd = dd
        }
        val seg = trades.filter { it.openIndex in from until to }
        val wins = seg.filter { it.returnPct > 0 }
        val weeks = countWeeks(h.bars, from, to)
        val b0 = bench[from]; val b1 = bench[to - 1]
        val bp = (b1 / b0 - 1) * 100
        return SegStats(
            name = name, bars = to - from, weeks = weeks.toDouble(),
            totalReturnPct = total, annualizedPct = annual, maxDrawdownPct = mdd * 100,
            tradeCount = seg.size,
            winCount = wins.size,
            winRatePct = if (seg.isEmpty()) 0.0 else wins.size.toDouble() / seg.size * 100,
            avgPnlPct = if (seg.isEmpty()) 0.0 else seg.map { it.returnPct }.average(),
            avgBarsHeld = if (seg.isEmpty()) 0 else seg.sumOf { it.barsHeld } / seg.size,
            tradesPerWeek = seg.size.toDouble() / weeks,
            longT = seg.count { it.direction == "正T" },
            shortT = seg.count { it.direction == "反T" },
            benchReturnPct = bp, excessPct = total - bp
        )
    }

    private val FMT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
}
