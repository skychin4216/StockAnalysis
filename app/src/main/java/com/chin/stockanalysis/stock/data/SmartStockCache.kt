package com.chin.stockanalysis.stock.data

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * ## 智能缓存 - 根据A股交易时段自动调整TTL
 *
 * 这是 EnhancedStockDataProvider 中 SmartStockCache 的独立版本。
 * 与 StockCache 的不同：此类用于 MultiSourceStockRepository 的并发多源场景，
 * StockCache 用于 RealtimeDataAccessor 的并发选取场景。
 *
 * TTL 策略：
 * - 交易时段 9:30-15:00 → 1秒
 * - 盘后 15:00-22:00 → 5分钟
 * - 夜间 22:00-9:30 → 30分钟
 * - 周末/节假日 → 1小时
 *
 * @param maxSize 最大缓存条目数，默认200
 */
class SmartStockCache(private val maxSize: Int = 200) {

    companion object {
        /** 预取数据默认 TTL：10 分钟 */
        const val PREFETCH_TTL_MS = 10 * 60 * 1000L
    }

    private data class CacheEntry(
        val data: StockRealtime,
        val timestamp: Long,
        val ttlMs: Long
    )

    private val cache = LinkedHashMap<String, CacheEntry>(maxSize)
    private val tag = "SmartStockCache"

    /**
     * 获取缓存中未过期的数据
     */
    fun get(codes: List<String>): Map<String, StockRealtime> {
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, StockRealtime>()
        synchronized(cache) {
            for (code in codes) {
                val entry = cache[code]
                if (entry != null && (now - entry.timestamp) < entry.ttlMs) {
                    result[code] = entry.data
                }
            }
        }
        return result
    }

    /**
     * 将数据写入缓存，自动计算智能TTL
     */
    fun put(data: Map<String, StockRealtime>) {
        if (data.isEmpty()) return
        val now = System.currentTimeMillis()
        val ttl = calculateSmartTTL()
        synchronized(cache) {
            for ((code, realtime) in data) {
                cache[code] = CacheEntry(realtime, now, ttl)
                if (cache.size > maxSize) {
                    cache.remove(cache.keys.firstOrNull())
                }
            }
        }
        Log.d(tag, "Cached ${data.size} stocks with TTL=${ttl / 1000}s")
    }

    /**
     * 预取写入：使用指定 TTL（覆盖智能TTL），TTL 取 max(smartTTL, ttlMs)
     *
     * 用于后台预取场景：交易中智能TTL=1s，但预取数据可保留 10 分钟，
     * 这样用户提问时如果数据在 10 分钟内则秒返回，无需实时请求。
     *
     * @param data 股票数据
     * @param ttlMs 期望的 TTL（毫秒），将与智能TTL取较大值
     */
    fun putWithExtendedTtl(data: Map<String, StockRealtime>, ttlMs: Long = 10 * 60 * 1000L) {
        if (data.isEmpty()) return
        val now = System.currentTimeMillis()
        val smartTtl = calculateSmartTTL()
        val effectiveTtl = maxOf(smartTtl, ttlMs)
        synchronized(cache) {
            for ((code, realtime) in data) {
                // 只有当缓存中没有数据，或现有数据即将过期（< 30s）才覆盖
                // 避免把更新鲜的短TTL数据覆盖为旧的长TTL数据
                val existing = cache[code]
                val existingAge = if (existing != null) now - existing.timestamp else Long.MAX_VALUE
                val shouldUpdate = existing == null || existingAge > existing.ttlMs - 30_000L
                if (shouldUpdate) {
                    cache[code] = CacheEntry(realtime, now, effectiveTtl)
                    if (cache.size > maxSize) {
                        cache.remove(cache.keys.firstOrNull())
                    }
                }
            }
        }
        Log.d(tag, "Prefetch cached ${data.size} stocks TTL=${effectiveTtl / 1000}s (smart=${smartTtl / 1000}s, requested=${ttlMs / 1000}s)")
    }

    /**
     * 根据 A 股交易时段计算 TTL
     */
    private fun calculateSmartTTL(): Long {
        return try {
            val now = LocalTime.now()
            val today = LocalDate.now()
            val isWeekend = today.dayOfWeek == DayOfWeek.SATURDAY ||
                today.dayOfWeek == DayOfWeek.SUNDAY

            when {
                isWeekend -> 60 * 60 * 1000L                                          // 周末1小时
                now >= LocalTime.of(9, 30) && now <= LocalTime.of(15, 0) -> 1000L     // 交易中1秒
                now > LocalTime.of(15, 0) && now < LocalTime.of(22, 0) -> 5 * 60 * 1000L // 盘后5分钟
                now >= LocalTime.of(22, 0) || now < LocalTime.of(9, 30) -> 30 * 60 * 1000L // 夜间30分钟
                else -> 5 * 60 * 1000L
            }
        } catch (e: Exception) {
            Log.w(tag, "Smart TTL failed, using 3s: ${e.message}")
            3000L
        }
    }

    /**
     * 清除所有缓存
     */
    fun clear() {
        synchronized(cache) { cache.clear() }
        Log.d(tag, "Cache cleared")
    }

    /**
     * 获取缓存统计
     */
    fun getStats(): String {
        synchronized(cache) {
            return "Cache: ${cache.size}/$maxSize | TTL: ${calculateSmartTTL() / 1000}s"
        }
    }
}