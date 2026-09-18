package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity

/**
 * ## K 线经典形态侦测器
 *
 * 侦测上升三法、下降三法、早晨之星、黄昏之星、红三兵、三乌鸦等经典形态。
 * 输入为最近 N 日的 OHLC 数据（ASC：旧→新），输出为匹配到的形态列表。
 *
 * 用于 Pipeline 中对候选股进行形态扫描，在报告中重点提醒买入/卖出。
 */
object CandlePatternDetector {

    /** 形态方向 */
    enum class Direction(val label: String, val signal: String) {
        BULLISH("看多", "买入"),
        BEARISH("看空", "卖出")
    }

    /** 侦测结果 */
    data class PatternMatch(
        val patternName: String,      // 上升三法
        val direction: Direction,
        val description: String,      // 简要描述
        val strength: Int             // 强度 1-5（越高越可靠）
    )

    /**
     * 对单只股票的 K 线进行形态侦测。
     *
     * @param candles 最近 N 日 K 线（ASC：旧→新），建议至少 10 根
     * @return 匹配到的形态列表（可能多个），按强度降序
     */
    fun detect(candles: List<DailySnapshotEntity>): List<PatternMatch> {
        if (candles.size < 5) return emptyList()
        val results = mutableListOf<PatternMatch>()

        // 取最后几根 K 线侦测（只看最近形成的形态）
        val n = candles.size
        val c = candles // shorthand

        detectRisingThreeMethods(c, n)?.let { results.add(it) }
        detectFallingThreeMethods(c, n)?.let { results.add(it) }
        detectMorningStar(c, n)?.let { results.add(it) }
        detectEveningStar(c, n)?.let { results.add(it) }
        detectThreeWhiteSoldiers(c, n)?.let { results.add(it) }
        detectThreeBlackCrows(c, n)?.let { results.add(it) }
        // 新增形态
        detectBullishEngulfing(c, n)?.let { results.add(it) }
        detectBearishEngulfing(c, n)?.let { results.add(it) }
        detectBullishHarami(c, n)?.let { results.add(it) }
        detectBearishHarami(c, n)?.let { results.add(it) }
        detectDoji(c, n)?.let { results.add(it) }
        detectHammer(c, n)?.let { results.add(it) }
        detectShootingStar(c, n)?.let { results.add(it) }
        detectPiercingLine(c, n)?.let { results.add(it) }
        detectDarkCloudCover(c, n)?.let { results.add(it) }
        detectInvertedHammerBottom(c, n)?.let { results.add(it) }
        detectInvertedHammerPullback(c, n)?.let { results.add(it) }

        // ═══ 复杂趋势形态（需要 30+ 根 K 线） ═══
        if (n >= 30) {
            detectWBottom(c, n)?.let { results.add(it) }
            detectDoubleTop(c, n)?.let { results.add(it) }
            detectHeadShouldersTop(c, n)?.let { results.add(it) }
            detectHeadShouldersBottom(c, n)?.let { results.add(it) }
            detectRoundedBottom(c, n)?.let { results.add(it) }
            detectVBottom(c, n)?.let { results.add(it) }
            detectGoldenPit(c, n)?.let { results.add(it) }
            detectIslandReversal(c, n)?.let { results.add(it) }
            detectAscendingFlag(c, n)?.let { results.add(it) }
            detectDescendingTriangle(c, n)?.let { results.add(it) }
            detectExpandingTriangle(c, n)?.let { results.add(it) }
            detectCupAndHandle(c, n)?.let { results.add(it) }
            detectBottomDoubleHeroes(c, n)?.let { results.add(it) }
        }

        return results.sortedByDescending { it.strength }
    }

    /**
     * 批量侦测多只股票。
     * @param stockCandles Map<stockCode, List<DailySnapshotEntity>>（ASC）
     * @return Map<stockCode, List<PatternMatch>>（只含有形态的股票）
     */
    fun detectBatch(stockCandles: Map<String, List<DailySnapshotEntity>>): Map<String, List<PatternMatch>> {
        return stockCandles.mapNotNull { (code, candles) ->
            val patterns = detect(candles)
            if (patterns.isNotEmpty()) code to patterns else null
        }.toMap()
    }

    // ════════════════════════════════════════════════════
    //  各形态侦测逻辑
    // ════════════════════════════════════════════════════

    /**
     * 上升三法（Rising Three Methods）— 5 根 K 线，看多持续
     *
     * Day1: 长阳线（实体 > 平均实体的 1.5 倍）
     * Day2-4: 3 根小阴线（实体 < Day1 实体的 50%），收盘在 Day1 范围内
     * Day5: 长阳线，收盘 > Day1 收盘
     */
    private fun detectRisingThreeMethods(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d1 = c[n - 5]; val d2 = c[n - 4]; val d3 = c[n - 3]; val d4 = c[n - 2]; val d5 = c[n - 1]

        val body1 = d1.close - d1.open  // 阳线 > 0
        val body5 = d5.close - d5.open
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        // Day1: 长阳
        if (body1 <= 0 || body1 < avgBody * 1.3) return null
        // Day5: 长阳，收盘 > Day1 收盘
        if (body5 <= 0 || d5.close <= d1.close) return null
        // Day2-4: 小阴线，收盘在 Day1 范围内（open~close 之间）
        val smallBody = body1 * 0.6
        for (d in listOf(d2, d3, d4)) {
            val body = d.close - d.open
            if (body > 0 && kotlin.math.abs(body) > smallBody) return null  // 不是小阴
            if (d.close < d1.open || d.close > d1.close) return null        // 超出 Day1 范围
        }

        return PatternMatch(
            patternName = "上升三法",
            direction = Direction.BULLISH,
            description = "长阳后三根小阴回调未破低，第五根长阳突破——多头蓄力完成，趋势延续",
            strength = 4
        )
    }

