package com.chin.stockanalysis.strategy.ssa

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * PC 端（JVM 单测）对比 **BBI 拐点策略** 各平滑口径 vs **现有多指标策略** 的 1H 回测表现，
 * 第五轮额外输出**逐笔成交明细**（`build/ssa_bbi_pivot_trades.csv`）用于亏损归因，
 * 并对比 **WR(14) 闸门**与**硬止损**对收益/胜率的增量。
 *
 * 多只样本股（默认 12 只，可用环境变量 `SSA_PIVOT_CODES` 覆盖）：
 * 抓末 1023 根 60 分钟 K → 取末 500 根计算 → 收益率区间 = 末 480 根（≈120 交易日）。
 * 结果写入 `build/ssa_bbi_pivot_compare.csv`、`build/ssa_bbi_pivot_signals.csv`、`build/ssa_bbi_pivot_trades.csv`。
 */
class SsaBbiPivotPcTest {

    private data class Row(
        val code: String, val variant: String,
        val strategy: Double, val buyHold: Double, val excess: Double,
        val trades: Int, val winRate: Double
    )

    /** 并排对照的代表性平滑口径（原线 / MA6 / MA12 / MA16） */
    private val REP_SMOOTHS = listOf(
        SsaBbiPivotEngine.Smooth.RAW, SsaBbiPivotEngine.Smooth.MA6,
        SsaBbiPivotEngine.Smooth.MA12, SsaBbiPivotEngine.Smooth.MA16
    )

    /** 中文短名（用于并排表首列） */
    private fun repName(s: SsaBbiPivotEngine.Smooth): String = when (s) {
        SsaBbiPivotEngine.Smooth.RAW -> "原线"
        SsaBbiPivotEngine.Smooth.MA6 -> "MA6"
        SsaBbiPivotEngine.Smooth.MA12 -> "MA12"
        SsaBbiPivotEngine.Smooth.MA16 -> "MA16"
        else -> s.label
    }

    /** 旧 BBI 口径下代表性变体的展示名（BB 语义改为 BBI，买入窗统一后缀「+窗」） */
    private fun bbiRepLabel(s: SsaBbiPivotEngine.Smooth, gated: Boolean): String =
        s.label.replace("BB", "BBI") + if (gated) "+窗" else ""

    // ─────────────────── HYBRID：入场/出场分属不同口径（信号层组合） ───────────────────
    //
    // 设计原则：BB（σ 归一化振荡量，无量纲）与 BBI（价格尺度均线）**不能混成一根线**，
    // 只能在**拐点信号层**做角色分工 —— 一侧负责「早入场 / 早避跌」，另一侧负责「抗扰动 / 滤抖」。

    /** 一条融合规则：入场源 + 出场源（可选第二出场「先到先出」+ 可选跨口径买入窗闸门） */
    private data class Hybrid(
        val label: String,
        val entrySmooth: SsaBbiPivotEngine.Smooth, val entryBase: SsaBbiPivotEngine.BaseSeries,
        val exitSmooth: SsaBbiPivotEngine.Smooth, val exitBase: SsaBbiPivotEngine.BaseSeries,
        val extraExitSmooth: SsaBbiPivotEngine.Smooth? = null,
        val extraExitBase: SsaBbiPivotEngine.BaseSeries? = null,
        /** 买入窗闸门取自哪个口径（用其 bb/bbMa 算 buyWindowSeries），null = 不设闸门 */
        val gateBase: SsaBbiPivotEngine.BaseSeries? = null
    )

    /** 单个融合变体的逐股结果（含持仓/亏损归因诊断量） */
    private data class HybOut(
        val code: String, val label: String,
        val strategy: Double, val excess: Double, val trades: Int, val winRate: Double,
        /** 平均持仓根数（1H） */
        val avgBars: Double,
        /** 亏损笔的平均亏损幅度（%） */
        val lossAvg: Double
    )

    private val BASE_BB = SsaBbiPivotEngine.BaseSeries.BB
    private val BASE_BBI = SsaBbiPivotEngine.BaseSeries.BBI
    private val SM_RAW = SsaBbiPivotEngine.Smooth.RAW
    private val SM_MA6 = SsaBbiPivotEngine.Smooth.MA6
    private val SM_MA12 = SsaBbiPivotEngine.Smooth.MA12
    private val SM_MA16 = SsaBbiPivotEngine.Smooth.MA16

    private val HYBRIDS = listOf(
        // ① 纯单口径（作为诊断基准：直接对比「灵敏 vs 平滑」的持仓时长与亏损幅度）
        Hybrid("纯·BB6底→BB6顶", SM_MA6, BASE_BB, SM_MA6, BASE_BB),
        Hybrid("纯·BB16底→BB16顶", SM_MA16, BASE_BB, SM_MA16, BASE_BB),
        Hybrid("纯·BBI12底→BBI12顶", SM_MA12, BASE_BBI, SM_MA12, BASE_BBI),
        // ② 不对称角色分工：一侧入场、另一侧出场
        Hybrid("BBI12底→BB6顶", SM_MA12, BASE_BBI, SM_MA6, BASE_BB),
        Hybrid("BB16底→BBI12顶", SM_MA16, BASE_BB, SM_MA12, BASE_BBI),
        Hybrid("BB6底→BBI12顶", SM_MA6, BASE_BB, SM_MA12, BASE_BBI),
        Hybrid("BBI12底→(BB6∪BBI12)顶", SM_MA12, BASE_BBI, SM_MA6, BASE_BB, SM_MA12, BASE_BBI),
        // ③ 跨口径买入窗闸门（用另一口径的均线趋势做前提过滤）
        Hybrid("BB6底→BB6顶·BBI窗", SM_MA6, BASE_BB, SM_MA6, BASE_BB, null, null, BASE_BBI),
        Hybrid("BB16底→BB6顶·BBI窗", SM_MA16, BASE_BB, SM_MA6, BASE_BB, null, null, BASE_BBI),
        Hybrid("BBI12底→BBI12顶·BB窗", SM_MA12, BASE_BBI, SM_MA12, BASE_BBI, null, null, BASE_BB),
        Hybrid("BBI12底→BB6顶·BB窗", SM_MA12, BASE_BBI, SM_MA6, BASE_BB, null, null, BASE_BB)
    )

    /** 在给定 bars/window 上评估全部融合变体（两侧 Result 必须是用同一 bars 算出的） */
    private fun hybridTrades(
        bars: List<SsaAlertEngine.Bar>, window: Int,
        resBB: SsaBbiPivotEngine.Result, resBbi: SsaBbiPivotEngine.Result, code: String
    ): List<HybOut> {
        val allOpen = BooleanArray(bars.size) { true }
        val from = (bars.size - window).coerceAtLeast(0)
        val to = bars.size - 1
        fun src(base: SsaBbiPivotEngine.BaseSeries) = if (base == BASE_BB) resBB else resBbi
        fun sigsOf(r: SsaBbiPivotEngine.Result, s: SsaBbiPivotEngine.Smooth, buy: Boolean) =
            r.variants.first { it.variant == s && !it.gated }.signals.filter { it.buy == buy }
        return HYBRIDS.map { h ->
            val ex = h.extraExitSmooth; val exb = h.extraExitBase
            val sells = sigsOf(src(h.exitBase), h.exitSmooth, false) +
                    (if (ex != null && exb != null) sigsOf(src(exb), ex, false) else emptyList())
            val sigs = (sigsOf(src(h.entryBase), h.entrySmooth, true) + sells).sortedBy { it.index }
            val win = h.gateBase?.let { g -> src(g).let { SsaAlertEngine.buyWindowSeries(it.bb, it.bbMa) } } ?: allOpen
            val (tr, perf) = SsaBbiPivotEngine.simulate(bars, sigs, win, from, to, 0.0)
            val losses = tr.filter { it.pnlPct < 0 }
            HybOut(code, h.label, perf.strategyReturnPct, perf.excessPct, perf.trades, perf.winRatePct,
                if (tr.isEmpty()) 0.0 else tr.map { it.barsHeld }.average(),
                if (losses.isEmpty()) 0.0 else losses.map { it.pnlPct }.average())
        }
    }

    /** 同平滑单口径变体（非闸门）的超额%，作为「单亲」参照 */
    private fun parentExcess(r: SsaBbiPivotEngine.Result, s: SsaBbiPivotEngine.Smooth): Double =
        r.variants.first { it.variant == s && !it.gated }.perf.excessPct

    private fun http(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.setRequestProperty("Referer", "https://finance.sina.com.cn/")
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        println("fetch fail: ${e.message} -> $url"); null
    }

