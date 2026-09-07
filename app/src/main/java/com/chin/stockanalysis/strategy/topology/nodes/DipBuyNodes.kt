package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.DailySnapshot
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * ## 恐慌日抄底 dip_buy 节点（双端之一：APK 侧）
 *
 * 规则/参数在 assets/usecases/dip_buy_pipeline.xml，与 AutoQuant Python 引擎共用同一 XML（单一事实源）。
 * 统计支撑：AutoQuant/backtest_logs/_dip_rebound_report.md —— 恐慌日收盘买「前期热门∩自身连跌≥3」龙头，
 * H2/H3 胜率 58-61%（热门龙头 60-68%），冷门连跌股显著更差 → 只做热门跌透龙头。
 *
 * - n_dip_gate    JSONObject {ok, date, streak, fired[], note}
 * - n_dip_signal  JSONObject {as_of, gate, rows:[{code,name,date,close,ret60,streak,drop_pct,vr}], scanned}
 * - n_dip_exit    JSONObject {as_of, gate, signal_today, scanned, strategy, exit:{tp,sl,hold}}
 */
private const val TAG = "DipBuy"

private const val GATE_INDEX = arrayOf("sh000001", "sh000688") // 上证 / 科创50

/** 阶段1：大盘恐慌门控（上证/科创50 连跌≥streakDown 且5日跌≥4%、或5日≤p5Drop、或当日大跌且已连跌） */
class DipMarketGateNode(
    private val streakReq: Int = 3,
    private val p5Drop: Double = -6.0,
    private val dayDrop: Double = -1.5
) : BaseNode<Any, JSONObject>("dip_market_gate", "大盘恐慌门控", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val db = StockDatabase.getInstance(context.androidContext)
        val notes = ArrayList<String>()
        val fired = ArrayList<String>()
        var asOf = ""
        var maxStreak = 0
        for (code in GATE_INDEX) {
            val snaps = try {
                db.dailySnapshotDao().getByCode(code, 90).sortedBy { it.date }
            } catch (e: Exception) {
                Log.w(TAG, "$code 读取失败: ${e.message}")
                continue
            }
            if (snaps.size < 25) continue
            val s = snaps.last()
            asOf = s.date
            // streak：自末根起连续收跌天数
            var streak = 0
            for (j in snaps.indices.reversed()) {
                if ((snaps[j].changePct ?: 0.0) < 0) streak++ else break
            }
            maxStreak = maxOf(maxStreak, streak)
            val day = s.changePct ?: 0.0
            val c5 = if (snaps.size >= 6) snaps[snaps.size - 6].close else 0.0
            val p5 = if (c5 > 0) (s.close / c5 - 1.0) * 100.0 else 0.0
            val tag = if (code == "sh000001") "上证" else "科创50"
            val hit = (streak >= streakReq && p5 <= -4.0) || p5 <= p5Drop ||
                (day <= dayDrop && streak >= streakReq - 1)
            notes.add("$tag连跌$streak天/5日${fmt(p5)}%/当日${fmt(day)}%→${if (hit) "触发" else "未触发"}")
            if (hit) fired.add(tag)
        }
        val ok = fired.isNotEmpty()
        val note = notes.joinToString(" | ")
        context.log(nodeId, if (ok) "🔥 恐慌日触发: $note" else "⛔ 无恐慌信号: $note")
        return JSONObject().put("ok", ok).put("date", asOf).put("streak", maxStreak)
            .put("fired", JSONArray(fired)).put("note", note)
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%+.1f", v)
}

