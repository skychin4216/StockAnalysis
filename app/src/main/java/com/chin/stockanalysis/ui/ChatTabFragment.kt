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
import com.chin.stockanalysis.ai.StockEntityExtractor
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
        /** P1: 串流渲染節流閥值 (ms) — 避免頻繁 UI 更新導致卡頓 */
        private const val STREAMING_THROTTLE_MS = 80L

        private val BASE_SYSTEM_PROMPT = com.chin.stockanalysis.ai.StockAIPromptBuilder.buildBaseSystemPrompt()
    }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ChatAdapter
    private val messages: MutableList<Message> = mutableListOf()

    private var currentStreamingJob: Job? = null
    private var apiProvider: ApiProvider? = null
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

    // ═══ 媒體與檔案選擇器 ═══
    private var photoUri: Uri? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            photoUri?.let { sendMessage("[圖片]") }
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    val content = extractTextFromImage(uri)
                    if (content.isImage && content.base64Image.isNotBlank()) {
                        sendMessageWithImage("[請分析這張圖片]", content.base64Image, content.mimeType)
                    } else if (content.text.isNotBlank() && content.text != "[圖片: unknownxunknown]") {
                        sendMessage("[圖片內容]\n${content.text}", skipStockContext = true)
                    } else {
                        sendMessage("[圖片] 無法提取內容", skipStockContext = true)
                    }
                }
            }
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    val fileName = getFileNameFromUri(uri) ?: "未知檔案"
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
        setupTitleBar()
        initTts()
        showWelcomeMessage()
        showHotSectors()
        preloadMarketData()
        observeCrossTabBus()
    }

    override fun onResume() { super.onResume(); initProvider() }
    override fun onPause() { super.onPause(); saveCurrentConversation() }
    override fun onStop() { super.onStop(); saveCurrentConversation() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
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

        binding.btnModeQuick.apply {
            setTextColor(android.graphics.Color.parseColor(if (mode == AnalysisMode.QUICK) "#FFFFFF" else inactiveColor))
            background = if (mode == AnalysisMode.QUICK) requireContext().getDrawable(activeBg) else requireContext().getDrawable(inactiveBg)
        }
        binding.btnModeDeep.apply {
            setTextColor(android.graphics.Color.parseColor(if (mode == AnalysisMode.DEEP) "#FFFFFF" else inactiveColor))
            background = if (mode == AnalysisMode.DEEP) requireContext().getDrawable(activeBg) else requireContext().getDrawable(inactiveBg)
        }
        binding.btnModeExpert.apply {
            setTextColor(android.graphics.Color.parseColor(if (mode == AnalysisMode.EXPERT) "#FFFFFF" else inactiveColor))
            background = if (mode == AnalysisMode.EXPERT) requireContext().getDrawable(activeBg) else requireContext().getDrawable(inactiveBg)
        }

        val modeHint = when (mode) {
            AnalysisMode.QUICK -> "⚡ 快速模式：实时行情+情绪分析"
            AnalysisMode.DEEP -> "🔍 深度模式：多策略量化筛选+板块对比"
            AnalysisMode.EXPERT -> "🧠 专家模式：使用 AI智能体流水线"
        }
        binding.etInput.hint = modeHint
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
                if (existingCount >= 30) { Log.i(TAG, "📊 今日數據已完整 (${existingCount}隻)"); return@launch }
                Log.i(TAG, "📊 後台預取全市場數據 (現有${existingCount}隻)...")
                val fetcher = com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(requireContext())
                val count = fetcher.fetchAllHistoricalData(days = 1)
                Log.i(TAG, "📊 預取完成: $count 條")
                cachedHotSectorsTime = 0L
            } catch (e: Exception) { Log.w(TAG, "預取失敗: ${e.message}") }
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
        binding.recyclerView.itemAnimator = null // 禁用動畫，避免 loading view 閃現
        binding.recyclerView.adapter = adapter
    }

    private fun setupInput() {
        // 模式选择按钮
        binding.btnModeQuick.setOnClickListener { setAnalysisMode(AnalysisMode.QUICK) }
        binding.btnModeDeep.setOnClickListener { setAnalysisMode(AnalysisMode.DEEP) }
        binding.btnModeExpert.setOnClickListener { setAnalysisMode(AnalysisMode.EXPERT) }

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
                // 第一步：獲取10個東方財富概念板塊漲幅
                var hotSectors = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.conceptSectors
                    .sortedByDescending { it.changePercent }.take(10)
                if (hotSectors.isEmpty()) {
                    val source = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource()
                    hotSectors = source.fetchSectorsByTypeDirect(type = 3, topN = 10).sortedByDescending { it.changePercent }.take(10)
                }

                // 第二步：對每個板塊獲取漲幅前5的成分股
                val eastSource = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource()
                val allLeaders = mutableMapOf<String, List<com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.LeaderStock>>()
                for (s in hotSectors) {
                    try {
                        allLeaders[s.name] = eastSource.fetchSectorLeaders(s.code, 5)
                    } catch (_: Exception) { allLeaders[s.name] = emptyList() }
                }

                // 第三步：格式化輸出
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
                    // Top5 個股匯總
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

                // 第五步：保持 Top5 個股行不變
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
    /** 暫存最後一張圖片的 base64，供 AI 分析時使用 */
    private var lastImageBase64: String? = null

    /**
     * 發送帶圖片的訊息（使用 base64 data URL）
     */
    private fun sendMessageWithImage(userText: String, base64Image: String, mimeType: String) {
        lastImageBase64 = base64Image
        val displayText = if (userText.isNotBlank()) userText else "[圖片]"
        addMessage(Message(content = "🖼️ $displayText", isUser = true))
        binding.etInput.setText(""); hideKeyboard()

        val provider = apiProvider
        if (provider == null) {
            addErrorMessage("❌ AI 尚未連接，請稍候重試")
            return
        }

        // 使用快速模式分析圖片
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "🖼️ 正在分析圖片...")
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val imageDataUrl = "data:$mimeType;base64,$base64Image"
                val prompt = buildString {
                    appendLine("【圖片分析請求】")
                    appendLine("用戶上傳了一張圖片，請分析圖片內容並給出詳細解答。")
                    appendLine()
                    appendLine("圖片數據 (data URL):")
                    appendLine(imageDataUrl.take(100))  // 只提示，實際圖片通過其他方式傳遞
                    appendLine("... (base64 圖片數據)")
                    appendLine()
                    appendLine("用戶問題: ${userText.ifBlank { "請分析這張圖片的內容" }}")
                    appendLine()
                    appendLine("注意：如果當前 AI 模型支援圖片分析，請直接分析圖片。如果不支援，請告知用戶。")
                }
                val history = messages.toList().subList(0, loadingIndex).filter {
                    !it.content.startsWith("🖼️")
                }
                sendWithRetry(provider, history, prompt, loadingIndex, 2)
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "圖片分析失败: ${e.message}")
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
            // Provider 尚未就绪，等待初始化完成
            currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
                addBotMessage("⏳ AI 正在连接...")
                var waited = 0
                while (apiProvider == null && waited < 50) {
                    kotlinx.coroutines.delay(200L)
                    waited++
                }
                if (apiProvider != null) {
                    // 移除"连接中"消息
                    if (messages.isNotEmpty() && !messages.last().isUser) {
                        messages.removeLast()
                        adapter.notifyItemRemoved(messages.size)
                    }
                    sendMessageInternal(userText, apiProvider!!, isRetry = true, skipStockContext = skipStockContext)
                } else {
                    addErrorMessage("❌ AI 连接超时，请稍候重试")
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

        // 🎯 Agent 模式：如果全局開啟了 Agent，走 ChatRouter → ChatAgent
        if (FeatureFlagManager.isAgentFramework(FeatureFlagManager.chatRoute)) {
            runAgentAnalysis(userText, provider, skipStockContext)
            return
        }

        // 🎯 Legacy 模式：根据分析模式分流处理
        when (analysisMode) {
            AnalysisMode.QUICK -> runQuickAnalysis(userText, provider, skipStockContext)
            AnalysisMode.DEEP -> runDeepAnalysis(userText, provider, skipStockContext)
            AnalysisMode.EXPERT -> runExpertAnalysis(userText, provider, skipStockContext)
        }
    }

    /** 🤖 Agent 模式：ChatRouter → ChatAgent 智能對話 */
    private fun runAgentAnalysis(userText: String, provider: ApiProvider, skipStockContext: Boolean = false) {
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "🤖 Agent 正在分析...")
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                updateLoadingStatus(loadingIndex, "🤖 正在啟動 Agent 框架...")
                val service = ChatRouter.getService()

                val result = withContext(Dispatchers.IO) {
                    service.handleMessage(
                        context = requireContext(),
                        message = userText,
                        onStream = { chunk ->
                            // 專家模式 Pipeline 進度：實時更新對話消息
                            if (isAdded) requireActivity().runOnUiThread {
                                updateLoadingStatus(loadingIndex, chunk)
                            }
                        },
                        analysisMode = analysisMode
                    )
                }
                if (isAdded) requireActivity().runOnUiThread {
                    // 歧義實體：需要用戶確認
                    if (result.ambiguousEntities != null && result.ambiguousEntities.isNotEmpty()) {
                        // 移除 loading 消息
                        messages.removeAt(loadingIndex)
                        adapter.notifyItemRemoved(loadingIndex)
                        // 創建 EntityConfirmCard 消息
                        val entityMsg = Message(
                            content = result.response,
                            isUser = false,
                            ambiguousEntities = result.ambiguousEntities,
                            onEntityConfirm = { selected ->
                                // 用戶選擇後，將選中的股票代碼作為新消息發送
                                sendMessage("分析 ${selected.code}")
                            },
                            onEntityCancel = {
                                // 用戶取消
                                addBotMessage("已取消")
                            }
                        )
                        addMessage(entityMsg)
                    } else if (result.success) {
                        completeStreamingMessage(loadingIndex, result.response)
                        onMessageComplete()
                    } else {
                        failStreamingMessage(loadingIndex, "Agent 分析失败: ${result.response}")
                    }
                }
            } catch (e: UnsupportedOperationException) {
                // LegacyChatService 抛出 UnsupportedOperationException，fallback 到原有專家模式
                Log.i(TAG, "Agent Legacy 模式，fallback 到原有專家分析")
                if (isAdded) requireActivity().runOnUiThread {
                    messages.removeAt(loadingIndex)
                    adapter.notifyItemRemoved(loadingIndex)
                    runExpertAnalysis(userText, provider, skipStockContext)
                }
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "Agent 異常: ${e.message}")
                }
            }
        }
    }

    /** ⚡ 快速模式：AI 智能解析 + 結構化輸出 */
    private fun runQuickAnalysis(userText: String, provider: ApiProvider, skipStockContext: Boolean = false) {
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "⚡ 正在思考...")
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 動態更新 loading 狀態
                val stockInfo = if (skipStockContext) {
                    ""
                } else {
                    updateLoadingStatus(loadingIndex, "⚡ 正在搜索相關數據...")
                    withContext(Dispatchers.IO) {
                        smartContext.getOrBuild(userText = userText, baseSystemPrompt = BASE_SYSTEM_PROMPT, onPreferenceLeaned = {})
                    }
                }
                updateLoadingStatus(loadingIndex, "⚡ 正在分析...")
                val memory = withContext(Dispatchers.IO) { memoryManager.buildMemorySuffix() }

                // AI 先判斷用戶意圖，再決定輸出格式
                val prompt = """【⚡ AI 快速分析】
用戶輸入：$userText

$stockInfo
$memory

請先判斷用戶意圖，然後按對應格式輸出：

【意圖判斷】
1. 如果用戶要求「對比」多只股票 → 輸出「多股對比格式」
2. 如果用戶問單只股票 → 輸出「單股分析格式」
3. 如果用戶問非股票問題（生活/技術/其他）→ 輸出「通用問答格式」

【單股分析格式】（參考豆包風格，簡潔全面）
一、公司基本概況（主營業務、行業地位、核心競爭力）
二、最新財務表現（營收/利潤增速、毛利率）
三、核心上漲邏輯（2-3個驅動因素）
四、核心風險（2-3個風險點）
五、短期 & 中長期總結

【多股對比格式】（參考豆包風格）
一、先理清產業鏈位置（各公司在產業鏈中的位置）
二、基礎信息 & 主營業務對比（表格形式）
三、最新財務對比（營收、利潤、增速）
四、核心壁壘 & 競爭優勢對比（每家公司 ✅優勢 / ❌劣勢）
五、估值 & 成長屬性對比
六、風險總結
七、極簡選股建議（針對不同風格投資者）

【通用問答格式】
- 直接回答用戶問題，保持專業簡潔

注意：
1. 總字數控制在 500-800 字
2. 用簡潔的 bullet points 和表格
3. 最後加一句免責聲明：「以上分析不構成投資建議」

⚠️ 關鍵約束：
- 以上【實時行情數據】中的所有價格、技術指標、板塊熱度、資金流向來自東方財富/交易所實時獲取
- 務必以注入的即時數據為唯一分析依據，嚴禁使用訓練數據中的歷史價格或過時資訊
- 如果某項基準數據不在注入數據中，標註「該數據暫未獲取到」，不得編造"""
                val history = messages.toList().subList(0, loadingIndex)
                sendWithRetry(provider, history, prompt, loadingIndex, 2)
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "快速分析失败: ${e.message}")
                }
            }
        }
    }

    /** 🔍 深度模式：多策略量化筛选 + 板块对比 + AI Predict */
    private fun runDeepAnalysis(userText: String, provider: ApiProvider, skipStockContext: Boolean = false) {
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "🔍 正在搜索相關數據...")
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Step 1: 获取实时行情 + 同板块策略数据
                val stockInfo: String
                val sectorAnalysis: String
                if (skipStockContext) {
                    stockInfo = ""
                    sectorAnalysis = ""
                } else {
                    updateLoadingStatus(loadingIndex, "🔍 正在獲取實時行情...")
                    stockInfo = withContext(Dispatchers.IO) {
                        smartContext.getOrBuild(userText = userText, baseSystemPrompt = BASE_SYSTEM_PROMPT, onPreferenceLeaned = {})
                    }
                    updateLoadingStatus(loadingIndex, "🔍 正在掃描量化策略...")
                    sectorAnalysis = withContext(Dispatchers.IO) {
                        buildSectorAnalysis(userText)
                    }
                }

                // Step 2: 构建深度分析 prompt
                updateLoadingStatus(loadingIndex, "🔍 正在深度分析...")
                val memory = withContext(Dispatchers.IO) { memoryManager.buildMemorySuffix() }
                val prompt = """【🔍 深度分析模式】
用戶輸入：$userText

【實時行情數據】（以上價格為當前最新數據，務必以此為基準分析）
$stockInfo

【板塊 & 策略數據】
$sectorAnalysis
$memory

請先判斷用戶意圖，然後按對應格式輸出：

【意圖判斷】
1. 如果用戶要求「對比」多只股票 → 輸出「多股深度對比格式」
2. 如果用戶問單只股票 → 輸出「單股深度分析格式」
3. 如果用戶問非股票問題 → 輸出「通用深度問答格式」

【單股深度分析格式】
一、所屬板塊熱度分析（當前板塊資金流向、排名）
二、同板塊 Top5 對比（表格：股票/市值/漲跌幅/量比/策略信號）
三、量化策略信號綜合評估（MA/RSI/量比/資金流等策略信號匯總）
四、AI 預測模型評分（技術面/基本面/情緒面綜合評分）
五、買入/觀望/回避建議（給出具體價位區間和止損位）

【多股深度對比格式】
一、產業鏈位置 & 板塊歸屬
二、量化策略篩選對比（策略信號表格）
三、板塊熱度 & 資金流向對比
四、AI 預測評分對比
五、綜合排名 & 選股建議

【通用深度問答格式】
- 深入分析用戶問題，提供結構化答案

注意：
1. 總字數 800-1200 字
2. 使用表格和 bullet points
3. 最後加免責聲明"""
                val history = messages.toList().subList(0, loadingIndex)
                sendWithRetry(provider, history, prompt, loadingIndex, 2)
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "深度分析失败: ${e.message}")
                }
            }
        }
    }

    /** 🧠 專家模式：智能體協同篩選
     *
     * 根據股票產業鏈位置自動選擇分析流程：
     * - 賣水人（上游）→ 賣水人智能篩選（7 個智能體，含題材賽道分析）
     * - 非賣水人（下游）→ 非賣水人智能篩選（6 個智能體，注重產品力和品牌）
     *
     * 7 個智能體（賣水人）：
     * 1. 基本面  2. 技術面  3. 資金面  4. 題材賽道  5. 估值  D. 風控  F. 情緒策略
     *
     * 6 個智能體（非賣水人）：
     * 1. 基本面  2. 技術面  3. 資金面  4. 估值  5. 風控  6. 情緒策略
     */
    private fun runExpertAnalysis(userText: String, provider: ApiProvider, skipStockContext: Boolean = false) {
        val loadingMsg = Message(content = "", isUser = false, isStreaming = true,
            loadingStatus = "🧠 正在搜索相關數據...")
        addMessage(loadingMsg)
        val loadingIndex = messages.size - 1

        currentStreamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val stockInfo = if (skipStockContext) {
                    ""
                } else {
                    updateLoadingStatus(loadingIndex, "🧠 正在識別產業鏈位置...")
                    withContext(Dispatchers.IO) {
                        smartContext.getOrBuild(userText = userText, baseSystemPrompt = BASE_SYSTEM_PROMPT, onPreferenceLeaned = {})
                    }
                }
                updateLoadingStatus(loadingIndex, "🧠 正在啟動智能體協同篩選...")
                val memory = withContext(Dispatchers.IO) { memoryManager.buildMemorySuffix() }

                // 智能體協同分析 prompt（根據股票產業鏈位置自動選擇 6步/7步）
                val prompt = """【🧠 專家模式：智能體協同篩選】
分析基準時間：${java.time.LocalDate.now()} 午盤
⚠️ 全部內容僅為數據復盤研究，不構成任何投資建議

⚠️ 數據約束：
1. 你只能使用上方【股票信息】中明確提供的具體數據進行分析
2. 對於未提供的數據（如營收、PE、融資餘額等），必須標註「數據不可用」
3. 禁止使用訓練數據中的任何舊價格、舊信息進行推斷
4. 如果某項分析所需數據缺失，請直接跳過該維度，不要編造

用戶輸入：$userText

$stockInfo
$memory

【第一步】判斷用戶意圖：
1. 如果用戶要求「對比」多只股票 → 執行「橫向對比流程」
2. 如果用戶問單只股票 → 執行「單股分析流程」
3. 如果用戶問非股票問題 → 執行「通用專家問答流程」

【第二步】對每隻股票進行產業鏈分類（這決定用 6 步還是 7 步）：
- 「賣水人」= 產業鏈上游（原材料、設備、技術平台、關鍵零部件），為下游提供核心材料/技術/服務
  → 使用「賣水人智能篩選」（7 個智能體）
- 「非賣水人」= 產業鏈下游（終端產品、品牌、集成商），直接面向終端客戶
  → 使用「非賣水人智能篩選」（6 個智能體）

【賣水人智能篩選】（7 個智能體，適用於產業鏈上游股票）
上游公司更依賴賽道景氣度和技術迭代，因此需要額外的「題材賽道」智能體。

智能體 1：基本面分析智能體（營收、利潤、業務壁壘）
- 核心業務：主營業務、行業地位、核心競爭力
- 財報核心：最新季度營收/歸母淨利潤、同比增速、毛利率、資產負債率、經營性現金流
- 護城河：專利數量、客戶認證、市佔率、國產替代空間
- 短板：毛利率水平、業務集中度、傳統業務增長乏力等

智能體 2：技術面分析智能體（K線、趨勢、支撐壓力）
- 階段走勢：近 3 個月漲幅、是否屬於超級趨勢妖股、今日盤面表現
- 量能結構：近 5 日成交額、換手率、籌碼交換情況
- 關鍵價位：短期壓力位、短期支撐位、前期密集成交區
- 均線狀態：短期均線方向、5 日線壓制/支撐、趨勢格局判斷

智能體 3：資金面分析智能體（主力、北向、融資、龍虎）
- 槓桿資金：融資餘額、佔流通市值比例、槓桿倉位分位數
- 資金行為：主力資金流向、北向資金動向、機構大額買入/賣出信號
- 成交特徵：連續天量/縮量、場內分歧程度、增量/存量博弈特徵

智能體 4：題材賽道分析智能體（行業邏輯、政策催化）← 賣水人專屬
- 核心題材：所屬概念板塊、核心驅動邏輯
- 行業邏輯：中長期行業景氣度、供需格局、技術迭代趨勢
- 催化點：近期利好催化（訂單落地、產能投產、新產品認證）
- 利空壓制：題材炒作過熱、板塊退潮、估值殺情緒

智能體 5：估值定價分析智能體（PE、溢價、合理性測算）
- 當前估值：TTM 市盈率、市淨率、處於歷史什麼分位
- 溢價拆解：情緒溢價佔比、業績成長溢價佔比、估值透支年限
- 估值結論：基本面增速能否匹配估值、高估/低估/合理判斷

智能體 D：風控預警智能體（風險清單、排雷）
- 估值泡沫風險：PE 極度偏高、估值回調空間
- 股東減持風險：高管/大股東減持計劃、產業資本高位減持信號
- 槓桿踩踏風險：融資盤倉位過高、大跌觸發被動平倉
- 業績兌現風險：訂單增速不及預期、產能釋放延遲
- 板塊退潮風險：題材降溫、高位股集體殺估值
- 成本波動風險：原材料漲價擠壓毛利率

智能體 F：市場情緒 & 策略智能體（情緒打分、操作參考）
- 情緒打分（0~10 分）：前期抱團情緒、當前恐慌/貪婪程度、做多意願
- 短線判斷：趨勢是否終結、進入震盪調整概率、反彈乏力/強勁判斷
- 分層參考思路（非操作建議）：
  * 持倉者：支撐位為強弱分水嶺，跌破注意減倉規避深度回調
  * 觀望者：不急於抄底，等待估值回落、縮量企穩、情緒修復後再評估
  * 長線視角：看好賽道邏輯也需等估值消化，不宜高位追入

綜合總評：基本面賽道價值 vs 短期風險權衡，明確結論類型

【非賣水人智能篩選】（6 個智能體，適用於產業鏈下游股票）
下游公司更看重產品力、品牌、渠道和財務質量，不需要「題材賽道」智能體。

智能體 1：基本面分析智能體（營收、利潤、業務壁壘）
- 核心業務：主營業務、行業地位、品牌力、渠道優勢
- 財報核心：最新季度營收/歸母淨利潤、同比增速、毛利率、資產負債率、經營性現金流
- 護城河：品牌溢價、渠道壁壘、規模效應、客戶粘性
- 短板：增長乏力、市場份額流失、成本控制能力

智能體 2：技術面分析智能體（K線、趨勢、支撐壓力）
（同賣水人智能體 2）

智能體 3：資金面分析智能體（主力、北向、融資、龍虎）
（同賣水人智能體 3）

智能體 4：估值定價分析智能體（PE、溢價、合理性測算）
（同賣水人智能體 5，但更注重 ROE、分紅率、自由現金流）

智能體 5：風控預警智能體（風險清單、排雷）
（同賣水人智能體 D，但更注重行業競爭加劇、渠道變化、消費降級等下游風險）

智能體 6：市場情緒 & 策略智能體（情緒打分、操作參考）
（同賣水人智能體 F）

綜合總評：產品力 vs 競爭格局，明確結論類型

【橫向對比流程】（多只股票時）
先輸出「基礎行情總覽」表格：
| 標的 | 代碼 | 現價 | 當日漲跌幅 | 總市值 | PE(TTM) | 產業鏈定位 | 篩選類型 |

然後：
1. 對每隻股票分類（賣水人/非賣水人），標註使用 7步還是 6步
2. 每個智能體對所有股票橫向對比分析，給出排名
3. 賣水人股票用 7 個智能體分析，非賣水人股票用 6 個智能體分析
4. 最後綜合總評：產業鏈關係、整體風險、不同風格投資者選擇建議

【通用專家問答流程】
- 深入分析用戶問題，提供專業結構化答案

注意：
1. 每個智能體獨立輸出，用「智能體 X：」標題分隔
2. 使用 bullet points、表格、emoji 標註
3. 單股分析總字數 1500-2500 字；橫向對比總字數 2000-3000 字
4. 最後加免責聲明：「⚠️ 全部內容僅為數據復盤研究，不構成任何投資建議」"""

                val history = messages.toList().subList(0, loadingIndex)
                sendWithRetry(provider, history, prompt, loadingIndex, 2)
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    failStreamingMessage(loadingIndex, "專家模式失敗: ${e.message}")
                }
            }
        }
    }

    /** 构建实时同板块分析文本（含板块热度 + Top5 + 资金流向） */
    private suspend fun buildSectorAnalysis(userText: String): String {
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            try {
                val db = com.chin.stockanalysis.stock.database.StockDatabase.getInstance(requireContext())
                val tradingDay = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay().toString()
                val codeMatch = Regex("(sh|sz|bj)?\\d{6}").find(userText)
                val stockCode = codeMatch?.value

                // 1. 实时板块热度排名（从东方财富拉取，非历史知识）
                val hotSectors = com.chin.stockanalysis.stock.data.sources.EastMoneyHotSectorSource.conceptSectors
                if (hotSectors.isNotEmpty()) {
                    sb.appendLine("【实时板块热度排名Top10】（东方财富实时数据，取自 $tradingDay）")
                    sb.appendLine("| 排名 | 板块名称 | 涨跌幅 |")
                    sb.appendLine("|------|---------|--------|")
                    hotSectors.take(10).forEachIndexed { i, s ->
                        val emoji = if (s.changePercent > 0) "📈" else "📉"
                        sb.appendLine("| ${i + 1} | $emoji ${s.name} | ${if (s.changePercent > 0) "+" else ""}${"%.2f".format(s.changePercent)}% |")
                    }
                    sb.appendLine()
                }

                // 2. 所属板块 Top5 实时数据
                if (stockCode != null) {
                    val sectors = com.chin.stockanalysis.stock.database.StockDataCenter.getSectorsByStock(stockCode)
                    if (sectors.isNotEmpty()) {
                        sb.appendLine("【目标股票所属板块】${sectors.joinToString(", ")}")
                        for (sector in sectors.take(2)) {
                            val topStocks = com.chin.stockanalysis.stock.database.StockDataCenter.getTopStocksBySector(sector, 5, 5)
                            if (topStocks.isNotEmpty()) {
                                sb.appendLine()
                                sb.appendLine("【$sector 板块Top5 实时行情】")
                                sb.appendLine("| 排名 | 股票名称 | 代码 | 现价 | 涨跌幅 | 量比 | 主力净流入(万) |")
                                sb.appendLine("|------|---------|------|------|--------|------|--------------|")
                                topStocks.forEachIndexed { i, pair ->
                                    val (code, name) = pair
                                    // ✅ 用交易日而非今天，避免周末/节假日无数据
                                    val snap = db.dailySnapshotDao().getByDateAndCode(tradingDay, code)
                                    if (snap != null) {
                                        val chg = snap.changePct
                                        val chgEmoji = if (chg >= 0) "+" else ""
                                        val volRatio = if (snap.turnoverRate > 0) "%.1f".format(snap.volume / 10000) else "-"
                                        val inflow = if (snap.mainNetInflow != 0.0) "%.0f".format(snap.mainNetInflow / 10000) else "-"
                                        sb.appendLine("| ${i + 1} | $name | $code | ${"%.2f".format(snap.close)} | $chgEmoji${"%.2f".format(chg)}% | $volRatio | $inflow |")
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 板块热门股票排行（当日涨幅最高的板块领涨股）
                val topGainers = db.dailySnapshotDao().getByDate(tradingDay)
                    .sortedByDescending { it.changePct }.take(10)
                if (topGainers.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("【今日全市涨幅Top10】（$tradingDay 实时）")
                    sb.appendLine("| 排名 | 股票 | 代码 | 现价 | 涨跌幅 | 换手率 |")
                    sb.appendLine("|------|------|------|------|--------|--------|")
                    topGainers.forEachIndexed { i, snap ->
                        val chg = snap.changePct
                        sb.appendLine("| ${i + 1} | ${snap.name} | ${snap.code} | ${"%.2f".format(snap.close)} | ${if (chg >= 0) "+" else ""}${"%.2f".format(chg)}% | ${"%.2f".format(snap.turnoverRate)}% |")
                    }
                }
            } catch (_: Exception) { }
            if (sb.isEmpty()) sb.appendLine("【板块分析】未能获取实时板块数据，请基于【实时行情数据】区块中的信息分析。")
            sb.toString()
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
                        // P1: 80ms 節流，避免串流期間過度刷新
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
                        // 完成時強制刷新最後一次（確保最後的內容完整顯示）
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
    // 相機 / 相簿 / 檔案 / 語音
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
            Toast.makeText(requireContext(), "無法啟動相機", Toast.LENGTH_SHORT).show()
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
     * 從圖片提取內容（用於 AI 分析）
     * 返回 FileContentExtractor.ExtractedContent 包含 base64 圖片
     */
    private suspend fun extractTextFromImage(uri: Uri): FileContentExtractor.ExtractedContent =
        FileContentExtractor.extract(requireContext(), uri)

    /**
     * 從文件提取文字
     * 支持 txt、csv、pdf、docx、xlsx 等格式
     */
    private suspend fun extractTextFromFile(uri: Uri, fileName: String): String =
        FileContentExtractor.extract(requireContext(), uri, fileName).text

    private fun startVoiceInput() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("🎤 語音輸入")
            .setMessage("錄音中...")
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
                        "語音轉文字功能需要整合語音辨識SDK",
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
        // 不再使用固定追問模板，讓 AI 自然對話
        // 檢查最後一條用戶消息是否包含股票代碼，如有則彈窗詢問操作
        tryPromptStockAction()
    }

    /** 如果用戶輸入包含股票代碼，分析完成後彈窗詢問加入自選/買入 */
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

    /** 快速分析單只股票（本地策略，不調 AI） */
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

    /** 顯示股票操作彈窗：加入自選 + 買入（符合條件時綠色，否則灰色） */
    private fun showStockActionDialog(result: StockBriefResult) {
        if (!isAdded) return
        val canBuy = result.score >= 60 && result.hits.isNotEmpty()
        val actionColor = if (canBuy) "#4CAF50" else "#9E9E9E"
        val msg = buildString {
            appendLine("${result.name} (${result.code})")
            appendLine("現價 ¥${"%.2f".format(result.price)} (${if(result.changePct>=0)"+" else ""}${"%.2f".format(result.changePct)}%)")
            appendLine("策略命中: ${result.hits.size} 個 (${result.hits.joinToString()})")
            appendLine("綜合評分: ${result.score}分")
            appendLine()
            appendLine(if (canBuy) "🟢 符合買入條件" else "🔴 暫不符合買入條件")
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("📌 分析完成")
            .setMessage(msg)
            .setPositiveButton("➕ 加入自選") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        com.chin.stockanalysis.stock.database.AppBackgroundRunner.addToWatchlist(
                            requireContext(), result.code, result.name, "chat_analysis", result.score)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "✅ 已加入自選: ${result.name}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("▶ 買入") { _, _ ->
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
                                buyPrice = result.price, quantity = 100, orderType = "對話買入",
                                status = "BUYING", reason = "對話分析命中: ${result.hits.joinToString()}",
                                scoreAtBuy = result.score, createdAt = System.currentTimeMillis(),
                                buyTime = java.time.LocalTime.now().toString().take(8)
                            )
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "✅ 已買入 ${result.name}", Toast.LENGTH_SHORT).show()
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
                neutralBtn?.text = "▶ 買入 (條件不足)"
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
            CrossTabBus.command.collect { cmd ->
                if (cmd != null) {
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
                    CrossTabBus.consumeCommand()
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
}

