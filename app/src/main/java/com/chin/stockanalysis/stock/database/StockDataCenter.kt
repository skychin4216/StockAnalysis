package com.chin.stockanalysis.stock.database

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.util.PinyinUtils
import com.chin.stockanalysis.stock.database.ChinaMarketTradingHours as A股TradingHours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ## 股票数据中心（v2.0 — 共享股票池 + 历史统计）
 */
object StockDataCenter {

    private const val TAG = "StockDataCenter"

    /** 板块→股票映射（内存缓存，key=sectorName） */
    private val sectorStockCache = mutableMapOf<String, List<String>>()

    /** 股票→板块映射（内存缓存，key=stockCode） */
    private val stockSectorCache = mutableMapOf<String, List<String>>()

    /** 全局交易状态 */
    private val _marketStatus = MutableStateFlow(A股TradingHours.获取状态摘要())
    val marketStatus: StateFlow<String> = _marketStatus.asStateFlow()

    // ═══════════════════════════════════════════════════════
    // v10.1: 用戶搜索記錄（重點關注股票池）
    // ═══════════════════════════════════════════════════════

    /** 用戶搜索過的股票完整記錄（含價格連結、搜索次數） */
    data class UserStockEntry(
        val stockCode: String,
        val stockName: String,
        val lastPrice: Double = -1.0,
        val lastChangePct: Double = 0.0,
        val searchCount: Int = 1,
        val firstSearchedAt: Long = System.currentTimeMillis(),
        val lastSearchedAt: Long = System.currentTimeMillis()
    )

