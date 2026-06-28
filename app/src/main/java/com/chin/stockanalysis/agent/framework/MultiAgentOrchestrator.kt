package com.chin.stockanalysis.agent.framework

import android.content.Context
import android.util.Log

/**
 * ## 多 Agent 編排器
 *
 * 負責管理所有 Agent 的生命週期，支持：
 * 1. Agent 註冊與發現
 * 2. 任務分發（根據任務描述匹配最合適的 Agent）
 * 3. Agent 間協作（一個 Agent 可以調用其他 Agent 作為子任務）
 * 4. 結果匯總
 */
class MultiAgentOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "MultiAgentOrchestrator"

        @Volatile
        private var instance: MultiAgentOrchestrator? = null

        fun getInstance(context: Context): MultiAgentOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: MultiAgentOrchestrator(context.applicationContext).also { instance = it }
            }
        }
    }

    /** 已註冊的 Agent 集合 */
    private val agents = mutableMapOf<String, AgentBase>()

    /** 註冊 Agent */
    fun register(agent: AgentBase) {
        agents[agent.id] = agent
        Log.i(TAG, "🤖 註冊 Agent: ${agent.name} (${agent.id})")
    }

    /** 獲取 Agent */
    fun get(id: String): AgentBase? = agents[id]

    /** 獲取所有已註冊的 Agent */
    fun getAll(): List<AgentBase> = agents.values.toList()

    /** 根據關鍵詞匹配最合適的 Agent */
    fun findBestAgent(taskDescription: String): AgentBase? {
        // 簡單的關鍵詞匹配，未來可以用 LLM 做更智能的匹配
        val keywords = taskDescription.lowercase()
        val scores = agents.mapValues { (_, agent) ->
            var score = 0
            val desc = (agent.description + " " + agent.name).lowercase()
            if (keywords.contains("選股") && desc.contains("選股")) score += 10
            if (keywords.contains("分析") && desc.contains("分析")) score += 10
            if (keywords.contains("交易") && desc.contains("交易")) score += 10
            if (keywords.contains("買入") && desc.contains("買入")) score += 10
            if (keywords.contains("賣出") && desc.contains("賣出")) score += 10
            if (keywords.contains("新聞") && desc.contains("新聞")) score += 10
            if (keywords.contains("風控") && desc.contains("風控")) score += 10
            if (keywords.contains("對話") && desc.contains("對話")) score += 10
            if (keywords.contains("chat") && desc.contains("chat")) score += 10
            score
        }
        return scores.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let { agents[it.key] }
    }

    /**
     * 執行協作任務
     *
     * 由一個主 Agent 執行任務，過程中可以調用其他 Agent 作為子任務
     */
    suspend fun executeCollaborative(
        primaryAgentId: String,
        task: String,
        ctx: AgentContext = AgentContext()
    ): AgentResult {
        val agent = agents[primaryAgentId]
            ?: return AgentResult(success = false, output = "Agent '$primaryAgentId' 未找到")

        Log.i(TAG, "🚀 協作執行: ${agent.name} | $task")

        // 使用 Plan-and-Execute 模式，讓主 Agent 規劃並執行
        return agent.planAndExecute(task, ctx)
    }

    /**
     * 智能任務分發
     *
     * 根據任務描述自動選擇最合適的 Agent 執行
     */
    suspend fun dispatch(
        taskDescription: String,
        ctx: AgentContext = AgentContext()
    ): AgentResult {
        val agent = findBestAgent(taskDescription)
            ?: return AgentResult(
                success = false,
                output = "沒有找到適合處理此任務的 Agent。已註冊 Agent: ${agents.keys.joinToString()}"
            )

        Log.i(TAG, "📤 任務分發 → ${agent.name}: $taskDescription")

        // 對於複雜任務，使用 Plan-and-Execute；對於簡單任務，使用 ReAct
        return if (taskDescription.length > 30) {
            agent.planAndExecute(taskDescription, ctx)
        } else {
            agent.react(taskDescription, ctx)
        }
    }

    /** 匯總多個 Agent 的結果 */
    fun aggregateResults(results: List<AgentResult>): String {
        return buildString {
            appendLine("## 多 Agent 協作結果匯總")
            appendLine()
            results.forEachIndexed { index, result ->
                appendLine("### Agent ${index + 1}")
                appendLine("- 成功: ${result.success}")
                appendLine("- 步驟: ${result.steps}")
                appendLine("- 輸出: ${result.output.take(200)}")
                appendLine()
            }
        }
    }
}
