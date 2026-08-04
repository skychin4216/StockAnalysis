package com.chin.stockanalysis.agent.chat

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.agent.core.analyzeStock
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.agent.stock.StockPickingAgent
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.strategy.data.LeaderStockPool
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 對話 Agent（ReAct + 子 Agent 協作）
 *
 * 替代現有 ChatTabFragment 中硬編碼的對話邏輯，實現智能對話：
 * 1. 理解用戶意圖（選股/分析/交易/閒聊）
 * 2. 規劃回覆步驟（可能需要調用子 Agent）
 * 3. 執行並匯總結果
 * 4. 生成自然語言回覆
 */
class ChatAgent(context: Context) : AgentBase(
    id = "chat",
    name = "對話 Agent",
    description = "理解用戶意圖，協調其他 Agent 完成任務，生成自然語言回覆",
    context = context
) {
    companion object {
        private const val TAG = "ChatAgent"
    }

    /** 分析模式（由 ChatTabFragment 傳入） */
    var analysisMode: com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode = com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.QUICK

    private val pickingAgent = StockPickingAgent(context)
    private val analysisAgent = StockAnalysisAgent(context)

    init {
        registerTool(StockQueryTool(context))
        registerTool(MarketBriefTool(context))
        registerTool(IntentParseTool())
        registerTool(LeaderPoolManageTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位專業的股票投資助手，擅長理解用戶意圖並協調專業 Agent 完成任務。

        ## 你能做的事情
        1. 選股：調用選股 Agent 為用戶推薦股票
        2. 分析：調用分析 Agent 深度分析單只股票
        3. 閒聊：與用戶進行自然對話，回答投資相關問題
        4. 市場概覽：提供當日市場簡報

        ## 對話風格
        - 專業但親切，像一位經驗豐富的投資顧問
        - 回答簡潔有力，避免冗長
        - 涉及具體股票時，給出明確的代碼和理由
        - 不確定時坦誠說明，不瞎猜

        ## 意圖識別
        用戶輸入可能包含以下意圖：
        - "幫我選股" / "今天有什麼好股票" → 調用選股 Agent
        - "分析一下 600519" / "茅台怎麼樣" → 調用分析 Agent
        - "市場怎麼樣" / "今天大盤" → 提供市場簡報
        - 其他 → 直接回答
    """.trimIndent()

    /**
     * 處理用戶消息
     */
    suspend fun handleMessage(
        userMessage: String,
        onStream: ((String) -> Unit)? = null
    ): ChatAgentResult {
        val ctx = AgentContext().apply {
            put("user_message", userMessage)
            put("session_id", sessionId)
        }

        // 判斷意圖，決定使用哪種模式
        val intent = detectIntent(userMessage)

        // 同步提取機構線索（僅在非分析意圖時觸發，避免「分析機構動態」等請求誤觸發）
        val instResult = if (intent != UserIntent.STOCK_ANALYSIS) {
            tryExtractInstitutionalTips(userMessage)
        } else null

        val baseResult = when (intent) {
            UserIntent.INDEX_ANALYSIS -> {
                // 提取指數名稱/代碼
                val indexInfo = extractIndexInfo(userMessage)
                if (indexInfo != null) {
                    val result = IndexAnalysisAgent.analyze(context, indexInfo.first, indexInfo.second)
                    if (result != null) {
                        ChatAgentResult(
                            success = true,
                            response = "📊 ${result.indexName} 技術面分析\n\n${result.summary}\n\n${result.technicalView}\n\n🔮 短線預判：${result.prediction}\n\n⚠️ 風險提示：\n${result.risks.joinToString("\n") { "• $it" }}",
                            intent = intent.name
                        )
                    } else {
                        ChatAgentResult(success = false, response = "暫無指數歷史數據，請先確保已導入歷史數據。", intent = intent.name)
                    }
                } else {
                    ChatAgentResult(success = false, response = "請提供具體的指數名稱（如上證指數、深證成指）。", intent = intent.name)
                }
            }
            UserIntent.STOCK_PICKING -> {
                // 選股：使用 Plan-and-Execute
                val result = pickingAgent.pickStocks()
                ChatAgentResult(
                    success = result.success,
                    response = formatPickingResponse(result),
                    intent = intent.name,
                    data = mapOf("recommendations" to result.recommendations)
                )
            }
            UserIntent.STOCK_ANALYSIS -> {
                // 分析：提取所有股票實體（支持名稱和 múltiple stocks）
                val entities = extractAllStockEntities(userMessage)

                if (entities.isNotEmpty()) {
                    val coreMode = when (analysisMode) {
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.QUICK -> com.chin.stockanalysis.agent.core.AnalysisMode.QUICK
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.DEEP -> com.chin.stockanalysis.agent.core.AnalysisMode.DEEP
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.EXPERT -> com.chin.stockanalysis.agent.core.AnalysisMode.EXPERT
                    }
                    val modeLabel = when (analysisMode) {
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.QUICK -> "⚡ V1.0 Quick"
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.DEEP -> "🔍 V1.0 Pipeline"
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.EXPERT -> "📊 V2.0 全周期"
                    }

                    if (entities.size == 1) {
                        // 單股票：完整分析
                        val entity = entities.first()
                        val normalizedCode = StockAnalysisAgent.normalizeStockCode(entity.code)
                        onStream?.invoke("$modeLabel 分析中：${entity.name}(${normalizedCode})\n")

                        val result = com.chin.stockanalysis.agent.core.AgentOrchestrator(context).analyzeStock(
                            stockCode = normalizedCode,
                            stockName = entity.name,
                            mode = coreMode,
                            useAgentFramework = false
                        )

                        ChatAgentResult(
                            success = result.success,
                            response = if (result.success) result.summaryText
                                else "${modeLabel} 分析失敗：${result.errorMessage}",
                            intent = intent.name,
                            data = mapOf("unifiedResult" to result)
                        )
                    } else {
                        // 多股票：逐一分析後合併摘要
                        val orchestrator = com.chin.stockanalysis.agent.core.AgentOrchestrator(context)
                        val results = entities.take(5).map { entity ->
                            val normalizedCode = StockAnalysisAgent.normalizeStockCode(entity.code)
                            onStream?.invoke("$modeLabel 分析中：${entity.name}(${normalizedCode})\n")
                            try {
                                val r = orchestrator.analyzeStock(
                                    stockCode = normalizedCode,
                                    stockName = entity.name,
                                    mode = coreMode,
                                    useAgentFramework = false
                                )
                                entity to r
                            } catch (e: Exception) {
                                entity to null
                            }
                        }

                        val sb = StringBuilder()
                        sb.appendLine("## 📊 多股票對比分析（${results.size} 隻）")
                        sb.appendLine()
                        for ((entity, result) in results) {
                            val normalizedCode = StockAnalysisAgent.normalizeStockCode(entity.code)
                            if (result != null && result.success) {
                                sb.appendLine("### ${entity.name}（$normalizedCode）")
                                sb.appendLine(result.summaryText)
                                sb.appendLine()
                            } else {
                                sb.appendLine("### ${entity.name}（$normalizedCode）— 分析失敗")
                                sb.appendLine()
                            }
                        }

                        ChatAgentResult(
                            success = true,
                            response = sb.toString().trimEnd(),
                            intent = intent.name
                        )
                    }
                } else {
                    ChatAgentResult(
                        success = false,
                        response = "請提供具體的股票代碼（如 600519）或名稱，我來為您分析。",
                        intent = intent.name
                    )
                }
            }
            UserIntent.MARKET_BRIEF -> {
                // 市場簡報
                val brief = generateMarketBrief()
                ChatAgentResult(
                    success = true,
                    response = brief,
                    intent = intent.name
                )
            }
            else -> {
                // 一般對話：使用 ReAct
                val result = react(userMessage, ctx, maxSteps = 4)
                ChatAgentResult(
                    success = result.success,
                    response = result.output,
                    intent = intent.name,
                    steps = result.steps
                )
            }
        }

        // 如果提取到機構線索，附加板塊分析到回覆
        if (instResult != null && instResult.detectedSectors.isNotEmpty()) {
            val sectorAnalysis = buildInstitutionalSectorAnalysis(instResult, userMessage)
            return baseResult.copy(
                response = baseResult.response + "\n\n" + sectorAnalysis
            )
        }

        return baseResult
    }

    /**
     * 構建機構推薦板塊分析回覆
     * 包含：檢測到的板塊、股票歸屬、大盤趨勢判斷、超短/短線跟進建議
     */
    private suspend fun buildInstitutionalSectorAnalysis(
        extraction: InstitutionalTipExtraction,
        originalMessage: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("━━━━━━━━━━━━━━━━━━")
        sb.appendLine("🏦 機構線索板塊分析")
        sb.appendLine("━━━━━━━━━━━━━━━━━━")

        // 1. 顯式提及的板塊
        if (extraction.textSectors.isNotEmpty()) {
            sb.appendLine("📌 消息提及板塊：${extraction.textSectors.joinToString("、")}")
        }

        // 2. 股票→板塊歸屬
        sb.appendLine()
        sb.appendLine("📊 股票板塊歸屬：")
        for ((code, name) in extraction.stocks) {
            val sectors = extraction.stockSectors[code] ?: emptyList()
            val display = if (name.isNotBlank()) "$name(${code.takeLast(6)})" else code.takeLast(6)
            if (sectors.isNotEmpty()) {
                sb.appendLine("  • $display → ${sectors.joinToString("/")}")
            } else {
                sb.appendLine("  • $display → 未匹配到板塊")
            }
        }

        // 3. 合併板塊列表
        sb.appendLine()
        sb.appendLine("🎯 綜合板塊判斷：${extraction.detectedSectors.joinToString("、")}")

        // 4. 大盤趨勢判斷
        try {
            val appCtx = context.applicationContext
            val mktCtx = com.chin.stockanalysis.strategy.sector.StrategyMarketContext
                .build(appCtx, java.time.LocalDate.now().toString())
            val env = mktCtx.indexSnapshot.tripleVote
            val envLabel = when (env) {
                "BULLISH" -> "牛市偏多 🐂"
                "OSCILLATION" -> "震蕩市 ⚖️"
                "BEARISH" -> "熊市偏空 🐻"
                else -> "未知 $env"
            }
            sb.appendLine()
            sb.appendLine("📈 當前大盤環境：$envLabel")

            // 5. 跟進建議
            sb.appendLine()
            sb.appendLine("💡 跟進建議：")
            when (env) {
                "BULLISH" -> {
                    sb.appendLine("  • 大盤偏多，機構推薦板塊可積極跟進")
                    sb.appendLine("  • 建議在【超短線】或【短線】週期建倉")
                    sb.appendLine("  • 優先關注：${extraction.detectedSectors.take(3).joinToString("、")}")
                }
                "OSCILLATION" -> {
                    sb.appendLine("  • 震蕩市中機構推薦僅供參考，注意倉位控制")
                    sb.appendLine("  • 建議在【短線】週期輕倉試探，設好止損")
                    sb.appendLine("  • 優先關注有資金持續流入的板塊：${extraction.detectedSectors.take(2).joinToString("、")}")
                }
                "BEARISH" -> {
                    sb.appendLine("  • ⚠️ 熊市環境，機構推薦需謹慎對待")
                    sb.appendLine("  • 建議僅觀察，不急於跟進")
                    sb.appendLine("  • 若必須操作，僅限【超短線】日內做T，嚴控倉位<30%")
                }
                else -> {
                    sb.appendLine("  • 大盤方向不明，建議在【短線】週期觀察")
                }
            }
        } catch (_: Exception) {
            sb.appendLine("📈 大盤環境：暫無法判斷")
            sb.appendLine("💡 建議在【短線】週期觀察機構推薦板塊動向")
        }

        // 6. 已寫入數據庫提示
        sb.appendLine()
        sb.appendLine("✅ ${extraction.stockCount} 隻機構推薦股票已記錄，有效期3天")
        sb.appendLine("   板塊輪動預測器已接收線索（20%權重）")

        return sb.toString().trimEnd()
    }

    private fun detectIntent(message: String): UserIntent {
        val lower = message.lowercase()

        // 指數查詢意圖識別
        val indexKeywords = listOf("上證", "深證", "創業板", "科創板", "滬深300", "上證50", "大盤", "指數")
        val hasIndex = indexKeywords.any { lower.contains(it) }
        if (hasIndex) return UserIntent.INDEX_ANALYSIS

        // 優先用 StockEntityExtractor 做本地詞典匹配
        try {
            val entities = com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(message)
            if (entities.isNotEmpty()) {
                return when {
                    lower.contains("選股") || lower.contains("推薦") || lower.contains("有什麼好股票") || lower.contains("買什麼")
                        -> UserIntent.STOCK_PICKING
                    // 已找到股票實體 + 明確分析意圖 → STOCK_ANALYSIS（優先於市場簡報）
                    lower.contains("分析") || lower.contains("投資價值") || lower.contains("詳細") ||
                    lower.contains("基本面") || lower.contains("技術面") || lower.contains("資金面") ||
                    lower.contains("風險評估") || lower.contains("買入") || lower.contains("走勢")
                        -> UserIntent.STOCK_ANALYSIS
                    // 只有在大盤/市場詞彙出現且無具體分析要求時才顯示簡報
                    lower.contains("大盤") || lower.contains("市場簡報") || lower.contains("行情概覽")
                        -> UserIntent.MARKET_BRIEF
                    else -> UserIntent.STOCK_ANALYSIS
                }
            }
        } catch (_: Exception) { /* Trie 未構建，繼續 */ }

        return when {
            lower.contains("選股") || lower.contains("推薦") || lower.contains("有什麼好股票") || lower.contains("買什麼") -> UserIntent.STOCK_PICKING
            lower.contains("分析") || lower.contains("怎麼樣") || lower.contains("看一下") || lower.contains("點評") -> UserIntent.STOCK_ANALYSIS
            lower.contains("大盤") || lower.contains("市場") || lower.contains("行情") || lower.contains("走勢") -> UserIntent.MARKET_BRIEF
            Regex("(sh|sz|bj)?\\d{6}").containsMatchIn(lower) -> UserIntent.STOCK_ANALYSIS
            else -> UserIntent.GENERAL_CHAT
        }
    }

    private fun extractAllStockEntities(message: String): List<com.chin.stockanalysis.ai.StockEntityExtractor.ExtractedEntity> {
        try {
            val entities = com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(message)
            if (entities.isNotEmpty()) return entities
        } catch (_: Exception) { /* Trie 未構建，繼續 */ }

        // 降級：正則提取代碼
        val codes = Regex("(sh|sz|bj)?(\\d{6})").findAll(message).map { it.groupValues[2] }.toList()
        return codes.map { com.chin.stockanalysis.ai.StockEntityExtractor.ExtractedEntity(
            text = it, code = it, name = it,
            matchType = com.chin.stockanalysis.ai.StockEntityExtractor.MatchType.EXACT_CODE,
            confidence = 1.0f
        ) }
    }

    private fun extractIndexInfo(message: String): Pair<String, String>? {
        val indexMap = mapOf(
            "上證指數" to "sh000001", "上證" to "sh000001", "大盤" to "sh000001",
            "深證成指" to "sz399001", "深證" to "sz399001",
            "創業板指" to "sz399006", "創業板" to "sz399006",
            "科創50" to "sh000688", "科創板" to "sh000688",
            "滬深300" to "sh000300", "上證50" to "sh000016",
            "中證500" to "sh000905", "中證1000" to "sh000852"
        )
        for ((name, code) in indexMap) {
            if (message.contains(name)) return Pair(code, name)
        }
        return null
    }

    private fun formatPickingResponse(result: com.chin.stockanalysis.agent.stock.StockPickingResult): String {
        return buildString {
            appendLine("🎯 選股 Agent 為您推薦以下股票：")
            appendLine()
            result.recommendations.forEachIndexed { index, rec ->
                appendLine("${index + 1}. **${rec.name} (${rec.code})** — 評分 ${rec.score}分")
                appendLine("   命中策略: ${rec.strategies.joinToString(", ")}")
                appendLine("   理由: ${rec.reason}")
                appendLine()
            }
            if (result.riskWarning.isNotBlank()) {
                appendLine("⚠️ 風險提示: ${result.riskWarning}")
            }
        }
    }

    private fun formatAnalysisResponse(result: com.chin.stockanalysis.agent.stock.StockAnalysisResult): String {
        return buildString {
            appendLine("📊 **${result.stockCode} 分析報告**")
            appendLine()
            appendLine("綜合評分: ${result.overallScore}分 | 建議: ${result.recommendation} | 置信度: ${result.confidence}")
            appendLine()
            appendLine("各維度評分:")
            appendLine("- 技術面: ${result.technicalScore}分")
            appendLine("- 基本面: ${result.fundamentalScore}分")
            appendLine("- 資金面: ${result.fundFlowScore}分")
            appendLine()
            appendLine("分析理由: ${result.reasoning}")
            if (result.targetPrice.isNotBlank()) {
                appendLine("目標價: ${result.targetPrice} | 止損位: ${result.stopLoss}")
            }
            if (result.riskFactors.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ 風險因素:")
                result.riskFactors.forEach { appendLine("- $it") }
            }
        }
    }

    private suspend fun generateMarketBrief(): String {
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(context)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val data = db.dailySnapshotDao().getByDate(today)

                val avgChange = data.map { it.changePct }.average()
                val upCount = data.count { it.changePct > 0 }
                val downCount = data.count { it.changePct < 0 }
                val topGainers = data.sortedByDescending { it.changePct }.take(5)

                buildString {
                    appendLine("📈 **今日市場簡報** ($today)")
                    appendLine()
                    appendLine("大盤環境: ${if (avgChange > 1) "強勢" else if (avgChange > 0) "偏多" else if (avgChange > -1) "偏弱" else "弱勢"}（平均 ${"%.2f".format(avgChange)}%）")
                    appendLine("漲跌家數: 上漲 $upCount / 下跌 $downCount")
                    appendLine()
                    appendLine("漲幅榜 TOP 5:")
                    topGainers.forEachIndexed { i, s ->
                        appendLine("${i + 1}. ${s.name} (${s.code}): +${"%.2f".format(s.changePct)}%")
                    }
                }
            } catch (e: Exception) {
                "暫時無法獲取市場數據，請稍後再試。"
            }
        }
    }

    // ═══ 機構線索提取 ═══

    private val INST_KEYWORDS = listOf(
        "機構", "研報", "目標價", "買入評級", "增持評級", "推薦買入",
        "券商", "基金", "調研", "機構調研", "主力", "莊家", "游資",
        "龍虎榜", "機構席位", "量化", "融資", "北向資金",
        // 擴展：機構推薦消息常見用語
        "案例股", "教學案例", "調倉換股", "重點留意", "熱點機會",
        "新主線", "佈局", "抄底", "加倉", "減倉", "止盈",
        "投資顧問", "執業編號", "執業證書", "內部服務",
        "行情已經", "板塊方面", "短期可以", "重點關注"
    )

    /**
     * 從用戶對話中提取機構線索，寫入 institutional_tips 表。
     * 觸發條件：消息包含機構相關關鍵詞 + 至少一個股票代碼/名稱。
     * 有效期默認 3 天。
     *
     * @return 機構線索提取結果（含板塊檢測），若無線索返回 null
     */
    private suspend fun tryExtractInstitutionalTips(message: String): InstitutionalTipExtraction? {
        try {
            val hasInstKeyword = INST_KEYWORDS.any { message.contains(it) }
            if (!hasInstKeyword) return null

            // 提取股票實體
            val entities = try {
                com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(message)
            } catch (_: Exception) { emptyList() }

            // 降級：正則提取代碼（支援 sh/sz/bj 前綴 和 純6位數字）
            val stocks: List<Pair<String, String>> = if (entities.isNotEmpty()) {
                entities.map { it.code to it.name }
            } else {
                // 先嘗試帶前綴的代碼
                val prefixed = Regex("(sh|sz|bj)(\\d{6})").findAll(message).map {
                    it.value to ""
                }.toList()
                if (prefixed.isNotEmpty()) prefixed
                else {
                    // 降級：純6位數字代碼（需要排除非股票數字如日期、電話等）
                    Regex("(?<!\\d)(\\d{6})(?!\\d)").findAll(message).map { match ->
                        val code = match.value
                        // 根據代碼首位判斷市場前綴
                        val prefix = when (code.first()) {
                            '6' -> "sh"
                            '0', '3' -> "sz"
                            '8', '4' -> "bj"
                        else -> "sh"
                        }
                        "$prefix$code" to ""
                    }.toList()
                }
            }

            if (stocks.isEmpty()) return null

            val db = StockDatabase.getInstance(context)
            val dao = db.institutionalTipDao()
            val today = java.time.LocalDate.now().toString()
            val expire = java.time.LocalDate.now().plusDays(3).toString()

            // 判斷線索類型
            val tipType = when {
                message.contains("目標價") -> "target"
                message.contains("評級") || message.contains("增持") -> "rating"
                else -> "research"
            }

            // 板塊檢測：結合文本關鍵詞 + 股票板塊反查
            val sectorDetection = com.chin.stockanalysis.ai.SectorDetector
                .detect(message, stocks, context)

            val tips = stocks.map { (code, name) ->
                // 為每隻股票確定最佳板塊
                val stockSector = sectorDetection.stockSectors[code]?.firstOrNull()
                    ?: sectorDetection.textSectors.firstOrNull()
                    ?: ""

                com.chin.stockanalysis.strategy.topology.nodes.InstitutionalTipEntity(
                    stockCode = code,
                    stockName = name,
                    sector = stockSector,
                    source = "ai_chat",
                    tipType = tipType,
                    summary = message.take(100),
                    chatId = sessionId,
                    createdDate = today,
                    expireDate = expire
                )
            }
            dao.insertAll(tips)
            Log.i(TAG, "🏦 提取機構線索: ${tips.size} 條 (${tips.joinToString { "${it.stockCode}(${it.sector})" }})")

            return InstitutionalTipExtraction(
                stockCount = tips.size,
                stocks = stocks.map { it.first to it.second },
                detectedSectors = sectorDetection.allSectors,
                textSectors = sectorDetection.textSectors,
                stockSectors = sectorDetection.stockSectors
            )
        } catch (e: Exception) {
            Log.w(TAG, "機構線索提取失敗: ${e.message}")
            return null
        }
    }
}

/** 機構線索提取結果 */
data class InstitutionalTipExtraction(
    val stockCount: Int,
    val stocks: List<Pair<String, String>>,
    val detectedSectors: List<String>,
    val textSectors: List<String>,
    val stockSectors: Map<String, List<String>>
)

/** 對話結果 */
data class ChatAgentResult(
    val success: Boolean,
    val response: String,
    val intent: String = "GENERAL_CHAT",
    val data: Map<String, Any> = emptyMap(),
    val steps: Int = 0,
    val ambiguousEntities: List<com.chin.stockanalysis.ai.StockEntityExtractor.ExtractedEntity>? = null
)

enum class UserIntent {
    INDEX_ANALYSIS,
    STOCK_PICKING,
    STOCK_ANALYSIS,
    MARKET_BRIEF,
    GENERAL_CHAT
}

/** ================================================================ */
/** 股票查詢工具 */
class StockQueryTool(private val ctx: Context) : AgentTool {
    override val name = "stock_query"
    override val description = "查詢股票基本信息和最新行情"
    override val parameters = listOf("query")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val localCtx = ctx
        return withContext(Dispatchers.IO) {
            try {
                val rawQuery = params["query"] ?: params["stock_code"]
                    ?: return@withContext "錯誤: 未提供股票名稱或代碼"
                // 解析：可能是代碼（6位數字/sh+代碼）或名稱
                val code = if (rawQuery.matches(Regex("\\d{6}"))) {
                    rawQuery
                } else if (rawQuery.matches(Regex("(?i)(sh|sz|bj)\\d{6}"))) {
                    rawQuery.takeLast(6)
                } else {
                    // 名稱 → 通過 StockEntityExtractor 解析為代碼
                    val resolved = com.chin.stockanalysis.ai.StockEntityExtractor.resolveSync(rawQuery)
                    resolved ?: return@withContext "錯誤: 未找到股票「$rawQuery」"
                }
                val db = StockDatabase.getInstance(localCtx)
                val basic = db.stockBasicDao().getByCode(code)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val snapshot = db.dailySnapshotDao().getByDateAndCode(today, code)

                if (basic == null && snapshot == null) return@withContext "未找到股票 $code"

                buildString {
                    appendLine("【股票查詢】 $code")
                    basic?.let {
                        appendLine("- 名稱: ${it.name}")
                        appendLine("- 主營: ${it.business}")
                    }
                    snapshot?.let {
                        appendLine("- 最新價: ${it.close} (${if(it.changePct>=0)"+" else ""}${"%.2f".format(it.changePct)}%)")
                        appendLine("- 成交額: ${"%.0f".format(it.amount)}萬")
                        appendLine("- 換手率: ${"%.2f".format(it.turnoverRate)}%")
                    }
                }
            } catch (e: Exception) {
                "錯誤: 查詢失敗: ${e.message}"
            }
        }
    }
}

/** 市場簡報工具 */
class MarketBriefTool(private val ctx: Context) : AgentTool {
    override val name = "market_brief"
    override val description = "獲取當日市場簡要概況"
    override val parameters = listOf<String>()

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(ctx)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val data = db.dailySnapshotDao().getByDate(today)

                val avgChange = data.map { it.changePct }.average()
                val upCount = data.count { it.changePct > 0 }
                val downCount = data.count { it.changePct < 0 }

                "今日市場: 平均 ${"%.2f".format(avgChange)}%, 上漲 $upCount 家, 下跌 $downCount 家"
            } catch (e: Exception) {
                "錯誤: 獲取市場簡報失敗: ${e.message}"
            }
        }
    }
}

/** 意圖解析工具 */
class IntentParseTool : AgentTool {
    override val name = "intent_parse"
    override val description = "解析用戶輸入的意圖（選股/分析/閒聊）"
    override val parameters = listOf("message")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val message = params["message"] ?: return "錯誤: 未提供消息"
        val lower = message.lowercase()
        val intent = when {
            lower.contains("選股") || lower.contains("推薦") -> "STOCK_PICKING"
            lower.contains("分析") || Regex("\\d{6}").containsMatchIn(lower) -> "STOCK_ANALYSIS"
            lower.contains("大盤") || lower.contains("市場") -> "MARKET_BRIEF"
            else -> "GENERAL_CHAT"
        }
        return "意圖識別結果: $intent"
    }
}

/** 龍頭股池管理工具 — 支持對話式增刪改查 */
class LeaderPoolManageTool(private val ctx: Context) : AgentTool {
    override val name = "leader_pool_manage"
    override val description = "管理龍頭股池：列出板塊、添加/移除板塊、添加/移除股票、標記概念炒作。參數: action=list|add_sector|remove_sector|add_stock|remove_stock|set_concept, sector_name, sub_sector_name, stock_code, is_concept(true/false)"
    override val parameters = listOf("action", "sector_name", "sub_sector_name", "stock_code", "is_concept")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val action = params["action"] ?: return "錯誤: 缺少 action 參數"
        val sectorName = params["sector_name"] ?: ""
        val subSectorName = params["sub_sector_name"] ?: ""
        val stockCode = params["stock_code"] ?: ""
        val isConcept = params["is_concept"]?.lowercase() == "true"

        return when (action) {
            "list" -> {
                val sectors = LeaderStockPool.listSectors(ctx)
                val details = sectors.joinToString("\n") { name ->
                    val cfg = LeaderStockPool.getAllConfigs(ctx).find { it.name == name }
                    val tag = if (cfg?.isConcept == true) "[概念]" else "[產業]"
                    val stocks = cfg?.subSectors?.sumOf { it.stocks.size } ?: 0
                    "- $tag $name ($stocks 只)"
                }
                "當前龍頭股池共 ${sectors.size} 個板塊:\n$details"
            }

            "list_detail" -> {
                if (sectorName.isBlank()) return "錯誤: 請提供 sector_name"
                val subs = LeaderStockPool.listSubSectors(ctx, sectorName)
                if (subs.isEmpty()) return "板塊 [$sectorName] 不存在或為空"
                subs.joinToString("\n") { (subName, stocks) ->
                    "  [$subName]: ${stocks.joinToString(", ")}"
                }
            }

            "add_sector" -> {
                if (sectorName.isBlank()) return "錯誤: 請提供 sector_name"
                val ok = LeaderStockPool.addSector(ctx, sectorName, isConcept)
                if (ok) "✅ 已添加板塊 [$sectorName]${if (isConcept) " (標記為概念)" else ""}"
                else "⚠️ 板塊 [$sectorName] 已存在"
            }

            "remove_sector" -> {
                if (sectorName.isBlank()) return "錯誤: 請提供 sector_name"
                val ok = LeaderStockPool.removeSector(ctx, sectorName)
                if (ok) "✅ 已移除板塊 [$sectorName]"
                else "⚠️ 板塊 [$sectorName] 不存在"
            }

            "add_stock" -> {
                if (sectorName.isBlank() || subSectorName.isBlank() || stockCode.isBlank())
                    return "錯誤: 請提供 sector_name, sub_sector_name, stock_code"
                val ok = LeaderStockPool.addStock(ctx, sectorName, subSectorName, stockCode)
                if (ok) "✅ 已添加 [$stockCode] 到 [$sectorName / $subSectorName]"
                else "⚠️ 添加失敗（可能已存在或板塊不存在）"
            }

            "remove_stock" -> {
                if (sectorName.isBlank() || subSectorName.isBlank() || stockCode.isBlank())
                    return "錯誤: 請提供 sector_name, sub_sector_name, stock_code"
                val ok = LeaderStockPool.removeStock(ctx, sectorName, subSectorName, stockCode)
                if (ok) "✅ 已從 [$sectorName / $subSectorName] 移除 [$stockCode]"
                else "⚠️ 移除失敗（股票不存在）"
            }

            "set_concept" -> {
                if (sectorName.isBlank()) return "錯誤: 請提供 sector_name"
                val ok = LeaderStockPool.setSectorConcept(ctx, sectorName, isConcept)
                if (ok) "✅ 已將 [$sectorName] 標記為${if (isConcept) "概念炒作" else "產業主線"}"
                else "⚠️ 板塊 [$sectorName] 不存在"
            }

            "reset" -> {
                LeaderStockPool.resetToDefault(ctx)
                "✅ 龍頭股池已重置為默認配置"
            }

            else -> "錯誤: 未知 action [$action]。支持: list, list_detail, add_sector, remove_sector, add_stock, remove_stock, set_concept, reset"
        }
    }
}
