package com.chin.stockanalysis.agent.pipeline.analytics

import android.content.Context
import android.content.SharedPreferences
import com.chin.stockanalysis.agent.pipeline.AgentPipelineOrchestrator
import com.chin.stockanalysis.agent.pipeline.PipelineResult
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 4 — Pipeline 歷史回測框架
 *
 * 對過去 N 天的 Pipeline 執行結果進行回溯驗證：
 * 1. 記錄每次 Pipeline 的通過標的和打分
 * 2. N 天後計算實際收益
 * 3. 統計勝率、平均收益、最大回撤
 * 4. 與基準（滬深300）比較
 *
 * 回測週期建議：
 * - 短線：5日後收益
 * - 中線：20日後收益
 * - 長線：60日後收益
 */
class PipelineBacktest(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "pipeline_backtest"
        private const val KEY_BACKTEST_RECORDS = "backtest_records"
        private const val MAX_RECORDS = 500

        /** 回測週期（交易日） */
        val BACKTEST_PERIODS = listOf(5, 20, 60)
    }

    /**
     * 記錄一次 Pipeline 執行結果
     */
    suspend fun recordPipelineResult(result: PipelineResult, date: String) {
        withContext(Dispatchers.IO) {
            val records = loadRecords().toMutableList()
            for (stock in result.stocks.filter { it.passed }) {
                records.add(
                    BacktestRecord(
                        stockCode = stock.stockCode,
                        stockName = stock.stockName,
                        pipelineDate = date,
                        score = stock.chainScore?.totalScore ?: 0,
                        barrierLevel = stock.chainScore?.barrierLevel ?: "",
                        riskLevel = stock.riskResult?.riskLevel ?: "",
                        positionAdjust = stock.sentimentResult?.positionAdjust ?: "",
                        returns5d = null,
                        returns20d = null,
                        returns60d = null
                    )
                )
            }
            // 去重並限制數量
            val deduped = records.groupBy { it.stockCode to it.pipelineDate }
                .map { (_, list) -> list.last() }
                .takeLast(MAX_RECORDS)
            saveRecords(deduped)
        }
    }

    /**
     * 更新回測收益（每天收盤後調用）
     */
    suspend fun updateReturns(context: Context) {
        withContext(Dispatchers.IO) {
            val db = StockDatabase.getInstance(context)
            val records = loadRecords().toMutableList()
            var updated = false

            for (i in records.indices) {
                val r = records[i]
                val basePrice = db.dailySnapshotDao().getByDateAndCode(r.pipelineDate, r.stockCode)?.close
                    ?: continue
                if (basePrice <= 0) continue

                // 5日收益
                if (r.returns5d == null) {
                    val d5 = addTradingDays(r.pipelineDate, 5)
                    val p5 = db.dailySnapshotDao().getByDateAndCode(d5, r.stockCode)?.close
                    if (p5 != null) { records[i] = r.copy(returns5d = (p5 - basePrice) / basePrice); updated = true }
                }

                // 20日收益
                if (r.returns20d == null) {
                    val d20 = addTradingDays(r.pipelineDate, 20)
                    val p20 = db.dailySnapshotDao().getByDateAndCode(d20, r.stockCode)?.close
                    if (p20 != null) { records[i] = r.copy(returns20d = (p20 - basePrice) / basePrice); updated = true }
                }

                // 60日收益
                if (r.returns60d == null) {
                    val d60 = addTradingDays(r.pipelineDate, 60)
                    val p60 = db.dailySnapshotDao().getByDateAndCode(d60, r.stockCode)?.close
                    if (p60 != null) { records[i] = r.copy(returns60d = (p60 - basePrice) / basePrice); updated = true }
                }
            }

            if (updated) saveRecords(records)
        }
    }

    /**
     * 獲取回測統計報告
     */
    fun getBacktestReport(): BacktestReport {
        val records = loadRecords()
        val completed5d = records.filter { it.returns5d != null }
        val completed20d = records.filter { it.returns20d != null }
        val completed60d = records.filter { it.returns60d != null }

        return BacktestReport(
            totalRecords = records.size,
            period5d = calculatePeriodStats(completed5d.map { it.returns5d!! }),
            period20d = calculatePeriodStats(completed20d.map { it.returns20d!! }),
            period60d = calculatePeriodStats(completed60d.map { it.returns60d!! }),
            scoreBucketAnalysis = analyzeScoreBuckets(records)
        )
    }

    /**
     * 按打分區間分析收益（驗證打分有效性）
     */
    private fun analyzeScoreBuckets(records: List<BacktestRecord>): List<ScoreBucket> {
        val buckets = listOf(0..39, 40..59, 60..79, 80..100)
        return buckets.map { range ->
            val bucketRecords = records.filter { it.score in range && it.returns5d != null }
            ScoreBucket(
                range = "${range.first}-${range.last}",
                count = bucketRecords.size,
                avgReturn5d = if (bucketRecords.isNotEmpty()) bucketRecords.map { it.returns5d!! }.average() * 100 else 0.0,
                winRate5d = if (bucketRecords.isNotEmpty()) bucketRecords.count { it.returns5d!! > 0 }.toDouble() / bucketRecords.size * 100 else 0.0
            )
        }
    }

    private fun calculatePeriodStats(returns: List<Double>): PeriodStats {
        if (returns.isEmpty()) return PeriodStats(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val avg = returns.average() * 100
        val winRate = returns.count { it > 0 }.toDouble() / returns.size * 100
        val maxProfit = returns.maxOrNull()!! * 100
        val maxLoss = returns.minOrNull()!! * 100
        // 簡化夏普：假設無風險利率 2%
        val sharpe = if (returns.size > 1) {
            val excess = returns.map { it * 100 - 2.0 }
            val meanExcess = excess.average()
            val std = kotlin.math.sqrt(excess.map { (it - meanExcess) * (it - meanExcess) }.average())
            if (std > 0) meanExcess / std else 0.0
        } else 0.0
        return PeriodStats(returns.size, avg, winRate, maxProfit, maxLoss, sharpe)
    }

    private fun addTradingDays(dateStr: String, days: Int): String {
        val date = java.time.LocalDate.parse(dateStr)
        return date.plusDays(days.toLong()).toString()
    }

    private fun loadRecords(): List<BacktestRecord> {
        val json = prefs.getString(KEY_BACKTEST_RECORDS, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                BacktestRecord(
                    stockCode = obj.getString("code"),
                    stockName = obj.getString("name"),
                    pipelineDate = obj.getString("date"),
                    score = obj.getInt("score"),
                    barrierLevel = obj.optString("barrier", ""),
                    riskLevel = obj.optString("risk", ""),
                    positionAdjust = obj.optString("pos", ""),
                    returns5d = if (obj.has("r5")) obj.getDouble("r5") else null,
                    returns20d = if (obj.has("r20")) obj.getDouble("r20") else null,
                    returns60d = if (obj.has("r60")) obj.getDouble("r60") else null
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveRecords(records: List<BacktestRecord>) {
        val arr = org.json.JSONArray()
        for (r in records) {
            val obj = org.json.JSONObject().apply {
                put("code", r.stockCode)
                put("name", r.stockName)
                put("date", r.pipelineDate)
                put("score", r.score)
                put("barrier", r.barrierLevel)
                put("risk", r.riskLevel)
                put("pos", r.positionAdjust)
                r.returns5d?.let { put("r5", it) }
                r.returns20d?.let { put("r20", it) }
                r.returns60d?.let { put("r60", it) }
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_BACKTEST_RECORDS, arr.toString()).apply()
    }

    // ═══════════════════════════════════════
    // 數據類
    // ═══════════════════════════════════════

    data class BacktestRecord(
        val stockCode: String,
        val stockName: String,
        val pipelineDate: String,
        val score: Int,
        val barrierLevel: String,
        val riskLevel: String,
        val positionAdjust: String,
        val returns5d: Double?,
        val returns20d: Double?,
        val returns60d: Double?
    )

    data class PeriodStats(
        val sampleSize: Int,
        val avgReturnPct: Double,
        val winRatePct: Double,
        val maxProfitPct: Double,
        val maxLossPct: Double,
        val sharpeRatio: Double
    )

    data class ScoreBucket(
        val range: String,
        val count: Int,
        val avgReturn5d: Double,
        val winRate5d: Double
    )

    data class BacktestReport(
        val totalRecords: Int,
        val period5d: PeriodStats,
        val period20d: PeriodStats,
        val period60d: PeriodStats,
        val scoreBucketAnalysis: List<ScoreBucket>
    ) {
        fun toText(): String {
            val sb = StringBuilder()
            sb.appendLine("📊 Pipeline 回測報告")
            sb.appendLine("總記錄數: $totalRecords")
            sb.appendLine()
            listOf("5日" to period5d, "20日" to period20d, "60日" to period60d).forEach { (label, stats) ->
                sb.appendLine("【$label 收益統計】")
                sb.appendLine("  樣本數: ${stats.sampleSize}")
                if (stats.sampleSize > 0) {
                    sb.appendLine("  平均收益: ${String.format("%.2f", stats.avgReturnPct)}%")
                    sb.appendLine("  勝率: ${String.format("%.1f", stats.winRatePct)}%")
                    sb.appendLine("  最大盈利: ${String.format("%.2f", stats.maxProfitPct)}%")
                    sb.appendLine("  最大虧損: ${String.format("%.2f", stats.maxLossPct)}%")
                    sb.appendLine("  夏普比率: ${String.format("%.2f", stats.sharpeRatio)}")
                }
                sb.appendLine()
            }
            sb.appendLine("【打分區間分析】")
            for (bucket in scoreBucketAnalysis) {
                sb.appendLine("  ${bucket.range}分: n=${bucket.count} 均收益=${String.format("%.2f", bucket.avgReturn5d)}% 勝率=${String.format("%.1f", bucket.winRate5d)}%")
            }
            return sb.toString()
        }
    }
}
