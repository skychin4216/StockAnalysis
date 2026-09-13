package com.chin.stockanalysis.agent.pipeline

import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.data.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class QuarterlyFinanceData(
    val reportDate: String,
    val reportType: String,
    val reportDateName: String,
    val totalRevenue: Double,
    val parentNetProfit: Double,
    val deductNetProfit: Double,
    val npQoQ: Double,
    val deductNpQoQ: Double,
    val revenueQoQ: Double,
    val revenueYoY: Double,
    val netProfitYoY: Double,
    val grossMargin: Double,
    val netMargin: Double,
    val roe: Double,
    val assetLiabRatio: Double,
    val operatingCashFlowRatio: Double
)

data class QuarterlyComparisonResult(
    val latest: QuarterlyFinanceData?,
    val previous: QuarterlyFinanceData?,
    val latestLabel: String,
    val previousLabel: String,
    val netProfitQoQ: Double,
    val revenueQoQ: Double,
    val deductNpQoQ: Double,
    val trend: QuarterlyTrend,
    val trendDescription: String,
    val scoreAdjustment: Int,
    val scoreReason: String,
    val dataDate: String,
    val isFresh: Boolean,
    val hasData: Boolean
)

enum class QuarterlyTrend {
    ACCELERATING_IMPROVEMENT,
    ACCELERATING_DECLINE,
    SINGLE_IMPROVEMENT,
    SINGLE_DECLINE,
    FLAT,
    UNKNOWN
}

object QuarterlyComparisonProvider {

    private const val TAG = "QuarterlyComparison"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val cache = mutableMapOf<String, Pair<QuarterlyComparisonResult, Long>>()
    private const val CACHE_DURATION_MS = 5 * 60 * 1000L

    private val client = HttpClientProvider.realtimeClient

    private suspend fun fetchFromApi(stockCode: String): QuarterlyComparisonResult =
        withContext(Dispatchers.IO) {
            val emptyResult = QuarterlyComparisonResult(
                latest = null, previous = null,
                latestLabel = "", previousLabel = "",
                netProfitQoQ = 0.0, revenueQoQ = 0.0, deductNpQoQ = 0.0,
                trend = QuarterlyTrend.UNKNOWN,
                trendDescription = "数据不足，无法判定环比趋势",
                scoreAdjustment = 0, scoreReason = "数据不足，无法判定环比趋势",
                dataDate = "", isFresh = false, hasData = false
            )

            try {
                val pureCode = stockCode
                    .removePrefix("sh")
                    .removePrefix("sz")
                    .removePrefix("bj")

                val columns = "REPORT_DATE,REPORT_TYPE,REPORT_DATE_NAME," +
                    "TOTALOPERATEREVE,PARENTNETPROFIT,KCFJCXSYJLR," +
                    "DJD_DPNP_QOQ,DJD_DEDUCTDPNP_QOQ,DJD_TOI_QOQ," +
                    "YYZSRGDHBZC,NETPROFITRPHBZC," +
                    "MGZBGJ,MGWFPLR,ROEJQ,XSMLL,ZZCJLL,ZCFZL,JYXJLYYSR"

                val url = buildString {
                    append(DataConfig.eastmoneyDatacenter)
                    append("?reportName=RPT_F10_FINANCE_MAINFINADATA")
                    append("&columns=$columns")
                    append("&filter=(SECURITY_CODE=\"$pureCode\")")
                    append("&pageSize=6&pageNumber=1")
                    append("&sortColumns=REPORT_DATE&sortTypes=-1")
                }

                Log.i(TAG, "获取季度财报: $pureCode")

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", DataConfig.eastmoneyData)
                    .build()

                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "季度财报 API 失败: ${resp.code}")
                    return@withContext emptyResult
                }

                val body = resp.body?.string() ?: return@withContext emptyResult
                val json = JSONObject(body.trim())
                val result = json.optJSONObject("result") ?: return@withContext emptyResult
                val dataArray = result.optJSONArray("data") ?: return@withContext emptyResult

