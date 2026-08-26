package com.chin.stockanalysis.cloud

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.config.DataConfig
import com.chin.stockanalysis.stock.database.StockDatabase
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 手机端 → 腾讯云 COS 数据闭环
 *
 * 三阶段闭环：
 * 1. **手机上传**：打包数据库关键表（订单/做T/回溯/每日Pipeline/拟合参数）为 ZIP，签名直传 COS
 * 2. **PC 下载回溯**：`smalltools/cloud_download.py` 下载后本地回溯对比、拟合
 * 3. **参数回流**：PC 拟合出新参数后上传 `backtest_params.json`，手机下载并导入立即生效
 *
 * 配置（app_config.json 的 cloud_sync 区块）：
 * ```
 * "cloud_sync": {
 *   "enabled": false,
 *   "bucket": "your-bucket-1250000000",
 *   "region": "ap-guangzhou",
 *   "secret_id": "",
 *   "secret_key": "",
 *   "prefix": "stockanalysis/phone",
 *   "params_key": "stockanalysis/params/backtest_params.json"
 * }
 * ```
 *
 * bucket 建议「公有读私有写」：手机签名直传，参数文件手机可匿名下载。
 */
class CloudSyncManager(private val context: Context) {

    private val db by lazy { StockDatabase.getInstance(context) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = com.google.gson.Gson()

    data class CloudConfig(
        val enabled: Boolean,
        val bucket: String,
        val region: String,
        val secretId: String,
        val secretKey: String,
        val prefix: String,
        val paramsKey: String,
        val dbKey: String,
        val candidatesKey: String
    )

    /** 读取 cloud_sync 配置 */
    fun loadConfig(): CloudConfig = CloudConfig(
        enabled = DataConfig.get("cloud_sync.enabled", "false").toBoolean(),
        bucket = DataConfig.get("cloud_sync.bucket"),
        region = DataConfig.get("cloud_sync.region", "ap-guangzhou"),
        secretId = DataConfig.get("cloud_sync.secret_id"),
        secretKey = DataConfig.get("cloud_sync.secret_key"),
        prefix = DataConfig.get("cloud_sync.prefix", "stockanalysis/phone"),
        paramsKey = DataConfig.get("cloud_sync.params_key", "stockanalysis/params/backtest_params.json"),
        dbKey = DataConfig.get("cloud_sync.db_key", "stockanalysis/db/market_data.db"),
        candidatesKey = DataConfig.get("cloud_sync.candidates_key", "stockanalysis/quant/candidates.json")
    )

    fun isConfigured(cfg: CloudConfig): Boolean =
        cfg.bucket.isNotBlank() && cfg.region.isNotBlank() && cfg.secretId.isNotBlank() && cfg.secretKey.isNotBlank()

    // ═══════════════════════════════════════════════════════════
    // 1. 上传：打包数据 → ZIP → COS PUT
    // ═══════════════════════════════════════════════════════════

    /** 打包当日数据库关键表为 ZIP 字节（内部为 data.json，附当日日志 log_yyyyMMdd.txt）。 */
    suspend fun buildDataPackage(today: String = todayDate()): ByteArray = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("meta", JSONObject().apply {
            put("export_time", nowTimestamp())
            put("app_version", appVersionName())
            put("device", android.os.Build.MODEL)
            put("data_scope", "today:$today")
        })

        root.put("strategy_trade_orders", queryOrders(today))
        root.put("t_trade_records", queryTTrades(today))
        root.put("strategy_trade_backtests", queryBacktests(today))
        root.put("daily_period_result", queryDailyPeriods(today))
        root.put("strategy_trade_fitting_params", queryFittingParams(today))
        root.put("t_trade_recommendations", queryTTradeRecommendations(today))
        root.put("real_positions", queryRealPositions())
        root.put("period_holding_profit", queryHoldingProfit(today))
        root.put("sector_daily_records", querySectorDailyRecords(today))

        // ZIP 压缩
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            // 附加当日 logcat 日志（存在才打包）
            val todayLog = com.chin.stockanalysis.util.FileLogger.readTodayLog()
            if (todayLog.isNotBlank()) {
                zip.putNextEntry(ZipEntry("log_${today.replace("-", "")}.txt"))
                zip.write(todayLog.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        baos.toByteArray()
    }

    /** 上次成功上传的日期（yyyy-MM-dd），未上传过返回 null。 */
    fun lastUploadDate(): String? =
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
            .getString("last_upload_date", null)

    /** 上传当日数据包到 COS，返回 COS 对象路径。同日已上传时跳过（force=true 强制重传）。 */
    suspend fun uploadData(cfg: CloudConfig, force: Boolean = false, onStatus: (String) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isConfigured(cfg)) return@withContext Result.failure(IllegalStateException("云同步未配置（bucket/密钥缺失）"))

            val today = todayDate()
            val prefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
            val lastUpload = prefs.getString("last_upload_date", null)
            if (!force && lastUpload == today) {
                onStatus("今日数据已上传过（$today），已跳过")
                return@withContext Result.success("今日数据已上传过，已跳过重复上传（$today）")
            }

            onStatus("正在打包今日数据…")
            val zipBytes = buildDataPackage(today)
            val filename = "phone_${timestampCompact()}.zip"
            val key = "${cfg.prefix.trim('/')}/$filename"

            onStatus("正在上传 $filename …")
            try {
                val url = "https://${cfg.bucket}.cos.${cfg.region}.myqcloud.com/$key"
                val host = "${cfg.bucket}.cos.${cfg.region}.myqcloud.com"
                val now = System.currentTimeMillis() / 1000
                val end = now + 600

                val headers = mapOf(
                    "host" to host,
                    "content-type" to "application/zip"
                )
                val auth = CosSigner.sign(
                    secretId = cfg.secretId,
                    secretKey = cfg.secretKey,
                    method = "put",
                    uriPathname = "/$key",
                    httpParameters = emptyMap(),
                    httpHeaders = headers,
                    startTime = now,
                    endTime = end
                )

                val body: RequestBody = zipBytes.toRequestBody("application/zip".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .put(body)
                    .header("Authorization", auth)
                    .header("Content-Type", "application/zip")
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        prefs.edit().putString("last_upload_date", today).apply()
                        onStatus("上传成功 ✓ $filename")
                        Result.success(key)
                    } else {
                        val err = resp.body?.string() ?: resp.code.toString()
                        onStatus("上传失败: HTTP ${resp.code}")
                        Result.failure(RuntimeException("COS 上传失败 HTTP ${resp.code}: $err"))
                    }
                }
            } catch (e: Exception) {
                onStatus("上传失败: ${e.message}")
                Result.failure(e)
            }
        }

