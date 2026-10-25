package com.chin.stockanalysis.strategy.ssa

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ## SSA 混合策略引擎（trend 斜率窗口定方向 + cycle 拐点波段，折中低延迟版）
 *
 * 信号（全部基于已收盘 K，t+1 开盘成交，无未来函数）：
 *  - 买入窗口：短 trend 斜率向上（低延迟，替代金叉排列判定）
 *  - 买入①：斜率由负转正瞬间（低延迟版金叉，T仓+底仓建仓）
 *  - 买入②：窗口内 cycle 谷底确认（cy[t-2]>cy[t-1]<cy[t]）
 *  - 卖出①：cycle 峰顶确认（cy[t-2]<cy[t-1]>cy[t]）
 *  - 卖出②：trend 死叉（最后防线，底仓同出）
 *
 * 底仓：baseFraction 资金在斜率转正买入、死叉卖出，期间不做波段；其余资金跑上述 T 策略。
 * 每根 K 收盘给出次日建议：BUY / SELL / HOLD_POS(持有) / HOLD_EMPTY(空仓观望)，
 * 并用次日开盘成交、t+1/3/5 收盘滚动评估建议命中率。
 *
 * **A 股 T+1（2026-10-22 修正）**：仅「t 收盘出信号 → t+1 开盘成交」只保证跨根，1H 级别下
 * t+1 与 t 仍在同一交易日，会出现当日买入当日卖出（违规）。故 [AccountSim.executeOpen]
 * 按**交易日**再判一次：卖出执行日必须晚于买入日，未解锁的卖单顺延到下一交易日开盘。
 * 日线级别下原逻辑已天然满足 T+1，本约束不改变日线结果。
 *
 * 逐 K 滚动 SSA 与 [SsaEngine] 完全同口径：每个 t 只用 [0..t] 历史独立分解取末端值；
 * trend/cycle 在末端值上再做 EMA 平滑（PC 全池 172 票验证 span=2 为甜点）。
 *
 * ⚠️ SSA 是历史时序分解，不预测精准价格；建议为规则输出，不构成投资建议；
 *    末尾 L 根存在边缘效应。
 *
 * 每日荐股增量模式：[RecoIncremental] seed 全量一次 + step 每日一根，历史永不重算，
 * 结果与逐日全量重算逐位一致（单测 SsaRecoIncrementalTest 守护）。
 */
object SsaHybridEngine {

    const val BUY = 0
    const val SELL = 1
    const val HOLD_POS = 2
    const val HOLD_EMPTY = 3

    /** 快速通道 Kaufman 效率系数窗口（与 PC 全池验证一致） */
    private const val FAST_ER_W = 10

    data class Bar(
        val date: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    )

    /**
     * @param trendShort 短趋势 SSA 窗口 L
     * @param trendLong  长趋势 SSA 窗口 L
     * @param cycleL     周期分量 SSA 窗口 L
     * @param smoothSpan trend/cycle 末端值 EMA 平滑 span（1=不平滑，全池验证推荐 2）
     * @param baseFraction 底仓比例 0..1（金叉买/死叉卖，不做波段）
     * @param fastErThr 快速通道 ER10 阈值（>0 启用；2026-10-21 全池验证默认 0.6）
     * @param fastDonN  快速通道唐奇安通道根数（前 N 根收盘高点/低点，0=关闭）
     * @param fastSell  快速通道是否允许"跌破通道立即卖出"（实测仅快买无效，买卖需成对）
     */
    data class Params(
        val trendShort: Int = 10,
        val trendLong: Int = 20,
        val cycleL: Int = 60,
        val smoothSpan: Int = 2,
        val baseFraction: Double = 0.0,
        val commissionRate: Double = 0.00025,
        val stampDutyRate: Double = 0.0005,
        val fastErThr: Double = 0.6,
        val fastDonN: Int = 15,
        val fastSell: Boolean = true,
        /** true = 仅跟随 cycle 拐点（谷底买 / 峰顶卖），忽略 trend 斜率与快通道 */
        val cycleOnly: Boolean = false
    ) {
        fun validate(): String? = when {
            trendShort !in 5..60 -> "短趋势 L 需在 5~60"
            trendLong !in 5..60 || trendLong <= trendShort -> "长趋势 L 需大于短趋势且在 5~60"
            cycleL !in 5..60 -> "周期 L 需在 5~60"
            smoothSpan !in 1..10 -> "EMA span 需在 1~10"
            baseFraction !in 0.0..1.0 -> "底仓比例需在 0~1"
            fastErThr !in 0.0..1.0 -> "快通道 ER 阈值需在 0~1"
            fastDonN !in 0..120 -> "快通道通道根数需在 0~120"
            else -> null
        }
    }

    data class Trade(
        val buyIndex: Int, val buyDate: String, val buyPrice: Double,
        val sellIndex: Int, val sellDate: String, val sellPrice: Double,
        val returnPct: Double, val barsHeld: Int,
        val buyWhy: String, val exitReason: String
    )

    data class AdviceStat(
        val count: Int,
        val win1Pct: Double,    // 次日收盘方向正确比例
        val win3Pct: Double,    // 3 日收盘方向正确比例
        val avgRet5Pct: Double  // 5 日平均收益（卖出统计中为负=避跌正确）
    )

    data class Stats(
        val totalReturnPct: Double, val benchmarkReturnPct: Double, val excessReturnPct: Double,
        val maxDrawdownPct: Double, val winRatePct: Double, val profitFactor: Double,
        val tradeCount: Int, val avgHoldBars: Int,
        val startDate: String, val endDate: String, val barCount: Int
    )

    /**
     * 票性识别结果（PC classify_ticket 同口径移植，固定 L=30+EMA2）。
     * hi3/hi5 = 高噪样本(z≥1)后 3/5 日均收益；lo5 = 超跌样本(z≤-2)后 5 日均收益；
     * cycAmp = 周期振幅/均价；hiCount/loCount = 样本数。前向窗口只取已走完的历史样本（滞后5日，无未来函数）。
     */
    data class TicketProfile(
        val label: String, val hi3: Double, val hi5: Double, val lo5: Double,
        val cycAmp: Double, val hiCount: Int, val loCount: Int
    )

