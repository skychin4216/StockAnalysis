package com.chin.stockanalysis.strategy.ssa

import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaT0Engine.Params
import org.json.JSONArray
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 池级（多票）SSA T0 基线回测：读代码清单 → 逐票抓新浪1H/日K → 跑 [SsaT0Engine] 基线三态机
 * → 聚合全池均值/中位（总收益 & 超额），写入 `build/ssa_t0_pool_result.txt`。
 * 仅基线 reduceMode=0，逐票并发 6 路。仅验证用途，不做断言。
 */
class SsaT0PoolPcBacktest {

    private fun http(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.setRequestProperty("Referer", "https://finance.sina.com.cn/")
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    private fun fetchKline(code: String, scale: Int, datalen: Int): List<Bar> {
        val sym = (if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) "sh" else "sz") + code
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
                "CN_MarketData.getKLineData?symbol=$sym&scale=$scale&ma=no&datalen=$datalen"
        val body = http(url) ?: return emptyList()
        val arr = try { JSONArray(body) } catch (e: Exception) { return emptyList() }
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

    private fun codesFile(): File {
        val cands = listOfNotNull(
            System.getenv("SSA_POOL_FILE")?.let { File(it) },
            File("../smalltools/_records/_codes.txt"),
            File("d:/Stock/StockAnalysis/smalltools/_records/_codes.txt")
        )
        return cands.firstOrNull { it.isFile } ?: error("找不到代码清单文件")
    }

    /** 返回一行 CSV；失败返回带 skip 标记的行 */
    private fun runOne(code: String): String {
        val hour = fetchKline(code, 60, 1023)
        val day = fetchKline(code, 240, 1023)
        if (hour.size < 300 || day.size < 300) {
            return "$code,skip,${hour.size},${day.size}\n"
        }
        return try {
            val r = SsaT0Engine.run(hour, day, Params(reduceMode = 0))
            val f = r.full
            "%.2f,%.2f,%.2f,%d,%.1f,%.2f\n".format(
                f.totalReturnPct, f.benchReturnPct, f.excessPct,
                f.tradeCount, f.winRatePct, f.maxDrawdownPct
            ).let { "$code,$it" }
        } catch (e: Exception) {
            "$code,error,${hour.size},${day.size}\n"
        }
    }

    private fun mean(a: DoubleArray) = if (a.isEmpty()) Double.NaN else a.average()
    private fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return Double.NaN
        val s = a.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    @Test
    fun runPoolBacktest() {
        val codesTxt = codesFile().readText().trim().split("\n")
        val codes = codesTxt.last().split(",").map { it.trim() }.filter { it.length == 6 }
        val out = File("build/ssa_t0_pool_result.txt")
        val csv = StringBuilder("code,总收益%,基准%,超额%,T单,胜率%,回撤%\n")

        val pool = Executors.newFixedThreadPool(6)
        val tasks = codes.map { c -> Callable { c to runOne(c) } }
        val results = pool.invokeAll(tasks)
        pool.shutdown()

        val tr = ArrayList<Double>(); val ex = ArrayList<Double>()
        val bh = ArrayList<Double>(); var ok = 0; var skip = 0
        results.forEach { fut ->
            val (c, line) = fut.get()
            csv.append(line)
            if (line.contains(",skip,") || line.contains(",error,")) {
                skip++
            } else {
                ok++
                val p = line.trim().split(",")
                tr.add(p[1].toDouble()); bh.add(p[2].toDouble()); ex.add(p[3].toDouble())
            }
        }

        val summary = StringBuilder()
        summary.appendLine("=== SSA T0 池级回测（基线三态机, reduceMode=0）===")
        summary.appendLine("池 ${codes.size} 只，成功 $ok，跳过/失败 $skip")
        summary.appendLine("全池 总收益  mean=%+.2f%%  median=%+.2f%%".format(mean(tr.toDoubleArray()), median(tr.toDoubleArray())))
        summary.appendLine("全池 基准(买入持有)  mean=%+.2f%%  median=%+.2f%%".format(mean(bh.toDoubleArray()), median(bh.toDoubleArray())))
        summary.appendLine("全池 超额  mean=%+.2f%%  median=%+.2f%%".format(mean(ex.toDoubleArray()), median(ex.toDoubleArray())))
        summary.appendLine("跑赢基准(超额>0) 占比 = %.1f%%".format(ex.count { it > 0 }.toDouble() / ex.size * 100))
        out.writeText(summary.toString() + "\n-- 逐票明细 --\n" + csv.toString())
        println(summary.toString())
    }
}
