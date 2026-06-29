package com.chin.stockanalysis.agent.chat

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ai.StockEntityExtractor

/**
 * 規劃 Agent — 將複雜的用戶請求分解為可執行的步驟
 *
 * 用戶輸入如「全面分析兆易創新」時，生成執行計劃：
 * 1. 獲取實時行情
 * 2. 獲取基本面數據
 * 3. 獲取板塊熱度
 * 4. 獲取相關新聞
 * 5. 觸發 Pipeline 分析（如果適用）
 *
 * 注意：目前為基礎框架，實際的 Pipeline 整合在後續迭代中完成。
 */
object PlanAgent {

    private const val TAG = "PlanAgent"

    data class ExecutionPlan(
        val goal: String,
        val stockCode: String,
        val stockName: String,
        val steps: List<PlanStep>
    )

    data class PlanStep(
        val id: String,
        val action: String,
        val description: String,
        val tool: String,
        val dependsOn: List<String> = emptyList()
    )

    /**
     * 為用戶輸入生成執行計劃
     *
     * @param userMessage 用戶原始輸入
     * @param context Android Context
     * @return 執行計劃，如果無法提取股票實體則返回 null
     */
    suspend fun generatePlan(userMessage: String, context: Context): ExecutionPlan? {
        // Step 1: 提取股票實體
        val entities = StockEntityExtractor.extract(userMessage, context)
        if (entities.isEmpty()) {
            Log.d(TAG, "無法提取股票實體: $userMessage")
            return null
        }
        val entity = entities.first()

        // Step 2: 判斷需要的步驟
        val isDeepAnalysis = userMessage.contains("全面") || userMessage.contains("深度") ||
            userMessage.contains("詳細") || userMessage.contains("多維")

        val steps = mutableListOf<PlanStep>()

        if (isDeepAnalysis) {
            // 深度分析：完整 Pipeline
            steps.add(PlanStep("s1", "fetch_realtime", "獲取${entity.name}實時行情", "stock_query"))
            steps.add(PlanStep("s2", "fetch_financials", "獲取基本面數據", "stock_query", listOf("s1")))
            steps.add(PlanStep("s3", "fetch_sector", "獲取板塊熱度", "sector_query", listOf("s1")))
            steps.add(PlanStep("s4", "fetch_news", "獲取相關新聞", "news_query", listOf("s1")))
            steps.add(PlanStep("s5", "pipeline_analysis", "觸發多智體分析 Pipeline", "pipeline", listOf("s2", "s3", "s4")))
        } else {
            // 標準分析：實時行情 + 基本面
            steps.add(PlanStep("s1", "fetch_realtime", "獲取${entity.name}實時行情", "stock_query"))
            steps.add(PlanStep("s2", "fetch_financials", "獲取基本面數據", "stock_query", listOf("s1")))
        }

        return ExecutionPlan(
            goal = userMessage,
            stockCode = entity.code,
            stockName = entity.name,
            steps = steps
        )
    }
}
