package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.stock.data.HttpClientProvider
import okhttp3.Request
import org.json.JSONObject

/**
 * ## 东方财富盘口买卖挂单数据源（Level-1 五档）
 *
 * 接口：东方财富行情推送 API（无需鉴权，浏览器级接口）
 * URL 示例：
 *   https://push2.eastmoney.com/api/qt/stock/get?secid=1.600519&fields=f31,f32,f33,f34,f35,f36,f37,f38,f39,f40,f41
 *
 * ### 字段说明
 * | 字段 | 含义 |
 * |------|------|
 * | f31 | 卖五价 | f32 | 卖五量（手）
 * | f33 | 卖四价 | f34 | 卖四量
 * | f35 | 卖三价 | f36 | 卖三量
 * | f37 | 卖二价 | f38 | 卖二量
 * | f39 | 卖一价 | f40 | 卖一量
 * | f41 | 买一价 | f42 | 买一量
 * | f43 | 买二价 | f44 | 买二量
 * | f45 | 买三价 | f46 | 买三量
 * | f47 | 买四价 | f48 | 买四量
 * | f49 | 买五价 | f50 | 买五量
 *
 * ### 低吸评级算法
 * ```
 * 总买手 = 买一量 + 买二量 + 买三量 + 买四量 + 买五量
 * 总卖手 = 卖一量 + 卖二量 + 卖三量 + 卖四量 + 卖五量
 * 买卖比 = 总买手 / 总卖手
 *
 * 买卖比 ≥ 1.5 → 🟢 强烈低吸信号
 * 买卖比 ≥ 1.2 → 🟡 低吸观察
 * 买卖比 < 1.0 → 🔴 暂不介入
 * ```
 */
class EastMoneyBidAskSource {

    private val client = HttpClientProvider.realtimeClient
    private val tag = "BidAskSource"

    companion object {
        // 所有五档字段
        private const val BID_ASK_FIELDS =
            "f31,f32,f33,f34,f35,f36,f37,f38,f39,f40,f41,f42,f43,f44,f45,f46,f47,f48,f49,f50,f2,f12,f14,f3"
    }

