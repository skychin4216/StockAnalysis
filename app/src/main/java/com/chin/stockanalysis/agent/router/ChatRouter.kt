package com.chin.stockanalysis.agent.router

import android.content.Context
import com.chin.stockanalysis.agent.chat.ChatAgent
import com.chin.stockanalysis.agent.chat.ChatAgentResult
import com.chin.stockanalysis.config.FeatureFlagManager

/**
 * ## 對話路由層
 */
interface ChatService {
    suspend fun handleMessage(
        context: Context,
        message: String,
        onStream: ((String) -> Unit)? = null
    ): ChatAgentResult
}

/** Legacy：直接走原有 ChatTabFragment 的專家分析流程 */
class LegacyChatService : ChatService {
    override suspend fun handleMessage(
        context: Context,
        message: String,
        onStream: ((String) -> Unit)?
    ): ChatAgentResult {
        // Legacy 模式下抛出异常，由调用方自动 fallback 到原有流程
        throw UnsupportedOperationException("ChatRouter Legacy: 请使用原有 ChatTabFragment 流程")
        @Suppress("UNREACHABLE_CODE")
        return ChatAgentResult(
            success = false,
            response = "请使用原有对话流程",
            intent = "LEGACY"
        )
    }
}

/** Agent 實現 */
class AgentChatService : ChatService {
    override suspend fun handleMessage(
        context: Context,
        message: String,
        onStream: ((String) -> Unit)?
    ): ChatAgentResult {
        val agent = ChatAgent(context)
        return agent.handleMessage(message, onStream)
    }
}

object ChatRouter {
    fun getService(): ChatService {
        return when (FeatureFlagManager.resolveRoute(FeatureFlagManager.chatRoute)) {
            com.chin.stockanalysis.config.AgentRoute.AGENT_FRAMEWORK -> AgentChatService()
            else -> LegacyChatService()
        }
    }
}
