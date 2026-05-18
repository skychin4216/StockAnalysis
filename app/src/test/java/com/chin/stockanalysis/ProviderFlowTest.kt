package com.chin.stockanalysis

import org.junit.Assert
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Provider 完整流程测试
 *
 * 使用 App 中的 OpenAiCompatibleProvider 进行全流程测试。
 * 验证从创建 Provider 配置 → 发送请求 → 收到回复 的完整链路。
 *
 * 配置统一从 TestProviders.kt 读取，API Key 统一从 api_keys_local.properties 读取。
 */
class ProviderFlowTest {

    companion object {
        private const val TAG = "ProviderFlowTest"
        // 超时时间（秒）
        private const val TIMEOUT_SECONDS = 120L
    }

    // ═══════════════════════════════════════════════════════
    //  🆓 免费模型
    // ═══════════════════════════════════════════════════════

    @Test
    fun testFreeSiliconflowV3Flash() {
        testProvider(TestProviders.FREE_PROVIDERS[0])
    }

    @Test
    fun testFreeSiliconflowV3() {
        testProvider(TestProviders.FREE_PROVIDERS[1])
    }

    @Test
    fun testFreeSiliconflowR1() {
        testProvider(TestProviders.FREE_PROVIDERS[2])
    }

    @Test
    fun testFreeSiliconflowQwen() {
        testProvider(TestProviders.FREE_PROVIDERS[3])
    }

    // ═══════════════════════════════════════════════════════
    //  💳 付费模型
    // ═══════════════════════════════════════════════════════

    @Test
    fun testPaidDoubao() {
        testProvider(TestProviders.findById("doubao")!!)
    }

    @Test
    fun testPaidDeepSeekOfficial() {
        testProvider(TestProviders.findById("deepseek-official")!!)
    }

    @Test
    fun testPaidAliyunMaas() {
        testProvider(TestProviders.findById("aliyun-maas")!!)
    }

    // ═══════════════════════════════════════════════════════
    //   核心方法
    // ═══════════════════════════════════════════════════════

    private fun testProvider(config: TestProviderConfig) {
        println()
        println("╔══════════════════════════════════════════════════╗")
        println("║  🔄 Provider 流程测试: ${config.name}")
        println("║  ID:    ${config.id}")
        println("║  Type:  ${if (config.isFree) "🆓 免费" else "💳 付费"}")
        println("╚══════════════════════════════════════════════════╝")
        println()

        val apiKey = config.apiKey
        if (apiKey.isBlank()) {
            println("⚠️ 跳过 ${config.name}：API Key 为空，请在 api_keys_local.properties 中配置")
            println()
            return
        }

        // 转换为 ApiProviderConfig（供 OpenAiCompatibleProvider 使用）
        val providerConfig = ApiProviderConfig(
            id = config.id,
            name = config.name,
            baseUrl = config.baseUrl,
            model = config.model,
            apiKey = apiKey,
            description = config.name
        )

        println("📋 配置:")
        println("   URL:     ${providerConfig.baseUrl}")
        println("   Model:   ${providerConfig.model}")
        println("   API Key: ${apiKey.take(8)}...${apiKey.takeLast(4)}")

        // 创建 Provider 实例
        val provider = OpenAiCompatibleProvider(providerConfig)

        // 发送测试消息
        val latch = CountDownLatch(1)
        val result = StringBuilder()
        val fullContent = StringBuilder()

        println()
        println("📤 发送消息: \"你好，请用一句话介绍你自己。\"")
        println("⏳ 等待流式响应...")
        println()

        val testMessages = listOf(
            Message(content = "你好，请用一句话介绍你自己。", isUser = true)
        )

        var hasError = false
        var errorMsg = ""

        val startTime = System.currentTimeMillis()

        provider.sendMessageStream(
            messages = testMessages,
            systemPrompt = "你是一个有用的AI助手，请用简洁的语言回答。",
            onSuccess = { chunk ->
                result.append(chunk)
                print(chunk)
                System.out.flush()
            },
            onComplete = { full ->
                val elapsed = System.currentTimeMillis() - startTime
                fullContent.append(full)
                println()
                println()
                println("╔══════════════════════════════════════════════════╗")
                println("║  ✅✅✅ ${config.name} 测试通过！")
                println("║  ⏱  耗时: ${elapsed}ms")
                println("║  📏 回复长度: ${full.length} 字符")
                println("╚══════════════════════════════════════════════════╝")
                println()
                println("🤖 $full")
                latch.countDown()
            },
            onError = { err ->
                val elapsed = System.currentTimeMillis() - startTime
                hasError = true
                errorMsg = err
                println()
                println()
                println("╔══════════════════════════════════════════════════╗")
                println("║  ❌❌❌ ${config.name} 测试失败")
                println("║  ⏱  耗时: ${elapsed}ms")
                println("║  ❌ 错误: $err")
                println("╚══════════════════════════════════════════════════╝")
                latch.countDown()
            }
        )

        // 等待响应（带超时）
        val completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (!completed) {
            hasError = true
            errorMsg = "超时（${TIMEOUT_SECONDS}s）"
            println()
            println("╔══════════════════════════════════════════════════╗")
            println("║  ❌❌❌ ${config.name} 超时")
            println("║  ⏱  ${TIMEOUT_SECONDS}s 内未收到完整响应")
            println("╚══════════════════════════════════════════════════╝")
        }

        if (hasError) {
            Assert.fail("${config.name} 测试失败: $errorMsg")
        }

        println()
    }
}