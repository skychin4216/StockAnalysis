package com.chin.stockanalysis

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 API 直连提供商
 *
 * 支持模型回退机制：主模型（如 ep-*）不可用时自动切换到备用模型。
 * 支持 HTTP 5xx 自动重试（最多 2 次）。
 */
class OpenAiCompatibleProvider(override val config: ApiProviderConfig) : ApiProvider {

    companion object {
        private const val TAG = "OpenAiProvider"
        private const val TIMEOUT_SECONDS = 60L
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 1500L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun sendMessageStream(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit
    ) {
        sendWithFallback(messages, systemPrompt, onSuccess, onComplete, onError, modelIndex = 0)
    }

    private fun sendWithFallback(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit,
        modelIndex: Int
    ) {
        val effectiveModel = when (modelIndex) {
            0 -> config.model
            else -> {
                val idx = modelIndex - 1
                if (idx < config.fallbackModels.size) config.fallbackModels[idx]
                else { onError("所有模型均不可用（已尝试 $modelIndex 个）"); return }
            }
        }
        if (modelIndex > 0) Log.d(TAG, "🔄 回退到备用模型 #$modelIndex: $effectiveModel")
        sendWithRetry(messages, systemPrompt, onSuccess, onComplete, onError, retryCount = 0, currentModel = effectiveModel, modelIndex = modelIndex)
    }

    private fun sendWithRetry(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit,
        retryCount: Int,
        currentModel: String,
        modelIndex: Int
    ) {
        Log.d(TAG, "\n🌐🌐🌐 直连 API（第 ${retryCount + 1} 次）🌐🌐🌐")
        Log.d(TAG, "Provider: ${config.name}, Base URL: ${config.baseUrl}, Model: $currentModel")

        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val requestBody = buildOpenAiRequestBody(messages, systemPrompt, currentModel)
        Log.d(TAG, "📦 请求体:\n${redactSensitiveFields(requestBody).toString(2)}")
        Log.d(TAG, "📤 发送直连请求到: $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ 请求失败: ${e.javaClass.simpleName} - ${e.message}")
                if (retryCount < MAX_RETRIES && isRetryableFailure(e)) {
                    Log.d(TAG, "🔄 准备第 ${retryCount + 2} 次重试...")
                    Thread.sleep(RETRY_DELAY_MS)
                    sendWithRetry(messages, systemPrompt, onSuccess, onComplete, onError, retryCount + 1, currentModel, modelIndex)
                    return
                }
                if (modelIndex < config.fallbackModels.size) {
                    Log.d(TAG, "🔄 网络错误，回退到备用模型...")
                    sendWithFallback(messages, systemPrompt, onSuccess, onComplete, onError, modelIndex + 1)
                    return
                }
                onError(when {
                    e.message?.contains("timeout", true) == true -> "请求超时，请检查网络或稍后重试"
                    e.message?.contains("Unable to resolve host", true) == true -> "无法连接服务器：域名解析失败"
                    else -> "网络错误：${e.message}"
                })
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "\n📥 响应: HTTP ${response.code}")
                try {
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: ""
                        Log.e(TAG, "❌ HTTP ${response.code}: $errorBody")

                        if (response.code >= 500 && retryCount < MAX_RETRIES) {
                            Log.d(TAG, "🔄 HTTP ${response.code}，准备第 ${retryCount + 2} 次重试...")
                            Thread.sleep(RETRY_DELAY_MS)
                            sendWithRetry(messages, systemPrompt, onSuccess, onComplete, onError, retryCount + 1, currentModel, modelIndex)
                            return
                        }

                        if (modelIndex < config.fallbackModels.size) {
                            Log.d(TAG, "🔄 主模型失败，回退到备用模型...")
                            sendWithFallback(messages, systemPrompt, onSuccess, onComplete, onError, modelIndex + 1)
                            return
                        }

                        onError(parseErrorResponse(response.code, errorBody))
                        return
                    }

                    val contentType = response.header("Content-Type", "") ?: ""
                    Log.d(TAG, "Content-Type: $contentType, isStream=${contentType.contains("text/event-stream")}")

                    if (contentType.contains("text/event-stream") || contentType.contains("application/x-ndjson")) {
                        handleStreamResponse(response, onSuccess, onComplete, onError)
                    } else {
                        val raw = response.body?.string() ?: ""
                        Log.d(TAG, "非流式响应(前200字符): ${raw.take(200)}")
                        val content = extractContentFromResponse(raw)
                        if (content != null) onComplete(content)
                        else onError("解析响应失败：无法从响应中提取 content")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 处理响应出错: ${e.message}")
                    onError("处理响应出错：${e.message}")
                }
            }
        })
    }

    private fun buildOpenAiRequestBody(messages: List<Message>, systemPrompt: String?, model: String): JSONObject {
        val msgArray = JSONArray()
        if (!systemPrompt.isNullOrBlank()) msgArray.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        for (msg in messages) {
            if (msg.isStreaming || msg.isError || msg.content.isBlank()) continue
            msgArray.put(JSONObject().apply { put("role", if (msg.isUser) "user" else "assistant"); put("content", msg.content) })
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", msgArray)
            put("temperature", 0.7)
            put("max_tokens", 4096)
            put("stream", true)
        }
    }

    private fun handleStreamResponse(response: Response, onSuccess: (String) -> Unit, onComplete: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val body = response.body ?: run { onError("响应体为空"); return }
            // 先用 string() 取原始内容，再逐行解析（Android 上 byteStream() 有时返回已关闭的流）
            val raw = body.string()
            Log.d(TAG, "SSE 原始响应长度: ${raw.length}")
            if (raw.isBlank()) {
                Log.w(TAG, "SSE 响应为空")
                onError("AI 回复为空")
                return
            }
            val fullContent = StringBuilder()
            var lineCount = 0
            for (line in raw.lines()) {
                lineCount++
                val trimmed = line.trim()
                if (trimmed.startsWith("data: ")) {
                    val data = trimmed.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        Log.d(TAG, "✅ 收到 [DONE] (共 $lineCount 行)")
                        break
                    }
                    try {
                        val json = JSONObject(data)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val choice = choices.getJSONObject(0)
                            val delta = choice.optJSONObject("delta")
                            val msg = choice.optJSONObject("message")
                            val content = delta?.optString("content", "")
                                ?: msg?.optString("content", "")
                                ?: ""
                            if (content.isNotBlank()) {
                                fullContent.append(content)
                                onSuccess(content)
                            }
                        }
                    } catch (ex: Exception) {
                        if (data.isNotBlank() && data.length < 200) {
                            Log.d(TAG, "跳过非 JSON data: $data")
                        }
                    }
                }
            }
            val result = fullContent.toString()
            Log.d(TAG, "流式处理完成: ${result.length} 字符, $lineCount 行")
            if (result.isNotBlank()) {
                onComplete(result)
            } else {
                Log.w(TAG, "流式响应为空（原始 ${raw.length} 字节, $lineCount 行）\n原始内容: ${raw.take(300)}")
                onError("AI 回复为空")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 流式处理异常: ${e.message}", e)
            onError("流式处理异常：${e.message}")
        }
    }

    private fun extractContentFromResponse(bodyStr: String): String? = try {
        val json = JSONObject(bodyStr)
        json.optJSONArray("choices")?.getJSONObject(0)?.optJSONObject("message")?.optString("content", null)
    } catch (e: Exception) { Log.e(TAG, "❌ 解析 JSON 失败: ${e.message}"); null }

    private fun isRetryableFailure(e: IOException): Boolean {
        val msg = e.message?.lowercase() ?: return true
        return msg.contains("timeout") || msg.contains("reset") || msg.contains("refused") || msg.contains("closed") || msg.contains("eof") || msg.contains("broken pipe")
    }

    private fun redactSensitiveFields(source: JSONObject) = JSONObject(source.toString()).apply { if (has("api_key")) put("api_key", "***") }

    private fun parseErrorResponse(httpCode: Int, errorBody: String): String {
        val (serverCode, serverMsg) = try {
            val json = JSONObject(errorBody)
            val error = json.optJSONObject("error")
            if (error != null) Pair(error.optString("code", ""), error.optString("message", ""))
            else Pair("", json.optString("message", errorBody))
        } catch (_: Exception) { Pair("", errorBody) }
        val hint = if (serverMsg.isNotBlank()) "\n💬 服务器返回: $serverMsg" else ""
        val cl = serverCode.lowercase(); val ml = serverMsg.lowercase()
        return when {
            httpCode == 402 || ml.contains("insufficient balance") -> "💰 账户余额不足（402）\n您的 API 账户余额已用完，请前往对应平台充值后再试。$hint"
            httpCode == 400 && (ml.contains("model") || ml.contains("endpoint")) -> "🔍 模型不存在（400）\n您选择的模型名称在当前提供商中不存在。$hint"
            httpCode == 400 -> "⚠️ 请求参数错误（400）$hint"
            httpCode == 401 -> "🔑 API Key 无效（401）\n请在设置中重新填写正确的 API Key。$hint"
            httpCode == 403 || cl.contains("forbidden") -> "🚫 访问被拒绝（403）\n可能原因：API Key 权限不足、模型未开通、或账户余额为 0。$hint"
            httpCode == 404 || ml.contains("does not exist") -> "❓ 模型或端点不存在（404）\n请在设置中切换模型。$hint"
            httpCode == 429 || ml.contains("rate limit") -> "⏳ 请求过于频繁（429）\n请稍候 10 秒后再试。$hint"
            httpCode >= 500 -> "🔧 AI 服务暂时不可用（$httpCode）\n已自动重试 $MAX_RETRIES 次，请稍后重试。$hint"
            else -> "❌ 请求失败（HTTP $httpCode）$hint"
        }
    }
}