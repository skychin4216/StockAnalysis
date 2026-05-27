package com.chin.stockanalysis.strategy.strategies

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 放量突破策略
 *
 * ### 选股逻辑
 * 1. 成交量放大（当日成交额 > 前一周期均值的 2 倍）
 * 2. 价格突破近期高点（当前价 > 近期最高价）
 * 3. 涨幅显著（> 2%）
 *
 * ### 信号强度计算
 * - 量比: 40% （核心指标）
 * - 突破强度: 30% （价格超过近期高点的幅度）
 * - 涨幅: 30% （当日涨幅）
 *
 * ### 配置参数
 * - volume_ratio_min: 最小量比（默认 2.0）
 * - break_percent_min: 最小突破幅度百分比（默认 1.0）
 * - change_percent_min: 最小涨幅（默认 2.0）
 */
class VolumeBreakStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "volume_break"
    override val name = "放量突破策略"
    override val description = "成交量放大2倍以上，价格突破近期高点，确认强势突破信号"
    override val category = StrategyCategory.VOLUME

    override val config = StrategyConfig.custom(
        params = mapOf(
            "volume_ratio_min" to 2.0,
            "break_percent_min" to 1.0,
            "change_percent_min" to 2.0
        ),
        maxResults = 15
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        return@withContext try {
            val pool = if (config.stockPool.isEmpty()) {
                screener.scanFullMarket()
            } else {
                screener.scanSpecific(config.stockPool).values.toList()
            }

            if (pool.isEmpty()) {
                return@withContext Result.success(ScreeningResult(
                    strategyId = id, strategyName = name, category = category,
                    signals = emptyList(), totalScanned = 0,
                    scanTimeMs = System.currentTimeMillis() - startTime
                ))
            }

            val volRatioMin = config.getDouble("volume_ratio_min", 2.0)
            val breakPercentMin = config.getDouble("break_percent_min", 1.0)
            val changePercentMin = config.getDouble("change_percent_min", 2.0)

            val signals = pool
                .filter { stock ->
                    // 涨幅过滤
                    stock.changePercent >= changePercentMin &&
                    // 价格在开盘价之上（突破信号）
                    stock.price > stock.open &&
                    // 成交额过滤（最小1千万）
                    stock.amount > 10_000_000
                }
                .map { stock ->
                    calculateSignal(stock, volRatioMin, breakPercentMin)
                }
                .filter { it.strength >= 50 }   // 强度阈值
                .sortedByDescending { it.strength }
                .take(config.maxResults)

            Log.i("VolumeBreak", "扫描 ${pool.size} 只, 命中 ${signals.size} 只")

            Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = signals, totalScanned = pool.size,
                scanTimeMs = System.currentTimeMillis() - startTime
            ))
        } catch (e: Exception) {
            Log.e("VolumeBreak", "扫描异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = true

    /**
     * 计算放量突破信号强度
     */
    private fun calculateSignal(
        stock: StockRealtime,
        volRatioMin: Double,
        breakPercentMin: Double
    ): StrategySignal {
        // 量比得分 (0-40) - 用成交额规模作为代理
        val volumeScore = when {
            stock.amount > 2_000_000_000 -> 40   // > 20亿
            stock.amount > 1_000_000_000 -> 30   // > 10亿
            stock.amount > 500_000_000 -> 20     // > 5亿
            stock.amount > 200_000_000 -> 10     // > 2亿
            else -> 5
        }

        // 突破强度得分 (0-30) - 价格超过开盘价的幅度
        val breakPercent = if (stock.open > 0) {
            ((stock.price - stock.open) / stock.open) * 100
        } else 0.0

        val breakScore = when {
            breakPercent > 5 -> 30   // > 5% 突破
            breakPercent > 3 -> 25
            breakPercent > 2 -> 20
            breakPercent > 1 -> 15
            breakPercent > 0 -> 10
            else -> 0
        }

        // 涨幅得分 (0-30)
        val changeScore = when {
            stock.changePercent > 7 -> 30    // 接近涨停
            stock.changePercent > 5 -> 25
            stock.changePercent > 3 -> 20
            stock.changePercent > 2 -> 15
            else -> 10
        }

        val strength = minOf(volumeScore + breakScore + changeScore, 100)

        return StrategySignal(
            stockCode = stock.code,
            stockName = stock.name,
            strategyId = id,
            category = category,
            strength = strength,
            action = when {
                strength >= 80 -> SignalAction.BUY
                strength >= 60 -> SignalAction.WATCH
                else -> SignalAction.HOLD
            },
            reason = buildString {
                append("放量突破：涨${String.format("%.2f", stock.changePercent)}%")
                append(", 突破${String.format("%.2f", breakPercent)}%")
                val amountYuan = if (stock.amount > 100_000_000) {
                    "${String.format("%.1f", stock.amount / 100_000_000)}亿"
                } else "少量"
                append(", 成交$amountYuan")
                if (strength >= 80) append(", 强势放量突破")
            },
            details = mapOf(
                "volume_score" to "$volumeScore",
                "break_score" to "$breakScore",
                "change_score" to "$changeScore",
                "break_percent" to "${String.format("%.2f", breakPercent)}%",
                "amount" to "${stock.amount}",
                "vol_ratio_min" to "$volRatioMin"
            ),
            currentPrice = stock.price,
            changePercent = stock.changePercent
        )
    }
}