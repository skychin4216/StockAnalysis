package com.chin.stockanalysis.stock.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 统一数据门面（Facade）
 *
 * 为所有模式（快速/深度/专家/Agent）提供统一的数据获取接口。
 *
 * ### 数据获取优先级
 * 1. **实时 API**（新浪/腾讯/东方财富）— 最快最新
 * 2. **网络搜索**（东方财富搜索/DuckDuckGo）— 补充基本面
 * 3. **本地数据库** — 历史数据、资金流向
 *
 * ### 使用方式
 * ```kotlin
 * val facade = StockDataFacade.getInstance(context)
 *
 * // 获取实时行情
 * val quote = facade.getRealtimeQuote("sh603986")
 *
 * // 获取基本面信息
 * val fundamental = facade.getFundamentalInfo("sh603986")
 *
 * // 获取历史数据（含新鲜度检查）
 * val history = facade.getHistoricalSnapshots("sh603986", 30)
 *
 * // 获取资金流向
 * val fundFlow = facade.getFundFlow("sh603986", 5)
 * ```
 */
class StockDataFacade private constructor(private val context: Context) {

    companion object {
        private const val TAG = "StockDataFacade"
        private const val DATE_FMT = "yyyy-MM-dd"
        private val dateFormatter = DateTimeFormatter.ofPattern(DATE_FMT)

        @Volatile
        private var instance: StockDataFacade? = null

        fun getInstance(context: Context): StockDataFacade {
            return instance ?: synchronized(this) {
                instance ?: StockDataFacade(context.applicationContext).also { instance = it }
            }
        }
    }

    private val db by lazy { StockDatabase.getInstance(context) }
    private val realtimeRepo by lazy { StockDataSourceFactory.createDefaultRepository(context) }

    // ═══════════════════════════════════════════════
    // 实时行情
    // ═══════════════════════════════════════════════

    /**
     * 获取单只股票的实时行情
     * 优先级：新浪→腾讯→东方财富（通过 MultiSourceStockRepository 并行获取）
     *
     * @return StockRealtime? 成功返回实时数据，失败返回 null
     */
    suspend fun getRealtimeQuote(code: String): StockRealtime? = withContext(Dispatchers.IO) {
        try {
            val map = realtimeRepo.getRealtime(listOf(code))
            map[code]
        } catch (e: Exception) {
            Log.w(TAG, "获取实时行情失败 [$code]: ${e.message}")
            null
        }
    }

    /**
     * 批量获取实时行情
     */
    suspend fun getRealtimeQuotes(codes: List<String>): Map<String, StockRealtime> = withContext(Dispatchers.IO) {
        try {
            realtimeRepo.getRealtime(codes)
        } catch (e: Exception) {
            Log.w(TAG, "批量获取实时行情失败: ${e.message}")
            emptyMap()
        }
    }

    // ═══════════════════════════════════════════════
    // 基本面信息
    // ═══════════════════════════════════════════════

    /**
     * 获取股票基本面信息
     * 优先级：
     * 1. 东方财富搜索 API（实时）
     * 2. 本地数据库（可能过期）
     *
     * @return FundamentalInfo 包含名称、主营业务、板块等
     */
    suspend fun getFundamentalInfo(code: String): FundamentalInfo = withContext(Dispatchers.IO) {
        // === 优先1：东方财富 API ===
        val emInfo = try {
            val url = com.chin.stockanalysis.config.DataConfig.eastmoneySearchUrl(code)
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val response = HttpClientProvider.realtimeClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    // 东方财富返回 {"QuotationCodeTable":{"Data":[...]}} 或直接 JSONArray
                    val arr = try {
                        val root = JSONObject(body)
                        // 尝试 QuotationCodeTable.Data 格式
                        if (root.has("QuotationCodeTable")) {
                            root.getJSONObject("QuotationCodeTable")
                                .getJSONArray("Data")
                        } else {
                            // 直接是 JSONObject 包含 Data
                            root.getJSONArray("Data")
                        }
                    } catch (_: Exception) {
                        try { JSONArray(body) } catch (_: Exception) { null }
                    }
                    if (arr != null && arr.length() > 0) {
                        val item = arr.getJSONObject(0)
                        FundamentalInfo(
                            name = item.optString("Name", ""),
                            business = item.optString("Business", ""),
                            industry = item.optString("Industry", ""),
                            code = item.optString("Code", code),
                            source = "东方财富实时",
                            isFresh = true
                        )
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "东方财富基本面获取失败 [$code]: ${e.message}")
            null
        }

        if (emInfo != null) return@withContext emInfo

        // === Fallback：本地数据库 ===
        val basic = db.stockBasicDao().getByCode(code)
        val sectorNames = db.sectorStockDao().getSectorNamesByStockCode(code)
        val chainRationale = basic?.chainRationale ?: ""

        FundamentalInfo(
            name = basic?.name ?: code,
            business = basic?.business ?: "",
            industry = "",
            code = code,
            source = "本地数据库",
            isFresh = false,
            sectorNames = sectorNames,
            chainRationale = chainRationale
        )
    }

