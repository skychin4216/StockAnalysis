package com.chin.stockanalysis.stock.database

import androidx.room.*

/**
 * ## AI 精选股（独立于自选股）
 *
 * 只保存当天的最新精选结果。
 * 下一个交易日 AppBackgroundRunner 会将前一天的 AI 精选股自动迁移到 user_watchlist（股票中心）。
 *
 * 设计原则：
 * - 每天运行策略时先清除旧数据，再写入当天最新精选
 * - AppBackgroundRunner 同时监控 user_watchlist 和 ai_selected_stock
 * - 切换交易日时自动迁移前一天数据到 user_watchlist
 */
@Entity(tableName = "ai_selected_stock", indices = [Index(value = ["stock_code", "selected_date"], unique = true)])
data class AiSelectedStockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "stock_code") val stockCode: String,
    @ColumnInfo(name = "stock_name") val stockName: String,
    @ColumnInfo(name = "source") val source: String,          // "shortterm" / "midterm" / "agent" / "unified" / "preselection"
    @ColumnInfo(name = "selected_date") val selectedDate: String,  // yyyy-MM-dd
    @ColumnInfo(name = "score") val score: Int = 0,
    @ColumnInfo(name = "reason") val reason: String = "",
    @ColumnInfo(name = "buy_price") val buyPrice: Double = 0.0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()  // 选中时间戳（毫秒），用于排序最新选中显示在最上方
)