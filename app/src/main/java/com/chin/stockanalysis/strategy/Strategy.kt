package com.chin.stockanalysis.strategy

import com.chin.stockanalysis.strategy.models.ScreeningResult

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
 * ### 使用示例
 * ```kotlin
 * class MyStrategy : Strategy {
 *     override val id = "my_macd_cross"
 *     override val name = "MACD金叉选股"
 *     override val category = StrategyCategory.TREND
 *
 *     override suspend fun screen(): Result<ScreeningResult> {
 *         // 1. 从 StockScreener 获取候选池
 *         // 2. 逐只判断是否满足条件
 *         // 3. 返回筛选结果
 *     }
 * }
 * ```
 */
interface Strategy {
    /** 策略唯一标识 */
    val id: String

    /** 策略名称（用户可见） */
    val name: String

    /** 策略描述 */
    val description: String

    /** 策略类别 */
    val category: StrategyCategory

    /** 策略参数配置 */
    val config: StrategyConfig

    /**
     * 执行选股扫描
     *
     * 返回 [Result.success] 包含 [ScreeningResult]
     * 返回 [Result.failure] 表示扫描失败（如网络错误）
     */
    suspend fun screen(): Result<ScreeningResult>

    /**
     * 判断策略当前是否可用
     * 例如：均线策略需要至少 20 个交易日的 K 线数据
     */
    suspend fun isAvailable(): Boolean
}

/**
 * ## 策略类别
 */
enum class StrategyCategory(val label: String, val icon: String, val description: String) {
    TREND("趋势类", "📈", "基于均线、趋势线等趋势跟踪策略"),
    MOMENTUM("动量类", "🚀", "基于价格动量、突破等顺势策略"),
    VALUE("价值类", "💎", "基于估值指标的低估值策略"),
    VOLUME("量价类", "📊", "基于成交量、量价关系的策略"),
    CUSTOM("自定义", "🔧", "用户自行配置的策略")
}