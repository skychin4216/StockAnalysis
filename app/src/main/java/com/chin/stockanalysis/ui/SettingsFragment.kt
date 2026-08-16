package com.chin.stockanalysis.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProviderConfig
import com.chin.stockanalysis.config.AgentRoute
import com.chin.stockanalysis.config.FeatureFlagManager
import com.chin.stockanalysis.config.GlobalMode
import com.chin.stockanalysis.config.LanguageManager
import com.chin.stockanalysis.databinding.FragmentSettingsBinding
import com.chin.stockanalysis.notification.TradeNotifier
import com.chin.stockanalysis.stock.StockService
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.backtest.BacktestParamsLoader

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var apiConfigManager: ApiConfigManager
    private val stockService: StockService by lazy {
        val multiSourceRepo = StockDataSourceFactory.createDefaultRepository(requireContext())
        StockService(repository = multiSourceRepo)
    }

    /** 导入参数 JSON：打开系统文件选择器 */
    private val importParamsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val text = requireContext().contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("无法读取文件")
                val err = BacktestParamsLoader.importParams(requireContext(), text)
                if (err == null) {
                    refreshParamsInfo()
                    Toast.makeText(requireContext(), "参数导入成功，已立即生效", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "导入失败: $err", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

    /** 导出当前参数 JSON：系统文件保存 */
    private val exportParamsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val text = BacktestParamsLoader.exportParams(requireContext())
                    ?: throw Exception("无参数可导出")
                requireContext().contentResolver
                    .openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: throw Exception("无法写入文件")
                Toast.makeText(requireContext(), "参数已导出", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        apiConfigManager = ApiConfigManager.getInstance(requireContext())
        setupUI()
    }

    private fun setupUI() {
        refreshProviderInfo()
        refreshLanguageInfo()
        binding.apply {
            btnChangeApiKey.setOnClickListener { showApiConfigDialog() }
            btnClearCache.setOnClickListener { clearAppCache() }
            btnLanguage.setOnClickListener { showLanguageDialog() }
            tvAbout.text = buildAboutText()
        }
        setupParamsManagement()
        setupAgentFramework()
        setupWechatNotification()
    }

    /** 回溯参数：导入 / 导出 / 恢复内置（用户需求：导入他人拟合参数即用，免回溯） */
    private fun setupParamsManagement() {
        refreshParamsInfo()
        binding.btnImportParams.setOnClickListener {
            importParamsLauncher.launch(arrayOf("application/json", "text/json", "*/*"))
        }
        binding.btnExportParams.setOnClickListener {
            exportParamsLauncher.launch("backtest_params.json")
        }
        binding.btnResetParams.setOnClickListener {
            if (BacktestParamsLoader.resetToBuiltIn(requireContext())) {
                refreshParamsInfo()
                Toast.makeText(requireContext(), "已恢复内置参数", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "恢复失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshParamsInfo() {
        binding.tvParamsSource.text = "当前参数来源: ${BacktestParamsLoader.sourceLabel(requireContext())}" +
            "（版本 ${BacktestParamsLoader.version(requireContext())}）"
    }

    /** 绑定做T 微信通知 & 自动执行 配置（Phase 11） */
    private fun setupWechatNotification() {
        binding.apply {
            swWechatEnabled.isChecked = TradeNotifier.isWechatEnabled(requireContext())
            swWechatEnabled.setOnCheckedChangeListener { _, isChecked ->
                TradeNotifier.setWechatEnabled(requireContext(), isChecked)
            }
            etServerChanKey.setText(TradeNotifier.getServerChanKey(requireContext()))
            etServerChanKey.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    TradeNotifier.setServerChanKey(requireContext(), s?.toString()?.trim().orEmpty())
                }
            })

            swPushPlusEnabled.isChecked = TradeNotifier.isPushPlusEnabled(requireContext())
            swPushPlusEnabled.setOnCheckedChangeListener { _, isChecked ->
                TradeNotifier.setPushPlusEnabled(requireContext(), isChecked)
            }
            etPushPlusToken.setText(TradeNotifier.getPushPlusToken(requireContext()))
            etPushPlusToken.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    TradeNotifier.setPushPlusToken(requireContext(), s?.toString()?.trim().orEmpty())
                }
            })

            swAutoExecute.isChecked = TradeNotifier.isAutoExecuteEnabled(requireContext())
            swAutoExecute.setOnCheckedChangeListener { _, isChecked ->
                TradeNotifier.setAutoExecuteEnabled(requireContext(), isChecked)
            }
            val threshold = TradeNotifier.getAutoExecuteThreshold(requireContext())
            etAutoExecThreshold.setText(if (threshold >= 0) "%.2f".format(threshold) else "0.70")
            etAutoExecThreshold.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.trim()?.toDoubleOrNull() ?: return
                    if (v in 0.0..1.0) {
                        TradeNotifier.setAutoExecuteThreshold(requireContext(), v)
                    }
                }
            })
        }
    }

    private fun refreshLanguageInfo() {
        val currentLang = LanguageManager.getCurrentLanguageName(requireContext())
        binding.tvCurrentLanguage.text = "当前语言: $currentLang"
    }

    private fun showLanguageDialog() {
        val context = requireContext()
        val languages = LanguageManager.SUPPORTED_LANGUAGES
        val currentLang = LanguageManager.getSavedLanguage(context)

        // 构建选项列表：第一项是"跟随系统"
        val displayNames = mutableListOf("跟随系统")
        displayNames.addAll(languages.map { it.second })

        val currentIndex = if (currentLang == null) 0
        else languages.indexOfFirst { it.first == currentLang } + 1

        AlertDialog.Builder(context)
            .setTitle("选择语言")
            .setSingleChoiceItems(
                displayNames.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                val selectedCode = if (which == 0) null
                else languages[which - 1].first

                LanguageManager.setLanguage(context, selectedCode)
                refreshLanguageInfo()
                dialog.dismiss()

                // 重建 Activity 以应用新语言
                Toast.makeText(context, "语言已切换", Toast.LENGTH_SHORT).show()
                activity?.recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshProviderInfo() {
        val currentConfig = apiConfigManager.getCurrentProviderConfig()
        binding.apply {
            tvCurrentProvider.text = "当前 AI 提供商: ${currentConfig?.name ?: "未配置"}"
            val keyStatus = if (currentConfig?.apiKey.isNullOrBlank()) {
                "使用服务器端默认 Key"
            } else {
                "使用用户自定义 Key"
            }
            tvModel.text = "模型: ${currentConfig?.model ?: "N/A"}\nAPI Key: $keyStatus"
        }
    }

    private fun showApiConfigDialog() {
        val context = requireContext()
        val providers = apiConfigManager.builtInProviders
        val selectedProviderId = apiConfigManager.getSelectedProviderId()
        val providerIndex = providers.indexOfFirst { it.id == selectedProviderId }.coerceAtLeast(0)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val providerSpinner = Spinner(context)
        val providerAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { providerLabel(it) }
        )
        providerSpinner.adapter = providerAdapter
        providerSpinner.setSelection(providerIndex)

        val modelSpinner = Spinner(context)
        val modelAdapter = ArrayAdapter<String>(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            mutableListOf()
        )
        modelSpinner.adapter = modelAdapter

        val apiKeyInput = EditText(context).apply {
            hint = "可选：填写你自己的 API Key，留空则使用服务器默认 Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(apiConfigManager.getUserApiKey(providers[providerIndex].id).orEmpty())
            setSelection(text?.length ?: 0)
        }

        val descriptionView = TextView(context).apply {
            textSize = 12f
            setPadding(0, 12, 0, 8)
        }

        // ── 刷新对话框状态 ──
        fun refreshDialogForProvider(index: Int) {
            val provider = providers[index]
            val models = apiConfigManager.getProviderModels(provider.id)
            val selectedModel = apiConfigManager.getSelectedModel(provider.id)
            modelAdapter.clear()
            modelAdapter.addAll(models)
            modelAdapter.notifyDataSetChanged()
            modelSpinner.setSelection(models.indexOf(selectedModel).coerceAtLeast(0))
            apiKeyInput.setText(apiConfigManager.getUserApiKey(provider.id).orEmpty())
            apiKeyInput.setSelection(apiKeyInput.text?.length ?: 0)
            descriptionView.text = "${provider.description}\n\n不填写 API Key：使用服务器端默认 Key。\n填写 API Key：本机保存，并在请求时发送给服务器代理使用。"
        }

        // ── 添加自定义模型弹窗 ──
        fun showAddCustomModelDialog(providerIndex: Int) {
            val provider = providers[providerIndex]
            val input = EditText(context).apply {
                hint = "输入模型名称，如 doubao-seed-2-0-mini-260428"
                inputType = InputType.TYPE_CLASS_TEXT
                val currentModels = apiConfigManager.getUserCustomModels(provider.id)
                if (currentModels.isNotEmpty()) {
                    setText(currentModels.first())
                }
            }

            val customListLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 8)
            }

            val customModels = apiConfigManager.getUserCustomModels(provider.id)
            if (customModels.isNotEmpty()) {
                val title = TextView(context).apply {
                    text = "已添加的自定义模型："
                    textSize = 12f
                    setPadding(0, 0, 0, 4)
                }
                customListLayout.addView(title)
                for (cm in customModels) {
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    val modelText = TextView(context).apply {
                        text = "  • $cm"
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val deleteBtn = android.widget.Button(context).apply {
                        text = "✕"
                        textSize = 10f
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44)
                        setPadding(8, 0, 8, 0)
                        setOnClickListener {
                            apiConfigManager.removeUserCustomModel(provider.id, cm)
                            refreshDialogForProvider(providerIndex)
                        }
                    }
                    row.addView(modelText)
                    row.addView(deleteBtn)
                    customListLayout.addView(row)
                }
                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
                }
                customListLayout.addView(spacer)
            }

            customListLayout.addView(input)

            AlertDialog.Builder(context)
                .setTitle("添加自定义模型（${provider.name}）")
                .setView(customListLayout)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加") { _, _ ->
                    val modelName = input.text?.toString()?.trim().orEmpty()
                    if (modelName.isNotBlank()) {
                        apiConfigManager.addUserCustomModel(provider.id, modelName)
                        refreshDialogForProvider(providerIndex)
                        Toast.makeText(context, "已添加模型: $modelName", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }

        // ── 添加自定义模型按钮 ──
        val addModelBtn = android.widget.Button(context).apply {
            text = "➕ 添加自定义模型"
            textSize = 12f
            setPadding(0, 8, 0, 8)
            setOnClickListener {
                showAddCustomModelDialog(providerSpinner.selectedItemPosition)
            }
        }

        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshDialogForProvider(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        root.addView(label(context, "API 提供商"))
        root.addView(providerSpinner)
        root.addView(label(context, "模型"))
        root.addView(modelSpinner)
        root.addView(addModelBtn)
        root.addView(label(context, "用户 API Key（可选）"))
        root.addView(apiKeyInput)
        root.addView(descriptionView)

        refreshDialogForProvider(providerIndex)

        AlertDialog.Builder(context)
            .setTitle("API 配置")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val provider = providers[providerSpinner.selectedItemPosition]
                val model = modelSpinner.selectedItem?.toString().orEmpty()
                val apiKey = apiKeyInput.text?.toString().orEmpty()

                apiConfigManager.setSelectedProviderId(provider.id)
                if (model.isNotBlank()) {
                    apiConfigManager.setSelectedModel(provider.id, model)
                }
                apiConfigManager.saveUserApiKey(provider.id, apiKey)

                refreshProviderInfo()
                Toast.makeText(context, "API 配置已保存", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun label(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setPadding(0, 12, 0, 4)
        }
    }

    private fun providerLabel(config: ApiProviderConfig): String {
        val tag = if (config.isFree) "🆓" else "💳"
        return "$tag ${config.name}"
    }

    private fun clearAppCache() {
        stockService.clearCache()
        binding.tvCacheInfo.text = "缓存已清除"
        Toast.makeText(requireContext(), "股票行情缓存已清除", Toast.LENGTH_SHORT).show()
    }

    private fun setupAgentFramework() {
        binding.apply {
            when (FeatureFlagManager.globalMode) {
                GlobalMode.LEGACY -> rbOldSystem.isChecked = true
                GlobalMode.AGENT -> rbNewAgent.isChecked = true
                GlobalMode.HYBRID -> rbPerModule.isChecked = true
            }

            rgGlobalMode.setOnCheckedChangeListener { _, checkedId ->
                val mode = when (checkedId) {
                    rbOldSystem.id -> GlobalMode.LEGACY
                    rbNewAgent.id -> GlobalMode.AGENT
                    rbPerModule.id -> GlobalMode.HYBRID
                    else -> GlobalMode.LEGACY
                }
                FeatureFlagManager.globalMode = mode
                updateModuleSwitchesEnabled(mode)
                when (mode) {
                    GlobalMode.LEGACY -> {
                        setAllModuleRoutes(AgentRoute.LEGACY)
                        FeatureFlagManager.setAllPeriodRoutes(AgentRoute.LEGACY)
                    }
                    GlobalMode.AGENT -> {
                        setAllModuleRoutes(AgentRoute.AGENT_FRAMEWORK)
                        FeatureFlagManager.setAllPeriodRoutes(AgentRoute.AGENT_FRAMEWORK)
                    }
                    GlobalMode.HYBRID -> {}
                }
                refreshModuleSwitches()
            }

            refreshModuleSwitches()
            updateModuleSwitchesEnabled(FeatureFlagManager.globalMode)

            swStockPicking.setOnCheckedChangeListener { _, isChecked ->
                if (FeatureFlagManager.isHybrid) {
                    FeatureFlagManager.stockPickingRoute =
                        if (isChecked) AgentRoute.AGENT_FRAMEWORK else AgentRoute.LEGACY
                }
            }
            swStockAnalysis.setOnCheckedChangeListener { _, isChecked ->
                if (FeatureFlagManager.isHybrid) {
                    FeatureFlagManager.stockAnalysisRoute =
                        if (isChecked) AgentRoute.AGENT_FRAMEWORK else AgentRoute.LEGACY
                }
            }
            swTradeExecution.setOnCheckedChangeListener { _, isChecked ->
                if (FeatureFlagManager.isHybrid) {
                    FeatureFlagManager.tradeExecutionRoute =
                        if (isChecked) AgentRoute.AGENT_FRAMEWORK else AgentRoute.LEGACY
                }
            }
            swChat.setOnCheckedChangeListener { _, isChecked ->
                if (FeatureFlagManager.isHybrid) {
                    FeatureFlagManager.chatRoute =
                        if (isChecked) AgentRoute.AGENT_FRAMEWORK else AgentRoute.LEGACY
                }
            }
            swNewsMonitor.setOnCheckedChangeListener { _, isChecked ->
                if (FeatureFlagManager.isHybrid) {
                    FeatureFlagManager.newsMonitoringRoute =
                        if (isChecked) AgentRoute.AGENT_FRAMEWORK else AgentRoute.LEGACY
                }
            }
            swRiskManagement.setOnCheckedChangeListener { _, isChecked ->
                if (FeatureFlagManager.isHybrid) {
                    FeatureFlagManager.riskManagementRoute =
                        if (isChecked) AgentRoute.AGENT_FRAMEWORK else AgentRoute.LEGACY
                }
            }

            // ── 周期级别路线开关（Phase 10） ──
            setupPeriodRouteSwitch(swRouteUltraShort, HoldingPeriod.ULTRA_SHORT)
            setupPeriodRouteSwitch(swRouteShort, HoldingPeriod.SHORT)
            setupPeriodRouteSwitch(swRouteMid, HoldingPeriod.MID)
            setupPeriodRouteSwitch(swRouteLong, HoldingPeriod.LONG)

            // 通用 DAG 开关已移除（pipeline 已全面启用）
        }
    }

    /** 设置单个周期路线开关的初始状态和监听器（Phase 10） */
    private fun setupPeriodRouteSwitch(switch: android.widget.Switch, period: HoldingPeriod) {
        switch.isChecked = FeatureFlagManager.getRoute(period) == AgentRoute.AGENT_FRAMEWORK
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (FeatureFlagManager.isHybrid) {
                FeatureFlagManager.setRoute(
                    period,
                    if (isChecked) AgentRoute.AGENT_FRAMEWORK else AgentRoute.LEGACY
                )
            }
        }
    }

    private fun refreshModuleSwitches() {
        binding.apply {
            swStockPicking.isChecked = FeatureFlagManager.stockPickingRoute == AgentRoute.AGENT_FRAMEWORK
            swStockAnalysis.isChecked = FeatureFlagManager.stockAnalysisRoute == AgentRoute.AGENT_FRAMEWORK
            swTradeExecution.isChecked = FeatureFlagManager.tradeExecutionRoute == AgentRoute.AGENT_FRAMEWORK
            swChat.isChecked = FeatureFlagManager.chatRoute == AgentRoute.AGENT_FRAMEWORK
            swNewsMonitor.isChecked = FeatureFlagManager.newsMonitoringRoute == AgentRoute.AGENT_FRAMEWORK
            swRiskManagement.isChecked = FeatureFlagManager.riskManagementRoute == AgentRoute.AGENT_FRAMEWORK
            // 周期路线开关
            swRouteUltraShort.isChecked = FeatureFlagManager.getRoute(HoldingPeriod.ULTRA_SHORT) == AgentRoute.AGENT_FRAMEWORK
            swRouteShort.isChecked = FeatureFlagManager.getRoute(HoldingPeriod.SHORT) == AgentRoute.AGENT_FRAMEWORK
            swRouteMid.isChecked = FeatureFlagManager.getRoute(HoldingPeriod.MID) == AgentRoute.AGENT_FRAMEWORK
            swRouteLong.isChecked = FeatureFlagManager.getRoute(HoldingPeriod.LONG) == AgentRoute.AGENT_FRAMEWORK
        }
    }

    private fun setAllModuleRoutes(route: AgentRoute) {
        FeatureFlagManager.stockPickingRoute = route
        FeatureFlagManager.stockAnalysisRoute = route
        FeatureFlagManager.tradeExecutionRoute = route
        FeatureFlagManager.chatRoute = route
        FeatureFlagManager.newsMonitoringRoute = route
        FeatureFlagManager.riskManagementRoute = route
    }

    private fun updateModuleSwitchesEnabled(mode: GlobalMode) {
        val enabled = (mode == GlobalMode.HYBRID)
        val alpha = if (enabled) 1.0f else 0.4f
        binding.apply {
            tvModuleConfigTitle.alpha = alpha
            swStockPicking.isEnabled = enabled; swStockPicking.alpha = alpha
            swStockAnalysis.isEnabled = enabled; swStockAnalysis.alpha = alpha
            swTradeExecution.isEnabled = enabled; swTradeExecution.alpha = alpha
            swChat.isEnabled = enabled; swChat.alpha = alpha
            swNewsMonitor.isEnabled = enabled; swNewsMonitor.alpha = alpha
            swRiskManagement.isEnabled = enabled; swRiskManagement.alpha = alpha
            // 周期路线开关（同样只在 HYBRID 模式下可操作）
            tvPeriodRouteTitle.alpha = alpha
            swRouteUltraShort.isEnabled = enabled; swRouteUltraShort.alpha = alpha
            swRouteShort.isEnabled = enabled; swRouteShort.alpha = alpha
            swRouteMid.isEnabled = enabled; swRouteMid.alpha = alpha
            swRouteLong.isEnabled = enabled; swRouteLong.alpha = alpha
        }
    }

    private fun buildAboutText(): String = """
        StockAnalysis v2.0
        
        功能特性：
        • AI 智能聊天辅助（多 Provider / 多模型）
        • 实时股票行情查询（新浪 + 腾讯 + 东方财富自动降级）
        • 意图识别 + 股票数据自动注入 AI Prompt
        • K 线分析 + MA/MACD 技术指标
        • 3 秒智能缓存
        
        技术栈：
        • Kotlin + Android ViewBinding + Fragment
        • BottomNavigationView + ViewPager2
        • OkHttp + org.json/Gson
        • MPAndroidChart
        • DAG 拓扑引擎（Pipeline 并行调度）
        
        © 2026 StockAnalysis Team
    """.trimIndent()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}