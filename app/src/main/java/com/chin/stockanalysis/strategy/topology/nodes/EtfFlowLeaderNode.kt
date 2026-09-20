package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.DataSourceConfig
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * ETF 资金流 → 板块 → **放量大阳线**（主力建仓）节点（**priority level = 1**，2026-09-19 用户需求）。
 *
 * 与 Python `usecase_pipeline.py:etf_flow_leader` 同口径（双端同源，见
 * `assets/usecases/etf_flow_leader_pipeline.xml`）：
 *   ① 钱进方向：ETF 资金流榜净流入 > 0 的主题（东财 ETF 榜 f62/f66）
 *               ∪ 行业板块主力净流入 TopN（东财 clist f62）
 *   ② 个股层：涨幅 ≥ bigYangPct(5%) ＋ 量比 ≥ volRatio(1.8×20日均量)
 *             ＋ 收盘位于当日振幅上 30%（非长上影）
 *   ③ 低位层：距 60 日高 ≤ -lowPos(10%)（主力建仓在低位，过滤追高）
 *   ④ 方向命中 → +20 分优先（用 `StockDataCenter.getSectorsByStock` 反查个股板块，
 *      双向子串匹配，与 PC 侧 board_index 反查表等价）
 *
 * 输出 `efDirectHits` → `generate_orders(directField="efDirectHits")` 直达（跳过拦截；
 * 仓位上限/ST 红线仍生效）。权限：低于三机构同时持有(level=0)，高于 level=4 通用直达。
 */
