package com.chin.stockanalysis.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
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
import com.chin.stockanalysis.conversation.ConversationEntity
import com.chin.stockanalysis.conversation.ConversationRepository
import com.chin.stockanalysis.databinding.FragmentChatBinding
import com.chin.stockanalysis.memory.KeyMemoryManager
import com.chin.stockanalysis.memory.KeyMemoryEntity
import com.chin.stockanalysis.news.NewsFactorManager
import com.chin.stockanalysis.news.NewsFactorEntity
import com.chin.stockanalysis.ai.ConnectionPreWarmPool
import com.chin.stockanalysis.ai.SmartContextWindow
import com.chin.stockanalysis.ai.IntentPredictionEngine
import com.chin.stockanalysis.ai.BackgroundPredictor
import com.chin.stockanalysis.ai.AiProbe
import com.chin.stockanalysis.ai.AiOrchestrator
import com.chin.stockanalysis.ai.DataCompletenessChecker
import com.chin.stockanalysis.stock.StockQueryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ## 聊天 Tab（豆包风格 v9.0）
 *
 * v9.0 新增:
 * - ➕ 菜单（相机/相册/文件）替代 📷
 * - 圆润小号按钮 (36dp)
 * - 加载状态显示使用的 AI 组合
 * - 多 AI 并行编排 (AiOrchestrator)
 * - 追问延迟到回复完成后
 * - 过滤 "null" 字符串
 * - 基于 dialog_techniques 改进的 system prompt
 */
class ChatTabFragment : Fragment() {

    companion object {
        private const val TAG = "ChatTabFragment"
        private const val PREFS_NAME = "chat_prefs"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_welcome_done"

        private val BASE_SYSTEM_PROMPT = """你是一个专业的A股股票投资分析助手（角色: 量化分析+投资顾问）。

## 核心能力
1. 分析股票实时行情，解读K线图和技术指标
2. 提供量化策略和基本面分析，输出结构化数据 (JSON/表格)
3. 解释股票术语和交易规则 (T+1, 涨跌停10%等)
4. 识别股票代码、名称、拼音搜索
5. 主动建议备选方案: 给出分析同时列出1-2个可操作的后续方向

## 股票敏感度规则
- 6位数字 → 极可能是股票代码，优先查询
- 中文名称/拼音缩写 → 优先匹配股票池
- 模糊输入时主动提示正确名称

## 注入数据使用规则
- 【实时行情数据】中的最新价是实时数据，严格基于此分析
- 【股票匹配结果】→ 直接展示匹配的股票信息
- 无注入数据时基于训练知识回答

## 交易日验证规则 (仅当用户隐含要求最新数据时执行)
- 用户明确指定日期/历史分析（如"去年"、"2024年"、"近一月走势"）→ 直接基于注入数据回答，无需验证是否为最近交易日
- 用户隐含要求实时/当前数据（如"现在多少钱"、"最近怎么样"、"分析一下"）→ 必须验证注入数据日期
  - 如果数据日期 = 最近交易日 → 正常进行全面分析
  - 如果数据日期 ≠ 最近交易日 → 回复: "📅 当前数据日期不是最近交易日，无法获取最新数据进行分析。"

## 输出格式规范
- 多只股票对比时输出简洁表格:
| 名称 | 代码 | 价格 | 涨跌 | 核心逻辑 |
|------|------|------|------|---------|
- 程序化解析时输出 JSON

## 回复规范
- 中文回答，专业易懂，适当emoji
- 投资有风险，入市需谨慎
- 提及个股必须输出最新价格: "贵州茅台(600519) 最新价 1680.50 元"
- 分段引导: 复杂分析拆成小步，每步给出明确结论
- 透明可解释: 给出建议时附简短理由"""
    }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ChatAdapter
    private val messages: MutableList<Message> = mutableListOf()

    private var currentStreamingJob: Job? = null
    private var apiProvider: ApiProvider? = null
    private var tts: TextToSpeech? = null

    private val queryEngine: StockQueryEngine by lazy { StockQueryEngine.create(requireContext(), skillOrchestrator) }

