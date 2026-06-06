package com.chin.stockanalysis

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiKeysLoader
import com.chin.stockanalysis.stock.StockService
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
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
 * 5. API Key 连通性
 *
 * ### 运行方式
 * ```
 * # 命令行（需要连接设备/模拟器）
 * ./gradlew connectedAndroidTest --tests "com.chin.stockanalysis.StockAnalysisTest"
 *
 * # Android Studio
 * 右键 StockAnalysisTest → Run 'StockAnalysisTest'
 * ```
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

            val intentOk = result.intent == prompt.expectedIntent
            val codesOk = result.stockCodes.size >= prompt.expectedMinCodes
            val confidenceOk = result.confidence >= prompt.expectedMinConfidence
            val codeMatchOk = if (prompt.expectedCodes.isNotEmpty()) {
                prompt.expectedCodes.any { expected ->
                    result.stockCodes.any { actual -> actual.contains(expected) || expected.contains(actual) }
                }
            } else true

            if (intentOk && codesOk && confidenceOk && codeMatchOk) {
                passed++
                Log.d(TAG, "✅ ${prompt.id}: ${prompt.description}")
            } else {
                failed++
                Log.e(TAG, "❌ ${prompt.id}: ${prompt.description}")
                Log.e(TAG, "   期望: intent=${prompt.expectedIntent}")
                Log.e(TAG, "   实际: intent=${result.intent}, codes=${result.stockCodes}, conf=${result.confidence}")
                assertEquals("${prompt.id}: 意图不匹配", prompt.expectedIntent, result.intent)
                assertTrue("${prompt.id}: 代码数量不足", codesOk)
                assertTrue("${prompt.id}: 置信度过低", confidenceOk)
            }
        }

        Log.i(TAG, "意图识别测试结果: ✅$passed ❌$failed / ${prompts.size}")
        if (failed > 0) fail("$failed 个测试用例失败（共 ${prompts.size} 个）")
    }

    @Test
    fun testSingleStockPriceAccuracy() {
        Log.i(TAG, "--- 单股票价格准确度测试 ---")
        val result = stockService.processUserInput("贵州茅台最新股价")
        assertTrue("应识别到股票数据", result.hasStockData)
        assertNotNull("应包含股票代码", result.intent.stockCodes.find { it == "sh600519" })
        Log.i(TAG, "✅ 贵州茅台价格查询: hasData=${result.hasStockData}")
    }

    @Test
    fun testMultiStockQuery_regression() {
        Log.i(TAG, "--- 多股票查询回归测试 ---")
        val result = stockService.processUserInput("生益科技和沪电股份，还能追涨么")
        assertTrue("应识别到多支股票", result.intent.stockCodes.size >= 2)
        val expectedCodes = listOf("sh600183", "sz002463")
        val foundCodes = result.intent.stockCodes.filter { code ->
            expectedCodes.any { expected -> code.contains(expected) }
        }
        assertTrue("应包含生益科技和沪电股份，实际: ${result.intent.stockCodes}", foundCodes.size >= 2)
        assertTrue("应有实时数据", result.hasStockData)
        Log.i(TAG, "✅ 多股票查询: codes=${result.intent.stockCodes}")
    }

    @Test
    fun testPriceDataValidity() {
        Log.i(TAG, "--- 价格数据合理性测试 ---")
        val codes = listOf("sh600519", "sz002594", "sh601318")
        val data = repository.getRealtime(codes)
        assertTrue("至少应获取到一只股票数据", data.isNotEmpty())
        PriceAssertions.assertAllValid(data)
        Log.i(TAG, "✅ 价格数据合理性: 获取到 ${data.size}/${codes.size} 只股票")
    }

    @Test
    fun testStockCodeQuery() {
        Log.i(TAG, "--- 代码查询测试 ---")
        val result = stockService.processUserInput("600519价格")
        assertEquals("意图应为 QUERY_PRICE", StockIntent.QUERY_PRICE, result.intent)
        assertTrue("应包含 sh600519", result.intent.stockCodes.contains("sh600519"))
        Log.i(TAG, "✅ 代码查询: ${result.intent.stockCodes}")
    }

    @Test
    fun testIndexQuery() {
        Log.i(TAG, "--- 指数查询测试 ---")
        val result = stockService.processUserInput("上证指数")
        assertEquals("意图应为 QUERY_INDEX", StockIntent.QUERY_INDEX, result.intent)
        assertTrue("应至少识别到一个指数代码", result.intent.stockCodes.isNotEmpty())
        Log.i(TAG, "✅ 指数查询: codes=${result.intent.stockCodes}")
    }

    @Test
    fun testNonStockQuery() {
        Log.i(TAG, "--- 非股票查询测试 ---")
        val result = stockService.processUserInput("今天天气怎么样")
        assertEquals("意图应为 UNKNOWN", StockIntent.UNKNOWN, result.intent)
        assertTrue("股票代码应为空", result.intent.stockCodes.isEmpty())
        Log.i(TAG, "✅ 非股票查询: 正确识别为 UNKNOWN")
    }

    @Test
    fun testEmptyInput() {
        Log.i(TAG, "--- 空输入测试 ---")
        val result = stockService.processUserInput("")
        assertEquals("意图应为 UNKNOWN", StockIntent.UNKNOWN, result.intent)
        assertFalse("不应有股票数据", result.hasStockData)
        Log.i(TAG, "✅ 空输入: 正确返回 UNKNOWN")
    }

    // ══════════════════════════════════════════════
    // 测试9: API Key 连通性测试
    // ══════════════════════════════════════════════

    @Test
    fun testApiKeyConnectivity() {
        Log.i(TAG, "━━━ API Key 连通性测试 ━━━")

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val jsonMedia = "application/json; charset=utf-8".toMediaType()

        val cfgMgr = ApiConfigManager.getInstance(context)
        val doubaoCfg = cfgMgr.getProviderConfig("doubao")
        val dsCfg = cfgMgr.getProviderConfig("deepseek-official")

        data class TestEntry(
            val label: String,
            val baseUrl: String,
            val model: String,
            val key: String,
            val required: Boolean = false
        )

        // 豆包所有模型（来自 supportedModels）
        val doubaoModels = listOf(
            "doubao-seed-2-0-pro-260215" to "Seed 2.0 Pro",
            "doubao-seed-2-0-code-preview-260215" to "Seed 2.0 Code",
            "doubao-seed-2-0-mini-260428" to "Seed 2.0 Mini",
            "doubao-seed-2-0-lite-260428" to "Seed 2.0 Lite",
            "doubao-seed-1-8-251228" to "Seed 1.8",
            "doubao-seed-1-6-251015" to "Seed 1.6",
            "doubao-1-5-pro-32k-250115" to "1.5 Pro 32k"
        )

        val doubaoUrl = doubaoCfg?.baseUrl?.trimEnd('/') ?: "https://ark.cn-beijing.volces.com/api/v3"
        val doubaoKey = doubaoCfg?.apiKey?.takeIf { it.isNotBlank() }
            ?: ApiKeysLoader.get(ApiKeysLoader.KEY_DOUBAO)

        val tests = mutableListOf<TestEntry>()

        // 豆包所有模型测试
        if (doubaoKey.isNotBlank()) {
            for ((model, label) in doubaoModels) {
                tests.add(TestEntry(
                    label = "豆包 $label",
                    baseUrl = doubaoUrl,
                    model = model,
                    key = doubaoKey,
                    required = true
                ))
            }
        } else {
            tests.add(TestEntry(
                label = "豆包/方舟", baseUrl = doubaoUrl,
                model = doubaoCfg?.model ?: "doubao-seed-2-0-pro-260215",
                key = "", required = true
            ))
        }

        // DeepSeek
        tests.add(TestEntry(
            label = "DeepSeek 官方",
            baseUrl = dsCfg?.baseUrl ?: "https://api.deepseek.com/v1/",
            model = dsCfg?.model ?: "deepseek-chat",
            key = dsCfg?.apiKey?.takeIf { it.isNotBlank() }
                ?: ApiKeysLoader.get(ApiKeysLoader.KEY_DEEPSEEK),
            required = false
        ))

        // SiliconFlow
        tests.add(TestEntry(
            label = "硅基流动 DeepSeek V3",
            baseUrl = "https://api.siliconflow.cn/v1/",
            model = "Pro/deepseek-ai/DeepSeek-V3",
            key = ApiKeysLoader.get(ApiKeysLoader.KEY_SILICONFLOW),
            required = false
        ))

        var passed = 0
        var failed = 0
        var skipped = 0
        val failures = mutableListOf<String>()

        for (entry in tests) {
            if (entry.key.isBlank()) {
                skipped++
                Log.w(TAG, "⏭️ ${entry.label}: 无 API Key，跳过")
                continue
            }

            val url = entry.baseUrl.trimEnd('/') + "/chat/completions"
            val requestBody = JSONObject().apply {
                put("model", entry.model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user"); put("content", "回复OK即可")
                    })
                })
                put("max_tokens", 10); put("stream", false)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${entry.key}")
                .post(requestBody.toRequestBody(jsonMedia))
                .build()

            try {
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: ""
                    response.close()
                    val content = try {
                        JSONObject(respBody).getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").optString("content", "")
                    } catch (_: Exception) { "" }
                    passed++
                    Log.i(TAG, "✅ ${entry.label} (${entry.model}): ${content.take(30)}")
                } else {
                    val code = response.code
                    val errorBody = response.body?.string()?.take(120) ?: ""
                    response.close()
                    failed++
                    val msg = "${entry.label} (${entry.model}): HTTP $code"
                    Log.e(TAG, "❌ $msg | $errorBody")
                    if (entry.required) failures.add(msg)
                }
            } catch (e: IOException) {
                failed++
                val msg = "${entry.label}: ${e.message}"
                Log.e(TAG, "❌ $msg")
                if (entry.required) failures.add(msg)
            }
        }

        Log.i(TAG, "━━━ API 连通性结果 ━━━")
        Log.i(TAG, "✅ $passed  ❌ $failed  ⏭️ $skipped")

        assertTrue(
            "豆包模型连通性失败 (${failures.size}):\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }
}
