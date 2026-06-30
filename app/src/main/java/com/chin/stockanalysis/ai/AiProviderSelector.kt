package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProviderConfig
import com.chin.stockanalysis.OpenAiCompatibleProvider
import com.chin.stockanalysis.config.AgentRoute
import com.chin.stockanalysis.config.FeatureFlagManager

/**
 * ## AI Provider 場景選擇器
 *
 * 根據使用場景自動選擇最適合的模型，解決「同一個 Provider 不同場景需要不同模型」的問題。
 *
 * ### 使用方式
 * ```kotlin
 * // Legacy 對話（自然語言）
 * val legacyProvider = AiProviderSelector.getProvider(context, AiScenario.CHAT_LEGACY)
 *
 * // Agent 分析（結構化 JSON）
 * val agentProvider = AiProviderSelector.getProvider(context, AiScenario.CHAT_AGENT)
 *
 * // Pipeline 專家模式（多步穩定輸出）
 * val pipelineProvider = AiProviderSelector.getProvider(context, AiScenario.PIPELINE_EXPERT)
 * ```
 *
 * ### 配置原理
 * - 不修改用戶在 Settings 中選擇的 Provider（如「豆包大模型」）
 * - 只切換該 Provider 下的 **模型 ID**，換成最適合當前場景的
 * - 如果目標模型不可用，自動回退到用戶設置的默認模型
 */
object AiProviderSelector {

    private const val TAG = "AiProviderSelector"

    /** 使用場景 */
    enum class AiScenario {
        /** Legacy 對話：自然語言輸出，適合推理模型 */
        CHAT_LEGACY,
        /** Agent 分析：結構化 JSON 輸出，需要強指令遵循 */
        CHAT_AGENT,
        /** Pipeline 專家模式：多步驟穩定輸出 */
        PIPELINE_EXPERT,
        /** 選股/掃描：批量處理，需要速度快 */
        STOCK_PICKING,
        /** 通用/默認：不指定場景時使用 */
        DEFAULT
    }

    /**
     * 場景 → 推薦模型映射
     *
     * Key: Provider ID（如 "doubao"）
     * Value: 場景 → 模型 ID 映射
     */
    private val SCENARIO_MODEL_MAP: Map<String, Map<AiScenario, String>> = mapOf(
        "doubao" to mapOf(
            // Legacy 對話：推理模型，自然語言輸出效果好
            AiScenario.CHAT_LEGACY to "doubao-seed-2-0-pro-260215",
            // Agent 結構化：1.6 支持 json_object，關閉 thinking 模式後不輸出思考過程
            AiScenario.CHAT_AGENT to "doubao-seed-1-6-251015",
            // Pipeline：1.6 穩定版，同樣關閉 thinking
            AiScenario.PIPELINE_EXPERT to "doubao-seed-1-6-251015",
            // 選股：Lite 速度快，成本低
            AiScenario.STOCK_PICKING to "doubao-seed-2-0-lite-260428",
            // 默認：與 Legacy 一致
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
     * 根據場景獲取最適合的 Provider 實例
     *
     * @param context   Context
     * @param scenario  使用場景
     * @return OpenAiCompatibleProvider 實例，或 null（無可用配置）
     */
    fun getProvider(context: Context, scenario: AiScenario = AiScenario.DEFAULT): OpenAiCompatibleProvider? {
        val mgr = ApiConfigManager.getInstance(context)
        val providerId = mgr.getSelectedProviderId()
        val baseConfig = mgr.getProviderConfig(providerId) ?: return null

        // 獲取場景對應的模型 ID
        val scenarioModel = SCENARIO_MODEL_MAP[providerId]?.get(scenario)
            ?: baseConfig.model  // 無映射時使用用戶設置的默認模型

        // 檢查模型是否在支持列表中
        val validModels = mgr.getProviderModels(providerId)
        val finalModel = if (scenarioModel in validModels) {
            scenarioModel
        } else {
            Log.w(TAG, "場景模型 $scenarioModel 不在支持列表中，回退到默認模型 ${baseConfig.model}")
            baseConfig.model
        }

        // 創建帶有場景模型的 Provider
        val scenarioConfig = baseConfig.copy(model = finalModel)
        Log.i(TAG, "🎯 場景=$scenario, Provider=${baseConfig.name}, 模型=$finalModel")
        return OpenAiCompatibleProvider(scenarioConfig)
    }

    /**
     * 獲取場景對應的配置（不創建 Provider 實例）
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
     * 根據 FeatureFlag 全局模式自動推斷場景
     */
    fun inferScenarioFromGlobalMode(): AiScenario {
        return when (FeatureFlagManager.chatRoute) {
            AgentRoute.LEGACY -> AiScenario.CHAT_LEGACY
            AgentRoute.AGENT_FRAMEWORK -> AiScenario.CHAT_AGENT
            else -> AiScenario.DEFAULT
        }
    }
}
