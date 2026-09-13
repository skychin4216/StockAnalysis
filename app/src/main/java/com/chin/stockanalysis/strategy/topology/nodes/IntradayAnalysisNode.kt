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
 * ## 盘中 K 线分析节点
 *
 * 在交易时段（9:30-11:30, 13:00-15:00）自动获取候选股的 5 分钟 K 线，
 * 计算 VWAP、盘中均线、量能变化等指标，存入 context 供下游节点使用。
 *
 * 非交易时段自动跳过（不影响 Pipeline 流程）。
 *
 * ### 输出
 * - `context.setStageOutput(nodeId, IntradayAnalysisResult)`
 * - 盘中指标存入 `context["intraday_indicators"]`
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
) : BaseNode<Any, IntradayAnalysisResult>("intraday_analysis", "盘中K线分析", NodeType.DATA_SOURCE) {

    companion object {
        private const val TAG = "IntradayAnalysis"
    }

    override suspend fun execute(context: PipelineContext, input: Any): IntradayAnalysisResult {
        // 判断是否在交易时段
        val now = LocalTime.now()
        val morningSession = now >= LocalTime.of(9, 30) && now <= LocalTime.of(11, 30)
        val afternoonSession = now >= LocalTime.of(13, 0) && now <= LocalTime.of(15, 0)
        val isTradingHours = isTradingDay() && (morningSession || afternoonSession)

        if (!isTradingHours) {
            context.log(nodeId, "⏸️ 非交易时段(${now.hour}:${"%02d".format(now.minute)})，跳过盘中K线分析")
            return IntradayAnalysisResult(skipped = true, skipReason = "非交易时段")
        }

        // 从上游输入提取候选股代码
        val candidateCodes = extractCandidateCodes(input)
        if (candidateCodes.isEmpty()) {
            context.log(nodeId, "无候选股，跳过盘中分析")
            return IntradayAnalysisResult(skipped = true, skipReason = "无候选股")
        }

        context.log(nodeId, "📊 开始盘中K线分析: ${candidateCodes.size} 只候选股, ${intervalMin}分钟线")

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val today = LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val fetcher = IntradayKlineFetcher(context.androidContext)

            // 先检查 DB 中是否已有今日数据
            val existingCount = db.intradayKlineDao().countToday(today)
            val barsMap: Map<String, List<IntradayKlineEntity>>

            if (existingCount > candidateCodes.size * 3) {
                // DB 已有足够数据，直接读取
                context.log(nodeId, "从 DB 读取今日盘中数据 ($existingCount 条)")
                barsMap = candidateCodes.associateWith { code ->
                    try { db.intradayKlineDao().getByCodeToday(code, today) }
                    catch (_: Exception) { emptyList() }
                }
            } else {
                // 从 API 获取
                context.log(nodeId, "从 EastMoney 获取盘中K线...")
                val fetched = fetcher.fetchBatchIntraday(candidateCodes, intervalMin)
                barsMap = fetched

                // 存入 DB
                val allBars = fetched.values.flatten()
                if (allBars.isNotEmpty()) {
                    try {
                        db.intradayKlineDao().insertAll(allBars)
                        context.log(nodeId, "已保存 ${allBars.size} 条盘中K线到 DB")
                    } catch (e: Exception) {
                        Log.w(TAG, "保存盘中K线失败: ${e.message}")
                    }
                }

                // 清理 3 天前的旧数据
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

            // 存入 context 供下游读取
            context.setStageOutput(nodeId, IntradayAnalysisResult(
                indicators = indicators,
                fetchedCount = barsMap.values.sumOf { it.size },
                analyzedCount = indicators.size
            ))
            context.setStageOutput("intraday_indicators", indicators)

            context.log(nodeId, "✅ 盘中分析完成: ${barsMap.size} 只获取 → ${indicators.size} 只分析成功")
            if (indicators.isNotEmpty()) {
                val sample = indicators.values.take(3)
                context.log(nodeId, "  样本: ${sample.map { "${it.code} ${it.trendDirection} ${"%.1f".format(it.intradayReturn)}%" }.joinToString(", ")}")
            }

            IntradayAnalysisResult(
                indicators = indicators,
                fetchedCount = barsMap.values.sumOf { it.size },
                analyzedCount = indicators.size
            )
        } catch (e: Exception) {
            context.log(nodeId, "❌ 盘中分析异常: ${e.message}")
            IntradayAnalysisResult(skipped = true, skipReason = "异常: ${e.message}")
        }
    }

    /** 从各种上游输入中提取候选股代码 */
    private fun extractCandidateCodes(input: Any): List<String> {
        return when (input) {
            is MergedSignalPool -> input.stockHits.keys.toList()
            is Set<*> -> input.filterIsInstance<String>()
            is List<*> -> input.filterIsInstance<String>()
            else -> {
                // 尝试从 context 中读取 stock_pool 结果
                emptyList()
            }
        }
    }

    private fun isTradingDay(): Boolean {
        val dow = LocalDate.now().dayOfWeek
        return dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY
    }
}
