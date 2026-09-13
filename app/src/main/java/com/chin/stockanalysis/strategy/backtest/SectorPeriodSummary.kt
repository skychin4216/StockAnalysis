package com.chin.stockanalysis.strategy.backtest

import androidx.room.*

/**
 * 周期板块摘要：记录每周/每月的主要板块表现
 *
 * 数据来源：从 sector_daily_record 聚合计算
 * 用途：替代硬编码板块列表，供 AI 分析和量化选股动态使用
 */
@Entity(
    tableName = "sector_period_summary",
    indices = [
        Index(value = ["period_type", "end_date"], unique = true),
        Index(value = ["period_type", "rank"])
    ]
)
data class SectorPeriodSummaryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "period_type") val periodType: String,   // "weekly" | "monthly"
    @ColumnInfo(name = "start_date") val startDate: String,     // yyyy-MM-dd
    @ColumnInfo(name = "end_date") val endDate: String,         // yyyy-MM-dd
    @ColumnInfo(name = "sector_code") val sectorCode: String,   // e.g. "BK0478"
    @ColumnInfo(name = "sector_name") val sectorName: String,   // e.g. "半导体"
    @ColumnInfo(name = "avg_change_pct") val avgChangePct: Double,
    @ColumnInfo(name = "total_inflow") val totalInflow: Double,
    @ColumnInfo(name = "avg_hot_score") val avgHotScore: Double,
    @ColumnInfo(name = "hot_days") val hotDays: Int,            // S/A 级天数
    @ColumnInfo(name = "total_days") val totalDays: Int,        // 交易日数
    @ColumnInfo(name = "rank") val rank: Int                    // 在该周期中的排名
)

@Dao
interface SectorPeriodSummaryDao {

    /** 查询指定周期的 top 板块 */
    @Query("SELECT * FROM sector_period_summary WHERE period_type = :periodType ORDER BY rank ASC LIMIT :limit")
    suspend fun getTopSectors(periodType: String, limit: Int = 10): List<SectorPeriodSummaryEntity>

    /** 查询指定周期的板块名称列表（用于替代硬编码） */
    @Query("SELECT sector_name FROM sector_period_summary WHERE period_type = :periodType ORDER BY rank ASC LIMIT :limit")
    suspend fun getTopSectorNames(periodType: String, limit: Int = 10): List<String>

    /** 查询指定板块的历史表现 */
    @Query("SELECT * FROM sector_period_summary WHERE sector_code = :sectorCode ORDER BY end_date DESC LIMIT :limit")
    suspend fun getBySectorCode(sectorCode: String, limit: Int = 12): List<SectorPeriodSummaryEntity>

    /** 批量插入（覆盖同周期同排名） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<SectorPeriodSummaryEntity>)

    /** 清除指定周期类型的旧数据 */
    @Query("DELETE FROM sector_period_summary WHERE period_type = :periodType AND end_date < :beforeDate")
    suspend fun deleteOlderThan(periodType: String, beforeDate: String): Int

    /** 清除指定周期类型的全部数据 */
    @Query("DELETE FROM sector_period_summary WHERE period_type = :periodType")
    suspend fun deleteByPeriodType(periodType: String): Int

    /** 查询最新一周的结束日期 */
    @Query("SELECT MAX(end_date) FROM sector_period_summary WHERE period_type = 'weekly'")
    suspend fun getLatestWeeklyDate(): String?
}
