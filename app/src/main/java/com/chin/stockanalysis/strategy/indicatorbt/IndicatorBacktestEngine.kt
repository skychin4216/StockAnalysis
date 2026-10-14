package com.chin.stockanalysis.strategy.indicatorbt

import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine.Plot
import com.chin.stockanalysis.strategy.techsignal.TechSignalEngine.Sig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * ## 单指标回测引擎（纯 Kotlin，无 Android 依赖）
 *
 * 输入日 K 序列 + 策略类型 + 参数，输出：
 * - 每根 bar 的买卖信号 / 实际成交点（次日开盘，模拟 T+1）
 * - 逐笔交易（买卖日期、价格、净收益率、持仓 K 线数）
 * - 资金曲线 / 基准（买入持有）曲线
 * - 统计：总收益、年化、最大回撤、胜率、盈亏比、交易次数、平均持仓
 *
 * A 股成交约定：
 * - 当日收盘出信号 → 次日开盘价成交（不使用未来数据）
 * - 全仓单边、一次只持有一笔、100 股整手
 * - 佣金双边万 2.5、卖出印花税万 5（可在 [BtParams] 调整）
 */
object IndicatorBacktestEngine {

    const val INITIAL_CAPITAL = 100_000.0

    /** 每根 K 收盘给出的次日操作建议（t+1 开盘执行） */
    const val BUY = 0
    const val SELL = 1
    const val HOLD_POS = 2
    const val HOLD_EMPTY = 3

    // ───────────────────────────── 数据模型 ─────────────────────────────

    data class Bar(
        val date: String,     // yyyy-MM-dd
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long = 0L
    )

    enum class StrategyType(val label: String) {
        MA_CROSS("双均线金叉(MA5/MA20)"),
        MACD("MACD 金叉/死叉"),
        RSI("RSI 超卖回升"),
        KDJ("KDJ 低位金叉"),
        BOLL("布林下轨反弹"),
        WR("WR 威廉超卖回升"),
        MULTI("多信号综合(5信号复合)")
    }

    /** 策略参数全集（不同策略只使用其中部分字段） */
    data class BtParams(
        val maShort: Int = 5,
        val maLong: Int = 20,
        val macdFast: Int = 12,
        val macdSlow: Int = 26,
        val macdSignal: Int = 9,
        val rsiPeriod: Int = 14,
        val rsiOversold: Double = 30.0,
        val rsiOverbought: Double = 70.0,
        val kdjN: Int = 9,
        val bollN: Int = 20,
        val bollK: Double = 2.0,
        // WR 采用国内软件口径：0~100，越大越超卖（>wrOversold 超卖，<wrOverbought 超买）
        val wrPeriod: Int = 14,
        val wrOversold: Double = 80.0,
        val wrOverbought: Double = 20.0,
        val commissionRate: Double = 0.00025,   // 佣金费率（双边）
        val stampDutyRate: Double = 0.0005,     // 印花税（卖出单边）
        // MULTI 多信号策略：启用的技术信号 key（macd/wr/rsi/obv/skdj，见 run() 中 MULTI 分支规则）
        val multiSignals: Set<String> = setOf("macd", "wr", "rsi", "obv", "skdj"),
        // MULTI：SKDJ 金叉触发窗口（金叉当日及之后 N 根 K 内，闸门满足即买入），0 = 仅金叉当日
        val multiCrossWindow: Int = 3
    ) {
        fun isValid(type: StrategyType): String? = when (type) {
            StrategyType.MA_CROSS ->
                if (maShort in 2..60 && maLong in 3..250 && maShort < maLong) null
                else "均线参数无效：要求 2≤短<长≤250"
            StrategyType.MACD ->
                if (macdFast in 2..30 && macdSlow in 5..60 && macdSignal in 2..20 && macdFast < macdSlow) null
                else "MACD 参数无效：要求 快<慢"
            StrategyType.RSI ->
                if (rsiPeriod in 2..60 && rsiOversold in 5.0..45.0 && rsiOverbought in 55.0..95.0 && rsiOversold < rsiOverbought) null
                else "RSI 参数无效（周期 2-60，超卖<超买）"
            StrategyType.KDJ ->
                if (kdjN in 2..60) null else "KDJ 周期无效（2-60）"
            StrategyType.BOLL ->
                if (bollN in 5..120 && bollK in 1.0..3.5) null else "布林参数无效（周期 5-120，倍数 1-3.5）"
            StrategyType.WR ->
                if (wrPeriod in 2..60 && wrOverbought in 5.0..45.0 && wrOversold in 55.0..95.0 && wrOverbought < wrOversold) null
                else "WR 参数无效（周期 2-60，超买<超卖，如 20/80）"
            StrategyType.MULTI ->
                if (multiSignals.isNotEmpty()) null else "请至少勾选一个技术信号"
        }
    }

    data class Trade(
        val buyIndex: Int,
        val buyDate: String,
        val buyPrice: Double,
        val sellIndex: Int,
        val sellDate: String,
        val sellPrice: Double,
        val returnPct: Double,      // 净收益率 %（扣费后）
        val barsHeld: Int,          // 持仓 K 线根数
        val forcedClose: Boolean = false,
        /** 买入的指标理由（信号日指标状态，复盘用） */
        val buyWhy: String = "",
        /** 卖出的指标理由（信号日指标状态；期末强平时为说明文案） */
        val sellWhy: String = ""
    )

