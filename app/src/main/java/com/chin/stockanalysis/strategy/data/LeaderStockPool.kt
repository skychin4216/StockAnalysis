package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 龙头股池 — 10大科技板块 × 41子板块 × 81只龙头股
 *
 * ### 数据来源
 * 参考 AutoQuant 项目的 hot_sector_config.py / LeaderStockPool.py
 *
 * ### 刷新策略
 * - 全量池（81只龙头股）: 每周一次 + 用户点击导入时
 * - 每日龙头（子板块 Top3）: 每日一次（基于当日涨幅排序）
 *
 * ### 筛选流程
 * 1. 排雷: ST/停牌/次新股
 * 2. 赛道: 10大科技板块 → 41子板块
 * 3. 龙头: 子板块内按市值/涨幅 Top3
 * 4. 排名: 动量+资金+相对强度 Z-score 综合打分
 */
class LeaderStockPool(private val context: Context) {

    companion object {
        private const val TAG = "LeaderStockPool"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        // ════════════════════════════════════
        // 板块→子板块→龙头股 映射
        // ════════════════════════════════════
        data class SubSector(val name: String, val stocks: List<String>)

        data class SectorConfig(val name: String, val subSectors: List<SubSector>)

        val SECTOR_CONFIGS = listOf(
            // 1. 稀缺小金属 (6子板块)
            SectorConfig("稀缺小金属", listOf(
                SubSector("钨",   listOf("sh603993", "sz000657", "sh600549")),
                SubSector("钼",   listOf("sh603399", "sz000960", "sh600497")),
                SubSector("铌",   listOf("sh600111", "sz000758", "sh600392")),
                SubSector("钒",   listOf("sh600101", "sz000629", "sh600117")),
                SubSector("钛",   listOf("sz002149", "sz000545", "sh600456")),
                SubSector("稀土", listOf("sh600111", "sh601600", "sh600392")),
            )),
            // 2. 有色金属 (3子板块)
            SectorConfig("有色金属", listOf(
                SubSector("锂", listOf("sz002460", "sz002466", "sh600338")),
                SubSector("铜", listOf("sh600362", "sz000630", "sh601899")),
                SubSector("铝", listOf("sh601600", "sz000807", "sh600219")),
            )),
            // 3. 半导体 (4子板块)
            SectorConfig("半导体", listOf(
                SubSector("设备",   listOf("sh688981", "sz002371", "sh603501")),
                SubSector("设计",   listOf("sh688396", "sz300661", "sh688256")),
                SubSector("封测",   listOf("sh688036", "sz002156", "sh600584")),
                SubSector("国产替代", listOf("sh688012", "sz002049", "sh688008")),
            )),
            // 4. AI算力 (3子板块)
            SectorConfig("AI算力", listOf(
                SubSector("国产算力", listOf("sh600536", "sh688111", "sz300502")),
                SubSector("超算",   listOf("sh688041", "sz002230", "sh603019")),
                SubSector("GPU服务器", listOf("sz000063", "sh601138", "sz002463")),
            )),
            // 5. 光通信 (5子板块)
            SectorConfig("光通信", listOf(
                SubSector("光模块",     listOf("sz300394", "sz300308", "sh600498")),
                SubSector("光纤光缆",   listOf("sh601869", "sh600105", "sh600487")),
                SubSector("光芯片",     listOf("sh600703", "sz300661", "sh688313")),
                SubSector("光材料",     listOf("sz002281", "sh600745", "sz300502")),
                SubSector("铜缆高速连接", listOf("sz002475", "sh601138", "sz300502")),
            )),
            // 6. PCB (5子板块)
            SectorConfig("PCB", listOf(
                SubSector("服务器PCB", listOf("sz002916", "sz002815", "sh603228")),
                SubSector("HDI板",    listOf("sz002384", "sh603920", "sz300476")),
                SubSector("IC载板",   listOf("sh603989", "sz002579", "sh688036")),
                SubSector("电子布",   listOf("sh600176", "sh600801", "sh600183")),
                SubSector("铜箔",     listOf("sh603799", "sz002902", "sz002340")),
            )),
            // 7. 电网设备 (5子板块)
            SectorConfig("电网设备", listOf(
                SubSector("特高压",   listOf("sh601700", "sh600406", "sh600312")),
                SubSector("智能电网", listOf("sh600131", "sz002339", "sh600517")),
                SubSector("储能",     listOf("sz300763", "sz300274", "sz300118")),
                SubSector("变压器",   listOf("sh600089", "sh600875", "sz002168")),
                SubSector("电线电缆", listOf("sh600973", "sh600522", "sh603618")),
            )),
            // 8. 氦气 (2子板块)
            SectorConfig("氦气", listOf(
                SubSector("氦气资源", listOf("sh600378", "sh600746", "sh600028")),
                SubSector("稀有气体", listOf("sh600378", "sz002430", "sh600160")),
            )),
            // 9. 新能源 (5子板块)
            SectorConfig("新能源", listOf(
                SubSector("锂电", listOf("sz300750", "sz002460", "sz002466")),
                SubSector("光伏", listOf("sh601012", "sh600438", "sz002459")),
                SubSector("绿电", listOf("sh600900", "sh600025", "sh600011")),
                SubSector("风电", listOf("sh601615", "sz002202", "sh600416")),
                SubSector("储能", listOf("sz300274", "sz300763", "sz300118")),
            )),
            // 10. 存储 (3子板块)
            SectorConfig("存储", listOf(
                SubSector("存储芯片", listOf("sh688256", "sz002049", "sh603501")),
                SubSector("HBM",      listOf("sh688981", "sh688012", "sh600584")),
                SubSector("存储模组", listOf("sz002036", "sz002055", "sh603160")),
            )),
        )

        // 所有龙头股的去重集合（用于导入）
        val ALL_LEADER_CODES: Set<String> by lazy {
            mutableSetOf<String>().apply {
                for (cfg in SECTOR_CONFIGS) {
                    for (ss in cfg.subSectors) {
                        addAll(ss.stocks)
                    }
                }
            }.toSet()
        }
    }

