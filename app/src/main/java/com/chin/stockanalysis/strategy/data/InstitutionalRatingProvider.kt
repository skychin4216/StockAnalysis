package com.chin.stockanalysis.strategy.data

import android.util.Log
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 機構評級數據提供者
 *
 * 獲取券商研報評級數據，包括：
 * - 最新機構評級（買入/增持/中性/賣出）
 * - 目標價區間
 * - 預測 EPS
 * - 近 90 天研報數量
 *
 * ### 數據來源
 * - 東方財富研報 API (reportapi.eastmoney.com)
 *
 * ### 使用方式
 * ```kotlin
 * val provider = InstitutionalRatingProvider()
 * val ratings = provider.getRatings("sh600519")   // List<RatingResult>
 * val summary = provider.getRatingSummary("sh600519") // RatingSummary
 * ```
 */
class InstitutionalRatingProvider {

    companion object {
        private const val TAG = "InstitutionalRating"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val client = HttpClientProvider.realtimeClient

    /** 研報 API 基礎 URL */
    private val baseUrl = "https://reportapi.eastmoney.com/report/list"

    /**
     * 機構評級結果
     */
    data class RatingResult(
        val stockCode: String,          // 股票代碼
        val stockName: String,          // 股票名稱
        val orgName: String,            // 券商名稱
        val rating: String,             // 評級（買入/增持/中性/賣出）
        val ratingChange: String,       // 評級變動（上調/下調/維持/首次）
        val publishDate: String,        // 發布日期
        val researcher: String,         // 研究員
        val targetPriceHigh: Double?,   // 目標價上限
        val targetPriceLow: Double?,    // 目標價下限
        val predictEpsThisYear: Double?,// 今年預測 EPS
        val predictEpsNextYear: Double?,// 明年預測 EPS
        val reportCount90d: Int         // 近90天報告數量
    )

    /**
     * 評級匯總
     */
    data class RatingSummary(
        val stockCode: String,
        val stockName: String,
        val totalReports: Int,          // 總研報數
        val buyCount: Int,              // 買入數
        val overweightCount: Int,       // 增持數
        val neutralCount: Int,          // 中性數
        val sellCount: Int,             // 賣出數
        val avgTargetPrice: Double?,    // 平均目標價
        val consensusRating: String,    // 共識評級
        val latestDate: String?,        // 最新研報日期
        val latestOrgs: List<String>,   // 最近評級機構
        val detailList: List<RatingResult>
    )

    /**
     * 獲取個股機構評級列表
     *
     * @param code 股票代碼 (如 sh600519)
     * @param days 查詢近 N 天的評級，預設 180 天
     * @param pageSize 每頁數量，預設 50
     * @return 評級列表（按日期倒序）
     */
    suspend fun getRatings(
        code: String,
        days: Int = 180,
        pageSize: Int = 50
    ): List<RatingResult> = withContext(Dispatchers.IO) {
        try {
            val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
            // 東方財富 API 需要 rcode 參數，格式為「市場代號.股票代碼」
            // 1=上海, 0=深圳/創業板
            val marketId = when {
                code.startsWith("sh") -> "1"
                code.startsWith("sz") || code.startsWith("bj") -> "0"
                pureCode.startsWith("6") || pureCode.startsWith("9") -> "1"
                else -> "0"
            }
            val rcode = "$marketId.$pureCode"

            val endDate = LocalDate.now()
            val beginDate = endDate.minusDays(days.toLong())

            val url = buildString {
                append(baseUrl)
                append("?industryCode=*")
                append("&pageNo=1")
                append("&pageSize=$pageSize")
                append("&code=*")
                append("&codeName=*")
                append("&industry=*")
                append("&ratingChange=*")
                append("&rating=*")
                append("&rcode=$rcode")
                append("&authorName=*")
                append("&beginTime=${beginDate.format(DATE_FMT)}")
                append("&endTime=${endDate.format(DATE_FMT)}")
                append("&qType=0")
            }

            Log.i(TAG, "獲取機構評級: $code, URL長度=${url.length}")

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://data.eastmoney.com/report/stock.jshtml")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "機構評級 API 失敗: ${resp.code}")
                return@withContext emptyList()
            }

            val body = resp.body?.string() ?: return@withContext emptyList()
            // 處理可能的 JSONP 回調包裝
            val jsonStr = body.trim()
            val json = try {
                val pure = if (jsonStr.startsWith("jQuery") || jsonStr.startsWith("callback"))
                    jsonStr.substringAfter("(").substringBeforeLast(")")
                else jsonStr
                JSONObject(pure)
            } catch (e: Exception) {
                Log.w(TAG, "JSON 解析失敗: ${e.message}, body前100字=${jsonStr.take(100)}")
                return@withContext emptyList()
            }
            val dataArray = json.optJSONObject("data")?.optJSONArray("data") ?: return@withContext emptyList()

            val results = mutableListOf<RatingResult>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                results.add(parseRatingItem(item, pureCode))
            }

            Log.i(TAG, "獲取機構評級成功: $code, ${results.size} 條")
            results
        } catch (e: Exception) {
            Log.w(TAG, "獲取機構評級異常: ${e.message}")
            emptyList()
        }
    }

    /**
     * 獲取評級匯總摘要
     *
     * @param code 股票代碼
     * @param days 查詢近 N 天
     * @return RatingSummary 匯總對象
     */
    suspend fun getRatingSummary(code: String, days: Int = 180): RatingSummary {
        val ratings = getRatings(code, days)
        if (ratings.isEmpty()) {
            return RatingSummary(
                stockCode = code, stockName = "",
                totalReports = 0, buyCount = 0, overweightCount = 0,
                neutralCount = 0, sellCount = 0,
                avgTargetPrice = null, consensusRating = "無數據",
                latestDate = null, latestOrgs = emptyList(),
                detailList = emptyList()
            )
        }

        val buyCount = ratings.count { it.rating.contains("買入") || it.rating.contains("买入") || it.rating == "Buy" }
        val overweightCount = ratings.count { it.rating.contains("增持") || it.rating == "Overweight" }
        val neutralCount = ratings.count { it.rating.contains("中性") || it.rating == "Neutral" }
        val sellCount = ratings.count { it.rating.contains("賣出") || it.rating.contains("卖出") || it.rating == "Sell" }

        // 平均目標價
        val validTargets = ratings.mapNotNull { it.targetPriceHigh }
        val avgTarget = if (validTargets.isNotEmpty()) validTargets.average() else null

        // 共識評級
        val consensus = when {
            buyCount >= overweightCount && buyCount >= neutralCount && buyCount >= sellCount -> "買入"
            overweightCount >= buyCount && overweightCount >= neutralCount && overweightCount >= sellCount -> "增持"
            neutralCount >= sellCount -> "中性"
            else -> "賣出"
        }

        // 最新日期和機構
        val latestDate = ratings.firstOrNull()?.publishDate
        val latestOrgs = ratings.take(5).map { it.orgName }.distinct()

        return RatingSummary(
            stockCode = code,
            stockName = ratings.firstOrNull()?.stockName ?: "",
            totalReports = ratings.size,
            buyCount = buyCount,
            overweightCount = overweightCount,
            neutralCount = neutralCount,
            sellCount = sellCount,
            avgTargetPrice = avgTarget,
            consensusRating = consensus,
            latestDate = latestDate,
            latestOrgs = latestOrgs,
            detailList = ratings
        )
    }

    /**
     * 解析單條評級數據
     */
    private fun parseRatingItem(item: JSONObject, code: String): RatingResult {
        val ratingMap = mapOf(
            "007" to "買入", "006" to "增持", "005" to "中性", "004" to "減持", "003" to "賣出",
            "0301" to "買入", "0302" to "增持", "0303" to "中性", "0304" to "減持", "0305" to "賣出",
            "0103" to "買入", "0201" to "買入"
        )

        val emRatingCode = item.optString("emRatingCode", "")
        val emRatingName = item.optString("emRatingName", "")
        val sRatingName = item.optString("sRatingName", "")

        val rating = when {
            emRatingName.isNotBlank() -> emRatingName
            sRatingName.isNotBlank() -> sRatingName
            ratingMap.containsKey(emRatingCode) -> ratingMap[emRatingCode]!!
            else -> "未知"
        }

        // 評級變動
        val ratingChange = when (item.optInt("ratingChange", 0)) {
            1 -> "上調"
            2 -> "首次"
            3 -> "維持"
            4 -> "下調"
            else -> "未知"
        }

        // 目標價
        val targetHigh = item.optString("indvAimPriceT", "").toDoubleOrNull()
        val targetLow = item.optString("indvAimPriceL", "").toDoubleOrNull()

        // 預測 EPS
        val epsThis = item.optString("predictThisYearEps", "").toDoubleOrNull()
        val epsNext = item.optString("predictNextYearEps", "").toDoubleOrNull()

        return RatingResult(
            stockCode = code,
            stockName = item.optString("stockName", ""),
            orgName = item.optString("orgSName", item.optString("orgName", "")),
            rating = rating,
            ratingChange = ratingChange,
            publishDate = item.optString("publishDate", "").take(10),
            researcher = item.optString("researcher", ""),
            targetPriceHigh = targetHigh,
            targetPriceLow = targetLow,
            predictEpsThisYear = epsThis,
            predictEpsNextYear = epsNext,
            reportCount90d = item.optInt("count", 0)
        )
    }

    // ════════════════════════════════════════════════════
    //  基金持倉數據
    // ════════════════════════════════════════════════════

    data class FundHolding(
        val fundName: String,
        val fundCode: String,
        val holdShares: Double,
        val holdMarketCap: Double,  // 持倉市值（萬元）
        val holdRatio: Double,       // 占流通股比例 %
        val reportDate: String
    )

    /**
     * 獲取某只股票的基金持倉數據
     * 使用東方財富 datacenter API：RPT_FUND_HOLDERSTOCK
     */
    suspend fun getFundHoldings(code: String, pageSize: Int = 20): List<FundHolding> = withContext(Dispatchers.IO) {
        try {
            val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")

            val url = buildString {
                append("https://datacenter-web.eastmoney.com/api/data/v1/get")
                append("?reportName=RPT_FUND_HOLDERSTOCK")
                append("&columns=ALL")
                append("&filter=(SECURITY_CODE=\"$pureCode\")")
                append("&pageNumber=1")
                append("&pageSize=$pageSize")
                append("&sortTypes=-1")
                append("&sortColumns=HOLD_MARKET_CAP")
                append("&source=WEB")
                append("&client=WEB")
            }

            Log.i(TAG, "獲取基金持倉: $code, URL長度=${url.length}")

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://data.eastmoney.com")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "基金持倉 API 失敗: ${resp.code}")
                return@withContext emptyList()
            }

            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body.trim())
            val result = json.optJSONObject("result") ?: return@withContext emptyList()
            val dataArray = result.optJSONArray("data") ?: return@withContext emptyList()

            val list = mutableListOf<FundHolding>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                list.add(FundHolding(
                    fundName = item.optString("FUND_NAME", ""),
                    fundCode = item.optString("FUND_CODE", ""),
                    holdShares = item.optDouble("HOLD_SHARES", 0.0),
                    holdMarketCap = item.optDouble("HOLD_MARKET_CAP", 0.0),
                    holdRatio = item.optDouble("HOLD_RATIO", 0.0),
                    reportDate = item.optString("REPORT_DATE", "").take(10)
                ))
            }

            Log.i(TAG, "基金持倉成功: $code, ${list.size} 條")
            list
        } catch (e: Exception) {
            Log.w(TAG, "基金持倉異常: ${e.message}")
            emptyList()
        }
    }
}
