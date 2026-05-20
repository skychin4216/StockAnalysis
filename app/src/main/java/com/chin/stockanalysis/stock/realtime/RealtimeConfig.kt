package com.chin.stockanalysis.stock.realtime

import com.chin.stockanalysis.stock.data.StockCache
import com.chin.stockanalysis.stock.data.StockDataSource
import com.chin.stockanalysis.stock.data.sources.EastMoneyStockSource
import com.chin.stockanalysis.stock.data.sources.SinaStockSource
import com.chin.stockanalysis.stock.data.sources.TencentStockSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * ## 实时数据组件配置
 *
 * 集中管理所有实时数据相关的配置，提供便捷的工厂方法。
 * 上层只需 `RealtimeConfig.createProcessor()` 即可获得完整配置的处理器。
 *
 * ### 使用示例
 * ```kotlin
 * // 最简单方式：一行代码获取完整配置的处理器
 * val processor = RealtimeConfig.createProcessor()
 *
 * // 获取实时行情
 * lifecycleScope.launch {
 *     val result = processor.getProcessedRealtime(listOf("sh600519"))
 *     val aiPrompt = processor.formatForAi(result)
 *     // 注入到 AI prompt...
 * }
 * ```
 */
class RealtimeConfig private constructor(
    /** 是否启用并发请求模式（多个源同时请求，取最快返回） */
    val enableConcurrentFetch: Boolean = true,
    /** 请求超时时间（毫秒） */
    val requestTimeoutMs: Long = 5000L,
    /** 同一股票最小请求间隔（毫秒） */
    val minRequestIntervalMs: Long = 1000L,
    /** 缓存 TTL（毫秒），交易时间内建议 3s，非交易时间可更长 */
    val cacheTtlMs: Long = 3000L,
    /** 自动健康检查间隔（毫秒），默认 30 秒 */
    val healthCheckIntervalMs: Long = 30_000L,
    /** 是否优先使用最快的源（否则按优先级） */
    val preferFastestSource: Boolean = true,
    /** 自定义数据源列表（按优先级排序） */
    val customSources: List<StockDataSource>? = null,
    /** 自定义协程作用域 */
    val customScope: CoroutineScope? = null
) {

    companion object {
        /**
         * 默认配置 —— 稳定模式：按优先级降级，新浪 > 腾讯 > 东方财富
         */
        val DEFAULT = Builder().build()

        /**
         * 极速模式 —— 并发请求所有源，取最快返回
         */
        val FASTEST = Builder()
            .enableConcurrentFetch(true)
            .preferFastestSource(true)
            .cacheTtlMs(2000L) // 缓存更短，数据更新
            .build()

        /**
         * 低流量模式 —— 只使用新浪，降低请求频率
         */
        val LOW_TRAFFIC = Builder()
            .enableConcurrentFetch(false)
            .preferFastestSource(false)
            .minRequestIntervalMs(3000L) // 降低频率
            .cacheTtlMs(5000L) // 缓存更长
            .build()

        /**
         * 创建默认数据源列表
         */
        private fun createDefaultSources(): List<StockDataSource> {
            return listOf(
                SinaStockSource(),      // 主源：新浪
                TencentStockSource(),    // 备1：腾讯
                EastMoneyStockSource()   // 备2：东方财富
            )
        }

        /**
         * 使用默认配置创建数据访问器
         */
        fun createAccessor(
            sources: List<StockDataSource> = createDefaultSources(),
            config: RealtimeConfig = DEFAULT
        ): RealtimeDataAccessor {
            val scope = config.customScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
            return RealtimeDataAccessor(
                sources = sources,
                scope = scope,
                requestTimeoutMs = config.requestTimeoutMs,
                minRequestIntervalMs = config.minRequestIntervalMs
            )
        }

        /**
         * 使用默认配置创建数据处理器
         *
         * 这是最便捷的入口方法：
         * ```kotlin
         * val processor = RealtimeConfig.createProcessor()
         * ```
         */
        fun createProcessor(
            sources: List<StockDataSource> = createDefaultSources(),
            config: RealtimeConfig = DEFAULT
        ): RealtimeDataProcessor {
            val cache = StockCache(defaultTtlMs = config.cacheTtlMs)
            val accessor = createAccessor(sources, config)
            return RealtimeDataProcessor(
                cache = cache,
                accessor = accessor
            )
        }

        /**
         * 仅创建缓存（不带 accessor），适用于离线场景
         */
        fun createCacheOnly(config: RealtimeConfig = DEFAULT): RealtimeDataProcessor {
            val cache = StockCache(defaultTtlMs = config.cacheTtlMs)
            return RealtimeDataProcessor(
                cache = cache,
                accessor = null
            )
        }
    }

    /**
     * Builder 模式构建 RealtimeConfig
     */
    class Builder {
        private var enableConcurrentFetch: Boolean = true
        private var requestTimeoutMs: Long = 5000L
        private var minRequestIntervalMs: Long = 1000L
        private var cacheTtlMs: Long = 3000L
        private var healthCheckIntervalMs: Long = 30_000L
        private var preferFastestSource: Boolean = true
        private var customSources: List<StockDataSource>? = null
        private var customScope: CoroutineScope? = null

        fun enableConcurrentFetch(enable: Boolean) = apply { this.enableConcurrentFetch = enable }
        fun requestTimeoutMs(timeout: Long) = apply { this.requestTimeoutMs = timeout }
        fun minRequestIntervalMs(interval: Long) = apply { this.minRequestIntervalMs = interval }
        fun cacheTtlMs(ttl: Long) = apply { this.cacheTtlMs = ttl }
        fun healthCheckIntervalMs(interval: Long) = apply { this.healthCheckIntervalMs = interval }
        fun preferFastestSource(prefer: Boolean) = apply { this.preferFastestSource = prefer }
        fun customSources(sources: List<StockDataSource>) = apply { this.customSources = sources }
        fun customScope(scope: CoroutineScope) = apply { this.customScope = scope }

        fun build(): RealtimeConfig {
            return RealtimeConfig(
                enableConcurrentFetch = enableConcurrentFetch,
                requestTimeoutMs = requestTimeoutMs,
                minRequestIntervalMs = minRequestIntervalMs,
                cacheTtlMs = cacheTtlMs,
                healthCheckIntervalMs = healthCheckIntervalMs,
                preferFastestSource = preferFastestSource,
                customSources = customSources,
                customScope = customScope
            )
        }
    }
}