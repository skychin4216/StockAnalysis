package com.chin.stockanalysis.stock.analysis

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlin.math.sqrt

/**
 * 指數 K 線分析器
 *
 * 從本地 daily_snapshot 表獲取指數歷史 K 線，計算技術形態指標，
 * 為 AI 預判提供結構化數據輸入。
 */
object IndexKlineAnalyzer {

    private const val TAG = "IndexKlineAnalyzer"

    /**
     * 指數 K 線形態分析結果
     */
    data class KlineAnalysis(
        val indexName: String,          // 上證指數
        val indexCode: String,          // sh000001
        val latestDate: String,         // 最新日期
        val latestClose: Double,        // 最新收盤價
        val latestChangePct: Double,    // 最新漲跌幅
        val consecutiveUpDays: Int,     // 連漲天數（連續收陽線）
        val consecutiveDownDays: Int,   // 連跌天數
        val totalUpDays: Int,           // N 日內上漲天數
        val totalDownDays: Int,         // N 日內下跌天數
        val avgVolume: Double,          // N 日均量
        val latestVolumeRatio: Double,  // 最新量能 / 均量（>1 放量，<1 縮量）
        val ma5: Double,                // 5 日均線
        val ma10: Double,               // 10 日均線
        val ma20: Double,               // 20 日均線
        val aboveMa5: Boolean,          // 最新價在 MA5 之上？
        val aboveMa10: Boolean,
        val aboveMa20: Boolean,
        val maxHighInPeriod: Double,    // N 日內最高價
        val minLowInPeriod: Double,     // N 日內最低價
        val volatility: Double,         // N 日波動率（標準差）
        val trend: String,              // "UP"/"DOWN"/"OSCILLATE"
        val keyEvents: List<String>     // 關鍵事件描述，如 "連漲4天後周五大跌-2.3%"
    )