    /** 图上一个买卖标注点（index 对齐 bars 下标） */
    data class Marker(val index: Int, val price: Double, val buy: Boolean)

    /** 叠加在主图 / 副图上的一条指标线 */
    data class IndLine(val name: String, val values: DoubleArray, val colorHint: Int = 0) {
        // colorHint: 0=橙 1=蓝 2=紫 3=灰 4=红 5=绿
    }

    /** 一类建议的滚动命中率统计 */
    data class AdviceStat(
        val count: Int,
        val win1Pct: Double,    // 次日（开盘→收盘）方向正确比例
        val win3Pct: Double,    // 3 日方向正确比例
        val avgRet5Pct: Double  // 5 日平均收益%（卖出统计中为负=避跌正确）
    )

    /** 近期单条建议快照：r1/r3/r5 = t 收盘 → t+k 收盘涨跌%（窗口未走完为 null） */
    data class DailyAdvice(
        val date: String, val advice: Int, val close: Double,
        val r1: Double?, val r3: Double?, val r5: Double?
    )

    data class Stats(
        val totalReturnPct: Double,
        val annualizedPct: Double,
        val maxDrawdownPct: Double,
        val winRatePct: Double,
        val tradeCount: Int,
        val winCount: Int,
        val avgWinPct: Double,
        val avgLossPct: Double,
        val profitFactor: Double,
        val avgHoldBars: Int,
        val benchmarkReturnPct: Double,
        val excessReturnPct: Double,
        val startDate: String,
        val endDate: String,
        val barCount: Int
    )

    data class Result(
        val type: StrategyType,
        val params: BtParams,
        val bars: List<Bar>,
        val trades: List<Trade>,
        val markers: List<Marker>,
        val equityCurve: DoubleArray,
        val benchmarkCurve: DoubleArray,
        val mainLines: List<IndLine>,      // 主图叠加线（MA / BOLL）
        val subTitle: String?,             // 副图标题（MACD/RSI/KDJ），null = 无副图
        val subLines: List<IndLine>,       // 副图线
        val subHist: DoubleArray?,         // 副图柱（MACD 柱）
        val multiPlots: List<Pair<Sig, Plot>>? = null,  // MULTI：6 信号副图（启用与否都画，便于对照）
        val stats: Stats,
        /** 每根 K 收盘给出的次日建议（BUY/SELL/HOLD_POS/HOLD_EMPTY） */
        val advice: IntArray,
        val buyStat: AdviceStat,
        val sellStat: AdviceStat,
        val holdPosStat: AdviceStat,
        val holdEmptyStat: AdviceStat,
        /** 近 30 条建议快照（t 收盘 → t+1/3/5 实际涨跌，供建议验证） */
        val recentAdvices: List<DailyAdvice>,
        val latestAdvice: Int,
        /** 最新一条建议为买/卖时的指标理由；持有/观望为 null */
        val latestAdviceWhy: String?,
        val latestDate: String,
        val latestClose: Double
    )

    // ───────────────────────────── 主入口 ─────────────────────────────

