package com.chin.stockanalysis.strategy

/**
 * ## 策略参数配置
 *
 * 每个策略可以通过 [params] Map 传递自定义参数。
 * 同时提供类型安全的便捷访问方法。
 */
data class StrategyConfig(
    /** 是否启用 */
    val enabled: Boolean = true,

    /** 扫描的股票池代码（空表示全市场扫描） */
    val stockPool: List<String> = emptyList(),

    /** 最多返回结果数 */
    val maxResults: Int = 20,

    /** 自定义参数 Map */
    val params: Map<String, Any> = emptyMap()
) {
    /** 获取 String 参数 */
    fun getString(key: String, default: String = ""): String =
        params[key]?.toString() ?: default

    /** 获取 Int 参数 */
    fun getInt(key: String, default: Int = 0): Int =
        (params[key] as? Number)?.toInt() ?: default

    /** 获取 Double 参数 */
    fun getDouble(key: String, default: Double = 0.0): Double =
        (params[key] as? Number)?.toDouble() ?: default

    /** 获取 Boolean 参数 */
    fun getBoolean(key: String, default: Boolean = false): Boolean =
        params[key] as? Boolean ?: default

    /** 获取 List 参数 */
    fun getList(key: String): List<Any> =
        (params[key] as? List<*>)?.filterNotNull() ?: emptyList()

    companion object {
        /** 默认配置 */
        val DEFAULT = StrategyConfig()

        /** 全市场扫描 */
        fun fullMarket(maxResults: Int = 20) = StrategyConfig(
            enabled = true,
            stockPool = emptyList(),
            maxResults = maxResults
        )

        /** 限定池扫描 */
        fun pool(pool: List<String>, maxResults: Int = 10) = StrategyConfig(
            enabled = true,
            stockPool = pool,
            maxResults = maxResults
        )

        /** 带参数配置 */
        fun custom(params: Map<String, Any>, maxResults: Int = 20) = StrategyConfig(
            enabled = true,
            params = params,
            maxResults = maxResults
        )
    }
}