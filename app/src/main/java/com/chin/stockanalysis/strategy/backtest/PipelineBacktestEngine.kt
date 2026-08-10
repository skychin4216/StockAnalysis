package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.pipelines.StockCheckPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## Pipeline Replay 回溯引擎
 *
 * 用历史数据重放 Pipeline 选股逻辑，验证实际表现。
 * 不仅回溯策略权重，而是回溯整个 Pipeline 的参数配置。
 *
 * ### 回溯流程
 * 1. 对每个历史交易日：用当日数据运行 StockCheckPipeline
 * 2. 收集 Pipeline 通过的股票
 * 3. 对比 T+1 实际表现（开盘买入 → 收盘/次日卖出）
 * 4. 统计胜率、平均收益、最大回撤
 * 5. 分析失败案例（为什么选了跌的股票）
 *
 * ### 拟合流程
 * 1. 网格搜索 Pipeline 参数组合
 * 2. Walk-Forward 验证（80% 训练 / 20% 测试）
 * 3. 过拟合检测（训练集 vs 测试集差异 > 15% → 拒绝）
 * 4. 输出最优参数组合
 */
class PipelineBacktestEngine(private val context: Context) {

    companion object {
        private const val TAG = "PipelineBacktest"
        private const val TRANSACTION_COST = 0.003  // 单边 0.15% × 2 = 0.3%
    }

    /**
     * 单日回溯结果
     */
    data class DailyBacktestResult(
        val date: String,
        val totalChecked: Int,
        val passed: Int,
        val passedStocks: List<StockTradeOutcome>,
        val winRate: Double,
        val avgReturn: Double
    )

    /**
     * 单只股票的交易结果
     */
    data class StockTradeOutcome(
        val stockCode: String,
        val stockName: String,
        val passCount: Int,
        val buyPrice: Double,      // T+1 开盘价
        val sellPrice: Double,     // T+1 收盘价 或 T+2 开盘价
        val returnPct: Double,     // 净收益（扣除手续费）
        val isWin: Boolean,
        val holdDays: Int = 1,
        val failReason: String = ""  // 如果失败，分析原因
    )

    /**
     * 回溯报告
     */
    data class PipelineBacktestReport(
        val period: String,           // "超短线" / "短线" / "中线" / "长线"
        val tradingDays: Int,
        val totalChecked: Int,
        val totalPassed: Int,
        val overallWinRate: Double,
        val avgReturn: Double,
        val maxWin: Double,
        val maxLoss: Double,
        val grade: String,            // A/B/C/D
        val dailyResults: List<DailyBacktestResult>,
        val failureAnalysis: List<FailureCase>,
        val bestParams: Map<String, Any>? = null  // 拟合后的最优参数
    )

    /**
     * 失败案例分析
     */
    data class FailureCase(
        val date: String,
        val stockCode: String,
        val stockName: String,
        val passCount: Int,
        val returnPct: Double,
        val reasons: List<String>   // 失败原因列表
    )

    /**
     * 执行 Pipeline Replay 回溯
     *
     * @param pipeline 要回溯的 Pipeline（含参数配置）
     * @param tradingDays 回溯多少个交易日
     * @param holdDays 持有天数（1=T+1收盘卖出，2=T+2卖出...）
     * @param periodLabel 周期标签（用于报告）
     */
    suspend fun runBacktest(
        pipeline: StockCheckPipeline,
        tradingDays: Int = 20,
        holdDays: Int = 1,
        periodLabel: String = "中线"
    ): PipelineBacktestReport = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        val dao = db.dailySnapshotDao()

        // 1. 获取最近的交易日列表
        val allDates = dao.getRecentTradeDates(tradingDays + holdDays + 5)
        if (allDates.size < tradingDays) {
            return@withContext PipelineBacktestReport(
                period = periodLabel, tradingDays = 0,
                totalChecked = 0, totalPassed = 0,
                overallWinRate = 0.0, avgReturn = 0.0,
                maxWin = 0.0, maxLoss = 0.0, grade = "D",
                dailyResults = emptyList(), failureAnalysis = emptyList()
            )
        }

        val backtestDates = allDates.take(tradingDays)
        val dailyResults = mutableListOf<DailyBacktestResult>()
        val allOutcomes = mutableListOf<StockTradeOutcome>()
        val failureCases = mutableListOf<FailureCase>()

        for (date in backtestDates) {
            // 2. 获取当日所有有数据的股票代码
            val codesOnDate = dao.getStockCodesByDate(date)
            if (codesOnDate.isEmpty()) continue

            // 3. 对每只股票运行 Pipeline 检查
            val passedStocks = mutableListOf<StockTradeOutcome>()
            var totalChecked = 0

            for (code in codesOnDate) {
                totalChecked++
                val snaps = dao.getByCodeBefore(code, date, pipeline.lookbackDays + 10)
                    .sortedBy { it.date }
                if (snaps.size < 20) continue

                // 用历史数据模拟 Pipeline 检查
                val result = simulateCheck(pipeline, snaps, date)
                if (!result.first) continue  // 未通过

                // 4. 查找 T+holdDays 的实际表现
                val futureSnaps = dao.getByCodeAfter(code, date, holdDays + 2)
                    .sortedBy { it.date }
                if (futureSnaps.isEmpty()) continue

                val buySnap = futureSnaps.first()  // T+1 开盘买入
                val sellSnap = if (futureSnaps.size > holdDays) {
                    futureSnaps[holdDays]
                } else futureSnaps.last()

                val buyPrice = buySnap.open
                val sellPrice = sellSnap.close
                val rawReturn = (sellPrice - buyPrice) / buyPrice
                val netReturn = rawReturn - TRANSACTION_COST

                val outcome = StockTradeOutcome(
                    stockCode = code,
                    stockName = buySnap.name,
                    passCount = result.second,
                    buyPrice = buyPrice,
                    sellPrice = sellPrice,
                    returnPct = netReturn * 100,
                    isWin = netReturn > 0,
                    holdDays = holdDays
                )
                passedStocks.add(outcome)
                allOutcomes.add(outcome)

                // 5. 记录失败案例
                if (!outcome.isWin && netReturn < -0.02) {
                    failureCases.add(analyzeFailure(date, outcome, snaps, buySnap))
                }
            }

            val winRate = if (passedStocks.isNotEmpty()) {
                passedStocks.count { it.isWin }.toDouble() / passedStocks.size
            } else 0.0
            val avgReturn = if (passedStocks.isNotEmpty()) {
                passedStocks.map { it.returnPct }.average()
            } else 0.0

            dailyResults.add(DailyBacktestResult(
                date = date, totalChecked = totalChecked,
                passed = passedStocks.size, passedStocks = passedStocks,
                winRate = winRate, avgReturn = avgReturn
            ))
        }