    fun run(type: StrategyType, bars: List<Bar>, params: BtParams = BtParams()): Result {
        require(bars.size >= 30) { "K线不足 30 根，无法回测" }
        params.isValid(type)?.let { throw IllegalArgumentException(it) }

        val n = bars.size
        val buySignal = BooleanArray(n)
        val sellSignal = BooleanArray(n)
        // 信号日的指标理由（复盘用）；同日多信号触发用「；」拼接（MULTI 策略）
        val buyWhy = arrayOfNulls<String>(n)
        val sellWhy = arrayOfNulls<String>(n)
        fun markBuy(i: Int, why: String) {
            buySignal[i] = true
            buyWhy[i] = if (buyWhy[i] == null) why else "${buyWhy[i]}；$why"
        }
        fun markSell(i: Int, why: String) {
            sellSignal[i] = true
            sellWhy[i] = if (sellWhy[i] == null) why else "${sellWhy[i]}；$why"
        }

        val mainLines = ArrayList<IndLine>()
        var subTitle: String? = null
        val subLines = ArrayList<IndLine>()
        var subHist: DoubleArray? = null

        when (type) {
            StrategyType.MA_CROSS -> {
                val s = smaSeries(closes(bars), params.maShort)
                val l = smaSeries(closes(bars), params.maLong)
                for (i in 1 until n) {
                    if (nv(s, i - 1) && nv(l, i - 1) && nv(s, i) && nv(l, i)) {
                        if (s[i - 1] <= l[i - 1] && s[i] > l[i])
                            markBuy(i, "MA%d(%.2f)上穿MA%d(%.2f)金叉".format(params.maShort, s[i], params.maLong, l[i]))
                        if (s[i - 1] >= l[i - 1] && s[i] < l[i])
                            markSell(i, "MA%d(%.2f)下穿MA%d(%.2f)死叉".format(params.maShort, s[i], params.maLong, l[i]))
                    }
                }
                mainLines.add(IndLine("MA${params.maShort}", s, 0))
                mainLines.add(IndLine("MA${params.maLong}", l, 1))
            }

            StrategyType.MACD -> {
                val (dif, dea, hist) = macdSeries(closes(bars), params.macdFast, params.macdSlow, params.macdSignal)
                for (i in 1 until n) {
                    if (nv(dif, i - 1) && nv(dea, i - 1) && nv(dif, i) && nv(dea, i)) {
                        if (dif[i - 1] <= dea[i - 1] && dif[i] > dea[i])
                            markBuy(i, "DIF(%.3f)上穿DEA(%.3f)金叉".format(dif[i], dea[i]))
                        if (dif[i - 1] >= dea[i - 1] && dif[i] < dea[i])
                            markSell(i, "DIF(%.3f)下穿DEA(%.3f)死叉".format(dif[i], dea[i]))
                    }
                }
                subTitle = "MACD(${params.macdFast},${params.macdSlow},${params.macdSignal})"
                subLines.add(IndLine("DIF", dif, 1))
                subLines.add(IndLine("DEA", dea, 2))
                subHist = hist
            }

            StrategyType.RSI -> {
                val rsi = rsiSeries(closes(bars), params.rsiPeriod)
                for (i in 1 until n) {
                    if (nv(rsi, i - 1) && nv(rsi, i)) {
                        // 低位上穿超卖线买入；高位上穿超买线后回落卖出
                        if (rsi[i - 1] < params.rsiOversold && rsi[i] >= params.rsiOversold)
                            markBuy(i, "RSI(%d) %.1f→%.1f 上穿超卖线%.0f（超卖回升）".format(
                                params.rsiPeriod, rsi[i - 1], rsi[i], params.rsiOversold))
                        if (rsi[i - 1] >= params.rsiOverbought && rsi[i] < params.rsiOverbought)
                            markSell(i, "RSI(%d) %.1f→%.1f 回落跌破超买线%.0f（超买回落）".format(
                                params.rsiPeriod, rsi[i - 1], rsi[i], params.rsiOverbought))
                    }
                }
                subTitle = "RSI(${params.rsiPeriod})"
                subLines.add(IndLine("RSI", rsi, 1))
            }

            StrategyType.KDJ -> {
                val (k, d, j) = kdjSeries(bars, params.kdjN)
                for (i in 1 until n) {
                    if (nv(k, i - 1) && nv(d, i - 1) && nv(k, i) && nv(d, i)) {
                        if (k[i - 1] <= d[i - 1] && k[i] > d[i] && k[i] < 50.0)
                            markBuy(i, "K(%.1f)上穿D(%.1f)且K<50（低位金叉）".format(k[i], d[i]))
                        if (k[i - 1] >= d[i - 1] && k[i] < d[i] && k[i] > 50.0)
                            markSell(i, "K(%.1f)下穿D(%.1f)且K>50（高位死叉）".format(k[i], d[i]))
                    }
                }
                subTitle = "KDJ(${params.kdjN})"
                subLines.add(IndLine("K", k, 0))
                subLines.add(IndLine("D", d, 1))
                subLines.add(IndLine("J", j, 2))
            }

            StrategyType.BOLL -> {
                val (upper, mid, lower) = bollSeries(closes(bars), params.bollN, params.bollK)
                val closes = closes(bars)
                for (i in 1 until n) {
                    if (nv(lower, i - 1) && nv(lower, i) && nv(upper, i)) {
                        // 前一日收在下轨之下（触轨），当日收回下轨之上 → 反弹买
                        if (closes[i - 1] < lower[i - 1] && closes[i] >= lower[i])
                            markBuy(i, "收盘%.2f收复下轨%.2f（触轨反弹）".format(closes[i], lower[i]))
                        // 收盘突破上轨 → 超买卖
                        if (closes[i] > upper[i])
                            markSell(i, "收盘%.2f突破上轨%.2f（超买离场）".format(closes[i], upper[i]))
                    }
                }
                mainLines.add(IndLine("UP", upper, 3))
                mainLines.add(IndLine("MID", mid, 1))
                mainLines.add(IndLine("LOW", lower, 3))
            }

            StrategyType.WR -> {
                val wr = wrSeries(bars, params.wrPeriod)
                for (i in 1 until n) {
                    if (nv(wr, i - 1) && nv(wr, i)) {
                        // 国内口径：WR 越大越超卖。从超卖区回落（拐头向上行情）买入；
                        // 从超买区抬升（高位回落）卖出
                        if (wr[i - 1] > params.wrOversold && wr[i] <= params.wrOversold)
                            markBuy(i, "WR(%d) %.1f→%.1f 退出超卖区(≤%.0f)拐头".format(
                                params.wrPeriod, wr[i - 1], wr[i], params.wrOversold))
                        if (wr[i - 1] < params.wrOverbought && wr[i] >= params.wrOverbought)
                            markSell(i, "WR(%d) %.1f→%.1f 进入超买区(≥%.0f)转弱".format(
                                params.wrPeriod, wr[i - 1], wr[i], params.wrOverbought))
                    }
                }
                subTitle = "WR(${params.wrPeriod})"
                subLines.add(IndLine("WR", wr, 1))
            }

            StrategyType.MULTI -> {
                // ── 「多信号综合」5 条件复合规则 ──
                // 1. MACD(6,13,9)：大趋势闸门 —— DIF>DEA（金叉后的多头区间）；
                //    红柱见顶后首次缩短 → 卖出
                // 2. WR(14)：从 >80 超卖区回落到 <65 → 允许买入；WR<25 为强势（买入/持有）；
                //    由 <25 上穿 ≥25 → 必须卖出
                // 3. SKDJ(9,3)：K 上穿 D 金叉 → 买入触发（须同时满足 1、2 两条闸门）；K 下穿 D → 卖出
                // 4. MAOBV：OBV 取 10 日均线后再做 EMA(3) 平滑，平滑线连续上升 → 允许买入
                // 5. RSI(6)：做 EMA(3) 平滑，平滑线上升 → 允许买入；平滑线转降
                //    仅作卖出辅助确认（须同时有 MACD 红柱缩短或 SKDJ 死叉才计入，不单独触发）
                // 买入 = 勾选信号的闸门全部满足，且（启用 SKDJ 时）出现 K 上穿 D 金叉
                // 卖出 = 勾选信号中 MACD / WR / SKDJ 任一成立；RSI 转降不单独触发
                val cl = closes(bars)
                val (dif, dea, hist) = macdSeries(cl, 6, 13, 9)
                val wr = wrSeries(bars, 14)
                val (k, d, _) = kdjSeries(bars, 9)
                val vol = DoubleArray(n) { bars[it].volume.toDouble() }
                val obv = DoubleArray(n)
                for (i in 1 until n) {
                    obv[i] = obv[i - 1] + when {
                        cl[i] > cl[i - 1] -> vol[i]
                        cl[i] < cl[i - 1] -> -vol[i]
                        else -> 0.0
                    }
                }
                val maobvS = emaSeries(smaSeries(obv, 10), 3)
                val rsiRaw = rsiSeries(cl, 6)
                val rsiS = emaSeries(rsiRaw, 3)

                val useMacd = "macd" in params.multiSignals
                val useWr = "wr" in params.multiSignals
                val useSkdj = "skdj" in params.multiSignals
                val useObv = "obv" in params.multiSignals
                val useRsi = "rsi" in params.multiSignals
                require(useMacd || useWr || useSkdj || useObv || useRsi) { "请至少勾选一个技术信号" }

                fun rising2(a: DoubleArray, i: Int) = nv(a, i) && nv(a, i - 1) && nv(a, i - 2) &&
                    a[i] > a[i - 1] && a[i - 1] > a[i - 2]

                val bMacd = ArrayList<Int>(); val sMacd = ArrayList<Int>()
                val bWr = ArrayList<Int>(); val sWr = ArrayList<Int>()
                val bSkdj = ArrayList<Int>(); val sSkdj = ArrayList<Int>()
                val bObv = ArrayList<Int>()
                val bRsi = ArrayList<Int>(); val sRsi = ArrayList<Int>()

                // SKDJ 金叉为买入触发，但在快速反转行情中金叉当日其余闸门常尚未转多，
                // 之后 K 又长期位于 D 之上不再金叉 → 会整段错过主升浪。
                // 故金叉当日及之后 skdjWindow 根 K 内，闸门一旦全部满足即视为有效买入触发。
                val skdjWindow = params.multiCrossWindow
                var lastCrossIdx = -1

                for (i in 2 until n) {
                    // 各信号的事件点（同时用于副图标注与主逻辑）
                    val macdBuy = nv(dif, i - 1) && nv(dea, i - 1) && nv(dif, i) && nv(dea, i) &&
                        dif[i - 1] <= dea[i - 1] && dif[i] > dea[i]
                    val macdSell = nv(hist, i) && nv(hist, i - 1) && nv(hist, i - 2) &&
                        hist[i] > 0 && hist[i - 1] > hist[i - 2] && hist[i] < hist[i - 1]
                    val wrBuy = nv(wr, i - 1) && nv(wr, i) && wr[i - 1] > 80.0 && wr[i] < 65.0
                    val wrSell = nv(wr, i - 1) && nv(wr, i) && wr[i - 1] < 25.0 && wr[i] >= 25.0
                    val skdjBuy = nv(k, i - 1) && nv(d, i - 1) && nv(k, i) && nv(d, i) &&
                        k[i - 1] <= d[i - 1] && k[i] > d[i]
                    val skdjSell = nv(k, i - 1) && nv(d, i - 1) && nv(k, i) && nv(d, i) &&
                        k[i - 1] >= d[i - 1] && k[i] < d[i]
                    val obvRising = rising2(maobvS, i)
                    val obvTurnUp = nv(maobvS, i - 2) && maobvS[i] > maobvS[i - 1] && maobvS[i - 1] <= maobvS[i - 2]
                    val rsiRising = rising2(rsiS, i)
                    val rsiFalling = nv(rsiS, i) && nv(rsiS, i - 1) && rsiS[i] < rsiS[i - 1]
                    val rsiTurnUp = nv(rsiS, i - 2) && rsiS[i] > rsiS[i - 1] && rsiS[i - 1] <= rsiS[i - 2]
                    val rsiTurnDown = nv(rsiS, i - 2) && rsiS[i] < rsiS[i - 1] && rsiS[i - 1] >= rsiS[i - 2]

                    if (macdBuy) bMacd.add(i)
                    if (macdSell) sMacd.add(i)
                    if (wrBuy) bWr.add(i)
                    if (wrSell) sWr.add(i)
                    if (skdjBuy) bSkdj.add(i)
                    if (skdjSell) sSkdj.add(i)
                    if (obvTurnUp) bObv.add(i)
                    if (rsiTurnUp) bRsi.add(i)
                    if (rsiTurnDown) sRsi.add(i)

                    // 闸门：勾选的信号才参与
                    if (skdjBuy) lastCrossIdx = i
                    val barsSinceCross = if (lastCrossIdx < 0) Int.MAX_VALUE else i - lastCrossIdx
                    val gateMacd = nv(dif, i) && nv(dea, i) && dif[i] > dea[i]
                    val gateWr = nv(wr, i) && wr[i] < 65.0
                    val gateOk = (!useMacd || gateMacd) && (!useWr || gateWr) &&
                        (!useObv || obvRising) && (!useRsi || rsiRising)
                    // 触发：未启用 SKDJ 时无需触发；启用时为金叉当日或金叉后 skdjWindow 根内
                    val triggerOk = !useSkdj || barsSinceCross <= skdjWindow

                    if (gateOk && triggerOk) {
                        val why = ArrayList<String>(5)
                        if (useMacd) why.add("MACD多头 DIF%.3f>DEA%.3f".format(dif[i], dea[i]))
                        if (useWr) why.add("WR14 %.1f<65（自超卖区回落）".format(wr[i]))
                        if (useSkdj) {
                            if (barsSinceCross == 0) why.add("SKDJ金叉 K%.1f上穿D%.1f".format(k[i], d[i]))
                            else why.add("SKDJ金叉后第%d根，闸门转多".format(barsSinceCross))
                        }
                        if (useObv) why.add("MAOBV10平滑线连续上升")
                        if (useRsi) why.add("RSI6平滑线连续上升")
                        markBuy(i, why.joinToString("；"))
                    }

                    // 卖出：MACD / WR / SKDJ 任一独立触发；
                    // RSI 平滑线转降仅作辅助确认，不单独触发卖出
                    val sellHits = ArrayList<String>(4)
                    if (useMacd && macdSell)
                        sellHits.add("MACD红柱见顶缩短 %.4f→%.4f".format(hist[i - 1], hist[i]))
                    if (useWr && wrSell)
                        sellHits.add("WR14 由%.1f上穿25（强势转弱）".format(wr[i - 1]))
                    if (useSkdj && skdjSell)
                        sellHits.add("SKDJ死叉 K%.1f下穿D%.1f".format(k[i], d[i]))
                    if (sellHits.isNotEmpty()) {
                        if (useRsi && rsiFalling)
                            sellHits.add("RSI6平滑线转降 %.2f→%.2f（辅助确认）".format(rsiS[i - 1], rsiS[i]))
                        markSell(i, sellHits.joinToString("；"))
                    }
                }

                // 副图：指标线 + 各信号自身事件标注（未启用也画，便于对照）
                val plots = listOf(
                    Sig.MACD to Plot(
                        listOf("DIF" to dif, "DEA" to dea), bars = hist,
                        refs = listOf(0.0 to ""), buys = bMacd, sells = sMacd
                    ),
                    Sig.WR to Plot(
                        listOf("WR14" to wr), refs = listOf(80.0 to "80", 65.0 to "65", 25.0 to "25"),
                        buys = bWr, sells = sWr, inverted = true
                    ),
                    Sig.RSI to Plot(
                        listOf("RSI6" to rsiRaw, "RSI6平滑" to rsiS),
                        refs = listOf(50.0 to "50", 80.0 to "80", 20.0 to "20"), buys = bRsi, sells = sRsi
                    ),
                    Sig.OBV to Plot(listOf("OBV" to obv, "MAOBV10平滑" to maobvS), buys = bObv),
                    Sig.SKDJ to Plot(
                        listOf("K" to k, "D" to d), refs = listOf(80.0 to "80", 20.0 to "20"),
                        buys = bSkdj, sells = sSkdj
                    )
                )
                return simulate(
                    bars, buySignal, sellSignal, params, type,
                    mainLines = emptyList(), subTitle = null, subLines = emptyList(), subHist = null,
                    multiPlots = plots, buyWhy = buyWhy, sellWhy = sellWhy
                )
            }
        }

        return simulate(bars, buySignal, sellSignal, params, type, mainLines, subTitle, subLines, subHist,
            buyWhy = buyWhy, sellWhy = sellWhy)
    }

