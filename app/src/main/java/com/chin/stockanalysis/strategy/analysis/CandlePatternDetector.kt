package com.chin.stockanalysis.strategy.analysis

import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity

/**
 * ## K 線經典形態偵測器
 *
 * 偵測上升三法、下降三法、早晨之星、黃昏之星、紅三兵、三烏鴉等經典形態。
 * 輸入為最近 N 日的 OHLC 數據（ASC：舊→新），輸出為匹配到的形態列表。
 *
 * 用於 Pipeline 中對候選股進行形態掃描，在報告中重點提醒買入/賣出。
 */
object CandlePatternDetector {

    /** 形態方向 */
    enum class Direction(val label: String, val signal: String) {
        BULLISH("看多", "買入"),
        BEARISH("看空", "賣出")
    }

    /** 偵測結果 */
    data class PatternMatch(
        val patternName: String,      // 上升三法
        val direction: Direction,
        val description: String,      // 簡要描述
        val strength: Int             // 強度 1-5（越高越可靠）
    )

    /**
     * 對單只股票的 K 線進行形態偵測。
     *
     * @param candles 最近 N 日 K 線（ASC：舊→新），建議至少 10 根
     * @return 匹配到的形態列表（可能多個），按強度降序
     */
    fun detect(candles: List<DailySnapshotEntity>): List<PatternMatch> {
        if (candles.size < 5) return emptyList()
        val results = mutableListOf<PatternMatch>()

        // 取最後幾根 K 線偵測（只看最近形成的形態）
        val n = candles.size
        val c = candles // shorthand

        detectRisingThreeMethods(c, n)?.let { results.add(it) }
        detectFallingThreeMethods(c, n)?.let { results.add(it) }
        detectMorningStar(c, n)?.let { results.add(it) }
        detectEveningStar(c, n)?.let { results.add(it) }
        detectThreeWhiteSoldiers(c, n)?.let { results.add(it) }
        detectThreeBlackCrows(c, n)?.let { results.add(it) }
        // 新增形態
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

        // ═══ 複雜趨勢形態（需要 30+ 根 K 線） ═══
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
     * 批量偵測多只股票。
     * @param stockCandles Map<stockCode, List<DailySnapshotEntity>>（ASC）
     * @return Map<stockCode, List<PatternMatch>>（只含有形態的股票）
     */
    fun detectBatch(stockCandles: Map<String, List<DailySnapshotEntity>>): Map<String, List<PatternMatch>> {
        return stockCandles.mapNotNull { (code, candles) ->
            val patterns = detect(candles)
            if (patterns.isNotEmpty()) code to patterns else null
        }.toMap()
    }

    // ════════════════════════════════════════════════════
    //  各形態偵測邏輯
    // ════════════════════════════════════════════════════

    /**
     * 上升三法（Rising Three Methods）— 5 根 K 線，看多持續
     *
     * Day1: 長陽線（實體 > 平均實體的 1.5 倍）
     * Day2-4: 3 根小陰線（實體 < Day1 實體的 50%），收盤在 Day1 範圍內
     * Day5: 長陽線，收盤 > Day1 收盤
     */
    private fun detectRisingThreeMethods(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d1 = c[n - 5]; val d2 = c[n - 4]; val d3 = c[n - 3]; val d4 = c[n - 2]; val d5 = c[n - 1]

        val body1 = d1.close - d1.open  // 陽線 > 0
        val body5 = d5.close - d5.open
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        // Day1: 長陽
        if (body1 <= 0 || body1 < avgBody * 1.3) return null
        // Day5: 長陽，收盤 > Day1 收盤
        if (body5 <= 0 || d5.close <= d1.close) return null
        // Day2-4: 小陰線，收盤在 Day1 範圍內（open~close 之間）
        val smallBody = body1 * 0.6
        for (d in listOf(d2, d3, d4)) {
            val body = d.close - d.open
            if (body > 0 && kotlin.math.abs(body) > smallBody) return null  // 不是小陰
            if (d.close < d1.open || d.close > d1.close) return null        // 超出 Day1 範圍
        }

        return PatternMatch(
            patternName = "上升三法",
            direction = Direction.BULLISH,
            description = "長陽後三根小陰回調未破低，第五根長陽突破——多頭蓄力完成，趨勢延續",
            strength = 4
        )
    }

    /**
     * 下降三法（Falling Three Methods）— 5 根 K 線，看空持續
     *
     * Day1: 長陰線
     * Day2-4: 3 根小陽線（反彈無力），收盤在 Day1 範圍內
     * Day5: 長陰線，收盤 < Day1 收盤
     */
    private fun detectFallingThreeMethods(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d1 = c[n - 5]; val d2 = c[n - 4]; val d3 = c[n - 3]; val d4 = c[n - 2]; val d5 = c[n - 1]

        val body1 = d1.open - d1.close  // 陰線 > 0
        val body5 = d5.open - d5.close
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        // Day1: 長陰
        if (body1 <= 0 || body1 < avgBody * 1.3) return null
        // Day5: 長陰，收盤 < Day1 收盤
        if (body5 <= 0 || d5.close >= d1.close) return null
        // Day2-4: 小陽線，收盤在 Day1 範圍內
        val smallBody = body1 * 0.6
        for (d in listOf(d2, d3, d4)) {
            val body = d.open - d.close  // 陰線為正
            if (body > 0 && kotlin.math.abs(d.close - d.open) > smallBody) return null
            if (d.close > d1.open || d.close < d1.close) return null
        }

        return PatternMatch(
            patternName = "下降三法",
            direction = Direction.BEARISH,
            description = "長陰後三根小陽反彈未破高，第五根長陰跌破——空頭蓄力完成，跌勢延續",
            strength = 4
        )
    }

    /**
     * 早晨之星（Morning Star）— 3 根 K 線，看多反轉
     *
     * Day1: 長陰線（跌幅 > 2%）
     * Day2: 小實體（十字星/小陰小陽），與 Day1 有向下跳空
     * Day3: 長陽線，收盤深入 Day1 實體（> Day1 中點）
     */
    private fun detectMorningStar(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 3) return null
        val d1 = c[n - 3]; val d2 = c[n - 2]; val d3 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        val body1 = d1.open - d1.close   // 陰 > 0
        val body2 = kotlin.math.abs(d2.close - d2.open)
        val body3 = d3.close - d3.open   // 陽 > 0

        // Day1: 長陰
        if (body1 <= 0 || body1 < avgBody * 1.2) return null
        // Day2: 小實體（< 平均的 40%）
        if (body2 > avgBody * 0.4) return null
        // Day3: 長陽，收盤 > Day1 實體中點
        val mid1 = (d1.open + d1.close) / 2
        if (body3 <= 0 || d3.close < mid1) return null
        // Day2 的低點低於 Day1 收盤（跳空或觸底）
        if (d2.low > d1.close) return null

        return PatternMatch(
            patternName = "早晨之星",
            direction = Direction.BULLISH,
            description = "長陰後出現小實體星線，第三根長陽收復失地——底部反轉信號",
            strength = 5
        )
    }

