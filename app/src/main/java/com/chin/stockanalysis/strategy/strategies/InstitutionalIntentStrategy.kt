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
 * ## 主力意圖策略（Pipeline-based）
 *
 * 復用 TTradePipelineNodes 的主力意圖分析邏輯，對全市場掃描。
 * 分析主力當前行為：建倉吸貨 / 震倉洗盤 / 拉升中 / 出貨 / 無法判斷。
 *
 * 只輸出「建倉吸貨」和「震倉洗盤」（低買機會）的股票。
 */
class InstitutionalIntentStrategy(
    private val screener: StockScreener,
    private val appContext: android.content.Context? = null
) : Strategy {

    override val id = "institutional_intent"
    override var name = "主力意圖追蹤"
    override var description = "復用主力意圖分析：量價關係+K線特徵+RSI+布林位置+均線排列，識別建倉/洗盤信號"
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
        WeightFactor("volume_price", "量價信號", 30, "放量/縮量+價格變化"),
        WeightFactor("kline_feature", "K線特徵", 20, "上下影線+陰陽線"),
        WeightFactor("rsi_zone", "RSI區間", 15, "超賣/中性/超買"),
        WeightFactor("boll_pos", "布林位置", 15, "上中下軌位置"),
        WeightFactor("ma_align", "均線排列", 20, "多頭/空頭排列")
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

                    // 只取建倉吸貨和震倉洗盤
                    if (intent.intent != "建倉吸貨" && intent.intent != "震倉洗盤") continue

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
                            action = if (intent.intent == "建倉吸貨" && strength >= 65) SignalAction.BUY else SignalAction.WATCH,
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

    // ─── 主力意圖分析（簡化版，復用 TInstIntentNode 核心邏輯）───

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

        // 1. 量價信號
        val avgVol5 = recent5.map { it.volume.toDouble() }.average()
        val avgVol10 = recent10.map { it.volume.toDouble() }.average()
        val volRatio = if (avgVol10 > 0) avgVol5 / avgVol10 else 1.0

        val priceChange5 = if (recent10.size >= 6) {
            val p0 = recent10[recent10.size - 6].close
            val p1 = recent5.last().close
            if (p0 > 0) (p1 - p0) / p0 else 0.0
        } else 0.0

        val volumeSignal = when {
            volRatio > 1.3 && priceChange5 > 0.02 -> "放量上漲"
            volRatio > 1.3 && abs(priceChange5) < 0.02 -> "放量滯漲"
            volRatio < 0.7 && abs(priceChange5) < 0.02 -> "縮量橫盤"
            volRatio < 0.7 && priceChange5 < -0.02 -> "縮量下跌"
            volRatio > 1.3 && priceChange5 < -0.02 -> "放量下跌"
            else -> "量價平穩"
        }

        // 2. RSI
        val rsi = RsiCalculator.fromSnaps(snaps)
        val rsiZone = when {
            rsi < 30 -> "超賣"
            rsi < 40 -> "偏弱"
            rsi < 60 -> "中性"
            rsi < 70 -> "偏強"
            else -> "超買"
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
            bollPos < 0.2 -> "下軌附近"
            bollPos < 0.4 -> "中下軌"
            bollPos < 0.6 -> "中軌"
            bollPos < 0.8 -> "中上軌"
            else -> "上軌附近"
        }

        // 4. 均線排列
        val ma5 = snaps.takeLast(5).map { it.close }.average()
        val ma10 = snaps.takeLast(10).map { it.close }.average()
        val ma20val = snaps.takeLast(20).map { it.close }.average()
        val maBullish = ma5 > ma10 && ma10 > ma20val
        val maBearish = ma5 < ma10 && ma10 < ma20val

        // 5. 綜合判斷
        val (intent, confidence) = when {
            // 建倉吸貨：縮量橫盤/低量 + 價格低位 + RSI偏弱
            (volumeSignal == "縮量橫盤" || volumeSignal == "量價平穩") &&
                bollPos < 0.4 && rsi < 45 && !maBearish ->
                "建倉吸貨" to 0.7

            // 震倉洗盤：放量下跌但RSI不極端 + 均線未完全破壞
            volumeSignal == "放量下跌" && rsi >= 30 && rsi <= 50 && ma10 > ma20val ->
                "震倉洗盤" to 0.6

            // 拉升中：放量上漲 + 均線多頭
            volumeSignal == "放量上漲" && maBullish && rsi > 50 ->
                "拉升中" to 0.75

            // 出貨：放量滯漲/放量下跌 + RSI偏高
            (volumeSignal == "放量滯漲" || volumeSignal == "放量下跌") && rsi > 60 ->
                "出貨" to 0.65

            else -> "無法判斷" to 0.3
        }

        return SimpleIntent(intent, confidence, volumeSignal, rsiZone, bollPosition)
    }
}
