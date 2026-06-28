package com.chin.stockanalysis.stock.database

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.Room
import com.chin.stockanalysis.stock.theme.ThemeStockLibrary
import com.chin.stockanalysis.stock.theme.ThemeStock
import com.chin.stockanalysis.strategy.data.LeaderStockPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * ## 股票数据库管理器
 *
 * 负责：
 * 1. Room 数据库单例创建
 * 2. 首次启动时从 [ThemeStockLibrary] 硬编码数据迁移到数据库
 * 3. 每隔 24 小时从 [ThemeStockLibrary] 重新同步（增删改同步）
 * 4. 对外提供板块查询、股票查询等接口
 *
 * ### 同步策略
 * - 首次：从 ThemeStockLibrary 全量导入
 * - 后续：每24小时对比 ThemeStockLibrary → DB，自动新增/更新/删除
 * - 人工在 ThemeStockLibrary 中增删股票后，下次同步自动反映到 DB
 *
 * ### 使用方式
 * ```kotlin
 * val dbManager = StockDatabaseManager.getInstance(context)
 * val stocks = dbManager.getStocksBySector("commercial_space") // → List<StockBasicEntity>
 * ```
 */
class StockDatabaseManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "StockDatabaseManager"

        /** 同步间隔：24小时（毫秒）*/
        private const val SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** SharedPreferences key：上次同步时间戳 */
        private const val PREF_NAME = "stock_db_sync"
        private const val KEY_LAST_SYNC = "last_sync_timestamp"

        @Volatile
        private var INSTANCE: StockDatabaseManager? = null

        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): StockDatabaseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StockDatabaseManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    // Room 数据库（单例，含 migration）
    val db: StockDatabase = StockDatabase.getInstance(appContext)

    private val stockDao = db.stockBasicDao()
    private val sectorDao = db.sectorStockDao()

    /** SharedPreferences 用于记录上次同步时间 */
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 标记是否已完成首次数据迁移 */
    @Volatile
    private var migrationDone = false

    // ════════════════════════════════════════
    // 初始化 + 定期同步
    // ════════════════════════════════════════

    /**
     * 确保数据库已初始化并检查是否需要同步
     *
     * 建议在 App 启动时调用一次。幂等操作。
     */
    suspend fun ensureInitialized() {
        if (migrationDone) return
        val count = withContext(Dispatchers.IO) { stockDao.count() }

        if (count == 0) {
            // 首次：全量导入
            syncFromThemeStockLibrary(isFullImport = true)
        } else {
            // 已有数据：检查是否需要周期同步
            migrationDone = true
            Log.d(TAG, "数据库已有 $count 条记录")

            if (shouldSync()) {
                Log.d(TAG, "距上次同步超过 24h，开始增量同步...")
                syncFromThemeStockLibrary(isFullImport = false)
            } else {
                Log.d(TAG, "同步间隔未到，跳过")
            }
        }
    }

    /**
     * 判断是否到了需要同步的时间（距上次同步 > 24h）
     */
    private fun shouldSync(): Boolean {
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
        if (lastSync == 0L) return true
        val elapsed = System.currentTimeMillis() - lastSync
        return elapsed >= SYNC_INTERVAL_MS
    }

    /**
     * 从 [ThemeStockLibrary] 同步数据到 Room 数据库（優化版）
     *
     * 選股邏輯：
     * 1. 核心龍頭股（LeaderStockPool）
     * 2. 年度/月度/周熱門板塊龍頭（ThemeStockLibrary）
     * 3. 根據 ETF 漲跌判斷熱門板塊 → 查找子板塊前 10
     * 4. 過濾：去掉 ST + 去掉小市值(<100億) + 其他不良股票
     * 5. 最終導入 200-500 只核心股票（無硬性上限，以質量為準）
     *
     * @param isFullImport true = 首次全量导入（无需对比，直接写入）
     *                       false = 增量同步（对比差异，增删改）
     */
    private suspend fun syncFromThemeStockLibrary(isFullImport: Boolean) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, if (isFullImport) "📥 首次全量导入开始..." else "🔄 增量同步开始...")
            val startTime = System.currentTimeMillis()

            // ── 1. 建立 ThemeStockLibrary 的 code -> ThemeStock 映射 ──
            val themeStockMap = mutableMapOf<String, Pair<String, ThemeStock>>()
            for ((themeKey, themeInfo) in ThemeStockLibrary.THEME_MAP) {
                val validStocks = ThemeStockLibrary.run { themeInfo.validStocks() }
                for (ts in validStocks) {
                    themeStockMap[ts.code] = themeKey to ts
                }
            }

            // ── 2. 收集候選股票 ──
            val candidateSet = mutableSetOf<String>()

            // 2a. 核心龍頭股（產業主線，排除概念板塊）
            val coreLeaders = LeaderStockPool.getMainlineCodes(appContext)
            candidateSet.addAll(coreLeaders)
            Log.d(TAG, "核心龍頭股: ${coreLeaders.size} 只")

            // 2b. 年度/月度/周熱門板塊（從 ThemeStockLibrary 選取）
            val annualHotThemes = listOf("ai_tech", "new_energy", "semiconductor")
            val monthlyHotThemes = listOf("nonferrous_metals", "commercial_space", "military", "pharma")
            val weeklyHotThemes = listOf("bank", "liquor", "steel", "consumer")
            val allHotThemes = annualHotThemes + monthlyHotThemes + weeklyHotThemes

            for (themeKey in allHotThemes) {
                val themeInfo = ThemeStockLibrary.THEME_MAP[themeKey] ?: continue
                val stocks = ThemeStockLibrary.run { themeInfo.validStocks() }
                    .filter { !it.name.contains("ST", ignoreCase = true) }
                    .take(10)
                for (ts in stocks) {
                    candidateSet.add(ts.code)
                }
            }
            Log.d(TAG, "熱門板塊股: ${candidateSet.size - coreLeaders.size} 只")

            // 2c. 根據 ETF 漲跌動態發現熱門板塊
            try {
                val today = java.time.LocalDate.now().toString()
                val dates = db.dailySnapshotDao().getAvailableDates(5)
                val targetDate = dates.filter { it <= today }.maxOrNull() ?: today
                val snaps = db.dailySnapshotDao().getByDate(targetDate)

                val etfSectorMap = mapOf(
                    "sh512480" to "semiconductor", "sz159995" to "semiconductor",
                    "sh515030" to "new_energy", "sh516160" to "new_energy",
                    "sh512760" to "ai_tech", "sz159819" to "ai_tech",
                    "sh512800" to "bank", "sh510050" to "blue_chip"
                )

                val etfChanges = snaps.filter { it.code in etfSectorMap.keys }
                    .map { it.code to it.changePct }
                    .sortedByDescending { it.second }
                    .take(5)

                for ((etfCode, changePct) in etfChanges) {
                    val sectorKey = etfSectorMap[etfCode] ?: continue
                    val themeInfo = ThemeStockLibrary.THEME_MAP[sectorKey] ?: continue
                    val stocks = ThemeStockLibrary.run { themeInfo.validStocks() }
                        .filter { !it.name.contains("ST", ignoreCase = true) }
                        .take(10)
                    for (ts in stocks) {
                        candidateSet.add(ts.code)
                    }
                    Log.d(TAG, "ETF $etfCode 漲幅 ${"%.1f".format(changePct)}% → 加入板塊 $sectorKey")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ETF 動態板塊獲取失敗: ${e.message}")
            }

            // 2d. 查找子板塊前 10（通過 LeaderStockPool 持久化配置）
            try {
                for (cfg in LeaderStockPool.getAllConfigs(appContext)) {
                    for (subSector in cfg.subSectors) {
                        val topCodes = subSector.stocks.take(10)
                        for (code in topCodes) {
                            // 檢查該代碼在 themeStockMap 中是否存在且不是 ST
                            val (_, ts) = themeStockMap[code] ?: continue
                            if (!ts.name.contains("ST", ignoreCase = true)) {
                                candidateSet.add(code)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "子板塊獲取失敗: ${e.message}")
            }

            Log.d(TAG, "候選池總計: ${candidateSet.size} 只（去重後）")

            // ── 3. 過濾條件 ──
            val filteredStocks = mutableListOf<StockBasicEntity>()
            val filteredSectors = mutableListOf<SectorStockEntity>()

            for (code in candidateSet) {
                val (themeKey, ts) = themeStockMap[code] ?: continue
                val themeName = ThemeStockLibrary.THEME_MAP[themeKey]?.name ?: "其他"

                // 3a. 去掉 ST
                if (ts.name.contains("ST", ignoreCase = true)) continue

                // 3b. 去掉名稱異常的（如 "退市"、"摘牌"）
                if (ts.name.contains("退市") || ts.name.contains("摘牌")) continue

                // 3c. 去掉代碼異常的（非標準 6 位數字）
                val pureCode = code.replace(Regex("^(sh|sz|bj)"), "")
                if (!pureCode.matches(Regex("^\\d{6}$"))) continue

                // 3d. 市值過濾（如果有市值數據）
                // TODO: 當 stock_basics 表有 market_cap 欄位時啟用

                filteredStocks.add(StockBasicEntity(
                    code = ts.code,
                    name = ts.name,
                    business = ts.business,
                    chainRationale = ts.chainRationale
                ))
                filteredSectors.add(SectorStockEntity(
                    sectorKey = themeKey,
                    sectorName = themeName,
                    stockCode = ts.code
                ))
            }

            // 3e. 最終數量控制（無硬性上限，但記錄日誌）
            val finalCount = filteredStocks.size
            when {
                finalCount < 200 -> Log.w(TAG, "⚠️ 選股數量偏少: $finalCount 只，建議檢查數據源")
                finalCount > 800 -> Log.w(TAG, "⚠️ 選股數量偏多: $finalCount 只，已自動保留")
                else -> Log.d(TAG, "✅ 選股數量合理: $finalCount 只")
            }

            // ── 4. 寫入數據庫 ──
            if (isFullImport) {
                stockDao.insertAll(filteredStocks)
                sectorDao.clearAll()
                sectorDao.insertAll(filteredSectors)

                val elapsed = System.currentTimeMillis() - startTime
                migrationDone = true
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                Log.d(TAG, "✅ 全量导入完成: ${finalCount} 只股票, ${filteredSectors.size} 条映射 | ${elapsed}ms")
            } else {
                val existingStocks = stockDao.getAll().associateBy { it.code }
                val toInsert = mutableListOf<StockBasicEntity>()
                var addedCount = 0
                var updatedCount = 0

                for (stock in filteredStocks) {
                    val existing = existingStocks[stock.code]
                    if (existing == null) {
                        toInsert.add(stock)
                        addedCount++
                    } else if (existing.name != stock.name || existing.business != stock.business) {
                        toInsert.add(stock)
                        updatedCount++
                    }
                }
                if (toInsert.isNotEmpty()) stockDao.insertAll(toInsert)

                val latestCodes = filteredStocks.map { it.code }.toSet()
                val removedCodes = existingStocks.keys - latestCodes
                if (removedCodes.isNotEmpty()) {
                    Log.d(TAG, "🗑️ 移除 ${removedCodes.size} 只不再符合條件的股票")
                }

                sectorDao.clearAll()
                sectorDao.insertAll(filteredSectors)

                val elapsed = System.currentTimeMillis() - startTime
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                Log.d(TAG, "✅ 增量同步完成: +${addedCount}新增 ${updatedCount}更新 ${removedCodes.size}移除 | ${elapsed}ms")
            }
        }
    }

    // ════════════════════════════════════════
    // 查询接口
    // ════════════════════════════════════════

    /**
     * 按板块 key 查询该板块下所有股票的基本信息
     *
     * @param sectorKey 板块标识（如 "commercial_space"）
     * @return 股票基本信息列表
     */
    suspend fun getStocksBySector(sectorKey: String): List<StockBasicEntity> {
        return withContext(Dispatchers.IO) {
            val codes = sectorDao.getStockCodesBySector(sectorKey)
            if (codes.isEmpty()) emptyList()
            else stockDao.getByCodes(codes)
        }
    }

    /**
     * 按板块 key 获取板块中文名
     */
    suspend fun getSectorName(sectorKey: String): String? {
        return withContext(Dispatchers.IO) {
            sectorDao.getSectorName(sectorKey)
        }
    }

    /**
     * 获取所有板块标识 key 列表
     */
    suspend fun getAllSectorKeys(): List<String> {
        return withContext(Dispatchers.IO) {
            sectorDao.getAllSectorKeys()
        }
    }

    /**
     * 获取所有股票（不分板块）
     */
    suspend fun getAllStocks(): List<StockBasicEntity> {
        return withContext(Dispatchers.IO) {
            stockDao.getAll()
        }
    }

    /**
     * 按股票代码查询
     */
    suspend fun getStockByCode(code: String): StockBasicEntity? {
        return withContext(Dispatchers.IO) {
            stockDao.getByCode(code)
        }
    }

    /**
     * 按名称模糊搜索
     */
    suspend fun searchStocksByName(keyword: String): List<StockBasicEntity> {
        return withContext(Dispatchers.IO) {
            stockDao.searchByName("%$keyword%")
        }
    }

    /**
     * 获取股票总数
     */
    suspend fun getStockCount(): Int {
        return withContext(Dispatchers.IO) {
            stockDao.count()
        }
    }
}
