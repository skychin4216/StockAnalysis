package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.data.FactorDataProvider
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## 主力行为分阶段识别节点（main_force_stage，2026-09-19 用户需求）
 *
 * 补齐此前只有「主力建仓」（inst_buy_recent / 十大流通股东）的缺口，形成完整生命周期：
 *
 *     建仓 BUILD → 拉升 PULL → 洗盘 WASH → 出货 DUMP
 *
 * 与 PC 侧 `smalltools/_main_force.py::detect()` **同口径同阈值**（单一事实源），
 * 也与既有做T侧枚举（`TTradePipelineNodes.InstIntent`：ACCUMULATING/SHAKING/
 * PULLING_UP/DISTRIBUTING）语义一致 —— 区别是本节点面向**全市场选股**，而非仅持仓做T。
 *
 * 判定口径（与 Python 逐条对齐）：
 *   ① DUMP 出货（优先，风控最要紧）：
 *        · 放量滞涨(Volume Climax)：5日量/20日量 > 1.3 且当日量 > 1.8×20日均量 且 |当日涨幅| < 1%，配合连续上影线≥2；
 *        · 缓跌放量立马撤（祖训11）：近5根阴线≥4 且量比>1.5 且 5日跌幅 < -2%；
 *        · 放量下跌：量比>1.3 且阴线≥3 且 5日跌幅 < -3%。
 *   ② PULL 拉升：均线多头(ma5>ma10>ma20 且 close>ma20) + 近5根阳线≥3 + 量能不缩 + 5日涨幅>3%
 *        （5日涨幅>15% 扣置信度，提示追高风险）。
 *   ③ WASH 洗盘：缩量(量比<0.85) + 近3根下影≥2 + RSI∈[30,52] + 布林位<0.45 + 未破 MA20(≥0.97×)。
 *   ④ BUILD 建仓：缩量 + RSI∈[28,55] + 布林位<0.5 + 20日涨幅<8%。
 *
 * 数据来源：上游 rows 已算好的技术字段（close/ma5/ma10/ma20/rsi/volRatio/bollPos/chg5/
 * chg20，键名有多套别名，逐个容错读取）；主力净额走 `FactorDataProvider.getCapitalFlow`。
 * **字段缺失时安全降级为 NONE**（不猜测、不崩），并在 notes 里留痕。
 *
 * 输出：{as_of, n, n_tagged, counts{BUILD/PULL/WASH/DUMP/NONE}, rows(带 mfStage/mfConf/
 *   mfReasons 标注), source:"主力行为识别(建仓/拉升/洗盘/出货)"} —— 下游可直接按 mfStage
 *   过滤（如 DUMP 一票否决、WASH/BUILD 加分、PULL 提示追高）。
 */
