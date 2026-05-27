package com.chin.stockanalysis

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chin.stockanalysis.stock.StockService
import com.chin.stockanalysis.stock.data.MultiSourceStockRepository
import com.chin.stockanalysis.stock.data.StockDataSourceFactory
import com.chin.stockanalysis.stock.formatter.StockDataFormatter
import com.chin.stockanalysis.stock.intent.IntentProcessorChain
import com.chin.stockanalysis.stock.intent.StockIntent
import com.chin.stockanalysis.testdata.PriceAssertions
import com.chin.stockanalysis.testdata.TestPrompt
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ## StockAnalysis 自动化测试套件
 *
 * 测试覆盖：
 * 1. 意图识别准确性（IntentProcessorChain）
 * 2. 股票代码匹配准确度
 * 3. 实时价格数据合理性
 * 4. 多股票查询正确性（回归测试需求1）
 *
 * ### 运行方式
 * ```
 * # 命令行（需要连接设备/模拟器）
 * ./gradlew connectedAndroidTest --tests "com.chin.stockanalysis.StockAnalysisTest"
 *
 * # Android Studio
 * 右键 StockAnalysisTest → Run 'StockAnalysisTest'
 * ```
 *
 * ### 注意事项
 * - 测试依赖网络（需要访问东方财富/Sina/Tencent API）
 * - 非交易日/非交易时段，价格数据可能不更新（为正常行为）
 * - 测试 T5（多股票查询）和 T16（三股票查询）依赖需求1修复
 */
@RunWith(AndroidJUnit4::class)
class StockAnalysisTest {

    companion object {
        private const val TAG = "StockAnalysisTest"
    }

