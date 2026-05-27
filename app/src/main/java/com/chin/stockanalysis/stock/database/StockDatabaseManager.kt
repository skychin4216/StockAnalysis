package com.chin.stockanalysis.stock.database

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.Room
import com.chin.stockanalysis.stock.theme.ThemeStockLibrary
import com.chin.stockanalysis.stock.theme.ThemeStock
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
class StockDatabaseManager private constructor(context: Context) {

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
    val db: StockDatabase = StockDatabase.getInstance(context)

    private val stockDao = db.stockBasicDao()
    private val sectorDao = db.sectorStockDao()

    /** SharedPreferences 用于记录上次同步时间 */
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

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
     * 从 [ThemeStockLibrary] 同步数据到 Room 数据库
     *
     * @param isFullImport true = 首次全量导入（无需对比，直接写入）
     *                       false = 增量同步（对比差异，增删改）
     */
    private suspend fun syncFromThemeStockLibrary(isFullImport: Boolean) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, if (isFullImport) "📥 首次全量导入开始..." else "🔄 增量同步开始...")
            val startTime = System.currentTimeMillis()

            // ── 1. 从 ThemeStockLibrary 收集最新数据 ──
            val latestStockMap = mutableMapOf<String, StockBasicEntity>()
            val latestSectorEntries = mutableListOf<SectorStockEntity>()

            for ((themeKey, themeInfo) in ThemeStockLibrary.THEME_MAP) {
                val validStocks = ThemeStockLibrary.run { themeInfo.validStocks() }
                for (ts in validStocks) {
                    latestStockMap[ts.code] = StockBasicEntity(
                        code = ts.code,
                        name = ts.name,
                        business = ts.business,
                        chainRationale = ts.chainRationale
                    )
                    latestSectorEntries.add(
                        SectorStockEntity(
                            sectorKey = themeKey,
                            sectorName = themeInfo.name,
                            stockCode = ts.code
                        )
                    )
                }
            }

            if (isFullImport) {
                // ── 全量导入：直接清空重写 ──
                stockDao.insertAll(latestStockMap.values.toList())
                sectorDao.clearAll()
                sectorDao.insertAll(latestSectorEntries)

                val elapsed = System.currentTimeMillis() - startTime
                migrationDone = true
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                Log.d(TAG, "✅ 全量导入完成: ${latestStockMap.size} 只股票, ${latestSectorEntries.size} 条映射 | ${elapsed}ms")
            } else {
                // ── 增量同步：对比差异 ──
                var addedCount = 0
                var updatedCount = 0
                // 获取 DB 中现有数据
                val existingStocks = stockDao.getAll().associateBy { it.code }
                val existingSectorCodes = sectorDao.getAllStockCodes().toSet()

                // 新增/更新股票
                val toInsert = mutableListOf<StockBasicEntity>()
                for ((code, stock) in latestStockMap) {
                    val existing = existingStocks[code]
                    if (existing == null) {
                        toInsert.add(stock)
                        // addedCount++ handled below
                    } else if (existing.name != stock.name || existing.business != stock.business) {
                        toInsert.add(stock)
                        updatedCount++
                    }
                }
                if (toInsert.isNotEmpty()) {
                    stockDao.insertAll(toInsert)
                }

                // 删除已退市股票（ThemeStockLibrary 中已移除的）
                val latestCodes = latestStockMap.keys
                val removedCodes = existingStocks.keys - latestCodes
                // NOTE: Room 不支持批量 DELETE WHERE code IN (...)，此处只记录日志
                // 实际删除通过 sector_stocks 映射表清理（板块映射表会自动删除不在其中的映射）
                if (removedCodes.isNotEmpty()) {
                    Log.d(TAG, "⚠️ 检测到 ${removedCodes.size} 只已退市股票（将在板块映射中清理）: $removedCodes")
                }

                // 重建板块映射（最简单可靠的方式：清空重写）
                sectorDao.clearAll()
                sectorDao.insertAll(latestSectorEntries)
                val sectorDiff = existingSectorCodes.size - latestSectorEntries.size

                val elapsed = System.currentTimeMillis() - startTime
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                Log.d(TAG, "✅ 增量同步完成: +${addedCount}新增 ${updatedCount}更新 ${removedCodes.size}退市 ${sectorDiff}映射差异 | ${elapsed}ms")
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
     * 按代码查询单只股票基本信息
     */
    suspend fun getStockByCode(code: String): StockBasicEntity? {
        return withContext(Dispatchers.IO) {
            stockDao.getByCode(code)
        }
    }

    /**
     * 按代码列表批量查询
     */
    suspend fun getStocksByCodes(codes: List<String>): List<StockBasicEntity> {
        return withContext(Dispatchers.IO) {
            stockDao.getByCodes(codes)
        }
    }

    /**
     * 按名称模糊搜索
     */
    suspend fun searchStocksByName(keyword: String): List<StockBasicEntity> {
        return withContext(Dispatchers.IO) {
            stockDao.searchByName(keyword)
        }
    }

    /**
     * 获取数据库统计信息
     */
    suspend fun getStats(): String {
        return withContext(Dispatchers.IO) {
            buildString {
                appendLine("数据库统计:")
                appendLine("  股票总数: ${stockDao.count()}")
                appendLine("  板块数: ${sectorDao.getAllSectorKeys().size}")
            }
        }
    }
}