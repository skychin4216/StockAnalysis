package com.chin.stockanalysis.strategy.sector

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 策略市場統一上下文
 *
 * 一次性整合所有策略需要的市場數據，避免在 量化選股/中綫量化/短綫量化 中重複調用：
 * - 用戶關注板塊（UserMarketMemory）
 * - 多周期熱門板塊（今日 / 周 / 月 / 季度）
 * - 大盤指數 K 綫（上證 / 深證 / 創業板）
 * - 回彈板塊（SectorBounceFactor）
 * - AI 板塊大年檢測
 *
 * 使用方式：
 * ```kotlin
 * val ctx = StrategyMarketContext.build(requireContext(), selectedDate)
 * // 後續直接讀取屬性，無需再查數據庫
 * val top3Today = ctx.todayHotSectors
 * val focusBoost = ctx.getFocusBoostForStock("茅台")
 * ```
 */
class StrategyMarketContext private constructor(
    val userFocusSectors: List<String>,
    val todayHotSectors: List<String>,
    val weeklyHotSectors: List<String>,
    val monthlyHotSectors: List<String>,
    val quarterlyHotSectors: List<String>,
    val bounceSectors: List<SectorBounceFactor.BounceSector>,
    val aiYearDetection: String,
    val indexSnapshot: IndexSnapshot
) {
    companion object {
        private const val TAG = "StrategyMarketContext"

        /** 緩存：避免短時間內重複構建 */
        private var cached: StrategyMarketContext? = null
        private var cachedAtMs: Long = 0L
        private const val CACHE_TTL_MS = 300_000L // 5 分鐘

        /** 清空緩存（用戶修改關注板塊後調用） */
        fun invalidateCache() {
            cached = null
            cachedAtMs = 0L
            Log.i(TAG, "緩存已清空")
        }

        /**
         * 構建市場上下文（帶緩存）
         * @param context Android Context
         * @param selectedDate 選定交易日（默認最新）
         * @param forceRefresh 強制刷新緩存
         */
        suspend fun build(
            context: Context,
            selectedDate: String? = null,
            forceRefresh: Boolean = false
        ): StrategyMarketContext = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!forceRefresh && cached != null && (now - cachedAtMs) < CACHE_TTL_MS) {
                Log.i(TAG, "使用緩存的市場上下文（${(now - cachedAtMs) / 1000}秒前）")
                return@withContext cached!!
            }

            val db = StockDatabase.getInstance(context)
            val memory = UserMarketMemory(context)
            val bounceFactor = SectorBounceFactor(db)
            val targetDate = selectedDate ?: try {
                db.dailySnapshotDao().getAvailableDates(1).firstOrNull()
            } catch (_: Exception) { null }

            Log.i(TAG, "構建市場上下文...")

            // 1. 用戶關注板塊
            val userFocus = memory.focusSectors

            // 2. 多周期熱門板塊
            // 今日熱門：優先用東方財富實時數據（EastMoneyHotSectorSource）
            val todayHot = fetchTodayHotFromAPI() ?: fetchHotSectors(db, days = 1, topN = 3)

            // 周/月/季度熱門：用 AIHotSectorProvider（AI查詢+緩存，不依賴 sector_daily_record）
            val aiSectors = try {
                com.chin.stockanalysis.strategy.data.AIHotSectorProvider.getHotSectors(context)
            } catch (_: Exception) { null }
            val weeklyHot = aiSectors?.weeklySectors?.take(5) ?: emptyList()
            val monthlyHot = aiSectors?.monthlySectors?.take(5) ?: emptyList()
            val quarterlyHot = aiSectors?.annualSectors?.take(5) ?: emptyList()

            // 3. 回彈板塊
            val bounces = try { bounceFactor.detectBounceSectors(targetDate) } catch (_: Exception) { emptyList() }

            // 4. AI 板塊大年（異步檢測，失敗不阻塞）
            val yearDetection = try { memory.detectSectorYearByIndex() } catch (_: Exception) { "檢測失敗" }
            if (memory.aiYearDetection == "未檢測" || memory.aiYearDetection == "檢測失敗") {
                memory.aiYearDetection = yearDetection
            }

            // 5. 大盤指數快照
            val indexSnap = buildIndexSnapshot(db, targetDate)

            val ctx = StrategyMarketContext(
                userFocusSectors = userFocus,
                todayHotSectors = todayHot,
                weeklyHotSectors = weeklyHot,
                monthlyHotSectors = monthlyHot,
                quarterlyHotSectors = quarterlyHot,
                bounceSectors = bounces,
                aiYearDetection = yearDetection,
                indexSnapshot = indexSnap
            )

            cached = ctx
            cachedAtMs = now
            Log.i(TAG, "市場上下文構建完成：今日熱門${todayHot.size}個，回彈板塊${bounces.size}個，用戶關注${userFocus.size}個")
            ctx
        }

        /** 從東方財富實時 API 獲取今日熱門板塊（概念板塊+行業板塊 Top3） */
        private fun fetchTodayHotFromAPI(): List<String>? {
            val all = EastMoneyHotSectorSource.conceptSectors + EastMoneyHotSectorSource.industrySectors
            if (all.isEmpty()) return null
            return all.sortedByDescending { it.changePercent }
                .take(3)
                .map { it.name }
                .distinct()
        }

        /** 獲取指定周期內的熱門板塊 TopN */
        private suspend fun fetchHotSectors(db: StockDatabase, days: Int, topN: Int): List<String> {
            // 主路徑：sector_daily_record 表
            return try {
                val records = db.sectorDailyRecordDao().getRecentDays(days)
                if (records.isNotEmpty()) {
                    records.groupBy { it.sectorName }
                        .map { (name, recs) -> name to recs.map { it.changePct }.average() }
                        .sortedByDescending { it.second }
                        .take(topN)
                        .map { it.first }
                } else emptyList()
            } catch (_: Exception) { emptyList() }
        }

        /** 構建三大指數快照 */
        private suspend fun buildIndexSnapshot(db: StockDatabase, date: String?): IndexSnapshot {
            return try {
                val sh = db.dailySnapshotDao().getByCode("sh000001", 30).sortedBy { it.date }
                val sz = db.dailySnapshotDao().getByCode("sz399001", 30).sortedBy { it.date }
                val cy = db.dailySnapshotDao().getByCode("sz399006", 30).sortedBy { it.date }

                fun ma(closes: List<Double>, n: Int) = closes.takeLast(n).average()
                fun dir(closes: List<Double>): String {
                    if (closes.size < 20) return "UNKNOWN"
                    val ma5 = ma(closes, 5); val ma10 = ma(closes, 10); val ma20 = ma(closes, 20)
                    return when {
                        ma5 > ma10 && ma10 > ma20 -> "BULLISH"
                        ma5 < ma10 && ma10 < ma20 -> "BEARISH"
                        else -> "OSCILLATION"
                    }
                }

                IndexSnapshot(
                    shDirection = dir(sh.map { it.close }),
                    shLatestChange = sh.lastOrNull()?.changePct ?: 0.0,
                    shMA5 = if (sh.size >= 5) ma(sh.map { it.close }, 5) else 0.0,
                    shMA10 = if (sh.size >= 10) ma(sh.map { it.close }, 10) else 0.0,
                    shMA20 = if (sh.size >= 20) ma(sh.map { it.close }, 20) else 0.0,
                    szDirection = dir(sz.map { it.close }),
                    cyDirection = dir(cy.map { it.close }),
                    tripleVote = tripleVote(dir(sh.map { it.close }), dir(sz.map { it.close }), dir(cy.map { it.close }))
                )
            } catch (_: Exception) {
                IndexSnapshot()
            }
        }

        private fun tripleVote(sh: String, sz: String, cy: String): String {
            val votes = listOf(sh, sz, cy).filter { it != "UNKNOWN" }
            if (votes.size < 2) return "UNKNOWN"
            val bullish = votes.count { it == "BULLISH" }
            val bearish = votes.count { it == "BEARISH" }
            return when {
                bullish >= 2 -> "BULLISH"
                bearish >= 2 -> "BEARISH"
                else -> "OSCILLATION"
            }
        }
    }

    /** 大盤指數快照 */
    data class IndexSnapshot(
        val shDirection: String = "UNKNOWN",
        val shLatestChange: Double = 0.0,
        val shMA5: Double = 0.0,
        val shMA10: Double = 0.0,
        val shMA20: Double = 0.0,
        val szDirection: String = "UNKNOWN",
        val cyDirection: String = "UNKNOWN",
        val tripleVote: String = "UNKNOWN"
    ) {
        fun marketDesc(): String {
            val trend = when (tripleVote) {
                "BULLISH" -> "多頭排列（MA5>MA10>MA20），大盤處於上升趨勢"
                "BEARISH" -> "空頭排列（MA5<MA10<MA20），大盤處於下降趨勢"
                else -> "均線糾纏，大盤震蕩格局"
            }
            return "大盤方向: $tripleVote | 上證${shDirection}/深證${szDirection}/創業板${cyDirection} | $trend | 上證最新日漲跌幅: ${"%.2f".format(shLatestChange)}% | 上證MA5=${"%.2f".format(shMA5)} MA10=${"%.2f".format(shMA10)} MA20=${"%.2f".format(shMA20)}"
        }
    }

    // ════════════════════════════════════════
    // 便捷查詢方法
    // ════════════════════════════════════════

    /** 獲取用戶關注板塊對某股票的權重加成 */
    fun getFocusBoostForStock(stockName: String): Int {
        return if (userFocusSectors.any { stockName.contains(it) || it.contains(stockName.take(2)) }) 15 else 0
    }

    /** 獲取某股票所屬回彈板塊的回調加分（回調 N 天 + N 分） */
    fun getBounceBoostForStock(stockName: String): Int {
        val matched = bounceSectors.find {
            stockName.contains(it.sectorName) || it.sectorName.contains(stockName.take(2))
        } ?: return 0
        return (-matched.recentDropPct / 1.0).toInt().coerceAtMost(5).coerceAtLeast(1)
    }

    /** 獲取某股票在今日熱門板塊中的排名（未命中返回 -1） */
    fun getTodayHotRank(stockName: String): Int {
        return todayHotSectors.indexOfFirst {
            stockName.contains(it) || it.contains(stockName.take(2))
        }
    }

    /** 是否屬於任意周期熱門板塊 */
    fun isInAnyHotSector(stockName: String): Boolean {
        val allHot = todayHotSectors + weeklyHotSectors + monthlyHotSectors + quarterlyHotSectors
        return allHot.any { stockName.contains(it) || it.contains(stockName.take(2)) }
    }

    /** 生成 AIPredictionEngine.SectorContext */
    fun toAiSectorContext(): com.chin.stockanalysis.strategy.predict.AIPredictionEngine.SectorContext {
        return com.chin.stockanalysis.strategy.predict.AIPredictionEngine.SectorContext(
            userFocusSectors = userFocusSectors,
            todayHotSectors = todayHotSectors,
            bounceSectors = bounceSectors.map {
                com.chin.stockanalysis.strategy.predict.AIPredictionEngine.SectorContext.BounceSectorInfo(
                    sectorName = it.sectorName,
                    consecutiveHotDays = it.consecutiveHotDays,
                    recentDropPct = it.recentDropPct,
                    todayBouncePct = it.todayBouncePct,
                    bounceScore = it.bounceScore
                )
            },
            aiYearDetection = aiYearDetection
        )
    }

    /** 生成簡潔摘要（用於日誌或 UI 顯示） */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (todayHotSectors.isNotEmpty()) parts.add("今日熱門: ${todayHotSectors.joinToString(",")}")
        if (weeklyHotSectors.isNotEmpty()) parts.add("周熱門: ${weeklyHotSectors.joinToString(",")}")
        if (userFocusSectors.isNotEmpty()) parts.add("用戶關注: ${userFocusSectors.joinToString(",")}")
        if (bounceSectors.isNotEmpty()) parts.add("回彈板塊: ${bounceSectors.take(3).joinToString(",") { it.sectorName }}")
        parts.add("大盤: ${indexSnapshot.tripleVote}")
        return parts.joinToString(" | ")
    }
}
