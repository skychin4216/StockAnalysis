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

    /** 流式請求的完整回應（包含可能的 tool_calls） */
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
     * 強制 JSON 輸出模式（response_format: json_object）
     * 適用於 Agent 場景：結構化數據提取、股票分析 JSON 輸出等
     */
    fun sendMessageStreamJson(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit
    ) {
        doSend(messages, systemPrompt, onSuccess, onComplete, onError, null, null, null, modelIndex = 0, retryCount = 0, jsonMode = true)
    }

    /**
     * 帶 Function Calling 工具的擴展方法（非接口方法）
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
        jsonMode: Boolean
    ) {
        val model = getModel(modelIndex, onError) ?: return
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val requestBody = buildBody(messages, systemPrompt, model, tools, toolChoice, jsonMode)

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
                    tools, toolChoice, onToolCalls, modelIndex, retryCount + 1, "网络错误: ${e.message}", jsonMode)
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
                            tools, toolChoice, onToolCalls, modelIndex, 3, "HTTP $code", jsonMode)  // retryCount=3 强制跳过重试
                    } else {
                        handleRetry(messages, systemPrompt, onSuccess, onComplete, onError,
                            tools, toolChoice, onToolCalls, modelIndex, retryCount + 1, "HTTP $code", jsonMode)
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
                            tools, toolChoice, onToolCalls, modelIndex, retryCount + 1, "流式处理异常: ${e.message}", jsonMode)
                    }
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
        jsonMode: Boolean = false
    ) {
        when {
            retryCount < 3 -> {
                Log.d(TAG, "🔄 第 $retryCount 次重试中...")
                doSend(messages, systemPrompt, onSuccess, onComplete, onError, tools, toolChoice, onToolCalls, modelIndex, retryCount, jsonMode)
            }
            modelIndex < config.fallbackModels.size -> {
                Log.d(TAG, "🔄 回退到备用模型 #${modelIndex + 1}")
                doSend(messages, systemPrompt, onSuccess, onComplete, onError, tools, toolChoice, onToolCalls, modelIndex + 1, 0, jsonMode)
            }
            else -> onError(lastError)
        }
    }

    private fun buildBody(
        messages: List<Message>, systemPrompt: String?, model: String,
        tools: List<ChatTools.ToolDef>? = null,
        toolChoice: String? = null,
        jsonMode: Boolean = false
    ): JSONObject {
        val msgArray = JSONArray()
        if (!systemPrompt.isNullOrBlank())
            msgArray.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        for (msg in messages) {
            if (msg.isStreaming || msg.isError || msg.content.isBlank()) continue
            msgArray.put(JSONObject().apply {
                put("role", if (msg.isUser) "user" else "assistant")
                put("content", msg.content)
            })
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", msgArray)
            put("temperature", 0.7)
            put("max_tokens", if (jsonMode) 16384 else 4096)
            put("stream", true)
            if (jsonMode) {
                put("response_format", JSONObject().apply { put("type", "json_object") })
                // 關閉思考模式（doubao-seed-1.6 等推理模型支持），避免思考過程混入輸出
                put("thinking", JSONObject().apply { put("type", "disabled") })
            }
            // Function Calling 工具定義
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

    /** 將 Map<String, Any> 轉為 JSONObject（遞迴處理嵌套結構） */
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
                is String? -> json.put(key, value)
                is Number -> json.put(key, value)
                is Boolean -> json.put(key, value)
                else -> json.put(key, value?.toString())
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
        val body = response.body ?: run { onError("响应体为空"); return }
        val source = body.source()
        val sb = StringBuilder()
        accumulated = sb
        var lineCount = 0

        // tool_calls 流式累積：按 index 分別收集 id / function.name / function.arguments 片段
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
        }

        // 組裝 tool_calls 結果
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
            Log.d(TAG, "🔧 收集到 ${collectedToolCalls.size} 個 tool_calls | finishReason=$finishReason")
            onToolCalls?.invoke(collectedToolCalls)
        }

        val result = sb.toString()
        if (result.isNotBlank()) {
            Log.d(TAG, "流式完成: ${result.length} 字符 | $lineCount 行")
            onComplete(result)
        } else if (collectedToolCalls.isNotEmpty()) {
            // 純 tool_call 無 content，視為成功完成
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
        finishReason: String?,
        jsonMode: Boolean = false
    ) {
        val json = JSONObject(data)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) return
        val choice = choices.getJSONObject(0)
        val fr = choice.optString("finish_reason", "").ifBlank { null }
        val delta = choice.optJSONObject("delta") ?: return

        // content 和 reasoning_content 二选一
        // jsonMode 時丟棄 reasoning_content（思考過程），只保留 content（最終回答）
        val content = delta.optString("content", "")
        val reasoningContent = delta.optString("reasoning_content", "")
        val textToAppend = if (jsonMode) {
            content  // JSON 模式：只要最終回答，不要思考過程
        } else {
            content.ifBlank { reasoningContent }  // 普通模式：回退到思考過程
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
