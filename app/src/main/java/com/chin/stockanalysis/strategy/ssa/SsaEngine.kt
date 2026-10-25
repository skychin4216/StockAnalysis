package com.chin.stockanalysis.strategy.ssa

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ## SSA 奇异谱分析引擎（纯 Kotlin，无 Android 依赖）
 *
 * 标准 SSA 四步：嵌入(Hankel) → SVD → 分量分组 → 对角平均重构，
 * 将收盘价拆成 趋势中枢 / 周期振荡 / 随机噪声 三条序列；
 * 逐 K 只用历史数据推断下一根 K 的「正常波动区间 / 极限扰动区间」，
 * 用真实下一根收盘价做事后 A/B/C 情景分类与命中率评估，
 * 并按情景执行 A 正常 / B 保守 / C 清仓 的风控回测。
 *
 * ⚠️ 强制注释（设计前提，勿删）：
 * 1. SSA 属于历史时序分解，**不具备预测未来精准价格的能力**，只推断短期波动范围。
 * 2. 动态区间仅为短期波动范围的概率推断，不是目标价。
 * 3. C 类结构突破代表市场结构改变，历史 SSA 模型自动失效，必须离场。
 * 4. 交易信号与推断完全解耦：信号只用 t 时刻已知数据；A/B/C 是 t+1 事后评估，
 *    下单时能用到的最新情景是「已收盘」的那一根，**全程无未来函数**。
 * 5. 序列末尾 L 根 K 线存在边缘效应（Hankel 对角平均样本变少），区间可信度降低。
 */
object SsaEngine {

    const val INITIAL_CAPITAL = 100_000.0

    const val SCN_WARM = 0   // 暖机：尚无有效推断
    const val SCN_A = 1      // 正常稳态：落在正常区间内
    const val SCN_B = 2      // 强扰动：正常区间外、极限区间内
    const val SCN_C = 3      // 结构突破：击穿极限区间

    data class Bar(
        val date: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long = 0L
    )

    /**
     * @param windowL 嵌入窗口 L
     * @param lookbackStd 波动标准差回看期 N
     * @param cycleMult 正常区间倍数（cycle_std 的倍数）
     * @param noiseMult 极限区间额外叠加倍数（noise_std 的倍数）
     * @param stopNormalPct A 情景止损百分比（相对成本，正数，如 8.0=8%）
     * @param stopBPct B 情景收紧后的止损百分比
     * @param commissionRate 佣金（双边）
     * @param stampDutyRate 印花税（卖出单边）
     */
    data class Params(
        val windowL: Int = 30,
        val lookbackStd: Int = 20,
        val cycleMult: Double = 1.0,
        val noiseMult: Double = 1.0,
        val stopNormalPct: Double = 8.0,
        val stopBPct: Double = 4.0,
        val commissionRate: Double = 0.00025,
        val stampDutyRate: Double = 0.0005
    ) {
        fun validate(): String? = when {
            windowL !in 10..60 -> "嵌入窗口 L 需在 10~60"
            lookbackStd !in 5..60 -> "回看周期需在 5~60"
            cycleMult !in 0.3..3.0 -> "正常区间倍数需在 0.3~3.0"
            noiseMult !in 0.3..3.0 -> "极限区间倍数需在 0.3~3.0"
            stopNormalPct !in 2.0..20.0 -> "A 情景止损需在 2~20%"
            stopBPct !in 1.0..15.0 || stopBPct >= stopNormalPct -> "B 情景止损需在 1~15% 且小于 A 止损"
            else -> null
        }
    }

    data class Trade(
        val buyIndex: Int, val buyDate: String, val buyPrice: Double,
        val sellIndex: Int, val sellDate: String, val sellPrice: Double,
        val returnPct: Double, val barsHeld: Int,
        val exitReason: String,        // 趋势周期卖出 / C类清仓 / A止损 / B收紧止损 / 期末平仓
        val worstScenario: Int         // 持仓期遇到的最差情景 A<B<C
    )

    data class Marker(val index: Int, val price: Double, val buy: Boolean)

    /** 情景风控触发日志（每一次 B/C 风控动作一条） */
    data class RiskEvent(
        val index: Int,
        val date: String,
        val scenario: Int,     // SCN_B / SCN_C
        val action: String,    // 执行动作
        val price: Double
    )

