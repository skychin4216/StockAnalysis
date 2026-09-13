package com.chin.stockanalysis.stock.database

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.HttpClientProvider
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全球市场 / 宏观快照采集（APK 侧）。
 *
 * 与 PC 侧 `smalltools/_daily_intel.py:snapshot()` **同口径**，两端字段一一对应，
 * 便于核对「同一交易日双端情报是否一致」。
 *
 * 数据源（2026-09-11 实测可用）：
 *  - 东财 `push2delay`    全球指数（push2 主域 RemoteDisconnected 时的替代链路）
 *  - 新浪 `hq.sinajs.cn`  美股收盘（gb_$dji / gb_$ixic / gb_$inx）、商品（hf_CL / hf_GC）、
 *                         在岸人民币（fx_susdcny）
 *  - 腾讯 `qt.gtimg.cn`   A 股实时（含 PE/PB/市值/换手）
 */
object GlobalMarketCollector {

    private const val TAG = "GlobalMarket"

    private const val SINA_HOST = "https://hq.sinajs.cn/list="
    private const val SINA_REFERER = "https://finance.sina.com.cn"

    /** 东财 secid → (中文名 to 区域) */
    private val GLOBAL_IDX = linkedMapOf(
        "100.DJIA" to ("道琼斯" to "美股"),
        "100.NDX" to ("纳斯达克" to "美股"),
        "100.SPX" to ("标普500" to "美股"),
        "100.N225" to ("日经225" to "亚太"),
        "100.KS11" to ("韩国KOSPI" to "亚太"),
        "100.HSI" to ("恒生指数" to "亚太"),
        "100.TWII" to ("台湾加权" to "亚太"),
        "100.STI" to ("新加坡海峡" to "亚太"),
        "100.AS51" to ("澳洲标普200" to "亚太"),
        "100.FTSE" to ("英国富时100" to "欧洲"),
        "100.GDAXI" to ("德国DAX30" to "欧洲"),
        "100.FCHI" to ("法国CAC40" to "欧洲"),
        "100.SX5E" to ("欧洲斯托克50" to "欧洲"),
        "100.UDI" to ("美元指数" to "汇率"),
    )

    /**
     * 新浪指数兜底：secid → 映射。
     * 字段布局 2026-09-11 实测：`int_*` f1=点位 f3=涨跌幅；`gb_$*` f1=点位 f2=涨跌幅。
     * KOSPI（100.KS11）新浪无源，仅东财提供。
     */
    private data class SinaIdx(
        val sym: String, val name: String, val region: String,
        val pxIdx: Int, val pctIdx: Int,
    )

    private val SINA_IDX_FALLBACK = mapOf(
        "100.N225" to SinaIdx("int_nikkei", "日经225", "亚太", 1, 3),
        "100.HSI" to SinaIdx("int_hangseng", "恒生指数", "亚太", 1, 3),
        "100.DJIA" to SinaIdx("gb_\$dji", "道琼斯", "美股", 1, 2),
        "100.NDX" to SinaIdx("gb_\$ixic", "纳斯达克", "美股", 1, 2),
        "100.SPX" to SinaIdx("gb_\$inx", "标普500", "美股", 1, 2),
    )

    /** 新浪商品/汇率：代码 → 中文名 */
    private val SINA_CMDT = linkedMapOf(
        "hf_CL" to "WTI原油",
        "hf_GC" to "COMEX黄金",
        "fx_susdcny" to "在岸人民币",
    )

    /** 韩国权重股（腾讯韩股代码 → 中文名） */
    private val KR_HEAVY = listOf(
        "kr005930" to "三星电子", "kr000660" to "SK海力士",
        "kr373220" to "LG新能源", "kr005380" to "现代汽车",
    )

    /** 单项指数/资产行情 */
    data class Quote(
        val key: String,
        val name: String,
        val region: String,
        val price: Double?,
        val pct: Double?,
    )

    /** 商品/汇率 */
    data class Commodity(val name: String, val price: Double?, val pct: Double?)

    /** 快讯 */
    data class NewsItem(val title: String, val time: String)

    /** A 股实时（含估值） */
    data class AQuote(
        val code: String,
        val name: String,
        val price: Double?,
        val pct: Double?,
        val turn: Double?,
        val peStatic: Double?,
        val peTtm: Double?,
        val pb: Double?,
        val mcapYi: Double?,
    )

    /** 一次完整采集结果 */
    data class Snapshot(
        val ts: String,
        val slot: String,
        val indices: List<Quote>,
        val usClose: List<Quote>,
        val commodity: List<Commodity>,
        val koreaHeavy: List<Quote>,
        val news: List<NewsItem>,
    )

    private val client get() = HttpClientProvider.realtimeClient

