package com.chin.stockanalysis.strategy.data

import android.util.Log
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject

/**
 * ## 统一因子数据源
 *
 * 提供 Level2 资金流向、财报数据、新闻情绪三大因子的获取。
 *
 * ### 数据来源
 * - 东方财富个股资金流向 API (f62/f184/f66/f69)
 * - 东方财富F10财务数据 API (PE/PB/ROE/净利增长)
 * - 东方财富个股公告 API (标题关键词检测)
 *
 * ### 使用方式
 * ```kotlin
 * val provider = FactorDataProvider()
 * val flow = provider.getCapitalFlow("sh600519")       // CapitalFlowResult
 * val finance = provider.getFinanceData("sh600519")     // FinanceResult
 * val sentiment = provider.getNewsSentiment("sh600519") // NewsSentimentResult
 * ```
 */
class FactorDataProvider {

    companion object {
        private const val TAG = "FactorDataProvider"
    }

    private val client = HttpClientProvider.realtimeClient

    // ══════════════════════════════════════
    // Level2 资金流向
    // ══════════════════════════════════════

    data class CapitalFlowResult(
        val mainNetInflow: Double = 0.0,     // 当日主力净流入(万元)
        val inflow3Day: Double = 0.0,        // 3日主力净流入
        val inflow5Day: Double = 0.0,        // 5日主力净流入
        val inflow10Day: Double = 0.0,       // 10日主力净流入
        val score: Int = 0,                  // 资金流评分 0-20
        val isContinuousInflow: Boolean = false // 连续流入
    )

    /**
     * 获取个股资金流向
     * @param code 股票代码 (如 sh600519)
     */
    suspend fun getCapitalFlow(code: String): CapitalFlowResult = withContext(Dispatchers.IO) {
        try {
            val market = if (code.startsWith("sh")) 1 else 0
            val pureCode = code.removePrefix("sh").removePrefix("sz")
            val url = "https://push2.eastmoney.com/api/qt/stock/get?" +
                    "secid=$market.$pureCode&fields=f62,f184,f66,f69"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://quote.eastmoney.com/")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext CapitalFlowResult()

            val data = JSONObject(resp.body?.string() ?: "").optJSONObject("data") ?: return@withContext CapitalFlowResult()
            val mainInflow = data.optDouble("f62", 0.0) / 10000.0  // 转万元
            val inflow3 = data.optDouble("f184", 0.0) / 10000.0
            val inflow5 = data.optDouble("f66", 0.0) / 10000.0
            val inflow10 = data.optDouble("f69", 0.0) / 10000.0

            // 资金流评分
            val score = when {
                mainInflow > 5000 && inflow3 > 0 && inflow5 > 0 -> 20  // 持续大额流入
                mainInflow > 2000 && inflow3 > 0 -> 18
                mainInflow > 1000 && inflow5 > 0 -> 15
                mainInflow > 500 -> 12
                mainInflow > 0 -> 8
                mainInflow > -500 -> 5
                mainInflow > -2000 -> 2
                else -> 0
            }
            val continuous = mainInflow > 0 && inflow3 > 0 && inflow5 > 0

            CapitalFlowResult(mainInflow, inflow3, inflow5, inflow10, score, continuous)
        } catch (e: Exception) {
            Log.w(TAG, "资金流向获取失败: ${e.message}")
            CapitalFlowResult()
        }
    }

    // ══════════════════════════════════════
    // 财报数据
    // ══════════════════════════════════════

    data class FinanceResult(
        val pe: Double = 0.0,              // PE(TTM)
        val pb: Double = 0.0,              // PB
        val roe: Double = 0.0,             // ROE
        val netProfitGrowth: Double = 0.0, // 净利润同比增长率%
        val revenueGrowth: Double = 0.0,   // 营收同比增长率%
        val score: Int = 0                 // 财务评分 0-30
    )

