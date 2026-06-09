package com.chin.stockanalysis.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.chin.stockanalysis.agent.Agent
import com.chin.stockanalysis.agent.AgentManager
import com.chin.stockanalysis.databinding.FragmentAgentTabBinding

class AgentTabFragment : Fragment() {

    private var _binding: FragmentAgentTabBinding? = null
    private val binding get() = _binding!!

    private lateinit var agentManager: AgentManager
    private lateinit var adapter: AgentListAdapter
    private var allAgents: List<Agent> = emptyList()
    private var searchQuery: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAgentTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        agentManager = AgentManager(requireContext())
        setupRecyclerView()
        setupSearch()
        setupAddButton()
    }

    override fun onResume() { super.onResume(); refreshList() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private fun setupRecyclerView() {
        adapter = AgentListAdapter(
            onAgentClick = { openAgentChat(it) },
            onAgentMoreClick = { agent, anchor -> showAgentPopupMenu(agent, anchor) }
        )
        binding.rvAgentList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAgentList.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearchAgent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilter()
            }
        })
    }

    private fun setupAddButton() { binding.btnAddAgent.setOnClickListener { showCreateDialog() } }

    private fun refreshList() {
        allAgents = agentManager.getAll().sortedByDescending { it.usageCount }
        applyFilter()
        if (allAgents.isEmpty()) Toast.makeText(requireContext(), "暂无智能体，点击创建", Toast.LENGTH_SHORT).show()
    }

    private fun applyFilter() {
        val filtered = if (searchQuery.isBlank()) allAgents else {
            val q = searchQuery.lowercase()
            allAgents.filter { a -> a.name.lowercase().contains(q) || a.description.lowercase().contains(q) || a.triggerKeywords.any { it.lowercase().contains(q) } }
        }
        adapter.submitList(filtered)
    }

    // ── 打开/关闭智能体对话 ──

    private fun openAgentChat(agent: Agent) {
        Log.d("AgentEdit", "========== openAgentChat ==========")
        Log.d("AgentEdit", "agent.quickPrompt= ${agent.quickPrompt.take(80)}")
        Log.d("AgentEdit", "agent.systemPrompt = ${agent.systemPrompt.take(80)}")
        Log.d("AgentEdit", "====================================")
        agentManager.recordUsage(agent.id)
        val f = AgentChatFragment.newInstance(agent)
        f.onBackClick = { closeAgentChat() }
        f.onSettingsClick = { showEditDialog(it) }
        parentFragmentManager.beginTransaction()
            .replace(com.chin.stockanalysis.R.id.layoutAgentChat, f, "agent_chat").commit()
        binding.layoutAgentList.visibility = View.GONE
        binding.layoutAgentChat.visibility = View.VISIBLE
    }

    private fun closeAgentChat() {
        parentFragmentManager.findFragmentByTag("agent_chat")?.let { parentFragmentManager.beginTransaction().remove(it).commit() }
        binding.layoutAgentChat.visibility = View.GONE
        binding.layoutAgentList.visibility = View.VISIBLE
        refreshList()
    }

    // ── ⋮ PopupMenu ──

    private fun showAgentPopupMenu(agent: Agent, anchorView: View?) {
        val v = anchorView ?: binding.rvAgentList
        val popup = PopupMenu(requireContext(), v)
        popup.menu.add(0, 0, 0, "编辑智能体")
        popup.menu.add(0, 1, 1, "查看详情")
        popup.menu.add(0, 2, 2, if (agent.enabled) "禁用" else "启用")
        popup.menu.add(0, 3, 3, "删除")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> { showEditDialog(agent); true }
                1 -> { showAgentDetail(agent); true }
                2 -> { agentManager.setEnabled(agent.id, !agent.enabled); Toast.makeText(requireContext(), if (!agent.enabled) "✅ 已启用" else "⏸️ 已禁用", Toast.LENGTH_SHORT).show(); refreshList(); true }
                3 -> {
                    AlertDialog.Builder(requireContext()).setTitle("确认删除").setMessage("确定删除智能体「${agent.name}」吗？")
                        .setPositiveButton("删除") { _, _ -> agentManager.remove(agent.id); Toast.makeText(requireContext(), "🗑️ 已删除", Toast.LENGTH_SHORT).show(); refreshList() }
                        .setNegativeButton("取消", null).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ── 创建智能体 ──

    private fun showCreateDialog() {
        val scroll = ScrollView(requireContext())
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 8) }
        scroll.addView(layout)

        val etName = EditText(requireContext()).apply { hint = "名称"; setSingleLine(); textSize = 13f }
        val etDesc = createScrollingEdit(requireContext(), hint = "設定描述（描述智能体的角色和功能）", minLines = 5, maxLines = 10, textSize = 13f)
        val etPrompt = createScrollingEdit(requireContext(), hint = "如: 财报 300XXX\n空报 300XXX\n里程碑 300XXX", minLines = 3, maxLines = 5, textSize = 12f).apply {
            setText("BOM 赛道\n选股 赛道\n财报 股票代码\n空报 股票代码\n里程碑 股票代码\n全流程 赛道/个股")
        }
        val etAutoCmd = createScrollingEdit(requireContext(), hint = "AI根据設定描述自动生成的全自动执行规则", minLines = 5, maxLines = 10, textSize = 12f)

        val heading = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6; topMargin = 12 }
        val p = lparams(6)

        layout.addView(lbl("名称"), heading)
        layout.addView(etName, p)
        layout.addView(lbl("設定描述（description）"), heading)
        layout.addView(etDesc, p)

        val btnGen = btnGen()
        layout.addView(btnGen, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = 8; topMargin = 8; gravity = android.view.Gravity.CENTER_HORIZONTAL })

        layout.addView(lbl("輸入指令描述（quickPrompt）"), heading)
        layout.addView(etPrompt, p)
        layout.addView(lbl("全自动执行规则描述（systemPrompt — AI生成）"), heading)
        layout.addView(etAutoCmd, p)

        btnGen.setOnClickListener {
            val desc = etDesc.text.toString().trim()
            if (desc.isBlank()) { toast("请先填写設定描述"); return@setOnClickListener }
            btnGen.text = "⏳ 生成中..."; btnGen.isEnabled = false
            lifecycleScope.launch {
                try { val ac = genAutoCommand(desc); ui { etAutoCmd.setText(ac); btnGen.text = "✅ 已生成"; btnGen.isEnabled = true } }
                catch (e: Exception) { ui { btnGen.text = "🤖 AI生成全自动规则"; btnGen.isEnabled = true; toast("生成失败: ${e.message}") } }
            }
        }

        AlertDialog.Builder(requireContext()).setTitle("🤖 创建智能体").setView(scroll)
            .setPositiveButton("创建") { _, _ ->
                val n = etName.text.toString().trim(); val pr = etPrompt.text.toString().trim()
                val d = etDesc.text.toString().trim(); val ac = etAutoCmd.text.toString().trim()
                if (n.isBlank()) { toast("名称不能为空"); return@setPositiveButton }
                val a = agentManager.create(name = n, description = d.ifBlank { n }, quickPrompt = pr, systemPrompt = ac)
                if (a != null) { toast("✅ 智能体「${a.name}」已创建"); refreshList(); openAgentChat(a) }
                else toast("⚠️ 创建失败")
            }.setNegativeButton("取消", null).show()
    }

    // ── 编辑智能体 ──

    fun showEditDialog(agent: Agent) {
        Log.d("AgentEdit", "========== showEditDialog ==========")
        Log.d("AgentEdit", "agent.id         = ${agent.id}")
        Log.d("AgentEdit", "agent.name       = ${agent.name}")
        Log.d("AgentEdit", "agent.description= ${agent.description}")
        Log.d("AgentEdit", "agent.quickPrompt= ${agent.quickPrompt}")
        Log.d("AgentEdit", "agent.systemPrompt = ${agent.systemPrompt}")
        Log.d("AgentEdit", "====================================")

        val scroll = ScrollView(requireContext())
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 8) }
        scroll.addView(layout)

        val etName = EditText(requireContext()).apply { setText(agent.name); hint = "名称"; setSingleLine(); textSize = 13f }
        val etDesc = createScrollingEdit(requireContext(), hint = "設定描述（描述智能体的角色和功能）", minLines = 5, maxLines = 10, textSize = 13f).apply {
            setText(cleanDesc(agent.description))
        }
        val etPrompt = createScrollingEdit(requireContext(), hint = "如: 财报 300XXX\n空报 300XXX\n里程碑 300XXX", minLines = 3, maxLines = 5, textSize = 12f).apply {
            setText(agent.quickPrompt)
        }
        val etAutoCmd = createScrollingEdit(requireContext(), hint = "AI根据設定描述自动生成的全自动执行规则", minLines = 5, maxLines = 10, textSize = 12f).apply {
            setText(agent.systemPrompt)
        }

        val heading = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6; topMargin = 12 }
        val p = lparams(6)

        layout.addView(lbl("名称"), heading)
        layout.addView(etName, p)
        layout.addView(lbl("設定描述（description）"), heading)
        layout.addView(etDesc, p)

        val btnGen = btnGen()
        layout.addView(btnGen, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = 8; topMargin = 8; gravity = android.view.Gravity.CENTER_HORIZONTAL })

        layout.addView(lbl("輸入指令描述（quickPrompt）"), heading)
        layout.addView(etPrompt, p)
        layout.addView(lbl("全自动执行规则描述（systemPrompt — AI生成）"), heading)
        layout.addView(etAutoCmd, p)

        btnGen.setOnClickListener {
            val desc = etDesc.text.toString().trim()
            if (desc.isBlank()) { toast("请先填写設定描述"); return@setOnClickListener }
            btnGen.text = "⏳ 生成中..."; btnGen.isEnabled = false
            lifecycleScope.launch {
                try { val ac = genAutoCommand(desc); ui { etAutoCmd.setText(ac); btnGen.text = "✅ 已生成"; btnGen.isEnabled = true } }
                catch (e: Exception) { ui { btnGen.text = "🤖 AI生成全自动规则"; btnGen.isEnabled = true; toast("生成失败: ${e.message}") } }
            }
        }

        AlertDialog.Builder(requireContext()).setTitle("✏️ 编辑智能体设定").setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val n = etName.text.toString().trim(); val pr = etPrompt.text.toString().trim()
                val d = etDesc.text.toString().trim(); val ac = etAutoCmd.text.toString().trim()
                if (n.isBlank()) { toast("名称不能为空"); return@setPositiveButton }
                agentManager.update(agent.id) { it.copy(name = n, quickPrompt = pr, description = d.ifBlank { n }, systemPrompt = ac) }
                toast("✅ 已更新"); refreshList()
            }.setNegativeButton("取消", null).show()
    }

    // ── 查看详情 ──

    private fun showAgentDetail(agent: Agent) {
        val info = buildString {
            appendLine("${agent.icon} ${agent.name}\n")
            appendLine("📝 設定描述（description）:"); appendLine(agent.description); appendLine()
            appendLine("📋 輸入指令描述（quickPrompt）:"); appendLine(agent.quickPrompt); appendLine()
            if (agent.systemPrompt.isNotBlank()) { appendLine("⚡ 全自动执行规则描述（systemPrompt）:"); appendLine(agent.systemPrompt); appendLine() }
            appendLine("🏷️ ${agent.triggerKeywords.joinToString(", ").ifBlank { "无" }}")
            appendLine("📊 ${agent.usageCount}次 | 🕐 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(agent.createdAt))}")
        }
        AlertDialog.Builder(requireContext()).setTitle("智能体详情").setMessage(info).setPositiveButton("确定", null).show()
    }

    // ── 工具方法 ──

    /** 清理 description 中多余的空行，合并连续空行为单换行 */
    private fun cleanDesc(text: String): String {
        return text
            .replace(Regex(" *\\n *\\n[\\n ]*"), "\n")  // 连续空行 → 单换行
            .trim()
    }

    /** 创建支持上下+左右滑动、自动换行的多行文本输入框 */
    private fun createScrollingEdit(
        context: android.content.Context,
        hint: String,
        minLines: Int,
        maxLines: Int,
        textSize: Float
    ): EditText {
        return EditText(context).apply {
            this.hint = hint
            this.minLines = minLines
            this.maxLines = maxLines
            this.textSize = textSize
            // 文本方向：左上对齐
            gravity = android.view.Gravity.START or android.view.Gravity.TOP
            // 输入类型：多行文本（支持自动换行）
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // 水平滚动：关闭 → 自动换行（长行自动折行）
            setHorizontallyScrolling(false)
            // 垂直滚动条：显示
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            // 重写触摸事件：解决嵌套滚动冲突（EditText 位于 ScrollView 内）
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    // 让 EditText 自己处理触摸，不被外层 ScrollView 拦截
                    parent.requestDisallowInterceptTouchEvent(true)
                } else if (event.action == android.view.MotionEvent.ACTION_UP ||
                    event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }

    private fun lbl(t: String) = TextView(requireContext()).apply { text = t; textSize = 11f; setTextColor(0xFF666666.toInt()) }
    private fun lparams(m: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = m }
    private fun btnGen() = TextView(requireContext()).apply {
        text = "🤖 AI生成指令"; setTextColor(0xFF4A90D9.toInt()); textSize = 14f
        setBackgroundResource(android.R.drawable.btn_default); setPadding(16, 8, 16, 8)
        gravity = android.view.Gravity.CENTER; isClickable = true
    }
    private fun toast(msg: String) { if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
    private fun ui(block: () -> Unit) { if (isAdded) requireActivity().runOnUiThread(block) }
    private suspend fun genAutoCommand(description: String): String {
        val provider = com.chin.stockanalysis.ApiConfigManager.getInstance(requireContext()).createCurrentProvider()
            ?: throw Exception("AI服务未初始化")
        val sysPrompt = "你是提示词优化工程师。根据用户提供的选股方法描述，生成一个结构化的「全自动指令源码」。\n" +
            "要求：\n1. 使用【关键词】→ 执行规则 的触发格式\n" +
            "2. 包含清晰的执行步骤和输出规则\n" +
            "3. 200-500字，量化、数据化\n" +
            "4. 不要添加问候语或解释\n\n" +
            "用户描述：$description"
        var result = ""
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            provider.sendMessageStream(
                messages = listOf(com.chin.stockanalysis.ui.Message(content = description, isUser = true)),
                systemPrompt = sysPrompt,
                onSuccess = { result = it },
                onComplete = { result = it.ifEmpty { result }; cont.resumeWith(Result.success(Unit)) },
                onError = { result = ""; cont.resumeWith(Result.success(Unit)) }
            )
        }
        if (result.isBlank()) throw Exception("AI生成结果为空")
        return result
    }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}