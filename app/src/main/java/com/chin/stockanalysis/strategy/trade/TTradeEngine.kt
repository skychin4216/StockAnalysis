package com.chin.stockanalysis.strategy.trade

import android.content.Context
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlin.math.abs

/**
 * 做T信號
 */
data class TTradeSignal(
    val stockCode: String,
    val stockName: String,
    val signalType: TTradeType,
    val suggestedPrice: Double,
    val targetPrice: Double,      // 目標配對價格
    val quantity: Int,            // 建議數量（底倉的30%-50%）
    val reason: String,
    val expectedProfitPct: Double, // 預期收益率
    val periodType: String = ""   // 所屬週期
)

/**
 * 做T交易類型
 *
 * - T_BUY  ：做T買入（開倉腿），日內低買，等待高賣配對
 * - T_SELL ：做T賣出（配對腿），賣出之前做T買入的倉位，鎖定利潤
 * - RT_SELL：反T賣出（開倉腿），日內高賣底倉，等待低買回配對
 * - RT_BUY ：反T買回（配對腿），買回之前反T賣出的倉位，鎖定利潤
 */
enum class TTradeType(val label: String, val desc: String) {
    T_BUY("做T買入", "低買後當日高賣"),
    T_SELL("做T賣出", "賣出之前做T買入的倉位"),
    RT_SELL("反T賣出", "高賣後當日低買回"),
    RT_BUY("反T買回", "買回之前反T賣出的倉位")
}

/**
 * 做T統計
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
 * 在持有底倉的前提下，利用日內波動進行高拋低吸：
 * - 做T：價格接近支撐位時買入，反彈至阻力位賣出
 * - 反T：價格接近阻力位時賣出，回落至支撐位買回
 *
 * 每次做T不改變底倉總量，僅賺取日內差價。
 */
class TTradeEngine(private val context: Context) {

