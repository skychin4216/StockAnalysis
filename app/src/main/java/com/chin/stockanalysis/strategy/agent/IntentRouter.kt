package com.chin.stockanalysis.strategy.agent

import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * ## 意图路由器
 *
 * 基于用户输入的关键字确定性路由到不同场景，无需 LLM。
 */
class IntentRouter {

    fun resolve(
        userInput: String,
        currentStock: String? = null,
        holdingPeriod: HoldingPeriod? = null
    ): UserIntent {
        val normalized = userInput.lowercase()

        return when {
            // 风控扫描
            normalized.containsAny("风险", "止损", "止盈", "风控", "持仓安全") ->
                UserIntent(IntentType.RISK_CHECK, target = currentStock)

            // 追问
            normalized.containsAny("为什么", "追问", "详细", "解释") && currentStock != null ->
                UserIntent(IntentType.FOLLOW_UP, target = currentStock)

            // 快速扫描
            normalized.containsAny("快速", "概览", "扫描", "市场怎么样") ->
                UserIntent(IntentType.QUICK_SCAN, target = null)

            // 深度分析
            else -> {
                val period = holdingPeriod ?: inferPeriod(normalized)
                UserIntent(IntentType.DEEP_ANALYSIS, target = currentStock, period = period)
            }
        }
    }

    private fun inferPeriod(text: String): HoldingPeriod {
        return when {
            text.containsAny("超短", "日内", "打板", "做t") -> HoldingPeriod.ULTRA_SHORT
            text.containsAny("短线", "几天") -> HoldingPeriod.SHORT
            text.containsAny("中线", "波段", "几周") -> HoldingPeriod.MID
            text.containsAny("长线", "长期", "价值") -> HoldingPeriod.LONG
            else -> HoldingPeriod.SHORT
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { contains(it) }
    }
}

data class UserIntent(
    val type: IntentType,
    val target: String? = null,
    val period: HoldingPeriod? = null
)

enum class IntentType {
    QUICK_SCAN,
    DEEP_ANALYSIS,
    RISK_CHECK,
    FOLLOW_UP
}