class EtfFlowLeaderNode(
    private val topN: Int = 10,
    private val bigYangPct: Double = 5.0,
    private val volRatio: Double = 1.8,
    private val lowPos: Double = 10.0,
    private val level: Int = 1
) : BaseNode<Any, JSONObject>("etf_flow_leader", "ETF资金流共振(主力建仓)", NodeType.FACTOR_COMPUTE) {

    companion object {
        private const val TAG = "EtfFlowLeader"
        private const val UT = "bd1d9ddb04089700cf9c27f6f7426281"
    }

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val out = JSONObject()
            .put("as_of", context.tradeDate ?: "").put("n", 0)
            .put("rows", JSONArray()).put("hits", JSONArray())
            .put("efDirectHits", JSONArray()).put("scored", JSONArray())
            .put("level", level).put("themes", JSONArray()).put("boards", JSONArray())
            .put("source", "ETF资金流→板块→放量大阳线(主力建仓, level=$level)")

        // ① 钱进方向
        val hot = ArrayList<String>()
        val themes = JSONArray()
        val boards = JSONArray()
        try {
            val base = DataSourceConfig.urlOr("east_delay_clist",
                "https://push2delay.eastmoney.com/api/qt/clist/get")
            // ETF 榜（f66 成交额榜，f62 净流入近似）
            for (it in fetchClist(base, "b:MK0021+b:MK0022+b:MK0023", "f66", 15)) {
                val nm = it.optString("f14").replace("ETF", "").trim()
                val inYi = it.optDouble("f62", 0.0) / 1e8
                if (nm.isNotBlank() && inYi > 0) {
                    hot.add(nm)
                    themes.put(nm)
                }
            }
            // 行业板块主力净流入 TopN
            for (it in fetchClist(base, "m:90+t:2+f:!50", "f62", topN)) {
                val nm = it.optString("f14").trim()
                val inYi = it.optDouble("f62", 0.0) / 1e8
                if (nm.isNotBlank() && inYi > 0) {
                    hot.add(nm)
                    boards.put(nm)
                }
            }
        } catch (e: Exception) {
            context.log(nodeId, "⚠ ETF/板块资金流不可用：${e.message}")
        }

        // ②③ 个股层
        val snaps = try {
            withContext(Dispatchers.IO) {
                StockDatabase.getInstance(context.androidContext)
                    .dailySnapshotDao().getRecentDays(70)
            }
        } catch (e: Exception) {
            context.log(nodeId, "⚠ 日K快照读取失败：${e.message}")
            emptyList()
        }
        if (snaps.isEmpty()) return out
        val byCode = snaps.groupBy { it.code }
        val hits = JSONArray()
        val rows = JSONArray()
        for ((code, list) in byCode) {
            val s = list.sortedBy { it.date }
            if (s.size < 25) continue
            val last = s.last()
            val prev = s[s.size - 2]
            if (last.close <= 0 || prev.close <= 0) continue
            val chg = last.close / prev.close - 1.0
            val vols = s.subList(s.size - 21, s.size - 1).map { it.volume.toDouble() }
            val vmean = if (vols.isEmpty()) 0.0 else vols.average()
            val vr = if (vmean > 0) last.volume / vmean else 0.0
            val hi = last.high
            val lo = last.low
            val upper = if (hi > lo) (last.close - lo) / (hi - lo) else 1.0
            val hi60 = s.takeLast(60).maxOfOrNull { it.high } ?: last.close
            val pos60 = if (hi60 > 0) (last.close / hi60 - 1.0) * 100 else 0.0
            if (chg < bigYangPct / 100.0 || vr < volRatio ||
                upper < 0.7 || pos60 > -lowPos
            ) continue
            // 方向匹配（双向子串）
            var hitDir = false
            if (hot.isNotEmpty()) {
                val secs = try {
                    com.chin.stockanalysis.stock.database.StockDataCenter
                        .getSectorsByStock(code).filter { it.isNotBlank() }
                } catch (_: Exception) { emptyList<String>() }
                hitDir = hot.any { k ->
                    k.isNotBlank() && secs.any { b -> b.contains(k) || k.contains(b) }
                }
            }
            val bare = if (code.length > 6) code.substring(code.length - 6) else code
            val secid = if (code.startsWith("sh") || code.startsWith("sz") ||
                code.startsWith("bj")) code else {
                (if (bare.first() in "569") "sh" else "sz") + bare
            }
            val reason = "💰ETF资金流+大阳线(涨%+.1f%% 量比%.1f 距60高%+.0f%%%s)".format(
                chg * 100, vr, pos60, if (hitDir) "·钱进方向✓" else "")
            val score = 60.0 + chg * 100 + minOf(10.0, vr * 2) +
                (if (hitDir) 20.0 else 0.0)
            hits.put(JSONObject()
                .put("code", bare).put("secid", secid)
                .put("name", last.name ?: bare)
                .put("score", Math.round(score * 10) / 10.0)
                .put("reason", reason))
            rows.put(JSONObject().put("code", bare).put("chg", chg * 100)
                .put("vr", vr).put("pos60", pos60))
        }
        // 排序取 TopN
        val sorted = (0 until hits.length()).map { hits.optJSONObject(it)!! }
            .sortedByDescending { it.optDouble("score", 0.0) }.take(topN)
        val finalHits = JSONArray()
        val scored = JSONArray()
        for (h in sorted) {
            finalHits.put(h)
            scored.put(JSONObject().put("secid", h.optString("secid"))
                .put("score", h.optDouble("score", 60.0)))
        }
        out.put("n", rows.length()).put("rows", rows)
            .put("hits", finalHits).put("efDirectHits", finalHits)
            .put("scored", scored).put("themes", themes).put("boards", boards)
        context.log(nodeId, if (finalHits.length() > 0)
            "💰 ETF资金流共振命中 ${finalHits.length()} 只（level$level 直达订单）：" +
                sorted.joinToString("、") { it.optString("name") }
        else
            "ETF资金流共振：0 命中（ETF方向 ${themes.length()} 个 / 板块 ${boards.length()} 个，" +
                "扫描 ${byCode.size} 只，阈 涨≥$bigYangPct% 量比≥$volRatio 距60高≤-$lowPos%）")
        context.setStageOutput(nodeId, out)
        return out
    }

    /** 东财 clist 榜单（返回 diff 列表）。 */
    private suspend fun fetchClist(
        base: String, fs: String, fid: String, pz: Int
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val url = "$base?pn=1&pz=$pz&po=1&np=1&ut=$UT&fltt=2&invt=2" +
                "&fid=$fid&fs=$fs&fields=f12,f14,f62,f66,f3"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://quote.eastmoney.com/")
                .build()
            val body = HttpClientProvider.realtimeClient.newCall(req).execute()
                .body?.string() ?: return@withContext emptyList()
            val diff = JSONObject(body).optJSONObject("data")?.optJSONArray("diff")
                ?: return@withContext emptyList()
            (0 until diff.length()).mapNotNull { diff.optJSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "clist 失败($fs): ${e.message}")
            emptyList()
        }
    }
}