    /**
     * 分析指數 K 線形態
     *
     * @param context Android Context
     * @param indexCode 指數代碼（如 "sh000001"）
     * @param indexName 指數名稱（如 "上證指數"）
     * @param days 回顧天數，默認 20（約 1 個月交易日）
     * @return KlineAnalysis，若無數據返回 null
     */
    suspend fun analyze(
        context: Context,
        indexCode: String,
        indexName: String,
        days: Int = 20
    ): KlineAnalysis? {
        val dao = StockDatabase.getInstance(context).dailySnapshotDao()
        val rawList = dao.getByCode(indexCode, days)

        if (rawList.isEmpty()) {
            Log.w(TAG, "無數據: indexCode=$indexCode, days=$days")
            return null
        }

        // DAO 返回 date DESC，需反轉為 ASC（舊→新）
        val data = rawList.reversed()
        val n = data.size

        val latest = data.last()
        val latestClose = latest.close
        val latestChangePct = latest.changePct
        val latestDate = latest.date
        val latestVolume = latest.volume

        // 1. 連漲天數 / 連跌天數（從最新一天往前數）
        var consecutiveUpDays = 0
        var consecutiveDownDays = 0
        for (i in n - 1 downTo 0) {
            when {
                data[i].changePct > 0 -> consecutiveUpDays++
                data[i].changePct < 0 -> consecutiveDownDays++
                else -> break
            }
        }
        // 修正：如果最後一天是漲，則連跌應為 0；反之亦然
        if (latestChangePct > 0) {
            consecutiveDownDays = 0
        } else if (latestChangePct < 0) {
            consecutiveUpDays = 0
        } else {
            // changePct == 0，兩者都從前一日開始算，這裡視為中斷
            consecutiveUpDays = 0
            consecutiveDownDays = 0
        }

        // 2. N 日內上漲/下跌天數
        val totalUpDays = data.count { it.changePct > 0 }
        val totalDownDays = data.count { it.changePct < 0 }

        // 3. 均量
        val avgVolume = data.map { it.volume.toDouble() }.average()
        val latestVolumeRatio = if (avgVolume > 0) latestVolume / avgVolume else 1.0

        // 4. 均線
        val ma5 = calculateMA(data, 5)
        val ma10 = calculateMA(data, 10)
        val ma20 = calculateMA(data, 20)

        val aboveMa5 = latestClose > ma5
        val aboveMa10 = latestClose > ma10
        val aboveMa20 = latestClose > ma20

        // 5. N 日內最高/最低價
        val maxHighInPeriod = data.maxOf { it.high }
        val minLowInPeriod = data.minOf { it.low }

        // 6. 波動率（changePct 標準差）
        val volatility = calculateStdDev(data.map { it.changePct })

        // 7. 趨勢判斷（最近 5 天平均漲幅）
        val recent5 = data.takeLast(5)
        val avg5ChangePct = recent5.map { it.changePct }.average()
        val trend = when {
            avg5ChangePct > 0.3 -> "UP"
            avg5ChangePct < -0.3 -> "DOWN"
            else -> "OSCILLATE"
        }

        // 8. 關鍵事件
        val keyEvents = mutableListOf<String>()

        // 連漲後大跌
        val upStreakBeforeLatest = countConsecutiveUpBeforeLatest(data)
        if (upStreakBeforeLatest >= 3 && latestChangePct < -1.5) {
            keyEvents.add("連漲${upStreakBeforeLatest}天後大跌${String.format("%.1f", latestChangePct)}%")
        }

        // 連跌後大漲
        val downStreakBeforeLatest = countConsecutiveDownBeforeLatest(data)
        if (downStreakBeforeLatest >= 3 && latestChangePct > 1.5) {
            keyEvents.add("連跌${downStreakBeforeLatest}天後大漲${String.format("%.1f", latestChangePct)}%")
        }

        // 放量突破 / 跌破 MA20
        if (latestVolumeRatio > 1.2 && aboveMa20 && data.size >= 2) {
            val prev = data[data.size - 2]
            if (prev.close <= ma20) {
                keyEvents.add("放量突破20日線")
            }
        }
        if (!aboveMa20 && data.size >= 2) {
            val prev = data[data.size - 2]
            if (prev.close >= ma20) {
                keyEvents.add("跌破20日線")
            }
        }

        Log.d(TAG, "分析完成: $indexName($indexCode) 天數=$n, 趨勢=$trend, 連漲=$consecutiveUpDays, 連跌=$consecutiveDownDays")

        return KlineAnalysis(
            indexName = indexName,
            indexCode = indexCode,
            latestDate = latestDate,
            latestClose = latestClose,
            latestChangePct = latestChangePct,
            consecutiveUpDays = consecutiveUpDays,
            consecutiveDownDays = consecutiveDownDays,
            totalUpDays = totalUpDays,
            totalDownDays = totalDownDays,
            avgVolume = avgVolume,
            latestVolumeRatio = latestVolumeRatio,
            ma5 = ma5,
            ma10 = ma10,
            ma20 = ma20,
            aboveMa5 = aboveMa5,
            aboveMa10 = aboveMa10,
            aboveMa20 = aboveMa20,
            maxHighInPeriod = maxHighInPeriod,
            minLowInPeriod = minLowInPeriod,
            volatility = volatility,
            trend = trend,
            keyEvents = keyEvents
        )
    }

    /**
     * 計算均線（簡單移動平均）
     *
     * @param data 已按日期 ASC 排序的數據
     * @param period 週期
     * @return 均線值，數據不足時取可用數據的平均
     */
    private fun calculateMA(data: List<DailySnapshotEntity>, period: Int): Double {
        val takeCount = minOf(period, data.size)
        return data.takeLast(takeCount).map { it.close }.average()
    }

    /**
     * 計算標準差
     */
    private fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val avg = values.average()
        val variance = values.sumOf { (it - avg) * (it - avg) } / values.size
        return sqrt(variance)
    }

    /**
     * 統計最新一天之前的連漲天數（不包含最新一天）
     */
    private fun countConsecutiveUpBeforeLatest(data: List<DailySnapshotEntity>): Int {
        if (data.size < 2) return 0
        var count = 0
        for (i in data.size - 2 downTo 0) {
            if (data[i].changePct > 0) count++ else break
        }
        return count
    }

    /**
     * 統計最新一天之前的連跌天數（不包含最新一天）
     */
    private fun countConsecutiveDownBeforeLatest(data: List<DailySnapshotEntity>): Int {
        if (data.size < 2) return 0
        var count = 0
        for (i in data.size - 2 downTo 0) {
            if (data[i].changePct < 0) count++ else break
        }
        return count
    }
}
