package com.chin.stockanalysis

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 多模型直连测试
 *
 * 直接从 TestProviders 读取配置，验证各 API Key 和模型是否可用。
 *
 * 测试分类：
 * - testFreeXxx() ➔ 免费模型，无需充值即可使用
 * - testPaidXxx() ➔ 付费模型，需要账号有余额
 *
 * 所有配置统一在 TestProviders.kt 中管理，API Key 统一从 api_keys_local.properties 读取。
 */
class ModelDirectTest {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ═══════════════════════════════════════════════════════
    //  🆓 免费模型测试（无需充值）
    // ═══════════════════════════════════════════════════════

    @Test
    fun testFree_SiliconflowV3Flash() {
        testProvider(TestProviders.FREE_PROVIDERS[0])
    }

    @Test
    fun testFree_SiliconflowV3Flash_Stream() {
        testProviderStream(TestProviders.FREE_PROVIDERS[0])
    }

    @Test
    fun testFree_SiliconflowV3() {
        testProvider(TestProviders.FREE_PROVIDERS[1])
    }

    @Test
    fun testFree_SiliconflowR1() {
        testProvider(TestProviders.FREE_PROVIDERS[2])
    }

    @Test
    fun testFree_SiliconflowQwen() {
        testProvider(TestProviders.FREE_PROVIDERS[3])
    }

    // ═══════════════════════════════════════════════════════
    //  💳 付费模型测试（需要余额）
    // ═══════════════════════════════════════════════════════

    @Test
    fun testPaid_Doubao() {
        testProvider(TestProviders.findById("doubao")!!)
    }

    @Test
    fun testPaid_Doubao_ResponsesAPIModel() {
        testProvider(TestProviders.findById("doubao-responses")!!, "豆包 Responses API (doubao-seed-2-0-mini-260215)")
    }

    @Test
    fun testPaid_DeepSeekOfficial() {
        testProvider(TestProviders.findById("deepseek-official")!!)
    }

    @Test
    fun testPaid_AliyunMaas() {
        testProvider(TestProviders.findById("aliyun-maas")!!)
    }

    @Test
    fun testPaid_AliyunMaas_Stream() {
        testProviderStream(TestProviders.findById("aliyun-maas")!!)
    }

    @Test
    fun testPaid_AliyunMaas_QwenPlus() {
        val cfg = TestProviders.findById("aliyun-maas")!!
        testProvider(cfg.copy(model = "qwen-plus"), "qwen-plus 模型")
    }

    @Test
    fun testPaid_AliyunMaas_DashScopeNative() {
        testProvider(TestProviders.ALIYUN_MAAS_DASHSCOPE)
    }

    // ═══════════════════════════════════════════════════════
    //   核心方法
    // ═══════════════════════════════════════════════════════

