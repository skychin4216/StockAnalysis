package com.chin.stockanalysis.ai

import android.util.Log
import com.chin.stockanalysis.memory.KeyMemoryManager
import com.chin.stockanalysis.ui.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * ## 后台静默预测器
 *
 * AI 回复完成后在后台并行执行:
 * 1. 分析对话提取关键信息 → 更新 KeyMemoryManager
 * 2. 预生成追问建议（使用同一 AI，不再竞争连接）
 * 3. 预取数据到 SmartContextWindow 缓存
 *
 * ### 单 AI 模式
 * 所有后台任务都复用主对话的 Provider，在回复完成后执行，
 * 绝不在回复过程中并行调用 AI。
 */
class BackgroundPredictor(
    private val memoryManager: KeyMemoryManager,
    private val contextWindow: SmartContextWindow
) {
    companion object {
        private const val TAG = "BgPredictor"
    }

    /**
     * 对话完成后调用，并行执行记忆提取 + 追问生成。
     *
     * @param scope 协程作用域
     * @param messages 当前全部消息列表
     * @param convId 当前会话 ID
     * @param onFollowUpReady 追问建议就绪回调
     */
    fun predictAfterConversation(
        scope: CoroutineScope,
        messages: List<Message>,
        convId: String,
        onFollowUpReady: ((List<KeyMemoryManager.FollowUpSuggestion>) -> Unit)? = null
    ) {
        scope.launch {
            try {
                Log.d(TAG, "后台预测启动")

                // 并行执行：记忆提取 + 追问生成（均使用 Provider 默认值，即用户选择的同一个 AI）
                val memoryJob = async(Dispatchers.IO) {
                    memoryManager.extractAndMergeMemories(messages, convId)
                    Log.d(TAG, "✅ 后台记忆提取完成")
                }

                val followUpJob = async(Dispatchers.IO) {
                    val activeMemories = memoryManager.getAllMemories().filter { it.weight >= 0.3f }
                    memoryManager.generateFollowUpSuggestions(messages, activeMemories)
                }

                memoryJob.await()
                val suggestions = followUpJob.await()

                if (suggestions.isNotEmpty()) {
                    Log.d(TAG, "💡 后台预生成 ${suggestions.size} 条追问建议")
                    onFollowUpReady?.invoke(suggestions)
                }

                // 预取：使下一轮 SmartContextWindow 缓存预热
                val lastUserMsg = messages.lastOrNull { it.isUser && !it.isError }
                if (lastUserMsg != null) {
                    contextWindow.invalidateIfNeeded(lastUserMsg.content)
                    Log.d(TAG, "🔄 上下文缓存已预热")
                }
            } catch (e: Exception) {
                Log.e(TAG, "后台预测异常: ${e.message}", e)
            }
        }
    }
}