    private fun fetchHourly(code: String, datalen: Int): List<SsaAlertEngine.Bar> {
        val sym = (if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) "sh" else "sz") + code
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
                "CN_MarketData.getKLineData?symbol=$sym&scale=60&ma=no&datalen=$datalen"
        // 新浪在批量抓取下偶发失败：最多重试 4 次（含退避），避免样本随机丢失
        var body: String? = null
        for (attempt in 1..4) {
            body = http(url)
            if (body != null && body.trimStart().startsWith("[")) break
            body = null
            Thread.sleep(400L * attempt)
        }
        if (body == null) return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<SsaAlertEngine.Bar>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val day = o.optString("day", "")
            if (day.length < 16) continue
            out.add(SsaAlertEngine.Bar(
                day.substring(0, 10), day.substring(11, 16),
                o.optDouble("open", 0.0), o.optDouble("high", 0.0),
                o.optDouble("low", 0.0), o.optDouble("close", 0.0),
                o.optLong("volume", 0L)))
        }
        return out.filter { it.close > 0 && it.high > 0 && it.low > 0 }.sortedBy { it.date + it.time }
    }

    private fun csv(sb: StringBuilder, cells: List<String>) {
        sb.append(cells.joinToString(",") { c -> if (c.contains(',')) "\"$c\"" else c }); sb.append('\n')
    }

    @Test
    fun runBbiPivotCompare() {
        val codes = (System.getenv("SSA_PIVOT_CODES")
            ?: "600176,600519,000001,300750,601318,600036,000858,601899,002594,600030,000333,601012").split(",")
        val rows = ArrayList<Row>()
        val sigCsv = StringBuilder()
        csv(sigCsv, listOf("code", "variant", "date", "time", "price", "side", "cond", "reason"))
        val trCsv = StringBuilder()
        csv(trCsv, listOf("code", "variant", "openDate", "openTime", "openPrice",
            "closeDate", "closeTime", "closePrice", "barsHeld", "pnlPct", "exit", "entryCond"))
        // HYBRID 融合结果（主窗口 + holdout）与「是否打败两个单亲」计数
        val hybMainRows = ArrayList<HybOut>()
        val hybHoldRows = ArrayList<HybOut>()
        val hybBeat = HashMap<String, Int>()

        fun dumpTrades(code: String, variant: String, ts: List<SsaBbiPivotEngine.Trade>) {
            ts.forEach { t ->
                val ol = t.openLabel.split(" "); val cl = t.closeLabel.split(" ")
                csv(trCsv, listOf(code, variant,
                    ol.getOrElse(0) { "" }, ol.getOrElse(1) { "" }, "%.2f".format(t.openPrice),
                    cl.getOrElse(0) { "" }, cl.getOrElse(1) { "" }, "%.2f".format(t.closePrice),
                    t.barsHeld.toString(), "%.2f".format(t.pnlPct), t.exit, t.entryCond))
            }
        }

        println("=== BB 布林极限拐点策略 vs 现有多指标策略 · 1H 回测对比 ===")
        var okCodes = 0
        var skipCodes = 0
        for (code in codes) {
            val all = fetchHourly(code, 1023)
            if (all.size < 300) { println("$code 数据不足(${all.size} 根)，跳过"); skipCodes++; continue }
            okCodes++
            val calc = all.takeLast(500)
            val res = SsaBbiPivotEngine.run(calc, window = 480)                        // 新：BB 布林极限
            val resBbi = SsaBbiPivotEngine.run(calc, window = 480,
                base = SsaBbiPivotEngine.BaseSeries.BBI)                               // 旧：BBI 多空指标

            // 口径验收（用户观测）：600176 的 MA(BB,6) 谷底须落 2026-08-25 11:30 → 8/25 14:00 确认买入
            fun ma6ValleyAt(r: SsaBbiPivotEngine.Result): Boolean = r.variants
                .first { it.variant == SsaBbiPivotEngine.Smooth.MA6 && !it.gated }
                .signals.filter { it.buy }.map { calc[it.index - 1] }
                .any { it.date == "2026-08-25" && it.time == "11:30" }
            if (code == "600176") {
                val hitNew = ma6ValleyAt(res); val hitOld = ma6ValleyAt(resBbi)
                assertTrue("600176 新BB MA(BB,6) 谷底须落 2026-08-25 11:30（用户验收口径）", hitNew)
                println("600176 口径对照：新BB·MA(BB,6) 命中 8/25 11:30 = $hitNew；" +
                        "旧BBI·MA(BB,6) 命中 = $hitOld（预期 false → 量化口径纠错价值）")
            }

            rows.add(Row(code, "BASE(现有多指标)", res.baseline.strategyReturnPct,
                res.baseline.buyHoldReturnPct, res.baseline.excessPct, res.baseline.trades, res.baseline.winRatePct))
            // 基线逐笔明细：信号取自 analyze，区间与各变体一致
            val baseRes = SsaAlertEngine.analyze(calc)
            dumpTrades(code, "BASE(现有多指标)",
                SsaBbiPivotEngine.simulate(calc, baseRes.signals, baseRes.buyWindowOpen,
                    res.windowFrom, calc.size - 1, 0.0).first)
            // 新 BB 口径：全网格 32 条（CSV 完整性 + 既有校验依赖 res.variants）
            res.variants.forEach { v ->
                rows.add(Row(code, "新BB·${v.label}", v.perf.strategyReturnPct,
                    v.perf.buyHoldReturnPct, v.perf.excessPct, v.perf.trades, v.perf.winRatePct))
                v.signals.forEach { s ->
                    csv(sigCsv, listOf(code, "新BB·${v.label}", s.date, s.time, "%.2f".format(s.price),
                        if (s.buy) "BUY" else "SELL", s.cond, s.reason))
                }
                dumpTrades(code, "新BB·${v.label}", v.trades)
            }
            // 旧 BBI 口径：仅代表性平滑（原线/MA6/MA12/MA16 × ±买入窗 = 8 条），避免并排表噪声翻倍
            REP_SMOOTHS.forEach { sm ->
                resBbi.variants.filter { it.variant == sm }.forEach { v ->
                    rows.add(Row(code, "旧BBI·${bbiRepLabel(sm, v.gated)}", v.perf.strategyReturnPct,
                        v.perf.buyHoldReturnPct, v.perf.excessPct, v.perf.trades, v.perf.winRatePct))
                }
            }

            // ── HYBRID：入场/出场分属不同口径（信号层组合，不混数值）──
            val hybMain = hybridTrades(calc, 480, res, resBbi, code)
            hybMain.forEach { h ->
                hybMainRows.add(h)
                rows.add(Row(code, "HYB " + h.label, h.strategy, res.baseline.buyHoldReturnPct,
                    h.excess, h.trades, h.winRate))
            }
            // 逐股「打败两个单亲（同平滑 BB / BBI 非闸门变体的较优者）」计数
            HYBRIDS.forEach { h ->
                val hm = hybMain.first { it.label == h.label }
                val p = maxOf(parentExcess(res, h.entrySmooth), parentExcess(resBbi, h.entrySmooth))
                hybBeat[h.label] = hybBeat.getOrDefault(h.label, 0) + if (hm.excess > p) 1 else 0
            }
            // holdout：同一融合逻辑在更早的 250 根上复核（防同窗口多重比较过拟合）
            val hold = calc.subList(0, 250)
            hybHoldRows.addAll(hybridTrades(hold, 250,
                SsaBbiPivotEngine.run(hold, window = 250),
                SsaBbiPivotEngine.run(hold, window = 250, base = BASE_BBI), code))

            // ── runMix（App 引擎 API）与 HYBRID 网格逐股等价（delta = 0.0）──
            val mixModes = listOf(
                SsaBbiPivotEngine.MixMode.PURE_BBI12,
                SsaBbiPivotEngine.MixMode.BBI12_BB6_OR,
                SsaBbiPivotEngine.MixMode.BBI12_BB6_BBWIN
            )
            val mix = SsaBbiPivotEngine.runMix(calc, window = 480, modes = mixModes)
            // ① 拐点序列 ≡ 单侧 run(base=...) 的 MA6/MA12 非闸门信号（index:buy 全等）
            assertEquals("runMix bbPivots ≡ 新BB·MA(BB,6)",
                res.variants.first { it.variant == SM_MA6 && !it.gated }.signals.map { "${it.index}:${it.buy}" },
                mix.bbPivots.map { "${it.index}:${it.buy}" })
            assertEquals("runMix bbiPivots ≡ 旧BBI·MA(BB,12)",
                resBbi.variants.first { it.variant == SM_MA12 && !it.gated }.signals.map { "${it.index}:${it.buy}" },
                mix.bbiPivots.map { "${it.index}:${it.buy}" })
            // ② 三条融合模式 ≡ hybridTrades 对应标签（策略%/超额%/笔数/胜率 全部容差 0）
            val refByName = mapOf(
                SsaBbiPivotEngine.MixMode.PURE_BBI12 to "纯·BBI12底→BBI12顶",
                SsaBbiPivotEngine.MixMode.BBI12_BB6_OR to "BBI12底→(BB6∪BBI12)顶",
                SsaBbiPivotEngine.MixMode.BBI12_BB6_BBWIN to "BBI12底→BB6顶·BB窗"
            )
            mix.results.forEach { m ->
                val ref = hybMain.first { it.label == refByName[m.mode] }
                assertEquals("runMix ${m.mode.label} 策略% 等价", ref.strategy, m.perf.strategyReturnPct, 0.0)
                assertEquals("runMix ${m.mode.label} 超额% 等价", ref.excess, m.perf.excessPct, 0.0)
                assertEquals("runMix ${m.mode.label} 笔数 等价", ref.trades, m.perf.trades)
                assertEquals("runMix ${m.mode.label} 胜率 等价", ref.winRate, m.perf.winRatePct, 0.0)
            }
            // ③ 融合基线 ≡ run().baseline（同一区间、同一 analyze 信号）
            assertEquals("runMix 基线超额 ≡ run().baseline 超额", res.baseline.excessPct, mix.baseline.excessPct, 0.0)
            // ④ 确定性：二次调用逐字节一致
            val mix2 = SsaBbiPivotEngine.runMix(calc, window = 480, modes = mixModes)
            mix.results.forEachIndexed { k, m ->
                assertEquals("runMix 确定性 ${m.mode.label}",
                    m.perf.strategyReturnPct, mix2.results[k].perf.strategyReturnPct, 0.0)
            }

            // 调参网格：拐点 + 多均线趋势过滤 + WR 闸门 + 硬止损
            val tuned = SsaBbiPivotEngine.runTuned(calc, window = 480)
            tuned.forEach { t ->
                rows.add(Row(code, "TUNE " + t.label, t.perf.strategyReturnPct,
                    t.perf.buyHoldReturnPct, t.perf.excessPct, t.perf.trades, t.perf.winRatePct))
                t.signals.forEach { s ->
                    csv(sigCsv, listOf(code, "TUNE " + t.label, s.date, s.time, "%.2f".format(s.price),
                        if (s.buy) "BUY" else "SELL", s.cond, s.reason))
                }
                dumpTrades(code, "TUNE " + t.label, t.trades)
            }

            // 多均线金叉/死叉网格（含用户点名的 MA3×MA12 与 WR/止损变体）
            val cross = SsaBbiPivotEngine.runCross(calc, window = 480)
            cross.forEach { c ->
                rows.add(Row(code, "CROSS " + c.label, c.perf.strategyReturnPct,
                    c.perf.buyHoldReturnPct, c.perf.excessPct, c.perf.trades, c.perf.winRatePct))
                c.signals.forEach { s ->
                    csv(sigCsv, listOf(code, "CROSS " + c.label, s.date, s.time, "%.2f".format(s.price),
                        if (s.buy) "BUY" else "SELL", s.cond, s.reason))
                }
                dumpTrades(code, "CROSS " + c.label, c.trades)
            }

            // WR(14) 策略：平滑 vs 不平滑 + BBI 多组合
            val wrRes = SsaBbiPivotEngine.runWr(calc, window = 480)
            wrRes.forEach { w ->
                rows.add(Row(code, "WR " + w.label, w.perf.strategyReturnPct,
                    w.perf.buyHoldReturnPct, w.perf.excessPct, w.perf.trades, w.perf.winRatePct))
                w.signals.forEach { s ->
                    csv(sigCsv, listOf(code, "WR " + w.label, s.date, s.time, "%.2f".format(s.price),
                        if (s.buy) "BUY" else "SELL", s.cond, s.reason))
                }
                dumpTrades(code, "WR " + w.label, w.trades)
            }

            // ④ 调参网格：标签唯一、信号下标不越界、确定性
            val cut = 170
            assertEquals("调参网格条数", 10, tuned.size)
            assertEquals("调参标签唯一", tuned.size, tuned.map { it.label }.toSet().size)
            tuned.forEach { t -> t.signals.forEach { s -> assertTrue("调参信号越界", s.index in 1 until calc.size) } }
            val tuned2 = SsaBbiPivotEngine.runTuned(calc, window = 480)
            tuned.forEachIndexed { k, t ->
                assertEquals("调参确定性 ${t.label}", t.perf.strategyReturnPct, tuned2[k].perf.strategyReturnPct, 0.0)
            }
            // ⑤ 金叉网格：条数 / 标签唯一 / 越界 / 确定性
            assertEquals("金叉网格条数", 10, cross.size)
            assertEquals("金叉标签唯一", cross.size, cross.map { it.label }.toSet().size)
            cross.forEach { c -> c.signals.forEach { s -> assertTrue("金叉信号越界", s.index in 1 until calc.size) } }
            val cross2 = SsaBbiPivotEngine.runCross(calc, window = 480)
            cross.forEachIndexed { k, c ->
                assertEquals("金叉确定性 ${c.label}", c.perf.strategyReturnPct, cross2[k].perf.strategyReturnPct, 0.0)
            }
            // ⑥ 金叉无未来函数：前缀重算
            val preCross = SsaBbiPivotEngine.runCross(calc.subList(0, cut), window = 480)
            cross.forEachIndexed { k, c ->
                val trunc = c.signals.filter { it.index <= cut - 1 }
                assertEquals("金叉无未来函数 ${c.label}",
                    trunc.map { "${it.index}:${it.cond}" }, preCross[k].signals.map { "${it.index}:${it.cond}" })
            }

            // ⑦ WR 网格：条数 / 标签唯一 / 越界 / 确定性 / 无未来函数
            assertEquals("WR 网格条数", 14, wrRes.size)
            assertEquals("WR 标签唯一", wrRes.size, wrRes.map { it.label }.toSet().size)
            wrRes.forEach { w -> w.signals.forEach { s -> assertTrue("WR 信号越界", s.index in 1 until calc.size) } }
            val wrRes2 = SsaBbiPivotEngine.runWr(calc, window = 480)
            wrRes.forEachIndexed { k, w ->
                assertEquals("WR 确定性 ${w.label}", w.perf.strategyReturnPct, wrRes2[k].perf.strategyReturnPct, 0.0)
            }
            val preWr = SsaBbiPivotEngine.runWr(calc.subList(0, cut), window = 480)
            wrRes.forEachIndexed { k, w ->
                val trunc = w.signals.filter { it.index <= cut - 1 }
                assertEquals("WR 无未来函数 ${w.label}",
                    trunc.map { "${it.index}:${it.cond}" }, preWr[k].signals.map { "${it.index}:${it.cond}" })
            }

            // ① 拐点信号与序列长度一致；信号根 = 确认根，无越界
            res.variants.forEach { v ->
                assertEquals("series 长度", calc.size, v.series.size)
                v.signals.forEach { s -> assertTrue("信号下标越界", s.index in 1 until calc.size) }
            }
            // ② 确定性
            val res2 = SsaBbiPivotEngine.run(calc, window = 480)
            assertEquals("变体数确定性", res.variants.size, res2.variants.size)
            res.variants.forEachIndexed { k, v ->
                assertEquals("信号数确定性", v.signals.size, res2.variants[k].signals.size)
                assertEquals("收益率确定性", v.perf.strategyReturnPct, res2.variants[k].perf.strategyReturnPct, 0.0)
            }
            // ③ 无未来函数：前缀重算的拐点序列 = 全量截断
            val pre = SsaBbiPivotEngine.run(calc.subList(0, cut))
            res.variants.forEachIndexed { k, v ->
                val trunc = v.signals.filter { it.index <= cut - 1 }
                assertEquals("拐点无未来函数 ${v.variant.label}",
                    trunc.map { "${it.index}:${it.cond}" }, pre.variants[k].signals.map { "${it.index}:${it.cond}" })
            }
        }

        if (rows.isEmpty()) { println("无可用样本，跳过"); return }

        File("build").mkdirs()
        val sb = StringBuilder()
        csv(sb, listOf("code", "variant", "strategyPct", "buyHoldPct", "excessPct", "trades", "winRatePct"))
        rows.forEach { csv(sb, listOf(it.code, it.variant, "%.2f".format(it.strategy),
            "%.2f".format(it.buyHold), "%.2f".format(it.excess), it.trades.toString(), "%.1f".format(it.winRate))) }
        File("build/ssa_bbi_pivot_compare.csv").writeText(sb.toString())
        File("build/ssa_bbi_pivot_signals.csv").writeText(sigCsv.toString())
        File("build/ssa_bbi_pivot_trades.csv").writeText(trCsv.toString())

        // ── 汇总 ────────────────────────────────────────────────────────
        fun avgOf(key: String): Triple<Double, Double, Double>? {
            val rs = rows.filter { it.variant == key }
            if (rs.isEmpty()) return null
            return Triple(rs.map { it.strategy }.average(), rs.map { it.excess }.average(),
                rs.map { it.trades.toDouble() }.average())
        }
        val samp = rows.groupBy { it.code }.size
        val buyHoldAvg = rows.groupBy { it.code }.values.map { it.first().buyHold }.average()

        println()
        println("══ 新BB（布林极限） vs 旧BBI（多空指标）· 同 $samp 只样本 / 同末480根1H窗口（区间买持 %+.2f%%）══".format(buyHoldAvg))
        println("%-14s | %10s | %10s | %11s | %11s".format("口径(±买入窗)", "新BB策略%", "旧BBI策略%", "新BB超额%", "旧BBI超额%"))
        REP_SMOOTHS.forEach { sm ->
            listOf(false, true).forEach { g ->
                val nb = avgOf("新BB·" + (sm.label + if (g) "+BB窗" else ""))
                val ob = avgOf("旧BBI·" + bbiRepLabel(sm, g))
                println("%-14s | %10s | %10s | %11s | %11s".format(
                    repName(sm) + if (g) "+窗" else "",
                    nb?.let { "%.2f".format(it.first) } ?: "—",
                    ob?.let { "%.2f".format(it.first) } ?: "—",
                    nb?.let { "%+.2f".format(it.second) } ?: "—",
                    ob?.let { "%+.2f".format(it.second) } ?: "—"))
            }
        }
        avgOf("BASE(现有多指标)")?.let { b ->
            println("%-14s | %10s | %10s | %11s | %11s".format(
                "BASE 多指标", "%.2f".format(b.first), "—", "%+.2f".format(b.second), "—"))
        }

        // ── HYBRID 融合汇总 ──
        println()
        println("── HYBRID 融合（入场/出场分属不同口径，信号层组合；holdout = 更早 250 根）──")
        println("%-24s %9s %9s %7s %8s %7s %8s %7s".format(
            "融合变体", "主窗超额%", "holdout%", "均笔数", "均胜率%", "均持仓", "均亏幅%", "胜单亲"))
        HYBRIDS.forEach { h ->
            val m = hybMainRows.filter { it.label == h.label }
            if (m.isEmpty()) return@forEach
            val hd = hybHoldRows.filter { it.label == h.label }
            println("%-24s %9.2f %9.2f %7.1f %8.1f %7.1f %8.2f %7s".format(h.label,
                m.map { it.excess }.average(),
                if (hd.isEmpty()) 0.0 else hd.map { it.excess }.average(),
                m.map { it.trades.toDouble() }.average(),
                m.map { it.winRate }.average(),
                m.map { it.avgBars }.average(),
                m.map { it.lossAvg }.average(),
                "${hybBeat[h.label] ?: 0}/$samp"))
        }

        // ── 既有网格族（TUNE / CROSS / WR / BASE，均基于新 BB 口径）──
        val byVariant = rows.groupBy { it.variant }
            .filterKeys { it.startsWith("BASE") || it.startsWith("TUNE") || it.startsWith("CROSS") || it.startsWith("WR ") }
        println()
        println("── 新 BB 口径下 TUNE / CROSS / WR 网格（按均超额降序）──")
        println("%-30s %8s %8s %8s %7s %8s".format("变体", "均策略%", "均买持%", "均超额%", "均笔数", "均胜率%"))
        byVariant.toList().sortedByDescending { it.second.map { r -> r.excess }.average() }.forEach { (v, rs) ->
            println("%-30s %8.2f %8.2f %8.2f %7.1f %8.1f".format(v,
                rs.map { it.strategy }.average(), rs.map { it.buyHold }.average(),
                rs.map { it.excess }.average(), rs.map { it.trades.toDouble() }.average(),
                rs.map { it.winRate }.average()))
        }
        println()
        println("样本数 $samp 只（尝试 ${codes.size} / 成功 $okCodes / 跳过 $skipCodes）；明细见 build/ssa_bbi_pivot_compare.csv")
        println("逐笔明细见 build/ssa_bbi_pivot_trades.csv（用于亏损归因）")

        assertTrue("应有至少一个变体产出交易", rows.any { it.trades > 0 })
    }

    // ═══════════ 持仓期风控网格（硬止损 / 移动止盈）═══════════
    //
    // 用户观测：BBI12 入场 → 顶/BB6 出场的**持仓段内**价格上下起伏很大，怀疑这是回撤主要来源。
    // 本网格对同一批 BBI12 融合信号，叠加不同「持仓期保护规则」重新配对成交，输出：
    // 总收益、超额、笔数、胜率、**平均单笔最大浮亏 MAE**、**权益最大回撤 MDD**，用于选型。
    // 口径：全部**收盘确认**（不用最低价，避免插针假破）；成交价 = 收盘价。

    /** 一条持仓期保护规则 */
    private data class HoldRule(val label: String, val stopPct: Double, val trailPct: Double)

    private val HOLD_RULES = listOf(
        HoldRule("无保护(基线)", 0.0, 0.0),
        HoldRule("硬止损3%", 3.0, 0.0),
        HoldRule("硬止损5%", 5.0, 0.0),
        HoldRule("移动止盈3%", 0.0, 3.0),
        HoldRule("移动止盈5%", 0.0, 5.0),
        HoldRule("移动止盈8%", 0.0, 8.0),
        HoldRule("移动止盈5%+硬止损5%", 5.0, 5.0)
    )

    @Test
    fun runMixHoldRiskGrid() {
        val codes = (System.getenv("SSA_PIVOT_CODES")
            ?: "600176,600519,000001,300750,601318,600036,000858,601899,002594,600030,000333,601012").split(",")
        val modes = listOf(
            SsaBbiPivotEngine.MixMode.PURE_BBI12,
            SsaBbiPivotEngine.MixMode.BBI12_BB6_OR
        )
        val p = SsaAlertEngine.AlertParams()
        val sb = StringBuilder()
        csv(sb, listOf("code", "mode", "rule", "strategyPct", "buyHoldPct", "excessPct",
            "trades", "winRatePct", "maePct", "mpdPct", "mddPct"))
        val okRows = ArrayList<Array<Any>>()
        val buyHolds = ArrayList<Double>()

        println("=== BBI12 融合策略 · 持仓期风控网格（收盘口径；MAE=均笔最大浮亏 MDD=权益最大回撤）===")
        var okCodes = 0
        for (code in codes) {
            val all = fetchHourly(code, 1023)
            if (all.size < 300) { println("$code 数据不足(${all.size} 根)，跳过"); continue }
            okCodes++
            val calc = all.takeLast(500)
            // 同一批信号（runMix 内部按 mode 生成），逐条规则重新配对成交
            val base = SsaBbiPivotEngine.runMix(calc, window = 480, modes = modes, p = p)
            val allOpen = BooleanArray(calc.size) { true }
            val from = base.windowFrom; val to = calc.size - 1
            buyHolds.add(calc[from].close.let { c -> (calc[to].close / c - 1.0) * 100.0 })
            base.results.forEach { r ->
                HOLD_RULES.forEach { rule ->
                    val (_, perf) = SsaBbiPivotEngine.simulate(
                        calc, r.signals, allOpen, from, to, rule.stopPct, p, rule.trailPct)
                    csv(sb, listOf(code, r.mode.label, rule.label,
                        "%.2f".format(perf.strategyReturnPct), "%.2f".format(perf.buyHoldReturnPct),
                        "%.2f".format(perf.excessPct), perf.trades.toString(),
                        "%.1f".format(perf.winRatePct), "%.2f".format(perf.maePct),
                        "%.2f".format(perf.mpdPct), "%.2f".format(perf.mddPct)))
                    okRows.add(arrayOf(code, r.mode.label, rule.label, perf.strategyReturnPct,
                        perf.excessPct, perf.trades, perf.winRatePct, perf.maePct, perf.mpdPct, perf.mddPct))
                }
            }
        }
        if (okRows.isEmpty()) { println("无可用样本，跳过"); return }

        File("build").mkdirs()
        File("build/ssa_mix_risk_grid.csv").writeText(sb.toString())

        fun stat(mode: String, rule: String, idx: Int): List<Double> =
            okRows.filter { it[1] == mode && it[2] == rule }.map { (it[idx] as Number).toDouble() }

        fun med(xs: List<Double>): Double {
            if (xs.isEmpty()) return 0.0
            val s = xs.sorted(); val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
        }

        val sum = StringBuilder()
        csv(sum, listOf("mode", "rule", "medStrategyPct", "avgStrategyPct", "medExcessPct",
            "avgTrades", "avgWinRatePct", "avgMaePct", "avgMpdPct", "medMddPct", "avgMddPct"))
        val buyHoldMed = med(buyHolds)
        println("样本 $okCodes 只（同末480根1H窗口）；中位买持 %+.2f%%".format(buyHoldMed))
        println("（MAE=均笔最大浮亏[相对建仓价]，MPD=均笔最大回吐[相对持仓期最高收盘]，MDD=权益最大回撤）")
        modes.forEach { m ->
            println()
            println("── ${m.label} ──")
            println("%-22s %9s %9s %9s %7s %8s %8s %8s %9s %9s".format(
                "保护规则", "中位策略%", "均策略%", "中位超额%", "均笔数", "均胜率%", "均MAE%", "均MPD%", "中位MDD%", "均MDD%"))
            HOLD_RULES.forEach { rule ->
                val st = stat(m.label, rule.label, 3); val ex = stat(m.label, rule.label, 4)
                val tr = stat(m.label, rule.label, 5); val wr = stat(m.label, rule.label, 6)
                val mae = stat(m.label, rule.label, 7); val mpd = stat(m.label, rule.label, 8)
                val mdd = stat(m.label, rule.label, 9)
                println("%-22s %9.2f %9.2f %9.2f %7.1f %8.1f %8.2f %8.2f %9.2f %9.2f".format(
                    rule.label, med(st), st.average(), med(ex), tr.average(), wr.average(),
                    mae.average(), mpd.average(), med(mdd), mdd.average()))
                csv(sum, listOf(m.label, rule.label, "%.2f".format(med(st)), "%.2f".format(st.average()),
                    "%.2f".format(med(ex)), "%.1f".format(tr.average()), "%.1f".format(wr.average()),
                    "%.2f".format(mae.average()), "%.2f".format(mpd.average()),
                    "%.2f".format(med(mdd)), "%.2f".format(mdd.average())))
            }
        }
        File("build/ssa_mix_risk_grid_summary.csv").writeText(sum.toString())
        println()
        println("明细 build/ssa_mix_risk_grid.csv · 汇总 build/ssa_mix_risk_grid_summary.csv")
    }

    // ═══════════ SSA 1H 周期分量拐点 · 对 BBI12 策略有没有帮助 ═══════════
    //
    // 同一批 BBI12 底买信号，分别换/叠加出场源与入场过滤，比较：
    // 策略收益、超额、笔数、胜率、MAE（相对建仓价最大浮亏）、MPD（相对持仓峰最大回吐）、MDD（权益最大回撤）。
    // 周期分量 = SsaEngine.rollingTrendCycle(L=30)（与三层 SSA 同源，无未来函数）；
    // 周期拐点口径 = cyclePivots（谷底/峰顶，信号根 = 确认根 = i+1，成交价 = 收盘价）。

    @Test
    fun runMixCycleGrid() {
        val codes = (System.getenv("SSA_PIVOT_CODES")
            ?: "600176,600519,000001,300750,601318,600036,000858,601899,002594,600030,000333,601012").split(",")
        val p = SsaAlertEngine.AlertParams()
        val sb = StringBuilder()
        csv(sb, listOf("code", "variant", "strategyPct", "buyHoldPct", "excessPct",
            "trades", "winRatePct", "maePct", "mpdPct", "mddPct"))
        val rows = ArrayList<Array<Any>>()

        println("=== SSA 1H 周期分量拐点 · 叠加到 BBI12 策略的效果 grid ===")
        var okCodes = 0
        for (code in codes) {
            val all = fetchHourly(code, 1023)
            if (all.size < 300) { println("$code 数据不足(${all.size} 根)，跳过"); continue }
            okCodes++
            val calc = all.takeLast(500)
            val b = SsaBbiPivotEngine.runMix(calc, window = 480,
                modes = listOf(SsaBbiPivotEngine.MixMode.PURE_BBI12), p = p)
            val bbiBuys = b.bbiPivots.filter { it.buy }
            val bbiSells = b.bbiPivots.filter { !it.buy }
            val bbSells = b.bbPivots.filter { !it.buy }
            val cycBuys = b.cyclePivots.filter { it.buy }
            val cycSells = b.cyclePivots.filter { !it.buy }
            val allOpen = BooleanArray(calc.size) { true }
            val from = b.windowFrom; val to = calc.size - 1

            // 周期分量暖机（前 cycleL=30 根为 NaN），入场过滤时要求 cycle 有效
            fun cycOk(i: Int) = i in b.cycle.indices && !b.cycle[i].isNaN()

            val variants = listOf(
                "① 纯BBI12（基线）" to (bbiBuys + bbiSells),
                "② BBI12底→(BB6∪BBI12)顶" to (bbiBuys + bbiSells + bbSells),
                "③ ②+周期峰顶（三源先到先出）" to (bbiBuys + bbiSells + bbSells + cycSells),
                "④ BBI12底→周期峰顶（周期单独出场）" to (bbiBuys + cycSells),
                "⑤ ①+周期峰顶" to (bbiBuys + bbiSells + cycSells),
                "⑥ ②+入场须周期谷底共振" to (bbiBuys.filter { buy ->
                    cycBuys.any { kotlin.math.abs(it.index - buy.index) <= 1 }
                } + bbiSells + bbSells),
                "⑦ ②+入场须 cycle>0" to (bbiBuys.filter { cycOk(it.index) && b.cycle[it.index] > 0.0 }
                        + bbiSells + bbSells)
            )
            variants.forEach { (label, sigs) ->
                val (_, perf) = SsaBbiPivotEngine.simulate(
                    calc, sigs.sortedBy { it.index }, allOpen, from, to, 0.0, p)
                csv(sb, listOf(code, label, "%.2f".format(perf.strategyReturnPct),
                    "%.2f".format(perf.buyHoldReturnPct), "%.2f".format(perf.excessPct),
                    perf.trades.toString(), "%.1f".format(perf.winRatePct),
                    "%.2f".format(perf.maePct), "%.2f".format(perf.mpdPct), "%.2f".format(perf.mddPct)))
                rows.add(arrayOf(code, label, perf.strategyReturnPct, perf.excessPct, perf.trades,
                    perf.winRatePct, perf.maePct, perf.mpdPct, perf.mddPct))
            }
        }
        if (rows.isEmpty()) { println("无可用样本，跳过"); return }

        File("build").mkdirs()
        File("build/ssa_mix_cycle_grid.csv").writeText(sb.toString())

        fun med(xs: List<Double>): Double {
            if (xs.isEmpty()) return 0.0
            val s = xs.sorted(); val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
        }
        fun col(label: String, idx: Int): List<Double> =
            rows.filter { it[1] == label }.map { (it[idx] as Number).toDouble() }

        val labels = rows.map { it[1] as String }.distinct()
        val sum = StringBuilder()
        csv(sum, listOf("variant", "medStrategyPct", "avgStrategyPct", "medExcessPct", "avgExcessPct",
            "avgTrades", "avgWinRatePct", "avgMaePct", "avgMpdPct", "medMddPct", "avgMddPct"))
        println("样本 $okCodes 只（同末480根1H窗口）")
        println("%-30s %9s %9s %9s %8s %7s %8s %8s %9s %9s".format(
            "变体", "中位策略%", "均策略%", "中位超额%", "均胜率%", "均笔数", "均MAE%", "均MPD%", "中位MDD%", "均MDD%"))
        labels.forEach { label ->
            val st = col(label, 2); val ex = col(label, 3); val tr = col(label, 4)
            val wr = col(label, 5); val mae = col(label, 6); val mpd = col(label, 7); val mdd = col(label, 8)
            println("%-30s %9.2f %9.2f %9.2f %8.1f %7.1f %8.2f %8.2f %9.2f %9.2f".format(
                label, med(st), st.average(), med(ex), wr.average(), tr.average(),
                mae.average(), mpd.average(), med(mdd), mdd.average()))
            csv(sum, listOf(label, "%.2f".format(med(st)), "%.2f".format(st.average()),
                "%.2f".format(med(ex)), "%.2f".format(ex.average()), "%.1f".format(tr.average()),
                "%.1f".format(wr.average()), "%.2f".format(mae.average()),
                "%.2f".format(mpd.average()), "%.2f".format(med(mdd)), "%.2f".format(mdd.average())))
        }
        File("build/ssa_mix_cycle_grid_summary.csv").writeText(sum.toString())
        println()
        println("明细 build/ssa_mix_cycle_grid.csv · 汇总 build/ssa_mix_cycle_grid_summary.csv")
    }

    // ═══════════ 单票复测：600176 · BBI12 + WR 威廉指标 ═══════════
    //
    // 口径（与引擎一致，无未来函数）：
    // · BBI(MA12) 拐点：i 根拐点须 i+1 根收盘确认（信号根=确认根），触发价=收盘价；
    //   谷底确认→买入 P1，峰顶确认→卖出 P2。
    // · WR(14) 国内口径 0~100，数值越大越超卖（价格越贴近区间低点）。
    //   「WR 下穿65」= 从超卖区回升（引擎 crossBuy 口径）；「WR 上穿25」= 从超买区回落。
    // · 闸门：买入要求确认根 WR ≥ 阈值（谷底确实处在超卖区）；卖出要求 WR ≤ 阈值（峰顶处在超买区）。
    // · 全部收盘价成交、单边做多最多一仓、末根强平。

    private data class VRow(
        val label: String, val strategy: Double, val buyHold: Double, val excess: Double,
        val trades: Int, val winRate: Double, val mae: Double, val mpd: Double, val mdd: Double
    )

    @Test
    fun report600176BbiWr() {
        val code = "600176"
        val all = fetchHourly(code, 1023)
        println("[$code] 抓取 1H K 线 ${all.size} 根")
        if (all.size < 300) { println("数据不足，退出"); return }
        val calc = all.takeLast(500)
        val n = calc.size
        val p = SsaAlertEngine.AlertParams()
        val window = 480
        val from = (n - window).coerceAtLeast(0)
        val to = n - 1
        val allOpen = BooleanArray(n) { true }

        val b = SsaBbiPivotEngine.runMix(calc, window = window,
            modes = listOf(SsaBbiPivotEngine.MixMode.PURE_BBI12), p = p)
        val bbiBuys = b.bbiPivots.filter { it.buy }
        val bbiSells = b.bbiPivots.filter { !it.buy }

        val wr = SsaBbiPivotEngine.wrOf(calc, p.wrN)
        val wrS = SsaAlertEngine.sma(wr, 3)
        fun wrA(i: Int) = if (i in wr.indices) wr[i] else Double.NaN
        fun wrSA(i: Int) = if (i in wrS.indices) wrS[i] else Double.NaN
        fun ok(v: Double) = !v.isNaN()

        /** WR 下穿 65（自超卖区回升）的确认根 */
        val wrUp65 = HashSet<Int>()
        /** WR 上穿 25（自超买区回落）的确认根 */
        val wrDn25 = HashSet<Int>()
        for (i in 1 until n) {
            val a = wrSA(i - 1); val c = wrSA(i)
            if (ok(a) && ok(c)) {
                if (a > 65.0 && c <= 65.0) wrUp65.add(i)
                if (a < 25.0 && c >= 25.0) wrDn25.add(i)
            }
        }

        fun buyGate(thr: Double) = bbiBuys.filter { ok(wrA(it.index)) && wrA(it.index) >= thr }
        fun sellGate(thr: Double) = bbiSells.filter { ok(wrA(it.index)) && wrA(it.index) <= thr }
        fun wrSig(set: Set<Int>, buy: Boolean, cond: String, why: String) = set.map { i ->
            SsaAlertEngine.Signal(i, calc[i].date, calc[i].time, calc[i].close, buy, cond, why, false)
        }
        /** 谷底确认根 i 是否「±tol 根内」出现 WR 下穿 65（超卖回升） */
        fun near65(i: Int, tol: Int = 1) = ((i - tol)..(i + tol)).any { wrUp65.contains(it) }

        val wrBuys = wrSig(wrUp65, true, "W1", "WR下穿65·超卖回升")
        val wrSells = wrSig(wrDn25, false, "W2", "WR上穿25·超买回落")

        val variants = listOf(
            "① 纯 BBI12（基线）" to (bbiBuys + bbiSells),
            "② BBI12入场 ∩ WR下穿65共振(±1根)" to (bbiBuys.filter { near65(it.index) } + bbiSells),
            "③ BBI12入场 + WR提前出场(WR上穿25)" to (bbiBuys + bbiSells + wrSells),
            "④ BBI12入场 → 改为只用WR出场" to (bbiBuys + wrSells),
            "⑤ BBI12入场 + WR买闸门(WR≥40)" to (buyGate(40.0) + bbiSells),
            "⑥ BBI12入场 + WR卖闸门(WR≤50)" to (bbiBuys + sellGate(50.0)),
            "⑦ 纯 WR（下穿65买 / 上穿25卖）" to (wrBuys + wrSells),
            "⑧ BBI12底 → (BB6∪BBI12)顶（对照）" to (bbiBuys + bbiSells + b.bbPivots.filter { !it.buy })
        )

        File("build").mkdirs()
        val rows = ArrayList<VRow>()
        val tradeCsv = StringBuilder()
        csv(tradeCsv, listOf("variant", "openDate", "openTime", "openPrice", "closeDate", "closeTime",
            "closePrice", "barsHeld", "pnlPct", "exit", "entryCond"))
        println()
        println("%-40s %9s %9s %9s %7s %8s %8s %8s %9s".format(
            "变体", "策略%", "买持%", "超额%", "笔数", "胜率%", "MAE%", "MPD%", "MDD%"))
        variants.forEach { (label, raw) ->
            val (tr, perf) = SsaBbiPivotEngine.simulate(calc, raw.sortedBy { it.index }, allOpen, from, to, 0.0, p)
            rows.add(VRow(label, perf.strategyReturnPct, perf.buyHoldReturnPct, perf.excessPct,
                perf.trades, perf.winRatePct, perf.maePct, perf.mpdPct, perf.mddPct))
            println("%-40s %9.2f %9.2f %9.2f %7d %8.1f %8.2f %8.2f %9.2f".format(
                label, perf.strategyReturnPct, perf.buyHoldReturnPct, perf.excessPct,
                perf.trades, perf.winRatePct, perf.maePct, perf.mpdPct, perf.mddPct))
            tr.forEach { t ->
                csv(tradeCsv, listOf(label, calc[t.openIndex].date, calc[t.openIndex].time,
                    "%.3f".format(t.openPrice), calc[t.closeIndex].date, calc[t.closeIndex].time,
                    "%.3f".format(t.closePrice), t.barsHeld.toString(), "%.2f".format(t.pnlPct),
                    t.exit, t.entryCond))
            }
        }

        val sum = StringBuilder()
        csv(sum, listOf("variant", "strategyPct", "buyHoldPct", "excessPct", "trades",
            "winRatePct", "maePct", "mpdPct", "mddPct"))
        rows.forEach { r ->
            csv(sum, listOf(r.label, "%.2f".format(r.strategy), "%.2f".format(r.buyHold),
                "%.2f".format(r.excess), r.trades.toString(), "%.1f".format(r.winRate),
                "%.2f".format(r.mae), "%.2f".format(r.mpd), "%.2f".format(r.mdd)))
        }
        File("build/ssa_600176_bbi_wr.csv").writeText(sum.toString())
        File("build/ssa_600176_bbi_wr_trades.csv").writeText(tradeCsv.toString())

        val kl = StringBuilder()
        csv(kl, listOf("date", "time", "open", "high", "low", "close", "wr", "bbiMa12"))
        for (i in from until n) {
            csv(kl, listOf(calc[i].date, calc[i].time, "%.3f".format(calc[i].open), "%.3f".format(calc[i].high),
                "%.3f".format(calc[i].low), "%.3f".format(calc[i].close),
                if (ok(wrA(i))) "%.2f".format(wrA(i)) else "",
                if (i in b.bbiMa12.indices && ok(b.bbiMa12[i])) "%.4f".format(b.bbiMa12[i]) else ""))
        }
        File("build/ssa_600176_kline_wr.csv").writeText(kl.toString())
        println()
        println("区间 ${calc[from].date} ${calc[from].time} → ${calc[to].date} ${calc[to].time}（${n - from} 根 1H）")
        println("汇总 build/ssa_600176_bbi_wr.csv · 明细 build/ssa_600176_bbi_wr_trades.csv · " +
            "K线+WR build/ssa_600176_kline_wr.csv")
    }

    // ═══════════ 600176 · WR 平滑 × 多周期 网格 ═══════════
    //
    // 问题：上一轮「BBI12 入场 + WR(14) 出场」虽然把 MDD 从 19.68% 压到 5.11%，
    // 但把 6/10 那笔主升浪砍成 +6.76%（过早被 MA(WR,3) 上穿 25 触发）。
    // 本轮检验：给 WR 做平滑（原线 / MA3 / MA5）并换用不同 WR 周期（6/9/14/21/28），
    // 看能否「既保留主升浪、又压住回撤」。
    //
    // 统一口径：BBI(MA12) 谷底→买；出场源可换为 WR；WR 国内口径 0~100 越大越超卖，
    // 「上穿 25」= 自超买区回落→卖。仍为收盘价成交、无未来函数。

    @Test
    fun run600176WrSmoothPeriod() {
        val code = "600176"
        val all = fetchHourly(code, 1023)
        println("[$code] 抓取 1H K 线 ${all.size} 根")
        if (all.size < 300) { println("数据不足，退出"); return }
        val calc = all.takeLast(500)
        val n = calc.size
        val p = SsaAlertEngine.AlertParams()
        val window = 480
        val from = (n - window).coerceAtLeast(0)
        val to = n - 1
        val allOpen = BooleanArray(n) { true }

        val b = SsaBbiPivotEngine.runMix(calc, window = window,
            modes = listOf(SsaBbiPivotEngine.MixMode.PURE_BBI12), p = p)
        val bbiBuys = b.bbiPivots.filter { it.buy }
        val bbiSells = b.bbiPivots.filter { !it.buy }

        val periods = listOf(6, 9, 14, 21, 28)
        fun smSmooth(x: DoubleArray): DoubleArray = SsaAlertEngine.sma(x, 3)
        fun smSmooth5(x: DoubleArray): DoubleArray = SsaAlertEngine.sma(x, 5)
        val smooths: List<Pair<String, (DoubleArray) -> DoubleArray>> = listOf(
            "原线" to { x: DoubleArray -> x },
            "MA3" to { x: DoubleArray -> smSmooth(x) },
            "MA5" to { x: DoubleArray -> smSmooth5(x) }
        )
        val cache = HashMap<String, DoubleArray>()
        fun wrSeq(period: Int, sm: String): DoubleArray = cache.getOrPut("$period|$sm") {
            smooths.first { it.first == sm }.second(SsaBbiPivotEngine.wrOf(calc, period))
        }
        /** WR 上穿 lv（自超买区回落）的确认根 → 卖出 */
        fun wrSells(w: DoubleArray, lv: Double): List<Int> {
            val out = ArrayList<Int>()
            for (i in 1 until n) {
                val a = w[i - 1]; val c = w[i]
                if (a.isNaN() || c.isNaN()) continue
                if (a < lv && c >= lv) out.add(i)
            }
            return out
        }

        val variants = ArrayList<Pair<String, List<SsaAlertEngine.Signal>>>()
        variants.add("① 纯 BBI12（基线）" to (bbiBuys + bbiSells))
        // A 组：BBI12 入场 → WR 出场（换平滑/换周期）
        periods.forEach { per ->
            smooths.forEach { (sm, _) ->
                val s = wrSells(wrSeq(per, sm), 25.0).map { i ->
                    SsaAlertEngine.Signal(i, calc[i].date, calc[i].time, calc[i].close, false, "W2",
                        "WR$per·$sm 上穿25", false)
                }
                variants.add("BBI12买→WR$per·$sm 卖" to (bbiBuys + s))
            }
        }
        // B 组：BBI12 入场 ∩ WR 买闸门（平滑/换周期）
        periods.forEach { per ->
            val w = wrSeq(per, "MA3")
            val buys = bbiBuys.filter { it.index in w.indices && !w[it.index].isNaN() && w[it.index] >= 50.0 }
            variants.add("WR$per·MA3 买闸≥50 → BBI12卖" to (buys + bbiSells))
        }
        variants.add("⑧ 对照：BBI12底→(BB6∪BBI12)顶" to (bbiBuys + bbiSells + b.bbPivots.filter { !it.buy }))

        File("build").mkdirs()
        val sum = StringBuilder()
        val tradeCsv = StringBuilder()
        csv(sum, listOf("variant", "strategyPct", "buyHoldPct", "excessPct", "trades",
            "winRatePct", "maePct", "mpdPct", "mddPct"))
        csv(tradeCsv, listOf("variant", "openDate", "openTime", "openPrice", "closeDate", "closeTime",
            "closePrice", "barsHeld", "pnlPct", "exit", "entryCond"))
        println()
        println("%-36s %9s %9s %7s %8s %8s %9s".format(
            "变体", "策略%", "超额%", "笔数", "胜率%", "MPD%", "MDD%"))
        variants.forEach { (label, raw) ->
            val (tr, perf) = SsaBbiPivotEngine.simulate(calc, raw.sortedBy { it.index }, allOpen, from, to, 0.0, p)
            csv(sum, listOf(label, "%.2f".format(perf.strategyReturnPct), "%.2f".format(perf.buyHoldReturnPct),
                "%.2f".format(perf.excessPct), perf.trades.toString(), "%.1f".format(perf.winRatePct),
                "%.2f".format(perf.maePct), "%.2f".format(perf.mpdPct), "%.2f".format(perf.mddPct)))
            println("%-36s %9.2f %9.2f %7d %8.1f %8.2f %9.2f".format(
                label, perf.strategyReturnPct, perf.excessPct, perf.trades,
                perf.winRatePct, perf.mpdPct, perf.mddPct))
            tr.forEach { t ->
                csv(tradeCsv, listOf(label, calc[t.openIndex].date, calc[t.openIndex].time,
                    "%.3f".format(t.openPrice), calc[t.closeIndex].date, calc[t.closeIndex].time,
                    "%.3f".format(t.closePrice), t.barsHeld.toString(), "%.2f".format(t.pnlPct),
                    t.exit, t.entryCond))
            }
        }
        File("build/ssa_600176_wr_grid.csv").writeText(sum.toString())
        File("build/ssa_600176_wr_grid_trades.csv").writeText(tradeCsv.toString())
        println()
        println("汇总 build/ssa_600176_wr_grid.csv · 明细 build/ssa_600176_wr_grid_trades.csv")
    }

    // ═══════════ 600176 · WR 阈值网格（下穿买入 / 上穿卖出） ═══════════
    //
    // 上一轮定下 WR 周期 28 + MA5 平滑后，本轮只调**阈值**：
    // · 买入阈值 buyT：WR 自上方**跌破** buyT（超卖区回升）→ 买
    // · 卖出阈值 sellT：WR 自下方**升破** sellT（超买区回落）→ 卖
    // 三组对照：A=保留 BBI12 谷底入场只扫 sellT；B=纯 WR 双向扫 buyT×sellT；
    // C=BBI12 谷底 ∩ WR 跌破共振(±1根)入场 + WR 升破 sellT 出场。

    @Test
    fun run600176WrThresholdGrid() {
        val code = "600176"
        val all = fetchHourly(code, 1023)
        println("[$code] 抓取 1H K 线 ${all.size} 根")
        if (all.size < 300) { println("数据不足，退出"); return }
        val calc = all.takeLast(500)
        val n = calc.size
        val p = SsaAlertEngine.AlertParams()
        val window = 480
        val from = (n - window).coerceAtLeast(0)
        val to = n - 1
        val allOpen = BooleanArray(n) { true }

        val b = SsaBbiPivotEngine.runMix(calc, window = window,
            modes = listOf(SsaBbiPivotEngine.MixMode.PURE_BBI12), p = p)
        val bbiBuys = b.bbiPivots.filter { it.buy }
        val bbiSells = b.bbiPivots.filter { !it.buy }

        // 上一轮最优的 WR 口径：周期 28 + MA5 平滑
        val w = SsaAlertEngine.sma(SsaBbiPivotEngine.wrOf(calc, 28), 5)

        /** WR 自上方跌破 lv（超卖区回升） */
        fun crossBuy(lv: Double): List<Int> {
            val out = ArrayList<Int>()
            for (i in 1 until n) {
                val a = w[i - 1]; val c = w[i]
                if (a.isNaN() || c.isNaN()) continue
                if (a > lv && c <= lv) out.add(i)
            }
            return out
        }
        /** WR 自下方升破 lv（超买区回落） */
        fun crossSell(lv: Double): List<Int> {
            val out = ArrayList<Int>()
            for (i in 1 until n) {
                val a = w[i - 1]; val c = w[i]
                if (a.isNaN() || c.isNaN()) continue
                if (a < lv && c >= lv) out.add(i)
            }
            return out
        }
        fun sig(lst: List<Int>, buy: Boolean, why: String) = lst.map { i ->
            SsaAlertEngine.Signal(i, calc[i].date, calc[i].time, calc[i].close, buy,
                if (buy) "W1" else "W2", why, false)
        }

        val variants = ArrayList<Pair<String, List<SsaAlertEngine.Signal>>>()
        variants.add("① 纯 BBI12（基线）" to (bbiBuys + bbiSells))

        // A 组：BBI12 谷底入场 + WR28·MA5 升破 sellT 出场（只扫卖出阈值）
        listOf(15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 50.0).forEach { st ->
            variants.add("A｜BBI12买→WR升破${st.toInt()}卖" to
                (bbiBuys + sig(crossSell(st), false, "WR28·MA5升破$st")))
        }

        // B 组：纯 WR28·MA5 双向（扫 buyT × sellT）
        listOf(60.0, 65.0, 70.0, 75.0).forEach { bt ->
            listOf(20.0, 25.0, 30.0).forEach { st ->
                variants.add("B｜纯WR 跌破${bt.toInt()}买/升破${st.toInt()}卖" to
                    (sig(crossBuy(bt), true, "WR跌破$bt") + sig(crossSell(st), false, "WR升破$st")))
            }
        }

        // C 组：BBI12 谷底 ∩ WR 跌破共振(±1根) → WR 升破 sellT 出场
        listOf(60.0, 65.0, 70.0).forEach { bt ->
            val cb = crossBuy(bt).toSet()
            listOf(20.0, 25.0, 30.0).forEach { st ->
                val buys = bbiBuys.filter { bi -> ((bi.index - 1)..(bi.index + 1)).any { cb.contains(it) } }
                variants.add("C｜BBI12∩WR跌破${bt.toInt()}共振→升破${st.toInt()}卖" to
                    (buys + sig(crossSell(st), false, "WR升破$st")))
            }
        }

        File("build").mkdirs()
        val sum = StringBuilder()
        val tradeCsv = StringBuilder()
        csv(sum, listOf("variant", "strategyPct", "buyHoldPct", "excessPct", "trades",
            "winRatePct", "maePct", "mpdPct", "mddPct"))
        csv(tradeCsv, listOf("variant", "openDate", "openTime", "openPrice", "closeDate", "closeTime",
            "closePrice", "barsHeld", "pnlPct", "exit", "entryCond"))
        println()
        println("%-44s %9s %9s %7s %8s %8s %9s".format("变体", "策略%", "超额%", "笔数", "胜率%", "MPD%", "MDD%"))
        variants.forEach { (label, raw) ->
            val (tr, perf) = SsaBbiPivotEngine.simulate(calc, raw.sortedBy { it.index }, allOpen, from, to, 0.0, p)
            csv(sum, listOf(label, "%.2f".format(perf.strategyReturnPct), "%.2f".format(perf.buyHoldReturnPct),
                "%.2f".format(perf.excessPct), perf.trades.toString(), "%.1f".format(perf.winRatePct),
                "%.2f".format(perf.maePct), "%.2f".format(perf.mpdPct), "%.2f".format(perf.mddPct)))
            println("%-44s %9.2f %9.2f %7d %8.1f %8.2f %9.2f".format(
                label, perf.strategyReturnPct, perf.excessPct, perf.trades,
                perf.winRatePct, perf.mpdPct, perf.mddPct))
            tr.forEach { t ->
                csv(tradeCsv, listOf(label, calc[t.openIndex].date, calc[t.openIndex].time,
                    "%.3f".format(t.openPrice), calc[t.closeIndex].date, calc[t.closeIndex].time,
                    "%.3f".format(t.closePrice), t.barsHeld.toString(), "%.2f".format(t.pnlPct),
                    t.exit, t.entryCond))
            }
        }
        File("build/ssa_600176_wr_thr.csv").writeText(sum.toString())
        File("build/ssa_600176_wr_thr_trades.csv").writeText(tradeCsv.toString())
        println()
        println("汇总 build/ssa_600176_wr_thr.csv · 明细 build/ssa_600176_wr_thr_trades.csv")
    }

    // ─────────────────── 多票 × 多窗口 横截面验证（WR28·MA5 结论能否复现） ───────────────────
    //
    // 目的：检验 600176 单票上「WR28·MA5 + 跌破65买/升破25卖」的结论，能否在 ~38 只票 × 4 个
    // 时间窗口上复现，还是只是单票单窗口的过拟合。变体固定为 6 个关键配置，**不对每只票重新调参**。

    @Test
    fun runMultiStockWrValidation() {
        val codes = listOf(
            "600176", "600519", "000858", "600036", "000001", "601318", "600030", "300750",
            "002594", "601012", "600276", "000333", "600887", "601899", "600028", "601857",
            "600900", "601088", "000651", "002415", "600009", "601888", "000725", "002230",
            "600585", "601166", "600048", "000002", "601668", "600438", "002714", "300059",
            "600309", "601398", "600000", "601988", "600104", "000063", "601288", "601939",
            "601328", "600016", "601169", "601009", "600015", "601818", "000538", "000568",
            "002304", "603288", "600809", "000596", "002007", "300347", "600196", "000963",
            "002422", "300760", "603259", "600570", "002050", "300124", "002475", "002241",
            "300308", "002463", "603501", "688981", "688036", "002352", "600745", "000100",
            "000625", "601633", "600741", "601238", "002027", "300015", "600690", "002142",
            "601628", "601601", "000776", "600837", "601211", "600999", "601377", "002736",
            "000166", "600018", "601018", "600011", "600886", "600795", "601985", "600025",
            "003816", "601728", "600050", "000977"
        )
        val p = SsaAlertEngine.AlertParams()

        /** WR 自上方**跌破** lv（超卖区回升）→ 买信号 */
        fun crossBuy(arr: DoubleArray, lv: Double, bars: List<SsaAlertEngine.Bar>): List<SsaAlertEngine.Signal> {
            val o = ArrayList<SsaAlertEngine.Signal>()
            for (i in 1 until arr.size) {
                val a = arr[i - 1]; val c = arr[i]
                if (a.isNaN() || c.isNaN()) continue
                if (a > lv && c <= lv) o.add(SsaAlertEngine.Signal(i, bars[i].date, bars[i].time,
                    bars[i].close, true, "W1", "WR跌破$lv", false))
            }
            return o
        }
        /** WR 自下方**升破** lv（超买区回落）→ 卖信号 */
        fun crossSell(arr: DoubleArray, lv: Double, bars: List<SsaAlertEngine.Bar>): List<SsaAlertEngine.Signal> {
            val o = ArrayList<SsaAlertEngine.Signal>()
            for (i in 1 until arr.size) {
                val a = arr[i - 1]; val c = arr[i]
                if (a.isNaN() || c.isNaN()) continue
                if (a < lv && c >= lv) o.add(SsaAlertEngine.Signal(i, bars[i].date, bars[i].time,
                    bars[i].close, false, "W2", "WR升破$lv", false))
            }
            return o
        }

        val csvFile = File("build/ssa_multistock_wr.csv")
        File("build").mkdirs()
        val head = StringBuilder()
        csv(head, listOf("code", "window", "fromDate", "toDate", "variant", "strategyPct",
            "buyHoldPct", "excessPct", "trades", "winRatePct", "maePct", "mpdPct", "mddPct"))
        csvFile.writeText(head.toString())   // 先落表头，之后逐票 append（崩溃也保进度）

        val ok = ArrayList<String>()
        println("%-8s %s".format("code", "状态"))
        for (code in codes) {
            val all = try { fetchHourly(code, 1023) }
            catch (e: Exception) { println("[$code] 抓取异常：${e.message}"); emptyList() }
            if (all.size < 500) { println("[$code] 数据不足(${all.size})，跳过"); continue }
            val calc = all.takeLast(900)
            val n = calc.size

            // 三条 WR 线（全部无未来函数：WR 只用 ≤i 的收盘价，sma 同理）
            val w28 = SsaAlertEngine.sma(SsaBbiPivotEngine.wrOf(calc, 28), 5)
            val w21 = SsaAlertEngine.sma(SsaBbiPivotEngine.wrOf(calc, 21), 5)
            val w14 = SsaBbiPivotEngine.wrOf(calc, 14)
            val b = SsaBbiPivotEngine.runMix(calc, window = 300,
                modes = listOf(SsaBbiPivotEngine.MixMode.PURE_BBI12), p = p)
            val bbiBuys = b.bbiPivots.filter { it.buy }
            val bbiSells = b.bbiPivots.filter { !it.buy }

            val variants = listOf(
                "① 基线·纯BBI12" to (bbiBuys + bbiSells),
                "② WR28·MA5 65/25" to (crossBuy(w28, 65.0, calc) + crossSell(w28, 25.0, calc)),
                "③ WR14原线 65/25" to (crossBuy(w14, 65.0, calc) + crossSell(w14, 25.0, calc)),
                "④ WR28·MA5 70/25" to (crossBuy(w28, 70.0, calc) + crossSell(w28, 25.0, calc)),
                "⑤ WR21·MA5 65/25" to (crossBuy(w21, 65.0, calc) + crossSell(w21, 25.0, calc)),
                "⑥ BBI12买→WR28升破25卖" to (bbiBuys + crossSell(w28, 25.0, calc))
            )
            val windows = listOf(
                "近300" to Pair(n - 300, n - 1),
                "中300" to Pair(n - 600, n - 301),
                "远300" to Pair(n - 900, n - 601),
                "全窗" to Pair(0, n - 1)
            )
            val sb = StringBuilder()
            for ((wn, rng) in windows) {
                val fr = rng.first; val to = rng.second
                if (fr < 0 || to - fr < 60) continue
                for ((label, sigs) in variants) {
                    val (_, perf) = SsaBbiPivotEngine.simulate(calc, sigs.sortedBy { it.index },
                        BooleanArray(n) { true }, fr, to, 0.0, p)
                    csv(sb, listOf(code, wn, calc[fr].date, calc[to].date, label,
                        "%.2f".format(perf.strategyReturnPct), "%.2f".format(perf.buyHoldReturnPct),
                        "%.2f".format(perf.excessPct), perf.trades.toString(),
                        "%.1f".format(perf.winRatePct), "%.2f".format(perf.maePct),
                        "%.2f".format(perf.mpdPct), "%.2f".format(perf.mddPct)))
                }
            }
            csvFile.appendText(sb.toString())
            ok.add(code)
            println("[%s] ok (%d/%d)".format(code, ok.size, codes.size))
        }
        println()
        println("完成：${ok.size}/${codes.size} 只 → build/ssa_multistock_wr.csv")
        println("成功代码：${ok.joinToString(",")}")
    }
}
