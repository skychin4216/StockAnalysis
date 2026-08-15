package com.chin.stockanalysis.strategy.trade

import java.time.LocalTime

/**
 * ## 交易模型（从 SimulationTradeEngine 拆分）
 *
 * 包含所有交易相关的 data class 和 Room Entity。
 * 保持原 package 不变，所有现有 import 无需修改。
 */

// ═══ 轻量交易订单（DAG GenerateOrdersNode 使用） ═══

data class TradeOrder(
    val stockCode: String, val stockName: String, val strategyId: String,
    val tradeDate: String, val buyPrice: Double, val quantity: Int,
    val buyTime: String = LocalTime.now().toString().take(8),
    val reason: String = "", val scoreAtBuy: Int = 0,
    val orderType: String = "模拟买入", var status: String = "PENDING"
)

// ═══ Room Entities ═══

@androidx.room.Entity(tableName = "daily_period_result", indices = [androidx.room.Index(value = ["strategy_id", "trade_date", "period_days"], unique = true)])
data class DailyPeriodResultEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "strategy_id") val strategyId: String,
    @androidx.room.ColumnInfo(name = "strategy_name") val strategyName: String,
    @androidx.room.ColumnInfo(name = "trade_date") val tradeDate: String,
    @androidx.room.ColumnInfo(name = "period_days") val periodDays: Int,
    @androidx.room.ColumnInfo(name = "stock_codes_json") val stockCodesJson: String,
    @androidx.room.ColumnInfo(name = "stock_count") val stockCount: Int,
    @androidx.room.ColumnInfo(name = "news_strength_score") val newsStrengthScore: Int,
    @androidx.room.ColumnInfo(name = "rotation_penalty") val rotationPenalty: Int,
    @androidx.room.ColumnInfo(name = "main_board_filter") val mainBoardFilter: Boolean,
    @androidx.room.ColumnInfo(name = "filtered_codes_json") val filteredCodesJson: String,
    @androidx.room.ColumnInfo(name = "filtered_reason_json") val filteredReasonJson: String,
    @androidx.room.ColumnInfo(name = "final_top3_json") val finalTop3Json: String,
    @androidx.room.ColumnInfo(name = "ai_selection_reason") val aiSelectionReason: String,
    @androidx.room.ColumnInfo(name = "pipeline_flow_json") val pipelineFlowJson: String = "",
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long
)

@androidx.room.Entity(tableName = "strategy_trade_fitting_params", indices = [androidx.room.Index(value = ["strategy_id", "trade_date", "period_days", "fitting_round"])])
data class StrategyTradeFittingParamEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "strategy_id") val strategyId: String,
    @androidx.room.ColumnInfo(name = "trade_date") val tradeDate: String,
    @androidx.room.ColumnInfo(name = "period_days") val periodDays: Int,
    @androidx.room.ColumnInfo(name = "param_json") val paramJson: String,
    @androidx.room.ColumnInfo(name = "fitting_round") val fittingRound: Int,
    @androidx.room.ColumnInfo(name = "accuracy") val accuracy: Double,
    @androidx.room.ColumnInfo(name = "avg_return") val avgReturn: Double,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long
)

@androidx.room.Entity(tableName = "daily_news_hot_picks", indices = [androidx.room.Index(value = ["news_date", "rank"], unique = true)])
data class DailyNewsHotPickEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "news_date") val newsDate: String,
    @androidx.room.ColumnInfo(name = "rank") val rank: Int,
    @androidx.room.ColumnInfo(name = "sector_name") val sectorName: String,
    @androidx.room.ColumnInfo(name = "sub_sector_name") val subSectorName: String,
    @androidx.room.ColumnInfo(name = "hot_score") val hotScore: Int,
    @androidx.room.ColumnInfo(name = "news_title") val newsTitle: String,
    @androidx.room.ColumnInfo(name = "related_stock_codes") val relatedStockCodes: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@androidx.room.Entity(tableName = "strategy_trade_orders", indices = [androidx.room.Index(value = ["strategy_id", "trade_date"]), androidx.room.Index(value = ["stock_code", "trade_date"])])
