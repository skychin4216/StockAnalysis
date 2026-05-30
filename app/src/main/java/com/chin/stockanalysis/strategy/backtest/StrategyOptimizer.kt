package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.models.WeightFactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * ## 策略权重优化器
 *
 * 两种模式：
 * 1. **AI 模式**：发送回测报告给LLM，由AI分析并建议权重调整
 * 2. **网格搜索模式**：穷举权重组合，找到历史准确率最高的配置
 *
 * ### 使用方式
 * ```kotlin
 * val optimizer = StrategyOptimizer(context)
 *
 * // AI 模式（需要网络 + API key）
 * val aiSuggestions = optimizer.aiOptimize(strategy, backtestReport)
 *
 * // 网格搜索模式（离线可用）
 * val bestWeights = optimizer.gridSearch(strategy, historicalData, days)
 * ```
 */
class StrategyOptimizer(private val context: Context) {

    companion object {
        private const val TAG = "StrategyOptimizer"
    }

    private val backtestEngine = HistoricalBacktestEngine(context)

    // ══════════════════════════════════════
    // AI 优化模式
    // ══════════════════════════════════════

    data class AIOptimizationResult(
        val suggestedWeights: List<WeightFactor>,
        val reasoning: String,
        val confidence: Float
    )

    /**
     * 构建 AI 优化 Prompt
     * 将历史回测结果 + 当前权重发送给 LLM 分析
     *
     * @return Prompt 文本，可直接发送给 ChatTabFragment
     */
    fun buildOptimizationPrompt(strategy: Strategy, report: HistoricalBacktestReport): String = buildString {
        appendLine("你是量化策略专家。请分析以下策略回测结果，并建议权重调整方案。")
        appendLine()
        appendLine("【策略信息】")
        appendLine("名称: ${strategy.name}")
        appendLine("描述: ${strategy.description}")
        appendLine()

        appendLine("【当前权重配置】")
        appendLine("满分100%，各因子权重：")
        for (factor in strategy.weightFactors) {
            appendLine("  - ${factor.label}(${factor.key}): ${factor.weight}% — ${factor.description}")
        }
        val currentSum = strategy.weightFactors.sumOf { it.weight }
        appendLine("权重合计: ${currentSum}%")
        appendLine()

        appendLine("【历史回测结果】")
        if (report.strategyReports.isNotEmpty()) {
            val r = report.strategyReports.first()
            appendLine("期间: ${report.dateRange} (${report.totalDays}个交易日)")
            appendLine("买入准确率: ${"%.1f".format(r.buyAccuracy * 100)}% (${r.correctBuys}/${r.totalBuys})")
            appendLine("平均收益: ${"%.2f".format(r.avgReturn)}%")
            appendLine("最大收益: ${"%.2f".format(r.maxGain)}% | 最大亏损: ${"%.2f".format(r.maxLoss)}%")
            appendLine("总命中: ${r.totalHits}只")
            appendLine("当前评级: ${r.grade}")
        } else {
            appendLine("（暂无足够历史数据）")
        }
        appendLine()

        appendLine("【优化目标】")
        appendLine("1. 提高买入准确率（当前目标≥60%）")
        appendLine("2. 提高平均收益率")
        appendLine("3. 降低最大回撤风险")
        appendLine()

        appendLine("【输出要求】")
        appendLine("请以下格式给出建议：")
        appendLine("1. 各因子建议权重（合计必须=100%）")
        appendLine("2. 调整理由（为什么这样调）")
        appendLine("3. 调整后预期效果")
        appendLine()
        appendLine("请直接给出分析，不要问问题。")
    }

    // ══════════════════════════════════════
    // 网格搜索模式
    // ══════════════════════════════════════

    data class GridSearchResult(
        val bestWeights: List<WeightFactor>,
        val bestAccuracy: Float,
        val bestAvgReturn: Double,
        val totalCombinations: Int
    )

