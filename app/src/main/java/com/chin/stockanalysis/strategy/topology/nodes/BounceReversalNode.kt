package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 跌后反弹加分节点 (BounceReversalNode)
 *
 * 在「精选」(AI/SmartMoney) 之前，检测大盘 + 个股是否出现「三天不新低」企稳信号，
 * 若满足则对候选信号适当加分，并在报告中标注。
 *
 * ### 触发条件（AND）
 * 1. 大盘（上证 sh000001）连续 ≥3 天不创新低（每日 low > 前一日 low 或 >= 3日前最低 low）
 * 2. 外围市场非空头（overseas.direction != BEARISH）
 *
 * ### 个股加分条件
 * - 个股近 3 天 low 均 > 第 4 天 low（即 3 天不新低）
 * - 满足 → strength +12，details 标记 "bounce_boost"
 *
 * ### 位置
 * 所有周期 XML：n_boost → **n_bounce** → n_ai
 */
class BounceReversalNode : BaseNode<Any, MergedSignalPool>("bounce_reversal", "跌后反弹加分", NodeType.ENRICHMENT) {

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
                context.log(nodeId, "$nodeName: 输入非 MergedSignalPool，跳过")
                return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
            }
        }

        if (pool.boostedSignals.isEmpty()) return pool

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val dao = db.dailySnapshotDao()

            // ═══ 1. 大盘三天不新低检测 ═══
            val indexSnaps = dao.getByCode(INDEX_CODE, NO_NEW_LOW_DAYS + 2)
            val marketStable = checkNoNewLow(indexSnaps.map { it.low })

            // ═══ 2. 外围市场方向 ═══
            val marketReport = context.getMarketReport()
            val overseasOk = marketReport?.overseas?.direction != "BEARISH"
            val overseasDir = marketReport?.overseas?.direction ?: "UNKNOWN"

            if (!marketStable) {
                context.log(nodeId, "$nodeName: 大盘未满足三天不新低，跳过加分")
                context.setStageOutput("bounce_active", false)
                return pool
            }
            if (!overseasOk) {
                context.log(nodeId, "$nodeName: 外围市场空头($overseasDir)，跳过加分")
                context.setStageOutput("bounce_active", false)
                return pool
            }

            // ═══ 2.5 均线粘合 + 震荡收割检测 ═══
            val maResult = context.getStageOutput<MaConvergenceResult>("n_ma_conv")
            if (maResult != null && maResult.riskLevel == "HIGH") {
                context.log(nodeId, "⚠️ $nodeName: 均线未粘合+震荡收割模式，外围利好可能是陷阱，跳过加分 | ${maResult.hint}")
                context.setStageOutput("bounce_active", false)
                return pool
            }
            if (maResult != null && maResult.oscillationHarvest) {
                context.log(nodeId, "⚠️ $nodeName: 侦测到震荡收割(${maResult.oscillationCount}天)，加分减半")
            }

            context.log(nodeId, "📈 $nodeName: 大盘三天不新低 ✓ | 外围 $overseasDir ✓ → 开始个股检测")
            context.setStageOutput("bounce_active", true)

            // ═══ 3. 个股逐只检测 + 加分 ═══
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

            context.log(nodeId, "✅ $nodeName: ${boostedCount}/${pool.boostedSignals.size} 只满足三天不新低，加分 +$BOOST_POINTS")
            context.recordStockFlow(nodeId, nodeName, pool.boostedSignals.size, boostedSignals.size, 0)

            MergedSignalPool(
                stockHits = pool.stockHits,
                stockNames = pool.stockNames,
                boostedSignals = boostedSignals
            )
        } catch (e: Exception) {
            Log.e(TAG, "反弹加分异常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 异常(${e.message})，原样通过")
            pool
        }
    }

    /**
     * 检测「三天不新低」：最近 3 天的 low 都不低于第 4 天的 low。
     * snaps 按日期降序（最新在前），至少需要 4 条数据。
     */
    private fun checkNoNewLow(lows: List<Double>): Boolean {
        return com.chin.stockanalysis.strategy.analysis.StabilityChecker.checkDescendingLows(lows)
    }
}