    // ───────────────────────────── 交易模拟 ─────────────────────────────

    private fun simulate(
        bars: List<Bar>,
        buySignal: BooleanArray,
        sellSignal: BooleanArray,
        params: BtParams,
        type: StrategyType,
        mainLines: List<IndLine>,
        subTitle: String?,
        subLines: List<IndLine>,
        subHist: DoubleArray?,
        multiPlots: List<Pair<Sig, Plot>>? = null,
        buyWhy: Array<String?> = Array(bars.size) { null },
        sellWhy: Array<String?> = Array(bars.size) { null }
    ): Result {
        val n = bars.size
        val trades = ArrayList<Trade>()
        val markers = ArrayList<Marker>()
        val equity = DoubleArray(n)
        // 每根 K 收盘的次日建议（与成交状态机严格同口径）
        val advice = IntArray(n) { HOLD_EMPTY }

        var cash = INITIAL_CAPITAL
        var shares = 0L
        var buyPrice = 0.0
        var buyIndex = -1
        var openWhy = ""
        var firstFillIndex = -1

        for (i in 0 until n) {
            // 建议口径：i 收盘看到信号 + 当前持仓 → i+1 开盘的动作
            advice[i] = when {
                shares == 0L && buySignal[i] -> BUY
                shares > 0L && sellSignal[i] -> SELL
                shares > 0L -> HOLD_POS
                else -> HOLD_EMPTY
            }
            // 信号在 i 收盘，成交在 i+1 开盘
            if (i + 1 < n) {
                val fillPrice = bars[i + 1].open
                if (fillPrice > 0) {
                    if (shares == 0L && buySignal[i]) {
                        val lots = (cash * (1 - params.commissionRate) / fillPrice / 100).toLong()
                        if (lots > 0) {
                            shares = lots * 100
                            cash -= shares * fillPrice * (1 + params.commissionRate)
                            buyPrice = fillPrice
                            buyIndex = i + 1
                            openWhy = buyWhy[i] ?: ""
                            if (firstFillIndex < 0) firstFillIndex = i + 1
                            markers.add(Marker(i + 1, fillPrice, true))
                        }
                    } else if (shares > 0L && sellSignal[i]) {
                        val ret = closePosition(bars, params, i + 1, buyIndex, buyPrice, shares, cash,
                            forced = false, buyWhy = openWhy, sellWhy = sellWhy[i] ?: "")
                        trades.add(ret.trade)
                        markers.add(Marker(i + 1, fillPrice, false))
                        cash = ret.cashAfter
                        shares = 0
                        buyIndex = -1
                        openWhy = ""
                    }
                }
            }
            equity[i] = cash + shares * bars[i].close
        }

        // 末日强制平仓（按最后收盘）
        if (shares > 0L) {
            val last = n - 1
            val ret = closePosition(bars, params, last, buyIndex, buyPrice, shares, cash,
                forced = true, buyWhy = openWhy, sellWhy = "期末平仓（区间内无卖出信号）")
            trades.add(ret.trade)
            markers.add(Marker(last, bars[last].close, false))
            cash = ret.cashAfter
            shares = 0
            equity[last] = cash
        }

        // 基准：从第一笔开仓日起买入持有到回测结束（与策略同一观察窗口，公平对比）
        val benchmark = DoubleArray(n)
        val start = if (firstFillIndex in 0 until n) firstFillIndex else 0
        val basePrice = bars[start].open.takeIf { it > 0 } ?: bars[start].close
        for (i in 0 until n) {
            benchmark[i] = if (i < start || basePrice <= 0) INITIAL_CAPITAL
            else INITIAL_CAPITAL * (bars[i].close / basePrice)
        }
        // 策略在空仓且尚未开仓时资金曲线保持初始值（simulate 已满足）

        val finalEquity = if (shares == 0L) cash else equity.last()
        val stats = buildStats(bars, trades, equity, benchmark, finalEquity, start)

        // ── 建议滚动命中率（次日开盘成交 → t+1/t+3/t+5 收盘）──
        val openArr = DoubleArray(n) { bars[it].open }
        val closeArr = DoubleArray(n) { bars[it].close }
        fun hit(label: Int, wantUp: Boolean): AdviceStat {
            var cnt = 0; var w1 = 0; var w3 = 0; var sum5 = 0.0
            for (t in 0 until n) {
                if (advice[t] != label || t + 1 >= n) continue
                val op = openArr[t + 1]
                if (op <= 0) continue
                cnt++
                val r1 = (closeArr[min(t + 1, n - 1)] / op - 1) * 100
                val r3 = (closeArr[min(t + 3, n - 1)] / op - 1) * 100
                val r5 = (closeArr[min(t + 5, n - 1)] / op - 1) * 100
                val ok1 = if (wantUp) r1 > 0 else r1 < 0
                val ok3 = if (wantUp) r3 > 0 else r3 < 0
                if (ok1) w1++; if (ok3) w3++; sum5 += r5
            }
            return AdviceStat(cnt,
                if (cnt > 0) w1 * 100.0 / cnt else 0.0,
                if (cnt > 0) w3 * 100.0 / cnt else 0.0,
                if (cnt > 0) sum5 / cnt else 0.0)
        }
        val buySt = hit(BUY, true)
        val sellSt = hit(SELL, false)
        val hpSt = hit(HOLD_POS, true)
        val heSt = hit(HOLD_EMPTY, false)

        // ── 近 30 条建议快照（t 收盘 → t+k 收盘实际涨跌，最新一条待验证）──
        val rec = ArrayList<DailyAdvice>(30)
        var t0 = n - 1
        while (t0 >= 0 && rec.size < 30) {
            val r1 = if (t0 + 1 < n) (closeArr[t0 + 1] / closeArr[t0] - 1) * 100 else null
            val r3 = if (t0 + 3 < n) (closeArr[t0 + 3] / closeArr[t0] - 1) * 100 else null
            val r5 = if (t0 + 5 < n) (closeArr[t0 + 5] / closeArr[t0] - 1) * 100 else null
            rec.add(DailyAdvice(bars[t0].date, advice[t0], closeArr[t0], r1, r3, r5))
            t0--
        }
        rec.reverse()

        val latest = advice[n - 1]
        val latestWhy = when (latest) {
            BUY -> buyWhy[n - 1]
            SELL -> sellWhy[n - 1]
            else -> null
        }
        return Result(
            type = type, params = params, bars = bars, trades = trades, markers = markers,
            equityCurve = equity, benchmarkCurve = benchmark,
            mainLines = mainLines, subTitle = subTitle, subLines = subLines, subHist = subHist,
            multiPlots = multiPlots, stats = stats,
            advice = advice,
            buyStat = buySt, sellStat = sellSt, holdPosStat = hpSt, holdEmptyStat = heSt,
            recentAdvices = rec,
            latestAdvice = latest, latestAdviceWhy = latestWhy,
            latestDate = bars[n - 1].date, latestClose = closeArr[n - 1]
        )
    }