    // ═══════════════════════════════════════════════
    // 历史数据（含新鲜度检查）
    // ═══════════════════════════════════════════════

    /**
     * 获取历史快照数据
     * 自动检查新鲜度，标注数据来源
     *
     * @param days 最近 N 天
     * @return HistoricalData 包含快照列表和新鲜度信息
     */
    suspend fun getHistoricalSnapshots(code: String, days: Int = 30): HistoricalData = withContext(Dispatchers.IO) {
        val snapshots = db.dailySnapshotDao().getByCode(code, days)
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        val isFresh = snapshots.isNotEmpty() && snapshots.first().let {
            try {
                val snapDate = LocalDate.parse(it.date, dateFormatter)
                snapDate == today || snapDate == yesterday
            } catch (_: Exception) { false }
        }

        val latestDate = snapshots.firstOrNull()?.date ?: ""

        HistoricalData(
            snapshots = snapshots,
            latestDate = latestDate,
            isFresh = isFresh,
            source = if (isFresh) "本地数据库" else "本地数据库（旧）"
        )
    }

    // ═══════════════════════════════════════════════
    // 资金流向
    // ═══════════════════════════════════════════════

    /**
     * 获取资金流向数据
     * 目前只有本地数据库，自动标注新鲜度
     *
     * @param days 最近 N 天
     * @return FundFlowData 包含合计流入、平均换手率、新鲜度
     */
    suspend fun getFundFlow(code: String, days: Int = 5): FundFlowData = withContext(Dispatchers.IO) {
        val snapshots = db.dailySnapshotDao().getByCode(code, days)
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        val isFresh = snapshots.isNotEmpty() && snapshots.firstOrNull()?.let {
            try {
                val snapDate = LocalDate.parse(it.date, dateFormatter)
                snapDate == today || snapDate == yesterday
            } catch (_: Exception) { false }
        } == true

        val totalInflow = snapshots.sumOf { it.mainNetInflow }
        val avgTurnover = snapshots.map { it.turnoverRate }.average()

        FundFlowData(
            totalNetInflow = totalInflow,
            avgTurnoverRate = avgTurnover,
            latestDate = snapshots.firstOrNull()?.date ?: "",
            isFresh = isFresh,
            source = if (isFresh) "本地数据库" else "本地数据库（旧）",
            isEmpty = snapshots.isEmpty()
        )
    }

    // ═══════════════════════════════════════════════
    // 组合查询（一键获取所有维度）
    // ═══════════════════════════════════════════════

    /**
     * 一键获取股票的所有分析数据（并行获取）
     * 用于 StockAnalysisAgent、ChatAgent 等场景
     *
     * @return StockAnalysisData 包含实时行情、基本面、历史数据、资金流向
     */
    suspend fun getAnalysisData(code: String, days: Int = 30): StockAnalysisData = withContext(Dispatchers.IO) {
        val quote = getRealtimeQuote(code)
        val fundamental = getFundamentalInfo(code)
        val history = getHistoricalSnapshots(code, days)
        val fundFlow = getFundFlow(code, 5)

        StockAnalysisData(
            code = code,
            quote = quote,
            fundamental = fundamental,
            history = history,
            fundFlow = fundFlow
        )
    }

    // ═══════════════════════════════════════════════
    // 数据类
    // ═══════════════════════════════════════════════

    data class FundamentalInfo(
        val name: String,
        val business: String,
        val industry: String,
        val code: String,
        val source: String,
        val isFresh: Boolean,
        val sectorNames: List<String> = emptyList(),
        val chainRationale: String = ""
    )

    data class HistoricalData(
        val snapshots: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>,
        val latestDate: String,
        val isFresh: Boolean,
        val source: String
    )

    data class FundFlowData(
        val totalNetInflow: Double,
        val avgTurnoverRate: Double,
        val latestDate: String,
        val isFresh: Boolean,
        val source: String,
        val isEmpty: Boolean
    )

    data class StockAnalysisData(
        val code: String,
        val quote: StockRealtime?,
        val fundamental: FundamentalInfo,
        val history: HistoricalData,
        val fundFlow: FundFlowData
    )
}
