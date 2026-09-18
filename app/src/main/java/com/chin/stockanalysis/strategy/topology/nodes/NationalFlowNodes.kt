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
 * ## 国家队 / 大基金持有进出 usecase 的 APK 侧节点（2026-09-13 新增）
 *
 * 规则出处：`E:\Android\work\dev\选股思路\national_flow_research.md`、
 * `institutional_breakout_guide.md`（§8 FundFlowAnalyzer 流入/流出扫描）、
 * `institutional_breakout.py`（NATIONAL_AUTHORITY 权威性加权 / 连续两期确认）。
 *
 * 与 Python 引擎 `app/src/main/assets/usecases/usecase_pipeline.py` 的 `national_flow` 节点
 * 逐字段同口径；参数全部走 pipeline XML（单一事实源）。已挂入：
 *   · `etf_holdings_top5_pipeline.xml`（阶段2，annotate）
 *   · `etf_industry_scan_pipeline.xml`（阶段3，annotate）
 *   · `inst_holding_pipeline.xml`（filter 用法，覆盖「三周期选股 + 两个 ETF 链 + 实仓」汇总池）
 *
 * ★ 判定算法**单一事实源** = `smalltools/_national_flow.py`；资产 `data/_national_flow_hist.json`
 *   内已含 `snap`（当前时点判定结果），双端**只读不算**，避免 Python / Kotlin / 推送表三处口径漂移。
 *
 * ★ 三条**直接持股**通道（2026-09-13 v2，回答「国家队/社保会不会直接买某只股票」）：
 *   ① free 季报十大流通股东（nat/big/ss 分类）；
 *   ② lock 锁定通道 = 十大股东（RPT_F10_EH_HOLDERS）可见、十大流通榜**不可见**的持股
 *      ⇒ 限售/非流通（大基金定增锁定 18 个月、战投、发起人股）。**原口径硬缺口**：
 *      锁定股不进「十大流通股东」排名，季报流通通道最长漏 18 个月。
 *      注意：「退出」**不计分**（锁定股退出十大股东最常见是**解禁转流通**，非减持）；
 *   ③ ann 公告通道 = 股东增减持 + 定增获配（**T+1**，比季报快 1-3 个月）。窗口 365 天，
 *      窗口内计分并置 `annFresh=true`；超窗口仍保留 `annDir/annNotice` 供展示「时间点」。
 *
 * ★ 三条硬约束（research 文档 §7.4「实盘红线」，实现层强制）：
 *   1) 身份**只能**来自法定披露（十大股东/十大流通股东/增减持·定增公告）；ETF 申赎、
 *      估算资金流不得计入本节点（ETF 另见 `national_etf_share` 节点）；
 *   2) 判定必须 NOTICE_DATE/公告日 ≤ 信号日（无未来函数，由 `_national_flow` 保证）；
 *   3) 季报滞后 1-3 月（公告通道 T+1）→ 仍属中长线定性，**非实时信号**。
 *
 * ★ 状态口径：`流入` / `流出` / `退出` / `持稳` / `无`。
 *   「退出」= 曾进前十大、现**连续 ≥2 期缺席**（每季披露，连续缺席即已退出）；
 *   否则会把 2015 年的一次新进一路带到 2026 年（实测 002371 国家队披露停在 2021Q1）。
 *
 * 数据资产：`data/_national_flow_hist.json`
 *   （由 `smalltools/_national_flow.py --build` 抓取并同步到 assets，双端同源）
 */

/** 国家队/大基金资产读取：external / files 覆盖优先（PC 推送最新），其次内置 assets。 */
private object NationalFlowStore {

    private var key: String? = null
    private var data: JSONObject? = null

    @Synchronized
    fun load(context: Context): JSONObject? {
        val asset = "data/_national_flow_hist.json"
        val name = asset.substringAfterLast('/')
        val files = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .map { File(it, "data/$name") }.filter { it.isFile }
            .ifEmpty {
                listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                    .map { File(it, name) }.filter { it.isFile }
            }
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
                    Log.w("NationalFlowStore", "解析失败 ${f.absolutePath}: ${e.message}")
                }
            }
        }
        if (key == "asset" && data != null) return data
        return try {
            val j = JSONObject(context.assets.open(asset).bufferedReader().use { it.readText() })
            key = "asset"; data = j; j
        } catch (e: Exception) {
            Log.w("NationalFlowStore", "内置资产读取失败($asset): ${e.message}")
            key = "asset"; data = null; null
        }
    }

    /** 当前时点状态快照（`snap`：code → {natState/bigState/ssState/flowScore/flowLabel/…}）。 */
    fun snap(context: Context): JSONObject? = load(context)?.optJSONObject("snap")
}

