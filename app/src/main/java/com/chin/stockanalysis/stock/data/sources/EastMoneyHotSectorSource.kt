package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject

class EastMoneyHotSectorSource {
    private val client = HttpClientProvider.realtimeClient
    private val tag = "HotPool"

    companion object {
        private val INDEX_CODES = listOf(
            "1.000001" to "上证指数", "0.399001" to "深证成指",
            "0.399006" to "创业板指", "1.000300" to "沪深300",
            "100.KS11" to "韩国KOSPI", "100.NDX" to "纳斯达克",
            "100.HSI" to "恒生指数", "1.510050" to "ETF", "1.159915" to "ETF"
        )
        @Volatile var industrySectors: List<HotSector> = emptyList()
        @Volatile var conceptSectors: List<HotSector> = emptyList()
        @Volatile var indexSectors: List<HotSector> = emptyList()
        @Volatile var globalIndices: List<GlobalIndex> = emptyList()

        private var poolJob: Job? = null; private var sortJob: Job? = null
        private var started = false

        fun startPoolScheduler(scope: CoroutineScope) {
            if (started) return; started = true
            val source = EastMoneyHotSectorSource()
            poolJob = scope.launch(Dispatchers.IO) {
                // 立即拉取第一次
                try { source.refreshPool() } catch (_: Exception) {}
                while (isActive) { delay(3 * 60_000L); try { source.refreshPool() } catch (_: Exception) {} }
            }
            sortJob = scope.launch(Dispatchers.IO) {
                delay(3000) // 等 poolJob 完成
                while (isActive) { try { source.resortPool() } catch (_: Exception) {}; delay(10 * 60_000L) }
            }
            scope.launch(Dispatchers.IO) {
                delay(1000)
                try { globalIndices = source.fetchIndexRaw() } catch (_: Exception) {}
                while (isActive) { delay(3 * 60_000L); try { globalIndices = source.fetchIndexRaw() } catch (_: Exception) {} }
            }
            Log.i("HotPool", "🚀 池调度已启动 (池3min/排序10min)")
        }
        fun stopPoolScheduler() { poolJob?.cancel(); sortJob?.cancel(); started = false }
    }

    data class HotSector(
        val code: String, val name: String, val changePercent: Double,
        val sectorIndex: Double, val hotScore: Double = 0.0,
        val turnoverRate: Double = 0.0, val mainNetInflow: Double = 0.0,
        val top1StockName: String = "", val top1StockCode: String = "",
        val top1ChangePercent: Double = 0.0, val compositeScore: Double = 0.0,
        val sectorType: Int = 2
    )
    data class GlobalIndex(val code: String, val name: String, val price: Double, val changePercent: Double, val changeAmount: Double)
    data class LeaderStock(
        val code: String, val name: String, val price: Double, val changePercent: Double,
        val turnoverRate: Double, val mainNetInflow: Double, val marketCap: Double = 0.0,
        val isBoard: Boolean = false, val limitDays: Int = 0, val threeDayInflow: Double = 0.0
    )

