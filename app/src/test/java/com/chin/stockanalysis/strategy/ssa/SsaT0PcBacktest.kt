package com.chin.stockanalysis.strategy.ssa

import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.LayerCfg
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Params
import org.json.JSONArray
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * PC 端（JVM 单测）跑 App 同款 [SsaT0Engine]，用新浪日K(scale=240)/60分钟K(scale=60)
 * 做一次真实回测，结果写入 `build/ssa_t0_pc_result.txt` 供人工核对。
 * 仅验证用途：取不到数据时打印原因并跳过，不做断言。
 */
class SsaT0PcBacktest {

    private fun http(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.setRequestProperty("Referer", "https://finance.sina.com.cn/")
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        println("fetch fail: ${e.message} -> $url"); null
    }

    /** scale: 240=日K, 60=60分钟K；返回升序 [Bar]（date=yyyyMMdd 紧凑，time=HH:mm，日K time 为空） */
    private fun fetchKline(code: String, scale: Int, datalen: Int): List<Bar> {
        val sym = (if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) "sh" else "sz") + code
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
                "CN_MarketData.getKLineData?symbol=$sym&scale=$scale&ma=no&datalen=$datalen"
        val body = http(url) ?: return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<Bar>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val day = o.optString("day", "")
            if (day.length < 10) continue
            val date = day.substring(0, 10).replace("-", "")
            val time = if (day.length >= 16) day.substring(11, 16) else ""
            out.add(Bar(date, time,
                o.optDouble("open", 0.0), o.optDouble("high", 0.0),
                o.optDouble("low", 0.0), o.optDouble("close", 0.0)))
        }
        return out.filter { it.close > 0 && it.high > 0 && it.low > 0 }
            .sortedBy { it.date + it.time }
    }

    /** 单次回测的可读明细（与旧版输出格式一致） */
    private fun detail(res: SsaT0Engine.Result): String {
        val sb = StringBuilder()
        sb.appendLine("周K=${res.w.bars.size} 根, 训练/样本外分界=${res.splitIndex}")
        for (s in listOf(res.full, res.train, res.test)) {
            sb.appendLine(
                "%s | 总收益%+.2f%% 年化%+.2f%% 最大回撤%.2f%% | T单%d 胜率%.1f%% 笔均%+.2f%% 正/反T %d/%d 每周%.2f | 基准%+.2f%% 超额%+.2f%%"
                    .format(s.name, s.totalReturnPct, s.annualizedPct, s.maxDrawdownPct,
                        s.tradeCount, s.winRatePct, s.avgPnlPct, s.longT, s.shortT,
                        s.tradesPerWeek, s.benchReturnPct, s.excessPct)
            )
        }
        val wd = res.weekDown
        sb.appendLine("周线下跌环境：T单%d 胜率%.1f%% 笔均%+.2f%% 累计%+.2f%%".format(
            wd.trades, wd.winRatePct, wd.avgPnlPct, wd.totalPnlPct))
        val dd = res.dayDown
        sb.appendLine("日线下跌环境：T单%d 胜率%.1f%% 笔均%+.2f%% 累计%+.2f%%".format(
            dd.trades, dd.winRatePct, dd.avgPnlPct, dd.totalPnlPct))
        sb.appendLine("风控触发：${res.riskEvents.size} 次")
        val hb = res.h.bars; val eq = res.equity
        var hi = 0; var lo = 0
        for (i in eq.indices) { if (eq[i] > eq[hi]) hi = i; if (eq[i] < eq[lo]) lo = i }
        sb.appendLine("权益 初始%.0f 峰值%.0f@%s 谷值%.0f@%s 期末%.0f".format(
            eq[0], eq[hi], hb[hi].date + " " + hb[hi].time, eq[lo], hb[lo].date + " " + hb[lo].time, eq.last()))
        return sb.toString()
    }

    @Test
    fun runPcBacktest() {
        val code = System.getenv("SSA_T0_CODE") ?: "600176"
        val hour = fetchKline(code, 60, 1023)
        val day = fetchKline(code, 240, 1023)
        val sb = StringBuilder()
        sb.appendLine("=== SSA 三层做T · PC 回测 $code ===")
        sb.appendLine("1H=${hour.size} 根, 日K=${day.size} 根")
        if (hour.size < 200 || day.size < 200) {
            sb.appendLine("数据不足，跳过回测")
        } else {
            val variants = listOf(
                "基线(三态机)" to Params(reduceMode = 0),
                "冻结核心仓" to Params(reduceMode = 1),
                "日线下行降仓" to Params(reduceMode = 2),
                "提前离场" to Params(reduceMode = 3),
                "中线上穿买/下穿卖" to Params(useMidCross = true),
                "中线·盘中触及即成交" to Params(useMidCross = true, execAtTouch = true),
                "中线触及+中位线上升K6" to Params(useMidCross = true, execAtTouch = true, midRisingK = 6),
                "中线触及+中位线EMA平滑5" to Params(useMidCross = true, execAtTouch = true, midSmoothSpan = 5),
                "中线触及+上升K6+平滑5" to Params(useMidCross = true, execAtTouch = true, midRisingK = 6, midSmoothSpan = 5),
                // ── 新增：cycle 拐点驱动做T 各变体 ──
                "cycle拐点·立即" to Params(useCycleRule = true, cyConfirm = 1),
                "cycle拐点·延迟1根" to Params(useCycleRule = true, cyConfirm = 2),
                "cycle·仅上升段" to Params(useCycleRule = true, cyConfirm = 1, cyUpLeg = true, cyDownLeg = false),
                "cycle·仅下降段" to Params(useCycleRule = true, cyConfirm = 1, cyUpLeg = false, cyDownLeg = true),
                "cycle·延迟·仅上升段" to Params(useCycleRule = true, cyConfirm = 2, cyUpLeg = true, cyDownLeg = false),
                "cycle·延迟·仅下降段" to Params(useCycleRule = true, cyConfirm = 2, cyUpLeg = false, cyDownLeg = true),
                // ── 新增：日线 cycle 拐点（更平滑、拐点更少）+ 日线滚动窗口扫描 ──
                "cyD·w40" to Params(useCycleRule = true, cyLayer = "D", day = LayerCfg(30, 40, 20)),
                "cyD·w60" to Params(useCycleRule = true, cyLayer = "D", day = LayerCfg(30, 60, 20)),
                "cyD·w80" to Params(useCycleRule = true, cyLayer = "D", day = LayerCfg(30, 80, 20)),
                "cyD·w120" to Params(useCycleRule = true, cyLayer = "D", day = LayerCfg(30, 120, 20)),
                "cyD·w60·延迟1根" to Params(useCycleRule = true, cyLayer = "D", cyConfirm = 2, day = LayerCfg(30, 60, 20)),
                "cyD·w60·仅上升段" to Params(useCycleRule = true, cyLayer = "D", cyUpLeg = true, cyDownLeg = false, day = LayerCfg(30, 60, 20)),
                "cyD·w60·下穿0也清仓" to Params(useCycleRule = true, cyLayer = "D", cyExitOnZero = true, day = LayerCfg(30, 60, 20)),
                // ── 日线窗口继续放大（趋势：越大越平滑越优）+ 延迟组合 ──
                "cyD·w160" to Params(useCycleRule = true, cyLayer = "D", day = LayerCfg(30, 160, 20)),
                "cyD·w200" to Params(useCycleRule = true, cyLayer = "D", day = LayerCfg(30, 200, 20)),
                "cyD·w120·延迟1根" to Params(useCycleRule = true, cyLayer = "D", cyConfirm = 2, day = LayerCfg(30, 120, 20)),
                "cyD·w160·延迟1根" to Params(useCycleRule = true, cyLayer = "D", cyConfirm = 2, day = LayerCfg(30, 160, 20)),
                "cyD·w200·延迟1根" to Params(useCycleRule = true, cyLayer = "D", cyConfirm = 2, day = LayerCfg(30, 200, 20)),
                // ── WINDOW 段向下止损（针对最大回撤：WINDOW 只在上沿卖出、无下沿保护）──
                "WINDOW破正常下沿止损" to Params(winStopDn = 1),
                "WINDOW破极限下沿止损" to Params(winStopDn = 2),
                "WINDOW半仓买入" to Params(winPosPct = 50.0),
                "WINDOW仅日线上行买入" to Params(winDayUp = true),
                "WINDOW半仓+仅日线上行" to Params(winPosPct = 50.0, winDayUp = true),
                // ── 三态机状态判定改用日线 cycle（更平滑、状态更稳）──
                "三态·日线cycle定状态" to Params(regimeLayer = "D"),
                "三态·日线cycle+WINDOW半仓" to Params(regimeLayer = "D", winPosPct = 50.0)
            )
            val cmp = StringBuilder("方案,总收益%,训练收益%,样本外收益%,年化%,回撤%,T单,胜率%,笔均%,超额%,日线下跌笔数,日线下跌胜率%,日线下跌累计%\n")
            variants.forEachIndexed { idx, (name, params) ->
                val r = runCatching { SsaT0Engine.run(hour, day, params) }
                r.onFailure { sb.appendLine("$name 回测抛异常：${it}") }
                r.onSuccess { res ->
                    val s = res.full; val wd = res.dayDown
                    cmp.appendLine(listOf(name,
                        "%.2f".format(s.totalReturnPct),
                        "%.2f".format(res.train.totalReturnPct), "%.2f".format(res.test.totalReturnPct),
                        "%.2f".format(s.annualizedPct),
                        "%.2f".format(s.maxDrawdownPct), s.tradeCount,
                        "%.1f".format(s.winRatePct), "%.2f".format(s.avgPnlPct),
                        "%.2f".format(s.excessPct), wd.trades,
                        "%.1f".format(wd.winRatePct), "%.2f".format(wd.totalPnlPct)).joinToString(","))

                    File("build/ssa_t0_result_m$idx.txt").writeText("=== $name ===\n" + detail(res))

                    // 基线(idx=0)导出 CSV 供 Python 画图 / 归因
                    if (idx == 0) {
                        val csv = StringBuilder("layer,date,time,open,high,low,close,trend,cycle,mid,nUp,nDn,eUp,eDn,scn\n")
                        fun dump(name: String, L: SsaT0Engine.Layer) {
                            for (i in L.bars.indices) {
                                val b = L.bars[i]
                                csv.appendLine(listOf(name, b.date, b.time, b.open, b.high, b.low, b.close,
                                    L.trend[i], L.cycle[i], L.mid[i], L.nUp[i], L.nDn[i], L.eUp[i], L.eDn[i],
                                    L.scenario[i]).joinToString(","))
                            }
                        }
                        dump("W", res.w); dump("D", res.d); dump("H", res.h)
                        File(System.getenv("SSA_T0_LAYERS") ?: "build/ssa_t0_layers.csv").writeText(csv.toString())

                        val mkb = StringBuilder("index,date,time,price,buy,reason\n")
                        for (m in res.markers) {
                            val b = res.h.bars[m.index]
                            mkb.appendLine(listOf(m.index, b.date, b.time, m.price,
                                if (m.buy) 1 else 0, m.reason.replace(",", " ")).joinToString(","))
                        }
                        File("build/ssa_t0_markers.csv").writeText(mkb.toString())

                        val tkb = StringBuilder("direction,openIndex,openTime,openPrice,closeIndex,closeTime,closePrice,returnPct,barsHeld,weeklyUp,dayUp,exitReason\n")
                        for (t in res.trades) {
                            tkb.appendLine(listOf(t.direction, t.openIndex, t.openTime, t.openPrice,
                                t.closeIndex, t.closeTime, t.closePrice, t.returnPct, t.barsHeld,
                                if (t.weeklyUpAtOpen) 1 else 0, if (t.dayUpAtOpen) 1 else 0,
                                t.exitReason.replace(",", " ")).joinToString(","))
                        }
                        File("build/ssa_t0_trades.csv").writeText(tkb.toString())

                        // 权益曲线 + 状态（供回撤归因）
                        val eqb = StringBuilder("index,date,time,close,equity,benchmark,drawdown,regime\n")
                        for (i in res.h.bars.indices) {
                            val b = res.h.bars[i]
                            eqb.appendLine(listOf(i, b.date, b.time, b.close,
                                res.equity[i], res.benchmark[i], res.drawdown[i], res.regime[i]).joinToString(","))
                        }
                        File("build/ssa_t0_equity.csv").writeText(eqb.toString())
                        sb.append(detail(res))
                    } else if (params.useMidCross || params.useCycleRule) {
                        // 试验规则：导出买卖点供 Python 画图
                        val tag = if (params.useCycleRule) "cycle" else "midcross"
                        val mkb = StringBuilder("index,date,time,price,buy,reason\n")
                        for (m in res.markers) {
                            val b = res.h.bars[m.index]
                            mkb.appendLine(listOf(m.index, b.date, b.time, m.price,
                                if (m.buy) 1 else 0, m.reason.replace(",", " ")).joinToString(","))
                        }
                        File("build/ssa_t0_${tag}_markers_$idx.csv").writeText(mkb.toString())
                    }
                }
            }
            File("build/ssa_t0_compare.txt").writeText(cmp.toString())
            sb.appendLine()
            sb.append("-- 方案对比 --\n").append(cmp)
        }
        val outFile = File(System.getenv("SSA_T0_OUT") ?: "build/ssa_t0_pc_result.txt")
        outFile.parentFile?.mkdirs()
        outFile.writeText(sb.toString())
        println(sb)
    }
}