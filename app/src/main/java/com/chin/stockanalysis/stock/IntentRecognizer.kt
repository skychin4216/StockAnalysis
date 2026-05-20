package com.chin.stockanalysis.stock

import android.util.Log
import com.chin.stockanalysis.stock.intent.IntentProcessorChain
import com.chin.stockanalysis.stock.intent.StockIntent
import com.chin.stockanalysis.stock.intent.handlers.*

/**
 * ## 云服务层 - 意图识别引擎
 *
 * 将本地意图识别（IntentProcessorChain + Handlers）和 AI 兜底分析
 * 统一封装为返回 [ProcessResult] 的独立引擎。
 *
 * ### 与 IntentProcessorChain 的不同
 * | 特性 | IntentProcessorChain | IntentRecognizer |
 * |------|-------------------|-----------------|
 * | 返回类型 | IntentResult | ProcessResult |
 * | AI 兜底 | 可选（需传入 AiStockAnalyzer） | 内置（需传入 ApiProvider） |
 * | 意图类型 | StockIntent (8种) | IntentType (11种) |
 * | 匹配规则 | 职责链 Handler | 封装职责链 + AI 兜底 |
 * | 适用场景 | StockService 内部调用 | 云端 API / 本地共用 |
 *
 * ### 使用示例
 * ```kotlin
 * val recognizer = IntentRecognizer(context)
 * val result = recognizer.recognizeAndProcess("分析今天股市")
 * // result.intentType = GENERAL_MARKET_CHAT
 * // result.stockCodes = []
 * // result.hasMarketData = false
 * ```
 *
 * ### 云端集成
 * 当部署到 Aliyun FC 或其他后端时，可直接用此类作为
 * `/api/analysis/complex` 接口的本地降级实现。
 */
