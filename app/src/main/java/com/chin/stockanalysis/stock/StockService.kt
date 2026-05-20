package com.chin.stockanalysis.stock

import android.util.Log
import com.chin.stockanalysis.stock.data.StockRepository
import com.chin.stockanalysis.stock.formatter.StockDataFormatter
import com.chin.stockanalysis.stock.intent.IntentProcessorChain
import com.chin.stockanalysis.stock.intent.StockIntent
import com.chin.stockanalysis.stock.realtime.ProcessedResult
import com.chin.stockanalysis.stock.realtime.RealtimeConfig
import com.chin.stockanalysis.stock.realtime.RealtimeDataProcessor
import com.chin.stockanalysis.stock.realtime.RealtimeDataAccessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 股票服务门面 - 核心业务逻辑
 *
 * 职责：
 * 1. 意图识别（使用 IntentProcessorChain）
 * 2. 获取数据（使用 StockRepository）
 * 3. 格式化数据（使用 StockDataFormatter）
 * 4. 返回上下文（用于注入 AI prompt）
 */
class StockService(
    private val intentProcessor: IntentProcessorChain = IntentProcessorChain(),
    private val repository: StockRepository,
    private val dataFormatter: StockDataFormatter = StockDataFormatter()
) {
    private val tag = "StockService"

    // ======================== 原始兼容方法 ========================

    /**
     * 处理用户输入，返回要注入到 prompt 的数据
     *
     * @param userMessage 用户输入文本
     * @return StockContext 包含意图和格式化数据
     */
    fun processUserInput(userMessage: String): StockContext {
        Log.d(tag, "═══════════════════════════════════════")
        Log.d(tag, "📥 StockService.processUserInput 开始")
        Log.d(tag, "  用户输入: '${userMessage.take(80)}...'")

        // 1. 意图识别
        val intent = intentProcessor.process(userMessage)
        Log.d(tag, "  ➡ IntentProcessor 结果:")
        Log.d(tag, "    intent: ${intent.intent}")
        Log.d(tag, "    codes: ${intent.stockCodes}")
        Log.d(tag, "    names: ${intent.stockNames}")
        Log.d(tag, "    confidence: ${intent.confidence}")

        // 2. 根据意图获取数据
        val data = when (intent.intent) {
            StockIntent.QUERY_PRICE,
            StockIntent.QUERY_INDEX,
            StockIntent.TECHNICAL_ANALYSIS,
            StockIntent.COMPARE_STOCKS -> {
                val codes = intent.stockCodes
                Log.d(tag, "  ➡ 准备获取数据: codes=$codes")
                val startTime = System.currentTimeMillis()
                val result = repository.getRealtime(codes)
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(tag, "  ➡ StockRepository.getRealtime (${elapsed}ms): ${result.size}/${codes.size} stocks")
                result
            }
            else -> {
                Log.d(tag, "  ➡ 意图 ${intent.intent} 不需要数据获取")
                emptyMap()
            }
        }

        // 3. 格式化输出
        val formattedData = dataFormatter.format(intent, data)
        Log.d(tag, "  ➡ StockDataFormatter.format: ${formattedData.length} chars")
        Log.d(tag, "  ➡ 前100字: ${formattedData.take(100)}")

        // 4. 返回上下文
        val result = StockContext(
            intent = intent,
            hasStockData = data.isNotEmpty(),
            promptPrefix = formattedData
        )
        Log.d(tag, "📤 StockService.processUserInput 结束: hasStockData=${result.hasStockData}")
        Log.d(tag, "═══════════════════════════════════════")
        return result
    }

    // ======================== 实时数据增强方法 ========================

    /**
     * 使用 [RealtimeDataProcessor] 获取处理后的实时数据，
     * 然后格式化为 AI prompt 文本。
     *
     * 相比 [processUserInput]，此方法：
     * - 使用协程（suspend）
     * - 并发请求多个数据源，取最快返回
     * - 自动验证数据合理性
     * - 感知交易时段
     * - 支持强制刷新缓存
     *
     * @param userMessage 用户输入文本
     * @param processor [RealtimeDataProcessor] 实例，可通过 [RealtimeConfig.createProcessor] 创建
     * @param forceRefresh 是否强制刷新缓存，默认 false
     * @return StockContext 包含意图和处理后的数据
     */
    suspend fun processUserInputRealtime(
        userMessage: String,
        processor: RealtimeDataProcessor,
        forceRefresh: Boolean = false
    ): StockContext {
        // 1. 意图识别
        val intent = intentProcessor.process(userMessage)

        // 2. 根据意图获取实时数据
        val stockCodes = intent.stockCodes
        var formattedData = ""

        if (stockCodes.isNotEmpty()) {
            val result = processor.getProcessedRealtime(stockCodes, forceRefresh)

            // 3. 使用 Processor 的格式化方法（更智能）
            formattedData = when (intent.intent) {
                StockIntent.QUERY_PRICE -> processor.formatForAi(result)
                StockIntent.QUERY_INDEX -> processor.formatForAi(result)
                StockIntent.TECHNICAL_ANALYSIS -> formatTechnicalWithRealtime(result, intent)
                StockIntent.COMPARE_STOCKS -> formatCompareWithRealtime(result, intent)
                else -> ""
            }
        }

        // 4. 返回上下文
        return StockContext(
            intent = intent,
            hasStockData = formattedData.isNotEmpty(),
            promptPrefix = formattedData
        )
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        repository.clearCache()
    }

    // ======================== 内部辅助 ========================

    /**
     * 技术分析：使用实时数据 + 分析类型提示
     */
    private fun formatTechnicalWithRealtime(result: ProcessedResult, intent: com.chin.stockanalysis.stock.intent.IntentResult): String {
        val base = formatProcessorResult(result)
        if (base.isBlank()) return ""

        val analysisType = intent.parsedParams["analysis_type"]?.toString().orEmpty().ifBlank { "general" }
        return buildString {
            appendLine("【股票技术分析上下文】")
            appendLine("用户原问题：${intent.rawQuery}")
            appendLine("分析类型：$analysisType")
            appendLine("请基于以下实时行情，结合常见技术分析框架（趋势、均线、MACD、量价关系、支撑/压力）给出专业但不构成投资建议的分析。")
            appendLine()
            append(base)
            appendLine("输出要求：")
            appendLine("1. 先总结当前价格和涨跌幅；")
            appendLine("2. 再分析趋势/量价/风险；")
            appendLine("3. 最后给出风险提示，不要直接喊买卖。")
        }
    }

    /**
     * 对比分析：使用实时数据
     */
    private fun formatCompareWithRealtime(result: ProcessedResult, intent: com.chin.stockanalysis.stock.intent.IntentResult): String {
        val base = formatProcessorResult(result)
        if (base.isBlank()) return ""

        return buildString {
            appendLine("【股票对比分析上下文】")
            appendLine("用户原问题：${intent.rawQuery}")
            appendLine("请基于以下多只股票实时行情，从涨跌幅、成交活跃度、价格位置、短线风险等维度进行横向对比。")
            appendLine()
            append(base)
            appendLine("输出要求：")
            appendLine("1. 用表格或分点对比；")
            appendLine("2. 强调数据时效性和风险；")
            appendLine("3. 不给具体买卖指令，仅提供分析参考。")
        }
    }

    /**
     * 将 ProcessedResult 转为 AI 提示文本
     */
    private fun formatProcessorResult(result: ProcessedResult): String {
        return if (result is com.chin.stockanalysis.stock.realtime.ProcessedResult.Data) {
            // 手动格式化，不依赖 formatter 包
            val sb = StringBuilder()
            for ((code, stock) in result.data) {
                sb.appendLine("• ${stock.name} ($code): 当前 ${stock.price}元, 涨跌 ${stock.changeAmount} (${stock.changePercent}%)")
            }
            sb.toString()
        } else ""
    }
}

