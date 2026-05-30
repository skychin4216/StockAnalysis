package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.Strategy
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.WeightFactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ## 策略自测调优引擎
 *
 * 通过"回测→评估→调优→再回测"的闭环，自动优化策略权重因子。
 *
 * ### 工作流程
 * ```
 * 1. 跑最近 N 个交易日的历史回测
 * 2. 逐策略统计买入准确率 + 平均收益
 * 3. 如果准确率 < 阈值 → 微调权重因子（±5 偏置）
 * 4. 再次回测 → 对比优化前后效果
 * 5. 输出调优报告
 * ```
 *
 * ### 使用方式
 * ```kotlin
 * val tuner = StrategySelfTuner(context)
 * val report = tuner.selfTune(
 *     strategies = engine.getStrategies(),
 *     backtestDays = 30,
 *     targetAccuracy = 0.55f
 * )
 * // report.beforeAccuracy / report.afterAccuracy
 * ```
 */
class StrategySelfTuner(private val context: Context) {

    companion object {
        private const val TAG = "StrategySelfTuner"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val db = StockDatabase.getInstance(context)

    /**
     * 自测调优结果
     */
    data class TuneReport(
        /** 回测区间 */
        val dateRange: String,
        /** 回测天数 */
        val backtestDays: Int,
        /** 每个策略的调优详情 */
        val strategyTuneDetails: List<StrategyTuneDetail>,
        /** 调优总结 */
        val summary: String
    )

    data class StrategyTuneDetail(
        val strategyId: String,
        val strategyName: String,
        /** 调优前准确率 */
        val beforeAccuracy: Float,
        /** 调优前平均收益 */
        val beforeAvgReturn: Double,
        /** 调优后准确率 */
        val afterAccuracy: Float,
        /** 调优后平均收益 */
        val afterAvgReturn: Double,
        /** 是否进行了调优 */
        val wasTuned: Boolean,
        /** 权重调整记录 */
        val weightChanges: List<String>
    )

    /**
     * 执行自测调优
     *
     * @param strategies 要调优的策略列表
     * @param backtestDays 回测天数
     * @param targetAccuracy 目标买入准确率 (0.0~1.0)
     */
    suspend fun selfTune(
        strategies: List<Strategy>,
        backtestDays: Int = 30,
        targetAccuracy: Float = 0.55f
    ): TuneReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "━━━ 开始策略自测调优: ${backtestDays}天, 目标准确率 ${"%.0f".format(targetAccuracy * 100)}% ━━━")

        val availableDates = db.dailySnapshotDao().getAvailableDates(backtestDays + 2)
        if (availableDates.size < 5) {
            Log.w(TAG, "历史数据不足，需要至少5个交易日，当前仅${availableDates.size}天")
            return@withContext TuneReport(
                dateRange = "数据不足",
                backtestDays = availableDates.size,
                strategyTuneDetails = emptyList(),
                summary = "需要更多历史数据：先在策略页面[导入]历史K线数据（至少5天）。"
            )
        }

        val engine = HistoricalBacktestEngine(context)
        val details = mutableListOf<StrategyTuneDetail>()

