package com.chin.stockanalysis.strategy.topology.nodes

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.TechTags
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.pipelines.TrendClassGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * ## 三个独立 ETF usecase 的 APK 侧节点（2026-09-12 新增）
 *
 * 与 Python 引擎 `app/src/main/assets/usecases/usecase_pipeline.py` 逐行同口径，
 * 参数全部走 pipeline XML（单一事实源），任何一端改 XML 双端自动一致：
 *
 * | module            | usecase            | 入口        | 输出                       |
 * |-------------------|--------------------|-------------|----------------------------|
 * | etf_holdings_rank | etf_holdings_top5  | 覆盖矩阵    | 覆盖度排行（个股观察清单） |
 * | etf_industry_scan | etf_industry_scan  | 行业/主题   | 前五重仓个股低吸 topN      |
 * | etf_pure_screen   | etf_pure_screen    | ETF 本体    | 底仓/做T 信号             |
 *
 * **为何拆三个（勿合并）**：入口不同（板块 / 覆盖矩阵 / ETF 本体）、输出表不同
 * （个股低吸 / 覆盖排行 / ETF 信号）、炒作生命周期不同（前两者炒个股、第三者炒 ETF 本体做 T 降本）。
 *
 * **大盘要不要考虑？** 要（回答用户《ETF选股思路.txt》的疑问）：
 *   - etf_industry_scan / etf_pure_screen 均内置**三态自适应**（节点内 auto 判定，取自沪深300）：
 *     BULLISH 正常/放宽 · OSCILLATION 提门槛并只接更深回撤 · BEARISH 暂停开仓仅观察；
 *   - etf_holdings_rank 是**覆盖度排行榜**（观察清单），不做大盘择时。
 *
 * 数据资产：`assets/data/_etf_holdings.json`（季报级 funds/stocks 覆盖矩阵，PC 生成）。
 */
object EtfHoldingsStore {
    private const val ASSET = "data/_etf_holdings.json"
    private const val FILE_NAME = "_etf_holdings.json"

    @Volatile private var cachedKey: String? = null
    @Volatile private var cached: JSONObject? = null

    /** external/internal 覆盖（PC 推送最新）优先，其次内置 assets。 */
    @Synchronized
    fun load(context: Context): JSONObject? {
        val files = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .map { File(it, FILE_NAME) }.filter { it.isFile }
        if (files.isNotEmpty()) {
            val key = "f|" + files.joinToString("|") { "${it.absolutePath}:${it.lastModified()}" }
            if (cachedKey == key && cached != null) return cached
            for (f in files) {
                try {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        val j = JSONObject(text)
                        if (j.length() > 0) { cachedKey = key; cached = j; return j }
                    }
                } catch (e: Exception) {
                    Log.w("EtfHoldingsStore", "解析失败 ${f.absolutePath}: ${e.message}")
                }
            }
        }
        if (cachedKey == "asset" && cached != null) return cached
        return try {
            val j = JSONObject(context.assets.open(ASSET).bufferedReader().use { it.readText() })
            cachedKey = "asset"; cached = j; j
        } catch (e: Exception) {
            Log.w("EtfHoldingsStore", "内置资产读取失败: ${e.message}")
            cachedKey = "asset"; cached = null; null
        }
    }
}

/** ETF 三节点共用数学/数据工具（与 Python 端逐行同口径）。 */
object EtfScreenMath {

    fun code6(code: String): String {
        val c = code.trim().lowercase(Locale.US)
        return if (c.length > 2 && (c.startsWith("sh") || c.startsWith("sz") || c.startsWith("bj")))
            c.substring(2) else c
    }

    fun prefixed(code: String): String {
        val c = code6(code)
        if (c.isEmpty()) return c
        return when (c[0]) {
            '6', '9' -> "sh$c"
            '0', '3' -> "sz$c"
            else -> c
        }
    }

    /** 大盘三态（沪深300 close 与 MA20/MA60，与 ctx.market / n_market 同口径）。 */
    fun marketState(cache: JSONObject?, indexCode: String = "sh000300"): String {
        val snaps = cache?.optJSONObject(indexCode)?.optJSONArray("snaps") ?: return "OSCILLATION"
        val closes = (0 until snaps.length()).mapNotNull { i ->
            snaps.optJSONObject(i)?.optDouble("close", Double.NaN)?.takeIf { !it.isNaN() }
        }
        if (closes.size < 60) return "OSCILLATION"
        val c = closes.last()
        val ma20 = closes.takeLast(20).average()
        val ma60 = closes.takeLast(60).average()
        return when {
            c > ma20 && ma20 > ma60 -> "BULLISH"
            c < ma20 && ma20 < ma60 -> "BEARISH"
            else -> "OSCILLATION"
        }
    }