    data class ScenarioStat(
        val scenario: Int,
        val barCount: Int,             // 该情景 K 线数（全样本）
        val holdingBars: Int,          // 持仓期间落在该情景的 K 线数
        val meanBarRetPct: Double,     // 持仓期间该情景 K 线的平均涨跌幅
        val upRatioPct: Double,        // 持仓期间该情景 K 线收阳比例
        val tradeCount: Int,           // 最差情景=本档的已平仓交易数
        val tradeWinRatePct: Double,
        val tradeAvgRetPct: Double
    )

    data class EvalReport(
        val validBars: Int,
        val aCount: Int, val bCount: Int, val cCount: Int,
        val aRatioPct: Double, val bRatioPct: Double, val cRatioPct: Double,
        val meanAbsBiasPct: Double,       // |close - mid| / mid 平均
        val meanNormalWidthPct: Double,   // 正常区间平均宽度 / mid
        val meanExtremeWidthPct: Double,  // 极限区间平均宽度 / mid
        val maxDevPct: Double,            // 极端偏离样本：max |close-mid|/mid
        val maxConsecutiveA: Int,
        val maxConsecutiveC: Int,
        val cEventDates: List<String>,    // 结构突破事件（最多保留 30 条）
        val bCountHolding: Int,           // 持仓期遇 B 的 K 线数
        val tightStopExits: Int,          // B 情景收紧止损触发次数
        val cClearExits: Int,             // C 类无条件清仓次数
        val bHalveExits: Int,             // B 情景仓位减半触发次数
        val scenarioStats: List<ScenarioStat>
    )

    data class Stats(
        val totalReturnPct: Double, val annualizedPct: Double,
        val maxDrawdownPct: Double, val winRatePct: Double,
        val tradeCount: Int, val winCount: Int,
        val avgWinPct: Double, val avgLossPct: Double,
        val profitFactor: Double, val avgHoldBars: Int,
        val benchmarkReturnPct: Double, val excessReturnPct: Double,
        val startDate: String, val endDate: String, val barCount: Int
    )

    /**
     * 全量回测结果。
     * 区间数组下标 t 表示「在 t-1 收盘推断、供第 t 根 K 使用」的区间（暖机期为 NaN）。
     */
    data class Result(
        val params: Params,
        val bars: List<Bar>,
        // —— 最终整窗 SSA 分解（仅用于可视化，交易不使用，避免未来函数） ——
        val trendFull: DoubleArray,
        val cycleFull: DoubleArray,
        val noiseFull: DoubleArray,
        val singularValues: DoubleArray,
        // —— 逐 K 滚动推断（无未来） ——
        val normalUp: DoubleArray, val normalDn: DoubleArray,
        val extremeUp: DoubleArray, val extremeDn: DoubleArray,
        val midLine: DoubleArray,           // 每根 K 的趋势中枢（t-1 时刻推断）
        val scenario: IntArray,             // 每根 K 的事后情景 A/B/C
        // —— 交易 ——
        val trades: List<Trade>,
        val markers: List<Marker>,
        val riskEvents: List<RiskEvent>,
        val equityCurve: DoubleArray,
        val benchmarkCurve: DoubleArray,
        val drawdownPct: DoubleArray,
        val eval: EvalReport,
        val stats: Stats
    )

    // ═══════════════════════════════ 主入口 ═══════════════════════════════

