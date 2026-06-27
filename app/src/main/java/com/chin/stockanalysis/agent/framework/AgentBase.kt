package com.chin.stockanalysis.agent.framework

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ai.AiProviderPool
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * ## Agent 基類（ReAct + Plan-and-Execute 雙模式）
 *
 * 設計哲學：
 * - 每個 Agent 是一個自治單元，擁有自己的記憶、工具、LLM 調用能力
 * - 支持 ReAct（推理-行動循環）和 Plan-and-Execute（規劃-執行）兩種模式
 * - 工具調用通過 JSON 格式約定，便於 LLM 理解和生成
 * - 記憶分為短期（當前會話）和長期（持久化到數據庫）
 */
abstract class AgentBase(
    val id: String,
    val name: String,
    val description: String,
    protected val context: Context
) {
    companion object {
        private const val TAG = "AgentBase"
        private const val MAX_REACT_STEPS = 8
        private const val LLM_TIMEOUT_MS = 60_000L
    }

    /** 已註冊的工具集合 */
    protected val tools = mutableMapOf<String, AgentTool>()

    /** 短期記憶（當前會話） */
    protected val shortTermMemory = mutableListOf<AgentMemory>()

    /** 當前會話的唯一標識 */
    protected var sessionId: String = System.currentTimeMillis().toString()

    /** 是否啟用調試日誌 */
    var debugMode: Boolean = false

    /** ================================================================ */
    /** 子類必須實現：獲取系統 Prompt（定義 Agent 角色和能力） */
    protected abstract fun buildSystemPrompt(): String

    /** 子類可選覆寫：執行前初始化 */
    protected open suspend fun onBeforeExecute(ctx: AgentContext) {}

    /** 子類可選覆寫：執行後清理 */
    protected open suspend fun onAfterExecute(result: AgentResult) {}

    /** ================================================================ */
    /**
     * ## ReAct 模式（推理-行動循環）
     *
     * 適用場景：需要逐步探索、試錯、收集信息的任務
     * 例如：選股（先觀察市場 → 思考 → 選策略 → 觀察結果 → ...）
     */
    suspend fun react(
        input: String,
        ctx: AgentContext = AgentContext(),
        maxSteps: Int = MAX_REACT_STEPS
    ): AgentResult {
        log("🚀 [$name] ReAct 開始 | 輸入: ${input.take(60)}")
        onBeforeExecute(ctx)

        val observations = mutableListOf<String>()
        var stepCount = 0
        var finalAnswer: String? = null

        try {
            while (stepCount < maxSteps && finalAnswer == null) {
                stepCount++
                log("  Step $stepCount/$maxSteps")

                // 1. Thought: LLM 思考下一步該做什麼
                val thoughtPrompt = buildReactPrompt(
                    input = input,
                    observations = observations,
                    step = stepCount,
                    maxSteps = maxSteps,
                    ctx = ctx
                )
                val thoughtRaw = callLLM(thoughtPrompt)
                log("  💭 Thought: ${thoughtRaw.take(120)}")

                // 2. 解析 Thought，提取 Action
                val action = parseAction(thoughtRaw)

                when (action.type) {
                    ActionType.TOOL_CALL -> {
                        // 執行工具
                        val toolResult = executeTool(action.toolName!!, action.params)
                        observations.add("[Step $stepCount] 調用 ${action.toolName}: $toolResult")
                        log("  🔧 Tool(${action.toolName}): ${toolResult.take(100)}")
                    }
                    ActionType.ANSWER -> {
                        // 給出最終答案
                        finalAnswer = action.content
                        log("  ✅ Final Answer: ${finalAnswer.take(100)}")
                    }
                    ActionType.THINK -> {
                        // 純思考，不執行工具
                        observations.add("[Step $stepCount] 思考: ${action.content}")
                    }
                }
            }

            if (finalAnswer == null) {
                finalAnswer = "經過 $stepCount 步推理，未能得出結論。觀察記錄:\n${observations.joinToString("\n")}"
            }

            val result = AgentResult(
                success = true,
                output = finalAnswer,
                steps = stepCount,
                observations = observations,
                metadata = ctx.data
            )

            // 保存記憶
            addMemory("react", input, finalAnswer, stepCount)
            onAfterExecute(result)
            return result

        } catch (e: Exception) {
            log("  ❌ ReAct 異常: ${e.message}", isError = true)
            val result = AgentResult(
                success = false,
                output = "執行失敗: ${e.message}",
                steps = stepCount,
                observations = observations,
                error = e
            )
            onAfterExecute(result)
            return result
        }
    }

    /**
     * ## Plan-and-Execute 模式（規劃-執行）
     *
     * 適用場景：目標明確、步驟清晰的任務
     * 例如：交易執行（規劃：選股→分析→下單→監控）
     */
    suspend fun planAndExecute(
        goal: String,
        ctx: AgentContext = AgentContext(),
        maxSteps: Int = MAX_REACT_STEPS
    ): AgentResult {
        log("🚀 [$name] Plan-and-Execute 開始 | 目標: ${goal.take(60)}")
        onBeforeExecute(ctx)

        try {
            // 1. Plan: 讓 LLM 制定執行計劃
            val planPrompt = buildPlanPrompt(goal, ctx)
            val planRaw = callLLM(planPrompt)
            val plan = parsePlan(planRaw)
            log("  📋 Plan: ${plan.steps.joinToString(" → ")}")

            // 2. Execute: 按計劃逐步執行
            val observations = mutableListOf<String>()
            var stepCount = 0

            for ((index, step) in plan.steps.withIndex()) {
                stepCount++
                if (stepCount > maxSteps) {
                    observations.add("達到最大步數限制，提前終止")
                    break
                }

                log("  Step ${index + 1}/${plan.steps.size}: ${step.description}")

                // 執行步驟（可能是工具調用，也可能是 LLM 推理）
                val stepResult = when (step.type) {
                    PlanStepType.TOOL -> {
                        val tool = tools[step.toolName]
                        if (tool != null) {
                            tool.execute(step.params, ctx)
                        } else {
                            "錯誤: 工具 ${step.toolName} 未找到"
                        }
                    }
                    PlanStepType.LLM -> {
                        callLLM(step.description + "\n上下文: ${observations.joinToString("\n")}")
                    }
                    PlanStepType.SUB_AGENT -> {
                        // 調用子 Agent（由子類實現）
                        executeSubAgent(step.subAgentId!!, step.description, ctx)
                    }
                }

                observations.add("[${step.description}] 結果: $stepResult")
                log("  📤 結果: ${stepResult.take(100)}")

                // 如果某步失敗，詢問 LLM 是否繼續或調整計劃
                if (stepResult.startsWith("錯誤") || stepResult.startsWith("失敗")) {
                    val adjustPrompt = buildAdjustPrompt(goal, plan, observations, step)
                    val adjustment = callLLM(adjustPrompt)
                    if (adjustment.contains("終止") || adjustment.contains("放棄")) {
                        observations.add("因步驟失敗，終止執行")
                        break
                    }
                }
            }

            // 3. 匯總結果
            val summaryPrompt = buildSummaryPrompt(goal, plan, observations)
            val summary = callLLM(summaryPrompt)

            val result = AgentResult(
                success = true,
                output = summary,
                steps = stepCount,
                observations = observations,
                metadata = ctx.data + ("plan" to plan.steps.map { it.description })
            )

            addMemory("plan", goal, summary, stepCount)
            onAfterExecute(result)
            return result

        } catch (e: Exception) {
            log("  ❌ Plan-and-Execute 異常: ${e.message}", isError = true)
            val result = AgentResult(
                success = false,
                output = "執行失敗: ${e.message}",
                steps = 0,
                error = e
            )
            onAfterExecute(result)
            return result
        }
    }

    /** ================================================================ */
    /** 註冊工具 */
    protected fun registerTool(tool: AgentTool) {
        tools[tool.name] = tool
        log("  🔧 註冊工具: ${tool.name}")
    }

    /** 調用 LLM（通過 AiProviderPool） */
    protected suspend fun callLLM(prompt: String): String {
        val slot = AiProviderPool.acquire(
            context = context,
            callerTag = "Agent.$id",
            timeoutMs = LLM_TIMEOUT_MS
        )
            ?: throw IllegalStateException("無可用 AI Provider")

        return try {
            kotlinx.coroutines.withTimeout(LLM_TIMEOUT_MS) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    slot.provider.sendMessageStream(
                        messages = emptyList(),
                        systemPrompt = buildSystemPrompt() + "\n\n" + prompt,
                        onSuccess = {},
                        onComplete = { full -> cont.resume(full, null) },
                        onError = { err -> cont.resumeWithException(Exception(err)) }
                    )
                }
            }
        } finally {
            AiProviderPool.releaseNonBlocking(slot)
        }
    }

    /** 執行工具 */
    private suspend fun executeTool(toolName: String, params: Map<String, String>): String {
        val tool = tools[toolName]
            ?: return "錯誤: 工具 '$toolName' 未註冊"
        return try {
            tool.execute(params, AgentContext())
        } catch (e: Exception) {
            "錯誤: 工具執行失敗: ${e.message}"
        }
    }

    /** 執行子 Agent（子類覆寫） */
    protected open suspend fun executeSubAgent(
        subAgentId: String,
        task: String,
        ctx: AgentContext
    ): String {
        return "子 Agent 調用未實現"
    }

    /** 添加記憶 */
    protected fun addMemory(type: String, input: String, output: String, steps: Int) {
        shortTermMemory.add(
            AgentMemory(
                timestamp = System.currentTimeMillis(),
                type = type,
                input = input,
                output = output,
                steps = steps
            )
        )
        // 只保留最近 20 條短期記憶
        if (shortTermMemory.size > 20) {
            shortTermMemory.removeAt(0)
        }
    }

    /** ================================================================ */
    /** 構建 ReAct Prompt */
    private fun buildReactPrompt(
        input: String,
        observations: List<String>,
        step: Int,
        maxSteps: Int,
        ctx: AgentContext
    ): String = buildString {
        appendLine("你是一個 $name Agent。請使用 ReAct（推理-行動）模式解決問題。")
        appendLine()
        appendLine("## 可用工具")
        tools.values.forEach { tool ->
            appendLine("- ${tool.name}: ${tool.description}")
            appendLine("  參數: ${tool.parameters.joinToString(", ")}")
        }
        appendLine()
        appendLine("## 任務")
        appendLine(input)
        appendLine()
        appendLine("## 上下文數據")
        ctx.data.forEach { (k, v) ->
            appendLine("- $k: $v")
        }
        appendLine()
        if (observations.isNotEmpty()) {
            appendLine("## 歷史觀察")
            observations.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## 要求")
        appendLine("請以 JSON 格式輸出你的思考結果，格式如下:")
        appendLine()
        appendLine("```json")
        appendLine("{")
        appendLine("  \"thought\": \"你的思考過程\",")
        appendLine("  \"action\": \"TOOL_CALL|ANSWER|THINK\",")
        appendLine("  \"tool_name\": \"如果 action=TOOL_CALL，填寫工具名\",")
        appendLine("  \"params\": {\"key\": \"value\"},")
        appendLine("  \"content\": \"如果 action=ANSWER，填寫最終答案；如果 action=THINK，填寫思考內容\"")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("注意:")
        appendLine("- 當前是第 $step 步，最多 $maxSteps 步")
        appendLine("- 如果已經收集到足夠信息，請使用 ANSWER 給出最終答案")
        appendLine("- 如果只需要思考不需要行動，請使用 THINK")
        appendLine("- 請確保 JSON 格式正確，不要輸出其他內容")
    }

    /** 解析 Action */
    private fun parseAction(raw: String): AgentAction {
        return try {
            val jsonStr = raw.substringAfter("```json").substringBefore("```").trim()
                .ifEmpty { raw.trim() }
            val json = JSONObject(jsonStr)
            val actionType = when (json.optString("action", "THINK").uppercase()) {
                "TOOL_CALL" -> ActionType.TOOL_CALL
                "ANSWER" -> ActionType.ANSWER
                else -> ActionType.THINK
            }
            AgentAction(
                type = actionType,
                content = json.optString("content", ""),
                toolName = json.optString("tool_name", null),
                params = json.optJSONObject("params")?.let { obj ->
                    mutableMapOf<String, String>().apply {
                        obj.keys().forEach { key -> put(key, obj.getString(key)) }
                    }
                } ?: emptyMap()
            )
        } catch (e: Exception) {
            // 解析失敗，視為思考
            AgentAction(type = ActionType.THINK, content = raw)
        }
    }

    /** 構建 Plan Prompt */
    private fun buildPlanPrompt(goal: String, ctx: AgentContext): String = buildString {
        appendLine("你是一個 $name Agent。請為以下目標制定執行計劃。")
        appendLine()
        appendLine("## 可用工具")
        tools.values.forEach { tool ->
            appendLine("- ${tool.name}: ${tool.description}")
        }
        appendLine()
        appendLine("## 目標")
        appendLine(goal)
        appendLine()
        appendLine("## 上下文")
        ctx.data.forEach { (k, v) -> appendLine("- $k: $v") }
        appendLine()
        appendLine("## 要求")
        appendLine("請以 JSON 格式輸出執行計劃，格式如下:")
        appendLine()
        appendLine("```json")
        appendLine("{")
        appendLine("  \"steps\": [")
        appendLine("    {")
        appendLine("      \"type\": \"TOOL|LLM|SUB_AGENT\",")
        appendLine("      \"description\": \"步驟描述\",")
        appendLine("      \"tool_name\": \"如果 type=TOOL\",")
        appendLine("      \"params\": {\"key\": \"value\"},")
        appendLine("      \"sub_agent_id\": \"如果 type=SUB_AGENT\"")
        appendLine("    }")
        appendLine("  ]")
        appendLine("}")
        appendLine("```")
    }

    /** 解析 Plan */
    private fun parsePlan(raw: String): AgentPlan {
        return try {
            val jsonStr = raw.substringAfter("```json").substringBefore("```").trim()
                .ifEmpty { raw.trim() }
            val json = JSONObject(jsonStr)
            val stepsArray = json.getJSONArray("steps")
            val steps = mutableListOf<PlanStep>()
            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                steps.add(
                    PlanStep(
                        type = when (stepObj.optString("type", "LLM").uppercase()) {
                            "TOOL" -> PlanStepType.TOOL
                            "SUB_AGENT" -> PlanStepType.SUB_AGENT
                            else -> PlanStepType.LLM
                        },
                        description = stepObj.optString("description", "未命名步驟"),
                        toolName = stepObj.optString("tool_name", null),
                        params = stepObj.optJSONObject("params")?.let { obj ->
                            mutableMapOf<String, String>().apply {
                                obj.keys().forEach { key -> put(key, obj.getString(key)) }
                            }
                        } ?: emptyMap(),
                        subAgentId = stepObj.optString("sub_agent_id", null)
                    )
                )
            }
            AgentPlan(steps = steps)
        } catch (e: Exception) {
            // 解析失敗，返回單步計劃
            AgentPlan(steps = listOf(PlanStep(type = PlanStepType.LLM, description = goal)))
        }
    }

    /** 構建調整 Prompt */
    private fun buildAdjustPrompt(
        goal: String,
        plan: AgentPlan,
        observations: List<String>,
        failedStep: PlanStep
    ): String = buildString {
        appendLine("執行計劃中遇到問題，請決定如何處理。")
        appendLine("目標: $goal")
        appendLine("失敗步驟: ${failedStep.description}")
        appendLine("觀察記錄:")
        observations.forEach { appendLine("- $it") }
        appendLine()
        appendLine("請輸出: '繼續' / '跳過此步' / '調整計劃' / '終止'，並簡要說明理由。")
    }

    /** 構建匯總 Prompt */
    private fun buildSummaryPrompt(goal: String, plan: AgentPlan, observations: List<String>): String = buildString {
        appendLine("請匯總以下執行結果，給出最終答案。")
        appendLine("目標: $goal")
        appendLine("執行計劃: ${plan.steps.joinToString(" → ") { it.description }}")
        appendLine()
        appendLine("觀察記錄:")
        observations.forEach { appendLine("- $it") }
    }

    /** 日誌 */
    private fun log(msg: String, isError: Boolean = false) {
        if (isError) {
            Log.e(TAG, "[$id] $msg")
        } else if (debugMode) {
            Log.d(TAG, "[$id] $msg")
        }
    }
}

