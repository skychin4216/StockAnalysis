package com.chin.stockanalysis.ui

import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.R
import com.chin.stockanalysis.databinding.FragmentConversationListBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * ## 对话历史列表（豆包 app2 风格）
 *
 * 作为全屏 BottomSheetDialogFragment 展示：
 * - 顶部：⬜ 对话  🔍 ✏️
 * - 列表：彩色头像 + 会话标题 + 摘要
 *
 * 由 [ChatTabFragment] 的 ≡ 按钮触发显示。
 */
class ConversationListFragment : BottomSheetDialogFragment() {

    // 回调：点击"新建对话"或某个历史会话
    var onNewChatClick: (() -> Unit)? = null
    var onSessionClick: ((ConversationSession) -> Unit)? = null

    // 由外部注入的历史会话数据
    var sessions: List<ConversationSession> = emptyList()

    private var _binding: FragmentConversationListBinding? = null
    private val binding get() = _binding!!

    // 可用的柔和背景色（参考豆包头像色系）
    private val avatarColors = listOf(
        "#FFD6E0", "#D6E4FF", "#D6FFE4", "#FFE4D6",
        "#E4D6FF", "#D6F5FF", "#FFFBD6", "#FFD6F5"
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
    }

    private fun setupRecyclerView() {
        val adapter = ConversationAdapter(sessions, avatarColors) { session ->
            dismissAllowingStateLoss()
            onSessionClick?.invoke(session)
        }
        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConversations.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────
    // 数据类
    // ─────────────────────────────

    data class ConversationSession(
        val id: String,
        val title: String,       // 会话标题（取第一条用户消息）
        val subtitle: String,    // 摘要（最后一条 AI 回复的前半句）
        val timestamp: String    // 时间
    )

    // ─────────────────────────────
    // 列表适配器
    // ─────────────────────────────

    inner class ConversationAdapter(
        private val items: List<ConversationSession>,
        private val colors: List<String>,
        private val onClick: (ConversationSession) -> Unit
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
            holder.tvTitle.text = item.title
            holder.tvSubtitle.text = item.subtitle

            // 设置头像背景颜色
            val colorHex = colors[position % colors.size]
            holder.ivAvatarBg.setBackgroundColor(Color.parseColor(colorHex))

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        fun newInstance() = ConversationListFragment()
    }
}
