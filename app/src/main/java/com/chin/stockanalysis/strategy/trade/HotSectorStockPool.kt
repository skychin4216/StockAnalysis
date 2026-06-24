package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 熱門板塊動態選股池 v2.0 — AI 驅動
 *
 * App啟動時通過 AI 獲取當前熱門板塊 → 展開到子版塊 →
 * 每個子版塊取前10隻股票（5主板 + 5科創/創業），合併去重。
 *
 * 覆蓋板塊由 AI 動態決定（不再硬編碼）
 */
object HotSectorStockPool {

    private const val TAG = "HotSectorStockPool"

    /** 每個子版塊選取的主板股票數量 */
    private const val MAIN_BOARD_COUNT = 5
    /** 每個子版塊選取的科創/創業股票數量 */
    private const val SCI_TECH_COUNT = 5

    /**
     * 構建熱門板塊選股池
     * @param context 上下文
     * @param aiSectorNames AI 提供的熱門板塊名稱集合（可選，為空則 fallback 到東方財富）
     * @return 代碼集合（用於與其他池合併）
     */
    suspend fun build(
        context: Context,
        aiSectorNames: Set<String> = emptySet()
    ): Set<String> = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        val pool = mutableSetOf<String>()

        try {
            // 1. 獲取板塊名稱（優先使用 AI 提供的，否則 fallback 到東方財富）
            val sectorNames: List<String> = if (aiSectorNames.isNotEmpty()) {
                Log.i(TAG, "🤖 使用 AI 提供的 ${aiSectorNames.size} 個熱門板塊")
                aiSectorNames.toList()
            } else {
                Log.i(TAG, "⚠️ AI 未提供板塊，fallback 到東方財富")
                val allSectors = (EastMoneyHotSectorSource.conceptSectors +
                        EastMoneyHotSectorSource.industrySectors)
                    .distinctBy { it.name }
                Log.i(TAG, "獲取到 ${allSectors.size} 個板塊（東方財富）")
                allSectors.map { it.name }
            }

            // 2. 展開每個板塊的子版塊
            val allSubSectors = mutableSetOf<String>()
            for (sectorName in sectorNames) {
                allSubSectors.add(sectorName)
                try {
                    val subs = com.chin.stockanalysis.stock.data.sources.SectorSubDivision
                        .getSubSectors(sectorName)
                    subs.forEach { allSubSectors.add(it.name) }
                } catch (_: Exception) { /* 無子版塊則只用父板塊 */ }
            }
            Log.i(TAG, "展開後共 ${allSubSectors.size} 個(子)板塊")

            // 3. 對每個子版塊，從DB取股票代碼，篩選前10隻
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

                    // 取前5主板 + 前5科創/創業
                    val picked = mainBoard.take(MAIN_BOARD_COUNT) + sciTech.take(SCI_TECH_COUNT)
                    pool.addAll(picked)
                    totalPicked += picked.size
                } catch (_: Exception) { /* skip individual sector errors */ }
            }

            Log.i(TAG, "熱門板塊選股池構建完成: ${pool.size} 隻 (從 ${allSubSectors.size} 個板塊選出 $totalPicked 次)")
        } catch (e: Exception) {
            Log.w(TAG, "構建熱門板塊選股池失敗: ${e.message}")
        }

        pool
    }

    private fun isMainBoard(code: String): Boolean =
        !(code.startsWith("sz300") || code.startsWith("sz301") ||
                code.startsWith("sh688") || code.startsWith("bj"))
}