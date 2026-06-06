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
 *
 * 所有页面（策略/板块/对话）共享此中心获取：
 * - 板块 → 子板块 → 个股 的完整映射
 * - 股票代码 → 所属板块的反向查询
 * - 实时行情数据缓存
 * - **历史热门板块统计（当日/近10日/近100日）**
 *
 * 设计原则：
 * 1. 单例模式，整个 App 共享一个实例
 * 2. 股票池数据持久化到 Room DB（sector_stocks 表）
 * 3. 实时行情来自 EastMoneyHotSectorSource 全局缓存
 * 4. 自动按市场交易时段调整刷新频率
 * 5. 所有板块/股票数据统计统一归口到这里
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
                try {
                    refreshMarketStatus()
                    loadSectorMappingsFromDB()
                } catch (_: Exception) {}
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

    // ═══════════════════════════════════════════════════════
    // 股票→板块 反向查询
    // ═══════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════
    // 全量板块列表
    // ═══════════════════════════════════════════════════════

    suspend fun getAllSectors(): List<String> {
        val live = (EastMoneyHotSectorSource.industrySectors + EastMoneyHotSectorSource.conceptSectors).map { it.name }.toSet()
        val ctx = appContext ?: return live.toList().sorted()
        val db = StockDatabase.getInstance(ctx)
        val dbSectors = db.sectorStockDao().getAllSectorKeys()
        return (live + dbSectors.toSet()).toList().sorted()
    }

    // ═══════════════════════════════════════════════════════
    // 历史热门板块统计 (v2.0 — 供策略选择)
    // ═══════════════════════════════════════════════════════

    /**
     * 获取指定时间段内的热门板块（按活跃度和涨幅排序）
     * @param days 回溯天数（1=当日, 10=近10日, 100=近100日）
     * @return 板块名称列表（Top 10）
     */
    suspend fun getHotSectorsByPeriod(days: Int): List<String> {
        val ctx = appContext ?: return emptyList()
        if (days <= 1) {
            // 当日：直接取实时概念板块
            val live = EastMoneyHotSectorSource.conceptSectors
            if (live.isNotEmpty()) return live.map { it.name }.take(10)
        }
        try {
            val db = StockDatabase.getInstance(ctx)
            val records = db.sectorDailyRecordDao().getRecentDays(days)
            // 按板块名聚合，统计出现频次和平均涨幅
            val sectorStats = records.groupBy { it.sectorName }
                .mapValues { (_, recs) ->
                    val avgPct = recs.map { it.changePct }.average()
                    val totalHot = recs.sumOf { it.hotScore.toDouble() }
                    val freq = recs.size
                    avgPct * 0.4 + totalHot * 0.3 + freq * 0.3 // 综合评分
                }
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .filter { it.isNotBlank() }
                .take(10)
            if (sectorStats.isNotEmpty()) return sectorStats
        } catch (_: Exception) {}
        // Fallback: 实时板块
        return EastMoneyHotSectorSource.conceptSectors.map { it.name }.take(10)
    }

    /**
     * 获取某板块在指定时间段内的Top个股（按平均涨幅排序）
     */
    suspend fun getTopStocksBySector(sectorName: String, days: Int, topN: Int = 5): List<Pair<String, String>> {
        val stockCodes = getStocksBySector(sectorName)
        if (stockCodes.isEmpty()) return emptyList()
        val ctx = appContext ?: return emptyList()
        try {
            val db = StockDatabase.getInstance(ctx)
            // 用 getRecentDays 获取近N天所有快照，然后按代码过滤
            val allSnapshots = db.dailySnapshotDao().getRecentDays(days)
            val codeSet = stockCodes.toSet()
            val filtered = allSnapshots.filter { it.code in codeSet }
            // 按code聚合平均涨幅
            val grouped: Map<String, List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>> = filtered.groupBy { snap -> snap.code }
            val ranked = grouped.map { (code, snaps) ->
                val avgPct = snaps.map { s -> s.changePct }.average()
                code to avgPct
            }.sortedByDescending { (_, avg) -> avg }.take(topN)
            return ranked.map { (code, _) ->
                val name = db.stockBasicDao().getByCode(code)?.name ?: code
                code to name
            }
        } catch (_: Exception) {
            return stockCodes.take(topN).map { it to it }
        }
    }

    // ═══════════════════════════════════════════════════════
    // 数据库加载
    // ═══════════════════════════════════════════════════════

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