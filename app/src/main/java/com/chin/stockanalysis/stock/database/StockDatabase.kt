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
    @Query("DELETE FROM ai_selected_stock WHERE selected_date < :minDate") suspend fun deleteBeforeDate(minDate: String)
    @Query("SELECT * FROM ai_selected_stock WHERE selected_date >= :minDate ORDER BY selected_date DESC, score DESC")
    suspend fun getRecentDays(minDate: String): List<AiSelectedStockEntity>
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
        AiSelectedStockEntity::class,
        com.chin.stockanalysis.strategy.topology.nodes.InstitutionalTipEntity::class,
        com.chin.stockanalysis.strategy.trade.PeriodHoldingProfitEntity::class,
        com.chin.stockanalysis.strategy.trade.TTradeRecordEntity::class
    ],
    version = 15,
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
    abstract fun institutionalTipDao(): com.chin.stockanalysis.strategy.topology.nodes.InstitutionalTipDao
    abstract fun periodHoldingProfitDao(): com.chin.stockanalysis.strategy.trade.PeriodHoldingProfitDao
    abstract fun tTradeRecordDao(): com.chin.stockanalysis.strategy.trade.TTradeRecordDao

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

        /**
         * v12 → v13 遷移：新增 institutional_tips 表（不破壞已有數據）
         */
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `institutional_tips` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `stock_code` TEXT NOT NULL,
                        `stock_name` TEXT NOT NULL DEFAULT '',
                        `sector` TEXT NOT NULL DEFAULT '',
                        `source` TEXT NOT NULL DEFAULT 'ai_chat',
                        `tip_type` TEXT NOT NULL DEFAULT 'research',
                        `summary` TEXT NOT NULL DEFAULT '',
                        `chat_id` TEXT NOT NULL DEFAULT '',
                        `created_date` TEXT NOT NULL DEFAULT '',
                        `expire_date` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_institutional_tips_stock_code` ON `institutional_tips` (`stock_code`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_institutional_tips_expire_date` ON `institutional_tips` (`expire_date`)")
                Log.i(TAG, "✅ v12→v13 遷移完成：已創建 institutional_tips 表（保留已有數據）")
            }
        }

        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `period_holding_profit` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `period_type` TEXT NOT NULL,
                        `trade_date` TEXT NOT NULL,
                        `holding_count` INTEGER NOT NULL,
                        `total_cost` REAL NOT NULL,
                        `total_value` REAL NOT NULL,
                        `total_pnl` REAL NOT NULL,
                        `total_pnl_pct` REAL NOT NULL,
                        `stock_codes` TEXT NOT NULL DEFAULT '',
                        `created_at` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_period_holding_profit_period_type_trade_date` ON `period_holding_profit` (`period_type`, `trade_date`)")
                Log.i(TAG, "✅ v13→v14 遷移完成：已創建 period_holding_profit 表（各週期持有收益獨立固化）")
            }
        }

        /**
         * v14 → v15 遷移：新增 t_trade_records 表（做T/反T 日內交易記錄）
         */
        private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `t_trade_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `stockCode` TEXT NOT NULL,
                        `stockName` TEXT NOT NULL,
                        `tradeDate` TEXT NOT NULL,
                        `tradeType` TEXT NOT NULL,
                        `quantity` INTEGER NOT NULL,
                        `price` REAL NOT NULL,
                        `pairedPrice` REAL NOT NULL,
                        `profit` REAL NOT NULL,
                        `profitPct` REAL NOT NULL,
                        `status` TEXT NOT NULL,
                        `periodType` TEXT NOT NULL,
                        `basePositionQty` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                Log.i(TAG, "✅ v14→v15 遷移完成：已創建 t_trade_records 表（做T/反T日內交易記錄）")
            }
        }

        fun getInstance(context: Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
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