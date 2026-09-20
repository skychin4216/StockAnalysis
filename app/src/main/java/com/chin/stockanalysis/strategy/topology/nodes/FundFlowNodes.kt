package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.strategy.data.DataSourceConfig
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * ## 资金流选股节点（双端之一：APK 侧，2026-09-18 用户需求）
 *
 * 与 PC/AutoQuant 引擎 `app/src/main/assets/usecases/usecase_pipeline.py`（fund_flow_* 节点）
 * 同构同源；pipeline 定义在 `assets/usecases/fund_flow_pipeline.xml`（双端共用，单一事实源）。
 *
 * 设计口径 = 《资金流选股策略_优化设计方案_v2.1.md》：
 *   · 趋势分 0-6：20D(3) > 10D(2) > 5D(1)，1D 仅确认(+0.5，需 5D>0)；
 *   · 质量分 0-4：超大单占比≥0.6→2 / ≥0.4→1；5 日流入加速 +1；无 NOISE/背离 +1；
 *   · 总分 0-10 → STRONG_BUY(≥8) / BUY(≥6) / WATCH(≥4) / SKIP。
 *
 * 数据源（与 PC 侧 smalltools/_stock_fundflow.py 同源）：
 *   · 板块 = 东财 push2delay clist（f62 主力净流入，实时）；
 *   · 个股 = 新浪历史资金流 MoneyFlow.ssl_qsfx_zjlrqs（近 60 交易日逐日净流入 + 超大单净额）。
 *   ⚠️ 东财 push2his/push2 在部分网络不可达，故个股历史序列统一用新浪源。
 *
 * 级别参数 level（用户需求：不同级别通过不同 node，最高级直达生成订单 node）：
 *   1=strict(仅≥8) / 2=normal(≥6，默认) / 3=aggressive(≥5) / 4=direct(≥8 → ffDirectHits 直达订单)。
 */
private const val FF_TAG = "FundFlow"

private fun ffToday(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

/** 个股逐日资金流（单位：亿） */
private data class FFBar(
    val date: String,
    val main: Double,
    val jumbo: Double,
    val jumboRatio: Double,
    val chg: Double
)

/** 阶段1：板块资金流榜（净流入 TopN / 净流出 BottomN） */
class FundFlowSectorNode(
    private val topN: Int = 15
) : BaseNode<Any, JSONObject>("fund_flow_sector", "板块资金流榜", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val out = JSONObject()
            .put("as_of", ffToday())
            .put("inflowTop", JSONArray())
            .put("outflowBottom", JSONArray())
            .put("flowMap", JSONObject())
        val rows = fetchBoardFlow()
        if (rows.isEmpty()) {
            context.log(nodeId, "⚠️ 板块资金流为空（东财 push2delay 不可达），跳过板块榜")
            return out
        }
        val sorted = rows.sortedByDescending { it.optDouble("main_yi", 0.0) }
        val inflow = sorted.filter { it.optDouble("main_yi", 0.0) > 0 }.take(topN)
        val outflow = sorted.filter { it.optDouble("main_yi", 0.0) < 0 }.takeLast(topN)
        val arr = JSONArray()
        inflow.forEach { arr.put(it) }
        val arr2 = JSONArray()
        outflow.forEach { arr2.put(it) }
        val map = JSONObject()
        inflow.forEach { map.put(it.optString("name"), it.optDouble("main_yi", 0.0)) }
        context.log(
            nodeId,
            "板块资金流：净流入Top${inflow.size}（" +
                inflow.take(6).joinToString("、") {
                    "${it.optString("name")}${"%.1f".format(it.optDouble("main_yi"))}亿"
                } +
                "）｜净流出Top${outflow.size}（" +
                outflow.take(4).joinToString("、") {
                    "${it.optString("name")}${"%.1f".format(it.optDouble("main_yi"))}亿"
                } + "）"
        )
        return out.put("inflowTop", arr).put("outflowBottom", arr2).put("flowMap", map)
    }

    private suspend fun fetchBoardFlow(): List<JSONObject> = withContext(Dispatchers.IO) {
        val out = ArrayList<JSONObject>()
        try {
            var pn = 1
            while (pn <= 6) {
                val url = DataSourceConfig.urlOr(
                    "east_delay_clist",
                    "https://push2delay.eastmoney.com/api/qt/clist/get") +
                        "?pn=$pn&pz=100&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281" +
                        "&fltt=2&invt=2&fid=f62&fs=m:90+t:2+f:!50&fields=f12,f14,f3,f62,f184"
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", "https://quote.eastmoney.com/")
                    .build()
                val body = HttpClientProvider.realtimeClient.newCall(req).execute()
                    .body?.string() ?: break
                val diff = JSONObject(body).optJSONObject("data")?.optJSONArray("diff") ?: break
                if (diff.length() == 0) break
                for (i in 0 until diff.length()) {
                    val it = diff.optJSONObject(i) ?: continue
                    val name = it.optString("f14")
                    if (name.isBlank()) continue
                    out.add(
                        JSONObject()
                            .put("name", name)
                            .put("code", it.optString("f12"))
                            .put("main_yi", it.optDouble("f62", 0.0) / 1e8)
                            .put("main_pct", it.optDouble("f184", 0.0))
                            .put("zdf_pct", it.optDouble("f3", 0.0))
                    )
                }
                pn++
            }
        } catch (e: Exception) {
            Log.w(FF_TAG, "板块资金流失败: ${e.message}")
        }
        out
    }
}

