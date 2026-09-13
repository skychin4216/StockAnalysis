package com.chin.stockanalysis.cloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chin.stockanalysis.news.TopInstitutionNewsCollector
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.FullCycleBacktestEngine
import com.chin.stockanalysis.strategy.data.HistoricalDataFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * ## adb 广播命令接收器
 *
 * 通过 adb 发送广播，触发「检查数据新鲜度 → 增量下载 → 同步 COS」闭环，
 * 方便自动化/调试时在无 UI 环境下维护数据链路。
 *
 * 用法示例：
 * ```
 * # 默认闭环：检查新鲜度 → 增量下载 → 上传 COS
 * adb shell am broadcast -a com.chin.stockanalysis.CMD --es command refresh
 *
 * # 只上传 COS（--ez force true 可忽略当日已上传缓存强制上传）
 * adb shell am broadcast -a com.chin.stockanalysis.CMD --es command sync --ez force true
 *
 * # 只同步用户「重点关注板块」到 COS（sync/refresh/backtest 均会顺带执行）
 * adb shell am broadcast -a com.chin.stockanalysis.CMD --es command focus
 *
 * # 只增量下载缺失数据
 * adb shell am broadcast -a com.chin.stockanalysis.CMD --es command download
 *
 * # 只打印数据新鲜度与 COS 配置状态
 * adb shell am broadcast -a com.chin.stockanalysis.CMD --es command status
 *
 * # 触发全球 Top10 机构研报新闻因子收集
 * adb shell am broadcast -a com.chin.stockanalysis.CMD --es command news
 *
 * # 一键数据维护：同步本周数据 → 四周期回溯 → 中线/长线拟合 → 上传 COS
 * adb shell am broadcast -a com.chin.stockanalysis.CMD --es command backtest --ez force true
 * ```
 *
 * extra 参数：
 * - `command`：refresh（默认）/ sync / focus / download / status / news / backtest
 * - `force`：布尔，是否忽略当日缓存强制刷新（用于 sync / refresh / backtest）
 *
 * 执行结果通过 Logcat（TAG=`CommandReceiver`）输出，供 adb logcat 抓取。
 */
class CommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CommandReceiver"
        private const val ACTION_CMD = "com.chin.stockanalysis.CMD"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CMD) return
        Log.i(TAG, "📡 收到 adb 命令广播: extras=${intent.extras?.keySet()?.joinToString() ?: "(无)"}")

        // goAsync：广播接收器返回后协程仍可继续执行（最多 10s，超时后协程仍随进程存活继续）
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val command = intent.getStringExtra("command")?.trim()?.lowercase(Locale.ROOT) ?: "refresh"
        val force = intent.getBooleanExtra("force", false)

        scope.launch {
            try {
                when (command) {
                    "status" -> runStatus(appContext)
                    "sync", "upload" -> runUpload(appContext, force)
                    "focus" -> runFocusUpload(appContext)
                    "download" -> runDownload(appContext)
                    "news" -> runNewsUpdate(appContext)
                    "backtest", "fit" -> runBacktest(appContext, force)
                    else -> runFullRefresh(appContext, force) // refresh / 未知命令默认走完整闭环
                }
            } catch (e: Exception) {
                Log.e(TAG, "命令 [$command] 执行失败: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // ══════════════════════════════════════
    // 命令实现
    // ══════════════════════════════════════

    /** 完整闭环：检查数据新鲜度 → 增量下载 → 同步 COS */
    private suspend fun runFullRefresh(context: Context, force: Boolean) {
        Log.i(TAG, "━━━ [refresh] 开始完整闭环 ━━━")

        // 1. 检查数据新鲜度
        val latestDate = queryLatestDate(context)
        val today = LocalDate.now().format(DATE_FMT)
        val isFresh = latestDate != null && latestDate >= today
        Log.i(TAG, "🔍 数据新鲜度: 本地最新=$latestDate，今天=$today，${if (isFresh) "已是最新" else "需要下载"}")

        // 2. 增量下载缺失交易日
        if (!isFresh) {
            runDownload(context)
        } else {
            Log.i(TAG, "✅ 数据已是最新，跳过下载")
        }

        // 3. 同步 COS
        runUpload(context, force)
        Log.i(TAG, "━━━ [refresh] 完整闭环结束 ━━━")
    }

    /** 增量下载缺失的交易日数据 */
    private suspend fun runDownload(context: Context) {
        Log.i(TAG, "📥 开始增量下载历史数据…")
        val today = LocalDate.now()
        val existingDates = try {
            StockDatabase.getInstance(context).dailySnapshotDao().getAvailableDates(30)
        } catch (_: Exception) {
            emptyList()
        }
        val latestDate = existingDates.filter { it <= today.format(DATE_FMT) }.maxOrNull()

        // 计算需要拉取的起始日期与天数
        // 注意：不能用 coerceIn(0, 30) 设上限——设备闲置超过 30 天时，
        // startLocalDate 距今天可能远超 30 天，截断会导致中间缺口永久丢失。
        val startLocalDate = if (latestDate != null) {
            LocalDate.parse(latestDate, DATE_FMT).plusDays(1)
        } else {
            today.minusDays(5) // 首次安装，拉取最近 5 天
        }
        val daysToFetch = ChronoUnit.DAYS.between(startLocalDate, today).toInt() + 1
        if (daysToFetch <= 0) {
            Log.i(TAG, "📥 无需增量下载")
            return
        }
        if (daysToFetch > 60) {
            Log.w(TAG, "📥 缺口较大（$daysToFetch 天），需要一段时间，请耐心等待")
        }

        Log.i(TAG, "📥 增量下载: ${startLocalDate.format(DATE_FMT)} ~ ${today.format(DATE_FMT)}（约 $daysToFetch 天）")
        val fetcher = HistoricalDataFetcher(context)
        val count = fetcher.fetchAllHistoricalData(
            days = daysToFetch + 2, // 多拉 2 天保险
            startDateOverride = startLocalDate,
            onProgress = { progress ->
                Log.d(TAG, "📥 下载进度: ${progress.completedStocks}/${progress.totalStocks}（${progress.totalRecords} 条）")
            }
        )
        Log.i(TAG, "📥 增量下载完成，写入 $count 条记录")
    }

    /** 上传数据包到 COS（顺带同步用户关注板块 user_focus_sectors.json） */
    private suspend fun runUpload(context: Context, force: Boolean) {
        Log.i(TAG, "☁️ 开始同步 COS…")
        val manager = CloudSyncManager(context)
        val cfg = manager.loadConfig()
        if (!manager.isConfigured(cfg)) {
            Log.w(TAG, "☁️ COS 未配置，跳过上传（请先在设置中配置 COS 参数）")
            return
        }

        val lastUpload = manager.lastUploadDate()
        if (!force && lastUpload == LocalDate.now().format(DATE_FMT)) {
            Log.i(TAG, "☁️ 今日数据包已上传过（$lastUpload），使用 --ez force true 可强制重传")
        } else {
            manager.uploadData(cfg, force = force) { status ->
                Log.i(TAG, "☁️ $status")
            }.onSuccess { url ->
                Log.i(TAG, "☁️ 上传成功: $url")
            }.onFailure { e ->
                Log.e(TAG, "☁️ 上传失败: ${e.message}")
            }
        }

        // 用户关注板块不受「每日一次」去重限制：随时增删，每次同步均上传最新状态
        uploadFocus(context, manager, cfg)
    }

    /** 只上传用户关注板块（不打包数据包） */
    private suspend fun runFocusUpload(context: Context) {
        val manager = CloudSyncManager(context)
        val cfg = manager.loadConfig()
        if (!manager.isConfigured(cfg)) {
            Log.w(TAG, "🧭 COS 未配置，跳过上传（请先在设置中配置 COS 参数）")
            return
        }
        uploadFocus(context, manager, cfg)
    }

    private suspend fun uploadFocus(context: Context, manager: CloudSyncManager, cfg: CloudSyncManager.CloudConfig) {
        manager.uploadFocusSectors(cfg) { status ->
            Log.i(TAG, "🧭 $status")
        }.onSuccess { key ->
            Log.i(TAG, "🧭 用户关注板块已同步: $key")
        }.onFailure { e ->
            Log.e(TAG, "🧭 关注板块同步失败: ${e.message}")
        }
    }

    /** 打印数据新鲜度与 COS 配置状态 */
    private suspend fun runStatus(context: Context) {
        val latestDate = queryLatestDate(context)
        val today = LocalDate.now().format(DATE_FMT)
        val manager = CloudSyncManager(context)
        val cfg = manager.loadConfig()

        Log.i(TAG, "━━━ [status] ━━━")
        Log.i(TAG, "数据最新日期: ${latestDate ?: "(空)"} | 今天: $today | 新鲜: ${latestDate != null && latestDate >= today}")
        Log.i(TAG, "COS 配置: ${if (manager.isConfigured(cfg)) "已配置" else "未配置"} | 上次上传: ${manager.lastUploadDate() ?: "从未"}")
        Log.i(TAG, "━━━ [status] 结束 ━━━")
    }

    /**
     * 一键数据维护：同步本周数据 → 四周期回溯 → 中线/长线状态拟合 → 上传 COS。
     * 回溯与拟合均为增量模式（只跑新增交易日区间，拟合网格仅在 3 个状态各有 >=3 样本时更新）。
     */
    private suspend fun runBacktest(context: Context, force: Boolean) {
        Log.i(TAG, "━━━ [backtest] 开始：同步数据 → 回溯 → 拟合 → 上传 ━━━")

        // 1. 同步本周数据（增量下载缺失交易日）
        runDownload(context)

        // 2. 四周期回溯（增量）
        Log.i(TAG, "📊 开始四周期回溯（超短/短线/中线/长线，增量模式）…")
        val stats = FullCycleBacktestEngine.runAll(context) { msg ->
            msg.lines().forEach { Log.i(TAG, "📊 $it") }
        }
        Log.i(TAG, "📊 四周期回溯完成，共 ${stats.size} 个周期统计")

        // 3. 中线/长线状态矩阵拟合
        for (period in listOf("中线", "长线")) {
            Log.i(TAG, "🔧 开始 [$period] 状态矩阵拟合（网格: 持有天数/止盈/止损）…")
            val fit = FullCycleBacktestEngine.fitByState(context, period) { msg -> Log.i(TAG, "🔧 $msg") }
            fit.report.lines().forEach { Log.i(TAG, "🔧 $it") }
        }

        // 4. 四周期成功率对比 + 长线分析（供人工研判）
        logSuccessRateReport(stats)

        // 5. 上传数据包到服务器
        runUpload(context, force)
        Log.i(TAG, "━━━ [backtest] 结束 ━━━")
    }

    /** 输出四周期成功率对比与长线/短线差异分析 */
    private fun logSuccessRateReport(stats: List<FullCycleBacktestEngine.PeriodStats>) {
        Log.i(TAG, "════════ 四周期成功率对比（固定本金口径） ════════")
        stats.forEach { s ->
            Log.i(TAG, "▍${s.period}: 信号${s.signalCount} | 已实现${s.realizedCount} | 胜率${"%.1f".format(s.winRate)}% | 平均${"%.2f".format(s.avgRet)}% | 累计${"%.2f".format(s.fixedCum)}% | 盈亏因子${"%.2f".format(s.profitFactor)} | 回撤${"%.2f".format(s.maxDrawdown)}%")
            s.byState.forEach { (st, ss) ->
                Log.i(TAG, "   └─ ${st.label}(${st.key}): ${ss.count}笔 胜率${"%.1f".format(ss.winRate)}% 平均${"%.2f".format(ss.avgRet)}% 累计${"%.2f".format(ss.fixedCum)}%")
            }
        }

        val longS = stats.find { it.period == "长线" }
        val shortS = stats.find { it.period == "短线" }
        if (longS != null && shortS != null) {
            val winDiff = longS.winRate - shortS.winRate
            Log.i(TAG, "════════ 长线 vs 短线 分析 ════════")
            Log.i(TAG, "长线胜率 ${"%.1f".format(longS.winRate)}% vs 短线胜率 ${"%.1f".format(shortS.winRate)}%，相差 ${"%.1f".format(winDiff)} 个百分点")
            val longFitted = longS.byState.filterValues { it.winRate >= 60.0 }
            Log.i(TAG, "长线高胜率状态（>=60%）: ${longFitted.size}/${longS.byState.size} 个大盘状态")
            longFitted.forEach { (st, ss) ->
                Log.i(TAG, "   ├─ ${st.label}(${st.key}) 胜率${"%.1f".format(ss.winRate)}%（${ss.count}笔）→ 拟合后按状态参数持有，可稳定提高长线成功率")
            }
            if (longFitted.isEmpty()) {
                Log.i(TAG, "⚠️ 长线目前无状态达到 60% 胜率，建议结合拟合矩阵挑选防守板块（如银行高息股）信号")
            }
        }
    }

    /** 触发全球 Top10 机构研报新闻因子收集（默认每日一次） */
    private suspend fun runNewsUpdate(context: Context) {
        Log.i(TAG, "📰 开始收集 Top10 机构研报新闻因子…")
        val count = TopInstitutionNewsCollector(context).updateIfNeeded(forceRefresh = false).size
        Log.i(TAG, "📰 机构研报收集完成：本次写入 $count 条（每日仅首次生效，使用 news 前无需参数）")
    }

    /** 查询本地最新交易日（<= 今天） */
    private suspend fun queryLatestDate(context: Context): String? {
        return try {
            StockDatabase.getInstance(context).dailySnapshotDao().getAvailableDates(30)
                .filter { it <= LocalDate.now().format(DATE_FMT) }
                .maxOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "查询最新日期失败: ${e.message}")
            null
        }
    }
}
