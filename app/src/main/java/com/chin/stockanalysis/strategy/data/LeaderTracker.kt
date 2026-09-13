package com.chin.stockanalysis.strategy.data

/**
 * ## 龙头股跟踪器（v6：龙头优先）
 *
 * 同一板块内，先识别「龙头」再决定买哪只：
 * - 龙头分 = 强度分×0.5 + 当日涨幅×0.8 + 量比×0.5 + 方向加成 + 命中次数×1.5
 * - 每板块取最高分者为龙头（strength 加分 +8）
 * - 龙头当日走弱（断板/大跌/破位）时标记 [LeaderStatus.DEGRADED]，
 *   提醒切向同板块次强股或离场
 */
enum class LeaderStatus(val cn: String) {
    ACTIVE("龙头强势"),
    DEGRADED("龙头走弱"),
    FOLLOWER("跟风股")
}

object LeaderTracker {

    private const val LEADER_BONUS = 8.0

    /** 方向加成：突破/上升最强，蓄势次之，下降/震荡无 */
    private fun directionBonus(direction: IndividualDirection?): Double = when (direction) {
        IndividualDirection.BREAKOUT -> 4.0
        IndividualDirection.UPTREND -> 3.0
        IndividualDirection.ACCUMULATION -> 2.0
        else -> 0.0
    }

    /**
     * 龙头强度分（越大越强）。
     * @param strength 节点原始 strength（0..100）
     * @param changePct 当日涨幅%
     * @param volumeRatio 量比
     * @param passCount 命中/通过数
     * @param direction 个股方向（可空）
     * @param drawdownPct 距高点回撤%（越小越好，破位走弱时扣分）
     */
    fun leaderScore(
        strength: Double,
        changePct: Double,
        volumeRatio: Double,
        passCount: Int,
        direction: IndividualDirection?,
        drawdownPct: Double
    ): Double =
        strength * 0.5 +
            changePct * 0.8 +
            volumeRatio * 0.5 +
            passCount * 1.5 +
            directionBonus(direction) +
            drawdownPct.coerceAtLeast(-20.0) * -0.3 // 距高点越远扣越多

    /** 龙头状态：断板/大跌/破位视为走弱 */
    fun statusOf(changePct: Double, drawdownPct: Double, isLeader: Boolean): LeaderStatus {
        if (!isLeader) return LeaderStatus.FOLLOWER
        val broken = changePct <= -3.0 || drawdownPct >= 12.0
        return if (broken) LeaderStatus.DEGRADED else LeaderStatus.ACTIVE
    }

    /**
     * 同板块分组标龙头。
     * @param items 每只候选股的 (code, name, score, isCandidate)
     * @param sectorOf 该股的板块归属（null=无板块）
     * @return 板块名 → 龙头 code；并把龙头 code 集返回
     */
    fun tagLeaders(
        items: List<LeaderItem>,
        sectorOf: (String) -> String?
    ): Pair<Map<String, String>, Set<String>> {
        val leaders = HashMap<String, String>()
        val grouped = items.filter { it.score != null }
            .groupBy { sectorOf(it.code) ?: "" }
            .filterKeys { it.isNotEmpty() }
        for ((sector, list) in grouped) {
            val top = list.maxByOrNull { it.score!! } ?: continue
            leaders[sector] = top.code
        }
        return leaders to leaders.values.toSet()
    }

    /** 候选股条目 */
    data class LeaderItem(
        val code: String,
        val name: String,
        val score: Double?,
    )
}