    /**
     * 非流式测试
     */
    private fun testProvider(config: TestProviderConfig, modelLabel: String? = null) {
        val label = modelLabel ?: config.name
        val model = config.model

        println()
        println("╔══════════════════════════════════════════════════╗")
        println("║  📡 $label")
        println("║  ID:    ${config.id}")
        println("║  URL:   ${config.baseUrl}chat/completions")
        println("║  Model: $model")
        println("║  Type:  ${if (config.isFree) "🆓 免费" else "💳 付费"}")
        println("╚══════════════════════════════════════════════════╝")
        println()

        val apiKey = config.apiKey
        if (apiKey.isBlank()) {
            val msg = "⚠️ 跳过：${config.name} 的 API Key 为空\n   请在 api_keys_local.properties 中配置 ${ApiKeysLoader.KEY_ALIYUN_MAAS}（适用于阿里云MaaS）等对应 Key"
            println(msg)
            println()
            return
        }

        // 构建请求体
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个有用的AI助手。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "你好，请用一句话介绍你自己。")
                })
            })
            put("temperature", 0.7)
            put("max_tokens", 200)
            put("stream", false)
        }

        val requestBodyStr = payload.toString(2)
        println("📤 请求体:")
        println(requestBodyStr.lines().joinToString("\n") { "  $it" })
        println()

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - startTime
            val body = response.body?.string() ?: ""

            println("📥 响应 (${elapsed}ms)")
            println("   HTTP ${response.code} ${response.message}")

            if (response.isSuccessful) {
                val json = JSONObject(body)
                val choices = json.optJSONArray("choices")
                val content = choices?.getJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "") ?: ""

                println("   ✅✅✅ 成功！回复长度: ${content.length} 字符")
                println("   🤖 ${content.take(150)}")
                println("   ✅ 测试通过")
            } else {
                // 打印完整错误
                val errorInfo = try {
                    val json = JSONObject(body)
                    val error = json.optJSONObject("error")
                    if (error != null) {
                        "code: ${error.optString("code", "N/A")}, message: ${error.optString("message", "N/A")}"
                    } else {
                        json.optString("message", body)
                    }
                } catch (_: Exception) {
                    body
                }
                println("   ❌ HTTP ${response.code} — $errorInfo")
                println()

                // HTTP 错误 = test fail
                Assert.fail("${config.name} 请求失败: HTTP ${response.code} — $errorInfo")
            }

        } catch (e: Exception) {
            println("   ❌ 异常: ${e.javaClass.simpleName}")
            println("   ${e.message}")
            println()
            Assert.fail("${config.name} 请求异常: ${e.javaClass.simpleName} — ${e.message}")
        }

        println()
    }

    /**
     * 流式测试
     */
    private fun testProviderStream(config: TestProviderConfig, modelLabel: String? = null) {
        val label = modelLabel ?: config.name
        val model = config.model

        println()
        println("╔══════════════════════════════════════════════════╗")
        println("║  📡 $label (流式)")
        println("║  ID:    ${config.id}")
        println("║  URL:   ${config.baseUrl}chat/completions")
        println("║  Model: $model")
        println("║  Type:  ${if (config.isFree) "🆓 免费" else "💳 付费"}")
        println("╚══════════════════════════════════════════════════╝")
        println()

        val apiKey = config.apiKey
        if (apiKey.isBlank()) {
            val msg = "⚠️ 跳过：${config.name} 的 API Key 为空，请在 api_keys_local.properties 中配置"
            println(msg)
            println()
            return
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个有用的AI助手，请用简洁的语言回答。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "你好，请用一句话介绍你自己。")
                })
            })
            put("temperature", 0.7)
            put("max_tokens", 200)
            put("stream", true)
        }

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - startTime

            println("📥 响应头 (${elapsed}ms)")
            println("   HTTP ${response.code} ${response.message}")

            if (response.isSuccessful) {
                val reader = response.body?.source()?.buffer()?.inputStream()?.bufferedReader()
                val fullContent = StringBuilder()
                var line: String?
                var chunks = 0

                val streamStart = System.currentTimeMillis()

                while (reader?.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("data: ")) {
                        val data = l.removePrefix("data: ").trim()
                        if (data == "[DONE]") {
                            println("   🏁 收到 [DONE] 标记")
                            break
                        }
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            val content = choices?.getJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content", "") ?: ""
                            if (content.isNotBlank()) {
                                fullContent.append(content)
                                chunks++
                            }
                        } catch (_: Exception) {
                            // 跳过非 JSON 行
                        }
                    }
                }

                val streamElapsed = System.currentTimeMillis() - streamStart
                val result = fullContent.toString()

                if (result.isNotBlank()) {
                    println()
                    println("   ✅✅✅ 流式成功！")
                    println("   📦 数据块数: $chunks")
                    println("   ⏱  流式耗时: ${streamElapsed}ms")
                    println("   📏 回复长度: ${result.length} 字符")
                    println("   🤖 $result")
                    println("   ✅ 测试通过")
                } else {
                    println("   ❌ 流式响应为空（无内容）")
                    Assert.fail("${config.name} 流式响应为空")
                }
            } else {
                val body = response.body?.string() ?: ""
                val errorInfo = try {
                    val json = JSONObject(body)
                    val error = json.optJSONObject("error")
                    if (error != null) {
                        "code: ${error.optString("code", "N/A")}, message: ${error.optString("message", "N/A")}"
                    } else {
                        json.optString("message", body)
                    }
                } catch (_: Exception) {
                    body
                }
                println("   ❌ HTTP ${response.code} — $errorInfo")
                Assert.fail("${config.name} 流式请求失败: HTTP ${response.code} — $errorInfo")
            }

        } catch (e: Exception) {
            println("   ❌ 异常: ${e.javaClass.simpleName}")
            println("   ${e.message}")
            Assert.fail("${config.name} 流式异常: ${e.javaClass.simpleName} — ${e.message}")
        }

        println()
    }
}