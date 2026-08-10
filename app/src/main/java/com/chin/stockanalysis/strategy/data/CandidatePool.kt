package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import com.chin.stockanalysis.stock.data.sources.SectorSubDivision
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 备选池管理器 v2.0 — AI 驱动热门板块
 *
 * 核心设计：通过 AI 直接查询年度/月度/周度/昨日热门板块，
 * 替代原来的 ETF 涨跌 + 东方财富 compositeScore 判断方式。
 *
 * 备选池组成：
 * 1. 核心龙头股（LeaderStockPool 的 81只）
 * 2. AI 查询的年度热门板块龙头
 * 3. AI 查询的月度热门板块龙头
 * 4. AI 查询的周度热门板块龙头
 * 5. AI 查询的昨日热门板块龙头
 *
 * 去重后总数控制在 100~200 只，主板为主（非科创非创业）
 */
object CandidatePool {

    private const val TAG = "CandidatePool"
    private const val PREFS_NAME = "candidate_pool_prefs"
    private const val KEY_POOL_CODES = "pool_codes"
    private const val KEY_LAST_UPDATE = "last_update_date"
    private const val KEY_HOT_SECTORS = "hot_sectors"

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 核心龙头股（产业主线，排除概念板块） */
    private fun getCoreLeaders(context: Context): Set<String> =
        LeaderStockPool.getMainlineCodes(context)

    // ════════════════════════════════════════
    // 数据模型
    // ════════════════════════════════════════

    data class CandidateStock(
        val code: String,
        val name: String,
        val sector: String,
        val subSector: String,
        val source: String,      // "core" / "ai"
        val rankInSector: Int,
        val changePct: Double = 0.0,
        val marketCap: Double = 0.0,
        val peRatio: Double = 0.0,
        val isST: Boolean = false,
        val fundamentalScore: Double = 0.0  // 0~5 分基本面评分
    )

    data class PoolSnapshot(
        val stocks: List<CandidateStock>,
        val hotSectors: List<String>,
        val etfSectors: List<String>,  // 保持兼容，实际为 AI 热门板块
        val updateTime: String,
        val totalCount: Int
    )

    // ════════════════════════════════════════
    // 公共 API
    // ════════════════════════════════════════

    /**
     * 获取当前备选池（优先从缓存，如过期则刷新）
     */
    suspend fun getPool(context: Context, forceRefresh: Boolean = false): PoolSnapshot = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUpdate = prefs.getString(KEY_LAST_UPDATE, "") ?: ""
        val today = LocalDate.now().format(DATE_FMT)

        if (!forceRefresh && lastUpdate == today) {
            // 今天已更新，从缓存读取
            val cachedCodes = prefs.getStringSet(KEY_POOL_CODES, emptySet()) ?: emptySet()
            val hotSectors = prefs.getStringSet(KEY_HOT_SECTORS, emptySet())?.toList() ?: emptyList()
            Log.i(TAG, "📦 从缓存读取备选池: ${cachedCodes.size}只")
            return@withContext buildSnapshotFromCodes(context, cachedCodes, hotSectors, today)
        }