    /**
     * 生成做T信號
     * @param stockCode 股票代碼
     * @param basePositionQty 底倉數量
     * @param periodType 週期類型
     * @return 做T信號列表
     */
    suspend fun generateSignals(
        stockCode: String,
        basePositionQty: Int,
        periodType: String
    ): List<TTradeSignal> {
        val db = StockDatabase.getInstance(context)
        val snaps = db.dailySnapshotDao().getByCode(stockCode, 30).sortedBy { it.date }
        if (snaps.size < 10) return emptyList()

        val signals = mutableListOf<TTradeSignal>()
        val latest = snaps.last()
        val stockName = latest.name

        // 計算關鍵價位
        val ma5 = snaps.takeLast(5).map { it.close }.average()
        val ma10 = snaps.takeLast(10).map { it.close }.average()
        val window20 = snaps.takeLast(minOf(20, snaps.size))
        val recentLow = window20.map { it.low }.minOrNull() ?: latest.close
        val recentHigh = window20.map { it.high }.maxOrNull() ?: latest.close
        val avgBody = snaps.takeLast(10).map { abs(it.close - it.open) }.average()

        // 支撐位和阻力位
        val supportPrice = listOf(recentLow, ma5 * 0.98, ma10 * 0.97).maxOrNull() ?: latest.close
        val resistancePrice = listOf(recentHigh, ma5 * 1.02, ma10 * 1.03).minOrNull() ?: latest.close

        // 做T數量：底倉的30%-50%（取40%），並取整到100股
        val tQty = if (basePositionQty > 0) {
            ((basePositionQty * 0.4).toInt().coerceAtLeast(100) / 100) * 100
        } else 0

        // 當前價接近支撐位 → 做T買入信號（先買後賣）
        if (supportPrice > 0) {
            val priceToSupport = (latest.close - supportPrice) / supportPrice
            if (priceToSupport < 0.02 && tQty > 0) {
                val targetPrice = ma5 // 目標賣出在MA5附近
                val expectedPct = (targetPrice - latest.close) / latest.close * 100
                if (expectedPct > 0.5) { // 至少0.5%的預期收益
                    signals.add(
                        TTradeSignal(
                            stockCode = stockCode,
                            stockName = stockName,
                            signalType = TTradeType.T_BUY,
                            suggestedPrice = latest.close,
                            targetPrice = targetPrice,
                            quantity = tQty,
                            reason = "股價接近支撐位(${ "%.2f".format(supportPrice) })，MA5=${ "%.2f".format(ma5) }，預期反彈至MA5附近；近10日平均振幅${ "%.2f".format(avgBody) }",
                            expectedProfitPct = expectedPct,
                            periodType = periodType
                        )
                    )
                }
            }
        }

        // 當前價接近阻力位 → 反T賣出信號（先賣後買）
        if (resistancePrice > 0) {
            val priceToResistance = (resistancePrice - latest.close) / resistancePrice
            if (priceToResistance < 0.02 && tQty > 0) {
                val targetPrice = ma5 // 目標買回在MA5附近
                val expectedPct = (latest.close - targetPrice) / latest.close * 100
                if (expectedPct > 0.5) {
                    signals.add(
                        TTradeSignal(
                            stockCode = stockCode,
                            stockName = stockName,
                            signalType = TTradeType.RT_SELL,
                            suggestedPrice = latest.close,
                            targetPrice = targetPrice,
                            quantity = tQty,
                            reason = "股價接近阻力位(${ "%.2f".format(resistancePrice) })，MA5=${ "%.2f".format(ma5) }，預期回落至MA5附近；近10日平均振幅${ "%.2f".format(avgBody) }",
                            expectedProfitPct = expectedPct,
                            periodType = periodType
                        )
                    )
                }
            }
        }

        // 檢查是否有未配對的T交易需要配對
        val openTrades = db.tTradeRecordDao().getOpenTrades(periodType)
            .filter { it.stockCode == stockCode }
        for (openTrade in openTrades) {
            when (openTrade.tradeType) {
                "T_BUY" -> {
                    // 做T買入後，如果價格到達目標，生成賣出配對信號
                    if (latest.close >= openTrade.price * 1.005) { // 至少0.5%收益
                        signals.add(
                            TTradeSignal(
                                stockCode = stockCode,
                                stockName = stockName,
                                signalType = TTradeType.T_SELL,
                                suggestedPrice = latest.close,
                                targetPrice = latest.close,
                                quantity = openTrade.quantity,
                                reason = "做T買入(${openTrade.price})已到目標，賣出配對鎖定利潤",
                                expectedProfitPct = (latest.close - openTrade.price) / openTrade.price * 100,
                                periodType = periodType
                            )
                        )
                    }
                }
                "RT_SELL" -> {
                    // 反T賣出後，如果價格回落到目標，生成買回配對信號
                    if (latest.close <= openTrade.price * 0.995) {
                        signals.add(
                            TTradeSignal(
                                stockCode = stockCode,
                                stockName = stockName,
                                signalType = TTradeType.RT_BUY,
                                suggestedPrice = latest.close,
                                targetPrice = latest.close,
                                quantity = openTrade.quantity,
                                reason = "反T賣出(${openTrade.price})已到目標，買回配對鎖定利潤",
                                expectedProfitPct = (openTrade.price - latest.close) / openTrade.price * 100,
                                periodType = periodType
                            )
                        )
                    }
                }
            }
        }

        return signals
    }