    /**
     * 黃昏之星（Evening Star）— 3 根 K 線，看空反轉
     *
     * Day1: 長陽線
     * Day2: 小實體（星線），與 Day1 有向上跳空
     * Day3: 長陰線，收盤深入 Day1 實體
     */
    private fun detectEveningStar(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 3) return null
        val d1 = c[n - 3]; val d2 = c[n - 2]; val d3 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        val body1 = d1.close - d1.open   // 陽 > 0
        val body2 = kotlin.math.abs(d2.close - d2.open)
        val body3 = d3.open - d3.close   // 陰 > 0

        // Day1: 長陽
        if (body1 <= 0 || body1 < avgBody * 1.2) return null
        // Day2: 小實體
        if (body2 > avgBody * 0.4) return null
        // Day3: 長陰，收盤 < Day1 實體中點
        val mid1 = (d1.open + d1.close) / 2
        if (body3 <= 0 || d3.close > mid1) return null
        // Day2 高點 > Day1 收盤（星線在上方）
        if (d2.high < d1.close) return null

        return PatternMatch(
            patternName = "黃昏之星",
            direction = Direction.BEARISH,
            description = "長陽後出現小實體星線，第三根長陰吞噬漲幅——頂部反轉信號",
            strength = 5
        )
    }

    /**
     * 紅三兵（Three White Soldiers）— 3 根連續長陽，看多
     *
     * 連續 3 根陽線，每根收盤 > 前一根收盤，實體逐漸放大或穩定，無長上影線。
     */
    private fun detectThreeWhiteSoldiers(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 3) return null
        val d1 = c[n - 3]; val d2 = c[n - 2]; val d3 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        val days = listOf(d1, d2, d3)
        // 全部陽線
        if (days.any { it.close <= it.open }) return null
        // 收盤逐步抬高
        if (!(d2.close > d1.close && d3.close > d2.close)) return null
        // 開盤在前一根實體內（非跳空過大）
        if (d2.open < d1.open || d3.open < d2.open) return null
        // 實體不能太小（> 平均的 60%）
        if (days.any { (it.close - it.open) < avgBody * 0.6 }) return null
        // 無過長上影線（上影 < 實體的 30%）
        if (days.any { (it.high - it.close) > (it.close - it.open) * 0.3 }) return null

        return PatternMatch(
            patternName = "紅三兵",
            direction = Direction.BULLISH,
            description = "連續三根實體陽線逐步走高，無上影壓力——多頭強勢推進",
            strength = 3
        )
    }

    /**
     * 三烏鴉（Three Black Crows）— 3 根連續長陰，看空
     *
     * 連續 3 根陰線，每根收盤 < 前一根收盤，實體穩定，無長下影線。
     */
    private fun detectThreeBlackCrows(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 3) return null
        val d1 = c[n - 3]; val d2 = c[n - 2]; val d3 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()

        val days = listOf(d1, d2, d3)
        // 全部陰線
        if (days.any { it.close >= it.open }) return null
        // 收盤逐步走低
        if (!(d2.close < d1.close && d3.close < d2.close)) return null
        // 開盤在前一根實體內
        if (d2.open > d1.open || d3.open > d2.open) return null
        // 實體不能太小
        if (days.any { (it.open - it.close) < avgBody * 0.6 }) return null
        // 無過長下影線
        if (days.any { (it.low - it.close) < -(it.open - it.close) * 0.3 }) return null

        return PatternMatch(
            patternName = "三烏鴉",
            direction = Direction.BEARISH,
            description = "連續三根實體陰線逐步走低，無下影支撐——空頭強勢打壓",
            strength = 3
        )
    }

    // ════════════════════════════════════════════════════
    //  新增形態偵測（吞沒/孕線/十字星/錘子線/射擊之星/刺透/烏雲蓋頂）
    // ════════════════════════════════════════════════════

    /**
     * 看漲吞沒（Bullish Engulfing）— 2 根 K 線，看多反轉
     *
     * Day1: 陰線
     * Day2: 陽線，實體完全覆蓋 Day1 實體（open <= Day1.open, close >= Day1.close）
     */
    private fun detectBullishEngulfing(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        // Day1 陰線
        if (d1.close >= d1.open) return null
        // Day2 陽線
        if (d2.close <= d2.open) return null
        // Day2 實體完全覆蓋 Day1 實體
        if (d2.open > d1.open || d2.close < d1.close) return null
        // Day2 實體不能太小
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        if ((d2.close - d2.open) < avgBody * 0.8) return null

        return PatternMatch(
            patternName = "看漲吞沒",
            direction = Direction.BULLISH,
            description = "陽線完全包裹前一根陰線實體——多頭強勢反轉，買盤壓制賣盤",
            strength = 4
        )
    }

    /**
     * 看跌吞沒（Bearish Engulfing）— 2 根 K 線，看空反轉
     *
     * Day1: 陽線
     * Day2: 陰線，實體完全覆蓋 Day1 實體
     */
    private fun detectBearishEngulfing(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        // Day1 陽線
        if (d1.close <= d1.open) return null
        // Day2 陰線
        if (d2.close >= d2.open) return null
        // Day2 實體完全覆蓋 Day1 實體
        if (d2.open < d1.open || d2.close > d1.close) return null
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        if ((d2.open - d2.close) < avgBody * 0.8) return null

        return PatternMatch(
            patternName = "看跌吞沒",
            direction = Direction.BEARISH,
            description = "陰線完全包裹前一根陽線實體——空頭強勢反轉，賣盤壓制買盤",
            strength = 4
        )
    }

    /**
     * 看漲孕線（Bullish Harami）— 2 根 K 線，看多反轉
     *
     * Day1: 長陰線
     * Day2: 小陽線，實體完全在 Day1 實體範圍內
     */
    private fun detectBullishHarami(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        // Day1 長陰線
        if (d1.close >= d1.open) return null
        if ((d1.open - d1.close) < avgBody * 1.0) return null
        // Day2 小陽線，實體在 Day1 實體內
        if (d2.close <= d2.open) return null
        if (d2.open < d1.close || d2.close > d1.open) return null
        if ((d2.close - d2.open) > avgBody * 0.6) return null

        return PatternMatch(
            patternName = "看漲孕線",
            direction = Direction.BULLISH,
            description = "大陰線後小陽線藏於其實體內——下跌動能減弱，可能反轉",
            strength = 2
        )
    }

    /**
     * 看跌孕線（Bearish Harami）— 2 根 K 線，看空反轉
     *
     * Day1: 長陽線
     * Day2: 小陰線，實體完全在 Day1 實體範圍內
     */
    private fun detectBearishHarami(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        // Day1 長陽線
        if (d1.close <= d1.open) return null
        if ((d1.close - d1.open) < avgBody * 1.0) return null
        // Day2 小陰線，實體在 Day1 實體內
        if (d2.close >= d2.open) return null
        if (d2.open > d1.close || d2.close < d1.open) return null
        if ((d2.open - d2.close) > avgBody * 0.6) return null

        return PatternMatch(
            patternName = "看跌孕線",
            direction = Direction.BEARISH,
            description = "大陽線後小陰線藏於其實體內——上漲動能減弱，可能反轉",
            strength = 2
        )
    }

    /**
     * 十字星（Doji）— 1 根 K 線，中性/反轉信號
     *
     * 實體極小（< 平均實體的 10%），有明顯上下影線
     */
    private fun detectDoji(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 1) return null
        val d = c[n - 1]
        val avgBody = c.takeLast(10).map { kotlin.math.abs(it.close - it.open) }.average()
        if (avgBody <= 0) return null
        val body = kotlin.math.abs(d.close - d.open)
        // 實體極小
        if (body > avgBody * 0.1) return null
        // 需有上下影線
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        if (upperShadow < body * 1.0 || lowerShadow < body * 1.0) return null

        // 判斷趨勢背景：前期下跌→反轉看多；前期上漲→反轉看空
        val recentTrend = if (n >= 5) {
            val prevClose = c[n - 5].close
            when {
                d.close < prevClose * 0.97 -> "下跌"
                d.close > prevClose * 1.03 -> "上漲"
                else -> "橫盤"
            }
        } else "橫盤"

        val (direction, desc) = when (recentTrend) {
            "下跌" -> Direction.BULLISH to "下跌末端出現十字星——多空均衡，可能見底反轉"
            "上漲" -> Direction.BEARISH to "上漲末端出現十字星——多空均衡，可能見頂反轉"
            else -> Direction.BULLISH to "橫盤中出現十字星——方向不明，等待突破確認"
        }

        return PatternMatch(
            patternName = "十字星",
            direction = direction,
            description = desc,
            strength = 2
        )
    }

    /**
     * 錘子線（Hammer）— 1 根 K 線，看多反轉
     *
     * 下影線 > 實體 2 倍，上影線 < 實體 30%，出現在下跌趨勢中
     */
    private fun detectHammer(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d = c[n - 1]
        val body = kotlin.math.abs(d.close - d.open)
        if (body <= 0) return null
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        // 下影線 > 實體 2 倍
        if (lowerShadow < body * 2) return null
        // 上影線 < 實體 30%
        if (upperShadow > body * 0.3) return null
        // 需在下跌趨勢中
        val prevClose = c[n - 5].close
        if (d.close >= prevClose * 0.98) return null

        return PatternMatch(
            patternName = "錘子線",
            direction = Direction.BULLISH,
            description = "下跌末端出現長下影線——下方有買盤承接，可能見底反轉",
            strength = 3
        )
    }

    /**
     * 射擊之星（Shooting Star）— 1 根 K 線，看空反轉
     *
     * 上影線 > 實體 2 倍，下影線 < 實體 30%，出現在上升趨勢中
     */
    private fun detectShootingStar(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d = c[n - 1]
        val body = kotlin.math.abs(d.close - d.open)
        if (body <= 0) return null
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        // 上影線 > 實體 2 倍
        if (upperShadow < body * 2) return null
        // 下影線 < 實體 30%
        if (lowerShadow > body * 0.3) return null
        // 需在上升趨勢中
        val prevClose = c[n - 5].close
        if (d.close <= prevClose * 1.02) return null

        return PatternMatch(
            patternName = "射擊之星",
            direction = Direction.BEARISH,
            description = "上升末端出現長上影線——上方拋壓重，可能見頂反轉",
            strength = 3
        )
    }

    /**
     * 刺透形態（Piercing Line）— 2 根 K 線，看多反轉
     *
     * Day1: 陰線
     * Day2: 陽線，開盤低於 Day1 最低價，收盤深入 Day1 實體一半以上
     */
    private fun detectPiercingLine(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        // Day1 陰線
        if (d1.close >= d1.open) return null
        // Day2 陽線
        if (d2.close <= d2.open) return null
        // Day2 開盤低於 Day1 最低價（低開）
        if (d2.open > d1.low) return null
        // Day2 收盤深入 Day1 實體一半以上
        val mid1 = (d1.open + d1.close) / 2
        if (d2.close < mid1) return null
        // 但不能完全覆蓋（否則是看漲吞沒）
        if (d2.close > d1.open) return null

        return PatternMatch(
            patternName = "刺透形態",
            direction = Direction.BULLISH,
            description = "陰線後陽線低開高走，收復前日一半失地——底部反轉信號",
            strength = 3
        )
    }

    /**
     * 烏雲蓋頂（Dark Cloud Cover）— 2 根 K 線，看空反轉
     *
     * Day1: 陽線
     * Day2: 陰線，開盤高於 Day1 最高價，收盤深入 Day1 實體一半以下
     */
    private fun detectDarkCloudCover(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 2) return null
        val d1 = c[n - 2]; val d2 = c[n - 1]
        // Day1 陽線
        if (d1.close <= d1.open) return null
        // Day2 陰線
        if (d2.close >= d2.open) return null
        // Day2 開盤高於 Day1 最高價（高開）
        if (d2.open < d1.high) return null
        // Day2 收盤深入 Day1 實體一半以下
        val mid1 = (d1.open + d1.close) / 2
        if (d2.close > mid1) return null
        // 但不能完全覆蓋（否則是看跌吞沒）
        if (d2.close < d1.open) return null

        return PatternMatch(
            patternName = "烏雲蓋頂",
            direction = Direction.BEARISH,
            description = "陽線後陰線高開低走，跌入前日實體一半以下——頂部反轉信號",
            strength = 3
        )
    }

    /**
     * 底部倒錘頭（Inverted Hammer at Bottom）— 1 根 K 線，看多反轉
     *
     * 幾何：小實體在低位，長上影線（> 實體 2 倍），下影線 < 實體 30%。
     * 與射擊之星幾何相同，但出現在下跌趨勢末端，含義相反——看多。
     * 機構嘗試拉升，雖被打回但顯示買盤進場意願。
     */
    private fun detectInvertedHammerBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 5) return null
        val d = c[n - 1]
        val body = kotlin.math.abs(d.close - d.open)
        if (body <= 0) return null
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        // 上影線 > 實體 2 倍
        if (upperShadow < body * 2) return null
        // 下影線 < 實體 30%
        if (lowerShadow > body * 0.3) return null
        // 需在下跌趨勢末端（前 5 日跌幅 > 3%）
        val prevClose = c[n - 5].close
        if (d.close >= prevClose * 0.97) return null

        return PatternMatch(
            patternName = "底部倒錘頭",
            direction = Direction.BULLISH,
            description = "下跌末端出現長上影線——有資金嘗試拉升，雖被打回但買盤進場意願增強，可能見底",
            strength = 2
        )
    }

    /**
     * 調整和反彈途中的倒垂線（Inverted Hammer During Pullback）— 1 根 K 線，趨勢延續
     *
     * 幾何同底部倒錘頭，但出現在上升趨勢中的回調階段。
     * 回調時出現長上影線，說明有資金嘗試上攻，預示回調結束、升勢延續。
     */
    private fun detectInvertedHammerPullback(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        if (n < 10) return null
        val d = c[n - 1]
        val body = kotlin.math.abs(d.close - d.open)
        if (body <= 0) return null
        val upperShadow = d.high - kotlin.math.max(d.open, d.close)
        val lowerShadow = kotlin.math.min(d.open, d.close) - d.low
        // 上影線 > 實體 2 倍
        if (upperShadow < body * 2) return null
        // 下影線 < 實體 30%
        if (lowerShadow > body * 0.3) return null
        // 大趨勢仍向上（10 日前 → 近期高點有明顯漲幅）
        val highRecent = c.takeLast(10).maxOf { it.high }
        val prevClose10 = c[n - 10].close
        if (highRecent < prevClose10 * 1.05) return null  // 近 10 日無 5% 漲幅，不算上升趨勢
        // 近 3 日處於回調（收盤低於 3 日前）
        if (n < 4) return null
        val prevClose3 = c[n - 4].close
        if (d.close >= prevClose3) return null  // 沒在回調

        return PatternMatch(
            patternName = "調整倒垂線",
            direction = Direction.BULLISH,
            description = "上升趨勢回調中出現長上影線——有資金嘗試上攻，回調接近尾聲，升勢有望延續",
            strength = 3
        )
    }

    // ════════════════════════════════════════════════════
    //  輔助方法（複雜形態偵測用）
    // ════════════════════════════════════════════════════

    /** 計算指定範圍的收盤價簡單移動平均 */
    private fun calcMA(c: List<DailySnapshotEntity>, start: Int, end: Int): Double {
        if (start >= end || end > c.size) return 0.0
        return c.subList(start, end).map { it.close }.average()
    }

    /** 計算指定範圍的平均成交量 */
    private fun calcAvgVol(c: List<DailySnapshotEntity>, start: Int, end: Int): Double {
        if (start >= end || end > c.size) return 0.0
        return c.subList(start, end).map { it.volume }.average()
    }

    /** 尋找局部低點（swing low）：比前後各 lookback 根都低的點 */
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

    /** 尋找局部高點（swing high）：比前後各 lookback 根都高的點 */
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

    /** 檢測跳空缺口：i 和 i-1 之間是否有 gap */
    private fun hasGapDown(c: List<DailySnapshotEntity>, i: Int): Boolean {
        if (i <= 0) return false
        return c[i].high < c[i - 1].low
    }

    private fun hasGapUp(c: List<DailySnapshotEntity>, i: Int): Boolean {
        if (i <= 0) return false
        return c[i].low > c[i - 1].high
    }

    /** 計算漲跌幅（百分比） */
    private fun pctChange(from: Double, to: Double): Double = (to - from) / from * 100

    /** 判斷是否為漲停（漲幅 >= 9.5%，考慮 ST 5% 和科創/創業 20%） */
    private fun isLimitUp(c: List<DailySnapshotEntity>, i: Int): Boolean {
        if (i <= 0) return false
        val prevClose = c[i - 1].close
        if (prevClose <= 0) return false
        val change = (c[i].close - prevClose) / prevClose
        return change >= 0.095
    }

    // ═══════════════════════════════════════════════════
    //  複雜趨勢形態偵測（10-30+ 根 K 線）
    // ════════════════════════════════════════════════════

    /**
     * W底 / 雙重底（W-Bottom）— 看多反轉
     *
     * 在 30 日窗口內尋找兩個大致持平的低點，中間有高點（頸線）。
     * 右底 >= 左底（略高更佳），最近價格突破頸線。
     */
    private fun detectWBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)
        val lows = findSwingLows(window, 3)
        if (lows.size < 2) return null

        // 找最近的兩個低點
        val lastTwoLows = lows.takeLast(2)
        val leftLow = window[lastTwoLows[0]].low
        val rightLow = window[lastTwoLows[1]].low
        // 兩個低點差距 < 5%
        if (kotlin.math.abs(leftLow - rightLow) / leftLow > 0.05) return null
        // 右底 >= 左底 * 0.98（允許略低）
        if (rightLow < leftLow * 0.98) return null

        // 找中間高點（頸線）
        val betweenStart = lastTwoLows[0]
        val betweenEnd = lastTwoLows[1]
        if (betweenEnd - betweenStart < 3) return null  // 間隔太短
        val neckHigh = window.subList(betweenStart, betweenEnd + 1).maxOf { it.high }

        // 最近價格突破頸線
        val lastClose = window.last().close
        if (lastClose < neckHigh * 0.98) return null

        // 右側成交量 > 左側（資金進場）
        val leftVol = calcAvgVol(window, betweenStart - 5.coerceAtLeast(0), betweenStart)
        val rightVol = calcAvgVol(window, betweenEnd, window.size)
        if (rightVol < leftVol * 0.8) return null  // 右側量能不足

        return PatternMatch(
            patternName = "W底（雙重底）",
            direction = Direction.BULLISH,
            description = "兩個低點大致持平，右側放量突破頸線——主力洗盤結束，反轉確認",
            strength = 4
        )
    }

    /**
     * 雙重頂 / M頭（Double Top）— 看空反轉
     */
    private fun detectDoubleTop(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)
        val highs = findSwingHighs(window, 3)
        if (highs.size < 2) return null

        val lastTwoHighs = highs.takeLast(2)
        val leftHigh = window[lastTwoHighs[0]].high
        val rightHigh = window[lastTwoHighs[1]].high
        // 兩個高點差距 < 3%
        if (kotlin.math.abs(leftHigh - rightHigh) / leftHigh > 0.03) return null

        // 中間低點（頸線）
        val betweenStart = lastTwoHighs[0]
        val betweenEnd = lastTwoHighs[1]
        if (betweenEnd - betweenStart < 3) return null
        val neckLow = window.subList(betweenStart, betweenEnd + 1).minOf { it.low }

        // 最近價格跌破頸線
        val lastClose = window.last().close
        if (lastClose > neckLow * 1.02) return null

        // 右頂成交量 < 左頂（量價背離）
        val leftVol = calcAvgVol(window, (lastTwoHighs[0] - 3).coerceAtLeast(0), lastTwoHighs[0] + 1)
        val rightVol = calcAvgVol(window, (lastTwoHighs[1] - 3).coerceAtLeast(0), lastTwoHighs[1] + 1)
        if (rightVol >= leftVol) return null  // 沒有量價背離

        return PatternMatch(
            patternName = "雙重頂（M頭）",
            direction = Direction.BEARISH,
            description = "兩次衝高到相近水平後回落，跌破頸線——頂部反轉確認，注意止損",
            strength = 4
        )
    }

    /**
     * 頭肩頂（Head & Shoulders Top）— 看空反轉
     *
     * 三個高峰：左肩→頭（最高）→右肩，右肩成交量 < 左肩。
     */
    private fun detectHeadShouldersTop(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(35)
        val highs = findSwingHighs(window, 4)
        if (highs.size < 3) return null

        val lastThree = highs.takeLast(3)
        val leftShoulder = window[lastThree[0]].high
        val head = window[lastThree[1]].high
        val rightShoulder = window[lastThree[2]].high

        // 頭最高
        if (head <= leftShoulder || head <= rightShoulder) return null
        // 兩肩大致等高（差距 < 5%）
        if (kotlin.math.abs(leftShoulder - rightShoulder) / leftShoulder > 0.05) return null

        // 頸線（兩個低谷的連線）
        val low1 = window.subList(lastThree[0], lastThree[1] + 1).minOf { it.low }
        val low2 = window.subList(lastThree[1], lastThree[2] + 1).minOf { it.low }
        val neckline = (low1 + low2) / 2

        // 最近跌破頸線
        val lastClose = window.last().close
        if (lastClose > neckline * 1.02) return null

        // 右肩成交量 < 左肩（量價背離）
        val leftVol = calcAvgVol(window, (lastThree[0] - 3).coerceAtLeast(0), lastThree[0] + 1)
        val rightVol = calcAvgVol(window, (lastThree[2] - 3).coerceAtLeast(0), lastThree[2] + 1)
        if (rightVol >= leftVol * 0.8) return null

        return PatternMatch(
            patternName = "頭肩頂",
            direction = Direction.BEARISH,
            description = "三高峰形態，頭最高兩肩等高，右肩縮量跌破頸線——經典逃頂信號",
            strength = 5
        )
    }

    /**
     * 頭肩底（Head & Shoulders Bottom）— 看多反轉
     */
    private fun detectHeadShouldersBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(35)
        val lows = findSwingLows(window, 4)
        if (lows.size < 3) return null

        val lastThree = lows.takeLast(3)
        val leftShoulder = window[lastThree[0]].low
        val head = window[lastThree[1]].low
        val rightShoulder = window[lastThree[2]].low

        // 頭最低
        if (head >= leftShoulder || head >= rightShoulder) return null
        // 兩肩大致等高
        if (kotlin.math.abs(leftShoulder - rightShoulder) / leftShoulder > 0.05) return null

        // 頸線
        val high1 = window.subList(lastThree[0], lastThree[1] + 1).maxOf { it.high }
        val high2 = window.subList(lastThree[1], lastThree[2] + 1).maxOf { it.high }
        val neckline = (high1 + high2) / 2

        // 最近突破頸線
        val lastClose = window.last().close
        if (lastClose < neckline * 0.98) return null

        // 右肩成交量 > 左肩（資金進場）
        val leftVol = calcAvgVol(window, (lastThree[0] - 3).coerceAtLeast(0), lastThree[0] + 1)
        val rightVol = calcAvgVol(window, (lastThree[2] - 3).coerceAtLeast(0), lastThree[2] + 1)
        if (rightVol < leftVol * 0.8) return null

        return PatternMatch(
            patternName = "頭肩底",
            direction = Direction.BULLISH,
            description = "三低谷形態，頭最低兩肩等高，右肩放量突破頸線——底部反轉確認",
            strength = 5
        )
    }

    /**
     * 圓弧底（Rounded Bottom）— 看多反轉
     *
     * 股價緩跌→底部盤整→緩漲，形成 U 形。左側陰線越來越小，底部縮量，右側放量突破。
     */
    private fun detectRoundedBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)
        val size = window.size

        // 分三段：左側(0-1/3)、底部(1/3-2/3)、右側(2/3-末尾)
        val third = size / 3
        if (third < 5) return null

        val leftPart = window.subList(0, third)
        val midPart = window.subList(third, third * 2)
        val rightPart = window.subList(third * 2, size)

        // 左側：整體下跌趨勢（首尾比較）
        if (leftPart.last().close >= leftPart.first().close * 0.97) return null

        // 底部：波動收窄（最高-最低 < 左側的 60%）
        val leftRange = leftPart.maxOf { it.high } - leftPart.minOf { it.low }
        val midRange = midPart.maxOf { it.high } - midPart.minOf { it.low }
        if (midRange > leftRange * 0.7) return null  // 底部波動太大

        // 底部縮量
        val leftVol = calcAvgVol(window, 0, third)
        val midVol = calcAvgVol(window, third, third * 2)
        if (midVol >= leftVol * 0.8) return null  // 底部沒有縮量

        // 右側：低點逐步抬高
        val rightLows = findSwingLows(rightPart, 2)
        if (rightLows.size >= 2) {
            val rl1 = rightPart[rightLows[rightLows.size - 2]].low
            val rl2 = rightPart[rightLows.last()].low
            if (rl2 < rl1 * 0.98) return null  // 右側低點沒有抬高
        }

        // 右側放量突破
        val rightVol = calcAvgVol(window, third * 2, size)
        val lastClose = window.last().close
        val leftHigh = leftPart.maxOf { it.high }
        if (lastClose < leftHigh * 0.95 || rightVol < midVol * 1.3) return null

        return PatternMatch(
            patternName = "圓弧底",
            direction = Direction.BULLISH,
            description = "U形圓弧底部，左側緩跌縮量→底部盤整→右側放量突破——主力吸籌完成",
            strength = 4
        )
    }

    /**
     * V形底（V-Bottom Reversal）— 看多反轉
     *
     * 急跌（3-5日跌幅>15%）→ 急漲（速度與下跌相當），底部放量。
     */
    private fun detectVBottom(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(20)

        // 找最低點
        var minIdx = 0
        var minPrice = window[0].low
        for (i in window.indices) {
            if (window[i].low < minPrice) { minPrice = window[i].low; minIdx = i }
        }
        // 最低點不能太靠近末尾（需要右側反彈空間）
        if (minIdx < 3 || minIdx > window.size - 4) return null

        // 左側急跌：從起點到最低點跌幅 > 12%
        val dropPct = pctChange(window[0].close, minPrice)
        if (dropPct > -12) return null

        // 右側急漲：從最低點到末尾漲幅 > 左側跌幅的 70%
        val reboundPct = pctChange(minPrice, window.last().close)
        if (reboundPct < kotlin.math.abs(dropPct) * 0.7) return null

        // 底部放量：最低點附近 3 日成交量 > 左側平均的 1.5 倍
        val bottomVol = calcAvgVol(window, (minIdx - 1).coerceAtLeast(0), (minIdx + 2).coerceAtMost(window.size))
        val leftVol = calcAvgVol(window, 0, minIdx)
        if (bottomVol < leftVol * 1.3) return null

        // 右側連續陽線
        var consecutiveUp = 0
        for (i in (minIdx + 1) until window.size) {
            if (window[i].close > window[i].open) consecutiveUp++ else consecutiveUp = 0
        }
        if (consecutiveUp < 2) return null

        return PatternMatch(
            patternName = "V形底",
            direction = Direction.BULLISH,
            description = "急跌後急漲形成V形反轉，底部放量——恐慌盤被主力接納，可能快速反彈",
            strength = 3
        )
    }

    /**
     * 黃金坑（Golden Pit Wash）— 看多
     *
     * 前期上漲→突然急跌挖坑（縮量）→坑底縮量盤整→放量拉升突破前高。
     */
    private fun detectGoldenPit(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)

        // 找最低點
        var minIdx = 0
        var minPrice = window[0].low
        for (i in window.indices) {
            if (window[i].low < minPrice) { minPrice = window[i].low; minIdx = i }
        }
        if (minIdx < 5 || minIdx > window.size - 5) return null

        // 前期有上漲（最低點前 5 日的高點 > 最低點前 10 日的低點）
        val preHigh = window.subList(0, minIdx).maxOf { it.high }
        if (minIdx < 10) return null
        val preLow = window.subList(0, minIdx - 5).minOf { it.low }
        if (preHigh < preLow * 1.05) return null  // 前期沒有上漲趨勢

        // 急跌但縮量：跌到坑底的過程成交量 < 前期的 70%
        val dropVol = calcAvgVol(window, (minIdx - 5).coerceAtLeast(0), minIdx)
        val preVol = calcAvgVol(window, 0, (minIdx - 5).coerceAtLeast(1))
        if (dropVol >= preVol * 0.8) return null  // 下跌放量=真下跌，非黃金坑

        // 坑底縮量（地量）
        val pitVol = calcAvgVol(window, minIdx, (minIdx + 3).coerceAtMost(window.size))
        if (pitVol >= preVol * 0.6) return null

        // 放量拉升突破前高
        val lastClose = window.last().close
        if (lastClose < preHigh * 0.95) return null
        val riseVol = calcAvgVol(window, (window.size - 5).coerceAtLeast(0), window.size)
        if (riseVol < pitVol * 1.5) return null

        return PatternMatch(
            patternName = "黃金坑",
            direction = Direction.BULLISH,
            description = "縮量急跌挖坑後放量突破前高——主力洗盤結束，黃金坑確認，最佳買點",
            strength = 4
        )
    }

    /**
     * 島形反轉（Island Reversal）— 看多反轉
     *
     * 向下跳空缺口→島嶼盤整 3-10 日→向上跳空缺口。
     */
    private fun detectIslandReversal(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(25)

        // 從末尾往前找向上跳空
        var gapUpIdx = -1
        for (i in window.size - 1 downTo 3) {
            if (hasGapUp(window, i)) { gapUpIdx = i; break }
        }
        if (gapUpIdx < 0) return null

        // 從 gapUpIdx 往前找向下跳空
        var gapDownIdx = -1
        for (i in gapUpIdx - 1 downTo 1) {
            if (hasGapDown(window, i)) { gapDownIdx = i; break }
        }
        if (gapDownIdx < 0) return null

        // 島嶼區間：gapDownIdx 到 gapUpIdx 之間
        val islandLen = gapUpIdx - gapDownIdx
        if (islandLen < 2 || islandLen > 10) return null  // 島嶼太短或太長

        // 兩個缺口價格區間大致重疊
        val gapDownHigh = window[gapDownIdx - 1].low  // 缺口前的低點
        val gapDownLow = window[gapDownIdx].high       // 缺口後的高點
        val gapUpLow = window[gapUpIdx - 1].high       // 缺口前的高點
        val gapUpHigh = window[gapUpIdx].low           // 缺口後的低點
        // 缺口區間重疊度 > 50%
        val overlap = kotlin.math.min(gapDownHigh, gapUpHigh) - kotlin.math.max(gapDownLow, gapUpLow)
        val totalRange = kotlin.math.max(gapDownHigh, gapUpHigh) - kotlin.math.min(gapDownLow, gapUpLow)
        if (totalRange > 0 && overlap / totalRange < 0.3) return null

        // 右側缺口放量
        val postVol = calcAvgVol(window, gapUpIdx, window.size)
        val islandVol = calcAvgVol(window, gapDownIdx, gapUpIdx)
        if (postVol < islandVol * 1.2) return null

        return PatternMatch(
            patternName = "島形反轉",
            direction = Direction.BULLISH,
            description = "向下跳空→島嶼盤整→向上跳空，雙缺口夾島——強烈反轉信號，可靠性極高",
            strength = 5
        )
    }

    /**
     * 上升旗形（Ascending Flag / Bull Flag）— 看多持續
     *
     * 急漲（旗桿）→ 小幅回調整理（旗面，向下傾斜通道）→ 突破上漲。
     */
    private fun detectAscendingFlag(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(20)

        // 找旗桿起點：從末尾往前找最近的急漲段
        // 旗桿：連續 3-5 根陽線，漲幅 > 12%
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

        // 旗面：poleEnd 之後的整理，高點逐步降低（向下傾斜）
        val flagPart = window.subList(poleEnd + 1, window.size)
        if (flagPart.size < 3) return null

        // 旗面高點逐步降低
        val flagHighs = findSwingHighs(flagPart, 1)
        if (flagHighs.size >= 2) {
            val fh1 = flagPart[flagHighs[flagHighs.size - 2]].high
            val fh2 = flagPart[flagHighs.last()].high
            if (fh2 >= fh1) return null  // 高點沒有降低
        }

        // 旗面成交量萎縮
        val poleVol = calcAvgVol(window, poleStart, poleEnd + 1)
        val flagVol = calcAvgVol(window, poleEnd + 1, window.size)
        if (flagVol >= poleVol * 0.8) return null

        // 突破旗面高點
        val flagHigh = flagPart.maxOf { it.high }
        val lastClose = window.last().close
        if (lastClose < flagHigh * 0.98) return null

        return PatternMatch(
            patternName = "上升旗形",
            direction = Direction.BULLISH,
            description = "急漲後小幅回調整理，縮量旗面突破——多頭蓄力完成，繼續上漲",
            strength = 4
        )
    }

    /**
     * 下降三角形（Descending Triangle）— 看空
     *
     * 高點逐級降低（下降趨勢線），低點大致持平（水平支撐），破位放量。
     */
    private fun detectDescendingTriangle(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(25)

        val highs = findSwingHighs(window, 2)
        val lows = findSwingLows(window, 2)
        if (highs.size < 3 || lows.size < 2) return null

        // 高點逐級降低
        val last3Highs = highs.takeLast(3)
        if (window[last3Highs[1]].high >= window[last3Highs[0]].high * 0.99) return null
        if (window[last3Highs[2]].high >= window[last3Highs[1]].high * 0.99) return null

        // 低點大致持平（差距 < 3%）
        val last2Lows = lows.takeLast(2)
        val low1 = window[last2Lows[0]].low
        val low2 = window[last2Lows[1]].low
        if (kotlin.math.abs(low1 - low2) / low1 > 0.03) return null

        // 最近跌破支撐
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
            description = "高點逐級降低、支撐多次測試後放量破位——空頭蓄力完成，加速下跌",
            strength = 4
        )
    }

    /**
     * 擴散三角形（Expanding Triangle / Megaphone）— 看空（頂部）
     *
     * 高點越來越高、低點越來越低，波動區間擴大，成交量混亂。
     */
    private fun detectExpandingTriangle(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(25)

        val highs = findSwingHighs(window, 2)
        val lows = findSwingLows(window, 2)
        if (highs.size < 3 || lows.size < 3) return null

        // 高點越來越高
        val last3Highs = highs.takeLast(3)
        if (window[last3Highs[1]].high <= window[last3Highs[0]].high * 1.01) return null
        if (window[last3Highs[2]].high <= window[last3Highs[1]].high * 1.01) return null

        // 低點越來越低
        val last3Lows = lows.takeLast(3)
        if (window[last3Lows[1]].low >= window[last3Lows[0]].low * 0.99) return null
        if (window[last3Lows[2]].low >= window[last3Lows[1]].low * 0.99) return null

        // 波動擴大：最後 5 日的 range > 前 5 日的 range
        val earlyRange = window.subList(0, 5).maxOf { it.high } - window.subList(0, 5).minOf { it.low }
        val lateRange = window.takeLast(5).maxOf { it.high } - window.takeLast(5).minOf { it.low }
        if (lateRange < earlyRange * 1.3) return null

        // 成交量大而混亂（標準差大）
        val vols = window.map { it.volume }
        val avgVol = vols.average()
        val volStdDev = kotlin.math.sqrt(vols.map { (it - avgVol) * (it - avgVol) }.average())
        if (volStdDev < avgVol * 0.3) return null  // 成交量不夠混亂

        return PatternMatch(
            patternName = "擴散三角形",
            direction = Direction.BEARISH,
            description = "高低點持續擴大、成交量混亂——多空分歧巨大，常見於頂部反轉",
            strength = 3
        )
    }

    /**
     * 杯柄形態（Cup and Handle）— 看多
     *
     * 圓弧底（杯身）+ 小幅回調（杯柄）+ 放量突破。
     */
    private fun detectCupAndHandle(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(35)

        // 先檢測是否有圓弧底特徵（簡化版）
        val size = window.size
        val third = size / 3
        if (third < 6) return null

        // 杯身：左側下跌 + 底部盤整 + 右側回升
        val leftPart = window.subList(0, third)
        val midPart = window.subList(third, third * 2)
        val rightPart = window.subList(third * 2, size)

        // 左側下跌
        if (leftPart.last().close >= leftPart.first().close * 0.97) return null
        // 底部波動收窄
        val leftRange = leftPart.maxOf { it.high } - leftPart.minOf { it.low }
        val midRange = midPart.maxOf { it.high } - midPart.minOf { it.low }
        if (midRange > leftRange * 0.7) return null

        // 杯身深度
        val cupHigh = leftPart.maxOf { it.high }
        val cupLow = midPart.minOf { it.low }
        val cupDepth = (cupHigh - cupLow) / cupHigh

        // 杯柄：右側最後 1/4 部分小幅回調
        val handleStart = size - size / 4
        val handlePart = window.subList(handleStart, size)
        if (handlePart.size < 3) return null

        // 杯柄回調 < 杯深的 1/2
        val handleHigh = handlePart.maxOf { it.high }
        val handleLow = handlePart.minOf { it.low }
        val handleDepth = (handleHigh - handleLow) / cupHigh
        if (handleDepth > cupDepth * 0.5) return null

        // 杯柄縮量
        val cupVol = calcAvgVol(window, third, third * 2)
        val handleVol = calcAvgVol(window, handleStart, size)
        if (handleVol >= cupVol * 1.2) return null

        // 突破杯柄高點放量
        val lastClose = window.last().close
        if (lastClose < cupHigh * 0.95) return null
        val breakVol = calcAvgVol(window, size - 3, size)
        if (breakVol < handleVol * 1.3) return null

        return PatternMatch(
            patternName = "杯柄形態",
            direction = Direction.BULLISH,
            description = "U形杯身+小幅杯柄回調後放量突破——經典中長期底部形態",
            strength = 4
        )
    }

    /**
     * 底部雙雄（Bottom Double Heroes）— 看多
     *
     * 底部區域出現兩次漲停/大陽線，中間有縮量回調洗盤。
     */
    private fun detectBottomDoubleHeroes(c: List<DailySnapshotEntity>, n: Int): PatternMatch? {
        val window = c.takeLast(30)

        // 找漲停/大陽線位置（漲幅 > 7%）
        val bigYangDays = mutableListOf<Int>()
        for (i in 1 until window.size) {
            val prevClose = window[i - 1].close
            if (prevClose > 0) {
                val change = (window[i].close - prevClose) / prevClose
                if (change > 0.07) bigYangDays.add(i)
            }
        }
        if (bigYangDays.size < 2) return null

        // 找最近的兩次大陽線
        val lastTwo = bigYangDays.takeLast(2)
        val firstYang = lastTwo[0]
        val secondYang = lastTwo[1]

        // 間隔 3-10 日
        val gap = secondYang - firstYang
        if (gap < 3 || gap > 12) return null

        // 兩次都在底部區域（價格 < 30 日最高點的 85%）
        val maxPrice = window.maxOf { it.high }
        if (window[firstYang].close > maxPrice * 0.9 || window[secondYang].close > maxPrice * 0.9) return null

        // 中間回調縮量
        val betweenVol = calcAvgVol(window, firstYang + 1, secondYang)
        val yang1Vol = window[firstYang].volume
        val yang2Vol = window[secondYang].volume
        if (betweenVol >= yang1Vol * 0.7) return null  // 回調沒有縮量

        // 第二次量能 >= 第一次
        if (yang2Vol < yang1Vol * 0.7) return null

        // 第二次後突破前高
        val preHigh = window.subList(0, firstYang).maxOf { it.high }
        val lastClose = window.last().close
        if (lastClose < preHigh * 0.95) return null

        return PatternMatch(
            patternName = "底部雙雄",
            direction = Direction.BULLISH,
            description = "底部兩次漲停/大陽線，中間縮量洗盤——主力試盤後正式進場",
            strength = 4
        )
    }
}
