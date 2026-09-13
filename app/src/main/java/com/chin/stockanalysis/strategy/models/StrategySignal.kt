package com.chin.stockanalysis.strategy.models

import com.chin.stockanalysis.strategy.StrategyCategory

/**
 * ## 策略信号
 *
 * 单个股票被策略命中后生成的信号。
 *
 * @property stockCode 股票代码（如 sh600519）
 * @property stockName 股票名称
 * @property strength 信号强度 (0-100)，越高表示越强
 * @property action 建议操作
 * @property reason 命中原因（人类可读）
 * @property details 详细信息（技术指标值等）
 * @property currentPrice 当前价格
 * @property changePercent 涨跌幅
 * @property timestamp 信号生成时间戳（毫秒），用于过期作废判断
 */
data class StrategySignal(
    val stockCode: String,
    val stockName: String,
    val strategyId: String,
    val category: StrategyCategory,
    val strength: Int,          // 0-100
    val action: SignalAction,
    val reason: String,
    val details: Map<String, String> = emptyMap(),
    val currentPrice: Double = 0.0,
    val changePercent: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
) {
    val emoji: String get() = when {
        strength >= 80 -> "🔥"
        strength >= 60 -> "✅"
        strength >= 40 -> "📌"
        else -> "⚪"
    }

    /** 简要描述 */
    fun brief(): String = "$emoji $stockName($stockCode): $reason (强度:$strength%)"

    /**
     * 判断信号是否已过期。
     *
     * @param maxAgeMs 最大有效期（毫秒），默认 24 小时
     * @return true 表示信号已过期应作废
     */
    fun isExpired(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean {
        return System.currentTimeMillis() - timestamp > maxAgeMs
    }

    companion object {
        /** 默认信号有效期：24 小时 */
        const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** 超短线信号有效期：4 小时（盘中信号当日有效） */
        const val ULTRA_SHORT_MAX_AGE_MS = 4L * 60 * 60 * 1000

        /** 短线信号有效期：3 天 */
        const val SHORT_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

        /** 中线信号有效期：30 天 */
        const val MID_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** 长线信号有效期：180 天 */
        const val LONG_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000
    }
}

/**
 * ## 信号操作建议
 */
enum class SignalAction(val label: String) {
    BUY("强烈关注"),
    WATCH("保持关注"),
    HOLD("持有观察"),
    SELL("减仓参考"),
    NONE("无建议")
}