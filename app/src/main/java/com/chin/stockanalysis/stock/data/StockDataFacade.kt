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
 * ## 統一數據門面（Facade）
 *
 * 為所有模式（快速/深度/專家/Agent）提供統一的數據獲取接口。
 *
 * ### 數據獲取優先級
 * 1. **實時 API**（新浪/騰訊/東方財富）— 最快最新
 * 2. **網絡搜索**（東方財富搜索/DuckDuckGo）— 補充基本面
 * 3. **本地數據庫** — 歷史數據、資金流向
 *
 * ### 使用方式
 * ```kotlin
 * val facade = StockDataFacade.getInstance(context)
 *
 * // 獲取實時行情
 * val quote = facade.getRealtimeQuote("sh603986")
 *
 * // 獲取基本面信息
 * val fundamental = facade.getFundamentalInfo("sh603986")
 *
 * // 獲取歷史數據（含新鮮度檢查）
 * val history = facade.getHistoricalSnapshots("sh603986", 30)
 *
 * // 獲取資金流向
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
    // 實時行情
    // ═══════════════════════════════════════════════

    /**
     * 獲取單只股票的實時行情
     * 優先級：新浪→騰訊→東方財富（通過 MultiSourceStockRepository 並行獲取）
     *
     * @return StockRealtime? 成功返回實時數據，失敗返回 null
     */
    suspend fun getRealtimeQuote(code: String): StockRealtime? = withContext(Dispatchers.IO) {
        try {
            val map = realtimeRepo.getRealtime(listOf(code))
            map[code]
        } catch (e: Exception) {
            Log.w(TAG, "獲取實時行情失敗 [$code]: ${e.message}")
            null
        }
    }

    /**
     * 批量獲取實時行情
     */
    suspend fun getRealtimeQuotes(codes: List<String>): Map<String, StockRealtime> = withContext(Dispatchers.IO) {
        try {
            realtimeRepo.getRealtime(codes)
        } catch (e: Exception) {
            Log.w(TAG, "批量獲取實時行情失敗: ${e.message}")
            emptyMap()
        }
    }

    // ═══════════════════════════════════════════════
    // 基本面信息
    // ═══════════════════════════════════════════════

    /**
     * 獲取股票基本面信息
     * 優先級：
     * 1. 東方財富搜索 API（實時）
     * 2. 本地數據庫（可能過期）
     *
     * @return FundamentalInfo 包含名稱、主營業務、板塊等
     */
    suspend fun getFundamentalInfo(code: String): FundamentalInfo = withContext(Dispatchers.IO) {
        // === 優先1：東方財富 API ===
        val emInfo = try {
            val url = "https://searchapi.eastmoney.com/api/suggest/get?input=${URLEncoder.encode(code, "UTF-8")}&type=14&count=1"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            val response = HttpClientProvider.realtimeClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val arr = JSONArray(body)
                    if (arr.length() > 0) {
                        val item = arr.getJSONObject(0)
                        FundamentalInfo(
                            name = item.optString("Name", ""),
                            business = item.optString("Business", ""),
                            industry = item.optString("Industry", ""),
                            code = item.optString("Code", code),
                            source = "東方財富實時",
                            isFresh = true
                        )
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "東方財富基本面獲取失敗 [$code]: ${e.message}")
            null
        }

        if (emInfo != null) return@withContext emInfo

        // === Fallback：本地數據庫 ===
        val basic = db.stockBasicDao().getByCode(code)
        val sectorNames = db.sectorStockDao().getSectorNamesByStockCode(code)
        val chainRationale = basic?.chainRationale ?: ""

        FundamentalInfo(
            name = basic?.name ?: code,
            business = basic?.business ?: "",
            industry = "",
            code = code,
            source = "本地數據庫",
            isFresh = false,
            sectorNames = sectorNames,
            chainRationale = chainRationale
        )
    }

    // ═══════════════════════════════════════════════
    // 歷史數據（含新鮮度檢查）
    // ═══════════════════════════════════════════════

    /**
     * 獲取歷史快照數據
     * 自動檢查新鮮度，標注數據來源
     *
     * @param days 最近 N 天
     * @return HistoricalData 包含快照列表和新鮮度信息
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
            source = if (isFresh) "本地數據庫" else "本地數據庫（舊）"
        )
    }

    // ═══════════════════════════════════════════════
    // 資金流向
    // ═══════════════════════════════════════════════

    /**
     * 獲取資金流向數據
     * 目前只有本地數據庫，自動標注新鮮度
     *
     * @param days 最近 N 天
     * @return FundFlowData 包含合計流入、平均換手率、新鮮度
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
            source = if (isFresh) "本地數據庫" else "本地數據庫（舊）",
            isEmpty = snapshots.isEmpty()
        )
    }

    // ═══════════════════════════════════════════════
    // 組合查詢（一鍵獲取所有維度）
    // ═══════════════════════════════════════════════

    /**
     * 一鍵獲取股票的所有分析數據（並行獲取）
     * 用於 StockAnalysisAgent、ChatAgent 等場景
     *
     * @return StockAnalysisData 包含實時行情、基本面、歷史數據、資金流向
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
    // 數據類
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
        val snapshots: List<com.chin.stockanalysis.stock.database.DailySnapshotEntity>,
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
