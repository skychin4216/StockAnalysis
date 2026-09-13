package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.BasePositionAnalyzer
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 打底仓守门 + 逃顶要快
 *
 * 总纲：长线看势，中线看价，短线看量，超短看情绪
 *
 * ### 抄底/打底仓两条件（中长线买入信号必须满足）
 * 1. 三天不新低 — 最近 3 天 low 均 > 前 3 天最低 low
 * 2. MA5/MA10/MA30 均线粘合向上
 *
 * ### 逃顶要快（卖出信号加速）
 * - 3 天急跌 > 5% 或跌破 3 日最低 low → 加强卖出信号
 *
 * ### 位置
 * mid/long XML：n_bounce → **n_guard** → n_ancestral
 */
class BasePositionGuardNode(
    private val holdingPeriod: String = "MID"
) : BaseNode<Any, MergedSignalPool>("base_position_guard", "打底仓守门", NodeType.FILTER) {

    companion object {
        private const val TAG = "BasePositionGuard"
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

            var adjustedCount = 0
            val adjustedSignals = pool.boostedSignals.map { signal ->
                val snaps = dao.getByCode(signal.stockCode, 60)
                    .sortedBy { it.date }
                if (snaps.size < 30) {
                    signal
                } else {
                    val analysis = BasePositionAnalyzer.analyze(snaps)
                    val isBuy = signal.strength > 50

                    when {
                        // 抄底/打底仓：条件不满足 → 大幅削弱买入信号
                        isBuy && !analysis.basePositionReady -> {
                            val penalty = if (holdingPeriod == "LONG") -30 else -20
                            val newStrength = (signal.strength + penalty).coerceIn(0, 100)
                            adjustedCount++
                            signal.copy(
                                strength = newStrength,
                                details = signal.details + ("basePosition" to "打底仓未就绪: ${analysis.hint}")
                            )
                        }
                        // 打底仓条件满足 → 加分
                        isBuy && analysis.maConvergedAndUp -> {
                            val bonus = if (holdingPeriod == "LONG") 20 else 12
                            val newStrength = (signal.strength + bonus).coerceIn(0, 100)
                            adjustedCount++
                            signal.copy(
                                strength = newStrength,
                                details = signal.details + ("basePosition" to "✅打底仓就绪: ${analysis.hint}")
                            )
                        }
                        // 逃顶要快：卖出信号 + 急跌/破低
                        !isBuy && analysis.escapeUrgent -> {
                            val boost = if (holdingPeriod == "LONG") 18 else 12
                            val newStrength = (signal.strength + boost).coerceIn(0, 100)
                            adjustedCount++
                            signal.copy(
                                strength = newStrength,
                                details = signal.details + ("escapeTop" to "⚡逃顶要快: ${analysis.hint}")
                            )
                        }
                        else -> signal
                    }
                }
            }.sortedByDescending { it.strength }

            context.log(nodeId, "🛡️ $nodeName: ${adjustedCount}/${pool.boostedSignals.size} 只触发守门规则")

            MergedSignalPool(
                stockHits = pool.stockHits,
                stockNames = pool.stockNames,
                boostedSignals = adjustedSignals
            )
        } catch (e: Exception) {
            Log.e(TAG, "守门评估异常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 异常(${e.message})，原样通过")
            pool
        }
    }
}
