package com.chin.stockanalysis.config

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.agent.framework.UnifiedAgentRunner
import com.chin.stockanalysis.agent.pipeline.AgentPipelineOrchestrator
import com.chin.stockanalysis.strategy.HoldingPeriod
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.StrategyEngineHolder
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.stock.StockRealtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ## Agent 路線執行器（Phase 8 + 9）
 *
 * 統一 Agent 執行入口，根據週期和配置驅動不同的 Agent 模式：
 * - **QUICK** — UnifiedAgentRunner(MODE_QUICK)，2 Agent 並行，30秒內完成
 * - **PIPELINE** — AgentPipelineOrchestrator，六智體/七智體流水線
 * - **V2** — V2AgentRunner，全周期投研（市場環境+利潤質量+決策矩陣）
 *
 * ### 設計理念（借鑑 opencode 配置驅動 + 可插拔）
 * 每個週期的 Agent 配置通過 JSON 文件定義（assets/usecases/ 目錄下的 *_agent.json），
 * 支持靈活的 pipeline 定義和後處理規則。
 *
 * ### 使用方式
 * ```kotlin
 * val executor = AgentRouteExecutor(context)
 * val result = executor.execute(HoldingPeriod.MID, stocks)
 * // result.candidateStocks → 排序後的候選清單
 * // result.postProcess → 止損/止盈/持倉限制配置
 * ```
 */
class AgentRouteExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AgentRouteExecutor"

        /** Agent 配置文件目錄 */
        private const val CONFIG_DIR = "usecases"

        /** 各週期的配置文件名 */
        private fun configFileName(period: HoldingPeriod): String = when (period) {
            HoldingPeriod.ULTRA_SHORT -> "ultra_short_agent.json"
            HoldingPeriod.SHORT       -> "short_agent.json"
            HoldingPeriod.MID         -> "mid_agent.json"
            HoldingPeriod.LONG        -> "long_agent.json"
        }
    }

    /**
     * Agent 執行結果
     */
    data class AgentResult(
        /** 週期 */
        val period: HoldingPeriod,
        /** 使用的模式 */
        val mode: String,
        /** 是否成功 */
        val success: Boolean,
        /** 候選股票清單（已排序） */
        val candidateStocks: List<AgentCandidate>,
        /** 後處理配置 */
        val postProcess: PostProcessConfig,
        /** 各股票的 Agent 分析結果 */
        val analysisTexts: Map<String, String> = emptyMap(),
        /** 錯誤信息 */
        val errorMessage: String? = null,
        /** 耗時 */
        val elapsedMs: Long = 0
    )

    /**
     * Agent 候選股票
     */
    data class AgentCandidate(
        val stockCode: String,
        val stockName: String,
        /** 綜合評分 0-100 */
        val overallScore: Int,
        /** 建議：BUY / HOLD / SELL / WATCH */
        val recommendation: String,
        /** 風險等級 */
        val riskLevel: String?,
        /** 詳細分析文本 */
        val analysisText: String,
        /** 當前價格 */
        val currentPrice: Double,
        /** 漲跌幅 */
        val changePercent: Double
    )

    /**
     * 後處理配置（從 JSON 讀取）
     */
    data class PostProcessConfig(
        /** 排序字段 */
        val sortBy: String = "overallScore",
        /** 最大結果數 */
        val maxResults: Int = 5,
        /** 止損百分比 */
        val stopLoss: Double = -8.0,
        /** 止盈百分比 */
        val takeProfit: Double = 20.0,
        /** 是否使用 AutoSellEngine */
        val autoSellEngine: Boolean = true
    )

    /**
     * Agent 配置（從 JSON 解析）
     */
    data class AgentConfig(
        val period: String,
        val mode: String,
        val agents: List<String>,
        val parallel: Boolean,
        val timeout: Int,
        val maxBatchSize: Int,
        val postProcess: PostProcessConfig
    )

    // ═══════════════════════════════════════════════════════════════
    // 主入口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根據週期執行 Agent 分析
     *
     * @param period 持倉週期
     * @param stocks 預篩選的股票列表（來自 Legacy 策略篩選）
     * @return Agent 執行結果
     */
    suspend fun execute(
        period: HoldingPeriod,
        stocks: List<StockRealtime>
    ): AgentResult {
        val startTime = System.currentTimeMillis()
        val config = loadAgentConfig(period)
        val mode = config.mode

        Log.i(TAG, "啟動 Agent 路線 [$period/$mode]: ${stocks.size} 只候選股票")

        return try {
            // 根據模式分發
            val candidates = when (mode) {
                "QUICK"    -> executeQuick(config, stocks, period)
                "PIPELINE" -> executePipeline(config, stocks, period)
                "V2"       -> executeV2(config, stocks, period)
                else       -> executeQuick(config, stocks, period)
            }

            // 後處理：排序 + 截斷
            val sorted = applyPostProcess(candidates, config.postProcess)

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Agent 路線完成 [$period/$mode]: ${sorted.size} 只結果, ${elapsed}ms")

            AgentResult(
                period = period,
                mode = mode,
                success = true,
                candidateStocks = sorted,
                postProcess = config.postProcess,
                elapsedMs = elapsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Agent 路線執行異常 [$period/$mode]", e)
            AgentResult(
                period = period,
                mode = mode,
                success = false,
                candidateStocks = emptyList(),
                postProcess = config.postProcess,
                errorMessage = e.message,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 各模式執行
    // ═══════════════════════════════════════════════════════════════

    /**
     * QUICK 模式：UnifiedAgentRunner(MODE_QUICK)
     * 2 Agent 並行（StockAnalysisAgent + RiskManagementAgent），30秒內完成
     */
    private suspend fun executeQuick(
        config: AgentConfig,
        stocks: List<StockRealtime>,
        period: HoldingPeriod
    ): List<AgentCandidate> = withContext(Dispatchers.IO) {
        // QUICK 模式限制批量大小，避免超時
        val batch = stocks.take(config.maxBatchSize)
        Log.i(TAG, "[QUICK] 批量分析 ${batch.size}/${stocks.size} 只股票")

        // 並行分析每只股票
        coroutineScope {
            batch.map { stock ->
                async {
                    try {
                        val result = UnifiedAgentRunner.run(
                            context = context,
                            stockCode = stock.code,
                            stockName = stock.name,
                            mode = UnifiedAgentRunner.MODE_QUICK
                        )
                        AgentCandidate(
                            stockCode = stock.code,
                            stockName = stock.name,
                            overallScore = result.overallScore,
                            recommendation = result.recommendation ?: "WATCH",
                            riskLevel = result.riskLevel,
                            analysisText = result.summaryText ?: "",
                            currentPrice = stock.price,
                            changePercent = stock.changePercent
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "[QUICK] ${stock.name} 分析失敗: ${e.message}")
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }
    }

    /**
     * PIPELINE 模式：AgentPipelineOrchestrator
     * 六智體/七智體流水線，深度分析
     */
    private suspend fun executePipeline(
        config: AgentConfig,
        stocks: List<StockRealtime>,
        period: HoldingPeriod
    ): List<AgentCandidate> = withContext(Dispatchers.IO) {
        // PIPELINE 模式較慢，限制批量大小
        val batch = stocks.take(config.maxBatchSize)
        Log.i(TAG, "[PIPELINE] 批量分析 ${batch.size}/${stocks.size} 只股票")

        // 串行分析（Pipeline 較重，避免並發過多）
        val candidates = mutableListOf<AgentCandidate>()
        for (stock in batch) {
            try {
                val orchestrator = AgentPipelineOrchestrator(context)
                val target = "${stock.name}(${stock.code})"
                val pipelineResult = orchestrator.execute(
                    target = target,
                    sector = null,
                    forceMode = null
                )

                // 從 Pipeline 結果提取候選
                val firstStock = pipelineResult.stocks.firstOrNull()
                val score = firstStock?.chainScore?.totalScore ?: 0
                // PipelineStockResult 沒有 finalRecommendation，根據 passed + 分數推導
                val recommendation = when {
                    firstStock == null -> "WATCH"
                    firstStock.passed && score >= 70 -> "BUY"
                    firstStock.passed -> "HOLD"
                    else -> "WATCH"
                }

                // 構建摘要文本（PipelineResult 沒有 summaryText，手工拼接）
                val sb = StringBuilder()
                sb.appendLine("## Pipeline 深度分析: ${stock.name}")
                sb.appendLine("板塊: ${pipelineResult.sector} | 步驟: ${pipelineResult.stepsCompleted}/${pipelineResult.totalSteps}")
                if (firstStock != null) {
                    sb.appendLine("產業鏈得分: $score/100 | 通過: ${firstStock.passed}")
                    firstStock.riskResult?.let { sb.appendLine("風控: ${it.riskLevel}") }
                    firstStock.tradePlan?.let {
                        sb.appendLine("止損: ${it.stopLoss} | 目標: ${it.targets.joinToString(",")}")
                    }
                }

                candidates.add(AgentCandidate(
                    stockCode = stock.code,
                    stockName = stock.name,
                    overallScore = score,
                    recommendation = recommendation,
                    riskLevel = firstStock?.riskResult?.riskLevel,
                    analysisText = sb.toString(),
                    currentPrice = stock.price,
                    changePercent = stock.changePercent
                ))
            } catch (e: Exception) {
                Log.w(TAG, "[PIPELINE] ${stock.name} 分析失敗: ${e.message}")
            }
        }
        candidates
    }

    /**
     * V2 模式：V2AgentRunner
     * 全周期投研（市場環境+利潤質量+決策矩陣）
     */
    private suspend fun executeV2(
        config: AgentConfig,
        stocks: List<StockRealtime>,
        period: HoldingPeriod
    ): List<AgentCandidate> = withContext(Dispatchers.IO) {
        val batch = stocks.take(config.maxBatchSize)
        Log.i(TAG, "[V2] 批量分析 ${batch.size}/${stocks.size} 只股票")

        // 並行分析
        coroutineScope {
            batch.map { stock ->
                async {
                    try {
                        val result = UnifiedAgentRunner.run(
                            context = context,
                            stockCode = stock.code,
                            stockName = stock.name,
                            mode = UnifiedAgentRunner.MODE_V2
                        )
                        AgentCandidate(
                            stockCode = stock.code,
                            stockName = stock.name,
                            overallScore = result.overallScore,
                            recommendation = result.recommendation ?: "WATCH",
                            riskLevel = result.riskLevel,
                            analysisText = result.summaryText ?: "",
                            currentPrice = stock.price,
                            changePercent = stock.changePercent
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "[V2] ${stock.name} 分析失敗: ${e.message}")
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 後處理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 應用後處理：排序 + 截斷
     */
    private fun applyPostProcess(
        candidates: List<AgentCandidate>,
        config: PostProcessConfig
    ): List<AgentCandidate> {
        val sorted = when (config.sortBy) {
            "overallScore" -> candidates.sortedByDescending { it.overallScore }
            "strength"     -> candidates.sortedByDescending { it.overallScore }
            "recommendation" -> candidates.sortedByDescending {
                when (it.recommendation) { "BUY" -> 4; "WATCH" -> 3; "HOLD" -> 2; "SELL" -> 1; else -> 0 }
            }
            else -> candidates.sortedByDescending { it.overallScore }
        }
        return sorted.take(config.maxResults)
    }

    // ═══════════════════════════════════════════════════════════════
    // 配置加載（Phase 9: opencode 模式 JSON 配置）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 從 assets/usecases/ 加載指定週期的 Agent 配置
     * 如果配置文件不存在，使用默認配置
     */
    private fun loadAgentConfig(period: HoldingPeriod): AgentConfig {
        val fileName = configFileName(period)
        val defaultMode = FeatureFlagManager.getAgentMode(period)

        return try {
            val json = context.assets.open("$CONFIG_DIR/$fileName").bufferedReader().use { it.readText() }
            parseConfig(json, period, defaultMode)
        } catch (e: Exception) {
            Log.w(TAG, "配置文件 ${fileName} 不存在，使用默認配置 [${defaultMode}]")
            getDefaultConfig(period, defaultMode)
        }
    }

    /**
     * 解析 JSON 配置
     */
    private fun parseConfig(json: String, period: HoldingPeriod, defaultMode: String): AgentConfig {
        val obj = JSONObject(json)
        val postObj = obj.optJSONObject("postProcess")

        return AgentConfig(
            period = obj.optString("period", period.name),
            mode = obj.optString("mode", defaultMode),
            agents = obj.optJSONArray("agents")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            parallel = obj.optBoolean("parallel", true),
            timeout = obj.optInt("timeout", 60),
            maxBatchSize = obj.optInt("maxBatchSize", getDefaultBatchSize(period)),
            postProcess = PostProcessConfig(
                sortBy = postObj?.optString("sortBy", "overallScore") ?: "overallScore",
                maxResults = postObj?.optInt("maxResults", getDefaultMaxResults(period))
                    ?: getDefaultMaxResults(period),
                stopLoss = postObj?.optDouble("stopLoss", getDefaultStopLoss(period))
                    ?: getDefaultStopLoss(period),
                takeProfit = postObj?.optDouble("takeProfit", getDefaultTakeProfit(period))
                    ?: getDefaultTakeProfit(period),
                autoSellEngine = postObj?.optBoolean("autoSellEngine", true) ?: true
            )
        )
    }

    /**
     * 獲取默認配置（配置文件不存在時使用）
     */
    private fun getDefaultConfig(period: HoldingPeriod, mode: String): AgentConfig {
        return AgentConfig(
            period = period.name,
            mode = mode,
            agents = when (mode) {
                "QUICK" -> listOf("StockAnalysisAgent", "RiskManagementAgent")
                "PIPELINE" -> listOf("AgentPipelineOrchestrator")
                "V2" -> listOf("V2AgentRunner")
                else -> listOf("StockAnalysisAgent", "RiskManagementAgent")
            },
            parallel = mode != "PIPELINE",
            timeout = when (mode) { "QUICK" -> 30; "PIPELINE" -> 120; "V2" -> 90; else -> 60 },
            maxBatchSize = getDefaultBatchSize(period),
            postProcess = PostProcessConfig(
                sortBy = "overallScore",
                maxResults = getDefaultMaxResults(period),
                stopLoss = getDefaultStopLoss(period),
                takeProfit = getDefaultTakeProfit(period),
                autoSellEngine = period != HoldingPeriod.ULTRA_SHORT
            )
        )
    }

    /** 各週期默認批量大小 */
    private fun getDefaultBatchSize(period: HoldingPeriod): Int = when (period) {
        HoldingPeriod.ULTRA_SHORT -> 5   // 時效優先
        HoldingPeriod.SHORT       -> 8
        HoldingPeriod.MID         -> 5   // 深度分析
        HoldingPeriod.LONG        -> 5
    }

    /** 各週期默認最大持倉 */
    private fun getDefaultMaxResults(period: HoldingPeriod): Int = when (period) {
        HoldingPeriod.ULTRA_SHORT -> 3
        HoldingPeriod.SHORT       -> 3
        HoldingPeriod.MID         -> 5
        HoldingPeriod.LONG        -> 5
    }

    /** 各週期默認止損 */
    private fun getDefaultStopLoss(period: HoldingPeriod): Double = when (period) {
        HoldingPeriod.ULTRA_SHORT -> -2.0
        HoldingPeriod.SHORT       -> -8.0
        HoldingPeriod.MID         -> -12.0
        HoldingPeriod.LONG        -> -20.0
    }

    /** 各週期默認止盈 */
    private fun getDefaultTakeProfit(period: HoldingPeriod): Double = when (period) {
        HoldingPeriod.ULTRA_SHORT -> 3.0
        HoldingPeriod.SHORT       -> 15.0
        HoldingPeriod.MID         -> 30.0
        HoldingPeriod.LONG        -> 50.0
    }

    // ═══════════════════════════════════════════════════════════════
    // 便捷方法：Legacy → Agent 橋接
    // ═══════════════════════════════════════════════════════════════

    /**
     * 從 Legacy 策略篩選結果中提取候選股票，交給 Agent 深度分析
     *
     * @param period 週期
     * @param screenings Legacy 策略篩選結果
     * @param stocks 原始股票數據（用於獲取即時價格）
     * @return Agent 執行結果
     */
    suspend fun executeFromLegacy(
        period: HoldingPeriod,
        screenings: Map<Strategy, ScreeningResult>,
        stocks: List<StockRealtime>
    ): AgentResult {
        // 從 Legacy 篩選結果中提取候選股票（按強度排序）
        val candidateCodes = mutableMapOf<String, Int>() // code → maxStrength
        for ((_, result) in screenings) {
            for (signal in result.signals) {
                val existing = candidateCodes[signal.stockCode] ?: 0
                if (signal.strength > existing) {
                    candidateCodes[signal.stockCode] = signal.strength
                }
            }
        }

        // 取 Top N 候選
        val batchSize = getDefaultBatchSize(period)
        val topCodes = candidateCodes.entries
            .sortedByDescending { it.value }
            .take(batchSize * 2) // 取兩倍批量，讓 Agent 有選擇空間
            .map { it.key }
            .toSet()

        val candidateStocks = stocks.filter { it.code in topCodes }
        Log.i(TAG, "從 Legacy 篩選 ${candidateCodes.size} 只 → 取 Top ${candidateStocks.size} 只交給 Agent")

        return execute(period, candidateStocks)
    }
}