    /**
     * 下降三法（Falling Three Methods）— 5 根 K 线，看空持续
     *
     * Day1: 长阴线
     * Day2-4: 3 根小阳线（反弹无力），收盘在 Day1 范围内
     * Day5: 长阴线，收盘 < Day1 收盘
     */
    private fun detectFallingThreeMethods(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d1 = c[n - 5]; val d2 = c[n - 4]; val d3 = c[n - 3]; val d4 = c[n - 2]; val d5 = c[n - 1]

        val body1 = d1.open - d1.close  // 阴线 > 0
        val body5 = d5.open - d5.close
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        // Day1: 长阴
        if (body1 <= 0 || body1 < avgBody * 1.3) return null
        // Day5: 长阴，收盘 < Day1 收盘
        if (body5 <= 0 || d5.close >= d1.close) return null
        // Day2-4: 小阳线，收盘在 Day1 范围内
        val smallBody = body1 * 0.6
        for (d in listOf(d2, d3, d4)) {
            val body = d.open - d.close  // 阴线为正
            if (body > 0 && kotlin.math.abs(d.close - d.open) > smallBody) return null
            if (d.close > d1.open || d.close < d1.close) return null
        }

        return PatternMatch(
            patternName = "下降三法",
            direction = Direction.BEARISH,
            description = "长阴后三根小阳反弹未破高，第五根长阴跌破——空头蓄力完成，跌势延续",
            strength = 4
        )
    }

    /**
     * 早晨之星（Morning Star）— 3 根 K 线，看多反转
     *
     * Day1: 长阴线（跌幅 > 2%）
     * Day2: 小实体（十字星/小阴小阳），与 Day1 有向下跳空
     * Day3: 长阳线，收盘深入 Day1 实体（> Day1 中点）
     */
    private fun detectMorningStar(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 3) return null
        val d1 = c[n - 3]; val d2 = c[n - 2]; val d3 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        val body1 = d1.open - d1.close   // 阴 > 0
        val body2 = kotlin.math.abs(d2.close - d2.open)
        val body3 = d3.close - d3.open   // 阳 > 0

        // Day1: 长阴
        if (body1 <= 0 || body1 < avgBody * 1.2) return null
        // Day2: 小实体（< 平均的 40%）
        if (body2 > avgBody * 0.4) return null
        // Day3: 长阳，收盘 > Day1 实体中点
        val mid1 = (d1.open + d1.close) / 2
        if (body3 <= 0 || d3.close < mid1) return null
        // Day2 的低点低于 Day1 收盘（跳空或触底）
        if (d2.low > d1.close) return null

