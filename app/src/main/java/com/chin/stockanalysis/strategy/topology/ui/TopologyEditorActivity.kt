package com.chin.stockanalysis.strategy.topology.ui

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.xml.NodeRegistry
import com.chin.stockanalysis.strategy.topology.xml.PipelineXmlParser
import com.chin.stockanalysis.strategy.topology.xml.UseCaseLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * ## TopologyEditorActivity — Pipeline 拓扑编辑器（全屏 v2）
 *
 * ### 设计目标
 * 沉浸式全屏展示，画布占满整屏，不再使用 ScrollView 包裹（[NodeCanvasView] 内置平移/缩放）。
 *
 * ### 布局结构
 * ```
 * ┌───────────────────────────────────────────────────────────┐
 * │ [✕]  超短线量化          ultra_short_pipeline.xml        │ ← 悬浮顶部工具条
 * │       ▶ UseCase · 15 节点 · 12 连线                        │    (半透明深色渐变)
 * ├───────────────────────────────────────────────────────────┤
 * │                                                           │
 * │                NodeCanvasView (全屏画布)                    │
 * │        单指拖拽空白 = 平移 · 双指捏合 = 缩放                  │
 * │                                                           │
 * │  [📦 模板] [🖼 适配]                                       │ ← 右下角悬浮操作
 * ├───────────────────────────────────────────────────────────┤
 * │ 状态信息 (悬浮左下角 chip)                                    │
 * └───────────────────────────────────────────────────────────┘
 * ```
 *
 * ### 交互
 * - 顶部工具条：返回 / 标题（UseCase 名 + Pipeline 文件信息）/ 保存 / 执行
 * - 右下角按钮组：打开模板面板 / 适配全图（zoomToFit）
 * - 模板面板（右侧悬浮抽屉）：UseCase 快速切换 · Pipeline 快速加载 · Node 模板添加
 * - 画布节点点击 → 选中；长按 → 菜单（配置 / 删除）；A 拖到 B → 连线
 */
class TopologyEditorActivity : AppCompatActivity() {

    private lateinit var viewModel: TopologyEditorViewModel
    private lateinit var canvasView: NodeCanvasView

    // 顶部工具条
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView

    // 底部状态 chip
    private lateinit var statusText: TextView

    // 右侧悬浮面板
    private lateinit var sidePanel: LinearLayout
    private lateinit var panelContent: LinearLayout
    private var panelVisible = false

    /** 当前 UseCase id（用于执行） */
    private var currentUseCaseId = "mid_term"

    /** 侧边栏各分区展开状态（默认全部展开） */
    private val sectionExpanded = mutableMapOf(
        "usecase" to true,
        "pipeline" to true,
        "node" to true
    )

    // 颜色常量
    private val cBg = Color.parseColor("#0B1220")
    private val cBar = Color.parseColor("#CC0F1B30")
    private val cBarBorder = Color.parseColor("#2A3B5A")
    private val cText = Color.WHITE
    private val cSub = Color.parseColor("#8FA3C0")
    private val cPanel = Color.parseColor("#E6101B33")
    private val cItem = Color.parseColor("#0E223D")
    private val cItemText = Color.parseColor("#D6E2F5")
    private val cAccent = Color.parseColor("#3B82F6")

    // ════════════════════════════════════════════════════
    // 生命周期
    // ════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 默认横屏显示（DAG 横向链路更适合宽屏）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        viewModel = ViewModelProvider(this)[TopologyEditorViewModel::class.java]
        ensureNodeRegistryInitialized()

