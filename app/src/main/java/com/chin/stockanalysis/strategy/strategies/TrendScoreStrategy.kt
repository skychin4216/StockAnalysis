package com.chin.stockanalysis.strategy.strategies

import android.content.Context
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
import kotlin.math.abs

/**
 * ## 趋势加减分策略
 *
 * 参考现有趋势图（K线 + 均线排列），对输入端的标的进行趋势评分：
 * - 满足上升趋势 → 加分（增强买入信号）
 * - 满足下降趋势 → 减分（可能触发卖出参考）
 * - 趋势不明显 → 中性评分（不影响原有排序）
 *
 * 与其他策略不同，本策略 **不过滤标的**：
 * 所有输入股票都会保留在结果中，只调整评分。
 * 这使得它可以用作其他策略的「趋势修正器」。
 *
 * 评分逻辑：
 * - 基础分：50（中性）
 * - 均线排列（MA5/MA10/MA20/MA60）：多头 +5~+20，空头 -5~-20
 * - 趋势强度（ADX）：强趋势额外 ±5~±10
 * - 价格动量（5日/10日涨幅）：顺势 ±3~±10，逆势 ∓3~∓10
 * - 量价配合：放量上涨 +5，放量下跌 -5
 *
 * 最终评分范围：0~100
 * - >= 70：BUY（趋势强多，强烈推荐）
 * - >= 55：WATCH（趋势偏多，保持关注）
 * - >= 40：HOLD（趋势中性，持有观察）
 * - < 40：SELL（趋势走弱，减仓参考）
 */