    /** 近期单条建议快照：r1/r3/r5 = t+k 收盘相对 t 收盘涨跌%（窗口未走完则为 null） */
    data class RecentAdvice(
        val date: String, val advice: Int, val close: Double,
        val r1: Double?, val r3: Double?, val r5: Double?
    )

    data class Result(
        val params: Params,
        val bars: List<Bar>,
        val trades: List<Trade>,
        val equityCurve: DoubleArray,
        val benchmarkCurve: DoubleArray,
        val trendShortLine: DoubleArray,
        val trendLongLine: DoubleArray,
        val cycleLine: DoubleArray,
        val advice: IntArray,
        val buyStat: AdviceStat,
        val sellStat: AdviceStat,
        val holdPosStat: AdviceStat,
        val holdEmptyStat: AdviceStat,
        val profile: TicketProfile,
        val recentAdvices: List<RecentAdvice>,
        val latestAdvice: Int,
        val latestDate: String,
        val latestClose: Double,
        val stats: Stats,
        /** 收盘时账户模拟状态（供 RecoIncremental.seed 跨日续算） */
        val simSnapshot: SimSnapshot
    )

    /** 账户模拟状态快照（与 AccountSim 可变字段一一对应，增量步进跨日续算用） */
    data class SimSnapshot(
        val cap0: Double, val capT: Double, val capB: Double,
        val cashT: Double, val shT: Long, val biT: Int, val biDate: String, val bpT: Double,
        val bwhy: String, val cashB: Double, val shB: Long,
        val pendT: Int, val pendB: Int, val whyT: String, val inBull: Boolean,
        /** 底仓买入日期（T+1 判定；旧存档缺失 → 空串=不约束） */
        val biDateB: String = ""
    )

    /** 每日荐股增量快照（推荐列表单票卡片所需全部字段） */
    data class RecoSnapshot(
        val params: Params,
        val profile: TicketProfile,
        val advice: Int,
        val date: String,
        val close: Double,
        val recentAdvices: List<RecentAdvice>,
        val buyStat: AdviceStat,
        val sellStat: AdviceStat,
        val holdPosStat: AdviceStat,
        val holdEmptyStat: AdviceStat
    )

    /** RecoIncremental 可持久化完整状态（存储层序列化；restore 后继续 step 与不中断连续 step 逐位一致） */
    data class RecoState(
        val params: Params,
        val bars: List<Bar>,
        val adviceHistory: List<Int>,
        val tsPrev: Double, val tsLast: Double,
        val tlPrev: Double, val tlLast: Double,
        val cyPrev2: Double, val cyPrev1: Double, val cyLast: Double,
        val tr30Last: Double, val cy30Last: Double,
        val sim: SimSnapshot,
        val noiseRecent: List<Double>,
        val pendingZ: List<PendingZEntry>,
        val hiCnt: Int, val hiSum3: Double, val hiSum5: Double,
        val loCnt: Int, val loSum5: Double,
        val cy30Hist: List<Double>,
        val closeSum: Double
    )

    /** 票性聚合待成熟样本（idx 日 z 值触发的高噪/超跌标记，idx+5≤n-2 时成熟计收益） */
    data class PendingZEntry(val idx: Int, val isHi: Boolean, val isLo: Boolean)

    // ═══════════════════════════════ 主入口 ═══════════════════════════════

