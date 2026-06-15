package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.strategies.FundamentalFilterStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Dual-pool stock manager for weekly iteration.
 *
 * - coreStockPool: refined pool (<200 stocks) for daily import/scanning
 * - headerStockPool: leader stocks from hot sectors for style rotation
 *
 * Weekly cycle: basic filter -> three-layer screen -> compute hot leaders ->
 * merge all -> low-quality filter -> score/sort/limit -> eliminate stale
 */
class StockPoolManager(private val context: Context) {

    companion object {
        private const val TAG = "StockPoolManager"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private const val MAX_CORE = 200
        private const val MAX_HEADER = 100
        private const val ELIMINATION_WEEKS = 4
        private const val MIN_CFO_TO_NI = 0.8
    }

    private val db = StockDatabase.getInstance(context)
    private val prefs = context.getSharedPreferences("stock_pool_mgr", Context.MODE_PRIVATE)

    /** Whether to only include main board (exclude 300/301/688/BJ) */
    var onlyMainBoard: Boolean = true

    // Core pool (backed by SharedPreferences via HistoricalDataFetcher)
    fun getCorePool(): Set<String> = HistoricalDataFetcher.getCoreStockPool(context)

    // Header pool (backed by SharedPreferences via HistoricalDataFetcher)
    fun getHeaderPool(): Set<String> = HistoricalDataFetcher.getHeaderStockPool(context)

    // Miss weeks
    private fun loadMiss(): MutableMap<String, Int> {
        val s = prefs.getString("miss_weeks", "") ?: ""
        if (s.isBlank()) return mutableMapOf()
        return s.split(";").mapNotNull { p ->
            val kv = p.split(":")
            if (kv.size == 2) kv[0] to (kv[1].toIntOrNull() ?: 0) else null
        }.associateTo(mutableMapOf()) { it.first to it.second }
    }

