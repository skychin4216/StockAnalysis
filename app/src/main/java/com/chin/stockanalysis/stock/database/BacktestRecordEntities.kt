package com.chin.stockanalysis.stock.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 工作台「回溯+拟合」增量状态与选中记录持久化。
 *
 * - backtest_meta：按周期记录「最后已回溯的信号日」，实现增量回溯；也存拟合参数矩阵 JSON。
 * - backtest_selected_stock：每次回溯选中的股票记录。
 *   中/长线长期保留；超短/短线只保留最近 30 天（由引擎定期清理）。
 */
@Entity(tableName = "backtest_meta")
data class BacktestMetaEntity(
    @PrimaryKey @ColumnInfo(name = "meta_key") val key: String,
    @ColumnInfo(name = "meta_value") val value: String
)

@Entity(
    tableName = "backtest_selected_stock",
    indices = [
        Index(value = ["period", "signal_date", "code"], unique = true),
        Index(value = ["signal_date"])
    ]
)
data class BacktestSelectedStockEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "period") val period: String,
    @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "signal_date") val signalDate: String,
    @ColumnInfo(name = "market_state") val marketState: String,
    @ColumnInfo(name = "buy_date") val buyDate: String,
    @ColumnInfo(name = "buy_price") val buyPrice: Double,
    @ColumnInfo(name = "sell_date") val sellDate: String?,
    @ColumnInfo(name = "sell_price") val sellPrice: Double?,
    @ColumnInfo(name = "ret_pct") val retPct: Double,
    @ColumnInfo(name = "exit_reason") val exitReason: String,
    @ColumnInfo(name = "t_profit_pct") val tProfitPct: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
