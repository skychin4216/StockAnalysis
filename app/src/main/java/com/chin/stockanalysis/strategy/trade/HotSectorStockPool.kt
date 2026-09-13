package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 热门板块动态选股池 v2.0 — AI 驱动
 *
 * App启动时通过 AI 获取当前热门板块 → 展开到子版块 →
 * 每个子版块取前10只股票（5主板 + 5科创/创业），合并去重。
 *
 * 覆盖板块由 AI 动态决定（不再硬编码）
 */
object HotSectorStockPool {

    private const val TAG = "HotSectorStockPool"

    /** 每个子版块选取的主板股票数量 */
    private const val MAIN_BOARD_COUNT = 5
    /** 每个子版块选取的科创/创业股票数量 */
    private const val SCI_TECH_COUNT = 5

    /**
     * 构建热门板块选股池
     * @param context 上下文
     * @param aiSectorNames AI 提供的热门板块名称集合（可选，为空则 fallback 到东方财富）
     * @return 代码集合（用于与其他池合并）
     */
    suspend fun build(
        context: Context,
        aiSectorNames: Set<String> = emptySet()
    ): Set<String> = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        val pool = mutableSetOf<String>()

        try {
            // 1. 获取板块名称（优先使用 AI 提供的，否则 fallback 到东方财富）
            val sectorNames: List<String> = if (aiSectorNames.isNotEmpty()) {
                Log.i(TAG, "🤖 使用 AI 提供的 ${aiSectorNames.size} 个热门板块")
                aiSectorNames.toList()
            } else {
                Log.i(TAG, "⚠️ AI 未提供板块，fallback 到东方财富")
                val allSectors = (EastMoneyHotSectorSource.conceptSectors +
                        EastMoneyHotSectorSource.industrySectors)
                    .distinctBy { it.name }
                Log.i(TAG, "获取到 ${allSectors.size} 个板块（东方财富）")
                allSectors.map { it.name }
            }

            // 2. 展开每个板块的子版块
            val allSubSectors = mutableSetOf<String>()
            for (sectorName in sectorNames) {
                allSubSectors.add(sectorName)
                try {
                    val subs = com.chin.stockanalysis.stock.data.sources.SectorSubDivision
                        .getSubSectors(sectorName)
                    subs.forEach { allSubSectors.add(it.name) }
                } catch (_: Exception) { /* 无子版块则只用父板块 */ }
            }
            Log.i(TAG, "展开后共 ${allSubSectors.size} 个(子)板块")

            // 3. 对每个子版块，从DB取股票代码，筛选前10只
            var totalPicked = 0
            for (subSector in allSubSectors) {
                try {
                    val codes = db.sectorStockDao().getStockCodesBySector(subSector)
                    if (codes.isEmpty()) continue

                    val mainBoard = mutableListOf<String>()
                    val sciTech = mutableListOf<String>()

                    for (code in codes) {
                        if (isMainBoard(code)) mainBoard.add(code)
                        else sciTech.add(code)
                    }

                    // 取前5主板 + 前5科创/创业
                    val picked = mainBoard.take(MAIN_BOARD_COUNT) + sciTech.take(SCI_TECH_COUNT)
                    pool.addAll(picked)
                    totalPicked += picked.size
                } catch (_: Exception) { /* skip individual sector errors */ }
            }

            Log.i(TAG, "热门板块选股池构建完成: ${pool.size} 只 (从 ${allSubSectors.size} 个板块选出 $totalPicked 次)")
        } catch (e: Exception) {
            Log.w(TAG, "构建热门板块选股池失败: ${e.message}")
        }

        pool
    }

    private fun isMainBoard(code: String): Boolean =
        !(code.startsWith("sz300") || code.startsWith("sz301") ||
                code.startsWith("sh688") || code.startsWith("bj"))
}