    // ═══════════════════════════════════════════════════════════
    // 2. 参数回流：下载 backtest_params.json 并导入
    // ═══════════════════════════════════════════════════════════

    /** 从 COS 下载参数文件并导入 App（返回导入消息）。 */
    suspend fun downloadParams(cfg: CloudConfig, onStatus: (String) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            if (cfg.bucket.isBlank() || cfg.paramsKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("参数回流未配置（bucket/params_key 缺失）"))
            }
            onStatus("正在下载最新参数…")
            try {
                val url = "https://${cfg.bucket}.cos.${cfg.region}.myqcloud.com/${cfg.paramsKey}"
                val host = "${cfg.bucket}.cos.${cfg.region}.myqcloud.com"
                val now = System.currentTimeMillis() / 1000
                val end = now + 600
                val auth = CosSigner.sign(
                    secretId = cfg.secretId,
                    secretKey = cfg.secretKey,
                    method = "get",
                    uriPathname = "/${cfg.paramsKey}",
                    httpParameters = emptyMap(),
                    httpHeaders = mapOf("host" to host),
                    startTime = now,
                    endTime = end
                )
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", auth)
                    .header("Host", host)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onStatus("参数下载失败: HTTP ${resp.code}")
                        return@withContext Result.failure(RuntimeException("下载参数失败 HTTP ${resp.code}"))
                    }
                    val jsonText = resp.body?.string() ?: ""
                    if (jsonText.isBlank()) {
                        onStatus("参数文件为空")
                        return@withContext Result.failure(RuntimeException("参数文件为空"))
                    }
                    // 落盘供回溯导入
                    val file = java.io.File(context.filesDir, "cloud_backtest_params.json")
                    file.writeText(jsonText)

