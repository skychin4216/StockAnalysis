package com.chin.stockanalysis.agent.framework

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.ai.ChatTools
import com.chin.stockanalysis.OpenAiCompatibleProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## Agent 基类（ReAct + Plan-and-Execute 双模式）
 *
 * 设计哲学：
 * - 每个 Agent 是一个自治单元，拥有自己的记忆、工具、LLM 调用能力
 * - 支持 ReAct（推理-行动循环）和 Plan-and-Execute（规划-执行）两种模式
 * - 工具调用通过 JSON 格式约定，便于 LLM 理解和生成
 * - 记忆分为短期（当前会话）和长期（持久化到数据库）
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
        private const val LLM_TIMEOUT_MS = 25_000L   // 必须小于 AiProviderPool 的 30s sweep 超时
    }

    /** 已注册的工具集合 */
    protected val tools = mutableMapOf<String, AgentTool>()

    /** 短期记忆（当前会话） */
    protected val shortTermMemory = mutableListOf<AgentMemory>()

    /** 当前会话的唯一标识 */
    protected var sessionId: String = System.currentTimeMillis().toString()

    /** 是否启用调试日志 */
    var debugMode: Boolean = false

    /** ================================================================ */
    /** 子类必须实现：获取系统 Prompt（定义 Agent 角色和能力） */
    protected abstract fun buildSystemPrompt(): String

    /** 子类可选覆写：执行前初始化 */
    protected open suspend fun onBeforeExecute(ctx: AgentContext) {}

    /** 子类可选覆写：执行后清理 */
    protected open suspend fun onAfterExecute(result: AgentResult) {}

    /** ================================================================ */
    /**
     * ## ReAct 模式（推理-行动循环）
     *
     * 适用场景：需要逐步探索、试错、收集信息的任务
     * 例如：选股（先观察市场 → 思考 → 选策略 → 观察结果 → ...）
     *
     * 支援两种模式：
     * - 原生 Function Calling：当 Provider 支援时，使用 tool_calls 机制
     * - JSON Prompt 约定模式：向后兼容，使用 `ACTION: TOOL_CALL` JSON 格式
     */
    suspend fun react(
        input: String,
        ctx: AgentContext = AgentContext(),
        maxSteps: Int = MAX_REACT_STEPS
    ): AgentResult {
        currentContext = ctx
        log("🚀 [$name] ReAct 开始 | 输入: ${input.take(60)}")
        onBeforeExecute(ctx)

        val observations = mutableListOf<String>()
        var stepCount = 0
        var finalAnswer: String? = null

        try {
            // 判断是否使用原生 Function Calling
            val supportsTools = tools.isNotEmpty() && checkProviderSupportsTools()

            if (supportsTools) {
                log("  📡 使用原生 Function Calling 模式")
                finalAnswer = reactWithFunctionCalling(input, ctx, observations, maxSteps)
                stepCount = observations.size
            } else {
                log("  📝 使用 JSON Prompt 约定模式")
                while (stepCount < maxSteps && finalAnswer == null) {
                    stepCount++
                    log("  Step $stepCount/$maxSteps")

                    // 1. Thought: LLM 思考下一步该做什么
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
                            // 执行工具
                            val toolResult = executeTool(action.toolName!!, action.params)
                            observations.add("[Step $stepCount] 调用 ${action.toolName}: $toolResult")
                            log("  🔧 Tool(${action.toolName}): ${toolResult.take(100)}")
                        }
                        ActionType.ANSWER -> {
                            // 给出最终答案
                            finalAnswer = action.content
                            log("  ✅ Final Answer: ${finalAnswer.take(100)}")
                        }
                        ActionType.THINK -> {
                            // 纯思考，不执行工具
                            observations.add("[Step $stepCount] 思考: ${action.content}")
                        }
                    }
                }
            }

            if (finalAnswer == null) {
                finalAnswer = "经过 $stepCount 步推理，未能得出结论。观察记录:\n${observations.joinToString("\n")}"
            }

            val result = AgentResult(
                success = true,
                output = finalAnswer,
                steps = stepCount,
                observations = observations,
                metadata = ctx.data
            )

            // 保存记忆
            addMemory("react", input, finalAnswer, stepCount)
            onAfterExecute(result)
            return result

        } catch (e: Exception) {
            log("  ❌ ReAct 异常: ${e.message}", isError = true)
            val result = AgentResult(
                success = false,
                output = "执行失败: ${e.message}",
                steps = stepCount,
                observations = observations,
                error = e
            )
            onAfterExecute(result)
            return result
        }
    }

    /**
     * ## 原生 Function Calling 模式的 ReAct 循环
     *
     * 使用 OpenAI 兼容的 tool_calls 机制，LLM 透过原生
     * function calling 请求工具调用，Agent 执行后将结果
     * 以 tool message 回传，重复直到 LLM 不再请求工具或达到 maxSteps。
     */
    private suspend fun reactWithFunctionCalling(
        input: String,
        ctx: AgentContext,
        observations: MutableList<String>,
        maxSteps: Int
    ): String {
        var stepCount = 0

        // 讯息历史：用于多轮 Function Calling 对话
        val messageHistory = mutableListOf<MutableMap<String, Any>>()
        // 初始 user message
        val userMsg = buildString {
            appendLine("你是一个 $name Agent。请使用可用工具解决以下问题。")
            appendLine()
            appendLine("## 任务")
            appendLine(input)
            appendLine()
            appendLine("## 上下文数据")
            ctx.data.forEach { (k, v) ->
                appendLine("- $k: $v")
            }
            appendLine()
            appendLine("请根据需要调用工具收集信息，然后给出最终答案。")
        }
        messageHistory.add(mutableMapOf("role" to "user", "content" to userMsg))

        var finalAnswer: String? = null

        while (stepCount < maxSteps && finalAnswer == null) {
            stepCount++
            log("  FC Step $stepCount/$maxSteps")

            // 使用带 tools 的 LLM 调用，获取 content 和 tool_calls
            val (content, toolCalls) = callLLMWithTools(messageHistory)

            if (toolCalls.isNotEmpty()) {
                log("  🔧 LLM 请求 ${toolCalls.size} 个工具调用")

                // 将 assistant message（含 tool_calls）加入历史
                val assistantMsg = mutableMapOf<String, Any>(
                    "role" to "assistant",
                    "content" to (content.ifBlank { "" })
                )
                val tcArray = toolCalls.map { tc ->
                    mapOf(
                        "id" to tc.id,
                        "type" to tc.type,
                        "function" to mapOf(
                            "name" to tc.function.name,
                            "arguments" to tc.function.arguments
                        )
                    )
                }
                assistantMsg["tool_calls"] = tcArray
                messageHistory.add(assistantMsg)

                // 处理每个 tool_call
                for (tc in toolCalls) {
                    val result = handleToolCall(tc)
                    observations.add("[Step $stepCount] 调用 ${tc.function.name}: $result")
                    log("  🔧 Tool(${tc.function.name}): ${result.take(100)}")

                    // 将 tool result 作为 tool message 回传
                    messageHistory.add(mutableMapOf(
                        "role" to "tool",
                        "tool_call_id" to tc.id,
                        "content" to result
                    ))
                }
            } else if (content.isNotEmpty()) {
                // LLM 没有请求工具调用，返回最终答案
                finalAnswer = content
                log("  ✅ Final Answer: ${finalAnswer.take(100)}")
            } else {
                observations.add("[Step $stepCount] LLM 回复为空")
            }
        }

        return finalAnswer ?: "经过 $stepCount 步推理，未能得出结论。观察记录:\n${observations.joinToString("\n")}"
    }

    /**
     * ## Plan-and-Execute 模式（规划-执行）
     *
     * 适用场景：目标明确、步骤清晰的任务
     * 例如：交易执行（规划：选股→分析→下单→监控）
     */
    suspend fun planAndExecute(
        goal: String,
        ctx: AgentContext = AgentContext(),
        maxSteps: Int = MAX_REACT_STEPS
    ): AgentResult {
        log("🚀 [$name] Plan-and-Execute 开始 | 目标: ${goal.take(60)}")
        onBeforeExecute(ctx)

        try {
            // 1. Plan: 让 LLM 制定执行计划
            val planPrompt = buildPlanPrompt(goal, ctx)
            val planRaw = callLLM(planPrompt)
            val plan = parsePlan(planRaw, goal)
            log("  📋 Plan: ${plan.steps.joinToString(" → ")}")

            // 2. Execute: 按计划逐步执行
            val observations = mutableListOf<String>()
            var stepCount = 0

            for ((index, step) in plan.steps.withIndex()) {
                stepCount++
                if (stepCount > maxSteps) {
                    observations.add("达到最大步数限制，提前终止")
                    break
                }

                log("  Step ${index + 1}/${plan.steps.size}: ${step.description}")

                // 执行步骤（可能是工具调用，也可能是 LLM 推理）
                val stepResult = when (step.type) {
                    PlanStepType.TOOL -> {
                        val tool = tools[step.toolName]
                        if (tool != null) {
                            tool.execute(step.params, ctx)
                        } else {
                            "错误: 工具 ${step.toolName} 未找到"
                        }
                    }
                    PlanStepType.LLM -> {
                        callLLM(step.description + "\n上下文: ${observations.joinToString("\n")}")
                    }
                    PlanStepType.SUB_AGENT -> {
                        // 调用子 Agent（由子类实现）
                        executeSubAgent(step.subAgentId!!, step.description, ctx)
                    }
                }

                observations.add("[${step.description}] 结果: $stepResult")
                log("  📤 结果: ${stepResult.take(100)}")

                // 如果某步失败，询问 LLM 是否继续或调整计划
                if (stepResult.startsWith("错误") || stepResult.startsWith("失败")) {
                    val adjustPrompt = buildAdjustPrompt(goal, plan, observations, step)
                    val adjustment = callLLM(adjustPrompt)
                    if (adjustment.contains("终止") || adjustment.contains("放弃")) {
                        observations.add("因步骤失败，终止执行")
                        break
                    }
                }
            }

            // 3. 汇总结果
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
            log("  ❌ Plan-and-Execute 异常: ${e.message}", isError = true)
            val result = AgentResult(
                success = false,
                output = "执行失败: ${e.message}",
                steps = 0,
                error = e
            )
            onAfterExecute(result)
            return result
        }
    }

    /** ================================================================ */
    /** 注册工具 */
    protected fun registerTool(tool: AgentTool) {
        tools[tool.name] = tool
        log("  🔧 注册工具: ${tool.name}")
    }

    /** 调用 LLM（通过 AiProviderPool） */
    protected suspend fun callLLM(prompt: String): String {
        val slot = AiProviderPool.acquire(
            context = context,
            callerTag = "Agent.$id",
            timeoutMs = LLM_TIMEOUT_MS
        )
            ?: throw IllegalStateException("无可用 AI Provider")

        return try {
            kotlinx.coroutines.withTimeout(LLM_TIMEOUT_MS) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    var resumed = false
                    slot.provider.sendMessageStream(
                        messages = emptyList(),
                        systemPrompt = buildSystemPrompt() + "\n\n" + prompt,
                        onSuccess = {},
                        onComplete = { full ->
                            if (!resumed) { resumed = true; cont.resume(full, null) }
                        },
                        onError = { err ->
                            if (!resumed) { resumed = true; cont.resumeWith(Result.failure(Exception(err))) }
                        }
                    )
                }
            }
        } finally {
            AiProviderPool.releaseNonBlocking(slot)
        }
    }

    private var currentContext: AgentContext = AgentContext()

    /** 执行工具 */
    private suspend fun executeTool(toolName: String, params: Map<String, String>): String {
        val tool = tools[toolName]
            ?: return "错误: 工具 '$toolName' 未注册"
        return try {
            tool.execute(params, currentContext)
        } catch (e: Exception) {
            "错误: 工具执行失败: ${e.message}"
        }
    }

    /** 执行子 Agent（子类覆写） */
    protected open suspend fun executeSubAgent(
        subAgentId: String,
        task: String,
        ctx: AgentContext
    ): String {
        return "子 Agent 调用未实现"
    }

    /** 添加记忆 */
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
        // 只保留最近 20 条短期记忆
        if (shortTermMemory.size > 20) {
            shortTermMemory.removeAt(0)
        }
    }

    /** ================================================================ */
    /** 构建 ReAct Prompt */
    private fun buildReactPrompt(
        input: String,
        observations: List<String>,
        step: Int,
        maxSteps: Int,
        ctx: AgentContext
    ): String = buildString {
        appendLine("你是一个 $name Agent。请使用 ReAct（推理-行动）模式解决问题。")
        appendLine()
        appendLine("## 可用工具")
        tools.values.forEach { tool ->
            appendLine("- ${tool.name}: ${tool.description}")
            appendLine("  参数: ${tool.parameters.joinToString(", ")}")
        }
        appendLine()
        appendLine("## 任务")
        appendLine(input)
        appendLine()
        appendLine("## 上下文数据")
        ctx.data.forEach { (k, v) ->
            appendLine("- $k: $v")
        }
        appendLine()
        if (observations.isNotEmpty()) {
            appendLine("## 历史观察")
            observations.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## 要求")
        appendLine("请以 JSON 格式输出你的思考结果，格式如下:")
        appendLine()
        appendLine("```json")
        appendLine("{")
        appendLine("  \"thought\": \"你的思考过程\",")
        appendLine("  \"action\": \"TOOL_CALL|ANSWER|THINK\",")
        appendLine("  \"tool_name\": \"如果 action=TOOL_CALL，填写工具名\",")
        appendLine("  \"params\": {\"key\": \"value\"},")
        appendLine("  \"content\": \"如果 action=ANSWER，填写最终答案；如果 action=THINK，填写思考内容\"")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("注意:")
        appendLine("- 当前是第 $step 步，最多 $maxSteps 步")
        appendLine("- 如果已经收集到足够信息，请使用 ANSWER 给出最终答案")
        appendLine("- 如果只需要思考不需要行动，请使用 THINK")
        appendLine("- 请确保 JSON 格式正确，不要输出其他内容")
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
            // 解析失败，视为思考
            AgentAction(type = ActionType.THINK, content = raw)
        }
    }

    /** 构建 Plan Prompt */
    private fun buildPlanPrompt(goal: String, ctx: AgentContext): String = buildString {
        appendLine("你是一个 $name Agent。请为以下目标制定执行计划。")
        appendLine()
        appendLine("## 可用工具")
        tools.values.forEach { tool ->
            appendLine("- ${tool.name}: ${tool.description}")
        }
        appendLine()
        appendLine("## 目标")
        appendLine(goal)
        appendLine()
        appendLine("## 上下文")
        ctx.data.forEach { (k, v) -> appendLine("- $k: $v") }
        appendLine()
        appendLine("## 要求")
        appendLine("请以 JSON 格式输出执行计划，格式如下:")
        appendLine()
        appendLine("```json")
        appendLine("{")
        appendLine("  \"steps\": [")
        appendLine("    {")
        appendLine("      \"type\": \"TOOL|LLM|SUB_AGENT\",")
        appendLine("      \"description\": \"步骤描述\",")
        appendLine("      \"tool_name\": \"如果 type=TOOL\",")
        appendLine("      \"params\": {\"key\": \"value\"},")
        appendLine("      \"sub_agent_id\": \"如果 type=SUB_AGENT\"")
        appendLine("    }")
        appendLine("  ]")
        appendLine("}")
        appendLine("```")
    }

    /** 解析 Plan */
    private fun parsePlan(raw: String, fallbackGoal: String = ""): AgentPlan {
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
                        description = stepObj.optString("description", "未命名步骤"),
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
            // 解析失败，返回单步计划
            AgentPlan(steps = listOf(PlanStep(type = PlanStepType.LLM, description = fallbackGoal)))
        }
    }

    /** 构建调整 Prompt */
    private fun buildAdjustPrompt(
        goal: String,
        plan: AgentPlan,
        observations: List<String>,
        failedStep: PlanStep
    ): String = buildString {
        appendLine("执行计划中遇到问题，请决定如何处理。")
        appendLine("目标: $goal")
        appendLine("失败步骤: ${failedStep.description}")
        appendLine("观察记录:")
        observations.forEach { appendLine("- $it") }
        appendLine()
        appendLine("请输出: '继续' / '跳过此步' / '调整计划' / '终止'，并简要说明理由。")
    }

    /** 构建汇总 Prompt */
    private fun buildSummaryPrompt(goal: String, plan: AgentPlan, observations: List<String>): String = buildString {
        appendLine("请汇总以下执行结果，给出最终答案。")
        appendLine("目标: $goal")
        appendLine("执行计划: ${plan.steps.joinToString(" → ") { it.description }}")
        appendLine()
        appendLine("观察记录:")
        observations.forEach { appendLine("- $it") }
    }

    /**
     * 带原生 Function Calling 的 LLM 调用
     *
     * 发送讯息历史 + tools 定义给 Provider，回传 (content, toolCalls)。
     * 直接使用 OpenAiCompatibleProvider 以支援 tool_calls 回传。
     *
     * @param messageHistory 讯息历史（mutable，调用后会被修改以加入 assistant 回复）
     * @return Pair<content, toolCalls>
     */
    protected suspend fun callLLMWithTools(
        messageHistory: List<Map<String, Any>>
    ): Pair<String, List<ChatTools.ToolCall>> {
        val slot = AiProviderPool.acquire(
            context = context,
            callerTag = "Agent.$id.FC",
            timeoutMs = LLM_TIMEOUT_MS
        ) ?: throw IllegalStateException("无可用 AI Provider")

        return try {
            kotlinx.coroutines.withTimeout(LLM_TIMEOUT_MS) {
                kotlinx.coroutines.suspendCancellableCoroutine<Pair<String, List<ChatTools.ToolCall>>> { cont ->
                    var resumed = false
                    val contentAcc = StringBuilder()

                    // 构建 proper JSONArray：system + messageHistory（含 user/assistant/tool roles）
                    val messagesJson = JSONArray()
                    // System message first
                    messagesJson.put(JSONObject().apply {
                        put("role", "system")
                        put("content", buildSystemPrompt())
                    })
                    // Then all history messages with proper roles
                    for (msg in messageHistory) {
                        val role = msg["role"] as? String ?: continue
                        val jsonObj = JSONObject()
                        jsonObj.put("role", role)
                        when (role) {
                            "user", "assistant" -> {
                                jsonObj.put("content", msg["content"]?.toString() ?: "")
                                // Include tool_calls if present on assistant messages
                                @Suppress("UNCHECKED_CAST")
                                val toolCalls = msg["tool_calls"] as? List<Map<String, Any>>
                                if (toolCalls != null) {
                                    val tcArray = JSONArray()
                                    for (tc in toolCalls) {
                                        tcArray.put(JSONObject().apply {
                                            put("id", tc["id"] ?: "")
                                            put("type", tc["type"] ?: "function")
                                            val func = tc["function"] as? Map<*, *>
                                            if (func != null) {
                                                put("function", JSONObject().apply {
                                                    put("name", func["name"] ?: "")
                                                    put("arguments", func["arguments"] ?: "")
                                                })
                                            }
                                        })
                                    }
                                    jsonObj.put("tool_calls", tcArray)
                                }
                            }
                            "tool" -> {
                                jsonObj.put("content", msg["content"]?.toString() ?: "")
                                jsonObj.put("tool_call_id", msg["tool_call_id"] ?: "")
                            }
                        }
                        messagesJson.put(jsonObj)
                    }

                    // 直接使用 OpenAiCompatibleProvider 以取得 tools 参数和 onToolCalls 回呼支援
                    val openAiProvider = slot.provider as? OpenAiCompatibleProvider
                    if (openAiProvider != null) {
                        openAiProvider.sendMessageStreamWithRawMessages(
                            rawMessages = messagesJson,
                            onSuccess = { chunk -> contentAcc.append(chunk) },
                            onComplete = { fullContent ->
                                if (!resumed) {
                                    resumed = true
                                    cont.resume(Pair(contentAcc.toString(), emptyList()), null)
                                }
                            },
                            onError = { err ->
                                if (!resumed) { resumed = true; cont.resumeWith(Result.failure(Exception(err))) }
                            },
                            tools = ChatTools.allTools,
                            onToolCalls = { toolCalls ->
                                if (!resumed) {
                                    resumed = true
                                    cont.resume(Pair(contentAcc.toString(), toolCalls), null)
                                }
                            }
                        )
                    } else {
                        // 降级：不带 tools 的普通调用（仍使用 rawMessages 保持历史结构）
                        slot.provider.sendMessageStream(
                            messages = emptyList(),
                            systemPrompt = buildSystemPrompt() + "\n\n" + buildMessageHistoryText(messageHistory),
                            onSuccess = { chunk -> contentAcc.append(chunk) },
                            onComplete = { full ->
                                if (!resumed) { resumed = true; cont.resume(Pair(full, emptyList()), null) }
                            },
                            onError = { err ->
                                if (!resumed) { resumed = true; cont.resumeWith(Result.failure(Exception(err))) }
                            }
                        )
                    }
                }
            }
        } finally {
            AiProviderPool.releaseNonBlocking(slot)
        }
    }

    /**
     * 将 messageHistory 转为文字格式，作为 systemPrompt 的一部分传递
     *
     * 注意：此方法仅作为非 OpenAiCompatibleProvider 降级路径的后备方案。
     * 正常情况下，callLLMWithTools 会构建 proper JSONArray 直接传递讯息历史。
     */
    private fun buildMessageHistoryText(messageHistory: List<Map<String, Any>>): String {
        return buildString {
            appendLine("## 对话历史")
            for (msg in messageHistory) {
                val role = msg["role"] as? String ?: "unknown"
                when (role) {
                    "user" -> {
                        appendLine("### User")
                        appendLine(msg["content"].toString())
                    }
                    "assistant" -> {
                        appendLine("### Assistant")
                        val content = msg["content"]?.toString()
                        if (!content.isNullOrBlank()) appendLine(content)
                        val toolCalls = msg["tool_calls"] as? List<*>
                        if (toolCalls != null) {
                            appendLine("[请求调用工具]")
                            @Suppress("UNCHECKED_CAST")
                            for (tc in toolCalls as List<Map<String, Any>>) {
                                val func = tc["function"] as? Map<*, *> ?: continue
                                appendLine("  - ${func["name"]}: ${func["arguments"]}")
                            }
                        }
                    }
                    "tool" -> {
                        appendLine("### Tool Result (call_id: ${msg["tool_call_id"]})")
                        appendLine(msg["content"].toString())
                    }
                }
            }
        }
    }

    /**
     * 检查当前可用的 Provider 是否支援原生 Function Calling
     */
    private suspend fun checkProviderSupportsTools(): Boolean {
        val slot = AiProviderPool.acquire(
            context = context,
            callerTag = "Agent.$id.checkFC",
            timeoutMs = 5_000L
        ) ?: return false

        return try {
            // 检查 slot 是否为 OpenAiCompatibleProvider（支援 tools 参数）
            val isOpenAiCompatible = slot.provider is OpenAiCompatibleProvider
            // 检查 provider 名称是否在 ChatTools 支援列表中
            val isSupported = ChatTools.isSupportedByProvider(slot.configId)
            isOpenAiCompatible && isSupported
        } finally {
            AiProviderPool.releaseNonBlocking(slot)
        }
    }

    /**
     * 处理单个 tool_call
     *
     * 将 LLM 返回的 ChatTools.ToolCall 映射到已注册的 AgentTool 并执行。
     * 支援 ChatTools 定义的工具（stock_query, sector_query, market_brief）
     * 以及 Agent 自行注册的工具。
     *
     * @param tc LLM 返回的 tool_call
     * @return 工具执行结果字串
     */
    protected suspend fun handleToolCall(tc: ChatTools.ToolCall): String {
        val toolName = tc.function.name
        val argsJson = tc.function.arguments

        // 解析参数
        val params = try {
            val json = JSONObject(argsJson)
            mutableMapOf<String, String>().apply {
                json.keys().forEach { key ->
                    put(key, json.optString(key, ""))
                }
            }
        } catch (e: Exception) {
            log("  ⚠️ 解析 tool_call 参数失败: ${e.message}", isError = true)
            return "错误: 无法解析工具参数: ${e.message}"
        }

        log("  🔧 handleToolCall: $toolName | params: $params")

        // 1. 先尝试匹配已注册的 AgentTool
        val registeredTool = tools[toolName]
        if (registeredTool != null) {
            return try {
                registeredTool.execute(params, currentContext)
            } catch (e: Exception) {
                "错误: 工具 $toolName 执行失败: ${e.message}"
            }
        }

        // 2. 处理 ChatTools 中定义的标准工具（stock_query, sector_query, market_brief）
        // 这些工具通常由外部服务提供，此处为框架占位，子类可覆写扩展
        return when (toolName) {
            "stock_query", "sector_query", "market_brief" -> {
                // 标准工具：子类应透过 registerTool 注册对应的实作
                // 如果到这里，表示子类未注册，返回提示
                log("  ⚠️ 工具 $toolName 未在 Agent 中注册，请确认子类已 registerTool", isError = true)
                "错误: 工具 '$toolName' 未注册。请在 Agent 初始化时透过 registerTool() 注册此工具的实作。"
            }
            else -> {
                "错误: 未知工具 '$toolName'"
            }
        }
    }

    /** 日志 */
    private fun log(msg: String, isError: Boolean = false) {
        if (isError) {
            Log.e(TAG, "[$id] $msg")
        } else if (debugMode) {
            Log.d(TAG, "[$id] $msg")
        }
    }
}