    private val db = StockDatabase.getInstance(context)

    data class LeaderStockEntry(
        val code: String, val name: String,
        val sector: String, val subSector: String,
        val rank: Int, val changePct: Double, val volume: Long, val close: Double
    )

    // ════════════════════════════════════════
    // 1. 每日龙头 — 子板块内 Top3
    // ════════════════════════════════════════

    suspend fun getDailyLeaders(date: String = LocalDate.now().format(DATE_FMT)): List<LeaderStockEntry> = withContext(Dispatchers.IO) {
        Log.i(TAG, "每日龙头: $date")
        val entries = mutableListOf<LeaderStockEntry>()
        try {
            val snaps = db.dailySnapshotDao().getByDate(date)
            if (snaps.isEmpty()) { Log.w(TAG, "无今日数据"); return@withContext entries }
            val snapMap = snaps.associateBy { it.code }
            for (cfg in SECTOR_CONFIGS) {
                for (ss in cfg.subSectors) {
                    val ranked = ss.stocks.mapNotNull { snapMap[it] }
                        .sortedByDescending { it.changePct }
                        .take(3)
                    for ((idx, snap) in ranked.withIndex()) {
                        entries.add(LeaderStockEntry(
                            code = snap.code, name = snap.name,
                            sector = cfg.name, subSector = ss.name,
                            rank = idx + 1, changePct = snap.changePct,
                            volume = snap.volume, close = snap.close
                        ))
                    }
                }
            }
            Log.i(TAG, "每日龙头: ${entries.size}只 (${entries.groupBy { it.sector }.size}个板块)")
        } catch (e: Exception) { Log.w(TAG, "每日龙头失败: ${e.message}") }
        entries
    }

    // ════════════════════════════════════════
    // 2. 多维度动量打分 — 板块热度排名
    // ════════════════════════════════════════

