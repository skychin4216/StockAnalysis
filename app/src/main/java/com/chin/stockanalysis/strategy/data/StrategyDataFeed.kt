package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    }

    private val db = StockDatabase.getInstance(context)

    data class DataFeedConfig(
        val onlyMainBoard: Boolean = true,
        val stockCodes: Set<String>? = null,    // null=全市场, 非null=只取这些代码
        val preferRealtime: Boolean = false     // true=走实时API, false=DB快照
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
            changeAmount = snap.close * chgPct / 100, timestamp = System.currentTimeMillis()
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
            val list = snaps
                .filter { !config.onlyMainBoard || isMainBoard(it.code) }
                .map { snap -> snapshotToStock(snap, codeToName[snap.code] ?: snap.code) }
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
}