package com.chin.stockanalysis.agent.chat

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.*
import com.chin.stockanalysis.agent.core.analyzeStock
import com.chin.stockanalysis.agent.hub.AgentHub
import com.chin.stockanalysis.agent.hub.ChatExpertSpec
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
 * ## 对话 Agent（ReAct + 子 Agent 协作）
 *
 * 替代现有 ChatTabFragment 中硬编码的对话逻辑，实现智能对话：
 * 1. 理解用户意图（选股/分析/交易/闲聊）
 * 2. 规划回复步骤（可能需要调用子 Agent）
 * 3. 执行并汇总结果
 * 4. 生成自然语言回复
 */
class ChatAgent(context: Context) : AgentBase(
    id = "chat",
    name = "对话 Agent",
    description = "理解用户意图，协调其他 Agent 完成任务，生成自然语言回复",
    context = context
) {
    companion object {
        private const val TAG = "ChatAgent"
    }

    /** 分析模式（由 ChatTabFragment 传入） */
    var analysisMode: com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode = com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.QUICK

    private val pickingAgent = StockPickingAgent(context)
    private val analysisAgent = StockAnalysisAgent(context)

    init {
        registerTool(StockQueryTool(context))
        registerTool(MarketBriefTool(context))
        registerTool(IntentParseTool())
        registerTool(LeaderPoolManageTool(context))
        registerTool(AiSelectionTool(context))
        registerTool(SectorRotationTool(context))
        registerTool(PortfolioHealthTool(context))
    }

    override fun buildSystemPrompt(): String = """
        你是一位专业的股票投资助手，擅长理解用户意图并协调专业 Agent 完成任务。

        ## 你能做的事情
        1. 选股：调用选股 Agent 为用户推荐股票
        2. 分析：调用分析 Agent 深度分析单只股票
        3. 闲聊：与用户进行自然对话，回答投资相关问题
        4. 市场概览：提供当日市场简报
        5. 一键 AI 选股：调用 ai_selection 工具，聚合龙头+备选池+AI精选+自选后四周期分类
        6. 板块轮动：调用 sector_rotation 工具，输出月度前瞻 Top 板块与轮动方向
        7. 持仓评估：调用 portfolio_health 工具，输出持仓盈亏/健康度/卖出信号

        ## 对话风格
        - 专业但亲切，像一位经验丰富的投资顾问
        - 回答简洁有力，避免冗长
        - 涉及具体股票时，给出明确的代码和理由
        - 不确定时坦诚说明，不瞎猜

        ## 意图识别
        用户输入可能包含以下意图：
        - "帮我选股" / "今天有什么好股票" → 调用选股 Agent
        - "分析一下 600519" / "茅台怎么样" → 调用分析 Agent
        - "市场怎么样" / "今天大盘" → 提供市场简报
        - 其他 → 直接回答
    """.trimIndent()

    /**
     * ## 复合需求 → 多 Agent 编排（对齐 CodeBuddy「父规划 + 子 Agent 委派」）
     *
     * 当一条消息命中 ≥2 个领域专家（如"持仓 + 板块"）时，系统不写死路由，
     * 而是把命中的每个领域当作一个独立子 Agent：子 Agent 拥有自己的角色描述
     * （AgentHub spec），并沿用工具循环（react 内部每次重建 messageHistory，
     * 天然隔离上下文）收集数据、给出独立结论；最后父级（再次 react）汇总。
     */
    private suspend fun orchestrateWithExperts(
        userMessage: String,
        ctx: AgentContext,
        experts: List<ChatExpertSpec>,
        onStream: ((String) -> Unit)?
    ): ChatAgentResult {
        val used = experts.take(3)
        val parts = StringBuilder()
        var totalSteps = 0
        used.forEachIndexed { idx, e ->
            onStream?.invoke("🧩 子Agent ${idx + 1}/${used.size}：${e.icon} ${e.name}\n")
            val task = "你正在扮演独立子Agent「${e.name}」——${e.description}。" +
                "只从你的领域视角分析用户需求：\n$userMessage\n" +
                "需要数据就调用工具；最后用 2~5 句给出独立结论（含风险提示）。"
            val r = react(task, ctx, maxSteps = 3)
            totalSteps += r.steps
            if (r.success && r.output.isNotBlank()) {
                parts.append("【${e.icon} ${e.name}】\n${r.output.trim()}\n\n")
                onStream?.invoke("✓ ${e.name} 完成\n")
            } else {
                parts.append("【${e.icon} ${e.name}】分析中断：${r.output}\n\n")
            }
        }
        val finalAnswer: String = if (used.size > 1) {
            onStream?.invoke("\n🧩 父Agent 汇总…\n")
            val summaryTask = "以下是多位子Agent 对同一需求的独立结论。" +
                "请交叉校验、合并重复，输出一份最终答复：" +
                "先直接回答用户需求，再给 1~3 条可执行建议与风险（600 字内）：\n\n" +
                "用户需求：$userMessage\n\n$parts"
            val s = react(summaryTask, ctx, maxSteps = 1)
            totalSteps += s.steps
            if (s.success && s.output.isNotBlank()) s.output else parts.toString()
        } else parts.toString()
        return ChatAgentResult(
            success = true,
            response = finalAnswer.trim(),
            intent = "MULTI_AGENT",
            steps = totalSteps
        )
    }

    /**
     * 处理用户消息
     */
    suspend fun handleMessage(
        userMessage: String,
        onStream: ((String) -> Unit)? = null
    ): ChatAgentResult {
        val ctx = AgentContext().apply {
            put("user_message", userMessage)
            put("session_id", sessionId)
        }

        // 判断意图，决定使用哪种模式
        val intent = detectIntent(userMessage)

        // 同步提取机构线索（仅在非分析意图时触发，避免「分析机构动态」等请求误触发）
        val instResult = if (intent != UserIntent.STOCK_ANALYSIS) {
            tryExtractInstitutionalTips(userMessage)
        } else null

        val baseResult = when (intent) {
            UserIntent.INDEX_ANALYSIS -> {
                // 提取指数名称/代码
                val indexInfo = extractIndexInfo(userMessage)
                if (indexInfo != null) {
                    val result = IndexAnalysisAgent.analyze(context, indexInfo.first, indexInfo.second)
                    if (result != null) {
                        ChatAgentResult(
                            success = true,
                            response = "📊 ${result.indexName} 技术面分析\n\n${result.summary}\n\n${result.technicalView}\n\n🔮 短线预判：${result.prediction}\n\n⚠️ 风险提示：\n${result.risks.joinToString("\n") { "• $it" }}",
                            intent = intent.name
                        )
                    } else {
                        ChatAgentResult(success = false, response = "暂无指数历史数据，请先确保已导入历史数据。", intent = intent.name)
                    }
                } else {
                    ChatAgentResult(success = false, response = "请提供具体的指数名称（如上证指数、深证成指）。", intent = intent.name)
                }
            }
            UserIntent.STOCK_PICKING -> {
                // 选股：使用 Plan-and-Execute
                val result = pickingAgent.pickStocks()
                ChatAgentResult(
                    success = result.success,
                    response = formatPickingResponse(result),
                    intent = intent.name,
                    data = mapOf("recommendations" to result.recommendations)
                )
            }
            UserIntent.STOCK_ANALYSIS -> {
                // 分析：提取所有股票实体（支持名称和 múltiple stocks）
                val entities = extractAllStockEntities(userMessage)

                if (entities.isNotEmpty()) {
                    val coreMode = when (analysisMode) {
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.QUICK -> com.chin.stockanalysis.agent.core.AnalysisMode.QUICK
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.DEEP -> com.chin.stockanalysis.agent.core.AnalysisMode.DEEP
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.EXPERT -> com.chin.stockanalysis.agent.core.AnalysisMode.EXPERT
                    }
                    val modeLabel = when (analysisMode) {
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.QUICK -> "⚡ 快速分析"
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.DEEP -> "🔍 多 agent 流水线深度分析"
                        com.chin.stockanalysis.ui.ChatTabFragment.AnalysisMode.EXPERT -> "📊 个股全周期深度分析"
                    }

                    if (entities.size == 1) {
                        // 单股票：完整分析
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
                                else "${modeLabel} 分析失败：${result.errorMessage}",
                            intent = intent.name,
                            data = mapOf("unifiedResult" to result)
                        )
                    } else {
                        // 多股票：逐一分析后合并摘要
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
                        sb.appendLine("## 📊 多股票对比分析（${results.size} 只）")
                        sb.appendLine()
                        for ((entity, result) in results) {
                            val normalizedCode = StockAnalysisAgent.normalizeStockCode(entity.code)
                            if (result != null && result.success) {
                                sb.appendLine("### ${entity.name}（$normalizedCode）")
                                sb.appendLine(result.summaryText)
                                sb.appendLine()
                            } else {
                                sb.appendLine("### ${entity.name}（$normalizedCode）— 分析失败")
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
                        response = "请提供具体的股票代码（如 600519）或名称，我来为您分析。",
                        intent = intent.name
                    )
                }
            }
            UserIntent.MARKET_BRIEF -> {
                // 市场简报
                val brief = generateMarketBrief()
                ChatAgentResult(
                    success = true,
                    response = brief,
                    intent = intent.name
                )
            }
            else -> {
                // 一般对话：若命中 ≥2 个领域专家 → 自动拆成多 Agent 依次执行再汇总；
                // 否则退回单 Agent ReAct。
                val experts = AgentHub.match(userMessage)
                if (experts.size >= 2 && userMessage.length >= 10) {
                    return orchestrateWithExperts(userMessage, ctx, experts, onStream)
                }
                val result = react(userMessage, ctx, maxSteps = 4)
                ChatAgentResult(
                    success = result.success,
                    response = result.output,
                    intent = intent.name,
                    steps = result.steps
                )
            }
        }

        // 如果提取到机构线索，附加板块分析到回复
        if (instResult != null && instResult.detectedSectors.isNotEmpty()) {
            val sectorAnalysis = buildInstitutionalSectorAnalysis(instResult, userMessage)
            return baseResult.copy(
                response = baseResult.response + "\n\n" + sectorAnalysis
            )
        }

        return baseResult
    }

    /**
     * 构建机构推荐板块分析回复
     * 包含：检测到的板块、股票归属、大盘趋势判断、超短/短线跟进建议
     */
    private suspend fun buildInstitutionalSectorAnalysis(
        extraction: InstitutionalTipExtraction,
        originalMessage: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("━━━━━━━━━━━━━━━━━━")
        sb.appendLine("🏦 机构线索板块分析")
        sb.appendLine("━━━━━━━━━━━━━━━━━━")

        // 1. 显式提及的板块
        if (extraction.textSectors.isNotEmpty()) {
            sb.appendLine("📌 消息提及板块：${extraction.textSectors.joinToString("、")}")
        }

        // 2. 股票→板块归属
        sb.appendLine()
        sb.appendLine("📊 股票板块归属：")
        for ((code, name) in extraction.stocks) {
            val sectors = extraction.stockSectors[code] ?: emptyList()
            val display = if (name.isNotBlank()) "$name(${code.takeLast(6)})" else code.takeLast(6)
            if (sectors.isNotEmpty()) {
                sb.appendLine("  • $display → ${sectors.joinToString("/")}")
            } else {
                sb.appendLine("  • $display → 未匹配到板块")
            }
        }

        // 3. 合并板块列表
        sb.appendLine()
        sb.appendLine("🎯 综合板块判断：${extraction.detectedSectors.joinToString("、")}")

        // 4. 大盘趋势判断
        try {
            val appCtx = context.applicationContext
            val mktCtx = com.chin.stockanalysis.strategy.sector.StrategyMarketContext
                .build(appCtx, java.time.LocalDate.now().toString())
            val env = mktCtx.indexSnapshot.tripleVote
            val envLabel = when (env) {
                "BULLISH" -> "牛市偏多 🐂"
                "OSCILLATION" -> "震荡市 ⚖️"
                "BEARISH" -> "熊市偏空 🐻"
                else -> "未知 $env"
            }
            sb.appendLine()
            sb.appendLine("📈 当前大盘环境：$envLabel")

            // 5. 跟进建议
            sb.appendLine()
            sb.appendLine("💡 跟进建议：")
            when (env) {
                "BULLISH" -> {
                    sb.appendLine("  • 大盘偏多，机构推荐板块可积极跟进")
                    sb.appendLine("  • 建议在【短线】或【中线】周期建仓（极速档并入短线）")
                    sb.appendLine("  • 优先关注：${extraction.detectedSectors.take(3).joinToString("、")}")
                }
                "OSCILLATION" -> {
                    sb.appendLine("  • 震荡市中机构推荐仅供参考，注意仓位控制")
                    sb.appendLine("  • 建议在【短线】周期轻仓试探，设好止损")
                    sb.appendLine("  • 优先关注有资金持续流入的板块：${extraction.detectedSectors.take(2).joinToString("、")}")
                }
                "BEARISH" -> {
                    sb.appendLine("  • ⚠️ 熊市环境，机构推荐需谨慎对待")
                    sb.appendLine("  • 建议仅观察，不急于跟进")
                    sb.appendLine("  • 若必须操作，仅限短线极速档（日内/隔日）轻仓做T，严控仓位<30%")
                }
                else -> {
                    sb.appendLine("  • 大盘方向不明，建议在【短线】周期观察")
                }
            }
        } catch (_: Exception) {
            sb.appendLine("📈 大盘环境：暂无法判断")
            sb.appendLine("💡 建议在【短线】周期观察机构推荐板块动向")
        }

        // 6. 已写入数据库提示
        sb.appendLine()
        sb.appendLine("✅ ${extraction.stockCount} 只机构推荐股票已记录，有效期3天")
        sb.appendLine("   板块轮动预测器已接收线索（20%权重）")

        return sb.toString().trimEnd()
    }

    private fun detectIntent(message: String): UserIntent {
        val lower = message.lowercase()

        // 指数查询意图识别
        val indexKeywords = listOf("上证", "深证", "创业板", "科创板", "沪深300", "上证50", "大盘", "指数")
        val hasIndex = indexKeywords.any { lower.contains(it) }
        if (hasIndex) return UserIntent.INDEX_ANALYSIS

        // 优先用 StockEntityExtractor 做本地词典匹配
        try {
            val entities = com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(message)
            if (entities.isNotEmpty()) {
                return when {
                    lower.contains("选股") || lower.contains("推荐") || lower.contains("有什么好股票") || lower.contains("买什么")
                        -> UserIntent.STOCK_PICKING
                    // 已找到股票实体 + 明确分析意图 → STOCK_ANALYSIS（优先于市场简报）
                    lower.contains("分析") || lower.contains("投资价值") || lower.contains("详细") ||
                    lower.contains("基本面") || lower.contains("技术面") || lower.contains("资金面") ||
                    lower.contains("风险评估") || lower.contains("买入") || lower.contains("走势")
                        -> UserIntent.STOCK_ANALYSIS
                    // 只有在大盘/市场词汇出现且无具体分析要求时才显示简报
                    lower.contains("大盘") || lower.contains("市场简报") || lower.contains("行情概览")
                        -> UserIntent.MARKET_BRIEF
                    else -> UserIntent.STOCK_ANALYSIS
                }
            }
        } catch (_: Exception) { /* Trie 未构建，继续 */ }

        return when {
            lower.contains("选股") || lower.contains("推荐") || lower.contains("有什么好股票") || lower.contains("买什么") -> UserIntent.STOCK_PICKING
            lower.contains("分析") || lower.contains("怎么样") || lower.contains("看一下") || lower.contains("点评") -> UserIntent.STOCK_ANALYSIS
            lower.contains("大盘") || lower.contains("市场") || lower.contains("行情") || lower.contains("走势") -> UserIntent.MARKET_BRIEF
            Regex("(sh|sz|bj)?\\d{6}").containsMatchIn(lower) -> UserIntent.STOCK_ANALYSIS
            else -> UserIntent.GENERAL_CHAT
        }
    }

    private fun extractAllStockEntities(message: String): List<com.chin.stockanalysis.ai.StockEntityExtractor.ExtractedEntity> {
        try {
            val entities = com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(message)
            if (entities.isNotEmpty()) return entities
        } catch (_: Exception) { /* Trie 未构建，继续 */ }

        // 降级：正则提取代码
        val codes = Regex("(sh|sz|bj)?(\\d{6})").findAll(message).map { it.groupValues[2] }.toList()
        return codes.map { com.chin.stockanalysis.ai.StockEntityExtractor.ExtractedEntity(
            text = it, code = it, name = it,
            matchType = com.chin.stockanalysis.ai.StockEntityExtractor.MatchType.EXACT_CODE,
            confidence = 1.0f
        ) }
    }

    private fun extractIndexInfo(message: String): Pair<String, String>? {
        val indexMap = mapOf(
            "上证指数" to "sh000001", "上证" to "sh000001", "大盘" to "sh000001",
            "深证成指" to "sz399001", "深证" to "sz399001",
            "创业板指" to "sz399006", "创业板" to "sz399006",
            "科创50" to "sh000688", "科创板" to "sh000688",
            "沪深300" to "sh000300", "上证50" to "sh000016",
            "中证500" to "sh000905", "中证1000" to "sh000852"
        )
        for ((name, code) in indexMap) {
            if (message.contains(name)) return Pair(code, name)
        }
        return null
    }

    private fun formatPickingResponse(result: com.chin.stockanalysis.agent.stock.StockPickingResult): String {
        return buildString {
            appendLine("🎯 选股 Agent 为您推荐以下股票：")
            appendLine()
            result.recommendations.forEachIndexed { index, rec ->
                appendLine("${index + 1}. **${rec.name} (${rec.code})** — 评分 ${rec.score}分")
                appendLine("   命中策略: ${rec.strategies.joinToString(", ")}")
                appendLine("   理由: ${rec.reason}")
                appendLine()
            }
            if (result.riskWarning.isNotBlank()) {
                appendLine("⚠️ 风险提示: ${result.riskWarning}")
            }
        }
    }

    private fun formatAnalysisResponse(result: com.chin.stockanalysis.agent.stock.StockAnalysisResult): String {
        return buildString {
            appendLine("📊 **${result.stockCode} 分析报告**")
            appendLine()
            appendLine("综合评分: ${result.overallScore}分 | 建议: ${result.recommendation} | 置信度: ${result.confidence}")
            appendLine()
            appendLine("各维度评分:")
            appendLine("- 技术面: ${result.technicalScore}分")
            appendLine("- 基本面: ${result.fundamentalScore}分")
            appendLine("- 资金面: ${result.fundFlowScore}分")
            appendLine()
            appendLine("分析理由: ${result.reasoning}")
            if (result.targetPrice.isNotBlank()) {
                appendLine("目标价: ${result.targetPrice} | 止损位: ${result.stopLoss}")
            }
            if (result.riskFactors.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ 风险因素:")
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
                    appendLine("📈 **今日市场简报** ($today)")
                    appendLine()
                    appendLine("大盘环境: ${if (avgChange > 1) "强势" else if (avgChange > 0) "偏多" else if (avgChange > -1) "偏弱" else "弱势"}（平均 ${"%.2f".format(avgChange)}%）")
                    appendLine("涨跌家数: 上涨 $upCount / 下跌 $downCount")
                    appendLine()
                    appendLine("涨幅榜 TOP 5:")
                    topGainers.forEachIndexed { i, s ->
                        appendLine("${i + 1}. ${s.name} (${s.code}): +${"%.2f".format(s.changePct)}%")
                    }
                }
            } catch (e: Exception) {
                "暂时无法获取市场数据，请稍后再试。"
            }
        }
    }

    // ═══ 机构线索提取 ═══

    private val INST_KEYWORDS = listOf(
        "机构", "研报", "目标价", "买入评级", "增持评级", "推荐买入",
        "券商", "基金", "调研", "机构调研", "主力", "庄家", "游资",
        "龙虎榜", "机构席位", "量化", "融资", "北向资金",
        // 扩展：机构推荐消息常见用语
        "案例股", "教学案例", "调仓换股", "重点留意", "热点机会",
        "新主线", "布局", "抄底", "加仓", "减仓", "止盈",
        "投资顾问", "执业编号", "执业证书", "内部服务",
        "行情已经", "板块方面", "短期可以", "重点关注"
    )

    /**
     * 从用户对话中提取机构线索，写入 institutional_tips 表。
     * 触发条件：消息包含机构相关关键词 + 至少一个股票代码/名称。
     * 有效期默认 3 天。
     *
     * @return 机构线索提取结果（含板块检测），若无线索返回 null
     */
    private suspend fun tryExtractInstitutionalTips(message: String): InstitutionalTipExtraction? {
        try {
            val hasInstKeyword = INST_KEYWORDS.any { message.contains(it) }
            if (!hasInstKeyword) return null

            // 提取股票实体
            val entities = try {
                com.chin.stockanalysis.ai.StockEntityExtractor.extractSync(message)
            } catch (_: Exception) { emptyList() }

            // 降级：正则提取代码（支援 sh/sz/bj 前缀 和 纯6位数字）
            val stocks: List<Pair<String, String>> = if (entities.isNotEmpty()) {
                entities.map { it.code to it.name }
            } else {
                // 先尝试带前缀的代码
                val prefixed = Regex("(sh|sz|bj)(\\d{6})").findAll(message).map {
                    it.value to ""
                }.toList()
                if (prefixed.isNotEmpty()) prefixed
                else {
                    // 降级：纯6位数字代码（需要排除非股票数字如日期、电话等）
                    Regex("(?<!\\d)(\\d{6})(?!\\d)").findAll(message).map { match ->
                        val code = match.value
                        // 根据代码首位判断市场前缀
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

            // 判断线索类型
            val tipType = when {
                message.contains("目标价") -> "target"
                message.contains("评级") || message.contains("增持") -> "rating"
                else -> "research"
            }

            // 板块检测：结合文本关键词 + 股票板块反查
            val sectorDetection = com.chin.stockanalysis.ai.SectorDetector
                .detect(message, stocks, context)

            val tips = stocks.map { (code, name) ->
                // 为每只股票确定最佳板块
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
            Log.i(TAG, "🏦 提取机构线索: ${tips.size} 条 (${tips.joinToString { "${it.stockCode}(${it.sector})" }})")

            return InstitutionalTipExtraction(
                stockCount = tips.size,
                stocks = stocks.map { it.first to it.second },
                detectedSectors = sectorDetection.allSectors,
                textSectors = sectorDetection.textSectors,
                stockSectors = sectorDetection.stockSectors
            )
        } catch (e: Exception) {
            Log.w(TAG, "机构线索提取失败: ${e.message}")
            return null
        }
    }
}

/** 机构线索提取结果 */
data class InstitutionalTipExtraction(
    val stockCount: Int,
    val stocks: List<Pair<String, String>>,
    val detectedSectors: List<String>,
    val textSectors: List<String>,
    val stockSectors: Map<String, List<String>>
)

/** 对话结果 */
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
/** 股票查询工具 */
class StockQueryTool(private val ctx: Context) : AgentTool {
    override val name = "stock_query"
    override val description = "查询股票基本信息和最新行情"
    override val parameters = listOf("query")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val localCtx = ctx
        return withContext(Dispatchers.IO) {
            try {
                val rawQuery = params["query"] ?: params["stock_code"]
                    ?: return@withContext "错误: 未提供股票名称或代码"
                // 解析：可能是代码（6位数字/sh+代码）或名称
                val code = if (rawQuery.matches(Regex("\\d{6}"))) {
                    rawQuery
                } else if (rawQuery.matches(Regex("(?i)(sh|sz|bj)\\d{6}"))) {
                    rawQuery.takeLast(6)
                } else {
                    // 名称 → 通过 StockEntityExtractor 解析为代码
                    val resolved = com.chin.stockanalysis.ai.StockEntityExtractor.resolveSync(rawQuery)
                    resolved ?: return@withContext "错误: 未找到股票「$rawQuery」"
                }
                val db = StockDatabase.getInstance(localCtx)
                val basic = db.stockBasicDao().getByCode(code)
                val today = TradingDayPickerView.recentTradingDay().toString()
                val snapshot = db.dailySnapshotDao().getByDateAndCode(today, code)

                if (basic == null && snapshot == null) return@withContext "未找到股票 $code"

                buildString {
                    appendLine("【股票查询】 $code")
                    basic?.let {
                        appendLine("- 名称: ${it.name}")
                        appendLine("- 主营: ${it.business}")
                    }
                    snapshot?.let {
                        appendLine("- 最新价: ${it.close} (${if(it.changePct>=0)"+" else ""}${"%.2f".format(it.changePct)}%)")
                        appendLine("- 成交额: ${"%.0f".format(it.amount)}万")
                        appendLine("- 换手率: ${"%.2f".format(it.turnoverRate)}%")
                    }
                }
            } catch (e: Exception) {
                "错误: 查询失败: ${e.message}"
            }
        }
    }
}

/** 市场简报工具 */
class MarketBriefTool(private val ctx: Context) : AgentTool {
    override val name = "market_brief"
    override val description = "获取当日市场简要概况"
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

                "今日市场: 平均 ${"%.2f".format(avgChange)}%, 上涨 $upCount 家, 下跌 $downCount 家"
            } catch (e: Exception) {
                "错误: 获取市场简报失败: ${e.message}"
            }
        }
    }
}

