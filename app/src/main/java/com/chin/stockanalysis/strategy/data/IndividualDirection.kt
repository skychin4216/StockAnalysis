package com.chin.stockanalysis.strategy.data

/**
 * ## 个股方向标签（v6：先判方向，再定周期）
 *
 * 在进入各周期 UseCase 之前，先对每只候选股做方向判断：
 * - [UPTREND] / [DOWNTREND]：均线多头/空头排列决定的趋势方向
 * - [ACCUMULATION]：均线粘合横盘蓄势（中线/长线最爱的形态）
 * - [BREAKOUT]：粘合后放量突破（短线/超短线起爆点）
 * - [OSCILLATION]：区间震荡，方向不明（中线/长线观望）
 *
 * 周期路由建议：
 * - 超短/短线：BREAKOUT > UPTREND（追强势）
 * - 中线/长线：ACCUMULATION > UPTREND（买蓄势/趋势），DOWNTREND/OSCILLATION 不参与
 */
enum class IndividualDirection(val cn: String) {
    UPTREND("上升趋势"),
    DOWNTREND("下降趋势"),
    ACCUMULATION("横盘蓄势"),
    BREAKOUT("放量突破"),
    OSCILLATION("区间震荡")
}

/**
 * ## 个股方向分析器（纯函数，无 IO，Kotlin/Python 双端同口径）
 *
 * 输入来自均线/粘合分析结果，输出方向标签。
 * 判定优先级：突破 > 蓄势 > 上升 > 下降 > 震荡。
 */
object DirectionAnalyzer {

    /** 粘合度阈值（%）— 与 strict_selection 的 convergenceThreshold 一致 */
    private const val CONVERGENCE_MAX = 6.0

    /** 蓄势最低持续天数 */
    private const val ACCUMULATION_MIN_DAYS = 8

    /** 放量阈值（量比） */
    private const val BREAKOUT_VOLUME_RATIO = 1.15

    fun analyze(
        close: Double,
        ma5: Double,
        ma10: Double,
        ma20: Double,
        ma60Rising: Boolean,
        convergenceDegree: Double,
        convergenceDays: Int,
        closeAboveConvergenceTop: Boolean,
        volumeRatio: Double,
        aboveAllMAs: Boolean
    ): IndividualDirection {
        val isConverging = convergenceDegree in 0.1..CONVERGENCE_MAX && convergenceDays >= ACCUMULATION_MIN_DAYS
        // 1) 粘合后放量突破 → 起爆点
        if (isConverging && closeAboveConvergenceTop && volumeRatio >= BREAKOUT_VOLUME_RATIO && aboveAllMAs) {
            return IndividualDirection.BREAKOUT
        }
        // 2) 粘合横盘 → 蓄势（即使还未突破）
        if (isConverging) return IndividualDirection.ACCUMULATION
        // 3) 均线多头排列 → 上升趋势
        if (ma5 > ma10 && ma10 > ma20 && close > ma5 && ma60Rising) {
            return IndividualDirection.UPTREND
        }
        // 4) 均线空头排列 → 下降趋势（中线/长线禁用）
        if (ma5 < ma10 && ma10 < ma20 && close < ma5 && !ma60Rising) {
            return IndividualDirection.DOWNTREND
        }
        // 5) 其他 → 区间震荡
        return IndividualDirection.OSCILLATION
    }

    /** 该方向是否允许进入中线/长线（先判方向再定周期） */
    fun allowedForHolding(direction: IndividualDirection): Boolean = when (direction) {
        IndividualDirection.UPTREND,
        IndividualDirection.ACCUMULATION,
        IndividualDirection.BREAKOUT -> true
        IndividualDirection.DOWNTREND,
        IndividualDirection.OSCILLATION -> false
    }
}
