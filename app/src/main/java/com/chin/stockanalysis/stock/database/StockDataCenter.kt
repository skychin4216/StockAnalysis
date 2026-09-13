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
    // v10.1: 用户搜索记录（重点关注股票池）
    // ═══════════════════════════════════════════════════════

    /** 用户搜索过的股票完整记录（含价格连结、搜索次数） */
    data class UserStockEntry(
        val stockCode: String,
        val stockName: String,
        val lastPrice: Double = -1.0,
        val lastChangePct: Double = 0.0,
        val searchCount: Int = 1,
        val firstSearchedAt: Long = System.currentTimeMillis(),
        val lastSearchedAt: Long = System.currentTimeMillis()
    )

    /** 来自 AI Skill 分析的精选股票记录 */
    data class SkillPickEntry(
        val rank: Int,
        val stockCode: String,
        val stockName: String,
        val reason: String,
        val confidence: Float = 0.5f,
        val sourceSkillId: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** v10.0: AI 对话中用户搜索过的股票（向后兼容） */
    @Volatile
    var userSearchHistory: List<Pair<String, String>> = emptyList()
        private set

    /** v10.1: 用户搜索股票完整记录（含价格、搜索次数） */
    @Volatile
    var userSearchHistoryEntries: List<UserStockEntry> = emptyList()
        private set

    /** v10.0: AI 对话中 Skill 精选出的股票（供模拟交易使用） */
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

    /** 常用股票 → 板块的硬编码映射（DB 无数据时的降级方案） */
    private val FALLBACK_NAME_SECTOR = mapOf(
        "兆易创新" to listOf("存储芯片", "半导体"),
        "兆易创新" to listOf("存储芯片", "半导体"),
        "贵州茅台" to listOf("白酒"),
        "宁德时代" to listOf("电池"),
        "比亚迪" to listOf("新能源汽车"),
        "中芯国际" to listOf("半导体"),
        "韦尔股份" to listOf("存储芯片", "半导体"),
        "北方华创" to listOf("半导体设备"),
        "中际旭创" to listOf("光模块", "通信设备"),
        "立讯精密" to listOf("消费电子"),
        "海康威视" to listOf("安防"),
        "招商银行" to listOf("银行"),
        "长江电力" to listOf("电力"),
        "恒瑞医药" to listOf("医药"),
        "药明康德" to listOf("CXO"),
        "美的集团" to listOf("家电"),
        "格力电器" to listOf("家电"),
        "五粮液" to listOf("白酒"),
        "中国平安" to listOf("保险")
    )

    suspend fun getSectorsByStock(stockCode: String): List<String> {
        stockSectorCache[stockCode]?.let { return it }
        val ctx = appContext ?: return emptyList()
        val db = StockDatabase.getInstance(ctx)
        val sectors = db.sectorStockDao().getSectorNamesByStockCode(stockCode)
        if (sectors.isNotEmpty()) {
            stockSectorCache[stockCode] = sectors
            return sectors
        }
        // 降级：尝试用股票名称匹配硬编码映射
        try {
            val snap = db.dailySnapshotDao().getByCode(stockCode).firstOrNull()
            val name = snap?.name ?: ""
            for ((key, value) in FALLBACK_NAME_SECTOR) {
                if (name.contains(key) || key.contains(name)) {
                    stockSectorCache[stockCode] = value
                    return value
                }
            }
        } catch (_: Exception) {}
        return emptyList()
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
    // code-to-name + 股价查询
    // ═══════════════════════════════════════════════════════

    data class StockQuote(
        val code: String,
        val name: String,
        val price: Double,
        val changePct: Double,
        val date: String
    )

    /**
     * 根据股票代码查询名称 + 最近交易日股价
     * 优先从缓存获取，缓存未命中则查数据库
     */
    suspend fun getStockQuote(stockCode: String): StockQuote? {
        val ctx = appContext ?: return null
        val db = StockDatabase.getInstance(ctx)

        // 1. 从 stock_basics 获取名称
        val basic = try { db.stockBasicDao().getByCode(stockCode) } catch (_: Exception) { null }

        // 2. 获取最近交易日
        val today = java.time.LocalDate.now().toString()
        val dates = try { db.dailySnapshotDao().getAvailableDates(5) } catch (_: Exception) { emptyList() }
        val targetDate = dates.filter { it <= today }.maxOrNull() ?: today

        // 3. 从日快照获取股价
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
     * 批量查询股票名称（从 stock_basics 缓存）
     */
    suspend fun getStockNames(codes: Collection<String>): Map<String, String> {
        val ctx = appContext ?: return emptyMap()
        val db = StockDatabase.getInstance(ctx)
        return try {
            db.stockBasicDao().getByCodes(codes.toList()).associate { it.code to it.name }
        } catch (_: Exception) { emptyMap() }
    }

    /**
     * 根据股票代码查询名称（简化版）
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
                    if (codes.isNotEmpty()) {
                        sectorStockCache[key] = codes
                        // 同时构建反向缓存：stockCode → sectors
                        for (code in codes) {
                            val existing = stockSectorCache[code] ?: emptyList()
                            if (key !in existing) {
                                stockSectorCache[code] = existing + key
                            }
                        }
                    }
                }
            }
            if (allKeys.size > 30) yield()
        } catch (_: Exception) {}
    }

    fun clearCache() { sectorStockCache.clear(); stockSectorCache.clear() }
    fun getStatus(): String = "数据: ${sectorStockCache.size}个板块, ${stockSectorCache.size}只股票已索引"

    // ═══════════════════════════════════════════════════════
    // v10.1: 用户搜索记录 API
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
            Log.i(TAG, "👤 用户搜索新股票: $stockName ($stockCode)")
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
     * 用户搜索加权分数（基于对数衰减 + 上限 10 分的阶梯式设计）
     *
     * 设计原理：
     * - 第1次搜索：+3 分（首次关注信号）
     * - 第2次搜索：+5 分（重复关注，信心提升）
     * - 第3次搜索：+7 分（持续关注，高分信号）
     * - 第4次搜索：+8 分
     * - 第5次+    ：+9~10 分区间趋近（边际递减，避免泛滥）
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
    // v10.0: Skill 精选股票池
    // ═══════════════════════════════════════════════════════

    fun addSkillPicks(picks: List<SkillPickEntry>) {
        if (picks.isEmpty()) return
        val current = skillPicks.toMutableList()
        val sourceIds = picks.map { it.sourceSkillId }.toSet()
        current.removeAll { it.sourceSkillId in sourceIds }
        current.addAll(0, picks)
        skillPicks = if (current.size > 50) current.take(50) else current.toList()
        Log.i(TAG, "📌 SkillPick 已存入: ${picks.size}只 (Skill: ${sourceIds.joinToString()}), 总计: ${skillPicks.size}只")
    }

    fun getRecentSkillPicks(limit: Int = 20): List<SkillPickEntry> = skillPicks.take(limit)

    fun getSkillPicksBySkillId(skillId: String): List<SkillPickEntry> {
        return skillPicks.filter { it.sourceSkillId == skillId }
    }

    /** v11.0: 取得 Skill/Agent 精选池中的所有股票代码（供模拟交易优先考虑） */
    fun getSkillPickStockCodes(): Set<String> {
        return skillPicks.map { it.stockCode }.toSet()
    }

    // ═══════════════════════════════════════════════════════
    // v13.0: 自选股票池（作为额外信号源，不加分不过滤）
    // ═══════════════════════════════════════════════════════

    /** 从 SharedPreferences 读取自选股票代码 */
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
     * Skill/Agent 精选股票加权分数（上限 10 分）
     *
     * 设计原理：
     * - 基于 AI 信心度（confidence）× 8 分
     * - 排名加分：rank=1 +2分, rank≤3 +1分
     * - 多次精选加分：每多一次 +0.5分（边际递减）
     * - 上限 10 分，确保用户搜索和智能体精选在同一尺度
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
    // 综合股票热度评分 (v12.0)
    // ═══════════════════════════════════════════════════════

    /**
     * 技术壁垒核心概念板块 — 科技类中具有高护城河的子行业
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
     * 计算一只股票的综合热度分数 (0-100 分)
     *
     * 五个维度：
     * 1. 交易量能 (30分) — 换手率 + 成交额占比 + 量比
     * 2. 资金动向 (20分) — 主力净流入 + 板块资金强度
     * 3. 板块历史热度 (20分) — 近60天板块上榜次数 + 综合评分
     * 4. 价格位置 (15分) — 涨跌幅历史分位 + 是否阶段新高
     * 5. 概念壁垒 (15分) — 是否核心科技概念 + 是否技术壁垒板块
     *
     * @param stockCode 股票代码 (如 sh600519)
     * @param todaySnapshots 当日全市场快照 (用于计算全市场统计)
     * @return 0-100 分，越高越热门
     */
    suspend fun calculateStockHeatScore(
        stockCode: String,
        todaySnapshots: List<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>
    ): Int {
        val ctx = appContext ?: return 25
        val db = StockDatabase.getInstance(ctx)
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        // 找当前股票的当日数据
        val selfSnap = todaySnapshots.find { it.code == stockCode } ?: return 15

        var score = 0

        // ════════════════════════════════════════════
        // 维度1: 交易量能 (0-30分)
        // ════════════════════════════════════════════

        // 1a. 换手率评分 (0-10分) — 越活跃越高，但极高会警示
        val turnoverRate = selfSnap.turnoverRate.coerceIn(0.0, 50.0)
        val turnoverScore = when {
            turnoverRate >= 8 -> 10  // 8%+ 极活跃
            turnoverRate >= 5 -> 8
            turnoverRate >= 3 -> 6
            turnoverRate >= 1 -> 3
            else -> 1
        }
        score += turnoverScore

        // 1b. 成交额占全市场排名 (0-10分)
        if (todaySnapshots.isNotEmpty()) {
            val totalAmount = todaySnapshots.sumOf { it.amount }
            if (totalAmount > 0) {
                val pct = selfSnap.amount / totalAmount
                val amountScore = when {
                    pct >= 0.03 -> 10 // 占全市场3%+
                    pct >= 0.01 -> 7
                    pct >= 0.005 -> 5
                    pct >= 0.001 -> 3
                    else -> 1
                }
                score += amountScore
            }
        }

        // 1c. 量比（近5日均量对比，0-10分）
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
        // 维度2: 资金动向 (0-20分)
        // ════════════════════════════════════════════

        // 2a. 所属板块的主力资金净流入
        try {
            val sectors = db.sectorStockDao().getSectorNamesByStockCode(stockCode)
            val allSectors = EastMoneyHotSectorSource.industrySectors + EastMoneyHotSectorSource.conceptSectors
            val matchedSectors = allSectors.filter { hs -> sectors.any { s -> hs.name.contains(s) || s.contains(hs.name) } }
            if (matchedSectors.isNotEmpty()) {
                val maxInflow = matchedSectors.maxOf { it.mainNetInflow }
                val avgComposite = matchedSectors.map { it.compositeScore }.average()
                val inflowScore = when {
                    maxInflow >= 10 -> 10  // 主力净流入 >= 10亿
                    maxInflow >= 5 -> 7
                    maxInflow >= 1 -> 5
                    maxInflow > 0 -> 3
                    else -> 0
                }
                val compositeScore = (avgComposite / 20.0).toInt().coerceIn(0, 10)
                score += inflowScore + compositeScore
            } else {
                score += 5  // 无板块归属，给基础分
            }
        } catch (_: Exception) { score += 5 }

        // ════════════════════════════════════════════
        // 维度3: 板块历史热度 (0-20分)
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
        // 维度4: 价格位置 (0-15分)
        // ════════════════════════════════════════════

        // 4a. 当前涨跌幅动能 (0-8分)
        val changePct = kotlin.math.abs(selfSnap.changePct)
        val changeScore = when {
            changePct >= 9.5 -> 8  // 涨停/跌停
            changePct >= 7 -> 6
            changePct >= 5 -> 5
            changePct >= 3 -> 3
            changePct >= 1 -> 1
            else -> 0
        }
        score += changeScore

        // 4b. 是否阶段新高（近30日最高收盘价）(0-7分)
        try {
            val recent30 = db.dailySnapshotDao().getAvailableDates(30)
                .filter { it <= today }.sorted().takeLast(30)
            val recentHighs = recent30.mapNotNull { date ->
                db.dailySnapshotDao().getByDate(date).find { it.code == stockCode }?.close
            }
            if (recentHighs.isNotEmpty()) {
                val maxClose = recentHighs.max()
                val isNewHigh = selfSnap.close >= maxClose * 0.98  // 2% 误差容忍
                score += if (isNewHigh) 7 else 3
            }
        } catch (_: Exception) { score += 3 }

        // ════════════════════════════════════════════
        // 维度5: 概念壁垒 (0-15分)
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