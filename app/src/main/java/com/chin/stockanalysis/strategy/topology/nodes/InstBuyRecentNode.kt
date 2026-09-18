package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.data.FactorDataProvider
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * ## 月内机构买入检测节点（inst_buy_recent，2026-09-17 用户需求）
 *
 * 「ETF 全行业扫描 + ETF top5 + 三大周期 + 持仓个票 → 对所有准备筛选的股票，
 * 分析最近一个月内是否有国家队/社保/基金/大机构资金买入；命中票直接保送到
 * 生成订单的 node（是否买入或拦截由买入订单 node 的最终操作决定）；即使
 * 最终没有买入，也要在 view log 里红色标注（🚨 前缀 → 执行日志面板红色）。」
 *
 * 与 Python 引擎 `app/src/main/assets/usecases/usecase_pipeline.py` 的
 * `inst_buy_recent` 节点逐字段同口径；参数走 pipeline XML（单一事实源）。
 * 已挂入 `inst_holding_pipeline.xml`（阶段5，n_inst_buy → n_orders 保送链）。
 *
 * 判定口径（三条件同时满足）：
 *   ① 披露日 NOTICE_DATE 距今 ≤ noticeDays(35) 天的十大流通股东记录
 *     （END_DATE 距今 ≤ endDays(130) 天，防陈年报告混入）；
 *   ② HOLD_NUM_CHANGE > 0（环比增持）；
 *   ③ HOLDER_NAME 命中机构身份：国家队(汇金/证金/国新) / 社保养老 / 大基金 /
 *     公募基金 / 险资 / QFII外资 / 北向(香港中央结算)。
 *
 * 数据源：东财 F10 十大流通股东（PageAjax sdltgd，在线逐票）；
 * 实仓必检、其余按池顺序取前 topN(80) 只。命中票 score = 身份权重
 * （国家队95/社保90/大基金88/公募78/险资76/QFII74/北向72）+ 增持幅度。
 *
 * 输出：{as_of, n, n_check, n_hit_codes, hits/instBuyHits:[{code, name, holder,
 * kind, chg_ratio, end_date, notice_date, days_ago, score, reason}], rows(带
 * instBuy 标注)} —— 下游 generate_orders 读 scored 保送；QuantFragmentBase
 * 读 instBuyHits 在 view log 红色标注。
 */
