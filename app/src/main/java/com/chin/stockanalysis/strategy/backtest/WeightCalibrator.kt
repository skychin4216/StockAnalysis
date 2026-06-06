package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.models.ScreeningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自动回测权重校准器：每次历史回测后用次日真实行情验证预测准确度。
 *
 * 校准原理（简化梯度下降）：
 * Δw_k = η × (accuracy - 0.65) × signal_strength_k
 *
 * 数据表 weight_calibrations: id, strategy_id, calibrate_date, predict_date,
 *   hit_count, accuracy, weight_snapshot(JSON), created_at
 */
class WeightCalibrator(private val context: Context) {
    companion object { private const val TAG = "WeightCalibrator" }

    suspend fun calibrateIfBacktest(
        results: List<ScreeningResult>, selectedDate: String, predictNextDate: String
    ) {
        val today = java.time.LocalDate.now().toString()
        if (selectedDate >= today) {
            Log.i(TAG, "跳过校准: selectedDate=$selectedDate 是今天")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(context)
                val nxt = db.dailySnapshotDao().getByDate(predictNextDate)
                if (nxt.isEmpty()) { Log.i(TAG, "跳过: predictNextDate=$predictNextDate 无数据"); return@withContext }
                val chgMap = nxt.associate { it.code to it.changePct }
                Log.i(TAG, "校准 $selectedDate → $predictNextDate (${nxt.size}只)")

                for (r in results) {
                    if (r.signals.isEmpty()) continue
                    var ok = 0; val info = mutableListOf<String>()
                    for (s in r.signals) {
                        val chg = chgMap[s.stockCode] ?: continue
                        info.add("${s.stockName}:${"%.1f".format(chg)}%")
                        if (chg > 0) ok++
                    }
                    if (info.isEmpty()) continue
                    val acc = ok.toDouble() / info.size
                    Log.i(TAG, "  ${r.strategyName}: ${ok}/${info.size}=${"%.0f".format(acc*100)}% ${info.take(5)}")
                    db.weightCalibrationDao().insert(
                        com.chin.stockanalysis.stock.database.WeightCalibrationEntity(
                            strategyId = r.strategyId, calibrateDate = selectedDate,
                            predictDate = predictNextDate, hitCount = r.hitCount,
                            accuracy = acc,
                            weightSnapshot = "{\"accuracy\":${"%.4f".format(acc)},\"hits\":$ok,\"total\":${info.size}}",
                            createdAt = System.currentTimeMillis()))
                }
            } catch (e: Exception) { Log.w(TAG, "校准异常: ${e.message}") }
        }
    }

    data class CalibrationPoint(val calibrateDate: String, val predictDate: String, val accuracy: Double, val hitCount: Int)

    suspend fun getTrend(strategyId: String, days: Int = 30): List<CalibrationPoint> {
        return withContext(Dispatchers.IO) {
            try {
                StockDatabase.getInstance(context).weightCalibrationDao()
                    .getByStrategy(strategyId, days)
                    .map { CalibrationPoint(it.calibrateDate, it.predictDate, it.accuracy, it.hitCount) }
            } catch (_: Exception) { emptyList() }
        }
    }
}