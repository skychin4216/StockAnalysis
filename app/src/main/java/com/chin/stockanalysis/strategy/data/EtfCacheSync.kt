package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * ## APK ETF 行情本地自拉同步器（2026-09-07）
 *
 * 目标：让 工作台·ETF 页**完全自给**（不再依赖 PC push / PC 桥）——
 * 与 PC 端 `smalltools/_etf_cache.json` **同构、同源**（腾讯 `fqkline` qfq 前复权日K），
 * 直接在手机写 `etf_cache.json`（`EtfCacheStore` 优先读 external files 目录）。
 * 写盘后 ETF 三节点（etf_gate / etf_dip_signal / etf_exit_policy）照常本地执行。
 *
 * - 标的池/名称与 `smalltools/_etf_buy.py` 的 `ETF_POOL` + `IDX_300` 严格一致（13 只 + 沪深300）
 * - snaps 字段 `{date,open,close,high,low,volume}` 与 PC `fetch_tencent` 逐字段一致
 * - 每只 ≥ MIN_SNAPS(900) 根（`EtfDipSignalNode.minSnapshots` 硬要求，≈3.7 年；
 *   本实现自 BEG=2020-01-01 拉 ≈1600 根，留足 MA250/回撤窗口余量）
 * - qfq 前复权遇除权会整体重构价格链 → 需要更新时对该只**整段重拉覆盖**，
 *   不做"只补尾巴"式增量（避免复权链断裂）
 * - 新鲜度用 1 次轻量"探测请求"取指数最新交易日判定，比本地自然日推断可靠
 */
class EtfCacheSync(private val context: Context) {

    data class SyncResult(
        val updated: Boolean,
        val codeCount: Int,    // 缓存内标的数（含指数）
        val asOf: String?,     // 最新交易日 yyyy-MM-dd
        val message: String
    )