    fun run(bars: List<Bar>, params: Params = Params()): Result {
        params.validate()?.let { throw IllegalArgumentException(it) }
        val n = bars.size
        require(n >= maxOf(params.cycleL, params.trendLong, 30) + 30) {
            "K线不足：混合策略至少需要 max(L,30)+30 根（当前 $n）"
        }
        val open = DoubleArray(n) { bars[it].open }
        val close = DoubleArray(n) { bars[it].close }

        // ── 逐 K 滚动 SSA（每档 L 独立滚动，与 SsaEngine 同口径；同 L 只滚一次）──
        val rollCache = HashMap<Int, Pair<DoubleArray, DoubleArray>>()
        fun roll(L: Int) = rollCache.getOrPut(L) { rolling(bars, L) }
        val ts = ema(roll(params.trendShort).first, params.smoothSpan)
        val tl = ema(roll(params.trendLong).first, params.smoothSpan)
        val cy = ema(roll(params.cycleL).second, params.smoothSpan)
        // 票性识别：固定 L=30+EMA2 口径（PC 全池校准），滞后5日无未来函数
        val p30 = roll(30)
        val profile = classify(bars, ema(p30.first, 2), ema(p30.second, 2))

        // ── 双账户模拟（run 全量与 RecoIncremental 增量步进共用 AccountSim，保证口径完全一致） ──
        val cap0 = max(100_000.0, open[0] * 100 * 1.2)
        val sim = AccountSim(params, cap0)
        val trades = sim.trades
        val equity = DoubleArray(n)
        val advice = IntArray(n) { HOLD_EMPTY }
        // 快速通道序列（ER10 + 唐奇安收盘通道）：仅依赖收盘价，无未来函数
        val erArr = if (params.fastErThr > 0.0) erSeries(close, FAST_ER_W)
                    else DoubleArray(n) { Double.NaN }
        val (donHi, donLo) = if (params.fastDonN > 0) donchianSeries(close, params.fastDonN)
                             else DoubleArray(n) { Double.NaN } to DoubleArray(n) { Double.NaN }

        for (t in 0 until n) {
            sim.executeOpen(t, bars[t].date, open[t])
            advice[t] = if (t == 0) HOLD_EMPTY else sim.signalAndAdvise(
                ts[t - 1], tl[t - 1], ts[t], tl[t],
                if (t >= 2) cy[t - 2] else Double.NaN,
                if (t >= 1) cy[t - 1] else Double.NaN,
                cy[t],
                closeCur = close[t], erCur = erArr[t], donHi = donHi[t], donLo = donLo[t]
            )
            equity[t] = sim.cashT + sim.shT * close[t] + sim.cashB + sim.shB * close[t]
        }
        if (sim.shT > 0L) {
            val px = close[n - 1]
            trades.add(Trade(sim.biT, sim.biDate, sim.bpT, n - 1, bars[n - 1].date, px,
                (px * (1 - params.commissionRate - params.stampDutyRate) /
                    (sim.bpT * (1 + params.commissionRate)) - 1) * 100,
                n - 1 - sim.biT, sim.bwhy, "期末平仓"))
        }

        // ── 建议命中率（次日开盘成交 → t+1/t+3/t+5 收盘）──
        fun hit(label: Int, wantUp: Boolean): AdviceStat {
            var cnt = 0; var w1 = 0; var w3 = 0; var sum5 = 0.0; var k5 = 0
            for (t in 0 until n) {
                if (advice[t] != label || t + 1 >= n) continue
                cnt++
                val op = open[t + 1]
                val r1 = (close[min(t + 1, n - 1)] / op - 1) * 100
                val r3 = (close[min(t + 3, n - 1)] / op - 1) * 100
                val r5 = (close[min(t + 5, n - 1)] / op - 1) * 100
                val ok1 = if (wantUp) r1 > 0 else r1 < 0
                val ok3 = if (wantUp) r3 > 0 else r3 < 0
                if (ok1) w1++; if (ok3) w3++; sum5 += r5; k5++
            }
            return AdviceStat(cnt,
                if (cnt > 0) w1 * 100.0 / cnt else 0.0,
                if (cnt > 0) w3 * 100.0 / cnt else 0.0,
                if (k5 > 0) sum5 / k5 else 0.0)
        }
        val buySt = hit(BUY, true)
        val sellSt = hit(SELL, false)
        val hpSt = hit(HOLD_POS, true)
        val heSt = hit(HOLD_EMPTY, false)

        // ── 绩效 ──
        val total = (equity[n - 1] / cap0 - 1) * 100
        val bench = (close[n - 1] / close[0] - 1) * 100
        var peak = equity[0]; var mdd = 0.0
        for (e in equity) { peak = max(peak, e); mdd = min(mdd, (e / peak - 1) * 100) }
        val wins = trades.filter { it.returnPct > 0 }
        val gw = trades.filter { it.returnPct > 0 }.sumOf { it.returnPct }
        val gl = -trades.filter { it.returnPct <= 0 }.sumOf { it.returnPct }
        val pf = if (gl > 1e-9) gw / gl else Double.POSITIVE_INFINITY
        val stats = Stats(
            totalReturnPct = total, benchmarkReturnPct = bench, excessReturnPct = total - bench,
            maxDrawdownPct = mdd,
            winRatePct = if (trades.isNotEmpty()) wins.size * 100.0 / trades.size else 0.0,
            profitFactor = pf, tradeCount = trades.size,
            avgHoldBars = if (trades.isNotEmpty()) (trades.sumOf { it.barsHeld }.toDouble() / trades.size).toInt() else 0,
            startDate = bars[0].date, endDate = bars[n - 1].date, barCount = n
        )
        val benchCurve = DoubleArray(n) { cap0 * close[it] / close[0] }
        // 近 30 条建议快照（含已走完窗口的实际收益，供持仓池历史验证）
        val rec = ArrayList<RecentAdvice>(30)
        var t0 = n - 1
        while (t0 >= 0 && rec.size < 30) {
            val r1 = if (t0 + 1 < n) (close[t0 + 1] / close[t0] - 1) * 100 else null
            val r3 = if (t0 + 3 < n) (close[t0 + 3] / close[t0] - 1) * 100 else null
            val r5 = if (t0 + 5 < n) (close[t0 + 5] / close[t0] - 1) * 100 else null
            rec.add(RecentAdvice(bars[t0].date, advice[t0], close[t0], r1, r3, r5))
            t0--
        }
        rec.reverse()

        return Result(
            params = params, bars = bars, trades = trades,
            equityCurve = equity, benchmarkCurve = benchCurve,
            trendShortLine = ts, trendLongLine = tl, cycleLine = cy,
            advice = advice,
            buyStat = buySt, sellStat = sellSt, holdPosStat = hpSt, holdEmptyStat = heSt,
            profile = profile,
            recentAdvices = rec,
            latestAdvice = advice[n - 1], latestDate = bars[n - 1].date, latestClose = close[n - 1],
            stats = stats,
            simSnapshot = sim.snap()
        )
    }

    /** 参数网格扫描行（单票寻优：每组合的绩效摘要） */
    data class ScanRow(
        val params: Params, val total: Double, val mdd: Double,
        val winRate: Double, val tradeCount: Int
    )

