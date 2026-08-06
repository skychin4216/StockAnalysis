package com.chin.stockanalysis.strategy.data

import com.chin.stockanalysis.strategy.backtest.IntradayKlineEntity
import kotlin.math.abs

/**
 * ## 盤中 K 線分析器
 *
 * 從 5 分鐘 K 線計算盤中指標，供 Pipeline 在交易時段使用。
 *
 * 指標包含：
 * - VWAP（成交量加權平均價）
 * - 盤中均線（MA5/MA10/MA20 根數）
 * - 量能變化（後半段 vs 前半段成交量比）
 * - 價格位置（當前價在日內高低範圍的位置）
 * - 盤中趨勢方向（上升/下降/震盪）
 */
object IntradayAnalyzer {

    data class IntradayIndicators(
        val code: String,
        val barCount: Int,              // K 線根數
        val vwap: Double,               // 成交量加權平均價
        val currentPrice: Double,       // 最新價
        val dayHigh: Double,            // 日內最高
        val dayLow: Double,             // 日內最低
        val pricePosition: Double,      // 價格位置 (0=最低, 1=最高)
        val aboveVwap: Boolean,         // 當前價在 VWAP 之上
        val ma5: Double,                // 5 根均線（25 分鐘）
        val ma10: Double,               // 10 根均線（50 分鐘）
        val ma20: Double,               // 20 根均線（100 分鐘）
        val maBullish: Boolean,         // MA5 > MA10 > MA20
        val volumeSurge: Boolean,       // 最近 5 根量能 > 平均 2 倍
        val volumeRatio: Double,        // 後半/前半 成交量比
        val trendDirection: TrendDir,   // 盤中趨勢
        val intradayReturn: Double      // 盤中漲跌幅 %（相對開盤價）
    )

    enum class TrendDir { UP, DOWN, SIDEWAYS }

    /**
     * 從分鐘 K 線計算盤中指標。
     *
     * @param bars 按時間升序的分鐘 K 線列表
     * @return 指標結果，數據不足時返回 null
     */
    fun analyze(bars: List<IntradayKlineEntity>): IntradayIndicators? {
        if (bars.size < 5) return null  // 至少 5 根才有基本意義

        val code = bars.first().code
        val closes = bars.map { it.close }
        val volumes = bars.map { it.volume.toDouble() }
        val highs = bars.map { it.high }
        val lows = bars.map { it.low }

        // VWAP = Σ(close × volume) / Σ(volume)
        val totalAmount = bars.sumOf { it.close * it.volume }
        val totalVolume = volumes.sum()
        val vwap = if (totalVolume > 0) totalAmount / totalVolume else closes.last()

        val currentPrice = closes.last()
        val dayHigh = highs.maxOrNull() ?: currentPrice
        val dayLow = lows.minOrNull() ?: currentPrice
        val range = dayHigh - dayLow
        val pricePosition = if (range > 0) (currentPrice - dayLow) / range else 0.5

        // 均線
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(minOf(10, closes.size)).average()
        val ma20 = closes.takeLast(minOf(20, closes.size)).average()
        val maBullish = ma5 > ma10 && ma10 > ma20

        // 量能分析
        val avgVol = if (volumes.isNotEmpty()) volumes.average() else 0.0
        val recentVol = volumes.takeLast(5).average()
        val volumeSurge = avgVol > 0 && recentVol > avgVol * 2.0

        // 前半/後半成交量比
        val mid = volumes.size / 2
        val firstHalfVol = volumes.take(mid).sum()
        val secondHalfVol = volumes.drop(mid).sum()
        val volumeRatio = if (firstHalfVol > 0) secondHalfVol / firstHalfVol else 1.0

        // 趨勢方向：用線性回歸斜率判斷
        val trend = if (closes.size >= 10) {
            val n = closes.size
            val xMean = (n - 1) / 2.0
            val yMean = closes.average()
            var num = 0.0
            var den = 0.0
            for (i in closes.indices) {
                num += (i - xMean) * (closes[i] - yMean)
                den += (i - xMean) * (i - xMean)
            }
            val slope = if (den > 0) num / den else 0.0
            val slopePct = slope / yMean * 100
            when {
                slopePct > 0.02 -> TrendDir.UP
                slopePct < -0.02 -> TrendDir.DOWN
                else -> TrendDir.SIDEWAYS
            }
        } else TrendDir.SIDEWAYS

        // 盤中漲跌幅
        val openPrice = bars.first().open
        val intradayReturn = if (openPrice > 0) (currentPrice - openPrice) / openPrice * 100 else 0.0

        return IntradayIndicators(
            code = code,
            barCount = bars.size,
            vwap = vwap,
            currentPrice = currentPrice,
            dayHigh = dayHigh,
            dayLow = dayLow,
            pricePosition = pricePosition,
            aboveVwap = currentPrice > vwap,
            ma5 = ma5,
            ma10 = ma10,
            ma20 = ma20,
            maBullish = maBullish,
            volumeSurge = volumeSurge,
            volumeRatio = volumeRatio,
            trendDirection = trend,
            intradayReturn = intradayReturn
        )
    }

    /**
     * 格式化指標為可讀字串，供日誌或報告使用。
     */
    fun formatSummary(ind: IntradayIndicators): String = buildString {
        append("📊 盤中指標(${ind.code}, ${ind.barCount}根)")
        append("\n  價格: ${"%.2f".format(ind.currentPrice)} (日內${"%.1f".format(ind.pricePosition * 100)}%位)")
        append("\n  VWAP: ${"%.2f".format(ind.vwap)} ${if (ind.aboveVwap) "↑在上方" else "↓在下方"}")
        append("\n  MA: ${"%.2f".format(ind.ma5)}/${"%.2f".format(ind.ma10)}/${"%.2f".format(ind.ma20)} ${if (ind.maBullish) "多頭排列" else "非多頭"}")
        append("\n  量能: ${if (ind.volumeSurge) "⚡放量" else "平"} 前後比${"%.2f".format(ind.volumeRatio)}")
        append("\n  趨勢: ${ind.trendDirection} 盤中漲跌${"%.2f".format(ind.intradayReturn)}%")
    }
}
