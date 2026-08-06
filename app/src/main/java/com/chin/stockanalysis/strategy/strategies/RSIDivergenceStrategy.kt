package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## RSI 背離策略（修正版）
 *
 * 從 DB 讀取單股連續 N 天收盤價計算標準 RSI(14)。
 * 超賣反彈：RSI < 30 且當日收陽（反轉確認）。
 * 底背離加分：股價創近期新低但 RSI 未創新低。
 */
class RSIDivergenceStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "rsi_divergence"
    override var name = "RSI背离策略"
    override var description = "RSI超卖区间反弹，配合成交量确认底部反转"
    override val category = StrategyCategory.MOMENTUM
    override val holdingPeriods = listOf(HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 120

    override val config = StrategyConfig.custom(
        params = mapOf("rsi_period" to 14, "oversold" to 30, "min_amount" to 50_000_000.0),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("rsi", "RSI得分", 40, "RSI值越低得分越高"),
        WeightFactor("divergence", "底背離", 30, "股價新低但RSI未新低"),
        WeightFactor("volume", "量能确认", 30, "反轉日放量確認")
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) screener.scanFullMarket()
            else screener.scanSpecific(config.stockPool).values.toList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty()) {
                preloadedStocks.filter { it.code in config.stockPool }
            } else preloadedStocks
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun isAvailable(): Boolean = true

    private suspend fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        val marketDir = try { screener.detectMarketDirection() } catch (_: Exception) { "OSCILLATION" }
        val isBearish = marketDir == "BEARISH"
        val strengthThreshold = if (isBearish) 50 else 35
        val period = (config.params["rsi_period"] as? Number)?.toInt() ?: 14
        val oversoldLevel = (config.params["oversold"] as? Number)?.toDouble() ?: 30.0
        val minAmount = (config.params["min_amount"] as? Number)?.toDouble() ?: 50_000_000.0

        val db = StockDatabase.getInstance(screener.context)
        val dao = db.dailySnapshotDao()

        // 預過濾：有基本流動性、當日有反彈跡象的股票
        val candidates = pool.filter {
            it.amount > minAmount && it.price > 2.0 && it.changePercent > -5.0
        }
        Log.i("RSI_Strategy", "大盤: $marketDir, 候選: ${candidates.size}/${pool.size}")

        val signals = mutableListOf<StrategySignal>()

        for (stock in candidates) {
            try {
                // 讀取 period+10 天歷史（多取 10 天用於背離檢測）
                val history = dao.getByCode(stock.code, period + 10)
                if (history.size < period + 1) continue

                val sorted = history.sortedBy { it.date }
                val closes = sorted.map { it.close }

                // 計算當前 RSI
                val currentRsi = calculateRSI(closes, period)

                // 只關注超賣區或接近超賣的股票
                if (currentRsi > oversoldLevel + 15) continue // RSI > 45 直接跳過

                // 反轉確認：當日收陽或漲幅 > 0
                val hasReversal = stock.price > stock.open || stock.changePercent > 0

                // 底背離檢測：近 5 日股價創新低但 RSI 未創新低
                val hasDivergence = detectBullishDivergence(closes, period)

                // 量能確認：今日成交量 vs 近 5 日均量
                val recentVolumes = sorted.takeLast(6).map { it.volume.toDouble() }
                val avgVol5 = if (recentVolumes.size > 1) recentVolumes.dropLast(1).average() else 1.0
                val volumeRatio = if (avgVol5 > 0) stock.volume.toDouble() / avgVol5 else 1.0

                // 評分
                val rsiScore = when {
                    currentRsi < 15 -> 40
                    currentRsi < 20 -> 36
                    currentRsi < 25 -> 32
                    currentRsi < 30 -> 28
                    currentRsi < 35 -> 22
                    currentRsi < 40 -> 15
                    else -> 8
                }

                val divergenceScore = when {
                    hasDivergence && hasReversal -> 30
                    hasDivergence -> 22
                    hasReversal && currentRsi < oversoldLevel -> 18
                    hasReversal -> 10
                    else -> 0
                }

                val volumeScore = when {
                    volumeRatio > 2.5 -> 30
                    volumeRatio > 1.8 -> 25
                    volumeRatio > 1.3 -> 18
                    volumeRatio > 1.0 -> 12
                    else -> 5
                }

                val strength = (rsiScore + divergenceScore + volumeScore).coerceIn(0, 100)
                if (strength < strengthThreshold) continue

                val reason = buildString {
                    append("RSI=${"%.0f".format(currentRsi)}")
                    if (hasDivergence) append(" 底背離")
                    if (hasReversal) append(" 反轉確認")
                    append(" 量比${"%.1f".format(volumeRatio)}")
                }

                signals.add(StrategySignal(
                    strategyId = id, category = category,
                    stockCode = stock.code, stockName = stock.name,
                    strength = strength, reason = reason,
                    action = if (strength >= 60) SignalAction.BUY else SignalAction.WATCH,
                    currentPrice = stock.price, changePercent = stock.changePercent
                ))
            } catch (_: Exception) { continue }
        }

        val result = signals.sortedByDescending { it.strength }.take(config.maxResults)
        Log.i("RSI_Strategy", "計算完成: ${candidates.size} 候選 → ${result.size} 信號")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = result, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    /**
     * 標準 RSI 計算（Wilder 平滑法的簡化版：簡單均值）
     */
    private fun calculateRSI(closes: List<Double>, period: Int): Double {
        return com.chin.stockanalysis.strategy.analysis.RsiCalculator.compute(closes, period)
    }

    /**
     * 底背離檢測：近 5 日股價創近期新低，但 RSI 未創新低
     */
    private fun detectBullishDivergence(closes: List<Double>, period: Int): Boolean {
        if (closes.size < period + 10) return false

        // 當前價格和 RSI
        val currentPrice = closes.last()
        val currentRsi = calculateRSI(closes, period)

        // 5 天前的價格和 RSI
        val prevCloses = closes.dropLast(5)
        val prevPrice = prevCloses.last()
        val prevRsi = calculateRSI(prevCloses, period)

        // 10 天前的價格和 RSI（更遠的參照）
        val olderCloses = closes.dropLast(10)
        val olderPrice = olderCloses.last()
        val olderRsi = calculateRSI(olderCloses, period)

        // 底背離：股價低於前期低點，但 RSI 高於前期 RSI
        val priceLower = currentPrice < prevPrice && currentPrice < olderPrice
        val rsiHigher = currentRsi > prevRsi || currentRsi > olderRsi

        return priceLower && rsiHigher && currentRsi < 40
    }
}
