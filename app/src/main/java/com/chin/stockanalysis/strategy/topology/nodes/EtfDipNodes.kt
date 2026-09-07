package com.chin.stockanalysis.strategy.topology.nodes

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ## ETF 低位低吸三节点（usecase: etf_dip · pipeline: etf_dip_pipeline.xml）
 *
 * 与三周期同构的「XML DAG 单一事实源」双端方案：
 * 规则/参数放在 app/src/main/assets/usecases/etf_dip_pipeline.xml，
 * APK（本文件）与 app/src/main/assets/usecases/usecase_pipeline.py
 * （Python 引擎，XML DAG 主线：与 XML 同居 assets/usecases 单一事实源）
 * 各按 XML 执行，
 * 任何参数改动只改 XML，双端行为自动一致。
 *
 * 数据源 = 本地 etf_cache.json（13 只 ETF + sh000300 前复权日K，
 * PC 盘后推送/桥拉，与 smalltools/_etf_cache.json 同一份结构）。
 *
 * 规则出处：smalltools/_etf_buy.py v0.3 dip_buy（OOS 11/11 全胜, FULL 55 笔 85.5%）：
 *   距60日高回撤 -25%~-12% + RSI6<30 + 年线上方 close>MA250
 *   + 止跌确认（收阳 或 RSI6 拐头）+ 收盘非前5日新低
 *   + 沪深300 结构多头门控(close>MA20>MA60)；离场 tp+2%/sl-6%/30 日
 *
 * stageOutputs：
 *   - n_etf_gate    JSONObject {ok, date, note, available}
 *   - n_etf_signal  JSONObject {as_of, rows:[{code,name,close,dd60,rsi6,...dip,dip_ok,watch_ok}]}
 *   - n_etf_exit    JSONObject {generated_at, as_of, gate, signal_today, approach, strategy, exit}
 */

/** 本地 ETF 行情缓存读取（external files 优先=最新推送，internal 兜底） */
object EtfCacheStore {
    private const val FILE_NAME = "etf_cache.json"
    @Volatile private var cachedKey: String? = null
    @Volatile private var cached: JSONObject? = null

    @Synchronized
    fun load(context: Context): JSONObject? {
        val files = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .map { File(it, FILE_NAME) }
            .filter { it.isFile }
        if (files.isEmpty()) return null
        val key = files.joinToString("|") { "${it.absolutePath}:${it.lastModified()}" }
        if (cachedKey == key) return cached
        for (f in files) {
            try {
                val text = f.readText()
                if (text.isNotBlank()) {
                    val j = JSONObject(text)
                    if (j.length() > 0) {
                        cachedKey = key
                        cached = j
                        return j
                    }
                }
            } catch (e: Exception) {
                Log.w("EtfCacheStore", "解析失败 ${f.absolutePath}: ${e.message}")
            }
        }
        cachedKey = key
        cached = null
        return null
    }

    /** 判断某 code 是否为指数（不可买，仅门控/基准） */
    fun isIndexCode(code: String): Boolean = code.startsWith("sh000") || code.startsWith("sz399")

    fun closesOf(ent: JSONObject): List<Double>? {
        val snaps = ent.optJSONArray("snaps") ?: return null
        if (snaps.length() == 0) return null
        return (0 until snaps.length()).mapNotNull { i ->
            snaps.optJSONObject(i)?.optDouble("close", Double.NaN)?.takeIf { !it.isNaN() }
        }
    }
}

/** 纯指标计算（对齐 smalltools/_etf_buy.py sma/rsi） */
private object EtfMath {
    fun sma(vals: List<Double>, n: Int): List<Double> {
        val out = ArrayList<Double>(vals.size)
        var acc = 0.0
        val q = ArrayDeque<Double>()
        for (v in vals) {
            acc += v
            q.addLast(v)
            if (q.size > n) acc -= q.removeFirst()
            out.add(acc / q.size)
        }
        return out
    }

    fun rsi(vals: List<Double>, n: Int = 6): List<Double> {
        val out = ArrayList<Double>(vals.size)
        out.add(50.0)
        val gains = ArrayDeque<Double>()
        val losses = ArrayDeque<Double>()
        for (i in 1 until vals.size) {
            val ch = vals[i] - vals[i - 1]
            gains.addLast(if (ch > 0) ch else 0.0)
            losses.addLast(if (ch < 0) -ch else 0.0)
            if (gains.size > n) { gains.removeFirst(); losses.removeFirst() }
            val ag = gains.sum() / gains.size
            val al = losses.sum() / losses.size
            out.add(if (al == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + ag / al))
        }
        return out
    }
}

