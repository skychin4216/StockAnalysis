package com.chin.stockanalysis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.agent.Agent
import com.chin.stockanalysis.agent.AgentPickParser
import com.chin.stockanalysis.agent.AgentPick
import com.chin.stockanalysis.databinding.FragmentAgentChatBinding
import com.chin.stockanalysis.stock.database.StockDataCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 智能体对话 Fragment
 *
 * 每个智能体拥有独立的对话上下文，
 * AI 回复后自动解析选股结果并存入 StockDataCenter.skillPicks。
 */
class AgentChatFragment : Fragment() {

    companion object {
        private const val TAG = "AgentChatFragment"
        private const val ARG_AGENT_ID = "agentId"
        private const val ARG_AGENT_NAME = "agentName"
        private const val ARG_AGENT_ICON = "agentIcon"
        private const val ARG_AGENT_QUICK_PROMPT = "agentQuickPrompt"
        private const val ARG_AGENT_SYSTEM_PROMPT = "agentSystemPrompt"

        fun newInstance(agent: Agent): AgentChatFragment {
            return AgentChatFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_AGENT_ID, agent.id)
                    putString(ARG_AGENT_NAME, agent.name)
                    putString(ARG_AGENT_ICON, agent.icon)
                    putString(ARG_AGENT_QUICK_PROMPT, agent.quickPrompt)
                    putString(ARG_AGENT_SYSTEM_PROMPT, agent.systemPrompt)
                }
            }
        }
    }

    private var _binding: FragmentAgentChatBinding? = null
    private val binding get() = _binding!!

    private var agentId: String = ""
    private var agentName: String = ""
    private var agentIcon: String = "🤖"
    private var agentQuickPrompt: String = ""
    private var agentSystemPrompt: String = ""

    private lateinit var adapter: ChatAdapter
    private val messages: MutableList<com.chin.stockanalysis.ui.Message> = mutableListOf()

    private var currentStreamingJob: Job? = null
    private var apiProvider: ApiProvider? = null
    private var aiSlot: AiProviderPool.Slot? = null

    var onBackClick: (() -> Unit)? = null
    var onSettingsClick: ((Agent) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            agentId = it.getString(ARG_AGENT_ID, "")
            agentName = it.getString(ARG_AGENT_NAME, "智能体")
            agentIcon = it.getString(ARG_AGENT_ICON, "🤖")
            agentQuickPrompt = it.getString(ARG_AGENT_QUICK_PROMPT, "")
            agentSystemPrompt = it.getString(ARG_AGENT_SYSTEM_PROMPT, "")
        }
        Log.d("AgentChat", "========== AgentChatFragment onCreate ==========")
        Log.d("AgentChat", "agentId         = $agentId")
        Log.d("AgentChat", "agentName       = $agentName")
        Log.d("AgentChat", "agentQuickPrompt= $agentQuickPrompt")
        Log.d("AgentChat", "agentSystemPrompt = $agentSystemPrompt")
        Log.d("AgentChat", "================================================")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAgentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initProvider()
        setupRecyclerView()
        setupInput()
        setupTitleBar()
        showWelcomeMessage()
    }

    override fun onPause() { super.onPause(); cancelApiCall() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
    override fun onDestroy() {
        super.onDestroy()
        cancelApiCall()
        aiSlot?.let { AiProviderPool.releaseNonBlocking(it) }
    }

    private fun cancelApiCall() { currentStreamingJob?.cancel(); currentStreamingJob = null; apiProvider?.cancel() }

    // ═══════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════

    private fun initProvider() {
        lifecycleScope.launch(Dispatchers.IO) {
            aiSlot?.let { AiProviderPool.release(it) }
            aiSlot = AiProviderPool.acquire(requireContext())
            if (isAdded) requireActivity().runOnUiThread {
                apiProvider = aiSlot?.provider
                Log.d(TAG, "Agent AI: ${aiSlot?.configName ?: "无"}")
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)
        adapter.onCopyMessage = { text ->
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("msg", text))
            Toast.makeText(requireContext(), "✅ 已复制", Toast.LENGTH_SHORT).show()
        }
        binding.rvAgentMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.rvAgentMessages.adapter = adapter
    }

    private fun setupInput() {
        binding.btnAgentSend.setOnClickListener {
            val text = binding.etAgentInput.text.toString().trim()
            if (text.isEmpty()) { Toast.makeText(requireContext(), "请输入内容", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            sendMessage(text)
        }
        binding.etAgentInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                val t = binding.etAgentInput.text.toString().trim()
                if (t.isNotEmpty()) sendMessage(t)
                true
            } else false
        }
    }

    private fun setupTitleBar() {
        binding.tvAgentChatTitle.text = "$agentIcon $agentName"
        binding.btnBack.setOnClickListener { onBackClick?.invoke() }
        // 点击标题 → 打开智能体设定编辑
        binding.tvAgentChatTitle.setOnClickListener { openSettings() }
        binding.btnAgentInfo.setOnClickListener { openSettings() }
    }

    private fun openSettings() {
        Log.d("AgentChat", "========== openSettings -> Agent ==========")
        Log.d("AgentChat", "agentQuickPrompt= $agentQuickPrompt")
        Log.d("AgentChat", "agentSystemPrompt = $agentSystemPrompt")
        Log.d("AgentChat", "============================================")
        val agt = com.chin.stockanalysis.agent.Agent(
            id = agentId,
            name = agentName,
            icon = agentIcon,
            description = "",
            quickPrompt = agentQuickPrompt,
            systemPrompt = agentSystemPrompt
        )
        onSettingsClick?.invoke(agt)
    }

    private fun showWelcomeMessage() {
        val intro = if (agentSystemPrompt.isNotBlank()) {
            "$agentIcon 我是「$agentName」智能体\n\n支持命令触发执行：\nBOM 赛道 | 选股 赛道 | 财报 股票代码 | 空报 股票代码 | 里程碑 股票代码 | 全流程 赛道/个股\n\n💡 请直接输入命令或告诉我你想分析的股票。"
        } else {
            "$agentIcon 我是「$agentName」智能体\n\n" +
            "${agentQuickPrompt.take(100)}…\n\n" +
            "💡 请告诉我你想分析的股票或问题，我会按照我的专业方法为你分析。"
        }
        addBotMessage(intro)
    }

    private fun showAgentInfo() {
        val info = buildString {
            appendLine("📋 輸入指令描述（quickPrompt）:")
            appendLine(agentQuickPrompt)
            if (agentSystemPrompt.isNotBlank()) {
                appendLine()
            appendLine("⚡ 全自动执行规则描述（systemPrompt）:")
            appendLine(agentSystemPrompt)
            }
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("$agentIcon $agentName")
            .setMessage(info.toString())
            .setPositiveButton("确定", null)
            .show()
    }

    // ═══════════════════════════════════════
    // 消息发送
    // ═══════════════════════════════════════

    private fun sendMessage(userText: String) {
        val provider = apiProvider
        if (provider == null) { addErrorMessage("❌ AI 服务未初始化"); return }

        addMessage(com.chin.stockanalysis.ui.Message(content = userText, isUser = true))
        binding.etAgentInput.setText(""); hideKeyboard()

        val streamingMessage = com.chin.stockanalysis.ui.Message(
            content = "", isUser = false, isStreaming = true,
            loadingStatus = "$agentIcon $agentName 正在分析..."
        )
        addMessage(streamingMessage)
        val streamingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            // 构建智能体专用 system prompt（全自动指令源码优先）
            val systemPrompt = if (agentSystemPrompt.isNotBlank()) {
                buildString {
                    appendLine(agentSystemPrompt)
                    appendLine()
                    appendLine("【补充指令】")
                    appendLine(agentQuickPrompt)
                    appendLine()
                    appendLine("【输出要求】")
                    appendLine("分析结束后，请明确列出你推荐的股票，格式如：")
                    appendLine("推荐股票：600519 贵州茅台 — 理由：XXX")
                    appendLine("推荐股票：sz000858 五粮液 — 理由：XXX")
                }
            } else {
                buildString {
                    appendLine(agentQuickPrompt)
                    appendLine()
                    appendLine("【重要输出要求】")
                    appendLine("1. 请按照上述方法进行系统性的选股分析")
                    appendLine("2. 分析结束后，请明确列出你推荐的股票，格式如：")
                    appendLine("   推荐股票：600519 贵州茅台 — 理由：XXX")
                    appendLine("   推荐股票：sz000858 五粮液 — 理由：XXX")
                    appendLine("3. 对每只推荐股票给出信心评级（高/中/低）")
                }
            }

            sendWithRetry(provider, systemPrompt, streamingIndex, 3)
        }
    }

    private suspend fun sendWithRetry(provider: ApiProvider, systemPrompt: String, streamingIndex: Int, maxRetries: Int, attempt: Int = 1) {
        val accumulated = StringBuilder()
        try {
            val history = messages.toList().subList(0, streamingIndex)
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                provider.sendMessageStream(
                    messages = history,
                    systemPrompt = systemPrompt,
                    onSuccess = { chunk ->
                        val sanitized = chunk.replace("null", "")
                        accumulated.append(sanitized)
                        if (isAdded) requireActivity().runOnUiThread {
                            if (streamingIndex in messages.indices && messages[streamingIndex].isStreaming) {
                                val msg = messages[streamingIndex]
                                messages[streamingIndex] = msg.copy(content = accumulated.toString(), loadingStatus = null)
                                adapter.notifyItemChanged(streamingIndex)
                                binding.rvAgentMessages.scrollToPosition(messages.size - 1)
                            }
                        }
                    },
                    onComplete = { full ->
                        val finalText = full.ifEmpty { accumulated.toString() }.replace("null", "")
                        if (isAdded) requireActivity().runOnUiThread {
                            completeStreamingMessage(streamingIndex, finalText)
                            onMessageComplete(finalText)
                        }
                        cont.resumeWith(Result.success(Unit))
                    },
                    onError = { errMsg -> cont.resumeWith(Result.failure(Exception(errMsg))) }
                )
            }
        } catch (e: Exception) {
            if (attempt < maxRetries && isAdded) {
                requireActivity().runOnUiThread { Toast.makeText(requireContext(), "⏳ 重试中... (${attempt}/$maxRetries)", Toast.LENGTH_SHORT).show() }
                kotlinx.coroutines.delay(1500L)
                sendWithRetry(provider, systemPrompt, streamingIndex, maxRetries, attempt + 1)
            } else {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(streamingIndex, "已重试 $maxRetries 次: ${e.message}")
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // 选股解析 & 存储
    // ═══════════════════════════════════════

    private fun onMessageComplete(aiResponse: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 解析 AI 回复中的选股结果
                val picks = AgentPickParser.parse(aiResponse, agentId)
                if (picks.isNotEmpty()) {
                    // 转换为 StockDataCenter.SkillPickEntry 并存储
                    val entries = picks.map { pick ->
                        StockDataCenter.SkillPickEntry(
                            rank = pick.rank,
                            stockCode = pick.stockCode,
                            stockName = pick.stockName,
                            reason = pick.reason,
                            confidence = pick.confidence,
                            sourceSkillId = pick.sourceAgentId,
                            createdAt = pick.createdAt
                        )
                    }
                    StockDataCenter.addSkillPicks(entries)

                    // 通知用户
                    if (isAdded) requireActivity().runOnUiThread {
                        val stockSummary = picks.take(5).joinToString("\n") { pick ->
                            "${pick.rank}. ${pick.stockName}(${pick.stockCode}) — 信心:${"%.0f".format(pick.confidence * 100)}%"
                        }
                        addBotMessage("📌 智能体「$agentName」已选出 ${picks.size} 只股票并存入精选池:\n$stockSummary\n\n这些股票将在策略和模拟交易中优先考虑。")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "选股解析失败: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════
    // 消息 UI 辅助
    // ═══════════════════════════════════════

    private fun completeStreamingMessage(index: Int, content: String) {
        if (index in messages.indices) {
            messages[index] = messages[index].copy(content = content, isStreaming = false, loadingStatus = null)
            adapter.notifyItemChanged(index)
        }
    }

    private fun failStreamingMessage(index: Int, errMsg: String) {
        if (index in messages.indices) {
            messages[index] = com.chin.stockanalysis.ui.Message(content = "❌ $errMsg", isUser = false, isError = true, errorMessage = errMsg)
            adapter.notifyItemChanged(index)
        }
    }

    private fun addMessage(message: com.chin.stockanalysis.ui.Message) {
        messages.add(message)
        adapter.notifyItemInserted(messages.size - 1)
        binding.rvAgentMessages.scrollToPosition(messages.size - 1)
    }

    private fun addBotMessage(text: String) = addMessage(com.chin.stockanalysis.ui.Message(content = text, isUser = false))
    private fun addErrorMessage(text: String) = addMessage(com.chin.stockanalysis.ui.Message(content = text, isUser = false, isError = true))

    private fun hideKeyboard() {
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.etAgentInput.windowToken, 0)
    }
}