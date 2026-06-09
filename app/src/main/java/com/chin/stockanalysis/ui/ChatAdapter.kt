package com.chin.stockanalysis.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.databinding.ItemMessageBinding

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

                tvUserMessage.setOnClickListener {
                    val visible = layoutUserActions.visibility == View.VISIBLE
                    layoutUserActions.visibility = if (visible) View.GONE else View.VISIBLE
                }

                btnCopyUser.setOnClickListener { onCopyMessage?.invoke(message.content) }
                btnPlayVoiceUser.setOnClickListener { onPlayVoice?.invoke(message.content) }
                btnFavoriteUser.setOnClickListener { onFavorite?.invoke(message.content) }
                btnShareUser.setOnClickListener { onShare?.invoke(message.content) }
                btnRegenerateUser.setOnClickListener { onRegenerate?.invoke(position) }
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

                renderStockTable(message.content)

                tvBotMessage.setOnClickListener {
                    val visible = layoutBotActions.visibility == View.VISIBLE
                    layoutBotActions.visibility = if (visible) View.GONE else View.VISIBLE
                }

                btnCopyBot.setOnClickListener { onCopyMessage?.invoke(message.content) }
                btnPlayVoiceBot.setOnClickListener { onPlayVoice?.invoke(message.content) }
                btnFavoriteBot.setOnClickListener { onFavorite?.invoke(message.content) }
                btnShareBot.setOnClickListener { onShare?.invoke(message.content) }
                btnRegenerateBot.setOnClickListener { onRegenerate?.invoke(position) }
            }
        }

        private fun bindStreaming(message: Message) {
            binding.apply {
                layoutBot.visibility = View.VISIBLE
                layoutUser.visibility = View.GONE
                tvBotMessage.text = message.content

                if (message.loadingStatus != null) {
                    tvLoadingStatus.text = message.loadingStatus
                    tvLoadingStatus.visibility = View.VISIBLE
                    tvTypingIndicator.visibility = View.GONE
                } else {
                    tvLoadingStatus.visibility = View.GONE
                    tvTypingIndicator.visibility = if (message.content.isEmpty()) View.VISIBLE else View.GONE
                }
                layoutBotActions.visibility = View.GONE
                layoutUserActions.visibility = View.GONE
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
                layoutUserActions.visibility = View.GONE
                layoutStockTable.visibility = View.GONE
            }
        }

        private fun renderStockTable(content: String) {
            val lines = content.lines()
            val sepIdx = lines.indexOfFirst { it.trimStart().startsWith("|") && it.contains("---") }
            if (sepIdx < 0 || sepIdx < 1) { binding.layoutStockTable.visibility = View.GONE; return }
            val tbl = mutableListOf<String>()
            for (i in sepIdx - 1 until lines.size) {
                val l = lines[i].trim()
                if (l.startsWith("|")) tbl.add(l) else if (tbl.isNotEmpty()) break
            }
            if (tbl.size < 2) { binding.layoutStockTable.visibility = View.GONE; return }
            val sb = StringBuilder()
            for (i in tbl.indices) {
                if (i == 1) { sb.appendLine(tbl[i]); continue }
                sb.appendLine(tbl[i])
            }
            binding.tvStockTable.text = sb.toString().trimEnd()
            binding.layoutStockTable.visibility = View.VISIBLE
        }

        private fun formatTime(timestamp: Long): String {
            return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when {
            messages[position].isError -> VIEW_TYPE_ERROR
            messages[position].isStreaming -> VIEW_TYPE_STREAMING
            messages[position].isUser -> VIEW_TYPE_USER
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