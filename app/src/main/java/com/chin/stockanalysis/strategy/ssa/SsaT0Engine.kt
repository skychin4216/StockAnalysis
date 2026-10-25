package com.chin.stockanalysis.strategy.ssa

import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ## 三层周期 SSA 稳态做T回测引擎（纯 Kotlin）
 *
 * 主体层 = 小时线**三态机**（UP / WINDOW / FLAT），以「中位线 vs 趋势线」关系定状态：
 * 因 mid = trend + cycle，「中位线在趋势线上方」等价于 cycle>0。
 *   UP     (cycle>0)：核心做多，直至 cycle 下穿 0 全仓离场；上升中超极限卖、破极限买回做T。
 *   WINDOW (cycle<0 且买入窗口开)：价格上穿中位线买、触正常上沿卖。
 *   FLAT   (cycle<0 且窗口关)：空仓，仅价格破极限下沿买T、超极限上沿卖T。
 * 日线/周线层仅作参考与统计口径，不参与主状态判定。
 *
 * 扰动区间（2026-10 新口径，实证校准）：
 * - **中线 = trend + cycle**（价格尺度平滑线，即该段趋势的中位价；SSA 未去均值，trend+cycle+noise==价格）。
 * - **σ = 残差噪声分量(close−中线)的滚动标准差**（tailStd）；噪声即 SSA 的 noise，天然无未来函数。
 * - 正常区间 = 中线 ± cycleMult·σ（默认 1.5σ，触轨做T阈值）；
 *   极限区间 = 中线 ± noiseMult·σ（默认 2.0σ，结构失效/硬止损阈值）。
 *
 * ⚠️ 铁律：
 * 1. 三层全部**固定长度滚动窗口**逐K重算 SSA，禁止一次性全量分解（全量=未来函数）。
 * 2. 信号在 t 收盘确认、t+1 开盘成交；1H 决策时能看到的日线/周线状态只可能是
 *    「已经收盘」的那一根（当日15:00前不用当日日K，当周未结束不用当周周K）。
 * 3. 区间由 t 收盘生成、供 t+1 使用，绝不引用未来K；触轨用当根 low/high 判定。
 * 4. A/B/C 是已收盘K的状态分类（A=正常区间内 B=极限区间内 C=突破极限区间），用于当下风控开关。
 * 5. 反T必须先有底仓，且**仅在中周期趋势非上行时允许**（实证：上行段反T高抛被趋势碾压）。
 * 6. **止损只保下轨**：上轨突破不强制平仓（主升段让利润奔跑），只有下轨破位才离场。
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

    /** 每层固定参数（窗口长度固定，不自动自适应）
     *  cycleMult = 正常区间 σ 倍数（触轨阈值）；noiseMult = 极限区间 σ 倍数（止损阈值）。 */
    data class LayerCfg(
        val embedL: Int,        // SSA 嵌入窗口 L
        val windowM: Int,       // 每次分解使用的固定滚动样本长度
        val lookbackN: Int,     // 波动标准差回看
        val cycleMult: Double = 1.5,   // 正常区间 = 中线 ± 1.5σ
        val noiseMult: Double = 2.0    // 极限区间 = 中线 ± 2.0σ
    )

    data class Params(
        val week: LayerCfg = LayerCfg(20, 80, 12),
        val day: LayerCfg = LayerCfg(30, 120, 20),
        val hour: LayerCfg = LayerCfg(30, 120, 20),
        val corePct: Double = 80.0,        // UP 段核心仓位（%权益）
        val tPct: Double = 30.0,           // UP 段极限带反向T 摆动比例（%持仓）
        val commissionRate: Double = 0.00025,
        val stampDutyRate: Double = 0.0005,
        val slippageRate: Double = 0.0005, // 滑点（双边各计）
        val trainRatio: Double = 0.70,
        /** 下跌趋势亏损削减方案（供对比）：0=基线 1=冻结核心仓 2=日线下行降仓 3=提前离场
         *  2026-10-20 全池回测采纳：默认 2（回撤 -27.31→-25.41pp，盈利票 +6） */
        val reduceMode: Int = 2,
        /** 试验规则：价格上穿中位线买入 / 下穿中位线卖出（为 true 时完全替代三态机，供对比） */
        val useMidCross: Boolean = false,
        /** 仅配合 useMidCross：盘中「触及中位线」即成交（挂单在 mid[t-1]，触及即按线价成交），不等 t+1 开盘 */
        val execAtTouch: Boolean = false,
        /** 仅配合 useMidCross：中位线 EMA 平滑跨度（1=不平滑），用于减少穿越误判 */
        val midSmoothSpan: Int = 1,
        /** 仅配合 useMidCross：买入需中位线在最近 K 根内上升（0=不启用），中位线下行时禁止买入 */
        val midRisingK: Int = 0,
        /** 试验规则：cycle 拐点驱动做T（为 true 时完全替代三态机，供对比）*/
        val useCycleRule: Boolean = false,
        /** 仅配合 useCycleRule：拐点确认窗口 1=3点反转立即确认 2=再等1根确认（4点单调）*/
        val cyConfirm: Int = 1,
        /** 仅配合 useCycleRule：启用上升段规则（谷底满仓 / 回中线补满 / 峰顶全清）*/
        val cyUpLeg: Boolean = true,
        /** 仅配合 useCycleRule：启用下降段规则（仅中位线上行且价上穿中线买；触极限/回买入点/回中线卖）*/
        val cyDownLeg: Boolean = true,
        /** 仅配合 useCycleRule：下降段买入需中位线在最近 K 根内上行（0=不启用）*/
        val cyMidRisingK: Int = 3,
        /** 仅配合 useCycleRule：cycle 信号层 "H"=小时线 / "D"=日线（日线更平滑、拐点更少、贴合大趋势）；
         *  日线信号在「该日K 首次可用于 1H 决策」的那根(通常当日 15:00)触发一次。*/
        val cyLayer: String = "H",
        /** 仅配合 useCycleRule：上升段 cycle 下穿 0（中位线下穿趋势线）也清仓（峰顶确认前的补充退出）*/
        val cyExitOnZero: Boolean = false,
        /** 三态机 WINDOW 段向下止损：0=关闭(基线) 1=跌破正常下沿(nDn)止损 2=跌破极限下沿(eDn)止损 */
        val winStopDn: Int = 0,
        /** 三态机 WINDOW 段买入仓位%（默认100=满仓） */
        val winPosPct: Double = 100.0,
        /** 三态机 WINDOW 段买入需日线趋势非下行（减少下跌段接盘） */
        val winDayUp: Boolean = false,
        /** WINDOW 段买入闸门·SNR：cycle 振幅/σ 需 ≥ 该值（0=关闭，默认关守恒基线） */
        val winMinSnr: Double = 0.0,
        /** 仅配合 winMinSnr：振幅回看根数（0=取 hour.embedL） */
        val winSnrK: Int = 0,
        /** WINDOW 段买入闸门·趋势强度：1H ADX(14) 需 ≥ 该值（0=关闭，默认关守恒基线） */
        val winMinAdx: Double = 0.0,
        /** UP 段卖出T仓按波动目标缩放：qty = tPct × min(1, σ_ref/σ_t)（仅缩放既有卖T数量，不新增卖出规则） */
        val volTargetT: Boolean = false,
        /** 仅配合 volTargetT：σ_ref 基准（价格尺度）；>0 直接使用，否则取全样本 σ 中位数 */
        val sigmaRefAbs: Double = 0.0,
        /** 三态机状态(UP/WINDOW/FLAT)判定所用的 cycle 层："H"=小时线(基线) "D"=日线(更平滑、状态更稳) */
        val regimeLayer: String = "H",
        /** 2026-10-20 全池回测采纳：UP 段超上限卖T需 RSI(tRsiPeriod) > tRsiThr 确认 */
        val tRsiConfirm: Boolean = false,
        val tRsiPeriod: Int = 14,
        val tRsiThr: Double = 70.0,
        /** 2026-10-20 全池回测采纳：ADX 动态核心仓+卖T量（>25 重仓少做T / <20 轻仓多做T） */
        val adxGate: Boolean = false,
        val adxPeriod: Int = 14,
        /** 2026-10-20 全池回测采纳：ER 自身时序分位入场闸（仅拦 UP 段买入；0=关闭，建议 70） */
        val erGate: Double = 0.0,
        /** 仅配合 erGate：Δ5ER ≤ 该值（自由落体）禁买 */
        val erD5: Double = -0.05,
        /** 仅配合 erGate：自身时序 ER 分位序列（[SsaErQuantile.erqArrays] 预计算注入，下标对齐 1H bars） */
        val erPctArr: DoubleArray? = null,
        /** 仅配合 erGate：个股原始 ER 序列（同上注入） */
        val erArr: DoubleArray? = null
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
        val dayUpAtOpen: Boolean,
        val exitReason: String
    )

    data class RiskEvent(val index: Int, val time: String, val level: String, val reason: String)

    // ═══════════════════════ 下一根操作建议 ═══════════════════════

    /** 建议动作常量（与 SsaHybridEngine 对齐，避免两页语义分裂） */
    const val BUY = 0
    const val SELL = 1
    const val HOLD_POS = 2
    const val HOLD_EMPTY = 3

    /** 末根「下一根操作建议」：state 用 regime 口径(1=UP/2=FLAT/3=WINDOW/0=暖机)，
     *  action ∈ {BUY/SELL/HOLD_POS/HOLD_EMPTY}，holdPct=末根实际持仓占权益%。 */
    data class Advice(
        val index: Int, val time: String, val state: Int,
        val action: Int, val reason: String, val close: Double, val holdPct: Double
    )

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
        /** 1H 主状态序列：1=UP(cycle>0) 2=FLAT 3=WINDOW，暖机=0（供图表画三态分区背景） */
        val regime: IntArray,
        val markers: List<Marker>,
        val trades: List<Trade>,
        val riskEvents: List<RiskEvent>,
        val equity: DoubleArray, val benchmark: DoubleArray, val drawdown: DoubleArray,
        val splitIndex: Int,
        val full: SegStats, val train: SegStats, val test: SegStats,
        val weekDown: WeekDownReport,
        val dayDown: WeekDownReport,
        val bandEval: BandEval,
        /** 回测末根的实际持仓占权益百分比（供「下一根操作建议」判断当前是否有底仓） */
        val finalHoldingPct: Double = 0.0
    )

    data class WeekDownReport(
        val trades: Int, val winRatePct: Double, val avgPnlPct: Double,
        val totalPnlPct: Double, val weeks: Int
    )

    /** 周期扰动区间评估：命中率 / 触轨后反转成功率 / 区间偏离误差（全部只用历史+下一根真实K） */
    data class BandEval(
        val layerName: String,
        val bars: Int,
        val insideRatePct: Double,          // 收盘价落在激进扰动区间内的比例
        val touchUp: Int, val touchUpReversalPct: Double,   // 触上沿次数 / 下一根收盘回落比例
        val touchDn: Int, val touchDnReversalPct: Double,   // 触下沿次数 / 下一根收盘反弹比例
        val avgDevPct: Double               // 收盘价相对中线（前收）的平均绝对偏离%
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

        // erGate 自动接线：erGate>0 且未手动注入数组时，用 1H 收盘价现算 erq（自身时序分位）——单票自洽
        val p = if (params.erGate > 0.0 && (params.erPctArr == null || params.erArr == null)) {
            val closes = DoubleArray(hourBars.size) { hourBars[it].close }
            val (pct, er) = SsaErQuantile.erqArrays(closes)
            params.copy(erPctArr = pct, erArr = er)
        } else params

        val w = buildLayer(weekBars, params.week)
        val d = buildLayer(dayBars, params.day)
        val h = buildLayer(hourBars, params.hour)

        val reg = if (params.regimeLayer.equals("D", ignoreCase = true))
            regimes(dailyCycleOnHour(h, d))
        else regimes(DoubleArray(h.bars.size) { h.mid[it] - h.trend[it] })
        val sim = simulate(h, d, w, reg, p)
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

        // 日线下跌环境独立绩效：开仓时「日线趋势 5 根斜率向下」的 T 单（下跌趋势亏损归因桶）
        val downD = sim.trades.filter { !it.dayUpAtOpen }
        val dayDown = WeekDownReport(
            trades = downD.size,
            winRatePct = if (downD.isEmpty()) 0.0 else downD.count { it.returnPct > 0 }.toDouble() / downD.size * 100,
            avgPnlPct = if (downD.isEmpty()) 0.0 else downD.map { it.returnPct }.average(),
            totalPnlPct = downD.sumOf { it.returnPct },
            weeks = countWeeks(h.bars, 0, h.bars.size)
        )

        val lastIdx = h.bars.size - 1
        val holdPct = if (sim.equity[lastIdx] > 0)
            sim.finalShares * h.bars[lastIdx].close / sim.equity[lastIdx] * 100 else 0.0
        return Result(params, h, d, w, reg, sim.markers, sim.trades, sim.riskEvents,
            sim.equity, sim.benchmark, sim.drawdown, split,
            full, train, test, weekDown, dayDown, bandEval(h), holdPct)
    }

    /**
     * 末根「下一根操作建议」：对最后一根已收盘 K 复现 [simulate] 的 `when(reg[last])` 决策，
     * 用末根实际持仓 [Result.finalHoldingPct] 判断"是否有底仓"。
     *
     * 支持边界：默认三态机（含 winHalf=winPosPct、reduceMode∈{0,1,2}）完整支持；
     * useCycleRule / useMidCross / reduceMode==3 支线仅返回 HOLD_EMPTY + 提示（以回测末段成交为准）。
     */
    fun adviceOf(r: Result): Advice {
        val h = r.h; val bars = h.bars; val n = bars.size
        val last = n - 1
        val p = r.params
        val close = bars[last].close
        val time = labelOf(bars[last])
        val state = r.regime[last]
        val hold = r.finalHoldingPct
        fun of(a: Int, reason: String) = Advice(last, time, state, a, reason, close, hold)

        if (p.useCycleRule) return of(HOLD_EMPTY, "当前为「日线cycle拐点」规则，请以回测末段成交为准")
        if (p.useMidCross) return of(HOLD_EMPTY, "当前为「中线上穿」规则，请以回测末段成交为准")
        if (p.reduceMode == 3) return of(HOLD_EMPTY, "当前为「提前离场」规则，请以回测末段成交为准")
        if (state == 0) return of(HOLD_EMPTY, "暖机中·观望")

        val mid = h.mid[last]; val nUp = h.nUp[last]; val nDn = h.nDn[last]
        val eUp = h.eUp[last]; val eDn = h.eDn[last]
        val empty = hold <= 1e-9
        // 镜像 simulate() 的 WINDOW 买入闸门（SNR/ADX 阈值 0=关闭 → 恒 true，基线守恒）
        val winGateOkL: Boolean = if (p.winMinSnr <= 0.0 && p.winMinAdx <= 0.0) true else {
            val cmL = p.hour.cycleMult
            val sigmaL = if (cmL > 0.0) (h.nUp[last] - h.mid[last]) / cmL else Double.NaN
            val snrKL = if (p.winSnrK > 0) p.winSnrK else p.hour.embedL
            var mx = Double.NEGATIVE_INFINITY; var mn = Double.POSITIVE_INFINITY; var c = 0
            for (i in max(0, last - snrKL + 1)..min(last, h.cycle.size - 1)) {
                val v = h.cycle[i]; if (!v.isNaN()) { if (v > mx) mx = v; if (v < mn) mn = v; c++ }
            }
            val ampL = if (c >= 2) (mx - mn) / 2.0 else Double.NaN
            val snrOk = if (p.winMinSnr > 0.0) (sigmaL > 0.0 && !ampL.isNaN() && ampL / sigmaL >= p.winMinSnr) else true
            val adxOk = if (p.winMinAdx > 0.0) {
                val a = adxSeries(bars, 14)
                last in a.indices && !a[last].isNaN() && a[last] >= p.winMinAdx
            } else true
            snrOk && adxOk
        }
        return when (state) {
            1 -> {   // UP：核心做多 + 极限带反向T（镜像 simulate 三开关：ADX仓位 / RSI卖T确认 / ER闸拦买入）
                var corePctEff = p.corePct
                if (p.reduceMode == 2) {   // 日线下行核心仓折半（与 simulate 同口径：5根斜率<0）
                    var dn = 0
                    for (k in 1..5) { val i = last - k; if (i >= 1 && h.mid[i] < h.mid[i - 1]) dn++ }
                    if (dn >= 3) corePctEff *= 0.5
                }
                if (p.adxGate) {
                    val a = adxSeries(bars, p.adxPeriod)
                    if (last in a.indices && !a[last].isNaN()) {
                        corePctEff = if (a[last] > 25.0) min(corePctEff, 95.0)
                                     else if (a[last] < 20.0) min(corePctEff, 60.0) else corePctEff
                    }
                }
                // ER 闸（仅拦买入）：未注入数组则现算 erq（与 simulate 注入口径一致）
                val erBlock = if (p.erGate > 0.0) {
                    val (ep, ea) = if (p.erPctArr != null && p.erArr != null) p.erPctArr to p.erArr
                    else {
                        val cl = DoubleArray(n) { bars[it].close }
                        SsaErQuantile.erqArrays(cl)
                    }
                    !(last < ep.size && ep[last].isFinite() && ep[last] >= p.erGate &&
                        !(last >= 5 && last < ea.size && ea[last].isFinite() && ea[last - 5].isFinite() &&
                            ea[last] - ea[last - 5] <= p.erD5))
                } else false
                when {
                    hold < corePctEff - 1e-9 ->
                        if (erBlock) of(HOLD_POS, "UP待回补·ER闸拦截(分位不足/Δ5ER弱)") else of(BUY, "UP建仓/回补至核心仓")
                    close > eUp -> {
                        val rsiOk = if (!p.tRsiConfirm) true else {
                            val cl = DoubleArray(n) { bars[it].close }
                            val rs = rsiSeries(cl, p.tRsiPeriod)
                            last in rs.indices && !rs[last].isNaN() && rs[last] > p.tRsiThr
                        }
                        if (rsiOk) of(SELL, "UP超极限上沿·卖T") else of(HOLD_POS, "UP超上沿·RSI未确认(≤${p.tRsiThr.toInt()})")
                    }
                    close < eDn ->
                        if (erBlock) of(HOLD_POS, "UP破下沿·ER闸拦截(分位不足/Δ5ER弱)") else of(BUY, "UP破极限下沿·买回T")
                    else -> of(HOLD_POS, "UP核心持有")
                }
            }
            3 -> {        // WINDOW：上穿中位买 / 触正常上沿卖 / 可选破下沿止损
                val stopLine = when (p.winStopDn) { 1 -> nDn; 2 -> eDn; else -> Double.NaN }
                val prevMid = if (last >= 1) h.mid[last - 1] else Double.NaN
                val crossUp = !prevMid.isNaN() && bars[last - 1].close < prevMid && close >= mid
                when {
                    r.regime[last - 1] != 3 && hold > 1e-9 -> of(SELL, "中位线下穿趋势线·全平")
                    hold > 1e-9 && !stopLine.isNaN() && close < stopLine ->
                        of(SELL, if (p.winStopDn == 2) "WINDOW破极限下沿·止损" else "WINDOW破正常下沿·止损")
                    empty && crossUp && !p.winDayUp && winGateOkL -> of(BUY, "WINDOW上穿中位·买入")
                    hold > 1e-9 && close >= nUp -> of(SELL, "WINDOW触正常上沿·卖出")
                    hold > 1e-9 -> of(HOLD_POS, "WINDOW持有")
                    else -> of(HOLD_EMPTY, "WINDOW空仓观望")
                }
            }
            else -> when {  // FLAT：空仓，仅极限突破T
                hold > 1e-9 -> of(SELL, "FLAT峰顶回落·清仓")
                close < eDn -> of(BUY, "FLAT破极限下沿·买T")
                else -> of(HOLD_EMPTY, "FLAT空仓观望")
            }
        }
    }

    /** 1H 周期扰动区间评估（next 为真实下一根，仅用于事后评估，不参与信号） */
    private fun bandEval(h: Layer): BandEval {
        val bars = h.bars
        var inside = 0; var valid = 0
        var tu = 0; var tuOk = 0; var td = 0; var tdOk = 0
        var dev = 0.0; var devN = 0
        for (t in 1 until bars.size) {
            if (h.nUp[t].isNaN()) continue
            valid++
            val c = bars[t].close
            if (c in h.nDn[t]..h.nUp[t]) inside++
            if (h.mid[t] > 0) { dev += abs(c - h.mid[t]) / h.mid[t]; devN++ }
            val next = if (t + 1 < bars.size) bars[t + 1].close else Double.NaN
            if (bars[t].high >= h.nUp[t]) { tu++; if (!next.isNaN() && next < c) tuOk++ }
            if (bars[t].low <= h.nDn[t]) { td++; if (!next.isNaN() && next > c) tdOk++ }
        }
        return BandEval(
            layerName = "1H", bars = valid,
            insideRatePct = if (valid == 0) 0.0 else inside * 100.0 / valid,
            touchUp = tu, touchUpReversalPct = if (tu == 0) 0.0 else tuOk * 100.0 / tu,
            touchDn = td, touchDnReversalPct = if (td == 0) 0.0 else tdOk * 100.0 / td,
            avgDevPct = if (devN == 0) 0.0 else dev * 100.0 / devN
        )
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
            trend[t] = dec.trend[mm - 1]
            cycle[t] = dec.cycle[mm - 1]
            // σ = 残差噪声分量（close − (trend+cycle)）最近 N 根的滚动标准差，无未来函数
            val sigma = tailStd(dec.noise, N)
            // 中线 = trend + cycle（价格尺度平滑线 = 该段趋势中位价），于 t 收盘生成供 t+1 使用。
            // 正常区间 = 中线 ± 1.5σ；极限区间 = 中线 ± 2.0σ。
            if (t + 1 < n) {
                val m = dec.trend[mm - 1] + dec.cycle[mm - 1]
                mid[t + 1] = m
                nUp[t + 1] = m + cfg.cycleMult * sigma
                nDn[t + 1] = m - cfg.cycleMult * sigma
                eUp[t + 1] = m + cfg.noiseMult * sigma
                eDn[t + 1] = m - cfg.noiseMult * sigma
            }
            // 当根事后情景（区间由 t-1 生成，无未来）：A=正常区间内 B=极限区间内 C=突破极限区间
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

    /** 尾窗标准差：最近 n 个有效值的样本标准差（ddof=1） */
    private fun tailStd(v: DoubleArray, n: Int): Double {
        var s = 0.0; var s2 = 0.0; var cnt = 0
        val from = max(0, v.size - n)
        for (i in from until v.size) if (!v[i].isNaN()) {
            s += v[i]; s2 += v[i] * v[i]; cnt++
        }
        if (cnt < 2) return 0.0
        val mean = s / cnt
        val vari = (s2 - cnt * mean * mean) / (cnt - 1)
        return if (vari <= 0.0) 0.0 else sqrt(vari)
    }

    /** Wilder RSI(n) 逐根序列（下标对齐 closes；前 n 根 NaN）。
     *  移植自 `smalltools/_ssa_t0_engine.py rsi()`（2026-10-20 回测采纳 tRsiConfirm），
     *  首值 = 前 n 根简单均值，之后 Wilder 平滑递推，无未来函数。 */
    internal fun rsiSeries(closes: DoubleArray, n: Int): DoubleArray {
        val len = closes.size
        val out = DoubleArray(len) { Double.NaN }
        if (len <= n) return out
        var avgG = 0.0; var avgL = 0.0
        for (i in 1..n) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) avgG += d else avgL -= d
        }
        avgG /= n; avgL /= n
        out[n] = 100.0 - 100.0 / (1.0 + (if (avgL > 0) avgG / avgL else 1e12))
        for (i in n + 1 until len) {
            val d = closes[i] - closes[i - 1]
            avgG = (avgG * (n - 1) + (if (d > 0) d else 0.0)) / n
            avgL = (avgL * (n - 1) + (if (d < 0) -d else 0.0)) / n
            out[i] = 100.0 - 100.0 / (1.0 + (if (avgL > 0) avgG / avgL else 1e12))
        }
        return out
    }

    /** 1H Wilder ADX(n) 逐根序列（下标对齐 bars；前段 NaN）。与 [EtfScreenMath.adx] 同口径
     *  （TR/+DM/−DM 累计和 → SMMA → DI → DX → ADX 递推），仅用于 WINDOW 买入闸门，无未来函数。 */
    internal fun adxSeries(bars: List<Bar>, n: Int): DoubleArray {
        val len = bars.size
        val out = DoubleArray(len) { Double.NaN }
        if (len < 2 * n + 1) return out
        val m = len - 1
        val tr = DoubleArray(m); val pdm = DoubleArray(m); val mdm = DoubleArray(m)
        for (i in 1 until len) {
            val j = i - 1
            val h = bars[i].high; val l = bars[i].low; val pc = bars[i - 1].close
            tr[j] = max(max(h - l, abs(h - pc)), abs(l - pc))
            val up = h - bars[i - 1].high
            val dn = bars[i - 1].low - l
            pdm[j] = if (up > dn && up > 0) up else 0.0
            mdm[j] = if (dn > up && dn > 0) dn else 0.0
        }
        fun wma(v: DoubleArray): DoubleArray {
            val o = DoubleArray(v.size); var acc = 0.0
            for (i in v.indices) { acc = if (i < n) acc + v[i] else acc - acc / n + v[i]; o[i] = acc }
            return o
        }
        val trS = wma(tr); val pS = wma(pdm); val mS = wma(mdm)
        var cnt = 0; var sum = 0.0; var adxV = Double.NaN
        for (j in 0 until m) {
            if (trS[j] <= 1e-12) continue
            val pdi = 100.0 * pS[j] / trS[j]; val mdi = 100.0 * mS[j] / trS[j]
            if (pdi + mdi <= 1e-12) continue
            val d = 100.0 * abs(pdi - mdi) / (pdi + mdi)
            cnt++
            if (cnt <= n) { sum += d; if (cnt == n) adxV = sum / n }
            else adxV = (adxV * (n - 1) + d) / n
            out[j + 1] = adxV
        }
        return out
    }

    // ═══════════════════════════════ 三态做T状态机 ═══════════════════════════════

    /** FIFO 持仓批次：买入入队、卖出按先进先出配对成 T 单 */
    private data class Lot(val openIndex: Int, val openPrice: Double, var shares: Long)

    private data class SimOut(
        val equity: DoubleArray, val benchmark: DoubleArray, val drawdown: DoubleArray,
        val markers: List<Marker>, val trades: List<Trade>, val riskEvents: List<RiskEvent>,
        val finalCash: Double, val finalShares: Long
    )

    /** 主状态序列（主体层=小时线）：因 mid=trend+cycle，「中位线 vs 趋势线」等价于 cycle 符号。
     *  UP=1(cycle>0，核心做多) / WINDOW=3(cycle<0 且买入窗口开) / FLAT=2(cycle<0 且窗口关)，暖机=0。
     *  窗口由 cycle 拐点开关：谷底反转(cy[i-2]>cy[i-1]<cy[i] 且 cy[i-1]<0)开窗；
     *  峰顶反转(cy[i-2]<cy[i-1]>cy[i] 且 cy[i-1]>0)关窗。 */
    private fun regimes(cy: DoubleArray): IntArray {
        val n = cy.size
        val reg = IntArray(n)
        var window = false
        for (i in 0 until n) {
            if (i < 2 || cy[i].isNaN() || cy[i - 1].isNaN() || cy[i - 2].isNaN()) continue
            if (cy[i - 2] < cy[i - 1] && cy[i - 1] > cy[i] && cy[i - 1] > 0) window = false
            if (cy[i - 2] > cy[i - 1] && cy[i - 1] < cy[i] && cy[i - 1] < 0) window = true
            reg[i] = if (cy[i] > 0) 1 else if (window) 3 else 2
        }
        return reg
    }

    /** 把日线 cycle 映射到小时线时间轴（每个 1H 根取其「已收盘」日K 的 cycle），供 regimeLayer="D" 用 */
    private fun dailyCycleOnHour(h: Layer, d: Layer): DoubleArray {
        val n = h.bars.size
        val out = DoubleArray(n) { Double.NaN }
        var di = -1
        for (t in 0 until n) {
            val hb = h.bars[t]
            while (di + 1 < d.bars.size) {
                val db = d.bars[di + 1]
                if (db.date < hb.date || (db.date == hb.date && hb.time >= "15:00")) di++ else break
            }
            if (di >= 0) out[t] = d.cycle[di]
        }
        return out
    }

    /** EMA 平滑（保留前导 NaN，不污染序列）：span=1 时返回原序列 */
    private fun emaNaN(src: DoubleArray, span: Int): DoubleArray {
        val a = 2.0 / (span + 1)
        var prev = Double.NaN
        return DoubleArray(src.size) { i ->
            val v = src[i]
            if (v.isNaN()) Double.NaN
            else { prev = if (prev.isNaN()) v else a * v + (1 - a) * prev; prev }
        }
    }

    private fun simulate(h: Layer, d: Layer, w: Layer, reg: IntArray, p: Params): SimOut {
        val n = h.bars.size
        val closes = DoubleArray(n) { h.bars[it].close }
        // 中线规则专用中位线（可选 EMA 平滑；不影响三态机）
        val midS = if (p.midSmoothSpan > 1) emaNaN(h.mid, p.midSmoothSpan) else h.mid

        // ── 可选闸门预计算（默认全关 → 不产生任何分支差异，基线逐字节守恒）──
        val adxArr = if (p.winMinAdx > 0.0) adxSeries(h.bars, 14) else DoubleArray(0)
        // 2026-10-20 回测采纳三开关（对应 Python --rsi / --adx / --erq）：
        val rsiArr = if (p.tRsiConfirm) rsiSeries(closes, p.tRsiPeriod) else DoubleArray(0)
        val adxArrT = if (p.adxGate) adxSeries(h.bars, p.adxPeriod) else DoubleArray(0)
        // ER 入场闸（仅拦 UP 段买入，不拦卖T）：erPct≥erGate 且 Δ5ER>erD5 才放行；无未来函数（t 收盘值）
        val erOk = if (p.erGate > 0.0 && p.erPctArr != null && p.erArr != null) {
            val ep = p.erPctArr; val ea = p.erArr
            BooleanArray(n) { t ->
                t < ep.size && ep[t].isFinite() && ep[t] >= p.erGate &&
                    !(t >= 5 && t < ea.size && ea[t].isFinite() && ea[t - 5].isFinite() &&
                        ea[t] - ea[t - 5] <= p.erD5)
            }
        } else BooleanArray(0)
        val cm = p.hour.cycleMult
        // σ_t = (nUp[t] − mid[t]) / cycleMult（区间由 t−1 生成、供 t 决策，无未来函数）
        fun sigmaAt(t: Int): Double =
            if (t in h.nUp.indices && cm > 0.0) (h.nUp[t] - h.mid[t]) / cm else Double.NaN
        // 波动目标 T 仓基准 σ_ref：显式给定，否则取全样本 σ 中位数
        val sigmaRef: Double = when {
            !p.volTargetT -> 0.0
            p.sigmaRefAbs > 0.0 -> p.sigmaRefAbs
            else -> {
                val vals = ArrayList<Double>()
                for (i in h.bars.indices) { val s = sigmaAt(i); if (!s.isNaN() && s > 0.0) vals.add(s) }
                if (vals.isEmpty()) 0.0 else { vals.sort(); vals[vals.size / 2] }
            }
        }
        val snrK = if (p.winSnrK > 0) p.winSnrK else p.hour.embedL
        // cycle 振幅 = 最近 K 根 cycle 的 (max−min)/2
        fun cycleAmpAt(t: Int): Double {
            if (snrK <= 0) return Double.NaN
            var mx = Double.NEGATIVE_INFINITY; var mn = Double.POSITIVE_INFINITY; var c = 0
            for (i in max(0, t - snrK + 1)..min(t, h.cycle.size - 1)) {
                val v = h.cycle[i]; if (!v.isNaN()) { if (v > mx) mx = v; if (v < mn) mn = v; c++ }
            }
            return if (c >= 2) (mx - mn) / 2.0 else Double.NaN
        }
        fun volF(t: Int): Double {
            if (!p.volTargetT) return 1.0
            val s = sigmaAt(t)
            return if (s > 0.0 && sigmaRef > 0.0) min(1.0, sigmaRef / s) else 1.0
        }
        // WINDOW 买入闸门：SNR 与 ADX 同时满足（阈值 0=关闭 → 恒 true）
        fun winGateOk(t: Int): Boolean {
            if (p.winMinSnr > 0.0) {
                val s = sigmaAt(t); val amp = cycleAmpAt(t)
                if (!(s > 0.0) || amp.isNaN() || amp / s < p.winMinSnr) return false
            }
            if (p.winMinAdx > 0.0) {
                if (t !in adxArr.indices) return false
                val a = adxArr[t]
                if (a.isNaN() || a < p.winMinAdx) return false
            }
            return true
        }

        // 暖机：轨道有效且状态已判定
        var start = 0
        for (i in h.bars.indices) {
            if (reg[i] != 0 && !h.mid[i].isNaN() && !h.eUp[i].isNaN()) { start = i; break }
        }
        val benchBase = h.bars[start].open.takeIf { it > 0 } ?: h.bars[start].close

        var cash = INITIAL_CAPITAL
        var sh = 0L
        val queue = ArrayDeque<Lot>()
        var xt = false                       // FLAT 段极限突破 T 仓标记
        var frozenCore = 0L                  // 方案1：UP 段进入时冻结的核心仓股数
        var upBlocked = false                // 方案3：UP 段提前离场后禁止再入场（直至脱离UP）
        val trades = ArrayList<Trade>()
        val markers = ArrayList<Marker>()
        val events = ArrayList<RiskEvent>()

        fun weekIdxFor(t: Int): Int {
            val hd = h.bars[t].date
            val tm = h.bars[t].time
            var wi = -1
            for (i in w.bars.indices) {
                if (w.bars[i].date < hd || (w.bars[i].date == hd && tm >= "15:00")) wi = i else break
            }
            return wi
        }

        // 已收盘日K索引（当日 15:00 前不用当日日K，避免未来函数）
        fun dayIdxFor(t: Int): Int {
            val hd = h.bars[t].date
            val tm = h.bars[t].time
            var di = -1
            for (i in d.bars.indices) {
                if (d.bars[i].date < hd || (d.bars[i].date == hd && tm >= "15:00")) di = i else break
            }
            return di
        }

        // 日线趋势 5 根斜率方向是否向下（只用已收盘日K）
        fun dayDownAt(t: Int): Boolean {
            val di = dayIdxFor(t)
            if (di < 5) return false
            val a = d.trend[di]; val b = d.trend[di - 5]
            return !a.isNaN() && !b.isNaN() && a < b
        }

        // ── cycle 拐点规则预计算（仅当启用；只用历史，无未来函数）──
        // cyLayer="H"：信号取 1H 的 SSA 周期分量 h.cycle；
        // cyLayer="D"：信号取日线周期分量 d.cycle（更平滑、拐点更少、贴合大趋势），
        //              日线拐点在「该日K 首次可用于 1H 决策」的那根(通常当日 15:00)触发一次。
        // 谷底=先降后升的最低点；峰顶=先升后降的最高点。
        // cyConfirm=1：3点反转，确认根 s 立即触发；cyConfirm=2：再等1根，要求 4 点单调确认。
        val useDayCy = p.useCycleRule && p.cyLayer.equals("D", ignoreCase = true)
        val valleySig = BooleanArray(n)   // 谷底确认信号（1H 信号根）
        val peakSig = BooleanArray(n)     // 峰顶确认信号（1H 信号根）
        // 当前 1H 根可见的 cycle 值（日线层取其「已收盘」日K 的 cycle）
        fun cyAt(t: Int): Double {
            if (!useDayCy) return if (t in 0 until n) h.cycle[t] else Double.NaN
            val di = dayIdxFor(t)
            return if (di in d.cycle.indices) d.cycle[di] else Double.NaN
        }
        if (p.useCycleRule) {
            val need = if (p.cyConfirm >= 2) 3 else 2
            if (!useDayCy) {
                val cy = DoubleArray(n) { h.cycle[it] }
                fun okCy(i: Int) = i >= 0 && !cy[i].isNaN()
                for (s in need until n) {
                    if (!okCy(s) || !okCy(s - 1) || !okCy(s - 2)) continue
                    if (p.cyConfirm >= 2) {
                        if (!okCy(s - 3)) continue
                        if (cy[s - 3] > cy[s - 2] && cy[s - 2] < cy[s - 1] && cy[s - 1] < cy[s]) valleySig[s] = true
                        if (cy[s - 3] < cy[s - 2] && cy[s - 2] > cy[s - 1] && cy[s - 1] > cy[s]) peakSig[s] = true
                    } else {
                        if (cy[s - 2] > cy[s - 1] && cy[s - 1] < cy[s]) valleySig[s] = true
                        if (cy[s - 2] < cy[s - 1] && cy[s - 1] > cy[s]) peakSig[s] = true
                    }
                }
            } else {
                val dn = d.bars.size
                val dy = d.cycle
                fun okD(i: Int) = i >= 0 && !dy[i].isNaN()
                val dv = BooleanArray(dn); val dp = BooleanArray(dn)
                for (s in need until dn) {
                    if (!okD(s) || !okD(s - 1) || !okD(s - 2)) continue
                    if (p.cyConfirm >= 2) {
                        if (!okD(s - 3)) continue
                        if (dy[s - 3] > dy[s - 2] && dy[s - 2] < dy[s - 1] && dy[s - 1] < dy[s]) dv[s] = true
                        if (dy[s - 3] < dy[s - 2] && dy[s - 2] > dy[s - 1] && dy[s - 1] > dy[s]) dp[s] = true
                    } else {
                        if (dy[s - 2] > dy[s - 1] && dy[s - 1] < dy[s]) dv[s] = true
                        if (dy[s - 2] < dy[s - 1] && dy[s - 1] > dy[s]) dp[s] = true
                    }
                }
                // 日线信号 → 1H 信号根（该日K 首次可用时触发一次）
                var lastDi = Int.MIN_VALUE
                for (t in 0 until n) {
                    val di = dayIdxFor(t)
                    if (di != lastDi) {
                        if (di in 0 until dn) { valleySig[t] = dv[di]; peakSig[t] = dp[di] }
                        lastDi = di
                    }
                }
            }
        }
        // 段方向初值：回看最近一个已确认拐点（找不到则按 cycle 符号）
        val baseCy = cyAt(start)
        var legUp = !baseCy.isNaN() && baseCy > 0.0
        for (i in start downTo 3) {
            if (valleySig[i]) { legUp = true; break }
            if (peakSig[i]) { legUp = false; break }
        }
        var downEntry = 0.0               // 下降段买入成交价（保本线）

        // 买入至目标股数（整手、受现金约束），t 收盘决策 → exec 开盘成交
        fun buyTo(exec: Int, fill: Double, target: Long, reason: String,
                  markIdx: Int = exec - 1, markPrice: Double = closes[exec - 1]) {
            val need = ((target - sh) / 100) * 100
            if (need <= 0) return
            var q = min(need, lots(cash, fill))
            while (q > 0 && buyCost(q, fill, p) > cash) q -= 100
            if (q <= 0) return
            cash -= buyCost(q, fill, p)
            queue.addLast(Lot(exec, fill, q)); sh += q
            // 图表标记默认落在「信号根」(收盘确认的那根, exec-1)；盘中触及模式则落在成交根与本根线价
            markers.add(Marker(markIdx, markPrice, true, reason))
        }

        // 卖出（FIFO 配对成 T 单）；level 非空则同时记一条风控日志
        fun sellQty(exec: Int, fill: Double, want: Long, reason: String, level: String?,
                    markIdx: Int = exec - 1, markPrice: Double = closes[exec - 1]) {
            val q = (min(want, sh) / 100) * 100
            if (q <= 0) return
            cash += sellProceeds(q, fill, p); sh -= q
            var remain = q
            while (remain > 0 && queue.isNotEmpty()) {
                val lot = queue.first()
                val take = min(lot.shares, remain)
                val wi = weekIdxFor(lot.openIndex)
                val weeklyUp = if (wi >= 1) w.trend[wi] >= w.trend[wi - 1] else true
                val di = dayIdxFor(lot.openIndex)
                val dayUp = if (di >= 5) (d.trend[di] >= d.trend[di - 5]) else true
                val cost0 = buyCost(take, lot.openPrice, p)
                trades.add(Trade(
                    direction = "正T",
                    openIndex = lot.openIndex, openTime = labelOf(h.bars[lot.openIndex]), openPrice = lot.openPrice,
                    closeIndex = exec, closeTime = labelOf(h.bars[exec]), closePrice = fill,
                    returnPct = (sellProceeds(take, fill, p) - cost0) / cost0 * 100,
                    barsHeld = exec - lot.openIndex,
                    weeklyUpAtOpen = weeklyUp, dayUpAtOpen = dayUp, exitReason = reason
                ))
                if (take >= lot.shares) queue.removeFirst() else lot.shares -= take
                remain -= take
            }
            markers.add(Marker(markIdx, markPrice, false, reason))
            if (level != null) events.add(RiskEvent(exec, labelOf(h.bars[exec]), level, reason))
        }

        val equity = DoubleArray(n) { INITIAL_CAPITAL }
        val bench = DoubleArray(n) { INITIAL_CAPITAL }

        for (t in start until n - 1) {
            val fill = h.bars[t + 1].open
            val mid = h.mid[t]; val nUp = h.nUp[t]; val nDn = h.nDn[t]; val eUp = h.eUp[t]; val eDn = h.eDn[t]
            if (fill > 0 && !mid.isNaN() && !nUp.isNaN() && !eUp.isNaN() && !eDn.isNaN()) {
                val ci = closes[t]
                val prevMid = h.mid[t - 1]
                val crossUp = !prevMid.isNaN() && closes[t - 1] < prevMid && ci >= mid
                val crossDn = !prevMid.isNaN() && closes[t - 1] >= prevMid && ci < mid

                if (p.useCycleRule) {
                    // ── 试验规则：cycle 拐点驱动做T ──
                    // 上升段：谷底确认满仓买入 → 回中线/低点反转补满 → 峰顶确认全部卖出。
                    // 下降段：仅「中位线上行且价上穿中线」或「价格低点反转」买入；
                    //         触极限上沿 / 价回买入点 / 价回中位线 任一先到即卖。
                    val fullSh = lots(cash + sh * ci, fill)
                    val lowRev = t >= 2 &&
                        h.bars[t - 2].low > h.bars[t - 1].low && h.bars[t - 1].low < h.bars[t].low
                    val prevMidC = h.mid[t - 1]
                    val crossUpMid = !prevMidC.isNaN() && closes[t - 1] < prevMidC && ci >= mid
                    val midRising = p.cyMidRisingK <= 0 ||
                        (t - p.cyMidRisingK >= 0 && !h.mid[t - p.cyMidRisingK].isNaN() &&
                            mid > h.mid[t - p.cyMidRisingK])

                    if (valleySig[t]) {                      // cycle 谷底 → 转上升段
                        legUp = true; downEntry = 0.0
                        if (p.cyUpLeg && sh < fullSh) buyTo(t + 1, fill, fullSh, "cycle谷底·满仓买入")
                    } else if (peakSig[t]) {                 // cycle 峰顶 → 转下降段并全清
                        legUp = false; downEntry = 0.0
                        if (p.cyUpLeg && sh > 0L) sellQty(t + 1, fill, sh, "cycle峰顶·全部卖出", "1H")
                    } else if (legUp) {                      // 上升段：维持满仓（回中线/低点反转补满）
                        val cyNow = cyAt(t); val cyPrev = cyAt(t - 1)
                        if (p.cyExitOnZero && cyNow < 0.0 && !cyPrev.isNaN() && cyPrev >= 0.0) {
                            // cycle 下穿 0（中位线下穿趋势线）→ 峰顶确认前的补充退出
                            legUp = false; downEntry = 0.0
                            if (sh > 0L) sellQty(t + 1, fill, sh, "上升段cycle下穿0·清仓", "1H")
                        } else if (p.cyUpLeg && sh < fullSh) {
                            val why = if (lowRev) "上升段低点反转·补满" else "上升段回中线·补满"
                            buyTo(t + 1, fill, fullSh, why)
                        }
                    } else {                                 // 下降段：尽量不买，仅限定条件
                        if (sh == 0L) {
                            if (p.cyDownLeg && midRising && crossUpMid) {
                                buyTo(t + 1, fill, lots(INITIAL_CAPITAL, fill), "下降段上穿中线·买入")
                                downEntry = fill
                            } else if (p.cyDownLeg && lowRev) {
                                buyTo(t + 1, fill, lots(INITIAL_CAPITAL, fill), "下降段低点反转·买入")
                                downEntry = fill
                            }
                        } else {
                            when {
                                ci >= eUp -> {
                                    sellQty(t + 1, fill, sh, "下降段触极限·卖出", null); downEntry = 0.0
                                }
                                downEntry > 0 && ci <= downEntry -> {
                                    sellQty(t + 1, fill, sh, "下降段回买入点·卖出", null); downEntry = 0.0
                                }
                                ci < mid -> {
                                    sellQty(t + 1, fill, sh, "下降段回中位线·卖出", null); downEntry = 0.0
                                }
                            }
                        }
                    }
                } else if (p.useMidCross) {
                    // ── 试验规则：价格上穿中位线买入 / 下穿中位线卖出（不看三态，满仓进出）──
                    val mNow = midS[t]
                    if (!mNow.isNaN()) {
                        // 中位线上行过滤：仅当最近 K 根中位线在上行时才允许买入（中位线下行时禁止买入）
                        val risingOk = p.midRisingK <= 0 ||
                            (t - p.midRisingK >= 0 && !midS[t - p.midRisingK].isNaN() && mNow > midS[t - p.midRisingK])
                        val prevMidS = if (t - 1 >= 0) midS[t - 1] else Double.NaN
                        if (p.execAtTouch) {
                            // 盘中触及即成交：参考线 L = 上一根中位线（t-1收盘已知，无未来函数）。
                            // 等价于每根在 L 挂限价/停损单：当根 open/high/low 触及则按线价成交（已含滑点）。
                            val L = prevMidS
                            if (!L.isNaN()) {
                                val o = h.bars[t].open; val hi = h.bars[t].high; val lo = h.bars[t].low
                                if (sh == 0L) {
                                    if (risingOk) {
                                        if (o >= L) buyTo(t, o, lots(INITIAL_CAPITAL, o), "触及中线·买入", t, o)
                                        else if (hi >= L) buyTo(t, L, lots(INITIAL_CAPITAL, L), "触及中线·买入", t, L)
                                    }
                                } else {
                                    if (o <= L) sellQty(t, o, sh, "触及中线·卖出", null, t, o)
                                    else if (lo <= L) sellQty(t, L, sh, "触及中线·卖出", null, t, L)
                                }
                            }
                        } else {
                            val xUp = !prevMidS.isNaN() && closes[t - 1] < prevMidS && ci >= mNow
                            val xDn = !prevMidS.isNaN() && closes[t - 1] >= prevMidS && ci < mNow
                            if (sh == 0L && xUp && risingOk) buyTo(t + 1, fill, lots(INITIAL_CAPITAL, fill), "上穿中线·买入")
                            else if (sh > 0 && xDn) sellQty(t + 1, fill, sh, "下穿中线·卖出", null)
                        }
                    }
                } else {
                if (reg[t] != 1) upBlocked = false    // 一旦脱离UP，解除提前离场的观望

                when (reg[t]) {
                    1 -> { // ── UP：核心做多 + 极限带反向T（reduceMode 决定削减方案）──
                        xt = false
                        val dayDown = dayDownAt(t)
                        when {
                            // 方案3：日线下行且收盘破中位 → 提前全平，转为观望直至脱离UP
                            p.reduceMode == 3 && upBlocked -> { /* 已离场，观望 */ }
                            p.reduceMode == 3 && dayDown && ci < mid -> {
                                sellQty(t + 1, fill, sh, "UP日线下行+破中位·提前离场", "1H")
                                upBlocked = true
                            }
                            // 方案1：核心仓在 UP 进入时冻结，之后仅 close<eDn 才补回（消除金字塔加仓）
                            p.reduceMode == 1 -> {
                                val entering = reg[t - 1] != 1
                                if (entering) frozenCore = lots((cash + sh * ci) * p.corePct / 100.0, fill)
                                when {
                                    entering -> buyTo(t + 1, fill, frozenCore, "UP建仓")
                                    ci > eUp -> sellQty(t + 1, fill, (sh * p.tPct / 100.0 * volF(t)).toLong(), "UP超上限卖T", null)
                                    ci < eDn -> buyTo(t + 1, fill, frozenCore, "UP破下限买T")
                                }
                            }
                            // 基线 / 方案2：核心仓 = 权益×corePct；方案2 在日线下行时上限折半(80→40)
                            // 2026-10-20 回测采纳叠加：ADX 动态仓位 + RSI 卖T确认 + ER 闸拦买入
                            else -> {
                                var corePctEff = if (p.reduceMode == 2 && dayDown) p.corePct * 0.5 else p.corePct
                                var tScale = 1.0
                                if (p.adxGate && t in adxArrT.indices) {
                                    val a = adxArrT[t]
                                    if (!a.isNaN()) when {
                                        a > 25.0 -> { corePctEff = min(corePctEff, 95.0); tScale = 0.5 }  // 强趋势：重仓少做T
                                        a < 20.0 -> { corePctEff = min(corePctEff, 60.0); tScale = 2.0 }  // 震荡：轻仓多做T
                                    }
                                }
                                val coreShares = lots((cash + sh * ci) * corePctEff / 100.0, fill)
                                val sellQ = (sh * p.tPct / 100.0 * volF(t) * tScale).toLong()
                                val erBlock = t in erOk.indices && !erOk[t]
                                when {
                                    sh < coreShares -> if (!erBlock) buyTo(t + 1, fill, coreShares, "UP建仓/回补")
                                    ci > eUp -> {
                                        val rsiOk = !p.tRsiConfirm ||
                                            (t in rsiArr.indices && !rsiArr[t].isNaN() && rsiArr[t] > p.tRsiThr)
                                        if (rsiOk) sellQty(t + 1, fill, sellQ, "UP超上限卖T", null)
                                    }
                                    ci < eDn -> if (!erBlock) buyTo(t + 1, fill, coreShares, "UP破下限买T")
                                }
                            }
                        }
                    }
                    3 -> { // ── WINDOW：中位线上穿买 / 触正常上沿卖 / （可选）破下沿止损 ──
                        xt = false
                        val stopLine = when (p.winStopDn) { 1 -> nDn; 2 -> eDn; else -> Double.NaN }
                        if (reg[t - 1] != 3 && sh > 0) {
                            sellQty(t + 1, fill, sh, "中位线下穿趋势线·全平", "1H")
                        } else if (sh > 0 && !stopLine.isNaN() && ci < stopLine) {
                            sellQty(t + 1, fill, sh, if (p.winStopDn == 2) "WINDOW破极限下沿·止损" else "WINDOW破正常下沿·止损", "1H")
                        } else if (sh == 0L && crossUp && (!p.winDayUp || !dayDownAt(t)) && winGateOk(t)) {
                            buyTo(t + 1, fill, lots(INITIAL_CAPITAL * p.winPosPct / 100.0, fill), "WINDOW上穿中位·买入")
                        } else if (sh > 0 && ci >= nUp) {
                            sellQty(t + 1, fill, sh, "WINDOW触正常上沿·卖出", null)
                        }
                    }
                    else -> { // ── FLAT：空仓，只做极限突破T ──
                        if (sh > 0 && !xt) {
                            sellQty(t + 1, fill, sh, "FLAT峰顶回落·清仓", "1H")
                        } else if (sh == 0L && ci < eDn) {
                            buyTo(t + 1, fill, lots(INITIAL_CAPITAL, fill), "FLAT破极限·买T")
                            xt = true
                        } else if (xt && sh > 0 && ci > eUp) {
                            sellQty(t + 1, fill, sh, "FLAT超极限·卖T", null)
                            xt = false
                        }
                    }
                }
                }
            }
            equity[t] = cash + sh * closes[t]
            bench[t] = INITIAL_CAPITAL * (closes[t] / benchBase)
        }
        // 末根估值（不在其开盘决策）
        val last = n - 1
        if (last >= start) {
            equity[last] = cash + sh * closes[last]
            bench[last] = INITIAL_CAPITAL * (closes[last] / benchBase)
        }

        var peak = INITIAL_CAPITAL
        val dd = DoubleArray(n)
        for (i in 0 until n) {
            if (equity[i] > peak) peak = equity[i]
            dd[i] = (equity[i] - peak) / peak * 100
        }
        return SimOut(equity, bench, dd, markers, trades, events, cash, sh)
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
