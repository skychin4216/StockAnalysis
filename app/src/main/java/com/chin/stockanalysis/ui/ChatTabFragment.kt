package com.chin.stockanalysis.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.databinding.FragmentChatBinding
import com.chin.stockanalysis.stock.StockQueryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ## 聊天 Tab（豆包 app1 风格）
 *
 * 顶部：≡（历史） | 标题（自动取用户首句） | ⋮菜单
 * 输入：📷 | 输入框 | 🎤 | ➕发送
 *
 * 点击 ≡ 展开 [ConversationListFragment] BottomSheet 查看历史对话。
 * 所有股票数据逻辑委托 [StockQueryEngine]。
 */
class ChatTabFragment : Fragment() {

    companion object {
        private const val TAG = "ChatTabFragment"

        private const val BASE_SYSTEM_PROMPT = """你是一个专业的A股股票投资分析助手。你的职责包括：

1. 分析股票实时行情，解读K线图和技术指标
2. 提供量化和基本面分析
3. 解释股票术语和交易规则（T+1, 涨跌停10%等）
4. 回答用户关于股票投资的各种问题

规则：
- 如果下方注入了【实时行情数据】，请基于这些数据进行分析。
- 如果没有【实时行情数据】，直接基于训练知识回答，不要说无法获取实时数据。
- 不要给出具体买卖建议，只提供分析参考
- 用中文回答，专业但易懂，适当使用emoji
- 风险提示：投资有风险，入市需谨慎
- 本APP支持5个数据源并发获取（新浪、腾讯、东方财富、聚宽、AKShare）。"""
    }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ChatAdapter
    private val messages: MutableList<Message> = mutableListOf()

    private var currentStreamingJob: Job? = null
    private var apiProvider: ApiProvider? = null
    private var tts: TextToSpeech? = null

    private val queryEngine: StockQueryEngine by lazy {
        StockQueryEngine.create(requireContext())
    }

    // 历史会话（传递给 ConversationListFragment）
    private val sessions = mutableListOf<ConversationListFragment.ConversationSession>()
    // 当前会话是否已有标题
    private var hasAutoTitle = false

