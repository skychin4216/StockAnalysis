package com.chin.stockanalysis.agent.chat

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.agent.stock.StockPickingAgent
import com.chin.stockanalysis.ai.AiProviderPool
import com.chin.stockanalysis.strategy.data.LeaderStockPool
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.ui.TradingDayPickerView
import kotlinx.coroutines.Dispatchers
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
    private val pipelineAdapter = PipelineChatAdapter(context)

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

        return when (intent) {
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
                // 分析：提取股票代碼
                val code = extractStockCode(userMessage)
                val stockName = extractStockName(userMessage)

                if (analysisMode == com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.EXPERT && stockName != null) {
                    // 專家模式：走 Pipeline 多智能體流水線
                    onStream?.invoke("🤖 專家模式分析中：$stockName\n")
                    val pipelineResult = pipelineAdapter.analyze(stockName) { progress ->
                        onStream?.invoke("$progress\n")
                    }

                    if (pipelineResult != null) {
                        val report = pipelineAdapter.formatResult(pipelineResult)
                        ChatAgentResult(
                            success = pipelineResult.stocks.isNotEmpty(),
                            response = report,
                            intent = intent.name,
                            data = mapOf("pipelineResult" to pipelineResult)
                        )
                    } else {
                        ChatAgentResult(
                            success = false,
                            response = "專家分析超時（120s），部分步驟可能因網路或模型響應過慢而中斷。請稍後重試或切換為深度模式。",
                            intent = intent.name
                        )
                    }
                } else if (code != null) {
                    // 快速/深度模式：單次 LLM 分析
                    val result = analysisAgent.analyze(code, stockName = stockName)
                    ChatAgentResult(
                        success = result.success,
                        response = formatAnalysisResponse(result),
                        intent = intent.name,
                        data = mapOf("analysis" to result)
                    )
                } else {
                    // 檢查是否有歧義匹配
                    try {
                        val entities = com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(userMessage)
                        if (entities.size > 1) {
                            // 歧義：多個匹配，需要用戶確認
                            ChatAgentResult(
                                success = false,
                                response = "找到 ${entities.size} 個匹配，請選擇您要分析的股票。",
                                intent = intent.name,
                                ambiguousEntities = entities
                            )
                        } else {
                            ChatAgentResult(
                                success = false,
                                response = "請提供具體的股票代碼（如 600519）或名稱，我來為您分析。",
                                intent = intent.name
                            )
                        }
                    } catch (_: Exception) {
                        ChatAgentResult(
                            success = false,
                            response = "請提供具體的股票代碼（如 600519）或名稱，我來為您分析。",
                            intent = intent.name
                        )
                    }
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
                    lower.contains("大盤") || lower.contains("市場") || lower.contains("行情")
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

    private fun extractStockCode(message: String): String? {
        // 優先使用 StockEntityExtractor（支援名稱→代碼）
        try {
            val entities = com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(message)
            if (entities.isNotEmpty()) return entities.first().code
        } catch (_: Exception) { /* Trie 未構建，繼續 */ }

        // 降級：正則提取代碼
        val match = Regex("(sh|sz|bj)?(\\d{6})").find(message)
        return match?.groupValues?.get(2)
    }

    /**
     * 提取股票名稱（用於 Pipeline 輸入，需要中文名而非代碼）
     */
    private fun extractStockName(message: String): String? {
        try {
            val entities = com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(message)
            if (entities.isNotEmpty()) return entities.first().name
        } catch (_: Exception) { /* Trie 未構建，繼續 */ }
        return null
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
}

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
    override val parameters = listOf("stock_code")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val localCtx = ctx
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val code = params["stock_code"] ?: return@withContext "錯誤: 未提供股票代碼"
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
        val localCtx = ctx
        val c = ctx
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(localCtx)
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
