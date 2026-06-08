package com.chin.stockanalysis.stock.data.sources

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.chin.stockanalysis.stock.data.HttpClientProvider
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * ## 即時股票資料爬蟲
 *
 * 從權威來源獲取最新的公告、新聞、資金流向數據，
 * 注入 AI 對話上下文，確保分析基於即時數據而非 2024 年訓練數據。
 *
 * ### 數據來源優先級
 * 1. 巨潮資訊網 (cninfo.com.cn) — 證監會官方公告
 * 2. 財聯社 (cls.cn) — 7×24 即時快訊
 * 3. 東方財富 (eastmoney.com) — 行情+資金+新聞
 * 4. 雪球 (xueqiu.com) — 深度討論
 */
class StockNewsFetcher {

    companion object {
        private const val TAG = "StockNewsFetcher"
    }

    // 使用模擬瀏覽器的 client，避免 403
    private val client = HttpClientProvider.webScrapeClient

    /**
     * 為指定股票獲取完整的即時數據上下文
     * 包含：公告摘要、財聯社快訊、東方財富新聞、雪球討論、資金流向
     */
    suspend fun fetchStockContext(
        stockCode: String,
        stockName: String
    ): StockNewsContext = withContext(Dispatchers.IO) {
        val pureCode = stockCode.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        Log.i(TAG, "開始獲取 $stockName ($stockCode) 的即時數據")

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
    // 1. 巨潮資訊網 — 證監會官方公告
    // ═══════════════════════════════════════════════════

    private suspend fun fetchAnnouncements(stockCode: String, stockName: String): List<Announcement> {
        return try {
            val url = "https://www.cninfo.com.cn/new/disclosure/detail?" +
                "stockCode=$stockCode&pageSize=5&pageNum=1"

            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", "https://www.cninfo.com.cn/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseAnnouncements(body)
        } catch (e: Exception) {
            Log.w(TAG, "巨潮公告獲取失敗: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════
    // 2. 財聯社 — 7×24 即時快訊
    // ═══════════════════════════════════════════════════

    private suspend fun fetchClsNews(stockName: String): List<NewsItem> {
        return try {
            val encoded = URLEncoder.encode(stockName, "UTF-8")
            // 財聯社搜索 API
            val url = "https://www.cls.cn/api/search?" +
                "q=$encoded&type=article&page=1&size=5"

            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", "https://www.cls.cn/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseClsNews(body)
        } catch (e: Exception) {
            Log.w(TAG, "財聯社快訊獲取失敗: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════
    // 3. 東方財富 — 個股新聞
    // ═══════════════════════════════════════════════════

    private suspend fun fetchEastMoneyNews(stockName: String): List<NewsItem> {
        return try {
            val encoded = URLEncoder.encode(stockName, "UTF-8")
            val url = "https://search-api.eastmoney.com/search?" +
                "input=$encoded&type=8192&pageSize=5&pageIndex=1"

            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", "https://guba.eastmoney.com/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseEastNews(body)
        } catch (e: Exception) {
            Log.w(TAG, "東方財富新聞獲取失敗: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════
    // 4. 雪球 — 深度討論
    // ═══════════════════════════════════════════════════

    private suspend fun fetchXueqiuDiscussion(stockCode: String, stockName: String): List<NewsItem> {
        return try {
            val symbol = if (stockCode.startsWith("sh")) "SH${stockCode.removePrefix("sh")}"
            else "SZ${stockCode.removePrefix("sz").removePrefix("bj")}"

            val url = "https://xueqiu.com/statuses/search.json?" +
                "count=5&comment=0&symbol=$symbol&hl=0&source=stock&sort=time&page=1"

            // 雪球需要 Cookie（先用空請求獲取，再帶 Cookie）
            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", "https://xueqiu.com/S/$symbol")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            parseXueqiu(body)
        } catch (e: Exception) {
            Log.w(TAG, "雪球討論獲取失敗: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════
    // 5. 東方財富 — 資金流向
    // ═══════════════════════════════════════════════════

    private suspend fun fetchCapitalFlow(stockCode: String): CapitalFlowData {
        return try {
            val market: Int = if (stockCode.startsWith("6")) 1 else 0
            val url = "https://push2.eastmoney.com/api/qt/stock/fflow/kline/get?" +
                "secid=$market.$stockCode&fields1=f1,f2,f3&fields2=f51,f52,f53,f54&klt=1&lmt=5"

            val request = Request.Builder()
                .url(url)
                .addHeader("Referer", "https://data.eastmoney.com/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return CapitalFlowData()

            val body = response.body?.string() ?: return CapitalFlowData()
            parseCapitalFlow(body)
        } catch (e: Exception) {
            Log.w(TAG, "資金流向獲取失敗: ${e.message}")
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
                    url = "https://www.cls.cn/detail/${item.optString("id", "")}"
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
                    url = "https://xueqiu.com${item.optString("target", "")}"
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
    // 數據模型
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