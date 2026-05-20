package com.chin.stockanalysis.stock.analysis

import android.util.Log
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.ui.Message
import com.chin.stockanalysis.stock.StockRealtime
import com.chin.stockanalysis.stock.intent.IntentResult
import com.chin.stockanalysis.stock.intent.StockIntent
import com.chin.stockanalysis.stock.data.StockRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * ## AI 意图解析 + 技术分析器
 *
 * ### 职责
 * 1. **AI 意图解析** — 当普通 Handler 无法匹配或置信度低时，调用 LLM 解析复杂意图
 * 2. **技术分析** — 基于实时行情 + AI，生成专业的技术分析文本
 * 3. **对比分析** — 多只股票横向对比，由 AI 生成分析报告
 *
 * ### 工作流程
 * ```
 * 用户输入 → AiStockAnalyzer.analyze()
 *     │
 *     ├─ 1. 构造 JSON 格式 prompt 发送给 AI
 *     ├─ 2. AI 返回结构化 JSON
 *     ├─ 3. 解析 JSON → IntentResult
 *     └─ 4. 执行意图（获取数据、格式化）
 * ```
 *
 * ### 使用示例
 * ```kotlin
 * val analyzer = AiStockAnalyzer(apiProvider, repository)
 * val intent = analyzer.analyzeIntent("今天哪些股票涨停了？")
 * // intent = IntentResult(QUERY_HOT_STOCKS, confidence=0.9f)
 * ```
 */
