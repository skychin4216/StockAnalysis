package com.chin.stockanalysis.strategy.topology.pipelines

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity

/**
 * ## 个股分析 Pipeline — 均线多头粘合选股（四周期版）
 *
 * 参考「均线多头粘合选股」框架，以**粘合度**为核心指标，
 * 不同周期使用不同参数和检查条件组合：
 *
 * 粘合度 = (MAX(MAs) - MIN(MAs)) / MIN(MAs) × 100%
 *
 * | 周期   | 均线  | 粘合度 | 时长  | 量能       | 跌幅   | 特殊                   |
 * |--------|-------|--------|-------|-----------|--------|------------------------|
 * | 超短线 | 3线   | ≤3%   | ≥5天  | 爆量2.5x  | 非高位 | 涨幅>4%               |
 * | 短线   | 4线   | ≤3%   | ≥10天 | 放量1.5x  | ≥20%  | 收盘站上所有均线        |
 * | 中线   | 4线   | ≤2.5% | ≥15天 | 温和1.2-1.8x | ≥30% | MA60上翘+站稳均线     |
 * | 长线   | 4线   | ≤2%   | ≥20天 | 地量<50%  | ≥40%  | MA60+MA250上翘+站稳年线 |
 *
 * 检查项（各周期启用不同子集，passCount 只统计本周期要求的项）：
 * 1. 粘合度 ≤ 阈值
 * 2. 多头排列 MA5>MA10>MA20[>MA60]
 * 3. 粘合持续 ≥ N 天
 * 4. 量能条件（爆量/放量/温和/地量）
 * 5. 距高点跌幅 ≥ 阈值
 * 6. MA60 上升（仅 requireMA60Rising=true 时计入）
 * 7. 站稳年线（仅 requireAboveYearLine=true 时计入）
 * 8. 涨幅达标（仅 requireChangePct=true 时计入）
 * 9. 站上所有均线（仅 requireAboveAllMAs=true 时计入）
 */
