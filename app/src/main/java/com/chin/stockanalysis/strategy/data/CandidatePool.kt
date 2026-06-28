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
 * ## 備選池管理器 v2.0 — AI 驅動熱門板塊
 *
 * 核心設計：通過 AI 直接查詢年度/月度/周度/昨日熱門板塊，
 * 替代原來的 ETF 漲跌 + 東方財富 compositeScore 判斷方式。
 *
 * 備選池組成：
 * 1. 核心龍頭股（LeaderStockPool 的 81只）
 * 2. AI 查詢的年度熱門板塊龍頭
 * 3. AI 查詢的月度熱門板塊龍頭
 * 4. AI 查詢的周度熱門板塊龍頭
 * 5. AI 查詢的昨日熱門板塊龍頭
 *
 * 去重後總數控制在 100~200 只，主板為主（非科創非創業）
 */
object CandidatePool {

    private const val TAG = "CandidatePool"
    private const val PREFS_NAME = "candidate_pool_prefs"
    private const val KEY_POOL_CODES = "pool_codes"
    private const val KEY_LAST_UPDATE = "last_update_date"
    private const val KEY_HOT_SECTORS = "hot_sectors"

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 核心龍頭股（產業主線，排除概念板塊） */
    private fun getCoreLeaders(context: Context): Set<String> =
        LeaderStockPool.getMainlineCodes(context)

