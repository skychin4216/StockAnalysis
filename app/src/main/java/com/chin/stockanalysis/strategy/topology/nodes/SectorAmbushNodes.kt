package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * ## 热门板块「埋伏」节点（双端之一：APK 侧）
 *
 * 规则/参数在 assets/usecases/sector_ambush_pipeline.xml，与 AutoQuant Python 引擎共用同一 XML
 * （单一事实源）。设计参考公开的行业轮动 + 主力吸筹形态研究：
 *   ① 先选板块再选个股：行业/题材轮动是 A 股最稳定的规律之一（资金从弱板块流向强板块）；
 *   ② 启动形态：连续 2~3 根阳线（刚启动，不追已连涨多日的高位票）；
 *   ③ 趋势确认：close > MA5 > MA10 > MA20 且 MA20 抬升；
 *   ④ 主力埋伏三证据：梯量温和放量（既非无量也非天量出货）、OBV 上行、启动前缩量洗盘不破 MA20；
 *   ⑤ 位置不高：距 60 日高点回撤 -25% ~ -1%（既不破位也不追高）。
 *
 * - n_ambush_signal  JSONObject {as_of, hotSectors[], rows[], scanned}
 * - n_ambush_exit    JSONObject {as_of, hotSectors, signal_today, scanned, strategy, exit{tp,sl,hold}}
 */
private const val AMB_TAG = "SectorAmbush"

/** 热门板块统计（节点内部使用） */
private data class AmbSector(
    val code: String,
    val name: String,
    val hotDays: Int,
    val inflow: Double,
    val avgChg: Double,
    val score: Double
)

