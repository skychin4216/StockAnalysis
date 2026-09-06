package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.HttpClientProvider
import okhttp3.Request
import org.json.JSONObject

/**
 * ## 东方财富板块成分股数据源（方案B）
 *
 * 通过东方财富行情 API 获取指定板块（行业/概念）的成分股列表，
 * 用于处理"有色金属前20的股票"这类动态板块查询。
 *
 * ### API 接口
 * 板块清单（行业 + 概念，运行时解析名称→代码用）：
 *   行业：https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=6000&fs=m:90+t:2+f:!50&fields=f12,f14
 *   概念：https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=6000&fs=m:90+t:3+f:!50&fields=f12,f14
 * 板块成分股（如按解析出的代码获取成分股）：
 *   https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=100&fs=b:BK0478+f:!50&fields=...
 *
 * ### ⚠️ 为什么不再硬编码板块代码（2026-09 事故记录）
 * 旧版维护一张"板块名 → BK 代码"静态表，但东财板块代码会随板块调整漂移，
 * 导致整批成分错写进 sector_stocks：
 *   - "稀土"/"碳中和" 都指向 BK1045 → 实为【房地产服务】，sz000560 我爱我家 被错标成"稀土"
 *   - "钢铁" BK0470 → 实为【造纸印刷】；"煤炭" BK0421 → 实为【铁路公路】
 *   - "半导体" BK0447 → 实为【互联网服务】；"AI/算力/大模型" BK1064 → 实为【东数西算】
 *   - "光伏" BK1026 → 实为【调味品】；"白酒" BK1078 → 实为【肝炎概念】…
 * 故代码一律在运行时从东财板块清单解析（[resolveSector]），静态表仅保留查询别名。
 */
class EastMoneySectorSource {

    private val client = HttpClientProvider.realtimeClient
    private val tag = "SectorSource"

