package com.chin.stockanalysis.strategy.topology.xml

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.topology.pipelines.OrderGenerationResult
import com.chin.stockanalysis.strategy.topology.pipelines.PositionMergeResult
import com.chin.stockanalysis.strategy.topology.pipelines.SwapWeakResult
import com.chin.stockanalysis.strategy.topology.pipelines.HoldingGuardResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## 通用 DAG 交易執行器
 *
 * 將「UseCaseLoader.run → 提取訂單/持倉/換股 → 保存自選股 → 構建摘要」
 * 的通用流程封裝為一處，供超短線/短線/中線/長線四個 Fragment 復用。
 *
 * 各週期只需提供：
 * - [useCaseId]    對應 `usecases/<id>_usecase.xml`（如 "ultra_short"）
 * - [orderType]    訂單類型（如 "UltraShortQuant"）
 * - [importDays]   數據不足時的歷史導入天數
 *
 * DAG Pipeline 內部的 `generate_orders` / `position_merge` / `swap_weak`
 * 節點會自行將訂單寫入 `strategy_trade_order` 表，本類只做結果提取與摘要。
 */
object DagTradeExecutor {

    private const val TAG = "DagTradeExecutor"

    /**
     * DAG 執行結果摘要。
     *
     * @property success           Pipeline 整體是否成功
     * @property ordersCount       生成訂單筆數
     * @property mergeSummary      持倉合併摘要（可空）
     * @property swapSummary       騰龍換鳥摘要（可空）
     * @property guardSummary      持倉風控摘要（可空）
     * @property savedWatchlist    是否已保存到自選股
     * @property stockFlowLines    各節點股票流動日誌（供 UI 展示）
     * @property totalElapsedMs    總耗時
     * @property pipelineNames     執行的 Pipeline 名稱列表
     * @property errors            錯誤信息
     * @property uiText            預構建的 UI 顯示文本
     */
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
        val strictEvalDetail: String = ""
    )

    /**
     * 執行指定週期的 DAG Pipeline。
     *
     * @param context      Android Context
     * @param useCaseId    UseCase ID（如 "ultra_short"、"short_term"、"mid_term"、"long_term"）
     * @param tradeDate    交易日（yyyy-MM-dd）
     * @param today        當前最近交易日（用於數據導入檢查）
     * @param strategies   啟用的策略列表（按週期過濾後傳入）
     * @param orderType    訂單類型（用於自選股來源標記，如 "ultra_short_dag"）
     * @param importDays   數據不足時的歷史導入天數（0 表示不導入）
     * @param onNodeProgress 節點執行進度回調（可選），參數為 (pipelineName, nodeName)，供 UI 實時顯示
     * @return 執行結果摘要
     */
    suspend fun execute(
        context: Context,
        useCaseId: String,
        tradeDate: String,
        today: String,
        strategies: List<Strategy>,
        orderType: String,
        importDays: Int = 60,
        onNodeProgress: ((pipelineName: String, nodeName: String) -> Unit)? = null
    ): DagExecResult {
        if (strategies.isEmpty()) {
            return DagExecResult(
                success = false, ordersCount = 0, mergeSummary = "",
                swapSummary = "", guardSummary = "", patternSummary = "", savedWatchlist = false,
                stockFlowLines = emptyList(),
                totalElapsedMs = 0, pipelineNames = emptyList(),
                errors = mapOf("strategy" to "沒有啟用的策略"),
                uiText = "⚠️ 沒有啟用的策略"
            )
        }

        val totalStart = System.currentTimeMillis()

        // 1. 初始化 UseCaseLoader（注入策略）
        UseCaseLoader.init(context, strategies)

        // 2. 數據導入檢查
        if (importDays > 0) {
            try {
                val db = StockDatabase.getInstance(context)
                val todaySnaps = db.dailySnapshotDao().getByDate(today)
                if (todaySnaps.size < 100) {
                    Log.i(TAG, "[$useCaseId] 數據不足(${todaySnaps.size}<100)，導入 $importDays 天")
                    com.chin.stockanalysis.strategy.data.HistoricalDataFetcher(context)
                        .fetchAllHistoricalData(importDays)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$useCaseId] 數據導入檢查失敗（不阻塞）: ${e.message}")
            }
        }

        // 3. 執行 DAG Pipeline
        val result = UseCaseLoader.run(useCaseId, tradeDate, onNodeProgress)
        val elapsed = System.currentTimeMillis() - totalStart

        // 4. 後處理：從 nodeResults 提取訂單/持倉/換股信息
        var ordersCount = 0
        var swapSummary = ""
        var guardSummary = ""
        var mergeSummary = ""
        var patternSummary = ""
        var savedWatchlist = false
        var typedGuardResult: HoldingGuardResult? = null
        var typedSwapResult: SwapWeakResult? = null

        try {
            for ((_, pipelineResult) in result.pipelineResults) {
                val nodeResults = pipelineResult.stageResults

                // 提取訂單生成結果
                val ordersOutput = nodeResults["n_orders"]?.output
                if (ordersOutput is OrderGenerationResult) {
                    ordersCount = ordersOutput.orders.size
                    Log.i(TAG, "[$useCaseId] 訂單生成: $ordersCount 筆")

                    // 保存到自選股
                    if (ordersOutput.orders.isNotEmpty()) {
                        try {
                            val watchlistItems = ordersOutput.orders.map { order ->
                                Triple(order.stockCode, order.stockName, order.scoreAtBuy)
                            }
                            com.chin.stockanalysis.stock.database.AppBackgroundRunner.addBatchToWatchlist(
                                context, watchlistItems, source = orderType
                            )
                            savedWatchlist = true
                            Log.i(TAG, "[$useCaseId] 已保存 ${watchlistItems.size} 只到自選股")
                        } catch (e: Exception) {
                            Log.w(TAG, "[$useCaseId] 保存自選股失敗: ${e.message}")
                        }
                    }
                }

                // 提取持倉合併結果
                val mergeOutput = nodeResults["n_merge_pos"]?.output
                if (mergeOutput is PositionMergeResult) {
                    mergeSummary = buildString {
                        appendLine("持倉合併: 新增${mergeOutput.newCount}筆, 總持倉${mergeOutput.totalHoldings}筆")
                        if (mergeOutput.updatedCodes.isNotEmpty()) {
                            appendLine("  追加: ${mergeOutput.updatedCodes.take(5).joinToString(", ")}")
                        }
                    }
                    Log.i(TAG, "[$useCaseId] $mergeSummary")
                }

                // 提取騰龍換鳥結果
                val swapOutput = nodeResults["n_swap"]?.output
                if (swapOutput is SwapWeakResult) {
                    typedSwapResult = swapOutput
                    swapSummary = buildString {
                        appendLine("騰龍換鳥: 換${swapOutput.swappedCount}筆")
                        appendLine("  換股前: ${swapOutput.beforeCount}筆 → 換股後: ${swapOutput.afterCount}筆")
                        if (swapOutput.soldStocks.isNotEmpty()) {
                            appendLine("  賣出: ${swapOutput.soldStocks.joinToString(", ")}")
                        }
                    }
                    Log.i(TAG, "[$useCaseId] $swapSummary")
                }

                // 提取持倉風控結果
                val guardOutput = nodeResults["n_guard"]?.output
                if (guardOutput is HoldingGuardResult && guardOutput.soldCount > 0) {
                    typedGuardResult = guardOutput
                    guardSummary = buildString {
                        appendLine("持倉風控: 賣出${guardOutput.soldCount}筆（評估${guardOutput.evaluatedCount}筆）")
                        appendLine("  賣出: ${guardOutput.soldStocks.joinToString(", ")}")
                    }
                    Log.i(TAG, "[$useCaseId] $guardSummary")
                }

                // 提取 K 線形態偵測結果
                @Suppress("UNCHECKED_CAST")
                val patternOutput = nodeResults["n_candle"]?.output as?
                    Map<String, List<com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.PatternMatch>>
                if (!patternOutput.isNullOrEmpty()) {
                    patternSummary = buildString {
                        appendLine("🚨 K線形態警示 🚨")
                        for ((code, patterns) in patternOutput) {
                            for (p in patterns) {
                                val emoji = if (p.direction == com.chin.stockanalysis.strategy.analysis.CandlePatternDetector.Direction.BULLISH) "📈" else "📉"
                                appendLine("  $emoji $code: 【${p.patternName}】→ ${p.direction.signal}（${p.description}）")
                            }
                        }
                    }
                    Log.i(TAG, "[$useCaseId] K線形態: ${patternOutput.size} 只股票偵測到形態")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$useCaseId] 後處理異常: ${e.message}")
        }

        // 5. 收集各 Pipeline 節點股票流動摘要
        val stockFlowLines = mutableListOf<String>()
        for ((pipeName, pr) in result.pipelineResults) {
            if (pr.stockFlowLogs.isNotEmpty()) {
                stockFlowLines.add("📊 $pipeName:")
                for ((nodeId, flow) in pr.stockFlowLogs) {
                    val line = buildString {
                        append("  ${flow.nodeName}: ${flow.inputCount}→${flow.outputCount}")
                        if (flow.filterCount > 0) append(" (過濾${flow.filterCount}: ${flow.filterReason})")
                    }
                    stockFlowLines.add(line)
                }
            }
        }

        // 6. 構建 UI 顯示文本
        val detailLines = mutableListOf<String>()
        if (ordersCount > 0) detailLines.add("訂單${ordersCount}筆")
        if (guardSummary.isNotBlank()) detailLines.add("風控賣出")
        if (swapSummary.isNotBlank()) {
            val swapNum = swapSummary.lines().first().filter { it.isDigit() }
            if (swapNum.isNotEmpty()) detailLines.add("換${swapNum}筆")
        }
        if (savedWatchlist) detailLines.add("已保存自選")

        val uiText = buildString {
            if (result.success) {
                appendLine("✅ [DAG] ${result.pipelineResults.keys.firstOrNull() ?: "Pipeline"} 完成 (${elapsed}ms)")
            } else {
                appendLine("❌ [DAG] 失敗: ${result.errors.keys.joinToString(", ")}")
            }
            if (stockFlowLines.isNotEmpty()) {
                for (line in stockFlowLines.take(6)) appendLine(line)
                if (stockFlowLines.size > 6) appendLine("  ... 共 ${stockFlowLines.size} 個節點")
            }
            if (detailLines.isNotEmpty()) append(detailLines.joinToString(" | "))
        }

        // 7. 後處理：觸發持倉監控
        try {
            com.chin.stockanalysis.stock.database.AppBackgroundRunner.monitorWatchlistDirect(context)
        } catch (_: Exception) {
        }

        // 8. 失敗分析：訂單為 0 時定位殺手節點
        val failureAnalysis = if (ordersCount == 0) {
            PipelineFailureAnalyzer.analyze(result.pipelineResults, ordersCount)
        } else {
            PipelineFailureAnalyzer.FailureAnalysis(isFailed = false)
        }

        // 將失敗分析追加到 uiText
        var finalUiText = if (failureAnalysis.isFailed) {
            buildString {
                append(uiText.trimEnd())
                appendLine()
                appendLine("⛔ 失敗: ${failureAnalysis.killNodeName} — ${failureAnalysis.reason}")
                if (failureAnalysis.suggestion.isNotBlank()) {
                    append("💡 ${failureAnalysis.suggestion}")
                }
            }
        } else uiText

        // 8b. 嚴選詳情：提取逐股過濾原因（當殺手節點為 n_strict 或 n_orders 時）
        var strictEvalDetail = ""
        if (ordersCount == 0) {
            for ((_, pr) in result.pipelineResults) {
                val evalOutput = pr.stageResults["n_strict_eval"]?.output
                if (evalOutput is com.chin.stockanalysis.strategy.topology.nodes.StockEvaluationResult && evalOutput.totalCount > 0) {
                    strictEvalDetail = buildString {
                        appendLine()
                        appendLine("── 嚴選詳情 (${evalOutput.passedCount}/${evalOutput.totalCount} 通過) ──")
                        val details = evalOutput.passedStocks.values.sortedByDescending { it.passCount }
                        for (d in details.take(10)) {
                            val checks = listOf(
                                if (d.maConvergedUp) "✅均線" else "❌均線",
                                if (d.threeDayNoNewLow) "✅不新低" else "❌不新低",
                                if (d.historicalLow25) "✅低位" else "❌低位",
                                if (d.peLow) "✅PE" else "❌PE",
                                if (d.cyclicalActive) "✅活躍" else "❌活躍",
                                if (d.freezingPoint) "✅冰點" else "❌冰點"
                            )
                            appendLine("  ${d.name}(${d.code}) ${d.passCount}/6 ${checks.joinToString(" ")}")
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

        // 9. 持倉診斷分析：騰龍換鳥/持倉風控賣出股票回溯
        var diagnosticSummary = ""
        if (typedGuardResult != null || typedSwapResult != null) {
            try {
                val db = StockDatabase.getInstance(context)
                val diagnostic = HoldingDiagnosticAnalyzer.analyze(typedGuardResult, typedSwapResult, db, tradeDate)
                diagnosticSummary = diagnostic.summary
                Log.i(TAG, "[$useCaseId] 持倉診斷: ${diagnostic.soldCount} 只被賣出, 總虧損 ${"%.2f".format(diagnostic.totalLossPct)}%")

                // 追加診斷摘要到 uiText
                if (diagnostic.hasIssues) {
                    finalUiText = buildString {
                        append(finalUiText.trimEnd())
                        appendLine()
                        appendLine(diagnostic.summary)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$useCaseId] 持倉診斷分析失敗: ${e.message}")
            }
        }

        // 10. 保存 Pipeline 報告到 daily_period_result（含診斷數據）
        try {
            savePipelineReport(context, useCaseId, result, tradeDate, stockFlowLines, diagnosticSummary)
        } catch (e: Exception) {
            Log.w(TAG, "[$useCaseId] 保存報告失敗: ${e.message}")
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
            strictEvalDetail = strictEvalDetail
        )
    }

    /**
     * 將 useCaseId 映射到 strategyId 和 periodDays。
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
     * 保存 Pipeline 報告到 daily_period_result 表（統一所有週期）。
     *
     * 與 MidTermQuantFragment.savePipelineReportToDb 邏輯一致，
     * 收集最終訂單股票代碼、構建 pipeline flow JSON 並寫入數據庫。
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

        // 收集最終輸出的股票代碼 + 逐策略 Top3 + 新聞力度/輪動懲罰
        val finalCodes = mutableListOf<String>()
        var newsStrengthScore = 0
        var rotationPenalty = 0
        val perStrategyTop3 = JSONArray()

        for ((_, pr) in result.pipelineResults) {
            // 最終訂單股票代碼
            val ordersOutput = pr.stageResults["n_orders"]?.output
            if (ordersOutput is OrderGenerationResult) {
                finalCodes.addAll(ordersOutput.orders.map { it.stockCode })
            }

            // 新聞力度（Int 輸出）
            (pr.stageResults["n_news_str"]?.output as? Int)?.let {
                newsStrengthScore = it
            }

            // 板塊輪動懲罰（Int 輸出）
            (pr.stageResults["n_rot_pen"]?.output as? Int)?.let {
                rotationPenalty = it
            }

            // 逐策略 Top3：從信號合併節點提取 MergedSignalPool，按 strategyId 分組取 Top3
            val mergedPool = pr.stageResults["n_merge"]?.output
            if (mergedPool is com.chin.stockanalysis.strategy.topology.core.MergedSignalPool) {
                // 從各策略節點的 SignalPack 輸出中收集 strategyId → strategyName 映射
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

        // 構建 pipeline flow JSON
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
                // 提取大盤均線檢查結果（兼容新統一節點 + 舊節點）
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
                // 提取嚴選檢查結果（評估結果存在 n_strict_eval key）
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
                                put("maConvergedUp", detail.maConvergedUp)
                                put("threeDayNoNewLow", detail.threeDayNoNewLow)
                                put("historicalLow25", detail.historicalLow25)
                                put("peLow", detail.peLow)
                                put("cyclicalActive", detail.cyclicalActive)
                                put("freezingPoint", detail.freezingPoint)
                            })
                        }
                        put("passedStocks", passedStocksObj)
                    })
                }
                pipes.put(pipeName, pipeObj)
            }
            put("pipelines", pipes)
            // 失敗分析（訂單為 0 時定位殺手節點）
            if (finalCodes.isEmpty()) {
                val fa = PipelineFailureAnalyzer.analyze(result.pipelineResults, 0)
                put("failureAnalysis", JSONObject().apply {
                    put("killNodeId", fa.killNodeId)
                    put("killNodeName", fa.killNodeName)
                    put("reason", fa.reason)
                    put("suggestion", fa.suggestion)
                })
            }
            // 持倉診斷分析（騰龍換鳥/持倉風控賣出回溯）
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
            aiSelectionReason = "DAG Pipeline 執行 ($strategyId)",
            pipelineFlowJson = flowJson.toString(),
            createdAt = System.currentTimeMillis()
        )
        db.dailyPeriodResultDao().insert(entity)
        Log.i(TAG, "[$useCaseId] 報告已保存: ${entity.strategyName} $tradeDate, 最終股票 ${finalCodes.size} 只, 逐策略Top3 ${perStrategyTop3.length()} 組, 節點流動 ${result.pipelineResults.values.sumOf { it.stockFlowLogs.size }} 個")
    }

    /**
     * 構建完整的 DAG 執行報告文本（用於彈窗展示）。
     *
     * @param title 報告標題（如 "超短線 DAG Pipeline 報告"）
     * @param r     執行結果
     */
    fun buildReportText(title: String, r: DagExecResult): String = buildString {
        appendLine("═══ $title ═══")
        if (r.patternSummary.isNotBlank()) {
            appendLine(r.patternSummary.trimEnd())
            appendLine("─────────────────────────")
        }
        appendLine("成功: ${r.success} | 耗時: ${r.totalElapsedMs}ms")
        appendLine("Pipeline: ${r.pipelineNames.joinToString(", ")}")
        if (r.ordersCount > 0) appendLine("生成訂單: ${r.ordersCount}筆")
        if (r.mergeSummary.isNotBlank()) appendLine(r.mergeSummary.trimEnd())
        if (r.guardSummary.isNotBlank()) appendLine(r.guardSummary.trimEnd())
        if (r.swapSummary.isNotBlank()) appendLine(r.swapSummary.trimEnd())
        if (r.savedWatchlist) appendLine("已保存到自選股")
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
            appendLine("── 節點股票流動 ──")
            for (line in r.stockFlowLines) appendLine(line)
        }
        if (r.errors.isNotEmpty()) {
            appendLine("── 錯誤 ──")
            for ((key, msg) in r.errors) appendLine("  [$key] $msg")
        }
        appendLine("═══════════════════════════")
    }

    // ═══════════════════════════════════════════════════
    //  做T Pipeline 執行器
    // ═══════════════════════════════════════════════════

    /**
     * 做T Pipeline 執行結果
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
     * 執行做T決策 Pipeline。
     *
     * 對指定週期的持倉進行多維度交叉驗證（日K機構意圖 + 外盤情緒 + 板塊新聞），
     * 輸出帶置信度的做T/反T建議。
     *
     * @param context      Android Context
     * @param periodType   週期類型（如 "UltraShortQuant"、"MidTermQuant"）
     * @return Pipeline 執行結果
     */
    suspend fun executeTTradePipeline(
        context: Context,
        periodType: String
    ): TTradePipelineResult {
        val totalStart = System.currentTimeMillis()

        try {
            // 1. 初始化 UseCaseLoader（做T不需要策略列表，傳空）
            UseCaseLoader.init(context, emptyList())

            // 2. 執行 Pipeline
            val result = UseCaseLoader.run("t_trade", java.time.LocalDate.now().toString(), null)
            val elapsed = System.currentTimeMillis() - totalStart

            if (!result.success) {
                return TTradePipelineResult(
                    success = false, signalsCount = 0, savedCount = 0,
                    marketSummary = "", overseasSummary = "",
                    elapsedMs = elapsed, errors = result.errors
                )
            }

            // 3. 從結果中提取做T信號
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

            Log.i(TAG, "[t_trade/$periodType] 完成: $signalsCount 條信號, 保存 $savedCount 條, ${elapsed}ms, $marketSummary, $overseasSummary")

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
            Log.e(TAG, "[t_trade/$periodType] Pipeline 執行失敗: ${e.message}")
            return TTradePipelineResult(
                success = false, signalsCount = 0, savedCount = 0,
                marketSummary = "", overseasSummary = "",
                elapsedMs = elapsed,
                errors = mapOf("pipeline" to (e.message ?: "unknown"))
            )
        }
    }
}
