package com.chin.stockanalysis

import android.content.Context
import android.content.SharedPreferences

/**
 * API 配置管理器
 *
 * 管理多个 AI API 提供商的配置，支持：
 * - 预置提供商列表（涵盖国内主流免费/付费 AI API）
 * - API Key 优先级链：用户设置 > 本地配置文件 > 空字符串
 * - 当前选中的提供商持久化
 * - SharedPreferences 存储用户配置
 *
 * 新增提供商只需在 builtInProviders 列表中添加一项即可。
 */
class ApiConfigManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "api_config"
        private const val KEY_SELECTED_PROVIDER = "selected_provider"
        private const val KEY_SELECTED_MODEL_PREFIX = "selected_model_"
        private const val KEY_USER_API_KEY_PREFIX = "user_api_key_"
        private const val KEY_USER_CUSTOM_MODELS_PREFIX = "user_custom_models_"

        @Volatile
        private var instance: ApiConfigManager? = null

        fun getInstance(context: Context): ApiConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ApiConfigManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ApiKeysLoader.init(context)
    }

    /**
     * 预置的 API 提供商列表
     *
     * 涵盖国内主流免费/付费 AI API，全部兼容 OpenAI Chat Completions 格式。
     *
     * 📌 API Key 获取优先级：
     *    1. 用户在设置页填写的 Key（SharedPreferences）
     *    2. api_keys_local.properties 文件中的 Key（本地开发配置，已 .gitignore）
     *    3. 空字符串（无 Key，需用户输入）
     *
     * 📌 如何添加新提供商？
     *    只需在下面的列表中添加一项 ApiProviderConfig 即可。
     *    不需要修改任何其他代码！
     */
    /** 获取所有预置 Provider 配置（含运行时注入的 Key） */
    fun getAllConfigs(): List<ApiProviderConfig> {
        return builtInProviders.map { config ->
            val key = getUserApiKey(config.id)
            if (key?.isNotBlank() == true) config.copy(apiKey = key!!) else config
        }
    }

    val builtInProviders: List<ApiProviderConfig> = listOf(
        // ═══════════════════════════════════════════════════════════════
        // ⭐ 優先級 1：高品質付費模型（豆包、千問）
        // ═══════════════════════════════════════════════════════════════

        // 火山引擎 - 豆包大模型（字节跳动官方，付费，推荐使用）
        // 优先使用自定义 Endpoint ID，不可用时自动回退到公共模型
        // 💡 速度提示：doubao-1.5-lite-32k 最快 / doubao-1.5-pro-32k 质量好
        ApiProviderConfig(
            id = "doubao",
            name = "🔥 豆包大模型 (付费)",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3/",
            model = "doubao-seed-2-0-pro-260215",
            description = "火山引擎 Ark 平台。默认使用最强 Seed 2.0 Pro",
            isFree = false,
            supportedModels = listOf(
                // ⭐ Seed 2.0 系列（按能力强弱排序）
                "doubao-seed-2-0-pro-260215",        // 🔥 Seed 2.0 Pro，旗舰最强 ⭐默认
                "doubao-seed-2-0-code-preview-260215",// Seed 2.0 Code，代码生成
                "doubao-seed-2-0-mini-260428",        // Seed 2.0 Mini，轻量
                "doubao-seed-2-0-mini-260215",        // Seed 2.0 Mini (旧版)
                "doubao-seed-2-0-lite-260428",        // Seed 2.0 Lite，速度最快
                // Seed 1.x 系列
                "doubao-seed-1-8-251228",             // Seed 1.8
                "doubao-seed-1-6-251015",             // Seed 1.6
                "doubao-seed-1-6-flash-250828",       // Seed 1.6 Flash
                "doubao-seed-1-6-vision-250815",      // Seed 1.6 Vision 多模态
                "doubao-seed-code-preview-251028",    // Seed Code Preview (旧)
                // 经典 1.5
                "doubao-1-5-pro-32k-250115",          // 1.5 Pro 32k
                // 自定义接入点 ID（按需启用）
                "ep-20260515024618-chcvf"
            ),
            fallbackModels = listOf(
                "doubao-seed-2-0-code-preview-260215",
                "doubao-seed-2-0-mini-260428",
                "doubao-1-5-pro-32k-250115"
            )
        ),

        // 阿里云百炼 - Qwen3 全系列（2025年5月最新发布，推荐使用）
        ApiProviderConfig(
            id = "dashscope-qwen3",
            name = "⭐ 阿里云 Qwen3 (付费)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/",
            model = "qwen3-235b-a22b",
            description = "Qwen3 最新旗舰系列，支持 235B MoE 超大模型和 32B/14B/8B 多个尺寸，thinking 模式可切换",
            isFree = false,
            supportedModels = listOf(
                // MoE 系列（参数量大，性价比高）
                "qwen3-235b-a22b",     // 最强 MoE，旗舰 ⭐
                "qwen3-30b-a3b",       // 轻量 MoE，速度快
                // Dense 系列（稳定，适合生产）
                "qwen3-32b",           // 最强 Dense ⭐推荐
                "qwen3-14b",           // 均衡
                "qwen3-8b",            // 轻量
                "qwen3-4b",            // 极速
                // 向后兼容：Qwen2.5 系列
                "qwen-max",
                "qwen-plus",
                "qwen-turbo"
            ),
            fallbackModels = listOf("qwen3-32b", "qwen-plus")
        ),

        // ═══════════════════════════════════════════════════════════════
        // 優先級 2：免費備用（矽基流動等）
        // ═══════════════════════════════════════════════════════════════

        // ⭐⭐⭐ 强烈推荐：硅基流动 - DeepSeek V3 Flash（免费，最新最快）
        ApiProviderConfig(
            id = "siliconflow-v3-flash",
            name = "硅基流动 V3 Flash (免费)",
            baseUrl = "https://api.siliconflow.cn/v1/",
            model = "Pro/deepseek-ai/DeepSeek-V3",
            description = "DeepSeek-V3 Flash 版本，免费备用",
            isFree = true,
            supportedModels = listOf(
                "Pro/deepseek-ai/DeepSeek-V3",
                "deepseek-ai/DeepSeek-V3",
                "deepseek-ai/DeepSeek-R1",
                "Qwen/Qwen2.5-72B-Instruct-128K",
                "meta-llama/Llama-3.3-70B-Instruct"
            )
        ),

        // DeepSeek 官方（付费，新用户送 500万 tokens）
        ApiProviderConfig(
            id = "deepseek-official",
            name = "DeepSeek 官方 (付费)",
            baseUrl = "https://api.deepseek.com/v1/",
            model = "deepseek-chat",
            description = "DeepSeek 官方接口，新用户送 500万 tokens，后续按量计费。如遇 402 余额不足需充值",
            isFree = false,
            supportedModels = listOf("deepseek-chat", "deepseek-reasoner")
        ),

        // 阿里云百炼 - Qwen3 全系列（2025年5月最新发布，推荐使用）
        ApiProviderConfig(
            id = "dashscope-qwen3",
            name = "⭐ 阿里云 Qwen3 (付费)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/",
            model = "qwen3-235b-a22b",
            description = "Qwen3 最新旗舰系列，支持 235B MoE 超大模型和 32B/14B/8B 多个尺寸，thinking 模式可切换",
            isFree = false,
            supportedModels = listOf(
                // MoE 系列（参数量大，性价比高）
                "qwen3-235b-a22b",     // 最强 MoE，旗舰 ⭐
                "qwen3-30b-a3b",       // 轻量 MoE，速度快
                // Dense 系列（稳定，适合生产）
                "qwen3-32b",           // 最强 Dense ⭐推荐
                "qwen3-14b",           // 均衡
                "qwen3-8b",            // 轻量
                "qwen3-4b",            // 极速
                // 向后兼容：Qwen2.5 系列
                "qwen-max",
                "qwen-plus",
                "qwen-turbo"
            ),
            fallbackModels = listOf("qwen3-32b", "qwen-plus")
        ),

        // 阿里云百炼 - 通义千问经典系列（付费，有免费额度）
        ApiProviderConfig(
            id = "dashscope",
            name = "阿里云百炼 Qwen2.5 (付费)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/",
            model = "qwen-plus",
            description = "阿里云百炼平台，通义千问 Qwen2.5 系列，有免费额度",
            isFree = false,
            supportedModels = listOf("qwen-plus", "qwen-turbo", "qwen-max", "qwen-max-latest", "deepseek-r1")
        ),

        // 阿里云 MaaS (Model as a Service) - 用户专属实例
        // 使用阿里云灵积模型服务（DashScope）的专属推理实例
        // ✅ 使用 /compatible-mode/v1 兼容 OpenAI 格式
        ApiProviderConfig(
            id = "aliyun-maas",
            name = "阿里云 MaaS (用户实例)",
            baseUrl = "https://llm-kowojoaryb0hq5ik.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/",
            model = "qwen-max",
            description = "阿里云 MaaS 用户专属实例（实例ID: 5144680），请在 api_keys_local.properties 中配置 ALIYUN_MAAS_KEY",
            isFree = false,
            supportedModels = listOf(
                "qwen-max",
                "qwen-plus",
                "qwen-turbo",
                "deepseek-r1",
                "deepseek-v3"
            )
        ),

        // 百度千帆 - ERNIE-4.0（付费，有免费额度）
        ApiProviderConfig(
            id = "baidu-qianfan",
            name = "百度千帆 ERNIE (付费)",
            baseUrl = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/",
            model = "ernie-4.0-8k",
            description = "百度文心一言 ERNIE 4.0，有免费额度",
            isFree = false
        ),

        // 智谱 AI - GLM-4（付费，有免费额度）
        ApiProviderConfig(
            id = "zhipu",
            name = "智谱 GLM-4 (付费)",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/",
            model = "glm-4",
            description = "智谱 AI GLM-4 模型，注册送免费额度",
            isFree = false
        ),

        // 讯飞星火 - Spark 4.0（付费，有免费额度）
        ApiProviderConfig(
            id = "iflytek",
            name = "讯飞星火 Spark (付费)",
            baseUrl = "https://spark-api-open.xf-yun.com/v1/",
            model = "generalv4",
            description = "讯飞星火大模型 V4.0，注册送免费体验额度",
            isFree = false
        ),

        // OpenAI - GPT-4o-mini（付费，性价比高）
        ApiProviderConfig(
            id = "openai",
            name = "OpenAI GPT (付费)",
            baseUrl = "https://api.openai.com/v1/",
            model = "gpt-4o-mini",
            description = "OpenAI GPT-4o-mini，性价比高，需要海外支付方式",
            isFree = false,
            supportedModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini")
        )
    )

    /**
     * 获取当前选中的提供商 ID
     * 默认使用第一个（硅基流动 V3，免费），避免用户遇到"账户不足"错误
     */
    fun getSelectedProviderId(): String {
        return prefs.getString(KEY_SELECTED_PROVIDER, builtInProviders[0].id) ?: builtInProviders[0].id
    }

    /**
     * 保存当前选中的提供商 ID
     */
    fun setSelectedProviderId(providerId: String) {
        prefs.edit().putString(KEY_SELECTED_PROVIDER, providerId).apply()
    }

    /**
     * 获取指定 ID 的提供商配置。
     *
     * API Key 优先级（三重回退）：
     * 1. 用户在设置中手动填写的 Key（SharedPreferences）- 最高优先级
     * 2. 本地配置文件 api_keys_local.properties 中的 Key - 次高优先级
     * 3. 空字符串（无 Key）
     */
    fun getProviderConfig(providerId: String): ApiProviderConfig? {
        val builtIn = builtInProviders.find { it.id == providerId } ?: return null
        val selectedModel = getSelectedModel(providerId)
        // 优先级1: 用户设置
        val userKey = getUserApiKey(providerId)
        // 优先级2: 本地配置文件
        val localKey = if (userKey == null) {
            try {
                com.chin.stockanalysis.ApiKeysLoader.get(getLocalKeyName(providerId))
            } catch (_: Exception) { "" }
        } else null
        val effectiveKey = userKey ?: localKey ?: ""
        return builtIn.copy(
            apiKey = effectiveKey,
            model = selectedModel
        )
    }

    /**
     * 获取当前选中的提供商配置
     */
    fun getCurrentProviderConfig(): ApiProviderConfig? {
        return getProviderConfig(getSelectedProviderId())
    }

    /**
     * 创建当前选中的 API 提供商实例
     */
    fun createCurrentProvider(): ApiProvider? {
        val config = getCurrentProviderConfig() ?: return null
        return OpenAiCompatibleProvider(config)
    }

    /** 保存用户自己的 API Key。为空时清除本地自定义 Key。 */
    fun saveUserApiKey(providerId: String, apiKey: String) {
        val normalizedKey = apiKey.trim()
        val editor = prefs.edit()
        if (normalizedKey.isBlank()) {
            editor.remove("$KEY_USER_API_KEY_PREFIX$providerId")
        } else {
            editor.putString("$KEY_USER_API_KEY_PREFIX$providerId", normalizedKey)
        }
        editor.apply()
    }

    /** 获取用户自己的 API Key；返回 null 表示无用户自定义 Key。 */
    fun getUserApiKey(providerId: String): String? {
        return prefs.getString("$KEY_USER_API_KEY_PREFIX$providerId", null)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 获取提供商支持的模型列表（内置 + 用户自定义），保证非空。
     *  自动过滤已知无效的旧模型 ID。 */
    fun getProviderModels(providerId: String): List<String> {
        val config = builtInProviders.find { it.id == providerId } ?: return emptyList()
        val builtIn = config.supportedModels.ifEmpty { listOf(config.model) }
        val custom = getUserCustomModels(providerId)
        val allModels = (custom + builtIn).distinct()
        // 过滤已知无效的旧模型
        return allModels.filter { it !in KNOWN_INVALID_MODELS }
    }

    /** 已知无效的模型 ID 列表（会在 getProviderModels() 中自动过滤） */
    private val KNOWN_INVALID_MODELS = emptySet<String>()

    /** 获取当前提供商选中的模型；未配置时返回默认模型。
     *  如果持久化的模型已不在有效列表中，自动清除并回退到默认模型。 */
    fun getSelectedModel(providerId: String): String {
        val models = getProviderModels(providerId)
        val defaultModel = models.firstOrNull()
            ?: builtInProviders.find { it.id == providerId }?.model.orEmpty()
        val saved = prefs.getString("$KEY_SELECTED_MODEL_PREFIX$providerId", defaultModel) ?: defaultModel
        return if (saved in models) {
            saved
        } else {
            // 已持久化的模型不在有效列表中 → 清除并回退
            setSelectedModel(providerId, defaultModel)
            defaultModel
        }
    }

    /** 保存当前提供商选中的模型。 */
    fun setSelectedModel(providerId: String, model: String) {
        prefs.edit().putString("$KEY_SELECTED_MODEL_PREFIX$providerId", model).apply()
    }

    // ═══════════════════════════════════════════════════════════════
    // 用户自定义模型（持久化到 SharedPreferences，data 分区）
    // ═══════════════════════════════════════════════════════════════

    /** 获取用户为指定提供商添加的自定义模型列表。 */
    fun getUserCustomModels(providerId: String): List<String> {
        val raw = prefs.getString("$KEY_USER_CUSTOM_MODELS_PREFIX$providerId", null) ?: return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /** 为用户指定提供商添加一个自定义模型（去重）。 */
    fun addUserCustomModel(providerId: String, newModel: String) {
        val normalized = newModel.trim()
        if (normalized.isBlank()) return
        val existing = getUserCustomModels(providerId).toMutableList()
        if (normalized !in existing) {
            existing.add(normalized)
            val serialized = existing.joinToString(",")
            prefs.edit().putString("$KEY_USER_CUSTOM_MODELS_PREFIX$providerId", serialized).apply()
        }
    }

    /** 删除用户指定提供商的某个自定义模型。 */
    fun removeUserCustomModel(providerId: String, modelToRemove: String) {
        val existing = getUserCustomModels(providerId).toMutableList()
        existing.removeAll { it == modelToRemove.trim() }
        val serialized = existing.joinToString(",")
        if (serialized.isBlank()) {
            prefs.edit().remove("$KEY_USER_CUSTOM_MODELS_PREFIX$providerId").apply()
        } else {
            prefs.edit().putString("$KEY_USER_CUSTOM_MODELS_PREFIX$providerId", serialized).apply()
        }
    }

    /**
     * 根据提供商 ID 获取本地配置文件中的 Key 名称
     */
    private fun getLocalKeyName(providerId: String): String {
        return when (providerId) {
            "siliconflow-v3-flash", "siliconflow-v3", "siliconflow-r1",
            "siliconflow-qwen", "siliconflow-llama", "siliconflow-v25" ->
                ApiKeysLoader.KEY_SILICONFLOW
            "doubao" -> ApiKeysLoader.KEY_DOUBAO
            "deepseek-official" -> ApiKeysLoader.KEY_DEEPSEEK
            // 阿里云 DashScope 统一使用 ALIYUN_MAAS_KEY
            "aliyun-maas", "dashscope", "dashscope-qwen3" -> ApiKeysLoader.KEY_ALIYUN_MAAS
            else -> "${providerId.uppercase()}_KEY"
        }
    }
}