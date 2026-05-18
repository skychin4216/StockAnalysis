package com.chin.stockanalysis.stock

import com.chin.stockanalysis.stock.data.StockRepository
import com.chin.stockanalysis.stock.formatter.StockDataFormatter
import com.chin.stockanalysis.stock.intent.IntentProcessorChain
import com.chin.stockanalysis.stock.intent.StockIntent

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

    /**
     * 处理用户输入，返回要注入到 prompt 的数据
     *
     * @param userMessage 用户输入文本
     * @return StockContext 包含意图和格式化数据
     */
    fun processUserInput(userMessage: String): StockContext {
        // 1. 意图识别
        val intent = intentProcessor.process(userMessage)

        // 2. 根据意图获取数据
        val data = when (intent.intent) {
            StockIntent.QUERY_PRICE,
            StockIntent.QUERY_INDEX,
            StockIntent.TECHNICAL_ANALYSIS,
            StockIntent.COMPARE_STOCKS -> {
                repository.getRealtime(intent.stockCodes)
            }
            else -> emptyMap()
        }

        // 3. 格式化输出
        val formattedData = dataFormatter.format(intent, data)

        // 4. 返回上下文
        return StockContext(
            intent = intent,
            hasStockData = data.isNotEmpty(),
            promptPrefix = formattedData
        )
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        repository.clearCache()
    }
}

