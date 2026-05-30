package com.chin.stockanalysis.strategy.backtest

import androidx.room.*

@Entity(
    tableName = "sector_daily_record",
    indices = [Index(value = ["date"]), Index(value = ["sector_code", "date"], unique = true)]
)
data class SectorDailyRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "sector_code") val sectorCode: String,
    @ColumnInfo(name = "sector_name") val sectorName: String,
    @ColumnInfo(name = "change_pct") val changePct: Double,
    @ColumnInfo(name = "main_net_inflow") val mainNetInflow: Double,
    @ColumnInfo(name = "hot_score") val hotScore: Double,
    @ColumnInfo(name = "composite_score") val compositeScore: Double,
    @ColumnInfo(name = "rank") val rank: Int,
    @ColumnInfo(name = "is_hot") val isHot: String,
    @ColumnInfo(name = "consecutive_hot_days") val consecutiveHotDays: Int = 0
)

@Dao
interface SectorDailyRecordDao {
    @Query("SELECT * FROM sector_daily_record WHERE date = :date ORDER BY rank ASC")
    suspend fun getByDate(date: String): List<SectorDailyRecordEntity>

    @Query("SELECT * FROM sector_daily_record WHERE sector_code = :code ORDER BY date DESC LIMIT :limit")
    suspend fun getBySectorCode(code: String, limit: Int = 30): List<SectorDailyRecordEntity>

    @Query("SELECT * FROM sector_daily_record WHERE date IN (SELECT DISTINCT date FROM sector_daily_record ORDER BY date DESC LIMIT :days)")
    suspend fun getRecentDays(days: Int = 60): List<SectorDailyRecordEntity>

    @Query("SELECT DISTINCT date FROM sector_daily_record ORDER BY date DESC LIMIT :limit")
    suspend fun getAvailableDates(limit: Int = 60): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<SectorDailyRecordEntity>)

    @Query("DELETE FROM sector_daily_record WHERE date < :beforeDate")
    suspend fun deleteOlderThan(beforeDate: String): Int

    @Query("SELECT sector_code, COUNT(*) as cnt, SUM(CASE WHEN is_hot IN ('S','A') THEN 1 ELSE 0 END) as hot_cnt FROM sector_daily_record GROUP BY sector_code ORDER BY hot_cnt DESC LIMIT :limit")
    suspend fun getTopHotSectors(limit: Int = 10): List<HotSectorStat>
}

data class HotSectorStat(
    val sector_code: String,
    val cnt: Int,
    val hot_cnt: Int
)