/** 阶段1：沪深300 结构多头门控（close>MA20>MA60）—— 大盘不弱才出手 */
class EtfGateNode(
    private val indexCode: String = "sh000300",
    private val maFast: Int = 20,
    private val maSlow: Int = 60,
    private val minSnapshots: Int = 60
) : BaseNode<Any, JSONObject>("etf_gate", "沪深300结构多头门控", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val cache = EtfCacheStore.load(context.androidContext)
        val ent = cache?.optJSONObject(indexCode)
        val snaps = ent?.optJSONArray("snaps")
        if (ent == null || snaps == null || snaps.length() < minSnapshots) {
            val note = if (cache == null)
                "本地无 ETF 行情缓存(etf_cache.json)，请先推送/同步行情"
            else "$indexCode 数据不足（${snaps?.length() ?: 0} 根 < $minSnapshots）"
            context.log(nodeId, "⚠ $note")
            return JSONObject().put("ok", false).put("available", cache != null).put("date", "").put("note", note)
        }
        val closes = (0 until snaps.length()).map { snaps.optJSONObject(it).optDouble("close") }
        val maF = EtfMath.sma(closes, maFast)
        val maS = EtfMath.sma(closes, maSlow)
        for (j in snaps.length() - 1 downTo 0) {
            if (j >= maSlow && closes[j] > maF[j] && maF[j] > maS[j]) {
                val note = "沪深300 close(${fmt0(closes[j])})>MA$maFast(${fmt0(maF[j])})>MA$maSlow(${fmt0(maS[j])})"
                context.log(nodeId, "✅ $note @ ${snaps.optJSONObject(j).optString("date")}")
                return JSONObject().put("ok", true).put("date", snaps.optJSONObject(j).optString("date"))
                    .put("note", note).put("available", true)
            }
        }
        val lastDate = snaps.optJSONObject(snaps.length() - 1).optString("date")
        context.log(nodeId, "⛔ 沪深300 非结构多头（截至 $lastDate），低吸暂停")
        return JSONObject().put("ok", false).put("date", lastDate)
            .put("note", "沪深300 未满足 close>MA$maFast>MA$maSlow 结构多头").put("available", true)
    }

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
}

