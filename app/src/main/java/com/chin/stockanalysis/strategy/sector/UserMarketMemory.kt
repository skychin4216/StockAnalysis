package com.chin.stockanalysis.strategy.sector

import android.content.Context
import android.content.SharedPreferences
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 用户市场记忆：管理用户关注板块 + AI 自动检测板块大年 */
class UserMarketMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("market_memory", Context.MODE_PRIVATE)
    private val db = StockDatabase.getInstance(context)

    companion object {
        const val KEY_FOCUS_SECTORS = "focus_sectors"
        const val KEY_AI_YEAR_DETECTION = "ai_year_detection"
        const val KEY_LAST_CHECK_DATE = "last_check_date"
        const val KEY_SECTOR_ALERTS = "sector_alerts"
        private const val MIGRATED_KEY = "focus_sectors_migrated_v20"
    }

    /** AI 检测的板块大年结论 */
    var aiYearDetection: String
        get() = prefs.getString(KEY_AI_YEAR_DETECTION, "未检测") ?: "未检测"
        set(value) = prefs.edit().putString(KEY_AI_YEAR_DETECTION, value).apply()

    /** 一次性迁移：将 SharedPreferences 中的 focusSectors 写入 DB */
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(MIGRATED_KEY, false)) return@withContext
        val legacy = prefs.getString(KEY_FOCUS_SECTORS, "")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        for (name in legacy) {
            db.userFocusSectorDao().insert(UserFocusSectorEntity(sectorName = name))
        }
        prefs.edit().putBoolean(MIGRATED_KEY, true).apply()
    }

    /** 获取当前启用的关注板块列表 */
    suspend fun getActiveSectors(): List<String> {
        migrateIfNeeded()
        return db.userFocusSectorDao().getActiveNames()
    }

    /** 获取所有关注板块（含已停用），用于 UI 展示 */
    suspend fun getAllFocusSectors(): List<UserFocusSectorEntity> {
        migrateIfNeeded()
        return db.userFocusSectorDao().getAll()
    }

    /** 新增或重新启用板块 */
    suspend fun addOrActivateSector(name: String) {
        val dao = db.userFocusSectorDao()
        val existing = dao.getAll().find { it.sectorName == name }
        if (existing != null) {
            if (!existing.isActive) dao.setActive(name, true)
        } else {
            dao.insert(UserFocusSectorEntity(sectorName = name))
        }
    }

    /** 切换板块启用/停用 */
    suspend fun toggleSector(name: String, active: Boolean) {
        db.userFocusSectorDao().setActive(name, active)
    }

    /** 删除板块 */
    suspend fun removeSector(name: String) {
        db.userFocusSectorDao().delete(name)
    }

    /** 获取用户关注板块的权重加成（用于策略选股） */
    suspend fun getFocusWeightBoost(sectorName: String): Int {
        val focus = getActiveSectors()
        return if (focus.any { sectorName.contains(it) || it.contains(sectorName.take(2)) }) 15 else 0
    }

    /** 检查用户关注板块是否连跌，返回需要提醒的列表 */
    suspend fun checkFocusSectorDrops(): List<SectorDropAlert> = withContext(Dispatchers.IO) {
        val focus = getActiveSectors()
        if (focus.isEmpty()) return@withContext emptyList()

        val today = try { db.dailySnapshotDao().getAvailableDates(1).firstOrNull() } catch (_: Exception) { null }
        if (today == null) return@withContext emptyList()

        val records = db.sectorDailyRecordDao().getRecentDays(5)
        val grouped = records.groupBy { it.sectorName }
        val alerts = mutableListOf<SectorDropAlert>()

        for (keyword in focus) {
            val matched = grouped.filter { (name, _) -> name.contains(keyword) || keyword.contains(name.take(2)) }
            for ((name, recs) in matched) {
                val sorted = recs.sortedBy { it.date }
                if (sorted.size >= 3) {
                    val last3 = sorted.takeLast(3).map { it.changePct }
                    val dropDays = last3.count { it < 0 }
                    val totalDrop = last3.sum()
                    if (dropDays >= 2 && totalDrop < -2.0) {
                        alerts.add(SectorDropAlert(name, dropDays, totalDrop, sorted.last().changePct))
                    }
                }
            }
        }
        alerts
    }

    /** AI 板块大年检测：通过科创50 vs 上证指数相对强弱判断 */
    suspend fun detectSectorYearByIndex(): String = withContext(Dispatchers.IO) {
        try {
            val kc50 = db.dailySnapshotDao().getByCode("sh000688", 60).sortedBy { it.date }
            val sh = db.dailySnapshotDao().getByCode("sh000001", 60).sortedBy { it.date }
            val cy = db.dailySnapshotDao().getByCode("sz399006", 60).sortedBy { it.date }

            if (kc50.size < 20 || sh.size < 20) return@withContext "数据不足"

            val kc50Return = (kc50.last().close - kc50[kc50.size - 20].close) / kc50[kc50.size - 20].close * 100
            val shReturn = (sh.last().close - sh[sh.size - 20].close) / sh[sh.size - 20].close * 100
            val cyReturn = (cy.last().close - cy[cy.size - 20].close) / cy[cy.size - 20].close * 100

            return@withContext when {
                kc50Return > shReturn + 5 && cyReturn > shReturn + 3 -> "科技大年（科创+创业板强于主板）"
                kc50Return > shReturn + 3 -> "科技偏强"
                shReturn > cyReturn + 3 && shReturn > kc50Return + 3 -> "主板大年（价值/周期风格）"
                cyReturn > shReturn + 5 -> "成长大年（创业板领涨）"
                else -> "风格均衡，无明显主线"
            }
        } catch (_: Exception) {
            "检测失败"
        }
    }

    /** 获取近期热门板块（从 sector_daily_record 表读取最近5个交易日，按热度评分排序） */
    suspend fun getRecentHotSectors(): List<RecentHotSector> = withContext(Dispatchers.IO) {
        try {
            val records = db.sectorDailyRecordDao().getRecentDays(5)
            if (records.isEmpty()) return@withContext emptyList()

            // 按板块名分组，计算近5日平均热度评分和累计涨幅
            val grouped = records.groupBy { it.sectorName }
            val result = grouped.map { (name, recs) ->
                val avgScore = recs.map { it.hotScore }.average()
                val totalChange = recs.sumOf { it.changePct }
                val hotDays = recs.count { it.isHot == "Y" || it.isHot == "true" }
                RecentHotSector(name, avgScore, totalChange, hotDays, recs.size)
            }.sortedByDescending { it.avgHotScore }.take(8)

            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 获取最近活跃的新闻板块（从 news_factor 表提取高频板块） */
    suspend fun getRecentNewsSectors(): List<String> = withContext(Dispatchers.IO) {
        try {
            val news = db.newsFactorDao().getAllActive(100)
            if (news.isEmpty()) return@withContext emptyList()
            news.groupBy { it.sector.takeIf { s -> s.isNotBlank() } ?: "其他" }
                .mapValues { (_, items) -> items.size }
                .filter { it.key != "其他" }
                .toList()
                .sortedByDescending { it.second }
                .take(8)
                .map { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class RecentHotSector(
        val sectorName: String,
        val avgHotScore: Double,
        val totalChangePct: Double,
        val hotDays: Int,
        val recordCount: Int
    )

    data class SectorDropAlert(
        val sectorName: String,
        val dropDays: Int,
        val totalDropPct: Double,
        val todayChangePct: Double
    )
}
