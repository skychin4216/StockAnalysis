package com.chin.stockanalysis.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(
    private val messages: MutableList<Message>
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
        private const val VIEW_TYPE_STREAMING = 3
        private const val VIEW_TYPE_ERROR = 4
    }

    var onCopyMessage: ((String) -> Unit)? = null
    var onEditMessage: ((Int, String) -> Unit)? = null
    var onDeleteMessage: ((Int) -> Unit)? = null
    var onUndoMessage: ((Int) -> Unit)? = null
    var onPlayVoice: ((String) -> Unit)? = null
    var onFavorite: ((String) -> Unit)? = null
    var onShare: ((String) -> Unit)? = null
    var onRegenerate: ((Int) -> Unit)? = null

    inner class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message, position: Int) {
            binding.tvTime.text = formatTime(message.timestamp)
            binding.tvTime.visibility = View.VISIBLE

            when {
                message.isError -> bindError(message)
                message.isStreaming -> bindStreaming(message)
                message.isUser -> bindUser(message, position)
                else -> bindAi(message, position)
            }
        }

        private fun bindUser(message: Message, position: Int) {
            binding.apply {
                layoutBot.visibility = View.GONE
                layoutUser.visibility = View.VISIBLE
                tvUserMessage.text = message.content

                tvUserMessage.setOnLongClickListener {
                    showPopupMenu(it, position, message)
                    true
                }
                btnCopyUser.setOnClickListener { onCopyMessage?.invoke(message.content) }
                btnEditUser.setOnClickListener { onEditMessage?.invoke(position, message.content) }
                btnDeleteUser.setOnClickListener { onDeleteMessage?.invoke(position) }
            }
        }

        private fun bindAi(message: Message, position: Int) {
            binding.apply {
                layoutUser.visibility = View.GONE
                layoutBot.visibility = View.VISIBLE
                tvBotMessage.text = message.content
                tvBotMessage.visibility = View.VISIBLE
                tvTypingIndicator.visibility = View.GONE
                tvLoadingStatus.visibility = View.GONE
                tvErrorHint.visibility = View.GONE

                // v9.0: 股票表格检测
                renderStockTable(message.content)

                tvBotMessage.setOnClickListener {
                    val visible = layoutBotActions.visibility == View.VISIBLE
                    layoutBotActions.visibility = if (visible) View.GONE else View.VISIBLE
                }
                tvBotMessage.setOnLongClickListener {
                    showPopupMenu(it, position, message)
                    true
                }

                btnCopyBot.setOnClickListener { onCopyMessage?.invoke(message.content) }
                btnPlayVoice.setOnClickListener { onPlayVoice?.invoke(message.content) }
                btnFavorite.setOnClickListener { onFavorite?.invoke(message.content) }
                btnShare.setOnClickListener { onShare?.invoke(message.content) }
                btnRegenerate.setOnClickListener { onRegenerate?.invoke(position) }
            }
        }

        private fun bindStreaming(message: Message) {
            binding.apply {
                layoutBot.visibility = View.VISIBLE
                layoutUser.visibility = View.GONE
                tvBotMessage.text = message.content

                // v9.0: 加载状态文字优先于 "..."
                if (message.loadingStatus != null) {
                    tvLoadingStatus.text = message.loadingStatus
                    tvLoadingStatus.visibility = View.VISIBLE
                    tvTypingIndicator.visibility = View.GONE
                } else {
                    tvLoadingStatus.visibility = View.GONE
                    tvTypingIndicator.visibility = if (message.content.isEmpty()) View.VISIBLE else View.GONE
                }
                layoutBotActions.visibility = View.GONE
                layoutStockTable.visibility = View.GONE
                tvErrorHint.visibility = View.GONE
            }
        }

        private fun bindError(message: Message) {
            binding.apply {
                layoutBot.visibility = View.VISIBLE
                layoutUser.visibility = View.GONE
                tvErrorHint.text = message.errorMessage ?: message.content
                tvErrorHint.visibility = View.VISIBLE
                tvBotMessage.visibility = View.GONE
                tvTypingIndicator.visibility = View.GONE
                tvLoadingStatus.visibility = View.GONE
                layoutBotActions.visibility = View.GONE
                layoutStockTable.visibility = View.GONE
            }
        }

        /** v9.0: 检测并渲染股票 CSV 表格 */
        private fun renderStockTable(content: String) {
            // 检测是否包含表格标记 (| 分隔的表格行)
            val lines = content.lines()
            val tableStart = lines.indexOfFirst { it.trimStart().startsWith("|") && it.contains("---") }
            if (tableStart < 0) {
                binding.layoutStockTable.visibility = View.GONE
                return
            }

            // 收集表格内容
            val tableLines = mutableListOf<String>()
            for (i in tableStart until lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("|") && line.endsWith("|")) {
                    tableLines.add(line)
                } else if (tableLines.isNotEmpty()) break
            }
            if (tableLines.size < 3) {
                binding.layoutStockTable.visibility = View.GONE
                return
            }

            // 用 monospace 字体渲染对齐的表格
            val sb = StringBuilder()
            val headerLine = tableLines[1] // --- 分隔行之后的第一个数据行从 index 2 开始
            // 实际上：header, ---, data1, data2...
            // 取表头行 + 数据行，跳过 ---

            for (i in tableLines.indices) {
                if (i == 1) continue // 跳过 --- 分隔行
                val cells = tableLines[i].split("|").filter { it.isNotBlank() }.map { it.trim() }
                if (cells.isEmpty()) continue
                sb.appendLine(cells.joinToString(" │ "))
                if (i == 0) sb.appendLine("─".repeat(sb.length - 1))
            }

            if (sb.isNotEmpty()) {
                binding.tvStockTable.text = sb.toString().trimEnd()
                binding.layoutStockTable.visibility = View.VISIBLE
            } else {
                binding.layoutStockTable.visibility = View.GONE
            }
        }

        private fun showPopupMenu(anchor: View, position: Int, message: Message) {
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add("选取文字")
            popup.menu.add("复制")
            if (message.isUser) popup.menu.add("编辑")
            if (message.isUser) popup.menu.add("撤销")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "选取文字" -> {
                        val tv = if (message.isUser) binding.tvUserMessage else binding.tvBotMessage
                        tv.setTextIsSelectable(true)
                        android.widget.Toast.makeText(anchor.context, "已启用文本选择，长按文字即可选取", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    "复制" -> onCopyMessage?.invoke(message.content)
                    "编辑" -> onEditMessage?.invoke(position, message.content)
                    "撤销" -> onUndoMessage?.invoke(position)
                }
                true
            }
            popup.show()
        }

        private fun formatTime(timestamp: Long): String {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }

    override fun getItemViewType(position: Int): Int {
        val msg = messages[position]
        return when {
            msg.isError -> VIEW_TYPE_ERROR
            msg.isStreaming -> VIEW_TYPE_STREAMING
            msg.isUser -> VIEW_TYPE_USER
            else -> VIEW_TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position], position)
    }

    override fun getItemCount() = messages.size
}