    /** 板块名称中常见的编号后缀，需要剥离后去重合并 */
    private val STRIP_SUFFIX_REGEX = Regex("[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅠⅠⅠⅠⅠⅠ]|[0-9]+|\\([^)]*\\)|（[^）]*）$")
    private val BLACKLIST_KW = listOf("房地产","足球","彩票","赛马","博彩","电竞","地摊","盲盒","宠物","网红","直播","ST股","ST板","退市","壳资源","预亏","预盈")
    private val MERGE_KW: List<Pair<String,String>> = listOf(
        "钼" to "有色金属","钨" to "有色金属","钴" to "有色金属","镍" to "有色金属",
        "锑" to "有色金属","锗" to "有色金属","镓" to "有色金属",
        "铌" to "稀有小金属","钽" to "稀有小金属","铍" to "稀有小金属",
        "铬" to "有色金属","锰" to "有色金属","铟" to "稀有小金属",
        "锆" to "稀有小金属","铪" to "稀有小金属",
        "非金属材料" to "化工","有机硅" to "化工","氟化工" to "化工",
        "钛白粉" to "化工","氦气" to "化工","氖气" to "化工","钛" to "有色金属",
    )
    private fun computeScore(c: Double, t: Double, i: Double, top: Double): Double {
        val tn = (t / 20.0).coerceIn(-1.0, 1.0) * 10
        val inN = (i / 50.0).coerceIn(-1.0, 1.0) * 10
        return c * 0.45 + tn * 0.20 + inN * 0.20 + top * 0.15
    }
    private fun stripSuffix(name: String): String {
        return name.replace(STRIP_SUFFIX_REGEX, "").trim().replace("·$".toRegex(), "").replace("\\.$".toRegex(), "")
    }
    private fun filterAndMerge(raw: List<HotSector>): List<HotSector> {
        val filtered = raw
            .filter { s -> !BLACKLIST_KW.any { s.name.contains(it, true) } }
            .map { s -> MERGE_KW.firstOrNull { s.name.contains(it.first, true) }?.let { s.copy(name = "${it.second}·${s.name}") } ?: s }
        // 剥离编码后缀（如Ⅰ/Ⅱ/III）后按名称去重，保留compositeScore最高的那条
        return filtered
            .groupBy { stripSuffix(it.name) }
            .map { (_, group) -> group.maxByOrNull { it.compositeScore } ?: group.first() }
    }

    // ============ 全量池 ============
    private var rawPool: List<HotSector> = emptyList()