class TrendScoreStrategy(
    private val context: Context,
    private val screener: StockScreener? = null
) : Strategy {

    override val id = "trend_score"
    override var name = "趋势加减分"
    override var description = "参考趋势图对标的加减分：上升趋势加分，下降趋势减分，不过滤标的"
    override val category = StrategyCategory.TREND
    override val holdingPeriods = listOf(HoldingPeriod.SHORT, HoldingPeriod.MID)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 24
    override val defaultStopLoss: Float? = -0.08f
    override val defaultTakeProfit: Float? = null
    override val maxPositions: Int = 5

    override val config = StrategyConfig.custom(
        params = mapOf(
            "ma_short" to 5,
            "ma_mid" to 20,
            "ma_long" to 60,
            "adx_period" to 14,
            "adx_threshold" to 25.0,
            "momentum_days" to 5
        ),
        maxResults = 50  // 不过滤，保留所有标的
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("ma_alignment", "均线排列", 40, "MA5/MA10/MA20/MA60 多空排列"),
        WeightFactor("adx_strength", "趋势强度", 25, "ADX 判断趋势是否明确"),
        WeightFactor("momentum", "价格动量", 20, "5日/10日涨跌幅动量"),
        WeightFactor("volume_confirm", "量价配合", 15, "放量方向与价格方向一致性")
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = screener?.scanFullMarket() ?: emptyList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        return try {
            screenWithPool(preloadedStocks, startTime)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = true

    // ════════════════════════════════════════
    // 核心逻辑：不过滤，只加减分
    // ════════════════════════════════════════

    private suspend fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        val marketDir = try { screener?.detectMarketDirection() } catch (_: Exception) { null } ?: "OSCILLATION"
        Log.i("TrendScore", "大盘环境: $marketDir, 输入标的: ${pool.size}")

        val db = StockDatabase.getInstance(context)
        val dao = db.dailySnapshotDao()

        // 基本流动性过滤（只排除明显不可交易的，不做趋势过滤）
        val candidates = pool.filter {
            it.price > 1.0 &&
            !it.name.contains("ST", ignoreCase = true) &&
            !it.name.contains("退", ignoreCase = true)
        }

        val signals = mutableListOf<StrategySignal>()
        var bullishCount = 0
        var bearishCount = 0
        var neutralCount = 0

        for (stock in candidates) {
            try {
                // 读取 65 天历史数据（MA60 需要 60 天 + 缓冲）
                val history = dao.getByCode(stock.code, 65)
                if (history.size < 20) {
                    // 数据不足，给中性评分保留标的
                    signals.add(buildNeutralSignal(stock, "历史数据不足(${history.size}天)，中性保留"))
                    neutralCount++
                    continue
                }

                val sorted = history.sortedBy { it.date }
                val closes = sorted.map { it.close }
                val volumes = sorted.map { it.volume.toDouble() }
                val highs = sorted.map { it.high }
                val lows = sorted.map { it.low }

                // ── 1. 均线排列评分 (基础分 50, 加减 -20~+20) ──
                val ma5 = if (closes.size >= 5) closes.takeLast(5).average() else closes.average()
                val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else closes.average()
                val ma20 = closes.takeLast(20).average()
                val ma60 = if (closes.size >= 60) closes.takeLast(60).average() else ma20

                val alignmentScore = calculateAlignmentScore(ma5, ma10, ma20, ma60, stock.price)

                // ── 2. ADX 趋势强度评分 (加减 -10~+10) ──
                val adxScore = if (closes.size >= 28) {
                    val adx = calculateADX(highs, lows, closes, 14)
                    calculateAdxScore(adx, ma5, ma20)
                } else 0.0

                // ── 3. 价格动量评分 (加减 -10~+10) ──
                val momentumScore = calculateMomentumScore(closes, 5)

                // ── 4. 量价配合评分 (加减 -8~+8) ──
                val volumeScore = calculateVolumeScore(volumes, closes, stock)

                // ── 最终评分 = 50 + 各项加减分 ──
                val totalAdjustment = alignmentScore + adxScore + momentumScore + volumeScore
                val strength = (50 + totalAdjustment).toInt().coerceIn(0, 100)

                // 统计
                when {
                    strength >= 60 -> bullishCount++
                    strength <= 40 -> bearishCount++
                    else -> neutralCount++
                }

                // ── 生成信号（不过滤，所有标的都保留）──
                val action = when {
                    strength >= 70 -> SignalAction.BUY
                    strength >= 55 -> SignalAction.WATCH
                    strength >= 40 -> SignalAction.HOLD
                    else -> SignalAction.SELL  // 趋势走弱，触发卖出参考
                }

                val reason = buildReason(strength, alignmentScore, adxScore, momentumScore, volumeScore,
                    ma5, ma10, ma20, ma60, stock.price)

                signals.add(StrategySignal(
                    strategyId = id, category = category,
                    stockCode = stock.code, stockName = stock.name,
                    strength = strength, action = action, reason = reason,
                    currentPrice = stock.price, changePercent = stock.changePercent,
                    details = mapOf(
                        "alignment" to "%.1f".format(alignmentScore),
                        "adx" to "%.1f".format(adxScore),
                        "momentum" to "%.1f".format(momentumScore),
                        "volume" to "%.1f".format(volumeScore),
                        "trend_dir" to if (strength >= 60) "UP" else if (strength <= 40) "DOWN" else "NEUTRAL"
                    )
                ))
            } catch (e: Exception) {
                // 单股计算失败，给中性评分保留标的
                signals.add(buildNeutralSignal(stock, "计算异常: ${e.message?.take(30)}"))
                neutralCount++
            }
        }

        // 按评分排序（高分在前，低分在后方便查看卖出参考）
        val result = signals.sortedByDescending { it.strength }
        Log.i("TrendScore", "完成: ${candidates.size}标的 → 多${bullishCount} 空${bearishCount} 中性${neutralCount}")

        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = result, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }

    // ════════════════════════════════════════
    // 评分计算函数
    // ════════════════════════════════════════

    /**
     * 均线排列评分
     * 多头排列 (MA5>MA10>MA20>MA60): +5~+20
     * 空头排列 (MA5<MA10<MA20<MA60): -5~-20
     * 混乱排列: -3~+3
     */
    private fun calculateAlignmentScore(
        ma5: Double, ma10: Double, ma20: Double, ma60: Double, currentPrice: Double
    ): Double {
        var score = 0.0

        // 多头排列检查
        val bullishCount = listOf(
            ma5 > ma10, ma10 > ma20, ma20 > ma60, currentPrice > ma5
        ).count { it }

        // 空头排列检查
        val bearishCount = listOf(
            ma5 < ma10, ma10 < ma20, ma20 < ma60, currentPrice < ma5
        ).count { it }

        when {
            bullishCount == 4 -> {
                // 完美多头排列
                score = 20.0
                // 额外奖励：均线发散程度（MA5 vs MA60 的距离）
                val spread = if (ma60 > 0) (ma5 - ma60) / ma60 * 100 else 0.0
                score += when {
                    spread > 10 -> 0.0  // 已经给满分
                    spread > 5 -> 0.0
                    else -> 0.0
                }
            }
            bullishCount == 3 -> score = 12.0
            bullishCount == 2 -> score = 5.0
            bearishCount == 4 -> {
                // 完美空头排列
                score = -20.0
            }
            bearishCount == 3 -> score = -12.0
            bearishCount == 2 -> score = -5.0
            else -> {
                // 排列混乱，微调
                score = if (currentPrice > ma20) 3.0 else -3.0
            }
        }

        return score
    }

    /**
     * ADX 趋势强度评分
     * ADX > 25 且价格在 MA20 上方 → +5~+10（上升趋势强）
     * ADX > 25 且价格在 MA20 下方 → -5~-10（下降趋势强）
     * ADX < 20 → 0（无趋势，不加减分）
     */
    private fun calculateAdxScore(adx: Double, ma5: Double, ma20: Double): Double {
        if (adx < 20) return 0.0  // 无明确趋势

        val isAboveMA20 = ma5 > ma20
        return when {
            adx > 40 && isAboveMA20 -> 10.0   // 极强上升趋势
            adx > 40 && !isAboveMA20 -> -10.0  // 极强下降趋势
            adx > 30 && isAboveMA20 -> 7.0
            adx > 30 && !isAboveMA20 -> -7.0
            adx > 25 && isAboveMA20 -> 5.0
            adx > 25 && !isAboveMA20 -> -5.0
            else -> 0.0
        }
    }

    /**
     * 价格动量评分
     * 5日涨幅 > 3% → +5~+10
     * 5日跌幅 > 3% → -5~-10
     * 小幅波动 → -2~+2
     */
    private fun calculateMomentumScore(closes: List<Double>, days: Int): Double {
        if (closes.size < days + 1) return 0.0

        val recentClose = closes.last()
        val pastClose = closes[closes.size - 1 - days]
        if (pastClose <= 0) return 0.0

        val momentum = (recentClose - pastClose) / pastClose * 100

        return when {
            momentum > 8 -> 10.0
            momentum > 5 -> 7.0
            momentum > 3 -> 5.0
            momentum > 1 -> 3.0
            momentum > -1 -> 0.0
            momentum > -3 -> -3.0
            momentum > -5 -> -5.0
            momentum > -8 -> -7.0
            else -> -10.0
        }
    }

    /**
     * 量价配合评分
     * 放量上涨 → +5~+8
     * 放量下跌 → -5~-8
     * 缩量 → -2~+2
     */
    private fun calculateVolumeScore(
        volumes: List<Double>, closes: List<Double>, stock: StockRealtime
    ): Double {
        if (volumes.size < 11) return 0.0

        val avgVol10 = volumes.takeLast(11).dropLast(1).average()
        if (avgVol10 <= 0) return 0.0

        val volRatio = stock.volume.toDouble() / avgVol10
        val priceUp = stock.price > stock.open

        return when {
            volRatio > 2.0 && priceUp -> 8.0    // 放量大涨
            volRatio > 1.5 && priceUp -> 5.0    // 放量上涨
            volRatio > 2.0 && !priceUp -> -8.0  // 放量大跌
            volRatio > 1.5 && !priceUp -> -5.0  // 放量下跌
            volRatio < 0.5 -> if (priceUp) 2.0 else -2.0  // 缩量
            else -> 0.0
        }
    }

    // ════════════════════════════════════════
    // ADX 计算（Wilder 平滑法）
    // ════════════════════════════════════════

    private fun calculateADX(
        highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int
    ): Double {
        if (highs.size < period * 2 + 1) return 0.0

        val trueRanges = mutableListOf<Double>()
        val plusDMs = mutableListOf<Double>()
        val minusDMs = mutableListOf<Double>()

        for (i in 1 until highs.size) {
            val tr = maxOf(
                highs[i] - lows[i],
                abs(highs[i] - closes[i - 1]),
                abs(lows[i] - closes[i - 1])
            )
            val plusDM = if (highs[i] - highs[i - 1] > lows[i - 1] - lows[i]) {
                maxOf(0.0, highs[i] - highs[i - 1])
            } else 0.0
            val minusDM = if (lows[i - 1] - lows[i] > highs[i] - highs[i - 1]) {
                maxOf(0.0, lows[i - 1] - lows[i])
            } else 0.0

            trueRanges.add(tr)
            plusDMs.add(plusDM)
            minusDMs.add(minusDM)
        }

        // Wilder 平滑
        val smoothedTR = wilderSmooth(trueRanges, period)
        val smoothedPlusDM = wilderSmooth(plusDMs, period)
        val smoothedMinusDM = wilderSmooth(minusDMs, period)

        val dxValues = mutableListOf<Double>()
        for (i in smoothedTR.indices) {
            if (smoothedTR[i] > 0) {
                val plusDI = smoothedPlusDM[i] / smoothedTR[i] * 100
                val minusDI = smoothedMinusDM[i] / smoothedTR[i] * 100
                val dx = if (plusDI + minusDI > 0) {
                    abs(plusDI - minusDI) / (plusDI + minusDI) * 100
                } else 0.0
                dxValues.add(dx)
            }
        }

        return if (dxValues.isNotEmpty()) dxValues.takeLast(period).average() else 0.0
    }

    private fun wilderSmooth(values: List<Double>, period: Int): List<Double> {
        if (values.size < period) return emptyList()
        val result = mutableListOf<Double>()
        var sum = values.take(period).sum()
        result.add(sum)
        for (i in period until values.size) {
            sum = sum - sum / period + values[i]
            result.add(sum)
        }
        return result
    }

    // ════════════════════════════════════════
    // 辅助函数
    // ════════════════════════════════════════

    private fun buildNeutralSignal(stock: StockRealtime, reason: String): StrategySignal {
        return StrategySignal(
            strategyId = id, category = category,
            stockCode = stock.code, stockName = stock.name,
            strength = 50, action = SignalAction.HOLD, reason = reason,
            currentPrice = stock.price, changePercent = stock.changePercent,
            details = mapOf("trend_dir" to "NEUTRAL")
        )
    }

    private fun buildReason(
        strength: Int, alignment: Double, adx: Double, momentum: Double, volume: Double,
        ma5: Double, ma10: Double, ma20: Double, ma60: Double, price: Double
    ): String {
        val trendDir = if (strength >= 60) "↑上升" else if (strength <= 40) "↓下降" else "→中性"
        val alignmentDesc = when {
            ma5 > ma10 && ma10 > ma20 && ma20 > ma60 -> "多头排列"
            ma5 < ma10 && ma10 < ma20 && ma20 < ma60 -> "空头排列"
            else -> "均线纠缠"
        }
        return buildString {
            append("${trendDir}趋势($strength) ")
            append(alignmentDesc)
            append(" 加减分: 均线${"%+.1f".format(alignment)}")
            if (adx != 0.0) append(" ADX${"%+.1f".format(adx)}")
            append(" 动量${"%+.1f".format(momentum)}")
            append(" 量价${"%+.1f".format(volume)}")
            if (strength < 40) append(" ⚠️趋势走弱注意减仓")
        }
    }
}