/** Action 类型 */
enum class ActionType { TOOL_CALL, ANSWER, THINK }

/** Agent 行动 */
data class AgentAction(
    val type: ActionType,
    val content: String = "",
    val toolName: String? = null,
    val params: Map<String, String> = emptyMap()
)

/** 计划步骤类型 */
enum class PlanStepType { TOOL, LLM, SUB_AGENT }

/** 计划步骤 */
data class PlanStep(
    val type: PlanStepType,
    val description: String,
    val toolName: String? = null,
    val params: Map<String, String> = emptyMap(),
    val subAgentId: String? = null
)

/** 执行计划 */
data class AgentPlan(val steps: List<PlanStep>)

/** Agent 上下文 */
data class AgentContext(
    val data: MutableMap<String, Any> = mutableMapOf()
) {
    fun put(key: String, value: Any) = data.put(key, value)
    fun get(key: String): Any? = data[key]
    fun getString(key: String): String? = data[key]?.toString()
}

/** Agent 执行结果 */
data class AgentResult(
    val success: Boolean,
    val output: String,
    val steps: Int = 0,
    val observations: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap(),
    val error: Exception? = null
)

/** Agent 记忆 */
data class AgentMemory(
    val timestamp: Long,
    val type: String,
    val input: String,
    val output: String,
    val steps: Int
)
