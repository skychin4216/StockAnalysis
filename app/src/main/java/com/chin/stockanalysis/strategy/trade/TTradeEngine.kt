package com.chin.stockanalysis.strategy.trade

import android.content.Context
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlin.math.abs

/**
 * 做T信号
 */
data class TTradeSignal(
    val stockCode: String,
    val stockName: String,
    val signalType: TTradeType,
    val suggestedPrice: Double,
    val targetPrice: Double,      // 目标配对价格
    val quantity: Int,            // 建议数量（底仓的30%-50%）
    val reason: String,
    val expectedProfitPct: Double, // 预期收益率
    val periodType: String = "",   // 所属周期
    // ── v2 增强：趋势分析 ──
    val trendDirection: String = "",  // "准备上升" / "准备下跌" / "上升中" / "下跌中" / "盘整"
    val rsi: Double = 50.0,
    val volumeRatio: Double = 1.0,   // 量比（今日/5日均量）
    val patternName: String = "",     // 匹配到的K线形态名称
    val patternDirection: String = "", // "BULLISH" / "BEARISH"
    val confidence: Int = 50          // 信号置信度 0-100
)

/**
 * 做T交易类型
 *
 * - T_BUY  ：做T买入（开仓腿），日内低买，等待高卖配对
 * - T_SELL ：做T卖出（配对腿），卖出之前做T买入的仓位，锁定利润
 * - RT_SELL：反T卖出（开仓腿），日内高卖底仓，等待低买回配对
 * - RT_BUY ：反T买回（配对腿），买回之前反T卖出的仓位，锁定利润
 */
enum class TTradeType(val label: String, val desc: String) {
    T_BUY("做T买入", "低买后当日高卖"),
    T_SELL("做T卖出", "卖出之前做T买入的仓位"),
    RT_SELL("反T卖出", "高卖后当日低买回"),
    RT_BUY("反T买回", "买回之前反T卖出的仓位")
}

/**
 * 做T统计
 */
data class TTradeStats(
    val openCount: Int,
    val totalProfit: Double,
    val winRate: Double,
    val totalTrades: Int
)

/**
 * 做T/反T 引擎
 *
 * 在持有底仓的前提下，利用日内波动进行高抛低吸：
 * - 做T：价格接近支撑位时买入，反弹至阻力位卖出
 * - 反T：价格接近阻力位时卖出，回落至支撑位买回
 *
 * 每次做T不改变底仓总量，仅赚取日内差价。
 */
class TTradeEngine(private val context: Context) {

