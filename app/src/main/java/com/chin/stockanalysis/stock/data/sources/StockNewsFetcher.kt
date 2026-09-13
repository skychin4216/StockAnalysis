package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.chin.stockanalysis.stock.data.HttpClientProvider
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * ## 即时股票资料爬虫
 *
 * 从权威来源获取最新的公告、新闻、资金流向数据，
 * 注入 AI 对话上下文，确保分析基于即时数据而非 2024 年训练数据。
 *
 * ### 数据来源优先级
 * 1. 巨潮资讯网 (cninfo.com.cn) — 证监会官方公告
 * 2. 财联社 (cls.cn) — 7×24 即时快讯
 * 3. 东方财富 (eastmoney.com) — 行情+资金+新闻
 * 4. 雪球 (xueqiu.com) — 深度讨论
 */
class StockNewsFetcher {

    companion object {
        private const val TAG = "StockNewsFetcher"
    }

    // 使用模拟浏览器的 client，避免 403
    private val client = HttpClientProvider.webScrapeClient

    /**
     * 为指定股票获取完整的即时数据上下文
     * 包含：公告摘要、财联社快讯、东方财富新闻、雪球讨论、资金流向
     */
    suspend fun fetchStockContext(
        stockCode: String,
        stockName: String
    ): StockNewsContext = withContext(Dispatchers.IO) {
        val pureCode = stockCode.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        Log.i(TAG, "开始获取 $stockName ($stockCode) 的即时数据")

        val announcements = fetchAnnouncements(pureCode, stockName)
        val clsNews = fetchClsNews(stockName)
        val eastNews = fetchEastMoneyNews(stockName)
        val xueqiu = fetchXueqiuDiscussion(stockCode, stockName)
        val capitalFlow = fetchCapitalFlow(pureCode)

        StockNewsContext(
            stockCode = stockCode,
            stockName = stockName,
            announcements = announcements,
            clsNews = clsNews,
            eastNews = eastNews,
            xueqiuHot = xueqiu,
            capitalFlow = capitalFlow,
            fetchTime = System.currentTimeMillis()
        )
    }

    // ═══════════════════════════════════════════════════
    // 1. 巨潮资讯网 — 证监会官方公告
    // ═══════════════════════════════════════════════════

