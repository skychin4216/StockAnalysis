package com.chin.stockanalysis.strategy.topology.nodes

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * ## 机构持续加仓 usecase 的 APK 侧节点（2026-09-12 新增）
 *
 * 规则出处：用户《机构持续加仓自动选股池.txt》。与 Python 引擎
 * `app/src/main/assets/usecases/usecase_pipeline.py` 的 `inst_pool_build` / `inst_holding_judge`
 * 逐行同口径；参数全部走 pipeline XML（单一事实源 `inst_holding_pipeline.xml`）。
 *
 * 链：`inst_pool_build`（汇总三大周期选股 + ETF全行业扫描 + ETF top5 + 实仓）
 *     → `inst_holding_judge`（贴 A 精选 / B 观察 / C 散户票）
 *     → `stop_loss_vote`（target=picks 多理论投票止损，读行内嵌 bars）。
 *
 * ★ 判定算法**单一事实源** = `smalltools/_inst_holdings.py`（东财 RPT_MAIN_ORGHOLD 机构持股一览表 +
 *   RPT_HOLDERNUMLATEST 股东户数），资产 `_inst_holdings.json` 内已含 grade，双端只读不算，
 *   避免 Python / Kotlin / 推送表三处口径漂移。机构季报天然滞后 1-3 月，**只做中长线定性、非实时信号**。
 *
 * 数据资产：
 *   - `data/_inst_holdings.json`（每周由 `smalltools/_inst_holdings.py --update-pool` 刷新）
 *   - `data/_inst_pool.json`（推送时由 `smalltools/_publish_candidates.py` 写出当日选股池）
 */

/** 通用资产读取：external / files 覆盖优先（PC 推送最新），其次内置 assets。 */
private object InstAssetStore {

    private class Slot {
        var key: String? = null
        var data: JSONObject? = null
    }

    private val slots = HashMap<String, Slot>()

    @Synchronized
    fun load(context: Context, asset: String): JSONObject? {
        val slot = slots.getOrPut(asset) { Slot() }
        val name = asset.substringAfterLast('/')
        val files = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .map { File(it, "data/$name") }.filter { it.isFile }
            .ifEmpty { listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                .map { File(it, name) }.filter { it.isFile } }
        if (files.isNotEmpty()) {
            val key = "f|" + files.joinToString("|") { "${it.absolutePath}:${it.lastModified()}" }
            if (slot.key == key && slot.data != null) return slot.data
            for (f in files) {
                try {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        val j = JSONObject(text)
                        if (j.length() > 0) { slot.key = key; slot.data = j; return j }
                    }
                } catch (e: Exception) {
                    Log.w("InstAssetStore", "解析失败 ${f.absolutePath}: ${e.message}")
                }
            }
        }
        if (slot.key == "asset" && slot.data != null) return slot.data
        return try {
            val j = JSONObject(context.assets.open(asset).bufferedReader().use { it.readText() })
            slot.key = "asset"; slot.data = j; j
        } catch (e: Exception) {
            Log.w("InstAssetStore", "内置资产读取失败($asset): ${e.message}")
            slot.key = "asset"; slot.data = null; null
        }
    }
}

/** 机构持仓判定快照（`data/_inst_holdings.json`）。 */
object InstHoldingsStore {
    fun load(context: Context): JSONObject? =
        InstAssetStore.load(context, "data/_inst_holdings.json")

    /**
     * 单只股票的「机构股」标签：A·机构加仓 / B·机构参与 / C·散户票；无季报数据返回 "—"。
     * 供 APK 技术假设表（[com.chin.stockanalysis.strategy.trade.PickTechTable]）内联展示，
     * 与 PC 推送表 `_publish_candidates._inst_cell` 同口径（同一份 `_inst_holdings.json`）。
     */
    fun gradeLabel(context: Context, code: String): String {
        val c6 = EtfScreenMath.code6(code)
        if (c6.length != 6) return "—"
        return try {
            val st = load(context)?.optJSONObject("stocks")?.optJSONObject(c6) ?: return "—"
            InstGrade.LABEL[st.optString("grade")] ?: "—"
        } catch (_: Exception) {
            "—"
        }
    }
}

