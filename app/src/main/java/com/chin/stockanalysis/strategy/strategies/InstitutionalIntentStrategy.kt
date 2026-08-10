package com.chin.stockanalysis.strategy.strategies

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.analysis.RsiCalculator
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * ## 主力意图策略（Pipeline-based）
 *
 * 复用 TTradePipelineNodes 的主力意图分析逻辑，对全市场扫描。
 * 分析主力当前行为：建仓吸货 / 震仓洗盘 / 拉升中 / 出货 / 无法判断。
 *
 * 只输出「建仓吸货」和「震仓洗盘」（低买机会）的股票。
 */
class InstitutionalIntentStrategy(
    private val screener: StockScreener,
    private val appContext: android.content.Context? = null
) : Strategy {

    override val id = "institutional_intent"
    override var name = "主力意图追踪"
    override var description = "复用主力意图分析：量价关系+K线特征+RSI+布林位置+均线排列，识别建仓/洗盘信号"
    override val category = StrategyCategory.VOLUME
    override val holdingPeriods = listOf(HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN

    override val config = StrategyConfig.custom(
        params = mapOf("min_confidence" to 0.6, "lookback_days" to 30)
    )

    override val defaultStopLoss = -0.05f
    override val defaultTakeProfit = 0.12f
    override val maxPositions = 5

    override var weightFactors = listOf(
        WeightFactor("volume_price", "量价信号", 30, "放量/缩量+价格变化"),
        WeightFactor("kline_feature", "K线特征", 20, "上下影线+阴阳线"),
        WeightFactor("rsi_zone", "RSI区间", 15, "超卖/中性/超买"),
        WeightFactor("boll_pos", "布林位置", 15, "上中下轨位置"),
        WeightFactor("ma_align", "均线排列", 20, "多头/空头排列")
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
            for (stock in candidates) {
                try {
                    val snaps = dao.getByCode(stock.code, 30)
                    if (snaps.size < 15) continue

                    val sorted = snaps.sortedBy { it.date }
                    val intent = analyzeIntent(sorted)
                    if (intent == null) continue

                    // 只取建仓吸货和震仓洗盘
                    if (intent.intent != "建仓吸货" && intent.intent != "震仓洗盘") continue

                    val strength = (intent.confidence * 100).toInt().coerceIn(0, 100)
                    val details = buildMap {
                        put("intent", intent.intent)
                        put("confidence", "${"%.0f".format(intent.confidence * 100)}%")
                        put("volumeSignal", intent.volumeSignal)
                        put("rsi", intent.rsiZone)
                        put("boll", intent.bollPosition)
                    }

                    signals.add(
                        StrategySignal(
                            stockCode = stock.code,
                            stockName = stock.name,
                            strategyId = id,
                            category = category,
                            strength = strength,
                            action = if (intent.intent == "建仓吸货" && strength >= 65) SignalAction.BUY else SignalAction.WATCH,
                            reason = "主力${intent.intent}: ${intent.volumeSignal}",
                            details = details,
                            currentPrice = stock.price,
                            changePercent = stock.changePercent
                        )
                    )
                } catch (_: Exception) {}
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

    // ─── 主力意图分析（简化版，复用 TInstIntentNode 核心逻辑）───

    data class SimpleIntent(
        val intent: String,
        val confidence: Double,
        val volumeSignal: String,
        val rsiZone: String,
        val bollPosition: String
    )

    private fun analyzeIntent(snaps: List<DailySnapshotEntity>): SimpleIntent? {
        if (snaps.size < 15) return null

        val recent5 = snaps.takeLast(5)
        val recent10 = snaps.takeLast(10)

        // 1. 量价信号
        val avgVol5 = recent5.map { it.volume.toDouble() }.average()
        val avgVol10 = recent10.map { it.volume.toDouble() }.average()
        val volRatio = if (avgVol10 > 0) avgVol5 / avgVol10 else 1.0

        val priceChange5 = if (recent10.size >= 6) {
            val p0 = recent10[recent10.size - 6].close
            val p1 = recent5.last().close
            if (p0 > 0) (p1 - p0) / p0 else 0.0
        } else 0.0

        val volumeSignal = when {
            volRatio > 1.3 && priceChange5 > 0.02 -> "放量上涨"
            volRatio > 1.3 && abs(priceChange5) < 0.02 -> "放量滞涨"
            volRatio < 0.7 && abs(priceChange5) < 0.02 -> "缩量横盘"
            volRatio < 0.7 && priceChange5 < -0.02 -> "缩量下跌"
            volRatio > 1.3 && priceChange5 < -0.02 -> "放量下跌"
            else -> "量价平稳"
        }

        // 2. RSI
        val rsi = RsiCalculator.fromSnaps(snaps)
        val rsiZone = when {
            rsi < 30 -> "超卖"
            rsi < 40 -> "偏弱"
            rsi < 60 -> "中性"
            rsi < 70 -> "偏强"
            else -> "超买"
        }

        // 3. 布林位置
        val closes = snaps.takeLast(20).map { it.close }
        val ma20 = closes.average()
        val std20 = if (closes.size >= 2) {
            val mean = ma20
            Math.sqrt(closes.map { (it - mean) * (it - mean) }.average())
        } else 0.0
        val upper = ma20 + 2 * std20
        val lower = ma20 - 2 * std20
        val currentPrice = snaps.last().close
        val bollPos = if (upper > lower) (currentPrice - lower) / (upper - lower) else 0.5
        val bollPosition = when {
            bollPos < 0.2 -> "下轨附近"
            bollPos < 0.4 -> "中下轨"
            bollPos < 0.6 -> "中轨"
            bollPos < 0.8 -> "中上轨"
            else -> "上轨附近"
        }

        // 4. 均线排列
        val ma5 = snaps.takeLast(5).map { it.close }.average()
        val ma10 = snaps.takeLast(10).map { it.close }.average()
        val ma20val = snaps.takeLast(20).map { it.close }.average()
        val maBullish = ma5 > ma10 && ma10 > ma20val
        val maBearish = ma5 < ma10 && ma10 < ma20val

        // 5. 综合判断
        val (intent, confidence) = when {
            // 建仓吸货：缩量横盘/低量 + 价格低位 + RSI偏弱
            (volumeSignal == "缩量横盘" || volumeSignal == "量价平稳") &&
                bollPos < 0.4 && rsi < 45 && !maBearish ->
                "建仓吸货" to 0.7

            // 震仓洗盘：放量下跌但RSI不极端 + 均线未完全破坏
            volumeSignal == "放量下跌" && rsi >= 30 && rsi <= 50 && ma10 > ma20val ->
                "震仓洗盘" to 0.6

            // 拉升中：放量上涨 + 均线多头
            volumeSignal == "放量上涨" && maBullish && rsi > 50 ->
                "拉升中" to 0.75

            // 出货：放量滞涨/放量下跌 + RSI偏高
            (volumeSignal == "放量滞涨" || volumeSignal == "放量下跌") && rsi > 60 ->
                "出货" to 0.65

            else -> "无法判断" to 0.3
        }

        return SimpleIntent(intent, confidence, volumeSignal, rsiZone, bollPosition)
    }
}