/**
 * ## national_flow：国家队（中央汇金/证金）+ 大基金（国家集成电路产业投资基金等）持有进出判定
 *
 * config：
 *   sourceNode  上游节点 id（缺省 n_holdings_top5；也接 n_industry_scan / n_inst_pool）
 *   mode        annotate（默认，只贴标签、保持上游顺序）/ filter（按下面门槛筛）
 *   requireNat  filter 下要求国家队「流入」
 *   requireBig  filter 下要求大基金「流入」
 *   minScore    filter 下要求 flowScore ≥（默认 50）
 *   excludeOut  filter 下剔除国家队「流出/退出」
 *   requireLock filter 下要求锁定通道「流入」（十大股东新增锁定持股=定增/战投在建仓）
 *   requireAnn  filter 下要求公告通道「流入」（近一年增减持/定增公告净买入）
 *   annInWindow requireAnn 时是否只认窗口内公告（默认 true，超窗口的历史公告不算买入）
 *   minDirect   filter 下要求 directScore ≥（默认 0=不过滤）
 *   maxRows     限行（默认 200）
 *
 * 输出：{as_of, judge_day, built, rule, states, n, available, counts{流入,流出,退出,持稳,无},
 *   counts_lock{…}, counts_ann{流入,流出,历史,无},
 *   rows:[…+natState/natStrong/natRatio/natChg/natEnd/natNotice/natNames,
 *         bigState/bigStrong/bigRatio/bigChg/bigEnd/bigNotice,
 *         ssState/ssRatio, flowScore, directScore,
 *         lockState/lockStrong/lockKind/lockRatio/lockEnd/lockNotice/lockTypes/lockNames,
 *         annState/annDir/annKind/annNotice/annFresh/annN/annNames/annDetail,
 *         flowLabel, semi]}
 */