/** 意图解析工具 */
class IntentParseTool : AgentTool {
    override val name = "intent_parse"
    override val description = "解析用户输入的意图（选股/分析/闲聊）"
    override val parameters = listOf("message")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val message = params["message"] ?: return "错误: 未提供消息"
        val lower = message.lowercase()
        val intent = when {
            lower.contains("选股") || lower.contains("推荐") -> "STOCK_PICKING"
            lower.contains("分析") || Regex("\\d{6}").containsMatchIn(lower) -> "STOCK_ANALYSIS"
            lower.contains("大盘") || lower.contains("市场") -> "MARKET_BRIEF"
            else -> "GENERAL_CHAT"
        }
        return "意图识别结果: $intent"
    }
}

/** 龙头股池管理工具 — 支持对话式增删改查 */
class LeaderPoolManageTool(private val ctx: Context) : AgentTool {
    override val name = "leader_pool_manage"
    override val description = "管理龙头股池：列出板块、添加/移除板块、添加/移除股票、标记概念炒作。参数: action=list|add_sector|remove_sector|add_stock|remove_stock|set_concept, sector_name, sub_sector_name, stock_code, is_concept(true/false)"
    override val parameters = listOf("action", "sector_name", "sub_sector_name", "stock_code", "is_concept")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val action = params["action"] ?: return "错误: 缺少 action 参数"
        val sectorName = params["sector_name"] ?: ""
        val subSectorName = params["sub_sector_name"] ?: ""
        val stockCode = params["stock_code"] ?: ""
        val isConcept = params["is_concept"]?.lowercase() == "true"

