package com.chin.stockanalysis.strategy.topology.nodes

import android.util.Log
import com.chin.stockanalysis.stock.database.StockDatabase
import com.chin.stockanalysis.strategy.analysis.MaConvergenceAnalyzer
import com.chin.stockanalysis.strategy.topology.core.*

/**
 * ## 均線粘合 + 震蕩收割偵測節點 (MaConvergenceNode)
 *
 * 分析大盤（上證 sh000001）的均線粘合度和近期震蕩收割模式，
 * 為下游節點（BounceReversal / GenerateOrders）提供「是否適合進場」的信號。
 *
 * ### 核心邏輯
 * 1. **均線粘合度**：MA5/MA20/MA30 離散率 < 1.5% → 粘合（趨勢修復完成）
 * 2. **震蕩收割模式**：近 5 天 ≥3 天「高開低走 + 振幅 > 2%」→ 量化來回收割
 * 3. **量價背離**：下跌日均量 > 上漲日均量 × 1.3 → 主力出貨
 * 4. **缺口風險**：當日跳空高開 > 1% 且均線未粘合 → 缺口回補風險高
 *
 * ### 輸出
 * [MaConvergenceResult] 存入 context.setStageOutput("n_ma_conv", result)
 * 下游讀取：context.getStageOutput<MaConvergenceResult>("n_ma_conv")
 *
 * ### 位置
 * 所有周期 XML Layer 1（依賴 n_import，與 n_pool 並行）
 */
class MaConvergenceNode : PipelineNode<Any, MaConvergenceResult> {

    companion object {
        private const val TAG = "MaConvergenceNode"
        private const val INDEX_CODE = "sh000001"
        private const val CONVERGENCE_THRESHOLD = 0.015  // 1.5% 離散率
        private const val OSCILLATION_DAYS = 5
        private const val OSCILLATION_MIN_COUNT = 3
        private const val AMPLITUDE_THRESHOLD = 0.02     // 2% 振幅
    }

    override val nodeId: String = "ma_convergence"
    override val nodeName: String = "均線粘合偵測"
    override val nodeType: NodeType = NodeType.FACTOR_COMPUTE

