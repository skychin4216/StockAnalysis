package com.chin.stockanalysis.strategy.backtest

import androidx.room.*

/**
 * 週期板塊摘要：記錄每週/每月的主要板塊表現
 *
 * 數據來源：從 sector_daily_record 聚合計算
 * 用途：替代硬編碼板塊列表，供 AI 分析和量化選股動態使用
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
    @ColumnInfo(name = "sector_name") val sectorName: String,   // e.g. "半導體"
    @ColumnInfo(name = "avg_change_pct") val avgChangePct: Double,
    @ColumnInfo(name = "total_inflow") val totalInflow: Double,
    @ColumnInfo(name = "avg_hot_score") val avgHotScore: Double,
    @ColumnInfo(name = "hot_days") val hotDays: Int,            // S/A 級天數
    @ColumnInfo(name = "total_days") val totalDays: Int,        // 交易日數
    @ColumnInfo(name = "rank") val rank: Int                    // 在該週期中的排名
)

@Dao
interface SectorPeriodSummaryDao {

    /** 查詢指定週期的 top 板塊 */
    @Query("SELECT * FROM sector_period_summary WHERE period_type = :periodType ORDER BY rank ASC LIMIT :limit")
    suspend fun getTopSectors(periodType: String, limit: Int = 10): List<SectorPeriodSummaryEntity>

    /** 查詢指定週期的板塊名稱列表（用於替代硬編碼） */
    @Query("SELECT sector_name FROM sector_period_summary WHERE period_type = :periodType ORDER BY rank ASC LIMIT :limit")
    suspend fun getTopSectorNames(periodType: String, limit: Int = 10): List<String>

    /** 查詢指定板塊的歷史表現 */
    @Query("SELECT * FROM sector_period_summary WHERE sector_code = :sectorCode ORDER BY end_date DESC LIMIT :limit")
    suspend fun getBySectorCode(sectorCode: String, limit: Int = 12): List<SectorPeriodSummaryEntity>

    /** 批量插入（覆蓋同週期同排名） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<SectorPeriodSummaryEntity>)

    /** 清除指定週期類型的舊數據 */
    @Query("DELETE FROM sector_period_summary WHERE period_type = :periodType AND end_date < :beforeDate")
    suspend fun deleteOlderThan(periodType: String, beforeDate: String): Int

    /** 清除指定週期類型的全部數據 */
    @Query("DELETE FROM sector_period_summary WHERE period_type = :periodType")
    suspend fun deleteByPeriodType(periodType: String): Int

    /** 查詢最新一週的結束日期 */
    @Query("SELECT MAX(end_date) FROM sector_period_summary WHERE period_type = 'weekly'")
    suspend fun getLatestWeeklyDate(): String?
}
