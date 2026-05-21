package com.chin.stockanalysis.stock.prefetch

import android.util.Log
import com.chin.stockanalysis.stock.data.MultiSourceStockRepository
import com.chin.stockanalysis.stock.theme.ThemeStockLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ## 股票数据后台预取调度器
 *
 * ### 核心思路（参考豆包快速响应的实现）
 *
 * 豆包之所以响应快，很大可能是因为：
 * 1. **数据预热**：App 后台定期拉取用户常查的数据并缓存
 * 2. **预测性预取**：根据用户习惯（时间段 + 历史查询），在用户发问前就把数据准备好
 * 3. **连接复用**：OkHttp 连接池保持热连接，省去 TCP 握手时间
 *
 * 本调度器实现：
 * - 记录用户高频股票 + 主题，定期在后台安静刷新缓存（用户无感知）
 * - 活跃时段（9:00~15:30、晚上用户常用时间）每 **5分钟** 刷新一次
 * - 非活跃时段每 **30分钟** 刷新一次（省电省流量）
 * - 预取的数据存入 SmartStockCache 时使用 **10分钟 TTL**，
 *   这样即使用户在交易中询问，10分钟内的数据也能秒返回，
 *   同时 AI 收到的是"几分钟前的实时数据"而非空数据
 *
 * ### 使用方式（在 StockQueryEngine 中）
 * ```kotlin
 * // 引擎初始化时启动
 * prefetchScheduler.start(coroutineScope)
 *
 * // 用户查询时顺便记录
 * prefetchScheduler.recordQuery(codes, themeName)
 *
 * // App 退到后台时停止（可选，不停也不会泄漏，scope cancel 即可）
 * prefetchScheduler.stop()
 * ```
 *
 * @param repository 共享的多源仓储（不重新创建，复用连接池和缓存）
 * @param queryHistory 用户查询历史（读取高频股票/主题）
 */
class StockPrefetchScheduler(
    private val repository: MultiSourceStockRepository,
    private val queryHistory: UserQueryHistory
) {
    private val tag = "StockPrefetchScheduler"

    /** 预取数据的 TTL：10分钟（比交易中 1s 长，比盘后 5min 也长） */
    private val prefetchTtlMs = 10 * 60 * 1000L

    /** 活跃时段预取间隔：5分钟 */
    private val activeIntervalMs = 5 * 60 * 1000L

    /** 非活跃时段预取间隔：30分钟 */
    private val idleIntervalMs = 30 * 60 * 1000L

    @Volatile
    private var schedulerJob: Job? = null

    // ────────────────────────────────────────────────────
    // 公开接口
    // ────────────────────────────────────────────────────

    /**
     * 启动后台调度器（建议在 StockQueryEngine 初始化时调用）
     *
     * 使用独立的 IO 协程，不阻塞 UI。
     * 如果已经在运行，则先停止旧任务再重启。
     */
    fun start(scope: CoroutineScope) {
        stop()
        schedulerJob = scope.launch(Dispatchers.IO) {
            Log.i(tag, "🚀 预取调度器已启动")
            // 首次延迟 30s 等 App 启动稳定后再预取
            delay(30_000L)

            while (isActive) {
                val intervalMs = if (queryHistory.isCurrentHourActive()) {
                    runPrefetch()
                    activeIntervalMs
                } else {
                    runPrefetch()
                    idleIntervalMs
                }
                Log.d(tag, "下次预取间隔: ${intervalMs / 60_000}分钟")
                delay(intervalMs)
            }
        }
    }

    /**
     * 停止调度器（可选，scope cancel 时自动停止）
     */
    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
        Log.d(tag, "预取调度器已停止")
    }

    /**
     * 立即触发一次预取（用于：用户刚打开某个主题时，主动预热相关数据）
     *
     * 不等待下一个调度周期。
     * @param scope 协程作用域
     * @param extraCodes 额外需要预取的股票代码（本次查询涉及的）
     * @param extraTheme 额外需要预取的主题名称（本次查询的主题）
     */
    fun triggerImmediately(
        scope: CoroutineScope,
        extraCodes: List<String> = emptyList(),
        extraTheme: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            runPrefetch(extraCodes = extraCodes, extraTheme = extraTheme)
        }
    }

    /**
     * 记录用户的一次查询（更新历史，供下次预取决策使用）
     *
     * @param codes 本次查询的股票代码列表
     * @param themeName 本次查询的主题名称（无主题则传 null）
     */
    fun recordQuery(codes: List<String>, themeName: String? = null) {
        if (codes.isNotEmpty()) queryHistory.recordCodes(codes)
        if (!themeName.isNullOrBlank()) queryHistory.recordTheme(themeName)
    }

    // ────────────────────────────────────────────────────
    // 私有：核心预取逻辑
    // ────────────────────────────────────────────────────

    private fun runPrefetch(
        extraCodes: List<String> = emptyList(),
        extraTheme: String? = null
    ) {
        val startMs = System.currentTimeMillis()
        var fetched = 0

        // ── 1. 预取高频股票代码 ──
        val topCodes = (queryHistory.getTopCodes(10) + extraCodes).distinct()
        if (topCodes.isNotEmpty()) {
            try {
                val result = repository.getRealtime(topCodes)
                if (result.isNotEmpty()) {
                    // 将结果以 prefetchTtlMs 写入缓存
                    repository.putToCache(result, prefetchTtlMs)
                    fetched += result.size
                    Log.d(tag, "✅ 预取股票 ${result.size}只: ${result.keys.take(5)}")
                }
            } catch (e: Exception) {
                Log.w(tag, "预取股票失败: ${e.message}")
            }
        }

        // ── 2. 预取高频主题（取主题内前10只股票）──
        val topThemes = queryHistory.getTopThemes(3)
        val themesToFetch = if (extraTheme != null) (listOf(extraTheme) + topThemes).distinct() else topThemes

        for (theme in themesToFetch) {
            try {
                // 从内置主题库获取该主题对应的代码列表（不调用 API，只预热内置主题）
                val themeInfo = ThemeStockLibrary.findTheme(theme) ?: continue
                val codes = ThemeStockLibrary.run { themeInfo.themeInfo.validStocks().map { it.code } }.take(10)
                if (codes.isEmpty()) continue

                val result = repository.getRealtime(codes)
                if (result.isNotEmpty()) {
                    repository.putToCache(result, prefetchTtlMs)
                    fetched += result.size
                    Log.d(tag, "✅ 预取主题[$theme] ${result.size}只")
                }
            } catch (e: Exception) {
                Log.w(tag, "预取主题[$theme]失败: ${e.message}")
            }
        }

        val elapsed = System.currentTimeMillis() - startMs
        if (fetched > 0) {
            Log.i(tag, "🎯 预取完成: 共${fetched}只股票 | 耗时${elapsed}ms | TTL=${prefetchTtlMs / 60_000}min")
        } else {
            Log.d(tag, "ℹ️ 无需预取（无历史数据或全部已在缓存中）| 耗时${elapsed}ms")
        }
    }
}
