package com.chin.stockanalysis.agent.framework

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.pipeline.AgentPipelineOrchestrator
import com.chin.stockanalysis.agent.pipeline.PipelineResult
import com.chin.stockanalysis.agent.risk.RiskManagementAgent
import com.chin.stockanalysis.agent.stock.StockAnalysisAgent
import com.chin.stockanalysis.strategy.market.MarketAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * ## 統一 Agent 分析運行器
 *
 * 一套 API 統一兩種分析模式：
 * - **Pipeline 模式**：完整流水線（5-6 個 Agent 串/並行），深度分析
 * - **Quick 模式**：並行運行 StockAnalysisAgent + RiskManagementAgent，快速分析
 *
 * 調用方只需選擇模式，底層差異對外透明。
 *
 * ### 使用方式
 * ```kotlin
 * // Pipeline 深度分析
 * val result = UnifiedAgentRunner.run(
 *     context, stockCode, stockName,
 *     mode = UnifiedAgentRunner.MODE_PIPELINE
 * )
 *
 * // Quick 快速分析
 * val result = UnifiedAgentRunner.run(
 *     context, stockCode, stockName,
 *     mode = UnifiedAgentRunner.MODE_QUICK
 * )
 *
 * // 展示結果
 * textView.text = result.summaryText
 * if (result.recommendation == "BUY") showBuyButton()
 * ```
 */
object UnifiedAgentRunner {

    private const val TAG = "UnifiedAgentRunner"

    // ════════════════════════════════════════════════════
    //  分析模式常量
    // ════════════════════════════════════════════════════

    /** Pipeline 深度分析模式（5-6 Agent 串/並行流水線） */
    const val MODE_PIPELINE = "pipeline"

    /** Quick 快速分析模式（2 Agent 並行） */
    const val MODE_QUICK = "quick"

    // ════════════════════════════════════════════════════
    //  統一結果類
    // ════════════════════════════════════════════════════

    /**
     * 統一分析結果
     *
     * 無論底層用 Pipeline 還是 Quick，返回結構一致。
     */
    data class Result(
        /** 股票代碼 */
        val stockCode: String,
        /** 股票名稱 */
        val stockName: String,
        /** 使用的分析模式 */
        val mode: String,
        /** 是否成功 */
        val success: Boolean,
        /** 綜合分數 0-100 */
        val overallScore: Int = 0,
        /** 建議：BUY / HOLD / SELL / WATCH */
        val recommendation: String? = null,
        /** 置信度：HIGH / MEDIUM / LOW */
        val confidence: String? = null,
        /** 風險等級：LOW / MEDIUM / HIGH / CRITICAL */
        val riskLevel: String? = null,
        /** 目標價 */
        val targetPrice: String? = null,
        /** 止損位 */
        val stopLoss: String? = null,
        /** 組合後可直接展示的文字摘要 */
        val summaryText: String = "",
        /** 原始完整輸出（調試用） */
        val rawOutput: String = "",
        /** 錯誤信息 */
        val errorMessage: String? = null,
        /** 耗時（毫秒） */
        val elapsedMs: Long = 0
    )

    // ════════════════════════════════════════════════════
    //  主入口
    // ════════════════════════════════════════════════════

    /**
     * 統一分析入口
     *
     * @param context ApplicationContext
     * @param stockCode 股票代碼（如 sh600519）
     * @param stockName 股票名稱（可選，用於 Pipeline 模式選擇）
     * @param mode 分析模式：MODE_PIPELINE 或 MODE_QUICK
     * @param sector 所屬板塊（可選，Pipeline 模式用於賽道識別）
     * @return Result 統一結果
     */
    suspend fun run(
        context: Context,
        stockCode: String,
        stockName: String? = null,
        mode: String = MODE_QUICK,
        sector: String? = null
    ): Result {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "開始統一分析 [$mode]: $stockCode ${stockName ?: ""}")