    fun nowString(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    private fun fetch(url: String, referer: String? = null): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile)")
        if (referer != null) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.onFailure { Log.w(TAG, "请求失败 $url: ${it.message}") }.getOrNull()

    private fun d(s: String?): Double? = s?.trim()?.toDoubleOrNull()

    // ─────────────────────── 全球指数 ───────────────────────

    /**
     * 东财 push2delay 全球指数（批量，12 个一批）。
     *
     * push2delay 偶发超时，故每批重试 3 次；仍缺的指数用新浪兜底
     * （日经/恒生 `int_*`、美股 `gb_$*`；KOSPI 新浪无源，仅东财）。
     * 与 PC 侧 `_daily_intel.py::east_global` 同口径。
     */
    fun fetchGlobalIndices(): List<Quote> {
        val out = mutableListOf<Quote>()
        val ids = GLOBAL_IDX.keys.toList()
        for (i in ids.indices step 12) {
            val batch = ids.subList(i, minOf(i + 12, ids.size))
            val url = "${DataConfig.eastmoneyPush2Delay}/ulist.np/get" +
                "?secids=${batch.joinToString(",")}" +
                "&fields=f2,f3,f4,f12,f14&fltt=2&invt=2&ut=fa5fd1943c7b386f172d6893dbfba10b"
            var body: String? = null
            for (attempt in 0 until 3) {
                body = fetch(url, "https://quote.eastmoney.com/")
                if (body != null) break
                Thread.sleep(800L * (attempt + 1))
            }
            if (body == null) {
                Log.w(TAG, "东财全球指数失败(重试3次)，批次 ${batch.firstOrNull()}…")
                continue
            }
            runCatching {
                val diff = JSONObject(body).optJSONObject("data")?.optJSONArray("diff")
                for (k in 0 until (diff?.length() ?: 0)) {
                    val item = diff!!.optJSONObject(k) ?: continue
                    val code = item.optString("f12")
                    val meta = GLOBAL_IDX["100.$code"] ?: continue
                    out += Quote("100.$code", meta.first, meta.second,
                        item.optDoubleOrNull("f2"), item.optDoubleOrNull("f3"))
                }
            }.onFailure { Log.w(TAG, "解析全球指数失败: ${it.message}") }
        }

        // 新浪兜底补全
        val having = out.map { it.key }.toSet()
        val missing = SINA_IDX_FALLBACK.keys.filter { it !in having }
        if (missing.isNotEmpty()) {
            val lines = sinaLines(missing.map { SINA_IDX_FALLBACK.getValue(it).sym })
            for (key in missing) {
                val meta = SINA_IDX_FALLBACK.getValue(key)
                val f = lines[meta.sym] ?: continue
                out += Quote(key, meta.name, meta.region,
                    d(f.getOrNull(meta.pxIdx)), d(f.getOrNull(meta.pctIdx)))
            }
            Log.i(TAG, "东财缺 ${missing.size} 个指数，新浪兜底补全")
        }
        return out
    }

    /** 美股收盘（新浪 gb_$ 系列，字段：0名称 1收盘 2涨跌% 4涨跌额）。 */
    fun fetchUsClose(): List<Quote> {
        val map = linkedMapOf(
            "gb_\$dji" to "道琼斯",
            "gb_\$ixic" to "纳斯达克",
            "gb_\$inx" to "标普500"
        )
        val lines = sinaLines(map.keys.toList())
        return map.mapNotNull { (code, name) ->
            val f = lines[code] ?: return@mapNotNull null
            if (f.size < 5) return@mapNotNull null
            Quote(code, name, "美股", d(f[1]), d(f[2]))
        }
    }

    /** 韩国权重股（腾讯韩股，可能不可用 → 返回空列表）。 */
    fun fetchKoreaHeavy(): List<Quote> {
        val map = tencentQuotes(KR_HEAVY.map { it.first })
        return KR_HEAVY.mapNotNull { (code, name) ->
            val f = map[code] ?: return@mapNotNull null
            if (f.size < 33) return@mapNotNull null
            Quote(code, name, "亚太", d(f[3]), d(f[32]))
        }.sortedByDescending { it.pct ?: -99.0 }
    }

    // ─────────────────────── 商品 / 汇率 ───────────────────────

