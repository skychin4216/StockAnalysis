package com.chin.stockanalysis.testdata

import com.chin.stockanalysis.stock.intent.StockIntent

/**
 * 自动化测试 Prompt 数据集
 *
 * 每个测试用例包含：
 * - id: 唯一标识
 * - prompt: 用户输入的测试文本
 * - expectedIntent: 期望的意图类型
 * - expectedMinCodes: 期望至少包含的股票代码数
 * - expectedCodes: 期望精确匹配的股票代码（部分匹配即可）
 * - description: 测试说明
 */
data class TestPrompt(
    val id: String,
    val prompt: String,
    val expectedIntent: StockIntent,
    val expectedMinCodes: Int = 1,
    val expectedCodes: List<String> = emptyList(),
    val expectedMinConfidence: Float = 0.7f,
    val description: String = ""
) {
    companion object {
        /**
         * 所有测试用例
         */
        fun all(): List<TestPrompt> = listOf(
            // ── 单股票查询 ──
            TestPrompt(
                id = "T1",
                prompt = "贵州茅台最新股价",
                expectedIntent = StockIntent.QUERY_PRICE,
                expectedMinCodes = 1,
                expectedCodes = listOf("sh600519"),
                description = "单股票硬编码匹配：贵州茅台 → sh600519"
            ),
            TestPrompt(
                id = "T2",
                prompt = "生益科技最新股价",
                expectedIntent = StockIntent.QUERY_PRICE,
                expectedMinCodes = 1,
                expectedCodes = listOf("sh600183"),
                description = "单股票硬编码匹配：生益科技 → sh600183"
            ),
            TestPrompt(
                id = "T3",
                prompt = "600519价格",
                expectedIntent = StockIntent.QUERY_PRICE,
                expectedMinCodes = 1,
                expectedCodes = listOf("sh600519"),
                expectedMinConfidence = 0.9f,
                description = "股票代码直接查询：600519 → sh600519"
            ),
            TestPrompt(
                id = "T4",
                prompt = "宁德时代股价",
                expectedIntent = StockIntent.QUERY_PRICE,
                expectedMinCodes = 1,
                expectedCodes = listOf("sz300750"),
                description = "创业板股票匹配：宁德时代 → sz300750"
            ),

            // ── 多股票查询 ──
            TestPrompt(
                id = "T5",
                prompt = "生益科技和沪电股份，还能追涨么",
                expectedIntent = StockIntent.QUERY_PRICE,
                expectedMinCodes = 2,
                expectedCodes = listOf("sh600183", "sz002463"),
                description = "多股票查询（连接词'和'拆分）：生益科技 + 沪电股份"
            ),
            TestPrompt(
                id = "T6",
                prompt = "宁德时代和比亚迪对比",
                expectedIntent = StockIntent.COMPARE_STOCKS,
                expectedMinCodes = 2,
                expectedCodes = listOf("sz300750", "sz002594"),
                description = "股票对比意图：'对比'关键词触发 COMPARE_STOCKS"
            ),
            TestPrompt(
                id = "T7",
                prompt = "茅台跟五粮液哪个好",
                expectedIntent = StockIntent.COMPARE_STOCKS,
                expectedMinCodes = 2,
                expectedCodes = listOf("sh600519", "sz000858"),
                description = "多股票对比（'哪个'触发对比）：茅台 vs 五粮液"
            ),

            // ── 指数查询 ──
            TestPrompt(
                id = "T8",
                prompt = "上证指数",
                expectedIntent = StockIntent.QUERY_INDEX,
                expectedMinCodes = 1,
                description = "指数查询：上证指数"
            ),

            // ── 技术分析 ──
            TestPrompt(
                id = "T9",
                prompt = "帮我分析茅台",
                expectedIntent = StockIntent.TECHNICAL_ANALYSIS,
                expectedMinCodes = 1,
                expectedCodes = listOf("sh600519"),
                description = "技术分析意图：'分析' + '茅台'触发 TECHNICAL_ANALYSIS"
            ),
            TestPrompt(
                id = "T10",
                prompt = "宁德时代MACD和K线技术面",
                expectedIntent = StockIntent.TECHNICAL_ANALYSIS,
                expectedMinCodes = 1,
                expectedCodes = listOf("sz300750"),
                description = "技术分析+技术指标关键词：MACD, K线"
            ),

            // ── 非股票查询 ──
            TestPrompt(
                id = "T11",
                prompt = "今天天气怎么样",
                expectedIntent = StockIntent.UNKNOWN,
                expectedMinCodes = 0,
                expectedMinConfidence = 0.0f,
                description = "非股票意图：天气查询，应返回 UNKNOWN"
            ),
            TestPrompt(
                id = "T12",
                prompt = "推荐几只股票",
                expectedIntent = StockIntent.UNKNOWN,
                expectedMinCodes = 0,
                description = "泛股票推荐（无具体股票名），GeneralStockHandler可能低置信度匹配"
            ),

            // ── 边界测试 ──
            TestPrompt(
                id = "T13",
                prompt = "",
                expectedIntent = StockIntent.UNKNOWN,
                expectedMinCodes = 0,
                expectedMinConfidence = 0.0f,
                description = "空输入：应直接返回 UNKNOWN"
            ),
            TestPrompt(
                id = "T14",
                prompt = "sh600519 sz002594",
                expectedIntent = StockIntent.QUERY_PRICE,
                expectedMinCodes = 2,
                expectedCodes = listOf("sh600519", "sz002594"),
                expectedMinConfidence = 0.85f,
                description = "多股票代码批量查询：sh前缀 + sz前缀"
            ),

            // ── 口语化多股票 ──
            TestPrompt(
                id = "T15",
                prompt = "韦尔股份跟汇顶科技最近走势",
                expectedIntent = StockIntent.TECHNICAL_ANALYSIS,
                expectedMinCodes = 2,
                expectedCodes = listOf("sh603501", "sh603160"),
                description = "口语化多股票+技术分析：'走势'触发 TECHNICAL_ANALYSIS"
            ),
            TestPrompt(
                id = "T16",
                prompt = "广联达、用友网络、恒生电子这三只怎么看",
                expectedIntent = StockIntent.QUERY_PRICE,
                expectedMinCodes = 3,
                expectedCodes = listOf("sz002410", "sh600588", "sh600570"),
                description = "三只股票同时查询（顿号分隔）"
            )
        )
    }
}