/** 阶段2：个股资金流 v2.1 双层评分（趋势分 0-6 + 质量分 0-4 = 0~10） */
class FundFlowScreenNode(
    private val topN: Int = 20,
    private val minScore: Double = 4.0,
    private val scanCap: Int = 600
) : BaseNode<Any, JSONObject>("fund_flow_screen", "个股资金流评分", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val out = JSONObject()
            .put("as_of", ffToday()).put("stocks", JSONArray())
            .put("n", 0).put("scanned", 0).put("sectorFlow", JSONObject())

        // 候选池：最近一个交易日的全部日K快照代码（与其它 pipeline 同源）
        val codes = try {
            val db = StockDatabase.getInstance(context.androidContext)
            db.dailySnapshotDao().getRecentDays(1)
                .map { it.code }.distinct()
                .filter { it.length >= 8 }
                .take(scanCap)
        } catch (e: Exception) {
            Log.w(FF_TAG, "候选池读取失败: ${e.message}")
            emptyList()
        }
        if (codes.isEmpty()) {
            context.log(nodeId, "⚠️ 候选池为空（daily_snapshot 无数据），跳过资金流选股")
            return out
        }

        val scored = withContext(Dispatchers.IO) {
            codes.chunked(8).flatMap { chunk ->
                chunk.map { c -> async { scoreOne(c) } }.awaitAll()
            }
        }.filterNotNull()
            .filter { it.optDouble("score", 0.0) >= minScore }
            .sortedByDescending { it.optDouble("score", 0.0) }
            .take(topN)

        val arr = JSONArray()
        scored.forEach { arr.put(it) }
        val dist = scored.groupingBy { it.optString("signal") }.eachCount()
        context.log(
            nodeId,
            "资金流选股：扫描${codes.size}只 → ≥${minScore}分 ${scored.size}只（" +
                (dist.entries.joinToString("、") { "${it.key}${it.value}" }.ifBlank { "无" }) + "）"
        )
        return out.put("stocks", arr).put("n", scored.size).put("scanned", codes.size)
    }

    /** 单只评分（新浪历史资金流 → v2.1 双层评分）。无数据返回 null。 */
    private fun scoreOne(secid: String): JSONObject? {
        val rows = fetchSina(secid) ?: return null
        if (rows.isEmpty()) return null
        val last = rows.last()
        val s5 = rows.takeLast(5).sumOf { it.main }
        val s10 = rows.takeLast(10).sumOf { it.main }
        val s20 = rows.takeLast(20).sumOf { it.main }
        var trend = 0.0
        if (s20 > 0) trend += 3.0
        if (s10 > 0) trend += 2.0
        if (s5 > 0) trend += 1.0
        if (last.main > 0 && s5 > 0) trend += 0.5
        trend = trend.coerceIn(0.0, 6.0)

        var quality = 0.0
        val jr = last.jumboRatio
        if (jr >= 0.6) quality += 2.0 else if (jr >= 0.4) quality += 1.0
        val accel = last.main - rows.takeLast(5).map { it.main }.average()
        if (accel > 0) quality += 1.0
        // 无 NOISE（主力流入却靠中小单）/ 价涨资金流出（DIVERG_A）→ +1
        val noise = last.main > 0 && jr < 0.4
        val diverg = last.chg > 1.0 && last.main < 0
        if (!noise && !diverg) quality += 1.0
        quality = quality.coerceIn(0.0, 4.0)

        val total = ((trend + quality) * 100).toInt() / 100.0
        val signal = when {
            total >= 8.0 -> "STRONG_BUY"
            total >= 6.0 -> "BUY"
            total >= 4.0 -> "WATCH"
            else -> "SKIP"
        }
        val issues = ArrayList<String>()
        if (diverg) issues.add("DIVERG_A")
        if (last.chg < -1.0 && last.main > 0) issues.add("ACCUM_B")
        if (noise) issues.add("NOISE")
        return JSONObject()
            .put("code6", secid.removePrefix("sh").removePrefix("sz").removePrefix("bj"))
            .put("secid", secid)
            .put("date", last.date)
            .put("score", total)
            .put("trend", trend).put("quality", quality)
            .put("signal", signal)
            .put("main_1d", round3(last.main))
            .put("main_5d", round3(s5)).put("main_10d", round3(s10)).put("main_20d", round3(s20))
            .put("jumbo_ratio", round3(jr))
            .put("accel_5d", round3(accel))
            .put("chg", last.chg)
            .put("issues", JSONArray(issues))
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    /** 新浪历史资金流（近 60 交易日），当日进程缓存；失败返回 null。 */
    private fun fetchSina(secid: String): List<FFBar>? {
        if (SINA_DAY != ffToday()) {
            SINA_DAY = ffToday()
            SINA_CACHE.clear()
        }
        SINA_CACHE[secid]?.let { return it }
        return try {
            val sinaHost = DataSourceConfig.host("sina_moneyflow")
                .ifEmpty { "https://vip.stock.finance.sina.com.cn" }
            val url = "$sinaHost/quotes_service/api/json_v2.php/" +
                    "MoneyFlow.ssl_qsfx_zjlrqs?page=1&num=60&sort=opendate&asc=0&daima=$secid"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://finance.sina.com.cn/")
                .build()
            val body = HttpClientProvider.realtimeClient.newCall(req).execute()
                .body?.string() ?: return null
            val arr = JSONArray(body)
            val list = ArrayList<FFBar>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val main = o.optString("netamount", "0").toDoubleOrNull() ?: 0.0
                val jumbo = o.optString("r0_net", "0").toDoubleOrNull() ?: 0.0
                list.add(
                    FFBar(
                        date = o.optString("opendate"),
                        main = main / 1e8,
                        jumbo = jumbo / 1e8,
                        jumboRatio = if (main > 0) min(1.0, jumbo / main) else 0.0,
                        chg = (o.optString("changeratio", "0").toDoubleOrNull() ?: 0.0) * 100
                    )
                )
            }
            val res = list.sortedBy { it.date }.takeLast(60)
            if (res.isNotEmpty()) SINA_CACHE[secid] = res
            res.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(FF_TAG, "个股资金流失败 $secid: ${e.message}")
            null
        }
    }

    companion object {
        private val SINA_CACHE = ConcurrentHashMap<String, List<FFBar>>()
        private var SINA_DAY: String = ""
    }
}

