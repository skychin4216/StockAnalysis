package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ 超级权限闸（priority level = 0，2026-09-19 用户需求）。
 *
 * 语义：**国家队（中央汇金/证金/国新投资/梧桐树）+ 社保养老 同时**在近期披露
 * 买入同一票 → 视为最高置信度信号，写成 `superDirectHits` 直达生成订单 node
 * （跳过趋势/形态/口诀三重拦截）。等级体系：0 超级权限 > 4 资金流直达 >
 * 3 激进 > 2 常规 > 1 严格。
 *
 * ⚠️ 数据来源 = 上游 `inst_buy_recent` 的 hits（十大流通股东披露）。
 * 持仓披露是**季度粒度**，"买入时间"只能用披露日 `NOTICE_DATE` 近似
 * （真实成交日公开数据不可得）——即口径为「近 N 天内**披露**的增持」。
 *
 * 参数：sourceNode=n_inst_buy、noticeDays=7（"一个星期之内"）、
 *      requireBoth=true（必须双买入；false 则任一即可）、level=0。
 * 输出：{as_of, n, superDirectHits/hits:[{code,secid,name,holder,kind,kinds...,
 *      notice_date,days_ago,score,level,reason}], scored, rows, source}。
 *
 * 与 Python `usecase_pipeline.py:super_holder_gate` 同口径（双端同源，见
 * assets/usecases/inst_holding_pipeline.xml 的 n_super 节点）。
 */
class SuperHolderGateNode(
    private val sourceNode: String = "n_inst_buy",
    private val noticeDays: Int = 7,
    private val requireBoth: Boolean = true,
    private val level: Int = 0,
    /** recent（严格，近 N 天双买入）/ hold（宽松，"没卖出就能买"）。 */
    private val mode: String = "recent"
) : BaseNode<Any, JSONObject>("super_holder_gate", "★超级权限(国家队+社保双买入)",
    NodeType.FACTOR_COMPUTE) {

    companion object {
        /** 超级权限要求的两个机构身份（与 InstBuyRecentNode.KINDS 的 kind 字符串一致）。 */
        private val NEED = setOf("国家队", "社保养老")

        /** mode=triple：**国家队 + 大基金 + 社保 三机构同时持有**（2026-09-19 用户需求②）。
         *  回测（_super_holder_backtest.py，2019 起）该口径最优：47 样本、T+5 胜率 66%、
         *  均 +2.17%（宽松档 3 倍）、✅+◐ 44.7%，故作为 level=0 默认口径。 */
        private val NEED3 = setOf("国家队", "大基金", "社保养老")
    }

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val src = (if (sourceNode.isNotBlank()) context.stageOutputs[sourceNode] as? JSONObject else null)
            ?: (input as? JSONObject)
        val srcObj = src ?: JSONObject()
        // mode=recent（默认，严格）：近 noticeDays 天"披露增持"（hits）
        // mode=hold  （宽松）  ：只要最新一期仍持有且未减持（holds）——「没卖出就能买」
        val pool = if (mode == "hold" || mode == "triple")
            (srcObj.optJSONArray("holds") ?: srcObj.optJSONArray("holdHits") ?: JSONArray())
        else (srcObj.optJSONArray("hits") ?: srcObj.optJSONArray("instBuyHits") ?: JSONArray())

        // 按代码分组（同票多机构多条）
        val byCode = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until pool.length()) {
            val h = pool.optJSONObject(i) ?: continue
            val c = h.optString("code")
            if (c.isBlank()) continue
            byCode.getOrPut(c) { ArrayList() }.add(h)
        }

        val outHits = JSONArray()
        val scored = JSONArray()
        for ((c6, hs) in byCode) {
            val recent = if (mode == "hold" || mode == "triple") hs
                         else hs.filter { it.optInt("days_ago", 999) <= noticeDays }
            var kinds = recent.map { it.optString("kind") }.toSet()
            if (mode == "triple" && noticeDays > 0) {
                // 用户 2026-09-19 需求①：三机构同时持有（holds 已保证"当前未退出"）
                // 之外，再要求 **近 noticeDays 天内有买入动作**（回测 D30 最优：70%/T+10+3.32%）。
                // 与 Python `_super_holder_gate` 同口径。
                val fresh = srcObj.optJSONArray("hits") ?: JSONArray()
                val fk = HashSet<String>()
                for (i in 0 until fresh.length()) {
                    val h0 = fresh.optJSONObject(i) ?: continue
                    if (h0.optString("code") == c6 &&
                        h0.optInt("days_ago", 999) <= noticeDays
                    ) fk.add(h0.optString("kind"))
                }
                if (fk.isEmpty()) continue
                kinds = kinds + fk
            }
            val _need = if (mode == "triple") NEED3 else NEED
            val ok = if (requireBoth) kinds.containsAll(_need) else kinds.any { it in _need }
            if (!ok) continue
            val best = (if (mode == "hold")
                recent.maxByOrNull { it.optDouble("ratio", 0.0) }
            else recent.maxByOrNull { it.optDouble("score", 0.0) }) ?: continue
            val secid = (if (c6.first() in "569") "sh" else "sz") + c6
            val hitKinds = kinds.filter { it in NEED }.sorted()
            val row = JSONObject(best.toString())
                .put("secid", secid)
                .put("level", level)
                .put("superKinds", JSONArray(hitKinds))
                .put("reason", if (mode == "hold")
                    "★超级权限(level$level,未卖出)：" + hitKinds.joinToString("+") +
                            " 同时持有未减持"
                else "★超级权限(level$level)：" + hitKinds.joinToString("+") +
                        " 近${noticeDays}天同时买入")
            outHits.put(row)
            scored.put(JSONObject().put("secid", secid)
                .put("score", best.optDouble("score", 95.0)))
        }

        context.log(nodeId, if (outHits.length() > 0)
            "★ 超级权限(level$level) 命中 ${outHits.length()} 只 → 直达生成订单" +
                    "（跳过三重拦截；检查 ${byCode.size} 只，noticeDays=$noticeDays）"
        else
            "超级权限(level$level)：0 命中（检查 ${byCode.size} 只，" +
                    "noticeDays=$noticeDays，requireBoth=$requireBoth）")

        val out = JSONObject()
            .put("as_of", context.tradeDate ?: "")
            .put("n", byCode.size)
            .put("superDirectHits", outHits)
            .put("hits", outHits)
            .put("level", level)
            .put("requireBoth", requireBoth)
            .put("noticeDays", noticeDays)
            .put("scored", if (scored.length() > 0) scored
                            else (srcObj.optJSONArray("scored") ?: JSONArray()))
            .put("rows", srcObj.optJSONArray("rows") ?: JSONArray())
            .put("source", "★超级权限(level=$level)：国家队+社保 近${noticeDays}天双买入")
        context.setStageOutput(nodeId, out)
        return out
    }
}
