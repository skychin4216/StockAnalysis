package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.data.sources.EastMoneyStockSource
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 统一数据层 (StrategyDataFeed)
 *
 * 参考 Backtrader DataFeed 架构设计：
 *   - 所有策略统一使用此入口获取 [StockRealtime] 列表
 *   - 集中处理缺失值补全（changePct=0 → 用 open 反推）
 *   - 量化选股Tab 和 模拟交易Tab 共享同一份数据
 *
 * ### 使用方式
 * ```kotlin
 * val feed = StrategyDataFeed(context)
 * val stocks = feed.prepareForDate("2026-06-12", DataFeedConfig(onlyMainBoard = true))
 * eng.getStrategies().forEach { it.screenWithData(stocks) }
 * ```
 */
class StrategyDataFeed(private val context: Context) {

    companion object {
        private const val TAG = "StrategyDataFeed"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        // ── 基本面日级缓存（PE/PB/市值/换手率，东财批量API，每日仅拉一次）──
        // DB快照只有OHLCV，基本面策略依赖这些字段；多线程并发时用锁避免重复拉取
        private data class FundamentalsCache(val date: String, val map: Map<String, StockRealtime>)
        @Volatile private var fundamentalsCache: FundamentalsCache? = null
        private val fundamentalsLock = Any()
    }

    private val db = StockDatabase.getInstance(context)

    data class DataFeedConfig(
        val onlyMainBoard: Boolean = true,
        val stockCodes: Set<String>? = null,    // null=全市场, 非null=只取这些代码
        val preferRealtime: Boolean = false,    // true=走实时API, false=DB快照
        val enrichFundamentals: Boolean = true  // true=出口批量注入PE/PB/市值(DB快照无此字段)
    )

