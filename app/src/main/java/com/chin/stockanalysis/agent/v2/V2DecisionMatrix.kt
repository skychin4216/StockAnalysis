package com.chin.stockanalysis.agent.v2

import android.util.Log

/**
 * V2.0 决策矩阵（中央处理器）
 *
 * 将市场环境、利润质量、估值定价三者融合，输出最终操作指令
 *
 * 决策规则：
 *   上升市 + 内生性高增 + 低估/合理  → 满仓格局
 *   上升市 + 一次性浮盈 + 低估       → 减仓切换
 *   震荡市 + 内生性高增 + 合理       → 半仓做T
 *   震荡市 + 一次性浮盈 + 看似低估   → 坚决不格局，只做短线反抽
 *   下跌市 + 任何类型                 → 空仓/轻仓
 */
object V2DecisionMatrix {

    private const val TAG = "V2DecisionMatrix"

    data class DecisionInput(
        val environment: MarketEnvironment,
        val positionCap: Int,
        val profitQuality: ProfitQualityAnalysis,
        val pe: Double,
        val pb: Double
    )

    fun evaluate(input: DecisionInput): FinalDecision {
        val env = input.environment.direction
        val quality = input.profitQuality.qualityLevel
        val pe = input.pe
        val cap = input.positionCap

        Log.i(TAG, "決策矩陣: 環境=$env, 利潤質量=$quality, PE=$pe, 倉位上限=$cap%")

        return when {
            // 下跌市：无论什么利润质量，一律空仓/轻仓
            env == "BEARISH" -> FinalDecision(
                action = "空仓观望",
                positionPercent = cap,
                strategy = "現金為王，等待市場企穩",
                tTradingAdvice = null,
                stopLossRule = "跌破MA60且3日內無法收回，強制減倉至50%以下",
                riskWarning = "大盤下行趨勢中，任何個股都可能補跌"
            )

            // 上升市 + 内生性高增
            env == "BULLISH" && quality == ProfitQualityLevel.ENDOGENOUS_GROWTH -> {
                val pos = if (pe > 0 && pe < 30) cap else (cap * 0.8).toInt()
                FinalDecision(
                    action = "滿倉格局",
                    positionPercent = pos,
                    strategy = "基本面優秀 + 市場上行，安心持有",
                    tTradingAdvice = null,
                    stopLossRule = "跌破放量起漲點且3日不收復，減倉50%",
                    riskWarning = null
                )
            }

            // 上升市 + 一次性浮盈
            env == "BULLISH" && quality == ProfitQualityLevel.ONE_TIME_PROFIT -> FinalDecision(
                action = "減倉切換",
                positionPercent = (cap * 0.5).toInt(),
                strategy = "趁上升市流動性好，將倉位切換至內生性高增標的",
                tTradingAdvice = null,
                stopLossRule = "跌破MA10減半，跌破MA20清倉",
                riskWarning = "一次性浮盈不可持續，靜態PE極低可能是市盈率陷阱"
            )

            // 上升市 + 利润注水
            env == "BULLISH" && quality == ProfitQualityLevel.PROFIT_INFLATION -> FinalDecision(
                action = "減倉觀望",
                positionPercent = (cap * 0.3).toInt(),
                strategy = "利潤質量堪憂，即使在大盤上行期也不宜重倉",
                tTradingAdvice = null,
                stopLossRule = "跌破MA10即清倉",
                riskWarning = "經營現金流覆蓋不足，利潤可能被粉飾"
            )

            // 震荡市 + 内生性高增
            env == "OSCILLATION" && quality == ProfitQualityLevel.ENDOGENOUS_GROWTH -> FinalDecision(
                action = "半倉做T",
                positionPercent = cap,
                strategy = "保留底倉不動，利用日內波動高拋低吸",
                tTradingAdvice = "適合做T：保留底倉，急跌時加倉、反彈時減倉，單次T出差價>1.5%",
                stopLossRule = "跌破前期低點-2%無條件止損",
                riskWarning = null
            )

            // 震荡市 + 一次性浮盈（典型陷阱：国泰海通案例）
            env == "OSCILLATION" && quality == ProfitQualityLevel.ONE_TIME_PROFIT -> FinalDecision(
                action = "短線反抽",
                positionPercent = (cap * 0.3).toInt(),
                strategy = "堅決不格局，只做短線反抽。趁利好沖高時清倉離場",
                tTradingAdvice = "反T為主：早盤急拉時賣出，回落後謹慎接回（不超過原倉位30%）",
                stopLossRule = "買入後跌破買入價-2%立即止損",
                riskWarning = "一次性浮盈在震蕩市中最危險：靜態PE極低誘人，但利潤不可持續"
            )

            // 震荡市 + 利润注水
            env == "OSCILLATION" && quality == ProfitQualityLevel.PROFIT_INFLATION -> FinalDecision(
                action = "觀望",
                positionPercent = (cap * 0.2).toInt(),
                strategy = "利潤質量差 + 震蕩市，不參與為妙",
                tTradingAdvice = null,
                stopLossRule = "已有倉位跌破MA20清倉",
                riskWarning = "現金流覆蓋不足 + 震蕩市雙重風險"
            )

            // 数据不足：保守处理
            else -> FinalDecision(
                action = "謹慎觀望",
                positionPercent = (cap * 0.5).toInt(),
                strategy = "數據不足，無法準確判斷，建議小倉位試探或觀望",
                tTradingAdvice = null,
                stopLossRule = "任何倉位跌破MA20清倉",
                riskWarning = "利潤質量或估值數據不足，決策置信度低"
            )
        }
    }
}
