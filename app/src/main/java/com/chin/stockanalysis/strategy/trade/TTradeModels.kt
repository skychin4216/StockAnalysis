package com.chin.stockanalysis.strategy.trade

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy
import androidx.room.Index
import androidx.room.ColumnInfo

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

/**
 * T+0 做T推薦記錄
 *
 * 系統在後台監控真實持倉時，自動生成做T推薦信號並持久化。
 * 與 t_trade_records（實際執行記錄）分離，用於：
 * - 回顧系統推薦了哪些做T機會
 * - 對比推薦 vs 實際執行，評估策略效果
 * - 避免重複推薦同一信號
 *
 * 生命週期：
 *   PENDING → EXECUTED（用戶執行了該推薦）
 *   PENDING → IGNORED（用戶忽略該推薦）
 *   PENDING → EXPIRED（超過當日未處理，自動過期）
 */
@Entity(
    tableName = "t_trade_recommendations",
    indices = [
        Index(value = ["stock_code", "trade_date", "signal_type"], unique = true),
        Index(value = ["status"]),
        Index(value = ["trade_date"])
    ]
)
data class TTradeRecommendationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "stock_code") val stockCode: String,
    @ColumnInfo(name = "stock_name") val stockName: String = "",
    @ColumnInfo(name = "trade_date") val tradeDate: String,           // 推薦日期 yyyy-MM-dd
    @ColumnInfo(name = "signal_type") val signalType: String,         // T_BUY / T_SELL / RT_SELL / RT_BUY
    @ColumnInfo(name = "suggested_price") val suggestedPrice: Double, // 推薦價格
    @ColumnInfo(name = "target_price") val targetPrice: Double,       // 目標配對價格
    @ColumnInfo(name = "quantity") val quantity: Int,                 // 建議數量
    @ColumnInfo(name = "expected_profit_pct") val expectedProfitPct: Double, // 預期收益率
    @ColumnInfo(name = "reason") val reason: String = "",             // 推薦原因
    @ColumnInfo(name = "status") val status: String = "PENDING",      // PENDING / EXECUTED / IGNORED / EXPIRED / TARGET_HIT / TARGET_MISSED
    @ColumnInfo(name = "source") val source: String = "REAL",         // REAL=真實持倉 / SIMULATED=模擬持倉
    @ColumnInfo(name = "period_type") val periodType: String = "",    // 所屬週期：UltraShortQuant / ShortTermQuant / MidTermQuant / LongTermQuant / RealPosition
    @ColumnInfo(name = "executed_price") val executedPrice: Double = 0.0, // 實際執行價格
    @ColumnInfo(name = "executed_at") val executedAt: Long = 0,       // 執行時間戳
    @ColumnInfo(name = "peak_price_after") val peakPriceAfter: Double = 0.0,  // 推薦後最高價（T_BUY/RT_SELL用）
    @ColumnInfo(name = "trough_price_after") val troughPriceAfter: Double = 0.0, // 推薦後最低價（RT_SELL/T_BUY用）
    @ColumnInfo(name = "target_hit") val targetHit: Boolean = false,  // 目標價是否觸及
    @ColumnInfo(name = "virtual_profit_pct") val virtualProfitPct: Double = 0.0, // 虛擬盈虧%（假設在目標價配對）
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TTradeRecommendationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(recommendation: TTradeRecommendationEntity): Long

    @Query("SELECT * FROM t_trade_recommendations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TTradeRecommendationEntity?

    @Query("SELECT * FROM t_trade_recommendations WHERE trade_date = :date AND status = 'PENDING' ORDER BY created_at DESC")
    suspend fun getPendingByDate(date: String): List<TTradeRecommendationEntity>

    @Query("SELECT * FROM t_trade_recommendations WHERE trade_date = :date ORDER BY created_at DESC")
    suspend fun getByDate(date: String): List<TTradeRecommendationEntity>

    @Query("SELECT * FROM t_trade_recommendations WHERE trade_date >= :startDate ORDER BY trade_date DESC, created_at DESC LIMIT :limit")
    suspend fun getRecent(startDate: String, limit: Int = 100): List<TTradeRecommendationEntity>

    @Query("SELECT * FROM t_trade_recommendations WHERE stock_code = :code ORDER BY trade_date DESC LIMIT :limit")
    suspend fun getByCode(code: String, limit: Int = 30): List<TTradeRecommendationEntity>

    @Query("UPDATE t_trade_recommendations SET status = 'EXECUTED', executed_price = :price, executed_at = :executedAt WHERE id = :id")
    suspend fun markExecuted(id: Long, price: Double, executedAt: Long = System.currentTimeMillis())

    @Query("UPDATE t_trade_recommendations SET status = 'IGNORED' WHERE id = :id")
    suspend fun markIgnored(id: Long)

    @Query("UPDATE t_trade_recommendations SET status = 'EXPIRED' WHERE status = 'PENDING' AND trade_date < :today")
    suspend fun expireOld(today: String): Int

    @Query("SELECT COUNT(*) FROM t_trade_recommendations WHERE status = 'PENDING' AND trade_date = :date")
    suspend fun getPendingCount(date: String): Int

    @Query("SELECT COUNT(*) FROM t_trade_recommendations WHERE status = 'EXECUTED' AND trade_date >= :startDate")
    suspend fun getExecutedCount(startDate: String): Int

    @Query("SELECT COUNT(*) FROM t_trade_recommendations WHERE status = 'PENDING' AND trade_date = :date AND stock_code = :code AND signal_type = :signalType")
    suspend fun existsPending(date: String, code: String, signalType: String): Int

    /** 更新推薦後的最高/最低價（每次監控時調用） */
    @Query("""UPDATE t_trade_recommendations SET 
        peak_price_after = MAX(peak_price_after, :currentPrice),
        trough_price_after = CASE WHEN trough_price_after = 0.0 THEN :currentPrice ELSE MIN(trough_price_after, :currentPrice) END
        WHERE id = :id""")
    suspend fun updatePriceTracking(id: Long, currentPrice: Double)

    /** 標記目標價已觸及（虛擬成功） */
    @Query("""UPDATE t_trade_recommendations SET 
        target_hit = 1, 
        virtual_profit_pct = :profitPct,
        status = CASE WHEN status = 'PENDING' THEN 'TARGET_HIT' ELSE status END
        WHERE id = :id""")
    suspend fun markTargetHit(id: Long, profitPct: Double)

    /** 收盤時標記未觸及目標的推薦（按 ID 逐條更新，避免批量覆蓋） */
    @Query("""UPDATE t_trade_recommendations SET 
        virtual_profit_pct = :profitPct,
        status = CASE WHEN status = 'PENDING' THEN 'TARGET_MISSED' ELSE status END
        WHERE id = :id""")
    suspend fun markDayEndById(id: Long, profitPct: Double)

    /** 收盤時標記未觸及目標的推薦（兼容舊調用） */
    @Query("""UPDATE t_trade_recommendations SET 
        virtual_profit_pct = :profitPct,
        status = CASE WHEN status = 'PENDING' THEN 'TARGET_MISSED' ELSE status END
        WHERE trade_date = :date AND status = 'PENDING'""")
    suspend fun markDayEnd(date: String, profitPct: Double = 0.0)

    /** 獲取某段時間範圍內的做T成功率統計 */
    @Query("""SELECT 
        COUNT(*) as total,
        SUM(CASE WHEN target_hit = 1 THEN 1 ELSE 0 END) as hit_count,
        SUM(CASE WHEN status = 'EXECUTED' THEN 1 ELSE 0 END) as executed_count,
        SUM(CASE WHEN status IN ('TARGET_HIT','TARGET_MISSED','EXECUTED') AND virtual_profit_pct > 0 THEN 1 ELSE 0 END) as profitable_count,
        AVG(virtual_profit_pct) as avg_virtual_profit,
        SUM(CASE WHEN signal_type IN ('T_BUY','T_SELL') THEN 1 ELSE 0 END) as t_count,
        SUM(CASE WHEN signal_type IN ('RT_SELL','RT_BUY') THEN 1 ELSE 0 END) as rt_count,
        SUM(CASE WHEN signal_type IN ('T_BUY','T_SELL') AND target_hit = 1 THEN 1 ELSE 0 END) as t_hit,
        SUM(CASE WHEN signal_type IN ('RT_SELL','RT_BUY') AND target_hit = 1 THEN 1 ELSE 0 END) as rt_hit
        FROM t_trade_recommendations 
        WHERE trade_date >= :startDate""")
    suspend fun getSuccessRateStats(startDate: String): SuccessRateStatsRow?

    /** 獲取某日某週期的推薦結果統計 */
    @Query("""SELECT 
        COUNT(*) as total,
        SUM(CASE WHEN target_hit = 1 THEN 1 ELSE 0 END) as hit_count,
        SUM(CASE WHEN status = 'EXECUTED' THEN 1 ELSE 0 END) as executed_count,
        AVG(CASE WHEN target_hit = 1 THEN virtual_profit_pct ELSE 0 END) as avg_hit_profit,
        AVG(virtual_profit_pct) as avg_virtual_profit
        FROM t_trade_recommendations 
        WHERE trade_date = :date AND period_type = :periodType""")
    suspend fun getDayOutcomeStats(date: String, periodType: String): OutcomeStatsRow?

    /** 獲取某日所有週期的推薦結果統計 */
    @Query("""SELECT 
        COUNT(*) as total,
        SUM(CASE WHEN target_hit = 1 THEN 1 ELSE 0 END) as hit_count,
        SUM(CASE WHEN status = 'EXECUTED' THEN 1 ELSE 0 END) as executed_count,
        AVG(CASE WHEN target_hit = 1 THEN virtual_profit_pct ELSE 0 END) as avg_hit_profit,
        AVG(virtual_profit_pct) as avg_virtual_profit
        FROM t_trade_recommendations 
        WHERE trade_date = :date""")
    suspend fun getDayAllPeriodStats(date: String): OutcomeStatsRow?

    /** 獲取指定週期今日推薦 */
    @Query("SELECT * FROM t_trade_recommendations WHERE trade_date = :date AND period_type = :periodType ORDER BY created_at DESC")
    suspend fun getByPeriodAndDate(periodType: String, date: String): List<TTradeRecommendationEntity>
}

