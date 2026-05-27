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
        com.chin.stockanalysis.memory.KeyMemoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class StockDatabase : RoomDatabase() {
    abstract fun stockBasicDao(): StockBasicDao
    abstract fun sectorStockDao(): SectorStockDao
    abstract fun conversationDao(): com.chin.stockanalysis.conversation.ConversationDao
    abstract fun keyMemoryDao(): com.chin.stockanalysis.memory.KeyMemoryDao

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

        @Volatile
        private var INSTANCE: StockDatabase? = null

        fun getInstance(context: Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