    /** SAR 状态 → (dir, bars, freshUp)。 */
    fun sarState(closes: List<Double>, highs: List<Double>, lows: List<Double>): Triple<String, Int, Boolean> {
        val n = closes.size
        if (n < 3) return Triple("", 0, false)
        val sar = TechTags.parabolicSar(highs, lows, closes)
        var i = n - 1
        while (i >= 0 && sar[i] == null) i--
        if (i < 0) return Triple("", 0, false)
        val dirs = ArrayList<String>()
        var j = i
        while (j >= 0) {
            val sv = sar[j] ?: break
            dirs.add(if (closes[j] >= sv) "UP" else "DOWN")
            j--
        }
        if (dirs.isEmpty()) return Triple("", 0, false)
        val cur = dirs[0]
        var bars = 0
        for (d in dirs) { if (d == cur) bars++ else break }
        val freshUp = cur == "UP" && dirs.size > bars && dirs[bars] == "DOWN" && bars <= 3
        return Triple(cur, bars, freshUp)
    }

    /** 最小二乘斜率（x=0..n-1，与 np.polyfit(deg=1) 同解）。 */
    fun lsqSlope(vals: List<Double>): Double {
        val n = vals.size
        if (n < 2) return 0.0
        val xm = (n - 1) / 2.0
        val ym = vals.sum() / n
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (i - xm) * (vals[i] - ym); den += (i - xm) * (i - xm) }
        return if (den != 0.0) num / den else 0.0
    }

    /** RAS 相对强度(ETF/沪深300) 的 win 日滚动斜率序列。 */
    fun rasSlopeSeries(etfCloses: List<Double>, benchCloses: List<Double>, win: Int): List<Double> {
        val m = min(etfCloses.size, benchCloses.size)
        if (m < win + 1) return emptyList()
        val ec = etfCloses.takeLast(m); val bc = benchCloses.takeLast(m)
        val rs = (0 until m).map { if (bc[it] != 0.0) ec[it] / bc[it] else 0.0 }
        val out = ArrayList<Double>()
        for (i in win - 1 until m) out.add(lsqSlope(rs.subList(i - win + 1, i + 1)))
        return out
    }

    /**
     * 低吸打分（自包含，与 Python `_etf_pick_score` 逐项一致）。
     * 硬门控：SAR 绿（下跌趋势）直接排除。返回 null=样本不足。
     */
    fun pickScore(
        closes: List<Double>, opens: List<Double>, highs: List<Double>,
        lows: List<Double>, vols: List<Double>, pos60: Double?
    ): JSONObject? {
        val n = closes.size
        if (n < 60) return null
        val c = closes[n - 1]; val o = opens[n - 1]
        val (d, sarBars, fresh) = sarState(closes, highs, lows)
        if (d != "UP") return null
        val macdT = TechTags.macdText(closes)
        val obvT = TechTags.obvText(closes, vols) ?: ""
        val rsi6 = TechTags.rsi(closes, 6)[n - 1]
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val threeNoLow = (0 until 3).all { lows[n - 3 + it] >= lows[n - 4] }
        val upDay = c > o
        val v5 = if (n >= 6) vols.subList(n - 6, n - 1).average() else 0.0
        val vr = if (v5 > 0) vols[n - 1] / v5 else 1.0
        val stable = upDay && vr >= 1.05 && c > ma5 && threeNoLow
        var s = 0.0
        if (stable) s += 3.0
        if (fresh) s += 2.0
        if (macdT == "金叉") s += 2.2 else if (macdT.startsWith("红柱")) s += 1.0
        if (obvT == "OBV上行") s += 1.5
        if (rsi6 in 20.0..55.0) s += 0.8
        if (pos60 != null) s += when { pos60 <= -20 -> 2.0; pos60 <= -12 -> 1.5; pos60 <= -6 -> 1.0; else -> 0.3 }
        if (c > ma5) s += 0.8
        if (c > ma10) s += 0.4
        if (threeNoLow) s += 1.2
        if (upDay) s += 0.6
        if (upDay && vr >= 1.0 && vr <= 3.5) s += 1.0
        val core = stable || fresh || macdT == "金叉"
        return JSONObject()
            .put("score", round(s, 2)).put("core", core)
            .put("sar", if (!fresh) "SAR红↑$sarBars" else "SAR刚翻红")
            .put("sarDir", d).put("sarBars", sarBars).put("freshUp", fresh)
            .put("macd", macdT).put("obv", obvT).put("rsi6", rsi6).put("pos60", pos60 ?: JSONObject.NULL)
            .put("above5", c > ma5).put("above10", c > ma10)
            .put("threeNoLow", threeNoLow).put("upDay", upDay)
            .put("volRatio", round(vr, 2)).put("stable", stable)
    }

    /** snaps → 精简 K 线（供下游 stop_loss_vote target=picks 内嵌，免二次取数）。 */
    fun barsPack(snaps: JSONArray, limit: Int = 140): JSONArray {
        val arr = JSONArray()
        val start = max(0, snaps.length() - limit)
        for (i in start until snaps.length()) {
            val s = snaps.optJSONObject(i) ?: continue
            arr.put(JSONObject()
                .put("date", s.optString("date"))
                .put("open", TechTags.num(s.optDouble("open")))
                .put("high", TechTags.num(s.optDouble("high")))
                .put("low", TechTags.num(s.optDouble("low")))
                .put("close", TechTags.num(s.optDouble("close")))
                .put("volume", TechTags.num(s.optDouble("volume"))))
        }
        return arr
    }

    /** 趋势图列（方向 + 经典形态）：与 PC `_etf_trend_label → _trend_match_3way` 同口径。 */
    fun trendLabel(closes: List<Double>, opens: List<Double>, highs: List<Double>, lows: List<Double>): String {
        if (closes.size < 30) return "—"
        val m = TrendClassGate.classify(TechTags.toCandles("etf", closes, opens, highs, lows))
        val arrow = when (m.label) { "上涨" -> "↑"; "下跌" -> "↓"; else -> "→" }
        val nm = m.bull ?: m.bear ?: ""
        return if (nm.isEmpty()) "$arrow${m.label}" else "$arrow${m.label}·$nm"
    }

    fun round(v: Double, digits: Int): Double {
        val m = Math.pow(10.0, digits.toDouble())
        return Math.round(v * m) / m
    }

    /** JSONArray(snaps) → 各序列（对齐 Python `_etf_snaps_for` 消费口径）。 */
    fun series(snaps: JSONArray): Map<String, List<Double>> {
        val n = snaps.length()
        val closes = ArrayList<Double>(n); val opens = ArrayList<Double>(n)
        val highs = ArrayList<Double>(n); val lows = ArrayList<Double>(n); val vols = ArrayList<Double>(n)
        for (i in 0 until n) {
            val s = snaps.optJSONObject(i)
            closes.add(TechTags.num(s.optDouble("close"))); opens.add(TechTags.num(s.optDouble("open")))
            highs.add(TechTags.num(s.optDouble("high"))); lows.add(TechTags.num(s.optDouble("low")))
            vols.add(TechTags.num(s.optDouble("volume")))
        }
        return mapOf("closes" to closes, "opens" to opens, "highs" to highs,
            "lows" to lows, "vols" to vols)
    }
}