    /**
     * 网格搜索最优权重
     * 对每个因子尝试 [10%, 15%, 20%, 25%, 30%, 35%, 40%] 等离散值，
     * 在历史数据上评估准确率，找到最优组合。
     *
     * @param strategy 要优化的策略（需要有 getHistoricalStocks 数据）
     * @param availableDates 可用的历史日期
     * @return 最优权重配置
     */
    suspend fun gridSearch(
        strategy: Strategy,
        availableDates: List<String>
    ): GridSearchResult = withContext(Dispatchers.IO) {
        val factors = strategy.weightFactors
        if (factors.isEmpty() || availableDates.size < 5) {
            return@withContext GridSearchResult(strategy.weightFactors, 0f, 0.0, 0)
        }

        // 搜索空间：每个因子可能的权重值（步长5%）
        val step = 5
        val candidates = (5..50 step step).toList()

        var bestAccuracy = 0f
        var bestAvgReturn = 0.0
        var bestWeights = factors
        var totalCombinations = 0

        when (factors.size) {
            3 -> {
                // 3因子的权重组合 O(n^3)
                for (w1 in candidates) {
                    for (w2 in candidates) {
                        for (w3 in candidates) {
                            if (w1 + w2 + w3 != 100) continue
                            totalCombinations++
                            val adjustedFactors = listOf(
                                factors[0].copy(weight = w1),
                                factors[1].copy(weight = w2),
                                factors[2].copy(weight = w3)
                            )
                            val (acc, ret) = evaluateWeights(strategy, adjustedFactors, availableDates)
                            if (acc > bestAccuracy) {
                                bestAccuracy = acc
                                bestAvgReturn = ret
                                bestWeights = adjustedFactors
                            }
                        }
                    }
                }
            }
            4 -> {
                for (w1 in candidates) for (w2 in candidates)
                    for (w3 in candidates) for (w4 in candidates) {
                        if (w1 + w2 + w3 + w4 != 100) continue
                        totalCombinations++
                        val adjusted = listOf(
                            factors[0].copy(weight = w1), factors[1].copy(weight = w2),
                            factors[2].copy(weight = w3), factors[3].copy(weight = w4)
                        )
                        val (acc, ret) = evaluateWeights(strategy, adjusted, availableDates)
                        if (acc > bestAccuracy) { bestAccuracy = acc; bestAvgReturn = ret; bestWeights = adjusted }
                    }
            }
            5 -> {
                for (w1 in candidates) for (w2 in candidates)
                    for (w3 in candidates) for (w4 in candidates) for (w5 in candidates) {
                        if (w1 + w2 + w3 + w4 + w5 != 100) continue
                        totalCombinations++
                        val adjusted = listOf(
                            factors[0].copy(weight = w1), factors[1].copy(weight = w2),
                            factors[2].copy(weight = w3), factors[3].copy(weight = w4),
                            factors[4].copy(weight = w5)
                        )
                        val (acc, ret) = evaluateWeights(strategy, adjusted, availableDates)
                        if (acc > bestAccuracy) { bestAccuracy = acc; bestAvgReturn = ret; bestWeights = adjusted }
                    }
            }
            else -> {
                // 太多因子 → 随机采样
                totalCombinations = 200
                for (i in 0 until 200) {
                    val adjusted = randomWeights(factors)
                    val (acc, ret) = evaluateWeights(strategy, adjusted, availableDates)
                    if (acc > bestAccuracy) { bestAccuracy = acc; bestAvgReturn = ret; bestWeights = adjusted }
                }
            }
        }

        Log.i(TAG, "网格搜索完成: $totalCombinations 组合 → 最优准确率 ${"%.1f".format(bestAccuracy * 100)}%")
        GridSearchResult(bestWeights, bestAccuracy, bestAvgReturn, totalCombinations)
    }

    /**
     * 评估一组权重在历史数据上的表现
     * @return Pair(准确率, 平均收益)
     */
    private suspend fun evaluateWeights(
        strategy: Strategy,
        adjustedFactors: List<WeightFactor>,
        dates: List<String>
    ): Pair<Float, Double> {
        strategy.weightFactors = adjustedFactors
        // 运行一次回测
        val report = backtestEngine.runHistoricalBacktest(
            strategies = listOf(strategy),
            tradingDays = minOf(dates.size - 1, 30)
        )
        val r = report.strategyReports.firstOrNull()
        return if (r != null) Pair(r.buyAccuracy, r.avgReturn) else Pair(0f, 0.0)
    }

    /**
     * 生成随机权重（合计=100%）
     */
    private fun randomWeights(factors: List<WeightFactor>): List<WeightFactor> {
        val n = factors.size
        val rawWeights = (0 until n).map { (5..50).random() }
        val total = rawWeights.sum()
        val normalized = rawWeights.map { w -> (w * 100 / total).coerceIn(5, 50) }
        // 调整最后一个以确保总和=100
        val diff = 100 - normalized.sum()
        return (0 until n).mapIndexed { i, idx ->
            factors[idx].copy(weight = normalized[idx] + if (i == n - 1) diff else 0)
        }
    }

    // ══════════════════════════════════════
    // 自动优化（AI + 网格搜索）
    // ══════════════════════════════════════

    data class OptimizationResult(
        val originalWeights: List<WeightFactor>,
        val optimizedWeights: List<WeightFactor>,
        val improvementScore: Float,    // 提升幅度
        val method: String,              // "grid_search" / "ai_prompt"
        val aiPrompt: String? = null     // 如果可用AI，返回prompt
    )

    /**
     * 一键优化：先网格搜索 → 如果提升不明显，生成AI prompt
     */
    suspend fun autoOptimize(
        strategy: Strategy,
        report: HistoricalBacktestReport
    ): OptimizationResult = withContext(Dispatchers.IO) {
        val originalWeights = strategy.weightFactors.toList()
        val dates = report.strategyReports.firstOrNull()?.let {
            (0..30).map { "" }  // placeholder
        } ?: listOf()

        // 方法1：网格搜索
        val originalAcc = report.strategyReports.firstOrNull()?.buyAccuracy ?: 0f
        val gridResult = gridSearch(strategy, dates)

        val improvement = gridResult.bestAccuracy - originalAcc

        // 方法2：如果网格搜索提升不明显，生成AI prompt
        val aiPrompt = if (improvement < 0.05f) {
            buildOptimizationPrompt(strategy, report)
        } else null

        OptimizationResult(
            originalWeights = originalWeights,
            optimizedWeights = gridResult.bestWeights,
            improvementScore = improvement,
            method = if (gridResult.bestWeights != originalWeights) "grid_search(${gridResult.totalCombinations}种组合)" else "未找到更优配置",
            aiPrompt = aiPrompt
        )
    }

    fun summarize(result: OptimizationResult): String = buildString {
        appendLine("━━━ ⚙️ 权重优化结果 ━━━")
        appendLine("方法: ${result.method}")
        appendLine("准确率提升: ${"%.1f".format(result.improvementScore * 100)}%")
        appendLine()
        appendLine("原始权重 → 优化后权重:")
        for ((orig, opt) in result.originalWeights.zip(result.optimizedWeights)) {
            val arrow = if (orig.weight != opt.weight) "${orig.weight}% → ${opt.weight}%" else "${orig.weight}% (未变)"
            appendLine("  ${orig.label}: $arrow")
        }
        if (result.aiPrompt != null) {
            appendLine()
            appendLine("💡 建议使用AI进一步优化（已生成Prompt，可在对话Tab发送）")
        }
    }
}