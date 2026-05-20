package com.chin.stockanalysis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.chin.stockanalysis.databinding.ActivityChatBinding
import com.chin.stockanalysis.stock.IntentRecognizer
import com.chin.stockanalysis.stock.StockService
import com.chin.stockanalysis.stock.data.MultiSourceStockRepository
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.ui.ChatAdapter      // ✅ 修正
import com.chin.stockanalysis.ui.Message          // ✅ 修正
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 聊天 Activity - 股票分析助手
 *
 * 集成：
 * - 多源股票数据（智能缓存 + 并发获取 + 优先级管理）
 * - 多 AI API 提供商（DeepSeek、硅基流动、豆包等）
 * - 股票实时数据服务（自动识别股票意图并注入数据到 AI prompt）
 * - 消息操作（复制/编辑/删除）
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        private const val SYSTEM_PROMPT = """你是一个专业的A股股票投资分析助手。你的职责包括：

1. 分析股票实时行情，解读K线图和技术指标
2. 提供量化和基本面分析
3. 解释股票术语和交易规则（T+1, 涨跌停10%等）
4. 回答用户关于股票投资的各种问题

规则：
- 【实时数据说明】如果下方注入了【实时行情数据】，请基于这些数据进行分析。这是通过新浪/腾讯/东方财富等免费API实时获取的。
- 【无实时数据处理】如果下方没有【实时行情数据】注入，说明用户问的是股市常识、投资理念、或者没有具体股票代码/名称的通用问题。这种情况下请直接基于你的训练知识回答，不要说"我无法获取实时行情"或"我的知识有截止日期"——普通股市常识和投资理念问答不需要实时数据。
- 不要给出具体的买卖建议，只提供分析参考
- 用中文回答，专业但易懂
- 适当使用emoji增强可读性
- 风险提示：投资有风险，入市需谨慎
- 本APP支持5个数据源并发获取（新浪、腾讯、东方财富、聚宽、AKShare），当用户查询具体股票代码或名称时会自动注入实时行情。"""
        // 各提供商的 API Key 配置键名（用于日志诊断）
        private val KEY_LOG_MAP = mapOf(
            "siliconflow-v3-flash" to "SILICONFLOW_KEY",
            "siliconflow-v3" to "SILICONFLOW_KEY",
            "siliconflow-r1" to "SILICONFLOW_KEY",
            "siliconflow-qwen" to "SILICONFLOW_KEY",
            "doubao" to "DOUBAO_KEY",
            "deepseek-official" to "DEEPSEEK_KEY",
            "aliyun-maas" to "ALIYUN_MAAS_KEY",
        )
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private val messages: MutableList<Message> = mutableListOf()

    private var apiProvider: ApiProvider? = null
    private lateinit var stockService: StockService
    private lateinit var multiSourceRepository: MultiSourceStockRepository

    private val activityJob = SupervisorJob()
    private val uiScope = CoroutineScope(Dispatchers.Main + activityJob)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityChatBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // ============================================================
            // 步骤 1: 初始化多源股票数据仓储 (功能 A/B/C)
            // ============================================================
            multiSourceRepository = StockDataSourceFactory.createDefaultRepository(this)
            Log.d(TAG, "✅ 多源仓储初始化完成")

            // ============================================================
            // 步骤 2: 启动后台健康检查（每30秒）
            // ============================================================
            lifecycleScope.launch {
                while (isActive) {
                    try {
                        multiSourceRepository.healthCheck()
                        delay(30000)  // 每30秒检查一次
                    } catch (e: Exception) {
                        Log.w(TAG, "Health check error: ${e.message}")
                    }
                }
            }

            // ============================================================
            // 步骤 3: 打印诊断信息（调试用）
            // ============================================================
            lifecycleScope.launch {
                delay(2000)
                val diagnostics = multiSourceRepository.getDiagnostics()
                Log.d(TAG, "\n$diagnostics")
            }

            // ============================================================
            // 步骤 4: 初始化 AI API 提供商
            // ============================================================
            initProvider()

            // ============================================================
            // 步骤 5: 初始化股票服务（使用并发多源仓储）
            // ============================================================
            // 复用已有的 multiSourceRepository（并发竞速 5 个数据源）
            stockService = StockService(repository = multiSourceRepository)

            // ============================================================
            // 步骤 6: 打印完整数据源信息
            // ============================================================
            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "📊 数据源配置:")
            Log.d(TAG, "📊 多源仓储已使用并发竞速模式")
            Log.d(TAG, "📊 诊断信息: ${multiSourceRepository.getDiagnostics().take(200)}")
            Log.d(TAG, "═══════════════════════════════════════")

            // ============================================================
            // 步骤 7: 初始化 UI 组件
            // ============================================================
            setupToolbar()
            setupRecyclerView()
            setupInput()
            setupMenu()
            showWelcomeMessage()

            Log.d(TAG, "✅ ChatActivity 初始化完成 - 所有功能就绪")
            Log.d(TAG, "═══════════════════════════════════════")
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ ChatActivity 初始化失败 ❌❌❌")
            Log.e(TAG, "错误类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "错误信息: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityJob.cancel()
    }

    /** 初始化 AI API 提供商 */
    private fun initProvider() {
        val configManager = ApiConfigManager.getInstance(this)
        apiProvider = configManager.createCurrentProvider()
        if (apiProvider == null) {
            Log.w(TAG, "⚠️ 未检测到可用的 AI 提供商或 API Key 未配置")
        } else {
            val config = apiProvider?.config
            Log.d(TAG, "✅ 当前提供商: ${config?.name}, URL: ${config?.baseUrl}, Model: ${config?.model}")
            val keyValue = config?.apiKey.orEmpty()
            val keySource = when {
                keyValue.isBlank() -> "❌ 空"
                configManager.getUserApiKey(configManager.getSelectedProviderId()).isNullOrBlank() -> "📄 配置文件"
                else -> "👤 用户输入"
            }
            val masked = if (keyValue.length > 12) "${keyValue.take(8)}...${keyValue.takeLast(4)}" else keyValue
            Log.d(TAG, "🔑 API Key: $masked | 来源: $keySource")
        }
    }

    /** 配置顶部标题栏 */
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    /** 配置 RecyclerView */
    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)
        adapter.onCopyMessage = { text ->
            copyToClipboard(text)
        }
        adapter.onEditMessage = { position, oldText ->
            showEditDialog(position, oldText)
        }
        adapter.onDeleteMessage = { position ->
            deleteMessage(position)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter
    }

    /** 配置输入框 + 发送按钮 */
    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendMessage(text)
        }
    }

    /** 配置右上角菜单按钮 */
    private fun setupMenu() {
        binding.btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("切换 API 提供商")
            popup.menu.add("清空对话")
            popup.menu.add("数据源诊断")  // 新增：查看数据源状态
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "切换 API 提供商" -> {
                        Toast.makeText(this, "请在设置页中切换", Toast.LENGTH_SHORT).show()
                        true
                    }
                    "清空对话" -> {
                        clearMessages()
                        true
                    }
                    "数据源诊断" -> {
                        val diagnostics = multiSourceRepository.getDiagnostics()
                        Toast.makeText(this, "诊断信息已打印到日志", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, diagnostics)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    /** 显示欢迎消息 */
    private fun showWelcomeMessage() {
        if (messages.isNotEmpty()) return
        val providerName = apiProvider?.config?.name ?: "未配置"
        addBotMessage(
            "👋 你好！我是你的A股投资分析助手。\n" +
                    "当前模型: $providerName\n" +
                    "数据源: 多源并发 (新浪/聱宽/腾讯/东方/AKShare)\n\n" +
                    "你可以这样问我：\n" +
                    "• 600519 现在多少钱？\n" +
                    "• 帮我分析一下贵州茅台\n" +
                    "• 上证指数今天怎么样？"
        )
    }

    /** 发送消息 */
    private fun sendMessage(userText: String) {
        // 1. 添加用户消息
        addMessage(Message(content = userText, isUser = true))
        binding.etInput.setText("")
        hideKeyboard()

        val provider = apiProvider
        if (provider == null) {
            addErrorMessage("❌ 未配置可用的 AI API。请前往设置页配置 API Key。")
            return
        }

        // 2. 添加占位"流式"消息
        val streamingMessage = Message(content = "", isUser = false, isStreaming = true)
        addMessage(streamingMessage)
        val streamingIndex = messages.size - 1

        // 3. 异步处理：识别股票意图 -> 拼接 system prompt -> 调用 AI
        uiScope.launch {
            val finalSystemPrompt = withContext(Dispatchers.IO) {
                buildSystemPromptWithStockData(userText)
            }

            // 发送请求（不含本次刚加入的 streaming 占位）
            val history = messages.subList(0, streamingIndex).toList()
            val accumulated = StringBuilder()

            provider.sendMessageStream(
                messages = history,
                systemPrompt = finalSystemPrompt,
                onSuccess = { chunk ->
                    accumulated.append(chunk)
                    runOnUiThread { updateStreamingMessage(streamingIndex, accumulated.toString()) }
                },
                onComplete = { full ->
                    val finalText = if (full.isNotEmpty()) full else accumulated.toString()
                    runOnUiThread { completeStreamingMessage(streamingIndex, finalText) }
                },
                onError = { errMsg ->
                    runOnUiThread { failStreamingMessage(streamingIndex, errMsg) }
                }
            )
        }
    }

    /** 根据用户输入构建 system prompt（注入股票数据） */
    private fun buildSystemPromptWithStockData(userText: String): String {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "═══════════════════════════════════════")
        Log.e(TAG, "🔍 开始处理用户输入: '${userText.take(50)}...'")
        Log.d(TAG, "═══════════════════════════════════════")

        // ============================================================
        // 🧪 新流程对比测试：IntentRecognizer 一步识别
        // ============================================================
        val newRecognizer = IntentRecognizer()
        val newResult = newRecognizer.recognizeAndProcess(userText)
        Log.i(TAG, "═══════════════════════════════════════")
        Log.w(TAG, "🧪 [新流程对比] IntentRecognizer.recognizeAndProcess:")
        Log.i(TAG, "  intentType: ${newResult.intentType}")
        Log.i(TAG, "  stockCodes: ${newResult.stockCodes}")
        Log.i(TAG, "  hasMarketData: ${newResult.hasMarketData}")
        Log.i(TAG, "  confidence: ${newResult.confidence}")
        Log.i(TAG, "  🧪 promptInjection 长度: ${newResult.promptInjection.length}")
        if (newResult.intentType.name.contains("GENERAL_MARKET") || newResult.intentType.name.contains("UNKNOWN")) {
            Log.i(TAG, "  🧪 用户输入没有具体股票代码 → AI 不会收到实时行情数据（这是正常的）")
        } else if (newResult.hasMarketData) {
            Log.i(TAG, "  🧪 IntentRecognizer 识别出具体股票代码，可获取实时数据")
        }
        Log.i(TAG, "═══════════════════════════════════════")

        // ============================================================
        // 旧流程：StockService（保持原有逻辑不变，继续正常运行）
        // ============================================================
        return try {
            val ctx = stockService.processUserInput(userText)

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "═══════════════════════════════════════")
            Log.e(TAG, "📋 [旧流程] StockService.processUserInput 结果 (${elapsed}ms):")
            Log.d(TAG, "  intent: ${ctx.intent.intent}")
            Log.d(TAG, "  stockCodes: ${ctx.intent.stockCodes}")
            Log.d(TAG, "  stockNames: ${ctx.intent.stockNames}")
            Log.d(TAG, "  confidence: ${ctx.intent.confidence}")
            Log.d(TAG, "  hasStockData: ${ctx.hasStockData}")
            Log.d(TAG, "  promptPrefix 长度: ${ctx.promptPrefix.length}")
            Log.d(TAG, "  promptPrefix 前200字: ${ctx.promptPrefix.take(200)}")

            if (ctx.hasStockData && ctx.promptPrefix.isNotBlank()) {
                val result = "$SYSTEM_PROMPT\n\n【实时行情数据】\n${ctx.promptPrefix}"
                Log.w(TAG, "✅ [旧流程] 成功注入实时数据到 system prompt (总长: ${result.length} chars)")
                Log.d(TAG, "═══════════════════════════════════════")
                result
            } else {
                Log.w(TAG, "⚠️ [旧流程] 未获取到股票数据 (hasStockData=${ctx.hasStockData}, promptPrefix.isEmpty=${ctx.promptPrefix.isBlank()})")
                Log.d(TAG, "  rawQuery: '${ctx.intent.rawQuery.take(50)}...'")
                Log.d(TAG, "  intent: ${ctx.intent.intent}")
                Log.d(TAG, "  ⚠️ 旧流程返回 UNKNOWN → AI 无实时数据（新流程对比可见 intentType）")
                Log.d(TAG, "═══════════════════════════════════════")
                SYSTEM_PROMPT
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 构建股票数据失败: ${e.message}", e)
            Log.e(TAG, "═══════════════════════════════════════")
            SYSTEM_PROMPT
        }
    }

    /** 更新流式消息内容 */
    private fun updateStreamingMessage(index: Int, content: String) {
        if (index !in messages.indices) return
        val old = messages[index]
        messages[index] = old.copy(content = content, isStreaming = true)
        adapter.notifyItemChanged(index)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    /** 完成流式消息 */
    private fun completeStreamingMessage(index: Int, content: String) {
        if (index !in messages.indices) return
        val old = messages[index]
        messages[index] = old.copy(content = content, isStreaming = false)
        adapter.notifyItemChanged(index)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    /** 流式消息失败 */
    private fun failStreamingMessage(index: Int, errMsg: String) {
        if (index !in messages.indices) return
        val old = messages[index]
        messages[index] = old.copy(
            content = errMsg,
            isStreaming = false,
            isError = true,
            errorMessage = errMsg
        )
        adapter.notifyItemChanged(index)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    /** 添加消息到列表并刷新UI */
    private fun addMessage(message: Message) {
        messages.add(message)
        adapter.notifyItemInserted(messages.size - 1)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    /** 添加机器人消息 */
    private fun addBotMessage(text: String) {
        addMessage(Message(content = text, isUser = false))
    }

    /** 添加错误消息 */
    private fun addErrorMessage(text: String) {
        addMessage(
            Message(
                content = text,
                isUser = false,
                isError = true,
                errorMessage = text
            )
        )
    }

    /** 清空对话 */
    private fun clearMessages() {
        val size = messages.size
        messages.clear()
        adapter.notifyItemRangeRemoved(0, size)
        showWelcomeMessage()
    }

    /** 复制文本到剪贴板 */
    fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    /** 删除指定位置的消息 */
    private fun deleteMessage(position: Int) {
        if (position in messages.indices) {
            messages.removeAt(position)
            adapter.notifyItemRemoved(position)
            adapter.notifyItemRangeChanged(position, messages.size - position)
        }
    }

    /** 显示编辑对话框 */
    private fun showEditDialog(position: Int, oldText: String) {
        val editText = android.widget.EditText(this).apply {
            setText(oldText)
            setSelection(oldText.length)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("编辑消息")
            .setView(editText)
            .setNegativeButton("取消", null)
            .setPositiveButton("重新发送") { _, _ ->
                val newText = editText.text.toString().trim()
                if (newText.isNotBlank() && newText != oldText) {
                    // 删除原消息
                    if (position in messages.indices) {
                        messages.removeAt(position)
                        adapter.notifyItemRemoved(position)
                    }
                    // 重新发送
                    sendMessage(newText)
                }
            }
            .show()
    }

    /** 隐藏软键盘 */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }

    /** 从输入文本中提取股票代码（沪深A股6位代码） */
    @Suppress("unused")
    private fun extractStockCode(query: String): String? {
        val pattern = Regex("\\b(60|00|30|68)[0-9]{4}\\b")
        return pattern.find(query)?.value
    }
}