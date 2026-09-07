package com.chin.stockanalysis.ui

import android.app.AlertDialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.ai.DataCompletenessChecker
import com.chin.stockanalysis.ui.CrossTabBus
import com.chin.stockanalysis.skill.SkillEngine
import com.chin.stockanalysis.skill.SkillOrchestrator
import com.chin.stockanalysis.stock.StockQueryEngine
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.config.AgentRoute
import com.chin.stockanalysis.agent.router.ChatRouter
import com.chin.stockanalysis.agent.core.AgentOrchestrator
import com.chin.stockanalysis.strategy.topology.xml.UseCaseExecution
import com.chin.stockanalysis.agent.core.analyzeStock
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.ai.StockEntityExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
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
 *
 * v12.0 变更:
 * - 接入 AiProviderPool 共享 AI 分配
 * - 接入 SkillEngine/SkillOrchestrator 关键词动态触发选股技巧
 */
class ChatTabFragment : Fragment() {

    companion object {
        private const val TAG = "ChatTabFragment"
        private const val PREFS_NAME = "chat_prefs"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_welcome_done"
        /** P1: 串流渲染节流阀值 (ms) — 避免频繁 UI 更新导致卡顿 */
        private const val STREAMING_THROTTLE_MS = 80L

        private val BASE_SYSTEM_PROMPT = com.chin.stockanalysis.ai.StockAIPromptBuilder.buildBaseSystemPrompt()
    }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ChatAdapter
    private val messages: MutableList<Message> = mutableListOf()

    private var currentStreamingJob: Job? = null
    private var apiProvider: ApiProvider? = null

    /** 分享内容待处理：AI 分析完成后询问是否保存到机构推荐 */
    private var pendingInstitutionalSave = false
    private var sharedExtractedText: String = ""
    private var aiSlot: AiProviderPool.Slot? = null
    private var tts: TextToSpeech? = null
    private var providerInitDone = false
    private var providerLoading = false

    private val skillEngine: SkillEngine by lazy { SkillEngine(requireContext()) }
    private val skillOrchestrator: SkillOrchestrator by lazy { SkillOrchestrator(skillEngine) }
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

    // 多 AI Provider（由 AiProbe 注入 — 仅用于 secondary，primary 由池管理）
    private var secondaryProvider: ApiProvider? = null
    private var tertiaryProvider: ApiProvider? = null
    /** ⚡ AI 增强开关 — 开启时使用双 AI，关闭时仅用用户选择的 AI */
    private var aiBoostEnabled = false

    /** 🎯 分析模式：QUICK=快速, DEEP=深度, EXPERT=专家 */
    private var analysisMode = AnalysisMode.QUICK

    // HotSectors 缓存 — 30s 内不重复查 DB
    private var cachedHotSectorsText: String? = null
    private var cachedHotSectorsTime: Long = 0L
    private var hotSectorsHideJob: Job? = null

