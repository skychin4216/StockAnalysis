package com.chin.stockanalysis.strategy.topology.core

/**
 * ## Node -- Pipeline 最小處理單元
 *
 * 無狀態（或僅持有配置），不持有業務數據。
 * 泛型 IN = 輸入類型, OUT = 輸出類型。
 *
 * ### 設計原則
 * - 每個 Node 只做一件事（Single Responsibility）
 * - 通過 [PipelineContext] 讀取共享上下文，不直接持有業務數據
 * - 通過泛型 IN/OUT 實現類型安全的數據流
 *
 * ### 使用示例
 * ```kotlin
 * class MarketSourceNode : PipelineNode<Unit, StockPool> {
 *     override val nodeId = "market_source"
 *     override val nodeName = "市場數據源"
 *     override val nodeType = NodeType.DATA_SOURCE
 *
 *     override suspend fun execute(context: PipelineContext, input: Unit): StockPool {
 *         // 從 context 中讀取配置，返回 StockPool
 *     }
 * }
 * ```
 *
 * @param IN 輸入數據類型
 * @param OUT 輸出數據類型
 */
interface PipelineNode<IN, OUT> {

    /** 節點唯一標識（用於日誌、調試、DAG 拓撲引用） */
    val nodeId: String

    /** 節點人類可讀名稱（用於 UI 展示和日誌） */
    val nodeName: String

    /** 節點類型（決定在 Pipeline DAG 中的語義角色） */
    val nodeType: NodeType

    /**
     * 執行節點處理邏輯
     *
     * @param context Pipeline 共享上下文（市場數據、緩存、配置等）
     * @param input 節點輸入數據
     * @return 節點處理結果
     */
    suspend fun execute(context: PipelineContext, input: IN): OUT
}

/**
 * ## 節點類型枚舉
 *
 * 標識節點在 Pipeline 中的語義角色，
 * 用於日誌分類、DAG 拓撲校驗、UI 可視化等場景。
 */
enum class NodeType {

    /** 數據源：從 API / 數據庫 / 緩存加載原始數據 */
    DATA_SOURCE,

    /** 數據轉換：數據格式轉換、映射、清洗 */
    DATA_TRANSFORM,

    /** 因子計算：MFI / CMF / A/D / ATR 等量化因子計算 */
    FACTOR_COMPUTE,

    /** 策略篩選：執行量化選股策略，生成信號 */
    STRATEGY,

    /** 數據增強：板塊加權、新聞因子、主力資金加分 */
    ENRICHMENT,

    /** 過濾：基於閾值或規則過濾信號 */
    FILTER,

    /** AI 預測：調用 LLM 進行綜合預測分析 */
    AI_PREDICTION,

    /** 聚合：多策略信號合併、去重、排序 */
    AGGREGATION,

    /** 交易動作：生成買入/賣出/持有等交易決策 */
    TRADE_ACTION
}
