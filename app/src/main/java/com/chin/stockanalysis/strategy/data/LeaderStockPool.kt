package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 龙头股池 — 持久化管理
 *
 * 支持通过 Chat Agent 动态增删改查。
 * 数据持久化到 SharedPreferences（JSON），应用启动时加载。
 *
 * ### 设计原则
 * - 产业主线（半导体/AI算力/光通信/PCB/电网/新能源/存储/有色金属/小金属）长期保留
 * - 概念炒作（低空经济/量子/人形机器人等）不纳入静态配置，由 AI 动态板块补充
 * - 每个 Sector 可标记 isConcept=true 用于过滤
 */
class LeaderStockPool(private val context: Context) {

    companion object {
        private const val TAG = "LeaderStockPool"
        private const val PREFS_NAME = "leader_stock_pool"
        private const val KEY_CONFIGS = "sector_configs"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        // ════════════════════════════════════
        // 数据模型
        // ════════════════════════════════════
        data class SubSector(val name: String, val stocks: List<String>)

        data class SectorConfig(
            val name: String,
            val subSectors: List<SubSector>,
            val isConcept: Boolean = false  // 概念炒作标记，true=概念，false=产业主线
        )

        // 默认配置（首次启动时使用）
        private val DEFAULT_CONFIGS: List<SectorConfig> = listOf(
            SectorConfig("稀缺小金属", listOf(
                SubSector("钨",   listOf("sh603993", "sz000657", "sh600549")),
                SubSector("钼",   listOf("sh603399", "sz000960", "sh600497")),
                SubSector("铌",   listOf("sh600111", "sz000758", "sh600392")),
                SubSector("钒",   listOf("sh600101", "sz000629", "sh600117")),
                SubSector("钛",   listOf("sz002149", "sz000545", "sh600456")),
                SubSector("稀土", listOf("sh600111", "sh601600", "sh600392")),
            )),
            SectorConfig("有色金属", listOf(
                SubSector("锂", listOf("sz002460", "sz002466", "sh600338")),
                SubSector("铜", listOf("sh600362", "sz000630", "sh601899")),
                SubSector("铝", listOf("sh601600", "sz000807", "sh600219")),
            )),
            SectorConfig("半导体", listOf(
                SubSector("设备",   listOf("sh688981", "sz002371", "sh603501")),
                SubSector("设计",   listOf("sh688396", "sz300661", "sh688256")),
                SubSector("封测",   listOf("sh688036", "sz002156", "sh600584")),
                SubSector("国产替代", listOf("sh688012", "sz002049", "sh688008")),
            )),
            SectorConfig("AI算力", listOf(
                SubSector("国产算力", listOf("sh600536", "sh688111", "sz300502")),
                SubSector("超算",   listOf("sh688041", "sz002230", "sh603019")),
                SubSector("GPU服务器", listOf("sz000063", "sh601138", "sz002463")),
            )),
            SectorConfig("光通信", listOf(
                SubSector("光模块",     listOf("sz300394", "sz300308", "sh600498")),
                SubSector("光纤光缆",   listOf("sh601869", "sh600105", "sh600487")),
                SubSector("光芯片",     listOf("sh600703", "sz300661", "sh688313")),
                SubSector("光材料",     listOf("sz002281", "sh600745", "sz300502")),
                SubSector("铜缆高速连接", listOf("sz002475", "sh601138", "sz300502")),
            )),
            SectorConfig("PCB", listOf(
                SubSector("服务器PCB", listOf("sz002916", "sz002815", "sh603228")),
                SubSector("HDI板",    listOf("sz002384", "sh603920", "sz300476")),
                SubSector("IC载板",   listOf("sh603989", "sz002579", "sh688036")),
                SubSector("电子布",   listOf("sh600176", "sh600801", "sh600183")),
                SubSector("铜箔",     listOf("sh603799", "sz002902", "sz002340")),
            )),
            SectorConfig("电网设备", listOf(
                SubSector("特高压",   listOf("sh601700", "sh600406", "sh600312")),
                SubSector("智能电网", listOf("sh600131", "sz002339", "sh600517")),
                SubSector("储能",     listOf("sz300763", "sz300274", "sz300118")),
                SubSector("变压器",   listOf("sh600089", "sh600875", "sz002168")),
                SubSector("电线电缆", listOf("sh600973", "sh600522", "sh603618")),
            )),
            SectorConfig("氦气", listOf(
                SubSector("氦气资源", listOf("sh600378", "sh600746", "sh600028")),
                SubSector("稀有气体", listOf("sh600378", "sz002430", "sh600160")),
            )),
            SectorConfig("新能源", listOf(
                SubSector("锂电", listOf("sz300750", "sz002460", "sz002466")),
                SubSector("光伏", listOf("sh601012", "sh600438", "sz002459")),
                SubSector("绿电", listOf("sh600900", "sh600025", "sh600011")),
                SubSector("风电", listOf("sh601615", "sz002202", "sh600416")),
                SubSector("储能", listOf("sz300274", "sz300763", "sz300118")),
            )),
            SectorConfig("存储", listOf(
                SubSector("存储芯片", listOf("sh688256", "sz002049", "sh603501")),
                SubSector("HBM",      listOf("sh688981", "sh688012", "sh600584")),
                SubSector("存储模组", listOf("sz002036", "sz002055", "sh603160")),
            )),
        )

        // ════════════════════════════════════
        // 持久化读写
        // ════════════════════════════════════

        private fun getPrefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** 加载配置（首次使用默认值） */
        fun loadConfigs(context: Context): MutableList<SectorConfig> {
            val prefs = getPrefs(context)
            val json = prefs.getString(KEY_CONFIGS, null)
            return if (json != null) {
                try {
                    parseConfigs(json)
                } catch (e: Exception) {
                    Log.w(TAG, "解析持久化配置失败，使用默认: ${e.message}")
                    DEFAULT_CONFIGS.toMutableList()
                }
            } else {
                Log.i(TAG, "首次启动，使用默认龙头股配置")
                DEFAULT_CONFIGS.toMutableList()
            }
        }

        /** 保存配置 */
        fun saveConfigs(context: Context, configs: List<SectorConfig>) {
            val json = serializeConfigs(configs)
            getPrefs(context).edit().putString(KEY_CONFIGS, json).apply()
            Log.i(TAG, "龙头股配置已保存: ${configs.size} 个板块")
        }

        /** 重置为默认 */
        fun resetToDefault(context: Context): List<SectorConfig> {
            saveConfigs(context, DEFAULT_CONFIGS)
            Log.i(TAG, "龙头股配置已重置为默认")
            return DEFAULT_CONFIGS
        }

        // ════════════════════════════════════
        // JSON 序列化
        // ════════════════════════════════════

        private fun serializeConfigs(configs: List<SectorConfig>): String {
            val root = JSONArray()
            for (cfg in configs) {
                val obj = JSONObject()
                obj.put("name", cfg.name)
                obj.put("isConcept", cfg.isConcept)
                val subs = JSONArray()
                for (ss in cfg.subSectors) {
                    val sObj = JSONObject()
                    sObj.put("name", ss.name)
                    sObj.put("stocks", JSONArray(ss.stocks))
                    subs.put(sObj)
                }
                obj.put("subSectors", subs)
                root.put(obj)
            }
            return root.toString()
        }

        private fun parseConfigs(json: String): MutableList<SectorConfig> {
            val list = mutableListOf<SectorConfig>()
            val root = JSONArray(json)
            for (i in 0 until root.length()) {
                val obj = root.getJSONObject(i)
                val name = obj.getString("name")
                val isConcept = obj.optBoolean("isConcept", false)
                val subs = mutableListOf<SubSector>()
                val subArr = obj.getJSONArray("subSectors")
                for (j in 0 until subArr.length()) {
                    val sObj = subArr.getJSONObject(j)
                    val sName = sObj.getString("name")
                    val stocks = mutableListOf<String>()
                    val stockArr = sObj.getJSONArray("stocks")
                    for (k in 0 until stockArr.length()) {
                        stocks.add(stockArr.getString(k))
                    }
                    subs.add(SubSector(sName, stocks))
                }
                list.add(SectorConfig(name, subs, isConcept))
            }
            return list
        }

        // ════════════════════════════════════
        // 查询 API（供外部使用）
        // ════════════════════════════════════

        /** 获取当前配置（产业主线，排除概念） */
        fun getMainlineConfigs(context: Context): List<SectorConfig> =
            loadConfigs(context).filter { !it.isConcept }

        /** 获取所有配置（含概念） */
        fun getAllConfigs(context: Context): List<SectorConfig> =
            loadConfigs(context)

        /** 获取所有龙头代码（产业主线） */
        fun getMainlineCodes(context: Context): Set<String> {
            val set = mutableSetOf<String>()
            for (cfg in getMainlineConfigs(context)) {
                for (ss in cfg.subSectors) {
                    set.addAll(ss.stocks)
                }
            }
            return set
        }

        /** 获取所有龙头代码（含概念） */
        fun getAllCodes(context: Context): Set<String> {
            val set = mutableSetOf<String>()
            for (cfg in getAllConfigs(context)) {
                for (ss in cfg.subSectors) {
                    set.addAll(ss.stocks)
                }
            }
            return set
        }

        /** 列出所有板块名称 */
        fun listSectors(context: Context): List<String> =
            getAllConfigs(context).map { it.name }

        /** 列出某板块的所有子板块和股票 */
        fun listSubSectors(context: Context, sectorName: String): List<Pair<String, List<String>>> {
            val cfg = getAllConfigs(context).find { it.name == sectorName } ?: return emptyList()
            return cfg.subSectors.map { it.name to it.stocks }
        }

        // ════════════════════════════════════
        // 修改 API（供 Chat Agent Tool 使用）
        // ════════════════════════════════════

        /** 添加新板块 */
        fun addSector(context: Context, sectorName: String, isConcept: Boolean = false): Boolean {
            val configs = loadConfigs(context)
            if (configs.any { it.name == sectorName }) return false
            configs.add(SectorConfig(sectorName, emptyList(), isConcept))
            saveConfigs(context, configs)
            return true
        }

        /** 移除板块 */
        fun removeSector(context: Context, sectorName: String): Boolean {
            val configs = loadConfigs(context)
            val removed = configs.removeIf { it.name == sectorName }
            if (removed) saveConfigs(context, configs)
            return removed
        }

        /** 添加股票到子板块（子板块不存在则创建） */
        fun addStock(context: Context, sectorName: String, subSectorName: String, code: String): Boolean {
            val configs = loadConfigs(context)
            val cfg = configs.find { it.name == sectorName } ?: return false
            val ss = cfg.subSectors.find { it.name == subSectorName }
            val newSubs = if (ss == null) {
                cfg.subSectors + SubSector(subSectorName, listOf(code))
            } else {
                if (code in ss.stocks) return false
                cfg.subSectors.map {
                    if (it.name == subSectorName) it.copy(stocks = it.stocks + code)
                    else it
                }
            }
            val idx = configs.indexOf(cfg)
            configs[idx] = cfg.copy(subSectors = newSubs)
            saveConfigs(context, configs)
            return true
        }

        /** 从子板块移除股票 */
        fun removeStock(context: Context, sectorName: String, subSectorName: String, code: String): Boolean {
            val configs = loadConfigs(context)
            val cfg = configs.find { it.name == sectorName } ?: return false
            val ss = cfg.subSectors.find { it.name == subSectorName } ?: return false
            if (code !in ss.stocks) return false
            val newSubs = cfg.subSectors.map {
                if (it.name == subSectorName) it.copy(stocks = it.stocks - code)
                else it
            }.filter { it.stocks.isNotEmpty() }
            val idx = configs.indexOf(cfg)
            configs[idx] = cfg.copy(subSectors = newSubs)
            saveConfigs(context, configs)
            return true
        }

        /** 设置板块为概念/产业 */
        fun setSectorConcept(context: Context, sectorName: String, isConcept: Boolean): Boolean {
            val configs = loadConfigs(context)
            val idx = configs.indexOfFirst { it.name == sectorName }
            if (idx < 0) return false
            configs[idx] = configs[idx].copy(isConcept = isConcept)
            saveConfigs(context, configs)
            return true
        }
    }