/** 个股行情：DB daily_snapshot 优先（升序）；不足 minBars 返回 null。 */
private suspend fun etfStockSnaps(context: PipelineContext, code6: String, minBars: Int, cap: Int = 400): JSONArray? {
    return try {
        val db = StockDatabase.getInstance(context.androidContext)
        val rows = db.dailySnapshotDao().getByCode(EtfScreenMath.prefixed(code6), cap)
            .sortedBy { it.date }
        if (rows.size < minBars) null else {
            val arr = JSONArray()
            for (r in rows) {
                arr.put(JSONObject().put("date", r.date).put("open", r.open).put("high", r.high)
                    .put("low", r.low).put("close", r.close).put("volume", r.volume))
            }
            arr
        }
    } catch (e: Exception) {
        Log.w("EtfScreen", "读取个股行情失败 $code6: ${e.message}")
        null
    }
}

/**
 * ## ① ETF 持股 top5（module=etf_holdings_rank）
 *
 * 输入 `data/_etf_holdings.json` 覆盖矩阵；统计「哪只个股被多少只行业/主题 ETF 列入前五重仓」，
 * 按 n↓ / 合计权重↓ / pos60↑ 排行。**不做大盘择时**（覆盖度排行榜）。
 * 输出：{as_of, updated, fundsTotal, stocksTotal, rows[], industryOnly, minCoverage, note}
 */