    fun run(bars: List<Bar>, params: Params = Params()): Result {
        params.validate()?.let { throw IllegalArgumentException(it) }
        val n = bars.size
        require(n >= params.windowL + 20) { "K线不足：SSA 滚动至少需要 L+20 根（当前 $n，L=${params.windowL}）" }
        val L = params.windowL
        val Nstd = params.lookbackStd
        val close = DoubleArray(n) { bars[it].close }

        // 滚动推断数组：下标 t = 供第 t 根 K 使用的区间（在 t-1 收盘生成）
        val nUp = DoubleArray(n) { Double.NaN }
        val nDn = DoubleArray(n) { Double.NaN }
        val eUp = DoubleArray(n) { Double.NaN }
        val eDn = DoubleArray(n) { Double.NaN }
        val mid = DoubleArray(n) { Double.NaN }
        // 滚动趋势/周期末端值，供 t 收盘信号使用
        val trendAt = DoubleArray(n) { Double.NaN }
        val cycleAt = DoubleArray(n) { Double.NaN }

        // 逐 K 滚动：每个 t 只用 [0 .. t] 的历史（不含任何未来K）独立完整 SSA 分解；
        // 仅丢弃最前面 L 根暖机样本，t>=L 之后全部保留；生成的区间仅供 t+1 使用（无未来函数）。
        for (t in L until n) {
            // t 收盘：用 [0 .. t] 全部历史做分解，取末端中枢，生成供 t+1 使用的区间
            val seg = close.copyOfRange(0, t + 1)
            val dec = decompose(seg, L)
            val m = seg.size
            trendAt[t] = dec.trend[m - 1]
            cycleAt[t] = dec.cycle[m - 1]
            if (t + 1 < n) {
                val cs = tailStd(dec.cycle, Nstd)
                val ns = tailStd(dec.noise, Nstd)
                val md = dec.trend[m - 1]
                mid[t + 1] = md
                nUp[t + 1] = md + params.cycleMult * cs
                nDn[t + 1] = md - params.cycleMult * cs
                eUp[t + 1] = md + params.cycleMult * cs + params.noiseMult * ns
                eDn[t + 1] = md - params.cycleMult * cs - params.noiseMult * ns
            }
        }

        // ── 事后情景分类（仅评估/统计；下单只使用已收盘 K 的情景） ──
        val scenario = IntArray(n) { SCN_WARM }
        for (t in 1 until n) {
            if (nUp[t].isNaN()) continue
            val c = close[t]
            scenario[t] = when {
                c in nDn[t]..nUp[t] -> SCN_A
                c in eDn[t]..eUp[t] -> SCN_B
                else -> SCN_C
            }
        }

        // ── 交易信号（t 收盘已知：趋势 + 周期拐点；进入 t+1 时最新情景=scenario[t]） ──
        val buySig = BooleanArray(n)
        val sellSig = BooleanArray(n)
        for (t in 2 until n) {
            if (trendAt[t].isNaN() || trendAt[t - 1].isNaN() ||
                cycleAt[t].isNaN() || cycleAt[t - 1].isNaN() || cycleAt[t - 2].isNaN()
            ) continue
            // 趋势向上 + 周期低位（零线下方）拐头
            if (trendAt[t] > trendAt[t - 1] &&
                cycleAt[t - 1] <= cycleAt[t - 2] && cycleAt[t] > cycleAt[t - 1] &&
                cycleAt[t - 1] < 0.0
            ) buySig[t] = true
            // 趋势向下 + 周期高位（零线上方）拐头
            if (trendAt[t] < trendAt[t - 1] &&
                cycleAt[t - 1] >= cycleAt[t - 2] && cycleAt[t] < cycleAt[t - 1] &&
                cycleAt[t - 1] > 0.0
            ) sellSig[t] = true
        }

        val sim = simulate(bars, close, buySig, sellSig, scenario, params)

        // ── 最终整窗 SSA 分解（仅画图用） ──
        val final = decompose(close, L)

        val eval = buildEval(bars, close, nUp, nDn, eUp, eDn, mid, scenario, sim)
        val stats = buildStats(bars, sim.trades, sim.equity, sim.benchmark, sim.finalEquity, sim.firstFill)
        return Result(
            params = params, bars = bars,
            trendFull = final.trend, cycleFull = final.cycle, noiseFull = final.noise,
            singularValues = final.singularValues,
            normalUp = nUp, normalDn = nDn, extremeUp = eUp, extremeDn = eDn,
            midLine = mid, scenario = scenario,
            trades = sim.trades, markers = sim.markers, riskEvents = sim.events,
            equityCurve = sim.equity, benchmarkCurve = sim.benchmark, drawdownPct = sim.drawdown,
            eval = eval, stats = stats
        )
    }

    // ═══════════════════════════════ SSA 分解 ═══════════════════════════════

