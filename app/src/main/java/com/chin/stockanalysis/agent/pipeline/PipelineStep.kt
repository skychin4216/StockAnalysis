package com.chin.stockanalysis.agent.pipeline

/**
 * 流水線單步定義
 *
 * @param agentId     對應 Agent 的 ID（需在 AgentManager 中註冊）
 * @param name        步驟名稱（UI 展示用）
 * @param order       執行順序（0-based）
 * @param isDataFeeder 是否為數據底座步驟（Agent F），不調用 LLM，直接採集數據
 * @param isScorer    是否為打分步驟（Agent 2），唯一產生 0~100 分數的核心步驟
 * @param canHedge    是否具備對沖機制（Agent 5），需核查海外供應鏈替代風險
 * @param isAuxiliary 是否為輔助步驟（Agent D），僅影響倉位微調，不影響標的淘汰
 * @param passThreshold  通過閾值（僅 isScorer 時生效），低於此值則標的淘汰
 */
data class PipelineStep(
    val agentId: String,
    val name: String,
    val order: Int,
    val isDataFeeder: Boolean = false,
    val isScorer: Boolean = false,
    val canHedge: Boolean = false,
    val isAuxiliary: Boolean = false,
    val passThreshold: Int = 40
)