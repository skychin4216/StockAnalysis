package com.chin.stockanalysis.stock.analysis

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import kotlin.math.sqrt

/**
 * 指数 K 线分析器
 *
 * 从本地 daily_snapshot 表获取指数历史 K 线，计算技术形态指标，
 * 为 AI 预判提供结构化数据输入。
 */
object IndexKlineAnalyzer {

    private const val TAG = "IndexKlineAnalyzer"

    /**
     * 指数 K 线形态分析结果
     */
    data class KlineAnalysis(
        val indexName: String,          // 上证指数
        val indexCode: String,          // sh000001
        val latestDate: String,         // 最新日期
        val latestClose: Double,        // 最新收盘价
        val latestChangePct: Double,    // 最新涨跌幅
        val consecutiveUpDays: Int,     // 连涨天数（连续收阳线）
        val consecutiveDownDays: Int,   // 连跌天数
        val totalUpDays: Int,           // N 日内上涨天数
        val totalDownDays: Int,         // N 日内下跌天数
        val avgVolume: Double,          // N 日均量
        val latestVolumeRatio: Double,  // 最新量能 / 均量（>1 放量，<1 缩量）
        val ma5: Double,                // 5 日均线
        val ma10: Double,               // 10 日均线
        val ma20: Double,               // 20 日均线
        val aboveMa5: Boolean,          // 最新价在 MA5 之上？
        val aboveMa10: Boolean,
        val aboveMa20: Boolean,
        val maxHighInPeriod: Double,    // N 日内最高价
        val minLowInPeriod: Double,     // N 日内最低价
        val volatility: Double,         // N 日波动率（标准差）
        val trend: String,              // "UP"/"DOWN"/"OSCILLATE"
        val keyEvents: List<String>     // 关键事件描述，如 "连涨4天后周五大跌-2.3%"
    )

    /**
     * 分析指数 K 线形态
     *
     * @param context Android Context
     * @param indexCode 指数代码（如 "sh000001"）
     * @param indexName 指数名称（如 "上证指数"）
     * @param days 回顾天数，默认 20（约 1 个月交易日）
     * @return KlineAnalysis，若无数据返回 null
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
            Log.w(TAG, "无数据: indexCode=$indexCode, days=$days")
            return null
        }

        // DAO 返回 date DESC，需反转为 ASC（旧→新）
        val data = rawList.reversed()
        val n = data.size

        val latest = data.last()
        val latestClose = latest.close
        val latestChangePct = latest.changePct
        val latestDate = latest.date
        val latestVolume = latest.volume

        // 1. 连涨天数 / 连跌天数（从最新一天往前数）
        var consecutiveUpDays = 0
        var consecutiveDownDays = 0
        for (i in n - 1 downTo 0) {
            when {
                data[i].changePct > 0 -> consecutiveUpDays++
                data[i].changePct < 0 -> consecutiveDownDays++
                else -> break
            }
        }
        // 修正：如果最后一天是涨，则连跌应为 0；反之亦然
        if (latestChangePct > 0) {
            consecutiveDownDays = 0
        } else if (latestChangePct < 0) {
            consecutiveUpDays = 0
        } else {
            // changePct == 0，两者都从前一日开始算，这里视为中断
            consecutiveUpDays = 0
            consecutiveDownDays = 0
        }

        // 2. N 日内上涨/下跌天数
        val totalUpDays = data.count { it.changePct > 0 }
        val totalDownDays = data.count { it.changePct < 0 }

        // 3. 均量
        val avgVolume = data.map { it.volume.toDouble() }.average()
        val latestVolumeRatio = if (avgVolume > 0) latestVolume / avgVolume else 1.0

        // 4. 均线
        val ma5 = calculateMA(data, 5)
        val ma10 = calculateMA(data, 10)
        val ma20 = calculateMA(data, 20)

        val aboveMa5 = latestClose > ma5
        val aboveMa10 = latestClose > ma10
        val aboveMa20 = latestClose > ma20

        // 5. N 日内最高/最低价
        val maxHighInPeriod = data.maxOf { it.high }
        val minLowInPeriod = data.minOf { it.low }

        // 6. 波动率（changePct 标准差）
        val volatility = calculateStdDev(data.map { it.changePct })

        // 7. 趋势判断（最近 5 天平均涨幅）
        val recent5 = data.takeLast(5)
        val avg5ChangePct = recent5.map { it.changePct }.average()
        val trend = when {
            avg5ChangePct > 0.3 -> "UP"
            avg5ChangePct < -0.3 -> "DOWN"
            else -> "OSCILLATE"
        }

        // 8. 关键事件
        val keyEvents = mutableListOf<String>()

        // 连涨后大跌
        val upStreakBeforeLatest = countConsecutiveUpBeforeLatest(data)
        if (upStreakBeforeLatest >= 3 && latestChangePct < -1.5) {
            keyEvents.add("连涨${upStreakBeforeLatest}天后大跌${String.format("%.1f", latestChangePct)}%")
        }

        // 连跌后大涨
        val downStreakBeforeLatest = countConsecutiveDownBeforeLatest(data)
        if (downStreakBeforeLatest >= 3 && latestChangePct > 1.5) {
            keyEvents.add("连跌${downStreakBeforeLatest}天后大涨${String.format("%.1f", latestChangePct)}%")
        }

        // 放量突破 / 跌破 MA20
        if (latestVolumeRatio > 1.2 && aboveMa20 && data.size >= 2) {
            val prev = data[data.size - 2]
            if (prev.close <= ma20) {
                keyEvents.add("放量突破20日线")
            }
        }
        if (!aboveMa20 && data.size >= 2) {
            val prev = data[data.size - 2]
            if (prev.close >= ma20) {
                keyEvents.add("跌破20日线")
            }
        }

        Log.d(TAG, "分析完成: $indexName($indexCode) 天数=$n, 趋势=$trend, 连涨=$consecutiveUpDays, 连跌=$consecutiveDownDays")

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
     * 计算均线（简单移动平均）
     *
     * @param data 已按日期 ASC 排序的数据
     * @param period 周期
     * @return 均线值，数据不足时取可用数据的平均
     */
    private fun calculateMA(data: List<DailySnapshotEntity>, period: Int): Double {
        val takeCount = minOf(period, data.size)
        return data.takeLast(takeCount).map { it.close }.average()
    }

    /**
     * 计算标准差
     */
    private fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val avg = values.average()
        val variance = values.sumOf { (it - avg) * (it - avg) } / values.size
        return sqrt(variance)
    }

    /**
     * 统计最新一天之前的连涨天数（不包含最新一天）
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
     * 统计最新一天之前的连跌天数（不包含最新一天）
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