    private data class CloseResult(val trade: Trade, val cashAfter: Double)

    private fun closePosition(
        bars: List<Bar>, params: BtParams, sellIdx: Int, buyIdx: Int, buyPrice: Double,
        shares: Long, cashBefore: Double, forced: Boolean, buyWhy: String, sellWhy: String
    ): CloseResult {
        val sp = bars[sellIdx].close.takeIf { forced } ?: bars[sellIdx].open
        val proceeds = shares * sp * (1 - params.commissionRate - params.stampDutyRate)
        val cost = shares * buyPrice * (1 + params.commissionRate)
        val retPct = (proceeds - cost) / cost * 100.0
        val t = Trade(
            buyIndex = buyIdx, buyDate = bars[buyIdx].date, buyPrice = buyPrice,
            sellIndex = sellIdx, sellDate = bars[sellIdx].date, sellPrice = sp,
            returnPct = retPct, barsHeld = sellIdx - buyIdx, forcedClose = forced,
            buyWhy = buyWhy, sellWhy = sellWhy
        )
        return CloseResult(t, cashBefore + proceeds)
    }

    // ───────────────────────────── 统计 ─────────────────────────────

    private fun buildStats(
        bars: List<Bar>, trades: List<Trade>, equity: DoubleArray,
        benchmark: DoubleArray, finalEquity: Double, startIdx: Int
    ): Stats {
        val totalPct = (finalEquity / INITIAL_CAPITAL - 1) * 100
        val activeBars = (bars.size - startIdx).coerceAtLeast(1)
        val annual = ((finalEquity / INITIAL_CAPITAL).pow(250.0 / activeBars) - 1) * 100

        var peak = INITIAL_CAPITAL
        var mdd = 0.0
        for (v in equity) {
            if (v > peak) peak = v
            val dd = (v - peak) / peak
            if (dd < mdd) mdd = dd
        }

        val wins = trades.filter { it.returnPct > 0 }
        val losses = trades.filter { it.returnPct <= 0 }
        val winRate = if (trades.isNotEmpty()) wins.size.toDouble() / trades.size * 100 else 0.0
        val avgWin = if (wins.isNotEmpty()) wins.sumOf { it.returnPct } / wins.size else 0.0
        val avgLoss = if (losses.isNotEmpty()) losses.sumOf { it.returnPct } / losses.size else 0.0
        val grossWin = wins.sumOf { abs(it.returnPct) }
        val grossLoss = losses.sumOf { abs(it.returnPct) }
        val pf = if (grossLoss > 1e-9) grossWin / grossLoss else if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0
        val avgHold = if (trades.isNotEmpty()) trades.sumOf { it.barsHeld } / trades.size else 0
        val benchPct = (benchmark.last() / INITIAL_CAPITAL - 1) * 100

        return Stats(
            totalReturnPct = totalPct,
            annualizedPct = annual,
            maxDrawdownPct = mdd * 100,
            winRatePct = winRate,
            tradeCount = trades.size,
            winCount = wins.size,
            avgWinPct = avgWin,
            avgLossPct = avgLoss,
            profitFactor = pf,
            avgHoldBars = avgHold,
            benchmarkReturnPct = benchPct,
            excessReturnPct = totalPct - benchPct,
            startDate = bars.first().date,
            endDate = bars.last().date,
            barCount = bars.size
        )
    }

