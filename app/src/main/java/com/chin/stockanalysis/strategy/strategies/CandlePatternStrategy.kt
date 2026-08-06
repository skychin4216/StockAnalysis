package com.chin.stockanalysis.strategy.strategies

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.analysis.CandlePatternDetector
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## K線形態策略（Pipeline-based）
 *
 * 復用 CandlePatternDetector 的 16 種形態識別，對全市場掃描。
 * 只取看多形態，按形態強度（1-5）評分。
 *
 * 核心形態：
 * - 晨星/三白兵/看漲吞沒/穿刺線 → 高強度
 * - 錘子線/倒錘(底部) → 中等強度
 * - 上升三法 → 趨勢延續
 */
class CandlePatternStrategy(
    private val screener: StockScreener,
    private val appContext: android.content.Context? = null
) : Strategy {

    override val id = "candle_pattern"
    override var name = "K線形態精選"
    override var description = "復用 CandlePatternDetector 16種形態識別，篩選看多信號（晨星/三白兵/吞沒/錘子等），按強度評分"
    override val category = StrategyCategory.MOMENTUM
    override val holdingPeriods = listOf(HoldingPeriod.SHORT)
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("min_strength" to 2, "lookback_days" to 30)
    )

    override val defaultStopLoss = -0.03f
    override val defaultTakeProfit = 0.06f
    override val maxPositions = 5

    override var weightFactors = listOf(
        WeightFactor("pattern_strength", "形態強度", 40, "看多形態強度加總"),
        WeightFactor("multi_pattern", "多形態共振", 30, "多個看多形態同時出現"),
        WeightFactor("volume_confirm", "量價配合", 30, "成交量確認價格突破")
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("需要 Context"))

        val pool = screener.scanFullMarket()
        doScreen(ctx, pool, startTime)
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        val ctx = appContext ?: return Result.failure(IllegalStateException("需要 Context"))
        return doScreen(ctx, preloadedStocks, startTime)
    }

    override suspend fun isAvailable() = true

    private suspend fun doScreen(
        ctx: android.content.Context,
        pool: List<StockRealtime>,
        startTime: Long
    ): Result<ScreeningResult> {
        return try {
            val db = StockDatabase.getInstance(ctx)
            val dao = db.dailySnapshotDao()
            val signals = mutableListOf<StrategySignal>()

            val candidates = pool.take(80)
            val codes = candidates.map { it.code }
            val nameMap = candidates.associate { it.code to it.name }

            // 批量獲取 K 線數據
            val candleMap = mutableMapOf<String, List<DailySnapshotEntity>>()
            for (code in codes) {
                val snaps = dao.getByCode(code, 30)
                if (snaps.size >= 5) {
                    candleMap[code] = snaps.sortedBy { it.date }
                }
            }

            // 批量檢測形態
            val patternResults = CandlePatternDetector.detectBatch(candleMap)

            for ((code, patterns) in patternResults) {
                // 只看多形態
                val bullish = patterns.filter { it.direction == CandlePatternDetector.Direction.BULLISH }
                if (bullish.isEmpty()) continue

                val totalStrength = bullish.sumOf { it.strength }
                val patternNames = bullish.joinToString("/") { it.patternName }

                // 評分：形態強度加總 * 15，上限 100
                val strength = (totalStrength * 15).coerceAtMost(100)
                val realtime = candidates.find { it.code == code }

                val details = buildMap {
                    put("patterns", patternNames)
                    put("bullishCount", "${bullish.size}")
                    put("totalStrength", "$totalStrength")
                }

                signals.add(
                    StrategySignal(
                        stockCode = code,
                        stockName = nameMap[code] ?: code,
                        strategyId = id,
                        category = category,
                        strength = strength,
                        action = if (strength >= 70) SignalAction.BUY else SignalAction.WATCH,
                        reason = "K線形態: $patternNames (強度$totalStrength)",
                        details = details,
                        currentPrice = realtime?.price ?: 0.0,
                        changePercent = realtime?.changePercent ?: 0.0
                    )
                )
            }

            Result.success(
                ScreeningResult(
                    strategyId = id,
                    strategyName = name,
                    category = category,
                    signals = signals.sortedByDescending { it.strength },
                    totalScanned = candidates.size,
                    scanTimeMs = System.currentTimeMillis() - startTime
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
