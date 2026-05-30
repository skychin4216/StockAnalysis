package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngine
import com.chin.stockanalysis.strategy.data.StockScreener
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 历史交易日回测引擎
 *
 * 当用户选择"前N个交易日"时：
 * 1. 从 daily_snapshot 表读取各日期的历史数据
 * 2. 逐日转换为 StockRealtime → 执行策略筛选
 * 3. 自动对比下一个交易日的实际涨跌
 * 4. 生成准确率 + 平均收益报告
 *
 * ### 使用方式
 * ```kotlin
 * val engine = HistoricalBacktestEngine(context)
 * val report = engine.runHistoricalBacktest(
 *     strategies = strategies,
 *     tradingDays = 10  // 前10个交易日
 * )
 * // report.summary() → 显示每个策略的准确率/平均收益
 * ```
 */
class HistoricalBacktestEngine(private val context: Context) {

    companion object {
        private const val TAG = "HistoricalBacktest"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val db = StockDatabase.getInstance(context)

    /**
     * 执行历史回测
     * @param strategies 启用的策略列表
     * @param tradingDays 回测的天数（1/3/5/10/30/50/100）
     * @return 回测报告
     */
    suspend fun runHistoricalBacktest(
        strategies: List<Strategy>,
        tradingDays: Int,
        targetDate: String? = null
    ): HistoricalBacktestReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "━━━ 开始历史回测: ${tradingDays}个交易日, ${strategies.size}个策略 ━━━")

        // 获取可用日期
        val allDates = db.dailySnapshotDao().getAvailableDates(tradingDays + 1)
        if (allDates.size < 2) {
            Log.w(TAG, "历史数据不足: 至少需要2个交易日")
            return@withContext HistoricalBacktestReport.EMPTY
        }
        val dates = allDates.take(minOf(tradingDays + 1, allDates.size)).reversed()
        Log.i(TAG, "可用日期: ${dates.size}个 (${dates.first()} → ${dates.last()})")

        val strategyReports = mutableListOf<StrategyBacktestReport>()

        for (strategy in strategies) {
            val report = backtestOneStrategy(strategy, dates)
            if (report.totalDays > 0) strategyReports.add(report)
        }

