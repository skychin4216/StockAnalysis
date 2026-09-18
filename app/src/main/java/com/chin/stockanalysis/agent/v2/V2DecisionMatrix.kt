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

        Log.i(TAG, "决策矩阵: 环境=$env, 利润质量=$quality, PE=$pe, 仓位上限=$cap%")

        return when {
            // 下跌市：无论什么利润质量，一律空仓/轻仓
            env == "BEARISH" -> FinalDecision(
                action = "空仓观望",
                positionPercent = cap,
                strategy = "现金为王，等待市场企稳",
                tTradingAdvice = null,
                stopLossRule = "跌破MA60且3日内无法收回，强制减仓至50%以下",
                riskWarning = "大盘下行趋势中，任何个股都可能补跌"
            )

            // 上升市 + 内生性高增
            env == "BULLISH" && quality == ProfitQualityLevel.ENDOGENOUS_GROWTH -> {
                val pos = if (pe > 0 && pe < 30) cap else (cap * 0.8).toInt()
                FinalDecision(
                    action = "满仓格局",
                    positionPercent = pos,
                    strategy = "基本面优秀 + 市场上行，安心持有",
                    tTradingAdvice = null,
                    stopLossRule = "跌破放量起涨点且3日不收复，减仓50%",
                    riskWarning = null
                )
            }

            // 上升市 + 一次性浮盈
            env == "BULLISH" && quality == ProfitQualityLevel.ONE_TIME_PROFIT -> FinalDecision(
                action = "减仓切换",
                positionPercent = (cap * 0.5).toInt(),
                strategy = "趁上升市流动性好，将仓位切换至内生性高增标的",
                tTradingAdvice = null,
                stopLossRule = "跌破MA10减半，跌破MA20清仓",
                riskWarning = "一次性浮盈不可持续，静态PE极低可能是市盈率陷阱"
            )

            // 上升市 + 利润注水
            env == "BULLISH" && quality == ProfitQualityLevel.PROFIT_INFLATION -> FinalDecision(
                action = "减仓观望",
                positionPercent = (cap * 0.3).toInt(),
                strategy = "利润质量堪忧，即使在大盘上行期也不宜重仓",
                tTradingAdvice = null,
                stopLossRule = "跌破MA10即清仓",
                riskWarning = "经营现金流覆盖不足，利润可能被粉饰"
            )

            // 震荡市 + 内生性高增
            env == "OSCILLATION" && quality == ProfitQualityLevel.ENDOGENOUS_GROWTH -> FinalDecision(
                action = "半仓做T",
                positionPercent = cap,
                strategy = "保留底仓不动，利用日内波动高抛低吸",
                tTradingAdvice = "适合做T：保留底仓，急跌时加仓、反弹时减仓，单次T出差价>1.5%",
                stopLossRule = "跌破前期低点-2%无条件止损",
                riskWarning = null
            )

            // 震荡市 + 一次性浮盈（典型陷阱：国泰海通案例）
            env == "OSCILLATION" && quality == ProfitQualityLevel.ONE_TIME_PROFIT -> FinalDecision(
                action = "短线反抽",
                positionPercent = (cap * 0.3).toInt(),
                strategy = "坚决不格局，只做短线反抽。趁利好冲高时清仓离场",
                tTradingAdvice = "反T为主：早盘急拉时卖出，回落后谨慎接回（不超过原仓位30%）",
                stopLossRule = "买入后跌破买入价-2%立即止损",
                riskWarning = "一次性浮盈在震荡市中最危险：静态PE极低诱人，但利润不可持续"
            )

            // 震荡市 + 利润注水
            env == "OSCILLATION" && quality == ProfitQualityLevel.PROFIT_INFLATION -> FinalDecision(
                action = "观望",
                positionPercent = (cap * 0.2).toInt(),
                strategy = "利润质量差 + 震荡市，不参与为妙",
                tTradingAdvice = null,
                stopLossRule = "已有仓位跌破MA20清仓",
                riskWarning = "现金流覆盖不足 + 震荡市双重风险"
            )

            // 数据不足：保守处理
            else -> FinalDecision(
                action = "谨慎观望",
                positionPercent = (cap * 0.5).toInt(),
                strategy = "数据不足，无法准确判断，建议小仓位试探或观望",
                tTradingAdvice = null,
                stopLossRule = "任何仓位跌破MA20清仓",
                riskWarning = "利润质量或估值数据不足，决策置信度低"
            )
        }
    }
}
