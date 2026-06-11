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
import com.chin.stockanalysis.conversation.ConversationRepository
import com.chin.stockanalysis.databinding.FragmentAgentChatBinding
import com.chin.stockanalysis.ai.StockAIPromptBuilder
import com.chin.stockanalysis.stock.StockQueryEngine
import com.chin.stockanalysis.stock.database.StockDataCenter
import com.chin.stockanalysis.skill.SkillEngine
import com.chin.stockanalysis.skill.SkillOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentChatFragment : Fragment() {

    companion object {
        private const val TAG = "AgentChatFragment"
        private const val ARG_AGENT_ID = "agentId"
        private const val ARG_AGENT_NAME = "agentName"
        private const val ARG_AGENT_ICON = "agentIcon"
        private const val ARG_AGENT_QUICK_PROMPT = "agentQuickPrompt"
        private const val ARG_AGENT_SYSTEM_PROMPT = "agentSystemPrompt"
        private const val PREFS_AGENT_CHAT = "agent_chats"

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
    private var providerInitDone = false
    private var providerLoading = false

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
        loadAndRestoreConversation()
    }

    override fun onPause() { super.onPause(); saveAgentConversation() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
    override fun onDestroy() {
        super.onDestroy()
        cancelApiCall()
        saveAgentConversation()
        aiSlot?.let { AiProviderPool.releaseNonBlocking(it) }
    }

    private fun cancelApiCall() { currentStreamingJob?.cancel(); currentStreamingJob = null; apiProvider?.cancel() }

    private fun initProvider() {
        if (providerInitDone || providerLoading) return
        providerLoading = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                aiSlot = AiProviderPool.acquire(requireContext())
                if (isAdded) requireActivity().runOnUiThread {
                    apiProvider = aiSlot?.provider
                    if (apiProvider != null) providerInitDone = true
                    Log.d(TAG, "Agent AI: ${aiSlot?.configName ?: "无"}")
                }
            } finally {
                providerLoading = false
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
        adapter.onPlayVoice = { text -> Toast.makeText(requireContext(), "🔊 语音播放: ${text.take(30)}...", Toast.LENGTH_SHORT).show() }
        adapter.onFavorite = { text -> Toast.makeText(requireContext(), "⭐ 已收藏", Toast.LENGTH_SHORT).show() }
        adapter.onShare = { text ->
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text)
                }, "分享到"))
        }
        adapter.onRegenerate = { position -> regenerateMessage(position) }
        binding.rvAgentMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.rvAgentMessages.adapter = adapter
    }

    /** 重新生成某个 AI 回复 */
    private fun regenerateMessage(botPosition: Int) {
        if (botPosition <= 0 || botPosition >= messages.size) return
        val userMsg = (botPosition - 1 downTo 0).firstNotNullOfOrNull {
            messages[it].takeIf { m -> m.isUser }
        } ?: return
        val removeCount = messages.size - botPosition
        repeat(removeCount) { messages.removeAt(botPosition) }
        adapter.notifyItemRangeRemoved(botPosition, removeCount)
        adapter.notifyItemRangeChanged(0, messages.size)
        sendMessage(userMsg.content)
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
        binding.tvAgentChatTitle.setOnClickListener { openSettings() }
        binding.btnAgentInfo.setOnClickListener { openSettings() }
    }

    private fun openSettings() {
        val agt = com.chin.stockanalysis.agent.Agent(
            id = agentId, name = agentName, icon = agentIcon,
            description = "", quickPrompt = agentQuickPrompt, systemPrompt = agentSystemPrompt
        )
        onSettingsClick?.invoke(agt)
    }

    private fun saveAgentConversation() {
        if (agentId.isEmpty()) return
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_AGENT_CHAT, Context.MODE_PRIVATE)
            val json = ConversationRepository.serializeMessages(messages)
            prefs.edit().putString("chat_$agentId", json).apply()
        } catch (e: Exception) { Log.w(TAG, "保存聊天记录失败: ${e.message}") }
    }

    private fun loadAndRestoreConversation() {
        val prefs = requireContext().getSharedPreferences(PREFS_AGENT_CHAT, Context.MODE_PRIVATE)
        val json = prefs.getString("chat_$agentId", null)
        if (json.isNullOrBlank()) {
            showWelcomeMessage()
            return
        }
        try {
            val restored = ConversationRepository.deserializeMessages(json)
            if (restored.isNotEmpty()) {
                messages.clear()
                messages.addAll(restored)
                adapter.notifyDataSetChanged()
                binding.rvAgentMessages.scrollToPosition(messages.size - 1)
                return
            }
        } catch (e: Exception) { Log.w(TAG, "加载聊天记录失败: ${e.message}") }
        showWelcomeMessage()
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

    private fun sendMessage(userText: String) {
        val provider = apiProvider
        if (provider == null) {
            // Provider 尚未就绪，等待初始化完成
            addMessage(com.chin.stockanalysis.ui.Message(content = userText, isUser = true))
            currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
                addBotMessage("⏳ AI 正在连接...")
                var waited = 0
                while (apiProvider == null && waited < 50) {
                    kotlinx.coroutines.delay(200L)
                    waited++
                }
                // 移除"连接中"消息
                if (messages.isNotEmpty() && !messages.last().isUser) {
                    messages.removeLast()
                    adapter.notifyItemRemoved(messages.size)
                }
                if (apiProvider != null) {
                    doSendMessage(userText, apiProvider!!)
                } else {
                    addErrorMessage("❌ AI 连接超时，请稍候重试")
                }
            }
            return
        }

        doSendMessage(userText, provider)
    }

    private val skillEngine: SkillEngine by lazy { SkillEngine(requireContext()) }
    private val skillOrchestrator: SkillOrchestrator by lazy { SkillOrchestrator(skillEngine) }
    private val queryEngine: StockQueryEngine by lazy { StockQueryEngine.create(requireContext(), skillOrchestrator) }
    private val agentManager: com.chin.stockanalysis.agent.AgentManager by lazy {
        com.chin.stockanalysis.agent.AgentManager(requireContext())
    }

    /**
     * 模糊匹配智能体触发关键词
     *
     * 从三个维度收集候选词进行匹配：
     * 1. agent name 中拆分出的有意义的词（如 "隐形冠军BOM选股" → ["隐形冠军", "BOM"]）
     * 2. skills_config 中定义的 keywords
     * 3. quickPrompt 中的触发命令（BOM、选股、财报、空报...）
     *
     * 用户只需输入包含这些词的模糊描述即可触发，如：
     *   "隐形冠军选股" → 匹配 "隐形冠军BOM选股"
     *   "BOM选股技巧" → 匹配 BOM + 选股
     *   "卡脖子技巧"   → 匹配 "卡脖子四阶选股"
     */
    private fun matchesAgentTrigger(userText: String): Boolean {
        if (agentName.isBlank()) return false
        val agent = agentManager.get(agentId) ?: return false

        // 1. 从 agent name 中提取中文短语和英文缩写
        //    "隐形冠军BOM选股" → ["隐形冠军", "BOM"]
        val nameTokens = agentName
            .split(Regex("[\\s\\-_,.，。、；;：:（）()]+"))
            .flatMap { segment ->
                Regex("[\u4e00-\u9fff]+|[A-Za-z0-9]+")
                    .findAll(segment).map { it.value }.toList()
            }
            .filter { it.length in 2..12 && it !in listOf("选股", "技巧", "选股技巧") }
            .toSet()

        // 2. 从 skills_config keywords 获取关键词
        val keywordTokens = agent.triggerKeywords.toSet()

        // 3. 从 quickPrompt 提取每行的第一个命令词
        val commandTriggers = if (agentQuickPrompt.isNotBlank()) {
            agentQuickPrompt.lines()
                .mapNotNull { it.trim().split(Regex("[\\s　]+")).firstOrNull() }
                .filter { it.isNotBlank() && it.length >= 2 }
                .toSet()
        } else emptySet()

        val allTriggers = nameTokens + keywordTokens + commandTriggers
        if (allTriggers.isEmpty()) return false

        val matched = allTriggers.any { trigger ->
            userText.contains(trigger, ignoreCase = true)
        }
        Log.d(TAG, "Agent trigger: nameTokens=$nameTokens, keywords=${keywordTokens.take(4)}, commands=${commandTriggers.take(4)}, matched=$matched, input='${userText.take(40)}'")
        return matched
    }

    private fun doSendMessage(userText: String, provider: ApiProvider) {
        addMessage(com.chin.stockanalysis.ui.Message(content = userText, isUser = true))
        binding.etAgentInput.setText(""); hideKeyboard()

        val streamingMessage = com.chin.stockanalysis.ui.Message(
            content = "", isUser = false, isStreaming = true,
            loadingStatus = "$agentIcon $agentName 正在分析..."
        )
        addMessage(streamingMessage)
        val streamingIndex = messages.size - 1

        // 检测是否触发了智能体的选股命令
        val isSkillTriggered = matchesAgentTrigger(userText)

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            // ═══ 获取实时行情数据并注入 prompt ═══
            val stockDataDeferred = async(Dispatchers.IO) {
                try {
                    queryEngine.buildSystemPrompt(userText = userText, baseSystemPrompt = "")
                } catch (e: Exception) {
                    Log.w(TAG, "获取实时行情数据失败: ${e.message}")
                    ""
                }
            }

            // ✅ 基础分析 prompt：始终注入，确保 AI 能正确使用实时行情数据
            val basePrompt = StockAIPromptBuilder.buildBaseSystemPrompt()

            val prompt: String = if (isSkillTriggered) {
                // ✅ 触发关键词匹配 → 基础分析 prompt + 智能体的完整选股框架
                val skillPart = buildString {
                    if (agentSystemPrompt.isNotBlank()) {
                        appendLine(agentSystemPrompt)
                        appendLine()
                        appendLine("【补充指令】")
                        appendLine(agentQuickPrompt)
                    } else {
                        appendLine(agentQuickPrompt)
                    }
                    appendLine()
                    appendLine("【输出要求】")
                    appendLine("分析结束后，请明确列出你推荐的股票，格式如：")
                    appendLine("推荐股票：600519 贵州茅台 — 理由：XXX")
                    appendLine("推荐股票：sz000858 五粮液 — 理由：XXX")
                }
                Log.d(TAG, "🎯 Agent skill triggered → base analysis + agent skill prompt")
                // 基础 prompt 在前，agent skill 在后（实时数据规则优先）
                "$basePrompt\n\n---\n\n$skillPart"
            } else {
                Log.d(TAG, "📊 General stock query → base analysis prompt only")
                basePrompt
            }

            val stockData = stockDataDeferred.await()
            val finalSystemPrompt = if (stockData.isNotBlank()) {
                "$stockData\n\n$prompt"
            } else {
                prompt
            }
            Log.d(TAG, "System prompt length: ${finalSystemPrompt.length}, stockData: ${stockData.isNotBlank()}, skillTriggered=$isSkillTriggered")

            sendWithRetry(provider, finalSystemPrompt, streamingIndex, 3)
        }
    }

    private suspend fun sendWithRetry(provider: ApiProvider, systemPrompt: String, streamingIndex: Int, maxRetries: Int, attempt: Int = 1) {
        val accumulated = StringBuilder()
        try {
            val history = messages.toList().subList(0, streamingIndex)
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                provider.sendMessageStream(
                    messages = history, systemPrompt = systemPrompt,
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

    private fun onMessageComplete(aiResponse: String) {
        saveAgentConversation()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val picks = AgentPickParser.parse(aiResponse, agentId)
                if (picks.isNotEmpty()) {
                    val entries = picks.map { pick ->
                        StockDataCenter.SkillPickEntry(
                            rank = pick.rank, stockCode = pick.stockCode, stockName = pick.stockName,
                            reason = pick.reason, confidence = pick.confidence,
                            sourceSkillId = pick.sourceAgentId, createdAt = pick.createdAt
                        )
                    }
                    StockDataCenter.addSkillPicks(entries)
                    if (isAdded) requireActivity().runOnUiThread {
                        val stockSummary = picks.take(5).joinToString("\n") { pick ->
                            "${pick.rank}. ${pick.stockName}(${pick.stockCode}) — 信心:${"%.0f".format(pick.confidence * 100)}%"
                        }
                        addBotMessage("📌 智能体「$agentName」已选出 ${picks.size} 只股票并存入精选池:\n$stockSummary\n\n这些股票将在策略和模拟交易中优先考虑。")
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "选股解析失败: ${e.message}") }
        }
    }

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