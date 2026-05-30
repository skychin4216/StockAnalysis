package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ## 板块轮动分析引擎
 *
 * 每日记录板块排行 → 分析动量/轮动规律 → 预测下一热门板块
 *
 * ### 核心模型
 * 1. **动量延续模型** — 近N日涨幅TOP-K板块预测持续走强
 * 2. **资金流向模型** — 主力净流入连续递增的板块看多
 * 3. **板块相关性** — 发现跟涨板块（A涨→B涨概率>70%）
 * 4. **轮动速度** — 判断市场风格（抱团/快速轮动/散乱）
 * 5. **卡方轮动** — 统计历史状态转移概率
 *
 * ### 使用方式
 * ```kotlin
 * val engine = SectorRotationEngine(context)
 * engine.saveDailySectorData(sectors)        // 每日收盘后保存
 * val predictions = engine.predictTomorrow()  // 预测明日热门板块
 * val correlation = engine.getCorrelation("BK0478") // 板块相关性
 * ```
 */
class SectorRotationEngine(private val context: Context) {

    companion object {
        private const val TAG = "SectorRotation"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(context)
    private val dao = db.sectorDailyRecordDao()

    // ══════════════════════════════════════
    // 板块每日记录保存
    // ══════════════════════════════════════

    /**
     * 保存当日板块数据（从 EastMoneyHotSectorSource 获取）
     */
    suspend fun saveDailySectorData() = withContext(Dispatchers.IO) {
        try {
            val today = LocalDate.now().format(DATE_FMT)
            val industrySectors = EastMoneyHotSectorSource.industrySectors
            val conceptSectors = EastMoneyHotSectorSource.conceptSectors

            val allSectors = (industrySectors + conceptSectors)
                .distinctBy { it.code }
                .sortedByDescending { it.compositeScore }

            val entities = allSectors.mapIndexed { index, sector ->
                val isHot = when {
                    sector.changePercent >= 3.0 && sector.mainNetInflow > 50 -> "S"
                    sector.changePercent >= 2.0 && sector.mainNetInflow > 30 -> "A"
                    sector.changePercent >= 1.0 -> "B"
                    else -> "C"
                }

                // 查询连续热门天数
                val consecutiveDays = calculateConsecutiveHotDays(sector.code, today, isHot)

                SectorDailyRecordEntity(
                    date = today,
                    sectorCode = sector.code,
                    sectorName = sector.name,
                    changePct = sector.changePercent,
                    mainNetInflow = sector.mainNetInflow,
                    hotScore = sector.hotScore,
                    compositeScore = sector.compositeScore,
                    rank = index + 1,
                    isHot = isHot,
                    consecutiveHotDays = consecutiveDays
                )
            }

            dao.insertAll(entities)
            Log.i(TAG, "保存 ${entities.size} 条板块记录: $today (S级${entities.count { it.isHot == "S" }})")

            // 清理旧数据
            val cutoffDate = LocalDate.now().minusDays(120).format(DATE_FMT)
            dao.deleteOlderThan(cutoffDate)
        } catch (e: Exception) {
            Log.e(TAG, "板块数据保存失败: ${e.message}", e)
        }
    }

    // ══════════════════════════════════════
    // 动量预测
    // ══════════════════════════════════════

    data class SectorPrediction(
        val sectorCode: String,
        val sectorName: String,
        val momentum: Double,          // 动量分数
        val capitalScore: Double,      // 资金流分数
        val confidence: Double,        // 综合置信度 0-1
        val lastChangePct: Double      // 最近涨跌幅
    )

    /**
     * 预测明日热门板块（动量延续 + 资金流向综合）
     */
    suspend fun predictTomorrow(topK: Int = 5): List<SectorPrediction> = withContext(Dispatchers.IO) {
        val records = dao.getRecentDays(20)
        if (records.size < 10) return@withContext emptyList()

        val bySector = records.groupBy { it.sectorCode }
        val predictions = mutableListOf<SectorPrediction>()

        for ((code, history) in bySector) {
            if (history.size < 5) continue
            val sorted = history.sortedByDescending { it.date }

            // 1. 动量延续：近3日涨幅加权平均
            val momentum3Day = sorted.take(3).sumOf { it.changePct * (if (sorted.indexOf(it) == 0) 0.5 else 0.25) }

            // 2. 资金流向：连续流入加分
            val recentFlows = sorted.take(5)
            val capitalTrend = if (recentFlows.all { it.mainNetInflow > 0 }) 1.0
            else if (recentFlows.take(3).all { it.mainNetInflow > 0 }) 0.6
            else if (recentFlows.first().mainNetInflow > 0) 0.3
            else 0.0

            // 3. 综合置信度
            val confidence = (momentum3Day.coerceIn(-5.0, 10.0) + 5) / 15 * 0.5 +
                    capitalTrend * 0.3 +
                    (if (sorted.first().isHot in listOf("S", "A")) 0.2 else 0.0)

            predictions.add(SectorPrediction(
                sectorCode = code,
                sectorName = sorted.first().sectorName,
                momentum = momentum3Day,
                capitalScore = capitalTrend,
                confidence = confidence.coerceIn(0.0, 1.0),
                lastChangePct = sorted.first().changePct
            ))
        }

        predictions.sortedByDescending { it.confidence }.take(topK)
    }

    // ══════════════════════════════════════
    // 板块相关性
    // ══════════════════════════════════════

    data class SectorCorrelation(
        val targetSector: String,
        val targetName: String,
        val correlation: Double,         // 相关系数 -1~1
        val followRate: Double           // 跟涨概率
    )

    /**
     * 计算某板块与其他板块的相关性
     */
    suspend fun getCorrelations(sectorCode: String, topK: Int = 5): List<SectorCorrelation> = withContext(Dispatchers.IO) {
        val records = dao.getRecentDays(60)
        if (records.isEmpty()) return@withContext emptyList()

        val targetChanges = records.filter { it.sectorCode == sectorCode }
            .sortedBy { it.date }.map { it.changePct }
        if (targetChanges.size < 10) return@withContext emptyList()

        val bySector = records.groupBy { it.sectorCode }
        val correlations = mutableListOf<SectorCorrelation>()

        for ((code, history) in bySector) {
            if (code == sectorCode || history.size < 10) continue
            val sorted = history.sortedBy { it.date }

            // 简化Pearson相关系数
            val n = minOf(targetChanges.size, sorted.size)
            val xList = targetChanges.takeLast(n)
            val yList = sorted.takeLast(n).map { it.changePct }

            val corr = pearsonCorrelation(xList, yList)

            // 跟涨概率：目标板块涨>1%时，该板块也涨的比例
            var followCount = 0
            var totalCount = 0
            for (i in 0 until n) {
                if (xList[i] > 1.0) {
                    totalCount++
                    if (yList[i] > 0) followCount++
                }
            }
            val followRate = if (totalCount > 0) followCount.toDouble() / totalCount else 0.0

            correlations.add(SectorCorrelation(
                targetSector = code,
                targetName = sorted.first().sectorName,
                correlation = corr,
                followRate = followRate
            ))
        }

        correlations.sortedByDescending { it.correlation + it.followRate }.take(topK)
    }

    // ══════════════════════════════════════
    // 轮动速度指标
    // ══════════════════════════════════════

    /**
     * 计算轮动速度（排名变化幅度越大=轮动越快）
     * 值高 → 快速轮动（短线策略优），值低 → 抱团行情（趋势策略优）
     */
    suspend fun rotationSpeed(): Double = withContext(Dispatchers.IO) {
        val records = dao.getRecentDays(10)
        if (records.isEmpty()) return@withContext 0.0

        val byDate = records.groupBy { it.date }.mapValues { it.value.sortedBy { it.rank } }
        if (byDate.size < 3) return@withContext 0.0

        val dates = byDate.keys.sorted()
        var totalChange = 0.0
        var count = 0

        for (i in 1 until dates.size) {
            val prev = byDate[dates[i - 1]] ?: continue
            val curr = byDate[dates[i]] ?: continue
            for (prevSec in prev.take(5)) {
                val currRank = curr.find { it.sectorCode == prevSec.sectorCode }?.rank ?: 100
                totalChange += abs(currRank - prevSec.rank)
                count++
            }
        }

        if (count > 0) totalChange / count else 0.0
    }

    /**
     * 市场风格诊断
     */
    suspend fun marketStyleDiagnosis(): String = withContext(Dispatchers.IO) {
        val speed = rotationSpeed()
        val predictions = predictTomorrow(5)

        when {
            speed < 2.0 && predictions.any { it.confidence > 0.7 } ->
                "抱团行情 — 趋势策略优先，重点关注${predictions.first().sectorName}"
            speed in 2.0..5.0 ->
                "结构性轮动 — 中短线策略，跟随主力资金流向"
            speed > 5.0 ->
                "快速轮动 — 超短线策略，严格止损止盈"
            predictions.isEmpty() ->
                "数据不足，无法诊断"
            else ->
                "震荡行情 — 观望为宜"
        }
    }

    // ══════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════

    private suspend fun calculateConsecutiveHotDays(code: String, today: String, currentGrade: String): Int {
        if (currentGrade !in listOf("S", "A")) return 0
        try {
            val recent = dao.getBySectorCode(code, 5)
            var count = 1
            for (record in recent) {
                if (record.date >= today) continue
                if (record.isHot in listOf("S", "A")) count++
                else break
            }
            return count
        } catch (_: Exception) { return 0 }
    }

    /**
     * Pearson 相关系数
     */
    private fun pearsonCorrelation(x: List<Double>, y: List<Double>): Double {
        val n = x.size
        if (n != y.size || n < 2) return 0.0

        val meanX = x.average()
        val meanY = y.average()

        var cov = 0.0
        var varX = 0.0
        var varY = 0.0

        for (i in 0 until n) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            cov += dx * dy
            varX += dx * dx
            varY += dy * dy
        }

        val denominator = sqrt(varX * varY)
        return if (denominator > 0) cov / denominator else 0.0
    }
}

