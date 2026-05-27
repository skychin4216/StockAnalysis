package com.chin.stockanalysis.stock.autorefresh

import com.chin.stockanalysis.stock.data.TradingCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZoneId

/**
 * ## TLL 刷新策略 — 决定何时应该刷新股票价格
 *
 * ### TLL 模型
 * - **Time**: 仅在交易时段（9:30-11:30, 13:00-15:00）刷新，每60秒一次
 * - **Location**: 由 [StockAutoRefreshManager] 控制（Fragment 可见时启用）
 * - **Limitation**: 单次最多10只股票，网络降级不阻塞UI
 *
 * 非交易日+价格已是最近交易时段获取 → 不再刷新
 */
object RefreshPolicy {

    /** 交易时段刷新间隔（毫秒） */
    const val TRADING_REFRESH_INTERVAL_MS = 60_000L   // 60秒

    /** 盘前/盘后刷新间隔（毫秒） */
    const val NON_TRADING_REFRESH_INTERVAL_MS = 5 * 60_000L  // 5分钟

    /** 上次更新时间与当前时间的最小差，低于此值则认为已是最新不需刷新 */
    const val STALE_THRESHOLD_MS = 60_000L  // 1分钟

    /** 单次请求最大股票数 */
    const val MAX_BATCH_SIZE = 10

    /** 上海时间 Zone */
    private val SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai")

    /** 早盘开始 */
    private val MORNING_START = LocalTime.of(9, 30)
    /** 早盘结束 */
    private val MORNING_END = LocalTime.of(11, 30)
    /** 下午盘开始 */
    private val AFTERNOON_START = LocalTime.of(13, 0)
    /** 下午盘结束 */
    private val AFTERNOON_END = LocalTime.of(15, 0)

    /**
     * 判断当前是否在 A 股交易时段
     *
     * @return true 如果在 9:30-11:30 或 13:00-15:00
     */
    fun isTradingSession(): Boolean {
        val now = LocalTime.now(SHANGHAI_ZONE)
        return (now >= MORNING_START && now < MORNING_END) ||
                (now >= AFTERNOON_START && now < AFTERNOON_END)
    }

    /**
     * 判断当前是否在午间休市
     */
    fun isLunchBreak(): Boolean {
        val now = LocalTime.now(SHANGHAI_ZONE)
        return now >= MORNING_END && now < AFTERNOON_START
    }

    /**
     * 完整判断是否应该自动刷新
     *
     * @param lastUpdateTimeMs 上次成功更新时间的时间戳（毫秒）
     * @param isTradingDay 是否为交易日（由 [TradingCalendar] 提供）
     * @return 应该刷新的原因，null 表示不需要刷新
     */
    suspend fun shouldRefresh(
        lastUpdateTimeMs: Long,
        isTradingDay: Boolean
    ): RefreshDecision = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()

        // 1. 非交易日
        if (!isTradingDay) {
            // 如果上次更新是今天交易时段内获取的 → 不刷新
            if (isFromTodayTradingSession(lastUpdateTimeMs)) {
                return@withContext RefreshDecision.No("非交易日，但已有今日最新价格")
            }
            return@withContext RefreshDecision.No("非交易日")
        }

        // 2. 午休不刷新
        if (isLunchBreak()) {
            return@withContext RefreshDecision.No("午间休市")
        }

        // 3. 盘前 (0:00 - 9:30)
        if (LocalTime.now(SHANGHAI_ZONE) < MORNING_START) {
            if (isFromTodayTradingSession(lastUpdateTimeMs)) {
                return@withContext RefreshDecision.No("盘前，已有今日最新价格")
            }
            // 盘前每5分钟刷一次
            return@withContext if (now - lastUpdateTimeMs > NON_TRADING_REFRESH_INTERVAL_MS) {
                RefreshDecision.Yes("盘前预热", NON_TRADING_REFRESH_INTERVAL_MS)
            } else {
                RefreshDecision.No("盘前，未到刷新间隔")
            }
        }

        // 4. 交易时段
        if (isTradingSession()) {
            if (now - lastUpdateTimeMs < TRADING_REFRESH_INTERVAL_MS) {
                return@withContext RefreshDecision.No("交易中，未到刷新间隔(${TRADING_REFRESH_INTERVAL_MS}ms)")
            }
            return@withContext RefreshDecision.Yes("交易时段", TRADING_REFRESH_INTERVAL_MS)
        }

        // 5. 盘后 (15:00 - 24:00)
        if (isFromTodayTradingSession(lastUpdateTimeMs)) {
            return@withContext RefreshDecision.No("盘后，已有今日收盘价")
        }
        // 盘后每5分钟刷一次直到获取到收盘价
        return@withContext if (now - lastUpdateTimeMs > NON_TRADING_REFRESH_INTERVAL_MS) {
            RefreshDecision.Yes("盘后等待收盘价", NON_TRADING_REFRESH_INTERVAL_MS)
        } else {
            RefreshDecision.No("盘后，未到刷新间隔")
        }
    }

    /**
     * 判断上次更新时间是否在今天交易时段内
     * 即：同一天 且 在 9:30-15:00 之间
     */
    private fun isFromTodayTradingSession(timestampMs: Long): Boolean {
        val updateInstant = java.time.Instant.ofEpochMilli(timestampMs)
        val updateDateTime = java.time.LocalDateTime.ofInstant(updateInstant, SHANGHAI_ZONE)
        val today = java.time.LocalDate.now(SHANGHAI_ZONE)

        if (updateDateTime.toLocalDate() != today) return false

        val time = updateDateTime.toLocalTime()
        return time >= MORNING_START && time <= AFTERNOON_END
    }

    /**
     * 获取当前一次刷新建议的间隔
     */
    fun currentInterval(): Long {
        return if (isTradingSession()) TRADING_REFRESH_INTERVAL_MS
        else NON_TRADING_REFRESH_INTERVAL_MS
    }
}

/**
 * 刷新决策 — 封装是否刷新及原因
 */
sealed class RefreshDecision {
    data class Yes(val reason: String, val intervalMs: Long) : RefreshDecision()
    data class No(val reason: String) : RefreshDecision()

    val shouldRefresh: Boolean get() = this is Yes
}