    /**
     * 生成做T信号（v2 - 增强版：RSI + 量能 + K线形态 + 趋势方向）
     * @param stockCode 股票代码
     * @param basePositionQty 底仓数量
     * @param periodType 周期类型
     * @return 做T信号列表
     */
    suspend fun generateSignals(
        stockCode: String,
        basePositionQty: Int,
        periodType: String
    ): List<TTradeSignal> {
        // 做T阈值参数（PC 端 walk-forward 拟合，见 backtest_params.json t_trade 区块）
        val p = com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader
            .tTradeParams(context)
        val db = StockDatabase.getInstance(context)
        val snaps = db.dailySnapshotDao().getByCode(stockCode, 30).sortedBy { it.date }
        if (snaps.size < 10) return emptyList()

        val signals = mutableListOf<TTradeSignal>()
        val latest = snaps.last()
        val stockName = latest.name

        // ── 基础均线和价位 ──
        val ma5 = snaps.takeLast(5).map { it.close }.average()
        val ma10 = snaps.takeLast(10).map { it.close }.average()
        val ma20 = snaps.takeLast(minOf(20, snaps.size)).map { it.close }.average()
        val window20 = snaps.takeLast(minOf(20, snaps.size))
        val recentLow = window20.map { it.low }.minOrNull() ?: latest.close
        val recentHigh = window20.map { it.high }.maxOrNull() ?: latest.close
        val avgBody = snaps.takeLast(10).map { abs(it.close - it.open) }.average()

        // 支撑位和阻力位（系数来自 PC 拟合参数）
        val supportPrice = listOf(recentLow, ma5 * p.supportMa5Factor, ma10 * p.supportMa10Factor).maxOrNull() ?: latest.close
        val resistancePrice = listOf(recentHigh, ma5 * p.resistanceMa5Factor, ma10 * p.resistanceMa10Factor).minOrNull() ?: latest.close

        // ── v2 增强分析 ──
        val closes = snaps.map { it.close }
        val rsi = com.chin.stockanalysis.strategy.analysis.RsiCalculator.compute(closes)
        val vol5 = snaps.takeLast(5).map { it.volume }.average()
        val volumeRatio = if (vol5 > 0) latest.volume / vol5 else 1.0

        // K线形态检测
        val patterns = com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.detect(snaps)
        val topPattern = patterns.maxByOrNull { it.strength }
        val patternName = topPattern?.patternName ?: ""
        val patternDir = topPattern?.direction?.name ?: ""

        // 趋势方向判断
        val trendDir = analyzeTrendDirection(snaps, ma5, ma10, ma20, rsi, topPattern)

        // 做T数量：底仓的 tQtyRatio（默认 40%），四舍五入到整手；不足一手或底仓不足一手时不触发
        val tQty = if (basePositionQty >= 100) {
            minOf((basePositionQty * p.tQtyRatio).toInt() / 100 * 100, basePositionQty / 100 * 100)
        } else 0

        // ── 做T买入信号 ──
        if (supportPrice > 0) {
            val priceToSupport = (latest.close - supportPrice) / supportPrice
            if (priceToSupport < p.nearSupportThreshold && tQty > 0) {
                val targetPrice = ma5
                val expectedPct = (targetPrice - latest.close) / latest.close * 100
                if (expectedPct > p.minExpectedProfitPct) {
                    // 置信度评分
                    var conf = p.baseConfidence
                    val reasons = mutableListOf<String>()
                    reasons.add("接近支撑位 ${"%.2f".format(supportPrice)}")

                    if (rsi < p.rsiOversold) { conf += p.confRsiOversold; reasons.add("RSI超卖${"%.0f".format(rsi)}") }
                    else if (rsi < p.rsiLow) { conf += p.confRsiLow; reasons.add("RSI偏低${"%.0f".format(rsi)}") }
                    else if (rsi > p.rsiOverbought) { conf += p.confRsiOverbought; reasons.add("⚠️RSI超买${"%.0f".format(rsi)}") }

                    if (volumeRatio < p.volumeShrink) { conf += p.confVolumeShrink; reasons.add("缩量回调") }
                    if (volumeRatio > p.volumeSurge) { conf += p.confVolumeSurge; reasons.add("⚠️放量异常") }

                    if (topPattern?.direction == com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.Direction.BULLISH) {
                        conf += p.confPatternBullish; reasons.add("K线形态:$patternName(看多)")
                    } else if (topPattern?.direction == com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.Direction.BEARISH) {
                        conf += p.confPatternBearish; reasons.add("⚠️K线形态:$patternName(看空)")
                    }

                    if (trendDir.contains("上升")) { conf += p.confTrendUp; reasons.add("趋势:$trendDir") }
                    if (trendDir.contains("下跌")) { conf += p.confTrendDown; reasons.add("⚠️趋势:$trendDir") }

                    conf = conf.coerceIn(0, 100)

                    signals.add(
                        TTradeSignal(
                            stockCode = stockCode, stockName = stockName,
                            signalType = TTradeType.T_BUY,
                            suggestedPrice = latest.close, targetPrice = targetPrice,
                            quantity = tQty,
                            reason = reasons.joinToString("；"),
                            expectedProfitPct = expectedPct, periodType = periodType,
                            trendDirection = trendDir, rsi = rsi,
                            volumeRatio = volumeRatio, patternName = patternName,
                            patternDirection = patternDir, confidence = conf
                        )
                    )
                }
            }
        }

        // ── 反T卖出信号 ──
        if (resistancePrice > 0) {
            val priceToResistance = (resistancePrice - latest.close) / resistancePrice
            if (priceToResistance < p.nearResistanceThreshold && tQty > 0) {
                val targetPrice = ma5
                val expectedPct = (latest.close - targetPrice) / latest.close * 100
                if (expectedPct > p.minExpectedProfitPct) {
                    var conf = p.baseConfidence
                    val reasons = mutableListOf<String>()
                    reasons.add("接近阻力位 ${"%.2f".format(resistancePrice)}")

                    if (rsi > p.rtRsiOverbought) { conf += p.rtConfRsiOverbought; reasons.add("RSI超买${"%.0f".format(rsi)}") }
                    else if (rsi > p.rtRsiHigh) { conf += p.rtConfRsiHigh; reasons.add("RSI偏高${"%.0f".format(rsi)}") }
                    else if (rsi < p.rtRsiOversold) { conf += p.rtConfRsiOversold; reasons.add("⚠️RSI超卖${"%.0f".format(rsi)}") }

                    if (volumeRatio > p.rtVolumeRise) { conf += p.rtConfVolumeRise; reasons.add("放量冲高") }
                    if (volumeRatio < p.rtVolumeDry) { conf += p.rtConfVolumeDry; reasons.add("⚠️缩量无力") }

                    if (topPattern?.direction == com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.Direction.BEARISH) {
                        conf += p.rtConfPatternBearish; reasons.add("K线形态:$patternName(看空)")
                    } else if (topPattern?.direction == com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.Direction.BULLISH) {
                        conf += p.rtConfPatternBullish; reasons.add("⚠️K线形态:$patternName(看多)")
                    }

                    if (trendDir.contains("下跌")) { conf += p.rtConfTrendDown; reasons.add("趋势:$trendDir") }
                    if (trendDir.contains("上升")) { conf += p.rtConfTrendUp; reasons.add("⚠️趋势:$trendDir") }

                    conf = conf.coerceIn(0, 100)

                    signals.add(
                        TTradeSignal(
                            stockCode = stockCode, stockName = stockName,
                            signalType = TTradeType.RT_SELL,
                            suggestedPrice = latest.close, targetPrice = targetPrice,
                            quantity = tQty,
                            reason = reasons.joinToString("；"),
                            expectedProfitPct = expectedPct, periodType = periodType,
                            trendDirection = trendDir, rsi = rsi,
                            volumeRatio = volumeRatio, patternName = patternName,
                            patternDirection = patternDir, confidence = conf
                        )
                    )
                }
            }
        }

        // ── 配对腿信号 ──
        val today = java.time.LocalDate.now().toString()
        val openTrades = db.tTradeRecordDao().getOpenTrades(periodType)
            .filter { it.stockCode == stockCode }
            // B1: A股T+1 — 当日做T买入(T_BUY)不可当日卖出配对，须隔日方可卖出
            .filter { !(it.tradeType == "T_BUY" && it.tradeDate == today) }
        for (openTrade in openTrades) {
            val pairUp = 1 + p.pairProfitPct / 100    // 做T卖出目标（默认 +0.5%）
            val pairDown = 1 - p.pairProfitPct / 100  // 反T买回目标（默认 -0.5%）
            when (openTrade.tradeType) {
                "T_BUY" -> {
                    if (latest.close >= openTrade.price * pairUp) {
                        signals.add(
                            TTradeSignal(
                                stockCode = stockCode, stockName = stockName,
                                signalType = TTradeType.T_SELL,
                                suggestedPrice = latest.close, targetPrice = openTrade.price * pairUp,
                                quantity = openTrade.quantity,
                                reason = "做T买入(${openTrade.price})已到目标，卖出配对锁定利润；趋势:$trendDir",
                                expectedProfitPct = (latest.close - openTrade.price) / openTrade.price * 100,
                                periodType = periodType,
                                trendDirection = trendDir, rsi = rsi,
                                volumeRatio = volumeRatio, confidence = 80
                            )
                        )
                    }
                }
                "RT_SELL" -> {
                    if (latest.close <= openTrade.price * pairDown) {
                        signals.add(
                            TTradeSignal(
                                stockCode = stockCode, stockName = stockName,
                                signalType = TTradeType.RT_BUY,
                                suggestedPrice = latest.close, targetPrice = openTrade.price * pairDown,
                                quantity = openTrade.quantity,
                                reason = "反T卖出(${openTrade.price})已到目标，买回配对锁定利润；趋势:$trendDir",
                                expectedProfitPct = (openTrade.price - latest.close) / openTrade.price * 100,
                                periodType = periodType,
                                trendDirection = trendDir, rsi = rsi,
                                volumeRatio = volumeRatio, confidence = 80
                            )
                        )
                    }
                }
            }
        }

        return signals
    }