/** 阶段1：热门板块埋伏信号扫描（universe=hot_sector|etf，2026-09-17） */
class SectorAmbushSignalNode(
    private val topSectors: Int = 10,
    /** 2026-09-18 用户需求：叠加「资金净流入远超流出」板块数（净流入>0 按净额降序）。 */
    private val inflowSectors: Int = 10,
    private val bullMin: Int = 2,
    private val bullMax: Int = 3,
    private val maShort: Int = 5,
    private val maMid: Int = 10,
    private val maLong: Int = 20,
    private val volExpand: Double = 1.2,
    private val volExpandMax: Double = 3.0,
    private val requireObvUp: Boolean = true,
    private val requireWash: Boolean = true,
    private val ddWin: Int = 60,
    private val dd60Lo: Double = -25.0,
    private val dd60Hi: Double = -1.0,
    private val minSnapshots: Int = 60,
    private val scanCap: Int = 400,
    private val topN: Int = 10,
    private val lookbackDays: Int = 20,
    /** 候选池：hot_sector=板块热度Top5（默认）；etf=全部行业/主题ETF前五重仓 */
    private val universe: String = "hot_sector",
    private val industryOnly: Boolean = true,
    private val baseThemes: String = "宽基",
    private val excludeStar: Boolean = true,
    private val minETF: Int = 1
) : BaseNode<Any, JSONObject>("sector_ambush_signal", "热门板块埋伏信号扫描", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject {
        if (universe.equals("etf", ignoreCase = true)) {
            return executeEtf(context)
        }
        val db = StockDatabase.getInstance(context.androidContext)
        val out = JSONObject().put("as_of", "").put("rows", JSONArray())
            .put("scanned", 0).put("hotSectors", JSONArray())

        // ── ① 热门板块：读 sector_daily_record 近 lookbackDays 日 ──
        val records = try {
            db.sectorDailyRecordDao().getRecentDays(lookbackDays)
        } catch (e: Exception) {
            Log.w(AMB_TAG, "读取板块记录失败: ${e.message}")
            emptyList()
        }
        if (records.isEmpty()) {
            context.log(nodeId, "⚠️ 无板块历史数据（sector_daily_record 为空），无法定位热门板块，跳过埋伏扫描")
            return out
        }
        val ranked = records.groupBy { it.sectorCode }.map { (code, recs) ->
            val name = recs.firstOrNull()?.sectorName ?: code
            val hotDays = recs.count { it.isHot == "S" || it.isHot == "A" }
            val inflow = recs.sumOf { it.mainNetInflow }
            val avgChg = recs.map { it.changePct }.average()
            val comp = recs.map { it.compositeScore }.maxOrNull() ?: 0.0
            AmbSector(code, name, hotDays, inflow, avgChg,
                hotDays * 3.0 + comp + inflow / 1e8 + avgChg)
        }.sortedByDescending { it.score }
        // 2026-09-18 用户需求：10 个热门（动量榜）+ 10 个资金净流入（净流入>0 降序）并集去重
        val uniq = LinkedHashMap<String, AmbSector>()
        ranked.take(topSectors).forEach { uniq[it.code] = it }
        if (inflowSectors > 0) {
            ranked.filter { it.inflow > 0 }.sortedByDescending { it.inflow }
                .take(inflowSectors).forEach { uniq.putIfAbsent(it.code, it) }
        }
        val sectors = uniq.values.toList()

        // ── ② 板块 → 成分股（sector_stocks；key 不匹配时按板块名回退） ──
        val pairs = try {
            db.sectorStockDao().getAllStockSectorPairs()
        } catch (e: Exception) {
            Log.w(AMB_TAG, "读取板块成分失败: ${e.message}")
            emptyList()
        }
        val byName = pairs.groupBy { it.sector_name }
        val stockSector = HashMap<String, String>()   // 代码(原样/裸码) -> 首个所属热门板块名
        val mergeOrder = LinkedHashSet<String>()
        val sectorRank = HashMap<String, Int>()
        sectors.forEachIndexed { idx, s ->
            sectorRank[s.name] = idx + 1
            val exact = try {
                db.sectorStockDao().getStockCodesBySector(s.code)
            } catch (_: Exception) {
                emptyList()
            }
            val list = if (exact.isNotEmpty()) exact
            else byName[s.name]?.map { it.stock_code } ?: emptyList()
            for (c in list) {
                if (c.isBlank()) continue
                mergeOrder.add(c)
                stockSector.putIfAbsent(c, s.name)
                stockSector.putIfAbsent(c.replace(Regex("^(sh|sz|bj)"), ""), s.name)
            }
        }
        if (mergeOrder.isEmpty()) {
            context.log(nodeId, "⚠️ 热门板块(${sectors.joinToString("、") { it.name }}) 无成分股数据（sector_stocks 为空），跳过")
            return out
        }

        // ── ③ 逐只评估 ──
        val rows = ArrayList<JSONObject>()
        var scanned = 0
        var asOf = ""
        for (raw in mergeOrder) {
            if (scanned >= scanCap) break
            val bare = raw.replace(Regex("^(sh|sz|bj)"), "")
            if (raw.startsWith("sh000") || raw.startsWith("sz399") ||
                raw.startsWith("sh880") || raw.startsWith("bj")
            ) continue
            scanned++
            val hist = loadHist(db, raw, bare)
            if (hist.size < minSnapshots + maLong) continue
            val name = hist.last().name.ifBlank { bare }
            if (name.contains("ST") || name.contains("退")) continue
            val secName = stockSector[raw] ?: stockSector[bare] ?: ""
            val row = evalStock(bare, name, secName, hist) ?: continue
            // 板块热度加分：越靠前的热门板块，其成分股加分越多
            val bonus = (sectors.size - (sectorRank[secName] ?: sectors.size) + 1).toDouble()
            row.put("score", round2(row.optDouble("score", 0.0) + bonus))
            val d = row.optString("date")
            if (d > asOf) asOf = d
            rows.add(row)
        }

        val top = rows.sortedByDescending { it.optDouble("score", 0.0) }.take(topN)
        val arr = JSONArray()
        top.forEach { arr.put(it) }
        out.put("as_of", asOf).put("rows", arr).put("scanned", scanned)
        out.put("hotSectors", JSONArray(sectors.map { it.name }))

        val sb = StringBuilder()
        sb.append("🎯 热门板块埋伏（近${lookbackDays}日）：")
        sb.append(sectors.joinToString("、") { "${it.name}(热度${it.hotDays}天/资金${"%.1f".format(it.inflow / 1e8)}亿)" })
        sb.append("\n   扫描 ${scanned} 只 → 命中 ${top.size} 只")
        top.take(6).forEach { r ->
            sb.append("\n   · ${r.optString("name")}(${r.optString("code")}) [${r.optString("sector")}] " +
                "连阳${r.optInt("bull")} 量比${r.optDouble("vr")} 距60高${"%.1f".format(r.optDouble("dd60"))}% " +
                "OBV${if (r.optBoolean("obvUp")) "↑" else "→"} 洗盘${if (r.optBoolean("wash")) "✓" else "-"} " +
                "分${"%.1f".format(r.optDouble("score"))}")
        }
        context.log(nodeId, sb.toString())
        context.recordStockFlow(
            nodeId = nodeId, nodeName = nodeName,
            inputCount = mergeOrder.size, outputCount = top.size,
            filterCount = mergeOrder.size - top.size,
            filterReason = "热门板块Top$topSectors → 2~${bullMax}连阳+多头+主力埋伏(梯量/OBV/洗盘)",
            inputCodes = mergeOrder.take(5).toList(),
            outputCodes = top.take(5).map { it.optString("code") }
        )
        return out
    }

    // ══════════ ETF 全景埋伏（universe=etf，2026-09-17）══════════

    /** 全部行业/主题 ETF 前五重仓股 → 埋伏信号扫描（候选与 etf_industry_scan 同源）。 */
    private suspend fun executeEtf(context: PipelineContext): JSONObject {
        val db = StockDatabase.getInstance(context.androidContext)
        val out = JSONObject().put("as_of", "").put("rows", JSONArray())
            .put("scanned", 0).put("hotSectors", JSONArray())

        val d = EtfHoldingsStore.load(context.androidContext)
        if (d == null) {
            context.log(nodeId, "⚠️ 缺 _etf_holdings.json（ETF 全景埋伏数据资产），跳过")
            return out
        }
        val base = baseThemes.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()

        // ① 行业/主题 ETF → 前五重仓候选 + 归属主题/ETF 列表
        data class Meta(
            val name: String,
            val themes: LinkedHashSet<String>,
            val etfs: LinkedHashSet<String>
        )
        val cand = LinkedHashMap<String, Meta>()
        val themeCodes = HashMap<String, MutableList<String>>()
        val fundArr = d.optJSONArray("funds") ?: JSONArray()
        for (i in 0 until fundArr.length()) {
            val f = fundArr.optJSONObject(i) ?: continue
            val theme = f.optString("theme")
            if (industryOnly && theme in base) continue
            val top = f.optJSONArray("top") ?: continue
            for (k in 0 until top.length()) {
                val s = top.optJSONObject(k) ?: continue
                val c6 = EtfScreenMath.code6(s.optString("code"))
                if (c6.isEmpty()) continue
                if (excludeStar && (c6.startsWith("68") || c6.startsWith("69"))) continue
                val e = cand.getOrPut(c6) {
                    Meta(s.optString("name"), LinkedHashSet(), LinkedHashSet())
                }
                if (theme.isNotBlank()) {
                    e.themes.add(theme)
                    themeCodes.getOrPut(theme) { mutableListOf() }.add(c6)
                }
                e.etfs.add(f.optString("name").ifBlank { f.optString("code") })
            }
        }
        val universeMap = cand.filterValues { it.etfs.size >= minETF }
        if (universeMap.isEmpty()) {
            context.log(nodeId, "⚠️ ETF 持仓矩阵无候选（industryOnly=$industryOnly），跳过")
            return out
        }

        // ② 逐只取日K（StockDatabase → EtfCacheStore 兜底），并算近10日收益给主题动量
        val histOf = HashMap<String, List<DailySnapshotEntity>>()
        for (c6 in universeMap.keys) {
            val h = loadHist(db, c6, c6)
            histOf[c6] = if (h.size >= minSnapshots + maLong) h else etfCacheHist(context, c6)
        }
        fun ret10(c6: String): Double? {
            val h = histOf[c6] ?: return null
            if (h.size < 11) return null
            val c0 = h[h.size - 11].close
            return if (c0 > 0) (h.last().close / c0 - 1) * 100 else null
        }
        val ranked = ArrayList<Pair<String, Double>>()
        for ((theme, codes) in themeCodes) {
            val rs = codes.distinct().mapNotNull { ret10(it) }
            if (rs.isNotEmpty()) ranked.add(theme to rs.average())
        }
        ranked.sortByDescending { it.second }
        val hot = ranked.take(topSectors)
        val rankOf = HashMap<String, Int>()
        hot.forEachIndexed { idx, p -> rankOf[p.first] = idx + 1 }

        // ③ 逐只评估（全部候选；热门主题排名加分，扫描不限热门主题）
        val rows = ArrayList<JSONObject>()
        var scanned = 0
        var asOf = ""
        for ((c6, meta) in universeMap) {
            if (scanned >= scanCap) break
            val hist = histOf[c6] ?: continue
            if (hist.size < minSnapshots + maLong) continue
            scanned++
            if (meta.name.contains("ST") || meta.name.contains("退")) continue
            val bestRank = meta.themes.minOfOrNull { rankOf[it] ?: (topSectors + 1) }
                ?: (topSectors + 1)
            val secName = meta.themes.take(2).joinToString("/").ifBlank { "ETF重仓" }
            val row = evalStock(c6, meta.name, secName, hist) ?: continue
            if (bestRank <= topSectors) {
                row.put("score", round2(row.optDouble("score", 0.0) + (topSectors - bestRank + 1)))
            }
            row.put("etfs", meta.etfs.size)
            val dd = row.optString("date")
            if (dd > asOf) asOf = dd
            rows.add(row)
        }
        val top = rows.sortedByDescending { it.optDouble("score", 0.0) }.take(topN)
        val arr = JSONArray()
        top.forEach { arr.put(it) }
        out.put("as_of", asOf).put("rows", arr).put("scanned", scanned)
            .put("hotSectors", JSONArray(hot.map { it.first }))
            .put("universe", universeMap.size)

        context.log(nodeId, "🔭 ETF全景埋伏：候选 ${universeMap.size} 只(主题 ${themeCodes.size} 个) | " +
                "热门主题：${hot.joinToString("、") { "${it.first}(近10日${"%.1f".format(it.second)}%)" }} | " +
                "扫描 $scanned 只 → 命中 ${top.size} 只")
        return out
    }

    /** EtfCacheStore 日K(JSON) → DailySnapshotEntity 兜底（ETF 重仓股不在 StockDatabase 时）。 */
    private fun etfCacheHist(context: PipelineContext, c6: String): List<DailySnapshotEntity> {
        val snaps = EtfCacheStore.load(context.androidContext)
            ?.optJSONObject(EtfScreenMath.prefixed(c6))?.optJSONArray("snaps") ?: return emptyList()
        val out = ArrayList<DailySnapshotEntity>(snaps.length())
        for (i in 0 until snaps.length()) {
            val s = snaps.optJSONObject(i) ?: continue
            out.add(
                DailySnapshotEntity(
                    code = EtfScreenMath.prefixed(c6), name = s.optString("name"),
                    date = s.optString("date"),
                    open = s.optDouble("open"), close = s.optDouble("close"),
                    high = s.optDouble("high"), low = s.optDouble("low"),
                    volume = s.optDouble("volume").toLong(), amount = s.optDouble("amount"),
                    changePct = s.optDouble("changePct")
                )
            )
        }
        return out
    }

    /** 读日K：依次尝试「原样 / 裸码 / 加前缀」，兼容不同代码格式 */
    private suspend fun loadHist(
        db: StockDatabase, raw: String, bare: String
    ): List<DailySnapshotEntity> {
        val limit = minSnapshots + maLong + 40
        val prefix = if (bare.startsWith("6")) "sh$bare" else "sz$bare"
        for (c in listOf(raw, bare, prefix)) {
            val h = try {
                db.dailySnapshotDao().getByCode(c, limit).sortedBy { it.date }
            } catch (_: Exception) {
                emptyList()
            }
            if (h.size >= minSnapshots + maLong) return h
        }
        return emptyList()
    }

    /** 单只个股埋伏信号评估；不满足任一条返回 null */
    private fun evalStock(
        code: String, name: String, sectorName: String, hist: List<DailySnapshotEntity>
    ): JSONObject? {
        val n = hist.size
        if (n < minSnapshots + maLong) return null
        val closes = DoubleArray(n) { hist[it].close }
        val vols = LongArray(n) { hist[it].volume ?: 0L }
        val chgs = DoubleArray(n) { hist[it].changePct ?: 0.0 }
        val last = n - 1

        // ① 连续阳线根数（自末根往前，changePct > 0）
        var bull = 0
        var j = last
        while (j >= 0 && chgs[j] > 0) { bull++; j-- }
        if (bull < bullMin || bull > bullMax) return null

        fun ma(idx: Int, win: Int): Double {
            if (idx < win - 1) return Double.NaN
            var s = 0.0
            for (k in idx - win + 1..idx) s += closes[k]
            return s / win
        }

        // ② 均线多头排列 + MA20 抬升
        if (last < maLong + 5) return null
        val ma5 = ma(last, maShort)
        val ma10 = ma(last, maMid)
        val ma20 = ma(last, maLong)
        val ma20Prev = ma(last - 5, maLong)
        if (ma5.isNaN() || ma10.isNaN() || ma20.isNaN() || ma20Prev.isNaN()) return null
        if (!(closes[last] > ma5 && ma5 > ma10 && ma10 > ma20)) return null
        if (ma20 <= ma20Prev) return null

        // ③ 位置：距 ddWin 日高点回撤
        val hi = (maxOf(0, last - ddWin + 1)..last).maxOf { closes[it] }
        if (hi <= 0.0) return null
        val dd = (closes[last] / hi - 1.0) * 100.0
        if (dd < dd60Lo || dd > dd60Hi) return null

        // ④ 主力埋伏①：梯量/温和放量（阳线段均量 / 前 10 日均量）
        if (vols[last] <= 0L) return null
        val bullLo = last - bull + 1
        val baseLo = maxOf(0, bullLo - 10)
        val vBull = avgVol(vols, bullLo, last + 1)
        val vBase = avgVol(vols, baseLo, bullLo)
        if (vBull <= 0.0 || vBase <= 0.0) return null
        val vr = vBull / vBase
        if (vr < volExpand || vr > volExpandMax) return null
        // 阶梯式放大：阳线段内量能基本不递减（允许 5% 容差）
        var ladder = true
        for (k in bullLo + 1..last) {
            if (vols[k] < vols[k - 1] * 0.95) { ladder = false; break }
        }
        if (!ladder) return null

        // ⑤ 主力埋伏②：OBV 上行（量价累积资金流）
        val obvArr = DoubleArray(n)
        var obv = 0.0
        for (k in 1 until n) {
            obv += (if (chgs[k] > 0) 1.0 else if (chgs[k] < 0) -1.0 else 0.0) * vols[k]
            obvArr[k] = obv
        }
        if (last < 5) return null
        val obvUp = obvArr[last] > obvArr[last - 5]
        if (requireObvUp && !obvUp) return null

        // ⑥ 主力埋伏③：启动前缩量洗盘且不破 MA20
        var wash = false
        if (requireWash) {
            val wHi = bullLo - 1
            val wLo = maxOf(maLong, wHi - 10)
            if (wHi - wLo >= 3) {
                var shrink = false
                var hold = true
                for (k in wLo..wHi) {
                    val v20 = avgVol(vols, maxOf(0, k - 20), k)
                    if (v20 > 0.0 && vols[k] < v20 * 0.9) shrink = true
                    val mk = ma(k, maLong)
                    if (!mk.isNaN() && closes[k] < mk * 0.97) hold = false
                }
                wash = shrink && hold
            }
            if (!wash) return null
        }

        // 评分：连阳根数 + 量能放大 + OBV + 洗盘 + 位置合适度（越接近 -8% 回撤越好）
        val posScore = maxOf(0.0, 1.0 - abs(dd + 8.0) / 17.0) * 2.0
        val score = bull * 2.0 + vr * 1.5 +
            (if (obvUp) 1.5 else 0.0) + (if (wash) 1.0 else 0.0) + posScore

        return JSONObject()
            .put("code", code).put("name", name).put("sector", sectorName)
            .put("date", hist[last].date).put("close", round2(closes[last]))
            .put("bull", bull).put("vr", round2(vr)).put("dd60", round2(dd))
            .put("obvUp", obvUp).put("wash", wash)
            .put("score", round2(score))
    }

    private fun avgVol(vols: LongArray, lo: Int, hi: Int): Double {
        var sum = 0.0
        var cnt = 0
        for (k in maxOf(0, lo) until minOf(hi, vols.size)) {
            if (vols[k] > 0L) { sum += vols[k].toDouble(); cnt++ }
        }
        return if (cnt > 0) sum / cnt else 0.0
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}

/** 阶段2：离场参数 + 发布口径打包（埋伏型 → 中线持有） */
class AmbushExitPolicyNode(
    private val tp: Double = 8.0,
    private val sl: Double = -5.0,
    private val hold: Int = 10,
    private val strategyText: String = ""
) : BaseNode<JSONObject, JSONObject>("ambush_exit_policy", "埋伏离场打包", NodeType.TRADE_ACTION) {

    override suspend fun execute(context: PipelineContext, input: JSONObject): JSONObject {
        val sig = input ?: JSONObject()
        val rows = sig.optJSONArray("rows") ?: JSONArray()
        val strategy = strategyText.ifBlank {
            "热门板块埋伏: 板块热度Top5 → 2~3连阳 + close>MA5>MA10>MA20且MA20抬升 + " +
                "梯量温和放量(1.2~3倍) + OBV上行 + 启动前缩量洗盘不破MA20 + 距60日高回撤-25%~-1%；持≤${hold}日"
        }
        val out = JSONObject()
            .put("as_of", sig.optString("as_of", ""))
            .put("hotSectors", sig.optJSONArray("hotSectors") ?: JSONArray())
            .put("signal_today", rows)
            .put("scanned", sig.optInt("scanned", 0))
            .put("strategy", strategy)
            .put("exit", JSONObject().put("tp", tp).put("sl", sl).put("hold", hold))
        context.log(nodeId, "📦 sector_ambush 打包完成: ${rows.length()} 只候选 (tp+$tp%/sl$sl%/持${hold}日)")
        return out
    }
}
