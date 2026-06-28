package com.chin.stockanalysis.common

import android.content.Context
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.data.LeaderStockPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 股票數據填充服務
 *
 * 為備選池/自選/AI精選等頁面批量填充 StockDisplayItem 的 8 列數據。
 * 數據來源優先級：daily_snapshot > stock_basics > sector_stocks > LeaderStockPool
 */
object StockDataService {

    /**
     * 批量填充股票顯示數據
     * @param context Context
     * @param codes 股票代碼列表
     * @return 已填充所有字段的 StockDisplayItem 列表
     */
    suspend fun enrich(
        context: Context,
        codes: List<String>
    ): List<StockTableHelper.StockDisplayItem> = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext emptyList()

        val db = StockDatabase.getInstance(context)
        val today = java.time.LocalDate.now().toString()

        // ── 1. 從 daily_snapshot 獲取行情數據 ──
        val availableDates = try { db.dailySnapshotDao().getAvailableDates(5) } catch (_: Exception) { emptyList() }
        val targetDate = availableDates.filter { it <= today }.maxOrNull() ?: today
        val snaps = try { db.dailySnapshotDao().getByDate(targetDate) } catch (_: Exception) { emptyList() }
        val snapMap = snaps.associateBy { it.code }

        // ── 2. 從 stock_basics 獲取名稱 ──
        val basicsMap = try {
            db.stockBasicDao().getAll().associateBy { it.code }
        } catch (_: Exception) { emptyMap() }

        // ── 3. 從 sector_stocks 批量查板塊 ──
        val sectorCache = mutableMapOf<String, Pair<String, String>>()  // code -> (sector, subSector)
        for (code in codes) {
            val pair = findSector(db, code)
            sectorCache[code] = pair
        }

        // ── 4. 構建結果 ──
        codes.map { code ->
            val snap = snapMap[code]
            val basic = basicsMap[code]
            val (sector, subSector) = sectorCache[code] ?: ("其他" to "")

            // 名稱優先級：snap.name > basic.name > code
            val name = snap?.name?.takeIf { it.isNotBlank() && it != code }
                ?: basic?.name?.takeIf { it.isNotBlank() }
                ?: code

            StockTableHelper.StockDisplayItem(
                code = code,
                name = name,
                sector = sector,
                subSector = subSector,
                changePct = snap?.changePct ?: 0.0,
                price = snap?.close ?: 0.0,
                peRatio = 0.0,             // TODO: 等 EastMoney API 接入後填充
                turnoverRate = snap?.turnoverRate ?: 0.0,
                marketCap = 0.0,           // TODO: 等 EastMoney API 接入後填充
                changeAmount = if (snap != null) (snap.close - snap.open) else 0.0,
                hasSnapshot = snap != null,
                source = "",
                score = 0
            )
        }
    }

    // ── 輔助 ──

    /**
     * 查找股票所屬板塊（三級 fallback）
     * 1. LeaderStockPool 靜態配置
     * 2. sector_stocks DB 查詢
     * 3. 返回 "其他"
     */
    private suspend fun findSector(
        db: StockDatabase,
        code: String
    ): Pair<String, String> {
        // 1. LeaderStockPool 靜態配置（精確匹配）
        for (cfg in LeaderStockPool.SECTOR_CONFIGS) {
            for (ss in cfg.subSectors) {
                if (code in ss.stocks) {
                    return cfg.name to ss.name
                }
            }
        }
        // 2. DB sector_stocks 查詢
        try {
            val sectorNames = db.sectorStockDao().getSectorNamesByStockCode(code)
            if (sectorNames.isNotEmpty()) {
                val mainSector = sectorNames.firstOrNull { it.isNotBlank() && it != "其他" }
                    ?: sectorNames.first()
                val subSector = if (sectorNames.size > 1) {
                    sectorNames[1].takeIf { it.isNotBlank() && it != "其他" } ?: ""
                } else ""
                return mainSector to subSector
            }
        } catch (_: Exception) {}
        return "其他" to ""
    }
}