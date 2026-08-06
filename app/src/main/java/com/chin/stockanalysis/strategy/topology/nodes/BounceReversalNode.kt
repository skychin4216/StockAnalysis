package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 跌後反彈加分節點 (BounceReversalNode)
 *
 * 在「精選」(AI/SmartMoney) 之前，檢測大盤 + 個股是否出現「三天不新低」企穩信號，
 * 若滿足則對候選信號適當加分，並在報告中標註。
 *
 * ### 觸發條件（AND）
 * 1. 大盤（上證 sh000001）連續 ≥3 天不創新低（每日 low > 前一日 low 或 >= 3日前最低 low）
 * 2. 外圍市場非空頭（overseas.direction != BEARISH）
 *
 * ### 個股加分條件
 * - 個股近 3 天 low 均 > 第 4 天 low（即 3 天不新低）
 * - 滿足 → strength +12，details 標記 "bounce_boost"
 *
 * ### 位置
 * 所有周期 XML：n_boost → **n_bounce** → n_ai
 */
class BounceReversalNode : BaseNode<Any, MergedSignalPool>("bounce_reversal", "跌後反彈加分", NodeType.ENRICHMENT) {

    companion object {
        private const val TAG = "BounceReversalNode"
        private const val INDEX_CODE = "sh000001"
        private const val NO_NEW_LOW_DAYS = 3
        private const val BOOST_POINTS = 12
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

            // ═══ 1. 大盤三天不新低檢測 ═══
            val indexSnaps = dao.getByCode(INDEX_CODE, NO_NEW_LOW_DAYS + 2)
            val marketStable = checkNoNewLow(indexSnaps.map { it.low })

            // ═══ 2. 外圍市場方向 ═══
            val marketReport = context.getMarketReport()
            val overseasOk = marketReport?.overseas?.direction != "BEARISH"
            val overseasDir = marketReport?.overseas?.direction ?: "UNKNOWN"

            if (!marketStable) {
                context.log(nodeId, "$nodeName: 大盤未滿足三天不新低，跳過加分")
                context.setStageOutput("bounce_active", false)
                return pool
            }
            if (!overseasOk) {
                context.log(nodeId, "$nodeName: 外圍市場空頭($overseasDir)，跳過加分")
                context.setStageOutput("bounce_active", false)
                return pool
            }

            // ═══ 2.5 均線粘合 + 震蕩收割檢測 ═══
            val maResult = context.getStageOutput<MaConvergenceResult>("n_ma_conv")
            if (maResult != null && maResult.riskLevel == "HIGH") {
                context.log(nodeId, "⚠️ $nodeName: 均線未粘合+震蕩收割模式，外圍利好可能是陷阱，跳過加分 | ${maResult.hint}")
                context.setStageOutput("bounce_active", false)
                return pool
            }
            if (maResult != null && maResult.oscillationHarvest) {
                context.log(nodeId, "⚠️ $nodeName: 偵測到震蕩收割(${maResult.oscillationCount}天)，加分減半")
            }

            context.log(nodeId, "📈 $nodeName: 大盤三天不新低 ✓ | 外圍 $overseasDir ✓ → 開始個股檢測")
            context.setStageOutput("bounce_active", true)

            // ═══ 3. 個股逐隻檢測 + 加分 ═══
            val effectiveBoost = if (maResult?.oscillationHarvest == true) BOOST_POINTS / 2 else BOOST_POINTS
            var boostedCount = 0
            val boostedSignals = pool.boostedSignals.map { signal ->
                val stockSnaps = dao.getByCode(signal.stockCode, NO_NEW_LOW_DAYS + 2)
                val stockStable = checkNoNewLow(stockSnaps.map { it.low })

                if (stockStable) {
                    boostedCount++
                    signal.copy(
                        strength = (signal.strength + effectiveBoost).coerceAtMost(100),
                        details = signal.details + ("bounce_boost" to "三天不新低+${effectiveBoost}")
                    )
                } else {
                    signal
                }
            }.sortedByDescending { it.strength }

            context.log(nodeId, "✅ $nodeName: ${boostedCount}/${pool.boostedSignals.size} 只滿足三天不新低，加分 +$BOOST_POINTS")
            context.recordStockFlow(nodeId, nodeName, pool.boostedSignals.size, boostedSignals.size, 0)

            MergedSignalPool(
                stockHits = pool.stockHits,
                stockNames = pool.stockNames,
                boostedSignals = boostedSignals
            )
        } catch (e: Exception) {
            Log.e(TAG, "反彈加分異常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 異常(${e.message})，原樣通過")
            pool
        }
    }

    /**
     * 檢測「三天不新低」：最近 3 天的 low 都不低於第 4 天的 low。
     * snaps 按日期降序（最新在前），至少需要 4 條數據。
     */
    private fun checkNoNewLow(lows: List<Double>): Boolean {
        return com.chin.stockanalysis.strategy.analysis.StabilityChecker.checkDescendingLows(lows)
    }
}
