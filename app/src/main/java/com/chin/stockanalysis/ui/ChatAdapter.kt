package com.chin.stockanalysis.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chin.stockanalysis.databinding.ItemMessageBinding
import com.chin.stockanalysis.databinding.ItemMessageChartBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j

/**
 * ChatAdapter P0~P3
 *
 * - P0: Markwon Markdown 渲染（取代 HtmlCompat）
 * - P1: 語法高亮 + 多 ViewType
 * - P2: 圖表 ViewType 支援 (ContentBlock)
 * - P3: LaTeX 數學公式
 */
class ChatAdapter(
    private val messages: MutableList<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
        private const val VIEW_TYPE_STREAMING = 3
        private const val VIEW_TYPE_ERROR = 4
        private const val VIEW_TYPE_CHART = 5
    }

    var onCopyMessage: ((String) -> Unit)? = null
    var onPlayVoice: ((String) -> Unit)? = null
    var onFavorite: ((String) -> Unit)? = null
    var onShare: ((String) -> Unit)? = null
    var onRegenerate: ((Int) -> Unit)? = null

    private var markwon: Markwon? = null

    private fun getMarkwon(context: android.content.Context): Markwon {
        if (markwon == null) {
            val prism4j = Prism4j(object : GrammarLocator {
                override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? = null
                override fun languages(): Set<String> = emptySet()
            })
            val theme = Prism4jThemeDefault.create()
            markwon = Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, theme))
                .usePlugin(JLatexMathPlugin.create(18f))
                .build()
        }
        return markwon!!
    }

    // ================================================================
    //  ViewHolder 類型
    // ================================================================

    // ── 用戶消息 ──
    inner class UserViewHolder(private val binding: ItemMessageBinding)
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message, position: Int) {
            binding.apply {
                tvTime.text = formatTime(message.timestamp)
                tvTime.visibility = View.VISIBLE
                layoutBot.visibility = View.GONE
                layoutUser.visibility = View.VISIBLE

                tvUserMessage.text = message.content
                tvUserMessage.setOnClickListener {
                    layoutUserActions.visibility =
                        if (layoutUserActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                btnCopyUser.setOnClickListener { onCopyMessage?.invoke(message.content) }
                btnPlayVoiceUser.setOnClickListener { onPlayVoice?.invoke(message.content) }
                btnFavoriteUser.setOnClickListener { onFavorite?.invoke(message.content) }
                btnShareUser.setOnClickListener { onShare?.invoke(message.content) }
            }
        }
    }

    // ── AI 消息（Markwon Markdown 渲染）──
    inner class AiViewHolder(private val binding: ItemMessageBinding)
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message, position: Int) {
            binding.apply {
                tvTime.text = formatTime(message.timestamp)
                tvTime.visibility = View.VISIBLE
                layoutUser.visibility = View.GONE
                layoutBot.visibility = View.VISIBLE

                // P0: Markwon 渲染 Markdown（表格、粗斜體、代碼區塊等）
                // P3: JLatexMathPlugin 自動處理 $$...$$ 和 $...$ 公式
                getMarkwon(root.context).setMarkdown(tvBotMessage, message.content)
                tvBotMessage.visibility = View.VISIBLE
                tvTypingIndicator.visibility = View.GONE
                tvLoadingStatus.visibility = View.GONE
                tvErrorHint.visibility = View.GONE

                tvBotMessage.setOnClickListener {
                    layoutBotActions.visibility =
                        if (layoutBotActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                btnCopyBot.setOnClickListener { onCopyMessage?.invoke(message.content) }
                btnPlayVoiceBot.setOnClickListener { onPlayVoice?.invoke(message.content) }
                btnFavoriteBot.setOnClickListener { onFavorite?.invoke(message.content) }
                btnShareBot.setOnClickListener { onShare?.invoke(message.content) }
                btnRegenerateBot.setOnClickListener { onRegenerate?.invoke(position) }
            }
        }
    }

    // ── 串流消息（純文本，不解析 Markdown）──
    inner class StreamingViewHolder(private val binding: ItemMessageBinding)
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            binding.apply {
                tvTime.text = formatTime(message.timestamp)
                tvTime.visibility = View.VISIBLE
                layoutBot.visibility = View.VISIBLE
                layoutUser.visibility = View.GONE

                // 串流期間使用純文本
                tvBotMessage.text = message.content
                tvBotMessage.visibility = View.VISIBLE
                tvErrorHint.visibility = View.GONE

                if (message.loadingStatus != null) {
                    tvLoadingStatus.text = message.loadingStatus
                    tvLoadingStatus.visibility = View.VISIBLE
                    tvTypingIndicator.visibility = View.GONE
                } else {
                    tvLoadingStatus.visibility = View.GONE
                    tvTypingIndicator.visibility =
                        if (message.content.isEmpty()) View.VISIBLE else View.GONE
                }
                layoutBotActions.visibility = View.GONE
                layoutUserActions.visibility = View.GONE
            }
        }
    }

    // ── 錯誤消息 ──
    inner class ErrorViewHolder(private val binding: ItemMessageBinding)
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            binding.apply {
                tvTime.text = formatTime(message.timestamp)
                tvTime.visibility = View.VISIBLE
                layoutBot.visibility = View.VISIBLE
                layoutUser.visibility = View.GONE

                tvErrorHint.text = message.errorMessage ?: message.content
                tvErrorHint.visibility = View.VISIBLE
                tvBotMessage.visibility = View.GONE
                tvTypingIndicator.visibility = View.GONE
                tvLoadingStatus.visibility = View.GONE
                layoutBotActions.visibility = View.GONE
                layoutUserActions.visibility = View.GONE
            }
        }
    }

    // ── 圖表消息（P2）──
    inner class ChartViewHolder(private val binding: ItemMessageChartBinding)
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            binding.apply {
                tvTime.text = formatTime(message.timestamp)
                tvTime.visibility = View.VISIBLE

                // 解析 content 中的圖表參數（格式: __CHART__|stockCode|stockName|title）
                val parts = message.content.split("|")
                val chartTitle = if (parts.size >= 4) parts[3] else "📊 走勢圖"
                val stockCode = if (parts.size >= 2) parts[1] else ""
                val stockDesc = if (parts.size >= 3 && parts[2].isNotBlank()) parts[2] else stockCode

                tvChartTitle.text = chartTitle
                tvChartTitle.visibility = View.VISIBLE
                tvChartDesc.text = "🔍 $stockDesc ($stockCode)"
                tvChartDesc.visibility = View.VISIBLE
                lineChart.visibility = View.VISIBLE

                // 清空樣本數據（實際數據由外部注入）
                lineChart.data = null
                lineChart.invalidate()
            }
        }
    }

    // ================================================================
    //  Adapter 實現
    // ================================================================

    override fun getItemViewType(position: Int): Int {
        val msg = messages[position]
        return when {
            msg.isError -> VIEW_TYPE_ERROR
            msg.isStreaming -> VIEW_TYPE_STREAMING
            msg.isUser -> VIEW_TYPE_USER
            msg.content.startsWith("__CHART__") -> VIEW_TYPE_CHART
            else -> VIEW_TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_CHART -> {
                val binding = ItemMessageChartBinding.inflate(inflater, parent, false)
                ChartViewHolder(binding)
            }
            else -> {
                val binding = ItemMessageBinding.inflate(inflater, parent, false)
                when (viewType) {
                    VIEW_TYPE_USER -> UserViewHolder(binding)
                    VIEW_TYPE_AI -> AiViewHolder(binding)
                    VIEW_TYPE_STREAMING -> StreamingViewHolder(binding)
                    VIEW_TYPE_ERROR -> ErrorViewHolder(binding)
                    else -> AiViewHolder(binding)
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserViewHolder -> holder.bind(messages[position], position)
            is AiViewHolder -> holder.bind(messages[position], position)
            is StreamingViewHolder -> holder.bind(messages[position])
            is ErrorViewHolder -> holder.bind(messages[position])
            is ChartViewHolder -> holder.bind(messages[position])
        }
    }

    override fun getItemCount() = messages.size

    private fun formatTime(timestamp: Long): String {
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }
}