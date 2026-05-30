package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.models.ScreeningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class BacktestEngine(private val context: Context) {

    companion object {
        private const val TAG = "BacktestEngine"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val db: StockDatabase by lazy { StockDatabase.getInstance(context) }
    private val snapshotDao get() = db.dailySnapshotDao()
    private val predictionDao get() = db.strategyPredictionDao()
    private val weightSnapshotDao get() = db.strategyWeightSnapshotDao()

    /** 将实时行情列表保存为当日快照 */
    suspend fun saveDailySnapshot(stocks: List<StockRealtime>) {
        val today = LocalDate.now().format(DATE_FMT)
        val entities = stocks.map { stock ->
            DailySnapshotEntity(
                code = stock.code, name = stock.name, date = today,
                open = stock.open, close = stock.price, high = stock.high, low = stock.low,
                volume = stock.volume, amount = stock.amount,
                changePct = stock.changePercent, turnoverRate = 0.0, mainNetInflow = 0.0
            )
        }
        snapshotDao.insertAll(entities)
        Log.d(TAG, "保存 ${entities.size} 条行情快照: $today")
    }

    /** 保存策略预测结果 */
    suspend fun savePredictions(strategyId: String, strategyName: String, result: ScreeningResult) {
        val today = LocalDate.now().format(DATE_FMT)
        val entities = result.signals.map { signal ->
            StrategyPredictionEntity(
                strategyId = strategyId, strategyName = strategyName, date = today,
                stockCode = signal.stockCode, stockName = signal.stockName,
                predictedScore = signal.strength, predictedAction = signal.action.name
            )
        }
        predictionDao.insertAll(entities)
        Log.d(TAG, "保存 ${entities.size} 条预测: $strategyId @ $today")
    }

    /** 保存策略权重快照 */
    suspend fun saveWeightSnapshot(strategyId: String, weightFactorsJson: String, totalScore: Int, hitCount: Int) {
        val today = LocalDate.now().format(DATE_FMT)
        weightSnapshotDao.insert(
            StrategyWeightSnapshotEntity(
                strategyId = strategyId, date = today,
                weightJson = weightFactorsJson, totalScore = totalScore, hitCount = hitCount
            )
        )
    }

    /** 超短线评估（T+1） */
    suspend fun evaluateUltraShort(strategyId: String): EvaluationResult = withContext(Dispatchers.IO) {
        val predictions = predictionDao.getByStrategy(strategyId, 100)
        if (predictions.isEmpty()) return@withContext EvaluationResult.EMPTY

        var totalBuy = 0
        var correctBuy = 0
        var totalReturn = 0.0
        var maxDrawdown = 0.0
        val deviations = mutableListOf<DayDeviation>()

        for (pred in predictions) {
            val nextDate: String = getNextTradingDay(pred.date)
            val nextSnapshots: List<DailySnapshotEntity> = snapshotDao.getByDate(nextDate)
            val nextSnapshot: DailySnapshotEntity? = nextSnapshots.firstOrNull { s: DailySnapshotEntity -> s.code == pred.stockCode }
            if (nextSnapshot == null) continue

            val actualPct: Double = nextSnapshot.changePct
            val isCorrect: Boolean? = when (pred.predictedAction) {
                "BUY" -> actualPct > 0
                "WATCH" -> actualPct in -2.0..2.0
                "HOLD" -> true
                "SELL" -> actualPct < 0
                else -> null
            }
            val deviation: Double = if (pred.predictedAction == "BUY") actualPct - 5.0 else actualPct

            predictionDao.updateResult(id = pred.id, pct = actualPct, correct = isCorrect, dev = deviation)

            if (pred.predictedAction == "BUY") {
                totalBuy++
                if (isCorrect == true) correctBuy++
                totalReturn += actualPct
                if (actualPct < maxDrawdown) maxDrawdown = actualPct
            }

            if (Math.abs(deviation) > 5.0) {
                deviations.add(DayDeviation(
                    date = pred.date, stockCode = pred.stockCode, stockName = pred.stockName,
                    predictedScore = pred.predictedScore, actualPct = actualPct, deviation = deviation
                ))
            }
        }

        val accuracy = if (totalBuy > 0) correctBuy.toFloat() / totalBuy else 0f
        val avgReturn = if (totalBuy > 0) totalReturn / totalBuy else 0.0
        val totalPredictions = predictions.size
        val totalCorrect = predictions.count { p: StrategyPredictionEntity -> p.wasCorrect == true }

        EvaluationResult(
            strategyId = strategyId, totalPredictions = totalPredictions, totalCorrect = totalCorrect,
            totalBuySignals = totalBuy, correctBuySignals = correctBuy,
            overallAccuracy = if (totalPredictions > 0) totalCorrect.toFloat() / totalPredictions else 0f,
            buyAccuracy = accuracy, avgReturn = avgReturn, maxDrawdown = maxDrawdown,
            topDeviations = deviations.sortedByDescending { d: DayDeviation -> Math.abs(d.deviation) }.take(10)
        )
    }

    /** 中长线评估（5日窗口） */
    suspend fun evaluateMidLong(strategyId: String, windowDays: Int = 5): EvaluationResult = withContext(Dispatchers.IO) {
        val predictions = predictionDao.getByStrategy(strategyId, 100)
        if (predictions.isEmpty()) return@withContext EvaluationResult.EMPTY

        var totalBuy = 0
        var correctBuy = 0
        var totalReturn = 0.0
        var maxDrawdown = 0.0
        var totalRecoveryDays = 0
        var recoveryCount = 0

        for (pred in predictions) {
            if (pred.predictedAction != "BUY") continue

            val futureDate: String = getFutureTradingDay(pred.date, windowDays)
            val futureShots: List<DailySnapshotEntity> = snapshotDao.getByDate(futureDate)
            val futureSnapshot: DailySnapshotEntity? = futureShots.firstOrNull { s: DailySnapshotEntity -> s.code == pred.stockCode }

            if (futureSnapshot != null) {
                val pct: Double = futureSnapshot.changePct
                predictionDao.update5DayResult(pred.id, pct)
                totalBuy++
                totalReturn += pct
                if (pct > 0) correctBuy++
                if (pct < maxDrawdown) maxDrawdown = pct

                if (pct < -2.0) {
                    val recoveryDays = findRecoveryDays(pred.stockCode, pred.date, windowDays)
                    if (recoveryDays > 0) { totalRecoveryDays += recoveryDays; recoveryCount++ }
                }
            }
        }

        EvaluationResult(
            strategyId = strategyId, totalPredictions = predictions.size,
            totalBuySignals = totalBuy, correctBuySignals = correctBuy,
            buyAccuracy = if (totalBuy > 0) correctBuy.toFloat() / totalBuy else 0f,
            avgReturn = if (totalBuy > 0) totalReturn / totalBuy else 0.0,
            maxDrawdown = maxDrawdown,
            avgRecoveryDays = if (recoveryCount > 0) totalRecoveryDays.toFloat() / recoveryCount else 0f
        )
    }

    suspend fun reEvaluate(strategyId: String, targetDate: String) {
        Log.d(TAG, "重新评估 $strategyId @ $targetDate")
    }

    suspend fun getAvailableDates(limit: Int = 100): List<String> = snapshotDao.getAvailableDates(limit)
    suspend fun getAllAccuracyStats(): List<StrategyAccuracyStat> = predictionDao.getAccuracyStats()
    suspend fun getTopDeviations(limit: Int = 20): List<StrategyPredictionEntity> = predictionDao.getTopDeviations(limit)
    suspend fun getPredictionsByDate(date: String): List<StrategyPredictionEntity> = predictionDao.getByDate(date)

    private fun getNextTradingDay(date: String): String {
        val d = LocalDate.parse(date, DATE_FMT).plusDays(1)
        return when (d.dayOfWeek) {
            DayOfWeek.SATURDAY -> d.plusDays(2).format(DATE_FMT)
            DayOfWeek.SUNDAY -> d.plusDays(1).format(DATE_FMT)
            else -> d.format(DATE_FMT)
        }
    }

    private fun getFutureTradingDay(date: String, days: Int): String {
        var d = LocalDate.parse(date, DATE_FMT)
        var added = 0
        while (added < days) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) added++
        }
        return d.format(DATE_FMT)
    }

    private suspend fun findRecoveryDays(stockCode: String, fromDate: String, maxDays: Int): Int {
        val d = LocalDate.parse(fromDate, DATE_FMT)
        val fromShots: List<DailySnapshotEntity> = snapshotDao.getByDate(fromDate)
        val fromPrice: Double = fromShots.firstOrNull { s: DailySnapshotEntity -> s.code == stockCode }?.close ?: return -1

        for (i in 1..maxDays) {
            val checkDate: String = d.plusDays(i.toLong()).format(DATE_FMT)
            val checkShots: List<DailySnapshotEntity> = snapshotDao.getByDate(checkDate)
            val checkSnapshot: DailySnapshotEntity? = checkShots.firstOrNull { s: DailySnapshotEntity -> s.code == stockCode }
            if (checkSnapshot != null && checkSnapshot.close >= fromPrice) return i
        }
        return -1
    }

    suspend fun cleanupOldData() {
        val cutoffDate = LocalDate.now().minusDays(120).format(DATE_FMT)
        snapshotDao.deleteOlderThan(cutoffDate)
        predictionDao.deleteOlderThan(cutoffDate)
        Log.d(TAG, "清理历史数据完成")
    }

    suspend fun getDataStats(): String {
        val dates = snapshotDao.getAvailableDates(100)
        return "历史数据: ${dates.size}个交易日"
    }
}