    /**
     * 趋势方向判断：综合均线排列、RSI、K线形态
     */
    private fun analyzeTrendDirection(
        snaps: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>,
        ma5: Double, ma10: Double, ma20: Double,
        rsi: Double,
        pattern: com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.PatternMatch?
    ): String {
        val latest = snaps.last()
        val price = latest.close

        // 均线排列评分
        var maScore = 0
        if (ma5 > ma10 && ma10 > ma20) maScore = 2        // 多头排列
        else if (ma5 < ma10 && ma10 < ma20) maScore = -2   // 空头排列
        else if (price > ma5 && ma5 > ma10) maScore = 1    // 偏多
        else if (price < ma5 && ma5 < ma10) maScore = -1   // 偏空

        // 近3日涨跌
        val last3 = snaps.takeLast(3)
        val change3 = if (last3.size >= 2 && last3.first().close > 0)
            (last3.last().close - last3.first().close) / last3.first().close * 100
        else 0.0

        // 综合评分
        val totalScore = maScore + when {
            rsi > 70 -> 1
            rsi < 30 -> -1
            else -> 0
        } + when {
            change3 > 3 -> 1
            change3 < -3 -> -1
            else -> 0
        } + when {
            pattern?.direction == com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.Direction.BULLISH -> 1
            pattern?.direction == com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.Direction.BEARISH -> -1
            else -> 0
        }

        // 判断趋势方向 + 是否准备转折
        return when {
            totalScore >= 3 -> "上升中"
            totalScore == 2 -> if (change3 > 0) "准备上升" else "盘整偏多"
            totalScore == 1 -> "盘整偏多"
            totalScore == 0 -> "盘整"
            totalScore == -1 -> "盘整偏空"
            totalScore == -2 -> if (change3 < 0) "准备下跌" else "盘整偏空"
            else -> "下跌中"
        }
    }

