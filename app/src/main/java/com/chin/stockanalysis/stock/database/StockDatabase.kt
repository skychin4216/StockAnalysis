package com.chin.stockanalysis.stock.database

import androidx.room.*
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
        SectorStockEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class StockDatabase : RoomDatabase() {
    abstract fun stockBasicDao(): StockBasicDao
    abstract fun sectorStockDao(): SectorStockDao

    companion object {
        const val DATABASE_NAME = "stock_analysis.db"
    }
}