        // ── 沉浸式全屏（edge-to-edge，隐藏系统状态栏/导航栏） ──
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(cBg)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 画布（全屏）
        canvasView = NodeCanvasView(this)
        root.addView(canvasView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 悬浮层
        root.addView(buildTopBar(), topBarParams())
        root.addView(buildStatusChip(), statusChipParams())
        root.addView(buildSidePanel(), sidePanelParams())
        root.addView(buildFloatingActions(), floatingActionsParams())

        setContentView(root)
        applySystemBarPadding(root)

        // 画布交互回调
        canvasView.listener = object : NodeCanvasView.NodeCanvasListener {
            override fun onNodeSelected(node: VisualNode?) {
                if (node != null) {
                    statusText.text = "选中: ${node.name} (${node.id}) | module: ${node.module}"
                } else {
                    statusText.text = currentPipelineSummary()
                }
            }

            override fun onNodeLongPressed(node: VisualNode) {
                showNodeContextMenu(node)
            }

            override fun onLinkCreated(fromId: String, toId: String) {
                viewModel.addLink(fromId, toId)
                syncCanvasFromViewModel()
                statusText.text = "已连线: $fromId → $toId"
            }

            override fun onLinkTapped(link: VisualLink) {
                val fromNode = canvasView.canvasState.nodes.find { it.id == link.fromId }
                val toNode = canvasView.canvasState.nodes.find { it.id == link.toId }
                if (fromNode != null && toNode != null) {
                    canvasView.canvasState.selectedNodeId = link.toId
                    canvasView.panToNode(toNode)
                    statusText.text = "连线: ${fromNode.name} → ${toNode.name}"
                    AlertDialog.Builder(this@TopologyEditorActivity)
                        .setTitle("${fromNode.name} → ${toNode.name}")
                        .setMessage("from: ${link.fromId}\nto: ${link.toId}")
                        .setPositiveButton("确定", null)
                        .setNegativeButton("🗑️ 删除连线") { _, _ ->
                            viewModel.removeLink(link.fromId, link.toId)
                            syncCanvasFromViewModel()
                            statusText.text = "已删除连线"
                        }
                        .show()
                }
            }
        }

        // 观察 ViewModel 状态消息
        viewModel.statusMessage.observe(this) { msg ->
            statusText.text = msg
        }

        // 从 Intent 读取 usecase_id 并加载
        val usecaseId = intent.getStringExtra("usecase_id") ?: "mid_term"
        loadUseCase(usecaseId)
    }

    // ════════════════════════════════════════════════════
    // 布局构建
    // ════════════════════════════════════════════════════

