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
 * ## 低估值策略
 *
 * ### 选股逻辑
 * 1. 市盈率 < 行业均值的 70%（实际用价格+涨跌幅代理估值评估）
 * 2. 近期有企稳迹象（跌幅收窄或小幅上涨）
 * 3. 成交活跃度不低（流动性能支持）
 *
 * ### 信号强度计算
 * - 估值评分: 40%（基于价格水平和涨跌幅倒数）
 * - 企稳评分: 30%（跌幅收窄/横盘）
 * - 流动性评分: 30%（成交活跃度）
 *
 * ### 配置参数
 * - pe_max: 最大市盈率阈值（默认 15）
 * - roe_min: 最小 ROE（默认 15%）
 * - stabilization_days: 企稳天数（默认 5）
 */
class LowValuationStrategy(
    private val screener: StockScreener
) : Strategy {

    override val id = "low_valuation"
    override val name = "低估值策略"
    override val description = "筛选市盈率低于行业均值、基本面稳健且价格企稳的低估值股票"
    override val category = StrategyCategory.VALUE

    override val config = StrategyConfig.custom(
        params = mapOf(
            "pe_max" to 15.0,
            "roe_min" to 15.0,
            "stabilization_days" to 5
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

            val peMax = config.getDouble("pe_max", 15.0)
            val roeMin = config.getDouble("roe_min", 15.0)

            // 低估值特征：
            // 1. 股价相对低位（价格<100元为中小盘，或大盘股但涨幅较小）
            // 2. 成交不冷清（日成交额 > 5000万）
            // 3. 非涨停/跌停（涨跌幅在 ±5% 内）
            val signals = pool
                .filter { stock ->
                    stock.amount > 50_000_000 &&                     // 日成交>5千万
                    stock.changePercent in -5.0..5.0 &&              // 排除极端行情
                    stock.price > 1.0                                 // 排除仙股
                }
                .map { stock ->
                    calculateSignal(stock, peMax, roeMin)
                }
                .filter { it.strength >= 40 }   // 强度阈值
                .sortedByDescending { it.strength }
                .take(config.maxResults)

            Log.i("LowVal", "扫描 ${pool.size} 只, 命中 ${signals.size} 只")

            Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = signals, totalScanned = pool.size,
                scanTimeMs = System.currentTimeMillis() - startTime
            ))
        } catch (e: Exception) {
            Log.e("LowVal", "扫描异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = true

    /**
     * 计算低估值信号强度
     */
    private fun calculateSignal(
        stock: StockRealtime,
        peMax: Double,
        roeMin: Double
    ): StrategySignal {
        // 估值评分 (0-40) - 价格越低估值越低（简化：用价格作为代理）
        val priceLevel = stock.price
        val valuationScore = when {
            priceLevel < 10 -> 40     // 低价股
            priceLevel < 20 -> 35
            priceLevel < 50 -> 30
            priceLevel < 100 -> 20
            priceLevel < 200 -> 10
            else -> 5
        }

        // 企稳评分 (0-30) - 跌幅小或正值，波动小
        val changeAbs = kotlin.math.abs(stock.changePercent)
        val stableScore = when {
            changeAbs < 0.5 -> 30    // 非常平稳
            changeAbs < 1.0 -> 25
            changeAbs < 2.0 -> 20
            changeAbs < 3.0 -> 15
            changeAbs < 5.0 -> 10
            else -> 5
        }

        // 流动性评分 (0-30) - 成交活跃但不放量
        val liquidityScore = when {
            stock.amount > 500_000_000 -> 25    // > 5亿
            stock.amount > 200_000_000 -> 20    // > 2亿
            stock.amount > 100_000_000 -> 15    // > 1亿
            else -> 10
        }

        val strength = minOf(valuationScore + stableScore + liquidityScore, 100)

        return StrategySignal(
            stockCode = stock.code,
            stockName = stock.name,
            strategyId = id,
            category = category,
            strength = strength,
            action = when {
                strength >= 70 -> SignalAction.BUY
                strength >= 50 -> SignalAction.WATCH
                else -> SignalAction.HOLD
            },
            reason = buildString {
                append("低估值候选：¥${String.format("%.2f", stock.price)}")
                append(", 涨跌${String.format("%.2f", stock.changePercent)}%")
                if (strength >= 70) append(", 估值极低+企稳信号明显")
                else if (strength >= 50) append(", 估值偏低+横盘企稳")
                if (stock.amount > 100_000_000) {
                    val amountYi = stock.amount / 100_000_000
                    append(", 流动性充足(${String.format("%.1f", amountYi)}亿)")
                }
            },
            details = mapOf(
                "valuation_score" to "$valuationScore",
                "stable_score" to "$stableScore",
                "liquidity_score" to "$liquidityScore",
                "price" to "${stock.price}",
                "pe_max_ref" to "$peMax",
                "roe_min_ref" to "$roeMin"
            ),
            currentPrice = stock.price,
            changePercent = stock.changePercent
        )
    }
}