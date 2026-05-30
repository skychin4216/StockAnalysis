package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 历史数据抓取器
 *
 * 从东方财富历史K线 API 批量拉取 A 股 OHLCV 数据，
 * 填充到 daily_snapshot 表用于回测。
 *
 * ### 使用方式
 * ```kotlin
 * val fetcher = HistoricalDataFetcher(context)
 * fetcher.fetchAllHistoricalData(60)  // 拉取最近60个交易日
 * ```
 *
 * ### API 格式
 * https://push2his.eastmoney.com/api/qt/stock/kline/get?
 *   secid=1.600519&klt=101&fqt=1&beg=20260501&end=20260530
 *   &fields1=f1,f2,f3
 *   &fields2=f51,f52,f53,f54,f55,f56,f57,f58,f61
 *   &lmt=120
 */
class HistoricalDataFetcher(private val context: Context) {

    companion object {
        private const val TAG = "HistoricalDataFetcher"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val STORE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 全市场前200只热门股票代码 */
        private val TOP_STOCKS = listOf(
            "sh600519", "sz000858", "sh600183", "sz002594", "sz300750",
            "sh601318", "sz002463", "sh688981", "sh600030", "sz000001",
            "sh600036", "sz000002", "sh601398", "sh600900", "sh601857",
            "sz002475", "sh600276", "sz000651", "sz002415", "sh600809",
            "sh601012", "sz300059", "sh600887", "sz002230", "sz000333",
            "sh601166", "sz300015", "sh600585", "sz002714", "sh600104",
            "sz000725", "sh600031", "sz000063", "sh601088", "sz002241",
            "sh600050", "sz300433", "sh601899", "sz002049", "sh601728"
        )
    }

    private val client = HttpClientProvider.realtimeClient
    private val db = StockDatabase.getInstance(context)

    data class FetchProgress(
        val totalStocks: Int,
        val completedStocks: Int,
        val totalRecords: Int,
        val currentStock: String = ""
    )

    /**
     * 批量拉取历史数据
     * @param days 拉取天数（默认60个交易日）
     * @param onProgress 进度回调
     * @return 总共插入的记录数
     */
    suspend fun fetchAllHistoricalData(
        days: Int = 60,
        onProgress: ((FetchProgress) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays((days * 1.5).toLong())  // 多拉一些，过滤非交易日

        Log.i(TAG, "━━━ 开始拉取历史数据: ${startDate.format(STORE_FMT)} ~ ${endDate.format(STORE_FMT)} ━━━")

        var totalRecords = 0
        val totalStocks = TOP_STOCKS.size

        // 控制并发，每次5只
        TOP_STOCKS.chunked(5).forEach { batch ->
            val jobs = batch.map { code ->
                async {
                    fetchOneStock(code, startDate, endDate)
                }
            }
            for (job in jobs) {
                val records = job.await()
                if (records.isNotEmpty()) {
                    db.dailySnapshotDao().insertAll(records)
                    totalRecords += records.size
                }
                onProgress?.invoke(FetchProgress(
                    totalStocks = totalStocks,
                    completedStocks = minOf(TOTAL_COMPLETE + batch.size, totalStocks),
                    totalRecords = totalRecords
                ))
                TOTAL_COMPLETE++
            }
        }

        Log.i(TAG, "✅ 历史数据拉取完成: $totalRecords 条记录")
        totalRecords
    }

    private var TOTAL_COMPLETE = 0  // 线程安全的计数器简化

    /**
     * 拉取单只股票的历史K线
     */
    private suspend fun fetchOneStock(
        code: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<DailySnapshotEntity> {
        try {
        val market = if (code.startsWith("sh")) 1 else 0
        val pureCode = code.removePrefix("sh").removePrefix("sz")
        val beg = startDate.format(DATE_FMT)
        val end = endDate.format(DATE_FMT)

        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?" +
                "secid=$market.$pureCode" +
                "&klt=101" +          // 日K
                "&fqt=1" +            // 前复权
                "&fields1=f1,f2,f3" +
                "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f61" +
                "&beg=$beg&end=$end&lmt=150"

        val req = Request.Builder().url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .addHeader("Referer", "https://quote.eastmoney.com/")
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return emptyList()

        val body = resp.body?.string() ?: return emptyList()
        val data = JSONObject(body).optJSONObject("data")
        val klines = data?.optJSONArray("klines") ?: return emptyList()

        val results = mutableListOf<DailySnapshotEntity>()

        for (i in 0 until klines.length()) {
            val line = klines.getString(i)
            val parts = line.split(",")
            if (parts.size < 8) continue

            // f51:日期 f52:开盘 f53:收盘 f54:最高 f55:最低 f56:成交量 f57:成交额 f58:振幅 f61:涨跌幅
            val dateStr = parts[0].replace("-", "")
            val date = try { LocalDate.parse(dateStr, DATE_FMT).format(STORE_FMT) } catch (_: Exception) { continue }

            results.add(DailySnapshotEntity(
                code = code,
                name = "",  // 名称后续从 stock_basics 表补充
                date = date,
                open = parts[1].toDoubleOrNull() ?: 0.0,
                close = parts[2].toDoubleOrNull() ?: 0.0,
                high = parts[3].toDoubleOrNull() ?: 0.0,
                low = parts[4].toDoubleOrNull() ?: 0.0,
                volume = parts[5].toLongOrNull() ?: 0L,
                amount = parts[6].toDoubleOrNull() ?: 0.0,
                changePct = parts[8].toDoubleOrNull() ?: 0.0,
                turnoverRate = 0.0,
                mainNetInflow = 0.0
            ))
        }

            return results
        } catch (e: Exception) {
            Log.w(TAG, "拉取 $code 失败: ${e.message}")
            return emptyList()
        }
    }
}