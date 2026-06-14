package com.chin.stockanalysis.ai

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.ApiConfigManager
import com.chin.stockanalysis.ApiProvider
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyCategory
import com.chin.stockanalysis.strategy.StrategyConfig
import com.chin.stockanalysis.strategy.StrategySource
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.SignalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ## AI 策略配置生成器（元编程）
 *
 * 用户用自然语言描述选股逻辑 → AI 生成 JSON 配置 → 动态注册到策略引擎。
 *
 * ### 使用方式
 * ```kotlin
 * val gen = StrategyConfigGenerator(context)
 * val result = gen.generate("日线MACD金叉 + 成交量放大2倍")
 * if (result != null) {
 *     StrategyEngineHolder.get().registerStrategy(result)
 * }
 * ```
 */
class StrategyConfigGenerator(private val context: Context) {

    companion object {
        private const val TAG = "StrategyGen"

        private val GENERATE_SYSTEM_PROMPT = """
你是一个专业的股票策略工程师。用户用自然语言描述选股逻辑，你需要将其转换为 JSON 策略配置。

## 输出格式
严格按照以下 JSON 格式输出（不要加任何其他文字）：
```json
{
  "id": "ai_gen_<time>",
  "name": "<策略中文名>",
  "description": "<一句话描述>",
  "category": "CUSTOM",
  "weightFactors": [
    {"key": "factor1", "label": "因子1", "weight": 40, "desc": "说明"},
    {"key": "factor2", "label": "因子2", "weight": 30, "desc": "说明"},
    {"key": "factor3", "label": "因子3", "weight": 30, "desc": "说明"}
  ]
}
```

## 规则
- category 可选值: TREND(趋势), MOMENTUM(动量), VALUE(价值), VOLUME(量价), CUSTOM(自定义)
- weightFactors 必须是 3-7 个因子，权重总和 100
- id 使用 "ai_gen_<毫秒时间戳>" 确保唯一
- name 简洁明了，不超过 15 字
""".trimIndent()
    }

    data class GeneratedStrategy(
        val id: String,
        val name: String,
        val description: String,
        val category: StrategyCategory,
        val weightFactors: List<WeightFactor>
    )

    suspend fun generate(userDescription: String): GeneratedStrategy? {
        return withContext(Dispatchers.IO) {
            try {
                val configManager = com.chin.stockanalysis.ApiConfigManager.getInstance(context)
                val config = configManager.getCurrentProviderConfig()
                    ?: configManager.getProviderConfig("doubao")
                    ?: return@withContext null
                val provider = com.chin.stockanalysis.OpenAiCompatibleProvider(config)

                var resultJson = ""
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    provider.sendMessageStream(
                        messages = listOf(com.chin.stockanalysis.ui.Message(
                            content = "请为以下选股逻辑生成策略配置: $userDescription",
                            isUser = true
                        )),
                        systemPrompt = GENERATE_SYSTEM_PROMPT,
                        onSuccess = { resultJson += it },
                        onComplete = {
                            cont.resumeWith(Result.success(Unit))
                        },
                        onError = {
                            Log.w(TAG, "AI生成策略失败: $it")
                            cont.resumeWith(Result.success(Unit))
                        }
                    )
                }

                parseGeneratedJson(resultJson)
            } catch (e: Exception) {
                Log.w(TAG, "策略生成异常: ${e.message}")
                null
            }
        }
    }

    private fun parseGeneratedJson(json: String): GeneratedStrategy? {
        try {
            // 提取 JSON 块（AI 可能在周围加了 ```json 和 ```）
            val cleanJson = json
                .replace(Regex("""```json\s*"""), "")
                .replace(Regex("""```\s*"""), "")
                .trim()
            val obj = JSONObject(cleanJson)
            val id = obj.optString("id", "ai_gen_${System.currentTimeMillis()}")
            val name = obj.optString("name", "AI生成策略")
            val desc = obj.optString("description", "")
            val categoryStr = obj.optString("category", "CUSTOM")
            val category = try {
                StrategyCategory.valueOf(categoryStr)
            } catch (_: Exception) { StrategyCategory.CUSTOM }

            val factors = mutableListOf<WeightFactor>()
            val factorsArr = obj.optJSONArray("weightFactors") ?: return null
            for (i in 0 until factorsArr.length()) {
                val f = factorsArr.getJSONObject(i)
                factors.add(WeightFactor(
                    key = f.optString("key", "factor$i"),
                    label = f.optString("label", "因子$i"),
                    weight = f.optInt("weight", 100 / factorsArr.length()),
                    description = f.optString("desc", "")
                ))
            }

            return GeneratedStrategy(id, name, desc, category, factors)
        } catch (e: Exception) {
            Log.w(TAG, "解析AI生成的JSON失败: ${e.message}")
            return null
        }
    }

    /** 将生成的策略注册到引擎 */
    fun registerToEngine(generated: GeneratedStrategy) {
        val engine = StrategyEngineHolder.get()
        val strategy = object : Strategy {
            override val id = generated.id
            override var name = generated.name
            override var description = generated.description
            override val category = generated.category
            override val source = StrategySource.USER_CUSTOM
            override val config = StrategyConfig.custom(params = emptyMap(), maxResults = 15)
            override var weightFactors = generated.weightFactors
            override suspend fun screen() = Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = emptyList(), totalScanned = 0, scanTimeMs = 0
            ))
            override suspend fun screenWithData(preloadedStocks: List<com.chin.stockanalysis.stock.StockRealtime>) =
                Result.success(ScreeningResult(
                    strategyId = id, strategyName = name, category = category,
                    signals = emptyList(), totalScanned = 0, scanTimeMs = 0
                ))
            override suspend fun isAvailable() = true
        }
        engine.registerStrategy(strategy)
        Log.i(TAG, "✅ AI生成策略已注册: ${generated.name} (${generated.id})")
    }
}