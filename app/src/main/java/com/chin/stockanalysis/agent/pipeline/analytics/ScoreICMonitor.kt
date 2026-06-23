package com.chin.stockanalysis.agent.pipeline.analytics

import android.content.Context
import android.content.SharedPreferences
import com.chin.stockanalysis.agent.pipeline.ChainScoreResult
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Phase 4 — Agent 2 打分 IC (Information Coefficient) 監控
 *
 * IC = 預測打分與未來實際收益的相關性
 * IC > 0.03 表示有預測能力，IC > 0.05 表示較強預測能力
 *
 * 使用方法：
 * 1. 每次 Pipeline 執行後記錄打分
 * 2. N 天後計算實際收益
 * 3. 計算 IC 值並存儲
 * 4. 當 IC 連續下降時預警，提示調整打分模型
 */
class ScoreICMonitor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "score_ic_monitor"
        private const val KEY_SCORE_RECORDS = "score_records"
        private const val KEY_IC_HISTORY = "ic_history"
        private const val MAX_RECORDS = 200
        private const val IC_CALCULATION_DAYS = 5 // 5日後計算收益

        /** IC 預警閾值 */
        const val IC_WARNING_THRESHOLD = 0.02
        const val IC_GOOD_THRESHOLD = 0.05
    }

    /**
     * 記錄一次打分結果
     *
     * @param stockCode 股票代碼
     * @param score Agent 2 總分
     * @param date 打分日期 yyyy-MM-dd
     */
    suspend fun recordScore(stockCode: String, score: Int, date: String) {
        withContext(Dispatchers.IO) {
            val records = loadRecords().toMutableList()
            records.add(ScoreRecord(stockCode, score, date, null))
            // 去重：同一天同一股票只保留最新
            val deduped = records.groupBy { it.stockCode to it.scoreDate }
                .map { (_, list) -> list.last() }
                .takeLast(MAX_RECORDS)
            saveRecords(deduped)
        }
    }

    /**
     * 計算並更新 IC 值
     * 調用時機：每天收盤後，有足夠的歷史數據時
     */
    suspend fun calculateIC(context: Context): ICResult? {
        return withContext(Dispatchers.IO) {
            val db = StockDatabase.getInstance(context)
            val records = loadRecords().filter { it.actualReturn == null }
            if (records.isEmpty()) return@withContext null

            var updatedCount = 0
            val updatedRecords = records.toMutableList()

            for (i in updatedRecords.indices) {
                val record = updatedRecords[i]
                if (record.actualReturn != null) continue

                // 查詢 N 天後的收盤價
                val futureDate = addTradingDays(record.scoreDate, IC_CALCULATION_DAYS)
                val futurePrice = db.dailySnapshotDao().getByDateAndCode(futureDate, record.stockCode)?.close
                val scoreDatePrice = db.dailySnapshotDao().getByDateAndCode(record.scoreDate, record.stockCode)?.close

                if (futurePrice != null && scoreDatePrice != null && scoreDatePrice > 0) {
                    val actualReturn = (futurePrice - scoreDatePrice) / scoreDatePrice
                    updatedRecords[i] = record.copy(actualReturn = actualReturn)
                    updatedCount++
                }
            }

            if (updatedCount > 0) {
                saveRecords(updatedRecords)
            }

            // 計算 IC
            val completedRecords = updatedRecords.filter { it.actualReturn != null }
            if (completedRecords.size < 10) return@withContext null

            val ic = computeSpearmanIC(completedRecords)
            val icHistory = loadICHistory().toMutableList()
            icHistory.add(ICEntry(System.currentTimeMillis(), ic, completedRecords.size))
            saveICHistory(icHistory.takeLast(50))

            ICResult(
                ic = ic,
                sampleSize = completedRecords.size,
                trend = analyzeTrend(icHistory),
                warning = ic < IC_WARNING_THRESHOLD,
                recommendation = when {
                    ic < 0 -> "打分與實際收益負相關，建議檢查打分邏輯"
                    ic < IC_WARNING_THRESHOLD -> "IC 過低，建議調整打分權重"
                    ic < IC_GOOD_THRESHOLD -> "IC 一般，可繼續觀察"
                    else -> "IC 良好，打分模型有效"
                }
            )
        }
    }

    /**
     * 獲取最新 IC 和歷史趨勢
     */
    fun getLatestIC(): ICResult? {
        val history = loadICHistory()
        val latest = history.lastOrNull() ?: return null
        return ICResult(
            ic = latest.ic,
            sampleSize = latest.sampleSize,
            trend = analyzeTrend(history),
            warning = latest.ic < IC_WARNING_THRESHOLD,
            recommendation = when {
                latest.ic < 0 -> "打分與實際收益負相關"
                latest.ic < IC_WARNING_THRESHOLD -> "IC 過低，建議調整"
                latest.ic < IC_GOOD_THRESHOLD -> "IC 一般"
                else -> "IC 良好"
            }
        )
    }

    /**
     * 獲取 IC 歷史趨勢文本（用於 UI 展示）
     */
    fun getICTrendText(): String {
        val history = loadICHistory()
        if (history.isEmpty()) return "暫無 IC 數據"
        val latest = history.last()
        val trend = analyzeTrend(history)
        val trendEmoji = when (trend) {
            Trend.RISING -> "📈"
            Trend.FALLING -> "📉"
            Trend.STABLE -> "➡️"
        }
        return "$trendEmoji IC=${String.format("%.3f", latest.ic)} (n=${latest.sampleSize}) ${trend.label}"
    }

    // ═══════════════════════════════════════
    // 私有方法
    // ═══════════════════════════════════════

    private fun computeSpearmanIC(records: List<ScoreRecord>): Double {
        // 簡化版：使用 Pearson 相關係數（打分 vs 實際收益）
        val scores = records.map { it.score.toDouble() }
        val returns = records.map { it.actualReturn!! }

        val n = scores.size
        val meanScore = scores.average()
        val meanReturn = returns.average()

        var numerator = 0.0
        var denomScore = 0.0
        var denomReturn = 0.0

        for (i in 0 until n) {
            val ds = scores[i] - meanScore
            val dr = returns[i] - meanReturn
            numerator += ds * dr
            denomScore += ds * ds
            denomReturn += dr * dr
        }

        val denom = kotlin.math.sqrt(denomScore * denomReturn)
        return if (denom > 0) numerator / denom else 0.0
    }

    private fun analyzeTrend(history: List<ICEntry>): Trend {
        if (history.size < 3) return Trend.STABLE
        val recent = history.takeLast(5)
        val first = recent.first().ic
        val last = recent.last().ic
        return when {
            last - first > 0.01 -> Trend.RISING
            last - first < -0.01 -> Trend.FALLING
            else -> Trend.STABLE
        }
    }

    private fun addTradingDays(dateStr: String, days: Int): String {
        // 簡化：直接加 days 天（實際應跳過週末和節假日）
        val date = java.time.LocalDate.parse(dateStr)
        return date.plusDays(days.toLong()).toString()
    }

    private fun loadRecords(): List<ScoreRecord> {
        val json = prefs.getString(KEY_SCORE_RECORDS, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                ScoreRecord(
                    stockCode = obj.getString("code"),
                    score = obj.getInt("score"),
                    scoreDate = obj.getString("date"),
                    actualReturn = if (obj.has("ret")) obj.getDouble("ret") else null
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveRecords(records: List<ScoreRecord>) {
        val arr = org.json.JSONArray()
        for (r in records) {
            val obj = org.json.JSONObject().apply {
                put("code", r.stockCode)
                put("score", r.score)
                put("date", r.scoreDate)
                r.actualReturn?.let { put("ret", it) }
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_SCORE_RECORDS, arr.toString()).apply()
    }

    private fun loadICHistory(): List<ICEntry> {
        val json = prefs.getString(KEY_IC_HISTORY, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                ICEntry(
                    timestamp = obj.getLong("ts"),
                    ic = obj.getDouble("ic"),
                    sampleSize = obj.getInt("n")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveICHistory(history: List<ICEntry>) {
        val arr = org.json.JSONArray()
        for (h in history) {
            val obj = org.json.JSONObject().apply {
                put("ts", h.timestamp)
                put("ic", h.ic)
                put("n", h.sampleSize)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_IC_HISTORY, arr.toString()).apply()
    }

    // ═══════════════════════════════════════
    // 數據類
    // ═══════════════════════════════════════

    data class ScoreRecord(
        val stockCode: String,
        val score: Int,
        val scoreDate: String,
        val actualReturn: Double? // 實際 N 日後收益
    )

    data class ICEntry(
        val timestamp: Long,
        val ic: Double,
        val sampleSize: Int
    )

    data class ICResult(
        val ic: Double,
        val sampleSize: Int,
        val trend: Trend,
        val warning: Boolean,
        val recommendation: String
    )

    enum class Trend(val label: String) {
        RISING("上升"),
        FALLING("下降"),
        STABLE("平穩")
    }
}
