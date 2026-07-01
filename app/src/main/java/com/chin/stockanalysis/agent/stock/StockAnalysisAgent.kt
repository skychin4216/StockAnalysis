package com.chin.stockanalysis.agent.stock

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.ai.AiProviderSelector
import com.chin.stockanalysis.stock.data.StockDataFacade
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException

/**
 * ## 股票分析 Agent
 *
 * 對單只股票進行多維度深度分析，給出買賣建議。
 *
 * 優化：通過 StockDataFacade 統一獲取數據，僅做 1 次 LLM 調用，
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
        // 不再註冊 Tool，改用 StockDataFacade 統一獲取數據
    }

    override fun buildSystemPrompt(): String = """
        你是一位專業的 A 股股票分析師，擅長多維度深度分析單只股票。

        ## 核心規則（必須遵守）
        - 你只能使用下方數據中提供的股票名稱、代碼和價格進行分析
        - 絕對禁止使用你的訓練數據中的舊價格、舊信息
        - 如果數據中顯示股票名稱是「兆易創新」，你必須分析兆易創新，不能替換為其他股票
        - 所有價格、漲跌幅必須以「實時行情」為準，忽略歷史數據中的過期價格
        - 對於未提供的數據（如營收、PE、融資餘額等），必須標注「數據不可用」，禁止編造
        - 如果某項分析所需數據缺失，直接跳過該維度，不要使用訓練數據推斷
        - 永遠不要說「據我所知」「根據我的訓練數據」等暗示來自舊數據的表述

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
     * 優化策略：通過 StockDataFacade 統一獲取數據 → 1 次 LLM 調用生成結果
     */
    suspend fun analyze(
        stockCode: String,
        stockName: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): StockAnalysisResult {
        val normalizedCode = normalizeStockCode(stockCode)
        onProgress?.invoke("🔍 正在收集 ${stockName ?: normalizedCode} 數據...")

        // Step 1: 使用 StockDataFacade 一鍵獲取所有數據
        val data = StockDataFacade.getInstance(context)
            .getAnalysisData(normalizedCode)

        val resolvedName = stockName ?: data.quote?.name ?: data.fundamental.name ?: normalizedCode

        onProgress?.invoke("🤖 AI 分析 $resolvedName 中...")

        // Step 2: 構建 prompt
        val promptData = buildAnalysisPrompt(data, resolvedName)
        val prompt = """
請根據以下數據對股票 $resolvedName（$normalizedCode）進行深度分析，給出買賣建議。

$promptData
請嚴格按 JSON 格式輸出分析結果。
        """.trimIndent()

        // Step 3: 單次 LLM 調用，60s 超時
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

        // Step 4: 解析 JSON 結果
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

    /**
     * 構建分析用的 prompt 數據部分
     */
    private fun buildAnalysisPrompt(
        data: StockDataFacade.StockAnalysisData,
        resolvedName: String
    ): String {
        val sb = StringBuilder()

        // 實時行情
        if (data.quote != null) {
            val q = data.quote
            sb.appendLine("## 實時行情（${q.name}）")
            sb.appendLine("- 當前價: ${q.price} (${if (q.changePercent >= 0) "+" else ""}${"%.2f".format(q.changePercent)}%)")
            sb.appendLine("- 最高: ${q.high}, 最低: ${q.low}")
            sb.appendLine("- 成交量: ${q.volume}, 成交額: ${"%.0f".format(q.amount)}")
            sb.appendLine("- 換手率: ${"%.2f".format(q.turnoverRate)}%")
            if (q.pe > 0) sb.appendLine("- PE(TTM): ${"%.2f".format(q.pe)}")
            sb.appendLine()
        }

        // 基本面
        val f = data.fundamental
        sb.appendLine("## 基本面數據（${f.source}）")
        sb.appendLine("- 股票名稱: ${f.name}")
        if (f.business.isNotBlank()) sb.appendLine("- 主營業務: ${f.business}")
        if (f.sectorNames.isNotEmpty()) sb.appendLine("- 所屬板塊: ${f.sectorNames.joinToString(", ")}")
        if (f.chainRationale.isNotBlank()) sb.appendLine("- 產業鏈邏輯: ${f.chainRationale}")
        if (!f.isFresh) sb.appendLine("⚠️ 基本面數據可能不是最新")
        sb.appendLine()

        // 技術面
        val h = data.history
        if (h.snapshots.size >= 5) {
            val prices = h.snapshots.map { it.close }
            val volumes = h.snapshots.map { it.volume }
            val ma5 = prices.take(5).average()
            val ma10 = prices.take(10).average()
            val ma20 = prices.take(minOf(20, prices.size)).average()
            val avgVol = volumes.average()
            val latestVol = volumes.first()
            val trend = when {
                prices.first() > prices.last() * 1.05 -> "上升趨勢"
                prices.first() < prices.last() * 0.95 -> "下降趨勢"
                else -> "震蕩整理"
            }

            sb.appendLine("## 技術面數據（${h.source}）")
            sb.appendLine("- MA5: ${"%.2f".format(ma5)}, MA10: ${"%.2f".format(ma10)}, MA20: ${"%.2f".format(ma20)}")
            sb.appendLine("- 趨勢: $trend")
            sb.appendLine("- 量能: ${if (latestVol > avgVol * 1.5) "放量" else if (latestVol < avgVol * 0.7) "縮量" else "正常"}")
            if (!h.isFresh) sb.appendLine("⚠️ 歷史數據截至 ${h.latestDate}")
            sb.appendLine()
        } else {
            sb.appendLine("## 技術面數據")
            sb.appendLine("- 歷史數據不足（僅 ${h.snapshots.size} 條），無法計算技術指標")
            sb.appendLine()
        }

        // 資金面
        val ff = data.fundFlow
        sb.appendLine("## 資金面數據（${ff.source}）")
        if (!ff.isEmpty) {
            sb.appendLine("- 主力淨流入合計: ${"%.2f".format(ff.totalNetInflow)}萬")
            sb.appendLine("- 平均換手率: ${"%.2f".format(ff.avgTurnoverRate)}%")
            sb.appendLine("- 資金趨勢: ${if (ff.totalNetInflow > 0) "流入" else "流出"}")
            if (!ff.isFresh) sb.appendLine("⚠️ 最新數據日期: ${ff.latestDate}")
        } else {
            sb.appendLine("- 沒有資金流向數據（本地數據庫無記錄）")
        }

        return sb.toString()
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