    /**
     * 获取个股财务数据
     * @param code 股票代码
     */
    suspend fun getFinanceData(code: String): FinanceResult = withContext(Dispatchers.IO) {
        try {
            val pureCode = code.removePrefix("sh").removePrefix("sz")
            val url = "https://datacenter.eastmoney.com/api/data/v1/get?" +
                    "reportName=RPT_F10_FINANCE_MAINFINADATA&columns=ALL" +
                    "&filter=(SECURITY_CODE=\"$pureCode\")&pageSize=1&pageNumber=1"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext FinanceResult()

            val json = JSONObject(resp.body?.string() ?: "")
            val records = json.optJSONObject("result")?.optJSONArray("data")
            if (records == null || records.length() == 0) return@withContext FinanceResult()

            val data = records.getJSONObject(0)
            val pe = data.optDouble("PE_TTM", 0.0)
            val pb = data.optDouble("PB", 0.0)
            val roe = data.optDouble("ROE_WEIGHT", 0.0)
            val npGrowth = data.optDouble("NETPROFIT_YOY", 0.0)
            val revGrowth = data.optDouble("TOTALOPERATEREVE_YOY", 0.0)

            // 财务评分
            val score = when {
                npGrowth >= 50 && roe > 15 -> 30  // 高增长+高ROE
                npGrowth >= 20 && roe > 10 -> 25
                npGrowth >= 10 -> 20
                npGrowth >= 0 -> 15
                npGrowth >= -10 -> 10
                else -> 5
            }

            FinanceResult(pe, pb, roe, npGrowth, revGrowth, score)
        } catch (e: Exception) {
            Log.w(TAG, "财报获取失败: ${e.message}")
            FinanceResult()
        }
    }

    // ══════════════════════════════════════
    // 新闻情绪
    // ══════════════════════════════════════

    data class NewsSentimentResult(
        val score: Int = 0,                // -20 ~ +20
        val summary: String = "",
        val keywords: List<String> = emptyList()
    )

    private val POSITIVE_KW = listOf(
        "增持", "回购", "中标", "签约", "订单", "预增", "大增", "突破",
        "获批", "上市", "研发", "专利", "扩产", "涨价", "提价",
        "政策扶持", "补贴", "行业景气", "上调", "买入", "看好"
    )
    private val NEGATIVE_KW = listOf(
        "减持", "暴雷", "亏损", "问询", "处罚", "立案", "诉讼",
        "下调", "评级下调", "解禁", "退市", "停产", "整顿",
        "商誉", "暴跌", "违约", "利空", "风险提示", "ST"
    )

    /**
     * 获取个股公告/新闻情绪
     */
    suspend fun getNewsSentiment(code: String): NewsSentimentResult = withContext(Dispatchers.IO) {
        try {
            val pureCode = code.removePrefix("sh").removePrefix("sz")
            val url = "https://np-anotice-stock.eastmoney.com/api/security/ann?" +
                    "sr=-1&page_size=5&page_index=1&ann_type=A&stock_list=$pureCode"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext NewsSentimentResult()

            val json = JSONObject(resp.body?.string() ?: "")
            val list = json.optJSONObject("data")?.optJSONArray("list")
            if (list == null || list.length() == 0) return@withContext NewsSentimentResult()

            val titles = mutableListOf<String>()
            for (i in 0 until list.length()) {
                val title = list.getJSONObject(i).optString("title", "")
                if (title.isNotBlank()) titles.add(title)
            }

            var score = 0
            val matchedKeywords = mutableListOf<String>()

            for (title in titles) {
                for (kw in POSITIVE_KW) {
                    if (title.contains(kw)) { score += 4; matchedKeywords.add("+$kw") }
                }
                for (kw in NEGATIVE_KW) {
                    if (title.contains(kw)) { score -= 5; matchedKeywords.add("-$kw") }
                }
            }

            NewsSentimentResult(
                score = score.coerceIn(-20, 20),
                summary = titles.joinToString("; ") { it.take(30) },
                keywords = matchedKeywords
            )
        } catch (e: Exception) {
            Log.w(TAG, "新闻获取失败: ${e.message}")
            NewsSentimentResult()
        }
    }
}