    // ════════════════════════════════════════
    // 數據模型
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
        val fundamentalScore: Double = 0.0  // 0~5 分基本面評分
    )

    data class PoolSnapshot(
        val stocks: List<CandidateStock>,
        val hotSectors: List<String>,
        val etfSectors: List<String>,  // 保持兼容，實際為 AI 熱門板塊
        val updateTime: String,
        val totalCount: Int
    )

    // ════════════════════════════════════════
    // 公共 API
    // ════════════════════════════════════════

    /**
     * 獲取當前備選池（優先從緩存，如過期則刷新）
     */
    suspend fun getPool(context: Context, forceRefresh: Boolean = false): PoolSnapshot = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUpdate = prefs.getString(KEY_LAST_UPDATE, "") ?: ""
        val today = LocalDate.now().format(DATE_FMT)

        if (!forceRefresh && lastUpdate == today) {
            // 今天已更新，從緩存讀取
            val cachedCodes = prefs.getStringSet(KEY_POOL_CODES, emptySet()) ?: emptySet()
            val hotSectors = prefs.getStringSet(KEY_HOT_SECTORS, emptySet())?.toList() ?: emptyList()
            Log.i(TAG, "📦 從緩存讀取備選池: ${cachedCodes.size}只")
            return@withContext buildSnapshotFromCodes(context, cachedCodes, hotSectors, today)
        }

        // 需要刷新
        refreshPool(context)
    }

    /**
     * 強制刷新備選池（AI 查詢熱門板塊 → 展開子版塊 → 取龍頭股）
     */
    suspend fun refreshPool(context: Context): PoolSnapshot = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔄 開始刷新備選池 (AI 驅動)...")
        val today = LocalDate.now().format(DATE_FMT)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val pool = mutableSetOf<String>()
        val allSectorNames = mutableListOf<String>()

        // 1. 加入核心龍頭股
        val coreLeaders = getCoreLeaders(context)
        pool.addAll(coreLeaders)
        Log.i(TAG, "✅ 核心龍頭: ${coreLeaders.size}只")

        // 2. 🤖 AI 查詢熱門板塊（年度/月度/周度/昨日）
        val hotSectors = try {
            val result = AIHotSectorProvider.getHotSectors(context)
            Log.i(TAG, "🤖 AI 熱門板塊: 年度${result.annualSectors.size}個, 月度${result.monthlySectors.size}個, 周度${result.weeklySectors.size}個, 昨日${result.yesterdaySectors.size}個")
            allSectorNames.addAll(result.allSectors)
            result
        } catch (e: Exception) {
            Log.w(TAG, "AI 熱門板塊查詢失敗，使用備用列表: ${e.message}")
            val fallback = AIHotSectorProvider.getDefaultHotSectors()
            allSectorNames.addAll(fallback.allSectors)
            fallback
        }

        // 3. 🏗️ 從 SectorSubDivision.ALL_SECTORS 獲取板塊股票 → 按主板/科創/創業分組各取 5 只
        var addedFromSectors = 0
        val db = StockDatabase.getInstance(context)
        for (sectorName in hotSectors.allSectors) {
            try {
                // 方案 A: 直接從 SectorSubDivision.ALL_SECTORS 獲取該板塊的硬編碼股票列表
                val subSectorList = SectorSubDivision.ALL_SECTORS[sectorName]
                val codes = if (!subSectorList.isNullOrEmpty()) {
                    val allStocks = subSectorList.flatMap { it.stocks.map { stock -> stock.code } }
                    Log.d(TAG, "  板塊 [$sectorName] → 從 SectorSubDivision 獲取 ${allStocks.size} 只")
                    allStocks
                } else {
                    // Fallback: 展開子板塊 → 從 DB 查詢
                    val subSectors = SectorSubDivision.getSubSectors(sectorName)
                    Log.d(TAG, "  板塊 [$sectorName] → ${subSectors.size} 個子板塊 (DB fallback)")
                    subSectors.flatMap { sub ->
                        db.sectorStockDao().getStockCodesBySector(sub.name)
                    }.distinct()
                }

                if (codes.isEmpty()) {
                    Log.d(TAG, "  板塊 [$sectorName] 無股票數據")
                    continue
                }

                // 按 board 類型分組，各取 5 只
                val mainBoard = mutableListOf<String>()
                val starBoard = mutableListOf<String>()  // 科創板 688
                val gemBoard = mutableListOf<String>()   // 創業板 300/301

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
                    Log.d(TAG, "  板塊 [$sectorName] 選中 ${picked.size} 只 (主${mainBoard.take(5).size}/科${starBoard.take(5).size}/創${gemBoard.take(5).size})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "  板塊 [$sectorName] 處理失敗: ${e.message}")
            }
        }
        Log.i(TAG, "✅ AI熱門板塊龍頭: 新增${addedFromSectors}只, 總池${pool.size}只")

        // 4. 保存到緩存
        prefs.edit().apply {
            putStringSet(KEY_POOL_CODES, pool)
            putString(KEY_LAST_UPDATE, today)
            putStringSet(KEY_HOT_SECTORS, allSectorNames.toSet())
            apply()
        }

        Log.i(TAG, "✅ 備選池刷新完成: ${pool.size}只 (核心${coreLeaders.size} + AI動態${pool.size - coreLeaders.size})")
        buildSnapshotFromCodes(context, pool, allSectorNames, today)
    }

    /**
     * 獲取備選池股票代碼列表（用於策略掃描）
     */
    suspend fun getPoolCodes(context: Context): List<String> = withContext(Dispatchers.IO) {
        getPool(context).stocks.map { it.code }
    }

    /**
     * 獲取熱門板塊列表
     */
    suspend fun getHotSectors(context: Context): List<String> = withContext(Dispatchers.IO) {
        getPool(context).hotSectors
    }

    /**
     * 檢查是否需要更新（跨天或強制刷新）
     */
    fun needsUpdate(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUpdate = prefs.getString(KEY_LAST_UPDATE, "") ?: ""
        return lastUpdate != LocalDate.now().format(DATE_FMT)
    }

    // ════════════════════════════════════════
    // 內部方法
    // ════════════════════════════════════════

    private suspend fun buildSnapshotFromCodes(
        context: Context,
        codes: Set<String>,
        hotSectors: List<String>,
        date: String
    ): PoolSnapshot = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        val coreLeaders = getCoreLeaders(context)

        // 從 stock_basics 獲取名稱映射
        val nameMap = try {
            db.stockBasicDao().getAll().associate { it.code to it.name }
        } catch (_: Exception) { emptyMap() }

        // 獲取最近可用交易日（今天沒數據則回退到最近交易日）
        val today = LocalDate.now().toString()
        val availableDates = try { db.dailySnapshotDao().getAvailableDates(5) } catch (_: Exception) { emptyList() }
        val targetDate = availableDates.filter { it <= today }.maxOrNull() ?: date

        // 從日快照獲取行情數據
        val snaps = try { db.dailySnapshotDao().getByDate(targetDate) } catch (_: Exception) { emptyList() }
        val snapMap = snaps.associateBy { it.code }

        // 獲取 stock_basics 用於 ST 過濾
        val basicsMap = try {
            db.stockBasicDao().getAll().associateBy { it.code }
        } catch (_: Exception) { emptyMap() }

        val stocks = mutableListOf<CandidateStock>()
        var filteredST = 0

        for (code in codes) {
            val snap = snapMap[code]
            val name = snap?.name ?: nameMap[code] ?: code
            val basic = basicsMap[code]

            // ── ST / 退市 過濾 ──
            if (name.contains("ST", ignoreCase = true) || name.contains("退", ignoreCase = true)) {
                filteredST++
                continue
            }

            // 查找所屬板塊（從持久化配置 + DB 查詢）
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
            Log.i(TAG, "🧹 過濾: ST/退市 ${filteredST}只")
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
        // 2. 從 DB 查（AI 動態板塊新增的股票）
        try {
            val sectorNames = db.sectorStockDao().getSectorNamesByStockCode(code)
            if (sectorNames.isNotEmpty()) {
                // 取第一個非 "其他" 的板塊名
                val mainSector = sectorNames.firstOrNull { it != "其他" && it.isNotBlank() } ?: sectorNames.first()
                // 如果有子板塊（第2級），作為 subSector
                val subSector = sectorNames.getOrNull(1)?.takeIf { it.isNotBlank() && it != "其他" } ?: ""
                return mainSector to subSector
            }
        } catch (_: Exception) {}
        return "其他" to ""
    }

    /**
     * 判斷是否為主板股票（非科創非創業）
     */
    private fun isMainBoard(code: String): Boolean {
        return code.startsWith("sh6") || code.startsWith("sz0") || code.startsWith("sz2")
    }

    /**
     * 標準化股票代碼（確保格式一致）
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