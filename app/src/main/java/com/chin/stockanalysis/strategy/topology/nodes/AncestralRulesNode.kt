package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.PricePositionAnalyzer
import com.chin.stockanalysis.strategy.backtest.DailySnapshotEntity
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 大A祖訓 · 選股加分/減分節點
 *
 * 將五條祖訓量化為可計算的選股信號，對 MergedSignalPool 中的候選股票進行加分或減分。
 *
 * ### 五條祖訓量化規則
 *
 * 1. **高開要跑**：當日高開 >2% 且收陰（close < open）→ 減分
 *    - 超短線：高開即跑，重罰
 *    - 短線：高開看承接，破均線才跑
 *    - 中長線：高開不敏感，除非估值過高
 *
 * 2. **買無人問津時**：低換手率 + 價格在 60 日低位區間 → 加分
 *    - 換手率 < 1% 且 close 在 60 日最低價 15% 以內
 *    - 中長線加大加分（這是長線的主場）
 *
 * 3. **賣人聲鼎沸時**：高換手率 + 價格在 60 日高位 + 量價背離 → 減分
 *    - 換手率 > 8% 且 close 在 60 日最高價 5% 以內
 *    - 成交量放大但股價滯漲（籌碼高位換手）
 *
 * 4. **低位利空=利好**：低位 + 大跌 + 下影線 → 加分
 *    - close 在 60 日低位 20% 以內
 *    - 當日跌幅 > 3% 或有長下影線（low 遠低於 open/close）
 *
 * 5. **高位利好=利空**：高位 + 大漲 + 墓碑線 → 減分
 *    - close 在 60 日高位 5% 以內
 *    - 當日漲幅 > 3% 或有長上影線（high 遠高於 open/close）
 *
 * ### 位置
 * 所有周期 XML：n_bounce → **n_ancestral** → n_ai（或下游節點）
 */
