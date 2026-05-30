package com.chin.stockanalysis.strategy.backtest

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ## 技术指标计算工具
 *
 * 纯数学计算，无依赖，可用于策略打分。
 *
 * ### 指标列表
 * - RSI(14) — 相对强弱指标
 * - Bollinger(20,2) — 布林带
 * - MACD(12,26,9) — 指数平滑异同移动平均线
 * - KDJ(9,3,3) — 随机指标
 * - VolumeRatio(10) — 10日量比
 * - EMA — 指数移动平均
 */
object MathIndicators {

    /**
     * RSI 相对强弱指标
     * @param closes 收盘价序列（最近的在末尾）
     * @param period 周期，默认14
     * @return RSI值 (0-100)
     */
    fun rsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size < period + 1) return 50.0
        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()
        for (i in closes.size - period until closes.size) {
            val diff = closes[i] - closes[i - 1]
            if (diff > 0) { gains.add(diff); losses.add(0.0) }
            else { gains.add(0.0); losses.add(-diff) }
        }
        val avgGain = gains.average()
        val avgLoss = losses.average()
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /**
     * 布林带
     * @param closes 收盘价序列
     * @return Triple(上轨, 中轨(MA20), 下轨)
     */
    fun bollinger(closes: List<Double>, period: Int = 20, multiplier: Double = 2.0): Triple<Double, Double, Double> {
        if (closes.size < period) return Triple(0.0, 0.0, 0.0)
        val recent = closes.takeLast(period)
        val ma = recent.average()
        val variance = recent.map { (it - ma) * (it - ma) }.average()
        val stdDev = sqrt(variance)
        return Triple(ma + multiplier * stdDev, ma, ma - multiplier * stdDev)
    }

    /**
     * MACD
     * @return Triple(DIF, DEA, MACD柱)
     */
    fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): Triple<Double, Double, Double> {
        if (closes.size < slow + signal) return Triple(0.0, 0.0, 0.0)
        val emaFast = ema(closes, fast)
        val emaSlow = ema(closes, slow)
        val dif = emaFast - emaSlow

        // 计算 DEA: dif 的 signal 周期 EMA
        val difList = mutableListOf<Double>()
        for (i in slow until closes.size) {
            val ef = emaSingle(closes.subList(0, i + 1), fast)
            val es = emaSingle(closes.subList(0, i + 1), slow)
            difList.add(ef - es)
        }
        val dea = if (difList.size >= signal) emaSingle(difList, signal) else 0.0
        val bar = (dif - dea) * 2

        return Triple(dif, dea, bar)
    }

    /**
     * KDJ 随机指标
     * @return Triple(K, D, J)
     */
    fun kdj(highs: List<Double>, lows: List<Double>, closes: List<Double>, n: Int = 9): Triple<Double, Double, Double> {
        if (closes.size < n) return Triple(50.0, 50.0, 50.0)
        val recentHigh = highs.takeLast(n).maxOrNull() ?: 0.0
        val recentLow = lows.takeLast(n).minOrNull() ?: 0.0
        val rsv = if (recentHigh != recentLow) {
            (closes.last() - recentLow) / (recentHigh - recentLow) * 100
        } else 50.0
        // 简化：直接用RSV作为K
        val k = rsv
        val d = rsv  // 简化
        val j = 3 * k - 2 * d
        return Triple(k.coerceIn(0.0, 100.0), d.coerceIn(0.0, 100.0), j.coerceIn(0.0, 100.0))
    }

    /**
     * 量比 = 当日成交量 / 近N日均量
     */
    fun volumeRatio(volumes: List<Long>, period: Int = 10): Double {
        if (volumes.size < period) return 1.0
        val recent = volumes.takeLast(period)
        val todayVol = recent.last().toDouble()
        val avgVol = (recent.dropLast(1).sumOf { it.toDouble() }) / (period - 1)
        return if (avgVol > 0) todayVol / avgVol else 1.0
    }

    /**
     * 指数移动平均 (完整序列)
     */
    fun ema(closes: List<Double>, period: Int): Double = emaSingle(closes, period)

    /**
     * 单值 EMA 计算
     */
    fun emaSingle(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        if (values.size == 1) return values.first()
        val k = 2.0 / (period + 1)
        var ema = values.first()
        for (i in 1 until values.size) {
            ema = values[i] * k + ema * (1 - k)
        }
        return ema
    }

    /**
     * 简单移动平均
     */
    fun sma(values: List<Double>, period: Int): Double {
        if (values.size < period) return values.average()
        return values.takeLast(period).average()
    }

    /**
     * MA 趋势判断：多头排列 / 空头排列
     * 用5/10/20日简单移动平均
     */
    fun maTrend(closes: List<Double>): MaTrendResult {
        if (closes.size < 20) return MaTrendResult("数据不足", 0)
        val ma5 = sma(closes, 5)
        val ma10 = sma(closes, 10)
        val ma20 = sma(closes, 20)
        val score = when {
            ma5 > ma10 && ma10 > ma20 -> 35  // 三线多头排列
            ma5 > ma10 -> 20  // 5金10
            ma5 > ma20 && ma10 <= ma20 -> 25  // 5金20
            ma10 > ma20 -> 20  // 10金20
            else -> 0
        }
        val trend = when {
            ma5 > ma10 && ma10 > ma20 -> "三线多头"
            ma5 > ma20 -> "5金20"
            ma10 > ma20 -> "10金20"
            ma5 > ma10 -> "5金10"
            else -> "空头排列"
        }
        return MaTrendResult(trend, score)
    }

    data class MaTrendResult(val trend: String, val score: Int)
}