    // ════════════════════════════════════════
    // 生命周期
    // ════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initProvider()
        setupRecyclerView()
        setupInput()
        setupTitleBar()
        initTts()
        showWelcomeMessage()
    }

    override fun onResume() {
        super.onResume()
        initProvider()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        currentStreamingJob?.cancel()
        tts?.shutdown()
    }

    // ════════════════════════════════════════
    // 初始化
    // ════════════════════════════════════════

    private fun initProvider() {
        apiProvider = ApiConfigManager.getInstance(requireContext()).createCurrentProvider()
    }

    private fun initTts() {
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
            }
        }
    }

    // ════════════════════════════════════════
    // UI 设置
    // ════════════════════════════════════════

    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)

        // 复制
        adapter.onCopyMessage = { text ->
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("msg", text))
            Toast.makeText(requireContext(), "✅ 已复制", Toast.LENGTH_SHORT).show()
        }

        // 编辑
        adapter.onEditMessage = { position, _ -> showEditMessageDialog(position) }

        // 删除
        adapter.onDeleteMessage = { position ->
            if (position in messages.indices) {
                messages.removeAt(position)
                adapter.notifyItemRemoved(position)
            }
        }

        // 撤销
        adapter.onUndoMessage = { position -> undoUserMessage(position) }

        // 🔊 播放声音
        adapter.onPlayVoice = { text ->
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "play")
            Toast.makeText(requireContext(), "🔊 播放中...", Toast.LENGTH_SHORT).show()
        }

        // ⭐ 收藏
        adapter.onFavorite = { text ->
            Toast.makeText(requireContext(), "⭐ 已收藏（本地存储功能开发中）", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "收藏: ${text.take(30)}...")
        }

        // 转发
        adapter.onShare = { text ->
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "分享到"))
        }

        // 🔄 重新生成
        adapter.onRegenerate = { position ->
            regenerateMessage(position)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter
    }

    private fun setupInput() {
        // 发送按钮点击
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(requireContext(), "请输入内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendMessage(text)
        }

        // Enter 键发送
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                val text = binding.etInput.text.toString().trim()
                if (text.isNotEmpty()) sendMessage(text)
                true
            } else false
        }

        // 📷 图片（预留）
        binding.btnImage.setOnClickListener {
            Toast.makeText(requireContext(), "📸 图片解析功能开发中，敬请期待~", Toast.LENGTH_SHORT).show()
        }

        // 🎤 语音输入（预留）
        binding.btnVoice.setOnClickListener {
            Toast.makeText(requireContext(), "🎤 语音输入功能开发中，敬请期待~", Toast.LENGTH_SHORT).show()
        }

        // 根据输入内容切换右边按钮：空 → 🎤 + ➕，有内容 → ↑ 发送箭头
        binding.etInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasText = !s.isNullOrBlank()
                if (hasText) {
                    // 有输入：隐藏 🎤，右边按钮变为 ↑ 发送箭头
                    binding.btnVoice.visibility = View.GONE
                    binding.btnSend.setImageResource(com.chin.stockanalysis.R.drawable.ic_send_arrow)
                } else {
                    // 无输入：显示 🎤，右边按钮还原为 ➕
                    binding.btnVoice.visibility = View.VISIBLE
                    binding.btnSend.setImageResource(android.R.drawable.ic_input_add)
                }
            }
        })
    }

    private fun setupTitleBar() {
        queryEngine.startPrefetch(viewLifecycleOwner.lifecycleScope)

        // ≡ 打开历史对话
        binding.btnDrawer.setOnClickListener {
            showConversationHistory()
        }

        // ⋮ 菜单
        binding.btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view)
            popup.menu.add("清空对话")
            popup.menu.add("已记忆的偏好")
            popup.menu.add("清除偏好记忆")
            popup.menu.add("数据源诊断")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "清空对话" -> { startNewConversation(); true }
                    "已记忆的偏好" -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("📌 当前已记忆的偏好")
                            .setMessage(queryEngine.userPrefManager.getPreferenceSummary())
                            .setPositiveButton("确定", null).show(); true
                    }
                    "清除偏好记忆" -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("清除偏好记忆")
                            .setMessage("确定要清除所有已记忆的过滤条件吗？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("确定清除") { _, _ ->
                                queryEngine.userPrefManager.clearAllPreferences()
                                Toast.makeText(requireContext(), "✅ 已清除", Toast.LENGTH_SHORT).show()
                            }.show(); true
                    }
                    "数据源诊断" -> {
                        Log.d(TAG, queryEngine.getRepository().getDiagnostics())
                        Toast.makeText(requireContext(), "诊断信息已打印到日志", Toast.LENGTH_SHORT).show(); true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun showWelcomeMessage() {
        if (messages.isNotEmpty()) return
        addBotMessage(
            "👋 你好！我是你的A股投资分析助手。\n" +
                    "当前模型: ${apiProvider?.config?.name ?: "服务器默认模型"}\n\n" +
                    "💡 支持以下查询：\n" +
                    "• 600519 现在多少钱？\n" +
                    "• 帮我分析贵州茅台\n" +
                    "• 商业航天产业链前10支股票\n" +
                    "• 有色金属板块今天怎么样？\n\n" +
                    "📖 点击左上角 ≡ 可查看历史对话"
        )
    }

    // ════════════════════════════════════════
    // 历史对话（BottomSheet）
    // ════════════════════════════════════════

    private fun showConversationHistory() {
        val sheet = ConversationListFragment.newInstance()
        sheet.sessions = sessions.toList()
        sheet.onNewChatClick = { startNewConversation() }
        sheet.onSessionClick = { session ->
            Toast.makeText(requireContext(), "💬 ${session.title}", Toast.LENGTH_SHORT).show()
        }
        sheet.show(childFragmentManager, "conversation_history")
    }

    private fun startNewConversation() {
        // 保存当前会话到历史
        val firstUser = messages.firstOrNull { it.isUser }
        val lastBot = messages.lastOrNull { !it.isUser && !it.isError }
        if (firstUser != null) {
            val title = firstUser.content.let {
                if (it.length > 18) it.take(18) + "…" else it
            }
            val subtitle = lastBot?.content?.let {
                if (it.length > 30) it.take(30) + "…" else it
            } ?: "暂无回复"
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            sessions.add(0, ConversationListFragment.ConversationSession(
                id = System.currentTimeMillis().toString(),
                title = title,
                subtitle = subtitle,
                timestamp = ts
            ))
        }
        // 清空当前对话
        val size = messages.size
        messages.clear()
        adapter.notifyItemRangeRemoved(0, size)
        hasAutoTitle = false
        if (_binding != null) {
            binding.tvChatTitle.text = getString(com.chin.stockanalysis.R.string.app_name)
        }
        showWelcomeMessage()
    }

    // ════════════════════════════════════════
    // 消息发送
    // ════════════════════════════════════════

    private fun sendMessage(userText: String) {
        addMessage(Message(content = userText, isUser = true))
        binding.etInput.setText("")
        hideKeyboard()

        // 第一条用户消息 → 自动更新标题栏
        if (!hasAutoTitle) {
            hasAutoTitle = true
            val title = if (userText.length > 12) userText.take(12) + "…" else userText
            binding.tvChatTitle.text = title
        }

        val provider = apiProvider ?: run {
            addErrorMessage("❌ AI 服务未初始化，请在「我的」页面配置 API。")
            return
        }

        val streamingMessage = Message(content = "", isUser = false, isStreaming = true)
        addMessage(streamingMessage)
        val streamingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            val finalSystemPrompt = withContext(Dispatchers.IO) {
                queryEngine.buildSystemPrompt(
                    userText = userText,
                    baseSystemPrompt = BASE_SYSTEM_PROMPT,
                    onPreferenceLeaned = { _ ->
                        if (isAdded) requireActivity().runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                "📌 已记住你的偏好，后续自动应用",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            val history = messages.toList().subList(0, streamingIndex)
            val accumulated = StringBuilder()

            provider.sendMessageStream(
                messages = history,
                systemPrompt = finalSystemPrompt,
                onSuccess = { chunk ->
                    accumulated.append(chunk)
                    if (isAdded) requireActivity().runOnUiThread {
                        updateStreamingMessage(streamingIndex, accumulated.toString())
                    }
                },
                onComplete = { full ->
                    val finalText = full.ifEmpty { accumulated.toString() }
                    if (isAdded) requireActivity().runOnUiThread {
                        completeStreamingMessage(streamingIndex, finalText)
                        // 消息结束后显示"聊聊新话题"提示
                        binding.tvNewTopicHint.visibility = View.VISIBLE
                    }
                },
                onError = { errMsg ->
                    if (isAdded) requireActivity().runOnUiThread {
                        failStreamingMessage(streamingIndex, errMsg)
                    }
                }
            )
        }
    }

    /** 重新生成指定位置之后的 AI 回复 */
    private fun regenerateMessage(botPosition: Int) {
        if (botPosition <= 0 || botPosition >= messages.size) return
        val userMsg = (botPosition - 1 downTo 0).firstNotNullOfOrNull {
            messages[it].takeIf { m -> m.isUser }
        } ?: return
        // 删除该条及以后所有消息
        val count = messages.size - botPosition
        repeat(count) { messages.removeAt(botPosition) }
        adapter.notifyItemRangeRemoved(botPosition, count)
        // 重新发送
        sendMessage(userMsg.content)
    }

    // ════════════════════════════════════════
    // 消息 UI 操作
    // ════════════════════════════════════════

    private fun updateStreamingMessage(index: Int, content: String) {
        if (index in messages.indices) {
            messages[index] = messages[index].copy(content = content, isStreaming = true)
            adapter.notifyItemChanged(index)
            binding.recyclerView.scrollToPosition(messages.size - 1)
        }
    }

    private fun completeStreamingMessage(index: Int, content: String) {
        if (index in messages.indices) {
            messages[index] = messages[index].copy(content = content, isStreaming = false)
            adapter.notifyItemChanged(index)
            binding.recyclerView.scrollToPosition(messages.size - 1)
        }
    }

    private fun failStreamingMessage(index: Int, errMsg: String) {
        if (index in messages.indices) {
            messages[index] = Message(content = "❌ $errMsg", isUser = false, isError = true, errorMessage = errMsg)
            adapter.notifyItemChanged(index)
            binding.recyclerView.scrollToPosition(messages.size - 1)
        }
    }

    private fun addMessage(message: Message) {
        binding.tvNewTopicHint.visibility = View.GONE
        messages.add(message)
        adapter.notifyItemInserted(messages.size - 1)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun addBotMessage(text: String) = addMessage(Message(content = text, isUser = false))
    private fun addErrorMessage(text: String) =
        addMessage(Message(content = text, isUser = false, isError = true))

    private fun showEditMessageDialog(position: Int) {
        if (position !in messages.indices || !messages[position].isUser) return
        val input = EditText(requireContext()).apply {
            setText(messages[position].content)
            setSelection(text?.length ?: 0)
            minLines = 2
        }
        AlertDialog.Builder(requireContext())
            .setTitle("修改消息").setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val newText = input.text?.toString()?.trim().orEmpty()
                if (newText.isBlank()) return@setPositiveButton
                messages[position] = messages[position].copy(content = newText)
                adapter.notifyItemChanged(position)
            }.show()
    }

    private fun undoUserMessage(position: Int) {
        if (position !in messages.indices || !messages[position].isUser) return
        val removeEnd = (position + 1 until messages.size).firstOrNull { messages[it].isUser } ?: messages.size
        val count = removeEnd - position
        repeat(count) { messages.removeAt(position) }
        adapter.notifyItemRangeRemoved(position, count)
        Toast.makeText(requireContext(), "已撤销该轮对话", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }
}