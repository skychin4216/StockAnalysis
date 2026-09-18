package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.MaConvergenceAnalyzer
import com.chin.stockanalysis.strategy.analysis.MarketMicrostructureAnalyzer
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 大盘均线统一检查节点（Unified Market MA Check）
 *
 * 合并原 MaConvergenceNode（1.5% 阈值）+ MarketMaConvergenceCheckNode（3% 阈值）为一。
 * 透过 config 参数 `threshold` 控制粘合判定灵敏度，
 * 同时输出 [MaConvergenceResult] 和 [MarketMaCheckResult] 以兼容下游。
 *
 * ### 侦测项目
 * 1. 均线粘合度（MaConvergenceAnalyzer，可配置阈值）
 * 2. 多头排列（MA5 > MA10 > MA20）+ 斜率
 * 3. 震荡收割模式（近 5 天 ≥3 天高开低走 + 大振幅）
 * 4. 量价背离（跌量 > 涨量 × 1.3）
 * 5. 缺口风险（高开 >1% 且未粘合）
 *
 * ### 输出
 * - context.stageOutputs["{xmlNodeId}_ma"] = MaConvergenceResult（兼容 BounceReversalNode）
 * - context.stageOutputs["{xmlNodeId}_check"] = MarketMaCheckResult（兼容报告）
 * - return value = MarketMaUnifiedResult（完整合并结果）
 */
