package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * 板块周期追踪器：从 sector_daily_record 聚合计算每周/每月的主要板块
 *
 * 用途：替代硬编码板块列表，让 AI 分析和量化选股能根据近期大盘交易情况动态更新
 */
class SectorPeriodTracker(private val context: Context) {

    companion object {
        private const val TAG = "SectorPeriodTracker"
        private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE

        /** 每周保留数量上限 */
        private const val MAX_WEEKLY_ENTRIES = 20
        /** 每月保留数量上限 */
        private const val MAX_MONTHLY_ENTRIES = 20
        /** 周数据保留周数 */
        private const val WEEKLY_RETENTION_WEEKS = 12
        /** 月数据保留月数 */
        private const val MONTHLY_RETENTION_MONTHS = 6
    }

    /**
     * 更新周期板块摘要（每日调用一次即可）
     * - 计算本周 top 板块
     * - 如果是月末，还计算上月 top 板块
     */
    suspend fun update() {
        val db = StockDatabase.getInstance(context)
        val dao = db.sectorPeriodSummaryDao()
        val dailyDao = db.sectorDailyRecordDao()

        try {
            // 1. 更新每周摘要
            updateWeekly(dailyDao, dao)

            // 2. 更新每月摘要
            updateMonthly(dailyDao, dao)

            // 3. 清理过期数据
            val weeklyCutoff = LocalDate.now().minusWeeks(WEEKLY_RETENTION_WEEKS.toLong()).format(DATE_FMT)
            val monthlyCutoff = LocalDate.now().minusMonths(MONTHLY_RETENTION_MONTHS.toLong()).format(DATE_FMT)
            dao.deleteOlderThan("weekly", weeklyCutoff)
            dao.deleteOlderThan("monthly", monthlyCutoff)

            Log.i(TAG, "板块周期摘要更新完成")
        } catch (e: Exception) {
            Log.e(TAG, "板块周期摘要更新失败: ${e.message}")
        }
    }

    /**
     * 获取当前周的 top 板块名称（动态替代硬编码）
     */
    suspend fun getCurrentWeekTopSectors(limit: Int = 10): List<String> {
        val db = StockDatabase.getInstance(context)
        return db.sectorPeriodSummaryDao().getTopSectorNames("weekly", limit)
    }

    /**
     * 获取当前月的 top 板块名称
     */
    suspend fun getCurrentMonthTopSectors(limit: Int = 10): List<String> {
        val db = StockDatabase.getInstance(context)
        return db.sectorPeriodSummaryDao().getTopSectorNames("monthly", limit)
    }

    /**
     * 获取综合 hot 板块（合并周+月数据，按权重排序）
     */
    suspend fun getHotSectors(limit: Int = 15): List<SectorPeriodSummaryEntity> {
        val db = StockDatabase.getInstance(context)
        val dao = db.sectorPeriodSummaryDao()
        val weekly = dao.getTopSectors("weekly", limit).map { it to (limit - it.rank + 1) * 0.6 }
        val monthly = dao.getTopSectors("monthly", limit).map { it to (limit - it.rank + 1) * 0.4 }

        // 合并同板块得分
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

    // ── 内部方法 ──

    private suspend fun updateWeekly(
        dailyDao: SectorDailyRecordDao,
        summaryDao: SectorPeriodSummaryDao
    ) {
        val today = LocalDate.now()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(4) // Friday

        // 取本周交易日数据
        val records = dailyDao.getRecentDays(7) // 取最近 7 天（覆盖 5 个交易日）
        val weekRecords = records.filter { record ->
            val date = try { LocalDate.parse(record.date, DATE_FMT) } catch (_: Exception) { return@filter false }
            !date.isBefore(weekStart) && !date.isAfter(weekEnd)
        }

        if (weekRecords.isEmpty()) {
            Log.w(TAG, "本周无交易日数据，跳过周摘要更新")
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
        Log.i(TAG, "周摘要更新: ${summaries.size} 个板块 (${weekStart}~${weekEnd})")
    }

    private suspend fun updateMonthly(
        dailyDao: SectorDailyRecordDao,
        summaryDao: SectorPeriodSummaryDao
    ) {
        val today = LocalDate.now()
        val monthStart = today.with(TemporalAdjusters.firstDayOfMonth())
        val monthEnd = today.with(TemporalAdjusters.lastDayOfMonth())

        // 取本月交易日数据（最近 30 天覆盖）
        val records = dailyDao.getRecentDays(30)
        val monthRecords = records.filter { record ->
            val date = try { LocalDate.parse(record.date, DATE_FMT) } catch (_: Exception) { return@filter false }
            !date.isBefore(monthStart) && !date.isAfter(today)
        }

        if (monthRecords.isEmpty()) {
            Log.w(TAG, "本月无交易日数据，跳过月摘要更新")
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
        Log.i(TAG, "月摘要更新: ${summaries.size} 个板块 (${monthStart}~${today})")
    }

    /**
     * 聚合板块日记录为周期摘要
     */
    private fun aggregateRecords(
        records: List<SectorDailyRecordEntity>,
        periodType: String,
        startDate: String,
        endDate: String,
        maxEntries: Int
    ): List<SectorPeriodSummaryEntity> {
        // 按板块分组
        val grouped = records.groupBy { it.sectorCode }

        return grouped.map { (sectorCode, sectorRecords) ->
            val avgChange = sectorRecords.map { it.changePct }.average()
            val totalInflow = sectorRecords.sumOf { it.mainNetInflow }
            val avgHotScore = sectorRecords.map { it.hotScore }.average()
            val hotDays = sectorRecords.count { it.isHot in listOf("S", "A") }
            val totalDays = sectorRecords.size
            val sectorName = sectorRecords.first().sectorName

            // 综合得分：涨幅 30% + 资金流入 30% + 热度 25% + 连续热天 15%
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
                rank = 0 // 稍后赋值
            ) to compositeScore
        }
            .sortedByDescending { it.second }
            .take(maxEntries)
            .mapIndexed { index, (entity, _) -> entity.copy(rank = index + 1) }
    }
}
