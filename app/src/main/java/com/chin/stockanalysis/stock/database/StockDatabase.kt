package com.chin.stockanalysis.stock.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * ## 股票基本信息实体
 *
 * 存储 A 股主板核心标的的基本信息（代码、名称、业务、产业链依据）
 */
@Entity(tableName = "stock_basics")
data class StockBasicEntity(
    @PrimaryKey
    @ColumnInfo(name = "code")
    val code: String,                   // sh600519 / sz000858 格式

    @ColumnInfo(name = "name")
    val name: String,                   // 贵州茅台

    @ColumnInfo(name = "business")
    val business: String,               // 核心业务描述

    @ColumnInfo(name = "chain_rationale")
    val chainRationale: String = ""     // 产业链核心依据
)

/**
 * ## 板块与股票映射关系实体
 *
 * 关联板块（如"商业航天"）与其包含的股票代码
 */
@Entity(
    tableName = "sector_stocks",
    indices = [Index(value = ["sector_key", "stock_code"], unique = true)]
)
data class SectorStockEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "sector_key")
    val sectorKey: String,              // commercial_space / ai_tech 等

    @ColumnInfo(name = "sector_name")
    val sectorName: String,             // 板块中文名：商业航天

    @ColumnInfo(name = "stock_code")
    val stockCode: String               // 关联的股票代码
)

// ═══════════════════════════════════════════════════════
// DAO
// ═══════════════════════════════════════════════════════

@Dao
interface StockBasicDao {
    /** 按代码查询 */
    @Query("SELECT * FROM stock_basics WHERE code = :code")
    suspend fun getByCode(code: String): StockBasicEntity?

    /** 按代码列表批量查询 */
    @Query("SELECT * FROM stock_basics WHERE code IN (:codes)")
    suspend fun getByCodes(codes: List<String>): List<StockBasicEntity>

    /** 按名称模糊搜索 */
    @Query("SELECT * FROM stock_basics WHERE name LIKE '%' || :keyword || '%'")
    suspend fun searchByName(keyword: String): List<StockBasicEntity>

    /** 获取全部股票信息 */
    @Query("SELECT * FROM stock_basics")
    suspend fun getAll(): List<StockBasicEntity>

    /** 获取股票总数 */
    @Query("SELECT COUNT(*) FROM stock_basics")
    suspend fun count(): Int

    /** 插入单条 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stock: StockBasicEntity)

    /** 批量插入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stocks: List<StockBasicEntity>)
}

@Dao
interface SectorStockDao {
    /** 按板块 key 获取全部关联股票代码 */
    @Query("SELECT stock_code FROM sector_stocks WHERE sector_key = :sectorKey")
    suspend fun getStockCodesBySector(sectorKey: String): List<String>

    /** 按板块 key 获取板块名称 */
    @Query("SELECT DISTINCT sector_name FROM sector_stocks WHERE sector_key = :sectorKey LIMIT 1")
    suspend fun getSectorName(sectorKey: String): String?

    /** 获取所有板块 key */
    @Query("SELECT DISTINCT sector_key FROM sector_stocks")
    suspend fun getAllSectorKeys(): List<String>

    /** 获取所有板块 key Flow */
    @Query("SELECT DISTINCT sector_key FROM sector_stocks")
    fun getAllSectorKeysFlow(): Flow<List<String>>

    /** 查询板块记录数 */
    @Query("SELECT COUNT(*) FROM sector_stocks WHERE sector_key = :sectorKey")
    suspend fun countBySector(sectorKey: String): Int

    /** 批量插入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SectorStockEntity>)

    /** 清空板块映射表 */
    @Query("DELETE FROM sector_stocks")
    suspend fun clearAll()

    /** 获取所有股票代码（用于删除已退市股票） */
    @Query("SELECT stock_code FROM sector_stocks")
    suspend fun getAllStockCodes(): List<String>
}

// ═══════════════════════════════════════════════════════
// Room Database
// ═══════════════════════════════════════════════════════