class EtfHoldingsRankNode(
    private val topN: Int = 10,
    private val minCoverage: Int = 2,
    private val industryOnly: Boolean = true,
    private val baseThemes: String = "宽基",
    private val embedBars: Boolean = true,
    private val barsLimit: Int = 140,
    private val minBars: Int = 60,
    private val strategyText: String = ""
) : BaseNode<Any, JSONObject>("etf_holdings_rank", "ETF前五重仓覆盖矩阵排行", NodeType.DATA_SOURCE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject =
        withContext(Dispatchers.IO) {
            val d = EtfHoldingsStore.load(context.androidContext)
            if (d == null) {
                context.log(nodeId, "✗ 缺 data/_etf_holdings.json（ETF 持股 top5 数据资产）")
                val out = JSONObject().put("available", false).put("rows", JSONArray())
                    .put("note", "缺 data/_etf_holdings.json：请先在 PC 生成并推送该资产")
                context.setStageOutput(nodeId, out)
                return@withContext out
            }
            val base = baseThemes.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val fundMap = HashMap<String, JSONObject>()
            val funds = d.optJSONArray("funds") ?: JSONArray()
            for (i in 0 until funds.length()) {
                val f = funds.optJSONObject(i) ?: continue
                fundMap[EtfScreenMath.code6(f.optString("code"))] = f
            }
            val stocks = d.optJSONObject("stocks") ?: JSONObject()
            val rows = ArrayList<JSONObject>()
            val keys = stocks.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                val st = stocks.optJSONObject(code) ?: continue
                val rawFunds = st.optJSONArray("funds") ?: JSONArray()
                var fl = (0 until rawFunds.length()).map { EtfScreenMath.code6(rawFunds.optString(it)) }
                    .filter { fundMap.containsKey(it) }
                if (industryOnly) fl = fl.filter { (fundMap[it]?.optString("theme") ?: "") !in base }
                if (fl.size < minCoverage) continue
                val themes = fl.mapNotNull { fundMap[it]?.optString("theme")?.takeIf { t -> t.isNotBlank() } }
                    .distinct()
                rows.add(JSONObject()
                    .put("code", EtfScreenMath.prefixed(code)).put("code6", EtfScreenMath.code6(code))
                    .put("name", st.optString("name"))
                    .put("n", fl.size)
                    .put("funds", JSONArray(fl.map { fundMap[it]?.optString("name") ?: it }))
                    .put("themes", JSONArray(themes))
                    .put("sumRatio", EtfScreenMath.round(st.optDouble("sum_ratio", 0.0), 2))
                    .put("pos60", if (st.has("pos60") && !st.isNull("pos60")) st.optDouble("pos60") else JSONObject.NULL))
            }
            rows.sortWith(Comparator { a, b ->
                var c = b.optInt("n") - a.optInt("n")
                if (c != 0) return@Comparator c
                c = b.optDouble("sumRatio", 0.0).compareTo(a.optDouble("sumRatio", 0.0))
                if (c != 0) return@Comparator c
                val pa = if (a.isNull("pos60")) 0.0 else a.optDouble("pos60")
                val pb = if (b.isNull("pos60")) 0.0 else b.optDouble("pos60")
                pa.compareTo(pb)
            })
            val picked = rows.take(topN)
            var miss = 0
            if (embedBars) {
                for (r in picked) {
                    val snaps = etfStockSnaps(context, r.optString("code6"), max(25, minBars))
                    if (snaps == null) { miss++; continue }
                    val closes = EtfScreenMath.series(snaps)["closes"]!!
                    r.put("bars", EtfScreenMath.barsPack(snaps, barsLimit))
                    r.put("date", snaps.optJSONObject(snaps.length() - 1)?.optString("date") ?: "")
                    r.put("close", EtfScreenMath.round(closes.last(), 3))
                }
            }
            val outRows = JSONArray()
            picked.forEach { outRows.put(it) }
            val out = JSONObject()
                .put("available", true).put("updated", d.optString("updated"))
                .put("fundsTotal", fundMap.size).put("stocksTotal", stocks.length())
                .put("rows", outRows).put("industryOnly", industryOnly).put("minCoverage", minCoverage)
                .put("strategy", strategyText)
                .put("note", "ETF 前五重仓覆盖矩阵（季报级）：n=覆盖该股的行业ETF数；" +
                    (if (industryOnly) "仅被宽基覆盖的个股已剔除。" else ""))
            context.setStageOutput(nodeId, out)
            context.log(nodeId, "🧺 ETF 持股 top5: ${outRows.length()} 只（覆盖≥$minCoverage 只行业ETF，" +
                "${if (miss > 0) "$miss 只无本地行情；" else ""}矩阵 ${fundMap.size}基金/${stocks.length()}股）")
            out
        }
}

