package com.chin.stockanalysis.stock.database

import android.content.Context
import android.util.Log
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * ## 股票基本信息实体
 */
@Entity(tableName = "stock_basics")
data class StockBasicEntity(
    @PrimaryKey @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "business") val business: String,
    @ColumnInfo(name = "chain_rationale") val chainRationale: String = ""
)

@Entity(tableName = "sector_stocks", indices = [Index(value = ["sector_key", "stock_code"], unique = true)])
data class SectorStockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sector_key") val sectorKey: String,
    @ColumnInfo(name = "sector_name") val sectorName: String,
    @ColumnInfo(name = "stock_code") val stockCode: String
)

@Entity(tableName = "weight_calibrations", indices = [Index(value = ["strategy_id", "calibrate_date"], unique = true)])
data class WeightCalibrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "strategy_id") val strategyId: String,
    @ColumnInfo(name = "calibrate_date") val calibrateDate: String,
    @ColumnInfo(name = "predict_date") val predictDate: String,
    @ColumnInfo(name = "hit_count") val hitCount: Int,
    @ColumnInfo(name = "accuracy") val accuracy: Double,
    @ColumnInfo(name = "weight_snapshot") val weightSnapshot: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

// ── DAOs ──────────────────────────────────────────

@Dao
interface StockBasicDao {
    @Query("SELECT * FROM stock_basics WHERE code = :code") suspend fun getByCode(code: String): StockBasicEntity?
    @Query("SELECT * FROM stock_basics WHERE code IN (:codes)") suspend fun getByCodes(codes: List<String>): List<StockBasicEntity>
    @Query("SELECT * FROM stock_basics WHERE name LIKE '%' || :keyword || '%'") suspend fun searchByName(keyword: String): List<StockBasicEntity>
    @Query("SELECT * FROM stock_basics") suspend fun getAll(): List<StockBasicEntity>
    @Query("SELECT COUNT(*) FROM stock_basics") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(stock: StockBasicEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(stocks: List<StockBasicEntity>)
}

@Dao
interface SectorStockDao {
    @Query("SELECT stock_code FROM sector_stocks WHERE sector_key = :sectorKey") suspend fun getStockCodesBySector(sectorKey: String): List<String>
    @Query("SELECT DISTINCT sector_name FROM sector_stocks WHERE sector_key = :sectorKey LIMIT 1") suspend fun getSectorName(sectorKey: String): String?
    @Query("SELECT DISTINCT sector_key FROM sector_stocks") suspend fun getAllSectorKeys(): List<String>
    @Query("SELECT DISTINCT sector_key FROM sector_stocks") fun getAllSectorKeysFlow(): Flow<List<String>>
    @Query("SELECT COUNT(*) FROM sector_stocks WHERE sector_key = :sectorKey") suspend fun countBySector(sectorKey: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(entries: List<SectorStockEntity>)
    @Query("DELETE FROM sector_stocks") suspend fun clearAll()
    @Query("SELECT stock_code FROM sector_stocks") suspend fun getAllStockCodes(): List<String>
    @Query("SELECT DISTINCT sector_name FROM sector_stocks WHERE stock_code = :stockCode LIMIT 3") suspend fun getSectorNamesByStockCode(stockCode: String): List<String>
}

@Dao
interface WeightCalibrationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entity: WeightCalibrationEntity)
    @Query("SELECT * FROM weight_calibrations WHERE strategy_id = :strategyId ORDER BY calibrate_date DESC LIMIT :limit") suspend fun getByStrategy(strategyId: String, limit: Int = 30): List<WeightCalibrationEntity>
    @Query("DELETE FROM weight_calibrations WHERE strategy_id = :strategyId") suspend fun deleteByStrategy(strategyId: String)
}

/** 用戶自選股 DAO */
@Dao
interface UserWatchlistDao {
    @Query("SELECT * FROM user_watchlist WHERE status = :status ORDER BY added_date DESC") suspend fun getByStatus(status: String): List<UserWatchlistEntity>
    @Query("SELECT * FROM user_watchlist ORDER BY added_date DESC") suspend fun getAll(): List<UserWatchlistEntity>
    @Query("SELECT * FROM user_watchlist WHERE stock_code = :code LIMIT 1") suspend fun getByCode(code: String): UserWatchlistEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entity: UserWatchlistEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(entities: List<UserWatchlistEntity>)
    @Update suspend fun update(entity: UserWatchlistEntity)
    @Query("DELETE FROM user_watchlist WHERE stock_code = :code") suspend fun deleteByCode(code: String)
    @Query("DELETE FROM user_watchlist") suspend fun clearAll()
}