        for (strategy in strategies) {
            // Step 1: 原始回测
            Log.d(TAG, "  评估 ${strategy.id} - ${strategy.name} ...")
            val beforeReport = engine.runHistoricalBacktest(listOf(strategy), backtestDays)
            val beforeStats = beforeReport.strategyReports.firstOrNull()

            if (beforeStats == null || beforeStats.totalBuys < 3) {
                details.add(StrategyTuneDetail(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    beforeAccuracy = beforeStats?.buyAccuracy ?: 0f,
                    beforeAvgReturn = beforeStats?.avgReturn ?: 0.0,
                    afterAccuracy = beforeStats?.buyAccuracy ?: 0f,
                    afterAvgReturn = beforeStats?.avgReturn ?: 0.0,
                    wasTuned = false,
                    weightChanges = emptyList()
                ))
                continue
            }

            // Step 2: 是否需要调优
            if (beforeStats.buyAccuracy >= targetAccuracy) {
                details.add(StrategyTuneDetail(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    beforeAccuracy = beforeStats.buyAccuracy,
                    beforeAvgReturn = beforeStats.avgReturn,
                    afterAccuracy = beforeStats.buyAccuracy,
                    afterAvgReturn = beforeStats.avgReturn,
                    wasTuned = false,
                    weightChanges = listOf("已达目标准确率，无需调优")
                ))
                continue
            }

            // Step 3: 微调权重
            val originalWeights = strategy.weightFactors.toList()
            val changes = mutableListOf<String>()
            val adjustedFactors = originalWeights.map { wf ->
                if (wf.weight < 20) {
                    // 低权重因子：尝试提升
                    val newWeight = (wf.weight + 5).coerceAtMost(25)
                    if (newWeight != wf.weight) {
                        changes.add("${wf.key}: ${wf.weight}→${newWeight}")
                    }
                    wf.copy(weight = newWeight)
                } else {
                    wf
                }
            }.toMutableList()

            // 如果调高了某些权重，按比例压缩回 100
            val totalWeight = adjustedFactors.sumOf { it.weight }
            if (totalWeight != 100) {
                val scale = 100.0 / totalWeight
                adjustedFactors.replaceAll { it.copy(weight = (it.weight * scale).toInt().coerceIn(1, 95)) }
            }

            strategy.weightFactors = adjustedFactors

            // Step 4: 再回测
            val afterReport = engine.runHistoricalBacktest(listOf(strategy), backtestDays)
            val afterStats = afterReport.strategyReports.firstOrNull()

            if (afterStats == null) {
                // 还原
                strategy.weightFactors = originalWeights
                details.add(StrategyTuneDetail(
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    beforeAccuracy = beforeStats.buyAccuracy,
                    beforeAvgReturn = beforeStats.avgReturn,
                    afterAccuracy = beforeStats.buyAccuracy,
                    afterAvgReturn = beforeStats.avgReturn,
                    wasTuned = false,
                    weightChanges = listOf("调优后回测失败，已还原")
                ))
                continue
            }

            // Step 5: 对比效果
            val improved = afterStats.buyAccuracy > beforeStats.buyAccuracy
            if (improved) {
                changes.add("✅ 准确率: ${"%.1f".format(beforeStats.buyAccuracy * 100)}% → ${"%.1f".format(afterStats.buyAccuracy * 100)}%")
            } else {
                // 没改善 → 还原
                strategy.weightFactors = originalWeights
                changes.add("⚠️ 无改善，已还原原权重")
            }

            details.add(StrategyTuneDetail(
                strategyId = strategy.id,
                strategyName = strategy.name,
                beforeAccuracy = beforeStats.buyAccuracy,
                beforeAvgReturn = beforeStats.avgReturn,
                afterAccuracy = if (improved) afterStats.buyAccuracy else beforeStats.buyAccuracy,
                afterAvgReturn = if (improved) afterStats.avgReturn else beforeStats.avgReturn,
                wasTuned = improved,
                weightChanges = changes
            ))
        }

        val dateRange = "${availableDates.last()} ~ ${availableDates.first()}"
        val tunedCount = details.count { it.wasTuned }
        val improvedCount = details.count { it.afterAccuracy > it.beforeAccuracy }

        val summary = buildString {
            appendLine("━━━ 📊 策略自测调优报告 ━━━")
            appendLine("回测区间: $dateRange (${backtestDays}天)")
            appendLine("共 ${details.size} 个策略，已调优 $tunedCount 个，$improvedCount 个改善")
            appendLine()
            for (d in details) {
                val icon = when {
                    d.afterAccuracy > d.beforeAccuracy + 0.02f -> "📈"
                    d.afterAccuracy > d.beforeAccuracy -> "✅"
                    else -> "➖"
                }
                appendLine("$icon ${d.strategyName}")
                appendLine("   准确率: ${"%.1f".format(d.beforeAccuracy * 100)}% → ${"%.1f".format(d.afterAccuracy * 100)}%")
                if (d.wasTuned) appendLine("   调优: ${d.weightChanges.joinToString("; ")}")
            }
        }

        Log.i(TAG, summary)
        TuneReport(dateRange, backtestDays, details, summary)
    }

    /**
     * 快速评估：对单个策略计算最近 N 天的准确率
     */
    suspend fun quickEvaluate(strategy: Strategy, days: Int = 10): String {
        val engine = HistoricalBacktestEngine(context)
        val report = engine.runHistoricalBacktest(listOf(strategy), days)
        val stats = report.strategyReports.firstOrNull()
        return if (stats != null) {
            "${strategy.name}: 买入准确率 ${"%.1f".format(stats.buyAccuracy * 100)}% (${stats.correctBuys}/${stats.totalBuys}), 平均收益 ${"%.2f".format(stats.avgReturn)}%"
        } else {
            "${strategy.name}: 数据不足以评估"
        }
    }
}