class MarketMaUnifiedNode(
    private val threshold: Double = 0.02,
    private val checkMode: String = "full"
) : BaseNode<Any, MarketMaUnifiedNode.MarketMaUnifiedResult>("market_ma_unified", "大盘均线统一检查", NodeType.FACTOR_COMPUTE) {

    companion object {
        private const val TAG = "MarketMaUnified"
        private const val INDEX_CODE = "sh000001"
        private const val OSCILLATION_DAYS = 5
        private const val OSCILLATION_MIN_COUNT = 3
        private const val AMPLITUDE_THRESHOLD = 0.02
    }

    /**
     * 合并输出：同时包含 MaConvergenceResult + MarketMaCheckResult 的所有栏位
     */
    data class MarketMaUnifiedResult(
        // ── 来自 MaConvergenceResult ──
        val maConverged: Boolean = false,
        val maConvergedAndUp: Boolean = false,
        val maDivergence: Double = 1.0,
        val oscillationHarvest: Boolean = false,
        val oscillationCount: Int = 0,
        val volumeDivergence: Boolean = false,
        val gapRiskHigh: Boolean = false,
        val gapPct: Double = 0.0,
        val riskLevel: String = "MEDIUM",
        // ── 来自 MarketMaCheckResult ──
        val isConvergedUpward: Boolean = false,
        val ma5Slope: Double = 0.0,
        val divergencePct: Double = 0.0,
        // ── 共用 ──
        val ma5: Double = 0.0,
        val ma10: Double = 0.0,
        val ma20: Double = 0.0,
        val description: String = "",
        val hint: String = ""
    ) {
        /** 转为旧版 MaConvergenceResult（向下兼容 BounceReversalNode） */
        fun toMaConvergenceResult(): MaConvergenceResult = MaConvergenceResult(
            maConverged = maConverged,
            maConvergedAndUp = maConvergedAndUp,
            maDivergence = maDivergence,
            ma5 = ma5, ma20 = ma20, ma30 = 0.0,
            oscillationHarvest = oscillationHarvest,
            oscillationCount = oscillationCount,
            volumeDivergence = volumeDivergence,
            gapRiskHigh = gapRiskHigh,
            gapPct = gapPct,
            riskLevel = riskLevel,
            hint = hint
        )

        /** 转为旧版 MarketMaCheckResult（向下兼容 DagTradeExecutor / 报告） */
        fun toMarketMaCheckResult(): MarketMaCheckResult = MarketMaCheckResult(
            isConvergedUpward = isConvergedUpward,
            ma5 = ma5, ma10 = ma10, ma20 = ma20,
            divergencePct = divergencePct,
            ma5Slope = ma5Slope,
            description = description
        )
    }

    override suspend fun execute(context: PipelineContext, input: Any): MarketMaUnifiedResult {
        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val snaps = db.dailySnapshotDao().getByCode(INDEX_CODE, 35)

            if (snaps.size < 20) {
                context.log(nodeId, "$nodeName: 指数数据不足(${snaps.size}条)，跳过")
                return MarketMaUnifiedResult(description = "数据不足")
            }

            val sorted = snaps.sortedBy { it.date }
            val closes = sorted.map { it.close }

            // ═══ 1. 均线粘合度（共用 MaConvergenceAnalyzer） ═══
            val maResult = MaConvergenceAnalyzer.analyze(sorted, threshold)
            val maConverged = maResult.converged
            val divergence = maResult.divergencePct / 100.0
            val ma5 = maResult.ma5
            val ma10 = maResult.ma10 ?: 0.0
            val ma20 = maResult.ma20 ?: 0.0
            val convergedAndUp = maResult.convergedAndUp

            // ═══ 2. 多头排列 + 斜率 ═══
            val isBullishAligned = ma5 > ma10 && ma10 > ma20
            val ma5Prev = if (closes.size >= 8) closes.takeLast(8).take(5).average() else ma5
            val slope = if (ma5Prev > 0) (ma5 - ma5Prev) / ma5Prev else 0.0

            // ═══ 3. 震荡收割 + 量价背离（共用工具） ═══
            val micro = MarketMicrostructureAnalyzer.analyze(sorted)

            // ═══ 4. 缺口风险 ═══
            val today = sorted.last()
            val prevDay = sorted[sorted.size - 2]
            val gapPct = if (prevDay.close > 0) (today.open - prevDay.close) / prevDay.close else 0.0
            val gapRiskHigh = gapPct > 0.01 && !maConverged

            // ═══ 综合判断 ═══
            val riskLevel = when {
                gapRiskHigh && micro.oscillationHarvest -> "HIGH"
                micro.oscillationHarvest || micro.volumeDivergence -> "MEDIUM"
                maConverged && convergedAndUp -> "LOW"
                maConverged -> "LOW"
                else -> "MEDIUM"
            }

            val isConvergedUpward = isBullishAligned &&
                kotlin.math.abs(divergence) < (threshold * 2) && slope > 0

            val desc = buildString {
                append("MA5=${"%.2f".format(ma5)} MA10=${"%.2f".format(ma10)} MA20=${"%.2f".format(ma20)}")
                append(" 离散=${"%.2f".format(divergence * 100)}%(阈${"%.1f".format(threshold * 100)}%)")
                append(" 斜率=${"%.2f".format(slope * 100)}%")
                append(if (isBullishAligned) " 多头✓" else " 非多头")
                append(if (maConverged) " 粘合✓" else " 未粘合")
                append(if (slope > 0) " 向上✓" else " 向下")
            }

            val hint = buildString {
                if (convergedAndUp) append("✅ 均线粘合向上(离散${"%.1f".format(divergence * 100)}%) → 蓄势突破 ")
                else if (maConverged) append("🟡 均线粘合(离散${"%.1f".format(divergence * 100)}%) 待方向 ")
                else append("⚠️ 均线分散(离散${"%.1f".format(divergence * 100)}%) ")
                if (micro.oscillationHarvest) append("⚠️ 震荡收割(${micro.oscillationCount}天) ")
                if (micro.volumeDivergence) append("⚠️ 量价背离 ")
                if (gapRiskHigh) append("⚠️ 缺口风险 ")
            }.trim()

            context.log(nodeId, "📐 $nodeName: $desc → ${if (isConvergedUpward) "适合进场" else "不宜进场"}")

            val result = MarketMaUnifiedResult(
                maConverged = maConverged,
                maConvergedAndUp = convergedAndUp,
                maDivergence = divergence,
                oscillationHarvest = micro.oscillationHarvest,
                oscillationCount = micro.oscillationCount,
                volumeDivergence = micro.volumeDivergence,
                gapRiskHigh = gapRiskHigh,
                gapPct = gapPct,
                riskLevel = riskLevel,
                isConvergedUpward = isConvergedUpward,
                ma5Slope = slope * 100,
                divergencePct = divergence * 100,
                ma5 = ma5, ma10 = ma10, ma20 = ma20,
                description = desc,
                hint = hint
            )

            // 向下兼容：同时存入旧版结果到 stageOutputs
            // 下游 BounceReversalNode 读 "n_ma_conv"，DagTradeExecutor 读 "n_market_ma"
            // 使用 XML nodeId（由 DagPipeline 存入 stageOutputs[nodeId]）
            // 这里额外存入兼容 key
            context.setStageOutput("n_ma_conv", result.toMaConvergenceResult())
            context.setStageOutput("n_market_ma", result.toMarketMaCheckResult())

            result
        } catch (e: Exception) {
            Log.e(TAG, "大盘均线统一检查异常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 异常(${e.message})")
            MarketMaUnifiedResult(description = "异常: ${e.message}")
        }
    }
}
