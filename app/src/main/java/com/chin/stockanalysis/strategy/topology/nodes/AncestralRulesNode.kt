package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.PricePositionAnalyzer
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 大A祖训 · 选股加分/减分节点
 *
 * 将五条祖训量化为可计算的选股信号，对 MergedSignalPool 中的候选股票进行加分或减分。
 *
 * ### 五条祖训量化规则
 *
 * 1. **高开要跑**：当日高开 >2% 且收阴（close < open）→ 减分
 *    - 超短线：高开即跑，重罚
 *    - 短线：高开看承接，破均线才跑
 *    - 中长线：高开不敏感，除非估值过高
 *
 * 2. **买无人问津时**：低换手率 + 价格在 60 日低位区间 → 加分
 *    - 换手率 < 1% 且 close 在 60 日最低价 15% 以内
 *    - 中长线加大加分（这是长线的主场）
 *
 * 3. **卖人声鼎沸时**：高换手率 + 价格在 60 日高位 + 量价背离 → 减分
 *    - 换手率 > 8% 且 close 在 60 日最高价 5% 以内
 *    - 成交量放大但股价滞涨（筹码高位换手）
 *
 * 4. **低位利空=利好**：低位 + 大跌 + 下影线 → 加分
 *    - close 在 60 日低位 20% 以内
 *    - 当日跌幅 > 3% 或有长下影线（low 远低于 open/close）
 *
 * 5. **高位利好=利空**：高位 + 大涨 + 墓碑线 → 减分
 *    - close 在 60 日高位 5% 以内
 *    - 当日涨幅 > 3% 或有长上影线（high 远高于 open/close）
 *
 * ### 位置
 * 所有周期 XML：n_bounce → **n_ancestral** → n_ai（或下游节点）
 */
