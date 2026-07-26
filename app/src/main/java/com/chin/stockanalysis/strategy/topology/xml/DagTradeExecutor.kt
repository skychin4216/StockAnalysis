package com.chin.stockanalysis.strategy.topology.xml

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.topology.nodes.OrderGenerationResult
import com.chin.stockanalysis.strategy.topology.nodes.PositionMergeResult
import com.chin.stockanalysis.strategy.topology.nodes.SwapWeakResult

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
        val savedWatchlist: Boolean,
        val stockFlowLines: List<String>,
        val totalElapsedMs: Long,
        val pipelineNames: List<String>,
        val errors: Map<String, String>,
        val uiText: String
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
     * @return 執行結果摘要
     */
    suspend fun execute(
        context: Context,
        useCaseId: String,
        tradeDate: String,
        today: String,
        strategies: List<Strategy>,
        orderType: String,
        importDays: Int = 60
    ): DagExecResult {
        if (strategies.isEmpty()) {
            return DagExecResult(
                success = false, ordersCount = 0, mergeSummary = "",
                swapSummary = "", savedWatchlist = false, stockFlowLines = emptyList(),
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
        val result = UseCaseLoader.run(useCaseId, tradeDate)
        val elapsed = System.currentTimeMillis() - totalStart

        // 4. 後處理：從 nodeResults 提取訂單/持倉/換股信息
        var ordersCount = 0
        var swapSummary = ""
        var mergeSummary = ""
        var savedWatchlist = false

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
                    swapSummary = buildString {
                        appendLine("騰龍換鳥: 換${swapOutput.swappedCount}筆")
                        appendLine("  換股前: ${swapOutput.beforeCount}筆 → 換股後: ${swapOutput.afterCount}筆")
                        if (swapOutput.soldStocks.isNotEmpty()) {
                            appendLine("  賣出: ${swapOutput.soldStocks.joinToString(", ")}")
                        }
                    }
                    Log.i(TAG, "[$useCaseId] $swapSummary")
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

        return DagExecResult(
            success = result.success,
            ordersCount = ordersCount,
            mergeSummary = mergeSummary,
            swapSummary = swapSummary,
            savedWatchlist = savedWatchlist,
            stockFlowLines = stockFlowLines,
            totalElapsedMs = elapsed,
            pipelineNames = result.pipelineResults.keys.toList(),
            errors = result.errors,
            uiText = uiText.trimEnd()
        )
    }

    /**
     * 構建完整的 DAG 執行報告文本（用於彈窗展示）。
     *
     * @param title 報告標題（如 "超短線 DAG Pipeline 報告"）
     * @param r     執行結果
     */
    fun buildReportText(title: String, r: DagExecResult): String = buildString {
        appendLine("═══ $title ═══")
        appendLine("成功: ${r.success} | 耗時: ${r.totalElapsedMs}ms")
        appendLine("Pipeline: ${r.pipelineNames.joinToString(", ")}")
        if (r.ordersCount > 0) appendLine("生成訂單: ${r.ordersCount}筆")
        if (r.mergeSummary.isNotBlank()) appendLine(r.mergeSummary.trimEnd())
        if (r.swapSummary.isNotBlank()) appendLine(r.swapSummary.trimEnd())
        if (r.savedWatchlist) appendLine("已保存到自選股")
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
}
