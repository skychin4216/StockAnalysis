package com.chin.stockanalysis.strategy.ssa

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * PC 端（JVM 单测）跑 [SsaAlertEngine]，用新浪 60 分钟 K（scale=60，**含 volume**）
 * 做一次真实短线预警分析，结果写入 `build/ssa_alert_signals.csv` / `build/ssa_alert_indicators.csv` 供人工核对。
 *
 * 断言：①无未来函数（前缀重算一致）；②确定性；③信号条数>0；④JAX 暖机段全 NaN。
 */
class SsaAlertPcTest {

    private fun http(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.setRequestProperty("Referer", "https://finance.sina.com.cn/")
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        println("fetch fail: ${e.message} -> $url"); null
    }

    /** 抓 1H K 线并映射为 [SsaAlertEngine.Bar]（**读 volume**） */
    private fun fetchHourly(code: String, datalen: Int): List<SsaAlertEngine.Bar> {
        val sym = (if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) "sh" else "sz") + code
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
                "CN_MarketData.getKLineData?symbol=$sym&scale=60&ma=no&datalen=$datalen"
        val body = http(url) ?: return emptyList()
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
        sb.append(cells.joinToString(",") { c -> if (c.contains(',') || c.contains('"')) "\"" + c.replace("\"", "\"\"") + "\"" else c })
        sb.append('\n')
    }

    @Test
    fun runPcAlert() {
        val code = System.getenv("SSA_ALERT_CODE") ?: "600176"
        val all = fetchHourly(code, 300)
        println("=== SSA 短线预警 · PC 分析 $code · 抓取 ${all.size} 根 ===")
        if (all.size < 160) {
            println("数据不足（需 ≥160 根以计算 200→末120），跳过")
            return
        }
        // 计算用末 200 根（BBI/JAX 暖机），展示用末 120 根
        val calc = all.takeLast(200)
        val res = SsaAlertEngine.analyze(calc)
        val showN = 120
        val off = calc.size - showN   // 展示区起点在 calc 中的下标

        // ── dump 指标 CSV（末 120 根）──
        val ind = StringBuilder()
        csv(ind, listOf("date", "time", "close", "jax", "jix", "obv", "maobv", "k", "d", "j", "wr", "bbi", "bbima"))
        fun f(x: Double) = if (x.isNaN()) "" else "%.4f".format(x)
        for (i in off until calc.size) {
            val b = res.bars[i]
            csv(ind, listOf(b.date, b.time, "%.2f".format(b.close),
                f(res.jax[i]), f(res.jix[i]), f(res.obv[i]), f(res.maobv[i]),
                f(res.k[i]), f(res.d[i]), f(res.j[i]), f(res.wr[i]), f(res.bbi[i]), f(res.bbiMa[i])))
        }
        File("build").mkdirs()
        File("build/ssa_alert_indicators.csv").writeText(ind.toString())

        // ── dump 信号 CSV（末 120 根内）──
        val sig = StringBuilder()
        csv(sig, listOf("date", "time", "price", "side", "cond", "resonance", "strong", "windowOpen", "score", "reason"))
        val shown = res.signals.filter { it.index >= off }
        shown.forEach { s ->
            csv(sig, listOf(s.date, s.time, "%.2f".format(s.price), if (s.buy) "BUY" else "SELL",
                s.cond, if (s.resonance) "1" else "0", if (s.strong) "1" else "0",
                if (s.windowOpen) "1" else "0", s.score.toString(), s.reason))
        }
        File("build/ssa_alert_signals.csv").writeText(sig.toString())

        // ── dump 逐根评分 / BBI 买入窗 CSV（末 120 根）──
        val sc = StringBuilder()
        csv(sc, listOf("date", "time", "close", "bbi", "bbima", "windowOpen", "barScore"))
        for (i in off until calc.size) {
            val b = res.bars[i]
            csv(sc, listOf(b.date, b.time, "%.2f".format(b.close), f(res.bbi[i]), f(res.bbiMa[i]),
                if (res.buyWindowOpen[i]) "1" else "0", res.barScore[i].toString()))
        }
        File("build/ssa_alert_scores.csv").writeText(sc.toString())

        // ── dump 收益率对比 CSV ──
        val pf = StringBuilder()
        csv(pf, listOf("fromLabel", "toLabel", "strategyPct", "buyHoldPct", "excessPct", "trades", "winRatePct", "netOfCost"))
        val p0 = res.perf
        csv(pf, listOf(p0.fromLabel, p0.toLabel, "%.2f".format(p0.strategyReturnPct),
            "%.2f".format(p0.buyHoldReturnPct), "%.2f".format(p0.excessPct),
            p0.trades.toString(), "%.1f".format(p0.winRatePct), if (p0.netOfCost) "1" else "0"))
        val p1 = SsaAlertEngine.analyze(calc, SsaAlertEngine.AlertParams(includeCost = true)).perf
        csv(pf, listOf(p1.fromLabel, p1.toLabel, "%.2f".format(p1.strategyReturnPct),
            "%.2f".format(p1.buyHoldReturnPct), "%.2f".format(p1.excessPct),
            p1.trades.toString(), "%.1f".format(p1.winRatePct), if (p1.netOfCost) "1" else "0"))
        File("build/ssa_alert_perf.csv").writeText(pf.toString())

        println("信号 ${shown.size} 条（末120根），已写 build/ssa_alert_signals.csv / ssa_alert_indicators.csv / ssa_alert_scores.csv / ssa_alert_perf.csv")
        val buy = shown.count { it.buy }; val sell = shown.size - buy
        println("买 $buy 条 / 卖 $sell 条；共振 ${shown.count { it.resonance }} 条；强动能 ${shown.count { it.strong }} 条")
        println("末根评分 ${res.lastScore} 分；末根买入窗 ${if (res.buyWindowOpen[calc.size - 1]) "开" else "关"}")
        println("收益率对比(毛)：策略 %+.2f%% / 买持 %+.2f%% / 超额 %+.2f%% / ${p0.trades} 笔 / 胜率 %.1f%%"
            .format(p0.strategyReturnPct, p0.buyHoldReturnPct, p0.excessPct, p0.winRatePct))
        println("收益率对比(净)：策略 %+.2f%% / 买持 %+.2f%% / 超额 %+.2f%% / ${p1.trades} 笔 / 胜率 %.1f%%"
            .format(p1.strategyReturnPct, p1.buyHoldReturnPct, p1.excessPct, p1.winRatePct))
        shown.takeLast(10).forEach { println("  ${it.date} ${it.time} ${if (it.buy) "买" else "卖"} ${it.cond} ${it.score}分 @%.2f ${it.reason}".format(it.price)) }

        // ① 信号条数 > 0（有效行情下应至少有一条）
        assertTrue("应至少产生 1 条信号", shown.isNotEmpty())

        // ② 确定性：同输入同输出
        val res2 = SsaAlertEngine.analyze(calc)
        for (i in calc.indices) {
            assertEquals("jax 确定性", res.jax[i], res2.jax[i], 0.0)
            assertEquals("wr 确定性", res.wr[i], res2.wr[i], 0.0)
        }
        assertEquals("信号条数确定性", res.signals.size, res2.signals.size)
        assertEquals("末根评分确定性", res.lastScore, res2.lastScore)
        assertEquals("策略收益率确定性", res.perf.strategyReturnPct, res2.perf.strategyReturnPct, 0.0)
        assertEquals("买持收益率确定性", res.perf.buyHoldReturnPct, res2.perf.buyHoldReturnPct, 0.0)
        assertEquals("交易笔数确定性", res.perf.trades, res2.perf.trades)

        // ③ 无未来函数：截取前缀 [0..i] 重算，与全量结果在 i 处一致
        for (i in (calc.size - 1) downTo 60 step 20) {
            val pre = SsaAlertEngine.analyze(calc.subList(0, i + 1))
            assertEquals("jax 无未来函数@$i", res.jax[i], pre.jax[i], 1e-6)
            assertEquals("jix 无未来函数@$i", res.jix[i], pre.jix[i], 1e-6)
            assertEquals("obi 无未来函数@$i", res.obv[i], pre.obv[i], 1e-6)
            assertEquals("bbi 无未来函数@$i", res.bbi[i], pre.bbi[i], 1e-6)
            assertEquals("wr 无未来函数@$i", res.wr[i], pre.wr[i], 1e-6)
            assertEquals("买入窗无未来函数@$i", res.buyWindowOpen[i], pre.buyWindowOpen[i])
            assertEquals("逐根评分无未来函数@$i", res.barScore[i], pre.barScore[i])
        }

        // ④ JAX 暖机段 [0, N+1) 全 NaN（jix 自 N-1 播种，ma1 再需 2 根 → jax 自 N+1 有效）
        val N = 5
        for (i in 0 until N + 1) assertTrue("JAX 暖机段应为 NaN @$i", res.jax[i].isNaN())
        assertTrue("JAX 展示区应有效", !res.jax[calc.size - 1].isNaN())
        assertTrue("BB 均线展示区应有效", !res.bbiMa[calc.size - 1].isNaN())

        // ⑤ 评分只可能取 0/20/40/60/80/100
        for (i in calc.indices) {
            val s = res.barScore[i]
            assertTrue("评分取值非法 @$i = $s", s == 0 || s == 20 || s == 40 || s == 60 || s == 80 || s == 100)
        }

        // ⑥ 硬闸门：评分 > 0 必须买入窗开启；窗口关闭 ⇒ 评分 0
        for (i in calc.indices) {
            if (res.barScore[i] > 0) assertTrue("评分>0 但买入窗关闭 @$i", res.buyWindowOpen[i])
            else if (!res.buyWindowOpen[i]) assertEquals("窗口关但评分非 0 @$i", 0, res.barScore[i])
        }

        // ⑦ 逐根评分 = 20 × 同根 B1..B5 命中数（X1 不计分）
        val bSet = setOf("B1", "B2", "B3", "B4", "B5")
        val cnt = IntArray(calc.size)
        res.signals.forEach { s -> if (s.buy && s.cond in bSet) cnt[s.index] = cnt[s.index] + 1 }
        for (i in calc.indices) {
            val expect = if (res.buyWindowOpen[i]) 20 * cnt[i] else 0
            assertEquals("评分与命中数不一致 @$i", expect, res.barScore[i])
        }

        // ⑨ 收益率区间 = 末 120 根
        assertEquals("perf.fromIndex", (calc.size - 120).coerceAtLeast(0), res.perf.fromIndex)
        assertEquals("perf.toIndex", calc.size - 1, res.perf.toIndex)
        assertEquals("windowFrom", (calc.size - 120).coerceAtLeast(0), res.windowFrom)
        assertEquals("lastScore", res.barScore[calc.size - 1], res.lastScore)
        assertEquals("买持收益率", (calc.last().close / calc[res.perf.fromIndex].close - 1.0) * 100.0,
            res.perf.buyHoldReturnPct, 1e-9)
    }
}
