package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 机构线索加分节点 (InstitutionalTipsNode)
 *
 * 读取 AI 对话中提取的机构提示（研报/评级/目标价），
 * 对未过期线索涉及的股票在 MergedSignalPool 中加分。
 *
 * ### 加分规则
 * - 每条有效线索 +8 分（同一股票多条线索叠加，上限 +20）
 * - details 标记 "inst_tip" = 摘要
 *
 * ### 位置
 * 短线 / 超短线 XML：与 n_bounce 同层（n_boost → n_bounce → n_inst_tips → n_ai）
 * 或并入 n_bounce 之后。
 */
class InstitutionalTipsNode : BaseNode<Any, MergedSignalPool>("inst_tips", "机构线索加分", NodeType.ENRICHMENT) {

    companion object {
        private const val TAG = "InstitutionalTipsNode"
        private const val POINTS_PER_TIP = 8
        private const val MAX_BOOST = 20
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
            val dao = db.institutionalTipDao()

            // 清理过期 + 读取有效线索
            dao.deleteExpired(context.tradeDate)
            val activeTips = dao.getActiveTips(context.tradeDate)

            if (activeTips.isEmpty()) {
                context.log(nodeId, "$nodeName: 无有效机构线索，原样通过")
                return pool
            }

            // 按股票分组
            val tipsByCode = activeTips.groupBy { it.stockCode }
            context.log(nodeId, "🏦 $nodeName: ${activeTips.size} 条有效线索，涉及 ${tipsByCode.size} 只股票")

            var boostedCount = 0
            val boostedSignals = pool.boostedSignals.map { signal ->
                val tips = tipsByCode[signal.stockCode]
                if (tips != null && tips.isNotEmpty()) {
                    boostedCount++
                    val boost = (tips.size * POINTS_PER_TIP).coerceAtMost(MAX_BOOST)
                    val summary = tips.first().summary.ifEmpty { tips.first().tipType }
                    signal.copy(
                        strength = (signal.strength + boost).coerceAtMost(100),
                        details = signal.details + ("inst_tip" to "机构线索×${tips.size}+$boost | $summary")
                    )
                } else {
                    signal
                }
            }.sortedByDescending { it.strength }

            context.log(nodeId, "✅ $nodeName: $boostedCount 只候选获机构加分")
            context.recordStockFlow(nodeId, nodeName, pool.boostedSignals.size, boostedSignals.size, 0)

            MergedSignalPool(
                stockHits = pool.stockHits,
                stockNames = pool.stockNames,
                boostedSignals = boostedSignals
            )
        } catch (e: Exception) {
            Log.e(TAG, "机构线索加分异常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 异常(${e.message})，原样通过")
            pool
        }
    }
}
