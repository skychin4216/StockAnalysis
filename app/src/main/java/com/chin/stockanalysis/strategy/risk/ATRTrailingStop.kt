package com.chin.stockanalysis.strategy.risk

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import kotlin.math.max

/**
 * ## ATR 动态追踪止损
 *
 * 不是选股策略，是持仓管理工具，供 AutoSellEngine / Fragment 调用。
 *
 * 逻辑：
 * - ATR(14) = 14 日 True Range 均值
 * - 追踪止损线 = 持仓期间最高价 - N × ATR
 * - 止损线只上移不下移（棘轮机制）
 * - 触发条件：当前价 < 止损线 → 卖出
 *
 * 不同周期 N 值：
 * - ULTRA_SHORT: 1.5（紧贴，快速止损）
 * - SHORT: 2.0
 * - MID: 2.5
 * - LONG: 3.0（宽容，避免被洗出）
 */
object ATRTrailingStop {

    /**
     * 计算 ATR(14)
     *
     * @param highs 近 N 天最高价（升序，最后一个是今天）
     * @param lows 近 N 天最低价
     * @param closes 近 N 天收盘价
     * @param period ATR 周期（默认 14）
     * @return ATR 值，数据不足时返回 0
     */
    fun calculateATR(
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>,
        period: Int = 14
    ): Double {
        if (highs.size < period + 1 || lows.size < period + 1 || closes.size < period + 1) return 0.0

        val trueRanges = mutableListOf<Double>()
        for (i in 1 until highs.size) {
            val tr = maxOf(
                highs[i] - lows[i],
                Math.abs(highs[i] - closes[i - 1]),
                Math.abs(lows[i] - closes[i - 1])
            )
            trueRanges.add(tr)
        }

        // 取最近 period 个 TR 的均值
        return trueRanges.takeLast(period).average()
    }

    /**
     * 计算追踪止损价
     *
     * @param highestSinceBuy 买入后的最高价
     * @param atr 当前 ATR 值
     * @param period 持仓周期（决定 N 倍数）
     * @return 止损价格
     */
    fun calculateStopPrice(
        highestSinceBuy: Double,
        atr: Double,
        period: HoldingPeriod
    ): Double {
        val multiplier = getMultiplier(period)
        return highestSinceBuy - multiplier * atr
    }

    /**
     * 判断是否触发止损
     *
     * @param currentPrice 当前价格
     * @param highestSinceBuy 买入后最高价
     * @param atr 当前 ATR
     * @param period 持仓周期
     * @param previousStopPrice 上一次计算的止损价（用于棘轮：只上移不下移）
     * @return Triple(是否触发, 新止损价, 止损原因)
     */
    fun checkTrailingStop(
        currentPrice: Double,
        highestSinceBuy: Double,
        atr: Double,
        period: HoldingPeriod,
        previousStopPrice: Double = 0.0
    ): Triple<Boolean, Double, String> {
        if (atr <= 0) return Triple(false, 0.0, "")

        val rawStop = calculateStopPrice(highestSinceBuy, atr, period)

        // 棘轮机制：止损线只上移不下移
        val effectiveStop = if (previousStopPrice > 0) max(rawStop, previousStopPrice) else rawStop

        val triggered = currentPrice < effectiveStop
        val reason = if (triggered) {
            val multiplier = getMultiplier(period)
            "ATR追踪止损: 当前${"%.2f".format(currentPrice)} < 止损线${"%.2f".format(effectiveStop)} " +
                "(最高${"%.2f".format(highestSinceBuy)} - ${multiplier}×ATR${"%.2f".format(atr)})"
        } else ""

        return Triple(triggered, effectiveStop, reason)
    }

    /**
     * 从 DB 计算某股票的 ATR 并判断止损
     *
     * 便捷方法：一次性完成 DB 查询 + ATR 计算 + 止损判断
     *
     * @param db StockDatabase 实例
     * @param stockCode 股票代码
     * @param currentPrice 当前价格
     * @param highestSinceBuy 买入后最高价
     * @param period 持仓周期
     * @param previousStopPrice 上次止损价
     * @return Triple(是否触发, 新止损价, 原因)
     */
    suspend fun checkFromDb(
        db: StockDatabase,
        stockCode: String,
        currentPrice: Double,
        highestSinceBuy: Double,
        period: HoldingPeriod,
        previousStopPrice: Double = 0.0
    ): Triple<Boolean, Double, String> {
        return try {
            val history = db.dailySnapshotDao().getByCode(stockCode, 20)
            if (history.size < 15) return Triple(false, 0.0, "")

            val sorted = history.sortedBy { it.date }
            val highs = sorted.map { it.high }
            val lows = sorted.map { it.low }
            val closes = sorted.map { it.close }

            val atr = calculateATR(highs, lows, closes, 14)
            if (atr <= 0) return Triple(false, 0.0, "")

            checkTrailingStop(currentPrice, highestSinceBuy, atr, period, previousStopPrice)
        } catch (_: Exception) {
            Triple(false, 0.0, "")
        }
    }

    private fun getMultiplier(period: HoldingPeriod): Double = when (period) {
        HoldingPeriod.ULTRA_SHORT -> 1.5
        HoldingPeriod.SHORT -> 2.0
        HoldingPeriod.MID -> 2.5
        HoldingPeriod.LONG -> 3.0
    }
}
