package com.chin.stockanalysis.agent.pipeline

/**
 * 流水線上下文 — 在 7 步之間傳遞的累積狀態
 */
data class PipelineContext(
    /** 用戶輸入的標的（如 "生益科技" 或 "光通信板塊"） */
    val target: String,
    /** 動態識別的板塊/賽道（可由用戶輸入推斷，默認為科技板塊） */
    var sector: String = "科技",
    /** 當前分析模式（使用 String 避免循環引用） */
    var analysisModeName: String = "精簡版",
    /** Agent F 採集的標準化情報 */
    var intelligence: DataFeederResult? = null,
    /** Agent 3 的賽道熱度評級 */
    var sectorHeatLevel: String? = null,
    /** Agent 1 的初選池 */
    var filteredPool: List<FilteredStock>? = null,
    /** Agent 2 的產業鏈打分結果 */
    var chainScore: ChainScoreResult? = null,
    /** Agent 5 的風控結果 */
    var riskResult: RiskValidationResult? = null,
    /** Agent D 的輿情微調結果 */
    var sentimentResult: SentimentAdjustResult? = null,
    /** Agent 4 的交易執行方案 */
    var tradePlan: TradeExecutionPlan? = null,
    /** 量化策略信號（Phase 2 注入） */
    var quantSignals: String? = null,
    /** 最終倉位微調比例（如 "+5%"） */
    var positionAdjust: String? = null,
    /** 每步的原始 Markdown 分析文本 */
    val stepAnalyses: MutableMap<Int, String> = mutableMapOf()
)

/** Agent F 數據底座採集結果 */
data class DataFeederResult(
    val events: List<String> = emptyList(),         // 催化事件
    val ratings: List<String> = emptyList(),         // 外資評級摘要
    val supplyChain: List<String> = emptyList(),     // 供需格局
    val timestamp: Long = System.currentTimeMillis()
)

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
    val baseScore: Int,           // 基礎分（不含海外/外資加分）
    val materialScore: Int = 0,   // 認證週期
    val barrierScore: Int = 0,    // 產能壁壘
    val coverageScore: Int = 0,   // 下游覆蓋
    val irreplaceScore: Int = 0,  // 不可替代性
    val resonanceBonus: Int = 0,   // 共振加分
    val overseasBonus: Int = 0,    // 海外供應鏈加分（+15 或 +5）
    val foreignRatingBonus: Int = 0, // 外資 Buy 評級加分（+12）
    val totalScore: Int,          // 最終得分（上限 100）
    val barrierLevel: String,     // 壁壘等級：高/中/低
    val passed: Boolean           // ≥40 分進入風控
)

/** Agent 5 風控終審結果 */
data class RiskValidationResult(
    val stockCode: String,
    val riskLevel: String,        // 低/中/高
    val deductions: List<RiskDeduction> = emptyList(),
    val overseasDeduction: Int = 0,  // 海外替代風險扣分（0 或清零全部海外加分）
    val adjustedScore: Int = 0,      // 對沖後的 Agent2 分數
    val passed: Boolean               // 非高風險則通過
)

data class RiskDeduction(
    val item: String,
    val description: String,
    val score: Int  // 扣分值
)

/** Agent D 輿情微調結果 */
data class SentimentAdjustResult(
    val sentimentScore: Int,       // 輿情得分
    val positionAdjust: String,     // 倉位微調建議（如 "+5%"）
    val reason: String              // 微調理由
)

/** Agent 4 交易執行方案 */
data class TradeExecutionPlan(
    val stockCode: String,
    val stockName: String,
    val entryZones: List<String>,   // 兩檔低吸區間 ["穩健區間", "激進區間"]
    val stopLoss: String,          // 硬性止損位
    val targets: List<String>,      // 兩檔止盈目標
    val maxPosition: String,       // 單只最大倉位
    val splitRatio: String,        // 分倉比例
    val tradeRules: List<String>    // 交易紀律
)

/** 流水線最終結果 */
data class PipelineResult(
    val target: String,
    val sector: String,
    val stepsCompleted: Int = 0,
    val totalSteps: Int = 7,
    val stocks: List<PipelineStockResult> = emptyList(),
    val errorMessage: String? = null,
    val analysisMode: String = "精簡版",
    val weightFormula: String = ""
)

/** 單只股票的最終流水線結果 */
data class PipelineStockResult(
    val stockCode: String,
    val stockName: String,
    val chainScore: ChainScoreResult?,
    val riskResult: RiskValidationResult?,
    val sentimentResult: SentimentAdjustResult?,
    val tradePlan: TradeExecutionPlan?,
    val finalPosition: String,      // 最終建議倉位（含 Agent D 微調）
    val passed: Boolean             // 是否通過全部流水線
)