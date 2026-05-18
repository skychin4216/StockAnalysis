package com.chin.stockanalysis

/**
 * 测试用 API 提供商配置
 *
 * 所有测试文件（ModelDirectTest、ProviderFlowTest 等）统一从此文件读取配置，
 * 避免重复定义和散落的硬编码。
 *
 * 新增测试只需在此添加一项配置即可。
 *
 * @property id 配置 ID，与 ApiConfigManager.builtInProviders 中的 id 保持一致
 * @property name 显示名称
 * @property baseUrl API 基础 URL
 * @property model 默认模型名
 * @property isFree 是否免费（免费模型无需充值）
 */
data class TestProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val isFree: Boolean = false,
    val supportedModels: List<String> = listOf(model)
) {
    /** 从 api_keys_local.properties 读取对应的 API Key */
    val apiKey: String get() = ApiKeysLoader.get(keyName)

    /** 配置文件中对应的 Key 名称 */
    private val keyName: String get() = when {
        id.startsWith("siliconflow") -> ApiKeysLoader.KEY_SILICONFLOW
        id == "doubao" -> ApiKeysLoader.KEY_DOUBAO
        id == "deepseek-official" -> ApiKeysLoader.KEY_DEEPSEEK
        id == "aliyun-maas" -> ApiKeysLoader.KEY_ALIYUN_MAAS
        else -> "${id.uppercase()}_KEY"
    }
}

/**
 * 所有测试可用的 API 提供商配置列表
 */
object TestProviders {

    // ═══════════════════════════════════════════════════════════════
    // 🆓 免费模型（无需充值，可直接测试）
    // ═══════════════════════════════════════════════════════════════

    val FREE_PROVIDERS = listOf(
        TestProviderConfig(
            id = "siliconflow-v3-flash",
            name = "硅基流动 V3 Flash",
            baseUrl = "https://api.siliconflow.cn/v1/",
            model = "Pro/deepseek-ai/DeepSeek-V3",
            isFree = true,
            supportedModels = listOf(
                "Pro/deepseek-ai/DeepSeek-V3",
                "deepseek-ai/DeepSeek-V3",
                "deepseek-ai/DeepSeek-R1",
                "Qwen/Qwen2.5-72B-Instruct-128K"
            )
        ),
        TestProviderConfig(
            id = "siliconflow-v3",
            name = "硅基流动 V3",
            baseUrl = "https://api.siliconflow.cn/v1/",
            model = "deepseek-ai/DeepSeek-V3",
            isFree = true
        ),
        TestProviderConfig(
            id = "siliconflow-r1",
            name = "硅基流动 R1",
            baseUrl = "https://api.siliconflow.cn/v1/",
            model = "deepseek-ai/DeepSeek-R1",
            isFree = true
        ),
        TestProviderConfig(
            id = "siliconflow-qwen",
            name = "硅基流动 Qwen",
            baseUrl = "https://api.siliconflow.cn/v1/",
            model = "Qwen/Qwen2.5-72B-Instruct-128K",
            isFree = true
        )
    )

    // ═══════════════════════════════════════════════════════════════
    // 💳 付费模型（需要余额/充值，按需测试）
    // ═══════════════════════════════════════════════════════════════

    val PAID_PROVIDERS = listOf(
        TestProviderConfig(
            id = "doubao",
            name = "豆包 Ark (旧端点)",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3/",
            model = "ep-20260515024618-chcvf",
            isFree = false
        ),
        // 豆包 Responses API（新格式，模型不同）✅ 已验证通过
        TestProviderConfig(
            id = "doubao-responses",
            name = "豆包 Responses API",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3/",
            model = "doubao-seed-2-0-mini-260215",
            isFree = false
        ),
        TestProviderConfig(
            id = "deepseek-official",
            name = "DeepSeek 官方",
            baseUrl = "https://api.deepseek.com/v1/",
            model = "deepseek-chat",
            isFree = false,
            supportedModels = listOf("deepseek-chat", "deepseek-reasoner")
        ),
        TestProviderConfig(
            id = "aliyun-maas",
            name = "阿里云 MaaS (用户实例)",
            baseUrl = "https://llm-kowojoaryb0hq5ik.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/",
            model = "qwen-max",
            isFree = false,
            supportedModels = listOf("qwen-max", "qwen-plus", "qwen-turbo", "deepseek-r1")
        )
    )

    // ═══════════════════════════════════════════════════════════════
    // 阿里云 MaaS 备选端点（用于测试不同 API 格式）
    // ═══════════════════════════════════════════════════════════════

    val ALIYUN_MAAS_DASHSCOPE = TestProviderConfig(
        id = "aliyun-maas-dashscope",
        name = "阿里云 MaaS (DashScope原生)",
        baseUrl = "https://llm-kowojoaryb0hq5ik.cn-beijing.maas.aliyuncs.com/api/v1/",
        model = "qwen-max",
        isFree = false
    )

    /** 所有提供商（免费 + 付费） */
    val ALL_PROVIDERS: List<TestProviderConfig> = FREE_PROVIDERS + PAID_PROVIDERS

    /** 按 ID 查找 */
    fun findById(id: String): TestProviderConfig? = ALL_PROVIDERS.find { it.id == id }
}