/**
 * ## ② ETF 全行业扫描（module=etf_industry_scan）
 *
 * 行业/主题 ETF（剔除宽基）→ 各自前五重仓股 → 逐股低吸打分（SAR红硬门控）→ topN。
 * 内置**大盘三态自适应**：BULLISH 正常 / OSCILLATION 门槛+oscScoreAdd 且 pos60≤oscPos60Max /
 * BEARISH 暂停开仓（ok=false 降级为观察）。
 * 行内嵌 `bars` 供下游 stop_loss_vote target=picks 直接定价。
 */
class EtfIndustryScanNode(
    private val topN: Int = 10,
    private val minScore: Double = 5.0,
    private val minBars: Int = 60,
    private val ddWin: Int = 60,
    private val minETF: Int = 1,
    private val industryOnly: Boolean = true,
    private val baseThemes: String = "宽基",
    private val excludeStar: Boolean = true,
    private val barsLimit: Int = 140,
    private val embedBars: Boolean = true,
    private val marketAdapt: Boolean = true,
    private val indexCode: String = "sh000300",
    private val oscScoreAdd: Double = 1.0,
    private val oscPos60Max: Double = -12.0,
    private val bearishPause: Boolean = true,
    private val strategyText: String = ""
) : BaseNode<Any, JSONObject>("etf_industry_scan", "ETF全行业低吸扫描", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject =
        withContext(Dispatchers.IO) {
            val cache = EtfCacheStore.load(context.androidContext)
            val mkt = if (marketAdapt) EtfScreenMath.marketState(cache, indexCode) else "OSCILLATION"
            val effMin = minScore + (if (mkt == "OSCILLATION") oscScoreAdd else 0.0)
            val bearPause = bearishPause && mkt == "BEARISH"
            val d = EtfHoldingsStore.load(context.androidContext)
            if (d == null) {
                context.log(nodeId, "✗ 缺 data/_etf_holdings.json（ETF 全行业扫描数据资产）")
                val out = JSONObject().put("available", false).put("rows", JSONArray())
                    .put("note", "缺 data/_etf_holdings.json：请先在 PC 生成并推送该资产")
                context.setStageOutput(nodeId, out)
                return@withContext out
            }
            val base = baseThemes.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            // ① 行业/主题 ETF → 前五重仓股（候选 + 归属 ETF/主题/合计权重）
            val cand = LinkedHashMap<String, JSONObject>()
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
                        JSONObject().put("name", s.optString("name"))
                            .put("etfs", JSONArray()).put("themes", JSONArray()).put("ratio", 0.0)
                    }
                    val fn = f.optString("name").ifBlank { f.optString("code") }
                    val etfs = e.optJSONArray("etfs")!!
                    if ((0 until etfs.length()).none { etfs.optString(it) == fn }) etfs.put(fn)
                    val themes = e.optJSONArray("themes")!!
                    if (theme.isNotBlank() && (0 until themes.length()).none { themes.optString(it) == theme }) {
                        themes.put(theme)
                    }
                    e.put("ratio", e.optDouble("ratio", 0.0) + s.optDouble("ratio", 0.0))
                }
            }
            val universe = cand.filterValues { it.optJSONArray("etfs")!!.length() >= minETF }
            // ② 逐股取行情 + 打分
            val rows = ArrayList<JSONObject>()
            var skipped = 0
            for ((c6, meta) in universe) {
                val snaps = etfStockSnaps(context, c6, minBars)
                    ?: EtfCacheStore.load(context.androidContext)?.optJSONObject(EtfScreenMath.prefixed(c6))
                        ?.optJSONArray("snaps")?.takeIf { it.length() >= minBars }
                if (snaps == null) { skipped++; continue }
                val ser = EtfScreenMath.series(snaps)
                val closes = ser["closes"]!!; val opens = ser["opens"]!!
                val highs = ser["highs"]!!; val lows = ser["lows"]!!; val vols = ser["vols"]!!
                val pos60 = if (closes.size >= 21) {
                    val hi = closes.takeLast(ddWin).max()
                    if (hi > 0) EtfScreenMath.round((closes.last() / hi - 1) * 100, 2) else null
                } else null
                val sc = EtfScreenMath.pickScore(closes, opens, highs, lows, vols, pos60)
                if (sc == null) { skipped++; continue }
                var ok = sc.optBoolean("core") && sc.optDouble("score") >= effMin
                if (ok && mkt == "OSCILLATION" && pos60 != null && pos60 > oscPos60Max) ok = false
                if (ok && bearPause) ok = false
                val row = JSONObject()
                    .put("code", EtfScreenMath.prefixed(c6)).put("code6", c6)
                    .put("name", meta.optString("name"))
                    .put("date", snaps.optJSONObject(snaps.length() - 1)?.optString("date") ?: "")
                    .put("close", EtfScreenMath.round(closes.last(), 3))
                    .put("score", sc.optDouble("score")).put("ok", ok)
                    .put("etfs", meta.optJSONArray("etfs")).put("themes", meta.optJSONArray("themes"))
                    .put("sumRatio", EtfScreenMath.round(meta.optDouble("ratio", 0.0), 2))
                    .put("pos60", pos60 ?: JSONObject.NULL)
                    .put("sar", sc.optString("sar")).put("freshUp", sc.optBoolean("freshUp"))
                    .put("macd", sc.optString("macd")).put("obv", sc.optString("obv"))
                    .put("rsi6", EtfScreenMath.round(sc.optDouble("rsi6"), 1))
                    .put("volRatio", sc.optDouble("volRatio")).put("stable", sc.optBoolean("stable"))
                    .put("above5", sc.optBoolean("above5")).put("above10", sc.optBoolean("above10"))
                    .put("threeNoLow", sc.optBoolean("threeNoLow")).put("upDay", sc.optBoolean("upDay"))
                    .put("kline", TechTags.klineDesc(closes, closes.size - 1))
                    .put("trend", EtfScreenMath.trendLabel(closes, opens, highs, lows))
                if (embedBars) row.put("bars", EtfScreenMath.barsPack(snaps, barsLimit))
                rows.add(row)
            }
            rows.sortWith(compareByDescending { it.optDouble("score") })
            var degraded = false
            var picked = rows.filter { it.optBoolean("ok") }.take(topN)
            if (picked.isEmpty()) { picked = rows.take(topN); degraded = true }
            val pickedArr = JSONArray(); picked.forEach { pickedArr.put(it) }
            val marketNote = when (mkt) {
                "BULLISH" -> "🐮 多头：正常扫描"
                "BEARISH" -> "🐻 空头：暂停开仓，仅输出观察清单"
                else -> String.format(Locale.US, "↔ 震荡：门槛 +%.1f 且要求 pos60≤%.0f%%",
                    effMin - minScore, oscPos60Max)
            }
            val out = JSONObject()
                .put("available", true).put("updated", d.optString("updated"))
                .put("universe", universe.size).put("scanned", rows.size).put("skipped", skipped)
                .put("rows", pickedArr).put("degraded", degraded).put("marketState", mkt)
                .put("minScore", effMin).put("industryOnly", industryOnly)
                .put("strategy", strategyText).put("marketNote", marketNote)
                .put("note", String.format(Locale.US,
                    "行业ETF前五重仓 → 低吸打分（SAR红为硬门控；score≥%.1f 且含转折确认）", effMin) +
                    (if (degraded) "；⚠ 无达标标的，已降级输出打分前 ${picked.size}（仅供观察）" else ""))
            context.setStageOutput(nodeId, out)
            context.log(nodeId, "🔭 ETF 全行业扫描($mkt): 候选 ${cand.size} 只 → 打分 ${rows.size} 只 → " +
                "${if (degraded) "降级观察" else "入选"} ${picked.size} 只（跳过 $skipped 只无行情）")
            out
        }
}

