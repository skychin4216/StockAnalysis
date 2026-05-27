package com.chin.stockanalysis.ui

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.R
import com.chin.stockanalysis.conversation.ConversationEntity
import com.chin.stockanalysis.conversation.ConversationRepository
import com.chin.stockanalysis.databinding.FragmentConversationListBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ## 对话历史列表（豆包 app2 风格）
 *
 * 作为全屏 BottomSheetDialogFragment 展示：
 * - 顶部：⬜ 对话  🔍 ✏️
 * - 列表：彩色头像 + 会话标题 + 摘要
 * - 数据来源：Room 数据库（通过 [convRepo] 注入）
 *
 * 由 [ChatTabFragment] 的 ≡ 按钮触发显示。
 */
class ConversationListFragment : BottomSheetDialogFragment() {

    /** 回调：点击"新建对话" */
    var onNewChatClick: (() -> Unit)? = null

    /** 回调：点击某个历史会话，参数为会话 ID */
    var onSessionClick: ((String) -> Unit)? = null

    /** 回调：删除某个会话，参数为会话 ID */
    var onDeleteSession: ((String) -> Unit)? = null

    /** 由 ChatTabFragment 注入的对话仓库 */
    var convRepo: ConversationRepository? = null

    private var _binding: FragmentConversationListBinding? = null
    private val binding get() = _binding!!

    /** 当前加载的对话列表 */
    private val convList: MutableList<ConversationEntity> = mutableListOf()
    private var adapter: ConversationAdapter? = null

    // 参考豆包：三种头像颜色交替
    private val avatarColors = listOf(
        "#4A90D9", "#E8734A", "#50B86C"
    )
    // 头像文字颜色
    private val avatarTextColors = listOf(
        "#FFFFFF", "#FFFFFF", "#FFFFFF"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 全屏展开
        val bottomSheet = dialog?.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            it.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        }

        // 新建对话
        binding.btnNewChat.setOnClickListener {
            dismissAllowingStateLoss()
            onNewChatClick?.invoke()
        }

        // 搜索（预留）
        binding.btnSearch.setOnClickListener {
            Toast.makeText(requireContext(), "搜索功能开发中", Toast.LENGTH_SHORT).show()
        }

        // 左上展开按钮（关闭）
        binding.btnExpand.setOnClickListener {
            dismissAllowingStateLoss()
        }

        // 设置列表
        setupRecyclerView()
        // 从 DB 加载数据
        loadConversations()
    }

    private fun setupRecyclerView() {
        adapter = ConversationAdapter(convList, avatarColors,
            onItemClick = { conv ->
                dismissAllowingStateLoss()
                onSessionClick?.invoke(conv.id)
            },
            onItemLongClick = { conv ->
                // 长按删除
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("删除对话")
                    .setMessage("确定要删除「${conv.title}」吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        val repo = convRepo
                        if (repo != null) {
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) { repo.deleteConversation(conv.id) }
                                onDeleteSession?.invoke(conv.id)
                                loadConversations()
                            }
                        } else {
                            onDeleteSession?.invoke(conv.id)
                            convList.remove(conv)
                            adapter?.notifyDataSetChanged()
                        }
                    }
                    .show()
            }
        )
        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConversations.adapter = adapter
    }

    private fun loadConversations() {
        val repo = convRepo
        if (repo != null) {
            lifecycleScope.launch {
                val list = withContext(Dispatchers.IO) { repo.getAllConvs() }
                convList.clear()
                convList.addAll(list)
                adapter?.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────
    // 列表适配器
    // ─────────────────────────────

    inner class ConversationAdapter(
        private val items: List<ConversationEntity>,
        private val colors: List<String>,
        private val onItemClick: (ConversationEntity) -> Unit,
        private val onItemLongClick: (ConversationEntity) -> Unit
    ) : RecyclerView.Adapter<ConversationAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivAvatarBg: ImageView = itemView.findViewById(R.id.ivAvatarBg)
            val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            // 标题格式：【时间】标题
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            holder.tvTitle.text = "[${sdf.format(java.util.Date(item.timestamp))}] ${item.title}"
            holder.tvSubtitle.text = item.subtitle

            // 设置头像背景颜色
            val colorHex = colors[position % colors.size]
            holder.ivAvatarBg.setBackgroundColor(Color.parseColor(colorHex))

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}