package com.chin.stockanalysis.strategy.trade

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy

/**
 * T+0 日內交易記錄（做T/反T）
 *
 * 做T：先買後賣 — 日內低買高賣，賺取差價
 * 反T：先賣後買 — 日內高賣低買，賺取差價
 *
 * 適用於A股「持有底倉、日內高拋低吸」的策略：
 * - 做T買入(T_BUY)後，當日賣出(T_SELL)配對，鎖定差價利潤，底倉數量不變
 * - 反T賣出(RT_SELL)底倉後，當日買回(RT_BUY)配對，鎖定差價利潤，底倉數量不變
 */
@Entity(tableName = "t_trade_records")
data class TTradeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stockCode: String,
    val stockName: String,
    val tradeDate: String,           // 交易日期 yyyy-MM-dd
    val tradeType: String,           // "T_BUY" 做T買入, "T_SELL" 做T賣出, "RT_SELL" 反T賣出, "RT_BUY" 反T買回
    val quantity: Int,               // 交易數量
    val price: Double,               // 交易價格
    val pairedPrice: Double = 0.0,   // 配對價格（做T的賣出價 / 反T的買回價）
    val profit: Double = 0.0,        // 本次T交易盈虧
    val profitPct: Double = 0.0,     // 盈虧百分比
    val status: String = "OPEN",     // OPEN=未配對, CLOSED=已配對完成
    val periodType: String,          // "UltraShortQuant" / "ShortTermQuant" / "MidTermQuant" / "LongTermQuant"
    val basePositionQty: Int = 0,    // 當時的底倉數量
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TTradeRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TTradeRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<TTradeRecordEntity>)

    @Query("SELECT * FROM t_trade_records WHERE stockCode = :code ORDER BY tradeDate DESC LIMIT :limit")
    suspend fun getByCode(code: String, limit: Int = 50): List<TTradeRecordEntity>

    @Query("SELECT * FROM t_trade_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TTradeRecordEntity?

    @Query("SELECT * FROM t_trade_records WHERE periodType = :period AND status = 'OPEN' ORDER BY tradeDate DESC")
    suspend fun getOpenTrades(period: String): List<TTradeRecordEntity>

    @Query("SELECT * FROM t_trade_records WHERE periodType = :period AND tradeDate = :date ORDER BY createdAt DESC")
    suspend fun getByPeriodAndDate(period: String, date: String): List<TTradeRecordEntity>

    @Query("SELECT * FROM t_trade_records WHERE periodType = :period AND tradeDate >= :startDate ORDER BY tradeDate DESC")
    suspend fun getRecentByPeriod(period: String, startDate: String): List<TTradeRecordEntity>

    @Query("UPDATE t_trade_records SET status = 'CLOSED', pairedPrice = :pairedPrice, profit = :profit, profitPct = :profitPct WHERE id = :id")
    suspend fun closeTrade(id: Long, pairedPrice: Double, profit: Double, profitPct: Double)

    @Query("SELECT COUNT(*) FROM t_trade_records WHERE periodType = :period AND status = 'OPEN'")
    suspend fun getOpenCount(period: String): Int

    @Query("SELECT SUM(profit) FROM t_trade_records WHERE periodType = :period AND status = 'CLOSED'")
    suspend fun getTotalProfit(period: String): Double?

    @Query("SELECT COUNT(*) FROM t_trade_records WHERE periodType = :period AND status = 'CLOSED' AND profit > 0")
    suspend fun getWinCount(period: String): Int

    @Query("SELECT COUNT(*) FROM t_trade_records WHERE periodType = :period AND status = 'CLOSED'")
    suspend fun getTotalClosedCount(period: String): Int
}
