package com.chin.stockanalysis.stock.data

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 行情数据缓存 - LRU 缓存，支持智能 TTL
 *
 * ✅ 智能 TTL 策略（根据 A 股交易时段自动调整）：
 * - 交易时段（9:30-11:30, 13:00-15:00）：1 秒（极短，确保实时性）
 * - 午休（11:30-13:00）：5 秒
 * - 盘后至午夜（15:00-22:00）：5 分钟
 * - 深夜至盘前（22:00-9:30）：30 分钟
 * - 周末/节假日：1 小时
 */
class StockCache(private val defaultTtlMs: Long = 3000) {

    private data class CacheEntry(
        val data: StockRealtime,
        val timestamp: Long,
        val ttlMs: Long // 每个条目独立 TTL
    )

    // 使用 LinkedHashMap 实现简单的 LRU
    private val cache = LinkedHashMap<String, CacheEntry>(100)
    private val maxSize = 100
    private val tag = "StockCache"

    /**
     * 获取缓存中未过期的数据
     */
    fun get(codes: List<String>): Map<String, StockRealtime> {
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, StockRealtime>()

        synchronized(cache) {
            for (code in codes) {
                val entry = cache[code]
                if (entry != null) {
                    if ((now - entry.timestamp) < entry.ttlMs) {
                        result[code] = entry.data
                    } else {
                        // 过期条目自动清理
                        cache.remove(code)
                    }
                }
            }
        }

        if (result.isNotEmpty()) {
            Log.v(tag, "get: hit ${result.size}/${codes.size}")
        }
        return result
    }

    /**
     * 将数据写入缓存，使用智能 TTL
     */
    fun put(data: Map<String, StockRealtime>) {
        if (data.isEmpty()) return
        val now = System.currentTimeMillis()
        val smartTtl = calculateSmartTTL()
        val ttlToUse = if (smartTtl > 0) smartTtl else defaultTtlMs

        synchronized(cache) {
            for ((code, realtime) in data) {
                cache[code] = CacheEntry(realtime, now, ttlToUse)

                // 简单的 LRU：如果超过最大值，删除最早的
                if (cache.size > maxSize) {
                    val firstKey = cache.keys.firstOrNull()
                    if (firstKey != null) {
                        cache.remove(firstKey)
                    }
                }
            }
        }

        Log.d(tag, "put ${data.size} stocks, TTL=${ttlToUse / 1000}s")
    }

    /**
     * 根据 A 股交易时段计算智能 TTL
     *
     * @return TTL 毫秒，-1 表示使用默认 TTL
     */
    private fun calculateSmartTTL(): Long {
        return try {
            val now = LocalTime.now()
            val today = LocalDate.now()
            val isWeekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY

            when {
                // 周末/节假日 -> 1 小时
                isWeekend -> 60 * 60 * 1000L

                // 交易时段（9:30-11:30 + 13:00-15:00）-> 1 秒
                now >= LocalTime.of(9, 30) && now < LocalTime.of(11, 30) -> 1000L
                now >= LocalTime.of(13, 0) && now < LocalTime.of(15, 0) -> 1000L

                // 午休（11:30-13:00）-> 5 秒
                now >= LocalTime.of(11, 30) && now < LocalTime.of(13, 0) -> 5000L

                // 盘后（15:00-22:00）-> 5 分钟
                now >= LocalTime.of(15, 0) && now < LocalTime.of(22, 0) -> 5 * 60 * 1000L

                // 深夜-盘前（22:00-9:30）-> 30 分钟
                now >= LocalTime.of(22, 0) || now < LocalTime.of(9, 30) -> 30 * 60 * 1000L

                // 兜底 -> 默认
                else -> defaultTtlMs
            }
        } catch (e: Exception) {
            // 如果 LocalTime 不可用（API < 26），回退到默认
            Log.w(tag, "Smart TTL failed, using default: ${e.message}")
            defaultTtlMs
        }
    }

    /**
     * 清除所有缓存
     */
    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
        Log.d(tag, "Cache cleared")
    }

    /**
     * 清除过期数据
     */
    fun clearExpired() {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache.entries.removeAll { (_, entry) ->
                (now - entry.timestamp) >= entry.ttlMs
            }
        }
        Log.d(tag, "Expired entries cleared")
    }

    /**
     * 获取缓存统计信息
     */
    fun getStats(): String {
        synchronized(cache) {
            return "Cache: ${cache.size}/$maxSize entries"
        }
    }

    /**
     * 计算当前应使用的 TTL（外部查询用）
     */
    fun getCurrentTTL(): Long {
        val smartTtl = calculateSmartTTL()
        return if (smartTtl > 0) smartTtl else defaultTtlMs
    }
}