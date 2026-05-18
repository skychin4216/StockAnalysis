package com.chin.stockanalysis.stock.data

import com.chin.stockanalysis.stock.StockRealtime

/**
 * 行情数据缓存 - LRU 缓存，3秒 TTL
 */
class StockCache(private val ttlMs: Long = 3000) {

    private data class CacheEntry(
        val data: StockRealtime,
        val timestamp: Long
    )

    // 使用 LinkedHashMap 实现简单的 LRU
    private val cache = LinkedHashMap<String, CacheEntry>(100)
    private val maxSize = 100

    /**
     * 获取缓存中未过期的数据
     */
    fun get(codes: List<String>): Map<String, StockRealtime> {
        val now = System.currentTimeMillis()
        return codes.mapNotNull { code ->
            val entry = cache[code]
            if (entry != null && (now - entry.timestamp) < ttlMs) {
                code to entry.data
            } else {
                null
            }
        }.toMap()
    }

    /**
     * 将数据写入缓存
     */
    fun put(data: Map<String, StockRealtime>) {
        val now = System.currentTimeMillis()

        synchronized(cache) {
            for ((code, realtime) in data) {
                cache[code] = CacheEntry(realtime, now)

                // 简单的 LRU：如果超过最大值，删除最早的
                if (cache.size > maxSize) {
                    val firstKey = cache.keys.firstOrNull()
                    if (firstKey != null) {
                        cache.remove(firstKey)
                    }
                }
            }
        }
    }

    /**
     * 清除所有缓存
     */
    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
    }

    /**
     * 清除过期数据
     */
    fun clearExpired() {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache.entries.removeAll { (_, entry) ->
                (now - entry.timestamp) >= ttlMs
            }
        }
    }
}

