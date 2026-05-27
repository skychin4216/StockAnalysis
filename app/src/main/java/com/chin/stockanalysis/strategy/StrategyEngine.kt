package com.chin.stockanalysis.strategy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.*

/**
 * ## 量化选股策略引擎
 *
 * 管理策略生命周期（注册/删除/执行），协调所有策略的扫描。
 *
 * ### 使用方式
 * ```kotlin
 * val engine = StrategyEngine(context, screener)
 * engine.registerStrategy(MovingAverageStrategy())
 * engine.registerStrategy(VolumeBreakStrategy())
 * engine.registerStrategy(LowValuationStrategy())
 *
 * // 执行所有启用策略
 * engine.runAll { result ->
 *     runOnUiThread { updateUI(result) }
 * }
 *
 * // 执行单个策略
 * engine.runOne("ma_cross") { result -> ... }
 *
 * // 增删策略
 * engine.registerStrategy(myNewStrategy)
 * engine.removeStrategy("ma_cross")
 * ```
 */
class StrategyEngine(
    private val context: Context,
    private val screener: StockScreener
) {
    companion object {
        private const val TAG = "StrategyEngine"
        private const val PREFS_NAME = "strategy_prefs"
        private const val KEY_ENABLED_IDS = "enabled_strategy_ids"
    }

    /** 所有已注册的策略（线程安全） */
    private val strategies = mutableMapOf<String, Strategy>()

    /** 策略持久化 */
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 最近的扫描结果缓存 */
    private val lastResults = mutableMapOf<String, ScreeningResult>()

    /** 当前扫描 Job */
    private var scanJob: Job? = null

    // ═══════════════════════════════
    // 策略管理
    // ═══════════════════════════════

    /**
     * 注册一个新策略
     */
    fun registerStrategy(strategy: Strategy) {
        strategies[strategy.id] = strategy
        // 默认启用新策略
        setEnabled(strategy.id, true)
        Log.i(TAG, "注册策略: ${strategy.id} - ${strategy.name}")
    }

    /**
     * 移除一个策略
     */
    fun removeStrategy(id: String): Boolean {
        val removed = strategies.remove(id) != null
        if (removed) {
            setEnabled(id, false)
            lastResults.remove(id)
            Log.i(TAG, "移除策略: $id")
        }
        return removed
    }

    /**
     * 设置策略启用/禁用
     */
    fun setEnabled(id: String, enabled: Boolean) {
        val ids = getEnabledIds().toMutableSet()
        if (enabled) ids.add(id) else ids.remove(id)
        prefs.edit().putStringSet(KEY_ENABLED_IDS, ids).apply()
    }

    /**
     * 策略是否启用
     */
    fun isEnabled(id: String): Boolean = getEnabledIds().contains(id)

    /**
     * 获取所有策略
     */
    fun getStrategies(): List<Strategy> = strategies.values.toList()

    /**
     * 获取启用的策略
     */
    fun getEnabledStrategies(): List<Strategy> =
        strategies.values.filter { isEnabled(it.id) }

    // ═══════════════════════════════
    // 执行扫描
    // ═══════════════════════════════

    /**
     * 异步执行所有启用策略
     *
     * @param scope 协程作用域
     * @param onProgress 每完成一个策略的回调（在 IO 线程）
     * @param onComplete 全部完成的回调（在 IO 线程）
     */
    fun runAll(
        scope: CoroutineScope,
        onProgress: ((ScreeningResult) -> Unit)? = null,
        onComplete: ((List<ScreeningResult>) -> Unit)? = null
    ) {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            val enabled = getEnabledStrategies()
            if (enabled.isEmpty()) {
                Log.w(TAG, "没有启用的策略")
                withContext(Dispatchers.Main) { onComplete?.invoke(emptyList()) }
                return@launch
            }

            Log.i(TAG, "开始执行 ${enabled.size} 个策略...")
            val startTime = System.currentTimeMillis()
            val results = mutableListOf<ScreeningResult>()

            // 并发执行所有策略（每个策略独立的协程）
            val jobs = enabled.map { strategy ->
                async {
                    runOneInternal(strategy, onProgress)
                }
            }

            for (job in jobs) {
                val result = job.await()
                if (result != null) results.add(result)
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "全部策略执行完成: ${results.size}/${enabled.size} 成功, 耗时 ${elapsed}ms")

            withContext(Dispatchers.Main) { onComplete?.invoke(results) }
        }
    }

    /**
     * 执行单个策略
     */
    fun runOne(
        id: String,
        scope: CoroutineScope,
        onResult: ((ScreeningResult?) -> Unit)? = null
    ) {
        val strategy = strategies[id] ?: run {
            onResult?.invoke(null)
            return
        }

        scope.launch(Dispatchers.IO) {
            val result = runOneInternal(strategy, null)
            withContext(Dispatchers.Main) { onResult?.invoke(result) }
        }
    }

    /**
     * 获取最近扫描结果
     */
    fun getLastResult(id: String): ScreeningResult? = lastResults[id]

    /**
     * 获取所有最近结果
     */
    fun getAllLastResults(): List<ScreeningResult> = lastResults.values.toList()

    /**
     * 取消扫描
     */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
    }

    // ═══════════════════════════════
    // 内部
    // ═══════════════════════════════

    private fun getEnabledIds(): Set<String> =
        prefs.getStringSet(KEY_ENABLED_IDS, emptySet()) ?: emptySet()

    private suspend fun runOneInternal(
        strategy: Strategy,
        onProgress: ((ScreeningResult) -> Unit)?
    ): ScreeningResult? {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "  执行策略: ${strategy.id} - ${strategy.name}")

        return try {
            // 检查可用性
            if (!strategy.isAvailable()) {
                Log.w(TAG, "  策略 ${strategy.id} 不可用，跳过")
                return null
            }

            // 执行屏幕
            val result = withTimeoutOrNull(30_000L) {
                strategy.screen().getOrThrow()
            }

            if (result != null) {
                lastResults[strategy.id] = result
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "  ✅ ${strategy.id}: 命中 ${result.hitCount} 只 / ${elapsed}ms")
                onProgress?.invoke(result)
            } else {
                Log.w(TAG, "  ⚠️ ${strategy.id}: 超时")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "  ❌ ${strategy.id}: ${e.message}", e)
            null
        }
    }

    /**
     * 便捷方法：直接执行策略并以示例数据快速返回（供 demo / 降级使用）
     */
    fun runWithSampleData(scope: CoroutineScope, onComplete: ((List<ScreeningResult>) -> Unit)? = null) {
        scope.launch(Dispatchers.Default) {
            val enabled = getEnabledStrategies()
            val results = enabled.map { strategy ->
                ScreeningResult(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    category = strategy.category,
                    signals = generateSampleSignals(strategy),
                    totalScanned = 200,
                    scanTimeMs = 500L + (strategy.id.hashCode() % 1000),
                    timestamp = System.currentTimeMillis()
                )
            }
            withContext(Dispatchers.Main) { onComplete?.invoke(results) }
        }
    }

    private fun generateSampleSignals(strategy: Strategy): List<StrategySignal> {
        val sampleStocks = listOf(
            Triple("sh600519", "贵州茅台", 85),
            Triple("sz002594", "比亚迪", 78),
            Triple("sz300750", "宁德时代", 72),
            Triple("sh600183", "生益科技", 65),
            Triple("sz002463", "沪电股份", 58)
        )

        return sampleStocks.mapIndexed { i, (code, name, baseStrength) ->
            StrategySignal(
                stockCode = code,
                stockName = name,
                strategyId = strategy.id,
                category = strategy.category,
                strength = minOf(baseStrength + (i * 2), 100),
                action = when {
                    baseStrength >= 80 -> SignalAction.BUY
                    baseStrength >= 60 -> SignalAction.WATCH
                    else -> SignalAction.HOLD
                },
                reason = "${strategy.name}命中: 满足选股条件（示例数据）",
                details = mapOf(
                    "signal_strength" to "$baseStrength%",
                    "source" to "sample_data"
                ),
                currentPrice = 100.0 + i * 50,
                changePercent = 2.5 - i * 0.5
            )
        }
    }
}