    private suspend fun fetchAnnouncements(stockCode: String, stockName: String): List<Announcement> {
        return try {
            val url = "${DataConfig.newsCninfo}?" +
                "stockCode=$stockCode&pageSize=5&pageNum=1"

            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", DataConfig.newsCninfo)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseAnnouncements(body)
        } catch (e: Exception) {
            Log.w(TAG, "巨潮公告获取失败: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════
    // 2. 财联社 — 7×24 即时快讯
    // ═══════════════════════════════════════════════════

    private suspend fun fetchClsNews(stockName: String): List<NewsItem> {
        return try {
            val encoded = URLEncoder.encode(stockName, "UTF-8")
            // 财联社搜索 API
            val url = "${DataConfig.newsCls}?" +
                "q=$encoded&type=article&page=1&size=5"

            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", DataConfig.newsCls)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseClsNews(body)
        } catch (e: Exception) {
            Log.w(TAG, "财联社快讯获取失败: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════
    // 3. 东方财富 — 个股新闻
    // ═══════════════════════════════════════════════════

    private suspend fun fetchEastMoneyNews(stockName: String): List<NewsItem> {
        return try {
            val encoded = URLEncoder.encode(stockName, "UTF-8")
            val url = "${DataConfig.eastmoneySearchApi}?" +
                "input=$encoded&type=8192&pageSize=5&pageIndex=1"

            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", DataConfig.eastmoneyGuba)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseEastNews(body)
        } catch (e: Exception) {
            Log.w(TAG, "东方财富新闻获取失败: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════
    // 4. 雪球 — 深度讨论
    // ═══════════════════════════════════════════════════

    private suspend fun fetchXueqiuDiscussion(stockCode: String, stockName: String): List<NewsItem> {
        return try {
            val symbol = if (stockCode.startsWith("sh")) "SH${stockCode.removePrefix("sh")}"
            else "SZ${stockCode.removePrefix("sz").removePrefix("bj")}"

            val url = "${DataConfig.newsXueqiu}?" +
                "count=5&comment=0&symbol=$symbol&hl=0&source=stock&sort=time&page=1"

            // 雪球需要 Cookie（先用空请求获取，再带 Cookie）
            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", "${DataConfig.newsXueqiuDetail}/S/$symbol")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseXueqiu(body)
        } catch (e: Exception) {
            Log.w(TAG, "雪球讨论获取失败: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════
    // 5. 东方财富 — 资金流向
    // ═══════════════════════════════════════════════════

    private suspend fun fetchCapitalFlow(stockCode: String): CapitalFlowData {
        return try {
            val market: Int = if (stockCode.startsWith("6")) 1 else 0
            val url = "${DataConfig.eastmoneyPush2}/stock/fflow/kline/get?" +
                "secid=$market.$stockCode&fields1=f1,f2,f3&fields2=f51,f52,f53,f54&klt=1&lmt=5"

            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", DataConfig.eastmoneyData)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return CapitalFlowData()

            val body = response.body?.string() ?: return CapitalFlowData()
            parseCapitalFlow(body)
        } catch (e: Exception) {
            Log.w(TAG, "资金流向获取失败: ${e.message}")
            CapitalFlowData()
        }
    }

    // ═══════════════════════════════════════════════════
    // JSON 解析
    // ═══════════════════════════════════════════════════

    private fun parseAnnouncements(json: String): List<Announcement> {
        try {
            val root = JSONObject(json)
            val data = root.optJSONObject("classifiedAnnouncements") ?: return emptyList()
            val arr = data.optJSONArray("announcements") ?: return emptyList()
            val results = mutableListOf<Announcement>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val item = arr.getJSONObject(i)
                results.add(Announcement(
                    title = item.optString("announcementTitle", ""),
                    date = item.optString("announcementTime", "").take(10),
                    summary = item.optString("announcementSummary", "").take(200)
                ))
            }
            return results
        } catch (_: Exception) { return emptyList() }
    }

    private fun parseClsNews(json: String): List<NewsItem> {
        try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data")
            val arr = data?.optJSONArray("articles") ?: data?.optJSONArray("list") ?: return emptyList()
            val results = mutableListOf<NewsItem>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val item = arr.getJSONObject(i)
                results.add(NewsItem(
                    title = item.optString("title", "").take(100),
                    source = "财联社",
                    date = item.optString("ctime", "").take(10),
                    url = "${DataConfig.newsClsDetail}/${item.optString("id", "")}"
                ))
            }
            return results
        } catch (_: Exception) { return emptyList() }
    }

    private fun parseEastNews(json: String): List<NewsItem> {
        try {
            val root = JSONObject(json)
            val data = root.optJSONArray("Data") ?: return emptyList()
            val results = mutableListOf<NewsItem>()
            for (i in 0 until minOf(data.length(), 5)) {
                val item = data.getJSONObject(i)
                results.add(NewsItem(
                    title = item.optString("Title", "").take(100),
                    source = item.optString("Source", "东方财富"),
                    date = item.optString("Time", "").take(10),
                    url = item.optString("Url", "")
                ))
            }
            return results
        } catch (_: Exception) { return emptyList() }
    }

    private fun parseXueqiu(json: String): List<NewsItem> {
        try {
            val root = JSONObject(json)
            val list = root.optJSONArray("list") ?: return emptyList()
            val results = mutableListOf<NewsItem>()
            for (i in 0 until minOf(list.length(), 5)) {
                val item = list.getJSONObject(i)
                val text = item.optString("text", "").take(100)
                    .replace("\n", " ").replace("\r", "")
                if (text.isBlank()) continue
                results.add(NewsItem(
                    title = text,
                    source = "雪球",
                    date = item.optString("created_at", "").take(10),
                    url = "${DataConfig.newsXueqiuDetail}${item.optString("target", "")}"
                ))
            }
            return results
        } catch (_: Exception) { return emptyList() }
    }

    private fun parseCapitalFlow(json: String): CapitalFlowData {
        try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: return CapitalFlowData()
            val klines = data.optJSONArray("klines") ?: return CapitalFlowData()

            var totalMainInflow = 0.0
            var totalMainOutflow = 0.0
            for (i in 0 until klines.length()) {
                val line = klines.getString(i)
                val parts = line.split(",")
                if (parts.size >= 4) {
                    totalMainInflow += parts[2].toDoubleOrNull() ?: 0.0
                    totalMainOutflow += parts[3].toDoubleOrNull() ?: 0.0
                }
            }
            return CapitalFlowData(
                mainNetInflow = totalMainInflow - totalMainOutflow,
                mainInflow = totalMainInflow,
                mainOutflow = totalMainOutflow
            )
        } catch (_: Exception) { return CapitalFlowData() }
    }

    // ═══════════════════════════════════════════════════
    // 数据模型
    // ═══════════════════════════════════════════════════

    data class StockNewsContext(
        val stockCode: String,
        val stockName: String,
        val announcements: List<Announcement>,
        val clsNews: List<NewsItem>,
        val eastNews: List<NewsItem>,
        val xueqiuHot: List<NewsItem>,
        val capitalFlow: CapitalFlowData,
        val fetchTime: Long = System.currentTimeMillis()
    ) {
        fun toPromptInjection(): String = buildString {
            appendLine()
            appendLine("【最新实时市场数据】(以下数据为系统实时从权威来源获取)")
            val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(fetchTime))
            appendLine("数据获取时间: $timeStr")

            if (announcements.isNotEmpty()) {
                appendLine()
                appendLine("### 📋 巨潮资讯网公告（证监会官方）")
                announcements.forEachIndexed { i, a ->
                    appendLine("${i + 1}. [${a.date}] ${a.title}")
                }
            }

            if (clsNews.isNotEmpty()) {
                appendLine()
                appendLine("### ⚡ 财联社快讯（7×24小时）")
                clsNews.forEachIndexed { i, n ->
                    appendLine("${i + 1}. [${n.date}] ${n.title}")
                }
            }

            if (eastNews.isNotEmpty()) {
                appendLine()
                appendLine("### 📰 东方财富新闻")
                eastNews.forEachIndexed { i, n ->
                    appendLine("${i + 1}. [${n.source}|${n.date}] ${n.title}")
                }
            }

            if (xueqiuHot.isNotEmpty()) {
                appendLine()
                appendLine("### 💬 雪球最新讨论")
                xueqiuHot.forEachIndexed { i, n ->
                    appendLine("${i + 1}. ${n.title}")
                }
            }

            if (capitalFlow.mainNetInflow != 0.0) {
                appendLine()
                appendLine("### 💰 近5日资金流向")
                val emoji = if (capitalFlow.mainNetInflow > 0) "📈" else "📉"
                val sign = if (capitalFlow.mainNetInflow > 0) "+" else ""
                appendLine("$emoji 主力资金净额: $sign${"%.2f".format(capitalFlow.mainNetInflow)}万元")
            }

            appendLine()
            appendLine("请基于以上实时数据进行分析，不要使用训练数据中的旧信息。")
        }
    }

    data class Announcement(
        val title: String,
        val date: String,
        val summary: String
    )

    data class NewsItem(
        val title: String,
        val source: String,
        val date: String,
        val url: String
    )

    data class CapitalFlowData(
        val mainNetInflow: Double = 0.0,
        val mainInflow: Double = 0.0,
        val mainOutflow: Double = 0.0
    )
}