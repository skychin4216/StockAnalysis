package com.chin.stockanalysis.stock.data

import android.util.Log
import com.chin.stockanalysis.stock.StockRealtime
import java.time.*

/**
 * ## 智能缓存 — 交易时段强制实时，非交易时段长 TTL
 *
 * 核心规则：
 * - 交易中（9:30-15:00）：强制实时，TL=5秒，始终用最新数据覆盖
 * - 非交易时段：长 TTL，缓存到下一个交易日 9:30
 */
class SmartStockCache(private val maxSize: Int = 200) {

    companion object {
        const val PREFETCH_TTL_MS = 10 * 60 * 1000L
    }

    private data class CacheEntry(
        val data: StockRealtime,
        val timestamp: Long,
        val ttlMs: Long
    )

    private val cache = LinkedHashMap<String, CacheEntry>(maxSize)
    private val tag = "SmartStockCache"

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

    fun put(data: Map<String, StockRealtime>) {
        if (data.isEmpty()) return
        val now = System.currentTimeMillis()
        val ttl = calculateSmartTTL()
        synchronized(cache) {
            for ((code, realtime) in data) {
                cache[code] = CacheEntry(realtime, now, ttl)
                if (cache.size > maxSize) cache.remove(cache.keys.firstOrNull())
            }
        }
        Log.d(tag, "Cached ${data.size} stocks TTL=${ttl / 1000}s")
    }

    fun putWithExtendedTtl(data: Map<String, StockRealtime>, ttlMs: Long = PREFETCH_TTL_MS) {
        if (data.isEmpty()) return
        val now = System.currentTimeMillis()
        val smartTtl = calculateSmartTTL()
        val isTrading = smartTtl <= 60_000L
        // 交易时段强制短TTL + 强制覆盖；非交易时段延长TTL
        val effectiveTtl = if (isTrading) smartTtl else maxOf(smartTtl, ttlMs)
        synchronized(cache) {
            for ((code, realtime) in data) {
                if (isTrading) {
                    // 强制覆盖
                    cache[code] = CacheEntry(realtime, now, effectiveTtl)
                } else {
                    val existing = cache[code]
                    val existingAge = if (existing != null) now - existing.timestamp else Long.MAX_VALUE
                    val shouldUpdate = existing == null || existingAge > existing.ttlMs - 30_000L
                    if (shouldUpdate) {
                        cache[code] = CacheEntry(realtime, now, effectiveTtl)
                    }
                }
                if (cache.size > maxSize) cache.remove(cache.keys.firstOrNull())
            }
        }
    }

    /** 盘后/周末缓存到下一个交易日 9:30 */
    private fun calculateSmartTTL(): Long {
        return try {
            val now = LocalTime.now()
            val today = LocalDate.now()
            val dayOfWeek = today.dayOfWeek
            if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
                return millisToNextTradingDay(today, now)
            }
            when {
                now >= LocalTime.of(9, 30) && now < LocalTime.of(11, 30) -> 5_000L
                now >= LocalTime.of(13, 0) && now < LocalTime.of(15, 0) -> 5_000L
                now >= LocalTime.of(11, 30) && now < LocalTime.of(13, 0) -> 60_000L
                now >= LocalTime.of(15, 0) -> millisToNextTradingDay(today, now)
                else -> minOf(Duration.between(now, LocalTime.of(9, 30)).toMillis(), 30 * 60 * 1000L)
            }
        } catch (e: Exception) { 3_000L }
    }

    private fun millisToNextTradingDay(today: LocalDate, nowTime: LocalTime): Long {
        val next = com.chin.stockanalysis.ui.TradingDayPickerView.addTradingDays(today, 1)
        val now = LocalDateTime.of(today, nowTime)
        val target = LocalDateTime.of(next, LocalTime.of(9, 30))
        return Duration.between(now, target).toMillis()
    }

    fun clear() { synchronized(cache) { cache.clear() } }
    fun getStats(): String {
        synchronized(cache) {
            return "Cache: ${cache.size}/$maxSize | TTL: ${calculateSmartTTL() / 1000}s"
        }
    }
}