    private lateinit var context: Context
    private lateinit var processor: IntentProcessorChain
    private lateinit var repository: MultiSourceStockRepository
    private lateinit var stockService: StockService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        processor = IntentProcessorChain()
        repository = StockDataSourceFactory.createDefaultRepository(context)
        stockService = StockService(
            intentProcessor = processor,
            repository = repository,
            dataFormatter = StockDataFormatter()
        )
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "🚀 StockAnalysisTest 启动")
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    @After
    fun tearDown() {
        repository.clearCache()
        Log.i(TAG, "✅ StockAnalysisTest 完成")
    }

    // ══════════════════════════════════════════════
    // 测试1: 意图识别准确性
    // ══════════════════════════════════════════════

    @Test
    fun testAllPrompts_intentRecognition() {
        Log.i(TAG, "--- 意图识别测试 ---")
        val prompts = TestPrompt.all()

        var passed = 0
        var failed = 0

        for (prompt in prompts) {
            val result = processor.process(prompt.prompt)

            // 检查意图
            val intentOk = result.intent == prompt.expectedIntent
            // 检查代码数量
            val codesOk = result.stockCodes.size >= prompt.expectedMinCodes
            // 检查置信度
            val confidenceOk = result.confidence >= prompt.expectedMinConfidence
            // 检查期望代码匹配
            val codeMatchOk = if (prompt.expectedCodes.isNotEmpty()) {
                prompt.expectedCodes.any { expected ->
                    result.stockCodes.any { actual -> actual.contains(expected) || expected.contains(actual) }
                }
            } else true

            if (intentOk && codesOk && confidenceOk && codeMatchOk) {
                passed++
                Log.d(TAG, "✅ ${prompt.id}: ${prompt.description}")
                Log.v(TAG, "   intent=${result.intent}, codes=${result.stockCodes}, confidence=${result.confidence}")
            } else {
                failed++
                Log.e(TAG, "❌ ${prompt.id}: ${prompt.description}")
                Log.e(TAG, "   输入: '${prompt.prompt.take(40)}'")
                Log.e(TAG, "   期望: intent=${prompt.expectedIntent}, minCodes=${prompt.expectedMinCodes}, codes=${prompt.expectedCodes}, minConf=${prompt.expectedMinConfidence}")
                Log.e(TAG, "   实际: intent=${result.intent}, codes=${result.stockCodes}, conf=${result.confidence}")

                // 按优先级断言
                assertEquals("${prompt.id}: 意图不匹配", prompt.expectedIntent, result.intent)
                assertTrue(
                    "${prompt.id}: 代码数量不足 (${result.stockCodes.size} < ${prompt.expectedMinCodes})",
                    codesOk
                )
                assertTrue(
                    "${prompt.id}: 置信度过低 (${result.confidence} < ${prompt.expectedMinConfidence})",
                    confidenceOk
                )
            }
        }

        Log.i(TAG, "意图识别测试结果: ✅$passed ❌$failed / ${prompts.size}")
        if (failed > 0) {
            fail("$failed 个测试用例失败（共 ${prompts.size} 个）")
        }
    }

    // ══════════════════════════════════════════════
    // 测试2: 单股票价格查询准确度
    // ══════════════════════════════════════════════

    @Test
    fun testSingleStockPriceAccuracy() {
        Log.i(TAG, "--- 单股票价格准确度测试 ---")

        // 查询贵州茅台
        val result = stockService.processUserInput("贵州茅台最新股价")
        assertTrue("应识别到股票数据", result.hasStockData)
        assertNotNull("应包含股票代码", result.intent.stockCodes.find { it == "sh600519" })

        Log.i(TAG, "✅ 贵州茅台价格查询: hasData=${result.hasStockData}")
        Log.d(TAG, "   promptPrefix 前 200 字: ${result.promptPrefix.take(200)}")
    }

    // ══════════════════════════════════════════════
    // 测试3: 多股票查询（回归：需求1修复）
    // ══════════════════════════════════════════════

    @Test
    fun testMultiStockQuery_regression() {
        Log.i(TAG, "--- 多股票查询回归测试 ---")

        // 确保硬编码表中包含生益科技和沪电股份
        val result = stockService.processUserInput("生益科技和沪电股份，还能追涨么")

        // 注意：由于 StockNameHandler 在 IntentProcessorChain 中排在 FuzzyStockNameHandler 之前，
        // 如果硬编码表中已添加生益科技/沪电股份，则 StockNameHandler 会命中。
        // 如果硬编码表没有，则 FuzzyStockNameHandler v2.0 的多股票拆分逻辑会处理。

        assertTrue("应识别到多支股票", result.intent.stockCodes.size >= 2)

        val expectedCodes = listOf("sh600183", "sz002463")
        val foundCodes = result.intent.stockCodes.filter { code ->
            expectedCodes.any { expected -> code.contains(expected) }
        }

        assertTrue(
            "应包含生益科技(sh600183)和沪电股份(sz002463)，实际: ${result.intent.stockCodes}",
            foundCodes.size >= 2
        )

        assertTrue("应有实时数据", result.hasStockData)
        assertTrue("promptPrefix 不应为空", result.promptPrefix.isNotBlank())

        Log.i(TAG, "✅ 多股票查询: codes=${result.intent.stockCodes}, names=${result.intent.stockNames}")
        Log.d(TAG, "   promptPrefix 前 300 字: ${result.promptPrefix.take(300)}")
    }

    // ══════════════════════════════════════════════
    // 测试4: 实时价格数据合理性
    // ══════════════════════════════════════════════

    @Test
    fun testPriceDataValidity() {
        Log.i(TAG, "--- 价格数据合理性测试 ---")

        // 获取多只股票的价格，验证数据合理
        val codes = listOf("sh600519", "sz002594", "sh601318")
        val data = repository.getRealtime(codes)

        // 至少有一只能获取到数据
        assertTrue("至少应获取到一只股票数据", data.isNotEmpty())

        // 验证每只股票数据合理
        PriceAssertions.assertAllValid(data)

        Log.i(TAG, "✅ 价格数据合理性: 获取到 ${data.size}/${codes.size} 只股票")
        for ((code, stock) in data) {
            Log.d(TAG, "   $code: ${stock.name} ¥${stock.price} (${stock.changePercent}%)")
        }
    }

    // ══════════════════════════════════════════════
    // 测试5: 代码查询
    // ══════════════════════════════════════════════

    @Test
    fun testStockCodeQuery() {
        Log.i(TAG, "--- 代码查询测试 ---")

        val result = stockService.processUserInput("600519价格")
        assertEquals("意图应为 QUERY_PRICE", StockIntent.QUERY_PRICE, result.intent)
        assertTrue("应包含 sh600519", result.intent.stockCodes.contains("sh600519"))
        assertTrue("置信度应 > 0.9", result.intent.confidence >= 0.9f)

        Log.i(TAG, "✅ 代码查询: ${result.intent.stockCodes}, confidence=${result.intent.confidence}")
    }

    // ══════════════════════════════════════════════
    // 测试6: 指数查询
    // ══════════════════════════════════════════════

    @Test
    fun testIndexQuery() {
        Log.i(TAG, "--- 指数查询测试 ---")

        val result = stockService.processUserInput("上证指数")
        assertEquals("意图应为 QUERY_INDEX", StockIntent.QUERY_INDEX, result.intent)
        assertTrue("应至少识别到一个指数代码", result.intent.stockCodes.isNotEmpty())

        Log.i(TAG, "✅ 指数查询: codes=${result.intent.stockCodes}")
    }

    // ══════════════════════════════════════════════
    // 测试7: 非股票查询
    // ══════════════════════════════════════════════

    @Test
    fun testNonStockQuery() {
        Log.i(TAG, "--- 非股票查询测试 ---")

        val result = stockService.processUserInput("今天天气怎么样")
        assertEquals("意图应为 UNKNOWN", StockIntent.UNKNOWN, result.intent)
        assertTrue("股票代码应为空", result.intent.stockCodes.isEmpty())
        assertFalse("不应有股票数据", result.hasStockData)

        Log.i(TAG, "✅ 非股票查询: 正确识别为 UNKNOWN")
    }

    // ══════════════════════════════════════════════
    // 测试8: 空输入
    // ══════════════════════════════════════════════

    @Test
    fun testEmptyInput() {
        Log.i(TAG, "--- 空输入测试 ---")

        val result = stockService.processUserInput("")
        assertEquals("意图应为 UNKNOWN", StockIntent.UNKNOWN, result.intent)
        assertFalse("不应有股票数据", result.hasStockData)

        Log.i(TAG, "✅ 空输入: 正确返回 UNKNOWN")
    }
}