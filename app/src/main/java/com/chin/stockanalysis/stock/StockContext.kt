package com.chin.stockanalysis.stock

import com.chin.stockanalysis.stock.intent.IntentResult

/**
 * 股票服务处理结果上下文
 * 用于传递给 ChatActivity，注入到 AI prompt
 */
data class StockContext(
    val intent: IntentResult,
    val hasStockData: Boolean,
    val promptPrefix: String,  // 注入到 system prompt 的文本
    val realtimeData: Map<String, StockRealtime> = emptyMap()  // v10.1: 即時行情原始數據（供持久化）
)