    // ════════════════════════════════════════
    // 实例方法（保留原有功能）
    // ════════════════════════════════════════

    private val db = StockDatabase.getInstance(context)

    data class LeaderStockEntry(
        val code: String, val name: String,
        val sector: String, val subSector: String,
        val rank: Int, val changePct: Double, val volume: Long, val close: Double
    )

    /** 获取当前配置（已持久化） */
    private fun getConfigs(): List<SectorConfig> = loadConfigs(context)

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
            for (cfg in getConfigs().filter { !it.isConcept }) {  // 排除概念板块
                for (ss in cfg.subSectors) {
                    val ranked = ss.stocks.mapNotNull { snapMap[it] }
                        .filter { !it.name.contains("ST", ignoreCase = true) && !it.name.contains("退", ignoreCase = true) }
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

        for (cfg in getConfigs().filter { !it.isConcept }) {  // 排除概念板块
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

    /** 获取需要导入的全部代码（产业主线龙头股 + 每日龙头） */
    suspend fun getDailyImportCodes(date: String = LocalDate.now().format(DATE_FMT)): Set<String> = withContext(Dispatchers.IO) {
        val set = mutableSetOf<String>()
        set.addAll(getMainlineCodes(context))
        set.addAll(getDailyLeaders(date).map { it.code })
        Log.i(TAG, "导入池: ${set.size}只")
        set
    }
}
