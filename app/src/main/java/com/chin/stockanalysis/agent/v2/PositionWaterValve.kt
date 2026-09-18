package com.chin.stockanalysis.agent.v2

import com.chin.stockanalysis.strategy.market.MarketAnalyzer.MarketReport

/**
 * 仓位水阀管理器（V2.0 顶层战略模块）
 *
 * 根据市场环境量化分类，决定总仓位上限
 */
object PositionWaterValve {

    /**
     * 根据大盘环境计算仓位上限
     */
    fun calculatePositionCap(marketReport: MarketReport): PositionCapResult {
        val trend = marketReport.trend
        val sellType = marketReport.sellType

        return when (trend.direction) {
            "BULLISH" -> {
                val cap = if (trend.strength > 70) 100 else 80
                PositionCapResult(
                    capPercent = cap,
                    strategy = "捂股躺赢",
                    advice = "指数站上MA60且均线多头排列，可满仓或接近满仓操作。逢盘中急跌至MA10/MA20可考虑加仓，严禁做T防止卖飞。",
                    tTradingEnabled = false
                )
            }
            "BEARISH" -> {
                val cap = if (sellType.sellType == "INSTITUTIONAL_EXIT") 10 else 20
                PositionCapResult(
                    capPercent = cap,
                    strategy = "防守反击",
                    advice = "指数跌破MA60且均线空头排列，${if (sellType.sellType == "INSTITUTIONAL_EXIT") "主力撤资中，" else ""}总仓位控制在${cap}%以下。仅做日内超跌反抽，杜绝隔夜重仓。",
                    tTradingEnabled = false
                )
            }
            else -> { // OSCILLATION
                val cap = if (trend.strength > 40) 40 else 50
                PositionCapResult(
                    capPercent = cap,
                    strategy = "高抛低吸（做T）",
                    advice = "指数在MA60上下反复缠绕，市场处于无序震荡期。总仓位控制在${cap}%以下，重点狙击高弹性活跃股的日内波动。",
                    tTradingEnabled = true
                )
            }
        }
    }
}

data class PositionCapResult(
    val capPercent: Int,           // 仓位上限 %
    val strategy: String,          // 策略名称
    val advice: String,            // 策略建议
    val tTradingEnabled: Boolean   // 是否允许做T
)
