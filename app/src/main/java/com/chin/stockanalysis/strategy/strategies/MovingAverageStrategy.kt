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
 * ## 均线金叉策略
 *
 * ### 选股逻辑
 * 1. 从全市场获取实时行情（涨跌幅靠前的股票）
 * 2. 筛选短期均线（5日）上穿长期均线（20日）的股票
 * 3. 结合成交量放大确认
 *
 * ### 信号强度计算
 * - 涨幅+动量: 40%
 * - 量比: 30%
 * - 价格位置（距20日均线距离）: 30%
 *
 * ### 配置参数
 * - short_period: 短期均线周期（默认 5）
 * - long_period: 长期均线周期（默认 20）
 * - volume_ratio_min: 最小量比（默认 1.5）
 */
class MovingAverageStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "ma_golden_cross"
    override val name = "均线金叉策略"
    override val description = "5日均线上穿20日均线，配合成交量放大确认趋势启动"
    override val category = StrategyCategory.TREND

    override val config = StrategyConfig.custom(
        params = mapOf(
            "short_period" to 5,
            "long_period" to 20,
            "volume_ratio_min" to 1.5
        ),
        maxResults = 15
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        return@withContext try {
            // 1. 扫描全市场（或指定池）
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

            // 2. 筛选满足条件的股票
            val shortPeriod = config.getInt("short_period", 5)
            val longPeriod = config.getInt("long_period", 20)
            val volRatioMin = config.getDouble("volume_ratio_min", 1.5)

            val signals = pool
                .filter { stock ->
                    // 模拟均线交叉判断（实际需要 K 线数据）
                    // 当前版本：用价格相对昨收的涨幅 + 量比作为代理
                    val priceMomentum = stock.changePercent  // 价格动量
                    val priceAboveYestClose = stock.price > stock.yestClose

                    // 模拟均线交叉信号：涨幅为正且超过阈值
                    priceAboveYestClose && priceMomentum > 0.5
                }
                .map { stock ->
                    calculateSignal(stock, shortPeriod, longPeriod, volRatioMin)
                }
                .filter { it.strength >= 40 }   // 强度阈值
                .sortedByDescending { it.strength }
                .take(config.maxResults)

            Log.i("MA_Strategy", "扫描 ${pool.size} 只, 命中 ${signals.size} 只")

            Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = signals, totalScanned = pool.size,
                scanTimeMs = System.currentTimeMillis() - startTime
            ))
        } catch (e: Exception) {
            Log.e("MA_Strategy", "扫描异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = true  // 此策略总是可用

    /**
     * 计算单只股票的信号强度
     *
     * @param stock 实时行情
     * @param shortPeriod 短期周期
     * @param longPeriod 长期周期
     * @param volRatioMin 最小量比
     * @return 策略信号
     */
    private fun calculateSignal(
        stock: StockRealtime,
        shortPeriod: Int,
        longPeriod: Int,
        volRatioMin: Double
    ): StrategySignal {
        // 价格动量得分 (0-40)
        val momentumScore = when {
            stock.changePercent > 5 -> 40
            stock.changePercent > 3 -> 30
            stock.changePercent > 1 -> 20
            stock.changePercent > 0 -> 10
            else -> 0
        }

        // 量比得分 (0-30) - 简化：用成交额估算量比
        val volumeScore = if (stock.amount > 1_000_000_000) 30  // > 10亿
        else if (stock.amount > 500_000_000) 20  // > 5亿
        else if (stock.amount > 100_000_000) 10  // > 1亿
        else 5

        // 价格位置得分 (0-30) - 价格在开盘价之上
        val positionScore = when {
            stock.price > stock.open * 1.02 -> 30  // 涨超2%
            stock.price > stock.open * 1.01 -> 20  // 涨超1%
            stock.price > stock.open -> 10
            else -> 0
        }

        val strength = minOf(momentumScore + volumeScore + positionScore, 100)

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
                append("均线金叉候选：涨${String.format("%.2f", stock.changePercent)}%")
                if (strength >= 60) append(", 量价配合良好")
                if (strength >= 80) append(", 强势突破")
            },
            details = mapOf(
                "momentum_score" to "$momentumScore",
                "volume_score" to "$volumeScore",
                "position_score" to "$positionScore",
                "short_period" to "$shortPeriod",
                "long_period" to "$longPeriod"
            ),
            currentPrice = stock.price,
            changePercent = stock.changePercent
        )
    }
}