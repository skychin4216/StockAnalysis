package com.chin.stockanalysis.common

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.sources.EastMoneyStockSource
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

        // ── 1. 從 daily_snapshot 獲取行情數據（本地快照，可能有名稱但缺少 PE/市值） ──
        val availableDates = try { db.dailySnapshotDao().getAvailableDates(5) } catch (_: Exception) { emptyList() }
        val targetDate = availableDates.filter { it <= today }.maxOrNull() ?: today
        val snaps = try { db.dailySnapshotDao().getByDate(targetDate) } catch (_: Exception) { emptyList() }
        val snapMap = snaps.associateBy { it.code }.toMutableMap()

        // ── 2. 從 stock_basics 獲取名稱 ──
        val basicsMap = try {
            db.stockBasicDao().getAll().associateBy { it.code }
        } catch (_: Exception) { emptyMap() }

        // ── 3. 從東方財富 API 獲取完整實時數據（名稱+價格+PE+市值+換手率）──
        val rtMap = try {
            EastMoneyStockSource().fetchRealtime(codes)
        } catch (e: Exception) {
            Log.w("StockDataService", "API 獲取實時數據失敗: ${e.message}")
            emptyMap()
        }
        // 對於本地快照缺失的股票，用 API 數據補充
        rtMap.forEach { (code, rt) ->
            if (snapMap[code] == null) {
                snapMap[code] = com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity(
                    code = rt.code, name = rt.name, date = today,
                    open = rt.open, close = rt.price,
                    high = rt.high, low = rt.low,
                    volume = rt.volume, amount = rt.amount,
                    changePct = rt.changePercent,
                    turnoverRate = rt.turnoverRate
                )
            }
        }

        // ── 4. 從 sector_stocks 批量查板塊 ──
        val sectorCache = mutableMapOf<String, Pair<String, String>>()
        for (code in codes) {
            val pair = findSector(context, db, code)
            sectorCache[code] = pair
        }

        // ── 5. 構建結果（API 數據優先用於填充 PE/市值/換手率） ──
        codes.map { code ->
            val snap = snapMap[code]
            val basic = basicsMap[code]
            val rt = rtMap[code]
            val (sector, subSector) = sectorCache[code] ?: ("其他" to "")

            // 名稱優先級：snap.name > basic.name > rt.name > code
            val name = snap?.name?.takeIf { it.isNotBlank() && it != code }
                ?: basic?.name?.takeIf { it.isNotBlank() }
                ?: rt?.name?.takeIf { it.isNotBlank() }
                ?: code

            StockTableHelper.StockDisplayItem(
                code = code,
                name = name,
                sector = sector,
                subSector = subSector,
                changePct = rt?.changePercent ?: snap?.changePct ?: 0.0,
                price = rt?.price ?: snap?.close ?: 0.0,
                peRatio = rt?.pe ?: 0.0,
                turnoverRate = rt?.turnoverRate ?: snap?.turnoverRate ?: 0.0,
                marketCap = { val cap = rt?.marketCap ?: 0.0; if (cap > 0) cap / 1_0000_0000.0 else 0.0 }(),
                changeAmount = rt?.changeAmount ?: if (snap != null) (snap.close - snap.open) else 0.0,
                hasSnapshot = snap != null || rt != null,
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
        context: Context,
        db: StockDatabase,
        code: String
    ): Pair<String, String> {
        // 1. LeaderStockPool 持久化配置
        for (cfg in LeaderStockPool.getAllConfigs(context)) {
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