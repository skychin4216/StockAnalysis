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
 * 股票名称处理器 - 识别常见股票名称 "茅台" / "五粮液" 等
 *
 * v2.0：当硬编码表无法匹配时，自动通过东方财富 API 模糊搜索兜底，
 *       确保多股票查询中不会丢失任何一只股票。
 */
class StockNameHandler : IntentHandler {
    override var next: IntentHandler? = null

    companion object {
        private const val TAG = "StockNameHandler"

        // 常见 A 股股票名称映射表（按代码排序）
        // 添加新的股票时，格式：\"名称\" to \"sh/szXXXXXX\"
        private val STOCK_NAME_MAP = mapOf(
            "茅台" to "sh600519",
            "五粮液" to "sz000858",
            "平安" to "sh601318",
            "宁德时代" to "sz300750",
            "比亚迪" to "sz002594",
            "工商银行" to "sh601398",
            "建设银行" to "sh601939",
            "农业银行" to "sh601288",
            "中国银行" to "sh601988",
            "贵州茅台" to "sh600519",
            "美的集团" to "sz000333",
            "格力电器" to "sz000651",
            "苹果" to "sh603160",
            "华勤技术" to "sh603296",
            "立讯精密" to "sz002475",
            "海康威视" to "sz002415",
            "中芯国际" to "sh688981",
            "长江电力" to "sh600900",
            "中兴通讯" to "sz000063",
            "迈瑞医疗" to "sz300760",
            "恒瑞医药" to "sh600276",
            "药明康德" to "sh603259",
            "科大讯飞" to "sz002230",
            "紫金矿业" to "sh601899",
            "万华化学" to "sh600309",
            "生益科技" to "sh600183",
            "沪电股份" to "sz002463",
            "韦尔股份" to "sh603501",
            "汇顶科技" to "sh603160",
            "深信服" to "sz300454",
            "广联达" to "sz002410",
            "用友网络" to "sh600588",
            "恒生电子" to "sh600570",
            "赣锋锂业" to "sz002460",
            "北方稀土" to "sz000831",
            "天齐锂业" to "sz002466",
            "中国平安" to "sh601318",
            "招商银行" to "sh600036",
        )

        // ─── 东方财富模糊搜索 ───
        private const val SEARCH_TIMEOUT = 3L
        private const val EASTMONEY_SEARCH_URL =
            "https://searchapi.eastmoney.com/api/suggest/get" +
                    "?input=%s&type=14,22&token=D43BF722C8E33BDC906FB84D85E326&count=3"

        // 在多股票查询中拆分的关键词
        private val STOCK_CONNECTORS = Regex("和|跟|与|还有|以及|、")

        // 需要从文本中剥离的查询前缀后缀
        private val STRIP_WORDS = listOf(
            "的最新股价", "最新股价", "的股价", "现价",
            "还能追涨", "可以追涨", "还能买吗", "能买吗", "能不能买",
            "可以买入吗", "还能买", "可以买",
            "价格是多少", "多少钱", "是什么价", "什么价格", "什么价",
            "帮我分析", "帮我看看", "帮我查", "帮我", "分析一下",
            "走势如何", "怎么样", "怎么了",
            "涨了吗", "跌了吗", "今日", "今天", "最新", "最近",
            "分析", "查询", "查看", "走势", "行情", "价格", "股价",
            "追涨", "技术面", "基本面",
        )

        // OkHttp 客户端
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(SEARCH_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(SEARCH_TIMEOUT, TimeUnit.SECONDS)
            .build()
    }

    override fun match(input: String): Boolean {
        return STOCK_NAME_MAP.keys.any { input.contains(it) }
    }

    override fun parse(input: String): IntentResult {
        // 1. 从硬编码表匹配
        val hardMatchedNames = mutableListOf<String>()
        val hardMatchedCodes = mutableListOf<String>()

        for ((name, code) in STOCK_NAME_MAP) {
            if (input.contains(name)) {
                hardMatchedNames.add(name)
                hardMatchedCodes.add(code)
            }
        }

        val distinctCodes = hardMatchedCodes.distinct().toMutableList()
        val distinctNames = hardMatchedNames.distinct().toMutableList()

        // 2. 从输入中提取所有可能的股票名称候选词（包括硬编码表未覆盖的）
        val allNameCandidates = extractCandidateNames(input)

        // 3. 对于硬编码表中未匹配的名称，通过东方财富 API 搜索
        val unmatchedCandidates = allNameCandidates
            .filter { candidate ->
                hardMatchedNames.none { hardName ->
                    candidate.contains(hardName) || hardName.contains(candidate)
                }
            }

        if (unmatchedCandidates.isNotEmpty()) {
            Log.d(TAG, "parse: 硬编码表匹配了 $distinctCodes，仍有 ${unmatchedCandidates.size} 个候选需要模糊搜索: $unmatchedCandidates")

            for (candidate in unmatchedCandidates) {
                val codes = searchStockCode(candidate)
                if (codes.isNotEmpty()) {
                    distinctCodes.addAll(codes)
                    distinctNames.add(candidate)
                    Log.i(TAG, "parse: 模糊搜索命中 '$candidate' → $codes")
                } else {
                    Log.w(TAG, "parse: 模糊搜索无结果 '$candidate'")
                }
            }
        }

        val finalCodes = distinctCodes.distinct()
        val finalNames = distinctNames.distinct()

        val upperInput = input.uppercase()
        val intent = when {
            finalCodes.size >= 2 && (input.contains("对比") || input.contains("比较") || input.contains("哪个") || input.contains("哪只") || upperInput.contains("VS")) -> StockIntent.COMPARE_STOCKS
            input.contains("MACD", ignoreCase = true) || input.contains("K线") || input.contains("技术") || input.contains("指标") || input.contains("走势") || input.contains("分析") -> StockIntent.TECHNICAL_ANALYSIS
            else -> StockIntent.QUERY_PRICE
        }

        // 置信度：如果有硬编码命中则高，否则基于搜索结果
        val confidence = when {
            hardMatchedCodes.isNotEmpty() -> 0.8f
            finalCodes.isNotEmpty() -> 0.72f
            else -> 0.0f
        }

        return IntentResult(
            intent = intent,
            stockCodes = finalCodes,
            stockNames = finalNames,
            confidence = confidence,
            rawQuery = input,
            parsedParams = mapOf("analysis_type" to detectAnalysisType(input))
        )
    }

    /**
     * 从输入中提取所有可能的股票名称候选词
     */
    private fun extractCandidateNames(input: String): List<String> {
        var stripped = input
        for (word in STRIP_WORDS) {
            stripped = stripped.replace(word, " ")
        }
        stripped = stripped.replace(Regex("[\\s，。？！、,.?！\\d]"), " ").trim()

        // 按连接词拆分子句
        val clauses = stripped.split(STOCK_CONNECTORS).filter { it.isNotBlank() }

        val candidates = mutableListOf<String>()
        for (clause in clauses) {
            val trimClause = clause.trim()
            val segments = Regex("[\\u4e00-\\u9fff·]{2,8}")
                .findAll(trimClause)
                .map { it.value }
                .filter { name ->
                    name !in setOf("今天", "今日", "最近", "最新", "现在", "目前", "当前",
                        "股票", "股市", "大盘", "行情", "指数", "推荐",
                        "怎么", "如何", "什么", "为什么", "有没有", "哪个", "哪只",
                        "买入", "卖出", "买卖", "投资", "基金", "期货", "外汇",
                        "上涨", "下跌", "涨停", "跌停", "涨幅", "跌幅",
                        "当前价", "最低价", "最高价", "成交量", "成交额", "开盘价", "收盘价",
                        "过去一个", "年度最", "今天有", "真的值"
                    )
                }
                .toList()
            candidates.addAll(segments)
        }

        // 尾部清理：剥离中文语法助词
        val cleaned = candidates.map { segment ->
            var cleaned = segment
            while (cleaned.length >= 2) {
                val lastChar = cleaned.last()
                if (lastChar in "的了吗呢吧啊呀么哦嘛着过在到") {
                    cleaned = cleaned.dropLast(1)
                } else break
            }
            if (cleaned.length >= 4) {
                val lastTwo = cleaned.takeLast(2)
                if (lastTwo in setOf("如何", "是否", "可以", "现在", "什么"))
                    cleaned = cleaned.dropLast(2)
            }
            cleaned
        }.filter { it.length >= 2 }

        return cleaned.distinct().sortedByDescending { it.length }
    }

    /**
     * 调用东方财富搜索 API 查找股票代码
     */
    private fun searchStockCode(stockName: String): List<String> {
        return try {
            val url = EASTMONEY_SEARCH_URL.format(
                java.net.URLEncoder.encode(stockName, "UTF-8")
            )
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseEastMoneyResponse(body)
        } catch (e: Exception) {
            Log.w(TAG, "searchStockCode 失败 '$stockName': ${e.message}")
            emptyList()
        }
    }

    private fun parseEastMoneyResponse(body: String): List<String> {
        return try {
            val json = JSONObject(body)
            val data = json.optJSONObject("Data") ?: return emptyList()
            val codes = mutableListOf<String>()

            for (typeKey in listOf("14", "22")) {
                val arr = data.optJSONArray(typeKey) ?: continue
                for (i in 0 until minOf(arr.length(), 2)) {
                    val item = arr.getJSONObject(i)
                    val code = item.optString("Code", "")
                    val marketType = item.optInt("MarketType", 0)
                    if (code.isNotBlank()) {
                        val prefix = when (marketType) {
                            1 -> "sh"
                            2 -> "sz"
                            else -> if (code.startsWith("6")) "sh" else "sz"
                        }
                        codes.add("$prefix$code")
                    }
                }
            }
            codes.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "parseEastMoneyResponse 失败: ${e.message}")
            emptyList()
        }
    }

    private fun detectAnalysisType(input: String): String {
        return when {
            input.contains("MACD", ignoreCase = true) -> "macd"
            input.contains("K线") || input.contains("蜡烛图") -> "kline"
            input.contains("均线") || input.contains("MA") -> "ma"
            input.contains("成交量") || input.contains("量能") -> "volume"
            input.contains("走势") || input.contains("趋势") -> "trend"
            else -> "general"
        }
    }
}