    private suspend fun refreshPool() {
        val all = mutableListOf<HotSector>()
        for (type in listOf(2, 3)) {
            try {
                val url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                    "pn=1&pz=50&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281" +
                    "&fltt=2&invt=2&fid=f3&fs=m:90+t:${type}+f:!50" +
                    "&fields=f2,f3,f5,f8,f12,f14,f62,f128,f140,f124"
                val req = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) continue
                val diffs = JSONObject(resp.body?.string() ?: "").optJSONObject("data")?.optJSONArray("diff") ?: continue
                for (i in 0 until diffs.length()) {
                    val item = diffs.getJSONObject(i)
                    val c = item.optDouble("f3", 0.0); val t = item.optDouble("f8", 0.0)
                    val inflow = item.optDouble("f62", 0.0) / 1_0000_0000; val top = item.optDouble("f124", 0.0)
                    all.add(HotSector(code = item.optString("f12", ""), name = item.optString("f14", ""),
                        changePercent = c, sectorIndex = item.optDouble("f2", 0.0),
                        hotScore = Math.abs(c) + t * 0.5 + (inflow / 10.0).coerceIn(0.0, 5.0),
                        turnoverRate = t, mainNetInflow = inflow,
                        top1StockName = item.optString("f128", ""),
                        top1StockCode = item.optString("f140", "").ifEmpty { item.optString("f136", "") },
                        top1ChangePercent = top, compositeScore = computeScore(c, t, inflow, top), sectorType = type))
                }
            } catch (_: Exception) {}
        }
        if (all.isNotEmpty()) { rawPool = filterAndMerge(all); resortPool(); Log.i(tag, "🔄 池刷新: ${rawPool.size}") }
    }

    private fun resortPool() {
        if (rawPool.isEmpty()) return
        industrySectors = rawPool.filter { it.sectorType == 2 }.sortedByDescending { it.compositeScore }.take(20)
        conceptSectors = rawPool.filter { it.sectorType == 3 }.sortedByDescending { it.compositeScore }.take(20)
        indexSectors = rawPool.filter { it.sectorType == 1 || it.name.contains("指数") }.sortedByDescending { it.compositeScore }.take(20)
        Log.i(tag, "📊 排序: 行业${industrySectors.size} 概念${conceptSectors.size}")
    }

    private suspend fun fetchIndexRaw(): List<GlobalIndex> {
        try {
            val codes = INDEX_CODES.joinToString(",") { it.first }
            val url = "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2&fields=f2,f3,f4,f12,f14&secids=$codes"
            val req = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return emptyList()
            val diffs = JSONObject(resp.body?.string() ?: "").optJSONObject("data")?.optJSONArray("diff") ?: return emptyList()
            return (0 until diffs.length()).map { i ->
                val item = diffs.getJSONObject(i); val code = item.optString("f12", "")
                val mapped = INDEX_CODES.firstOrNull { it.first.endsWith(code) }?.second ?: ""
                GlobalIndex(code = code, name = mapped.ifEmpty { item.optString("f14", "") },
                    price = item.optDouble("f2", 0.0), changePercent = item.optDouble("f3", 0.0), changeAmount = item.optDouble("f4", 0.0))
            }
        } catch (_: Exception) { return emptyList() }
    }

    /** Fallback：按类型直接获取（当缓存为空时 UI 触发） */
    fun fetchSectorsByTypeDirect(type: Int, topN: Int = 20): List<HotSector> {
        return try {
            val url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                "pn=1&pz=$topN&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281" +
                "&fltt=2&invt=2&fid=f3&fs=m:90+t:${type}+f:!50" +
                "&fields=f2,f3,f5,f8,f12,f14,f62,f128,f140,f124"
            val req = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return emptyList()
            val diffs = JSONObject(resp.body?.string() ?: "").optJSONObject("data")?.optJSONArray("diff") ?: return emptyList()
            val result = (0 until diffs.length()).map { i ->
                val item = diffs.getJSONObject(i)
                val c = item.optDouble("f3", 0.0); val t = item.optDouble("f8", 0.0)
                val inflow = item.optDouble("f62", 0.0) / 1_0000_0000; val top = item.optDouble("f124", 0.0)
                HotSector(code = item.optString("f12", ""), name = item.optString("f14", ""),
                    changePercent = c, sectorIndex = item.optDouble("f2", 0.0),
                    hotScore = Math.abs(c) + t * 0.5 + (inflow / 10.0).coerceIn(0.0, 5.0),
                    turnoverRate = t, mainNetInflow = inflow,
                    top1StockName = item.optString("f128", ""),
                    top1StockCode = item.optString("f140", "").ifEmpty { item.optString("f136", "") },
                    top1ChangePercent = top, compositeScore = computeScore(c, t, inflow, top), sectorType = type)
            }
            filterAndMerge(result).sortedByDescending { it.compositeScore }.take(topN)
        } catch (_: Exception) { emptyList() }
    }

    fun fetchSectorLeaders(blockCode: String, topN: Int = 10): List<LeaderStock> {
        return try {
            val url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                "pn=1&pz=$topN&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281" +
                "&fltt=2&invt=2&fid=f20&fs=b:${blockCode}+f:!50" +
                "&fields=f2,f3,f8,f12,f14,f20,f62,f184,f192"
            val req = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return emptyList()
            val diffs = JSONObject(resp.body?.string() ?: "").optJSONObject("data")?.optJSONArray("diff") ?: return emptyList()
            (0 until diffs.length()).map { i ->
                val item = diffs.getJSONObject(i)
                LeaderStock(code = item.optString("f12", ""), name = item.optString("f14", ""),
                    price = item.optDouble("f2", 0.0), changePercent = item.optDouble("f3", 0.0),
                    turnoverRate = item.optDouble("f8", 0.0),
                    mainNetInflow = item.optDouble("f62", 0.0) / 1_0000_0000,
                    marketCap = item.optDouble("f20", 0.0) / 1_0000_0000,
                    isBoard = (item.optDouble("f3", 0.0) >= 9.8),
                    limitDays = item.optInt("f192", 0),
                    threeDayInflow = item.optDouble("f184", 0.0) / 1_0000_0000)
            }
        } catch (e: Exception) { emptyList() }
    }
}