        return when (mode) {
            MODE_PIPELINE -> runPipeline(context, stockCode, stockName, sector, startTime)
            MODE_QUICK    -> runQuick(context, stockCode, stockName, startTime)
            else -> {
                Log.w(TAG, "未知模式 '$mode'，回退到 Quick 模式")
                runQuick(context, stockCode, stockName, startTime)
            }
        }
    }

    // ════════════════════════════════════════════════════
    //  Pipeline 模式（深度分析）
    // ════════════════════════════════════════════════════

    /**
     * Pipeline 深度分析：5-6 Agent 串/並行流水線
     */
    private suspend fun runPipeline(
        context: Context,
        stockCode: String,
        stockName: String?,
        sector: String?,
        startTime: Long
    ): Result = withContext(Dispatchers.IO) {
        try {
            val orchestrator = AgentPipelineOrchestrator(context)
            val target = stockName?.let { "$it($stockCode)" } ?: stockCode

            val pipelineResult = orchestrator.execute(
                target = target,
                sector = sector,
                forceMode = null  // AI 自動選擇模式
            )

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Pipeline 分析完成: $stockCode, 步驟=${pipelineResult.stepsCompleted}/${pipelineResult.totalSteps}, 耗時=${elapsed}ms")

            convertPipelineResult(pipelineResult, stockCode, stockName, elapsed)

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline 分析異常: $stockCode", e)
            Result(
                stockCode = stockCode,
                stockName = stockName ?: stockCode,
                mode = MODE_PIPELINE,
                success = false,
                errorMessage = "Pipeline 分析失敗: ${e.message}",
                summaryText = "❌ 深度分析失敗: ${e.message}",
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * 將 PipelineResult 轉換為統一 Result
     */
    private fun convertPipelineResult(
        pr: PipelineResult,
        stockCode: String,
        stockName: String?,
        elapsed: Long
    ): Result {
        val firstStock = pr.stocks.firstOrNull()

        // 構建文字摘要
        val sb = StringBuilder()
        sb.appendLine("## 🧠 ${pr.analysisMode} 深度分析")
        sb.appendLine("目標: ${pr.target} | 板塊: ${pr.sector}")
        sb.appendLine("完成步驟: ${pr.stepsCompleted}/${pr.totalSteps}")
        sb.appendLine()

        if (firstStock != null) {
            val score = firstStock.chainScore
            val risk = firstStock.riskResult
            val sentiment = firstStock.sentimentResult
            val plan = firstStock.tradePlan

            if (score != null) {
                sb.appendLine("### 📊 產業鏈打分")
                sb.appendLine("總分: ${score.totalScore}/100 | 壁壘: ${score.barrierLevel}")
                if (score.baseScore > 0) sb.appendLine("基礎分=${score.baseScore} 材料=${score.materialScore} 覆蓋=${score.coverageScore} 不可替代=${score.irreplaceScore}")
                if (score.overseasBonus > 0) sb.appendLine("海外加分: +${score.overseasBonus}")
                if (score.foreignRatingBonus > 0) sb.appendLine("外資加分: +${score.foreignRatingBonus}")
                sb.appendLine()
            }

            if (risk != null) {
                sb.appendLine("### 🛡 風控終審")
                sb.appendLine("風險等級: ${risk.riskLevel}")
                if (risk.adjustedScore > 0) sb.appendLine("對沖後分數: ${risk.adjustedScore}")
                if (risk.deductions.isNotEmpty()) {
                    sb.appendLine("扣分項:")
                    risk.deductions.forEach { sb.appendLine("  - ${it.item}: ${it.description} (-${it.score})") }
                }
                sb.appendLine()
            }

            if (sentiment != null) {
                sb.appendLine("### 📰 板塊&輿情")
                sb.appendLine("輿情得分: ${sentiment.sentimentScore} | 倉位微調: ${sentiment.positionAdjust}")
                sb.appendLine("理由: ${sentiment.reason}")
                sb.appendLine()
            }

            if (plan != null) {
                sb.appendLine("### 📈 交易方案")
                sb.appendLine("低吸區間: ${plan.entryZones.joinToString(", ")}")
                sb.appendLine("止損位: ${plan.stopLoss}")
                sb.appendLine("止盈目標: ${plan.targets.joinToString(", ")}")
                sb.appendLine("最大倉位: ${plan.maxPosition} | 分倉: ${plan.splitRatio}")
                if (plan.tradeRules.isNotEmpty()) {
                    sb.appendLine("交易紀律:")
                    plan.tradeRules.forEach { sb.appendLine("  - $it") }
                }
                sb.appendLine()
            }

            sb.appendLine("---")
            sb.appendLine("最終建議倉位: ${firstStock.finalPosition}")
            sb.appendLine(if (firstStock.passed) "✅ 通過全部流水線篩選" else "❌ 未通過篩選")
        } else {
            sb.appendLine("暫無具體股票分析結果")
        }

        // 各 Agent 的原始深度分析文本
        if (pr.stepAnalyses.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine("## 📋 各 Agent 深度分析詳情")
            sb.appendLine()
            val sortedSteps = pr.stepAnalyses.toSortedMap()
            for ((idx, text) in sortedSteps) {
                sb.appendLine("### Agent 步驟 $idx")
                sb.appendLine(text.trim())
                sb.appendLine()
            }
        }

        // 推薦轉換
        val recommendation = when {
            firstStock?.passed == true && (firstStock.chainScore?.totalScore ?: 0) >= 70 -> "BUY"
            firstStock?.passed == true -> "HOLD"
            firstStock != null -> "WATCH"
            else -> null
        }

        return Result(
            stockCode = stockCode,
            stockName = stockName ?: stockCode,
            mode = MODE_PIPELINE,
            success = pr.errorMessage == null,
            overallScore = firstStock?.chainScore?.totalScore ?: 0,
            recommendation = recommendation,
            confidence = if ((firstStock?.chainScore?.totalScore ?: 0) >= 70) "HIGH" else "MEDIUM",
            riskLevel = firstStock?.riskResult?.riskLevel,
            targetPrice = firstStock?.tradePlan?.targets?.firstOrNull(),
            stopLoss = firstStock?.tradePlan?.stopLoss,
            summaryText = sb.toString(),
            rawOutput = pr.toString(),
            errorMessage = pr.errorMessage,
            elapsedMs = elapsed
        )
    }

    // ════════════════════════════════════════════════════
    //  Quick 模式（快速分析）
    // ════════════════════════════════════════════════════

    /**
     * Quick 快速分析：並行運行 StockAnalysisAgent + RiskManagementAgent
     */
    private suspend fun runQuick(
        context: Context,
        stockCode: String,
        stockName: String?,
        startTime: Long
    ): Result = withContext(Dispatchers.IO) {
        val result = coroutineScope {
            val analysisJob = async {
                try {
                    StockAnalysisAgent(context).analyze(stockCode, stockName)
                } catch (e: Exception) {
                    Log.w(TAG, "StockAnalysisAgent 失敗: ${e.message}")
                    null
                }
            }
            val riskJob = async {
                try {
                    RiskManagementAgent(context).assessStockRisk(stockCode)
                } catch (e: Exception) {
                    Log.w(TAG, "RiskManagementAgent 失敗: ${e.message}")
                    null
                }
            }
            // 並行跑大盤環境分析
            val marketJob = async {
                try {
                    MarketAnalyzer.analyze(context, emptyList())
                } catch (e: Exception) {
                    Log.w(TAG, "MarketAnalyzer 失敗: ${e.message}")
                    null
                }
            }
            Triple(analysisJob.await(), riskJob.await(), marketJob.await())
        }

        val analysis = result.first
        val risk = result.second
        val marketReport = result.third
        val elapsed = System.currentTimeMillis() - startTime

        // 組合文字摘要
        val sb = StringBuilder()
        var recommendation: String? = null

        // ── 大盤環境提示（初始佔位，分析完成後追加警告） ──
        val marketWarnings = mutableListOf<String>()
        if (marketReport != null) {
            val trend = marketReport.trend
            val sellType = marketReport.sellType
            when (trend.direction) {
                "BEARISH" -> {
                    marketWarnings.add("⚠️ 大盤下行（強度${trend.strength}/100）" +
                        if (sellType.sellType == "INSTITUTIONAL_EXIT") " | 主力撤資中"
                        else if (sellType.sellType == "QUANT_CRASH") " | 量化砸盤"
                        else "")
                    marketWarnings.add("建議降低倉位，關注防禦板塊")
                }
                "OSCILLATION" -> {
                    if (trend.strength > 40)
                        marketWarnings.add("⚡ 大盤震蕩加劇（強度${trend.strength}）— 控制倉位")
                    else
                        marketWarnings.add("📊 大盤震蕩（強度${trend.strength}）— 輕倉操作")
                }
            }
        }

        if (analysis != null && analysis.success) {
            recommendation = analysis.recommendation
            sb.appendLine("## 📊 AI 綜合分析")
            sb.appendLine("評分: ${analysis.overallScore}/100 | 建議: ${analysis.recommendation} | 置信度: ${analysis.confidence}")
            sb.appendLine("技術面: ${analysis.technicalScore}/100 | 基本面: ${analysis.fundamentalScore}/100 | 資金面: ${analysis.fundFlowScore}/100")
            if (analysis.targetPrice.isNotBlank()) sb.appendLine("目標價: ${analysis.targetPrice}")
            if (analysis.stopLoss.isNotBlank()) sb.appendLine("止損位: ${analysis.stopLoss}")
            if (analysis.riskFactors.isNotEmpty()) sb.appendLine("風險: ${analysis.riskFactors.joinToString("、")}")
            if (analysis.reasoning.isNotBlank()) sb.appendLine("\n${analysis.reasoning}")
            sb.appendLine()
        } else {
            sb.appendLine("AI 綜合分析：失敗或超時")
        }

        if (risk != null && risk.success) {
            sb.appendLine("## 🛡 風控評估")
            sb.appendLine(risk.assessment)
        } else {
            sb.appendLine("風控評估：失敗或超時")
        }

        // ── 大盤環境區塊（根據分析結果追加警告） ──
        if (marketWarnings.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## 🌐 大盤環境")
            marketWarnings.forEach { sb.appendLine(it) }
            // 如果大盤下行但 AI 建議買入，追加風險警告
            if (recommendation == "BUY" && marketReport?.trend?.direction == "BEARISH") {
                sb.appendLine()
                sb.appendLine("🔴 **注意**: 大盤下行環境下，買入建議需謹慎！")
            }
            sb.appendLine()
        }

        Log.i(TAG, "Quick 分析完成: $stockCode, 耗時=${elapsed}ms")

        Result(
            stockCode = stockCode,
            stockName = stockName ?: stockCode,
            mode = MODE_QUICK,
            success = analysis?.success == true,
            overallScore = analysis?.overallScore ?: 0,
            recommendation = recommendation,
            confidence = analysis?.confidence,
            riskLevel = null,
            targetPrice = analysis?.targetPrice?.takeIf { it.isNotBlank() },
            stopLoss = analysis?.stopLoss?.takeIf { it.isNotBlank() },
            summaryText = sb.toString(),
            rawOutput = (analysis?.rawOutput ?: "") + "\n\n" + (risk?.assessment ?: ""),
            errorMessage = if (analysis?.success != true) "AI 分析失敗" else null,
            elapsedMs = elapsed
        )
    }
}