/** 阶段2：ETF 低位低吸信号扫描（dip_buy v0.3，规则参数取自 XML config） */
class EtfDipSignalNode(
    private val ddLo: Double = -25.0,
    private val ddHi: Double = -12.0,
    private val rsiMax: Double = 30.0,
    private val requireAbove250: Boolean = true,
    private val upCloseOrRsiTurn: Boolean = true,
    private val notNew5: Boolean = true,
    private val ddWin: Int = 60,
    private val maYear: Int = 250,
    private val minSnapshots: Int = 900,
    private val watchDdMax: Double = -8.0,
    private val watchRsiMax: Double = 45.0
) : BaseNode<JSONObject, JSONObject>("etf_dip_signal", "ETF低位低吸信号扫描", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: JSONObject): JSONObject {
        val gate = input ?: JSONObject()
        val gateOk = gate.optBoolean("ok", false)
        val cache = EtfCacheStore.load(context.androidContext)
        if (cache == null) {
            context.log(nodeId, "✗ 本地无 ETF 行情缓存，无法本地执行 etf_dip usecase")
            throw IllegalStateException(
                "本地无 ETF 行情缓存(etf_cache.json)。请先在 PC 盘后推送行情，或连接 PC 拉取后重试。"
            )
        }
        val rows = JSONArray()
        var asOf: String? = null
        val it = cache.keys()
        while (it.hasNext()) {
            val code = it.next()
            if (EtfCacheStore.isIndexCode(code)) continue
            val ent = cache.optJSONObject(code) ?: continue
            val snaps = ent.optJSONArray("snaps") ?: continue
            if (snaps.length() < minSnapshots) continue
            val row = scanRow(context, snaps, code, ent.optString("name").ifBlank { code }, gateOk) ?: continue
            val d = row.optString("date", "")
            if (d > (asOf ?: "")) asOf = d
            rows.put(row)
        }
        val out = JSONObject()
        out.put("as_of", asOf ?: "")
        out.put("rows", rows)
        context.log(nodeId, "✓ 扫描完成：${rows.length()} 只 ETF（数据截至 $asOf）")
        return out
    }

    private fun scanRow(context: PipelineContext, snaps: JSONArray, code: String, name: String, gateOk: Boolean): JSONObject? {
        val n = snaps.length()
        val closes = ArrayList<Double>(n)
        val dates = ArrayList<String>(n)
        val opens = ArrayList<Double>(n)
        for (i in 0 until n) {
            val s = snaps.optJSONObject(i)
            closes.add(s.optDouble("close"))
            opens.add(s.optDouble("open"))
            dates.add(s.optString("date"))
        }
        val i = n - 1
        if (i < maxOf(maYear, ddWin)) return null
        val c = closes[i]
        val maY = EtfMath.sma(closes, maYear)[i]
        val r6 = EtfMath.rsi(closes, 6)
        val r6v = r6[i]
        val r6p = if (i >= 1) r6[i - 1] else r6v
        val start = maxOf(0, i - ddWin + 1)
        val run = closes.subList(start, i + 1)
        val dd60 = (c / (run.maxOrNull() ?: c) - 1.0) * 100.0
        val above250 = c > maY
        val upClose = c > opens[i]
        val rsiTurn = r6v > r6p
        val notNew5 = c > (if (i >= 5) closes.subList(i - 5, i).minOrNull() ?: c else c)
        val dipOk = dd60 >= ddLo && dd60 <= ddHi && r6v < rsiMax &&
            (!requireAbove250 || above250) &&
            (!upCloseOrRsiTurn || upClose || rsiTurn) &&
            (!notNew5 || notNew5)
        val watchOk = !dipOk && dd60 <= watchDdMax && r6v < watchRsiMax && above250
        val row = JSONObject()
        row.put("code", code)
        row.put("name", name)
        row.put("date", dates[i])
        row.put("close", round(c, 3))
        row.put("dd60", round(dd60, 2))
        row.put("rsi6", round(r6v, 1))
        row.put("above250", above250)
        row.put("up_close", upClose)
        row.put("rsi_turn", rsiTurn)
        row.put("not_new5", notNew5)
        row.put("gate", gateOk)
        row.put("dip", dipOk && gateOk)   // 仅门控开时才视为可买信号（UI 绿标）
        row.put("dip_ok", dipOk)
        row.put("watch_ok", watchOk)
        return row
    }

    private fun round(v: Double, digits: Int): Double {
        val m = Math.pow(10.0, digits.toDouble())
        return Math.round(v * m) / m
    }
}

/** 阶段3：离场策略 + 发布口径打包（tp/sl/hold 发布参数取自 XML） */
class EtfExitPolicyNode(
    private val tp: Double = 2.0,
    private val sl: Double = -6.0,
    private val hold: Int = 30,
    private val topApproach: Int = 8,
    private val topWatch: Int = 12,
    private val strategyText: String = ""
) : BaseNode<JSONObject, JSONObject>("etf_exit_policy", "ETF低吸离场策略与发布打包", NodeType.ENRICHMENT) {

    override suspend fun execute(context: PipelineContext, input: JSONObject): JSONObject {
        val gate = context.stageOutputs["n_etf_gate"] as? JSONObject ?: JSONObject()
        val signal = input ?: JSONObject()
        val asOf = signal.optString("as_of", "")
        val rows = signal.optJSONArray("rows") ?: JSONArray()
        val approach = ArrayList<JSONObject>()
        val watch = ArrayList<JSONObject>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            when {
                r.optBoolean("dip_ok") -> approach.add(r)
                r.optBoolean("watch_ok") -> watch.add(r)
            }
        }
        approach.sortWith(compareBy { it.optDouble("dd60", 0.0) })
        watch.sortWith(compareBy { it.optDouble("dd60", 0.0) })
        val signalToday = JSONArray()
        approach.take(topApproach).forEach { signalToday.put(it) }
        val watchArr = JSONArray()
        watch.take(topWatch).forEach { watchArr.put(it) }

        val now = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val payload = JSONObject()
        payload.put("generated_at", now)
        payload.put("as_of", asOf)
        payload.put("gate", gate)
        payload.put("signal_today", signalToday)
        payload.put("approach", watchArr)
        payload.put("strategy", strategyText.ifBlank {
            "v0.3 dip_buy: 距60日高回撤-25%~-12% + RSI6小于30 + 年线上方" +
                " + 收阳/RSI拐头 + 非5日新低 + 沪深300结构多头门控(参数见 etf_dip_pipeline.xml)"
        })
        payload.put("exit", JSONObject().put("tp", tp).put("sl", sl).put("hold", hold))
        return payload
    }
}
