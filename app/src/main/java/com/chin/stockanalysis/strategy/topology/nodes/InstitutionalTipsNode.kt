package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 機構線索加分節點 (InstitutionalTipsNode)
 *
 * 讀取 AI 對話中提取的機構提示（研報/評級/目標價），
 * 對未過期線索涉及的股票在 MergedSignalPool 中加分。
 *
 * ### 加分規則
 * - 每條有效線索 +8 分（同一股票多條線索疊加，上限 +20）
 * - details 標記 "inst_tip" = 摘要
 *
 * ### 位置
 * 短線 / 超短線 XML：與 n_bounce 同層（n_boost → n_bounce → n_inst_tips → n_ai）
 * 或併入 n_bounce 之後。
 */
class InstitutionalTipsNode : PipelineNode<Any, MergedSignalPool> {

    companion object {
        private const val TAG = "InstitutionalTipsNode"
        private const val POINTS_PER_TIP = 8
        private const val MAX_BOOST = 20
    }

    override val nodeId: String = "inst_tips"
    override val nodeName: String = "機構線索加分"
    override val nodeType: NodeType = NodeType.ENRICHMENT

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
            val dao = db.institutionalTipDao()

            // 清理過期 + 讀取有效線索
            dao.deleteExpired(context.tradeDate)
            val activeTips = dao.getActiveTips(context.tradeDate)

            if (activeTips.isEmpty()) {
                context.log(nodeId, "$nodeName: 無有效機構線索，原樣通過")
                return pool
            }

            // 按股票分組
            val tipsByCode = activeTips.groupBy { it.stockCode }
            context.log(nodeId, "🏦 $nodeName: ${activeTips.size} 條有效線索，涉及 ${tipsByCode.size} 只股票")

            var boostedCount = 0
            val boostedSignals = pool.boostedSignals.map { signal ->
                val tips = tipsByCode[signal.stockCode]
                if (tips != null && tips.isNotEmpty()) {
                    boostedCount++
                    val boost = (tips.size * POINTS_PER_TIP).coerceAtMost(MAX_BOOST)
                    val summary = tips.first().summary.ifEmpty { tips.first().tipType }
                    signal.copy(
                        strength = (signal.strength + boost).coerceAtMost(100),
                        details = signal.details + ("inst_tip" to "機構線索×${tips.size}+$boost | $summary")
                    )
                } else {
                    signal
                }
            }.sortedByDescending { it.strength }

            context.log(nodeId, "✅ $nodeName: $boostedCount 只候選獲機構加分")
            context.recordStockFlow(nodeId, nodeName, pool.boostedSignals.size, boostedSignals.size, 0)

            MergedSignalPool(
                stockHits = pool.stockHits,
                stockNames = pool.stockNames,
                boostedSignals = boostedSignals
            )
        } catch (e: Exception) {
            Log.e(TAG, "機構線索加分異常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 異常(${e.message})，原樣通過")
            pool
        }
    }
}