    /**
     * 執行做T交易
     *
     * - 開倉腿（T_BUY / RT_SELL）：插入新的 OPEN 記錄
     * - 配對腿（T_SELL / RT_BUY）：找到對應的 OPEN 開倉腿並關閉，計算盈虧
     *
     * @return 開倉腿返回新記錄 id；配對腿返回被關閉的開倉腿 id（找不到時插入新記錄並返回其 id）
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

        // 配對腿：T_SELL 配對 T_BUY，RT_BUY 配對 RT_SELL
        if (signal.signalType == TTradeType.T_SELL || signal.signalType == TTradeType.RT_BUY) {
            val targetType = if (signal.signalType == TTradeType.T_SELL) "T_BUY" else "RT_SELL"
            val openTrade = db.tTradeRecordDao()
                .getOpenTrades(periodType)
                .firstOrNull { it.stockCode == signal.stockCode && it.tradeType == targetType }

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
            // 找不到對應開倉腿時，仍記錄該筆交易（便於事後核對）
        }

        // 開倉腿：插入新的 OPEN 記錄
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
     * 手動配對完成一筆未平倉的T交易
     * @param tradeId 開倉腿記錄 id（T_BUY 或 RT_SELL）
     * @param pairedPrice 配對價格（做T的賣出價 / 反T的買回價）
     */
    suspend fun closeTTrade(tradeId: Long, pairedPrice: Double) {
        val db = StockDatabase.getInstance(context)
        val trade = db.tTradeRecordDao().getById(tradeId) ?: return

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
     * 獲取做T統計
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
    // 做T推薦記錄管理
    // ═══════════════════════════════════════════════════

    /**
     * 將生成的做T信號保存為推薦記錄（自動去重）
     *
     * 同一股票、同一天、同一信號類型只保存一條。
     * 使用 IGNORE 策略，重複插入會被忽略。
     *
     * @param signals 做T信號列表
     * @param source 來源："REAL"=真實持倉 / "SIMULATED"=模擬持倉
     * @return 新保存的推薦數量
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

            // 檢查是否已存在相同的待處理推薦
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
            android.util.Log.i("TTradeEngine", "保存 $saved 條做T推薦 (source=$source, period=$periodType)")
        }
        return saved
    }

    /**
     * 獲取今日待處理的做T推薦
     */
    suspend fun getTodayPendingRecommendations(): List<TTradeRecommendationEntity> {
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        return db.tTradeRecommendationDao().getPendingByDate(today)
    }

    /**
     * 獲取今日所有做T推薦（含已處理）
     */
    suspend fun getTodayRecommendations(): List<TTradeRecommendationEntity> {
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        return db.tTradeRecommendationDao().getByDate(today)
    }

    /**
     * 獲取最近N天的做T推薦歷史
     */
    suspend fun getRecommendationHistory(days: Int = 7): List<TTradeRecommendationEntity> {
        val db = StockDatabase.getInstance(context)
        val startDate = java.time.LocalDate.now().minusDays(days.toLong()).toString()
        return db.tTradeRecommendationDao().getRecent(startDate, 200)
    }

    /**
     * 標記推薦為已執行
     */
    suspend fun markRecommendationExecuted(recommendationId: Long, executedPrice: Double) {
        val db = StockDatabase.getInstance(context)
        db.tTradeRecommendationDao().markExecuted(recommendationId, executedPrice)
        android.util.Log.i("TTradeEngine", "✅ 推薦 #$recommendationId 已標記為執行 @ $executedPrice")
    }

    /**
     * 標記推薦為已忽略
     */
    suspend fun markRecommendationIgnored(recommendationId: Long) {
        val db = StockDatabase.getInstance(context)
        db.tTradeRecommendationDao().markIgnored(recommendationId)
    }

    /**
     * 過期處理：將前一天仍為 PENDING 的推薦標記為 EXPIRED
     */
    suspend fun expireOldRecommendations(): Int {
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        val expired = db.tTradeRecommendationDao().expireOld(today)
        if (expired > 0) {
            android.util.Log.i("TTradeEngine", "⏰ $expired 條做T推薦已過期")
        }
        return expired
    }

    /**
     * 獲取今日推薦統計
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
    // 做T推薦結果跟蹤（收盤統計）
    // ═══════════════════════════════════════════════════

    /**
     * 跟蹤推薦結果：更新價格軌跡並檢查目標是否觸及
     *
     * 每次後台監控時調用，對所有 PENDING/TARGET_HIT 的推薦：
     * - 更新 peak/trough 價格
     * - 檢查目標價是否觸及
     *
     * @param currentPrices 當前價格 Map(stockCode -> price)
     */
    suspend fun trackOutcomeForRecommendations(currentPrices: Map<String, Double>) {
        if (currentPrices.isEmpty()) return
        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()
        val recs = db.tTradeRecommendationDao().getPendingByDate(today)
        var hitCount = 0

        for (rec in recs) {
            val price = currentPrices[rec.stockCode] ?: continue
            // 更新價格軌跡
            db.tTradeRecommendationDao().updatePriceTracking(rec.id, price)

            // 檢查目標是否觸及
            if (!rec.targetHit) {
                val hit = when (rec.signalType) {
                    "T_BUY" -> price >= rec.targetPrice  // 低買後價格漲到目標
                    "RT_SELL" -> price <= rec.targetPrice // 高賣後價格跌到目標
                    else -> false
                }
                if (hit) {
                    val profitPct = when (rec.signalType) {
                        "T_BUY" -> (rec.targetPrice - rec.suggestedPrice) / rec.suggestedPrice * 100
                        "RT_SELL" -> (rec.suggestedPrice - rec.targetPrice) / rec.suggestedPrice * 100
                        else -> 0.0
                    }
                    db.tTradeRecommendationDao().markTargetHit(rec.id, profitPct)
                    hitCount++
                }
            }
        }

        if (hitCount > 0) {
            android.util.Log.i("TTradeEngine", "做T跟蹤: $hitCount 條推薦目標價已觸及")
        }
    }

    /**
     * 收盤時標記當日所有未處理的推薦為 TARGET_MISSED
     * 並計算虛擬盈虧（基於收盤價 vs 推薦價）
     */
    suspend fun markDayEnd(date: String) {
        val db = StockDatabase.getInstance(context)
        // 先計算所有 PENDING 推薦的虛擬盈虧
        val pending = db.tTradeRecommendationDao().getPendingByDate(date)
        for (rec in pending) {
            val profitPct = when (rec.signalType) {
                "T_BUY" -> {
                    // 假設在推薦價買入，收盤時賣出
                    val peakOrClose = if (rec.peakPriceAfter > 0) rec.peakPriceAfter else rec.suggestedPrice
                    (peakOrClose - rec.suggestedPrice) / rec.suggestedPrice * 100
                }
                "RT_SELL" -> {
                    // 假設在推薦價賣出，收盤時買回
                    val troughOrClose = if (rec.troughPriceAfter > 0) rec.troughPriceAfter else rec.suggestedPrice
                    (rec.suggestedPrice - troughOrClose) / rec.suggestedPrice * 100
                }
                else -> 0.0
            }
            // 逐條更新虛擬盈虧（修復：避免批量覆蓋）
            db.tTradeRecommendationDao().markDayEndById(rec.id, profitPct)
        }
        android.util.Log.i("TTradeEngine", "收盤統計: $date 共 ${pending.size} 條推薦已結算")
    }

    /**
     * 獲取某日某週期的做T統計摘要
     */
    suspend fun getDailySummary(date: String, periodType: String): DailyTSummary {
        val db = StockDatabase.getInstance(context)
        val stats = db.tTradeRecommendationDao().getDayOutcomeStats(date, periodType)
        val details = db.tTradeRecommendationDao().getByPeriodAndDate(periodType, date)

        val total = stats?.total ?: 0
        val hitCount = stats?.hitCount ?: 0
        val executedCount = stats?.executedCount ?: 0
        val virtualSuccessRate = if (total > 0) hitCount.toDouble() / total * 100 else 0.0

        // 實際成功率：已執行中盈利的比例
        val executedList = details.filter { it.status == "EXECUTED" }
        val executedProfitable = executedList.count { it.executedPrice > 0 }
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
     * 獲取某日所有週期的做T統計摘要
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
        val executedProfitable = executedList.count { it.executedPrice > 0 }
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
     * 獲取做T成功率（跨天匯總）
     *
     * @param days 統計天數（默认7天）
     * @return TTradeSuccessRate 包含總成功率、做T/反T分別成功率、平均盈虧
     */
    suspend fun getSuccessRate(days: Int = 7): TTradeSuccessRate {
        val db = StockDatabase.getInstance(context)
        val startDate = java.time.LocalDate.now().minusDays(days.toLong()).toString()
        val stats = db.tTradeRecommendationDao().getSuccessRateStats(startDate)
            ?: return TTradeSuccessRate(days, 0, 0, 0, 0.0, 0.0, 0, 0, 0.0, 0.0, 0.0)

        val total = stats.total
        val hitCount = stats.hitCount
        val profitableCount = stats.profitableCount
        val settledCount = stats.tCount + stats.rtCount  // 已結算的記錄

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
 * 做T推薦統計
 */
data class RecommendationStats(
    val todayPending: Int,     // 今日待處理推薦數
    val weekExecuted: Int      // 近7天已執行推薦數
)

/** 做T成功率統計（跨天匯總） */
data class TTradeSuccessRate(
    val days: Int,
    val total: Int,
    val hitCount: Int,
    val profitableCount: Int,
    val overallSuccessRate: Double,   // 目標觸及率 %
    val profitRate: Double,           // 盈利比例 %
    val tCount: Int,                  // 做T次數
    val rtCount: Int,                 // 反T次數
    val tSuccessRate: Double,         // 做T成功率 %
    val rtSuccessRate: Double,        // 反T成功率 %
    val avgVirtualProfitPct: Double   // 平均虛擬盈虧 %
)