    /**
     * 执行做T交易
     *
     * - 开仓腿（T_BUY / RT_SELL）：插入新的 OPEN 记录
     * - 配对腿（T_SELL / RT_BUY）：找到对应的 OPEN 开仓腿并关闭，计算盈亏
     *
     * @return 开仓腿返回新记录 id；配对腿返回被关闭的开仓腿 id（找不到时插入新记录并返回其 id）
     */
    suspend fun executeTTrade(signal: TTradeSignal, periodType: String): Long {
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()

        val tradeType = when (signal.signalType) {
            TTradeType.T_BUY -> "T_BUY"
            TTradeType.T_SELL -> "T_SELL"
            TTradeType.RT_SELL -> "RT_SELL"
            TTradeType.RT_BUY -> "RT_BUY"
        }

        // 配对腿：T_SELL 配对 T_BUY，RT_BUY 配对 RT_SELL
        if (signal.signalType == TTradeType.T_SELL || signal.signalType == TTradeType.RT_BUY) {
            val targetType = if (signal.signalType == TTradeType.T_SELL) "T_BUY" else "RT_SELL"
            val openTrade = db.tTradeRecordDao()
                .getOpenTrades(periodType)
                .firstOrNull {
                    it.stockCode == signal.stockCode && it.tradeType == targetType &&
                    // B1: A股T+1 — 当日做T买入(T_BUY)不可当日卖出配对，须隔日
                    !(targetType == "T_BUY" && it.tradeDate == today)
                }

            if (openTrade != null && openTrade.price > 0) {
                val pairedPrice = signal.suggestedPrice
                val profit = if (targetType == "T_BUY") {
                    (pairedPrice - openTrade.price) * openTrade.quantity
                } else {
                    (openTrade.price - pairedPrice) * openTrade.quantity
                }
                val profitPct = if (targetType == "T_BUY") {
                    (pairedPrice - openTrade.price) / openTrade.price * 100
                } else {
                    (openTrade.price - pairedPrice) / openTrade.price * 100
                }
                db.tTradeRecordDao().closeTrade(openTrade.id, pairedPrice, profit, profitPct)
                return openTrade.id
            }
            // 找不到对应开仓腿（或受T+1限制当日不可配对）：配对腿不能作为开仓腿落库
            // （generateSignals 只会为 T_BUY/RT_SELL 生成配对，落库成 OPEN 将形成永久未平仓的孤儿记录），直接忽略
            android.util.Log.w("TTradeEngine",
                "⚠️ 配对腿 ${signal.signalType} 未找到可配对开仓腿($targetType)，已忽略: ${signal.stockCode}")
            return 0L
        }

        // B2: 开仓腿去重 — 已有同股票同类型未平仓记录时跳过，避免重复开仓产生孤儿记录
        val existsOpen = db.tTradeRecordDao()
            .getOpenTrades(periodType)
            .any { it.stockCode == signal.stockCode && it.tradeType == tradeType }
        if (existsOpen) {
            android.util.Log.w("TTradeEngine",
                "⚠️ 开仓腿 ${signal.signalType}($tradeType) 已有未平仓记录，去重跳过: ${signal.stockCode}")
            return 0L
        }

        // 开仓腿：插入新的 OPEN 记录
        val record = TTradeRecordEntity(
            stockCode = signal.stockCode,
            stockName = signal.stockName,
            tradeDate = today,
            tradeType = tradeType,
            quantity = signal.quantity,
            price = signal.suggestedPrice,
            periodType = periodType,
            basePositionQty = 0
        )
        return db.tTradeRecordDao().insert(record)
    }

