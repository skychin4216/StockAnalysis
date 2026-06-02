package com.chin.stockanalysis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.chin.stockanalysis.stock.database.StockDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ## 多交易日统计面板
 *
 * 从 strategy_prediction 和 daily_snapshot 表提取数据，
 * 展示：
 * - 📈 各策略 N 日准确率趋势（表格）
 * - 🏭 板块热力图（Top 10 板块涨幅排名/频率）
 * - 🏆 Top 个股排行榜（多日涨幅合并）
 * - 📊 最优持仓周期对比（1/3/5/10天）
 *
 * ### 使用方式
 * ```kotlin
 * val fragment = StrategyStatsFragment.newInstance(selectedDays = 30)
 * fragment.show(parentFragmentManager, "stats")
 * ```
 */
class StrategyStatsFragment : Fragment() {

    companion object {
        fun newInstance(selectedDays: Int = 30): StrategyStatsFragment {
            return StrategyStatsFragment().apply {
                arguments = Bundle().apply { putInt("days", selectedDays) }
            }
        }
    }

    private var selectedDays: Int = 30

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        selectedDays = arguments?.getInt("days", 30) ?: 30

        val scrollView = ScrollView(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 24, 16, 32)
            setBackgroundColor(Color.parseColor("#F5F6FA"))
        }

        // 标题
        root.addView(TextView(requireContext()).apply {
            text = "📊 多交易日统计 (近 ${selectedDays} 个交易日)"
            textSize = 20f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 4)
        })
        root.addView(TextView(requireContext()).apply {
            text = "策略准确率 · 板块热力图 · 个股排行 · 最优持仓周期"
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
            setPadding(0, 0, 0, 16)
        })

        // 加载中
        val loadingTv = TextView(requireContext()).apply {
            text = "⏳ 正在分析历史数据..."
            textSize = 14f; setTextColor(Color.parseColor("#888888"))
            setPadding(0, 32, 0, 32); gravity = Gravity.CENTER
        }
        root.addView(loadingTv)

        scrollView.addView(root)

        lifecycleScope.launch {
            val data = loadStats()
            withContext(Dispatchers.Main) {
                root.removeView(loadingTv)
                if (data == null) {
                    root.addView(TextView(requireContext()).apply {
                        text = "⚠️ 暂无足够历史数据。\n请先在策略页 [导入] 历史K线，再执行几次扫描。"
                        textSize = 14f; setTextColor(Color.parseColor("#E65100"))
                        setPadding(16, 32, 16, 32)
                    })
                } else {
                    buildStatsUI(root, data)
                }
            }
        }

        return scrollView
    }

    data class StatsData(
        val strategyAccuracy: List<StrategyAccuracyRow>,
        val topSectors: List<SectorStatRow>,
        val topStocks: List<StockRankRow>,
        val optimalHolding: List<HoldingPeriodRow>
    )

    data class StrategyAccuracyRow(
        val strategyName: String,
        val totalBuys: Int,
        val correct: Int,
        val accuracy: Float,
        val avgReturn: Double
    )

    data class SectorStatRow(
        val sectorName: String,
        val totalEntries: Int,
        val hotDays: Int,
        val avgChangePct: Double
    )

    data class StockRankRow(
        val stockName: String,
        val stockCode: String,
        val totalScore: Int,
        val avgStrength: Double,
        val hitDays: Int
    )

    data class HoldingPeriodRow(
        val days: Int,
        val avgReturn: Double,
        val winRate: Float,
        val sampleCount: Int
    )

    private suspend fun loadStats(): StatsData? = withContext(Dispatchers.IO) {
        try {
            val db = StockDatabase.getInstance(requireContext())
            val dates = db.dailySnapshotDao().getAvailableDates(selectedDays + 1)
            if (dates.size < 3) return@withContext null

            // 1. 策略准确率
            val strategyAcc = mutableListOf<StrategyAccuracyRow>()
            val accuracyStats = db.strategyPredictionDao().getAccuracyStats()
            for (stat in accuracyStats.take(7)) {
                strategyAcc.add(StrategyAccuracyRow(
                    strategyName = stat.strategy_id,
                    totalBuys = stat.total,
                    correct = stat.correct_count,
                    accuracy = stat.accuracy,
                    avgReturn = 0.0  // getAccuracyStats() 不含 avgReturn，后续扩展
                ))
            }

            // 2. 板块热力图（Top 10）
            val sectorStats = mutableListOf<SectorStatRow>()
            try {
                val hotSectors = db.sectorDailyRecordDao().getTopHotSectors(10)
                for (h in hotSectors) {
                    val records = db.sectorDailyRecordDao().getBySectorCode(h.sector_code, selectedDays)
                    val avgPct = if (records.isNotEmpty()) records.map { it.changePct }.average() else 0.0
                    val sectorName = records.firstOrNull()?.sectorName ?: h.sector_code
                    sectorStats.add(SectorStatRow(
                        sectorName = sectorName,
                        totalEntries = h.cnt,
                        hotDays = h.hot_cnt,
                        avgChangePct = avgPct
                    ))
                }
            } catch (_: Exception) { /* 板块表可能为空 */ }

            // 3. Top 个股排行榜（多日预测强度汇总）
            val topStocks = mutableListOf<StockRankRow>()
            val allPredictions = dates.flatMap { d -> db.strategyPredictionDao().getByDate(d) }
            val stockScoreMap = mutableMapOf<String, Pair<String, MutableList<Int>>>() // code → (name, scores)
            for (pred in allPredictions) {
                val entry = stockScoreMap.getOrPut(pred.stockCode) {
                    Pair(pred.stockName, mutableListOf())
                }
                entry.second.add(pred.predictedScore)
            }
            stockScoreMap.entries
                .sortedByDescending { it.value.second.sum() }
                .take(20)
                .forEach { (code, pair) ->
                    val avgStrength = if (pair.second.isNotEmpty()) pair.second.average() else 0.0
                    topStocks.add(StockRankRow(
                        stockName = pair.first,
                        stockCode = code,
                        totalScore = pair.second.sum(),
                        avgStrength = avgStrength,
                        hitDays = pair.second.count { it >= 60 }
                    ))
                }

            // 4. 最优持仓周期（1/3/5/10天）
            val holdingPeriods = mutableListOf<HoldingPeriodRow>()
            for (windowDays in listOf(1, 3, 5, 10)) {
                var totalReturn = 0.0
                var winCount = 0
                var sampleCount = 0
                for (date in dates) {
                    val preds = db.strategyPredictionDao().getByDate(date)
                        .filter { it.predictedScore >= 70 }
                    for (pred in preds) {
                        val futureDate = getFutureTradingDay(pred.date, windowDays, dates)
                        if (futureDate == null) continue
                        val futureShots = db.dailySnapshotDao().getByDate(futureDate)
                        val futureShot = futureShots.firstOrNull { it.code == pred.stockCode } ?: continue
                        val entrySnapshot = db.dailySnapshotDao().getByDate(pred.date)
                        val entryShot = entrySnapshot.firstOrNull { it.code == pred.stockCode } ?: continue
                        val ret = ((futureShot.close - entryShot.close) / entryShot.close) * 100
                        totalReturn += ret
                        if (ret > 0) winCount++
                        sampleCount++
                    }
                }
                if (sampleCount > 0) {
                    holdingPeriods.add(HoldingPeriodRow(
                        days = windowDays,
                        avgReturn = totalReturn / sampleCount,
                        winRate = winCount.toFloat() / sampleCount,
                        sampleCount = sampleCount
                    ))
                }
            }

            StatsData(
                strategyAccuracy = strategyAcc,
                topSectors = sectorStats,
                topStocks = topStocks,
                optimalHolding = holdingPeriods.sortedBy { it.days }
            )
        } catch (e: Exception) {
            android.util.Log.w("StrategyStats", "加载统计数据失败: ${e.message}")
            null
        }
    }

    private fun getFutureTradingDay(fromDate: String, offset: Int, availableDates: List<String>): String? {
        val sorted = availableDates.sorted()
        val idx = sorted.indexOf(fromDate)
        return if (idx >= 0 && idx + offset < sorted.size) sorted[idx + offset] else null
    }

    // ════════════════════════════════════════
    // UI 构建
    // ════════════════════════════════════════

    private fun buildStatsUI(root: LinearLayout, data: StatsData) {
        // Section 1: 策略准确率
        root.addView(sectionTitle("📈 策略准确率"))
        if (data.strategyAccuracy.isNotEmpty()) {
            val table = buildTable(
                headers = listOf("策略", "买入/正确", "准确率", "均收益"),
                headerWeights = listOf(2.2f, 1.5f, 1.0f, 1.0f)
            )
            for (row in data.strategyAccuracy) {
                val color = when {
                    row.accuracy >= 0.6f -> Color.parseColor("#2E7D32")
                    row.accuracy >= 0.45f -> Color.parseColor("#EF6C00")
                    else -> Color.parseColor("#E53935")
                }
                addTableRow(table, listOf(
                    row.strategyName.take(10),
                    "${row.correct}/${row.totalBuys}",
                    "${"%.1f".format(row.accuracy * 100)}%",
                    "${"%.2f".format(row.avgReturn)}%"
                ), color)
            }
            root.addView(table)
        } else {
            root.addView(emptyHint("暂无策略执行记录"))
        }

        root.addView(spacer())

        // Section 2: 板块热力图
        root.addView(sectionTitle("🔥 热门板块排行"))
        if (data.topSectors.isNotEmpty()) {
            val table = buildTable(
                headers = listOf("板块", "总次数", "热门天数", "均涨幅"),
                headerWeights = listOf(1.5f, 1.0f, 1.0f, 1.0f)
            )
            for (row in data.topSectors) {
                val color = when {
                    row.avgChangePct > 2.0 -> Color.parseColor("#E65100")
                    row.avgChangePct > 0 -> Color.parseColor("#2E7D32")
                    else -> Color.parseColor("#E53935")
                }
                addTableRow(table, listOf(
                    row.sectorName,
                    "${row.totalEntries}",
                    "${row.hotDays}天",
                    "${"%.2f".format(row.avgChangePct)}%"
                ), color)
            }
            root.addView(table)
        } else {
            root.addView(emptyHint("暂无板块记录"))
        }

        root.addView(spacer())

        // Section 3: Top 个股
        root.addView(sectionTitle("🏆 Top 个股多日综合"))
        if (data.topStocks.isNotEmpty()) {
            val table = buildTable(
                headers = listOf("股票", "代码", "总评分", "均强度", "命中"),
                headerWeights = listOf(1.8f, 1.2f, 0.8f, 0.8f, 0.6f)
            )
            for ((i, row) in data.topStocks.withIndex()) {
                val color = if (i < 3) Color.parseColor("#E65100") else Color.parseColor("#333333")
                addTableRow(table, listOf(
                    row.stockName,
                    row.stockCode.takeLast(6),
                    "${row.totalScore}",
                    "${"%.0f".format(row.avgStrength)}",
                    "${row.hitDays}天"
                ), color)
            }
            root.addView(table)
        } else {
            root.addView(emptyHint("暂无个股记录"))
        }

        root.addView(spacer())

        // Section 4: 最优持仓周期
        root.addView(sectionTitle("⏱ 最优持仓周期对比"))
        if (data.optimalHolding.isNotEmpty()) {
            val table = buildTable(
                headers = listOf("持有时长", "均收益", "胜率", "样本"),
                headerWeights = listOf(1.3f, 1.0f, 1.0f, 0.8f)
            )
            for (row in data.optimalHolding) {
                val color = if (row.avgReturn > 0) Color.parseColor("#2E7D32") else Color.parseColor("#E53935")
                addTableRow(table, listOf(
                    "${row.days}天",
                    "${"%.2f".format(row.avgReturn)}%",
                    "${"%.1f".format(row.winRate * 100)}%",
                    "${row.sampleCount}"
                ), color)
            }
            root.addView(table)
        } else {
            root.addView(emptyHint("暂无足够数据计算持仓周期"))
        }

        root.addView(spacer())

        // 统计区间提示
        root.addView(TextView(requireContext()).apply {
            text = "📋 统计区间: 近 ${selectedDays} 个交易日\n💡 建议导入更多历史数据以获得更有统计意义的结论"
            textSize = 11f; setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 8, 0, 16)
        })
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 16f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 12, 0, 8)
        }
    }

    private fun emptyHint(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = "  ⚠️ $text"
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
            setPadding(4, 4, 4, 8)
        }
    }

    private fun spacer(): View {
        return View(requireContext()).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, 8, 0, 8)
            }
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
    }

    private fun buildTable(headers: List<String>, headerWeights: List<Float>): TableLayout {
        val ctx = requireContext()
        val table = TableLayout(ctx).apply {
            isStretchAllColumns = true
            setBackgroundColor(Color.WHITE)
            setPadding(4, 4, 4, 4)
        }
        val headerRow = TableRow(ctx).apply {
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            setPadding(4, 6, 4, 6)
        }
        for ((i, h) in headers.withIndex()) {
            headerRow.addView(TextView(ctx).apply {
                text = h; textSize = 11f
                setTextColor(Color.parseColor("#1565C0"))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, headerWeights[i])
            })
        }
        table.addView(headerRow)
        return table
    }

    private fun addTableRow(table: TableLayout, cells: List<String>, color: Int) {
        val ctx = requireContext()
        val row = TableRow(ctx).apply {
            setPadding(4, 5, 4, 5)
        }
        val weights = listOf(2.2f, 1.5f, 1.0f, 1.0f, 0.8f, 0.6f)
        for ((i, cell) in cells.withIndex()) {
            row.addView(TextView(ctx).apply {
                text = cell; textSize = 11f; setTextColor(color)
                gravity = Gravity.CENTER
                layoutParams = TableRow.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT,
                    weights.getOrElse(i) { 1.0f }
                )
            })
        }
        table.addView(row)
    }
}