/** Action 類型 */
enum class ActionType { TOOL_CALL, ANSWER, THINK }

/** Agent 行動 */
data class AgentAction(
    val type: ActionType,
    val content: String = "",
    val toolName: String? = null,
    val params: Map<String, String> = emptyMap()
)

/** 計劃步驟類型 */
enum class PlanStepType { TOOL, LLM, SUB_AGENT }

/** 計劃步驟 */
data class PlanStep(
    val type: PlanStepType,
    val description: String,
    val toolName: String? = null,
    val params: Map<String, String> = emptyMap(),
    val subAgentId: String? = null
)

/** 執行計劃 */
data class AgentPlan(val steps: List<PlanStep>)

/** Agent 上下文 */
data class AgentContext(
    val data: MutableMap<String, Any> = mutableMapOf()
) {
    fun put(key: String, value: Any) = data.put(key, value)
    fun get(key: String): Any? = data[key]
    fun getString(key: String): String? = data[key]?.toString()
}

/** Agent 執行結果 */
data class AgentResult(
    val success: Boolean,
    val output: String,
    val steps: Int = 0,
    val observations: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap(),
    val error: Exception? = null
)

/** Agent 記憶 */
data class AgentMemory(
    val timestamp: Long,
    val type: String,
    val input: String,
    val output: String,
    val steps: Int
)
