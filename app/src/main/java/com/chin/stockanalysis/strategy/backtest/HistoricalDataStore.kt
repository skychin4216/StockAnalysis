package com.chin.stockanalysis.strategy.backtest

import androidx.room.*

/**
 * ## 每日行情快照实体
 * 存储每只股票每天的OHLCV数据用于回测
 */
@Entity(
    tableName = "daily_snapshot",
    indices = [Index(value = ["date"]), Index(value = ["code", "date"], unique = true)]
)
data class DailySnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "code")
    val code: String,           // sh600519

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "date")
    val date: String,           // YYYY-MM-DD

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
    val amount: Double,

    @ColumnInfo(name = "change_pct")
    val changePct: Double,

    @ColumnInfo(name = "turnover_rate")
    val turnoverRate: Double = 0.0,

    @ColumnInfo(name = "main_net_inflow")
    val mainNetInflow: Double = 0.0   // 主力净流入(万元)，默认0
)

/**
 * ## 策略预测记录实体
 * 记录每个策略每天对每只股票的预测及其实际结果
 */
@Entity(
    tableName = "strategy_prediction",
    indices = [Index(value = ["strategy_id", "date"]), Index(value = ["stock_code", "date"])]
)
data class StrategyPredictionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "strategy_id")
    val strategyId: String,

    @ColumnInfo(name = "strategy_name")
    val strategyName: String,

    @ColumnInfo(name = "date")
    val date: String,               // 预测日期 YYYY-MM-DD

    @ColumnInfo(name = "stock_code")
    val stockCode: String,

    @ColumnInfo(name = "stock_name")
    val stockName: String,

    @ColumnInfo(name = "predicted_score")
    val predictedScore: Int,        // 预测强度 0-100

    @ColumnInfo(name = "predicted_action")
    val predictedAction: String,    // BUY/WATCH/HOLD/SELL

    @ColumnInfo(name = "actual_next_day_pct")
    val actualNextDayPct: Double? = null,   // 次日实际涨跌幅

    @ColumnInfo(name = "actual_5day_pct")
    val actual5DayPct: Double? = null,      // 5日后涨跌幅

    @ColumnInfo(name = "actual_10day_pct")
    val actual10DayPct: Double? = null,     // 10日后涨跌幅

    @ColumnInfo(name = "was_correct")
    val wasCorrect: Boolean? = null,        // 预测是否正确

    @ColumnInfo(name = "deviation")
    val deviation: Double? = null           // 偏差值
)

/**
 * ## 策略权重快照实体
 * 记录策略在某日的权重配置，用于修正回测
 */
@Entity(
    tableName = "strategy_weight_snapshot",
    indices = [Index(value = ["strategy_id", "date"], unique = true)]
)
data class StrategyWeightSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "strategy_id")
    val strategyId: String,

    @ColumnInfo(name = "date")
    val date: String,

    @ColumnInfo(name = "weight_json")
    val weightJson: String,          // WeightFactor 列表的JSON

    @ColumnInfo(name = "total_score")
    val totalScore: Int = 0,         // 当日该策略的综合得分

    @ColumnInfo(name = "hit_count")
    val hitCount: Int = 0            // 当日命中数量
)

// ══════════════════════════════════════
// DAO
// ══════════════════════════════════════

@Dao
interface DailySnapshotDao {
    /** 获取某日所有股票快照 */
    @Query("SELECT * FROM daily_snapshot WHERE date = :date")
    suspend fun getByDate(date: String): List<DailySnapshotEntity>

    /** 获取某只股票的历史数据 */
    @Query("SELECT * FROM daily_snapshot WHERE code = :code ORDER BY date DESC LIMIT :limit")
    suspend fun getByCode(code: String, limit: Int = 100): List<DailySnapshotEntity>

    /** 获取最近N个交易日的所有数据 */
    @Query("SELECT * FROM daily_snapshot WHERE date IN (SELECT DISTINCT date FROM daily_snapshot ORDER BY date DESC LIMIT :days)")
    suspend fun getRecentDays(days: Int = 100): List<DailySnapshotEntity>

