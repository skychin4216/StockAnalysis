package com.chin.stockanalysis.strategy.risk

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.HoldingPeriod
import kotlin.math.max

/**
 * ## ATR 動態追蹤止損
 *
 * 不是選股策略，是持倉管理工具，供 AutoSellEngine / Fragment 調用。
 *
 * 邏輯：
 * - ATR(14) = 14 日 True Range 均值
 * - 追蹤止損線 = 持倉期間最高價 - N × ATR
 * - 止損線只上移不下移（棘輪機制）
 * - 觸發條件：當前價 < 止損線 → 賣出
 *
 * 不同週期 N 值：
 * - ULTRA_SHORT: 1.5（緊貼，快速止損）
 * - SHORT: 2.0
 * - MID: 2.5
 * - LONG: 3.0（寬容，避免被洗出）
 */
object ATRTrailingStop {

    /**
     * 計算 ATR(14)
     *
     * @param highs 近 N 天最高價（升序，最後一個是今天）
     * @param lows 近 N 天最低價
     * @param closes 近 N 天收盤價
     * @param period ATR 周期（默認 14）
     * @return ATR 值，數據不足時返回 0
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

        // 取最近 period 個 TR 的均值
        return trueRanges.takeLast(period).average()
    }

    /**
     * 計算追蹤止損價
     *
     * @param highestSinceBuy 買入後的最高價
     * @param atr 當前 ATR 值
     * @param period 持倉週期（決定 N 倍數）
     * @return 止損價格
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
     * 判斷是否觸發止損
     *
     * @param currentPrice 當前價格
     * @param highestSinceBuy 買入後最高價
     * @param atr 當前 ATR
     * @param period 持倉週期
     * @param previousStopPrice 上一次計算的止損價（用於棘輪：只上移不下移）
     * @return Triple(是否觸發, 新止損價, 止損原因)
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

        // 棘輪機制：止損線只上移不下移
        val effectiveStop = if (previousStopPrice > 0) max(rawStop, previousStopPrice) else rawStop

        val triggered = currentPrice < effectiveStop
        val reason = if (triggered) {
            val multiplier = getMultiplier(period)
            "ATR追蹤止損: 當前${"%.2f".format(currentPrice)} < 止損線${"%.2f".format(effectiveStop)} " +
                "(最高${"%.2f".format(highestSinceBuy)} - ${multiplier}×ATR${"%.2f".format(atr)})"
        } else ""

        return Triple(triggered, effectiveStop, reason)
    }

    /**
     * 從 DB 計算某股票的 ATR 並判斷止損
     *
     * 便捷方法：一次性完成 DB 查詢 + ATR 計算 + 止損判斷
     *
     * @param db StockDatabase 實例
     * @param stockCode 股票代碼
     * @param currentPrice 當前價格
     * @param highestSinceBuy 買入後最高價
     * @param period 持倉週期
     * @param previousStopPrice 上次止損價
     * @return Triple(是否觸發, 新止損價, 原因)
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
