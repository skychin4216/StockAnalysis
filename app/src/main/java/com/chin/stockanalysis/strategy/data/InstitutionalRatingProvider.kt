package com.chin.stockanalysis.strategy.data

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 机构评级数据提供者
 *
 * 获取券商研报评级数据，包括：
 * - 最新机构评级（买入/增持/中性/卖出）
 * - 目标价区间
 * - 预测 EPS
 * - 近 90 天研报数量
 *
 * ### 数据来源
 * - 东方财富研报 API (reportapi.eastmoney.com)
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

    /** 研报 API 基础 URL（配置：data_sources.eastmoney.report） */
    private val baseUrl = DataConfig.eastmoneyReport

    /**
     * 机构评级结果
     */
    data class RatingResult(
        val stockCode: String,          // 股票代码
        val stockName: String,          // 股票名称
        val orgName: String,            // 券商名称
        val rating: String,             // 评级（买入/增持/中性/卖出）
        val ratingChange: String,       // 评级变动（上调/下调/维持/首次）
        val publishDate: String,        // 发布日期
        val researcher: String,         // 研究员
        val targetPriceHigh: Double?,   // 目标价上限
        val targetPriceLow: Double?,    // 目标价下限
        val predictEpsThisYear: Double?,// 今年预测 EPS
        val predictEpsNextYear: Double?,// 明年预测 EPS
        val reportCount90d: Int         // 近90天报告数量
    )

    /**
     * 评级汇总
     */
    data class RatingSummary(
        val stockCode: String,
        val stockName: String,
        val totalReports: Int,          // 总研报数
        val buyCount: Int,              // 买入数
        val overweightCount: Int,       // 增持数
        val neutralCount: Int,          // 中性数
        val sellCount: Int,             // 卖出数
        val avgTargetPrice: Double?,    // 平均目标价
        val consensusRating: String,    // 共识评级
        val latestDate: String?,        // 最新研报日期
        val latestOrgs: List<String>,   // 最近评级机构
        val detailList: List<RatingResult>
    )

    /**
     * 获取个股机构评级列表
     *
     * @param code 股票代码 (如 sh600519)
     * @param days 查询近 N 天的评级，预设 180 天
     * @param pageSize 每页数量，预设 50
     * @return 评级列表（按日期倒序）
     */
    suspend fun getRatings(
        code: String,
        days: Int = 180,
        pageSize: Int = 50
    ): List<RatingResult> = withContext(Dispatchers.IO) {
        try {
            val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
            // 东方财富 API 需要 rcode 参数，格式为「市场代号.股票代码」
            // 1=上海, 0=深圳/创业板
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

            Log.i(TAG, "获取机构评级: $code, URL长度=${url.length}")

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", DataConfig.eastmoneyReportStock)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "机构评级 API 失败: ${resp.code}")
                return@withContext emptyList()
            }

            val body = resp.body?.string() ?: return@withContext emptyList()
            // 处理可能的 JSONP 回调包装
            val jsonStr = body.trim()
            val json = try {
                val pure = if (jsonStr.startsWith("jQuery") || jsonStr.startsWith("callback"))
                    jsonStr.substringAfter("(").substringBeforeLast(")")
                else jsonStr
                JSONObject(pure)
            } catch (e: Exception) {
                Log.w(TAG, "JSON 解析失败: ${e.message}, body前100字=${jsonStr.take(100)}")
                return@withContext emptyList()
            }
            val dataArray = json.optJSONObject("data")?.optJSONArray("data") ?: return@withContext emptyList()

            val results = mutableListOf<RatingResult>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                results.add(parseRatingItem(item, pureCode))
            }

            Log.i(TAG, "获取机构评级成功: $code, ${results.size} 条")
            results
        } catch (e: Exception) {
            Log.w(TAG, "获取机构评级异常: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取评级汇总摘要
     *
     * @param code 股票代码
     * @param days 查询近 N 天
     * @return RatingSummary 汇总对象
     */
    suspend fun getRatingSummary(code: String, days: Int = 180): RatingSummary {
        val ratings = getRatings(code, days)
        if (ratings.isEmpty()) {
            return RatingSummary(
                stockCode = code, stockName = "",
                totalReports = 0, buyCount = 0, overweightCount = 0,
                neutralCount = 0, sellCount = 0,
                avgTargetPrice = null, consensusRating = "无数据",
                latestDate = null, latestOrgs = emptyList(),
                detailList = emptyList()
            )
        }

        val buyCount = ratings.count { it.rating.contains("买入") || it.rating.contains("买入") || it.rating == "Buy" }
        val overweightCount = ratings.count { it.rating.contains("增持") || it.rating == "Overweight" }
        val neutralCount = ratings.count { it.rating.contains("中性") || it.rating == "Neutral" }
        val sellCount = ratings.count { it.rating.contains("卖出") || it.rating.contains("卖出") || it.rating == "Sell" }

        // 平均目标价
        val validTargets = ratings.mapNotNull { it.targetPriceHigh }
        val avgTarget = if (validTargets.isNotEmpty()) validTargets.average() else null

        // 共识评级
        val consensus = when {
            buyCount >= overweightCount && buyCount >= neutralCount && buyCount >= sellCount -> "买入"
            overweightCount >= buyCount && overweightCount >= neutralCount && overweightCount >= sellCount -> "增持"
            neutralCount >= sellCount -> "中性"
            else -> "卖出"
        }

        // 最新日期和机构
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
     * 解析单条评级数据
     */
    private fun parseRatingItem(item: JSONObject, code: String): RatingResult {
        val ratingMap = mapOf(
            "007" to "买入", "006" to "增持", "005" to "中性", "004" to "减持", "003" to "卖出",
            "0301" to "买入", "0302" to "增持", "0303" to "中性", "0304" to "减持", "0305" to "卖出",
            "0103" to "买入", "0201" to "买入"
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

        // 评级变动
        val ratingChange = when (item.optInt("ratingChange", 0)) {
            1 -> "上调"
            2 -> "首次"
            3 -> "维持"
            4 -> "下调"
            else -> "未知"
        }

        // 目标价
        val targetHigh = item.optString("indvAimPriceT", "").toDoubleOrNull()
        val targetLow = item.optString("indvAimPriceL", "").toDoubleOrNull()

        // 预测 EPS
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
    //  基金持仓数据
    // ════════════════════════════════════════════════════

    data class FundHolding(
        val fundName: String,
        val fundCode: String,
        val holdShares: Double,
        val holdMarketCap: Double,  // 持仓市值（万元）
        val holdRatio: Double,       // 占流通股比例 %
        val reportDate: String
    )

    /**
     * 获取某只股票的基金持仓数据
     * 使用东方财富 F10 基金持股接口（替代已弃用的 RPT_FUND_HOLDERSTOCK）
     * API: https://emweb.securities.eastmoney.com/PC_HSF10/ShareholderResearch/PageAjax?code={SECUCODE}
     */
    suspend fun getFundHoldings(code: String, pageSize: Int = 20): List<FundHolding> = withContext(Dispatchers.IO) {
        try {
            val secuCode = normalizeToSecuCode(code)
            val url = "${DataConfig.eastmoneyF10Shareholder}?code=$secuCode"

            Log.i(TAG, "获取基金持仓: $code → $secuCode, URL=$url")

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", DataConfig.eastmoneyF10Host)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "基金持仓 API 失败: ${resp.code}")
                return@withContext emptyList()
            }

            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body.trim())
            val dataArray = json.optJSONArray("jjcc") ?: return@withContext emptyList()

            val list = mutableListOf<FundHolding>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                // 只取机构类型为 01（基金）的数据
                if (item.optString("ORG_TYPE", "") != "01") continue
                val holdValue = item.optDouble("HOLD_VALUE", 0.0)
                list.add(FundHolding(
                    fundName = item.optString("HOLDER_NAME", ""),
                    fundCode = item.optString("FUND_CODE", ""),
                    holdShares = item.optDouble("TOTAL_SHARES", 0.0),
                    holdMarketCap = if (holdValue > 0) holdValue / 10000.0 else 0.0,  // 元 → 万元
                    holdRatio = item.optDouble("FREESHARES_RATIO", 0.0) * 100,         // 小数 → %
                    reportDate = item.optString("REPORT_DATE", "").take(10)
                ))
            }

            // 按持股市值降序排列，取前 pageSize 条
            val sorted = list.sortedByDescending { it.holdMarketCap }.take(pageSize)
            Log.i(TAG, "基金持仓成功: $code, ${sorted.size}/${list.size} 条（已按市值排序取TOP）")
            sorted
        } catch (e: Exception) {
            Log.w(TAG, "基金持仓异常: ${e.message}")
            emptyList()
        }
    }

    /**
     * 将标准股票代码转换为东方财富 F10 格式（如 sh603986 → SH603986）
     */
    private fun normalizeToSecuCode(code: String): String {
        val trimmed = code.trim().lowercase()
        return when {
            trimmed.startsWith("sh") -> trimmed.uppercase()
            trimmed.startsWith("sz") -> trimmed.uppercase()
            trimmed.startsWith("bj") -> trimmed.uppercase()
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
}