    /** 來自 AI Skill 分析的精選股票記錄 */
    data class SkillPickEntry(
        val rank: Int,
        val stockCode: String,
        val stockName: String,
        val reason: String,
        val confidence: Float = 0.5f,
        val sourceSkillId: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** v10.0: AI 對話中用戶搜索過的股票（向後兼容） */
    @Volatile
    var userSearchHistory: List<Pair<String, String>> = emptyList()
        private set

    /** v10.1: 用戶搜索股票完整記錄（含價格、搜索次數） */
    @Volatile
    var userSearchHistoryEntries: List<UserStockEntry> = emptyList()
        private set

    /** v10.0: AI 對話中 Skill 精選出的股票（供模擬交易使用） */
    @Volatile
    var skillPicks: List<SkillPickEntry> = emptyList()
        private set

    private var refreshJob: Job? = null
    private var started = false
    private var appContext: Context? = null

    /** 初始化数据中心（MainActivity.onCreate 调用一次） */
    fun init(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        appContext = context.applicationContext
        refreshJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { refreshMarketStatus(); loadSectorMappingsFromDB() } catch (_: Exception) {}
                delay(A股TradingHours.获取刷新间隔())
            }
        }
        Log.i(TAG, "数据中心已初始化")
    }

    fun stop() { refreshJob?.cancel(); started = false }

    private fun refreshMarketStatus() {
        _marketStatus.value = A股TradingHours.获取状态摘要()
    }

    // ═══════════════════════════════════════════════════════
    // 板块→股票 查询
    // ═══════════════════════════════════════════════════════

    suspend fun getStocksBySector(sectorName: String): List<String> {
        sectorStockCache[sectorName]?.let { return it }
        val liveCodes = getLiveSectorCodes(sectorName)
        if (liveCodes.isNotEmpty()) { sectorStockCache[sectorName] = liveCodes; return liveCodes }
        val ctx = appContext ?: return emptyList()
        val db = StockDatabase.getInstance(ctx)
        val codes = db.sectorStockDao().getStockCodesBySector(sectorName)
        if (codes.isNotEmpty()) sectorStockCache[sectorName] = codes
        return codes
    }

    private fun getLiveSectorCodes(sectorName: String): List<String> {
        val allSectors = EastMoneyHotSectorSource.industrySectors + EastMoneyHotSectorSource.conceptSectors
        val match = allSectors.find { it.name == sectorName }
        if (match != null && match.top1StockCode.isNotEmpty()) return listOfNotNull(match.top1StockCode.takeIf { it.isNotEmpty() })
        return emptyList()
    }

    suspend fun getSectorsByStock(stockCode: String): List<String> {
        stockSectorCache[stockCode]?.let { return it }
        val ctx = appContext ?: return emptyList()
        val db = StockDatabase.getInstance(ctx)
        val sectors = db.sectorStockDao().getSectorNamesByStockCode(stockCode)
        if (sectors.isNotEmpty()) stockSectorCache[stockCode] = sectors
        return sectors
    }

    suspend fun getSubSectorByStock(stockCode: String, stockName: String): String {
        val sectors = getSectorsByStock(stockCode)
        if (sectors.isEmpty()) return "-"
        return com.chin.stockanalysis.stock.data.sources.SectorSubDivision
            .getSubSectors(sectors.firstOrNull() ?: "")
            .firstOrNull { ss -> ss.stocks.any { s -> s.code == stockCode } }?.name
            ?: sectors.first().take(8)
    }

    suspend fun getAllSectors(): List<String> {
        val live = (EastMoneyHotSectorSource.industrySectors + EastMoneyHotSectorSource.conceptSectors).map { it.name }.toSet()
        val ctx = appContext ?: return live.toList().sorted()
        val db = StockDatabase.getInstance(ctx)
        val dbSectors = db.sectorStockDao().getAllSectorKeys()
        return (live + dbSectors.toSet()).toList().sorted()
    }

    suspend fun getHotSectorsByPeriod(days: Int): List<String> {
        val ctx = appContext ?: return emptyList()
        if (days <= 1) {
            val live = EastMoneyHotSectorSource.conceptSectors
            if (live.isNotEmpty()) return live.map { it.name }.take(10)
        }
        try {
            val db = StockDatabase.getInstance(ctx)
            val records = db.sectorDailyRecordDao().getRecentDays(days)
            val sectorStats = records.groupBy { it.sectorName }
                .mapValues { (_, recs) ->
                    val avgPct = recs.map { it.changePct }.average()
                    val totalHot = recs.sumOf { it.hotScore.toDouble() }
                    val freq = recs.size
                    avgPct * 0.4 + totalHot * 0.3 + freq * 0.3
                }
                .entries.sortedByDescending { it.value }.map { it.key }.filter { it.isNotBlank() }.take(10)
            if (sectorStats.isNotEmpty()) return sectorStats
        } catch (_: Exception) {}
        return EastMoneyHotSectorSource.conceptSectors.map { it.name }.take(10)
    }

    suspend fun getTopStocksBySector(sectorName: String, days: Int, topN: Int = 5): List<Pair<String, String>> {
        val stockCodes = getStocksBySector(sectorName)
        if (stockCodes.isEmpty()) return emptyList()
        val ctx = appContext ?: return emptyList()
        try {
            val db = StockDatabase.getInstance(ctx)
            val allSnapshots = db.dailySnapshotDao().getRecentDays(days)
            val codeSet = stockCodes.toSet()
            val filtered = allSnapshots.filter { it.code in codeSet }
            val grouped: Map<String, List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>> = filtered.groupBy { it.code }
            val ranked = grouped.map { (code, snaps) ->
                val avgPct = snaps.map { it.changePct }.average()
                code to avgPct
            }.sortedByDescending { (_, avg) -> avg }.take(topN)
            return ranked.map { (code, _) ->
                val name = db.stockBasicDao().getByCode(code)?.name ?: code
                code to name
            }
        } catch (_: Exception) { return stockCodes.take(topN).map { it to it } }
    }

    private suspend fun loadSectorMappingsFromDB() {
        val ctx = appContext ?: return
        try {
            val db = StockDatabase.getInstance(ctx)
            val allKeys = db.sectorStockDao().getAllSectorKeys()
            for (key in allKeys) {
                if (key !in sectorStockCache) {
                    val codes = db.sectorStockDao().getStockCodesBySector(key)
                    if (codes.isNotEmpty()) sectorStockCache[key] = codes
                }
            }
            if (allKeys.size > 30) yield()
        } catch (_: Exception) {}
    }

    fun clearCache() { sectorStockCache.clear(); stockSectorCache.clear() }
    fun getStatus(): String = "数据: ${sectorStockCache.size}个板块, ${stockSectorCache.size}只股票已索引"

    // ═══════════════════════════════════════════════════════
    // v10.1: 用戶搜索記錄 API
    // ═══════════════════════════════════════════════════════

    fun recordUserSearch(
        stockCode: String,
        stockName: String,
        price: Double = -1.0,
        changePct: Double = 0.0
    ) {
        val current = userSearchHistoryEntries.toMutableList()
        val existing = current.indexOfFirst { it.stockCode == stockCode }
        val now = System.currentTimeMillis()
        if (existing >= 0) {
            val old = current[existing]
            current.removeAt(existing)
            current.add(0, old.copy(
                lastPrice = if (price > 0) price else old.lastPrice,
                lastChangePct = if (changePct != 0.0) changePct else old.lastChangePct,
                searchCount = old.searchCount + 1,
                lastSearchedAt = now
            ))
        } else {
            current.add(0, UserStockEntry(
                stockCode = stockCode, stockName = stockName,
                lastPrice = price, lastChangePct = changePct,
                searchCount = 1, firstSearchedAt = now, lastSearchedAt = now
            ))
            Log.i(TAG, "👤 用戶搜索新股票: $stockName ($stockCode)")
        }
        if (current.size > 30) current.removeAt(current.size - 1)
        userSearchHistoryEntries = current.toList()
        userSearchHistory = current.map { entry -> entry.stockCode to entry.stockName }
    }

    fun getRecentSearches(limit: Int = 10): List<Pair<String, String>> {
        return userSearchHistoryEntries.take(limit).map { entry -> entry.stockCode to entry.stockName }
    }

    fun getUserStockEntries(): List<UserStockEntry> = userSearchHistoryEntries

    fun getUserSearchStockCodes(): Set<String> {
        return userSearchHistoryEntries.map { entry -> entry.stockCode }.toSet()
    }

    fun getUserStockBoost(stockCode: String): Int {
        val entry = userSearchHistoryEntries.find { it.stockCode == stockCode } ?: return 0
        return (8 + (entry.searchCount - 1) * 2).coerceAtMost(15)
    }

    suspend fun refreshUserStockPrices() {
        val ctx = appContext ?: return
        try {
            val db = StockDatabase.getInstance(ctx)
            val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val snapshots = try { db.dailySnapshotDao().getByDate(today) } catch (_: Exception) { emptyList() }
            if (snapshots.isEmpty()) return
            val priceMap: Map<String, Pair<Double, Double>> = snapshots.associate { snap ->
                snap.code to (snap.close to snap.changePct)
            }
            val updated = userSearchHistoryEntries.map { entry ->
                val (price, pct) = priceMap[entry.stockCode] ?: (entry.lastPrice to entry.lastChangePct)
                entry.copy(lastPrice = price, lastChangePct = pct)
            }
            userSearchHistoryEntries = updated
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════
    // v10.0: Skill 精選股票池
    // ═══════════════════════════════════════════════════════

    fun addSkillPicks(picks: List<SkillPickEntry>) {
        if (picks.isEmpty()) return
        val current = skillPicks.toMutableList()
        val sourceIds = picks.map { it.sourceSkillId }.toSet()
        current.removeAll { it.sourceSkillId in sourceIds }
        current.addAll(0, picks)
        skillPicks = if (current.size > 50) current.take(50) else current.toList()
        Log.i(TAG, "📌 SkillPick 已存入: ${picks.size}隻 (Skill: ${sourceIds.joinToString()}), 總計: ${skillPicks.size}隻")
    }

    fun getRecentSkillPicks(limit: Int = 20): List<SkillPickEntry> = skillPicks.take(limit)

    fun getSkillPicksBySkillId(skillId: String): List<SkillPickEntry> {
        return skillPicks.filter { it.sourceSkillId == skillId }
    }

    /** v11.0: 取得 Skill/Agent 精選池中的所有股票代碼（供模擬交易優先考慮） */
    fun getSkillPickStockCodes(): Set<String> {
        return skillPicks.map { it.stockCode }.toSet()
    }

    /** v11.0: Skill/Agent 精選股票加權分數（基於信心度和排序） */
    fun getSkillPickBoost(stockCode: String): Int {
        val picks = skillPicks.filter { it.stockCode == stockCode }
        if (picks.isEmpty()) return 0
        // 取最高信心度 + 多次出現加分
        val maxConfidence = picks.maxOf { it.confidence }
        val frequencyBonus = (picks.size - 1) * 5
        val rankBonus = picks.minOf { it.rank }.let { if (it <= 3) 10 else if (it <= 5) 5 else 0 }
        return (maxConfidence * 15 + frequencyBonus + rankBonus).toInt().coerceIn(5, 30)
    }

    // ═══════════════════════════════════════════════════════
    // 拼音搜索
    // ═══════════════════════════════════════════════════════

    suspend fun searchSectors(keyword: String): List<String> {
        if (keyword.isBlank()) return emptyList()
        val all = getAllSectors()
        return all.filter { PinyinUtils.matches(it, keyword) }.take(20)
    }

    suspend fun searchStocks(keyword: String): List<Pair<String, String>> {
        if (keyword.isBlank()) return emptyList()
        val ctx = appContext ?: return emptyList()
        val db = StockDatabase.getInstance(ctx)
        val byName = db.stockBasicDao().searchByName(keyword)
        if (byName.isNotEmpty()) return byName.map { it.code to it.name }.take(20)
        val byCode = db.stockBasicDao().getByCode(keyword.lowercase())
        if (byCode != null) return listOf(byCode.code to byCode.name)
        for (prefix in listOf("sh", "sz", "bj")) {
            val stock = db.stockBasicDao().getByCode("$prefix$keyword")
            if (stock != null) return listOf(stock.code to stock.name)
        }
        val allStocks = db.stockBasicDao().getAll()
        return allStocks.filter {
            PinyinUtils.matches(it.name, keyword) || it.code.contains(keyword, ignoreCase = true)
        }.map { it.code to it.name }.take(20)
    }

    suspend fun searchStocksWithSectors(keyword: String): List<Triple<String, String, List<String>>> {
        val stocks = searchStocks(keyword)
        return stocks.map { (code, name) -> Triple(code, name, getSectorsByStock(code)) }
    }
}