    /**
     * 手动配对完成一笔未平仓的T交易
     * @param tradeId 开仓腿记录 id（T_BUY 或 RT_SELL）
     * @param pairedPrice 配对价格（做T的卖出价 / 反T的买回价）
     */
    suspend fun closeTTrade(tradeId: Long, pairedPrice: Double) {
        val db = StockDatabase.getInstance(context)
        val trade = db.tTradeRecordDao().getById(tradeId) ?: return

        // B1: A股T+1 — 当日做T买入(T_BUY)不可当日手动配对卖出
        if (trade.tradeType == "T_BUY" && trade.tradeDate == java.time.LocalDate.now().toString()) {
            android.util.Log.w("TTradeEngine",
                "⚠️ T+1 限制：当日买入的做T仓位不可当日配对卖出（${trade.stockCode}），请隔日再配对")
            return
        }

        val profit = when (trade.tradeType) {
            "T_BUY" -> (pairedPrice - trade.price) * trade.quantity
            "RT_SELL" -> (trade.price - pairedPrice) * trade.quantity
            else -> 0.0
        }
        val profitPct = if (trade.price != 0.0) {
            when (trade.tradeType) {
                "T_BUY" -> (pairedPrice - trade.price) / trade.price * 100
                "RT_SELL" -> (trade.price - pairedPrice) / trade.price * 100
                else -> 0.0
            }
        } else 0.0

        db.tTradeRecordDao().closeTrade(tradeId, pairedPrice, profit, profitPct)
    }

    /**
     * 获取做T统计
     */
    suspend fun getTTradeStats(periodType: String): TTradeStats {
        val db = StockDatabase.getInstance(context)
        val openCount = db.tTradeRecordDao().getOpenCount(periodType)
        val totalProfit = db.tTradeRecordDao().getTotalProfit(periodType) ?: 0.0
        val winCount = db.tTradeRecordDao().getWinCount(periodType)
        val totalClosed = db.tTradeRecordDao().getTotalClosedCount(periodType)
        val winRate = if (totalClosed > 0) winCount.toDouble() / totalClosed * 100 else 0.0

        return TTradeStats(
            openCount = openCount,
            totalProfit = totalProfit,
            winRate = winRate,
            totalTrades = totalClosed
        )
    }

    // ═══════════════════════════════════════════════════
    // 做T推荐记录管理
    // ═══════════════════════════════════════════════════

    /**
     * 将生成的做T信号保存为推荐记录（自动去重）
     *
     * 同一股票、同一天、同一信号类型只保存一条。
     * 使用 IGNORE 策略，重复插入会被忽略。
     *
     * @param signals 做T信号列表
     * @param source 来源："REAL"=真实持仓 / "SIMULATED"=模拟持仓
     * @return 新保存的推荐数量
     */
    suspend fun saveRecommendations(
        signals: List<TTradeSignal>,
        source: String = "REAL",
        periodType: String = ""
    ): Int {
        if (signals.isEmpty()) return 0
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        var saved = 0

        for (signal in signals) {
            val signalTypeStr = when (signal.signalType) {
                TTradeType.T_BUY -> "T_BUY"
                TTradeType.T_SELL -> "T_SELL"
                TTradeType.RT_SELL -> "RT_SELL"
                TTradeType.RT_BUY -> "RT_BUY"
            }

            // 检查是否已存在相同的待处理推荐
            val exists = db.tTradeRecommendationDao().existsPending(today, signal.stockCode, signalTypeStr)
            if (exists > 0) continue

            val recommendation = TTradeRecommendationEntity(
                stockCode = signal.stockCode,
                stockName = signal.stockName,
                tradeDate = today,
                signalType = signalTypeStr,
                suggestedPrice = signal.suggestedPrice,
                targetPrice = signal.targetPrice,
                quantity = signal.quantity,
                expectedProfitPct = signal.expectedProfitPct,
                reason = signal.reason,
                status = "PENDING",
                source = source,
                periodType = signal.periodType.ifEmpty { periodType }
            )
            val id = db.tTradeRecommendationDao().insert(recommendation)
            if (id > 0) saved++
        }

        if (saved > 0) {
            android.util.Log.i("TTradeEngine", "保存 $saved 条做T推荐 (source=$source, period=$periodType)")
        }
        return saved
    }

