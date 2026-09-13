package com.chin.stockanalysis.strategy.trade

import android.content.Context
import com.chin.stockanalysis.strategy.analysis.TechTags
import com.chin.stockanalysis.strategy.data.EtfCacheSync
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * ## ETF 选股思路实验室（2026-09-10 新增）
 *
 * 目的：为工作台·ETF 页提供一个**专门分析「如何选择 ETF」**的引擎 —— 把
 * `选股思路/ETF选股思路.txt` 的完整方法论落成可执行、可回测、可拟合、可修正的闭环：
 *
 *  1. **选股五条件**（日线底仓信号，思路文档「一/二」节）
 *     ① RAS 相对强度(ETF/沪深300) 20 日斜率 **绿转红**（相对走强）
 *     ② MACD 金叉 / DIF 底部拐头向上
 *     ③ OBV 在 OBV_MA20 上方（资金进场）
 *     ④ RSI(6) ∈ [30, 55]（低位未超买）
 *     ⑤ 距 250 日高点回撤 ∈ [40%, 60%]（成长赛道低位区，可放宽）
 *
 *  2. **买卖 / 做T 信号**（思路文档「二、三」节）
 *     · 底仓买入：日线五条件全中（T 日收盘判定，T+1 开盘可买）
 *     · 底仓清仓：RAS 转绿 / RSI6 > 75（超买）/ MACD 死叉
 *     · 正T 低吸：RSI6 ≤ 30 且 MACD 柱由绿转升（绿柱缩短），OBV 不创新低
 *     · 反T 高抛：RSI6 ≥ 70 且 MACD 红柱缩短
 *
 *  3. **回测**：对齐 PC `smalltools/_etf_buy.py` —— T 日收盘出信号、T+1 开盘成交、
 *     单仓状态机（持仓中忽略新信号、平仓后冷却 cool 天）、沪深300 结构多头门控、
 *     离场 tp/sl/hold；输出 笔数/胜率/盈亏比/总收益/年化/最大回撤。
 *
 *  4. **网格拟合**：在参数网格上搜索「信号阈值 × 离场参数」，用与 PC `exit_score`
 *     同口径的评分（胜率为主 + 盈亏比 + 收益 − 回撤）选最优；拟合结果可一键
 *     [Params] 应用为生效参数 → 重跑即采用，形成「回测 → 拟合 → 修正选股思路」闭环。
 *
 * 数据源：手机本地 `etf_cache.json`（[EtfCacheSync] 拉取，13 只 ETF + 沪深300，qfq 日K），
 * 与 PC 端同构同源，双端口径一致。
 */
object EtfStrategyLab {

    // ══════════════════════════ 参数 ══════════════════════════

    /**
     * 实验室生效参数：信号阈值 + 离场参数（可手动编辑 / 由拟合结果应用 / 恢复默认）。
     * 默认值取思路文档原文口径，与 PC `_etf_buy.py` 的发布参数 (tp+2%/sl-6%/30日/cool30) 对齐。
     */
    data class Params(
        val useGate: Boolean = true,
        val rsiLo: Double = 30.0,
        val rsiHi: Double = 55.0,
        val ddLo: Double = 40.0,      // 距250日高回撤下限 %（文档：成长ETF 40%~60%）
        val ddHi: Double = 60.0,      // 距250日高回撤上限 %
        val rsiOverbought: Double = 75.0,  // 底仓清仓超买线
        val tBuyRsi: Double = 30.0,   // 正T低吸 RSI 阈值
        val tSellRsi: Double = 70.0,  // 反T高抛 RSI 阈值
        val tp: Double = 2.0,         // 止盈 %
        val sl: Double = -6.0,        // 止损 %
        val hold: Int = 30,           // 时间离场（交易日）
        val cool: Int = 30            // 平仓后冷却（交易日）
    ) {
        fun toJson(): String = JSONObject().apply {
            put("useGate", useGate); put("rsiLo", rsiLo); put("rsiHi", rsiHi)
            put("ddLo", ddLo); put("ddHi", ddHi); put("rsiOverbought", rsiOverbought)
            put("tBuyRsi", tBuyRsi); put("tSellRsi", tSellRsi)
            put("tp", tp); put("sl", sl); put("hold", hold); put("cool", cool)
        }.toString()

        companion object {
            fun fromJson(text: String?): Params {
                if (text.isNullOrBlank()) return Params()
                return try {
                    val j = JSONObject(text)
                    Params(
                        useGate = j.optBoolean("useGate", true),
                        rsiLo = j.optDouble("rsiLo", 30.0),
                        rsiHi = j.optDouble("rsiHi", 55.0),
                        ddLo = j.optDouble("ddLo", 40.0),
                        ddHi = j.optDouble("ddHi", 60.0),
                        rsiOverbought = j.optDouble("rsiOverbought", 75.0),
                        tBuyRsi = j.optDouble("tBuyRsi", 30.0),
                        tSellRsi = j.optDouble("tSellRsi", 70.0),
                        tp = j.optDouble("tp", 2.0),
                        sl = j.optDouble("sl", -6.0),
                        hold = j.optInt("hold", 30),
                        cool = j.optInt("cool", 30)
                    )
                } catch (_: Exception) {
                    Params()
                }
            }
        }
    }

