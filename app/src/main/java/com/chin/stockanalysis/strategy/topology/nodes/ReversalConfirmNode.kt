package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.MacdDivergenceAnalyzer
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 背离确认 + 趋势K线法则（**标注/增强节点**，2026-09-19 用户需求①）。
 *
 * 与 Python `usecase_pipeline.py:reversal_confirm` 同口径（取自《PEG_营收增速_超跌反转.txt》）：
 *   ① MACD 背离：顶背离（复用 `MacdDivergenceAnalyzer.topDivergence`）+ 底背离（`analyze`）
 *   ② **底背离三步法**（专治假背离）：
 *       第1步 价格创阶段新低；第2步 MACD 绿柱不再放大/拐头；第3步 缩量回踩不破低 **且** 放量突破
 *       三步齐 → 「真底背离」；只满足前两步 → 「假背离(待确认)」。
 *       （实测：假底背离占比 ~94%，只看"底背离"就抄底会踩坑）
 *   ③ **趋势K线法则**：0 号K线（最高价不再创新高）→ 后数 3 根取**最低点**画支撑（跌破→离场）；
 *      下跌趋势取 3 根后**最高点**画压力（突破→买入候选）。
 *
 * 本节点**只标注、不否决**（否决由 `GenerateOrdersNode` 的顶背离拦截统一负责，
 * 保证"所有 usecase 顶背离不买"只有一处实现）。输出 rows 供日志/表格展示。
 */
class ReversalConfirmNode(
    private val lookback: Int = 60
) : BaseNode<Any, JSONObject>("reversal_confirm", "背离确认+趋势K线", NodeType.FACTOR_COMPUTE) {

    companion object {
        private const val TAG = "ReversalConfirm"
    }

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val rows = JSONArray()
        val st = JSONObject()
            .put("topDiv", 0).put("botDiv", 0).put("realBottom", 0)
            .put("fakeBottom", 0).put("exitWarn", 0).put("buyTrigger", 0)
        val snaps = try {
            withContext(Dispatchers.IO) {
                StockDatabase.getInstance(context.androidContext)
                    .dailySnapshotDao().getRecentDays(70)
            }
        } catch (e: Exception) {
            Log.w(TAG, "快照读取失败: ${e.message}")
            emptyList()
        }
        if (snaps.isEmpty()) {
            context.log(nodeId, "⚠ 快照为空，跳过背离/趋势K分析")
            return JSONObject().put("n", 0).put("rows", rows).put("stats", st)
        }
        for ((code, list) in snaps.groupBy { it.code }) {
            val s = list.sortedBy { it.date }
            if (s.size < 30) continue
            val sl = s.takeLast(lookback)
            val closes = sl.map { it.close }
            val highs = sl.map { it.high }
            val lows = sl.map { it.low }
            val vols = sl.map { it.volume.toDouble() }
            val n = closes.size
            if (closes.last() <= 0) continue

            // ① 背离（顶/底）
            var dv = ""
            try {
                if (MacdDivergenceAnalyzer.topDivergence(closes, highs) != null) dv = "top"
                else {
                    val r = MacdDivergenceAnalyzer.analyze(sl)
                    if (r.bullDivergence) dv = "bottom"
                }
            } catch (_: Exception) { }
            if (dv == "top") st.put("topDiv", st.optInt("topDiv") + 1)
            if (dv == "bottom") st.put("botDiv", st.optInt("botDiv") + 1)

            // ② 底背离三步法
            val dif = emaSer(closes, 12)
            val dea = emaSer(dif, 9)
            val hist = dif.indices.map { dif[it] - dea[it] }
            val w = minOf(25, n - 1)
            val s1 = n > w && lows.takeLast(5).min() < lows.subList(n - w, n - 5).min()
            val hr = hist.takeLast(5).min()
            val hp = if (n > w) hist.subList(n - w, n - 5).min() else hr
            val s2 = hr > hp || (hist[n - 1] > hist[n - 2] && hist[n - 2] < 0)
            val vr = vols.takeLast(5).average()
            val vp = if (w > 5) vols.subList(n - w, n - 5).average() else vr
            val s3a = vp > 0 && vr < vp
            val s3b = vp > 0 && closes.last() > closes.subList(n - 6, n - 1).max() &&
                vols.last() > vp * 1.2
            var three = ""
            if (s1 && s2 && s3a && s3b) {
                three = "真底背离"
                st.put("realBottom", st.optInt("realBottom") + 1)
            } else if (s1 && s2) {
                three = "假背离(待确认)"
                st.put("fakeBottom", st.optInt("fakeBottom") + 1)
            }

            // ③ 趋势K线法则（0 号K线 + 3 根偏移定支撑/压力）
            var trendK = ""
            var sup: Double? = null
            var res: Double? = null
            val m = minOf(20, n - 4)
            if (m > 3) {
                var hiI = n - m
                var loI = n - m
                for (i in (n - m) until n) {
                    if (highs[i] > highs[hiI]) hiI = i
                    if (lows[i] < lows[loI]) loI = i
                }
                if (hiI + 3 < n) {
                    sup = Math.round(lows[hiI + 3] * 100) / 100.0
                    if (closes.last() < sup) {
                        trendK = "跌破支撑→离场"
                        st.put("exitWarn", st.optInt("exitWarn") + 1)
                    } else trendK = "支撑上方→持有"
                }
                if (loI + 3 < n) {
                    res = Math.round(highs[loI + 3] * 100) / 100.0
                    if (closes.last() > res) {
                        if (trendK.isBlank() || trendK.contains("持有")) {
                            trendK = "突破压力→买入候选"
                            st.put("buyTrigger", st.optInt("buyTrigger") + 1)
                        }
                    } else if (trendK.isBlank()) trendK = "未破压力→观望"
                }
            }
            rows.put(JSONObject()
                .put("code", code)
                .put("close", Math.round(closes.last() * 100) / 100.0)
                .put("dv", dv).put("three", three)
                .put("trendK", trendK)
                .put("support", sup ?: JSONObject.NULL)
                .put("resist", res ?: JSONObject.NULL))
        }
        val out = JSONObject().put("n", rows.length()).put("rows", rows).put("stats", st)
        context.log(nodeId, "🔍 背离/趋势K：顶背离${st.optInt("topDiv")} " +
            "底背离${st.optInt("botDiv")}（真底${st.optInt("realBottom")}/" +
            "假底${st.optInt("fakeBottom")}）｜趋势K 离场预警${st.optInt("exitWarn")} " +
            "买入触发${st.optInt("buyTrigger")}（扫描${rows.length()}只）")
        context.setStageOutput(nodeId, out)
        return out
    }

    /** EMA 序列（与 Python `_ema_ser` 同口径）。 */
    private fun emaSer(vals: List<Double>, n: Int): List<Double> {
        val k = 2.0 / (n + 1)
        val out = ArrayList<Double>(vals.size)
        var e: Double? = null
        for (v in vals) {
            e = if (e == null) v else v * k + e!! * (1 - k)
            out.add(e!!)
        }
        return out
    }
}
