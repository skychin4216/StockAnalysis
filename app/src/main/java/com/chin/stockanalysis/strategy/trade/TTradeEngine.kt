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
    val expectedProfitPct: Double // 預期收益率
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
                            expectedProfitPct = expectedPct
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
                            expectedProfitPct = expectedPct
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
                                expectedProfitPct = (latest.close - openTrade.price) / openTrade.price * 100
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
                                expectedProfitPct = (openTrade.price - latest.close) / openTrade.price * 100
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
}
