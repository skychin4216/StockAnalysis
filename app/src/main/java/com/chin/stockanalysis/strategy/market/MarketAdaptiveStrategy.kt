package com.chin.stockanalysis.strategy.market

import com.chin.stockanalysis.agent.v2.PositionWaterValve

/**
 * 市场自适应策略（Market State-Driven Strategy Adaptation）
 *
 * 根据大盘环境（BULLISH/BEARISH/OSCILLATION）动态调整：
 * - AI 选股评分阈值
 * - 最大买入数量
 * - 选股因子主题
 * - 止盈止损系数
 * - 空仓触发条件
 */
object MarketAdaptiveStrategy {

    /**
     * 自适应参数（动态计算结果）
     */
    data class AdaptiveParams(
        /** AI 选股评分阈值 */
        val scoreThreshold: Int,
        /** 最大买入数量 */
        val maxStockCount: Int,
        /** 选股因子主题描述（注入 AI prompt） */
        val strategyTheme: String,
        /** 止损系数（如 -0.05 表示 -5%） */
        val stopLossRate: Double,
        /** 止盈系数（如 0.15 表示 +15%） */
        val takeProfitRate: Double,
        /** 是否触发空仓（不进行任何买入） */
        val forceEmpty: Boolean = false,
        /** 仓位上限（来自 PositionWaterValve） */
        val positionCapPercent: Int,
        /** 策略描述（用于日志和 UI 显示） */
        val description: String,
        /** 大盘方向：BULLISH / BEARISH / OSCILLATION（usecase XML 环境路由判据） */
        val direction: String = "OSCILLATION"
    )

    /**
     * 根据大盘报告计算自适应参数
     */
    fun calculate(marketReport: MarketAnalyzer.MarketReport): AdaptiveParams {
        val direction = marketReport.trend.direction
        val strength = marketReport.trend.strength
        val capResult = PositionWaterValve.calculatePositionCap(marketReport)

        return when {
            // 强趋势下跌：严格但不极端
            direction == "BEARISH" && strength > 40 -> AdaptiveParams(
                scoreThreshold = 55,
                maxStockCount = 2,
                strategyTheme = "防御因子（低Beta+高股息+现金充沛+经营现金流/净利润>0.8）",
                stopLossRate = -0.03,
                takeProfitRate = 0.05,
                positionCapPercent = capResult.capPercent,
                description = "📉 强趋势空头（强度${strength}）：阈值55，最多2只，快进快出",
                direction = direction
            )
            // 弱趋势下跌
            direction == "BEARISH" -> AdaptiveParams(
                scoreThreshold = 60,
                maxStockCount = 2,
                strategyTheme = "平衡因子（低Beta+合同负债增长+稳定现金流）",
                stopLossRate = -0.04,
                takeProfitRate = 0.07,
                positionCapPercent = capResult.capPercent,
                description = "📉 弱趋势空头（强度${strength}）：阈值60，最多2只",
                direction = direction
            )
            // 多头上升
            direction == "BULLISH" -> AdaptiveParams(
                scoreThreshold = 50,
                maxStockCount = 4,
                strategyTheme = "进攻因子（高Beta+高成长+高换手率+合同负债增速）",
                stopLossRate = -0.08,
                takeProfitRate = 0.15,
                positionCapPercent = capResult.capPercent,
                description = "📈 多头上升（强度${strength}）：阈值50，最多4只，让利润奔驰",
                direction = direction
            )
            // 震荡
            else -> AdaptiveParams(
                scoreThreshold = 55,
                maxStockCount = 3,
                strategyTheme = "做T因子（振幅>4%+流动性前20%+适合日内波动）",
                stopLossRate = -0.05,
                takeProfitRate = 0.07,
                positionCapPercent = capResult.capPercent,
                description = "📊 震荡市（强度${strength}）：阈值55，最多3只，配合做T",
                direction = direction
            )
        }
    }

    /**
     * 构建注入 AI prompt 的结构化市场环境文本
     */
    fun buildMarketPrompt(params: AdaptiveParams): String = buildString {
        appendLine("## 🌊 市场自适应选股约束（必须遵守）")
        appendLine()
        appendLine("### 当前市场环境参数")
        appendLine("- 可用总仓位上限：${params.positionCapPercent}%")
        appendLine("- AI 综合评分阈值：≥ ${params.scoreThreshold} 分")
        appendLine("- 最大买入数量：${params.maxStockCount} 只")
        appendLine("- 策略主题：${params.strategyTheme}")
        appendLine()
        appendLine("### 选股规则")
        appendLine("1. 从备选池中挑选符合策略主题的个股")
        appendLine("2. 综合评分必须 ≥ ${params.scoreThreshold} 分，低于此分数的不得推荐")
        appendLine("3. 推荐数量不超过 ${params.maxStockCount} 只（宁缺毋滥）")
        appendLine("4. 评分理由中必须明确说明个股是否符合「${params.strategyTheme}」主题")
        appendLine("5. **如果备选池中没有符合门槛的个股，请直接返回「空仓建议」**，不要退而求其次选低分股")
        appendLine()
        if (params.stopLossRate < -0.05) {
            appendLine("### 止损止盈参考")
            appendLine("- 当前环境建议止损：${(params.stopLossRate * 100).toInt()}%")
            appendLine("- 当前环境建议止盈：+${(params.takeProfitRate * 100).toInt()}%")
        }
    }

    /**
     * 空仓触发判断
     * 条件：BEARISH + ADX>40 + AI 高分股不足 2 只
     */
    fun shouldForceEmpty(
        marketReport: MarketAnalyzer.MarketReport,
        highScoreCount: Int  // AI 评分 >= scoreThreshold 的数量
    ): Boolean {
        return marketReport.trend.direction == "BEARISH"
            && marketReport.trend.strength > 40
            && highScoreCount < 2
    }

    /**
     * 无热点板块时的防御型选股 prompt
     */
    fun buildDefensiveFallbackPrompt(): String = """
        ⚠️ 当前市场无明显热点板块（V型≥2的板块数为0）。

        请启用「防御型个股筛选逻辑」：
        - 不看行业概念，只看以下硬指标：
          1. 股息率 > 3%（如有数据）
          2. 市净率 < 1.5（低估值安全边际）
          3. 北向资金连续 3 日净流入（如有数据）
          4. 经营性现金流 / 净利润 > 0.8（现金流质量好）
        - 如果满足以上条件的个股数量不足，返回「空仓建议」。
    """.trimIndent()
}