        // 需要刷新
        refreshPool(context)
    }

    /**
     * 强制刷新备选池（AI 查询热门板块 → 展开子版块 → 取龙头股）
     */
    suspend fun refreshPool(context: Context): PoolSnapshot = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔄 开始刷新备选池 (AI 驱动)...")
        val today = LocalDate.now().format(DATE_FMT)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val pool = mutableSetOf<String>()
        val allSectorNames = mutableListOf<String>()

        // 1. 加入核心龙头股
        val coreLeaders = getCoreLeaders(context)
        pool.addAll(coreLeaders)
        Log.i(TAG, "✅ 核心龙头: ${coreLeaders.size}只")

        // 2. 🤖 AI 查询热门板块（年度/月度/周度/昨日）
        val hotSectors = try {
            val result = AIHotSectorProvider.getHotSectors(context)
            Log.i(TAG, "🤖 AI 热门板块: 年度${result.annualSectors.size}个, 月度${result.monthlySectors.size}个, 周度${result.weeklySectors.size}个, 昨日${result.yesterdaySectors.size}个")
            allSectorNames.addAll(result.allSectors)
            result
        } catch (e: Exception) {
            Log.w(TAG, "AI 热门板块查询失败，使用备用列表: ${e.message}")
            val fallback = AIHotSectorProvider.getDefaultHotSectors(context)
            allSectorNames.addAll(fallback.allSectors)
            fallback
        }

        // 3. 🏗️ 从 SectorSubDivision.ALL_SECTORS 获取板块股票 → 按主板/科创/创业分组各取 5 只
        var addedFromSectors = 0
        val db = StockDatabase.getInstance(context)
        for (sectorName in hotSectors.allSectors) {
            try {
                // 方案 A: 直接从 SectorSubDivision.ALL_SECTORS 获取该板块的硬编码股票列表
                val subSectorList = SectorSubDivision.ALL_SECTORS[sectorName]
                val codes = if (!subSectorList.isNullOrEmpty()) {
                    val allStocks = subSectorList.flatMap { it.stocks.map { stock -> stock.code } }
                    Log.d(TAG, "  板块 [$sectorName] → 从 SectorSubDivision 获取 ${allStocks.size} 只")
                    allStocks
                } else {
                    // Fallback: 展开子板块 → 从 DB 查询
                    val subSectors = SectorSubDivision.getSubSectors(sectorName)
                    Log.d(TAG, "  板块 [$sectorName] → ${subSectors.size} 个子板块 (DB fallback)")
                    subSectors.flatMap { sub ->
                        db.sectorStockDao().getStockCodesBySector(sub.name)
                    }.distinct()
                }

                if (codes.isEmpty()) {
                    Log.d(TAG, "  板块 [$sectorName] 无股票数据")
                    continue
                }

                // 按 board 类型分组，各取 5 只
                val mainBoard = mutableListOf<String>()
                val starBoard = mutableListOf<String>()  // 科创板 688
                val gemBoard = mutableListOf<String>()   // 创业板 300/301

                for (code in codes) {
                    when {
                        code.startsWith("sh688") -> starBoard.add(code)
                        code.startsWith("sz300") || code.startsWith("sz301") -> gemBoard.add(code)
                        else -> mainBoard.add(code)
                    }
                }

                val picked = (mainBoard.take(5) + starBoard.take(5) + gemBoard.take(5)).toSet()
                for (code in picked) {
                    if (pool.add(code)) addedFromSectors++
                }

                if (picked.isNotEmpty()) {
                    Log.d(TAG, "  板块 [$sectorName] 选中 ${picked.size} 只 (主${mainBoard.take(5).size}/科${starBoard.take(5).size}/创${gemBoard.take(5).size})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "  板块 [$sectorName] 处理失败: ${e.message}")
            }
        }
        Log.i(TAG, "✅ AI热门板块龙头: 新增${addedFromSectors}只, 总池${pool.size}只")

        // 4. 保存到缓存
        prefs.edit().apply {
            putStringSet(KEY_POOL_CODES, pool)
            putString(KEY_LAST_UPDATE, today)
            putStringSet(KEY_HOT_SECTORS, allSectorNames.toSet())
            apply()
        }

        Log.i(TAG, "✅ 备选池刷新完成: ${pool.size}只 (核心${coreLeaders.size} + AI动态${pool.size - coreLeaders.size})")
        buildSnapshotFromCodes(context, pool, allSectorNames, today)
    }

    /**
     * 获取备选池股票代码列表（用于策略扫描）
     */
    suspend fun getPoolCodes(context: Context): List<String> = withContext(Dispatchers.IO) {
        getPool(context).stocks.map { it.code }
    }

    /**
     * 获取热门板块列表
     */
    suspend fun getHotSectors(context: Context): List<String> = withContext(Dispatchers.IO) {
        getPool(context).hotSectors
    }

    /**
     * 检查是否需要更新（跨天或强制刷新）
     */
    fun needsUpdate(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUpdate = prefs.getString(KEY_LAST_UPDATE, "") ?: ""
        return lastUpdate != LocalDate.now().format(DATE_FMT)
    }

    // ════════════════════════════════════════
    // 内部方法
    // ════════════════════════════════════════

    private suspend fun buildSnapshotFromCodes(
        context: Context,
        codes: Set<String>,
        hotSectors: List<String>,
        date: String
    ): PoolSnapshot = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        val coreLeaders = getCoreLeaders(context)

        // 从 stock_basics 获取名称映射
        val nameMap = try {
            db.stockBasicDao().getAll().associate { it.code to it.name }
        } catch (_: Exception) { emptyMap() }

        // 获取最近可用交易日（今天没数据则回退到最近交易日）
        val today = LocalDate.now().toString()
        val availableDates = try { db.dailySnapshotDao().getAvailableDates(5) } catch (_: Exception) { emptyList() }
        val targetDate = availableDates.filter { it <= today }.maxOrNull() ?: date

        // 从日快照获取行情数据
        val snaps = try { db.dailySnapshotDao().getByDate(targetDate) } catch (_: Exception) { emptyList() }
        val snapMap = snaps.associateBy { it.code }

        // 获取 stock_basics 用于 ST 过滤
        val basicsMap = try {
            db.stockBasicDao().getAll().associateBy { it.code }
        } catch (_: Exception) { emptyMap() }

        val stocks = mutableListOf<CandidateStock>()
        var filteredST = 0

        for (code in codes) {
            val snap = snapMap[code]
            val name = snap?.name ?: nameMap[code] ?: code
            val basic = basicsMap[code]

            // ── ST / 退市 过滤 ──
            if (name.contains("ST", ignoreCase = true) || name.contains("退", ignoreCase = true)) {
                filteredST++
                continue
            }

            // 查找所属板块（从持久化配置 + DB 查询）
            val (sector, subSector) = findSectorForCode(context, db, code)
            stocks.add(CandidateStock(
                code = code,
                name = name,
                sector = sector,
                subSector = subSector,
                source = if (code in coreLeaders) "core" else "ai",
                rankInSector = 0,
                changePct = snap?.changePct ?: 0.0,
                marketCap = 0.0,
                peRatio = 0.0,
                isST = false,
                fundamentalScore = 0.0
            ))
        }

        if (filteredST > 0) {
            Log.i(TAG, "🧹 过滤: ST/退市 ${filteredST}只")
        }

        PoolSnapshot(
            stocks = stocks.sortedByDescending { it.changePct },
            hotSectors = hotSectors,
            etfSectors = hotSectors,
            updateTime = targetDate,
            totalCount = stocks.size
        )
    }

    private suspend fun findSectorForCode(context: Context, db: StockDatabase, code: String): Pair<String, String> {
        // 1. 先查持久化配置（LeaderStockPool）
        for (cfg in LeaderStockPool.getAllConfigs(context)) {
            for (ss in cfg.subSectors) {
                if (code in ss.stocks) {
                    return cfg.name to ss.name
                }
            }
        }
        // 2. 从 DB 查（AI 动态板块新增的股票）
        try {
            val sectorNames = db.sectorStockDao().getSectorNamesByStockCode(code)
            if (sectorNames.isNotEmpty()) {
                // 取第一个非 "其他" 的板块名
                val mainSector = sectorNames.firstOrNull { it != "其他" && it.isNotBlank() } ?: sectorNames.first()
                // 如果有子板块（第2级），作为 subSector
                val subSector = sectorNames.getOrNull(1)?.takeIf { it.isNotBlank() && it != "其他" } ?: ""
                return mainSector to subSector
            }
        } catch (_: Exception) {}
        return "其他" to ""
    }

    /**
     * 判断是否为主板股票（非科创非创业）
     */
    private fun isMainBoard(code: String): Boolean {
        return code.startsWith("sh6") || code.startsWith("sz0") || code.startsWith("sz2")
    }

    /**
     * 标准化股票代码（确保格式一致）
     */
    private fun normalizeCode(code: String): String {
        val c = code.trim().lowercase()
        return when {
            c.startsWith("6") -> "sh$c"
            c.startsWith("0") || c.startsWith("2") || c.startsWith("3") -> "sz$c"
            c.startsWith("sh") || c.startsWith("sz") -> c
            else -> c
        }
    }
}