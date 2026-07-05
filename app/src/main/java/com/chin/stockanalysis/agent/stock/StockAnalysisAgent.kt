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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        1. 技術面（必須嚴格按以下步驟分析）：
           a) 大盤環境判斷：結合下方注入的大盤趨勢和熱點板塊，確定當前市場處於上升/下降/震蕩趨勢
              - 「形態生效的土壤」：上升三法等持續形態僅在明確趨勢中有效，震蕩市中形態成功率極低
              - 結合熱點板塊：個股屬於當前市場熱點板塊時，形態突破更容易獲得市場共鳴
           b) K線形態識別（必須逐一檢查，發現時在 reasoning 中明確指出）：
              * 看漲反轉：錘子線、啓明星、刺透形態、看漲吞沒（陽包陰）、上升三法、仙人指路、雙錘打擊
              * 看跌反轉：射擊之星、黃昏之星、看跌吞沒（陰包陽）、高位實體吞噬、漲勢盡頭線、高位掉線、頂部陰包陽
              * 多K線組合：紅三兵、三只烏鴉、塔形反轉、棄嬰/島形反轉
              * 中性/觀望：十字星、紡錘線
           c) 量價配合（核心驗證標準）：
              - 啟動信號（第一根K線）：必須放量，顯示強勁突破動能（成交量 > 前5日均量1.5倍）
              - 整理階段（中間小K線）：成交量應逐步萎縮，代表拋壓枯竭（洗盤而非出貨）
              - 確認信號（最後一根K線）：必須再次放量，且最好超過第一根K線的量能
              - 無量形態多為誘多或誘空，需特別警惕
           d) 圖表形態：頭肩頂/底、雙頂/雙底（M頂/W底）、杯柄形態、旗形整理、三角形收斮
           e) 背馳信號：價格創新高/新低但動量指標（RSI/MACD）未能確認 → 可能即將反轉
           f) 均線排列：多頭/空頭排列、金叉/死叉
           g) 形態確認標準：必須結合前後K線、成交量、位置（高位/低位/盤整）綜合判斷，不可單憑一根K線下結論
           h) 失敗場景警示：震蕩市中形態易失敗、消息驅動個股K線易被扭曲、高位滯漲的「上升三法」可能是誘多陷阱、小盤股中形態可能被主力刻意畫出
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

        // Step 2: 構建 prompt（使用完整的 system prompt + 數據）
        // 異步獲取大盤環境（失敗不阻塞分析）
        val marketReportStr = try {
            val report = com.chin.stockanalysis.strategy.market.MarketAnalyzer.analyze(
                context, emptyList()
            )
            buildString {
                appendLine("## 大盤環境（實時分析）")
                appendLine("- 大盤趨勢: ${report.trend.direction}（強度${report.trend.strength}/100）")
                appendLine("- 趨勢描述: ${report.trend.description}")
                appendLine("- 賣出信號: ${report.sellType.sellType}")
                if (report.sectorAdvice.recommendedSectors.isNotEmpty()) {
                    val sectorNames = report.sectorAdvice.recommendedSectors.map { it.sectorName }.joinToString(",")
                    appendLine("- 板塊建議: ${report.sectorAdvice.marketStyle}，推薦: $sectorNames")
                }
                appendLine("- 大盤摘要: ${report.summary}")
            }
        } catch (_: Exception) { "" }

        val promptData = buildAnalysisPrompt(data, resolvedName, marketReportStr)
        val systemPrompt = buildSystemPrompt() + "\n\n## 待分析股票數據\n\n" + promptData
        val prompt = "請分析股票 $resolvedName（$normalizedCode），嚴格按上方 JSON 格式輸出。"

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
                            messages = listOf(com.chin.stockanalysis.ui.Message(content = prompt, isUser = true)),
                            systemPrompt = systemPrompt,
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
        resolvedName: String,
        marketReport: String = ""
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

        // 大盤環境（從外部傳入，在 analyze() 中異步獲取）
        if (marketReport.isNotEmpty()) {
            sb.appendLine(marketReport)
        } else {
            sb.appendLine("## 大盤環境")
            sb.appendLine("- ⚠️ 大盤分析數據暫時不可用")
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
            val snaps = h.snapshots
            val prices = snaps.map { it.close }
            val opens = snaps.map { it.open }
            val highs = snaps.map { it.high }
            val lows = snaps.map { it.low }
            val volumes = snaps.map { it.volume }
            val ma5 = prices.take(5).average()
            val ma10 = if (prices.size >= 10) prices.take(10).average() else ma5
            val ma20 = if (prices.size >= 20) prices.take(20).average() else ma10
            val avgVol5 = volumes.take(5).average()
            val latestVol = volumes.first()
            val latestPrice = prices.first()
            val latestOpen = opens.first()
            val latestHigh = highs.first()
            val latestLow = lows.first()

            // 趨勢判斷
            val trend = when {
                prices.first() > prices.last() * 1.05 -> "上升趨勢"
                prices.first() < prices.last() * 0.95 -> "下降趨勢"
                else -> "震蕩整理"
            }

            // 均線排列
            val maAlign = when {
                ma5 > ma10 && ma10 > ma20 -> "多頭排列（MA5>MA10>MA20）"
                ma5 < ma10 && ma10 < ma20 -> "空頭排列（MA5<MA10<MA20）"
                ma5 > ma10 && ma10 < ma20 -> "短期偏強但中期承壓"
                ma5 < ma10 && ma10 > ma20 -> "短期回調但中期趨勢仍在"
                else -> "均線糾纏"
            }

            // 量能分析
            val volStatus = when {
                latestVol > avgVol5 * 2.0 -> "大幅放量（${"%.1f".format(latestVol / avgVol5)}倍均量）"
                latestVol > avgVol5 * 1.5 -> "放量（${"%.1f".format(latestVol / avgVol5)}倍均量）"
                latestVol < avgVol5 * 0.5 -> "大幅縮量（${"%.1f".format(latestVol / avgVol5)}倍均量）"
                latestVol < avgVol5 * 0.7 -> "縮量（${"%.1f".format(latestVol / avgVol5)}倍均量）"
                else -> "量能正常"
            }

            // K線形態初步識別（為 AI 提供數據基礎）
            val bodySize = abs(latestPrice - latestOpen)
            val upperShadow = latestHigh - max(latestPrice, latestOpen)
            val lowerShadow = min(latestPrice, latestOpen) - latestLow
            val totalRange = latestHigh - latestLow
            val bodyRatio = if (totalRange > 0) bodySize / totalRange else 0.0

            val candlePatterns = mutableListOf<String>()
            // 大陽/大陰線
            val changePctDay = if (latestOpen > 0) (latestPrice - latestOpen) / latestOpen * 100 else 0.0
            if (changePctDay > 3.0) candlePatterns.add("大陽線（漲${"%.1f".format(changePctDay)}%）")
            else if (changePctDay < -3.0) candlePatterns.add("大陰線（跌${"%.1f".format(abs(changePctDay))}%）")
            // 十字星
            if (bodyRatio < 0.1 && totalRange > 0) candlePatterns.add("十字星（上下影線較長，多空分歧）")
            // 錘子線（長下影線 + 小實體 + 出現在下跌後）
            if (lowerShadow > bodySize * 2 && upperShadow < bodySize * 0.5 && trend == "下降趨勢")
                candlePatterns.add("錘子線（長下影線 ${"%.2f".format(lowerShadow)}，看漲反轉信號）")
            // 射擊之星（長上影線 + 小實體 + 出現在上漲後）
            if (upperShadow > bodySize * 2 && lowerShadow < bodySize * 0.5 && trend == "上升趨勢")
                candlePatterns.add("射擊之星（長上影線 ${"%.2f".format(upperShadow)}，看跌反轉信號）")
            // 陽包陰/陰包陽
            if (snaps.size >= 2) {
                val prevOpen = opens[1]; val prevClose = prices[1]
                if (prevClose < prevOpen && latestPrice > latestOpen && latestPrice > prevOpen && latestOpen < prevClose)
                    candlePatterns.add("陽包陰（看漲吞沒，今日實體完全包裹昨日陰線實體）")
                if (prevClose > prevOpen && latestPrice < latestOpen && latestPrice < prevOpen && latestOpen > prevClose)
                    candlePatterns.add("陰包陽（看跌吞沒，今日實體完全包裹昨日陽線實體）")
            }

            sb.appendLine("## 技術面數據（${h.source}）")
            sb.appendLine("- 趨勢: $trend")
            sb.appendLine("- 均線: MA5=${"%.2f".format(ma5)}, MA10=${"%.2f".format(ma10)}, MA20=${"%.2f".format(ma20)}")
            sb.appendLine("- 均線排列: $maAlign")
            sb.appendLine("- 量能: $volStatus（5日均量=${"%.0f".format(avgVol5)}）")
            sb.appendLine("- 今日K線: 開${"%.2f".format(latestOpen)} 收${"%.2f".format(latestPrice)} 高${"%.2f".format(latestHigh)} 低${"%.2f".format(latestLow)}")
            if (candlePatterns.isNotEmpty())
                sb.appendLine("- 識別到K線形態: ${candlePatterns.joinToString("；")}")
            // 最近5日K線概要（幫助AI識別多K線組合）
            if (snaps.size >= 5) {
                sb.appendLine("- 近5日K線走勢:")
                for (i in 0 until minOf(5, snaps.size)) {
                    val s = snaps[i]
                    val dayChange = if (s.open > 0) (s.close - s.open) / s.open * 100 else 0.0
                    val volVsAvg = if (avgVol5 > 0) s.volume / avgVol5 else 1.0
                    val type = when {
                        dayChange > 1.0 -> "陽"
                        dayChange < -1.0 -> "陰"
                        else -> "小"
                    }
                    sb.appendLine("  D${i}: ${s.date} ${type}(${"%.1f".format(dayChange)}%) 量比${"%.1f".format(volVsAvg)}")
                }
            }
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
