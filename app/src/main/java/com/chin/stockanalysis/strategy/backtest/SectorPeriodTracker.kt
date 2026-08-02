package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * 板塊週期追蹤器：從 sector_daily_record 聚合計算每週/每月的主要板塊
 *
 * 用途：替代硬編碼板塊列表，讓 AI 分析和量化選股能根據近期大盤交易情況動態更新
 */
class SectorPeriodTracker(private val context: Context) {

    companion object {
        private const val TAG = "SectorPeriodTracker"
        private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE

        /** 每週保留數量上限 */
        private const val MAX_WEEKLY_ENTRIES = 20
        /** 每月保留數量上限 */
        private const val MAX_MONTHLY_ENTRIES = 20
        /** 週數據保留週數 */
        private const val WEEKLY_RETENTION_WEEKS = 12
        /** 月數據保留月數 */
        private const val MONTHLY_RETENTION_MONTHS = 6
    }

    /**
     * 更新週期板塊摘要（每日調用一次即可）
     * - 計算本週 top 板塊
     * - 如果是月末，還計算上月 top 板塊
     */
    suspend fun update() {
        val db = StockDatabase.getInstance(context)
        val dao = db.sectorPeriodSummaryDao()
        val dailyDao = db.sectorDailyRecordDao()

        try {
            // 1. 更新每週摘要
            updateWeekly(dailyDao, dao)

            // 2. 更新每月摘要
            updateMonthly(dailyDao, dao)

            // 3. 清理過期數據
            val weeklyCutoff = LocalDate.now().minusWeeks(WEEKLY_RETENTION_WEEKS.toLong()).format(DATE_FMT)
            val monthlyCutoff = LocalDate.now().minusMonths(MONTHLY_RETENTION_MONTHS.toLong()).format(DATE_FMT)
            dao.deleteOlderThan("weekly", weeklyCutoff)
            dao.deleteOlderThan("monthly", monthlyCutoff)

            Log.i(TAG, "板塊週期摘要更新完成")
        } catch (e: Exception) {
            Log.e(TAG, "板塊週期摘要更新失敗: ${e.message}")
        }
    }

    /**
     * 獲取當前週的 top 板塊名稱（動態替代硬編碼）
     */
    suspend fun getCurrentWeekTopSectors(limit: Int = 10): List<String> {
        val db = StockDatabase.getInstance(context)
        return db.sectorPeriodSummaryDao().getTopSectorNames("weekly", limit)
    }

    /**
     * 獲取當前月的 top 板塊名稱
     */
    suspend fun getCurrentMonthTopSectors(limit: Int = 10): List<String> {
        val db = StockDatabase.getInstance(context)
        return db.sectorPeriodSummaryDao().getTopSectorNames("monthly", limit)
    }

