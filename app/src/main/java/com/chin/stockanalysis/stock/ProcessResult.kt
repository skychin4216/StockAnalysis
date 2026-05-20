package com.chin.stockanalysis.stock

/**
 * ## 云服务层 - 统一处理结果
 *
 * 将 IntentProcessorChain 和 StockService 的处理结果统一封装，
 * 用于云端 API 和本地处理的一致性接口。
 *
 * ### 与现有类的关系
 * - IntentResult → 意图识别结果（codes/names/confidence）
 * - StockContext → 服务处理上下文（hasStockData/promptPrefix）
 * - **ProcessResult** → 两者的统一，额外增加云端所需字段
 *
 * ### 使用场景
 * ```
 * 用户输入 → IntentRecognizer → ProcessResult
 *                                     ↓
 *   ┌──────────────────────────────────┴──┐
 *   ↓ (本地)                              ↓ (云端)
 * StockService                           RemoteDataService
 *   ↓                                      ↓
 * StockContext                            API Response
 * ```
 */
data class ProcessResult(
    /** 识别的意图类型 */
    val intentType: IntentType,
    /** 提取的股票代码列表（如 ["sh600519"]） */
    val stockCodes: List<String> = emptyList(),
    /** 提取的股票名称列表（如 ["贵州茅台"]） */
    val stockNames: List<String> = emptyList(),
    /** 置信度 0.0~1.0 */
    val confidence: Float = 0.0f,
    /** 原始用户输入 */
    val rawQuery: String = "",
    /** 用于注入 AI prompt 的文本（包含实时行情等上下文） */
    val promptInjection: String = "",
    /** 是否有实时股市数据被注入 */
    val hasMarketData: Boolean = false,
    /** 额外解析参数（如分析类型、时间范围、板块等） */
    val parsedParams: Map<String, Any> = emptyMap()
)

/**
 * 意图类型枚举 - 覆盖5大类云端和本地共用的识别类型
 */
enum class IntentType {
    /** 简单价格查询，如 "600519多少钱" */
    SIMPLE_QUERY,
    /** 查询大盘指数，如 "上证指数多少" */
    QUERY_INDEX,
    /** 查询热门/涨停股票，如 "今日涨停板" */
    QUERY_HOT_STOCKS,
    /** 板块行情查询，如 "白酒板块" */
    QUERY_SECTOR,
    /** 技术分析，如 "分析茅台MACD" */
    TECHNICAL_ANALYSIS,
    /** 对比分析，如 "茅台和五粮液对比" */
    COMPARE_STOCKS,
    /** 查询财务/基本面数据 */
    QUERY_FINANCIALS,
    /** 产业链分析，如 "人形机器人产业链" */
    INDUSTRY_ANALYSIS,
    /** 投资建议询问，如 "适合买吗" */
    INVESTMENT_ADVICE,
    /** 包含股市关键词但无具体股票，如 "分析今天股市" */
    GENERAL_MARKET_CHAT,
    /** 无法识别为任何股票相关意图 */
    UNKNOWN
}