/**
 * ## ③ 纯 ETF 本体筛选（module=etf_pure_screen）
 *
 * ETF 自身 RAS 相对强度 / MACD / OBV / RSI6 / 距250日高回撤 / 流动性 → base_buy / t_buy / t_sell。
 * 内置**大盘三态自适应**：BULLISH 放宽位置门槛 / OSCILLATION 标准档 / BEARISH 底仓全关仅做 T。
 */
class EtfPureScreenNode(
    private val indexCode: String = "sh000300",
    private val ddLo: Double = 0.40,
    private val ddHi: Double = 0.60,
    private val growthRelax: Boolean = true,
    private val relaxLo: Double = 0.35,
    private val relaxHi: Double = 0.65,
    private val ddWin: Int = 250,
    private val rsiLo: Double = 30.0,
    private val rsiHi: Double = 55.0,
    private val rsiSell: Double = 70.0,
    private val rasWin: Int = 20,
    private val amountWin: Int = 20,
    private val volLot: Double = 100.0,
    private val minAmountYi: Double = 0.5,
    private val topN: Int = 5,
    private val minBars: Int = 0,
    private val requireRas: Boolean = true,
    private val requireMacd: Boolean = true,
    private val requireObv: Boolean = true,
    private val marketAdapt: Boolean = true,
    private val bearishPause: Boolean = true,
    private val bullLo: Double = 0.45,
    private val bullHi: Double = 0.72,
    private val bullRsiHi: Double = 60.0,
    private val excludeBroadBaseBuy: Boolean = false,
    private val broadCodes: String = "510300,159915,510050,510500,510180,510880,159919,510330,510900,159901,159905,159949",
    private val strategyText: String = ""
) : BaseNode<Any, JSONObject>("etf_pure_screen", "纯ETF本体筛选", NodeType.FACTOR_COMPUTE) {

    override suspend fun execute(context: PipelineContext, input: Any): JSONObject =
        withContext(Dispatchers.IO) {
            var lo = ddLo; var hi = ddHi
            if (growthRelax) { lo = relaxLo; hi = relaxHi }
            val minB = if (minBars > 0) minBars else ddWin
            val cache = EtfCacheStore.load(context.androidContext)
            val mkt = if (marketAdapt) EtfScreenMath.marketState(cache, indexCode) else "OSCILLATION"
            var rsiUp = rsiHi
            if (mkt == "BULLISH") { lo = bullLo; hi = bullHi; rsiUp = bullRsiHi }
            val bearPause = bearishPause && mkt == "BEARISH"
            val broadSet = broadCodes.split(",").map { EtfScreenMath.code6(it.trim()) }
                .filter { it.isNotEmpty() }.toSet()
            val benchSnaps = cache?.optJSONObject(indexCode)?.optJSONArray("snaps") ?: JSONArray()
            val benchCloses = EtfScreenMath.series(benchSnaps)["closes"]!!
            val rows = ArrayList<JSONObject>()
            var skipped = 0
            if (cache != null) {
                val keys = cache.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    if (code.startsWith("sh000") || code.startsWith("sz399")) continue
                    val snaps = cache.optJSONObject(code)?.optJSONArray("snaps") ?: continue
                    if (snaps.length() < minB || benchCloses.size < rasWin + 1) { skipped++; continue }
                    val ser = EtfScreenMath.series(snaps)
                    val closes = ser["closes"]!!; val opens = ser["opens"]!!
                    val highs = ser["highs"]!!; val lows = ser["lows"]!!; val vols = ser["vols"]!!
                    val c = closes.last()
                    val h250 = closes.takeLast(ddWin).max()
                    if (h250 <= 0) { skipped++; continue }
                    val dd = c / h250
                    val rsi6 = TechTags.rsi(closes, 6).last()
                    val dif = TechTags.emaSeries(closes, 12)
                    val dea = TechTags.emaSeries(dif, 9)
                    val macdBar = (dif[dif.size - 1] - dea[dea.size - 1]) * 2
                    val prevBar = if (dif.size > 1) (dif[dif.size - 2] - dea[dea.size - 2]) * 2 else macdBar
                    val macdT = TechTags.macdText(closes)
                    val obvT = TechTags.obvText(closes, vols) ?: ""
                    val slopes = EtfScreenMath.rasSlopeSeries(closes, benchCloses, rasWin)
                    val g2r = slopes.size >= 2 && slopes[slopes.size - 2] < 0 && slopes.last() >= 0
                    val lo250 = lows.takeLast(ddWin).min()
                    var amtSum = 0.0
                    for (i in 0 until amountWin) {
                        val idx = closes.size - amountWin + i
                        amtSum += vols[idx] * closes[idx] * volLot
                    }
                    val amtYi = amtSum / amountWin / 1e8
                    val condRas = g2r || !requireRas
                    val condMacd = dif.last() > dea.last() || !requireMacd
                    val condObv = obvT == "OBV上行" || !requireObv
                    val condRsi = rsi6 >= rsiLo && rsi6 <= rsiUp
                    val condPos = dd >= lo && dd <= hi
                    val condAmt = amtYi >= minAmountYi
                    var baseBuy = condRas && condMacd && condObv && condRsi && condPos && condAmt
                    if (bearPause) baseBuy = false
                    val isBroad = EtfScreenMath.code6(code) in broadSet
                    if (isBroad && excludeBroadBaseBuy) baseBuy = false
                    val tBuy = rsi6 <= rsiLo && macdBar < prevBar
                    val tSell = rsi6 >= rsiSell && macdBar > prevBar
                    val posScore = if (condPos) {
                        when { dd <= lo + 0.08 -> 2.0; dd <= lo + 0.16 -> 1.5; else -> 1.0 }
                    } else 0.0
                    val score = (if (g2r) 2.0 else 0.0) + (if (dif.last() > dea.last()) 2.0 else 0.0) +
                        (if (obvT == "OBV上行") 1.5 else 0.0) + (if (condRsi) 1.0 else 0.0) + posScore
                    rows.add(JSONObject()
                        .put("code", code).put("name", cache.optJSONObject(code)?.optString("name") ?: code)
                        .put("date", snaps.optJSONObject(snaps.length() - 1)?.optString("date") ?: "")
                        .put("close", EtfScreenMath.round(c, 3))
                        .put("dd250", EtfScreenMath.round(dd, 3)).put("pos250", EtfScreenMath.round(dd * 100, 1))
                        .put("rsi6", EtfScreenMath.round(rsi6, 1)).put("macd", macdT).put("obv", obvT)
                        .put("rasGreen2Red", g2r)
                        .put("rasSlope", if (slopes.isNotEmpty()) EtfScreenMath.round(slopes.last(), 6) else JSONObject.NULL)
                        .put("amountYi", EtfScreenMath.round(amtYi, 2)).put("minAmountYi", minAmountYi)
                        .put("broad", isBroad).put("baseBuy", baseBuy).put("tBuy", tBuy).put("tSell", tSell)
                        .put("score", EtfScreenMath.round(score, 2))
                        .put("gates", JSONObject().put("ras", condRas).put("macd", condMacd)
                            .put("obv", condObv).put("rsi", condRsi).put("pos", condPos).put("amount", condAmt))
                        .put("kline", TechTags.klineDesc(closes, closes.size - 1))
                        .put("trend", EtfScreenMath.trendLabel(closes, opens, highs, lows))
                        .put("exit", JSONObject().put("tpRsi", 75.0)
                            .put("sl250", EtfScreenMath.round(lo250 * 0.95, 3))
                            .put("note", "底仓止盈: RSI6>75 或 RAS转绿；底仓止损: 跌破250日低点下方5%")))
                }
            }
            rows.sortWith(Comparator { a, b ->
                val c = b.optDouble("score").compareTo(a.optDouble("score"))
                if (c != 0) c else a.optDouble("dd250").compareTo(b.optDouble("dd250"))
            })
            val nBase = rows.count { it.optBoolean("baseBuy") }
            var degraded = false
            var picked = rows.filter { it.optBoolean("baseBuy") }.take(topN)
            if (picked.isEmpty()) { picked = rows.take(topN); degraded = true }
            val watch = rows.filter { !it.optBoolean("baseBuy") }.take(topN)
            val pickedArr = JSONArray(); picked.forEach { pickedArr.put(it) }
            val watchArr = JSONArray(); watch.forEach { watchArr.put(it) }
            val marketNote = when (mkt) {
                "BULLISH" -> String.format(Locale.US, "🐮 多头：位置门槛放宽到 %.2f~%.2f / RSI上限 %.0f", lo, hi, rsiUp)
                "BEARISH" -> "🐻 空头：底仓关闭，仅保留做 T 观察"
                else -> String.format(Locale.US, "↔ 震荡：标准档 %.2f~%.2f", lo, hi)
            }
            val out = JSONObject()
                .put("available", benchCloses.isNotEmpty()).put("bench", indexCode)
                .put("pool", cache?.length() ?: 0).put("scanned", rows.size).put("skipped", skipped)
                .put("rows", pickedArr).put("watch", watchArr).put("degraded", degraded)
                .put("nBaseBuy", nBase).put("marketState", mkt).put("marketNote", marketNote)
                .put("rule", JSONObject().put("ddWindow", ddWin)
                    .put("ddRange", JSONArray(listOf(lo, hi))).put("rsi6", JSONArray(listOf(rsiLo, rsiUp)))
                    .put("rsiSell", rsiSell).put("rasWin", rasWin).put("minAmountYi", minAmountYi))
                .put("strategy", strategyText)
                .put("note", "纯ETF本体筛选：baseBuy=五个门控全过（本次达标 $nBase 只）" +
                    (if (degraded) "；⚠ 无达标标的，已降级输出打分前 ${picked.size}（仅供观察）" else ""))
            context.setStageOutput(nodeId, out)
            context.log(nodeId, "🎯 纯ETF筛选: 池 ${cache?.length() ?: 0} → 可判 ${rows.size} → base_buy $nBase 只（跳过 $skipped 只样本不足）")
            out
        }
}
