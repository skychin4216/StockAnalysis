package com.chin.stockanalysis.strategy.backtest

import androidx.room.*

/**
 * ## 盤中分鐘 K 線實體
 *
 * 存儲盤中分鐘級 OHLCV 數據，用於盤中分析。
 * 僅保留當日數據，每日自動清理過期記錄。
 */
@Entity(
    tableName = "intraday_kline",
    indices = [
        Index(value = ["code", "datetime", "interval_min"], unique = true),
        Index(value = ["date"])
    ]
)
data class IntradayKlineEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "code")
    val code: String,              // sh600519

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "datetime")
    val datetime: String,          // "2025-01-15 10:30"

    @ColumnInfo(name = "date")
    val date: String,              // "2025-01-15" (方便按日清理)

    @ColumnInfo(name = "open")
    val open: Double,

    @ColumnInfo(name = "close")
    val close: Double,

    @ColumnInfo(name = "high")
    val high: Double,

    @ColumnInfo(name = "low")
    val low: Double,

    @ColumnInfo(name = "volume")
    val volume: Long,

    @ColumnInfo(name = "amount")
    val amount: Double = 0.0,

    @ColumnInfo(name = "interval_min")
    val intervalMin: Int = 5       // 1, 5, 15, 30, 60
)

/**
 * ## 盤中 K 線 DAO
 */
@Dao
interface IntradayKlineDao {

    /** 獲取某只股票當日的全部分鐘線 */
    @Query("SELECT * FROM intraday_kline WHERE code = :code AND date = :date ORDER BY datetime ASC")
    suspend fun getByCodeToday(code: String, date: String): List<IntradayKlineEntity>

    /** 獲取多只股票當日的分鐘線 */
    @Query("SELECT * FROM intraday_kline WHERE code IN (:codes) AND date = :date ORDER BY code, datetime ASC")
    suspend fun getByCodesToday(codes: List<String>, date: String): List<IntradayKlineEntity>

    /** 批量插入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(klines: List<IntradayKlineEntity>)

    /** 清理指定日期之前的數據 */
    @Query("DELETE FROM intraday_kline WHERE date < :beforeDate")
    suspend fun deleteOlderThan(beforeDate: String)

    /** 清理指定日期的數據 */
    @Query("DELETE FROM intraday_kline WHERE date = :date")
    suspend fun deleteByDate(date: String)

    /** 統計當日記錄數 */
    @Query("SELECT COUNT(*) FROM intraday_kline WHERE date = :date")
    suspend fun countToday(date: String): Int
}
