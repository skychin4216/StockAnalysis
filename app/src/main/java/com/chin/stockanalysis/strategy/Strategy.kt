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

    /** 策略适用的持仓周期（默认短线，向后兼容） */
    val holdingPeriods: List<HoldingPeriod>
        get() = listOf(HoldingPeriod.SHORT)

    /** 默认推荐周期 */
    val defaultPeriod: HoldingPeriod
        get() = holdingPeriods.first()

    // ───────────────────────────────────────────
    // 风控默认值（下沉到策略，Fragment 不再硬编码）
    // ───────────────────────────────────────────

    /** 默认止损比率（如 -0.02 表示 -2%）。null 表示该策略不设默认止损 */
    val defaultStopLoss: Float?
        get() = null

    /** 默认止盈比率（如 0.03 表示 +3%）。null 表示该策略不设默认止盈 */
    val defaultTakeProfit: Float?
        get() = null

    /** 该策略在同周期内最大同时持有数量（默认 5） */
    val maxPositions: Int
        get() = 5

    // ───────────────────────────────────────────
    // 持仓天数建议（默认取周期枚举的范围）
    // ───────────────────────────────────────────

    /** 最短建议持仓天数 */
    val minHoldingDays: Int
        get() = defaultPeriod.holdingDays.first

    /** 最长建议持仓天数 */
    val maxHoldingDays: Int
        get() = defaultPeriod.holdingDays.last

    // ───────────────────────────────────────────
    // 数据依赖
    // ───────────────────────────────────────────

    /** 是否需要 Level2 实时数据（超短线必备） */
    val requiresL2Data: Boolean
        get() = false

    /** 是否需要财务季报/年报数据（长线必备） */
    val requiresFinancialData: Boolean
        get() = false

    /** 数据频率 */
    val dataFrequency: DataFrequency
        get() = DataFrequency.DAILY

    // ───────────────────────────────────────────
    // 过滤开关（动态：可覆写为基于时间的 get()）
    // ───────────────────────────────────────────

    /** 是否需要主力资金过滤（>=55分）。超短线可覆写为时间动态开关 */
    val requiresSmartMoney: Boolean
        get() = false

    /** 是否需要 AI 精选加权。超短线可覆写为盘后动态开关 */
    val requiresAIRefine: Boolean
        get() = false

    /** 信号有效期（小时），过期作废。默认 24 小时 */
    val signalExpiryHours: Int
        get() = 24

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

/** 持仓周期 */
enum class HoldingPeriod(val label: String, val icon: String, val holdingDays: IntRange) {
    ULTRA_SHORT("超短線", "⚡", 1..1),
    SHORT("短線", "🤖", 1..14),
    MID("中線", "📈", 30..180),
    LONG("長線", "💎", 180..999)
}

/** 数据频率 */
enum class DataFrequency(val label: String) {
    TICK("逐筆"),
    MIN5("5分鐘"),
    DAILY("日K"),
    WEEKLY("週K")
}