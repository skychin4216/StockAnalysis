package com.chin.stockanalysis.agent.pipeline

/**
 * 流水線子 Agent 輸出結構（供 StructuredOutputParser 解析、DeepAnalystEngine 使用）
 *
 * 精簡後僅保留仍被引用的數據類。
 */

/** Agent 1 初選池中的個股 */
data class FilteredStock(
    val stockCode: String,
    val stockName: String,
    val filterReason: String
)

/** Agent 2 產業鏈打分結果（唯一打分核心） */
data class ChainScoreResult(
    val stockCode: String,
    val stockName: String,
    val baseScore: Int,
    val materialScore: Int = 0,
    val barrierScore: Int = 0,
    val coverageScore: Int = 0,
    val irreplaceScore: Int = 0,
    val resonanceBonus: Int = 0,
    val overseasBonus: Int = 0,
    val foreignRatingBonus: Int = 0,
    val totalScore: Int,
    val barrierLevel: String,
    val passed: Boolean
)

/** Agent 5 風控終審結果 */
data class RiskValidationResult(
    val stockCode: String,
    val riskLevel: String,
    val deductions: List<RiskDeduction> = emptyList(),
    val overseasDeduction: Int = 0,
    val adjustedScore: Int = 0,
    val passed: Boolean
)

data class RiskDeduction(
    val item: String,
    val description: String,
    val score: Int
)

/** Agent D 輿情微調結果 */
data class SentimentAdjustResult(
    val sentimentScore: Int,
    val positionAdjust: String,
    val reason: String
)

/** Agent 4 交易執行方案 */
data class TradeExecutionPlan(
    val stockCode: String,
    val stockName: String,
    val entryZones: List<String>,
    val stopLoss: String,
    val targets: List<String>,
    val maxPosition: String,
    val splitRatio: String,
    val tradeRules: List<String>
)
