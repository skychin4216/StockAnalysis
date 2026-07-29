package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 低估值策略（重寫版）
 *
 * 使用真實 PE/PB/ROE 數據進行估值篩選，廢除舊版「股價=估值」的錯誤邏輯。
 *
 * 三維評分：
 * - 估值(40%)：PE < 15 滿分，PE 15-25 遞減，PE > 40 或虧損淘汰
 * - 質量(30%)：ROE > 15% 滿分，毛利率 > 30% 加分
 * - 安全邊際(30%)：PB < 2 滿分，市值 > 200 億加分
 *
 * 淘汰條件：PE < 0（虧損）、ST 股、日均成交 < 5000 萬
 */
class LowValuationStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "low_valuation"
    override var name = "低估值策略"
    override var description = "筛选PE/PB低于合理区间、ROE稳健且具备安全边际的低估值股票"
    override val category = StrategyCategory.VALUE
    override val holdingPeriods = listOf(HoldingPeriod.LONG)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 720

    override val config = StrategyConfig.custom(
        params = mapOf("pe_max" to 25.0, "pb_max" to 3.0, "roe_min" to 10.0),
        maxResults = 15
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("valuation", "估值评分", 40, "PE越低得分越高"),
        WeightFactor("quality", "质量评分", 30, "ROE和毛利率"),
        WeightFactor("safety", "安全边际", 30, "PB和市值保障")
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
            val pool = if (config.stockPool.isNotEmpty()) preloadedStocks.filter { it.code in config.stockPool } else preloadedStocks
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
        val strengthThreshold = if (isBearish) 55 else 40
        val peMax = (config.params["pe_max"] as? Number)?.toDouble() ?: 25.0
        val pbMax = (config.params["pb_max"] as? Number)?.toDouble() ?: 3.0
        val roeMin = (config.params["roe_min"] as? Number)?.toDouble() ?: 10.0

        Log.i("LV_Strategy", "大盤: $marketDir, 門檻: PE<$peMax PB<$pbMax ROE>$roeMin")

        // 硬性淘汰
        val candidates = pool.filter { stock ->
            // 淘汰虧損股（PE < 0 或 PE = 0 表示無數據/虧損）
            stock.pe > 0 &&
            // 淘汰 ST
            !stock.name.contains("ST", ignoreCase = true) &&
            // 流動性保障
            stock.amount > 50_000_000 &&
            // PE 上限
            stock.pe <= peMax &&
            // PB 上限（PB=0 表示無數據，放行）
            (stock.pb <= 0 || stock.pb <= pbMax) &&
            // 價格穩定（排除漲跌停）
            stock.changePercent in -9.0..9.0
        }

        Log.i("LV_Strategy", "pool=${pool.size} → 硬性過濾後=${candidates.size}")

        val signals = candidates.map { stock -> calculateSignal(stock, roeMin) }
            .filter { it.strength >= strengthThreshold }
            .sortedByDescending { it.strength }
            .take(config.maxResults)

        Log.i("LV_Strategy", "評分後 strength>=$strengthThreshold: ${signals.size}")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = signals, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    private fun calculateSignal(stock: StockRealtime, roeMin: Double): StrategySignal {
        // ── 估值評分 (0-40) ──
        val valuationScore = when {
            stock.pe <= 8 -> 40
            stock.pe <= 12 -> 36
            stock.pe <= 15 -> 32
            stock.pe <= 18 -> 26
            stock.pe <= 20 -> 20
            stock.pe <= 25 -> 14
            else -> 8
        }

        // ── 質量評分 (0-30) ──
        val roeScore = when {
            stock.roeTTM >= 25 -> 18
            stock.roeTTM >= 20 -> 15
            stock.roeTTM >= 15 -> 12
            stock.roeTTM >= roeMin -> 8
            stock.roeTTM > 0 -> 4
            else -> 0 // ROE 無數據
        }
        val marginScore = when {
            stock.grossMarginTTM >= 50 -> 12
            stock.grossMarginTTM >= 30 -> 10
            stock.grossMarginTTM >= 20 -> 7
            stock.grossMarginTTM > 0 -> 4
            else -> 2 // 無數據給基礎分
        }
        val qualityScore = (roeScore + marginScore).coerceAtMost(30)

        // ── 安全邊際評分 (0-30) ──
        val pbScore = when {
            stock.pb <= 0 -> 8   // 無數據
            stock.pb <= 1.0 -> 15
            stock.pb <= 1.5 -> 12
            stock.pb <= 2.0 -> 10
            stock.pb <= 3.0 -> 6
            else -> 2
        }
        val capScore = when {
            stock.marketCap >= 100_000_000_000 -> 15  // 千億以上
            stock.marketCap >= 50_000_000_000 -> 12   // 500億
            stock.marketCap >= 20_000_000_000 -> 9    // 200億
            stock.marketCap >= 10_000_000_000 -> 6    // 100億
            stock.marketCap > 0 -> 3
            else -> 2 // 無數據
        }
        val safetyScore = (pbScore + capScore).coerceAtMost(30)

        val strength = (valuationScore + qualityScore + safetyScore).coerceIn(0, 100)

        val reason = buildString {
            append("PE=${"%.1f".format(stock.pe)}")
            if (stock.pb > 0) append(" PB=${"%.2f".format(stock.pb)}")
            if (stock.roeTTM > 0) append(" ROE=${"%.1f".format(stock.roeTTM)}%")
            if (stock.marketCap > 0) append(" 市值${"%.0f".format(stock.marketCap / 100_000_000)}億")
        }

        return StrategySignal(
            stockCode = stock.code, stockName = stock.name, strategyId = id, category = category,
            strength = strength,
            action = when { strength >= 70 -> SignalAction.BUY; strength >= 50 -> SignalAction.WATCH; else -> SignalAction.HOLD },
            reason = reason,
            currentPrice = stock.price, changePercent = stock.changePercent
        )
    }
}
