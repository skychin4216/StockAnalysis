package com.chin.stockanalysis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chin.stockanalysis.R
import com.chin.stockanalysis.ui.MainActivity

/**
 * 量化前台服务 — 防止系统/MARs 在 pipeline 执行期间杀进程
 *
 * 设计原则：
 * - 仅在 pipeline 执行期间运行（start/stop 由 QuantTaskScheduler 控制）
 * - 持有 PARTIAL_WAKE_LOCK 防止 CPU 休眠
 * - 显示低优先级通知（不干扰用户）
 * - 三星 MARs / 华为 EMUI 等激进电池管理不会杀前台服务
 */
class QuantForegroundService : Service() {

    companion object {
        private const val TAG = "QuantFgService"
        private const val CHANNEL_ID = "quant_execution"
        private const val NOTIFICATION_ID = 9001
        const val ACTION_START = "com.chin.stockanalysis.action.START_QUANT"
        const val ACTION_STOP = "com.chin.stockanalysis.action.STOP_QUANT"
        const val EXTRA_PERIOD = "period_name"

        /** 启动前台服务（pipeline 开始时调用） */
        fun start(context: Context, periodName: String = "") {
            val intent = Intent(context, QuantForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PERIOD, periodName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动前台服务失败: ${e.message}")
            }
        }

        /** 停止前台服务（所有 pipeline 完成后调用） */
        fun stop(context: Context) {
            val intent = Intent(context, QuantForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "停止前台服务失败: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var currentPeriod = ""
    private var isStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentPeriod = intent.getStringExtra(EXTRA_PERIOD) ?: ""
                if (!isStarted) {
                    startForegroundWithNotification()
                    acquireWakeLock()
                    isStarted = true
                } else {
                    updateNotification(currentPeriod)
                }
                Log.i(TAG, "前台服务运行中: period=$currentPeriod")
            }
            ACTION_STOP -> {
                Log.i(TAG, "前台服务停止")
                releaseWakeLock()
                isStarted = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
        Log.i(TAG, "前台服务销毁")
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("量化分析执行中...")
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败: ${e.message}")
        }
    }

    private fun buildNotification(contentText: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("量化分析")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateNotification(periodName: String) {
        if (!isStarted) return
        val text = if (periodName.isNotEmpty()) "正在分析: $periodName" else "量化分析执行中..."
        val notification = buildNotification(text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StockAnalysis:Quant").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)  // 最多 30 分钟
        }
        Log.i(TAG, "WakeLock 已获取")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "WakeLock 已释放")
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "量化分析执行",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "量化 Pipeline 执行期间的前台通知"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