class AncestralRulesNode(
    private val holdingPeriod: String = "SHORT"
) : BaseNode<Any, MergedSignalPool>("ancestral_rules", "大A祖训", NodeType.ENRICHMENT) {

    companion object {
        private const val TAG = "AncestralRulesNode"
        private const val LOOKBACK_DAYS = 60  // 60 日回看窗口

        // 规则阈值
        private const val GAP_UP_THRESHOLD = 0.02     // 高开 2%
        private const val BIG_DROP_THRESHOLD = -0.03   // 大跌 3%
        private const val BIG_GAIN_THRESHOLD = 0.03    // 大涨 3%
        private const val LOW_TURNOVER_THRESHOLD = 1.0 // 低换手率 1%
        private const val HIGH_TURNOVER_THRESHOLD = 8.0 // 高换手率 8%
        private const val LOW_POSITION_PCT = 0.15      // 低位区间：距60日低点15%以内
        private const val HIGH_POSITION_PCT = 0.05     // 高位区间：距60日高点5%以内
    }

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> {
                context.log(nodeId, "$nodeName: 输入非 MergedSignalPool，跳过")
                return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }

        if (pool.boostedSignals.isEmpty()) return pool

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val dao = db.dailySnapshotDao()

            // 大盘情绪参考（MarketReport）
            val marketReport = context.getMarketReport()
            val marketBearish = marketReport?.trend?.direction == "BEARISH"

            var adjustedCount = 0
            val adjustedSignals = pool.boostedSignals.map { signal ->
                val snaps = dao.getByCode(signal.stockCode, LOOKBACK_DAYS + 5)
                if (snaps.size < 20) {
                    signal  // 数据不足，不调整
                } else {
                    val sorted = snaps.sortedBy { it.date }
                    val result = evaluateRules(sorted, signal.changePercent)
                    if (result.totalAdjustment != 0) {
                        adjustedCount++
                        val newStrength = (signal.strength + result.totalAdjustment).coerceIn(0, 100)
                        signal.copy(
                            strength = newStrength,
                            details = signal.details + ("ancestral" to result.summary)
                        )
                    } else {
                        signal
                    }
                }
            }.sortedByDescending { it.strength }

            context.log(nodeId, "📜 $nodeName: ${adjustedCount}/${pool.boostedSignals.size} 只触发祖训规则")

            MergedSignalPool(
                stockHits = pool.stockHits,
                stockNames = pool.stockNames,
                boostedSignals = adjustedSignals
            )
        } catch (e: Exception) {
            Log.e(TAG, "祖训评估异常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 异常(${e.message})，原样通过")
            pool
        }
    }

    /**
     * 对单只股票评估五条祖训
     * @param snaps 按日期升序排列，至少 20 条
     * @param todayChangePct 今日涨跌幅（百分比，如 3.5 = 涨3.5%）
     */
    private fun evaluateRules(snaps: List<DailySnapshotEntity>, todayChangePct: Double): RuleResult {
        val today = snaps.last()
        val prevDay = snaps[snaps.size - 2]
        val tags = mutableListOf<String>()
        var totalAdj = 0

        // 60 日高低点
        val range60 = snaps.takeLast(LOOKBACK_DAYS)

        // 当前位置（0=最低，1=最高）
        val positionPct = PricePositionAnalyzer.fromHighLow(range60, today.close)

        // ═══ 规则 1：高开要跑 ═══
        val gapPct = if (prevDay.close > 0) (today.open - prevDay.close) / prevDay.close else 0.0
        if (gapPct > GAP_UP_THRESHOLD && today.close < today.open) {
            // 高开 + 收阴 = 跑路信号
            val penalty = when (holdingPeriod) {
                "ULTRA_SHORT" -> -15
                "SHORT" -> -10
                "MID" -> -5
                else -> -3  // LONG
            }
            totalAdj += penalty
            tags.add("高开要跑(${"%.1f".format(gapPct * 100)}%高开收阴$penalty)")
        }

        // ═══ 规则 2：买无人问津时 ═══
        val isLowPosition = positionPct < LOW_POSITION_PCT
        val avgVol = range60.map { it.volume.toDouble() }.average()
        val volRatio = if (avgVol > 0) today.volume.toDouble() / avgVol else 1.0
        val lowTurnover = today.turnoverRate > 0 && today.turnoverRate < LOW_TURNOVER_THRESHOLD

        if (isLowPosition && (lowTurnover || volRatio < 0.5)) {
            val bonus = when (holdingPeriod) {
                "LONG" -> 15   // 长线主场
                "MID" -> 12
                "SHORT" -> 5
                else -> 3      // 超短线不太适用
            }
            totalAdj += bonus
            tags.add("买无人问津(低位+低量+$bonus)")
        }

        // ═══ 规则 3：卖人声鼎沸时 ═══
        val isHighPosition = positionPct > (1.0 - HIGH_POSITION_PCT)
        val highTurnover = today.turnoverRate > HIGH_TURNOVER_THRESHOLD
        val volumeSurgeStagnant = volRatio > 2.0 && Math.abs(todayChangePct) < 1.0  // 量大但不涨

        if (isHighPosition && (highTurnover || volumeSurgeStagnant)) {
            val penalty = when (holdingPeriod) {
                "ULTRA_SHORT" -> -12
                "SHORT" -> -15
                "MID" -> -10
                else -> -8  // LONG
            }
            totalAdj += penalty
            tags.add("卖人声鼎沸(高位+放量滞涨$penalty)")
        }

        // ═══ 规则 4：低位利空=利好 ═══
        val hasLongLowerShadow = (today.open - today.low) > 2 * Math.abs(today.close - today.open) && today.low < today.open
        val bigDrop = todayChangePct < (BIG_DROP_THRESHOLD * 100)

        if (isLowPosition && (bigDrop || hasLongLowerShadow)) {
            val bonus = when (holdingPeriod) {
                "LONG" -> 18   // 长线暴富开关
                "MID" -> 15
                "SHORT" -> 8
                else -> 5      // 超短线轻仓博反抽
            }
            totalAdj += bonus
            val reason = if (bigDrop) "大跌${"%.1f".format(todayChangePct)}%" else "长下影线"
            tags.add("低位利空=利好($reason+$bonus)")
        }

        // ═══ 规则 5：高位利好=利空 ═══
        val hasLongUpperShadow = (today.high - today.open) > 2 * Math.abs(today.close - today.open) && today.high > today.open
        val bigGain = todayChangePct > (BIG_GAIN_THRESHOLD * 100)

        if (isHighPosition && (bigGain || hasLongUpperShadow)) {
            val penalty = when (holdingPeriod) {
                "ULTRA_SHORT" -> -10
                "SHORT" -> -12
                "MID" -> -15
                else -> -18  // 长线清仓号角
            }
            totalAdj += penalty
            val reason = if (bigGain) "大涨${"%.1f".format(todayChangePct)}%" else "长上影线"
            tags.add("高位利好=利空($reason$penalty)")
        }

        val summary = if (tags.isEmpty()) "无触发" else tags.joinToString("; ")
        return RuleResult(totalAdj, summary)
    }

    data class RuleResult(
        val totalAdjustment: Int,
        val summary: String
    )
}