class NationalFlowNode(
    private val sourceNode: String = "n_holdings_top5",
    private val mode: String = "annotate",
    private val requireNat: Boolean = false,
    private val requireBig: Boolean = false,
    private val minScore: Double = 50.0,
    private val excludeOut: Boolean = false,
    private val requireLock: Boolean = false,
    private val requireAnn: Boolean = false,
    private val annInWindow: Boolean = true,
    private val minDirect: Double = 0.0,
    private val maxRows: Int = 200
) : BaseNode<Any, JSONObject>("national_flow", "国家队/大基金持有进出", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val src = (if (sourceNode.isNotBlank()) context.stageOutputs[sourceNode] as? JSONObject else null)
            ?: (input as? JSONObject)
        val srcObj = src ?: JSONObject().put("rows", JSONArray())
        val srcRows = srcObj.optJSONArray("rows") ?: JSONArray()

        val snap = NationalFlowStore.snap(context.androidContext)
        if (snap == null || snap.length() == 0) {
            context.log(nodeId, "✗ 缺 data/_national_flow_hist.json（国家队/大基金数据资产）")
            val out = JSONObject(srcObj.toString())
                .put("available", false)
                .put("note", "缺 data/_national_flow_hist.json；先跑 smalltools/_national_flow.py --build")
            context.setStageOutput(nodeId, out)
            return out
        }

        val cnt = HashMap<String, Int>()
        val lkc = HashMap<String, Int>()
        val anc = HashMap<String, Int>()
        val rows = ArrayList<JSONObject>()
        for (i in 0 until srcRows.length()) {
            val r = srcRows.optJSONObject(i) ?: continue
            val row = JSONObject(r.toString())
            val c6 = EtfScreenMath.code6(row.optString("code"))
            val s = if (c6.length == 6) snap.optJSONObject(c6) else null
            val natState = s?.optString("natState").orEmpty().ifBlank { "无" }
            cnt[natState] = (cnt[natState] ?: 0) + 1
            val lockState = s?.optString("lockState").orEmpty().ifBlank { "无" }
            lkc[lockState] = (lkc[lockState] ?: 0) + 1
            val annSt0 = s?.optString("annState").orEmpty().ifBlank { "无" }
            val annFresh = s?.optBoolean("annFresh") ?: false
            val annKey = if (annSt0 != "无" && !annFresh) "历史" else annSt0
            anc[annKey] = (anc[annKey] ?: 0) + 1
            row.put("natState", natState)
            row.put("natStrong", s?.optBoolean("natStrong") ?: false)
            row.put("natRatio", s?.opt("natRatio") ?: JSONObject.NULL)
            row.put("natChg", s?.opt("natChg") ?: JSONObject.NULL)
            row.put("natEnd", s?.optString("natEnd").orEmpty())
            row.put("natNotice", s?.optString("natNotice").orEmpty())
            row.put("natNames", s?.optJSONArray("natNames") ?: JSONArray())
            row.put("bigState", s?.optString("bigState").orEmpty().ifBlank { "无" })
            row.put("bigStrong", s?.optBoolean("bigStrong") ?: false)
            row.put("bigRatio", s?.opt("bigRatio") ?: JSONObject.NULL)
            row.put("bigChg", s?.opt("bigChg") ?: JSONObject.NULL)
            row.put("bigEnd", s?.optString("bigEnd").orEmpty())
            row.put("bigNotice", s?.optString("bigNotice").orEmpty())
            row.put("ssState", s?.optString("ssState").orEmpty().ifBlank { "无" })
            row.put("ssRatio", s?.opt("ssRatio") ?: JSONObject.NULL)
            // ① 锁定通道（十大股东可见 / 流通榜不可见 = 限售，如大基金定增锁定中）
            row.put("lockState", lockState)
            row.put("lockStrong", s?.optBoolean("lockStrong") ?: false)
            row.put("lockKind", s?.optString("lockKind").orEmpty())
            row.put("lockRatio", s?.opt("lockRatio") ?: JSONObject.NULL)
            row.put("lockEnd", s?.optString("lockEnd").orEmpty())
            row.put("lockNotice", s?.optString("lockNotice").orEmpty())
            row.put("lockTypes", s?.optJSONArray("lockTypes") ?: JSONArray())
            row.put("lockNames", s?.optJSONArray("lockNames") ?: JSONArray())
            // ② 公告通道（股东增减持 / 定增获配，T+1）
            row.put("annState", annSt0)
            row.put("annDir", s?.optString("annDir").orEmpty())
            row.put("annKind", s?.optString("annKind").orEmpty())
            row.put("annRatio", s?.opt("annRatio") ?: JSONObject.NULL)
            row.put("annNotice", s?.optString("annNotice").orEmpty())
            row.put("annEnd", s?.optString("annEnd").orEmpty())
            row.put("annSrc", s?.optString("annSrc").orEmpty())
            row.put("annFresh", annFresh)
            row.put("annN", s?.optInt("annN", 0) ?: 0)
            row.put("annNames", s?.optJSONArray("annNames") ?: JSONArray())
            row.put("annDetail", s?.optJSONArray("annDetail") ?: JSONArray())
            row.put("flowScore", s?.optDouble("flowScore", 50.0) ?: 50.0)
            row.put("directScore", s?.optDouble("directScore", 50.0) ?: 50.0)
            row.put("flowLabel", s?.optString("flowLabel").orEmpty().ifBlank { "无披露" })
            row.put("semi", s?.optBoolean("semi") ?: false)
            rows.add(row)
        }

        val kept = if (!mode.equals("filter", ignoreCase = true)) rows else rows.filter { r ->
            (!requireNat || r.optString("natState") == "流入") &&
                (!requireBig || r.optString("bigState") == "流入") &&
                (!excludeOut || (r.optString("natState") != "流出" && r.optString("natState") != "退出")) &&
                (!requireLock || r.optString("lockState") == "流入") &&
                (!requireAnn || (r.optString("annState") == "流入" &&
                    (!annInWindow || r.optBoolean("annFresh")))) &&
                (minDirect <= 0.0 || r.optDouble("directScore", 50.0) >= minDirect) &&
                r.optDouble("flowScore", 50.0) >= minScore
        }

        val meta = NationalFlowStore.load(context.androidContext)
        val outArr = JSONArray()
        kept.take(maxRows.coerceAtLeast(1)).forEach { outArr.put(it) }
        val out = JSONObject(srcObj.toString())
            .put("as_of", srcObj.optString("as_of").ifBlank { context.tradeDate })
            .put("available", true)
            .put("judge_day", meta?.optString("judge_day").orEmpty())
            .put("built", meta?.optString("built").orEmpty())
            .put("rule", meta?.optString("rule").orEmpty())
            .put("states", meta?.optString("states").orEmpty())
            .put("n", outArr.length())
            .put(
                "counts", JSONObject()
                    .put("流入", cnt["流入"] ?: 0).put("流出", cnt["流出"] ?: 0)
                    .put("退出", cnt["退出"] ?: 0).put("持稳", cnt["持稳"] ?: 0)
                    .put("无", cnt["无"] ?: 0)
            )
            .put(
                "counts_lock", JSONObject()
                    .put("流入", lkc["流入"] ?: 0).put("流出", lkc["流出"] ?: 0)
                    .put("退出", lkc["退出"] ?: 0).put("无", lkc["无"] ?: 0)
            )
            .put(
                "counts_ann", JSONObject()
                    .put("流入", anc["流入"] ?: 0).put("流出", anc["流出"] ?: 0)
                    .put("历史", anc["历史"] ?: 0).put("无", anc["无"] ?: 0)
            )
            .put("rows", outArr)
        context.setStageOutput(nodeId, out)
        context.log(
            nodeId, "🏛️ 国家队/大基金(${out.optString("judge_day")}): ${outArr.length()} 只 → " +
                "流入 ${cnt["流入"] ?: 0} / 流出 ${cnt["流出"] ?: 0} / " +
                "退出 ${cnt["退出"] ?: 0} / 持稳 ${cnt["持稳"] ?: 0} ｜ " +
                "锁定流入 ${lkc["流入"] ?: 0} ｜ 公告流入 ${anc["流入"] ?: 0}"
        )
        return out
    }
}
