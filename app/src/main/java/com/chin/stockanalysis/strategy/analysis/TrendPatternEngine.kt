package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity

/**
 * ## K 线趋势识别引擎（扫描 / 个股详情 / AI精选闸门 三处共用）
 *
 * 输入要求：**时间正序**（旧 → 新）的日K列表，内部取最近 20 根做识别。
 *
 * 由两部分组成：
 * 1. **RSA 状态机近似**（对应 SAR 变色趋势状态机）：
 *    观察 MA5/MA20 的 金叉/死叉（近 3 根）、多头/空头排列 与 收线位置，
 *    输出 绿翻红 / 红翻绿 / 多头 / 空头 状态标签。
 * 2. **看多形态识别**（三白兵 / 看涨吞没 / 锤子线 / 上升趋势），每类带权重：
 *    - 三白兵=3 / 上升趋势=3 / 看涨吞没=2 / 锤子线=1
 *
 * 加权规则（与“RSA 很重要；低权重因素不硬拦截、该放行放行”对齐）：
 * - 高权重形态（>=2）直接放行；
 * - 低权重形态（锤子线）仅在空头状态（死叉/空头排列）下拦截；
 * - 多头状态对低权重形态给于“放行”，趋势状态标签随结果返回供展示。
 */
object TrendPatternEngine {

    /** 识别结果：cat/tag 与趋势图谱分类对齐；weight 为形态权重；stateLabel/stateBull 为 RSA 状态 */
    data class Match(
        val cat: String,
        val tag: String,
        val weight: Int,
        val stateLabel: String? = null,
        val stateBull: Boolean = false
    )

    /**
     * 识别最近 20 根 K 线的形态 + RSA 状态。
     * @param ascCandles 时间正序的日K列表（旧→新）
     * @return 命中看多形态则返回 Match，否则 null
     */
    fun match(ascCandles: List<DailySnapshotEntity>): Match? {
        if (ascCandles.size < 20) return null
        val k = ascCandles.takeLast(20)
        val last = k.last()

        // ── 1. RSA 趋势状态机 ──
        fun ma(n: Int, idx: Int): Double {
            val from = (idx - n + 1).coerceAtLeast(0)
            return k.subList(from, idx + 1).map { it.close }.average()
        }
        val m5 = ma(5, k.size - 1)
        val m20 = ma(20, k.size - 1)
        val lastBarBull = m5 > m20 && last.close > m5
        val lastBarBear = m5 < m20 && last.close < m5

        var crossedUp = false
        var crossedDown = false
        for (i in (k.size - 3).coerceAtLeast(1) until k.size) {
            val p5 = ma(5, i - 1); val p20 = ma(20, i - 1)
            val c5 = ma(5, i);   val c20 = ma(20, i)
            if (p5 <= p20 && c5 > c20) crossedUp = true
            if (p5 >= p20 && c5 < c20) crossedDown = true
        }
        var stateBull = false
        var stateLabel: String? = null
        when {
            crossedUp -> { stateBull = true;  stateLabel = "绿翻红" }
            crossedDown -> { stateBull = false; stateLabel = "红翻绿" }
            lastBarBull -> { stateBull = true;  stateLabel = "多头" }
            lastBarBear -> { stateBull = false; stateLabel = "空头" }
            else -> { stateBull = m5 >= m20; stateLabel = if (stateBull) "多头" else "空头" }
        }

        // ── 2. 看多形态（权重高者优先，识别顺序与历史一致） ──

        // 三白兵：最近3根均阳线且收盘依次抬高（权重 3）
        val last3 = k.takeLast(3)
        if (last3.size == 3 && last3.all { it.close > it.open } &&
            last3[0].close < last3[1].close && last3[1].close < last3[2].close
        ) {
            return Match("three", "三白兵", 3, stateLabel, stateBull)
        }

        // 看涨吞没：前阴后阳，阳线实体吞没前阴实体（权重 2）
        if (k.size >= 2) {
            val prev = k[k.size - 2]
            if (prev.close < prev.open && last.close > last.open &&
                last.close >= prev.open && last.open <= prev.close
            ) {
                return Match("two", "看涨吞没", 2, stateLabel, stateBull)
            }
        }

        // 锤子线：下影线 >= 2 倍实体，收盘偏强（低权重 1：空头状态下拦截）
        val body = Math.abs(last.close - last.open)
        val lower = Math.min(last.close, last.open) - last.low
        if (body > 0 && lower >= 2 * body && last.close >= last.open) {
            if (crossedDown || lastBarBear) return null // 加权拦截：趋势看空不放行
            return Match("single", "锤子线(反转)", 1, stateLabel, stateBull)
        }

        // 上升趋势：MA5 > MA20 且收盘站上 MA5（权重 3）
        if (m5 > m20 && last.close > m5) {
            return Match("trend", "上升趋势", 3, stateLabel, stateBull)
        }

        return null
    }

    /**
     * 便捷：仅判断当前 RSA 状态是否偏多（供 AI 精选闸门等使用）
     */
    fun isBullish(ascCandles: List<DailySnapshotEntity>): Boolean =
        match(ascCandles)?.stateBull == true
}