    companion object {
        private const val TAG = "EtfCacheSync"
        private const val FILE_NAME = "etf_cache.json"
        private const val FQKLINE = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
        private const val MIN_SNAPS = 900          // 与 EtfDipSignalNode.minSnapshots 一致
        private const val BEG = "2020-01-01"        // 数据起点（自然年），保证 900 根 + 余量
        private const val PROBE_DAYS_BACK = 15L     // 探测窗口（自然日，覆盖长假）
        private const val PAGE = 640                // 腾讯单页上限
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        // ── 与 smalltools/_etf_buy.py 的 ETF_POOL + IDX_300 严格一致 ──
        val POOL: List<Pair<String, String>> = listOf(
            "sh510050" to "上证50ETF", "sh510300" to "沪深300ETF", "sh510500" to "中证500ETF",
            "sz159915" to "创业板ETF", "sh510180" to "上证180ETF", "sh510880" to "上证红利ETF",
            "sz159919" to "沪深300ETF嘉实", "sh510330" to "沪深300ETF华泰", "sh512010" to "医药ETF",
            "sz159928" to "消费ETF", "sh510900" to "H股ETF", "sz159920" to "恒生ETF",
            "sh510660" to "医药行业ETF"
        )
        val IDX_300: Pair<String, String> = "sh000300" to "沪深300指数"
        val ALL: List<Pair<String, String>> = POOL + IDX_300
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    // ── 缓存文件：与 EtfCacheStore 一致（external 优先、internal 兜底）──
    private fun targetFile(): File {
        val ext = context.getExternalFilesDir(null)
        return if (ext != null) File(ext, FILE_NAME) else File(context.filesDir, FILE_NAME)
    }

    private fun existingFiles(): List<File> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .map { File(it, FILE_NAME) }
            .filter { it.isFile }

    /** 读取当前本地缓存（无则 null）。与 EtfCacheStore.load 同一份文件。 */
    fun loadLocalCache(): JSONObject? {
        for (f in existingFiles()) {
            try {
                val text = f.readText()
                if (text.isNotBlank()) {
                    val j = JSONObject(text)
                    if (j.length() > 0) return j
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析 ${f.absolutePath} 失败: ${e.message}")
            }
        }
        return null
    }

    /**
     * 主入口：缓存缺失 / 指数落后于最新交易日 / 任一只不足 900 根 → 全量重拉。
     * 幂等：调用方（ETF 页刷新）可随时调用，内部先做 1 次轻探测快速短路。
     */
    suspend fun syncIfStale(force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        val cache = loadLocalCache()

        if (!force) {
            val latest = probeIndexLatest()
            if (latest != null && cache != null) {
                val idxLast = lastDateOf(cache, IDX_300.first)
                val short = POOL.any { (snapsOf(cache, it.first)?.length() ?: 0) < MIN_SNAPS }
                if (idxLast != null && idxLast == latest && !short) {
                    return@withContext SyncResult(false, cache.length(), latest,
                        "✔ 本地 ETF 行情已是最新（截至 $latest，${cache.length()} 标的）")
                }
            }
            if (latest == null && cache != null) {
                // 网络不可用：保留旧缓存，上层走离线渲染 / PC 桥
                val asOf = lastDateOf(cache, IDX_300.first) ?: ""
                return@withContext SyncResult(false, cache.length(), asOf,
                    "⚠ 网络不可用，无法更新 ETF 行情（沿用本地缓存 $asOf）")
            }
        }

        // ── 需要同步：分批并发全量重拉（BEG→今，每批 4 路，避免触发限流）──
        var success = 0
        var fail = 0
        val fresh = JSONObject()
        coroutineScope {
            ALL.chunked(4).forEach { batch ->
                val results = batch.map { (code, defName) ->
                    async(Dispatchers.IO) {
                        try {
                            val (name, snaps) = fetchFullHistory(code)
                            if (snaps.size >= MIN_SNAPS) {
                                code to JSONObject()
                                    .put("name", if (name.isBlank()) defName else name)
                                    .put("src", "tencent")
                                    .put("snaps", snaps)
                            } else {
                                Log.w(TAG, "$code 样本不足（${snaps.size} < $MIN_SNAPS），保留旧数据")
                                code to null
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "$code 拉取失败: ${e.message}")
                            code to null
                        }
                    }
                }
                results.map { it.await() }.forEach { (code, ent) ->
                    if (ent != null) { fresh.put(code, ent); success++ } else fail++
                }
            }
        }
        // 拉取失败的标的保留旧缓存（若有）
        if (cache != null) {
            val it = cache.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (!fresh.has(k)) fresh.put(k, cache.opt(k))
            }
        }
        if (success == 0 && fresh.length() == 0) {
            return@withContext SyncResult(false, 0, null,
                "⚠ ETF 行情拉取全部失败（$fail 标的）。请检查网络后重试，或走 PC 桥")
        }
        val ok = writeCache(fresh)
        val asOf = lastDateOf(fresh, IDX_300.first) ?: ""
        val msg = if (ok)
            "✔ 本地 ETF 行情已同步至 $asOf（成功 $success，失败 $fail）"
        else
            "⚠ 行情已拉取但写入失败（成功 $success，失败 $fail）"
        SyncResult(ok, fresh.length(), asOf, msg)
    }

    /** 单标的全量拉取：从 BEG 向今，腾讯 640/页 向前翻页直到覆盖 BEG。返回 (真实名称, 升序 snaps) */
    private suspend fun fetchFullHistory(code: String): Pair<String, List<JSONObject>> {
        val beg = LocalDate.parse(BEG, DATE_FMT)
        var curEnd = LocalDate.now()
        val merged = LinkedHashMap<String, JSONObject>()   // 按 date 去重保序
        var name = ""
        var guard = 0
        while (guard < 40 && !curEnd.isBefore(beg)) {
            val rows = fetchPage(code, beg, curEnd)
            if (rows.isEmpty()) break
            rows.forEach { merged[it.optString("date")] = it }
            if (name.isBlank()) {
                rows.lastOrNull()?.optString("_name")?.let { if (it.isNotBlank()) name = it }
            }
            if (rows.size < PAGE) break                     // 非满页 → 已回到段首
            val oldest = rows.first().optString("date")     // 升序 → 段内最老一根
            curEnd = LocalDate.parse(oldest, DATE_FMT).minusDays(1)
            guard++
        }
        return Pair(name, merged.values.sortedBy { it.optString("date") })
    }

    /** 探测指数最新交易日：1 次轻请求（最近 [PROBE_DAYS_BACK] 天窗口） */
    private fun probeIndexLatest(): String? {
        val end = LocalDate.now()
        val beg = end.minusDays(PROBE_DAYS_BACK)
        return try {
            val rows = fetchPage(IDX_300.first, beg, end)
            if (rows.isEmpty()) null else rows.last().optString("date")
        } catch (e: Exception) {
            Log.w(TAG, "探测指数最新交易日失败: ${e.message}")
            null
        }
    }

    /** 单页请求：升序返回 {date,open,close,high,low,volume,_name}（_name 为内部名） */
    private fun fetchPage(code: String, beg: LocalDate, end: LocalDate): List<JSONObject> {
        val url = "$FQKLINE?param=$code,day,${beg.format(DATE_FMT)},${end.format(DATE_FMT)},$PAGE,qfq"
        var lastErr: String? = null
        for (attempt in 0..2) {
            try {
                val req = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) { lastErr = "HTTP ${resp.code}"; continue }
                val body = resp.body?.string()
                if (body == null) { lastErr = "空响应"; continue }
                val node = JSONObject(body).optJSONObject("data")?.optJSONObject(code)
                if (node == null) { lastErr = "无数据节点"; continue }
                val rows = node.optJSONArray("qfqday") ?: node.optJSONArray("day")
                if (rows == null) { lastErr = "无 K 线数组"; continue }
                var nm = ""
                node.optJSONObject("qt")?.optJSONArray(code)?.optString(1)?.let { if (it.isNotBlank()) nm = it }
                if (nm.isBlank()) nm = node.optString("name", "")

                val out = ArrayList<JSONObject>(rows.length())
                for (i in 0 until rows.length()) {
                    val p = rows.optJSONArray(i) ?: continue
                    if (p.length() < 6) continue
                    val d = p.optString(0)
                    if (d.isBlank()) continue
                    val s = JSONObject()
                    s.put("date", d)
                    s.put("open", p.optDouble(1, Double.NaN).takeIf { !it.isNaN() } ?: 0.0)
                    s.put("close", p.optDouble(2, Double.NaN).takeIf { !it.isNaN() } ?: 0.0)
                    s.put("high", p.optDouble(3, Double.NaN).takeIf { !it.isNaN() } ?: 0.0)
                    s.put("low", p.optDouble(4, Double.NaN).takeIf { !it.isNaN() } ?: 0.0)
                    s.put("volume", p.optDouble(5, Double.NaN).takeIf { !it.isNaN() } ?: 0.0)
                    if (nm.isNotBlank()) s.put("_name", nm)
                    out.add(s)
                }
                out.sortBy { it.optString("date") }
                return out
            } catch (e: Exception) {
                lastErr = e.message?.take(60) ?: "未知错误"
            }
            Thread.sleep(300L * (attempt + 1))
        }
        throw IllegalStateException("$code 请求失败: $lastErr")
    }

    // ── 小工具 ──
    private fun snapsOf(cache: JSONObject, code: String): JSONArray? =
        cache.optJSONObject(code)?.optJSONArray("snaps")

    private fun lastDateOf(cache: JSONObject?, code: String): String? {
        if (cache == null) return null
        val arr = snapsOf(cache, code) ?: return null
        return if (arr.length() > 0) arr.optJSONObject(arr.length() - 1).optString("date", "") else null
    }

    private fun writeCache(cache: JSONObject): Boolean = try {
        val f = targetFile()
        f.parentFile?.mkdirs()
        f.writeText(cache.toString())
        Log.i(TAG, "已写入 ${f.absolutePath}（${cache.length()} 标的）")
        true
    } catch (e: Exception) {
        Log.w(TAG, "写入 $FILE_NAME 失败: ${e.message}")
        false
    }
}
