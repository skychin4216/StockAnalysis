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

    // ═══════════════════════════════════════════════════════
    // code-to-name + 股價查詢
    // ═══════════════════════════════════════════════════════

    data class StockQuote(
        val code: String,
        val name: String,
        val price: Double,
        val changePct: Double,
        val date: String
    )

    /**
     * 根據股票代碼查詢名稱 + 最近交易日股價
     * 優先從緩存獲取，緩存未命中則查數據庫
     */
    suspend fun getStockQuote(stockCode: String): StockQuote? {
        val ctx = appContext ?: return null
        val db = StockDatabase.getInstance(ctx)

        // 1. 從 stock_basics 獲取名稱
        val basic = try { db.stockBasicDao().getByCode(stockCode) } catch (_: Exception) { null }

        // 2. 獲取最近交易日
        val today = java.time.LocalDate.now().toString()
        val dates = try { db.dailySnapshotDao().getAvailableDates(5) } catch (_: Exception) { emptyList() }
        val targetDate = dates.filter { it <= today }.maxOrNull() ?: today

        // 3. 從日快照獲取股價
        val snaps = try { db.dailySnapshotDao().getByDate(targetDate) } catch (_: Exception) { emptyList() }
        val snap = snaps.find { it.code == stockCode }

        return if (snap != null) {
            StockQuote(code = stockCode, name = snap.name, price = snap.close, changePct = snap.changePct, date = targetDate)
        } else if (basic != null) {
            StockQuote(code = stockCode, name = basic.name, price = -1.0, changePct = 0.0, date = targetDate)
        } else {
            null
        }
    }

    /**
     * 批量查詢股票名稱（從 stock_basics 緩存）
     */
    suspend fun getStockNames(codes: Collection<String>): Map<String, String> {
        val ctx = appContext ?: return emptyMap()
        val db = StockDatabase.getInstance(ctx)
        return try {
            db.stockBasicDao().getByCodes(codes.toList()).associate { it.code to it.name }
        } catch (_: Exception) { emptyMap() }
    }

    /**
     * 根據股票代碼查詢名稱（簡化版）
     */
    suspend fun getStockName(stockCode: String): String {
        return getStockQuote(stockCode)?.name ?: stockCode
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

    /**
     * 用戶搜索加權分數（基於對數衰減 + 上限 10 分的階梯式設計）
     *
     * 設計原理：
     * - 第1次搜索：+3 分（首次關注信號）
     * - 第2次搜索：+5 分（重複關注，信心提升）
     * - 第3次搜索：+7 分（持續關注，高分信號）
     * - 第4次搜索：+8 分
     * - 第5次+    ：+9~10 分區間趨近（邊際遞減，避免氾濫）
     *
     * 公式：3 + floor(ln(searchCount) * 3.0)，上限 10
     */
    fun getUserStockBoost(stockCode: String): Int {
        val entry = userSearchHistoryEntries.find { it.stockCode == stockCode } ?: return 0
        val n = entry.searchCount.coerceIn(1, 100)
        return if (n == 1) 3
        else if (n == 2) 5
        else if (n == 3) 7
        else (7 + kotlin.math.ln((n - 2).toDouble()).toInt()).coerceAtMost(10)
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

    // ═══════════════════════════════════════════════════════
    // v13.0: 自選股票池（作為額外信號源，不加分不過濾）
    // ═══════════════════════════════════════════════════════

    /** 從 SharedPreferences 讀取自選股票代碼 */
    fun getWatchlistStockCodes(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("watchlist_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("groups", "[]") ?: "[]"
        val codes = mutableSetOf<String>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val stocksArr = obj.optJSONArray("stocks") ?: org.json.JSONArray()
                for (j in 0 until stocksArr.length()) {
                    codes.add(stocksArr.getJSONObject(j).getString("code"))
                }
            }
        } catch (_: Exception) {}
        return codes
    }

    /**
     * Skill/Agent 精選股票加權分數（上限 10 分）
     *
     * 設計原理：
     * - 基於 AI 信心度（confidence）× 8 分
     * - 排名加分：rank=1 +2分, rank≤3 +1分
     * - 多次精選加分：每多一次 +0.5分（邊際遞減）
     * - 上限 10 分，確保用戶搜索和智能體精選在同一尺度
     */
    fun getSkillPickBoost(stockCode: String): Int {
        val picks = skillPicks.filter { it.stockCode == stockCode }
        if (picks.isEmpty()) return 0
        val maxConfidence = picks.maxOf { it.confidence }
        val rankBonus = picks.minOf { it.rank }.let { if (it == 1) 2 else if (it <= 3) 1 else 0 }
        val frequencyBonus = if (picks.size > 1) ((picks.size - 1) * 0.5).toInt().coerceAtMost(3) else 0
        return (maxConfidence * 8 + rankBonus + frequencyBonus).toInt().coerceIn(2, 10)
    }

    // ═══════════════════════════════════════════════════════
    // 綜合股票熱度評分 (v12.0)
    // ═══════════════════════════════════════════════════════

    /**
     * 技術壁壘核心概念板塊 — 科技類中具有高護城河的子行業
     */
    private val TECH_MOAT_SECTORS = setOf(
        "光刻机", "光刻胶", "芯片设计", "芯片制造", "先进封装", "EDA",
        "半导体设备", "半导体材料", "第三代半导体", "IGBT",
        "碳化硅", "氮化镓", "光模块", "CPO", "算力租赁",
        "量子计算", "卫星互联网", "商业航天", "航空发动机",
        "工业母机", "高端数控机床", "机器人", "机器视觉",
        "新材料", "稀土永磁", "碳纤维", "石墨烯",
        "创新药", "CXO", "基因编辑", "合成生物"
    )

    /**
     * 計算一隻股票的綜合熱度分數 (0-100 分)
     *
     * 五個維度：
     * 1. 交易量能 (30分) — 換手率 + 成交額佔比 + 量比
     * 2. 資金動向 (20分) — 主力淨流入 + 板塊資金強度
     * 3. 板塊歷史熱度 (20分) — 近60天板塊上榜次數 + 綜合評分
     * 4. 價格位置 (15分) — 漲跌幅歷史分位 + 是否階段新高
     * 5. 概念壁壘 (15分) — 是否核心科技概念 + 是否技術壁壘板塊
     *
     * @param stockCode 股票代碼 (如 sh600519)
     * @param todaySnapshots 當日全市場快照 (用於計算全市場統計)
     * @return 0-100 分，越高越熱門
     */
    suspend fun calculateStockHeatScore(
        stockCode: String,
        todaySnapshots: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>
    ): Int {
        val ctx = appContext ?: return 25
        val db = StockDatabase.getInstance(ctx)
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        // 找當前股票的當日數據
        val selfSnap = todaySnapshots.find { it.code == stockCode } ?: return 15

        var score = 0

        // ════════════════════════════════════════════
        // 維度1: 交易量能 (0-30分)
        // ════════════════════════════════════════════

        // 1a. 換手率評分 (0-10分) — 越活躍越高，但極高會警示
        val turnoverRate = selfSnap.turnoverRate.coerceIn(0.0, 50.0)
        val turnoverScore = when {
            turnoverRate >= 8 -> 10  // 8%+ 極活躍
            turnoverRate >= 5 -> 8
            turnoverRate >= 3 -> 6
            turnoverRate >= 1 -> 3
            else -> 1
        }
        score += turnoverScore

        // 1b. 成交額佔全市場排名 (0-10分)
        if (todaySnapshots.isNotEmpty()) {
            val totalAmount = todaySnapshots.sumOf { it.amount }
            if (totalAmount > 0) {
                val pct = selfSnap.amount / totalAmount
                val amountScore = when {
                    pct >= 0.03 -> 10 // 佔全市場3%+
                    pct >= 0.01 -> 7
                    pct >= 0.005 -> 5
                    pct >= 0.001 -> 3
                    else -> 1
                }
                score += amountScore
            }
        }

        // 1c. 量比（近5日均量對比，0-10分）
        try {
            val recentDates = db.dailySnapshotDao().getAvailableDates(5)
                .filter { it <= today }.sorted().takeLast(5)
            if (recentDates.size >= 5) {
                val avgVolume = recentDates.dropLast(1)
                    .mapNotNull { date -> db.dailySnapshotDao().getByDate(date).find { it.code == stockCode }?.volume }
                    .average()
                if (avgVolume > 0) {
                    val volumeRatio = selfSnap.volume / avgVolume
                    val volScore = when {
                        volumeRatio >= 3.0 -> 10
                        volumeRatio >= 2.0 -> 7
                        volumeRatio >= 1.5 -> 5
                        volumeRatio >= 1.0 -> 3
                        else -> 1
                    }
                    score += volScore
                }
            }
        } catch (_: Exception) { score += 3 }

        // ════════════════════════════════════════════
        // 維度2: 資金動向 (0-20分)
        // ════════════════════════════════════════════

        // 2a. 所屬板塊的主力資金淨流入
        try {
            val sectors = db.sectorStockDao().getSectorNamesByStockCode(stockCode)
            val allSectors = EastMoneyHotSectorSource.industrySectors + EastMoneyHotSectorSource.conceptSectors
            val matchedSectors = allSectors.filter { hs -> sectors.any { s -> hs.name.contains(s) || s.contains(hs.name) } }
            if (matchedSectors.isNotEmpty()) {
                val maxInflow = matchedSectors.maxOf { it.mainNetInflow }
                val avgComposite = matchedSectors.map { it.compositeScore }.average()
                val inflowScore = when {
                    maxInflow >= 10 -> 10  // 主力淨流入 >= 10億
                    maxInflow >= 5 -> 7
                    maxInflow >= 1 -> 5
                    maxInflow > 0 -> 3
                    else -> 0
                }
                val compositeScore = (avgComposite / 20.0).toInt().coerceIn(0, 10)
                score += inflowScore + compositeScore
            } else {
                score += 5  // 無板塊歸屬，給基礎分
            }
        } catch (_: Exception) { score += 5 }

        // ════════════════════════════════════════════
        // 維度3: 板塊歷史熱度 (0-20分)
        // ════════════════════════════════════════════
        try {
            val sectors = db.sectorStockDao().getSectorNamesByStockCode(stockCode)
            val recentRecords = db.sectorDailyRecordDao().getRecentDays(60)
            val sectorRecordCount = recentRecords.count { r -> sectors.any { r.sectorName.contains(it) || it.contains(r.sectorName) } }
            val sectorHotCount = recentRecords.count { r ->
                sectors.any { s -> r.sectorName.contains(s) || s.contains(r.sectorName) } && r.isHot in listOf("S", "A")
            }
            val historyScore = when {
                sectorHotCount >= 20 -> 20
                sectorHotCount >= 10 -> 15
                sectorHotCount >= 5 -> 10
                sectorRecordCount >= 10 -> 5
                else -> 2
            }
            score += historyScore
        } catch (_: Exception) { score += 5 }

        // ════════════════════════════════════════════
        // 維度4: 價格位置 (0-15分)
        // ════════════════════════════════════════════

        // 4a. 當前漲跌幅動能 (0-8分)
        val changePct = kotlin.math.abs(selfSnap.changePct)
        val changeScore = when {
            changePct >= 9.5 -> 8  // 漲停/跌停
            changePct >= 7 -> 6
            changePct >= 5 -> 5
            changePct >= 3 -> 3
            changePct >= 1 -> 1
            else -> 0
        }
        score += changeScore

        // 4b. 是否階段新高（近30日最高收盤價）(0-7分)
        try {
            val recent30 = db.dailySnapshotDao().getAvailableDates(30)
                .filter { it <= today }.sorted().takeLast(30)
            val recentHighs = recent30.mapNotNull { date ->
                db.dailySnapshotDao().getByDate(date).find { it.code == stockCode }?.close
            }
            if (recentHighs.isNotEmpty()) {
                val maxClose = recentHighs.max()
                val isNewHigh = selfSnap.close >= maxClose * 0.98  // 2% 誤差容忍
                score += if (isNewHigh) 7 else 3
            }
        } catch (_: Exception) { score += 3 }

        // ════════════════════════════════════════════
        // 維度5: 概念壁壘 (0-15分)
        // ════════════════════════════════════════════
        try {
            val stockSectors = db.sectorStockDao().getSectorNamesByStockCode(stockCode)
            val hasMoat = stockSectors.any { s -> TECH_MOAT_SECTORS.any { ts -> s.contains(ts) || ts.contains(s) } }
            score += if (hasMoat) 15 else 5
        } catch (_: Exception) { score += 5 }

        val finalScore = score.coerceIn(0, 100)
        if (selfSnap.name.isNotBlank()) {
            Log.d(TAG, "🔥 [热度评分] ${selfSnap.name}($stockCode): $finalScore/100 " +
                    "(量能30/${turnoverScore} 资金20 历史20 价格15 壁垒15)")
        }
        return finalScore
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