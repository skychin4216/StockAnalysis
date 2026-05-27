package com.chin.stockanalysis.stock.autorefresh

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.MultiSourceStockRepository
import com.chin.stockanalysis.stock.data.TradingCalendar
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * ## 股票自动刷新管理器（TLL 架构）
 *
 * 负责周期性从数据源拉取最新价格，使用 TLL 策略决定是否刷新。
 *
 * ### 使用方式
 * ```kotlin
 * val manager = StockAutoRefreshManager(repository)
 * manager.startWatching(codes = listOf("sh600519", "sz002594"), scope = lifecycleScope)
 *
 * // 监听价格变化
 * PriceUpdateNotifier.addListener(object : PriceUpdateListener {
 *     override fun onPriceUpdate(event: PriceUpdateEvent) {
 *         when (event) {
 *             is PriceUpdateEvent.PricesUpdated -> updateUI(event.data)
 *             ...
 *         }
 *     }
 * })
 *
 * // 停止
 * manager.stopWatching()
 * ```
 */
class StockAutoRefreshManager(
    private val repository: MultiSourceStockRepository
) {
    companion object {
        private const val TAG = "StockAutoRefreshMgr"
    }

    /** 当前监控的股票代码列表 */
    private var watchedCodes: List<String> = emptyList()

    /** 上次成功更新的时间戳 */
    @Volatile
    private var lastUpdateTimeMs: Long = 0L

    /** 最近一次获取的价格快照（code → StockRealtime） */
    private val lastPrices = ConcurrentHashMap<String, StockRealtime>()

    /** 自动刷新 Job */
    private var refreshJob: Job? = null

    /** 是否正在运行 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * 开始监控一组股票，开启自动刷新循环
     *
     * @param codes 股票代码列表（如 ["sh600519", "sz002594"]）
     * @param scope 生命周期绑定的协程作用域（lifecycleScope 或 viewModelScope）
     * @param initialData 初始化数据（可选，如页面首次加载的数据）
     */
    fun startWatching(
        codes: List<String>,
        scope: CoroutineScope,
        initialData: Map<String, StockRealtime> = emptyMap()
    ) {
        if (codes.isEmpty()) {
            Log.w(TAG, "startWatching: codes is empty, skip")
            return
        }

        // 如果已运行且股票列表不变，跳过
        if (isRunning && watchedCodes == codes) {
            Log.d(TAG, "startWatching: already watching same codes, skip")
            return
        }

        stopWatching()

        watchedCodes = codes.toList()
        lastUpdateTimeMs = System.currentTimeMillis()

        // 设置初始数据
        if (initialData.isNotEmpty()) {
            lastPrices.clear()
            lastPrices.putAll(initialData)
        }

        isRunning = true
        Log.i(TAG, "开始监控 ${codes.size} 只股票: $codes")

        refreshJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val decision = evaluateAndRefresh()
                    val delayMs = decision.delayMs()
                    Log.d(TAG, "下次刷新: ${delayMs}ms (${decision})")
                    delay(delayMs)
                } catch (e: CancellationException) {
                    Log.i(TAG, "刷新循环被取消")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "刷新循环异常: ${e.message}", e)
                    delay(RefreshPolicy.TRADING_REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * 停止自动刷新
     */
    fun stopWatching() {
        refreshJob?.cancel()
        refreshJob = null
        isRunning = false
        Log.i(TAG, "停止监控")
    }

    /**
     * 强制立即刷新（不管 TLL 策略）
     *
     * @param callback 刷新完成回调（在主线程）
     */
    fun forceRefreshNow(scope: CoroutineScope, callback: ((Map<String, StockRealtime>) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                PriceUpdateNotifier.notifyStatusChanged(
                    RefreshStatus.Refreshing(watchedCodes.size)
                )
                val data = repository.getRealtimeSuspend(watchedCodes)
                if (data.isNotEmpty()) {
                    lastPrices.clear()
                    lastPrices.putAll(data)
                    lastUpdateTimeMs = System.currentTimeMillis()
                    PriceUpdateNotifier.notifyPriceUpdated(data, "force-refresh")
                    PriceUpdateNotifier.notifyStatusChanged(
                        RefreshStatus.Success(lastUpdateTimeMs, data.size)
                    )
                    withContext(Dispatchers.Main) {
                        callback?.invoke(data)
                    }
                } else {
                    PriceUpdateNotifier.notifyStatusChanged(
                        RefreshStatus.Error("未获取到数据")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "forceRefreshNow 失败: ${e.message}", e)
                PriceUpdateNotifier.notifyStatusChanged(
                    RefreshStatus.Error(e.message ?: "未知错误")
                )
            }
        }
    }

    /**
     * 获取最近一次价格快照（供 UI 查询，不发起网络请求）
     */
    fun getLastPrices(): Map<String, StockRealtime> = lastPrices.toMap()

    /**
     * 获取上次更新时间
     */
    fun getLastUpdateTime(): Long = lastUpdateTimeMs

    // ─────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────

    /**
     * 评估 TLL 策略并执行刷新
     *
     * @return 决策信息，用于计算下次延迟
     */
    private suspend fun evaluateAndRefresh(): RefreshDecision {
        // 获取交易日历状态
        val marketStatus = TradingCalendar.getMarketStatus()
        val isTradingDay = marketStatus.isTradingDay

        // 评估是否应该刷新
        val decision = RefreshPolicy.shouldRefresh(lastUpdateTimeMs, isTradingDay)

        Log.d(TAG, "TLL评估: isTradingDay=$isTradingDay, session=${marketStatus.session}, " +
                "decision=${decision}")

        if (decision.shouldRefresh) {
            // 执行刷新
            performRefresh()
            return decision
        }

        return decision
    }

    /**
     * 执行实际的刷新操作
     */
    private suspend fun performRefresh() {
        if (watchedCodes.isEmpty()) return

        Log.d(TAG, "执行刷新: ${watchedCodes.size} stocks")

        PriceUpdateNotifier.notifyStatusChanged(
            RefreshStatus.Refreshing(watchedCodes.size)
        )

        try {
            // 分批请求（每次最多 MAX_BATCH_SIZE）
            val batches = watchedCodes.chunked(RefreshPolicy.MAX_BATCH_SIZE)
            var totalUpdated = 0
            val allData = mutableMapOf<String, StockRealtime>()

            for (batch in batches) {
                val data = repository.getRealtimeSuspend(batch)
                allData.putAll(data)
                totalUpdated++
            }

            if (allData.isNotEmpty()) {
                // 合并更新：保留旧价格 + 覆盖新价格
                lastPrices.putAll(allData)
                lastUpdateTimeMs = System.currentTimeMillis()

                PriceUpdateNotifier.notifyPriceUpdated(allData, "auto-refresh")
                PriceUpdateNotifier.notifyStatusChanged(
                    RefreshStatus.Success(lastUpdateTimeMs, allData.size)
                )

                Log.i(TAG, "刷新完成: ${allData.size}/${watchedCodes.size} 只股票获取到最新价")
            } else {
                PriceUpdateNotifier.notifyStatusChanged(
                    RefreshStatus.Error("未获取到数据")
                )
                Log.w(TAG, "刷新未获取到数据")
            }
        } catch (e: Exception) {
            Log.e(TAG, "刷新失败: ${e.message}", e)
            PriceUpdateNotifier.notifyStatusChanged(
                RefreshStatus.Error(e.message ?: "未知错误")
            )
        }
    }

    /**
     * 根据决策计算下次刷新延迟
     */
    private fun RefreshDecision.delayMs(): Long = when (this) {
        is RefreshDecision.Yes -> intervalMs
        is RefreshDecision.No -> RefreshPolicy.TRADING_REFRESH_INTERVAL_MS
    }
}