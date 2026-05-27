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
    val changePercent: Double = 0.0
) {
    val emoji: String get() = when {
        strength >= 80 -> "🔥"
        strength >= 60 -> "✅"
        strength >= 40 -> "📌"
        else -> "⚪"
    }

    /** 简要描述 */
    fun brief(): String = "$emoji $stockName($stockCode): $reason (强度:$strength%)"
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