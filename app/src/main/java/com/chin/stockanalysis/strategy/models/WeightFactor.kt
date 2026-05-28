package com.chin.stockanalysis.strategy.models

/**
 * ## 权重因子
 *
 * 策略信号强度由多个因子加权计算得出。
 * 所有权重合计应为 100。
 */
data class WeightFactor(
    val key: String,
    val label: String,
    val weight: Int,
    val description: String
)