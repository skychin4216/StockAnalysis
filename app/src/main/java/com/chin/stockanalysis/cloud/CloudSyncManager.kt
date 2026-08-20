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
        val paramsKey: String
    )

    /** 读取 cloud_sync 配置 */
    fun loadConfig(): CloudConfig = CloudConfig(
        enabled = DataConfig.get("cloud_sync.enabled", "false").toBoolean(),
        bucket = DataConfig.get("cloud_sync.bucket"),
        region = DataConfig.get("cloud_sync.region", "ap-guangzhou"),
        secretId = DataConfig.get("cloud_sync.secret_id"),
        secretKey = DataConfig.get("cloud_sync.secret_key"),
        prefix = DataConfig.get("cloud_sync.prefix", "stockanalysis/phone"),
        paramsKey = DataConfig.get("cloud_sync.params_key", "stockanalysis/params/backtest_params.json")
    )

    fun isConfigured(cfg: CloudConfig): Boolean =
        cfg.bucket.isNotBlank() && cfg.region.isNotBlank() && cfg.secretId.isNotBlank() && cfg.secretKey.isNotBlank()

    // ═══════════════════════════════════════════════════════════
    // 1. 上传：打包数据 → ZIP → COS PUT
    // ═══════════════════════════════════════════════════════════

    /** 打包数据库关键表为 ZIP 字节（内部为 data.json）。 */
    suspend fun buildDataPackage(): ByteArray = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("meta", JSONObject().apply {
            put("export_time", nowTimestamp())
            put("app_version", appVersionName())
            put("device", android.os.Build.MODEL)
        })

        root.put("strategy_trade_orders", queryOrders())
        root.put("t_trade_records", queryTTrades())
        root.put("strategy_trade_backtests", queryBacktests())
        root.put("daily_period_result", queryDailyPeriods())
        root.put("strategy_trade_fitting_params", queryFittingParams())
        root.put("t_trade_recommendations", queryTTradeRecommendations())
        root.put("real_positions", queryRealPositions())
        root.put("period_holding_profit", queryHoldingProfit())

        // ZIP 压缩
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        baos.toByteArray()
    }

    /** 上传数据包到 COS，返回 COS 对象路径。 */
    suspend fun uploadData(cfg: CloudConfig, onStatus: (String) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isConfigured(cfg)) return@withContext Result.failure(IllegalStateException("云同步未配置（bucket/密钥缺失）"))

            onStatus("正在打包数据…")
            val zipBytes = buildDataPackage()
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
                val request = Request.Builder().url(url).get().build()
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
    // 数据查询（与 DataExportImport 输出键保持一致）
    // ═══════════════════════════════════════════════════════════

    private suspend fun queryOrders(): JSONArray {
        val arr = JSONArray()
        try {
            for (o in db.strategyTradeOrderDao().getRecent(5000)) {
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

    private suspend fun queryTTrades(): JSONArray {
        val arr = JSONArray()
        try {
            for (period in PERIODS) {
                for (t in db.tTradeRecordDao().getRecentByPeriod(period, "2000-01-01")) {
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

    private suspend fun queryBacktests(): JSONArray {
        val arr = JSONArray()
        try {
            for (b in db.strategyTradeBacktestDao().getAll()) {
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

    private suspend fun queryDailyPeriods(): JSONArray {
        val arr = JSONArray()
        try {
            for (p in db.dailyPeriodResultDao().getRecent(3000)) {
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

    private suspend fun queryFittingParams(): JSONArray {
        val arr = JSONArray()
        try {
            for (p in db.strategyTradeFittingParamDao().getRecentByStrategy("all", 5000)) {
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

    private suspend fun queryTTradeRecommendations(): JSONArray {
        val arr = JSONArray()
        try {
            for (r in db.tTradeRecommendationDao().getRecent("2000-01-01", 2000)) {
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

    private suspend fun queryHoldingProfit(): JSONArray {
        val arr = JSONArray()
        try {
            for (h in db.periodHoldingProfitDao().getLatestAllPeriods()) {
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
    }
}
