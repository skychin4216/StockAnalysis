package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.HttpClientProvider
import com.chin.stockanalysis.stock.database.StockBasicEntity
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
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

        /** A股精選股票池 (~200只)：覆蓋各行業龍頭 + 成交活躍股
         *  理念：全市場5000只 → 篩選200只值得投資的 → 每次按熱門板塊選前100只
         *  符合梁文峰量化策略 — 專注少數優質股，多因子+動態調權+跨行業分散
         */
        internal val TOP_STOCKS = listOf(
            // ═══ 金融 (銀行/保險/券商) ═══ 18只
            "sh601398", "sh601939", "sh601288", "sh600036", "sh600016", "sh600000",
            "sh601318", "sh601628", "sh601601", "sh600030", "sh601688", "sh600837",
            "sh601066", "sh600999", "sz000001", "sz002142", "sz002736", "sz000776",
            // ═══ 白酒/食品飲料 ═══ 15只
            "sh600519", "sz000858", "sz000568", "sh600809", "sz002304", "sh600600",
            "sh603369", "sz000596", "sh600132", "sz000799", "sz002568", "sh600779",
            "sz000895", "sh603288", "sz002557",
            // ═══ 醫藥/醫療 ═══ 20只
            "sh600276", "sz300760", "sh603259", "sz002007", "sz300122", "sh600085",
            "sz000538", "sz300015", "sh600196", "sh688180", "sz300003", "sz300347",
            "sh603392", "sz002821", "sh688271", "sz300529", "sz000963", "sh600511",
            "sh600436", "sz002001",
            // ═══ 新能源/光伏/鋰電 ═══ 18只
            "sz300750", "sz002594", "sh601012", "sh600438", "sz002459", "sz300274",
            "sh688599", "sz002460", "sz000591", "sh600875", "sz300763", "sh605117",
            "sz002506", "sh600732", "sz300118", "sh688223", "sz002340", "sh600885",
            // ═══ 半導體/芯片 ═══ 15只
            "sh688981", "sz002371", "sh603501", "sz300661", "sh688012", "sh688036",
            "sz002049", "sh600703", "sz300782", "sh688256", "sh688008", "sz300474",
            "sh688396", "sz002156", "sh600584",
            // ═══ 消費電子/汽車 ═══ 18只
            "sz002475", "sh600104", "sz000725", "sz002594", "sh601238", "sh600660",
            "sz300433", "sz002241", "sh600031", "sz000333", "sh600183", "sz002230",
            "sh601138", "sz000625", "sh600418", "sz002050", "sh600741", "sz002920",
            // ═══ 通信/5G/算力 ═══ 15只
            "sh600050", "sz000063", "sh601728", "sz002463", "sh600498", "sz300308",
            "sh688041", "sz300502", "sh603236", "sz300394", "sh600745", "sz002281",
            "sh688313", "sz000988", "sh600105",
            // ═══ 有色/煤炭/材料 ═══ 15只
            "sz002415", "sh601899", "sz000651", "sz000002", "sh600585", "sh600188",
            "sh601088", "sh600362", "sz000630", "sz000983", "sh600489", "sh601600",
            "sz002460", "sh601168", "sz002756",
            // ═══ 基建/地產/建材 ═══ 12只
            "sh601390", "sh601668", "sh600048", "sz001979", "sh600383", "sh600325",
            "sh600585", "sh600031", "sh600176", "sh600801", "sh600886", "sz002271",
            // ═══ 交通運輸/物流 ═══ 10只
            "sh601111", "sh600029", "sh601919", "sz002352", "sh600009", "sh600115",
            "sz002120", "sh603128", "sh600233", "sz002468",
            // ═══ 公用事業/環保 ═══ 8只
            "sh600900", "sh601857", "sh600025", "sz000027", "sh600886",
            "sh600011", "sz003816", "sh600674",
            // ═══ 軟件/互聯網 ═══ 10只
            "sz002230", "sz300059", "sh600536", "sz300033", "sh688111",
            "sz300454", "sh688561", "sz002602", "sz300624", "sh600570"
        )
    }

    /** 历史K线专用客户端：更长超时，适应大批量数据 */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 120, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
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

        // 已有数据日期去重：跳过已存在数据的日期
        val existingDates = db.dailySnapshotDao().getAvailableDates(500).toSet()
        val todayStr = LocalDate.now().format(STORE_FMT)
        val alreadyImported = existingDates.contains(todayStr)

        // 檢查今天數據是否完整（至少要有 TOP_STOCKS 中 80% 的數據才算完整）
        val todayDataCount = try {
            db.dailySnapshotDao().getByDate(todayStr).size
        } catch (_: Exception) { 0 }
        val isComplete = todayDataCount >= (TOP_STOCKS.size * 0.8).toInt()

        if (alreadyImported && existingDates.size > 10 && isComplete) {
            Log.i(TAG, "✅ 已有 ${existingDates.size} 个交易日数据 (今日${todayDataCount}只, 完整)，跳过重复导入")
            return@withContext 0
        }
        if (alreadyImported && !isComplete) {
            Log.i(TAG, "⚠️ 今日数据不完整 (${todayDataCount}/${TOP_STOCKS.size}隻)，重新拉取")
        }

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
                val (records, name) = job.await()
                if (records.isNotEmpty()) {
                    db.dailySnapshotDao().insertAll(records)
                    totalRecords += records.size
                    // 同步写入 stock_basics 表（确保名称映射完整）
                    if (name.isNotBlank()) {
                        try {
                            db.stockBasicDao().insert(StockBasicEntity(
                                code = records.first().code,
                                name = name,
                                business = ""
                            ))
                        } catch (_: Exception) { /* 已存在则忽略 */ }
                    }
                }
                onProgress?.invoke(FetchProgress(
                    totalStocks = totalStocks,
                    completedStocks = minOf(TOTAL_COMPLETE + batch.size, totalStocks),
                    totalRecords = totalRecords
                ))
                TOTAL_COMPLETE++
            }
        }

        // 补齐缺失的股票名称（旧数据可能为空）
        val filledCount = fillMissingNames()
        if (filledCount > 0) {
            Log.i(TAG, "✅ 补齐了 $filledCount 只股票的名称映射")
        }

        Log.i(TAG, "✅ 历史数据拉取完成: $totalRecords 条记录")
        totalRecords
    }

    private var TOTAL_COMPLETE = 0  // 线程安全的计数器简化

    /**
     * 修正所有股票名称（包括空名和错名）。
     * 从东方财富 API 逐一查询正确名称，覆盖 daily_snapshot 和 stock_basics。
     */
    private suspend fun fillMissingNames(): Int {
        try {
            // 收集所有出现过的股票代码
            val recentDates = db.dailySnapshotDao().getAvailableDates(10)
            if (recentDates.isEmpty()) return 0
            val allCodes = mutableSetOf<String>()
            for (date in recentDates) {
                val shots = db.dailySnapshotDao().getByDate(date)
                for (s in shots) allCodes.add(s.code)
            }
            if (allCodes.isEmpty()) return 0

            Log.i(TAG, "正在为 ${allCodes.size} 只股票修正名称...")
            var corrected = 0
            for (code in allCodes) {
                try {
                    val (records, name) = fetchOneStock(code,
                        startDate = LocalDate.now().minusDays(3),
                        endDate = LocalDate.now())
                    if (name.isNotBlank()) {
                        // 覆盖 stock_basics（REPLACE 策略更新旧值）
                        db.stockBasicDao().insert(StockBasicEntity(code = code, name = name, business = ""))
                        // 覆盖 daily_snapshot 中该 code 的所有名称
                        db.dailySnapshotDao().updateName(code, name)
                        corrected++
                    }
                } catch (_: Exception) { /* skip */ }
            }
            Log.i(TAG, "名称修正完成: $corrected/${allCodes.size}")
            return corrected
        } catch (e: Exception) {
            Log.w(TAG, "名称修正失败: ${e.message}")
            return 0
        }
    }

    /**
     * 拉取单只股票的历史K线
     */
    /** 備選API: 新浪財經 (無需Referer, 返回JSON格式不同) */
    private suspend fun fetchFromSina(code: String, startDate: LocalDate, endDate: LocalDate): List<DailySnapshotEntity> {
        val prefix = if (code.startsWith("sh")) "sh" else "sz"
        val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?" +
                "symbol=${prefix}$pureCode&scale=240&ma=no&datalen=300"
        try {
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val arr = org.json.JSONArray(body)
            val results = mutableListOf<DailySnapshotEntity>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val dateStr = obj.optString("day", "").replace("-", "")
                if (dateStr.isEmpty()) continue
                val date = try { LocalDate.parse(dateStr, DATE_FMT).format(STORE_FMT) } catch (_: Exception) { continue }
                if (date < startDate.format(STORE_FMT) || date > endDate.format(STORE_FMT)) continue
                results.add(DailySnapshotEntity(
                    code = code, name = "", date = date,
                    open = obj.optDouble("open", 0.0),
                    close = obj.optDouble("close", 0.0),
                    high = obj.optDouble("high", 0.0),
                    low = obj.optDouble("low", 0.0),
                    volume = obj.optLong("volume", 0),
                    amount = 0.0, changePct = 0.0,
                    turnoverRate = 0.0, mainNetInflow = 0.0
                ))
            }
            return results
        } catch (e: Exception) {
            Log.w(TAG, "新浪备选API拉取 $code 失败: ${e.message}")
            return emptyList()
        }
    }

    internal suspend fun fetchOneStock(
        code: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Pair<List<DailySnapshotEntity>, String> {
        // 主API: 新浪财经（更稳定，无需Referer防爬）
        val sinaResults = fetchFromSina(code, startDate, endDate)
        if (sinaResults.isNotEmpty()) {
            val name = sinaResults.firstOrNull()?.name ?: ""
            return Pair(sinaResults, name)
        }
        // 备选: 东方财富历史K线
        try {
            val market = if (code.startsWith("sh")) 1 else if (code.startsWith("bj")) 1 else 0
            val pureCode = code.removePrefix("sh").removePrefix("sz").removePrefix("bj")
            val beg = startDate.format(DATE_FMT)
            val end = endDate.format(DATE_FMT)

            val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?" +
                    "secid=$market.$pureCode" +
                    "&klt=101" +          // 日K
                    "&fqt=1" +            // 前复权
                    "&fields1=f1,f2,f3" +
                    "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f61" +
                    "&beg=$beg&end=$end&lmt=300"
            Log.d(TAG, "🌐 K线请求: $code beg=$beg end=$end market=$market")

            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://quote.eastmoney.com/")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")

            val body = resp.body?.string() ?: throw Exception("empty body")
            val data = JSONObject(body).optJSONObject("data") ?: throw Exception("no data field")
            val klines = data.optJSONArray("klines") ?: throw Exception("no klines field")

            // 从顶层 data JSON 提取股票名称，去除除权除息前缀 XD/XR/DR
            val rawName = data.optString("name", "").trim()
            val stockName = if (rawName.startsWith("XD") || rawName.startsWith("XR") || rawName.startsWith("DR")) {
                rawName.removePrefix("XD").removePrefix("XR").removePrefix("DR").trim()
            } else rawName.takeIf { it.isNotBlank() && it.length < 20 } ?: ""

            val results = mutableListOf<DailySnapshotEntity>()
            for (i in 0 until klines.length()) {
                val line = klines.getString(i)
                val parts = line.split(",")
                if (parts.size < 9) continue

                val dateStr = parts[0].replace("-", "")
                val date = try { LocalDate.parse(dateStr, DATE_FMT).format(STORE_FMT) } catch (_: Exception) { continue }

                results.add(DailySnapshotEntity(
                    code = code, name = stockName, date = date,
                    open = parts[1].toDoubleOrNull() ?: 0.0,
                    close = parts[2].toDoubleOrNull() ?: 0.0,
                    high = parts[3].toDoubleOrNull() ?: 0.0,
                    low = parts[4].toDoubleOrNull() ?: 0.0,
                    volume = parts[5].toLongOrNull() ?: 0L,
                    amount = parts[6].toDoubleOrNull() ?: 0.0,
                    changePct = parts[8].toDoubleOrNull() ?: 0.0,
                    turnoverRate = 0.0, mainNetInflow = 0.0
                ))
            }
            return Pair(results, stockName)
        } catch (e: Exception) {
            Log.w(TAG, "东方财富K线 $code 失败: ${e.message}，尝试新浪备选...")
            // 备选: 新浪财经API
            val sinaResults = fetchFromSina(code, startDate, endDate)
            val name = sinaResults.firstOrNull()?.name ?: ""
            return Pair(sinaResults, name)
        }
    }
}