class AiStockAnalyzer(
    /** AI API 提供商（复用 ChatActivity 的配置） */
    private val apiProvider: ApiProvider,
    /** 数据仓储（用于执行意图时获取数据） */
    private val repository: StockRepository? = null
) {
    private val tag = "AiStockAnalyzer"

    companion object {
        /**
         * AI 意图解析 Prompt
         *
         * 要求 AI 以纯 JSON 格式返回，禁止输出其他文字。
         */
        private const val INTENT_PROMPT = """你是一个A股股票分析助手。请分析用户的问题意图，并以 **纯JSON** 格式返回，不要输出任何其他文字。

用户输入: {userInput}

请判断用户意图，返回以下 JSON 格式：
{
  "intent": "query_price|query_hot_stocks|query_index|query_sector|compare|technical_analysis|financial|unknown",
  "stocks": ["股票名称或代码"],
  "sector": "板块名称或null",
  "time_range": "today|this_week|this_month|3months|6months|this_year|null",
  "analysis_type": "macd|kdj|rsi|volume|trend|null",
  "explanation": "对用户意图的简短解释"
}

示例：
输入: "今天哪些股票涨停了？"
输出: {"intent":"query_hot_stocks","stocks":[],"sector":null,"time_range":"today","analysis_type":null,"explanation":"查询今日涨停板股票"}

输入: "帮我分析茅台的MACD指标"
输出: {"intent":"technical_analysis","stocks":["贵州茅台"],"sector":null,"time_range":"3months","analysis_type":"macd","explanation":"对贵州茅台进行MACD技术分析"}

输入: "白酒板块走势怎么样"
输出: {"intent":"query_sector","stocks":[],"sector":"白酒","time_range":"this_month","analysis_type":null,"explanation":"查询白酒板块整体走势"}

输入: "茅台和五粮液哪个更值得投资"
输出: {"intent":"compare","stocks":["贵州茅台","五粮液"],"sector":null,"time_range":null,"analysis_type":null,"explanation":"对比分析茅台和五粮液"}"""

        /**
         * AI 技术分析 Prompt 模板
         */
        private const val TECHNICAL_PROMPT = """你是一个专业的A股技术分析师。请基于以下实时行情数据进行技术分析。

{realtimeData}

用户问题: {userQuery}
分析类型: {analysisType}

请从以下几个方面进行分析（用中文，专业但易懂）：
1. 当前价格位置（相对于近期高低点）
2. 量价关系分析
3. 短线趋势判断
4. 关键支撑和压力位
5. 风险提示

注意：数据仅供分析参考，不构成投资建议。"""
    }

    // ======================== 公开 API ========================

    /**
     * 调用 AI 解析用户意图（挂起函数，等待 AI 返回）
     *
     * @param userInput 用户原始输入
     * @return IntentResult 解析结果，失败时返回 UNKNOWN
     */
    suspend fun analyzeIntent(userInput: String): IntentResult {
        if (userInput.isBlank()) return createUnknown(userInput)

        Log.d(tag, "analyzeIntent: calling AI for: '${userInput.take(50)}...'")

        val prompt = INTENT_PROMPT.replace("{userInput}", userInput)

        try {
            val jsonStr = callAiForJson(prompt)

            if (jsonStr.isBlank()) {
                Log.w(tag, "AI returned empty response")
                return createUnknown(userInput)
            }

            return parseAiResponse(jsonStr, userInput)
        } catch (e: Exception) {
            Log.e(tag, "AI intent analysis failed: ${e.message}", e)
            return createUnknown(userInput)
        }
    }

    /**
     * 调用 AI 进行技术分析
     *
     * @param userQuery 用户原始问题
     * @param realtimeData 格式化后的实时行情文本
     * @param analysisType 分析类型（macd/kdj/rsi/volume/trend）
     * @return AI 分析结果文本
     */
    suspend fun technicalAnalysis(
        userQuery: String,
        realtimeData: String,
        analysisType: String = "general"
    ): String {
        val prompt = TECHNICAL_PROMPT
            .replace("{realtimeData}", realtimeData)
            .replace("{userQuery}", userQuery)
            .replace("{analysisType}", analysisType)

        Log.d(tag, "technicalAnalysis: calling AI for analysis type=$analysisType")

        return try {
            val result = callAiForJson(prompt)
            if (result.isBlank()) "（AI 技术分析暂时不可用）" else result
        } catch (e: Exception) {
            Log.e(tag, "Technical analysis failed: ${e.message}")
            "（技术分析服务暂时不可用，请稍后再试）"
        }
    }

    /**
     * 执行 AI 解析出的意图
     *
     * @param intent AI 解析结果
     * @return 适合注入 AI prompt 的文本
     */
    suspend fun executeIntent(intent: IntentResult): String {
        return when (intent.intent) {
            StockIntent.TECHNICAL_ANALYSIS -> {
                if (repository == null) return "（数据仓储未配置）"
                val data = repository.getRealtime(intent.stockCodes)
                if (data.isEmpty()) return "（未获取到 ${intent.stockCodes.joinToString()} 的实时数据）"
                val realtimeText = formatRealtimeForPrompt(data)
                val analysisType = intent.parsedParams["analysis_type"]?.toString() ?: "general"
                technicalAnalysis(intent.rawQuery, realtimeText, analysisType)
            }
            StockIntent.COMPARE_STOCKS -> {
                if (repository == null) return "（数据仓储未配置）"
                val data = repository.getRealtime(intent.stockCodes)
                if (data.isEmpty()) return "（未获取到对比数据）"
                formatCompareForPrompt(intent, data)
            }
            StockIntent.QUERY_SECTOR -> {
                "【板块查询】用户查询板块: ${intent.parsedParams["sector"] ?: "未知"}（此功能需要在东方财富/腾讯数据源中扩展，当前暂未实现。）"
            }
            StockIntent.QUERY_HOT_STOCKS -> {
                "【热门股票查询】用户想查询: ${intent.parsedParams["time_range"] ?: "今日"}的热门股票。（此功能需要在东方财富数据源中扩展，当前暂未实现。）"
            }
            StockIntent.QUERY_FINANCIALS -> {
                "【财务数据查询】用户查询 ${intent.stockNames.joinToString()} 的财务数据。（此功能需要扩展基本面数据源，当前暂未实现。）"
            }
            else -> {
                // QUERY_PRICE/QUERY_INDEX 由 StockService 处理
                ""
            }
        }
    }

    // ======================== 内部实现 ========================

    /**
     * 调用 AI API 并获取完整的 JSON 响应
     * 将回调式 API 包装为挂起函数
     */
    private suspend fun callAiForJson(prompt: String): String {
        return suspendCancellableCoroutine { continuation ->
            val messages = listOf(Message(content = prompt, isUser = true))
            val sb = StringBuilder()

            apiProvider.sendMessageStream(
                messages = messages,
                systemPrompt = "你是一个JSON输出助手。严格按照要求返回纯JSON，不要输出任何其他文字。",
                onSuccess = { chunk ->
                    sb.append(chunk)
                },
                onComplete = { full ->
                    val result = if (full.isNotEmpty()) full else sb.toString()
                    if (continuation.isActive) {
                        continuation.resume(extractJson(result))
                    }
                },
                onError = { errMsg ->
                    Log.e(tag, "AI call error: $errMsg")
                    if (continuation.isActive) {
                        continuation.resume("")
                    }
                }
            )
        }
    }

    /**
     * 从 AI 返回文本中提取 JSON
     * 支持：纯 JSON、markdown 代码块、含前缀的 JSON
     */
    private fun extractJson(text: String): String {
        // 尝试移除 markdown 代码块标记
        var cleaned = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        // 找到第一个 { 和最后一个 }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            cleaned.substring(start, end + 1)
        } else {
            cleaned
        }
    }

    /**
     * 解析 AI 返回的 JSON 字符串为 IntentResult
     */
    private fun parseAiResponse(jsonStr: String, rawQuery: String): IntentResult {
        return try {
            val json = JSONObject(jsonStr)
            val intentStr = json.optString("intent", "unknown")
            val intent = when (intentStr) {
                "query_price" -> StockIntent.QUERY_PRICE
                "query_hot_stocks" -> StockIntent.QUERY_HOT_STOCKS
                "query_index" -> StockIntent.QUERY_INDEX
                "query_sector" -> StockIntent.QUERY_SECTOR
                "compare" -> StockIntent.COMPARE_STOCKS
                "technical_analysis" -> StockIntent.TECHNICAL_ANALYSIS
                "financial" -> StockIntent.QUERY_FINANCIALS
                else -> StockIntent.UNKNOWN
            }

            val stocks = json.optJSONArray("stocks")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()

            val sector = json.optString("sector", "")
            val timeRange = json.optString("time_range", "")
            val analysisType = json.optString("analysis_type", "")

            val params = mutableMapOf<String, Any>()
            if (sector.isNotBlank() && sector != "null") params["sector"] = sector
            if (timeRange.isNotBlank() && timeRange != "null") params["time_range"] = timeRange
            if (analysisType.isNotBlank() && analysisType != "null") params["analysis_type"] = analysisType

            Log.d(tag, "Parsed intent: $intent, stocks=$stocks, params=$params")

            IntentResult(
                intent = intent,
                stockCodes = emptyList(), // AI 返回名称/代码，需由调用方转码
                stockNames = stocks,
                confidence = if (intent != StockIntent.UNKNOWN) 0.85f else 0.2f,
                rawQuery = rawQuery,
                parsedParams = params
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse AI JSON response: ${e.message}")
            Log.d(tag, "Raw response: $jsonStr")
            createUnknown(rawQuery)
        }
    }

    /**
     * 创建 UNKNOWN 意图
     */
    private fun createUnknown(rawQuery: String): IntentResult {
        return IntentResult(
            intent = StockIntent.UNKNOWN,
            stockCodes = emptyList(),
            stockNames = emptyList(),
            confidence = 0.0f,
            rawQuery = rawQuery
        )
    }

    /**
     * 将实时行情数据格式化为 AI prompt 文本
     */
    private fun formatRealtimeForPrompt(data: Map<String, StockRealtime>): String {
        val sb = StringBuilder()
        sb.appendLine("【实时行情数据】")
        for ((code, stock) in data) {
            val arrow = when {
                stock.changeAmount > 0 -> "📈"
                stock.changeAmount < 0 -> "📉"
                else -> "➡️"
            }
            sb.appendLine("$arrow ${stock.name} ($code)")
            sb.appendLine("  当前: ${"%.2f".format(stock.price)} 元")
            sb.appendLine("  涨跌: ${if (stock.changeAmount > 0) "+" else ""}${"%.2f".format(stock.changeAmount)} (${"%.2f".format(stock.changePercent)}%)")
            sb.appendLine("  最高: ${"%.2f".format(stock.high)}  最低: ${"%.2f".format(stock.low)}")
            sb.appendLine("  成交量: ${stock.volume / 10000} 万手  成交额: ${"%.2f".format(stock.amount / 100000000)} 亿元")
            sb.appendLine("  昨收: ${"%.2f".format(stock.yestClose)}  开盘: ${"%.2f".format(stock.open)}")
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * 格式化对比分析数据
     */
    private fun formatCompareForPrompt(intent: IntentResult, data: Map<String, StockRealtime>): String {
        val sb = StringBuilder()
        sb.appendLine("【股票对比分析上下文】")
        sb.appendLine("用户原问题：${intent.rawQuery}")
        sb.appendLine()
        sb.append(formatRealtimeForPrompt(data))
        sb.appendLine("请基于以上多只股票实时行情，从涨跌幅、成交活跃度、价格位置、短线风险等维度进行横向对比。")
        sb.appendLine("输出要求：")
        sb.appendLine("1. 用表格或分点对比；")
        sb.appendLine("2. 强调数据时效性和风险；")
        sb.appendLine("3. 不给具体买卖指令，仅提供分析参考。")
        return sb.toString()
    }
}