    override suspend fun execute(context: PipelineContext, input: Any): MaConvergenceResult {
        return try {
            val db = StockDatabase.getInstance(context.androidContext)
            val dao = db.dailySnapshotDao()
            val snaps = dao.getByCode(INDEX_CODE, 35)  // 需要 30+ 天算 MA30

            if (snaps.size < 30) {
                context.log(nodeId, "$nodeName: 指數數據不足(${snaps.size}條)，跳過")
                return MaConvergenceResult()
            }

            // 按日期升序排列（最舊在前）
            val sorted = snaps.sortedBy { it.date }

            // ═══ 1. 均線粘合 + 方向（共用工具） ═══
            val maResult = MaConvergenceAnalyzer.analyze(sorted)
            val maConverged = maResult.converged
            val divergence = maResult.divergencePct / 100.0
            val ma5 = maResult.ma5
            val ma20 = maResult.ma20 ?: 0.0
            val convergedAndUp = maResult.convergedAndUp

            // ═══ 2. 震蕩收割模式 ═══
            val recent5 = sorted.takeLast(OSCILLATION_DAYS + 1)  // +1 for prevClose
            var oscillationCount = 0
            for (i in 1 until recent5.size) {
                val prev = recent5[i - 1]
                val cur = recent5[i]
                val gapUp = cur.open > prev.close          // 高開
                val fadeDown = cur.close < cur.open        // 低走（收陰）
                val amplitude = if (cur.close > 0) (cur.high - cur.low) / cur.close else 0.0
                if (gapUp && fadeDown && amplitude > AMPLITUDE_THRESHOLD) {
                    oscillationCount++
                }
            }
            val oscillationHarvest = oscillationCount >= OSCILLATION_MIN_COUNT

            // ═══ 3. 量價背離 ═══
            val last5 = sorted.takeLast(OSCILLATION_DAYS)
            val upDays = last5.filter { it.changePct > 0 }
            val downDays = last5.filter { it.changePct < 0 }
            val avgUpVol = if (upDays.isNotEmpty()) upDays.map { it.volume.toDouble() }.average() else 0.0
            val avgDownVol = if (downDays.isNotEmpty()) downDays.map { it.volume.toDouble() }.average() else 0.0
            val volumeDivergence = avgUpVol > 0 && avgDownVol > avgUpVol * 1.3

            // ═══ 4. 缺口風險 ═══
            val today = sorted.last()
            val prevDay = sorted[sorted.size - 2]
            val gapPct = if (prevDay.close > 0) (today.open - prevDay.close) / prevDay.close else 0.0
            val gapRiskHigh = gapPct > 0.01 && !maConverged  // 高開 >1% 且未粘合

            // ═══ 綜合判斷 ═══
            val riskLevel = when {
                gapRiskHigh && oscillationHarvest -> "HIGH"
                oscillationHarvest || volumeDivergence -> "MEDIUM"
                maConverged && convergedAndUp -> "LOW"
                maConverged -> "LOW"
                else -> "MEDIUM"
            }

            val hint = buildString {
                if (convergedAndUp) append("✅ 均線粘合向上(離散${"%.1f".format(divergence * 100)}%) → 蓄勢突破 ")
                else if (maConverged) append("🟡 均線粘合(離散${"%.1f".format(divergence * 100)}%) 待方向 ")
                else append("⚠️ 均線分散(離散${"%.1f".format(divergence * 100)}%) ")
                if (oscillationHarvest) append("⚠️ 震蕩收割(${oscillationCount}天高開低走) ")
                if (volumeDivergence) append("⚠️ 量價背離(跌量>漲量) ")
                if (gapRiskHigh) append("⚠️ 缺口回補風險(高開${"%.1f".format(gapPct * 100)}%) ")
                if (riskLevel == "LOW" && !convergedAndUp) append("→ 趨勢修復，可正常進場")
            }

            context.log(nodeId, "📐 $nodeName: $hint")

            MaConvergenceResult(
                maConverged = maConverged,
                maConvergedAndUp = convergedAndUp,
                maDivergence = divergence,
                ma5 = ma5, ma20 = ma20, ma30 = 0.0,
                oscillationHarvest = oscillationHarvest,
                oscillationCount = oscillationCount,
                volumeDivergence = volumeDivergence,
                gapRiskHigh = gapRiskHigh,
                gapPct = gapPct,
                riskLevel = riskLevel,
                hint = hint.trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "均線粘合偵測異常: ${e.message}", e)
            context.log(nodeId, "⚠ $nodeName: 異常(${e.message})")
            MaConvergenceResult()
        }
    }
}

/**
 * 均線粘合 + 震蕩收割偵測結果
 */
data class MaConvergenceResult(
    /** MA5/MA20/MA30 是否粘合（離散率 < 1.5%） */
    val maConverged: Boolean = false,
    /** 均線粘合且向上（蓄勢突破） */
    val maConvergedAndUp: Boolean = false,
    /** 均線離散率 */
    val maDivergence: Double = 1.0,
    val ma5: Double = 0.0,
    val ma20: Double = 0.0,
    val ma30: Double = 0.0,
    /** 是否存在震蕩收割模式（近5天≥3天高開低走+大振幅） */
    val oscillationHarvest: Boolean = false,
    val oscillationCount: Int = 0,
    /** 量價背離（跌量 > 漲量 × 1.3） */
    val volumeDivergence: Boolean = false,
    /** 缺口回補風險（高開 >1% 且均線未粘合） */
    val gapRiskHigh: Boolean = false,
    val gapPct: Double = 0.0,
    /** 綜合風險等級：LOW / MEDIUM / HIGH */
    val riskLevel: String = "MEDIUM",
    /** 文字提示（可直接展示在報告） */
    val hint: String = ""
)