data class StrategyTradeOrderEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "strategy_id") val strategyId: String,
    @androidx.room.ColumnInfo(name = "stock_code") val stockCode: String,
    @androidx.room.ColumnInfo(name = "stock_name") val stockName: String,
    @androidx.room.ColumnInfo(name = "trade_date") val tradeDate: String,
    @androidx.room.ColumnInfo(name = "buy_price") val buyPrice: Double,
    @androidx.room.ColumnInfo(name = "buy_time") val buyTime: String,
    @androidx.room.ColumnInfo(name = "quantity") val quantity: Int,
    @androidx.room.ColumnInfo(name = "order_type") val orderType: String,
    @androidx.room.ColumnInfo(name = "sell_price") val sellPrice: Double = 0.0,
    @androidx.room.ColumnInfo(name = "sell_time") val sellTime: String = "",
    @androidx.room.ColumnInfo(name = "profit_pct") val profitPct: Double = 0.0,
    @androidx.room.ColumnInfo(name = "status") val status: String,
    @androidx.room.ColumnInfo(name = "reason") val reason: String,
    @androidx.room.ColumnInfo(name = "score_at_buy") val scoreAtBuy: Int,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

// ═══ DAO ═══

@androidx.room.Dao
interface DailyPeriodResultDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyPeriodResultEntity): Long
    @androidx.room.Query("SELECT * FROM daily_period_result WHERE trade_date = :date ORDER BY period_days")
    suspend fun getByDate(date: String): List<DailyPeriodResultEntity>
    @androidx.room.Query("SELECT * FROM daily_period_result ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<DailyPeriodResultEntity>
    @androidx.room.Query("SELECT DISTINCT trade_date FROM daily_period_result ORDER BY trade_date DESC LIMIT :limit")
    suspend fun getAvailableDates(limit: Int = 30): List<String>
    @androidx.room.Query("DELETE FROM daily_period_result WHERE trade_date = :date")
    suspend fun deleteByDate(date: String)
}

@androidx.room.Dao
interface StrategyTradeFittingParamDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StrategyTradeFittingParamEntity): Long
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<StrategyTradeFittingParamEntity>)
    @androidx.room.Query("SELECT * FROM strategy_trade_fitting_params WHERE strategy_id = :sid ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentByStrategy(sid: String, limit: Int = 100): List<StrategyTradeFittingParamEntity>
    @androidx.room.Query("SELECT MAX(accuracy) FROM strategy_trade_fitting_params WHERE strategy_id = :sid AND trade_date = :date AND period_days = :period")
    suspend fun getBestAccuracy(sid: String, date: String, period: Int): Double?
}

// ═══ 历史回溯结果落库（P0：按周期分别保存，供工作台统一对比 / Agent 跨周期分析） ═══

@androidx.room.Entity(
    tableName = "strategy_trade_backtests",
    indices = [
        androidx.room.Index(value = ["period_key", "strategy_id", "trade_date"], unique = true)
    ]
)
data class StrategyTradeBacktestEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "period_key") val periodKey: String,
    @androidx.room.ColumnInfo(name = "strategy_id") val strategyId: String,
    @androidx.room.ColumnInfo(name = "strategy_name") val strategyName: String,
    @androidx.room.ColumnInfo(name = "trade_date") val tradeDate: String,
    @androidx.room.ColumnInfo(name = "total_days") val totalDays: Int,
    @androidx.room.ColumnInfo(name = "signal_count") val signalCount: Int,
    @androidx.room.ColumnInfo(name = "correct_count") val correctCount: Int,
    @androidx.room.ColumnInfo(name = "accuracy") val accuracy: Double,
    @androidx.room.ColumnInfo(name = "avg_return") val avgReturn: Double,
    @androidx.room.ColumnInfo(name = "max_gain") val maxGain: Double,
    @androidx.room.ColumnInfo(name = "max_loss") val maxLoss: Double,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@androidx.room.Dao