        return when (action) {
            "list" -> {
                val sectors = LeaderStockPool.listSectors(ctx)
                val details = sectors.joinToString("\n") { name ->
                    val cfg = LeaderStockPool.getAllConfigs(ctx).find { it.name == name }
                    val tag = if (cfg?.isConcept == true) "[概念]" else "[产业]"
                    val stocks = cfg?.subSectors?.sumOf { it.stocks.size } ?: 0
                    "- $tag $name ($stocks 只)"
                }
                "当前龙头股池共 ${sectors.size} 个板块:\n$details"
            }

            "list_detail" -> {
                if (sectorName.isBlank()) return "错误: 请提供 sector_name"
                val subs = LeaderStockPool.listSubSectors(ctx, sectorName)
                if (subs.isEmpty()) return "板块 [$sectorName] 不存在或为空"
                subs.joinToString("\n") { (subName, stocks) ->
                    "  [$subName]: ${stocks.joinToString(", ")}"
                }
            }

            "add_sector" -> {
                if (sectorName.isBlank()) return "错误: 请提供 sector_name"
                val ok = LeaderStockPool.addSector(ctx, sectorName, isConcept)
                if (ok) "✅ 已添加板块 [$sectorName]${if (isConcept) " (标记为概念)" else ""}"
                else "⚠️ 板块 [$sectorName] 已存在"
            }

            "remove_sector" -> {
                if (sectorName.isBlank()) return "错误: 请提供 sector_name"
                val ok = LeaderStockPool.removeSector(ctx, sectorName)
                if (ok) "✅ 已移除板块 [$sectorName]"
                else "⚠️ 板块 [$sectorName] 不存在"
            }

            "add_stock" -> {
                if (sectorName.isBlank() || subSectorName.isBlank() || stockCode.isBlank())
                    return "错误: 请提供 sector_name, sub_sector_name, stock_code"
                val ok = LeaderStockPool.addStock(ctx, sectorName, subSectorName, stockCode)
                if (ok) "✅ 已添加 [$stockCode] 到 [$sectorName / $subSectorName]"
                else "⚠️ 添加失败（可能已存在或板块不存在）"
            }

            "remove_stock" -> {
                if (sectorName.isBlank() || subSectorName.isBlank() || stockCode.isBlank())
                    return "错误: 请提供 sector_name, sub_sector_name, stock_code"
                val ok = LeaderStockPool.removeStock(ctx, sectorName, subSectorName, stockCode)
                if (ok) "✅ 已从 [$sectorName / $subSectorName] 移除 [$stockCode]"
                else "⚠️ 移除失败（股票不存在）"
            }

            "set_concept" -> {
                if (sectorName.isBlank()) return "错误: 请提供 sector_name"
                val ok = LeaderStockPool.setSectorConcept(ctx, sectorName, isConcept)
                if (ok) "✅ 已将 [$sectorName] 标记为${if (isConcept) "概念炒作" else "产业主线"}"
                else "⚠️ 板块 [$sectorName] 不存在"
            }

            "reset" -> {
                LeaderStockPool.resetToDefault(ctx)
                "✅ 龙头股池已重置为默认配置"
            }

            else -> "错误: 未知 action [$action]。支持: list, list_detail, add_sector, remove_sector, add_stock, remove_stock, set_concept, reset"
        }
    }
}