                val allItems = mutableListOf<QuarterlyFinanceData>()
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    allItems.add(parseFinanceItem(item))
                }

                if (allItems.size < 2) {
                    Log.w(TAG, "季度财报数据不足: ${allItems.size} 条")
                    return@withContext emptyResult
                }

                val nonAnnual = allItems.filter { it.reportType != "年报" }
                if (nonAnnual.size < 2) {
                    Log.w(TAG, "非年报季度数据不足: ${nonAnnual.size} 条")
                    return@withContext emptyResult
                }

                val latest = nonAnnual[0]
                val previous = nonAnnual[1]

                val latestQoQ = calculateQoQ(latest, previous)

                val trendData = determineTrend(nonAnnual, latestQoQ)

                val latestLabel = toQuarterLabel(latest.reportDate, latest.reportType)
                val previousLabel = toQuarterLabel(previous.reportDate, previous.reportType)

                val isFresh = checkFreshness(dataArray)

                QuarterlyComparisonResult(
                    latest = latest,
                    previous = previous,
                    latestLabel = latestLabel,
                    previousLabel = previousLabel,
                    netProfitQoQ = latestQoQ.netProfitQoQ,
                    revenueQoQ = latestQoQ.revenueQoQ,
                    deductNpQoQ = latestQoQ.deductNpQoQ,
                    trend = trendData.trend,
                    trendDescription = trendData.description,
                    scoreAdjustment = trendData.scoreAdjustment,
                    scoreReason = trendData.scoreReason,
                    dataDate = latest.reportDate,
                    isFresh = isFresh,
                    hasData = true
                )
            } catch (e: Exception) {
                Log.w(TAG, "获取季度财报异常: ${e.message}")
                emptyResult
            }
        }

    private fun parseFinanceItem(item: JSONObject): QuarterlyFinanceData {
        return QuarterlyFinanceData(
            reportDate = item.optString("REPORT_DATE", "").take(10),
            reportType = item.optString("REPORT_TYPE", ""),
            reportDateName = item.optString("REPORT_DATE_NAME", ""),
            totalRevenue = item.optDouble("TOTALOPERATEREVE", 0.0),
            parentNetProfit = item.optDouble("PARENTNETPROFIT", 0.0),
            deductNetProfit = item.optDouble("KCFJCXSYJLR", 0.0),
            npQoQ = item.optDouble("DJD_DPNP_QOQ", Double.NaN),
            deductNpQoQ = item.optDouble("DJD_DEDUCTDPNP_QOQ", Double.NaN),
            revenueQoQ = item.optDouble("DJD_TOI_QOQ", Double.NaN),
            revenueYoY = item.optDouble("YYZSRGDHBZC", 0.0),
            netProfitYoY = item.optDouble("NETPROFITRPHBZC", 0.0),
            grossMargin = item.optDouble("XSMLL", 0.0),
            netMargin = item.optDouble("ZZCJLL", 0.0),
            roe = item.optDouble("ROEJQ", 0.0),
            assetLiabRatio = item.optDouble("ZCFZL", 0.0),
            operatingCashFlowRatio = item.optDouble("JYXJLYYSR", 0.0)
        )
    }

    private data class QoQValues(
        val netProfitQoQ: Double,
        val revenueQoQ: Double,
        val deductNpQoQ: Double
    )

    private fun calculateQoQ(latest: QuarterlyFinanceData, previous: QuarterlyFinanceData): QoQValues {
        val npQoQ = if (!latest.npQoQ.isNaN()) {
            latest.npQoQ
        } else {
            calcQoQ(latest.parentNetProfit, previous.parentNetProfit)
        }

        val deductQoQ = if (!latest.deductNpQoQ.isNaN()) {
            latest.deductNpQoQ
        } else {
            calcQoQ(latest.deductNetProfit, previous.deductNetProfit)
        }

        val revQoQ = if (!latest.revenueQoQ.isNaN()) {
            latest.revenueQoQ
        } else {
            calcQoQ(latest.totalRevenue, previous.totalRevenue)
        }

        return QoQValues(netProfitQoQ = npQoQ, revenueQoQ = revQoQ, deductNpQoQ = deductQoQ)
    }

    private fun calcQoQ(current: Double, previous: Double): Double {
        if (previous == 0.0) return 0.0
        return (current - previous) / abs(previous) * 100.0
    }

    private data class TrendInfo(
        val trend: QuarterlyTrend,
        val description: String,
        val scoreAdjustment: Int,
        val scoreReason: String
    )

    private fun determineTrend(
        nonAnnual: List<QuarterlyFinanceData>,
        latestQoQ: QoQValues
    ): TrendInfo {
        if (nonAnnual.size < 2) {
            return TrendInfo(
                trend = QuarterlyTrend.UNKNOWN,
                description = "数据不足，无法判定环比趋势",
                scoreAdjustment = 0,
                scoreReason = "数据不足，无法判定环比趋势"
            )
        }

        val latestData = nonAnnual[0]
        val prevData = nonAnnual[1]

        val latestNpQoQ = if (!latestData.npQoQ.isNaN()) {
            latestData.npQoQ
        } else {
            calcQoQ(latestData.parentNetProfit, prevData.parentNetProfit)
        }

        val prevNpQoQ: Double
        if (nonAnnual.size >= 3) {
            val prevPrev = nonAnnual[2]
            prevNpQoQ = if (!prevData.npQoQ.isNaN()) {
                prevData.npQoQ
            } else {
                calcQoQ(prevData.parentNetProfit, prevPrev.parentNetProfit)
            }
        } else {
            prevNpQoQ = 0.0
        }

        val isLatestPositive = latestNpQoQ > 0
        val isPrevPositive = prevNpQoQ > 0
        val isLatestNegative = latestNpQoQ < 0
        val isPrevNegative = prevNpQoQ < 0

        return when {
            isLatestPositive && isPrevPositive -> TrendInfo(
                trend = QuarterlyTrend.ACCELERATING_IMPROVEMENT,
                description = "连续2季环比上升，经营加速改善",
                scoreAdjustment = 5,
                scoreReason = "连续2季环比上升，经营加速改善"
            )
            isLatestNegative && isPrevNegative -> TrendInfo(
                trend = QuarterlyTrend.ACCELERATING_DECLINE,
                description = "连续2季环比下降，经营颓势明显",
                scoreAdjustment = -10,
                scoreReason = "连续2季环比下降，经营颓势明显"
            )
            isLatestPositive -> TrendInfo(
                trend = QuarterlyTrend.SINGLE_IMPROVEMENT,
                description = "单季环比改善，需观察后续延续性",
                scoreAdjustment = 2,
                scoreReason = "单季环比改善，需观察后续延续性"
            )
            isLatestNegative -> TrendInfo(
                trend = QuarterlyTrend.SINGLE_DECLINE,
                description = "单季环比下滑，需关注是否持续",
                scoreAdjustment = -5,
                scoreReason = "单季环比下滑，需关注是否持续"
            )
            else -> TrendInfo(
                trend = QuarterlyTrend.FLAT,
                description = "环比平稳，无明显趋势",
                scoreAdjustment = 0,
                scoreReason = "环比平稳，无明显趋势"
            )
        }
    }

    private fun toQuarterLabel(reportDate: String, reportType: String): String {
        try {
            val date = LocalDate.parse(reportDate.take(10), DATE_FMT)
            val year = date.year
            val month = date.monthValue
            val quarter = when {
                month <= 3 -> "Q1"
                month <= 6 -> "Q2"
                month <= 9 -> "Q3"
                else -> "Q4"
            }
            return "${year}$quarter"
        } catch (e: Exception) {
            return reportDate
        }
    }

    private fun checkFreshness(dataArray: JSONArray): Boolean {
        try {
            if (dataArray.length() == 0) return false
            val firstItem = dataArray.getJSONObject(0)
            val updateDate = firstItem.optString("UPDATE_DATE", "").take(10)
            val noticeDate = firstItem.optString("NOTICE_DATE", "").take(10)
            val dateStr = if (updateDate.isNotBlank()) updateDate else noticeDate
            if (dateStr.isBlank()) return false
            val dataDate = LocalDate.parse(dateStr, DATE_FMT)
            val today = LocalDate.now()
            val daysDiff = ChronoUnit.DAYS.between(dataDate, today)
            return daysDiff < 30
        } catch (e: Exception) {
            return false
        }
    }

    suspend fun fetch(stockCode: String): QuarterlyComparisonResult {
        val now = System.currentTimeMillis()
        val cached = cache[stockCode]
        if (cached != null && (now - cached.second) < CACHE_DURATION_MS) {
            Log.d(TAG, "使用缓存: $stockCode")
            return cached.first
        }

        val result = fetchFromApi(stockCode)
        cache[stockCode] = Pair(result, now)
        return result
    }

    fun formatForAgentInjection(result: QuarterlyComparisonResult): String {
        if (!result.hasData || result.latest == null || result.previous == null) {
            return "【季度环比数据】无可用数据"
        }

        val latestNpYi = result.latest.parentNetProfit / 100000000.0
        val prevNpYi = result.previous.parentNetProfit / 100000000.0
        val latestRevYi = result.latest.totalRevenue / 100000000.0
        val prevRevYi = result.previous.totalRevenue / 100000000.0

        val npChange = formatChange(result.netProfitQoQ)
        val revChange = formatChange(result.revenueQoQ)

        val npTrend = trendEmoji(result.trend)
        val revTrend = trendEmoji(result.trend)

        val trendText = when (result.trend) {
            QuarterlyTrend.ACCELERATING_IMPROVEMENT -> "加速改善"
            QuarterlyTrend.ACCELERATING_DECLINE -> "出现颓势"
            QuarterlyTrend.SINGLE_IMPROVEMENT -> "单季回暖"
            QuarterlyTrend.SINGLE_DECLINE -> "单季回落"
            QuarterlyTrend.FLAT -> "平稳震荡"
            QuarterlyTrend.UNKNOWN -> "数据不足"
        }

        return buildString {
            appendLine("【季度环比数据】（数据截至: ${result.latestLabel}）")
            appendLine("| 指标 | ${result.latestLabel} | ${result.previousLabel} | 环比变化 | 趋势判定 |")
            appendLine("|------|---------|---------|---------|---------|")
            appendLine("| 单季归母净利润 | ${String.format("%.2f", latestNpYi)}亿 | ${String.format("%.2f", prevNpYi)}亿 | $npChange | $npTrend |")
            appendLine("| 单季营业收入 | ${String.format("%.2f", latestRevYi)}亿 | ${String.format("%.2f", prevRevYi)}亿 | $revChange | $revTrend |")
            appendLine("| 综合判定 | — | — | — | $trendText |")
            appendLine("趋势结论: ${result.trendDescription}")
            append("评分调整: ${result.scoreAdjustment}分 — ${result.scoreReason}")
        }
    }

    private fun formatChange(value: Double): String {
        return if (value >= 0) {
            "+${String.format("%.1f", value)}%"
        } else {
            "${String.format("%.1f", value)}%"
        }
    }

    private fun trendEmoji(trend: QuarterlyTrend): String {
        return when (trend) {
            QuarterlyTrend.ACCELERATING_IMPROVEMENT -> "\u2B06\uFE0F"
            QuarterlyTrend.ACCELERATING_DECLINE -> "\u2B07\uFE0F"
            else -> "\uD83D\uDD04"
        }
    }
}
