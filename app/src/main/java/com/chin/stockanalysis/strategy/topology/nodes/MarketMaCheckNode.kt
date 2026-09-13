package com.chin.stockanalysis.strategy.topology.nodes

import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 大盘均线粘合向上检查节点
 *
 * 检查大盘（上证 sh000001）是否处于均线粘合向上状态。
 * 输出布林值供下游节点判断是否适合进场。
 *
 * ### 判定条件
 * - MA5 > MA10 > MA20（多头排列）
 * - MA5 与 MA20 离散率 < threshold（粘合）
 * - MA5 斜率 > 0（向上）
 *
 * ### 输出
 * [MarketMaCheckResult] 存入 context.setStageOutput("n_market_ma_check", result)
 *
 * ### 备注
 * 已被 [MarketMaUnifiedNode] 取代（统一检查 + 可配置阈值），
 * 此节点保留用于向下兼容。
 */
data class MarketMaCheckResult(
    val isConvergedUpward: Boolean = false,
    val ma5: Double = 0.0,
    val ma10: Double = 0.0,
    val ma20: Double = 0.0,
    val divergencePct: Double = 0.0,  // MA5-MA20 离散率 %
    val ma5Slope: Double = 0.0,       // MA5 斜率（3日变化率）
    val description: String = ""
)

class MarketMaConvergenceCheckNode(
    private val threshold: Double = 0.03
) : BaseNode<Any, MarketMaCheckResult>("market_ma_check", "大盘均线粘合检查", NodeType.FACTOR_COMPUTE) {

    companion object {
        private const val TAG = "MarketMaCheck"
        private const val INDEX_CODE = "sh000001"
    }

    override suspend fun execute(context: PipelineContext, input: Any): MarketMaCheckResult {
        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val snaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 30)
            if (snaps.size < 20) {
                context.log(nodeId, "$nodeName: 指数数据不足(${snaps.size}条)")
                return MarketMaCheckResult(description = "数据不足")
            }

            val sorted = snaps.sortedBy { it.date }
            val closes = sorted.map { it.close }

            val ma5 = closes.takeLast(5).average()
            val ma10 = closes.takeLast(10).average()
            val ma20 = closes.takeLast(20).average()

            // 离散率：(MA5 - MA20) / MA20
            val divergence = if (ma20 > 0) (ma5 - ma20) / ma20 else 0.0

            // MA5 斜率：最近3日 MA5 变化
            val ma5Prev = if (closes.size >= 8) closes.takeLast(8).take(5).average() else ma5
            val slope = if (ma5Prev > 0) (ma5 - ma5Prev) / ma5Prev else 0.0

            // 判定：多头排列 + 粘合 + 向上
            val isBullishAligned = ma5 > ma10 && ma10 > ma20
            val isConverged = kotlin.math.abs(divergence) < threshold
            val isUpward = slope > 0

            val result = MarketMaCheckResult(
                isConvergedUpward = isBullishAligned && isConverged && isUpward,
                ma5 = ma5,
                ma10 = ma10,
                ma20 = ma20,
                divergencePct = divergence * 100,
                ma5Slope = slope * 100,
                description = buildString {
                    append("MA5=${"%.2f".format(ma5)} MA10=${"%.2f".format(ma10)} MA20=${"%.2f".format(ma20)}")
                    append(" 离散=${"%.2f".format(divergence * 100)}%")
                    append(" 斜率=${"%.2f".format(slope * 100)}%")
                    append(if (isBullishAligned) " 多头✓" else " 非多头")
                    append(if (isConverged) " 粘合✓" else " 未粘合")
                    append(if (isUpward) " 向上✓" else " 向下")
                }
            )

            context.log(nodeId, "$nodeName: ${result.description} → ${if (result.isConvergedUpward) "适合进场" else "不宜进场"}")
            context.setStageOutput(nodeId, result)
            result
        } catch (e: Exception) {
            context.log(nodeId, "$nodeName 异常: ${e.message}")
            MarketMaCheckResult(description = "异常: ${e.message}")
        }
    }
}
