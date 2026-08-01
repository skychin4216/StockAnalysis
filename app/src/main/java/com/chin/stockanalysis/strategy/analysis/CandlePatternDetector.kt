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
}
