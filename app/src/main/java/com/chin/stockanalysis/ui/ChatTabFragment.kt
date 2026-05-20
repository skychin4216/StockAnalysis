package com.chin.stockanalysis.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import android.app.AlertDialog  // 添加 AlertDialog 导入
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.databinding.FragmentChatBinding
import com.chin.stockanalysis.stock.IntentRecognizer
import com.chin.stockanalysis.stock.StockService
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatTabFragment : Fragment() {

    companion object {
        private const val TAG = "ChatTabFragment"
        private const val SYSTEM_PROMPT = """你是一个专业的A股股票投资分析助手。你的职责包括：

1. 分析股票实时行情，解读K线图和技术指标
2. 提供量化和基本面分析
3. 解释股票术语和交易规则（T+1, 涨跌停10%等）
4. 回答用户关于股票投资的各种问题

规则：
- 基于提供的实时数据进行分析，如果数据不可用，诚实告知用户
- 不要给出具体的买卖建议，只提供分析参考
- 用中文回答，专业但易懂
- 适当使用emoji增强可读性
- 风险提示：投资有风险，入市需谨慎"""
    }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    // ✅ 唯一正确定义，无任何重复
    private lateinit var adapter: ChatAdapter
    private val messages: MutableList<Message> = mutableListOf()

    // 当前正在进行的流式请求 Job（可取消）
    private var currentStreamingJob: Job? = null

    private var apiProvider: ApiProvider? = null
    private lateinit var stockService: StockService

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initProvider()
        initStockService()
        setupRecyclerView()
        setupInput()
        setupMenu()
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
    }

    private fun initProvider() {
        val configManager = ApiConfigManager.getInstance(requireContext())
        apiProvider = configManager.createCurrentProvider()
        Log.d(TAG, "当前提供商: ${apiProvider?.config?.name ?: "未配置"}")
    }

    private fun initStockService() {
        // 使用并发多源仓储，从5个数据源竞速获取数据
        val multiSourceRepo = StockDataSourceFactory.createDefaultRepository(requireContext())
        stockService = StockService(repository = multiSourceRepo)
    }

    // ✅ 100% 无报错
    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)

        adapter.onCopyMessage = { text ->
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("message", text))
            Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
        }

        adapter.onEditMessage = { position, _ ->
            showEditMessageDialog(position)
        }

        adapter.onDeleteMessage = { position ->
            if (position in messages.indices) {
                messages.removeAt(position)
                adapter.notifyItemRemoved(position)
            }
        }

        adapter.onUndoMessage = { position ->
            undoUserMessage(position)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(requireContext(), "请输入内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendMessage(text)
        }
    }

    private fun setupMenu() {
        binding.btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view)
            popup.menu.add("清空对话")
            popup.setOnMenuItemClickListener { item ->
                if (item.title == "清空对话") {
                    clearMessages()
                    true
                } else false
            }
            popup.show()
        }
    }

    private fun showWelcomeMessage() {
        if (messages.isNotEmpty()) return
        val providerName = apiProvider?.config?.name ?: "服务器默认模型"
        addBotMessage(
            "👋 你好！我是你的A股投资分析助手。\n" +
                    "当前模型: $providerName\n\n" +
                    "你可以这样问我：\n" +
                    "• 600519 现在多少钱？\n" +
                    "• 帮我分析一下贵州茅台\n" +
                    "• 上证指数今天怎么样？"
        )
    }

    private fun sendMessage(userText: String) {
        addMessage(Message(content = userText, isUser = true))
        binding.etInput.setText("")
        hideKeyboard()

        val provider = apiProvider ?: run {
            addErrorMessage("❌ AI 服务未初始化，请稍后重试。")
            return
        }

        val streamingMessage = Message(content = "", isUser = false, isStreaming = true)
        addMessage(streamingMessage)
        val streamingIndex = messages.size - 1

        // 使用 viewLifecycleOwner.lifecycleScope 确保 Fragment 销毁时自动取消
        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            val finalSystemPrompt = withContext(Dispatchers.IO) {
                buildSystemPromptWithStockData(userText)
            }
            val history = messages.toList().subList(0, streamingIndex)
            val accumulated = StringBuilder()

            provider.sendMessageStream(
                messages = history,
                systemPrompt = finalSystemPrompt,
                onSuccess = { chunk ->
                    accumulated.append(chunk)
                    // isAdded 确保 Fragment 仍然附加到 Activity（解决 HOME 键后回来可能崩溃）
                    if (isAdded) requireActivity().runOnUiThread {
                        updateStreamingMessage(streamingIndex, accumulated.toString())
                    }
                },
                onComplete = { full ->
                    val finalText = full.ifEmpty { accumulated.toString() }
                    if (isAdded) requireActivity().runOnUiThread {
                        completeStreamingMessage(streamingIndex, finalText)
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

    private fun buildSystemPromptWithStockData(userText: String): String {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "🔍 开始处理用户输入: '${userText.take(50)}...'")
        Log.i(TAG, "═══════════════════════════════════════")

        // 🧪 新流程对比测试：IntentRecognizer 一步识别
        val recognizer = IntentRecognizer()
        val newResult = recognizer.recognizeAndProcess(userText)
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "🧪 [新流程对比] IntentRecognizer.recognizeAndProcess:")
        Log.i(TAG, "  intentType: ${newResult.intentType}")
        Log.i(TAG, "  stockCodes: ${newResult.stockCodes}")
        Log.i(TAG, "  hasMarketData: ${newResult.hasMarketData}")
        Log.i(TAG, "  confidence: ${newResult.confidence}")
        Log.i(TAG, "  promptInjection 长度: ${newResult.promptInjection.length}")
        if (newResult.intentType.name.contains("GENERAL_MARKET") || newResult.intentType.name.contains("UNKNOWN")) {
            Log.i(TAG, "  🧪 用户输入没有具体股票代码 → AI 不会收到实时行情数据（这是正常的）")
        } else if (newResult.hasMarketData) {
            Log.i(TAG, "  🧪 IntentRecognizer 识别出具体股票代码，可获取实时数据")
        }
        Log.i(TAG, "═══════════════════════════════════════")

        // 旧流程：StockService（保持原有逻辑不变）
        return try {
            val ctx = stockService.processUserInput(userText)
            val elapsed = System.currentTimeMillis() - startTime

            Log.i(TAG, "═══════════════════════════════════════")
            Log.i(TAG, "📋 [旧流程] StockService.processUserInput 结果 (${elapsed}ms):")
            Log.i(TAG, "  intent: ${ctx.intent.intent}")
            Log.i(TAG, "  stockCodes: ${ctx.intent.stockCodes}")
            Log.i(TAG, "  stockNames: ${ctx.intent.stockNames}")
            Log.i(TAG, "  confidence: ${ctx.intent.confidence}")
            Log.i(TAG, "  hasStockData: ${ctx.hasStockData}")
            Log.i(TAG, "  promptPrefix 长度: ${ctx.promptPrefix.length}")
            Log.i(TAG, "  promptPrefix 前200字: ${ctx.promptPrefix.take(200)}")

            if (ctx.hasStockData && ctx.promptPrefix.isNotBlank()) {
                val result = "$SYSTEM_PROMPT\n\n【实时行情数据】\n${ctx.promptPrefix}"
                Log.i(TAG, "✅ [旧流程] 成功注入实时数据 (总长: ${result.length})")
                Log.i(TAG, "═══════════════════════════════════════")
                result
            } else {
                Log.w(TAG, "⚠️ [旧流程] 未获取到股票数据")
                Log.w(TAG, "  ⚠️ 旧流程返回 ${ctx.intent.intent} → AI 无实时数据（新流程对比可见 intentType）")
                Log.i(TAG, "═══════════════════════════════════════")
                SYSTEM_PROMPT
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 构建股票数据失败: ${e.message}", e)
            Log.i(TAG, "═══════════════════════════════════════")
            SYSTEM_PROMPT
        }
    }

    private fun showEditMessageDialog(position: Int) {
        if (position !in messages.indices || !messages[position].isUser) return
        val input = EditText(requireContext()).apply {
            setText(messages[position].content)
            setSelection(text?.length ?: 0)
            minLines = 2
        }
        AlertDialog.Builder(requireContext())
            .setTitle("修改消息")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val newText = input.text?.toString()?.trim().orEmpty()
                if (newText.isBlank()) {
                    Toast.makeText(requireContext(), "消息不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                messages[position] = messages[position].copy(content = newText)
                adapter.notifyItemChanged(position)
            }
            .show()
    }

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
            messages[index] = Message(
                content = "❌ $errMsg",
                isUser = false,
                isError = true,
                errorMessage = errMsg
            )
            adapter.notifyItemChanged(index)
            binding.recyclerView.scrollToPosition(messages.size - 1)
        }
    }

    private fun addMessage(message: Message) {
        messages.add(message)
        adapter.notifyItemInserted(messages.size - 1)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun addBotMessage(text: String) {
        addMessage(Message(content = text, isUser = false))
    }

    private fun addErrorMessage(text: String) {
        addMessage(Message(content = text, isUser = false, isError = true))
    }

    private fun clearMessages() {
        val size = messages.size
        messages.clear()
        adapter.notifyItemRangeRemoved(0, size)
        showWelcomeMessage()
    }

    /** 撤销用户消息及其后续 AI 回复 */
    private fun undoUserMessage(position: Int) {
        if (position !in messages.indices || !messages[position].isUser) return
        // 从当前位置开始删除，直到遇到下一条用户消息（或消息列表末尾）
        val removeEnd = (position + 1 until messages.size)
            .firstOrNull { messages[it].isUser } ?: messages.size
        val removeCount = removeEnd - position
        repeat(removeCount) { messages.removeAt(position) }
        adapter.notifyItemRangeRemoved(position, removeCount)
        Toast.makeText(requireContext(), "已撤销该轮对话", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }
}