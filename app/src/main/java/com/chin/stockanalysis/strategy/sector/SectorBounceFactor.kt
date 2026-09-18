package com.chin.stockanalysis.strategy.sector

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.SectorDailyRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 板块回调反弹因子：识别「近期热门但连跌后今日反弹」的板块 */
class SectorBounceFactor(private val db: StockDatabase) {

    data class BounceSector(
        val sectorName: String,
        val consecutiveHotDays: Int,
        val recentDropPct: Double,    // 近3天总涨幅（负数表示下跌）
        val todayBouncePct: Double,   // 今日涨幅
        val compositeScore: Double,
        val bounceScore: Double       // 0-100，越高越值得关注
    )

    /** 检测回弹板块：连续热门≥3天 + 近3天跌 + 今日反弹>0 */
    suspend fun detectBounceSectors(date: String? = null): List<BounceSector> = withContext(Dispatchers.IO) {
        val targetDate = date ?: getLatestDate()
        if (targetDate == null) return@withContext emptyList()

        // 获取最近 7 天的板块数据
        val recentRecords = db.sectorDailyRecordDao().getRecentDays(7)
        if (recentRecords.isEmpty()) return@withContext emptyList()

        val grouped = recentRecords.groupBy { it.sectorName }
        val result = mutableListOf<BounceSector>()

        for ((name, records) in grouped) {
            val sorted = records.sortedBy { it.date }
            if (sorted.size < 4) continue

            val latest = sorted.last()
            val consecutiveHot = latest.consecutiveHotDays
            val recent3Days = sorted.takeLast(3).map { it.changePct }
            val recentDrop = recent3Days.sum()
            val todayBounce = latest.changePct

            // 条件：连续热门≥3天 + 近3天总和<0（回调）+ 今日>0（反弹）
            if (consecutiveHot >= 3 && recentDrop < 0 && todayBounce > 0) {
                val score = (consecutiveHot * 3.0).coerceAtMost(30.0) +
                           (todayBounce * 8.0).coerceAtMost(40.0) +
                           ((-recentDrop) * 2.0).coerceAtMost(20.0) +
                           (latest.compositeScore / 10.0).coerceAtMost(10.0)
                result.add(BounceSector(
                    sectorName = name,
                    consecutiveHotDays = consecutiveHot,
                    recentDropPct = recentDrop,
                    todayBouncePct = todayBounce,
                    compositeScore = latest.compositeScore,
                    bounceScore = score.coerceIn(0.0, 100.0)
                ))
            }
        }
        result.sortedByDescending { it.bounceScore }
    }

    /** 检查某股票是否属于回弹板块（名称匹配） */
    suspend fun getBounceScoreForStock(stockName: String, date: String? = null): Double {
        val bounces = detectBounceSectors(date)
        val match = bounces.find { stockName.contains(it.sectorName) || it.sectorName.contains(stockName.take(2)) }
        return match?.bounceScore ?: 0.0
    }

    private suspend fun getLatestDate(): String? {
        return try {
            db.dailySnapshotDao().getAvailableDates(1).firstOrNull()
        } catch (_: Exception) { null }
    }
}