    /**
     * 获取今日待处理的做T推荐
     */
    suspend fun getTodayPendingRecommendations(): List<TTradeRecommendationEntity> {
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        return db.tTradeRecommendationDao().getPendingByDate(today)
    }

    /**
     * 获取今日所有做T推荐（含已处理）
     */
    suspend fun getTodayRecommendations(): List<TTradeRecommendationEntity> {
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        return db.tTradeRecommendationDao().getByDate(today)
    }

    /**
     * 获取最近N天的做T推荐历史
     */
    suspend fun getRecommendationHistory(days: Int = 7): List<TTradeRecommendationEntity> {
        val db = StockDatabase.getInstance(context)
        val startDate = java.time.LocalDate.now().minusDays(days.toLong()).toString()
        return db.tTradeRecommendationDao().getRecent(startDate, 200)
    }

    /**
     * 标记推荐为已执行
     */
    suspend fun markRecommendationExecuted(recommendationId: Long, executedPrice: Double) {
        val db = StockDatabase.getInstance(context)
        db.tTradeRecommendationDao().markExecuted(recommendationId, executedPrice)
        android.util.Log.i("TTradeEngine", "✅ 推荐 #$recommendationId 已标记为执行 @ $executedPrice")
    }

    /**
     * 标记推荐为已忽略
     */
    suspend fun markRecommendationIgnored(recommendationId: Long) {
        val db = StockDatabase.getInstance(context)
        db.tTradeRecommendationDao().markIgnored(recommendationId)
    }

    /**
     * 过期处理：将前一天仍为 PENDING 的推荐标记为 EXPIRED
     */
    suspend fun expireOldRecommendations(): Int {
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        val expired = db.tTradeRecommendationDao().expireOld(today)
        if (expired > 0) {
            android.util.Log.i("TTradeEngine", "⏰ $expired 条做T推荐已过期")
        }
        return expired
    }

    /**
     * 获取今日推荐统计
     */
    suspend fun getRecommendationStats(): RecommendationStats {
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        val weekAgo = java.time.LocalDate.now().minusDays(7).toString()

        val todayPending = db.tTradeRecommendationDao().getPendingCount(today)
        val weekExecuted = db.tTradeRecommendationDao().getExecutedCount(weekAgo)

        return RecommendationStats(
            todayPending = todayPending,
            weekExecuted = weekExecuted
        )
    }

    // ═══════════════════════════════════════════════════
    // 做T推荐结果跟踪（收盘统计）
    // ═══════════════════════════════════════════════════

    /**
     * 跟踪推荐结果：更新价格轨迹并检查目标是否触及
     *
     * 每次后台监控时调用，对所有 PENDING/TARGET_HIT 的推荐：
     * - 更新 peak/trough 价格
     * - 检查目标价是否触及
     *
     * @param currentPrices 当前价格 Map(stockCode -> price)
     */
    suspend fun trackOutcomeForRecommendations(currentPrices: Map<String, Double>) {
        if (currentPrices.isEmpty()) return
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        val recs = db.tTradeRecommendationDao().getPendingByDate(today)
        var hitCount = 0

        for (rec in recs) {
            val price = currentPrices[rec.stockCode] ?: continue
            // 更新价格轨迹
            db.tTradeRecommendationDao().updatePriceTracking(rec.id, price)

            // 检查目标是否触及（含配对腿 T_SELL / RT_BUY）
            if (!rec.targetHit) {
                val hit = when (rec.signalType) {
                    "T_BUY" -> price >= rec.targetPrice  // 低买后价格涨到目标
                    "RT_SELL" -> price <= rec.targetPrice // 高卖后价格跌到目标
                    "T_SELL" -> price >= rec.targetPrice  // 做T卖出：价格涨到目标卖出配对
                    "RT_BUY" -> price <= rec.targetPrice  // 反T买回：价格跌到目标买回配对
                    else -> false
                }
                if (hit) {
                    val profitPct = when (rec.signalType) {
                        "T_BUY", "T_SELL" -> (rec.targetPrice - rec.suggestedPrice) / rec.suggestedPrice * 100
                        "RT_SELL", "RT_BUY" -> (rec.suggestedPrice - rec.targetPrice) / rec.suggestedPrice * 100
                        else -> 0.0
                    }
                    db.tTradeRecommendationDao().markTargetHit(rec.id, profitPct)
                    hitCount++
                }
            }
        }

        if (hitCount > 0) {
            android.util.Log.i("TTradeEngine", "做T跟踪: $hitCount 条推荐目标价已触及")
        }
    }

