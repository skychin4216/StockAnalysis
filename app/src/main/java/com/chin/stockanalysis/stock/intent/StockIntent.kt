package com.chin.stockanalysis.stock.intent

/**
 * 股票查询意图枚举
 */
enum class StockIntent {
    QUERY_PRICE,        // 查询实时股价（单隻/多隻）
    QUERY_INDEX,        // 查询大盘指数
    QUERY_HOT_STOCKS,   // 热门股票/涨停板
    QUERY_HISTORY,      // 历史K线数据
    QUERY_FINANCIALS,   // 财务数据/基本面
    QUERY_SECTOR,       // 板块行情
    COMPARE_STOCKS,     // 对比分析
    TECHNICAL_ANALYSIS, // 技术分析
    UNKNOWN             // 不包含股票意图
}

/**
 * 意图解析结果
 */
data class IntentResult(
    val intent: StockIntent,
    val stockCodes: List<String>,       // 提取出的股票代码
    val stockNames: List<String>,       // 提取出的股票名称
    val confidence: Float,              // 置信度 0.0~1.0
    val rawQuery: String,               // 原始用户输入
    val parsedParams: Map<String, Any> = emptyMap()  // 额外参数
)