    private lateinit var convRepo: ConversationRepository
    private lateinit var memoryManager: KeyMemoryManager

    private var currentConvId: String = System.currentTimeMillis().toString()
    private var hasAutoTitle = false
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var followUpSuggestions: List<KeyMemoryManager.FollowUpSuggestion> = emptyList()

    // ═══ v9.0 ═══
    private val smartContext by lazy { SmartContextWindow(queryEngine) }
    private val intentEngine = IntentPredictionEngine()
    private val backgroundPredictor by lazy { BackgroundPredictor(memoryManager, smartContext) }
    private val orchestrator = AiOrchestrator()

    // ═══ v10.0: Skill 系統 ═══
    private val skillEngine by lazy { com.chin.stockanalysis.skill.SkillEngine(requireContext()) }
    private val skillOrchestrator by lazy { com.chin.stockanalysis.skill.SkillOrchestrator(skillEngine) }

    // 多 AI Provider（由 AiProbe 注入）
    private var secondaryProvider: ApiProvider? = null
    private var tertiaryProvider: ApiProvider? = null
    /** ⚡ AI 增强开关 — 开启时使用双 AI，关闭时仅用用户选择的 AI */
    private var aiBoostEnabled = false

    // HotSectors 缓存 — 30s 内不重复查 DB
    private var cachedHotSectorsText: String? = null
    private var cachedHotSectorsTime: Long = 0L
    private var hotSectorsHideJob: Job? = null