    /**
     * 收盘时标记当日所有未处理的推荐为 TARGET_MISSED
     * 并计算虚拟盈亏（基于收盘价 vs 推荐价）
     */
    suspend fun markDayEnd(date: String) {
        val db = StockDatabase.getInstance(context)
        // 先计算所有 PENDING 推荐的虚拟盈亏
        val pending = db.tTradeRecommendationDao().getPendingByDate(date)
        for (rec in pending) {
            val profitPct = when (rec.signalType) {
                "T_BUY" -> {
                    // 假设在推荐价买入，收盘时卖出
                    val peakOrClose = if (rec.peakPriceAfter > 0) rec.peakPriceAfter else rec.suggestedPrice
                    (peakOrClose - rec.suggestedPrice) / rec.suggestedPrice * 100
                }
                "RT_SELL" -> {
                    // 假设在推荐价卖出，收盘时买回
                    val troughOrClose = if (rec.troughPriceAfter > 0) rec.troughPriceAfter else rec.suggestedPrice
                    (rec.suggestedPrice - troughOrClose) / rec.suggestedPrice * 100
                }
                "T_SELL" -> {
                    // 做T卖出配对：推荐卖出，收盘价若能高于建议价即为虚拟盈利
                    val peakOrClose = if (rec.peakPriceAfter > 0) rec.peakPriceAfter else rec.suggestedPrice
                    (peakOrClose - rec.suggestedPrice) / rec.suggestedPrice * 100
                }
                "RT_BUY" -> {
                    // 反T买回配对：推荐买回，收盘价若能低于建议价即为虚拟盈利
                    val troughOrClose = if (rec.troughPriceAfter > 0) rec.troughPriceAfter else rec.suggestedPrice
                    (rec.suggestedPrice - troughOrClose) / rec.suggestedPrice * 100
                }
                else -> 0.0
            }
            // 逐条更新虚拟盈亏（修复：避免批量覆盖）
            db.tTradeRecommendationDao().markDayEndById(rec.id, profitPct)
        }
        android.util.Log.i("TTradeEngine", "收盘统计: $date 共 ${pending.size} 条推荐已结算")
    }

    /**
     * 获取某日某周期的做T统计摘要
     */
    suspend fun getDailySummary(date: String, periodType: String): DailyTSummary {
        val db = StockDatabase.getInstance(context)
        val stats = db.tTradeRecommendationDao().getDayOutcomeStats(date, periodType)
        val details = db.tTradeRecommendationDao().getByPeriodAndDate(periodType, date)

        val total = stats?.total ?: 0
        val hitCount = stats?.hitCount ?: 0
        val executedCount = stats?.executedCount ?: 0
        val virtualSuccessRate = if (total > 0) hitCount.toDouble() / total * 100 else 0.0

        // 实际成功率：已执行中真正盈利的比例（按建议价 vs 执行价方向计算）
        val executedList = details.filter { it.status == "EXECUTED" }
        val executedProfitable = executedList.count { isExecutedProfitable(it) }
        val actualSuccessRate = if (executedList.isNotEmpty()) executedProfitable.toDouble() / executedList.size * 100 else 0.0

        return DailyTSummary(
            date = date,
            periodType = periodType,
            totalRecommendations = total,
            targetHitCount = hitCount,
            executedCount = executedCount,
            virtualSuccessRate = virtualSuccessRate,
            actualSuccessRate = actualSuccessRate,
            avgVirtualProfitPct = stats?.avgVirtualProfit ?: 0.0,
            details = details
        )
    }