/** AI 精選股 DAO */
@Dao
interface AiSelectedStockDao {
    @Query("SELECT * FROM ai_selected_stock WHERE selected_date = :date ORDER BY score DESC") suspend fun getByDate(date: String): List<AiSelectedStockEntity>
    @Query("SELECT * FROM ai_selected_stock ORDER BY selected_date DESC, score DESC") suspend fun getAll(): List<AiSelectedStockEntity>
    @Query("SELECT * FROM ai_selected_stock WHERE selected_date = :date") fun getByDateFlow(date: String): kotlinx.coroutines.flow.Flow<List<AiSelectedStockEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entity: AiSelectedStockEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(entities: List<AiSelectedStockEntity>)
    @Query("DELETE FROM ai_selected_stock WHERE selected_date != :today") suspend fun keepOnlyToday(today: String)
    @Query("DELETE FROM ai_selected_stock") suspend fun clearAll()
    @Query("DELETE FROM ai_selected_stock WHERE selected_date = :date") suspend fun deleteByDate(date: String)
}

// ── Room Database ────────────────────────────────

@Database(
    entities = [
        StockBasicEntity::class, SectorStockEntity::class,
        com.chin.stockanalysis.conversation.ConversationEntity::class,
        com.chin.stockanalysis.memory.KeyMemoryEntity::class,
        com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity::class,
        com.chin.stockanalysis.strategy.backtest.StrategyPredictionEntity::class,
        com.chin.stockanalysis.strategy.backtest.StrategyWeightSnapshotEntity::class,
        com.chin.stockanalysis.strategy.backtest.SectorDailyRecordEntity::class,
        com.chin.stockanalysis.news.NewsFactorEntity::class,
        WeightCalibrationEntity::class,
        com.chin.stockanalysis.strategy.trade.DailyPeriodResultEntity::class,
        com.chin.stockanalysis.strategy.trade.StrategyTradeFittingParamEntity::class,
        com.chin.stockanalysis.strategy.trade.DailyNewsHotPickEntity::class,
        com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity::class,
        UserWatchlistEntity::class,
        AiSelectedStockEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class StockDatabase : RoomDatabase() {
    abstract fun stockBasicDao(): StockBasicDao
    abstract fun sectorStockDao(): SectorStockDao
    abstract fun conversationDao(): com.chin.stockanalysis.conversation.ConversationDao
    abstract fun keyMemoryDao(): com.chin.stockanalysis.memory.KeyMemoryDao
    abstract fun dailySnapshotDao(): com.chin.stockanalysis.strategy.backtest.DailySnapshotDao
    abstract fun strategyPredictionDao(): com.chin.stockanalysis.strategy.backtest.StrategyPredictionDao
    abstract fun strategyWeightSnapshotDao(): com.chin.stockanalysis.strategy.backtest.StrategyWeightSnapshotDao
    abstract fun sectorDailyRecordDao(): com.chin.stockanalysis.strategy.backtest.SectorDailyRecordDao
    abstract fun newsFactorDao(): com.chin.stockanalysis.news.NewsFactorDao
    abstract fun weightCalibrationDao(): WeightCalibrationDao
    abstract fun dailyPeriodResultDao(): com.chin.stockanalysis.strategy.trade.DailyPeriodResultDao
    abstract fun strategyTradeFittingParamDao(): com.chin.stockanalysis.strategy.trade.StrategyTradeFittingParamDao
    abstract fun dailyNewsHotPickDao(): com.chin.stockanalysis.strategy.trade.DailyNewsHotPickDao
    abstract fun strategyTradeOrderDao(): com.chin.stockanalysis.strategy.trade.StrategyTradeOrderDao
    abstract fun userWatchlistDao(): UserWatchlistDao
    abstract fun aiSelectedStockDao(): AiSelectedStockDao

    companion object {
        const val DATABASE_NAME = "stock_analysis.db"
        private const val TAG = "StockDB"

        /**
         * 破坏性迁移回调：数据库版本升级时自动重建
         * 用户可先通过 DataExportImport 导出数据，升级后再导入
         */
        private val destructiveCallback = object : RoomDatabase.Callback() {
            override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                super.onDestructiveMigration(db)
                Log.w(TAG, "⚠️ 数据库结构变更，旧数据已清除。请使用『数据 → 导入』恢复数据。")
            }
        }

        @Volatile private var INSTANCE: StockDatabase? = null

        fun getInstance(context: Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(destructiveCallback)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * 清除 Room 实例缓存（从 SAF 备份恢复后调用，强制重新打开数据库）
         */
        fun clearInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                Log.i(TAG, "🔁 数据库实例已清除")
            }
        }
    }
}