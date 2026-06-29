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
        onError: (errorMsg: String) -> Unit,
        tools: List<ChatTools.ToolDef>? = null,
        toolChoice: String? = null,
        onToolCalls: ((List<ChatTools.ToolCall>) -> Unit)? = null
    ) {
        doSend(messages, systemPrompt, onSuccess, onComplete, onError, tools, toolChoice, onToolCalls, modelIndex = 0, retryCount = 0)
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
        retryCount: Int
    ) {
        val model = getModel(modelIndex, onError) ?: return
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val requestBody = buildBody(messages, systemPrompt, model, tools, toolChoice)

        Log.d(TAG, "📤 请求: $url | 模型: $model | 重试: $retryCount")

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
                    tools, toolChoice, onToolCalls, modelIndex, retryCount + 1, "网络错误: ${e.message}")
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
                            tools, toolChoice, onToolCalls, modelIndex, 3, "HTTP $code")  // retryCount=3 强制跳过重试
                    } else {
                        handleRetry(messages, systemPrompt, onSuccess, onComplete, onError,
                            tools, toolChoice, onToolCalls, modelIndex, retryCount + 1, "HTTP $code")
                    }
                    return
                }

                try {
                    handleResponse(response, onSuccess, onComplete, onError, onToolCalls)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 响应处理异常: ${e.message}")
                    val partial = accumulated?.toString() ?: ""
                    if (partial.isNotBlank()) {
                        onComplete(partial)
                    } else {
                        handleRetry(messages, systemPrompt, onSuccess, onComplete, onError,
                            tools, toolChoice, onToolCalls, modelIndex, retryCount + 1, "流式处理异常: ${e.message}")
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
        modelIndex: Int, retryCount: Int, lastError: String
    ) {
        when {
            retryCount < 3 -> {
                Log.d(TAG, "🔄 第 $retryCount 次重试中...")
                doSend(messages, systemPrompt, onSuccess, onComplete, onError, tools, toolChoice, onToolCalls, modelIndex, retryCount)
            }
            modelIndex < config.fallbackModels.size -> {
                Log.d(TAG, "🔄 回退到备用模型 #${modelIndex + 1}")
                doSend(messages, systemPrompt, onSuccess, onComplete, onError, tools, toolChoice, onToolCalls, modelIndex + 1, 0)
            }
            else -> onError(lastError)
        }
    }

    private fun buildBody(
        messages: List<Message>, systemPrompt: String?, model: String,
        tools: List<ChatTools.ToolDef>? = null,
        toolChoice: String? = null
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
            put("max_tokens", 4096)
            put("stream", true)
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
        onToolCalls: ((List<ChatTools.ToolCall>) -> Unit)? = null
    ) {
        val body = response.body ?: run { onError("响应体为空"); return }
        val source = body.source()
        val sb = StringBuilder()
        accumulated = sb
        var lineCount = 0

        // tool_calls 流式累積：按 index 分別收集 id / function.name / function.arguments 片段
        val toolCallBuilders = mutableMapOf<Int, MutableMap<String, StringBuilder>>()
        var finishReason: String? = null

        try {
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                lineCount++

                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("data: ")) continue

                val data = trimmed.removePrefix("data: ").trim()
                if (data == "[DONE]") {
                    Log.d(TAG, "✅ [DONE] | ${sb.length} 字符 | $lineCount 行")
                    break
                }

                // 容错解析：单行 JSON 失败不中断整个流
                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        finishReason = choice.optString("finish_reason", null).ifBlank { null }
                        val delta = choice.optJSONObject("delta")
                        if (delta != null) {
                            // 使用 opt() 避免 null 转字符串
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                sb.append(content)
                                onSuccess(content)
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
                                    tc.optString("type", null)?.let { /* type 通常只在第一個 chunk 出現，已由 id 區分 */ }
                                    val funcObj = tc.optJSONObject("function")
                                    if (funcObj != null) {
                                        funcObj.optString("name", null)?.let { builder["name"]?.append(it) }
                                        funcObj.optString("arguments", null)?.let { builder["arguments"]?.append(it) }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 跳过非 JSON 行（如注释、空数据行）
                    if (lineCount <= 2) Log.v(TAG, "跳过非 JSON 行: ${data.take(80)}")
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
}