package com.chin.stockanalysis.strategy.ssa

import com.chin.stockanalysis.strategy.indicatorbt.IndicatorBacktestEngine
import com.chin.stockanalysis.strategy.ssa.SsaAlertEngine.AlertParams
import com.chin.stockanalysis.strategy.ssa.SsaAlertEngine.AlertPerf
import com.chin.stockanalysis.strategy.ssa.SsaAlertEngine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaAlertEngine.Signal
import kotlin.math.sqrt

/**
 * ## BB（布林极限）拐点策略（1H 周期，纯 Kotlin，零 Android 依赖）
 *
 * 在预警引擎之外另立一条策略：对 **BB = 布林极限**（通达信系统原版，见 [bbSeries]；**不是 BBI 多空指标**）
 * 线（或其均线）做**拐点**判定 —— **谷底拐点确认 → 买入**；**峰顶拐点确认 → 卖出**。**不使用多指标共振**。
 *
 * 拐点判定只依赖序列的**形状**，对仿射变换 `a + b·X` 完全不变，故归一化中心/量程
 * （原版中心 25、约 −100~+150）不影响信号位置。
 *
 * ### 无未来函数口径（与项目既有约定一致）
 * 序列 `s` 在 i 根是否为拐点，需等 i+1 根才能确认（此时 `s[i+1]` 已由 ≤ i+1 的收盘价算出）：
 * - 谷底：`s[i] <= s[i-1] && s[i+1] > s[i]` → **在 i+1 根收盘确认买入**
 * - 峰顶：`s[i] >= s[i-1] && s[i+1] < s[i]` → **在 i+1 根收盘确认卖出**
 *
 * 即信号根 = 确认根（天然滞后 1 根），触发价 = 确认根收盘；平台期用 `<=` / `>=` 处理并列值，
 * 上升/下降腿用严格不等号避免同平台重复出信号。
 *
 * ### 平滑口径（用户点名的两种情况映射进本网格）
 * 用户提出的「BB 线平滑处理后找拐点」与「BB 线的 MA 平均线找拐点」在数学上是同一条线的不同说法
 * （BB 的均线本身就是对 BB 的平滑），故此处参数化为 [Smooth] 网格，连同敏感性一并对比。
 */
object SsaBbiPivotEngine {

    /** 布林极限中轨周期：通达信系统 BB 用 MID = MA(C, 20) */
    private const val BB_MID_N = 20

    /** 布林极限偏差周期：VART3 = SQRT(MA((C-MID)^2, 20))（偏差平方的滚动均值再开方，≠ STD） */
    private const val BB_SIG_N = 20

    /**
     * 拐点判定所基于的**原始序列**口径（仅用于 A/B 对照）：
     * - [BB]：布林极限（通达信系统原版，见 [bbSeries]）
     * - [BBI]：BBI 多空指标 `(MA3+MA6+MA12+MA24)/4`（**上一版误用口径**，仅供回归对照，不参与 App 展示）
     */
    enum class BaseSeries(val label: String) { BB("新BB"), BBI("旧BBI") }

    /** 用于拐点检测的序列口径 */
    enum class Smooth(val label: String) {
        RAW("BB原线"),
        MA3("MA(BB,3)"),
        MA5("MA(BB,5)"),
        MA6("MA(BB,6)"),
        MA8("MA(BB,8)"),
        MA10("MA(BB,10)"),
        MA12("MA(BB,12)"),
        MA16("MA(BB,16)"),
        MA20("MA(BB,20)"),
        MA24("MA(BB,24)"),
        EMA3("EMA(BB,3)"),
        EMA5("EMA(BB,5)"),
        EMA8("EMA(BB,8)"),
        EMA10("EMA(BB,10)"),
        EMA12("EMA(BB,12)"),
        EMA16("EMA(BB,16)")
    }

    /** 单笔成交明细（用于亏损归因；成交价 = 收盘价，语义与 [SsaAlertEngine.computePerf] 完全一致） */
    data class Trade(
        val openIndex: Int, val openLabel: String, val openPrice: Double,
        val closeIndex: Int, val closeLabel: String, val closePrice: Double,
        /** 持仓根数（1H 根） */
        val barsHeld: Int,
        /** 单笔收益率（%，含费与否同 [AlertParams.includeCost]） */
        val pnlPct: Double,
        /** 平仓原因：`signal` 信号卖出 / `stop` 硬止损 / `forced` 末根强平 */
        val exit: String,
        /** 建仓信号条件码（P1/C1/…） */
        val entryCond: String
    )

    /** 单个平滑口径下的拐点结果 */
    data class VariantResult(
        val variant: Smooth,
        /** 是否启用「BB 买入窗」硬闸门（BB均线上行 且 BB 在其均线上方才允许建仓） */
        val gated: Boolean,
        val series: DoubleArray,
        val signals: List<Signal>,
        val perf: AlertPerf,
        val trades: List<Trade>
    ) {
        /** 展示名，如 `MA(BB,3)` / `MA(BB,3)+BB窗` */
        val label: String get() = variant.label + if (gated) "+BB窗" else ""
    }

    data class Result(
        val bars: List<Bar>,
        val bb: DoubleArray,
        /** BB 的均线 MA(BB, maN)（maN = [AlertParams.bbiMaN]），与 [Smooth.MA3]（maN=3 时）为同一序列 */
        val bbMa: DoubleArray,
        /** 收益率区间起点 = max(0, n - window) */
        val windowFrom: Int,
        val variants: List<VariantResult>,
        /** 现有多指标策略在同一区间的基线表现（对照用） */
        val baseline: AlertPerf
    )

