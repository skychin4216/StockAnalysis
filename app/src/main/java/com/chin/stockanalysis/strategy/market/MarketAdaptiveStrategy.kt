package com.chin.stockanalysis.strategy.market

import com.chin.stockanalysis.agent.v2.PositionWaterValve

/**
 * 市場自適應策略（Market State-Driven Strategy Adaptation）
 *
 * 根據大盤環境（BULLISH/BEARISH/OSCILLATION）動態調整：
 * - AI 選股評分閾值
 * - 最大買入數量
 * - 選股因子主題
 * - 止盈止損係數
 * - 空倉觸發條件
 */
object MarketAdaptiveStrategy {

    /**
     * 自適應參數（動態計算結果）
     */
    data class AdaptiveParams(
        /** AI 選股評分閾值 */
        val scoreThreshold: Int,
        /** 最大買入數量 */
        val maxStockCount: Int,
        /** 選股因子主題描述（注入 AI prompt） */
        val strategyTheme: String,
        /** 止損係數（如 -0.05 表示 -5%） */
        val stopLossRate: Double,
        /** 止盈係數（如 0.15 表示 +15%） */
        val takeProfitRate: Double,
        /** 是否觸發空倉（不進行任何買入） */
        val forceEmpty: Boolean = false,
        /** 倉位上限（來自 PositionWaterValve） */
        val positionCapPercent: Int,
        /** 策略描述（用於日誌和 UI 顯示） */
        val description: String
    )

    /**
     * 根據大盤報告計算自適應參數
     */
    fun calculate(marketReport: MarketAnalyzer.MarketReport): AdaptiveParams {
        val direction = marketReport.trend.direction
        val strength = marketReport.trend.strength
        val capResult = PositionWaterValve.calculatePositionCap(marketReport)

        return when {
            // 強趨勢下跌：嚴格但不極端
            direction == "BEARISH" && strength > 40 -> AdaptiveParams(
                scoreThreshold = 55,
                maxStockCount = 2,
                strategyTheme = "防禦因子（低Beta+高股息+現金充沛+經營現金流/淨利潤>0.8）",
                stopLossRate = -0.03,
                takeProfitRate = 0.05,
                positionCapPercent = capResult.capPercent,
                description = "📉 強趨勢空頭（強度${strength}）：閾值55，最多2只，快進快出"
            )
            // 弱趨勢下跌
            direction == "BEARISH" -> AdaptiveParams(
                scoreThreshold = 60,
                maxStockCount = 2,
                strategyTheme = "平衡因子（低Beta+合同負債增長+穩定現金流）",
                stopLossRate = -0.04,
                takeProfitRate = 0.07,
                positionCapPercent = capResult.capPercent,
                description = "📉 弱趨勢空頭（強度${strength}）：閾值60，最多2只"
            )
            // 多頭上升
            direction == "BULLISH" -> AdaptiveParams(
                scoreThreshold = 50,
                maxStockCount = 4,
                strategyTheme = "進攻因子（高Beta+高成長+高換手率+合同負債增速）",
                stopLossRate = -0.08,
                takeProfitRate = 0.15,
                positionCapPercent = capResult.capPercent,
                description = "📈 多頭上升（強度${strength}）：閾值50，最多4只，讓利潤奔馳"
            )
            // 震蕩
            else -> AdaptiveParams(
                scoreThreshold = 55,
                maxStockCount = 3,
                strategyTheme = "做T因子（振幅>4%+流動性前20%+適合日內波動）",
                stopLossRate = -0.05,
                takeProfitRate = 0.07,
                positionCapPercent = capResult.capPercent,
                description = "📊 震蕩市（強度${strength}）：閾值55，最多3只，配合做T"
            )
        }
    }

    /**
     * 構建注入 AI prompt 的結構化市場環境文本
     */
    fun buildMarketPrompt(params: AdaptiveParams): String = buildString {
        appendLine("## 🌊 市場自適應選股約束（必須遵守）")
        appendLine()
        appendLine("### 當前市場環境參數")
        appendLine("- 可用總倉位上限：${params.positionCapPercent}%")
        appendLine("- AI 綜合評分閾值：≥ ${params.scoreThreshold} 分")
        appendLine("- 最大買入數量：${params.maxStockCount} 只")
        appendLine("- 策略主題：${params.strategyTheme}")
        appendLine()
        appendLine("### 選股規則")
        appendLine("1. 從備選池中挑選符合策略主題的個股")
        appendLine("2. 綜合評分必須 ≥ ${params.scoreThreshold} 分，低於此分數的不得推薦")
        appendLine("3. 推薦數量不超過 ${params.maxStockCount} 只（寧缺毋濫）")
        appendLine("4. 評分理由中必須明確說明個股是否符合「${params.strategyTheme}」主題")
        appendLine("5. **如果備選池中沒有符合門檻的個股，請直接返回「空倉建議」**，不要退而求其次選低分股")
        appendLine()
        if (params.stopLossRate < -0.05) {
            appendLine("### 止損止盈參考")
            appendLine("- 當前環境建議止損：${(params.stopLossRate * 100).toInt()}%")
            appendLine("- 當前環境建議止盈：+${(params.takeProfitRate * 100).toInt()}%")
        }
    }

    /**
     * 空倉觸發判斷
     * 條件：BEARISH + ADX>40 + AI 高分股不足 2 只
     */
    fun shouldForceEmpty(
        marketReport: MarketAnalyzer.MarketReport,
        highScoreCount: Int  // AI 評分 >= scoreThreshold 的數量
    ): Boolean {
        return marketReport.trend.direction == "BEARISH"
            && marketReport.trend.strength > 40
            && highScoreCount < 2
    }

    /**
     * 無熱點板塊時的防御型選股 prompt
     */
    fun buildDefensiveFallbackPrompt(): String = """
        ⚠️ 當前市場無明顯熱點板塊（V型≥2的板塊數為0）。

        請啟用「防御型個股篩選邏輯」：
        - 不看行業概念，只看以下硬指標：
          1. 股息率 > 3%（如有數據）
          2. 市淨率 < 1.5（低估值安全邊際）
          3. 北向資金連續 3 日淨流入（如有數據）
          4. 經營性現金流 / 淨利潤 > 0.8（現金流質量好）
        - 如果滿足以上條件的個股數量不足，返回「空倉建議」。
    """.trimIndent()
}