class InstBuyRecentNode(
    private val sourceNode: String = "n_inst_pool",
    private val topN: Int = 80,
    private val noticeDays: Long = 35,
    private val endDays: Long = 130
) : BaseNode<Any, JSONObject>("inst_buy_recent", "月内机构买入检测", NodeType.FACTOR_COMPUTE) {

    companion object {
        private const val TAG = "InstBuyRecentNode"

        /** 机构身份关键词（与 Python _IBR_KINDS 同口径，顺序即优先级）。 */
        private val KINDS: List<Pair<String, List<String>>> = listOf(
            "国家队" to listOf("汇金", "证金", "国新投资", "梧桐树投资", "中央汇金资产管理", "国家队基金"),
            "社保养老" to listOf("社保", "养老"),
            "大基金" to listOf("国家集成电路", "国家制造业", "国家大基金", "先进制造产业投资基金"),
            "公募基金" to listOf(
                "基金", "易方达", "华夏", "嘉实", "富国", "中欧", "广发", "南方", "博时",
                "招商", "兴全", "景顺", "银华", "工银", "建信", "交银", "农银", "汇添富",
                "华安", "国泰", "鹏华", "大成", "万家", "天弘", "诺安", "长信", "海富通"),
            "险资" to listOf("人寿", "平安资管", "平安人寿", "太保", "新华", "泰康", "人保", "阳光人寿", "大家资产"),
            "QFII外资" to listOf(
                "高盛", "瑞银", "摩根", "贝莱德", "淡马锡", "阿布扎比", "挪威央行",
                "新加坡政府投资", "巴克莱", "施罗德", "科威特政府"),
            "北向" to listOf("香港中央结算")
        )

        private val KIND_SCORE = mapOf(
            "国家队" to 95, "社保养老" to 90, "大基金" to 88, "公募基金" to 78,
            "险资" to 76, "QFII外资" to 74, "北向" to 72
        )

        fun holderKind(holderName: String): String? {
            // 「香港中央结算(代理人)有限公司」= H股登记处（持H股股东的托管），
            // ≠「香港中央结算有限公司」(北向资金)。代理人误判为北向会污染信号
            //（2026-09-17 回测时发现，实测海南橡胶曾误命中 chg+0.0%）→ 直接排除。
            if (holderName.contains("代理人")) return null
            for ((kind, kws) in KINDS) {
                if (kws.any { holderName.contains(it) }) return kind
            }
            return null
        }
    }

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val src = (if (sourceNode.isNotBlank()) context.stageOutputs[sourceNode] as? JSONObject else null)
            ?: (input as? JSONObject)
        val srcObj = src ?: JSONObject().put("rows", JSONArray())
        val srcRows = srcObj.optJSONArray("rows") ?: JSONArray()

        val today = LocalDate.now()
        val provider = FactorDataProvider()
        val hits = JSONArray()
        val hitCodes = HashSet<String>()

        // 实仓优先必检，其余按池顺序取前 topN
        val queue = ArrayList<JSONObject>()
        for (i in 0 until srcRows.length()) {
            val r = srcRows.optJSONObject(i) ?: continue
            queue.add(r)
        }
        val holdings = queue.filter { (it.optString("from") ?: "").contains("实仓") }
        val others = queue.filter { !(it.optString("from") ?: "").contains("实仓") }
        val toCheck = (holdings + others).take(topN.coerceAtLeast(holdings.size))

        for (row in toCheck) {
            val c6 = row.optString("code", "")
            if (c6.length != 6 || !c6.all { it.isDigit() }) continue
            val name = row.optString("name", "")
            val recs = try {
                provider.getTopFreeHolders(c6)
            } catch (e: Exception) {
                Log.w(TAG, "十大流通股东获取失败 $c6: ${e.message}")
                emptyList()
            }
            // ── 2026-09-17 用户需求①：机构增持保送前先查「当天选股前是否已退出」──
            // 票级最新报告期 = recs 中最大 END_DATE；按 HOLDER_NAME 只看该机构
            // 「最新一条」记录（END_DATE/NOTICE_DATE 最大）判定：
            // · 机构最新报告期 < 票级最新报告期（从最新一期十大流通股东消失）→ 已退出，
            //   旧增持作废不保送（⚠ 日志标注，与 Python _ibr_check 同口径）；
            // · 最新披露 HOLD_NUM_CHANGE<=0 → 减持/持平，不保送；
            // · 其余走原窗口判定（披露≤35天 + 报告期≤130天 + 增持 + 机构身份）。
            var latestPeriod = ""
            for (rec in recs) {
                val e = rec.optString("END_DATE", "")
                if (e > latestPeriod) latestPeriod = e
            }
            val own = LinkedHashMap<String, JSONObject>()
            fun recKey(o: JSONObject): String =
                o.optString("END_DATE", "") + "|" + o.optString("NOTICE_DATE", "")
            for (rec in recs) {
                val h = rec.optString("HOLDER_NAME", "")
                if (h.isBlank()) continue
                val prev = own[h]
                if (prev == null || recKey(rec) > recKey(prev)) own[h] = rec
            }
            for ((holder, rec) in own) {
                val kind = holderKind(holder) ?: continue
                val end = rec.optString("END_DATE", "")
                if (latestPeriod.isNotEmpty() && end < latestPeriod) {
                    // 已退出：曾增持过才打提示（避免噪音）
                    val everBuy = recs.any {
                        it.optString("HOLDER_NAME", "") == holder &&
                            it.optDouble("HOLD_NUM_CHANGE", 0.0) > 0
                    }
                    if (everBuy) {
                        context.log(nodeId, "⚠ [月内机构买入] $name($c6) $kind·${holder.take(24)} 曾增持但已退出最新一期十大流通股东（$end 后消失），不作保送")
                    }
                    continue
                }
                val notice = rec.optString("NOTICE_DATE", "").takeIf { it.isNotBlank() } ?: end
                val noticeDate = runCatching { LocalDate.parse(notice.take(10)) }.getOrNull() ?: continue
                val endDate = runCatching { LocalDate.parse(end.take(10)) }.getOrNull() ?: continue
                val daysAgo = ChronoUnit.DAYS.between(noticeDate, today)
                if (daysAgo > noticeDays || daysAgo < 0) continue
                if (ChronoUnit.DAYS.between(endDate, today) > endDays) continue
                val chg = rec.optDouble("HOLD_NUM_CHANGE", 0.0)
                if (chg <= 0) continue
                val ratio = rec.optDouble("CHANGE_RATIO", 0.0)
                val score = (KIND_SCORE[kind] ?: 70) + ratio.coerceIn(0.0, 25.0)
                hits.put(JSONObject()
                    .put("code", c6)
                    .put("secid", (if (c6[0] in "569") "sh" else "sz") + c6)
                    .put("name", name)
                    .put("holder", holder)
                    .put("kind", kind)
                    .put("chg_ratio", ratio)
                    .put("hold_ratio", rec.optDouble("FREE_HOLDNUM_RATIO", 0.0))
                    .put("end_date", end)
                    .put("notice_date", notice)
                    .put("days_ago", daysAgo)
                    .put("score", score)
                    .put("reason", "月内机构买入保送($kind·${holder.take(24)} ${if (ratio >= 0) "+" else ""}%.1f%%)".format(ratio)))
                hitCodes.add(c6)
                context.log(nodeId, "🚨 [月内机构买入] $name($c6) $kind·$holder 增持${if (ratio >= 0) "+" else ""}%.1f%%（${notice.take(10)}披露，${daysAgo}天前）".format(ratio))
            }
        }

        // rows 标注（instBuy 字段供表列 / 下游展示）
        val outRows = JSONArray()
        val byCode = HashMap<String, JSONObject>()
        for (i in 0 until hits.length()) {
            val h = hits.optJSONObject(i) ?: continue
            val prev = byCode[h.optString("code")]
            if (prev == null || h.optDouble("score") > prev.optDouble("score")) byCode[h.optString("code")] = h
        }
        for (i in 0 until srcRows.length()) {
            val r = srcRows.optJSONObject(i) ?: continue
            val row = JSONObject(r.toString())
            val h = byCode[row.optString("code")]
            if (h != null) {
                row.put("instBuy", "🚨 %s·%s %+.1f%%(%d天前披露)".format(
                    h.optString("kind"), h.optString("holder").take(16),
                    h.optDouble("chg_ratio"), h.optLong("days_ago")))
                row.put("instBuyScore", h.optDouble("score"))
            }
            outRows.put(row)
        }

        if (hitCodes.isNotEmpty()) {
            context.log(nodeId, "🚨 月内机构买入命中 ${hitCodes.size} 只 / 检测 ${toCheck.size} 只（命中票保送订单 node，是否买入或拦截由买入订单 node 最终操作决定）")
        } else {
            context.log(nodeId, "月内机构买入检测: 0 命中（检测 ${toCheck.size} 只，noticeDays=$noticeDays）")
        }

        val out = JSONObject()
            .put("as_of", context.tradeDate ?: today.toString())
            .put("n", srcRows.length())
            .put("n_check", toCheck.size)
            .put("n_hit_codes", hitCodes.size)
            .put("hits", hits)
            .put("instBuyHits", hits)
            .put("rows", outRows)
            .put("source", "月内机构买入检测(国家队/社保/大基金/公募/险资/QFII/北向 增持)")
        context.setStageOutput(nodeId, out)
        return out
    }
}
