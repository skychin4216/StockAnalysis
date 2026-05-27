package com.chin.stockanalysis

import android.util.Log
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
        doSend(messages, systemPrompt, onSuccess, onComplete, onError, modelIndex = 0, retryCount = 0)
    }

    private fun doSend(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit,
        modelIndex: Int,
        retryCount: Int
    ) {
        val model = getModel(modelIndex, onError) ?: return
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val requestBody = buildBody(messages, systemPrompt, model)

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
                    modelIndex, retryCount + 1, "网络错误: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                activeCall.compareAndSet(call, null)
                if (call.isCanceled()) { response.close(); return }

                if (!response.isSuccessful) {
                    response.close()
                    val code = response.code
                    Log.e(TAG, "❌ HTTP $code")
                    handleRetry(messages, systemPrompt, onSuccess, onComplete, onError,
                        modelIndex, retryCount + 1, "HTTP $code")
                    return
                }

                try {
                    handleResponse(response, onSuccess, onComplete, onError)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 响应处理异常: ${e.message}")
                    val partial = accumulated?.toString() ?: ""
                    if (partial.isNotBlank()) {
                        onComplete(partial)
                    } else {
                        handleRetry(messages, systemPrompt, onSuccess, onComplete, onError,
                            modelIndex, retryCount + 1, "流式处理异常: ${e.message}")
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
        modelIndex: Int, retryCount: Int, lastError: String
    ) {
        when {
            retryCount < 3 -> {
                Log.d(TAG, "🔄 第 $retryCount 次重试中...")
                doSend(messages, systemPrompt, onSuccess, onComplete, onError, modelIndex, retryCount)
            }
            modelIndex < config.fallbackModels.size -> {
                Log.d(TAG, "🔄 回退到备用模型 #${modelIndex + 1}")
                doSend(messages, systemPrompt, onSuccess, onComplete, onError, modelIndex + 1, 0)
            }
            else -> onError(lastError)
        }
    }

    private fun buildBody(messages: List<Message>, systemPrompt: String?, model: String): JSONObject {
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
        }
    }

    // ─── 流式响应处理（核心健壮改进） ───

    /** 线程间传递已累积内容（用于异常时回传部分结果） */
    @Volatile
    private var accumulated: StringBuilder? = null

    private fun handleResponse(
        response: Response,
        onSuccess: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val body = response.body ?: run { onError("响应体为空"); return }
        val source = body.source()
        val sb = StringBuilder()
        accumulated = sb
        var lineCount = 0

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
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        if (delta != null) {
                            // 使用 opt() 避免 null 转字符串
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                sb.append(content)
                                onSuccess(content)
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

        val result = sb.toString()
        if (result.isNotBlank()) {
            Log.d(TAG, "流式完成: ${result.length} 字符 | $lineCount 行")
            onComplete(result)
        } else {
            onError("AI 回复为空")
        }
    }
}