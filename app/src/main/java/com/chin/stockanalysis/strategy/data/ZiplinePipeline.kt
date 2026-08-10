package com.chin.stockanalysis.strategy.data

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * ## Zipline Pipeline — 因子统一计算层
 *
 * 参考 Zipline Pipeline API 设计：
 *   - 所有技术指标（MA/RSI/BB/量能）只计算一次
 *   - 策略从 Pipeline 直接取结果
 */
class ZiplinePipeline(private val context: Context) {

    companion object {
        private const val TAG = "ZiplinePipeline"
    }

    private val db = StockDatabase.getInstance(context)

    data class FactorSet(
        val ma5: Map<String, Double> = emptyMap(),
        val ma20: Map<String, Double> = emptyMap(),
        val rsi14: Map<String, Double> = emptyMap(),
        val bbUpper: Map<String, Double> = emptyMap(),
        val bbMid: Map<String, Double> = emptyMap(),
        val bbLower: Map<String, Double> = emptyMap(),
        val volumeSma10: Map<String, Long> = emptyMap(),
        val momentum5: Map<String, Double> = emptyMap(),
        val atr14: Map<String, Double> = emptyMap()
    )

    suspend fun computeAll(
        stocks: List<StockRealtime>,
        date: String,
        lookbackDays: Int = 30
    ): FactorSet = withContext(Dispatchers.IO) {
        try {
            // 只计算传入股票，大幅减少无效计算
            val targetCodes = stocks.map { it.code }.toSet()
            val availableDates = db.dailySnapshotDao().getAvailableDates(lookbackDays + 5)
                .filter { it <= date }.sorted().takeLast(lookbackDays)
            if (availableDates.isEmpty()) return@withContext FactorSet()

            val history = mutableMapOf<String, MutableList<Double>>()
            val volumes = mutableMapOf<String, MutableList<Long>>()
            for (d in availableDates) {
                try {
                    val snaps = db.dailySnapshotDao().getByDate(d)
                    for (snap in snaps) {
                        if (snap.code !in targetCodes) continue  // 只计算候选池
                        history.getOrPut(snap.code) { mutableListOf() }.add(snap.close)
                        volumes.getOrPut(snap.code) { mutableListOf() }.add(snap.volume)
                    }
                } catch (_: Exception) { continue }
            }

            val ma5 = mutableMapOf<String, Double>()
            val ma20 = mutableMapOf<String, Double>()
            val rsi14 = mutableMapOf<String, Double>()
            val bbUpper = mutableMapOf<String, Double>()
            val bbMid = mutableMapOf<String, Double>()
            val bbLower = mutableMapOf<String, Double>()
            val vol10 = mutableMapOf<String, Long>()
            val mom5 = mutableMapOf<String, Double>()
            val atr14 = mutableMapOf<String, Double>()

            for ((code, prices) in history) {
                if (prices.size < 5) continue
                ma5[code] = prices.takeLast(5).average()
                mom5[code] = (prices.last() - prices.first()) / prices.first() * 100
                vol10[code] = (volumes[code]?.takeLast(10)?.average()?.toLong() ?: 0L)

                if (prices.size >= 14) {
                    rsi14[code] = computeRSI(prices, 14)
                    atr14[code] = computeATR(prices)
                }
                if (prices.size >= 20) {
                    ma20[code] = prices.takeLast(20).average()
                    val std = kotlin.math.sqrt(prices.takeLast(20).map { (it - ma20[code]!!).pow(2) }.average())
                    bbMid[code] = ma20[code]!!
                    bbUpper[code] = ma20[code]!! + 2.0 * std
                    bbLower[code] = ma20[code]!! - 2.0 * std
                }
            }

            Log.i(TAG, "Pipeline: ${availableDates.size}天 × ${history.size}只 → MA5:${ma5.size} MA20:${ma20.size} RSI14:${rsi14.size} BB:${bbUpper.size}")
            FactorSet(ma5 = ma5, ma20 = ma20, rsi14 = rsi14, bbUpper = bbUpper, bbMid = bbMid, bbLower = bbLower,
                volumeSma10 = vol10, momentum5 = mom5, atr14 = atr14)
        } catch (e: Exception) {
            Log.w(TAG, "Pipeline 异常: ${e.message}"); FactorSet()
        }
    }

    private fun computeRSI(prices: List<Double>, period: Int): Double {
        return com.chin.stockanalysis.strategy.analysis.RsiCalculator.compute(prices, period)
    }

    private fun computeATR(prices: List<Double>): Double {
        if (prices.size < 15) return 0.0
        val trValues = (1 until prices.size).map { kotlin.math.abs(prices[it] - prices[it - 1]) }
        return trValues.takeLast(14).average()
    }
}