        HistoricalBacktestReport(
            dateRange = "${dates.first()} ~ ${dates.last()}",
            totalDays = dates.size - 1,
            strategyReports = strategyReports.sortedByDescending { it.buyAccuracy }
        )
    }

    private suspend fun backtestOneStrategy(
        strategy: Strategy,
        dates: List<String>
    ): StrategyBacktestReport {
        var totalBuys = 0
        var correctBuys = 0
        var totalReturn = 0.0
        var maxGain = 0.0
        var maxLoss = 0.0
        var totalScanHits = 0

        val dayDetails = mutableListOf<DayBacktestDetail>()

        for (i in 0 until dates.size - 1) {
            val today = dates[i]
            val tomorrow = dates[i + 1]

            try {
                // 1. 从数据库读取当日历史数据
                val todaySnapshots = db.dailySnapshotDao().getByDate(today)
                if (todaySnapshots.isEmpty()) continue

                // 2. 转换为 StockRealtime
                val stockList = todaySnapshots.map { snap ->
                    StockRealtime(
                        code = snap.code, name = snap.name,
                        price = snap.close, open = snap.open,
                        yestClose = if (i > 0) {
                            db.dailySnapshotDao().getByDate(dates[i - 1])
                                .find { it.code == snap.code }?.close ?: snap.open * 0.99
                        } else snap.open * 0.99,
                        high = snap.high, low = snap.low,
                        volume = snap.volume, amount = snap.amount,
                        changePercent = snap.changePct,
                        changeAmount = snap.close * snap.changePct / 100,
                        timestamp = System.currentTimeMillis()
                    )
                }

                // 3. 执行策略筛选（直接调用打分逻辑）
                val signals = executeStrategyOnHistoricalData(strategy, stockList)
                if (signals.isEmpty()) continue
                totalScanHits += signals.size

                // 4. 读取次日数据对比
                val tomorrowSnapshots = db.dailySnapshotDao().getByDate(tomorrow)
                if (tomorrowSnapshots.isEmpty()) continue

                var dayBuys = 0; var dayCorrect = 0
                for (signal in signals.take(5)) {
                    val tomorrowSnap = tomorrowSnapshots.find { it.code == signal.stockCode } ?: continue
                    val actualPct = tomorrowSnap.changePct
                    val isCorrect = when (signal.action) {
                        SignalAction.BUY -> actualPct > 0
                        SignalAction.WATCH -> true
                        SignalAction.HOLD -> true
                        SignalAction.SELL -> actualPct < 0
                        else -> null
                    }

                    // 保存预测记录
                    db.strategyPredictionDao().insertAll(listOf(
                        StrategyPredictionEntity(
                            strategyId = strategy.id, strategyName = strategy.name,
                            date = today, stockCode = signal.stockCode,
                            stockName = signal.stockName,
                            predictedScore = signal.strength,
                            predictedAction = signal.action.name,
                            actualNextDayPct = actualPct,
                            wasCorrect = isCorrect,
                            deviation = actualPct - 5.0
                        )
                    ))

                    if (signal.action == SignalAction.BUY) {
                        dayBuys++
                        if (isCorrect == true) dayCorrect++
                        totalReturn += actualPct
                        if (actualPct > maxGain) maxGain = actualPct
                        if (actualPct < maxLoss) maxLoss = actualPct
                    }
                }

                totalBuys += dayBuys
                correctBuys += dayCorrect

                if (dayBuys > 0) {
                    dayDetails.add(DayBacktestDetail(
                        date = today, buys = dayBuys, correct = dayCorrect,
                        avgReturn = totalReturn / totalBuys
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "回测异常 $today: ${e.message}")
            }
        }

        return StrategyBacktestReport(
            strategyId = strategy.id,
            strategyName = strategy.name,
            totalDays = dates.size - 1,
            totalBuys = totalBuys,
            correctBuys = correctBuys,
            buyAccuracy = if (totalBuys > 0) correctBuys.toFloat() / totalBuys else 0f,
            avgReturn = if (totalBuys > 0) totalReturn / totalBuys else 0.0,
            maxGain = maxGain,
            maxLoss = maxLoss,
            totalHits = totalScanHits,
            dayDetails = dayDetails
        )
    }

    /**
     * 在历史数据上执行策略筛选
     * 直接调用策略的算分逻辑，不依赖 StockScreener API
     */
    private suspend fun executeStrategyOnHistoricalData(
        strategy: Strategy,
        stockList: List<StockRealtime>
    ): List<StrategySignal> {
        return try {
            // 调用 screenWithData 使用历史预加载数据，避免实时 API
            val result = strategy.screenWithData(stockList)
            result.getOrNull()?.signals ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "策略执行异常 ${strategy.id}: ${e.message}")
            emptyList()
        }
    }
}

// ══════════════════════════════════════
// 回测结果模型
// ══════════════════════════════════════

data class HistoricalBacktestReport(
    val dateRange: String,
    val totalDays: Int,
    val strategyReports: List<StrategyBacktestReport>
) {
    companion object { val EMPTY = HistoricalBacktestReport("", 0, emptyList()) }

    fun summary(): String = buildString {
        appendLine("━━━ 📊 策略历史回测报告 ━━━")
        appendLine("期间: $dateRange ($totalDays 个交易日)")
        appendLine()
        for ((i, report) in strategyReports.withIndex()) {
            appendLine("${i + 1}. ${report.strategyName}")
            appendLine("   准确率: ${"%.1f".format(report.buyAccuracy * 100)}% (${report.correctBuys}/${report.totalBuys})")
            appendLine("   平均收益: ${"%.2f".format(report.avgReturn)}%")
            appendLine("   最大收益: ${"%.2f".format(report.maxGain)}% | 最大亏损: ${"%.2f".format(report.maxLoss)}%")
            appendLine("   评级: ${report.grade}")
        }
    }
}

data class StrategyBacktestReport(
    val strategyId: String,
    val strategyName: String,
    val totalDays: Int,
    val totalBuys: Int,
    val correctBuys: Int,
    val buyAccuracy: Float,
    val avgReturn: Double,
    val maxGain: Double,
    val maxLoss: Double,
    val totalHits: Int,
    val dayDetails: List<DayBacktestDetail> = emptyList()
) {
    val grade: String get() = when {
        buyAccuracy >= 0.7f && avgReturn > 2.0 -> "A (优秀)"
        buyAccuracy >= 0.55f && avgReturn > 0 -> "B (良好)"
        buyAccuracy >= 0.4f -> "C (一般)"
        else -> "D (待优化)"
    }
}

data class DayBacktestDetail(
    val date: String,
    val buys: Int,
    val correct: Int,
    val avgReturn: Double
)