class MainForceStageNode(
    private val sourceNode: String = "n_direction",
    private val topN: Int = 120,
    private val withFlow: Boolean = true
) : BaseNode<Any, JSONObject>("main_force_stage", "主力行为识别(建仓/拉升/洗盘/出货)", NodeType.FACTOR_COMPUTE) {

    companion object {
        private const val TAG = "MainForceStageNode"
        const val STAGE_BUILD = "BUILD"
        const val STAGE_PULL = "PULL"
        const val STAGE_WASH = "WASH"
        const val STAGE_DUMP = "DUMP"
        const val STAGE_NONE = "NONE"
        val STAGE_CN = mapOf(
            STAGE_BUILD to "建仓吸货", STAGE_PULL to "拉升中",
            STAGE_WASH to "震仓洗盘", STAGE_DUMP to "主力出货", STAGE_NONE to "无明确信号"
        )
        private val VOL_SHRINK = 0.85
        private val VOL_EXPAND = 1.3
    }

    /** 多别名容错取值（不同上游节点字段命名不一致）。 */
    private fun num(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (o.has(k)) {
                val v = o.optDouble(k, Double.NaN)
                if (!v.isNaN()) return v
            }
        }
        return null
    }

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val src = (if (sourceNode.isNotBlank()) context.stageOutputs[sourceNode] as? JSONObject else null)
            ?: (input as? JSONObject)
        val srcRows = src?.optJSONArray("rows") ?: JSONArray()

        val provider = FactorDataProvider()
        val outRows = JSONArray()
        val counts = HashMap<String, Int>()
        var nTagged = 0
        var nSkipped = 0

        val queue = ArrayList<JSONObject>()
        for (i in 0 until srcRows.length()) {
            srcRows.optJSONObject(i)?.let { queue.add(it) }
        }

        for (row in queue.take(topN)) {
            val c6 = row.optString("code", "")
            val close = num(row, "close", "price", "lastClose")
            val ma5 = num(row, "ma5")
            val ma10 = num(row, "ma10")
            val ma20 = num(row, "ma20", "maLong")
            val rsi = num(row, "rsi", "rsi6")
            val vr = num(row, "volRatio", "volumeRatio", "vr")
            val boll = num(row, "bollPos", "boll_pos", "bollPosition")
            val chg5 = num(row, "chg5", "change5", "chg5d")
            val chg20 = num(row, "chg20", "change20", "chg20d")
            val nYang = num(row, "nYang", "upCandles")
            val nYin = num(row, "nYin", "downCandles")
            val upShadow = num(row, "upShadow", "upperShadowCnt")
            val dnShadow = num(row, "dnShadow", "downShadowCnt")
            val climax = row.optBoolean("volumeClimax", false)

            // 必要条件不足 → 安全降级（不猜）
            if (close == null || (ma20 == null && rsi == null && vr == null)) {
                nSkipped++
                val o = JSONObject(row.toString())
                o.put("mfStage", STAGE_NONE).put("mfConf", 0.0)
                    .put("mfReasons", JSONArray().put("上游缺技术字段，已降级"))
                outRows.put(o)
                continue
            }

            val maBull = (ma5 != null && ma10 != null && ma20 != null &&
                ma5 > ma10 && ma10 > ma20 && close > ma20)
            val shrink = vr != null && vr < VOL_SHRINK
            val expand = vr != null && vr > VOL_EXPAND
            val r = rsi ?: 50.0
            val b = boll ?: 0.5
            val c5 = chg5 ?: 0.0
            val c20 = chg20 ?: 0.0
            val holdMa20 = ma20 != null && close >= ma20 * 0.97

            var stage = STAGE_NONE
            var conf = 0.0
            val reasons = ArrayList<String>()

            if ((climax && (upShadow ?: 0.0) >= 2) || (expand && (nYin ?: 0.0) >= 3 && c5 < -3)) {
                stage = STAGE_DUMP; conf = if (climax) 0.80 else 0.70
                reasons.add(if (climax) "放量滞涨(Volume Climax)" else "放量下跌+阴线≥3")
                if (r > 65) reasons.add("RSI超买(${r.toInt()})")
            } else if ((nYin ?: 0.0) >= 4 && (vr ?: 0.0) > 1.5 && c5 < -2) {
                stage = STAGE_DUMP; conf = 0.72
                reasons.add("缓跌放量立马撤(祖训11)")
            } else if (maBull && (nYang ?: 0.0) >= 3 && !shrink && c5 > 3.0) {
                stage = STAGE_PULL; conf = 0.70
                reasons.add("均线多头排列"); reasons.add("5日阳线≥3")
                if (c5 > 15) { conf -= 0.15; reasons.add("短期涨幅过大(追高风险)") }
            } else if (shrink && (dnShadow ?: 0.0) >= 2 && r in 30.0..52.0 && b < 0.45 && holdMa20) {
                stage = STAGE_WASH; conf = 0.75
                reasons.add("缩量承接(量比$vr)"); reasons.add("未破MA20")
            } else if (shrink && r in 28.0..55.0 && b < 0.5 && c20 < 8) {
                stage = STAGE_BUILD; conf = 0.60
                reasons.add("量缩价稳"); reasons.add("布林下轨区")
            }

            // 主力净额增强（可选，失败不影响主判定）
            if (withFlow && c6.length == 6 && c6.all { it.isDigit() }) {
                try {
                    val f = provider.getCapitalFlow(c6)
                    if (f.mainNetInflow != 0.0) {
                        reasons.add("主力净额${(f.mainNetInflow / 1e4).toInt()}万")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "资金流获取失败 $c6: ${e.message}")
                }
            }

            val o = JSONObject(row.toString())
            o.put("mfStage", stage).put("mfConf", Math.round(conf * 100) / 100.0)
            o.put("mfReasons", JSONArray(reasons))
            outRows.put(o)
            counts[stage] = (counts[stage] ?: 0) + 1
            if (stage != STAGE_NONE) nTagged++
        }

        val out = JSONObject()
            .put("as_of", context.tradeDate ?: "")
            .put("n", outRows.length())
            .put("n_tagged", nTagged)
            .put("n_skipped", nSkipped)
            .put("counts", JSONObject(counts as Map<*, *>))
            .put("rows", outRows)
            .put("source", "主力行为识别(建仓/拉升/洗盘/出货)")
        context.stageOutputs[nodeId] = out
        return out
    }
}
