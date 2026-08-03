package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity

/**
 * ## 均線粘合分析工具（共用）
 *
 * 供 DAG 節點 MaConvergenceNode 和 StockDetailFragment 復用。
 * 輸入：按日期升序排列的 DailySnapshotEntity 列表。
 *
 * ### 核心指標
 * 1. **粘合度**：MA5/MA10/MA20 離散率 < threshold → 粘合
 * 2. **方向**：MA5 今日 > MA5 昨日 → 向上
 * 3. **粘合向上**：粘合 + 向上 = 蓄勢突破信號
 */
object MaConvergenceAnalyzer {

    data class Result(
        /** MA5/MA10/MA20 是否粘合 */
        val converged: Boolean,
        /** 均線離散率（百分比，如 1.2 = 1.2%） */
        val divergencePct: Double,
        /** 均線是否向上（MA5 今日 > 昨日） */
        val upward: Boolean,
        /** 粘合 + 向上 = 蓄勢突破 */
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
                convergedAndUp = false, ma5 = 0.0, ma10 = null, ma20 = null, hint = "數據不足"
            )
        }
    }

    /**
     * 分析均線粘合 + 方向
     * @param snaps 按日期升序排列的日線數據（至少 20 條）
     * @param convergenceThreshold 粘合閾值（離散率 < 此值視為粘合），默認 2%
     */
    fun analyze(snaps: List<DailySnapshotEntity>, convergenceThreshold: Double = 0.02): Result {
        if (snaps.size < 20) return Result.empty()

        val closes = snaps.map { it.close }

        // MA 計算
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
                append("✅ 均線粘合向上(偏離${"%.2f".format(divergence * 100)}%) → 蓄勢突破")
            } else if (converged) {
                append("🟡 均線粘合(偏離${"%.2f".format(divergence * 100)}%) 待方向選擇")
            } else if (upward) {
                append("📈 均線分散但向上(偏離${"%.2f".format(divergence * 100)}%)")
            } else {
                append("⚠️ 均線分散(偏離${"%.2f".format(divergence * 100)}%)")
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
}
