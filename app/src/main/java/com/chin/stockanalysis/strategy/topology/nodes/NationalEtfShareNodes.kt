package com.chin.stockanalysis.strategy.topology.nodes

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ## 国家队（汇金）宽基 ETF 份额动向的 APK 侧节点（2026-09-13 新增）
 *
 * 规则出处：`E:\Android\work\dev\选股思路\national_flow_research.md`、
 * `institutional_breakout_guide.md`（§8 FundFlowAnalyzer 流入/流出扫描）。
 *
 * 与 Python 引擎 `app/src/main/assets/usecases/usecase_pipeline.py` 的 `national_etf_share`
 * 节点逐字段同口径；参数全部走 pipeline XML（单一事实源）。
 *
 * ★ 与 `national_flow`（十大流通股东·**个股**级）的分工：
 *   `national_flow` 读季报法定披露的十大流通股东 —— 滞后 1~3 月，汇金 2015 后披露稀少；
 *   本节点读**宽基 ETF 份额（申赎）** —— 每日可观测，是汇金进出的直接计量
 *   （汇金 2015 年后主要借道沪深300/上证50/中证500/中证1000/科创50/创业板等宽基 ETF）。
 *   两者互补，勿合并。
 *
 * ★ 判定算法**单一事实源** = `smalltools/_etf_share_flow.py`；资产
 *   `data/_etf_share_signal.json` 内已含 `judge`（当前时点结论），双端**只读不算**，
 *   避免 Python / Kotlin / 推送表三处口径漂移。
 *
 * ★ 四象限（份额 vs 单位净值）：
 *   份额↑ & 价格↓ → 低位承接★（国家队低吸，买点信号）
 *   份额↑ & 价格↑ → 追涨申购
 *   份额↓ & 价格↑ → 高位派发★（卖点信号）
 *   份额↓ & 价格↓ → 赎回杀跌
 *   汇金身份取**年度报告**§9.2「期末上市基金前十名持有人」（半年报无此子项，只能年报点名）。
 *
 * ★ 本节点是**市场级横幅**：只贴标签、保持上游顺序、不筛行；缺资产时透传上游不阻断链路。
 *
 * 数据资产：`data/_etf_share_signal.json`（当日信号）
 *          `data/_etf_share_hist.json`（逐只 ETF 份额四象限 + 持有人，可选）
 *   （由 `smalltools/_etf_share_flow.py` 抓取并同步到 assets，双端同源）
 */

/** 单个 JSON 资产的读取缓存：external / files 覆盖优先（PC 推送最新），其次内置 assets。 */
private class EtfShareAsset(private val asset: String) {

    private var key: String? = null
    private var data: JSONObject? = null

    @Synchronized
    fun get(context: Context): JSONObject? {
        val name = asset.substringAfterLast('/')
        val dirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
        val files = dirs.map { File(it, "data/$name") }.filter { it.isFile }
            .ifEmpty { dirs.map { File(it, name) }.filter { it.isFile } }
        if (files.isNotEmpty()) {
            val k = "f|" + files.joinToString("|") { "${it.absolutePath}:${it.lastModified()}" }
            if (key == k && data != null) return data
            for (f in files) {
                try {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        val j = JSONObject(text)
                        if (j.length() > 0) {
                            key = k; data = j; return j
                        }
                    }
                } catch (e: Exception) {
                    Log.w("EtfShareAsset", "解析失败 ${f.absolutePath}: ${e.message}")
                }
            }
        }
        if (key == "asset" && data != null) return data
        return try {
            val j = JSONObject(context.assets.open(asset).bufferedReader().use { it.readText() })
            key = "asset"; data = j; j
        } catch (e: Exception) {
            Log.w("EtfShareAsset", "内置资产读取失败($asset): ${e.message}")
            key = "asset"; data = null; null
        }
    }
}

/** 当日拐点信号资产（data/_etf_share_signal.json）。 */
private val ETF_SHARE_SIGNAL = EtfShareAsset("data/_etf_share_signal.json")

/** 份额历史 + 持有人资产（data/_etf_share_hist.json）。 */
private val ETF_SHARE_HIST = EtfShareAsset("data/_etf_share_hist.json")

/**
 * ## national_etf_share：国家队（汇金）宽基 ETF 份额动向
 *
 * config：
 *   sourceNode  上游节点 id（缺省取第一个带 rows 的上游；仅作透传）
 *   maxFunds    逐只 ETF 明细条数（默认 10）
 *   emitFunds   是否附带 funds 明细（默认 true；false 只回 judge 摘要）
 *
 * 输出（在上游输出之外追加）：
 *   {available, updated, verdict, level, lines[],
 *    quarterly{current,at,net_sub,turns[]}, lastTurn{at,dir,netSub},
 *    huijin{code,pct,names}, attr{...}, daily{...},
 *    dailyLead{from,to,n,outflow[],inflow[]}, recentDaily{from,to,d_pct,lead,board},
 *    push, why[],
 *    funds:[{code,name,last,dSharesPct,dNavPct,netSub,quadrant}], rule}
 */