    /**
     * 拐点策略上的过滤条件（用于调参对比；默认值 = 不过滤）。
     */
    data class PivotFilter(
        val smooth: Smooth = Smooth.MA8,
        /** 是否启用 BB 买入窗闸门（BB均线上行 且 BB 在其均线上方才允许建仓） */
        val gateBbiWindow: Boolean = true,
        /**
         * 摆动幅度阈值（%）：本次拐点相对**上一个反向原始拐点**的幅度 ≥ 该值才出信号。
         * 例如谷底买入要求「上一个峰顶 → 本谷底」跌幅 ≥ minSwingPct；峰顶卖出同理。
         * 比「单根跌幅」稳健得多：它衡量的是一个完整摆动的幅度，不会被单根毛刺放大。
         */
        val minSwingPct: Double = 0.0,
        /** 确认根 ADX(14) ≥ 该值才买入（0=关闭） */
        val minAdx: Double = 0.0,
        /** 平仓改为「峰顶拐点 或 BB 下穿其均线」双出口 */
        val exitOnBbiCrossDown: Boolean = false,
        /** 多均线趋势过滤：要求「前一均线 > 后一均线」才允许买入（如 MA3 > MA12 代表短均线在长均线上方） */
        val trendFilter: Pair<Smooth, Smooth>? = null,
        /** WR(14) 买入闸门：确认根 WR ≥ 该值（国内口径越大越超卖）才买入；0=关闭 */
        val wrBuyMin: Double = 0.0,
        /** WR(14) 卖出闸门：确认根 WR ≤ 该值（越小越超买）才卖出；100=关闭 */
        val wrSellMax: Double = 100.0,
        /** 硬止损（%）：持仓期收盘价 ≤ 建仓价×(1-阈值) 即平仓；0=关闭 */
        val hardStopPct: Double = 0.0,
        val note: String = ""
    )

    /** BB 多均线**金叉/死叉**策略：fast 上穿 slow 买入、下穿 slow 卖出 */
    data class CrossFilter(
        val fast: Smooth,
        val slow: Smooth,
        /** 是否要求 BB 买入窗开启才建仓 */
        val gateBbiWindow: Boolean = false,
        /** WR(14) 买入闸门：确认根 WR ≥ 该值才买入；0=关闭 */
        val wrBuyMin: Double = 0.0,
        /** WR(14) 卖出闸门：确认根 WR ≤ 该值才卖出；100=关闭 */
        val wrSellMax: Double = 100.0,
        /** 硬止损（%）：持仓期收盘价 ≤ 建仓价×(1-阈值) 即平仓；0=关闭 */
        val hardStopPct: Double = 0.0,
        val note: String = ""
    )

    data class CrossResult(
        val label: String,
        val signals: List<Signal>,
        val perf: AlertPerf,
        val trades: List<Trade>
    )

    /** WR(14) 平滑口径（用于对比「平滑 vs 不平滑」） */
    enum class WrSmooth(val label: String) {
        RAW("WR原线"),
        MA3("MA(WR,3)"),
        MA5("MA(WR,5)"),
        MA8("MA(WR,8)"),
        EMA3("EMA(WR,3)"),
        EMA5("EMA(WR,5)")
    }

    /**
     * **WR(14) 超买超卖策略**（国内口径 0~100，数值越大越超卖，`wrN` 默认 14）：
     * - **买入**：WR 自上方**下穿 65**（`crossBuy`）；或自上方**下穿 20**（`strongBuy`，强信号）
     * - **持有**：WR 在 65 下方持续下降时不动
     * - **卖出**：WR 自下方**上穿 25**（`crossSell`）；可选 `sellCross2`（如 65）作为第二出口
     * - 可选叠加 BB 条件：BB 买入窗闸门 / 同根 BB(MA12) 谷底确认 / BB 多均线趋势
     */
    data class WrFilter(
        val wrSmooth: WrSmooth = WrSmooth.RAW,
        /** 自上方下穿该值 → 买入 */
        val crossBuy: Double = 65.0,
        /** 自上方下穿该值 → 强买入信号（0=关闭） */
        val strongBuy: Double = 20.0,
        /** 自下方上穿该值 → 卖出 */
        val crossSell: Double = 25.0,
        /** 自下方上穿该值 → 第二卖出出口（0=关闭，如 65 止盈） */
        val sellCross2: Double = 0.0,
        /** 要求 BB 买入窗开启才建仓 */
        val gateBbiWindow: Boolean = false,
        /** 要求同一根同时是 BB(MA12) 谷底确认（拐点 + WR 共振） */
        val requireBbiValley: Boolean = false,
        /** 要求 BB 多均线「前一 > 后一」趋势向上才建仓 */
        val requireBbiTrend: Pair<Smooth, Smooth>? = null,
        val note: String = ""
    )

    data class WrResult(
        val label: String,
        val signals: List<Signal>,
        val perf: AlertPerf,
        val trades: List<Trade>
    )

    data class TuneResult(
        val label: String,
        val signals: List<Signal>,
        val perf: AlertPerf,
        val trades: List<Trade>
    )

    /**
     * 在 [PivotFilter] 网格上跑同一区间，返回各组合的拐点信号与收益率（供调参选型）。
     */
    fun runTuned(bars: List<Bar>, window: Int = 120, p: AlertParams = AlertParams()): List<TuneResult> {
        require(bars.size >= 30) { "K 线不足 30 根" }
        val n = bars.size
        val closes = DoubleArray(n) { bars[it].close }
        val (bb, bbMa) = bbSeries(closes, p.bbiMaN)
        val win = SsaAlertEngine.buyWindowSeries(bb, bbMa)
        val from = (n - window).coerceAtLeast(0)
        val to = n - 1
        val allOpen = BooleanArray(n) { true }
        val adxArr by lazy { adxOf(bars, 14) }
        val wrArr by lazy { wrOf(bars, p.wrN) }
        val cache = HashMap<Smooth, DoubleArray>()
        // 同一平滑序列在多条网格项之间复用，避免重复计算
        fun series(s: Smooth) = cache.getOrPut(s) { smoothOf(s, bb) }

        return TUNE_GRID.map { f ->
            val sigs = buildSignals(bars, series(f.smooth), f, bb, bbMa, adxArr, wrArr, ::series)
            val (tr, perf) = simulate(bars, sigs, if (f.gateBbiWindow) win else allOpen, from, to, f.hardStopPct, p)
            TuneResult(f.note, sigs, perf, tr)
        }
    }