    /**
     * 只算滚动**趋势 / 周期分量末端值**（与 [run] 里 `trendAt` / `cycleAt` **同源同口径**：每个 t 用
     * `[0..t]` 全历史独立做完整 SSA 分解，取末端值；`t < L` 为 NaN），跳过情景分类/交易配对/评估等重计算。
     *
     * 供 1H「周期分量副图」与「周期拐点对本策略有没有帮助」的对照回测使用。**无未来函数**
     * （第 t 根的值只用了 ≤ t 的收盘）。周期分量是**价格尺度**（元），与价格同轴可比。
     *
     * @param L 嵌入窗口长度（1H 层默认 30）
     */
    internal fun rollingTrendCycle(closes: DoubleArray, L: Int = 30): Pair<DoubleArray, DoubleArray> {
        val n = closes.size
        val trend = DoubleArray(n) { Double.NaN }
        val cycle = DoubleArray(n) { Double.NaN }
        for (t in L until n) {
            val seg = closes.copyOfRange(0, t + 1)
            val dec = decompose(seg, L)
            val m = seg.size
            trend[t] = dec.trend[m - 1]
            cycle[t] = dec.cycle[m - 1]
        }
        return trend to cycle
    }

    internal data class Decomp(
        val trend: DoubleArray, val cycle: DoubleArray,
        val noise: DoubleArray, val singularValues: DoubleArray
    )

    /**
     * 标准 SSA 四步：
     * 1) 嵌入：长度 Ns 序列 + 窗口 L → K×L 严格 Hankel 轨迹矩阵（K=Ns-L+1）
     * 2) SVD：对 L×L 的 XᵀX 做 Jacobi 特征分解（等价 SVD，σ=√λ）
     * 3) 分组：分量0=趋势；分量1,2 配对=主周期；其余=高频噪声
     * 4) 对角平均（Hankelization）重构三条时序
     */
    internal fun decompose(x: DoubleArray, L: Int): Decomp {
        val ns = x.size
        val K = ns - L + 1
        require(K >= 1) { "SSA 输入过短：Ns=$ns <= L=$L" }

        // Gram = XᵀX（L×L 对称半正定），G[a][b]=Σ_i X[i][a]X[i][b]，X[i][j]=x[i+j]
        val G = Array(L) { DoubleArray(L) }
        for (a in 0 until L) {
            for (b in a until L) {
                var s = 0.0
                for (i in 0 until K) s += x[i + a] * x[i + b]
                G[a][b] = s; G[b][a] = s
            }
        }
        val (eigVals, eigVecs) = jacobiSymmetric(G)
        // 降序排列
        val order = (0 until L).sortedByDescending { eigVals[it] }
        val sigma = DoubleArray(L) { sqrt(max(0.0, eigVals[order[it]])) }

        // 重构分组：g0={0} 趋势，g1={1,2} 周期（不足则退化），其余噪声
        val trend = reconstructGroup(x, L, order, intArrayOf(0), eigVecs, sigma)
        val cycle = if (L >= 3) reconstructGroup(x, L, order, intArrayOf(1, 2), eigVecs, sigma)
        else DoubleArray(ns)
        val noise = DoubleArray(ns) { i -> x[i] - trend[i] - cycle[i] }
        return Decomp(trend, cycle, noise, sigma)
    }

    /** 第 group 个（全局排序后）分量的秩1矩阵之和 → 对角平均；v 为特征向量列矩阵（按原列序） */
    private fun reconstructGroup(
        x: DoubleArray, L: Int, order: List<Int>,
        group: IntArray, eigVecs: Array<DoubleArray>, sigma: DoubleArray
    ): DoubleArray {
        val ns = x.size
        val K = ns - L + 1
        val Y = Array(K) { DoubleArray(L) }
        for (rank in group) {
            val origCol = order[rank]
            val sv = sigma[rank]
            if (sv < 1e-12) continue
            // a = X·v_origCol（K 向量）；秩1矩阵 = a·vᵀ（σu vᵀ = Xv vᵀ）
            val a = DoubleArray(K) { i ->
                var s = 0.0
                for (j in 0 until L) s += x[i + j] * eigVecs[j][origCol]
                s
            }
            for (i in 0 until K) {
                val ai = a[i]
                val Yi = Y[i]
                for (j in 0 until L) Yi[j] += ai * eigVecs[j][origCol]
            }
        }
        return diagonalAverage(Y, ns, K, L)
    }

    /** Hankel 对角平均：第 k 条反对角线均值，k=0..N-1（2026-10-10 修复：原循环漏 k=N-1，致重构末端恒为0） */
    private fun diagonalAverage(Y: Array<DoubleArray>, ns: Int, K: Int, L: Int): DoubleArray {
        val out = DoubleArray(ns)
        for (kk in 0..ns - 1) {
            val i0 = max(0, kk - L + 1)
            val i1 = min(kk, K - 1)
            var s = 0.0
            for (i in i0..i1) s += Y[i][kk - i]
            out[kk] = s / (i1 - i0 + 1)
        }
        return out
    }

