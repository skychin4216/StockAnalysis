package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.MaConvergenceAnalyzer
import com.chin.stockanalysis.strategy.analysis.MarketMicrostructureAnalyzer
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 均线粘合 + 震荡收割侦测节点 (MaConvergenceNode)
 *
 * 分析大盘（上证 sh000001）的均线粘合度和近期震荡收割模式，
 * 为下游节点（BounceReversal / GenerateOrders）提供「是否适合进场」的信号。
 *
 * ### 核心逻辑
 * 1. **均线粘合度**：MA5/MA20/MA30 离散率 < 1.5% → 粘合（趋势修复完成）
 * 2. **震荡收割模式**：近 5 天 ≥3 天「高开低走 + 振幅 > 2%」→ 量化来回收割
 * 3. **量价背离**：下跌日均量 > 上涨日均量 × 1.3 → 主力出货
 * 4. **缺口风险**：当日跳空高开 > 1% 且均线未粘合 → 缺口回补风险高
 *
 * ### 输出
 * [MaConvergenceResult] 存入 context.setStageOutput("n_ma_conv", result)
 * 下游读取：context.getStageOutput<MaConvergenceResult>("n_ma_conv")
 *
 * ### 位置
 * 所有周期 XML Layer 1（依赖 n_import，与 n_pool 并行）
 */
class MaConvergenceNode : BaseNode<Any, MaConvergenceResult>("ma_convergence", "均线粘合侦测", NodeType.FACTOR_COMPUTE) {

    companion object {
        private const val TAG = "MaConvergenceNode"
        private const val INDEX_CODE = "sh000001"
        private const val CONVERGENCE_THRESHOLD = 0.015  // 1.5% 离散率
        private const val OSCILLATION_DAYS = 5
        private const val OSCILLATION_MIN_COUNT = 3
        private const val AMPLITUDE_THRESHOLD = 0.02     // 2% 振幅
    }

    override suspend fun execute(context: PipelineContext, input: Any): MaConvergenceResult {
        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val dao = db.dailySnapshotDao()
            val snaps = dao.getByCode(INDEX_CODE, 35)  // 需要 30+ 天算 MA30

            if (snaps.size < 30) {
                context.log(nodeId, "$nodeName: 指数数据不足(${snaps.size}条)，跳过")
                return MaConvergenceResult()
            }

            // 按日期升序排列（最旧在前）
            val sorted = snaps.sortedBy { it.date }

            // ═══ 1. 均线粘合 + 方向（共用工具） ═══
            val maResult = MaConvergenceAnalyzer.analyze(sorted)
            val maConverged = maResult.converged
            val divergence = maResult.divergencePct / 100.0
            val ma5 = maResult.ma5
            val ma20 = maResult.ma20 ?: 0.0
            val convergedAndUp = maResult.convergedAndUp

            // ═══ 2. 震荡收割 + 量价背离 + 缺口风险（共用工具） ═══
            val micro = MarketMicrostructureAnalyzer.analyze(sorted)

            // 缺口风险（MaConvergenceNode 用更精确的计算）
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

            val hint = buildString {
                if (convergedAndUp) append("✅ 均线粘合向上(离散${"%.1f".format(divergence * 100)}%) → 蓄势突破 ")
                else if (maConverged) append("🟡 均线粘合(离散${"%.1f".format(divergence * 100)}%) 待方向 ")
                else append("⚠️ 均线分散(离散${"%.1f".format(divergence * 100)}%) ")
                if (micro.oscillationHarvest) append("⚠️ 震荡收割(${micro.oscillationCount}天高开低走) ")
                if (micro.volumeDivergence) append("⚠️ 量价背离(跌量>涨量) ")
                if (gapRiskHigh) append("⚠️ 缺口回补风险(高开${"%.1f".format(gapPct * 100)}%) ")
                if (riskLevel == "LOW" && !convergedAndUp) append("→ 趋势修复，可正常进场")
            }

            context.log(nodeId, "📐 $nodeName: $hint")

            MaConvergenceResult(
                maConverged = maConverged,
                maConvergedAndUp = convergedAndUp,
                maDivergence = divergence,
                ma5 = ma5, ma20 = ma20, ma30 = 0.0,
                oscillationHarvest = micro.oscillationHarvest,
                oscillationCount = micro.oscillationCount,
                volumeDivergence = micro.volumeDivergence,
                gapRiskHigh = gapRiskHigh,
                gapPct = gapPct,
                riskLevel = riskLevel,
                hint = hint.trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "均线粘合侦测异常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 异常(${e.message})")
            MaConvergenceResult()
        }
    }
}

/**
 * 均线粘合 + 震荡收割侦测结果
 */
data class MaConvergenceResult(
    /** MA5/MA20/MA30 是否粘合（离散率 < 1.5%） */
    val maConverged: Boolean = false,
    /** 均线粘合且向上（蓄势突破） */
    val maConvergedAndUp: Boolean = false,
    /** 均线离散率 */
    val maDivergence: Double = 1.0,
    val ma5: Double = 0.0,
    val ma20: Double = 0.0,
    val ma30: Double = 0.0,
    /** 是否存在震荡收割模式（近5天≥3天高开低走+大振幅） */
    val oscillationHarvest: Boolean = false,
    val oscillationCount: Int = 0,
    /** 量价背离（跌量 > 涨量 × 1.3） */
    val volumeDivergence: Boolean = false,
    /** 缺口回补风险（高开 >1% 且均线未粘合） */
    val gapRiskHigh: Boolean = false,
    val gapPct: Double = 0.0,
    /** 综合风险等级：LOW / MEDIUM / HIGH */
    val riskLevel: String = "MEDIUM",
    /** 文字提示（可直接展示在报告） */
    val hint: String = ""
)