/** 当日选股池（`data/_inst_pool.json`，推送时由 PC 写出）。 */
object InstPoolStore {
    fun load(context: Context): JSONObject? =
        InstAssetStore.load(context, "data/_inst_pool.json")
}

/** 机构判定分级文案（与 Python `_INST_GRADE_LABEL` / `_INST_GRADE_VERDICT` 一致）。 */
internal object InstGrade {
    val LABEL = mapOf("A" to "A·机构加仓", "B" to "B·机构参与", "C" to "C·散户票")
    val VERDICT = mapOf(
        "A" to "机构真加仓·强趋势", "B" to "机构温和参与·观察", "C" to "机构撤退/散户票"
    )
}

/**
 * ## inst_pool_build：选股池 + 实仓汇总（机构判定链入口）
 *
 * 汇总口径（按 6 位码去重，`from` 记录命中来源，多来源用 + 连接）：
 *   ① `data/_inst_pool.json` —— 推送时由 `_publish_candidates` 写出的当日真实选股池（最完整）；
 *   ② 本次会话已 stage 的各链输出（ETF 全行业扫描 / ETF top5 / ETF 信号 / 三周期选股）；
 *   ③ RealPositionDao 实仓。
 *
 * config：includeHoldings=true、maxRows=200、embedBars=true、barsLimit=140。
 * 输出：{as_of, n, rows:[{code,name,from,entry,bars?}], source, n_bars}
 */
