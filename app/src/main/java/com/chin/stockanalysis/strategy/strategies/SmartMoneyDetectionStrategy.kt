package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.Strategy
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
 * ## 主力資金行為偵測策略
 *
 * 基於 MFI / CMF / A/D 背離 / 主力淨流入趨勢 四個因子，
 * 偵測主力埋伏、拉升、出貨三種行為模式。
 *
 * ### 因子構成
 * | 因子 | 權重 | 數據源 |
 * |------|------|--------|
 * | MFI(14日) | 30% | DailySnapshotEntity 本地計算 |
 * | CMF(20日) | 25% | DailySnapshotEntity 本地計算 |
 * | A/D 背離信號 | 25% | DailySnapshotEntity 本地計算 |
 * | 主力淨流入趨勢 | 20% | 東方財富 f62/f184/f66/f69 |
 *
 * ### 信號判斷
 * - combined >= 75 → BUY（主力持續流入 + 背離吸籌信號）
 * - combined >= 55 → WATCH（溫和流入）
 * - combined < 55 → HOLD
 */
class SmartMoneyDetectionStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "smart_money_detection"
    override var name = "主力資金行為偵測"
    override var description =
        "MFI(30%) + CMF(25%) + A/D背離(25%) + 主力淨流入趨勢(20%) → 綜合評分偵測埋伏/拉升/出貨"
    override val category = StrategyCategory.VOLUME
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("min_score" to 55.0, "max_results" to 30.0),
        maxResults = 30
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("mfi", "MFI資金流量", 30, "MFI(14日) 資金流量指數"),
        WeightFactor("cmf", "CMF蔡金流向", 25, "CMF(20日) 蔡金資金流向"),
        WeightFactor("ad", "A/D背離", 25, "A/D 累積線背離偵測"),
        WeightFactor("flow", "主力淨流入", 20, "f62/f184/f66 主力淨流入趨勢")
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
            Log.e(TAG, "篩選失敗", e)
            Result.failure(e)
        }
    }

    override suspend fun screenWithData(stocks: List<StockRealtime>): Result<ScreeningResult> {
        val start = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty()) stocks.filter { it.code in config.stockPool } else stocks
            doScreen(pool, start)
        } catch (e: Exception) {
            Log.e(TAG, "篩選失敗", e)
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = SmartMoneyCache.isFresh()

    // ── Pipeline ──

    private fun doScreen(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return success(emptyList(), 0, startTime)

        val minScore = (config.params["min_score"] as? Number)?.toInt() ?: 55
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

        // 判斷主力行為模式
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
     * 偵測主力行為模式
     * - 埋伏吸籌：MFI 40~60 + A/D 底背離 + 主力淨流入為正但不大
     * - 拉升啟動：MFI > 60 + CMF > 0 + 主力淨流入放大
     * - 出貨風險：MFI > 80 + A/D 頂背離 + 主力淨流出
     * - 中性觀察：其他情況
     */
    private fun detectPattern(s: StockRealtime, sm: SmartMoneyCache.SmartMoneyScore): String {
        val isBullishDivergence = sm.adScore >= 85  // 底背離
        val isBearishDivergence = sm.adScore <= 15  // 頂背離
        val isStrongInflow = sm.flowScore >= 80
        val isOverboughtMFI = sm.mfiScore >= 60 && sm.mfiScore < 100 // MFI > 80 但 score=60（非超買）
        val isHighMFI = sm.mfiScore == 60.0  // MFI > 80 超買區

        return when {
            // 出貨風險：頂背離 + 超買 + 流出
            isBearishDivergence && sm.flowScore < 30 -> "⚠️出貨"
            // 超買回落
            isHighMFI && sm.flowScore < 40 -> "⚠️超買"
            // 埋伏吸籌：底背離 + 溫和流入
            isBullishDivergence && sm.flowScore in 40.0..80.0 -> "埋伏吸籌"
            // 拉升啟動：強流入 + MFI 流入區
            isStrongInflow && isOverboughtMFI -> "🚀拉升"
            // 持續流入
            isStrongInflow -> "持續流入"
            // 溫和流入
            sm.combined >= 55 -> "溫和流入"
            else -> "觀察"
        }
    }

    private fun success(
        signals: List<StrategySignal>, total: Int, startTime: Long
    ) = Result.success(ScreeningResult(
        id, name, category, signals, total,
        System.currentTimeMillis() - startTime
    ))
}
