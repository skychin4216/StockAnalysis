package com.chin.stockanalysis.news

import androidx.room.*

/**
 * ## 新闻利好利空因子实体
 *
 * 存储从AI提取的公司/股票/行业相关利好利空新闻信息，
 * 用于策略分析因子。超过3个月的数据不参与策略分析，
 * 超过1年的数据自动清理。
 */
@Entity(
    tableName = "news_factors",
    indices = [
        Index(value = ["stock_code"]),
        Index(value = ["news_date"]),
        Index(value = ["sentiment"]),
        Index(value = ["created_at"])
    ]
)
data class NewsFactorEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 股票代码（可选，如 sh600519，若无则空字符串） */
    @ColumnInfo(name = "stock_code")
    val stockCode: String = "",

    /** 股票/公司名称 */
    @ColumnInfo(name = "company_name")
    val companyName: String,

    /** 新闻标题/摘要 */
    @ColumnInfo(name = "title")
    val title: String,

    /** 新闻详细内容 */
    @ColumnInfo(name = "content")
    val content: String,

    /** 新闻日期（YYYY-MM-DD），不是录入日期 */
    @ColumnInfo(name = "news_date")
    val newsDate: String,

    /** 情感分：1=利好，-1=利空，0=中性 */
    @ColumnInfo(name = "sentiment")
    val sentiment: Int = 0,

    /** 影响强度 0-100 */
    @ColumnInfo(name = "impact_strength")
    val impactStrength: Int = 50,

    /** 来源: ai_extract/tonghuashun/eastmoney/user_input */
    @ColumnInfo(name = "source")
    val source: String = "ai_extract",

    /** 来源URL（如有） */
    @ColumnInfo(name = "source_url")
    val sourceUrl: String = "",

    /** 关联标签（逗号分隔，如"英伟达,AI芯片,黄仁勋"） */
    @ColumnInfo(name = "tags")
    val tags: String = "",

    /** 行业/板块（如"半导体","AI算力"） */
    @ColumnInfo(name = "sector")
    val sector: String = "",

    /** 录入时间戳 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** 该因子的活跃状态（超过3个月自动标记为false，不参与策略） */
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true
)

// ══════════════════════════════════════
// DAO
// ══════════════════════════════════════

@Dao
interface NewsFactorDao {
    /** 插入单条 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NewsFactorEntity): Long

    /** 批量插入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<NewsFactorEntity>)

    /** 按股票代码查询活跃的因子（3个月内） */
    @Query("SELECT * FROM news_factors WHERE stock_code = :code AND is_active = 1 ORDER BY news_date DESC LIMIT :limit")
    suspend fun getActiveByStock(code: String, limit: Int = 20): List<NewsFactorEntity>

    /** 按公司名称模糊查询活跃因子 */
    @Query("SELECT * FROM news_factors WHERE company_name LIKE '%' || :keyword || '%' AND is_active = 1 ORDER BY news_date DESC LIMIT :limit")
    suspend fun searchActiveByCompany(keyword: String, limit: Int = 20): List<NewsFactorEntity>

    /** 按标签查询活跃因子 */
    @Query("SELECT * FROM news_factors WHERE tags LIKE '%' || :tag || '%' AND is_active = 1 ORDER BY news_date DESC LIMIT :limit")
    suspend fun searchActiveByTag(tag: String, limit: Int = 20): List<NewsFactorEntity>

    /** 获取最近活跃的利好因子 */
    @Query("SELECT * FROM news_factors WHERE sentiment = 1 AND is_active = 1 ORDER BY news_date DESC LIMIT :limit")
    suspend fun getActiveBullish(limit: Int = 50): List<NewsFactorEntity>

    /** 获取最近活跃的利空因子 */
    @Query("SELECT * FROM news_factors WHERE sentiment = -1 AND is_active = 1 ORDER BY news_date DESC LIMIT :limit")
    suspend fun getActiveBearish(limit: Int = 50): List<NewsFactorEntity>

    /** 按行业获取活跃因子 */
    @Query("SELECT * FROM news_factors WHERE sector = :sector AND is_active = 1 ORDER BY news_date DESC LIMIT :limit")
    suspend fun getActiveBySector(sector: String, limit: Int = 20): List<NewsFactorEntity>

    /** 获取所有活跃因子 */
    @Query("SELECT * FROM news_factors WHERE is_active = 1 ORDER BY news_date DESC LIMIT :limit")
    suspend fun getAllActive(limit: Int = 100): List<NewsFactorEntity>

    /** 获取所有因子（含非活跃） */
    @Query("SELECT * FROM news_factors ORDER BY news_date DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 200): List<NewsFactorEntity>

    /** 总数 */
    @Query("SELECT COUNT(*) FROM news_factors")
    suspend fun count(): Int

    /** 活跃数 */
    @Query("SELECT COUNT(*) FROM news_factors WHERE is_active = 1")
    suspend fun countActive(): Int

    /** 将超过3个月的因子标记为非活跃（不参与策略分析） */
    @Query("UPDATE news_factors SET is_active = 0 WHERE news_date < :cutoffDate AND is_active = 1")
    suspend fun deactivateOlderThan(cutoffDate: String): Int

    /** 删除超过1年的数据 */
    @Query("DELETE FROM news_factors WHERE news_date < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: String): Int

    /** 按ID删除 */
    @Query("DELETE FROM news_factors WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空表 */
    @Query("DELETE FROM news_factors")
    suspend fun deleteAll()

    /** 获取最新一条新闻的日期 */
    @Query("SELECT news_date FROM news_factors ORDER BY news_date DESC LIMIT 1")
    suspend fun getLatestNewsDate(): String?

    /** 按日期范围查询 */
    @Query("SELECT * FROM news_factors WHERE news_date BETWEEN :fromDate AND :toDate AND is_active = 1 ORDER BY news_date DESC")
    suspend fun getActiveByDateRange(fromDate: String, toDate: String): List<NewsFactorEntity>
}