    /**
     * BB **多均线金叉/死叉**网格：`fast` 上穿 `slow` 买入、下穿 `slow` 卖出（含用户点名的 MA3×MA12）。
     */
    fun runCross(bars: List<Bar>, window: Int = 120, p: AlertParams = AlertParams()): List<CrossResult> {
        require(bars.size >= 30) { "K 线不足 30 根" }
        val n = bars.size
        val closes = DoubleArray(n) { bars[it].close }
        val (bb, bbMa) = bbSeries(closes, p.bbiMaN)
        val win = SsaAlertEngine.buyWindowSeries(bb, bbMa)
        val from = (n - window).coerceAtLeast(0)
        val to = n - 1
        val allOpen = BooleanArray(n) { true }
        val wrArr by lazy { wrOf(bars, p.wrN) }
        val cache = HashMap<Smooth, DoubleArray>()
        fun series(s: Smooth) = cache.getOrPut(s) { smoothOf(s, bb) }

        return CROSS_GRID.map { f ->
            val sigs = crossSignals(bars, series(f.fast), series(f.slow), f, wrArr)
            val (tr, perf) = simulate(bars, sigs, if (f.gateBbiWindow) win else allOpen, from, to, f.hardStopPct, p)
            CrossResult(f.note, sigs, perf, tr)
        }
    }

    /**
     * 金叉网格：在第四轮最优（MA5×MA20 / MA3×MA24）基础上叠加
     * **WR(14) 闸门**与**硬止损**，检验能否改善「买入后继续跌」的亏损来源。
     */
    private val CROSS_GRID = listOf(
        CrossFilter(Smooth.MA5, Smooth.MA20, note = "金叉 MA5×MA20"),
        CrossFilter(Smooth.MA3, Smooth.MA24, note = "金叉 MA3×MA24"),
        CrossFilter(Smooth.MA5, Smooth.MA20, wrBuyMin = 60.0, note = "金叉 MA5×MA20+WR买60"),
        CrossFilter(Smooth.MA5, Smooth.MA20, wrBuyMin = 50.0, note = "金叉 MA5×MA20+WR买50"),
        CrossFilter(Smooth.MA5, Smooth.MA20, wrSellMax = 40.0, note = "金叉 MA5×MA20+WR卖40"),
        CrossFilter(Smooth.MA5, Smooth.MA20, wrBuyMin = 60.0, wrSellMax = 40.0, note = "金叉 MA5×MA20+WR双闸"),
        CrossFilter(Smooth.MA3, Smooth.MA24, wrBuyMin = 60.0, note = "金叉 MA3×MA24+WR买60"),
        CrossFilter(Smooth.MA5, Smooth.MA20, hardStopPct = 3.0, note = "金叉 MA5×MA20+止损3"),
        CrossFilter(Smooth.MA3, Smooth.MA24, hardStopPct = 3.0, note = "金叉 MA3×MA24+止损3"),
        CrossFilter(Smooth.MA5, Smooth.MA20, wrBuyMin = 60.0, hardStopPct = 3.0, note = "金叉 MA5×MA20+WR买60+止损3")
    )

