package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlin.math.abs

/**
 * ## 個股分析共用工具函數庫
 *
 * 抽取自 CommonAnalysisNodes / StockCheckPipeline / PipelineBacktestEngine /
 * BounceReversalNode / BasePositionAnalyzer / AncestralRulesNode / TTradePipelineNodes
 * 中的重複邏輯，統一管理。
 */

// ═══════════════════════════════════════════════════
//  均線計算
// ═══════════════════════════════════════════════════

/**
 * 計算 MA5/MA10/MA20 及多頭排列粘合判斷
 *
 * @param closes 按日期升序的收盤價列表
 * @param threshold 離散率閾值（如 0.03 = 3%）
 * @return (ma5, ma10, ma20, divergence, isBullishConverged)
 */
fun calcMaBullishConvergence(
    closes: List<Double>,
    threshold: Double = 0.03
): Pair<Triple<Double, Double, Double>, Boolean> {
    val ma5 = closes.takeLast(5).average()
    val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else ma5
    val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else ma5
    val divergence = if (ma20 > 0) (ma5 - ma20) / ma20 else 1.0
    val converged = ma5 > ma10 && ma10 > ma20 && abs(divergence) < threshold
    return Triple(ma5, ma10, ma20) to converged
}

// ═══════════════════════════════════════════════════
//  三日不新低
// ═══════════════════════════════════════════════════

/**
 * 檢查「三日不新低」的三種語義
 */
object StabilityChecker {
    enum class Mode {
        /** 遞增序：lows[0] ≤ lows[1] ≤ lows[2]（StrictSelection / StockCheckPipeline） */
        ASCENDING,
        /** vs 第4日：近3日 low 均 >= 第4日 low（BounceReversalNode） */
        ABOVE_FOURTH_DAY,
        /** vs 前段最低：近3日 low 均 >= 之前所有日最低（BasePositionAnalyzer） */
        ABOVE_PRIOR_MIN
    }

    /**
     * @param snaps 按日期升序排列的日線數據
     */
    fun check(snaps: List<DailySnapshotEntity>, mode: Mode = Mode.ASCENDING): Boolean {
        if (snaps.size < 4) return false
        val lows = snaps.map { it.low }
        return when (mode) {
            Mode.ASCENDING -> {
                val r3 = lows.takeLast(3)
                r3[0] <= r3[1] && r3[1] <= r3[2]
            }
            Mode.ABOVE_FOURTH_DAY -> {
                val baseline = lows[lows.size - 4]
                lows.takeLast(3).all { it >= baseline }
            }
            Mode.ABOVE_PRIOR_MIN -> {
                val recent3 = snaps.takeLast(3)
                val prev = snaps.dropLast(3)
                if (prev.isEmpty()) false
                else recent3.all { it.low > prev.minOf { s -> s.low } }
            }
        }
    }

    /** 從已有的 low 列表檢查（降序，供 BounceReversalNode 用） */
    fun checkDescendingLows(lows: List<Double>): Boolean {
        if (lows.size < 4) return false
        val baseline = lows[3]
        return lows.take(3).all { it >= baseline }
    }
}

// ═══════════════════════════════════════════════════
//  價格區間位置
// ═══════════════════════════════════════════════════

/**
 * 計算當前價在 N 日區間的位置（0.0=最低, 1.0=最高）
 */
object PricePositionAnalyzer {

    /** 用收盤價計算區間位置 */
    fun fromCloses(closes: List<Double>, currentPrice: Double): Double {
        if (closes.isEmpty()) return 0.5
        val high = closes.maxOrNull() ?: currentPrice
        val low = closes.minOrNull() ?: currentPrice
        val range = high - low
        return if (range > 0) (currentPrice - low) / range else 0.5
    }

    /** 用 high/low 計算區間位置（AncestralRulesNode 用） */
    fun fromHighLow(snaps: List<DailySnapshotEntity>, currentPrice: Double): Double {
        if (snaps.isEmpty()) return 0.5
        val high = snaps.maxOf { it.high }
        val low = snaps.minOf { it.low }
        val range = high - low
        return if (range > 0) (currentPrice - low) / range else 0.5
    }
}

// ═══════════════════════════════════════════════════
//  冰點買入
// ═══════════════════════════════════════════════════

/**
 * 冰點買入檢查：低換手率 + 低量比
 */
object FreezingPointChecker {

    data class Result(
        val turnoverOk: Boolean,
        val volumeRatio: Double,
        val volumeRatioOk: Boolean,
        val isFreezing: Boolean
    )

    /**
     * @param latest 最新一日快照
     * @param snaps 按日期升序的日線數據（用於計算量比）
     * @param volumeDays 量比基准天數（默認5日）
     */
    fun check(
        latest: DailySnapshotEntity,
        snaps: List<DailySnapshotEntity>,
        turnoverThreshold: Double = 2.0,
        volumeRatioThreshold: Double = 1.0,
        volumeDays: Int = 5
    ): Result {
        val turnoverOk = latest.turnoverRate < turnoverThreshold && latest.turnoverRate > 0
        val totalDays = volumeDays + 1
        val avgVol = if (snaps.size >= totalDays) {
            snaps.takeLast(totalDays).dropLast(1).map { it.volume.toDouble() }.average()
        } else latest.volume.toDouble()
        val volRatio = if (avgVol > 0) latest.volume / avgVol else 1.0
        return Result(
            turnoverOk = turnoverOk,
            volumeRatio = volRatio,
            volumeRatioOk = volRatio < volumeRatioThreshold,
            isFreezing = turnoverOk && volRatio < volumeRatioThreshold
        )
    }
}

