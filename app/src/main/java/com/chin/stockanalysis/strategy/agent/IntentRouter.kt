package com.chin.stockanalysis.strategy.agent

import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * ## 意圖路由器
 *
 * 基於用戶輸入的關鍵字確定性路由到不同場景，無需 LLM。
 */
class IntentRouter {

    fun resolve(
        userInput: String,
        currentStock: String? = null,
        holdingPeriod: HoldingPeriod? = null
    ): UserIntent {
        val normalized = userInput.lowercase()

        return when {
            // 風控掃描
            normalized.containsAny("風險", "止損", "止盈", "風控", "持倉安全") ->
                UserIntent(IntentType.RISK_CHECK, target = currentStock)

            // 追問
            normalized.containsAny("為什麼", "追問", "詳細", "解釋") && currentStock != null ->
                UserIntent(IntentType.FOLLOW_UP, target = currentStock)

            // 快速掃描
            normalized.containsAny("快速", "概覽", "掃描", "市場怎麼樣") ->
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
            text.containsAny("超短", "日內", "打板", "做t") -> HoldingPeriod.ULTRA_SHORT
            text.containsAny("短線", "幾天") -> HoldingPeriod.SHORT
            text.containsAny("中線", "波段", "幾週") -> HoldingPeriod.MID
            text.containsAny("長線", "長期", "價值") -> HoldingPeriod.LONG
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