    /**
     * 第五轮调参网格：以第四轮最优的 **MA12 拐点**为基准，逐项叠加
     * WR(14) 买入/卖出闸门与硬止损，量化各改善手段的增量。
     */
    private val TUNE_GRID = listOf(
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false, note = "MA12拐点"),
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false,
            trendFilter = Smooth.MA3 to Smooth.MA12, note = "MA12拐点+MA3>MA12"),
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false, wrBuyMin = 60.0, note = "MA12拐点+WR买60"),
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false, wrBuyMin = 50.0, note = "MA12拐点+WR买50"),
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false, wrBuyMin = 60.0, wrSellMax = 40.0,
            note = "MA12拐点+WR双闸"),
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false, hardStopPct = 3.0, note = "MA12拐点+止损3"),
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false, hardStopPct = 2.0, note = "MA12拐点+止损2"),
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false, wrBuyMin = 60.0, hardStopPct = 3.0,
            note = "MA12拐点+WR买60+止损3"),
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false, minSwingPct = 1.0, note = "MA12拐点+swing1.0"),
        PivotFilter(smooth = Smooth.MA12, gateBbiWindow = false, minSwingPct = 1.0, minAdx = 20.0,
            note = "MA12拐点+swing1.0+ADX20")
    )

    /**
     * **WR(14) 策略网格**：对比「WR 平滑 vs 不平滑」，并叠加 BB 组合条件
     * （BB 买入窗 / BB(MA12) 谷底同根共振 / BB 多均线趋势）。
     */
    fun runWr(bars: List<Bar>, window: Int = 120, p: AlertParams = AlertParams()): List<WrResult> {
        require(bars.size >= 30) { "K 线不足 30 根" }
        val n = bars.size
        val closes = DoubleArray(n) { bars[it].close }
        val (bb, bbMa) = bbSeries(closes, p.bbiMaN)
        val win = SsaAlertEngine.buyWindowSeries(bb, bbMa)
        val from = (n - window).coerceAtLeast(0)
        val to = n - 1
        val allOpen = BooleanArray(n) { true }
        val wrRaw by lazy { wrOf(bars, p.wrN) }
        /** BB(MA12) 谷底确认根集合（拐点族基准口径） */
        val valleySet by lazy {
            pivotSignals(bars, smoothOf(Smooth.MA12, bb), Smooth.MA12).filter { it.buy }.map { it.index }.toSet()
        }
        val cache = HashMap<WrSmooth, DoubleArray>()

        return WR_GRID.map { f ->
            val seq = cache.getOrPut(f.wrSmooth) { wrSmoothOf(f.wrSmooth, wrRaw) }
            val fast = f.requireBbiTrend?.let { smoothOf(it.first, bb) }
            val slow = f.requireBbiTrend?.let { smoothOf(it.second, bb) }
            val sigs = wrSignals(bars, seq, f, valleySet, win, fast, slow)
            val (tr, perf) = simulate(bars, sigs, if (f.gateBbiWindow) win else allOpen, from, to, 0.0, p)
            WrResult(f.note, sigs, perf, tr)
        }
    }

    /**
     * WR 网格：前 6 条纯 WR（平滑 vs 不平滑），后 8 条为 WR × BB 多组合。
     */
    private val WR_GRID = listOf(
        WrFilter(WrSmooth.RAW, note = "WR原线"),
        WrFilter(WrSmooth.MA3, note = "MA(WR,3)"),
        WrFilter(WrSmooth.MA5, note = "MA(WR,5)"),
        WrFilter(WrSmooth.MA8, note = "MA(WR,8)"),
        WrFilter(WrSmooth.EMA3, note = "EMA(WR,3)"),
        WrFilter(WrSmooth.EMA5, note = "EMA(WR,5)"),
        WrFilter(WrSmooth.RAW, sellCross2 = 65.0, note = "WR原线+65止盈"),
        WrFilter(WrSmooth.MA3, sellCross2 = 65.0, note = "MA(WR,3)+65止盈"),
        WrFilter(WrSmooth.RAW, gateBbiWindow = true, note = "WR原线+BB窗"),
        WrFilter(WrSmooth.MA3, gateBbiWindow = true, note = "MA(WR,3)+BB窗"),
        WrFilter(WrSmooth.RAW, requireBbiValley = true, note = "WR原线+BB谷底"),
        WrFilter(WrSmooth.MA3, requireBbiValley = true, note = "MA(WR,3)+BB谷底"),
        WrFilter(WrSmooth.RAW, requireBbiValley = true, gateBbiWindow = true, note = "WR原线+BB谷底+窗"),
        WrFilter(WrSmooth.MA3, requireBbiValley = true,
            requireBbiTrend = Smooth.MA3 to Smooth.MA12, note = "MA(WR,3)+BB谷底+MA3>MA12")
    )

    /**
     * WR 信号：
     * - 买入 `W1`：自上方下穿 `crossBuy`(65)，或（`strongBuy`>0 时）自上方下穿 `strongBuy`(20)
     * - 卖出 `W2`：自下方上穿 `crossSell`(25)，或（`sellCross2`>0 时）自下方上穿 `sellCross2`(65)
     * 买入前依次校验 BB 买入窗 / BB 谷底同根 / BB 多均线趋势三个可选闸门。
     */
    private fun wrSignals(
        bars: List<Bar>, wr: DoubleArray, f: WrFilter,
        valleySet: Set<Int>, win: BooleanArray, fast: DoubleArray?, slow: DoubleArray?
    ): List<Signal> {
        val out = ArrayList<Signal>(16)
        for (i in 1 until bars.size) {
            val p0 = wr[i - 1]; val c0 = wr[i]
            if (p0.isNaN() || c0.isNaN()) continue
            val crossBuy = p0 > f.crossBuy && c0 <= f.crossBuy
            val strongBuy = f.strongBuy > 0.0 && p0 > f.strongBuy && c0 <= f.strongBuy
            if (crossBuy || strongBuy) {
                val g1 = !f.gateBbiWindow || win[i]
                val g2 = !f.requireBbiValley || valleySet.contains(i)
                val g3 = fast == null || slow == null ||
                        (i in fast.indices && i in slow.indices &&
                                !fast[i].isNaN() && !slow[i].isNaN() && fast[i] > slow[i])
                if (g1 && g2 && g3) {
                    val lv = if (strongBuy) f.strongBuy else f.crossBuy
                    out.add(Signal(i, bars[i].date, bars[i].time, bars[i].close, true, "W1",
                        "WR·%s 下穿%.0f→%.1f%s".format(f.wrSmooth.label, lv, c0,
                            if (strongBuy) " · 强买(<20)" else ""), false, strongBuy))
                }
            }
            val sell1 = p0 < f.crossSell && c0 >= f.crossSell
            val sell2 = f.sellCross2 > 0.0 && p0 < f.sellCross2 && c0 >= f.sellCross2
            if (sell1 || sell2) {
                val lv = if (sell2 && !sell1) f.sellCross2 else f.crossSell
                out.add(Signal(i, bars[i].date, bars[i].time, bars[i].close, false, "W2",
                    "WR·%s 上穿%.0f→%.1f".format(f.wrSmooth.label, lv, c0), false))
            }
        }
        return out
    }

    /** WR 平滑口径 */
    private fun wrSmoothOf(s: WrSmooth, wr: DoubleArray): DoubleArray = when (s) {
        WrSmooth.RAW -> wr
        WrSmooth.MA3 -> SsaAlertEngine.sma(wr, 3)
        WrSmooth.MA5 -> SsaAlertEngine.sma(wr, 5)
        WrSmooth.MA8 -> SsaAlertEngine.sma(wr, 8)
        WrSmooth.EMA3 -> ema(wr, 3)
        WrSmooth.EMA5 -> ema(wr, 5)
    }

    private fun adxOf(bars: List<Bar>, n: Int): DoubleArray =
        SsaT0Engine.adxSeries(bars.map { SsaT0Engine.Bar(it.date, it.time, it.open, it.high, it.low, it.close) }, n)

    /**
     * 应用 [PivotFilter] 生成最终信号：
     * 买入 = 谷底确认 + 摆动幅度/ADX 过滤；卖出 = 峰顶确认，可选 BB 下穿其均线（双出口）。
     * 摆动幅度用**原始**拐点链测量（与是否被过滤无关），保证过滤后链条不断裂。
     */
    private fun buildSignals(
        bars: List<Bar>, seq: DoubleArray, f: PivotFilter,
        bb: DoubleArray, bbMa: DoubleArray, adx: DoubleArray, wr: DoubleArray,
        seriesOf: (Smooth) -> DoubleArray
    ): List<Signal> {
        val raw = ArrayList<Signal>(16)
        var lastPeakPx = Double.NaN       // 上一个原始峰顶拐点所在 K 的收盘价
        var lastValleyPx = Double.NaN     // 上一个原始谷底拐点所在 K 的收盘价
        val fast = f.trendFilter?.let { seriesOf(it.first) }
        val slow = f.trendFilter?.let { seriesOf(it.second) }
        /** WR(14) 国内口径：数值越大越超卖 */
        fun wrAt(i: Int) = if (i in wr.indices) wr[i] else Double.NaN
        for (i in 1 until seq.size - 1) {
            val a = seq[i - 1]; val b = seq[i]; val c = seq[i + 1]
            if (a.isNaN() || b.isNaN() || c.isNaN()) continue
            val ci = i + 1
            if (b <= a && c > b) {
                // 摆幅以**价格**度量：BB 为零中心震荡线（含负值），用比率会失真
                val px = bars[i].close
                val swing = if (lastPeakPx.isNaN() || px <= 0) Double.MAX_VALUE else (lastPeakPx / px - 1.0) * 100.0
                lastValleyPx = px
                if (swing + 1e-9 >= f.minSwingPct) {
                    val adxOk = f.minAdx <= 0.0 ||
                            (ci in adx.indices && !adx[ci].isNaN() && adx[ci] >= f.minAdx)
                    val trendOk = fast == null || slow == null ||
                            (ci in fast.indices && ci in slow.indices &&
                                    !fast[ci].isNaN() && !slow[ci].isNaN() && fast[ci] > slow[ci])
                    val wrOk = f.wrBuyMin <= 0.0 || (!wrAt(ci).isNaN() && wrAt(ci) >= f.wrBuyMin)
                    if (adxOk && trendOk && wrOk) {
                        raw.add(Signal(ci, bars[ci].date, bars[ci].time, bars[ci].close, true, "P1",
                            "BB 谷底拐点·%s(@%.3f) 摆幅%.2f%% WR%.1f".format(f.smooth.label, b, swing, wrAt(ci)), false))
                    }
                }
            } else if (b >= a && c < b) {
                val px = bars[i].close
                val swing = if (lastValleyPx.isNaN() || lastValleyPx <= 0) Double.MAX_VALUE else (px / lastValleyPx - 1.0) * 100.0
                lastPeakPx = px
                val wrOk = f.wrSellMax >= 100.0 || (!wrAt(ci).isNaN() && wrAt(ci) <= f.wrSellMax)
                if (swing + 1e-9 >= f.minSwingPct && wrOk) {
                    raw.add(Signal(ci, bars[ci].date, bars[ci].time, bars[ci].close, false, "P2",
                        "BB 峰顶拐点·%s(@%.3f) 摆幅%.2f%% WR%.1f".format(f.smooth.label, b, swing, wrAt(ci)), false))
                }
            }
        }
        if (f.exitOnBbiCrossDown) {
            // P3 双出口：BB（原线）下穿其均线 MA(BB, bbMaN)
            for (i in 1 until bars.size) {
                if (!bb[i - 1].isNaN() && !bbMa[i - 1].isNaN() && !bb[i].isNaN() && !bbMa[i].isNaN() &&
                    bb[i - 1] >= bbMa[i - 1] && bb[i] < bbMa[i]
                ) {
                    raw.add(Signal(i, bars[i].date, bars[i].time, bars[i].close, false, "P3",
                        "BB 下穿其均线 MA(BB)（双出口）", false))
                }
            }
        }
        return raw.sortedBy { it.index }
    }

    /** 金叉（fast 上穿 slow）→ 买入；死叉（fast 下穿 slow）→ 卖出；均在当根收盘确认。 */
    private fun crossSignals(
        bars: List<Bar>, fast: DoubleArray, slow: DoubleArray, f: CrossFilter, wr: DoubleArray
    ): List<Signal> {
        val out = ArrayList<Signal>(16)
        fun wrAt(i: Int) = if (i in wr.indices) wr[i] else Double.NaN
        for (i in 1 until bars.size) {
            if (fast[i - 1].isNaN() || slow[i - 1].isNaN() || fast[i].isNaN() || slow[i].isNaN()) continue
            if (fast[i - 1] <= slow[i - 1] && fast[i] > slow[i]) {
                if (f.wrBuyMin > 0.0 && (wrAt(i).isNaN() || wrAt(i) < f.wrBuyMin)) continue
                out.add(Signal(i, bars[i].date, bars[i].time, bars[i].close, true, "C1",
                    "BB 金叉·%s上穿%s(@%.3f) WR%.1f".format(f.fast.label, f.slow.label, fast[i], wrAt(i)), false))
            } else if (fast[i - 1] >= slow[i - 1] && fast[i] < slow[i]) {
                if (f.wrSellMax < 100.0 && (wrAt(i).isNaN() || wrAt(i) > f.wrSellMax)) continue
                out.add(Signal(i, bars[i].date, bars[i].time, bars[i].close, false, "C2",
                    "BB 死叉·%s下穿%s(@%.3f) WR%.1f".format(f.fast.label, f.slow.label, fast[i], wrAt(i)), false))
            }
        }
        return out
    }

    /**
     * @param window 收益率/评估窗口长度（根），默认 120 ≈ 30 交易日
     */
    fun run(
        bars: List<Bar>, window: Int = 120, p: AlertParams = AlertParams(),
        base: BaseSeries = BaseSeries.BB
    ): Result {
        require(bars.size >= 30) { "K 线不足 30 根，无法做 BB 布林极限拐点分析" }
        val n = bars.size
        val closes = DoubleArray(n) { bars[it].close }

        // 原始序列口径：默认布林极限（新）；BBI 分支复用 [SsaAlertEngine.bbiSeries]（旧口径对照）
        val (bb, bbMa) = when (base) {
            BaseSeries.BB -> bbSeries(closes, p.bbiMaN)
            BaseSeries.BBI -> SsaAlertEngine.bbiSeries(closes, p.bbiMaN)
        }
        val win = SsaAlertEngine.buyWindowSeries(bb, bbMa)
        val from = (n - window).coerceAtLeast(0)
        val to = n - 1
        val allOpen = BooleanArray(n) { true }

        val variants = ArrayList<VariantResult>(Smooth.values().size * 2)
        val baseName = if (base == BaseSeries.BBI) "BBI" else "BB"
        for (s in Smooth.values()) {
            val series = smoothOf(s, bb)
            val sigs = pivotSignals(bars, series, s, base = baseName)
            val (t0, p0) = simulate(bars, sigs, allOpen, from, to, 0.0, p)
            variants.add(VariantResult(s, false, series, sigs, p0, t0))
            val (t1, p1) = simulate(bars, sigs, win, from, to, 0.0, p)
            variants.add(VariantResult(s, true, series, sigs, p1, t1))
        }

        // 现有多指标基线：信号取自 analyze，但收益率必须在**同一评估窗口**上重算，
        // 否则 BASE 走 analyze 内置的 120 根窗口、与各变体的 window 不同，对比就失去意义。
        val base = SsaAlertEngine.analyze(bars, p)
        val basePerf = SsaAlertEngine.computePerf(bars, base.signals, base.buyWindowOpen, from, to, p)
        return Result(bars, bb, bbMa, from, variants, basePerf)
    }

    // ───────────────────── 逐笔配对模拟（与 computePerf 同语义） ─────────────────────

    /**
     * 单边做多、最多一仓、全部收盘价成交的配对模拟，语义与 [SsaAlertEngine.computePerf] 逐字节一致，
     * 额外支持**硬止损**（`stopPct>0` 时持仓期收盘价 ≤ 建仓价×(1-stopPct/100) 即平仓）。
     * 返回 `(逐笔明细, 聚合表现)`；`stopPct=0` 时聚合结果与原 `computePerf` 完全相同。
     */
    fun simulate(
        bars: List<Bar>, signals: List<Signal>, buyWindowOpen: BooleanArray,
        from: Int, to: Int, stopPct: Double, p: AlertParams = AlertParams(),
        /**
         * 移动止盈（%）：持仓期「最高收盘」回撤该幅度即**收盘确认平仓**（`exit = "trail"`）。
         * 与 [stopPct] 同为收盘口径（不用最低价，避免插针假破），0=关闭。
         */
        trailPct: Double = 0.0
    ): Pair<List<Trade>, AlertPerf> {
        val buyAt = HashMap<Int, Signal>()
        val sellAt = HashSet<Int>()
        signals.forEach { s ->
            if (s.index in from..to) { if (s.buy) buyAt.putIfAbsent(s.index, s) else sellAt.add(s.index) }
        }
        val feeMul = (1.0 - p.commissionRate) * (1.0 - p.commissionRate - p.stampDutyRate)
        val out = ArrayList<Trade>(16)
        var holding = false
        var entry = 0.0; var entryIdx = 0; var entryCond = ""
        var cum = 1.0; var wins = 0
        // 持仓期风险统计（全部收盘口径盯市）
        var peakEq = 1.0; var mdd = 0.0          // 权益曲线峰值 / 最大回撤
        var maeSum = 0.0                          // 各笔最大浮亏之和
        var mpdSum = 0.0                          // 各笔「相对持仓峰」最大回吐之和
        var peakClose = 0.0                       // 本笔持仓期最高收盘（移动止盈基准）
        var worst = 1.0                           // 本笔最大浮亏比
        var worstFromPeak = 0.0                   // 本笔相对持仓峰的最大回吐（%）

        fun closeTrade(i: Int, why: String) {
            val px = bars[i].close
            val mult = (px / entry) * (if (p.includeCost) feeMul else 1.0)
            cum *= mult
            out.add(Trade(entryIdx, "${bars[entryIdx].date} ${bars[entryIdx].time}", entry,
                i, "${bars[i].date} ${bars[i].time}", px, i - entryIdx, (mult - 1.0) * 100.0, why, entryCond))
            if (mult > 1.0) wins++
            maeSum += (worst - 1.0) * 100.0
            mpdSum += worstFromPeak
            holding = false
        }

        for (i in from..to) {
            if (!holding) {
                val b = buyAt[i]
                if (b != null && buyWindowOpen[i]) {
                    entry = bars[i].close; entryIdx = i; entryCond = b.cond; holding = true
                    peakClose = entry; worst = 1.0; worstFromPeak = 0.0
                }
                continue
            }
            // 先按本根收盘盯市：权益峰值/最大回撤、本笔最高收盘、本笔最大浮亏与最大回吐
            val ratio = bars[i].close / entry
            val eq = cum * ratio
            if (eq > peakEq) peakEq = eq
            val dd = (1.0 - eq / peakEq) * 100.0
            if (dd > mdd) mdd = dd
            if (ratio < worst) worst = ratio
            if (bars[i].close > peakClose) peakClose = bars[i].close
            val fromPeak = (1.0 - bars[i].close / peakClose) * 100.0
            if (fromPeak > worstFromPeak) worstFromPeak = fromPeak

            if (sellAt.contains(i)) {
                closeTrade(i, "signal")
            } else if (stopPct > 0.0 && bars[i].close <= entry * (1.0 - stopPct / 100.0)) {
                closeTrade(i, "stop")
            } else if (trailPct > 0.0 && bars[i].close <= peakClose * (1.0 - trailPct / 100.0)) {
                closeTrade(i, "trail")
            }
        }
        if (holding) closeTrade(to, "forced")

        val strategy = (cum - 1.0) * 100.0
        val bh = if (bars[from].close > 0.0) (bars[to].close / bars[from].close - 1.0) * 100.0 else 0.0
        return out to AlertPerf(
            fromIndex = from, toIndex = to,
            fromLabel = "${bars[from].date} ${bars[from].time}",
            toLabel = "${bars[to].date} ${bars[to].time}",
            strategyReturnPct = strategy, buyHoldReturnPct = bh, excessPct = strategy - bh,
            trades = out.size, winRatePct = if (out.isNotEmpty()) wins * 100.0 / out.size else 0.0,
            netOfCost = p.includeCost,
            maePct = if (out.isNotEmpty()) maeSum / out.size else 0.0,
            mpdPct = if (out.isNotEmpty()) mpdSum / out.size else 0.0,
            mddPct = mdd
        )
    }

    /** WR(14) 序列（国内口径 0~100，越大越超卖），复用指标引擎实现（App 图表副图也用本函数取数） */
    internal fun wrOf(bars: List<Bar>, n: Int): DoubleArray {
        val ib = bars.map { IndicatorBacktestEngine.Bar(it.date, it.open, it.high, it.low, it.close, it.volume) }
        return IndicatorBacktestEngine.wrSeries(ib, n)
    }

    /**
     * 谷底/峰顶拐点 → [Signal]（信号根 = 确认根 = i+1）
     *
     * @param base 被测序列口径名（`"BB"` 布林极限 / `"BBI"` 多空指标）。[Smooth] 的 label 是按 BB 口径
     *   书写的（如 `MA(BB,12)`），BBI 口径下需把其中的 `BB` 换成 `BBI`，否则清单「原因」列会张冠李戴。
     */
    private fun pivotSignals(bars: List<Bar>, seq: DoubleArray, s: Smooth, base: String = "BB"): List<Signal> {
        val out = ArrayList<Signal>(16)
        val name = if (base == "BB") s.label else s.label.replace("BB", base)
        for (i in 1 until seq.size - 1) {
            val a = seq[i - 1]; val b = seq[i]; val c = seq[i + 1]
            if (a.isNaN() || b.isNaN() || c.isNaN()) continue
            val ci = i + 1
            if (b <= a && c > b) {
                out.add(Signal(ci, bars[ci].date, bars[ci].time, bars[ci].close, true, "P1",
                    "%s 谷底拐点·%s(@%.3f)".format(base, name, b), false))
            } else if (b >= a && c < b) {
                out.add(Signal(ci, bars[ci].date, bars[ci].time, bars[ci].close, false, "P2",
                    "%s 峰顶拐点·%s(@%.3f)".format(base, name, b), false))
            }
        }
        return out
    }

    /**
     * **SSA 1H 周期分量拐点** → [Signal]（口径与 [pivotSignals] 完全一致：信号根 = 确认根 = i+1，
     * 触发价 = 确认根收盘）：
     * - 谷底 `c[i] <= c[i-1] && c[i+1] > c[i]` → i+1 根确认买入
     * - 峰顶 `c[i] >= c[i-1] && c[i+1] < c[i]` → i+1 根确认卖出
     */
    internal fun cyclePivots(bars: List<Bar>, cycle: DoubleArray): List<Signal> {
        val out = ArrayList<Signal>(16)
        for (i in 1 until cycle.size - 1) {
            val a = cycle[i - 1]; val b = cycle[i]; val c = cycle[i + 1]
            if (a.isNaN() || b.isNaN() || c.isNaN()) continue
            val ci = i + 1
            if (b <= a && c > b) {
                out.add(Signal(ci, bars[ci].date, bars[ci].time, bars[ci].close, true, "C1",
                    "SSA周期 谷底拐点·cycle(@%.3f)".format(b), false))
            } else if (b >= a && c < b) {
                out.add(Signal(ci, bars[ci].date, bars[ci].time, bars[ci].close, false, "C2",
                    "SSA周期 峰顶拐点·cycle(@%.3f)".format(b), false))
            }
        }
        return out
    }

    /**
     * **布林极限 BB**（通达信系统原版，非 BBI）：
     * ```
     * MID   := MA(C, 20);
     * VART3 := SQRT(MA((C - MID)^2, 20));        // 偏差平方的滚动均值再开方（≠ STD）
     * BB = (C - REF(MID,1) + REF(VART3,1)) / (4·REF(VART3,1)) × 100
     *    ≡ 25 + 25 × (C - REF(MID,1)) / REF(VART3,1);
     * ```
     * 三条与直觉不同的要点：① 上下轨/中轨取**前一根**（`REF(...,1)`）；② 分母 `UB-LB = 4·VART3`；
     * ③ 分母是 `VART3` 而非 `STD`。中心 = 25，量程约 −100~+150。
     *
     * 铁律：`REF` 值 NaN 或 `VART3 ≤ 0` → 该根 NaN（避免除零 / 前导 NaN 污染整条序列）。
     *
     * @param maN BB 的均线周期（[AlertParams.bbiMaN]）；原版第二条线为 `MA(BB,6)`。
     */
    internal fun bbSeries(closes: DoubleArray, maN: Int): Pair<DoubleArray, DoubleArray> {
        val n = closes.size
        val mid = SsaAlertEngine.sma(closes, BB_MID_N)
        val dev2 = DoubleArray(n) { val d = closes[it] - mid[it]; if (d.isNaN()) Double.NaN else d * d }
        val var2 = SsaAlertEngine.sma(dev2, BB_SIG_N)
        val var3 = DoubleArray(n) { val s = var2[it]; if (s.isNaN()) Double.NaN else sqrt(s) }
        val bb = DoubleArray(n) { Double.NaN }
        for (i in 1 until n) {
            val m = mid[i - 1]
            val s = var3[i - 1]
            if (m.isNaN() || s.isNaN() || s <= 0.0) continue
            bb[i] = 25.0 + 25.0 * (closes[i] - m) / s
        }
        return bb to SsaAlertEngine.sma(bb, maN)
    }

    private fun smoothOf(s: Smooth, bb: DoubleArray): DoubleArray = when (s) {
        Smooth.RAW -> bb
        Smooth.MA3 -> SsaAlertEngine.sma(bb, 3)
        Smooth.MA5 -> SsaAlertEngine.sma(bb, 5)
        Smooth.MA6 -> SsaAlertEngine.sma(bb, 6)
        Smooth.MA8 -> SsaAlertEngine.sma(bb, 8)
        Smooth.MA10 -> SsaAlertEngine.sma(bb, 10)
        Smooth.MA12 -> SsaAlertEngine.sma(bb, 12)
        Smooth.MA16 -> SsaAlertEngine.sma(bb, 16)
        Smooth.MA20 -> SsaAlertEngine.sma(bb, 20)
        Smooth.MA24 -> SsaAlertEngine.sma(bb, 24)
        Smooth.EMA3 -> ema(bb, 3)
        Smooth.EMA5 -> ema(bb, 5)
        Smooth.EMA8 -> ema(bb, 8)
        Smooth.EMA10 -> ema(bb, 10)
        Smooth.EMA12 -> ema(bb, 12)
        Smooth.EMA16 -> ema(bb, 16)
    }

    /** NaN-safe EMA：前 n 个有效值用 SMA 播种，其后递推；前导 NaN 保持 NaN */
    private fun ema(src: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(src.size) { Double.NaN }
        var first = -1
        for (i in src.indices) if (!src[i].isNaN()) { first = i; break }
        if (first < 0) return out
        val alpha = 2.0 / (n + 1)
        var seed = 0.0
        var prev = Double.NaN
        for (i in first until src.size) {
            if (prev.isNaN()) {
                seed += src[i]
                if (i - first >= n - 1) { prev = seed / n; out[i] = prev }
            } else {
                prev = alpha * src[i] + (1 - alpha) * prev
                out[i] = prev
            }
        }
        return out
    }

    // ───────────────────── BB × BBI 融合策略（信号层分工，不做数值混合） ─────────────────────

    /**
     * 融合策略模式。BB（σ 归一化振荡量，无量纲）与 BBI（价格尺度均线）**不能混成一根线**，
     * 只能在**拐点信号层**做角色分工 —— BBI(MA12) 平滑抗扰负责入场，BB 灵敏负责早避跌出场：
     * - [PURE_BBI12]：纯 BBI(MA12) 拐点 —— 谷底买 / 峰顶卖，最平稳
     * - [BBI12_BB6_OR]：BBI(MA12) 谷底买；出场取 **BBI(MA12) 峰顶 ∪ BB(MA6) 峰顶** 先到先出
     * - [BBI12_BB6_CYCLE_OR]：出场再并入 **SSA 1H 周期分量峰顶**（三源先到先出）
     * - [BBI12_BB6_BBWIN]：BBI(MA12) 谷底买 / BB(MA6) 峰顶卖，并以 **BB 买入窗**为建仓前提
     */
    enum class MixMode(val label: String) {
        PURE_BBI12("纯·BBI12底→BBI12顶"),
        BBI12_BB6_OR("BBI12底→(BB6∪BBI12)顶"),
        BBI12_BB6_CYCLE_OR("BBI12底→(BB6∪BBI12∪周期)顶"),
        BBI12_BB6_BBWIN("BBI12底→BB6顶·BB窗")
    }

    data class MixResult(
        val mode: MixMode,
        val signals: List<Signal>,
        val perf: AlertPerf,
        val trades: List<Trade>
    )

    data class MixBundle(
        val bars: List<Bar>,
        val windowFrom: Int,
        /** 新 BB（布林极限）原线（右轴） */
        val bb: DoubleArray,
        /** `MA(BB, 6)`：BB6 拐点线 + 右轴展示（与 [Smooth.MA6] 同源） */
        val bbMa6: DoubleArray,
        /** 旧 BBI（多空指标）原线（左轴） */
        val bbi: DoubleArray,
        /** `MA(BBI, 12)`：BBI12 拐点线 + 左轴展示（与 [Smooth.MA12] 同源） */
        val bbiMa12: DoubleArray,
        /** BB(MA6) 拐点（供核对图 / 拐点清单） */
        val bbPivots: List<Signal>,
        /** BBI(MA12) 拐点（供核对图 / 拐点清单） */
        val bbiPivots: List<Signal>,
        /** SSA 1H **周期分量**（价格尺度，元；`cycleL=30` 滚动、无未来函数），供副图与周期拐点对照 */
        val cycle: DoubleArray,
        /** SSA 1H 趋势分量（价格尺度，元） */
        val trend: DoubleArray,
        /** SSA 1H 周期分量的谷底/峰顶拐点 */
        val cyclePivots: List<Signal>,
        val results: List<MixResult>,
        /** 现有多指标策略在同一区间的基线表现（对照用） */
        val baseline: AlertPerf
    )

    /**
     * 一次性算好双侧（BB / BBI）原线与均线、两组拐点，输出 [modes] 各融合策略的成交与绩效。
     *
     * 与 [SsaBbiPivotPcTest] 的 HYBRID 网格**同口径**：入场源取 BB(MA6)/BBI(MA12)，
     * 出场源按 mode 组合（`OR` = 两口径峰顶先到先出），BB 窗闸门取 `buyWindowSeries(bb, MA(BB, bbiMaN))`
     * （与 [run] 的 `bbMa` 同源）；成交价 = 收盘价，`stopPct = 0`。
     *
     * @param window 收益率/评估窗口长度（根），默认 120 ≈ 30 交易日
     * @param cycleL SSA 周期分量滚动嵌入窗口（1H 层默认 30，与三层 SSA 口径一致）
     */
    fun runMix(
        bars: List<Bar>, window: Int = 120,
        modes: List<MixMode> = MixMode.values().toList(),
        p: AlertParams = AlertParams(),
        /** 持仓期硬止损（%）：收盘价 ≤ 建仓价×(1-stopPct/100) 即平仓；0=关闭 */
        stopPct: Double = 0.0,
        /** 持仓期移动止盈（%）：最高收盘回撤该幅度即平仓；0=关闭 */
        trailPct: Double = 0.0,
        /** SSA 周期分量窗口（1H 层默认 30） */
        cycleL: Int = 30
    ): MixBundle {
        require(bars.size >= 30) { "K 线不足 30 根，无法做 BB/BBI 融合分析" }
        val n = bars.size
        val closes = DoubleArray(n) { bars[it].close }
        // bbMaGate = MA(BB, bbiMaN)：BB 窗闸门均线，与 run()/hybridTrades 的 res.bbMa 同源
        val (bb, bbMaGate) = bbSeries(closes, p.bbiMaN)
        val bbMa6 = SsaAlertEngine.sma(bb, 6)
        val (bbi, bbiMa12) = SsaAlertEngine.bbiSeries(closes, 12)
        val bbPivots = pivotSignals(bars, bbMa6, Smooth.MA6)
        val bbiPivots = pivotSignals(bars, SsaAlertEngine.sma(bbi, 12), Smooth.MA12, base = "BBI")
        val bbSells = bbPivots.filter { !it.buy }
        val bbiBuys = bbiPivots.filter { it.buy }
        val bbiSells = bbiPivots.filter { !it.buy }
        // SSA 1H 周期分量（价格尺度，无未来函数）+ 其峰谷拐点
        val (trendSsa, cycleSsa) = SsaEngine.rollingTrendCycle(closes, cycleL)
        val cyclePiv = cyclePivots(bars, cycleSsa)
        val cycleSells = cyclePiv.filter { !it.buy }

        val from = (n - window).coerceAtLeast(0)
        val to = n - 1
        val allOpen = BooleanArray(n) { true }
        val bbWin = SsaAlertEngine.buyWindowSeries(bb, bbMaGate)

        val results = modes.map { m ->
            val (sigs, win) = when (m) {
                MixMode.PURE_BBI12 -> bbiPivots to allOpen
                MixMode.BBI12_BB6_OR -> (bbiBuys + bbiSells + bbSells) to allOpen
                MixMode.BBI12_BB6_CYCLE_OR -> (bbiBuys + bbiSells + bbSells + cycleSells) to allOpen
                MixMode.BBI12_BB6_BBWIN -> (bbiBuys + bbSells) to bbWin
            }.let { (raw, w) -> raw.sortedBy { it.index } to w }
            val (tr, perf) = simulate(bars, sigs, win, from, to, stopPct, p, trailPct)
            MixResult(m, sigs, perf, tr)
        }

        // 现有多指标基线：信号取自 analyze，收益率在**同一评估窗口**上重算（与 run() 一致）
        val base = SsaAlertEngine.analyze(bars, p)
        val basePerf = SsaAlertEngine.computePerf(bars, base.signals, base.buyWindowOpen, from, to, p)
        return MixBundle(bars, from, bb, bbMa6, bbi, bbiMa12, bbPivots, bbiPivots,
            cycleSsa, trendSsa, cyclePiv, results, basePerf)
    }
}