    /**
     * 参数网格扫描（单票寻优）：trendShort/trendLong ∈ trendGrid（短<长）× cycleL ∈ cycleGrid × smoothSpan ∈ spanGrid。
     * 同档 L 的滚动 SSA 只分解一次（原始 trend/cycle 序列全 span 共享，各 span 仅重做 EMA 平滑），
     * 组合仅做轻量账户模拟，手机秒级完成。
     * 底仓、费率沿用 baseParams；返回按总收益降序。
     */
    fun scanGrid(
        bars: List<Bar>, baseParams: Params = Params(),
        trendGrid: List<Int> = listOf(5, 10, 20, 30, 60),
        cycleGrid: List<Int> = listOf(20, 30, 60),
        spanGrid: List<Int> = listOf(baseParams.smoothSpan)
    ): List<ScanRow> {
        val n = bars.size
        require(n >= 90) { "K线不足：寻优至少需要 90 根（当前 $n）" }
        val open = DoubleArray(n) { bars[it].open }
        val close = DoubleArray(n) { bars[it].close }
        val cap0 = max(100_000.0, open[0] * 100 * 1.2)
        // 快速通道序列与 span 无关，循环外只算一次（口径与 run() 一致）
        val erArr = if (baseParams.fastErThr > 0.0) erSeries(close, FAST_ER_W)
                    else DoubleArray(n) { Double.NaN }
        val (donHiArr, donLoArr) = if (baseParams.fastDonN > 0) donchianSeries(close, baseParams.fastDonN)
                                   else DoubleArray(n) { Double.NaN } to DoubleArray(n) { Double.NaN }
        // 同 L 只滚动分解一次，原始序列全组合全 span 共享
        val rollCache = HashMap<Int, Pair<DoubleArray, DoubleArray>>()
        for (L in (trendGrid + cycleGrid).distinct()) rollCache[L] = rolling(bars, L)
        val combos = ArrayList<Triple<Int, Int, Int>>()
        val ls = trendGrid.distinct().sorted()
        for (s in ls) for (l in ls) if (s < l) for (c in cycleGrid.distinct()) combos.add(Triple(s, l, c))
        val rows = ArrayList<ScanRow>()
        for (span in spanGrid.distinct()) {
            val trEma = HashMap<Int, DoubleArray>()
            val cyEma = HashMap<Int, DoubleArray>()
            for ((L, r) in rollCache) { trEma[L] = ema(r.first, span); cyEma[L] = ema(r.second, span) }
            for ((s, l, c) in combos) {
                if (maxOf(s, l, c) + 30 > n) continue   // 预热不足的组合跳过
                val p = baseParams.copy(trendShort = s, trendLong = l, cycleL = c, smoothSpan = span)
                val sim = AccountSim(p, cap0)
                val ts = trEma.getValue(s); val tl = trEma.getValue(l); val cy = cyEma.getValue(c)
                val equity = DoubleArray(n)
                for (t in 0 until n) {
                    sim.executeOpen(t, bars[t].date, open[t])
                    if (t > 0) sim.signalAndAdvise(
                        ts[t - 1], tl[t - 1], ts[t], tl[t],
                        if (t >= 2) cy[t - 2] else Double.NaN,
                        if (t >= 1) cy[t - 1] else Double.NaN,
                        cy[t],
                        closeCur = close[t], erCur = erArr[t],
                        donHi = donHiArr[t], donLo = donLoArr[t]
                    )
                    equity[t] = sim.cashT + sim.shT * close[t] + sim.cashB + sim.shB * close[t]
                }
                var peak = equity[0]; var mdd = 0.0
                for (e in equity) { peak = max(peak, e); mdd = min(mdd, (e / peak - 1) * 100) }
                val trades = sim.trades
                val wins = trades.count { it.returnPct > 0 }
                rows.add(ScanRow(p, (equity[n - 1] / cap0 - 1) * 100, mdd,
                    if (trades.isNotEmpty()) wins * 100.0 / trades.size else 0.0, trades.size))
            }
        }
        rows.sortByDescending { it.total }
        return rows
    }

    // ═════════════════════════ 每日荐股增量引擎 ═════════════════════════

    /** 双账户模拟器（T 波段仓 + B 底仓）：run() 全量与 RecoIncremental 增量步进共用，保证口径完全一致 */
    private class AccountSim(val params: Params, val cap0: Double) {
        val capT = cap0 * (1.0 - params.baseFraction)
        val capB = cap0 * params.baseFraction
        var cashT = capT; var shT = 0L; var biT = -1; var biDate = ""; var bpT = 0.0; var bwhy = ""
        var cashB = capB; var shB = 0L; var biDateB = ""
        var pendT = 0; var pendB = 0; var whyT = ""
        var inBull = false
        /** 本挂单是否因 T+1 未解锁顺延过（成交时在离场原因上标注） */
        private var t1Deferred = false
        val trades = ArrayList<Trade>()

        /** 交易日（取日期部分；1H bar.date 形如 "2026-10-21 15:00:00"） */
        private fun dayOf(date: String): String = date.trim().substringBefore(' ')

        /** T+1：卖出执行日必须严格晚于买入日（同一交易日不可卖） */
        private fun t1AllowsSell(execDate: String, buyDate: String): Boolean {
            val buyDay = dayOf(buyDate)
            return buyDay.isEmpty() || dayOf(execDate) > buyDay
        }

        /**
         * t 根开盘：执行上一根挂单并清挂单。
         *
         * T+1：卖出未解锁时**不清挂单**，顺延到下一根（下一交易日首根）开盘继续尝试。
         */
        fun executeOpen(tIdx: Int, date: String, px: Double) {
            // ── T 波段仓 ──
            if (pendT == 1 && shT == 0L) {
                val lot = (cashT / (1 + params.commissionRate) / px / 100).toInt() * 100
                if (lot > 0) {
                    cashT -= lot * px * (1 + params.commissionRate)
                    shT = lot.toLong(); biT = tIdx; biDate = date; bpT = px
                }
                pendT = 0; t1Deferred = false
            } else if (pendT == 2 && shT > 0L) {
                if (t1AllowsSell(date, biDate)) {
                    cashT += shT * px * (1 - params.commissionRate - params.stampDutyRate)
                    trades.add(Trade(biT, biDate, bpT, tIdx, date, px,
                        (px * (1 - params.commissionRate - params.stampDutyRate) /
                            (bpT * (1 + params.commissionRate)) - 1) * 100,
                        tIdx - biT, bwhy, if (t1Deferred) "$whyT（T+1顺延）" else whyT))
                    shT = 0; pendT = 0; t1Deferred = false
                } else {
                    t1Deferred = true   // 当日买入，T+1 未解锁 → 卖单顺延
                }
            } else {
                pendT = 0; t1Deferred = false
            }
            // ── 底仓账户成交（只认金叉/死叉，同样受 T+1 约束）──
            if (pendB == 1 && shB == 0L) {
                val lot = (cashB / (1 + params.commissionRate) / px / 100).toInt() * 100
                if (lot > 0) {
                    cashB -= lot * px * (1 + params.commissionRate); shB = lot.toLong(); biDateB = date
                }
                pendB = 0
            } else if (pendB == 2 && shB > 0L) {
                if (t1AllowsSell(date, biDateB)) {
                    cashB += shB * px * (1 - params.commissionRate - params.stampDutyRate); shB = 0; pendB = 0
                }
            } else {
                pendB = 0
            }
            if (pendT == 0) whyT = ""
        }