    // ───────────────────────────── 指标序列 ─────────────────────────────

    private fun closes(bars: List<Bar>): DoubleArray = DoubleArray(bars.size) { bars[it].close }
    private fun nv(a: DoubleArray, i: Int) = i in a.indices && !a[i].isNaN()

    fun smaSeries(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= n) sum -= v[i - n]
            if (i >= n - 1) out[i] = sum / n
        }
        return out
    }

    /**
     * EMA 序列：跳过前导 NaN，从首个有效值起的 n 个有效值用 SMA 播种，之前保持 NaN。
     * 输入为原始序列（无 NaN）时等价于原「前 n-1 个 NaN」实现。
     */
    fun emaSeries(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        var first = -1
        for (i in v.indices) if (!v[i].isNaN()) { first = i; break }
        if (first < 0 || v.size - first < n) return out
        var seed = 0.0
        for (i in first until first + n) seed += v[i]
        var ema = seed / n
        out[first + n - 1] = ema
        val k = 2.0 / (n + 1)
        for (i in first + n until v.size) {
            ema = v[i] * k + ema * (1 - k)
            out[i] = ema
        }
        return out
    }

    fun macdSeries(v: DoubleArray, fast: Int, slow: Int, signal: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val ef = emaSeries(v, fast)
        val es = emaSeries(v, slow)
        val dif = DoubleArray(v.size) { Double.NaN }
        var first = -1
        for (i in v.indices) if (!ef[i].isNaN() && !es[i].isNaN()) { dif[i] = ef[i] - es[i]; if (first < 0) first = i }
        val dea = DoubleArray(v.size) { Double.NaN }
        val hist = DoubleArray(v.size) { Double.NaN }
        if (first >= 0 && v.size - first >= signal) {
            var acc = 0.0
            for (j in 0 until signal) acc += dif[first + j]
            var d = acc / signal
            dea[first + signal - 1] = d
            hist[first + signal - 1] = (dif[first + signal - 1] - d) * 2
            val k = 2.0 / (signal + 1)
            for (i in first + signal until v.size) {
                d = dif[i] * k + d * (1 - k)
                dea[i] = d
                hist[i] = (dif[i] - d) * 2
            }
        }
        return Triple(dif, dea, hist)
    }

    /** Wilder RSI 序列 */
    fun rsiSeries(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (v.size <= n) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = v[i] - v[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        var avgGain = gain / n
        var avgLoss = loss / n
        out[n] = if (avgLoss == 0.0) 100.0 else 100 - 100 / (1 + avgGain / avgLoss)
        for (i in n + 1 until v.size) {
            val d = v[i] - v[i - 1]
            avgGain = (avgGain * (n - 1) + max(d, 0.0)) / n
            avgLoss = (avgLoss * (n - 1) + max(-d, 0.0)) / n
            out[i] = if (avgLoss == 0.0) 100.0 else 100 - 100 / (1 + avgGain / avgLoss)
        }
        return out
    }

    fun kdjSeries(bars: List<Bar>, n: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val kArr = DoubleArray(bars.size) { Double.NaN }
        val dArr = DoubleArray(bars.size) { Double.NaN }
        val jArr = DoubleArray(bars.size) { Double.NaN }
        var prevK = 50.0
        var prevD = 50.0
        for (i in bars.indices) {
            if (i < n - 1) continue
            var hh = Double.MIN_VALUE
            var ll = Double.MAX_VALUE
            for (j in i - n + 1..i) {
                if (bars[j].high > hh) hh = bars[j].high
                if (bars[j].low < ll) ll = bars[j].low
            }
            val rsv = if (hh != ll) (bars[i].close - ll) / (hh - ll) * 100 else 50.0
            val k = 2.0 / 3.0 * prevK + 1.0 / 3.0 * rsv
            val d = 2.0 / 3.0 * prevD + 1.0 / 3.0 * k
            kArr[i] = k
            dArr[i] = d
            jArr[i] = 3 * k - 2 * d
            prevK = k
            prevD = d
        }
        return Triple(kArr, dArr, jArr)
    }

    /**
     * 威廉指标（国内软件口径）：WR = (HH(n) - C) / (HH(n) - LL(n)) * 100，范围 0~100。
     * 数值越大越接近区间低点 → 越超卖；越小越超买。
     */
    fun wrSeries(bars: List<Bar>, n: Int): DoubleArray {
        val out = DoubleArray(bars.size) { Double.NaN }
        for (i in n - 1 until bars.size) {
            var hh = Double.MIN_VALUE
            var ll = Double.MAX_VALUE
            for (j in i - n + 1..i) {
                if (bars[j].high > hh) hh = bars[j].high
                if (bars[j].low < ll) ll = bars[j].low
            }
            out[i] = if (hh != ll) (hh - bars[i].close) / (hh - ll) * 100 else 50.0
        }
        return out
    }

    fun bollSeries(v: DoubleArray, n: Int, k: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val mid = smaSeries(v, n)
        val up = DoubleArray(v.size) { Double.NaN }
        val lo = DoubleArray(v.size) { Double.NaN }
        for (i in n - 1 until v.size) {
            val m = mid[i]
            var variance = 0.0
            for (j in i - n + 1..i) variance += (v[j] - m).pow(2)
            val sd = sqrt(variance / n)
            up[i] = m + k * sd
            lo[i] = m - k * sd
        }
        return Triple(up, mid, lo)
    }

    private fun sqrt(x: Double) = kotlin.math.sqrt(x)
}