class AncestralRulesNode(
    private val holdingPeriod: String = "SHORT"
) : BaseNode<Any, MergedSignalPool>("ancestral_rules", "大A祖訓", NodeType.ENRICHMENT) {

    companion object {
        private const val TAG = "AncestralRulesNode"
        private const val LOOKBACK_DAYS = 60  // 60 日回看窗口

        // 規則閾值
        private const val GAP_UP_THRESHOLD = 0.02     // 高開 2%
        private const val BIG_DROP_THRESHOLD = -0.03   // 大跌 3%
        private const val BIG_GAIN_THRESHOLD = 0.03    // 大漲 3%
        private const val LOW_TURNOVER_THRESHOLD = 1.0 // 低換手率 1%
        private const val HIGH_TURNOVER_THRESHOLD = 8.0 // 高換手率 8%
        private const val LOW_POSITION_PCT = 0.15      // 低位區間：距60日低點15%以內
        private const val HIGH_POSITION_PCT = 0.05     // 高位區間：距60日高點5%以內
    }

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> {
                context.log(nodeId, "$nodeName: 輸入非 MergedSignalPool，跳過")
                return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }

        if (pool.boostedSignals.isEmpty()) return pool

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val dao = db.dailySnapshotDao()

            // 大盤情緒參考（MarketReport）
            val marketReport = context.getMarketReport()
            val marketBearish = marketReport?.trend?.direction == "BEARISH"

            var adjustedCount = 0
            val adjustedSignals = pool.boostedSignals.map { signal ->
                val snaps = dao.getByCode(signal.stockCode, LOOKBACK_DAYS + 5)
                if (snaps.size < 20) {
                    signal  // 數據不足，不調整
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

            context.log(nodeId, "📜 $nodeName: ${adjustedCount}/${pool.boostedSignals.size} 只觸發祖訓規則")

            MergedSignalPool(
                stockHits = pool.stockHits,
                stockNames = pool.stockNames,
                boostedSignals = adjustedSignals
            )
        } catch (e: Exception) {
            Log.e(TAG, "祖訓評估異常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 異常(${e.message})，原樣通過")
            pool
        }
    }

    /**
     * 對單隻股票評估五條祖訓
     * @param snaps 按日期升序排列，至少 20 條
     * @param todayChangePct 今日漲跌幅（百分比，如 3.5 = 漲3.5%）
     */
    private fun evaluateRules(snaps: List<DailySnapshotEntity>, todayChangePct: Double): RuleResult {
        val today = snaps.last()
        val prevDay = snaps[snaps.size - 2]
        val tags = mutableListOf<String>()
        var totalAdj = 0

        // 60 日高低點
        val range60 = snaps.takeLast(LOOKBACK_DAYS)

        // 當前位置（0=最低，1=最高）
        val positionPct = PricePositionAnalyzer.fromHighLow(range60, today.close)

        // ═══ 規則 1：高開要跑 ═══
        val gapPct = if (prevDay.close > 0) (today.open - prevDay.close) / prevDay.close else 0.0
        if (gapPct > GAP_UP_THRESHOLD && today.close < today.open) {
            // 高開 + 收陰 = 跑路信號
            val penalty = when (holdingPeriod) {
                "ULTRA_SHORT" -> -15
                "SHORT" -> -10
                "MID" -> -5
                else -> -3  // LONG
            }
            totalAdj += penalty
            tags.add("高開要跑(${"%.1f".format(gapPct * 100)}%高開收陰$penalty)")
        }

        // ═══ 規則 2：買無人問津時 ═══
        val isLowPosition = positionPct < LOW_POSITION_PCT
        val avgVol = range60.map { it.volume.toDouble() }.average()
        val volRatio = if (avgVol > 0) today.volume.toDouble() / avgVol else 1.0
        val lowTurnover = today.turnoverRate > 0 && today.turnoverRate < LOW_TURNOVER_THRESHOLD

        if (isLowPosition && (lowTurnover || volRatio < 0.5)) {
            val bonus = when (holdingPeriod) {
                "LONG" -> 15   // 長線主場
                "MID" -> 12
                "SHORT" -> 5
                else -> 3      // 超短線不太適用
            }
            totalAdj += bonus
            tags.add("買無人問津(低位+低量+$bonus)")
        }

        // ═══ 規則 3：賣人聲鼎沸時 ═══
        val isHighPosition = positionPct > (1.0 - HIGH_POSITION_PCT)
        val highTurnover = today.turnoverRate > HIGH_TURNOVER_THRESHOLD
        val volumeSurgeStagnant = volRatio > 2.0 && Math.abs(todayChangePct) < 1.0  // 量大但不漲

        if (isHighPosition && (highTurnover || volumeSurgeStagnant)) {
            val penalty = when (holdingPeriod) {
                "ULTRA_SHORT" -> -12
                "SHORT" -> -15
                "MID" -> -10
                else -> -8  // LONG
            }
            totalAdj += penalty
            tags.add("賣人聲鼎沸(高位+放量滯漲$penalty)")
        }

        // ═══ 規則 4：低位利空=利好 ═══
        val hasLongLowerShadow = (today.open - today.low) > 2 * Math.abs(today.close - today.open) && today.low < today.open
        val bigDrop = todayChangePct < (BIG_DROP_THRESHOLD * 100)

        if (isLowPosition && (bigDrop || hasLongLowerShadow)) {
            val bonus = when (holdingPeriod) {
                "LONG" -> 18   // 長線暴富開關
                "MID" -> 15
                "SHORT" -> 8
                else -> 5      // 超短線輕倉博反抽
            }
            totalAdj += bonus
            val reason = if (bigDrop) "大跌${"%.1f".format(todayChangePct)}%" else "長下影線"
            tags.add("低位利空=利好($reason+$bonus)")
        }

        // ═══ 規則 5：高位利好=利空 ═══
        val hasLongUpperShadow = (today.high - today.open) > 2 * Math.abs(today.close - today.open) && today.high > today.open
        val bigGain = todayChangePct > (BIG_GAIN_THRESHOLD * 100)

        if (isHighPosition && (bigGain || hasLongUpperShadow)) {
            val penalty = when (holdingPeriod) {
                "ULTRA_SHORT" -> -10
                "SHORT" -> -12
                "MID" -> -15
                else -> -18  // 長線清倉號角
            }
            totalAdj += penalty
            val reason = if (bigGain) "大漲${"%.1f".format(todayChangePct)}%" else "長上影線"
            tags.add("高位利好=利空($reason$penalty)")
        }

        val summary = if (tags.isEmpty()) "無觸發" else tags.joinToString("; ")
        return RuleResult(totalAdj, summary)
    }

    data class RuleResult(
        val totalAdjustment: Int,
        val summary: String
    )
}