/** 推薦結果統計（DAO 查詢返回行） */
data class OutcomeStatsRow(
    val total: Int,
    @ColumnInfo(name = "hit_count") val hitCount: Int,
    @ColumnInfo(name = "executed_count") val executedCount: Int,
    @ColumnInfo(name = "avg_hit_profit") val avgHitProfit: Double?,
    @ColumnInfo(name = "avg_virtual_profit") val avgVirtualProfit: Double?
)

/** 做T收盤統計摘要 */
data class DailyTSummary(
    val date: String,
    val periodType: String,
    val totalRecommendations: Int,
    val targetHitCount: Int,
    val executedCount: Int,
    val virtualSuccessRate: Double,   // targetHit / total * 100
    val actualSuccessRate: Double,    // executed中盈利 / executed * 100
    val avgVirtualProfitPct: Double,  // 平均虛擬盈虧%
    val details: List<TTradeRecommendationEntity>
)

/** 做T成功率統計（跨天匯總） */
data class SuccessRateStatsRow(
    val total: Int,
    @ColumnInfo(name = "hit_count") val hitCount: Int,
    @ColumnInfo(name = "executed_count") val executedCount: Int,
    @ColumnInfo(name = "profitable_count") val profitableCount: Int,
    @ColumnInfo(name = "avg_virtual_profit") val avgVirtualProfit: Double?,
    @ColumnInfo(name = "t_count") val tCount: Int,
    @ColumnInfo(name = "rt_count") val rtCount: Int,
    @ColumnInfo(name = "t_hit") val tHit: Int,
    @ColumnInfo(name = "rt_hit") val rtHit: Int
)