@Database(
    entities = [
        StockBasicEntity::class,
        SectorStockEntity::class,
        com.chin.stockanalysis.conversation.ConversationEntity::class,
        com.chin.stockanalysis.memory.KeyMemoryEntity::class,
        com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity::class,
        com.chin.stockanalysis.strategy.backtest.StrategyPredictionEntity::class,
        com.chin.stockanalysis.strategy.backtest.StrategyWeightSnapshotEntity::class,
        com.chin.stockanalysis.strategy.backtest.SectorDailyRecordEntity::class,
        com.chin.stockanalysis.news.NewsFactorEntity::class
    ],
    version = 6,
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

    companion object {
        const val DATABASE_NAME = "stock_analysis.db"

        /** v1 → v2 迁移：新增 conversations 表 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        subtitle TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        messages_json TEXT NOT NULL DEFAULT '[]'
                    )
                """.trimIndent())
            }
        }

        /** v2 → v3 迁移：新增 key_memories 表 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS key_memories (
                        id TEXT PRIMARY KEY NOT NULL,
                        category TEXT NOT NULL,
                        `key` TEXT NOT NULL,
                        value TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 0.3,
                        source_conv_ids TEXT NOT NULL DEFAULT '[]',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /** v3 → v4 迁移：新增回测相关表（daily_snapshot, strategy_prediction, strategy_weight_snapshot） */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS daily_snapshot (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        code TEXT NOT NULL,
                        name TEXT NOT NULL,
                        date TEXT NOT NULL,
                        open REAL NOT NULL,
                        close REAL NOT NULL,
                        high REAL NOT NULL,
                        low REAL NOT NULL,
                        volume INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        change_pct REAL NOT NULL,
                        turnover_rate REAL NOT NULL,
                        main_net_inflow REAL NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_daily_snapshot_date ON daily_snapshot(date)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_daily_snapshot_code_date ON daily_snapshot(code, date)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS strategy_prediction (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        strategy_id TEXT NOT NULL,
                        strategy_name TEXT NOT NULL,
                        date TEXT NOT NULL,
                        stock_code TEXT NOT NULL,
                        stock_name TEXT NOT NULL,
                        predicted_score INTEGER NOT NULL,
                        predicted_action TEXT NOT NULL,
                        actual_next_day_pct REAL,
                        actual_5day_pct REAL,
                        actual_10day_pct REAL,
                        was_correct INTEGER,
                        deviation REAL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_strategy_prediction_strategy_id_date ON strategy_prediction(strategy_id, date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_strategy_prediction_stock_code_date ON strategy_prediction(stock_code, date)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS strategy_weight_snapshot (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        strategy_id TEXT NOT NULL,
                        date TEXT NOT NULL,
                        weight_json TEXT NOT NULL,
                        total_score INTEGER NOT NULL DEFAULT 0,
                        hit_count INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_strategy_weight_snapshot_strategy_id_date ON strategy_weight_snapshot(strategy_id, date)")
            }
        }

        /** v4 → v5 迁移：新增板块每日记录表 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sector_daily_record (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        sector_code TEXT NOT NULL,
                        sector_name TEXT NOT NULL,
                        change_pct REAL NOT NULL,
                        main_net_inflow REAL NOT NULL,
                        hot_score REAL NOT NULL,
                        composite_score REAL NOT NULL,
                        rank INTEGER NOT NULL,
                        is_hot TEXT NOT NULL,
                        consecutive_hot_days INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sector_daily_record_date ON sector_daily_record(date)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sector_daily_record_code_date ON sector_daily_record(sector_code, date)")
            }
        }

        /** v5 → v6 迁移：新增 news_factors 表（新闻利好利空因子库） */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS news_factors (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        stock_code TEXT NOT NULL DEFAULT '',
                        company_name TEXT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        news_date TEXT NOT NULL,
                        sentiment INTEGER NOT NULL DEFAULT 0,
                        impact_strength INTEGER NOT NULL DEFAULT 50,
                        source TEXT NOT NULL DEFAULT 'ai_extract',
                        source_url TEXT NOT NULL DEFAULT '',
                        tags TEXT NOT NULL DEFAULT '',
                        sector TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        is_active INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_news_factors_stock_code ON news_factors(stock_code)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_news_factors_news_date ON news_factors(news_date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_news_factors_sentiment ON news_factors(sentiment)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_news_factors_created_at ON news_factors(created_at)")
            }
        }

        @Volatile
        private var INSTANCE: StockDatabase? = null

        fun getInstance(context: Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