    /** 快照 → 统一转换为 StockRealtime（集中处理缺失值） */
    private fun snapshotToStock(snap: DailySnapshotEntity, fallbackName: String = ""): StockRealtime {
        val name = snap.name.takeIf { it.isNotBlank() } ?: fallbackName

        // 计算 yestClose: 优先用 changePct, 否则用 open 反推
        val yc: Double = if (snap.changePct != 0.0 && snap.close != 0.0) {
            snap.close / (1.0 + snap.changePct / 100.0)
        } else if (snap.open > 0 && snap.close > 0) {
            snap.open / (1.0 + (snap.close - snap.open) / snap.open)
        } else snap.close

        // 计算 changePercent: 优先用 changePct, 否则用 close/open 反推
        val chgPct: Double = when {
            snap.changePct != 0.0 -> snap.changePct
            snap.close > 0 && snap.open > 0 && kotlin.math.abs(snap.close - snap.open) > 0.001 ->
                (snap.close - snap.open) / snap.open * 100
            snap.close > 0 && yc > 0 -> (snap.close - yc) / yc * 100
            else -> 0.0
        }

        return StockRealtime(
            code = snap.code, name = name, price = snap.close,
            open = snap.open, yestClose = yc, high = snap.high, low = snap.low,
            volume = snap.volume, amount = snap.amount, changePercent = chgPct,
            changeAmount = snap.close * chgPct / 100,
            turnoverRate = snap.turnoverRate,
            pe = snap.pe, pb = snap.pb, marketCap = snap.marketCap,
            roeTTM = snap.roeTTM, grossMarginTTM = snap.grossMarginTTM,
            debtToAsset = snap.debtToAsset, operatingCashFlow = snap.operatingCashFlow,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun isMainBoard(code: String): Boolean =
        !(code.startsWith("sz300") || code.startsWith("sz301") || code.startsWith("sh688") || code.startsWith("bj"))

    /** 从 DB 快照获取并转换 */
    suspend fun prepareFromDb(
        date: String,
        config: DataFeedConfig = DataFeedConfig()
    ): List<StockRealtime> = withContext(Dispatchers.IO) {
        try {
            val allSnaps = db.dailySnapshotDao().getByDate(date)
            if (allSnaps.isEmpty()) {
                Log.w(TAG, "日期 $date 无 DB 快照")
                return@withContext emptyList()
            }
            val codeToName = try { db.stockBasicDao().getAll().associate { it.code to it.name } }
                catch (_: Exception) { emptyMap() }
            val snaps = when {
                config.stockCodes != null -> allSnaps.filter { it.code in config.stockCodes }
                else -> allSnaps
            }
            val raw = snaps
                .filter { !config.onlyMainBoard || isMainBoard(it.code) }
                .map { snap -> snapshotToStock(snap, codeToName[snap.code] ?: snap.code) }
            // 兜底：DB 行缺基本面（如同步未跑）时用行情批量 API 补 PE/PB/市值
            // 加 15s 超时防止网络不稳定时阻塞整个 Pipeline
            val list = if (config.enrichFundamentals) {
                withTimeoutOrNull(15_000L) { enrichMissingFundamentals(raw) } ?: run {
                    Log.w(TAG, "基本面兜底超时(15s)，跳过 enrichment")
                    raw
                }
            } else raw
            Log.i(TAG, "数据准备: ${allSnaps.size}只 → 过滤后${list.size}只 (主板=${config.onlyMainBoard})")
            list
        } catch (e: Exception) {
            Log.w(TAG, "数据准备异常: ${e.message}"); emptyList()
        }
    }

    /** 批量极速转换（适用已知 snapshot 的场景） */
    fun convertSnapshots(snapshots: List<DailySnapshotEntity>, config: DataFeedConfig = DataFeedConfig()): List<StockRealtime> {
        val codeToName = try {
            kotlinx.coroutines.runBlocking { db.stockBasicDao().getAll().associate { it.code to it.name } }
        } catch (_: Exception) { emptyMap() }
        return snapshots
            .filter { !config.onlyMainBoard || isMainBoard(it.code) }
            .map { snap -> snapshotToStock(snap, codeToName[snap.code] ?: snap.code) }
    }

    /** 获取前 N 天数据（多周期模式） */
    suspend fun prepareMultiPeriod(
        baseDate: String,
        days: Int,
        config: DataFeedConfig = DataFeedConfig()
    ): List<StockRealtime> = withContext(Dispatchers.IO) {
        if (days <= 1) return@withContext prepareFromDb(baseDate, config)
        try {
            val allDates = db.dailySnapshotDao().getAvailableDates(days + 10)
                .filter { it <= baseDate }.sorted().takeLast(days)
            val allSnaps = mutableListOf<DailySnapshotEntity>()
            for (date in allDates) {
                try { allSnaps.addAll(db.dailySnapshotDao().getByDate(date)) }
                catch (_: Exception) { /* skip missing date */ }
            }
            val codeToName = try { db.stockBasicDao().getAll().associate { it.code to it.name } }
                catch (_: Exception) { emptyMap() }
            val list = allSnaps.distinctBy { it.code }
                .filter { !config.onlyMainBoard || isMainBoard(it.code) }
                .map { snap -> snapshotToStock(snap, codeToName[snap.code] ?: snap.code) }
                .take(1000)
            Log.i(TAG, "多周期数据: ${allSnaps.size}条 → ${list.size}只 (${days}天)")
            list
        } catch (e: Exception) {
            Log.w(TAG, "多周期数据异常: ${e.message}"); emptyList()
        }
    }

    // ══════════════════════════════════════
    // 基本面运行时兜底（B层）
    // 正常路径由 HistoricalDataFetcher Step 2.5 在同步时持久化；
    // 这里仅补 DB 行缺失的部分（marketCap<=0），同日缓存增量复用。
    // ══════════════════════════════════════

    /** 只补缺基本面的股票（市值<=0 视为缺失；PE 可为负=亏损，不作缺失判据） */
    private fun enrichMissingFundamentals(list: List<StockRealtime>): List<StockRealtime> {
        val missing = list.filter { it.marketCap <= 0.0 }
        if (missing.isEmpty()) return list
        val quoteMap = getOrFetchQuotes(missing.map { it.code })
        if (quoteMap.isEmpty()) return list
        var hit = 0
        val enriched = list.map { stock ->
            if (stock.marketCap > 0.0) return@map stock
            val rt = quoteMap[stock.code] ?: return@map stock
            hit++
            stock.copy(
                pe = if (rt.pe != 0.0) rt.pe else stock.pe,
                pb = if (rt.pb != 0.0) rt.pb else stock.pb,
                marketCap = if (rt.marketCap > 0) rt.marketCap else stock.marketCap,
                turnoverRate = if (rt.turnoverRate > 0) rt.turnoverRate else stock.turnoverRate
            )
        }
        Log.i(TAG, "基本面兜底: 缺失${missing.size}只, 命中$hit")
        return enriched
    }

    /** 行情批量（日级增量缓存，加锁防并发重复拉取） */
    private fun getOrFetchQuotes(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()
        val today = LocalDate.now().format(DATE_FMT)
        synchronized(fundamentalsLock) {
            val cached = fundamentalsCache
            val base = if (cached != null && cached.date == today) cached.map else emptyMap()
            val missing = codes.filter { it !in base }
            if (missing.isEmpty()) return base
            val fetched = try {
                EastMoneyStockSource().fetchRealtime(missing)
            } catch (e: Exception) {
                Log.w(TAG, "基本面兜底拉取失败(${missing.size}只): ${e.message}")
                emptyMap()
            }
            if (fetched.isNotEmpty()) {
                Log.i(TAG, "基本面缓存 +${fetched.size} (合计${base.size + fetched.size})")
            }
            val merged = base + fetched
            fundamentalsCache = FundamentalsCache(today, merged)
            return merged
        }
    }
}