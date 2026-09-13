package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity

/**
 * ## 打底仓条件分析工具
 *
 * 总纲：长线看势，中线看价，短线看量，超短看情绪
 * 逃顶要快，抄底要慢
 *
 * 抄底 / 打底仓两条件：
 *   1. 三天不新低 — 最近 3 天 low 均 > 前 3 天最低 low
 *   2. MA5/MA10/MA30 均线粘合向上
 *
 * 逃顶信号：
 *   - 3 天急跌 > 5% 或跌破 3 日最低 low
 */
object BasePositionAnalyzer {

    data class Result(
        val threeDayNoNewLow: Boolean,
        val maConverged: Boolean,
        val maUpward: Boolean,
        val maConvergedAndUp: Boolean,
        val basePositionReady: Boolean,   // 两条件都满足
        val escapeUrgent: Boolean,        // 逃顶要快
        val ma5: Double,
        val ma10: Double,
        val ma30: Double,
        val divergencePct: Double,
        val threeDayChangePct: Double,
        val hint: String
    ) {
        companion object {
            fun empty() = Result(
                false, false, false, false, false, false,
                0.0, 0.0, 0.0, 999.0, 0.0, "数据不足"
            )
        }
    }

    /**
     * @param snaps 按日期升序
     * @param convergenceThreshold MA 离散率阈值（默认 2%）
     */
    fun analyze(
        snaps: List<DailySnapshotEntity>,
        convergenceThreshold: Double = 0.02
    ): Result {
        if (snaps.size < 30) return Result.empty()

        // ═══ 1. 三天不新低 ═══
        val recentAllAbove = StabilityChecker.check(snaps, StabilityChecker.Mode.ABOVE_PRIOR_MIN)

        // ═══ 2. MA5/MA10/MA30 粘合向上 ═══
        val closes = snaps.map { it.close }
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val ma30 = closes.takeLast(30).average()

        val minMa = minOf(ma5, ma10, ma30)
        val maxMa = maxOf(ma5, ma10, ma30)
        val divergence = if (minMa > 0) (maxMa - minMa) / minMa else 1.0
        val converged = divergence < convergenceThreshold

        // MA5 方向：今天 MA5 vs 昨天 MA5
        val yesterdayMa5 = if (closes.size >= 6)
            closes.dropLast(1).takeLast(5).average()
        else ma5
        val upward = ma5 > yesterdayMa5

        // ═══ 3. 逃顶要快 ═══
        val threeDayChange = if (snaps.size >= 4) {
            val c3 = snaps[snaps.size - 4].close
            if (c3 > 0) (closes.last() - c3) / c3 * 100 else 0.0
        } else 0.0
        val escapeUrgent = threeDayChange < -5.0 || !recentAllAbove

        // ═══ 4. 综合判定 ═══
        val baseReady = recentAllAbove && converged && upward

        val hint = buildString {
            append(if (recentAllAbove) "✅3天不新低" else "⚠️仍在创新低")
            append(" | ")
            append("MA离散${"%.1f".format(divergence * 100)}%")
            append(if (converged) "✅粘合" else "⚠️分散")
            append(if (upward) "↑" else "↓")
            if (escapeUrgent && !baseReady) {
                append(" | ⚡逃顶要快(${"%.1f".format(threeDayChange)}%)")
            }
        }

        return Result(
            threeDayNoNewLow = recentAllAbove,
            maConverged = converged,
            maUpward = upward,
            maConvergedAndUp = converged && upward,
            basePositionReady = baseReady,
            escapeUrgent = escapeUrgent && !baseReady,
            ma5 = ma5, ma10 = ma10, ma30 = ma30,
            divergencePct = divergence,
            threeDayChangePct = threeDayChange,
            hint = hint
        )
    }
}
