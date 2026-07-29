package com.chin.stockanalysis.agent.core

import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * 用戶意圖類型
 */
enum class IntentType {
    QUICK_SCAN,      // 快速掃描：純量化，無 LLM（僅 Scout）
    DEEP_ANALYSIS,   // 深度分析：全鏈路 Agent 群
    RISK_CHECK,      // 風控掃描：僅 Guardian
    FOLLOW_UP        // 追問：僅 Analyst（對已有分析結果的後續提問）
}

/**
 * 用戶意圖解析結果
 *
 * @property type 意圖類型
 * @property target 目標股票代碼（null 表示全市場掃描）
 * @property period 交易週期（DEEP_ANALYSIS 時必填）
 * @property rawInput 原始用戶輸入
 */
data class UserIntent(
    val type: IntentType,
    val target: String? = null,
    val period: HoldingPeriod? = null,
    val rawInput: String = ""
)

/**
 * 意圖路由器 — 確定性路由，無需 LLM
 *
 * 基於「消息來源」（交易週期 + 用戶意圖類型）進行確定性分流。
 * 規則明確，零 token 消耗。
 *
 * 路由優先級（從高到低）：
 * 1. RISK_CHECK — 用戶只想看持倉風險
 * 2. QUICK_SCAN — 快速市場概覽
 * 3. DEEP_ANALYSIS + HoldingPeriod — 全鏈路分析
 * 4. FOLLOW_UP — 對已有結果追問
 */
class IntentRouter {

    fun resolve(
        userInput: String,
        currentStock: String? = null,
        holdingPeriod: HoldingPeriod? = null
    ): UserIntent {
        val normalized = userInput.lowercase()

        return when {
            // 風控掃描：用戶想看持倉風險
            normalized.containsAny("風險", "止損", "止盈", "風控", "持倉安全", "倉位") ->
                UserIntent(IntentType.RISK_CHECK, target = currentStock, rawInput = userInput)

            // 快速掃描：市場概覽
            normalized.containsAny("快速", "概覽", "掃描", "市場怎麼樣", "大盤", "盤面") ->
                UserIntent(IntentType.QUICK_SCAN, rawInput = userInput)

            // 追問：對已有分析結果的後續提問
            normalized.containsAny("為什麼", "追問", "詳細", "解釋", "怎麼理解") && currentStock != null ->
                UserIntent(IntentType.FOLLOW_UP, target = currentStock, rawInput = userInput)

            // 深度分析：指定股票或選股（默認路徑）
            else -> {
                val period = holdingPeriod ?: inferPeriod(normalized)
                UserIntent(IntentType.DEEP_ANALYSIS, target = currentStock, period = period, rawInput = userInput)
            }
        }
    }

    /**
     * 從文本推斷交易週期
     */
    private fun inferPeriod(text: String): HoldingPeriod {
        return when {
            text.containsAny("超短", "日內", "打板", "隔日", "T+1") -> HoldingPeriod.ULTRA_SHORT
            text.containsAny("短線", "幾天", "一週", "短線") -> HoldingPeriod.SHORT
            text.containsAny("中線", "波段", "幾週", "一個月") -> HoldingPeriod.MID
            text.containsAny("長線", "長期", "價值", "持有") -> HoldingPeriod.LONG
            else -> HoldingPeriod.SHORT  // 默認短線
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }
}

/**
 * Agent 集群配置 — 每個交易週期的 Agent 組合和參數
 *
 * @property period 交易週期
 * @property orchestratorTimeout 編排者超時
 * @property analystSteps 分析師步驟數
 * @property analystTimeout 分析師超時
 * @property guardianTimeout 風控官超時
 * @property cacheTtlMinutes 緩存有效期
 * @property maxTotalConcurrent 最大總並發數
 */
data class AgentClusterConfig(
    val period: HoldingPeriod,
    val orchestratorTimeout: Long,
    val analystSteps: Int,
    val analystTimeout: Long,
    val guardianTimeout: Long,
    val cacheTtlMinutes: Int,
    val maxTotalConcurrent: Int
) {
    companion object {
        // DeepAnalystEngine 最多 3 階段串行 LLM（每階段 ≤90s），超時需覆蓋完整流程
        fun forPeriod(period: HoldingPeriod): AgentClusterConfig = when (period) {
            HoldingPeriod.ULTRA_SHORT -> AgentClusterConfig(
                period = period,
                orchestratorTimeout = 120_000,
                analystSteps = 3,       // 技術+輿情+風控（2階段）
                analystTimeout = 90_000,
                guardianTimeout = 5_000,
                cacheTtlMinutes = 5,
                maxTotalConcurrent = 6
            )
            HoldingPeriod.SHORT -> AgentClusterConfig(
                period = period,
                orchestratorTimeout = 180_000,
                analystSteps = 5,       // +基本面+賽道
                analystTimeout = 150_000,
                guardianTimeout = 15_000,
                cacheTtlMinutes = 15,
                maxTotalConcurrent = 10
            )
            HoldingPeriod.MID -> AgentClusterConfig(
                period = period,
                orchestratorTimeout = 240_000,
                analystSteps = 6,       // +產業鏈（全量）
                analystTimeout = 210_000,
                guardianTimeout = 30_000,
                cacheTtlMinutes = 60,
                maxTotalConcurrent = 12
            )
            HoldingPeriod.LONG -> AgentClusterConfig(
                period = period,
                orchestratorTimeout = 240_000,
                analystSteps = 6,       // 全量深度分析
                analystTimeout = 210_000,
                guardianTimeout = 30_000,
                cacheTtlMinutes = 120,
                maxTotalConcurrent = 12
            )
        }
    }
}
