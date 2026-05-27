package com.chin.stockanalysis.strategy.models

import com.chin.stockanalysis.strategy.StrategyCategory

/**
 * ## 选股扫描结果
 *
 * 每次策略扫描完成后返回的结果模型。
 *
 * @property strategyId 策略 ID
 * @property strategyName 策略名称
 * @property category 策略类别
 * @property signals 命中信号列表
 * @property totalScanned 总共扫描的股票数
 * @property scanTimeMs 扫描耗时（毫秒）
 * @property timestamp 扫描时间戳
 */
data class ScreeningResult(
    val strategyId: String,
    val strategyName: String,
    val category: StrategyCategory,
    val signals: List<StrategySignal>,
    val totalScanned: Int,
    val scanTimeMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 命中数量 */
    val hitCount: Int get() = signals.size

    /** 命中率 */
    val hitRate: Float get() = if (totalScanned > 0) hitCount.toFloat() / totalScanned else 0f

    /** 按信号强度排序的结果 */
    val sortedByStrength: List<StrategySignal> get() = signals.sortedByDescending { it.strength }

    /** Top N 结果 */
    fun topN(n: Int): List<StrategySignal> = sortedByStrength.take(n)

    /** 人类可读的摘要 */
    fun summary(): String = buildString {
        appendLine("🎯 $strategyName")
        appendLine("   扫描: $totalScanned 只 | 命中: $hitCount 只 | 耗时: ${scanTimeMs}ms")
        if (signals.isNotEmpty()) {
            appendLine("   Top 5:")
            for (signal in topN(5)) {
                appendLine("     ${signal.emoji} ${signal.stockName}(${signal.stockCode}) 强度:${signal.strength}%")
            }
        }
    }
}