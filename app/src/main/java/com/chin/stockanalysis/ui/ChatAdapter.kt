package com.chin.stockanalysis.ui

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
    // 豆包风格新增操作
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

                btnCopyUser.setOnClickListener {
                    onCopyMessage?.invoke(message.content)
                }
                btnEditUser.setOnClickListener {
                    onEditMessage?.invoke(position, message.content)
                }
                btnDeleteUser.setOnClickListener {
                    onDeleteMessage?.invoke(position)
                }
            }
        }

        private fun bindAi(message: Message, position: Int) {
            binding.apply {
                layoutUser.visibility = View.GONE
                layoutBot.visibility = View.VISIBLE
                tvBotMessage.text = message.content
                tvBotMessage.visibility = View.VISIBLE
                tvTypingIndicator.visibility = View.GONE
                tvErrorHint.visibility = View.GONE

                // 点击消息 → 显示/隐藏操作栏
                tvBotMessage.setOnClickListener {
                    val visible = layoutBotActions.visibility == View.VISIBLE
                    layoutBotActions.visibility = if (visible) View.GONE else View.VISIBLE
                }
                tvBotMessage.setOnLongClickListener {
                    showPopupMenu(it, position, message)
                    true
                }

                // 复制
                btnCopyBot.setOnClickListener { onCopyMessage?.invoke(message.content) }
                // 🔊 播放
                btnPlayVoice.setOnClickListener { onPlayVoice?.invoke(message.content) }
                // ⭐ 收藏
                btnFavorite.setOnClickListener { onFavorite?.invoke(message.content) }
                // 转发
                btnShare.setOnClickListener { onShare?.invoke(message.content) }
                // 🔄 重新生成
                btnRegenerate.setOnClickListener { onRegenerate?.invoke(position) }
            }
        }

        private fun bindStreaming(message: Message) {
            binding.apply {
                layoutBot.visibility = View.VISIBLE
                layoutUser.visibility = View.GONE
                tvBotMessage.text = message.content
                tvTypingIndicator.visibility = View.VISIBLE
                layoutBotActions.visibility = View.GONE  // 流式输出中不显示操作栏
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
                layoutBotActions.visibility = View.GONE
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
                        // 找到当前消息的 TextView 并启用文本选择
                        val tv = if (message.isUser) binding.tvUserMessage else binding.tvBotMessage
                        tv.setTextIsSelectable(true)
                        // 通知用户
                        android.widget.Toast.makeText(
                            anchor.context,
                            "已启用文本选择，长按文字即可选取",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
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
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position], position)
    }

    override fun getItemCount() = messages.size
}