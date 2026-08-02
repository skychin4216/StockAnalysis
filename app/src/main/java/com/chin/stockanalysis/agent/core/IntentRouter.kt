package com.chin.stockanalysis.agent.core

import com.chin.stockanalysis.strategy.HoldingPeriod

/**
 * 用戶意圖類型
 */
enum class IntentType {
    QUICK_SCAN,      // 快速掃描：純量化，無 LLM（僅 Scout）
    DEEP_ANALYSIS,   // 深度分析：全鏈路 Agent 群
    RISK_CHECK,      // 風控掃描：僅 Guardian
    FOLLOW_UP,       // 追問：僅 Analyst（對已有分析結果的後續提問）
    GENERAL_CHAT     // 通用問答：知識問答、閒聊、非股票相關問題
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

            // 通用問答：知識問答、閒聊、非股票相關問題（優先於 DEEP_ANALYSIS，避免浪費 LLM 調用）
            isGeneralChat(normalized, currentStock) ->
                UserIntent(IntentType.GENERAL_CHAT, rawInput = userInput)

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

    /**
     * 判斷是否為通用問答（非股票分析請求）。
     *
     * 策略：如果輸入不包含股票代碼、股票名稱或市場分析關鍵詞，
     * 且看起來像一般問題、閒聊或知識問答，則路由到 GENERAL_CHAT。
     * 寧可誤判為 GENERAL_CHAT（1 次 LLM 調用），也不要誤判為 DEEP_ANALYSIS（5-17 次 LLM 調用）。
     */
    private fun isGeneralChat(text: String, currentStock: String?): Boolean {
        // 如果有明確的當前股票上下文，且輸入較短（可能是追問），不走 GENERAL_CHAT
        // 但如果輸入明顯是知識問答，即使有 currentStock 也走 GENERAL_CHAT

        // ── 1. 閒聊/問候語 ──
        if (text.containsAny(
                "你好", "您好", "hello", "hi", "嗨", "再見", "bye",
                "謝謝", "感謝", "thanks", "thank you",
                "幫助", "help", "能做什麼", "有什麼功能", "功能"
            )
        ) return true

        // ── 2. 金融知識/概念問答 ──
        if (text.containsAny(
                "什麼是", "是什麼", "怎麼看", "如何看", "怎麼用", "如何用",
                "怎麼選股", "如何選股", "怎麼分析", "如何分析",
                "怎麼設置", "如何設置", "怎麼設定", "如何設定",
                "止損怎麼", "止盈怎麼", "止損如何", "止盈如何",
                "什麼意思", "是什麼意思", "怎麼理解",
                "pe", "pb", "roe", "macd", "kdj", "rsi", "布林", "boll",
                "均線", "成交量", "換手率", "市盈率", "市淨率", "淨資產",
                "k線", "陽線", "陰線", "十字星", "漲停", "跌停",
                "基本面", "技術面", "消息面", "政策面",
                "價值投資", "趨勢交易", "短線技巧", "操盤",
                "仓位管理", "資金管理", "風險管理",
                "etf", "指數", "基金", "債券", "期貨", "期權",
                "牛市", "熊市", "震盪", "行情"
            )
        ) {
            // 排除明確包含股票代碼的情況（如「600519是什麼意思」→ 可能是問股票）
            if (!containsStockCode(text)) return true
        }

        // ── 3. 沒有 currentStock 且輸入不包含市場分析關鍵詞 → 大概率閒聊 ──
        if (currentStock == null && !text.containsAny(
                "分析", "推薦", "選股", "買入", "賣出", "持倉", "建倉", "加倉",
                "減倉", "清倉", "目標價", "支撐", "壓力", "突破",
                "漲", "跌", "走勢", "趨勢", "板塊", "概念", "龍頭",
                "主力", "資金流", "北向", "外資", "融資", "融券"
            )
        ) {
            // 輸入較短（< 30 字）且是問句形式
            if (text.length < 30 && (text.contains("？") || text.contains("?") ||
                        text.containsAny("嗎", "呢", "麼", "嘛", "咋", "怎麼", "如何", "什麼", "为啥", "為什麼"))
            ) return true
        }

        return false
    }

    /**
     * 簡單檢查文本中是否包含股票代碼（6位數字，可能帶市場前綴）。
     */
    private fun containsStockCode(text: String): Boolean {
        // 匹配 6 位數字股票代碼（可選 sh/sz/sh6/sh9 前綴）
        val codePattern = Regex("""(?:sh|sz)?\d{6}""")
        return codePattern.containsMatchIn(text)
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