    private fun saveMiss(m: MutableMap<String, Int>) {
        prefs.edit().putString("miss_weeks", m.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()
    }

    // Last update
    private fun getLastUpdate(): String? = prefs.getString("last_update", null)
    private fun setLastUpdate(v: String) { prefs.edit().putString("last_update", v).apply() }

    // ═══════════════════════════════════════════
    //  Init
    // ═══════════════════════════════════════════

    suspend fun initialize() {
        val core = getCorePool()
        val header = getHeaderPool()
        if (core.isNotEmpty() && header.isNotEmpty()) {
            Log.i(TAG, "Already initialized: core=${core.size} header=${header.size}")
            return
        }
        val seed = (HistoricalDataFetcher.getCoreStockPool(context) + LeaderStockPool.ALL_LEADER_CODES)
        val valid = basicFilter(seed)
        HistoricalDataFetcher.saveCoreStockPool(context, valid)
        HistoricalDataFetcher.saveHeaderStockPool(context, LeaderStockPool.ALL_LEADER_CODES)
        val mw = mutableMapOf<String, Int>()
        for (c in valid) mw[c] = 0
        saveMiss(mw)
        setLastUpdate(LocalDate.now().format(DATE_FMT))
        Log.i(TAG, "Initialized: core=${valid.size} header=${getHeaderPool().size}")
    }

    // ═══════════════════════════════════════════
    //  Weekly update
    // ═══════════════════════════════════════════

    suspend fun weeklyUpdate(today: LocalDate = LocalDate.now()) {
        val dateStr = today.format(DATE_FMT)
        if (getLastUpdate() == dateStr) { Log.i(TAG, "Already updated $dateStr"); return }

        Log.i(TAG, "=== Weekly update: $dateStr ===")
        val allStocks = basicFilterFromDB()
        Log.i(TAG, "Step1 basic: ${allStocks.size}")

        val repo = com.chin.stockanalysis.stock.data.StockDataSourceFactory
            .createDefaultRepository(context.applicationContext)
        val filter = FundamentalFilterStrategy(StockScreener(repo))
        val weeklyPassed = if (allStocks.isNotEmpty()) {
            try { filter.screenWithData(allStocks).getOrNull()?.signals?.map { it.stockCode }?.toSet() ?: emptySet() }
            catch (e: Exception) { Log.w(TAG, "screen err: ${e.message}"); emptySet() }
        } else emptySet()
        Log.i(TAG, "Step2 passed: ${weeklyPassed.size}")

        val newLeaders = computeHotLeaders(today)
        Log.i(TAG, "Step3 leaders: ${newLeaders.size}")

        val core = getCorePool()
        val header = getHeaderPool()
        val userSearch = HistoricalDataFetcher.getUserSearchPool(context)
        val skillPick = HistoricalDataFetcher.getSkillPickPool(context)
        val merged = core + header + weeklyPassed + newLeaders + userSearch + skillPick
        Log.i(TAG, "Step4 merged: ${merged.size} (c${core.size}+h${header.size}+p${weeklyPassed.size}+l${newLeaders.size})")

        // Step4.5: Low-quality filter (remove junk stocks)
        val stockMap = allStocks.associateBy { it.code }
        val qualityPassed = merged.filter { code ->
            val s = stockMap[code]
            s != null && !isLowQualityStock(s)
        }.toSet()
        if (qualityPassed.size < merged.size) {
            Log.i(TAG, "Step4.5 quality filter: removed ${merged.size - qualityPassed.size} low-quality stocks, remaining ${qualityPassed.size}")
        }

        val scored = qualityPassed.mapNotNull { code ->
            val s = stockMap[code] ?: return@mapNotNull null
            val sc = computeScore(s, newLeaders)
            sc to code
        }.sortedByDescending { it.first }

        val newCore = scored.take(MAX_CORE).map { it.second }.toSet()
        Log.i(TAG, "Step5 scored: ${newCore.size} (max $MAX_CORE)")

        updateMissAndEliminate(newCore)
        HistoricalDataFetcher.saveCoreStockPool(context, newCore)
        HistoricalDataFetcher.saveHeaderStockPool(context, (header + newLeaders).take(MAX_HEADER).toSet())
        setLastUpdate(dateStr)
        Log.i(TAG, "=== Done: core=${newCore.size} header=${getHeaderPool().size} ===")
    }

    // ═══════════════════════════════════════════
    //  Low-quality stock filter (junk filter)
    // ═══════════════════════════════════════════

    private fun isLowQualityStock(s: StockRealtime): Boolean {
        if (s.roeTTM > 5.0 && s.operatingCashFlow < 0.0) return true
        if (s.pe < 0.0) return true
        if (s.roeTTM > 0.0 && s.roeTTM < 5.0) return true
        if (s.debtToAsset > 0.0 && s.debtToAsset > 70.0) return true
        if (s.price <= 2.0) return true
        if (s.amount > 0.0 && s.amount < 50_000_000.0) return true
        if (s.grossMarginTTM > 0.0 && s.grossMarginTTM < 10.0) return true
        return false
    }

    // ═══════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════

    private suspend fun basicFilter(codes: Set<String>): Set<String> {
        if (codes.isEmpty()) return emptySet()
        return try {
            val snaps = db.dailySnapshotDao().getByDate(LocalDate.now().format(DATE_FMT))
            if (snaps.isEmpty()) return codes
            val map = snaps.associateBy { it.code }
            codes.filter { c ->
                val s = map[c] ?: return@filter true
                !s.name.contains("ST", true) && !s.name.contains("退", true) &&
                s.close > 1.0 && s.volume > 0 && s.amount > 0
            }.toSet()
        } catch (_: Exception) { codes }
    }

    private suspend fun basicFilterFromDB(): List<StockRealtime> = withContext(Dispatchers.IO) {
        try {
            val feed = StrategyDataFeed(context)
            val list = feed.prepareFromDb(LocalDate.now().format(DATE_FMT),
                StrategyDataFeed.DataFeedConfig(onlyMainBoard = onlyMainBoard))
            list.filter { s ->
                !s.name.contains("ST", true) && !s.name.contains("退", true) &&
                s.price > 1.0 && s.volume > 0 && s.amount > 0 &&
                (s.marketCap <= 0.0 || s.marketCap >= 100000000000.0) &&
                s.amount >= 10000000.0
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun computeHotLeaders(today: LocalDate): Set<String> {
        return try {
            val dates = db.dailySnapshotDao().getAvailableDates(10)
            if (dates.isEmpty()) return emptySet()
            val snaps = mutableListOf<com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity>()
            for (d in dates.take(5)) snaps.addAll(db.dailySnapshotDao().getByDate(d))
            snaps.groupBy { it.code }.map { (code, list) ->
                val gain = list.map { it.changePct }.average()
                val amt = list.sumOf { it.amount }
                val vol = list.map { it.volume }.average().toLong()
                val sc = gain * 0.5 + (amt / 1e9) * 0.3 + (vol / 1e7) * 0.2
                code to sc
            }.sortedByDescending { it.second }.take(50).map { it.first }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun computeScore(s: StockRealtime, leaders: Set<String>): Double {
        var sc = 0.0
        val tech = s.code.startsWith("sh688") || s.code.startsWith("sz300")
        if (s.pe > 0 && ((tech && s.pe < 80) || (!tech && s.pe < 25))) sc += 1.0
        if (s.pb > 0 && s.pb < 3.0) sc += 0.5
        if (s.roeTTM > 0 && s.roeTTM > 10) sc += 1.5
        if (s.turnoverRate > 0 && s.turnoverRate in 3.0..15.0) sc += 1.0
        if (s.amount > 200000000) sc += 0.5
        if (s.code in leaders) sc += 2.0
        return sc
    }

    private fun updateMissAndEliminate(newCore: Set<String>) {
        val mw = loadMiss()
        for (c in mw.keys.toList()) mw[c] = (mw[c] ?: 0) + 1
        for (c in newCore) mw[c] = 0
        val toRemove = mw.filter { (c, w) -> w >= ELIMINATION_WEEKS && c !in getHeaderPool() }.keys
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "Eliminate ${toRemove.size} stale: ${toRemove.take(5)}")
            HistoricalDataFetcher.saveCoreStockPool(context, getCorePool().filterNot { it in toRemove }.toSet())
            for (c in toRemove) mw.remove(c)
        }
        saveMiss(mw)
    }
}