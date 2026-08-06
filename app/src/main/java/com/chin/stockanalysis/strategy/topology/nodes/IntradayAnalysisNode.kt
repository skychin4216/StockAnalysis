package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.backtest.IntradayKlineEntity
import com.chin.stockanalysis.strategy.data.IntradayAnalyzer
import com.chin.stockanalysis.strategy.data.IntradayKlineFetcher
import com.chin.stockanalysis.strategy.topology.core.*
import java.time.LocalDate
import java.time.LocalTime

/**
 * ## 盤中 K 線分析節點
 *
 * 在交易時段（9:30-11:30, 13:00-15:00）自動獲取候選股的 5 分鐘 K 線，
 * 計算 VWAP、盤中均線、量能變化等指標，存入 context 供下游節點使用。
 *
 * 非交易時段自動跳過（不影響 Pipeline 流程）。
 *
 * ### 輸出
 * - `context.setStageOutput(nodeId, IntradayAnalysisResult)`
 * - 盤中指標存入 `context["intraday_indicators"]`
 */
data class IntradayAnalysisResult(
    val indicators: Map<String, IntradayAnalyzer.IntradayIndicators> = emptyMap(),
    val fetchedCount: Int = 0,
    val analyzedCount: Int = 0,
    val skipped: Boolean = false,
    val skipReason: String = ""
)

class IntradayAnalysisNode(
    private val intervalMin: Int = 5,
    private val minBars: Int = 5
) : BaseNode<Any, IntradayAnalysisResult>("intraday_analysis", "盤中K線分析", NodeType.DATA_SOURCE) {

    companion object {
        private const val TAG = "IntradayAnalysis"
    }

    override suspend fun execute(context: PipelineContext, input: Any): IntradayAnalysisResult {
        // 判斷是否在交易時段
        val now = LocalTime.now()
        val morningSession = now >= LocalTime.of(9, 30) && now <= LocalTime.of(11, 30)
        val afternoonSession = now >= LocalTime.of(13, 0) && now <= LocalTime.of(15, 0)
        val isTradingHours = isTradingDay() && (morningSession || afternoonSession)

        if (!isTradingHours) {
            context.log(nodeId, "⏸️ 非交易時段(${now.hour}:${"%02d".format(now.minute)})，跳過盤中K線分析")
            return IntradayAnalysisResult(skipped = true, skipReason = "非交易時段")
        }

        // 從上游輸入提取候選股代碼
        val candidateCodes = extractCandidateCodes(input)
        if (candidateCodes.isEmpty()) {
            context.log(nodeId, "無候選股，跳過盤中分析")
            return IntradayAnalysisResult(skipped = true, skipReason = "無候選股")
        }

        context.log(nodeId, "📊 開始盤中K線分析: ${candidateCodes.size} 只候選股, ${intervalMin}分鐘線")

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val today = LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val fetcher = IntradayKlineFetcher(context.androidContext)

            // 先檢查 DB 中是否已有今日數據
            val existingCount = db.intradayKlineDao().countToday(today)
            val barsMap: Map<String, List<IntradayKlineEntity>>

            if (existingCount > candidateCodes.size * 3) {
                // DB 已有足夠數據，直接讀取
                context.log(nodeId, "從 DB 讀取今日盤中數據 ($existingCount 條)")
                barsMap = candidateCodes.associateWith { code ->
                    try { db.intradayKlineDao().getByCodeToday(code, today) }
                    catch (_: Exception) { emptyList() }
                }
            } else {
                // 從 API 獲取
                context.log(nodeId, "從 EastMoney 獲取盤中K線...")
                val fetched = fetcher.fetchBatchIntraday(candidateCodes, intervalMin)
                barsMap = fetched

                // 存入 DB
                val allBars = fetched.values.flatten()
                if (allBars.isNotEmpty()) {
                    try {
                        db.intradayKlineDao().insertAll(allBars)
                        context.log(nodeId, "已保存 ${allBars.size} 條盤中K線到 DB")
                    } catch (e: Exception) {
                        Log.w(TAG, "保存盤中K線失敗: ${e.message}")
                    }
                }

                // 清理 3 天前的舊數據
                try {
                    val threeDaysAgo = LocalDate.now().minusDays(3).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                    db.intradayKlineDao().deleteOlderThan(threeDaysAgo)
                } catch (_: Exception) {}
            }

            // 分析
            val indicators = mutableMapOf<String, IntradayAnalyzer.IntradayIndicators>()
            for ((code, bars) in barsMap) {
                if (bars.size < minBars) continue
                val ind = IntradayAnalyzer.analyze(bars)
                if (ind != null) {
                    indicators[code] = ind
                }
            }

            // 存入 context 供下游讀取
            context.setStageOutput(nodeId, IntradayAnalysisResult(
                indicators = indicators,
                fetchedCount = barsMap.values.sumOf { it.size },
                analyzedCount = indicators.size
            ))
            context.setStageOutput("intraday_indicators", indicators)

            context.log(nodeId, "✅ 盤中分析完成: ${barsMap.size} 只獲取 → ${indicators.size} 只分析成功")
            if (indicators.isNotEmpty()) {
                val sample = indicators.values.take(3)
                context.log(nodeId, "  樣本: ${sample.map { "${it.code} ${it.trendDirection} ${"%.1f".format(it.intradayReturn)}%" }.joinToString(", ")}")
            }

            IntradayAnalysisResult(
                indicators = indicators,
                fetchedCount = barsMap.values.sumOf { it.size },
                analyzedCount = indicators.size
            )
        } catch (e: Exception) {
            context.log(nodeId, "❌ 盤中分析異常: ${e.message}")
            IntradayAnalysisResult(skipped = true, skipReason = "異常: ${e.message}")
        }
    }

    /** 從各種上游輸入中提取候選股代碼 */
    private fun extractCandidateCodes(input: Any): List<String> {
        return when (input) {
            is MergedSignalPool -> input.stockHits.keys.toList()
            is Set<*> -> input.filterIsInstance<String>()
            is List<*> -> input.filterIsInstance<String>()
            else -> {
                // 嘗試從 context 中讀取 stock_pool 結果
                emptyList()
            }
        }
    }

    private fun isTradingDay(): Boolean {
        val dow = LocalDate.now().dayOfWeek
        return dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY
    }
}
