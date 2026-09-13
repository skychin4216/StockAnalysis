package com.chin.stockanalysis.agent.chat

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ai.StockEntityExtractor

/**
 * 规划 Agent — 将复杂的用户请求分解为可执行的步骤
 *
 * 用户输入如「全面分析兆易创新」时，生成执行计划：
 * 1. 获取实时行情
 * 2. 获取基本面数据
 * 3. 获取板块热度
 * 4. 获取相关新闻
 * 5. 触发 Pipeline 分析（如果适用）
 *
 * 注意：目前为基础框架，实际的 Pipeline 整合在后续迭代中完成。
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
     * 为用户输入生成执行计划
     *
     * @param userMessage 用户原始输入
     * @param context Android Context
     * @return 执行计划，如果无法提取股票实体则返回 null
     */
    suspend fun generatePlan(userMessage: String, context: Context): ExecutionPlan? {
        // Step 1: 提取股票实体
        val entities = StockEntityExtractor.extract(userMessage, context)
        if (entities.isEmpty()) {
            Log.d(TAG, "无法提取股票实体: $userMessage")
            return null
        }
        val entity = entities.first()

        // Step 2: 判断需要的步骤
        val isDeepAnalysis = userMessage.contains("全面") || userMessage.contains("深度") ||
            userMessage.contains("详细") || userMessage.contains("多维")

        val steps = mutableListOf<PlanStep>()

        if (isDeepAnalysis) {
            // 深度分析：完整 Pipeline
            steps.add(PlanStep("s1", "fetch_realtime", "获取${entity.name}实时行情", "stock_query"))
            steps.add(PlanStep("s2", "fetch_financials", "获取基本面数据", "stock_query", listOf("s1")))
            steps.add(PlanStep("s3", "fetch_sector", "获取板块热度", "sector_query", listOf("s1")))
            steps.add(PlanStep("s4", "fetch_news", "获取相关新闻", "news_query", listOf("s1")))
            steps.add(PlanStep("s5", "pipeline_analysis", "触发多智体分析 Pipeline", "pipeline", listOf("s2", "s3", "s4")))
        } else {
            // 标准分析：实时行情 + 基本面
            steps.add(PlanStep("s1", "fetch_realtime", "获取${entity.name}实时行情", "stock_query"))
            steps.add(PlanStep("s2", "fetch_financials", "获取基本面数据", "stock_query", listOf("s1")))
        }

        return ExecutionPlan(
            goal = userMessage,
            stockCode = entity.code,
            stockName = entity.name,
            steps = steps
        )
    }
}
