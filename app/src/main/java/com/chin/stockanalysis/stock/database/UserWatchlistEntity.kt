package com.chin.stockanalysis.stock.database

import androidx.room.*

/**
 * 用戶自選股（策略精選 → 後臺監控買賣點 → 自動交易）
 */
@Entity(tableName = "user_watchlist", indices = [Index(value = ["stock_code"], unique = true)])
data class UserWatchlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "stock_code") val stockCode: String,
    @ColumnInfo(name = "stock_name") val stockName: String,
    @ColumnInfo(name = "source") val source: String,       // "midterm" / "shortterm" / "manual"
    @ColumnInfo(name = "added_date") val addedDate: String,
    @ColumnInfo(name = "status") val status: String = "WATCHING", // WATCHING / BOUGHT / SOLD
    @ColumnInfo(name = "buy_price") val buyPrice: Double = 0.0,
    @ColumnInfo(name = "buy_date") val buyDate: String = "",
    @ColumnInfo(name = "sell_price") val sellPrice: Double = 0.0,
    @ColumnInfo(name = "sell_date") val sellDate: String = "",
    @ColumnInfo(name = "score_at_add") val scoreAtAdd: Int = 0,
    @ColumnInfo(name = "last_monitor_time") val lastMonitorTime: Long = 0L
)