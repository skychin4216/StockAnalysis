package com.chin.stockanalysis.strategy.backtest

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.StockCheckPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## Pipeline Replay 回溯引擎
 *
 * 用歷史數據重放 Pipeline 選股邏輯，驗證實際表現。
 * 不僅回溯策略權重，而是回溯整個 Pipeline 的參數配置。
 *
 * ### 回溯流程
 * 1. 對每個歷史交易日：用當日數據運行 StockCheckPipeline
 * 2. 收集 Pipeline 通過的股票
 * 3. 對比 T+1 實際表現（開盤買入 → 收盤/次日賣出）
 * 4. 統計勝率、平均收益、最大回撤
 * 5. 分析失敗案例（為什麼選了跌的股票）
 *
 * ### 擬合流程
 * 1. 網格搜索 Pipeline 參數組合
 * 2. Walk-Forward 驗證（80% 訓練 / 20% 測試）
 * 3. 過擬合檢測（訓練集 vs 測試集差異 > 15% → 拒絕）
 * 4. 輸出最優參數組合
 */
class PipelineBacktestEngine(private val context: Context) {

    companion object {
        private const val TAG = "PipelineBacktest"
        private const val TRANSACTION_COST = 0.003  // 單邊 0.15% × 2 = 0.3%
    }

    /**
     * 單日回溯結果
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
     * 單只股票的交易結果
     */
    data class StockTradeOutcome(
        val stockCode: String,
        val stockName: String,
        val passCount: Int,
        val buyPrice: Double,      // T+1 開盤價
        val sellPrice: Double,     // T+1 收盤價 或 T+2 開盤價
        val returnPct: Double,     // 淨收益（扣除手續費）
        val isWin: Boolean,
        val holdDays: Int = 1,
        val failReason: String = ""  // 如果失敗，分析原因
    )

    /**
     * 回溯報告
     */
    data class PipelineBacktestReport(
        val period: String,           // "超短線" / "短線" / "中線" / "長線"
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
        val bestParams: Map<String, Any>? = null  // 擬合後的最優參數
    )

    /**
     * 失敗案例分析
     */
    data class FailureCase(
        val date: String,
        val stockCode: String,
        val stockName: String,
        val passCount: Int,
        val returnPct: Double,
        val reasons: List<String>   // 失敗原因列表
    )

    /**
     * 執行 Pipeline Replay 回溯
     *
     * @param pipeline 要回溯的 Pipeline（含參數配置）
     * @param tradingDays 回溯多少個交易日
     * @param holdDays 持有天數（1=T+1收盤賣出，2=T+2賣出...）
     * @param periodLabel 周期標籤（用於報告）
     */
    suspend fun runBacktest(
        pipeline: StockCheckPipeline,
        tradingDays: Int = 20,
        holdDays: Int = 1,
        periodLabel: String = "中線"
    ): PipelineBacktestReport = withContext(Dispatchers.IO) {
        val db = StockDatabase.getInstance(context)
        val dao = db.dailySnapshotDao()

        // 1. 獲取最近的交易日列表
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
            // 2. 獲取當日所有有數據的股票代碼
            val codesOnDate = dao.getStockCodesByDate(date)
            if (codesOnDate.isEmpty()) continue

            // 3. 對每只股票運行 Pipeline 檢查
            val passedStocks = mutableListOf<StockTradeOutcome>()
            var totalChecked = 0

            for (code in codesOnDate) {
                totalChecked++
                val snaps = dao.getByCodeBefore(code, date, pipeline.lookbackDays + 10)
                    .sortedBy { it.date }
                if (snaps.size < 20) continue

                // 用歷史數據模擬 Pipeline 檢查
                val result = simulateCheck(pipeline, snaps, date)
                if (!result.first) continue  // 未通過

                // 4. 查找 T+holdDays 的實際表現
                val futureSnaps = dao.getByCodeAfter(code, date, holdDays + 2)
                    .sortedBy { it.date }
                if (futureSnaps.isEmpty()) continue

                val buySnap = futureSnaps.first()  // T+1 開盤買入
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

                // 5. 記錄失敗案例
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

        // 6. 統計匯總
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
            failureAnalysis = failureCases.take(20)  // 最多 20 個失敗案例
        )
    }

