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
    val mainNetInflow: Double = 0.0,  // 主力净流入(万元)，默认0

    // ── 基本面字段（v12 新增，同步时由 FundamentalsProvider 批量填充，0 = 无数据）──
    @ColumnInfo(name = "pe")
    val pe: Double = 0.0,                       // 市盈率(动态)，负值=亏损
    @ColumnInfo(name = "pb")
    val pb: Double = 0.0,                       // 市净率
    @ColumnInfo(name = "market_cap")
    val marketCap: Double = 0.0,                // 总市值(元)
    @ColumnInfo(name = "roe_ttm")
    val roeTTM: Double = 0.0,                   // ROE加权(最新报告期)%
    @ColumnInfo(name = "gross_margin_ttm")
    val grossMarginTTM: Double = 0.0,           // 销售毛利率%
    @ColumnInfo(name = "debt_to_asset")
    val debtToAsset: Double = 0.0,              // 资产负债率%
    @ColumnInfo(name = "operating_cash_flow")
    val operatingCashFlow: Double = 0.0         // 经营现金流净额(元)
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

    /** 获取某日某只股票的快照 */
    @Query("SELECT * FROM daily_snapshot WHERE date = :date AND code = :code LIMIT 1")
    suspend fun getByDateAndCode(date: String, code: String): DailySnapshotEntity?

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

    /** Pipeline 回溯用：获取最近的交易日列表 */
    @Query("SELECT DISTINCT date FROM daily_snapshot ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentTradeDates(limit: Int = 30): List<String>

    /** Pipeline 回溯用：获取某日所有股票代码 */
    @Query("SELECT DISTINCT code FROM daily_snapshot WHERE date = :date")
    suspend fun getStockCodesByDate(date: String): List<String>

    /** Pipeline 回溯用：获取某日之前（含）的数据 */
    @Query("SELECT * FROM daily_snapshot WHERE code = :code AND date <= :beforeDate ORDER BY date DESC LIMIT :limit")
    suspend fun getByCodeBefore(code: String, beforeDate: String, limit: Int = 100): List<DailySnapshotEntity>

    /** Pipeline 回溯用：获取某日之后的数据 */
    @Query("SELECT * FROM daily_snapshot WHERE code = :code AND date > :afterDate ORDER BY date ASC LIMIT :limit")
    suspend fun getByCodeAfter(code: String, afterDate: String, limit: Int = 10): List<DailySnapshotEntity>

    /** 删除超过N天的旧数据 */
    @Query("DELETE FROM daily_snapshot WHERE date < :beforeDate")
    suspend fun deleteOlderThan(beforeDate: String): Int

    /** 批量更新股票名称（覆盖空名和错名） */
    @Query("UPDATE daily_snapshot SET name = :name WHERE code = :code")
    suspend fun updateName(code: String, name: String)

    /**
     * 更新基本面字段（同步时 FundamentalsProvider 批量回写）
     * turnover_rate 用 CASE 保护：新值为 0 时保留原值
     */
    @Query("""UPDATE daily_snapshot SET pe = :pe, pb = :pb, market_cap = :marketCap,
        roe_ttm = :roeTTM, gross_margin_ttm = :grossMarginTTM,
        debt_to_asset = :debtToAsset, operating_cash_flow = :operatingCashFlow,
        turnover_rate = CASE WHEN :turnoverRate > 0 THEN :turnoverRate ELSE turnover_rate END
        WHERE code = :code AND date = :date""")
    suspend fun updateFundamentals(
        code: String, date: String,
        pe: Double, pb: Double, marketCap: Double,
        roeTTM: Double, grossMarginTTM: Double, debtToAsset: Double,
        operatingCashFlow: Double, turnoverRate: Double
    ): Int

    /**
     * 批量回填历史基本面：将某只股票在指定日期范围内的基本面字段统一更新。
     * 仅覆盖 roe_ttm = 0 的行（避免覆盖已有数据）。
     */
    @Query("""UPDATE daily_snapshot SET
        roe_ttm = :roeTTM, gross_margin_ttm = :grossMarginTTM,
        debt_to_asset = :debtToAsset, operating_cash_flow = :operatingCashFlow
        WHERE code = :code AND date >= :fromDate AND date <= :toDate
        AND roe_ttm = 0 AND gross_margin_ttm = 0""")
    suspend fun updateFundamentalsForDateRange(
        code: String, fromDate: String, toDate: String,
        roeTTM: Double, grossMarginTTM: Double,
        debtToAsset: Double, operatingCashFlow: Double
    ): Int

    /** 增量拉取：每只股票已有数据的最大日期（空表返回空列表） */
    @Query("SELECT code, MAX(date) AS maxDate FROM daily_snapshot GROUP BY code")
    suspend fun getMaxDateByCode(): List<CodeMaxDate>

    /** 增量拉取：单只股票已有数据的最大日期 */
    @Query("SELECT MAX(date) FROM daily_snapshot WHERE code = :code")
    suspend fun getMaxDate(code: String): String?

    /** 盘中/选股前刷新：仅更新行情字段（保留基本面字段），返回受影响行数 */
    @Query("""UPDATE daily_snapshot SET open = :open, close = :close, high = :high, low = :low,
        volume = :volume, amount = :amount, change_pct = :changePct,
        turnover_rate = CASE WHEN :turnoverRate > 0 THEN :turnoverRate ELSE turnover_rate END,
        pe = :pe, pb = :pb, market_cap = :marketCap
        WHERE code = :code AND date = :date""")
    suspend fun updateQuote(
        code: String, date: String,
        open: Double, close: Double, high: Double, low: Double,
        volume: Long, amount: Double, changePct: Double,
        turnoverRate: Double, pe: Double, pb: Double, marketCap: Double
    ): Int
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

/** 每只股票已同步的最大日期（增量拉取判断缺失区间用） */
data class CodeMaxDate(
    val code: String,
    val maxDate: String
)

data class StrategyAccuracyStat(
    val strategy_id: String,
    val total: Int,
    val correct_count: Int
) {
    val accuracy: Float get() = if (total > 0) correct_count.toFloat() / total else 0f
}