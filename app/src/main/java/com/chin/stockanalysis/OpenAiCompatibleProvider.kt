package com.chin.stockanalysis

import android.util.Log
import com.chin.stockanalysis.ai.ChatTools
import com.chin.stockanalysis.ui.Message
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容 API 直连提供商
 *
 * 根本性健壮改进：
 * - 连接/写入超时 30s，读取超时不限（流式连接可能长时间无数据）
 * - OkHttp 自动重试网络故障
 * - 流式解析容错：跳过空行/非 JSON 行，JSON 解析失败不中断流
 * - cancel() 取消进行中的请求
 */
class OpenAiCompatibleProvider(override val config: ApiProviderConfig) : ApiProvider {

    /** 流式请求的完整回应（包含可能的 tool_calls） */
    data class ChatResponse(
        val content: String,
        val finishReason: String?,
        val toolCalls: List<ChatTools.ToolCall> = emptyList()
    )

    companion object {
        private const val TAG = "OpenAiProvider"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)      // 流式无读取超时
        .retryOnConnectionFailure(true)              // 网络故障自动重试
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))  // 连接池复用
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))   // HTTP/2 多路复用
        .build()

    private val activeCall = AtomicReference<Call?>(null)

    override fun cancel() {
        val call = activeCall.getAndSet(null)
        call?.cancel()
    }

    override fun sendMessageStream(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit
    ) {
        doSend(messages, systemPrompt, onSuccess, onComplete, onError, null, null, null, modelIndex = 0, retryCount = 0, jsonMode = false)
    }

    /**
     * 强制 JSON 输出模式（response_format: json_object）
     * 适用于 Agent 场景：结构化数据提取、股票分析 JSON 输出等
     *
     * @param maxTokens 自定义最大输出 tokens（预设 6144）。
     *   简单 Agent（1-3）建议 4096，复杂 Agent（6 风控）建议 6144-8192
     */
    fun sendMessageStreamJson(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit,
        maxTokens: Int? = null
    ) {
        doSend(messages, systemPrompt, onSuccess, onComplete, onError, null, null, null, modelIndex = 0, retryCount = 0, jsonMode = true, maxTokens = maxTokens)
    }

    /**
     * 带 Function Calling 工具的扩展方法（非接口方法）
     */
    fun sendMessageStreamWithTools(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit,
        tools: List<ChatTools.ToolDef>? = null,
        toolChoice: String? = null,
        onToolCalls: ((List<ChatTools.ToolCall>) -> Unit)? = null
    ) {
        doSend(messages, systemPrompt, onSuccess, onComplete, onError, tools, toolChoice, onToolCalls, modelIndex = 0, retryCount = 0, jsonMode = false)
    }

    /**
     * 带 Function Calling 的流式请求（使用预构建的 JSONArray 讯息）
     *
     * 与 sendMessageStreamWithTools 不同，此方法接受预先构建好的 JSONArray 讯息阵列，
     * 允许呼叫者直接控制 system/user/assistant/tool 等角色讯息，
     * 支援完整的 Function Calling 对话历史（包含 tool_calls 和 tool role 回复）。
     */
    fun sendMessageStreamWithRawMessages(
        rawMessages: JSONArray,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit,
        tools: List<ChatTools.ToolDef>? = null,
        toolChoice: String? = null,
        onToolCalls: ((List<ChatTools.ToolCall>) -> Unit)? = null
    ) {
        doSend(emptyList(), "", onSuccess, onComplete, onError, tools, toolChoice, onToolCalls,
            modelIndex = 0, retryCount = 0, jsonMode = false, rawMessages = rawMessages)
    }

    private fun doSend(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit,
        tools: List<ChatTools.ToolDef>?,
        toolChoice: String?,
        onToolCalls: ((List<ChatTools.ToolCall>) -> Unit)?,
        modelIndex: Int,
        retryCount: Int,
        jsonMode: Boolean,
        maxTokens: Int? = null,
        rawMessages: JSONArray? = null
    ) {
        val model = getModel(modelIndex, onError) ?: return
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val requestBody = buildBody(messages, systemPrompt, model, tools, toolChoice, jsonMode, maxTokens, rawMessages)

        Log.d(TAG, "📤 请求: $url | 模型: $model | 重试: $retryCount | jsonMode=$jsonMode")

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(request)
        activeCall.set(call)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCall.compareAndSet(call, null)
                if (call.isCanceled()) return

                Log.e(TAG, "❌ 网络失败: ${e.message}")
                handleRetry(messages, systemPrompt, onSuccess, onComplete, onError,
                    tools, toolChoice, onToolCalls, modelIndex, retryCount + 1, "网络错误: ${e.message}", jsonMode, rawMessages = rawMessages)
            }

            override fun onResponse(call: Call, response: Response) {
                activeCall.compareAndSet(call, null)
                if (call.isCanceled()) { response.close(); return }

                if (!response.isSuccessful) {
                    response.close()
                    val code = response.code
                    Log.e(TAG, "❌ HTTP $code")
                    // 4xx 客户端错误（404/403/401）重试无意义，直接跳到回退模型
                    if (code in 400..499) {
                        Log.d(TAG, "🔄 客户端错误 $code，跳过重试，直接切换模型")
                        handleRetry(messages, systemPrompt, onSuccess, onComplete, onError,
                            tools, toolChoice, onToolCalls, modelIndex, 3, "HTTP $code", jsonMode, rawMessages = rawMessages)  // retryCount=3 强制跳过重试
                    } else {
                        handleRetry(messages, systemPrompt, onSuccess, onComplete, onError,
                            tools, toolChoice, onToolCalls, modelIndex, retryCount + 1, "HTTP $code", jsonMode, rawMessages = rawMessages)
                    }
                    return
                }

                try {
                    handleResponse(response, onSuccess, onComplete, onError, onToolCalls, jsonMode)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 响应处理异常: ${e.message}")
                    val partial = accumulated?.toString() ?: ""
                    if (partial.isNotBlank()) {
                        onComplete(partial)
                    } else {
                        handleRetry(messages, systemPrompt, onSuccess, onComplete, onError,
                            tools, toolChoice, onToolCalls, modelIndex, retryCount + 1, "流式处理异常: ${e.message}", jsonMode, rawMessages = rawMessages)
                    }
                } finally {
                    // 确保 response body 被关闭，避免 OkHttp 连接泄漏
                    try { response.close() } catch (_: Exception) {}
                }
            }
        })
    }

    private fun getModel(modelIndex: Int, onError: (String) -> Unit): String? {
        return when (modelIndex) {
            0 -> config.model
            else -> {
                val idx = modelIndex - 1
                if (idx < config.fallbackModels.size) config.fallbackModels[idx]
                else { onError("所有模型均不可用"); null }
            }
        }
    }

    private fun handleRetry(
        messages: List<Message>, systemPrompt: String,
        onSuccess: (String) -> Unit, onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        tools: List<ChatTools.ToolDef>?,
        toolChoice: String?,
        onToolCalls: ((List<ChatTools.ToolCall>) -> Unit)?,
        modelIndex: Int, retryCount: Int, lastError: String,
        jsonMode: Boolean = false,
        maxTokens: Int? = null,
        rawMessages: JSONArray? = null
    ) {
        when {
            retryCount < 3 -> {
                Log.d(TAG, "🔄 第 $retryCount 次重试中...")
                doSend(messages, systemPrompt, onSuccess, onComplete, onError, tools, toolChoice, onToolCalls, modelIndex, retryCount, jsonMode, maxTokens, rawMessages)
            }
            modelIndex < config.fallbackModels.size -> {
                Log.d(TAG, "🔄 回退到备用模型 #${modelIndex + 1}")
                doSend(messages, systemPrompt, onSuccess, onComplete, onError, tools, toolChoice, onToolCalls, modelIndex + 1, 0, jsonMode, maxTokens, rawMessages)
            }
            else -> onError(lastError)
        }
    }

    private fun buildBody(
        messages: List<Message>, systemPrompt: String?, model: String,
        tools: List<ChatTools.ToolDef>? = null,
        toolChoice: String? = null,
        jsonMode: Boolean = false,
        maxTokens: Int? = null,
        rawMessages: JSONArray? = null
    ): JSONObject {
        val msgArray = if (rawMessages != null) {
            rawMessages
        } else {
            JSONArray().apply {
                if (!systemPrompt.isNullOrBlank())
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                for (msg in messages) {
                    if (msg.isStreaming || msg.isError || msg.content.isBlank()) continue
                    put(JSONObject().apply {
                        put("role", if (msg.isUser) "user" else "assistant")
                        put("content", msg.content)
                    })
                }
            }
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", msgArray)
            put("temperature", 0.7)
            // Pipeline Agent 分层：简单 Agent 4096，复杂 Agent 6144，通用预设 6144
            put("max_tokens", maxTokens ?: if (jsonMode) 6144 else 4096)
            put("stream", true)
            if (jsonMode) {
                put("response_format", JSONObject().apply { put("type", "json_object") })
                // 关闭思考模式（doubao-seed-1.6 等推理模型支持），避免思考过程混入输出
                put("thinking", JSONObject().apply { put("type", "disabled") })
            }
            // Function Calling 工具定义
            if (!tools.isNullOrEmpty()) {
                val toolsArray = JSONArray()
                for (tool in tools) {
                    toolsArray.put(JSONObject().apply {
                        put("type", tool.type)
                        put("function", JSONObject().apply {
                            put("name", tool.function.name)
                            put("description", tool.function.description)
                            put("parameters", tool.function.parameters.toJsonObject())
                        })
                    })
                }
                put("tools", toolsArray)
                put("tool_choice", toolChoice ?: "auto")
            }
        }
    }

    /** 将 Map<String, Any> 转为 JSONObject（递回处理嵌套结构） */
    private fun Map<String, Any>.toJsonObject(): JSONObject {
        val json = JSONObject()
        for ((key, value) in this) {
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    json.put(key, (value as Map<String, Any>).toJsonObject())
                }
                is List<*> -> {
                    val arr = JSONArray()
                    for (item in value) {
                        when (item) {
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                arr.put((item as Map<String, Any>).toJsonObject())
                            }
                            is String? -> arr.put(item)
                            is Number -> arr.put(item)
                            is Boolean -> arr.put(item)
                            else -> arr.put(item?.toString())
                        }
                    }
                    json.put(key, arr)
                }
                is String -> json.put(key, value)
                is Number -> json.put(key, value)
                is Boolean -> json.put(key, value)
                else -> json.put(key, value.toString())
            }
        }
        return json
    }

    // ─── 流式响应处理（核心健壮改进） ───

    /** 线程间传递已累积内容（用于异常时回传部分结果） */
    @Volatile
    private var accumulated: StringBuilder? = null

    private fun handleResponse(
        response: Response,
        onSuccess: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        onToolCalls: ((List<ChatTools.ToolCall>) -> Unit)? = null,
        jsonMode: Boolean = false
    ) {
        val body = response.body
        if (body == null) {
            response.close()
            onError("响应体为空")
            return
        }
        val source = body.source()
        val sb = StringBuilder()
        accumulated = sb
        var lineCount = 0

        // tool_calls 流式累积：按 index 分别收集 id / function.name / function.arguments 片段
        val toolCallBuilders = mutableMapOf<Int, MutableMap<String, StringBuilder>>()
        var finishReason: String? = null

        var pendingLine: String? = null
        try {
            while (true) {
                val line = pendingLine ?: (source.readUtf8Line() ?: break)
                pendingLine = null
                lineCount++

                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("data: ")) continue

                var data = trimmed.removePrefix("data: ").trim()
                if (data == "[DONE]") {
                    Log.d(TAG, "✅ [DONE] | ${sb.length} 字符 | $lineCount 行")
                    break
                }

                // 容错：如果单行 JSON 解析失败，尝试累积下一行（SSE 可能因推理模型新行符分裂）
                try {
                    parseSSEData(data, sb, onSuccess, toolCallBuilders, finishReason, jsonMode)
                } catch (e: Exception) {
                    val nextLine = source.readUtf8Line()
                    if (nextLine != null) {
                        lineCount++
                        val nextTrimmed = nextLine.trim()
                        if (nextTrimmed.startsWith("data: ")) {
                            // 下一行是新的 SSE 事件，当前行确实是非 JSON 数据
                            pendingLine = nextLine
                            if (lineCount <= 3) Log.v(TAG, "跳过非 JSON 行: ${data.take(80)}")
                        } else if (nextTrimmed.isNotEmpty()) {
                            // 当前 JSON 被换行符截断，尝试拼接
                            val merged = data + nextTrimmed
                            try {
                                parseSSEData(merged, sb, onSuccess, toolCallBuilders, finishReason, jsonMode)
                            } catch (e2: Exception) {
                                if (lineCount <= 3) Log.v(TAG, "跳过非 JSON 行(合并后): ${data.take(80)} | next=${nextTrimmed.take(40)}")
                            }
                        } else {
                            if (lineCount <= 3) Log.v(TAG, "跳过非 JSON 行: ${data.take(80)}")
                        }
                    } else {
                        if (lineCount <= 3) Log.v(TAG, "跳过非 JSON 行(EOF): ${data.take(80)}")
                    }
                }
            }
        } catch (e: IOException) {
            val partial = sb.toString()
            if (partial.isNotBlank()) {
                Log.w(TAG, "⚠️ 流中断（${e.message}），已收到 ${partial.length} 字符，返回部分结果")
                onComplete(partial)
            } else {
                throw e // 没有收到任何数据，抛出让外层重试
            }
            return
        } finally {
            accumulated = null
            // 确保 source 和 response 被关闭，避免 OkHttp 连接泄漏
            try { source.close() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
        }

        // 组装 tool_calls 结果
        val collectedToolCalls = if (toolCallBuilders.isNotEmpty()) {
            toolCallBuilders.entries.sortedBy { it.key }.mapNotNull { (_, parts) ->
                val id = parts["id"]?.toString() ?: return@mapNotNull null
                val name = parts["name"]?.toString() ?: return@mapNotNull null
                val args = parts["arguments"]?.toString() ?: "{}"
                ChatTools.ToolCall(
                    id = id,
                    type = "function",
                    function = ChatTools.FunctionCall(name = name, arguments = args)
                )
            }
        } else emptyList()

        if (collectedToolCalls.isNotEmpty()) {
            Log.d(TAG, "🔧 收集到 ${collectedToolCalls.size} 个 tool_calls | finishReason=$finishReason")
            onToolCalls?.invoke(collectedToolCalls)
        }

        val result = sb.toString()
        if (result.isNotBlank()) {
            Log.d(TAG, "流式完成: ${result.length} 字符 | $lineCount 行")
            onComplete(result)
        } else if (collectedToolCalls.isNotEmpty()) {
            // 纯 tool_call 无 content，视为成功完成
            onComplete("")
        } else {
            onError("AI 回复为空")
        }
    }

    /** 解析 SSE 数据行，提取 delta content / reasoning_content / tool_calls */
    private fun parseSSEData(
        data: String,
        sb: StringBuilder,
        onSuccess: (String) -> Unit,
        toolCallBuilders: MutableMap<Int, MutableMap<String, StringBuilder>>,
        _finishReason: String?,
        jsonMode: Boolean = false
    ) {
        val json = JSONObject(data)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) return
        val choice = choices.getJSONObject(0)
        val delta = choice.optJSONObject("delta") ?: return

        // content 和 reasoning_content 二选一
        // jsonMode 时丢弃 reasoning_content（思考过程），只保留 content（最终回答）
        val content = delta.optString("content", "")
        val reasoningContent = delta.optString("reasoning_content", "")
        val textToAppend = if (jsonMode) {
            content  // JSON 模式：只要最终回答，不要思考过程
        } else {
            content.ifBlank { reasoningContent }  // 普通模式：回退到思考过程
        }
        if (textToAppend.isNotEmpty()) {
            sb.append(textToAppend)
            onSuccess(textToAppend)
        }

        // 解析 delta 中的 tool_calls
        val toolCallsArr = delta.optJSONArray("tool_calls")
        if (toolCallsArr != null) {
            for (i in 0 until toolCallsArr.length()) {
                val tc = toolCallsArr.getJSONObject(i)
                val index = tc.optInt("index", i)
                val builder = toolCallBuilders.getOrPut(index) {
                    mutableMapOf("id" to StringBuilder(), "name" to StringBuilder(), "arguments" to StringBuilder())
                }
                tc.optString("id", null)?.let { builder["id"]?.append(it) }
                val funcObj = tc.optJSONObject("function")
                if (funcObj != null) {
                    funcObj.optString("name", null)?.let { builder["name"]?.append(it) }
                    funcObj.optString("arguments", null)?.let { builder["arguments"]?.append(it) }
                }
            }
        }
    }
}
