package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.strategy.models.StrategySignal
import com.chin.stockanalysis.strategy.topology.core.BaseNode
import com.chin.stockanalysis.strategy.topology.core.NodeType
import com.chin.stockanalysis.strategy.topology.core.PipelineContext
import com.chin.stockanalysis.strategy.topology.core.MergedSignalPool
import com.chin.stockanalysis.stock.database.StockDatabase

/**
 * ## 财务健康筛选节点 (FinancialHealthNode)
 *
 * 参考《豆包全套系统化交易体系》第五部分：财务健康筛选体系（排雷 + 优选）。
 * 插入一键建仓 DAG 的「主力资金过滤/新闻拦截 → AI 精选」之间，对技术面/资金面已通过的候选做基本面排雷。
 *
 * 排雷（硬性排除，任意一项 → 财务分 0，直接剔除）：
 * 1. 亏损（PE ≤ 0）
 * 2. 资产负债率 > 70%（银行/保险豁免，天然高负债经营）
 * 3. ROE 极低（< 1%）—— 盈利质量差
 *
 * 优选评分（0-100，豆包口径：<60 观望 / 60-69 仅短线 / 70-79 中线+短线 / 80+ 可中长线）：
 * - ROE(30 分)：ROE ≥ 15 → 30，线性递减
 * - 负债率(25 分)：≤ 40 → 25，40~70 线性递减，> 70 排雷
 * - PE(15 分)：0 < PE ≤ 30 → 15，30~60 递减
 * - PB(15 分)：≤ 3 → 15，线性递减
 * - 市值(15 分)：≥ 100 亿 → 15，线性递减
 *
 * 容错：快照缺数据 / 查询失败 → 不剔除（保持原信号），全部失败 → 透传原池，不阻断建仓。
 */
class FinancialHealthNode(
    private val minScore: Int = 60,
    private val maxDebtRatio: Double = 70.0
) : BaseNode<Any, MergedSignalPool>("financial_health", "财务健康筛选", NodeType.ENRICHMENT) {

    companion object {
        private const val TAG = "FinancialHealth"

        /** 高负债豁免板块（银行/保险天然高负债经营） */
        private val HIGH_DEBT_EXEMPT = listOf("银行", "保险")
    }

    override suspend fun execute(context: PipelineContext, input: Any): MergedSignalPool {
        val pool: MergedSignalPool = when (input) {
            is MergedSignalPool -> input
            else -> context.getStageOutput<MergedSignalPool>("smart_money_filter")
                ?: context.getStageOutput<MergedSignalPool>("n_smart")
                ?: return MergedSignalPool(emptyMap(), emptyMap(), emptyList())
        }
        if (pool.boostedSignals.isEmpty()) return pool

        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val snaps = db.dailySnapshotDao().getByDate(context.tradeDate).associateBy { it.code }

            // 高负债豁免板块判定（银行/保险）
            val exemptCodes = mutableSetOf<String>()
            for (code in pool.boostedSignals.map { it.stockCode }) {
                val sectors = db.sectorStockDao().getSectorNamesByStockCode(code)
                if (HIGH_DEBT_EXEMPT.any { kw -> sectors.any { it.contains(kw) } }) {
                    exemptCodes.add(code)
                }
            }

            val kept = mutableListOf<StrategySignal>()
            val dropped = mutableListOf<String>()
            for (sig in pool.boostedSignals) {
                val snap = snaps[sig.stockCode]
                if (snap == null ||
                    (snap.pe <= 0 && snap.roeTTM <= 0 && snap.debtToAsset <= 0)
                ) {
                    // 数据缺失 → 无法判断，不剔除（保持原信号）
                    kept.add(sig)
                    continue
                }
                val score = scoreOf(
                    pe = snap.pe, pb = snap.pb, roe = snap.roeTTM,
                    debt = snap.debtToAsset, cap = snap.marketCap,
                    debtExempt = sig.stockCode in exemptCodes
                )
                if (score >= minScore) {
                    kept.add(sig.copy(reason = "${sig.reason} · 财务${score}分"))
                } else {
                    dropped.add("${sig.stockName}财务${score}分(<$minScore)")
                }
            }

            if (dropped.isEmpty()) {
                context.log(nodeId, "📤 $nodeName: ${pool.boostedSignals.size} 只候选全部通过财务健康筛选")
                return pool
            }

            val keptCodes = kept.map { it.stockCode }.toSet()
            val result = MergedSignalPool(
                stockHits = pool.stockHits.filterKeys { it in keptCodes },
                stockNames = pool.stockNames.filterKeys { it in keptCodes },
                boostedSignals = kept
            )

            context.log(nodeId, "📤 $nodeName: ${pool.boostedSignals.size} → ${kept.size}（剔除 ${dropped.joinToString("; ") { it }}）")
            context.recordStockFlow(
                nodeId = nodeId, nodeName = nodeName,
                inputCount = pool.boostedSignals.size, outputCount = kept.size,
                filterCount = pool.boostedSignals.size - kept.size,
                filterReason = "财务排雷/评分<$minScore",
                inputCodes = emptyList(), outputCodes = keptCodes.toList()
            )
            result

        } catch (e: Exception) {
            Log.e(TAG, "财务健康筛选失败: ${e.message}", e)
            context.recordError(nodeId, "财务健康筛选失败: ${e.message}")
            pool  // 容错：失败透传原池，不阻断建仓
        }
    }

    /**
     * 财务健康评分（0-100）。
     * 豆包口径：<60 观望 / 60-69 仅短线 / 70-79 中线+短线 / 80+ 可中长线。
     */
    private fun scoreOf(
        pe: Double, pb: Double, roe: Double,
        debt: Double, cap: Double, debtExempt: Boolean
    ): Int {
        // ── 排雷（硬性排除） ──
        if (pe <= 0) return 0                                   // 亏损
        if (!debtExempt && debt > maxDebtRatio) return 0        // 高负债（非豁免）
        if (roe < 1.0) return 0                                 // 盈利质量极差

        val roeScore = (roe.coerceAtMost(20.0) / 20.0 * 30).toInt()                         // 30 分
        val debtScore = if (debtExempt || debt <= 40) {
            25
        } else {
            ((maxDebtRatio - debt) / 30.0 * 25).coerceIn(0.0, 25.0).toInt()                  // 25 分
        }
        val peScore = when {
            pe <= 30 -> 15
            pe <= 60 -> (15 * (60 - pe) / 30).toInt()
            else -> 5
        }                                                                                      // 15 分
        val pbScore = (15 * (3.0 - pb.coerceAtMost(3.0)) / 3.0).toInt().coerceAtLeast(0)      // 15 分
        val capScore = (15 * (cap / 100_0000_0000.0).coerceAtMost(1.0)).toInt().coerceAtLeast(0) // 15 分
        return roeScore + debtScore + peScore + pbScore + capScore
    }
}