/** AI 一键选股工具：聚合全部候选来源（龙头+备选池+AI精选+自选）后四周期分类 */
class AiSelectionTool(private val ctx: Context) : AgentTool {
    override val name = "ai_selection"
    override val description = "一键 AI 选股：聚合龙头股池+备选池+AI精选+用户自选，输出短线(含极速档)/中线/长线各周期入选股票。参数: top_n(可选，每周期返回前N只，默认5)"
    override val parameters = listOf("top_n")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val topN = params["top_n"]?.toIntOrNull() ?: 5
        return withContext(Dispatchers.IO) {
            try {
                val classifier = com.chin.stockanalysis.strategy.trade.UnifiedStockClassifier(ctx)
                val scan = classifier.classifyAll()
                if (scan.byPeriod.isEmpty()) return@withContext "当前无候选股通过筛选"
                val periodLabels = mapOf(
                    "ultra_short" to "短线⚡(极速档)", "short" to "短线",
                    "mid" to "中线", "long" to "长线")
                buildString {
                    appendLine("🎯 AI 一键选股结果（候选 ${scan.candidates.size} 只，命中 ${scan.classified.size} 只）:")
                    for ((period, stocks) in scan.byPeriod) {
                        appendLine()
                        appendLine("【${periodLabels[period] ?: period}】")
                        stocks.take(topN).forEachIndexed { i, s ->
                            appendLine("  ${i + 1}. ${s.name}(${s.code.takeLast(6)}) 现价${s.price} " +
                                "通过${s.result.passCount}/${s.result.totalChecks} 趋势${if (s.isTrend) "跟涨" else "粘合"}")
                        }
                    }
                }
            } catch (e: Exception) {
                "错误: AI 选股失败: ${e.message}"
            }
        }
    }
}

