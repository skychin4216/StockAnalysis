package com.chin.stockanalysis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chin.stockanalysis.R
import com.chin.stockanalysis.strategy.ssa.SsaAlertBatch
import com.chin.stockanalysis.strategy.ssa.SsaAlertStore
import com.chin.stockanalysis.strategy.ssa.SsaT0PoolStore
import com.chin.stockanalysis.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * ## 短线预警系统悬浮窗服务
 *
 * App 退到后台（[MainActivity.onStop]）时启动，在系统层显示持仓股的短线信号浮窗；
 * 回到前台（[MainActivity.onStart]）时停止并移除视图。
 *
 * - **三重自检**（[start] 与 [onCreate] 双重执行）：悬浮窗授权 + 开关开启 + 持仓池非空，
 *   任一不满足即 [stopSelf]（绝不在无授权时 addView，否则 BadTokenException）。
 * - 前台服务类型 `specialUse`（见 AndroidManifest）；**不取 WakeLock**（被动展示）。
 * - 打开时先渲染本地缓存（秒开），缓存超 [STALE_MS] 或为空才联网重算；
 *   之后**仅 A 股交易时段**每 [LOOP_MS] 重算一次；用户可随时点「🔄 更新」强制重算。
 */
class SsaAlertOverlayService : Service() {

    companion object {
        private const val TAG = "SsaAlertOverlay"
        private const val CHANNEL_ID = "stock_alert_overlay"
        private const val NOTIFICATION_ID = 9002
        const val ACTION_START = "com.chin.stockanalysis.action.ALERT_OVERLAY_START"
        const val ACTION_STOP = "com.chin.stockanalysis.action.ALERT_OVERLAY_STOP"
        const val ACTION_REFRESH = "com.chin.stockanalysis.action.ALERT_OVERLAY_REFRESH"

        private const val STALE_MS = 10 * 60 * 1000L    // 缓存超过 10 分钟才自动联网
        private const val LOOP_MS = 5 * 60 * 1000L      // 盘中每 5 分钟重算

        /** 前置自检：不满足则直接返回（绝不启动服务） */
        private fun canRun(context: Context): Boolean =
            Settings.canDrawOverlays(context) &&
                SsaAlertStore.isOverlayEnabled(context) &&
                SsaT0PoolStore.load(context).isNotEmpty()

        fun start(context: Context) {
            if (!canRun(context)) {
                Log.i(TAG, "自检未通过，不启动浮窗（权限/开关/持仓池）")
                return
            }
            val intent = Intent(context, SsaAlertOverlayService::class.java).apply { action = ACTION_START }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "启动失败: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SsaAlertOverlayService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "停止失败: ${e.message}")
            }
        }

        fun refresh(context: Context) {
            val intent = Intent(context, SsaAlertOverlayService::class.java).apply { action = ACTION_REFRESH }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "刷新失败: ${e.message}")
            }
        }

        /** A 股交易时段：周一~周五 09:30–11:30 / 13:00–15:00 */
        private fun isTradingTime(): Boolean {
            val c = Calendar.getInstance()
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
            val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            return (m in 570..690) || (m in 780..900)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var overlayView: SsaAlertOverlayView? = null
    private var isStarted = false
    private var refreshing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!canRun(this)) { stopSelf(); return START_NOT_STICKY }
                // 必须在 5s 内落地前台通知
                startForegroundWithNotification()
                isStarted = true
                attachOverlay()
                // 先渲染缓存（秒开），再按需联网
                val cached = SsaAlertStore.loadRows(this)
                overlayView?.render(cached)
                val stale = System.currentTimeMillis() - SsaAlertStore.lastRefreshAt(this) > STALE_MS
                if (cached.isEmpty() || stale) doRefresh()
                startLoop()
            }
            ACTION_REFRESH -> {
                if (!isStarted) { stopSelf(); return START_NOT_STICKY }
                doRefresh()
            }
            ACTION_STOP -> {
                releaseOverlay()
                isStarted = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户"退出软件后浮窗常驻" → 不 stopSelf，仅保持现有浮窗
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        loopJob?.cancel()
        releaseOverlay()
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "浮窗服务销毁")
    }

    // ── 浮窗 ────────────────────────────────────────────

    private fun attachOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val v = SsaAlertOverlayView(this) { doRefresh() }
        v.attach()
        overlayView = v
    }

    private fun releaseOverlay() {
        overlayView?.detach()
        overlayView = null
    }

    // ── 数据 ────────────────────────────────────────────

    private fun doRefresh() {
        if (refreshing) return
        refreshing = true
        overlayView?.setUpdating(true)
        scope.launch {
            try {
                val members = withContext(Dispatchers.IO) { SsaT0PoolStore.load(this@SsaAlertOverlayService) }
                if (members.isEmpty()) {
                    overlayView?.render(emptyList())
                    releaseOverlay()
                    isStarted = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                val rows = SsaAlertBatch.run(this@SsaAlertOverlayService, members)
                SsaAlertStore.saveRows(this@SsaAlertOverlayService, rows)
                SsaAlertStore.setLastRefreshAt(this@SsaAlertOverlayService, System.currentTimeMillis())
                overlayView?.render(rows)
                updateNotification("已更新 ${rows.size} 只持仓 · ${rows.count { it.advice == "买入" }} 买 / ${rows.count { it.advice == "卖出" }} 卖")
            } catch (e: Exception) {
                Log.w(TAG, "刷新失败: ${e.message}")
            } finally {
                refreshing = false
                overlayView?.setUpdating(false)
            }
        }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isStarted) {
                delay(LOOP_MS)
                if (isStarted && isTradingTime()) doRefresh()
            }
        }
    }

    // ── 通知 ────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification("持仓股短线预警运行中"))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败: ${e.message}")
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("短线预警")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!isStarted) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "短线预警浮窗", NotificationManager.IMPORTANCE_LOW).apply {
                description = "持仓股短线信号悬浮窗（退出 App 后显示）"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }
}