interface StrategyTradeBacktestDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<StrategyTradeBacktestEntity>)
    @androidx.room.Query("SELECT * FROM strategy_trade_backtests WHERE period_key = :periodKey ORDER BY trade_date DESC, accuracy DESC")
    suspend fun getByPeriod(periodKey: String): List<StrategyTradeBacktestEntity>
    @androidx.room.Query("SELECT * FROM strategy_trade_backtests ORDER BY trade_date DESC, period_key, accuracy DESC")
    suspend fun getAll(): List<StrategyTradeBacktestEntity>
    @androidx.room.Query("SELECT * FROM strategy_trade_backtests WHERE period_key = :periodKey AND trade_date = :tradeDate ORDER BY accuracy DESC")
    suspend fun getByPeriodAndDate(periodKey: String, tradeDate: String): List<StrategyTradeBacktestEntity>
    @androidx.room.Query("SELECT DISTINCT trade_date FROM strategy_trade_backtests ORDER BY trade_date DESC LIMIT :limit")
    suspend fun getAvailableDates(limit: Int = 30): List<String>
    @androidx.room.Query("DELETE FROM strategy_trade_backtests WHERE period_key = :periodKey AND trade_date = :tradeDate")
    suspend fun deleteByPeriodAndDate(periodKey: String, tradeDate: String)
}

@androidx.room.Dao
interface DailyNewsHotPickDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyNewsHotPickEntity): Long
    @androidx.room.Query("SELECT * FROM daily_news_hot_picks WHERE news_date = :date ORDER BY rank ASC")
    suspend fun getByDate(date: String): List<DailyNewsHotPickEntity>
    @androidx.room.Query("SELECT DISTINCT news_date FROM daily_news_hot_picks ORDER BY news_date DESC LIMIT 100")
    suspend fun getAvailableDates(): List<String>
}

@androidx.room.Dao
interface StrategyTradeOrderDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StrategyTradeOrderEntity): Long
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<StrategyTradeOrderEntity>)
    @androidx.room.Query("SELECT * FROM strategy_trade_orders WHERE trade_date = :date ORDER BY score_at_buy DESC")
    suspend fun getByDate(date: String): List<StrategyTradeOrderEntity>
    @androidx.room.Query("SELECT * FROM strategy_trade_orders ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<StrategyTradeOrderEntity>
    @androidx.room.Query("SELECT * FROM strategy_trade_orders WHERE strategy_id = :sid ORDER BY created_at DESC LIMIT :limit")
    suspend fun getByStrategy(sid: String, limit: Int = 50): List<StrategyTradeOrderEntity>
    @androidx.room.Query("UPDATE strategy_trade_orders SET status = :status, sell_price = :sellPrice, sell_time = :sellTime, profit_pct = :profitPct WHERE id = :id")
    suspend fun updateSellInfo(id: Long, status: String, sellPrice: Double, sellTime: String, profitPct: Double)
    @androidx.room.Query("UPDATE strategy_trade_orders SET quantity = :quantity, buy_price = :buyPrice WHERE id = :id")
    suspend fun updateQuantityAndPrice(id: Long, quantity: Int, buyPrice: Double)
    @androidx.room.Query("SELECT SUM(profit_pct) FROM strategy_trade_orders WHERE strategy_id = :sid AND status = 'SOLD'")
    suspend fun getTotalProfit(sid: String): Double?
    @androidx.room.Query("SELECT COUNT(*) FROM strategy_trade_orders WHERE strategy_id = :sid AND status = 'SOLD' AND profit_pct > 0")
    suspend fun getWinCount(sid: String): Int
    @androidx.room.Query("SELECT COUNT(*) FROM strategy_trade_orders WHERE strategy_id = :sid AND status = 'SOLD'")
    suspend fun getTotalSoldCount(sid: String): Int
    @androidx.room.Query("DELETE FROM strategy_trade_orders WHERE trade_date = :date")
    suspend fun deleteByDate(date: String)
    @androidx.room.Query("DELETE FROM strategy_trade_orders WHERE id = :id")
    suspend fun deleteById(id: Long)
    @androidx.room.Query("UPDATE strategy_trade_orders SET quantity = :quantity WHERE id = :id")
    suspend fun updateQuantity(id: Long, quantity: Int)
    @androidx.room.Query("UPDATE strategy_trade_orders SET buy_price = :price, quantity = :qty, trade_date = :date WHERE id = :id")
    suspend fun updateBuyPriceAndQty(id: Long, price: Double, qty: Int, date: String)
    @androidx.room.Query("UPDATE strategy_trade_orders SET stock_name = :name WHERE id = :id")
    suspend fun updateStockName(id: Long, name: String)
    @androidx.room.Query("UPDATE strategy_trade_orders SET reason = :reason WHERE id = :id")
    suspend fun updateReason(id: Long, reason: String)
}

