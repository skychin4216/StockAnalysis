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
 * ✅ 支持多股票同时查询（"和"/"跟"/"与" 拆分）
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
        // ⚠️ 这些词会干扰候选词提取，必须按长度从长到短排列（先剥离长词组，避免残留短词）
        private val STRIP_WORDS = listOf(
            "还能追涨", "可以追涨", "还能买吗", "能买吗", "能不能买",
            "可以买入吗", "的最新股价", "最新股价", "的股价", "现价",
            "价格是多少", "多少钱", "是什么价", "什么价格", "什么价",
            "还能买", "可以买", "帮我分析", "帮我看看", "帮我查",
            "分析一下", "走势如何", "怎么样", "怎么了", "怎么样", "是多少",
            "涨了吗", "跌了吗", "今日", "今天", "最新", "最近",
            "分析", "查询", "查看", "走势", "行情", "价格", "股价",
            "追涨", "技术面", "基本面"
        )

        // 多股票连接词 — 拆分多支股票查询
        private val STOCK_CONNECTORS = Regex("和|跟|与|还有|以及|、")

        // 纯中文字符正则（备用）
        @Suppress("unused")
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
        val candidates = extractStockNameCandidates(input)
        if (candidates.isEmpty()) {
            Log.v(TAG, "match: 未提取到有效候选词 from '${input.take(20)}'")
            return false
        }
        Log.d(TAG, "match: 候选词 $candidates from '${input.take(20)}'")
        return true
    }

    override fun parse(input: String): IntentResult {
        val candidates = extractStockNameCandidates(input)
        if (candidates.isEmpty()) return unknownResult(input)

        val allCodes = mutableListOf<String>()
        val allNames = mutableListOf<String>()

        for (candidate in candidates) {
            // 1. 优先查缓存
            val cached = searchCache[candidate]
            if (cached != null) {
                Log.d(TAG, "parse: 缓存命中 '$candidate' → $cached")
                if (cached.isNotEmpty()) {
                    allCodes.addAll(cached)
                    allNames.add(candidate)
                }
                continue
            }

            // 2. 调用东方财富搜索 API
            val codes = searchEastMoney(candidate) ?: searchSina(candidate) ?: emptyList()

            // 3. 存缓存
            if (codes.isNotEmpty()) {
                if (searchCache.size >= CACHE_MAX_SIZE) {
                    searchCache.entries.firstOrNull()?.let { searchCache.remove(it.key) }
                }
                searchCache[candidate] = codes
                allCodes.addAll(codes)
                allNames.add(candidate)
                Log.i(TAG, "parse: 搜索命中 '$candidate' → $codes")
            } else {
                Log.w(TAG, "parse: 搜索无结果 '$candidate'")
                // 缓存空结果，避免重复无效搜索
                searchCache[candidate] = emptyList()
            }
        }

        val distinctCodes = allCodes.distinct()
        val distinctNames = allNames.distinct()

        return buildResult(input, distinctNames, distinctCodes)
    }

    // ======================== 内部工具 ========================

    /**
     * 从用户输入中提取潜在的股票名称候选词列表
     *
     * 策略 v2.0（多股票支持）：
     * 1. 先将常用查询词从输入中剥离
     * 2. 按连接词（和/跟/与/还有/以及/、）拆分子句
     * 3. 在每个子句中寻找 2-8 个字的纯中文词组
     * 4. 排除通用词（今天、股市等）
     * 5. 返回所有有效候选词
     */
    private fun extractStockNameCandidates(input: String): List<String> {
        // 第1步：剥离常用查询前后缀
        var stripped = input
        for (word in STRIP_WORDS) { stripped = stripped.replace(word, " ") }
        stripped = stripped.replace(Regex("[\\s，。？！、,.?！\\d]"), " ").trim()

        // 第2步：按连接词拆分子句，分别提取
        val clauses = stripped.split(STOCK_CONNECTORS).filter { it.isNotBlank() }
        Log.v(TAG, "extractCandidates: clauses after split = $clauses")

        val allSegments = mutableListOf<String>()

        for (clause in clauses) {
            val trimClause = clause.trim()
            // 提取纯中文词组（2-8字）
            val segments = Regex("[\\u4e00-\\u9fff·]{$MIN_NAME_LEN,$MAX_NAME_LEN}")
                .findAll(trimClause)
                .map { it.value }
                .filter { segment ->
                    // 排除完全匹配黑名单的词
                    BLACKLIST_WORDS.none { it == segment }
                }
                .toList()
            allSegments.addAll(segments)
        }

        if (allSegments.isEmpty()) return emptyList()

        // 第3步：尾部清理 — 剥离中文语法助词后缀
        // 典型场景："华润三九的" → "华润三九", "茅台了" → "茅台"
        // 东方财富搜索 API 要求精确匹配股票名称，多余的语法词会导致搜索失败
        val cleanedSegments = allSegments.map { segment ->
            var cleaned = segment
            // 剥离尾部常见的中文单字语法助词
            while (cleaned.length >= MIN_NAME_LEN) {
                val lastChar = cleaned.last()
                if (lastChar == '的' || lastChar == '是' || lastChar == '了' ||
                    lastChar == '吗' || lastChar == '呢' || lastChar == '吧' ||
                    lastChar == '啊' || lastChar == '呀' || lastChar == '么' ||
                    lastChar == '哦' || lastChar == '嘛' || lastChar == '着' ||
                    lastChar == '过' || lastChar == '在' || lastChar == '到') {
                    cleaned = cleaned.dropLast(1)
                } else break
            }
            // 同时剥离残留的双字查询词（"如何"、"是否"等）
            if (cleaned.length >= MIN_NAME_LEN + 2) {
                val lastTwo = cleaned.takeLast(2)
                if (lastTwo == "如何" || lastTwo == "是否" || lastTwo == "可以" ||
                    lastTwo == "现在" || lastTwo == "什么")
                    cleaned = cleaned.dropLast(2)
            }
            cleaned
        }.filter { it.length >= MIN_NAME_LEN }

        if (cleanedSegments.isEmpty()) return emptyList()

        // 第4步：去重，优先保留 3-5 字的词（最像股票名）
        val scored = cleanedSegments
            .distinct()
            .map { it to (if (it.length in 3..5) it.length + 10 else it.length) }
            .sortedByDescending { it.second }

        val result = scored.map { it.first }
        Log.d(TAG, "extractCandidates: raw=[${allSegments.joinToString(",")}] cleaned=$result")
        return result
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

    private fun buildResult(input: String, names: List<String>, codes: List<String>): IntentResult {
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
            stockNames = names,
            confidence = if (codes.isNotEmpty()) 0.75f else 0.0f,
            rawQuery = input,
            parsedParams = mapOf("source" to "fuzzy_search_multi", "queries" to names)
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