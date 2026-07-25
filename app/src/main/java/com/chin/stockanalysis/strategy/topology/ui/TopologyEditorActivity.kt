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
 * ## TopologyEditorActivity（重寫版）
 *
 * 基於 [NodeCanvasView] 2D 畫布的 Pipeline 拓撲可視化編輯器。
 *
 * ### 佈局結構
 * ```
 * ┌───────────────────────────────────────────────────┐
 * │ [◀/▶]   UseCase 名稱          [💾 保存] [▶ 執行]   │ ← 頂部欄
 * ├──────────────┬────────────────────────────────────┤
 * │ Collapsible  │                                    │
 * │ Sidebar      │       NodeCanvasView               │
 * │  ▼ Node      │       (2D 畫布，ScrollView 包裹)    │
 * │  ▼ Pipeline  │                                    │
 * │  ▶ UseCase   │                                    │
 * ├──────────────┴────────────────────────────────────┤
 * │ 狀態文字                                            │ ← 底部欄
 * └───────────────────────────────────────────────────┘
 * ```
 *
 * ### 交互
 * - 側邊欄 Node 模板點擊 → 添加節點到畫布
 * - 畫布節點點擊 → 選中（狀態欄顯示信息）
 * - 畫布節點長按 → 彈出 AlertDialog（配置 / 刪除）
 * - 從節點 A 拖拽到節點 B → 建立連線
 * - 連線點擊 → 彈出刪除連線對話框
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

    // 側邊欄各分區展開狀態（默認全部收起）
    private val sectionExpanded = mutableMapOf(
        "node" to false,
        "pipeline" to false,
        "usecase" to false
    )

    // ════════════════════════════════════════════════════
    // 生命週期
    // ════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        // 頂部欄
        rootLayout.addView(buildTopBar())

        // 中間：側邊欄 + 畫布
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

        // 底部：狀態欄
        statusText = TextView(this).apply {
            text = "就緒"
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            maxLines = 3
            setBackgroundColor(Color.WHITE)
        }
        rootLayout.addView(statusText)

        setContentView(rootLayout)

        // 畫布交互回調
        canvasView.listener = object : NodeCanvasView.NodeCanvasListener {
            override fun onNodeSelected(node: VisualNode?) {
                if (node != null) {
                    statusText.text = "選中: ${node.name} (${node.id}) | module: ${node.module}"
                } else {
                    statusText.text = "未選中節點"
                }
            }

            override fun onNodeLongPressed(node: VisualNode) {
                showNodeContextMenu(node)
            }

            override fun onLinkCreated(fromId: String, toId: String) {
                viewModel.addLink(fromId, toId)
                syncCanvasFromViewModel()
                statusText.text = "已連線: $fromId → $toId"
            }

            override fun onLinkTapped(link: VisualLink) {
                // 點擊連線 → 高亮起止節點並平移到目標節點
                val fromNode = canvasView.canvasState.nodes.find { it.id == link.fromId }
                val toNode = canvasView.canvasState.nodes.find { it.id == link.toId }
                if (fromNode != null && toNode != null) {
                    // 選中目標節點
                    canvasView.canvasState.selectedNodeId = link.toId
                    // 平移畫布使目標節點居中
                    canvasView.panToNode(toNode)
                    statusText.text = "連線: ${fromNode.name} → ${toNode.name}"
                    // 長按顯示刪除選項
                    val dialog = AlertDialog.Builder(this@TopologyEditorActivity)
                        .setTitle("${fromNode.name} → ${toNode.name}")
                        .setMessage("from: ${link.fromId}\nto: ${link.toId}")
                        .setPositiveButton("確定", null)
                        .setNegativeButton("🗑️ 刪除連線") { _, _ ->
                            viewModel.removeLink(link.fromId, link.toId)
                            syncCanvasFromViewModel()
                            statusText.text = "已刪除連線"
                        }
                        .show()
                }
            }
        }

        // 觀察 ViewModel 狀態消息
        viewModel.statusMessage.observe(this) { msg ->
            statusText.text = msg
        }

        // 從 Intent 讀取 usecase_id 並加載
        val usecaseId = intent.getStringExtra("usecase_id") ?: "mid_term"
        loadUseCase(usecaseId)
    }

    // ════════════════════════════════════════════════════
    // 頂部欄
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
            text = "Pipeline 編輯器"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(titleText)

        val saveBtn = Button(this).apply {
            text = "💾 保存"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#059669"))
            setOnClickListener { savePipeline() }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(32))
        }
        bar.addView(saveBtn)

        val runBtn = Button(this).apply {
            text = "▶ 執行"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setOnClickListener { runPipeline() }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(32)).apply {
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
    // 側邊欄
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

        // Node 模板分區
        addSidebarSection("node", "📦 Node 模板") {
            val modules = NodeRegistry.listModules().sorted()
            if (modules.isEmpty()) {
                addSidebarItem("(無可用 module，請先初始化)") { }
            }
            for (module in modules) {
                val displayName = moduleDisplayName(module)
                val nodeType = inferNodeType(module)
                addSidebarItem(displayName) {
                    addNodeFromPalette(module, displayName, nodeType)
                }
            }
        }

        // Pipeline 分區
        addSidebarSection("pipeline", "📊 Pipeline") {
            val pipelines = listAssetFiles("usecases")
                ?.filter { it.endsWith("_pipeline.xml") }
                ?: emptyList()
            if (pipelines.isEmpty()) {
                addSidebarItem("(無 Pipeline 文件)") { }
            }
            for (pipeFile in pipelines) {
                val pipeName = extractXmlName("usecases/$pipeFile")
                    ?: pipeFile.removeSuffix(".xml").replace("_", " ")
                addSidebarItem(pipeName) { loadPipelineFile(pipeFile) }
            }
        }

        // UseCase 分區
        addSidebarSection("usecase", "📋 UseCase") {
            val usecases = listAssetFiles("usecases")
                ?.filter { it.endsWith("_usecase.xml") }
                ?: emptyList()
            if (usecases.isEmpty()) {
                addSidebarItem("(無 UseCase 文件)") { }
            }
            for (ucFile in usecases) {
                val ucName = extractXmlName("usecases/$ucFile")
                    ?: ucFile.removeSuffix("_usecase.xml").replace("_", " ")
                addSidebarItem(ucName) { loadUseCaseFile(ucFile) }
            }
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

    /** 切換橫屏/竪屏 */
    private fun toggleOrientation() {
        val current = requestedOrientation
        if (current == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
            current == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // ════════════════════════════════════════════════════
    // 畫布
    // ════════════════════════════════════════════════════

    private fun buildCanvas(): View {
        canvasView = NodeCanvasView(this).apply {
            setBackgroundColor(Color.parseColor("#FAFBFC"))
        }
        // 包裝 ScrollView 支持上下左右滾動
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
    // 節點操作
    // ════════════════════════════════════════════════════

    private fun addNodeFromPalette(module: String, displayName: String, nodeType: NodeType) {
        viewModel.addNode(module)
        syncCanvasFromViewModel()
        statusText.text = "已添加: $displayName"
    }

    private fun showNodeContextMenu(node: VisualNode) {
        val options = arrayOf("📋 配置節點", "🗑️ 刪除節點", "取消")
        AlertDialog.Builder(this)
            .setTitle(node.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showNodeConfigDialog(node)
                    1 -> {
                        viewModel.removeNode(node.id)
                        syncCanvasFromViewModel()
                        statusText.text = "已刪除: ${node.name}"
                    }
                }
            }
            .show()
    }

    private fun showNodeConfigDialog(node: VisualNode) {
        val editableNode = viewModel.getSelectedNode()
            ?: viewModel.getNodes().find { it.id == node.id }
        if (editableNode == null) {
            statusText.text = "找不到節點: ${node.id}"
            return
        }

        val configItems = editableNode.config.entries.toList()
        val sb = StringBuilder()
        sb.appendLine("節點 ID: ${editableNode.id}")
        sb.appendLine("Module: ${editableNode.module}")
        sb.appendLine("Name: ${editableNode.name.ifBlank { editableNode.module }}")
        sb.appendLine()
        sb.appendLine("配置項:")
        if (configItems.isEmpty()) {
            sb.appendLine("  (無)")
        } else {
            for ((k, v) in configItems) {
                sb.appendLine("  $k = $v")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("節點配置")
            .setMessage(sb.toString())
            .setPositiveButton("確定", null)
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
            .setTitle("添加配置項")
            .setView(input)
            .setPositiveButton("確定") { _, _ ->
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
    // 加載 / 保存 / 執行
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
                    // 加載第一個 Pipeline 引用
                    val pipeFile = useCaseConfig.steps.firstOrNull { it is PipelineXmlParser.StepRef.pipeline }
                        ?.let { (it as PipelineXmlParser.StepRef.pipeline).ref }
                    if (pipeFile != null) {
                        loadPipelineFile(pipeFile.substringAfterLast("/"))
                    }
                    statusText.text = "已加載 UseCase: ${useCaseConfig.name}"
                } else {
                    // UseCase 文件不存在，嘗試直接加載同名 Pipeline
                    loadPipelineFile(fileName.removeSuffix("_usecase.xml") + "_pipeline.xml")
                }
            } catch (e: Exception) {
                statusText.text = "加載失敗: ${e.message}"
            }
        }
    }

    private fun loadPipelineFile(fileName: String) {
        lifecycleScope.launch {
            try {
                // 自動檢測 V1 Pipeline / V2 DagPipeline 格式
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
                    statusText.text = "已加載: ${editable.name} (${viewModel.getNodes().size} 節點, ${viewModel.getLinks().size} 連線)"
                } else {
                    statusText.text = "文件不存在或解析失敗: usecases/$fileName"
                }
            } catch (e: Exception) {
                statusText.text = "加載失敗: ${e.message}"
            }
        }
    }

    /**
     * 直接解析 DAG XML 為 EditablePipeline（不依賴 NodeRegistry 實例化）。
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

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "DagPipeline", "dagPipeline", "Pipeline" -> {
                            pipelineId = parser.getAttributeValue(null, "id") ?: ""
                            pipelineName = parser.getAttributeValue(null, "name") ?: ""
                            pipelineDesc = parser.getAttributeValue(null, "description") ?: ""
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
                                    id = id, module = module, name = name, config = config
                                ))
                            }
                        }
                        "Link", "link" -> {
                            // 支持兩種格式：
                            // 屬性格式: <Link source="..." target="..." />
                            // 子標籤格式: <Link><SourceNodeId>...</SourceNodeId><TargetNodeId>...</TargetNodeId></Link>
                            var from = parser.getAttributeValue(null, "source")
                                ?: parser.getAttributeValue(null, "from") ?: ""
                            var to = parser.getAttributeValue(null, "target")
                                ?: parser.getAttributeValue(null, "to") ?: ""

                            // 如果屬性格式沒拿到，嘗試子標籤格式
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
                        name = "DAG 主鏈路",
                        parallel = false,
                        linkLists = listOf(
                            PipelineXmlParser.EditableLinkList(
                                name = "連線",
                                links = links
                            )
                        )
                    )
                )
            )
        } catch (e: Exception) {
            Log.e("TopologyEditor", "DAG XML 解析失敗: ${e.message}", e)
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
            statusText.text = "保存失敗: ${e.message}"
        }
    }

    private fun runPipeline() {
        statusText.text = "🔄 執行 Pipeline..."
        lifecycleScope.launch {
            try {
                val useCaseId = viewModel.pipelineId.ifBlank { "mid_term" }
                val tradeDate = com.chin.stockanalysis.ui.TradingDayPickerView
                    .recentTradingDay().toString()
                val result = withContext(Dispatchers.IO) {
                    UseCaseLoader.run(useCaseId, tradeDate)
                }
                val sb = StringBuilder()
                sb.appendLine(if (result.success) "✅ 執行成功" else "❌ 執行失敗")
                if (result.errors.isNotEmpty()) {
                    sb.appendLine("錯誤: ${result.errors.values.joinToString("; ")}")
                }
                for ((pipeName, pr) in result.pipelineResults) {
                    sb.appendLine("📊 $pipeName:")
                    for ((_, flow) in pr.stockFlowLogs) {
                        sb.appendLine(
                            "  ${flow.nodeName}: ${flow.inputCount}→${flow.outputCount}" +
                                if (flow.filterCount > 0)
                                    " (過濾${flow.filterCount}: ${flow.filterReason})"
                                else ""
                        )
                    }
                }
                statusText.text = sb.toString().trim()
            } catch (e: Exception) {
                statusText.text = "執行失敗: ${e.message}"
            }
        }
    }

    // ════════════════════════════════════════════════════
    // ViewModel ↔ Canvas 同步
    // ════════════════════════════════════════════════════

    private fun syncCanvasFromViewModel() {
        val editableNodes = viewModel.getNodes()
        val links = viewModel.getLinks()

        // 構建 id→中文名 映射
        val nameMap = editableNodes.associate { it.id to it.name.ifBlank { it.module } }

        // EditableNode → VisualNode（保留已有座標，避免重佈局抖動）
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
                config = en.config
            )
        }

        // 連線標籤用中文名，不用英文 ID
        val visualLinks = links.map { l ->
            VisualLink(fromId = l.fromId, toId = l.toId, label = "")
        }

        // 若節點尚無座標（新加載/新增），執行自動佈局
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

    /** 安全初始化 NodeRegistry（避免重複初始化或未初始化崩潰） */
    private fun ensureNodeRegistryInitialized() {
        try {
            if (NodeRegistry.listModules().isEmpty()) {
                NodeRegistry.init(applicationContext)
            }
        } catch (_: Exception) {
            try {
                NodeRegistry.init(applicationContext)
            } catch (_: Exception) {
                // 忽略重複初始化
            }
        }
    }

    /**
     * 根據 module 字符串推斷 [NodeType]（NodeRegistry 未提供此映射，故在此實現）。
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

    /** 將 module 字符串轉為人類可讀顯示名稱 */
    private fun moduleDisplayName(module: String): String {
        return module.removePrefix("strategy:")
            .replace("_", " ")
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * 從 assets XML 文件中快速提取根標籤的 name 屬性（用於側邊欄中文名顯示）。
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