class InstPoolBuildNode(
    private val includeHoldings: Boolean = true,
    private val maxRows: Int = 200,
    private val embedBars: Boolean = true,
    private val barsLimit: Int = 140
) : BaseNode<Any, JSONObject>("inst_pool_build", "选股池+实仓汇总", NodeType.DATA_SOURCE) {

    companion object {
        private const val TAG = "InstPoolBuild"
        /** 上游链 → 来源标签（与 Python `_INST_POOL_SRC` 同口径）。 */
        private val SRC = listOf(
            "n_industry_scan" to "ETF全行业扫描", "n_holdings_top5" to "ETFtop5",
            "n_holdings_rank" to "ETFtop5", "n_etf_signal" to "ETF信号",
            "n_etf_gate" to "ETF信号", "n_ai" to "三周期选股", "n_strict" to "三周期选股"
        )
    }

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject =
        withContext(Dispatchers.IO) {
            val rows = ArrayList<JSONObject>()
            val idx = HashMap<String, JSONObject>()

            fun add(codeIn: String?, nameIn: String?, src: String?, entryIn: Double) {
                val c6 = EtfScreenMath.code6(codeIn ?: "")
                if (c6.length != 6 || !c6.all { it.isDigit() }) return
                val exist = idx[c6]
                if (exist == null) {
                    val r = JSONObject().put("code", c6).put("name", nameIn ?: "")
                        .put("from", src ?: "").put("entry", entryIn)
                    idx[c6] = r
                    rows.add(r)
                    return
                }
                if (!src.isNullOrBlank() && !exist.optString("from").contains(src)) {
                    val f = exist.optString("from")
                    exist.put("from", if (f.isEmpty()) src else "$f+$src")
                }
                if (!nameIn.isNullOrBlank() && exist.optString("name").isEmpty()) exist.put("name", nameIn)
                if (entryIn > 0 && exist.optDouble("entry", 0.0) <= 0.0) exist.put("entry", entryIn)
            }

            // ① 推送落盘选股池（最完整）
            val pool = InstPoolStore.load(context.androidContext)
            val poolRows = pool?.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until poolRows.length()) {
                val r = poolRows.optJSONObject(i) ?: continue
                add(r.optString("code"), r.optString("name"),
                    r.optString("from").ifBlank { "选股池" }, r.optDouble("entry", 0.0))
            }
            // ② 本次会话各链 stage 输出
            for ((nid, label) in SRC) {
                val o = context.stageOutputs[nid] as? JSONObject ?: continue
                val arr = o.optJSONArray("rows") ?: continue
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val entry = if (r.has("entry")) r.optDouble("entry", 0.0)
                    else if (r.has("cost")) r.optDouble("cost", 0.0) else r.optDouble("close", 0.0)
                    add(r.optString("code").ifBlank { r.optString("secid") }, r.optString("name"),
                        label, entry)
                }
            }
            // ③ 实仓（RealPositionDao；策略持仓由 real_holding 链另行处理）
            if (includeHoldings) {
                try {
                    val db = StockDatabase.getInstance(context.androidContext)
                    for (p in db.realPositionDao().getAllActive()) {
                        add(p.stockCode, p.stockName, "实仓", p.avgBuyPrice)
                    }
                } catch (e: Exception) {
                    context.log(nodeId, "⚠ 读实仓失败: ${e.message}")
                }
            }

            val outRows = ArrayList<JSONObject>(rows)
            // ArrayList 副本：includeHoldings=false 时需按来源剔除「实仓」行（List 无 removeAll(predicate)）
            val trimmed = ArrayList(outRows.take(maxRows.coerceAtLeast(1)))
            var nBars = 0
            if (embedBars) {
                val db = try { StockDatabase.getInstance(context.androidContext) } catch (_: Exception) { null }
                for (r in trimmed) {
                    val snaps = if (db != null) instSnaps(db, r.optString("code"), max(barsLimit, 60) + 20) else null
                    if (snaps != null && snaps.length() >= 25) {
                        r.put("bars", EtfScreenMath.barsPack(snaps, barsLimit))
                        val last = snaps.optJSONObject(snaps.length() - 1)
                        if (last != null) {
                            r.put("close", last.optDouble("close", 0.0))
                            r.put("date", last.optString("date"))
                        }
                        nBars++
                    }
                }
            }
            if (!includeHoldings) trimmed.removeAll { it.optString("from").contains("实仓") }

            val arr = JSONArray()
            trimmed.forEach { arr.put(it) }
            val srcs = trimmed.flatMap { it.optString("from").split("+") }
                .filter { it.isNotBlank() }.distinct().sorted()
            val out = JSONObject()
                .put("as_of", context.tradeDate)
                .put("n", trimmed.size)
                .put("rows", arr)
                .put("source", "选股池(三周期+ETF全行业扫描+ETFtop5)+实仓")
                .put("n_bars", nBars)
            context.setStageOutput(nodeId, out)
            context.log(nodeId, "🏛️ 机构判定池: ${trimmed.size} 只（内嵌日K $nBars 只）｜来源: " +
                srcs.take(6).joinToString("/"))
            out
        }

    private suspend fun instSnaps(
        db: StockDatabase,
        code: String,
        cap: Int
    ): JSONArray? = try {
        val rows = db.dailySnapshotDao().getByCode(EtfScreenMath.prefixed(code), cap)
            .sortedBy { it.date }
        val arr = JSONArray()
        for (r in rows) {
            arr.put(JSONObject().put("date", r.date).put("open", r.open).put("high", r.high)
                .put("low", r.low).put("close", r.close).put("volume", r.volume))
        }
        arr
    } catch (_: Exception) {
        null
    }
}

/**
 * ## inst_holding_judge：机构持仓判定（A 精选 / B 观察 / C 散户票）
 *
 * 数据源：`data/_inst_holdings.json`（东财机构持股一览表 + 股东户数；每周 `_inst_holdings.py` 刷新）。
 * 判定口径（与 `smalltools/_inst_holdings.judge_series` 完全一致，资产内已含 grade）：
 *   口径A 连续 ≥2 期持股比例环比增持 >0.5pp；口径B 全程净增持 >1pp；
 *   score = 0.6×机构加仓分 + 0.4×集中度（股东户数下降加分）；
 *   A=口径A 且 ≥75 分｜B=(A或B) 且 ≥50 分｜C=其余（散户票）。
 *
 * config：sourceNode=n_inst_pool、gradeFilter=""（"A"/"AB" 可只看精选）、maxRows=200。
 * 输出：{as_of, n, a, b, c, period, available, rows:[…+grade/score/label/hold_ratio/latest_chg/
 *   streak/net_chg/n_funds/holder_chg_pct/instVerdict]}（按 A→B→C、分数降序）
 */
