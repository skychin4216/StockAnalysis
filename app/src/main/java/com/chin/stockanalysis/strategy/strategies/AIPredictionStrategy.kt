package com.chin.stockanalysis.strategy.strategies

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
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
 * ### 两种执行模式
 * 1. **注入模式**：外部（如 `doExecute()`）先设置 `strategyResults` 和 `targetDate`，
 *    再调用 `screen()`，AI 直接使用注入的结果
 * 2. **独立模式**：`strategyResults` 为空时，自动从 DB (`strategy_prediction` 表)
 *    获取最近的策略回测结果作为 AI 输入
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
            "mode" to "auto"
        ),
        maxResults = 5
    )

    override var weightFactors: List<WeightFactor> = listOf(
        WeightFactor("multi_strategy", "多策略综合", 40, "多维度策略交叉验证"),
        WeightFactor("historical_pattern", "历史模式识别", 30, "近5日价格形态分析"),
        WeightFactor("news_sentiment", "新闻情绪", 20, "近3日舆情+利好利空"),
        WeightFactor("market_timing", "市场时机", 10, "当前市场环境适配")
    )

    /** 由外部注入：其他策略已执行完的结果。注入模式下设置，独立模式下留空。 */
    var strategyResults: List<ScreeningResult> = emptyList()
    var targetDate: String = ""

    override suspend fun screen(): Result<ScreeningResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            // 注入模式下直接使用，独立模式下从DB获取历史策略结果
            val effectiveResults = if (strategyResults.isNotEmpty()) {
                strategyResults
            } else {
                loadRecentStrategyResults()
            }
            val date = targetDate.ifEmpty { java.time.LocalDate.now().toString() }

            if (effectiveResults.isEmpty()) {
                return@withContext Result.success(ScreeningResult(
                    strategyId = id, strategyName = name, category = category,
                    signals = emptyList(), totalScanned = 0,
                    scanTimeMs = System.currentTimeMillis() - startTime
                ))
            }

            val ai = AIPredictionEngine(context)
            val prediction = ai.predict(effectiveResults, date)

            if (prediction == null || prediction.topPicks.isEmpty()) {
                return@withContext Result.success(ScreeningResult(
                    strategyId = id, strategyName = name, category = category,
                    signals = emptyList(), totalScanned = effectiveResults.sumOf { it.totalScanned },
                    scanTimeMs = System.currentTimeMillis() - startTime
                ))
            }

            val signals = prediction.topPicks.map { pick ->
                StrategySignal(
                    stockCode = pick.stockCode,
                    stockName = pick.stockName,
                    strategyId = id,
                    category = category,
                    strength = pick.compositeScore,
                    action = when { pick.rank <= 3 -> SignalAction.BUY; else -> SignalAction.WATCH },
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
                signals = signals, totalScanned = effectiveResults.sumOf { it.totalScanned },
                scanTimeMs = System.currentTimeMillis() - startTime
            ))
        } catch (e: Exception) {
            Log.e(id, "AI预测失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun isAvailable(): Boolean = true

    /**
     * 独立模式：从 DB 获取最近策略回测结果
     * 取 strategy_prediction 表中最近2个交易日的数据
     */
    private suspend fun loadRecentStrategyResults(): List<ScreeningResult> {
        return try {
            val db = StockDatabase.getInstance(context)
            val availableDates = db.dailySnapshotDao().getAvailableDates(2)
            if (availableDates.isEmpty()) return emptyList()

            val allSignals = mutableListOf<StrategySignal>()
            for (date in availableDates) {
                val predictions = db.strategyPredictionDao().getByDate(date)
                for (pred in predictions) {
                    allSignals.add(StrategySignal(
                        stockCode = pred.stockCode,
                        stockName = pred.stockName,
                        strategyId = pred.strategyId,
                        category = StrategyCategory.CUSTOM,
                        strength = pred.predictedScore,
                        action = try { SignalAction.valueOf(pred.predictedAction) } catch (_: Exception) { SignalAction.WATCH },
                        reason = "${pred.strategyName}: 预测强度${pred.predictedScore}",
                        details = mapOf(
                            "strategy_name" to pred.strategyName,
                            "predicted_score" to "${pred.predictedScore}",
                            "actual_next" to "${pred.actualNextDayPct ?: "N/A"}"
                        ),
                        currentPrice = 0.0,
                        changePercent = 0.0
                    ))
                }
            }

            if (allSignals.isEmpty()) return emptyList()

            listOf(ScreeningResult(
                strategyId = "historical",
                strategyName = "历史策略数据",
                category = StrategyCategory.CUSTOM,
                signals = allSignals.distinctBy { it.stockCode }.take(50),
                totalScanned = allSignals.size,
                scanTimeMs = 0
            ))
        } catch (e: Exception) {
            Log.w(id, "加载历史策略结果失败: ${e.message}")
            emptyList()
        }
    }
}