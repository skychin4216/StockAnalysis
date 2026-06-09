package com.chin.stockanalysis

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.databinding.ActivityChatBinding
import com.chin.stockanalysis.stock.StockQueryEngine
import com.chin.stockanalysis.ui.ChatAdapter
import com.chin.stockanalysis.ui.Message
import com.chin.stockanalysis.ui.StockBrowserFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## AI 聊天 Activity（备用入口）
 *
 * 与 [com.chin.stockanalysis.ui.ChatTabFragment] 并列存在，
 * 所有股票查询逻辑均委托给 [StockQueryEngine]，保持代码一致性。
 *
 * 如需增加新的股票查询功能，只需修改 [StockQueryEngine]，
 * Activity 和 Fragment 均自动受益，无需重复修改。
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"

        /**
         * 基础系统提示词（由 Activity 维护，传入 StockQueryEngine）
         * 与 ChatTabFragment 保持一致即可，也可以按需定制。
         */
        private const val BASE_SYSTEM_PROMPT = """你是一个专业的A股股票投资分析助手。你的职责包括：

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
- 本APP支持5个数据源并发获取（新浪、腾讯、东方财富、聚宽、AKShare），当用户查询具体股票代码或名称时会自动注入实时行情。
- 当用户询问行业主题（如商业航天、有色金属、AI算力等）时，系统会自动注入该主题的实时板块行情数据供你分析。"""
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private val messages: MutableList<Message> = mutableListOf()

    private var apiProvider: ApiProvider? = null

    /**
     * 股票查询引擎（所有股票相关逻辑的唯一入口）
     * 主题/板块、具体股票、偏好记忆、盘口数据均在此引擎中处理
     */
    private val queryEngine: StockQueryEngine by lazy {
        StockQueryEngine.create(this)
    }

    private val activityJob = SupervisorJob()
    private val uiScope = CoroutineScope(Dispatchers.Main + activityJob)

    /** 历史会话列表（内存存储，显示在抽屉中）*/
    private val historySessions = mutableListOf<ChatSession>()
    private lateinit var historyAdapter: HistoryAdapter
    private var isFirstMessageInSession = true   // 每个会话的第一条消息时记录到历史

    // ════════════════════════════════════════
    // 生命周期
    // ════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityChatBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 初始化 AI 提供商
            initProvider()

            // 触发 queryEngine 懒加载（同时启动多源健康检查）
            val engine = queryEngine
            Log.d(TAG, "✅ StockQueryEngine 初始化完成")

            // 后台定期健康检查
            lifecycleScope.launch {
                delay(2000)
                Log.d(TAG, "诊断:\n${engine.getRepository().getDiagnostics().take(300)}")
                while (isActive) {
                    try { engine.getRepository().healthCheck() } catch (_: Exception) {}
                    delay(30000)
                }
            }

            // 初始化 UI
            setupToolbar()
            setupRecyclerView()
            setupInput()
            setupQuickActions()
            setupDrawer()
            setupMenu()
            showWelcomeMessage()

            Log.d(TAG, "✅ ChatActivity 初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ChatActivity 初始化失败: ${e.message}", e)
            throw e
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityJob.cancel()
    }

    // ════════════════════════════════════════
    // 初始化
    // ════════════════════════════════════════

    private fun initProvider() {
        val configManager = ApiConfigManager.getInstance(this)
        apiProvider = configManager.createCurrentProvider()
        val name = apiProvider?.config?.name ?: "未配置"
        Log.d(TAG, "当前提供商: $name")
    }

    // ════════════════════════════════════════
    // UI 设置
    // ════════════════════════════════════════

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)
        adapter.onCopyMessage = { text -> copyToClipboard(text) }
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter
    }

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

    private fun setupMenu() {
        binding.btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("清空对话")
            popup.menu.add("已记忆的偏好")
            popup.menu.add("清除偏好记忆")
            popup.menu.add("数据源诊断")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "清空对话" -> { clearMessages(); true }
                    "已记忆的偏好" -> {
                        val summary = queryEngine.userPrefManager.getPreferenceSummary()
                        AlertDialog.Builder(this)
                            .setTitle("📌 当前已记忆的偏好")
                            .setMessage(summary)
                            .setPositiveButton("确定", null)
                            .show()
                        true
                    }
                    "清除偏好记忆" -> {
                        AlertDialog.Builder(this)
                            .setTitle("清除偏好记忆")
                            .setMessage("确定要清除所有已记忆的过滤条件吗？（市值/板块/价格等）")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("确定清除") { _, _ ->
                                queryEngine.userPrefManager.clearAllPreferences()
                                Toast.makeText(this, "✅ 偏好记忆已清除", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                        true
                    }
                    "数据源诊断" -> {
                        val diag = queryEngine.getRepository().getDiagnostics()
                        Log.d(TAG, "诊断信息:\n$diag")
                        Toast.makeText(this, "诊断信息已打印到日志", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun showWelcomeMessage() {
        if (messages.isNotEmpty()) return
        val providerName = apiProvider?.config?.name ?: "未配置"
        addBotMessage(
            "👋 你好！我是你的A股投资分析助手。\n" +
                    "当前模型: $providerName\n\n" +
                    "💡 我支持以下查询：\n" +
                    "• 600519 现在多少钱？\n" +
                    "• 帮我分析一下贵州茅台\n" +
                    "• 商业航天产业链前10的股票\n" +
                    "• 有色金属板块今天怎么样？\n" +
                    "• 分析半导体行业，剔除科创板"
        )
    }

    // ════════════════════════════════════════
    // 消息发送（核心流程）
    // ════════════════════════════════════════

    private fun sendMessage(userText: String) {
        addMessage(Message(content = userText, isUser = true))
        binding.etInput.setText("")
        hideKeyboard()

        val provider = apiProvider ?: run {
            addErrorMessage("❌ 未配置可用的 AI API。请前往设置页配置 API Key。")
            return
        }

        val streamingMessage = Message(content = "", isUser = false, isStreaming = true)
        addMessage(streamingMessage)
        val streamingIndex = messages.size - 1

        uiScope.launch {
            // 步骤 1：在 IO 线程中通过 StockQueryEngine 构建 system prompt
            val finalSystemPrompt = withContext(Dispatchers.IO) {
                queryEngine.buildSystemPrompt(
                    userText = userText,
                    baseSystemPrompt = BASE_SYSTEM_PROMPT,
                    onPreferenceLeaned = {
                        // 在 IO 线程的回调，需切换到主线程显示 Toast
                        runOnUiThread {
                            Toast.makeText(
                                this@ChatActivity,
                                "📌 已记住你的偏好，后续查询自动应用\n菜单→已记忆的偏好 可查看",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }

            // 步骤 2：调用 AI 流式接口
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

    // ════════════════════════════════════════
    // 消息 UI 操作
    // ════════════════════════════════════════

    private fun updateStreamingMessage(index: Int, content: String) {
        if (index !in messages.indices) return
        messages[index] = messages[index].copy(content = content, isStreaming = true)
        adapter.notifyItemChanged(index)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun completeStreamingMessage(index: Int, content: String) {
        if (index !in messages.indices) return
        messages[index] = messages[index].copy(content = content, isStreaming = false)
        adapter.notifyItemChanged(index)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun failStreamingMessage(index: Int, errMsg: String) {
        if (index !in messages.indices) return
        messages[index] = messages[index].copy(
            content = errMsg,
            isStreaming = false,
            isError = true,
            errorMessage = errMsg
        )
        adapter.notifyItemChanged(index)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun addMessage(message: Message) {
        messages.add(message)
        adapter.notifyItemInserted(messages.size - 1)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun addBotMessage(text: String) = addMessage(Message(content = text, isUser = false))

    private fun addErrorMessage(text: String) =
        addMessage(Message(content = text, isUser = false, isError = true, errorMessage = text))

    private fun clearMessages() {
        val size = messages.size
        messages.clear()
        adapter.notifyItemRangeRemoved(0, size)
        showWelcomeMessage()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun deleteMessage(position: Int) {
        if (position in messages.indices) {
            messages.removeAt(position)
            adapter.notifyItemRemoved(position)
            adapter.notifyItemRangeChanged(position, messages.size - position)
        }
    }

    private fun showEditDialog(position: Int, oldText: String) {
        val editText = android.widget.EditText(this).apply {
            setText(oldText)
            setSelection(oldText.length)
        }
        AlertDialog.Builder(this)
            .setTitle("编辑消息")
            .setView(editText)
            .setNegativeButton("取消", null)
            .setPositiveButton("重新发送") { _, _ ->
                val newText = editText.text.toString().trim()
                if (newText.isNotBlank() && newText != oldText) {
                    if (position in messages.indices) {
                        messages.removeAt(position)
                        adapter.notifyItemRemoved(position)
                    }
                    sendMessage(newText)
                }
            }
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }

    // ════════════════════════════════════════
    // 快捷操作栏（豆包风格工具条）
    // ════════════════════════════════════════

    /**
     * 设置底部快捷工具栏（📈股票 / ⚡快速 / 🔍深入研究 / 🖼️图片 / ⋮更多）
     *
     * - **📈 股票**：弹出同花顺风格股票选择面板（A股/ETF/热门/涨幅/跌幅），
     *   选中后自动填入输入框
     * - **⚡ 快速**：常用查询快捷入口（大盘/热点/涨停板等）
     * - **🔍 深入研究**：在当前输入内容前加「深入分析」前缀
     * - **🖼️ 图片**：图片解析功能（预留）
     * - **⋮ 更多**：额外功能菜单
     *
     * 同时启动后台预取调度器（提升二次查询响应速度）。
     */
    private fun setupQuickActions() {
        // ── 启动后台预取（绑定生命周期，Activity 销毁自动停止）──
        queryEngine.startPrefetch(lifecycleScope)

        // ── 📈 股票选择器 ──
        binding.btnStocks.setOnClickListener {
            showStockBrowser()
        }

        // ── ⚡ 快速查询 ──
        binding.btnQuick.setOnClickListener {
            showQuickQueryMenu()
        }

        // ── 🔍 深入研究 ──
        binding.btnResearch.setOnClickListener {
            val current = binding.etInput.text.toString().trim()
            if (current.isEmpty()) {
                binding.etInput.setText("请深入分析：")
            } else {
                binding.etInput.setText("请深入分析：$current")
            }
            binding.etInput.setSelection(binding.etInput.text.length)
            binding.etInput.requestFocus()
        }

        // ── 🖼️ 图片（预留）──
        binding.btnImage.setOnClickListener {
            Toast.makeText(this, "📸 图片解析功能开发中，敬请期待~", Toast.LENGTH_SHORT).show()
        }

        // ── ⋮ 更多 ──
        binding.btnMoreActions.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("📊 今日热门主题")
            popup.menu.add("💹 沪深300行情")
            popup.menu.add("⭐ 自选股")
            popup.menu.add("📋 查询历史记录")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "📊 今日热门主题" -> {
                        sendMessage("今天A股市场有哪些热门主题和板块？")
                        true
                    }
                    "💹 沪深300行情" -> {
                        sendMessage("沪深300指数今日表现如何？主要权重股涨跌情况？")
                        true
                    }
                    "⭐ 自选股" -> {
                        Toast.makeText(this, "自选股功能开发中~", Toast.LENGTH_SHORT).show()
                        true
                    }
                    "📋 查询历史记录" -> {
                        val summary = queryEngine.queryHistory.getSummary()
                        AlertDialog.Builder(this)
                            .setTitle("📋 查询历史")
                            .setMessage(if (summary.contains("[]")) "暂无历史查询记录" else summary)
                            .setPositiveButton("确定", null)
                            .setNeutralButton("清除历史") { _, _ ->
                                queryEngine.queryHistory.clear()
                                Toast.makeText(this, "历史记录已清除", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    /**
     * 弹出同花顺风格股票选择面板
     * 用户选中后，自动填入输入框：「帮我分析 贵州茅台（600519）」
     */
    private fun showStockBrowser() {
        val browser = StockBrowserFragment.newInstance()
        browser.onStockSelected = { stock ->
            val code6 = stock.code.takeLast(6)
            val query = "帮我分析 ${stock.name}（$code6）的实时行情和投资价值"
            binding.etInput.setText(query)
            binding.etInput.setSelection(query.length)
            binding.etInput.requestFocus()
            // 触发后台预取，加速后续该股票的查询
            queryEngine.prefetchScheduler.recordQuery(listOf(stock.code))
        }
        browser.show(supportFragmentManager, "stock_browser")
    }

    /**
     * 快速查询菜单 - 常见问题一键发送
     */
    private fun showQuickQueryMenu() {
        val queries = arrayOf(
            "📈 分析当前A股大盘走势",
            "🔥 今日热门板块有哪些",
            "💎 帮我分析贵州茅台600519",
            "🚀 商业航天产业链前10支股票",
            "⚡ AI算力/半导体板块实时行情",
            "🏦 今日资金流向分析"
        )
        AlertDialog.Builder(this)
            .setTitle("⚡ 快速查询")
            .setItems(queries) { _, which ->
                val raw = queries[which]
                val text = raw.substring(raw.indexOfFirst { it == ' ' } + 1)
                sendMessage(text)
            }
            .show()
    }

    // ════════════════════════════════════════
    // 左侧抽屉（历史对话 + 账号）
    // ════════════════════════════════════════

    /**
     * 初始化左侧抽屉：
     * - btnDrawer → 打开抽屉
     * - btnNewChat/btnNewChatDrawer → 新建会话
     * - rvHistory → 显示历史会话列表
     * - layoutAccount → 账号信息（点击弹出设置）
     *
     * 右滑手势由 DrawerLayout 原生支持，无需额外设置。
     */
    private fun setupDrawer() {
        // 初始化历史列表适配器
        historyAdapter = HistoryAdapter(historySessions) { session ->
            // 点击历史会话 → 提示（目前内存模式，重启后清空）
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            Toast.makeText(this, "💬 ${session.title}", Toast.LENGTH_SHORT).show()
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter

        // 打开抽屉按钮（标题栏左侧 ≡ 图标）
        binding.btnDrawer.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // 新建对话（标题栏）
        binding.btnNewChat.setOnClickListener {
            startNewSession()
        }

        // 新建对话（抽屉内）
        binding.btnNewChatDrawer.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startNewSession()
        }

        // 账号区域点击 → 显示设置提示
        binding.layoutAccount.setOnClickListener {
            Toast.makeText(this, "⚙️ 账号设置功能开发中~", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 开始新会话：保存当前对话到历史，清空消息，重置状态
     */
    private fun startNewSession() {
        // 将当前对话的第一条用户消息作为会话标题保存
        val firstUserMsg = messages.firstOrNull { it.isUser }?.content
        if (firstUserMsg != null && firstUserMsg.isNotBlank()) {
            val title = if (firstUserMsg.length > 20) firstUserMsg.take(20) + "…" else firstUserMsg
            val timestamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            historySessions.add(0, ChatSession(title, timestamp))
            historyAdapter.notifyItemInserted(0)
        }
        // 清空消息，开始新会话
        clearMessages()
        isFirstMessageInSession = true
    }

    /**
     * 返回键：如果抽屉打开则关闭，否则正常返回
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    // ════════════════════════════════════════
    // 历史会话数据类 + Adapter
    // ════════════════════════════════════════

    /** 聊天会话摘要（显示在左侧历史抽屉中）*/
    data class ChatSession(
        val title: String,       // 会话标题（取第一条用户消息前20字）
        val timestamp: String    // 时间戳 "MM-dd HH:mm"
    )

    /**
     * 历史会话列表 Adapter
     *
     * 每行显示：💬 会话标题（左）+ 时间戳（右）
     */
    inner class HistoryAdapter(
        private val sessions: List<ChatSession>,
        private val onClick: (ChatSession) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTitle: TextView = itemView.findViewById(android.R.id.text1)
            val tvTime: TextView = itemView.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            // 使用系统双行列表布局
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val session = sessions[position]
            holder.tvTitle.text = "💬 ${session.title}"
            holder.tvTitle.textSize = 14f
            holder.tvTitle.setTextColor(Color.parseColor("#222222"))
            holder.tvTitle.maxLines = 1
            holder.tvTitle.ellipsize = android.text.TextUtils.TruncateAt.END

            holder.tvTime.text = session.timestamp
            holder.tvTime.textSize = 11f
            holder.tvTime.setTextColor(Color.parseColor("#AAAAAA"))

            // 悬停/点击背景
            holder.itemView.setOnClickListener { onClick(session) }

            // 斑马纹
            holder.itemView.setBackgroundColor(
                if (position % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE
            )
        }

        override fun getItemCount() = sessions.size
    }
}