    /** 日线（腾讯 fqkline，前复权）：返回 (日期, 收盘, 最高) 序列，升序。 */
    fun fetchDailyKline(code: String, days: Int = 90): List<Triple<String, Double, Double>> {
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get" +
            "?param=$code,day,,,$days,qfq"
        val body = fetch(url, "https://gu.qq.com/") ?: return emptyList()
        return runCatching {
            val node = JSONObject(body).optJSONObject("data")?.optJSONObject(code)
                ?: return@runCatching emptyList()
            val arr = node.optJSONArray("qfqday") ?: node.optJSONArray("day")
                ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val row = arr.optJSONArray(i) ?: return@mapNotNull null
                if (row.length() < 4) return@mapNotNull null
                val date = row.optString(0)
                val close = row.optString(2).toDoubleOrNull()
                val high = row.optString(3).toDoubleOrNull()
                if (close == null || high == null) null else Triple(date, close, high)
            }.sortedBy { it.first }
        }.getOrElse { emptyList() }
    }

    /** 商品与汇率（新浪）。hf_ 字段：0最新 6时间 7昨收；fx_ 字段：0时间 1买价 3中间价。 */
    fun fetchCommodityFx(): List<Commodity> {
        val lines = sinaLines(SINA_CMDT.keys.toList())
        return SINA_CMDT.mapNotNull { (code, name) ->
            val f = lines[code] ?: return@mapNotNull null
            if (code.startsWith("hf_")) {
                val cur = d(f.getOrNull(0))
                val prev = d(f.getOrNull(7))
                val pct = if (cur != null && prev != null && prev != 0.0) {
                    (cur / prev - 1) * 100
                } else null
                Commodity(name, cur, pct)
            } else {
                Commodity(name, d(f.getOrNull(3)) ?: d(f.getOrNull(1)), null)
            }
        }
    }

    // ─────────────────────── 快讯 ───────────────────────

    /** 东财 7x24 财经快讯。 */
    fun fetchNews(limit: Int = 12): List<NewsItem> {
        val url = "https://np-listapi.eastmoney.com/comm/web/getFastNewsList" +
            "?client=web&biz=web_724&fastColumn=102&sortEnd=&pageSize=$limit&req_trace=1"
        val body = fetch(url) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("fastNewsList")
            (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").ifBlank { o.optString("summary") }
                if (title.isBlank()) null
                else NewsItem(title.take(80), o.optString("showTime").ifBlank { o.optString("digestTime") })
            }
        }.getOrElse { emptyList() }
    }

    // ─────────────────────── A 股 ───────────────────────

    /**
     * 腾讯 A 股实时（含估值）。
     * 字段映射（`_sector_quote.py` 同口径）：f3现价 f32涨跌% f38换手
     * f39 PE(TTM) f45总市值(亿) f46 PB f53静态PE
     */
    fun fetchAQuotes(codes: List<String>): Map<String, AQuote> {
        val out = mutableMapOf<String, AQuote>()
        for (i in codes.indices step 12) {
            val batch = codes.subList(i, minOf(i + 12, codes.size))
            val map = tencentQuotes(batch)
            for ((code, f) in map) {
                if (f.size < 54) continue
                out[code] = AQuote(
                    code, f[1], d(f[3]), d(f[32]), d(f[38]),
                    d(f[53]), d(f[39]), d(f[46]), d(f[45]),
                )
            }
        }
        return out
    }

    // ─────────────────────── 内部 ───────────────────────

    private fun sinaLines(symbols: List<String>): Map<String, List<String>> {
        if (symbols.isEmpty()) return emptyMap()
        val body = fetch(SINA_HOST + symbols.joinToString(","), SINA_REFERER) ?: return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        for (line in body.split("\n")) {
            val t = line.trim()
            if (!t.contains("hq_str_") || !t.contains("=\"")) continue
            val code = t.substringAfter("hq_str_").substringBefore("=")
            val content = t.substringAfter("=\"").trimEnd(';', '"')
            if (content.isNotBlank()) out[code] = content.split(",")
        }
        return out
    }

    private fun tencentQuotes(codes: List<String>): Map<String, List<String>> {
        if (codes.isEmpty()) return emptyMap()
        val body = fetch("${DataConfig.tencentGtimg}/q=${codes.joinToString(",")}",
            "https://gu.qq.com/") ?: return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        for (line in body.split(";")) {
            val t = line.trim()
            if (!t.contains("=\"")) continue
            val code = t.substringAfter("v_", "").substringBefore("=").trim()
            if (code.isBlank()) continue
            out[code] = t.substringAfter("\"").substringBeforeLast("\"").split("~")
        }
        return out
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    /**
     * 一次完整采集。
     *
     * @param slot pre8（08:00 盘前，宏观 + 美股收盘）/ pre9（09:00 亚太，含韩国权重股）
     */
    fun snapshot(slot: String): Snapshot {
        val indices = runCatching { fetchGlobalIndices() }.getOrElse { emptyList() }
        val us = runCatching { fetchUsClose() }.getOrElse { emptyList() }
        val cmd = runCatching { fetchCommodityFx() }.getOrElse { emptyList() }
        val kr = if (slot == "pre9") runCatching { fetchKoreaHeavy() }.getOrElse { emptyList() }
        else emptyList()
        val news = runCatching { fetchNews() }.getOrElse { emptyList() }
        return Snapshot(nowString(), slot, indices, us, cmd, kr, news)
    }
}