/** 板块轮动工具：月度前瞻预测 Top 板块 */
class SectorRotationTool(private val ctx: Context) : AgentTool {
    override val name = "sector_rotation"
    override val description = "板块轮动信号：月度前瞻预测 Top 板块与轮动方向，输出综合趋势分数。参数: 无"
    override val parameters = listOf<String>()

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        return withContext(Dispatchers.IO) {
            try {
                val report = com.chin.stockanalysis.strategy.market.SectorTrendForecaster(ctx).forecast()
                buildString {
                    appendLine("🔄 板块轮动月度前瞻（目标月 ${report.targetMonth}，生成于 ${report.generatedDate}）:")
                    report.sectors.sortedByDescending { it.compositeScore }
                        .take(8)
                        .forEachIndexed { i, f ->
                            appendLine("  ${i + 1}. ${f.sectorName} 分数${f.compositeScore} " +
                                "方向${f.trend.label} 置信度${f.confidence} " +
                                "证据:${f.evidence.take(2).joinToString("; ")}")
                        }
                    if (report.narrative.isNotBlank()) {
                        appendLine()
                        appendLine("📝 综述: ${report.narrative.take(200)}")
                    }
                }
            } catch (e: Exception) {
                "错误: 板块轮动预测失败: ${e.message}"
            }
        }
    }
}