        /** t 日收盘信号（trend/cycle 平滑值的当前与历史值，无未来函数），返回当日对次日的建议 */
        fun signalAndAdvise(
            tsPrev: Double, tlPrev: Double, tsCur: Double, tlCur: Double,
            cyPrev2: Double, cyPrev1: Double, cyCur: Double,
            closeCur: Double = Double.NaN,
            erCur: Double = Double.NaN,
            donHi: Double = Double.NaN,
            donLo: Double = Double.NaN
        ): Int {
            var dead = false
            var windowOpen = false
            if (!tsPrev.isNaN() && !tlPrev.isNaN() && !tsCur.isNaN() && !tlCur.isNaN()) {
                dead = tsPrev >= tlPrev && tsCur < tlCur
            }
            // 买入窗口：短trend斜率向上（低延迟，替代金叉排列判定）；
            // 斜率由负转正的瞬间 = 低延迟版金叉事件（底仓建仓点）
            if (!tsPrev.isNaN() && !tsCur.isNaN()) {
                windowOpen = !inBull && tsCur > tsPrev
                inBull = tsCur > tsPrev
            }
            var trough = false; var peak = false
            if (!cyPrev2.isNaN() && !cyPrev1.isNaN() && !cyCur.isNaN()) {
                trough = cyPrev2 > cyPrev1 && cyPrev1 < cyCur
                peak = cyPrev2 < cyPrev1 && cyPrev1 > cyCur
            }
            if (params.cycleOnly) {
                // 仅跟随 cycle 拐点：峰顶卖、谷底买；不看 trend 斜率、不加快通道
                if (shT > 0L) {
                    if (peak) { pendT = 2; whyT = "周期峰顶" }
                } else if (trough) {
                    pendT = 1; bwhy = "周期谷底"
                }
                return when {
                    pendT == 1 -> BUY
                    pendT == 2 -> SELL
                    shT > 0L || shB > 0L -> HOLD_POS
                    else -> HOLD_EMPTY
                }
            }
            if (shT > 0L) {
                when {
                    dead -> { pendT = 2; whyT = "趋势死叉"; pendB = 2 }
                    peak -> { pendT = 2; whyT = "周期峰顶" }
                }
            } else {
                when {
                    windowOpen -> { pendT = 1; bwhy = "斜率转正"; pendB = 1 }
                    inBull && trough -> { pendT = 1; bwhy = "谷底买回" }
                }
            }
            // ── 快速通道覆盖（2026-10-21 全池回测：ER10≥0.6 + 唐奇安15根，急涨急跌抢先反应）──
            if (params.fastErThr > 0.0 && params.fastDonN > 0 &&
                !closeCur.isNaN() && !erCur.isNaN() && !donHi.isNaN() && !donLo.isNaN() &&
                erCur >= params.fastErThr
            ) {
                if (shT > 0L) {
                    if (params.fastSell && closeCur < donLo) {
                        pendT = 2; whyT = "快通道·跌破通道"; pendB = 2
                    }
                } else if (closeCur > donHi) {
                    pendT = 1; bwhy = "快通道·突破通道"; pendB = 1
                }
            }
            return when {
                pendT == 1 -> BUY
                pendT == 2 -> SELL
                shT > 0L || shB > 0L -> HOLD_POS
                else -> HOLD_EMPTY
            }
        }

        /** 导出账户状态快照（持久化/续算用） */
        fun snap(): SimSnapshot = SimSnapshot(
            cap0, capT, capB, cashT, shT, biT, biDate, bpT, bwhy, cashB, shB, pendT, pendB, whyT, inBull, biDateB
        )

        companion object {
            /** 从 run() 结束时的快照恢复（增量续算用） */
            fun from(p: Params, s: SimSnapshot): AccountSim {
                val sim = AccountSim(p, s.cap0)
                sim.cashT = s.cashT; sim.shT = s.shT; sim.biT = s.biT; sim.biDate = s.biDate
                sim.bpT = s.bpT; sim.bwhy = s.bwhy
                sim.cashB = s.cashB; sim.shB = s.shB; sim.biDateB = s.biDateB
                sim.pendT = s.pendT; sim.pendB = s.pendB; sim.whyT = s.whyT
                sim.inBull = s.inBull
                return sim
            }
        }
    }

