package com.chin.stockanalysis.strategy.topology.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
 * ## TopologyEditorActivity（重写版）
 *
 * 基于 [NodeCanvasView] 2D 画布的 Pipeline 拓扑可视化编辑器。
 *
 * ### 布局结构
 * ```
 * ┌───────────────────────────────────────────────────┐
 * │ [◀/▶]   UseCase 名称          [💾 保存] [▶ 执行]   │ ← 顶部栏
 * ├──────────────┬────────────────────────────────────┤
 * │ Collapsible  │                                    │
 * │ Sidebar      │       NodeCanvasView               │
 * │  ▼ Node      │       (2D 画布，ScrollView 包裹)    │
 * │  ▼ Pipeline  │                                    │
 * │  ▶ UseCase   │                                    │
 * ├──────────────┴────────────────────────────────────┤
 * │ 状态文字                                            │ ← 底部栏
 * └───────────────────────────────────────────────────┘
 * ```
 *
 * ### 交互
 * - 侧边栏 Node 模板点击 → 添加节点到画布
 * - 画布节点点击 → 选中（状态栏显示信息）
 * - 画布节点长按 → 弹出 AlertDialog（配置 / 删除）
 * - 从节点 A 拖拽到节点 B → 建立连线
 * - 连线点击 → 弹出删除连线对话框
 */
class TopologyEditorActivity : AppCompatActivity() {

    private lateinit var viewModel: TopologyEditorViewModel
    private lateinit var canvasView: NodeCanvasView
    private lateinit var sidebarContainer: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var sidebarToggleBtn: Button

    private var sidebarCollapsed = false
    private val sidebarExpandedWidthDp = 80
    private val sidebarCollapsedWidthDp = 24

    // 侧边栏各分区展开状态（默认全部收起）
    private val sectionExpanded = mutableMapOf(
        "node" to false,
        "pipeline" to false,
        "usecase" to false
    )

    // ════════════════════════════════════════════════════
    // 生命周期
    // ════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 默认横屏显示
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        viewModel = ViewModelProvider(this)[TopologyEditorViewModel::class.java]
        ensureNodeRegistryInitialized()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFBFC"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 顶部栏
        rootLayout.addView(buildTopBar())