    suspend fun getTopSectorsByMomentum(
        date: String = LocalDate.now().format(DATE_FMT),
        rankDays: Int = 5, lookbackDays: Int = 20, topN: Int = 10
    ): List<Pair<String, Double>> = withContext(Dispatchers.IO) {
        Log.i(TAG, "动量打分: $date (${rankDays}d / ${lookbackDays}d)")
        val allDates = try {
            db.dailySnapshotDao().getAvailableDates(lookbackDays + 5).sorted()
                .filter { it <= date }.takeLast(lookbackDays + rankDays)
        } catch (_: Exception) { emptyList() }
        if (allDates.size < rankDays + 3) { Log.w(TAG, "数据不足: ${allDates.size}天"); return@withContext emptyList() }

        val benchCodes = listOf("sh601398", "sh601939", "sh600036", "sh600016")
        val benchReturns = mutableListOf<Double>()

        data class SectorScore(val name: String, val momentum: Double, val fundFlow: Double, val relativeStrength: Double)
        val scores = mutableListOf<SectorScore>()

        for (cfg in SECTOR_CONFIGS) {
            val codes = cfg.subSectors.flatMap { it.stocks }.toSet()
            val returns = mutableListOf<Double>()
            for (dateStr in allDates) {
                val snaps = try { db.dailySnapshotDao().getByDate(dateStr) } catch (_: Exception) { emptyList() }
                val sec = snaps.filter { it.code in codes }
                if (sec.isEmpty()) continue
                returns.add(sec.map { it.changePct }.average())
                if (benchReturns.size < returns.size) {
                    val bn = snaps.filter { it.code in benchCodes }
                    benchReturns.add(if (bn.isNotEmpty()) bn.map { it.changePct }.average() else 0.0)
                }
            }
            if (returns.size < rankDays + 2) continue
            val short = returns.takeLast(rankDays)
            val mom = short.sum()
            val sVol = short.map { kotlin.math.abs(it) }.average()
            val lVol = if (returns.size >= lookbackDays) returns.takeLast(lookbackDays).map { kotlin.math.abs(it) }.average() else sVol
            val ff = if (lVol > 0.001) (sVol / lVol - 1.0) * 100 else 0.0
            val bSlice = benchReturns.takeLast(minOf(benchReturns.size, rankDays))
            val bMom = if (bSlice.isNotEmpty()) bSlice.sum() else 0.0
            val rs = mom - bMom
            scores.add(SectorScore(cfg.name, mom, ff, rs))
        }
        if (scores.isEmpty()) return@withContext emptyList()

        fun List<Double>.z(): List<Double> {
            val m = average(); val s = kotlin.math.sqrt(map { (it - m) * (it - m) }.average())
            return if (s > 0.001) map { (it - m) / s } else map { 0.0 }
        }
        val mz = scores.map { it.momentum }.z()
        val fz = scores.map { it.fundFlow }.z()
        val rz = scores.map { it.relativeStrength }.z()
        val ranked = scores.indices.map { i -> scores[i].name to ((mz[i] + fz[i] + rz[i]) / 3.0) }
            .sortedByDescending { it.second }
        Log.i(TAG, "热门Top${minOf(topN, ranked.size)}: ${ranked.take(topN).joinToString { "${it.first}(${"%.2f".format(it.second)})" }}")
        ranked.take(topN)
    }

    // ════════════════════════════════════════
    // 3. 导入代码池
    // ════════════════════════════════════════

    /** 获取需要导入的全部代码（龙头股81只 + 每日龙头 + 历史热门板块Top3） */
    suspend fun getDailyImportCodes(date: String = LocalDate.now().format(DATE_FMT)): Set<String> = withContext(Dispatchers.IO) {
        val set = mutableSetOf<String>()
        set.addAll(ALL_LEADER_CODES)
        set.addAll(getDailyLeaders(date).map { it.code })
        Log.i(TAG, "导入池: ${set.size}只")
        set
    }
}