    /** 顶部悬浮工具条：返回 / 标题 / 操作按钮 */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setBackgroundColor(cBar)
        }
        // 底部 1px 分隔线
        bar.addView(View(this).apply {
            setBackgroundColor(cBarBorder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }, 0)

        val backBtn = flatButton("✕", 0xFF64748B.toInt()) {
            finish()
        }
        bar.addView(backBtn)

        // 标题列
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleText = TextView(this).apply {
            text = "Pipeline 编辑器"
            setTextColor(cText)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        titleCol.addView(titleText)
        subtitleText = TextView(this).apply {
            text = "加载中…"
            setTextColor(cSub)
            textSize = 10f
            maxLines = 1
        }
        titleCol.addView(subtitleText)
        bar.addView(titleCol)

        val fitBtn = accentButton("🖼 适配", 0xFF059669.toInt()) {
            canvasView.zoomToFit()
        }
        bar.addView(fitBtn)

        val panelBtn = accentButton("📦 模板", 0xFF7C3AED.toInt()) {
            togglePanel()
        }
        bar.addView(panelBtn)

        val saveBtn = accentButton("💾 保存", 0xFF0284C7.toInt()) {
            savePipeline()
        }
        bar.addView(saveBtn)

        val runBtn = accentButton("▶ 执行", 0xFFDC2626.toInt()) {
            runPipeline()
        }
        bar.addView(runBtn)

        return bar
    }

    private fun topBarParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.TOP
    )

    /** 底部悬浮状态 chip（左下角） */
    private fun buildStatusChip(): View {
        statusText = TextView(this).apply {
            text = "就绪"
            setTextColor(cSub)
            textSize = 11f
            maxLines = 4
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundResource(android.R.color.transparent)
            background = roundedRect(Color.parseColor("#CC0B1526"), dp(14))
            setOnClickListener { canvasView.zoomToFit() }
        }
        return statusText
    }

    private fun statusChipParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.START
    ).apply { setMargins(dp(12), 0, 0, dp(12)) }

    /** 右下角悬浮操作按钮组 */
    private fun buildFloatingActions(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(android.R.color.transparent)
            background = roundedRect(Color.parseColor("#E60E1B30"), dp(12))
        }
        col.addView(iconButton("📦", 0xFF7C3AED.toInt()) { togglePanel() })
        col.addView(iconButton("🖼", 0xFF059669.toInt()) { canvasView.zoomToFit() })
        return col
    }

    private fun floatingActionsParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.END
    ).apply { setMargins(0, 0, dp(12), dp(12)) }

    /** 右侧悬浮模板面板（默认隐藏） */
    private fun buildSidePanel(): View {
        sidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cPanel)
            visibility = View.GONE
        }

        // 面板标题
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#1A2B4A"))
        }
        val headerTitle = TextView(this).apply {
            text = "资源面板"
            setTextColor(cText)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(headerTitle)
        val collapseBtn = flatButton("✕", 0xFF64748B.toInt()) { togglePanel() }
        header.addView(collapseBtn)
        sidePanel.addView(header)

        // 可滚动内容
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        panelContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        scroll.addView(panelContent, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        sidePanel.addView(scroll)

        // 底部：旋转屏幕
        val orientationBtn = accentButton("🔄 旋转屏幕", 0xFF334155.toInt()) { toggleOrientation() }
        sidePanel.addView(orientationBtn)

        refreshPanelContent()
        return sidePanel
    }

    private fun sidePanelParams() = FrameLayout.LayoutParams(
        dp(250), ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.END
    )

    private fun togglePanel() {
        panelVisible = !panelVisible
        sidePanel.visibility = if (panelVisible) View.VISIBLE else View.GONE
        if (panelVisible) refreshPanelContent()
    }

    // ════════════════════════════════════════════════════
    // 面板内容
    // ════════════════════════════════════════════════════

    private fun refreshPanelContent() {
        panelContent.removeAllViews()

        // UseCase 分区
        addPanelSection("usecase", "📋 UseCase 周期") {
            val usecases = listAssetFiles("usecases")
                ?.filter { it.endsWith("_usecase.xml") }
                ?: emptyList()
            if (usecases.isEmpty()) {
                addPanelItem("(无 UseCase 文件)") { }
            }
            for (ucFile in usecases) {
                val ucName = extractXmlName("usecases/$ucFile")
                    ?: ucFile.removeSuffix("_usecase.xml").replace("_", " ")
                addPanelItem(ucName) { loadUseCaseFile(ucFile) }
            }
            addPanelItem("🌐 Pipeline 全景") { showPipelinePanorama() }
        }

        // Pipeline 分区
        addPanelSection("pipeline", "📊 Pipeline 文件") {
            val pipelines = listAssetFiles("usecases")
                ?.filter { it.endsWith("_pipeline.xml") }
                ?: emptyList()
            if (pipelines.isEmpty()) {
                addPanelItem("(无 Pipeline 文件)") { }
            }
            for (pipeFile in pipelines) {
                val pipeName = extractXmlName("usecases/$pipeFile")
                    ?: pipeFile.removeSuffix(".xml").replace("_", " ")
                addPanelItem(pipeName) { loadPipelineFile(pipeFile) }
            }
        }

        // Node 模板分区
        addPanelSection("node", "📦 Node 模板") {
            val modules = NodeRegistry.listModules().sorted()
            if (modules.isEmpty()) {
                addPanelItem("(无可用 module，请先初始化)") { }
            }
            for (module in modules) {
                val displayName = moduleDisplayName(module)
                val nodeType = inferNodeType(module)
                addPanelItem(displayName) {
                    addNodeFromPalette(module, displayName, nodeType)
                }
            }
        }
    }

    private fun addPanelItem(label: String, onClick: () -> Unit) {
        val itemBtn = TextView(this).apply {
            text = "• $label"
            textSize = 11f
            setTextColor(cItemText)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundResource(android.R.color.transparent)
            background = roundedRect(cItem, dp(8))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2), 0, dp(2)) }
        }
        panelContent.addView(itemBtn)
    }

    private fun addPanelSection(key: String, title: String, contentBuilder: () -> Unit) {
        val isExpanded = sectionExpanded[key] ?: true

        val header = Button(this).apply {
            text = if (isExpanded) "▼ $title" else "▶ $title"
            textSize = 12f
            setTextColor(cText)
            setBackgroundColor(Color.TRANSPARENT)
            background = roundedRect(Color.parseColor("#22365C"), dp(8))
            isAllCaps = false
            setOnClickListener {
                sectionExpanded[key] = !(sectionExpanded[key] ?: true)
                refreshPanelContent()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)
            ).apply { setMargins(0, dp(6), 0, dp(4)) }
        }
        panelContent.addView(header)

        if (isExpanded) {
            contentBuilder()
        }
    }

    /** 切换横屏/竖屏 */
    private fun toggleOrientation() {
        val current = requestedOrientation
        if (current == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
            current == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /** 显示 Pipeline 全景图（WebView 加载 pipeline_framework.html） */
    private fun showPipelinePanorama() {
        val dialog = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1923"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#1A237E"))
        }
        val titleTv = TextView(this).apply {
            text = "Pipeline 全景图"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleTv)

        val closeBtn = Button(this).apply {
            text = "关闭"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        titleRow.addView(closeBtn)
        root.addView(titleRow)

        val webView = android.webkit.WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            loadUrl("file:///android_asset/pipeline_framework.html")
        }
        root.addView(webView)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()
    }

    // ════════════════════════════════════════════════════
    // 节点操作
    // ════════════════════════════════════════════════════

    private fun addNodeFromPalette(module: String, displayName: String, nodeType: NodeType) {
        viewModel.addNode(module)
        syncCanvasFromViewModel()
        statusText.text = "已添加: $displayName"
    }

    private fun showNodeContextMenu(node: VisualNode) {
        val options = arrayOf("📋 配置节点", "🗑️ 删除节点", "取消")
        AlertDialog.Builder(this)
            .setTitle(node.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showNodeConfigDialog(node)
                    1 -> {
                        viewModel.removeNode(node.id)
                        syncCanvasFromViewModel()
                        statusText.text = "已删除: ${node.name}"
                    }
                }
            }
            .show()
    }

    private fun showNodeConfigDialog(node: VisualNode) {
        val editableNode = viewModel.getNodes().find { it.id == node.id }
        if (editableNode == null) {
            statusText.text = "找不到节点: ${node.id}"
            return
        }

        val configItems = editableNode.config.entries.toList()
        val sb = StringBuilder()
        sb.appendLine("节点 ID: ${editableNode.id}")
        sb.appendLine("Module: ${editableNode.module}")
        sb.appendLine("Name: ${editableNode.name.ifBlank { editableNode.module }}")
        sb.appendLine()
        sb.appendLine("配置项:")
        if (configItems.isEmpty()) {
            sb.appendLine("  (无)")
        } else {
            for ((k, v) in configItems) {
                sb.appendLine("  $k = $v")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("节点配置")
            .setMessage(sb.toString())
            .setPositiveButton("确定", null)
            .setNeutralButton("添加配置") { _, _ ->
                showAddConfigDialog(editableNode.id)
            }
            .show()
    }

    private fun showAddConfigDialog(nodeId: String) {
        val input = EditText(this).apply {
            hint = "key=value（如 topN=10）"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("添加配置项")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val parts = input.text.toString().split("=", limit = 2)
                if (parts.size == 2) {
                    val node = viewModel.getNodes().find { it.id == nodeId }
                    if (node != null) {
                        val newConfig = node.config.toMutableMap()
                        newConfig[parts[0].trim()] = parts[1].trim()
                        viewModel.updateNodeConfig(nodeId, newConfig)
                        syncCanvasFromViewModel()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ════════════════════════════════════════════════════
    // 加载 / 保存 / 执行
    // ════════════════════════════════════════════════════

    /**
     * 根据 usecase id 加载对应 UseCase 文件。
     * 支持 "ultra_short" / "short_term" / "mid_term" / "long_term" / "real_holding" 等周期 id。
     */
    private fun loadUseCase(usecaseId: String) {
        val id = usecaseId.trim().lowercase()
        val usecaseFile = when (id) {
            "mid_term", "midterm" -> "mid_term_usecase.xml"
            "short_term", "shortterm" -> "short_term_usecase.xml"
            "ultra_short", "ultrashort" -> "ultra_short_usecase.xml"
            "long_term", "longterm" -> "long_term_usecase.xml"
            "real_holding", "realholding", "real_hold" -> "real_holding_usecase.xml"
            "screening" -> "screening_usecase.xml"
            else -> if (id.endsWith(".xml")) id else "${id}_usecase.xml"
        }
        currentUseCaseId = id
        loadUseCaseFile(usecaseFile)
    }

    private fun loadUseCaseFile(fileName: String) {
        lifecycleScope.launch {
            try {
                val useCaseConfig = withContext(Dispatchers.IO) {
                    PipelineXmlParser.loadUseCaseFromAssets(
                        applicationContext, "usecases/$fileName"
                    )
                }
                if (useCaseConfig != null) {
                    titleText.text = useCaseConfig.name.ifBlank {
                        fileName.removeSuffix("_usecase.xml")
                    }
                    subtitleText.text = "$fileName · 点击「📦 模板」可切换资源"
                    statusText.text = "✅ UseCase: ${useCaseConfig.name} ($fileName)"
                    // 加载第一个 Pipeline 引用
                    val pipeFile = useCaseConfig.steps.firstOrNull { it is PipelineXmlParser.StepRef.pipeline }
                        ?.let { (it as PipelineXmlParser.StepRef.pipeline).ref }
                    if (pipeFile != null) {
                        loadPipelineFile(pipeFile.substringAfterLast("/"))
                    }
                } else {
                    // UseCase 文件不存在，尝试直接加载同名 Pipeline
                    statusText.text = "⚠️ 未找到 $fileName，尝试直接加载 Pipeline…"
                    loadPipelineFile(fileName.removeSuffix("_usecase.xml") + "_pipeline.xml")
                }
            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
            }
        }
    }

    private fun loadPipelineFile(fileName: String) {
        lifecycleScope.launch {
            try {
                // 自动检测 V1 Pipeline / V2 DagPipeline 格式
                val editable = withContext(Dispatchers.IO) {
                    val xml = applicationContext.assets.open("usecases/$fileName").bufferedReader().use { it.readText() }
                    if (PipelineXmlParser.isDagPipelineXml(xml)) {
                        parseDagXmlToEditable(xml)
                    } else {
                        val pipeline = PipelineXmlParser.parsePipeline(xml, applicationContext)
                        if (pipeline != null) PipelineXmlParser.pipelineToEditable(pipeline) else null
                    }
                }
                if (editable != null) {
                    viewModel.loadFromEditable(editable)
                    titleText.text = editable.name.ifBlank { fileName.removeSuffix(".xml") }
                    subtitleText.text = "$fileName · ${viewModel.getNodes().size} 节点 · ${viewModel.getLinks().size} 连线"
                    syncCanvasFromViewModel()
                    canvasView.post { canvasView.zoomToFit() }
                    statusText.text = "✅ 已加载: ${editable.name} · ${viewModel.getNodes().size} 节点 · ${viewModel.getLinks().size} 连线"
                } else {
                    statusText.text = "❌ 文件不存在或解析失败: usecases/$fileName"
                }
            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
            }
        }
    }

    /**
     * 直接解析 DAG XML 为 EditablePipeline（不依赖 NodeRegistry 实例化）。
     * 提取 <Node id="..." name="..." module="..." /> 和 <Link source="..." target="..." />。
     */
    private fun parseDagXmlToEditable(xml: String): PipelineXmlParser.EditablePipeline? {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(java.io.StringReader(xml))

            var pipelineId = ""
            var pipelineName = ""
            var pipelineDesc = ""
            val nodes = mutableListOf<PipelineXmlParser.EditableNode>()
            val links = mutableListOf<PipelineXmlParser.EditableLink>()
            var currentGroupId = ""  // 追踪当前所在的 Pipeline 分组

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "DagPipeline", "dagPipeline" -> {
                            pipelineId = parser.getAttributeValue(null, "id") ?: ""
                            pipelineName = parser.getAttributeValue(null, "name") ?: ""
                            pipelineDesc = parser.getAttributeValue(null, "description") ?: ""
                        }
                        // ── 子 Pipeline 分组块 ──
                        "Pipeline" -> {
                            val gId = parser.getAttributeValue(null, "id")
                            if (!gId.isNullOrBlank()) currentGroupId = gId
                        }
                        "Node", "node" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val name = parser.getAttributeValue(null, "name") ?: ""
                            val module = parser.getAttributeValue(null, "module") ?: ""
                            if (id.isNotBlank() && module.isNotBlank()) {
                                val config = mutableMapOf<String, String>()
                                val depth = parser.depth
                                var ne = parser.next()
                                while (!(ne == XmlPullParser.END_TAG && parser.depth == depth &&
                                    (parser.name == "Node" || parser.name == "node"))) {
                                    if (ne == XmlPullParser.START_TAG && parser.name == "config") {
                                        val cDepth = parser.depth
                                        var ce = parser.next()
                                        while (!(ce == XmlPullParser.END_TAG && parser.depth == cDepth &&
                                            parser.name == "config")) {
                                            if (ce == XmlPullParser.START_TAG && parser.name == "param") {
                                                val k = parser.getAttributeValue(null, "name") ?: ""
                                                val v = parser.getAttributeValue(null, "value") ?: ""
                                                if (k.isNotBlank()) config[k] = v
                                            }
                                            ce = parser.next()
                                        }
                                    }
                                    ne = parser.next()
                                }
                                nodes.add(PipelineXmlParser.EditableNode(
                                    id = id, module = module, name = name, config = config,
                                    pipelineGroup = currentGroupId
                                ))
                            }
                        }
                        "Link", "link" -> {
                            // 支持两种格式：
                            // 属性格式: <Link source="..." target="..." />
                            // 子标签格式: <Link><SourceNodeId>...</SourceNodeId><TargetNodeId>...</TargetNodeId></Link>
                            var from = parser.getAttributeValue(null, "source")
                                ?: parser.getAttributeValue(null, "from") ?: ""
                            var to = parser.getAttributeValue(null, "target")
                                ?: parser.getAttributeValue(null, "to") ?: ""

                            // 如果属性格式没拿到，尝试子标签格式
                            if (from.isBlank() || to.isBlank()) {
                                val depth = parser.depth
                                var le = parser.next()
                                while (!(le == XmlPullParser.END_TAG && parser.depth == depth &&
                                    (parser.name == "Link" || parser.name == "link"))) {
                                    if (le == XmlPullParser.START_TAG) {
                                        when (parser.name) {
                                            "SourceNodeId", "sourceNodeId", "source" -> {
                                                if (from.isBlank()) from = parser.nextText().trim()
                                            }
                                            "TargetNodeId", "targetNodeId", "target" -> {
                                                if (to.isBlank()) to = parser.nextText().trim()
                                            }
                                        }
                                    }
                                    le = parser.next()
                                }
                            }

                            if (from.isNotBlank() && to.isNotBlank()) {
                                links.add(PipelineXmlParser.EditableLink(fromId = from, toId = to))
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "Pipeline" -> currentGroupId = ""
                    }
                }
                eventType = parser.next()
            }

            if (nodes.isEmpty()) return null

            PipelineXmlParser.EditablePipeline(
                id = pipelineId.ifBlank { "dag_pipeline" },
                name = pipelineName.ifBlank { "DAG Pipeline" },
                description = pipelineDesc,
                nodes = nodes,
                stages = listOf(
                    PipelineXmlParser.EditableStage(
                        name = "DAG 主链路",
                        parallel = false,
                        linkLists = listOf(
                            PipelineXmlParser.EditableLinkList(
                                name = "连线",
                                links = links
                            )
                        )
                    )
                )
            )
        } catch (e: Exception) {
            Log.e("TopologyEditor", "DAG XML 解析失败: ${e.message}", e)
            null
        }
    }

    private fun savePipeline() {
        try {
            val xml = viewModel.toXml()
            val fileName = viewModel.pipelineId.ifBlank { "new_pipeline" } + ".xml"
            val dir = getExternalFilesDir(null)
            val file = java.io.File(dir, fileName)
            file.writeText(xml, Charsets.UTF_8)
            statusText.text = "已保存: ${file.absolutePath}"
            Toast.makeText(this, "已保存到 ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            statusText.text = "保存失败: ${e.message}"
        }
    }

    private fun runPipeline() {
        statusText.text = "🔄 执行 Pipeline..."
        lifecycleScope.launch {
            try {
                val useCaseId = currentUseCaseId.ifBlank { "mid_term" }
                val tradeDate = com.chin.stockanalysis.ui.TradingDayPickerView
                    .recentTradingDay().toString()
                val result = withContext(Dispatchers.IO) {
                    UseCaseLoader.run(useCaseId, tradeDate)
                }
                val sb = StringBuilder()
                sb.appendLine(if (result.success) "✅ 执行成功" else "❌ 执行失败")
                if (result.errors.isNotEmpty()) {
                    sb.appendLine("错误: ${result.errors.values.joinToString("; ")}")
                }
                for ((pipeName, pr) in result.pipelineResults) {
                    sb.appendLine("📊 $pipeName:")
                    for ((_, flow) in pr.stockFlowLogs) {
                        sb.appendLine(
                            "  ${flow.nodeName}: ${flow.inputCount}→${flow.outputCount}" +
                                if (flow.filterCount > 0)
                                    " (过滤${flow.filterCount}: ${flow.filterReason})"
                                else ""
                        )
                    }
                }
                statusText.text = sb.toString().trim()
            } catch (e: Exception) {
                statusText.text = "执行失败: ${e.message}"
            }
        }
    }

    // ════════════════════════════════════════════════════
    // ViewModel ↔ Canvas 同步
    // ════════════════════════════════════════════════════

    private fun syncCanvasFromViewModel() {
        val editableNodes = viewModel.getNodes()
        val links = viewModel.getLinks()

        // EditableNode → VisualNode（保留已有座标，避免重布局抖动）
        val visualNodes = editableNodes.map { en ->
            val nodeType = inferNodeType(en.module)
            val existing = canvasView.canvasState.nodes.find { it.id == en.id }
            VisualNode(
                id = en.id,
                module = en.module,
                name = en.name.ifBlank { en.module },
                nodeType = nodeType,
                x = existing?.x ?: 0f,
                y = existing?.y ?: 0f,
                config = en.config,
                pipelineGroupId = en.pipelineGroup
            )
        }

        val visualLinks = links.map { l ->
            VisualLink(fromId = l.fromId, toId = l.toId, label = "")
        }

        // 若节点尚无座标（新加载/新增），执行自动布局
        val needsLayout = visualNodes.any { it.x == 0f && it.y == 0f }
        val nodeList = visualNodes.toMutableList()
        val linkList = visualLinks.toMutableList()
        if (needsLayout) {
            NodeAutoLayout.layout(nodeList, linkList)
        }

        canvasView.setNodes(nodeList)
        canvasView.setLinks(linkList)
        canvasView.invalidate()
    }

    /** 当前 Pipeline 概要（未选中节点时状态栏显示） */
    private fun currentPipelineSummary(): String {
        return "就绪 · ${viewModel.getNodes().size} 节点 · ${viewModel.getLinks().size} 连线"
    }

    // ════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    /** 创建圆角背景 Drawable */
    private fun roundedRect(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    /** 普通文字按钮（圆角） */
    private fun flatButton(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedRect(color, 8)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)
            ).apply { minWidth = dp(36) }
        }
    }

    /** 强调色按钮（圆角） */
    private fun accentButton(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.WHITE)
            background = roundedRect(color, 8)
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)
            ).apply {
                minWidth = dp(58)
                marginStart = dp(4)
            }
        }
    }

    /** 圆形图标按钮 */
    private fun iconButton(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedRect(color, 20)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                dp(40), dp(40)
            ).apply { setMargins(0, dp(6), 0, 0) }
        }
    }

    private fun listAssetFiles(path: String): List<String>? {
        return try {
            assets.list(path)?.toList()
        } catch (e: Exception) {
            null
        }
    }

    /** 安全初始化 NodeRegistry（避免重复初始化或未初始化崩溃） */
    private fun ensureNodeRegistryInitialized() {
        try {
            if (NodeRegistry.listModules().isEmpty()) {
                NodeRegistry.init(applicationContext)
            }
        } catch (_: Exception) {
            try {
                NodeRegistry.init(applicationContext)
            } catch (_: Exception) {
                // 忽略重复初始化
            }
        }
    }

    /**
     * 根据 module 字符串推断 [NodeType]（NodeRegistry 未提供此映射，故在此实现）。
     */
    private fun inferNodeType(module: String): NodeType {
        return when {
            module.startsWith("strategy:") -> NodeType.STRATEGY
            module.contains("filter") || module.contains("guard") -> NodeType.FILTER
            module.contains("merge") || module.contains("aggregation") -> NodeType.AGGREGATION
            module.contains("boost") || module.contains("strength") ||
                module.contains("penalty") || module.contains("enhance") -> NodeType.ENRICHMENT
            module.contains("ai") || module.contains("predict") -> NodeType.AI_PREDICTION
            module.contains("order") || module.contains("swap") ||
                module.contains("generate") || module.contains("position") -> NodeType.TRADE_ACTION
            module.contains("context") || module.contains("pool") ||
                module.contains("source") -> NodeType.DATA_SOURCE
            module.contains("score") || module.contains("params") ||
                module.contains("hot") -> NodeType.FACTOR_COMPUTE
            else -> NodeType.DATA_TRANSFORM
        }
    }

    /** 将 module 字符串转为人类可读显示名称 */
    private fun moduleDisplayName(module: String): String {
        return module.removePrefix("strategy:")
            .replace("_", " ")
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * 从 assets XML 文件中快速提取根标签的 name 属性（用于侧边栏中文名显示）。
     */
    private fun extractXmlName(assetPath: String): String? {
        return try {
            val xml = assets.open(assetPath).bufferedReader().use { it.readText() }
            // 匹配 <DagPipeline ... name="xxx" ...> 或 <UseCase ... name="xxx" ...>
            val regex = Regex("""<\w+[^>]*\bname="([^"]+)"""")
            regex.find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** 沉浸式全屏后，给悬浮层补上系统栏安全边距（刘海屏/圆角屏保护） */
    private fun applySystemBarPadding(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
