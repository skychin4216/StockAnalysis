package com.chin.stockanalysis.stock.data.sources

import android.util.Log
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
 * 板块列表：
 *   https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=50&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2&fid=f3&fs=m:90+t:2+f:!50&fields=f2,f3,f4,f8,f12,f14,f15,f16,f17,f18,f20,f21,f23,f24,f25,f22,f11,f62,f128,f136,f115,f152
 *
 * 板块成分股（以板块代码如 BK0478 获取有色金属成分股）：
 *   https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=50&po=1&np=1&fid=f3&fs=b:BK0478+f:!50&fields=f2,f3,f4,f5,f6,f12,f14,f15,f16,f17,f18&fltt=2
 *
 * ### 已知板块代码
 * | 板块名 | 东方财富代码 |
 * |--------|------------|
 * | 有色金属 | BK0478 |
 * | 钢铁 | BK0470 |
 * | 煤炭 | BK0421 |
 * | 银行 | BK0475 |
 * | 白酒 | BK1078 |
 * | 半导体 | BK0447 |
 * | 医药生物 | BK0465 |
 * | 新能源汽车 | BK0811 |
 * | 军工 | BK0460 |
 * | 房地产 | BK0451 |
 * | 化工 | BK0419 |
 * | 电力 | BK0427 |
 */
class EastMoneySectorSource {

    private val client = HttpClientProvider.realtimeClient
    private val tag = "SectorSource"

    companion object {
        // 板块名称 → 东方财富板块代码
        val SECTOR_CODE_MAP: Map<String, String> = mapOf(
            "有色金属" to "BK0478",
            "有色" to "BK0478",
            "钢铁" to "BK0470",
            "煤炭" to "BK0421",
            "银行" to "BK0475",
            "白酒" to "BK1078",
            "食品饮料" to "BK0432",
            "半导体" to "BK0447",
            "芯片" to "BK0447",
            "集成电路" to "BK0447",
            "医药生物" to "BK0465",
            "医药" to "BK0465",
            "生物医药" to "BK0465",
            "新能源汽车" to "BK0811",
            "新能源" to "BK0811",
            "军工" to "BK0460",
            "国防军工" to "BK0460",
            "房地产" to "BK0451",
            "化工" to "BK0419",
            "电力" to "BK0427",
            "证券" to "BK0473",
            "保险" to "BK0474",
            "汽车" to "BK0481",
            "家电" to "BK0436",
            "电子" to "BK0448",
            "通信" to "BK0480",
            "计算机" to "BK0446",
            "传媒" to "BK0420",
            "互联网" to "BK0803",
            "农业" to "BK0403",
            "建筑" to "BK0414",
            "交通运输" to "BK0437",
            "商业零售" to "BK0467",
            "纺织服装" to "BK0431",
            "旅游" to "BK0463",
            "造纸" to "BK0485",
            "机械" to "BK0441",
            "光伏" to "BK1026",
            "储能" to "BK0816",
            "人工智能" to "BK1064",
            "AI" to "BK1064",
            "算力" to "BK1064",
            "大模型" to "BK1064",
            "稀土" to "BK1045",
            "氢能" to "BK1041",
            "碳中和" to "BK1045",
        )
    }

    /**
     * 根据板块名称查找对应的东方财富板块代码
     */
    fun findSectorCode(sectorName: String): String? {
        return SECTOR_CODE_MAP.entries.firstOrNull { (key, _) ->
            sectorName.contains(key, ignoreCase = true)
        }?.value
    }

    /**
     * 获取板块成分股列表（按涨跌幅/市值排序），返回股票代码
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
        val url = "https://push2.eastmoney.com/api/qt/clist/get" +
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
     * @return Pair<板块中文名, 股票代码列表>，找不到板块时返回 null
     */
    fun fetchByName(
        sectorName: String,
        topN: Int = 20,
        excludeKcb: Boolean = true,
        excludeCyb: Boolean = false,
        minMarketCapBillion: Long = 0L
    ): Pair<String, List<SectorStock>>? {
        val code = findSectorCode(sectorName) ?: run {
            Log.w(tag, "Unknown sector: $sectorName")
            return null
        }
        val sectorFullName = SECTOR_CODE_MAP.entries.firstOrNull { it.value == code }?.key ?: sectorName
        val stocks = fetchSectorComponents(code, topN, excludeKcb, excludeCyb, minMarketCapBillion)
        Log.d(tag, "fetchByName: $sectorName → $code → ${stocks.size} stocks")
        return Pair(sectorFullName, stocks)
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
                .header("Referer", "https://quote.eastmoney.com/")
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
