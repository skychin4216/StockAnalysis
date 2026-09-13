package com.chin.stockanalysis.strategy.data

import com.chin.stockanalysis.strategy.backtest.IntradayKlineEntity
import kotlin.math.abs

/**
 * ## 盘中 K 线分析器
 *
 * 从 5 分钟 K 线计算盘中指标，供 Pipeline 在交易时段使用。
 *
 * 指标包含：
 * - VWAP（成交量加权平均价）
 * - 盘中均线（MA5/MA10/MA20 根数）
 * - 量能变化（后半段 vs 前半段成交量比）
 * - 价格位置（当前价在日内高低范围的位置）
 * - 盘中趋势方向（上升/下降/震荡）
 */
object IntradayAnalyzer {

    data class IntradayIndicators(
        val code: String,
        val barCount: Int,              // K 线根数
        val vwap: Double,               // 成交量加权平均价
        val currentPrice: Double,       // 最新价
        val dayHigh: Double,            // 日内最高
        val dayLow: Double,             // 日内最低
        val pricePosition: Double,      // 价格位置 (0=最低, 1=最高)
        val aboveVwap: Boolean,         // 当前价在 VWAP 之上
        val ma5: Double,                // 5 根均线（25 分钟）
        val ma10: Double,               // 10 根均线（50 分钟）
        val ma20: Double,               // 20 根均线（100 分钟）
        val maBullish: Boolean,         // MA5 > MA10 > MA20
        val volumeSurge: Boolean,       // 最近 5 根量能 > 平均 2 倍
        val volumeRatio: Double,        // 后半/前半 成交量比
        val trendDirection: TrendDir,   // 盘中趋势
        val intradayReturn: Double      // 盘中涨跌幅 %（相对开盘价）
    )

    enum class TrendDir { UP, DOWN, SIDEWAYS }

    /**
     * 从分钟 K 线计算盘中指标。
     *
     * @param bars 按时间升序的分钟 K 线列表
     * @return 指标结果，数据不足时返回 null
     */
    fun analyze(bars: List<IntradayKlineEntity>): IntradayIndicators? {
        if (bars.size < 5) return null  // 至少 5 根才有基本意义

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

        // 均线
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(minOf(10, closes.size)).average()
        val ma20 = closes.takeLast(minOf(20, closes.size)).average()
        val maBullish = ma5 > ma10 && ma10 > ma20

        // 量能分析
        val avgVol = if (volumes.isNotEmpty()) volumes.average() else 0.0
        val recentVol = volumes.takeLast(5).average()
        val volumeSurge = avgVol > 0 && recentVol > avgVol * 2.0

        // 前半/后半成交量比
        val mid = volumes.size / 2
        val firstHalfVol = volumes.take(mid).sum()
        val secondHalfVol = volumes.drop(mid).sum()
        val volumeRatio = if (firstHalfVol > 0) secondHalfVol / firstHalfVol else 1.0

        // 趋势方向：用线性回归斜率判断
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

        // 盘中涨跌幅
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
     * 格式化指标为可读字串，供日志或报告使用。
     */
    fun formatSummary(ind: IntradayIndicators): String = buildString {
        append("📊 盘中指标(${ind.code}, ${ind.barCount}根)")
        append("\n  价格: ${"%.2f".format(ind.currentPrice)} (日内${"%.1f".format(ind.pricePosition * 100)}%位)")
        append("\n  VWAP: ${"%.2f".format(ind.vwap)} ${if (ind.aboveVwap) "↑在上方" else "↓在下方"}")
        append("\n  MA: ${"%.2f".format(ind.ma5)}/${"%.2f".format(ind.ma10)}/${"%.2f".format(ind.ma20)} ${if (ind.maBullish) "多头排列" else "非多头"}")
        append("\n  量能: ${if (ind.volumeSurge) "⚡放量" else "平"} 前后比${"%.2f".format(ind.volumeRatio)}")
        append("\n  趋势: ${ind.trendDirection} 盘中涨跌${"%.2f".format(ind.intradayReturn)}%")
    }
}
