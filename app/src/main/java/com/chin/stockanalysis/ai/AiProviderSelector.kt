package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProviderConfig
import com.chin.stockanalysis.OpenAiCompatibleProvider
import com.chin.stockanalysis.config.AgentRoute
import com.chin.stockanalysis.config.FeatureFlagManager

/**
 * ## AI Provider 场景选择器
 *
 * 根据使用场景自动选择最适合的模型，解决「同一个 Provider 不同场景需要不同模型」的问题。
 *
 * ### 使用方式
 * ```kotlin
 * // Legacy 对话（自然语言）
 * val legacyProvider = AiProviderSelector.getProvider(context, AiScenario.CHAT_LEGACY)
 *
 * // Agent 分析（结构化 JSON）
 * val agentProvider = AiProviderSelector.getProvider(context, AiScenario.CHAT_AGENT)
 *
 * // Pipeline 专家模式（多步稳定输出）
 * val pipelineProvider = AiProviderSelector.getProvider(context, AiScenario.PIPELINE_EXPERT)
 * ```
 *
 * ### 配置原理
 * - 不修改用户在 Settings 中选择的 Provider（如「豆包大模型」）
 * - 只切换该 Provider 下的 **模型 ID**，换成最适合当前场景的
 * - 如果目标模型不可用，自动回退到用户设置的默认模型
 */
object AiProviderSelector {

    private const val TAG = "AiProviderSelector"

    /** 使用场景 */
    enum class AiScenario {
        /** Legacy 对话：自然语言输出，适合推理模型 */
        CHAT_LEGACY,
        /** Agent 分析：结构化 JSON 输出，需要强指令遵循 */
        CHAT_AGENT,
        /** Pipeline 专家模式：多步骤稳定输出 */
        PIPELINE_EXPERT,
        /** 选股/扫描：批量处理，需要速度快 */
        STOCK_PICKING,
        /** 通用/默认：不指定场景时使用 */
        DEFAULT
    }

    /**
     * 场景 → 推荐模型映射
     *
     * Key: Provider ID（如 "doubao"）
     * Value: 场景 → 模型 ID 映射
     */
    private val SCENARIO_MODEL_MAP: Map<String, Map<AiScenario, String>> = mapOf(
        "doubao" to mapOf(
            // Legacy 对话：推理模型，自然语言输出效果好
            AiScenario.CHAT_LEGACY to "doubao-seed-2-0-pro-260215",
            // Agent 结构化：1.6 支持 json_object，关闭 thinking 模式后不输出思考过程
            AiScenario.CHAT_AGENT to "doubao-seed-1-6-251015",
            // Pipeline：1.6 稳定版，同样关闭 thinking
            AiScenario.PIPELINE_EXPERT to "doubao-seed-1-6-251015",
            // 选股：Lite 速度快，成本低
            AiScenario.STOCK_PICKING to "doubao-seed-2-0-lite-260428",
            // 默认：与 Legacy 一致
            AiScenario.DEFAULT to "doubao-seed-2-0-pro-260215"
        ),
        "dashscope-qwen3" to mapOf(
            AiScenario.CHAT_LEGACY to "qwen3-235b-a22b",
            AiScenario.CHAT_AGENT to "qwen3-32b",
            AiScenario.PIPELINE_EXPERT to "qwen3-32b",
            AiScenario.STOCK_PICKING to "qwen3-8b",
            AiScenario.DEFAULT to "qwen3-235b-a22b"
        ),
        "deepseek-official" to mapOf(
            AiScenario.CHAT_LEGACY to "deepseek-reasoner",
            AiScenario.CHAT_AGENT to "deepseek-chat",
            AiScenario.PIPELINE_EXPERT to "deepseek-chat",
            AiScenario.STOCK_PICKING to "deepseek-chat",
            AiScenario.DEFAULT to "deepseek-chat"
        ),
        "siliconflow-v3-flash" to mapOf(
            AiScenario.CHAT_LEGACY to "Pro/deepseek-ai/DeepSeek-V3",
            AiScenario.CHAT_AGENT to "Pro/deepseek-ai/DeepSeek-V3",
            AiScenario.PIPELINE_EXPERT to "Pro/deepseek-ai/DeepSeek-V3",
            AiScenario.STOCK_PICKING to "Pro/deepseek-ai/DeepSeek-V3",
            AiScenario.DEFAULT to "Pro/deepseek-ai/DeepSeek-V3"
        )
    )

    /**
     * 根据场景获取最适合的 Provider 实例
     *
     * @param context   Context
     * @param scenario  使用场景
     * @return OpenAiCompatibleProvider 实例，或 null（无可用配置）
     */
    fun getProvider(context: Context, scenario: AiScenario = AiScenario.DEFAULT): OpenAiCompatibleProvider? {
        val mgr = ApiConfigManager.getInstance(context)
        val providerId = mgr.getSelectedProviderId()
        val baseConfig = mgr.getProviderConfig(providerId) ?: return null

        // 获取场景对应的模型 ID
        val scenarioModel = SCENARIO_MODEL_MAP[providerId]?.get(scenario)
            ?: baseConfig.model  // 无映射时使用用户设置的默认模型

        // 检查模型是否在支持列表中
        val validModels = mgr.getProviderModels(providerId)
        val finalModel = if (scenarioModel in validModels) {
            scenarioModel
        } else {
            Log.w(TAG, "场景模型 $scenarioModel 不在支持列表中，回退到默认模型 ${baseConfig.model}")
            baseConfig.model
        }

        // 创建带有场景模型的 Provider
        val scenarioConfig = baseConfig.copy(model = finalModel)
        Log.i(TAG, "🎯 场景=$scenario, Provider=${baseConfig.name}, 模型=$finalModel")
        return OpenAiCompatibleProvider(scenarioConfig)
    }

    /**
     * 获取场景对应的配置（不创建 Provider 实例）
     */
    fun getConfig(context: Context, scenario: AiScenario = AiScenario.DEFAULT): ApiProviderConfig? {
        val mgr = ApiConfigManager.getInstance(context)
        val providerId = mgr.getSelectedProviderId()
        val baseConfig = mgr.getProviderConfig(providerId) ?: return null

        val scenarioModel = SCENARIO_MODEL_MAP[providerId]?.get(scenario) ?: baseConfig.model
        val validModels = mgr.getProviderModels(providerId)
        val finalModel = if (scenarioModel in validModels) scenarioModel else baseConfig.model

        return baseConfig.copy(model = finalModel)
    }

    /**
     * 根据 FeatureFlag 全局模式自动推断场景
     */
    fun inferScenarioFromGlobalMode(): AiScenario {
        return when (FeatureFlagManager.chatRoute) {
            AgentRoute.LEGACY -> AiScenario.CHAT_LEGACY
            AgentRoute.AGENT_FRAMEWORK -> AiScenario.CHAT_AGENT
            else -> AiScenario.DEFAULT
        }
    }
}
