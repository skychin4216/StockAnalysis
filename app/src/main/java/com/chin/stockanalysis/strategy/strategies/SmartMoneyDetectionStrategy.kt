package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.StrategyCategory
import com.chin.stockanalysis.strategy.StrategyConfig
import com.chin.stockanalysis.strategy.StrategySource
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.data.SmartMoneyCache
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 主力资金行为侦测策略
 *
 * 基于 MFI / CMF / A/D 背离 / 主力净流入趋势 四个因子，
 * 侦测主力埋伏、拉升、出货三种行为模式。
 *
 * ### 因子构成
 * | 因子 | 权重 | 数据源 |
 * |------|------|--------|
 * | MFI(14日) | 30% | DailySnapshotEntity 本地计算 |
 * | CMF(20日) | 25% | DailySnapshotEntity 本地计算 |
 * | A/D 背离信号 | 25% | DailySnapshotEntity 本地计算 |
 * | 主力净流入趋势 | 20% | 东方财富 f62/f184/f66/f69 |
 *
 * ### 信号判断
 * - combined >= 75 → BUY（主力持续流入 + 背离吸筹信号）
 * - combined >= 55 → WATCH（温和流入）
 * - combined < 55 → HOLD
 */
class SmartMoneyDetectionStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "smart_money_detection"
    override var name = "主力资金行为侦测"
    override var description =
        "MFI(30%) + CMF(25%) + A/D背离(25%) + 主力净流入趋势(20%) → 综合评分侦测埋伏/拉升/出货"
    override val category = StrategyCategory.VOLUME
    override val holdingPeriods = listOf(HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 72    // 3个交易日

    override val config = StrategyConfig.custom(
        params = mapOf("min_score" to 55.0, "max_results" to 30.0),
        maxResults = 30
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("mfi", "MFI资金流量", 30, "MFI(14日) 资金流量指数"),
        WeightFactor("cmf", "CMF蔡金流向", 25, "CMF(20日) 蔡金资金流向"),
        WeightFactor("ad", "A/D背离", 25, "A/D 累积线背离侦测"),
        WeightFactor("flow", "主力净流入", 20, "f62/f184/f66 主力净流入趋势")
    )

    companion object {
        private const val TAG = "SmartMoneyStrategy"
    }

    // ── Screening entry ──

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            doScreen(pool, start)
        } catch (e: Exception) {
            Log.e(TAG, "筛选失败", e)
            Result.failure(e)
        }
    }

    override suspend fun screenWithData(stocks: List<StockRealtime>): Result<ScreeningResult> {
        val start = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty()) stocks.filter { it.code in config.stockPool } else stocks
            doScreen(pool, start)
        } catch (e: Exception) {
            Log.e(TAG, "筛选失败", e)
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = SmartMoneyCache.isFresh()

    // ── Pipeline ──

    private suspend fun doScreen(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return success(emptyList(), 0, startTime)

        // 大盘环境预检
        val marketDir = try { screener.detectMarketDirection() } catch (_: Exception) { "OSCILLATION" }
        val isBearish = marketDir == "BEARISH"
        val minScore = if (isBearish) 70 else ((config.params["min_score"] as? Number)?.toInt() ?: 55)
        Log.i(id, "大盘环境: $marketDir → 智慧资金门槛 ${if (isBearish) "55→70" else "标准门槛55"}")

        val scored = pool.mapNotNull { s ->
            val sm = SmartMoneyCache.getScore(s.code)
            if (sm.combined >= minScore) {
                buildSignal(s, sm)
            } else null
        }.sortedByDescending { it.strength }

        return success(scored.take(config.maxResults), pool.size, startTime)
    }

    // ── Signal building ──

    private fun buildSignal(s: StockRealtime, sm: SmartMoneyCache.SmartMoneyScore): StrategySignal {
        val str = sm.combined.toInt().coerceIn(0, 100)

        // 判断主力行为模式
        val pattern = detectPattern(s, sm)

        val sb = StringBuilder(pattern)
        sb.append(" | MFI=").append(String.format("%.0f", sm.mfiScore))
        sb.append(" CMF=").append(String.format("%.0f", sm.cmfScore))
        sb.append(" AD=").append(String.format("%.0f", sm.adScore))
        sb.append(" FLOW=").append(String.format("%.0f", sm.flowScore))

        val act = when {
            str >= 75 -> SignalAction.BUY
            str >= 55 -> SignalAction.WATCH
            else -> SignalAction.HOLD
        }

        return StrategySignal(
            stockCode = s.code,
            stockName = s.name,
            strategyId = id,
            category = category,
            strength = str,
            action = act,
            reason = sb.toString(),
            details = mapOf(
                "mfi" to sm.mfiScore.toString(),
                "cmf" to sm.cmfScore.toString(),
                "ad" to sm.adScore.toString(),
                "flow" to sm.flowScore.toString(),
                "pattern" to pattern
            ),
            currentPrice = s.price,
            changePercent = s.changePercent
        )
    }

    /**
     * 侦测主力行为模式
     * - 埋伏吸筹：MFI 40~60 + A/D 底背离 + 主力净流入为正但不大
     * - 拉升启动：MFI > 60 + CMF > 0 + 主力净流入放大
     * - 出货风险：MFI > 80 + A/D 顶背离 + 主力净流出
     * - 中性观察：其他情况
     */
    private fun detectPattern(s: StockRealtime, sm: SmartMoneyCache.SmartMoneyScore): String {
        val isBullishDivergence = sm.adScore >= 85  // 底背离
        val isBearishDivergence = sm.adScore <= 15  // 顶背离
        val isStrongInflow = sm.flowScore >= 80
        val isOverboughtMFI = sm.mfiScore >= 60 && sm.mfiScore < 100 // MFI > 80 但 score=60（非超买）
        val isHighMFI = sm.mfiScore == 60.0  // MFI > 80 超买区

        return when {
            // 出货风险：顶背离 + 超买 + 流出
            isBearishDivergence && sm.flowScore < 30 -> "⚠️出货"
            // 超买回落
            isHighMFI && sm.flowScore < 40 -> "⚠️超买"
            // 埋伏吸筹：底背离 + 温和流入
            isBullishDivergence && sm.flowScore in 40.0..80.0 -> "埋伏吸筹"
            // 拉升启动：强流入 + MFI 流入区
            isStrongInflow && isOverboughtMFI -> "🚀拉升"
            // 持续流入
            isStrongInflow -> "持续流入"
            // 温和流入
            sm.combined >= 55 -> "温和流入"
            else -> "观察"
        }
    }

    private fun success(
        signals: List<StrategySignal>, total: Int, startTime: Long
    ) = Result.success(ScreeningResult(
        id, name, category, signals, total,
        System.currentTimeMillis() - startTime
    ))
}
