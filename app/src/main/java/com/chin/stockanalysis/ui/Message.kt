package com.chin.stockanalysis.ui

import com.chin.stockanalysis.ai.StockEntityExtractor

// ====================== 【统一】消息数据类 ======================

/** 内容块类型（P2 动态布局） */
enum class ContentType { TEXT, TABLE, CODE, CHART, LATEX }

/** 内容块（用于 P2 混合布局） */
data class ContentBlock(
    val type: ContentType,
    val content: String,
    val language: String? = null  // 代码块语言（如 "kotlin", "python"）
)

data class Message(
    val id: String = System.currentTimeMillis().toString(),
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    /** v9.0: 加载状态文字，如 "正在搜索5个关键字，参考3篇资料" */
    val loadingStatus: String? = null,
    /** P2: 内容块列表（动态布局），非 null 时优先使用 */
    val contentBlocks: List<ContentBlock>? = null,
    /** EntityConfirmCard: 歧義實體列表，非 null 且非空時顯示確認卡片 */
    val ambiguousEntities: List<StockEntityExtractor.ExtractedEntity>? = null,
    /** EntityConfirmCard 回調：確認選擇 & 取消 */
    val onEntityConfirm: ((EntityConfirmCard.Candidate) -> Unit)? = null,
    val onEntityCancel: (() -> Unit)? = null
)