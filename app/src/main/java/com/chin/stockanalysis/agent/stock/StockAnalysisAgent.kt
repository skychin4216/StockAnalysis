package com.chin.stockanalysis.agent.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.ai.AiProviderSelector
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException

/**
 * ## 股票分析 Agent
 *
 * 對單只股票進行多維度深度分析，給出買賣建議。
 *
 * 優化：並行收集技術面/基本面/資金面數據，僅做 1 次 LLM 調用，
 * 避免多次 plan-and-execute 超時。
 */
class StockAnalysisAgent(context: Context) : AgentBase(
    id = "stock_analysis",
    name = "股票分析 Agent",
    description = "對單只股票進行多維度深度分析，給出買賣建議",
    context = context
) {
    companion object {
        private const val TAG = "StockAnalysisAgent"
        /** 單次 LLM 調用超時（推理模型較慢） */
        private const val ANALYSIS_LLM_TIMEOUT_MS = 120_000L

        /**
         * 正規化股票代碼：603986 → sh603986
         * Sina API、Tencent API、DB 都需要前綴格式
         */
        fun normalizeStockCode(code: String): String {
            val trimmed = code.trim()
            // 已有前綴的格式直接返回
            if (trimmed.length > 6 && (trimmed.startsWith("sh") || trimmed.startsWith("sz") || trimmed.startsWith("bj"))) {
                return trimmed
            }
            // 6位純數字代碼，根據首字添加對應前綴
            if (trimmed.length == 6 && trimmed.all { it.isDigit() }) {
                return when (trimmed[0]) {
                    '6', '9' -> "sh$trimmed"
                    '0', '3' -> "sz$trimmed"
                    '4', '8' -> "bj$trimmed"
                    else -> "sh$trimmed" // fallback
                }
            }
            return trimmed
        }
    }

    init {
        registerTool(TechnicalAnalysisTool(context))
        registerTool(FundamentalAnalysisTool(context))
        registerTool(FundFlowTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位專業的 A 股股票分析師，擅長多維度深度分析單只股票。

        ## 核心規則（必須遵守）
        - 你只能使用下方數據中提供的股票名稱、代碼和價格進行分析
        - 絕對禁止使用你的訓練數據中的舊價格、舊信息
        - 如果數據中顯示股票名稱是「兆易創新」，你必須分析兆易創新，不能替換為其他股票
        - 所有價格、漲跌幅必須以「實時行情」為準，忽略歷史數據中的過期價格

        ## 分析框架
        1. 技術面：均線排列、支撐壓力、量能變化
        2. 基本面：主營業務、產業鏈地位
        3. 資金面：主力流向、換手率

        ## 輸出格式（嚴格 JSON）
        ```json
        {
          "stock_name": "數據中提供的股票名稱",
          "stock_code": "數據中提供的股票代碼",
          "current_price": "實時行情中的當前價",
          "overall_score": 75,
          "recommendation": "BUY|HOLD|SELL|WATCH",
          "confidence": "HIGH|MEDIUM|LOW",
          "technical_score": 80,
          "fundamental_score": 85,
          "fund_flow_score": 70,
          "reasoning": "基於提供的實時數據進行分析",
          "risk_factors": ["風險1", "風險2"],
          "target_price": "基於實時價格推算的目標價",
          "stop_loss": "基於實時價格推算的止損價"
        }
        ```
        請只輸出 JSON，不要有其他文字。如果無法計算具體分數，給出合理估算。
    """.trimIndent()

    /**
     * 分析股票
     *
     * 優化策略：並行收集 3 維度數據 → 1 次 LLM 調用生成結果
     */
    suspend fun analyze(
        stockCode: String,
        stockName: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): StockAnalysisResult {
        // 正規化股票代碼：603986 → sh603986（Sina API 和 DB 都需要前綴格式）
        val normalizedCode = normalizeStockCode(stockCode)
        onProgress?.invoke("🔍 正在收集 ${stockName ?: normalizedCode} 數據...")

        val ctx = AgentContext().apply {
            put("stock_code", normalizedCode)
            put("date", TradingDayPickerView.recentTradingDay().toString())
        }

        // Step 1: 並行收集三維度數據 + 實時行情
        val analysisData = withContext(Dispatchers.IO) {
            // 先獲取實時行情（所有 Tool 都可能需要，同時獲取股票名稱）
            val realtimeData = try {
                com.chin.stockanalysis.stock.data.sources.SinaStockSource()
                    .fetchRealtime(listOf(normalizedCode))[normalizedCode]
            } catch (_: Exception) { null }

            // 確定股票名稱：優先使用傳入的，其次用實時行情的，最後用代碼
            val resolvedName = stockName ?: realtimeData?.name ?: normalizedCode

            // 將實時數據注入 AgentContext，供 Tool 使用
            if (realtimeData != null) {
                ctx.put("realtime_price", realtimeData.price.toString())
                ctx.put("realtime_change_pct", realtimeData.changePercent.toString())
                ctx.put("realtime_high", realtimeData.high.toString())
                ctx.put("realtime_low", realtimeData.low.toString())
                ctx.put("realtime_volume", realtimeData.volume.toString())
                ctx.put("realtime_amount", realtimeData.amount.toString())
            }

            val technical = async {
                tools["technical_analysis"]?.execute(
                    mapOf("stock_code" to normalizedCode, "days" to "30"), ctx
                ) ?: "數據不可用"
            }
            val fundamental = async {
                tools["fundamental_analysis"]?.execute(
                    mapOf("stock_code" to normalizedCode), ctx
                ) ?: "數據不可用"
            }
            val fundFlow = async {
                tools["fund_flow"]?.execute(
                    mapOf("stock_code" to normalizedCode, "days" to "5"), ctx
                ) ?: "數據不可用"
            }

            val techResult = technical.await()
            val fundResult = fundamental.await()
            val flowResult = fundFlow.await()

            Log.d(TAG, "📊 Tool 結果: tech=${techResult.take(30)}, fund=${fundResult.take(30)}, flow=${flowResult.take(30)}")
            Log.d(TAG, "📊 realtimeData=${if (realtimeData != null) "price=${realtimeData.price}" else "null"}")

            // 如果所有 Tool 都返回空數據，用實時行情構建基本數據
            val allEmpty = (techResult.contains("沒有") || techResult.contains("不足") || techResult.contains("不可用"))
                    && (fundResult.contains("沒有") || fundResult.contains("未找到") || fundResult.contains("不可用"))
                    && (flowResult.contains("沒有") || flowResult.contains("不可用"))
            val fallbackData = if (allEmpty && realtimeData != null) {
                Log.w(TAG, "⚠️ DB 數據為空，使用實時行情構建基本數據")
                """
## 實時行情（新浪 — DB 中無歷史數據，僅提供即時行情）
- 股票名稱: ${resolvedName}
- 當前價: ${realtimeData.price}
- 漲跌幅: ${"%.2f".format(realtimeData.changePercent)}%
- 最高: ${realtimeData.high}
- 最低: ${realtimeData.low}
- 成交量: ${realtimeData.volume}
- 成交額: ${"%.0f".format(realtimeData.amount)}
"""
            } else null

            if (fallbackData != null) {
                Pair(fallbackData, resolvedName)
            } else {
                // 正常數據 + 如果有實時行情，追加到末尾確保 prompt 不為空
                val realtimeSection = if (realtimeData != null && !allEmpty) {
                    "\n## 實時行情（新浪）\n- 股票名稱: $resolvedName\n- 當前價: ${realtimeData.price}\n- 漲跌幅: ${"%.2f".format(realtimeData.changePercent)}%\n- 最高: ${realtimeData.high}\n- 最低: ${realtimeData.low}\n- 成交量: ${realtimeData.volume}\n- 成交額: ${"%.0f".format(realtimeData.amount)}"
                } else null

                Pair("""
## 技術面數據
$techResult

## 基本面數據
$fundResult

## 資金面數據
$flowResult
${realtimeSection ?: ""}
                """.trimIndent(), resolvedName)
            }
        }

        val (promptData, resolvedStockName) = analysisData

        onProgress?.invoke("🤖 AI 分析 $resolvedStockName 中...")

        // Step 2: 單次 LLM 調用，60s 超時
        val prompt = """
請根據以下數據對股票 $resolvedStockName（$normalizedCode）進行深度分析，給出買賣建議。

$promptData
請嚴格按 JSON 格式輸出分析結果。
        """.trimIndent()

        val llmOutput = try {
            // 使用場景選擇器：Agent 分析用結構化輸出模型
            val provider = AiProviderSelector.getProvider(
                context = context,
                scenario = AiProviderSelector.AiScenario.CHAT_AGENT
            ) ?: throw IllegalStateException("無可用 AI Provider")

            val startTime = System.currentTimeMillis()
            var lastTokenTime = startTime
            var hasReceivedToken = false

            withTimeout(ANALYSIS_LLM_TIMEOUT_MS) {
                coroutineScope {
                    var resumed = false

                    // 無活動檢測：20s 無 token 則認為卡住
                    val activityJob = launch {
                        while (isActive) {
                            delay(5_000)
                            val idle = System.currentTimeMillis() - lastTokenTime
                            if (idle > 20_000 && hasReceivedToken) {
                                Log.w(TAG, "⏱ 20s 無新 token，取消")
                                resumed = true
                                break
                            }
                        }
                    }

                    val result = suspendCancellableCoroutine<String> { cont ->
                        cont.invokeOnCancellation {
                            provider.cancel()
                        }

                        provider.sendMessageStreamJson(
                            messages = emptyList(),
                            systemPrompt = buildSystemPrompt(),
                            onSuccess = { _ ->
                                lastTokenTime = System.currentTimeMillis()
                                hasReceivedToken = true
                            },
                            onComplete = { full ->
                                if (!resumed) { cont.resume(full) {} }
                            },
                            onError = { err ->
                                if (!resumed) { cont.resumeWith(Result.failure(Exception(err))) }
                            }
                        )
                    }

                    activityJob.cancel()
                    result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM 分析失敗: ${e.message}")
            return StockAnalysisResult(
                success = false,
                stockCode = normalizedCode,
                rawOutput = "AI 分析超時，請稍後重試",
                steps = 1
            )
        }

        // Step 3: 解析 JSON 結果
        return try {
            val jsonStr = llmOutput.substringAfter("```json").substringBefore("```").trim()
                .ifEmpty { llmOutput.trim() }
            val json = org.json.JSONObject(jsonStr)
            StockAnalysisResult(
                success = true,
                stockCode = normalizedCode,
                overallScore = json.optInt("overall_score", 0),
                recommendation = json.optString("recommendation", "WATCH"),
                confidence = json.optString("confidence", "LOW"),
                technicalScore = json.optInt("technical_score", 0),
                fundamentalScore = json.optInt("fundamental_score", 0),
                fundFlowScore = json.optInt("fund_flow_score", 0),
                reasoning = json.optString("reasoning", ""),
                riskFactors = json.optJSONArray("risk_factors")?.let {
                    (0 until it.length()).map { i -> it.getString(i) }
                } ?: emptyList(),
                targetPrice = json.optString("target_price", ""),
                stopLoss = json.optString("stop_loss", ""),
                rawOutput = llmOutput,
                steps = 1
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析分析結果失敗: ${e.message}")
            // JSON 解析失敗時，直接返回原始文本（推理模型可能帶思考過程）
            StockAnalysisResult(
                success = true,
                stockCode = normalizedCode,
                reasoning = llmOutput,
                rawOutput = llmOutput,
                steps = 1
            )
        }
    }
}

/** 分析結果 */
data class StockAnalysisResult(
    val success: Boolean,
    val stockCode: String,
    val overallScore: Int = 0,
    val recommendation: String = "WATCH",
    val confidence: String = "LOW",
    val technicalScore: Int = 0,
    val fundamentalScore: Int = 0,
    val fundFlowScore: Int = 0,
    val reasoning: String = "",
    val riskFactors: List<String> = emptyList(),
    val targetPrice: String = "",
    val stopLoss: String = "",
    val rawOutput: String = "",
    val steps: Int = 0
)

/** ================================================================ */
/** 技術分析工具 */
class TechnicalAnalysisTool(private val ctx: Context) : AgentTool {
    override val name = "technical_analysis"
    override val description = "分析股票技術面（均線、K線、成交量）"
    override val parameters = listOf("stock_code", "days")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val days = params["days"]?.toIntOrNull() ?: 30
                val db = StockDatabase.getInstance(c)
                val snapshots = db.dailySnapshotDao().getByCode(code, days)

                if (snapshots.isEmpty()) return@withContext "沒有歷史數據"
                if (snapshots.size < 5) return@withContext "數據不足（僅 ${snapshots.size} 條）"

                val latest = snapshots.first()
                val prices = snapshots.map { it.close }
                val volumes = snapshots.map { it.volume }

                val ma5 = prices.take(5).average()
                val ma10 = prices.take(10).average()
                val ma20 = prices.take(coerceAtMost(20, prices.size)).average()
                val avgVolume = volumes.average()
                val latestVolume = volumes.first()

                val trend = when {
                    prices.first() > prices.last() * 1.05 -> "上升趨勢"
                    prices.first() < prices.last() * 0.95 -> "下降趨勢"
                    else -> "震蕩整理"
                }

                // 嘗試獲取實時行情，替換過期的本地數據
                var currentPrice = latest.close
                var currentChangePct = latest.changePct
                var dataSource = "歷史"
                try {
                    val realtimeMap = com.chin.stockanalysis.stock.data.sources.SinaStockSource()
                        .fetchRealtime(listOf(code))
                    val realtime = realtimeMap[code]
                    if (realtime != null && realtime.price > 0) {
                        currentPrice = realtime.price
                        currentChangePct = realtime.changePercent
                        dataSource = "實時"
                    }
                } catch (_: Exception) { /* 實時獲取失敗，使用本地數據 */ }

                buildString {
                    appendLine("【技術面分析】 $code（$dataSource）")
                    appendLine("- 當前價: $currentPrice " +
                        "(${if (currentChangePct >= 0) "+" else ""}" +
                        "${"%.2f".format(currentChangePct)}%)")
                    appendLine("- MA5: ${"%.2f".format(ma5)}, " +
                        "MA10: ${"%.2f".format(ma10)}, " +
                        "MA20: ${"%.2f".format(ma20)}")
                    appendLine("- 趨勢: $trend")
                    appendLine("- 量能: ${
                        if (latestVolume > avgVolume * 1.5) "放量"
                        else if (latestVolume < avgVolume * 0.7) "縮量"
                        else "正常"
                    } (${"%.0f".format(latestVolume / avgVolume * 100)}% 均量)")
                    appendLine("- 換手率: ${"%.2f".format(latest.turnoverRate)}%")
                    appendLine("- 主力淨流入: ${"%.2f".format(latest.mainNetInflow)}萬")
                }
            } catch (e: Exception) {
                "錯誤: 技術分析失敗: ${e.message}"
            }
        }
    }

    private fun coerceAtMost(max: Int, size: Int): Int = if (max > size) size else max
}

/** 基本面分析工具 */
class FundamentalAnalysisTool(private val ctx: Context) : AgentTool {
    override val name = "fundamental_analysis"
    override val description = "分析股票基本面（主營業務、產業鏈）"
    override val parameters = listOf("stock_code")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val db = StockDatabase.getInstance(c)
                val basic = db.stockBasicDao().getByCode(code)
                    ?: return@withContext "未找到股票基本信息"

                val sectorNames = db.sectorStockDao().getSectorNamesByStockCode(code)

                buildString {
                    appendLine("【基本面分析】 $code ${basic.name}")
                    appendLine("- 主營業務: ${basic.business}")
                    if (sectorNames.isNotEmpty()) {
                        appendLine("- 所屬板塊: ${sectorNames.joinToString(", ")}")
                    }
                    if (basic.chainRationale.isNotBlank()) {
                        appendLine("- 產業鏈邏輯: ${basic.chainRationale}")
                    }
                }
            } catch (e: Exception) {
                "錯誤: 基本面分析失敗: ${e.message}"
            }
        }
    }
}

/** 資金流向工具 */
class FundFlowTool(private val ctx: Context) : AgentTool {
    override val name = "fund_flow"
    override val description = "分析資金流向和主力動向"
    override val parameters = listOf("stock_code", "days")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
                val days = params["days"]?.toIntOrNull() ?: 5
                val db = StockDatabase.getInstance(c)
                val snapshots = db.dailySnapshotDao().getByCode(code, days)

                if (snapshots.isEmpty()) return@withContext "沒有資金流向數據"

                val totalInflow = snapshots.sumOf { it.mainNetInflow }
                val avgTurnover = snapshots.map { it.turnoverRate }.average()

                buildString {
                    appendLine("【資金面分析】 $code（近 $days 日）")
                    appendLine("- 主力淨流入合計: ${"%.2f".format(totalInflow)}萬")
                    appendLine("- 平均換手率: ${"%.2f".format(avgTurnover)}%")
                    appendLine("- 資金趨勢: ${if (totalInflow > 0) "流入" else "流出"}")
                }
            } catch (e: Exception) {
                "錯誤: 資金分析失敗: ${e.message}"
            }
        }
    }
}
