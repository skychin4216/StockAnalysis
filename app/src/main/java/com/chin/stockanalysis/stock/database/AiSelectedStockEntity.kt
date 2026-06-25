package com.chin.stockanalysis.stock.database

import androidx.room.*

/**
 * ## AI 精選股（獨立於自選股）
 *
 * 只保存當天的最新精選結果。
 * 下一個交易日 AppBackgroundRunner 會將前一天的 AI 精選股自動遷移到 user_watchlist（股票中心）。
 *
 * 設計原則：
 * - 每天運行策略時先清除舊數據，再寫入當天最新精選
 * - AppBackgroundRunner 同時監控 user_watchlist 和 ai_selected_stock
 * - 切換交易日時自動遷移前一天數據到 user_watchlist
 */
@Entity(tableName = "ai_selected_stock", indices = [Index(value = ["stock_code", "selected_date"], unique = true)])
data class AiSelectedStockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "stock_code") val stockCode: String,
    @ColumnInfo(name = "stock_name") val stockName: String,
    @ColumnInfo(name = "source") val source: String,          // "shortterm" / "midterm" / "agent"
    @ColumnInfo(name = "selected_date") val selectedDate: String,  // yyyy-MM-dd
    @ColumnInfo(name = "score") val score: Int = 0,
    @ColumnInfo(name = "reason") val reason: String = "",
    @ColumnInfo(name = "buy_price") val buyPrice: Double = 0.0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)