class InstHoldingJudgeNode(
    private val sourceNode: String = "n_inst_pool",
    private val gradeFilter: String = "",
    private val maxRows: Int = 200
) : BaseNode<Any, JSONObject>("inst_holding_judge", "机构持仓判定", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        val src = (if (sourceNode.isNotBlank()) context.stageOutputs[sourceNode] as? JSONObject else null)
            ?: (input as? JSONObject)
        val srcObj = src ?: JSONObject().put("rows", JSONArray())
        val srcRows = srcObj.optJSONArray("rows") ?: JSONArray()

        val d = InstHoldingsStore.load(context.androidContext)
        val stocks = d?.optJSONObject("stocks")
        if (stocks == null || stocks.length() == 0) {
            context.log(nodeId, "✗ 缺 data/_inst_holdings.json（机构持仓数据资产）")
            val out = JSONObject(srcObj.toString())
                .put("available", false)
                .put("note", "缺 data/_inst_holdings.json；先跑 smalltools/_inst_holdings.py")
            context.setStageOutput(nodeId, out)
            return out
        }

        val filter = gradeFilter.trim().uppercase(Locale.US)
        val cnt = HashMap<String, Int>()
        val rows = ArrayList<JSONObject>()
        for (i in 0 until srcRows.length()) {
            val r = srcRows.optJSONObject(i) ?: continue
            val row = JSONObject(r.toString())
            val v = stocks.optJSONObject(row.optString("code"))
            val grade = if (v != null && v.length() > 0) v.optString("grade") else ""
            if (grade.isEmpty()) {
                row.put("grade", "").put("label", "—").put("instVerdict", "无季报数据")
            } else {
                cnt[grade] = (cnt[grade] ?: 0) + 1
                row.put("grade", grade)
                row.put("score", v.opt("score") ?: JSONObject.NULL)
                row.put("hold_ratio", v.opt("hold_ratio") ?: JSONObject.NULL)
                row.put("latest_chg", v.opt("latest_chg") ?: JSONObject.NULL)
                row.put("streak", v.opt("streak") ?: JSONObject.NULL)
                row.put("net_chg", v.opt("net_chg") ?: JSONObject.NULL)
                row.put("n_funds", v.opt("n_funds") ?: JSONObject.NULL)
                row.put("holder_chg_pct", v.opt("holder_chg_pct") ?: JSONObject.NULL)
                row.put("label", InstGrade.LABEL[grade] ?: "—")
                row.put("instVerdict", InstGrade.VERDICT[grade] ?: "—")
            }
            rows.add(row)
        }
        val kept = ArrayList(
            if (filter.isEmpty()) rows
            else rows.filter { filter.contains(it.optString("grade")) }
        )
        kept.sortWith(Comparator { a, b ->
            val ra = if (a.optString("grade") == "A") 0 else if (a.optString("grade") == "B") 1 else 2
            val rb = if (b.optString("grade") == "A") 0 else if (b.optString("grade") == "B") 1 else 2
            if (ra != rb) ra - rb
            else b.optDouble("score", 0.0).compareTo(a.optDouble("score", 0.0))
        })
        val outArr = JSONArray()
        kept.take(maxRows.coerceAtLeast(1)).forEach { outArr.put(it) }
        val out = JSONObject(srcObj.toString())
            .put("as_of", srcObj.optString("as_of").ifBlank { context.tradeDate })
            .put("available", true)
            .put("period", d.optString("period"))
            .put("prev_period", d.optString("prev_period"))
            .put("rule", d.optString("rule"))
            .put("n", outArr.length())
            .put("a", cnt["A"] ?: 0).put("b", cnt["B"] ?: 0).put("c", cnt["C"] ?: 0)
            .put("rows", outArr)
        context.setStageOutput(nodeId, out)
        context.log(nodeId, "🏛️ 机构判定(${d.optString("period")}): ${outArr.length()} 只 → " +
            "A ${cnt["A"] ?: 0} / B ${cnt["B"] ?: 0} / C ${cnt["C"] ?: 0}")
        return out
    }
}
