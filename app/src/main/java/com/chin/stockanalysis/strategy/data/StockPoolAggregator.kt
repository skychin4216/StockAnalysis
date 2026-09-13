package com.chin.stockanalysis.strategy.data

import android.content.Context
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.sector.UserMarketMemory
import com.chin.stockanalysis.strategy.trade.AutoTradePortfolioEngine
import com.chin.stockanalysis.strategy.trade.HotSectorStockPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 统一股票池整合器
 *
 * 把以下 7 个来源合并去重（按插入顺序即优先级：自选 > AI精选 > 备选池 > 龙头池
 * > 实仓 > 持仓 > 板块精选池）：
 * 1. 自选（user_watchlist）
 * 2. AI精选（ai_selected_stock）
 * 3. 备选池（CandidatePool：核心龙头 + AI动态）
 * 4. 龙头池（LeaderStockPool）
 * 5. 实仓（real_positions）
 * 6. 持仓（AutoTradePortfolioEngine）
 * 7. **板块精选池**（每板块 5 大票(主板) + 5 小票(科创/创业)）：
 *    由「近期热门板块」展开到子版块后从成分股快照现算，
 *    与一键建仓 DAG 中 SectorStockPoolNode 同源，不额外落盘。
 *
 * 任何“扫描全部股票池”的地方都应调用本函数，保证口径一致。
 */
object StockPoolAggregator {

    /** 收集结果：codes 为去重后的代码列表；countBySource 为各来源原始数量 */
    data class Result(
        val codes: List<String>,
        val countBySource: LinkedHashMap<String, Int>
    )

    /**
     * 合并收集所有股票池。
     * 各来源独立容错：单个来源失败不影响整体。
     */
    suspend fun collect(context: Context): Result = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        val sourceCodes = linkedMapOf<String, Set<String>>()

        fun put(label: String, codes: Collection<String>) {
            val clean = codes.filter { it.isNotBlank() }.toSet()
            if (clean.isNotEmpty()) sourceCodes[label] = clean
        }

        // 1. 自选
        try {
            put("自选", db.userWatchlistDao().getAll().map { it.stockCode })
        } catch (_: Exception) {}
        // 2. AI精选
        try {
            put("AI精选", db.aiSelectedStockDao().getAll().map { it.stockCode })
        } catch (_: Exception) {}
        // 3. 备选池
        try {
            put("备选池", CandidatePool.getPoolCodes(context))
        } catch (_: Exception) {}
        // 4. 龙头池
        try {
            put("龙头池", LeaderStockPool.getAllCodes(context))
        } catch (_: Exception) {}
        // 5. 实仓
        try {
            put("实仓", db.realPositionDao().getAll().map { it.stockCode })
        } catch (_: Exception) {}
        // 6. 持仓（自动交易模拟盘）
        try {
            put("持仓", AutoTradePortfolioEngine(context).getHoldings().map { it.stockCode })
        } catch (_: Exception) {}
        // 7. 板块精选池：近期热门板块(近5日热度Top) → 每板块 5大+5小
        try {
            val memory = UserMarketMemory(context)
            val hotSectors = memory.getRecentHotSectors().map { it.sectorName }.take(12).toSet()
            val sectorPool = if (hotSectors.isNotEmpty()) {
                HotSectorStockPool.build(context, hotSectors)
            } else {
                // 无板块记录时退化为东方财富默认板块
                HotSectorStockPool.build(context, emptySet())
            }
            if (sectorPool.isNotEmpty()) put("板块池", sectorPool)
        } catch (_: Exception) {}

        // 合并去重（保持来源优先级顺序）
        val merged = LinkedHashSet<String>()
        sourceCodes.values.forEach { merged.addAll(it) }

        Result(
            codes = merged.toList(),
            countBySource = LinkedHashMap(sourceCodes.mapValues { (_, v) -> v.size })
        )
    }
}