    /**
     * 獲取綜合 hot 板塊（合併週+月數據，按權重排序）
     */
    suspend fun getHotSectors(limit: Int = 15): List<SectorPeriodSummaryEntity> {
        val db = StockDatabase.getInstance(context)
        val dao = db.sectorPeriodSummaryDao()
        val weekly = dao.getTopSectors("weekly", limit).map { it to (limit - it.rank + 1) * 0.6 }
        val monthly = dao.getTopSectors("monthly", limit).map { it to (limit - it.rank + 1) * 0.4 }

        // 合併同板塊得分
        val merged = mutableMapOf<String, Pair<SectorPeriodSummaryEntity, Double>>()
        for ((entity, score) in weekly + monthly) {
            val key = entity.sectorCode
            val existing = merged[key]
            if (existing != null) {
                merged[key] = existing.copy(second = existing.second + score)
            } else {
                merged[key] = entity to score
            }
        }

        return merged.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // ── 內部方法 ──

    private suspend fun updateWeekly(
        dailyDao: SectorDailyRecordDao,
        summaryDao: SectorPeriodSummaryDao
    ) {
        val today = LocalDate.now()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(4) // Friday

        // 取本週交易日數據
        val records = dailyDao.getRecentDays(7) // 取最近 7 天（覆蓋 5 個交易日）
        val weekRecords = records.filter { record ->
            val date = try { LocalDate.parse(record.date, DATE_FMT) } catch (_: Exception) { return@filter false }
            !date.isBefore(weekStart) && !date.isAfter(weekEnd)
        }

        if (weekRecords.isEmpty()) {
            Log.w(TAG, "本週無交易日數據，跳過週摘要更新")
            return
        }

        val summaries = aggregateRecords(
            records = weekRecords,
            periodType = "weekly",
            startDate = weekStart.format(DATE_FMT),
            endDate = weekEnd.format(DATE_FMT),
            maxEntries = MAX_WEEKLY_ENTRIES
        )

        summaryDao.deleteByPeriodType("weekly")
        summaryDao.insertAll(summaries)
        Log.i(TAG, "週摘要更新: ${summaries.size} 個板塊 (${weekStart}~${weekEnd})")
    }

    private suspend fun updateMonthly(
        dailyDao: SectorDailyRecordDao,
        summaryDao: SectorPeriodSummaryDao
    ) {
        val today = LocalDate.now()
        val monthStart = today.with(TemporalAdjusters.firstDayOfMonth())
        val monthEnd = today.with(TemporalAdjusters.lastDayOfMonth())

        // 取本月交易日數據（最近 30 天覆蓋）
        val records = dailyDao.getRecentDays(30)
        val monthRecords = records.filter { record ->
            val date = try { LocalDate.parse(record.date, DATE_FMT) } catch (_: Exception) { return@filter false }
            !date.isBefore(monthStart) && !date.isAfter(today)
        }

        if (monthRecords.isEmpty()) {
            Log.w(TAG, "本月無交易日數據，跳過月摘要更新")
            return
        }

        val summaries = aggregateRecords(
            records = monthRecords,
            periodType = "monthly",
            startDate = monthStart.format(DATE_FMT),
            endDate = today.format(DATE_FMT),
            maxEntries = MAX_MONTHLY_ENTRIES
        )

        summaryDao.deleteByPeriodType("monthly")
        summaryDao.insertAll(summaries)
        Log.i(TAG, "月摘要更新: ${summaries.size} 個板塊 (${monthStart}~${today})")
    }

    /**
     * 聚合板塊日記錄為週期摘要
     */
    private fun aggregateRecords(
        records: List<SectorDailyRecordEntity>,
        periodType: String,
        startDate: String,
        endDate: String,
        maxEntries: Int
    ): List<SectorPeriodSummaryEntity> {
        // 按板塊分組
        val grouped = records.groupBy { it.sectorCode }

        return grouped.map { (sectorCode, sectorRecords) ->
            val avgChange = sectorRecords.map { it.changePct }.average()
            val totalInflow = sectorRecords.sumOf { it.mainNetInflow }
            val avgHotScore = sectorRecords.map { it.hotScore }.average()
            val hotDays = sectorRecords.count { it.isHot in listOf("S", "A") }
            val totalDays = sectorRecords.size
            val sectorName = sectorRecords.first().sectorName

            // 綜合得分：漲幅 30% + 資金流入 30% + 熱度 25% + 連續熱天 15%
            val compositeScore = avgChange * 0.30 +
                (totalInflow / 10.0).coerceIn(-5.0, 5.0) * 0.30 +
                avgHotScore * 0.25 +
                hotDays.toDouble() * 0.15

            SectorPeriodSummaryEntity(
                periodType = periodType,
                startDate = startDate,
                endDate = endDate,
                sectorCode = sectorCode,
                sectorName = sectorName,
                avgChangePct = avgChange,
                totalInflow = totalInflow,
                avgHotScore = avgHotScore,
                hotDays = hotDays,
                totalDays = totalDays,
                rank = 0 // 稍後賦值
            ) to compositeScore
        }
            .sortedByDescending { it.second }
            .take(maxEntries)
            .mapIndexed { index, (entity, _) -> entity.copy(rank = index + 1) }
    }
}