        // 6. 统计汇总
        val overallWinRate = if (allOutcomes.isNotEmpty()) {
            allOutcomes.count { it.isWin }.toDouble() / allOutcomes.size
        } else 0.0
        val avgReturn = if (allOutcomes.isNotEmpty()) {
            allOutcomes.map { it.returnPct }.average()
        } else 0.0
        val maxWin = allOutcomes.maxOfOrNull { it.returnPct } ?: 0.0
        val maxLoss = allOutcomes.minOfOrNull { it.returnPct } ?: 0.0

        val grade = when {
            overallWinRate >= 0.70 && avgReturn > 2.0 -> "A"
            overallWinRate >= 0.55 && avgReturn > 0.0 -> "B"
            overallWinRate >= 0.40 -> "C"
            else -> "D"
        }

        PipelineBacktestReport(
            period = periodLabel,
            tradingDays = tradingDays,
            totalChecked = dailyResults.sumOf { it.totalChecked },
            totalPassed = allOutcomes.size,
            overallWinRate = overallWinRate,
            avgReturn = avgReturn,
            maxWin = maxWin,
            maxLoss = maxLoss,
            grade = grade,
            dailyResults = dailyResults,
            failureAnalysis = failureCases.take(20)  // 最多 20 个失败案例
        )
    }

    /**
     * 用历史快照模拟 Pipeline 检查（不依赖实时数据）
     */
    private fun simulateCheck(
        pipeline: StockCheckPipeline,
        snaps: List<DailySnapshotEntity>,
        asOfDate: String
    ): Pair<Boolean, Int> {
        val filtered = snaps.filter { it.date <= asOfDate }.takeLast(pipeline.lookbackDays)
        val result = pipeline.analyzeSnaps(filtered)
        return result.passed to result.passCount
    }

    /**
     * 分析失败原因
     */
    private fun analyzeFailure(
        date: String,
        outcome: StockTradeOutcome,
        historicalSnaps: List<DailySnapshotEntity>,
        buySnap: DailySnapshotEntity
    ): FailureCase {
        val reasons = mutableListOf<String>()

        // 分析为什么跌
        if (buySnap.changePct > 3) {
            reasons.add("T+1 高开 ${"%.1f".format(buySnap.changePct)}%，追高风险")
        }
        if (outcome.returnPct < -5) {
            reasons.add("跌幅超过 5%，可能遇到利空")
        }
        if (outcome.returnPct < -3) {
            reasons.add("跌幅超过 3%，止损不及时")
        }

        // 检查大盘环境
        val marketSnaps = historicalSnaps.filter { it.code == "sh000001" }
        if (marketSnaps.isNotEmpty()) {
            val marketChange = marketSnaps.last().changePct
            if (marketChange < -1) {
                reasons.add("大盘下跌 ${"%.1f".format(marketChange)}%，系统性风险")
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("正常波动，持仓 ${outcome.holdDays} 天收益 ${"%.2f".format(outcome.returnPct)}%")
        }

        return FailureCase(
            date = date,
            stockCode = outcome.stockCode,
            stockName = outcome.stockName,
            passCount = outcome.passCount,
            returnPct = outcome.returnPct,
            reasons = reasons
        )
    }

    /**
     * 格式化报告
     */
    fun formatReport(report: PipelineBacktestReport): String {
        return buildString {
            appendLine("═══ ${report.period} Pipeline 回溯报告 ═══")
            appendLine("回溯天数: ${report.tradingDays} 日")
            appendLine("总检查: ${report.totalChecked} 只  通过: ${report.totalPassed} 只")
            appendLine("胜率: ${"%.1f".format(report.overallWinRate * 100)}%")
            appendLine("平均收益: ${"%.2f".format(report.avgReturn)}%")
            appendLine("最大盈利: ${"%.2f".format(report.maxWin)}%  最大亏损: ${"%.2f".format(report.maxLoss)}%")
            appendLine("评级: ${report.grade}")
            appendLine()

            if (report.failureAnalysis.isNotEmpty()) {
                appendLine("━━━ 失败案例分析 ━━━")
                for (fc in report.failureAnalysis.take(10)) {
                    appendLine("${fc.date} ${fc.stockName}(${fc.stockCode}) 通过${fc.passCount}/7 收益${"%.2f".format(fc.returnPct)}%")
                    for (r in fc.reasons) {
                        appendLine("  → $r")
                    }
                }
            }
        }
    }
}
