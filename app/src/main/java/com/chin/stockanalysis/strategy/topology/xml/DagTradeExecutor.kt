package com.chin.stockanalysis.strategy.topology.xml

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.topology.pipelines.OrderGenerationResult
import com.chin.stockanalysis.strategy.topology.pipelines.PositionMergeResult
import com.chin.stockanalysis.strategy.topology.pipelines.RotationPenaltyResult
import com.chin.stockanalysis.strategy.topology.pipelines.SwapWeakResult
import com.chin.stockanalysis.strategy.topology.pipelines.HoldingGuardResult
import com.chin.stockanalysis.strategy.topology.core.StockFlowRecord
import com.chin.stockanalysis.strategy.backtest.StrategyOptimizer
import com.chin.stockanalysis.strategy.trade.StrategyTradeFittingParamEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## 通用 DAG 交易执行器
 *
 * 将「UseCaseLoader.run → 提取订单/持仓/换股 → 保存自选股 → 构建摘要」
 * 的通用流程封装为一处，供超短线/短线/中线/长线四个 Fragment 复用。
 *
 * 各周期只需提供：
 * - [useCaseId]    对应 `usecases/<id>_usecase.xml`（如 "ultra_short"）
 * - [orderType]    订单类型（如 "UltraShortQuant"）
 * - [importDays]   数据不足时的历史导入天数
 *
 * DAG Pipeline 内部的 `generate_orders` / `position_merge` / `swap_weak`
 * 节点会自行将订单写入 `strategy_trade_order` 表，本类只做结果提取与摘要。
 */
object DagTradeExecutor {

    private const val TAG = "DagTradeExecutor"

    /**
     * DAG 执行结果摘要。
     *
     * @property success           Pipeline 整体是否成功
     * @property ordersCount       生成订单笔数
     * @property mergeSummary      持仓合并摘要（可空）
     * @property swapSummary       腾龙换鸟摘要（可空）
     * @property guardSummary      持仓风控摘要（可空）
     * @property savedWatchlist    是否已保存到自选股
     * @property stockFlowLines    各节点股票流动日志（供 UI 展示）
     * @property totalElapsedMs    总耗时
     * @property pipelineNames     执行的 Pipeline 名称列表
     * @property errors            错误信息
     * @property uiText            预构建的 UI 显示文本
     */
    /** 单个 Node 的流动详情（含实际股票代码） */
    data class NodeFlowDetail(
        val nodeId: String,
        val nodeName: String,
        val inputCount: Int,
        val outputCount: Int,
        val filterCount: Int,
        val filterReason: String,
        val inputCodes: List<String>,
        val outputCodes: List<String>,
        val elapsedMs: Long = 0,
        val success: Boolean = true,
        val errorMsg: String = ""
    )

    data class DagExecResult(
        val success: Boolean,
        val ordersCount: Int,
        val mergeSummary: String,
        val swapSummary: String,
        val guardSummary: String,
        val patternSummary: String,
        val savedWatchlist: Boolean,
        val stockFlowLines: List<String>,
        val totalElapsedMs: Long,
        val pipelineNames: List<String>,
        val errors: Map<String, String>,
        val uiText: String,
        val failureAnalysis: PipelineFailureAnalyzer.FailureAnalysis = PipelineFailureAnalyzer.FailureAnalysis(isFailed = false),
        val diagnosticSummary: String = "",
        val strictEvalDetail: String = "",
        val selectedStocks: List<Triple<String, String, Int>> = emptyList(),
        val nodeFlowDetails: List<NodeFlowDetail> = emptyList()
    )

