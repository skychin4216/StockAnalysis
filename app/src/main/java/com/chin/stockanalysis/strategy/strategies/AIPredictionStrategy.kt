package com.chin.stockanalysis.strategy.strategies

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.strategy.*
import com.chin.stockanalysis.strategy.models.ScreeningResult
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.models.WeightFactor
import com.chin.stockanalysis.strategy.models.SignalAction
import com.chin.stockanalysis.strategy.predict.AIPredictionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## AI 量化选股策略
 *
 * 基于多策略打分结果 + 历史数据特征 + 新闻因子，
 * 通过 AI（LLM）综合推荐 3-5 只最可能上涨的股票。
 *
 * 此策略依赖其他策略先执行产生 [ScreeningResult]，作为 AI 推理的输入。
 * 在引擎中应注册为最后一个策略。
 */
class AIPredictionStrategy(private val context: Context) : Strategy {

    override val id = "ai_prediction"
    override var name = "AI量化选股"
    override var description = "基于多策略打分+历史数据+新闻因子，AI综合推荐3-5只最可能上涨的股票"
    override val category = StrategyCategory.CUSTOM
    override val source = StrategySource.USER_CUSTOM

    override val config = StrategyConfig.custom(
        params = mapOf(
            "max_picks" to 5,
            "mode" to "auto"  // auto: AI 自动选择方案A/B
        ),
        maxResults = 5
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("multi_strategy", "多策略综合", 40, "多维度策略交叉验证"),
        WeightFactor("historical_pattern", "历史模式识别", 30, "近5日价格形态分析"),
        WeightFactor("news_sentiment", "新闻情绪", 20, "近3日舆情+利好利空"),
        WeightFactor("market_timing", "市场时机", 10, "当前市场环境适配")
    )

    /**
     * 由外部注入：其他策略已执行完的结果。
     * 在引擎执行前设置。
     */
    var strategyResults: List<ScreeningResult> = emptyList()
    var targetDate: String = ""

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            if (strategyResults.isEmpty()) {
                return@withContext Result.success(ScreeningResult(
                    strategyId = id, strategyName = name, category = category,
                    signals = emptyList(), totalScanned = 0,
                    scanTimeMs = System.currentTimeMillis() - startTime
                ))
            }

            val ai = AIPredictionEngine(context)
            val date = targetDate.ifEmpty { java.time.LocalDate.now().toString() }
            val prediction = ai.predict(strategyResults, date)

            if (prediction == null || prediction.topPicks.isEmpty()) {
                return@withContext Result.success(ScreeningResult(
                    strategyId = id, strategyName = name, category = category,
                    signals = emptyList(), totalScanned = strategyResults.sumOf { it.totalScanned },
                    scanTimeMs = System.currentTimeMillis() - startTime
                ))
            }

            // 将 AI 推荐结果转为 StrategySignal 列表
            val signals = prediction.topPicks.map { pick ->
                StrategySignal(
                    stockCode = pick.stockCode,
                    stockName = pick.stockName,
                    strategyId = id,
                    category = category,
                    strength = pick.compositeScore,
                    action = when {
                        pick.rank <= 3 -> SignalAction.BUY
                        else -> SignalAction.WATCH
                    },
                    reason = "AI推荐(${prediction.mode}方案): ${pick.reason.take(60)}",
                    details = mapOf(
                        "ai_rank" to "${pick.rank}",
                        "composite_score" to "${pick.compositeScore}",
                        "up_probability" to "${pick.upProbability}%",
                        "action" to pick.actionSuggestion,
                        "mode" to prediction.mode,
                        "market_outlook" to prediction.marketOutlook,
                        "risk_warning" to prediction.riskWarning
                    ),
                    currentPrice = 0.0,
                    changePercent = 0.0
                )
            }

            Result.success(ScreeningResult(
                strategyId = id, strategyName = name, category = category,
                signals = signals, totalScanned = strategyResults.sumOf { it.totalScanned },
                scanTimeMs = System.currentTimeMillis() - startTime
            ))
        } catch (e: Exception) {
            Log.e(id, "AI预测失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = true
}