        return PatternMatch(
            patternName = "早晨之星",
            direction = Direction.BULLISH,
            description = "长阴后出现小实体星线，第三根长阳收复失地——底部反转信号",
            strength = 5
        )
    }

    /**
     * 黄昏之星（Evening Star）— 3 根 K 线，看空反转
     *
     * Day1: 长阳线
     * Day2: 小实体（星线），与 Day1 有向上跳空
     * Day3: 长阴线，收盘深入 Day1 实体
     */
    private fun detectEveningStar(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 3) return null
        val d1 = c[n - 3]; val d2 = c[n - 2]; val d3 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        val body1 = d1.close - d1.open   // 阳 > 0
        val body2 = kotlin.math.abs(d2.close - d2.open)
        val body3 = d3.open - d3.close   // 阴 > 0

        // Day1: 长阳
        if (body1 <= 0 || body1 < avgBody * 1.2) return null
        // Day2: 小实体
        if (body2 > avgBody * 0.4) return null
        // Day3: 长阴，收盘 < Day1 实体中点
        val mid1 = (d1.open + d1.close) / 2
        if (body3 <= 0 || d3.close > mid1) return null
        // Day2 高点 > Day1 收盘（星线在上方）
        if (d2.high < d1.close) return null

        return PatternMatch(
            patternName = "黄昏之星",
            direction = Direction.BEARISH,
            description = "长阳后出现小实体星线，第三根长阴吞噬涨幅——顶部反转信号",
            strength = 5
        )
    }

    /**
     * 红三兵（Three White Soldiers）— 3 根连续长阳，看多
     *
     * 连续 3 根阳线，每根收盘 > 前一根收盘，实体逐渐放大或稳定，无长上影线。
     */
    private fun detectThreeWhiteSoldiers(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 3) return null
        val d1 = c[n - 3]; val d2 = c[n - 2]; val d3 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        val days = listOf(d1, d2, d3)
        // 全部阳线
        if (days.any { it.close <= it.open }) return null
        // 收盘逐步抬高
        if (!(d2.close > d1.close && d3.close > d2.close)) return null
        // 开盘在前一根实体内（非跳空过大）
        if (d2.open < d1.open || d3.open < d2.open) return null
        // 实体不能太小（> 平均的 60%）
        if (days.any { (it.close - it.open) < avgBody * 0.6 }) return null
        // 无过长上影线（上影 < 实体的 30%）
        if (days.any { (it.high - it.close) > (it.close - it.open) * 0.3 }) return null

        return PatternMatch(
            patternName = "红三兵",
            direction = Direction.BULLISH,
            description = "连续三根实体阳线逐步走高，无上影压力——多头强势推进",
            strength = 3
        )
    }

    /**
     * 三乌鸦（Three Black Crows）— 3 根连续长阴，看空
     *
     * 连续 3 根阴线，每根收盘 < 前一根收盘，实体稳定，无长下影线。
     */
    private fun detectThreeBlackCrows(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 3) return null
        val d1 = c[n - 3]; val d2 = c[n - 2]; val d3 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        val days = listOf(d1, d2, d3)
        // 全部阴线
        if (days.any { it.close >= it.open }) return null
        // 收盘逐步走低
        if (!(d2.close < d1.close && d3.close < d2.close)) return null
        // 开盘在前一根实体内
        if (d2.open > d1.open || d3.open > d2.open) return null
        // 实体不能太小
        if (days.any { (it.open - it.close) < avgBody * 0.6 }) return null
        // 无过长下影线
        if (days.any { (it.low - it.close) < -(it.open - it.close) * 0.3 }) return null

        return PatternMatch(
            patternName = "三乌鸦",
            direction = Direction.BEARISH,
            description = "连续三根实体阴线逐步走低，无下影支撑——空头强势打压",
            strength = 3
        )
    }

    // ════════════════════════════════════════════════════
    //  新增形态侦测（吞没/孕线/十字星/锤子线/射击之星/刺透/乌云盖顶）
    // ════════════════════════════════════════════════════

    /**
     * 看涨吞没（Bullish Engulfing）— 2 根 K 线，看多反转
     *
     * Day1: 阴线
     * Day2: 阳线，实体完全覆盖 Day1 实体（open <= Day1.open, close >= Day1.close）
     */
    private fun detectBullishEngulfing(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        // Day1 阴线
        if (d1.close >= d1.open) return null
        // Day2 阳线
        if (d2.close <= d2.open) return null
        // Day2 实体完全覆盖 Day1 实体
        if (d2.open > d1.open || d2.close < d1.close) return null
        // Day2 实体不能太小
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        if ((d2.close - d2.open) < avgBody * 0.8) return null

        return PatternMatch(
            patternName = "看涨吞没",
            direction = Direction.BULLISH,
            description = "阳线完全包裹前一根阴线实体——多头强势反转，买盘压制卖盘",
            strength = 4
        )
    }

    /**
     * 看跌吞没（Bearish Engulfing）— 2 根 K 线，看空反转
     *
     * Day1: 阳线
     * Day2: 阴线，实体完全覆盖 Day1 实体
     */
    private fun detectBearishEngulfing(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        // Day1 阳线
        if (d1.close <= d1.open) return null
        // Day2 阴线
        if (d2.close >= d2.open) return null
        // Day2 实体完全覆盖 Day1 实体
        if (d2.open < d1.open || d2.close > d1.close) return null
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        if ((d2.open - d2.close) < avgBody * 0.8) return null

        return PatternMatch(
            patternName = "看跌吞没",
            direction = Direction.BEARISH,
            description = "阴线完全包裹前一根阳线实体——空头强势反转，卖盘压制买盘",
            strength = 4
        )
    }

    /**
     * 看涨孕线（Bullish Harami）— 2 根 K 线，看多反转
     *
     * Day1: 长阴线
     * Day2: 小阳线，实体完全在 Day1 实体范围内
     */
    private fun detectBullishHarami(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        // Day1 长阴线
        if (d1.close >= d1.open) return null
        if ((d1.open - d1.close) < avgBody * 1.0) return null
        // Day2 小阳线，实体在 Day1 实体内
        if (d2.close <= d2.open) return null
        if (d2.open < d1.close || d2.close > d1.open) return null
        if ((d2.close - d2.open) > avgBody * 0.6) return null

        return PatternMatch(
            patternName = "看涨孕线",
            direction = Direction.BULLISH,
            description = "大阴线后小阳线藏于其实体内——下跌动能减弱，可能反转",
            strength = 2
        )
    }

    /**
     * 看跌孕线（Bearish Harami）— 2 根 K 线，看空反转
     *
     * Day1: 长阳线
     * Day2: 小阴线，实体完全在 Day1 实体范围内
     */
    private fun detectBearishHarami(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        // Day1 长阳线
        if (d1.close <= d1.open) return null
        if ((d1.close - d1.open) < avgBody * 1.0) return null
        // Day2 小阴线，实体在 Day1 实体内
        if (d2.close >= d2.open) return null
        if (d2.open > d1.close || d2.close < d1.open) return null
        if ((d2.open - d2.close) > avgBody * 0.6) return null

        return PatternMatch(
            patternName = "看跌孕线",
            direction = Direction.BEARISH,
            description = "大阳线后小阴线藏于其实体内——上涨动能减弱，可能反转",
            strength = 2
        )
    }

    /**
     * 十字星（Doji）— 1 根 K 线，中性/反转信号
     *
     * 实体极小（< 平均实体的 10%），有明显上下影线
     */
    private fun detectDoji(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 1) return null
        val d = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        if (avgBody <= 0) return null
        val body = kotlin.math.abs(d.close - d.open)
        // 实体极小
        if (body > avgBody * 0.1) return null
        // 需有上下影线
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        if (upperShadow < body * 1.0 || lowerShadow < body * 1.0) return null

        // 判断趋势背景：前期下跌→反转看多；前期上涨→反转看空
        val recentTrend = if (n >= 5) {
            val prevClose = c[n - 5].close
            when {
                d.close < prevClose * 0.97 -> "下跌"
                d.close > prevClose * 1.03 -> "上涨"
                else -> "横盘"
            }
        } else "横盘"

        val (direction, desc) = when (recentTrend) {
            "下跌" -> Direction.BULLISH to "下跌末端出现十字星——多空均衡，可能见底反转"
            "上涨" -> Direction.BEARISH to "上涨末端出现十字星——多空均衡，可能见顶反转"
            else -> Direction.BULLISH to "横盘中出现十字星——方向不明，等待突破确认"
        }

        return PatternMatch(
            patternName = "十字星",
            direction = direction,
            description = desc,
            strength = 2
        )
    }

    /**
     * 锤子线（Hammer）— 1 根 K 线，看多反转
     *
     * 下影线 > 实体 2 倍，上影线 < 实体 30%，出现在下跌趋势中
     */
    private fun detectHammer(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d = c[n - 1]
        val body = kotlin.math.abs(d.close - d.open)
        if (body <= 0) return null
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        // 下影线 > 实体 2 倍
        if (lowerShadow < body * 2) return null
        // 上影线 < 实体 30%
        if (upperShadow > body * 0.3) return null
        // 需在下跌趋势中
        val prevClose = c[n - 5].close
        if (d.close >= prevClose * 0.98) return null

        return PatternMatch(
            patternName = "锤子线",
            direction = Direction.BULLISH,
            description = "下跌末端出现长下影线——下方有买盘承接，可能见底反转",
            strength = 3
        )
    }

    /**
     * 射击之星（Shooting Star）— 1 根 K 线，看空反转
     *
     * 上影线 > 实体 2 倍，下影线 < 实体 30%，出现在上升趋势中
     */
    private fun detectShootingStar(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d = c[n - 1]
        val body = kotlin.math.abs(d.close - d.open)
        if (body <= 0) return null
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        // 上影线 > 实体 2 倍
        if (upperShadow < body * 2) return null
        // 下影线 < 实体 30%
        if (lowerShadow > body * 0.3) return null
        // 需在上升趋势中
        val prevClose = c[n - 5].close
        if (d.close <= prevClose * 1.02) return null

        return PatternMatch(
            patternName = "射击之星",
            direction = Direction.BEARISH,
            description = "上升末端出现长上影线——上方抛压重，可能见顶反转",
            strength = 3
        )
    }

    /**
     * 刺透形态（Piercing Line）— 2 根 K 线，看多反转
     *
     * Day1: 阴线
     * Day2: 阳线，开盘低于 Day1 最低价，收盘深入 Day1 实体一半以上
     */
    private fun detectPiercingLine(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        // Day1 阴线
        if (d1.close >= d1.open) return null
        // Day2 阳线
        if (d2.close <= d2.open) return null
        // Day2 开盘低于 Day1 最低价（低开）
        if (d2.open > d1.low) return null
        // Day2 收盘深入 Day1 实体一半以上
        val mid1 = (d1.open + d1.close) / 2
        if (d2.close < mid1) return null
        // 但不能完全覆盖（否则是看涨吞没）
        if (d2.close > d1.open) return null

        return PatternMatch(
            patternName = "刺透形态",
            direction = Direction.BULLISH,
            description = "阴线后阳线低开高走，收复前日一半失地——底部反转信号",
            strength = 3
        )
    }

    /**
     * 乌云盖顶（Dark Cloud Cover）— 2 根 K 线，看空反转
     *
     * Day1: 阳线
     * Day2: 阴线，开盘高于 Day1 最高价，收盘深入 Day1 实体一半以下
     */
    private fun detectDarkCloudCover(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        // Day1 阳线
        if (d1.close <= d1.open) return null
        // Day2 阴线
        if (d2.close >= d2.open) return null
        // Day2 开盘高于 Day1 最高价（高开）
        if (d2.open < d1.high) return null
        // Day2 收盘深入 Day1 实体一半以下
        val mid1 = (d1.open + d1.close) / 2
        if (d2.close > mid1) return null
        // 但不能完全覆盖（否则是看跌吞没）
        if (d2.close < d1.open) return null

        return PatternMatch(
            patternName = "乌云盖顶",
            direction = Direction.BEARISH,
            description = "阳线后阴线高开低走，跌入前日实体一半以下——顶部反转信号",
            strength = 3
        )
    }

    /**
     * 底部倒锤头（Inverted Hammer at Bottom）— 1 根 K 线，看多反转
     *
     * 几何：小实体在低位，长上影线（> 实体 2 倍），下影线 < 实体 30%。
     * 与射击之星几何相同，但出现在下跌趋势末端，含义相反——看多。
     * 机构尝试拉升，虽被打回但显示买盘进场意愿。
     */
    private fun detectInvertedHammerBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d = c[n - 1]
        val body = kotlin.math.abs(d.close - d.open)
        if (body <= 0) return null
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        // 上影线 > 实体 2 倍
        if (upperShadow < body * 2) return null
        // 下影线 < 实体 30%
        if (lowerShadow > body * 0.3) return null
        // 需在下跌趋势末端（前 5 日跌幅 > 3%）
        val prevClose = c[n - 5].close
        if (d.close >= prevClose * 0.97) return null

        return PatternMatch(
            patternName = "底部倒锤头",
            direction = Direction.BULLISH,
            description = "下跌末端出现长上影线——有资金尝试拉升，虽被打回但买盘进场意愿增强，可能见底",
            strength = 2
        )
    }

    /**
     * 调整和反弹途中的倒垂线（Inverted Hammer During Pullback）— 1 根 K 线，趋势延续
     *
     * 几何同底部倒锤头，但出现在上升趋势中的回调阶段。
     * 回调时出现长上影线，说明有资金尝试上攻，预示回调结束、升势延续。
     */
    private fun detectInvertedHammerPullback(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 10) return null
        val d = c[n - 1]
        val body = kotlin.math.abs(d.close - d.open)
        if (body <= 0) return null
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        // 上影线 > 实体 2 倍
        if (upperShadow < body * 2) return null
        // 下影线 < 实体 30%
        if (lowerShadow > body * 0.3) return null
        // 大趋势仍向上（10 日前 → 近期高点有明显涨幅）
        val highRecent = c.takeLast(10).maxOf { it.high }
        val prevClose10 = c[n - 10].close
        if (highRecent < prevClose10 * 1.05) return null  // 近 10 日无 5% 涨幅，不算上升趋势
        // 近 3 日处于回调（收盘低于 3 日前）
        if (n < 4) return null
        val prevClose3 = c[n - 4].close
        if (d.close >= prevClose3) return null  // 没在回调

        return PatternMatch(
            patternName = "调整倒垂线",
            direction = Direction.BULLISH,
            description = "上升趋势回调中出现长上影线——有资金尝试上攻，回调接近尾声，升势有望延续",
            strength = 3
        )
    }

    // ════════════════════════════════════════════════════
    //  辅助方法（复杂形态侦测用）
    // ════════════════════════════════════════════════════

    /** 计算指定范围的收盘价简单移动平均 */
    private fun calcMA(c: List<DailySnapshotEntity>, start: Int, end: Int): Double {
        if (start >= end || end > c.size) return 0.0
        return c.subList(start, end).map { it.close }.average()
    }

    /** 计算指定范围的平均成交量 */
    private fun calcAvgVol(c: List<DailySnapshotEntity>, start: Int, end: Int): Double {
        if (start >= end || end > c.size) return 0.0
        return c.subList(start, end).map { it.volume }.average()
    }

    /** 寻找局部低点（swing low）：比前后各 lookback 根都低的点 */
    private fun findSwingLows(c: List<DailySnapshotEntity>, lookback: Int = 3): List<Int> {
        val lows = mutableListOf<Int>()
        for (i in lookback until c.size - lookback) {
            val isLow = (i - lookback until i + lookback + 1).all { j ->
                j == i || c[i].low <= c[j].low
            }
            if (isLow) lows.add(i)
        }
        return lows
    }

    /** 寻找局部高点（swing high）：比前后各 lookback 根都高的点 */
    private fun findSwingHighs(c: List<DailySnapshotEntity>, lookback: Int = 3): List<Int> {
        val highs = mutableListOf<Int>()
        for (i in lookback until c.size - lookback) {
            val isHigh = (i - lookback until i + lookback + 1).all { j ->
                j == i || c[i].high >= c[j].high
            }
            if (isHigh) highs.add(i)
        }
        return highs
    }

    /** 检测跳空缺口：i 和 i-1 之间是否有 gap */
    private fun hasGapDown(c: List<DailySnapshotEntity>, i: Int): Boolean {
        if (i <= 0) return false
        return c[i].high < c[i - 1].low
    }

    private fun hasGapUp(c: List<DailySnapshotEntity>, i: Int): Boolean {
        if (i <= 0) return false
        return c[i].low > c[i - 1].high
    }

    /** 计算涨跌幅（百分比） */
    private fun pctChange(from: Double, to: Double): Double = (to - from) / from * 100

    /** 判断是否为涨停（涨幅 >= 9.5%，考虑 ST 5% 和科创/创业 20%） */
    private fun isLimitUp(c: List<DailySnapshotEntity>, i: Int): Boolean {
        if (i <= 0) return false
        val prevClose = c[i - 1].close
        if (prevClose <= 0) return false
        val change = (c[i].close - prevClose) / prevClose
        return change >= 0.095
    }

    // ═══════════════════════════════════════════════════
    //  复杂趋势形态侦测（10-30+ 根 K 线）
    // ════════════════════════════════════════════════════

    /**
     * W底 / 双重底（W-Bottom）— 看多反转
     *
     * 在 30 日窗口内寻找两个大致持平的低点，中间有高点（颈线）。
     * 右底 >= 左底（略高更佳），最近价格突破颈线。
     */
    private fun detectWBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)
        val lows = findSwingLows(window, 3)
        if (lows.size < 2) return null

        // 找最近的两个低点
        val lastTwoLows = lows.takeLast(2)
        val leftLow = window[lastTwoLows[0]].low
        val rightLow = window[lastTwoLows[1]].low
        // 两个低点差距 < 5%
        if (kotlin.math.abs(leftLow - rightLow) / leftLow > 0.05) return null
        // 右底 >= 左底 * 0.98（允许略低）
        if (rightLow < leftLow * 0.98) return null

        // 找中间高点（颈线）
        val betweenStart = lastTwoLows[0]
        val betweenEnd = lastTwoLows[1]
        if (betweenEnd - betweenStart < 3) return null  // 间隔太短
        val neckHigh = window.subList(betweenStart, betweenEnd + 1).maxOf { it.high }

        // 最近价格突破颈线
        val lastClose = window.last().close
        if (lastClose < neckHigh * 0.98) return null

        // 右侧成交量 > 左侧（资金进场）
        val leftVol = calcAvgVol(window, betweenStart - 5.coerceAtLeast(0), betweenStart)
        val rightVol = calcAvgVol(window, betweenEnd, window.size)
        if (rightVol < leftVol * 0.8) return null  // 右侧量能不足

        return PatternMatch(
            patternName = "W底（双重底）",
            direction = Direction.BULLISH,
            description = "两个低点大致持平，右侧放量突破颈线——主力洗盘结束，反转确认",
            strength = 4
        )
    }

    /**
     * 双重顶 / M头（Double Top）— 看空反转
     */
    private fun detectDoubleTop(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)
        val highs = findSwingHighs(window, 3)
        if (highs.size < 2) return null

        val lastTwoHighs = highs.takeLast(2)
        val leftHigh = window[lastTwoHighs[0]].high
        val rightHigh = window[lastTwoHighs[1]].high
        // 两个高点差距 < 3%
        if (kotlin.math.abs(leftHigh - rightHigh) / leftHigh > 0.03) return null

        // 中间低点（颈线）
        val betweenStart = lastTwoHighs[0]
        val betweenEnd = lastTwoHighs[1]
        if (betweenEnd - betweenStart < 3) return null
        val neckLow = window.subList(betweenStart, betweenEnd + 1).minOf { it.low }

        // 最近价格跌破颈线
        val lastClose = window.last().close
        if (lastClose > neckLow * 1.02) return null

        // 右顶成交量 < 左顶（量价背离）
        val leftVol = calcAvgVol(window, (lastTwoHighs[0] - 3).coerceAtLeast(0), lastTwoHighs[0] + 1)
        val rightVol = calcAvgVol(window, (lastTwoHighs[1] - 3).coerceAtLeast(0), lastTwoHighs[1] + 1)
        if (rightVol >= leftVol) return null  // 没有量价背离

        return PatternMatch(
            patternName = "双重顶（M头）",
            direction = Direction.BEARISH,
            description = "两次冲高到相近水平后回落，跌破颈线——顶部反转确认，注意止损",
            strength = 4
        )
    }

    /**
     * 头肩顶（Head & Shoulders Top）— 看空反转
     *
     * 三个高峰：左肩→头（最高）→右肩，右肩成交量 < 左肩。
     */
    private fun detectHeadShouldersTop(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(35)
        val highs = findSwingHighs(window, 4)
        if (highs.size < 3) return null

        val lastThree = highs.takeLast(3)
        val leftShoulder = window[lastThree[0]].high
        val head = window[lastThree[1]].high
        val rightShoulder = window[lastThree[2]].high

        // 头最高
        if (head <= leftShoulder || head <= rightShoulder) return null
        // 两肩大致等高（差距 < 5%）
        if (kotlin.math.abs(leftShoulder - rightShoulder) / leftShoulder > 0.05) return null

        // 颈线（两个低谷的连线）
        val low1 = window.subList(lastThree[0], lastThree[1] + 1).minOf { it.low }
        val low2 = window.subList(lastThree[1], lastThree[2] + 1).minOf { it.low }
        val neckline = (low1 + low2) / 2

        // 最近跌破颈线
        val lastClose = window.last().close
        if (lastClose > neckline * 1.02) return null

        // 右肩成交量 < 左肩（量价背离）
        val leftVol = calcAvgVol(window, (lastThree[0] - 3).coerceAtLeast(0), lastThree[0] + 1)
        val rightVol = calcAvgVol(window, (lastThree[2] - 3).coerceAtLeast(0), lastThree[2] + 1)
        if (rightVol >= leftVol * 0.8) return null

        return PatternMatch(
            patternName = "头肩顶",
            direction = Direction.BEARISH,
            description = "三高峰形态，头最高两肩等高，右肩缩量跌破颈线——经典逃顶信号",
            strength = 5
        )
    }

    /**
     * 头肩底（Head & Shoulders Bottom）— 看多反转
     */
    private fun detectHeadShouldersBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(35)
        val lows = findSwingLows(window, 4)
        if (lows.size < 3) return null

        val lastThree = lows.takeLast(3)
        val leftShoulder = window[lastThree[0]].low
        val head = window[lastThree[1]].low
        val rightShoulder = window[lastThree[2]].low

        // 头最低
        if (head >= leftShoulder || head >= rightShoulder) return null
        // 两肩大致等高
        if (kotlin.math.abs(leftShoulder - rightShoulder) / leftShoulder > 0.05) return null

        // 颈线
        val high1 = window.subList(lastThree[0], lastThree[1] + 1).maxOf { it.high }
        val high2 = window.subList(lastThree[1], lastThree[2] + 1).maxOf { it.high }
        val neckline = (high1 + high2) / 2

        // 最近突破颈线
        val lastClose = window.last().close
        if (lastClose < neckline * 0.98) return null

        // 右肩成交量 > 左肩（资金进场）
        val leftVol = calcAvgVol(window, (lastThree[0] - 3).coerceAtLeast(0), lastThree[0] + 1)
        val rightVol = calcAvgVol(window, (lastThree[2] - 3).coerceAtLeast(0), lastThree[2] + 1)
        if (rightVol < leftVol * 0.8) return null

        return PatternMatch(
            patternName = "头肩底",
            direction = Direction.BULLISH,
            description = "三低谷形态，头最低两肩等高，右肩放量突破颈线——底部反转确认",
            strength = 5
        )
    }

    /**
     * 圆弧底（Rounded Bottom）— 看多反转
     *
     * 股价缓跌→底部盘整→缓涨，形成 U 形。左侧阴线越来越小，底部缩量，右侧放量突破。
     */
    private fun detectRoundedBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)
        val size = window.size

        // 分三段：左侧(0-1/3)、底部(1/3-2/3)、右侧(2/3-末尾)
        val third = size / 3
        if (third < 5) return null

        val leftPart = window.subList(0, third)
        val midPart = window.subList(third, third * 2)
        val rightPart = window.subList(third * 2, size)

        // 左侧：整体下跌趋势（首尾比较）
        if (leftPart.last().close >= leftPart.first().close * 0.97) return null

        // 底部：波动收窄（最高-最低 < 左侧的 60%）
        val leftRange = leftPart.maxOf { it.high } - leftPart.minOf { it.low }
        val midRange = midPart.maxOf { it.high } - midPart.minOf { it.low }
        if (midRange > leftRange * 0.7) return null  // 底部波动太大

        // 底部缩量
        val leftVol = calcAvgVol(window, 0, third)
        val midVol = calcAvgVol(window, third, third * 2)
        if (midVol >= leftVol * 0.8) return null  // 底部没有缩量

        // 右侧：低点逐步抬高
        val rightLows = findSwingLows(rightPart, 2)
        if (rightLows.size >= 2) {
            val rl1 = rightPart[rightLows[rightLows.size - 2]].low
            val rl2 = rightPart[rightLows.last()].low
            if (rl2 < rl1 * 0.98) return null  // 右侧低点没有抬高
        }

        // 右侧放量突破
        val rightVol = calcAvgVol(window, third * 2, size)
        val lastClose = window.last().close
        val leftHigh = leftPart.maxOf { it.high }
        if (lastClose < leftHigh * 0.95 || rightVol < midVol * 1.3) return null

        return PatternMatch(
            patternName = "圆弧底",
            direction = Direction.BULLISH,
            description = "U形圆弧底部，左侧缓跌缩量→底部盘整→右侧放量突破——主力吸筹完成",
            strength = 4
        )
    }

    /**
     * V形底（V-Bottom Reversal）— 看多反转
     *
     * 急跌（3-5日跌幅>15%）→ 急涨（速度与下跌相当），底部放量。
     */
    private fun detectVBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(20)

        // 找最低点
        var minIdx = 0
        var minPrice = window[0].low
        for (i in window.indices) {
            if (window[i].low < minPrice) { minPrice = window[i].low; minIdx = i }
        }
        // 最低点不能太靠近末尾（需要右侧反弹空间）
        if (minIdx < 3 || minIdx > window.size - 4) return null

        // 左侧急跌：从起点到最低点跌幅 > 12%
        val dropPct = pctChange(window[0].close, minPrice)
        if (dropPct > -12) return null

        // 右侧急涨：从最低点到末尾涨幅 > 左侧跌幅的 70%
        val reboundPct = pctChange(minPrice, window.last().close)
        if (reboundPct < kotlin.math.abs(dropPct) * 0.7) return null

        // 底部放量：最低点附近 3 日成交量 > 左侧平均的 1.5 倍
        val bottomVol = calcAvgVol(window, (minIdx - 1).coerceAtLeast(0), (minIdx + 2).coerceAtMost(window.size))
        val leftVol = calcAvgVol(window, 0, minIdx)
        if (bottomVol < leftVol * 1.3) return null

        // 右侧连续阳线
        var consecutiveUp = 0
        for (i in (minIdx + 1) until window.size) {
            if (window[i].close > window[i].open) consecutiveUp++ else consecutiveUp = 0
        }
        if (consecutiveUp < 2) return null

        return PatternMatch(
            patternName = "V形底",
            direction = Direction.BULLISH,
            description = "急跌后急涨形成V形反转，底部放量——恐慌盘被主力接纳，可能快速反弹",
            strength = 3
        )
    }

    /**
     * 黄金坑（Golden Pit Wash）— 看多
     *
     * 前期上涨→突然急跌挖坑（缩量）→坑底缩量盘整→放量拉升突破前高。
     */
    private fun detectGoldenPit(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)

        // 找最低点
        var minIdx = 0
        var minPrice = window[0].low
        for (i in window.indices) {
            if (window[i].low < minPrice) { minPrice = window[i].low; minIdx = i }
        }
        if (minIdx < 5 || minIdx > window.size - 5) return null

        // 前期有上涨（最低点前 5 日的高点 > 最低点前 10 日的低点）
        val preHigh = window.subList(0, minIdx).maxOf { it.high }
        if (minIdx < 10) return null
        val preLow = window.subList(0, minIdx - 5).minOf { it.low }
        if (preHigh < preLow * 1.05) return null  // 前期没有上涨趋势

        // 急跌但缩量：跌到坑底的过程成交量 < 前期的 70%
        val dropVol = calcAvgVol(window, (minIdx - 5).coerceAtLeast(0), minIdx)
        val preVol = calcAvgVol(window, 0, (minIdx - 5).coerceAtLeast(1))
        if (dropVol >= preVol * 0.8) return null  // 下跌放量=真下跌，非黄金坑

        // 坑底缩量（地量）
        val pitVol = calcAvgVol(window, minIdx, (minIdx + 3).coerceAtMost(window.size))
        if (pitVol >= preVol * 0.6) return null

        // 放量拉升突破前高
        val lastClose = window.last().close
        if (lastClose < preHigh * 0.95) return null
        val riseVol = calcAvgVol(window, (window.size - 5).coerceAtLeast(0), window.size)
        if (riseVol < pitVol * 1.5) return null

        return PatternMatch(
            patternName = "黄金坑",
            direction = Direction.BULLISH,
            description = "缩量急跌挖坑后放量突破前高——主力洗盘结束，黄金坑确认，最佳买点",
            strength = 4
        )
    }

    /**
     * 岛形反转（Island Reversal）— 看多反转
     *
     * 向下跳空缺口→岛屿盘整 3-10 日→向上跳空缺口。
     */
    private fun detectIslandReversal(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(25)

        // 从末尾往前找向上跳空
        var gapUpIdx = -1
        for (i in window.size - 1 downTo 3) {
            if (hasGapUp(window, i)) { gapUpIdx = i; break }
        }
        if (gapUpIdx < 0) return null

        // 从 gapUpIdx 往前找向下跳空
        var gapDownIdx = -1
        for (i in gapUpIdx - 1 downTo 1) {
            if (hasGapDown(window, i)) { gapDownIdx = i; break }
        }
        if (gapDownIdx < 0) return null

        // 岛屿区间：gapDownIdx 到 gapUpIdx 之间
        val islandLen = gapUpIdx - gapDownIdx
        if (islandLen < 2 || islandLen > 10) return null  // 岛屿太短或太长

        // 两个缺口价格区间大致重叠
        val gapDownHigh = window[gapDownIdx - 1].low  // 缺口前的低点
        val gapDownLow = window[gapDownIdx].high       // 缺口后的高点
        val gapUpLow = window[gapUpIdx - 1].high       // 缺口前的高点
        val gapUpHigh = window[gapUpIdx].low           // 缺口后的低点
        // 缺口区间重叠度 > 50%
        val overlap = kotlin.math.min(gapDownHigh, gapUpHigh) - kotlin.math.max(gapDownLow, gapUpLow)
        val totalRange = kotlin.math.max(gapDownHigh, gapUpHigh) - kotlin.math.min(gapDownLow, gapUpLow)
        if (totalRange > 0 && overlap / totalRange < 0.3) return null

        // 右侧缺口放量
        val postVol = calcAvgVol(window, gapUpIdx, window.size)
        val islandVol = calcAvgVol(window, gapDownIdx, gapUpIdx)
        if (postVol < islandVol * 1.2) return null

        return PatternMatch(
            patternName = "岛形反转",
            direction = Direction.BULLISH,
            description = "向下跳空→岛屿盘整→向上跳空，双缺口夹岛——强烈反转信号，可靠性极高",
            strength = 5
        )
    }

    /**
     * 上升旗形（Ascending Flag / Bull Flag）— 看多持续
     *
     * 急涨（旗杆）→ 小幅回调整理（旗面，向下倾斜通道）→ 突破上涨。
     */
    private fun detectAscendingFlag(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(20)

        // 找旗杆起点：从末尾往前找最近的急涨段
        // 旗杆：连续 3-5 根阳线，涨幅 > 12%
        var poleStart = -1
        for (i in window.size - 6 downTo 0) {
            if (i + 4 < window.size) {
                val segment = window.subList(i, i + 5)
                val upDays = segment.count { it.close > it.open }
                val gain = pctChange(segment.first().open, segment.last().close)
                if (upDays >= 3 && gain > 12) { poleStart = i; break }
            }
        }
        if (poleStart < 0) return null

        val poleEnd = poleStart + 4
        val poleHigh = window.subList(poleStart, poleEnd + 1).maxOf { it.high }

        // 旗面：poleEnd 之后的整理，高点逐步降低（向下倾斜）
        val flagPart = window.subList(poleEnd + 1, window.size)
        if (flagPart.size < 3) return null

        // 旗面高点逐步降低
        val flagHighs = findSwingHighs(flagPart, 1)
        if (flagHighs.size >= 2) {
            val fh1 = flagPart[flagHighs[flagHighs.size - 2]].high
            val fh2 = flagPart[flagHighs.last()].high
            if (fh2 >= fh1) return null  // 高点没有降低
        }

        // 旗面成交量萎缩
        val poleVol = calcAvgVol(window, poleStart, poleEnd + 1)
        val flagVol = calcAvgVol(window, poleEnd + 1, window.size)
        if (flagVol >= poleVol * 0.8) return null

        // 突破旗面高点
        val flagHigh = flagPart.maxOf { it.high }
        val lastClose = window.last().close
        if (lastClose < flagHigh * 0.98) return null

        return PatternMatch(
            patternName = "上升旗形",
            direction = Direction.BULLISH,
            description = "急涨后小幅回调整理，缩量旗面突破——多头蓄力完成，继续上涨",
            strength = 4
        )
    }

    /**
     * 下降三角形（Descending Triangle）— 看空
     *
     * 高点逐级降低（下降趋势线），低点大致持平（水平支撑），破位放量。
     */
    private fun detectDescendingTriangle(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(25)

        val highs = findSwingHighs(window, 2)
        val lows = findSwingLows(window, 2)
        if (highs.size < 3 || lows.size < 2) return null

        // 高点逐级降低
        val last3Highs = highs.takeLast(3)
        if (window[last3Highs[1]].high >= window[last3Highs[0]].high * 0.99) return null
        if (window[last3Highs[2]].high >= window[last3Highs[1]].high * 0.99) return null

        // 低点大致持平（差距 < 3%）
        val last2Lows = lows.takeLast(2)
        val low1 = window[last2Lows[0]].low
        val low2 = window[last2Lows[1]].low
        if (kotlin.math.abs(low1 - low2) / low1 > 0.03) return null

        // 最近跌破支撑
        val supportLevel = (low1 + low2) / 2
        val lastClose = window.last().close
        if (lastClose > supportLevel * 1.01) return null

        // 破位放量
        val breakVol = calcAvgVol(window, window.size - 3, window.size)
        val preVol = calcAvgVol(window, window.size - 8, window.size - 3)
        if (breakVol < preVol * 1.2) return null

        return PatternMatch(
            patternName = "下降三角形",
            direction = Direction.BEARISH,
            description = "高点逐级降低、支撑多次测试后放量破位——空头蓄力完成，加速下跌",
            strength = 4
        )
    }

    /**
     * 扩散三角形（Expanding Triangle / Megaphone）— 看空（顶部）
     *
     * 高点越来越高、低点越来越低，波动区间扩大，成交量混乱。
     */
    private fun detectExpandingTriangle(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(25)

        val highs = findSwingHighs(window, 2)
        val lows = findSwingLows(window, 2)
        if (highs.size < 3 || lows.size < 3) return null

        // 高点越来越高
        val last3Highs = highs.takeLast(3)
        if (window[last3Highs[1]].high <= window[last3Highs[0]].high * 1.01) return null
        if (window[last3Highs[2]].high <= window[last3Highs[1]].high * 1.01) return null

        // 低点越来越低
        val last3Lows = lows.takeLast(3)
        if (window[last3Lows[1]].low >= window[last3Lows[0]].low * 0.99) return null
        if (window[last3Lows[2]].low >= window[last3Lows[1]].low * 0.99) return null

        // 波动扩大：最后 5 日的 range > 前 5 日的 range
        val earlyRange = window.subList(0, 5).maxOf { it.high } - window.subList(0, 5).minOf { it.low }
        val lateRange = window.takeLast(5).maxOf { it.high } - window.takeLast(5).minOf { it.low }
        if (lateRange < earlyRange * 1.3) return null

        // 成交量大而混乱（标准差大）
        val vols = window.map { it.volume }
        val avgVol = vols.average()
        val volStdDev = kotlin.math.sqrt(vols.map { (it - avgVol) * (it - avgVol) }.average())
        if (volStdDev < avgVol * 0.3) return null  // 成交量不够混乱

        return PatternMatch(
            patternName = "扩散三角形",
            direction = Direction.BEARISH,
            description = "高低点持续扩大、成交量混乱——多空分歧巨大，常见于顶部反转",
            strength = 3
        )
    }

    /**
     * 杯柄形态（Cup and Handle）— 看多
     *
     * 圆弧底（杯身）+ 小幅回调（杯柄）+ 放量突破。
     */
    private fun detectCupAndHandle(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(35)

        // 先检测是否有圆弧底特征（简化版）
        val size = window.size
        val third = size / 3
        if (third < 6) return null

        // 杯身：左侧下跌 + 底部盘整 + 右侧回升
        val leftPart = window.subList(0, third)
        val midPart = window.subList(third, third * 2)
        val rightPart = window.subList(third * 2, size)

        // 左侧下跌
        if (leftPart.last().close >= leftPart.first().close * 0.97) return null
        // 底部波动收窄
        val leftRange = leftPart.maxOf { it.high } - leftPart.minOf { it.low }
        val midRange = midPart.maxOf { it.high } - midPart.minOf { it.low }
        if (midRange > leftRange * 0.7) return null

        // 杯身深度
        val cupHigh = leftPart.maxOf { it.high }
        val cupLow = midPart.minOf { it.low }
        val cupDepth = (cupHigh - cupLow) / cupHigh

        // 杯柄：右侧最后 1/4 部分小幅回调
        val handleStart = size - size / 4
        val handlePart = window.subList(handleStart, size)
        if (handlePart.size < 3) return null

        // 杯柄回调 < 杯深的 1/2
        val handleHigh = handlePart.maxOf { it.high }
        val handleLow = handlePart.minOf { it.low }
        val handleDepth = (handleHigh - handleLow) / cupHigh
        if (handleDepth > cupDepth * 0.5) return null

        // 杯柄缩量
        val cupVol = calcAvgVol(window, third, third * 2)
        val handleVol = calcAvgVol(window, handleStart, size)
        if (handleVol >= cupVol * 1.2) return null

        // 突破杯柄高点放量
        val lastClose = window.last().close
        if (lastClose < cupHigh * 0.95) return null
        val breakVol = calcAvgVol(window, size - 3, size)
        if (breakVol < handleVol * 1.3) return null

        return PatternMatch(
            patternName = "杯柄形态",
            direction = Direction.BULLISH,
            description = "U形杯身+小幅杯柄回调后放量突破——经典中长期底部形态",
            strength = 4
        )
    }

    /**
     * 底部双雄（Bottom Double Heroes）— 看多
     *
     * 底部区域出现两次涨停/大阳线，中间有缩量回调洗盘。
     */
    private fun detectBottomDoubleHeroes(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)

        // 找涨停/大阳线位置（涨幅 > 7%）
        val bigYangDays = mutableListOf<Int>()
        for (i in 1 until window.size) {
            val prevClose = window[i - 1].close
            if (prevClose > 0) {
                val change = (window[i].close - prevClose) / prevClose
                if (change > 0.07) bigYangDays.add(i)
            }
        }
        if (bigYangDays.size < 2) return null

        // 找最近的两次大阳线
        val lastTwo = bigYangDays.takeLast(2)
        val firstYang = lastTwo[0]
        val secondYang = lastTwo[1]

        // 间隔 3-10 日
        val gap = secondYang - firstYang
        if (gap < 3 || gap > 12) return null

        // 两次都在底部区域（价格 < 30 日最高点的 85%）
        val maxPrice = window.maxOf { it.high }
        if (window[firstYang].close > maxPrice * 0.9 || window[secondYang].close > maxPrice * 0.9) return null

        // 中间回调缩量
        val betweenVol = calcAvgVol(window, firstYang + 1, secondYang)
        val yang1Vol = window[firstYang].volume
        val yang2Vol = window[secondYang].volume
        if (betweenVol >= yang1Vol * 0.7) return null  // 回调没有缩量

        // 第二次量能 >= 第一次
        if (yang2Vol < yang1Vol * 0.7) return null

        // 第二次后突破前高
        val preHigh = window.subList(0, firstYang).maxOf { it.high }
        val lastClose = window.last().close
        if (lastClose < preHigh * 0.95) return null

        return PatternMatch(
            patternName = "底部双雄",
            direction = Direction.BULLISH,
            description = "底部两次涨停/大阳线，中间缩量洗盘——主力试盘后正式进场",
            strength = 4
        )
    }
}
