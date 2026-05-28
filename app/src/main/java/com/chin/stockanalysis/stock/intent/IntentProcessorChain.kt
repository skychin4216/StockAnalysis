package com.chin.stockanalysis.stock.intent

import android.util.Log
import com.chin.stockanalysis.stock.analysis.AiStockAnalyzer
import com.chin.stockanalysis.stock.intent.handlers.*

/**
 * 意图处理器链 - 使用职责链模式处理用户输入
 *
 * 处理流程：
 * 1. StockCodeHandler     - 优先：纯数字代码（如"600519"）
 * 2. IndexHandler         - 指数查询（如"上证指数"）
 * 3. StockNameHandler     - 股票名称（硬编码常见股票，如"茅台"）
 * 4. FuzzyStockNameHandler- 动态搜索（东方财富 API，覆盖任意股票名称如"北方稀土"）
 * 5. GeneralStockHandler  - 通用股市查询
 * 6. AiIntentHandler      - AI 兜底解析（需要 LLM，可选）
 *
 * 如果 AI 分析器可用且前面 Handler 置信度 < 0.7，自动使用 AI 兜底。
 */
class IntentProcessorChain(
    /** AI 分析器（可选，用于复杂意图的 AI 兜底解析） */
    private val aiAnalyzer: AiStockAnalyzer? = null
) {
    private val tag = "IntentProcessorChain"
    /** 置信度阈值：低于此值则尝试 AI 兜底 */
    private val AI_FALLBACK_THRESHOLD = 0.7f

    private val handlers: List<IntentHandler> = buildList {
        add(StockCodeHandler())          // 优先：纯数字代码
        add(IndexHandler())              // 指数查询
        add(StockNameHandler())          // 股票名称（硬编码常见股票）
        add(FuzzyStockNameHandler())     // 模糊搜索（东方财富 API，任意股票名如"北方稀土"）
        add(GeneralStockHandler())       // 通用股市查询（低置信度）
        // AI 兜底（如果可用）
        if (aiAnalyzer != null) {
            add(AiIntentHandler(aiAnalyzer))
        }
    }

    /**
     * 同步处理用户输入，返回意图识别结果
     *
     * v2.0 改进：遍历所有 handler 并合并结果，支持多股票同时查询
     * （如 "汇顶科技和北方稀土"、"600519和茅台" 等）
     *
     * 适用于：纯代码/名称匹配，不需要 AI 兜底的场景
     */
    fun process(input: String): IntentResult {
        Log.d(tag, "═══════════════════════════════════════")
        Log.d(tag, "🔍 IntentProcessorChain.process start")
        Log.d(tag, "  输入: '${input.take(60)}'")
        Log.d(tag, "  handlers: ${handlers.map { it::class.simpleName }}")
        Log.d(tag, "═══════════════════════════════════════")

        val allCodes = mutableListOf<String>()
        val allNames = mutableListOf<String>()
        var bestIntent = StockIntent.UNKNOWN
        var bestConfidence = 0.0f
        val allParsedParams = mutableMapOf<String, Any?>()

        for (handler in handlers) {
            val handlerName = handler::class.simpleName ?: "Unknown"

            // GeneralStockHandler 只在没有其他 handler 匹配时才使用
            if (handler is GeneralStockHandler && (allCodes.isNotEmpty() || bestIntent != StockIntent.UNKNOWN)) {
                Log.d(tag, "  ⊘ $handlerName: 已有 ${allCodes.size} codes，跳过通用Handler")
                continue
            }

            val matched = handler.match(input)
            Log.d(tag, "  ➡ $handlerName.match → $matched")

            if (matched) {
                val result = handler.parse(input)
                Log.d(tag, "  ➡ $handlerName.parse() → intent=${result.intent}, codes=${result.stockCodes}, names=${result.stockNames}, confidence=${result.confidence}")

                if (result.confidence >= AI_FALLBACK_THRESHOLD) {
                    // 合并股票代码（去重）
                    for ((i, code) in result.stockCodes.withIndex()) {
                        if (code !in allCodes) {
                            allCodes.add(code)
                            if (i < result.stockNames.size) {
                                allNames.add(result.stockNames[i])
                            }
                        }
                    }

                    // 意图取优先级最高的
                    val intentPriority = intentPriority(result.intent)
                    if (intentPriority > intentPriority(bestIntent)) {
                        bestIntent = result.intent
                    }

                    // 取最高置信度
                    if (result.confidence > bestConfidence) {
                        bestConfidence = result.confidence
                    }

                    // 合并参数
                    allParsedParams.putAll(result.parsedParams)

                    Log.d(tag, "  ✓ $handlerName: merged (confidence=${result.confidence}), 累计 codes=${allCodes.size}")
                } else {
                    Log.d(tag, "  ${handlerName}: confidence=${result.confidence} < $AI_FALLBACK_THRESHOLD, skipping")
                }
            }
        }

        if (allCodes.isNotEmpty()) {
            // 如果合并后有多只股票，升级意图（无明确对比/分析意图则默认 QUERY_PRICE）
            val finalIntent = if (allCodes.distinct().size >= 2 && bestIntent != StockIntent.COMPARE_STOCKS) {
                if (input.contains("对比") || input.contains("比较") || input.contains("哪个") || input.contains("哪只") || input.contains("VS", ignoreCase = true))
                    StockIntent.COMPARE_STOCKS
                else if (input.contains("分析") || input.contains("走势") || input.contains("技术"))
                    StockIntent.TECHNICAL_ANALYSIS
                else
                    StockIntent.QUERY_PRICE
            } else {
                if (bestIntent == StockIntent.UNKNOWN) StockIntent.QUERY_PRICE else bestIntent
            }

            val distinctCodes = allCodes.distinct()
            val distinctNames = allNames.distinct()
            val result = IntentResult(
                intent = finalIntent,
                stockCodes = distinctCodes,
                stockNames = distinctNames,
                confidence = bestConfidence,
                rawQuery = input,
                parsedParams = (allParsedParams.filterValues { it != null }.mapValues { it.value!! } as Map<String, Any>) + mapOf("merged" to true, "handlerCount" to handlers.count { it.match(input) })
            )
            Log.d(tag, "✅ [合并结果] intent=$finalIntent, codes=$distinctCodes, names=$distinctNames, confidence=$bestConfidence")
            Log.d(tag, "═══════════════════════════════════════")
            return result
        }

        // 未能识别，返回 UNKNOWN
        Log.d(tag, "✗ No handler matched, returning UNKNOWN")
        Log.d(tag, "  ⚠️ 用户输入未匹配任何股票意图模式")
        Log.d(tag, "  ⚠️ AI 将只收到系统提示词，不含实时行情数据")
        Log.d(tag, "═══════════════════════════════════════")
        return IntentResult(
            intent = StockIntent.UNKNOWN,
            stockCodes = emptyList(),
            stockNames = emptyList(),
            confidence = 0.0f,
            rawQuery = input
        )
    }

    /** 意图优先级：COMPARE_STOCKS > TECHNICAL_ANALYSIS > QUERY_PRICE > QUERY_INDEX > QUERY_SECTOR > QUERY_FINANCIALS > QUERY_HISTORY > QUERY_HOT_STOCKS > UNKNOWN */
    private fun intentPriority(intent: StockIntent): Int = when (intent) {
        StockIntent.COMPARE_STOCKS -> 8
        StockIntent.TECHNICAL_ANALYSIS -> 7
        StockIntent.QUERY_PRICE -> 6
        StockIntent.QUERY_INDEX -> 5
        StockIntent.QUERY_SECTOR -> 4
        StockIntent.QUERY_FINANCIALS -> 3
        StockIntent.QUERY_HISTORY -> 2
        StockIntent.QUERY_HOT_STOCKS -> 1
        StockIntent.UNKNOWN -> 0
    }

    /**
     * 挂起版本的处理方法 - 支持 AI 兜底意图解析
     *
     * 适用于：需要 AI 解析复杂意图的场景
     * 1. 先让普通 Handler 匹配
     * 2. 如果匹配且置信度 >= 阈值，直接返回
     * 3. 如果所有 Handler 都不匹配或置信度低，使用 AI 兜底
     *
     * @param input 用户输入
     * @return IntentResult
     */
    suspend fun processSuspend(input: String): IntentResult {
        Log.d(tag, "═══════════════════════════════════════")
        Log.d(tag, "🔍 IntentProcessorChain.processSuspend start")
        Log.d(tag, "  输入: '${input.take(60)}'")
        Log.d(tag, "  handlers: ${handlers.map { it::class.simpleName }}")
        Log.d(tag, "═══════════════════════════════════════")

        if (input.isBlank()) {
            Log.w(tag, "  ✗ input is blank")
            Log.d(tag, "═══════════════════════════════════════")
            return IntentResult(
                intent = StockIntent.UNKNOWN,
                stockCodes = emptyList(),
                stockNames = emptyList(),
                confidence = 0.0f,
                rawQuery = input
            )
        }

        // 1. 先让普通 Handler 匹配
        for (handler in handlers) {
            val handlerName = handler::class.simpleName ?: "Unknown"
            val matched = handler.match(input)
            Log.d(tag, "  ➡ $handlerName.match → $matched")

            if (matched) {
                val result = handler.parse(input)
                Log.d(tag, "  ➡ $handlerName.parse() → intent=${result.intent}, codes=${result.stockCodes}, confidence=${result.confidence}")

                if (result.confidence >= AI_FALLBACK_THRESHOLD) {
                    Log.d(tag, "  ✓ $handlerName: accept (confidence=${result.confidence})")
                    Log.d(tag, "═══════════════════════════════════════")
                    return result
                }
                // 置信度低，如果这是最后一个非AI Handler，继续尝试其他
                if (handler !is AiIntentHandler) {
                    Log.d(tag, "  ${handlerName}: confidence=${result.confidence}, trying AI...")
                }
            }
        }

        // 2. 尝试 AI 兜底解析
        if (aiAnalyzer != null) {
            Log.d(tag, "🤖 Falling back to AI intent analysis...")
            val aiResult = aiAnalyzer.analyzeIntent(input)
            Log.d(tag, "🤖 AI result → intent=${aiResult.intent}, codes=${aiResult.stockCodes}, confidence=${aiResult.confidence}")
            if (aiResult.confidence >= AI_FALLBACK_THRESHOLD) {
                Log.d(tag, "✓ AI: intent=${aiResult.intent}, confidence=${aiResult.confidence}")
                Log.d(tag, "═══════════════════════════════════════")
                return aiResult
            }
            Log.d(tag, "✗ AI: confidence=${aiResult.confidence}, still low")
        } else {
            Log.d(tag, "  ⚠️ aiAnalyzer is null, skipping AI fallback")
            Log.d(tag, "  ⚠️ 如需 AI 兜底分析，需在 ChatActivity 中传入 AiStockAnalyzer")
        }

        // 3. 真的无法识别
        Log.d(tag, "✗ All handlers + AI failed, returning UNKNOWN")
        Log.d(tag, "  ⚠️ 用户输入 '${input.take(50)}' 未匹配任何 Handler，且无 AI 兜底")
        Log.d(tag, "  ⚠️ AI 将只收到系统提示词，不含实时行情数据")
        Log.d(tag, "═══════════════════════════════════════")
        return IntentResult(
            intent = StockIntent.UNKNOWN,
            stockCodes = emptyList(),
            stockNames = emptyList(),
            confidence = 0.0f,
            rawQuery = input
        )
    }
}