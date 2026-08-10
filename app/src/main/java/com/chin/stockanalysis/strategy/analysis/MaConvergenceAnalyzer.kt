package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity

/**
 * ## 均线粘合分析工具（共用）
 *
 * 供 DAG 节点 MaConvergenceNode 和 StockDetailFragment 复用。
 * 输入：按日期升序排列的 DailySnapshotEntity 列表。
 *
 * ### 核心指标
 * 1. **粘合度**：MA5/MA10/MA20 离散率 < threshold → 粘合
 * 2. **方向**：MA5 今日 > MA5 昨日 → 向上
 * 3. **粘合向上**：粘合 + 向上 = 蓄势突破信号
 */
object MaConvergenceAnalyzer {

    data class Result(
        /** MA5/MA10/MA20 是否粘合 */
        val converged: Boolean,
        /** 均线离散率（百分比，如 1.2 = 1.2%） */
        val divergencePct: Double,
        /** 均线是否向上（MA5 今日 > 昨日） */
        val upward: Boolean,
        /** 粘合 + 向上 = 蓄势突破 */
        val convergedAndUp: Boolean,
        val ma5: Double,
        val ma10: Double?,
        val ma20: Double?,
        /** 文字摘要 */
        val hint: String
    ) {
        companion object {
            fun empty() = Result(
                converged = false, divergencePct = 999.0, upward = false,
                convergedAndUp = false, ma5 = 0.0, ma10 = null, ma20 = null, hint = "数据不足"
            )
        }
    }

    /**
     * 分析均线粘合 + 方向
     * @param snaps 按日期升序排列的日线数据（至少 20 条）
     * @param convergenceThreshold 粘合阈值（离散率 < 此值视为粘合），默认 2%
     */
    fun analyze(snaps: List<DailySnapshotEntity>, convergenceThreshold: Double = 0.02): Result {
        if (snaps.size < 20) return Result.empty()

        val closes = snaps.map { it.close }

        // MA 计算
        val ma5 = closes.takeLast(5).average()
        val ma10 = if (closes.size >= 10) closes.takeLast(10).average() else null
        val ma20 = if (closes.size >= 20) closes.takeLast(20).average() else null

        if (ma10 == null || ma20 == null) return Result.empty()

        // 粘合度
        val maMax = maxOf(ma5, ma10, ma20)
        val maMin = minOf(ma5, ma10, ma20)
        val divergence = if (maMin > 0) (maMax - maMin) / maMin else 1.0
        val converged = divergence < convergenceThreshold

        // 方向：MA5 今日 vs 昨日
        val ma5Yesterday = if (closes.size >= 6) {
            closes.subList(closes.size - 6, closes.size - 1).average()
        } else ma5
        val upward = ma5 > ma5Yesterday

        val convergedAndUp = converged && upward

        // 文字摘要
        val hint = buildString {
            if (convergedAndUp) {
                append("✅ 均线粘合向上(偏离${"%.2f".format(divergence * 100)}%) → 蓄势突破")
            } else if (converged) {
                append("🟡 均线粘合(偏离${"%.2f".format(divergence * 100)}%) 待方向选择")
            } else if (upward) {
                append("📈 均线分散但向上(偏离${"%.2f".format(divergence * 100)}%)")
            } else {
                append("⚠️ 均线分散(偏离${"%.2f".format(divergence * 100)}%)")
            }
        }

        return Result(
            converged = converged,
            divergencePct = divergence * 100,
            upward = upward,
            convergedAndUp = convergedAndUp,
            ma5 = ma5,
            ma10 = ma10,
            ma20 = ma20,
            hint = hint
        )
    }

    /**
     * 多头排列粘合检查（(MA5-MA20)/MA20 公式）
     *
     * 供 StrictSelectionNode / StockCheckPipeline / PipelineBacktestEngine 等使用，
     * 与 [analyze] 的 (max-min)/min 公式不同，此处用 (MA5-MA20)/MA20 离散率。
     *
     * @param closes 按日期升序的收盘价列表
     * @param threshold 离散率阈值（如 0.03 = 3%）
     * @return (ma5, ma10, ma20, isBullishConverged)
     */
    fun bullishConvergence(
        closes: List<Double>,
        threshold: Double = 0.03
    ): Pair<Triple<Double, Double, Double>, Boolean> = calcMaBullishConvergence(closes, threshold)
}
