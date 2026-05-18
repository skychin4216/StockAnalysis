package com.chin.stockanalysis

/**
 * API 提供商配置数据类
 */
data class ApiProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String,
    val description: String,
    val isFree: Boolean = false,
    /**
     * 当前提供商支持的模型列表。
     *
     * 为空时表示仅支持 [model] 一个默认模型；设置页会使用
     * ApiConfigManager.getProviderModels() 统一转成非空列表。
     */
    val supportedModels: List<String> = emptyList(),
    /**
     * 备用模型名列表，当 [model] 不可用时依次尝试。
     * 通常 [model] 是自定义 Endpoint ID (ep-*)，fallback 是公共模型名。
     * 为空时表示不启用自动回退。
     */
    val fallbackModels: List<String> = emptyList()
)

/**
 * API 提供商接口
 * 所有 AI API 提供商都需要实现此接口
 */
interface ApiProvider {

    /** 提供商配置 */
    val config: ApiProviderConfig

    /**
     * 发送消息并流式接收回复
     *
     * @param messages 历史消息列表
     * @param systemPrompt 系统提示词
     * @param onSuccess 每收到一段内容时回调
     * @param onComplete 流式结束回调
     * @param onError 出错回调
     */
    fun sendMessageStream(
        messages: List<Message>,
        systemPrompt: String,
        onSuccess: (content: String) -> Unit,
        onComplete: (fullContent: String) -> Unit,
        onError: (errorMsg: String) -> Unit
    )
}