package com.chin.stockanalysis.strategy.trade

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 熱門板塊動態選股池
 *
 * 第1步 (原第0步)：
 * App啟動時抓取近一年熱門板塊 → 展開到子版塊 →
 * 每個子版塊取前10隻股票（5主板 + 5科創/創業），合併去重。
 *
 * 覆蓋板塊包括：
 * 光通信(光模塊/光芯片/光纖/光器件)、化肥、有機硅、化工、電網設備、
 * 固態電池、風電設備、算力(國產替代)、華為產業鏈、超算、銅箔、PCB、
 * 燃氣輪機、儲能、液冷、谷歌/馬斯克/英偉達供應鏈、電子布、稀土、
 * 半導體(設計/封測/設備/材料)、綠電、傳統電力、光刻膠、高壓變壓器、
 * AI服務器、光伏玻璃、戶用儲能、大儲能、算點協同、量子科技、CPO、
 * 有色金屬...等
 */
object HotSectorStockPool {

    private const val TAG = "HotSectorStockPool"

    /** 每個子版塊選取的主板股票數量 */
    private const val MAIN_BOARD_COUNT = 5
    /** 每個子版塊選取的科創/創業股票數量 */
    private const val SCI_TECH_COUNT = 5

    /**
     * 構建熱門板塊選股池
     * @return 代碼集合（用於與其他池合併）
     */
    suspend fun build(context: Context): Set<String> = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        val pool = mutableSetOf<String>()

        try {
            // 1. 從東方財富獲取概念板塊 + 行業板塊
            val allSectors = (EastMoneyHotSectorSource.conceptSectors +
                    EastMoneyHotSectorSource.industrySectors)
                .distinctBy { it.name }
            Log.i(TAG, "獲取到 ${allSectors.size} 個板塊")

            // 2. 展開每個板塊的子版塊
            val allSubSectors = mutableSetOf<String>()
            for (sector in allSectors) {
                allSubSectors.add(sector.name)
                try {
                    val subs = com.chin.stockanalysis.stock.data.sources.SectorSubDivision
                        .getSubSectors(sector.name)
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