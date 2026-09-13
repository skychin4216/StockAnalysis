package com.chin.stockanalysis.strategy.sector

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 策略市场统一上下文
 *
 * 一次性整合所有策略需要的市场数据，避免在 量化选股/中线量化/短线量化 中重复调用：
 * - 用户关注板块（UserMarketMemory）
 * - 多周期热门板块（今日 / 周 / 月 / 季度）
 * - 大盘指数 K 线（上证 / 深证 / 创业板）
 * - 回弹板块（SectorBounceFactor）
 * - AI 板块大年检测
 *
 * 使用方式：
 * ```kotlin
 * val ctx = StrategyMarketContext.build(requireContext(), selectedDate)
 * // 后续直接读取属性，无需再查数据库
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
    val indexSnapshot: IndexSnapshot,
    /** 轮动引擎预测的明日热门板块（动量延续+资金流），供板块聚焦加分 */
    val rotationPredictedSectors: List<String> = emptyList()
) {
    companion object {
        private const val TAG = "StrategyMarketContext"

        /** 缓存：避免短时间内重复构建 */
        private var cached: StrategyMarketContext? = null
        private var cachedAtMs: Long = 0L
        private const val CACHE_TTL_MS = 300_000L // 5 分钟

        /** 清空缓存（用户修改关注板块后调用） */
        fun invalidateCache() {
            cached = null
            cachedAtMs = 0L
            Log.i(TAG, "缓存已清空")
        }

        /**
         * 构建市场上下文（带缓存）
         * @param context Android Context
         * @param selectedDate 选定交易日（默认最新）
         * @param forceRefresh 强制刷新缓存
         */
        suspend fun build(
            context: Context,
            selectedDate: String? = null,
            forceRefresh: Boolean = false
        ): StrategyMarketContext = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!forceRefresh && cached != null && (now - cachedAtMs) < CACHE_TTL_MS) {
                Log.i(TAG, "使用缓存的市场上下文（${(now - cachedAtMs) / 1000}秒前）")
                return@withContext cached!!
            }

            val db = StockDatabase.getInstance(context)
            val memory = UserMarketMemory(context)
            val bounceFactor = SectorBounceFactor(db)
            val targetDate = selectedDate ?: try {
                db.dailySnapshotDao().getAvailableDates(1).firstOrNull()
            } catch (_: Exception) { null }

            Log.i(TAG, "构建市场上下文...")

            // 1. 用户关注板块
            val userFocus = memory.getActiveSectors()

            // 2. 多周期热门板块
            // 今日热门：优先用东方财富实时数据（EastMoneyHotSectorSource）
            val todayHot = fetchTodayHotFromAPI() ?: fetchHotSectors(db, days = 1, topN = 3)

            // 周/月/季度热门：用 AIHotSectorProvider（AI查询+缓存，不依赖 sector_daily_record）
            val aiSectors = try {
                com.chin.stockanalysis.strategy.data.AIHotSectorProvider.getHotSectors(context)
            } catch (_: Exception) { null }
            val weeklyHot = aiSectors?.weeklySectors?.take(5) ?: emptyList()
            val monthlyHot = aiSectors?.monthlySectors?.take(5) ?: emptyList()
            val quarterlyHot = aiSectors?.annualSectors?.take(5) ?: emptyList()

            // 3. 回弹板块
            val bounces = try { bounceFactor.detectBounceSectors(targetDate) } catch (_: Exception) { emptyList() }

            // 4. AI 板块大年（异步检测，失败不阻塞）
            val yearDetection = try { memory.detectSectorYearByIndex() } catch (_: Exception) { "检测失败" }
            if (memory.aiYearDetection == "未检测" || memory.aiYearDetection == "检测失败") {
                memory.aiYearDetection = yearDetection
            }

            // 5. 轮动预测板块（动量延续 + 资金流向，数据不足时为空列表）
            val rotationPredicted = try {
                com.chin.stockanalysis.strategy.backtest.SectorRotationEngine(context)
                    .predictTomorrow(6)
                    .map { it.sectorName }
            } catch (_: Exception) { emptyList() }

            // 6. 大盘指数快照
            val indexSnap = buildIndexSnapshot(db, targetDate)

            val ctx = StrategyMarketContext(
                userFocusSectors = userFocus,
                todayHotSectors = todayHot,
                weeklyHotSectors = weeklyHot,
                monthlyHotSectors = monthlyHot,
                quarterlyHotSectors = quarterlyHot,
                bounceSectors = bounces,
                aiYearDetection = yearDetection,
                indexSnapshot = indexSnap,
                rotationPredictedSectors = rotationPredicted
            )

            cached = ctx
            cachedAtMs = now
            Log.i(TAG, "市场上下文构建完成：今日热门${todayHot.size}个，回弹板块${bounces.size}个，用户关注${userFocus.size}个")
            ctx
        }

        /** 从东方财富实时 API 获取今日热门板块（概念板块+行业板块 Top3） */
        private fun fetchTodayHotFromAPI(): List<String>? {
            val all = EastMoneyHotSectorSource.conceptSectors + EastMoneyHotSectorSource.industrySectors
            if (all.isEmpty()) return null
            return all.sortedByDescending { it.changePercent }
                .take(3)
                .map { it.name }
                .distinct()
        }

        /** 获取指定周期内的热门板块 TopN */
        private suspend fun fetchHotSectors(db: StockDatabase, days: Int, topN: Int): List<String> {
            // 主路径：sector_daily_record 表
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

        /** 构建三大指数快照 */
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

    /** 大盘指数快照 */
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
                "BULLISH" -> "多头排列（MA5>MA10>MA20），大盘处于上升趋势"
                "BEARISH" -> "空头排列（MA5<MA10<MA20），大盘处于下降趋势"
                else -> "均线纠缠，大盘震荡格局"
            }
            return "大盘方向: $tripleVote | 上证${shDirection}/深证${szDirection}/创业板${cyDirection} | $trend | 上证最新日涨跌幅: ${"%.2f".format(shLatestChange)}% | 上证MA5=${"%.2f".format(shMA5)} MA10=${"%.2f".format(shMA10)} MA20=${"%.2f".format(shMA20)}"
        }
    }

    // ════════════════════════════════════════
    // 便捷查询方法
    // ════════════════════════════════════════

    /** 获取用户关注板块对某股票的权重加成 */
    fun getFocusBoostForStock(stockName: String): Int {
        return if (userFocusSectors.any { stockName.contains(it) || it.contains(stockName.take(2)) }) 15 else 0
    }

    /** 获取某股票所属回弹板块的回调加分（回调 N 天 + N 分） */
    fun getBounceBoostForStock(stockName: String): Int {
        val matched = bounceSectors.find {
            stockName.contains(it.sectorName) || it.sectorName.contains(stockName.take(2))
        } ?: return 0
        return (-matched.recentDropPct / 1.0).toInt().coerceAtMost(5).coerceAtLeast(1)
    }

    /** 获取某股票在今日热门板块中的排名（未命中返回 -1） */
    fun getTodayHotRank(stockName: String): Int {
        return todayHotSectors.indexOfFirst {
            stockName.contains(it) || it.contains(stockName.take(2))
        }
    }

    /** 获取轮动预测板块对某股票的加分（命中前3 +12，其余 +8） */
    fun getRotationBoostForStock(stockName: String): Int {
        val idx = rotationPredictedSectors.indexOfFirst {
            stockName.contains(it) || it.contains(stockName.take(2))
        }
        if (idx < 0) return 0
        return if (idx < 3) 12 else 8
    }

    /** 是否命中轮动预测板块 */
    fun isInRotationPredicted(stockName: String): Boolean {
        return rotationPredictedSectors.any {
            stockName.contains(it) || it.contains(stockName.take(2))
        }
    }

    /** 是否属于任意周期热门板块 */
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

    /** 生成简洁摘要（用于日志或 UI 显示） */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (todayHotSectors.isNotEmpty()) parts.add("今日热门: ${todayHotSectors.joinToString(",")}")
        if (weeklyHotSectors.isNotEmpty()) parts.add("周热门: ${weeklyHotSectors.joinToString(",")}")
        if (rotationPredictedSectors.isNotEmpty()) parts.add("轮动预测: ${rotationPredictedSectors.take(3).joinToString(",")}")
        if (userFocusSectors.isNotEmpty()) parts.add("用户关注: ${userFocusSectors.joinToString(",")}")
        if (bounceSectors.isNotEmpty()) parts.add("回弹板块: ${bounceSectors.take(3).joinToString(",") { it.sectorName }}")
        parts.add("大盘: ${indexSnapshot.tripleVote}")
        return parts.joinToString(" | ")
    }
}