    /**
     * 批量获取多只股票的五档盘口数据
     *
     * @param codes 股票代码列表（sh/sz 前缀格式）
     * @return Map<code, BidAskData>
     */
    fun fetchBidAsk(codes: List<String>): Map<String, BidAskData> {
        if (codes.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, BidAskData>()

        // 东方财富单股接口，每次查一只（也可批量，但单股精度更高）
        codes.chunked(10).forEach { batch ->
            val secids = batch.joinToString(",") { toSecId(it) }
            val url = "https://push2.eastmoney.com/api/qt/ulist.np/get" +
                    "?fltt=2&invt=2&secids=$secids&fields=$BID_ASK_FIELDS"
            try {
                val body = executeRequest(url) ?: return@forEach
                parseBidAskBatch(body).forEach { data -> result[data.code] = data }
            } catch (e: Exception) {
                Log.w(tag, "fetchBidAsk batch error: ${e.message}")
            }
        }

        return result
    }

    /**
     * 获取单只股票的五档盘口数据
     */
    fun fetchSingle(code: String): BidAskData? {
        val secid = toSecId(code)
        val url = "https://push2.eastmoney.com/api/qt/stock/get" +
                "?secid=$secid&fields=$BID_ASK_FIELDS&fltt=2"
        return try {
            val body = executeRequest(url) ?: return null
            parseSingleBidAsk(body, code)
        } catch (e: Exception) {
            Log.w(tag, "fetchSingle error for $code: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════
    // 解析
    // ════════════════════════════════════════

    private fun parseBidAskBatch(body: String): List<BidAskData> {
        return runCatching {
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return emptyList()
            val diff = data.optJSONArray("diff") ?: return emptyList()

            buildList {
                for (i in 0 until diff.length()) {
                    val item = diff.optJSONObject(i) ?: continue
                    val rawCode = item.optString("f12")
                    val code = normalizeBack(rawCode)
                    parseBidAskItem(item, code)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseSingleBidAsk(body: String, originalCode: String): BidAskData? {
        return runCatching {
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return null
            parseBidAskItem(data, originalCode)
        }.getOrNull()
    }

    private fun parseBidAskItem(item: JSONObject, code: String): BidAskData? {
        val currentPrice = item.optDoubleSafe("f2")
        if (currentPrice <= 0) return null

        // 卖档（卖五 → 卖一，价格从高到低）
        val ask5 = BidAskLevel(item.optDoubleSafe("f31"), item.optLongSafe("f32"))
        val ask4 = BidAskLevel(item.optDoubleSafe("f33"), item.optLongSafe("f34"))
        val ask3 = BidAskLevel(item.optDoubleSafe("f35"), item.optLongSafe("f36"))
        val ask2 = BidAskLevel(item.optDoubleSafe("f37"), item.optLongSafe("f38"))
        val ask1 = BidAskLevel(item.optDoubleSafe("f39"), item.optLongSafe("f40"))

        // 买档（买一 → 买五，价格从高到低）
        val bid1 = BidAskLevel(item.optDoubleSafe("f41"), item.optLongSafe("f42"))
        val bid2 = BidAskLevel(item.optDoubleSafe("f43"), item.optLongSafe("f44"))
        val bid3 = BidAskLevel(item.optDoubleSafe("f45"), item.optLongSafe("f46"))
        val bid4 = BidAskLevel(item.optDoubleSafe("f47"), item.optLongSafe("f48"))
        val bid5 = BidAskLevel(item.optDoubleSafe("f49"), item.optLongSafe("f50"))

        val totalBid = bid1.volume + bid2.volume + bid3.volume + bid4.volume + bid5.volume
        val totalAsk = ask1.volume + ask2.volume + ask3.volume + ask4.volume + ask5.volume

        val ratio = if (totalAsk > 0) totalBid.toDouble() / totalAsk.toDouble() else 0.0

        // 低吸评级
        val rating = when {
            ratio >= 1.5 -> BidAskRating.STRONG_BUY    // 买手明显大于卖手
            ratio >= 1.2 -> BidAskRating.WATCH          // 买手略大
            ratio in 0.8..1.2 -> BidAskRating.NEUTRAL  // 均衡
            else -> BidAskRating.AVOID                   // 卖手多
        }

        return BidAskData(
            code = code,
            name = item.optString("f14", ""),
            currentPrice = currentPrice,
            changePercent = item.optDoubleSafe("f3"),
            bids = listOf(bid1, bid2, bid3, bid4, bid5),
            asks = listOf(ask1, ask2, ask3, ask4, ask5),
            totalBidVolume = totalBid,
            totalAskVolume = totalAsk,
            bidAskRatio = ratio,
            rating = rating,
            timestamp = System.currentTimeMillis()
        )
    }

    // ════════════════════════════════════════
    // 格式化输出（用于注入 AI prompt）
    // ════════════════════════════════════════

    /**
     * 将盘口数据格式化为 AI prompt 文本
     *
     * 示例输出：
     * ```
     * 📊 西部材料 (002149) | 当前: 12.35元 | 涨幅: +2.3%
     * 盘口五档买卖手数：
     *   买盘合计: 18,420 手  卖盘合计: 9,210 手  买卖比: 2.0
     *   🟢 评级: 强烈低吸信号（买手大于卖手 2.0 倍）
     *   买一: 12.35 × 3,200手  |  卖一: 12.36 × 1,800手
     *   买二: 12.34 × 4,100手  |  卖二: 12.37 × 2,100手
     *   ...
     * ```
     */
    fun formatForPrompt(dataMap: Map<String, BidAskData>): String {
        if (dataMap.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("\n【尾盘盘口买卖挂单数据（五档）】")

        for ((_, data) in dataMap) {
            val ratingEmoji = when (data.rating) {
                BidAskRating.STRONG_BUY -> "🟢"
                BidAskRating.WATCH -> "🟡"
                BidAskRating.NEUTRAL -> "⚪"
                BidAskRating.AVOID -> "🔴"
            }
            val ratingText = when (data.rating) {
                BidAskRating.STRONG_BUY -> "强烈低吸信号（买手显著大于卖手）"
                BidAskRating.WATCH -> "低吸观察（买手略大于卖手）"
                BidAskRating.NEUTRAL -> "买卖均衡，观望"
                BidAskRating.AVOID -> "卖手偏多，暂不介入"
            }

            sb.appendLine("─────────────────────────────")
            sb.appendLine("📊 ${data.name} (${data.code.takeLast(6)}) | 现价: ${"%.2f".format(data.currentPrice)}元 | 涨幅: ${"%.2f".format(data.changePercent)}%")
            sb.appendLine("  买盘合计: ${"%,d".format(data.totalBidVolume)} 手  卖盘合计: ${"%,d".format(data.totalAskVolume)} 手  买卖比: ${"%.2f".format(data.bidAskRatio)}")
            sb.appendLine("  $ratingEmoji 评级: $ratingText")
            sb.appendLine("  五档明细:")

            val asks = data.asks.reversed() // 卖五→卖一，价格从高到低排列
            asks.forEachIndexed { i, ask ->
                val level = 5 - i
                if (ask.price > 0)
                    sb.appendLine("    卖$level: ${"%.2f".format(ask.price)} × ${"%,d".format(ask.volume)}手")
            }
            sb.appendLine("  - - - 当前价: ${"%.2f".format(data.currentPrice)} - - -")
            data.bids.forEachIndexed { i, bid ->
                val level = i + 1
                if (bid.price > 0)
                    sb.appendLine("    买$level: ${"%.2f".format(bid.price)} × ${"%,d".format(bid.volume)}手")
            }
        }

        sb.appendLine("─────────────────────────────")
        sb.appendLine("⚠️ 买卖挂单数据为当前时刻快照，请结合趋势综合判断，不构成投资建议。")
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
            Log.w(tag, "executeRequest: ${e.message}")
            null
        }
    }

    private fun toSecId(code: String): String {
        val raw = code.takeLast(6)
        val market = when {
            code.startsWith("sh") || raw.startsWith("6") || raw.startsWith("9") -> "1"
            else -> "0"
        }
        return "$market.$raw"
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

    private fun JSONObject.optLongSafe(key: String): Long {
        val value = opt(key) ?: return 0L
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}

// ═══════════════════════════════════════════════════════
// 数据类
// ═══════════════════════════════════════════════════════

/**
 * 单档买卖挂单
 */
data class BidAskLevel(
    val price: Double,    // 挂单价格
    val volume: Long      // 挂单手数
)

/**
 * 低吸评级枚举
 */
enum class BidAskRating {
    STRONG_BUY,   // 🟢 强烈低吸信号（买卖比 ≥ 1.5）
    WATCH,        // 🟡 低吸观察（买卖比 1.2~1.5）
    NEUTRAL,      // ⚪ 买卖均衡
    AVOID         // 🔴 暂不介入（卖手偏多）
}

/**
 * 五档盘口完整数据
 */
data class BidAskData(
    val code: String,
    val name: String,
    val currentPrice: Double,
    val changePercent: Double,
    val bids: List<BidAskLevel>,        // 买一~买五
    val asks: List<BidAskLevel>,        // 卖一~卖五
    val totalBidVolume: Long,           // 总买手
    val totalAskVolume: Long,           // 总卖手
    val bidAskRatio: Double,            // 买卖比 = 总买手/总卖手
    val rating: BidAskRating,           // 低吸评级
    val timestamp: Long
) {
    /** 格式化的评级文字（带 emoji） */
    val ratingText: String get() = when (rating) {
        BidAskRating.STRONG_BUY -> "🟢 强烈低吸"
        BidAskRating.WATCH -> "🟡 低吸观察"
        BidAskRating.NEUTRAL -> "⚪ 均衡观望"
        BidAskRating.AVOID -> "🔴 暂不介入"
    }
}
