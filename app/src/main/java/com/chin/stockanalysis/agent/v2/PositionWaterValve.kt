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
                    advice = "指數站上MA60且均線多頭排列，可滿倉或接近滿倉操作。逢盤中急跌至MA10/MA20可考慮加倉，嚴禁做T防止賣飛。",
                    tTradingEnabled = false
                )
            }
            "BEARISH" -> {
                val cap = if (sellType.sellType == "INSTITUTIONAL_EXIT") 10 else 20
                PositionCapResult(
                    capPercent = cap,
                    strategy = "防守反擊",
                    advice = "指數跌破MA60且均線空頭排列，${if (sellType.sellType == "INSTITUTIONAL_EXIT") "主力撤資中，" else ""}總倉位控制在${cap}%以下。僅做日內超跌反抽，杜絕隔夜重倉。",
                    tTradingEnabled = false
                )
            }
            else -> { // OSCILLATION
                val cap = if (trend.strength > 40) 40 else 50
                PositionCapResult(
                    capPercent = cap,
                    strategy = "高拋低吸（做T）",
                    advice = "指數在MA60上下反覆纏繞，市場處於無序震蕩期。總倉位控制在${cap}%以下，重點狙擊高彈性活躍股的日內波動。",
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