    /**
     * 实对称矩阵 Jacobi 特征分解（循环最大非对角元法）。
     * @return (特征值, 列特征向量 V，V[i][col])，VᵀGV=diag(λ)
     */
    internal fun jacobiSymmetric(a: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val m = a.size
        val A = Array(m) { i -> DoubleArray(m) { j -> a[i][j] } }
        val V = Array(m) { i -> DoubleArray(m) { j -> if (i == j) 1.0 else 0.0 } }
        val maxSweeps = 100
        repeat(maxSweeps) {
            // 收敛判定：非对角平方和
            var off = 0.0
            for (i in 0 until m) for (j in i + 1 until m) off += A[i][j] * A[i][j]
            if (off < 1e-18) return@repeat
            for (p in 0 until m) {
                for (q in p + 1 until m) {
                    val apq = A[p][q]
                    if (abs(apq) < 1e-18) continue
                    val app = A[p][p]; val aqq = A[q][q]
                    val tau = (aqq - app) / (2.0 * apq)
                    val t = if (tau >= 0) 1.0 / (tau + sqrt(1.0 + tau * tau))
                    else -1.0 / (-tau + sqrt(1.0 + tau * tau))
                    val c = 1.0 / sqrt(1.0 + t * t)
                    val s = t * c
                    // A ← Jᵀ A J
                    for (k in 0 until m) {
                        val akp = A[k][p]; val akq = A[k][q]
                        A[k][p] = c * akp - s * akq
                        A[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until m) {
                        val apk = A[p][k]; val aqk = A[q][k]
                        A[p][k] = c * apk - s * aqk
                        A[q][k] = s * apk + c * aqk
                    }
                    for (k in 0 until m) {
                        val vkp = V[k][p]; val vkq = V[k][q]
                        V[k][p] = c * vkp - s * vkq
                        V[k][q] = s * vkp + c * vkq
                    }
                }
            }
        }
        val vals = DoubleArray(m) { A[it][it] }
        return vals to V
    }

    private fun tailStd(v: DoubleArray, n: Int): Double {
        var cnt = 0
        var sum = 0.0
        val from = max(0, v.size - n)
        for (i in from until v.size) if (!v[i].isNaN()) { sum += v[i]; cnt++ }
        if (cnt < 2) return 0.0
        val mean = sum / cnt
        var ss = 0.0
        for (i in from until v.size) if (!v[i].isNaN()) ss += (v[i] - mean).pow(2)
        return sqrt(ss / (cnt - 1))
    }

    // ═══════════════════════════════ 交易模拟（情景风控） ═══════════════════════════════

    private data class SimOut(
        val trades: List<Trade>, val markers: List<Marker>,
        val events: List<RiskEvent>,
        val equity: DoubleArray, val benchmark: DoubleArray,
        val drawdown: DoubleArray, val finalEquity: Double, val firstFill: Int,
        val tightStopExits: Int, val cClearExits: Int, val bHalveExits: Int
    )

    /**
     * 风控（全部在 t 收盘决策、t+1 开盘成交，无未来）：
     * - A：趋势向上+周期低位拐头 → 全仓开仓；正常止损
     * - B：不开新仓；本笔持仓第一次进入 B 立即减半；止损空间收紧到 stopBPct
     * - C：无条件清仓，且禁止开新仓，直到重新出现 A 类（行情重新收敛）
     */
    private fun simulate(
        bars: List<Bar>, close: DoubleArray,
        buySig: BooleanArray, sellSig: BooleanArray, scenario: IntArray,
        p: Params
    ): SimOut {
        val n = bars.size
        val trades = ArrayList<Trade>()
        val markers = ArrayList<Marker>()
        val events = ArrayList<RiskEvent>()
        val equity = DoubleArray(n)
        val bench = DoubleArray(n)

        var cash = INITIAL_CAPITAL
        var shares = 0L
        var buyPrice = 0.0
        var buyIndex = -1
        var firstFill = -1
        var tightStopExits = 0
        var cClearExits = 0
        var bHalveExits = 0
        var halved = false   // 本笔持仓是否已执行过 B 减半

        for (t in 0 until n) {
            if (t + 1 < n) {
                val fill = bars[t + 1].open
                // 进入 t+1 时，最新「已确认」情景 = scenario[t]
                val scn = scenario[t]
                if (shares > 0L) {
                    // ── 持仓侧优先级：C 清仓 > 趋势周期卖出 > 止损 > B 减半 ──
                    var reason: String? = null
                    var exitPrice = fill
                    if (scn == SCN_C) {
                        reason = "C类结构突破·无条件清仓"; cClearExits++
                        events.add(RiskEvent(t + 1, bars[t + 1].date, SCN_C,
                            "无条件清仓离场，暂停开仓直到重新收敛回A", fill))
                    } else if (sellSig[t]) {
                        reason = "趋势走弱·周期高位拐头"
                    } else {
                        // 止损：先看开盘是否直接跳空击穿，否则用当日最低价判断，成交价取止损价
                        val stopPct = if (scn == SCN_B) p.stopBPct else p.stopNormalPct
                        val stop = buyPrice * (1 - stopPct / 100.0)
                        if (fill <= stop) {
                            reason = if (scn == SCN_B) "B类扰动·收紧止损(${p.stopBPct.toInt()}%)"
                            else "A类止损(${p.stopNormalPct.toInt()}%)"
                            exitPrice = fill
                            if (scn == SCN_B) {
                                tightStopExits++
                                events.add(RiskEvent(t + 1, bars[t + 1].date, SCN_B,
                                    "收紧止损(${p.stopBPct.toInt()}%)清仓剩余仓位", fill))
                            }
                        } else if (bars[t + 1].low <= stop) {
                            reason = if (scn == SCN_B) "B类扰动·收紧止损(${p.stopBPct.toInt()}%)"
                            else "A类止损(${p.stopNormalPct.toInt()}%)"
                            exitPrice = stop
                            if (scn == SCN_B) {
                                tightStopExits++
                                events.add(RiskEvent(t + 1, bars[t + 1].date, SCN_B,
                                    "收紧止损(${p.stopBPct.toInt()}%)清仓剩余仓位", stop))
                            }
                        }
                    }
                    if (reason != null) {
                        val ret = closePosition(bars, p, t + 1, buyIndex, buyPrice, shares, cash, exitPrice, reason, scenario)
                        trades.add(ret.trade); markers.add(Marker(t + 1, exitPrice, false))
                        cash = ret.cashAfter; shares = 0; buyIndex = -1; halved = false
                    } else if (scn == SCN_B && !halved) {
                        // ── B 强扰动：不开新仓 + 本笔持仓首次进入 B 立即减半（整手） ──
                        halved = true
                        val sellLots = shares / 200          // 持仓的一半（向下取整手）
                        val sellShares = sellLots * 100
                        if (sellShares >= 100L) {
                            val proceeds = sellShares * fill *
                                (1 - p.commissionRate - p.stampDutyRate)
                            cash += proceeds
                            shares -= sellShares
                            bHalveExits++
                            markers.add(Marker(t + 1, fill, false))
                            events.add(RiskEvent(t + 1, bars[t + 1].date, SCN_B,
                                "禁止新开仓；持仓减半 $sellShares 股，止损收紧至${p.stopBPct.toInt()}%", fill))
                        } else {
                            events.add(RiskEvent(t + 1, bars[t + 1].date, SCN_B,
                                "禁止新开仓；持仓不足2手无法整手减半，止损收紧至${p.stopBPct.toInt()}%", fill))
                        }
                    }
                } else if (buySig[t] && scn == SCN_A) {
                    // ── 开仓侧：仅 A 类稳态允许开新仓（B/C 禁止；C 后必须等重新收敛到 A） ──
                    if (fill > 0) {
                        val lots = (cash * (1 - p.commissionRate) / fill / 100).toLong()
                        if (lots > 0) {
                            shares = lots * 100
                            cash -= shares * fill * (1 + p.commissionRate)
                            buyPrice = fill; buyIndex = t + 1; halved = false
                            if (firstFill < 0) firstFill = t + 1
                            markers.add(Marker(t + 1, fill, true))
                        }
                    }
                }
            }
            equity[t] = cash + shares * close[t]
        }
        if (shares > 0L) {
            val last = n - 1
            val ret = closePosition(bars, p, last, buyIndex, buyPrice, shares, cash, bars[last].close,
                "期末强制平仓", scenario)
            trades.add(ret.trade); markers.add(Marker(last, bars[last].close, false))
            cash = ret.cashAfter; shares = 0
            equity[last] = cash
        }

        // 基准：首笔开仓日起买入持有
        val start = if (firstFill in 0 until n) firstFill else 0
        val base = bars[start].open.takeIf { it > 0 } ?: bars[start].close
        for (i in 0 until n) {
            bench[i] = if (i < start || base <= 0) INITIAL_CAPITAL
            else INITIAL_CAPITAL * (close[i] / base)
        }
        // 回撤
        var peak = INITIAL_CAPITAL
        val dd = DoubleArray(n)
        for (i in 0 until n) {
            if (equity[i] > peak) peak = equity[i]
            dd[i] = (equity[i] - peak) / peak * 100
        }
        return SimOut(trades, markers, events, equity, bench, dd,
            if (shares == 0L) cash else equity.last(), firstFill,
            tightStopExits, cClearExits, bHalveExits)
    }

    private data class CloseOut(val trade: Trade, val cashAfter: Double)

    private fun closePosition(
        bars: List<Bar>, p: Params, sellIdx: Int, buyIdx: Int, buyPrice: Double,
        shares: Long, cashBefore: Double, exitPrice: Double, reason: String, scenario: IntArray
    ): CloseOut {
        val proceeds = shares * exitPrice * (1 - p.commissionRate - p.stampDutyRate)
        val cost = shares * buyPrice * (1 - p.commissionRate)
        var worst = SCN_A
        for (k in buyIdx..sellIdx) {
            if (scenario[k] == SCN_WARM) continue
            if (scenario[k] > worst) worst = scenario[k]
        }
        val t = Trade(
            buyIndex = buyIdx, buyDate = bars[buyIdx].date, buyPrice = buyPrice,
            sellIndex = sellIdx, sellDate = bars[sellIdx].date, sellPrice = exitPrice,
            returnPct = (proceeds - cost) / cost * 100,
            barsHeld = sellIdx - buyIdx, exitReason = reason, worstScenario = worst
        )
        return CloseOut(t, cashBefore + proceeds)
    }

    // ═══════════════════════════════ 评估报告 ═══════════════════════════════

    private fun buildEval(
        bars: List<Bar>, close: DoubleArray,
        nUp: DoubleArray, nDn: DoubleArray, eUp: DoubleArray, eDn: DoubleArray,
        mid: DoubleArray, scenario: IntArray, sim: SimOut
    ): EvalReport {
        val n = bars.size
        var valid = 0; var a = 0; var b = 0; var c = 0
        var biasSum = 0.0; var nwSum = 0.0; var ewSum = 0.0; var maxDev = 0.0
        val cDates = ArrayList<String>()
        for (t in 1 until n) {
            if (nUp[t].isNaN() || mid[t].isNaN() || mid[t] <= 0) continue
            valid++
            val bias = abs(close[t] - mid[t]) / mid[t] * 100
            biasSum += bias
            if (bias > maxDev) maxDev = bias
            nwSum += (nUp[t] - nDn[t]) / mid[t] * 100
            ewSum += (eUp[t] - eDn[t]) / mid[t] * 100
            when (scenario[t]) {
                SCN_A -> a++
                SCN_B -> { b++; if (cDates.size < 30) cDates.add("${bars[t].date} B") }
                SCN_C -> { c++; if (cDates.size < 30) cDates.add("${bars[t].date} C") }
            }
        }
        // 连续性
        var maxCA = 0; var curA = 0; var maxCC = 0; var curC = 0
        for (t in 1 until n) {
            if (scenario[t] == SCN_A) { curA++; if (curA > maxCA) maxCA = curA } else curA = 0
            if (scenario[t] == SCN_C) { curC++; if (curC > maxCC) maxCC = curC } else curC = 0
        }

        // 持仓期逐 K 表现按情景分组（用 markers 判定持仓区间：简化为买入~卖出间）
        val holdBars = IntArray(4)
        val holdRetSum = Array(4) { ArrayList<Double>() }
        val tradesByWorst = Array(4) { ArrayList<Double>() }
        // 用 trades 还原每根持仓 K
        sim.trades.forEach { tr ->
            for (k in tr.buyIndex..tr.sellIndex) {
                val s = scenario[k]
                if (s == SCN_WARM) continue
                holdBars[s]++
                if (k > tr.buyIndex) {
                    val r = (close[k] / close[k - 1] - 1) * 100
                    holdRetSum[s].add(r)
                }
            }
            tradesByWorst[tr.worstScenario].add(tr.returnPct)
        }
        val bHolding = holdBars[SCN_B]

        val scnStats = listOf(SCN_A, SCN_B, SCN_C).map { s ->
            val rets = holdRetSum[s]
            val trs = tradesByWorst[s]
            ScenarioStat(
                scenario = s,
                barCount = intArrayOf(0, a, b, c)[s],
                holdingBars = holdBars[s],
                meanBarRetPct = if (rets.isEmpty()) 0.0 else rets.average(),
                upRatioPct = if (rets.isEmpty()) 0.0 else rets.count { it > 0 }.toDouble() / rets.size * 100,
                tradeCount = trs.size,
                tradeWinRatePct = if (trs.isEmpty()) 0.0 else trs.count { it > 0 }.toDouble() / trs.size * 100,
                tradeAvgRetPct = if (trs.isEmpty()) 0.0 else trs.average()
            )
        }

        return EvalReport(
            validBars = valid,
            aCount = a, bCount = b, cCount = c,
            aRatioPct = pct(a, valid), bRatioPct = pct(b, valid), cRatioPct = pct(c, valid),
            meanAbsBiasPct = biasSum / valid.coerceAtLeast(1),
            meanNormalWidthPct = nwSum / valid.coerceAtLeast(1),
            meanExtremeWidthPct = ewSum / valid.coerceAtLeast(1),
            maxDevPct = maxDev,
            maxConsecutiveA = maxCA, maxConsecutiveC = maxCC,
            cEventDates = cDates,
            bCountHolding = bHolding,
            tightStopExits = sim.tightStopExits, cClearExits = sim.cClearExits,
            bHalveExits = sim.bHalveExits,
            scenarioStats = scnStats
        )
    }

    private fun pct(part: Int, total: Int) = if (total == 0) 0.0 else part.toDouble() / total * 100

    // ═══════════════════════════════ 常规统计 ═══════════════════════════════

    private fun buildStats(
        bars: List<Bar>, trades: List<Trade>, equity: DoubleArray,
        bench: DoubleArray, finalEquity: Double, startIdx: Int
    ): Stats {
        val totalPct = (finalEquity / INITIAL_CAPITAL - 1) * 100
        val active = (bars.size - startIdx).coerceAtLeast(1)
        val annual = ((finalEquity / INITIAL_CAPITAL).pow(250.0 / active) - 1) * 100
        var peak = INITIAL_CAPITAL; var mdd = 0.0
        for (v in equity) { if (v > peak) peak = v; val d = (v - peak) / peak; if (d < mdd) mdd = d }
        val wins = trades.filter { it.returnPct > 0 }
        val losses = trades.filter { it.returnPct <= 0 }
        val gw = wins.sumOf { abs(it.returnPct) }; val gl = losses.sumOf { abs(it.returnPct) }
        val pf = when { gl > 1e-9 -> gw / gl; gw > 0 -> Double.POSITIVE_INFINITY; else -> 0.0 }
        val benchPct = (bench.last() / INITIAL_CAPITAL - 1) * 100
        return Stats(
            totalReturnPct = totalPct, annualizedPct = annual,
            maxDrawdownPct = mdd * 100,
            winRatePct = if (trades.isEmpty()) 0.0 else wins.size.toDouble() / trades.size * 100,
            tradeCount = trades.size, winCount = wins.size,
            avgWinPct = if (wins.isEmpty()) 0.0 else wins.sumOf { it.returnPct } / wins.size,
            avgLossPct = if (losses.isEmpty()) 0.0 else losses.sumOf { it.returnPct } / losses.size,
            profitFactor = pf,
            avgHoldBars = if (trades.isEmpty()) 0 else trades.sumOf { it.barsHeld } / trades.size,
            benchmarkReturnPct = benchPct, excessReturnPct = totalPct - benchPct,
            startDate = bars.first().date, endDate = bars.last().date, barCount = bars.size
        )
    }
}