    private const val PREFS = "etf_strategy_lab"
    private const val KEY_PARAMS = "params"

    fun defaultParams(): Params = Params()

    fun loadParams(ctx: Context): Params = Params.fromJson(
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PARAMS, null)
    )

    fun saveParams(ctx: Context, p: Params) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PARAMS, p.toJson()).apply()
    }

    fun resetParams(ctx: Context): Params {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PARAMS).apply()
        return Params()
    }

    // ══════════════════════════ 结果模型 ══════════════════════════

    /** 选股判定卡：五条件命中情况 + 摘要指标（供 UI 表格逐行渲染）。 */
    data class PickCard(
        val code: String,
        val name: String,
        val close: Double,
        val dd250: Double,        // 距250日高回撤幅度 %（正数=跌了多少）
        val rsi6: Double,
        val ras: String,          // 绿转红 / 红转绿 / 强势 / 弱势
        val macd: String,         // 金叉 / 多头 / DIF拐头 / 死叉 / 空头
        val obv: String,          // 上行 / 下行
        val condHits: BooleanArray,   // ①..⑤ 命中
        val pass: Int,
        val selected: Boolean,
        val score: Double
    ) {
        /** "①②④" 形式的命中标记。 */
        fun hitText(): String {
            val marks = arrayOf("①", "②", "③", "④", "⑤")
            val sb = StringBuilder()
            condHits.forEachIndexed { i, hit -> if (hit) sb.append(marks.getOrElse(i) { "?" }) }
            return if (sb.isEmpty()) "—" else sb.toString()
        }
    }

    /** 当日可执行信号：底仓买入 / 底仓清仓 / 正T低吸 / 反T高抛。 */
    data class SignalRow(
        val code: String,
        val name: String,
        val close: Double,
        val kind: String,
        val detail: String
    )

    /** 一笔回测回合（T+1 开盘进、收盘离场）。 */
    data class Trade(
        val code: String,
        val entryDate: String,
        val entryPx: Double,
        val exitDate: String,
        val exitPx: Double,
        val pnlPct: Double,
        val days: Int
    )

    /** 回测统计（口径对齐 PC `_etf_buy.stats_of`）。 */
    data class Stats(
        val n: Int = 0,
        val winRate: Double = 0.0,
        val avgWin: Double = 0.0,
        val avgLoss: Double = 0.0,
        val profitFactor: Double = 0.0,
        val totalRet: Double = 0.0,
        val cagr: Double = 0.0,
        val mdd: Double = 0.0,
        val avgDays: Double = 0.0
    )

    /** 一组拟合候选（信号阈值 + 离场参数 + 统计 + 评分）。 */
    data class FitRow(
        val rsiHi: Double,
        val ddLo: Double,
        val ddHi: Double,
        val tp: Double,
        val sl: Double,
        val hold: Int,
        val stats: Stats,
        val score: Double
    ) {
        fun paramText(): String =
            "RSI≤${rsiHi.toInt()} · 回撤${ddLo.toInt()}~${ddHi.toInt()}% · tp${tp}% / sl${sl}% / ${hold}日"
    }

    /** 一次实验室运行的完整产出。 */
    data class Report(
        val asOf: String,
        val poolSize: Int,
        val gateOk: Boolean,
        val gateNote: String,
        val picks: List<PickCard>,
        val signals: List<SignalRow>,
        val trades: List<Trade>,
        val stats: Stats,
        val fits: List<FitRow>,
        val params: Params,
        val error: String? = null,
        val log: List<String> = emptyList()
    )

    // ══════════════════════════ 内部数据 ══════════════════════════

    private class Series(
        val code: String,
        val name: String,
        val dates: List<String>,
        val opens: List<Double>,
        val closes: List<Double>,
        val highs: List<Double>,
        val lows: List<Double>,
        val vols: List<Double>
    )

    /** 预计算指标序列（13 只 ETF × 1600 根只需算一次，拟合网格复用）。 */
    private class Ind(
        val s: Series,
        val ma20: List<Double>,
        val ma250: List<Double>,
        val rsi6: List<Double>,
        val obv: List<Double>,
        val obv20: List<Double>,
        val dd250: List<Double>,     // 回撤幅度 %（正数）
        val dif: List<Double>,
        val dea: List<Double>,
        val hist: List<Double>,
        val rasSlope: List<Double>,
        val rasGreen2Red: BooleanArray,
        val rasRed2Green: BooleanArray
    ) {
        val n get() = s.closes.size
    }

    // ══════════════════════════ 主流程 ══════════════════════════

    /** 同步运行（调用方自行切到 IO/Default 线程）。 */
    fun run(ctx: Context, params: Params): Report {
        val log = ArrayList<String>()
        val cache = try {
            EtfCacheSync(ctx).loadLocalCache()
        } catch (e: Exception) {
            null
        }
        if (cache == null || cache.length() == 0) {
            return Report(
                asOf = "-", poolSize = 0, gateOk = false, gateNote = "无本地 ETF 行情缓存",
                picks = emptyList(), signals = emptyList(), trades = emptyList(),
                stats = Stats(), fits = emptyList(), params = params,
                error = "本地无 etf_cache.json，请先在 ETF 页点「📈选股」同步行情"
            )
        }
        log.add("行情就绪：${cache.length()} 个标的")

        // ── 基准沪深300：门控 + RAS 相对强度 ──
        val bench = buildSeries(cache, EtfCacheSync.IDX_300.first)
        val gateDates = if (bench != null) buildGateDates(bench) else emptySet()
        val gateNote = buildGateNote(bench)
        val gateOk = bench?.let { b -> isGateOk(b, b.closes.size - 1) } ?: false
        log.add("门控：$gateNote")

        // ── 逐只 ETF 构建指标 ──
        val inds = ArrayList<Ind>()
        for ((code, _) in EtfCacheSync.POOL) {
            val s = buildSeries(cache, code) ?: continue
            if (s.closes.size < 260) continue
            inds.add(buildInd(s, bench))
        }
        log.add("参与判定：${inds.size} 只（样本≥260根）")
        if (inds.isEmpty()) {
            return Report(
                asOf = bench?.dates?.lastOrNull() ?: "-", poolSize = 0,
                gateOk = gateOk, gateNote = gateNote,
                picks = emptyList(), signals = emptyList(), trades = emptyList(),
                stats = Stats(), fits = emptyList(), params = params, log = log,
                error = "缓存内 ETF 样本不足 260 根，无法判定"
            )
        }

        // ── 1) 选股判定（最新交易日） ──
        val picks = inds.map { pickCard(it, params) }
            .sortedWith(compareByDescending<PickCard> { it.selected }
                .thenByDescending { it.pass }
                .thenByDescending { it.score })

        // ── 2) 当日买卖 / 做T 信号 ──
        val signals = buildSignals(inds, params)

        // ── 3) 当前参数回测 ──
        val allTrades = ArrayList<Trade>()
        for (ind in inds) {
            allTrades.addAll(backtest(ind, params, gateDates))
        }
        allTrades.sortWith(compareBy({ it.entryDate }, { it.code }))
        val stats = statsOf(allTrades)

        // ── 4) 网格拟合 ──
        val fits = fit(inds, params, gateDates)
        log.add("拟合候选 ${fits.size} 组，最佳评分 ${fits.firstOrNull()?.let { "%.1f".format(it.score) } ?: "-"}")

        val asOf = bench?.dates?.lastOrNull()
            ?: inds.maxOfOrNull { it.s.dates.lastOrNull() ?: "-" } ?: "-"
        return Report(
            asOf = asOf, poolSize = inds.size, gateOk = gateOk, gateNote = gateNote,
            picks = picks, signals = signals, trades = allTrades, stats = stats,
            fits = fits, params = params, log = log
        )
    }

    // ══════════════════════════ 序列 / 指标构建 ══════════════════════════

    private fun buildSeries(cache: JSONObject, code: String): Series? {
        val ent = cache.optJSONObject(code) ?: return null
        val arr = ent.optJSONArray("snaps") ?: return null
        if (arr.length() < 2) return null
        val name = ent.optString("name", code)
        val dates = ArrayList<String>(arr.length())
        val opens = ArrayList<Double>(arr.length())
        val closes = ArrayList<Double>(arr.length())
        val highs = ArrayList<Double>(arr.length())
        val lows = ArrayList<Double>(arr.length())
        val vols = ArrayList<Double>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val c = o.optDouble("close", 0.0)
            if (!c.isFinite() || c <= 0) continue
            dates.add(o.optString("date"))
            opens.add(o.optDouble("open", c))
            closes.add(c)
            highs.add(o.optDouble("high", c))
            lows.add(o.optDouble("low", c))
            vols.add(o.optDouble("volume", 0.0))
        }
        if (closes.size < 2) return null
        return Series(code, name, dates, opens, closes, highs, lows, vols)
    }

    private fun buildInd(s: Series, bench: Series?): Ind {
        val closes = s.closes
        val rsi6 = TechTags.rsi(closes, 6)
        val ma20 = TechTags.sma(closes, 20)
        val ma250 = TechTags.sma(closes, 250)
        val ema12 = TechTags.emaSeries(closes, 12)
        val ema26 = TechTags.emaSeries(closes, 26)
        val dif = List(closes.size) { ema12[it] - ema26[it] }
        val dea = TechTags.emaSeries(dif, 9)
        val hist = List(closes.size) { (dif[it] - dea[it]) * 2 }

        // OBV 与其 20 日均线
        val obv = ArrayList<Double>(closes.size)
        obv.add(0.0)
        for (i in 1 until closes.size) {
            obv.add(obv.last() + when {
                closes[i] > closes[i - 1] -> s.vols[i]
                closes[i] < closes[i - 1] -> -s.vols[i]
                else -> 0.0
            })
        }
        val obv20 = TechTags.sma(obv, 20)

        // 距 250 日高回撤幅度（%）
        val dd250 = ArrayList<Double>(closes.size)
        val win = ArrayDeque<Double>()
        for (i in closes.indices) {
            win.addLast(closes[i])
            if (win.size > 250) win.removeFirst()
            val peak = win.maxOrNull() ?: closes[i]
            dd250.add(if (peak > 0) (1 - closes[i] / peak) * 100.0 else 0.0)
        }

        // RAS = close_etf / close_bench，按日期对齐基准后前向填充
        val benchMap = HashMap<String, Double>()
        bench?.dates?.forEachIndexed { i, d -> benchMap[d] = bench.closes[i] }
        val rs = ArrayList<Double>(closes.size)
        var lastRs = Double.NaN
        for (i in closes.indices) {
            val b = benchMap[s.dates[i]]
            val cur = if (b != null && b > 0) closes[i] / b else Double.NaN
            if (cur.isFinite()) lastRs = cur
            rs.add(lastRs)
        }
        val rasSlope = ArrayList<Double>(closes.size)
        val g2r = BooleanArray(closes.size)
        val r2g = BooleanArray(closes.size)
        for (i in closes.indices) {
            if (i >= 20 && rs[i].isFinite() && rs[i - 20].isFinite()) {
                val sl = slope(rs, i, 20)
                rasSlope.add(sl)
                if (i >= 1) {
                    val prev = rasSlope[i - 1]
                    g2r[i] = prev < 0 && sl > 0
                    r2g[i] = prev > 0 && sl < 0
                }
            } else {
                rasSlope.add(0.0)
            }
        }
        return Ind(
            s = s, ma20 = ma20, ma250 = ma250, rsi6 = rsi6, obv = obv, obv20 = obv20,
            dd250 = dd250, dif = dif, dea = dea, hist = hist,
            rasSlope = rasSlope, rasGreen2Red = g2r, rasRed2Green = r2g
        )
    }

    /** 最小二乘斜率（窗口末点 = end，长度 win）。 */
    private fun slope(vals: List<Double>, end: Int, win: Int): Double {
        val start = end - win + 1
        if (start < 0) return 0.0
        val n = win
        val xMean = (n - 1) / 2.0
        var yMean = 0.0
        for (k in 0 until n) yMean += vals[start + k]
        yMean /= n
        var num = 0.0
        var den = 0.0
        for (k in 0 until n) {
            val dx = k - xMean
            num += dx * (vals[start + k] - yMean)
            den += dx * dx
        }
        return if (den == 0.0) 0.0 else num / den
    }

    // ══════════════════════════ 门控（沪深300 结构多头） ══════════════════════════

    private fun isGateOk(b: Series, i: Int): Boolean {
        val c = b.closes
        val m20 = TechTags.sma(c, 20)
        val m60 = TechTags.sma(c, 60)
        return i >= 60 && c[i] > m20[i] && m20[i] > m60[i]
    }

    private fun buildGateDates(b: Series): Set<String> {
        val c = b.closes
        val m20 = TechTags.sma(c, 20)
        val m60 = TechTags.sma(c, 60)
        val out = HashSet<String>()
        for (i in 60 until c.size) if (c[i] > m20[i] && m20[i] > m60[i]) out.add(b.dates[i])
        return out
    }

    private fun buildGateNote(b: Series?): String {
        if (b == null || b.closes.size < 60) return "沪深300 数据不足"
        val i = b.closes.size - 1
        val c = b.closes
        val m20 = TechTags.sma(c, 20).last()
        val m60 = TechTags.sma(c, 60).last()
        val state = when {
            c[i] > m20 && m20 > m60 -> "多头排列 ✅ 可执行低吸"
            c[i] < m20 && m20 < m60 -> "空头排列 ⛔ 暂停低吸"
            else -> "均线纠缠 ⚠ 谨慎"
        }
        return "沪深300 ${b.dates[i]} 收${"%.0f".format(c[i])} / MA20 ${"%.0f".format(m20)} / MA60 ${"%.0f".format(m60)} · $state"
    }

    // ══════════════════════════ 五条件判定 ══════════════════════════

    private fun condRas(ind: Ind, i: Int): Boolean = ind.rasGreen2Red.getOrElse(i) { false }

    private fun condMacd(ind: Ind, i: Int): Boolean {
        if (i < 2) return false
        val golden = ind.dif[i] > ind.dea[i]
        val bottomUp = ind.dif[i] > ind.dif[i - 1] && ind.dif[i - 1] <= ind.dif[i - 2]
        return golden || bottomUp
    }

    private fun condObv(ind: Ind, i: Int): Boolean = i >= 20 && ind.obv[i] > ind.obv20[i]

    private fun condRsi(ind: Ind, i: Int, p: Params): Boolean =
        ind.rsi6[i] >= p.rsiLo && ind.rsi6[i] <= p.rsiHi

    private fun condPos(ind: Ind, i: Int, p: Params): Boolean =
        ind.dd250[i] >= p.ddLo && ind.dd250[i] <= p.ddHi

    private fun hitAt(ind: Ind, i: Int, p: Params): BooleanArray = booleanArrayOf(
        condRas(ind, i), condMacd(ind, i), condObv(ind, i),
        condRsi(ind, i, p), condPos(ind, i, p)
    )

    // ══════════════════════════ 选股判定卡 ══════════════════════════

    private fun pickCard(ind: Ind, p: Params): PickCard {
        val i = ind.n - 1
        val hits = hitAt(ind, i, p)
        val pass = hits.count { it }
        val rasState = when {
            ind.rasGreen2Red.getOrElse(i) { false } -> "绿转红"
            ind.rasRed2Green.getOrElse(i) { false } -> "红转绿"
            ind.rasSlope.getOrElse(i) { 0.0 } > 0 -> "强势"
            else -> "弱势"
        }
        val macdState = when {
            i >= 1 && ind.dif[i] > ind.dea[i] && ind.dif[i - 1] <= ind.dea[i - 1] -> "金叉"
            ind.dif[i] > ind.dea[i] -> "多头"
            i >= 1 && ind.dif[i] < ind.dea[i] && ind.dif[i - 1] >= ind.dea[i - 1] -> "死叉"
            i >= 2 && ind.dif[i] > ind.dif[i - 1] -> "DIF拐头"
            else -> "空头"
        }
        val obvState = if (condObv(ind, i)) "上行" else "下行"
        // 推荐分：命中数为主，回撤越靠近区间中值、RAS 越强越高
        val ddMid = (p.ddLo + p.ddHi) / 2
        val score = pass * 100.0 - abs(ind.dd250[i] - ddMid) + if (rasState == "绿转红") 8 else 0
        return PickCard(
            code = ind.s.code, name = ind.s.name, close = ind.s.closes[i],
            dd250 = ind.dd250[i], rsi6 = ind.rsi6[i], ras = rasState, macd = macdState,
            obv = obvState, condHits = hits, pass = pass, selected = pass == 5, score = score
        )
    }

    // ══════════════════════════ 当日买卖 / 做T 信号 ══════════════════════════

    private fun buildSignals(inds: List<Ind>, p: Params): List<SignalRow> {
        val out = ArrayList<SignalRow>()
        for (ind in inds) {
            val i = ind.n - 1
            if (i < 2) continue
            val name = ind.s.name
            val code = ind.s.code
            val close = ind.s.closes[i]
            val hitAll = hitAt(ind, i, p).all { it }
            if (hitAll) {
                out.add(SignalRow(code, name, close, "底仓买入",
                    "五条件全中 · 回撤${"%.0f".format(ind.dd250[i])}% RSI6 ${"%.0f".format(ind.rsi6[i])}（次日开盘可买）"))
            }
            // 底仓清仓：RAS 转绿 / RSI 超买 / MACD 死叉
            val deathCross = ind.dif[i] < ind.dea[i] && ind.dif[i - 1] >= ind.dea[i - 1]
            if (ind.rasRed2Green.getOrElse(i) { false }) {
                out.add(SignalRow(code, name, close, "底仓清仓", "RAS 相对强度红转绿 · 主趋势转弱"))
            } else if (ind.rsi6[i] > p.rsiOverbought) {
                out.add(SignalRow(code, name, close, "底仓清仓",
                    "RSI6 ${"%.0f".format(ind.rsi6[i])} > ${p.rsiOverbought.toInt()} 超买"))
            } else if (deathCross) {
                out.add(SignalRow(code, name, close, "底仓清仓", "MACD 死叉"))
            }
            // 正T 低吸：RSI 超卖 + 绿柱缩短 + OBV 不创新低
            val histRising = ind.hist[i] > ind.hist[i - 1] && ind.hist[i - 1] < 0
            val obvNoNewLow = ind.obv[i] >= ind.obv[i - 1]
            if (ind.rsi6[i] <= p.tBuyRsi && histRising && obvNoNewLow) {
                out.add(SignalRow(code, name, close, "正T低吸",
                    "RSI6 ${"%.0f".format(ind.rsi6[i])} ≤ ${p.tBuyRsi.toInt()} · MACD绿柱缩短 · OBV不创新低（先买后卖）"))
            }
            // 反T 高抛：RSI 超买 + 红柱缩短
            val histFalling = ind.hist[i] < ind.hist[i - 1] && ind.hist[i - 1] > 0
            if (ind.rsi6[i] >= p.tSellRsi && histFalling) {
                out.add(SignalRow(code, name, close, "反T高抛",
                    "RSI6 ${"%.0f".format(ind.rsi6[i])} ≥ ${p.tSellRsi.toInt()} · MACD红柱缩短（先卖后买）"))
            }
        }
        return out
    }

    // ══════════════════════════ 回测 ══════════════════════════

    /** 单只 ETF 五条件信号 → T+1 开盘进、单仓状态机 + 冷却。 */
    private fun backtest(ind: Ind, p: Params, gateDates: Set<String>): List<Trade> {
        val s = ind.s
        val n = ind.n
        val trades = ArrayList<Trade>()
        var nextAllowed = 0
        var i = 250
        while (i < n - 1) {
            if (i < nextAllowed) { i++; continue }
            if (p.useGate && s.dates[i] !in gateDates) { i++; continue }
            if (!hitAt(ind, i, p).all { it }) { i++; continue }

            val entryIdx = i + 1
            val entryPx = s.opens[entryIdx]
            if (entryPx <= 0) { i++; continue }
            var exitIdx = -1
            val endIdx = min(entryIdx + p.hold, n - 1)
            var j = entryIdx + 1
            while (j <= endIdx) {
                val chg = (s.closes[j] / entryPx - 1) * 100
                if (chg >= p.tp || chg <= p.sl) { exitIdx = j; break }
                j++
            }
            if (exitIdx < 0) exitIdx = endIdx
            if (exitIdx <= entryIdx) { i++; continue }
            val exitPx = s.closes[exitIdx]
            trades.add(Trade(
                code = s.code, entryDate = s.dates[entryIdx], entryPx = entryPx,
                exitDate = s.dates[exitIdx], exitPx = exitPx,
                pnlPct = (exitPx / entryPx - 1) * 100,
                days = exitIdx - entryIdx
            ))
            nextAllowed = exitIdx + max(p.cool, 1)
            i = exitIdx + 1
        }
        return trades
    }

    private fun statsOf(trades: List<Trade>): Stats {
        if (trades.isEmpty()) return Stats()
        val wins = trades.filter { it.pnlPct > 0 }
        val losses = trades.filter { it.pnlPct <= 0 }
        var eq = 1.0
        var peak = 1.0
        var mdd = 0.0
        for (t in trades) {
            eq *= (1 + t.pnlPct / 100)
            peak = max(peak, eq)
            if (peak > 0) mdd = min(mdd, eq / peak - 1)
        }
        val lossSum = losses.sumOf { it.pnlPct }
        val winSum = wins.sumOf { it.pnlPct }
        val yrs = run {
            val ed = runCatching { LocalDate.parse(trades.maxOf { it.exitDate }) }.getOrNull()
            val sd = runCatching { LocalDate.parse(trades.minOf { it.entryDate }) }.getOrNull()
            if (ed != null && sd != null)
                max(java.time.temporal.ChronoUnit.DAYS.between(sd, ed) / 365.25, 0.1)
            else 0.1
        }
        val cagr = if (eq > 0) (eq.pow(1.0 / yrs) - 1) * 100 else -100.0
        return Stats(
            n = trades.size,
            winRate = wins.size.toDouble() / trades.size * 100,
            avgWin = if (wins.isNotEmpty()) winSum / wins.size else 0.0,
            avgLoss = if (losses.isNotEmpty()) lossSum / losses.size else 0.0,
            profitFactor = if (losses.isNotEmpty() && lossSum != 0.0) winSum / -lossSum else 99.0,
            totalRet = (eq - 1) * 100,
            cagr = cagr,
            mdd = mdd * 100,
            avgDays = trades.sumOf { it.days }.toDouble() / trades.size
        )
    }

    /** 与 PC `_etf_buy.exit_score` 同口径：胜率为主 + 盈亏比 + 收益 − 回撤惩罚。 */
    private fun exitScore(st: Stats): Double =
        st.winRate * 2.5 +
            max(min(st.profitFactor, 6.0), -6.0) * 4 +
            max(st.totalRet * 0.6, -60.0) +
            st.mdd * 0.3

    // ══════════════════════════ 网格拟合 ══════════════════════════

    private val RSI_HI_GRID = listOf(50.0, 55.0, 60.0)
    private val DD_LO_GRID = listOf(20.0, 30.0, 40.0)
    private val DD_HI_GRID = listOf(50.0, 60.0)
    private val TP_GRID = listOf(2.0, 3.0, 4.0)
    private val SL_GRID = listOf(-4.0, -6.0)
    private val HOLD_GRID = listOf(20, 30)

    private fun fit(inds: List<Ind>, base: Params, gateDates: Set<String>): List<FitRow> {
        val out = ArrayList<FitRow>()
        for (rsiHi in RSI_HI_GRID) for (ddLo in DD_LO_GRID) for (ddHi in DD_HI_GRID) {
            // 先算信号命中索引（信号阈值只影响三处），再对离场网格复用
            val sigIdx = HashMap<Ind, List<Int>>()
            val sp = base.copy(rsiHi = rsiHi, ddLo = ddLo, ddHi = ddHi)
            for (ind in inds) {
                val idx = ArrayList<Int>()
                var i = 250
                while (i < ind.n - 1) {
                    if (sp.useGate && ind.s.dates[i] !in gateDates) { i++; continue }
                    if (hitAt(ind, i, sp).all { it }) idx.add(i)
                    i++
                }
                if (idx.isNotEmpty()) sigIdx[ind] = idx
            }
            if (sigIdx.isEmpty()) continue
            for (tp in TP_GRID) for (sl in SL_GRID) for (hold in HOLD_GRID) {
                val trades = ArrayList<Trade>()
                for ((ind, idx) in sigIdx) {
                    trades.addAll(backtestFromSignals(ind, idx, tp, sl, hold, base.cool))
                }
                if (trades.size < 8) continue
                trades.sortWith(compareBy({ it.entryDate }, { it.code }))
                val st = statsOf(trades)
                out.add(FitRow(rsiHi, ddLo, ddHi, tp, sl, hold, st, exitScore(st)))
            }
        }
        out.sortByDescending { it.score }
        return if (out.size > 12) out.subList(0, 12).toList() else out
    }

    /** 用预计算的信号索引做回合模拟（避免拟合时重复扫描全序列）。 */
    private fun backtestFromSignals(
        ind: Ind, sigIdx: List<Int>, tp: Double, sl: Double, hold: Int, cool: Int
    ): List<Trade> {
        val s = ind.s
        val n = ind.n
        val trades = ArrayList<Trade>()
        var nextAllowed = 0
        for (i in sigIdx) {
            if (i < nextAllowed) continue
            val entryIdx = i + 1
            if (entryIdx >= n) continue
            val entryPx = s.opens[entryIdx]
            if (entryPx <= 0) continue
            var exitIdx = -1
            val endIdx = min(entryIdx + hold, n - 1)
            var j = entryIdx + 1
            while (j <= endIdx) {
                val chg = (s.closes[j] / entryPx - 1) * 100
                if (chg >= tp || chg <= sl) { exitIdx = j; break }
                j++
            }
            if (exitIdx < 0) exitIdx = endIdx
            if (exitIdx <= entryIdx) continue
            val exitPx = s.closes[exitIdx]
            trades.add(Trade(
                code = s.code, entryDate = s.dates[entryIdx], entryPx = entryPx,
                exitDate = s.dates[exitIdx], exitPx = exitPx,
                pnlPct = (exitPx / entryPx - 1) * 100, days = exitIdx - entryIdx
            ))
            nextAllowed = exitIdx + max(cool, 1)
        }
        return trades
    }
}