/** 阶段2：热门跌透龙头扫描（门控 ok 才执行；逻辑对齐 usecase_pipeline.py dip_stock_signal） */
class DipStockSignalNode(
    private val hotDays: Int = 60,
    private val hotRatio: Double = 0.35,
    private val streakReq: Int = 3,
    private val deepDrop: Double = -6.0,
    private val volShrink: Double = 0.9,
    private val topN: Int = 6,
    private val scanCap: Int = 1500
) : BaseNode<JSONObject, JSONObject>("dip_stock_signal", "热门跌透龙头扫描", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: JSONObject): JSONObject {
        val gate = input ?: JSONObject()
        val out = JSONObject().put("gate", gate).put("rows", JSONArray()).put("scanned", 0).put("as_of", gate.optString("date", ""))
        if (!gate.optBoolean("ok", false)) {
            context.log(nodeId, "⛔ 大盘无恐慌信号，跳过抄底扫描")
            return out
        }
        val db = StockDatabase.getInstance(context.androidContext)
        // ① 当日快照（今日收跌的股票才有入场价值）
        val today = try {
            db.dailySnapshotDao().getByDate(context.tradeDate)
        } catch (e: Exception) {
            Log.w(TAG, "getByDate 失败: ${e.message}")
            return out
        }
        val candidates = today.filter { (it.changePct ?: 0.0) < 0 }.take(scanCap)
        if (candidates.isEmpty()) {
            context.log(nodeId, "⛔ 今日无收跌股票（快照不足）")
            return out
        }
        // ② 逐只拉历史算近 hotDays 涨幅（热门优先）
        data class S(val code: String, val name: String, val ret60: Double)
        val scored = ArrayList<S>()
        for (snap in candidates) {
            val hist = try {
                db.dailySnapshotDao().getByCode(snap.code, hotDays + 20).sortedBy { it.date }
            } catch (e: Exception) { continue }
            if (hist.size < hotDays) continue
            val c0 = hist[hist.size - 1 - hotDays].close
            val c1 = hist.last().close
            if (c0 <= 0 || c1 <= 0) continue
            scored.add(S(snap.code, snap.name.ifBlank { snap.code }, (c1 / c0 - 1.0) * 100.0))
        }
        if (scored.isEmpty()) {
            context.log(nodeId, "⛔ 历史K线不足，无法排名热门")
            return out
        }
        scored.sortByDescending { it.ret60 }
        val nHot = maxOf(1, (scored.size * hotRatio).toInt())
        val hotCodes = scored.take(nHot).map { it.code }.toHashSet()
        val rows = JSONArray()
        var asOf = ""
        for (c in scored.take(nHot)) {
            val hist = try {
                db.dailySnapshotDao().getByCode(c.code, 120).sortedBy { it.date }
            } catch (e: Exception) { continue }
            if (hist.size < 30) continue
            var streak = 0
            for (j in hist.indices.reversed()) {
                if (streak >= 15) break
                if ((hist[j].changePct ?: 0.0) < 0) streak++ else break
            }
            if (streak < streakReq || streak >= hist.size - 1) continue
            val ck = hist.last().close
            val ck0 = hist[hist.size - 1 - streak].close
            if (ck <= 0 || ck0 <= 0) continue
            val drop = (ck0 / ck - 1.0) * 100.0          // 连跌段累计跌幅(%)
            if (drop < -deepDrop) continue               // 跌得不够深
            // 缩量：连跌期均量 vs 更早基线均量
            var vr: Double? = null
            val hasVol = hist.any { (it.volume ?: 0L) > 0 }
            if (hasVol) {
                val rec = avgVol(hist, hist.size - streak, hist.size)
                val base = avgVol(hist, maxOf(0, hist.size - streak - 10), hist.size - streak)
                if (rec > 0 && base > 0) {
                    vr = rec / base
                    if (vr > volShrink) continue
                }
            }
            val row = JSONObject()
            row.put("code", c.code).put("name", c.name).put("date", hist.last().date)
                .put("close", round(ck)).put("ret60", round(c.ret60))
                .put("streak", streak).put("drop_pct", round(drop))
                .put("vr", if (vr != null) round(vr) else JSONObject.NULL)
            rows.put(row)
            if (hist.last().date > asOf) asOf = hist.last().date
        }
        // 热门优先 + 跌得深者优先
        val list = (0 until rows.length()).map { rows.getJSONObject(it) }
            .sortedWith(compareByDescending<JSONObject> { it.optDouble("ret60", 0.0) }
                .thenByDescending { it.optDouble("drop_pct", 0.0) })
            .take(topN)
        val top = JSONArray()
        list.forEach { top.put(it) }
        context.log(nodeId, "🔥 恐慌日热门跌透龙头: 热门池${scored.size}只(${hotCodes.size}) 筛出${list.size}只")
        return out.put("as_of", asOf).put("rows", top).put("scanned", nHot)
    }

    private fun avgVol(hist: List<DailySnapshot>, lo: Int, hi: Int): Double {
        var sum = 0.0
        var n = 0
        for (j in lo until minOf(hi, hist.size)) {
            val v = hist[j].volume ?: 0L
            if (v > 0) { sum += v.toDouble(); n++ }
        }
        return if (n > 0) sum / n else 0.0
    }

    private fun round(v: Double): Double {
        val m = Math.pow(10.0, 2.0)
        return Math.round(v * m) / m
    }
}

/** 阶段3：离场参数 + 发布口径打包 */
class DipExitPolicyNode(
    private val tp: Double = 4.0,
    private val sl: Double = -2.5,
    private val hold: Int = 3
) : BaseNode<JSONObject, JSONObject>("dip_exit_policy", "抄底离场打包", NodeType.OUTPUT) {

    override suspend fun execute(context: PipelineContext, input: JSONObject): JSONObject {
        val sig = input ?: JSONObject()
        val rows = sig.optJSONArray("rows") ?: JSONArray()
        val out = JSONObject()
        out.put("as_of", sig.optString("as_of", ""))
        out.put("gate", sig.optJSONObject("gate") ?: JSONObject())
        out.put("signal_today", rows)
        out.put("scanned", sig.optInt("scanned", 0))
        out.put("strategy", "恐慌日抄底 dip_buy: 上证/科创50 连跌≥3且5日跌≥4%(或5日≤-6%、或当日恐慌大跌) 收盘后 → 买前期热门(前60日涨幅前35%)∩自身连跌≥3∩缩量跌透龙头；持≤3日 tp+4%/sl-2.5%")
        out.put("exit", JSONObject().put("tp", tp).put("sl", sl).put("hold", hold))
        context.log(nodeId, "📦 dip_buy 打包完成: ${rows.length()} 只候选 (tp+$tp%/sl$sl%/持$hold日)")
        return out
    }
}