class IntentRecognizer(
    /** 可选的 API 提供商，用于 AI 兜底意图解析 */
    private val apiProvider: Any? = null
) {
    private val tag = "IntentRecognizer"

    /**
     * 本地正则 Handler 列表（不含 AI 兜底，纯模式匹配）
     * 保持与 IntentProcessorChain 一致
     */
    private val localHandlers: List<IntentHandler> = buildList {
        add(StockCodeHandler())
        add(IndexHandler())
        add(StockNameHandler())
        add(GeneralStockHandler())
    }

    // ======================== 公开 API ========================

    /**
     * 识别用户输入的意图，返回统一 [ProcessResult]
     *
     * @param input 用户原始输入
     * @return ProcessResult（总是返回，不会抛出异常）
     */
    fun recognizeAndProcess(input: String): ProcessResult {
        if (input.isBlank()) {
            return createUnknown(input, "空白输入")
        }

        Log.d(tag, "═══════════════════════════════════════")
        Log.d(tag, "🔍 IntentRecognizer.recognizeAndProcess")
        Log.d(tag, "  输入: '${input.take(60)}'")
        Log.d(tag, "═══════════════════════════════════════")

        // 1. 本地 Handler 匹配
        for (handler in localHandlers) {
            val handlerName = handler::class.simpleName ?: "Unknown"
            if (handler.match(input)) {
                val intentResult = handler.parse(input)
                Log.d(tag, "  ✓ $handlerName: intent=${intentResult.intent}, codes=${intentResult.stockCodes}, confidence=${intentResult.confidence}")

                // 高置信度 → 直接转换为 ProcessResult
                if (intentResult.confidence >= 0.7f) {
                    val result = toProcessResult(intentResult)
                    Log.d(tag, "  → ProcessResult: type=${result.intentType}, codes=${result.stockCodes}, hasMarketData=${result.hasMarketData}")
                    Log.d(tag, "═══════════════════════════════════════")
                    return result
                }
                Log.d(tag, "  ${handlerName}: confidence=${intentResult.confidence} < 0.7, 尝试更通用的匹配...")
            }
        }

        // 2. 通用股市词汇检测（无需 AI 也能识别）
        val genericResult = detectGeneralMarketIntent(input)
        if (genericResult != null) {
            Log.d(tag, "  ✓ GeneralMarketDetector: type=${genericResult.intentType}, codes=${genericResult.stockCodes}")
            Log.d(tag, "═══════════════════════════════════════")
            return genericResult
        }

        // 3. 全部失败 → UNKNOWN
        Log.d(tag, "✗ 所有 Handler 和检测器都未匹配")
        Log.d(tag, "  → 返回 UNKNOWN (无需实时数据，AI 直接回答)")
        Log.d(tag, "═══════════════════════════════════════")
        return createUnknown(input, "未匹配任何已知模式")
    }

    // ======================== 内部辅助 ========================

    /**
     * 检测通用股市意图（不依赖具体股票代码/名称）
     *
     * 这些查询无法获取实时数据，但 AI 仍可基于知识回答
     */
    private fun detectGeneralMarketIntent(input: String): ProcessResult? {
        return when {
            // "分析今天股市" / "今天行情怎么样"
            matchesAny(input, listOf(
                Regex("分析.*(今天|今日|最近|近期).*(股市|大盘|市场|行情)"),
                Regex("(今天|今日|最近|近期).*(股市|大盘|市场|行情)")
            )) -> ProcessResult(
                intentType = IntentType.GENERAL_MARKET_CHAT,
                rawQuery = input,
                hasMarketData = false,
                promptInjection = "（用户询问通用股市情况，无具体股票代码，AI 应基于知识回答）"
            )

            // "推荐股票" / "推荐几只潜力股"
            matchesAny(input, listOf(Regex("推荐.*(股票|个股|板块|基金)"))) ->
                ProcessResult(
                    intentType = IntentType.INVESTMENT_ADVICE,
                    rawQuery = input,
                    hasMarketData = false,
                    promptInjection = "（用户请求投资建议，注意风险提示，不给具体买卖建议）"
                )

            // "涨停板" / "哪些股票涨停"
            matchesAny(input, listOf(Regex("涨停|跌停|涨幅榜|跌幅榜"))) ->
                ProcessResult(
                    intentType = IntentType.QUERY_HOT_STOCKS,
                    rawQuery = input,
                    hasMarketData = false,
                    promptInjection = "（用户询问涨跌榜，需要东方财富数据源扩展才能实时获取）"
                )

            // "白酒板块怎么样" / "半导体行业"
            matchesAny(input, listOf(Regex(".*板块.*(行情|走势|分析|怎么样)"), Regex(".*行业.*(行情|走势|分析|怎么样)"))) ->
                ProcessResult(
                    intentType = IntentType.QUERY_SECTOR,
                    rawQuery = input,
                    hasMarketData = false,
                    promptInjection = "（用户询问特定板块/行业，需要板块数据源扩展才能实时获取）"
                )

            // "适合买吗" / "能买吗"
            matchesAny(input, listOf(Regex(".*适[合不]*(买|入|投资).*"), Regex(".*(买|卖|入).*(建议|时机|点).*"))) ->
                ProcessResult(
                    intentType = IntentType.INVESTMENT_ADVICE,
                    rawQuery = input,
                    hasMarketData = false,
                    promptInjection = "（用户询问买卖时机，注意规则：不给具体建议，仅提供分析参考）"
                )

            // "分析[股票名称]" 或 "[股票名称]怎么样" — 其中名称不在 StockNameHandler 映射表中
            // 即使不知道具体代码，也标记为 GENERAL_MARKET_CHAT，让 AI 基于知识回答
            matchesAny(input, listOf(
                Regex("分析\\w{2,5}"),
                Regex("\\w{2,5}(多少钱|怎么样|市值|行情|走势|基本面|技术面)")
            )) -> ProcessResult(
                intentType = IntentType.GENERAL_MARKET_CHAT,
                rawQuery = input,
                hasMarketData = false,
                promptInjection = "（用户分析具体的股票或公司，但该名称不在系统的硬编码映射表中。AI 应基于训练知识回答，说明无法获取实时数据但可以提供基本面信息或行业分析）"
            )

            // 包含股市/股票关键词但未匹配具体代码
            matchesAny(input, listOf(Regex("股市|大盘|行情|A股|股票|市场|指数"))) ->
                ProcessResult(
                    intentType = IntentType.GENERAL_MARKET_CHAT,
                    rawQuery = input,
                    hasMarketData = false,
                    promptInjection = "（用户提及股市相关主题，AI 应基于训练知识回答）"
                )

            else -> null
        }
    }

    /**
     * 将 IntentResult 转换为 ProcessResult
     */
    private fun toProcessResult(intentResult: com.chin.stockanalysis.stock.intent.IntentResult): ProcessResult {
        val hasData = intentResult.stockCodes.isNotEmpty()
        val type = when (intentResult.intent) {
            StockIntent.QUERY_PRICE -> IntentType.SIMPLE_QUERY
            StockIntent.QUERY_INDEX -> IntentType.QUERY_INDEX
            StockIntent.QUERY_HOT_STOCKS -> IntentType.QUERY_HOT_STOCKS
            StockIntent.QUERY_SECTOR -> IntentType.QUERY_SECTOR
            StockIntent.QUERY_FINANCIALS -> IntentType.QUERY_FINANCIALS
            StockIntent.TECHNICAL_ANALYSIS -> IntentType.TECHNICAL_ANALYSIS
            StockIntent.COMPARE_STOCKS -> IntentType.COMPARE_STOCKS
            StockIntent.UNKNOWN -> IntentType.UNKNOWN
            else -> IntentType.UNKNOWN
        }

        return ProcessResult(
            intentType = type,
            stockCodes = intentResult.stockCodes,
            stockNames = intentResult.stockNames,
            confidence = intentResult.confidence,
            rawQuery = intentResult.rawQuery,
            hasMarketData = hasData,
            parsedParams = intentResult.parsedParams
        )
    }

    /**
     * 创建 UNKNOWN 结果
     */
    private fun createUnknown(input: String, reason: String): ProcessResult {
        return ProcessResult(
            intentType = IntentType.UNKNOWN,
            rawQuery = input,
            confidence = 0.0f,
            hasMarketData = false,
            parsedParams = mapOf("diagnostic" to reason)
        )
    }

    /**
     * 辅助：检查输入是否匹配多个正则表达式中的任意一个
     */
    private fun matchesAny(input: String, patterns: List<Regex>): Boolean {
        return patterns.any { it.containsMatchIn(input) }
    }

    /**
     * 获取所有支持的正则模式列表（用于调试和诊断）
     */
    fun getSupportedPatterns(): Map<String, String> {
        return mapOf(
            "StockCodeHandler" to "6位股票代码（如 600519）",
            "IndexHandler" to "指数名称（上证指数/沪深300等）",
            "StockNameHandler" to "股票名称（茅台/五粮液等28只）",
            "GeneralStockHandler" to "通用股市词汇（股市/大盘/行情等）",
            "GeneralMarketDetector" to "通用模式（分析今天股市/推荐股票/涨停板/板块/买卖建议）"
        )
    }
}