package com.chin.stockanalysis.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chin.stockanalysis.stock.database.AppBackgroundRunner
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 实仓日K「每日收盘兜底」OS 级调度（WorkManager）。
 *
 * 背景：AppBackgroundRunner 里的实仓日K补齐（syncRealPositionDailyData）原本只挂在
 * App 进程内每 5 分钟的 monitor 循环中执行；App 进入后台、进程被系统回收后不会执行，
 * 导致收盘后实仓股票的当日完整日K/现价缺失（做T、盈亏、止损依据都缺当日数据）。
 *
 * 机制（两条自维持链，App 不打开也能到点执行）：
 *  - AM = 11:35（上午收盘 11:30 +5min 网络缓冲）、PM = 15:05（下午收盘 +5min）；
 *  - 每个 Worker 执行完毕后调用 schedule()：
 *      * 当前窗口正 RUNNING → KEEP 跳过，不会重复入队；
 *      * AM 完成后会顺排「当天 PM」；PM 完成后会顺排「次日 AM」→ 天然循环；
 *  - MainActivity 每次启动也调用 schedule()，负责首次安装 / 升级 / 断链后的兜底；
 *  - 目标时刻顺延遇周末/节假日同样安全：syncRealPositionDailyData 以最近交易日为目标、
 *    幂等且空跑开销极低，反而能补上缺失交易日的实仓日K。
 */
object RealPositionDailySyncScheduler {

    private const val TAG = "RealPosSync"
    const val WORK_AM = "realpos-daily-am"
    const val WORK_PM = "realpos-daily-pm"

    /** 上午收盘后（11:30 收盘 + 5 分钟缓冲，避开瞬时网络拥塞） */
    private val AM_TIME = LocalTime.of(11, 35)
    /** 下午收盘后（15:00 收盘 + 5 分钟缓冲） */
    private val PM_TIME = LocalTime.of(15, 5)

    /**
     * 注册/续排两个收盘窗口的一次性任务。
     * 幂等：同名任务已 ENQUEUED/RUNNING 时 KEEP 保留原计划，不重复、不重置 delay。
     * 可安全地在 MainActivity 启动、Worker 执行完毕等处重复调用。
     */
    fun schedule(context: Context) {
        enqueue(context, WORK_AM, AM_TIME)
        enqueue(context, WORK_PM, PM_TIME)
        Log.i(TAG, "📅 收盘兜底调度已注册: AM $AM_TIME / PM $PM_TIME")
    }

    private fun enqueue(context: Context, workName: String, at: LocalTime) {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(at)
        if (!target.isAfter(now)) {
            target = target.plusDays(1) // 当日窗口已过 → 顺延次日（周末亦然，Worker 幂等）
        }
        val delayMs = Duration.between(now, target).toMillis().coerceAtLeast(1000L)
        val request = OneTimeWorkRequestBuilder<RealPositionDailySyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("window" to workName))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
    }
}

/** OS 级兜底任务：只做「实仓日K补齐」，不触碰现有循环 / 做T / 推送逻辑。 */
class RealPositionDailySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private companion object {
        private const val TAG = "RealPosSyncWorker"
    }

    override suspend fun doWork(): Result {
        val window = inputData.getString("window") ?: RealPositionDailySyncScheduler.WORK_AM
        val label = when (window) {
            RealPositionDailySyncScheduler.WORK_AM -> "上午收盘(11:35)"
            RealPositionDailySyncScheduler.WORK_PM -> "下午收盘(15:05)"
            else -> window
        }
        return try {
            AppBackgroundRunner.syncRealPositionsForWorker(applicationContext)
            // 自维持续排：当前窗口 RUNNING 被 KEEP 跳过，顺排另一窗口 / 次日
            RealPositionDailySyncScheduler.schedule(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "$label 实仓日K兜底失败: ${e.message}")
            Result.retry()
        }
    }
}
