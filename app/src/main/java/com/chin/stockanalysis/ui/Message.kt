package com.chin.stockanalysis.ui
// ====================== 【统一】消息数据类 ======================

data class Message(
    val id: String = System.currentTimeMillis().toString(),
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    /** v9.0: 加载状态文字，如 "正在搜索5个关键字，参考3篇资料" */
    val loadingStatus: String? = null
)