    /**
     * 执行指定周期的 DAG Pipeline。
     *
     * @param context      Android Context
     * @param useCaseId    UseCase ID（如 "ultra_short"、"short_term"、"mid_term"、"long_term"）
     * @param tradeDate    交易日（yyyy-MM-dd）
     * @param today        当前最近交易日（用于数据导入检查）
     * @param strategies   启用的策略列表（按周期过滤后传入）
     * @param orderType    订单类型（用于自选股来源标记，如 "ultra_short_dag"）
     * @param importDays   数据不足时的历史导入天数（0 表示不导入）
     * @param onNodeProgress 节点执行进度回调（可选），参数为 (pipelineName, nodeName)，供 UI 实时显示
     * @param saveAsAiOnly 非交易时间一键建仓「仅选股」模式：跳过订单落库/持仓合并/换仓/拟合，仅保存 AI 精选
     * @param seedStageOutputs 预置的 stageOutput（一键建仓：公共研判结果 n_pool/n_adaptive 等播种给周期专属 pipeline）
     * @return 执行结果摘要
     */
    suspend fun execute(
        context: Context,
        useCaseId: String,
        tradeDate: String,
        today: String,
        strategies: List<Strategy>,
        orderType: String,
        importDays: Int = 60,
        onNodeProgress: ((pipelineName: String, nodeName: String) -> Unit)? = null,
        onNodeDone: ((pipelineName: String, nodeName: String, output: Any?, flow: StockFlowRecord?) -> Unit)? = null,
        saveAsAiOnly: Boolean = false,
        autoPick: Boolean = false,
        seedStageOutputs: Map<String, Any?> = emptyMap()
    ): DagExecResult {
        if (strategies.isEmpty()) {
            return DagExecResult(
                success = false, ordersCount = 0, mergeSummary = "",
                swapSummary = "", guardSummary = "", patternSummary = "", savedWatchlist = false,
                stockFlowLines = emptyList(),
                totalElapsedMs = 0, pipelineNames = emptyList(),
                errors = mapOf("strategy" to "没有启用的策略"),
                uiText = "⚠️ 没有启用的策略"
            )
        }

        val totalStart = System.currentTimeMillis()

        // 1. 初始化 UseCaseLoader（注入策略）
        UseCaseLoader.init(context, strategies)

        // 2. 数据导入检查
        if (importDays > 0) {
            try {
                val db = StockDatabase.getInstance(context)
                val todaySnaps = db.dailySnapshotDao().getByDate(today)
                if (todaySnaps.size < 100) {
                    Log.i(TAG, "[$useCaseId] 数据不足(${todaySnaps.size}<100)，导入 $importDays 天")
                    com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(context)
                        .fetchAllHistoricalData(importDays)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$useCaseId] 数据导入检查失败（不阻塞）: ${e.message}")
            }
        }

        // 3. 执行 DAG Pipeline（saveAsAiOnly 通过 configOverrides 注入，节点内跳过订单落库/换仓/拟合）
        val result = UseCaseLoader.run(
            useCaseId, tradeDate, onNodeProgress, onNodeDone,
            configOverrides = if (saveAsAiOnly) mapOf("saveAsAiOnly" to "true") else emptyMap(),
            seedStageOutputs = seedStageOutputs
        )
        val elapsed = System.currentTimeMillis() - totalStart

        // 4. 后处理：从 nodeResults 提取订单/持仓/换股信息
        var ordersCount = 0
        var swapSummary = ""
        var guardSummary = ""
        var mergeSummary = ""
        var patternSummary = ""
        var savedWatchlist = false
        var typedGuardResult: HoldingGuardResult? = null
        var typedSwapResult: SwapWeakResult? = null
        val selectedStocks = mutableListOf<Triple<String, String, Int>>()
        // AI 精选质量闸门：非交易一键建仓（saveAsAiOnly）时按周期登记 code->score，
        // 四周期全部登记后由 AiSelectionQualityGate 统一过滤（多周期共振≥3 或 分≥85 才保留）
        val aiOnlySelected = mutableMapOf<String, Int>()

        try {
            for ((_, pipelineResult) in result.pipelineResults) {
                val nodeResults = pipelineResult.stageResults

                // 提取订单生成结果
                val ordersOutput = nodeResults["n_orders"]?.output
                if (ordersOutput is OrderGenerationResult) {
                    ordersCount = ordersOutput.orders.size
                    selectedStocks.addAll(ordersOutput.orders.map {
                        Triple(it.stockCode, it.stockName, it.scoreAtBuy)
                    })
                    Log.i(TAG, "[$useCaseId] 订单生成: $ordersCount 笔")

                    // 写入 user_watchlist 供选股区 UI 渲染完整表格（价格/时间/评分）
                    try {
                        val db = StockDatabase.getInstance(context)
                        val rawWatchlistSource = when (ordersOutput.orders.firstOrNull()?.orderType) {
                            "UltraShortQuant" -> "ultra_short"
                            "ShortTermQuant" -> "shortterm"
                            "MidTermQuant" -> "midterm"
                            "LongTermQuant" -> "long_term"
                            else -> useCaseId
                        }
                        // 自动盘中选股（autoPick）：AI 精选统一加 auto_ 前缀（供后台调度清理、监控/迁移跳过），
                        // 且不写 user_watchlist（避免覆盖用户自选盯盘状态或撑爆周期页表格）。
                        val storeSource = if (autoPick) "auto_$rawWatchlistSource" else rawWatchlistSource
                        val today = com.chin.stockanalysis.ui.TradingDayPickerView.recentTradingDay()
                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                        if (!autoPick) {
                            val watchlistEntities = ordersOutput.orders.map { order ->
                                com.chin.stockanalysis.stock.database.UserWatchlistEntity(
                                    stockCode = order.stockCode,
                                    stockName = order.stockName,
                                    source = rawWatchlistSource,
                                    addedDate = today,
                                    buyPrice = order.buyPrice,
                                    scoreAtAdd = order.scoreAtBuy,
                                    status = "WATCHING",
                                    notes = order.reason
                                )
                            }
                            if (watchlistEntities.isNotEmpty()) {
                                db.userWatchlistDao().insertAll(watchlistEntities)
                                Log.i(TAG, "[$useCaseId] 选股写入 user_watchlist: ${watchlistEntities.size} 只 (source=$rawWatchlistSource)")
                            }
                        }

                        // 同步写入 AI 精选（ai_selected_stock），统一到「AI 精选」查看
                        try {
                            val aiEntities = ordersOutput.orders.map { order ->
                                com.chin.stockanalysis.stock.database.AiSelectedStockEntity(
                                    stockCode = order.stockCode,
                                    stockName = order.stockName,
                                    source = storeSource,
                                    selectedDate = today,
                                    score = (order.scoreAtBuy).coerceIn(0, 100),
                                    reason = order.reason,
                                    buyPrice = order.buyPrice
                                )
                            }
                            if (aiEntities.isNotEmpty()) {
                                db.aiSelectedStockDao().insertAll(aiEntities)
                                Log.i(TAG, "[$useCaseId] 选股同步写入 AI 精选: ${aiEntities.size} 只 (source=$storeSource)")
                                // 记录到质量闸门登记（仅 saveAsAiOnly 模式会被消费）
                                if (saveAsAiOnly) {
                                    for (order in ordersOutput.orders) {
                                        aiOnlySelected.merge(order.stockCode, order.scoreAtBuy) { a, b -> maxOf(a, b) }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[$useCaseId] 写入 AI 精选失败: ${e.message}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[$useCaseId] 写入 user_watchlist 失败: ${e.message}")
                    }
                    // 非交易仅选股模式：订单已写入 user_watchlist + AI 精选（savedWatchlist 标记用于弹窗提示）
                    if (saveAsAiOnly) savedWatchlist = true
                }

                // 提取持仓合并结果
                val mergeOutput = nodeResults["n_merge_pos"]?.output
                if (mergeOutput is PositionMergeResult) {
                    mergeSummary = buildString {
                        appendLine("持仓合并: 新增${mergeOutput.newCount}笔, 总持仓${mergeOutput.totalHoldings}笔")
                        if (mergeOutput.updatedCodes.isNotEmpty()) {
                            appendLine("  追加: ${mergeOutput.updatedCodes.take(5).joinToString(", ")}")
                        }
                    }
                    Log.i(TAG, "[$useCaseId] $mergeSummary")
                }

                // 提取腾龙换鸟结果
                val swapOutput = nodeResults["n_swap"]?.output
                if (swapOutput is SwapWeakResult) {
                    typedSwapResult = swapOutput
                    swapSummary = buildString {
                        appendLine("腾龙换鸟: 换${swapOutput.swappedCount}笔")
                        appendLine("  换股前: ${swapOutput.beforeCount}笔 → 换股后: ${swapOutput.afterCount}笔")
                        if (swapOutput.soldStocks.isNotEmpty()) {
                            appendLine("  卖出: ${swapOutput.soldStocks.joinToString(", ")}")
                        }
                    }
                    Log.i(TAG, "[$useCaseId] $swapSummary")
                }

                // 提取持仓风控结果
                val guardOutput = nodeResults["n_guard"]?.output
                if (guardOutput is HoldingGuardResult && guardOutput.soldCount > 0) {
                    typedGuardResult = guardOutput
                    guardSummary = buildString {
                        appendLine("持仓风控: 卖出${guardOutput.soldCount}笔（评估${guardOutput.evaluatedCount}笔）")
                        appendLine("  卖出: ${guardOutput.soldStocks.joinToString(", ")}")
                    }
                    Log.i(TAG, "[$useCaseId] $guardSummary")
                }

                // 提取 K 线形态侦测结果
                @Suppress("UNCHECKED_CAST")
                val patternOutput = nodeResults["n_candle"]?.output as?
                    Map<String, List<com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.PatternMatch>>
                if (!patternOutput.isNullOrEmpty()) {
                    patternSummary = buildString {
                        appendLine("🚨 K线形态警示 🚨")
                        for ((code, patterns) in patternOutput) {
                            for (p in patterns) {
                                val emoji = if (p.direction == com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.Direction.BULLISH) "📈" else "📉"
                                appendLine("  $emoji $code: 【${p.patternName}】→ ${p.direction.signal}（${p.description}）")
                            }
                        }
                    }
                    Log.i(TAG, "[$useCaseId] K线形态: ${patternOutput.size} 只股票侦测到形态")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$useCaseId] 后处理异常: ${e.message}")
        }

        // 4b. AI 精选质量闸门：saveAsAiOnly（非交易一键建仓）→ 登记本周期选股结果，
        // 四周期全部到齐后由 AiSelectionQualityGate 统一过滤弱票（共振≥3 或 分≥85）
        if (saveAsAiOnly) {
            try {
                com.chin.stockanalysis.strategy.trade.AiSelectionQualityGate
                    .registerPeriod(context, today, useCaseId, aiOnlySelected)
                Log.i(TAG, "[$useCaseId] AI 精选质量闸门登记完成: ${aiOnlySelected.size} 只")
            } catch (e: Exception) {
                Log.w(TAG, "[$useCaseId] AI 精选质量闸门登记失败: ${e.message}")
            }
        }

        // 5. 收集各 Pipeline 节点股票流动摘要
        val stockFlowLines = mutableListOf<String>()
        for ((pipeName, pr) in result.pipelineResults) {
            if (pr.stockFlowLogs.isNotEmpty()) {
                stockFlowLines.add("📊 $pipeName:")
                for ((nodeId, flow) in pr.stockFlowLogs) {
                    val line = buildString {
                        append("  ${flow.nodeName}: ${flow.inputCount}→${flow.outputCount}")
                        if (flow.filterCount > 0) append(" (过滤${flow.filterCount}: ${flow.filterReason})")
                    }
                    stockFlowLines.add(line)
                }
            }
        }

        // 5b. 收集各 Node 的完整流动详情（含股票代码）
        val nodeFlowDetails = mutableListOf<NodeFlowDetail>()
        for ((_, pr) in result.pipelineResults) {
            for ((nodeId, flow) in pr.stockFlowLogs) {
                val stageResult = pr.stageResults[nodeId]
                nodeFlowDetails.add(NodeFlowDetail(
                    nodeId = nodeId,
                    nodeName = flow.nodeName,
                    inputCount = flow.inputCount,
                    outputCount = flow.outputCount,
                    filterCount = flow.filterCount,
                    filterReason = flow.filterReason,
                    inputCodes = flow.inputCodes,
                    outputCodes = flow.outputCodes,
                    elapsedMs = stageResult?.stepTimings?.firstOrNull()?.second ?: 0,
                    success = stageResult?.success ?: true,
                    errorMsg = pr.errors[nodeId] ?: ""
                ))
            }
        }

        // 5c. 持久化节点使用率统计（累计拦截/空转计数，供评估低使用率 node）
        try {
            com.chin.stockanalysis.strategy.topology.core.NodeUsageStats.record(context, useCaseId, nodeFlowDetails)
        } catch (e: Exception) {
            Log.w(TAG, "[$useCaseId] 节点使用率统计失败: ${e.message}")
        }

        // 6. 构建 UI 显示文本
        val detailLines = mutableListOf<String>()
        if (ordersCount > 0) detailLines.add("订单${ordersCount}笔")
        if (guardSummary.isNotBlank()) detailLines.add("风控卖出")
        if (swapSummary.isNotBlank()) {
            val swapNum = swapSummary.lines().first().filter { it.isDigit() }
            if (swapNum.isNotEmpty()) detailLines.add("换${swapNum}笔")
        }
        if (savedWatchlist) detailLines.add("已保存自选")

        val uiText = buildString {
            if (result.success) {
                appendLine("✅ [DAG] ${result.pipelineResults.keys.lastOrNull() ?: "Pipeline"} 完成 (${elapsed}ms)")
            } else {
                appendLine("❌ [DAG] 失败: ${result.errors.keys.joinToString(", ")}")
            }
            // 公共研判摘要（大盘多周期研判→风格轮动判断→板块强弱监测）
            for ((_, pr) in result.pipelineResults) {
                val styleOut = pr.stageResults["n_style_rotation"]?.output
                if (styleOut is com.chin.stockanalysis.strategy.topology.nodes.StyleRotationResult && styleOut.summary.isNotBlank()) {
                    appendLine()
                    appendLine("🧭 公共研判（大盘→风格→板块）")
                    appendLine(styleOut.summary)
                    break
                }
            }
            if (stockFlowLines.isNotEmpty()) {
                for (line in stockFlowLines.take(6)) appendLine(line)
                if (stockFlowLines.size > 6) appendLine("  ... 共 ${stockFlowLines.size} 个节点")
            }
            if (detailLines.isNotEmpty()) append(detailLines.joinToString(" | "))
        }

        // 7. 后处理：触发持仓监控
        try {
            com.chin.stockanalysis.stock.database.AppBackgroundRunner.monitorWatchlistDirect(context)
        } catch (_: Exception) {
        }

        // 8. 失败分析：订单为 0 时定位杀手节点
        val failureAnalysis = if (ordersCount == 0) {
            PipelineFailureAnalyzer.analyze(result.pipelineResults, ordersCount)
        } else {
            PipelineFailureAnalyzer.FailureAnalysis(isFailed = false)
        }

        // 将失败分析追加到 uiText
        var finalUiText = if (failureAnalysis.isFailed) {
            buildString {
                append(uiText.trimEnd())
                appendLine()
                appendLine("⛔ 失败: ${failureAnalysis.killNodeName} — ${failureAnalysis.reason}")
                if (failureAnalysis.suggestion.isNotBlank()) {
                    append("💡 ${failureAnalysis.suggestion}")
                }
            }
        } else uiText

        // 8b. 严选详情：提取逐股过滤原因（当杀手节点为 n_strict 或 n_orders 时）
        var strictEvalDetail = ""
        if (ordersCount == 0) {
            for ((_, pr) in result.pipelineResults) {
                val evalOutput = pr.stageResults["n_strict_eval"]?.output
                if (evalOutput is com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationResult && evalOutput.totalCount > 0) {
                    strictEvalDetail = buildString {
                        appendLine()
                        appendLine("── 严选详情 (${evalOutput.passedCount}/${evalOutput.totalCount} 通过) ──")
                        val details = evalOutput.passedStocks.values.sortedByDescending { it.passCount }
                        for (d in details.take(10)) {
                            val checks = listOf(
                                if (d.convergenceOk) "✅粘合" else "❌粘合",
                                if (d.bullishAligned) "✅多头" else "❌多头",
                                if (d.convergenceDurationOk) "✅持续" else "❌持续",
                                if (d.volumeConditionOk) "✅量能" else "❌量能",
                                if (d.drawdownOk) "✅跌幅" else "❌跌幅",
                                if (d.ma60Rising) "✅MA60" else "❌MA60",
                                if (d.aboveYearLine) "✅年线" else "❌年线",
                                if (d.changePctOk) "✅涨幅" else "❌涨幅",
                                if (d.aboveAllMAs) "✅站上" else "❌站上"
                            )
                            appendLine("  ${d.name}(${d.code}) ${d.passCount}/${d.totalChecks} ${checks.joinToString(" ")}")
                        }
                        if (details.size > 10) appendLine("  ... 共 ${details.size} 只")
                    }
                    break
                }
            }
            if (strictEvalDetail.isNotBlank()) {
                finalUiText = finalUiText.trimEnd() + "\n" + strictEvalDetail
            }
        }

        // 9. 持仓诊断分析：腾龙换鸟/持仓风控卖出股票回溯
        var diagnosticSummary = ""
        if (typedGuardResult != null || typedSwapResult != null) {
            try {
                val db = StockDatabase.getInstance(context)
                val diagnostic = HoldingDiagnosticAnalyzer.analyze(typedGuardResult, typedSwapResult, db, tradeDate)
                diagnosticSummary = diagnostic.summary
                Log.i(TAG, "[$useCaseId] 持仓诊断: ${diagnostic.soldCount} 只被卖出, 平均亏损 ${"%.2f".format(diagnostic.totalLossPct)}%")

                // 追加诊断摘要到 uiText
                if (diagnostic.hasIssues) {
                    finalUiText = buildString {
                        append(finalUiText.trimEnd())
                        appendLine()
                        appendLine(diagnostic.summary)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$useCaseId] 持仓诊断分析失败: ${e.message}")
            }
        }

        // 10. 保存 Pipeline 报告到 daily_period_result（含诊断数据）
        try {
            savePipelineReport(context, useCaseId, result, tradeDate, stockFlowLines, diagnosticSummary)
        } catch (e: Exception) {
            Log.w(TAG, "[$useCaseId] 保存报告失败: ${e.message}")
        }

        // 11. 后台异步拟合计算（不阻塞 Pipeline 结果，完成后自动更新报告）
        //     非交易时间仅选股：跳过拟合（拟合参数仅交易时间自动生效）
        if (saveAsAiOnly) {
            Log.i(TAG, "[$useCaseId] 非交易时间（仅选股）：跳过后台拟合，选股结果已保存 AI 精选")
        } else {
            launchBackgroundFitting(context, useCaseId, tradeDate, strategies)
        }

        return DagExecResult(
            success = result.success,
            ordersCount = ordersCount,
            mergeSummary = mergeSummary,
            swapSummary = swapSummary,
            guardSummary = guardSummary,
            patternSummary = patternSummary,
            savedWatchlist = savedWatchlist,
            stockFlowLines = stockFlowLines,
            totalElapsedMs = elapsed,
            pipelineNames = result.pipelineResults.keys.toList(),
            errors = result.errors,
            uiText = finalUiText.trimEnd(),
            failureAnalysis = failureAnalysis,
            diagnosticSummary = diagnosticSummary,
            strictEvalDetail = strictEvalDetail,
            selectedStocks = selectedStocks,
            nodeFlowDetails = nodeFlowDetails
        )
    }

    /**
     * 将 useCaseId 映射到 strategyId 和 periodDays。
     */
    private fun mapUseCaseToStrategy(useCaseId: String): Pair<String, Int> {
        return when (useCaseId) {
            "ultra_short" -> "UltraShortQuant" to 1
            "short_term" -> "ShortTermTrend" to 5
            "mid_term" -> "MidTermSwing" to 20
            "long_term" -> "LongTermInvest" to 60
            else -> useCaseId to 0
        }
    }

    /**
     * 保存 Pipeline 报告到 daily_period_result 表（统一所有周期）。
     *
     * 与 MidTermQuantFragment.savePipelineReportToDb 逻辑一致，
     * 收集最终订单股票代码、构建 pipeline flow JSON 并写入数据库。
     */
    private suspend fun savePipelineReport(
        context: Context,
        useCaseId: String,
        result: UseCaseLoader.MultiPipelineResult,
        tradeDate: String,
        stockFlowLines: List<String>,
        diagnosticSummary: String = ""
    ) {
        val (strategyId, periodDays) = mapUseCaseToStrategy(useCaseId)
        val db = StockDatabase.getInstance(context)

        // 收集最终输出的股票代码 + 逐策略 Top3 + 新闻力度/轮动惩罚
        val finalCodes = mutableListOf<String>()
        var newsStrengthScore = 0
        var rotationPenalty = 0
        val perStrategyTop3 = JSONArray()

        for ((_, pr) in result.pipelineResults) {
            // 最终订单股票代码
            val ordersOutput = pr.stageResults["n_orders"]?.output
            if (ordersOutput is OrderGenerationResult) {
                finalCodes.addAll(ordersOutput.orders.map { it.stockCode })
            }

            // 新闻力度（Int 输出）
            (pr.stageResults["n_news_str"]?.output as? Int)?.let {
                newsStrengthScore = it
            }

            // 板块轮动惩罚（v2：RotationPenaltyResult 输出，取总惩罚写入报表）
            (pr.stageResults["n_rot_pen"]?.output as? RotationPenaltyResult)?.let {
                rotationPenalty = it.rotationPenalty
            }

            // 逐策略 Top3：从信号合并节点提取 MergedSignalPool，按 strategyId 分组取 Top3
            val mergedPool = pr.stageResults["n_merge"]?.output
            if (mergedPool is com.chin.stockanalysis.strategy.topology.core.MergedSignalPool) {
                // 从各策略节点的 SignalPack 输出中收集 strategyId → strategyName 映射
                val strategyNames = mutableMapOf<String, String>()
                for ((_, linkResult) in pr.stageResults) {
                    val sp = linkResult.output
                    if (sp is com.chin.stockanalysis.strategy.topology.core.SignalPack) {
                        strategyNames[sp.strategyId] = sp.strategyName
                    }
                }

                val byStrategy = mergedPool.boostedSignals.groupBy { it.strategyId }
                for ((sid, signals) in byStrategy) {
                    val top3 = signals.sortedByDescending { it.strength }.take(3)
                    val picksArr = JSONArray()
                    for ((rank, sig) in top3.withIndex()) {
                        picksArr.put(JSONObject().apply {
                            put("rank", rank + 1)
                            put("code", sig.stockCode)
                            put("name", sig.stockName)
                            put("strength", sig.strength)
                            put("reason", sig.reason.take(100))
                        })
                    }
                    perStrategyTop3.put(JSONObject().apply {
                        put("strategyId", sid)
                        put("strategyName", strategyNames[sid] ?: sid)
                        put("picks", picksArr)
                    })
                }
            }
        }

        // 构建 pipeline flow JSON
        val flowJson = JSONObject().apply {
            put("useCaseId", result.useCaseId)
            put("success", result.success)
            put("totalElapsedMs", result.totalElapsedMs)
            val pipes = JSONObject()
            for ((pipeName, pr) in result.pipelineResults) {
                val pipeObj = JSONObject()
                pipeObj.put("pipelineName", pr.pipelineName)
                pipeObj.put("success", pr.success)
                val flows = JSONArray()
                for ((nodeId, flow) in pr.stockFlowLogs) {
                    flows.put(JSONObject().apply {
                        put("nodeId", nodeId)
                        put("nodeName", flow.nodeName)
                        put("inputCount", flow.inputCount)
                        put("outputCount", flow.outputCount)
                        put("filterCount", flow.filterCount)
                        put("filterReason", flow.filterReason)
                    })
                }
                pipeObj.put("stockFlows", flows)
                // 提取大盘均线检查结果（兼容新统一节点 + 旧节点）
                val maUnifiedResult = pr.stageResults["n_ma_unified"]?.output
                val marketMaResult = pr.stageResults["n_market_ma"]?.output
                when {
                    maUnifiedResult is com.chin.stockanalysis.strategy.topology.nodes.MarketMaUnifiedNode.MarketMaUnifiedResult -> {
                        pipeObj.put("n_market_ma_check", JSONObject().apply {
                            put("isConvergedUpward", maUnifiedResult.isConvergedUpward)
                            put("ma5", maUnifiedResult.ma5)
                            put("ma10", maUnifiedResult.ma10)
                            put("ma20", maUnifiedResult.ma20)
                            put("divergencePct", maUnifiedResult.divergencePct)
                            put("ma5Slope", maUnifiedResult.ma5Slope)
                            put("description", maUnifiedResult.description)
                            put("riskLevel", maUnifiedResult.riskLevel)
                            put("oscillationHarvest", maUnifiedResult.oscillationHarvest)
                        })
                    }
                    marketMaResult is com.chin.stockanalysis.strategy.topology.nodes.MarketMaCheckResult -> {
                        pipeObj.put("n_market_ma_check", JSONObject().apply {
                            put("isConvergedUpward", marketMaResult.isConvergedUpward)
                            put("ma5", marketMaResult.ma5)
                            put("ma10", marketMaResult.ma10)
                            put("ma20", marketMaResult.ma20)
                            put("divergencePct", marketMaResult.divergencePct)
                            put("ma5Slope", marketMaResult.ma5Slope)
                            put("description", marketMaResult.description)
                        })
                    }
                }
                // 提取严选检查结果（评估结果存在 n_strict_eval key）
                val strictResult = pr.stageResults["n_strict_eval"]?.output
                if (strictResult is com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationResult) {
                    pipeObj.put("n_strict_selection", JSONObject().apply {
                        put("totalCount", strictResult.totalCount)
                        put("passedCount", strictResult.passedCount)
                        val passedStocksObj = JSONObject()
                        for ((code, detail) in strictResult.passedStocks) {
                            passedStocksObj.put(code, JSONObject().apply {
                                put("name", detail.name)
                                put("passCount", detail.passCount)
                                put("totalChecks", detail.totalChecks)
                                put("convergenceOk", detail.convergenceOk)
                                put("bullishAligned", detail.bullishAligned)
                                put("convergenceDurationOk", detail.convergenceDurationOk)
                                put("volumeConditionOk", detail.volumeConditionOk)
                                put("drawdownOk", detail.drawdownOk)
                                put("ma60Rising", detail.ma60Rising)
                                put("aboveYearLine", detail.aboveYearLine)
                                put("changePctOk", detail.changePctOk)
                                put("aboveAllMAs", detail.aboveAllMAs)
                            })
                        }
                        put("passedStocks", passedStocksObj)
                    })
                }
                pipes.put(pipeName, pipeObj)
            }
            put("pipelines", pipes)
            // 失败分析（订单为 0 时定位杀手节点）
            if (finalCodes.isEmpty()) {
                val fa = PipelineFailureAnalyzer.analyze(result.pipelineResults, 0)
                put("failureAnalysis", JSONObject().apply {
                    put("killNodeId", fa.killNodeId)
                    put("killNodeName", fa.killNodeName)
                    put("reason", fa.reason)
                    put("suggestion", fa.suggestion)
                })
            }
            // 持仓诊断分析（腾龙换鸟/持仓风控卖出回溯）
            if (diagnosticSummary.isNotBlank()) {
                put("diagnosticSummary", diagnosticSummary)
            }
        }

        val entity = com.chin.stockanalysis.strategy.trade.DailyPeriodResultEntity(
            strategyId = "DAG_${useCaseId.uppercase()}",
            strategyName = result.pipelineResults.keys.firstOrNull() ?: useCaseId,
            tradeDate = tradeDate,
            periodDays = periodDays,
            stockCodesJson = JSONArray(finalCodes).toString(),
            stockCount = finalCodes.size,
            newsStrengthScore = newsStrengthScore,
            rotationPenalty = rotationPenalty,
            mainBoardFilter = true,
            filteredCodesJson = "[]",
            filteredReasonJson = stockFlowLines.joinToString("\n"),
            finalTop3Json = perStrategyTop3.toString(),
            aiSelectionReason = "DAG Pipeline 执行 ($strategyId)",
            pipelineFlowJson = flowJson.toString(),
            createdAt = System.currentTimeMillis()
        )
        db.dailyPeriodResultDao().insert(entity)
        Log.i(TAG, "[$useCaseId] 报告已保存: ${entity.strategyName} $tradeDate, 最终股票 ${finalCodes.size} 只, 逐策略Top3 ${perStrategyTop3.length()} 组, 节点流动 ${result.pipelineResults.values.sumOf { it.stockFlowLogs.size }} 个")
    }

    /**
     * 构建完整的 DAG 执行报告文本（用于弹窗展示）。
     *
     * @param title 报告标题（如 "超短线 DAG Pipeline 报告"）
     * @param r     执行结果
     */
    fun buildReportText(title: String, r: DagExecResult): String = buildString {
        appendLine("═══ $title ═══")
        if (r.patternSummary.isNotBlank()) {
            appendLine(r.patternSummary.trimEnd())
            appendLine("─────────────────────────")
        }
        appendLine("成功: ${r.success} | 耗时: ${r.totalElapsedMs}ms")
        appendLine("Pipeline: ${r.pipelineNames.joinToString(", ")}")
        if (r.ordersCount > 0) appendLine("生成订单: ${r.ordersCount}笔")
        if (r.mergeSummary.isNotBlank()) appendLine(r.mergeSummary.trimEnd())
        if (r.guardSummary.isNotBlank()) appendLine(r.guardSummary.trimEnd())
        if (r.swapSummary.isNotBlank()) appendLine(r.swapSummary.trimEnd())
        if (r.savedWatchlist) appendLine("已保存到自选股")
        if (r.failureAnalysis.isFailed) {
            appendLine()
            appendLine(r.failureAnalysis.flowSummary)
        }
        if (r.strictEvalDetail.isNotBlank()) {
            appendLine()
            appendLine(r.strictEvalDetail.trimEnd())
        }
        if (r.diagnosticSummary.isNotBlank()) {
            appendLine()
            appendLine(r.diagnosticSummary.trimEnd())
        }
        if (r.stockFlowLines.isNotEmpty()) {
            appendLine("── 节点股票流动 ──")
            for (line in r.stockFlowLines) appendLine(line)
        }
        if (r.errors.isNotEmpty()) {
            appendLine("── 错误 ──")
            for ((key, msg) in r.errors) appendLine("  [$key] $msg")
        }
        appendLine("═══════════════════════════")
    }

    // ═══════════════════════════════════════════════════
    //  做T Pipeline 执行器
    // ═══════════════════════════════════════════════════

    /**
     * 做T Pipeline 执行结果
     */
    data class TTradePipelineResult(
        val success: Boolean,
        val signalsCount: Int,
        val savedCount: Int,
        val marketSummary: String,
        val overseasSummary: String,
        val elapsedMs: Long,
        val errors: Map<String, String>
    )

    /**
     * 执行做T决策 Pipeline。
     *
     * 对指定周期的持仓进行多维度交叉验证（日K机构意图 + 外盘情绪 + 板块新闻），
     * 输出带置信度的做T/反T建议。
     *
     * @param context      Android Context
     * @param periodType   周期类型（如 "UltraShortQuant"、"MidTermQuant"）
     * @return Pipeline 执行结果
     */
    suspend fun executeTTradePipeline(
        context: Context,
        periodType: String
    ): TTradePipelineResult {
        val totalStart = System.currentTimeMillis()

        try {
            // 1. 初始化 UseCaseLoader（做T不需要策略列表，传空）
            UseCaseLoader.init(context, emptyList())

            // 2. 执行 Pipeline（传入 periodType 以便正确过滤持仓）
            val orderTypeForPeriod = when (periodType) {
                "UltraShortQuant" -> "ultra_short"
                "ShortTermQuant" -> "shortterm"
                "MidTermQuant" -> "midterm"
                "LongTermQuant" -> "long_term"
                else -> "shortterm"
            }
            val result = UseCaseLoader.run(
                "t_trade", java.time.LocalDate.now().toString(), null,
                configOverrides = mapOf("periodType" to orderTypeForPeriod, "holdingPeriod" to orderTypeForPeriod)
            )
            val elapsed = System.currentTimeMillis() - totalStart

            if (!result.success) {
                return TTradePipelineResult(
                    success = false, signalsCount = 0, savedCount = 0,
                    marketSummary = "", overseasSummary = "",
                    elapsedMs = elapsed, errors = result.errors
                )
            }

            // 3. 从结果中提取做T信号
            var signalsCount = 0
            var savedCount = 0
            var marketSummary = ""
            var overseasSummary = ""

            for ((_, pipelineResult) in result.pipelineResults) {
                val synthOutput = pipelineResult.stageResults["t_synth"]?.output
                if (synthOutput is com.chin.stockanalysis.strategy.topology.pipelines.TSynthesizeResult) {
                    signalsCount = synthOutput.signals.size
                    marketSummary = synthOutput.marketSummary
                    overseasSummary = synthOutput.overseasSummary
                }

                val saveOutput = pipelineResult.stageResults["t_save"]?.output
                if (saveOutput is com.chin.stockanalysis.strategy.topology.pipelines.TRecommendSaveResult) {
                    savedCount = saveOutput.saved
                }
            }

            Log.i(TAG, "[t_trade/$periodType] 完成: $signalsCount 条信号, 保存 $savedCount 条, ${elapsed}ms, $marketSummary, $overseasSummary")

            return TTradePipelineResult(
                success = true,
                signalsCount = signalsCount,
                savedCount = savedCount,
                marketSummary = marketSummary,
                overseasSummary = overseasSummary,
                elapsedMs = elapsed,
                errors = result.errors
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - totalStart
            Log.e(TAG, "[t_trade/$periodType] Pipeline 执行失败: ${e.message}")
            return TTradePipelineResult(
                success = false, signalsCount = 0, savedCount = 0,
                marketSummary = "", overseasSummary = "",
                elapsedMs = elapsed,
                errors = mapOf("pipeline" to (e.message ?: "unknown"))
            )
        }
    }

    // ═══════════════════════════════════════════════════
    //  后台异步拟合计算
    // ═══════════════════════════════════════════════════

    /**
     * 后台异步执行拟合计算。
     *
     * 从 DAG 关键路径移除后，拟合不再阻塞 Pipeline 结果。
     * 在独立 CoroutineScope 中运行，完成后自动写入 DB。
     */
    /** 共享拟合 Scope — 所有周期的拟合共用一个受监督的 Scope，可统一取消 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val fittingScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(2)
    )

    private fun launchBackgroundFitting(
        context: Context,
        useCaseId: String,
        tradeDate: String,
        strategies: List<Strategy>
    ) {
        fittingScope.launch {
            try {
                Log.i(TAG, "[$useCaseId] 🔧 后台拟合开始 (${strategies.size} 个策略)")
                val db = StockDatabase.getInstance(context)

                val availableDates = db.dailySnapshotDao().getAvailableDates(120)
                    .sorted()
                    .filter { it <= tradeDate }

                if (availableDates.size < 5) {
                    Log.w(TAG, "[$useCaseId] 历史数据不足（${availableDates.size} 天），跳过拟合")
                    return@launch
                }

                val optimizer = StrategyOptimizer(context)
                val fitJobs = strategies.filter { it.id != "ai_prediction" }
                val fittingResults = mutableListOf<StrategyTradeFittingParamEntity>()
                // 拟合窗口：周级(最近5日) + 月级(最近20日)
                val fitWindows = listOf(5 to "周", 20 to "月")

                // 串行拟合（避免多策略并发 CPU 竞争），每策略之间无依赖
                for (strategy in fitJobs) {
                    for ((windowDays, label) in fitWindows) {
                        if (availableDates.size < windowDays) continue
                        try {
                            val result = optimizer.gridSearch(strategy, availableDates, windowDays)
                            val weightsJson = result.bestWeights.joinToString(",") { w -> "${w.key}=${w.weight}" }
                            Log.i(TAG, "[$useCaseId]   拟合 ${strategy.id}(${label}级): accuracy=${"%.1f".format(result.bestAccuracy)}%")
                            fittingResults.add(
                                StrategyTradeFittingParamEntity(
                                    strategyId = strategy.id,
                                    tradeDate = tradeDate,
                                    periodDays = windowDays,
                                    paramJson = weightsJson,
                                    fittingRound = 1,
                                    accuracy = result.bestAccuracy.toDouble(),
                                    avgReturn = result.bestAvgReturn,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "[$useCaseId]   拟合 ${strategy.id}(${label}级) 失败: ${e.message}")
                        }
                    }
                }

                if (fittingResults.isNotEmpty()) {
                    db.strategyTradeFittingParamDao().insertAll(fittingResults)
                }
                Log.i(TAG, "[$useCaseId] ✅ 后台拟合完成: ${fittingResults.size}/${fitJobs.size} 策略成功")
            } catch (e: Exception) {
                Log.e(TAG, "[$useCaseId] 后台拟合失败: ${e.message}")
            }
        }
    }
}
