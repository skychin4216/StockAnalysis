# 后端伪代码：AI 分析处理器与 Prompt 构建

目标：提供 API handler 与 Prompt builder 的伪实现，供后端工程师参考并快速实现 /api/ai/analyze_stock。

## Handler 概要 (Kotlin 风格伪码)

suspend fun handleAnalyzeStock(request: AnalyzeStockRequest): AnalyzeStockResponse {
    // 1. 记录请求并创建 exec_id
    val execId = uuid()
    logExecStart(execId, request)

    try {
        // 2. 并行拉取依赖数据
        val overviewDeferred = async { stockService.getOverview(request.symbol, request.date) }
        val peersDeferred = async { stockService.getSectorPeers(overviewDeferred.await().sector, limit = 10) }
        val orderbookDeferred = async { marketService.getOrderbook(request.symbol, depth = 5) }
        val newsDeferred = async { newsService.getRecentNews(request.symbol, days = 30) }
        val financialsDeferred = async { financeService.getFinancials(request.symbol) }

        val overview = overviewDeferred.await()
        val peers = peersDeferred.await()
        val orderbook = orderbookDeferred.await()
        val news = newsDeferred.await()
        val financials = financialsDeferred.await()

        // 3. 构建 prompt
        val prompt = PromptBuilder.buildAnalyzeStockPrompt(
            overview = overview,
            peers = peers,
            orderbook = orderbook,
            news = news,
            financials = financials,
            mode = request.mode
        )

        // 4. 调用 LLM（stream 或 非流式）
        val provider = apiConfigManager.createCurrentProvider() ?: throw Exception("No provider")
        val rawResponse = provider.sendMessageSync(systemPrompt = prompt.system, messages = prompt.messages)

        // 5. 解析并校验 JSON
        val parsed = JsonParser.safeParse(rawResponse)
        if (!SchemaValidator.validate(parsed, AnalyzeStockSchema)) {
            // 如果解析失败或不合规，回退到规则引擎输出
            val fallback = buildRuleBasedFallback(overview, peers)
            saveExecResult(execId, rawResponse, parsed, status = "fallback")
            return AnalyzeStockResponse(execId = execId, data = fallback, status = "fallback")
        }

        // 6. 一致性校验（与原始数据冲突时调整 confidence）
        val validated = consistencyCheckAndAnnotate(parsed, overview, peers, orderbook)

        // 7. 保存并返回
        saveExecResult(execId, rawResponse, validated, status = "completed")
        return AnalyzeStockResponse(execId = execId, data = validated, status = "completed")

    } catch (e: Exception) {
        logExecError(execId, e)
        saveExecResult(execId, error = e.message, status = "failed")
        throw e
    }
}


## PromptBuilder 伪实现

object PromptBuilder {
    fun buildAnalyzeStockPrompt(
        overview: StockOverview,
        peers: List<StockOverview>,
        orderbook: OrderbookSnapshot,
        news: List<NewsItem>,
        financials: Financials,
        mode: String?
    ): Prompt {
        val system = "你是专业的A股量化分析师。仅基于下面提供的数据输出 JSON 严格遵循 schema。不要编造事实。"

        val messages = mutableListOf<String>()
        messages.add("输入数据:\n${overview.toShortString()}")
        messages.add("板块同行(Top ${peers.size}):\n" + peers.joinToString("\n") { it.toShortString() })
        messages.add("订单簿摘要:\n" + orderbook.summary())
        messages.add("财务概要:\n${financials.summary()}")
        messages.add("新闻要点(近30天):\n" + news.take(10).joinToString("\n") { it.title + " - " + it.sentiment })

        messages.add("\n请输出 JSON：{symbol, selected_date, market_outlook, confidence, score, top_peers, orderbook_summary, financial_highlights, supply_chain_related, recommendations, table_view}")

        return Prompt(system = system, messages = messages.map { Message(role = "user", content = it) })
    }
}


## 解析与校验建议
- 使用严格的 JSON schema（例如 ajv / json-schema）校验 LLM 输出
- 对数值字段设定合理边界（score: 0-100，confidence: 0-1）
- 当解析失败时，记录 rawResponse 并返回规则引擎的备选输出


## 日志与审计
- 保存 exec_id、requester、prompt hash、rawResponse、parsedJson、providerId、cost（若可得）
- 对重要事件（解析失败、低置信度、与数据冲突）触发告警


## 速成建议
- 先实现 sync 版本以便快速上线（等待 LLM 返回），随后再支持流式响应用于 UI 的逐字展示
- 限制并发与速率，记录调用成本并在管理后台展示