    /**
     * ## 每日荐股增量引擎（seed 全量一次 + step 每日步进，历史永不重算）
     *
     * [seed] 内部跑一次 [run] 建立状态；此后每天对新增 K 每档 L 只做 1 次 SSA 分解 + EMA 递推 + 账户步进，
     * 票性聚合逐日增量维护，与全量 [run] 结果逐位一致（单测守护）。
     * 命中率/近30条建议按 run() 同口径每日对建议全史重算（O(n)/票，平凡开销）。
     *
     * ⚠️ step 的 K 日期必须严格递增；Params 构造后不可变；票性 label 迁移导致参数切换时，
     *    由上层用存储全历史重新 seed（罕见事件，秒级/票）。
     */
    class RecoIncremental private constructor(
        val params: Params,
        private val bars: ArrayList<Bar>,
        simSnapshot: SimSnapshot
    ) {
        private val sim = AccountSim.from(params, simSnapshot)
        private var tsPrev = Double.NaN; private var tsLast = Double.NaN
        private var tlPrev = Double.NaN; private var tlLast = Double.NaN
        private var cyPrev2 = Double.NaN; private var cyPrev1 = Double.NaN; private var cyLast = Double.NaN
        private var tr30Last = Double.NaN; private var cy30Last = Double.NaN
        private val adviceHistory = ArrayList<Int>()

        // ── 票性聚合增量状态（与 classify() 逐位一致） ──
        private val noiseRecent = ArrayDeque<Double>()
        private class PendingZ(val idx: Int, val isHi: Boolean, val isLo: Boolean)
        private val pendingZ = ArrayList<PendingZ>()
        private var hiCnt = 0; private var hiSum3 = 0.0; private var hiSum5 = 0.0
        private var loCnt = 0; private var loSum5 = 0.0
        private val cy30Hist = ArrayList<Double>()
        private var closeSum = 0.0

        companion object {
            /** 全量构建：跑一次 run() 建立增量状态（一次性成本 = 全量分析一次） */
            fun seed(bars: List<Bar>, params: Params): RecoIncremental {
                val r = run(bars, params)
                val n = bars.size
                val inc = RecoIncremental(params, ArrayList(bars), r.simSnapshot)
                inc.tsLast = r.trendShortLine[n - 1]; inc.tsPrev = r.trendShortLine[n - 2]
                inc.tlLast = r.trendLongLine[n - 1]; inc.tlPrev = r.trendLongLine[n - 2]
                inc.cyLast = r.cycleLine[n - 1]; inc.cyPrev1 = r.cycleLine[n - 2]; inc.cyPrev2 = r.cycleLine[n - 3]
                inc.adviceHistory.addAll(r.advice.toList())
                // 票性聚合重放（L=30+EMA2 固定口径，与 classify 完全同序同式）
                val (tr30, cy30) = rolling(bars, 30)
                val tr30e = ema(tr30, 2); val cy30e = ema(cy30, 2)
                for (t in 0 until n) {
                    inc.closeSum += bars[t].close
                    if (!tr30e[t].isNaN() && !cy30e[t].isNaN()) {
                        inc.cy30Hist.add(cy30e[t])
                        inc.pushNoise(t, bars[t].close - tr30e[t] - cy30e[t])
                    }
                    inc.maturePending(t + 1)
                }
                inc.tr30Last = tr30e[n - 1]; inc.cy30Last = cy30e[n - 1]
                return inc
            }

            /** 从持久化状态恢复（不做任何 SSA 重算，O(n) 拷贝；与连续 step 逐位一致，单测守护） */
            fun restore(s: RecoState): RecoIncremental {
                require(s.bars.isNotEmpty()) { "恢复失败：bars 为空" }
                require(s.bars.size == s.adviceHistory.size) {
                    "恢复失败：bars(${s.bars.size}) 与建议史(${s.adviceHistory.size})长度不一致"
                }
                val inc = RecoIncremental(s.params, ArrayList(s.bars), s.sim)
                inc.tsPrev = s.tsPrev; inc.tsLast = s.tsLast
                inc.tlPrev = s.tlPrev; inc.tlLast = s.tlLast
                inc.cyPrev2 = s.cyPrev2; inc.cyPrev1 = s.cyPrev1; inc.cyLast = s.cyLast
                inc.tr30Last = s.tr30Last; inc.cy30Last = s.cy30Last
                inc.adviceHistory.addAll(s.adviceHistory)
                inc.noiseRecent.addAll(s.noiseRecent)
                s.pendingZ.forEach { inc.pendingZ.add(PendingZ(it.idx, it.isHi, it.isLo)) }
                inc.hiCnt = s.hiCnt; inc.hiSum3 = s.hiSum3; inc.hiSum5 = s.hiSum5
                inc.loCnt = s.loCnt; inc.loSum5 = s.loSum5
                inc.cy30Hist.addAll(s.cy30Hist)
                inc.closeSum = s.closeSum
                return inc
            }
        }

        /** 追加新 K 并步进（可一次多根），返回最新快照。日期必须严格递增。 */
        fun step(newBars: List<Bar>): RecoSnapshot {
            require(newBars.isNotEmpty()) { "step 需要至少 1 根新K" }
            for (nb in newBars) {
                require(nb.date > bars.last().date) { "新K日期须严格递增：${nb.date}" }
                bars.add(nb)
                val n = bars.size
                val close = DoubleArray(n) { bars[it].close }
                val cache = HashMap<Int, SsaEngine.Decomp>()
                fun dec(L: Int): SsaEngine.Decomp = cache.getOrPut(L) { SsaEngine.decompose(close, L) }
                // 信号链 EMA 递推（新增 1 根 K → 每档 L 只取末端值，历史值不变）
                tsPrev = tsLast; tsLast = emaStep(tsLast, dec(params.trendShort).trend[n - 1], params.smoothSpan)
                tlPrev = tlLast; tlLast = emaStep(tlLast, dec(params.trendLong).trend[n - 1], params.smoothSpan)
                cyPrev2 = cyPrev1; cyPrev1 = cyLast
                cyLast = emaStep(cyLast, dec(params.cycleL).cycle[n - 1], params.smoothSpan)
                tr30Last = emaStep(tr30Last, dec(30).trend[n - 1], 2)
                cy30Last = emaStep(cy30Last, dec(30).cycle[n - 1], 2)
                val t = n - 1
                sim.executeOpen(t, nb.date, nb.open)
                // 快速通道标量（与 run() 序列同口径）
                val erCur = if (params.fastErThr > 0.0) erAt(close, t, FAST_ER_W) else Double.NaN
                val (donHi, donLo) = if (params.fastDonN > 0) donAt(close, t, params.fastDonN)
                                     else Double.NaN to Double.NaN
                val adv = sim.signalAndAdvise(
                    tsPrev, tlPrev, tsLast, tlLast, cyPrev2, cyPrev1, cyLast,
                    closeCur = nb.close, erCur = erCur, donHi = donHi, donLo = donLo
                )
                adviceHistory.add(adv)
                // 票性聚合增量
                closeSum += nb.close
                if (!tr30Last.isNaN() && !cy30Last.isNaN()) {
                    cy30Hist.add(cy30Last)
                    pushNoise(t, nb.close - tr30Last - cy30Last)
                }
                maturePending(n)
            }
            return snapshot()
        }

        /** 当前快照（不追加K，可重复调用）——hit/近30条与 run() 完全同口径 */
        fun snapshot(): RecoSnapshot {
            val n = bars.size
            val open = DoubleArray(n) { bars[it].open }
            val close = DoubleArray(n) { bars[it].close }
            fun hit(label: Int, wantUp: Boolean): AdviceStat {
                var cnt = 0; var w1 = 0; var w3 = 0; var sum5 = 0.0; var k5 = 0
                for (t in 0 until n) {
                    if (adviceHistory[t] != label || t + 1 >= n) continue
                    cnt++
                    val op = open[t + 1]
                    val r1 = (close[min(t + 1, n - 1)] / op - 1) * 100
                    val r3 = (close[min(t + 3, n - 1)] / op - 1) * 100
                    val r5 = (close[min(t + 5, n - 1)] / op - 1) * 100
                    val ok1 = if (wantUp) r1 > 0 else r1 < 0
                    val ok3 = if (wantUp) r3 > 0 else r3 < 0
                    if (ok1) w1++; if (ok3) w3++; sum5 += r5; k5++
                }
                return AdviceStat(cnt,
                    if (cnt > 0) w1 * 100.0 / cnt else 0.0,
                    if (cnt > 0) w3 * 100.0 / cnt else 0.0,
                    if (k5 > 0) sum5 / k5 else 0.0)
            }
            val rec = ArrayList<RecentAdvice>(30)
            var t0 = n - 1
            while (t0 >= 0 && rec.size < 30) {
                val r1 = if (t0 + 1 < n) (close[t0 + 1] / close[t0] - 1) * 100 else null
                val r3 = if (t0 + 3 < n) (close[t0 + 3] / close[t0] - 1) * 100 else null
                val r5 = if (t0 + 5 < n) (close[t0 + 5] / close[t0] - 1) * 100 else null
                rec.add(RecentAdvice(bars[t0].date, adviceHistory[t0], close[t0], r1, r3, r5))
                t0--
            }
            rec.reverse()
            return RecoSnapshot(
                params = params,
                profile = computeProfile(),
                advice = adviceHistory.last(),
                date = bars.last().date,
                close = bars.last().close,
                recentAdvices = rec,
                buyStat = hit(BUY, true),
                sellStat = hit(SELL, false),
                holdPosStat = hit(HOLD_POS, true),
                holdEmptyStat = hit(HOLD_EMPTY, false)
            )
        }

        internal fun adviceHistoryCopy(): List<Int> = adviceHistory.toList()
        internal fun barsCopy(): List<Bar> = bars.toList()

        /** 导出完整可持久化状态（restore 后继续 step 与不中断 step 逐位一致） */
        fun exportState(): RecoState = RecoState(
            params = params, bars = bars.toList(), adviceHistory = adviceHistory.toList(),
            tsPrev = tsPrev, tsLast = tsLast, tlPrev = tlPrev, tlLast = tlLast,
            cyPrev2 = cyPrev2, cyPrev1 = cyPrev1, cyLast = cyLast,
            tr30Last = tr30Last, cy30Last = cy30Last,
            sim = sim.snap(),
            noiseRecent = noiseRecent.toList(),
            pendingZ = pendingZ.map { PendingZEntry(it.idx, it.isHi, it.isLo) },
            hiCnt = hiCnt, hiSum3 = hiSum3, hiSum5 = hiSum5,
            loCnt = loCnt, loSum5 = loSum5,
            cy30Hist = cy30Hist.toList(), closeSum = closeSum
        )

        /** EMA 单步递推（与 [ema] 逐位一致：span≤1 原样返回） */
        private fun emaStep(last: Double, v: Double, span: Int): Double {
            if (v.isNaN()) return last
            if (last.isNaN()) return v
            if (span <= 1) return v
            val alpha = 2.0 / (span + 1)
            return alpha * v + (1 - alpha) * last
        }

        private fun pushNoise(idx: Int, noiseVal: Double) {
            noiseRecent.addLast(noiseVal)
            if (noiseRecent.size > 20) noiseRecent.removeFirst()
            val z = if (noiseRecent.size >= 2) {
                val m = noiseRecent.sum() / noiseRecent.size
                var s = 0.0; for (v in noiseRecent) s += (v - m) * (v - m)
                val sd = sqrt(s / (noiseRecent.size - 1))
                if (sd > 1e-9) noiseVal / sd else Double.NaN
            } else Double.NaN
            if (!z.isNaN()) pendingZ.add(PendingZ(idx, z >= 1.0, z <= -2.0))
        }

        private fun maturePending(n: Int) {
            while (pendingZ.isNotEmpty() && pendingZ.first().idx + 5 <= n - 2) {
                val p = pendingZ.removeAt(0)
                if (p.isHi) {
                    hiCnt++
                    hiSum3 += (bars[p.idx + 3].close / bars[p.idx].close - 1) * 100
                    hiSum5 += (bars[p.idx + 5].close / bars[p.idx].close - 1) * 100
                }
                if (p.isLo) {
                    loCnt++
                    loSum5 += (bars[p.idx + 5].close / bars[p.idx].close - 1) * 100
                }
            }
        }

        /** 与 [classify] 逐位一致（两遍方差、同序求和） */
        private fun computeProfile(): TicketProfile {
            val n = bars.size
            val hi3 = if (hiCnt > 0) hiSum3 / hiCnt else 0.0
            val hi5 = if (hiCnt > 0) hiSum5 / hiCnt else 0.0
            val lo5 = if (loCnt > 0) loSum5 / loCnt else 0.0
            val cycAmp = if (cy30Hist.size > 20) {
                val m = cy30Hist.sum() / cy30Hist.size
                sqrt(cy30Hist.sumOf { (it - m) * (it - m) } / cy30Hist.size) / (closeSum / n)
            } else 0.0
            val label = when {
                hi5 > 0.6 && cycAmp > 0.04 -> "TREND"
                hi5 < -0.2 && cycAmp < 0.03 -> "OSCILLATION"
                hi5 >= -0.2 && hi5 < 0.4 && cycAmp < 0.025 -> "SLOWBULL"
                else -> "MIXED"
            }
            return TicketProfile(label, hi3, hi5, lo5, cycAmp, hiCnt, loCnt)
        }
    }

