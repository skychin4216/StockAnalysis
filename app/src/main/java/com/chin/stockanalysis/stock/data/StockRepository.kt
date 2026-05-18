package com.chin.stockanalysis.stock.data

import com.chin.stockanalysis.stock.StockRealtime

/**
 * 股票数据仓储 - 管理缓存、多数据源降级
 */
class StockRepository(
    private val primarySource: StockDataSource,
    private val fallbackSources: List<StockDataSource> = emptyList(),
    private val cache: StockCache = StockCache()
) {

    /**
     * 获取实时行情，支持自动降级
     * 1. 查缓存
     * 2. 缓存未命中 -> 主数据源
     * 3. 主源失败 -> 备用源
     * 4. 全部失败 -> 返回过期缓存
     */
    fun getRealtime(codes: List<String>): Map<String, StockRealtime> {
        if (codes.isEmpty()) return emptyMap()

        // 1. 查缓存（3秒内有效）
        val cached = cache.get(codes)
        val uncached = codes - cached.keys

        if (uncached.isEmpty()) {
            return cached
        }

        // 2. 尝试主数据源
        try {
            val fresh = primarySource.fetchRealtime(uncached)
            if (fresh.isNotEmpty()) {
                cache.put(fresh)
                return cached + fresh
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. 主源失败，尝试备用源
        for (fallback in fallbackSources) {
            try {
                val fresh = fallback.fetchRealtime(uncached)
                if (fresh.isNotEmpty()) {
                    cache.put(fresh)
                    return cached + fresh
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. 全部失败，返回缓存（即使过期）
        cache.clearExpired() // 清除过期数据
        val staleData = cache.get(uncached)

        return if (staleData.isNotEmpty()) {
            cached + staleData
        } else {
            cached
        }
    }

    /**
     * 清除所有缓存
     */
    fun clearCache() {
        cache.clear()
    }
}