/** 阶段3：级别闸（level 1~4；4=最高级 → ffDirectHits 直达生成订单） */
class FundFlowLevelNode(
    private val level: Int = 2
) : BaseNode<Any, JSONObject>("fund_flow_level", "资金流级别闸", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val src = input as? JSONObject ?: JSONObject()
        val stocks = src.optJSONArray("stocks") ?: JSONArray()
        val minScore: Double
        val direct: Boolean
        when (level) {
            1 -> { minScore = 8.0; direct = false }
            3 -> { minScore = 5.0; direct = false }
            4 -> { minScore = 8.0; direct = true }
            else -> { minScore = 6.0; direct = false }
        }
        val scored = JSONArray()
        val hits = JSONArray()
        for (i in 0 until stocks.length()) {
            val s = stocks.optJSONObject(i) ?: continue
            val sc = s.optDouble("score", 0.0)
            if (sc < minScore) continue
            val secid = s.optString("secid").ifBlank { s.optString("code6") }
            if (secid.isBlank()) continue
            val sc100 = Math.round(sc * 10.0) / 10.0
            val item = JSONObject()
                .put("secid", secid).put("code", secid)
                .put("name", s.optString("name")).put("score", sc100)
                .put("signal", s.optString("signal"))
                .put("reason", "资金流选股(level$level:${s.optString("signal")})")
            scored.put(item)
            if (direct) {
                hits.put(
                    JSONObject(item.toString())
                        .put("reason", "资金流直达(level$level:${s.optString("signal")})")
                )
            }
        }
        val lvName = when (level) {
            1 -> "strict"; 3 -> "aggressive"; 4 -> "direct"; else -> "normal"
        }
        context.log(
            nodeId,
            "💰 资金流级别 level=$level($lvName)：放行 ≥$minScore 分 → ${scored.length()} 只" +
                if (hits.length() > 0) "，其中 ${hits.length()} 只直达订单" else ""
        )
        return JSONObject()
            .put("scored", scored).put("ffDirectHits", hits)
            .put("level", level).put("n", scored.length())
            .put("sectorFlow", src.optJSONObject("sectorFlow") ?: JSONObject())
    }
}