                    // 通过 BacktestParamsLoader 导入立即生效
                    val result = com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader.importParams(context, jsonText)
                    if (result == null) {
                        onStatus("参数导入成功 ✓")
                        Result.success("参数导入成功，来源: ${cfg.paramsKey}")
                    } else {
                        onStatus("参数导入失败: $result")
                        Result.failure(RuntimeException("参数导入失败: $result"))
                    }
                }
            } catch (e: Exception) {
                onStatus("参数下载失败: ${e.message}")
                Result.failure(e)
            }
        }

    // ═══════════════════════════════════════════════════════════
    // 3. 行情库同步：下载 PC 端上传的市场库，导入 Room daily_snapshot
    // ═══════════════════════════════════════════════════════════

    /** 下载市场库文件并导入本地。 */
    suspend fun downloadMarketDb(cfg: CloudConfig, onStatus: (String) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            if (cfg.bucket.isBlank() || cfg.dbKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("行情库同步未配置（bucket/db_key 缺失）"))
            }
            onStatus("正在下载行情库…")
            try {
                val url = "https://${cfg.bucket}.cos.${cfg.region}.myqcloud.com/${cfg.dbKey}"
                val host = "${cfg.bucket}.cos.${cfg.region}.myqcloud.com"
                val now = System.currentTimeMillis() / 1000
                val end = now + 600
                val auth = CosSigner.sign(
                    secretId = cfg.secretId,
                    secretKey = cfg.secretKey,
                    method = "get",
                    uriPathname = "/${cfg.dbKey}",
                    httpParameters = emptyMap(),
                    httpHeaders = mapOf("host" to host),
                    startTime = now,
                    endTime = end
                )
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", auth)
                    .header("Host", host)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onStatus("行情库下载失败: HTTP ${resp.code}")
                        return@withContext Result.failure(RuntimeException("下载行情库失败 HTTP ${resp.code}"))
                    }
                    val bytes = resp.body?.bytes() ?: byteArrayOf()
                    if (bytes.size < 4096) {
                        onStatus("行情库文件异常（过小）")
                        return@withContext Result.failure(RuntimeException("行情库文件异常（过小 ${bytes.size} B）"))
                    }
                    // 落盘
                    val file = java.io.File(context.filesDir, "market_db_cloud.db")
                    file.writeBytes(bytes)

                    // 导入 Room daily_snapshot
                    onStatus("正在导入行情数据（${bytes.size / 1048576} MB）…")
                    val msg = importMarketDb(file)
                    onStatus("行情库同步完成 ✓")
                    Result.success(msg)
                }
            } catch (e: Exception) {
                onStatus("行情库下载失败: ${e.message}")
                Result.failure(e)
            }
        }

    // ═══════════════════════════════════════════════════════════
    // 4. PC 候选清单：下载 smalltools 发布的 candidates.json
    // ═══════════════════════════════════════════════════════════

    /** 上次成功下载 PC 候选的时间戳（毫秒），无记录返回 0。 */
    fun lastCandidatesFetchedAt(): Long =
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
            .getLong("last_candidates_fetched_at", 0L)

    /** 从 COS 下载 PC 候选清单（candidates_key），落盘并返回 JSON 文本。 */
    suspend fun downloadCandidates(cfg: CloudConfig, onStatus: (String) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            if (cfg.bucket.isBlank() || cfg.candidatesKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("PC 候选未配置（bucket/candidates_key 缺失）"))
            }
            onStatus("正在下载 PC 候选清单…")
            try {
                val url = "https://${cfg.bucket}.cos.${cfg.region}.myqcloud.com/${cfg.candidatesKey}"
                val host = "${cfg.bucket}.cos.${cfg.region}.myqcloud.com"
                val now = System.currentTimeMillis() / 1000
                val end = now + 600
                val auth = CosSigner.sign(
                    secretId = cfg.secretId,
                    secretKey = cfg.secretKey,
                    method = "get",
                    uriPathname = "/${cfg.candidatesKey}",
                    httpParameters = emptyMap(),
                    httpHeaders = mapOf("host" to host),
                    startTime = now,
                    endTime = end
                )
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", auth)
                    .header("Host", host)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onStatus("候选下载失败: HTTP ${resp.code}")
                        return@withContext Result.failure(RuntimeException("下载 PC 候选失败 HTTP ${resp.code}"))
                    }
                    val jsonText = resp.body?.string() ?: ""
                    if (jsonText.isBlank()) {
                        onStatus("候选文件为空")
                        return@withContext Result.failure(RuntimeException("PC 候选文件为空"))
                    }
                    val file = java.io.File(context.filesDir, "pc_candidates.json")
                    file.writeText(jsonText)
                    context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
                        .edit().putLong("last_candidates_fetched_at", System.currentTimeMillis()).apply()
                    onStatus("PC 候选下载成功 ✓")
                    Result.success(jsonText)
                }
            } catch (e: Exception) {
                onStatus("候选下载失败: ${e.message}")
                Result.failure(e)
            }
        }

    /**
     * 把下载的市场库（表 kline: secid/date/open/high/low/close/volume/change_pct/turnover/name）
     * 导入 Room daily_snapshot 表（code/name/date/open/close/high/low/volume/amount/change_pct/turnover_rate...）。
     *
     * 兼容 minSdk 26 的 SQLite（无 UPSERT 语法），采用「先 UPDATE 已有行、无则 INSERT」，
     * 已有行仅更新行情字段，保留本地基本面（pe/pb/roe 等）与 amount 数据不被云端覆盖。
     */
    private fun importMarketDb(srcFile: java.io.File): String {
        val dest = db.openHelper.writableDatabase
        var src: android.database.sqlite.SQLiteDatabase? = null
        var inserted = 0
        var updated = 0
        try {
            src = android.database.sqlite.SQLiteDatabase.openDatabase(
                srcFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            val upd = dest.compileStatement(
                "UPDATE daily_snapshot SET name=?, open=?, close=?, high=?, low=?, volume=?, change_pct=?, turnover_rate=? " +
                    "WHERE code=? AND date=?"
            )
            val ins = dest.compileStatement(
                "INSERT INTO daily_snapshot (code, name, date, open, close, high, low, volume, amount, change_pct, turnover_rate) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?)"
            )
            src.rawQuery(
                "SELECT secid, name, date, open, high, low, close, volume, change_pct, turnover FROM kline ORDER BY secid, date",
                null
            ).use { c ->
                dest.beginTransaction()
                try {
                    while (c.moveToNext()) {
                        val code = c.getString(0)
                        val name = c.getString(1)
                        val date = c.getString(2)
                        val open = c.getDouble(3)
                        val high = c.getDouble(4)
                        val low = c.getDouble(5)
                        val close = c.getDouble(6)
                        val volume = c.getLong(7)
                        val changePct = c.getDouble(8)
                        val turnover = c.getDouble(9)

                        // 1) 尝试更新已有行
                        upd.bindString(1, name)
                        upd.bindDouble(2, open)
                        upd.bindDouble(3, close)
                        upd.bindDouble(4, high)
                        upd.bindDouble(5, low)
                        upd.bindLong(6, volume)
                        upd.bindDouble(7, changePct)
                        upd.bindDouble(8, turnover)
                        upd.bindString(9, code)
                        upd.bindString(10, date)
                        if (upd.executeUpdateDelete() > 0) {
                            updated++
                        } else {
                            // 2) 不存在则插入（基本面字段用默认 0）
                            ins.bindString(1, code)
                            ins.bindString(2, name)
                            ins.bindString(3, date)
                            ins.bindDouble(4, open)
                            ins.bindDouble(5, close)
                            ins.bindDouble(6, high)
                            ins.bindDouble(7, low)
                            ins.bindLong(8, volume)
                            ins.bindDouble(9, 0.0) // amount 无数据
                            ins.bindDouble(10, changePct)
                            ins.bindDouble(11, turnover)
                            ins.executeInsert()
                            inserted++
                        }
                    }
                    dest.setTransactionSuccessful()
                } finally {
                    dest.endTransaction()
                }
            }
            return "导入完成：新增 $inserted 行、更新 $updated 行（daily_snapshot）"
        } catch (e: Exception) {
            throw e
        } finally {
            src?.close()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 数据查询（与 DataExportImport 输出键保持一致）
    // ═══════════════════════════════════════════════════════════

    private suspend fun queryOrders(today: String): JSONArray {
        val arr = JSONArray()
        try {
            for (o in db.strategyTradeOrderDao().getByDate(today)) {
                arr.put(JSONObject().apply {
                    put("strategy_id", o.strategyId)
                    put("stock_code", o.stockCode)
                    put("stock_name", o.stockName)
                    put("trade_date", o.tradeDate)
                    put("buy_price", o.buyPrice)
                    put("buy_time", o.buyTime)
                    put("quantity", o.quantity)
                    put("order_type", o.orderType)
                    put("sell_price", o.sellPrice)
                    put("sell_time", o.sellTime)
                    put("profit_pct", o.profitPct)
                    put("status", o.status)
                    put("reason", o.reason)
                    put("score_at_buy", o.scoreAtBuy)
                })
            }
        } catch (e: Exception) { Log.w(TAG, "queryOrders: ${e.message}") }
        return arr
    }

    private suspend fun queryTTrades(today: String): JSONArray {
        val arr = JSONArray()
        try {
            for (period in PERIODS) {
                for (t in db.tTradeRecordDao().getByPeriodAndDate(period, today)) {
                    arr.put(JSONObject().apply {
                        put("stock_code", t.stockCode)
                        put("stock_name", t.stockName)
                        put("trade_date", t.tradeDate)
                        put("trade_type", t.tradeType)
                        put("quantity", t.quantity)
                        put("price", t.price)
                        put("paired_price", t.pairedPrice)
                        put("profit", t.profit)
                        put("profit_pct", t.profitPct)
                        put("status", t.status)
                        put("period_type", t.periodType)
                    })
                }
            }
        } catch (e: Exception) { Log.w(TAG, "queryTTrades: ${e.message}") }
        return arr
    }

    private suspend fun queryBacktests(today: String): JSONArray {
        val arr = JSONArray()
        try {
            for (b in db.strategyTradeBacktestDao().getByDate(today)) {
                arr.put(JSONObject().apply {
                    put("period_key", b.periodKey)
                    put("strategy_id", b.strategyId)
                    put("strategy_name", b.strategyName)
                    put("trade_date", b.tradeDate)
                    put("total_days", b.totalDays)
                    put("signal_count", b.signalCount)
                    put("correct_count", b.correctCount)
                    put("accuracy", b.accuracy)
                    put("avg_return", b.avgReturn)
                    put("max_gain", b.maxGain)
                    put("max_loss", b.maxLoss)
                    put("created_at", b.createdAt)
                })
            }
        } catch (e: Exception) { Log.w(TAG, "queryBacktests: ${e.message}") }
        return arr
    }

    private suspend fun queryDailyPeriods(today: String): JSONArray {
        val arr = JSONArray()
        try {
            for (p in db.dailyPeriodResultDao().getByDate(today)) {
                arr.put(JSONObject().apply {
                    put("strategy_id", p.strategyId)
                    put("trade_date", p.tradeDate)
                    put("period_days", p.periodDays)
                    put("stock_codes", p.stockCodesJson)
                    put("final_top3", p.finalTop3Json)
                    put("filtered_reason", p.filteredReasonJson)
                })
            }
        } catch (e: Exception) { Log.w(TAG, "queryDailyPeriods: ${e.message}") }
        return arr
    }

    private suspend fun querySectorDailyRecords(today: String): JSONArray {
        val arr = JSONArray()
        try {
            for (s in db.sectorDailyRecordDao().getByDate(today)) {
                arr.put(JSONObject().apply {
                    put("date", s.date)
                    put("sector_code", s.sectorCode)
                    put("sector_name", s.sectorName)
                    put("change_pct", s.changePct)
                    put("main_net_inflow", s.mainNetInflow)
                    put("hot_score", s.hotScore)
                    put("composite_score", s.compositeScore)
                    put("rank", s.rank)
                    put("is_hot", s.isHot)
                    put("consecutive_hot_days", s.consecutiveHotDays)
                })
            }
        } catch (e: Exception) { Log.w(TAG, "querySectorDailyRecords: ${e.message}") }
        return arr
    }

    private suspend fun queryFittingParams(today: String): JSONArray {
        val arr = JSONArray()
        try {
            for (p in db.strategyTradeFittingParamDao().getByDate(today)) {
                arr.put(JSONObject().apply {
                    put("strategy_id", p.strategyId)
                    put("trade_date", p.tradeDate)
                    put("period_days", p.periodDays)
                    put("param_json", p.paramJson)
                    put("fitting_round", p.fittingRound)
                    put("accuracy", p.accuracy)
                    put("avg_return", p.avgReturn)
                })
            }
        } catch (e: Exception) { Log.w(TAG, "queryFittingParams: ${e.message}") }
        return arr
    }

    private suspend fun queryTTradeRecommendations(today: String): JSONArray {
        val arr = JSONArray()
        try {
            for (r in db.tTradeRecommendationDao().getByDate(today)) {
                arr.put(JSONObject().apply {
                    put("stock_code", r.stockCode)
                    put("stock_name", r.stockName)
                    put("trade_date", r.tradeDate)
                    put("period_type", r.periodType)
                    put("signal_type", r.signalType)
                    put("suggested_price", r.suggestedPrice)
                    put("target_price", r.targetPrice)
                    put("quantity", r.quantity)
                    put("expected_profit_pct", r.expectedProfitPct)
                    put("status", r.status)
                    put("reason", r.reason)
                })
            }
        } catch (e: Exception) { Log.w(TAG, "queryTTradeRecommendations: ${e.message}") }
        return arr
    }

    private suspend fun queryRealPositions(): JSONArray {
        val arr = JSONArray()
        try {
            for (p in db.realPositionDao().getAllActive()) {
                arr.put(JSONObject().apply {
                    put("stock_code", p.stockCode)
                    put("stock_name", p.stockName)
                    put("period_type", p.periodType)
                    put("quantity", p.quantity)
                    put("avg_buy_price", p.avgBuyPrice)
                    put("buy_date", p.buyDate)
                    put("current_price", p.currentPrice)
                    put("sector", p.sector)
                })
            }
        } catch (e: Exception) { Log.w(TAG, "queryRealPositions: ${e.message}") }
        return arr
    }

    private suspend fun queryHoldingProfit(today: String): JSONArray {
        val arr = JSONArray()
        try {
            for (h in db.periodHoldingProfitDao().getByDate(today)) {
                arr.put(JSONObject().apply {
                    put("period_type", h.periodType)
                    put("trade_date", h.tradeDate)
                    put("holding_count", h.holdingCount)
                    put("total_cost", h.totalCost)
                    put("total_value", h.totalValue)
                    put("total_pnl", h.totalPnl)
                    put("total_pnl_pct", h.totalPnlPct)
                    put("stock_codes", h.stockCodes)
                })
            }
        } catch (e: Exception) { Log.w(TAG, "queryHoldingProfit: ${e.message}") }
        return arr
    }

    /** 从 PackageManager 读取版本名（项目未开启 buildConfig，避免依赖 BuildConfig）。 */
    private fun appVersionName(): String =
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    companion object {
        private const val TAG = "CloudSyncManager"
        private val PERIODS = listOf("UltraShortQuant", "ShortTermQuant", "MidTermQuant", "LongTermQuant")

        private fun nowTimestamp(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

        private fun timestampCompact(): String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())

        /** 当日日期（yyyy-MM-dd），用于按日增量查询与上传去重。 */
        private fun todayDate(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    }
}