        // 中间：侧边栏 + 画布
        val middleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        middleLayout.addView(buildSidebar())
        middleLayout.addView(
            buildCanvas(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        rootLayout.addView(middleLayout)

        // 底部：状态栏
        statusText = TextView(this).apply {
            text = "就绪"
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            maxLines = 3
            setBackgroundColor(Color.WHITE)
        }
        rootLayout.addView(statusText)

        setContentView(rootLayout)

        // 画布交互回调
        canvasView.listener = object : NodeCanvasView.NodeCanvasListener {
            override fun onNodeSelected(node: VisualNode?) {
                if (node != null) {
                    statusText.text = "选中: ${node.name} (${node.id}) | module: ${node.module}"
                } else {
                    statusText.text = "未选中节点"
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
                // 点击连线 → 高亮起止节点并平移到目标节点
                val fromNode = canvasView.canvasState.nodes.find { it.id == link.fromId }
                val toNode = canvasView.canvasState.nodes.find { it.id == link.toId }
                if (fromNode != null && toNode != null) {
                    // 选中目标节点
                    canvasView.canvasState.selectedNodeId = link.toId
                    // 平移画布使目标节点居中
                    canvasView.panToNode(toNode)
                    statusText.text = "连线: ${fromNode.name} → ${toNode.name}"
                    // 长按显示删除选项
                    val dialog = AlertDialog.Builder(this@TopologyEditorActivity)
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
    // 顶部栏
    // ════════════════════════════════════════════════════

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        sidebarToggleBtn = Button(this).apply {
            text = "◀"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { toggleSidebar() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(32))
        }
        bar.addView(sidebarToggleBtn)

        titleText = TextView(this).apply {
            text = "Pipeline 编辑器"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(titleText)

        val saveBtn = Button(this).apply {
            text = "💾保存"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#059669"))
            setOnClickListener { savePipeline() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                minWidth = dp(60)
            }
        }
        bar.addView(saveBtn)

        val runBtn = Button(this).apply {
            text = "▶执行"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setOnClickListener { runPipeline() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                minWidth = dp(60)
                marginStart = dp(4)
            }
        }
        bar.addView(runBtn)

        val orientationBtn = Button(this).apply {
            text = "🔄"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { toggleOrientation() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(32)).apply {
                marginStart = dp(4)
            }
        }
        bar.addView(orientationBtn)

        return bar
    }

    // ════════════════════════════════════════════════════
    // 侧边栏
    // ════════════════════════════════════════════════════

    private fun buildSidebar(): View {
        sidebarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            layoutParams = LinearLayout.LayoutParams(
                dp(sidebarExpandedWidthDp), ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        refreshSidebarContent()
        return sidebarContainer
    }

    private fun refreshSidebarContent() {
        sidebarContainer.removeAllViews()

        // Node 模板分区
        addSidebarSection("node", "📦 Node 模板") {
            val modules = NodeRegistry.listModules().sorted()
            if (modules.isEmpty()) {
                addSidebarItem("(无可用 module，请先初始化)") { }
            }
            for (module in modules) {
                val displayName = moduleDisplayName(module)
                val nodeType = inferNodeType(module)
                addSidebarItem(displayName) {
                    addNodeFromPalette(module, displayName, nodeType)
                }
            }
        }

        // Pipeline 分区
        addSidebarSection("pipeline", "📊 Pipeline") {
            val pipelines = listAssetFiles("usecases")
                ?.filter { it.endsWith("_pipeline.xml") }
                ?: emptyList()
            if (pipelines.isEmpty()) {
                addSidebarItem("(无 Pipeline 文件)") { }
            }
            for (pipeFile in pipelines) {
                val pipeName = extractXmlName("usecases/$pipeFile")
                    ?: pipeFile.removeSuffix(".xml").replace("_", " ")
                addSidebarItem(pipeName) { loadPipelineFile(pipeFile) }
            }
        }

        // UseCase 分区
        addSidebarSection("usecase", "📋 UseCase") {
            val usecases = listAssetFiles("usecases")
                ?.filter { it.endsWith("_usecase.xml") }
                ?: emptyList()
            if (usecases.isEmpty()) {
                addSidebarItem("(无 UseCase 文件)") { }
            }
            for (ucFile in usecases) {
                val ucName = extractXmlName("usecases/$ucFile")
                    ?: ucFile.removeSuffix("_usecase.xml").replace("_", " ")
                addSidebarItem(ucName) { loadUseCaseFile(ucFile) }
            }
            // Pipeline 全景图入口
            addSidebarItem("🌐 Pipeline 全景") { showPipelinePanorama() }
        }
    }

    private fun addSidebarItem(label: String, onClick: () -> Unit) {
        val itemBtn = TextView(this).apply {
            text = label
            textSize = 10f
            setTextColor(Color.parseColor("#334155"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.WHITE)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(2), dp(1), dp(2), 0) }
        }
        sidebarContainer.addView(itemBtn)
    }

    private fun addSidebarSection(key: String, title: String, contentBuilder: () -> Unit) {
        val isExpanded = sectionExpanded[key] ?: true

        val header = Button(this).apply {
            text = if (isExpanded) "▼ $title" else "▶ $title"
            textSize = 12f
            setTextColor(Color.parseColor("#1E293B"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            isAllCaps = false
            setOnClickListener {
                sectionExpanded[key] = !(sectionExpanded[key] ?: true)
                refreshSidebarContent()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
            ).apply { setMargins(0, dp(4), 0, 0) }
        }
        sidebarContainer.addView(header)

        if (isExpanded) {
            contentBuilder()
        }
    }

    private fun toggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed
        val width = if (sidebarCollapsed) dp(sidebarCollapsedWidthDp) else dp(sidebarExpandedWidthDp)
        sidebarContainer.layoutParams =
            LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        sidebarToggleBtn.text = if (sidebarCollapsed) "▶" else "◀"
        if (sidebarCollapsed) {
            sidebarContainer.removeAllViews()
        } else {
            refreshSidebarContent()
        }
    }

    /** 切换横屏/竖屏 */
    private fun toggleOrientation() {
        val current = requestedOrientation
        if (current == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
            current == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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

        // 标题栏
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

        // WebView
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
    // 画布
    // ════════════════════════════════════════════════════

    private fun buildCanvas(): View {
        canvasView = NodeCanvasView(this).apply {
            setBackgroundColor(Color.parseColor("#FAFBFC"))
        }
        // 包装 ScrollView 支持上下左右滚动
        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(canvasView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val vScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(hScroll, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        return vScroll
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
        val editableNode = viewModel.getSelectedNode()
            ?: viewModel.getNodes().find { it.id == node.id }
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

    private fun loadUseCase(usecaseId: String) {
        val usecaseFile = when (usecaseId) {
            "mid_term", "midterm" -> "mid_term_usecase.xml"
            "short_term", "shortterm" -> "short_term_usecase.xml"
            "screening" -> "screening_usecase.xml"
            else -> "${usecaseId}_usecase.xml"
        }
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
                    titleText.text =
                        useCaseConfig.name.ifBlank { fileName.removeSuffix("_usecase.xml") }
                    // 加载第一个 Pipeline 引用
                    val pipeFile = useCaseConfig.steps.firstOrNull { it is PipelineXmlParser.StepRef.pipeline }
                        ?.let { (it as PipelineXmlParser.StepRef.pipeline).ref }
                    if (pipeFile != null) {
                        loadPipelineFile(pipeFile.substringAfterLast("/"))
                    }
                    statusText.text = "已加载 UseCase: ${useCaseConfig.name}"
                } else {
                    // UseCase 文件不存在，尝试直接加载同名 Pipeline
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
                    syncCanvasFromViewModel()
                    canvasView.zoomToFit()
                    statusText.text = "已加载: ${editable.name} (${viewModel.getNodes().size} 节点, ${viewModel.getLinks().size} 连线)"
                } else {
                    statusText.text = "文件不存在或解析失败: usecases/$fileName"
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
                val useCaseId = viewModel.pipelineId.ifBlank { "mid_term" }
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

        // 构建 id→中文名 映射
        val nameMap = editableNodes.associate { it.id to it.name.ifBlank { it.module } }

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

        // 连线标签用中文名，不用英文 ID
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

    // ════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
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
}