/** 持仓健康度工具：盈亏 / 建议 / 组合警告 */
class PortfolioHealthTool(private val ctx: Context) : AgentTool {
    override val name = "portfolio_health"
    override val description = "持仓评估：输出当前持仓盈亏、健康度、操作建议与组合警告。参数: period(可选，限定周期 ultra_short/short/mid/long，默认全部)"
    override val parameters = listOf("period")

    override suspend fun execute(params: Map<String, String>, agentCtx: AgentContext): String {
        val periodFilter = params["period"] ?: ""
        return withContext(Dispatchers.IO) {
            try {
                val db = StockDatabase.getInstance(ctx)
                val dao = db.realPositionDao()
                val positions = if (periodFilter.isBlank()) dao.getAllActive()
                    else dao.getByPeriod(periodFilter)
                if (positions.isEmpty()) return@withContext "当前${if (periodFilter.isNotBlank()) "[" + periodFilter + "]" else ""}无持仓"
                val today = TradingDayPickerView.recentTradingDay().toString()
                var totalCost = 0.0
                var totalValue = 0.0
                val warns = mutableListOf<String>()
                buildString {
                    appendLine("💼 持仓评估（${positions.size} 只）:")
                    for (p in positions) {
                        val price = if (p.currentPrice > 0) p.currentPrice else
                            db.dailySnapshotDao().getByDateAndCode(today, p.stockCode)?.close ?: p.avgBuyPrice
                        val pnl = (price - p.avgBuyPrice) / p.avgBuyPrice * 100
                        val cost = p.avgBuyPrice * p.quantity
                        val value = price * p.quantity
                        totalCost += cost
                        totalValue += value
                        val action = when {
                            pnl <= -8 -> "止损离场"
                            pnl <= -4 -> "减仓"
                            pnl >= 15 -> "止盈观察"
                            else -> "持有"
                        }
                        if (pnl <= -8) warns.add("${p.stockName}(${p.stockCode.takeLast(6)}) 亏损${"%.1f".format(pnl)}% 触发止损线")
                        appendLine("  ${p.stockName}(${p.stockCode.takeLast(6)}) [${p.periodType}] " +
                            "成本${p.avgBuyPrice} 现价${price} 盈亏${"%.1f".format(pnl)}% → $action")
                    }
                    if (totalCost > 0) {
                        appendLine()
                        appendLine("📊 组合: 成本 ${"%,.0f".format(totalCost)} / 市值 ${"%,.0f".format(totalValue)} / " +
                            "总盈亏 ${"%.1f".format((totalValue - totalCost) / totalCost * 100)}%")
                    }
                    if (warns.isNotEmpty()) {
                        appendLine()
                        appendLine("⚠️ 警告:")
                        warns.forEach { appendLine("  • $it") }
                    }
                }
            } catch (e: Exception) {
                "错误: 持仓评估失败: ${e.message}"
            }
        }
    }
}
