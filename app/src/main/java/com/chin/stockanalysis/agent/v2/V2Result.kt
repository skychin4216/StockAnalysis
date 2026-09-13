package com.chin.stockanalysis.agent.v2

import com.chin.stockanalysis.agent.pipeline.QuarterlyComparisonResult
//import com.chin.stockanalysis.strategy.market.MarketReport

/**
 * V2.0 全周期投研与交易决策系统结果
 *
 * 在 V1.0（基本面分析 + 估值定价）基础上，新增：
 * - 市场环境量化分类（上升/震荡/下跌）
 * - 利润质量剪刀差分析
 * - 仓位水阀管理
 * - 决策矩阵（环境 × 利润质量 × 估值）
 */
data class V2Result(
    val stockCode: String,
    val stockName: String,
    val success: Boolean = true,

    // === 市场环境（顶层战略）===
    val marketEnvironment: MarketEnvironment,
    val positionCap: Int,  // 仓位上限 %

    // === 利润质量（中层战术）===
    val profitQuality: ProfitQualityAnalysis,

    // === 估值定价 ===
    val valuation: ValuationAssessment,

    // === 决策矩阵输出 ===
    val finalDecision: FinalDecision,

    // === V1.0 兼容字段 ===
    val overallScore: Int = 0,
    val recommendation: String = "WATCH",
    val confidence: String = "LOW",
    val riskLevel: String? = null,
    val targetPrice: String? = null,
    val stopLoss: String? = null,

    // === 原始分析文本 ===
    val summaryText: String = "",
    val rawOutput: String = "",
    val errorMessage: String? = null,
    val elapsedMs: Long = 0
)

/** 市场环境分类 */
data class MarketEnvironment(
    val direction: String,       // BULLISH / BEARISH / OSCILLATION
    val strength: Int,           // 0-100
    val description: String,     // 趋势描述
    val sellType: String = "NONE" // 主力撤资/量化砸盘/正常
)

/** 利润质量分析 */
data class ProfitQualityAnalysis(
    val qualityLevel: ProfitQualityLevel,  // 内生性高增 / 一次性浮盈 / 利润注水 / 数据不足
    val netProfit: Double,                 // 净利润（亿元）
    val deductNetProfit: Double,           // 扣非净利润（亿元）
    val profitGap: Double,                 // 剪刀差 = 净利润 - 扣非净利润（亿元）
    val profitGapRatio: Double,            // 剪刀差占比 = profitGap / netProfit
    val operatingCashFlowRatio: Double,    // 经营现金流 / 净利润
    val revenueYoY: Double,                // 营收同比增长 %
    val netProfitYoY: Double,              // 净利润同比增长 %
    val deductNpYoY: Double,               // 扣非净利润同比增长 %
    val grossMargin: Double,               // 毛利率 %
    val roe: Double,                       // ROE %
    val warningFlags: List<String> = emptyList(),  // 警告标签
    val reportDate: String = ""
)

enum class ProfitQualityLevel {
    ENDOGENOUS_GROWTH,    // 内生性高增（真成长）
    ONE_TIME_PROFIT,      // 一次性浮盈（纸面富贵）
    PROFIT_INFLATION,     // 利润注水（现金流覆盖不足）
    INSUFFICIENT_DATA     // 数据不足
}

/** 估值评估 */
data class ValuationAssessment(
    val pe: Double,
    val pb: Double,
    val peBandPosition: String,   // 低估 / 合理 / 高估（基于历史分位）
    val valuationWarning: String? = null  // 市盈率陷阱警告
)

/** 最终决策 */
data class FinalDecision(
    val action: String,           // 满仓格局 / 半仓做T / 减仓切换 / 空仓观望 / 短线反抽
    val positionPercent: Int,     // 建议仓位 %
    val strategy: String,         // 策略描述
    val tTradingAdvice: String? = null,  // 做T建议（仅在震荡市）
    val stopLossRule: String,     // 止损规则
    val riskWarning: String? = null      // 风险提示
)
