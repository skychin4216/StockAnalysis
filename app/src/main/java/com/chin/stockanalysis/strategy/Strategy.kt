package com.chin.stockanalysis.strategy

import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.WeightFactor

/**
 * ## 量化选股策略接口
 *
 * 所有选股策略必须实现此接口。
 *
 * ### 生命周期
 * ```
 * isAvailable() → screen() → 返回 ScreeningResult
 * ```
 *
 * ### 历史回测模式
 * 调用 `screenWithData(stocks)` 可传入预加载的历史数据，
 * 避免回测时调用实时 API。默认实现委托给 `screen()`。
 */
interface Strategy {
    /** 策略唯一标识 */
    val id: String

    /** 策略名称（用户可见） */
    var name: String

    /** 策略描述 */
    var description: String

    /** 策略类别 */
    val category: StrategyCategory

    /** 策略参数配置 */
    val config: StrategyConfig

    /** 权重因子列表（总和应为 100） */
    var weightFactors: List<WeightFactor>

    /** 策略来源：BUILTIN / USER_CUSTOM */
    val source: StrategySource

    /**
     * 执行选股扫描（使用实时 API）
     */
    suspend fun screen(): Result<ScreeningResult>

    /**
     * 使用预加载的股票列表执行选股（用于历史回测）。
     * 默认委托给 screen()，策略可覆写以使用预加载数据。
     *
     * @param preloadedStocks 预加载的股票行情列表
     */
    suspend fun screenWithData(preloadedStocks: List<StockRealtime>): Result<ScreeningResult> {
        return screen()
    }

    /**
     * 判断策略当前是否可用
     */
    suspend fun isAvailable(): Boolean
}

enum class StrategyCategory(val label: String, val icon: String, val description: String) {
    TREND("趋势类", "📈", "基于均线、趋势线等趋势跟踪策略"),
    MOMENTUM("动量类", "🚀", "基于价格动量、突破等顺势策略"),
    VALUE("价值类", "💎", "基于估值指标的低估值策略"),
    VOLUME("量价类", "📊", "基于成交量、量价关系的策略"),
    CUSTOM("自定义", "🔧", "用户自行配置的策略")
}

enum class StrategySource(val label: String) {
    BUILTIN("系统内置"),
    USER_CUSTOM("用户自定义")
}