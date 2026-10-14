package com.chin.stockanalysis.strategy.indicatorbt

import org.junit.Test
import java.io.File

/**
 * MULTI 5 条件复合规则的 PC 端诊断（600176 日 K）。
 * 读取 MULTI_DIAG_CSV（date,open,high,low,close,volume），
 * 输出各闸门通过率、买入信号数、回测交易与建议，写入 MULTI_DIAG_OUT。
 */
class MultiRuleDiagTest {

    @Test
    fun diag600176() {
        val csvPath = System.getenv("MULTI_DIAG_CSV")
        org.junit.Assume.assumeTrue("未设置 MULTI_DIAG_CSV，跳过诊断", !csvPath.isNullOrBlank())
        val csv = requireNotNull(csvPath)
        val e = IndicatorBacktestEngine
        val bars = File(csv).readLines().filter { it.isNotBlank() }.map { ln ->
            val p = ln.trim().split(",")
            IndicatorBacktestEngine.Bar(
                p[0], p[1].toDouble(), p[2].toDouble(),
                p[3].toDouble(), p[4].toDouble(), p[5].toLong()
            )
        }
        val n = bars.size
        val cl = bars.map { it.close }.toDoubleArray()
        val (dif, dea, hist) = e.macdSeries(cl, 6, 13, 9)
        val wr = e.wrSeries(bars, 14)
        val (k, d, _) = e.kdjSeries(bars, 9)
        val vol = bars.map { it.volume.toDouble() }.toDoubleArray()
        val obv = DoubleArray(n)
        for (i in 1 until n) obv[i] = obv[i - 1] + when {
            cl[i] > cl[i - 1] -> vol[i]
            cl[i] < cl[i - 1] -> -vol[i]
            else -> 0.0
        }
        val maobvS = e.emaSeries(e.smaSeries(obv, 10), 3)
        val rsiRaw = e.rsiSeries(cl, 6)
        val rsiS = e.emaSeries(rsiRaw, 3)

        fun nvf(a: DoubleArray, i: Int) = i in a.indices && !a[i].isNaN()
        fun rising2(a: DoubleArray, i: Int) = nvf(a, i) && nvf(a, i - 1) && nvf(a, i - 2) &&
            a[i] > a[i - 1] && a[i - 1] > a[i - 2]

        var cMacd = 0; var cWr = 0; var cObv = 0; var cRsi = 0; var cSkdj = 0; var cAll = 0
        for (i in 2 until n) {
            val gMacd = nvf(dif, i) && nvf(dea, i) && dif[i] > dea[i]
            val gWr = nvf(wr, i) && wr[i] < 65.0
            val gObv = rising2(maobvS, i)
            val gRsi = rising2(rsiS, i)
            val skdjBuy = nvf(k, i - 1) && nvf(d, i - 1) && nvf(k, i) && nvf(d, i) &&
                k[i - 1] <= d[i - 1] && k[i] > d[i]
            if (gMacd) cMacd++
            if (gWr) cWr++
            if (gObv) cObv++
            if (gRsi) cRsi++
            if (skdjBuy) cSkdj++
            if (gMacd && gWr && gObv && gRsi && skdjBuy) cAll++
        }

        val nanMaobvS = (0 until n).count { maobvS[it].isNaN() }
        val nanRsiS = (0 until n).count { rsiS[it].isNaN() }
        val nanWr = (0 until n).count { wr[it].isNaN() }
        val nanK = (0 until n).count { k[it].isNaN() }

        val r = e.run(
            IndicatorBacktestEngine.StrategyType.MULTI, bars,
            IndicatorBacktestEngine.BtParams(multiSignals = setOf("macd", "wr", "rsi", "obv", "skdj"))
        )

        val sb = StringBuilder()
        sb.appendLine("=== 600176 日K  ${bars.first().date} ~ ${bars.last().date}  bars=$n ===")
        sb.appendLine("NaN 计数: wr=$nanWr K=$nanK maobvS=$nanMaobvS rsiS=$nanRsiS")
        sb.appendLine("闸门通过 bars: MACD多头=$cMacd WR<65=$cWr MAOBV平滑上升2根=$cObv RSI平滑上升2根=$cRsi SKDJ金叉=$cSkdj")
        sb.appendLine("5条件同日全真(买入信号数)=$cAll")
        sb.appendLine("回测: 交易=${r.trades.size} 总收益=${"%.2f".format(r.stats.totalReturnPct)}% " +
            "最新建议=${r.latestAdvice} 区间末20日建议=${r.recentAdvices.takeLast(20).map { it.advice }}")
        r.trades.take(15).forEach {
            sb.appendLine("  ${it.buyDate}→${it.sellDate} ${"%.2f".format(it.returnPct)}% ${it.barsHeld}根 | ${it.buyWhy} || ${it.sellWhy}")
        }

        // 指定窗口逐日闸门明细（用于定位「某段行情为何不买入」）
        val from = System.getenv("MULTI_DIAG_FROM")
        val to = System.getenv("MULTI_DIAG_TO")
        if (!from.isNullOrBlank() && !to.isNullOrBlank()) {
            sb.appendLine("--- 窗口 $from ~ $to 的成交（买入日过滤） ---")
            r.trades.filter { it.buyDate in from..to }.forEach {
                sb.appendLine("  ${it.buyDate}→${it.sellDate} ${"%.2f".format(it.returnPct)}% ${it.barsHeld}根 | ${it.buyWhy} || ${it.sellWhy}")
            }
        }
        if (!from.isNullOrBlank() && !to.isNullOrBlank()) {
            var wMacd = 0; var wWr = 0; var wObv = 0; var wRsi = 0; var wCross = 0; var wAll = 0
            sb.appendLine("--- 窗口 $from ~ $to 逐日闸门（1=满足） ---")
            sb.appendLine("date        close  MACD WR   OBV  RSI  K    D    金叉 全闸 买入")
            for (i in 2 until n) {
                val dt = bars[i].date
                if (dt < from || dt > to) continue
                val gMacd = nvf(dif, i) && nvf(dea, i) && dif[i] > dea[i]
                val gWr = nvf(wr, i) && wr[i] < 65.0
                val gObv = rising2(maobvS, i)
                val gRsi = rising2(rsiS, i)
                val cross = nvf(k, i - 1) && nvf(d, i - 1) && nvf(k, i) && nvf(d, i) &&
                    k[i - 1] <= d[i - 1] && k[i] > d[i]
                val allOk = gMacd && gWr && gObv && gRsi
                val buy = allOk && cross
                if (gMacd) wMacd++; if (gWr) wWr++; if (gObv) wObv++; if (gRsi) wRsi++
                if (cross) wCross++; if (allOk) wAll++
                sb.appendLine(
                    "$dt %.2f  %s   %s  %s   %s   %s %s  %s   %s   %s".format(
                        cl[i],
                        if (gMacd) "Y" else "-", if (gWr) "Y" else "-", if (gObv) "Y" else "-",
                        if (gRsi) "Y" else "-",
                        if (nvf(k, i)) "%.1f".format(k[i]) else "  -",
                        if (nvf(d, i)) "%.1f".format(d[i]) else "  -",
                        if (cross) "Y" else "-", if (allOk) "Y" else "-", if (buy) "★" else "-"
                    ) + "  [WR=%.1f KDJ金叉=%s]".format(
                        if (nvf(wr, i)) wr[i] else Double.NaN, if (cross) "Y" else "-"
                    )
                )
            }
            sb.appendLine("窗口汇总: MACD=$wMacd WR=$wWr OBV升=$wObv RSI升=$wRsi 金叉=$wCross 四闸全满足=$wAll")
        }
        // 触发窗口敏感性：不同 multiCrossWindow 的表现（避免对单一案例过拟合）
        sb.appendLine("--- SKDJ 触发窗口敏感性（multiCrossWindow，0=仅金叉当日） ---")
        sb.appendLine("窗口  交易  总收益%   年化%  胜率%  盈亏比  最大回撤%  均持仓  基准%   超额%")
        for (w in intArrayOf(0, 1, 2, 3, 4, 5, 6, 10)) {
            val rr = e.run(
                IndicatorBacktestEngine.StrategyType.MULTI, bars,
                IndicatorBacktestEngine.BtParams(
                    multiSignals = setOf("macd", "wr", "rsi", "obv", "skdj"),
                    multiCrossWindow = w
                )
            )
            val s = rr.stats
            sb.appendLine("%4d  %4d  %8.2f  %7.2f  %6.2f  %6.2f  %8.2f  %5d  %7.2f  %7.2f".format(
                w, s.tradeCount, s.totalReturnPct, s.annualizedPct, s.winRatePct,
                s.profitFactor, s.maxDrawdownPct, s.avgHoldBars, s.benchmarkReturnPct, s.excessReturnPct
            ))
        }

        val out = File(System.getenv("MULTI_DIAG_OUT") ?: "build/multi_diag.txt")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        println(sb.toString())
    }
}