data class EvaluationResult(
    val strategyId: String,
    val totalPredictions: Int = 0, val totalCorrect: Int = 0,
    val totalBuySignals: Int = 0, val correctBuySignals: Int = 0,
    val overallAccuracy: Float = 0f, val buyAccuracy: Float = 0f,
    val avgReturn: Double = 0.0, val maxDrawdown: Double = 0.0,
    val avgRecoveryDays: Float = 0f,
    val topDeviations: List<DayDeviation> = emptyList()
) {
    companion object { val EMPTY = EvaluationResult(strategyId = "") }
    val isSignificant: Boolean get() = totalPredictions >= 5
    val grade: String get() = when {
        buyAccuracy >= 0.7f && avgReturn > 2.0 -> "A (优秀)"
        buyAccuracy >= 0.55f && avgReturn > 0 -> "B (良好)"
        buyAccuracy >= 0.4f -> "C (一般)"
        else -> "D (待优化)"
    }

    fun summary(): String = buildString {
        appendLine("策略: $strategyId")
        appendLine("  总预测: $totalPredictions | 总正确: $totalCorrect | 综合准确率: ${"%.1f".format(overallAccuracy * 100)}%")
        appendLine("  买入信号: $totalBuySignals | 正确: $correctBuySignals | 买入准确率: ${"%.1f".format(buyAccuracy * 100)}%")
        appendLine("  平均收益: ${"%.2f".format(avgReturn)}% | 最大回撤: ${"%.2f".format(maxDrawdown)}%")
        if (avgRecoveryDays > 0) appendLine("  平均回调修复: ${"%.1f".format(avgRecoveryDays)}天")
        appendLine("  评级: $grade")
    }
}

data class DayDeviation(
    val date: String, val stockCode: String, val stockName: String,
    val predictedScore: Int, val actualPct: Double, val deviation: Double
)