class NationalEtfShareNode(
    private val sourceNode: String = "",
    private val maxFunds: Int = 10,
    private val emitFunds: Boolean = true
) : BaseNode<Any, JSONObject>("national_etf_share", "国家队(汇金)ETF份额动向", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        // 上游输出整份透传（市场级横幅不改行、不筛行）：优先 sourceNode，其次 DAG 输入
        val src = resolveSource(context, input)
        val out = if (src != null) JSONObject(src.toString()) else JSONObject()
        val sig = ETF_SHARE_SIGNAL.get(context.androidContext)
        val judge = sig?.optJSONObject("judge")
        val lines = judge?.optJSONArray("lines")
        if (sig == null || judge == null || lines == null || lines.length() == 0) {
            context.log(nodeId, "✗ 缺 data/_etf_share_signal.json（国家队ETF份额资产）")
            out.put("available", false)
                .put("note", "缺数据资产：先跑 smalltools/_etf_share_flow.py（或 --snap）")
            context.setStageOutput(nodeId, out)
            return out
        }

        // ── 逐只宽基 ETF 明细（四象限 + 最新披露期）—— 取自 hist，缺失则留空 ──
        val funds = JSONArray()
        if (emitFunds) {
            val width = ETF_SHARE_HIST.get(context.androidContext)?.optJSONObject("width")
            if (width != null) {
                val keys = width.keys().asSequence().sortedDescending().toList()
                for (code in keys) {
                    if (funds.length() >= maxFunds.coerceAtLeast(1)) break
                    val w = width.optJSONObject(code) ?: continue
                    val per = w.optJSONArray("periods")
                    val last = if (per != null && per.length() > 0) per.optJSONObject(per.length() - 1) else null
                    funds.put(
                        JSONObject()
                            .put("code", code)
                            .put("name", w.optString("name"))
                            .put("last", last?.optString("end") ?: JSONObject.NULL)
                            .put("dSharesPct", last?.opt("d_shares_pct") ?: JSONObject.NULL)
                            .put("dNavPct", last?.opt("d_nav_pct") ?: JSONObject.NULL)
                            .put("netSub", last?.opt("net_sub") ?: JSONObject.NULL)
                            .put("quadrant", last?.optString("quadrant") ?: JSONObject.NULL)
                    )
                }
            }
        }

        val lt = judge.optJSONObject("last_turn")
        if (out.optString("as_of").isBlank()) out.put("as_of", context.tradeDate)
        out.put("available", true)
            .put("updated", sig.optString("as_of"))
            .put("verdict", judge.optString("verdict"))
            .put("level", judge.optString("level"))
            .put("lines", lines)
            .put("quarterly", judge.opt("quarterly") ?: JSONObject.NULL)
            .put(
                "lastTurn", if (lt == null) JSONObject.NULL else JSONObject()
                    .put("at", lt.optString("at"))
                    .put("dir", lt.optString("dir"))
                    .put("netSub", lt.opt("net_sub") ?: JSONObject.NULL)
            )
            .put("huijin", judge.opt("huijin") ?: JSONObject.NULL)
            .put("attr", judge.opt("attr") ?: JSONObject.NULL)
            .put("attrCode", judge.optString("attr_code"))
            .put("daily", judge.opt("daily") ?: JSONObject.NULL)
            .put("dailyLead", judge.opt("daily_lead") ?: JSONObject.NULL)
            .put("recentDaily", judge.opt("recent_daily") ?: JSONObject.NULL)
            .put("push", sig.optBoolean("push"))
            .put("why", sig.optJSONArray("why") ?: JSONArray())
            .put("funds", funds)
            .put(
                "rule",
                "宽基ETF份额(申赎)四象限：份额↑&价↓=低位承接(买) / 份额↓&价↑=高位派发(卖)；" +
                    "汇金身份取年报§9.2前十名持有人（法定披露，滞后；半年报无此子项）；" +
                    "日频快照每交易日追加，季度披露给方向、日频给拐点时点"
            )

        // 横幅取「季度方向 + 日频近端」两条：季度给方向、日频给拐点时点（9.11 净申购等）
        val picked = mutableListOf<String>()
        if (lines.length() > 0) picked.add(lines.optString(0))
        for (i in 0 until lines.length()) {
            if (picked.size >= 2) break
            val s = lines.optString(i)
            if (s.isNotBlank() && s != picked[0] && (s.contains("日频净申购领先") || s.contains("背离"))) {
                picked.add(s)
            }
        }
        if (picked.size < 2) {
            for (i in 1 until lines.length()) {
                val s = lines.optString(i)
                if (s.startsWith("日频")) {
                    picked.add(s)
                    break
                }
            }
        }
        context.log(
            nodeId,
            "🏛️ 国家队ETF份额: ${judge.optString("verdict")}（置信 ${judge.optString("level")}）｜" +
                picked.joinToString("；")
        )
        context.setStageOutput(nodeId, out)
        return out
    }

    /** 上游输出：优先 config.sourceNode 的 stageOutput，其次 DAG 输入（单依赖）。 */
    private fun resolveSource(context: PipelineContext, input: Any): JSONObject? {
        val fromCtx = if (sourceNode.isNotBlank()) context.stageOutputs[sourceNode] as? JSONObject else null
        return fromCtx ?: (input as? JSONObject)
    }
}