    companion object {
        /**
         * 常见板块中文名/别名（用户/AI 高频查询词）。
         * 仅用于：① ThemeStockService 从用户输入中截取板块词；
         * ② MainActivity 预热 sector_stocks 的遍历集合。
         * 不再保存 BK 代码 —— 板块代码全部运行时从东财板块清单解析。
         */
        val SECTOR_KEYWORDS: List<String> = listOf(
            "有色金属", "有色", "钢铁", "煤炭", "银行", "白酒", "食品饮料",
            "半导体", "芯片", "集成电路",
            "医药生物", "医药", "生物医药", "新能源汽车", "新能源",
            "军工", "国防军工", "房地产", "化工", "电力", "证券", "保险",
            "汽车", "家电", "电子", "通信", "计算机", "传媒", "互联网",
            "农业", "建筑", "交通运输", "商业零售", "纺织服装", "旅游", "造纸",
            "机械", "光伏", "储能", "人工智能", "AI", "算力", "大模型",
            "稀土", "氢能", "碳中和"
        )

        private data class BoardRef(val code: String, val name: String)

        private val codeBookMutex = Any()
        private var boardListCache: List<BoardRef>? = null
        private var boardListTime = 0L

        /** 板块清单进程内缓存时长（6 小时） */
        private const val CODE_BOOK_TTL = 6 * 3600 * 1000L

        /**
         * 在板块清单中按"精确 → 官方名包含查询词 → 查询词包含官方名"查找，
         * 返回 (板块代码, 官方板块名)；清单拉取失败/未命中返回 null。
         */
        fun resolveSectorFromCodeBook(sectorName: String): Pair<String, String>? {
            val trimmed = sectorName.trim()
            if (trimmed.isEmpty()) return null
            val boards = boardListOrFetch()
            val exact = trimmed.replace("板块", "").replace("概念", "").trim()
            boards.firstOrNull { it.name == trimmed || it.name == exact }?.let {
                return it.code to it.name
            }
            boards.firstOrNull { it.name.contains(trimmed) }?.let { return it.code to it.name }
            boards.firstOrNull { trimmed.contains(it.name) }?.let { return it.code to it.name }
            Log.w("SectorSource", "板块清单中未找到: $sectorName（当前共 ${boards.size} 个板块）")
            return null
        }

        /** 拉取行业 + 概念板块清单（进程内缓存 TTL 6h） */
        private fun boardListOrFetch(): List<BoardRef> {
            synchronized(codeBookMutex) {
                val now = System.currentTimeMillis()
                if (boardListCache != null && now - boardListTime < CODE_BOOK_TTL) {
                    return boardListCache!!
                }
                val fresh = mutableListOf<BoardRef>()
                for (fs in listOf("m:90+t:2+f:!50", "m:90+t:3+f:!50")) {
                    val url = DataConfig.eastmoneyPush2Api("/clist/get") +
                            "?pn=1&pz=6000&po=1&np=1&fltt=2&invt=2&fid=f12" +
                            "&fs=" + fs +
                            "&fields=f12,f14"
                    val body = fetchBoardListBody(url) ?: continue
                    runCatching {
                        val diff = JSONObject(body).optJSONObject("data")
                            ?.optJSONArray("diff") ?: return@runCatching
                        for (i in 0 until diff.length()) {
                            val o = diff.optJSONObject(i) ?: continue
                            fresh.add(BoardRef(o.optString("f12"), o.optString("f14")))
                        }
                    }
                }
                if (fresh.isNotEmpty()) {
                    boardListCache = fresh
                    boardListTime = now
                    Log.i("SectorSource", "板块清单已加载: ${fresh.size} 个（行业+概念）")
                }
                return fresh
            }
        }

        private fun fetchBoardListBody(url: String): String? {
            return try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", DataConfig.eastmoneyQuote)
                    .build()
                val response = HttpClientProvider.realtimeClient.newCall(request).execute()
                if (!response.isSuccessful) { Log.w("SectorSource", "HTTP ${response.code}"); return null }
                response.body?.string()
            } catch (e: Exception) {
                Log.w("SectorSource", "板块清单请求失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 解析板块名称 → (板块代码, 官方板块名)。代码来自运行时板块清单，
     * 避免静态 BK 代码漂移导致的整批错配。
     */
    fun resolveSector(sectorName: String): Pair<String, String>? =
        resolveSectorFromCodeBook(sectorName)

    /**
     * 根据板块名称查找对应的东方财富板块代码（运行时解析）。
     */
    fun findSectorCode(sectorName: String): String? = resolveSector(sectorName)?.first

    /**
     * 获取板块成分股列表（按市值排序），返回股票代码
     *
     * @param sectorCode 东方财富板块代码（如 BK0478）
     * @param topN 取前 N 只（默认 20）
     * @param excludeKcb 是否排除科创板（688xxx）
     * @param excludeCyb 是否排除创业板（300xxx）
     * @param minMarketCapBillion 最小市值（亿元，0 表示不过滤）
     * @return 股票代码列表（sh/sz 前缀格式）
     */
    fun fetchSectorComponents(
        sectorCode: String,
        topN: Int = 20,
        excludeKcb: Boolean = true,
        excludeCyb: Boolean = false,
        minMarketCapBillion: Long = 0L
    ): List<SectorStock> {
        // 每页取 100 只，按市值从大到小排序（fid=f20, po=1=降序）
        val url = DataConfig.eastmoneyPush2Api("/clist/get") +
                "?pn=1&pz=100&po=1&np=1&fltt=2&invt=2" +
                "&fid=f20" +   // 按市值排序
                "&fs=b:$sectorCode+f:!50" +  // 板块成分，排除ST
                "&fields=f2,f3,f4,f5,f6,f12,f14,f15,f16,f17,f18,f20,f23"

        return try {
            val body = executeRequest(url) ?: return emptyList()
            parseSectorComponents(body, topN, excludeKcb, excludeCyb, minMarketCapBillion)
        } catch (e: Exception) {
            Log.e(tag, "fetchSectorComponents error for $sectorCode: ${e.message}")
            emptyList()
        }
    }

    /**
     * 便捷方法：根据板块名称直接获取成分股代码列表
     *
     * @param sectorName 板块名称（如"有色金属"）
     * @return Pair<官方板块名, 股票代码列表>；板块无法解析时返回 null
     */
    fun fetchByName(
        sectorName: String,
        topN: Int = 20,
        excludeKcb: Boolean = true,
        excludeCyb: Boolean = false,
        minMarketCapBillion: Long = 0L
    ): Pair<String, List<SectorStock>>? {
        val resolved = resolveSector(sectorName) ?: run {
            Log.w(tag, "Unknown sector: $sectorName")
            return null
        }
        val (code, officialName) = resolved
        val stocks = fetchSectorComponents(code, topN, excludeKcb, excludeCyb, minMarketCapBillion)
        Log.d(tag, "fetchByName: $sectorName → $code($officialName) → ${stocks.size} stocks")
        return Pair(officialName, stocks)
    }

    // ════════════════════════════════════════
    // 解析
    // ════════════════════════════════════════

    private fun parseSectorComponents(
        body: String,
        topN: Int,
        excludeKcb: Boolean,
        excludeCyb: Boolean,
        minMarketCapBillion: Long
    ): List<SectorStock> {
        return runCatching {
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return emptyList()
            val diff = data.optJSONArray("diff") ?: return emptyList()

            val result = mutableListOf<SectorStock>()
            for (i in 0 until diff.length()) {
                val item = diff.optJSONObject(i) ?: continue
                val rawCode = item.optString("f12")
                val code = normalizeBack(rawCode)

                // 过滤科创板
                if (excludeKcb && (rawCode.startsWith("688") || rawCode.startsWith("689"))) continue
                // 过滤创业板
                if (excludeCyb && (rawCode.startsWith("300") || rawCode.startsWith("301"))) continue
                // 过滤北交所
                if (rawCode.startsWith("8") || rawCode.startsWith("4")) continue

                val price = item.optDoubleSafe("f2")
                if (price <= 0) continue

                // 市值过滤（f20 单位为元，转换为亿元）
                val marketCapYuan = item.optDoubleSafe("f20")
                val marketCapBillion = (marketCapYuan / 100_000_000).toLong()
                if (minMarketCapBillion > 0 && marketCapBillion < minMarketCapBillion) continue

                result.add(
                    SectorStock(
                        code = code,
                        name = item.optString("f14"),
                        price = price,
                        changePercent = item.optDoubleSafe("f3"),
                        changeAmount = item.optDoubleSafe("f4"),
                        volume = (item.optDoubleSafe("f5") * 100).toLong(),
                        amount = item.optDoubleSafe("f6") * 10000,
                        high = item.optDoubleSafe("f15"),
                        low = item.optDoubleSafe("f16"),
                        open = item.optDoubleSafe("f17"),
                        yestClose = item.optDoubleSafe("f18"),
                        marketCapBillion = marketCapBillion,
                        peRatio = item.optDoubleSafe("f23")
                    )
                )

                if (result.size >= topN) break
            }
            result
        }.getOrDefault(emptyList())
    }

    // ════════════════════════════════════════
    // 格式化（注入 AI prompt）
    // ════════════════════════════════════════

    /**
     * 将板块成分股格式化为 AI prompt 文本
     */
    fun formatForPrompt(sectorName: String, stocks: List<SectorStock>): String {
        if (stocks.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("\n【${sectorName}板块实时行情数据（按市值排序）】")
        sb.appendLine("共 ${stocks.size} 只股票（已过滤科创板，按市值从大到小）")
        sb.appendLine()

        stocks.forEachIndexed { index, s ->
            val arrow = when {
                s.changePercent > 0 -> "📈"
                s.changePercent < 0 -> "📉"
                else -> "➡️"
            }
            val capStr = if (s.marketCapBillion > 0) "  市值: ${s.marketCapBillion}亿" else ""
            sb.appendLine("${index + 1}. $arrow ${s.name} (${s.code.takeLast(6)})" +
                    "  现价: ${"%.2f".format(s.price)}元" +
                    "  涨跌: ${if (s.changePercent > 0) "+" else ""}${"%.2f".format(s.changePercent)}%" +
                    "  成交量: ${s.volume / 10000}万手$capStr")
        }
        return sb.toString()
    }

    // ════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════

    private fun executeRequest(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", DataConfig.eastmoneyQuote)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { Log.w(tag, "HTTP ${response.code}"); return null }
            response.body?.string()
        } catch (e: Exception) {
            Log.w(tag, "executeRequest error: ${e.message}")
            null
        }
    }

    private fun normalizeBack(rawCode: String): String {
        return when {
            rawCode.startsWith("6") || rawCode.startsWith("9") -> "sh$rawCode"
            rawCode.startsWith("4") || rawCode.startsWith("8") -> "bj$rawCode"
            else -> "sz$rawCode"
        }
    }

    private fun JSONObject.optDoubleSafe(key: String): Double {
        val value = opt(key) ?: return 0.0
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}

// ═══════════════════════════════════════════════════════
// 数据类
// ═══════════════════════════════════════════════════════

/**
 * 板块成分股数据
 */
data class SectorStock(
    val code: String,
    val name: String,
    val price: Double,
    val changePercent: Double,
    val changeAmount: Double,
    val volume: Long,
    val amount: Double,
    val high: Double,
    val low: Double,
    val open: Double,
    val yestClose: Double,
    val marketCapBillion: Long,   // 市值（亿元）
    val peRatio: Double           // 市盈率
)
