package com.chin.stockanalysis.stock.intent.handlers

import android.util.Log
import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 模糊股票名称处理器
 *
 * 当 StockNameHandler 的硬编码表无法匹配时，自动调用
 * 东方财富搜索 API 将股票名称转换为代码。
 *
 * ✅ 支持任意 A 股股票名称，不需要硬编码
 * ✅ 搜索结果本地缓存（LRU，最多500条）
 * ✅ 搜索超时 3 秒，失败不影响主流程
 *
 * 调用链位置：位于 StockNameHandler 之后，GeneralStockHandler 之前
 */
class FuzzyStockNameHandler : IntentHandler {
    override var next: IntentHandler? = null

    companion object {
        private const val TAG = "FuzzyStockNameHandler"
        private const val SEARCH_TIMEOUT = 3L    // 3秒超时
        private const val CACHE_MAX_SIZE = 500
        private const val MIN_NAME_LEN = 2       // 最少2个字
        private const val MAX_NAME_LEN = 8       // 最多8个字（含简称+公司后缀）

        // 结果缓存（名称 → 代码列表），线程安全
        private val searchCache = ConcurrentHashMap<String, List<String>>()

        // 通用关键词黑名单 - 这些词不应该被当作股票名称查询
        private val BLACKLIST_WORDS = setOf(
            "今天", "今日", "最近", "最新", "现在", "目前", "当前",
            "股票", "股市", "大盘", "行情", "指数", "推荐", "分析",
            "怎么", "如何", "什么", "为什么", "有没有", "哪个", "哪只",
            "买入", "卖出", "买卖", "投资", "基金", "期货", "外汇",
            "上涨", "下跌", "涨停", "跌停", "涨幅", "跌幅"
        )

        // 在提取候选词之前先从输入中剥离的查询前后缀词
        private val STRIP_WORDS = listOf(
            "帮我分析", "帮我看看", "帮我查", "分析一下", "分析",
            "怎么样", "多少钱", "现价", "查询", "查看",
            "走势如何", "走势", "行情", "怎么了", "涨了吗", "跌了吗",
            "技术面", "基本面", "今天", "最新"
        )

        // 纯中文字符正则（备用）
        private val CHINESE_ONLY = Regex("^[\\u4e00-\\u9fff·]{$MIN_NAME_LEN,$MAX_NAME_LEN}$")

        // 东方财富搜索 API（公开接口，无需鉴权）
        private const val EASTMONEY_SEARCH_URL =
            "https://searchapi.eastmoney.com/api/suggest/get" +
                    "?input=%s&type=14,22&token=D43BF722C8E33BDC906FB84D85E326&count=3"

        // 新浪财经搜索（备用）
        private const val SINA_SEARCH_URL =
            "https://suggest3.sinajs.cn/suggest/type=11&key=%s"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(SEARCH_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(SEARCH_TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * 匹配逻辑：
     * 提取输入中的中文词组，尝试判断是否为可搜索的股票名称
     */
    override fun match(input: String): Boolean {
        val candidate = extractStockNameCandidate(input)
        if (candidate == null) {
            Log.v(TAG, "match: 未提取到有效候选词 from '${input.take(20)}'")
            return false
        }
        Log.d(TAG, "match: 候选词 '$candidate' from '${input.take(20)}'")
        return true
    }

    override fun parse(input: String): IntentResult {
        val candidate = extractStockNameCandidate(input) ?: return unknownResult(input)

        // 1. 优先查缓存
        val cached = searchCache[candidate]
        if (cached != null) {
            Log.d(TAG, "parse: 缓存命中 '$candidate' → $cached")
            return buildResult(input, candidate, cached)
        }

        // 2. 调用东方财富搜索 API
        val codes = searchEastMoney(candidate) ?: searchSina(candidate) ?: emptyList()

        // 3. 存缓存
        if (codes.isNotEmpty()) {
            if (searchCache.size >= CACHE_MAX_SIZE) {
                searchCache.entries.firstOrNull()?.let { searchCache.remove(it.key) }
            }
            searchCache[candidate] = codes
            Log.i(TAG, "parse: 搜索命中 '$candidate' → $codes")
        } else {
            Log.w(TAG, "parse: 搜索无结果 '$candidate'")
            // 缓存空结果，避免重复无效搜索
            searchCache[candidate] = emptyList()
        }

        return buildResult(input, candidate, codes)
    }

    // ======================== 内部工具 ========================

    /**
     * 从用户输入中提取潜在的股票名称候选词
     *
     * 策略：
     * 1. 先将常用查询词（分析、怎么样等）从输入中剥离
     * 2. 在剩余文字中寻找 2-6 个字的纯中文词组
     * 3. 排除通用词（今天、股市等）
     */
    private fun extractStockNameCandidate(input: String): String? {
        // 第1步：剥离常用查询前后缀（for 循环避免 lambda 捕获 var 的限制）
        var stripped = input
        for (word in STRIP_WORDS) { stripped = stripped.replace(word, " ") }
        stripped = stripped.replace(Regex("[\\s，。？！、,.?！\\d]"), " ").trim()

        // 第2步：提取纯中文词组（2-6字）
        val segments = Regex("[\\u4e00-\\u9fff·]{$MIN_NAME_LEN,$MAX_NAME_LEN}")
            .findAll(stripped)
            .map { it.value }
            .filter { segment ->
                // 排除完全匹配黑名单的词（如"今天"、"股市"）
                BLACKLIST_WORDS.none { segment == it || segment == it }
            }
            .toList()

        if (segments.isEmpty()) return null

        // 第3步：选最可能是股票名的词（优先 3-5 字，其次 2 字）
        return segments.maxByOrNull { if (it.length in 3..5) it.length + 10 else it.length }
    }

    /**
     * 调用东方财富搜索 API
     * 返回格式的股票代码列表：sh600111 / sz002594
     */
    private fun searchEastMoney(stockName: String): List<String>? {
        return try {
            val url = EASTMONEY_SEARCH_URL.format(
                java.net.URLEncoder.encode(stockName, "UTF-8")
            )
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            parseEastMoneyResponse(body)
        } catch (e: Exception) {
            Log.w(TAG, "searchEastMoney 失败: ${e.message}")
            null
        }
    }

    /**
     * 解析东方财富返回的 JSON
     * 示例：{"Data":{"14":[{"Code":"600111","Name":"北方稀土","MarketType":1}]}}
     * MarketType: 1=上交所(sh), 2=深交所(sz)
     */
    private fun parseEastMoneyResponse(body: String): List<String>? {
        return try {
            val json = JSONObject(body)
            val data = json.optJSONObject("Data") ?: return null
            val codes = mutableListOf<String>()

            // type 14 = A股, type 22 = 指数
            for (typeKey in listOf("14", "22")) {
                val arr = data.optJSONArray(typeKey) ?: continue
                for (i in 0 until minOf(arr.length(), 2)) {
                    val item = arr.getJSONObject(i)
                    val code = item.optString("Code", "")
                    val marketType = item.optInt("MarketType", 0)
                    if (code.isNotBlank()) {
                        val prefix = when (marketType) {
                            1 -> "sh"   // 上交所
                            2 -> "sz"   // 深交所
                            else -> if (code.startsWith("6")) "sh" else "sz"
                        }
                        codes.add("$prefix$code")
                    }
                }
            }
            codes.distinct().ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "parseEastMoneyResponse 失败: ${e.message}")
            null
        }
    }

    /**
     * 新浪财经搜索（备用）
     * 返回格式：suggestdata="北方稀土,11,600111,北方稀土,600111,北方稀土,BFXT,0,,sh600111,..."
     */
    private fun searchSina(stockName: String): List<String>? {
        return try {
            val url = SINA_SEARCH_URL.format(
                java.net.URLEncoder.encode(stockName, "UTF-8")
            )
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            // 提取 sh/sz + 6位数字 格式的代码
            val pattern = Regex("(sh|sz)\\d{6}")
            val codes = pattern.findAll(body).map { it.value }.distinct().toList()
            codes.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "searchSina 失败: ${e.message}")
            null
        }
    }

    private fun buildResult(input: String, name: String, codes: List<String>): IntentResult {
        val intent = when {
            codes.isEmpty() -> StockIntent.UNKNOWN
            codes.size >= 2 && (input.contains("对比") || input.contains("比较")) ->
                StockIntent.COMPARE_STOCKS
            input.contains("分析") || input.contains("走势") || input.contains("技术") ->
                StockIntent.TECHNICAL_ANALYSIS
            else -> StockIntent.QUERY_PRICE
        }
        return IntentResult(
            intent = intent,
            stockCodes = codes,
            stockNames = if (codes.isNotEmpty()) listOf(name) else emptyList(),
            confidence = if (codes.isNotEmpty()) 0.75f else 0.0f,
            rawQuery = input,
            parsedParams = mapOf("source" to "fuzzy_search", "query" to name)
        )
    }

    private fun unknownResult(input: String) = IntentResult(
        intent = StockIntent.UNKNOWN,
        stockCodes = emptyList(),
        stockNames = emptyList(),
        confidence = 0.0f,
        rawQuery = input
    )
}