    // ════════════════════════════════════════
    // 生命周期
    // ════════════════════════════════════════

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        convRepo = ConversationRepository(requireContext())
        memoryManager = KeyMemoryManager(requireContext())
        initProvider()
        initAiProbe()
        setupRecyclerView()
        setupInput()
        setupTitleBar()
        initTts()
        showWelcomeMessage()
        showHotSectors()
    }

    override fun onResume() { super.onResume(); initProvider() }
    override fun onPause() { super.onPause(); cancelApiCall() }
    override fun onStop() { super.onStop(); cancelApiCall(); saveCurrentConversation() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
    override fun onDestroy() { super.onDestroy(); cancelApiCall(); tts?.shutdown() }

    private fun cancelApiCall() { currentStreamingJob?.cancel(); currentStreamingJob = null; apiProvider?.cancel() }

    // ════════════════════════════════════════
    // 初始化
    // ════════════════════════════════════════

    private fun initProvider() { apiProvider = ApiConfigManager.getInstance(requireContext()).createCurrentProvider() }

    private fun initAiProbe() {
        val configManager = ApiConfigManager.getInstance(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            AiProbe.runProbe(configManager) { result ->
                when (result) {
                    is AiProbe.Result.Ready -> {
                        apiProvider = result.primary.provider
                        // 仅当 secondary 与 primary 是不同 Provider 时才赋值
                        if (result.secondary != null && result.secondary.config.id != result.primary.config.id) {
                            secondaryProvider = result.secondary.provider
                            Log.i(TAG, "🎯 AI探针: 前台=${result.primary.config.name} | 后台=${result.secondary.config.name}")
                        } else {
                            Log.i(TAG, "🎯 AI探针: 单AI模式 — ${result.primary.config.name}")
                        }
                    }
                    is AiProbe.Result.None -> Log.w(TAG, "AI探针: 无可用 Provider")
                }
            }
        }
    }

    private fun initTts() { tts = TextToSpeech(requireContext()) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.CHINESE } }

    // ════════════════════════════════════════
    // UI 设置
    // ════════════════════════════════════════

    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)
        adapter.onCopyMessage = { text -> (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msg", text)); Toast.makeText(requireContext(), "✅ 已复制", Toast.LENGTH_SHORT).show() }
        adapter.onEditMessage = { position, _ -> showEditMessageDialog(position) }
        adapter.onDeleteMessage = { position -> if (position in messages.indices) { messages.removeAt(position); adapter.notifyItemRemoved(position) } }
        adapter.onUndoMessage = { position -> undoUserMessage(position) }
        adapter.onPlayVoice = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "play") }
        adapter.onFavorite = { text -> Toast.makeText(requireContext(), "⭐ 已收藏", Toast.LENGTH_SHORT).show() }
        adapter.onShare = { text -> startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }, "分享到")) }
        adapter.onRegenerate = { position -> regenerateMessage(position) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.recyclerView.adapter = adapter
    }

    private fun setupInput() {
        // ⚡ AI 增强开关
        binding.btnAiBoost.setOnClickListener {
            aiBoostEnabled = !aiBoostEnabled
            val color = if (aiBoostEnabled) "#FF6600" else "#AAAAAA"
            binding.btnAiBoost.setColorFilter(android.graphics.Color.parseColor(color))
            Log.d(TAG, "⚡ AI增强: ${if (aiBoostEnabled) "开 — 双AI模式" else "关 — 单AI模式"}")
        }
        // 发送按钮点击
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isEmpty()) { Toast.makeText(requireContext(), "请输入内容", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            sendMessage(text)
        }
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { val t = binding.etInput.text.toString().trim(); if (t.isNotEmpty()) sendMessage(t); true } else false
        }
        binding.btnVoice.setOnClickListener { Toast.makeText(requireContext(), "🎤 语音输入开发中", Toast.LENGTH_SHORT).show() }

        // 输入时隐藏热门板块 + 切换 +/🎤 → ↑
        binding.etInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasText = !s.isNullOrBlank()
                if (hasText) {
                    binding.frameHotSectors.visibility = View.GONE  // 用户输入后隐藏热门板块
                    binding.btnAiBoost.visibility = View.GONE
                    binding.btnVoice.visibility = View.GONE
                    binding.btnSend.setImageResource(com.chin.stockanalysis.R.drawable.ic_send_arrow)
                    intentEngine.onInputChanged(s.toString(), viewLifecycleOwner.lifecycleScope) { intent -> Log.d(TAG, "🔮 $intent") }
                } else {
                    binding.btnAiBoost.visibility = View.VISIBLE
                    binding.btnVoice.visibility = View.VISIBLE
                    binding.btnSend.setImageResource(android.R.drawable.ic_input_add)
                    intentEngine.cancel()
                    // 输入清空时重新显示热门板块（取消旧定时器，重新 10s 倒计时）
                    showHotSectors()
                }
            }
        })
    }

    private fun setupTitleBar() {
        queryEngine.startPrefetch(viewLifecycleOwner.lifecycleScope)
        binding.btnDrawer.setOnClickListener { showConversationHistory() }
        binding.btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view)
            popup.menu.add("修改标题"); popup.menu.add("清空对话"); popup.menu.add("已记忆的偏好"); popup.menu.add("清除偏好记忆"); popup.menu.add("数据源诊断")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "修改标题" -> { showEditTitleDialog(); true }
                    "清空对话" -> { startNewConversation(); true }
                    "已记忆的偏好" -> { AlertDialog.Builder(requireContext()).setTitle("📌 已记忆偏好").setMessage(queryEngine.userPrefManager.getPreferenceSummary()).setPositiveButton("确定", null).show(); true }
                    "清除偏好记忆" -> { AlertDialog.Builder(requireContext()).setTitle("清除偏好记忆").setMessage("确定清除所有已记忆的过滤条件吗？").setNegativeButton("取消", null).setPositiveButton("确定清除") { _, _ -> queryEngine.userPrefManager.clearAllPreferences(); Toast.makeText(requireContext(), "✅ 已清除", Toast.LENGTH_SHORT).show() }.show(); true }
                    "数据源诊断" -> { Log.d(TAG, queryEngine.getRepository().getDiagnostics()); Toast.makeText(requireContext(), "诊断信息已打印到日志", Toast.LENGTH_SHORT).show(); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    /**
     * 动态控制今日热点 View 的显示/隐藏
     * @param show true=显示（重新加载数据并启动10s倒计时），false=隐藏
     */
    fun toggleHotSectors(show: Boolean) {
        if (show) {
            showHotSectors()
        } else {
            hotSectorsHideJob?.cancel()
            binding.frameHotSectors.visibility = View.GONE
        }
    }

    /** 
     * 获取热门板块+交易日涨幅Top5。显示 10s 后自动隐藏。
     * 先用DB+硬编码秒出，后台AI更新并缓存30s。
     */
    private fun showHotSectors() {
        // 取消之前的隐藏任务
        hotSectorsHideJob?.cancel()
        
        // 显示 frameHotSectors（外层容器），内部 layoutHotSectors 自动可见
        binding.frameHotSectors.visibility = View.VISIBLE
        binding.tvHotSectors.text = "📊 正在加载热门板块数据..."
        
        // 10s 后自动隐藏
        hotSectorsHideJob = lifecycleScope.launch {
            delay(10_000L)
            if (isAdded) requireActivity().runOnUiThread {
                binding.frameHotSectors.visibility = View.GONE
            }
        }
        
        // 30s 缓存
        val now = System.currentTimeMillis()
        
        if (cachedHotSectorsText != null && (now - cachedHotSectorsTime) < 30_000L) {
            binding.tvHotSectors.text = cachedHotSectorsText
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 热门板块 Top3（优先用全局缓存，缓存空时直接网络请求兜底）
                var hotSectors = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.conceptSectors
                    .sortedByDescending { it.changePercent }.take(3)
                if (hotSectors.isEmpty()) {
                    // 缓存未就绪，直接网络请求兜底
                    val source = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource()
                    hotSectors = source.fetchSectorsByTypeDirect(type = 3, topN = 3)
                        .sortedByDescending { it.changePercent }.take(3)
                }
                val sectorLines = if (hotSectors.isNotEmpty()) {
                    hotSectors.withIndex().joinToString("\n") { (i, s) ->
                        val emoji = if (s.changePercent > 0) "📈" else "📉"
                        val sign = if (s.changePercent > 0) "+" else ""
                        "${i + 1}. $emoji ${s.name} $sign${"%.2f".format(s.changePercent)}%"
                    }
                } else "暂无实时板块数据"

                // 2. 涨幅Top5（DB+硬编码秒出，后台AI更新）
                val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
                val recentTradingDay = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().toString()
                val snapshots = db.dailySnapshotDao().getByDate(recentTradingDay)
                val checker = DataCompletenessChecker(db)
                val stockLines = if (snapshots.isNotEmpty()) {
                    val top5 = snapshots.sortedByDescending { it.changePct }.take(5)
                    val sectorCache = mutableMapOf<String, String>()
                    for (snap in top5) {
                        sectorCache[snap.code] = checker.ensureSectorFast(snap.code, snap.name)
                    }
                    top5.joinToString("\n") { snap ->
                        val emoji = if (snap.changePct > 0) "📈" else "📉"
                        val sign = if (snap.changePct > 0) "+" else ""
                        val sector = (sectorCache[snap.code] ?: "-").replace("null", "")
                        "$emoji ${snap.name} $sign${"%.2f".format(snap.changePct)}%  $sector"
                    }
                } else "暂无交易日数据"

                val text = "$sectorLines\n\n🔥 最近交易日($recentTradingDay) 涨幅Top5:\n$stockLines"

                // 缓存
                cachedHotSectorsText = text
                cachedHotSectorsTime = System.currentTimeMillis()

                if (isAdded) requireActivity().runOnUiThread {
                    binding.tvHotSectors.text = text
                }

                // 后台用 AI 检查并更新板块数据（低优先级，不阻塞UI）
                if (apiProvider != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (snap in snapshots.sortedByDescending { it.changePct }.take(5)) {
                            checker.ensureSector(snap.code, snap.name, apiProvider)
                        }
                        // 清除缓存让下次显示时用最新数据
                        cachedHotSectorsTime = 0L
                    }
                }
            } catch (e: Exception) { 
                Log.w(TAG, "热门板块获取失败: ${e.message}") 
                if (isAdded) requireActivity().runOnUiThread {
                    binding.tvHotSectors.text = "获取热门板块失败: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun showWelcomeMessage() {
        if (messages.isNotEmpty()) return
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) {
            addBotMessage("👋 你好！我是你的智能助手！\n当前模型: ${apiProvider?.config?.name ?: "默认"}\n\n💡 试试：\n• 600519 现在多少钱？\n• 帮我分析贵州茅台\n• 商业航天产业链有哪些股票？\n\n📖 ≡ 查看历史对话")
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
        }
    }

    // ════════════════════════════════════════
    // 历史对话
    // ════════════════════════════════════════

    private fun showConversationHistory() {
        val sheet = ConversationListFragment()
        sheet.onNewChatClick = { startNewConversation() }
        sheet.onSessionClick = { sessionId -> lifecycleScope.launch { loadConversation(sessionId) } }
        sheet.onDeleteSession = { sessionId -> lifecycleScope.launch { convRepo.deleteConversation(sessionId); Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show() } }
        sheet.convRepo = convRepo; sheet.show(childFragmentManager, "conversation_history")
    }

    private suspend fun loadConversation(convId: String) {
        val entity = withContext(Dispatchers.IO) { convRepo.getById(convId) } ?: run { Toast.makeText(requireContext(), "对话不存在", Toast.LENGTH_SHORT).show(); return }
        cancelApiCall(); saveCurrentConversationSync()
        messages.clear(); messages.addAll(ConversationRepository.deserializeMessages(entity.messagesJson)); adapter.notifyDataSetChanged()
        currentConvId = entity.id; sessionStartTime = entity.timestamp; hasAutoTitle = messages.any { it.isUser }
        binding.tvChatTitle.text = entity.title; binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun hasMeaningfulContent(): Boolean = messages.filter { it.isUser && !it.isStreaming && !it.isError && it.content.isNotBlank() }.any { !isTrivialMessage(it.content) }

    private fun saveCurrentConversation() {
        if (!hasMeaningfulContent()) return
        val realMessages = messages.filter { !it.isStreaming && !it.isError && it.content.isNotBlank() }
        lifecycleScope.launch { withContext(Dispatchers.IO) { convRepo.saveConversation(ConversationEntity(id = currentConvId, title = ConversationRepository.generateTitleAndSubtitle(realMessages).first, subtitle = "", timestamp = sessionStartTime, messagesJson = ConversationRepository.serializeMessages(realMessages))) } }
    }

    private fun saveCurrentConversationSync() {
        if (!hasMeaningfulContent()) return
        val realMessages = messages.filter { !it.isStreaming && !it.isError && it.content.isNotBlank() }
        lifecycleScope.launch { withContext(Dispatchers.IO) { convRepo.saveConversation(ConversationEntity(id = currentConvId, title = ConversationRepository.generateTitleAndSubtitle(realMessages).first, subtitle = "", timestamp = sessionStartTime, messagesJson = ConversationRepository.serializeMessages(realMessages))) } }
    }

    private fun startNewConversation() {
        saveCurrentConversation(); messages.clear(); adapter.notifyItemRangeRemoved(0, messages.size.coerceAtLeast(0))
        hasAutoTitle = false; currentConvId = System.currentTimeMillis().toString(); sessionStartTime = System.currentTimeMillis()
        binding.tvChatTitle.text = getString(com.chin.stockanalysis.R.string.app_name)
        showWelcomeMessage(); showHotSectors()
    }

    // ════════════════════════════════════════
    // 消息发送
    // ════════════════════════════════════════

    private fun sendMessage(userText: String) {
        addMessage(Message(content = userText, isUser = true))
        binding.etInput.setText(""); hideKeyboard()
        if (!hasAutoTitle) { hasAutoTitle = true; binding.tvChatTitle.text = extractSmartTitle(userText) }

        // ═══ v10.0: 動態建立 Skill 意圖處理 ═══
        val skillCreationResult = skillOrchestrator.handleDynamicSkillCreation(userText)
        if (skillCreationResult != null) {
            addBotMessage(skillCreationResult)
            Log.i(TAG, "🆕 動態 Skill 建立: $userText")
            return
        }

        // 用户指定 AI 提供者（如 "用豆包分析"、"切换到deepseek"等）
        var provider = detectAndSwitchProvider(userText) ?: apiProvider
        if (provider == null) { addErrorMessage("❌ AI 服务未初始化"); return }

        // 清除行情缓存确保获取实时数据
        smartContext.invalidateAll()

        // v9.0: AI 组合名称（AI增强开 + 有不同 secondary → 双AI，否则单AI）
        val secondary = secondaryProvider
        val useDualAi = aiBoostEnabled && secondary != null && secondary.config.id != provider.config.id
        val aiLabel = if (useDualAi) {
            "${provider.config.name.take(8)} + ${secondary!!.config.name.take(8)}"
        } else {
            provider.config.name.take(10)
        }
        val estimatedKeywords = if (userText.length > 10) 3 + userText.length / 10 else 2

        val streamingMessage = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "🤖 $aiLabel\n正在搜索${estimatedKeywords}个关键字...")
        addMessage(streamingMessage)
        val streamingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            // 800ms 后更新加载状态
            delay(800L)
            if (isAdded && streamingIndex in messages.indices && messages[streamingIndex].isStreaming) {
                requireActivity().runOnUiThread {
                    messages[streamingIndex] = messages[streamingIndex].copy(
                        loadingStatus = "🤖 $aiLabel\n正在搜索${estimatedKeywords}个关键字，参考2篇资料")
                    adapter.notifyItemChanged(streamingIndex)
                }
            }

            // 并行: memory + smartContext(行情) + orchestrator(多AI)
            val memoryDeferred = async(Dispatchers.IO) { memoryManager.buildMemorySuffix() }
        val dataDeferred = async(Dispatchers.IO) {
                smartContext.getOrBuild(userText = userText, baseSystemPrompt = BASE_SYSTEM_PROMPT,
                    onPreferenceLeaned = { _ -> if (isAdded) requireActivity().runOnUiThread { Toast.makeText(requireContext(), "📌 已记住你的偏好", Toast.LENGTH_SHORT).show() } })
            }
            // 多 AI 分析（仅当 AI增强开关开启 + secondary 可用时）
            val multiAnalysis = if (useDualAi && secondary != null) {
                orchestrator.fetchMultiAnalysis(provider, secondary, null, userText)
            } else ""

            val memorySuffix = memoryDeferred.await()
            val dataPrompt = dataDeferred.await()
            val finalSystemPrompt = dataPrompt + memorySuffix + multiAnalysis
            val history = messages.toList().subList(0, streamingIndex)
            sendWithRetry(provider, history, finalSystemPrompt, streamingIndex, 3)
        }
    }

    private suspend fun sendWithRetry(provider: ApiProvider, history: List<Message>, systemPrompt: String, streamingIndex: Int, maxRetries: Int, attempt: Int = 1) {
        val accumulated = StringBuilder()
        try {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                provider.sendMessageStream(messages = history, systemPrompt = systemPrompt,
                    onSuccess = { chunk ->
                        val sanitized = chunk.replace("null", "") // v9.0: 过滤 "null" 字符串
                        accumulated.append(sanitized)
                        if (isAdded) requireActivity().runOnUiThread {
                            if (streamingIndex in messages.indices && messages[streamingIndex].isStreaming) {
                                val msg = messages[streamingIndex]
                                messages[streamingIndex] = msg.copy(content = accumulated.toString(), loadingStatus = null)
                                adapter.notifyItemChanged(streamingIndex)
                                binding.recyclerView.scrollToPosition(messages.size - 1)
                            }
                        }
                    },
                    onComplete = { full ->
                        val finalText = full.ifEmpty { accumulated.toString() }.replace("null", "")
                        if (isAdded) requireActivity().runOnUiThread {
                            completeStreamingMessage(streamingIndex, finalText)
                            onMessageComplete()
                        }
                        cont.resumeWith(Result.success(Unit))
                    },
                    onError = { errMsg -> cont.resumeWith(Result.failure(Exception(errMsg))) })
            }
        } catch (e: Exception) {
            if (attempt < maxRetries && isAdded) {
                requireActivity().runOnUiThread { Toast.makeText(requireContext(), "⏳ 重试中... (${attempt}/$maxRetries)", Toast.LENGTH_SHORT).show() }
                if (accumulated.isNotEmpty()) { requireActivity().runOnUiThread { messages[streamingIndex] = messages[streamingIndex].copy(content = accumulated.toString() + "\n\n_重新获取..._", isStreaming = true); adapter.notifyItemChanged(streamingIndex) } }
                kotlinx.coroutines.delay(1500L)
                sendWithRetry(provider, history, systemPrompt, streamingIndex, maxRetries, attempt + 1)
            } else {
                if (isAdded) requireActivity().runOnUiThread { failStreamingMessage(streamingIndex, "已重试 $maxRetries 次: ${e.message}") }
            }
        }
    }

    private fun regenerateMessage(botPosition: Int) {
        if (botPosition <= 0 || botPosition >= messages.size) return
        val userMsg = (botPosition - 1 downTo 0).firstNotNullOfOrNull { messages[it].takeIf { m -> m.isUser } } ?: return
        repeat(messages.size - botPosition) { messages.removeAt(botPosition) }
        adapter.notifyItemRangeRemoved(botPosition, messages.size - botPosition + 1)
        sendMessage(userMsg.content)
    }

    // ════════════════════════════════════════
    // 消息 UI
    // ════════════════════════════════════════

    private fun completeStreamingMessage(index: Int, content: String) {
        if (index in messages.indices) { messages[index] = messages[index].copy(content = content, isStreaming = false, loadingStatus = null); adapter.notifyItemChanged(index) }
    }

    private fun failStreamingMessage(index: Int, errMsg: String) {
        if (index in messages.indices) { messages[index] = Message(content = "❌ $errMsg", isUser = false, isError = true, errorMessage = errMsg); adapter.notifyItemChanged(index) }
    }

    private fun addMessage(message: Message) { binding.tvNewTopicHint.visibility = View.GONE; messages.add(message); adapter.notifyItemInserted(messages.size - 1); binding.recyclerView.scrollToPosition(messages.size - 1) }
    private fun addBotMessage(text: String) = addMessage(Message(content = text, isUser = false))
    private fun addErrorMessage(text: String) = addMessage(Message(content = text, isUser = false, isError = true))
    private fun hideKeyboard() { (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.etInput.windowToken, 0) }
    private fun isTrivialMessage(text: String): Boolean = text.replace(Regex("[\\s,.，。!！?？、；;：:【】()（）、·]+"), "").length < 3
    fun sendMessageFromExternal(text: String) { binding.etInput.setText(text); binding.btnSend.performClick() }

    private fun showEditMessageDialog(position: Int) {
        if (position !in messages.indices || !messages[position].isUser) return
        val input = EditText(requireContext()).apply { setText(messages[position].content); setSelection(text?.length ?: 0) }
        AlertDialog.Builder(requireContext()).setTitle("修改消息").setView(input).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
            val t = input.text?.toString()?.trim().orEmpty(); if (t.isNotBlank()) { messages[position] = messages[position].copy(content = t); adapter.notifyItemChanged(position) }
        }.show()
    }

    private fun undoUserMessage(position: Int) {
        if (position !in messages.indices || !messages[position].isUser) return
        val removeEnd = (position + 1 until messages.size).firstOrNull { messages[it].isUser } ?: messages.size
        repeat(removeEnd - position) { messages.removeAt(position) }
        adapter.notifyItemRangeRemoved(position, removeEnd - position)
    }

    private fun extractSmartTitle(userText: String): String {
        val text = userText.trim(); if (text.isEmpty()) return "新对话"
        Regex("""[sS][hHzZ](\d{6})""").find(text)?.let { return when { text.contains("分析")||text.contains("走势") -> "${it.groupValues[1]} 技术分析"; text.contains("对比")||text.contains("比较") -> "${it.groupValues[1]} 对比查询"; else -> "${it.groupValues[1]} 行情查询" } }
        Regex("""\b(\d{6})\b""").find(text)?.let { return "${it.groupValues[1]} 查询" }
        listOf("分析","走势","行情","怎么样","多少钱","最新").firstOrNull{text.contains(it)}?.let { val p=text.take(15).replace(it,"").trim(); return if(p.length>=2)"$p $it" else text.take(15) }
        return if(text.length<=15) text else text.take(15)+"…"
    }

    private fun showEditTitleDialog() {
        val input = EditText(requireContext()).apply { setText(binding.tvChatTitle.text); setSelection(text?.length ?: 0); hint = "输入新标题" }
        AlertDialog.Builder(requireContext()).setTitle("✏️ 修改标题").setView(input).setNegativeButton("取消", null).setPositiveButton("确定") { _, _ ->
            val t = input.text?.toString()?.trim(); if (!t.isNullOrBlank()) { binding.tvChatTitle.text = t; saveCurrentConversation() }
        }.show()
    }

    // ════════════════════════════════════════
    // 记忆提取 + 追问（v9.0: 仅回复完成后显示）
    // ════════════════════════════════════════

    private fun onMessageComplete() {
        lifecycleScope.launch {
            try {
                backgroundPredictor.predictAfterConversation(scope = this, messages = messages.toList(), convId = currentConvId,
                    onFollowUpReady = { suggestions -> if (suggestions.isNotEmpty() && isAdded) requireActivity().runOnUiThread { showFollowUpChips(suggestions) } })
                checkAndPromptNewsFactor()
            } catch (e: Exception) { Log.e(TAG, "onMessageComplete 异常: ${e.message}") }
        }
    }

    /** 检测用户输入中的 AI 提供者关键词，切换对应的 Provider */
    private fun detectAndSwitchProvider(userText: String): ApiProvider? {
        val configManager = ApiConfigManager.getInstance(requireContext())
        val text = userText.lowercase()
        val targetId = when {
            text.contains("豆包") || text.contains("doubao") -> "doubao"
            text.contains("deepseek") || text.contains("深度求索") -> "deepseek"
            text.contains("通义") || text.contains("qwen") -> "tongyi"
            text.contains("阿里") || text.contains("ali") -> "ali"
            text.contains("硅基") || text.contains("silicon") -> "silicon_flow"
            else -> null
        }
        if (targetId != null) {
            val config = configManager.getProviderConfig(targetId)
            if (config != null) {
                val newProvider = com.chin.stockanalysis.OpenAiCompatibleProvider(config)
                apiProvider = newProvider
                Log.i(TAG, "🔄 用户指定切换到: ${config.name}")
                return newProvider
            }
        }
        return null
    }

    private suspend fun checkAndPromptNewsFactor() {
        val lastUserMsg = messages.lastOrNull { it.isUser && !it.isStreaming && !it.isError } ?: return
        val newsManager = NewsFactorManager(requireContext())
        val extracted = try { newsManager.tryExtractFromUserMessage(lastUserMsg.content) } catch (_: Exception) { null }
        if (extracted != null && isAdded) { requireActivity().runOnUiThread { showNewsFactorDialog(newsManager, extracted) } }
    }

    private fun showNewsFactorDialog(newsManager: NewsFactorManager, factor: NewsFactorEntity) {
        val emoji = when { factor.sentiment > 0 -> "📈 利好"; factor.sentiment < 0 -> "📉 利空"; else -> "➖ 中性" }
        AlertDialog.Builder(requireContext()).setTitle("📰 新闻因子提取").setMessage("🏢 ${factor.companyName}\n📰 ${factor.title}\n🎯 $emoji").setPositiveButton("保存") { _, _ -> lifecycleScope.launch { try { newsManager.insertFactor(factor) } catch (e: Exception) { /* ignore */ } } }.setNegativeButton("忽略", null).show()
    }

    private fun showFollowUpChips(suggestions: List<KeyMemoryManager.FollowUpSuggestion>) {
        followUpSuggestions = suggestions
        if (suggestions.isNotEmpty()) { addBotMessage(buildString { appendLine("💡 你可能还想问："); for ((i, s) in suggestions.withIndex()) appendLine("${i+1}. ${s.text}") }) }
    }

    private fun onFollowUpConfirmed(suggestion: KeyMemoryManager.FollowUpSuggestion) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { memoryManager.boostMemoryWeight(key = suggestion.memoryKey, value = suggestion.memoryValue, category = suggestion.memoryCategory, convId = currentConvId) } }
        sendMessage(suggestion.text)
    }
}