class StockCheckPipeline(
    /** 粘合度阈值（百分比，如 3.0 = 3%） */
    val convergenceThreshold: Double = 3.0,
    /** 是否使用 MA60（四线 vs 三线） */
    val useMA60: Boolean = true,
    /** 粘合持续天数要求（在 lookback 窗口内） */
    val convergenceDurationDays: Int = 10,
    /** 放量倍数阈值（当日量 / 5日均量 ≥ 此值） */
    val volumeBreakoutRatio: Double = 1.5,
    /** 最低涨幅要求（%），需配合 requireChangePct=true */
    val minChangePct: Double = 0.0,
    /** 是否启用涨幅检查 */
    val requireChangePct: Boolean = false,
    /** 距高点最低跌幅要求（%），0 = 不要求 */
    val minDrawdownPct: Double = 0.0,
    /** 是否要求 MA60 上升 */
    val requireMA60Rising: Boolean = false,
    /** 是否要求地量（10日均量 < 60日均量 × volumeShrinkRatio） */
    val requireVolumeShrink: Boolean = false,
    /** 地量比例阈值 */
    val volumeShrinkRatio: Double = 0.5,
    /** 是否要求价格站稳年线（MA250）上方 */
    val requireAboveYearLine: Boolean = false,
    /** 是否要求收盘站上所有均线 */
    val requireAboveAllMAs: Boolean = false,
    /** 温和放量下限 */
    val moderateVolumeLower: Double = 0.0,
    /** 温和放量上限 */
    val moderateVolumeUpper: Double = 0.0,
    /** 回溯天数 */
    val lookbackDays: Int = 60,
    /** 通过所需最少项数（应等于 totalChecks） */
    val minPassCount: Int = 7
) {

    companion object {
        private const val TAG = "StockCheckPipeline"

        /**
         * 超短线：5/10/20 三线粘合 + 爆量突破 + 涨幅>4%
         * 不要求历史低位，排除高位股
         * 适用检查：①②③④⑤⑧ = 6 项
         */
        fun ultraShortParams() = StockCheckPipeline(
            convergenceThreshold = 3.0,
            useMA60 = false,
            convergenceDurationDays = 5,
            volumeBreakoutRatio = 2.5,
            minChangePct = 4.0,
            requireChangePct = true,
            minDrawdownPct = 10.0,
            lookbackDays = 30,
            minPassCount = 6
        )

        /**
         * 短线：5/10/20/60 四线粘合 + 放量突破 + 站上所有均线
         * 距高点跌幅 ≥ 20%
         * 适用检查：①②③④⑤⑨ = 6 项
         */
        fun shortTermParams() = StockCheckPipeline(
            convergenceThreshold = 3.0,
            useMA60 = true,
            convergenceDurationDays = 10,
            volumeBreakoutRatio = 1.5,
            minDrawdownPct = 20.0,
            requireAboveAllMAs = true,
            lookbackDays = 60,
            minPassCount = 6
        )

        /**
         * 中线：四线粘合 + MA60 上翘 + 温和放量 + 站上所有均线
         * 距高点跌幅 ≥ 30%
         * 适用检查：①②③④⑤⑥⑨ = 7 项
         */
        fun midTermParams() = StockCheckPipeline(
            convergenceThreshold = 2.5,
            useMA60 = true,
            convergenceDurationDays = 15,
            moderateVolumeLower = 1.2,
            moderateVolumeUpper = 1.8,
            minDrawdownPct = 30.0,
            requireMA60Rising = true,
            requireAboveAllMAs = true,
            lookbackDays = 120,
            minPassCount = 7
        )

        /**
         * 长线：极致粘合 + MA60/MA250 同步上翘 + 地量 + 站稳年线
         * 距高点跌幅 ≥ 40%
         * 适用检查：①②③④⑤⑥⑦ = 7 项
         */
        fun longTermParams() = StockCheckPipeline(
            convergenceThreshold = 2.0,
            useMA60 = true,
            convergenceDurationDays = 20,
            requireVolumeShrink = true,
            volumeShrinkRatio = 0.5,
            minDrawdownPct = 40.0,
            requireMA60Rising = true,
            requireAboveYearLine = true,
            lookbackDays = 250,
            minPassCount = 7
        )
    }

    /**
     * 个股分析结果（均线多头粘合框架）
     */
    data class StockCheckResult(
        val stockCode: String,
        val stockName: String,
        // ── 核心指标 ──
        /** 粘合度（百分比，如 1.5 = 1.5%） */
        val convergenceDegree: Double = 999.0,
        /** 粘合度是否达标 */
        val convergenceOk: Boolean = false,
        /** 多头排列 MA5>MA10>MA20[>MA60] */
        val bullishAligned: Boolean = false,
        /** 粘合持续天数 */
        val convergenceDays: Int = 0,
        /** 粘合持续天数是否达标 */
        val convergenceDurationOk: Boolean = false,
        /** 量能条件是否达标 */
        val volumeConditionOk: Boolean = false,
        /** 量比（当日量 / 5日均量） */
        val volumeRatio: Double = 0.0,
        /** 距高点跌幅（%） */
        val drawdownPct: Double = 0.0,
        /** 跌幅是否达标 */
        val drawdownOk: Boolean = false,
        /** MA60 是否上升（不要求时为 true） */
        val ma60Rising: Boolean = false,
        /** 价格是否在年线上方（不要求时为 true） */
        val aboveYearLine: Boolean = false,
        /** 涨幅是否达标（不要求时为 true） */
        val changePctOk: Boolean = true,
        /** 收盘是否站上所有均线 */
        val aboveAllMAs: Boolean = false,
        // ── 汇总 ──
        /** 通过项数 */
        val passCount: Int = 0,
        /** 本周期适用检查总数 */
        val totalChecks: Int = 7,
        /** 是否通过（passCount >= minPassCount） */
        val passed: Boolean = false,
        /** 当前价格 */
        val currentPrice: Double = 0.0,
        /** PE 值 */
        val pe: Double = 0.0,
        /** 换手率 */
        val turnoverRate: Double = 0.0,
        /** 文字摘要 */
        val summary: String = ""
    )

    suspend fun analyze(context: Context, stockCode: String): StockCheckResult {
        val db = StockDatabase.getInstance(context)
        return analyze(db, stockCode)
    }

    suspend fun analyze(db: StockDatabase, stockCode: String): StockCheckResult {
        return try {
            val snaps = db.dailySnapshotDao().getByCode(stockCode, lookbackDays + 10)
                .sortedBy { it.date }
            if (snaps.size < 20) {
                return StockCheckResult(stockCode, "数据不足", summary = "K线数据不足(${snaps.size}条)")
            }
            analyzeSnaps(snaps)
        } catch (e: Exception) {
            Log.e(TAG, "个股分析异常: $stockCode - ${e.message}", e)
            StockCheckResult(stockCode, "异常", summary = "分析异常: ${e.message}")
        }
    }

    /**
     * 用预载入的快照分析（不回查 DB，供 PipelineBacktestEngine 回溯用）
     */
    fun analyzeSnaps(
        snaps: List<DailySnapshotEntity>,
        marketMaResult: Any? = null  // 保留参数兼容
    ): StockCheckResult {
        if (snaps.size < 20) {
            return StockCheckResult("", "数据不足", summary = "K线数据不足(${snaps.size}条)")
        }

        val latest = snaps.last()
        val closes = snaps.map { it.close }
        val stockCode = latest.code
        val name = latest.name

        // ── MA 计算 ──
        val ma5 = closes.takeLast(5).average()
        val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else ma5
        val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else ma5
        val ma60 = if (closes.size >= 60) closes.takeLast(60).average() else null
        val ma250 = if (closes.size >= 250) closes.takeLast(250).average() else null

        val mas = mutableListOf(ma5, ma10, ma20)
        if (useMA60 && ma60 != null) mas.add(ma60)

        // ═══ 1. 粘合度 ═══
        val maMax = mas.max()
        val maMin = mas.min()
        val convergenceDegree = if (maMin > 0) (maMax - maMin) / maMin * 100 else 999.0
        val convergenceOk = convergenceDegree <= convergenceThreshold

        // ═══ 2. 多头排列 ═══
        val bullishAligned = if (useMA60 && ma60 != null) {
            ma5 > ma10 && ma10 > ma20 && ma20 > ma60
        } else {
            ma5 > ma10 && ma10 > ma20
        }

        // ═══ 3. 粘合持续天数 ═══
        // 使用宽松阈值（+0.5%）：允许临界附近微小波动，统计的是"近似粘合"天数
        val durationWindow = convergenceDurationDays.coerceAtLeast(5)
        val looseThreshold = convergenceThreshold + 0.5
        var convergenceDays = 0
        for (i in closes.size - durationWindow until closes.size) {
            if (i < 19) continue
            val window = closes.subList(0, i + 1)
            val wMa5 = window.takeLast(5).average()
            val wMa10 = if (window.size >= 10) window.takeLast(10).average() else wMa5
            val wMa20 = if (window.size >= 20) window.takeLast(20).average() else wMa5
            val wMas = mutableListOf(wMa5, wMa10, wMa20)
            if (useMA60 && window.size >= 60) {
                val wMa60 = window.takeLast(60).average()
                wMas.add(wMa60)
            }
            val wMax = wMas.max()
            val wMin = wMas.min()
            val wDeg = if (wMin > 0) (wMax - wMin) / wMin * 100 else 999.0
            if (wDeg <= looseThreshold) convergenceDays++
        }
        val convergenceDurationOk = convergenceDays >= minOf(convergenceDurationDays, durationWindow)

        // ═══ 4. 量能条件（三种模式互斥） ═══
        // 统一排除当日：基准量 = 前N日均量，与当日量对比
        val vol5Avg = if (snaps.size >= 6) {
            snaps.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
        } else latest.volume.toDouble()
        val volumeRatio = if (vol5Avg > 0) latest.volume / vol5Avg else 1.0

        val volumeConditionOk = when {
            // 地量模式（长线）：前10日均量 < 前60日均量 × ratio
            requireVolumeShrink -> {
                val vol10Avg = if (snaps.size >= 11) {
                    snaps.takeLast(11).dropLast(1).map { it.volume.toDouble() }.average()
                } else if (snaps.size >= 10) {
                    snaps.takeLast(10).map { it.volume.toDouble() }.average()
                } else latest.volume.toDouble()
                val vol60Avg = if (snaps.size >= 61) {
                    snaps.takeLast(61).dropLast(1).map { it.volume.toDouble() }.average()
                } else if (snaps.size >= 60) {
                    snaps.takeLast(60).map { it.volume.toDouble() }.average()
                } else vol10Avg
                vol60Avg > 0 && vol10Avg < vol60Avg * volumeShrinkRatio
            }
            // 温和放量模式（中线）：量比在 [lower, upper] 区间
            moderateVolumeLower > 0 && moderateVolumeUpper > 0 -> {
                volumeRatio >= moderateVolumeLower && volumeRatio <= moderateVolumeUpper
            }
            // 放量突破模式（超短线/短线）：量比 ≥ ratio
            else -> volumeRatio >= volumeBreakoutRatio
        }

        // ═══ 5. 距高点跌幅 ═══
        val lookbackHigh = snaps.takeLast(lookbackDays).maxOfOrNull { it.high } ?: latest.high
        val drawdownPct = if (lookbackHigh > 0) (lookbackHigh - latest.close) / lookbackHigh * 100 else 0.0
        val drawdownOk = minDrawdownPct <= 0 || drawdownPct >= minDrawdownPct

        // ═══ 6. MA60 上升（仅在要求时计算） ═══
        val ma60Rising = if (requireMA60Rising && ma60 != null && closes.size >= 65) {
            val ma60FiveDaysAgo = closes.subList(0, closes.size - 5).takeLast(60).average()
            ma60 > ma60FiveDaysAgo
        } else false

        // ═══ 7. 年线位置（仅在要求时计算） ═══
        val aboveYearLine = if (requireAboveYearLine && ma250 != null) {
            latest.close > ma250
        } else false

        // ═══ 8. 涨幅达标（仅在要求时计算） ═══
        val changePctOk = if (requireChangePct) {
            latest.changePct >= minChangePct
        } else true

        // ═══ 9. 收盘站上所有均线（仅在要求时计算） ═══
        val aboveAllMAs = if (requireAboveAllMAs) {
            if (useMA60 && ma60 != null) {
                latest.close > ma5 && latest.close > ma10 && latest.close > ma20 && latest.close > ma60
            } else {
                latest.close > ma5 && latest.close > ma10 && latest.close > ma20
            }
        } else true

        // ═══ 统计通过项数 — 只计本周期要求的检查 ═══
        var passCount = 0
        var totalChecks = 0

        // ① 粘合度（所有周期）
        totalChecks++; if (convergenceOk) passCount++
        // ② 多头排列（所有周期）
        totalChecks++; if (bullishAligned) passCount++
        // ③ 粘合持续（所有周期）
        totalChecks++; if (convergenceDurationOk) passCount++
        // ④ 量能条件（所有周期）
        totalChecks++; if (volumeConditionOk) passCount++
        // ⑤ 跌幅达标（所有周期，minDrawdownPct=0 时自动通过）
        totalChecks++; if (drawdownOk) passCount++
        // ⑥ MA60 上升（仅要求时计入）
        if (requireMA60Rising) { totalChecks++; if (ma60Rising) passCount++ }
        // ⑦ 站稳年线（仅要求时计入）
        if (requireAboveYearLine) { totalChecks++; if (aboveYearLine) passCount++ }
        // ⑧ 涨幅达标（仅要求时计入）
        if (requireChangePct) { totalChecks++; if (changePctOk) passCount++ }
        // ⑨ 站上所有均线（仅要求时计入）
        if (requireAboveAllMAs) { totalChecks++; if (aboveAllMAs) passCount++ }

        val passed = passCount >= minPassCount

        return StockCheckResult(
            stockCode = stockCode,
            stockName = name,
            convergenceDegree = convergenceDegree,
            convergenceOk = convergenceOk,
            bullishAligned = bullishAligned,
            convergenceDays = convergenceDays,
            convergenceDurationOk = convergenceDurationOk,
            volumeConditionOk = volumeConditionOk,
            volumeRatio = volumeRatio,
            drawdownPct = drawdownPct,
            drawdownOk = drawdownOk,
            ma60Rising = ma60Rising,
            aboveYearLine = aboveYearLine,
            changePctOk = changePctOk,
            aboveAllMAs = aboveAllMAs,
            passCount = passCount,
            totalChecks = totalChecks,
            passed = passed,
            currentPrice = latest.close,
            pe = latest.pe,
            turnoverRate = latest.turnoverRate,
            summary = "$name(${stockCode.takeLast(4)}) 粘合${"%.1f".format(convergenceDegree)}% 通过:$passCount/$totalChecks"
        )
    }

    suspend fun analyzeBatch(context: Context, stockCodes: List<String>): List<StockCheckResult> {
        return stockCodes.map { analyze(context, it) }
    }

    fun formatResult(result: StockCheckResult): String {
        return buildString {
            appendLine("═══ ${result.stockName}(${result.stockCode}) ═══")
            appendLine("价格: ${result.currentPrice}  PE: ${result.pe}  换手率: ${result.turnoverRate}%")
            appendLine("量比: ${"%.2f".format(result.volumeRatio)}")
            appendLine()
            var idx = 0
            appendLine("${++idx}. 粘合度≤${convergenceThreshold}%: ${if (result.convergenceOk) "✅" else "❌"} (${"%.2f".format(result.convergenceDegree)}%)")
            appendLine("${++idx}. 多头排列:       ${if (result.bullishAligned) "✅" else "❌"}")
            appendLine("${++idx}. 粘合≥${convergenceDurationDays}天:    ${if (result.convergenceDurationOk) "✅" else "❌"} (${result.convergenceDays}天)")
            val volDesc = when {
                requireVolumeShrink -> "地量"
                moderateVolumeLower > 0 -> "温和${moderateVolumeLower}-${moderateVolumeUpper}x"
                else -> "放量≥${volumeBreakoutRatio}x"
            }
            appendLine("${++idx}. $volDesc: ${if (result.volumeConditionOk) "✅" else "❌"} (量比${"%.1f".format(result.volumeRatio)})")
            if (minDrawdownPct > 0) {
                appendLine("${++idx}. 跌幅≥${"%.0f".format(minDrawdownPct)}%:  ${if (result.drawdownOk) "✅" else "❌"} (${"%.1f".format(result.drawdownPct)}%)")
            }
            if (requireMA60Rising) {
                appendLine("${++idx}. MA60上升:       ${if (result.ma60Rising) "✅" else "❌"}")
            }
            if (requireAboveYearLine) {
                appendLine("${++idx}. 站稳年线:       ${if (result.aboveYearLine) "✅" else "❌"}")
            }
            if (requireChangePct) {
                appendLine("${++idx}. 涨幅≥${"%.1f".format(minChangePct)}%:   ${if (result.changePctOk) "✅" else "❌"}")
            }
            if (requireAboveAllMAs) {
                appendLine("${++idx}. 站上所有均线:   ${if (result.aboveAllMAs) "✅" else "❌"}")
            }
            appendLine()
            appendLine("通过: ${result.passCount}/${result.totalChecks} ${if (result.passed) "→ ✅ 符合买入条件" else "→ ⚠ 未达标准"}")
        }
    }

}
