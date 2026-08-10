package com.chin.stockanalysis.strategy.strategies

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## 板块轮动加速度策略
 *
 * 追踪板块资金净流入排名的「加速度」而非静态排名。
 * 连续 3 日排名上升的板块 → 选板块内涨幅前 3 且量比 > 1.5 的个股。
 *
 * 数据来源：DailySnapshotEntity.mainNetInflow（主力净流入）按板块聚合。
 * 由于本地无板块归属表，使用股票名称/代码前缀做简化分组，
 * 或从 HotSectorNewsUpdater 的缓存中读取板块归属。
 *
 * 评分：板块加速度(40%) + 个股相对强度(30%) + 量能(30%)
 */
class SectorRotationStrategy(
    private val context: Context,
    private val screener: StockScreener? = null
) : Strategy {

    override val id = "sector_rotation"
    override var name = "板块轮动加速度"
    override var description = "追踪板块资金流入加速度，选加速流入板块中的强势个股"
    override val category = StrategyCategory.MOMENTUM
    override val holdingPeriods = listOf(HoldingPeriod.SHORT)
    override val source = StrategySource.BUILTIN
    override val signalExpiryHours = 48

    override val config = StrategyConfig.custom(
        params = mapOf("accel_days" to 3, "top_per_sector" to 3, "volume_ratio_min" to 1.5),
        maxResults = 10
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("acceleration", "板块加速度", 40, "连续N日资金流入排名上升"),
        WeightFactor("relative", "个股强度", 30, "板块内相对涨幅排名"),
        WeightFactor("volume", "量能确认", 30, "量比>1.5确认资金进场")
    )

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val pool = screener?.scanFullMarket() ?: emptyList()
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        val startTime = System.currentTimeMillis()
        return try {
            val pool = if (config.stockPool.isNotEmpty()) {
                preloadedStocks.filter { it.code in config.stockPool }
            } else {
                preloadedStocks
            }
            screenWithPool(pool, startTime)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun isAvailable(): Boolean = true

    private suspend fun screenWithPool(pool: List<StockRealtime>, startTime: Long): Result<ScreeningResult> {
        if (pool.isEmpty()) return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = emptyList(), totalScanned = 0, scanTimeMs = System.currentTimeMillis() - startTime
        ))

        val marketDir = try { screener?.detectMarketDirection() } catch (_: Exception) { null } ?: "OSCILLATION"
        val isBearish = marketDir == "BEARISH"
        val strengthThreshold = if (isBearish) 55 else 40
        val accelDays = (config.params["accel_days"] as? Number)?.toInt() ?: 3
        val topPerSector = (config.params["top_per_sector"] as? Number)?.toInt() ?: 3
        val volumeRatioMin = (config.params["volume_ratio_min"] as? Number)?.toDouble() ?: 1.5

        val db = StockDatabase.getInstance(context)
        val dao = db.dailySnapshotDao()

        // 获取近 accelDays+1 天的可用日期
        val dates = dao.getAvailableDates(accelDays + 5).sorted().takeLast(accelDays + 1)
        if (dates.size < accelDays + 1) {
            Log.i("SR_Strategy", "历史数据不足: ${dates.size} 天")
            return Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
            ))
        }

        // 按板块（简化：用代码前缀分组）聚合每日主力净流入，计算排名变化
        // 板块分组：sh60=沪主板, sz00=深主板, sz30=创业板, sh68=科创板
        val sectorMap = mapOf(
            "sh60" to "沪主板", "sz00" to "深主板",
            "sz30" to "创业板", "sh68" to "科创板"
        )

        fun getSector(code: String): String {
            val prefix = code.take(4)
            return sectorMap[prefix] ?: "其他"
        }

        // 计算每日每板块的总主力净流入
        val dailySectorInflow = mutableListOf<Map<String, Double>>()
        for (date in dates) {
            val snaps = dao.getByDate(date)
            val sectorInflow = snaps.groupBy { getSector(it.code) }
                .mapValues { (_, stocks) -> stocks.sumOf { it.mainNetInflow } }
            dailySectorInflow.add(sectorInflow)
        }

        // 计算每日排名
        val dailyRankings = dailySectorInflow.map { inflow ->
            inflow.entries.sortedByDescending { it.value }.mapIndexed { idx, entry ->
                entry.key to (idx + 1)
            }.toMap()
        }

        // 计算加速度：排名连续上升的板块
        val sectors = dailyRankings.firstOrNull()?.keys ?: emptySet()
        val acceleratingSectors = sectors.filter { sector ->
            val rankings = dailyRankings.mapNotNull { it[sector] }
            if (rankings.size < accelDays + 1) return@filter false
            // 检查排名是否连续上升（数值连续减小）
            val recent = rankings.takeLast(accelDays + 1)
            var allRising = true
            for (i in 1 until recent.size) {
                if (recent[i] >= recent[i - 1]) { allRising = false; break }
            }
            allRising
        }

        // 如果没有加速板块，放宽条件：取排名上升最多的板块
        val targetSectors = acceleratingSectors.ifEmpty {
            sectors.mapNotNull { sector ->
                val rankings = dailyRankings.mapNotNull { it[sector] }
                if (rankings.size < 2) return@mapNotNull null
                val improvement = rankings.first() - rankings.last()
                if (improvement > 0) sector to improvement else null
            }.sortedByDescending { it.second }.take(2).map { it.first }
        }

        Log.i("SR_Strategy", "大盘: $marketDir, 加速板块: $targetSectors")

        if (targetSectors.isEmpty()) {
            return Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
            ))
        }

        // 从加速板块中选股
        val candidates = pool.filter { stock ->
            getSector(stock.code) in targetSectors &&
            stock.changePercent > 0 &&
            stock.amount > 50_000_000 &&
            stock.price > 3.0 &&
            !stock.name.contains("ST", ignoreCase = true)
        }

        // 按板块分组，每板块取涨幅前 N
        val signals = mutableListOf<StrategySignal>()
        for (sector in targetSectors) {
            val sectorStocks = candidates.filter { getSector(it.code) == sector }
                .sortedByDescending { it.changePercent }
                .take(topPerSector)

            for (stock in sectorStocks) {
                try {
                    // 量比计算
                    val history = dao.getByCode(stock.code, 10)
                    val avgVol = history.map { it.volume.toDouble() }.average().takeIf { it > 0 } ?: 1.0
                    val volumeRatio = stock.volume.toDouble() / avgVol
                    if (volumeRatio < volumeRatioMin) continue

                    // 板块加速度评分 (0-40)
                    val accelScore = if (sector in acceleratingSectors) 40 else 25

                    // 个股相对强度 (0-30)：板块内排名
                    val rankInSector = sectorStocks.indexOf(stock) + 1
                    val relativeScore = when (rankInSector) {
                        1 -> 30
                        2 -> 24
                        3 -> 18
                        else -> 12
                    }

                    // 量能评分 (0-30)
                    val volumeScore = when {
                        volumeRatio > 3.0 -> 30
                        volumeRatio > 2.0 -> 24
                        volumeRatio > 1.5 -> 18
                        else -> 10
                    }

                    val strength = (accelScore + relativeScore + volumeScore).coerceIn(0, 100)
                    if (strength < strengthThreshold) continue

                    signals.add(StrategySignal(
                        strategyId = id, category = category,
                        stockCode = stock.code, stockName = stock.name,
                        strength = strength,
                        reason = "${sector}加速流入 板块内#${rankInSector} 量比${"%.1f".format(volumeRatio)} 涨${"%.1f".format(stock.changePercent)}%",
                        action = if (strength >= 65) SignalAction.BUY else SignalAction.WATCH,
                        currentPrice = stock.price, changePercent = stock.changePercent
                    ))
                } catch (_: Exception) { continue }
            }
        }

        val result = signals.sortedByDescending { it.strength }.take(config.maxResults)
        Log.i("SR_Strategy", "计算完成: ${candidates.size} 候选 → ${result.size} 信号")
        return Result.success(ScreeningResult(
            strategyId = id, strategyName = name, category = category,
            signals = result, totalScanned = pool.size, scanTimeMs = System.currentTimeMillis() - startTime
        ))
    }
}
