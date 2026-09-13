package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlin.math.abs

/**
 * ## 个股分析共用工具函数库
 *
 * 抽取自 CommonAnalysisNodes / StockCheckPipeline / PipelineBacktestEngine /
 * BounceReversalNode / BasePositionAnalyzer / AncestralRulesNode / TTradePipelineNodes
 * 中的重复逻辑，统一管理。
 */

// ═══════════════════════════════════════════════════
//  均线计算
// ═══════════════════════════════════════════════════

/**
 * 计算 MA5/MA10/MA20 及多头排列粘合判断
 *
 * @param closes 按日期升序的收盘价列表
 * @param threshold 离散率阈值（如 0.03 = 3%）
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
 * 检查「三日不新低」的三种语义
 */
object StabilityChecker {
    enum class Mode {
        /** 递增序：lows[0] ≤ lows[1] ≤ lows[2]（StrictSelection / StockCheckPipeline） */
        ASCENDING,
        /** vs 第4日：近3日 low 均 >= 第4日 low（BounceReversalNode） */
        ABOVE_FOURTH_DAY,
        /** vs 前段最低：近3日 low 均 >= 之前所有日最低（BasePositionAnalyzer） */
        ABOVE_PRIOR_MIN
    }

    /**
     * @param snaps 按日期升序排列的日线数据
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

    /** 从已有的 low 列表检查（降序，供 BounceReversalNode 用） */
    fun checkDescendingLows(lows: List<Double>): Boolean {
        if (lows.size < 4) return false
        val baseline = lows[3]
        return lows.take(3).all { it >= baseline }
    }
}

// ═══════════════════════════════════════════════════
//  价格区间位置
// ═══════════════════════════════════════════════════

/**
 * 计算当前价在 N 日区间的位置（0.0=最低, 1.0=最高）
 */
object PricePositionAnalyzer {

    /** 用收盘价计算区间位置 */
    fun fromCloses(closes: List<Double>, currentPrice: Double): Double {
        if (closes.isEmpty()) return 0.5
        val high = closes.maxOrNull() ?: currentPrice
        val low = closes.minOrNull() ?: currentPrice
        val range = high - low
        return if (range > 0) (currentPrice - low) / range else 0.5
    }

    /** 用 high/low 计算区间位置（AncestralRulesNode 用） */
    fun fromHighLow(snaps: List<DailySnapshotEntity>, currentPrice: Double): Double {
        if (snaps.isEmpty()) return 0.5
        val high = snaps.maxOf { it.high }
        val low = snaps.minOf { it.low }
        val range = high - low
        return if (range > 0) (currentPrice - low) / range else 0.5
    }
}

// ═══════════════════════════════════════════════════
//  冰点买入
// ═══════════════════════════════════════════════════

/**
 * 冰点买入检查：低换手率 + 低量比
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
     * @param snaps 按日期升序的日线数据（用于计算量比）
     * @param volumeDays 量比基准天数（默认5日）
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
//  RSI 计算
// ═══════════════════════════════════════════════════

/**
 * 标准 Wilder RSI（简单平均版），统一 4 处重复实现
 */
object RsiCalculator {

    /** 从收盘价列表计算 RSI */
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

    /** 从 DailySnapshotEntity 列表计算 RSI */
    fun fromSnaps(snaps: List<DailySnapshotEntity>, period: Int = 14): Double {
        return compute(snaps.map { it.close }, period)
    }
}

// ═══════════════════════════════════════════════════
//  市场微结构（震荡收割 + 量价背离 + 跳空风险）
// ═══════════════════════════════════════════════════

/**
 * 市场微结构分析：震荡收割、量价背离、跳空风险
 *
 * 原 MaConvergenceNode 和 MarketMaUnifiedNode 中完全相同的代码块
 */
object MarketMicrostructureAnalyzer {

    data class Result(
        val oscillationHarvest: Boolean,
        val oscillationCount: Int,
        val volumeDivergence: Boolean,
        val gapRisk: Boolean
    )

    /**
     * @param snaps 按日期升序排列的日线数据（至少 6 条）
     * @param days 分析天数（默认5）
     * @param minCount 震荡收割最低次数（默认3）
     * @param amplitudeThreshold 振幅阈值（默认0.02=2%）
     */
    fun analyze(
        snaps: List<DailySnapshotEntity>,
        days: Int = 5,
        minCount: Int = 3,
        amplitudeThreshold: Double = 0.02
    ): Result {
        if (snaps.size < days + 1) return Result(false, 0, false, false)
        val sorted = snaps.sortedBy { it.date }

        // 震荡收割：高开低走 + 振幅 > 2%
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

        // 量价背离：下跌日成交量 > 上涨日成交量 × 1.3
        val lastN = sorted.takeLast(days)
        val upDays = lastN.filter { it.changePct > 0 }
        val downDays = lastN.filter { it.changePct < 0 }
        val avgUpVol = if (upDays.isNotEmpty()) upDays.map { it.volume.toDouble() }.average() else 0.0
        val avgDownVol = if (downDays.isNotEmpty()) downDays.map { it.volume.toDouble() }.average() else 0.0
        val volumeDivergence = avgUpVol > 0 && avgDownVol > avgUpVol * 1.3

        // 跳空风险
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
//  防守板块评分
// ═══════════════════════════════════════════════════

/**
 * 防守板块评分（PB + 板块 + 市值 + ROE）
 *
 * 供 DefensiveDividendNode 和 MidTermPipelineNodes 熊市打底仓共用
 */
object DefensiveScoring {

    /** 默认防守板块列表 */
    val DEFAULT_SECTORS = setOf(
        "银行", "保险", "电力", "高速公路", "煤炭", "石油",
        "电信", "水务", "燃气", "铁路", "港口", "机场", "证券"
    )

    /**
     * 计算防守评分（满分 100）
     *
     * @param pb 市净率
     * @param marketCap 市值（元）
     * @param roe ROE（%）
     * @param isDefensiveSector 是否属防守板块
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

    /** 判断板块名称是否属防守板块 */
    fun isDefensiveSector(sectorName: String?): Boolean {
        if (sectorName.isNullOrBlank()) return false
        return DEFAULT_SECTORS.any { sectorName.contains(it) }
    }
}
