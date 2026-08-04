package com.chin.stockanalysis.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chin.stockanalysis.R
import com.chin.stockanalysis.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 交易通知服務 — 做T信號 / 建倉信號 推送
 *
 * 支持兩種通知渠道：
 * 1. Android 系統通知（狀態欄推送）
 * 2. 微信推送（ServerChan — 通過 ServerChan 公眾號推送到微信）
 *
 * 用戶可在設置中配置：
 * - 是否啟用系統通知
 * - 是否啟用微信推送
 * - ServerChan SendKey
 */
object TradeNotifier {

    private const val TAG = "TradeNotifier"
    private const val CHANNEL_ID = "trade_signals"
    private const val CHANNEL_NAME = "交易信號"
    private const val PREFS_NAME = "trade_notification"
    private const val KEY_SYSTEM_ENABLED = "system_enabled"
    private const val KEY_WECHAT_ENABLED = "wechat_enabled"
    private const val KEY_SERVERCHAN_KEY = "serverchan_sendkey"
    private const val KEY_PUSHPLUS_TOKEN = "pushplus_token"
    private const val KEY_PUSHPLUS_ENABLED = "pushplus_enabled"
    private const val NOTIFICATION_ID_BASE = 9000

    // ═══════════════════════════════════════
    //  偏好設置
    // ═══════════════════════════════════════

    fun isSystemEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYSTEM_ENABLED, true)

    fun setSystemEnabled(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SYSTEM_ENABLED, enabled).apply()

    fun isWechatEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WECHAT_ENABLED, false)

    fun setWechatEnabled(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WECHAT_ENABLED, enabled).apply()

    fun getServerChanKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVERCHAN_KEY, "") ?: ""

    fun setServerChanKey(ctx: Context, key: String) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVERCHAN_KEY, key).apply()

    fun getPushPlusToken(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PUSHPLUS_TOKEN, "") ?: ""

    fun setPushPlusToken(ctx: Context, token: String) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PUSHPLUS_TOKEN, token).apply()

    fun isPushPlusEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PUSHPLUS_ENABLED, false)

    fun setPushPlusEnabled(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PUSHPLUS_ENABLED, enabled).apply()

    // ═══════════════════════════════════════
    //  通知渠道初始化
    // ═══════════════════════════════════════

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "做T信號、建倉信號等交易通知"
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    // ═══════════════════════════════════════
    //  發送通知（統一入口）
    // ═══════════════════════════════════════

    /**
     * 發送交易信號通知
     * @param ctx Context
     * @param title 通知標題（如 "做T買入信號"）
     * @param body 通知內容
     * @param tag 信號標籤（用於去重，如 "T_BUY_000650_20240804"）
     */
    suspend fun send(ctx: Context, title: String, body: String, tag: String = "") {
        val appCtx = ctx.applicationContext
        // 1. Android 系統通知
        if (isSystemEnabled(appCtx)) {
            sendSystemNotification(appCtx, title, body, tag)
        }
        // 2. 微信推送
        if (isWechatEnabled(appCtx)) {
            val scKey = getServerChanKey(appCtx)
            if (scKey.isNotBlank()) {
                sendServerChan(scKey, title, body)
            }
            val ppToken = getPushPlusToken(appCtx)
            if (ppToken.isNotBlank() && isPushPlusEnabled(appCtx)) {
                sendPushPlus(ppToken, title, body)
            }
        }
    }

    /**
     * 批量發送做T信號通知
     */
    suspend fun sendTTradeSignals(
        ctx: Context,
        signals: List<com.chin.stockanalysis.strategy.trade.TTradeSignal>,
        periodType: String
    ) {
        if (signals.isEmpty()) return
        val appCtx = ctx.applicationContext
        val periodLabel = when (periodType) {
            "UltraShortQuant" -> "超短線"
            "ShortTermQuant" -> "短線"
            "MidTermQuant" -> "中線"
            "LongTermQuant" -> "長線"
            "RealPosition" -> "真實持倉"
            else -> periodType
        }

        // 逐條發送系統通知
        for ((idx, signal) in signals.withIndex()) {
            val title = "${signal.signalType.label} — ${signal.stockName}(${signal.stockCode.takeLast(4)})"
            val body = buildString {
                appendLine("週期: $periodLabel")
                appendLine("價格: ${"%.2f".format(signal.suggestedPrice)} → 目標 ${"%.2f".format(signal.targetPrice)}")
                appendLine("數量: ${signal.quantity}股 | 預期: ${"%.2f%%".format(signal.expectedProfitPct)}")
                append("原因: ${signal.reason}")
            }
            val tag = "T_${signal.signalType.name}_${signal.stockCode}_${System.currentTimeMillis()}"
            send(appCtx, title, body.trim(), tag)
            // 系統通知限流：避免短時間內大量推送
            if (idx > 0 && idx % 3 == 0) {
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    // ═══════════════════════════════════════
    //  Android 系統通知
    // ═══════════════════════════════════════

    private fun sendSystemNotification(ctx: Context, title: String, body: String, tag: String) {
        try {
            ensureChannel(ctx)

            // 檢查通知權限（Android 13+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "無通知權限，跳過系統通知")
                    return
                }
            }

            // 點擊通知後打開主界面
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body.lines().firstOrNull() ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(ctx).notify(
                tag.ifBlank { title },
                NOTIFICATION_ID_BASE + title.hashCode() % 1000,
                notification
            )
            Log.i(TAG, "系統通知已發送: $title")
        } catch (e: Exception) {
            Log.w(TAG, "系統通知發送失敗: ${e.message}")
        }
    }

    // ═══════════════════════════════════════
    //  ServerChan 微信推送
    // ═══════════════════════════════════════

    /**
     * ServerChan 推送（https://sctapi.ftqq.com/）
     *
     * 用戶在 https://sct.ftqq.com 註冊獲取 SendKey，
     * 關注「ServerChan」公眾號即可收到微信推送。
     */
    private suspend fun sendServerChan(sendKey: String, title: String, body: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://sctapi.ftqq.com/$sendKey.send")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val params = "title=${URLEncoder.encode(title.take(100), "UTF-8")}" +
                    "&desp=${URLEncoder.encode(body.take(2000), "UTF-8")}"
            conn.outputStream.use { os ->
                os.write(params.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (code == 200 && resp.contains("\"code\":0")) {
                Log.i(TAG, "ServerChan 推送成功: $title")
            } else {
                Log.w(TAG, "ServerChan 推送失敗: $code $resp")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ServerChan 推送異常: ${e.message}")
        }
    }

    // ═══════════════════════════════════════
    //  PushPlus 微信推送（備選）
    // ═══════════════════════════════════════

    /**
     * PushPlus 推送（https://www.pushplus.plus/）
     *
     * 用戶在 PushPlus 註冊獲取 Token，
     * 關注「pushplus推送助手」公眾號即可收到微信推送。
     */
    private suspend fun sendPushPlus(token: String, title: String, body: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://www.pushplus.plus/send")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val json = """{"token":"$token","title":"${title.take(100).replace("\"", "\\\"")}","content":"${body.take(2000).replace("\"", "\\\"").replace("\n", "\\n")}","template":"txt"}"""
            conn.outputStream.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (code == 200 && resp.contains("\"code\":200")) {
                Log.i(TAG, "PushPlus 推送成功: $title")
            } else {
                Log.w(TAG, "PushPlus 推送失敗: $code $resp")
            }
        } catch (e: Exception) {
            Log.w(TAG, "PushPlus 推送異常: ${e.message}")
        }
    }
}