    // ═══════════════════════════════ 票性识别 ═══════════════════════════════

    /**
     * 票性识别（PC classify_ticket 同口径移植）：noise=close-trend-cycle（须已 EMA 平滑），
     * z=noise/尾部20根样本标准差；hi3/hi5=高噪样本(z≥1)后3/5日均收益、lo5=超跌样本(z≤-2)后5日均收益、
     * cycAmp=周期振幅/均价。前向窗口只取已走完的历史样本（t+5≤n-2，滞后5日），无未来函数。
     * 判据：TREND hi5>0.6 且 cycAmp>4%；OSCILLATION hi5<-0.2 且 cycAmp<3%；
     * SLOWBULL -0.2≤hi5<0.4 且 cycAmp<2.5%；其余 MIXED。
     */
    private fun classify(bars: List<Bar>, tr: DoubleArray, cy: DoubleArray): TicketProfile {
        val n = bars.size
        val close = DoubleArray(n) { bars[it].close }
        val noise = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) if (!tr[i].isNaN() && !cy[i].isNaN()) noise[i] = close[i] - tr[i] - cy[i]
        val recent = ArrayList<Double>(20)
        val hiR3 = ArrayList<Double>(); val hiR5 = ArrayList<Double>(); val loR5 = ArrayList<Double>()
        var hiCnt = 0; var loCnt = 0
        for (t in 0 until n) {
            if (noise[t].isNaN()) continue
            recent.add(noise[t]); if (recent.size > 20) recent.removeAt(0)
            val z = if (recent.size >= 2) {
                val m = recent.sum() / recent.size
                var s = 0.0; for (v in recent) s += (v - m) * (v - m)
                val sd = sqrt(s / (recent.size - 1))
                if (sd > 1e-9) noise[t] / sd else Double.NaN
            } else Double.NaN
            if (z.isNaN() || t + 5 > n - 2) continue
            if (z >= 1.0) {
                hiCnt++
                hiR3.add((close[t + 3] / close[t] - 1) * 100)
                hiR5.add((close[t + 5] / close[t] - 1) * 100)
            }
            if (z <= -2.0) {
                loCnt++
                loR5.add((close[t + 5] / close[t] - 1) * 100)
            }
        }
        fun mean(a: List<Double>) = if (a.isEmpty()) 0.0 else a.sum() / a.size
        val hi3 = mean(hiR3); val hi5 = mean(hiR5); val lo5 = mean(loR5)
        val validCy = cy.filter { !it.isNaN() }
        val cycAmp = if (validCy.size > 20) {
            val m = validCy.sum() / validCy.size
            sqrt(validCy.sumOf { (it - m) * (it - m) } / validCy.size) / (close.sum() / n)
        } else 0.0
        val label = when {
            hi5 > 0.6 && cycAmp > 0.04 -> "TREND"
            hi5 < -0.2 && cycAmp < 0.03 -> "OSCILLATION"
            hi5 >= -0.2 && hi5 < 0.4 && cycAmp < 0.025 -> "SLOWBULL"
            else -> "MIXED"
        }
        return TicketProfile(label, hi3, hi5, lo5, cycAmp, hiCnt, loCnt)
    }

    /** 仅票性识别的轻量入口：只滚动 L=30 一个窗口（约为全量 run 的 1/4 耗时），用于运行前自动配参 */
    fun classifyOnly(bars: List<Bar>): TicketProfile {
        require(bars.size >= 60) { "K线不足：票性识别至少需要 60 根（当前 ${bars.size}）" }
        val (tr, cy) = rolling(bars, 30)
        return classify(bars, ema(tr, 2), ema(cy, 2))
    }

    /** 差异化参数预填（param_advice_TREND_MIXED.md 全池回测结论固化）：
     *  TREND 进取档 t5/10+c60；MIXED/OSCILLATION 防守档 t30/60+c60；SLOWBULL t30/60+c60+底仓30%。 */
    fun recommendedParams(label: String): Params = when (label) {
        "TREND" -> Params(trendShort = 5, trendLong = 10, cycleL = 60)
        "MIXED", "OSCILLATION" -> Params(trendShort = 30, trendLong = 60, cycleL = 60)
        "SLOWBULL" -> Params(trendShort = 30, trendLong = 60, cycleL = 60, baseFraction = 0.3)
        else -> Params()
    }

    // ═══════════════════════════════ 滚动 SSA ═══════════════════════════════

    /** 逐 K 用 [0..t] 历史独立分解，返回该 L 的 (trendAt, cycleAt)，暖机段为 NaN */
    private fun rolling(bars: List<Bar>, L: Int): Pair<DoubleArray, DoubleArray> {
        val n = bars.size
        val tr = DoubleArray(n) { Double.NaN }
        val cy = DoubleArray(n) { Double.NaN }
        val close = DoubleArray(n) { bars[it].close }
        for (t in L until n) {
            val seg = close.copyOfRange(0, t + 1)
            val dec = SsaEngine.decompose(seg, L)
            tr[t] = dec.trend[seg.size - 1]
            cy[t] = dec.cycle[seg.size - 1]
        }
        return tr to cy
    }

    /** EMA 平滑（只用历史值，无未来函数；NaN 段跳过，首个有效值作种子） */
    private fun ema(a: DoubleArray, span: Int): DoubleArray {
        if (span <= 1) return a
        val alpha = 2.0 / (span + 1)
        val out = DoubleArray(a.size) { Double.NaN }
        var last = Double.NaN
        for (i in a.indices) {
            val v = a[i]
            if (v.isNaN()) continue
            last = if (last.isNaN()) v else alpha * v + (1 - alpha) * last
            out[i] = last
        }
        return out
    }

    // ═══════════════════════════ 快速通道指标 ═══════════════════════════

    /** Kaufman 效率系数序列：ER = |c[t]-c[t-w]| / Σ|Δc|，t<w 为 NaN（0=纯震荡，1=单边） */
    private fun erSeries(close: DoubleArray, w: Int): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        for (t in w until n) out[t] = erAt(close, t, w)
        return out
    }

    /** 单点 ER（增量 step 用，与 [erSeries] 同口径） */
    private fun erAt(close: DoubleArray, t: Int, w: Int): Double {
        if (w <= 0 || t < w || t >= close.size) return Double.NaN
        var sum = 0.0
        for (i in t - w + 1..t) sum += abs(close[i] - close[i - 1])
        return if (sum <= 1e-12) 0.0 else abs(close[t] - close[t - w]) / sum
    }

    /** 唐奇安收盘通道：hi/lo = 前 n 根（不含当前）close 的最高/最低，t<n 为 NaN */
    private fun donchianSeries(close: DoubleArray, n: Int): Pair<DoubleArray, DoubleArray> {
        val len = close.size
        val hi = DoubleArray(len) { Double.NaN }
        val lo = DoubleArray(len) { Double.NaN }
        for (t in n until len) {
            val (h, l) = donAt(close, t, n)
            hi[t] = h; lo[t] = l
        }
        return hi to lo
    }

    /** 单点唐奇安（增量 step 用，与 [donchianSeries] 同口径） */
    private fun donAt(close: DoubleArray, t: Int, n: Int): Pair<Double, Double> {
        if (n <= 0 || t < n || t >= close.size) return Double.NaN to Double.NaN
        var h = Double.NEGATIVE_INFINITY
        var l = Double.POSITIVE_INFINITY
        for (i in t - n until t) {
            h = max(h, close[i]); l = min(l, close[i])
        }
        return h to l
    }
}
