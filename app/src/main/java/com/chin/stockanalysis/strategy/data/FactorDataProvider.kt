package com.chin.stockanalysis.strategy.data

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject

/**
 * ## 统一因子数据源
 *
 * 提供 Level2 资金流向、财报数据、新闻情绪、股东户数、北向持股五大因子的获取。
 *
 * ### 数据来源
 * - 东方财富个股资金流向 API (f62/f184/f66/f69)
 * - 东方财富F10财务数据 API (PE/PB/ROE/净利增长)
 * - 东方财富个股公告 API (标题关键词检测)
 * - 东方财富数据中心 股东户数 API (RPT_HOLDERNUMLATEST)
 * - 东方财富F10十大流通股东 API (北向持股/香港中央结算)
 *
 * ### 使用方式
 * ```kotlin
 * val provider = FactorDataProvider()
 * val flow = provider.getCapitalFlow("sh600519")        // CapitalFlowResult
 * val finance = provider.getFinanceData("sh600519")      // FinanceResult
 * val sentiment = provider.getNewsSentiment("sh600519")  // NewsSentimentResult
 * val holder = provider.getHolderNum("sh600519")         // HolderNumResult
 * val north = provider.getNorthboundHold("sh600519")     // NorthboundHoldResult
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
            val url = "${DataConfig.eastmoneyPush2}/stock/get?" +
                    "secid=$market.$pureCode&fields=f62,f184,f66,f69"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", DataConfig.eastmoneyQuote)
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
            val url = "${DataConfig.eastmoneyDatacenter}?" +
                    "reportName=RPT_F10_FINANCE_MAINFINADATA&columns=ALL" +
                    "&filter=(SECURITY_CODE=\"$pureCode\")&pageSize=1&pageNumber=1" +
                    "&sortColumns=REPORT_DATE&sortTypes=-1"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext FinanceResult()

            val json = JSONObject(resp.body?.string() ?: "")
            val records = json.optJSONObject("result")?.optJSONArray("data")
            if (records == null || records.length() == 0) return@withContext FinanceResult()

            val data = records.getJSONObject(0)
            // 注意：该报表不含 PE/PB 字段（保持 0，调用方另有行情来源）
            val pe = data.optDouble("PE_TTM", 0.0)
            val pb = data.optDouble("PB", 0.0)
            val roe = data.optDouble("ROEJQ", 0.0)                    // ROE加权%
            val npGrowth = data.optDouble("PARENTNETPROFITTZ", 0.0)   // 归母净利润同比%
            val revGrowth = data.optDouble("TOTALOPERATEREVETZ", 0.0) // 营业总收入同比%

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

    // ══════════════════════════════════════
    // 股东户数（筹码集中度）
    // ══════════════════════════════════════

    data class HolderNumResult(
        val holderNum: Long = 0,              // 最新股东户数(户)
        val prevHolderNum: Long = 0,          // 上期股东户数(户)
        val holderNumChange: Long = 0,        // 环比变动(户)
        val holderNumRatio: Double = 0.0,     // 环比变动比例(%)
        val endDate: String = "",             // 报告期
        val isConcentrating: Boolean = false, // 筹码集中（户数减少）
        val score: Int = 0                    // 筹码集中评分 0-20
    )

    /**
     * 获取股东户数（筹码集中度）
     *
     * 数据源：东方财富数据中心 RPT_HOLDERNUMLATEST
     * 逻辑：股东户数持续下降 → 筹码从散户流向主力 → 吸筹信号（利好）
     *
     * @param code 股票代码 (如 sh600519)
     */
    suspend fun getHolderNum(code: String): HolderNumResult = withContext(Dispatchers.IO) {
        try {
            val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
            val url = "${DataConfig.eastmoneyDatacenter}?" +
                    "reportName=RPT_HOLDERNUMLATEST&columns=ALL" +
                    "&filter=(SECURITY_CODE=\"$pureCode\")" +
                    "&pageSize=5&pageNumber=1&sortColumns=END_DATE&sortTypes=-1"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext HolderNumResult()

            val json = JSONObject(resp.body?.string() ?: "")
            val records = json.optJSONObject("result")?.optJSONArray("data")
            if (records == null || records.length() == 0) return@withContext HolderNumResult()

            val data = records.getJSONObject(0)
            val holderNum = data.optLong("HOLDER_NUM", 0L)
            val prevHolderNum = data.optLong("PRE_HOLDER_NUM", 0L)
            val change = holderNum - prevHolderNum
            val ratio = if (prevHolderNum > 0) change.toDouble() / prevHolderNum * 100.0 else 0.0

            // 评分：户数减少比例越大 → 筹码越集中 → 加分
            val score = when {
                ratio <= -10.0 -> 20
                ratio <= -5.0 -> 18
                ratio <= -2.0 -> 15
                ratio <= 0.0 -> 12
                ratio <= 2.0 -> 8
                ratio <= 5.0 -> 5
                ratio <= 10.0 -> 2
                else -> 0
            }

            HolderNumResult(
                holderNum = holderNum,
                prevHolderNum = prevHolderNum,
                holderNumChange = change,
                holderNumRatio = ratio,
                endDate = data.optString("END_DATE", "").take(10),
                isConcentrating = change < 0,
                score = score
            )
        } catch (e: Exception) {
            Log.w(TAG, "股东户数获取失败: ${e.message}")
            HolderNumResult()
        }
    }

    // ══════════════════════════════════════
    // 北向持股
    // ══════════════════════════════════════

    data class NorthboundHoldResult(
        val holdShares: Long = 0,           // 北向持股数(股)
        val holdSharesRatio: Double = 0.0,  // 占流通股比例(%)
        val holdChange: Long = 0,           // 较上期变动(股)
        val changeRatio: Double = 0.0,      // 较上期变动比例(%)
        val holderRank: Int = 0,            // 十大流通股东中排名
        val reportDate: String = "",        // 报告期
        val isIncreasing: Boolean = false,  // 本期北向增持
        val score: Int = 0                  // 北向持股评分 0-20
    )

    /**
     * 获取北向持股（香港中央结算有限公司）
     *
     * 数据源：东方财富 F10 十大流通股东（PageAjax → sdltgd）
     * 北向资金在十大流通股东中体现为"香港中央结算有限公司"。
     * 注意：自 2024-08 起港交所改为季度披露北向持股，返回最近一次季报数据，
     * 用于判断"长线主力是否布局"足够。
     *
     * @param code 股票代码 (如 sh600519)
     */
    suspend fun getNorthboundHold(code: String): NorthboundHoldResult = withContext(Dispatchers.IO) {
        try {
            val secuCode = toSecuCode(code)
            if (secuCode.isEmpty()) return@withContext NorthboundHoldResult()
            val url = "${DataConfig.eastmoneyF10Shareholder}?code=$secuCode"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", DataConfig.eastmoneyF10Host)
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext NorthboundHoldResult()

            val json = JSONObject(resp.body?.string() ?: "")
            val ltgd = json.optJSONArray("sdltgd") ?: return@withContext NorthboundHoldResult()

            // 在十大流通股东中定位"香港中央结算有限公司"
            for (i in 0 until ltgd.length()) {
                val rec = ltgd.getJSONObject(i)
                if (!rec.optString("HOLDER_NAME", "").contains("香港中央结算")) continue

                val holdShares = rec.optLong("HOLD_NUM", 0L)
                val holdRatio = rec.optDouble("FREE_HOLDNUM_RATIO", 0.0)
                val changeStr = rec.optString("HOLD_NUM_CHANGE", "0")
                val change = changeStr.toLongOrNull() ?: 0L
                val changeRatio = rec.optDouble("CHANGE_RATIO", 0.0)
                val increasing = change > 0

                // 评分：增持幅度/持仓比例越大 → 主力布局越明显
                val score = when {
                    increasing && changeRatio >= 20 -> 20   // 大幅增持 ≥20%
                    increasing && changeRatio >= 10 -> 18
                    increasing && changeRatio >= 5 -> 15
                    increasing -> 12
                    holdRatio >= 5.0 -> 10                  // 高持仓比例
                    holdRatio >= 2.0 -> 8
                    holdRatio >= 0.5 -> 5
                    changeRatio <= -20 -> 0                 // 大幅减持
                    else -> 2
                }

                return@withContext NorthboundHoldResult(
                    holdShares = holdShares,
                    holdSharesRatio = holdRatio,
                    holdChange = change,
                    changeRatio = changeRatio,
                    holderRank = rec.optInt("HOLDER_RANK", 0),
                    reportDate = rec.optString("END_DATE", "").take(10),
                    isIncreasing = increasing,
                    score = score
                )
            }
            NorthboundHoldResult()
        } catch (e: Exception) {
            Log.w(TAG, "北向持股获取失败: ${e.message}")
            NorthboundHoldResult()
        }
    }

    /**
     * 将标准股票代码转换为东方财富 F10 格式（如 sh600519 → SH600519）
     */
    private fun toSecuCode(code: String): String {
        val trimmed = code.trim().lowercase()
        return when {
            trimmed.startsWith("sh") || trimmed.startsWith("sz") || trimmed.startsWith("bj") -> trimmed.uppercase()
            trimmed.length == 6 && trimmed.all { it.isDigit() } -> {
                when (trimmed[0]) {
                    '6', '9' -> "SH$trimmed"
                    '0', '3' -> "SZ$trimmed"
                    '4', '8' -> "BJ$trimmed"
                    else -> "SH$trimmed"
                }
            }
            else -> trimmed.uppercase()
        }
    }

    /**
     * 获取个股公告/新闻情绪
     */
    suspend fun getNewsSentiment(code: String): NewsSentimentResult = withContext(Dispatchers.IO) {
        try {
            val pureCode = code.removePrefix("sh").removePrefix("sz")
            val url = "${DataConfig.eastmoneyAnotice}?" +
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