    /**
     * 获取某日所有周期的做T统计摘要
     */
    suspend fun getDailyAllPeriodSummary(date: String): DailyTSummary {
        val db = StockDatabase.getInstance(context)
        val stats = db.tTradeRecommendationDao().getDayAllPeriodStats(date)
        val details = db.tTradeRecommendationDao().getByDate(date)

        val total = stats?.total ?: 0
        val hitCount = stats?.hitCount ?: 0
        val executedCount = stats?.executedCount ?: 0
        val virtualSuccessRate = if (total > 0) hitCount.toDouble() / total * 100 else 0.0

        val executedList = details.filter { it.status == "EXECUTED" }
        val executedProfitable = executedList.count { isExecutedProfitable(it) }
        val actualSuccessRate = if (executedList.isNotEmpty()) executedProfitable.toDouble() / executedList.size * 100 else 0.0

        return DailyTSummary(
            date = date,
            periodType = "ALL",
            totalRecommendations = total,
            targetHitCount = hitCount,
            executedCount = executedCount,
            virtualSuccessRate = virtualSuccessRate,
            actualSuccessRate = actualSuccessRate,
            avgVirtualProfitPct = stats?.avgVirtualProfit ?: 0.0,
            details = details
        )
    }

    /**
     * 判断一条已执行的推荐是否真正盈利（按信号方向用建议价 vs 执行价计算）。
     *
     * - T_BUY   ：低买做T，执行价应高于建议价 → 盈利（执行价 > 建议价）
     * - RT_SELL ：高卖反T，执行价应低于建议价 → 盈利（执行价 < 建议价）
     * - T_SELL / RT_BUY：配对腿，需结合建议价方向判断（做T卖出价高于买入价、反T买回价低于卖出价）
     */
    private fun isExecutedProfitable(rec: TTradeRecommendationEntity): Boolean {
        if (rec.executedPrice <= 0 || rec.suggestedPrice <= 0) return false
        return when (rec.signalType) {
            "T_BUY", "T_SELL" -> rec.executedPrice > rec.suggestedPrice
            "RT_SELL", "RT_BUY" -> rec.executedPrice < rec.suggestedPrice
            else -> false
        }
    }

    /**
     * 获取做T成功率（跨天汇总）
     *
     * @param days 统计天数（默认7天）
     * @return TTradeSuccessRate 包含总成功率、做T/反T分别成功率、平均盈亏
     */
    suspend fun getSuccessRate(days: Int = 7): TTradeSuccessRate {
        val db = StockDatabase.getInstance(context)
        val startDate = java.time.LocalDate.now().minusDays(days.toLong()).toString()
        val stats = db.tTradeRecommendationDao().getSuccessRateStats(startDate)
            ?: return TTradeSuccessRate(days, 0, 0, 0, 0.0, 0.0, 0, 0, 0.0, 0.0, 0.0)

        val total = stats.total
        val hitCount = stats.hitCount
        val profitableCount = stats.profitableCount
        val settledCount = stats.tCount + stats.rtCount  // 已结算的记录

        val overallRate = if (settledCount > 0) hitCount.toDouble() / settledCount * 100 else 0.0
        val profitRate = if (settledCount > 0) profitableCount.toDouble() / settledCount * 100 else 0.0
        val tRate = if (stats.tCount > 0) stats.tHit.toDouble() / stats.tCount * 100 else 0.0
        val rtRate = if (stats.rtCount > 0) stats.rtHit.toDouble() / stats.rtCount * 100 else 0.0

        return TTradeSuccessRate(
            days = days,
            total = total,
            hitCount = hitCount,
            profitableCount = profitableCount,
            overallSuccessRate = overallRate,
            profitRate = profitRate,
            tCount = stats.tCount,
            rtCount = stats.rtCount,
            tSuccessRate = tRate,
            rtSuccessRate = rtRate,
            avgVirtualProfitPct = stats.avgVirtualProfit ?: 0.0
        )
    }
}

/**
 * 做T推荐统计
 */
data class RecommendationStats(
    val todayPending: Int,     // 今日待处理推荐数
    val weekExecuted: Int      // 近7天已执行推荐数
)

/** 做T成功率统计（跨天汇总） */
data class TTradeSuccessRate(
    val days: Int,
    val total: Int,
    val hitCount: Int,
    val profitableCount: Int,
    val overallSuccessRate: Double,   // 目标触及率 %
    val profitRate: Double,           // 盈利比例 %
    val tCount: Int,                  // 做T次数
    val rtCount: Int,                 // 反T次数
    val tSuccessRate: Double,         // 做T成功率 %
    val rtSuccessRate: Double,        // 反T成功率 %
    val avgVirtualProfitPct: Double   // 平均虚拟盈亏 %
)
