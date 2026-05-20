package com.chin.stockanalysis.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProviderConfig
import com.chin.stockanalysis.databinding.FragmentSettingsBinding
import com.chin.stockanalysis.stock.StockService
import com.chin.stockanalysis.stock.data.StockDataSourceFactory

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var apiConfigManager: ApiConfigManager
    private val stockService: StockService by lazy {
        val multiSourceRepo = StockDataSourceFactory.createDefaultRepository(requireContext())
        StockService(repository = multiSourceRepo)
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
        binding.apply {
            btnChangeApiKey.setOnClickListener { showApiConfigDialog() }
            btnClearCache.setOnClickListener { clearAppCache() }
            tvAbout.text = buildAboutText()
        }
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
        
        © 2026 StockAnalysis Team
    """.trimIndent()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}