// ═══════════════════════════════════════════════════
//  RSI 計算
// ═══════════════════════════════════════════════════

/**
 * 標準 Wilder RSI（簡單平均版），統一 4 處重複實現
 */
object RsiCalculator {

    /** 從收盤價列表計算 RSI */
    fun compute(closes: List<Double>, period: Int = 14): Double {
        if (closes.size < period + 1) return 50.0
        val recent = closes.takeLast(period + 1)
        var gain = 0.0
        var loss = 0.0
        for (i in 1 until recent.size) {
            val diff = recent[i] - recent[i - 1]
            if (diff > 0) gain += diff else loss += -diff
        }
        val avgGain = gain / period
        val avgLoss = loss / period
        if (avgGain + avgLoss == 0.0) return 50.0
        val rs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    /** 從 DailySnapshotEntity 列表計算 RSI */
    fun fromSnaps(snaps: List<DailySnapshotEntity>, period: Int = 14): Double {
        return compute(snaps.map { it.close }, period)
    }
}

// ═══════════════════════════════════════════════════
//  市場微結構（震盪收割 + 量價背離 + 跳空風險）
// ═══════════════════════════════════════════════════

/**
 * 市場微結構分析：震盪收割、量價背離、跳空風險
 *
 * 原 MaConvergenceNode 和 MarketMaUnifiedNode 中完全相同的代碼塊
 */
object MarketMicrostructureAnalyzer {

    data class Result(
        val oscillationHarvest: Boolean,
        val oscillationCount: Int,
        val volumeDivergence: Boolean,
        val gapRisk: Boolean
    )

    /**
     * @param snaps 按日期升序排列的日線數據（至少 6 條）
     * @param days 分析天數（默認5）
     * @param minCount 震盪收割最低次數（默認3）
     * @param amplitudeThreshold 振幅閾值（默認0.02=2%）
     */
    fun analyze(
        snaps: List<DailySnapshotEntity>,
        days: Int = 5,
        minCount: Int = 3,
        amplitudeThreshold: Double = 0.02
    ): Result {
        if (snaps.size < days + 1) return Result(false, 0, false, false)
        val sorted = snaps.sortedBy { it.date }

        // 震盪收割：高開低走 + 振幅 > 2%
        val recent = sorted.takeLast(days + 1)
        var oscillationCount = 0
        for (i in 1 until recent.size) {
            val prev = recent[i - 1]
            val cur = recent[i]
            val gapUp = cur.open > prev.close
            val fadeDown = cur.close < cur.open
            val amplitude = if (cur.close > 0) (cur.high - cur.low) / cur.close else 0.0
            if (gapUp && fadeDown && amplitude > amplitudeThreshold) oscillationCount++
        }

        // 量價背離：下跌日成交量 > 上漲日成交量 × 1.3
        val lastN = sorted.takeLast(days)
        val upDays = lastN.filter { it.changePct > 0 }
        val downDays = lastN.filter { it.changePct < 0 }
        val avgUpVol = if (upDays.isNotEmpty()) upDays.map { it.volume.toDouble() }.average() else 0.0
        val avgDownVol = if (downDays.isNotEmpty()) downDays.map { it.volume.toDouble() }.average() else 0.0
        val volumeDivergence = avgUpVol > 0 && avgDownVol > avgUpVol * 1.3

        // 跳空風險
        val gapRisk = sorted.takeLast(3).any {
            it.close > 0 && abs(it.open - it.close) / it.close > 0.02
        }

        return Result(
            oscillationHarvest = oscillationCount >= minCount,
            oscillationCount = oscillationCount,
            volumeDivergence = volumeDivergence,
            gapRisk = gapRisk
        )
    }
}

// ═══════════════════════════════════════════════════
//  防守板塊評分
// ═══════════════════════════════════════════════════

/**
 * 防守板塊評分（PB + 板塊 + 市值 + ROE）
 *
 * 供 DefensiveDividendNode 和 MidTermPipelineNodes 熊市打底倉共用
 */
object DefensiveScoring {

    /** 默認防守板塊列表 */
    val DEFAULT_SECTORS = setOf(
        "銀行", "保險", "電力", "高速公路", "煤炭", "石油",
        "電信", "水務", "燃氣", "鐵路", "港口", "機場", "證券"
    )

    /**
     * 計算防守評分（滿分 100）
     *
     * @param pb 市淨率
     * @param marketCap 市值（元）
     * @param roe ROE（%）
     * @param isDefensiveSector 是否屬防守板塊
     */
    fun score(
        pb: Double,
        marketCap: Double,
        roe: Double,
        isDefensiveSector: Boolean
    ): Double {
        val pbScore = when {
            pb > 0 && pb < 1.0 -> 40.0
            pb > 0 && pb < 1.5 -> 30.0
            else -> 0.0
        }
        val sectorScore = if (isDefensiveSector) 25.0 else 0.0
        val capScore = if (marketCap > 500_0000_0000.0) 20.0 else 0.0
        val roeScore = if (roe > 10.0) 15.0 else 0.0
        return pbScore + sectorScore + capScore + roeScore
    }

    /** 判斷板塊名稱是否屬防守板塊 */
    fun isDefensiveSector(sectorName: String?): Boolean {
        if (sectorName.isNullOrBlank()) return false
        return DEFAULT_SECTORS.any { sectorName.contains(it) }
    }
}