    // ═══ 媒体与档案选择器 ═══
    private var photoUri: Uri? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            photoUri?.let { sendMessage("[图片]") }
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    val content = extractTextFromImage(uri)
                    if (content.isImage && content.base64Image.isNotBlank()) {
                        sendMessageWithImage("[请分析这张图片]", content.base64Image, content.mimeType)
                    } else if (content.text.isNotBlank() && content.text != "[图片: unknownxunknown]") {
                        sendMessage("[图片内容]\n${content.text}", skipStockContext = true)
                    } else {
                        sendMessage("[图片] 无法提取内容", skipStockContext = true)
                    }
                }
            }
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    val fileName = getFileNameFromUri(uri) ?: "未知档案"
                    val extractedText = extractTextFromFile(uri, fileName)
                    if (extractedText.isNotBlank()) {
                        sendMessage("[文件: $fileName]\n$extractedText", skipStockContext = true)
                    } else {
                        sendMessage("📎 $fileName", skipStockContext = true)
                    }
                }
            }
        }
    }

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
        setAnalysisMode(analysisMode)  // 初始化提示（快速为默认，无按钮）
        setupTitleBar()
        initTts()
        showWelcomeMessage()
        showHotSectors()
        // 热门板块关闭按钮
        binding.btnCloseHotSectors.setOnClickListener { binding.frameHotSectors.visibility = View.GONE }
        preloadMarketData()
        observeCrossTabBus()
    }

    override fun onResume() { super.onResume(); initProvider() }
    override fun onPause() { super.onPause(); saveCurrentConversation() }
    override fun onStop() { super.onStop(); saveCurrentConversation() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null; hotSectorsHideJob?.cancel() }
    override fun onDestroy() {
        super.onDestroy()
        cancelApiCall()
        tts?.shutdown()
        aiSlot?.let { AiProviderPool.releaseNonBlocking(it) }
    }

    private fun cancelApiCall() { currentStreamingJob?.cancel(); currentStreamingJob = null; apiProvider?.cancel() }

    /** 设置分析模式并更新UI */
    private fun setAnalysisMode(mode: AnalysisMode) {
        analysisMode = mode
        val activeColor = "#1565C0"
        val inactiveColor = "#666666"
        val activeBg = com.chin.stockanalysis.R.drawable.bg_mode_active
        val inactiveBg = com.chin.stockanalysis.R.drawable.bg_mode_inactive

        binding.btnModeDeep.apply {
            setTextColor(android.graphics.Color.parseColor(if (mode == AnalysisMode.DEEP) "#FFFFFF" else inactiveColor))
            background = if (mode == AnalysisMode.DEEP) requireContext().getDrawable(activeBg) else requireContext().getDrawable(inactiveBg)
        }
        binding.btnModeExpert.apply {
            setTextColor(android.graphics.Color.parseColor(if (mode == AnalysisMode.EXPERT) "#FFFFFF" else inactiveColor))
            background = if (mode == AnalysisMode.EXPERT) requireContext().getDrawable(activeBg) else requireContext().getDrawable(inactiveBg)
        }

        val modeHint = when (mode) {
            AnalysisMode.QUICK -> quickAnalysisLabel()
            AnalysisMode.DEEP -> "🔍 多 agent 流水线深度分析"
            AnalysisMode.EXPERT -> "🧭 板块多周期全面深度分析"
        }
        binding.etInput.hint = modeHint
    }

    /** ⚡ 快速模式入口文案：纯本地豆包 useCase 分析，不调用 LLM */
    private fun quickAnalysisLabel(): String = "⚡ 本地快速分析"

    /** 📈 深度模式尾段：多Agent分析 → 短线/中线 usecase pipeline → 适合买入则保存到 AI 精选 */
    private fun maybeRunDeepTail(userText: String) {
        if (analysisMode != AnalysisMode.DEEP) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val tail = withContext(Dispatchers.IO) {
                    com.chin.stockanalysis.agent.chat.DeepPipelineTail.run(ctx, userText)
                }
                if (tail.isNotBlank() && isAdded) {
                    requireActivity().runOnUiThread {
                        addBotMessage(tail)
                        onMessageComplete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "深度尾段失败: ${e.message}")
            }
        }
    }

    // ════════════════════════════════════════
    // 初始化
    // ════════════════════════════════════════

    private fun initProvider() {
        if (providerInitDone || providerLoading) return
        providerLoading = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                aiSlot = AiProviderPool.acquire(requireContext())
                if (isAdded) requireActivity().runOnUiThread {
                    apiProvider = aiSlot?.provider
                    providerInitDone = true
                    Log.i(TAG, "🤖 AI: ${aiSlot?.configName ?: "无"}")
                }
            } finally {
                providerLoading = false
            }
        }
    }

    /** AiProbe 仅用于发现 secondary Provider，不覆盖池分配的 primary */
    private fun initAiProbe() {
        val configManager = ApiConfigManager.getInstance(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            AiProbe.runProbe(configManager) { result ->
                when (result) {
                    is AiProbe.Result.Ready -> {
                        // 不覆盖 apiProvider（池已分配）
                        if (result.secondary != null && result.secondary.config.id != result.primary.config.id) {
                            secondaryProvider = result.secondary.provider
                            Log.i(TAG, "🎯 AI探针: 辅助=${result.secondary.config.name}")
                        }
                    }
                    is AiProbe.Result.None -> Log.w(TAG, "AI探针: 无辅助 Provider")
                }
            }
        }
    }
    private fun initTts() { tts = TextToSpeech(requireContext()) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.CHINESE } }

    private fun preloadMarketData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
                val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().toString()
                val existingCount = db.dailySnapshotDao().getByDate(today).size
                if (existingCount >= 30) { Log.i(TAG, "📊 今日数据已完整 (${existingCount}只)"); return@launch }
                Log.i(TAG, "📊 后台预取全市场数据 (现有${existingCount}只)...")
                val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                val count = fetcher.fetchAllHistoricalData(days = 1)
                Log.i(TAG, "📊 预取完成: $count 条")
                cachedHotSectorsTime = 0L
            } catch (e: Exception) { Log.w(TAG, "预取失败: ${e.message}") }
        }
    }

    // ════════════════════════════════════════
    // UI 设置
    // ════════════════════════════════════════

    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)
        adapter.onCopyMessage = { text -> (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msg", text)); Toast.makeText(requireContext(), "✅ 已复制", Toast.LENGTH_SHORT).show() }
        adapter.onPlayVoice = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "play") }
        adapter.onFavorite = { text -> Toast.makeText(requireContext(), "⭐ 已收藏", Toast.LENGTH_SHORT).show() }
        adapter.onShare = { text -> startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }, "分享到")) }
        adapter.onRegenerate = { position -> regenerateMessage(position) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.recyclerView.itemAnimator = null // 禁用动画，避免 loading view 闪现
        binding.recyclerView.adapter = adapter
    }

    private fun setupInput() {
        // 模式选择按钮（快速为默认，不显示按钮；再次点击深度/专家可切回快速）
        binding.btnModeDeep.setOnClickListener {
            setAnalysisMode(if (analysisMode == AnalysisMode.DEEP) AnalysisMode.QUICK else AnalysisMode.DEEP)
        }
        binding.btnModeExpert.setOnClickListener {
            setAnalysisMode(if (analysisMode == AnalysisMode.EXPERT) AnalysisMode.QUICK else AnalysisMode.EXPERT)
        }
        // 📡 远程：与 PC(exe) / CodeBuddy 互动
        binding.btnModeRemote.setOnClickListener {
            com.chin.stockanalysis.strategy.trade.RemoteControlDialog(requireContext()).show()
        }

        // ⚡ AI增强按钮
        binding.btnAiBoost.setOnClickListener {
            aiBoostEnabled = !aiBoostEnabled
            val color = if (aiBoostEnabled) "#FF6600" else "#AAAAAA"
            binding.btnAiBoost.setColorFilter(android.graphics.Color.parseColor(color))
            Toast.makeText(requireContext(), if (aiBoostEnabled) "⚡ AI增强已开启" else "⚡ AI增强已关闭", Toast.LENGTH_SHORT).show()
        }

        // btnSend: 默认=+菜单，有输入=发送↑
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isEmpty()) {
                // 没有输入 → 展开菜单
                showPlusMenu(it)
            } else {
                sendMessage(text)
            }
        }
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { val t = binding.etInput.text.toString().trim(); if (t.isNotEmpty()) sendMessage(t); true } else false
        }
        binding.btnVoice.setOnClickListener { startVoiceInput() }

        binding.etInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasText = !s.isNullOrBlank()
                if (hasText) {
                    binding.frameHotSectors.visibility = View.GONE
                    // 有输入：隐藏🎤，+变成↑发送箭头
                    binding.btnVoice.visibility = View.GONE
                    binding.btnSend.setImageResource(com.chin.stockanalysis.R.drawable.ic_send_arrow_up)
                    binding.btnSend.imageTintList = null
                    intentEngine.onInputChanged(s.toString(), viewLifecycleOwner.lifecycleScope) { intent -> Log.d(TAG, "🔮 $intent") }
                } else {
                    // 无输入：显示🎤，恢复+图标
                    binding.btnVoice.visibility = View.VISIBLE
                    binding.btnSend.setImageResource(android.R.drawable.ic_input_add)
                    binding.btnSend.setColorFilter(android.graphics.Color.parseColor("#FFFFFF"))
                    intentEngine.cancel()
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

    fun toggleHotSectors(show: Boolean) {
        if (show) showHotSectors() else { hotSectorsHideJob?.cancel(); binding.frameHotSectors.visibility = View.GONE }
    }

    private fun showHotSectors() {
        hotSectorsHideJob?.cancel()
        binding.frameHotSectors.visibility = View.VISIBLE
        binding.tvHotSectors.text = "📊 正在加载热门板块数据..."
        hotSectorsHideJob = lifecycleScope.launch {
            delay(30_000L)
            if (isAdded) requireActivity().runOnUiThread { binding.frameHotSectors.visibility = View.GONE }
        }
        val now = System.currentTimeMillis()
        if (cachedHotSectorsText != null && (now - cachedHotSectorsTime) < 30_000L) { binding.tvHotSectors.text = cachedHotSectorsText; return }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 第一步：获取10个东方财富概念板块涨幅
                var hotSectors = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.conceptSectors
                    .sortedByDescending { it.changePercent }.take(10)
                if (hotSectors.isEmpty()) {
                    val source = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource()
                    hotSectors = source.fetchSectorsByTypeDirect(type = 3, topN = 10).sortedByDescending { it.changePercent }.take(10)
                }

                // 第二步：对每个板块获取涨幅前5的成分股
                val eastSource = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource()
                val allLeaders = mutableMapOf<String, List<com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.LeaderStock>>()
                for (s in hotSectors) {
                    try {
                        allLeaders[s.name] = eastSource.fetchSectorLeaders(s.code, 5)
                    } catch (_: Exception) { allLeaders[s.name] = emptyList() }
                }

                // 第三步：格式化输出
                val sectorLines = if (hotSectors.isNotEmpty()) {
                    val lines = mutableListOf<String>()
                    for (s in hotSectors) {
                        val emoji = if (s.changePercent > 0) "📈" else "📉"
                        val sign = if (s.changePercent > 0) "+" else ""
                        lines.add("$emoji ${s.name} $sign${"%.2f".format(s.changePercent)}%")
                        val leaders = allLeaders[s.name] ?: emptyList()
                        if (leaders.isNotEmpty()) {
                            val stockStr = leaders.map { l ->
                                val cpSign = if (l.changePercent > 0) "+" else ""
                                "${l.name} $cpSign${"%.2f".format(l.changePercent)}%"
                            }.joinToString(" ")
                            lines.add("  ├─ $stockStr")
                        }
                    }
                    // Top5 个股汇总
                    val allStocks = allLeaders.values.flatten().sortedByDescending { it.changePercent }
                    if (allStocks.isNotEmpty()) {
                        lines.add("")
                        lines.add("🔥 涨幅Top5个股:")
                        allStocks.take(5).forEach { l ->
                            val emoji2 = if (l.changePercent > 0) "📈" else "📉"
                            val sign2 = if (l.changePercent > 0) "+" else ""
                            lines.add("$emoji2 ${l.name}(${l.code}) $sign2${"%.2f".format(l.changePercent)}%")
                        }
                    }
                    lines.joinToString("\n")
                } else "暂无实时板块数据"

                // 第五步仍需要 db
                val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())

                // 第五步：保持 Top5 个股行不变
                val recentTradingDay = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().toString()
                val allDates = db.dailySnapshotDao().getAvailableDates(5).sorted()
                val effectiveDate = if (allDates.contains(recentTradingDay)) recentTradingDay else allDates.lastOrNull() ?: recentTradingDay
                Log.i(TAG, "📊 showHotSectors: recentTradingDay=$recentTradingDay, effectiveDate=$effectiveDate, availableDates=${allDates.take(5)}")
                val snapshots = db.dailySnapshotDao().getByDate(effectiveDate)
                Log.i(TAG, "📊 showHotSectors: snapshots.size=${snapshots.size}, effectiveDate=$effectiveDate")
                val checker = DataCompletenessChecker(db)
                val hasEnoughData = snapshots.size >= 5
                val stockLines = if (snapshots.isNotEmpty()) {
                    val top5 = snapshots.sortedByDescending { it.changePct }.take(5)
                    if (!hasEnoughData) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try { com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext()).fetchAllHistoricalData(days = 1); cachedHotSectorsTime = 0L } catch (_: Exception) {}
                        }
                    }
                    val sectorCache = mutableMapOf<String, String>()
                    for (snap in top5) {
                        sectorCache[snap.code] = lookupSectorLabel(snap.code, snap.name, db)
                    }
                    top5.joinToString("\n") { snap ->
                        val emoji = if (snap.changePct > 0) "📈" else "📉"
                        val sign = if (snap.changePct > 0) "+" else ""
                        val sector = (sectorCache[snap.code] ?: "").replace("null", "").trim()
                        val sectorSuffix = if (sector.isNotEmpty()) " [$sector]" else ""
                        val codeShort = snap.code.takeLast(6)
                        "$emoji ${snap.name}($codeShort) $sign${"%.2f".format(snap.changePct)}%$sectorSuffix"
                    }
                } else { lifecycleScope.launch(Dispatchers.IO) { try { com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext()).fetchAllHistoricalData(days = 1); cachedHotSectorsTime = 0L } catch (_: Exception) {} }; "暂无交易日数据（后台拉取中...）" }

                val text = if (!hasEnoughData) "$sectorLines\n\n🔥 最近交易日($effectiveDate) 涨幅Top5:\n$stockLines\n\n📥 数据拉取中" else "$sectorLines\n\n🔥 最近交易日($effectiveDate) 涨幅Top5:\n$stockLines"
                Log.i(TAG, "📊 showHotSectors: effectiveDate=$effectiveDate, top5Snapshot: $stockLines")
                if (hasEnoughData) { cachedHotSectorsText = text; cachedHotSectorsTime = System.currentTimeMillis() }
                if (isAdded) requireActivity().runOnUiThread { binding.tvHotSectors.text = text; binding.tvHotSectors.minHeight = dpToPx(160); binding.tvHotSectors.requestLayout() }
            } catch (e: Exception) { Log.w(TAG, "热门板块获取失败: ${e.message}") }
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
    private fun saveCurrentConversation() { if (!hasMeaningfulContent()) return; val realMessages = messages.filter { !it.isStreaming && !it.isError && it.content.isNotBlank() }; lifecycleScope.launch { withContext(Dispatchers.IO) { convRepo.saveConversation(ConversationEntity(id = currentConvId, title = ConversationRepository.generateTitleAndSubtitle(realMessages).first, subtitle = "", timestamp = sessionStartTime, messagesJson = ConversationRepository.serializeMessages(realMessages))) } } }
    private fun saveCurrentConversationSync() { if (!hasMeaningfulContent()) return; val realMessages = messages.filter { !it.isStreaming && !it.isError && it.content.isNotBlank() }; lifecycleScope.launch { withContext(Dispatchers.IO) { convRepo.saveConversation(ConversationEntity(id = currentConvId, title = ConversationRepository.generateTitleAndSubtitle(realMessages).first, subtitle = "", timestamp = sessionStartTime, messagesJson = ConversationRepository.serializeMessages(realMessages))) } } }

    private fun startNewConversation() {
        saveCurrentConversation(); messages.clear(); adapter.notifyItemRangeRemoved(0, messages.size.coerceAtLeast(0))
        hasAutoTitle = false; currentConvId = System.currentTimeMillis().toString(); sessionStartTime = System.currentTimeMillis()
        binding.tvChatTitle.text = getString(com.chin.stockanalysis.R.string.app_name)
        showWelcomeMessage(); showHotSectors()
    }

    // ════════════════════════════════════════
    // 消息发送
    // ════════════════════════════════════════

    private var lastUserText: String = ""
    /** 暂存最后一张图片的 base64，供 AI 分析时使用 */
    private var lastImageBase64: String? = null

    /**
     * 发送带图片的讯息（使用 base64 data URL）
     */
    private fun sendMessageWithImage(userText: String, base64Image: String, mimeType: String) {
        lastImageBase64 = base64Image
        val displayText = if (userText.isNotBlank()) userText else "[图片]"
        addMessage(Message(content = "🖼️ $displayText", isUser = true))
        binding.etInput.setText(""); hideKeyboard()

        val provider = apiProvider
        if (provider == null) {
            addErrorMessage("❌ AI 尚未连接，请稍候重试")
            return
        }

        // 使用快速模式分析图片
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "🖼️ 正在分析图片...")
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val imageDataUrl = "data:$mimeType;base64,$base64Image"
                val prompt = buildString {
                    appendLine("【图片分析请求】")
                    appendLine("用户上传了一张图片，请分析图片内容并给出详细解答。")
                    appendLine()
                    appendLine("图片数据 (data URL):")
                    appendLine(imageDataUrl.take(100))  // 只提示，实际图片通过其他方式传递
                    appendLine("... (base64 图片数据)")
                    appendLine()
                    appendLine("用户问题: ${userText.ifBlank { "请分析这张图片的内容" }}")
                    appendLine()
                    appendLine("注意：如果当前 AI 模型支援图片分析，请直接分析图片。如果不支援，请告知用户。")
                }
                val history = messages.toList().subList(0, loadingIndex).filter {
                    !it.content.startsWith("🖼️")
                }
                sendWithRetry(provider, history, prompt, loadingIndex, 2)
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "图片分析失败: ${e.message}")
                }
            }
        }
    }

    private fun sendMessage(userText: String, skipStockContext: Boolean = false) {
        // 去重：连续两次相同输入不重复处理
        if (userText == lastUserText && messages.lastOrNull()?.isUser == true) {
            Toast.makeText(requireContext(), "已发送，请勿重复输入", Toast.LENGTH_SHORT).show()
            return
        }
        lastUserText = userText

        // 先尝试意图分发
        if (!skipStockContext && com.chin.stockanalysis.ai.IntentDispatcher.dispatch(userText, requireContext())) {
            addMessage(Message(content = userText, isUser = true))
            binding.etInput.setText(""); hideKeyboard()
            addBotMessage("✅ 指令已执行，正在分析...")
            return
        }
        addMessage(Message(content = userText, isUser = true))
        binding.etInput.setText(""); hideKeyboard()
        if (!hasAutoTitle) { hasAutoTitle = true; binding.tvChatTitle.text = extractSmartTitle(userText) }

        val provider = apiProvider
        if (provider == null) {
            // Provider 尚未就绪：若此前获取失败（providerInitDone=false），先重新发起获取再等待
            if (!providerInitDone && !providerLoading) initProvider()
            currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
                addBotMessage("⏳ AI 正在连接...")
                var waited = 0
                while (apiProvider == null && waited < 100) {
                    kotlinx.coroutines.delay(200L)
                    waited++
                }
                // 移除"连接中"消息
                if (messages.isNotEmpty() && !messages.last().isUser) {
                    messages.removeLast()
                    adapter.notifyItemRemoved(messages.size)
                }
                val ready = apiProvider
                if (ready != null) {
                    sendMessageInternal(userText, ready, isRetry = true, skipStockContext = skipStockContext)
                } else {
                    addErrorMessage("❌ AI 连接超时，请检查网络，并在「设置→AI 配置」确认已填写可用的 API Key 后重试")
                }
            }
            return
        }

        sendMessageInternal(userText, provider, isRetry = false, skipStockContext = skipStockContext)
    }

    private fun sendMessageInternal(userText: String, provider: ApiProvider, isRetry: Boolean, skipStockContext: Boolean = false) {
        if (!isRetry) { } // already added user message
        binding.etInput.setText(""); hideKeyboard()
        if (!hasAutoTitle) { hasAutoTitle = true; binding.tvChatTitle.text = extractSmartTitle(userText) }

        // ⚡ 快速模式：纯本地解析 + 本地分析（豆包 useCase / 板块多周期），不调用 LLM
        if (analysisMode == AnalysisMode.QUICK) {
            handleQuickModeInput(userText)
            return
        }

        // 🧭 专家模式：常规解析优先（不花 token），解析不出再用 LLM 兜底解析用户输入
        if (analysisMode == AnalysisMode.EXPERT) {
            handleExpertModeInput(userText, provider)
            return
        }

        // 🎯 Agent 模式：如果全局开启了 Agent，走 ChatRouter → ChatAgent
        if (FeatureFlagManager.isAgentFramework(FeatureFlagManager.chatRoute)) {
            runAgentAnalysis(userText, provider, skipStockContext)
            return
        }

        // 🎯 新引擎：尝试提取股票代码，走 UnifiedAgentRunner
        val stockCode = extractStockCodeFromText(userText)
        if (stockCode != null) {
            runUnifiedAnalysis(userText, stockCode)
        } else {
            // 无股票代码 → 通用问答（非股票问题、生活/技术等）
            runGeneralChat(userText, provider, skipStockContext)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  ⚡ 快速模式（纯本地，无 LLM）
    // ════════════════════════════════════════════════════════════

    /** ⚡ 快速模式：常规解析用户输入 → 个股走豆包本地深度分析，板块走板块多周期分析；解析不出给出格式提示 */
    private fun handleQuickModeInput(userText: String) {
        val stockCode = extractStockCodeFromText(userText)
        if (stockCode != null) {
            runQuickStockAnalysis(userText, stockCode)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val (sectors, codes) = withContext(Dispatchers.IO) {
                    com.chin.stockanalysis.agent.chat.QuickBuildExpertRunner.parseFocus(ctx, userText)
                }
                if (isAdded) requireActivity().runOnUiThread {
                    if (sectors.isNotEmpty() || codes.isNotEmpty()) {
                        runSectorDeepAnalysis(userText)
                    } else {
                        addBotMessage("🔍 未识别到板块或个股，请输入：板块名称（如 半导体）/ 个股代码（如 300308）/ 个股名称（如 兆易创新）")
                    }
                }
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    addBotMessage("⚠️ 快速分析异常：${e.message?.take(60)}")
                }
            }
        }
    }

    /** ⚡ 快速模式个股分析：豆包体系本地四周期深度分析（不调用 LLM，与详情页「深度分析」一致） */
    private fun runQuickStockAnalysis(userText: String, stockCode: String) {
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "⚡ 豆包体系本地深度分析中（约5-10秒）..."
        )
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1
        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    UseCaseExecution.runStockDeepAnalysis(requireContext().applicationContext, stockCode)
                }
                if (isAdded) requireActivity().runOnUiThread {
                    completeStreamingMessage(loadingIndex, result.report)
                    onMessageComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "runQuickStockAnalysis", e)
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "快速分析异常：${e.message?.take(60)}")
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  🧭 专家模式（常规解析优先，LLM 兜底解析输入）
    // ════════════════════════════════════════════════════════════

    /** 🧭 专家模式：常规解析（代码/名称/板块）优先，解析不出才用 LLM 解析用户输入（省 token） */
    private fun handleExpertModeInput(userText: String, provider: ApiProvider) {
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "🔎 正在识别你的输入..."
        )
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1
        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = requireContext()
                // 1. 常规解析（优先，不消耗 token）
                var sectors = emptyList<String>()
                var codes = emptyList<String>()
                val stockCode = extractStockCodeFromText(userText)
                if (stockCode != null) {
                    codes = listOf(stockCode)
                } else {
                    val parsed = withContext(Dispatchers.IO) {
                        com.chin.stockanalysis.agent.chat.QuickBuildExpertRunner.parseFocus(ctx, userText)
                    }
                    sectors = parsed.first; codes = parsed.second
                }
                // 2. 常规解析失败 → LLM 兜底解析（单次调用）
                if (sectors.isEmpty() && codes.isEmpty()) {
                    val llm = withContext(Dispatchers.IO) { resolveFocusWithLlm(userText, provider) }
                    if (llm != null) { sectors = llm.first; codes = llm.second }
                }
                if (isAdded) requireActivity().runOnUiThread {
                    messages.removeAt(loadingIndex)
                    adapter.notifyItemRemoved(loadingIndex)
                    if (sectors.isEmpty() && codes.isEmpty()) {
                        addBotMessage("🔍 未识别到板块或个股，请输入：板块名称（如 半导体）/ 个股代码（如 300308）/ 个股名称（如 兆易创新）")
                    } else {
                        runSectorDeepAnalysis(userText, preParsed = sectors to codes)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleExpertModeInput", e)
                if (isAdded) requireActivity().runOnUiThread {
                    messages.removeAt(loadingIndex)
                    adapter.notifyItemRemoved(loadingIndex)
                    addBotMessage("⚠️ 专家分析异常：${e.message?.take(60)}")
                }
            }
        }
    }

    /**
     * 用 LLM 单次调用解析用户输入（专家模式兜底）。
     * 返回 (板块列表, 个股代码列表)；无法识别返回 null。
     */
    private suspend fun resolveFocusWithLlm(userText: String, provider: ApiProvider): Pair<List<String>, List<String>>? =
        kotlin.coroutines.suspendCoroutine { cont ->
            provider.sendMessageStream(
                messages = listOf(Message(content = userText, isUser = true)),
                systemPrompt = "你是A股输入识别助手。用户输入可能是个股名称、个股代码、板块名称、指数名称或无关内容。识别用户想分析的对象，只输出一个 JSON 对象，不要任何多余文字或 Markdown 代码块：" +
                    "个股：{\"type\":\"stock\",\"code\":\"603986\",\"name\":\"兆易创新\"}（code 必须是 6 位数字）；" +
                    "板块：{\"type\":\"sector\",\"name\":\"半导体\"}；无法识别：{\"type\":\"unknown\"}。" +
                    "注意：即使输入包含“分析”“看看”等动词也要识别出目标实体；如果既不是个股也不是板块，输出 unknown。",
                onSuccess = {},
                onComplete = { full ->
                    try {
                        val start = full.indexOf('{'); val end = full.lastIndexOf('}')
                        if (start < 0 || end <= start) { cont.resume(null); return@sendMessageStream }
                        val json = full.substring(start, end + 1)
                        val type = Regex("\"type\"\\s*:\\s*\"(\\w+)\"").find(json)?.groupValues?.get(1)
                        when (type) {
                            "stock" -> {
                                val code = Regex("\"code\"\\s*:\\s*\"(\\d{6})\"").find(json)?.groupValues?.get(1)
                                cont.resume(Pair(emptyList(), code?.let { listOf(it) } ?: emptyList()))
                            }
                            "sector" -> {
                                val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                                cont.resume(Pair(name?.let { listOf(it) } ?: emptyList(), emptyList()))
                            }
                            else -> cont.resume(null)
                        }
                    } catch (e: Exception) { cont.resume(null) }
                },
                onError = { cont.resume(null) }
            )
        }

    /** 🤖 Agent 模式：ChatRouter → ChatAgent 智能对话 */
    private fun runAgentAnalysis(userText: String, provider: ApiProvider, skipStockContext: Boolean = false) {
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "🤖 Agent 正在分析...")
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                updateLoadingStatus(loadingIndex, "🤖 正在启动 Agent 框架...")
                val service = ChatRouter.getService()

                val result = withContext(Dispatchers.IO) {
                    service.handleMessage(
                        context = requireContext(),
                        message = userText,
                        onStream = { chunk ->
                            // 专家模式 Pipeline 进度：实时更新对话消息
                            if (isAdded) requireActivity().runOnUiThread {
                                updateLoadingStatus(loadingIndex, chunk)
                            }
                        },
                        analysisMode = analysisMode
                    )
                }
                if (isAdded) requireActivity().runOnUiThread {
                    // 歧义实体：需要用户确认
                    if (result.ambiguousEntities != null && result.ambiguousEntities.isNotEmpty()) {
                        // 移除 loading 消息
                        messages.removeAt(loadingIndex)
                        adapter.notifyItemRemoved(loadingIndex)
                        // 创建 EntityConfirmCard 消息
                        val entityMsg = Message(
                            content = result.response,
                            isUser = false,
                            ambiguousEntities = result.ambiguousEntities,
                            onEntityConfirm = { selected ->
                                // 用户选择后，将选中的股票代码作为新消息发送
                                sendMessage("分析 ${selected.code}")
                            },
                            onEntityCancel = {
                                // 用户取消
                                addBotMessage("已取消")
                            }
                        )
                        addMessage(entityMsg)
                    } else if (result.success) {
                        // 清理原始推理过程、JSON 碎片、thinking 标签
                        val cleanedResponse = cleanAgentResponse(result.response)
                        completeStreamingMessage(loadingIndex, cleanedResponse)
                        onMessageComplete()
                        // 📈 深度模式尾段：短线/中线 usecase → 保存 AI 精选
                        maybeRunDeepTail(userText)
                    } else {
                        failStreamingMessage(loadingIndex, "Agent 分析失败: ${result.response}")
                    }
                }
            } catch (e: UnsupportedOperationException) {
                // LegacyChatService 抛出 UnsupportedOperationException，fallback 到通用问答
                Log.i(TAG, "Agent Legacy 模式，fallback 到通用问答")
                if (isAdded) requireActivity().runOnUiThread {
                    messages.removeAt(loadingIndex)
                    adapter.notifyItemRemoved(loadingIndex)
                    runGeneralChat(userText, provider, skipStockContext)
                }
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "Agent 异常: ${e.message}")
                }
            }
        }
    }

    /** 从用户输入文本中提取股票代码 */
    private fun extractStockCodeFromText(text: String): String? {
        // 匹配带前缀的格式：sh600519, sz000001, bj830799
        val prefixed = Regex("(?i)(sh|sz|bj)(\\d{6})").find(text)
        if (prefixed != null) {
            return StockAnalysisAgent.normalizeStockCode(prefixed.value)
        }
        // 匹配纯6位数字代码
        val pure = Regex("\\b(\\d{6})\\b").find(text)
        if (pure != null) {
            return StockAnalysisAgent.normalizeStockCode(pure.groupValues[1])
        }
        // 降级：尝试用 StockEntityExtractor 解析中文名称
        val resolved = com.chin.stockanalysis.ai.StockEntityExtractor.resolveSync(text)
        if (resolved != null) {
            return StockAnalysisAgent.normalizeStockCode(resolved)
        }
        return null
    }

    /** 根据股票代码解析股票名称 */
    private suspend fun resolveStockName(code: String): String? {
        return try {
            withContext(Dispatchers.IO) {
                val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
                val tradingDay = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().toString()
                val snap = db.dailySnapshotDao().getByDateAndCode(tradingDay, code)
                snap?.name
            }
        } catch (_: Exception) { null }
    }

    /** 🧭 板块多周期全面深度分析：EXPERT/QUICK 模式把用户输入中的板块/个股 → 板块龙头分析（只分析不下单，给评分评价，合适则提示买入） */
    private fun runSectorDeepAnalysis(userText: String, preParsed: Pair<List<String>, List<String>>? = null) {
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "🧭 板块多周期全面深度分析 启动中..."
        )
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val (sectors, codes) = preParsed ?: withContext(Dispatchers.IO) {
                    com.chin.stockanalysis.agent.chat.QuickBuildExpertRunner.parseFocus(ctx, userText)
                }
                val result = com.chin.stockanalysis.agent.chat.QuickBuildExpertRunner.analyzeSectorFocus(
                    ctx = ctx,
                    focusSectors = sectors,
                    focusStocks = codes,
                    onProgress = { status -> updateLoadingStatus(loadingIndex, "🧭 $status") }
                )
                if (isAdded) requireActivity().runOnUiThread {
                    if (result.ok) completeStreamingMessage(loadingIndex, result.message)
                    else failStreamingMessage(loadingIndex, result.message)
                    onMessageComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "runSectorDeepAnalysis", e)
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "板块多周期全面深度分析异常：${e.message?.take(80)}")
                }
            }
        }
    }

    /** 🚀 统一引擎分析：Chat 和详情页共用 UnifiedAgentRunner */
    private fun runUnifiedAnalysis(userText: String, stockCode: String) {
        val modeLabel = when (analysisMode) {
            AnalysisMode.QUICK -> quickAnalysisLabel()
            AnalysisMode.DEEP -> "🔍 多 agent 流水线深度分析"
            AnalysisMode.EXPERT -> "🧭 板块多周期全面深度分析"
        }
        val coreMode = when (analysisMode) {
            AnalysisMode.QUICK -> com.chin.stockanalysis.agent.core.AnalysisMode.QUICK
            AnalysisMode.DEEP -> com.chin.stockanalysis.agent.core.AnalysisMode.DEEP
            AnalysisMode.EXPERT -> com.chin.stockanalysis.agent.core.AnalysisMode.EXPERT
        }

        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "$modeLabel 中..."
        )
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 在协程中解析股票名称
                val stockName = resolveStockName(stockCode)

                val result = withContext(Dispatchers.IO) {
                    AgentOrchestrator(requireContext()).analyzeStock(
                        stockCode = stockCode,
                        stockName = stockName,
                        mode = coreMode,
                        useAgentFramework = false
                    )
                }

                if (isAdded) requireActivity().runOnUiThread {
                    if (result.success) {
                        // 组合最终文本：用户问题 + 分析结果（清理可能残留的 JSON 碎片）
                        val cleanedSummary = result.summaryText
                            .replace(Regex("```json[\\s\\S]*?```"), "")
                            .lines()
                            .filter { line ->
                                val t = line.trim()
                                t.isNotBlank() && !t.matches(Regex("^[{}\\[\\],:]\\s*$"))
                            }
                            .joinToString("\n")
                            .trim()
                        val fullText = buildString {
                            appendLine("**用户**：$userText")
                            appendLine()
                            append(cleanedSummary)
                        }
                        completeStreamingMessage(loadingIndex, fullText)
                    } else {
                        failStreamingMessage(loadingIndex, "$modeLabel 失败: ${result.errorMessage}")
                    }
                    onMessageComplete()
                    // 📈 深度模式尾段：短线/中线 usecase → 保存 AI 精选
                    maybeRunDeepTail(userText)
                }
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "$modeLabel 异常: ${e.message}")
                }
            }
        }
    }


    /** 💬 通用问答：非股票问题的简洁 LLM 对话 */
    private fun runGeneralChat(userText: String, provider: ApiProvider, skipStockContext: Boolean = false) {
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "💬 正在思考...")
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val contextInfo = if (skipStockContext) {
                    ""
                } else {
                    updateLoadingStatus(loadingIndex, "💬 正在搜索相关数据...")
                    withContext(Dispatchers.IO) {
                        smartContext.getOrBuild(userText = userText, baseSystemPrompt = BASE_SYSTEM_PROMPT, onPreferenceLeaned = {})
                    }
                }
                updateLoadingStatus(loadingIndex, "💬 正在回答...")
                val memory = withContext(Dispatchers.IO) { memoryManager.buildMemorySuffix() }

                val prompt = """你是用户的 AI 投资助手，专业简洁。

用户输入：$userText

$contextInfo
$memory

回答要求：
1. 直接回答用户问题，保持专业简洁
2. 如果涉及股票/投资，使用结构化格式（bullet points、表格）
3. 字数控制在 300-800 字
4. 如果涉及投资建议，末尾加免责声明：「以上不构成投资建议」
5. 以上数据来自实时行情，严禁使用训练数据中的旧价格或过时资讯"""

                val history = messages.toList().subList(0, loadingIndex)
                sendWithRetry(provider, history, prompt, loadingIndex, 2)
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "回答失败: ${e.message}")
                }
            }
        }
    }

    private suspend fun sendWithRetry(provider: ApiProvider, history: List<Message>, systemPrompt: String, streamingIndex: Int, maxRetries: Int, attempt: Int = 1) {
        val accumulated = StringBuilder()
        var lastUiUpdate = 0L
        try {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                provider.sendMessageStream(messages = history, systemPrompt = systemPrompt,
                    onSuccess = { chunk ->
                        val sanitized = chunk.replace("null", "")
                        accumulated.append(sanitized)
                        val now = System.currentTimeMillis()
                        // P1: 80ms 节流，避免串流期间过度刷新
                        if (isAdded && (now - lastUiUpdate >= STREAMING_THROTTLE_MS)) {
                            lastUiUpdate = now
                            requireActivity().runOnUiThread {
                                if (streamingIndex in messages.indices && messages[streamingIndex].isStreaming) {
                                    messages[streamingIndex] = messages[streamingIndex].copy(content = accumulated.toString(), loadingStatus = null)
                                    adapter.notifyItemChanged(streamingIndex)
                                    binding.recyclerView.scrollToPosition(messages.size - 1)
                                }
                            }
                        }
                    },
                    onComplete = { full ->
                        // 完成时强制刷新最后一次（确保最后的内容完整显示）
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
        val removeCount = messages.size - botPosition
        if (removeCount <= 0) return
        repeat(removeCount) { messages.removeAt(botPosition) }
        adapter.notifyItemRangeRemoved(botPosition, removeCount)
        sendMessage(userMsg.content)
    }

    // ════════════════════════════════════════
    // 消息 UI
    // ════════════════════════════════════════

    private fun completeStreamingMessage(index: Int, content: String) { if (index in messages.indices) { messages[index] = messages[index].copy(content = content, isStreaming = false, loadingStatus = null); adapter.notifyItemChanged(index) } }
    private fun updateLoadingStatus(index: Int, status: String) {
        if (index in messages.indices && isAdded) {
            messages[index] = messages[index].copy(loadingStatus = status)
            adapter.notifyItemChanged(index)
        }
    }

    private fun failStreamingMessage(index: Int, errMsg: String) { if (index in messages.indices) { messages[index] = Message(content = "❌ $errMsg", isUser = false, isError = true, errorMessage = errMsg); adapter.notifyItemChanged(index) } }
    private fun addMessage(message: Message) { binding.tvNewTopicHint.visibility = View.GONE; messages.add(message); adapter.notifyItemInserted(messages.size - 1); binding.recyclerView.scrollToPosition(messages.size - 1) }
    private fun addBotMessage(text: String) = addMessage(Message(content = text, isUser = false))
    private fun addErrorMessage(text: String) = addMessage(Message(content = text, isUser = false, isError = true))
    private fun hideKeyboard() { (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.etInput.windowToken, 0) }
    private fun isTrivialMessage(text: String): Boolean = text.replace(Regex("[\\s,.，。!！?？、；;：:【】()（）、·]+"), "").length < 3
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
    fun sendMessageFromExternal(text: String) { binding.etInput.setText(text); binding.btnSend.performClick() }

    /**
     * 处理外部分享的内容（图片/PDF/文字）。
     * OCR 识别后发送到 AI 对话，分析完成后询问是否保存到机构推荐。
     */
    fun handleSharedContent(sharedUri: android.net.Uri?, sharedText: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 等待 UI 就绪
            kotlinx.coroutines.delay(500)
            if (!isAdded) return@launch

            if (sharedUri != null) {
                // 有 URI → 判断类型（图片 or PDF）
                val mimeType = requireContext().contentResolver.getType(sharedUri) ?: ""
                val path = sharedUri.path ?: ""
                when {
                    mimeType.startsWith("image/") || path.matches(Regex(""".*\.(jpg|jpeg|png|bmp|webp)$""", RegexOption.IGNORE_CASE)) -> {
                        // 图片 → OCR
                        val content = extractTextFromImage(sharedUri)
                        if (content.text.isNotBlank() && content.text != "[图片: unknownxunknown]") {
                            val ocrText = content.text
                            sharedExtractedText = ocrText
                            pendingInstitutionalSave = true
                            sendMessage("[分享图片 OCR]\n$ocrText\n\n请分析以上内容中的股票推荐信息。", skipStockContext = true)
                        } else {
                            sendMessage("[分享图片] 无法提取内容", skipStockContext = true)
                        }
                    }
                    mimeType == "application/pdf" || path.endsWith(".pdf", ignoreCase = true) -> {
                        // PDF → 提取文字
                        val fileName = getFileNameFromUri(sharedUri) ?: "PDF"
                        val extractedText = extractTextFromFile(sharedUri, fileName)
                        if (extractedText.isNotBlank()) {
                            sharedExtractedText = extractedText
                            pendingInstitutionalSave = true
                            sendMessage("[分享PDF: $fileName]\n$extractedText\n\n请分析以上内容中的股票推荐信息。", skipStockContext = true)
                        } else {
                            sendMessage("[分享PDF] 无法提取内容", skipStockContext = true)
                        }
                    }
                    else -> {
                        // 其他文件 → 尝试提取
                        val fileName = getFileNameFromUri(sharedUri) ?: "文件"
                        val extractedText = extractTextFromFile(sharedUri, fileName)
                        if (extractedText.isNotBlank()) {
                            sharedExtractedText = extractedText
                            pendingInstitutionalSave = true
                            sendMessage("[分享文件: $fileName]\n$extractedText\n\n请分析以上内容中的股票推荐信息。", skipStockContext = true)
                        } else {
                            sendMessage("[分享文件] 无法提取内容: $fileName", skipStockContext = true)
                        }
                    }
                }
            } else if (!sharedText.isNullOrEmpty()) {
                // 纯文字分享
                sharedExtractedText = sharedText
                pendingInstitutionalSave = true
                sendMessage("[分享文字]\n$sharedText\n\n请分析以上内容中的股票推荐信息。", skipStockContext = true)
            }
        }
    }

    private fun showEditMessageDialog(position: Int) {
        if (position !in messages.indices || !messages[position].isUser) return
        val input = EditText(requireContext()).apply { setText(messages[position].content); setSelection(text?.length ?: 0) }
        AlertDialog.Builder(requireContext()).setTitle("修改消息").setView(input).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ -> val t = input.text?.toString()?.trim().orEmpty(); if (t.isNotBlank()) { messages[position] = messages[position].copy(content = t); adapter.notifyItemChanged(position) } }.show()
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
        AlertDialog.Builder(requireContext()).setTitle("✏️ 修改标题").setView(input).setNegativeButton("取消", null).setPositiveButton("确定") { _, _ -> val t = input.text?.toString()?.trim(); if (!t.isNullOrBlank()) { binding.tvChatTitle.text = t; saveCurrentConversation() } }.show()
    }

    // ════════════════════════════════════════
    // 相机 / 相簿 / 档案 / 语音
    // ════════════════════════════════════════

    // ════════════════════════════════════════
    // + 菜单：相机 / 相册 / 文件
    // ════════════════════════════════════════

    private fun showPlusMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "📷 拍照")
        popup.menu.add(0, 2, 1, "🖼️ 相册")
        popup.menu.add(0, 3, 2, "📎 文件")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openCamera()
                2 -> openGallery()
                3 -> openFilePicker()
            }
            true
        }
        popup.show()
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.TITLE, "camera_${System.currentTimeMillis()}")
                put(MediaStore.Images.Media.DESCRIPTION, "Chat capture")
            }
            photoUri = requireContext().contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            )
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            cameraLauncher.launch(intent)
        } else {
            Toast.makeText(requireContext(), "无法启动相机", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryLauncher.launch(intent)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        fileLauncher.launch(intent)
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    /**
     * 从图片提取内容（用于 AI 分析）
     * 返回 FileContentExtractor.ExtractedContent 包含 base64 图片
     */
    private suspend fun extractTextFromImage(uri: Uri): FileContentExtractor.ExtractedContent =
        FileContentExtractor.extract(requireContext(), uri)

    /**
     * 从文件提取文字
     * 支持 txt、csv、pdf、docx、xlsx 等格式
     */
    private suspend fun extractTextFromFile(uri: Uri, fileName: String): String =
        FileContentExtractor.extract(requireContext(), uri, fileName).text

    private fun startVoiceInput() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("🎤 语音输入")
            .setMessage("录音中...")
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            delay(3000L)
            if (isAdded) {
                requireActivity().runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "语音转文字功能需要整合语音辨识SDK",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ════════════════════════════════════════
    // 记忆提取 + 追问
    // ════════════════════════════════════════

    private fun onMessageComplete() {
        // 不再使用固定追问模板，让 AI 自然对话
        // 检查最后一条用户消息是否包含股票代码，如有则弹窗询问操作
        tryPromptStockAction()

        // 如果是分享内容的 OCR 分析完成，询问是否保存到机构推荐
        if (pendingInstitutionalSave && isAdded) {
            pendingInstitutionalSave = false
            promptSaveToInstitutional()
        }
    }

    /** AI 分析完成后，询问用户是否将识别到的股票保存到机构推荐 */
    private fun promptSaveToInstitutional() {
        val text = sharedExtractedText
        if (text.isBlank()) return

        // 从文字中提取股票
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entities = com.chin.stockanalysis.ai.StockEntityExtractor.extract(text, requireContext())
                val stocks = if (entities.isNotEmpty()) {
                    entities.map { e -> (e.name.ifEmpty { e.text }) to e.code }.distinctBy { it.second }
                } else {
                    // fallback: 按行解析
                    val results = mutableListOf<Pair<String, String>>()
                    for (line in text.lines()) {
                        val codeMatch = Regex("""(\d{6})""").find(line)
                        if (codeMatch != null) {
                            val code = codeMatch.groupValues[1]
                            val name = Regex("""[\u4e00-\u9fa5]{2,6}""").find(line)?.value ?: ""
                            results.add(name to code)
                        }
                    }
                    results.distinctBy { it.second }
                }

                if (stocks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle("机构推荐")
                            .setMessage("未识别到股票信息。是否仍要手动添加到机构推荐？")
                            .setPositiveButton("去添加") { _, _ -> navigateToInstitutionalTab() }
                            .setNegativeButton("不需要", null)
                            .show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    val msg = buildString {
                        appendLine("识别到 ${stocks.size} 只股票：\n")
                        for ((name, code) in stocks) {
                            appendLine("  ${name.ifEmpty { "?" }} ($code)")
                        }
                        appendLine("\n是否保存到机构推荐？")
                    }
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("保存到机构推荐")
                        .setMessage(msg)
                        .setPositiveButton("保存") { _, _ ->
                            saveStocksToInstitutional(stocks)
                        }
                        .setNegativeButton("不保存") { _, _ ->
                            navigateToInstitutionalTab()
                        }
                        .setNeutralButton("保存并查看") { _, _ ->
                            saveStocksToInstitutional(stocks, navigateAfter = true)
                        }
                        .show()
                }
            } catch (e: Exception) {
                android.util.Log.w("ChatTabFragment", "机构推荐保存失败", e)
            }
        }
    }

    /** 将股票保存到自选（source 字段记录来源，如「AI推荐」「分享导入」等） */
    private fun saveStocksToInstitutional(stocks: List<Pair<String, String>>, navigateAfter: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
                val dao = db.userWatchlistDao()
                val today = java.time.LocalDate.now().toString()
                var savedCount = 0

                for ((name, code) in stocks) {
                    val existing = dao.getByCode(code)
                    if (existing != null) {
                        // 已存在 → 更新 source 和 notes
                        dao.update(existing.copy(
                            source = if (existing.source in listOf("manual", "midterm", "shortterm", "ultra_short", "long_term")) existing.source else "AI推荐",
                            notes = if (existing.notes.isEmpty()) "AI 分析确认" else existing.notes
                        ))
                    } else {
                        dao.insert(com.chin.stockanalysis.stock.database.UserWatchlistEntity(
                            stockCode = code,
                            stockName = name,
                            source = "AI推荐",
                            addedDate = today,
                            notes = "AI 分析确认"
                        ))
                    }
                    savedCount++
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    Toast.makeText(requireContext(), "已保存 $savedCount 只到自选", Toast.LENGTH_SHORT).show()
                    if (navigateAfter) navigateToInstitutionalTab()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) Toast.makeText(requireContext(), "保存失败: ${e.message?.take(30)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 导航到精选股票 → 机构推荐 Tab */
    private fun navigateToInstitutionalTab() {
        (activity as? MainActivity)?.navigateToInstitutional()
    }

    /** 如果用户输入包含股票代码，分析完成后弹窗询问加入自选/买入 */
    private fun tryPromptStockAction() {
        val lastUserMsg = messages.lastOrNull { it.isUser && !it.isStreaming && !it.isError } ?: return
        val codeMatch = Regex("(sh|sz|bj)?\\d{6}").find(lastUserMsg.content)
        val stockCode = codeMatch?.value ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = analyzeStockBrief(stockCode)
                if (result != null && isAdded) {
                    withContext(Dispatchers.Main) { showStockActionDialog(result) }
                }
            } catch (_: Exception) {}
        }
    }

    data class StockBriefResult(
        val code: String, val name: String, val price: Double,
        val changePct: Double, val score: Int, val hits: List<String>
    )

    /** 快速分析单只股票（本地策略，不调 AI） */
    private suspend fun analyzeStockBrief(stockCode: String): StockBriefResult? {
        val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().toString()
        val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
        val snap = db.dailySnapshotDao().getByDateAndCode(today, stockCode) ?: return null
        val basic = db.stockBasicDao().getByCode(stockCode)
        val stockName = basic?.name ?: stockCode

        val feed = com.chin.stockanalysis.strategy.data.StrategyDataFeed(requireContext())
        val stocks = feed.prepareFromDb(today, com.chin.stockanalysis.strategy.data.StrategyDataFeed.DataFeedConfig(onlyMainBoard = false, stockCodes = setOf(stockCode)))
        if (stocks.isEmpty()) return null

        val eng = com.chin.stockanalysis.strategy.StrategyEngineHolder.get()
        val hits = mutableListOf<String>()
        var maxScore = 0
        for (strategy in eng.getStrategies()) {
            if (!eng.isEnabled(strategy.id) || strategy.id == "ai_prediction") continue
            try {
                val r = strategy.screenWithData(stocks)
                r.getOrNull()?.signals?.firstOrNull()?.let { sig ->
                    hits.add(strategy.name)
                    if (sig.strength > maxScore) maxScore = sig.strength
                }
            } catch (_: Exception) {}
        }
        return StockBriefResult(stockCode, stockName, snap.close, snap.changePct, maxScore, hits)
    }

    /** 显示股票操作弹窗：加入自选 + 买入（符合条件时绿色，否则灰色） */
    private fun showStockActionDialog(result: StockBriefResult) {
        if (!isAdded) return
        val canBuy = result.score >= 60 && result.hits.isNotEmpty()
        val actionColor = if (canBuy) "#4CAF50" else "#9E9E9E"
        val msg = buildString {
            appendLine("${result.name} (${result.code})")
            appendLine("现价 ¥${"%.2f".format(result.price)} (${if(result.changePct>=0)"+" else ""}${"%.2f".format(result.changePct)}%)")
            appendLine("策略命中: ${result.hits.size} 个 (${result.hits.joinToString()})")
            appendLine("综合评分: ${result.score}分")
            appendLine()
            appendLine(if (canBuy) "🟢 符合买入条件" else "🔴 暂不符合买入条件")
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("📌 分析完成")
            .setMessage(msg)
            .setPositiveButton("➕ 加入自选") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        com.chin.stockanalysis.stock.database.AppBackgroundRunner.addToWatchlist(
                            requireContext(), result.code, result.name, "chat_analysis", result.score)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "✅ 已加入自选: ${result.name}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("▶ 买入") { _, _ ->
                if (!canBuy) return@setNeutralButton
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
                        val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                        db.strategyTradeOrderDao().insert(
                            com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity(
                                strategyId = "Chat_Analysis", stockCode = result.code,
                                stockName = result.name, tradeDate = today,
                                buyPrice = result.price, quantity = 100, orderType = "对话买入",
                                status = "BUYING", reason = "对话分析命中: ${result.hits.joinToString()}",
                                scoreAtBuy = result.score, createdAt = System.currentTimeMillis(),
                                buyTime = java.time.LocalTime.now().toString().take(8)
                            )
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "✅ 已买入 ${result.name}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {}
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(android.graphics.Color.parseColor("#2196F3"))
            val neutralBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
            if (canBuy) {
                neutralBtn?.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                neutralBtn?.text = "▶ 买入 (条件不足)"
                neutralBtn?.setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            }
        }
        dialog.show()
    }

    /** 检测用户输入中的 AI 提供者关键词 — 仅记录，不绕过池创建 Provider */
    private fun detectAndSwitchProvider(userText: String): ApiProvider? {
        return null // 统一由池管理，不单独创建
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

    // ════════════════════════════════════════
    // 跨Tab数据总线
    // ════════════════════════════════════════

    private fun observeCrossTabBus() {
        lifecycleScope.launch(Dispatchers.IO) {
            CrossTabBus.strategyResults.collect { results ->
                if (results.isNotEmpty()) {
                    Log.i(TAG, "📊 收到跨Tab策略结果: ${results.size}个策略")
                    // 注入到 AI 上下文（下次对话时生效）
                    smartContext.invalidateAll()
                }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            CrossTabBus.aiTopPicks.collect { picks ->
                if (picks.isNotEmpty()) {
                    Log.i(TAG, "🤖 收到跨Tab AI精选: ${picks.size}只")
                    smartContext.invalidateAll()
                }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            CrossTabBus.commandFlow.collect { cmd ->
                Log.i(TAG, "📢 收到跨Tab指令: ${cmd.action}")
                when (cmd.action) {
                    "CREATE_STRATEGY" -> {
                        withContext(Dispatchers.Main) {
                            addBotMessage("🤖 AI 正在生成策略配置...")
                        }
                        try {
                            val gen = com.chin.stockanalysis.ai.StrategyConfigGenerator(requireContext())
                            val generated = gen.generate(cmd.stockName)
                            if (generated != null) {
                                gen.registerToEngine(generated)
                                withContext(Dispatchers.Main) {
                                    addBotMessage("✅ 策略「${generated.name}」已创建！\n\n" +
                                        "分类: ${generated.category.label}\n" +
                                        "因子: ${generated.weightFactors.joinToString { "${it.label}(${it.weight}%)" }}")
                                    Toast.makeText(requireContext(), "新策略已就绪", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    addErrorMessage("⚠️ 策略生成失败，请用更具体的选股逻辑描述")
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                addErrorMessage("⚠️ 策略生成异常: ${e.message?.take(40)}")
                            }
                        }
                    }
                    // 未知命令不处理
                }
            }
        }
    }

    // ════════════════════════════════════════
    // 分析结果 → 买入确认
    // ════════════════════════════════════════

    private fun showBuyConfirmationDialog(result: com.chin.stockanalysis.ai.StockAnalyzerService.AnalysisResult) {
        if (!isAdded || result.strategyHits.isEmpty()) return

        val score = result.aiPicks.firstOrNull()?.compositeScore ?: (result.strategyHits.maxOfOrNull { it.strength } ?: 0)
        val actionEmoji = when {
            score >= 75 -> "🟢 推荐买入"
            score >= 60 -> "🟡 可关注"
            else -> "🔴 建议观望"
        }
        val msg = "${result.stockName}(${result.stockCode.takeLast(6)})\n" +
            "现价 ¥${"%.2f".format(result.currentPrice)} (${if(result.changePct>=0)"+" else ""}${"%.2f".format(result.changePct)}%)\n" +
            "策略命中: ${result.strategyHits.joinToString { "${it.strategyName.take(4)}(${it.strength})" }}\n" +
            "建议: $actionEmoji\n\n" +
            "是否买入该股票？"

        if (score >= 50) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("📌 买入确认")
                .setMessage(msg)
                .setPositiveButton("▶ 买入") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
                            val existing = db.strategyTradeOrderDao().getRecent(200)
                                .filter { it.status == "BUYING" || it.status == "PENDING" }
                            // 最大持仓 5 只，已满时强制对比优先级
                            val maxHoldings = 5
                            if (existing.size >= maxHoldings) {
                                val toSell = existing.minByOrNull { it.scoreAtBuy }
                                if (toSell != null && score > (toSell.scoreAtBuy ?: 0)) {
                                    db.strategyTradeOrderDao().updateSellInfo(
                                        toSell.id, "SOLD", toSell.buyPrice,
                                        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " 15:00", 0.0
                                    )
                                    Log.i(TAG, "🔄 持仓已满，替换: ${toSell.stockName}(${toSell.scoreAtBuy}分) → ${result.stockName}(${score}分)")
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(requireContext(), "持仓已满5只，当前股票优先级不高", Toast.LENGTH_SHORT).show()
                                    }; return@launch
                                }
                            }
                            val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay()
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                            db.strategyTradeOrderDao().insert(
                                com.chin.stockanalysis.strategy.trade.StrategyTradeOrderEntity(
                                    strategyId = "AI_Recommend", stockCode = result.stockCode,
                                    stockName = result.stockName, tradeDate = today,
                                    buyPrice = result.currentPrice, buyTime = "",
                                    quantity = 100, orderType = "对话买入",
                                    status = "BUYING", reason = "AI分析推荐: ${result.strategyHits.joinToString()}",
                                    scoreAtBuy = score, createdAt = System.currentTimeMillis()
                                )
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "✅ 已买入 ${result.stockName}", Toast.LENGTH_SHORT).show()
                                // 持仓已更新，下次切换到模拟交易Tab时会自动刷新
                            }
                        } catch (_: Exception) {}
                    }
                }
                .setNegativeButton("取消", null)
                .setNeutralButton("🗑️ 不感兴趣") { _, _ -> /* 标记为不感兴趣 */ }
                .show()
        }
    }

    /** 板块标签三级 fallback: DB → hardcoded → StockDataCenter */
    private fun lookupSectorLabel(code: String, name: String, db: com.chin.stockanalysis.stock.database.StockDatabase): String {
        try {
            val existing = kotlinx.coroutines.runBlocking { db.sectorStockDao().getSectorNamesByStockCode(code) }
            if (existing.isNotEmpty()) {
                val sect = existing.first()
                if (sect != "null" && !sect.contains("null", ignoreCase = true)) return sect
            }
        } catch (_: Exception) {}
        val fallback = com.chin.stockanalysis.ai.DataCompletenessChecker(db).hardcodedSector(name)
        if (fallback != "-") return fallback
        try {
            val sectors = kotlinx.coroutines.runBlocking { com.chin.stockanalysis.stock.database.StockDataCenter.getSectorsByStock(code) }
            if (sectors.isNotEmpty()) return sectors.first()
        } catch (_: Exception) {}
        return ""
    }

    private fun onFollowUpConfirmed(suggestion: KeyMemoryManager.FollowUpSuggestion) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { memoryManager.boostMemoryWeight(key = suggestion.memoryKey, value = suggestion.memoryValue, category = suggestion.memoryCategory, convId = currentConvId) } }
        sendMessage(suggestion.text)
    }

    /** 分析模式枚举 */
    enum class AnalysisMode { QUICK, DEEP, EXPERT }

    /**
     * 清理 Agent 回应中的原始推理过程、JSON 碎片、thinking 标签
     */
    private fun cleanAgentResponse(text: String): String {
        return text
            // 移除 <thinking>...</thinking> 推理标签及内容
            .replace(Regex("<thinking>[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE), "")
            // 移除 ```json ... ``` 代码块
            .replace(Regex("```json[\\s\\S]*?```", RegexOption.IGNORE_CASE), "")
            // 移除 ``` ... ``` 通用代码块
            .replace(Regex("```[\\s\\S]*?```"), "")
            // 逐行过滤
            .lines()
            .filter { line ->
                val t = line.trim()
                // 保留非空行
                if (t.isBlank()) return@filter false
                // 过滤纯大括号/中括号行
                if (t.matches(Regex("^[{}\\[\\],:]\\s*$"))) return@filter false
                // 过滤 JSON key-value 行（如 "key": "value"）
                if (t.matches(Regex("^\"[^\"]+\"\\s*:\\s*.+$"))) return@filter false
                // 过滤纯数字行
                if (t.matches(Regex("^-?\\d+(\\.\\d+)?$"))) return@filter false
                true
            }
            .joinToString("\n")
            .trim()
    }
}