// ═══ 周期持有收益摘要 Entity（每个周期独立固化） ═══

/**
 * ## 周期持有收益摘要实体
 *
 * 每次刷新持仓时，将各周期（超短/短/中/长线）的持有收益固化到此表。
 * 同一周期同一交易日只保留一条记录（REPLACE 策略）。
 *
 * @property periodType  周期类型（UltraShortQuant / ShortTermQuant / MidTermQuant / LongTermQuant）
 * @property tradeDate   交易日
 * @property holdingCount 持仓股票数量
 * @property totalCost   总买入成本
 * @property totalValue  最新总市值
 * @property totalPnl    总盈亏金额（totalValue - totalCost）
 * @property totalPnlPct 总盈亏百分比
 * @property stockCodes  持仓股票代码列表（逗号分隔）
 */
@androidx.room.Entity(
    tableName = "period_holding_profit",
    indices = [androidx.room.Index(value = ["period_type", "trade_date"], unique = true)]
)
data class PeriodHoldingProfitEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "period_type") val periodType: String,
    @androidx.room.ColumnInfo(name = "trade_date") val tradeDate: String,
    @androidx.room.ColumnInfo(name = "holding_count") val holdingCount: Int,
    @androidx.room.ColumnInfo(name = "total_cost") val totalCost: Double,
    @androidx.room.ColumnInfo(name = "total_value") val totalValue: Double,
    @androidx.room.ColumnInfo(name = "total_pnl") val totalPnl: Double,
    @androidx.room.ColumnInfo(name = "total_pnl_pct") val totalPnlPct: Double,
    @androidx.room.ColumnInfo(name = "stock_codes") val stockCodes: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@androidx.room.Dao
interface PeriodHoldingProfitDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PeriodHoldingProfitEntity): Long

    /** 查询指定周期的最新收益记录 */
    @androidx.room.Query("SELECT * FROM period_holding_profit WHERE period_type = :periodType ORDER BY trade_date DESC LIMIT :limit")
    suspend fun getByPeriodType(periodType: String, limit: Int = 30): List<PeriodHoldingProfitEntity>

    /** 查询指定交易日所有周期的收益 */
    @androidx.room.Query("SELECT * FROM period_holding_profit WHERE trade_date = :date ORDER BY period_type")
    suspend fun getByDate(date: String): List<PeriodHoldingProfitEntity>

    /** 查询指定周期指定日期的收益 */
    @androidx.room.Query("SELECT * FROM period_holding_profit WHERE period_type = :periodType AND trade_date = :date LIMIT 1")
    suspend fun getByPeriodAndDate(periodType: String, date: String): PeriodHoldingProfitEntity?

    /** 查询所有周期的最新记录 */
    @androidx.room.Query("SELECT * FROM period_holding_profit WHERE id IN (SELECT MAX(id) FROM period_holding_profit GROUP BY period_type) ORDER BY period_type")
    suspend fun getLatestAllPeriods(): List<PeriodHoldingProfitEntity>

    /** 查询可用日期 */
    @androidx.room.Query("SELECT DISTINCT trade_date FROM period_holding_profit ORDER BY trade_date DESC LIMIT :limit")
    suspend fun getAvailableDates(limit: Int = 30): List<String>

    /** 删除指定周期的历史记录 */
    @androidx.room.Query("DELETE FROM period_holding_profit WHERE period_type = :periodType")
    suspend fun deleteByPeriodType(periodType: String)

    /** 删除指定日期的记录 */
    @androidx.room.Query("DELETE FROM period_holding_profit WHERE trade_date = :date")
    suspend fun deleteByDate(date: String)
}