    /** 获取所有有数据的日期列表 */
    @Query("SELECT DISTINCT date FROM daily_snapshot ORDER BY date DESC LIMIT :limit")
    suspend fun getAvailableDates(limit: Int = 100): List<String>

    /** 数据量统计 */
    @Query("SELECT COUNT(*) FROM daily_snapshot")
    suspend fun count(): Int

    /** 按日期统计 */
    @Query("SELECT date, COUNT(*) as cnt FROM daily_snapshot GROUP BY date ORDER BY date DESC LIMIT :limit")
    suspend fun countByDate(limit: Int = 30): List<DateCount>

    /** 批量插入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(snapshots: List<DailySnapshotEntity>)

    /** 删除超过N天的旧数据 */
    @Query("DELETE FROM daily_snapshot WHERE date < :beforeDate")
    suspend fun deleteOlderThan(beforeDate: String): Int
}

@Dao
interface StrategyPredictionDao {
    /** 插入预测记录 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(predictions: List<StrategyPredictionEntity>)

    /** 获取某策略的所有预测 */
    @Query("SELECT * FROM strategy_prediction WHERE strategy_id = :strategyId ORDER BY date DESC LIMIT :limit")
    suspend fun getByStrategy(strategyId: String, limit: Int = 100): List<StrategyPredictionEntity>

    /** 获取某日所有策略的预测 */
    @Query("SELECT * FROM strategy_prediction WHERE date = :date")
    suspend fun getByDate(date: String): List<StrategyPredictionEntity>

    /** 更新预测的实际结果 */
    @Query("UPDATE strategy_prediction SET actual_next_day_pct = :pct, was_correct = :correct, deviation = :dev WHERE id = :id")
    suspend fun updateResult(id: Long, pct: Double?, correct: Boolean?, dev: Double?)

    /** 批量更新5日/10日结果 */
    @Query("UPDATE strategy_prediction SET actual_5day_pct = :pct WHERE id = :id")
    suspend fun update5DayResult(id: Long, pct: Double?)

    @Query("UPDATE strategy_prediction SET actual_10day_pct = :pct WHERE id = :id")
    suspend fun update10DayResult(id: Long, pct: Double?)

    /** 按策略统计准确率 */
    @Query("SELECT strategy_id, COUNT(*) as total, SUM(CASE WHEN was_correct = 1 THEN 1 ELSE 0 END) as correct_count FROM strategy_prediction WHERE was_correct IS NOT NULL GROUP BY strategy_id")
    suspend fun getAccuracyStats(): List<StrategyAccuracyStat>

    /** 获取预测偏差最大的记录 */
    @Query("SELECT * FROM strategy_prediction WHERE deviation IS NOT NULL ORDER BY ABS(deviation) DESC LIMIT :limit")
    suspend fun getTopDeviations(limit: Int = 20): List<StrategyPredictionEntity>

    /** 删除某日期前的旧预测 */
    @Query("DELETE FROM strategy_prediction WHERE date < :beforeDate")
    suspend fun deleteOlderThan(beforeDate: String): Int
}

@Dao
interface StrategyWeightSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: StrategyWeightSnapshotEntity)

    @Query("SELECT * FROM strategy_weight_snapshot WHERE strategy_id = :strategyId AND date = :date")
    suspend fun getByStrategyAndDate(strategyId: String, date: String): StrategyWeightSnapshotEntity?

    @Query("SELECT * FROM strategy_weight_snapshot WHERE strategy_id = :strategyId ORDER BY date DESC LIMIT :limit")
    suspend fun getByStrategy(strategyId: String, limit: Int = 100): List<StrategyWeightSnapshotEntity>
}

// ══════════════════════════════════════
// 辅助数据类
// ══════════════════════════════════════

data class DateCount(
    val date: String,
    val cnt: Int
)

data class StrategyAccuracyStat(
    val strategy_id: String,
    val total: Int,
    val correct_count: Int
) {
    val accuracy: Float get() = if (total > 0) correct_count.toFloat() / total else 0f
}