    /**
     * 用歷史快照模擬 Pipeline 檢查（不依賴實時數據）
     */
    private fun simulateCheck(
        pipeline: StockCheckPipeline,
        snaps: List<DailySnapshotEntity>,
        asOfDate: String
    ): Pair<Boolean, Int> {
        val filtered = snaps.filter { it.date <= asOfDate }.takeLast(pipeline.lookbackDays)
        if (filtered.size < 20) return false to 0

        val latest = filtered.last()
        val closes = filtered.map { it.close }

        var passCount = 0

        // 1. 均線粘合向上
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val ma20 = closes.takeLast(20).average()
        val divergence = if (ma20 > 0) (ma5 - ma20) / ma20 else 1.0
        if (ma5 > ma10 && ma10 > ma20 && kotlin.math.abs(divergence) < pipeline.maDivergenceThreshold) passCount++

        // 2. 三日不新低
        val recent3 = filtered.takeLast(3)
        if (recent3.size >= 3) {
            val lows = recent3.map { it.low }
            if (lows[0] <= lows[1] && lows[1] <= lows[2]) passCount++
        }

        // 3. 歷史低位
        val highN = closes.maxOrNull() ?: latest.close
        val lowN = closes.minOrNull() ?: latest.close
        val range = highN - lowN
        val position = if (range > 0) (latest.close - lowN) / range else 0.5
        if (position <= pipeline.historicalLowPercentile) passCount++

        // 4. PE
        if (latest.pe > 0 && latest.pe < pipeline.peThreshold) passCount++

        // 5. 活躍度
        val activeDays = filtered.count { kotlin.math.abs(it.changePct) > pipeline.activeChangeThreshold }
        if (activeDays >= pipeline.activeDaysThreshold) passCount++

        // 6. 冰點
        val turnoverOk = latest.turnoverRate < pipeline.turnoverThreshold && latest.turnoverRate > 0
        val avgVol5 = if (filtered.size >= 6) {
            filtered.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
        } else latest.volume.toDouble()
        val volRatio = if (avgVol5 > 0) latest.volume / avgVol5 else 1.0
        if (turnoverOk && volRatio < pipeline.volumeRatioThreshold) passCount++

        return (passCount >= pipeline.minPassCount) to passCount
    }

    /**
     * 分析失敗原因
     */
    private fun analyzeFailure(
        date: String,
        outcome: StockTradeOutcome,
        historicalSnaps: List<DailySnapshotEntity>,
        buySnap: DailySnapshotEntity
    ): FailureCase {
        val reasons = mutableListOf<String>()

        // 分析為什麼跌
        if (buySnap.changePct > 3) {
            reasons.add("T+1 高開 ${"%.1f".format(buySnap.changePct)}%，追高風險")
        }
        if (outcome.returnPct < -5) {
            reasons.add("跌幅超過 5%，可能遇到利空")
        }
        if (outcome.returnPct < -3) {
            reasons.add("跌幅超過 3%，止損不及時")
        }

        // 檢查大盤環境
        val marketSnaps = historicalSnaps.filter { it.code == "sh000001" }
        if (marketSnaps.isNotEmpty()) {
            val marketChange = marketSnaps.last().changePct
            if (marketChange < -1) {
                reasons.add("大盤下跌 ${"%.1f".format(marketChange)}%，系統性風險")
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("正常波動，持倉 ${outcome.holdDays} 天收益 ${"%.2f".format(outcome.returnPct)}%")
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
     * 格式化報告
     */
    fun formatReport(report: PipelineBacktestReport): String {
        return buildString {
            appendLine("═══ ${report.period} Pipeline 回溯報告 ═══")
            appendLine("回溯天數: ${report.tradingDays} 日")
            appendLine("總檢查: ${report.totalChecked} 只  通過: ${report.totalPassed} 只")
            appendLine("勝率: ${"%.1f".format(report.overallWinRate * 100)}%")
            appendLine("平均收益: ${"%.2f".format(report.avgReturn)}%")
            appendLine("最大盈利: ${"%.2f".format(report.maxWin)}%  最大虧損: ${"%.2f".format(report.maxLoss)}%")
            appendLine("評級: ${report.grade}")
            appendLine()

            if (report.failureAnalysis.isNotEmpty()) {
                appendLine("━━━ 失敗案例分析 ━━━")
                for (fc in report.failureAnalysis.take(10)) {
                    appendLine("${fc.date} ${fc.stockName}(${fc.stockCode}) 通過${fc.passCount}/7 收益${"%.2f".format(fc.returnPct)}%")
                    for (r in fc.reasons) {
                        appendLine("  → $r")
                    }
                }
            }
        }
    }
}
